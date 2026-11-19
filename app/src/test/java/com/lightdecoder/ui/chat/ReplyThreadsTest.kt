package com.lightdecoder.ui.chat

import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MessageRow
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Inline-reply threads as an iPhone shows them ([ReplyThreads]): roots, counts, faded copies and lines. */
class ReplyThreadsTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val dayStart: (Long) -> Long = { TimeFormat.startOfDay(it, utc) }
    private val noon = 1_700_000_000_000L / 86_400_000L * 86_400_000L + 12 * 3_600_000L
    private val second = 1_000L

    private var clock = noon

    private fun row(id: String, replyTo: String = "", mine: Boolean = false, at: Long = clock.also { clock += 10 * second }, chat: String = "c1", system: Boolean = false) =
        MessageRow(
            id = id, conversationId = chat, tmpId = "", senderId = if (mine) "" else "alice", senderName = if (mine) "" else "Alice",
            isOutgoing = mine, timestampMs = at,
            direction = when {
                system -> "system"
                mine -> "out"
                else -> "in"
            },
            status = "", statusCode = 0, errorText = "", transport = "IMESSAGE", text = id, subject = "", replyToId = replyTo,
            reactions = emptyList(), localState = LocalState.SYNCED, media = emptyList(), updatedAt = 0,
        )

    @Test
    fun aReplyToAReplyIsInTheThreadOfTheFirstMessage() {
        val rows = listOf(row("a"), row("b", "a", mine = true), row("c"), row("d", "b"), row("e", "d", mine = true))
        val threads = ReplyThreads.threads(rows)
        assertEquals(mapOf("b" to "a", "d" to "a", "e" to "a"), threads.rootOf)
        assertEquals(listOf("b", "d", "e"), threads.replies["a"])
        assertEquals(3, threads.count("a"))
        assertEquals(0, threads.count("c"))
        assertEquals(0, threads.count("b"))
        assertEquals("a", threads.threadOf("a"))
        assertEquals("a", threads.threadOf("e"))
        assertNull(threads.threadOf("c"))
        assertEquals(listOf("a", "b", "d", "e"), ReplyThreads.members("a", rows, threads).map { it.id })
    }

    @Test
    fun aChainIsFollowedThroughMessagesReadFromTheArchive() {
        // The first two are not shown: read for the quote (and the one above it, for the root).
        val older = mapOf("x" to row("x"), "y" to row("y", "x"))
        val rows = listOf(row("z", "y", mine = true), row("w", "z"))
        val threads = ReplyThreads.threads(rows, older)
        assertEquals(mapOf("z" to "x", "w" to "x"), threads.rootOf)
        assertEquals(2, threads.count("x"))
        // The thread view has the root from the archive, and the replies shown (not the one between, which is not shown).
        assertEquals(listOf("x", "z", "w"), ReplyThreads.members("x", rows, threads, older).map { it.id })
    }

    @Test
    fun aMissingMessageIsTheRootOfWhatAnswersIt() {
        val rows = listOf(row("b", "gone"), row("c", "b"))
        val threads = ReplyThreads.threads(rows)
        assertEquals(mapOf("b" to "gone", "c" to "gone"), threads.rootOf)
        assertEquals(2, threads.count("gone"))
        assertEquals(listOf("b", "c"), ReplyThreads.members("gone", rows, threads).map { it.id })
    }

    @Test
    fun aCycleHasItsEarliestMessageAsTheRootForAllInIt() {
        val rows = listOf(row("p", "r"), row("q", "p"), row("r", "q"), row("s", "r"))
        val threads = ReplyThreads.threads(rows)
        assertEquals(mapOf("p" to "p", "q" to "p", "r" to "p", "s" to "p"), threads.rootOf)
        assertEquals("the root does not count as its own reply", listOf("q", "r", "s"), threads.replies["p"])
        // One that replies to itself is no reply at all.
        assertTrue(ReplyThreads.threads(listOf(row("self", "self"))).rootOf.isEmpty())
    }

    @Test
    fun aVeryLongChainStopsAtItsLimit() {
        val rows = (0..200).map { i -> row("m$i", if (i == 0) "" else "m${i - 1}") }
        val roots = ReplyThreads.roots(rows)
        assertEquals("m0", roots["m1"])
        assertTrue(roots.getValue("m200") != "m200")
    }

    @Test
    fun aReplyRightUnderItsMessageIsJoinedByALineAndHasNoCopy() {
        val rows = listOf(row("a"), row("b", "a", mine = true))
        assertEquals(ReplyThreads.Link.ADJACENT, ReplyThreads.link(rows, 1))
        // With the time between them it is not right under it.
        assertEquals(ReplyThreads.Link.NEAR, ReplyThreads.link(rows, 1) { it == 1 })
        assertEquals(ReplyThreads.Link.NONE, ReplyThreads.link(rows, 0))
    }

    @Test
    fun aReplyCloseUnderItsMessageHasACopyAndALineAndOneFurtherAwayTheCopyOnly() {
        val near = listOf(row("a")) + (1..ReplyThreads.CONNECT_WITHIN - 1).map { row("n$it", mine = it % 2 == 0) } + row("r", "a", mine = true)
        assertEquals(ReplyThreads.Link.NEAR, ReplyThreads.link(near, near.lastIndex))
        val far = listOf(row("a")) + (1..ReplyThreads.CONNECT_WITHIN).map { row("f$it") } + row("r", "a", mine = true)
        assertEquals(ReplyThreads.Link.FAR, ReplyThreads.link(far, far.lastIndex))
        // Not shown at all: the copy only.
        assertEquals(ReplyThreads.Link.FAR, ReplyThreads.link(listOf(row("r", "elsewhere")), 0))
        // A notice is never joined to.
        assertEquals(ReplyThreads.Link.FAR, ReplyThreads.link(listOf(row("n", system = true), row("r", "n")), 1))
    }

    @Test
    fun marksSayWhatIsDrawn() {
        val rows = listOf(row("a"), row("b", "a", mine = true), row("c"), row("d", "a"))
        val marks = ReplyThreads.marks(rows, ReplyThreads.threads(rows))
        assertEquals(ReplyThreads.Mark(root = "a", replyCount = 2), marks["a"])
        assertEquals(ReplyThreads.Mark(root = "a", fadedQuote = false, connectTo = "a"), marks["b"])
        assertEquals(ReplyThreads.Mark(root = "a", fadedQuote = true, connectTo = "a"), marks["d"])
        assertNull("not in a thread", marks["c"])
        // In the thread view: no copies and no counts, each joined to the one before it.
        val members = listOf(rows[0], rows[1], rows[3])
        assertEquals(
            mapOf("a" to ReplyThreads.Mark("a"), "b" to ReplyThreads.Mark("a", connectTo = "a"), "d" to ReplyThreads.Mark("a", connectTo = "b")),
            ReplyThreads.panelMarks(members, "a"),
        )
    }

    @Test
    fun aReplyToAReplyGoesToTheRootWhenItIsAtHand() {
        val a = row("a")
        val b = row("b", "a", mine = true)
        val c = row("c")
        val rows = listOf(a, b, c)
        val threads = ReplyThreads.threads(rows)
        val byId = rows.associateBy { it.id }
        assertSame(a, ReplyThreads.replyTarget(b, threads, byId::get))
        assertSame(a, ReplyThreads.replyTarget(a, threads, byId::get))
        assertSame("in no thread: itself", c, ReplyThreads.replyTarget(c, threads, byId::get))
        assertSame("its root not at hand: itself", b, ReplyThreads.replyTarget(b, threads) { null })
    }

    @Test
    fun theTranscriptCarriesTheMarksOnlyInIphoneStyleChats() {
        val rows = listOf(row("a"), row("b", "a", mine = true), row("c", "a"))
        val threads = ReplyThreads.threads(rows)
        fun build(phone: Boolean) = ThreadItems.build(
            rows, isGroup = false, quotes = emptyMap(), header = null, highlightId = null,
            dayStart = dayStart, dayLabel = { "day" }, phone = phone, threads = threads,
        ).filterIsInstance<ThreadItem.Message>().associate { it.row.id to it.thread }
        val phone = build(true)
        assertEquals(2, phone["a"]?.replyCount)
        assertEquals("a", phone["b"]?.connectTo)
        assertEquals(true, phone["c"]?.fadedQuote)
        assertTrue("other chats: as before", build(false).values.all { it == null })
    }
}
