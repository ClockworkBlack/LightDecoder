package com.lightdecoder.imessage.direct

import android.util.Log
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.rust_bridge.AppleAccountHandle
import uniffi.rust_bridge.CircleSession
import uniffi.rust_bridge.Disposable
import uniffi.rust_bridge.EventPipeline
import uniffi.rust_bridge.IdmsListener
import uniffi.rust_bridge.LoginResult
import uniffi.rust_bridge.PushWatcher
import uniffi.rust_bridge.RestoredHardware
import uniffi.rust_bridge.ReusableConnection
import uniffi.rust_bridge.SharedPushStateHandle

/**
 * "Messages in iCloud" > "Turn On…" (B6.5): joining this phone to the account's iCloud Keychain, the
 * only place the session is rebuilt with the account's two-factor session (the "reconfigure" path),
 * and only after its own screen, "I understand", and an approval on the user's Apple device.
 *
 * What it never does: reset or set up a new iCloud Keychain, join with another device's passcode
 * (`joinCliqueWithBottle`, `getBottles`), carry a previous device over, or sign out at Apple. It
 * joins only by the approving device's voucher (the library's `circleSetupClique` after a device
 * approval); as every member does, this phone gets an escrow record of its own, protected by a
 * random 6-digit code shown once ([Page.Code]) and kept (Keystore-encrypted) only if the user ticks
 * "Keep it on this phone". It fails closed: any answer it does not expect (no device approval,
 * Advanced Data Protection on, the keychain not joined after all) ends it with Messages in iCloud off.
 *
 * The commit (§B6.5 step 3) is marked first (`{"state":"committing","history":"joining"}`, and
 * [AccountFiles.joinMarker], which nothing else touches): should the process end in the window where
 * the registration was deleted, the next start re-registers the phone's number alone from its kept
 * user ([Steps.recover], [needsRecovery]) and signs the account out, so iMessage is never left
 * unregistered. Should that fail too (no network at a start), the marker stays and every start tries
 * again; only once the number is registered again is the account signed out here and the marker gone.
 * Once begun, the commit runs to its end whatever the screen does (it is not cancelled with it).
 */
