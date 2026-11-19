package com.lightdecoder.imessage.direct

import com.lightdecoder.archive.ParticipantRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.EditMessage
import uniffi.rust_bridge.IndexedMessagePart
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MessagePart
import uniffi.rust_bridge.MessageType
import uniffi.rust_bridge.NormalMessage
import uniffi.rust_bridge.PushChatMessage
import uniffi.rust_bridge.TextFlags
import uniffi.rust_bridge.TextFormat
import uniffi.rust_bridge.UnsendMessage

/** iMessages in the Messages tab's archive: ids, and what each event from Apple changes. */
class DirectArchiveTest {

    private val me = "tel:+15550000001"
    private val bob = "tel:+15550000002"
    private val carol = "mailto:carol@example.com"
    private val names = mapOf(bob to "Bob", carol to "Carol")
    private fun nameOf(h: String) = names[h] ?: Handles.display(h)

    private fun text(s: String) = IndexedMessagePart(MessagePart.Text(s, TextFormat.Flags(TextFlags(false, false, false, false))), null, null)

    private fun message(id: String, sender: String?, participants: List<String>, body: String, groupGuid: String? = null, name: String? = null, reply: String? = null) =
        MessageInst(
            id, sender, ConversationData(participants, name, groupGuid, null),
            PushChatMessage.Message(NormalMessage(listOf(text(body)), null, reply, null, MessageType.IMessage, null, null, null, false, null, null)),
            1_700_000_000_000UL, null, false, false, null,
        )

    @Test
    fun idsNeverLookLikeGoogleMessagesIds() {
        assertTrue(DirectArchive.isImessage("imsg:tel:+1555"))
        assertFalse(DirectArchive.isImessage("12345"))
        assertFalse(DirectArchive.isImessage("tmp:abc"))
        assertEquals("imsg:ABC-123", DirectArchive.messageId("ABC-123"))
        assertEquals("ABC-123", DirectArchive.guidOf("imsg:ABC-123"))
        assertNull(DirectArchive.guidOf("98765"))
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
        val u = DirectArchive.map(message("M1", bob, listOf(me, bob), "hello", reply = "M0"), setOf(me), ::nameOf)
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
        }
    }

    @Test
    fun aCopyOfWhatTheUserSentFromAnotherDeviceIsOutgoing() {
        val u = DirectArchive.map(message("M2", me, listOf(me, bob), "sent from OpenBubbles"), setOf(me), ::nameOf)
        u as DirectArchive.Update.Upsert
        assertEquals("imsg:$bob", u.conversation.id)
        assertTrue(u.message.fromMe)
        assertEquals("out", u.message.direction)
        assertEquals(DirectArchive.STATUS_SENT, u.message.status)
    }

    @Test
    fun aGroupTakesItsNameOrItsMembers() {
        val named = DirectArchive.map(message("M3", bob, listOf(me, bob, carol), "hi all", "G-2", "Trip"), setOf(me), ::nameOf)
        assertEquals("Trip", (named as DirectArchive.Update.Upsert).conversation.name)
        assertTrue(named.conversation.isGroup)
        val unnamed = DirectArchive.map(message("M4", bob, listOf(me, bob, carol), "hi", "G-3"), setOf(me), ::nameOf)
        assertEquals("Bob, Carol", (unnamed as DirectArchive.Update.Upsert).conversation.name)
    }

    @Test
    fun unsendEditAndReceiptsChangeTheStoredMessage() {
        val conv = ConversationData(listOf(me, bob), null, null, null)
        fun inst(id: String, m: PushChatMessage) = MessageInst(id, bob, conv, m, 0UL, null, false, false, null)
        assertEquals(DirectArchive.Update.Remove("imsg:M1"), DirectArchive.map(inst("X", PushChatMessage.Unsend(UnsendMessage("M1", 0UL))), setOf(me), ::nameOf))
        assertEquals(
            DirectArchive.Update.Edit("imsg:M1", "fixed"),
            DirectArchive.map(inst("X", PushChatMessage.Edit(EditMessage("M1", 0UL, listOf(text("fixed"))))), setOf(me), ::nameOf),
        )
        assertEquals(
            DirectArchive.Update.Receipt(listOf("imsg:M5"), DirectArchive.STATUS_DELIVERED),
            DirectArchive.map(inst("M5", PushChatMessage.Delivered), setOf(me), ::nameOf),
        )
        assertEquals(
            DirectArchive.Update.Receipt(listOf("imsg:M5"), DirectArchive.STATUS_READ),
            DirectArchive.map(inst("M5", PushChatMessage.Read), setOf(me), ::nameOf),
        )
        assertEquals(DirectArchive.Update.Ignore, DirectArchive.map(inst("X", PushChatMessage.MarkUnread), setOf(me), ::nameOf))
    }

    @Test
    fun receiptsNeverMoveAMessageBackwards() {
        assertTrue(DirectArchive.statusRank(DirectArchive.STATUS_READ) > DirectArchive.statusRank(DirectArchive.STATUS_DELIVERED))
        assertTrue(DirectArchive.statusRank(DirectArchive.STATUS_DELIVERED) > DirectArchive.statusRank(DirectArchive.STATUS_SENT))
    }

    @Test
    fun attachmentsAreNamedInTheText() {
        val parts = listOf(
            MessagePart.Text("look", TextFormat.Flags(TextFlags(false, false, false, false))),
            MessagePart.Attachment(uniffi.rust_bridge.MessageAttachment(uniffi.rust_bridge.AttachmentType.Inline(byteArrayOf()), 1UL, "public.jpeg", "image/jpeg", "IMG_1.jpg", false)),
        )
        assertEquals("look\n" + DirectArchive.ATTACHMENT_LINE + "IMG_1.jpg", DirectArchive.textOf(parts))
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
}
