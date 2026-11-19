package com.lightdecoder.imessage.direct

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.ParticipantRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.rust_bridge.Balloon
import uniffi.rust_bridge.BalloonLayout
import uniffi.rust_bridge.ChangeParticipantMessage
import uniffi.rust_bridge.ChatRenameMessage
import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.EditMessage
import uniffi.rust_bridge.ErrorMessage
import uniffi.rust_bridge.ExtensionApp
import uniffi.rust_bridge.IconChangeMessage
import uniffi.rust_bridge.IndexedMessagePart
import uniffi.rust_bridge.LinkMeta
import uniffi.rust_bridge.LpLinkMetadata
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MessagePart
import uniffi.rust_bridge.MessageType
import uniffi.rust_bridge.MmcsFile
import uniffi.rust_bridge.NormalMessage
import uniffi.rust_bridge.NsDictionaryClass
import uniffi.rust_bridge.NsUrl
import uniffi.rust_bridge.PushChatMessage
import uniffi.rust_bridge.ReactMessage
import uniffi.rust_bridge.ReactMessageType
import uniffi.rust_bridge.Reaction
import uniffi.rust_bridge.TextFlags
import uniffi.rust_bridge.TextFormat
import uniffi.rust_bridge.UnsendMessage

/** iMessages in the Messages tab's archive: ids, and what each event from Apple changes. */
@RunWith(AndroidJUnit4::class)
class DirectArchiveTest {

    private val me = "tel:+15550000001"
    private val bob = "tel:+15550000002"
    private val carol = "mailto:carol@example.com"
    private val dave = "tel:+15550000004"
    private val names = mapOf(bob to "Bob", carol to "Carol", dave to "Dave")
    private fun nameOf(h: String) = names[h] ?: Handles.display(h)

    private fun text(s: String, idx: Int? = null) =
        IndexedMessagePart(MessagePart.Text(s, TextFormat.Flags(TextFlags(false, false, false, false))), idx?.toULong(), null)

    private fun attachment(name: String, idx: Int? = null) = IndexedMessagePart(
        MessagePart.Attachment(uniffi.rust_bridge.MessageAttachment(uniffi.rust_bridge.AttachmentType.Inline(byteArrayOf()), 0UL, "public.jpeg", "image/jpeg", name, false)),
        idx?.toULong(), null,
    )

    private fun normal(parts: List<IndexedMessagePart>, reply: String? = null, effect: String? = null, voice: Boolean = false, app: ExtensionApp? = null, link: LinkMeta? = null) =
        NormalMessage(parts, effect, reply, null, MessageType.IMessage, null, app, link, voice, null, null)

    private fun message(id: String, sender: String?, participants: List<String>, body: String, groupGuid: String? = null, name: String? = null, reply: String? = null) =
        MessageInst(
            id, sender, ConversationData(participants, name, groupGuid, null),
            PushChatMessage.Message(normal(listOf(text(body)), reply)),
            1_700_000_000_000UL, null, false, false, null,
        )

    private fun inst(id: String, sender: String?, m: PushChatMessage, participants: List<String> = listOf(me, bob), groupGuid: String? = null, name: String? = null, unverified: Boolean = false) =
        MessageInst(id, sender, ConversationData(participants, name, groupGuid, null), m, 1_700_000_005_000UL, null, false, unverified, null)

    private fun map(i: MessageInst) = DirectArchive.map(i, setOf(me), ::nameOf, TestTexts)

    @Test
    fun idsNeverLookLikeGoogleMessagesIds() {
        assertTrue(DirectArchive.isImessage("imsg:tel:+1555"))
        assertFalse(DirectArchive.isImessage("12345"))
        assertFalse(DirectArchive.isImessage("tmp:abc"))
        assertEquals("imsg:ABC-123", DirectArchive.messageId("ABC-123"))
        assertEquals("ABC-123", DirectArchive.guidOf("imsg:ABC-123"))
        assertNull(DirectArchive.guidOf("98765"))
        assertEquals("p2", DirectArchive.partId(2))
        assertEquals("ABC/p2", DirectArchive.mediaId("ABC", 2))
    }

    @Test
    fun aOneToOneConversationIsKeyedByTheOtherPerson() {
        assertEquals("imsg:$bob", DirectArchive.conversationId(listOf(me, bob), setOf(me), null))
        assertEquals("imsg:$bob", DirectArchive.conversationId(listOf(bob, me), setOf(me), "guid-ignored"))
        assertFalse(DirectArchive.isGroup("imsg:$bob"))
    }

    @Test
    fun aGroupIsKeyedByItsGuid() {
        val id = DirectArchive.conversationId(listOf(me, bob, carol), setOf(me), "G-1")
        assertEquals("imsg:group:G-1", id)
        assertTrue(DirectArchive.isGroup(id))
        assertEquals("G-1", DirectArchive.groupGuid(id))
        val noGuid = DirectArchive.conversationId(listOf(me, carol, bob), setOf(me), null)
        assertTrue(DirectArchive.isGroup(noGuid))
        assertNull(DirectArchive.groupGuid(noGuid))
    }