class KeychainJoin(
    private val steps: Steps,
    private val files: AccountFiles,
    private val lock: Mutex,
    private val busy: () -> Boolean,
    private val allowed: () -> Boolean,
    /** Keeps the code, encrypted ([AccountFiles.keychainCodeFile]). */
    private val keepCode: (String) -> Unit,
    /** The account's state changed (told to the main process). */
    private val changed: () -> Unit,
    /** The live session's connection was taken for the join and no new session holds it: iMessage restarts (as after a sign-in, S12). */
    private val touched: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val random: () -> String = ::newCode,
) {

    /** The library, as the join uses it (tests: a fake). Each may throw. */
    interface Steps {
        /** Whether the session can be rebuilt at all (iMessage connected, the phone's number kept): asked before any device approval. */
        fun ready(): Boolean = true

        /** `tryAuth` with the saved sign-in (no password): where it leads. Takes the live session's connection (`reusableConnection`). */
        suspend fun authSaved(): AccountBridge.Step

        /** `send2faToDevices`: an approval asked for on the user's devices. */
        suspend fun sendToDevices(): AccountBridge.Step

        /** `verify2fa` with the code shown on the device. */
        suspend fun verify(code: String): AccountBridge.Verified

        /**
         * `resetState(logout = false)`, `registerIds` (the phone's number alone), `startSession` with the
         * account and its two-factor session. Throws [Untouched] for a failure before `resetState`
         * (nothing was deleted), anything else after it.
         */
        suspend fun reconfigure()

        /** `circleSetupClique` with this phone's new escrow code. */
        suspend fun setupClique(code: String): Boolean

        /** `isInClique`. */
        suspend fun inClique(): Boolean

        /** The phone's number registered again alone, as before the account (the crash window, or a failed rebuild). */
        suspend fun recover()

        /** `resetAppleLogin`: the account wiped on this phone (after [recover]). */
        fun signOutHere()

        /** Lets go of what a join held. */
        fun close()
    }

    sealed class Page {
        object Intro : Page()
        data class DeviceCode(val problem: String? = null, val waitMs: Long = 0) : Page()

        /** Joined: this phone's code, shown once. */
        data class Code(val code: String) : Page()
        object Done : Page()
        /** [waitMs]: with [Why.TOO_MANY], how long until the saved sign-in may be tried again. */
        data class Failed(val why: Why, val text: String? = null, val waitMs: Long = 0) : Page()
    }

    enum class Why { BUSY, NOT_ALLOWED, NOT_READY, SIGN_IN_AGAIN, ADP, NOT_JOINED, FAILED, TOO_MANY }

    /** [Steps.reconfigure] failed before it deleted anything: the registration and the session are as they were. */
    class Untouched(cause: Throwable) : Exception(cause.message, cause)

    @Volatile
    private var understood = false

    @Volatile
    private var sentAt = 0L

    @Volatile
    private var shownCode: String? = null

    /** A step is under way ([guarded]): the screen going away does not let go of what it uses. */
    @Volatile
    private var running = false

    /** The screen went while a step was under way: let go of once it ends (unless it committed). */
    @Volatile
    private var leftMeanwhile = false

    /** The live session's connection was taken ([Steps.authSaved]) and no new session holds it yet. */
    @Volatile
    private var touchedLive = false

    /** The first page, or why not. A code not yet seen (the screen was made again) is shown again. */
    fun begin(): Page {
        shownCode?.let { return Page.Code(it) }
        if (running) return Page.Failed(Why.BUSY)
        understood = false
        return when {
            busy() -> Page.Failed(Why.BUSY)
            !allowed() -> Page.Failed(Why.NOT_ALLOWED)
            else -> Page.Intro
        }
    }

    /** "I understand" ticked and "Continue": the saved sign-in, then an approval asked for on the user's devices. */
    suspend fun understood(): Page = guarded {
        // Nothing the rebuild needs is missing: else not even a device approval is asked for.
        if (!steps.ready()) return@guarded end(Page.Failed(Why.NOT_READY))
        // The saved sign-in is a password's stand-in: S7's password limit holds for it too.
        val wait = files.readFlag().limits.passwordWaitMs(now())
        if (wait > 0) return@guarded end(Page.Failed(Why.TOO_MANY, waitMs = wait))
        understood = true
        touchedLive = true
        when (val step = steps.authSaved()) {
            AccountBridge.Step.NeedsDevice2Fa, AccountBridge.Step.Needs2FaVerification -> sendToDevices()
            // Apple refused the saved sign-in: a failed attempt, as a wrong password is.
            AccountBridge.Step.NeedsLogin -> {
                val flag = files.readFlag()
                files.writeFlag(flag.copy(limits = flag.limits.passwordFailed(now())))
                Log.i(TAG, "the saved sign-in was refused")
                end(Page.Failed(Why.SIGN_IN_AGAIN))
            }
            // Signed in with no device approval (nothing to join with), or only a text message: the
            // join is offered with a device's approval only.
            else -> {
                Log.i(TAG, "the saved sign-in did not ask for a device's approval (${step::class.java.simpleName})")
                end(Page.Failed(Why.SIGN_IN_AGAIN))
            }
        }
    }

    /** Asks the user's devices again (a minute apart at most, five an hour). */
    suspend fun sendAgain(): Page = guarded { sendToDevices() }

    private suspend fun sendToDevices(): Page {
        val flag = files.readFlag()
        val wait = flag.limits.deviceCodeWaitMs(now())
        if (wait > 0) return Page.DeviceCode(waitMs = wait)
        files.writeFlag(flag.copy(limits = flag.limits.deviceCodeSent(now())))
        return when (val step = steps.sendToDevices()) {
            AccountBridge.Step.NeedsDevice2Fa, AccountBridge.Step.Needs2FaVerification -> {
                sentAt = now()
                Page.DeviceCode()
            }
            else -> {
                Log.i(TAG, "no device approval was asked for (${step::class.java.simpleName})")
                end(Page.Failed(Why.SIGN_IN_AGAIN))
            }
        }
    }

    /** The code shown on the user's device: once Apple confirms it, the commit. */
    suspend fun verify(code: String): Page = guarded {
        if (!understood || sentAt == 0L) return@guarded Page.Intro
        val digits = code.trim()
        if (!CODE.matches(digits)) return@guarded Page.DeviceCode(problem = "code")
        val v = steps.verify(digits)
        if (v.step != AccountBridge.Step.LoggedIn) {
            Log.i(TAG, "the device's code did not sign in (${v.step::class.java.simpleName})")
            return@guarded if (v.step is AccountBridge.Step.NeedsDevice2Fa || v.step is AccountBridge.Step.Needs2FaVerification) {
                Page.DeviceCode(problem = "code")
            } else {
                end(Page.Failed(Why.SIGN_IN_AGAIN))
            }
        }
        commit()
    }

    /** Under the engine's lock ([guarded]); once begun, never cancelled (the screen going does not cut it short). */
    private suspend fun commit(): Page = withContext(NonCancellable) { commitWork() }

    private suspend fun commitWork(): Page {
        if (busy()) return end(Page.Failed(Why.BUSY))
        val before = files.readFlag()
        files.mark(files.joinMarker, true)
        files.writeFlag(before.copy(state = FlagState.COMMITTING, history = AccountFlag.HISTORY_JOINING))
        try {
            steps.reconfigure()
            // The new session holds the connection taken.
            touchedLive = false
        } catch (e: Untouched) {
            // Nothing deleted: the account and the session stay as they were, Messages in iCloud off.
            Log.w(TAG, "the session was not rebuilt, nothing was changed (${e.cause?.let { it::class.java.simpleName }})")
            files.writeFlag(files.readFlag().copy(state = before.state, history = AccountFlag.HISTORY_OFF))
            files.mark(files.joinMarker, false)
            changed()
            return end(Page.Failed(Why.FAILED, textOf(e.cause ?: e)))
        } catch (e: Throwable) {
            Log.w(TAG, "the session was not rebuilt (${e::class.java.simpleName}); the number is registered alone again")
            recoverLocked()
            return end(Page.Failed(Why.FAILED, textOf(e)))
        }
        val code = random()
        val joined = try {
            steps.setupClique(code)
            steps.inClique()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "the iCloud Keychain was not joined (${e::class.java.simpleName})")
            files.writeFlag(files.readFlag().copy(state = FlagState.SIGNED_IN, history = AccountFlag.HISTORY_OFF))
            files.mark(files.joinMarker, false)
            changed()
            val text = textOf(e)
            return end(Page.Failed(if (CloudImport.isAdp(text)) Why.ADP else Why.NOT_JOINED, text))
        }
        if (!joined) {
            files.writeFlag(files.readFlag().copy(state = FlagState.SIGNED_IN, history = AccountFlag.HISTORY_OFF))
            files.mark(files.joinMarker, false)
            changed()
            return end(Page.Failed(Why.NOT_JOINED))
        }
        files.writeFlag(files.readFlag().copy(state = FlagState.SIGNED_IN, history = AccountFlag.HISTORY_ON))
        files.mark(files.joinMarker, false)
        understood = false
        sentAt = 0L
        steps.close()
        changed()
        shownCode = code
        return Page.Code(code)
    }

    /** "Done" on the code's page: kept (encrypted) only when [keep]; it is not shown again. */
    fun codeSeen(keep: Boolean): Page {
        val code = shownCode
        shownCode = null
        if (keep && code != null) {
            try {
                keepCode(code)
            } catch (e: Throwable) {
                Log.w(TAG, "the keychain code was not kept (${e::class.java.simpleName})")
            }
        }
        return Page.Done
    }

    /**
     * Left the screen: what the join held is let go of (nothing is committed that was not). A step
     * under way (a commit above all) is let end first: what it holds is let go of then.
     */
    fun cancel() {
        if (running) {
            leftMeanwhile = true
            return
        }
        shownCode = null
        letGo()
    }

    private fun letGo() {
        understood = false
        sentAt = 0L
        steps.close()
        if (touchedLive) {
            touchedLive = false
            touched()
        }
    }

    /**
     * Re-registers the phone's number alone and, once that worked, signs the account out here (under
     * the engine's lock). Should the number not be registered again, the join's marks stay (the next
     * start tries again) and the account is left as it is. True when the number is registered again.
     */
    private suspend fun recoverLocked(): Boolean {
        try {
            steps.recover()
            touchedLive = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "the number was not registered again (${e::class.java.simpleName}); tried again at the next start")
            files.mark(files.joinMarker, true)
            val flag = files.readFlag()
            if (flag.state != FlagState.NONE) files.writeFlag(flag.copy(history = AccountFlag.HISTORY_JOINING))
            changed()
            return false
        }
        try {
            steps.signOutHere()
        } catch (e: Throwable) {
            Log.w(TAG, "the account was not wiped (${e::class.java.simpleName})")
        }
        val flag = files.readFlag()
        // An account flag already gone (a start found no account) is not made again.
        if (flag.state != FlagState.NONE) {
            files.writeFlag(flag.copy(state = FlagState.ATTENTION, history = AccountFlag.HISTORY_OFF, notice = AccountNotice.SIGN_IN_AGAIN))
        }
        files.mark(files.joinMarker, false)
        changed()
        return true
    }

    /**
     * At a start, once the session was (or was not) restored (under the engine's lock): a join that
     * was cut short. Without a session ([restored] false) in the window: the number registered alone
     * again and the account signed out here. With one: the join simply did not finish (Messages in
     * iCloud stays off). Nothing at all otherwise.
     */
    suspend fun atStartLocked(restored: Boolean) {
        val flag = files.readFlag()
        val marked = files.joinMarker.isFile
        if (flag.history != AccountFlag.HISTORY_JOINING && !marked) return
        if (needsRecovery(flag, restored, marked)) {
            recoverLocked()
        } else {
            if (flag.history == AccountFlag.HISTORY_JOINING) files.writeFlag(flag.copy(history = AccountFlag.HISTORY_OFF))
            files.mark(files.joinMarker, false)
            changed()
        }
    }

    private suspend fun guarded(block: suspend () -> Page): Page {
        if (running) return Page.Failed(Why.BUSY)
        if (busy()) return Page.Failed(Why.BUSY)
        if (!allowed()) return end(Page.Failed(Why.NOT_ALLOWED))
        val page = try {
            lock.withLock {
                if (busy()) {
                    Page.Failed(Why.BUSY)
                } else {
                    running = true
                    try {
                        block()
                    } finally {
                        running = false
                    }
                }
            }
        } catch (e: CancellationException) {
            letGo()
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "a step of the join failed (${e::class.java.simpleName})")
            val text = textOf(e)
            end(Page.Failed(if (CloudImport.isAdp(text)) Why.ADP else Why.FAILED, text))
        }
        // The screen went meanwhile: what the join holds is let go of (a code to show is kept for when it comes back).
        if (leftMeanwhile) {
            leftMeanwhile = false
            if (page !is Page.Code) letGo()
        }
        return page
    }

    private fun end(p: Page): Page {
        letGo()
        return p
    }

    private fun textOf(e: Throwable): String = com.lightdecoder.imessage.Redact.secrets(DirectEngine.readable(e)).take(300)

    companion object {
        private const val TAG = "KeychainJoin"
        private val CODE = Regex("[0-9]{6}")

        /** Whether a start found a join cut short in the window where the registration was deleted ([marked]: [AccountFiles.joinMarker]). */
        fun needsRecovery(flag: AccountFlag, restored: Boolean, marked: Boolean = false): Boolean =
            (flag.history == AccountFlag.HISTORY_JOINING || marked) && !restored

        fun newCode(): String {
            val r = SecureRandom()
            return (1..6).joinToString("") { r.nextInt(10).toString() }
        }
    }
}

