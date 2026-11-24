package com.lightdecoder.relay

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.ChatService
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.wa.WaManager
import com.lightdecoder.wa.WaPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Message notifications, their channels and their buttons per service: the
 * two services never replace, cancel or answer each other's notifications,
 * and nothing of a WhatsApp id is readable from outside the app.
 */
@RunWith(AndroidJUnit4::class)
class MessageNotifierServicesTest {

    private lateinit var env: RelayTestEnv
    private lateinit var waDb: ArchiveDb
    private lateinit var waRepo: ArchiveRepository
    private val context: Context get() = env.context
    private val system: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        env = RelayTestEnv()
        context.getSharedPreferences(WaPrefs.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase(WA_DB_NAME)
        waDb = ArchiveDb(context, WA_DB_NAME)
        waRepo = ArchiveRepository.forTesting(waDb, ChatService.WHATSAPP)
        RelayPrefs.setNotifyMessages(context, true)
        WaPrefs.setNotifyMessages(context, true)
        for (service in ChatService.entries) {
            MessageNotifier.cancelAll(context, service)
            MessageNotifier.setVisibleConversationId(service, null)
        }
    }

    @After
    fun tearDown() {
        for (service in ChatService.entries) {
            MessageNotifier.cancelAll(context, service)
            MessageNotifier.setVisibleConversationId(service, null)
        }
        WaManager.resetForTesting()
        env.tearDown()
        waDb.close()
    }

    // --- channels ---------------------------------------------------------------------------

    @Test
    fun whatsAppsChannelsAppearOnlyOnceWhatsAppNeedsThem() {
        system.notificationChannels.forEach { system.deleteNotificationChannel(it.id) }
        RelayNotifications.ensureChannels(context)
        assertEquals(
            setOf(RelayNotifications.CHANNEL_STATUS, RelayNotifications.CHANNEL_ALERTS, RelayNotifications.CHANNEL_MESSAGES),
            system.notificationChannels.map { it.id }.toSet(),
        )

        assertTrue(RelayNotifications.statusVisible(context, ChatService.WHATSAPP))
        val status = system.getNotificationChannel(RelayNotifications.CHANNEL_WA_STATUS)
        val messages = system.getNotificationChannel(RelayNotifications.CHANNEL_WA_MESSAGES)
        assertEquals("WhatsApp link", status.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, status.importance)
        assertEquals("WhatsApp messages", messages.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_HIGH, messages.importance)

        assertEquals(RelayNotifications.CHANNEL_STATUS, RelayNotifications.statusChannel(ChatService.MESSAGES))
        assertEquals(RelayNotifications.CHANNEL_WA_STATUS, RelayNotifications.statusChannel(ChatService.WHATSAPP))
        assertEquals(RelayNotifications.CHANNEL_MESSAGES, RelayNotifications.messagesChannel(ChatService.MESSAGES))
        assertEquals(RelayNotifications.CHANNEL_WA_MESSAGES, RelayNotifications.messagesChannel(ChatService.WHATSAPP))
        assertEquals(RelayNotifications.ID_SERVICE, RelayNotifications.serviceNotificationId(ChatService.MESSAGES))
        assertNotEquals(RelayNotifications.ID_SERVICE, RelayNotifications.serviceNotificationId(ChatService.WHATSAPP))
    }

    @Test
    fun eachLinkIsVisibleByItsOwnChannel() {
        RelayNotifications.ensureChannels(context)
        RelayNotifications.ensureChannels(context, ChatService.WHATSAPP)
        assertTrue(RelayNotifications.statusVisible(context))
        assertTrue(RelayNotifications.statusVisible(context, ChatService.WHATSAPP))

        // The user turns the WhatsApp link's channel off: only that link may not run.
        system.getNotificationChannel(RelayNotifications.CHANNEL_WA_STATUS).importance = NotificationManager.IMPORTANCE_NONE
        assertFalse(RelayNotifications.statusVisible(context, ChatService.WHATSAPP))
        assertTrue(RelayNotifications.statusVisible(context))
        assertTrue(RelayNotifications.statusVisible(context, ChatService.MESSAGES))

        system.getNotificationChannel(RelayNotifications.CHANNEL_WA_STATUS).importance = NotificationManager.IMPORTANCE_LOW
        system.getNotificationChannel(RelayNotifications.CHANNEL_STATUS).importance = NotificationManager.IMPORTANCE_NONE
        assertTrue(RelayNotifications.statusVisible(context, ChatService.WHATSAPP))
        assertFalse(RelayNotifications.statusVisible(context))
    }

