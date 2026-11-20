package com.lightdecoder.imessage.direct

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MediaCache
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageErrors
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.IMessagePrefs
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.RelayException
import java.io.File
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.rust_bridge.AttachmentType
import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.IndexedMessagePart
import uniffi.rust_bridge.MessageAttachment
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MessagePart
import uniffi.rust_bridge.MessageType
import uniffi.rust_bridge.MmcsFile
import uniffi.rust_bridge.NormalMessage
import uniffi.rust_bridge.PushChatMessage

/**
 * Pictures, videos, files and voice messages through the iMessage library, main-process side:
 * downloaded when first shown, sent from here, kept as their files. The ":imessage" process is
 * played by a fake that answers as it does.
 */
@RunWith(AndroidJUnit4::class)
class DirectAttachmentsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ArchiveDb
    private lateinit var repo: ArchiveRepository
    private val me = "tel:+15550000001"
    private val bob = "tel:+15559876543"
    private val chat = "imsg:$bob"

    @Before
    fun setUp() {
        context.deleteDatabase(DB)
        db = ArchiveDb(context, DB)
        repo = ArchiveRepository.forTesting(db, ChatService.IMESSAGE)
        ArchiveRepository.installForTesting(repo)
        IMessageManager.installForTesting(context, repo)
        // Direct mode on: registered, with the library's files.
        DirectPrefs.setRegistered(context, true)
        DirectLibrary.REQUIRED.forEach { File(DirectLibrary.dir(context), it).apply { parentFile?.mkdirs(); writeText("") } }
        MediaCache.mediaDir(context, ChatService.IMESSAGE).deleteRecursively()
        MediaCache.outboxDir(context, ChatService.IMESSAGE).deleteRecursively()
    }

    @After
    fun tearDown() {
        MessageNotifier.cancelAll(context, ChatService.IMESSAGE)
        IMessagePrefs.setEnabled(context, false)
        DirectPrefs.setRegistered(context, false)
        DirectLink.resetForTesting()
        IMessageManager.resetForTesting()
        ArchiveRepository.resetForTesting()
        db.close()
    }

    private val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())

    private fun link(answer: (Int, JSONObject) -> JSONObject): DirectLink =
        DirectLink.installForTesting(context) { what, request, _ ->
            asked += what to request
            answer(what, request)
        }

    private fun requests(what: Int) = synchronized(asked) { asked.filter { it.first == what }.map { it.second } }

    /** Bob's message with [attachment] as part 0, as the ":imessage" process hands it over. */
    private fun incoming(guid: String, attachment: MessageAttachment, voice: Boolean = false, describe: String = "DESC-$guid") =
        DirectArchive.map(
            MessageInst(
                guid, bob, ConversationData(listOf(me, bob), null, null, null),
                PushChatMessage.Message(
                    NormalMessage(listOf(IndexedMessagePart(MessagePart.Attachment(attachment), 0UL, null)), null, null, null, MessageType.IMessage, null, null, null, voice, null, null),
                ),
                1_700_000_000_000UL, null, false, false, null,
            ),
            setOf(me), Handles::display, DirectTexts(context), { describe },
        ).let { DirectWire.decode(JSONObject(DirectWire.encode(it).toString())) }

    private fun mmcs(name: String, uti: String, size: Long = 1234) =
        MessageAttachment(AttachmentType.Mmcs(listOf(MmcsFile(byteArrayOf(1), "o", "https://x", byteArrayOf(2), size.toULong()))), 0UL, uti, "", name, false)

    /** Answers a download as the ":imessage" process does: the file written to the path asked for. */
    private fun downloads(bytes: ByteArray, count: AtomicInteger = AtomicInteger(), delayMs: Long = 0): (Int, JSONObject) -> JSONObject = { what, request ->
        if (what == DirectWire.DOWNLOAD) {
            count.incrementAndGet()
            Thread.sleep(delayMs)
            File(request.getString("path")).apply { parentFile?.mkdirs(); writeBytes(bytes) }
            DirectWire.downloaded(bytes.size.toLong())
        } else {
            DirectWire.ok()
        }
    }

    // --- downloads ----------------------------------------------------------------------------

    @Test
    fun anAttachmentIsDownloadedOnceWhenFirstShownThenItsFileIsKept() {
        val count = AtomicInteger()
        val picture = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3)
        val link = link(downloads(picture, count, delayMs = 300))
        link.apply(incoming("G1", mmcs("IMG_1.png", "public.png")))
        val part = repo.mediaPart("imsg:G1", "p0")!!
        assertEquals("", part.localPath)
        assertEquals("image/png", part.mime)
        // Two asks at once (the bubble and the gallery): one download.
        val files = runBlocking { List(2) { async { link.mediaFile("imsg:G1", "p0") } }.awaitAll() }
        assertEquals(1, count.get())
        assertEquals(files[0], files[1])
        val file = files[0]!!
        assertArrayEquals(picture, file.readBytes())
        val request = requests(DirectWire.DOWNLOAD).single()
        assertEquals("DESC-G1", request.getString("descriptor"))
        // Where it is kept: by its media id, as the ":imessage" process would name it.
        assertEquals(MediaCache.fileFor(context, "G1/p0", "png", ChatService.IMESSAGE).path, request.getString("path"))
        val stored = repo.mediaPart("imsg:G1", "p0")!!
        assertEquals(file.path, stored.localPath)
        assertEquals(picture.size.toLong(), stored.size)
        // From now on, the file here: no download.
        assertEquals(file, runBlocking { link.mediaFile("imsg:G1", "p0") })
        assertEquals(1, count.get())
        // Through iMessage's link, as the thread screen asks for it.
        assertEquals(file, runBlocking { IMessageManager.get(context).mediaFile("imsg:G1", "p0", thumbnail = true) })
    }

    @Test
    fun aSmallFileThatCameInsideTheMessageIsFoundWithoutADownload() {
        val link = link { _, _ -> fail("asked the other process"); DirectWire.ok() }
        val bytes = byteArrayOf(71, 73, 70, 56, 57, 97, 1)
        val gif = MessageAttachment(AttachmentType.Inline(bytes), 0UL, "com.compuserve.gif", "image/gif", "a.gif", false)
        link.apply(incoming("G2", gif))
        // The ":imessage" process wrote it where the main process looks.
        val written = MediaCache.fileFor(context, "G2/p0", DirectMedia.extensionFor("image/gif", "a.gif"), ChatService.IMESSAGE)
        MediaCache.writeAtomically(written, bytes)
        val file = runBlocking { link.mediaFile("imsg:G2", "p0") }
        assertEquals(written, file)
        assertEquals(written.path, repo.mediaPart("imsg:G2", "p0")!!.localPath)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun aSmallFileThatCameInsideTheMessageGoesWithItEvenIfNeverShown() {
        val link = link { _, _ -> DirectWire.ok() }
        val bytes = byteArrayOf(71, 73, 70, 56, 57, 97, 2)
        fun gif() = MessageAttachment(AttachmentType.Inline(bytes), 0UL, "com.compuserve.gif", "image/gif", "a.gif", false)
        fun fileOf(guid: String) = MediaCache.fileFor(context, "$guid/p0", DirectMedia.extensionFor("image/gif", "a.gif"), ChatService.IMESSAGE)
        // Written aside by the ":imessage" process before it hands the message over.
        fun written(guid: String) = DirectMedia.pendingInlineFile(fileOf(guid)).also { MediaCache.writeAtomically(it, bytes) }
        val pending = written("G5")
        val file = fileOf("G5")
        assertFalse("not in place until the message is taken", file.exists())
        link.apply(incoming("G5", gif()))
        // The part's file from the start, moved into place.
        assertEquals(file.path, repo.mediaPart("imsg:G5", "p0")!!.localPath)
        assertArrayEquals(bytes, file.readBytes())
        assertFalse(pending.exists())
        // Unsent before it was ever shown: the file goes with it.
        link.apply(DirectArchive.Update.Unsent("imsg:G5", 0, false, "Bob", bob, 1_700_000_005_000L))
        assertEquals("system", repo.message("imsg:G5")!!.direction)
        assertFalse(file.exists())
        // Apple handing it over again (its file written again): it stays unsent, and the file goes.
        written("G5")
        link.apply(incoming("G5", gif()))
        assertEquals("system", repo.message("imsg:G5")!!.direction)
        assertFalse(file.exists())
        assertFalse(pending.exists())
        // Deleted before it was shown: the same.
        written("G6")
        val deleted = fileOf("G6")
        link.apply(incoming("G6", gif()))
        link.apply(DirectArchive.Update.Remove("imsg:G6"))
        assertNull(repo.message("imsg:G6"))
        assertFalse(deleted.exists())
        // A message for a group that is not stored is not kept: nor is its file.
        val dropped = written("G7")
        val part = com.lightdecoder.relay.PartDto("p0", "media", mediaId = "G7/p0", mime = "image/gif", fileName = "a.gif")
        link.apply(
            DirectArchive.Update.Upsert(
                com.lightdecoder.relay.ConversationDto(id = "imsg:group:G-X", isGroup = true, type = DirectArchive.TRANSPORT),
                com.lightdecoder.relay.MessageDto(
                    id = "imsg:G7", conversationId = "imsg:group:G-X", senderId = bob, timestampMs = 1, direction = "in",
                    transport = DirectArchive.TRANSPORT, parts = listOf(part),
                ),
                groupGuid = "G-X", groupOnly = true,
            ),
        )
        assertNull(repo.message("imsg:G7"))
        assertFalse(dropped.exists())
        assertFalse(fileOf("G7").exists())
    }

    @Test
    fun aMessageReusingAnothersIdNeverReplacesItsFile() {
        val link = link { _, _ -> DirectWire.ok() }
        val bobs = byteArrayOf(71, 73, 70, 56, 57, 97, 3)
        val gif = { b: ByteArray -> MessageAttachment(AttachmentType.Inline(b), 0UL, "com.compuserve.gif", "image/gif", "a.gif", false) }
        val file = MediaCache.fileFor(context, "G8/p0", DirectMedia.extensionFor("image/gif", "a.gif"), ChatService.IMESSAGE)
        MediaCache.writeAtomically(DirectMedia.pendingInlineFile(file), bobs)
        link.apply(incoming("G8", gif(bobs)))
        assertArrayEquals(bobs, file.readBytes())
        // Alice's message with the same GUID and a picture of her own, written aside by the ":imessage" process.
        val alices = byteArrayOf(71, 73, 70, 56, 57, 97, 9, 9, 9)
        MediaCache.writeAtomically(DirectMedia.pendingInlineFile(file), alices)
        val fake = DirectArchive.map(
            MessageInst(
                "G8", "tel:+15551234567", ConversationData(listOf(me, "tel:+15551234567"), null, null, null),
                PushChatMessage.Message(
                    NormalMessage(listOf(IndexedMessagePart(MessagePart.Attachment(gif(alices)), 0UL, null)), null, null, null, MessageType.IMessage, null, null, null, false, null, null),
                ),
                1_700_000_000_000UL, null, false, false, null,
            ),
            setOf(me), Handles::display, DirectTexts(context), { "DESC" },
        ).let { DirectWire.decode(JSONObject(DirectWire.encode(it).toString())) }
        link.apply(fake)
        // Bob's message and its picture are as they were; Alice's picture went.
        assertEquals(chat, repo.message("imsg:G8")!!.conversationId)
        assertArrayEquals(bobs, file.readBytes())
        assertFalse(DirectMedia.pendingInlineFile(file).exists())
        assertEquals(file.path, repo.mediaPart("imsg:G8", "p0")!!.localPath)
    }

    @Test
    fun aVideosBubbleShowsItsFirstFrameOnceItsFileIsHere() {
        val link = link(downloads(byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70)))
        link.apply(incoming("VD1", mmcs("clip.mov", "com.apple.quicktime-movie")))
        val waiting = repo.mediaPart("imsg:VD1", "p0")!!
        assertTrue(waiting.mime, waiting.mime.startsWith("video/"))
        assertEquals("", waiting.thumbPath)
        val path = repo.mediaFileFor("VD1/p0", DirectMedia.extensionFor(waiting.mime, waiting.fileName)).path
        org.robolectric.shadows.ShadowMediaMetadataRetriever.addFrame(
            path, 0, android.graphics.Bitmap.createBitmap(1280, 720, android.graphics.Bitmap.Config.ARGB_8888),
        )
        try {
            val file = runBlocking { link.mediaFile("imsg:VD1", "p0") }!!
            assertEquals(path, file.path)
            val part = repo.mediaPart("imsg:VD1", "p0")!!
            assertEquals(path, part.localPath)
            // Its still, as the bubble's picture, kept beside it (and going with it).
            assertTrue(part.thumbPath.isNotEmpty() && part.thumbPath != path)
            assertTrue(File(part.thumbPath).length() > 0)
            link.apply(DirectArchive.Update.Remove("imsg:VD1"))
            assertFalse(File(part.thumbPath).exists())
        } finally {
            org.robolectric.shadows.ShadowMediaMetadataRetriever.reset()
        }
    }

    @Test
    fun withNothingToDownloadItByThereIsNoFile() {
        val link = link(downloads(byteArrayOf(1)))
        val gif = MessageAttachment(AttachmentType.Inline(byteArrayOf(1)), 0UL, "com.compuserve.gif", "image/gif", "b.gif", false)
        // A large one's description is not kept: only its file, which is gone here.
        link.apply(incoming("G3", gif, describe = ""))
        assertNull(runBlocking { link.mediaFile("imsg:G3", "p0") })
        assertNull("no such part", runBlocking { link.mediaFile("imsg:G3", "p9") })
        assertTrue(asked.isEmpty())
    }

    @Test
    fun aDownloadThatFailsSaysWhyAndIsTriedAgainNextTime() {
        val fails = AtomicInteger(1)
        val link = link { what, request ->
            if (what == DirectWire.DOWNLOAD && fails.getAndDecrement() > 0) DirectWire.error("Apple sent this attachment without a file.")
            else downloads(byteArrayOf(1, 2))(what, request)
        }
        link.apply(incoming("G4", mmcs("clip.mov", "com.apple.quicktime-movie")))
        try {
            runBlocking { link.mediaFile("imsg:G4", "p0") }
            fail("a failed download gave a file")
        } catch (e: DirectEngine.ImessageError) {
            assertEquals("Apple sent this attachment without a file.", e.message)
        }
        assertEquals("", repo.mediaPart("imsg:G4", "p0")!!.localPath)
        assertNotNull(runBlocking { link.mediaFile("imsg:G4", "p0") })
        assertEquals(2, requests(DirectWire.DOWNLOAD).size)
    }

    /** A CAF file of 16-bit big-endian mono PCM. */
    private fun cafPcm(sound: ByteArray): ByteArray {
        fun chunk(type: String, body: ByteArray) = ByteBuffer.allocate(12 + body.size).put(type.toByteArray()).putLong(body.size.toLong()).put(body).array()
        val desc = ByteBuffer.allocate(32).putDouble(8000.0).put("lpcm".toByteArray()).putInt(0).putInt(2).putInt(1).putInt(1).putInt(16).array()
        return "caff".toByteArray() + byteArrayOf(0, 1, 0, 0) + chunk("desc", desc) + chunk("data", ByteArray(4) + sound)
    }

    @Test
    fun anIphonesVoiceMessageIsMadePlayableAndTheOriginalKeptWithIt() {
        val caf = cafPcm(byteArrayOf(0, 1, 0, 2))
        val link = link(downloads(caf))
        link.apply(incoming("V1", mmcs("Audio Message.caf", "com.apple.coreaudio-format"), voice = true))
        val waiting = repo.message("imsg:V1")!!
        assertTrue(IMessageExtra.parse(waiting.extra).voice)
        assertEquals("audio/x-caf", waiting.media.single().mime)
        val file = runBlocking { link.mediaFile("imsg:V1", "p0") }!!
        assertEquals("wav", file.extension)
        assertEquals("RIFF", String(file.readBytes(), 0, 4))
        val part = repo.mediaPart("imsg:V1", "p0")!!
        assertEquals(file.path, part.localPath)
        assertEquals("audio/x-wav", part.mime)
        // The original, as it came, stays with it.
        assertArrayEquals(caf, File(part.thumbPath).readBytes())
        // Apple handing the message over again changes none of it.
        link.apply(incoming("V1", mmcs("Audio Message.caf", "com.apple.coreaudio-format"), voice = true))
        val again = repo.mediaPart("imsg:V1", "p0")!!
        assertEquals(file.path, again.localPath)
        assertEquals("audio/x-wav", again.mime)
    }

    @Test
    fun aVoiceMessageInACodecThisPhoneCannotPlayStaysAsItCame() {
        val alac = cafPcm(byteArrayOf(0, 1)).also { "alac".toByteArray().copyInto(it, 8 + 12 + 8) }
        val link = link(downloads(alac))
        link.apply(incoming("V2", mmcs("Audio Message.caf", "com.apple.coreaudio-format")))
        val file = runBlocking { link.mediaFile("imsg:V2", "p0") }!!
        assertEquals("caf", file.extension)
        assertEquals("audio/x-caf", repo.mediaPart("imsg:V2", "p0")!!.mime)
    }

    // --- sending ------------------------------------------------------------------------------

    /** Apple's answer to a send, as the ":imessage" process gives it: a GUID, the attachments numbered as rustpush does. */
    private fun sends(guid: String, failFirst: Int = 0): (Int, JSONObject) -> JSONObject {
        val left = AtomicInteger(failFirst)
        return { what, request ->
            when {
                what != DirectWire.SEND -> DirectWire.ok()
                left.getAndDecrement() > 0 -> DirectWire.error("Apple refused the message: busy")
                else -> {
                    val r = DirectWire.SendRequest.fromJson(request)
                    val first = if (r.text.isEmpty()) 0 else 1
                    DirectWire.sent(
                        guid, 1_700_000_050_000L,
                        r.attachments.mapIndexed { i, a -> DirectWire.SentPart(first + i, "p${first + i}", a.mime, a.name, File(a.path).length()) },
                    )
                }
            }
        }
    }

    private fun bobsChat(link: DirectLink) {
        link.apply(
            DirectArchive.map(
                MessageInst(
                    "B0", bob, ConversationData(listOf(me, bob), null, null, null),
                    PushChatMessage.Message(
                        NormalMessage(
                            listOf(IndexedMessagePart(MessagePart.Text("hi", uniffi.rust_bridge.TextFormat.Flags(uniffi.rust_bridge.TextFlags(false, false, false, false))), 0UL, null)),
                            null, null, null, MessageType.IMessage, null, null, null, false, null, null,
                        ),
                    ),
                    1_699_999_000_000UL, null, false, false, null,
                ),
                setOf(me), Handles::display, DirectTexts(context),
            ),
        )
    }

    private fun fileToSend(name: String, bytes: ByteArray): Uri =
        Uri.fromFile(File(context.cacheDir, "picked/$name").apply { parentFile?.mkdirs(); writeBytes(bytes) })

    @Test
    fun aPictureGoesAsAnIMessageWithItsCaptionAndBecomesTheSentMessagesFile() {
        // Apple answers once the local copy has been looked at.
        val answered = CountDownLatch(1)
        val answer = sends("S1")
        val link = link { what, request ->
            if (what == DirectWire.SEND) answered.await(5, TimeUnit.SECONDS)
            answer(what, request)
        }
        bobsChat(link)
        val bytes = byteArrayOf(-119, 80, 78, 71, 9, 9, 9)
        val tmpId = link.sendMedia(chat, fileToSend("IMG_9.png", bytes), " Look ", "imsg:B0")
        // At once: the local copy, with its own copy of the file.
        val local = repo.message(MessageRow.tmpMessageId(tmpId))!!
        assertEquals("Look", local.text)
        assertEquals("image/png", local.media.single().mime)
        assertEquals("IMG_9.png", local.media.single().fileName)
        assertEquals(repo.outboxFileFor(tmpId).path, local.media.single().localPath)
        assertArrayEquals(bytes, repo.outboxFileFor(tmpId).readBytes())
        answered.countDown()
        waitFor("the sent message") { repo.message("imsg:S1") != null }
        val request = DirectWire.SendRequest.fromJson(requests(DirectWire.SEND).single())
        assertEquals("Look", request.text)
        assertEquals("B0", request.replyGuid)
        assertFalse(request.voice)
        val attachment = request.attachments.single()
        assertEquals("image/png", attachment.mime)
        assertEquals("IMG_9.png", attachment.name)
        assertEquals(repo.outboxFileFor(tmpId).path, attachment.path)
        val sent = repo.message("imsg:S1")!!
        assertNull("the local copy gave way", repo.message(MessageRow.tmpMessageId(tmpId)))
        assertEquals("Look", sent.text)
        assertEquals("imsg:B0", sent.replyToId)
        // The caption is part 0, the picture part 1, as Apple numbered them.
        val part = sent.media.single()
        assertEquals("p1", part.partId)
        assertEquals("S1/p1", part.mediaId)
        assertEquals(MediaCache.fileFor(context, "S1/p1", "png", ChatService.IMESSAGE).path, part.localPath)
        assertArrayEquals(bytes, File(part.localPath).readBytes())
        assertFalse("the outbox copy moved", repo.outboxFileFor(tmpId).exists())
        // Shown from it, with nothing downloaded.
        assertEquals(File(part.localPath), runBlocking { link.mediaFile("imsg:S1", "p1") })
        assertTrue(requests(DirectWire.DOWNLOAD).isEmpty())
    }

    @Test
    fun aVoiceMessageGoesAsOneAndIsSentAgainAsOneAfterAFailure() {
        val link = link(sends("S2", failFirst = 1))
        bobsChat(link)
        val tmpId = IMessageManager.get(context).sendVoice(chat, fileToSend("voice-1.m4a", byteArrayOf(0, 0, 0, 0x18, 1)), null)
        waitFor("the failure") { repo.message(MessageRow.tmpMessageId(tmpId))?.localState == LocalState.FAILED }
        val failed = repo.message(MessageRow.tmpMessageId(tmpId))!!
        assertEquals("Apple refused the message: busy", failed.errorText)
        assertTrue("still a voice message, after a restart too", IMessageExtra.parse(failed.extra).voice)
        assertEquals("Audio Message.m4a", failed.media.single().fileName)
        assertTrue(link.retry(tmpId))
        waitFor("the sent voice message") { repo.message("imsg:S2") != null }
        val (first, second) = requests(DirectWire.SEND).map(DirectWire.SendRequest::fromJson)
        assertTrue(first.voice && second.voice)
        assertEquals(first.attachments, second.attachments)
        assertEquals("audio/mp4", second.attachments.single().mime)
        assertEquals("Audio Message.m4a", second.attachments.single().name)
        val sent = repo.message("imsg:S2")!!
        assertEquals("", sent.text)
        assertEquals("p0", sent.media.single().partId)
        assertTrue(IMessageExtra.parse(sent.extra).voice)
    }

    @Test
    fun aFileOverApplesLimitIsRefusedWithAClearError() {
        val link = link(sends("S3"))
        bobsChat(link)
        val huge = File(context.cacheDir, "picked/huge.mov").apply { parentFile?.mkdirs() }
        java.io.RandomAccessFile(huge, "rw").use { it.setLength(DirectMedia.MAX_SEND_BYTES + 1) }
        try {
            link.sendMedia(chat, Uri.fromFile(huge), null, null)
            fail("sent a file over 100 MB")
        } catch (e: DirectEngine.ImessageError) {
            assertEquals("This file is too big for iMessage: at most 100 MB can be sent.", e.message)
        } finally {
            huge.delete()
        }
        assertTrue("nothing stored", repo.messages(chat).none { it.isLocal })
        assertTrue(requests(DirectWire.SEND).isEmpty())
    }

    @Test
    fun aChatTheLibraryCannotReachTakesNoFile() {
        link(sends("S4"))
        try {
            IMessageManager.get(context).sendMedia("iMessage;+;chat9", fileToSend("a.png", byteArrayOf(1)), null, null)
            fail("sent to a chat the library cannot reach")
        } catch (e: RelayException) {
            assertEquals(IMessageErrors.NO_REPLY_BUTTON, e.code)
        }
    }

    @Test
    fun discardingAFailedFileDropsItsCopy() {
        val link = link(sends("S5", failFirst = 1))
        bobsChat(link)
        val tmpId = link.sendMedia(chat, fileToSend("doc.pdf", byteArrayOf(37, 80, 68, 70)), null, null)
        waitFor("the failure") { repo.message(MessageRow.tmpMessageId(tmpId))?.localState == LocalState.FAILED }
        assertEquals("application/pdf", repo.message(MessageRow.tmpMessageId(tmpId))!!.media.single().mime)
        assertTrue(repo.outboxFileFor(tmpId).exists())
        IMessageManager.get(context).discardFailed(tmpId)
        assertNull(repo.message(MessageRow.tmpMessageId(tmpId)))
        assertFalse(repo.outboxFileFor(tmpId).exists())
    }

    @Test
    fun deletingAChatDropsTheCopiesOfItsFilesThatDidNotGo() {
        val link = link(sends("S6", failFirst = 1))
        bobsChat(link)
        val tmpId = link.sendMedia(chat, fileToSend("doc.pdf", byteArrayOf(37, 80, 68, 70)), null, null)
        waitFor("the failure") { repo.message(MessageRow.tmpMessageId(tmpId))?.localState == LocalState.FAILED }
        val copy = repo.outboxFileFor(tmpId)
        assertTrue(copy.exists())
        // Another chat's queued send keeps its own copy.
        val queued = repo.outboxFileFor("t-other").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
        repo.insertLocalOutgoing("t-other", "imsg:tel:+15550002222", "", queued.path, "image/png", "a.png", null, 1)
        repo.enqueueOutbox(
            com.lightdecoder.archive.OutboxRow(
                idempotencyKey = "t-other", conversationId = "imsg:tel:+15550002222", mediaLocalPath = queued.path,
                mediaMime = "image/png", createdAt = 1,
            ),
        )
        runBlocking { IMessageManager.get(context).deleteConversation(chat) }
        assertNull(repo.conversation(chat))
        assertFalse(copy.exists())
        assertTrue(queued.exists())
    }

    @Test
    fun filesPickedTogetherGoOneAtATimeInTheOrderPicked() {
        // The first (a large video, say) takes a while to go up; the second must not overtake it.
        val firstAnswered = CountDownLatch(1)
        val count = AtomicInteger()
        val link = link { what, request ->
            if (what != DirectWire.SEND) return@link DirectWire.ok()
            val n = count.incrementAndGet()
            if (n == 1) firstAnswered.await(5, TimeUnit.SECONDS)
            val r = DirectWire.SendRequest.fromJson(request)
            val first = if (r.text.isEmpty()) 0 else 1
            DirectWire.sent("O$n", 1_700_000_050_000L + n, r.attachments.mapIndexed { i, a -> DirectWire.SentPart(first + i, "p${first + i}", a.mime, a.name, 1) })
        }
        bobsChat(link)
        link.sendMedia(chat, fileToSend("big.mov", byteArrayOf(0, 0, 0, 0x14, 1)), "Both", null)
        link.sendMedia(chat, fileToSend("small.png", byteArrayOf(-119, 80, 78, 71)), null, null)
        link.sendText(chat, "and a text", null)
        waitFor("the first send") { requests(DirectWire.SEND).size == 1 }
        Thread.sleep(300)
        assertEquals("nothing overtakes it", 1, requests(DirectWire.SEND).size)
        firstAnswered.countDown()
        waitFor("all three") { repo.message("imsg:O3") != null }
        val sent = requests(DirectWire.SEND).map(DirectWire.SendRequest::fromJson)
        assertEquals(listOf("big.mov", "small.png", null), sent.map { it.attachments.singleOrNull()?.name })
        assertEquals(listOf("Both", "", "and a text"), sent.map { it.text })
        // Each after the one before it, as Apple has them.
        assertEquals(listOf("B0", "O1", "O2"), sent.map { it.target.afterGuid })
    }

    @Test
    fun aVoiceMessageWhoseMessageWentDuringItsDownloadLeavesNoFileBehind() {
        val caf = cafPcm(byteArrayOf(0, 1, 0, 2))
        val link = link { what, request ->
            if (what == DirectWire.DOWNLOAD) {
                File(request.getString("path")).apply { parentFile?.mkdirs(); writeBytes(caf) }
                // Deleted while it came down.
                repo.applyDeletion("imsg:V3")
                DirectWire.downloaded(caf.size.toLong())
            } else {
                DirectWire.ok()
            }
        }
        link.apply(incoming("V3", mmcs("Audio Message.caf", "com.apple.coreaudio-format"), voice = true))
        assertNull(runBlocking { link.mediaFile("imsg:V3", "p0") })
        val left = MediaCache.mediaDir(context, ChatService.IMESSAGE).listFiles().orEmpty().filter { it.isFile }
        assertTrue("left behind: " + left.map { it.name }, left.isEmpty())
    }

    @Test
    fun afterTheLibraryIsTurnedOffAFailedFileIsNotSentAsItsCaptionAlone() {
        val link = link(sends("S6", failFirst = 1))
        bobsChat(link)
        val tmpId = link.sendMedia(chat, fileToSend("IMG_3.png", byteArrayOf(-119, 80, 78, 71)), "Look", null)
        waitFor("the failure") { repo.message(MessageRow.tmpMessageId(tmpId))?.localState == LocalState.FAILED }
        // The library off, and OpenBubbles' Reply button for the chat there, which takes text alone.
        DirectPrefs.setRegistered(context, false)
        IMessagePrefs.setEnabled(context, true)
        val reply = com.lightdecoder.imessage.OpenBubblesTestNotifications.replyAction(context)
        com.lightdecoder.imessage.IMessageReplies.update(
            chat, com.lightdecoder.imessage.IMessageAction(reply.actionIntent!!, arrayOf(android.app.RemoteInput.Builder("text_reply").build())),
            null, null, "m1",
        )
        try {
            IMessageManager.get(context).retry(tmpId)
            val row = repo.message(MessageRow.tmpMessageId(tmpId))!!
            assertEquals(LocalState.FAILED, row.localState)
            assertEquals("still with its picture", "image/png", row.media.single().mime)
            assertTrue(repo.outboxFileFor(tmpId).exists())
            assertTrue("nothing marked sent", repo.messages(chat).none { it.id.startsWith("ld:") })
        } finally {
            com.lightdecoder.imessage.IMessageReplies.clear()
        }
    }

    @Test
    fun iMessagesFilesCanBeHandedToAnotherApp() {
        // To open a voice message this phone cannot play, a document, or share a picture.
        val authority = "${context.packageName}.fileprovider"
        val received = MediaCache.fileFor(context, "G7/p0", "caf", ChatService.IMESSAGE).apply { writeBytes(byteArrayOf(1)) }
        val waiting = repo.outboxFileFor("t-7").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(2)) }
        for (file in listOf(received, waiting)) {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
            assertEquals(authority, uri.authority)
            assertArrayEquals(file.readBytes(), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        }
    }

    /** Polls, for work the link does on its own threads. */
    private fun waitFor(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    private companion object {
        const val DB = "direct-attachments-test.db"
    }
}
