package com.lightdecoder.relay

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.OutboxStatus
import com.lightdecoder.relay.RelayManagerTest.Companion.CONV
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * Several messages in a row: each text goes out as soon as Google accepted
 * the one before it, without waiting for the phone's answer to it, and they
 * keep their order.
 */
@RunWith(AndroidJUnit4::class)
class OutboxPipelineTest {

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
    fun aBurstGoesOutWithoutWaitingForThePhoneBetweenMessages() {
        val (manager, fake) = env.pairedManager()
        val phone = CountDownLatch(1)
        fake.holdPhoneAnswers = phone
        fake.goOnline()
        val ids = listOf("one", "two", "three", "four").map { manager.sendText(CONV, it) }

        // All four reach Google, in order, while the phone has answered none of them.
        env.eventually(manager, what = "all four posted") { fake.sent.size == 4 }
        assertEquals(ids, fake.sent.map { it.tmpId })
        assertEquals(4, fake.awaitingPhone.get())
        assertTrue(ids.all { env.repo.outbox(it)?.status == OutboxStatus.SENDING })

        phone.countDown()
        env.eventually(manager, what = "every answer") { ids.all { env.repo.outbox(it)?.status == OutboxStatus.SENT } }
        assertEquals("each sent once", 4, fake.sent.size)
    }

    @Test
    fun noMoreThanTheLimitWaitForThePhoneAtOnce() {
        val (manager, fake) = env.pairedManager()
        val phone = CountDownLatch(1)
        fake.holdPhoneAnswers = phone
        fake.goOnline()
        val ids = (1..Outbox.MAX_TEXTS_IN_FLIGHT + 3).map { manager.sendText(CONV, "m$it") }

        env.eventually(manager, what = "the first batch") { fake.sent.size == Outbox.MAX_TEXTS_IN_FLIGHT }
        Thread.sleep(200)
        assertEquals("the rest wait for answers", Outbox.MAX_TEXTS_IN_FLIGHT, fake.sent.size)

        phone.countDown()
        env.eventually(manager, what = "everything") { ids.all { env.repo.outbox(it)?.status == OutboxStatus.SENT } }
        assertEquals(ids, fake.sent.map { it.tmpId })
    }

    @Test
    fun anAttachmentWaitsForTheTextsAheadOfIt() {
        val (manager, fake) = env.pairedManager()
        val phone = CountDownLatch(1)
        fake.holdPhoneAnswers = phone
        fake.goOnline()
        val text = manager.sendText(CONV, "look at this")
        val picture = File(env.context.cacheDir, "pipe.png").apply { writeBytes(ByteArray(512) { it.toByte() }) }
        val media = manager.sendMedia(CONV, Uri.fromFile(picture), caption = "nice", replyToId = null)

        env.eventually(manager, what = "the text") { fake.sent.size == 1 }
        Thread.sleep(200)
        assertEquals("the picture waits for the text's answer", listOf(text), fake.sent.map { it.tmpId })

        phone.countDown()
        env.eventually(manager, what = "picture and caption") { fake.sent.size == 3 }
        assertEquals(text, fake.sent[0].tmpId)
        assertEquals("then the picture", media, fake.sent[1].tmpId)
        assertEquals("then its caption", "nice", fake.sent[2].text)
    }

    @Test
    fun aTransientAnswerIsRetriedWithoutHoldingTheOthersBack() {
        val (manager, fake) = env.pairedManager(RelayTestEnv.FAST.copy(outboxRetryDelaysMs = listOf(100, 100, 100)))
        val phone = CountDownLatch(1)
        fake.holdPhoneAnswers = phone
        fake.sendAnswers += { tmp -> SendResult(tmp, ok = false, status = "FAILURE_2", code = RelayException.SEND_FAILED) }
        fake.goOnline()
        val first = manager.sendText(CONV, "first")
        val second = manager.sendText(CONV, "second")
        env.eventually(manager, what = "both posted") { fake.sent.size == 2 }

        phone.countDown()
        env.eventually(manager, what = "both sent") {
            env.repo.outbox(first)?.status == OutboxStatus.SENT && env.repo.outbox(second)?.status == OutboxStatus.SENT
        }
        assertEquals("the first was sent again", listOf(first, second, first), fake.sent.map { it.tmpId })
        assertEquals(2, env.repo.outbox(first)!!.attempts)
    }

    @Test
    fun aDisconnectWhileWaitingStopsTheRestAndKeepsThemQueued() {
        val (manager, fake) = env.pairedManager()
        val phone = CountDownLatch(1)
        fake.holdPhoneAnswers = phone
        // The first answer: the connection dropped while waiting for the phone.
        fake.sendAnswers += { _ -> throw RelayException(RelayException.CONNECTION_CLOSED, "") }
        fake.goOnline()
        val first = manager.sendText(CONV, "first")
        env.eventually(manager, what = "posted") { fake.sent.size == 1 }
        phone.countDown()
        env.eventually(manager, what = "back in the queue") { env.repo.outbox(first)?.status == OutboxStatus.PENDING }
    }
}
