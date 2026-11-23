package com.lightdecoder.imessage.direct

import uniffi.rust_bridge.AnisetteClient
import uniffi.rust_bridge.AppleAccountHandle
import uniffi.rust_bridge.ApsConnection
import uniffi.rust_bridge.CircleSession
import uniffi.rust_bridge.Disposable
import uniffi.rust_bridge.IdmsListener
import uniffi.rust_bridge.JoinedOsConfig
import uniffi.rust_bridge.LoginResult
import uniffi.rust_bridge.PushWatcher
import uniffi.rust_bridge.SharedPushStateHandle
import uniffi.rust_bridge.VerifyBody

/**
 * The library's Apple Account functions, as the optional sign-in ([AppleAccount]) uses them, in the
 * ":imessage" process only. Its objects (the account, the connection, a two-factor session) cross as
 * [Native]s nothing outside the real bridge can look into; what the library answers is plain Kotlin.
 * Tests put a fake here: the library itself cannot run in them.
 *
 * Only these. What the sign-in must never do (reset the iCloud Keychain or Messages in iCloud, join
 * the keychain, set the account aside, approve other devices' sign-ins, read passwords or live
 * headers, sign out at Apple) has no function here, and nothing else in LightDecoder calls it.
 * Every function may throw ([uniffi.rust_bridge.RustBridgeException] and worse).
 */
interface AccountBridge {

    /** One of the library's objects, held while the sign-in needs it; [close] lets the library free it. */
    interface Native {
        fun close()
    }

    /** This device's configuration as registration saved it (the relay's). */
    class Hardware(val config: Native)

    /** The live session's Apple connection and anisette client. */
    class Connection(val conn: Native, val anisette: Native)

    /** A sign-in's account and how far it got. */
    class Auth(val account: Native, val step: Step)

    /** Codes asked for on the user's trusted devices: their two-factor session and where that left the sign-in. */
    class DeviceCodes(val circle: Native, val step: Step)

    /** A code checked: where it left the sign-in, and whether the library signed in to iCloud with it. */
    data class Verified(val step: Step, val user: Boolean)

    /** The trusted phone numbers a code can be texted to, and a step to go on with instead, if Apple set one. */
    data class SmsOptions(val numbers: List<TrustedNumber>, val next: Step?)

    /** One trusted number: its id with Apple, and how Apple writes it (mostly hidden). */
    data class TrustedNumber(val id: Long, val display: String)

    /** The device Apple will list in the account: the relay's name, serial number and system. */
    data class Device(val name: String, val serial: String, val os: String)

    /** The library's `LoginResult`. */
    sealed class Step {
        object LoggedIn : Step()
        object NeedsDevice2Fa : Step()
        object Needs2FaVerification : Step()
        object NeedsSms2Fa : Step()
        class NeedsSmsVerification(val body: Native) : Step()
        data class NeedsExtraStep(val message: String) : Step()
        object NeedsLogin : Step()
    }

    fun readHardware(statePath: String): Hardware?
    suspend fun reusableConnection(): Connection
    fun subscribeConn(conn: Native): Native
    suspend fun makeIdms(conn: Native): Native
    suspend fun tryAuth(statePath: String, config: Native, conn: Native, anisette: Native, username: String?, password: String?): Auth

    /** Whether the library signed in to iCloud (its `IdsUser`, which is let go of at once: it is never registered here). */
    suspend fun tryIcloudLogin(statePath: String, config: Native, account: Native): Boolean
    suspend fun getUserName(account: Native): String
    suspend fun send2faToDevices(account: Native, conn: Native): DeviceCodes
    suspend fun verify2fa(statePath: String, circle: Native, config: Native, account: Native, watcher: Native, idms: Native, code: String): Verified
    suspend fun get2faSmsOpts(account: Native): SmsOptions
    suspend fun send2faSms(circle: Native?, account: Native, phoneId: Long): Step
    suspend fun verify2faSms(statePath: String, account: Native, config: Native, body: Native, code: String): Verified
    fun resetAppleLogin(statePath: String)
    fun getAvailableUser(statePath: String): String?
    fun getDeviceInfo(config: Native): Device
    suspend fun hasIcloudServices(): Boolean
}

/**
 * The real [AccountBridge]: `uniffi.rust_bridge`, on the live session [session] gives (the engine's).
 * The library must be loaded ([DirectLibrary.load]) before any of it is called.
 */
class NativeAccountBridge(private val session: () -> SharedPushStateHandle?) : AccountBridge {

