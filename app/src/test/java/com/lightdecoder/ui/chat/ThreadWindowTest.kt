package com.lightdecoder.ui.chat

import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MessageRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window a thread shows ([ThreadWindow]): the newest rows of a conversation and of the iMessage
 * chats shown in it, cut from both together, so every iMessage ever stored can be scrolled back to.
 */
class ThreadWindowTest {

    private val page = 50
    private val slack = 50
    private val max = 20_000

    private fun row(id: String, at: Long) = MessageRow(
        id = id, conversationId = "c", tmpId = "", senderId = "a", senderName = "A", isOutgoing = false, timestampMs = at,
        direction = "in", status = "", statusCode = 0, errorText = "", transport = "", text = id, subject = "", replyToId = "",
        reactions = emptyList(), localState = LocalState.SYNCED, media = emptyList(), updatedAt = 0,
    )

    /** An archive of [rows] (any order): its newest [limit] rows, oldest first, as [com.lightdecoder.archive.ArchiveRepository.messages] gives them. */
    private fun archive(rows: List<MessageRow>, reads: MutableList<Int>? = null): (Int) -> List<MessageRow> = { limit ->
        reads?.add(limit)
        rows.sortedBy { it.timestampMs }.takeLast(limit)
    }

    private fun texts(n: Int, from: Long = 0, step: Long = 1000) = (0 until n).map { row("sms$it", from + it * step) }
    private fun iMessages(n: Int, from: Long = 500, step: Long = 1000) = (0 until n).map { row("imsg:$it", from + it * step) }

    /** Scrolls up as the thread screen does: the window as wide as what is shown plus a page, until nothing more comes. */
    private fun scrollToTheTop(own: (Int) -> List<MessageRow>, companion: ((Int) -> List<MessageRow>)?): ThreadWindow.Read {
        var read = ThreadWindow.read(page, null, slack, null, max, own, companion)
        var steps = 0
        while (read.allShown != true) {
            val before = read.shown.size
            read = ThreadWindow.read(maxOf(page, before) + page, null, slack, null, max, own, companion)
            assertTrue("each step up shows older rows", read.shown.size > before)
            assertTrue("it ends", ++steps < 1000)
        }
        return read
    }

    @Test
    fun aFewTextsDoNotHideTheOlderIMessages() {
        // What was wrong until 0.5.73.1: ten texts and a thousand iMessages showed the newest hundred iMessages at most.
        val sms = texts(10, from = 990_000)
        val im = iMessages(1000)
        val all = scrollToTheTop(archive(sms), archive(im))
        assertEquals(1010, all.shown.size)
        assertEquals(1000, all.companion.size)
        assertEquals(10, all.own.size)
        assertEquals(all.shown.map { it.timestampMs }.sorted(), all.shown.map { it.timestampMs })
        assertEquals("imsg:0", all.shown.first().id)
    }

    @Test
    fun onlyIMessagesAndNoTextsAtAll() {
        val all = scrollToTheTop(archive(emptyList()), archive(iMessages(430)))
        assertEquals(430, all.shown.size)
        assertTrue(all.ownExhausted)
        assertEquals(0, all.ownStored)
    }

    @Test
    fun theFirstWindowIsTheNewestOfBoth() {
        val sms = texts(200)
        val im = iMessages(200)
        val read = ThreadWindow.read(page, null, slack, null, max, archive(sms), archive(im))
        assertEquals(page, read.shown.size)
        // The newest fifty of the four hundred, in order: no row of either kind left out in between.
        val expected = (sms + im).sortedBy { it.timestampMs }.takeLast(page).map { it.id }
        assertEquals(expected, read.shown.map { it.id })
        assertEquals(25, read.own.size)
        assertEquals(25, read.companion.size)
        assertEquals(false, read.allShown)
        // The archive holds older texts than the ones shown: the phone is not asked yet.
        assertFalse(read.ownExhausted)
    }

    @Test
    fun nothingIsLeftOutBetweenTheOldestRowShownAndTheNewest() {
        // Many iMessages in a short while among texts spread over a long one, and the other way round.
        val sms = texts(120, from = 0, step = 10_000)
        val im = iMessages(300, from = 900_000, step = 100)
        for (wanted in listOf(50, 100, 150, 250, 400)) {
            val read = ThreadWindow.read(wanted, null, slack, null, max, archive(sms), archive(im))
            val oldest = read.shown.first().timestampMs
            val expected = (sms + im).filter { it.timestampMs >= oldest }.map { it.id }.toSet()
            assertEquals("window of $wanted", expected, read.shown.map { it.id }.toSet())
            assertTrue(read.shown.size <= wanted)
        }
    }

