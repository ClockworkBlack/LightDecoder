package com.lightdecoder.imessage

import com.lightdecoder.archive.MessageRow
import com.lightdecoder.imessage.IMessageCheck.Reply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Check iMessage: what the archives say about how far a check got and how the person replied. */
class IMessageCheckTest {

    private val text = IMessageCheck.text("4821")
    private val sentAt = 100_000L

    @Test
    fun theCheckMessageCarriesItsCode() {
        assertEquals("LightDecoder iMessage check 4821. Please reply OK.", text)
        val code = IMessageCheck.newCode()
        assertEquals(4, code.length)
        assertTrue(code.all(Char::isDigit))
    }

    @Test
    fun nothingYet() {
        val p = IMessageCheck.progress(text, sentAt, emptyList(), emptyList())
        assertEquals(IMessageCheck.Progress(handedOver = false, inOpenBubbles = false, sentAsText = false, reply = Reply.NONE), p)
        assertFalse(p.done)
    }

    @Test
    fun handedOverThenShownByOpenBubbles() {
        val handed = IMessageCheck.progress(text, sentAt, listOf(row("ld:t1", text, sentAt + 10, out = true)), emptyList())
        assertTrue(handed.handedOver)
        assertFalse("OpenBubbles has not shown it yet", handed.inOpenBubbles)
        // OpenBubbles' echo replaces it; its time is in whole seconds, so it can read a little earlier.
        val echoed = IMessageCheck.progress(text, sentAt, listOf(row("ob:abc", text, sentAt - 900, out = true)), emptyList())
        assertTrue(echoed.handedOver)
        assertTrue(echoed.inOpenBubbles)
    }

    @Test
    fun aReplyByIMessageMeansBlue() {
        val p = IMessageCheck.progress(
            text, sentAt,
            listOf(row("ob:abc", text, sentAt, out = true), row("ob:r", "OK", sentAt + 5_000)),
            emptyList(),
        )
        assertEquals(Reply.IMESSAGE, p.reply)
        assertTrue(p.done)
    }

    @Test
    fun aReplyByTextMeansGreen() {
        val p = IMessageCheck.progress(
            text, sentAt,
            listOf(row("ob:abc", text, sentAt, out = true)),
            listOf(row("sms1", "OK", sentAt + 5_000)),
        )
        assertEquals(Reply.TEXT, p.reply)
    }

    @Test
    fun theFirstReplyCountsAndOldOnesDoNot() {
        val p = IMessageCheck.progress(
            text, sentAt,
            listOf(row("ob:old", "earlier", sentAt - 60_000), row("ob:r", "OK", sentAt + 9_000)),
            listOf(row("sms-old", "earlier", sentAt - 30_000), row("sms1", "ok", sentAt + 4_000)),
        )
        assertEquals("the text came first", Reply.TEXT, p.reply)
        val none = IMessageCheck.progress(text, sentAt, listOf(row("ob:old", "hi", sentAt - 1)), listOf(row("sms-old", "hi", sentAt - 1)))
        assertEquals(Reply.NONE, none.reply)
    }

    @Test
    fun goingOutAsATextIsNoticed() {
        val p = IMessageCheck.progress(text, sentAt, emptyList(), listOf(row("tmp:x", text, sentAt + 5, out = true)))
        assertTrue(p.sentAsText)
        assertFalse(p.handedOver)
        assertEquals("my own text is not a reply", Reply.NONE, p.reply)
    }

    private fun row(id: String, text: String, ts: Long, out: Boolean = false) = MessageRow(
        id = id, conversationId = "c", tmpId = "", senderId = if (out) "" else "p1", senderName = "", isOutgoing = out,
        timestampMs = ts, direction = if (out) "out" else "in", status = "", statusCode = 0, errorText = "", transport = "",
        text = text, subject = "", replyToId = "", reactions = emptyList(), localState = "", media = emptyList(), updatedAt = 0,
    )
}