    // --- notifications ------------------------------------------------------------------------

    @Test
    fun eachServiceNotifiesOnItsOwnChannelUnderItsOwnTag() {
        MessageNotifier.onIncoming(context, env.repo, incoming("m1", "c1", "from Messages"))
        MessageNotifier.onIncoming(context, waRepo, incoming("W1", JID, "from WhatsApp"))

        val messages = active().single { it.notification.channelId == RelayNotifications.CHANNEL_MESSAGES }
        val whatsApp = active().single { it.notification.channelId == RelayNotifications.CHANNEL_WA_MESSAGES }
        assertEquals(2, active().size)
        assertEquals(listOf("from Messages"), texts(messages.notification))
        assertEquals(listOf("from WhatsApp"), texts(whatsApp.notification))
        assertNotEquals("an id per service", messages.id, whatsApp.id)

        // Google Messages: the conversation id, as before. WhatsApp: a stand-in, never the JID.
        assertEquals("c1", messages.tag)
        assertEquals(MessageNotifier.tagFor(context, ChatService.WHATSAPP, JID), whatsApp.tag)
        assertTrue(whatsApp.tag, whatsApp.tag.matches(Regex("wa:[0-9a-f]{32}")))
        assertFalse(whatsApp.tag.contains(NUMBER))

        // A second WhatsApp message joins its conversation's notification.
        MessageNotifier.onIncoming(context, waRepo, incoming("W2", JID, "and another"))
        assertEquals(2, active().size)
        assertEquals(
            listOf("from WhatsApp", "and another"),
            texts(active().single { it.notification.channelId == RelayNotifications.CHANNEL_WA_MESSAGES }.notification),
        )
    }

    @Test
    fun theSameConversationIdInBothServicesGivesTwoNotifications() {
        MessageNotifier.onIncoming(context, env.repo, incoming("m1", "c1", "from Messages"))
        MessageNotifier.onIncoming(context, waRepo, incoming("m1", "c1", "from WhatsApp"))
        assertEquals(2, active().size)

        MessageNotifier.cancel(context, "c1", ChatService.WHATSAPP)
        assertEquals(listOf("from Messages"), texts(active().single().notification))
        // Started afresh: the cancelled lines do not come back, and Messages' lines are not WhatsApp's.
        MessageNotifier.onIncoming(context, waRepo, incoming("m2", "c1", "again"))
        assertEquals(
            listOf("again"),
            texts(active().single { it.notification.channelId == RelayNotifications.CHANNEL_WA_MESSAGES }.notification),
        )

        MessageNotifier.cancel(context, "c1")
        assertEquals(RelayNotifications.CHANNEL_WA_MESSAGES, active().single().notification.channelId)
    }

    @Test
    fun cancelAllIsPerService() {
        MessageNotifier.onIncoming(context, env.repo, incoming("m1", "c1", "one"))
        MessageNotifier.onIncoming(context, env.repo, incoming("m2", "c2", "two"))
        MessageNotifier.onIncoming(context, waRepo, incoming("W1", JID, "three"))
        assertEquals(3, active().size)

        // What unpairing Google Messages does: WhatsApp's notification stays.
        MessageNotifier.cancelAll(context)
        assertEquals(RelayNotifications.CHANNEL_WA_MESSAGES, active().single().notification.channelId)

        MessageNotifier.onIncoming(context, env.repo, incoming("m3", "c1", "four"))
        MessageNotifier.cancelAll(context, ChatService.WHATSAPP)
        assertEquals(listOf("four"), texts(active().single().notification))
    }

