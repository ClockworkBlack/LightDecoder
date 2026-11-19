package com.lightdecoder.imessage.direct

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.ParticipantDto
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MmcsFile
import uniffi.rust_bridge.MonogramData
import uniffi.rust_bridge.PhotoPosterContentsFrame
import uniffi.rust_bridge.PosterColor
import uniffi.rust_bridge.PosterRole
import uniffi.rust_bridge.PosterType
import uniffi.rust_bridge.PrPosterContentMaterialStyle
import uniffi.rust_bridge.PushChatMessage
import uniffi.rust_bridge.SetTranscriptBackgroundMessage
import uniffi.rust_bridge.SimplifiedPoster
import uniffi.rust_bridge.SimplifiedTranscriptPoster
import uniffi.rust_bridge.TranscriptDynamicUserData

/**
 * iOS 26's chat backgrounds through the iMessage library: what Apple's change maps to, the poster
 * fetched and its picture written (the library's calls faked), stored per chat with its version
 * (an older one ignored), taken off, and set from here.
 */
@RunWith(AndroidJUnit4::class)
class ChatBackgroundsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ArchiveDb
    private lateinit var repo: ArchiveRepository
    private val me = "tel:+15550000001"
    private val bob = "tel:+15559876543"
    private val carol = "tel:+15551112222"

    @Before
    fun setUp() {
        context.deleteDatabase(DB)
        db = ArchiveDb(context, DB)
        repo = ArchiveRepository.forTesting(db, ChatService.IMESSAGE)
        ArchiveRepository.installForTesting(repo)
        IMessageManager.installForTesting(context, repo)
        ChatBackgrounds.dir(context).deleteRecursively()
    }

    @After
    fun tearDown() {
        DirectPrefs.setRegistered(context, false)
        DirectLink.resetForTesting()
        IMessageManager.resetForTesting()
        ArchiveRepository.resetForTesting()
        db.close()
        ChatBackgrounds.dir(context).deleteRecursively()
    }

    // --- what Apple's change is for -----------------------------------------------------------------

    private fun set(bid: Long, chatId: String? = null) = SetTranscriptBackgroundMessage.Set(
        1u, bid.toULong(), chatId, "OBJ", 1u, "BG-1", "https://mmcs.example/obj", "c2ln", "AGtleQ==", 0UL,
    )

    private fun remove(bid: Long, chatId: String? = null) = SetTranscriptBackgroundMessage.Remove(1u, bid.toULong(), chatId, true)

    private fun inst(guid: String, sender: String, m: SetTranscriptBackgroundMessage, participants: List<String> = listOf(me, sender), groupGuid: String? = null) =
        MessageInst(
            guid, sender, ConversationData(participants, if (groupGuid != null) "Trip" else null, groupGuid, null),
            PushChatMessage.SetTranscriptBackground(m), 1_700_000_009_000UL, null, false, false, null,
        )

    @Test
    fun theChatIsTheOnesItNamesOrTheSenders() {
        val mine = setOf(me)
        // From Bob, naming no chat: the chat with him.
        assertEquals("imsg:$bob", DirectArchive.backgroundChat(inst("A1", bob, set(5)), mine)!!.conversationId)
        assertEquals(bob, DirectArchive.backgroundChat(inst("A1", bob, set(5)), mine)!!.peer)
        // From the user's other device, as OpenBubbles tells them: the other person's address.
        val fromMe = DirectArchive.backgroundChat(inst("A2", me, set(5, "+15559876543"), participants = listOf(me)), mine)!!
        assertEquals("imsg:$bob", fromMe.conversationId)
        assertEquals(bob, fromMe.peer)
        // A group, by its guid.
        val group = DirectArchive.backgroundChat(inst("A3", carol, remove(6, "G-77"), listOf(me, bob, carol), "G-77"), mine)!!
        assertEquals("imsg:group:G-77", group.conversationId)
        assertEquals("G-77", group.groupGuid)
        assertNull(group.peer)
        // Naming the user: no chat.
        assertNull(DirectArchive.backgroundChat(inst("A4", bob, set(5, "+15550000001")), mine))
    }

    // --- fetched, its picture written (the library faked) --------------------------------------------

    private class FakeNatives(private val poster: SimplifiedTranscriptPoster?) : IncomingBackgrounds.Natives {
        val downloads = ArrayList<MmcsFile>()
        override fun toMmcs(message: SetTranscriptBackgroundMessage): MmcsFile? =
            (message as? SetTranscriptBackgroundMessage.Set)?.let { MmcsFile(byteArrayOf(1), it.objectId, it.url, byteArrayOf(2), 0UL) }

        override suspend fun download(file: MmcsFile, path: String) {
            downloads += file
            File(path).writeBytes(byteArrayOf(0x50, 0x4b, 3, 4))
        }

        override fun parse(payload: ByteArray): SimplifiedTranscriptPoster {
            assertEquals(0x50.toByte(), payload[0])
            return poster ?: throw IllegalStateException("no poster")
        }
    }

    private fun jpeg(width: Int, height: Int, color: Int): ByteArray {
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun photo(width: Int = 400, height: Int = 800, color: Int = Color.RED): SimplifiedTranscriptPoster =
        BackgroundPosters.photoPoster(width, height, jpeg(width, height, color), jpeg(82, 100, Color.BLUE), aspect = 0.5, uuid = "U-1")

    private fun titleOf(p: SimplifiedTranscriptPoster) = p.poster.titleConfiguration

    private fun dynamic(): SimplifiedTranscriptPoster {
        val p = photo()
        return p.copy(poster = SimplifiedPoster(titleOf(p), PosterType.TranscriptDynamic(TranscriptDynamicUserData("")), PosterRole.PR_POSTER_ROLE_BACKDROP))
    }

    private fun receive(i: MessageInst, natives: FakeNatives) =
        runBlocking { IncomingBackgrounds(context, natives).receive(i, setOf(me), Handles::display, DirectTexts(context)) }

    @Test
    fun aPhotoIsFetchedAndItsPictureWrittenForTheMainProcess() {
        val natives = FakeNatives(photo())
        val u = receive(inst("B1", bob, set(1_000)), natives) as DirectArchive.Update.Background
        assertEquals("OBJ", natives.downloads.single().`object`)
        assertEquals("imsg:$bob", u.conversationId)
        assertEquals(1_000L, u.version)
        val file = File(u.path!!)
        assertTrue("in the backgrounds' folder", ChatBackgrounds.isOurs(context, file))
        val bitmap = BitmapFactory.decodeFile(file.path)!!
        val px = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        assertTrue("the photo's own picture", Color.red(px) > 200 && Color.green(px) < 60 && Color.blue(px) < 60)
        assertEquals("+15559876543 changed the chat background.", u.row!!.message.text)
        assertEquals("system", u.row!!.message.direction)
        // As it crosses to the main process.
        val wired = DirectWire.decode(JSONObject(DirectWire.encode(u).toString())) as DirectArchive.Update.Background
        assertEquals(u.copy(row = null), wired.copy(row = null))
        assertEquals(u.row!!.message.text, wired.row!!.message.text)
    }

    @Test
    fun aDynamicOrRemovedBackgroundIsNone() {
        val natives = FakeNatives(dynamic())
        val d = receive(inst("B2", bob, set(2_000)), natives) as DirectArchive.Update.Background
        assertNull(d.path)
        assertEquals("+15559876543 removed the chat background.", d.row!!.message.text)
        // Taken off: nothing is fetched.
        val none = FakeNatives(null)
        val r = receive(inst("B3", bob, remove(3_000)), none) as DirectArchive.Update.Background
        assertNull(r.path)
        assertEquals(3_000L, r.version)
        assertTrue(none.downloads.isEmpty())
    }

    @Test
    fun aPosterWithNoPictureIsIgnoredAndAFailedFetchThrows() {
        val p = photo()
        val monogram = p.copy(
            watch = p.watch.copy(backgroundImageData = ByteArray(0)),
            poster = SimplifiedPoster(
                titleOf(p),
                PosterType.Monogram(MonogramData(PosterColor(1.0, 0.0, 0.0, 0.0), PosterColor(1.0, 0.0, 0.0, 0.0), "B", true), PosterColor(1.0, 0.0, 0.0, 0.0)),
                PosterRole.PR_POSTER_ROLE_BACKDROP,
            ),
        )
        assertEquals(DirectArchive.Update.Ignore, receive(inst("B4", bob, set(4_000)), FakeNatives(monogram)))
        // A monogram with the Watch's picture shows that.
        val withWatch = monogram.copy(watch = p.watch)
        assertNotNull((receive(inst("B5", bob, set(4_001)), FakeNatives(withWatch)) as DirectArchive.Update.Background).path)
        try {
            receive(inst("B6", bob, set(4_002)), FakeNatives(null))
            fail("an unreadable poster was taken")
        } catch (e: IllegalStateException) {
            // The engine logs it and goes on.
        }
    }

    // --- stored per chat ---------------------------------------------------------------------------

    private fun incomingPicture(color: Int): String {
        val f = ChatBackgrounds.incomingFile(context)
        f.writeBytes(jpeg(40, 80, color))
        return f.path
    }

    private fun wire(u: DirectArchive.Update) = DirectWire.decode(JSONObject(DirectWire.encode(u).toString()))

    private fun change(guid: String, sender: String, m: SetTranscriptBackgroundMessage, path: String?, participants: List<String> = listOf(me, sender), groupGuid: String? = null) =
        wire(DirectArchive.background(inst(guid, sender, m, participants, groupGuid), setOf(me), Handles::display, DirectTexts(context), path))

    @Test
    fun aNewBackgroundIsKeptPerChatAndAnOlderOneIgnored() {
        val link = DirectLink.get(context)
        val store = ChatBackgrounds(context)
        link.apply(change("C1", bob, set(10_000), incomingPicture(Color.RED)))
        val first = store.of("imsg:$bob")
        assertEquals(10_000L, first.version)
        val picture = first.file!!
        assertTrue(picture.isFile)
        assertEquals("+15559876543 changed the chat background.", repo.message("imsg:C1")!!.text)
        assertEquals("system", repo.message("imsg:C1")!!.direction)

        // Older than the one shown (a slow device): ignored, its picture dropped, no row.
        val late = incomingPicture(Color.GREEN)
        link.apply(change("C2", bob, set(9_000), late))
        assertEquals(first, store.of("imsg:$bob"))
        assertFalse(File(late).exists())
        assertNull(repo.message("imsg:C2"))

        // The same one again (Apple's journal after a restart): nothing changes.
        val again = incomingPicture(Color.RED)
        link.apply(change("C1", bob, set(10_000), again))
        assertEquals(picture, store.pictureOf("imsg:$bob"))
        assertFalse(File(again).exists())

        // Newer: replaces it, the old picture deleted.
        link.apply(change("C3", bob, set(11_000), incomingPicture(Color.BLUE)))
        assertEquals(11_000L, store.of("imsg:$bob").version)
        assertFalse(picture.exists())

        // Taken off: none, and an older set does not put it back.
        link.apply(change("C4", bob, remove(12_000), null))
        assertNull(store.pictureOf("imsg:$bob"))
        assertEquals(12_000L, store.of("imsg:$bob").version)
        assertEquals("+15559876543 removed the chat background.", repo.message("imsg:C4")!!.text)
        link.apply(change("C5", bob, set(11_500), incomingPicture(Color.RED)))
        assertNull(store.pictureOf("imsg:$bob"))
        // Other chats are their own.
        assertEquals(ChatBackgrounds.Background(null, 0), store.of("imsg:$carol"))
    }

    @Test
    fun aPersonsBlueBubblesChatAndAGroupGetTheirs() {
        repo.upsertConversation(
            ConversationDto(
                id = "iMessage;-;+15559876543", type = IMessageManager.TRANSPORT,
                participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = "+15559876543", name = "Bob", number = "+15559876543")),
            ),
        )
        val link = DirectLink.get(context)
        // Set on the user's own iPhone, which names Bob's chat.
        link.apply(change("D1", me, set(20_000, "+15559876543"), incomingPicture(Color.RED), participants = listOf(me)))
        assertNotNull(ChatBackgrounds(context).pictureOf("iMessage;-;+15559876543"))
        assertEquals("You changed the chat background.", repo.message("imsg:D1")!!.text)
        assertEquals("iMessage;-;+15559876543", repo.message("imsg:D1")!!.conversationId)
        // A group, by its guid.
        link.apply(change("D2", carol, set(21_000, "G-9"), incomingPicture(Color.BLUE), listOf(me, bob, carol), "G-9"))
        assertNotNull(ChatBackgrounds(context).pictureOf("imsg:group:G-9"))
        assertEquals("imsg:group:G-9", repo.message("imsg:D2")!!.conversationId)
    }

    // --- set from here -------------------------------------------------------------------------------

    private fun directMode() {
        DirectPrefs.setRegistered(context, true)
        DirectLibrary.REQUIRED.forEach { File(DirectLibrary.dir(context), it).apply { parentFile?.mkdirs(); writeText("") } }
    }

    private fun bobsChat() = repo.upsertConversation(
        ConversationDto(
            id = "imsg:$bob", type = IMessageManager.TRANSPORT, defaultOutgoingId = me,
            participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = "+15559876543", name = "Bob", number = "+15559876543")),
        ),
    )

    @Test
    fun aPhotoPickedGoesToAppleAndShowsOnceConfirmed() {
        directMode()
        bobsChat()
        val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())
        val link = DirectLink.installForTesting(context) { what, request, _ ->
            asked += what to request
            if (what == DirectWire.BACKGROUND) {
                // While Apple is asked, the picture is where the other process may read it.
                val r = DirectWire.BackgroundRequest.fromJson(request)
                assertTrue(r.path == null || ChatBackgrounds.isOurs(context, File(r.path!!)))
                assertTrue(r.path == null || BitmapFactory.decodeFile(r.path) != null)
            }
            DirectWire.ok()
        }
        val source = File(context.cacheDir, "picked.jpg").apply { writeBytes(jpeg(300, 600, Color.MAGENTA)) }
        val before = ChatBackgrounds.versionNow()
        runBlocking { link.setBackground("imsg:$bob", android.net.Uri.fromFile(source)) }
        val r = DirectWire.BackgroundRequest.fromJson(asked.single { it.first == DirectWire.BACKGROUND }.second)
        assertEquals(listOf("tel:+15559876543"), r.target.participants)
        assertTrue("a version as Apple counts them", r.version >= before)
        val shown = ChatBackgrounds(context).of("imsg:$bob")
        assertEquals(r.version, shown.version)
        assertNotNull(shown.file)
        assertTrue(repo.messages("imsg:$bob").any { it.text == "You changed the chat background." && it.direction == "system" })

        // Removed: the remove variant (no picture), a later version.
        runBlocking { link.setBackground("imsg:$bob", null) }
        val removed = DirectWire.BackgroundRequest.fromJson(asked.last().second)
        assertNull(removed.path)
        assertTrue(removed.version > r.version)
        assertNull(ChatBackgrounds(context).pictureOf("imsg:$bob"))
        assertTrue(repo.messages("imsg:$bob").any { it.text == "You removed the chat background." })
    }

    @Test
    fun aBackgroundAppleRefusedChangesNothingHere() {
        directMode()
        bobsChat()
        val link = DirectLink.installForTesting(context) { _, _, _ -> DirectWire.error("Apple said no") }
        val source = File(context.cacheDir, "picked.jpg").apply { writeBytes(jpeg(300, 600, Color.MAGENTA)) }
        try {
            runBlocking { link.setBackground("imsg:$bob", android.net.Uri.fromFile(source)) }
            fail("a refused background was taken")
        } catch (e: DirectEngine.ImessageError) {
            assertEquals("Apple said no", e.message)
        }
        assertEquals(ChatBackgrounds.Background(null, 0), ChatBackgrounds(context).of("imsg:$bob"))
        // The prepared picture does not stay behind.
        assertTrue(ChatBackgrounds.dir(context).listFiles().orEmpty().none { it.name.endsWith(".jpg") })
    }

    // --- the poster for a photo (OpenBubbles' editor's) ------------------------------------------------

    @Test
    fun aPhotoPosterHasWhatPosterKitNeeds() {
        val heic = byteArrayOf(9, 9)
        val p = BackgroundPosters.photoPoster(1200, 900, heic, byteArrayOf(1), aspect = 0.5, uuid = "U-9", timeNumberingSystem = byteArrayOf(7))
        assertEquals(PosterRole.PR_POSTER_ROLE_BACKDROP, p.poster.role)
        assertEquals(BackgroundPosters.PHOTOS_PROVIDER, p.watch.extensionIdentifier)
        assertTrue(p.watch.backgroundImageData.contentEquals(byteArrayOf(1)))
        val asset = (p.poster.type as PosterType.Photo).assets.single()
        assertEquals("U-9", asset.uuid)
        assertEquals(listOf("background-backfill", "background"), asset.contents.layers.map { it.identifier })
        assertEquals(asset.contents.layers.map { it.filename }.toSet(), asset.files.keys)
        assertTrue(asset.files.values.all { it.contentEquals(heic) })
        val layout = asset.contents.properties.portraitLayout
        assertEquals(1200.0, layout.imageSize.width, 0.0)
        // A screen half as wide as it is tall: the middle 450 x 900 of a 1200 x 900 photo.
        assertEquals(PhotoPosterContentsFrame(450.0, 900.0, 375.0, 0.0), layout.visibleFrame)
        // Packing needs a title style.
        assertEquals(PrPosterContentMaterialStyle.PrPosterContentVibrantMaterialStyle, p.poster.titleConfiguration.titleStyle)
        assertTrue(p.poster.titleConfiguration.timeNumberingSystem!!.contentEquals(byteArrayOf(7)))
    }

    @Test
    fun theLayerIsCutToThePartOnScreen() {
        val p = BackgroundPosters.photoPoster(400, 800, jpeg(400, 800, Color.RED), ByteArray(0), aspect = 0.25)
        val (source, crop) = BackgroundPosters.pictureSources(p).single()
        assertTrue(source.bytes.isNotEmpty())
        // 200 x 800 in the middle of 400 x 800, in a picture decoded at half size.
        assertEquals(BackgroundPosters.CropBox(50, 0, 100, 400), BackgroundPosters.cropOf(crop!!, 200, 400))
        // y counts up from the bottom: a frame at the bottom of the picture is at the bottom of the layer.
        val layout = (p.poster.type as PosterType.Photo).assets.single().contents.properties.portraitLayout
        val bottom = BackgroundPosters.PhotoCrop(layout.copy(visibleFrame = PhotoPosterContentsFrame(400.0, 200.0, 0.0, 0.0)), crop.layerFrame)
        assertEquals(BackgroundPosters.CropBox(0, 600, 400, 200), BackgroundPosters.cropOf(bottom, 400, 800))
        // The whole picture: no cut.
        val whole = BackgroundPosters.PhotoCrop(layout.copy(visibleFrame = PhotoPosterContentsFrame(400.0, 800.0, 0.0, 0.0)), crop.layerFrame)
        assertNull(BackgroundPosters.cropOf(whole, 400, 800))
        // A Watch picture comes after the photo's own.
        val both = p.copy(watch = p.watch.copy(backgroundImageData = byteArrayOf(1)))
        assertEquals(2, BackgroundPosters.pictureSources(both).size)
        assertNull(BackgroundPosters.pictureSources(both).last().second)
        assertTrue(BackgroundPosters.isRemoval(dynamic()))
        assertFalse(BackgroundPosters.isRemoval(p))
    }

    @Test
    fun versionsAreApplesNanosecondsSince2001() {
        // 2001-01-01T00:00:00Z is 978307200000 ms.
        assertEquals(0L, ChatBackgrounds.versionNow(978_307_200_000L))
        assertEquals(1_000_000_000L, ChatBackgrounds.versionNow(978_307_201_000L))
    }

    private companion object {
        const val DB = "chat-backgrounds-test.db"
    }
}
