package com.lightdecoder.imessage.direct

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.rust_bridge.CertifiedContext
import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.IndexedMessagePart
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MessagePart
import uniffi.rust_bridge.MessageType
import uniffi.rust_bridge.NormalMessage
import uniffi.rust_bridge.PushChatMessage
import uniffi.rust_bridge.TextFlags
import uniffi.rust_bridge.TextFormat
import java.io.File

/** What the ":imessage" process sends Apple besides the user's messages: receipts, and which chat. */
@RunWith(AndroidJUnit4::class)
class DirectMessagesTest {

    private val me = "tel:+15550000001"
    private val bob = "tel:+15550000002"
    private val carol = "mailto:carol@example.com"

    private fun normal(service: MessageType = MessageType.IMessage) = PushChatMessage.Message(
        NormalMessage(
            listOf(IndexedMessagePart(MessagePart.Text("hi", TextFormat.Flags(TextFlags(false, false, false, false))), 0UL, null)),
            null, null, null, service, null, null, null, false, null, null,
        ),
    )

    private fun inst(
        sender: String? = bob,
        participants: List<String> = listOf(me, bob),
        guid: String? = "CHAT-1",
        sendDelivered: Boolean = true,
        certified: CertifiedContext? = null,
        message: PushChatMessage = normal(),
    ) = MessageInst("M1", sender, ConversationData(participants, null, guid, null), message, 1UL, null, sendDelivered, false, certified)

    @Test
    fun theSenderIsToldTheirMessageArrived() {
        val ack = DirectMessages.deliveryAck(inst(), setOf(me))
        assertEquals(DirectMessages.DeliveryAck.Delivered(ConversationData(listOf(bob), null, "CHAT-1", null), me), ack)
    }

    @Test
    fun aCertifiedMessageIsCertifiedAndNotifiesOnlyWhenAsked() {
        val ctx = CertifiedContext(1u, byteArrayOf(1), bob, me, byteArrayOf(2), byteArrayOf(3))
        assertEquals(DirectMessages.DeliveryAck.Certify(ctx, true), DirectMessages.deliveryAck(inst(certified = ctx), setOf(me)))
        assertEquals(DirectMessages.DeliveryAck.Certify(ctx, false), DirectMessages.deliveryAck(inst(certified = ctx, sendDelivered = false), setOf(me)))
    }

    @Test
    fun noReceiptWhenNotAskedForToTheUsersOwnDevicesOrForATextMessage() {
        assertEquals(DirectMessages.DeliveryAck.None, DirectMessages.deliveryAck(inst(sendDelivered = false), setOf(me)))
        assertEquals(DirectMessages.DeliveryAck.None, DirectMessages.deliveryAck(inst(sender = me), setOf(me)))
        assertEquals(DirectMessages.DeliveryAck.None, DirectMessages.deliveryAck(inst(sender = null), setOf(me)))
        val sms = inst(message = normal(MessageType.Sms(true, me, bob)))
        assertEquals(DirectMessages.DeliveryAck.None, DirectMessages.deliveryAck(sms, setOf(me)))
    }

    @Test
    fun theReceiptGoesFromTheAddressTheChatUses() {
        val email = "mailto:me@icloud.com"
        val ack = DirectMessages.deliveryAck(inst(participants = listOf(email, bob)), setOf(me, email))
        assertEquals(email, (ack as DirectMessages.DeliveryAck.Delivered).from)
        assertEquals(me, DirectMessages.sendingHandle(null, setOf(email, me)))
    }

    @Test
    fun aOneToOneChatsGuidIsTakenFromItsMessages() {
        assertEquals(bob to "CHAT-1", DirectMessages.oneToOneChat(inst(), setOf(me)))
        assertEquals(bob to "CHAT-1", DirectMessages.oneToOneChat(inst(sender = me), setOf(me)))
        assertNull(DirectMessages.oneToOneChat(inst(participants = listOf(me, bob, carol)), setOf(me)))
        assertNull(DirectMessages.oneToOneChat(inst(guid = null), setOf(me)))
        assertNull(DirectMessages.oneToOneChat(inst(message = PushChatMessage.Typing(true, null)), setOf(me)))
        assertNull(DirectMessages.oneToOneChat(inst(message = normal(MessageType.Sms(true, me, bob))), setOf(me)))
    }

    @Test
    fun readReceiptsReachThePersonOnlyWhenTurnedOnAndOneToOne() {
        val one = DirectWire.Target(listOf(bob), null, null, me)
        val group = DirectWire.Target(listOf(bob, carol), "Trip", "G-1", me)
        assertEquals(listOf(bob), DirectMessages.readReceiptParticipants(one, sendReadReceipts = true))
        assertEquals(emptyList<String>(), DirectMessages.readReceiptParticipants(one, sendReadReceipts = false))
        assertEquals(emptyList<String>(), DirectMessages.readReceiptParticipants(group, sendReadReceipts = true))
    }

