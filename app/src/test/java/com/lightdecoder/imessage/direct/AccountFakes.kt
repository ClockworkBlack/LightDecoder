package com.lightdecoder.imessage.direct

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.sync.Mutex

/**
 * A stand-in for the library's Apple Account functions ([AccountBridge]): it answers as the test sets
 * it up and writes what the library would to its state folder (an `account.plist` once signed in,
 * nothing of the password), and keeps every call, in order, with what `tryAuth` was given.
 */
class FakeAccountBridge(private val statePath: String) : AccountBridge {

    class Ref(val name: String) : AccountBridge.Native {
        @Volatile
        var closed = false
        var onClose: (() -> Unit)? = null

        override fun close() {
            if (closed) return
            closed = true
            onClose?.invoke()
        }
    }

    /** Every call, by the function's name, in order. */
    val calls = java.util.Collections.synchronizedList(ArrayList<String>())

    /** What each `tryAuth` was given: the Apple ID and the password. */
    val auths = java.util.Collections.synchronizedList(ArrayList<Pair<String?, String?>>())
    val refs = java.util.Collections.synchronizedList(ArrayList<Ref>())

    /** The two-factor session each `send2faSms` was given (null: none). */
    val textCircles = ArrayList<Ref?>()

    var hasHardware = true
    var device = AccountBridge.Device("iPhone14,2", "F2LXXXXXXXX1", "17.5")
    var onTryAuth: (String?, String?) -> AccountBridge.Step = { _, _ -> AccountBridge.Step.LoggedIn }
    var icloudUser: () -> Boolean = { true }
    var onSendToDevices: () -> AccountBridge.Step = { AccountBridge.Step.Needs2FaVerification }
    var onVerify: (String) -> AccountBridge.Verified = { AccountBridge.Verified(AccountBridge.Step.LoggedIn, true) }
    var smsOptions: () -> AccountBridge.SmsOptions = {
        AccountBridge.SmsOptions(listOf(AccountBridge.TrustedNumber(7, "+1 (•••) •••-••12")), null)
    }
    var onSendSms: (Long) -> AccountBridge.Step = { AccountBridge.Step.NeedsSmsVerification(ref("body")) }
    var onVerifySms: (String) -> AccountBridge.Verified = { AccountBridge.Verified(AccountBridge.Step.LoggedIn, true) }
    var icloud = true

    /** Something else this test wants done, in order with the calls (S10's order). */
    var order: MutableList<String>? = null

    fun ref(name: String) = Ref(name).also { refs += it }

    private fun call(name: String) {
        calls += name
        order?.add(name)
    }

    private val accountPlist = File(statePath, "account.plist")

    override fun readHardware(statePath: String): AccountBridge.Hardware? {
        call("readHardware")
        return if (hasHardware) AccountBridge.Hardware(ref("config")) else null
    }

    override suspend fun reusableConnection(): AccountBridge.Connection {
        call("reusableConnection")
        return AccountBridge.Connection(ref("conn"), ref("anisette"))
    }

    /** `makeIdms` fails this many times first. */
    var idmsFailures = 0

    override fun subscribeConn(conn: AccountBridge.Native): AccountBridge.Native {
        call("subscribeConn")
        return ref("watcher")
    }

    override suspend fun makeIdms(conn: AccountBridge.Native): AccountBridge.Native {
        call("makeIdms")
        if (idmsFailures > 0) {
            idmsFailures--
            throw uniffi.rust_bridge.RustBridgeException.Other("no listener")
        }
        return ref("idms")
    }

    override suspend fun tryAuth(
        statePath: String,
        config: AccountBridge.Native,
        conn: AccountBridge.Native,
        anisette: AccountBridge.Native,
        username: String?,
        password: String?,
    ): AccountBridge.Auth {
        call("tryAuth")
        auths += username to password
        // As the library: credentials wipe what was saved first; then the account is saved (never the password).
        if (username != null) wipe()
        val step = onTryAuth(username, password)
        if (username != null) {
            accountPlist.parentFile?.mkdirs()
            accountPlist.writeText("account of $username")
        }
        return AccountBridge.Auth(ref("account"), step)
    }

