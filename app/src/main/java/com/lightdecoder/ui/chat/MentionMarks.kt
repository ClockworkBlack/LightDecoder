package com.lightdecoder.ui.chat

import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextWatcher
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import com.lightdecoder.imessage.direct.Mention
import com.lightdecoder.imessage.direct.Mentions

/**
 * A mention in the composer (iOS 17): the member's address ([handle]) and the name it was put in
 * as ([name]), drawn bold in [color]. It is one thing: a deletion that reaches into it takes all of
 * it, and anything typed into it makes it plain text ([MentionWatcher]).
 */
internal class MentionSpan(val handle: String, val name: String, private val color: Int) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(tp: TextPaint) {
        tp.color = color
        tp.isFakeBoldText = true
    }
}

/** A mention's name in a bubble: bold in [color]; one of the user also on [background]. */
internal class MentionHighlightSpan(private val color: Int, private val background: Int?) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(tp: TextPaint) {
        tp.color = color
        tp.isFakeBoldText = true
        if (background != null) tp.bgColor = background
    }
}

/** Mentions in the composer and in bubbles. Tested in MentionsScreenTest. */
internal object MentionMarks {

    /** [text] (a bubble's, perhaps already formatted) with [mentions] drawn as names: bold in [color], one of the user also on [mineBackground]. */
    fun highlight(text: CharSequence, mentions: List<Mention>, color: Int, mineBackground: Int): CharSequence {
        if (mentions.isEmpty() || text.isEmpty()) return text
        val out = SpannableString(text)
        for (m in Mentions.normalize(text.toString(), mentions)) {
            out.setSpan(MentionHighlightSpan(color, if (m.mine) mineBackground else null), m.start, m.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    /** The composer's mentions over [text], as they are sent ([Mentions.normalize] makes them whole and in order). */
    fun mentions(text: CharSequence): List<Mention> {
        if (text !is Spanned || text.isEmpty()) return emptyList()
        val found = text.getSpans(0, text.length, MentionSpan::class.java).mapNotNull { span ->
            val s = text.getSpanStart(span)
            val e = text.getSpanEnd(span)
            if (s < 0 || e <= s || text.subSequence(s, e).toString() != span.name) null else Mention(s, e, span.handle)
        }
        return Mentions.normalize(text.toString(), found)
    }

    /** The stretches the composer's mentions take ([Mentions.query] does not look inside them). */
    fun taken(text: CharSequence): List<IntRange> {
        if (text !is Spanned) return emptyList()
        return text.getSpans(0, text.length, MentionSpan::class.java).mapNotNull { span ->
            val s = text.getSpanStart(span)
            val e = text.getSpanEnd(span)
            if (s < 0 || e <= s) null else s until e
        }
    }

    /**
     * The mention of [member] in place of [query] (the "@" and what was typed after it) in [text], as
     * an iPhone puts one in: their first name ([Mentions.shownName]), then a space unless one follows.
     * The cursor's place after it, or -1 when [query] is no longer there.
     */
    fun insert(text: Editable, query: Mentions.Query, member: Mentions.Member, color: Int): Int {
        if (query.start < 0 || query.end > text.length || query.start >= query.end || text[query.start] != '@') return -1
        val name = Mentions.shownName(member.name).ifBlank { com.lightdecoder.imessage.direct.Handles.display(member.handle) }
        // The keyboard's word being typed is over: the name replaces it whole.
        BaseInputConnection.removeComposingSpans(text)
        val space = query.end < text.length && text[query.end] == ' '
        text.replace(query.start, query.end, if (space) name else "$name ")
        text.setSpan(MentionSpan(member.handle, name, color), query.start, query.start + name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return query.start + name.length + 1
    }
}

/**
 * Keeps the composer's mentions whole and says what name is being looked up after "@": a deletion
 * that reaches into a mention takes all of it (as on an iPhone), and a change inside one leaves its
 * words as plain text. After each change [onQuery] hears of the [Mentions.Query] at the cursor, or
 * null (also whenever [offered] says mentions are not for this chat).
 */
internal class MentionWatcher(
    private val field: EditText,
    private val offered: () -> Boolean,
    private val onQuery: (Mentions.Query?) -> Unit,
) : TextWatcher {

    /** The mentions the change about to happen deletes part of. */
    private var cut: List<MentionSpan> = emptyList()
    private var busy = false

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
        if (busy || s !is Spanned || count == 0 || after != 0) {
            cut = emptyList()
            return
        }
        val end = start + count
        cut = s.getSpans(start, end, MentionSpan::class.java).filter { span ->
            val ss = s.getSpanStart(span)
            val se = s.getSpanEnd(span)
            ss < end && se > start && !(start <= ss && end >= se)
        }
    }

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

    override fun afterTextChanged(e: Editable?) {
        if (e == null || busy) return
        busy = true
        try {
            // What a deletion reached into goes whole.
            for (span in cut) {
                val ss = e.getSpanStart(span)
                val se = e.getSpanEnd(span)
                e.removeSpan(span)
                if (ss in 0 until se) e.delete(ss, se)
            }
            cut = emptyList()
            // Changed some other way (typed into): plain text from now on.
            for (span in e.getSpans(0, e.length, MentionSpan::class.java)) {
                val ss = e.getSpanStart(span)
                val se = e.getSpanEnd(span)
                if (ss < 0 || se <= ss || e.subSequence(ss, se).toString() != span.name) e.removeSpan(span)
            }
        } finally {
            busy = false
        }
        onQuery(if (offered()) Mentions.query(e, field.selectionEnd.coerceAtMost(e.length), MentionMarks.taken(e)) else null)
    }
}