    @Test
    fun aReloadKeepsTheTopWhereItIsAndAddsWhatCame() {
        val sms = texts(30).toMutableList()
        val im = iMessages(300).toMutableList()
        val first = ThreadWindow.read(100, null, slack, null, max, archive(sms), archive(im))
        val anchor = first.shown.first().timestampMs
        sms += row("sms-new", 2_000_000)
        im += row("imsg:new", 2_000_001)
        val again = ThreadWindow.read(100, anchor, slack, null, max, archive(sms), archive(im))
        assertEquals(first.shown.map { it.id } + listOf("sms-new", "imsg:new"), again.shown.map { it.id })
        // Read from the oldest row shown on: it does not say whether older ones are stored.
        assertNull(again.allShown)
    }

    @Test
    fun thePhoneIsAskedOnlyOnceTheWindowReachesTheOldestStoredText() {
        // Ten texts from long ago under a thousand iMessages: the phone may hold older texts, which matter
        // only once the window is back at the oldest stored one.
        val sms = texts(10, from = 0)
        val im = iMessages(1000, from = 1_000_000)
        val newest = ThreadWindow.read(page, null, slack, null, max, archive(sms), archive(im))
        assertFalse(newest.ownExhausted)
        assertEquals(10, newest.ownStored)
        val back = ThreadWindow.read(1010, null, slack, null, max, archive(sms), archive(im))
        assertTrue(back.ownExhausted)
        // And a conversation whose few texts are all among the newest rows: asked at once, as before.
        val recent = ThreadWindow.read(page, null, slack, null, max, archive(texts(10, from = 2_000_000)), archive(im))
        assertTrue(recent.ownExhausted)
        assertEquals(10, recent.ownStored)
    }

    @Test
    fun aSearchHitAmongOldIMessagesIsReached() {
        val sms = texts(20, from = 5_000_000)
        val im = iMessages(3000)
        val read = ThreadWindow.read(page, null, slack, "imsg:7", max, archive(sms), archive(im))
        assertTrue(read.shown.any { it.id == "imsg:7" })
        // From the hit to the newest, whole.
        val oldest = read.shown.first().timestampMs
        assertEquals((sms + im).count { it.timestampMs >= oldest }, read.shown.size)
        // Also when the thread was already showing its newest rows.
        val anchored = ThreadWindow.read(page, im.last().timestampMs - 10_000, slack, "imsg:7", max, archive(sms), archive(im))
        assertTrue(anchored.shown.any { it.id == "imsg:7" })
    }

    @Test
    fun aHitThatIsNotThereEndsAtTheWidestWindow() {
        val reads = ArrayList<Int>()
        val read = ThreadWindow.read(page, null, slack, "gone", 400, archive(texts(1000), reads), archive(iMessages(1000)))
        assertEquals(400, read.shown.size)
        assertEquals(listOf(50, 100, 200, 400), reads)
        // Nor does it go on reading once both archives are through.
        reads.clear()
        val small = ThreadWindow.read(page, null, slack, "gone", max, archive(texts(5), reads), archive(iMessages(5)))
        assertEquals(10, small.shown.size)
        assertEquals(listOf(50), reads)
        assertEquals(true, small.allShown)
    }

    @Test
    fun severalIMessageChatsOfOnePersonAreOneHistory() {
        // The person's chat from before iMessage was set up again, and the one since: both in the conversation.
        val old = (0 until 150).map { row("imsg:old$it", it * 1000L) }
        val now = (0 until 150).map { row("imsg:now$it", 200_000 + it * 1000L) }
        val both: (Int) -> List<MessageRow> = { limit -> (archive(old)(limit) + archive(now)(limit)).distinctBy { it.id } }
        val all = scrollToTheTop(archive(texts(3, from = 400_000)), both)
        assertEquals(303, all.shown.size)
        assertEquals("imsg:old0", all.shown.first().id)
    }

    @Test
    fun aConversationByItselfIsReadAsItAlwaysWas() {
        val sms = texts(130)
        val first = ThreadWindow.read(page, null, slack, null, max, archive(sms), null)
        assertEquals(sms.takeLast(50).map { it.id }, first.shown.map { it.id })
        assertTrue(first.companion.isEmpty())
        assertFalse(first.ownExhausted)
        assertEquals(false, first.allShown)
        val all = scrollToTheTop(archive(sms), null)
        assertEquals(130, all.shown.size)
        assertTrue(all.ownExhausted)
        // A short one: the phone is asked for more (it may never have been read in).
        val short = ThreadWindow.read(page, null, slack, null, max, archive(texts(7)), null)
        assertTrue(short.ownExhausted)
        assertEquals(7, short.ownStored)
        assertEquals(true, short.allShown)
        // From the oldest row shown on, with what came since.
        val anchored = ThreadWindow.read(page, sms[100].timestampMs, slack, null, max, archive(sms + row("new", 9_000_000)), null)
        assertEquals(31, anchored.shown.size)
        assertNull(anchored.allShown)
        // A hit further back widens it.
        val hit = ThreadWindow.read(page, sms[100].timestampMs, slack, "sms3", max, archive(sms), null)
        assertTrue(hit.shown.any { it.id == "sms3" })
    }
}
