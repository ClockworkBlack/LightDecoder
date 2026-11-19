package com.lightdecoder.wrapper

import android.graphics.Rect

/**
 * One element of OpenBubbles' screen as Android's accessibility service
 * sees it (its Flutter semantics). An interface so the finding below can be
 * tested with made-up screens.
 */
interface UiNode {
    val text: String
    val description: String
    val hint: String
    val className: String
    val bounds: Rect
    val clickable: Boolean
    val longClickable: Boolean
    val editable: Boolean
    val scrollable: Boolean
    val children: List<UiNode>
    val parent: UiNode?
}

/** Finding things on OpenBubbles' screen (labels from its source, 1.15). Pure: no Android calls. */
object WrapperNodes {

    /** Every node under [root], depth first. */
    fun all(root: UiNode): Sequence<UiNode> = sequence {
        val stack = ArrayDeque<UiNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            yield(n)
            for (i in n.children.indices.reversed()) stack.addLast(n.children[i])
        }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

    /** What a node says: its text, else its description (a Flutter label or tooltip). */
    fun said(n: UiNode): String = n.text.ifBlank { n.description }

    /**
     * The bubble of the message that reads [text]: the lowest one on screen (the newest) whose words
     * contain the message's first words. Null if it is not on screen.
     */
    fun message(root: UiNode, text: String): UiNode? {
        val want = norm(text).take(MATCH_CHARS)
        if (want.isEmpty()) return null
        return all(root).filter { !it.editable && norm(said(it)).contains(want) }
            .maxByOrNull { bottomOf(it) }
    }

    /** Where a node ends on screen: its own bounds, or (a label with none) its nearest ancestor's. */
    private fun bottomOf(n: UiNode): Int {
        var cur: UiNode? = n
        while (cur != null) {
            if (!cur.bounds.isEmpty) return cur.bounds.bottom
            cur = cur.parent
        }
        return 0
    }

    /** The node to long-press for [n]: itself or the nearest ancestor that takes a long press. */
    fun longPressTarget(n: UiNode): UiNode? {
        var cur: UiNode? = n
        while (cur != null) {
            if (cur.longClickable) return cur
            cur = cur.parent
        }
        return null
    }

    /** The node to click for [n]: itself or the nearest clickable ancestor. */
    fun clickTarget(n: UiNode): UiNode? {
        var cur: UiNode? = n
        while (cur != null) {
            if (cur.clickable) return cur
            cur = cur.parent
        }
        return null
    }

    /** A menu action by its title ("Reply", "Undo Send", "Edit"): exactly that, any case. */
    fun action(root: UiNode, title: String): UiNode? {
        val want = norm(title)
        return all(root).firstOrNull { norm(said(it)) == want }
    }

    /** The popup's "more" button: Material's "Show menu" tooltip, or the other skins' "More". */
    fun more(root: UiNode): UiNode? =
        all(root).firstOrNull { norm(said(it)) in MORE_LABELS }

    /** The message field: the editable node, preferring the one hinted "iMessage". */
    fun field(root: UiNode): UiNode? {
        val editable = all(root).filter { it.editable }.toList()
        return editable.firstOrNull { norm(it.hint).startsWith("imessage") || norm(it.hint).startsWith("text") }
            ?: editable.maxByOrNull { it.bounds.bottom }
    }

    /**
     * The send button: it has no label (an icon), so it is found by place: the clickable node on the
     * field's row, to the right of it, furthest right.
     */
    fun send(root: UiNode, field: UiNode): UiNode? {
        val f = field.bounds
        return all(root).filter { n ->
            n !== field && n.clickable && !n.editable &&
                n.bounds.left >= f.right - SLACK_PX && n.bounds.top < f.bottom && n.bounds.bottom > f.top
        }.maxByOrNull { it.bounds.right }
    }

    /** The field OpenBubbles opens over a message being edited (hint "Edited Message"). */
    fun editField(root: UiNode): UiNode? =
        all(root).firstOrNull { it.editable && norm(it.hint).startsWith("edited message") }

    /**
     * A reaction in OpenBubbles' reaction row: drawn as the emoji itself (❤️ 👍 👎 😂 ❗ ❓, then recent
     * ones). Compared without the emoji presentation marks, so ❤ and ❤️ are the same.
     */
    fun reaction(root: UiNode, emoji: String): UiNode? {
        val want = bareEmoji(emoji)
        if (want.isEmpty()) return null
        return all(root).firstOrNull { !it.editable && bareEmoji(said(it)) == want }
    }

    /** Short scrolling rows (the reaction row), widest first, to look further along for an emoji. */
    fun rows(root: UiNode): List<UiNode> =
        all(root).filter { it.scrollable && it.bounds.height() in 1 until it.bounds.width() / 3 }.sortedByDescending { it.bounds.width() }.toList()

    private fun bareEmoji(s: String) = s.trim().replace("\uFE0F", "").replace("\uFE0E", "")

    /** What OpenBubbles says under the user's newest message: "Read …", "Delivered …" or "Sent …". */
    enum class Receipt { SENT, DELIVERED, READ }

    /** The strongest receipt on screen (OpenBubbles shows it under the newest message of one's own), or null. */
    fun receipt(root: UiNode): Receipt? {
        var best: Receipt? = null
        for (n in all(root)) {
            val words = norm(said(n))
            val r = when {
                words == "read" || words.startsWith("read ") -> Receipt.READ
                words == "delivered" || words.startsWith("delivered ") -> Receipt.DELIVERED
                words == "sent" || words.startsWith("sent ") && words.length < 24 -> Receipt.SENT
                else -> null
            } ?: continue
            if (best == null || r.ordinal > best.ordinal) best = r
        }
        return best
    }

    /** A list that can scroll back to older messages. */
    fun scrollable(root: UiNode): UiNode? = all(root).filter { it.scrollable }.maxByOrNull { it.bounds.height() }

    /** Words from the start of a message used to find its bubble. */
    const val MATCH_CHARS = 40
    private const val SLACK_PX = 8
    private val MORE_LABELS = setOf("show menu", "more", "more options")
}
