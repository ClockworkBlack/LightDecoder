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
        val captured = parse(chat(context, listOf(Line("Hi there", 1_700_000_000_000L, alice))))!!
        assertEquals(ALICE_GUID, captured.chatGuid)
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
            chat(context, listOf(Line("Yo", 1_000_000_000_000L, sender("Cara", "mailto:cara@example.com"))), chatGuid = "iMessage;-;cara@example.com"),
        )!!
        assertEquals("cara@example.com", captured.address)
    }

    @Test
    fun otherNotificationsAreNotChats() {
        val ok = chat(context, listOf(Line("Hi", 1_000_000_000_000L, alice)))
        assertNull("another app", parse(ok, packageName = "com.whatsapp"))
        assertNull("another tag", parse(ok, tag = "something-else"))
        assertNull("a text-message chat", parse(chat(context, listOf(Line("Hi", 1_000_000_000_000L, alice)), chatGuid = "SMS;-;+15551234567")))
        val summary = NotificationCompat.Builder(context, "c").setSmallIcon(android.R.drawable.sym_action_chat)
            .setGroup("g").setGroupSummary(true).build()
        assertNull("the group summary", parse(summary))
        val plain = NotificationCompat.Builder(context, "c").setSmallIcon(android.R.drawable.sym_action_chat).setContentTitle("Syncing").build()
        assertNull("no chat id", parse(plain))
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
    fun chatIdsAndAddresses() {
        assertEquals("+15551234567", OpenBubbles.addressOf(ALICE_GUID))
        assertNull(OpenBubbles.addressOf(GROUP_GUID))
        assertTrue(OpenBubbles.isIMessageChat(ALICE_GUID))
        assertFalse(OpenBubbles.isIMessageChat("SMS;-;+1555"))
        assertEquals(IMessageAddress.key("+1 (555) 123-4567"), IMessageAddress.key("tel:5551234567"))
        assertEquals(IMessageAddress.key("Cara@Example.com"), IMessageAddress.key("mailto:cara@example.com"))
        assertNull(IMessageAddress.key("Alice"))
    }
}
