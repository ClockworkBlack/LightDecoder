package com.lightdecoder.ui.chat

import com.lightdecoder.archive.MessageRow

/**
 * The window of messages a thread shows: the newest ones, as many as asked for, of the
 * conversation's own rows and, in a Messages conversation with the person's iMessage chats in it
 * ([BlueBubblesLinks]), of those chats' rows as well. The two come from different archives; the
 * window is cut from both together, so that scrolling up goes back through all of them. (Until
 * 0.5.73.1 it was cut from the conversation's own rows alone and the iMessages were only laid over
 * it: with few texts and many iMessages the window never grew past the newest iMessages, the older
 * ones could not be reached, and "Start of the conversation" stood above them.)
 *
 * Pure: the archives are read through the two functions it is given. Tested in ThreadWindowTest.
 */
internal object ThreadWindow {

    /** What one read of the archives gave. */
    class Read(
        /** The conversation's own rows that are shown, oldest first. */
        val own: List<MessageRow>,
        /** Its iMessage chats' rows that are shown, oldest first (none without such chats). */
        val companion: List<MessageRow>,
        /** Both together, oldest first. */
        val shown: List<MessageRow>,
        /**
         * The conversation's archive holds no row of its own older than the ones shown: older ones,
         * if there are any, are on the phone (which is asked for them).
         */
        val ownExhausted: Boolean,
        /** How many rows of its own the conversation's archive is known to hold (all of them when [ownExhausted]). */
        val ownStored: Int,
        /**
         * Everything the archives hold is shown (the conversation's and its iMessage chats'): null when
         * this read could not tell (one from the oldest row shown on, which looks no further back).
         */
        val allShown: Boolean?,
    )

    /**
     * Reads the window.
     *
     * @param wanted how many rows the window holds at most when it is cut anew
     * @param anchorMs the time of the oldest row shown now, to read from there on (new arrivals are
     *   added and the top stays where it is); null to cut the window anew ([wanted] newest rows)
     * @param slack how many rows more than [wanted] are read from [anchorMs] on (arrivals since)
     * @param target a message that must be in the window (a search hit, a reply's original): the
     *   window is widened, doubling, until it is, the archives have no more, or [maxWindow]
     * @param own the conversation's newest rows, at most as many as asked, oldest first
     * @param companion its iMessage chats' newest rows (each chat's, at most as many as asked,
     *   together and without doubles), oldest first; null without such chats
     */
    fun read(
        wanted: Int,
        anchorMs: Long?,
        slack: Int,
        target: String?,
        maxWindow: Int,
        own: (Int) -> List<MessageRow>,
        companion: ((Int) -> List<MessageRow>)?,
    ): Read {
        if (companion == null) return readOwn(wanted, anchorMs, slack, target, maxWindow, own)
        if (anchorMs != null) {
            val limit = wanted + slack
            val ownAll = own(limit)
            val o = ownAll.filter { it.timestampMs >= anchorMs }
            val c = companion(limit).filter { it.timestampMs >= anchorMs }
            if (target == null || o.any { it.id == target } || c.any { it.id == target }) {
                return Read(
                    own = o,
                    companion = c,
                    shown = merged(o, c),
                    ownExhausted = ownAll.size < limit && o.size == ownAll.size,
                    ownStored = ownAll.size,
                    allShown = null,
                )
            }
            // The target is further back than what is shown: the window is cut anew, as wide as it takes.
            return cut(maxOf(wanted, o.size + c.size), target, maxWindow, own, companion)
        }
        return cut(wanted, target, maxWindow, own, companion)
    }

    /** The [size] newest rows of both archives together; wider, doubling, until [target] is among them. */
    private fun cut(
        size: Int,
        target: String?,
        maxWindow: Int,
        own: (Int) -> List<MessageRow>,
        companion: (Int) -> List<MessageRow>,
    ): Read {
        var limit = size.coerceAtLeast(1)
        while (true) {
            val o = own(limit)
            val c = companion(limit)
            val all = merged(o, c)
            // Each archive's part of the newest [limit] rows of both is among its own newest [limit]: nothing is left out in between.
            val shown = if (all.size > limit) all.subList(all.size - limit, all.size) else all
            val more = all.size >= limit
            if (target == null || shown.any { it.id == target } || !more || limit >= maxWindow) {
                val ids = shown.mapTo(HashSet(shown.size)) { it.id }
                val ownIds = o.mapTo(HashSet(o.size)) { it.id }
                val ownShown = o.filter { it.id in ids }
                return Read(
                    own = ownShown,
                    companion = shown.filter { it.id !in ownIds },
                    shown = shown,
                    ownExhausted = o.size < limit && ownShown.size == o.size,
                    ownStored = o.size,
                    allShown = all.size <= limit && o.size < limit && c.size < limit,
                )
            }
            limit = (limit.toLong() * 2).coerceAtMost(maxWindow.toLong()).toInt()
        }
    }

    /** A conversation by itself (no iMessage chats in it): its own rows, as threads were always read. */
    private fun readOwn(wanted: Int, anchorMs: Long?, slack: Int, target: String?, maxWindow: Int, own: (Int) -> List<MessageRow>): Read {
        var anew = anchorMs == null
        var rows = if (anchorMs == null) own(wanted) else own(wanted + slack).filter { it.timestampMs >= anchorMs }
        var size = maxOf(wanted, rows.size)
        if (target != null && rows.none { it.id == target }) {
            if (!anew) {
                // Further back than what is shown: looked for from the newest row on, not only from the oldest one shown.
                rows = own(size)
                anew = true
            }
            while (rows.none { it.id == target } && rows.size >= size && size < maxWindow) {
                size = (size.toLong() * 2).coerceAtMost(maxWindow.toLong()).toInt()
                rows = own(size)
                anew = true
            }
        }
        return Read(
            own = rows,
            companion = emptyList(),
            shown = rows,
            ownExhausted = rows.size < wanted,
            ownStored = rows.size,
            allShown = if (anew) rows.size < size else null,
        )
    }

    /** Both archives' rows in the order they are shown: by time, the conversation's own first among equals. */
    private fun merged(own: List<MessageRow>, companion: List<MessageRow>): List<MessageRow> =
        if (companion.isEmpty()) own else (own + companion).sortedBy { it.timestampMs }
}