    @Test
    fun anIncomingMessageBecomesAConversationAndAMessage() {
        val u = map(message("M1", bob, listOf(me, bob), "hello", reply = "M0"))
        u as DirectArchive.Update.Upsert
        assertEquals("imsg:$bob", u.conversation.id)
        assertEquals("Bob", u.conversation.name)
        assertEquals(me, u.conversation.defaultOutgoingId)
        assertEquals(setOf(me, bob), u.conversation.participants.map { it.id }.toSet())
        assertTrue(u.conversation.participants.first { it.id == me }.isMe)
        with(u.message) {
            assertEquals("imsg:M1", id)
            assertEquals("imsg:$bob", conversationId)
            assertFalse(fromMe)
            assertEquals("in", direction)
            assertEquals("hello", text)
            assertEquals("Bob", senderName)
            assertEquals("imsg:M0", replyToId)
            assertEquals(1_700_000_000_000L, timestampMs)
            assertEquals(DirectArchive.TRANSPORT, transport)
            assertEquals(mapOf(0 to "hello"), IMessageExtra.parse(extra).parts)
        }
    }

    @Test
    fun aCopyOfWhatTheUserSentFromAnotherDeviceIsOutgoing() {
        val u = map(message("M2", me, listOf(me, bob), "sent from my Mac"))
        u as DirectArchive.Update.Upsert
        assertEquals("imsg:$bob", u.conversation.id)
        assertTrue(u.message.fromMe)
        assertEquals("out", u.message.direction)
        assertEquals(DirectArchive.STATUS_SENT, u.message.status)
    }

    @Test
    fun aGroupTakesTheNameItsMembersGaveIt() {
        val named = map(message("M3", bob, listOf(me, bob, carol), "hi all", "G-2", "Trip"))
        assertEquals("Trip", (named as DirectArchive.Update.Upsert).conversation.name)
        assertTrue(named.conversation.isGroup)
        // None: the main process names it after its members, with their contact names.
        val unnamed = map(message("M4", bob, listOf(me, bob, carol), "hi", "G-3"))
        assertEquals("", (unnamed as DirectArchive.Update.Upsert).conversation.name)
        // A name with a comma is still a name.
        val commas = map(message("M5", bob, listOf(me, bob, carol), "hi", "G-4", "Mom, Dad & me"))
        assertEquals("Mom, Dad & me", (commas as DirectArchive.Update.Upsert).conversation.name)
    }

    @Test
    fun partsKeepTheirIndexesAndRunsOfOnePartStayOnOneLine() {
        // "Hello " plain and "world" bold are one part; the picture is part 1; the caption after it part 2.
        val parts = listOf(
            text("Hello ", 0),
            IndexedMessagePart(MessagePart.Text("world", TextFormat.Flags(TextFlags(true, false, false, false))), 0UL, null),
            attachment("IMG_1.jpg", 1),
            text("see?", 2),
        )
        val u = map(MessageInst("M5", bob, ConversationData(listOf(me, bob), null, null, null), PushChatMessage.Message(normal(parts)), 1UL, null, false, false, null))
        u as DirectArchive.Update.Upsert
        assertEquals("Hello world\nsee?", u.message.text)
        assertEquals(mapOf(0 to "Hello world", 2 to "see?"), IMessageExtra.parse(u.message.extra).parts)
        // The picture is a media part of its own, by Apple's part index.
        assertEquals(listOf("0", "p1"), u.message.parts.map { it.partId })
    }

    @Test
    fun partsWithoutIndexesAreNumberedAsRustpushNumbersThem() {
        assertEquals(listOf(0, 0, 1, 2), DirectArchive.partIndexes(listOf(text("a"), text("b"), attachment("x"), text("c"))))
        assertEquals(listOf(0, 1), DirectArchive.partIndexes(listOf(attachment("x"), attachment("y"))))
        assertEquals(listOf(4, 7), DirectArchive.partIndexes(listOf(text("a", 4), attachment("x", 7))))
    }

    @Test
    fun attachmentsAreNotInTheText() {
        val parts = listOf(
            MessagePart.Text("look", TextFormat.Flags(TextFlags(false, false, false, false))),
            MessagePart.Attachment(uniffi.rust_bridge.MessageAttachment(uniffi.rust_bridge.AttachmentType.Inline(byteArrayOf()), 1UL, "public.jpeg", "image/jpeg", "IMG_1.jpg", false)),
        )
        assertEquals("look", DirectArchive.textOf(parts))
    }

    // --- attachments ----------------------------------------------------------------------------

    private fun mmcs(size: Long, vararg more: Long) = uniffi.rust_bridge.AttachmentType.Mmcs(
        (listOf(size) + more.toList()).map { MmcsFile(byteArrayOf(1), "obj", "https://example.com/$it", byteArrayOf(2), it.toULong()) },
    )

