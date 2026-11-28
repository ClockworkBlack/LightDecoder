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
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
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
import com.lightdecoder.imessage.direct.DirectArchive
import com.lightdecoder.imessage.direct.IMessageExtra
import com.lightdecoder.imessage.direct.Polls
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

    /** The picture of whoever sent a message, for the avatar beside it in a group ([AvatarPhotos]); null for their initials. */
    var senderPhoto: (MessageRow) -> java.io.File? = { null }

    /** A poll's voter by name ([Polls.Ballot.voter]: [Polls.ME] for the user, else their address). */
    var voterName: (String) -> String = { it }

    /** Whether the user can vote in a poll's card (the iMessage library reaches its chat). */
    var canVote: (MessageRow) -> Boolean = { false }

    /** The file a picture kept with an iMessage is in, by its key ([IMessageExtra.linkImageKey], a sticker's). */
    var pictureFile: (String) -> java.io.File? = { null }

    /**
     * Which of the user's failed messages are iMessages that were not delivered, as an iPhone tells
     * them (sent through the iMessage library, to a chat it reaches): "Not Delivered" under them and,
     * in iPhone-style chats, a red "!" beside them; a tap offers what can be done ([Callbacks.onFailedTap]).
     */
    var notDelivered: (MessageRow) -> Boolean = { false }

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

        /** A poll's choice tapped: the user's vote on it, or taken back ([Polls.toggle]). */
        fun onPollVote(row: MessageRow, optionId: String) = Unit

        /** A poll's "Add Choice" tapped. */
        fun onPollAddChoice(row: MessageRow) = Unit

        /** "Edit Poll" tapped on a poll the user made: its choices locked or unlocked, or one taken off. */
        fun onPollEdit(row: MessageRow) = Unit

        /** A FaceTime call's row tapped ([anchor]: the row's words): call back, with video or audio. */
        fun onCallRowTap(row: MessageRow, anchor: View) = Unit
    }

    /** Messages whose invisible ink was tapped away, by id: their text shows. */
    private val revealed = HashSet<String>()

    /** Edited messages whose earlier versions are shown above them ([IMessageExtra.history]), by id. */
    private val historyShown = HashSet<String>()

    /** Whether [messageId]'s earlier versions are shown (its "Edited" was tapped). */
    fun historyShown(messageId: String): Boolean = messageId in historyShown

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

        /** The notice's own text colours, to go back to after a FaceTime call's row was drawn in this holder. */
        var plainColors: android.content.res.ColorStateList? = null
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

        /** The "Show / Hide earlier versions" accessibility action added at the last bind ([historyShown]). */
        var historyActionId = View.NO_ID

        /** Its message's time, as the last bind wrote it: what iPhone-style chats show beside it on a drag to the side ([TimeReveal]). */
        var shownTime = ""

        /** Whether it is one of the user's own messages, as the last bind found: those slide over for their times ([TimeReveal]). */
        var outgoing = false

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
        val phoneRed = androidx.core.content.ContextCompat.getColor(view.context, R.color.phone_red)
        val failedColor = binding.failedText.currentTextColor

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

    /**
     * A notice; in iPhone-style chats small, grey and upright in the middle (on a picture, in a pill).
     * A FaceTime call's ([com.lightdecoder.imessage.facetime.CallRows]) has the call's sign before its
     * words (a missed one in red) and can be tapped to call back.
     */
    private fun bindSystem(holder: SystemHolder, item: ThreadItem.Message) {
        val context = holder.binding.root.context
        val text = holder.binding.systemText
        text.text = item.row.text.ifBlank { context.getString(R.string.chat_system_message) }
        text.setTypeface(android.graphics.Typeface.DEFAULT, if (phoneStyle) android.graphics.Typeface.NORMAL else android.graphics.Typeface.ITALIC)
        val call = if (com.lightdecoder.imessage.facetime.CallRows.isCallRow(item.row.id)) {
            com.lightdecoder.imessage.direct.IMessageExtra.parse(item.row.extra).call
        } else {
            null
        }
        if (holder.plainColors == null) holder.plainColors = text.textColors
        if (call != null) {
            val missed = call.outcome == com.lightdecoder.imessage.facetime.Outcome.MISSED.wire && !call.outgoing
            val color = if (missed) ContextCompat.getColor(context, R.color.phone_red) else holder.plainColors!!.defaultColor
            val side = (CALL_SIGN_DP * context.resources.displayMetrics.density).toInt()
            val sign = AppCompatResources.getDrawable(context, if (call.video) R.drawable.ic_ft_video else R.drawable.ic_ft_audio)?.mutate()?.apply {
                setTint(color)
                setBounds(0, 0, side, side)
            }
            text.setTextColor(color)
            text.setCompoundDrawablesRelative(sign, null, null, null)
            text.compoundDrawablePadding = side / 3
            text.contentDescription = context.getString(
                if (call.outgoing) R.string.facetime_row_outgoing_description else R.string.facetime_row_incoming_description, text.text,
            )
            text.setOnClickListener { callbacks.onCallRowTap(item.row, text) }
        } else {
            text.setTextColor(holder.plainColors)
            text.setCompoundDrawablesRelative(null, null, null, null)
            text.contentDescription = null
            text.setOnClickListener(null)
            text.isClickable = false
        }
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
        // A poll (iOS 26) draws its own card: its question, choices and votes.
        val poll = app?.takeIf(Polls::isPoll)?.let { pollOf(it.url) }
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
        // Mentions: the names bold in the accent (white on the user's own), one of the user on a soft highlight.
        val mentions = if (inked || app != null) emptyList() else extra.textMentions(row.text)
        val accent = if (outgoing) textColor else if (phone) holder.phoneBlue else holder.readTint
        b.body.text = if (inked) {
            IMessageBubbles.inked(row.text)
        } else {
            MentionMarks.highlight(BubbleText.styled(row.text, runs), mentions, accent, (accent and 0x00FFFFFF) or MENTION_ME_ALPHA)
        }
        val textEffects = runs.mapNotNull { it.effect }.distinct()
        bindAppCard(holder, app.takeIf { poll == null }, textColor, metaColor)
        bindPollCard(holder, row, poll, extra, textColor, metaColor, accent)
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
        holder.shownTime = b.time.text.toString()
        holder.outgoing = outgoing
        // Changed since it was sent: "Edited" by the time, as on an iPhone. A tap shows what it read
        // before (iOS 16) above it, in faded bubbles, and hides them again; with none kept it says when.
        val edited = row.editedAtMs > 0
        val history = if (edited && !inked) extra.history else emptyList()
        val historyOpen = history.isNotEmpty() && row.id in historyShown
        b.edited.visibility = if (edited) View.VISIBLE else View.GONE
        b.edited.setTextColor(metaColor)
        val toggleHistory = View.OnClickListener {
            if (!historyShown.add(row.id)) historyShown.remove(row.id)
            val at = holder.bindingAdapterPosition
            if (at != RecyclerView.NO_POSITION) notifyItemChanged(at)
        }
        b.edited.setOnClickListener(
            when {
                !edited -> null
                history.isNotEmpty() -> toggleHistory
                else -> View.OnClickListener {
                    Toast.makeText(context, context.getString(R.string.imessage_edited_at, TimeFormat.clock(context, row.editedAtMs)), Toast.LENGTH_SHORT).show()
                }
            }
        )
        if (!edited) b.edited.isClickable = false
        val historyLabel = if (history.isEmpty()) null else context.getString(if (historyOpen) R.string.imessage_edit_history_hide else R.string.imessage_edit_history_show)
        ViewCompat.replaceAccessibilityAction(b.edited, AccessibilityActionCompat.ACTION_CLICK, historyLabel, null)
        bindHistory(holder, if (historyOpen) history else emptyList(), outgoing, bubbleColor, toggleHistory)
        // TalkBack reaches "Edited" through the bubble: the same, as an action of it.
        if (holder.historyActionId != View.NO_ID) ViewCompat.removeAccessibilityAction(b.bubble, holder.historyActionId)
        holder.historyActionId = if (historyLabel == null) View.NO_ID else ViewCompat.addAccessibilityAction(b.bubble, historyLabel) { _, _ ->
            toggleHistory.onClick(b.bubble)
            true
        }
        placeEdited(holder)
        val tick = MessageStatus.tickFor(row)
        // A send that may have gone out all the same has its own words: "Not sent" would invite a resend.
        val unconfirmed = MessageStatus.outcomeUnknown(row)
        // An iMessage that was not delivered, as an iPhone says it (and marks it, in iPhone-style chats).
        val undelivered = tick == Tick.FAILED && notDelivered(row)
        val shownTick = if (tick in RECEIPT_TICKS && appleReceipts(row)) Tick.NONE else tick
        bindTick(holder, shownTick, metaColor, unconfirmed, undelivered)
        bindFailedMark(holder, row, undelivered && phone)
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
            // "Not Delivered" in an iPhone's red.
            b.failedText.setTextColor(holder.phoneRed)
        } else {
            b.time.visibility = View.VISIBLE
            underBubble(b.receipt, holder.receiptColor)
            underBubble(b.effect, holder.effectColor)
            underBubble(b.sender, holder.senderColor)
            b.edited.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            b.failedText.setTextColor(holder.failedColor)
        }
        b.column.alpha = if (phone && tick == Tick.PENDING) PENDING_ALPHA else 1f

        // Failure notice under the bubble.
        val retryable = MessageStatus.canRetryLocally(row)
        b.failedText.visibility = if (tick == Tick.FAILED) View.VISIBLE else View.GONE
        b.failedText.setText(
            when {
                undelivered -> R.string.imessage_not_delivered
                unconfirmed -> R.string.chat_failed_unconfirmed
                retryable -> R.string.chat_failed_retry
                else -> R.string.chat_failed_phone
            }
        )
        b.failedText.setOnClickListener { callbacks.onFailedTap(row) }
        // What a tap does, for TalkBack ("double-tap to send again or discard").
        val failedTapLabel = when {
            tick != Tick.FAILED -> null
            undelivered -> context.getString(R.string.imessage_not_delivered_try_again)
            unconfirmed -> context.getString(R.string.chat_failed_unconfirmed_action)
            retryable -> context.getString(R.string.chat_action_retry)
            else -> null
        }
        ViewCompat.replaceAccessibilityAction(b.failedText, AccessibilityActionCompat.ACTION_CLICK, failedTapLabel, null)

        bindReactions(holder, row, extra)
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
        // bubble with pictures or files opens its thread from its faded copy / "N Replies" only.
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
            // A poll's choices take their own taps.
            poll != null -> null
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
            poll != null -> null
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
     * message others answered, and a reply's faded copy of the message it answers, above it on that
     * message's side (a grey outline, smaller text, at most two lines; filled over a chat background).
     * A tap on either opens the thread ([Callbacks.onOpenThread]); TalkBack reads the copy as "Reply
     * to <sender>: <text>", double-tap to open the thread. Elsewhere both are hidden.
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
            copy.isClickable = false
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

    /**
     * A poll's card (iOS 26, [Polls]; none: hidden): its question, then each choice with how many
     * voted for it and who (their initials), the user's own marked, a bar as long as its share of
     * the votes; a tap on a choice votes for it or takes the vote back ([Callbacks.onPollVote]), and
     * "Add Choice" under them adds one ([Callbacks.onPollAddChoice]), where the user can vote.
     */
    private fun bindPollCard(
        holder: MessageHolder,
        row: MessageRow,
        poll: Polls.Poll?,
        extra: IMessageExtra,
        textColor: Int,
        metaColor: Int,
        accent: Int,
    ) {
        val b = holder.binding
        val box = b.pollOptions
        box.removeAllViews()
        if (poll == null) {
            b.pollCard.visibility = View.GONE
            b.pollAddChoice.setOnClickListener(null)
            b.pollEdit.setOnClickListener(null)
            return
        }
        val context = b.root.context
        b.pollCard.visibility = View.VISIBLE
        b.pollTitle.text = poll.title.ifBlank { context.getString(R.string.imessage_poll_summary_untitled) }
        b.pollTitle.setTextColor(textColor)
        val votable = canVote(row)
        val tally = Polls.tally(poll, extra.pollVotes)
        val most = tally.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
        val mine = Polls.choicesOf(extra.pollVotes, Polls.ME).toSet()
        val inflater = LayoutInflater.from(context)
        for (t in tally) {
            val line = inflater.inflate(R.layout.item_poll_option, box, false)
            val chosen = t.option.id in mine
            val mark = line.findViewById<TextView>(R.id.pollOptionMark)
            mark.text = if (chosen) "✓" else ""
            mark.setTextColor(if (chosen) Avatars.textColorOn(accent) else accent)
            mark.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (chosen) accent else Color.TRANSPARENT)
                setStroke(holder.dp(2f), accent)
            }
            line.findViewById<TextView>(R.id.pollOptionText).apply {
                text = t.option.text
                setTextColor(textColor)
            }
            line.findViewById<TextView>(R.id.pollOptionCount).apply {
                text = if (t.count > 0) t.count.toString() else ""
                setTextColor(metaColor)
            }
            val voters = line.findViewById<android.widget.LinearLayout>(R.id.pollOptionVoters)
            for (voter in t.voters.take(MAX_VOTER_FACES)) {
                val face = inflater.inflate(R.layout.item_poll_voter, voters, false) as TextView
                Avatars.bind(face, voterName(voter), voter, "")
                voters.addView(face)
            }
            // How much of the most votes it has: a bar behind it.
            line.findViewById<View>(R.id.pollOptionBar).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = holder.dp(POLL_RADIUS_DP).toFloat()
                    setColor((accent and 0x00FFFFFF) or POLL_BAR_ALPHA)
                }
                pivotX = 0f
                scaleX = t.count.toFloat() / most
                visibility = if (t.count > 0) View.VISIBLE else View.INVISIBLE
            }
            val votes = context.resources.getQuantityString(R.plurals.imessage_poll_votes, t.count, t.count)
            line.contentDescription = context.getString(
                if (chosen) R.string.imessage_poll_option_mine_description else R.string.imessage_poll_option_description, t.option.text, votes,
            )
            line.alpha = if (votable) 1f else DISABLED_ALPHA
            if (votable) {
                line.setOnClickListener { callbacks.onPollVote(row, t.option.id) }
                ViewCompat.replaceAccessibilityAction(
                    line, AccessibilityActionCompat.ACTION_CLICK,
                    context.getString(if (chosen) R.string.imessage_poll_unvote_action else R.string.imessage_poll_vote_action), null,
                )
            } else {
                line.setOnClickListener(null)
                line.isClickable = false
            }
            line.setOnLongClickListener {
                callbacks.onMessageLongPress(row)
                true
            }
            box.addView(line)
        }
        // A poll the user made and locked here: no choice is added to it (nor by others: [com.lightdecoder.imessage.direct.DirectLink.setPollLocked]).
        val locked = extra.pollLocked
        val canAdd = votable && !locked && poll.options.size < Polls.MAX_OPTIONS
        b.pollAddChoice.visibility = if (canAdd) View.VISIBLE else View.GONE
        b.pollAddChoice.setTextColor(accent)
        b.pollAddChoice.setOnClickListener(if (canAdd) View.OnClickListener { callbacks.onPollAddChoice(row) } else null)
        b.pollLockedNote.visibility = if (locked) View.VISIBLE else View.GONE
        b.pollLockedNote.setTextColor(metaColor)
        // The user's own poll: its choices can be locked, and taken off again.
        val owned = votable && row.isOutgoing
        b.pollEdit.visibility = if (owned) View.VISIBLE else View.GONE
        b.pollEdit.setTextColor(accent)
        b.pollEdit.setOnClickListener(if (owned) View.OnClickListener { callbacks.onPollEdit(row) } else null)
    }

    /** Polls read from their cards' addresses, by address (a card is bound again and again). */
    private val polls = android.util.LruCache<String, Polls.Poll>(32)

    private fun pollOf(url: String): Polls.Poll? = polls.get(url) ?: Polls.decode(url)?.also { polls.put(url, it) }

    /** Pictures kept with messages (a link's, a sticker's), by file, size and time, decoded once. */
    private val pictures = object : android.util.LruCache<String, android.graphics.Bitmap>(PICTURE_CACHE_KB) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = (value.byteCount / 1024).coerceAtLeast(1)
    }

    /** The picture in [file], at most [maxSide] on its longest side; null when there is none (yet) or it cannot be read. */
    private fun picture(file: java.io.File, maxSide: Int): android.graphics.Bitmap? {
        if (!file.isFile) return null
        val key = "${file.path}|${file.length()}|${file.lastModified()}|$maxSide"
        pictures.get(key)?.let { return it }
        val decoded = try {
            if (file.length() > MAX_PICTURE_BYTES) null else MediaLoader.decodePreview(file.readBytes(), maxSide)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null
        pictures.put(key, decoded)
        return decoded
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
        // Its page's picture above its lines, as on an iPhone (none: the lines alone).
        val picture = link.image.takeIf { it.isNotEmpty() }?.let(pictureFile)?.let { picture(it, LINK_PICTURE_SIDE) }
        val card = b.linkCard.layoutParams as android.widget.LinearLayout.LayoutParams
        if (picture != null) {
            val width = holder.dp(LINK_CARD_WIDTH_DP)
            card.width = width
            b.linkImage.visibility = View.VISIBLE
            b.linkImage.setImageBitmap(picture)
            val inner = width - b.linkCard.paddingStart - b.linkCard.paddingEnd
            val image = b.linkImage.layoutParams
            image.height = (inner.toLong() * picture.height / picture.width.coerceAtLeast(1)).toInt()
                .coerceIn(holder.dp(LINK_PICTURE_MIN_DP), holder.dp(LINK_PICTURE_MAX_DP))
            b.linkImage.layoutParams = image
        } else {
            card.width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            b.linkImage.visibility = View.GONE
            b.linkImage.setImageDrawable(null)
        }
        b.linkCard.layoutParams = card
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
            Avatars.bind(b.avatar, row.senderName.ifBlank { "?" }, row.senderId.ifBlank { row.senderName }, "", senderPhoto(row))
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

    /**
     * An edited message's earlier [versions] above its bubble (none: hidden), oldest first, as an
     * iPhone shows them once "Edited" is tapped: smaller bubbles of its colour, faded, on its side; a
     * tap on one hides them again ([onTap]). TalkBack reads each as "Earlier version, <time>: <text>".
     */
    private fun bindHistory(holder: MessageHolder, versions: List<IMessageExtra.Version>, outgoing: Boolean, bubbleColor: Int, onTap: View.OnClickListener) {
        val box = holder.binding.editHistory
        box.removeAllViews()
        if (versions.isEmpty()) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        val context = box.context
        val fill = (bubbleColor and 0x00FFFFFF) or (if (outgoing) HISTORY_OWN_ALPHA else HISTORY_ALPHA)
        val textColor = if (phoneStyle) holder.phoneOnIncoming else holder.onIncoming
        // In line with the bubble's own body, not its tail's room.
        val gutter = if (phoneStyle) holder.phoneBubble.gutter else 0
        for (version in versions) {
            val view = TextView(context)
            view.text = version.text
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, HISTORY_TEXT_SP)
            view.setTextColor(textColor)
            view.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = holder.dp(HISTORY_RADIUS_DP).toFloat()
                setColor(fill)
            }
            view.setPaddingRelative(holder.dp(11f), holder.dp(6f), holder.dp(11f), holder.dp(6f))
            view.contentDescription = context.getString(R.string.imessage_edit_history_version, TimeFormat.clock(context, version.atMs), version.text)
            view.setOnClickListener(onTap)
            ViewCompat.replaceAccessibilityAction(view, AccessibilityActionCompat.ACTION_CLICK, context.getString(R.string.imessage_edit_history_hide), null)
            box.addView(
                view,
                android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = if (outgoing) Gravity.END else Gravity.START
                    bottomMargin = holder.dp(HISTORY_GAP_DP)
                    if (outgoing) marginEnd = gutter else marginStart = gutter
                },
            )
        }
    }

    /**
     * iPhone-style chats: the red "!" beside a message that was not delivered ([shown]), the bubble
     * moved over for it, as on an iPhone; a tap on it is a tap on the message's failure.
     */
    private fun bindFailedMark(holder: MessageHolder, row: MessageRow, shown: Boolean) {
        val b = holder.binding
        b.failedMark.visibility = if (shown) View.VISIBLE else View.GONE
        b.failedMark.setOnClickListener(if (shown) View.OnClickListener { callbacks.onFailedTap(row) } else null)
        if (!shown) b.failedMark.isClickable = false
        val column = b.column.layoutParams as FrameLayout.LayoutParams
        val room = if (shown) holder.dp(FAILED_MARK_ROOM_DP) else 0
        if (column.marginEnd != room) {
            column.marginEnd = room
            b.column.layoutParams = column
        }
    }

    /**
     * [unconfirmed]: a failed send that may have gone out ([MessageStatus.outcomeUnknown]);
     * [undelivered]: an iMessage that was not delivered ([notDelivered]), said as an iPhone says it.
     */
    private fun bindTick(holder: MessageHolder, tick: Tick, metaColor: Int, unconfirmed: Boolean, undelivered: Boolean = false) {
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
            Tick.FAILED -> R.drawable.ic_msg_failed to when {
                undelivered -> R.string.imessage_not_delivered
                unconfirmed -> R.string.chat_failed_unconfirmed
                else -> R.string.chat_status_failed
            }
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

    private fun bindReactions(holder: MessageHolder, row: MessageRow, extra: IMessageExtra = NO_EXTRA) {
        val box = holder.binding.reactions
        box.removeAllViews()
        val shown = row.reactions.filter { it.emoji.isNotEmpty() }
        val guid = DirectArchive.guidOf(row.id)
        // Stickers dragged onto it (iOS 17), on its corner as on an iPhone: each one's picture, when it came.
        val stickers = if (guid == null) emptyList() else extra.stickers.mapNotNull { st ->
            pictureFile(IMessageExtra.stickerKey(guid, st.id))?.let { picture(it, STICKER_SIDE_PX) }?.let { st to it }
        }
        if (shown.isEmpty() && stickers.isEmpty()) {
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
            // A sticker as a tapback: the latest one's picture in place of the emoji, as on an iPhone.
            val sticker = if (reaction.emoji == DirectArchive.STICKER_TAPBACK && guid != null) {
                reaction.participantIds.asReversed().firstNotNullOfOrNull { who ->
                    pictureFile(IMessageExtra.tapbackStickerKey(guid, who))?.let { picture(it, STICKER_SIDE_PX) }
                }
            } else {
                null
            }
            if (sticker != null) {
                val side = holder.dp(STICKER_PILL_DP)
                val drawable = android.graphics.drawable.BitmapDrawable(chip.resources, sticker).apply {
                    val w = if (sticker.width >= sticker.height) side else side * sticker.width / sticker.height.coerceAtLeast(1)
                    val h = if (sticker.height >= sticker.width) side else side * sticker.height / sticker.width.coerceAtLeast(1)
                    setBounds(0, 0, w.coerceAtLeast(1), h.coerceAtLeast(1))
                }
                chip.text = if (count > 1) count.toString() else ""
                chip.setCompoundDrawablesRelative(drawable, null, null, null)
                chip.compoundDrawablePadding = if (count > 1) holder.dp(2f) else 0
            } else {
                chip.setCompoundDrawablesRelative(null, null, null, null)
            }
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
                if (reaction.fromMe) R.plurals.chat_reaction_mine else R.plurals.chat_reaction, count,
                if (sticker != null) box.context.getString(R.string.imessage_sticker_tapback) else reaction.emoji, count,
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
        for ((st, bitmap) in stickers) {
            val view = ImageView(box.context)
            val side = holder.dp(PLACED_STICKER_DP)
            view.layoutParams = android.view.ViewGroup.MarginLayoutParams(side, side).apply { marginEnd = holder.dp(2f) }
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            view.setImageBitmap(bitmap)
            view.contentDescription = box.context.getString(
                R.string.imessage_sticker_by, if (st.by == Polls.ME) box.context.getString(R.string.chat_you) else voterName(st.by),
            )
            view.setOnLongClickListener {
                callbacks.onMessageLongPress(row)
                true
            }
            box.addView(view)
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

        /** The side of the sign before a FaceTime call's row. */
        const val CALL_SIGN_DP = 15

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

        /** A mention of the user: the accent at this alpha behind its name. */
        const val MENTION_ME_ALPHA = 0x40000000

        /** A link card with its page's picture: this wide, the picture this tall at most and at least. */
        const val LINK_CARD_WIDTH_DP = 240f
        const val LINK_PICTURE_MAX_DP = 200f
        const val LINK_PICTURE_MIN_DP = 80f
        const val LINK_PICTURE_SIDE = 600

        /** Stickers: on a tapback's pill, and dragged onto a bubble. */
        const val STICKER_PILL_DP = 22f
        const val PLACED_STICKER_DP = 40f
        const val STICKER_SIDE_PX = 160

        /** An edited message's earlier versions: their faded fill (the user's own a little lighter), text, corners and the gap between them. */
        const val HISTORY_ALPHA = 0x8C000000.toInt()
        const val HISTORY_OWN_ALPHA = 0x40000000
        const val HISTORY_TEXT_SP = 14f
        const val HISTORY_RADIUS_DP = 14f
        const val HISTORY_GAP_DP = 3f

        /** The room beside a bubble for its red "!" (not delivered). */
        const val FAILED_MARK_ROOM_DP = 30f

        /** A poll's choice: the bar behind it at this alpha of the accent, its corners; at most this many voters' faces. */
        const val POLL_BAR_ALPHA = 0x33000000
        const val POLL_RADIUS_DP = 12f
        const val MAX_VOTER_FACES = 3

        /** The pictures kept with messages decoded at once, and the largest one read. */
        const val PICTURE_CACHE_KB = 4 * 1024
        const val MAX_PICTURE_BYTES = 4L * 1024 * 1024

        /** How far the time under a bubble-less picture sits in from its edge. */
        val META_INSET = (4 * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

        val DIFF = object : DiffUtil.ItemCallback<ThreadItem>() {
            override fun areItemsTheSame(a: ThreadItem, b: ThreadItem) = a.key == b.key

            override fun areContentsTheSame(a: ThreadItem, b: ThreadItem): Boolean =
                if (a is ThreadItem.Message && b is ThreadItem.Message) a.sameContent(b) else a == b
        }
    }
}
