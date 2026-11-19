package com.lightdecoder.imessage.direct

import com.lightdecoder.archive.ParticipantRow
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.PartDto
import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MessagePart
import uniffi.rust_bridge.PushChatMessage

/**
 * How iMessages live in the Messages tab's archive, next to the Google Messages conversations:
 * every iMessage conversation and message id starts with [PREFIX], which Google Messages ids
 * never do, so the two never collide and [com.lightdecoder.relay.RelayManager] knows which link
 * a conversation belongs to.
 *
 * Conversation ids: `imsg:tel:+15551234567` (one other person, by their address) or
 * `imsg:group:<group guid>`. Message ids: `imsg:<message GUID>`, so the GUID Apple knows a
 * message by is always in its id (what unsend needs, also after a restart).
 *
 * Pure: no library, no database. Tested in DirectArchiveTest.
 */
object DirectArchive {

    const val PREFIX = "imsg:"
    private const val GROUP = "group:"

    /** What the thread screen shows as the transport of an iMessage. */
    const val TRANSPORT = "IMESSAGE"

    /** The status names the thread screen draws ticks for (Google Messages' names). */
    const val STATUS_SENT = "OUTGOING_COMPLETE"
    const val STATUS_DELIVERED = "OUTGOING_DELIVERED"
    const val STATUS_READ = "OUTGOING_DISPLAYED"
    const val STATUS_RECEIVED = "INCOMING_COMPLETE"

    fun isImessage(id: String): Boolean = id.startsWith(PREFIX)

    fun messageId(guid: String): String = PREFIX + guid

    /** The GUID of an iMessage's archive id, or null for any other message. */
    fun guidOf(messageId: String): String? = messageId.takeIf(::isImessage)?.removePrefix(PREFIX)

    /**
     * The conversation a message belongs to. One other person: by their address; a group: by
     * the group's guid when it has one, else by its members.
     */
    fun conversationId(participants: List<String>, me: Set<String>, groupGuid: String?): String {
        val others = participants.filter { it !in me }.distinct().sorted()
        return when {
            others.size <= 1 && (others.isNotEmpty() || participants.isNotEmpty()) ->
                PREFIX + (others.firstOrNull() ?: participants.first())
            !groupGuid.isNullOrBlank() -> PREFIX + GROUP + groupGuid
            else -> PREFIX + GROUP + "members:" + others.joinToString(",")
        }
    }

    fun isGroup(conversationId: String): Boolean = conversationId.startsWith(PREFIX + GROUP)

    /** A group's guid from its conversation id, null for one-to-one or a members-only group. */
    fun groupGuid(conversationId: String): String? =
        conversationId.takeIf { isGroup(it) }?.removePrefix(PREFIX + GROUP)?.takeIf { !it.startsWith("members:") }

    /**
     * The library's description of a stored conversation, for sending to it: its members' addresses
     * from the archive (the user's own included) and the group's guid and name.
     */
    fun conversationData(conversationId: String, participants: List<ParticipantRow>, groupName: String?): ConversationData {
        val handles = participants.map { it.id }.filter { it.startsWith("tel:") || it.startsWith("mailto:") }.distinct()
        return ConversationData(handles, if (isGroup(conversationId)) groupName?.takeIf { it.isNotBlank() } else null, groupGuid(conversationId), null)
    }

    /** What one event from Apple does to the archive. */
    sealed class Update {
        /** A new or changed message, in a conversation that is created or refreshed with it. */
        data class Upsert(val conversation: ConversationDto, val message: MessageDto) : Update()
        /** The sender unsent a message. */
        data class Remove(val messageId: String) : Update()
        /** A delivery or read receipt for messages the user sent. */
        data class Receipt(val messageIds: List<String>, val status: String) : Update()
        /** The sender edited a message: its new text. */
        data class Edit(val messageId: String, val text: String) : Update()
        object Ignore : Update()
    }

