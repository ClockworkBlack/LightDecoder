package com.lightdecoder.imessage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.rust_bridge.CarrierHandler

/**
 * Verifies this phone's number with Apple by SMS ([PhoneRegSms]): asks the library for the SIM
 * carrier's Apple gateway, texts it the request, and waits for Apple's reply. Needs the SEND_SMS
 * and RECEIVE_SMS permissions (asked for on the iMessage screen just before registering). The
 * carrier may charge for one text to the gateway, as it does when an iPhone registers.
 */
class SmsPhoneVerifier(private val context: Context) : PhoneVerifier {

    override suspend fun verify(pushToken: ByteArray): PhoneRegSms.Response {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val mccmnc = tm?.simOperator.orEmpty()
        if (mccmnc.isEmpty()) throw ImessageEngine.ImessageError("No SIM found: phone-number registration needs this phone's SIM.")
        val gateway = gateway(mccmnc)
        val requestId = PhoneRegSms.newRequestId()
        val reply = CompletableDeferred<PhoneRegSms.Response>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val body = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                    ?.joinToString("") { it?.messageBody.orEmpty() } ?: return
                val r = PhoneRegSms.parseResponse(body) ?: return
                if (r.requestId == requestId) reply.complete(r)
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION),
            android.Manifest.permission.BROADCAST_SMS, null, ContextCompat.RECEIVER_EXPORTED,
        )
        try {
            smsManager().sendTextMessage(gateway, null, PhoneRegSms.request(pushToken, requestId), null, null)
            return withTimeoutOrNull(REPLY_TIMEOUT_MS) { reply.await() }
                ?: throw ImessageEngine.ImessageError(
                    "Apple did not answer the verification text within ${REPLY_TIMEOUT_MS / 1000} seconds. " +
                        "Check that this SIM can send texts to short codes, then try again.",
                )
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private suspend fun gateway(mccmnc: String): String = suspendCancellableCoroutine { cont ->
        uniffi.rust_bridge.getCarrier(
            object : CarrierHandler {
                override fun gotGateway(gateway: String?, error: String?) {
                    if (!cont.isActive) return
                    if (gateway.isNullOrBlank()) {
                        cont.resumeWith(
                            Result.failure(
                                ImessageEngine.ImessageError(
                                    "This carrier ($mccmnc) has no Apple registration gateway" + (error?.let { ": $it" } ?: "."),
                                ),
                            ),
                        )
                    } else {
                        cont.resume(gateway)
                    }
                }
            },
            mccmnc,
        )
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    companion object {
        const val REPLY_TIMEOUT_MS = 90_000L
    }
}