    private class Held<T : Disposable>(val obj: T) : AccountBridge.Native {
        override fun close() {
            try {
                obj.destroy()
            } catch (e: Throwable) {
                // Already let go of.
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Disposable> AccountBridge.Native.obj(): T = (this as Held<*>).obj as T

    private fun live(): SharedPushStateHandle = session() ?: throw DirectEngine.ImessageError("iMessage is not connected.")

    private fun step(r: LoginResult): AccountBridge.Step = when (r) {
        is LoginResult.LoggedIn -> AccountBridge.Step.LoggedIn
        is LoginResult.NeedsDevice2Fa -> AccountBridge.Step.NeedsDevice2Fa
        is LoginResult.Needs2FaVerification -> AccountBridge.Step.Needs2FaVerification
        is LoginResult.NeedsSms2Fa -> AccountBridge.Step.NeedsSms2Fa
        is LoginResult.NeedsSmsVerification -> AccountBridge.Step.NeedsSmsVerification(Held(r.body))
        is LoginResult.NeedsExtraStep -> AccountBridge.Step.NeedsExtraStep(r.message)
        is LoginResult.NeedsLogin -> AccountBridge.Step.NeedsLogin
    }

    override fun readHardware(statePath: String): AccountBridge.Hardware? {
        val hw = uniffi.rust_bridge.readHardware(statePath) ?: return null
        // Only its configuration is needed: the identity and the saved push state are let go of.
        Disposable.destroy(hw.identity, hw.push)
        return AccountBridge.Hardware(Held(hw.osConfig))
    }

    override suspend fun reusableConnection(): AccountBridge.Connection {
        val rc = uniffi.rust_bridge.reusableConnection(live())
        return AccountBridge.Connection(Held(rc.connection), Held(rc.anisette))
    }

    override fun subscribeConn(conn: AccountBridge.Native): AccountBridge.Native =
        Held(uniffi.rust_bridge.subscribeConn(conn.obj<ApsConnection>()))

    override suspend fun makeIdms(conn: AccountBridge.Native): AccountBridge.Native =
        Held(uniffi.rust_bridge.makeIdms(conn.obj<ApsConnection>()))

    override suspend fun tryAuth(
        statePath: String,
        config: AccountBridge.Native,
        conn: AccountBridge.Native,
        anisette: AccountBridge.Native,
        username: String?,
        password: String?,
    ): AccountBridge.Auth {
        val auth = uniffi.rust_bridge.tryAuth(statePath, config.obj<JoinedOsConfig>(), conn.obj<ApsConnection>(), anisette.obj<AnisetteClient>(), username, password)
        return AccountBridge.Auth(Held(auth.account), step(auth.state))
    }

    override suspend fun tryIcloudLogin(statePath: String, config: AccountBridge.Native, account: AccountBridge.Native): Boolean {
        val user = uniffi.rust_bridge.tryIcloudLogin(statePath, config.obj<JoinedOsConfig>(), account.obj<AppleAccountHandle>()) ?: return false
        user.destroy()
        return true
    }

    override suspend fun getUserName(account: AccountBridge.Native): String = uniffi.rust_bridge.getUserName(account.obj<AppleAccountHandle>())

    override suspend fun send2faToDevices(account: AccountBridge.Native, conn: AccountBridge.Native): AccountBridge.DeviceCodes {
        // Its session id is for advertising over Bluetooth, which is not done here.
        val r = uniffi.rust_bridge.send2faToDevices(account.obj<AppleAccountHandle>(), conn.obj<ApsConnection>())
        return AccountBridge.DeviceCodes(Held(r.session), step(r.state))
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
        val r = uniffi.rust_bridge.verify2fa(
            statePath, circle.obj<CircleSession>(), config.obj<JoinedOsConfig>(), account.obj<AppleAccountHandle>(),
            watcher.obj<PushWatcher>(), idms.obj<IdmsListener>(), code,
        )
        val user = r.user
        user?.destroy()
        return AccountBridge.Verified(step(r.state), user != null)
    }

    override suspend fun get2faSmsOpts(account: AccountBridge.Native): AccountBridge.SmsOptions {
        val r = uniffi.rust_bridge.get2faSmsOpts(account.obj<AppleAccountHandle>())
        return AccountBridge.SmsOptions(r.numbers.map { AccountBridge.TrustedNumber(it.id.toLong(), it.numberWithDialCode) }, r.newState?.let(::step))
    }

    override suspend fun send2faSms(circle: AccountBridge.Native?, account: AccountBridge.Native, phoneId: Long): AccountBridge.Step {
        require(phoneId in 0..UInt.MAX_VALUE.toLong()) { "not a phone number's id" }
        return step(uniffi.rust_bridge.send2faSms(circle?.obj<CircleSession>(), account.obj<AppleAccountHandle>(), phoneId.toUInt()))
    }

    override suspend fun verify2faSms(
        statePath: String,
        account: AccountBridge.Native,
        config: AccountBridge.Native,
        body: AccountBridge.Native,
        code: String,
    ): AccountBridge.Verified {
        val r = uniffi.rust_bridge.verify2faSms(statePath, account.obj<AppleAccountHandle>(), config.obj<JoinedOsConfig>(), body.obj<VerifyBody>(), code)
        val user = r.user
        user?.destroy()
        return AccountBridge.Verified(step(r.state), user != null)
    }

    override fun resetAppleLogin(statePath: String) = uniffi.rust_bridge.resetAppleLogin(statePath)

    override fun getAvailableUser(statePath: String): String? = uniffi.rust_bridge.getAvailableUser(statePath)

    override fun getDeviceInfo(config: AccountBridge.Native): AccountBridge.Device {
        val d = uniffi.rust_bridge.getDeviceInfo(config.obj<JoinedOsConfig>())
        return AccountBridge.Device(d.name, d.serial, d.osVersion)
    }

    override suspend fun hasIcloudServices(): Boolean = uniffi.rust_bridge.hasIcloudServices(live())
}
