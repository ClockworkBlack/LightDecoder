package com.lightdecoder.ui.chat

import android.content.ActivityNotFoundException
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.lightdecoder.R

/**
 * Links in a bubble for TalkBack. The bubble is one accessibility node (its
 * text view is hidden from TalkBack so a message is read in one go), so the
 * links autoLink found in the text are offered as custom actions on the
 * bubble: "Open link: example.com" in TalkBack's actions menu.
 */
internal object BubbleLinks {

    /** A link [label] (the text shown for it) and the span that opens it. */
    data class Link(val label: String, val span: ClickableSpan)

    /** At most this many links per bubble get an action (Android allows 32 custom actions per node). */
    const val MAX_LINKS = 10

    /** Longest label read out; longer link texts are cut with an ellipsis. */
    private const val MAX_LABEL = 80

    /** The links in [text], in reading order, without repeats of the same text. */
    fun linksIn(text: CharSequence?): List<Link> {
        if (text !is Spanned || text.isEmpty()) return emptyList()
        val spans = text.getSpans(0, text.length, ClickableSpan::class.java)
            .sortedBy { text.getSpanStart(it) }
        val seen = HashSet<String>()
        val links = ArrayList<Link>()
        for (span in spans) {
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            if (start < 0 || end <= start) continue
            val raw = text.subSequence(start, end).toString().trim()
            if (raw.isEmpty() || !seen.add(raw)) continue
            val label = if (raw.length > MAX_LABEL) raw.take(MAX_LABEL - 1) + "…" else raw
            links += Link(label, span)
            if (links.size == MAX_LINKS) break
        }
        return links
    }

    /**
     * Replaces the link actions [previous] on [target] with one per link in
     * [body]'s text, and returns the new action ids (for the next rebind).
     */
    fun bind(target: View, body: TextView, previous: List<Int>): List<Int> {
        previous.forEach { ViewCompat.removeAccessibilityAction(target, it) }
        if (body.visibility != View.VISIBLE) return emptyList()
        val context = target.context
        val ids = ArrayList<Int>()
        for (link in linksIn(body.text)) {
            val id = ViewCompat.addAccessibilityAction(target, context.getString(R.string.chat_open_link, link.label)) { _, _ ->
                open(body, link.span)
            }
            if (id == View.NO_ID) break
            ids += id
        }
        return ids
    }

    private fun open(body: TextView, span: ClickableSpan): Boolean = try {
        span.onClick(body)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
