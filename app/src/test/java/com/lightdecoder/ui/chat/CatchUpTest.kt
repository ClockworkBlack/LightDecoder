package com.lightdecoder.ui.chat

import com.lightdecoder.archive.MessageRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Jump to first unread": where a chat's unread messages begin. */
class CatchUpTest {

    private fun row(id: String, ts: Long, mine: Boolean = false, system: Boolean = false) = MessageRow(
        id = id, conversationId = "c", tmpId = "", senderId = if (mine) "" else "p", senderName = "", isOutgoing = mine, timestampMs = ts,
        direction = when {
            system -> "system"
            mine -> "out"
            else -> "in"
        },
        status = "", statusCode = 0, errorText = "", transport = "", text = id, subject = "", replyToId = "", reactions = emptyList(),
        localState = "synced", media = emptyList(), updatedAt = 0,
    )

    private fun items(vararg rows: MessageRow): List<ThreadItem> =
        listOf<ThreadItem>(ThreadItem.Day(0, "Today")) + rows.map { ThreadItem.Message(it, it.id, null, false, false) }

    @Test
    fun theFirstMessageFromSomeoneElseAtOrAfterTheTime() {
        val list = items(row("a", 100), row("b", 200, mine = true), row("s", 250, system = true), row("c", 300), row("d", 400))
        assertEquals(4, CatchUp.firstUnreadIndex(list, 210))
        assertEquals(4, CatchUp.firstUnreadIndex(list, 300))
        assertEquals(1, CatchUp.firstUnreadIndex(list, 50))
        assertEquals(-1, CatchUp.firstUnreadIndex(list, 500))
        assertEquals(-1, CatchUp.firstUnreadIndex(list, 0))
    }

    @Test
    fun aMergedChatTakesTheEarliest() {
        assertEquals(200L, CatchUp.earliest(listOf(0, 300, 200)))
        assertEquals(0L, CatchUp.earliest(listOf(0, 0)))
    }

    @Test
    fun theArrowOnlyForARowAboveTheScreen() {
        assertTrue(CatchUp.shows(index = 3, firstVisible = 10))
        assertFalse(CatchUp.shows(index = 10, firstVisible = 10))
        assertFalse(CatchUp.shows(index = 12, firstVisible = 10))
        assertFalse(CatchUp.shows(index = -1, firstVisible = 10))
        assertFalse(CatchUp.shows(index = 3, firstVisible = -1))
    }
}
