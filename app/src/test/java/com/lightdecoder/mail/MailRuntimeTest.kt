package com.lightdecoder.mail

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.archive.OutboxStatus
import com.lightdecoder.relay.Folder
import com.lightdecoder.relay.RelayException
import com.lightdecoder.relay.RelayState
import kotlinx.coroutines.runBlocking
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
import org.robolectric.Shadows.shadowOf

/** The Gmail tab's runtime over a fake mail server: the account, threading, notifications, replies and new emails. */
@RunWith(AndroidJUnit4::class)
class MailRuntimeTest {

    private lateinit var env: MailTestEnv

    @Before
    fun setUp() {
        env = MailTestEnv()
    }

    @After
    fun tearDown() {
        env.tearDown()
    }

    private fun linked(): MailManager {
        val manager = env.manager()
        runBlocking { manager.addAccount(FakeMailBridge.OWN, FakeMailBridge.PASSWORD) }
        return manager
    }

    @Test
    fun aWrongPasswordStoresNothing() {
        val manager = env.manager()
        try {
            runBlocking { manager.addAccount(FakeMailBridge.OWN, "wrong") }
            fail("Gmail refused it")
        } catch (e: RelayException) {
            assertEquals(MailErrors.AUTH_FAILED, e.code)
        }
        assertFalse(manager.isPaired())
        assertNull(MailVault.load(env.context))
        assertEquals(RelayState.Phase.UNPAIRED, manager.state.value.phase)
    }

    @Test
    fun addingTheAccountFetchesRecentMailWithoutNotifying() {
        env.fake.receive("ann@example.com", "Lunch", "Are we still on for lunch?", name = "Ann")
        val manager = linked()

        assertTrue(manager.isPaired())
        assertEquals("the spaces of the app password are dropped", "abcdabcdabcdabcd", MailVault.load(env.context)!!.password)
        assertEquals(RelayState.Phase.CONNECTED, manager.state.value.phase)
        val conversation = env.repo.conversations(Folder.INBOX).single()
        assertEquals("Lunch", conversation.name)
        val message = env.repo.messages(conversation.id).single()
        assertEquals("Are we still on for lunch?", message.text)
        assertEquals("Ann", message.senderName)
        val notifications = shadowOf(env.context.getSystemService(NotificationManager::class.java)).allNotifications
        assertTrue("old mail is not notified", notifications.isEmpty())
    }

    @Test
    fun repliesJoinTheirThreadAndNewMailIsNotified() {
        val first = env.fake.receive("ann@example.com", "Lunch", "Are we still on?", name = "Ann")
        val manager = linked()
        env.fake.receive(
            "bob@example.com", "Re: Lunch", "Count me in", name = "Bob",
            inReplyTo = first.messageId, references = listOf(first.messageId), to = listOf(FakeMailBridge.OWN, "ann@example.com"),
        )
        env.fake.receive("carol@example.com", "Invoice", "Attached.")
        runBlocking { manager.syncNow() }

        val conversations = env.repo.conversations(Folder.INBOX)
        assertEquals(2, conversations.size)
        val lunch = conversations.single { it.name == "Lunch" }
        assertEquals(listOf("Are we still on?", "Count me in"), env.repo.messages(lunch.id).map { it.text })
        assertTrue("Ann and Bob", lunch.isGroup)
        assertTrue(lunch.unread)
        val notifications = shadowOf(env.context.getSystemService(NotificationManager::class.java)).allNotifications
        assertEquals("one per conversation with new mail", 2, notifications.size)
    }

    @Test
    fun aReplyGoesToEveryoneOnTheThreadAndItsCopyReplacesTheLocalOne() {
        val first = env.fake.receive(
            "ann@example.com", "Lunch", "Are we still on?", name = "Ann", to = listOf(FakeMailBridge.OWN, "bob@example.com"),
        )
        val manager = linked()
        val thread = env.repo.conversations(Folder.INBOX).single().id

        val tmpId = manager.sendText(thread, "Yes, 12:30")
        env.eventually("the reply to go out") { env.fake.sent.isNotEmpty() }
        val reply = env.fake.sent.single()
        assertEquals(listOf("ann@example.com", "bob@example.com"), (reply.to + reply.cc).map { it.email }.sorted())
        assertEquals("Re: Lunch", reply.subject)
        assertEquals(first.messageId, reply.inReplyTo)
        assertEquals(listOf(first.messageId), reply.references)
        assertEquals("Yes, 12:30", reply.text)
        assertEquals(tmpId, reply.tmpId)

        // Its copy in Sent is fetched and takes the local one's place in the same thread.
        env.eventually("the copy from Sent") { env.repo.message(MessageRow.tmpMessageId(tmpId)) == null }
        val messages = env.repo.messages(thread)
        assertEquals(listOf("Are we still on?", "Yes, 12:30"), messages.map { it.text })
        assertTrue(messages.last().isOutgoing)
        assertNull(env.repo.outbox(tmpId))
    }

