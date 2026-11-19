package com.lightdecoder.ui.chat

import com.lightdecoder.archive.MessageRow
import com.lightdecoder.chat.Capability
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditRuleTest {
    private val now = 1_700_000_000_000L
    private val can = setOf(Capability.EDIT)

    private fun row(id: String = "M1", outgoing: Boolean = true, ageMs: Long = 60_000, text: String = "hello") = MessageRow(
        id = id, conversationId = "c", tmpId = "", senderId = "me", senderName = "", isOutgoing = outgoing,
        timestampMs = now - ageMs, direction = if (outgoing) "outgoing" else "incoming", status = "", statusCode = 0,
        errorText = "", transport = "", text = text, subject = "", replyToId = "", reactions = emptyList(),
        localState = "", media = emptyList(), updatedAt = 0,
    )

    @Test
    fun onesOwnRecentTextCanBeEdited() {
        assertTrue(EditRule.canEdit(row(), can, now))
    }

    @Test
    fun notWhereTheServiceCannotEdit() {
        assertFalse(EditRule.canEdit(row(), emptySet(), now))
    }

    @Test
    fun notSomeoneElsesNorOldNorUnsentNorEmpty() {
        assertFalse(EditRule.canEdit(row(outgoing = false), can, now))
        assertFalse(EditRule.canEdit(row(ageMs = EditRule.WINDOW_MS), can, now))
        assertFalse(EditRule.canEdit(row(id = MessageRow.tmpMessageId("t")), can, now))
        assertFalse(EditRule.canEdit(row(text = " "), can, now))
    }
}
