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

    /**
     * A day's separator ("Today"). In iPhone-style chats, the time between messages instead
     * ([PhoneStyle.timeHeader]): [dayStartMs] is then the message's own time, and [time] its clock time.
     */
    data class Day(val dayStartMs: Long, val label: String, val time: String = "") : ThreadItem() {
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
        /**
         * Pictures sent together: this message and the next are only pictures,
         * from the same person, moments apart. They are drawn as one stack,
         * with the time under the last picture only.
         */
        val stackedWithNext: Boolean = false,
        /** "Delivered" or "Read …" under it ([ThreadItems.receipts]), else null. */
        val receipt: Receipt? = null,
        /** iPhone-style chats: where it is in its sender's run ([PhoneStyle.places]); null otherwise. */
        val place: PhoneStyle.Place? = null,
        /** iPhone-style group chats: someone else's message, which leaves room at its side for the sender's picture. */
        val besideAvatar: Boolean = false,
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
                stackedWithNext == other.stackedWithNext &&
                receipt == other.receipt &&
                place == other.place &&
                besideAvatar == other.besideAvatar &&
                row.copy(media = emptyList()) == other.row.copy(media = emptyList()) &&
                row.media.map(::mediaSignature) == other.row.media.map(::mediaSignature)
    }
}

/**
 * What an iPhone writes under one of the user's iMessages: "Delivered", or "Read" with when
 * ([readAtMs], 0 when that is not known).
 */
data class Receipt(val read: Boolean, val readAtMs: Long = 0)

/** Turns a thread's messages into what the list draws. Pure, so it can be tested. */
object ThreadItems {

    /**
     * Which of the user's messages say "Read" or "Delivered" under them, as on an iPhone: the
     * latest one that was read, and the latest one delivered after it (delivered, not read yet).
     * Only messages [hasReceipts] picks (iMessages that went through the iMessage library, whose
     * receipts come from Apple); the rest keep their ticks. Message id -> read (true) or delivered.
     *
     * @param messages oldest first
     */
    fun receipts(messages: List<MessageRow>, hasReceipts: (MessageRow) -> Boolean): Map<String, Boolean> {
        val mine = messages.filter { it.isOutgoing && it.direction == "out" && !it.isLocal && hasReceipts(it) }
        val lastRead = mine.indexOfLast { MessageStatus.tickFor(it) == Tick.READ }
        val lastDelivered = mine.indexOfLast { MessageStatus.tickFor(it) == Tick.DELIVERED }
        val marks = LinkedHashMap<String, Boolean>()
        if (lastRead >= 0) marks[mine[lastRead].id] = true
        if (lastDelivered > lastRead) marks[mine[lastDelivered].id] = false
        return marks
    }

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
        /** "Delivered" / "Read …" under messages, by id ([receipts]). */
        receipts: Map<String, Receipt> = emptyMap(),
        /** iPhone-style chats ([PhoneStyle]): times between messages instead of days, and runs with tails. */
        phone: Boolean = false,
        /** The clock time of a time header ([phone]). */
        clock: (Long) -> String = { "" },
    ): List<ThreadItem> {
        val items = ArrayList<ThreadItem>(messages.size + 16)
        if (header != null) items.add(header)
        val usedKeys = HashSet<String>()
        var lastDay = Long.MIN_VALUE
        var previous: MessageRow? = null
        val places = if (phone) PhoneStyle.places(messages, dayStart) else emptyList()
        for ((index, row) in messages.withIndex()) {
            val day = dayStart(row.timestampMs)
            val newDay = day != lastDay
            if (phone) {
                if (PhoneStyle.timeHeader(previous, row, dayStart)) {
                    // Keyed by its message's time; two at the same time (out of order) must still differ.
                    var at = row.timestampMs
                    while (!usedKeys.add("day:$at")) at++
                    items.add(ThreadItem.Day(at, dayLabel(day), clock(row.timestampMs)))
                }
                lastDay = day
            } else if (newDay) {
                items.add(ThreadItem.Day(day, dayLabel(day)))
                lastDay = day
            }
            val incoming = !row.isOutgoing && row.direction == "in"
            val sameSenderAsBefore = previous != null && !newDay &&
                previous.direction == row.direction && previous.senderId == row.senderId
            val place = places.getOrNull(index)
            val showSender = isGroup && incoming && (place?.first ?: !sameSenderAsBefore)
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
                    stackedWithNext = messages.getOrNull(index + 1)?.let { stacks(row, it, dayStart) } == true,
                    receipt = receipts[row.id],
                    place = place,
                    besideAvatar = phone && isGroup && incoming,
                )
            )
            previous = row
        }
        return items
    }

    /**
     * Whether [next] continues a stack of pictures [row] is in: both only
     * pictures (no text, subject or reply), the same side and sender, the
     * same day and at most [STACK_GAP_MS] apart. Several pictures picked at
     * once go out as messages of their own, one after another (the phone
     * keeps only one part of a message with several); this shows them as
     * the one message they were sent as.
     */
    fun stacks(row: MessageRow, next: MessageRow, dayStart: (Long) -> Long): Boolean =
        picturesOnly(row) && picturesOnly(next) &&
            row.direction == next.direction && row.isOutgoing == next.isOutgoing && row.senderId == next.senderId &&
            next.timestampMs >= row.timestampMs && next.timestampMs - row.timestampMs <= STACK_GAP_MS &&
            dayStart(row.timestampMs) == dayStart(next.timestampMs)

    /** Only pictures or video stills: no text, subject or reply quote. */
    fun picturesOnly(row: MessageRow): Boolean =
        row.media.isNotEmpty() && row.media.all(::isVisualPart) && row.text.isEmpty() &&
            row.subject.isBlank() && row.replyToId.isEmpty()

    /** Pictures sent together arrive within this of each other. */
    const val STACK_GAP_MS = 3 * 60 * 1000L

    /**
     * A one-line preview of a message for quotes and the reply bar; [mediaLabel] when it has no
     * text, [inkedLabel] (when given) for a text sent with invisible ink, which shows only in its bubble.
     */
    fun preview(row: MessageRow, mediaLabel: String, inkedLabel: String = ""): String {
        val shown = if (inkedLabel.isEmpty()) row.text else com.lightdecoder.imessage.direct.IMessageExtra.shownText(row.text, row.extra, inkedLabel)
        val text = shown.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return when {
            text.isNotEmpty() -> text.take(PREVIEW_CHARS)
            row.media.isNotEmpty() -> mediaLabel
            else -> ""
        }
    }

    private const val PREVIEW_CHARS = 140
}

/** A picture, video or sticker (drawn as an image), not a file, voice message or other audio. */
internal fun isVisualPart(part: MediaRow): Boolean =
    part.mime.startsWith("image/") || part.mime.startsWith("video/") ||
        (part.mime.isEmpty() && (part.thumbMediaId.isNotEmpty() || part.width > 0 || part.inlineData.isNotEmpty()))

/** What of a media part changes how it is drawn. */
internal fun mediaSignature(m: MediaRow): List<Any> = listOf(
    m.partId, m.mediaId, m.thumbMediaId, m.mime, m.fileName, m.localPath, m.thumbPath,
    m.needsFullSize, m.fullSizeRequested, m.inlineData.size, m.width, m.height,
)
