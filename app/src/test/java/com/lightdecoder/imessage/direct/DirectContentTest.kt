package com.lightdecoder.imessage.direct

import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.IMessagePrefs
import com.lightdecoder.relay.MessageNotifier
import java.io.ByteArrayOutputStream
import java.net.InetAddress
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import uniffi.rust_bridge.Balloon
import uniffi.rust_bridge.BalloonLayout
import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.ExtensionApp
import uniffi.rust_bridge.IndexedMessagePart
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MessagePart
import uniffi.rust_bridge.MessageType
import uniffi.rust_bridge.NormalMessage
import uniffi.rust_bridge.NsDictionaryClass
import uniffi.rust_bridge.PushChatMessage
import uniffi.rust_bridge.ReactMessage
import uniffi.rust_bridge.ReactMessageType
import uniffi.rust_bridge.TextFlags
import uniffi.rust_bridge.TextFormat

/**
 * Message content through the iMessage library, in the main process ([DirectLink]): mentions sent
 * and notified, link previews sent with their picture and received with one, stickers on messages,
 * and polls (cards, votes both ways, a choice added). Who may change what is checked as for tapbacks.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DirectContentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ArchiveDb
    private lateinit var repo: ArchiveRepository
    private val me = "tel:+15550000001"
    private val alice = "tel:+15551234567"
    private val bob = "tel:+15559876543"
    private val dave = "tel:+15554443333"
    private val group = "imsg:group:GRP-1"
    private val plain = TextFormat.Flags(TextFlags(false, false, false, false))

    @Before
    fun setUp() {
        context.deleteDatabase(DB)
        db = ArchiveDb(context, DB)
        repo = ArchiveRepository.forTesting(db, ChatService.IMESSAGE)
        ArchiveRepository.installForTesting(repo)
        IMessageManager.installForTesting(context, repo)
        DirectPrefs.setLinkPreviews(context, true)
        DirectPrefs.setNotifyWhenMentioned(context, true)
    }

    @After
    fun tearDown() {
        MessageNotifier.cancelAll(context, ChatService.IMESSAGE)
        IMessagePrefs.setEnabled(context, false)
        DirectPrefs.setLinkPreviews(context, true)
        DirectPrefs.setNotifyWhenMentioned(context, true)
        DirectPrefs.setRegistered(context, false)
        DirectLink.resetForTesting()
        IMessageManager.resetForTesting()
        ArchiveRepository.resetForTesting()
        db.close()
        com.lightdecoder.archive.MediaCache.mediaDir(context, ChatService.IMESSAGE).deleteRecursively()
    }

    // --- set-up ----------------------------------------------------------------------------------

    private fun wire(u: DirectArchive.Update) = DirectWire.decode(JSONObject(DirectWire.encode(u).toString()))

    private fun inst(guid: String, from: String, m: PushChatMessage, participants: List<String> = listOf(me, alice, bob), groupGuid: String? = "GRP-1", ts: Long = 1_700_000_000_000L) =
        MessageInst(guid, from, ConversationData(participants, null, groupGuid, null), m, ts.toULong(), null, false, false, null)

    private fun map(i: MessageInst) = wire(DirectArchive.map(i, setOf(me), Handles::display, DirectTexts(context)))

    private fun text(s: String) = IndexedMessagePart(MessagePart.Text(s, plain), null, null)

    private fun normal(parts: List<IndexedMessagePart>, app: ExtensionApp? = null, link: uniffi.rust_bridge.LinkMeta? = null) =
        PushChatMessage.Message(NormalMessage(parts, null, null, null, MessageType.IMessage, null, app, link, false, null, null))

    /** The group with Alice and Bob, as Alice's first message to it stored it. */
    private fun aGroup(link: DirectLink) {
        link.apply(map(inst("G0", alice, normal(listOf(text("hello all"))))))
        assertNotNull(repo.conversation(group))
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.RED)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** What the ":imessage" process writes aside under [key] for a change it hands over. */
    private fun writtenAside(key: String) = DirectMedia.pendingInlineFile(DirectLink.pictureFile(context, key)).apply {
        parentFile?.mkdirs()
        writeBytes(png())
    }

    /** The iMessage library set up (as far as the link can tell): what it offers depends on it. */
    private fun activate() {
        DirectPrefs.setRegistered(context, true)
        DirectLibrary.REQUIRED.forEach { java.io.File(DirectLibrary.dir(context), it).apply { parentFile?.mkdirs(); writeText("") } }
    }

    private fun waitFor(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    // --- mentions ---------------------------------------------------------------------------------

    @Test
    fun aMentionGoesToAppleWithItsMemberAndBothCopiesKeepIt() {
        val asked = Collections.synchronizedList(ArrayList<JSONObject>())
        val link = DirectLink.installForTesting(context) { what, request, _ ->
            if (what == DirectWire.SEND) asked += request
            DirectWire.sent("S1", 1_700_000_005_000L)
        }
        aGroup(link)
        val tmpId = link.sendText(group, "Hi Alice, lunch?", null, mentions = listOf(Mention(3, 8, "+15551234567")))
        // The copy here shows it at once.
        assertEquals(listOf(Mention(3, 8, "+15551234567")), IMessageExtra.parse(repo.message(MessageRow.tmpMessageId(tmpId))?.extra ?: repo.message("imsg:S1")!!.extra).mentions[0])
        waitFor("the sent message") { repo.message("imsg:S1") != null }
        val request = DirectWire.SendRequest.fromJson(asked.single())
        assertEquals(listOf(Mention(3, 8, "+15551234567")), request.mentions)
        assertEquals("Hi Alice, lunch?", request.text)
        assertEquals(listOf(Mention(3, 8, "+15551234567")), IMessageExtra.parse(repo.message("imsg:S1")!!.extra).textMentions("Hi Alice, lunch?"))
    }

    @Test
    fun aMentionOfTheUserNotifiesEvenWithTheChatsAlertsHiddenUnlessTurnedOff() {
        IMessagePrefs.setEnabled(context, true)
        IMessagePrefs.setNotifyMessages(context, true)
        val link = DirectLink.installForTesting(context) { _, _, _ -> DirectWire.ok() }
        aGroup(link)
        repo.markConversationRead(group)
        MessageNotifier.cancelAll(context, ChatService.IMESSAGE)
        repo.setConversationMuted(group, true)
        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
        fun announced() = notifications.activeNotifications.any { it.tag?.startsWith("im:") == true }
        fun mentioning(guid: String, uri: String, ts: Long) =
            map(inst(guid, alice, normal(listOf(text("Hey "), IndexedMessagePart(MessagePart.Mention(uri, "You"), null, null))), ts = ts))
        // Muted: an ordinary message makes no sound, and neither does a mention of someone else.
        link.apply(map(inst("Q1", alice, normal(listOf(text("quiet please"))), ts = 1_700_000_001_000L)))
        link.apply(mentioning("Q2", "+15559876543", 1_700_000_002_000L))
        assertFalse(announced())
        // A mention of the user: it does.
        link.apply(mentioning("Q3", "+15550000001", 1_700_000_003_000L))
        assertTrue(announced())
        // With "Notify me when mentioned" off: it does not.
        MessageNotifier.cancelAll(context, ChatService.IMESSAGE)
        repo.markConversationRead(group)
        DirectPrefs.setNotifyWhenMentioned(context, false)
        link.apply(mentioning("Q4", "+15550000001", 1_700_000_004_000L))
        assertFalse(announced())
        // Not muted: it notifies as any message does.
        repo.setConversationMuted(group, false)
        link.apply(mentioning("Q5", "+15550000001", 1_700_000_005_000L))
        assertTrue(announced())
    }

    @Test
    fun theSettingsAreOnByDefaultAndKept() {
        DirectPrefs.setNotifyWhenMentioned(context, true)
        DirectPrefs.setLinkPreviews(context, true)
        assertTrue(DirectPrefs.notifyWhenMentioned(context))
        assertTrue(DirectPrefs.linkPreviews(context))
        DirectPrefs.setNotifyWhenMentioned(context, false)
        DirectPrefs.setLinkPreviews(context, false)
        assertFalse(DirectPrefs.notifyWhenMentioned(context))
        assertFalse(DirectPrefs.linkPreviews(context))
    }

    // --- link previews -----------------------------------------------------------------------------

    private val page = """<html><head><meta property="og:title" content="A story"><meta property="og:description" content="About it">
        <meta property="og:image" content="https://example.com/cover.png"></head></html>"""

    /** The network as the fetch sees it: the story's page and its picture. */
    private fun network(asked: MutableList<String>) = LinkPreviews.Fetcher { url, _, _, _ ->
        asked += url
        when (url) {
            "https://example.com/s" -> LinkPreviews.Response(200, null, "text/html", page.toByteArray())
            "https://example.com/cover.png" -> LinkPreviews.Response(200, null, "image/png", png())
            else -> throw java.io.IOException("no page")
        }
    }

    private val public = LinkPreviews.Policy(resolve = { listOf(InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))) })

    @Test
    fun aLinkGoesWithItsPagesPreviewAndPictureAndTheCopyHereShowsThemToo() {
        val asked = Collections.synchronizedList(ArrayList<JSONObject>())
        var pictureThere = false
        val link = DirectLink.installForTesting(context) { what, request, _ ->
            if (what == DirectWire.SEND) {
                asked += request
                // The picture is there, under the app's own files (where the other process may read), while Apple is asked.
                val path = DirectWire.SendRequest.fromJson(request).link?.imagePath
                pictureThere = path != null && java.io.File(path).length() > 0 && DirectPaths.isAllowed(path, DirectPaths.forApp(context))
            }
            DirectWire.sent("S2", 1_700_000_005_000L)
        }
        val fetched = Collections.synchronizedList(ArrayList<String>())
        link.linkFetcher = network(fetched)
        link.linkPolicy = public
        aGroup(link)
        link.sendText(group, "Read this https://example.com/s", null)
        waitFor("the sent message") { repo.message("imsg:S2") != null }
        assertTrue(pictureThere)
        val request = DirectWire.SendRequest.fromJson(asked.single())
        val preview = request.link!!
        assertEquals("https://example.com/s", preview.url)
        assertEquals("https://example.com/s", preview.originalUrl)
        assertEquals("A story", preview.title)
        assertEquals("About it", preview.summary)
        assertEquals("https://example.com/cover.png", preview.imageUrl)
        assertEquals("image/jpeg", preview.imageMime)
        assertEquals(listOf("https://example.com/s", "https://example.com/cover.png"), fetched)
        // The sent message shows the same card, its picture its own from now on.
        val shown = IMessageExtra.parse(repo.message("imsg:S2")!!.extra).link!!
        assertEquals(IMessageExtra.Link("https://example.com/s", "A story", "About it", "example.com", "S2/link"), shown)
        assertTrue(DirectLink.pictureFile(context, "S2/link").length() > 0)
        assertFalse(java.io.File(preview.imagePath!!).exists())
    }

    @Test
    fun withLinkPreviewsOffOrNothingToShowALinkGoesAsItIs() {
        val asked = Collections.synchronizedList(ArrayList<JSONObject>())
        var n = 0
        val link = DirectLink.installForTesting(context) { what, request, _ ->
            if (what == DirectWire.SEND) asked += request
            DirectWire.sent("S${++n + 10}", 1_700_000_005_000L)
        }
        val fetched = Collections.synchronizedList(ArrayList<String>())
        link.linkFetcher = network(fetched)
        link.linkPolicy = public
        aGroup(link)
        DirectPrefs.setLinkPreviews(context, false)
        link.sendText(group, "https://example.com/s", null)
        waitFor("the first") { asked.size == 1 }
        DirectPrefs.setLinkPreviews(context, true)
        link.sendText(group, "https://example.com/missing", null)
        waitFor("the second") { asked.size == 2 }
        assertTrue(asked.all { DirectWire.SendRequest.fromJson(it).link == null })
        assertEquals("nothing fetched with the setting off", listOf("https://example.com/missing"), fetched)
    }

    @Test
    fun anIncomingLinksPictureBecomesItsOwnOnlyOnceTheMessageIsTaken() {
        val link = DirectLink.installForTesting(context) { _, _, _ -> DirectWire.ok() }
        aGroup(link)
        fun linked(guid: String, from: String) = map(
            inst(
                guid, from,
                normal(
                    listOf(text("https://example.com/a")),
                    link = uniffi.rust_bridge.LinkMeta(
                        uniffi.rust_bridge.LpLinkMetadata(
                            null, 1u, null, uniffi.rust_bridge.NsUrl("\$null", "https://example.com/a"), uniffi.rust_bridge.NsUrl("\$null", "https://example.com/a"),
                            "A", "", uniffi.rust_bridge.RichLinkImageAttachmentSubstitute("image/jpeg", 0UL), null, null, null, null, null, null, null, null,
                        ),
                        listOf(png()),
                    ),
                ),
            ),
        )
        val aside = writtenAside("L1/link")
        link.apply(linked("L1", alice))
        assertFalse(aside.exists())
        assertTrue(DirectLink.pictureFile(context, "L1/link").length() > 0)
        assertEquals("L1/link", IMessageExtra.parse(repo.message("imsg:L1")!!.extra).link!!.image)
        // From someone not in the group: dropped, and its picture with it.
        val dropped = writtenAside("L2/link")
        link.apply(linked("L2", dave))
        assertNull(repo.message("imsg:L2"))
        assertFalse(dropped.exists())
        assertFalse(DirectLink.pictureFile(context, "L2/link").exists())
        // Unsent: its picture goes too.
        link.apply(map(inst("U1", alice, PushChatMessage.Unsend(uniffi.rust_bridge.UnsendMessage("L1", 0UL)), ts = 1_700_000_000_500L)))
        assertEquals("system", repo.message("imsg:L1")!!.direction)
        assertFalse(DirectLink.pictureFile(context, "L1/link").exists())
    }

    // --- stickers ------------------------------------------------------------------------------------

    @Test
    fun aStickerTapbackKeepsItsPictureUntilAnotherTapbackTakesItsPlace() {
        val link = DirectLink.installForTesting(context) { _, _, _ -> DirectWire.ok() }
        aGroup(link)
        val key = IMessageExtra.tapbackStickerKey("G0", "+15559876543")
        val aside = writtenAside(key)
        link.apply(wire(DirectArchive.Update.Reaction("imsg:G0", "+15559876543", DirectArchive.STICKER_TAPBACK, true, false, 5, group, "GRP-1", key)))
        assertEquals(listOf(com.lightdecoder.relay.ReactionDto(DirectArchive.STICKER_TAPBACK, listOf("+15559876543"))), repo.message("imsg:G0")!!.reactions)
        assertFalse(aside.exists())
        assertTrue(DirectLink.pictureFile(context, key).length() > 0)
        // A heart in its place: the sticker's picture goes.
        link.apply(wire(DirectArchive.Update.Reaction("imsg:G0", "+15559876543", "❤️", true, false, 6, group, "GRP-1")))
        assertFalse(DirectLink.pictureFile(context, key).exists())
        // Someone not in the group: nothing of theirs is kept.
        val stranger = IMessageExtra.tapbackStickerKey("G0", "+15554443333")
        val theirs = writtenAside(stranger)
        link.apply(wire(DirectArchive.Update.Reaction("imsg:G0", "+15554443333", DirectArchive.STICKER_TAPBACK, true, false, 7, group, "GRP-1", stranger)))
        assertFalse(theirs.exists())
        assertFalse(DirectLink.pictureFile(context, stranger).exists())
        assertTrue(repo.message("imsg:G0")!!.reactions.none { "+15554443333" in it.participantIds })
    }

    @Test
    fun aStickerDraggedOntoAMessageIsKeptWithItFromSomeoneInTheChatOnly() {
        val link = DirectLink.installForTesting(context) { _, _, _ -> DirectWire.ok() }
        aGroup(link)
        val key = IMessageExtra.stickerKey("G0", "PS1")
        val aside = writtenAside(key)
        link.apply(wire(DirectArchive.Update.Sticker("imsg:G0", "PS1", "+15559876543", false, 9, group, "GRP-1")))
        assertEquals(listOf(IMessageExtra.PlacedSticker("PS1", "+15559876543", 9)), IMessageExtra.parse(repo.message("imsg:G0")!!.extra).stickers)
        assertFalse(aside.exists())
        assertTrue(DirectLink.pictureFile(context, key).length() > 0)
        // The same again changes nothing; the user's own (another device) is the user's.
        link.apply(wire(DirectArchive.Update.Sticker("imsg:G0", "PS1", "+15559876543", false, 9, group, "GRP-1")))
        link.apply(wire(DirectArchive.Update.Sticker("imsg:G0", "PS2", "me", true, 10, group, "GRP-1")))
        assertEquals(listOf("PS1", "PS2"), IMessageExtra.parse(repo.message("imsg:G0")!!.extra).stickers.map { it.id })
        // Someone not in the group, or from another chat: refused, its picture gone.
        val strangers = writtenAside(IMessageExtra.stickerKey("G0", "PS3"))
        link.apply(wire(DirectArchive.Update.Sticker("imsg:G0", "PS3", "+15554443333", false, 11, group, "GRP-1")))
        link.apply(wire(DirectArchive.Update.Sticker("imsg:G0", "PS4", "+15559876543", false, 12, "imsg:group:OTHER", "OTHER")))
        assertEquals(listOf("PS1", "PS2"), IMessageExtra.parse(repo.message("imsg:G0")!!.extra).stickers.map { it.id })
        assertFalse(strangers.exists())
        // Apple handing the message over again keeps them.
        link.apply(map(inst("G0", alice, normal(listOf(text("hello all"))))))
        assertEquals(listOf("PS1", "PS2"), IMessageExtra.parse(repo.message("imsg:G0")!!.extra).stickers.map { it.id })
    }

    // --- polls -----------------------------------------------------------------------------------------

    private val pollsBundle = Polls.BUNDLE_ID
    private val session = "0F8FAD5B-D9CB-469F-A165-70867728950E"

    private fun pollApp(poll: Polls.Poll) = Polls.card(poll, session).let { card ->
        ExtensionApp(
            "Polls", null, pollsBundle,
            Balloon(card.url, card.session, BalloonLayout.TemplateLayout("", "", card.caption, "", "", card.subcaption, NsDictionaryClass.NS_DICTIONARY), card.ldText, true, null),
        )
    }

    private fun aPoll(link: DirectLink): Polls.Poll {
        var n = 0
        val poll = Polls.create("Dinner?", listOf("Pizza", "Tacos"), "+15551234567") { "OPT-${++n}" }!!
        link.apply(map(inst("P1", alice, normal(listOf(IndexedMessagePart(MessagePart.Object("Pizza, Tacos"), null, null)), app = pollApp(poll)))))
        return poll
    }

    private fun voteFrom(guid: String, from: String, card: String, choices: List<String>, ts: Long, groupGuid: String = "GRP-1") = map(
        inst(
            guid, from,
            PushChatMessage.React(
                ReactMessage(
                    card, null,
                    ReactMessageType.Extension(
                        ExtensionApp("Polls", null, pollsBundle, Balloon(Polls.votesUrl(choices, "+19999999999"), card, null, null, false, null)),
                        listOf(IndexedMessagePart(MessagePart.Object(""), null, null)), true,
                    ),
                    Polls.VOTE_NOTICE, null,
                ),
            ),
            groupGuid = groupGuid, ts = ts,
        ),
    )

    private fun votes() = IMessageExtra.parse(repo.message("imsg:P1")!!.extra).pollVotes

    @Test
    fun aPollsCardIsStoredAsItsQuestionAndVotesCountOnItWithNoRows() {
        val link = DirectLink.installForTesting(context) { _, _, _ -> DirectWire.ok() }
        aGroup(link)
        aPoll(link)
        assertEquals("Poll: Dinner?", repo.message("imsg:P1")!!.text)
        val before = repo.messages(group).size
        link.apply(voteFrom("V1", bob, "P1", listOf("OPT-1"), 1_700_000_001_000L))
        link.apply(voteFrom("V2", alice, "P1", listOf("OPT-1", "OPT-2"), 1_700_000_002_000L))
        // Bob changes his mind; an older vote of Alice's, come late, does not count.
        link.apply(voteFrom("V3", bob, "P1", listOf("OPT-2"), 1_700_000_003_000L))
        link.apply(voteFrom("V0", alice, "P1", emptyList(), 1_700_000_000_500L))
        assertEquals(before, repo.messages(group).size)
        val tally = Polls.tally(Polls.decode(IMessageExtra.parse(repo.message("imsg:P1")!!.extra).app!!.url)!!, votes())
        assertEquals(listOf(1, 2), tally.map { it.count })
        assertEquals(listOf("+15551234567", "+15559876543"), tally[1].voters)
        // Someone not in the group, or a vote from another chat: refused.
        link.apply(voteFrom("V4", dave, "P1", listOf("OPT-1"), 1_700_000_004_000L))
        link.apply(voteFrom("V5", bob, "P1", listOf("OPT-1"), 1_700_000_005_000L, groupGuid = "OTHER"))
        assertEquals(listOf(1, 2), Polls.tally(Polls.decode(IMessageExtra.parse(repo.message("imsg:P1")!!.extra).app!!.url)!!, votes()).map { it.count })
        // The card handed over again keeps its votes.
        aPoll(link)
        assertEquals(2, votes().size)
    }

    @Test
    fun aVoteForACardNotStoredYetCountsOnceItIs() {
        val link = DirectLink.installForTesting(context) { _, _, _ -> DirectWire.ok() }
        aGroup(link)
        link.apply(voteFrom("V1", bob, "P1", listOf("OPT-2"), 1_700_000_001_000L))
        assertNull(repo.message("imsg:P1"))
        aPoll(link)
        assertEquals(listOf(Polls.Ballot("+15559876543", listOf("OPT-2"), 1_700_000_001_000L)), votes())
    }

    @Test
    fun aChoiceAddedShowsOnTheCardInItsPlaceAndVotesOnTheNewCardCountThere() {
        val link = DirectLink.installForTesting(context) { _, _, _ -> DirectWire.ok() }
        aGroup(link)
        val poll = aPoll(link)
        val added = Polls.addChoice(poll, "Curry", "+15559876543", "OPT-3")!!
        fun change(guid: String, p: Polls.Poll, from: String = bob) = map(
            inst(guid, from, PushChatMessage.React(ReactMessage("P1", null, ReactMessageType.Extension(pollApp(p), listOf(IndexedMessagePart(MessagePart.Object(Polls.ldText(p)), null, null)), false), "", null))),
        )
        val before = repo.messages(group).size
        link.apply(change("PC2", added))
        assertEquals(before, repo.messages(group).size)
        val extra = IMessageExtra.parse(repo.message("imsg:P1")!!.extra)
        assertEquals(listOf("Pizza", "Tacos", "Curry"), Polls.decode(extra.app!!.url)!!.options.map { it.text })
        assertEquals(listOf("PC2"), extra.pollCards)
        assertEquals("imsg:P1", extra.app!!.root)
        // A vote on the new card counts on this one.
        link.apply(voteFrom("V9", alice, "PC2", listOf("OPT-3"), 1_700_000_009_000L))
        assertEquals(listOf("OPT-3"), Polls.choicesOf(votes(), "+15551234567"))
        // An older state (the card as it was) does not undo it; one from someone not in the group is refused.
        link.apply(change("PC1", poll))
        link.apply(change("PC3", Polls.addChoice(added, "Soup", "+1", "OPT-4")!!, from = dave))
        assertEquals(3, Polls.decode(IMessageExtra.parse(repo.message("imsg:P1")!!.extra).app!!.url)!!.options.size)
    }

    @Test
    fun theUsersVoteGoesAsOpenBubblesSendsOneAndIsPutBackIfAppleRefusesIt() {
        val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())
        var refuse = false
        val link = DirectLink.installForTesting(context) { what, request, _ ->
            asked += what to request
            if (refuse) DirectWire.error("Apple said no") else DirectWire.sent("MV${asked.size}", 1_700_000_010_000L)
        }
        aGroup(link)
        aPoll(link)
        activate()
        assertTrue(link.canVote(repo.message("imsg:P1")!!, reachable = true))
        assertFalse("not a poll", link.canVote(repo.message("imsg:G0")!!, reachable = true))
        runBlocking { link.votePoll("imsg:P1", "OPT-2") }
        val (what, request) = asked.last()
        assertEquals(DirectWire.EXTENSION, what)
        val r = DirectWire.ExtensionRequest.fromJson(request)
        assertEquals("P1", r.rootGuid)
        assertTrue(r.isMeta)
        assertTrue(Polls.isPoll(r.app))
        assertEquals("P1", r.app.session)
        assertEquals(listOf("OPT-2"), Polls.decodeVotes(r.app.url))
        assertTrue("the voter, bare", String(java.util.Base64.getDecoder().decode(r.app.url.removePrefix("data:,"))).contains("\"participantHandle\":\"+15550000001\""))
        assertNull(r.imagePath)
        assertEquals(listOf("OPT-2"), Polls.choicesOf(votes(), Polls.ME))
        // A second choice: both go.
        runBlocking { link.votePoll("imsg:P1", "OPT-1") }
        assertEquals(listOf("OPT-2", "OPT-1"), Polls.decodeVotes(DirectWire.ExtensionRequest.fromJson(asked.last().second).app.url))
        // Refused: it is as it was.
        refuse = true
        val failed = runCatching { runBlocking { link.votePoll("imsg:P1", "OPT-2") } }
        assertTrue(failed.isFailure)
        assertEquals(listOf("OPT-2", "OPT-1"), Polls.choicesOf(votes(), Polls.ME))
        // A choice the poll does not have is no vote.
        assertTrue(runCatching { runBlocking { link.votePoll("imsg:P1", "NOPE") } }.isFailure)
    }

    @Test
    fun aChoiceAddedFromHereGoesToTheSessionAndShowsOnTheCard() {
        val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())
        val link = DirectLink.installForTesting(context) { what, request, _ ->
            asked += what to request
            DirectWire.sent("AC1", 1_700_000_010_000L)
        }
        aGroup(link)
        aPoll(link)
        runBlocking { link.addPollChoice("imsg:P1", "Curry") }
        val (what, request) = asked.single()
        assertEquals(DirectWire.EXTENSION, what)
        val r = DirectWire.ExtensionRequest.fromJson(request)
        assertEquals("P1", r.rootGuid)
        assertFalse(r.isMeta)
        assertEquals(session, r.app.session)
        assertTrue(r.app.isLive)
        assertEquals("Pizza, Tacos, Curry", r.app.ldText)
        assertEquals(3, Polls.countOf(r.app.url))
        val extra = IMessageExtra.parse(repo.message("imsg:P1")!!.extra)
        assertEquals(listOf("Pizza", "Tacos", "Curry"), Polls.decode(extra.app!!.url)!!.options.map { it.text })
        assertEquals(listOf("AC1"), extra.pollCards)
        // One already there: refused before anything goes.
        assertTrue(runCatching { runBlocking { link.addPollChoice("imsg:P1", "pizza") } }.isFailure)
        assertEquals(1, asked.size)
    }

    @Test
    fun aNewPollGoesAsAnAppsCardAndReadsAsItsQuestion() {
        val asked = Collections.synchronizedList(ArrayList<JSONObject>())
        val link = DirectLink.installForTesting(context) { what, request, _ ->
            if (what == DirectWire.SEND) asked += request
            DirectWire.sent("NP1", 1_700_000_010_000L)
        }
        aGroup(link)
        link.sendPoll(group, "Where to?", listOf("Beach", "Hills"))
        waitFor("the poll") { repo.message("imsg:NP1") != null }
        val request = DirectWire.SendRequest.fromJson(asked.single())
        val app = request.app!!
        assertTrue(Polls.isPoll(app))
        assertEquals("", request.text)
        val poll = Polls.decode(app.url)!!
        assertEquals("Where to?", poll.title)
        assertEquals(listOf("Beach", "Hills"), poll.options.map { it.text })
        assertEquals("+15550000001", poll.creator)
        val stored = repo.message("imsg:NP1")!!
        assertEquals("Poll: Where to?", stored.text)
        assertEquals("imsg:NP1", IMessageExtra.parse(stored.extra).app!!.root)
        assertTrue(runCatching { link.sendPoll(group, "Q", listOf("Only one")) }.isFailure)
    }

    companion object {
        private const val DB = "direct-content-test.db"
    }
}
