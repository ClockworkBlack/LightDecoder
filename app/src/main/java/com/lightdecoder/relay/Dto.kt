package com.lightdecoder.relay

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/*
 * Kotlin mirrors of the JSON the Go bridge hands over (contract §4). Parsing
 * is lenient on purpose: a missing or null key becomes the field's default,
 * so a newer or older bridge never crashes the app over one field.
 * Timestamps are epoch milliseconds; byte fields arrive as standard base64.
 *
 * Byte-array fields compare by reference in equals(); compare their contents
 * with contentEquals() where it matters.
 */

/** The bridge's own view of the pairing (StatusDTO). */
data class RelayStatus(
    val paired: Boolean = false,
    val connected: Boolean = false,
    val account: String = "",
    val phoneId: String = "",
    val sessionId: String = "",
) {
    companion object {
        fun fromJson(o: JSONObject) = RelayStatus(
            paired = o.optBoolean("paired"),
            connected = o.optBoolean("connected"),
            account = o.str("account"),
            phoneId = o.str("phoneId"),
            sessionId = o.str("sessionId"),
        )
    }
}

/** The emoji the user must tap on their phone to confirm pairing. */
data class PairingChallenge(
    val emoji: String,
    val emojiUrl: String = "",
    val account: String = "",
) {
    companion object {
        fun fromJson(o: JSONObject) = PairingChallenge(
            emoji = o.str("emoji"),
            emojiUrl = o.str("emojiUrl"),
            account = o.str("account"),
        )
    }
}

/** The conversation folders the phone can list. */
enum class Folder(val wire: String) {
    INBOX("inbox"),
    ARCHIVE("archive"),
    SPAM("spam");

    companion object {
        /** Null for folders that cannot be listed ("deleted", "other", unknown). */
        fun fromWire(wire: String): Folder? = entries.firstOrNull { it.wire == wire }
    }
}

data class ParticipantDto(
    val id: String,
    val name: String = "",
    val firstName: String = "",
    val number: String = "",
    val formattedNumber: String = "",
    val isMe: Boolean = false,
    val color: String = "",
    val contactId: String = "",
    val visible: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("firstName", firstName)
        put("number", number)
        put("formattedNumber", formattedNumber)
        put("isMe", isMe)
        put("color", color)
        put("contactId", contactId)
        put("visible", visible)
    }

    companion object {
        fun fromJson(o: JSONObject) = ParticipantDto(
            id = o.str("id"),
            name = o.str("name"),
            firstName = o.str("firstName"),
            number = o.str("number"),
            formattedNumber = o.str("formattedNumber"),
            isMe = o.optBoolean("isMe"),
            color = o.str("color"),
            contactId = o.str("contactId"),
            visible = o.optBoolean("visible", true),
        )
    }
}

/**
 * One conversation as the phone describes it.
 *
 * @param type   "SMS", "RCS" or "UNKNOWN"
 * @param folder "inbox", "archive", "spam", "deleted" or "other"
 */
data class ConversationDto(
    val id: String,
    val name: String = "",
    val isGroup: Boolean = false,
    val type: String = "UNKNOWN",
    val folder: String = "inbox",
    val statusCode: Int = 0,
    val unread: Boolean = false,
    val pinned: Boolean = false,
    val readOnly: Boolean = false,
    val muted: Boolean = false,
    val lastMessageTs: Long = 0,
    val latestMessageId: String = "",
    val snippet: String = "",
    val snippetFromMe: Boolean = false,
    val snippetSender: String = "",
    val avatarColor: String = "",
    val defaultOutgoingId: String = "",
    val participants: List<ParticipantDto> = emptyList(),
    val otherParticipantIds: List<String> = emptyList(),
) {
    val isRcs: Boolean get() = type == "RCS"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("isGroup", isGroup)
        put("type", type)
        put("folder", folder)
        put("statusCode", statusCode)
        put("unread", unread)
        put("pinned", pinned)
        put("readOnly", readOnly)
        put("muted", muted)
        put("lastMessageTs", lastMessageTs)
        put("latestMessageId", latestMessageId)
        put("snippet", snippet)
        put("snippetFromMe", snippetFromMe)
        put("snippetSender", snippetSender)
        put("avatarColor", avatarColor)
        put("defaultOutgoingId", defaultOutgoingId)
        put("participants", JSONArray().apply { participants.forEach { put(it.toJson()) } })
        put("otherParticipantIds", JSONArray(otherParticipantIds))
    }

    companion object {
        fun fromJson(o: JSONObject) = ConversationDto(
            id = o.str("id"),
            name = o.str("name"),
            isGroup = o.optBoolean("isGroup"),
            type = o.str("type", "UNKNOWN"),
            folder = o.str("folder", "inbox"),
            statusCode = o.optInt("statusCode"),
            unread = o.optBoolean("unread"),
            pinned = o.optBoolean("pinned"),
            readOnly = o.optBoolean("readOnly"),
            muted = o.optBoolean("muted"),
            lastMessageTs = o.optLong("lastMessageTs"),
            latestMessageId = o.str("latestMessageId"),
            snippet = o.str("snippet"),
            snippetFromMe = o.optBoolean("snippetFromMe"),
            snippetSender = o.str("snippetSender"),
            avatarColor = o.str("avatarColor"),
            defaultOutgoingId = o.str("defaultOutgoingId"),
            participants = o.objects("participants").map(ParticipantDto::fromJson),
            otherParticipantIds = o.strings("otherParticipantIds"),
        )

        fun listFromJson(arr: JSONArray?): List<ConversationDto> = arr.objects().map(::fromJson)
    }
}

