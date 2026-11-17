package com.lightdecoder.reactions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.chat.ChatService
import com.lightdecoder.relay.ReactionDto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** When reactions were made: what is noted, and what is not. */
@RunWith(AndroidJUnit4::class)
class ReactionTimesTest {

    private lateinit var times: ReactionTimes
    private val messages = ChatService.MESSAGES
    private val heart = ReactionKey("❤️", "p2")

    @Before
    fun setUp() {
        ReactionTimes.resetForTesting()
        times = ReactionTimes.get(ApplicationProvider.getApplicationContext<Context>())
        times.clear(ChatService.MESSAGES)
        times.clear(ChatService.WHATSAPP)
    }

    @After
    fun tearDown() = ReactionTimes.resetForTesting()

    @Test
    fun aMessageSeenForTheFirstTimeBringsNoTimes() {
        assertNull(ReactionTimes.diff(null, listOf(ReactionDto("❤️", listOf("p2")))))
        times.note(messages, "m1", null, listOf(ReactionDto("❤️", listOf("p2"))), 5_000, live = true)
        assertEquals(emptyMap<ReactionKey, ReactionTime>(), times.timesFor(messages, "m1"))
    }

    @Test
    fun aReactionThatAppearsIsTimedAndOneThatGoesIsForgotten() {
        times.note(messages, "m1", emptyList(), listOf(ReactionDto("❤️", listOf("p2"))), 5_000, live = true)
        assertEquals(ReactionTime(5_000, ReactionTime.Kind.SEEN), times.timesFor(messages, "m1")[heart])

        // The same reactions again (a status update): the time stays.
        times.note(messages, "m1", listOf(ReactionDto("❤️", listOf("p2"))), listOf(ReactionDto("❤️", listOf("p2"))), 9_000, live = true)
        assertEquals(5_000L, times.timesFor(messages, "m1")[heart]!!.atMs)

        // A second person adds the same emoji: only theirs is new.
        times.note(
            messages, "m1", listOf(ReactionDto("❤️", listOf("p2"))), listOf(ReactionDto("❤️", listOf("p2", "p3"))), 7_000, live = true,
        )
        assertEquals(5_000L, times.timesFor(messages, "m1")[heart]!!.atMs)
        assertEquals(7_000L, times.timesFor(messages, "m1")[ReactionKey("❤️", "p3")]!!.atMs)

        // p2 switches to 😂: the heart goes, the laugh is timed.
        times.note(
            messages, "m1", listOf(ReactionDto("❤️", listOf("p2", "p3"))),
            listOf(ReactionDto("❤️", listOf("p3")), ReactionDto("😂", listOf("p2"))), 8_000, live = true,
        )
        val now = times.timesFor(messages, "m1")
        assertNull(now[heart])
        assertEquals(8_000L, now[ReactionKey("😂", "p2")]!!.atMs)
    }

    @Test
    fun oneThatArrivesAsTheLinkCatchesUpIsAtOrBefore() {
        times.note(messages, "m1", emptyList(), listOf(ReactionDto("❤️", listOf("p2"))), 5_000, live = false)
        assertEquals(ReactionTime.Kind.BY, times.timesFor(messages, "m1")[heart]!!.kind)
    }

    @Test
    fun clearingOrReadingBeforeAnythingWasTimedCreatesNoDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ReactionTimes.resetForTesting()
        context.deleteDatabase("reaction_times.db")
        val fresh = ReactionTimes.get(context)
        fresh.clear(messages)
        assertTrue(fresh.timesFor(messages, "m1").isEmpty())
        assertTrue("nothing to clear, nothing created", !context.getDatabasePath("reaction_times.db").exists())
        // The first reaction creates it, and it then reads and clears as before.
        fresh.note(messages, "m1", emptyList(), listOf(ReactionDto("❤️", listOf("p2"))), 5_000, live = true)
        assertEquals(5_000L, fresh.timesFor(messages, "m1")[heart]!!.atMs)
        fresh.clear(messages)
        assertTrue(fresh.timesFor(messages, "m1").isEmpty())
    }

    @Test
    fun eachServiceIsClearedOnItsOwn() {
        times.note(messages, "m1", emptyList(), listOf(ReactionDto("❤️", listOf("p2"))), 5_000, live = true)
        times.note(ChatService.WHATSAPP, "m1", emptyList(), listOf(ReactionDto("❤️", listOf("p2"))), 6_000, live = true)
        times.clear(messages)
        assertTrue(times.timesFor(messages, "m1").isEmpty())
        assertEquals(6_000L, times.timesFor(ChatService.WHATSAPP, "m1")[heart]!!.atMs)
    }
}
