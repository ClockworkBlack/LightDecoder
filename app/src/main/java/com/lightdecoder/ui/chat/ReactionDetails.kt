package com.lightdecoder.ui.chat

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.R as MaterialR
import com.lightdecoder.R
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.archive.ParticipantRow
import com.lightdecoder.reactions.ReactionKey
import com.lightdecoder.reactions.ReactionTime

/**
 * "Reactions": who put which reaction on a message, and when, opened by
 * holding a reaction chip down.
 *
 * The time is the most LightDecoder knows (see [com.lightdecoder.reactions.ReactionTimes]):
 * exact for an iPhone's reaction texts, the moment it arrived for reactions
 * received while connected, "at or before" for ones that arrived as the link
 * caught up, and "not known" for ones already there when the message was
 * first loaded.
 */
object ReactionDetails {

    /** One person's reaction. [time] is null when it is not known. */
    data class Entry(val emoji: String, val participantId: String, val name: String, val isMe: Boolean, val time: ReactionTime?)

    /**
     * Every reaction on [row], one per person: the [chosen] emoji's first,
     * then the rest; within each, oldest first, unknown times last.
     */
    @VisibleForTesting
    internal fun entries(
        row: MessageRow,
        chosen: String,
        participants: List<ParticipantRow>,
        stored: Map<ReactionKey, ReactionTime>,
        you: String,
        unknown: String,
    ): List<Entry> {
        val list = ArrayList<Entry>()
        for (reaction in row.reactions) {
            if (reaction.emoji.isEmpty()) continue
            val ids = reaction.participantIds.ifEmpty { listOf(if (reaction.fromMe) ME else "") }
            for (id in ids) {
                val person = participants.firstOrNull { it.id == id }
                val isMe = person?.isMe == true || id == ME ||
                    (reaction.fromMe && ids.size == 1 && person == null)
                val name = when {
                    isMe -> you
                    person != null -> person.name.ifBlank { person.formattedNumber.ifBlank { person.number } }.ifBlank { unknown }
                    // An iPhone's reaction text that named its sender rather than giving an id.
                    id.isNotBlank() && id != "them" && !id.all { it.isDigit() } -> id
                    else -> unknown
                }
                val exact = reaction.madeAtMs[id]?.takeIf { it > 0 }?.let { ReactionTime(it, ReactionTime.Kind.EXACT) }
                list += Entry(reaction.emoji, id, name, isMe, exact ?: stored[ReactionKey(reaction.emoji, id)])
            }
        }
        return list.sortedWith(
            compareBy<Entry> { it.emoji != chosen }
                .thenBy { it.time == null }
                .thenBy { it.time?.atMs ?: Long.MAX_VALUE }
        )
    }

    /** How [time] reads: "Today, 4:21 PM", "At or before Yesterday, 9:05 AM", or "Time not known". */
    fun describe(context: Context, time: ReactionTime?, nowMs: Long = System.currentTimeMillis()): String {
        if (time == null) return context.getString(R.string.reaction_time_unknown)
        val at = context.getString(
            R.string.reaction_time_at,
            TimeFormat.dayLabel(context, time.atMs, nowMs),
            TimeFormat.clock(context, time.atMs),
        )
        return if (time.kind == ReactionTime.Kind.BY) context.getString(R.string.reaction_time_by, at) else at
    }

    fun show(
        context: Context,
        row: MessageRow,
        chosen: String,
        participants: List<ParticipantRow>,
        stored: Map<ReactionKey, ReactionTime>,
    ) {
        val entries = entries(
            row, chosen, participants, stored,
            you = context.getString(R.string.chat_you), unknown = context.getString(R.string.chat_unknown_sender),
        )
        if (entries.isEmpty()) return
        val dp = context.resources.displayMetrics.density
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
        }
        for (entry in entries) {
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (56 * dp).toInt()
                // Read as one: "Alice, ❤️, Today, 4:21 PM".
                isFocusable = true
            }
            val emoji = TextView(context).apply {
                text = entry.emoji
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                importantForAccessibility = TextView.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val words = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPaddingRelative((16 * dp).toInt(), 0, 0, 0)
                importantForAccessibility = LinearLayout.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            val time = describe(context, entry.time)
            words.addView(TextView(context).apply {
                text = entry.name
                setTextAppearance(themeAttr(context, MaterialR.attr.textAppearanceBodyLarge))
            })
            words.addView(TextView(context).apply {
                text = time
                setTextAppearance(themeAttr(context, MaterialR.attr.textAppearanceBodyMedium))
                setTextColor(com.google.android.material.color.MaterialColors.getColor(this, MaterialR.attr.colorOnSurfaceVariant))
            })
            line.addView(emoji)
            line.addView(words)
            line.contentDescription = context.getString(R.string.reaction_details_line, entry.name, entry.emoji, time)
            column.addView(line)
        }
        column.addView(TextView(context).apply {
            setText(R.string.reaction_details_note)
            setTextAppearance(themeAttr(context, MaterialR.attr.textAppearanceBodySmall))
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this, MaterialR.attr.colorOnSurfaceVariant))
            setPadding(0, (12 * dp).toInt(), 0, 0)
        })
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.reaction_details_title)
            .setView(ScrollView(context).apply { addView(column) })
            .setPositiveButton(R.string.reaction_details_close, null)
            .show()
    }

    private fun themeAttr(context: Context, attr: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) value.resourceId else 0
    }

    /** The id Google Messages gives the phone's own user in a reaction. */
    private const val ME = "1"
}

/** The line that says when one of the user's messages was read (in the sheet a held message opens). */
object ReadInfo {
    fun text(context: Context, time: ReactionTime?, nowMs: Long = System.currentTimeMillis()): String {
        if (time == null) return context.getString(R.string.read_info_unknown)
        val at = context.getString(
            R.string.reaction_time_at,
            TimeFormat.dayLabel(context, time.atMs, nowMs),
            TimeFormat.clock(context, time.atMs),
        )
        return context.getString(if (time.kind == ReactionTime.Kind.BY) R.string.read_info_by else R.string.read_info_at, at)
    }
}
