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
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.IMessagePrefs
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.ui.chat.shownMedia
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
 * SPEC2 B4, main-process side: photos sent together as one message, transfers' progress and their
 * stopping, Live Photos' moving parts, location cards, per-photo tapbacks. The ":imessage" process
 * is played by a fake that answers as it does.
 */
@RunWith(AndroidJUnit4::class)
class DirectMediaBatchTest {

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

    /** The fake ":imessage" process: [answer] gets each request and may tell its progress. */
    private fun link(answer: (Int, JSONObject, ((JSONObject) -> Unit)?) -> JSONObject): DirectLink =
        DirectLink.installForTesting(
            context,
            requests = object : DirectLink.Requests {
                override suspend fun call(what: Int, request: JSONObject, timeoutMs: Long): JSONObject = call(what, request, timeoutMs, null, null)
                override suspend fun call(what: Int, request: JSONObject, timeoutMs: Long, onProgress: ((JSONObject) -> Unit)?, idleMs: Long?): JSONObject {
                    asked += what to request
                    return answer(what, request, onProgress)
                }
            },
        )

    private fun requests(what: Int) = synchronized(asked) { asked.filter { it.first == what }.map { it.second } }

    private fun sends(guid: String, failFirst: Int = 0): (Int, JSONObject, ((JSONObject) -> Unit)?) -> JSONObject {
        val left = AtomicInteger(failFirst)
        return { what, request, _ ->
            when {
                what != DirectWire.SEND -> DirectWire.ok()
                left.getAndDecrement() > 0 -> DirectWire.error("Apple refused the message: busy")
                else -> {
                    val r = DirectWire.SendRequest.fromJson(request)
                    val first = if (r.text.isEmpty()) 0 else 1
                    DirectWire.sent(guid, 1_700_000_050_000L, r.attachments.mapIndexed { i, a -> DirectWire.SentPart(first + i, "p${first + i}", a.mime, a.name, File(a.path).length()) })
                }
            }
        }
    }

    private fun bobsChat(link: DirectLink) {
        link.apply(message("B0", listOf(IndexedMessagePart(MessagePart.Text("hi", uniffi.rust_bridge.TextFormat.Flags(uniffi.rust_bridge.TextFlags(false, false, false, false))), 0UL, null))))
    }

    private fun message(guid: String, parts: List<IndexedMessagePart>, describe: (MessageAttachment) -> String = { "DESC-" + it.name }) = DirectArchive.map(
        MessageInst(
            guid, bob, ConversationData(listOf(me, bob), null, null, null),
            PushChatMessage.Message(NormalMessage(parts, null, null, null, MessageType.IMessage, null, null, null, false, null, null)),
            1_699_999_000_000UL, null, false, false, null,
        ),
        setOf(me), Handles::display, DirectTexts(context), describe,
    ).let { DirectWire.decode(JSONObject(DirectWire.encode(it).toString())) }

    private fun attachment(idx: Int, name: String, uti: String, iris: Boolean = false, type: AttachmentType = AttachmentType.Mmcs(listOf(MmcsFile(byteArrayOf(1), "o", "https://x", byteArrayOf(2), 1234UL)))) =
        IndexedMessagePart(MessagePart.Attachment(MessageAttachment(type, 0UL, uti, "", name, iris)), idx.toULong(), null)

    private fun fileToSend(name: String, bytes: ByteArray): Uri =
        Uri.fromFile(File(context.cacheDir, "picked/$name").apply { parentFile?.mkdirs(); writeBytes(bytes) })

