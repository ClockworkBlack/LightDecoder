package com.lightdecoder.imessage.direct

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
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
        // The SIM texts go out from (as OpenBubbles does): the carrier, its gateway, the sending all use it.
        val subId = SubscriptionManager.getDefaultSmsSubscriptionId()
        val tm = context.getSystemService(TelephonyManager::class.java)?.let {
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) it.createForSubscriptionId(subId) else it
        }
        val mccmnc = tm?.simOperator.orEmpty()
        if (mccmnc.isEmpty()) throw DirectEngine.ImessageError("No SIM found: phone-number registration needs this phone's SIM.")
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
        // Whether the carrier took the text: a refusal is said at once, not after minutes of waiting.
        val sent = CompletableDeferred<Int>()
        val sentAction = "com.lightdecoder.imessage.REG_REQ_SENT." + requestId
        val sentReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                sent.complete(resultCode)
            }
        }
        ContextCompat.registerReceiver(context, sentReceiver, IntentFilter(sentAction), ContextCompat.RECEIVER_NOT_EXPORTED)
        val sentIntent = android.app.PendingIntent.getBroadcast(
            context, 0, Intent(sentAction).setPackage(context.packageName),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_ONE_SHOT,
        )
        try {
            smsManager(subId).sendTextMessage(gateway, null, PhoneRegSms.request(pushToken, requestId), sentIntent, null)
            val code = withTimeoutOrNull(SENT_TIMEOUT_MS) { sent.await() }
            if (code != null && code != android.app.Activity.RESULT_OK) {
                throw DirectEngine.ImessageError(
                    "The verification text to Apple ($gateway) could not be sent: ${sendError(code)}. " +
                        "Check that this SIM has service and can text short codes.",
                )
            }
            return withTimeoutOrNull(REPLY_TIMEOUT_MS) { reply.await() }
                ?: throw DirectEngine.ImessageError(
                    "$NO_ANSWER within ${REPLY_TIMEOUT_MS / 60000} minutes. If a text starting with REG-RESP did arrive " +
                        "in your messages, register again and paste it when asked.",
                )
        } finally {
            manual = null
            context.unregisterReceiver(textReceiver)
            context.unregisterReceiver(dataReceiver)
            context.unregisterReceiver(sentReceiver)
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
                                DirectEngine.ImessageError(
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
    private fun smsManager(subId: Int): SmsManager {
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
            } else {
                SmsManager.getSmsManagerForSubscriptionId(subId)
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
    }

    /** Android's reason a text was not sent. */
    private fun sendError(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "the carrier refused it (generic failure)"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no mobile service"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "the mobile radio is off (airplane mode?)"
        SmsManager.RESULT_ERROR_NULL_PDU -> "it could not be encoded"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "too many texts sent just now"
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED, SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED ->
            "Android blocked a text to a short code (allow it in LightDecoder's app settings, SMS to premium numbers)"
        else -> "error $code"
    }

    companion object {
        const val REPLY_TIMEOUT_MS = 2 * 60_000L

        /** How the error starts when Apple's gateway stays silent ([DirectEngine] looks for it). */
        const val NO_ANSWER = "Apple did not answer the verification text"
        private const val SENT_TIMEOUT_MS = 60_000L
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
