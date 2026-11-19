package com.lightdecoder.imessage.direct

import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.imessage.TypingBoard
import com.lightdecoder.ui.chat.ThreadItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.rust_bridge.RegisterState

/** Typing both ways, read receipts out, "Delivered" / "Read" under messages, and how Apple's registration state reads. */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class TypingAndReceiptsTest {

    // --- typing out --------------------------------------------------------------------------

    @Test
    fun typingGoesOnTheFirstKeystrokeAndAgainEveryFiftySeconds() {
        val t = TypingThrottle(repeatMs = 50_000)
        assertEquals("the first keystroke", 0, t.keystroke("c", 0))
        assertNull(t.keystroke("c", 1_000))
        assertNull(t.keystroke("c", 49_999))
        assertEquals("50 seconds on, typing all along: the same turn", 0, t.keystroke("c", 50_000))
        assertNull(t.keystroke("c", 60_000))
        // Another chat has its own.
        assertEquals(0, t.keystroke("d", 60_000))
    }

    @Test
    fun stoppedGoesAWhileAfterTheLastKeystroke() {
        val t = TypingThrottle(repeatMs = 50_000)
        t.keystroke("c", 0)
        t.keystroke("c", 3_000)
        // The check a while after the first keystroke: typed since.
        assertFalse(t.idle("c", 0))
        assertTrue("quiet since the last one", t.idle("c", 3_000))
        assertFalse("once", t.idle("c", 3_000))
        // Typing again starts over, in a turn of its own.
        assertEquals(1, t.keystroke("c", 10_000))
    }

    @Test
    fun anEmptiedComposerStopsAndASentMessageNeedsNoStop() {
        val t = TypingThrottle(repeatMs = 50_000)
        assertFalse("never told it was typing", t.cleared("c"))
        t.keystroke("c", 0)
        assertTrue(t.cleared("c"))
        assertFalse("once", t.cleared("c"))
        t.keystroke("c", 1_000)
        t.sent("c")
        assertFalse("the message ended it", t.idle("c", 1_000))
        assertFalse(t.cleared("c"))
        assertEquals("typing after the send starts again", 2, t.keystroke("c", 2_000))
    }

    @Test
    fun aTypingThatWaitedPastItsTurnIsOutOfDate() {
        val t = TypingThrottle(repeatMs = 50_000)
        val turn = t.keystroke("c", 0)!!
        assertTrue(t.current("c", turn))
        assertFalse("nothing ended it yet", t.endedBySend("c", turn))
        // A message went: a "typing" of that turn still waiting is out of date, and one on its
        // way then needs a "stopped" after it (once).
        t.sent("c")
        assertFalse(t.current("c", turn))
        assertTrue(t.endedBySend("c", turn))
        assertFalse("once", t.endedBySend("c", turn))
        // Emptied or paused: out of date as well, but "stopped" follows anyway.
        val next = t.keystroke("c", 1_000)!!
        assertTrue(t.cleared("c"))
        assertFalse(t.current("c", next))
        assertFalse(t.endedBySend("c", next))
        val third = t.keystroke("c", 2_000)!!
        assertTrue(t.idle("c", 2_000))
        assertFalse(t.current("c", third))
        assertFalse(t.endedBySend("c", third))
        // A message with nothing typed ends no turn.
        val fourth = t.keystroke("c", 3_000)!!
        t.sent("c")
        t.sent("c")
        assertTrue(t.endedBySend("c", fourth))
        assertTrue("another chat's turn is its own", t.current("d", 0))
    }

    // --- typing in ---------------------------------------------------------------------------

    @Test
    fun someoneTypingShowsUntilTheyStopOrAMinutePassesUnheard() {
        val board = TypingBoard(expiryMs = 60_000)
        assertTrue(board.note("chat", "+1555", "Alice", typing = true, nowMs = 0))
        assertEquals(mapOf("chat" to setOf("Alice")), board.shown())
        assertFalse("the same again changes nothing", board.note("chat", "+1555", "Alice", typing = true, nowMs = 30_000))
        // Said again at 30 s: shown until 90 s.
        assertFalse(board.expire(60_000))
        assertTrue(board.expire(90_000))
        assertTrue(board.shown().isEmpty())
        // Stopped.
        board.note("chat", "+1555", "Alice", typing = true, nowMs = 100_000)
        board.note("chat", "+1666", "", typing = true, nowMs = 100_000)
        assertEquals(mapOf("chat" to setOf("Alice", "+1666")), board.shown())
        assertTrue(board.note("chat", "+1555", "Alice", typing = false, nowMs = 101_000))
        assertEquals(mapOf("chat" to setOf("+1666")), board.shown())
        assertFalse("not typing: nothing to stop", board.note("other", "+1555", "Alice", typing = false, nowMs = 101_000))
    }

    // --- read receipts out -------------------------------------------------------------------

    @Test
    fun aReadReceiptGoesOncePerMessageUnlessItDidNotGo() {
        val sent = OnceSet(max = 2)
        assertTrue(sent.claim("A"))
        assertFalse(sent.claim("A"))
        sent.release("A")
        assertTrue("it did not go: tried again", sent.claim("A"))
        assertTrue(sent.claim("B"))
        assertTrue(sent.claim("C"))
        assertTrue("only the latest few are remembered", sent.claim("A"))
    }

    // --- Delivered / Read under the user's messages -------------------------------------------

    private fun mine(id: String, status: String, ts: Long, local: String = LocalState.SYNCED) = MessageRow(
        id = id, conversationId = "imsg:tel:+1555", tmpId = if (id.startsWith("tmp:")) id.removePrefix("tmp:") else "",
        senderId = "", senderName = "", isOutgoing = true, timestampMs = ts, direction = "out", status = status, statusCode = 1,
        errorText = "", transport = "IMESSAGE", text = id, subject = "", replyToId = "", reactions = emptyList(),
        localState = local, media = emptyList(), updatedAt = 0,
    )

    private fun theirs(id: String, ts: Long) = mine(id, DirectArchive.STATUS_RECEIVED, ts).copy(isOutgoing = false, direction = "in")

    private val all: (MessageRow) -> Boolean = { true }

    @Test
    fun theLatestReadSaysReadAndALaterDeliveredOneSaysDelivered() {
        val rows = listOf(
            mine("imsg:A", DirectArchive.STATUS_READ, 1),
            mine("imsg:B", DirectArchive.STATUS_READ, 2),
            theirs("imsg:H", 3),
            mine("imsg:C", DirectArchive.STATUS_DELIVERED, 4),
            mine("imsg:D", DirectArchive.STATUS_SENT, 5),
        )
        // As on an iPhone: "Read" under B, "Delivered" stays under C while D is on its way.
        assertEquals(mapOf("imsg:B" to true, "imsg:C" to false), ThreadItems.receipts(rows, all))
        // Once the latest is read, only it says so.
        val read = rows.map { if (it.id == "imsg:D") it.copy(status = DirectArchive.STATUS_READ) else it }
        assertEquals(mapOf("imsg:D" to true), ThreadItems.receipts(read, all))
        // A delivered one before the latest read one is read too: it says nothing.
        assertEquals(
            mapOf("imsg:B" to true),
            ThreadItems.receipts(listOf(mine("imsg:A", DirectArchive.STATUS_DELIVERED, 1), mine("imsg:B", DirectArchive.STATUS_READ, 2)), all),
        )
    }

    @Test
    fun onlyMessagesWithApplesReceiptsSayIt() {
        val rows = listOf(
            mine("imsg:A", DirectArchive.STATUS_DELIVERED, 1),
            mine("sms:1", DirectArchive.STATUS_READ, 2),
            mine("tmp:t1", DirectArchive.STATUS_SENT, 3, LocalState.SENDING),
        )
        assertEquals(mapOf("imsg:A" to false), ThreadItems.receipts(rows) { DirectArchive.isImessage(it.id) })
        assertTrue("nothing delivered or read", ThreadItems.receipts(listOf(mine("imsg:A", DirectArchive.STATUS_SENT, 1)), all).isEmpty())
        assertTrue("a failed one says nothing", ThreadItems.receipts(listOf(mine("imsg:A", DirectArchive.STATUS_FAILED, 1)), all).isEmpty())
    }

    // --- Apple's registration state -------------------------------------------------------------

    @Test
    fun aFailureAppleRetriesIsNotNotWorking() {
        val handles = listOf("tel:+15550000001")
        assertEquals(DirectEngine.Status.Retrying("busy", 30), DirectEngine.statusOf(RegisterState.Failed(30uL, "busy"), handles))
        assertEquals(DirectEngine.Status.Failed("signed out"), DirectEngine.statusOf(RegisterState.Failed(null, "signed out"), handles))
        assertEquals(DirectEngine.Status.Registered(handles), DirectEngine.statusOf(RegisterState.Registered(100L), handles))
        assertTrue(DirectEngine.statusOf(RegisterState.Registering, handles) is DirectEngine.Status.Working)
    }

    @Test
    fun theLibrarysStateInAFewWordsForTheLogs() {
        assertEquals("connected to Apple", DirectEngine.describe(DirectEngine.Status.Registered(listOf("tel:+15550000001"))))
        assertEquals(
            "not connected, Apple's library tries again in 30 s: busy",
            DirectEngine.describe(DirectEngine.Status.Retrying("busy", 30)),
        )
        assertEquals("not working: signed out", DirectEngine.describe(DirectEngine.Status.Failed("signed out")))
        assertEquals("working: Starting", DirectEngine.describe(DirectEngine.Status.Working("Starting")))
        assertEquals("not registered", DirectEngine.describe(DirectEngine.Status.NotRegistered))
    }

    // --- sends Apple did not confirm -----------------------------------------------------------

    @Test
    fun aSendAppleDidNotConfirmIsMarkedSoAcrossTheProcesses() {
        val late = DirectWire.failure(DirectEngine.Unconfirmed("Apple did not confirm the message within 45 seconds."))
        assertEquals("Apple did not confirm the message within 45 seconds.", DirectWire.errorOf(late))
        assertTrue(DirectWire.unconfirmed(org.json.JSONObject(late.toString())))
        assertFalse("refused: it did not go", DirectWire.unconfirmed(DirectWire.failure(DirectEngine.ImessageError("Apple refused the message: no"))))
        // The other process stopped or did not answer while it was asked.
        assertTrue(DirectWire.unconfirmed(DirectWire.error(DirectIpc.Client.DIED)))
        assertTrue(DirectWire.unconfirmed(DirectWire.error(DirectIpc.Client.NO_ANSWER)))
        assertFalse("never asked", DirectWire.unconfirmed(DirectWire.error(DirectIpc.Client.UNREACHABLE)))
        assertFalse(DirectWire.unconfirmed(DirectWire.ok()))
    }
}