    private fun file(
        idx: Int?,
        name: String,
        mime: String,
        uti: String,
        type: uniffi.rust_bridge.AttachmentType = mmcs(1000),
        iris: Boolean = false,
        ext: uniffi.rust_bridge.PartExtension? = null,
    ) = IndexedMessagePart(MessagePart.Attachment(uniffi.rust_bridge.MessageAttachment(type, (idx ?: 0).toULong(), uti, mime, name, iris)), idx?.toULong(), ext)

    private fun withParts(id: String, parts: List<IndexedMessagePart>, voice: Boolean = false, describe: (uniffi.rust_bridge.MessageAttachment) -> String = { "desc:" + it.name }) =
        DirectArchive.map(
            MessageInst(id, bob, ConversationData(listOf(me, bob), null, null, null), PushChatMessage.Message(normal(parts, voice = voice)), 1UL, null, false, false, null),
            setOf(me), ::nameOf, TestTexts, describe,
        ) as DirectArchive.Update.Upsert

    @Test
    fun anAttachmentIsAMediaPartWithItsRealTypeNameSizeAndDescription() {
        val u = withParts("G1", listOf(text("look", 0), file(1, "IMG_7.HEIC", "", "public.heic", mmcs(900, 120_000, 4_000))))
        assertEquals("look", u.message.text)
        val text = u.message.parts.first()
        assertEquals(listOf("0", "text", "look"), listOf(text.partId, text.kind, text.text))
        val media = u.message.parts.single { it.isMedia }
        assertEquals("p1", media.partId)
        assertEquals("G1/p1", media.mediaId)
        assertEquals("image/heic", media.mime)
        assertEquals("IMG_7.HEIC", media.fileName)
        // The largest of the qualities Apple stored: the one that is downloaded.
        assertEquals(120_000L, media.size)
        assertEquals("desc:IMG_7.HEIC", String(media.key, Charsets.UTF_8))
        // Across the processes and back.
        val wired = DirectWire.decode(org.json.JSONObject(DirectWire.encode(u).toString())) as DirectArchive.Update.Upsert
        assertEquals("desc:IMG_7.HEIC", String(wired.message.parts.single { it.isMedia }.key, Charsets.UTF_8))
        // No line saying an attachment came.
        assertFalse(u.message.text.contains("📎"))
    }

    @Test
    fun theTypeComesFromApplesTypeThenItsUtiThenItsNameElseAnyData() {
        fun mimeOf(mime: String, uti: String, name: String) =
            withParts("T", listOf(file(0, name, mime, uti))).message.parts.single().mime
        assertEquals("image/png", mimeOf("image/png", "public.jpeg", "a.jpg"))
        // A wildcard or "any data" says nothing.
        assertEquals("image/jpeg", mimeOf("image/*", "public.jpeg", "a"))
        assertEquals("audio/x-caf", mimeOf("application/octet-stream", "com.apple.coreaudio-format", "Audio Message.caf"))
        assertEquals("video/quicktime", mimeOf("", "com.apple.quicktime-movie", "IMG_2.MOV"))
        assertEquals("video/mp4", mimeOf("", "dyn.unknown", "clip.mp4"))
        assertEquals("application/octet-stream", mimeOf("", "dyn.unknown", "thing"))
    }

    @Test
    fun aMessageOfOnlyAPictureHasNoTextPartAndItsPictureIsPartZero() {
        val u = withParts("P0", listOf(file(0, "IMG_1.jpg", "image/jpeg", "public.jpeg")))
        assertEquals("", u.message.text)
        assertEquals(listOf("p0"), u.message.parts.map { it.partId })
        assertEquals("P0/p0", u.message.parts.single().mediaId)
    }

    @Test
    fun aLivePhotosMovingPartIsLeftOutTheStillShows() {
        val u = withParts(
            "L1",
            listOf(
                file(0, "IMG_5.HEIC", "image/heic", "public.heic"),
                file(1, "IMG_5.MOV", "video/quicktime", "com.apple.quicktime-movie", iris = true),
                file(2, "IMG_6.MOV", "video/quicktime", "com.apple.quicktime-movie"),
            ),
        )
        assertEquals(listOf("p0", "p2"), u.message.parts.map { it.partId })
        // A video marked so that does not follow a picture is a video.
        val alone = withParts("L2", listOf(file(0, "IMG_8.MOV", "video/quicktime", "com.apple.quicktime-movie", iris = true)))
        assertEquals(listOf("p0"), alone.message.parts.map { it.partId })
        // The still marked too: its moving part is still left out, and only that one.
        val both = withParts(
            "L3",
            listOf(
                file(0, "IMG_9.HEIC", "image/heic", "public.heic", iris = true),
                file(1, "IMG_9.MOV", "video/quicktime", "com.apple.quicktime-movie", iris = true),
                file(2, "IMG_10.MOV", "video/quicktime", "com.apple.quicktime-movie", iris = true),
            ),
        )
        assertEquals(listOf("p0", "p2"), both.message.parts.map { it.partId })
        // Text between them: the video does not follow the picture.
        val apart = withParts(
            "L4",
            listOf(
                file(0, "IMG_11.HEIC", "image/heic", "public.heic"),
                text("and", 1),
                file(2, "IMG_11.MOV", "video/quicktime", "com.apple.quicktime-movie", iris = true),
            ),
        )
        assertEquals(listOf("p0", "p2"), apart.message.parts.filter { it.isMedia }.map { it.partId })
    }

