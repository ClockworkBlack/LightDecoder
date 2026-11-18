package com.lightdecoder

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.IMessagePrefs
import com.lightdecoder.messenger.MessengerNotificationListener
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.chat.BlueBubblesLinks
import com.lightdecoder.ui.chat.ConversationsActivity
import com.lightdecoder.ui.chat.ConversationsAdapter
import com.lightdecoder.ui.chat.MessagesAdapter
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.ui.chat.ThreadItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Blue Bubbles end to end on a real Android system, against a stand-in for the OpenBubbles app
 * (the :fakebubbles module, installed as com.bluebubbles.messaging.beta), which posts message
 * notifications the way OpenBubbles does and reports what its buttons receive.
 *
 * Checked: the real notification listener catches it; the iMessage is stored and becomes the
 * companion of the Messages conversation with the same number; a reply goes out through the
 * notification's Reply button with the text filled in, and OpenBubbles' echo of it is not stored
 * twice; Mark As Read is pressed; the Messages list shows one row and the conversation shows the
 * iMessages, the user's own in blue. Apple and a real iPhone are not part of it.
 */
@RunWith(AndroidJUnit4::class)
class BlueBubblesDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val replies = CopyOnWriteArrayList<String>()
    private val markedRead = CountDownLatch(1)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                REPLIED -> replies += intent.getStringExtra("text").orEmpty()
                MARKED_READ -> markedRead.countDown()
            }
        }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    private fun waitFor(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(200)
        }
    }

    private fun post(text: String, msgGuid: String) {
        context.sendBroadcast(
            Intent().setComponent(ComponentName(FAKE, "com.lightdecoder.fakebubbles.PostReceiver"))
                .putExtra("guid", GUID).putExtra("msgGuid", msgGuid).putExtra("from", "Alice")
                .putExtra("uri", "tel:+15551234567").putExtra("text", text)
                .putExtra("ts", System.currentTimeMillis()),
        )
    }

    @Before
    fun setUp() {
        shell("pm grant $FAKE android.permission.POST_NOTIFICATIONS")
        shell("cmd notification allow_listener ${context.packageName}/${MessengerNotificationListener::class.java.name}")
        waitFor("notification access") { MessengerNotificationListener.hasAccess(context) }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter().apply { addAction(REPLIED); addAction(MARKED_READ) }, ContextCompat.RECEIVER_EXPORTED,
        )
        IMessagePrefs.setEnabled(context, true)
        IMessageManager.get(context).repo.clearAll()
        // The person's Messages conversation (as Google Messages would have it).
        ArchiveRepository.get(context, ChatService.MESSAGES).upsertConversation(
            ConversationDto(
                id = MESSAGES_ID, name = "Alice", lastMessageTs = 1_000,
                participants = listOf(
                    ParticipantDto(id = "me", isMe = true),
                    ParticipantDto(id = "p1", name = "Alice", number = "+1 555-123-4567", formattedNumber = "(555) 123-4567"),
                ),
            ),
        )
    }

    @After
    fun tearDown() {
        context.unregisterReceiver(receiver)
        context.sendBroadcast(Intent().setComponent(ComponentName(FAKE, "com.lightdecoder.fakebubbles.PostReceiver")).putExtra("clear", true))
        IMessagePrefs.setEnabled(context, false)
    }

    @Test
    fun anIMessageArrivesInThePersonsChatAndAReplyGoesBackThroughOpenBubbles() {
        val iMessage = IMessageManager.get(context)
        val repo = iMessage.repo

        // 1. Alice writes from her iPhone: OpenBubbles shows it.
        post("Hi from my iPhone", "m1")
        waitFor("the iMessage in LightDecoder") { repo.messages(GUID).any { it.text == "Hi from my iPhone" } }
        assertEquals("+15551234567", repo.participants(GUID).single { !it.isMe }.number)
        assertTrue("its Reply button is held", iMessage.canReply(GUID))

        // 2. It belongs inside her Messages conversation.
        val messages = ArchiveRepository.get(context, ChatService.MESSAGES)
        val conversation = messages.conversation(MESSAGES_ID)
        assertEquals(GUID, BlueBubblesLinks.iMessageFor(context, conversation, messages.participants(MESSAGES_ID)))

        // 3. A reply goes through OpenBubbles' Reply button, with the text filled in.
        iMessage.sendText(GUID, "Hello back")
        waitFor("OpenBubbles to get the reply") { replies.contains("Hello back") }
        // OpenBubbles shows it again in its notification: not stored a second time.
        Thread.sleep(3_000)
        assertEquals(1, repo.messages(GUID).count { it.isOutgoing && it.text == "Hello back" })

        // 4. Reading it presses Mark As Read.
        runBlocking { iMessage.markRead(GUID) }
        assertTrue("Mark As Read pressed", markedRead.await(15, TimeUnit.SECONDS))

        // 5. The Messages list shows Alice once, and her conversation shows the iMessages, mine blue.
        ActivityScenario.launch<ConversationsActivity>(RelayIntents.conversations(context, ChatService.MESSAGES).setFlags(0)).use { scenario ->
            waitFor("the list") {
                var titles = emptyList<String>()
                scenario.onActivity { titles = (it.findViewById<RecyclerView>(R.id.list).adapter as ConversationsAdapter).currentList.map { c -> c.title } }
                titles.isNotEmpty()
            }
            scenario.onActivity { activity ->
                val items = (activity.findViewById<RecyclerView>(R.id.list).adapter as ConversationsAdapter).currentList
                assertEquals(listOf("Alice"), items.map { it.title })
                assertEquals(ChatService.MESSAGES, items.single().service)
            }
        }
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(context, MESSAGES_ID).setFlags(0)).use { scenario ->
            var rows = emptyList<com.lightdecoder.archive.MessageRow>()
            waitFor("the conversation") {
                scenario.onActivity { activity ->
                    rows = (activity.findViewById<RecyclerView>(R.id.messages).adapter as MessagesAdapter).currentList
                        .filterIsInstance<ThreadItem.Message>().map { it.row }
                }
                rows.any { it.text == "Hello back" }
            }
            scenario.onActivity { activity ->
                val adapter = activity.findViewById<RecyclerView>(R.id.messages).adapter as MessagesAdapter
                assertTrue(rows.any { it.text == "Hi from my iPhone" })
                assertTrue("my iMessage is blue", adapter.blueFor(rows.single { it.text == "Hello back" }))
            }
        }
        assertFalse(replies.isEmpty())
    }

    private companion object {
        const val FAKE = "com.bluebubbles.messaging.beta"
        const val GUID = "5D2B5A4C-1F0E-4C3B-9A7D-2E6F1B0C9D11"
        const val MESSAGES_ID = "bbtest-alice"
        const val REPLIED = "com.lightdecoder.fakebubbles.REPLIED"
        const val MARKED_READ = "com.lightdecoder.fakebubbles.MARKED_READ"
    }
}
