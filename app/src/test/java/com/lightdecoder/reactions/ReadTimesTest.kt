package com.lightdecoder.reactions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.chat.ChatService
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.RelayTestEnv
import com.lightdecoder.ui.chat.ReadInfo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** When the user's messages were read: noted as the link sees it happen. */
@RunWith(AndroidJUnit4::class)
class ReadTimesTest {

    private lateinit var env: RelayTestEnv
    private val messages = ChatService.MESSAGES

    @Before
    fun setUp() {
        ReadTimes.resetForTesting()
        env = RelayTestEnv()
    }

    @After
    fun tearDown() {
        env.tearDown()
        ReadTimes.resetForTesting()
    }

    private fun mine(id: String, status: String, old: Boolean = false) = MessageDto(
        id = id, conversationId = "c1", senderId = "me", fromMe = true, timestampMs = 1_000,
        direction = "out", status = status, text = "hi", isOld = old,
    )

    private fun time(id: String) = ReadTimes.get(env.context).timeFor(messages, id)

    @Test
    fun aMessageThatTurnsToReadIsTimedOnceAndKeepsThatTime() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        fake.emitMessage(mine("m1", "OUTGOING_DELIVERED"))
        manager.awaitIdleForTesting()
        assertNull("delivered is not read", time("m1"))

        val before = System.currentTimeMillis()
        fake.emitMessage(mine("m1", "OUTGOING_DISPLAYED"))
        manager.awaitIdleForTesting()
        val read = time("m1")!!
        assertEquals(ReactionTime.Kind.SEEN, read.kind)
        assertTrue(read.atMs >= before && read.atMs <= System.currentTimeMillis())

        // The same status again later does not move the time.
        Thread.sleep(15)
        fake.emitMessage(mine("m1", "OUTGOING_DISPLAYED"))
        manager.awaitIdleForTesting()
        assertEquals(read, time("m1"))
    }

    @Test
    fun oneAlreadyReadWhenFirstLoadedHasNoTimeAndOneCaughtUpIsAtOrBefore() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        fake.emitMessage(mine("m1", "OUTGOING_DISPLAYED"))
        manager.awaitIdleForTesting()
        assertNull("it may have been read long before", time("m1"))
        assertFalse("and nothing was created for it", env.context.getDatabasePath("read_times.db").exists())

        fake.emitMessage(mine("m2", "OUTGOING_DELIVERED"))
        manager.awaitIdleForTesting()
        fake.emitMessage(mine("m2", "OUTGOING_DISPLAYED", old = true))
        manager.awaitIdleForTesting()
        assertEquals(ReactionTime.Kind.BY, time("m2")!!.kind)
    }

    @Test
    fun deletingTheArchiveDeletesTheTimes() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        fake.emitMessage(mine("m1", "OUTGOING_DELIVERED"))
        manager.awaitIdleForTesting()
        fake.emitMessage(mine("m1", "OUTGOING_DISPLAYED"))
        manager.awaitIdleForTesting()
        assertTrue(time("m1") != null)
        runBlocking { manager.unpair(deleteArchive = true) }
        assertNull(time("m1"))
    }

    @Test
    fun theLineSaysWhenOrThatItIsNotKnown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val now = java.util.Calendar.getInstance().apply { set(2026, java.util.Calendar.NOVEMBER, 17, 16, 30) }.timeInMillis
        val at = now - 60 * 60 * 1000
        assertTrue(ReadInfo.text(context, ReactionTime(at, ReactionTime.Kind.SEEN), now).startsWith("Read Today, "))
        assertTrue(ReadInfo.text(context, ReactionTime(at, ReactionTime.Kind.BY), now).startsWith("Read at or before Today, "))
        assertTrue(ReadInfo.text(context, null, now).startsWith("Read (it was already read"))
    }
}