    @Test
    fun aNewEmailStartsAThreadThatItsRepliesJoin() {
        val manager = linked()
        val thread = manager.startEmail(listOf("dan@example.com"), "Weekend")
        manager.sendText(thread, "Free on Saturday?")
        env.eventually("the email to go out") { env.fake.sent.isNotEmpty() }
        val email = env.fake.sent.single()
        assertEquals(listOf("dan@example.com"), email.to.map { it.email })
        assertEquals("Weekend", email.subject)
        assertTrue(email.inReplyTo.isEmpty())
        env.eventually("its copy") { env.repo.messages(thread).none { it.isLocal } }

        env.fake.receive("dan@example.com", "Re: Weekend", "Yes!", inReplyTo = email.messageId, references = listOf(email.messageId))
        runBlocking { manager.syncNow() }
        assertEquals(listOf("Free on Saturday?", "Yes!"), env.repo.messages(thread).map { it.text })
        assertEquals("Weekend", env.repo.conversation(thread)!!.name)
        assertEquals(1, env.repo.conversations(Folder.INBOX).size)
    }

    @Test
    fun withoutANetworkTheEmailWaitsAndGoesLater() {
        val manager = linked()
        val thread = manager.startEmail(listOf("dan@example.com"), "Later")
        env.fake.offline = true
        val tmpId = manager.sendText(thread, "when online")
        env.eventually("the queue to give up for now") { env.repo.outbox(tmpId)?.status == OutboxStatus.PENDING && env.fake.calls.isNotEmpty() && manager.state.value.phase == RelayState.Phase.DISCONNECTED }
        assertTrue(env.fake.sent.isEmpty())

        env.fake.offline = false
        runBlocking { manager.syncNow() }
        env.eventually("the email to go out") { env.fake.sent.size == 1 }
    }

    @Test
    fun readingAThreadMarksItReadInGmail() {
        env.fake.receive("ann@example.com", "Lunch", "Hi")
        val manager = linked()
        val thread = env.repo.conversations(Folder.INBOX).single()
        assertTrue(thread.unread)
        runBlocking { manager.markRead(thread.id) }
        assertFalse(env.repo.conversation(thread.id)!!.unread)
        assertEquals(1, env.fake.seen.size)
    }

    @Test
    fun anAttachmentIsDownloadedWhenOpened() {
        env.fake.receive(
            "ann@example.com", "Photo", "Here it is",
            attachments = listOf(MailBridge.Attachment(0, "cat.png", "image/png", 3)),
        )
        val uid = env.fake.inbox.single().location.uid
        env.fake.attachments[uid to 0] = byteArrayOf(1, 2, 3)
        val manager = linked()
        val row = env.repo.messages(env.repo.conversations(Folder.INBOX).single().id).single()
        val part = row.media.single()
        assertEquals("cat.png", part.fileName)
        val file = runBlocking { manager.mediaFile(row.id, part.partId) }
        assertNotNull(file)
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(file!!.readBytes()))
    }

    @Test
    fun removingTheAccountForgetsThePasswordAndHoldsQueuedEmail() {
        val manager = linked()
        val thread = manager.startEmail(listOf("dan@example.com"), "x")
        env.fake.offline = true
        val tmpId = manager.sendText(thread, "queued")
        env.eventually("queued") { env.repo.outbox(tmpId) != null }
        runBlocking { manager.removeAccount(deleteArchive = false) }
        assertFalse(manager.isPaired())
        assertNull(MailVault.load(env.context))
        assertEquals(RelayState.Phase.UNPAIRED, manager.state.value.phase)
        assertEquals(OutboxStatus.FAILED, env.repo.outbox(tmpId)?.status)
    }
}
