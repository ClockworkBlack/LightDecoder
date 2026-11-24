package com.lightdecoder.imessage.direct

import com.lightdecoder.archive.MessageRow

/**
 * "Report Junk", as an iPhone offers it under a message from someone unknown ("This sender is not in
 * your contacts.") and OpenBubbles' `markAsSpam` sends it: the person's last messages go to Apple,
 * then the chat is deleted. The rules, pure: tested in JunkReportsTest.
 */
object JunkReports {

    /**
     * Whether "Report Junk" is offered for a chat: one person ([others] is 1), not a group, still open
     * ([readOnly], [deleted]), who is not a known contact ([knownContact]: no contact's name for their
     * address; a picture alone makes nobody known), in a chat the user never wrote in ([wroteIn]), with something of theirs to
     * report ([somethingToReport]).
     */
    fun offered(
        isGroup: Boolean,
        readOnly: Boolean,
        deleted: Boolean,
        others: Int,
        knownContact: Boolean,
        wroteIn: Boolean,
        somethingToReport: Boolean,
    ): Boolean = !isGroup && !readOnly && !deleted && others == 1 && !knownContact && !wroteIn && somethingToReport

    /**
     * The messages a report carries, from a chat's [rows] (oldest first): the last
     * [DirectWire.MAX_JUNK_MESSAGES] someone else sent that Apple knows by their GUID, oldest first.
     */
    fun pick(rows: List<MessageRow>): List<MessageRow> =
        rows.filter { !it.isOutgoing && it.direction == "in" && !it.isLocal && DirectArchive.guidOf(it.id) != null }
            .takeLast(DirectWire.MAX_JUNK_MESSAGES)

    /**
     * [row] as the report names it, to the chat [target]: its GUID, its sender's address as the
     * library has it (its own; the chat's person's for a row without one), the number of people the
     * chat has besides the user, its text, when it was sent in seconds since 1970 (OpenBubbles':
     * `dateCreated.microsecondsSinceEpoch / 1000000`), and the library's descriptions of its
     * attachments that are small enough to go. Null for a row with no GUID or sender.
     */
    fun message(row: MessageRow, target: DirectWire.Target): DirectWire.JunkMessage? {
        val guid = DirectArchive.guidOf(row.id)?.takeIf { it.isNotBlank() && it.length <= DirectWire.MAX_GUID_CHARS } ?: return null
        val sender = row.senderId.takeIf { it.isNotBlank() }?.let { Handles.normalize(it) } ?: target.peer ?: return null
        // A Live Photo's moving part is never one of its own: only what the chat shows is reported.
        val motion = IMessageExtra.parse(row.extra).motionParts
        val attachments = row.media.filter { it.partId !in motion }.map { String(it.key, Charsets.UTF_8) }
            .filter { it.isNotEmpty() && it.length <= DirectWire.MAX_DESCRIPTOR_CHARS }
            .take(DirectWire.MAX_JUNK_ATTACHMENTS)
        return DirectWire.JunkMessage(
            guid = guid,
            sender = sender,
            conversationSize = target.participants.size.coerceAtLeast(1),
            text = row.text.take(DirectWire.MAX_JUNK_TEXT_CHARS),
            timeOfMessage = row.timestampMs / 1000.0,
            attachments = attachments,
        )
    }
}