    @Test
    fun eachServiceHasItsOwnSwitch() {
        assertTrue(MessageNotifier.enabled(context, ChatService.MESSAGES))
        assertTrue(MessageNotifier.enabled(context, ChatService.WHATSAPP))

        WaPrefs.setNotifyMessages(context, false)
        assertFalse(MessageNotifier.enabled(context, ChatService.WHATSAPP))
        MessageNotifier.onIncoming(context, waRepo, incoming("W1", JID, "quiet"))
        MessageNotifier.onIncoming(context, env.repo, incoming("m1", "c1", "loud"))
        assertEquals(listOf("loud"), texts(active().single().notification))

        MessageNotifier.cancelAll(context)
        WaPrefs.setNotifyMessages(context, true)
        RelayPrefs.setNotifyMessages(context, false)
        MessageNotifier.onIncoming(context, waRepo, incoming("W2", JID, "now loud"))
        MessageNotifier.onIncoming(context, env.repo, incoming("m2", "c1", "now quiet"))
        assertEquals(listOf("now loud"), texts(active().single().notification))

        // A reply shown after its service's switch went off removes that notification only.
        MessageNotifier.onReplySent(context, env.repo, "c1", "reply")
        assertEquals(1, active().size)
        WaPrefs.setNotifyMessages(context, false)
        MessageNotifier.onReplySent(context, waRepo, JID, "reply")
        assertTrue(active().isEmpty())
    }

    @Test
    fun theConversationOnScreenIsPerService() {
        assertNull(MessageNotifier.visibleConversationId)
        MessageNotifier.setVisibleConversationId(ChatService.WHATSAPP, "c1")
        assertNull("the WhatsApp screen is not the Messages one", MessageNotifier.visibleConversationId)
        assertEquals("c1", MessageNotifier.visibleConversationId(ChatService.WHATSAPP))

        MessageNotifier.onIncoming(context, waRepo, incoming("W1", "c1", "seen on screen"))
        assertTrue(active().isEmpty())
        MessageNotifier.onIncoming(context, env.repo, incoming("m1", "c1", "same id, other service"))
        assertEquals(RelayNotifications.CHANNEL_MESSAGES, active().single().notification.channelId)

        // The property is the Messages one, as the thread screen uses it.
        MessageNotifier.visibleConversationId = "c2"
        assertEquals("c2", MessageNotifier.visibleConversationId(ChatService.MESSAGES))
        assertEquals("c1", MessageNotifier.visibleConversationId(ChatService.WHATSAPP))
        MessageNotifier.visibleConversationId = null
        assertNull(MessageNotifier.visibleConversationId(ChatService.MESSAGES))
        assertEquals("c1", MessageNotifier.visibleConversationId(ChatService.WHATSAPP))
        MessageNotifier.setVisibleConversationId(ChatService.WHATSAPP, null)
        assertNull(MessageNotifier.visibleConversationId(ChatService.WHATSAPP))
    }

    // --- the notification's intents -------------------------------------------------------------

