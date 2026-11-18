package com.lightdecoder.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.RelayManager
import com.lightdecoder.relay.RelayState
import com.lightdecoder.relay.RelayTestEnv
import com.lightdecoder.ui.chat.ConversationsActivity
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.wa.WaManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shared API the conversation screens are written against: the services,
 * their backends, and the intents that say which service a screen is for.
 */
@RunWith(AndroidJUnit4::class)
class ChatFoundationTest {

    private lateinit var env: RelayTestEnv
    private lateinit var waDb: ArchiveDb
    private lateinit var waRepo: ArchiveRepository

    @Before
    fun setUp() {
        env = RelayTestEnv()
        env.context.deleteDatabase(WA_DB_NAME)
        waDb = ArchiveDb(env.context, WA_DB_NAME)
        waRepo = ArchiveRepository.forTesting(waDb, ChatService.WHATSAPP)
    }

    @After
    fun tearDown() {
        WaManager.resetForTesting()
        com.lightdecoder.mail.MailManager.resetForTesting()
        env.tearDown()
        waDb.close()
    }

    @Test
    fun aServicesKeyNamesItAndAnythingElseIsMessages() {
        assertEquals("messages", ChatService.MESSAGES.key)
        assertEquals("whatsapp", ChatService.WHATSAPP.key)
        for (service in ChatService.entries) assertEquals(service, ChatService.fromKey(service.key))
        // An intent from before there was a second service carries no key.
        assertEquals(ChatService.MESSAGES, ChatService.fromKey(null))
        assertEquals(ChatService.MESSAGES, ChatService.fromKey(""))
        assertEquals(ChatService.MESSAGES, ChatService.fromKey("WHATSAPP"))
    }

    @Test
    fun eachServiceHasItsOwnBackend() {
        val messages = env.manager()
        val whatsApp = WaManager.installForTesting(env.context, waRepo)

        assertSame(messages, ChatBackends.get(env.context, ChatService.MESSAGES))
        assertSame(whatsApp, ChatBackends.get(env.context, ChatService.WHATSAPP))
        val gmail = ChatBackends.get(env.context, ChatService.GMAIL)
        assertEquals(listOf(messages, whatsApp, gmail), ChatBackends.all(env.context))
        assertEquals(ChatService.GMAIL, gmail.service)
        for (backend in ChatBackends.all(env.context)) {
            assertSame("the backend of its own service", backend, ChatBackends.get(env.context, backend.service))
            assertEquals("over its own archive", backend.service, backend.repo.service)
        }
        assertNotSame(messages.repo, whatsApp.repo)
    }

    @Test
    fun theMessagesLinkIsABackendWithEverythingButPresenceAndDeleteForEveryone() {
        val manager: ChatBackend = env.manager()
        assertTrue(manager is RelayManager)
        assertEquals(ChatService.MESSAGES, manager.service)
        assertSame(env.repo, manager.repo)
        assertEquals(
            setOf(
                Capability.SPAM_FOLDER, Capability.DELETED_FOLDER, Capability.FULL_SIZE_REQUEST,
                Capability.USE_HERE, Capability.SUBJECT, Capability.NEW_GROUP,
            ),
            manager.capabilities,
        )
        assertEquals(Capability.entries.toSet() - setOf(Capability.PRESENCE, Capability.DELETE_FOR_EVERYONE, Capability.EDIT), manager.capabilities)

        // Presence does not exist for Google Messages: nothing to show, nothing happens.
        assertTrue(manager.presence.value.isEmpty())
        manager.watchPresence("c1")
        manager.setAppVisible(true)
        manager.setAppVisible(false)
        assertTrue(manager.presence.value.isEmpty())
        assertEquals(RelayState.Phase.UNPAIRED, manager.state.value.phase)
        assertFalse(manager.isPaired())
    }

    @Test
    fun aSendThroughTheInterfaceIsTheManagersOwnSend() {
        val (manager, fake) = env.pairedManager()
        env.repo.upsertConversation(ConversationDto(id = "c1", name = "Sam"))
        val backend: ChatBackend = ChatBackends.get(env.context, ChatService.MESSAGES)

        // The interface's defaults (no reply, no caption) are the ones the manager had.
        val tmpId = backend.sendText("c1", "hello")
        manager.awaitIdleForTesting()
        val local = env.repo.messages("c1").single()
        assertEquals("tmp:$tmpId", local.id)
        assertEquals("hello", local.text)
        assertEquals("", local.replyToId)
        assertTrue("nothing goes out while the link is down", fake.sent.isEmpty())
        assertEquals(0, runBlocking { backend.purgeDeleted(null) })
    }

    @Test
    fun intentsSayWhichServiceTheyAreFor() {
        val context = env.context
        val jid = "15550111@s.whatsapp.net"

        val thread = RelayIntents.thread(context, jid, "M1", ChatService.WHATSAPP)
        assertEquals(ThreadActivity::class.java.name, thread.component!!.className)
        assertEquals(jid, thread.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID))
        assertEquals("M1", thread.getStringExtra(RelayIntents.EXTRA_MESSAGE_ID))
        assertEquals("whatsapp", thread.getStringExtra(RelayIntents.EXTRA_SERVICE))
        assertEquals(ChatService.WHATSAPP, RelayIntents.serviceOf(thread))

        // Without a service it is Google Messages, as every caller so far means.
        val plain = RelayIntents.thread(context, "c1")
        assertEquals("c1", plain.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID))
        assertNull(plain.getStringExtra(RelayIntents.EXTRA_MESSAGE_ID))
        assertEquals(ChatService.MESSAGES, RelayIntents.serviceOf(plain))
        assertEquals(ChatService.MESSAGES, RelayIntents.serviceOf(RelayIntents.thread(context, "c1", "m1")))

        assertEquals(ConversationsActivity::class.java.name, RelayIntents.conversations(context).component!!.className)
        assertEquals(ChatService.MESSAGES, RelayIntents.serviceOf(RelayIntents.conversations(context)))
        assertEquals(ChatService.WHATSAPP, RelayIntents.serviceOf(RelayIntents.conversations(context, ChatService.WHATSAPP)))

        // An intent that never heard of services (an old notification, a launcher start) is Messages.
        assertEquals(ChatService.MESSAGES, RelayIntents.serviceOf(android.content.Intent()))
        assertEquals(ChatService.MESSAGES, RelayIntents.serviceOf(null))
        assertEquals("com.lightdecoder.extra.SERVICE", RelayIntents.EXTRA_SERVICE)
    }

    private companion object {
        const val WA_DB_NAME = "chat-foundation-wa-test.db"
    }
}
