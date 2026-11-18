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

    private fun capture(vararg lines: Line, live: Boolean = true) {
        manager.onNotification(posted(chat(context, lines.toList())), live)
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
    fun withoutAReplyButtonTheMessageFailsForOpenBubblesToSend() {
        val tmpId = manager.sendText(ALICE_GUID, "Hello")
        val row = repo.message(MessageRow.tmpMessageId(tmpId))!!
        assertEquals(LocalState.FAILED, row.localState)
        assertEquals(IMessageErrors.NO_REPLY_BUTTON, row.errorText)
        assertTrue(replies.isEmpty())
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