    @Test
    fun aStickerIsAPictureMarkedAsASticker() {
        val sticker = uniffi.rust_bridge.PartExtension.Sticker(0.5, 0.0, 0UL, 1.0, null, 0UL, 0.5, 0.5, 1UL, "h", 0UL, 0L, "s1")
        val u = withParts("S1", listOf(file(0, "sticker", "", "", ext = sticker)))
        assertTrue(IMessageExtra.parse(u.message.extra).sticker)
        assertTrue(u.message.parts.single().mime.startsWith("image/"))
        // A picture is no sticker.
        assertFalse(IMessageExtra.parse(withParts("S2", listOf(file(0, "a.png", "image/png", "public.png"))).message.extra).sticker)
    }

    @Test
    fun aVoiceMessageIsMarkedAndKeepsItsCaf() {
        val u = withParts("V1", listOf(file(0, "Audio Message.caf", "", "com.apple.coreaudio-format")), voice = true)
        assertTrue(IMessageExtra.parse(u.message.extra).voice)
        assertEquals("audio/x-caf", u.message.parts.single().mime)
    }

    @Test
    fun aSmallFileThatCameInsideTheMessageKeepsItsDescriptionOnlyWhileSmall() {
        val small = withParts("I1", listOf(file(0, "a.png", "image/png", "public.png", uniffi.rust_bridge.AttachmentType.Inline(ByteArray(10)))))
        assertEquals(10L, small.message.parts.single().size)
        assertEquals("desc:a.png", String(small.message.parts.single().key, Charsets.UTF_8))
        // Described at more than a message keeps: the file the ":imessage" process writes is the only way to it.
        val big = withParts(
            "I2", listOf(file(0, "b.png", "image/png", "public.png", uniffi.rust_bridge.AttachmentType.Inline(ByteArray(10)))),
            describe = { "x".repeat(DirectArchive.MAX_INLINE_KEYS_BYTES + 1) },
        )
        assertEquals(0, big.message.parts.single().key.size)
        // A file larger than that is not even described.
        var described = 0
        val large = withParts(
            "I4", listOf(file(0, "d.png", "image/png", "public.png", uniffi.rust_bridge.AttachmentType.Inline(ByteArray(DirectArchive.MAX_INLINE_KEYS_BYTES + 1)))),
            describe = { described++; "small" },
        )
        assertEquals(0, large.message.parts.single().key.size)
        assertEquals(0, described)
        // Several together: each kept while all of them stay within it.
        val three = withParts(
            "I5",
            (0 until 3).map { file(it, "f$it.png", "image/png", "public.png", uniffi.rust_bridge.AttachmentType.Inline(ByteArray(10))) },
            describe = { "x".repeat(DirectArchive.MAX_INLINE_KEYS_BYTES / 2 - 1) },
        )
        assertEquals(listOf(true, true, false), three.message.parts.map { it.key.isNotEmpty() })
        // A download's description is always kept: it is all there is to fetch it by.
        val download = withParts("I3", listOf(file(0, "c.mov", "video/quicktime", "")), describe = { "x".repeat(DirectArchive.MAX_INLINE_KEYS_BYTES + 1) })
        assertEquals(DirectArchive.MAX_INLINE_KEYS_BYTES + 1, download.message.parts.single().key.size)
        // Which the ":imessage" process writes.
        val inline = DirectArchive.attachments(listOf(file(3, "d.gif", "image/gif", "", uniffi.rust_bridge.AttachmentType.Inline(byteArrayOf(7)))))
        assertEquals(listOf(3), inline.map { it.idx })
        assertTrue(inline.single().inlineBytes!!.contentEquals(byteArrayOf(7)))
        assertNull(DirectArchive.attachments(listOf(file(0, "e.gif", "image/gif", ""))).single().inlineBytes)
    }

