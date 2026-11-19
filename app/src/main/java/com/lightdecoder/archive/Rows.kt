package com.lightdecoder.archive

import com.lightdecoder.relay.Cursor
import com.lightdecoder.relay.Folder
import com.lightdecoder.relay.ReactionDto

/*
 * What the archive hands back: one class per table, read-only snapshots.
 * Byte-array fields compare by reference in equals().
 */

/**
 * @param folder wire name: "inbox", "archive", "spam" or "other"
 * @param lastMessageTs 0 while only a placeholder is known
 */
data class ConversationRow(
    val id: String,
    val name: String,
    val isGroup: Boolean,
    val isRcs: Boolean,
    val folder: String,
    val statusCode: Int,
    val unread: Boolean,
    val pinned: Boolean,
    val readOnly: Boolean,
    val muted: Boolean,
    val lastMessageTs: Long,
    val latestMessageId: String,
    val snippet: String,
    val snippetFromMe: Boolean,
    val snippetSender: String,
    val avatarColor: String,
    val defaultOutgoingId: String,
    val updatedAt: Long,
) {
    /** The folder, including the local [Folder.DELETED]; null for "other" and anything unknown. */
    val folderEnum: Folder? get() = Folder.fromStored(folder)
}

data class ParticipantRow(
    val conversationId: String,
    val id: String,
    val name: String,
    val firstName: String,
    val number: String,
    val formattedNumber: String,
    val isMe: Boolean,
    val color: String,
    val contactId: String,
    val visible: Boolean,
)

/**
 * One message. Messages this app is still sending have the id "tmp:<tmpId>"
 * until the phone echoes the real one.
 *
 * @param direction  "out", "in" or "system"
 * @param localState one of [LocalState]
 * @param media      the media parts, in message order
 */
data class MessageRow(
    val id: String,
    val conversationId: String,
    val tmpId: String,
    val senderId: String,
    val senderName: String,
    val isOutgoing: Boolean,
    val timestampMs: Long,
    val direction: String,
    val status: String,
    val statusCode: Int,
    val errorText: String,
    val transport: String,
    val text: String,
    val subject: String,
    val replyToId: String,
    val reactions: List<ReactionDto>,
    val localState: String,
    val media: List<MediaRow>,
    val updatedAt: Long,
) {
    /** True while only the local copy of an outgoing message exists. */
    val isLocal: Boolean get() = id.startsWith(TMP_PREFIX)

    companion object {
        const val TMP_PREFIX = "tmp:"

        fun tmpMessageId(tmpId: String) = TMP_PREFIX + tmpId
    }
}

/**
 * One media part of a message, plus what this app has done with it.
 *
 * @param key           AES key for [mediaId]; empty when there is none
 * @param inlineData    the media bytes when the phone sent them inline; usually empty
 * @param localPath     the downloaded (or, while sending, the local) file; empty if none yet
 * @param thumbPath     the downloaded thumbnail; empty if none yet
 */
data class MediaRow(
    val messageId: String,
    val partId: String,
    val mediaId: String,
    val key: ByteArray,
    val thumbMediaId: String,
    val thumbKey: ByteArray,
    val mime: String,
    val fileName: String,
    val size: Long,
    val width: Int,
    val height: Int,
    val inlineData: ByteArray,
    val needsFullSize: Boolean,
    val localPath: String,
    val thumbPath: String,
    val fullSizeRequested: Boolean,
    val stickerChecked: Boolean,
) {
    /**
     * The phone sent a small preview inline (mediaData) next to the real
     * media, which is a media id or a thumbnail to download. The preview is
     * only a placeholder, never the picture: shown as the picture it is a
     * blurry postage stamp. The connector uses inline bytes only when there
     * is nothing else.
     */
    val inlineIsPreview: Boolean get() = inlineData.isNotEmpty() && (mediaId.isNotEmpty() || thumbMediaId.isNotEmpty())

    /** The inline bytes are the media itself: there is nothing else to download. */
    val inlineIsMedia: Boolean get() = inlineData.isNotEmpty() && mediaId.isEmpty() && thumbMediaId.isEmpty()
}

/**
 * A message waiting to be sent. [idempotencyKey] is the tmpId the message was
 * created with, so retries never send twice.
 *
 * @param status one of [OutboxStatus]
 */
data class OutboxRow(
    val idempotencyKey: String,
    val conversationId: String,
    val textBody: String = "",
    val mediaLocalPath: String = "",
    val mediaMime: String = "",
    val mediaName: String = "",
    val replyToId: String = "",
    val status: String = OutboxStatus.PENDING,
    val attempts: Int = 0,
    val lastError: String = "",
    val createdAt: Long,
)

/** The last known state of the link, for the status screen. Error text is content-free. */
data class HealthRow(
    val status: String = "unknown",
    val lastEventTs: Long = 0,
    val lastTokenRefresh: Long = 0,
    val lastError: String = "",
    val account: String = "",
    val phoneId: String = "",
)

/** How far history has been imported for one conversation. */
data class SyncStateRow(
    val conversationId: String,
    val cursorId: String = "",
    val cursorTs: Long = 0,
    val historyComplete: Boolean = false,
    val lastBackfillAt: Long = 0,
) {
    /** The cursor to fetch the page before the oldest imported one; null to start at the newest. */
    val cursor: Cursor? get() = if (cursorId.isEmpty()) null else Cursor(cursorId, cursorTs)
}

/** One full-text search result. [snippet] is a short plain-text excerpt around the match. */
data class SearchHit(
    val messageId: String,
    val conversationId: String,
    val conversationName: String,
    val snippet: String,
    val timestampMs: Long,
    /** The archive it was found in (the Messages tab also searches iMessage's, with Blue Bubbles on). */
    val service: com.lightdecoder.chat.ChatService = com.lightdecoder.chat.ChatService.MESSAGES,
)

/**
 * One phone contact. [customAlias] and [avatarPath] are set locally and kept
 * across refreshes.
 */
data class ContactRow(
    val contactKey: String,
    val participantId: String,
    val contactId: String,
    val phoneNumber: String,
    val formattedNumber: String,
    val displayName: String,
    val customAlias: String,
    val avatarPath: String,
    val color: String,
    val updatedAt: Long,
)

/** Values of messages.local_state. */
object LocalState {
    const val SYNCED = "synced"
    const val PENDING = "pending"
    const val SENDING = "sending"
    const val FAILED = "failed"
}

/** Values of outbox_queue.status. */
object OutboxStatus {
    const val PENDING = "PENDING"
    const val SENDING = "SENDING"
    const val SENT = "SENT"
    const val FAILED = "FAILED"
}
