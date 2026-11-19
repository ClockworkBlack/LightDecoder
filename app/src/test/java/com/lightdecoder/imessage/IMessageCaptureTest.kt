package com.lightdecoder.imessage

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.imessage.OpenBubblesTestNotifications.ALICE_GUID
import com.lightdecoder.imessage.OpenBubblesTestNotifications.GROUP_GUID
import com.lightdecoder.imessage.OpenBubblesTestNotifications.Line
import com.lightdecoder.imessage.OpenBubblesTestNotifications.PACKAGE
import com.lightdecoder.imessage.OpenBubblesTestNotifications.chat
import com.lightdecoder.imessage.OpenBubblesTestNotifications.sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What OpenBubbles' notifications are read as. */
@RunWith(AndroidJUnit4::class)
class IMessageCaptureTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alice = sender("Alice", "tel:+15551234567")

    private fun parse(notification: Notification, packageName: String = PACKAGE, tag: String? = OpenBubbles.MESSAGE_TAG) =
        IMessageCapture.parse(packageName, tag, notification, 5_000L)

    @Test
    fun aOneToOneChatIsReadWithItsPersonAndAddress() {
        val captured = parse(chat(context, listOf(Line("Hi there", 1_700_000_000_000L, alice)), messageGuid = "m-42"))!!
        assertEquals(ALICE_GUID, captured.chatGuid)
        assertEquals("m-42", captured.messageGuid)
        assertEquals("Alice", captured.title)
        assertFalse(captured.isGroup)
        assertEquals("+15551234567", captured.address)
        val message = captured.messages.single()
        assertEquals("Hi there", message.text)
        assertEquals("Alice", message.sender)
        assertFalse(message.fromMe)
        assertEquals(1_700_000_000_000L, message.timestampMs)
    }

    @Test
    fun theReplyOpenBubblesAddsAfterSendingIsTheUsersOwn() {
        // Under the user's profile name, timed in seconds (InternalIntentReceiver).
        val notification = chat(
            context,
            listOf(Line("Hi there", 1_700_000_000_000L, alice), Line("Hello!", 1_700_000_005L, sender("Ryan"))),
        )
        val (incoming, reply) = parse(notification)!!.messages
        assertFalse(incoming.fromMe)
        assertTrue(reply.fromMe)
        assertEquals("", reply.sender)
        assertEquals(1_700_000_005_000L, reply.timestampMs)
    }

    @Test
    fun aGroupIsReadUnderItsName() {
        val captured = parse(
            chat(context, listOf(Line("Pizza?", 1_000_000_000_000L, sender("Bob"))), chatGuid = GROUP_GUID, groupTitle = "Friends"),
        )!!
        assertTrue(captured.isGroup)
        assertEquals("Friends", captured.title)
        assertEquals("", captured.address)
        assertEquals("Bob", captured.messages.single().sender)
    }

    @Test
    fun anEmailChatsAddressComesFromItsHandle() {
        val captured = parse(
            chat(context, listOf(Line("Yo", 1_000_000_000_000L, sender("Cara", "mailto:cara@example.com"))), chatGuid = "0F0E0D0C-0B0A-4909-8807-060504030201"),
        )!!
        assertEquals("cara@example.com", captured.address)
    }

    @Test
    fun otherNotificationsAreNotChats() {
        val ok = chat(context, listOf(Line("Hi", 1_000_000_000_000L, alice)))
        assertNull("another app", parse(ok, packageName = "com.whatsapp"))
        // Not every OpenBubbles build tags its message notifications the same: the chat id is what counts.
        assertNotNull("another tag", parse(ok, tag = "something-else"))
        assertNull("the summary's id", parse(chat(context, listOf(Line("Hi", 1_000_000_000_000L, alice)), chatGuid = "-1")))
        val summary = NotificationCompat.Builder(context, "c").setSmallIcon(android.R.drawable.sym_action_chat)
            .setGroup("g").setGroupSummary(true).build()
        assertNull("the group summary", parse(summary))
        val plain = NotificationCompat.Builder(context, "c").setSmallIcon(android.R.drawable.sym_action_chat).setContentTitle("Syncing").build()
        assertNull("no chat id", parse(plain))
    }

    @Test
    fun hiddenMessageTextIsNotKeptAsAMessage() {
        // OpenBubbles' "Hide Message Text": every line reads "iMessage".
        val hidden = chat(context, listOf(Line("iMessage", 1_000_000_000_000L, alice), Line("iMessage", 1_000_000_001_000L, alice)))
        val (chat, why) = IMessageCapture.parseOrWhyNot(PACKAGE, OpenBubbles.MESSAGE_TAG, hidden, 5_000L)
        assertNull(chat)
        assertEquals(IMessageCapture.HIDDEN_REASON, why)
        // Someone who really wrote "iMessage" among other things is kept.
        assertNotNull(parse(chat(context, listOf(Line("Do you have iMessage?", 1L, alice), Line("iMessage", 2L, alice)))))
    }

    @Test
    fun aChatNamedByANumberHasThatNumberAsItsAddress() {
        // Someone not in the contacts, and no handle on the person.
        val captured = parse(chat(context, listOf(Line("Hello?", 1_000_000_000_000L, sender("+1 (555) 222-3333")))))!!
        assertEquals("+1 (555) 222-3333", captured.address)
    }

    @Test
    fun withoutAChatGuidTheConversationShortcutNamesTheChat() {
        val n = chat(context, listOf(Line("Hi", 1_000_000_000_000L, alice)))
        n.extras.remove("chatGuid")
        val captured = parse(n, tag = null)!!
        assertEquals("the shortcut id is the chat's", ALICE_GUID, captured.chatGuid)
        n.extras.remove("android.shortcutId")
    }

    @Test
    fun whyANotificationIsNotUsedIsSaidWithoutAnythingInIt() {
        val plain = NotificationCompat.Builder(context, "com.bluebubbles.new_messages.$ALICE_GUID")
            .setSmallIcon(android.R.drawable.sym_action_chat).setContentTitle("Alice Secret").setContentText("meet at noon").build()
        val (chat, why) = IMessageCapture.parseOrWhyNot(PACKAGE, OpenBubbles.MESSAGE_TAG, plain, 5_000L)
        assertNull(chat)
        assertEquals("no chat id (no chatGuid extra, no conversation shortcut)", why)
        val withId = NotificationCompat.Builder(context, "x").setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle("Alice Secret").setContentText("meet at noon").setShortcutId(ALICE_GUID).build()
        assertEquals("not a chat-style (MessagingStyle) notification", IMessageCapture.parseOrWhyNot(PACKAGE, null, withId, 5_000L).second)
        val ok = chat(context, listOf(Line("meet at noon", 1_000_000_000_000L, alice)))
        assertEquals("", IMessageCapture.parseOrWhyNot(PACKAGE, OpenBubbles.MESSAGE_TAG, ok, 5_000L).second)

        val shape = IMessageCapture.shape(OpenBubbles.MESSAGE_TAG, 7, ok)
        assertTrue(shape, shape.contains("tag=message id=7"))
        assertTrue(shape, shape.contains("channel=(per-chat)"))
        assertTrue(shape, shape.contains("style=MessagingStyle"))
        assertTrue(shape, shape.contains("messages=1"))
        assertTrue(shape, shape.contains("shortcut=true"))
        assertTrue("its Reply takes text", shape.contains("+text]"))
        assertTrue("extras by name", shape.contains("chatGuid"))
        for (secret in listOf("Alice", "meet at noon", "5551234567", ALICE_GUID, ALICE_GUID.take(8))) {
            assertFalse("must not be in the shape: $secret", shape.contains(secret))
        }
        assertFalse(IMessageCapture.shape(null, 1, plain).contains("Secret"))
    }

    @Test
    fun itsReplyAndMarkAsReadButtonsAreFound() {
        val notification = chat(context, listOf(Line("Hi", 1_000_000_000_000L, alice)))
        val reply = IMessageCapture.replyAction(notification)
        assertNotNull(reply)
        assertEquals(OpenBubblesTestNotifications.REPLY_KEY, reply!!.inputs.single().resultKey)
        assertNotNull(IMessageCapture.markReadAction(notification))
        val bare = chat(context, listOf(Line("Hi", 1_000_000_000_000L, alice)), withActions = false)
        assertNull(IMessageCapture.replyAction(bare))
        assertNull(IMessageCapture.markReadAction(bare))
    }

    @Test
    fun aMessageKeepsItsIdWhenShownAgainAndOthersDoNot() {
        val a = CapturedIMessage("Alice", "", false, "Hi", 1_000L)
        assertEquals(IMessageCapture.messageId(ALICE_GUID, a), IMessageCapture.messageId(ALICE_GUID, a.copy()))
        assertNotEquals(IMessageCapture.messageId(ALICE_GUID, a), IMessageCapture.messageId(ALICE_GUID, a.copy(timestampMs = 2_000L)))
        assertNotEquals(IMessageCapture.messageId(ALICE_GUID, a), IMessageCapture.messageId(GROUP_GUID, a))
    }

    @Test
    fun aNotifyAnywaysPostHasNoAddressAndAPlainTitle() {
        // OpenBubbles leaves the person's handle out of it and prefixes the title.
        val notification = chat(context, listOf(Line("Urgent", 1_000_000_000_000L, sender("Alice"))))
        val captured = parse(notification)!!
        assertEquals("", captured.address)
        assertEquals("Alice", captured.title)
    }

    @Test
    fun addressesAreComparedLoosely() {
        assertEquals(IMessageAddress.key("+1 (555) 123-4567"), IMessageAddress.key("tel:5551234567"))
        assertEquals(IMessageAddress.key("Cara@Example.com"), IMessageAddress.key("mailto:cara@example.com"))
        assertNull(IMessageAddress.key("Alice"))
    }
}
