package com.lightdecoder.imessage.direct

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import uniffi.rust_bridge.ApsConnection
import uniffi.rust_bridge.EapAkaCallback
import uniffi.rust_bridge.IdsUser
import uniffi.rust_bridge.JoinedOsConfig

/**
 * "SMS-less" phone-number verification, as OpenBubbles 2.0.0 does it first and as iPhones do on
 * carriers that support it: the carrier's entitlement server challenges the SIM ([EapAka]) and
 * then vouches for the number to Apple. No text message is involved; on such carriers Apple's SMS
 * gateway may never answer one.
 *
 * Android lets an app ask the SIM (and read the IMSI and IMEI the carrier wants) only with the
 * app-op USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER, which only the user can grant, from a shell:
 * from a computer ([adbCommand]). Without it, SMS is used.
 */
object SimAuth {

    private const val OP = "android:use_icc_auth_with_device_identifier"
    const val OP_SHELL_NAME = "USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER"

    /** What the rust library says when the SIM's carrier has no entitlement server: SMS is the way then. */
    const val UNSUPPORTED = "Carrier does not support ICC auth!"

    /** Whether the user has allowed LightDecoder to ask the SIM. */
    fun allowed(context: Context): Boolean = try {
        @Suppress("DEPRECATION")
        context.getSystemService(AppOpsManager::class.java)
            .checkOpNoThrow(OP, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    } catch (e: RuntimeException) {
        false // Android 9 and older do not know the app-op
    }

    /** The shell command that allows it (`adb shell <this>`). */
    fun shellCommand(context: Context): Array<String> =
        arrayOf("appops", "set", "--uid", context.packageName, OP_SHELL_NAME, "allow")

    fun adbCommand(context: Context): String = "adb shell " + shellCommand(context).joinToString(" ")

    /**
     * Verifies the default SMS SIM's number with its carrier and returns the phone's iMessage user.
     * Throws [DirectEngine.ImessageError] with [UNSUPPORTED] in its message when the carrier has no
     * entitlement server (the caller then uses SMS), or with what went wrong.
     */
    @SuppressLint("MissingPermission", "HardwareIds")
    suspend fun verify(context: Context, config: JoinedOsConfig, conn: ApsConnection): IdsUser {
        if (!allowed(context)) throw DirectEngine.ImessageError("LightDecoder is not allowed to ask the SIM yet.")
        val subId = SubscriptionManager.getDefaultSmsSubscriptionId()
        val tm = context.getSystemService(TelephonyManager::class.java).let {
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) it.createForSubscriptionId(subId) else it
        }
        val mccmnc = tm.simOperator.orEmpty()
        val (imsi, imei) = try {
            tm.subscriberId to tm.imei.orEmpty()
        } catch (e: SecurityException) {
            throw DirectEngine.ImessageError("Android did not let LightDecoder read the SIM (the permission may not have taken yet: restart LightDecoder and try again).")
        }
        val subscriber = EapAka.identity(mccmnc, imsi)
            ?: throw DirectEngine.ImessageError("No SIM found: phone-number registration needs this phone's SIM.")
        var simProblem: Throwable? = null
        val callback = object : EapAkaCallback {
            override fun respondToChallenge(challenge: String): String = try {
                EapAka.respond(challenge, subscriber) { request ->
                    tm.getIccAuthentication(TelephonyManager.APPTYPE_USIM, TelephonyManager.AUTHTYPE_EAP_AKA, request)
                }
            } catch (e: Throwable) {
                simProblem = e // the library only reports that the callback failed
                throw e
            }
        }
        try {
            return uniffi.rust_bridge.getEntitlements(config, conn, mccmnc, subscriber, imei, callback)
        } catch (e: Exception) {
            val why = simProblem?.let { it.message ?: it.javaClass.simpleName } ?: DirectEngine.readable(e)
            throw DirectEngine.ImessageError(why)
        }
    }
}