/**
 * One part of a message: text, or a media attachment.
 *
 * @param kind          "text" or "media"
 * @param key           AES key for [mediaId]; empty when there is none
 * @param mime          the resolved MIME type, empty if the phone did not say
 * @param inlineData    the media itself when the phone sent it inline; usually empty
 * @param needsFullSize only a thumbnail exists so far; the full size must be requested
 */
data class PartDto(
    val partId: String,
    val kind: String = "text",
    val text: String = "",
    val mediaId: String = "",
    val key: ByteArray = EMPTY,
    val thumbMediaId: String = "",
    val thumbKey: ByteArray = EMPTY,
    val mime: String = "",
    val fileName: String = "",
    val size: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val inlineData: ByteArray = EMPTY,
    val needsFullSize: Boolean = false,
) {
    val isMedia: Boolean get() = kind == "media"

    fun toJson(includeInlineData: Boolean = true): JSONObject = JSONObject().apply {
        put("partId", partId)
        put("kind", kind)
        put("text", text)
        put("mediaId", mediaId)
        put("key", base64(key))
        put("thumbMediaId", thumbMediaId)
        put("thumbKey", base64(thumbKey))
        put("mime", mime)
        put("fileName", fileName)
        put("size", size)
        put("width", width)
        put("height", height)
        put("inlineData", if (includeInlineData) base64(inlineData) else "")
        put("needsFullSize", needsFullSize)
    }

    companion object {
        fun fromJson(o: JSONObject) = PartDto(
            partId = o.str("partId"),
            kind = o.str("kind", "text"),
            text = o.str("text"),
            mediaId = o.str("mediaId"),
            key = o.bytes("key"),
            thumbMediaId = o.str("thumbMediaId"),
            thumbKey = o.bytes("thumbKey"),
            mime = o.str("mime"),
            fileName = o.str("fileName"),
            size = o.optLong("size"),
            width = o.optInt("width"),
            height = o.optInt("height"),
            inlineData = o.bytes("inlineData"),
            needsFullSize = o.optBoolean("needsFullSize"),
        )
    }
}

/** One emoji reaction and who left it. */
data class ReactionDto(
    val emoji: String,
    val participantIds: List<String> = emptyList(),
    val fromMe: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("emoji", emoji)
        put("participantIds", JSONArray(participantIds))
        put("fromMe", fromMe)
    }

    companion object {
        fun fromJson(o: JSONObject) = ReactionDto(
            emoji = o.str("emoji"),
            participantIds = o.strings("participantIds"),
            fromMe = o.optBoolean("fromMe"),
        )

        fun listToJson(reactions: List<ReactionDto>): JSONArray =
            JSONArray().apply { reactions.forEach { put(it.toJson()) } }

        fun listFromJson(arr: JSONArray?): List<ReactionDto> = arr.objects().map(::fromJson)
    }
}

/**
 * One message, live or from history. The same id arrives again whenever its
 * status, reactions or media change, so consumers upsert.
 *
 * @param tmpId     the id this app chose when sending it, echoed back; empty otherwise
 * @param direction "out", "in", "system" or "deleted"
 * @param status    the phone's status enum name, e.g. "OUTGOING_DELIVERED"
 * @param transport "SMS", "MMS", "RCS" or "UNKNOWN"
 * @param text      the text parts joined by newlines
 * @param isOld     true for history the phone re-sent, not a new arrival
 */
