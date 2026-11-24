package com.lightdecoder.imessage.direct

import com.lightdecoder.chat.SharedNames
import com.lightdecoder.imessage.facetime.CallIds
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.PartDto
import uniffi.rust_bridge.AttributedBody
import uniffi.rust_bridge.CloudAttachment
import uniffi.rust_bridge.CloudChat
import uniffi.rust_bridge.CloudMessage
import uniffi.rust_bridge.CloudMessageBody
import uniffi.rust_bridge.MessageSummaryInfo

/**
 * Messages in iCloud, as the archive keeps them (B6.5): read-only history, pure. Its records come
 * from the user's own devices but carry other people's words, so everything is bounded as live
 * messages are: addresses only as addresses, names cleaned, texts and attributed bodies capped, a
 * time clamped, attachments a few per message, each only a record id LightDecoder downloads on
 * demand into a file of its own choosing. Every message is marked imported
 * ([IMessageExtra.imported]): the archive never notifies, plays effects or sends receipts for it.
 *
 * - Conversation ids as live ones: `imsg:<handle>` for one person, `imsg:group:<groupId>` for a
 *   group (whether an imported group's id is the one live messages carry is [U]; one that is not
 *   stays a chat of its own, read-only).
 * - Sender "" is the user. Apple's time (nanoseconds since 2001, or seconds in older records) to ms.
 * - Only iMessages that are messages: tapbacks, renames and other events, SMS chats, and records
 *   that are deleted in iCloud (null) are left out. GUIDs already stored or deleted here ([skip])
 *   are left out too.
 */
object CloudMapping {

    const val MAX_TEXT_CHARS = 20_000
    const val MAX_BODY_BYTES = 256 * 1024
    const val MAX_PARTICIPANTS = 32
    const val MAX_ATTACHMENTS = 10
    const val MAX_NAME_CHARS = 256
    private const val APPLE_EPOCH_MS = 978_307_200_000L

    /** One imported chat: its archive id and what Apple calls it (`chatIdentifier`). */
    data class Chat(val conversation: ConversationDto, val identifier: String, val group: Boolean)

    /** An attachment's record, as far as an imported message needs it. */
    data class Attachment(val record: String, val mime: String, val name: String, val size: Long)

    private val GROUP_ID = Regex("^[A-Za-z0-9-]{1,64}$")

    /** [c] (record [record]) as a chat, or null: not an iMessage chat, or not one LightDecoder can name. */
    fun chat(record: String, c: CloudChat): Chat? {
        if (!c.serviceName.equals("iMessage", ignoreCase = true)) return null
        val identifier = c.chatIdentifier.takeIf { it.isNotBlank() && it.length <= DirectWire.MAX_ADDRESS_CHARS } ?: return null
        val members = c.participants.asSequence().mapNotNull { handleOf(it.uri) }.distinctBy(CallIds::key).take(MAX_PARTICIPANTS).toList()
        val group = c.style == STYLE_GROUP
        val id = if (group) {
            val gid = c.groupId.takeIf { GROUP_ID.matches(it) } ?: return null
            DirectArchive.groupConversationId(gid)
        } else {
            val h = handleOf(identifier) ?: members.singleOrNull() ?: return null
            DirectArchive.PREFIX + h
        }
        val people = if (group) members else listOf(id.removePrefix(DirectArchive.PREFIX))
        if (people.isEmpty()) return null
        val name = if (group) SharedNames.clean(c.displayName.orEmpty()).take(MAX_NAME_CHARS) else ""
        return Chat(
            ConversationDto(
                id = id,
                name = name,
                isGroup = group,
                type = DirectArchive.TRANSPORT,
                participants = people.map { ParticipantDto(id = it, name = "", number = it, formattedNumber = it) },
            ),
            identifier,
            group,
        )
    }

