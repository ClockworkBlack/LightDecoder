package com.lightdecoder.ui.chat

import com.lightdecoder.archive.MessageRow

/**
 * Inline replies as an iPhone shows them (iOS 14 and later), in iPhone-style chats: every reply
 * belongs to the thread of the first message of its chain (its root: a reply to a reply is in
 * the same thread), and the root says "N Replies" under it. By default a reply shows a faded copy
 * of the message it answers above it, and a tap on any message of a thread opens the thread view.
 * With "Show reply threads in the chat" on, a reply is also joined to the message it answers by a
 * thin line when that is close above it (right under it, the line only). Pure, so it can be
 * tested; [ThreadItems.build] puts what it works out on the rows ([Mark]).
 */
object ReplyThreads {

    /** A reply is joined by a line to the message it answers when that is at most this many messages above it. */
    const val CONNECT_WITHIN = 8

    /** How far a chain is followed up (a longer one is cut there: its top is taken as the root). */
    const val MAX_DEPTH = 64

    /**
     * Who is in which thread.
     *
     * @param rootOf a reply's id -> its thread's root (the first message of its chain); only replies are in it
     * @param replies a root's id -> its replies, in the order shown
     */
    data class Threads(val rootOf: Map<String, String>, val replies: Map<String, List<String>>) {
        /** How many replies [rootId] has (0: none, or not a root). */
        fun count(rootId: String): Int = replies[rootId]?.size ?: 0

        /** The thread [messageId] is in: its root's id, its own when it is a root with replies; null otherwise. */
        fun threadOf(messageId: String): String? = rootOf[messageId] ?: messageId.takeIf { replies.containsKey(it) }

        companion object {
            val NONE = Threads(emptyMap(), emptyMap())
        }
    }

    /**
     * How a row of the transcript is drawn for its thread.
     *
     * @param root its thread's root ("" when it is in none)
     * @param replyCount "N Replies" under it (a root's; 0 for the others)
     * @param fadedQuote a faded copy of the message it answers goes above it
     * @param connectTo the message above it a line joins it to (the one it answers, close enough); null for none
     */
    data class Mark(
        val root: String = "",
        val replyCount: Int = 0,
        val fadedQuote: Boolean = false,
        val connectTo: String? = null,
    )

    /**
     * Each reply's root: [rows] are the messages shown, [known] messages read from the archives
     * besides them (the ones replied to that are not shown, [ThreadActivity]'s quote lookups). A
     * chain is followed up through both; where it reaches a message neither has, that message is
     * the root (it is the first message of the chain, even unseen). A cycle (which Apple would not
     * send, but an archive can hold) has its earliest message as the root, for all in it.
     */
    fun roots(rows: List<MessageRow>, known: Map<String, MessageRow> = emptyMap()): Map<String, String> {
        val byId = HashMap<String, MessageRow>(known.size + rows.size)
        byId.putAll(known)
        rows.forEach { byId[it.id] = it }
        val out = LinkedHashMap<String, String>()
        for (row in rows) {
            if (row.replyToId.isEmpty() || row.replyToId == row.id) continue
            out[row.id] = rootOf(row.id, byId)
        }
        return out
    }

    private fun rootOf(start: String, byId: Map<String, MessageRow>): String {
        val seen = LinkedHashSet<String>()
        var at = start
        while (true) {
            if (!seen.add(at)) {
                // A cycle: all in it share the earliest one (by time, then id), whichever was met first.
                val cycle = seen.toList().let { it.subList(it.indexOf(at), it.size) }
                return cycle.minWithOrNull(compareBy<String>({ byId[it]?.timestampMs ?: Long.MAX_VALUE }, { it })) ?: at
            }
            if (seen.size > MAX_DEPTH) return at
            val parent = byId[at]?.replyToId.orEmpty()
            if (parent.isEmpty() || parent == at) return at
            at = parent
        }
    }

    /** [roots] and, for each root, its replies among [rows] in their order. */
    fun threads(rows: List<MessageRow>, known: Map<String, MessageRow> = emptyMap()): Threads {
        val roots = roots(rows, known)
        if (roots.isEmpty()) return Threads.NONE
        val replies = LinkedHashMap<String, MutableList<String>>()
        for (row in rows) {
            val root = roots[row.id] ?: continue
            if (root == row.id) continue
            replies.getOrPut(root) { ArrayList() }.add(row.id)
        }
        return Threads(roots, replies)
    }