data class MessageDto(
    val id: String,
    val conversationId: String,
    val tmpId: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val fromMe: Boolean = false,
    val timestampMs: Long = 0,
    val direction: String = "in",
    val status: String = "",
    val statusCode: Int = 0,
    val errorText: String = "",
    val transport: String = "UNKNOWN",
    val subject: String = "",
    val text: String = "",
    val parts: List<PartDto> = emptyList(),
    val replyToId: String = "",
    val reactions: List<ReactionDto> = emptyList(),
    val isOld: Boolean = false,
    val mmsPendingDownload: Boolean = false,
) {
    val isDeleted: Boolean get() = direction == "deleted"
    val mediaParts: List<PartDto> get() = parts.filter { it.isMedia }

    /** [includeInlineData] false leaves inline media out, for compact storage. */
    fun toJson(includeInlineData: Boolean = true): JSONObject = JSONObject().apply {
        put("id", id)
        put("tmpId", tmpId)
        put("conversationId", conversationId)
        put("senderId", senderId)
        put("senderName", senderName)
        put("fromMe", fromMe)
        put("timestampMs", timestampMs)
        put("direction", direction)
        put("status", status)
        put("statusCode", statusCode)
        put("errorText", errorText)
        put("transport", transport)
        put("subject", subject)
        put("text", text)
        put("parts", JSONArray().apply { parts.forEach { put(it.toJson(includeInlineData)) } })
        put("replyToId", replyToId)
        put("reactions", ReactionDto.listToJson(reactions))
        put("isOld", isOld)
        put("mmsPendingDownload", mmsPendingDownload)
    }

    companion object {
        fun fromJson(o: JSONObject) = MessageDto(
            id = o.str("id"),
            conversationId = o.str("conversationId"),
            tmpId = o.str("tmpId"),
            senderId = o.str("senderId"),
            senderName = o.str("senderName"),
            fromMe = o.optBoolean("fromMe"),
            timestampMs = o.optLong("timestampMs"),
            direction = o.str("direction", "in"),
            status = o.str("status"),
            statusCode = o.optInt("statusCode"),
            errorText = o.str("errorText"),
            transport = o.str("transport", "UNKNOWN"),
            subject = o.str("subject"),
            text = o.str("text"),
            parts = o.objects("parts").map(PartDto::fromJson),
            replyToId = o.str("replyToId"),
            reactions = ReactionDto.listFromJson(o.optJSONArray("reactions")),
            isOld = o.optBoolean("isOld"),
            mmsPendingDownload = o.optBoolean("mmsPendingDownload"),
        )
    }
}

/** Where a page of history ended; pass it back unchanged to fetch the page before it. */
data class Cursor(
    val lastItemId: String,
    val lastItemTimestampMs: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("lastItemId", lastItemId)
        put("lastItemTimestampMs", lastItemTimestampMs)
    }

    companion object {
        /** Null for a missing cursor or one without an item id. */
        fun fromJson(o: JSONObject?): Cursor? {
            if (o == null) return null
            val id = o.str("lastItemId")
            if (id.isEmpty()) return null
            return Cursor(id, o.optLong("lastItemTimestampMs"))
        }
    }
}

/** A page of history, oldest first. [cursor] is null when the phone gave none. */
data class MessagePage(
    val messages: List<MessageDto>,
    val cursor: Cursor? = null,
    val total: Int = 0,
) {
    companion object {
        fun fromJson(o: JSONObject) = MessagePage(
            messages = o.objects("messages").map(MessageDto::fromJson),
            cursor = Cursor.fromJson(o.optJSONObject("cursor")),
            total = o.optInt("total"),
        )
    }
}

/**
 * A protocol-switch tombstone stored in the archive, as
 * [RelayBridge.seedProtocolSwitches] takes it ([status] and [statusCode] as in
 * [MessageDto]).
 */
data class ProtocolSwitchSeed(
    val conversationId: String,
    val status: String,
    val statusCode: Int,
    val timestampMs: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("conversationId", conversationId)
        put("status", status)
        put("statusCode", statusCode)
        put("timestampMs", timestampMs)
    }

    companion object {
        /** The statuses Go's SeedProtocolSwitches reads (the one-to-one switches, as in the connector). */
        val STATUSES = listOf(
            "MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_RCS_TO_E2EE",
            "MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_TEXT_TO_E2EE",
            "MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_E2EE_TO_RCS",
            "MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_E2EE_TO_TEXT",
        )
    }
}

