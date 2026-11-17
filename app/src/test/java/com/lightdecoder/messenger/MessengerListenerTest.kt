package com.lightdecoder.messenger

import android.content.ComponentName
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.messenger.MessengerTestNotifications.Line
import com.lightdecoder.messenger.MessengerTestNotifications.chat
import com.lightdecoder.messenger.MessengerTestNotifications.person
import com.lightdecoder.messenger.MessengerTestNotifications.posted
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/** The listener: Messenger's notifications reach the inbox, nobody else's do. */
@RunWith(AndroidJUnit4::class)
class MessengerListenerTest {

    private lateinit var context: Context
    private lateinit var store: MessengerStore
    private lateinit var listener: MessengerNotificationListener
    private val alice = person("Alice")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        MessengerStore.resetForTesting()
        store = MessengerStore.get(context)
        store.clear()
        MessengerReplies.clear()
        MessengerPrefs.setPaused(context, false)
        listener = Robolectric.setupService(MessengerNotificationListener::class.java)
    }

    @After
    fun tearDown() {
        listener.onDestroy()
        MessengerReplies.clear()
        MessengerStore.resetForTesting()
    }

    /** The listener stores on its own thread; give it a moment, then report what is there. */
    private fun titlesAfter(expected: Int, timeoutMs: Long = 5_000): List<String> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (store.conversations().size >= expected) break
            Thread.sleep(20)
        }
        return store.conversations().map { it.title }
    }

    @Test
    fun aMessengerNotificationBecomesAConversationWithAReplyButton() {
        listener.onNotificationPosted(posted(chat(context, listOf(Line("Hi", 1_000, alice)))))
        assertEquals(listOf("Alice"), titlesAfter(1))
        val conversation = store.conversations().single()
        assertEquals("Hi", conversation.lastText)
        assertEquals(1, conversation.unread)
        assertTrue(MessengerReplies.canReply(conversation.key))
    }

    @Test
    fun anotherAppsNotificationIsDroppedUnread() {
        listener.onNotificationPosted(posted(chat(context, listOf(Line("Secret", 1_000, alice))), packageName = "com.whatsapp"))
        listener.onNotificationPosted(posted(chat(context, listOf(Line("Secret", 1_000, alice))), packageName = "com.google.android.apps.messaging"))
        // Something of Messenger's after them shows the worker got that far.
        listener.onNotificationPosted(posted(chat(context, listOf(Line("Hi", 2_000, person("Bob"))), shortcut = "thread-bob")))
        assertEquals(listOf("Bob"), titlesAfter(1))
        Thread.sleep(100)
        assertEquals(listOf("Bob"), store.conversations().map { it.title })
    }

    @Test
    fun whilePausedNothingIsReadOrKept() {
        MessengerPrefs.setPaused(context, true)
        listener.onNotificationPosted(posted(chat(context, listOf(Line("Hi", 1_000, alice)))))
        Thread.sleep(150)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<MessengerConversation>(), store.conversations())
        MessengerPrefs.setPaused(context, false)
        listener.onNotificationPosted(posted(chat(context, listOf(Line("Hi", 1_000, alice)))))
        assertEquals(listOf("Alice"), titlesAfter(1))
    }

    @Test
    fun accessIsWhatTheSystemSaysItIs() {
        assertFalse(MessengerNotificationListener.hasAccess(context))
        shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
            .setNotificationListenerAccessGranted(ComponentName(context, MessengerNotificationListener::class.java), true)
        assertTrue(MessengerNotificationListener.hasAccess(context))
    }
}