    @Test
    fun effectVoiceLinkAndAppGoInTheExtra() {
        val link = LinkMeta(
            LpLinkMetadata(null, 1u, null, NsUrl("\$null", "https://www.example.com/a"), NsUrl("\$null", "https://www.example.com/a"), "A page", "About it", null, null, null, null, null, null, null, null, null),
            emptyList(),
        )
        val app = ExtensionApp(
            "GamePigeon", 1124197642UL, "com.apple.messages.MSMessageExtensionBalloonPlugin:EWFNLB79LQ:com.gamerdelights.gamepigeon.ext",
            Balloon("data:game", "S-1", BalloonLayout.TemplateLayout("sub", "title", "Let's play 8 Ball!", "2nd", "3rd", "subcap", NsDictionaryClass.NS_DICTIONARY), "8 Ball", true, null),
        )
        val m = MessageInst(
            "M6", bob, ConversationData(listOf(me, bob), null, null, null),
            PushChatMessage.Message(normal(listOf(text("https://www.example.com/a")), effect = "com.apple.MobileSMS.expressivesend.impact", voice = true, app = app, link = link)),
            1UL, null, false, false, null,
        )
        val extra = IMessageExtra.parse((map(m) as DirectArchive.Update.Upsert).message.extra)
        assertEquals("com.apple.MobileSMS.expressivesend.impact", extra.effect)
        assertTrue(extra.voice)
        assertEquals(IMessageExtra.Link("https://www.example.com/a", "A page", "About it", "example.com"), extra.link)
        // A preview whose own address is no web page's: the web address it came with, if it has one.
        val odd = LinkMeta(
            LpLinkMetadata(null, 1u, null, NsUrl("\$null", "tel:+15551234567"), NsUrl("\$null", "https://www.example.com/b"), "Call", "", null, null, null, null, null, null, null, null, null),
            emptyList(),
        )
        val oddMessage = m.copy(message = PushChatMessage.Message(normal(listOf(text("x")), link = odd)))
        assertEquals("https://www.example.com/b", IMessageExtra.parse((map(oddMessage) as DirectArchive.Update.Upsert).message.extra).link!!.url)
        val a = extra.app!!
        assertEquals("GamePigeon", a.name)
        assertEquals(1124197642L, a.appId)
        assertEquals("S-1", a.session)
        assertEquals("8 Ball", a.ldText)
        assertEquals("Let's play 8 Ball!", a.caption)
        assertEquals("title", a.imageTitle)
        assertEquals("subcap", a.subcaption)
        assertEquals("imsg:M6", a.root)
        assertTrue(a.isLive)
    }

    private val gamePigeon = "com.apple.messages.MSMessageExtensionBalloonPlugin:EWFNLB79LQ:com.gamerdelights.gamepigeon.ext"

    private fun game(caption: String = "Your move", ldText: String? = "8 Ball", name: String = "GamePigeon", url: String = "data:state") = ExtensionApp(
        name, 1124197642UL, gamePigeon,
        Balloon(url, "S-1", BalloonLayout.TemplateLayout("", "8 Ball", caption, "", "", "", NsDictionaryClass.NS_DICTIONARY), ldText, false, null),
    )

    private fun objectPart(text: String, idx: Int? = null) = IndexedMessagePart(MessagePart.Object(text), idx?.toULong(), null)

    @Test
    fun anAppsCardReadsAsItsCaptionElseItsSummaryElseTheAppsName() {
        fun textOf(app: ExtensionApp) = (map(
            MessageInst(
                "A1", bob, ConversationData(listOf(me, bob), null, null, null),
                PushChatMessage.Message(normal(listOf(objectPart("8 Ball", 0), attachment("card.jpeg", 1)), app = app)), 1UL, null, false, false, null,
            ),
        ) as DirectArchive.Update.Upsert).message
        val card = textOf(game())
        assertEquals("Your move", card.text)
        // Its picture is a media part of its own, numbered as Apple numbered it.
        assertEquals(listOf("0", "p1"), card.parts.map { it.partId })
        assertEquals("A1/p1", card.parts[1].mediaId)
        assertEquals("8 Ball", textOf(game(caption = "")).text)
        assertEquals("GamePigeon", textOf(game(caption = "", ldText = null)).text)
        // Apple did not name the app: nothing to stand for it, and no text part.
        val bare = textOf(game(caption = "", ldText = null, name = IMessageExtra.UNNAMED_APP))
        assertEquals("", bare.text)
        assertEquals(listOf("p1"), bare.parts.map { it.partId })
        // Then its own words, if it came with any.
        val worded = (map(
            MessageInst(
                "A2", bob, ConversationData(listOf(me, bob), null, null, null),
                PushChatMessage.Message(normal(listOf(text("look", 0), attachment("card.jpeg", 1)), app = game(caption = "", ldText = null, name = IMessageExtra.UNNAMED_APP))),
                1UL, null, false, false, null,
            ),
        ) as DirectArchive.Update.Upsert).message
        assertEquals("look", worded.text)
    }

