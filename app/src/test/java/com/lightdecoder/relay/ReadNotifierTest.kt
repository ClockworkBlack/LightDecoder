package com.lightdecoder.relay

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.chat.ChatService
import com.lightdecoder.wa.WaReceipt
import com.lightdecoder.wa.WaTestEnv
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** "Read" notifications: when someone reads a message the user sent. */
@RunWith(AndroidJUnit4::class)
class ReadNotifierTest {

    private lateinit var env: RelayTestEnv
    private val context: Context get() = env.context
    private val system: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        env = RelayTestEnv()
        context.getSharedPreferences("read_notify", Context.MODE_PRIVATE).edit().clear().commit()
        AppVisibility.started = 0
        system.cancelAll()
    }

    @After
    fun tearDown() {
        AppVisibility.started = 0
        system.cancelAll()
        env.tearDown()
    }

    private fun readNotifications() = system.activeNotifications.filter { it.notification.channelId == ReadNotifier.CHANNEL_READ }

    @Test
    fun theRulesFollowTheUsersChoices() {
        val now = System.currentTimeMillis()
        fun wanted(id: String = "c1", sent: Long = now - 60_000) = ReadNotifier.wanted(context, ChatService.MESSAGES, id, now, sent)
        assertTrue("on for everyone by default", wanted())
        assertFalse("not for a message read days after it was sent", wanted(sent = now - ReadNotifier.MAX_AGE_MS - 1))
        // Not while LightDecoder is on screen, unless asked for.
        AppVisibility.started = 1
        assertFalse(wanted())
        ReadNotifyPrefs.setWhileLooking(context, true)
        assertTrue(wanted())
        AppVisibility.started = 0
        // Off for one person, on for the others.
        ReadNotifyPrefs.setForConversation(context, ChatService.MESSAGES, "c1", false)
        assertFalse(wanted("c1"))
        assertTrue(wanted("c2"))
        assertTrue("another service's conversation of the same id is someone else",
            ReadNotifier.wanted(context, ChatService.WHATSAPP, "c1", now, now))
        ReadNotifyPrefs.setForConversation(context, ChatService.MESSAGES, "c1", true)
        assertTrue(wanted("c1"))
        // Off altogether.
        ReadNotifyPrefs.setEnabled(context, false)
        assertFalse(wanted())
    }

    @Test
    fun aMessageThePhoneSaysWasReadIsNotified() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        env.eventually(manager, what = "online") { manager.state.value.isOnline }
        env.repo.upsertConversation(
            ConversationDto(
                id = "c1", name = "Alice", folder = "inbox", lastMessageTs = System.currentTimeMillis(),
                participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = "p1", name = "Alice")),
            )
        )
        val sent = System.currentTimeMillis() - 60_000
        fun mine(status: String) = MessageDto(
            id = "m1", conversationId = "c1", fromMe = true, direction = "out", status = status, timestampMs = sent, text = "see you at 6",
        )
        fake.emitMessage(mine("OUTGOING_DELIVERED"))
        env.eventually(manager, what = "the message") { env.repo.message("m1")?.status == "OUTGOING_DELIVERED" }
        assertTrue("delivered is not read", readNotifications().isEmpty())

        fake.emitMessage(mine("OUTGOING_DISPLAYED"))
        env.eventually(manager, what = "read") { env.repo.message("m1")?.status == "OUTGOING_DISPLAYED" }
        manager.awaitIdleForTesting()
        val posted = readNotifications()
        assertEquals(1, posted.size)
        val extras = posted[0].notification.extras
        assertEquals("Alice read your message", extras.getCharSequence("android.title").toString())
        assertEquals("“see you at 6”", extras.getCharSequence("android.text").toString())

        // Told again that it was read: no second notification.
        system.cancelAll()
        fake.emitMessage(mine("OUTGOING_DISPLAYED"))
        manager.awaitIdleForTesting()
        assertTrue(readNotifications().isEmpty())
    }

    @Test
    fun notWhileLookingUnlessAskedAndNotForSomeoneTurnedOff() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        env.eventually(manager, what = "online") { manager.state.value.isOnline }
        val sent = System.currentTimeMillis() - 60_000
        fun send(id: String, conversationId: String, status: String) = fake.emitMessage(
            MessageDto(id = id, conversationId = conversationId, fromMe = true, direction = "out", status = status, timestampMs = sent, text = "hi $id")
        )
        for ((id, c) in listOf("a" to "c1", "b" to "c1", "c" to "c2")) send(id, c, "OUTGOING_DELIVERED")
        env.eventually(manager, what = "the messages") { env.repo.message("c") != null }

        // On screen: nothing.
        AppVisibility.started = 1
        send("a", "c1", "OUTGOING_DISPLAYED")
        env.eventually(manager, what = "read") { env.repo.message("a")?.status == "OUTGOING_DISPLAYED" }
        manager.awaitIdleForTesting()
        assertTrue(readNotifications().isEmpty())
        // Asked for even then: notified.
        ReadNotifyPrefs.setWhileLooking(context, true)
        send("b", "c1", "OUTGOING_DISPLAYED")
        env.eventually(manager, what = "read") { env.repo.message("b")?.status == "OUTGOING_DISPLAYED" }
        manager.awaitIdleForTesting()
        assertEquals(1, readNotifications().size)
        // Turned off for this person: nothing.
        system.cancelAll()
        ReadNotifyPrefs.setForConversation(context, ChatService.MESSAGES, "c2", false)
        send("c", "c2", "OUTGOING_DISPLAYED")
        env.eventually(manager, what = "read") { env.repo.message("c")?.status == "OUTGOING_DISPLAYED" }
        manager.awaitIdleForTesting()
        assertTrue(readNotifications().isEmpty())
    }

    @Test
    fun aWhatsAppReadReceiptIsNotified() {
        val wa = WaTestEnv()
        try {
            val (manager, fake) = wa.connectedManager()
            val chat = wa.conversation("15550111")
            fake.emitConversation(chat)
            fake.emitMessage(
                MessageDto(
                    id = "OUT1", conversationId = chat.id, fromMe = true, direction = "out", status = "OUTGOING_COMPLETE",
                    timestampMs = System.currentTimeMillis() - 30_000, text = "lunch?", transport = "WHATSAPP",
                )
            )
            wa.eventually(manager, what = "the message") { wa.repo.message("OUT1") != null }
            fake.emitReceipt(chat.id, listOf("OUT1"), WaReceipt.READ)
            wa.eventually(manager, what = "read") { wa.repo.message("OUT1")?.status == "OUTGOING_DISPLAYED" }
            manager.awaitIdleForTesting()
            val posted = readNotifications()
            assertEquals(1, posted.size)
            assertEquals("“lunch?”", posted[0].notification.extras.getCharSequence("android.text").toString())
            assertFalse("no WhatsApp id where other apps can read it", posted[0].tag.orEmpty().contains("15550111"))
        } finally {
            wa.tearDown()
        }
    }

    @Test
    fun theSettingsAndTheConversationMenuChangeIt() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        env.eventually(manager, what = "online") { manager.state.value.isOnline }
        env.repo.upsertConversation(
            ConversationDto(
                id = "c1", name = "Alice", folder = "inbox", lastMessageTs = 1_000,
                participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = "p1", name = "Alice")),
            )
        )
        // Settings: the switches.
        org.robolectric.Robolectric.buildActivity(com.lightdecoder.ui.relay.RelayStatusActivity::class.java).setup().use { controller ->
            val a = controller.get()
            val all = a.findViewById<android.widget.CompoundButton>(com.lightdecoder.R.id.switchReadNotify)
            val looking = a.findViewById<android.widget.CompoundButton>(com.lightdecoder.R.id.switchReadWhileLooking)
            assertTrue("on by default", all.isChecked)
            assertFalse("not while looking by default", looking.isChecked)
            looking.performClick()
            assertTrue(ReadNotifyPrefs.whileLooking(context))
            all.performClick()
            assertFalse(ReadNotifyPrefs.enabled(context))
            assertFalse("nothing to choose while it is off", looking.isEnabled)
            all.performClick()
            assertTrue(ReadNotifyPrefs.enabled(context))
        }
        // The conversation's menu: off for this person, and back on.
        org.robolectric.Robolectric.buildActivity(
            com.lightdecoder.ui.chat.ThreadActivity::class.java, RelayIntents.thread(context, "c1").setFlags(0),
        ).setup().use { controller ->
            val a = controller.get()
            repeat(5) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
            val shadow = org.robolectric.Shadows.shadowOf(a)
            assertTrue(shadow.clickMenuItem(com.lightdecoder.R.id.action_notify_read))
            assertFalse(ReadNotifyPrefs.forConversation(context, ChatService.MESSAGES, "c1"))
            assertTrue(shadow.clickMenuItem(com.lightdecoder.R.id.action_notify_read))
            assertTrue(ReadNotifyPrefs.forConversation(context, ChatService.MESSAGES, "c1"))
        }
    }
}
