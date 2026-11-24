package com.lightdecoder.imessage.direct

import android.util.Log
import com.lightdecoder.imessage.Redact
import com.lightdecoder.imessage.direct.AppleAccountRules.Next
import com.lightdecoder.imessage.direct.AppleAccountRules.Page
import com.lightdecoder.imessage.direct.AppleAccountRules.Problem
import com.lightdecoder.imessage.direct.AppleAccountRules.Refusal
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The optional Apple Account (SPEC2 §4), in the ":imessage" process, owned by [DirectEngine] and run
 * under its lock: the sign-in with its two-factor steps, the commit, the sign-out, and what the
 * engine has it do at a start and a new registration. Only "iCloud services only": the registration
 * stays this phone's number alone, so nothing of iMessage changes with it.
 *
 * - Strictly opt-in: with no account on the phone nothing here calls the library, writes a file or
 *   tells the main process anything ([atStartLocked], [sessionUpLocked], [newDeviceLocked]).
 * - The password is held here only, in memory, while a sign-in may need it again (a `NeedsLogin`
 *   answer is tried once more with it): let go of once signed in, on [cancel], after
 *   [AppleAccountRules.CREDENTIALS_MS], and when the screen goes ([dropCredentials]). Never written
 *   anywhere, logged, or sent to the other process.
 * - The commit and the sign-out are local, and take effect by restarting the process
 *   ([Hooks.restart]): the library rebuilds its session from disk, with the saved account or
 *   without it. The sign-out never signs out at Apple. A sign-in left part-way after it worked on
 *   the session (its connection taken, FaceTime's state wiped) also ends in a restart ([cancel]).
 * - A restart is only ever made while one of LightDecoder's own screens is in sight, when Android
 *   allows iMessage's service to be started ([restartPending] says which moments). One that cannot
 *   be made then simply waits, pending, for the next such moment: no timer, no retry, nothing done
 *   by itself.
 * - Nothing of it while a FaceTime call is on ([Hooks.busy]).
 */
class AppleAccount internal constructor(
    private val files: AccountFiles,
    private val bridge: AccountBridge,
    private val lock: Mutex,
    private val hooks: Hooks,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineContext = Dispatchers.IO,
    /** Where this account's own work runs (tests: a dispatcher of virtual time). */
    work: CoroutineContext = Dispatchers.Default,
) {

    /** What the sign-in needs of the engine. */
    interface Hooks {
        /** A FaceTime call is on (ringing, being placed, or joined): nothing of the account changes then. */
        val busy: Boolean

        /** This phone's number is registered with iMessage: there is a session to sign in on. */
        val registered: Boolean

        /** The relay the user set up (the sign-in contacts it), as an address. */
        val relayHost: String

        /**
         * Restarts the ":imessage" process (§4.4): iMessage's service is started first, and should
         * Android refuse, nothing else is done ([ProcessRestart.Outcome.FAILED]: nothing held, nobody
         * told). Then once nothing holds it: a start or a registration under way is let finish, no
         * FaceTime call may be on (looked at again under the calls' guard), and the main process's
         * requests being answered are let finish. [wanted] is looked at again then (a registration
         * since may have made it needless, a sign-in begun meanwhile); [last] is done right before the
         * process ends. [ProcessRestart.Outcome.KEPT_BACK] when something held it (nothing done).
         */
        suspend fun restart(wanted: () -> Boolean, last: suspend () -> Unit): ProcessRestart.Outcome

        /**
         * Tells the main process where the account stands (the "account" update); [restarting]: right
         * before the process ends for its restart (the main process then checks that it came back).
         */
        suspend fun tell(state: AccountState, restarting: Boolean)

        /** Deletes the Keystore keys that are the account's own (never the registration's). */
        fun deleteAccountKeys()

        /**
         * A sign-out's first step (§4.5 step 1): the account's features stop (the import, Focus status
         * asked for and shared, what they keep). Nothing for an engine without them.
         */
        suspend fun stopFeatures() = Unit
    }

    sealed class SignOut {
        object Done : SignOut()
        object NotSignedIn : SignOut()
        data class Refused(val why: Refusal) : SignOut()
    }

    private val statePath: String get() = files.statePath

    /** Whatever escapes the work below (a file that could not be written, say) is logged, never a crash of the process. */
    private val failures = CoroutineExceptionHandler { _, e -> Log.w(TAG, "Apple Account: work failed (${e::class.java.simpleName})") }
    private val scope = CoroutineScope(SupervisorJob() + work + failures)

    /** Whether the session has iCloud's services, as read at the last start; false until then. */
    @Volatile
    private var icloud = false

    private val _state = MutableStateFlow(stateOf(files.readFlag()))

    /** Where the account stands ([DirectWire.ACCOUNT]'s answer). */
    val state: StateFlow<AccountState> = _state

    /**
     * Signed in or out here (or a sign-in left that worked on the session), and the process not
     * restarted for it yet. It is made only at a moment one of LightDecoder's own screens is in sight,
     * exactly these:
     * - what the user does on the sign-in screen or the iMessage screen: "OK" on "Signed in"
     *   ([finishSignIn]), leaving the sign-in ([cancel]), the "Signed out" message read ([restartNow]);
     * - either of those screens coming up ([screenShown]);
     * - the FaceTime call screen ending the call that kept it back ([callScreenEnded]);
     * - one of the main process's screens coming up, which it says with its "visible" ACCOUNT
     *   request ([screenInSight]), once answered.
     * At any other moment it waits. Not kept on disk: a process that ends has restarted. The main
     * process is told when it becomes pending (so its screens say when they are in sight), and the
     * ACCOUNT answer says it.
     */
    @Volatile
    var restartPending = false
        private set

    /** The last try at [restartPending] was kept back by a FaceTime call (so the call screen ending it may try again). */
    @Volatile
    var restartHeldByCall = false
        private set

    /** Whether a refusal of this pending restart was logged already: once per pending restart, never per try. */
    private var refusalLogged = false

    // --- the password: in memory only, and not for long ----------------------------------------------

    private class Credentials(val appleId: String, val password: String, val atMs: Long)

    @Volatile
    private var credentials: Credentials? = null
    private var expiry: Job? = null

    private fun keepCredentials(appleId: String, password: String) {
        credentials = Credentials(appleId, password, now())
        expiry?.cancel()
        expiry = scope.launch {
            delay(AppleAccountRules.CREDENTIALS_MS)
            dropCredentials()
        }
    }

    /** The password, while it may still be used. */
    private fun credentials(): Credentials? {
        val c = credentials ?: return null
        if (now() - c.atMs >= AppleAccountRules.CREDENTIALS_MS || now() < c.atMs) {
            dropCredentials()
            return null
        }
        return c
    }

    /** Tests: whether a password is held at all. */
    internal val holdsPassword: Boolean get() = credentials != null

    /** Lets go of the Apple ID and password typed (the screen went, or the sign-in no longer needs them). */
    fun dropCredentials() {
        credentials = null
        expiry?.cancel()
        expiry = null
    }

    // --- a sign-in under way ---------------------------------------------------------------------------

    private enum class Stage { PASSWORD, SEND_DEVICE, DEVICE_CODE, SEND_TEXT, TEXT_CODE }

    /** One sign-in: the library's objects it holds, and how far it got. */
    private class Flow(val hardware: AccountBridge.Hardware, val again: Boolean) {
        var connection: AccountBridge.Connection? = null
        var watcher: AccountBridge.Native? = null
        var idms: AccountBridge.Native? = null
        var account: AccountBridge.Native? = null
        var circle: AccountBridge.Native? = null
        var textBody: AccountBridge.Native? = null
        var numbers: List<AccountBridge.TrustedNumber> = emptyList()
        var number: AccountBridge.TrustedNumber? = null
        var retried = false
        var user = false
        var icloudTried = false

        /** The user typed the password for it. */
        var typed = false

        /** A sign-in with a password ran: what was saved before is gone (the library wipes it first). */
        var replacedSaved = false

        /** "Before you sign in" was read and "I understand" ticked: a password may be typed (S6). */
        var understood = false

        /**
         * The live session was worked on: its connection taken (`reusableConnection`), or FaceTime's
         * state wiped with the account. However the sign-in ends, iMessage restarts after it (S12).
         */
        var touched = false

        /** Let go of ([cleanup]): what it held is closed, and nothing of it is done again. */
        var cleaned = false
        var done = false
        var page: Page = Page.Credentials()

        /** What one try at the account holds. */
        fun closeAuth() {
            circle = close(circle)
            textBody = close(textBody)
            account = close(account)
            numbers = emptyList()
            number = null
        }

        fun closeAll() {
            closeAuth()
            watcher = close(watcher)
            idms = close(idms)
            connection?.let {
                it.conn.close()
                it.anisette.close()
            }
            connection = null
            hardware.config.close()
        }

        private fun close(n: AccountBridge.Native?): AccountBridge.Native? {
            try {
                n?.close()
            } catch (e: Throwable) {
                // Let go of either way.
            }
            return null
        }
    }

    private val flowLock = Any()
    private var flow: Flow? = null
    private var cleaning: Job? = null

    /**
     * How many times the user left a sign-in ([cancel]; under [flowLock]): a [begin] asked for before
     * the last of them, and still waiting for the engine's lock then, begins nothing (no sign-in is
     * left behind with no screen to end it, which would hold every restart back).
     */
    private var left = 0

    private fun currentFlow(): Flow? = synchronized(flowLock) { flow }
    private fun takeFlow(): Flow? = synchronized(flowLock) { flow.also { flow = null } }

    /** The page the sign-in is at (a screen made again comes back to it); null with none under way. */
    val page: Page? get() = currentFlow()?.page

    private fun page(f: Flow, p: Page): Page {
        f.page = p
        return p
    }

    private fun refusal(): Refusal? = when {
        hooks.busy -> Refusal.BUSY
        !hooks.registered -> Refusal.NOT_REGISTERED
        else -> null
    }

    /** [block] under the engine's lock, off the main thread; whatever it throws is a page saying so. */
    private suspend fun locked(block: suspend () -> Page): Page = withContext(io) {
        lock.withLock {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Apple Account: a sign-in step failed (${e::class.java.simpleName})")
                val f = currentFlow()
                if (f == null) Page.Refused(Refusal.NOT_STARTED) else resetToPassword(f, problemOf(e))
            }
        }
    }

    /** "Sign in with your Apple Account": the page to start from, or why not. */
    suspend fun begin(): Page {
        val asked = synchronized(flowLock) { left }
        return locked { beginLocked(asked) }
    }

    /** Whether the user left a sign-in since [asked] was read. */
    private fun leftSince(asked: Int): Boolean = synchronized(flowLock) { left != asked }

    private fun beginLocked(asked: Int): Page {
        // Left while this waited for the lock: nothing begun.
        if (leftSince(asked)) return Page.Refused(Refusal.NOT_STARTED)
        // One under way (the screen was made again): where it is.
        currentFlow()?.let { return it.page }
        refusal()?.let { return Page.Refused(it) }
        val flag = files.readFlag()
        // Only from a signed-out phone (or one whose account needs a new sign-in): a sign-in with a
        // password wipes what is saved first.
        if (flag.state == FlagState.SIGNED_IN || flag.state == FlagState.COMMITTING) return Page.Refused(Refusal.SIGNED_IN)
        val hw = try {
            bridge.readHardware(statePath)
        } catch (e: Throwable) {
            Log.w(TAG, "Apple Account: this device's configuration was not read (${e::class.java.simpleName})")
            null
        } ?: return Page.Refused(Refusal.NOT_REGISTERED)
        val f = Flow(hw, again = flag.state == FlagState.ATTENTION)
        // Left meanwhile ([cancel] takes no lock): never stored, its configuration let go of.
        val stored = synchronized(flowLock) { (left == asked).also { if (it) flow = f } }
        if (!stored) {
            f.cleaned = true
            try {
                f.closeAll()
            } catch (e: Throwable) {
                // Let go of either way.
            }
            return Page.Refused(Refusal.NOT_STARTED)
        }
        // Straight to "Continue as" only when there is a saved account to continue with (on this very
        // device: a new registration wipes it). Otherwise "Before you sign in", every time (S6).
        return page(f, if (f.again && files.accountSaved()) credentialsPage(f) else introPage(f))
    }

    private fun introPage(f: Flow) = Page.Intro(deviceOf(f.hardware), hooks.relayHost)

    private fun deviceOf(hw: AccountBridge.Hardware): AccountBridge.Device? = try {
        bridge.getDeviceInfo(hw.config).let {
            AccountBridge.Device(AppleAccountRules.plain(it.name, MAX_DEVICE), AppleAccountRules.plain(it.serial, MAX_DEVICE), AppleAccountRules.plain(it.os, MAX_DEVICE))
        }.takeIf { it.name.isNotEmpty() || it.serial.isNotEmpty() }
    } catch (e: Throwable) {
        null
    }

    private fun credentialsPage(f: Flow, problem: Problem? = null, iforgot: Boolean = false): Page.Credentials {
        val saved = if (f.again && !f.replacedSaved && files.accountSaved()) {
            try {
                bridge.getAvailableUser(statePath)?.let(AppleAccountRules::mask)
            } catch (e: Throwable) {
                null
            }
        } else {
            null
        }
        return Page.Credentials(saved, problem, iforgot)
    }

    /** "I understand", ticked, and Continue: the Apple ID and password. */
    suspend fun understood(): Page = locked {
        val f = currentFlow()
        when {
            refusal() != null -> Page.Refused(refusal()!!)
            f == null -> Page.Refused(Refusal.NOT_STARTED)
            else -> {
                f.understood = true
                page(f, credentialsPage(f))
            }
        }
    }

    /** Signs in with [appleId] and [password] (S11: only from a signed-out phone; S6: only once "I understand" was ticked). */
    suspend fun signIn(appleId: String, password: String): Page = locked { signInLocked(appleId, password) }

    private suspend fun signInLocked(appleId: String, password: String): Page {
        refusal()?.let { return Page.Refused(it) }
        val f = currentFlow() ?: return Page.Refused(Refusal.NOT_STARTED)
        val flag = files.readFlag()
        if (flag.state == FlagState.SIGNED_IN || flag.state == FlagState.COMMITTING) return Page.Refused(Refusal.SIGNED_IN)
        // A password goes nowhere before "Before you sign in" was read for this sign-in (nothing of it is kept).
        if (!f.understood) return page(f, introPage(f))
        val id = appleId.trim().lowercase(Locale.ROOT)
        if (id.isEmpty() || password.isEmpty()) return page(f, credentialsPage(f, Problem.Missing))
        val wait = flag.limits.passwordWaitMs(now())
        if (wait > 0) return page(f, credentialsPage(f, Problem.TooManyAttempts(wait), iforgot = true))
        // The library wipes what was saved before it signs in with a password: from here, nothing is.
        if (flag.state == FlagState.ATTENTION) {
            writeFlag(flag.copy(state = FlagState.NONE, sinceMs = 0))
            // Nothing of the account it replaces is kept for whichever account comes next (S5).
            wipeAccountSecrets()
        }
        f.replacedSaved = true
        f.typed = true
        f.retried = false
        keepCredentials(id, password)
        publish()
        return authenticate(f, useCredentials = true)
    }

    /** "Continue as …": what this phone saved, signed in to again without the password (from `attention` only). */
    suspend fun continueAsSaved(): Page = locked { continueLocked() }

    private suspend fun continueLocked(): Page {
        refusal()?.let { return Page.Refused(it) }
        val f = currentFlow() ?: return Page.Refused(Refusal.NOT_STARTED)
        if (!f.again || f.replacedSaved || !files.accountSaved()) return page(f, credentialsPage(f, Problem.EnterPassword))
        // The saved sign-in stands in for the password (S3): S7's limit holds for it as for a typed one.
        val wait = files.readFlag().limits.passwordWaitMs(now())
        if (wait > 0) return page(f, credentialsPage(f, Problem.TooManyAttempts(wait), iforgot = true))
        f.typed = false
        f.retried = false
        return authenticate(f, useCredentials = false)
    }

    /** The live session's connection, and Apple's two-factor listeners on it (before any code is asked for, so none is missed). */
    private suspend fun connection(f: Flow): AccountBridge.Connection {
        f.connection?.let { return it }
        // From here the live session is worked on: the sign-in ends with a restart, whatever its end (S12).
        f.touched = true
        val c = bridge.reusableConnection()
        var watcher: AccountBridge.Native? = null
        try {
            watcher = bridge.subscribeConn(c.conn)
            val idms = bridge.makeIdms(c.conn)
            f.watcher = watcher
            f.idms = idms
        } catch (e: Throwable) {
            // All three, or none: a sign-in never goes on without the listeners.
            watcher?.close()
            c.conn.close()
            c.anisette.close()
            throw e
        }
        f.connection = c
        return c
    }

    private suspend fun authenticate(f: Flow, useCredentials: Boolean): Page {
        val c = if (useCredentials) credentials() ?: return page(f, credentialsPage(f, Problem.Expired)) else null
        val conn = try {
            connection(f)
        } catch (e: Throwable) {
            return failed(f, e, Stage.PASSWORD)
        }
        f.closeAuth()
        val auth = try {
            bridge.tryAuth(statePath, f.hardware.config, conn.conn, conn.anisette, c?.appleId, c?.password)
        } catch (e: Throwable) {
            return failed(f, e, Stage.PASSWORD)
        }
        f.account = auth.account
        f.user = try {
            bridge.tryIcloudLogin(statePath, f.hardware.config, auth.account)
        } catch (e: Throwable) {
            return failed(f, e, Stage.PASSWORD)
        }
        f.icloudTried = true
        return dispatch(f, auth.step)
    }

    /** Where the library's answer [step] leads (§4.3). */
    private suspend fun dispatch(f: Flow, step: AccountBridge.Step): Page {
        val progress = AppleAccountRules.Progress(
            user = f.user,
            icloudTried = f.icloudTried,
            retried = f.retried,
            credentials = f.typed && credentials() != null,
            circle = f.circle != null,
        )
        return when (val next = AppleAccountRules.next(step, progress)) {
            Next.Commit -> commit(f)
            Next.IcloudLogin -> {
                val account = f.account ?: return resetToPassword(f, Problem.EnterPassword)
                f.user = try {
                    bridge.tryIcloudLogin(statePath, f.hardware.config, account)
                } catch (e: Throwable) {
                    return failed(f, e, Stage.PASSWORD)
                }
                f.icloudTried = true
                if (f.user) commit(f) else resetToPassword(f, Problem.Incomplete)
            }
            Next.Incomplete -> resetToPassword(f, Problem.Incomplete)
            Next.RetryPassword -> {
                f.retried = true
                authenticate(f, useCredentials = true)
            }
            Next.WrongPassword -> {
                countFailure()
                resetToPassword(f, Problem.WrongPassword, iforgot = true)
            }
            Next.EnterPassword -> {
                // Apple refused the saved sign-in ("Continue as"): a failed attempt, as a wrong password is (S7).
                if (!f.typed && step is AccountBridge.Step.NeedsLogin) countFailure()
                resetToPassword(f, Problem.EnterPassword)
            }
            Next.SendToDevices -> sendToDevices(f)
            Next.EnterDeviceCode -> page(f, Page.DeviceCode(sent = true))
            Next.TextOptions -> textOptions(f)
            is Next.EnterTextCode -> {
                if (f.textBody !== next.body) f.textBody?.close()
                f.textBody = next.body
                page(f, Page.TextCode(f.number?.display, resendAtMs = textResendAt()))
            }
            is Next.AppleMessage -> {
                end(f)
                Page.AppleMessage(next.text)
            }
        }
    }

    // --- codes on the trusted devices ---------------------------------------------------------------

    /** Asks for codes on the user's trusted devices again (one a minute, five an hour at most: each shows on all of them). */
    suspend fun resendToDevices(): Page = locked {
        val f = currentFlow()
        when {
            refusal() != null -> Page.Refused(refusal()!!)
            f == null -> Page.Refused(Refusal.NOT_STARTED)
            else -> sendToDevices(f)
        }
    }

    private suspend fun sendToDevices(f: Flow): Page {
        val account = f.account ?: return resetToPassword(f, Problem.EnterPassword)
        val flag = files.readFlag()
        val wait = flag.limits.deviceCodeWaitMs(now())
        if (wait > 0) return page(f, Page.DeviceCode(sent = f.circle != null, problem = Problem.CodeWait(wait)))
        // Counted before it goes: one that fails still showed on the devices.
        writeFlag(flag.copy(limits = flag.limits.deviceCodeSent(now())))
        val r = try {
            bridge.send2faToDevices(account, connection(f).conn)
        } catch (e: Throwable) {
            return failed(f, e, Stage.SEND_DEVICE)
        }
        f.circle?.close()
        f.circle = r.circle
        return when (r.step) {
            is AccountBridge.Step.NeedsDevice2Fa, is AccountBridge.Step.Needs2FaVerification -> page(f, Page.DeviceCode(sent = true))
            else -> dispatch(f, r.step)
        }
    }

    /** The code shown on the trusted devices; also "Try Again" after Apple did not confirm it in time. */
    suspend fun verifyDeviceCode(code: String): Page = locked { verifyDeviceLocked(code) }

    private suspend fun verifyDeviceLocked(code: String): Page {
        refusal()?.let { return Page.Refused(it) }
        val f = currentFlow() ?: return Page.Refused(Refusal.NOT_STARTED)
        val circle = f.circle
        val account = f.account
        val watcher = f.watcher
        val idms = f.idms
        if (circle == null || account == null || watcher == null || idms == null) return page(f, Page.DeviceCode(sent = false))
        val digits = code.trim()
        if (!CODE.matches(digits)) return page(f, Page.DeviceCode(sent = true, problem = Problem.CodeFormat))
        val r = try {
            bridge.verify2fa(statePath, circle, f.hardware.config, account, watcher, idms, digits)
        } catch (e: Throwable) {
            return failed(f, e, Stage.DEVICE_CODE)
        }
        f.user = r.user
        f.icloudTried = false
        return dispatch(f, r.step)
    }

    // --- a code by text message ---------------------------------------------------------------------

    /** "Text me a code instead": the trusted numbers (one is texted at once). */
    suspend fun textMeInstead(): Page = locked {
        val f = currentFlow()
        when {
            refusal() != null -> Page.Refused(refusal()!!)
            f == null -> Page.Refused(Refusal.NOT_STARTED)
            else -> textOptions(f)
        }
    }

    private suspend fun textOptions(f: Flow): Page {
        val account = f.account ?: return resetToPassword(f, Problem.EnterPassword)
        val r = try {
            bridge.get2faSmsOpts(account)
        } catch (e: Throwable) {
            return failed(f, e, Stage.SEND_TEXT)
        }
        // How Apple writes them is shown: plain text, short, a few.
        f.numbers = r.numbers.take(MAX_NUMBERS).map { it.copy(display = AppleAccountRules.plain(it.display, MAX_NUMBER_CHARS)) }
        val next = r.next
        if (next != null && next !is AccountBridge.Step.NeedsSms2Fa) return dispatch(f, next)
        return when (f.numbers.size) {
            0 -> page(f, Page.TextNumbers(emptyList(), Problem.NoNumbers))
            1 -> sendText(f, f.numbers[0])
            else -> page(f, Page.TextNumbers(f.numbers.map { it.display }))
        }
    }

    /** Texts a code to the [index]th trusted number. */
    suspend fun chooseNumber(index: Int): Page = locked {
        val f = currentFlow()
        val number = f?.numbers?.getOrNull(index)
        when {
            refusal() != null -> Page.Refused(refusal()!!)
            f == null -> Page.Refused(Refusal.NOT_STARTED)
            number == null -> page(f, Page.TextNumbers(f.numbers.map { it.display }))
            else -> sendText(f, number)
        }
    }

    /** "Resend Code": the same number again (one a minute, five an hour at most). */
    suspend fun resendText(): Page = locked {
        val f = currentFlow()
        val number = f?.number
        when {
            refusal() != null -> Page.Refused(refusal()!!)
            f == null -> Page.Refused(Refusal.NOT_STARTED)
            number == null -> textOptions(f)
            else -> sendText(f, number)
        }
    }

    private suspend fun sendText(f: Flow, number: AccountBridge.TrustedNumber): Page {
        val account = f.account ?: return resetToPassword(f, Problem.EnterPassword)
        val flag = files.readFlag()
        val wait = flag.limits.textCodeWaitMs(now())
        if (wait > 0) {
            val problem = Problem.CodeWait(wait)
            return page(f, if (f.textBody != null) Page.TextCode(f.number?.display, problem, textResendAt()) else Page.TextNumbers(f.numbers.map { it.display }, problem))
        }
        writeFlag(flag.copy(limits = flag.limits.textCodeSent(now())))
        val step = try {
            bridge.send2faSms(f.circle, account, number.id)
        } catch (e: Throwable) {
            return failed(f, e, Stage.SEND_TEXT)
        }
        // The library ended the trusted devices' session with it (if there was one): let go of here too.
        f.circle?.close()
        f.circle = null
        f.number = number
        return dispatch(f, step)
    }

    /** The texted code. */
    suspend fun verifyTextCode(code: String): Page = locked { verifyTextLocked(code) }

    private suspend fun verifyTextLocked(code: String): Page {
        refusal()?.let { return Page.Refused(it) }
        val f = currentFlow() ?: return Page.Refused(Refusal.NOT_STARTED)
        val account = f.account
        val body = f.textBody
        if (account == null || body == null) return textOptions(f)
        val digits = code.trim()
        if (!CODE.matches(digits)) return page(f, Page.TextCode(f.number?.display, Problem.CodeFormat, textResendAt()))
        val r = try {
            bridge.verify2faSms(statePath, account, f.hardware.config, body, digits)
        } catch (e: Throwable) {
            return failed(f, e, Stage.TEXT_CODE)
        }
        f.user = r.user
        f.icloudTried = false
        return dispatch(f, r.step)
    }

    private fun textResendAt(): Long = now() + files.readFlag().limits.textCodeWaitMs(now())

    // --- how a sign-in ends -----------------------------------------------------------------------------

    /**
     * Signed in (§4.4, steps 1-4): marked as committing, everything of the sign-in let go of (the
     * two-factor session too: it is not used to join the iCloud Keychain), the library's logs deleted,
     * then marked as signed in. iMessage restarts once the user saw it ([finishSignIn]).
     */
    private fun commit(f: Flow): Page {
        // Left while Apple answered (the screen closed during a step): nothing is kept of it, never signed in for it.
        if (currentFlow() !== f) {
            cleanup(f)
            return Page.Refused(Refusal.NOT_STARTED)
        }
        // A fresh sign-in (maybe another Apple ID) finds nothing an account before it kept (S5). Its
        // files only: a Keystore key the library may have made for this very sign-in stays.
        if (!f.again || f.replacedSaved) {
            try {
                files.deleteAccountSecrets()
            } catch (e: Throwable) {
                Log.w(TAG, "Apple Account: an earlier account's files were not all deleted (${e::class.java.simpleName})")
            }
        }
        val flag = files.readFlag()
        writeFlag(flag.copy(state = FlagState.COMMITTING, sinceMs = now(), notice = null))
        dropCredentials()
        f.closeAll()
        deleteLogsQuietly()
        writeFlag(files.readFlag().copy(state = FlagState.SIGNED_IN))
        f.done = true
        icloud = false
        publish()
        // "OK" restarts iMessage once "Signed in" is read; should it not come (left with Home, a
        // FaceTime call first), the restart waits for one of LightDecoder's screens to come up (the
        // main process is told, so its screens say when they are).
        wantRestart()
        Log.i(TAG, "Apple Account: signed in")
        return page(f, Page.Done)
    }

    /**
     * "OK" on "Signed in": the main process is told, and iMessage restarts. False when it was kept
     * back (a FaceTime call: it is then made once the call screen ends the call) or could not be
     * made: it stays pending for the next moment one of LightDecoder's screens is in sight.
     */
    suspend fun finishSignIn(): Boolean {
        endDone()
        return restartNow()
    }

    /** Whether iMessage may restart now (no FaceTime call on). */
    val canRestart: Boolean get() = !hooks.busy

    /** [finishSignIn], once the screen that asked for it has gone (so it is not the one shown again after the restart). */
    fun finishSignInSoon() {
        scope.launch { finishSignIn() }
    }

    /**
     * "OK" on "Signed in" while a FaceTime call is on: the sign-in is over, and iMessage restarts once
     * the call screen ends the call ([callScreenEnded]), or at the next moment one of LightDecoder's
     * screens comes up.
     */
    fun finishSignInAfterCall() {
        endDone()
        heldByCall(true)
    }

    /**
     * "Signed in" closed some other way than with "OK" (the screen swiped away, not in sight): the
     * sign-in is over; the restart waits for the next moment one of LightDecoder's screens comes up.
     */
    fun finishSignInLater() {
        endDone()
    }

    /** A sign-in that was committed is not under way any more. */
    private fun endDone() {
        synchronized(flowLock) { if (flow?.done == true) flow = null }
    }

    /** One restart at a time. */
    private val restarting = Mutex()

    /**
     * A moment one of LightDecoder's screens was in sight ([trySoon]) not tried for yet: one that came
     * while another try ran gets one try of its own once that one is over ([drain]), never more.
     */
    private val again = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Screens showing a message about the account that their "OK" closes and that restarts iMessage
     * ("Signed out", with what to do at Apple): no other moment restarts it under them, which would
     * take the message away unread ([reading]).
     */
    private val readers: MutableSet<Any> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * [owner] shows ([on]) a message about the account that its "OK" closes and restarts iMessage
     * after, or no longer shows it (closed, or the screen gone: always said, or no restart is made
     * while the process lives).
     */
    fun reading(owner: Any, on: Boolean) {
        if (on) readers.add(owner) else readers.remove(owner)
    }

    /**
     * Restarts iMessage for a sign-in or sign-out done here, or a sign-in left part-way that worked on
     * the session: one try, now, for something the user did on one of LightDecoder's screens. True
     * when nothing is pending any more. False when it was kept back (a FaceTime call, a start or
     * registration under way, a request of the main process being answered) or Android refused to
     * start iMessage's service (the process then goes on as it was, nothing held, nobody told): it
     * stays pending for the next moment one of LightDecoder's screens is in sight. Just before the
     * process ends, the library's logs are deleted (they may hold the account's tokens) and the main
     * process is told. Done in this account's own scope: a screen that asked for it and goes
     * meanwhile never cancels it.
     */
    suspend fun restartNow(): Boolean = scope.async(io) {
        restarting.withLock { attempt() }
        // A screen came up while it was tried: its own try.
        drain()
        !restartPending
    }.await()

    /**
     * The sign-in screen or the iMessage screen came up: a pending restart is made now (Android allows
     * iMessage's service to be started while one of LightDecoder's screens is in sight). Nothing when
     * none is pending. One being tried already is let end first; should it not have restarted, this
     * moment gets one try of its own.
     */
    fun screenShown() = trySoon()

    /**
     * One of the main process's screens came up (its ACCOUNT request said so, and was answered): as
     * [screenShown]. The only request of the main process that may restart iMessage; with nothing
     * pending (no account, nothing signed in or out here) it does nothing at all.
     */
    fun screenInSight() = trySoon()

    private fun trySoon() {
        if (!restartPending) return
        again.set(true)
        scope.launch(io) { drain() }
    }

    /**
     * Tries once for each moment [trySoon] noted, one at a time: whoever holds [restarting] tries for
     * the moments noted meanwhile before letting go of it, and looks again once it did (a moment
     * noted just then). Nothing is ever tried that no moment asked for.
     */
    private suspend fun drain() {
        while (again.get() && restarting.tryLock()) {
            try {
                while (again.getAndSet(false)) {
                    if (attempt()) break
                }
            } finally {
                restarting.unlock()
            }
        }
    }

    /** One try, under [restarting]: true when nothing is pending any more. */
    private suspend fun attempt(): Boolean {
        if (!restartPending) return true
        // A sign-in's step on screen, or a message to be read: never under the user's hands (each ends
        // in a restart of its own).
        if (underWay() || readers.isNotEmpty()) return false
        if (hooks.busy) {
            heldByCall(true)
            return false
        }
        val outcome = try {
            hooks.restart({ restartPending && !underWay() && readers.isEmpty() }) {
                deleteLogsQuietly()
                tellQuietly(restarting = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Apple Account: the restart failed (${e::class.java.simpleName})")
            ProcessRestart.Outcome.FAILED
        }
        when (outcome) {
            ProcessRestart.Outcome.RESTARTED -> synchronized(flowLock) {
                restartPending = false
                heldByCall(false)
            }
            // Not needed any more (pending no longer), or a sign-in began meanwhile (still pending).
            ProcessRestart.Outcome.NOT_WANTED -> Unit
            ProcessRestart.Outcome.KEPT_BACK -> heldByCall(hooks.busy)
            ProcessRestart.Outcome.FAILED -> {
                val first = synchronized(flowLock) { !refusalLogged.also { refusalLogged = true } }
                if (first) Log.w(TAG, "Apple Account: iMessage did not restart now; it does the next time one of LightDecoder's screens comes up")
            }
        }
        return !restartPending
    }

    /** Whether the last try was kept back by a FaceTime call; the call screen ending it then tries again ([callScreenEnded]). */
    private fun heldByCall(held: Boolean) {
        restartHeldByCall = held
        waitingForCall = if (held) this else waitingForCall?.takeIf { it !== this }
    }

    /**
     * iMessage is to restart (signed in or out, or a sign-in left that worked on the session): pending,
     * made at the next of [restartPending]'s moments. The library's logs are marked to be deleted at
     * the next start, however the process ends ([AccountFiles.logsMarker]). With [tell], the main
     * process is told where the account stands now (so its screens say when they are in sight); a
     * sign-out tells it itself once done.
     */
    private fun wantRestart(tell: Boolean = true) {
        synchronized(flowLock) {
            if (!restartPending) refusalLogged = false
            restartPending = true
        }
        try {
            files.markLogsToDelete()
        } catch (e: Throwable) {
            Log.w(TAG, "Apple Account: the library's logs were not marked to be deleted (${e::class.java.simpleName})")
        }
        if (tell) scope.launch { tellQuietly() }
    }

    /**
     * The keychain join (B6.5) took the live session's connection, and no new session holds it: as
     * after a sign-in that worked on the session (S12), iMessage restarts the next moment it may.
     */
    internal fun liveSessionTouched() = wantRestart()

    /** A sign-in is under way: its screen shows a step of it (once committed it is not). */
    private fun underWay(): Boolean = currentFlow()?.let { !it.done } ?: false

    /**
     * The user left the sign-in ("Not Now", back, OK on Apple's words or the terms): all of it is let
     * go of, and nothing it began is kept. If it worked on the session (its connection taken,
     * FaceTime's state wiped), iMessage restarts now, as after a commit (S12, §4.3 step 2). With
     * [restart] false (the screen closed while not in sight, swiped away), the restart waits for the
     * next moment one of LightDecoder's screens comes up.
     */
    fun cancel(restart: Boolean = true) {
        dropCredentials()
        // A [begin] still waiting for the lock begins nothing now.
        val f = synchronized(flowLock) {
            left++
            flow.also { flow = null }
        }
        cleaning = scope.launch {
            if (f != null) withContext(io) { lock.withLock { cleanup(f) } }
            if (restart && restartPending) restartNow()
        }
    }

    /** Tests: waits for what [cancel] left to do. */
    internal suspend fun settled() {
        cleaning?.join()
    }

    /** Ends [f] now (under the lock); the restart it may need waits for the user to leave the screen ([cancel]). */
    private fun end(f: Flow) {
        synchronized(flowLock) { if (flow === f) flow = null }
        dropCredentials()
        cleanup(f)
    }

    private fun cleanup(f: Flow) {
        if (f.cleaned) return
        f.cleaned = true
        f.closeAll()
        if (f.done) return
        try {
            if (cleanHalf()) f.touched = true
            val flag = files.readFlag()
            // A new sign-in from "needs attention" replaced what was saved, and did not finish: signed out now.
            if (f.again && f.replacedSaved && flag.state == FlagState.NONE) {
                writeFlag(flag.copy(notice = AccountNotice.SIGNED_OUT))
                wipeAccountSecrets()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Apple Account: an unfinished sign-in was not put away (${e::class.java.simpleName})")
        }
        publish()
        // Leaving the screen restarts iMessage at once ([cancel]); should the user not leave it that way
        // (Home from Apple's words or the terms page, and the screen kept), the restart waits for one
        // of LightDecoder's screens to come up (the main process is told, so its screens say when).
        if (f.touched) wantRestart()
    }

    /**
     * What the library saved for a sign-in that was not committed is not kept (§4.4's table would wipe
     * it at the next start). True when it was wiped (FaceTime's state with it: a restart follows).
     */
    private fun cleanHalf(): Boolean {
        if (files.readFlag().state != FlagState.NONE || !files.accountSaved()) return false
        try {
            bridge.resetAppleLogin(statePath)
        } catch (e: Throwable) {
            Log.w(TAG, "Apple Account: an unfinished sign-in was not wiped (${e::class.java.simpleName})")
        }
        return true
    }

    /** Back to the Apple ID and password, the password let go of and what this try began undone. */
    private fun resetToPassword(f: Flow, problem: Problem?, iforgot: Boolean = false): Page {
        dropCredentials()
        f.closeAuth()
        f.typed = false
        if (cleanHalf()) f.touched = true
        publish()
        return page(f, credentialsPage(f, problem, iforgot))
    }

    /**
     * S5: LightDecoder's own secrets of the account (its files but the flag, and its Keystore key)
     * deleted, wherever the account goes without a sign-out: it vanished at a start, a new device
     * could not wipe it, a new sign-in from "needs attention" replaced it. Never a failure of what called it.
     */
    private fun wipeAccountSecrets() {
        try {
            files.deleteAccountSecrets()
        } catch (e: Throwable) {
            Log.w(TAG, "Apple Account: its files were not all deleted (${e::class.java.simpleName})")
        }
        try {
            hooks.deleteAccountKeys()
        } catch (e: Throwable) {
            Log.w(TAG, "Apple Account: its key was not deleted (${e::class.java.simpleName})")
        }
    }

    private fun countFailure() {
        val flag = files.readFlag()
        writeFlag(flag.copy(limits = flag.limits.passwordFailed(now())))
    }

    private fun problemOf(e: Throwable): Problem =
        Problem.Apple(AppleAccountRules.plain(Redact.secrets(DirectEngine.readable(e)), AppleAccountRules.MAX_APPLE_TEXT))

    /** A step that threw: Apple's new terms, a code not confirmed in time, or anything else (counted as a failed attempt). */
    private fun failed(f: Flow, e: Throwable, stage: Stage): Page {
        if (e is CancellationException) throw e
        Log.w(TAG, "Apple Account: ${stage.name.lowercase(Locale.ROOT)} failed (${e::class.java.simpleName})")
        when (AppleAccountRules.classify(DirectEngine.readable(e))) {
            AppleAccountRules.ErrorKind.TERMS -> {
                end(f)
                return Page.Terms
            }
            AppleAccountRules.ErrorKind.TIMEOUT -> if (stage == Stage.DEVICE_CODE) return page(f, Page.DeviceCode(sent = true, timedOut = true))
            AppleAccountRules.ErrorKind.OTHER -> Unit
        }
        val problem = problemOf(e)
        countFailure()
        val wait = files.readFlag().limits.passwordWaitMs(now())
        if (stage == Stage.PASSWORD) return resetToPassword(f, problem, iforgot = true)
        if (wait > 0) return resetToPassword(f, Problem.TooManyAttempts(wait), iforgot = true)
        return page(
            f,
            when (stage) {
                Stage.TEXT_CODE -> Page.TextCode(f.number?.display, problem, textResendAt())
                Stage.SEND_TEXT -> when {
                    f.textBody != null -> Page.TextCode(f.number?.display, problem, textResendAt())
                    f.numbers.isNotEmpty() -> Page.TextNumbers(f.numbers.map { it.display }, problem)
                    else -> Page.DeviceCode(sent = f.circle != null, problem = problem)
                }
                else -> Page.DeviceCode(sent = f.circle != null, problem = problem)
            },
        )
    }

    // --- signing out (§4.5): on this phone only -------------------------------------------------------------

    /**
     * Signs out on this phone: the library's saved account wiped (`resetAppleLogin`, never a sign-out
     * at Apple), anything set aside for a next device removed, LightDecoder's account files and the
     * account's own Keystore key deleted, the main process told. iMessage then restarts
     * ([restartNow]) without iCloud's services. Never while a FaceTime call is on.
     */
    suspend fun signOut(): SignOut = scope.async(io) { signOutWork() }.await()

    /** [signOut]'s work, in this account's own scope: a screen that asked for it and goes meanwhile never cuts it short. */
    private suspend fun signOutWork(): SignOut {
        if (hooks.busy) return SignOut.Refused(Refusal.BUSY)
        val result = lock.withLock {
            if (hooks.busy) return@withLock SignOut.Refused(Refusal.BUSY)
            // Anything of an account still on disk (left by a way out that was not a sign-out) can still be signed out of (S5).
            if (files.readFlag().state == FlagState.NONE && !files.accountSaved() && !files.accountSecretsLeft()) return@withLock SignOut.NotSignedIn
            takeFlow()?.let {
                dropCredentials()
                it.closeAll()
            }
            // The account's features stop first (the import, Focus status asked for and shared).
            try {
                hooks.stopFeatures()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Apple Account: its features did not all stop (${e::class.java.simpleName})")
            }
            inClique = false
            try {
                bridge.resetAppleLogin(statePath)
            } catch (e: Throwable) {
                // Left on disk, it is wiped at the next start (an account with no flag is an interrupted sign-in).
                Log.w(TAG, "Apple Account: the saved account was not wiped (${e::class.java.simpleName})")
            }
            icloud = false
            // The screen restarts iMessage once its "Signed out" message is read; should it not (left
            // with Home, a FaceTime call first), the restart waits for one of LightDecoder's screens to
            // come up. Asked for here, in this account's own scope, as soon as the saved account is gone
            // and before anything that waits or can fail: nothing after it can lose it. The main
            // process is told below, once the rest is done.
            wantRestart(tell = false)
            try {
                val setAside = try {
                    bridge.getAvailableUser(statePath) != null
                } catch (e: Throwable) {
                    false
                }
                if (setAside) {
                    files.deleteSetAside()
                    val still = try {
                        bridge.getAvailableUser(statePath) != null
                    } catch (e: Throwable) {
                        false
                    }
                    if (still) Log.w(TAG, "Apple Account: a set-aside sign-in is still offered")
                }
                files.deleteAccountFiles()
            } catch (e: Throwable) {
                Log.w(TAG, "Apple Account: its files were not all deleted (${e::class.java.simpleName})")
            }
            try {
                hooks.deleteAccountKeys()
            } catch (e: Throwable) {
                Log.w(TAG, "Apple Account: its key was not deleted (${e::class.java.simpleName})")
            }
            publish()
            Log.i(TAG, "Apple Account: signed out on this phone")
            SignOut.Done
        }
        if (result == SignOut.Done) tellQuietly()
        return result
    }

    // --- what the engine has it do ----------------------------------------------------------------------

    /**
     * At a start, before the session is restored (§4.4's table; under the engine's lock). With no
     * account and no flag: nothing at all, as before there was a sign-in.
     */
    internal fun atStartLocked() {
        // Never in the way of iMessage itself: whatever goes wrong here, the session is restored next.
        try {
            startTable()
        } catch (e: Throwable) {
            Log.w(TAG, "Apple Account: what a start found was not put right (${e::class.java.simpleName})")
        }
        publish()
    }

    private fun startTable() {
        if (currentFlow() != null) return
        // What the library logged while the last process still held an account it was to let go of
        // (signed out, or a sign-in left), should that process have ended some other way than by its
        // restart: deleted now, once. Never there for a user who never signed in.
        if (files.logsToDelete()) {
            deleteLogsQuietly()
            files.logsDeleted()
        }
        val flag = files.readFlag()
        when (AppleAccountRules.atStart(files.accountSaved(), flag.state)) {
            AppleAccountRules.StartAction.NOTHING -> return
            AppleAccountRules.StartAction.RESET_INTERRUPTED -> {
                try {
                    bridge.resetAppleLogin(statePath)
                } catch (e: Throwable) {
                    Log.w(TAG, "Apple Account: an interrupted sign-in was not wiped (${e::class.java.simpleName})")
                }
                deleteLogsQuietly()
                // A registration that could not wipe the account says why itself ([newDeviceLocked]).
                writeFlag(flag.copy(notice = if (flag.notice == AccountNotice.SIGN_IN_AGAIN) AccountNotice.SIGN_IN_AGAIN else AccountNotice.INTERRUPTED))
            }
            AppleAccountRules.StartAction.FINISH_COMMIT -> {
                deleteLogsQuietly()
                writeFlag(flag.copy(state = FlagState.SIGNED_IN))
            }
            AppleAccountRules.StartAction.NORMAL -> deleteLogsQuietly()
            AppleAccountRules.StartAction.VANISHED -> {
                // Still signed in as far as the flag goes: what the library logged meanwhile goes too.
                deleteLogsQuietly()
                writeFlag(
                    flag.copy(
                        state = FlagState.NONE,
                        sinceMs = 0,
                        notice = if (flag.notice == AccountNotice.SIGN_IN_AGAIN) AccountNotice.SIGN_IN_AGAIN else AccountNotice.SIGNED_OUT,
                    ),
                )
                wipeAccountSecrets()
            }
        }
    }

    /** Once the session came up at a start: whether it has iCloud's services, and the main process told. Nothing without an account. */
    internal suspend fun sessionUpLocked() {
        try {
            if (files.readFlag().state == FlagState.NONE) return
            icloud = try {
                bridge.hasIcloudServices()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                false
            }
            publish()
            scope.launch { tellQuietly() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Never a failure of iMessage's start.
            Log.w(TAG, "Apple Account: its state was not read at the start (${e::class.java.simpleName})")
        }
    }

    /**
     * S10: every registration makes a new device, so an account saved here belongs to one that is
     * gone. It is wiped before [setIdentity] makes the new one, and the user is asked to sign in again.
     * Never in the way of the registration: should the wipe fail, the flag says signed out, so the next
     * start wipes it as an interrupted sign-in (before the session is restored from disk).
     */
    internal suspend fun newDeviceLocked(setIdentity: suspend () -> Unit) {
        try {
            takeFlow()?.let {
                dropCredentials()
                it.closeAll()
                it.cleaned = true
            }
            val flag = files.readFlag()
            val saved = files.accountSaved()
            var wiped = !saved
            if (saved) {
                try {
                    bridge.resetAppleLogin(statePath)
                    wiped = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "Apple Account: the old device's account was not wiped (${e::class.java.simpleName})")
                }
            }
            if (saved || flag.state != FlagState.NONE) deleteLogsQuietly()
            if (flag.state != FlagState.NONE) {
                writeFlag(flag.copy(state = if (wiped) FlagState.ATTENTION else FlagState.NONE, notice = AccountNotice.SIGN_IN_AGAIN))
                if (!wiped) wipeAccountSecrets()
                icloud = false
                publish()
                scope.launch { tellQuietly() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Apple Account: a new registration's step failed (${e::class.java.simpleName})")
        }
        setIdentity()
    }

    /**
     * A registration put its new session in place (built with no account, on a connection of its
     * own): a restart still pending for the old one is not needed any more.
     */
    internal fun sessionReplacedLocked() {
        synchronized(flowLock) {
            restartPending = false
            heldByCall(false)
        }
    }

    // --- what the screens read ------------------------------------------------------------------------------

    /** What the iMessage screen says once ([noticeShown] once it did). */
    fun notice(): AccountNotice? = files.readFlag().notice

    suspend fun noticeShown() = withContext(io) {
        lock.withLock {
            try {
                val flag = files.readFlag()
                if (flag.notice != null) writeFlag(flag.copy(notice = null))
            } catch (e: Throwable) {
                // Said again next time: nothing worse.
                Log.w(TAG, "Apple Account: the notice was not marked as shown (${e::class.java.simpleName})")
            }
        }
    }

    /** The Apple ID, masked (`j•••@icloud.com`), from what the library saved; null when signed out or not there. */
    suspend fun maskedName(): String? = withContext(io) {
        if (files.readFlag().state == FlagState.NONE || !files.accountSaved()) return@withContext null
        try {
            bridge.getAvailableUser(statePath)?.let(AppleAccountRules::mask)
        } catch (e: Throwable) {
            null
        }
    }

    /** Whether this phone is in the account's iCloud Keychain, as last read (B6); false without an account. */
    @Volatile
    internal var inClique = false

    /** Whether the session has iCloud's services, as read at the last start (B6's features need them). */
    val hasIcloud: Boolean get() = icloud

    private fun stateOf(flag: AccountFlag): AccountState {
        val signedIn = flag.state != FlagState.NONE
        val ic = signedIn && icloud
        // The features (B6): only signed in, not needing a sign-in, with iCloud's services.
        val on = ic && flag.state == FlagState.SIGNED_IN
        return AccountState(
            signedIn = signedIn,
            attention = flag.state == FlagState.ATTENTION,
            icloud = ic,
            clique = on && inClique,
            focus = on && flag.focusIn && !flag.focusUnavailable,
            history = on && flag.history == AccountFlag.HISTORY_ON,
            profiles = on && flag.profilesIn,
            profile = on && flag.profileShare && files.myProfileFile.isFile,
        )
    }

    /**
     * An account-gated call (Name & Photo, Focus status, Messages in iCloud) failed with an
     * authentication error ([AppleAccountRules.isAuthFailure]): Apple no longer takes the account's
     * sign-in, so it needs the user's sign-in again (S7, §4.8), offered with one tap ("Continue as").
     * Marked once, under the lock (in this account's own scope: never in the way of what failed, nor
     * waiting on a lock it may hold); nothing is retried here, and nothing loops: the features are off
     * from then on, so no further call fails for it.
     */
    fun authFailed() {
        scope.launch(io) {
            val marked = lock.withLock {
                val flag = files.readFlag()
                if (flag.state != FlagState.SIGNED_IN || !files.accountSaved()) return@withLock false
                writeFlag(flag.copy(state = FlagState.ATTENTION, notice = AccountNotice.SIGN_IN_AGAIN))
                publish()
                true
            }
            if (marked) {
                Log.i(TAG, "Apple Account: Apple no longer takes the sign-in; needs attention")
                tellQuietly()
            }
        }
    }

    /** A feature's setting or state changed (B6): published, and the main process told. Nothing without an account. */
    fun featuresChanged() {
        publish()
        if (files.readFlag().state != FlagState.NONE || _state.value.signedIn) scope.launch { tellQuietly() }
    }

    private fun publish() {
        _state.value = stateOf(files.readFlag())
    }

    private fun writeFlag(flag: AccountFlag) = files.writeFlag(flag)

    /** The library's logs (`<statePath>/logs*`, which may hold the account's tokens), deleted; never a failure of what asked. */
    private fun deleteLogsQuietly() {
        try {
            files.deleteLibraryLogs()
        } catch (e: Throwable) {
            Log.w(TAG, "Apple Account: the library's logs were not deleted (${e::class.java.simpleName})")
        }
    }

    /** One telling at a time, so the main process is told in order and the last it hears is the latest. */
    private val telling = Mutex()

    /**
     * Tells the main process where the account stands now (read when it is told), [restarting] right
     * before the process ends for its restart; never a failure of what asked.
     */
    private suspend fun tellQuietly(restarting: Boolean = false) {
        try {
            telling.withLock { hooks.tell(_state.value, restarting) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Apple Account: the main process was not told (${e::class.java.simpleName})")
        }
    }

    companion object {
        private const val TAG = "AppleAccount"
        private val CODE = Regex("[0-9]{6}")
        private const val MAX_NUMBERS = 10
        private const val MAX_NUMBER_CHARS = 40
        private const val MAX_DEVICE = 64

        /** The account whose last restart try a FaceTime call kept back ([heldByCall]); null with none. */
        @Volatile
        private var waitingForCall: AppleAccount? = null

        /**
         * The FaceTime call screen ends a call while in sight: a restart that call kept back is made
         * now. Nothing at all otherwise (no account is made or read for it).
         */
        fun callScreenEnded() {
            val a = waitingForCall ?: return
            if (a.restartPending && a.restartHeldByCall) a.trySoon()
        }

        /** Tests: the account the sign-in screen and the iMessage screen use instead of the engine's. */
        @Volatile
        internal var testInstance: AppleAccount? = null

        /** The engine's (":imessage" process only). */
        fun of(context: android.content.Context): AppleAccount = testInstance ?: DirectEngine.get(context).account
    }
}
