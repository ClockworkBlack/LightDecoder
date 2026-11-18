package com.lightdecoder.imessage

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.OpenBubblesTestNotifications.ALICE_GUID
import com.lightdecoder.imessage.OpenBubblesTestNotifications.Line
import com.lightdecoder.imessage.OpenBubblesTestNotifications.chat
import com.lightdecoder.imessage.OpenBubblesTestNotifications.posted
import com.lightdecoder.imessage.OpenBubblesTestNotifications.sender
import com.lightdecoder.messenger.MessengerNotificationListener
import com.lightdecoder.relay.MessageNotifier
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * Blue Bubbles' backend: OpenBubbles' notifications become an iMessage
 * archive, and replies and "read" go back through their own buttons.
 */
@RunWith(AndroidJUnit4::class)
class IMessageManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ArchiveDb
    private lateinit var repo: ArchiveRepository
    private lateinit var manager: IMessageManager
    private val alice = sender("Alice", "tel:+15551234567")
    private val replies = ArrayList<String>()
    private var markReads = 0
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                OpenBubblesTestNotifications.REPLY_ACTION ->
                    replies += RemoteInput.getResultsFromIntent(intent)?.getCharSequence(OpenBubblesTestNotifications.REPLY_KEY)?.toString() ?: "<none>"
                OpenBubblesTestNotifications.MARK_READ_ACTION -> markReads++
            }
        }
    }

    @Before
    fun setUp() {
        context.deleteDatabase(DB)
        db = ArchiveDb(context, DB)
        repo = ArchiveRepository.forTesting(db, ChatService.IMESSAGE)
        ArchiveRepository.installForTesting(repo)
        manager = IMessageManager.installForTesting(context, repo)
        IMessagePrefs.setEnabled(context, true)
        IMessagePrefs.setNotifyMessages(context, true)
        val filter = IntentFilter().apply {
            addAction(OpenBubblesTestNotifications.REPLY_ACTION)
            addAction(OpenBubblesTestNotifications.MARK_READ_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    @After
    fun tearDown() {
        context.unregisterReceiver(receiver)
        IMessagePrefs.setEnabled(context, false)
        MessageNotifier.setVisibleConversationId(ChatService.IMESSAGE, null)
        IMessageManager.resetForTesting()
        ArchiveRepository.resetForTesting()
        db.close()
    }

    private fun capture(vararg lines: Line, live: Boolean = true, messageGuid: String = "msg-1") {
        manager.onNotification(posted(chat(context, lines.toList(), messageGuid = messageGuid)), live)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun messages() = repo.messages(ALICE_GUID)

    @Test
    fun anOpenBubblesNotificationBecomesAnIMessageChat() {
        capture(Line("Hi there", 1_700_000_000_000L, alice))
        val conversation = repo.conversation(ALICE_GUID)!!
        assertTrue(conversation.unread)
        assertEquals(IMessageManager.TRANSPORT, repo.messages(ALICE_GUID).single().transport)
        val people = repo.participants(ALICE_GUID).filter { !it.isMe }
        assertEquals("Alice", people.single().name)
        assertEquals("+15551234567", people.single().number)
        assertEquals("Hi there", messages().single().text)
        assertFalse(messages().single().isOutgoing)
        assertTrue(manager.canReply(ALICE_GUID))
    }

    @Test
    fun aNotificationShownAgainAddsOnlyItsNewLines() {
        capture(Line("One", 1_700_000_000_000L, alice))
        capture(Line("One", 1_700_000_000_000L, alice), Line("Two", 1_700_000_060_000L, alice))
        capture(Line("One", 1_700_000_000_000L, alice), Line("Two", 1_700_000_060_000L, alice))
        assertEquals(listOf("One", "Two"), messages().sortedBy { it.timestampMs }.map { it.text })
    }

    @Test
    fun aReplyGoesThroughTheReplyButtonAndIsKeptOnce() {
        capture(Line("Hi there", 1_700_000_000_000L, alice))
        val tmpId = manager.sendText(ALICE_GUID, "  On my way  ")
        idle()
        assertEquals(listOf("On my way"), replies)
        assertNull("the local copy is replaced", repo.message(MessageRow.tmpMessageId(tmpId)))
        val sent = messages().single { it.isOutgoing }
        assertEquals("On my way", sent.text)
        // OpenBubbles then shows it in its notification, under the user's name and in seconds.
        val seconds = sent.timestampMs / 1000 + 2
        capture(Line("Hi there", 1_700_000_000_000L, alice), Line("On my way", seconds, sender("Ryan")))
        assertEquals(1, messages().count { it.isOutgoing })
    }

    @Test
    fun aReplySentFromOpenBubblesNotificationShadeIsKeptAsTheUsers() {
        capture(Line("Hi there", 1_700_000_000_000L, alice), Line("Sure", 1_700_000_100L, sender("Ryan")))
        val own = messages().single { it.isOutgoing }
        assertEquals("Sure", own.text)
        assertEquals(1_700_000_100_000L, own.timestampMs)
    }

    @Test
    fun withoutAReplyButtonTheSendFailsLoudlyAndStaysForOpenBubblesToSend() {
        val failure = runCatching { manager.sendText(ALICE_GUID, "Hello") }.exceptionOrNull()
        assertEquals(IMessageErrors.NO_REPLY_BUTTON, (failure as com.lightdecoder.relay.RelayException).code)
        val row = messages().single()
        assertEquals(LocalState.FAILED, row.localState)
        assertEquals(IMessageErrors.NO_REPLY_BUTTON, row.errorText)
        assertTrue(replies.isEmpty())
    }

    @Test
    fun theSameTextTwiceInAnswerToTheSameMessageIsNotHandedOverTwice() {
        // OpenBubbles drops a reply equal to the last one for the same message.
        capture(Line("Hi there", 1_700_000_000_000L, alice), messageGuid = "m1")
        manager.sendText(ALICE_GUID, "ok")
        idle()
        assertFalse(manager.canReply(ALICE_GUID, "ok"))
        assertTrue(manager.canReply(ALICE_GUID, "ok then"))
        assertTrue(runCatching { manager.sendText(ALICE_GUID, "ok") }.isFailure)
        idle()
        assertEquals(listOf("ok"), replies)
        // A new message from them: "ok" answers that one, and goes.
        capture(Line("Hi there", 1_700_000_000_000L, alice), Line("Sure?", 1_700_000_100_000L, alice), messageGuid = "m2")
        assertTrue(manager.canReply(ALICE_GUID, "ok"))
    }

    @Test
    fun markAsReadIsStillPressedAfterTheAppRestarted() = runBlocking {
        capture(Line("Hi there", 1_700_000_000_000L, alice))
        // The process goes; the listener reads OpenBubbles' notification again when it reconnects.
        IMessageReplies.clear()
        capture(Line("Hi there", 1_700_000_000_000L, alice), live = false)
        manager.markRead(ALICE_GUID)
        idle()
        assertEquals(1, markReads)
    }

    @Test
    fun aLaterIdenticalReplyFromOpenBubblesItselfIsKept() {
        capture(Line("Hi there", 1_700_000_000_000L, alice))
        manager.sendText(ALICE_GUID, "ok")
        val sentAt = messages().single { it.isOutgoing }.timestampMs
        // OpenBubbles shows it back; then the user answers "ok" again, from OpenBubbles' own notification.
        capture(Line("Hi there", 1_700_000_000_000L, alice), Line("ok", sentAt / 1000 + 1, sender("Ryan")))
        capture(
            Line("Hi there", 1_700_000_000_000L, alice), Line("ok", sentAt / 1000 + 1, sender("Ryan")),
            Line("Again?", sentAt + 30_000, alice), Line("ok", sentAt / 1000 + 60, sender("Ryan")),
        )
        assertEquals(2, messages().count { it.isOutgoing && it.text == "ok" })
    }

    @Test
    fun deletedMessagesAndChatsDoNotComeBackFromTheNotification() = runBlocking {
        capture(Line("One", 1_700_000_000_000L, alice), Line("Two", 1_700_000_060_000L, alice))
        manager.deleteMessage(messages().first { it.text == "One" }.id)
        capture(Line("One", 1_700_000_000_000L, alice), Line("Two", 1_700_000_060_000L, alice))
        assertEquals(listOf("Two"), messages().map { it.text })
        manager.deleteConversation(ALICE_GUID)
        // OpenBubbles shows the old lines again with a new one: only the new one comes in.
        val now = System.currentTimeMillis()
        capture(Line("One", 1_700_000_000_000L, alice), Line("Two", 1_700_000_060_000L, alice), Line("Three", now + 60_000, alice))
        assertEquals(listOf("Three"), messages().map { it.text })
    }

    @Test
    fun theAddressStaysWhenANotificationCarriesNone() {
        capture(Line("Hi there", 1_700_000_000_000L, alice))
        // A "Notify Anyways" post has no handle on its sender.
        capture(Line("Hi there", 1_700_000_000_000L, alice), Line("Call me", 1_700_000_100_000L, sender("Alice")))
        assertEquals("+15551234567", repo.participants(ALICE_GUID).single { !it.isMe }.number)
        assertEquals(ALICE_GUID, manager.conversationWith("(555) 123-4567"))
    }

    @Test
    fun aGroupKeepsEveryoneSeenWritingInIt() {
        val group = OpenBubblesTestNotifications.GROUP_GUID
        manager.onNotification(posted(chat(context, listOf(Line("Hi", 1_000_000_000_000L, sender("Bob"))), chatGuid = group, groupTitle = "Friends")), true)
        manager.onNotification(posted(chat(context, listOf(Line("Yo", 1_000_000_100_000L, sender("Carol"))), chatGuid = group, groupTitle = "Friends")), true)
        assertEquals(setOf("Bob", "Carol"), repo.participants(group).filter { !it.isMe }.map { it.name }.toSet())
    }

    @Test
    fun readingTheChatPressesMarkAsReadOnceForWhatArrived() = runBlocking {
        capture(Line("Hi there", 1_700_000_000_000L, alice))
        manager.markRead(ALICE_GUID)
        idle()
        assertEquals(1, markReads)
        assertFalse(repo.conversation(ALICE_GUID)!!.unread)
        manager.markRead(ALICE_GUID)
        idle()
        assertEquals("nothing new since", 1, markReads)
    }

    @Test
    fun aChatOnScreenIsNotMarkedUnread() {
        MessageNotifier.setVisibleConversationId(ChatService.IMESSAGE, ALICE_GUID)
        capture(Line("Hi there", 1_700_000_000_000L, alice))
        assertFalse(repo.conversation(ALICE_GUID)!!.unread)
    }

    @Test
    fun aNewMessageIsNotifiedWithoutTheNumberInTheTag() {
        capture(Line("Hi there", 1_700_000_000_000L, alice))
        val posted = shadowOf(context.getSystemService(NotificationManager::class.java)).activeNotifications
        val ours = posted.single { it.tag?.startsWith("im:") == true }
        assertFalse(ours.tag.contains("5551234567"))
    }

    @Test
    fun whatWasShowingAlreadyIsKeptButNotAnnounced() {
        capture(Line("Hi there", 1_700_000_000_000L, alice), live = false)
        assertEquals(1, messages().size)
        val posted = shadowOf(context.getSystemService(NotificationManager::class.java)).activeNotifications
        assertTrue(posted.none { it.tag?.startsWith("im:") == true })
    }

    @Test
    fun withBlueBubblesOffNothingIsRead() {
        manager.setEnabled(false)
        capture(Line("Hi there", 1_700_000_000_000L, alice))
        assertNull(repo.conversation(ALICE_GUID))
        assertFalse(manager.isPaired())
    }

    @Test
    fun theListenerHandsOpenBubblesNotificationsToTheBackend() {
        val listener = Robolectric.setupService(MessengerNotificationListener::class.java)
        try {
            listener.onNotificationPosted(posted(chat(context, listOf(Line("Hi there", 1_700_000_000_000L, alice)))))
            val deadline = System.currentTimeMillis() + 5_000
            while (repo.conversation(ALICE_GUID) == null && System.currentTimeMillis() < deadline) {
                idle()
                Thread.sleep(20)
            }
            assertEquals("Hi there", messages().single().text)
        } finally {
            listener.onDestroy()
        }
    }

    @Test
    fun theStateSaysWhatIsMissing() {
        manager.refreshState()
        assertEquals(IMessageErrors.NOT_INSTALLED, manager.state.value.code)
        shadowOf(context.packageManager).installPackage(
            android.content.pm.PackageInfo().apply { packageName = OpenBubblesTestNotifications.PACKAGE },
        )
        manager.refreshState()
        assertEquals(IMessageErrors.NO_ACCESS, manager.state.value.code)
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(
            android.content.ComponentName(context, MessengerNotificationListener::class.java), true,
        )
        manager.refreshState()
        assertTrue(manager.state.value.isOnline)
    }

    private companion object {
        const val DB = "imessage-test.db"
    }
}
