package com.lightdecoder.imessage.direct

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.ParticipantDto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.IndexedMessagePart
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MessagePart
import uniffi.rust_bridge.MessageType
import uniffi.rust_bridge.NormalMessage
import uniffi.rust_bridge.PushChatMessage
import uniffi.rust_bridge.TextFlags
import uniffi.rust_bridge.TextFormat

/**
 * The library's messages land in Blue Bubbles' iMessage archive, in its conventions: a person's
 * existing chat is reused, the user is `me`, addresses are stored without their scheme.
 */
@RunWith(AndroidJUnit4::class)
class DirectLinkTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ArchiveDb
    private lateinit var repo: ArchiveRepository
    private val me = "tel:+15550000001"
    private val alice = "tel:+15551234567"
    private val bob = "tel:+15559876543"

    @Before
    fun setUp() {
        context.deleteDatabase(DB)
        db = ArchiveDb(context, DB)
        repo = ArchiveRepository.forTesting(db, ChatService.IMESSAGE)
        ArchiveRepository.installForTesting(repo)
        IMessageManager.installForTesting(context, repo)
    }

    @After
    fun tearDown() {
        IMessageManager.resetForTesting()
        ArchiveRepository.resetForTesting()
        db.close()
    }

    private fun incoming(guid: String, from: String, text: String, ts: Long = 1_700_000_000_000L, replyGuid: String? = null) =
        DirectArchive.map(
            MessageInst(
                guid, from, ConversationData(listOf(me, from), null, null, null),
                PushChatMessage.Message(
                    NormalMessage(
                        listOf(IndexedMessagePart(MessagePart.Text(text, TextFormat.Flags(TextFlags(false, false, false, false))), null, null)),
                        null, replyGuid, replyGuid?.let { "0:0:0" }, MessageType.IMessage, null, null, null, false, null, null,
                    ),
                ),
                ts.toULong(), null, false, false, null,
            ),
            setOf(me), Handles::display,
        ).let { DirectWire.decode(DirectWire.encode(it)) } // as it arrives from the other process

    @Test
    fun aPersonsBlueBubblesChatIsReused() {
        // Alice's chat as Blue Bubbles stored it from OpenBubbles' notifications.
        repo.upsertConversation(
            ConversationDto(
                id = "iMessage;-;+15551234567", type = IMessageManager.TRANSPORT,
                participants = listOf(
                    ParticipantDto(id = "me", isMe = true),
                    ParticipantDto(id = "+15551234567", name = "Alice", number = "+15551234567"),
                ),
            ),
        )
        DirectLink.get(context).apply(incoming("G1", alice, "hello from the library"))
        val m = repo.message("imsg:G1")!!
        assertEquals("iMessage;-;+15551234567", m.conversationId)
        assertEquals("hello from the library", m.text)
        assertEquals("+15551234567", m.senderId)
        assertEquals(IMessageManager.TRANSPORT, m.transport)
        assertNull(repo.conversation("imsg:$alice"))
        assertTrue(repo.conversation("iMessage;-;+15551234567")!!.unread)
    }

    @Test
    fun someoneNewGetsAChatInTheArchivesConventions() {
        DirectLink.get(context).apply(incoming("G2", bob, "hi"))
        val id = "imsg:$bob"
        assertEquals(id, repo.message("imsg:G2")!!.conversationId)
        val people = repo.participants(id)
        assertEquals("me", people.single { it.isMe }.id)
        assertEquals("+15559876543", people.single { !it.isMe }.number)
        // Sending back to them (after any restart): their address, from the archive.
        val target = DirectLink.get(context).targetOf(id)!!
        assertEquals(listOf(bob), target.participants)
        assertNull(target.groupGuid)
    }

    @Test
    fun aGroupBlueBubblesMadeIsNotSentToDirectly() {
        // A group chat from OpenBubbles' notifications has no iMessage group id: Blue Bubbles' way is used.
        repo.upsertConversation(
            ConversationDto(
                id = "iMessage;+;chat123", isGroup = true, name = "Trip", type = IMessageManager.TRANSPORT,
                participants = listOf(
                    ParticipantDto(id = "me", isMe = true),
                    ParticipantDto(id = "+15551234567", number = "+15551234567"),
                    ParticipantDto(id = "+15559876543", number = "+15559876543"),
                ),
            ),
        )
        assertNull(DirectLink.get(context).targetOf("iMessage;+;chat123"))
    }

    @Test
    fun anInlineReplyFromAnIphoneShowsUnderTheMessageItAnswers() {
        val link = DirectLink.get(context)
        link.apply(incoming("G4", bob, "dinner at 7?"))
        link.apply(incoming("G5", bob, "or 8", ts = 1_700_000_001_000L, replyGuid = "G4"))
        assertEquals("imsg:G4", repo.message("imsg:G5")!!.replyToId)
        // And a reply from here to either threads under it on their iPhone.
        assertEquals("G4", DirectArchive.guidOf(repo.message("imsg:G4")!!.id))
    }

    @Test
    fun receiptsAndUnsendsReachTheStoredMessages() {
        val link = DirectLink.get(context)
        link.apply(incoming("G3", bob, "to be unsent"))
        link.apply(DirectArchive.Update.Remove("imsg:G3"))
        assertNull(repo.message("imsg:G3"))
    }

    companion object {
        private const val DB = "direct-link-test.db"
    }
}