    /**
     * Turns one incoming event into its archive change. [me] are the user's own addresses (a message
     * from one of them is one the user sent from another device); [nameOf] gives a display name
     * for an address.
     */
    fun map(inst: MessageInst, me: Set<String>, nameOf: (String) -> String): Update {
        val sender = inst.sender
        return when (val m = inst.message) {
            is PushChatMessage.Message -> {
                val conversation = inst.conversation ?: return Update.Ignore
                val fromMe = sender != null && sender in me
                val participants = (conversation.participants + listOfNotNull(sender)).distinct()
                val id = conversationId(participants, me, conversation.senderGuid)
                val text = textOf(m.v1.parts.map { it.part })
                val group = isGroup(id)
                val others = participants.filter { it !in me }
                val name = when {
                    group -> conversation.cvName?.takeIf { it.isNotBlank() } ?: others.joinToString(", ") { nameOf(it) }
                    else -> nameOf(others.firstOrNull() ?: participants.first())
                }
                val ts = inst.sentTimestamp.toLong().takeIf { it > 0 } ?: System.currentTimeMillis()
                val conv = ConversationDto(
                    id = id,
                    name = name,
                    isGroup = group,
                    type = TRANSPORT,
                    defaultOutgoingId = participants.firstOrNull { it in me }.orEmpty(),
                    participants = participants.map { h ->
                        ParticipantDto(id = h, name = if (h in me) "" else nameOf(h), number = displayAddress(h), formattedNumber = displayAddress(h), isMe = h in me)
                    },
                    otherParticipantIds = others,
                )
                val msg = MessageDto(
                    id = messageId(inst.id),
                    conversationId = id,
                    senderId = sender.orEmpty(),
                    senderName = if (fromMe || sender == null) "" else nameOf(sender),
                    fromMe = fromMe,
                    timestampMs = ts,
                    direction = if (fromMe) "out" else "in",
                    status = if (fromMe) STATUS_SENT else STATUS_RECEIVED,
                    transport = TRANSPORT,
                    subject = m.v1.subject.orEmpty(),
                    text = text,
                    parts = if (text.isEmpty()) emptyList() else listOf(PartDto(partId = "0", kind = "text", text = text)),
                    replyToId = m.v1.replyGuid?.let(::messageId).orEmpty(),
                )
                Update.Upsert(conv, msg)
            }
            is PushChatMessage.Unsend -> Update.Remove(messageId(m.v1.tuuid))
            is PushChatMessage.Edit -> Update.Edit(messageId(m.v1.tuuid), textOf(m.v1.newParts.map { it.part }))
            is PushChatMessage.Delivered -> Update.Receipt(listOf(messageId(inst.id)), STATUS_DELIVERED)
            is PushChatMessage.Read -> Update.Receipt(listOf(messageId(inst.id)), STATUS_READ)
            else -> Update.Ignore
        }
    }

    /**
     * The text of a message: its text parts, and a line for each attachment (attachments are not
     * downloaded yet: the line says so, rather than the message looking empty).
     */
    fun textOf(parts: List<MessagePart>): String = parts.mapNotNull {
        when (it) {
            is MessagePart.Text -> it.v1
            is MessagePart.Mention -> it.v2
            is MessagePart.Attachment -> ATTACHMENT_LINE + it.v1.name.ifBlank { it.v1.mime }
            is MessagePart.Object -> null
        }
    }.filter { it.isNotEmpty() }.joinToString("\n")

    const val ATTACHMENT_LINE = "📎 Attachment (open it on another device for now): "

    /** For showing: the address without its scheme. */
    fun displayAddress(handle: String): String = Handles.display(handle)

    /** Orders outgoing statuses so a late or repeated receipt never moves a message backwards. */
    fun statusRank(status: String): Int = when (status) {
        STATUS_SENT -> 1
        STATUS_DELIVERED -> 2
        STATUS_READ -> 3
        else -> if (status.startsWith("OUTGOING_FAILED")) -1 else 0
    }

    fun statusCode(status: String): Int = when (status) {
        STATUS_DELIVERED -> 2
        STATUS_READ -> 11
        else -> 1
    }
}
