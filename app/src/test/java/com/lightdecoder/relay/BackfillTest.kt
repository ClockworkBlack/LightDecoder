package com.lightdecoder.relay

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.relay.RelayManagerTest.Companion.CONV
import com.lightdecoder.relay.RelayManagerTest.Companion.incoming
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue

/** History import and "load older": paging through sync_state, stopping, progress. */
@RunWith(AndroidJUnit4::class)
class BackfillTest {

    private lateinit var env: RelayTestEnv

    @Before
    fun setUp() {
        env = RelayTestEnv()
    }

    @After
    fun tearDown() {
        env.tearDown()
    }

    @Test
    fun recentImportPagesBackUntilThePhoneGivesNoCursor() {
        val (manager, fake) = env.pairedManager()
        fake.conversations += ConversationDto(id = CONV, name = "Sam", lastMessageTs = 100_000)
        val cursor1 = Cursor("m-80", 80_000)
        fake.pages[CONV] = ConcurrentLinkedQueue(
            listOf(
                page(80..99, cursor1),
                page(70..79, null), // the start of the conversation
            )
        )
        fake.goOnline()

        manager.startBackfill(BackfillDepth.RECENT)
        env.eventually(manager, what = "the import") { RelayPrefs.backfillDoneAt(env.context) > 0 }

        assertEquals(2, fake.fetches.size)
        assertEquals(Triple(CONV, 50, null as Cursor?), fake.fetches[0])
        assertEquals("the second page starts at the first one's cursor", cursor1, fake.fetches[1].third)
        assertEquals("only what is still missing is asked for", 30, fake.fetches[1].second)
        val sync = env.repo.syncState(CONV)!!
        assertTrue(sync.historyComplete)
        assertTrue(sync.lastBackfillAt > 0)
        assertEquals(30, env.repo.messages(CONV, limit = 500).size)

        val progress = manager.backfill.value
        assertFalse(progress.running)
        assertEquals(1, progress.conversationsDone)
        assertEquals(1, progress.conversationsTotal)
        assertEquals(30, progress.messages)
        assertEquals("", progress.error)

        // A complete history is not fetched again.
        manager.startBackfill(BackfillDepth.DEEP)
        env.eventually(manager, what = "the second run") { !manager.backfill.value.running && fake.calls.count { it == "listConversations" } >= 3 }
        Thread.sleep(50)
        assertEquals(2, fake.fetches.size)
    }

    @Test
    fun recentImportStopsAtItsMessageCount() {
        val (manager, fake) = env.pairedManager()
        fake.conversations += ConversationDto(id = CONV, name = "Sam", lastMessageTs = 100_000)
        fake.pages[CONV] = ConcurrentLinkedQueue(listOf(page(50..99, Cursor("m-50", 50_000)), page(0..49, Cursor("m-0", 0))))
        fake.goOnline()

        manager.startBackfill(BackfillDepth.RECENT)
        env.eventually(manager, what = "the import") { RelayPrefs.backfillDoneAt(env.context) > 0 }
        assertEquals(1, fake.fetches.size)
        val sync = env.repo.syncState(CONV)!!
        assertFalse(sync.historyComplete)
        assertEquals("m-50", sync.cursorId)
    }

    @Test
    fun theFirstConnectionImportsHistoryWhenConfigured() {
        val (manager, fake) = env.pairedManager()
        RelayPrefs.setBackfillDepth(env.context, BackfillDepth.RECENT)
        fake.conversations += ConversationDto(id = CONV, name = "Sam", lastMessageTs = 100_000)
        fake.pages[CONV] = ConcurrentLinkedQueue(listOf(page(90..99, null)))
        fake.goOnline()
        env.eventually(manager, what = "the automatic import") { RelayPrefs.backfillDoneAt(env.context) > 0 }
        assertEquals(10, env.repo.messages(CONV).size)
    }

    @Test
    fun anImportThatCannotReachThePhoneReportsWhy() {
        val (manager, _) = env.pairedManager(RelayTestEnv.FAST.copy(backfillWaitOnlineMs = 50))
        manager.startBackfill(BackfillDepth.RECENT)
        env.eventually(manager, what = "the error") { manager.backfill.value.error.isNotEmpty() }
        assertEquals(RelayException.NOT_CONNECTED, manager.backfill.value.error)
        assertFalse(manager.backfill.value.running)
        assertEquals(0L, RelayPrefs.backfillDoneAt(env.context))
    }

    @Test
    fun loadOlderStartsBelowTheOldestStoredMessageAndKeepsTheCursor() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        fake.emitMessage(incoming("m-90", "live", ts = 90_000))
        fake.emitMessage(incoming("m-95", "live", ts = 95_000))
        manager.awaitIdleForTesting()
        fake.pages[CONV] = ConcurrentLinkedQueue(listOf(page(60..89, Cursor("m-60", 60_000)), page(50..59, null)))

        assertEquals(30, runBlocking { manager.loadOlder(CONV, 30) })
        assertEquals(Cursor("m-90", 90_000), fake.fetches[0].third)
        assertEquals(Cursor("m-60", 60_000), env.repo.syncState(CONV)!!.cursor)

        assertEquals(10, runBlocking { manager.loadOlder(CONV, 30) })
        assertEquals(Cursor("m-60", 60_000), fake.fetches[1].third)
        assertTrue(env.repo.syncState(CONV)!!.historyComplete)

        assertEquals("done", 0, runBlocking { manager.loadOlder(CONV, 30) })
        assertEquals(2, fake.fetches.size)
        assertEquals(42, env.repo.messages(CONV, limit = 500).size)
    }

    @Test
    fun loadOlderWithNothingStoredStartsAtTheNewest() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        fake.pages[CONV] = ConcurrentLinkedQueue(listOf(page(0..9, null)))
        assertEquals(10, runBlocking { manager.loadOlder(CONV) })
        assertNull(fake.fetches.single().third)
    }

    /** Messages with ids m-<n> at n seconds, oldest first, as the bridge returns them. */
    private fun page(range: IntRange, cursor: Cursor?) = MessagePage(
        messages = range.map { incoming("m-$it", "message $it", ts = it * 1_000L).copy(isOld = true) },
        cursor = cursor,
    )
}
