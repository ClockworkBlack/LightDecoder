package com.lightdecoder.messenger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The Messenger inbox on the phone: what is kept, counted and removed. */
@RunWith(AndroidJUnit4::class)
class MessengerStoreTest {

    private lateinit var store: MessengerStore

    @Before
    fun setUp() {
        MessengerStore.resetForTesting()
        store = MessengerStore.get(ApplicationProvider.getApplicationContext<Context>())
        store.clear()
    }

    @After
    fun tearDown() {
        MessengerStore.resetForTesting()
    }

    private fun incoming(text: String, ts: Long, sender: String = "Alice") =
        CapturedMessage(sender, false, text, MessengerCapture.KIND_TEXT, ts)

    private fun mine(text: String, ts: Long) = CapturedMessage("", true, text, MessengerCapture.KIND_TEXT, ts)

    private fun alice(vararg messages: CapturedMessage) = CapturedConversation("alice", "Alice", false, messages.toList())

    @Test
    fun aNotificationShownAgainAddsNothingTwice() {
        assertEquals(1, store.record(alice(incoming("Hi", 1_000))))
        // The next update repeats the first line and adds one.
        assertEquals(1, store.record(alice(incoming("Hi", 1_000), incoming("You there?", 2_000))))
        assertEquals(0, store.record(alice(incoming("Hi", 1_000), incoming("You there?", 2_000))))
        assertEquals(listOf("Hi", "You there?"), store.messages("alice").map { it.text })
        val conversation = store.conversations().single()
        assertEquals("Alice", conversation.title)
        assertEquals("You there?", conversation.lastText)
        assertEquals(2_000L, conversation.lastTimestampMs)
        assertEquals("two unread, counted once each", 2, conversation.unread)
    }

    @Test
    fun readingClearsTheCountAndNewMessagesStartItAgain() {
        store.record(alice(incoming("Hi", 1_000)))
        store.markRead("alice")
        assertEquals(0, store.conversation("alice")!!.unread)
        store.record(alice(incoming("Hi", 1_000), incoming("Again", 2_000)))
        assertEquals(1, store.conversation("alice")!!.unread)
    }

    @Test
    fun theNewestConversationIsFirst() {
        store.record(alice(incoming("Hi", 1_000)))
        store.record(CapturedConversation("bob", "Bob", false, listOf(incoming("Yo", 5_000, "Bob"))))
        store.record(CapturedConversation("fam", "Family", true, listOf(incoming("Dinner", 3_000, "Mum"))))
        assertEquals(listOf("Bob", "Family", "Alice"), store.conversations().map { it.title })
        assertTrue(store.conversation("fam")!!.isGroup)
    }

    @Test
    fun aReplySentHereIsOneMessageOnceMessengerShowsIt() {
        store.record(alice(incoming("Hi", 1_000)))
        store.markRead("alice")
        store.addOutgoing("alice", "On my way", 10_000)
        assertEquals(MessengerStore.STATE_SENT_UNCONFIRMED, store.messages("alice").last().state)
        assertTrue(store.conversation("alice")!!.lastFromMe)

        // Messenger's notification now shows the reply as the user's own line, a second later.
        assertEquals(0, store.record(alice(incoming("Hi", 1_000), mine("On my way", 11_000))))
        val messages = store.messages("alice")
        assertEquals(listOf("Hi", "On my way"), messages.map { it.text })
        assertEquals(MessengerStore.STATE_SENT_CONFIRMED, messages.last().state)
        assertEquals("the user's own line is not unread", 0, store.conversation("alice")!!.unread)
        // And shown yet again, it is still one.
        store.record(alice(incoming("Hi", 1_000), mine("On my way", 11_000)))
        assertEquals(2, store.messages("alice").size)
    }

    @Test
    fun aLineTypedInMessengerItselfIsKeptAsItsOwnMessage() {
        store.record(alice(incoming("Hi", 1_000)))
        store.addOutgoing("alice", "From here", 10_000)
        store.record(alice(incoming("Hi", 1_000), mine("Typed in the shade", 11_000)))
        assertEquals(listOf("Hi", "From here", "Typed in the shade"), store.messages("alice").map { it.text })
        // The same words much later are another message, not the echo of the first.
        store.record(alice(mine("From here", 10_000 + 60 * 60 * 1000)))
        assertEquals(4, store.messages("alice").size)
    }

    @Test
    fun removingAConversationOrEverythingLeavesNothingBehind() {
        store.record(alice(incoming("Hi", 1_000)))
        store.record(CapturedConversation("bob", "Bob", false, listOf(incoming("Yo", 5_000, "Bob"))))
        store.deleteConversation("alice")
        assertNull(store.conversation("alice"))
        assertEquals(emptyList<MessengerMessage>(), store.messages("alice"))
        assertEquals(listOf("Bob"), store.conversations().map { it.title })
        store.clear()
        assertEquals(emptyList<MessengerConversation>(), store.conversations())
        assertEquals(emptyList<MessengerMessage>(), store.messages("bob"))
    }
}
