package com.lightdecoder.wa

import com.lightdecoder.chat.Presence
import org.json.JSONObject

/*
 * Kotlin mirrors of the JSON only the WhatsApp bridge hands over (the
 * contract's "Events" and StatusJSON). Everything it shares with the Google
 * Messages bridge (conversations, messages, parts, reactions, typing, the
 * state event) is parsed by com.lightdecoder.relay's Dto classes, unchanged.
 *
 * Parsing is lenient, as there: a missing or null key becomes the field's
 * default. Most of these hold ids that identify people (see WaBridge), so
 * none of them is ever logged or put into an exception's message.
 */

/** The bridge's own view of the link (Go: StatusJSON). [ownId] is the user part of the account's JID: its phone number without "+". */
data class WaStatus(
    val paired: Boolean = false,
    val connected: Boolean = false,
    val ownId: String = "",
    val pushName: String = "",
) {
    companion object {
        fun fromJson(o: JSONObject) = WaStatus(
            paired = o.optBoolean("paired"),
            connected = o.optBoolean("connected"),
            ownId = o.str("ownId"),
            pushName = o.str("pushName"),
        )
    }
}

/** A "qr" event: [code] is the text of the QR code to show now. As good as a pairing code while it is valid. */
class WaQr(val code: String) {
    companion object {
        fun fromJson(o: JSONObject) = WaQr(code = o.str("code"))
    }
}

/** A "pair_success" event: linked. [ownId] is the account's number, as in [WaStatus]. */
class WaPairSuccess(val ownId: String) {
    companion object {
        fun fromJson(o: JSONObject) = WaPairSuccess(ownId = o.str("ownId"))
    }
}

/** A "pair_error" event: linking ended without a link. [code] is a bridge error code ([WaErrors]); [detail] is content-free. */
data class WaPairError(val code: String, val detail: String = "") {
    companion object {
        fun fromJson(o: JSONObject) = WaPairError(code = o.str("code"), detail = o.str("detail"))
    }
}

/**
 * A "receipt" event: what became of messages this account sent.
 *
 * @param status one of [DELIVERED], [READ], [PLAYED]
 */
data class WaReceipt(
    val conversationId: String,
    val messageIds: List<String> = emptyList(),
    val status: String = "",
    val timestampMs: Long = 0,
) {
    companion object {
        const val DELIVERED = "delivered"
        const val READ = "read"

        /** A voice message was listened to (which also means read). */
        const val PLAYED = "played"

        fun fromJson(o: JSONObject): WaReceipt {
            val ids = o.optJSONArray("messageIds")
            return WaReceipt(
                conversationId = o.str("conversationId"),
                messageIds = if (ids == null) {
                    emptyList()
                } else {
                    (0 until ids.length()).mapNotNull { i -> ids.opt(i)?.takeUnless { it == JSONObject.NULL }?.toString() }
                },
                status = o.str("status"),
                timestampMs = o.optLong("timestampMs"),
            )
        }
    }
}

/** A "presence" event: whether one user is online now; [lastSeenMs] (epoch ms) is 0 when not known. */
data class WaPresenceEvent(
    val participantId: String,
    val online: Boolean = false,
    val lastSeenMs: Long = 0,
) {
    /** What [com.lightdecoder.chat.ChatBackend.presence] holds for this user. */
    fun toPresence() = Presence(online, lastSeenMs)

    companion object {
        fun fromJson(o: JSONObject) = WaPresenceEvent(
            participantId = o.str("participantId"),
            online = o.optBoolean("online"),
            lastSeenMs = o.optLong("lastSeenMs"),
        )
    }
}

/** A "history_sync" event: how far the phone's history sync has got, [progress] 0..100. */
data class WaHistorySync(val progress: Int = 0, val done: Boolean = false) {
    companion object {
        fun fromJson(o: JSONObject) = WaHistorySync(
            progress = o.optInt("progress").coerceIn(0, 100),
            done = o.optBoolean("done"),
        )
    }
}

/** Like optString, but a JSON null also gives "" (optString would say "null"). */
private fun JSONObject.str(key: String): String {
    val value = opt(key)
    return if (value == null || value == JSONObject.NULL) "" else value.toString()
}
