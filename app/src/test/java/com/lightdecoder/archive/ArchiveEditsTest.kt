package com.lightdecoder.archive

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.chat.ChatService
import com.lightdecoder.reactions.ReactionKey
import com.lightdecoder.reactions.ReactionTimes
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.PartDto
import com.lightdecoder.relay.ReactionDto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Version 3 of the archive: edits, reactions one per person, unsent messages and the newest
 * message ids, as iMessage needs them.
 */
@RunWith(AndroidJUnit4::class)
class ArchiveEditsTest {

    private lateinit var context: Context
    private lateinit var db: ArchiveDb
    private lateinit var repo: ArchiveRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
        db = ArchiveDb(context, DB_NAME)
        repo = ArchiveRepository.forTesting(db, ChatService.IMESSAGE)
        ReactionTimes.resetForTesting()
    }

    @After
    fun tearDown() {
        db.close()
        ReactionTimes.resetForTesting()
    }

    private fun message(
        id: String,
        text: String = "",
        ts: Long = 1_000,
        fromMe: Boolean = false,
        status: String = if (fromMe) "OUTGOING_COMPLETE" else "INCOMING_COMPLETE",
        parts: List<PartDto> = if (text.isEmpty()) emptyList() else listOf(PartDto("0", "text", text)),
        reactions: List<ReactionDto> = emptyList(),
        extra: String = "",
        editedAtMs: Long = 0,
    ) = MessageDto(
        id = id, conversationId = CONV, senderId = if (fromMe) "" else "+15550000002", fromMe = fromMe, timestampMs = ts,
        direction = if (fromMe) "out" else "in", status = status, transport = "IMESSAGE", text = text, parts = parts,
        reactions = reactions, replyToId = "", extra = extra, editedAtMs = editedAtMs,
    )

    private fun picture(partId: String, mediaId: String) =
        PartDto(partId = partId, kind = "media", mediaId = mediaId, mime = "image/jpeg", size = 10)

    @Test
    fun editedAtAndExtraAreStoredAndReadBack() {
        repo.upsertMessage(message("imsg:A", "hi", extra = "{\"edits\":1}", editedAtMs = 5_000))
        val row = repo.message("imsg:A")!!
        assertEquals(5_000L, row.editedAtMs)
        assertEquals("{\"edits\":1}", row.extra)
        // Other services leave them at their defaults.
        repo.upsertMessage(message("m-other", "plain"))
        assertEquals(0L, repo.message("m-other")!!.editedAtMs)
        assertEquals("", repo.message("m-other")!!.extra)
    }

    @Test
    fun aVersion2ArchiveGetsTheNewColumnsAndKeepsItsRows() {
        val raw = SQLiteDatabase.create(null)
        raw.execSQL("CREATE TABLE messages (message_id TEXT PRIMARY KEY, text_body TEXT NOT NULL DEFAULT '')")
        raw.execSQL("INSERT INTO messages (message_id, text_body) VALUES ('m1', 'old')")
        db.onUpgrade(raw, 2, ArchiveDb.VERSION)
        raw.rawQuery("SELECT text_body, edited_at, extra_json FROM messages", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("old", c.getString(0))
            assertEquals(0L, c.getLong(1))
            assertEquals("", c.getString(2))
        }
        // Run twice (an interrupted upgrade): no duplicate column.
        db.onUpgrade(raw, 2, ArchiveDb.VERSION)
        raw.close()
    }

    @Test
    fun anEditKeepsReactionsMediaAndStatusAndMovesTheSnippet() {
        repo.upsertMessage(
            message(
                "imsg:A", "dinner at 7", fromMe = true, status = "OUTGOING_DELIVERED",
                parts = listOf(PartDto("0", "text", "dinner at 7"), picture("p1", "A/p1")),
                reactions = listOf(ReactionDto("👍", listOf("+15550000002"))),
            ),
        )
        assertTrue(repo.editText("imsg:A", "dinner at 8", 9_000, "{\"edits\":1}"))
        val row = repo.message("imsg:A")!!
        assertEquals("dinner at 8", row.text)
        assertEquals(9_000L, row.editedAtMs)
        assertEquals("{\"edits\":1}", row.extra)
        assertEquals(listOf(ReactionDto("👍", listOf("+15550000002"))), row.reactions)
        assertEquals(listOf("p1"), row.media.map { it.partId })
        assertEquals("OUTGOING_DELIVERED", row.status)
        assertEquals("dinner at 8", repo.conversation(CONV)!!.snippet)
        assertEquals(listOf("imsg:A"), repo.search("8").map { it.messageId })
        assertTrue(repo.search("7").isEmpty())
        // A null extra leaves the stored one.
        assertTrue(repo.editText("imsg:A", "dinner at 9", 10_000, null))
        assertEquals("{\"edits\":1}", repo.message("imsg:A")!!.extra)
        assertFalse(repo.editText("imsg:missing", "x", 1, null))
    }

    @Test
    fun editingAnOlderMessageLeavesTheSnippet() {
        repo.upsertMessage(message("imsg:A", "first", ts = 1_000))
        repo.upsertMessage(message("imsg:B", "second", ts = 2_000))
        repo.editText("imsg:A", "first, fixed", 3_000, null)
        assertEquals("second", repo.conversation(CONV)!!.snippet)
    }

    @Test
    fun eachPersonHasOneTapback() {
        repo.upsertMessage(message("imsg:A", "hello"))
        val bob = "+15550000002"
        val carol = "carol@example.com"
        assertTrue(repo.applyReaction("imsg:A", bob, "❤️", enable = true, fromMe = false, atMs = 1))
        assertTrue(repo.applyReaction("imsg:A", carol, "❤️", enable = true, fromMe = false, atMs = 2))
        assertEquals(listOf(ReactionDto("❤️", listOf(bob, carol))), repo.message("imsg:A")!!.reactions)
        // Bob switches to a laugh: his heart goes.
        repo.applyReaction("imsg:A", bob, "😂", enable = true, fromMe = false, atMs = 3)
        assertEquals(listOf(ReactionDto("❤️", listOf(carol)), ReactionDto("😂", listOf(bob))), repo.message("imsg:A")!!.reactions)
        // Taking back an emoji he does not have changes nothing; his laugh stays.
        repo.applyReaction("imsg:A", bob, "❤️", enable = false, fromMe = false, atMs = 4)
        assertEquals(listOf(ReactionDto("❤️", listOf(carol)), ReactionDto("😂", listOf(bob))), repo.message("imsg:A")!!.reactions)
        repo.applyReaction("imsg:A", bob, "😂", enable = false, fromMe = false, atMs = 5)
        assertEquals(listOf(ReactionDto("❤️", listOf(carol))), repo.message("imsg:A")!!.reactions)
        assertFalse(repo.applyReaction("imsg:missing", bob, "❤️", enable = true, fromMe = false, atMs = 6))
    }

    @Test
    fun theUsersTapbackIsFromMeAndHasATime() {
        repo.upsertMessage(message("imsg:A", "hello", reactions = listOf(ReactionDto("👍", listOf("+15550000002")))))
        repo.applyReaction("imsg:A", "me", "👍", enable = true, fromMe = true, atMs = 7_000)
        assertEquals(listOf(ReactionDto("👍", listOf("+15550000002", "me"), fromMe = true)), repo.message("imsg:A")!!.reactions)
        val time = ReactionTimes.get(context).timesFor(ChatService.IMESSAGE, "imsg:A")[ReactionKey("👍", "me")]
        assertEquals(7_000L, time?.atMs)
        repo.applyReaction("imsg:A", "me", "‼️", enable = true, fromMe = true, atMs = 8_000)
        assertEquals(
            listOf(ReactionDto("👍", listOf("+15550000002")), ReactionDto("‼️", listOf("me"), fromMe = true)),
            repo.message("imsg:A")!!.reactions,
        )
        repo.applyReaction("imsg:A", "me", "‼️", enable = false, fromMe = true, atMs = 9_000)
        assertEquals(listOf(ReactionDto("👍", listOf("+15550000002"))), repo.message("imsg:A")!!.reactions)
    }

    @Test
    fun anUnsentMessageBecomesAPlaceholderInItsPlace() {
        repo.upsertMessage(message("imsg:A", "older", ts = 1_000))
        repo.upsertMessage(
            message(
                "imsg:B", "oops", ts = 2_000, fromMe = true,
                parts = listOf(PartDto("0", "text", "oops"), picture("p1", "B/p1")),
                reactions = listOf(ReactionDto("😂", listOf("+15550000002"))),
            ).copy(replyToId = "imsg:A", subject = "s"),
        )
        val file = MediaCache.fileFor(context, "B/p1", "jpg", ChatService.IMESSAGE).apply { parentFile?.mkdirs(); writeText("x") }
        assertTrue(repo.setMediaLocalPath("imsg:B", "p1", "B/p1", file.path, thumbnail = false))

        assertTrue(repo.replaceWithPlaceholder("imsg:B", "You unsent a message."))
        val row = repo.message("imsg:B")!!
        assertEquals("system", row.direction)
        assertEquals("You unsent a message.", row.text)
        assertEquals(2_000L, row.timestampMs)
        assertEquals(CONV, row.conversationId)
        assertTrue(row.reactions.isEmpty())
        assertTrue(row.media.isEmpty())
        assertEquals("", row.replyToId)
        assertEquals("", row.subject)
        assertFalse(file.exists())
        assertEquals("You unsent a message.", repo.conversation(CONV)!!.snippet)
        assertTrue(repo.search("oops").isEmpty())
        assertFalse(repo.replaceWithPlaceholder("imsg:missing", "x"))
    }

    @Test
    fun oneAttachmentCanBeTakenBack() {
        repo.upsertMessage(message("imsg:A", "two pictures", parts = listOf(PartDto("0", "text", "two pictures"), picture("p1", "A/p1"), picture("p2", "A/p2"))))
        assertTrue(repo.removeMediaPart("imsg:A", "p1"))
        assertEquals(listOf("p2"), repo.message("imsg:A")!!.media.map { it.partId })
        assertFalse(repo.removeMediaPart("imsg:A", "p1"))
    }

    @Test
    fun theNewestIdsAreFoundByPrefix() {
        assertNull(repo.latestMessageId(CONV))
        repo.upsertMessage(message("imsg:IN1", "a", ts = 1_000))
        repo.upsertMessage(message("imsg:OUT1", "b", ts = 2_000, fromMe = true))
        repo.upsertMessage(message("bb-row", "c", ts = 3_000))
        repo.insertLocalOutgoing("t1", CONV, "sending", null, null, null, null, 4_000)
        assertEquals("imsg:OUT1", repo.latestMessageId(CONV))
        assertEquals("imsg:IN1", repo.latestIncomingMessageId(CONV))
        assertEquals("bb-row", repo.latestIncomingMessageId(CONV, prefix = "bb-"))
        assertNotNull(repo.message("tmp:t1"))
    }

    @Test
    fun aSentMessageIsMarkedFailedUnlessItGotFurther() {
        val rank = { s: String -> when (s) { "OUTGOING_COMPLETE" -> 1; "OUTGOING_DELIVERED" -> 2; "OUTGOING_DISPLAYED" -> 3; else -> 0 } }
        repo.upsertMessage(message("imsg:A", "a", fromMe = true))
        repo.upsertMessage(message("imsg:B", "b", fromMe = true, status = "OUTGOING_DELIVERED"))
        repo.upsertMessage(message("imsg:C", "c"))
        assertTrue(repo.markSentFailed("imsg:A", "OUTGOING_FAILED", "not delivered", 2, rank))
        assertEquals("OUTGOING_FAILED", repo.message("imsg:A")!!.status)
        assertEquals("not delivered", repo.message("imsg:A")!!.errorText)
        assertFalse(repo.markSentFailed("imsg:B", "OUTGOING_FAILED", "late", 2, rank))
        assertEquals("OUTGOING_DELIVERED", repo.message("imsg:B")!!.status)
        assertFalse("not the user's", repo.markSentFailed("imsg:C", "OUTGOING_FAILED", "x", 2, rank))
    }

    private companion object {
        const val DB_NAME = "archive-edits-test.db"
        const val CONV = "imsg:tel:+15550000002"
    }
}
