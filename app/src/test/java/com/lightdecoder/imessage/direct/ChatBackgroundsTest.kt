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

    private fun gradient(vararg colors: PosterColor): SimplifiedTranscriptPoster {
        val p = photo()
        return p.copy(poster = SimplifiedPoster(titleOf(p), PosterType.TranscriptGradient(colors.toList()), PosterRole.PR_POSTER_ROLE_BACKDROP))
    }

    private fun dynamicNamed(identifier: String): SimplifiedTranscriptPoster {
        val p = photo()
        return p.copy(poster = SimplifiedPoster(titleOf(p), PosterType.TranscriptDynamic(TranscriptDynamicUserData(identifier)), PosterRole.PR_POSTER_ROLE_BACKDROP))
    }

    @Test
    fun aGradientOrDynamicBackgroundIsKeptToBeDrawnNotTakenOff() {
        // A gradient: its colours (PosterColor is alpha, blue, green, red), no picture, "changed".
        val g = receive(inst("B2", bob, set(2_000)), FakeNatives(gradient(PosterColor(1.0, 0.0, 0.5, 1.0), PosterColor(1.0, 1.0, 0.0, 0.0)))) as DirectArchive.Update.Background
        assertNull(g.path)
        assertEquals(BackgroundStyle.Gradient(listOf(Rgba(1.0, 0.5, 0.0, 1.0), Rgba(0.0, 0.0, 1.0, 1.0))), g.style)
        assertEquals("+15559876543 changed the chat background.", g.row!!.message.text)
        // Aurora, by its identifier.
        val d = receive(inst("B2b", bob, set(2_001)), FakeNatives(dynamicNamed("aurora_1"))) as DirectArchive.Update.Background
        assertNull(d.path)
        assertEquals(BackgroundStyle.Dynamic("aurora_1"), d.style)
        assertTrue((d.style as BackgroundStyle.Dynamic).isKnown)
        assertEquals("+15559876543 changed the chat background.", d.row!!.message.text)
        // As they cross to the main process.
        for (u in listOf(g, d)) {
            val wired = DirectWire.decode(JSONObject(DirectWire.encode(u).toString())) as DirectArchive.Update.Background
            assertEquals(u.copy(row = null), wired.copy(row = null))
        }
        // Nothing is left in the backgrounds' folder for them.
        assertTrue(ChatBackgrounds.dir(context).listFiles().orEmpty().none { it.name.endsWith(".jpg") })
        // Taken off: nothing is fetched.
        val none = FakeNatives(null)
        val r = receive(inst("B3", bob, remove(3_000)), none) as DirectArchive.Update.Background
        assertNull(r.path)
        assertNull(r.style)
        assertEquals("+15559876543 removed the chat background.", r.row!!.message.text)
        assertEquals(3_000L, r.version)
        assertTrue(none.downloads.isEmpty())
    }

    @Test
    fun gradientColoursRoundTripAsPosterKitWritesThem() {
        val colors = listOf(Rgba(1.0, 0.5, 0.25, 1.0), Rgba(0.0, 0.123456, 1.0, 0.5))
        val text = BackgroundStyle.encodeColors(colors)
        assertEquals("1/0.5/0.25/1//0/0.123456/1/0.5", text)
        assertEquals(colors, BackgroundStyle.decodeColors(text))
        // As the library writes them (Rust's float formatting), and tolerantly.
        assertEquals(listOf(Rgba(1.0, 0.0, 0.0, 1.0)), BackgroundStyle.decodeColors("1.0/0/0/1"))
        assertEquals(listOf(Rgba(1.0, 0.0, 0.0, 1.0)), BackgroundStyle.decodeColors("255/0/0"))
        assertEquals(emptyList<Rgba>(), BackgroundStyle.decodeColors("junk"))
        // Kind and data, as stored and wired.
        val g = BackgroundStyle.Gradient(colors)
        assertEquals(g, BackgroundStyle.of(g.type, g.data))
        assertEquals(BackgroundStyle.Dynamic("aurora_1"), BackgroundStyle.of("dynamic", "aurora_1"))
        assertNull(BackgroundStyle.of("photo", ""))
        assertNull(BackgroundStyle.of(null, null))
        // Android colours.
        assertEquals(0xFFFF8040.toInt(), Rgba.of(0xFFFF8040.toInt()).argb())
        // To and from PosterKit's poster type.
        assertEquals(g, BackgroundPosters.styleOf(BackgroundPosters.drawnPoster(g, ByteArray(0))))
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

    @Test
    fun aDrawnBackgroundIsStoredWithItsKindAndAnUnknownOneIsLogged() {
        val link = DirectLink.get(context)
        val store = ChatBackgrounds(context)
        val sunset = BackgroundPalettes.ALL.first().style
        link.apply(wire(DirectArchive.background(inst("E1", bob, set(30_000)), setOf(me), Handles::display, DirectTexts(context), null, sunset)))
        val shown = store.of("imsg:$bob")
        assertEquals(sunset, shown.style)
        assertNull(shown.file)
        assertTrue(shown.isSet)
        assertEquals("+15559876543 changed the chat background.", repo.message("imsg:E1")!!.text)

        // A photo after it: the photo, the style gone.
        link.apply(change("E2", bob, set(31_000), incomingPicture(Color.RED)))
        assertNull(store.of("imsg:$bob").style)
        assertNotNull(store.of("imsg:$bob").file)

        // A dynamic one not known here: kept (drawn as a plain gradient), its name in the iMessage log.
        com.lightdecoder.imessage.IMessageLog.clear(context)
        val old = store.of("imsg:$bob").file!!
        link.apply(wire(DirectArchive.background(inst("E3", bob, set(32_000)), setOf(me), Handles::display, DirectTexts(context), null, BackgroundStyle.Dynamic("comet_7"))))
        assertEquals(BackgroundStyle.Dynamic("comet_7"), store.of("imsg:$bob").style)
        assertFalse("the photo before it goes", old.exists())
        val log = com.lightdecoder.imessage.IMessageLog.read(context)
        assertTrue(log.toString(), log.any { it.contains("unknown dynamic background \"comet_7\"") })
        // Aurora is known: not logged as unknown.
        link.apply(wire(DirectArchive.background(inst("E4", bob, set(33_000)), setOf(me), Handles::display, DirectTexts(context), null, BackgroundStyle.Dynamic("aurora_1"))))
        assertEquals(1, com.lightdecoder.imessage.IMessageLog.read(context).count { it.contains("unknown dynamic background") })
        assertEquals(BackgroundStyle.Dynamic("aurora_1"), store.of("imsg:$bob").style)
    }

    // --- drawn here ---------------------------------------------------------------------------------

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun distinctColours(b: Bitmap) = pixels(b).toSet().size

    @Test
    fun everyThemeIsDrawnTheSameEachTimeAtTheSizeAsked() {
        assertEquals(10, BackgroundThemes.ALL.size)
        for (theme in BackgroundThemes.ALL) {
            val a = BackgroundThemes.render(theme.id, 108, 234)!!
            assertEquals(108, a.width)
            assertEquals(234, a.height)
            assertTrue("${theme.id} is not blank", distinctColours(a) > 20)
            assertTrue("${theme.id} is opaque", pixels(a).all { Color.alpha(it) == 255 })
            val b = BackgroundThemes.render(theme.id, 108, 234)!!
            assertTrue("${theme.id} is the same each time", pixels(a).contentEquals(pixels(b)))
        }
        // The designs differ from one another.
        val firsts = BackgroundThemes.ALL.map { pixels(BackgroundThemes.render(it.id, 54, 117)!!).toList() }
        assertEquals(firsts.size, firsts.toSet().size)
        assertNull(BackgroundThemes.render("nope", 10, 10))
        // The size sent: the screen's, portrait, at most 2048 on its longest side.
        assertEquals(1080 to 1920, DirectLink.themeSize(1080, 1920))
        assertEquals(1080 to 1920, DirectLink.themeSize(1920, 1080))
        assertEquals(945 to 2048, DirectLink.themeSize(1080, 2340))
    }

    @Test
    fun gradientsAndTheAuroraAreDrawnHere() {
        for (palette in BackgroundPalettes.ALL) {
            val b = BackgroundArt.render(palette.style, 60, 120)
            val top = b.getPixel(30, 2)
            val bottom = b.getPixel(30, 117)
            assertTrue("${palette.id} blends from top to bottom", top != bottom)
            assertTrue(pixels(b).all { Color.alpha(it) == 255 })
        }
        // A blend: the middle lies between the ends.
        val bw = BackgroundArt.render(BackgroundStyle.Gradient(listOf(Rgba(1.0, 1.0, 1.0), Rgba(0.0, 0.0, 0.0))), 40, 200)
        val mid = Color.red(bw.getPixel(20, 100))
        assertTrue(mid in 40..215)
        assertTrue(Color.red(bw.getPixel(20, 1)) > Color.red(bw.getPixel(20, 198)))
        // Aurora: an animated drawing, still until started.
        val aurora = BackgroundArt.drawableFor(BackgroundStyle.Dynamic("aurora_1"), animate = true)
        assertTrue(aurora is AuroraDrawable)
        assertFalse((aurora as AuroraDrawable).isRunning)
        aurora.start()
        assertTrue(aurora.isRunning)
        aurora.stop()
        assertFalse(aurora.isRunning)
        // Animations off: it never runs.
        val still = BackgroundArt.drawableFor(BackgroundStyle.Dynamic("aurora_1"), animate = false) as AuroraDrawable
        still.start()
        assertFalse(still.isRunning)
        assertTrue(distinctColours(BackgroundArt.render(BackgroundStyle.Dynamic("aurora_1"), 60, 120)) > 20)
        // Unknown: a calm gradient from its name, the same each time, different per name.
        assertTrue(BackgroundArt.drawableFor(BackgroundStyle.Dynamic("comet_7"), animate = true) is GradientBackgroundDrawable)
        assertTrue(BackgroundArt.neutralFor("comet_7").contentEquals(BackgroundArt.neutralFor("comet_7")))
        assertFalse(BackgroundArt.neutralFor("comet_7").contentEquals(BackgroundArt.neutralFor("nebula_2")))
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
    fun aGradientAuroraOrThemePickedGoesToAppleAsSuch() {
        directMode()
        bobsChat()
        val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())
        val link = DirectLink.installForTesting(context) { what, request, _ ->
            asked += what to request
            if (what == DirectWire.BACKGROUND) {
                val r = DirectWire.BackgroundRequest.fromJson(request)
                assertTrue(r.path == null || BitmapFactory.decodeFile(r.path) != null)
            }
            DirectWire.ok()
        }
        val ocean = BackgroundPalettes.ALL.first { it.id == "ocean" }.style
        runBlocking { link.setBackground("imsg:$bob", BackgroundChoice.Drawn(ocean)) }
        val g = DirectWire.BackgroundRequest.fromJson(asked.last().second)
        assertNull(g.path)
        assertEquals(ocean, g.style)
        assertEquals(ocean, ChatBackgrounds(context).of("imsg:$bob").style)
        assertTrue(repo.messages("imsg:$bob").any { it.text == "You changed the chat background." })

        runBlocking { link.setBackground("imsg:$bob", BackgroundChoice.Drawn(BackgroundStyle.Dynamic("aurora_1"))) }
        assertEquals(BackgroundStyle.Dynamic("aurora_1"), DirectWire.BackgroundRequest.fromJson(asked.last().second).style)

        // A design: drawn now, sent as a photo.
        runBlocking { link.setBackground("imsg:$bob", BackgroundChoice.Theme("galaxy")) }
        val t = DirectWire.BackgroundRequest.fromJson(asked.last().second)
        assertNull(t.style)
        assertNotNull(t.path)
        val shown = ChatBackgrounds(context).of("imsg:$bob")
        assertNull(shown.style)
        val bitmap = BitmapFactory.decodeFile(shown.file!!.path)!!
        assertTrue(bitmap.height > bitmap.width)
    }

    /** The library's packing and upload, faked: what was packed. */
    private class FakeOutgoing : OutgoingBackgrounds.Natives {
        val packed = ArrayList<SimplifiedTranscriptPoster>()
        val uploads = ArrayList<String>()
        override fun nsNull(): ByteArray? = byteArrayOf(7)
        override suspend fun pack(poster: SimplifiedTranscriptPoster): ByteArray {
            packed += poster
            return byteArrayOf(0x50, 0x4b, 3, 4)
        }
        override suspend fun upload(path: String): MmcsFile? {
            uploads += path
            assertEquals(0x50.toByte(), File(path).readBytes()[0])
            return MmcsFile(byteArrayOf(1), "OBJ", "https://mmcs.example/obj", byteArrayOf(2), 4UL)
        }
        override fun heic(bitmap: Bitmap, scratch: File): ByteArray? = null
    }

    @Test
    fun theOutgoingPosterIsAGradientADynamicOrAPhoto() {
        val natives = FakeOutgoing()
        val out = OutgoingBackgrounds(context, natives)
        val sunset = BackgroundPalettes.ALL.first().style
        assertEquals("OBJ", runBlocking { out.upload(null, sunset) }.`object`)
        val g = natives.packed.last()
        val type = g.poster.type as PosterType.TranscriptGradient
        assertEquals(sunset.colors, type.colors.map { Rgba(it.red, it.green, it.blue, it.alpha) })
        assertEquals(BackgroundPosters.GRADIENT_PROVIDER, g.watch.extensionIdentifier)
        assertEquals(PosterRole.PR_POSTER_ROLE_BACKDROP, g.poster.role)
        assertTrue(g.poster.titleConfiguration.timeNumberingSystem!!.contentEquals(byteArrayOf(7)))
        val watch = BitmapFactory.decodeByteArray(g.watch.backgroundImageData, 0, g.watch.backgroundImageData.size)!!
        assertEquals(BackgroundPosters.WATCH_WIDTH, watch.width)
        assertEquals(BackgroundPosters.WATCH_HEIGHT, watch.height)
        assertTrue(g.watch.luminance > 0.0)

        runBlocking { out.upload(null, BackgroundStyle.Dynamic("aurora_1")) }
        val d = natives.packed.last()
        assertEquals("aurora_1", (d.poster.type as PosterType.TranscriptDynamic).data.identifier)
        assertEquals(BackgroundPosters.DYNAMIC_PROVIDER, d.watch.extensionIdentifier)
        assertFalse(d.watch.isHighKey)

        // A photo (a design is one too): the photo poster, the JPEG as its layers where HEIC cannot be made.
        val jpeg = File(context.cacheDir, "theme.jpg").apply { writeBytes(jpeg(90, 180, Color.CYAN)) }
        runBlocking { out.upload(jpeg, null) }
        val p = natives.packed.last()
        assertEquals(BackgroundPosters.PHOTOS_PROVIDER, p.watch.extensionIdentifier)
        assertTrue((p.poster.type as PosterType.Photo).assets.single().files.values.all { it.contentEquals(jpeg.readBytes()) })
        // The zips do not stay behind.
        assertTrue(natives.uploads.none { File(it).exists() })
        // Nothing to send: said so.
        try {
            runBlocking { out.upload(null, null) }
            fail("no background was taken")
        } catch (e: DirectEngine.ImessageError) {
            assertEquals(context.getString(com.lightdecoder.R.string.imessage_background_unreadable), e.message)
        }
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
        assertEquals(BackgroundStyle.Dynamic(""), BackgroundPosters.styleOf(dynamic()))
        assertNull(BackgroundPosters.styleOf(p))
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
