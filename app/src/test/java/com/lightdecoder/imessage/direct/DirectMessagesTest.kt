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
}