    override suspend fun tryIcloudLogin(statePath: String, config: AccountBridge.Native, account: AccountBridge.Native): Boolean {
        call("tryIcloudLogin")
        return icloudUser()
    }

    override suspend fun getUserName(account: AccountBridge.Native): String {
        call("getUserName")
        return "user@example.com"
    }

    override suspend fun send2faToDevices(account: AccountBridge.Native, conn: AccountBridge.Native): AccountBridge.DeviceCodes {
        call("send2faToDevices")
        return AccountBridge.DeviceCodes(ref("circle"), onSendToDevices())
    }

    override suspend fun verify2fa(
        statePath: String,
        circle: AccountBridge.Native,
        config: AccountBridge.Native,
        account: AccountBridge.Native,
        watcher: AccountBridge.Native,
        idms: AccountBridge.Native,
        code: String,
    ): AccountBridge.Verified {
        call("verify2fa")
        return onVerify(code)
    }

    override suspend fun get2faSmsOpts(account: AccountBridge.Native): AccountBridge.SmsOptions {
        call("get2faSmsOpts")
        return smsOptions()
    }

    override suspend fun send2faSms(circle: AccountBridge.Native?, account: AccountBridge.Native, phoneId: Long): AccountBridge.Step {
        call("send2faSms:$phoneId")
        textCircles += circle as Ref?
        return onSendSms(phoneId)
    }

    override suspend fun verify2faSms(
        statePath: String,
        account: AccountBridge.Native,
        config: AccountBridge.Native,
        body: AccountBridge.Native,
        code: String,
    ): AccountBridge.Verified {
        call("verify2faSms")
        return onVerifySms(code)
    }

    override fun resetAppleLogin(statePath: String) {
        call("resetAppleLogin")
        wipe()
    }

    /** The library's `reset_user` list (not a set-aside record). */
    private fun wipe() {
        for (name in listOf("account.plist", "token_provider.plist", "facetime.plist", "cloudkit.plist", "statuskit.plist")) File(statePath, name).delete()
    }

    override fun getAvailableUser(statePath: String): String? {
        call("getAvailableUser")
        return when {
            accountPlist.isFile -> accountPlist.readText().removePrefix("account of ").ifBlank { null }
            File(statePath, "backup.plist").isFile -> "setaside@example.com"
            else -> null
        }
    }

    override fun getDeviceInfo(config: AccountBridge.Native): AccountBridge.Device {
        call("getDeviceInfo")
        return device
    }

    override suspend fun hasIcloudServices(): Boolean {
        call("hasIcloudServices")
        return icloud
    }

    companion object {
        /** What must never be called from anything LightDecoder does with the account (SPEC2 S8). */
        val NEVER = listOf(
            "resetClique", "resetCloudMessages", "joinCliqueWithBottle", "getBottles", "setAsideAccount", "approveCircle",
            "teardown2fa", "configureAppReview", "listCredentials", "savePassword", "syncPasswords", "getContactsHeaders",
            "getAnisetteHeaders", "updateAccountHeaders", "resetState", "circleSetupClique", "joinCliqueWithPreviousDevice",
            // B6: nothing in iCloud is deleted, saved or reset; no other escrow change; no poster; no Find My (SPEC2 §6).
            "deleteCloudAttachments", "deleteCloudChats", "deleteCloudMessages", "saveCloudAttachments", "saveCloudChats",
            "saveCloudChatsWithPhotos", "saveCloudMessages", "getCloudQuota", "changeEscrowPassword", "parsePoster", "fromPoster",
            "makeFindMyFriends", "makeFindMyPhone", "selectFriend", "selectBackgroundFriend", "getFollowing", "refreshFollowing",
            "getBackgroundFollowing", "refreshBackgroundFollowing", "updateSecureLocationStates", "reconcileFriends", "getBeaconItems", "acceptBeaconShare", "deleteBeaconShare", "updateBeaconName",
        )

        /**
         * Called only by the keychain join (B6.5), in `KeychainJoin.kt` alone: `resetState` with
         * `logout = false` (never a sign-out at Apple), and `circleSetupClique`.
         */
        val JOIN_ONLY = listOf("resetState", "circleSetupClique")
    }
}