    @Test
    fun aGameMoveIsANewCardOfTheSessionAndAVoteIsARow() {
        fun ext(app: ExtensionApp, meta: Boolean, body: List<IndexedMessagePart> = listOf(objectPart("8 Ball", 0), attachment("move.jpeg", 1))) =
            PushChatMessage.React(ReactMessage("ROOT", null, ReactMessageType.Extension(app, body, meta), "", null))
        val move = map(inst("MV1", bob, ext(game(caption = "Bob's turn done"), meta = false))) as DirectArchive.Update.Upsert
        assertEquals("imsg:MV1", move.message.id)
        assertEquals("imsg:$bob", move.message.conversationId)
        assertEquals("in", move.message.direction)
        assertEquals("Bob's turn done", move.message.text)
        assertEquals(listOf("0", "p1"), move.message.parts.map { it.partId })
        assertEquals("MV1/p1", move.message.parts[1].mediaId)
        val app = IMessageExtra.parse(move.message.extra).app!!
        assertEquals("imsg:ROOT", app.root)
        assertEquals("S-1", app.session)
        assertEquals(gamePigeon, app.bundleId)
        // The user's own move, from their iPhone.
        val mine = map(inst("MV2", me, ext(game(), meta = false))) as DirectArchive.Update.Upsert
        assertTrue(mine.message.fromMe)
        assertEquals("out", mine.message.direction)
        // A vote in a poll: a row saying who, in a group or one to one.
        val polls = ExtensionApp("Polls", null, "com.apple.messages.MSMessageExtensionBalloonPlugin:0000000000:com.apple.messages.Polls", Balloon("data:,x", null, null, "Lunch?", true, null))
        val vote = map(inst("V1", bob, ext(polls, meta = true, body = listOf(objectPart(""))), listOf(me, bob, carol), "G-9")) as DirectArchive.Update.Upsert
        assertEquals("system", vote.message.direction)
        assertEquals("imsg:V1", vote.message.id)
        assertEquals("imsg:group:G-9", vote.message.conversationId)
        assertEquals("Bob updated Polls.", vote.message.text)
        assertEquals("You updated Polls.", (map(inst("V2", me, ext(polls, meta = true))) as DirectArchive.Update.Upsert).message.text)
        val unnamed = ExtensionApp(IMessageExtra.UNNAMED_APP, null, "x", Balloon("data:,x", null, null, null, false, null))
        val oneToOne = map(inst("V3", bob, ext(unnamed, meta = true))) as DirectArchive.Update.Upsert
        assertEquals("imsg:$bob", oneToOne.message.conversationId)
        assertEquals("Bob updated an iMessage app.", oneToOne.message.text)
        // Not a card (an app's note on a message): nothing to show.
        val noCard = ExtensionApp("Something", null, "x", null)
        assertEquals(DirectArchive.Update.Ignore, map(inst("V4", bob, ext(noCard, meta = false))))
    }

    @Test
    fun theFilesOfAMessageOrOfAMoveAreTheOnesKept() {
        val parts = listOf(text("hi"), attachment("a.jpeg"))
        assertEquals(parts, DirectArchive.storedParts(PushChatMessage.Message(normal(parts))))
        val body = listOf(objectPart("x", 0), attachment("move.jpeg", 1))
        assertEquals(body, DirectArchive.storedParts(PushChatMessage.React(ReactMessage("R", null, ReactMessageType.Extension(game(), body, false), "", null))))
        assertEquals(emptyList<IndexedMessagePart>(), DirectArchive.storedParts(PushChatMessage.React(ReactMessage("R", null, ReactMessageType.Extension(game(), body, true), "", null))))
        assertEquals(emptyList<IndexedMessagePart>(), DirectArchive.storedParts(PushChatMessage.Typing(true, null)))
    }

    @Test
    fun tapbacksBecomeOnePersonsReaction() {
        fun react(r: Reaction, enable: Boolean = true, sender: String = bob) =
            map(inst("R1", sender, PushChatMessage.React(ReactMessage("M1", 0UL, ReactMessageType.React(r, enable), "", null))))
        val heart = react(Reaction.Heart) as DirectArchive.Update.Reaction
        assertEquals(DirectArchive.Update.Reaction("imsg:M1", "+15550000002", "❤️", true, false, 1_700_000_005_000L), heart)
        assertEquals("👍", (react(Reaction.Like) as DirectArchive.Update.Reaction).emoji)
        assertEquals("👎", (react(Reaction.Dislike) as DirectArchive.Update.Reaction).emoji)
        assertEquals("😂", (react(Reaction.Laugh) as DirectArchive.Update.Reaction).emoji)
        assertEquals("‼️", (react(Reaction.Emphasize) as DirectArchive.Update.Reaction).emoji)
        assertEquals("❓", (react(Reaction.Question) as DirectArchive.Update.Reaction).emoji)
        assertEquals("🦊", (react(Reaction.Emoji("🦊")) as DirectArchive.Update.Reaction).emoji)
        assertEquals("🖼️", (react(Reaction.Sticker(null, emptyList())) as DirectArchive.Update.Reaction).emoji)
        assertFalse((react(Reaction.Heart, enable = false) as DirectArchive.Update.Reaction).enable)
        // The user's own, from another device.
        val mine = react(Reaction.Laugh, sender = me) as DirectArchive.Update.Reaction
        assertTrue(mine.fromMe)
        assertEquals("+15550000001", mine.participantId)
    }

