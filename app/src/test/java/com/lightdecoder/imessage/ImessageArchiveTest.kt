package com.lightdecoder.imessage

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
class ImessageArchiveTest {

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
        assertTrue(ImessageArchive.isImessage("imsg:tel:+1555"))
        assertFalse(ImessageArchive.isImessage("12345"))
        assertFalse(ImessageArchive.isImessage("tmp:abc"))
        assertEquals("imsg:ABC-123", ImessageArchive.messageId("ABC-123"))
        assertEquals("ABC-123", ImessageArchive.guidOf("imsg:ABC-123"))
        assertNull(ImessageArchive.guidOf("98765"))
    }

    @Test
    fun aOneToOneConversationIsKeyedByTheOtherPerson() {
        assertEquals("imsg:$bob", ImessageArchive.conversationId(listOf(me, bob), setOf(me), null))
        assertEquals("imsg:$bob", ImessageArchive.conversationId(listOf(bob, me), setOf(me), "guid-ignored"))
        assertFalse(ImessageArchive.isGroup("imsg:$bob"))
    }

    @Test
    fun aGroupIsKeyedByItsGuid() {
        val id = ImessageArchive.conversationId(listOf(me, bob, carol), setOf(me), "G-1")
        assertEquals("imsg:group:G-1", id)
        assertTrue(ImessageArchive.isGroup(id))
        assertEquals("G-1", ImessageArchive.groupGuid(id))
        val noGuid = ImessageArchive.conversationId(listOf(me, carol, bob), setOf(me), null)
        assertTrue(ImessageArchive.isGroup(noGuid))
        assertNull(ImessageArchive.groupGuid(noGuid))
    }

    @Test
    fun anIncomingMessageBecomesAConversationAndAMessage() {
        val u = ImessageArchive.map(message("M1", bob, listOf(me, bob), "hello", reply = "M0"), setOf(me), ::nameOf)
        u as ImessageArchive.Update.Upsert
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
            assertEquals(ImessageArchive.TRANSPORT, transport)
        }
    }

    @Test
    fun aCopyOfWhatTheUserSentFromAnotherDeviceIsOutgoing() {
        val u = ImessageArchive.map(message("M2", me, listOf(me, bob), "sent from OpenBubbles"), setOf(me), ::nameOf)
        u as ImessageArchive.Update.Upsert
        assertEquals("imsg:$bob", u.conversation.id)
        assertTrue(u.message.fromMe)
        assertEquals("out", u.message.direction)
        assertEquals(ImessageArchive.STATUS_SENT, u.message.status)
    }

    @Test
    fun aGroupTakesItsNameOrItsMembers() {
        val named = ImessageArchive.map(message("M3", bob, listOf(me, bob, carol), "hi all", "G-2", "Trip"), setOf(me), ::nameOf)
        assertEquals("Trip", (named as ImessageArchive.Update.Upsert).conversation.name)
        assertTrue(named.conversation.isGroup)
        val unnamed = ImessageArchive.map(message("M4", bob, listOf(me, bob, carol), "hi", "G-3"), setOf(me), ::nameOf)
        assertEquals("Bob, Carol", (unnamed as ImessageArchive.Update.Upsert).conversation.name)
    }

    @Test
    fun unsendEditAndReceiptsChangeTheStoredMessage() {
        val conv = ConversationData(listOf(me, bob), null, null, null)
        fun inst(id: String, m: PushChatMessage) = MessageInst(id, bob, conv, m, 0UL, null, false, false, null)
        assertEquals(ImessageArchive.Update.Remove("imsg:M1"), ImessageArchive.map(inst("X", PushChatMessage.Unsend(UnsendMessage("M1", 0UL))), setOf(me), ::nameOf))
        assertEquals(
            ImessageArchive.Update.Edit("imsg:M1", "fixed"),
            ImessageArchive.map(inst("X", PushChatMessage.Edit(EditMessage("M1", 0UL, listOf(text("fixed"))))), setOf(me), ::nameOf),
        )
        assertEquals(
            ImessageArchive.Update.Receipt(listOf("imsg:M5"), ImessageArchive.STATUS_DELIVERED),
            ImessageArchive.map(inst("M5", PushChatMessage.Delivered), setOf(me), ::nameOf),
        )
        assertEquals(
            ImessageArchive.Update.Receipt(listOf("imsg:M5"), ImessageArchive.STATUS_READ),
            ImessageArchive.map(inst("M5", PushChatMessage.Read), setOf(me), ::nameOf),
        )
        assertEquals(ImessageArchive.Update.Ignore, ImessageArchive.map(inst("X", PushChatMessage.MarkUnread), setOf(me), ::nameOf))
    }

    @Test
    fun receiptsNeverMoveAMessageBackwards() {
        assertTrue(ImessageArchive.statusRank(ImessageArchive.STATUS_READ) > ImessageArchive.statusRank(ImessageArchive.STATUS_DELIVERED))
        assertTrue(ImessageArchive.statusRank(ImessageArchive.STATUS_DELIVERED) > ImessageArchive.statusRank(ImessageArchive.STATUS_SENT))
    }

    @Test
    fun attachmentsAreNamedInTheText() {
        val parts = listOf(
            MessagePart.Text("look", TextFormat.Flags(TextFlags(false, false, false, false))),
            MessagePart.Attachment(uniffi.rust_bridge.MessageAttachment(uniffi.rust_bridge.AttachmentType.Inline(byteArrayOf()), 1UL, "public.jpeg", "image/jpeg", "IMG_1.jpg", false)),
        )
        assertEquals("look\n" + ImessageArchive.ATTACHMENT_LINE + "IMG_1.jpg", ImessageArchive.textOf(parts))
    }

    @Test
    fun sendingToAStoredConversationUsesItsMembersAndGroupGuid() {
        fun p(id: String, isMe: Boolean = false) = ParticipantRow("c", id, "", "", "", "", isMe, "", "", true)
        val one = ImessageArchive.conversationData("imsg:$bob", listOf(p(bob), p(me, true)), "Bob")
        assertEquals(listOf(bob, me), one.participants)
        assertNull(one.cvName)
        assertNull(one.senderGuid)
        val group = ImessageArchive.conversationData("imsg:group:G-9", listOf(p(bob), p(carol), p(me, true)), "Trip")
        assertEquals("G-9", group.senderGuid)
        assertEquals("Trip", group.cvName)
    }
}
