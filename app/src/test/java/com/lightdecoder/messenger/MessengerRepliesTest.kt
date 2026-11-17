package com.lightdecoder.messenger

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.messenger.MessengerTestNotifications.Line
import com.lightdecoder.messenger.MessengerTestNotifications.chat
import com.lightdecoder.messenger.MessengerTestNotifications.person
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * A reply goes out through the notification's own Reply button, with the
 * text filled in the way the notification shade fills it in, and no other way.
 */
@RunWith(AndroidJUnit4::class)
class MessengerRepliesTest {

    private lateinit var context: Context
    private val received = ArrayList<String>()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(MessengerTestNotifications.REPLY_KEY)
            received.add(typed?.toString() ?: "<nothing typed>")
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        MessengerReplies.clear()
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(MessengerTestNotifications.REPLY_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    @After
    fun tearDown() {
        context.unregisterReceiver(receiver)
        MessengerReplies.clear()
    }

    private fun replyOf(withReply: Boolean = true) =
        MessengerCapture.replyAction(chat(context, listOf(Line("Hi", 1_000, person("Alice"))), withReply = withReply))

    @Test
    fun theTypedTextIsHandedToTheNotificationsReplyButton() {
        MessengerReplies.update("alice", "0|pkg|1", replyOf(), null)
        assertTrue(MessengerReplies.canReply("alice"))
        assertEquals(MessengerReplies.Result.HANDED_OVER, MessengerReplies.send(context, "alice", "On my way"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("On my way"), received)
    }

    @Test
    fun withoutAReplyButtonNothingIsSent() {
        assertFalse(MessengerReplies.canReply("nobody"))
        assertEquals(MessengerReplies.Result.NO_REPLY_BUTTON, MessengerReplies.send(context, "nobody", "Hello"))
        MessengerReplies.update("alice", "0|pkg|1", replyOf(withReply = false), null)
        assertFalse(MessengerReplies.canReply("alice"))
        assertEquals(MessengerReplies.Result.NO_REPLY_BUTTON, MessengerReplies.send(context, "alice", "Hello"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<String>(), received)
    }

    @Test
    fun aReplyGoesOnlyToItsOwnConversation() {
        MessengerReplies.update("alice", "0|pkg|1", replyOf(), null)
        assertEquals(MessengerReplies.Result.NO_REPLY_BUTTON, MessengerReplies.send(context, "bob", "For Bob"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<String>(), received)
    }

    @Test
    fun anUpdateWithoutAButtonKeepsTheOneOfferedBefore() {
        MessengerReplies.update("alice", "0|pkg|1", replyOf(), null)
        MessengerReplies.update("alice", "0|pkg|1", null, null)
        assertTrue(MessengerReplies.canReply("alice"))
    }

    @Test
    fun aWithdrawnButtonIsReportedAndForgotten() {
        val reply = replyOf()!!
        MessengerReplies.update("alice", "0|pkg|1", reply, null)
        reply.intent.cancel()
        assertEquals(MessengerReplies.Result.WITHDRAWN, MessengerReplies.send(context, "alice", "Hello"))
        assertFalse(MessengerReplies.canReply("alice"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<String>(), received)
    }

    @Test
    fun clearingForgetsEveryButton() {
        MessengerReplies.update("alice", "0|pkg|1", replyOf(), null)
        MessengerReplies.forget("alice")
        assertFalse(MessengerReplies.canReply("alice"))
        MessengerReplies.update("alice", "0|pkg|1", replyOf(), null)
        MessengerReplies.clear()
        assertFalse(MessengerReplies.canReply("alice"))
    }
}