    /** [a] as an attachment record, or null when its record id is not one a download may name. */
    fun attachment(record: String, a: CloudAttachment): Pair<String, Attachment>? {
        if (!DirectWire.isCloudRecord(record)) return null
        val guid = a.meta.guid.takeIf(RecycleRules::isGuid) ?: return null
        if (a.meta.hideAttachment) return null
        val mime = a.meta.mimeType?.takeIf { it.length <= 128 && '/' in it }.orEmpty()
        val name = SharedNames.clean(a.meta.transferName ?: a.meta.filename?.substringAfterLast('/') ?: "").take(128)
        return guid to Attachment(record, mime, name, a.meta.totalBytes.coerceIn(0, DirectMedia.MAX_DOWNLOAD_BYTES))
    }

    /**
     * [m] as an archive message in one of [chats] (by Apple's `chatIdentifier`), or null. [decode]
     * reads an attributed body (the library's; given [MAX_BODY_BYTES] at most); [attachments] by
     * their GUID; [sinceMs]: older messages are left out (the window); [skip]: GUIDs not taken again.
     */
    fun message(
        m: CloudMessage,
        chats: Map<String, String>,
        attachments: Map<String, Attachment>,
        decode: (ByteArray) -> AttributedBody?,
        skip: (String) -> Boolean,
        sinceMs: Long,
        now: Long,
        /** Reads a message's summary info (edits, unsends; given [MAX_BODY_BYTES] at most). */
        summary: (ByteArray) -> MessageSummaryInfo? = { null },
    ): MessageDto? {
        if (!m.service.equals("iMessage", ignoreCase = true)) return null
        val body = m.message as? CloudMessageBody.Message ?: return null
        if (body.reaction) return null
        val guid = m.guid.takeIf(RecycleRules::isGuid) ?: return null
        val id = DirectArchive.messageId(guid)
        if (skip(id)) return null
        val conversationId = chats[identifierOf(m.chatId) ?: return null] ?: return null
        val at = timeOf(m.time, now)
        if (at < sinceMs) return null
        val fromMe = m.sender.isEmpty()
        val sender = if (fromMe) "" else handleOf(m.sender)
            ?: conversationId.removePrefix(DirectArchive.PREFIX).takeIf { !DirectArchive.isGroup(conversationId) }
            ?: return null
        // Unsent on the user's devices (a part retracted): not brought back. One whose summary cannot
        // be read, or is too large to, is left out too (never shown as it was before an unsend).
        body.proto.messageSummaryInfo?.takeIf { it.isNotEmpty() }?.let { bytes ->
            if (bytes.size > MAX_BODY_BYTES) return null
            val info = runCatching { summary(bytes) }.getOrNull() ?: return null
            if (info.rp.isNotEmpty()) return null
        }
        val attributed = body.proto.attributedBody?.takeIf { it.isNotEmpty() && it.size <= MAX_BODY_BYTES }?.let { runCatching { decode(it) }.getOrNull() }
        val raw = body.proto.text ?: attributed?.text ?: ""
        val text = raw.replace("￼", "").trim().let { if (it.length > MAX_TEXT_CHARS) it.take(MAX_TEXT_CHARS) else it }
        val media = attributed?.runs.orEmpty().asSequence().mapNotNull { it.attachmentGuid }.distinct().take(MAX_ATTACHMENTS)
            .mapIndexedNotNull { i, g ->
                val a = attachments[g] ?: return@mapIndexedNotNull null
                PartDto(
                    partId = DirectArchive.partId(i + 1),
                    kind = "media",
                    mediaId = DirectArchive.mediaId(guid, i + 1),
                    key = (CLOUD_KEY + a.record).toByteArray(Charsets.UTF_8),
                    mime = a.mime,
                    fileName = a.name,
                    size = a.size,
                )
            }.toList()
        if (text.isEmpty() && media.isEmpty()) return null
        val subject = body.proto.subject?.let { SharedNames.clean(it) }.orEmpty()
        val extra = IMessageExtra(parts = if (text.isNotEmpty()) mapOf(0 to text) else emptyMap(), imported = true)
        return MessageDto(
            id = id,
            conversationId = conversationId,
            senderId = sender,
            fromMe = fromMe,
            timestampMs = at,
            direction = if (fromMe) "out" else "in",
            status = if (fromMe) DirectArchive.STATUS_DELIVERED else DirectArchive.STATUS_RECEIVED,
            transport = DirectArchive.TRANSPORT,
            subject = subject,
            text = text,
            parts = listOfNotNull(text.takeIf { it.isNotEmpty() }?.let { PartDto(partId = "0", kind = "text", text = it) }) + media,
            isOld = true,
            extra = extra.toJson(),
        )
    }