    @Test
    fun tappingOpensTheThreadOfTheRightService() {
        MessageNotifier.onIncoming(context, waRepo, incoming("W1", JID, "hello"))
        MessageNotifier.onIncoming(context, env.repo, incoming("m1", "c1", "hello"))

        for ((channel, service, conversationId) in listOf(
            Triple(RelayNotifications.CHANNEL_WA_MESSAGES, ChatService.WHATSAPP, JID),
            Triple(RelayNotifications.CHANNEL_MESSAGES, ChatService.MESSAGES, "c1"),
        )) {
            val notification = active().single { it.notification.channelId == channel }.notification
            val open = shadowOf(notification.contentIntent).savedIntent
            assertEquals(ThreadActivity::class.java.name, open.component!!.className)
            assertEquals(conversationId, open.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID))
            assertEquals(service, RelayIntents.serviceOf(open))

            val buttons = notification.actions.map { shadowOf(it.actionIntent).savedIntent } +
                shadowOf(notification.deleteIntent).savedIntent
            assertEquals(
                listOf(MessageNotifier.ACTION_REPLY, MessageNotifier.ACTION_MARK_READ, MessageNotifier.ACTION_DISMISSED),
                buttons.map { it.action },
            )
            for (button in buttons) {
                assertEquals(ComponentName(context, ReplyReceiver::class.java), button.component)
                assertEquals(conversationId, button.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID))
                assertEquals(service, RelayIntents.serviceOf(button))
                // What other apps and bug reports can see of a pending intent holds no WhatsApp id.
                assertFalse(button.dataString.orEmpty().contains(NUMBER))
            }
        }
    }

    @Test
    fun twoConversationsNeverShareAButton() {
        // Android tells pending intents apart by request code, action, component and data,
        // never by extras: with a shared one, a reply would go to the other conversation.
        MessageNotifier.onIncoming(context, env.repo, incoming("m1", "c1", "one"))
        MessageNotifier.onIncoming(context, env.repo, incoming("m2", "c2", "two"))
        MessageNotifier.onIncoming(context, waRepo, incoming("W1", "c1", "three"))
        MessageNotifier.onIncoming(context, waRepo, incoming("W2", JID, "four"))

        val replies = active().map { shadowOf(it.notification.actions[0].actionIntent).savedIntent }
        assertEquals(4, replies.size)
        for (a in replies) for (b in replies) {
            if (a !== b) assertFalse("$a and $b", a.filterEquals(b))
        }
        assertEquals(4, replies.map { it.data }.toSet().size)
    }

    // --- blocking (SPEC2 B3) ----------------------------------------------------------------------

    @Test
    fun aBlockedSenderOnGoogleMessagesOrSmsIsNotNotified() {
        com.lightdecoder.imessage.IMessageAddress.setRegionForTesting("US")
        val blocks = com.lightdecoder.chat.BlockList(java.io.File(context.filesDir, "blocked-notifier-test.json").apply { delete() })
        com.lightdecoder.chat.BlockList.installForTesting(blocks)
        try {
            // Google Messages names a participant id: matched by that participant's number.
            env.repo.upsertConversation(
                ConversationDto(
                    id = "c1", name = "Sam",
                    participants = listOf(ParticipantDto(id = "2", name = "Sam", number = "+15551234567", formattedNumber = "(555) 123-4567")),
                ),
            )
            // A text with no sender named, in a conversation with one person.
            env.repo.upsertConversation(
                ConversationDto(id = "c2", name = "Pat", participants = listOf(ParticipantDto(id = "7", name = "Pat", number = "+447700900123"))),
            )
            blocks.block("555-123-4567")
            blocks.block("+44 7700 900123")
            MessageNotifier.onIncoming(context, env.repo, incoming("m1", "c1", "hidden"))
            MessageNotifier.onIncoming(context, env.repo, incoming("m2", "c2", "hidden too").copy(senderId = ""))
            // Not even a mention lifts it.
            MessageNotifier.onIncoming(context, env.repo, incoming("m3", "c1", "hidden"), mentioned = true)
            assertTrue(active().isEmpty())
            // Unblocked: heard again.
            blocks.unblock("+15551234567")
            MessageNotifier.onIncoming(context, env.repo, incoming("m4", "c1", "heard"))
            assertEquals(listOf("heard"), texts(active().single().notification))
        } finally {
            com.lightdecoder.chat.BlockList.installForTesting(null)
            com.lightdecoder.imessage.IMessageAddress.resetForTesting()
        }
    }

    // --- the buttons ------------------------------------------------------------------------------

    @Test
    fun aReplyIsQueuedInItsOwnServiceAndShownInTheNotification() {
        val (manager, fake) = env.pairedManager()
        env.repo.upsertConversation(ConversationDto(id = "c1", name = "Sam"))
        MessageNotifier.onIncoming(context, env.repo, incoming("m1", "c1", "are you there?"))

        press(active().single().notification.actions[0], reply = "  on my way  ")
        manager.awaitIdleForTesting()

        val queued = env.repo.messages("c1").single { it.isLocal }
        assertEquals("on my way", queued.text)
        assertTrue("nothing is sent while the link is down", fake.sent.isEmpty())
        eventually("the reply in the notification") {
            texts(active().single().notification) == listOf("are you there?", "on my way")
        }
        assertTrue("nothing of it reached WhatsApp's archive", waRepo.conversations(folder = null).isEmpty())
    }

    @Test
    fun aWhatsAppButtonNeverReachesTheMessagesLink() {
        val (manager, fake) = env.pairedManager()
        WaManager.installForTesting(context, waRepo)
        // The same conversation id on both sides, to make a mix-up show.
        env.repo.upsertConversation(ConversationDto(id = "c1", name = "Sam", unread = true))
        waRepo.upsertConversation(ConversationDto(id = "c1", name = "Alex", unread = true))
        MessageNotifier.onIncoming(context, waRepo, incoming("W1", "c1", "hello from WhatsApp"))
        val notification = active().single().notification

        press(notification.actions[0], reply = "to Alex only")
        manager.awaitIdleForTesting()
        assertTrue("not queued for Sam", env.repo.messages("c1").isEmpty())
        assertTrue(fake.sent.isEmpty())
        // WhatsApp's side has dealt with it: the reply shows in its notification, or,
        // where it could not be queued, the notification is gone. Never left spinning.
        eventually("the reply handled") {
            active().isEmpty() || texts(active().single().notification).contains("to Alex only")
        }

        MessageNotifier.onIncoming(context, waRepo, incoming("W2", "c1", "hello again"))
        press(active().single().notification.actions[1])
        eventually("WhatsApp's conversation read") { !waRepo.conversation("c1")!!.unread }
        manager.awaitIdleForTesting()
        assertTrue("Sam's conversation is still unread", env.repo.conversation("c1")!!.unread)
        assertFalse(fake.calls.contains("markRead"))
        assertTrue("the notification is gone at once", active().isEmpty())
    }

    @Test
    fun aButtonWithoutAServiceIsAMessagesButton() {
        // A notification posted before the app knew two services (it survives an update).
        val (manager, _) = env.pairedManager()
        env.repo.upsertConversation(ConversationDto(id = "c1", name = "Sam", unread = true))
        MessageNotifier.onIncoming(context, env.repo, incoming("m1", "c1", "hello"))

        context.sendBroadcast(
            Intent(context, ReplyReceiver::class.java)
                .setAction(MessageNotifier.ACTION_MARK_READ)
                .putExtra(RelayIntents.EXTRA_CONVERSATION_ID, "c1")
        )
        shadowOf(Looper.getMainLooper()).idle()
        eventually("the conversation read") { !env.repo.conversation("c1")!!.unread }
        manager.awaitIdleForTesting()
        assertTrue(active().isEmpty())
    }

    // --- fixtures -----------------------------------------------------------------------------------

    private fun active(): List<StatusBarNotification> = system.activeNotifications.toList()

    private fun texts(notification: Notification): List<String> =
        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)!!
            .messages.map { it.text.toString() }

    /** Presses one of a notification's buttons, as the system does: its intent, with the typed reply if any. */
    private fun press(action: Notification.Action, reply: String? = null) {
        val intent = Intent(shadowOf(action.actionIntent).savedIntent)
        if (reply != null) {
            RemoteInput.addResultsToIntent(
                arrayOf(RemoteInput.Builder(MessageNotifier.KEY_REPLY_TEXT).build()),
                intent,
                Bundle().apply { putCharSequence(MessageNotifier.KEY_REPLY_TEXT, reply) },
            )
        }
        context.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    private fun incoming(id: String, conversationId: String, text: String) = MessageDto(
        id = id,
        conversationId = conversationId,
        senderId = "2",
        senderName = "Sam",
        timestampMs = System.currentTimeMillis(),
        direction = "in",
        status = "INCOMING_COMPLETE",
        text = text,
    )

    private companion object {
        const val WA_DB_NAME = "notifier-services-wa-test.db"
        const val NUMBER = "15550111"
        const val JID = "$NUMBER@s.whatsapp.net"
    }
}
