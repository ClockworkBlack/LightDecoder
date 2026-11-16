package com.lightdecoder.relay

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.os.PowerManager
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.R
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.archive.OutboxStatus
import com.lightdecoder.relay.RelayManagerTest.Companion.CONV
import com.lightdecoder.relay.RelayManagerTest.Companion.incoming
import com.lightdecoder.relay.RelayManagerTest.Companion.outgoing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The runtime side of the "known limitations" pass: contacts with their
 * source, the local deleted folder, Not spam, the battery exemption, the
 * unpair verdict, stored settings, token refreshes, sends looked up on the
 * phone, the uncapped and resumable catch-up, the owner gate for live events,
 * the Android 8 notification check, and Disconnect during an unpair.
 */
@RunWith(AndroidJUnit4::class)
class RelayLimitsTest {

    private lateinit var env: RelayTestEnv

    @Before
    fun setUp() {
        env = RelayTestEnv()
    }

    @After
    fun tearDown() {
        env.tearDown()
    }

    // --- contacts ---------------------------------------------------------------------

    @Test
    fun contactsSayWhetherTheyCameFromThePhone() {
        val (manager, fake) = env.pairedManager()
        fake.contacts += ContactDto("p1", name = "Alice", number = "+15550001")

        // Offline with nothing stored: the failure is the answer.
        try {
            runBlocking { manager.contacts(refresh = true) }
            fail("nothing to fall back on")
        } catch (e: RelayException) {
            assertEquals(RelayException.NOT_CONNECTED, e.code)
        }

        fake.goOnline()
        manager.awaitIdleForTesting()
        val fresh = runBlocking { manager.contacts(refresh = true) }
        assertTrue(fresh.fromPhone)
        assertEquals(listOf("Alice"), fresh.contacts.map { it.displayName })

        // The phone cannot be reached: the stored list, saying so.
        fake.emitState("disconnected")
        fake.connected = false
        manager.awaitIdleForTesting()
        val stored = runBlocking { manager.contacts(refresh = true) }
        assertFalse(stored.fromPhone)
        assertEquals(listOf("Alice"), stored.contacts.map { it.displayName })
        // Not refreshing is not a failure; the old call still works.
        assertTrue(runBlocking { manager.contacts(refresh = false) }.fromPhone)
        assertEquals(listOf("Alice"), runBlocking { manager.listContacts(refresh = true) }.map { it.displayName })
    }

    // --- deleted folder ------------------------------------------------------------------

    @Test
    fun aConversationThePhoneDeletesCanBeRestoredOrPurgedHere() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        fake.emit("conversation", ConversationDto(id = CONV, name = "Sam", folder = "inbox").toJson())
        fake.emitMessage(incoming("m1", "kept after the phone deleted it"))
        fake.emit("conversation", ConversationDto(id = CONV, name = "Sam", folder = "deleted").toJson())
        manager.awaitIdleForTesting()

        assertEquals(Folder.DELETED, env.repo.conversation(CONV)!!.folderEnum)
        assertNotNull(env.repo.message("m1"))
        assertEquals(listOf(CONV), env.repo.conversations(folder = Folder.DELETED).map { it.id })
        assertTrue(env.repo.conversations(folder = Folder.INBOX).isEmpty())

        // Restored here only: the phone is not asked.
        val callsBefore = fake.calls.size
        assertTrue(runBlocking { manager.restoreDeletedLocally(CONV) })
        assertEquals(Folder.INBOX, env.repo.conversation(CONV)!!.folderEnum)
        assertEquals(callsBefore, fake.calls.size)
        assertFalse("only from the deleted folder", runBlocking { manager.restoreDeletedLocally(CONV) })

        // Deleted again by the phone, then purged for good.
        fake.emit("conversation", ConversationDto(id = CONV, name = "Sam", folder = "deleted").toJson())
        fake.emit("conversation", ConversationDto(id = "c2", name = "Kim", folder = "deleted").toJson())
        fake.emitMessage(incoming("m2", "other").copy(conversationId = "c2"))
        fake.emit("conversation", ConversationDto(id = "c2", name = "Kim", folder = "deleted").toJson())
        manager.awaitIdleForTesting()
        assertEquals(1, runBlocking { manager.purgeDeleted(CONV) })
        assertNull(env.repo.conversation(CONV))
        assertNull(env.repo.message("m1"))
        assertEquals(1, runBlocking { manager.purgeDeleted(null) })
        assertNull(env.repo.conversation("c2"))
        assertFalse("nothing deleted on the phone", fake.calls.contains("deleteConversation"))

