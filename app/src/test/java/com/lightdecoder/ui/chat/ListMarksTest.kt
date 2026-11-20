package com.lightdecoder.ui.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.ConversationRow
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageLinks
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.ParticipantDto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The conversation list's own marks: "Pin" and "Mark as Unread" are the user's and outlast what the
 * phone reports of a conversation; pinned ones are listed first; a Messages conversation with the
 * person's iMessage chats in it is pinned, unread or quiet when either is.
 */
@RunWith(AndroidJUnit4::class)
class ListMarksTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ArchiveDb
    private lateinit var repo: ArchiveRepository

    @Before
    fun setUp() {
        context.deleteDatabase(DB)
        db = ArchiveDb(context, DB)
        repo = ArchiveRepository.forTesting(db, ChatService.MESSAGES)
    }

    @After
    fun tearDown() {
        repo.clearAll()
        db.close()
    }

    private fun conversation(id: String, lastTs: Long, pinned: Boolean = false, unread: Boolean = false) = ConversationDto(
        id = id, name = id, folder = "inbox", unread = unread, pinned = pinned, lastMessageTs = lastTs,
        participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = "p-$id", name = id, number = "+1555010000${id.length}")),
    )

    @Test
    fun aPinMadeHereOutlastsThePhonesWordAndIsListedFirst() {
        repo.upsertConversation(conversation("a", 3_000))
        repo.upsertConversation(conversation("b", 2_000))
        repo.upsertConversation(conversation("c", 1_000, pinned = true))
        assertEquals(listOf("c", "a", "b"), repo.conversations().map { it.id })
        assertTrue(repo.setConversationPinned("b", true))
        // The phone says the same conversations again, as it does with every new message: the pin stays.
        repo.upsertConversation(conversation("b", 4_000, pinned = false))
        assertEquals(listOf("b", "c", "a"), repo.conversations().map { it.id })
        // Unpinned here, it stays unpinned, though the phone had it pinned.
        assertTrue(repo.setConversationPinned("c", false))
        repo.upsertConversation(conversation("c", 1_500, pinned = true))
        assertEquals(listOf("b", "a", "c"), repo.conversations().map { it.id })
        // Gone, and back: the phone's word again.
        repo.deleteConversationLocal("c")
        repo.upsertConversation(conversation("c", 1_500, pinned = true))
        assertTrue(repo.conversation("c")!!.pinned)
        assertFalse("no such conversation", repo.setConversationPinned("zzz", true))
    }

    @Test
    fun markedUnreadItStaysUnreadUntilReadAgainWhateverThePhoneSays() {
        repo.upsertConversation(conversation("a", 1_000))
        assertTrue(repo.markConversationUnread("a"))
        assertEquals(1, repo.unreadCount())
        repo.upsertConversation(conversation("a", 2_000, unread = false))
        assertTrue(repo.conversation("a")!!.unread)
        repo.markConversationRead("a")
        assertFalse(repo.conversation("a")!!.unread)
        // Read here, the phone's word holds again.
        repo.upsertConversation(conversation("a", 3_000, unread = false))
        assertFalse(repo.conversation("a")!!.unread)
        assertFalse(repo.markConversationUnread("zzz"))
    }

    private fun row(id: String, lastTs: Long, pinned: Boolean = false, muted: Boolean = false, unread: Boolean = false) =
        ConversationRow(id, id, false, false, "inbox", 0, unread, pinned, false, muted, lastTs, "", "s-$id", false, "", "", "", 0)

    @Test
    fun aMessagesConversationIsPinnedQuietAndUnreadWhenItsIMessageChatIs() {
        val own = listOf(
            ConversationItem(row("c1", 1_000), "Alice"),
            ConversationItem(row("c2", 5_000), "Bob"),
            ConversationItem(row("c3", 3_000), "Carol"),
        )
        val iMessages = listOf(
            ConversationItem(row("imsg:alice", 2_000, pinned = true, muted = true, unread = true), "Alice", ChatService.IMESSAGE),
            ConversationItem(row("imsg:dave", 4_000), "Dave", ChatService.IMESSAGE),
        )
        val pairs = mapOf("imsg:alice" to IMessageLinks.Pair("c1", IMessageLinks.How.NUMBER))
        val listed = InboxLists.foldIn(own, iMessages, pairs)
        // Alice's conversation is pinned (her iMessage chat is): first, then the newest first.
        assertEquals(listOf("c1", "c2", "imsg:dave", "c3"), listed.map { it.row.id })
        val alice = listed.first().row
        assertTrue(alice.pinned)
        assertTrue(alice.muted)
        assertTrue(alice.unread)
        assertEquals("her iMessage is newer", 2_000, alice.lastMessageTs)
        assertFalse(listed.single { it.row.id == "c2" }.row.pinned)
        // Without the iMessage chat's marks, as it was.
        val plain = InboxLists.foldIn(own, listOf(iMessages[0].copy(row = row("imsg:alice", 500)), iMessages[1]), pairs)
        assertEquals(listOf("c2", "imsg:dave", "c3", "c1"), plain.map { it.row.id })
        assertFalse(plain.single { it.row.id == "c1" }.row.muted)
    }

    private companion object {
        const val DB = "list-marks-test.db"
    }
}