    private fun waitFor(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    /** Message [id] stored with each of its files recorded (that comes right after, off this thread). */
    private fun waitSent(id: String) =
        waitFor("$id and its files") { repo.message(id)?.media?.all { it.localPath.isNotEmpty() } == true }

    private val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    // --- photos sent together ----------------------------------------------------------------------

    @Test
    fun photosPickedTogetherGoAsOneMessageWithOneLocalCopy() {
        val answered = CountDownLatch(1)
        val answer = sends("S1")
        val link = link { what, request, p ->
            if (what == DirectWire.SEND) answered.await(5, TimeUnit.SECONDS)
            answer(what, request, p)
        }
        bobsChat(link)
        val uris = (1..3).map { fileToSend("IMG_$it.png", png + byteArrayOf(it.toByte())) }
        val tmpId = link.sendMediaBatch(chat, uris, "Trip", null)
        // One local copy: its first file in the row, the others waiting in its extra, all in the outbox.
        val local = repo.message(MessageRow.tmpMessageId(tmpId))!!
        assertEquals(repo.outboxFileFor(tmpId).path, local.media.single().localPath)
        val pending = IMessageExtra.parse(local.extra).pending
        assertEquals(listOf("$tmpId-1", "$tmpId-2").map { repo.outboxFileFor(it).path }, pending.map { it.path })
        assertEquals(listOf("IMG_2.png", "IMG_3.png"), pending.map { it.name })
        assertEquals(1, repo.messages(chat).count { it.isLocal })
        answered.countDown()
        waitSent("imsg:S1")
        // One SEND with all three.
        val request = DirectWire.SendRequest.fromJson(requests(DirectWire.SEND).single())
        assertEquals(listOf("IMG_1.png", "IMG_2.png", "IMG_3.png"), request.attachments.map { it.name })
        assertEquals(DirectWire.sendKey(tmpId), request.transfer)
        val sent = repo.message("imsg:S1")!!
        assertEquals(listOf("p1", "p2", "p3"), sent.media.map { it.partId })
        sent.media.forEachIndexed { i, m -> assertArrayEquals(png + byteArrayOf((i + 1).toByte()), File(m.localPath).readBytes()) }
        assertNull(repo.message(MessageRow.tmpMessageId(tmpId)))
        assertTrue("the outbox copies moved", (0..2).none { repo.outboxFileFor(if (it == 0) tmpId else "$tmpId-$it").exists() })
        assertTrue("no progress left once it ended", link.transfers.value.isEmpty())
    }

    @Test
    fun aBatchWithAFileThatCannotBeReadKeepsNoneOfIt() {
        val link = link(sends("S2"))
        bobsChat(link)
        val good = fileToSend("a.png", png)
        val missing = Uri.fromFile(File(context.cacheDir, "picked/missing.png"))
        try {
            link.sendMediaBatch(chat, listOf(good, missing), null, null)
            fail("a batch went in part")
        } catch (e: DirectEngine.ImessageError) {
            // As for one file.
        }
        assertTrue("nothing stored", repo.messages(chat).none { it.isLocal })
        assertTrue(requests(DirectWire.SEND).isEmpty())
        assertTrue("no copy kept", MediaCache.outboxDir(context, ChatService.IMESSAGE).listFiles().orEmpty().none { it.isFile })
        // Too many at once: refused before anything is copied.
        val many = (0..DirectLink.MAX_BATCH).map { fileToSend("m$it.png", png) }
        try {
            link.sendMediaBatch(chat, many, null, null)
            fail("more than a batch holds")
        } catch (e: DirectEngine.ImessageError) {
            assertTrue(MediaCache.outboxDir(context, ChatService.IMESSAGE).listFiles().orEmpty().none { it.isFile })
        }
    }

    @Test
    fun tryAgainSendsTheWholeBatchAgain() {
        val link = link(sends("S3", failFirst = 1))
        bobsChat(link)
        val tmpId = link.sendMediaBatch(chat, listOf(fileToSend("a.png", png), fileToSend("b.png", png + 1)), null, null)
        waitFor("the failure") { repo.message(MessageRow.tmpMessageId(tmpId))?.localState == LocalState.FAILED }
        assertTrue(link.retry(tmpId))
        waitSent("imsg:S3")
        val (first, second) = requests(DirectWire.SEND).map(DirectWire.SendRequest::fromJson)
        assertEquals(2, first.attachments.size)
        assertEquals(first.attachments, second.attachments)
        assertEquals(listOf("p0", "p1"), repo.message("imsg:S3")!!.media.map { it.partId })
    }

    @Test
    fun notDeliveredTryAgainSendsAllItsPhotosAgainAsOne() {
        val link = link(sends("S8"))
        bobsChat(link)
        val tmpId = link.sendMediaBatch(chat, listOf(fileToSend("a.png", png), fileToSend("b.png", png + 1)), null, null)
        // Stored first, its files recorded right after: both waited for.
        waitFor("the sent message and its files") { repo.message("imsg:S8")?.media?.let { m -> m.size == 2 && m.all { it.localPath.isNotEmpty() } } == true }
        // Apple said afterwards it could not deliver it.
        val sent = repo.message("imsg:S8")!!
        repo.upsertMessage(
            com.lightdecoder.relay.MessageDto(
                id = sent.id, conversationId = chat, fromMe = true, timestampMs = sent.timestampMs, direction = "out",
                status = DirectArchive.STATUS_FAILED, statusCode = 1, transport = IMessageManager.TRANSPORT,
                parts = sent.media.map { com.lightdecoder.relay.PartDto(partId = it.partId, kind = "media", mediaId = it.mediaId, mime = it.mime, fileName = it.fileName, size = it.size) },
                isOld = true,
            ),
        )
        assertTrue(link.undelivered(repo.message("imsg:S8")!!))
        assertNotNull(tmpId)
        assertNotNull(link.sendAgain("imsg:S8"))
        waitFor("sent again") { requests(DirectWire.SEND).size == 2 }
        assertEquals(2, DirectWire.SendRequest.fromJson(requests(DirectWire.SEND).last()).attachments.size)
    }

    @Test
    fun aBatchsOtherFilesAreOnlyTakenFromTheOutbox() {
        val link = link(sends("S4", failFirst = 1))
        bobsChat(link)
        val tmpId = link.sendMediaBatch(chat, listOf(fileToSend("a.png", png), fileToSend("b.png", png)), null, null)
        waitFor("the failure") { repo.message(MessageRow.tmpMessageId(tmpId))?.localState == LocalState.FAILED }
        // Its extra rewritten to name a file elsewhere (the library's state, say): left out of the retry.
        val row = repo.message(MessageRow.tmpMessageId(tmpId))!!
        val outside = File(context.filesDir, "imessage/state.plist").apply { parentFile?.mkdirs(); writeText("x") }
        val extra = IMessageExtra.parse(row.extra).copy(pending = listOf(IMessageExtra.PendingFile(outside.path, "image/png", "x.png")))
        repo.setExtra(row.id, extra.toJson())
        assertTrue(link.retry(tmpId))
        waitSent("imsg:S4")
        val again = DirectWire.SendRequest.fromJson(requests(DirectWire.SEND).last())
        assertEquals(listOf(repo.outboxFileFor(tmpId).path), again.attachments.map { it.path })
    }

    @Test
    fun discardingAFailedBatchDropsAllItsCopies() {
        val link = link(sends("S5", failFirst = 1))
        bobsChat(link)
        val tmpId = link.sendMediaBatch(chat, listOf(fileToSend("a.png", png), fileToSend("b.png", png), fileToSend("c.png", png)), null, null)
        waitFor("the failure") { repo.message(MessageRow.tmpMessageId(tmpId))?.localState == LocalState.FAILED }
        assertTrue(repo.outboxFileFor("$tmpId-2").exists())
        IMessageManager.get(context).discardFailed(tmpId)
        assertFalse(repo.outboxFileFor(tmpId).exists())
        assertFalse(repo.outboxFileFor("$tmpId-1").exists())
        assertFalse(repo.outboxFileFor("$tmpId-2").exists())
    }

    // --- progress and stopping ------------------------------------------------------------------

    @Test
    fun aSendsProgressShowsInItsChatThenGoes() {
        val seen = CountDownLatch(1)
        val release = CountDownLatch(1)
        val answer = sends("S6")
        val link = link { what, request, onProgress ->
            if (what == DirectWire.SEND) {
                // Untrusted numbers: more done than sent is clamped.
                onProgress?.invoke(DirectWire.progress(5, 10, 0, 2))
                onProgress?.invoke(DirectWire.progress(999_999, 999_999, 1, 2))
                seen.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            answer(what, request, onProgress)
        }
        bobsChat(link)
        val tmpId = link.sendMediaBatch(chat, listOf(fileToSend("a.png", png), fileToSend("b.png", png)), null, null)
        assertTrue(seen.await(5, TimeUnit.SECONDS))
        waitFor("its progress") { link.transfers.value[DirectWire.sendKey(tmpId)]?.part == 1 }
        val t = link.transfers.value.getValue(DirectWire.sendKey(tmpId))
        assertTrue(t.upload)
        assertEquals(chat, t.conversationId)
        assertTrue("at most twice the file's size", t.total <= png.size * 2L && t.done <= t.total)
        val bar = DirectLink.SendBars.sendBar(link.transfers.value, setOf(chat))!!
        assertEquals(2, bar.file)
        assertEquals(2, bar.files)
        assertNull("another chat shows none", DirectLink.SendBars.sendBar(link.transfers.value, setOf("imsg:other")))
        release.countDown()
        waitSent("imsg:S6")
        assertTrue(link.transfers.value.isEmpty())
        // A text has none.
        link.sendText(chat, "hello", null)
        waitFor("the text") { requests(DirectWire.SEND).size == 2 }
        assertNull(DirectWire.SendRequest.fromJson(requests(DirectWire.SEND).last()).transfer)
    }

    @Test
    fun aDownloadTheUserStopsIsStoppedNotFailed() {
        val inDownload = CountDownLatch(1)
        val stop = CountDownLatch(1)
        val link = link { what, request, onProgress ->
            when (what) {
                DirectWire.DOWNLOAD -> {
                    onProgress?.invoke(DirectWire.progress(100, 1234))
                    inDownload.countDown()
                    stop.await(5, TimeUnit.SECONDS)
                    DirectWire.failure(Transfers.Stopped())
                }
                DirectWire.CANCEL -> {
                    stop.countDown()
                    DirectWire.cancelled(DirectWire.cancelKeyOf(request) == "G1/p0")
                }
                else -> DirectWire.ok()
            }
        }
        link.apply(message("G1", listOf(attachment(0, "IMG_1.png", "public.png"))))
        val download = Thread {
            try {
                runBlocking { link.mediaFile("imsg:G1", "p0") }
                fail("a stopped download gave a file")
            } catch (e: DirectLink.Stopped) {
                stopped.set(true)
            }
        }.apply { start() }
        assertTrue(inDownload.await(5, TimeUnit.SECONDS))
        waitFor("its ring") { link.transfers.value["G1/p0"]?.done == 100L }
        assertFalse(link.transfers.value.getValue("G1/p0").upload)
        assertTrue(runBlocking { link.cancelTransfer("G1/p0") })
        download.join(5_000)
        assertTrue(stopped.get())
        assertTrue(link.transfers.value.isEmpty())
        // The request named it, so that it could be stopped.
        assertEquals("G1/p0", DirectWire.DownloadRequest.fromJson(requests(DirectWire.DOWNLOAD).single()).transfer)
        // Nothing else is ever asked to stop.
        assertFalse(runBlocking { link.cancelTransfer("../x") })
        assertEquals(1, requests(DirectWire.CANCEL).size)
    }

    private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)

    @Test
    fun aBatchDeletedWhileItUploadsIsStoppedFirstThenGone() {
        val inSend = CountDownLatch(1)
        val stop = CountDownLatch(1)
        val link = link { what, request, onProgress ->
            when (what) {
                DirectWire.SEND -> {
                    onProgress?.invoke(DirectWire.progress(1, 8, 0, 2))
                    inSend.countDown()
                    stop.await(5, TimeUnit.SECONDS)
                    DirectWire.failure(Transfers.Stopped())
                }
                DirectWire.CANCEL -> {
                    stop.countDown()
                    DirectWire.cancelled(DirectWire.cancelKeyOf(request)?.startsWith("tmp:") == true)
                }
                else -> DirectWire.ok()
            }
        }
        bobsChat(link)
        val tmpId = link.sendMediaBatch(chat, listOf(fileToSend("a.png", png), fileToSend("b.png", png)), null, null)
        assertTrue(inSend.await(5, TimeUnit.SECONDS))
        waitFor("its upload") { link.transfers.value[DirectWire.sendKey(tmpId)] != null }
        val files = repo.message(MessageRow.tmpMessageId(tmpId))!!.let { row ->
            row.media.map { File(it.localPath) } + IMessageExtra.parse(row.extra).pending.map { File(it.path) }
        }
        assertEquals(2, files.size)
        assertTrue(runBlocking { IMessageManager.get(context).deleteSending(tmpId) })
        // CANCEL 25 named the send, before anything was deleted.
        assertEquals("tmp:$tmpId", DirectWire.cancelKeyOf(requests(DirectWire.CANCEL).single()))
        assertNull(repo.message(MessageRow.tmpMessageId(tmpId)))
        waitFor("its files gone") { files.none { it.exists() } }
    }

    @Test
    fun aCopyDeletedWhileItWaitsItsTurnNeverGoes() {
        val inSend = CountDownLatch(1)
        val release = CountDownLatch(1)
        val answer = sends("S9")
        val link = link { what, request, p ->
            if (what == DirectWire.SEND) {
                inSend.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            answer(what, request, p)
        }
        bobsChat(link)
        link.sendText(chat, "first", null)
        assertTrue(inSend.await(5, TimeUnit.SECONDS))
        val tmpId = link.sendMediaBatch(chat, listOf(fileToSend("a.png", png), fileToSend("b.png", png)), null, null)
        assertEquals(LocalState.PENDING, repo.message(MessageRow.tmpMessageId(tmpId))!!.localState)
        assertTrue(runBlocking { IMessageManager.get(context).deleteSending(tmpId) })
        assertNull(repo.message(MessageRow.tmpMessageId(tmpId)))
        release.countDown()
        waitFor("the first one sent") { repo.message("imsg:S9") != null }
        Thread.sleep(200)
        assertEquals("only the first went", 1, requests(DirectWire.SEND).size)
        // Nothing was asked to stop: it had not started.
        assertTrue(requests(DirectWire.CANCEL).isEmpty())
    }

    @Test
    fun aBatchsOtherFilesGoWithItsLocalCopyHoweverItGoes() {
        fun batch(tmpId: String, chatId: String): List<File> {
            val files = (0..2).map { i -> repo.outboxFileFor(if (i == 0) tmpId else "$tmpId-$i").apply { parentFile?.mkdirs(); writeBytes(png) } }
            // A path outside the outbox is never deleted, whatever the extra says.
            val outside = File(context.filesDir, "keep-$tmpId.png").apply { writeBytes(png) }
            val extra = IMessageExtra(pending = (files.drop(1) + outside).map { IMessageExtra.PendingFile(it.path, "image/png", it.name) })
            repo.insertLocalOutgoing(tmpId, chatId, "", files[0].path, "image/png", "a.png", null, 1L, extra.toJson())
            return files + outside
        }
        val one = batch("t-del", chat)
        repo.applyDeletion(MessageRow.tmpMessageId("t-del"))
        assertTrue(one.dropLast(1).none { it.exists() })
        assertTrue(one.last().exists())
        val two = batch("t-conv", "imsg:tel:+15550000099")
        repo.deleteConversationLocal("imsg:tel:+15550000099")
        assertTrue(two.dropLast(1).none { it.exists() })
        assertTrue(two.last().exists())
    }

    @Test
    fun aCopyAlreadyWithAppleIsNotDeleted() {
        val link = link { what, request, p -> if (what == DirectWire.CANCEL) DirectWire.cancelled(false) else sends("S10")(what, request, p) }
        bobsChat(link)
        // Not waiting and not uploading (say a text being sent): it cannot be stopped, and stays.
        val tmpId = "t-sent"
        repo.insertLocalOutgoing(tmpId, chat, "hi", null, null, null, null, 1L, "")
        repo.setLocalState(tmpId, LocalState.SENDING)
        assertFalse(runBlocking { IMessageManager.get(context).deleteSending(tmpId) })
        assertNotNull(repo.message(MessageRow.tmpMessageId(tmpId)))
    }

    // --- Live Photos -------------------------------------------------------------------------------

    @Test
    fun aLivePhotosStillUnsentTakesItsMovingPartWithIt() {
        val link = link { _, _, _ -> DirectWire.ok() }
        link.apply(
            message(
                "L1",
                listOf(
                    attachment(0, "IMG_5.HEIC", "public.heic"),
                    attachment(1, "IMG_5.MOV", "com.apple.quicktime-movie", iris = true),
                    attachment(2, "IMG_6.HEIC", "public.heic"),
                ),
            ),
        )
        val row = repo.message("imsg:L1")!!
        assertEquals(listOf("p0", "p1", "p2"), row.media.map { it.partId })
        assertEquals(listOf("p0", "p2"), row.shownMedia().map { it.partId })
        val unsend = DirectArchive.Update.Unsent("imsg:L1", 0, false, "Bob", bob, 1_699_999_030_000L, chat)
        link.apply(DirectWire.decode(JSONObject(DirectWire.encode(unsend).toString())))
        val after = repo.message("imsg:L1")!!
        assertEquals(listOf("p2"), after.media.map { it.partId })
        assertTrue(IMessageExtra.parse(after.extra).live.isEmpty())
    }

    @Test
    fun theGalleryAndTheJunkReportNeverTakeAMovingPart() {
        val link = link { _, _, _ -> DirectWire.ok() }
        link.apply(
            message(
                "L2",
                listOf(
                    attachment(0, "IMG_7.HEIC", "public.heic"),
                    attachment(1, "IMG_7.MOV", "com.apple.quicktime-movie", iris = true),
                ),
            ),
        )
        val row = repo.message("imsg:L2")!!
        assertEquals(2, row.media.size)
        // The photos of the chat: the still once.
        val pictures = com.lightdecoder.ui.chat.PictureGalleryActivity.picturesOf(IMessageManager.get(context), chat)
        assertEquals(listOf("p0"), pictures.filter { it.message.id == "imsg:L2" }.map { it.part.partId })
        // Reported as junk: only what the chat shows.
        val junk = JunkReports.message(row, DirectWire.Target(listOf(me, bob), null, null, me))!!
        assertEquals(listOf("DESC-IMG_7.HEIC"), junk.attachments)
    }

    // --- location cards ----------------------------------------------------------------------------

    @Test
    fun aLocationCardIsFetchedOnceAsAnAutomaticDownloadOfSixteenKilobytesAtMost() {
        val vcf = AppleLocation.toVcf(37.33182, -122.03118).toByteArray()
        val link = link { what, request, _ ->
            if (what == DirectWire.DOWNLOAD) {
                File(request.getString("path")).apply { parentFile?.mkdirs(); writeBytes(vcf) }
                DirectWire.downloaded(vcf.size.toLong())
            } else {
                DirectWire.ok()
            }
        }
        link.apply(message("C1", listOf(attachment(0, "C1-CL.loc.vcf", "public.vlocation", type = AttachmentType.Mmcs(listOf(MmcsFile(byteArrayOf(1), "o", "https://x", byteArrayOf(2), 300UL)))))))
        assertEquals(37.33182 to -122.03118, runBlocking { link.locationOf("imsg:C1", "p0") })
        val request = DirectWire.DownloadRequest.fromJson(requests(DirectWire.DOWNLOAD).single())
        assertEquals(AppleLocation.MAX_BYTES.toLong(), request.maxBytes)
        assertNotNull("automatic: Apple's servers only", request.automaticMs)
        assertNull("and no progress", request.transfer)
        // Kept: read from here from now on.
        assertEquals(37.33182 to -122.03118, runBlocking { link.locationOf("imsg:C1", "p0") })
        assertEquals(1, requests(DirectWire.DOWNLOAD).size)
    }

    @Test
    fun aLocationCardThatSaysItIsLargeOrIsNoCardIsNotFetched() {
        val link = link { _, _, _ -> DirectWire.ok() }
        link.apply(message("C2", listOf(attachment(0, "C2-CL.loc.vcf", "public.vlocation", type = AttachmentType.Mmcs(listOf(MmcsFile(byteArrayOf(1), "o", "https://x", byteArrayOf(2), 1_000_000UL)))))))
        link.apply(message("C3", listOf(attachment(0, "IMG.png", "public.png"))))
        assertNull(runBlocking { link.locationOf("imsg:C2", "p0") })
        assertNull(runBlocking { link.locationOf("imsg:C3", "p0") })
        assertTrue(requests(DirectWire.DOWNLOAD).isEmpty())
    }

    @Test
    fun aLocationCardIsNotFetchedBeforeThePhoneIsRegistered() {
        val link = link { _, _, _ -> DirectWire.ok() }
        link.apply(message("C5", listOf(attachment(0, "C5-CL.loc.vcf", "public.vlocation", type = AttachmentType.Mmcs(listOf(MmcsFile(byteArrayOf(1), "o", "https://x", byteArrayOf(2), 300UL)))))))
        DirectPrefs.setRegistered(context, false)
        // Nothing could be fetched, so none of its sender's hourly fetches is used either.
        assertNull(runBlocking { link.locationOf("imsg:C5", "p0") })
        assertTrue(requests(DirectWire.DOWNLOAD).isEmpty())
    }

    @Test
    fun aLocationCardThatCameInsideTheMessageNeedsNoFetch() {
        val vcf = AppleLocation.toVcf(1.5, 2.5).toByteArray()
        val link = link { _, _, _ -> DirectWire.ok() }
        link.apply(message("C4", listOf(attachment(0, "C4-CL.loc.vcf", "public.vlocation", type = AttachmentType.Inline(vcf)))))
        // As the ":imessage" process writes it aside and the main process takes it (or, without its file, from the row).
        val at = runBlocking { link.locationOf("imsg:C4", "p0") }
        assertTrue(at == null || at == 1.5 to 2.5)
        assertTrue(requests(DirectWire.DOWNLOAD).all { DirectWire.DownloadRequest.fromJson(it).automaticMs != null })
    }

    @Test
    fun aLocationIsSentAsItsCardFile() {
        val link = link(sends("S7"))
        bobsChat(link)
        val file = File(context.cacheDir, "shared-in/x/x-CL.loc.vcf").apply { parentFile?.mkdirs(); writeText(AppleLocation.toVcf(1.0, 2.0)) }
        link.sendFile(chat, file, AppleLocation.MIME, file.name)
        file.delete()
        waitSent("imsg:S7")
        val a = DirectWire.SendRequest.fromJson(requests(DirectWire.SEND).single()).attachments.single()
        assertEquals(AppleLocation.MIME, a.mime)
        assertEquals("x-CL.loc.vcf", a.name)
        assertEquals(1.0 to 2.0, AppleLocation.coordinatesFrom(File(repo.message("imsg:S7")!!.media.single().localPath).readText()))
    }

    // --- per-photo tapbacks --------------------------------------------------------------------------

    @Test
    fun aTapbackOnOnePhotoIsKeptWithTheMessage() {
        val link = link { _, _, _ -> DirectWire.ok() }
        link.apply(message("P1", listOf(attachment(0, "a.png", "public.png"), attachment(1, "b.png", "public.png"))))
        fun react(part: Int, enable: Boolean = true) = link.apply(
            DirectArchive.Update.Reaction("imsg:P1", bob.removePrefix("tel:"), "❤️", enable, false, 1_699_999_040_000L, chat, part = part),
        )
        react(1)
        val row = repo.message("imsg:P1")!!
        assertEquals("❤️", row.reactions.single().emoji)
        assertEquals(mapOf(bob.removePrefix("tel:") to 1), IMessageExtra.parse(row.extra).tapbackParts)
        react(1, enable = false)
        assertTrue(IMessageExtra.parse(repo.message("imsg:P1")!!.extra).tapbackParts.isEmpty())
        // The first photo is Apple's part 0: kept as such, so its pill shows on it.
        react(0)
        assertEquals(mapOf(bob.removePrefix("tel:") to 0), IMessageExtra.parse(repo.message("imsg:P1")!!.extra).tapbackParts)
    }

    @Test
    fun aTapbackOnAMessageOfOnePictureKeepsNoPhoto() {
        val link = link { _, _, _ -> DirectWire.ok() }
        link.apply(message("P2", listOf(attachment(0, "a.png", "public.png"))))
        link.apply(DirectArchive.Update.Reaction("imsg:P2", bob.removePrefix("tel:"), "❤️", true, false, 1_699_999_040_000L, chat, part = 0))
        val row = repo.message("imsg:P2")!!
        assertEquals("❤️", row.reactions.single().emoji)
        assertTrue(IMessageExtra.parse(row.extra).tapbackParts.isEmpty())
    }

    private companion object {
        const val DB = "direct-media-batch-test.db"
    }
}
