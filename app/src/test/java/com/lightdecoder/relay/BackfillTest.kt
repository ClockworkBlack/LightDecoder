package com.lightdecoder.relay

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.lightdecoder.archive.SyncStateRow
import com.lightdecoder.relay.RelayManagerTest.Companion.CONV
import com.lightdecoder.relay.RelayManagerTest.Companion.incoming
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        // Below a cursor always a full page: a request for a few would come back
        // empty (the bridge drops the cursor's own message) and look like the start.
        assertEquals("a full page below a cursor", 50, fake.fetches[1].second)
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
    fun loadOlderWithoutAnImportStartsAtTheNewestAndKeepsTheCursor() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        fake.emitMessage(incoming("m-90", "live", ts = 90_000))
        fake.emitMessage(incoming("m-95", "live", ts = 95_000))
        manager.awaitIdleForTesting()
        fake.pages[CONV] = ConcurrentLinkedQueue(listOf(page(60..95, Cursor("m-60", 60_000)), page(50..59, null)))

        assertEquals(36, runBlocking { manager.loadOlder(CONV, 30) })
        assertNull("no import yet: the newest page", fake.fetches[0].third)
        assertEquals(Cursor("m-60", 60_000), env.repo.syncState(CONV)!!.cursor)

        assertEquals(10, runBlocking { manager.loadOlder(CONV, 30) })
        assertEquals(Cursor("m-60", 60_000), fake.fetches[1].third)
        assertTrue(env.repo.syncState(CONV)!!.historyComplete)

        assertEquals("done", 0, runBlocking { manager.loadOlder(CONV, 30) })
        assertEquals(2, fake.fetches.size)
        assertEquals(46, env.repo.messages(CONV, limit = 500).size)
    }

    @Test
    fun anIsolatedOldMessageDoesNotMoveLoadOlderPastTheGapOrNotify() {
        val (manager, fake) = env.pairedManager()
        RelayPrefs.setNotifyMessages(env.context, true)
        MessageNotifier.cancelAll(env.context)
        fake.goOnline()
        val now = System.currentTimeMillis()
        // The import got as far back as m-50; the newest message is from now.
        fake.emitMessage(incoming("m-new", "latest", ts = now))
        manager.awaitIdleForTesting()
        env.repo.setSyncState(SyncStateRow(CONV, "m-50", 50_000, historyComplete = false, lastBackfillAt = 1))
        // Someone reacts to a message from long ago: it is stored on its own.
        fake.emitMessage(incoming("m-10", "ancient", ts = 10_000))
        manager.awaitIdleForTesting()
        assertNotNull(env.repo.message("m-10"))
        val notification = shadowOf(env.context.getSystemService(NotificationManager::class.java)).allNotifications
            .single { it.channelId == RelayNotifications.CHANNEL_MESSAGES }
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)!!
        assertEquals("only the new message is notified", listOf("latest"), style.messages.map { it.text.toString() })

        fake.pages[CONV] = ConcurrentLinkedQueue(listOf(page(0..49, Cursor("m-0", 0))))
        runBlocking { manager.loadOlder(CONV, 50) }
        assertEquals("pages on from the import, not from the isolated message", Cursor("m-50", 50_000), fake.fetches.single().third)
    }

    @Test
    fun aSmallRequestBelowACursorNeverMarksTheHistoryComplete() {
        // The bridge drops the cursor's own message: an answer to "1 before C"
        // can be empty. Asking a full page avoids storing a false "complete".
        val (manager, fake) = env.pairedManager()
        fake.conversations += ConversationDto(id = CONV, name = "Sam", lastMessageTs = 100_000)
        fake.pages[CONV] = ConcurrentLinkedQueue(listOf(page(52..99, Cursor("m-52", 52_000)), page(10..51, Cursor("m-10", 10_000))))
        fake.goOnline()
        manager.startBackfill(BackfillDepth.RECENT)
        env.eventually(manager, what = "the import") { RelayPrefs.backfillDoneAt(env.context) > 0 }
        assertEquals(listOf(50, 50), fake.fetches.map { it.second })
        assertFalse(env.repo.syncState(CONV)!!.historyComplete)
    }

    @Test
    fun aConversationThePhoneKeepsFailingOnIsSkipped() {
        val (manager, fake) = env.pairedManager()
        fake.conversations += ConversationDto(id = "bad", name = "Bad", lastMessageTs = 300_000)
        fake.conversations += ConversationDto(id = CONV, name = "Sam", lastMessageTs = 100_000)
        fake.failFetches["bad"] = RelayException(RelayException.NOT_FOUND, "conversation not found")
        fake.pages[CONV] = ConcurrentLinkedQueue(listOf(page(90..99, null)))
        fake.goOnline()

        manager.startBackfill(BackfillDepth.RECENT)
        env.eventually(manager, what = "the import to finish") {
            RelayPrefs.backfillDoneAt(env.context) > 0 && RelayLog.read(env.context).any { it.contains("History import finished") }
        }
        assertEquals("the conversation after the bad one is imported", 10, env.repo.messages(CONV).size)
        assertEquals("", manager.backfill.value.error)
        assertTrue(RelayLog.read(env.context).any { it.contains("1 skipped") })
    }

    @Test
    fun anImportStopsWhenTheLinkIsDown() {
        val (manager, fake) = env.pairedManager()
        fake.conversations += ConversationDto(id = "a", name = "A", lastMessageTs = 300_000)
        fake.conversations += ConversationDto(id = CONV, name = "Sam", lastMessageTs = 100_000)
        fake.failFetches["a"] = RelayException(RelayException.NOT_CONNECTED, "")
        fake.goOnline()
        manager.startBackfill(BackfillDepth.RECENT)
        env.eventually(manager, what = "the error") { manager.backfill.value.error.isNotEmpty() }
        assertEquals(RelayException.NOT_CONNECTED, manager.backfill.value.error)
        assertEquals(0L, RelayPrefs.backfillDoneAt(env.context))
    }

    @Test
    fun aCancelledImportWritesNothingAfterItsPageArrivesAndLeavesTheNextRunAlone() {
        val (manager, fake) = env.pairedManager()
        fake.conversations += ConversationDto(id = CONV, name = "Sam", lastMessageTs = 100_000)
        fake.pages[CONV] = ConcurrentLinkedQueue(listOf(page(50..99, Cursor("m-50", 50_000))))
        val firstWaiting = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondWaiting = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val fetchCalls = AtomicInteger()
        fake.beforeFetch = {
            when (fetchCalls.incrementAndGet()) {
                1 -> { firstWaiting.countDown(); releaseFirst.await() }
                2 -> { secondWaiting.countDown(); releaseSecond.await() }
            }
        }
        fake.goOnline()

        manager.startBackfill(BackfillDepth.RECENT)
        assertTrue(firstWaiting.await(5, TimeUnit.SECONDS))
        manager.cancelBackfill() // the page request is still out
        assertFalse(manager.backfill.value.running)
        manager.startBackfill(BackfillDepth.RECENT)
        env.eventually(manager, what = "the new run") { manager.backfill.value.running }

        // The old request comes back while the new run is under way.
        releaseFirst.countDown()
        assertTrue("the new run pages only after the old one let go", secondWaiting.await(5, TimeUnit.SECONDS))
        manager.awaitIdleForTesting()
        assertNull("the cancelled run stored nothing", env.repo.message("m-99"))
        assertNull(env.repo.syncState(CONV))
        assertTrue("the new run's progress is untouched", manager.backfill.value.running)

        releaseSecond.countDown()
        env.eventually(manager, what = "the new run to finish") { RelayPrefs.backfillDoneAt(env.context) > 0 }
        assertFalse(manager.backfill.value.running)
    }

    @Test
    fun theConnectionCatchesUpOnWhatArrivedWhileTheLinkWasDown() {
        val (manager, fake) = env.pairedManager()
        // The archive has up to m-70; the phone has up to m-99 by now.
        fake.goOnline()
        manager.awaitIdleForTesting()
        env.repo.upsertMessages(page(60..70, null).messages)
        env.repo.setSyncState(SyncStateRow(CONV, "m-60", 60_000, historyComplete = true, lastBackfillAt = 1))
        fake.emitState("disconnected")
        fake.conversations += ConversationDto(id = CONV, name = "Sam", lastMessageTs = 99_000, latestMessageId = "m-99")
        fake.pages[CONV] = ConcurrentLinkedQueue(listOf(page(80..99, Cursor("m-80", 80_000)), page(65..79, Cursor("m-65", 65_000))))

        fake.goOnline()
        env.eventually(manager, what = "the gap to be filled") { RelayLog.read(env.context).any { it.contains("Caught up") } }
        assertEquals(listOf(null, Cursor("m-80", 80_000)), fake.fetches.map { it.third })
        assertEquals(40, env.repo.messages(CONV, limit = 500).size)
        val sync = env.repo.syncState(CONV)!!
        assertEquals("the import's cursor is left alone", "m-60", sync.cursorId)
        assertTrue(sync.historyComplete)
    }

    @Test
    fun aLiveMessageStoredBeforeTheCatchUpDoesNotSkipTheConversation() {
        val (manager, fake) = env.pairedManager()
        // The archive has m-60..m-70 from before; while the link was down the phone got up to m-99.
        env.repo.upsertMessages(page(60..70, null).messages)
        env.repo.setSyncState(SyncStateRow(CONV, "m-60", 60_000, historyComplete = true, lastBackfillAt = 1))
        fake.conversations += ConversationDto(id = CONV, name = "Sam", lastMessageTs = 100_000, latestMessageId = "m-100")
        fake.pages[CONV] = ConcurrentLinkedQueue(listOf(page(81..100, Cursor("m-81", 81_000)), page(65..80, Cursor("m-65", 65_000))))
        // Before the catch-up gets to it, m-100 arrives live and is stored: the phone's newest is "known".
        fake.beforeListReturns = {
            fake.emitMessage(incoming("m-100", "live", ts = 100_000))
            manager.awaitIdleForTesting()
        }

        fake.goOnline()
        env.eventually(manager, what = "the gap to be filled") { RelayLog.read(env.context).any { it.contains("Caught up") } }
        assertEquals(listOf(null, Cursor("m-81", 81_000)), fake.fetches.map { it.third })
        for (n in 71..99) assertNotNull("m-$n", env.repo.message("m-$n"))
    }

    @Test
    fun aLiveMessageInsideTheGapDoesNotEndTheCatchUpEarly() {
        val (manager, fake) = env.pairedManager()
        env.repo.upsertMessages(page(60..70, null).messages)
        env.repo.setSyncState(SyncStateRow(CONV, "m-60", 60_000, historyComplete = true, lastBackfillAt = 1))
        fake.conversations += ConversationDto(id = CONV, name = "Sam", lastMessageTs = 99_000, latestMessageId = "m-99")
        fake.pages[CONV] = ConcurrentLinkedQueue(listOf(page(80..99, Cursor("m-80", 80_000)), page(65..79, Cursor("m-65", 65_000))))
        // m-90 (an IsOld replay, say) is stored before the catch-up pages through it.
        fake.beforeListReturns = {
            fake.emitMessage(incoming("m-90", "replayed", ts = 90_000).copy(isOld = true))
            manager.awaitIdleForTesting()
        }

        fake.goOnline()
        env.eventually(manager, what = "the gap to be filled") { RelayLog.read(env.context).any { it.contains("Caught up") } }
        assertEquals(2, fake.fetches.size)
        for (n in 71..79) assertNotNull("m-$n", env.repo.message("m-$n"))
    }

    @Test
    fun aCatchUpWaitsForOneConversationOfARunningImportNotAllOfIt() {
        val (manager, fake) = env.pairedManager()
        // CONV's history is complete up to m-70; the import has A and B to do.
        env.repo.upsertMessages(page(60..70, null).messages)
        env.repo.setSyncState(SyncStateRow(CONV, "m-60", 60_000, historyComplete = true, lastBackfillAt = 1))
        fake.conversations += ConversationDto(id = "a", name = "A", lastMessageTs = 300_000)
        fake.conversations += ConversationDto(id = "b", name = "B", lastMessageTs = 200_000)
        fake.conversations += ConversationDto(id = CONV, name = "Sam", lastMessageTs = 70_000, latestMessageId = "m-70")
        fake.goOnline()
        env.eventually(manager, what = "the first sync") { env.repo.conversation("a")?.name == "A" }
        Thread.sleep(100)
        manager.awaitIdleForTesting()

        // The import is busy with A (the phone is slow).
        val inA = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val firstA = java.util.concurrent.atomic.AtomicBoolean(true)
        fake.beforeFetch = { id -> if (id == "a" && firstA.getAndSet(false)) { inA.countDown(); releaseA.await() } }
        manager.startBackfill(BackfillDepth.RECENT)
        assertTrue(inA.await(5, TimeUnit.SECONDS))

        // New messages in CONV: the phone asks for a resync, and its catch-up waits for the pager.
        fake.conversations.replaceAll {
            if (it.id == CONV) it.copy(lastMessageTs = 99_000, latestMessageId = "m-99") else it
        }
        fake.pages[CONV] = ConcurrentLinkedQueue(listOf(page(71..99, Cursor("m-71", 71_000)), page(60..70, null)))
        val listsBefore = fake.calls.count { it == "listConversations" }
        fake.emit("resync", org.json.JSONObject())
        env.eventually(manager, what = "the resync's list") { fake.calls.count { it == "listConversations" } > listsBefore }
        Thread.sleep(300) // the catch-up queues for the pager (it does so right after its list call)
        releaseA.countDown()

        env.eventually(manager, what = "both to finish") {
            RelayPrefs.backfillDoneAt(env.context) > 0 && RelayLog.read(env.context).any { it.contains("Caught up") }
        }
        val order = fake.fetches.map { it.first }
        assertTrue("the catch-up ran between A and B: $order", order.indexOf(CONV) in 1 until order.indexOf("b"))
        for (n in 71..99) assertNotNull("m-$n", env.repo.message("m-$n"))
    }

    @Test
    fun loadOlderWithNothingStoredStartsAtTheNewest() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        fake.pages[CONV] = ConcurrentLinkedQueue(listOf(page(0..9, null)))
        assertEquals(10, runBlocking { manager.loadOlder(CONV) })
        assertNull(fake.fetches.single().third)
    }

    @Test
    fun aConversationWithNothingStoredHasNoGapToCatchUpOn() {
        // History import is off: connecting must not page through every conversation.
        val (manager, fake) = env.pairedManager()
        fake.conversations += ConversationDto(id = CONV, name = "Sam", lastMessageTs = 99_000, latestMessageId = "m-99")
        fake.pages[CONV] = ConcurrentLinkedQueue(listOf(page(80..99, Cursor("m-80", 80_000))))
        fake.goOnline()
        env.eventually(manager, what = "the inbox refresh") { env.repo.conversation(CONV)?.name == "Sam" }
        Thread.sleep(150)
        manager.awaitIdleForTesting()
        assertTrue(fake.fetches.isEmpty())
    }

    /** Messages with ids m-<n> at n seconds, oldest first, as the bridge returns them. */
    private fun page(range: IntRange, cursor: Cursor?) = MessagePage(
        messages = range.map { incoming("m-$it", "message $it", ts = it * 1_000L).copy(isOld = true) },
        cursor = cursor,
    )
}
