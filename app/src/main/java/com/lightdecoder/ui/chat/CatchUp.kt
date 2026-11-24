package com.lightdecoder.ui.chat

/**
 * "Jump to first unread" (the catch-up arrow at the top of a chat opened with unread messages, as on
 * an iPhone): where the chat's unread messages begin. The archive keeps one time per chat
 * ([com.lightdecoder.archive.ConversationRow.firstUnreadTs]), the arriving message's as the chat
 * became unread; here that becomes a row of the list.
 *
 * Pure. Tested in CatchUpTest.
 */
object CatchUp {

    /**
     * The index in [items] of the first message someone else sent at or after [sinceMs] (the oldest
     * unread one), or -1 when there is none (or [sinceMs] is not set).
     */
    fun firstUnreadIndex(items: List<ThreadItem>, sinceMs: Long): Int {
        if (sinceMs <= 0) return -1
        return items.indexOfFirst { it is ThreadItem.Message && it.row.direction == "in" && !it.row.isOutgoing && it.row.timestampMs >= sinceMs }
    }

    /**
     * Where the unread messages of a chat shown with its companions begin: the earliest of the times
     * ([times]) that are set (0: not set), or 0 when none is.
     */
    fun earliest(times: List<Long>): Long = times.filter { it > 0 }.minOrNull() ?: 0L

    /** Whether the arrow shows: there is a first unread row, and it is above the first row on screen. */
    fun shows(index: Int, firstVisible: Int): Boolean = index >= 0 && firstVisible >= 0 && index < firstVisible
}