    @Test
    fun emojiAreSentAsTheClassicTapbacksWhereThereIsOne() {
        assertEquals(Reaction.Heart, DirectArchive.reactionOf("❤️"))
        assertEquals(Reaction.Heart, DirectArchive.reactionOf("❤"))
        assertEquals(Reaction.Like, DirectArchive.reactionOf("👍"))
        assertEquals(Reaction.Dislike, DirectArchive.reactionOf("👎"))
        assertEquals(Reaction.Laugh, DirectArchive.reactionOf("😂"))
        assertEquals(Reaction.Emphasize, DirectArchive.reactionOf("‼️"))
        assertEquals(Reaction.Question, DirectArchive.reactionOf("❓"))
        assertEquals(Reaction.Emoji("🦊"), DirectArchive.reactionOf("🦊"))
        assertEquals(Reaction.Emoji("👍🏽"), DirectArchive.reactionOf("👍🏽"))
    }

    @Test
    fun unsendEditAndTypingChangeTheStoredMessage() {
        assertEquals(
            DirectArchive.Update.Unsent("imsg:M1", 2, false, "Bob"),
            map(inst("X", bob, PushChatMessage.Unsend(UnsendMessage("M1", 2UL)))),
        )
        assertEquals(DirectArchive.Update.Unsent("imsg:M1", 0, true, ""), map(inst("X", me, PushChatMessage.Unsend(UnsendMessage("M1", 0UL)))))
        assertEquals(
            DirectArchive.Update.Edit("imsg:M1", "fixed it", 1, 1_700_000_005_000L),
            map(inst("X", bob, PushChatMessage.Edit(EditMessage("M1", 1UL, listOf(text("fixed "), text("it")))))),
        )
        assertEquals(DirectArchive.Update.Typing("imsg:$bob", "+15550000002", true), map(inst("X", bob, PushChatMessage.Typing(true, null))))
        assertEquals(DirectArchive.Update.Typing("imsg:$bob", "+15550000002", false), map(inst("X", bob, PushChatMessage.Typing(false, null))))
        // In a group down to one other person: its guid goes with it, so the group is found.
        assertEquals(
            DirectArchive.Update.Typing("imsg:$bob", "+15550000002", true, "G-S"),
            map(inst("X", bob, PushChatMessage.Typing(true, null), groupGuid = "G-S")),
        )
        // The user's own devices never show as typing.
        assertEquals(DirectArchive.Update.Ignore, map(inst("X", me, PushChatMessage.Typing(true, null))))
        assertEquals(DirectArchive.Update.Ignore, map(inst("X", bob, PushChatMessage.MarkUnread)))
    }

    @Test
    fun receiptsFromThemAndFromTheUsersOtherDevices() {
        assertEquals(DirectArchive.Update.Receipt(listOf("imsg:M5"), DirectArchive.STATUS_DELIVERED), map(inst("M5", bob, PushChatMessage.Delivered)))
        assertEquals(DirectArchive.Update.Receipt(listOf("imsg:M5"), DirectArchive.STATUS_READ), map(inst("M5", bob, PushChatMessage.Read)))
        // Read on the user's iPhone: the chat is read; their other devices' Delivered says nothing.
        assertEquals(DirectArchive.Update.ReadElsewhere("imsg:M5"), map(inst("M5", me, PushChatMessage.Read)))
        assertEquals(DirectArchive.Update.Ignore, map(inst("M5", me, PushChatMessage.Delivered)))
    }

    @Test
    fun whatAppleCouldNotVerifyIsDropped() {
        for (m in listOf(
            PushChatMessage.Delivered, PushChatMessage.Read, PushChatMessage.Typing(true, null),
            PushChatMessage.Edit(EditMessage("M1", 0UL, listOf(text("x")))), PushChatMessage.Unsend(UnsendMessage("M1", 0UL)),
            PushChatMessage.RenameMessage(ChatRenameMessage("Evil")),
        )) {
            assertEquals(m.toString(), DirectArchive.Update.Ignore, map(inst("X", bob, m, participants = listOf(me, bob, carol), groupGuid = "G", unverified = true)))
        }
    }

    @Test
    fun anErrorForTheUsersMessageFailsIt() {
        assertEquals(
            DirectArchive.Update.Failed("imsg:M7", "Not delivered"),
            map(inst("X", me, PushChatMessage.Error(ErrorMessage("M7", 4UL, "Not delivered")))),
        )
        assertEquals(DirectArchive.Update.Ignore, map(inst("X", bob, PushChatMessage.Error(ErrorMessage("M7", 4UL, "x")))))
    }

