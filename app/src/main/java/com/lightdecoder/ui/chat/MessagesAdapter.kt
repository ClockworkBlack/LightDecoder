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
import android.widget.Toast
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
import com.lightdecoder.imessage.direct.IMessageExtra
import com.lightdecoder.relay.ReactionDto

/**
 * Draws a thread: day headers, incoming and outgoing bubbles (sender name in
 * groups, reply quote, media, text, "Edited", time and delivery status,
 * reactions, an iPhone's "Delivered" / "Read"), and system notices. An
 * iMessage also shows what it carries beyond its text ([IMessageBubbles]): the
 * effect it was sent with, invisible ink, a link preview, an iMessage app's card, and iOS 18's
 * formatting and text effects ([BubbleText], [TextEffectView]).
 */
class MessagesAdapter(
    private val media: MediaLoader,
    private val callbacks: Callbacks,
) : ListAdapter<ThreadItem, RecyclerView.ViewHolder>(DIFF) {

    /**
     * The user's own messages in iMessage's blue with white text, as on an
     * iPhone: set for iMessage chats (Blue Bubbles). Set it before the first
     * rows are submitted.
     */
    var blueOutgoing = false

    /** Which of the user's own messages are iMessages, in a conversation that shows both kinds (Blue Bubbles). */
    var blueFor: (MessageRow) -> Boolean = { false }

    /**
     * The user's messages whose receipts come from Apple (iMessages through the iMessage
     * library): no sent, delivered or read ticks, "Delivered" or "Read …" under the latest instead
     * ([ThreadItem.Message.receipt]), as on an iPhone. Sending and failure marks stay.
     */
    var appleReceipts: (MessageRow) -> Boolean = { false }

    /**
     * iPhone-style chats ([PhoneStyle]): blue, green and grey bubbles with tails, no time or ticks
     * in them, tapbacks on their corner. Changed, every row must be bound again.
     */
    var phoneStyle = false

    /**
     * iPhone-style chats: a tap on a bubble that is in an inline-reply thread opens the thread
     * ([Callbacks.onOpenThread]). Off in the thread view itself.
     */
    var threadTaps = true

    /** A picture is behind the messages: iPhone-style times and notices keep their pill, to stay readable on it. */
    var onPicture = false

    interface Callbacks {
        fun onMessageLongPress(row: MessageRow)
        fun onFailedTap(row: MessageRow)
        fun onMediaTap(row: MessageRow, part: MediaRow)
        fun onLoadFullSize(row: MessageRow, part: MediaRow)

        /** Play or pause a voice message (or other audio) in its bubble. */
        fun onAudioTap(row: MessageRow, part: MediaRow) = onMediaTap(row, part)

        /** The user moved a playing voice message's bar to [positionMs]. */
        fun onAudioSeek(row: MessageRow, part: MediaRow, positionMs: Int) = Unit
        fun onQuoteTap(messageId: String)
        fun onReactionTap(row: MessageRow, reaction: ReactionDto)

        /** A reaction chip held down: who reacted with what, and when. */
        fun onReactionDetails(row: MessageRow, reaction: ReactionDto) = Unit
        fun onReply(row: MessageRow)

        /** An iMessage's link preview tapped: open [url]. */
        fun onOpenLink(url: String) = Unit

        /** An iMessage app's card tapped (a game, a poll). */
        fun onAppCardTap(row: MessageRow) = Unit

        /** "Replay" beside "Sent with Slam" tapped: play the message's effect again ([EffectPlayer]). */
        fun onReplayEffect(row: MessageRow) = Unit

        /** A message whose words have text effects (Big, Shake, ...) is on screen in [text]: they may play once by themselves. */
        fun onTextEffectsShown(row: MessageRow, text: TextEffectView) = Unit

        /** Its text tapped: its text effects play again. */
        fun onTextEffectsTap(row: MessageRow, text: TextEffectView) = Unit

        /**
         * iPhone-style chats: a message of a thread tapped (a reply, or one others answered), its
         * "Reply" or "N Replies", or a reply's faded copy: the thread rooted at [rootId] opens.
         */
        fun onOpenThread(rootId: String) = Unit
    }

    /** Messages whose invisible ink was tapped away, by id: their text shows. */
    private val revealed = HashSet<String>()

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
    class DayHolder(val binding: ItemDayHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        val pill: android.graphics.drawable.Drawable? = binding.dayLabel.background
    }

    class SystemHolder(val binding: ItemMessageSystemBinding) : RecyclerView.ViewHolder(binding.root) {
        val padding = intArrayOf(
            binding.systemText.paddingStart, binding.systemText.paddingTop, binding.systemText.paddingEnd, binding.systemText.paddingBottom,
        )
    }

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
        val blueBubble = androidx.core.content.ContextCompat.getColor(view.context, R.color.imessage_blue)
        val onBlue = androidx.core.content.ContextCompat.getColor(view.context, R.color.on_imessage_blue)

        /** The "Reply" accessibility action added at the last bind, removed before the next. */
        var replyActionId = View.NO_ID

        /** The "Open link" accessibility actions added at the last bind ([BubbleLinks]). */
        var linkActionIds: List<Int> = emptyList()

        /** The bubble as the layout made it, put back after a picture-only message used the holder. */
        val bubbleBackground: android.graphics.drawable.Drawable? = binding.bubble.background
        val bubblePadding = intArrayOf(
            binding.bubble.paddingStart, binding.bubble.paddingTop, binding.bubble.paddingEnd, binding.bubble.paddingBottom,
        )
        val bubbleMinWidth = binding.bubble.minimumWidth
        val metaMarginTop = (binding.meta.layoutParams as android.widget.LinearLayout.LayoutParams).topMargin

        // iPhone-style chats ([PhoneStyle]): their colours, and what the layout gave, to put back.
        private val density = view.resources.displayMetrics.density
        val phoneBlue = androidx.core.content.ContextCompat.getColor(view.context, R.color.phone_blue)
        val phoneGreen = androidx.core.content.ContextCompat.getColor(view.context, R.color.phone_green)
        val phoneIncoming = androidx.core.content.ContextCompat.getColor(view.context, R.color.phone_incoming)
        val phoneOnIncoming = androidx.core.content.ContextCompat.getColor(view.context, R.color.phone_on_incoming)
        val phoneMeta = androidx.core.content.ContextCompat.getColor(view.context, R.color.phone_meta)
        val phoneScreen = androidx.core.content.ContextCompat.getColor(view.context, R.color.phone_screen)

        /** A reply's faded copy of what it answers: an outline, filled only over a picture ([onPicture]). */
        val quoteOutline by lazy {
            android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = PHONE_QUOTE_RADIUS_DP * density
                setStroke(maxOf(1, density.toInt()), androidx.core.content.ContextCompat.getColor(view.context, R.color.phone_thread_line))
            }
        }
        val phoneBubble by lazy { PhoneBubbleDrawable(density) }
        val rootPadding = intArrayOf(view.paddingStart, view.paddingTop, view.paddingEnd, view.paddingBottom)
        val senderColor = binding.sender.currentTextColor
        val senderMarginStart = (binding.sender.layoutParams as android.widget.LinearLayout.LayoutParams).marginStart
        val receiptColor = binding.receipt.currentTextColor
        val bodySpacing = binding.body.letterSpacing
        val effectColor = binding.effect.currentTextColor
        val editedMarginEnd = (binding.edited.layoutParams as android.widget.LinearLayout.LayoutParams).marginEnd
        private val reactionsParams = binding.reactions.layoutParams as android.widget.LinearLayout.LayoutParams
        val reactionsMargins = intArrayOf(
            reactionsParams.marginStart, reactionsParams.topMargin, reactionsParams.marginEnd, reactionsParams.bottomMargin,
        )
        fun dp(value: Float): Int = (value * density).toInt()
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
            is ThreadItem.Day -> bindDay(holder as DayHolder, item)
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

    /** A day's name; in iPhone-style chats the time between messages ("**Today** 9:41 AM"), plain on the screen. */
    private fun bindDay(holder: DayHolder, item: ThreadItem.Day) {
        val label = holder.binding.dayLabel
        val phone = phoneStyle && item.time.isNotEmpty()
        label.text = if (phone) {
            android.text.SpannableString("${item.label} ${item.time}").apply {
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, item.label.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else {
            item.label
        }
        val pill = if (phoneStyle && !onPicture) null else holder.pill
        if (label.background !== pill) {
            val padding = intArrayOf(label.paddingStart, label.paddingTop, label.paddingEnd, label.paddingBottom)
            label.background = pill
            label.setPaddingRelative(padding[0], padding[1], padding[2], padding[3])
        }
    }

    /** A notice; in iPhone-style chats small, grey and upright in the middle (on a picture, in a pill). */
    private fun bindSystem(holder: SystemHolder, item: ThreadItem.Message) {
        val context = holder.binding.root.context
        val text = holder.binding.systemText
        text.text = item.row.text.ifBlank { context.getString(R.string.chat_system_message) }
        text.setTypeface(android.graphics.Typeface.DEFAULT, if (phoneStyle) android.graphics.Typeface.NORMAL else android.graphics.Typeface.ITALIC)
        val pill = phoneStyle && onPicture
        if (pill != (text.background != null)) {
            if (pill) {
                val d = context.resources.displayMetrics.density
                text.setBackgroundResource(R.drawable.bg_day_header)
                text.setPaddingRelative((12 * d).toInt(), (4 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
            } else {
                text.background = null
                val p = holder.padding
                text.setPaddingRelative(p[0], p[1], p[2], p[3])
            }
        }
    }

    private fun bindMessage(holder: MessageHolder, item: ThreadItem.Message) {
        val b = holder.binding
        val row = item.row
        val context = b.root.context
        val outgoing = row.isOutgoing || row.direction == "out"

        // Side and colours.
        (b.column.layoutParams as FrameLayout.LayoutParams).gravity = if (outgoing) Gravity.END else Gravity.START
        b.column.gravity = if (outgoing) Gravity.END else Gravity.START
        val blue = outgoing && (blueOutgoing || blueFor(row))
        val phone = phoneStyle
        val bubbleColor = when {
            // As an iPhone tells them apart: the user's iMessages blue, their texts green.
            phone && blue -> holder.phoneBlue
            phone && outgoing -> holder.phoneGreen
            phone -> holder.phoneIncoming
            blue -> holder.blueBubble
            outgoing -> holder.outgoingBubble
            else -> holder.incomingBubble
        }
        val onOwn = if (blue || phone) holder.onBlue else holder.onOutgoing
        val textColor = if (outgoing) onOwn else if (phone) holder.phoneOnIncoming else holder.onIncoming
        val extra = if (row.extra.isEmpty()) NO_EXTRA else IMessageExtra.parse(row.extra)
        val app = extra.app
        // A picture or sticker on its own has no bubble: it sits on the conversation itself
        // (as on an iPhone), with its time under it in the conversation's own colours.
        val bare = isBare(item) && app == null
        val metaColor = if (outgoing && !bare) onOwn else if (phone) holder.phoneMeta else holder.incomingMeta
        bindBubbleShape(holder, bare, outgoing, item.place?.last == true)
        bindPhoneRow(holder, item, outgoing)
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

        // Reply quote; in iPhone-style chats a faded copy above the bubble instead ([bindThread]).
        val quote = item.quote
        if (phone) {
            b.quote.visibility = View.GONE
            b.quote.setOnClickListener(null)
            b.quote.isClickable = false
        } else if (quote == null) {
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

        bindThread(holder, item)

        b.subject.visibility = if (row.subject.isNotBlank()) View.VISIBLE else View.GONE
        b.subject.text = row.subject

        // An app's card opens the app from its picture too.
        bindMedia(holder, row, textColor, opensApp = app != null)

        // An app's card draws its own lines instead of the text that stands for them.
        val inked = IMessageBubbles.hidden(extra, row.text, row.id in revealed)
        b.body.visibility = if (row.text.isNotEmpty() && app == null) View.VISIBLE else View.GONE
        // Bold, italic, ... and text effects; none under invisible ink (its shape alone shows).
        val runs = if (inked || app != null) emptyList() else extra.textRuns(row.text)
        b.body.text = if (inked) IMessageBubbles.inked(row.text) else BubbleText.styled(row.text, runs)
        val textEffects = runs.mapNotNull { it.effect }.distinct()
        bindAppCard(holder, app, textColor, metaColor)
        bindLinkCard(holder, extra.link.takeIf { app == null }, textColor, metaColor)
        val effect = IMessageBubbles.effectCaption(context, extra)
        b.effect.visibility = if (effect == null) View.GONE else View.VISIBLE
        b.effect.text = effect.orEmpty()
        b.effectRow.visibility = b.effect.visibility
        // Replay: an effect this app can play again (all it names).
        val replayable = effect != null && EffectKind.of(extra.effect) != null
        b.effectReplay.visibility = if (replayable) View.VISIBLE else View.GONE
        if (replayable) {
            b.effectReplay.contentDescription =
                context.getString(R.string.imessage_effect_replay_action, context.getString(IMessageExtra.effectName(extra.effect)!!))
            b.effectReplay.setOnClickListener { callbacks.onReplayEffect(row) }
        } else {
            b.effectReplay.setOnClickListener(null)
        }

        // Time and delivery status.
        b.time.text = TimeFormat.clock(context, row.timestampMs)
        // Changed since it was sent: "Edited" by the time, as on an iPhone; a tap says when.
        val edited = row.editedAtMs > 0
        b.edited.visibility = if (edited) View.VISIBLE else View.GONE
        b.edited.setTextColor(metaColor)
        b.edited.setOnClickListener(
            if (edited) {
                View.OnClickListener {
                    Toast.makeText(context, context.getString(R.string.imessage_edited_at, TimeFormat.clock(context, row.editedAtMs)), Toast.LENGTH_SHORT).show()
                }
            } else {
                null
            }
        )
        if (!edited) b.edited.isClickable = false
        placeEdited(holder)
        val tick = MessageStatus.tickFor(row)
        // A send that may have gone out all the same has its own words: "Not sent" would invite a resend.
        val unconfirmed = MessageStatus.outcomeUnknown(row)
        val shownTick = if (tick in RECEIPT_TICKS && appleReceipts(row)) Tick.NONE else tick
        bindTick(holder, shownTick, metaColor, unconfirmed)
        // Send Later, before its time: when it goes, in place of a receipt (iOS 18).
        val scheduledAt = com.lightdecoder.imessage.direct.SendLater.scheduledAt(row)
            .takeIf { it > 0 && com.lightdecoder.imessage.direct.SendLater.pending(row, System.currentTimeMillis()) }
        val receipt = scheduledAt?.let { context.getString(R.string.imessage_send_later_label, TimeFormat.sendLaterTime(context, it)) }
            ?: item.receipt?.let { receiptText(context, it) }
        b.receipt.visibility = if (receipt == null) View.GONE else View.VISIBLE
        b.receipt.text = receipt.orEmpty()
        // Pictures sent together: one time line, under the last of them (a failed one keeps its own).
        b.meta.visibility = if (item.stackedWithNext && tick != Tick.FAILED) View.GONE else View.VISIBLE
        if (phone) {
            // As on an iPhone: no time and no ticks in a bubble (the time is between the messages, and in
            // what TalkBack reads); "Edited" is under it. One still being sent is paler; a failed one says so under it.
            b.time.visibility = View.GONE
            b.statusIcon.visibility = View.GONE
            b.meta.visibility = View.GONE
            // Under the bubble: small and grey ("Edited" blue); on a picture white, with a shadow to stand out.
            underBubble(b.receipt, holder.phoneMeta)
            underBubble(b.edited, holder.phoneBlue)
            underBubble(b.effect, holder.phoneMeta)
            underBubble(b.sender, holder.phoneMeta)
        } else {
            b.time.visibility = View.VISIBLE
            underBubble(b.receipt, holder.receiptColor)
            underBubble(b.effect, holder.effectColor)
            underBubble(b.sender, holder.senderColor)
            b.edited.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
        b.column.alpha = if (phone && tick == Tick.PENDING) PENDING_ALPHA else 1f

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
        placeReactions(holder, outgoing)

        // Long press anywhere on the bubble (the text view takes its own touches for links).
        val longPress = View.OnLongClickListener {
            callbacks.onMessageLongPress(row)
            true
        }
        b.bubble.setOnLongClickListener(longPress)
        b.body.setOnLongClickListener(longPress)
        b.linkCard.setOnLongClickListener(longPress)
        // A tap, in this order: a failed one sends again (or asks), invisible ink shows its text (the
        // next tap is then as on any other), an app's card opens, text effects play again; else, on a
        // message of an inline-reply thread (iPhone-style chats), the thread opens. A link in the
        // text, a link's card, a picture, a video or a voice message take their own taps as ever: a
        // bubble with pictures or files opens its thread from "Reply" / "N Replies" under it only.
        val threadRoot = item.thread?.root?.takeIf { phone && threadTaps && it.isNotEmpty() && row.media.isEmpty() }
        val openThread = threadRoot?.let { root -> View.OnClickListener { callbacks.onOpenThread(root) } }
        val reveal = View.OnClickListener {
            revealed += row.id
            val at = holder.bindingAdapterPosition
            if (at != RecyclerView.NO_POSITION) notifyItemChanged(at)
        }
        val playText = View.OnClickListener { callbacks.onTextEffectsTap(row, b.body) }
        val tap = when {
            tick == Tick.FAILED -> View.OnClickListener { callbacks.onFailedTap(row) }
            inked -> reveal
            app != null -> View.OnClickListener { callbacks.onAppCardTap(row) }
            textEffects.isNotEmpty() -> playText
            else -> openThread
        }
        b.bubble.setOnClickListener(tap)
        if (tap == null) b.bubble.isClickable = false
        // The text takes its own touches (for its links): hidden, a tap on it shows it. Otherwise it
        // is clickable only for its links, so a tap beside them reaches the bubble.
        if (inked && tick != Tick.FAILED) {
            b.body.setOnClickListener(reveal)
        } else if (textEffects.isNotEmpty() && tick != Tick.FAILED) {
            b.body.setOnClickListener(playText)
        } else if (tap === openThread && openThread != null) {
            // The text takes the touch where it has links (or had, before the holder was reused): the
            // thread opens from it too, unless the tap was on a link, which opened.
            b.body.setOnClickListener { if (!b.body.tappedLink) openThread.onClick(it) }
        } else if (b.body.hasOnClickListeners()) {
            b.body.setOnClickListener(null)
            b.body.isClickable = b.body.movementMethod != null
        }
        val tapLabel = failedTapLabel ?: when {
            inked -> context.getString(R.string.imessage_invisible_ink_hidden)
            app != null -> app.shownName.ifBlank { context.getString(R.string.imessage_app_unnamed) }
            textEffects.isNotEmpty() -> context.getString(R.string.imessage_text_effect_replay_action)
            openThread != null -> context.getString(R.string.imessage_thread_open)
            else -> null
        }
        ViewCompat.replaceAccessibilityAction(b.bubble, AccessibilityActionCompat.ACTION_CLICK, tapLabel, null)

        b.bubble.contentDescription = describe(holder, item, shownTick, receipt, inked, effect, textEffects)
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
        // The text view is hidden from TalkBack (the bubble reads it): its links are bubble actions.
        holder.linkActionIds = BubbleLinks.bind(b.bubble, b.body, holder.linkActionIds)
        if (textEffects.isNotEmpty() && b.body.visibility == View.VISIBLE) callbacks.onTextEffectsShown(row, b.body)
    }

    /**
     * Inline replies in iPhone-style chats ([ThreadItem.Message.thread]): "N Replies" in blue under a
     * message others answered, and a small grey "Reply" with an arrow under a reply; or, with reply
     * threads drawn in the chat, a reply's faded copy of the message it answers instead, above it on
     * that message's side (a grey outline, smaller text, at most two lines). A tap on any of them
     * opens the thread ([Callbacks.onOpenThread]). Elsewhere all are hidden.
     */
    private fun bindThread(holder: MessageHolder, item: ThreadItem.Message) {
        val b = holder.binding
        val context = b.root.context
        val mark = if (phoneStyle) item.thread else null
        val copy = b.threadQuote
        if (mark != null && mark.fadedQuote) {
            val quote = item.quote
            val outgoing = item.row.isOutgoing || item.row.direction == "out"
            // On the side of whoever sent what it answers (unknown: the reply's own side).
            val mine = quote?.outgoing ?: outgoing
            val text = quote?.text?.ifEmpty { null } ?: context.getString(R.string.chat_reply_unavailable)
            copy.visibility = View.VISIBLE
            copy.text = text
            copy.setTextColor(if (onPicture) holder.phoneOnIncoming else holder.phoneMeta)
            copy.maxWidth = (context.resources.displayMetrics.widthPixels * PHONE_QUOTE_MAX_WIDTH_FRACTION).toInt()
            val outline = holder.quoteOutline
            outline.setColor(if (onPicture) (holder.phoneScreen and 0x00FFFFFF) or (PHONE_QUOTE_FILL_ALPHA shl 24) else Color.TRANSPARENT)
            copy.background = outline
            val params = copy.layoutParams as FrameLayout.LayoutParams
            params.gravity = Gravity.TOP or if (mine) Gravity.END else Gravity.START
            val gutter = holder.phoneBubble.gutter
            params.marginStart = if (mine) 0 else gutter + if (item.besideAvatar) holder.dp(PHONE_AVATAR_ROOM_DP) else 0
            params.marginEnd = if (mine) gutter else 0
            params.bottomMargin = holder.dp(PHONE_QUOTE_GAP_DP)
            copy.layoutParams = params
            val sender = quote?.sender ?: context.getString(R.string.chat_unknown_sender)
            copy.contentDescription = context.getString(R.string.imessage_thread_quote_description, sender, text)
            copy.setOnClickListener { callbacks.onOpenThread(mark.root) }
            ViewCompat.replaceAccessibilityAction(copy, AccessibilityActionCompat.ACTION_CLICK, context.getString(R.string.imessage_thread_open), null)
        } else {
            copy.visibility = View.GONE
            copy.setOnClickListener(null)
        }
        val marker = b.replyMarker
        if (mark != null && mark.marker) {
            val quote = item.quote
            marker.visibility = View.VISIBLE
            underBubble(marker, holder.phoneMeta)
            androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(marker, ColorStateList.valueOf(marker.currentTextColor))
            val side = holder.dp(PHONE_FOOTER_SIDE_DP)
            marker.setPaddingRelative(side, 0, side, 0)
            marker.contentDescription = context.getString(
                R.string.imessage_thread_reply_marker_description,
                quote?.sender ?: context.getString(R.string.chat_unknown_sender),
                quote?.text?.ifEmpty { null } ?: context.getString(R.string.chat_reply_unavailable),
            )
            marker.setOnClickListener { callbacks.onOpenThread(mark.root) }
        } else {
            marker.visibility = View.GONE
            marker.setOnClickListener(null)
            marker.isClickable = false
        }
        val count = mark?.replyCount ?: 0
        val replies = b.replyCount
        if (count > 0) {
            replies.visibility = View.VISIBLE
            replies.text = context.resources.getQuantityString(R.plurals.imessage_thread_replies, count, count)
            underBubble(replies, holder.phoneBlue)
            val side = holder.dp(PHONE_FOOTER_SIDE_DP)
            replies.setPaddingRelative(side, 0, side, 0)
            val root = item.row.id
            replies.setOnClickListener { callbacks.onOpenThread(root) }
            ViewCompat.replaceAccessibilityAction(
                replies, AccessibilityActionCompat.ACTION_CLICK,
                context.resources.getQuantityString(R.plurals.imessage_thread_open_replies, count, count), null,
            )
        } else {
            replies.visibility = View.GONE
            replies.setOnClickListener(null)
        }
    }

    /**
     * An iMessage app's card ([app], none: hidden): the lines its app gave it
     * ([IMessageBubbles.appLines]), the app's name small under them.
     */
    private fun bindAppCard(holder: MessageHolder, app: IMessageExtra.App?, textColor: Int, metaColor: Int) {
        val b = holder.binding
        if (app == null) {
            b.appCard.visibility = View.GONE
            return
        }
        b.appCard.visibility = View.VISIBLE
        val lines = IMessageBubbles.appLines(app)
        fun line(view: TextView, text: String, color: Int) {
            view.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            view.text = text
            view.setTextColor(color)
        }
        line(b.appImageTitle, lines.imageTitle, textColor)
        line(b.appImageSubtitle, lines.imageSubtitle, metaColor)
        line(b.appCaption, lines.caption, textColor)
        line(b.appSubcaption, lines.subcaption, metaColor)
        line(b.appSecondary, lines.secondary, metaColor)
        line(b.appName, lines.appName, metaColor)
    }

    /** A link's preview card under the text ([link], none: hidden); a tap opens the page (a web page only). */
    private fun bindLinkCard(holder: MessageHolder, link: IMessageExtra.Link?, textColor: Int, metaColor: Int) {
        val b = holder.binding
        if (link == null || link.url.isBlank()) {
            b.linkCard.visibility = View.GONE
            b.linkCard.setOnClickListener(null)
            return
        }
        val lines = IMessageBubbles.linkLines(link)
        b.linkCard.visibility = View.VISIBLE
        b.linkTitle.text = lines.title
        b.linkTitle.setTextColor(textColor)
        b.linkSummary.text = lines.summary
        b.linkSummary.visibility = if (lines.summary.isBlank()) View.GONE else View.VISIBLE
        b.linkSummary.setTextColor(textColor)
        b.linkSite.text = lines.site
        b.linkSite.visibility = if (lines.site.isBlank()) View.GONE else View.VISIBLE
        b.linkSite.setTextColor(metaColor)
        b.linkCard.contentDescription = b.root.context.getString(R.string.imessage_link_preview, lines.title)
        // Only a web page opens: the sender chose the address.
        if (link.opensInBrowser) {
            b.linkCard.setOnClickListener { callbacks.onOpenLink(link.url) }
        } else {
            b.linkCard.setOnClickListener(null)
            b.linkCard.isClickable = false
        }
    }

    /**
     * A message that is only pictures (or stickers, or video stills): no
     * text, subject or reply quote. It is drawn without a bubble.
     */
    private fun isBare(item: ThreadItem.Message): Boolean {
        val row = item.row
        // iPhone-style chats draw a reply's quote outside its bubble: a picture that replies has none either.
        return row.media.isNotEmpty() && row.media.all(::isVisual) && row.text.isEmpty() &&
            row.subject.isBlank() && (phoneStyle || (item.quote == null && row.replyToId.isEmpty()))
    }

    /** The bubble's background, padding and the time's place: a bubble, or none for a [bare] picture. */
    private fun bindBubbleShape(holder: MessageHolder, bare: Boolean, outgoing: Boolean, tail: Boolean) {
        val b = holder.binding
        val meta = b.meta.layoutParams as android.widget.LinearLayout.LayoutParams
        if (phoneStyle) {
            // An iPhone's bubble: the tail's room on its own side (start for others', end for the user's).
            val shape = holder.phoneBubble
            val gutter = shape.gutter
            val side = if (bare) 0 else holder.dp(PHONE_PADDING_SIDE_DP)
            val vertical = if (bare) 0 else holder.dp(PHONE_PADDING_VERTICAL_DP)
            shape.tail = tail
            shape.tailRight = outgoing != (b.root.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            b.bubble.background = if (bare) null else shape
            b.bubble.setPaddingRelative(side + if (outgoing) 0 else gutter, vertical, side + if (outgoing) gutter else 0, vertical)
            b.bubble.minimumWidth = if (bare) 0 else holder.dp(PHONE_MIN_WIDTH_DP)
            meta.gravity = if (bare && !outgoing) Gravity.START else Gravity.END
            meta.topMargin = 0
            meta.marginStart = if (bare) META_INSET else 0
            meta.marginEnd = if (bare) META_INSET else 0
        } else if (bare) {
            b.bubble.background = null
            b.bubble.setPaddingRelative(0, 0, 0, 0)
            b.bubble.minimumWidth = 0
            meta.gravity = if (outgoing) Gravity.END else Gravity.START
            meta.topMargin = 0
            meta.marginStart = META_INSET
            meta.marginEnd = META_INSET
        } else {
            b.bubble.background = holder.bubbleBackground
            val p = holder.bubblePadding
            b.bubble.setPaddingRelative(p[0], p[1], p[2], p[3])
            b.bubble.minimumWidth = holder.bubbleMinWidth
            meta.gravity = Gravity.END
            meta.topMargin = holder.metaMarginTop
            meta.marginStart = 0
            meta.marginEnd = 0
        }
        b.meta.layoutParams = meta
    }

    /**
     * What iPhone-style chats change around a bubble: runs sit close with a gap between them, a
     * bubble is at most three quarters of the screen wide, a group chat's names are small and
     * grey, and the sender's picture is beside the last bubble of their run. Otherwise as the layout has it.
     */
    private fun bindPhoneRow(holder: MessageHolder, item: ThreadItem.Message, outgoing: Boolean) {
        val b = holder.binding
        val phone = phoneStyle
        val place = item.place
        val p = holder.rootPadding
        if (phone) {
            b.root.setPaddingRelative(
                holder.dp(PHONE_ROW_SIDE_DP), holder.dp(if (place?.first != false) PHONE_RUN_GAP_DP else PHONE_IN_RUN_GAP_DP),
                holder.dp(PHONE_ROW_SIDE_DP), holder.dp(PHONE_IN_RUN_GAP_DP),
            )
        } else {
            b.root.setPaddingRelative(p[0], p[1], p[2], p[3])
        }
        b.column.maxWidthFraction = if (phone) PHONE_MAX_WIDTH_FRACTION else BubbleLayout.DEFAULT_MAX_WIDTH_FRACTION
        val beside = phone && item.besideAvatar
        val column = b.column.layoutParams as FrameLayout.LayoutParams
        column.marginStart = if (beside) holder.dp(PHONE_AVATAR_ROOM_DP) else 0
        b.column.layoutParams = column
        val avatar = beside && place?.last == true
        b.avatar.visibility = if (avatar) View.VISIBLE else View.GONE
        if (avatar) {
            val row = item.row
            Avatars.bind(b.avatar, row.senderName.ifBlank { "?" }, row.senderId.ifBlank { row.senderName }, "")
        }
        val sender = b.sender.layoutParams as android.widget.LinearLayout.LayoutParams
        sender.marginStart = holder.senderMarginStart + if (phone) holder.phoneBubble.gutter + holder.dp(2f) else 0
        b.sender.layoutParams = sender
        b.body.letterSpacing = if (phone) 0f else holder.bodySpacing
    }

    /** A line above or under a bubble in [color]; in iPhone-style chats over a picture, white with a shadow. */
    private fun underBubble(view: TextView, color: Int) {
        val lit = phoneStyle && onPicture
        view.setTextColor(if (lit) Color.WHITE else color)
        if (lit) view.setShadowLayer(4f, 0f, 1f, SHADOW) else view.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
    }

    /** "Edited": by the time in the bubble; in iPhone-style chats under the bubble, as on an iPhone. */
    private fun placeEdited(holder: MessageHolder) {
        val b = holder.binding
        val edited = b.edited
        val phone = phoneStyle
        if (phone == (edited.parent === b.column)) return
        (edited.parent as ViewGroup).removeView(edited)
        val params = edited.layoutParams as android.widget.LinearLayout.LayoutParams
        if (phone) {
            val side = holder.dp(PHONE_FOOTER_SIDE_DP)
            params.marginStart = side
            params.marginEnd = side
            params.topMargin = holder.dp(2f)
            b.column.addView(edited, b.column.indexOfChild(b.bubble) + 1, params)
        } else {
            params.marginStart = 0
            params.marginEnd = holder.editedMarginEnd
            params.topMargin = 0
            b.meta.addView(edited, 0, params)
        }
    }

    /**
     * Where the reactions go: under the bubble; in iPhone-style chats on its top corner, as tapbacks
     * are on an iPhone (the far corner from the tail: left on the user's own, right on others').
     * The row is then above the bubble in the column and drawn over it, so nothing is cut off: the
     * list row grows by what sticks out.
     */
    private fun placeReactions(holder: MessageHolder, outgoing: Boolean) {
        val b = holder.binding
        val phone = phoneStyle
        val column = b.column
        val box = b.reactions
        val at = column.indexOfChild(box)
        val bubbleAt = column.indexOfChild(b.bubble)
        if (phone && at > bubbleAt) {
            column.removeView(box)
            column.addView(box, column.indexOfChild(b.bubble))
        } else if (!phone && at < bubbleAt) {
            column.removeView(box)
            column.addView(box, column.indexOfChild(b.bubble) + 1)
        }
        val params = box.layoutParams as android.widget.LinearLayout.LayoutParams
        val m = holder.reactionsMargins
        if (phone) {
            params.marginStart = 0
            params.marginEnd = 0
            // A chip is a 48dp touch target around a 28dp pill: the pill's lower part lies on the bubble.
            params.topMargin = -holder.dp(PHONE_TAPBACK_LIFT_DP)
            params.bottomMargin = -holder.dp(PHONE_TAPBACK_OVERLAP_DP)
            box.alignEnd = !outgoing
        } else {
            params.marginStart = m[0]
            params.topMargin = m[1]
            params.marginEnd = m[2]
            params.bottomMargin = m[3]
        }
        box.layoutParams = params
        // Above the bubble in the column, it is still drawn over it.
        box.translationZ = if (phone) holder.dp(2f).toFloat() else 0f
        val rtl = b.root.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        column.cornerChild = if (phone) box else null
        column.cornerAnchor = if (phone) b.bubble else null
        column.cornerLeft = outgoing != rtl
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

    /** What is playing in the conversation's voice player; the bubbles follow it ([updateVoiceViews]). */
    var voiceState: VoicePlayer.State = VoicePlayer.State()

    /** A voice message's length once known (it has been played), else 0. */
    var voiceDuration: (String) -> Int = { 0 }

    /** [opensApp]: the pictures are an iMessage app's card, and a tap on one opens it ([Callbacks.onAppCardTap]). */
    private fun bindMedia(holder: MessageHolder, row: MessageRow, contentColor: Int, opensApp: Boolean = false) {
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
        val metrics = container.resources.displayMetrics
        // As large as a bubble can show it: its width on screen, and tall pictures up to the cap.
        val maxSide = maxOf((MEDIA_MAX_SIDE_DP * metrics.density).toInt(), (metrics.widthPixels * 0.8f).toInt())
        for (part in row.media) {
            val pb = ItemMediaPartBinding.inflate(inflater, container, false)
            val visual = isVisual(part)
            val audio = !visual && part.mime.startsWith("audio/")
            pb.mediaFrame.visibility = if (visual) View.VISIBLE else View.GONE
            pb.mediaFile.visibility = if (visual || audio) View.GONE else View.VISIBLE
            pb.mediaAudio.visibility = if (audio) View.VISIBLE else View.GONE
            if (audio) {
                bindAudio(pb, row, part, contentColor)
            } else if (visual) {
                media.bind(pb.mediaImage, row.id, part, maxSide, pb.mediaStatus)
                pb.mediaPlay.visibility = if (part.mime.startsWith("video/")) View.VISIBLE else View.GONE
                pb.mediaImage.contentDescription = container.context.getString(
                    if (part.mime.startsWith("video/")) R.string.chat_media_video else R.string.chat_media_picture
                )
                pb.mediaFrame.setOnClickListener { if (opensApp) callbacks.onAppCardTap(row) else callbacks.onMediaTap(row, part) }
                pb.mediaFrame.setOnLongClickListener {
                    callbacks.onMessageLongPress(row)
                    true
                }
            } else {
                pb.mediaFileName.text = part.fileName.ifBlank { part.mime.ifBlank { container.context.getString(R.string.chat_media_file) } }
                pb.mediaFile.setOnClickListener { callbacks.onMediaTap(row, part) }
            }
            val canAskFullSize = part.needsFullSize && !part.fullSizeRequested && !row.isLocal
            // Only a thumbnail on the phone so far: the original is asked for without a tap.
            pb.mediaFullSize.visibility = View.GONE
            if (canAskFullSize && visual) callbacks.onLoadFullSize(row, part)
            container.addView(pb.root)
        }
    }

    /** A voice message's row: play/pause, its bar (which can be moved while it plays) and its time. */
    private fun bindAudio(pb: ItemMediaPartBinding, row: MessageRow, part: MediaRow, contentColor: Int) {
        val key = VoicePlayer.keyOf(row.id, part.partId)
        pb.mediaAudio.tag = key
        pb.audioPlay.imageTintList = ColorStateList.valueOf(contentColor)
        pb.audioTime.setTextColor(contentColor)
        pb.audioProgress.progressTintList = ColorStateList.valueOf(contentColor)
        pb.audioProgress.thumbTintList = ColorStateList.valueOf(contentColor)
        pb.audioPlay.setOnClickListener { callbacks.onAudioTap(row, part) }
        pb.mediaAudio.setOnLongClickListener {
            callbacks.onMessageLongPress(row)
            true
        }
        pb.audioProgress.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val state = voiceState
                if (state.key != key || state.durationMs <= 0) return
                callbacks.onAudioSeek(row, part, (state.durationMs.toLong() * progress / bar.max).toInt())
            }
            override fun onStartTrackingTouch(bar: android.widget.SeekBar) = Unit
            override fun onStopTrackingTouch(bar: android.widget.SeekBar) = Unit
        })
        applyVoiceState(pb.mediaAudio)
    }

    /** Shows [voiceState] on one voice message's row (found by its tag). */
    private fun applyVoiceState(audioRow: View) {
        val key = audioRow.tag as? String ?: return
        val play = audioRow.findViewById<ImageView>(R.id.audioPlay)
        val bar = audioRow.findViewById<android.widget.SeekBar>(R.id.audioProgress)
        val time = audioRow.findViewById<android.widget.TextView>(R.id.audioTime)
        val state = voiceState
        val mine = state.key == key
        val playing = mine && state.playing
        play.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        play.contentDescription = audioRow.context.getString(if (playing) R.string.chat_voice_pause else R.string.chat_voice_play)
        val duration = if (mine) state.durationMs else voiceDuration(key)
        // Only the one loaded can be moved; the others start from the beginning.
        bar.isEnabled = mine && duration > 0
        bar.progress = if (mine && duration > 0) (state.positionMs.toLong() * bar.max / duration).toInt() else 0
        time.text = when {
            mine && state.positionMs > 0 -> VoicePlayer.clock(state.positionMs)
            duration > 0 -> VoicePlayer.clock(duration)
            else -> ""
        }
    }

    /** The voice player changed: every voice message row on screen shows it, without rebinding. */
    fun updateVoiceViews(list: RecyclerView, state: VoicePlayer.State) {
        voiceState = state
        for (i in 0 until list.childCount) {
            val holder = list.getChildViewHolder(list.getChildAt(i)) as? MessageHolder ?: continue
            val container = holder.binding.mediaContainer
            for (j in 0 until container.childCount) {
                container.getChildAt(j).findViewById<View>(R.id.mediaAudio)?.takeIf { it.visibility == View.VISIBLE }?.let(::applyVoiceState)
            }
        }
    }

    /** A row scrolled out of view: its pictures stop loading. */
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is MessageHolder) {
            cancelMedia(holder.binding.mediaContainer)
            // An effect still playing on it is not carried over to the next message it shows.
            EffectPlayer.settle(holder.binding.bubble)
            holder.binding.body.stop()
        }
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
            chip.setBackgroundResource(
                when {
                    phoneStyle && reaction.fromMe -> R.drawable.bg_phone_tapback_mine
                    phoneStyle -> R.drawable.bg_phone_tapback
                    reaction.fromMe -> R.drawable.bg_reaction_mine_target
                    else -> R.drawable.bg_reaction_target
                }
            )
            if (phoneStyle) chip.setTextColor(if (reaction.fromMe) holder.onBlue else holder.phoneOnIncoming)
            chip.setPaddingRelative(padStart, chip.paddingTop, padEnd, chip.paddingBottom)
            chip.contentDescription = box.context.resources.getQuantityString(
                if (reaction.fromMe) R.plurals.chat_reaction_mine else R.plurals.chat_reaction, count, reaction.emoji, count,
            )
            chip.isEnabled = !row.isLocal
            chip.alpha = if (chip.isEnabled) 1f else DISABLED_ALPHA
            chip.setOnClickListener { callbacks.onReactionTap(row, reaction) }
            // Held down (enabled or not): who reacted, and when. TalkBack names the action.
            chip.setOnLongClickListener {
                callbacks.onReactionDetails(row, reaction)
                true
            }
            androidx.core.view.ViewCompat.replaceAccessibilityAction(
                chip,
                androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                box.context.getString(R.string.reaction_details_action),
            ) { _, _ ->
                callbacks.onReactionDetails(row, reaction)
                true
            }
            box.addView(chip)
        }
    }

    /** "Delivered", "Read 9:41" (or just "Read" when it is not known when). */
    private fun receiptText(context: android.content.Context, receipt: Receipt): String = when {
        !receipt.read -> context.getString(R.string.imessage_receipt_delivered)
        receipt.readAtMs <= 0 -> context.getString(R.string.imessage_receipt_read)
        else -> context.getString(R.string.imessage_receipt_read_at, TimeFormat.receiptTime(context, receipt.readAtMs))
    }

    /**
     * What TalkBack reads for a bubble: who, what (not invisible ink's words while [inked]), the
     * [effect] it was sent with, when, and how far it got ([receipt] when it says).
     */
    private fun describe(
        holder: MessageHolder,
        item: ThreadItem.Message,
        tick: Tick,
        receipt: String?,
        inked: Boolean,
        effect: String?,
        textEffects: List<com.lightdecoder.imessage.direct.TextEffectKind> = emptyList(),
    ): String {
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
        if (inked) parts += context.getString(R.string.imessage_invisible_ink_hidden) else if (row.text.isNotEmpty()) parts += row.text
        effect?.takeIf { !inked }?.let { parts += it }
        if (textEffects.isNotEmpty()) {
            parts += context.getString(R.string.imessage_text_effect_described, textEffects.joinToString(", ") { context.getString(it.nameRes) })
        }
        if (row.editedAtMs > 0) parts += context.getString(R.string.imessage_edited)
        parts += TimeFormat.clock(context, row.timestampMs)
        holder.binding.statusIcon.contentDescription?.takeIf { tick != Tick.NONE }?.let { parts += it.toString() }
        receipt?.let { parts += it }
        return parts.joinToString(", ")
    }

    private fun isVisual(part: MediaRow): Boolean = isVisualPart(part)

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_DAY = 1
        const val TYPE_MESSAGE = 2
        const val TYPE_SYSTEM = 3

        /** How Material dims a control that cannot be used. */
        const val DISABLED_ALPHA = 0.38f

        /** A message that carries nothing beyond its text (every service's but iMessage's). */
        val NO_EXTRA = IMessageExtra()

        /** The ticks that receipts in words stand in for ([appleReceipts]). */
        val RECEIPT_TICKS = setOf(Tick.SENT, Tick.DELIVERED, Tick.READ)

        // iPhone-style chats ([PhoneStyle]).
        const val PHONE_MAX_WIDTH_FRACTION = 0.75f
        const val PHONE_PADDING_SIDE_DP = 13f
        const val PHONE_PADDING_VERTICAL_DP = 8f
        const val PHONE_MIN_WIDTH_DP = 46f
        const val PHONE_ROW_SIDE_DP = 10f
        const val PHONE_RUN_GAP_DP = 7f
        const val PHONE_IN_RUN_GAP_DP = 1f
        const val PHONE_AVATAR_ROOM_DP = 32f
        const val PHONE_TAPBACK_LIFT_DP = 8f
        const val PHONE_TAPBACK_OVERLAP_DP = 18f
        const val PHONE_FOOTER_SIDE_DP = 8f
        const val PENDING_ALPHA = 0.6f
        const val PHONE_QUOTE_RADIUS_DP = 16f
        const val PHONE_QUOTE_MAX_WIDTH_FRACTION = 0.66f
        const val PHONE_QUOTE_GAP_DP = 3f
        const val PHONE_QUOTE_FILL_ALPHA = 0xCC
        const val SHADOW = 0xB3000000.toInt()

        /** Longest side of a picture in a bubble (decoded at this size or the bubble's width, shown up to it). */
        const val MEDIA_MAX_SIDE_DP = 480

        /** How far the time under a bubble-less picture sits in from its edge. */
        val META_INSET = (4 * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

        val DIFF = object : DiffUtil.ItemCallback<ThreadItem>() {
            override fun areItemsTheSame(a: ThreadItem, b: ThreadItem) = a.key == b.key

            override fun areContentsTheSame(a: ThreadItem, b: ThreadItem): Boolean =
                if (a is ThreadItem.Message && b is ThreadItem.Message) a.sameContent(b) else a == b
        }
    }
}
