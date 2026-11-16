package com.lightdecoder.relay

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MediaCache
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.archive.OutboxRow
import com.lightdecoder.archive.OutboxStatus
import com.lightdecoder.relay.RelayManagerTest.Companion.CONV
import com.lightdecoder.relay.RelayManagerTest.Companion.outgoing
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Sending through the outbox: order, retries, failures, restarts, and the settings gate. */
@RunWith(AndroidJUnit4::class)
class OutboxTest {

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
    fun sendsOnceOnlineAndTheEchoFinishesIt() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        val tmpId = manager.sendText(CONV, "see you at 8")

        env.eventually(manager, what = "the send") { env.repo.outbox(tmpId)?.status == OutboxStatus.SENT }
        assertEquals(listOf(FakeRelayBridge.Sent(CONV, tmpId, "see you at 8")), fake.sent.toList())
        assertEquals(1, env.repo.outbox(tmpId)!!.attempts)
        assertEquals(LocalState.SENDING, env.repo.message(MessageRow.tmpMessageId(tmpId))!!.localState)

        fake.emitMessage(outgoing("real-1", "see you at 8", tmpId))
        manager.awaitIdleForTesting()
        assertNull(env.repo.outbox(tmpId))
        assertNull(env.repo.message(MessageRow.tmpMessageId(tmpId)))
        assertEquals(LocalState.SYNCED, env.repo.message("real-1")!!.localState)
    }

    @Test
    fun anEchoDuringTheSendCallIsHandled() {
        val (manager, fake) = env.pairedManager()
        fake.duringSend = { fake.emitMessage(outgoing("real-1", it.text, it.tmpId)); manager.awaitIdleForTesting() }
        fake.goOnline()
        val tmpId = manager.sendText(CONV, "fast phone")
        env.eventually(manager, what = "the entry to be gone") { fake.sent.isNotEmpty() && env.repo.outbox(tmpId) == null }
        assertNull(env.repo.message(MessageRow.tmpMessageId(tmpId)))
        assertEquals("fast phone", env.repo.message("real-1")!!.text)
    }

    @Test
    fun sendsKeepTheirOrder() {
        val (manager, fake) = env.pairedManager()
        val first = manager.sendText(CONV, "one")
        val second = manager.sendText(CONV, "two")
        val third = manager.sendText(CONV, "three")
        manager.awaitIdleForTesting()
        fake.goOnline()
        env.eventually(manager, what = "three sends") { fake.sent.size == 3 }
        assertEquals(listOf(first, second, third), fake.sent.map { it.tmpId })
    }

    @Test
    fun aTransientFailureIsRetriedThenSent() {
        val (manager, fake) = env.pairedManager()
        fake.sendAnswers += { tmp -> SendResult(tmp, ok = false, status = "FAILURE_2", code = RelayException.SEND_FAILED) }
        fake.goOnline()
        val tmpId = manager.sendText(CONV, "retry me")

        env.eventually(manager, what = "the retry") { env.repo.outbox(tmpId)?.status == OutboxStatus.SENT }
        assertEquals(2, fake.sent.size)
        assertEquals("the same idempotency key both times", setOf(tmpId), fake.sent.map { it.tmpId }.toSet())
        assertEquals(2, env.repo.outbox(tmpId)!!.attempts)
    }

    @Test
    fun transientFailuresGiveUpAfterTheConnectorsThreeRetries() {
        val (manager, fake) = env.pairedManager()
        repeat(4) { fake.sendAnswers += { tmp -> SendResult(tmp, ok = false, status = "FAILURE_3", code = RelayException.SEND_FAILED) } }
        fake.goOnline()
        val tmpId = manager.sendText(CONV, "never")

        env.eventually(manager, what = "the failure") { env.repo.outbox(tmpId)?.status == OutboxStatus.FAILED }
        assertEquals(4, fake.sent.size)
        assertEquals("FAILURE_3", env.repo.outbox(tmpId)!!.lastError)
    }

    @Test
    fun anEchoDuringTheRetryPauseIsNotSentAgain() {
        // The send broke off (send_failed), but the phone did send it: its
        // echo arrives while the outbox waits to retry.
        val (manager, fake) = env.pairedManager(RelayTestEnv.FAST.copy(outboxRetryDelaysMs = listOf(300, 300, 300)))
        fake.sendAnswers += { tmp ->
            fake.emitMessage(outgoing("real-late", "went out anyway", tmp))
            throw RelayException(RelayException.SEND_FAILED, "upload broke off")
        }
        fake.goOnline()
        val tmpId = manager.sendText(CONV, "went out anyway")

        env.eventually(manager, what = "the entry to be gone") { fake.sent.isNotEmpty() && env.repo.outbox(tmpId) == null }
        Thread.sleep(500) // past the retry pause
        manager.awaitIdleForTesting()
        assertEquals("sent once", 1, fake.sent.size)
        assertEquals("went out anyway", env.repo.message("real-late")!!.text)
        assertNull(env.repo.message(MessageRow.tmpMessageId(tmpId)))
    }

    @Test
    fun aPermanentFailureWaitsForTheUserAndRetryResendsIt() {
        val (manager, fake) = env.pairedManager()
        fake.sendAnswers += { tmp -> SendResult(tmp, ok = false, status = "FAILURE_4", code = RelayException.SEND_FAILED) }
        fake.goOnline()
        val tmpId = manager.sendText(CONV, "not default sms app")

        env.eventually(manager, what = "the failure") { env.repo.outbox(tmpId)?.status == OutboxStatus.FAILED }
        assertEquals(1, fake.sent.size)
        val local = env.repo.message(MessageRow.tmpMessageId(tmpId))!!
        assertEquals(LocalState.FAILED, local.localState)
        assertEquals("FAILURE_4", local.errorText)

        manager.retry(tmpId)
        env.eventually(manager, what = "the resend") { env.repo.outbox(tmpId)?.status == OutboxStatus.SENT }
        assertEquals(2, fake.sent.size)
    }

    @Test
    fun aFailedSendCanBeDiscarded() {
        val (manager, fake) = env.pairedManager()
        fake.sendAnswers += { throw RelayException(RelayException.INVALID_ARGUMENT, "bad") }
        fake.goOnline()
        val tmpId = manager.sendText(CONV, "oops")
        env.eventually(manager, what = "the failure") { env.repo.outbox(tmpId)?.status == OutboxStatus.FAILED }

        manager.discardFailed(tmpId)
        manager.awaitIdleForTesting()
        assertNull(env.repo.outbox(tmpId))
        assertNull(env.repo.message(MessageRow.tmpMessageId(tmpId)))
    }

    @Test
    fun losingTheConnectionMidQueueKeepsTheMessagePending() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        manager.awaitIdleForTesting()
        // The link dropped, and Go has not said so yet: sends fail with not_connected.
        fake.connected = false
        val tmpId = manager.sendText(CONV, "later")
        env.eventually(manager, what = "back to pending") {
            val row = env.repo.outbox(tmpId)
            row != null && row.attempts >= 1 && row.status == OutboxStatus.PENDING && row.lastError == RelayException.NOT_CONNECTED
        }
        assertEquals(LocalState.PENDING, env.repo.message(MessageRow.tmpMessageId(tmpId))!!.localState)
        assertTrue(fake.sent.isEmpty())

        // The next connection sends it.
        fake.goOnline()
        env.eventually(manager, what = "the send") { env.repo.outbox(tmpId)?.status == OutboxStatus.SENT }
        assertEquals(1, fake.sent.size)
    }

    @Test
    fun anInterruptedSendIsLookedUpOnThePhoneAndNeverSentAgainBlindly() {
        // As left by a process that died mid-send: the entry is SENDING and
        // the phone's echo already replaced the local copy.
        insertSending("tmp-done", "already there")
        env.repo.upsertMessage(outgoing("real-done", "already there", "tmp-done"))
        assertEquals(OutboxStatus.SENDING, env.repo.outbox("tmp-done")!!.status)
        assertNull(env.repo.message(MessageRow.tmpMessageId("tmp-done")))
        // One the phone has, but whose echo was never handled: the lookup finds it.
        insertSending("tmp-echoed", "went out")
        // And one the phone does not show: it may still have gone out, so it
        // waits for the user instead of going out a second time.
        insertSending("tmp-lost", "lost in flight")

        val (manager, fake) = env.pairedManager()
        fake.pages[CONV] = java.util.concurrent.ConcurrentLinkedQueue(
            listOf(MessagePage(listOf(outgoing("real-echoed", "went out", "tmp-echoed")), null))
        )
        fake.goOnline()
        env.eventually(manager, what = "the queue to settle") {
            env.repo.outbox("tmp-done") == null && env.repo.outbox("tmp-echoed") == null &&
                env.repo.outbox("tmp-lost")?.status == OutboxStatus.FAILED
        }
        assertTrue("nothing sent again", fake.sent.isEmpty())
        assertEquals("one lookup for the conversation", listOf(CONV), fake.fetches.map { it.first })
        assertEquals("went out", env.repo.message("real-echoed")!!.text)
        val lost = env.repo.message(MessageRow.tmpMessageId("tmp-lost"))!!
        assertEquals(LocalState.FAILED, lost.localState)
        assertEquals(Outbox.INTERRUPTED, lost.errorText)

        // The user decides: retry sends it.
        manager.retry("tmp-lost")
        env.eventually(manager, what = "the resend") { fake.sent.size == 1 }
        assertEquals("tmp-lost", fake.sent.single().tmpId)
    }

    @Test
    fun aSentMessageWhoseEchoWasLostIsSettledByLookingItUp() {
        val (manager, fake) = env.pairedManager(RelayTestEnv.FAST.copy(unconfirmedSendCheckMs = 50))
        fake.goOnline()
        val tmpId = manager.sendText(CONV, "echo lost")
        env.eventually(manager, what = "the send") { env.repo.outbox(tmpId)?.status == OutboxStatus.SENT }
        // The echo never came as an event, but the phone has the message.
        fake.pages[CONV] = java.util.concurrent.ConcurrentLinkedQueue(
            listOf(MessagePage(listOf(outgoing("real-1", "echo lost", tmpId)), null))
        )
        env.eventually(manager, what = "the lookup") { env.repo.outbox(tmpId) == null }
        assertNull(env.repo.message(MessageRow.tmpMessageId(tmpId)))
        assertEquals("echo lost", env.repo.message("real-1")!!.text)
        assertEquals(1, fake.sent.size)
        assertTrue(RelayPrefs.unconfirmedSends(env.context).isEmpty())
    }

    @Test
    fun aSentMessageThatIsNeverConfirmedStopsShowingSending() {
        val (manager, fake) = env.pairedManager(
            RelayTestEnv.FAST.copy(unconfirmedSendCheckMs = 50, unconfirmedSendGiveUpMs = 300)
        )
        fake.goOnline()
        val tmpId = manager.sendText(CONV, "into the void")
        env.eventually(manager, what = "the send") { env.repo.outbox(tmpId)?.status == OutboxStatus.SENT }
        env.eventually(manager, what = "giving up") { env.repo.outbox(tmpId)?.status == OutboxStatus.FAILED }
        val local = env.repo.message(MessageRow.tmpMessageId(tmpId))!!
        assertEquals(LocalState.FAILED, local.localState)
        assertEquals(Outbox.UNCONFIRMED, local.errorText)
        assertTrue("looked up before giving up", fake.fetches.isNotEmpty())
        assertEquals("not sent again by itself", 1, fake.sent.size)

        // A late echo still settles it.
        fake.emitMessage(outgoing("real-late", "into the void", tmpId))
        manager.awaitIdleForTesting()
        assertNull(env.repo.outbox(tmpId))
        assertNull(env.repo.message(MessageRow.tmpMessageId(tmpId)))
    }

    @Test
    fun nothingIsSentBeforeTheConnectionsSettingsArrive() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline(withSettings = false)
        val tmpId = manager.sendText(CONV, "needs the sim")
        manager.awaitIdleForTesting()
        Thread.sleep(150)
        assertTrue("held until settings", fake.sent.isEmpty())
        assertEquals(OutboxStatus.PENDING, env.repo.outbox(tmpId)!!.status)

        fake.emitSettings()
        env.eventually(manager, what = "the send") { fake.sent.size == 1 }
        assertEquals(1, manager.settings.value!!.sims.size)
    }

    @Test
    fun withoutSettingsTheGateOpensAfterItsTimeout() {
        val (manager, fake) = env.pairedManager(RelayTestEnv.FAST.copy(settingsGateMs = 200))
        fake.goOnline(withSettings = false)
        val tmpId = manager.sendText(CONV, "eventually")
        env.eventually(manager, what = "the send after the gate timeout") { env.repo.outbox(tmpId)?.status == OutboxStatus.SENT }
    }

    @Test
    fun settingsFromAnEarlierConnectionDoNotOpenTheGate() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline(withSettings = true)
        fake.emitState("disconnected")
        fake.goOnline(withSettings = false)
        // Only the second connection is under test: let its events land before sending.
        manager.awaitIdleForTesting()
        manager.sendText(CONV, "wait")
        manager.awaitIdleForTesting()
        Thread.sleep(150)
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun anAttachmentIsCopiedFirstThenSent() {
        val (manager, fake) = env.pairedManager()
        val picture = File(env.context.cacheDir, "pick.png").apply { writeBytes(ByteArray(2048) { it.toByte() }) }
        val tmpId = manager.sendMedia(CONV, Uri.fromFile(picture), caption = "look", replyToId = null)
        manager.awaitIdleForTesting()

        val copy = MediaCache.outboxFile(env.context, tmpId)
        assertTrue("copied into app storage", copy.isFile)
        val row = env.repo.outbox(tmpId)!!
        assertEquals(copy.path, row.mediaLocalPath)
        assertEquals("pick.png", row.mediaName)
        val local = env.repo.message(MessageRow.tmpMessageId(tmpId))!!
        assertEquals(copy.path, local.media.single().localPath)

        picture.delete() // the original can go away; the copy is sent
        fake.goOnline()
        env.eventually(manager, what = "the send") { fake.sent.size == 1 }
        val sent = fake.sent.single()
        assertArrayEquals(ByteArray(2048) { it.toByte() }, sent.media)
        assertEquals("look", sent.text)
        assertEquals("pick.png", sent.fileName)

        // The echo retires the entry and its copy.
        env.eventually(manager, what = "sent") { env.repo.outbox(tmpId)?.status == OutboxStatus.SENT }
        fake.emitMessage(outgoing("real-media", "look", tmpId))
        manager.awaitIdleForTesting()
        assertNull(env.repo.outbox(tmpId))
        assertTrue("copy deleted", !copy.exists())
    }

    @Test
    fun anUnreadableAttachmentIsQueuedAsFailed() {
        val (manager, _) = env.pairedManager()
        val tmpId = manager.sendMedia(CONV, Uri.fromFile(File(env.context.cacheDir, "missing.png")))
        manager.awaitIdleForTesting()
        assertEquals(OutboxStatus.FAILED, env.repo.outbox(tmpId)!!.status)
        assertEquals(LocalState.FAILED, env.repo.message(MessageRow.tmpMessageId(tmpId))!!.localState)
    }

    private fun insertSending(tmpId: String, text: String) {
        env.repo.insertLocalOutgoing(tmpId, CONV, text, null, null, null, null, 1_000)
        env.repo.enqueueOutbox(OutboxRow(idempotencyKey = tmpId, conversationId = CONV, textBody = text, createdAt = 1_000))
        env.repo.updateOutbox(tmpId, OutboxStatus.SENDING, attemptsDelta = 1)
    }
}