    @Test
    fun aRenamedGroupGetsItsNewNameAndARow() {
        val u = map(inst("E1", bob, PushChatMessage.RenameMessage(ChatRenameMessage("Ski trip")), listOf(me, bob, carol), "G-5", "Trip"))
        u as DirectArchive.Update.Upsert
        assertEquals("imsg:group:G-5", u.conversation.id)
        assertEquals("Ski trip", u.conversation.name)
        assertEquals("imsg:E1", u.message.id)
        assertEquals("system", u.message.direction)
        assertEquals("Bob named the conversation “Ski trip”.", u.message.text)
        val mine = map(inst("E2", me, PushChatMessage.RenameMessage(ChatRenameMessage("Ski")), listOf(me, bob, carol), "G-5"))
        assertEquals("You named the conversation “Ski”.", (mine as DirectArchive.Update.Upsert).message.text)
        // Not a group: nothing to rename.
        assertEquals(DirectArchive.Update.Ignore, map(inst("E3", bob, PushChatMessage.RenameMessage(ChatRenameMessage("x")))))
    }

    @Test
    fun membersComingAndGoingAreRowsAndTheGroupFollows() {
        fun change(sender: String, after: List<String>) =
            map(inst("E4", sender, PushChatMessage.ChangeParticipants(ChangeParticipantMessage(after, 2UL)), listOf(me, bob, carol), "G-6"))
                as DirectArchive.Update.Upsert
        val added = change(bob, listOf(me, bob, carol, dave))
        assertEquals("Bob added Dave.", added.message.text)
        assertEquals(setOf(me, bob, carol, dave), added.conversation.participants.map { it.id }.toSet())
        assertEquals("imsg:group:G-6", added.conversation.id)
        assertEquals("Bob removed Carol.", change(bob, listOf(me, bob)).message.text)
        // Down to two people, it is still the group.
        assertEquals("imsg:group:G-6", change(bob, listOf(me, bob)).conversation.id)
        assertEquals("Carol left the conversation.", change(carol, listOf(me, bob)).message.text)
        assertEquals("Bob removed you.", change(bob, listOf(bob, carol)).message.text)
        assertEquals("You added Dave.", change(me, listOf(me, bob, carol, dave)).message.text)
    }

    @Test
    fun aGroupPhotoChangeIsARow() {
        val file = MmcsFile(byteArrayOf(), "o", "u", byteArrayOf(), 1UL)
        val changed = map(inst("E5", bob, PushChatMessage.IconChange(IconChangeMessage(file, 3UL)), listOf(me, bob, carol), "G-7"))
        assertEquals("Bob changed the group photo.", (changed as DirectArchive.Update.Upsert).message.text)
        val removed = map(inst("E6", carol, PushChatMessage.IconChange(IconChangeMessage(null, 4UL)), listOf(me, bob, carol), "G-7"))
        assertEquals("Carol removed the group photo.", (removed as DirectArchive.Update.Upsert).message.text)
    }

    @Test
    fun receiptsNeverMoveAMessageBackwards() {
        assertTrue(DirectArchive.statusRank(DirectArchive.STATUS_READ) > DirectArchive.statusRank(DirectArchive.STATUS_DELIVERED))
        assertTrue(DirectArchive.statusRank(DirectArchive.STATUS_DELIVERED) > DirectArchive.statusRank(DirectArchive.STATUS_SENT))
        assertTrue(DirectArchive.statusRank(DirectArchive.STATUS_FAILED) < DirectArchive.statusRank(DirectArchive.STATUS_SENT))
    }

    @Test
    fun sendingToAStoredConversationUsesItsMembersAndGroupGuid() {
        fun p(id: String, isMe: Boolean = false) = ParticipantRow("c", id, "", "", "", "", isMe, "", "", true)
        val one = DirectArchive.conversationData("imsg:$bob", listOf(p(bob), p(me, true)), "Bob")
        assertEquals(listOf(bob, me), one.participants)
        assertNull(one.cvName)
        assertNull(one.senderGuid)
        val group = DirectArchive.conversationData("imsg:group:G-9", listOf(p(bob), p(carol), p(me, true)), "Trip")
        assertEquals("G-9", group.senderGuid)
        assertEquals("Trip", group.cvName)
    }

    /** The English strings, as strings_imessage.xml has them. */
    object TestTexts : DirectArchive.Texts {
        override val you = "You"
        override val youObject = "you"
        override fun named(who: String, name: String) = "$who named the conversation “$name”."
        override fun added(who: String, whom: String) = "$who added $whom."
        override fun removed(who: String, whom: String) = "$who removed $whom."
        override fun left(who: String) = "$who left the conversation."
        override fun changedPhoto(who: String) = "$who changed the group photo."
        override fun removedPhoto(who: String) = "$who removed the group photo."
        override fun updated(who: String, app: String) = if (app.isEmpty()) "$who updated an iMessage app." else "$who updated $app."
    }
}