/** The engine's side, as the sign-in sees it. */
class FakeAccountHooks : AppleAccount.Hooks {
    override var busy = false
    override var registered = true
    override var relayHost = "https://relay.example.com"

    /** False: something holds the restart once the service is started (a request being answered, a registration): [ProcessRestart.Outcome.KEPT_BACK]. */
    var restartAnswer = true

    /** Set: Android refuses to start iMessage's service ([ProcessRestart.Outcome.FAILED]: nothing else is done). */
    @Volatile
    var refused = false

    /** Every try ([restart] called), whatever came of it. */
    val tries = java.util.concurrent.atomic.AtomicInteger()

    /** Restarts made: the process would have ended. */
    val restarts = java.util.concurrent.atomic.AtomicInteger()

    /** Restarts asked for that were no longer wanted when it came to it (nothing done). */
    val needless = java.util.concurrent.atomic.AtomicInteger()
    var onRestart: () -> Unit = {}

    /** What a restart waits for once the service is started, as the engine's does for its lock (it can be cancelled there). */
    var beforeRestart: suspend () -> Unit = {}
    val told = java.util.Collections.synchronizedList(ArrayList<AccountState>())

    /** Set: telling the main process waits for it (as a busy link would), once what was told is kept. */
    @Volatile
    var tellGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var keysDeleted = 0

    /**
     * As the engine ([ProcessRestart.whenFree]): the service is started first, and when Android
     * refuses ([refused]) nothing else is done, nobody told; then a restart no longer wanted does
     * nothing, nor one kept back ([restartAnswer] false); else its last things, and the end.
     */
    override suspend fun restart(wanted: () -> Boolean, last: suspend () -> Unit): ProcessRestart.Outcome {
        tries.incrementAndGet()
        if (refused) return ProcessRestart.Outcome.FAILED
        beforeRestart()
        if (!wanted()) {
            needless.incrementAndGet()
            return ProcessRestart.Outcome.NOT_WANTED
        }
        if (!restartAnswer) return ProcessRestart.Outcome.KEPT_BACK
        last()
        restarts.incrementAndGet()
        onRestart()
        return ProcessRestart.Outcome.RESTARTED
    }

    /** For each of [told], whether it was told right before the process would end for its restart. */
    val toldRestarting = java.util.Collections.synchronizedList(ArrayList<Boolean>())

    override suspend fun tell(state: AccountState, restarting: Boolean) {
        told += state
        toldRestarting += restarting
        tellGate?.await()
    }

    override fun deleteAccountKeys() {
        keysDeleted++
    }
}

/** A phone's app folder and library state folder of its own, with an [AppleAccount] over them and the fakes. */
class AccountRig(
    val clock: LongArray = longArrayOf(1_700_000_000_000L),
    /** Where the account's own work runs: real threads by default, or a test dispatcher's virtual time. */
    private val work: kotlin.coroutines.CoroutineContext? = null,
) {
    val filesDir: File = Files.createTempDirectory("ld-account").toFile()
    val statePath: String = File(filesDir, "imessage/state").apply { mkdirs() }.path
    val files = AccountFiles(filesDir, statePath)
    val bridge = FakeAccountBridge(statePath)
    val hooks = FakeAccountHooks()
    val lock = Mutex()
    var account = make()

    fun make() = if (work == null) {
        AppleAccount(files, bridge, lock, hooks, now = { clock[0] })
    } else {
        AppleAccount(files, bridge, lock, hooks, now = { clock[0] }, io = work, work = work)
    }

    /** The process starts again: a new account over the same files. */
    fun restart() {
        account = make()
    }

    fun advance(ms: Long) {
        clock[0] += ms
    }

    fun signedIn(state: FlagState = FlagState.SIGNED_IN, user: String = "user@example.com") {
        File(statePath, "account.plist").writeText("account of $user")
        files.writeFlag(AccountFlag(state, clock[0]))
        restart()
    }

    /** Every file under the app's folder whose bytes contain [secret]. */
    fun filesHolding(secret: String): List<File> = filesDir.walkTopDown().filter { it.isFile && String(it.readBytes(), Charsets.ISO_8859_1).contains(secret) }.toList()
}
