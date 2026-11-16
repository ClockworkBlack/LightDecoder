package com.lightdecoder.ui.chat

import com.lightdecoder.archive.MediaRow
import com.lightdecoder.archive.MessageRow

/** The quoted message shown above a reply. [text] is already a one-line preview. */
data class Quote(val messageId: String, val sender: String, val text: String)

/** One row of a thread: the history header, a day separator, or a message. */
sealed class ThreadItem {
    abstract val key: String

    /** At the top: loading older messages, or the start of the history. */
    data class Header(val loading: Boolean, val complete: Boolean) : ThreadItem() {
        override val key: String get() = "header"
    }

    data class Day(val dayStartMs: Long, val label: String) : ThreadItem() {
        override val key: String get() = "day:$dayStartMs"
    }

    /**
     * @param showSender the sender's name goes above the bubble (groups, first of a run)
     * @param highlighted briefly marked, after jumping to it from search or a quote
     */
    data class Message(
        val row: MessageRow,
        override val key: String,
        val quote: Quote?,
        val showSender: Boolean,
        val highlighted: Boolean,
    ) : ThreadItem() {
        /**
         * For DiffUtil. MessageRow's byte arrays compare by reference, so a
         * plain equals would call every reloaded message changed; this
         * compares what is drawn instead.
         */
        fun sameContent(other: Message): Boolean =
            quote == other.quote &&
                showSender == other.showSender &&
                highlighted == other.highlighted &&
                row.copy(media = emptyList()) == other.row.copy(media = emptyList()) &&
                row.media.map(::mediaSignature) == other.row.media.map(::mediaSignature)
    }
}

/** Turns a thread's messages into what the list draws. Pure, so it can be tested. */
object ThreadItems {

    /**
     * The key that follows a message from "being sent" to "sent": the local
     * copy "tmp:<tmpId>" and the phone's echo share the tmpId, so the bubble
     * stays in place instead of disappearing and reappearing.
     */
    fun keyFor(row: MessageRow): String = if (row.tmpId.isNotEmpty()) "t:${row.tmpId}" else "m:${row.id}"

    /**
     * @param messages oldest first, as the archive returns them
     * @param dayStart start of the day a timestamp falls in
     * @param dayLabel the header text for a day start
     */
    fun build(
        messages: List<MessageRow>,
        isGroup: Boolean,
        quotes: Map<String, Quote>,
        header: ThreadItem.Header?,
        highlightId: String?,
        dayStart: (Long) -> Long,
        dayLabel: (Long) -> String,
    ): List<ThreadItem> {
        val items = ArrayList<ThreadItem>(messages.size + 16)
        if (header != null) items.add(header)
        val usedKeys = HashSet<String>()
        var lastDay = Long.MIN_VALUE
        var previous: MessageRow? = null
        for (row in messages) {
            val day = dayStart(row.timestampMs)
            val newDay = day != lastDay
            if (newDay) {
                items.add(ThreadItem.Day(day, dayLabel(day)))
                lastDay = day
            }
            val incoming = !row.isOutgoing && row.direction == "in"
            val sameSenderAsBefore = previous != null && !newDay &&
                previous.direction == row.direction && previous.senderId == row.senderId
            val showSender = isGroup && incoming && !sameSenderAsBefore
            // Keys must be unique, or the list's stable ids clash; fall back to the message id.
            var key = keyFor(row)
            if (!usedKeys.add(key)) {
                key = "m:${row.id}"
                usedKeys.add(key)
            }
            items.add(
                ThreadItem.Message(
                    row = row,
                    key = key,
                    quote = if (row.replyToId.isNotEmpty()) quotes[row.replyToId] else null,
                    showSender = showSender,
                    highlighted = highlightId != null && row.id == highlightId,
                )
            )
            previous = row
        }
        return items
    }

    /** A one-line preview of a message for quotes and the reply bar; [mediaLabel] when it has no text. */
    fun preview(row: MessageRow, mediaLabel: String): String {
        val text = row.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return when {
            text.isNotEmpty() -> text.take(PREVIEW_CHARS)
            row.media.isNotEmpty() -> mediaLabel
            else -> ""
        }
    }

    private const val PREVIEW_CHARS = 140
}

/** What of a media part changes how it is drawn. */
internal fun mediaSignature(m: MediaRow): List<Any> = listOf(
    m.partId, m.mediaId, m.thumbMediaId, m.mime, m.fileName, m.localPath, m.thumbPath,
    m.needsFullSize, m.fullSizeRequested, m.inlineData.size, m.width, m.height,
)