    @Test
    fun aSendNamesTheChatAndItsLatestMessage() {
        val one = DirectWire.Target(listOf(bob), null, null, me, afterGuid = "LAST")
        assertEquals(ConversationData(listOf(bob, me), null, "CHAT-9", "LAST"), DirectMessages.conversation(one, me, "CHAT-9"))
        val group = DirectWire.Target(listOf(bob, carol), "Trip", "G-1", me)
        assertEquals(ConversationData(listOf(bob, carol, me), "Trip", "G-1", null), DirectMessages.conversation(group, me, "ignored"))
    }

    @Test
    fun chatGuidsAreKeptAndTheFirstOneWins() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val file = File(context.filesDir, "imessage/chat-guids-test.json").apply { delete() }
        val guids = ChatGuids(file)
        guids.remember(bob, "FROM-BOB")
        guids.remember(bob, "LATER")
        assertEquals("FROM-BOB", guids.guidFor(bob))
        val made = guids.guidFor(carol)
        assertEquals(made.uppercase(), made)
        assertEquals(36, made.length)
        // An address in other case is the same person.
        assertEquals(made, guids.guidFor("mailto:Carol@Example.com"))
        // After a restart.
        val again = ChatGuids(file)
        assertEquals("FROM-BOB", again.guidFor(bob))
        assertEquals(made, again.guidFor(carol))
        assertNotEquals(made, again.guidFor("tel:+15550000009"))
        assertTrue(file.isFile)
        assertFalse(File(file.path + ".tmp").exists())
    }

    private fun uploaded(name: String, mime: String, sizes: List<Long>) = uniffi.rust_bridge.MessageAttachment(
        uniffi.rust_bridge.AttachmentType.Mmcs(sizes.map { uniffi.rust_bridge.MmcsFile(byteArrayOf(1), "o", "https://x/$it", byteArrayOf(2), it.toULong()) }),
        0UL, "public.jpeg", mime, name, false,
    )

    @Test
    fun aSentMessageHasItsTextFirstThenItsAttachmentsNumberedAsRustpushNumbersThem() {
        // With a caption: the text is part 0, the attachments 1 and 2.
        val withText = DirectMessages.outgoing("Look", listOf(uploaded("a.jpg", "image/jpeg", listOf(10, 500)), uploaded("b.mov", "video/quicktime", listOf(7))))
        assertEquals(3, withText.parts.size)
        assertEquals("Look", (withText.parts[0].part as MessagePart.Text).v1)
        assertTrue(withText.parts.drop(1).all { it.part is MessagePart.Attachment && it.idx == null })
        assertEquals(
            listOf(DirectWire.SentPart(1, "p1", "image/jpeg", "a.jpg", 500), DirectWire.SentPart(2, "p2", "video/quicktime", "b.mov", 7)),
            withText.attachments,
        )
        // Without one: no empty text, the attachments from 0.
        val alone = DirectMessages.outgoing("", listOf(uploaded("Audio Message.m4a", "audio/mp4", listOf(3))))
        assertEquals(1, alone.parts.size)
        assertEquals(listOf(DirectWire.SentPart(0, "p0", "audio/mp4", "Audio Message.m4a", 3)), alone.attachments)
        // A text alone.
        val text = DirectMessages.outgoing("hi", emptyList())
        assertEquals(1, text.parts.size)
        assertTrue(text.attachments.isEmpty())
    }

    private val game = IMessageExtra.App(
        name = "GamePigeon", appId = 1124197642L, bundleId = "com.apple.messages.MSMessageExtensionBalloonPlugin:EWFNLB79LQ:com.gamerdelights.gamepigeon.ext",
        url = "data:?ver=52&data=x", session = "0f8fad5b-d9cb-469f-a165-70867728950e", ldText = "8 Ball", isLive = true,
        imageTitle = "t", imageSubtitle = "st", caption = "Your move", subcaption = "sc", secondarySubcaption = "2", tertiarySubcaption = "3",
    )

    private val picture = uniffi.rust_bridge.MessageAttachment(
        uniffi.rust_bridge.AttachmentType.Mmcs(listOf(uniffi.rust_bridge.MmcsFile(byteArrayOf(1), "o", "https://x", byteArrayOf(2), 77UL))),
        0UL, "public.jpeg", "image/jpeg", "jpeg-image.jpeg", false,
    )

    @Test
    fun anIMessageAppsBalloonGoesAsOpenBubbleSendsIt() {
        val ext = DirectMessages.extensionApp(game)
        assertEquals("GamePigeon", ext.name)
        assertEquals(1124197642UL, ext.appId)
        assertEquals(game.bundleId, ext.bundleId)
        val b = ext.balloon!!
        assertEquals("data:?ver=52&data=x", b.url)
        assertEquals(game.session, b.session)
        assertEquals("8 Ball", b.ldText)
        assertTrue(b.isLive)
        val layout = b.layout as uniffi.rust_bridge.BalloonLayout.TemplateLayout
        assertEquals("Your move", layout.caption)
        assertEquals("2", layout.secondarySubcaption)
        assertEquals("3", layout.tertiarySubcaption)
        assertEquals("sc", layout.subcaption)
        assertEquals("t", layout.imageTitle)
        assertEquals("st", layout.imageSubtitle)
        // No session or summary: none, never an empty one.
        val bare = DirectMessages.extensionApp(game.copy(session = "", ldText = "")).balloon!!
        assertNull(bare.session)
        assertNull(bare.ldText)
    }

    @Test
    fun aCardsSummaryComesFirstAndItsPictureAfter() {
        val card = DirectMessages.appParts(game, listOf(picture), move = false)
        assertEquals(MessagePart.Object("8 Ball"), card.parts[0].part)
        assertTrue(card.parts[1].part is MessagePart.Attachment)
        assertEquals(listOf(DirectWire.SentPart(1, "p1", "image/jpeg", "jpeg-image.jpeg", 77)), card.attachments)
        // A new card without a summary is its picture alone; a move always has its placeholder.
        assertEquals(listOf("p0"), DirectMessages.appParts(game.copy(ldText = ""), listOf(picture), move = false).attachments.map { it.partId })
        val move = DirectMessages.appParts(game.copy(ldText = ""), emptyList(), move = true)
        assertEquals(listOf<MessagePart>(MessagePart.Object("")), move.parts.map { it.part })
        val update = DirectMessages.appUpdate("ROOT", game, move.parts, isMeta = false)
        assertEquals("ROOT", update.toUuid)
        assertEquals("", update.toText)
        val ext = update.reaction as uniffi.rust_bridge.ReactMessageType.Extension
        assertFalse(ext.isMeta)
        assertEquals(game.url, ext.spec.balloon!!.url)
        assertEquals(move.parts, ext.body)
    }

    @Test
    fun aTextWithMentionsIsOnePartWithItsMentionsAndTheAttachmentsAfterIt() {
        val text = "Hi Bob and Carol"
        val out = DirectMessages.outgoing(
            text, listOf(picture), listOf(TextRun(0, 2, bold = true)),
            listOf(Mention(3, 6, "tel:+15550000002"), Mention(11, 16, "mailto:carol@example.com")),
        )
        val parts = out.parts.map { it.part }
        assertEquals(MessagePart.Text("Hi", TextFormat.Flags(TextFlags(true, false, false, false))), parts[0])
        assertEquals(MessagePart.Text(" ", TextFormat.Flags(TextFlags(false, false, false, false))), parts[1])
        assertEquals(MessagePart.Mention("+15550000002", "Bob"), parts[2])
        assertEquals(MessagePart.Text(" and ", TextFormat.Flags(TextFlags(false, false, false, false))), parts[3])
        assertEquals(MessagePart.Mention("carol@example.com", "Carol"), parts[4])
        assertTrue(parts[5] is MessagePart.Attachment)
        // The text and its mentions are part 0, the picture part 1: what the archive, edits and replies number them by.
        assertEquals(listOf(0, 0, 0, 0, 0, 1), DirectArchive.partIndexes(out.parts))
        assertEquals(listOf("p1"), out.attachments.map { it.partId })
        assertEquals(mapOf(0 to text), DirectArchive.partTexts(out.parts))
        assertEquals("0:0:${text.length}", DirectWire.replyPart(text))
        // Back from the parts: the same mentions, and the formatting over the same text.
        assertEquals(mapOf(0 to listOf(Mention(3, 6, "+15550000002"), Mention(11, 16, "carol@example.com"))), Mentions.fromParts(out.parts, emptySet()))
        assertEquals(mapOf(0 to listOf(TextRun(0, 2, bold = true))), TextRuns.fromParts(out.parts))
        // An edit keeps them the same way.
        val edit = DirectMessages.editParts("Hi Bob!", emptyList(), listOf(Mention(3, 6, "+15550000002")))
        assertEquals(listOf<MessagePart>(MessagePart.Text("Hi ", TextFormat.Flags(TextFlags(false, false, false, false))), MessagePart.Mention("+15550000002", "Bob"), MessagePart.Text("!", TextFormat.Flags(TextFlags(false, false, false, false)))), edit.map { it.part })
    }

    @Test
    fun aLinksPreviewIsBuiltAsOpenBubblesBuildsOne() {
        val jpeg = byteArrayOf(1, 2, 3)
        val link = DirectWire.LinkPreviewRequest(
            url = "https://www.example.com/story", originalUrl = "https://example.com/s", title = "A story", summary = "About it",
            imageUrl = "https://example.com/cover.jpg", imagePath = "/unused", imageMime = "image/jpeg",
        )
        val meta = DirectMessages.linkMeta(link, jpeg)
        val d = meta.data
        assertEquals(uniffi.rust_bridge.NsUrl("\$null", "https://example.com/s"), d.originalUrl)
        assertEquals(uniffi.rust_bridge.NsUrl("\$null", "https://www.example.com/story"), d.url)
        assertEquals("A story", d.title)
        assertEquals("About it", d.summary)
        assertEquals(1u.toUByte(), d.version)
        // The picture: the first attachment, named by its index, described in an NSArray of one.
        assertEquals(1, meta.attachments.size)
        assertTrue(meta.attachments[0].contentEquals(jpeg))
        assertEquals(uniffi.rust_bridge.RichLinkImageAttachmentSubstitute("image/jpeg", 0UL), d.image)
        assertEquals(uniffi.rust_bridge.LpImageMetadata("{0, 0}", uniffi.rust_bridge.NsUrl("\$null", "https://example.com/cover.jpg"), 1u), d.imageMetadata)
        assertEquals(uniffi.rust_bridge.NsArrayLpImageMetadata(listOf(d.imageMetadata!!), uniffi.rust_bridge.NsArrayClass.NS_ARRAY), d.images)
        assertNull(d.icon)
        assertNull(d.iconMetadata)
        // What the archive reads of it, as one that came: the same picture.
        assertTrue(DirectArchive.linkImage(meta)!!.contentEquals(jpeg))
        // Without a picture: none described, no attachment; a blank title or summary goes as none.
        val bare = DirectMessages.linkMeta(link.copy(title = "", summary = ""), null)
        assertTrue(bare.attachments.isEmpty())
        assertNull(bare.data.image)
        assertNull(bare.data.images)
        assertNull(bare.data.title)
        assertNull(bare.data.summary)
        assertNull(DirectArchive.linkImage(bare))
    }

    @Test
    fun aVoteInAPollGoesAsOpenBubblesSendsOne() {
        val card1 = "8F0B2A6C-1D2E-4F30-9A1B-2C3D4E5F6A7B"
        val vote = Polls.vote(listOf("OPT-1"), "+15550000001", card1)
        val parts = DirectMessages.appParts(vote, emptyList(), move = true)
        val update = DirectMessages.appUpdate(card1, vote, parts.parts, isMeta = true)
        assertEquals(card1, update.toUuid)
        assertEquals(Polls.VOTE_NOTICE, update.toText)
        val ext = update.reaction as uniffi.rust_bridge.ReactMessageType.Extension
        assertTrue(ext.isMeta)
        assertEquals("Polls", ext.spec.name)
        assertNull(ext.spec.appId)
        assertEquals(Polls.BUNDLE_ID, ext.spec.bundleId)
        val b = ext.spec.balloon!!
        assertEquals(vote.url, b.url)
        assertEquals(card1, b.session)
        // A session that is no UUID would take the library down: none is sent then.
        val odd = DirectMessages.appUpdate("CARD-1", Polls.vote(listOf("OPT-1"), "+1", "CARD-1"), parts.parts, isMeta = true)
        assertNull((odd.reaction as uniffi.rust_bridge.ReactMessageType.Extension).spec.balloon!!.session)
        assertTrue(Polls.isUuid(card1))
        assertFalse(Polls.isUuid("1-1-1-1-1"))
        // No layout, summary or icon, and not live, as OpenBubbles sends a vote.
        assertNull(b.layout)
        assertNull(b.ldText)
        assertFalse(b.isLive)
        assertNull(b.icon)
        assertEquals(listOf<MessagePart>(MessagePart.Object("")), ext.body.map { it.part })
        // A choice added is the poll's card again, to its first card: live, with its layout.
        val poll = Polls.create("Q", listOf("A", "B"), "+15550000001")!!
        val card = Polls.card(poll, "0F8FAD5B-D9CB-469F-A165-70867728950E")
        val again = DirectMessages.appUpdate("ROOT", card, DirectMessages.appParts(card, emptyList(), move = true).parts, isMeta = false)
        val e = again.reaction as uniffi.rust_bridge.ReactMessageType.Extension
        assertFalse(e.isMeta)
        assertEquals("", again.toText)
        assertTrue(e.spec.balloon!!.isLive)
        assertEquals(Polls.FALLBACK_CAPTION, (e.spec.balloon!!.layout as uniffi.rust_bridge.BalloonLayout.TemplateLayout).caption)
        assertEquals(listOf<MessagePart>(MessagePart.Object("A, B")), e.body.map { it.part })
    }
}