/** One entry of the phone's contact list. [id] is the contact's participant id. */
data class ContactDto(
    val id: String,
    val name: String = "",
    val number: String = "",
    val formattedNumber: String = "",
    val color: String = "",
    val contactId: String = "",
) {
    companion object {
        fun fromJson(o: JSONObject) = ContactDto(
            id = o.str("id"),
            name = o.str("name"),
            number = o.str("number"),
            formattedNumber = o.str("formattedNumber"),
            color = o.str("color"),
            contactId = o.str("contactId"),
        )

        fun listFromJson(arr: JSONArray?): List<ContactDto> = arr.objects().map(::fromJson)
    }
}

/** A participant's avatar picture (image bytes, usually JPEG). */
data class ThumbnailDto(
    val participantId: String,
    val data: ByteArray,
) {
    companion object {
        fun fromJson(o: JSONObject) = ThumbnailDto(
            participantId = o.str("participantId"),
            data = o.bytes("data"),
        )

        fun listFromJson(arr: JSONArray?): List<ThumbnailDto> = arr.objects().map(::fromJson)
    }
}

data class TypingDto(
    val conversationId: String,
    val participantId: String = "",
    val number: String = "",
    val typing: Boolean = false,
) {
    companion object {
        fun fromJson(o: JSONObject) = TypingDto(
            conversationId = o.str("conversationId"),
            participantId = o.str("participantId"),
            number = o.str("number"),
            typing = o.optBoolean("typing"),
        )
    }
}

/** One SIM in the phone; [participantId] is "me" in conversations sent from it. */
data class SimDto(
    val participantId: String = "",
    val number: String = "",
    val label: String = "",
    val rcsEnabled: Boolean = false,
) {
    companion object {
        fun fromJson(o: JSONObject) = SimDto(
            participantId = o.str("participantId"),
            number = o.str("number"),
            label = o.str("label"),
            rcsEnabled = o.optBoolean("rcsEnabled"),
        )
    }
}

/** The phone's Messages settings that matter here. */
data class SettingsDto(
    val sims: List<SimDto> = emptyList(),
    val rcsEnabled: Boolean = false,
    val sendReadReceipts: Boolean = false,
    val showTypingIndicators: Boolean = false,
    val isDefaultSmsApp: Boolean = false,
) {
    companion object {
        fun fromJson(o: JSONObject) = SettingsDto(
            sims = o.objects("sims").map(SimDto::fromJson),
            rcsEnabled = o.optBoolean("rcsEnabled"),
            sendReadReceipts = o.optBoolean("sendReadReceipts"),
            showTypingIndicators = o.optBoolean("showTypingIndicators"),
            isDefaultSmsApp = o.optBoolean("isDefaultSmsApp"),
        )
    }
}

/**
 * What the phone said about a send. [ok] false with an empty [code] means the
 * phone answered with a failure [status]; [code] is a bridge error code (§2.3).
 */
data class SendResult(
    val tmpId: String,
    val ok: Boolean = false,
    val status: String = "",
    val code: String = "",
) {
    companion object {
        fun fromJson(o: JSONObject) = SendResult(
            tmpId = o.str("tmpId"),
            ok = o.optBoolean("ok"),
            status = o.str("status"),
            code = o.str("code"),
        )
    }
}

/** A "state" event: a lifecycle change of the link (contract §3). [detail] is content-free. */
data class RelayStateEvent(
    val state: String,
    val code: String = "",
    val detail: String = "",
) {
    companion object {
        fun fromJson(o: JSONObject) = RelayStateEvent(
            state = o.str("state"),
            code = o.str("code"),
            detail = o.str("detail"),
        )
    }
}

private val EMPTY = ByteArray(0)

/** Like optString, but a JSON null also gives the fallback (optString would say "null"). */
private fun JSONObject.str(key: String, fallback: String = ""): String {
    val value = opt(key)
    return if (value == null || value == JSONObject.NULL) fallback else value.toString()
}

private fun JSONObject.strings(key: String): List<String> {
    val arr = optJSONArray(key) ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        arr.opt(i)?.takeUnless { it == JSONObject.NULL }?.toString()
    }
}

private fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key).objects()

private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

/** Standard base64 as Go writes []byte; anything unreadable counts as empty. */
private fun JSONObject.bytes(key: String): ByteArray {
    val text = str(key)
    if (text.isEmpty()) return EMPTY
    return try {
        Base64.getDecoder().decode(text)
    } catch (e: IllegalArgumentException) {
        try {
            Base64.getUrlDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            EMPTY
        }
    }
}

private fun base64(bytes: ByteArray): String =
    if (bytes.isEmpty()) "" else Base64.getEncoder().encodeToString(bytes)
