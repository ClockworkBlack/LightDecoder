package com.lightdecoder.imessage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
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
        fun offer(text: String?): Boolean {
            val r = text?.let(PhoneRegSms::parseResponse) ?: return false
            if (r.requestId != requestId) return false
            reply.complete(r)
            return true
        }
        // Apple's reply comes as a text on most carriers, as a binary ("data") SMS on some.
        val textReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.mapNotNull { it?.messageBody } ?: return
                offer(PhoneRegSms.joinTextParts(parts))
            }
        }
        val dataReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val pdus = intent.extras?.get("pdus") as? Array<*> ?: return
                val format = intent.getStringExtra("format")
                for (pdu in pdus.filterIsInstance<ByteArray>()) offer(dataText(pdu, format))
            }
        }
        val data = IntentFilter(DATA_SMS_RECEIVED).apply {
            addDataScheme("sms")
            addDataAuthority("*", null)
        }
        ContextCompat.registerReceiver(
            context, textReceiver, IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION),
            android.Manifest.permission.BROADCAST_SMS, null, ContextCompat.RECEIVER_EXPORTED,
        )
        ContextCompat.registerReceiver(
            context, dataReceiver, data, android.Manifest.permission.BROADCAST_SMS, null, ContextCompat.RECEIVER_EXPORTED,
        )
        manual = ::offer
        try {
            smsManager().sendTextMessage(gateway, null, PhoneRegSms.request(pushToken, requestId), null, null)
            return withTimeoutOrNull(REPLY_TIMEOUT_MS) { reply.await() }
                ?: throw ImessageEngine.ImessageError(
                    "Apple did not answer the verification text within ${REPLY_TIMEOUT_MS / 60000} minutes. " +
                        "If a reply starting with REG-RESP arrived in your messages, register again and paste it " +
                        "when asked. Otherwise check that this SIM can text short codes.",
                )
        } finally {
            manual = null
            context.unregisterReceiver(textReceiver)
            context.unregisterReceiver(dataReceiver)
        }
    }

    /** The readable text of a data SMS: decoded as 3GPP or 3GPP2, else its raw bytes. */
    private fun dataText(pdu: ByteArray, format: String?): String? {
        for (f in listOfNotNull(format, "3gpp", "3gpp2").distinct()) {
            val msg = try {
                SmsMessage.createFromPdu(pdu, f)
            } catch (e: RuntimeException) {
                null
            } ?: continue
            val body = msg.messageBody ?: msg.userData?.let { String(it, Charsets.US_ASCII) }
            if (body != null && "REG-RESP" in body) return body
        }
        return String(pdu, Charsets.US_ASCII).takeIf { "REG-RESP" in it }
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
        const val REPLY_TIMEOUT_MS = 3 * 60_000L
        private const val DATA_SMS_RECEIVED = "android.intent.action.DATA_SMS_RECEIVED"

        /** While a verification waits: takes Apple's reply pasted by the user (setup screen). */
        @Volatile
        private var manual: ((String) -> Boolean)? = null

        val waiting: Boolean get() = manual != null

        /** What became of a pasted reply. */
        enum class Paste { TAKEN, NOT_WAITING, NOT_A_REPLY, OTHER_REQUEST }

        /** Hands a reply the user pasted to the waiting verification. */
        fun paste(text: String): Paste {
            val m = manual ?: return Paste.NOT_WAITING
            if (PhoneRegSms.parseResponse(text) == null) return Paste.NOT_A_REPLY
            return if (m(text)) Paste.TAKEN else Paste.OTHER_REQUEST
        }
    }
}
