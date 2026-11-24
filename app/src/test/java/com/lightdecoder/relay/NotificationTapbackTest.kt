package com.lightdecoder.relay

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.IMessagePrefs
import com.lightdecoder.imessage.direct.DirectArchive
import com.lightdecoder.imessage.direct.DirectLibrary
import com.lightdecoder.imessage.direct.DirectLink
import com.lightdecoder.imessage.direct.DirectPrefs
import com.lightdecoder.imessage.direct.DirectTexts
import com.lightdecoder.imessage.direct.DirectWire
import com.lightdecoder.imessage.direct.Handles
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.IndexedMessagePart
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MessagePart
import uniffi.rust_bridge.MessageType
import uniffi.rust_bridge.NormalMessage
import uniffi.rust_bridge.PushChatMessage
import uniffi.rust_bridge.TextFlags
import uniffi.rust_bridge.TextFormat
import java.io.File
import java.util.Collections

/**
 * A tapback from an iMessage's notification: the six as Reply's choices (never the system's own
 * suggestions), and only a chosen one of them, on the message the notification named, after every
 * check, becomes a tapback (through the same REACT as in the chat).
 */
@RunWith(AndroidJUnit4::class)
class NotificationTapbackTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val system: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private lateinit var db: ArchiveDb
    private lateinit var repo: ArchiveRepository
    private val me = "tel:+15550000001"
    private val bob = "tel:+15559876543"
    private val chat = "imsg:$bob"
    private val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())

    @Before
    fun setUp() {
        context.deleteDatabase(DB)
        db = ArchiveDb(context, DB)
        repo = ArchiveRepository.forTesting(db, ChatService.IMESSAGE)
        ArchiveRepository.installForTesting(repo)
        IMessageManager.installForTesting(context, repo)
        IMessagePrefs.setEnabled(context, true)
        IMessagePrefs.setNotifyMessages(context, true)
        DirectPrefs.setRegistered(context, true)
        DirectLibrary.REQUIRED.forEach { File(DirectLibrary.dir(context), it).apply { parentFile?.mkdirs(); writeText("") } }
        DirectLink.installForTesting(context) { what, request, _ ->
            asked += what to request
            DirectWire.ok()
        }
        MessageNotifier.cancelAll(context, ChatService.IMESSAGE)
        MessageNotifier.setVisibleConversationId(ChatService.IMESSAGE, null)
    }

    @After
    fun tearDown() {
        MessageNotifier.reactButton = false
        MessageNotifier.cancelAll(context, ChatService.IMESSAGE)
        com.lightdecoder.chat.BlockList.installForTesting(null)
        IMessagePrefs.setEnabled(context, false)
        DirectPrefs.setRegistered(context, false)
        DirectLink.resetForTesting()
        IMessageManager.resetForTesting()
        ArchiveRepository.resetForTesting()
        db.close()
    }

    private fun incoming(guid: String, text: String, from: String = bob) = DirectArchive.map(
        MessageInst(
            guid, from, ConversationData(listOf(me, from), null, null, null),
            PushChatMessage.Message(
                NormalMessage(
                    listOf(IndexedMessagePart(MessagePart.Text(text, TextFormat.Flags(TextFlags(false, false, false, false))), null, null)),
                    null, null, null, MessageType.IMessage, null, null, null, false, null, null,
                ),
            ),
            System.currentTimeMillis().toULong(), null, false, false, null,
        ),
        setOf(me), Handles::display, DirectTexts(context),
    ).let { DirectWire.decode(JSONObject(DirectWire.encode(it).toString())) }

    private fun notification(): Notification = system.activeNotifications.single().notification

    private fun reply(n: Notification) = n.actions.first { it.title.toString() == context.getString(com.lightdecoder.R.string.relay_notify_reply) }

    private fun texts(n: Notification): List<String> =
        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)!!.messages.map { it.text.toString() }

    /** Reply pressed, as the system does: its intent with [text], chosen from the chips or typed. */
    private fun press(action: Notification.Action, text: String, chosen: Boolean, change: (Intent) -> Unit = {}) {
        val intent = Intent(shadowOf(action.actionIntent).savedIntent).also(change)
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(MessageNotifier.KEY_REPLY_TEXT).build()), intent,
            Bundle().apply { putCharSequence(MessageNotifier.KEY_REPLY_TEXT, text) },
        )
        RemoteInput.setResultsSource(intent, if (chosen) RemoteInput.SOURCE_CHOICE else RemoteInput.SOURCE_FREE_FORM_INPUT)
        context.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun requests(what: Int) = synchronized(asked) { asked.filter { it.first == what }.map { it.second } }

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    /** Nothing happens for a while: no tapback goes. */
    private fun noTapback() {
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(requests(DirectWire.REACT).isEmpty())
    }

    @Test
    fun anImessagesReplyOffersTheSixTapbacksAndNoSuggestionsOfTheSystems() {
        DirectLink.get(context).apply(incoming("G1", "hi"))
        val n = notification()
        val reply = reply(n)
        val input = reply.remoteInputs.single()
        assertEquals(MessageNotifier.TAPBACK_CHOICES, input.choices.map { it.toString() })
        assertFalse(reply.allowGeneratedReplies)
        // The message it goes on: always named on the intent, so a fill-in cannot add one.
        assertEquals("imsg:G1", shadowOf(reply.actionIntent).savedIntent.getStringExtra(MessageNotifier.EXTRA_TARGET_MESSAGE))
        // Mark as read stays immutable; there is no third button by default.
        val markRead = n.actions.first { it.title.toString() == context.getString(com.lightdecoder.R.string.relay_notify_mark_read) }
        assertTrue(shadowOf(markRead.actionIntent).flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertEquals(2, n.actions.size)
    }

    @Test
    fun withoutTheLibraryThereAreNoChoicesAndTheTargetIsEmpty() {
        DirectLink.get(context).apply(incoming("G1", "hi"))
        DirectPrefs.setRegistered(context, false)
        // Posted again (a reply shown in it), now that the library is not set up.
        MessageNotifier.onReplySent(context, repo, chat, "ok")
        val reply = reply(notification())
        assertTrue(reply.remoteInputs.single().choices.isNullOrEmpty())
        assertEquals("", shadowOf(reply.actionIntent).savedIntent.getStringExtra(MessageNotifier.EXTRA_TARGET_MESSAGE))
    }

    @Test
    fun aChosenTapbackGoesAsATapbackAndIsShown() {
        DirectLink.get(context).apply(incoming("G1", "hi"))
        press(reply(notification()), DirectArchive.Tapback.LOVE, chosen = true)
        eventually("the tapback") { requests(DirectWire.REACT).isNotEmpty() }
        val sent = requests(DirectWire.REACT).single()
        assertEquals("G1", sent.getString("guid"))
        assertEquals(DirectArchive.Tapback.LOVE, sent.getString("emoji"))
        assertTrue(requests(DirectWire.SEND).isEmpty())
        eventually("shown in the notification") { texts(notification()).last() == "Loved “hi”" }
        // The chat is not marked read.
        assertTrue(repo.conversation(chat)!!.unread)
    }

    @Test
    fun aTypedHeartStaysAText() {
        DirectLink.get(context).apply(incoming("G1", "hi"))
        press(reply(notification()), DirectArchive.Tapback.LOVE, chosen = false)
        eventually("the reply") { repo.messages(chat).any { it.isOutgoing && it.text == DirectArchive.Tapback.LOVE } }
        assertTrue(requests(DirectWire.REACT).isEmpty())
    }

    @Test
    fun anythingThatFailsACheckIsNothing() {
        val link = DirectLink.get(context)
        link.apply(incoming("G1", "hi"))
        // Not one of the six.
        press(reply(notification()), "🙂", chosen = true)
        noTapback()
        assertFalse(repo.messages(chat).any { it.isOutgoing })
        // Another chat's message named.
        link.apply(incoming("H1", "other", from = "tel:+15551112222"))
        link.apply(incoming("G2", "hi again"))
        press(reply(system.activeNotifications.first { texts(it.notification).contains("hi again") }.notification), DirectArchive.Tapback.LIKE, chosen = true) {
            it.putExtra(MessageNotifier.EXTRA_TARGET_MESSAGE, "imsg:H1")
        }
        noTapback()
        // No target (no tapbacks offered): the system's suggested reply, sent as the text it is, as before.
        link.apply(incoming("G3", "and again"))
        press(reply(system.activeNotifications.first { texts(it.notification).contains("and again") }.notification), DirectArchive.Tapback.LIKE, chosen = true) {
            it.putExtra(MessageNotifier.EXTRA_TARGET_MESSAGE, "")
        }
        noTapback()
        eventually("the suggested reply as text") { repo.messages(chat).any { it.isOutgoing && it.text == DirectArchive.Tapback.LIKE } }
        // The user's own message.
        repo.upsertMessage(
            MessageDto(id = "imsg:MINE", conversationId = chat, fromMe = true, timestampMs = 1, direction = "out", transport = "IMESSAGE", text = "mine"),
        )
        link.apply(incoming("G4", "once more"))
        press(reply(system.activeNotifications.first { texts(it.notification).contains("once more") }.notification), DirectArchive.Tapback.LIKE, chosen = true) {
            it.putExtra(MessageNotifier.EXTRA_TARGET_MESSAGE, "imsg:MINE")
        }
        noTapback()
    }

    @Test
    fun notForAnotherServiceNorSomeoneBlocked() {
        val link = DirectLink.get(context)
        link.apply(incoming("G1", "hi"))
        // The same intent, said to be Google Messages': its choice is the system's suggested reply, never a tapback.
        assertNull(ReplyReceiver.tapbackRow(context, ChatService.MESSAGES, chat, "imsg:G1", DirectArchive.Tapback.LOVE))
        assertTrue(ReplyReceiver.tapbackRow(context, ChatService.IMESSAGE, chat, "imsg:G1", DirectArchive.Tapback.LOVE) != null)
        com.lightdecoder.imessage.IMessageAddress.setRegionForTesting("US")
        try {
            val blocks = com.lightdecoder.chat.BlockList(File(context.filesDir, "blocked-tapback-test.json").apply { delete() })
            com.lightdecoder.chat.BlockList.installForTesting(blocks)
            blocks.block("+15559876543")
            assertNull(ReplyReceiver.tapbackRow(context, ChatService.IMESSAGE, chat, "imsg:G1", DirectArchive.Tapback.LOVE))
            press(reply(notification()), DirectArchive.Tapback.LOVE, chosen = true)
            noTapback()
        } finally {
            com.lightdecoder.imessage.IMessageAddress.resetForTesting()
        }
    }

    @Test
    fun theFallbackButtonIsImmutableAndOpensTheScreenThatIsNotExported() {
        MessageNotifier.reactButton = true
        DirectLink.get(context).apply(incoming("G1", "hi"))
        val n = notification()
        assertTrue("no chips with the button", reply(n).remoteInputs.single().choices.isNullOrEmpty())
        val react = n.actions.first { it.title.toString() == context.getString(com.lightdecoder.R.string.tapback_react_action) }
        val pending = shadowOf(react.actionIntent)
        assertTrue(pending.isActivityIntent)
        assertTrue(pending.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertEquals(com.lightdecoder.ui.chat.TapbackActivity::class.java.name, pending.savedIntent.component!!.className)
        assertNull("no message text in it", pending.savedIntent.extras!!.keySet().firstOrNull { pending.savedIntent.extras!!.get(it) == "hi" })
        assertTrue(react.isAuthenticationRequired)
    }

    private companion object {
        const val DB = "notification-tapback-test.db"
    }
}