/**
 * The library's side of [KeychainJoin] on the live session. The account and its two-factor session
 * are this join's own (from the saved sign-in), held until [close]. The reconfigure is the one
 * place `resetState` is called, always with `logout = false` (the account is never signed out at
 * Apple), and `circleSetupClique` the one place the keychain is joined.
 */
internal class NativeKeychainSteps(
    private val statePath: String,
    private val session: () -> SharedPushStateHandle?,
    private val pipeline: () -> EventPipeline?,
    /** Puts a new session in place of the live one (never during a FaceTime call). */
    private val swap: suspend (SharedPushStateHandle) -> Unit,
    /** The phone's kept iMessage user ([PhoneUserStore]). */
    private val phoneUser: () -> String?,
    /** Keeps the phone's iMessage user as registered again ([PhoneUserStore], S4). */
    private val keepUser: (uniffi.rust_bridge.IdsUser) -> Unit = {},
) : KeychainJoin.Steps {

    private var hardware: RestoredHardware? = null
    private var connection: ReusableConnection? = null
    private var watcher: PushWatcher? = null
    private var idms: IdmsListener? = null
    private var account: AppleAccountHandle? = null
    private var circle: CircleSession? = null

    /** A session now in place was built on [connection] (a rebuild or a recovery): it is not closed with the rest. */
    private var connectionInUse = false

    private fun live(): SharedPushStateHandle = session() ?: throw DirectEngine.ImessageError("iMessage is not connected.")

    override fun ready(): Boolean = session() != null && pipeline() != null && try {
        phoneUser() != null
    } catch (e: Exception) {
        false
    }

    private fun step(r: LoginResult): AccountBridge.Step = when (r) {
        is LoginResult.LoggedIn -> AccountBridge.Step.LoggedIn
        is LoginResult.NeedsDevice2Fa -> AccountBridge.Step.NeedsDevice2Fa
        is LoginResult.Needs2FaVerification -> AccountBridge.Step.Needs2FaVerification
        is LoginResult.NeedsSms2Fa -> AccountBridge.Step.NeedsSms2Fa
        is LoginResult.NeedsSmsVerification -> {
            Disposable.destroy(r.body)
            AccountBridge.Step.NeedsLogin
        }
        is LoginResult.NeedsExtraStep -> AccountBridge.Step.NeedsExtraStep("")
        is LoginResult.NeedsLogin -> AccountBridge.Step.NeedsLogin
    }

    override suspend fun authSaved(): AccountBridge.Step {
        close()
        // Taken from the live session (as a sign-in does, S12): however the join ends without a new
        // session on it, iMessage restarts ([KeychainJoin]'s `touched`).
        val hw = uniffi.rust_bridge.readHardware(statePath) ?: throw DirectEngine.ImessageError("iMessage is not registered.")
        hardware = hw
        val rc = uniffi.rust_bridge.reusableConnection(live())
        connection = rc
        watcher = uniffi.rust_bridge.subscribeConn(rc.connection)
        idms = uniffi.rust_bridge.makeIdms(rc.connection)
        val auth = uniffi.rust_bridge.tryAuth(statePath, hw.osConfig, rc.connection, rc.anisette, null, null)
        account = auth.account
        return step(auth.state)
    }

    override suspend fun sendToDevices(): AccountBridge.Step {
        val a = account ?: throw DirectEngine.ImessageError("not signed in")
        val rc = connection ?: throw DirectEngine.ImessageError("not connected")
        val r = uniffi.rust_bridge.send2faToDevices(a, rc.connection)
        circle?.destroy()
        circle = r.session
        return step(r.state)
    }

    override suspend fun verify(code: String): AccountBridge.Verified {
        val hw = hardware ?: throw DirectEngine.ImessageError("not started")
        val r = uniffi.rust_bridge.verify2fa(
            statePath, circle ?: throw DirectEngine.ImessageError("no approval asked for"), hw.osConfig,
            account ?: throw DirectEngine.ImessageError("not signed in"), watcher!!, idms!!, code,
        )
        val user = r.user
        user?.destroy()
        return AccountBridge.Verified(step(r.state), user != null)
    }

    override suspend fun reconfigure() {
        // Everything is in hand before anything is deleted: a failure here changed nothing.
        val hw: RestoredHardware
        val a: AppleAccountHandle
        val c: CircleSession
        val rc: ReusableConnection
        val old: SharedPushStateHandle
        val events: EventPipeline
        val users: List<uniffi.rust_bridge.IdsUser>
        try {
            hw = hardware ?: throw DirectEngine.ImessageError("not started")
            a = account ?: throw DirectEngine.ImessageError("not signed in")
            c = circle ?: throw DirectEngine.ImessageError("no approval")
            // The connection taken once, for the sign-in ([authSaved]): never a second time.
            rc = connection ?: throw DirectEngine.ImessageError("not connected")
            old = live()
            events = pipeline() ?: throw DirectEngine.ImessageError("no events")
            val saved = phoneUser() ?: throw DirectEngine.ImessageError("the phone's number is not kept")
            users = listOf(uniffi.rust_bridge.restoreUser(saved))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw KeychainJoin.Untouched(e)
        }
        // Deletes the registration (never signs out at Apple); the number is registered again at once.
        uniffi.rust_bridge.resetState(old, statePath, false, false)
        val result = uniffi.rust_bridge.registerIds(statePath, hw.osConfig, rc.connection, hw.identity, users.map { uniffi.rust_bridge.duplicateUser(it) })
        result.alert?.let { throw DirectEngine.ImessageError("Apple: ${it.title}. ${it.body}".trim()) }
        (result.users ?: users).firstOrNull()?.let(keepUser)
        val started = uniffi.rust_bridge.startSession(
            statePath, hw.osConfig, rc.connection, rc.anisette, a, result.users ?: users, hw.identity, c, events,
        )
        connectionInUse = true
        swap(started)
    }

    override suspend fun setupClique(code: String): Boolean = uniffi.rust_bridge.circleSetupClique(live(), code)

    override suspend fun inClique(): Boolean = uniffi.rust_bridge.isInClique(live())

    override suspend fun recover() {
        val hw = hardware ?: uniffi.rust_bridge.readHardware(statePath) ?: throw DirectEngine.ImessageError("iMessage is not registered.")
        val saved = phoneUser() ?: throw DirectEngine.ImessageError("the phone's number is not kept")
        // The connection this join already took from the live session, when there is one (a rebuild
        // that failed): no second push connection beside it. Else (a start) one of its own.
        val held = connection
        val conn: uniffi.rust_bridge.ApsConnection
        val anisette: uniffi.rust_bridge.AnisetteClient
        if (held != null) {
            conn = held.connection
            anisette = held.anisette
        } else {
            conn = uniffi.rust_bridge.setupPush(hw.osConfig, hw.identity, hw.push, statePath).connection
            anisette = uniffi.rust_bridge.makeAnisette(statePath, hw.osConfig, conn)
        }
        val users = listOf(uniffi.rust_bridge.restoreUser(saved))
        val result = uniffi.rust_bridge.registerIds(statePath, hw.osConfig, conn, hw.identity, users.map { uniffi.rust_bridge.duplicateUser(it) })
        result.alert?.let { throw DirectEngine.ImessageError("Apple: ${it.title}. ${it.body}".trim()) }
        (result.users ?: users).firstOrNull()?.let(keepUser)
        val started = uniffi.rust_bridge.startSession(
            statePath, hw.osConfig, conn, anisette, null, result.users ?: users, hw.identity, null, pipeline() ?: throw DirectEngine.ImessageError("no events"),
        )
        if (held != null) connectionInUse = true
        swap(started)
    }

    override fun signOutHere() = uniffi.rust_bridge.resetAppleLogin(statePath)

    override fun close() {
        // The connection taken from the live session, when no session was built on it: let go of too
        // (iMessage restarts for it, [KeychainJoin]'s `touched`).
        val unused = connection?.takeIf { !connectionInUse }
        for (d in listOf<Disposable?>(circle, account, idms, watcher, unused?.connection, unused?.anisette)) {
            try {
                d?.destroy()
            } catch (e: Throwable) {
                // Already let go of.
            }
        }
        circle = null
        account = null
        idms = null
        watcher = null
        connection = null
        connectionInUse = false
        hardware = null
    }
}
