package com.lightdecoder.reactions

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.chat.ChatService
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.ReactionDto
import com.lightdecoder.relay.RelayTestEnv
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The Google Messages link times reactions as they arrive on messages it already has. */
@RunWith(AndroidJUnit4::class)
class ReactionTimesLinkTest {

    private lateinit var env: RelayTestEnv
    private val messages = ChatService.MESSAGES

    @Before
    fun setUp() {
        ReactionTimes.resetForTesting()
        env = RelayTestEnv()
        ReactionTimes.get(env.context).clear(messages)
    }

    @After
    fun tearDown() {
        env.tearDown()
        ReactionTimes.resetForTesting()
    }

    private fun incoming(id: String, reactions: List<ReactionDto> = emptyList(), old: Boolean = false) = MessageDto(
        id = id, conversationId = "c1", senderId = "p2", senderName = "Sam", timestampMs = 1_000,
        direction = "in", status = "INCOMING_COMPLETE", text = "hi", reactions = reactions, isOld = old,
    )

    private fun times(id: String) = ReactionTimes.get(env.context).timesFor(messages, id)

    @Test
    fun aReactionOnAKnownMessageIsTimedWhenItArrives() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        fake.emitMessage(incoming("m1"))
        manager.awaitIdleForTesting()
        val before = System.currentTimeMillis()
        fake.emitMessage(incoming("m1", listOf(ReactionDto("❤️", listOf("p2")))))
        manager.awaitIdleForTesting()
        val time = times("m1")[ReactionKey("❤️", "p2")]!!
        assertEquals(ReactionTime.Kind.SEEN, time.kind)
        assertTrue("stamped when it arrived", time.atMs >= before && time.atMs <= System.currentTimeMillis())

        // Taken off again: forgotten.
        fake.emitMessage(incoming("m1"))
        manager.awaitIdleForTesting()
        assertTrue(times("m1").isEmpty())
    }

    @Test
    fun reactionsAlreadyThereOnANewMessageHaveNoTime() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        fake.emitMessage(incoming("m2", listOf(ReactionDto("👍", listOf("p2")))))
        manager.awaitIdleForTesting()
        assertTrue(times("m2").isEmpty())
    }

    @Test
    fun oneReSentAsTheLinkCatchesUpIsAtOrBefore() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        fake.emitMessage(incoming("m1"))
        manager.awaitIdleForTesting()
        fake.emitMessage(incoming("m1", listOf(ReactionDto("😂", listOf("p2"))), old = true))
        manager.awaitIdleForTesting()
        assertEquals(ReactionTime.Kind.BY, times("m1")[ReactionKey("😂", "p2")]!!.kind)
    }

    @Test
    fun deletingTheArchiveDeletesTheTimes() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        fake.emitMessage(incoming("m1"))
        manager.awaitIdleForTesting()
        fake.emitMessage(incoming("m1", listOf(ReactionDto("❤️", listOf("p2")))))
        manager.awaitIdleForTesting()
        assertEquals(1, times("m1").size)
        runBlocking { manager.unpair(deleteArchive = true) }
        assertNull(times("m1")[ReactionKey("❤️", "p2")])
    }
}