    /** What marks a media part's key as an imported attachment's record id (instead of a library description). */
    const val CLOUD_KEY = "cloud:"

    /** The record id a media part's [key] names, when it is an imported attachment's; else null. */
    fun recordOf(key: ByteArray): String? {
        if (key.size > CLOUD_KEY.length + DirectWire.MAX_CLOUD_RECORD_CHARS) return null
        val s = String(key, Charsets.UTF_8)
        return s.removePrefix(CLOUD_KEY).takeIf { s.startsWith(CLOUD_KEY) && DirectWire.isCloudRecord(it) }
    }

    /** `iMessage;-;+15551234567` / `iMessage;+;chat123`: the identifier after the service and the kind. */
    fun identifierOf(chatId: String): String? {
        if (chatId.length > DirectWire.MAX_ADDRESS_CHARS + 16) return null
        val parts = chatId.split(';')
        return parts.getOrNull(2)?.takeIf { parts.size == 3 && it.isNotBlank() } ?: chatId.takeIf { ';' !in it && it.isNotBlank() }
    }

    /** Apple's [t] as ms since 1970: nanoseconds since 2001 (or seconds, in older records), no earlier than 2001 nor later than [now]. */
    fun timeOf(t: Long, now: Long): Long {
        val ms = when {
            t <= 0 -> return APPLE_EPOCH_MS
            t < 10_000_000_000L -> t * 1000 + APPLE_EPOCH_MS
            else -> t / 1_000_000 + APPLE_EPOCH_MS
        }
        return ms.coerceIn(APPLE_EPOCH_MS, now)
    }

    /** "+15551234567", "user@example.com", or a "tel:"/"mailto:" one, as an address; null for anything else. */
    fun handleOf(raw: String): String? {
        val s = raw.trim()
        CallIds.handle(s)?.let { return CallIds.key(it) }
        val h = if ('@' in s) "mailto:" + s.lowercase() else if (s.startsWith("+")) "tel:$s" else return null
        return CallIds.handle(h)?.let(CallIds::key)
    }

    /**
     * [chats] and [messages] cut into "cloudBatch" updates, each [DirectWire.MAX_CLOUD_CHATS] chats and
     * [DirectWire.MAX_CLOUD_MESSAGES] messages at most and under [maxChars] encoded. A message too large
     * for one alone is left out.
     */
    fun batches(chats: List<ConversationDto>, messages: List<MessageDto>, maxChars: Int = DirectWire.MAX_UPDATE_CHARS - 2_000): List<DirectArchive.Update.CloudBatch> {
        val out = ArrayList<DirectArchive.Update.CloudBatch>()
        var c = ArrayList<ConversationDto>()
        var m = ArrayList<MessageDto>()
        var size = 64
        fun flush() {
            if (c.isNotEmpty() || m.isNotEmpty()) out += DirectArchive.Update.CloudBatch(c, m)
            c = ArrayList()
            m = ArrayList()
            size = 64
        }
        for (chat in chats) {
            val n = chat.toJson().toString().length + 1
            if (n > maxChars) continue
            if (size + n > maxChars || c.size >= DirectWire.MAX_CLOUD_CHATS) flush()
            c += chat
            size += n
        }
        for (msg in messages) {
            val n = msg.toJson(includeInlineData = false).toString().length + 1
            if (n > maxChars - 64) continue
            if (size + n > maxChars || m.size >= DirectWire.MAX_CLOUD_MESSAGES) flush()
            m += msg
            size += n
        }
        flush()
        return out
    }

    /** Apple's chat style of a group (`chat.db`'s 43; one person is 45). */
    const val STYLE_GROUP = 43L
}