    /**
     * The messages of [rootId]'s thread, oldest first: the root (from [rows], else [known]) and
     * every reply to it among [rows], replies to replies too. What the thread view shows.
     */
    fun members(rootId: String, rows: List<MessageRow>, threads: Threads, known: Map<String, MessageRow> = emptyMap()): List<MessageRow> {
        val root = rows.firstOrNull { it.id == rootId } ?: known[rootId]
        val replies = rows.filter { it.id != rootId && threads.rootOf[it.id] == rootId }
        return (listOfNotNull(root) + replies).sortedWith(compareBy<MessageRow> { it.timestampMs }.thenBy { if (it.id == rootId) 0 else 1 })
    }

    /**
     * How a reply is joined to the message it answers ([target]) in the transcript.
     */
    enum class Link {
        /** Not a reply. */
        NONE,

        /** Right above it, nothing between (no time either): a line joins them, no copy. */
        ADJACENT,

        /** Close above it ([CONNECT_WITHIN]): a faded copy above the reply, and a line from the message to it. */
        NEAR,

        /** Further up, or not shown: the faded copy only. */
        FAR,
    }

    /**
     * How the reply at [index] of [rows] (the messages shown, oldest first) is joined to the
     * message it answers. [timeBefore]: whether a time (or day) is drawn above the message at an index.
     */
    fun link(rows: List<MessageRow>, index: Int, timeBefore: (Int) -> Boolean = { false }): Link {
        val row = rows.getOrNull(index) ?: return Link.NONE
        val target = row.replyToId
        if (target.isEmpty() || target == row.id) return Link.NONE
        val from = maxOf(0, index - CONNECT_WITHIN)
        for (j in index - 1 downTo from) {
            if (rows[j].id != target) continue
            if (rows[j].direction == "system") return Link.FAR
            return if (j == index - 1 && !timeBefore(index)) Link.ADJACENT else Link.NEAR
        }
        return Link.FAR
    }

    /**
     * The marks of [rows] (shown, oldest first) in the transcript: counts under roots and a faded
     * copy of the answered message above every reply; with [inChat] ("Show reply threads in the
     * chat") lines too, as [link] says (a reply right under its message then gets the line only).
     * Only rows of a thread are in it.
     */
    fun marks(rows: List<MessageRow>, threads: Threads, inChat: Boolean = false, timeBefore: (Int) -> Boolean = { false }): Map<String, Mark> {
        if (threads.rootOf.isEmpty()) return emptyMap()
        val out = HashMap<String, Mark>()
        for ((i, row) in rows.withIndex()) {
            val count = threads.count(row.id)
            val root = threads.rootOf[row.id]
            if (root == null && count == 0) continue
            val link = if (root != null && inChat) link(rows, i, timeBefore) else Link.NONE
            out[row.id] = Mark(
                root = root ?: row.id,
                replyCount = count,
                fadedQuote = if (inChat) link == Link.NEAR || link == Link.FAR else root != null,
                connectTo = row.replyToId.takeIf { link == Link.ADJACENT || link == Link.NEAR },
            )
        }
        return out
    }

    /**
     * The marks of a thread's messages ([members], oldest first) in the thread view: no copies
     * and no counts, each joined by a line to the one before it, as one chain under the root.
     */
    fun panelMarks(members: List<MessageRow>, rootId: String): Map<String, Mark> =
        members.withIndex().associate { (i, row) ->
            row.id to Mark(root = rootId, connectTo = members.getOrNull(i - 1)?.id)
        }

    /**
     * The message a reply to [row] goes to, as an iPhone (and OpenBubbles) sends it: the root of
     * [row]'s thread, so a reply to a reply stays in the same thread; [row] itself when it is in none
     * or its root is not at hand ([byId] null for it).
     */
    fun replyTarget(row: MessageRow, threads: Threads, byId: (String) -> MessageRow?): MessageRow {
        val root = threads.rootOf[row.id] ?: return row
        return byId(root) ?: row
    }
}
