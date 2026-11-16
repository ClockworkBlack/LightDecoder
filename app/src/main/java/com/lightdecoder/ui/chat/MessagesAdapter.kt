package com.lightdecoder.ui.chat

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.lightdecoder.R
import com.lightdecoder.archive.MediaRow
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.databinding.ItemDayHeaderBinding
import com.lightdecoder.databinding.ItemMediaPartBinding
import com.lightdecoder.databinding.ItemMessageBinding
import com.lightdecoder.databinding.ItemMessageSystemBinding
import com.lightdecoder.databinding.ItemThreadHeaderBinding
import com.lightdecoder.relay.ReactionDto

/**
 * Draws a thread: day headers, incoming and outgoing bubbles (sender name in
 * groups, reply quote, media, text, time and delivery status, reactions),
 * and system notices.
 */
class MessagesAdapter(
    private val media: MediaLoader,
    private val callbacks: Callbacks,
) : ListAdapter<ThreadItem, RecyclerView.ViewHolder>(DIFF) {

    interface Callbacks {
        fun onMessageLongPress(row: MessageRow)
        fun onFailedTap(row: MessageRow)
        fun onMediaTap(row: MessageRow, part: MediaRow)
        fun onLoadFullSize(row: MessageRow, part: MediaRow)
        fun onQuoteTap(messageId: String)
        fun onReactionTap(row: MessageRow, reaction: ReactionDto)
        fun onReply(row: MessageRow)
    }

    private val ids = StableIds()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = ids.of(getItem(position).key)

    override fun getItemViewType(position: Int): Int = when (val item = getItem(position)) {
        is ThreadItem.Header -> TYPE_HEADER
        is ThreadItem.Day -> TYPE_DAY
        is ThreadItem.Message -> if (item.row.direction == "system") TYPE_SYSTEM else TYPE_MESSAGE
    }

    class HeaderHolder(val binding: ItemThreadHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    class DayHolder(val binding: ItemDayHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    class SystemHolder(val binding: ItemMessageSystemBinding) : RecyclerView.ViewHolder(binding.root)

    /** Colours resolved once per holder from the theme, so light and dark both work. */
    class MessageHolder(val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root) {
        private val view = binding.root
        val incomingBubble = MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurfaceContainerHigh)
        val onIncoming = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface)
        val incomingMeta = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val outgoingBubble = MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimaryContainer)
        val onOutgoing = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnPrimaryContainer)
        val readTint = MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary)
        val errorTint = MaterialColors.getColor(view, androidx.appcompat.R.attr.colorError)
        val highlight = MaterialColors.getColor(view, com.google.android.material.R.attr.colorSecondaryContainer)

        /** The "Reply" accessibility action added at the last bind, removed before the next. */
        var replyActionId = View.NO_ID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(ItemThreadHeaderBinding.inflate(inflater, parent, false))
            TYPE_DAY -> DayHolder(ItemDayHeaderBinding.inflate(inflater, parent, false))
            TYPE_SYSTEM -> SystemHolder(ItemMessageSystemBinding.inflate(inflater, parent, false))
            else -> MessageHolder(ItemMessageBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ThreadItem.Header -> bindHeader(holder as HeaderHolder, item)
            is ThreadItem.Day -> (holder as DayHolder).binding.dayLabel.text = item.label
            is ThreadItem.Message ->
                if (holder is SystemHolder) bindSystem(holder, item) else bindMessage(holder as MessageHolder, item)
        }
    }

    private fun bindHeader(holder: HeaderHolder, item: ThreadItem.Header) {
        val b = holder.binding
        b.headerProgress.visibility = if (item.loading) View.VISIBLE else View.GONE
        b.headerText.setText(if (item.loading) R.string.chat_loading_older else R.string.chat_history_start)
        b.root.visibility = if (item.loading || item.complete) View.VISIBLE else View.GONE
    }

    private fun bindSystem(holder: SystemHolder, item: ThreadItem.Message) {
        val context = holder.binding.root.context
        holder.binding.systemText.text = item.row.text.ifBlank { context.getString(R.string.chat_system_message) }
    }

    private fun bindMessage(holder: MessageHolder, item: ThreadItem.Message) {
        val b = holder.binding
        val row = item.row
        val context = b.root.context
        val outgoing = row.isOutgoing || row.direction == "out"

        // Side and colours.
        (b.column.layoutParams as FrameLayout.LayoutParams).gravity = if (outgoing) Gravity.END else Gravity.START
        b.column.gravity = if (outgoing) Gravity.END else Gravity.START
        val bubbleColor = if (outgoing) holder.outgoingBubble else holder.incomingBubble
        val textColor = if (outgoing) holder.onOutgoing else holder.onIncoming
        val metaColor = if (outgoing) holder.onOutgoing else holder.incomingMeta
        b.bubble.backgroundTintList = ColorStateList.valueOf(bubbleColor)
        b.body.setTextColor(textColor)
        b.body.setLinkTextColor(textColor)
        b.subject.setTextColor(textColor)
        b.time.setTextColor(metaColor)
        b.quoteSender.setTextColor(textColor)
        b.quoteText.setTextColor(metaColor)
        b.root.setBackgroundColor(if (item.highlighted) holder.highlight else Color.TRANSPARENT)

        // Sender (groups only, first of a run).
        b.sender.visibility = if (item.showSender) View.VISIBLE else View.GONE
        b.sender.text = row.senderName.ifBlank { context.getString(R.string.chat_unknown_sender) }

        // Reply quote.
        val quote = item.quote
        if (quote == null) {
            b.quote.visibility = if (row.replyToId.isNotEmpty()) View.VISIBLE else View.GONE
            b.quoteSender.text = context.getString(R.string.chat_reply_unavailable)
            b.quoteText.text = ""
            b.quoteText.visibility = View.GONE
            b.quote.setOnClickListener(null)
            b.quote.isClickable = false
        } else {
            b.quote.visibility = View.VISIBLE
            b.quoteSender.text = quote.sender
            b.quoteText.text = quote.text
            b.quoteText.visibility = if (quote.text.isEmpty()) View.GONE else View.VISIBLE
            b.quote.setOnClickListener { callbacks.onQuoteTap(quote.messageId) }
        }

        b.subject.visibility = if (row.subject.isNotBlank()) View.VISIBLE else View.GONE
        b.subject.text = row.subject

        bindMedia(holder, row)

        b.body.visibility = if (row.text.isNotEmpty()) View.VISIBLE else View.GONE
        b.body.text = row.text

        // Time and delivery status.
        b.time.text = TimeFormat.clock(context, row.timestampMs)
        val tick = MessageStatus.tickFor(row)
        // A send that may have gone out all the same has its own words: "Not sent" would invite a resend.
        val unconfirmed = MessageStatus.outcomeUnknown(row)
        bindTick(holder, tick, metaColor, unconfirmed)

        // Failure notice under the bubble.
        val retryable = MessageStatus.canRetryLocally(row)
        b.failedText.visibility = if (tick == Tick.FAILED) View.VISIBLE else View.GONE
        b.failedText.setText(
            when {
                unconfirmed -> R.string.chat_failed_unconfirmed
                retryable -> R.string.chat_failed_retry
                else -> R.string.chat_failed_phone
            }
        )
        b.failedText.setOnClickListener { callbacks.onFailedTap(row) }
        // What a tap does, for TalkBack ("double-tap to send again or discard").
        val failedTapLabel = when {
            tick != Tick.FAILED -> null
            unconfirmed -> context.getString(R.string.chat_failed_unconfirmed_action)
            retryable -> context.getString(R.string.chat_action_retry)
            else -> null
        }
        ViewCompat.replaceAccessibilityAction(b.failedText, AccessibilityActionCompat.ACTION_CLICK, failedTapLabel, null)

        bindReactions(holder, row)

        // Long press anywhere on the bubble (the text view takes its own touches for links).
        val longPress = View.OnLongClickListener {
            callbacks.onMessageLongPress(row)
            true
        }
        b.bubble.setOnLongClickListener(longPress)
        b.body.setOnLongClickListener(longPress)
        b.bubble.setOnClickListener(if (tick == Tick.FAILED) View.OnClickListener { callbacks.onFailedTap(row) } else null)
        if (tick != Tick.FAILED) b.bubble.isClickable = false
        ViewCompat.replaceAccessibilityAction(b.bubble, AccessibilityActionCompat.ACTION_CLICK, failedTapLabel, null)

        b.bubble.contentDescription = describe(holder, item, tick)
        // TalkBack users cannot swipe a bubble: offer reply as an action.
        ViewCompat.replaceAccessibilityAction(
            b.bubble, AccessibilityActionCompat.ACTION_LONG_CLICK, context.getString(R.string.chat_message_actions), null,
        )
        if (holder.replyActionId != View.NO_ID) ViewCompat.removeAccessibilityAction(b.bubble, holder.replyActionId)
        holder.replyActionId = if (row.isLocal) {
            View.NO_ID
        } else {
            ViewCompat.addAccessibilityAction(b.bubble, context.getString(R.string.chat_action_reply)) { _, _ ->
                callbacks.onReply(row)
                true
            }
        }
    }

    /** [unconfirmed]: a failed send that may have gone out ([MessageStatus.outcomeUnknown]). */
    private fun bindTick(holder: MessageHolder, tick: Tick, metaColor: Int, unconfirmed: Boolean) {
        val icon = holder.binding.statusIcon
        val context = icon.context
        val (drawable, description) = when (tick) {
            Tick.NONE -> {
                icon.visibility = View.GONE
                icon.contentDescription = null
                return
            }
            Tick.PENDING -> R.drawable.ic_msg_pending to R.string.chat_status_sending
            Tick.SENT -> R.drawable.ic_msg_sent to R.string.chat_status_sent
            Tick.DELIVERED -> R.drawable.ic_msg_delivered to R.string.chat_status_delivered
            Tick.READ -> R.drawable.ic_msg_delivered to R.string.chat_status_read
            Tick.FAILED -> R.drawable.ic_msg_failed to
                if (unconfirmed) R.string.chat_failed_unconfirmed else R.string.chat_status_failed
        }
        icon.visibility = View.VISIBLE
        icon.setImageResource(drawable)
        icon.contentDescription = context.getString(description)
        icon.imageTintList = ColorStateList.valueOf(
            when (tick) {
                Tick.READ -> holder.readTint
                Tick.FAILED -> holder.errorTint
                else -> metaColor
            }
        )
    }

    private fun bindMedia(holder: MessageHolder, row: MessageRow) {
        val container = holder.binding.mediaContainer
        // The old part views go: stop their loads first, or every rebind (a status
        // or reaction change) would leave a download running for a detached view.
        cancelMedia(container)
        container.removeAllViews()
        if (row.media.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(container.context)
        val maxSide = (MEDIA_MAX_SIDE_DP * container.resources.displayMetrics.density).toInt()
        for (part in row.media) {
            val pb = ItemMediaPartBinding.inflate(inflater, container, false)
            val visual = isVisual(part)
            pb.mediaFrame.visibility = if (visual) View.VISIBLE else View.GONE
            pb.mediaFile.visibility = if (visual) View.GONE else View.VISIBLE
            if (visual) {
                media.bind(pb.mediaImage, row.id, part, maxSide, pb.mediaStatus)
                pb.mediaPlay.visibility = if (part.mime.startsWith("video/")) View.VISIBLE else View.GONE
                pb.mediaImage.contentDescription = container.context.getString(
                    if (part.mime.startsWith("video/")) R.string.chat_media_video else R.string.chat_media_picture
                )
                pb.mediaFrame.setOnClickListener { callbacks.onMediaTap(row, part) }
                pb.mediaFrame.setOnLongClickListener {
                    callbacks.onMessageLongPress(row)
                    true
                }
            } else {
                pb.mediaFileName.text = part.fileName.ifBlank { part.mime.ifBlank { container.context.getString(R.string.chat_media_file) } }
                pb.mediaFile.setOnClickListener { callbacks.onMediaTap(row, part) }
            }
            val canAskFullSize = part.needsFullSize && !part.fullSizeRequested && !row.isLocal
            pb.mediaFullSize.visibility = if (canAskFullSize) View.VISIBLE else View.GONE
            pb.mediaFullSize.setOnClickListener { callbacks.onLoadFullSize(row, part) }
            container.addView(pb.root)
        }
    }

    /** A row scrolled out of view: its pictures stop loading. */
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is MessageHolder) cancelMedia(holder.binding.mediaContainer)
    }

    private fun cancelMedia(container: ViewGroup) {
        for (i in 0 until container.childCount) {
            container.getChildAt(i).findViewById<ImageView>(R.id.mediaImage)?.let(media::cancel)
        }
    }

    private fun bindReactions(holder: MessageHolder, row: MessageRow) {
        val box = holder.binding.reactions
        box.removeAllViews()
        val shown = row.reactions.filter { it.emoji.isNotEmpty() }
        if (shown.isEmpty()) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        box.alignEnd = row.isOutgoing || row.direction == "out"
        val inflater = LayoutInflater.from(box.context)
        for (reaction in shown) {
            val chip = inflater.inflate(R.layout.item_reaction, box, false) as TextView
            val count = reaction.participantIds.size.coerceAtLeast(1)
            chip.text = if (count > 1) "${reaction.emoji} $count" else reaction.emoji
            // A 48dp touch target around a smaller pill (the inset drawables keep the pill's size).
            // A background with padding replaces the view's own: the inset's top and bottom
            // centre the label in the pill, and the layout's side padding is put back.
            val padStart = chip.paddingStart
            val padEnd = chip.paddingEnd
            chip.setBackgroundResource(if (reaction.fromMe) R.drawable.bg_reaction_mine_target else R.drawable.bg_reaction_target)
            chip.setPaddingRelative(padStart, chip.paddingTop, padEnd, chip.paddingBottom)
            chip.contentDescription = box.context.resources.getQuantityString(
                if (reaction.fromMe) R.plurals.chat_reaction_mine else R.plurals.chat_reaction, count, reaction.emoji, count,
            )
            chip.isEnabled = !row.isLocal
            chip.alpha = if (chip.isEnabled) 1f else DISABLED_ALPHA
            chip.setOnClickListener { callbacks.onReactionTap(row, reaction) }
            box.addView(chip)
        }
    }

    /** What TalkBack reads for a bubble: who, what, when, and how far it got. */
    private fun describe(holder: MessageHolder, item: ThreadItem.Message, tick: Tick): String {
        val context = holder.binding.root.context
        val row = item.row
        val who = if (row.isOutgoing) context.getString(R.string.chat_you)
        else row.senderName.ifBlank { context.getString(R.string.chat_unknown_sender) }
        val parts = ArrayList<String>()
        parts += who
        item.quote?.let { parts += context.getString(R.string.chat_replying_to, it.sender) }
        if (row.media.isNotEmpty()) {
            parts += context.resources.getQuantityString(R.plurals.chat_attachments, row.media.size, row.media.size)
        }
        if (row.text.isNotEmpty()) parts += row.text
        parts += TimeFormat.clock(context, row.timestampMs)
        holder.binding.statusIcon.contentDescription?.takeIf { tick != Tick.NONE }?.let { parts += it.toString() }
        return parts.joinToString(", ")
    }

    private fun isVisual(part: MediaRow): Boolean =
        part.mime.startsWith("image/") || part.mime.startsWith("video/") ||
            (part.mime.isEmpty() && (part.thumbMediaId.isNotEmpty() || part.width > 0 || part.inlineData.isNotEmpty()))

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_DAY = 1
        const val TYPE_MESSAGE = 2
        const val TYPE_SYSTEM = 3

        /** How Material dims a control that cannot be used. */
        const val DISABLED_ALPHA = 0.38f

        /** Longest side of a picture preview in a bubble (decoded at this size, shown up to it). */
        const val MEDIA_MAX_SIDE_DP = 240

        val DIFF = object : DiffUtil.ItemCallback<ThreadItem>() {
            override fun areItemsTheSame(a: ThreadItem, b: ThreadItem) = a.key == b.key

            override fun areContentsTheSame(a: ThreadItem, b: ThreadItem): Boolean =
                if (a is ThreadItem.Message && b is ThreadItem.Message) a.sameContent(b) else a == b
        }
    }
}
