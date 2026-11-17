package com.lightdecoder.messenger

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.messenger.MessengerTestNotifications.Line
import com.lightdecoder.messenger.MessengerTestNotifications.chat
import com.lightdecoder.messenger.MessengerTestNotifications.person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** What is read out of a chat notification, and which notifications are not read at all. */
@RunWith(AndroidJUnit4::class)
class MessengerCaptureTest {

    private lateinit var context: Context
    private val pkg = MessengerCapture.MESSENGER_PACKAGE
    private val alice = person("Alice Example")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun aChatWithOnePersonIsReadWithWhoSaidWhatAndWhen() {
        val notification = chat(context, listOf(Line("Hi there", 1_000, alice), Line("Are you around?", 2_000, alice)))
        val captured = MessengerCapture.parse(pkg, "tag", notification, 9_000)!!
        assertEquals("Alice Example", captured.title)
        assertFalse(captured.isGroup)
        assertEquals(listOf("Hi there", "Are you around?"), captured.messages.map { it.text })
        assertEquals(listOf(1_000L, 2_000L), captured.messages.map { it.timestampMs })
        assertTrue(captured.messages.none { it.fromMe })
        assertEquals("Alice Example", captured.messages[0].sender)
    }

    @Test
    fun aGroupKeepsItsNameAndEachSender() {
        val bob = person("Bob")
        val notification = chat(
            context, listOf(Line("Dinner?", 1_000, alice), Line("Yes", 2_000, bob)), groupTitle = "Family",
        )
        val captured = MessengerCapture.parse(pkg, "tag", notification, 9_000)!!
        assertEquals("Family", captured.title)
        assertTrue(captured.isGroup)
        assertEquals(listOf("Alice Example", "Bob"), captured.messages.map { it.sender })
    }

    @Test
    fun theUsersOwnLinesAreMarkedAsTheirs() {
        val notification = chat(
            context,
            listOf(
                Line("Hi", 1_000, alice),
                Line("On my way", 2_000, null), // how a reply typed in the shade is shown
                Line("Five minutes", 3_000, MessengerTestNotifications.me),
            ),
        )
        val captured = MessengerCapture.parse(pkg, "tag", notification, 9_000)!!
        assertEquals(listOf(false, true, true), captured.messages.map { it.fromMe })
        assertEquals("the conversation is still named after the other person", "Alice Example", captured.title)
    }

    @Test
    fun aPictureIsNotedAsOneWithoutBeingRead() {
        val notification = chat(context, listOf(Line("", 1_000, alice, pictureMime = "image/jpeg")))
        val captured = MessengerCapture.parse(pkg, "tag", notification, 9_000)!!
        assertEquals(MessengerCapture.KIND_PICTURE, captured.messages.single().kind)
    }

    @Test
    fun otherAppsNotificationsAreNotRead() {
        val notification = chat(context, listOf(Line("Hi", 1_000, alice)))
        assertNull(MessengerCapture.parse("com.whatsapp", "tag", notification, 9_000))
        assertNull(MessengerCapture.parse("com.facebook.katana", "tag", notification, 9_000))
        assertNull(MessengerCapture.parse(null, "tag", notification, 9_000))
        assertNotNull(MessengerCapture.parse(pkg, "tag", notification, 9_000))
    }

    @Test
    fun summariesCallsAndPromotionsAreNotChats() {
        val lines = listOf(Line("Hi", 1_000, alice))
        val summary = chat(context, lines) { it.setGroup("g").setGroupSummary(true) }
        assertNull(MessengerCapture.parse(pkg, "tag", summary, 9_000))
        val call = chat(context, lines, category = Notification.CATEGORY_CALL)
        assertNull(MessengerCapture.parse(pkg, "tag", call, 9_000))
        val ongoing = chat(context, lines) { it.setOngoing(true) }
        assertNull(MessengerCapture.parse(pkg, "tag", ongoing, 9_000))
        val promo = chat(context, lines, category = Notification.CATEGORY_PROMO)
        assertNull(MessengerCapture.parse(pkg, "tag", promo, 9_000))
    }

    @Test
    fun aPlainNotificationCountsOnlyWhenMessengerCallsItAMessage() {
        fun plain(category: String?) = NotificationCompat.Builder(context, "chats")
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle("Carol")
            .setContentText("See you at 5")
            .setCategory(category)
            .setWhen(4_000)
            .build()
        val captured = MessengerCapture.parse(pkg, "tag", plain(Notification.CATEGORY_MESSAGE), 9_000)!!
        assertEquals("Carol", captured.title)
        assertEquals("See you at 5", captured.messages.single().text)
        assertEquals(4_000L, captured.messages.single().timestampMs)
        assertNull("a suggestion or reminder", MessengerCapture.parse(pkg, "tag", plain(null), 9_000))
        assertNull(MessengerCapture.parse(pkg, "tag", plain(Notification.CATEGORY_SOCIAL), 9_000))
    }

    @Test
    fun theSameConversationKeepsItsKeyAndAnotherGetsAnother() {
        val first = MessengerCapture.parse(pkg, "tagA", chat(context, listOf(Line("1", 1_000, alice)), shortcut = "thread-1"), 9_000)!!
        val again = MessengerCapture.parse(pkg, "tagB", chat(context, listOf(Line("2", 2_000, alice)), shortcut = "thread-1"), 9_500)!!
        val other = MessengerCapture.parse(pkg, "tagA", chat(context, listOf(Line("1", 1_000, alice)), shortcut = "thread-2"), 9_000)!!
        assertEquals(first.key, again.key)
        assertNotEquals(first.key, other.key)
        // Without a shortcut the tag tells them apart; the key never holds the raw id.
        val byTag = MessengerCapture.parse(pkg, "tagA", chat(context, listOf(Line("1", 1_000, alice)), shortcut = null), 9_000)!!
        val byOtherTag = MessengerCapture.parse(pkg, "tagB", chat(context, listOf(Line("1", 1_000, alice)), shortcut = null), 9_000)!!
        assertNotEquals(byTag.key, byOtherTag.key)
        assertFalse(first.key.contains("thread-1"))
    }

    @Test
    fun theReplyButtonIsFoundOnlyWhereTextCanBeTyped() {
        val withReply = chat(context, listOf(Line("Hi", 1_000, alice)), withReply = true)
        val reply = MessengerCapture.replyAction(withReply)
        assertNotNull(reply)
        assertEquals(MessengerTestNotifications.REPLY_KEY, reply!!.inputs.single().resultKey)

        val without = chat(context, listOf(Line("Hi", 1_000, alice)), withReply = false) {
            // A button with nothing to type into ("Mark as read", "Like").
            it.addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_view, "Mark as read", MessengerTestNotifications.replyAction(context, 7).actionIntent,
                ).build()
            )
        }
        assertNull(MessengerCapture.replyAction(without))
    }

    @Test
    fun aMessageShownAgainHasTheSameIdAndADifferentOneDoesNot() {
        val a = CapturedMessage("Alice", false, "Hi", MessengerCapture.KIND_TEXT, 1_000)
        assertEquals(MessengerCapture.messageId("k", a), MessengerCapture.messageId("k", a.copy()))
        assertNotEquals(MessengerCapture.messageId("k", a), MessengerCapture.messageId("k", a.copy(text = "Hi!")))
        assertNotEquals(MessengerCapture.messageId("k", a), MessengerCapture.messageId("k", a.copy(timestampMs = 1_001)))
        assertNotEquals(MessengerCapture.messageId("k", a), MessengerCapture.messageId("other", a))
    }
}
