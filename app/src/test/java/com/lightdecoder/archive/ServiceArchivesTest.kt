package com.lightdecoder.archive

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.chat.ChatService
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.PartDto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * Each messaging service has an archive of its own: its own database, its
 * own media directories, and changes announced under its own name. Nothing
 * one archive does may reach the other's rows or files.
 */
@RunWith(AndroidJUnit4::class)
class ServiceArchivesTest {

    private lateinit var context: Context
    private lateinit var messagesDb: ArchiveDb
    private lateinit var whatsAppDb: ArchiveDb
    private lateinit var messages: ArchiveRepository
    private lateinit var whatsApp: ArchiveRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(MESSAGES_DB)
        context.deleteDatabase(WHATSAPP_DB)
        messagesDb = ArchiveDb(context, MESSAGES_DB)
        whatsAppDb = ArchiveDb(context, WHATSAPP_DB)
        messages = ArchiveRepository.forTesting(messagesDb)
        whatsApp = ArchiveRepository.forTesting(whatsAppDb, ChatService.WHATSAPP)
    }

    @After
    fun tearDown() {
        ArchiveRepository.resetForTesting()
        messagesDb.close()
        whatsAppDb.close()
    }

    @Test
    fun anArchiveKnowsItsServiceAndOldCallersGetMessages() {
        assertEquals(ChatService.MESSAGES, messages.service)
        assertEquals(ChatService.WHATSAPP, whatsApp.service)
        assertEquals("relay-archive.db", ArchiveDb.nameFor(ChatService.MESSAGES))
        assertEquals("whatsapp-archive.db", ArchiveDb.nameFor(ChatService.WHATSAPP))
        assertEquals(ArchiveDb.NAME, ArchiveDb.nameFor(ChatService.MESSAGES))
        // A change built without a service, as all were, is a Messages change.
        assertEquals(ChatService.MESSAGES, ArchiveChange("c1", ArchiveChange.Kind.MESSAGES).service)
    }

    @Test
    fun theSameIdsInBothServicesAreDifferentThings() {
        messages.upsertConversation(ConversationDto(id = SAME_ID, name = "Sam"))
        messages.upsertMessage(message("m1", text = "from Messages"))
        whatsApp.upsertConversation(ConversationDto(id = SAME_ID, name = "Alex"))
        whatsApp.upsertMessage(message("m1", text = "from WhatsApp"))

        assertEquals("Sam", messages.conversation(SAME_ID)!!.name)
        assertEquals("Alex", whatsApp.conversation(SAME_ID)!!.name)
        assertEquals("from Messages", messages.message("m1")!!.text)
        assertEquals("from WhatsApp", whatsApp.message("m1")!!.text)
        assertEquals(listOf("m1"), messages.search("Messages").map { it.messageId })
        assertTrue("one service's search never finds the other's messages", messages.search("WhatsApp").isEmpty())

        whatsApp.deleteConversationLocal(SAME_ID)
        assertNull(whatsApp.conversation(SAME_ID))
        assertNotNull(messages.conversation(SAME_ID))
        assertEquals(1, messages.messages(SAME_ID).size)
    }

    @Test
    fun everyChangeSaysWhoseArchiveItWas() {
        val seen = ArrayList<ArchiveChange>()
        val listener: (ArchiveChange) -> Unit = { seen += it }
        ArchiveEvents.register(listener)
        try {
            messages.upsertMessage(message("m1"))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(seen.isNotEmpty())
            assertTrue(seen.toString(), seen.all { it.service == ChatService.MESSAGES })

            seen.clear()
            whatsApp.upsertMessage(message("m1"))
            whatsApp.markConversationRead(SAME_ID)
            whatsApp.updateHealth(status = "connected")
            whatsApp.clearAll()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(seen.toString(), seen.all { it.service == ChatService.WHATSAPP })
            assertEquals("every kind of change", ArchiveChange.Kind.entries.toSet(), seen.map { it.kind }.toSet())
            // What a Messages screen compares: the same conversation id of the other service is not its conversation.
            assertFalse(seen.contains(ArchiveChange(SAME_ID, ArchiveChange.Kind.MESSAGES)))
            assertTrue(seen.contains(ArchiveChange(SAME_ID, ArchiveChange.Kind.MESSAGES, ChatService.WHATSAPP)))
        } finally {
            ArchiveEvents.unregister(listener)
        }
    }

    @Test
    fun eachServiceKeepsItsMediaInItsOwnDirectories() {
        val filesDir = context.filesDir
        assertEquals(File(filesDir, "relay-media"), MediaCache.mediaDir(context))
        assertEquals(File(filesDir, "relay-outbox"), MediaCache.outboxDir(context))
        assertEquals(File(filesDir, "relay-media"), MediaCache.mediaDir(context, ChatService.MESSAGES))
        assertEquals(File(filesDir, "whatsapp-media"), MediaCache.mediaDir(context, ChatService.WHATSAPP))
        assertEquals(File(filesDir, "whatsapp-outbox"), MediaCache.outboxDir(context, ChatService.WHATSAPP))
        assertEquals(MediaCache.mediaDir(filesDir, ChatService.WHATSAPP), MediaCache.mediaDir(context, ChatService.WHATSAPP))

        // The archive's own functions cannot get the service wrong.
        val waMedia = whatsApp.mediaFileFor("M1", "jpg")
        val waOutbox = whatsApp.outboxFileFor("t1")
        assertEquals(File(filesDir, "whatsapp-media"), waMedia.parentFile)
        assertEquals(File(filesDir, "whatsapp-outbox"), waOutbox.parentFile)
        assertEquals(waMedia, MediaCache.fileFor(context, "M1", "jpg", ChatService.WHATSAPP))
        assertEquals(waOutbox, MediaCache.outboxFile(context, "t1", ChatService.WHATSAPP))
        assertEquals(MediaCache.fileFor(context, "M1", "jpg"), messages.mediaFileFor("M1", "jpg"))
        assertEquals(MediaCache.outboxFile(context, "t1"), messages.outboxFileFor("t1"))
        assertEquals("the same media id, a file per service", waMedia.name, messages.mediaFileFor("M1", "jpg").name)
        assertNotEquals(waMedia, messages.mediaFileFor("M1", "jpg"))
    }

    @Test
    fun oneServicesCleanupNeverDeletesTheOthersFiles() {
        // The same message, part and media id in both archives, each with its own file.
        val messagesFile = messages.mediaFileFor("M1", "jpg").apply { writeText("messages") }
        val whatsAppFile = whatsApp.mediaFileFor("M1", "jpg").apply { writeText("whatsapp") }
        for ((repo, file) in listOf(messages to messagesFile, whatsApp to whatsAppFile)) {
            repo.upsertMessage(message("m1", parts = listOf(mediaPart("p1", "M1"))))
            assertTrue(repo.setMediaLocalPath("m1", "p1", "M1", file.path, thumbnail = false))
        }

        // WhatsApp deletes its message: only its own file goes.
        whatsApp.applyDeletion("m1")
        assertFalse(whatsAppFile.exists())
        assertTrue("the Messages file with the same name stays", messagesFile.exists())
        assertEquals(messagesFile.path, messages.mediaPart("m1", "p1")!!.localPath)

        // A late WhatsApp download for the deleted message is cleaned up, again in its own directory only.
        val late = whatsApp.mediaFileFor("M1", "jpg").apply { writeText("late") }
        assertFalse(whatsApp.setMediaLocalPath("m1", "p1", "M1", late.path, thumbnail = false))
        assertFalse(late.exists())
        assertTrue(messagesFile.exists())

        // And the other way round.
        val kept = whatsApp.mediaFileFor("M2", "jpg").apply { writeText("whatsapp") }
        whatsApp.upsertMessage(message("m2", parts = listOf(mediaPart("p1", "M2"))))
        assertTrue(whatsApp.setMediaLocalPath("m2", "p1", "M2", kept.path, thumbnail = false))
        messages.deleteConversationLocal(SAME_ID)
        assertFalse(messagesFile.exists())
        assertTrue(kept.exists())
    }

    @Test
    fun aFileInTheOtherServicesDirectoryIsRefused() {
        // What a caller gets that asked MediaCache for a directory without saying whose:
        // recorded here, the file could never be cleaned up, and the other service's
        // "delete everything" would take it away.
        val messagesMedia = MediaCache.fileFor(context, "M1", "jpg").apply { writeText("x") }
        val messagesOutbox = MediaCache.outboxFile(context, "t1").apply { writeText("x") }
        whatsApp.upsertMessage(message("m1", parts = listOf(mediaPart("p1", "M1"))))

        fun assertRefused(what: String, block: () -> Unit) {
            try {
                block()
                fail("$what was recorded")
            } catch (e: IllegalArgumentException) {
                assertFalse("no path in the message", e.message.orEmpty().contains(context.filesDir.path))
            }
        }
        assertRefused("a download in Messages' media directory") {
            whatsApp.setMediaLocalPath("m1", "p1", "M1", messagesMedia.path, thumbnail = false)
        }
        assertRefused("a thumbnail in Messages' media directory") {
            whatsApp.setMediaLocalPath("m1", "p1", messagesMedia.path, thumbnail = true)
        }
        assertRefused("an attachment copy in Messages' outbox") {
            whatsApp.insertLocalOutgoing("t1", SAME_ID, "", messagesOutbox.path, "image/jpeg", "a.jpg", null, 1_000)
        }
        assertRefused("a queued send from Messages' outbox") {
            whatsApp.enqueueOutbox(OutboxRow(idempotencyKey = "t1", conversationId = SAME_ID, mediaLocalPath = messagesOutbox.path, createdAt = 1))
        }
        assertRefused("a WhatsApp file in the Messages archive") {
            messages.insertLocalOutgoing("t2", SAME_ID, "", whatsApp.outboxFileFor("t2").path, "image/jpeg", "a.jpg", null, 1_000)
        }
        assertEquals("", whatsApp.mediaPart("m1", "p1")!!.localPath)
        assertNull(whatsApp.message("tmp:t1"))
        assertNull(whatsApp.outbox("t1"))
        assertTrue("and nothing was deleted", messagesMedia.exists() && messagesOutbox.exists())

        // Its own directories, and text-only sends, are fine.
        val own = whatsApp.outboxFileFor("t3").apply { writeText("x") }
        whatsApp.insertLocalOutgoing("t3", SAME_ID, "", own.path, "image/jpeg", "a.jpg", null, 1_000)
        whatsApp.enqueueOutbox(OutboxRow(idempotencyKey = "t3", conversationId = SAME_ID, mediaLocalPath = own.path, createdAt = 1))
        whatsApp.insertLocalOutgoing("t4", SAME_ID, "text", null, null, null, null, 1_000)
        assertEquals(own.path, whatsApp.message("tmp:t3")!!.media.single().localPath)
        assertNotNull(whatsApp.message("tmp:t4"))
    }

    @Test
    fun deletingOneArchiveLeavesTheOthersFilesAndRows() {
        val messagesMedia = messages.mediaFileFor("M1", "jpg").apply { writeText("x") }
        val messagesOutbox = messages.outboxFileFor("t1").apply { writeText("x") }
        val whatsAppMedia = whatsApp.mediaFileFor("M1", "jpg").apply { writeText("x") }
        val whatsAppOutbox = whatsApp.outboxFileFor("t1").apply { writeText("x") }
        messages.upsertMessage(message("m1"))
        whatsApp.upsertMessage(message("m1"))

        whatsApp.clearAll()
        assertFalse(whatsAppMedia.exists())
        assertFalse(whatsAppOutbox.exists())
        assertFalse(MediaCache.mediaDir(context, ChatService.WHATSAPP).exists())
        assertNull(whatsApp.message("m1"))
        assertTrue(messagesMedia.exists())
        assertTrue(messagesOutbox.exists())
        assertNotNull(messages.message("m1"))

        whatsApp.upsertMessage(message("m1"))
        val again = whatsApp.mediaFileFor("M1", "jpg").apply { writeText("x") }
        messages.clearAll()
        assertFalse(messagesMedia.exists())
        assertNull(messages.message("m1"))
        assertTrue(again.exists())
        assertNotNull(whatsApp.message("m1"))
    }

    @Test
    fun staleDownloadsAreSweptInTheArchivesOwnDirectoryOnly() {
        val old = System.currentTimeMillis() - MediaCache.STALE_PART_MS - 60_000
        val messagesPart = File(MediaCache.mediaDir(context).apply { mkdirs() }, "a.jpg.1" + MediaCache.PART_SUFFIX)
            .apply { writeText("x"); setLastModified(old) }
        val whatsAppPart = File(MediaCache.mediaDir(context, ChatService.WHATSAPP).apply { mkdirs() }, "a.jpg.1" + MediaCache.PART_SUFFIX)
            .apply { writeText("x"); setLastModified(old) }

        whatsApp.upsertMessage(message("m1")) // the first write of an archive sweeps
        assertFalse(whatsAppPart.exists())
        assertTrue("not WhatsApp's to sweep", messagesPart.exists())
        messages.upsertMessage(message("m1"))
        assertFalse(messagesPart.exists())
    }

    @Test
    fun getOpensOneArchivePerServiceEachInItsOwnDatabase() {
        ArchiveRepository.resetForTesting()
        context.deleteDatabase(ArchiveDb.NAME)
        context.deleteDatabase(ArchiveDb.NAME_WHATSAPP)

        val forMessages = ArchiveRepository.get(context)
        val forWhatsApp = ArchiveRepository.get(context, ChatService.WHATSAPP)
        assertSame("without a service it is the Messages archive, as before", forMessages, ArchiveRepository.get(context, ChatService.MESSAGES))
        assertSame(forWhatsApp, ArchiveRepository.get(context, ChatService.WHATSAPP))
        assertNotSame(forMessages, forWhatsApp)
        assertEquals(ChatService.MESSAGES, forMessages.service)
        assertEquals(ChatService.WHATSAPP, forWhatsApp.service)

        forWhatsApp.upsertConversation(ConversationDto(id = SAME_ID, name = "Alex"))
        assertTrue(context.getDatabasePath(ArchiveDb.NAME_WHATSAPP).exists())
        assertNull("not in the Messages database", forMessages.conversation(SAME_ID))
        assertEquals("Alex", forWhatsApp.conversation(SAME_ID)!!.name)
        assertTrue(context.getDatabasePath(ArchiveDb.NAME).exists())
        assertNotEquals(context.getDatabasePath(ArchiveDb.NAME), context.getDatabasePath(ArchiveDb.NAME_WHATSAPP))
    }

    @Test
    fun testsInstallAnArchivePerService() {
        ArchiveRepository.installForTesting(messages)
        ArchiveRepository.installForTesting(whatsApp, ChatService.WHATSAPP)
        assertSame(messages, ArchiveRepository.get(context))
        assertSame(whatsApp, ArchiveRepository.get(context, ChatService.WHATSAPP))

        // An archive made for one service cannot stand in for the other: its
        // changes and its files would carry the wrong service.
        try {
            ArchiveRepository.installForTesting(messages, ChatService.WHATSAPP)
            fail("a Messages archive was installed as WhatsApp's")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertSame(whatsApp, ArchiveRepository.get(context, ChatService.WHATSAPP))

        ArchiveRepository.resetForTesting()
        assertNotSame(messages, ArchiveRepository.get(context))
        assertNotSame(whatsApp, ArchiveRepository.get(context, ChatService.WHATSAPP))
    }

    private fun message(id: String, text: String = "hello", parts: List<PartDto> = emptyList()) = MessageDto(
        id = id,
        conversationId = SAME_ID,
        senderId = "2",
        senderName = "Sam",
        timestampMs = 1_000,
        status = "INCOMING_COMPLETE",
        text = text,
        parts = parts,
    )

    private fun mediaPart(partId: String, mediaId: String) =
        PartDto(partId = partId, kind = "media", mediaId = mediaId, mime = "image/jpeg", size = 10)

    private companion object {
        const val MESSAGES_DB = "service-archives-messages-test.db"
        const val WHATSAPP_DB = "service-archives-whatsapp-test.db"

        /** A conversation id both services happen to use. */
        const val SAME_ID = "c1"
    }
}