        // The local folder is never asked of the phone.
        val lists = fake.calls.count { it == "listConversations" }
        assertEquals(0, runBlocking { manager.refreshConversations(Folder.DELETED) })
        assertEquals(lists, fake.calls.count { it == "listConversations" })
        assertNull(Folder.fromWire("deleted"))
        assertEquals(Folder.DELETED, Folder.fromStored("deleted"))
        assertLogIsContentFree()
    }

    @Test
    fun aDeleteTheUserAsksForStillDeletesOnThePhoneAndHere() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        fake.emitMessage(incoming("m1", "bye"))
        manager.awaitIdleForTesting()
        runBlocking { manager.deleteConversation(CONV) }
        assertTrue(fake.calls.contains("deleteConversation"))
        assertNull(env.repo.conversation(CONV))
    }

    // --- not spam ---------------------------------------------------------------------------

    @Test
    fun notSpamMovesTheConversationOutOfSpamOnThePhoneAndHere() {
        val (manager, fake) = env.pairedManager()
        fake.emit("conversation", ConversationDto(id = CONV, name = "Sam", folder = "spam").toJson())
        manager.awaitIdleForTesting()

        // Offline: refused, nothing changes here.
        try {
            runBlocking { manager.markNotSpam(CONV) }
            fail("needs the phone")
        } catch (e: RelayException) {
            assertEquals(RelayException.NOT_CONNECTED, e.code)
        }
        assertEquals(Folder.SPAM, env.repo.conversation(CONV)!!.folderEnum)

        fake.goOnline()
        manager.awaitIdleForTesting()
        runBlocking { manager.markNotSpam(CONV) }
        assertTrue(fake.calls.contains("setConversationStatus:not_spam"))
        assertEquals(Folder.INBOX, env.repo.conversation(CONV)!!.folderEnum)
    }

    // --- battery ------------------------------------------------------------------------------

    @Test
    fun theBatteryExemptionIsAskedForAndReported() {
        val context = env.context
        val ask = RelayIntents.batteryExemption(context)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, ask.action)
        assertEquals("package:${context.packageName}", ask.dataString)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, RelayIntents.batterySettings().action)

        assertFalse(RelayNotifications.isIgnoringBatteryOptimizations(context))
        assertFalse(RelayPrefs.batteryExempt(context))
        shadowOf(context.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(context.packageName, true)
        assertTrue(RelayNotifications.isIgnoringBatteryOptimizations(context))
        assertTrue(RelayPrefs.batteryExempt(context))
        assertEquals(
            "Let it run in the background",
            context.getString(R.string.relay_battery_exempt_action),
        )
    }

    // --- unpair ---------------------------------------------------------------------------------

    @Test
    fun theUnpairVerdictComesFromTheReturnValueWithoutWaitingForTheEvent() {
        val (manager, fake) = env.pairedManager()
        // Go's "unpaired" state arrives long after Unpair returned.
        fake.unpairCallbackDelayMs = 30_000
        fake.unpairCode = RelayState.UNPAIR_REMOTE_FAILED

        val started = System.currentTimeMillis()
        runBlocking { manager.unpair(deleteArchive = false) }
        assertTrue("no wait for the event", System.currentTimeMillis() - started < 5_000)
        assertEquals(RelayState(RelayState.Phase.UNPAIRED, RelayState.UNPAIR_REMOTE_FAILED), manager.state.value)
        assertEquals(RelayState.UNPAIR_REMOTE_FAILED, env.repo.health().lastError)
        assertFalse(SessionVault.exists(env.context))
        assertTrue(RelayLog.read(env.context).none { it.contains("did not report") })
        assertEquals(RelayException.UNPAIR_REMOTE_FAILED, RelayException.fromThrowable(Exception("unpair_remote_failed: offline")).code)
    }

    @Test
    fun anUnpairThePhoneHeardAboutEndsAtOnceToo() {
        val (manager, fake) = env.pairedManager()
        fake.unpairCallbackDelayMs = 30_000
        val started = System.currentTimeMillis()
        runBlocking { manager.unpair(deleteArchive = true) }
        assertTrue(System.currentTimeMillis() - started < 5_000)
        assertEquals(RelayState(RelayState.Phase.UNPAIRED), manager.state.value)
    }

    @Test
    fun disconnectTappedDuringAnUnpairKeepsTheNotificationUntilTheRevokeEnds() {
        val (manager, fake) = env.pairedManager()
        RelayPrefs.setLiveEnabled(env.context, true)
        val service = Robolectric.buildService(RelayService::class.java).create().get()
        service.onStartCommand(null, 0, 1)
        env.eventually(manager, what = "connect") { fake.calls.contains("connect") }
        fake.goOnline()
        manager.awaitIdleForTesting()

        val inRevoke = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.duringUnpair = { inRevoke.countDown(); release.await(10, TimeUnit.SECONDS) }
        val screen = CoroutineScope(Dispatchers.IO)
        screen.launch { manager.unpair(deleteArchive = false) }
        assertTrue(inRevoke.await(5, TimeUnit.SECONDS))

        // Disconnect in the notification while the revoke runs: the service stays.
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(RelayService.disconnectIntent(env.context), 0, 2))
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertNull(shadowOf(env.context as Application).nextStoppedService)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val shown = notifications().getNotification(RelayNotifications.ID_SERVICE)
        assertEquals(
            env.context.getString(R.string.relay_service_title_unpairing),
            shown.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
        assertEquals(env.context.getString(R.string.relay_action_disconnect), shown.actions.single().title)

        // The revoke ends: the unpair stops the service.
        release.countDown()
        env.eventually(manager, what = "the unpair") { !manager.unpairing.value && manager.state.value.phase == RelayState.Phase.UNPAIRED }
        assertEquals(RelayService::class.java.name, shadowOf(env.context as Application).nextStoppedService?.component?.className)
        screen.cancel()
    }

    // --- settings ---------------------------------------------------------------------------------

    @Test
    fun theLastSettingsAreKeptSeededBeforeConnectingAndOpenTheSendGate() {
        val (manager, fake) = env.pairedManager()
        assertNull(manager.settings.value)
        fake.goOnline(withSettings = true)
        manager.awaitIdleForTesting()
        val stored = RelayPrefs.lastSettings(env.context)
        assertNotNull(stored)
        assertEquals("+15550000", JSONObject(stored!!).getJSONArray("sims").getJSONObject(0).getString("number"))
        assertFalse("never logged", RelayLog.read(env.context).any { it.contains("+15550000") })

        // A restart: known at once, and handed to the bridge before it connects.
        val (again, fake2) = env.pairedManager()
        assertEquals("+15550000", again.settings.value!!.sims.single().number)
        RelayPrefs.setLiveEnabled(env.context, true)
        again.serviceRunning = true
        val atConnect = java.util.concurrent.atomic.AtomicReference<String?>()
        fake2.duringConnect = { atConnect.set(fake2.seededSettings) }
        again.onServiceStarted()
        env.eventually(again, what = "connect") { fake2.calls.contains("connect") }
        assertEquals("seeded before connect", stored, atConnect.get())
        assertEquals(listOf<String?>(stored), fake2.settingsSeeds.toList())

        // Online without a settings event: sends go at once (no 15 s wait).
        fake2.goOnline(withSettings = false)
        val tmpId = again.sendText(CONV, "right SIM")
        env.eventually(again, what = "the send") { env.repo.outbox(tmpId)?.status == OutboxStatus.SENT }

        // Unpairing forgets them.
        runBlocking { again.unpair(deleteArchive = false) }
        assertNull(RelayPrefs.lastSettings(env.context))
        assertNull(again.settings.value)
        again.serviceRunning = false
    }

    @Test
    fun deletingTheArchiveForgetsTheStoredSettings() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline(withSettings = true)
        manager.awaitIdleForTesting()
        assertNotNull(RelayPrefs.lastSettings(env.context))
        runBlocking { manager.unpair(deleteArchive = true) }
        assertNull(RelayPrefs.lastSettings(env.context))

        RelayPrefs.setLastSettings(env.context, """{"sims":[]}""")
        assertTrue(runBlocking { manager.deleteLocalArchive() })
        assertNull(RelayPrefs.lastSettings(env.context))
    }

    // --- token refresh ------------------------------------------------------------------------------

    @Test
    fun onlyRealTokenRefreshesCountAsSessionRefreshes() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        fake.pushSession(FakeRelayBridge.SESSION) // the periodic save
        fake.emitState("disconnected") // the fake saves on disconnect, as Go does
        manager.awaitIdleForTesting()
        assertEquals(0L, env.repo.health().lastTokenRefresh)
        fake.emitTokenRefreshed()
        manager.awaitIdleForTesting()
        assertTrue(env.repo.health().lastTokenRefresh > 0)
    }

    // --- sends looked up on the phone ----------------------------------------------------------------

    @Test
    fun aPhoneNotRespondingSendIsLookedUpOnThePhoneAndItsEchoSettlesIt() {
        val (manager, fake) = env.pairedManager(RelayTestEnv.FAST.copy(unconfirmedSendCheckMs = 0))
        fake.sendAnswers += { throw RelayException(RelayException.PHONE_NOT_RESPONDING, "timed out") }
        fake.goOnline()
        val tmpId = manager.sendText(CONV, "did it go?")
        env.eventually(manager, what = "the failure and its planned lookup") {
            env.repo.outbox(tmpId)?.status == OutboxStatus.FAILED && RelayPrefs.lookupSends(env.context).containsKey(tmpId)
        }

        // The phone has it: the lookup (about a second later) finds its echo.
        fake.history[CONV] = listOf(outgoing("real-1", "did it go?", tmpId, ts = System.currentTimeMillis()))
        try {
            env.eventually(manager, timeoutMs = 8_000, what = "the lookup") { env.repo.outbox(tmpId) == null }
        } catch (e: AssertionError) {
            throw AssertionError("DEBUG fetches=${fake.fetches} log=${RelayLog.read(env.context)} outbox=${env.repo.outbox(tmpId)} lookups=${RelayPrefs.lookupSends(env.context)}")
        }
        assertNull(env.repo.message(MessageRow.tmpMessageId(tmpId)))
        assertNotNull(env.repo.message("real-1"))
        assertEquals("sent once", 1, fake.sent.size)
        assertTrue(RelayPrefs.lookupSends(env.context).isEmpty())
    }

    @Test
    fun aSendAnUnpairHeldWhileItWentOutIsPlannedForALookup() {
        val (manager, fake) = env.pairedManager()
        fake.duringSend = {
            fake.duringSend = null
            runBlocking { manager.unpair(deleteArchive = false) }
        }
        fake.goOnline()
        val tmpId = manager.sendText(CONV, "went out during the unpair")
        env.eventually(manager, what = "the send to settle and its planned lookup") {
            env.repo.message(MessageRow.tmpMessageId(tmpId))?.errorText == Outbox.UNCONFIRMED &&
                RelayPrefs.lookupSends(env.context).containsKey(tmpId)
        }
    }

    // --- catch-up --------------------------------------------------------------------------------------

    @Test
    fun theCatchUpHasNoPageCapAndResumesWhereItStopped() {
        val (manager, fake) = env.pairedManager()
        // Stored: up to m-100. The phone has up to m-1000 by now: 900 newer, 18 pages.
        env.repo.upsertMessages((1..100).map { message(it) })
        fake.history[CONV] = (1..1000).map { message(it) }
        fake.conversations += ConversationDto(id = CONV, name = "Sam", lastMessageTs = 1_000_000, latestMessageId = "m-1000")
        // The phone stops answering after 5 pages.
        val fetches = AtomicInteger()
        fake.beforeFetch = { if (fetches.incrementAndGet() == 6) fake.connected = false }

        fake.goOnline()
        env.eventually(manager, what = "the catch-up to stop") { RelayLog.read(env.context).any { it.contains("Catch-up stopped") } }
        assertNotNull(env.repo.message("m-751"))
        assertNull(env.repo.message("m-750"))
        assertNotNull("progress is kept", RelayPrefs.catchUpGaps(env.context))

        // A restart, and the link comes back: it carries on below m-751, not from the top.
        fake.beforeFetch = null
        val (again, fake2) = env.pairedManager()
        fake2.history[CONV] = fake.history[CONV]!!
        fake2.conversations += fake.conversations
        fake2.goOnline()
        env.eventually(again, timeoutMs = 10_000, what = "the gap to be filled") {
            RelayLog.read(env.context).any { it.contains("Caught up") }
        }
        assertEquals("resumed at the recorded cursor", Cursor("m-751", 751_000), fake2.fetches.first().third)
        for (n in 101..1000) assertNotNull("m-$n", env.repo.message("m-$n"))
        assertEquals("down to the stored m-100, page by page", 14, fake2.fetches.size)
        assertNull("nothing left to resume", RelayPrefs.catchUpGaps(env.context))
    }

    // --- owner gate ------------------------------------------------------------------------------------

    @Test
    fun liveEventsWaitWhileTheArchivesOwnerIsUnsettled() {
        val (manager, fake) = env.pairedManager()
        RelayPrefs.setArchiveOwner(env.context, "phone-1", "someone@example.com")
        env.repo.upsertMessages(listOf(message(1)))
        fake.history[CONV] = listOf(message(1), message(2))
        fake.conversations += ConversationDto(id = CONV, name = "Sam", lastMessageTs = 2_000, latestMessageId = "m-2")
        // The bridge does not know the phone when the connection begins.
        fake.phoneId = ""
        RelayPrefs.setLiveEnabled(env.context, true)
        manager.serviceRunning = true
        manager.onServiceStarted()
        env.eventually(manager, what = "connect") { fake.calls.contains("connect") }
        val tmpId = manager.sendText(CONV, "wait for the owner")

        fake.emitMessage(message(2))
        fake.goOnline()
        manager.awaitIdleForTesting()
        Thread.sleep(150)
        manager.awaitIdleForTesting()
        assertNull("dropped while the owner is unsettled", env.repo.message("m-2"))
        assertTrue("nothing sent", fake.sent.isEmpty())
        assertEquals(OutboxStatus.PENDING, env.repo.outbox(tmpId)!!.status)

        // The phone becomes known (the same one): settled; the catch-up fetches what was dropped.
        fake.phoneId = "phone-1"
        fake.goOnline()
        env.eventually(manager, what = "the catch-up") { env.repo.message("m-2") != null }
        env.eventually(manager, what = "the send") { fake.sent.size == 1 }
        fake.emitMessage(incoming("m-live", "stored again", ts = 3_000))
        manager.awaitIdleForTesting()
        assertNotNull(env.repo.message("m-live"))
        manager.serviceRunning = false
    }

    // --- Android 8 notification check ---------------------------------------------------------------

    @Test
    fun withoutABroadcastTheNotificationIsCheckedWhileConnected() {
        val (manager, fake) = env.pairedManager(RelayTestEnv.FAST.copy(pollNotifications = true, notificationCheckMs = 50))
        RelayPrefs.setLiveEnabled(env.context, true)
        val service = Robolectric.buildService(RelayService::class.java).create().get()
        service.onStartCommand(null, 0, 1)
        env.eventually(manager, what = "connect") { fake.calls.contains("connect") }
        fake.goOnline()
        manager.awaitIdleForTesting()

        // Turned off, and (as on Android 8) nothing tells the app.
        notifications().setNotificationsEnabled(false)
        env.eventually(manager, what = "the disconnect") { fake.calls.contains("disconnect") }
        env.eventually(manager, what = "the state") { manager.state.value.code == RelayState.NOTIFICATIONS_OFF }
        assertTrue("stay connected is kept", RelayPrefs.liveEnabled(env.context))
    }

    @Test
    fun noPollingWhereAndroidReportsIt() {
        assertFalse("Robolectric runs a recent Android", RelayManager.Timing().pollNotifications)
        assertTrue(RelayManager.Timing().notificationCheckMs <= 120_000)
    }

    // --- helpers ------------------------------------------------------------------------------------------

    private fun notifications() = shadowOf(env.context.getSystemService(NotificationManager::class.java))

    /** m-<n> at n seconds in [CONV], as the phone would page it. */
    private fun message(n: Int) = incoming("m-$n", "message $n", ts = n * 1_000L).copy(isOld = true)

    private fun assertLogIsContentFree() {
        val log = RelayLog.read(env.context).joinToString("\n")
        for (secret in listOf("Sam", "Kim", "kept after", CONV, "+1555")) {
            assertFalse("log must not contain '$secret'", log.contains(secret))
        }
    }
}
