package com.lightdecoder.archive

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.chat.ChatService
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.Folder
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.PartDto
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
 * Version 4 of the archive: Recently Deleted (a conversation's time there, retention), where a chat's
 * unread messages begin ("Jump to first unread"), and the deleted iMessage ids kept so that Apple
 * handing one over again does not bring it back. One migration, from every earlier version.
 */
@RunWith(AndroidJUnit4::class)
class ArchiveV4Test {

    private lateinit var context: Context
    private var db: ArchiveDb? = null
    private lateinit var repo: ArchiveRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        db?.close()
        context.deleteDatabase(DB_NAME)
    }

    private fun open(): ArchiveRepository {
        db = ArchiveDb(context, DB_NAME)
        return ArchiveRepository.forTesting(db!!, ChatService.IMESSAGE).also { repo = it }
    }

    private fun message(id: String, conversation: String = CONV, ts: Long = 1_000, text: String = "hi", fromMe: Boolean = false) = MessageDto(
        id = id, conversationId = conversation, senderId = if (fromMe) "" else "+15550000002", fromMe = fromMe, timestampMs = ts,
        direction = if (fromMe) "out" else "in", status = "INCOMING_COMPLETE", transport = "IMESSAGE", text = text,
        parts = listOf(PartDto("0", "text", text)),
    )

    // --- the migration ------------------------------------------------------------------------------

    /** An archive as [version] (1, 2 or 3) made it, with real rows in it. */
    private fun writeOld(version: Int) {
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        (if (version >= 3) V3_SCHEMA else V1_SCHEMA).forEach(raw::execSQL)
        if (version >= 2) {
            raw.execSQL("CREATE INDEX IF NOT EXISTS idx_media_local_path ON media(local_path)")
            raw.execSQL("CREATE INDEX IF NOT EXISTS idx_media_thumb_path ON media(thumb_path)")
        }
        raw.insertOrThrow("conversations", null, ContentValues().apply {
            put("conversation_id", CONV); put("name", "Bob"); put("unread", 1); put("pinned", 1)
            put("last_message_ts", 2_000); put("latest_message_id", "imsg:M2"); put("snippet", "second"); put("updated_at", 5)
        })
        raw.insertOrThrow("conversations", null, ContentValues().apply {
            put("conversation_id", OTHER); put("folder", Folder.DELETED.wire); put("updated_at", 5)
        })
        raw.insertOrThrow("participants", null, ContentValues().apply {
            put("conversation_id", CONV); put("participant_id", "+15550000002"); put("name", "Bob"); put("number", "+15550000002")
        })
        for ((id, ts, text) in listOf(Triple("imsg:M1", 1_000L, "first"), Triple("imsg:M2", 2_000L, "second"))) {
            val row = raw.insertOrThrow("messages", null, ContentValues().apply {
                put("message_id", id); put("conversation_id", CONV); put("timestamp_ms", ts); put("text_body", text); put("updated_at", 5)
                if (version >= 3) put("extra_json", "{\"effect\":\"x\"}")
            })
            raw.insertOrThrow("messages_fts", null, ContentValues().apply {
                put("docid", row); put("text_body", text); put("message_id", id); put("conversation_id", CONV)
            })
        }
        raw.insertOrThrow("media", null, ContentValues().apply {
            put("message_id", "imsg:M2"); put("part_id", "p1"); put("media_id", "M2/p1"); put("mime", "image/jpeg")
        })
        raw.version = version
        raw.close()
    }

    @Test
    fun everyEarlierArchiveIsUpgradedInOneStepAndKeepsItsRows() {
        for (version in 1..3) {
            db?.close()
            context.deleteDatabase(DB_NAME)
            writeOld(version)
            val repo = open()
            val c = repo.conversation(CONV)
            assertNotNull("v$version", c)
            assertEquals("Bob", c!!.name)
            assertTrue(c.unread)
            assertTrue(c.pinned)
            assertEquals("second", c.snippet)
            assertEquals(0L, c.deletedAt)
            assertEquals(0L, c.firstUnreadTs)
            assertEquals(Folder.DELETED, repo.conversation(OTHER)!!.folderEnum)
            assertEquals(listOf("imsg:M1", "imsg:M2"), repo.messages(CONV).map { it.id })
            assertEquals(if (version >= 3) "{\"effect\":\"x\"}" else "", repo.message("imsg:M1")!!.extra)
            assertNotNull(repo.mediaPart("imsg:M2", "p1"))
            assertEquals(listOf("imsg:M1"), repo.search("first").map { it.messageId })
            assertEquals("Bob", repo.participants(CONV).single().name)
            // The new columns and table are in use at once.
            assertTrue(repo.moveToDeleted(CONV, 7_000))
            assertEquals(7_000L, repo.conversation(CONV)!!.deletedAt)
            repo.applyDeletion("imsg:M1")
            assertTrue(repo.isTombstoned("imsg:M1"))
            assertEquals(4, db!!.readableDatabase.version)
        }
    }

    @Test
    fun anUpgradeCutShortIsFinishedWithoutAddingAnythingTwice() {
        writeOld(3)
        open().conversation(CONV)
        val raw = db!!.writableDatabase
        db!!.onUpgrade(raw, 3, ArchiveDb.VERSION)
        db!!.onUpgrade(raw, 1, ArchiveDb.VERSION)
        val columns = raw.rawQuery("PRAGMA table_info(conversations)", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(1)) } }
        assertEquals(1, columns.count { it == "deleted_at" })
        assertEquals(1, columns.count { it == "first_unread_ts" })
        assertEquals("Bob", repo.conversation(CONV)!!.name)
    }

    @Test
    fun aNewArchiveHasTheVersion4Schema() {
        val repo = open()
        assertEquals(4, ArchiveDb.VERSION)
        val sql = db!!.readableDatabase.rawQuery("SELECT sql FROM sqlite_master WHERE name = 'conversations'", null).use { c -> c.moveToFirst(); c.getString(0) }
        assertTrue(sql, "deleted_at INTEGER NOT NULL DEFAULT 0" in sql && "first_unread_ts INTEGER NOT NULL DEFAULT 0" in sql)
        assertEquals(0, repo.tombstoneCount())
    }

    // --- Recently Deleted ------------------------------------------------------------------------------

    @Test
    fun recentlyDeletedKeepsItsTimeAndRetentionTakesTheOldOnes() {
        val repo = open()
        val day = 24L * 60 * 60 * 1000
        val now = 100 * day
        repo.upsertMessage(message("imsg:A1", CONV))
        repo.upsertMessage(message("imsg:B1", OTHER))
        repo.upsertMessage(message("imsg:C1", THIRD))
        assertTrue(repo.moveToDeleted(CONV, now - 31 * day))
        assertTrue(repo.moveToDeleted(OTHER, now - 29 * day))
        // Already there: its time stays.
        assertFalse(repo.moveToDeleted(CONV, now))
        assertEquals(now - 31 * day, repo.conversation(CONV)!!.deletedAt)
        // Only the one past its 30 days goes, with its messages.
        assertEquals(listOf(CONV), repo.expiredDeleted(now - ArchiveRepository.RETENTION_MS))
        assertEquals(1, repo.purgeExpired(now - ArchiveRepository.RETENTION_MS))
        assertNull(repo.conversation(CONV))
        assertNull(repo.message("imsg:A1"))
        assertNotNull(repo.conversation(OTHER))
        assertNotNull(repo.conversation(THIRD))
        // Its messages' ids are kept as deleted.
        assertTrue(repo.isTombstoned("imsg:A1"))
        // Recovered: out of Recently Deleted, its time gone.
        assertTrue(repo.restoreDeleted(OTHER))
        assertEquals(Folder.INBOX, repo.conversation(OTHER)!!.folderEnum)
        assertEquals(0L, repo.conversation(OTHER)!!.deletedAt)
        // One there with no time known is kept its full period from now.
        repo.setConversationFolder(THIRD, Folder.DELETED)
        assertTrue(repo.expiredDeleted(Long.MAX_VALUE).contains(THIRD))
        assertTrue(repo.conversation(THIRD)!!.deletedAt > 0)
        assertTrue(repo.expiredDeleted(now).isEmpty())
        // A time set for it (a remote purge held back a day).
        assertTrue(repo.setDeletedAt(THIRD, 5))
        assertFalse(repo.setDeletedAt(OTHER, 5))
    }

    // --- first unread ------------------------------------------------------------------------------

    @Test
    fun firstUnreadIsSetAsAChatBecomesUnreadKeptWhileUnreadAndClearedOnRead() {
        val repo = open()
        repo.upsertConversation(ConversationDto(id = CONV, unread = false))
        assertEquals(0L, repo.conversation(CONV)!!.firstUnreadTs)
        repo.upsertConversation(ConversationDto(id = CONV, unread = true), unreadSinceMs = 5_000)
        assertEquals(5_000L, repo.conversation(CONV)!!.firstUnreadTs)
        // Still unread: a later message does not move it.
        repo.upsertConversation(ConversationDto(id = CONV, unread = true), unreadSinceMs = 9_000)
        assertEquals(5_000L, repo.conversation(CONV)!!.firstUnreadTs)
        repo.markConversationRead(CONV)
        assertEquals(0L, repo.conversation(CONV)!!.firstUnreadTs)
        assertFalse(repo.conversation(CONV)!!.unread)
        // The phone's word (Google Messages): its latest message's time.
        repo.upsertConversation(ConversationDto(id = CONV, unread = true, lastMessageTs = 12_000))
        assertEquals(12_000L, repo.conversation(CONV)!!.firstUnreadTs)
        repo.upsertConversation(ConversationDto(id = CONV, unread = false))
        assertEquals(0L, repo.conversation(CONV)!!.firstUnreadTs)
        // "Mark as Unread" names no message: none.
        assertTrue(repo.markConversationUnread(CONV))
        assertEquals(0L, repo.conversation(CONV)!!.firstUnreadTs)
        repo.upsertConversation(ConversationDto(id = CONV, unread = true), unreadSinceMs = 20_000)
        assertEquals(0L, repo.conversation(CONV)!!.firstUnreadTs)
        // A new chat that starts unread.
        repo.upsertConversation(ConversationDto(id = OTHER, unread = true), unreadSinceMs = 30_000)
        assertEquals(30_000L, repo.conversation(OTHER)!!.firstUnreadTs)
    }

    @Test
    fun anImessageChatInRecentlyDeletedIsStillUpdatedThereButAPhoneTrashedOneIsNot() {
        val repo = open()
        val people = listOf(ParticipantDto(id = "a", number = "+15550000002"))
        repo.upsertConversation(ConversationDto(id = CONV, name = "Old", isGroup = true, participants = people))
        assertTrue(repo.moveToDeleted(CONV, 4_000))
        repo.upsertConversation(
            ConversationDto(
                id = CONV, name = "New", isGroup = true, readOnly = true, folder = Folder.DELETED.wire,
                participants = people + ParticipantDto(id = "b", number = "+15550000003"),
            ),
        )
        val row = repo.conversation(CONV)!!
        assertEquals(Folder.DELETED, row.folderEnum)
        assertEquals(4_000L, row.deletedAt)
        assertEquals("New", row.name)
        assertTrue(row.readOnly)
        assertEquals(setOf("a", "b"), repo.participants(CONV).map { it.id }.toSet())

        // Google Messages' phone-trashed conversation: moved there with all else as it was.
        db?.close()
        context.deleteDatabase(DB_NAME)
        db = ArchiveDb(context, DB_NAME)
        val messages = ArchiveRepository.forTesting(db!!, ChatService.MESSAGES)
        messages.upsertConversation(ConversationDto(id = CONV, name = "Old", participants = people))
        messages.upsertConversation(ConversationDto(id = CONV, name = "New", folder = Folder.DELETED.wire))
        assertEquals(Folder.DELETED, messages.conversation(CONV)!!.folderEnum)
        messages.upsertConversation(ConversationDto(id = CONV, name = "Newer", folder = Folder.DELETED.wire))
        assertEquals("Old", messages.conversation(CONV)!!.name)
        assertEquals(listOf("a"), messages.participants(CONV).map { it.id })
    }

    // --- deleted ids ------------------------------------------------------------------------------

    @Test
    fun deletedImessageIdsAreKeptCappedAndPruned() {
        val repo = open()
        val day = 24L * 60 * 60 * 1000
        repo.upsertMessage(message("imsg:D1"))
        repo.upsertMessage(message("tmp:local"))
        repo.applyDeletion("imsg:D1")
        repo.applyDeletion("tmp:local")
        assertTrue(repo.isTombstoned("imsg:D1"))
        // Only iMessage's own ids are kept.
        assertFalse(repo.isTombstoned("tmp:local"))
        repo.tombstone(listOf("12345", "x".repeat(200)))
        assertEquals(1, repo.tombstoneCount())
        // At most MAX_TOMBSTONES: the oldest go.
        val now = System.currentTimeMillis() + 1_000
        repo.tombstone((1..ArchiveRepository.MAX_TOMBSTONES + 10).map { "imsg:T$it" }, now - 10 * day)
        repo.tombstone(listOf("imsg:NEW"), now)
        assertEquals(ArchiveRepository.MAX_TOMBSTONES, repo.tombstoneCount())
        assertTrue(repo.isTombstoned("imsg:NEW"))
        // Older than TOMBSTONE_DAYS: forgotten on start.
        repo.pruneTombstones(now + (ArchiveRepository.TOMBSTONE_DAYS - 5) * day)
        assertTrue(repo.isTombstoned("imsg:NEW"))
        assertTrue(repo.isTombstoned("imsg:D1"))
        assertEquals(2, repo.tombstoneCount())
        repo.pruneTombstones(now + (ArchiveRepository.TOMBSTONE_DAYS + 1) * day)
        assertEquals(0, repo.tombstoneCount())
    }

    @Test
    fun aChatDeletedForGoodKeepsItsNewestIdsAsDeleted() {
        val repo = open()
        for (i in 1..5) repo.upsertMessage(message("imsg:P$i", ts = i * 1_000L))
        repo.moveToDeleted(CONV, 1)
        assertEquals(1, repo.purgeDeleted(CONV))
        assertTrue((1..5).all { repo.isTombstoned("imsg:P$it") })
        // A conversation not in Recently Deleted is not purged.
        repo.upsertMessage(message("imsg:Q1", OTHER))
        assertEquals(0, repo.purgeDeleted(OTHER))
        assertNotNull(repo.message("imsg:Q1"))
    }

    private companion object {
        const val DB_NAME = "archive-v4-test.db"
        const val CONV = "imsg:tel:+15550000002"
        const val OTHER = "imsg:tel:+15550000003"
        const val THIRD = "imsg:tel:+15550000004"

        /** Version 3's tables, as ArchiveDb made them before version 4. */
        val V3_SCHEMA = listOf(
            """CREATE TABLE conversations (
                conversation_id TEXT PRIMARY KEY,
                name TEXT NOT NULL DEFAULT '',
                is_group INTEGER NOT NULL DEFAULT 0,
                is_rcs INTEGER NOT NULL DEFAULT 0,
                folder TEXT NOT NULL DEFAULT 'inbox',
                status_code INTEGER NOT NULL DEFAULT 0,
                unread INTEGER NOT NULL DEFAULT 0,
                pinned INTEGER NOT NULL DEFAULT 0,
                read_only INTEGER NOT NULL DEFAULT 0,
                muted INTEGER NOT NULL DEFAULT 0,
                last_message_ts INTEGER NOT NULL DEFAULT 0,
                latest_message_id TEXT NOT NULL DEFAULT '',
                snippet TEXT NOT NULL DEFAULT '',
                snippet_from_me INTEGER NOT NULL DEFAULT 0,
                snippet_sender TEXT NOT NULL DEFAULT '',
                avatar_color TEXT NOT NULL DEFAULT '',
                default_outgoing_id TEXT NOT NULL DEFAULT '',
                raw_json TEXT,
                updated_at INTEGER NOT NULL
            )""",
            "CREATE INDEX idx_conversations_folder ON conversations(folder, pinned, last_message_ts)",
            """CREATE TABLE participants (
                conversation_id TEXT NOT NULL REFERENCES conversations(conversation_id) ON DELETE CASCADE,
                participant_id TEXT NOT NULL,
                name TEXT NOT NULL DEFAULT '',
                first_name TEXT NOT NULL DEFAULT '',
                number TEXT NOT NULL DEFAULT '',
                formatted_number TEXT NOT NULL DEFAULT '',
                is_me INTEGER NOT NULL DEFAULT 0,
                color TEXT NOT NULL DEFAULT '',
                contact_id TEXT NOT NULL DEFAULT '',
                visible INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY (conversation_id, participant_id)
            )""",
            """CREATE TABLE contacts (
                contact_key TEXT PRIMARY KEY,
                participant_id TEXT NOT NULL DEFAULT '',
                contact_id TEXT NOT NULL DEFAULT '',
                phone_number TEXT NOT NULL DEFAULT '',
                formatted_number TEXT NOT NULL DEFAULT '',
                display_name TEXT NOT NULL DEFAULT '',
                custom_alias TEXT NOT NULL DEFAULT '',
                avatar_path TEXT NOT NULL DEFAULT '',
                color TEXT NOT NULL DEFAULT '',
                updated_at INTEGER NOT NULL DEFAULT 0
            )""",
            """CREATE TABLE messages (
                message_id TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL REFERENCES conversations(conversation_id) ON DELETE CASCADE,
                tmp_id TEXT NOT NULL DEFAULT '',
                sender_id TEXT NOT NULL DEFAULT '',
                sender_name TEXT NOT NULL DEFAULT '',
                timestamp_ms INTEGER NOT NULL,
                is_outgoing INTEGER NOT NULL DEFAULT 0,
                direction TEXT NOT NULL DEFAULT 'in',
                message_status TEXT NOT NULL DEFAULT '',
                status_code INTEGER NOT NULL DEFAULT 0,
                error_text TEXT NOT NULL DEFAULT '',
                transport_type TEXT NOT NULL DEFAULT '',
                text_body TEXT NOT NULL DEFAULT '',
                subject TEXT NOT NULL DEFAULT '',
                reply_to_msg_id TEXT NOT NULL DEFAULT '',
                reactions_json TEXT NOT NULL DEFAULT '[]',
                local_state TEXT NOT NULL DEFAULT 'synced',
                raw_json TEXT,
                updated_at INTEGER NOT NULL,
                edited_at INTEGER NOT NULL DEFAULT 0,
                extra_json TEXT NOT NULL DEFAULT ''
            )""",
            "CREATE INDEX idx_messages_conv_ts ON messages(conversation_id, timestamp_ms DESC)",
            "CREATE INDEX idx_messages_tmp ON messages(tmp_id)",
            """CREATE TABLE media (
                message_id TEXT NOT NULL REFERENCES messages(message_id) ON DELETE CASCADE,
                part_id TEXT NOT NULL,
                media_id TEXT NOT NULL DEFAULT '',
                decryption_key BLOB,
                thumb_media_id TEXT NOT NULL DEFAULT '',
                thumb_key BLOB,
                mime TEXT NOT NULL DEFAULT '',
                file_name TEXT NOT NULL DEFAULT '',
                size INTEGER NOT NULL DEFAULT 0,
                width INTEGER NOT NULL DEFAULT 0,
                height INTEGER NOT NULL DEFAULT 0,
                inline_data BLOB,
                needs_full_size INTEGER NOT NULL DEFAULT 0,
                local_path TEXT NOT NULL DEFAULT '',
                thumb_path TEXT NOT NULL DEFAULT '',
                full_size_requested INTEGER NOT NULL DEFAULT 0,
                sticker_checked INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (message_id, part_id)
            )""",
            """CREATE VIRTUAL TABLE messages_fts USING fts4(
                text_body, message_id, conversation_id,
                notindexed=message_id, notindexed=conversation_id, tokenize=unicode61
            )""",
            """CREATE TABLE outbox_queue (
                idempotency_key TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                text_body TEXT NOT NULL DEFAULT '',
                media_local_path TEXT NOT NULL DEFAULT '',
                media_mime TEXT NOT NULL DEFAULT '',
                media_name TEXT NOT NULL DEFAULT '',
                reply_to_id TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'PENDING',
                attempts INTEGER NOT NULL DEFAULT 0,
                last_error TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )""",
            """CREATE TABLE connection_health (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                status TEXT NOT NULL DEFAULT 'unknown',
                last_event_ts INTEGER NOT NULL DEFAULT 0,
                last_token_refresh INTEGER NOT NULL DEFAULT 0,
                last_error TEXT NOT NULL DEFAULT '',
                account TEXT NOT NULL DEFAULT '',
                phone_id TEXT NOT NULL DEFAULT ''
            )""",
            """CREATE TABLE sync_state (
                conversation_id TEXT PRIMARY KEY REFERENCES conversations(conversation_id) ON DELETE CASCADE,
                cursor_id TEXT NOT NULL DEFAULT '',
                cursor_ts INTEGER NOT NULL DEFAULT 0,
                history_complete INTEGER NOT NULL DEFAULT 0,
                last_backfill_at INTEGER NOT NULL DEFAULT 0
            )""",
        )

        /** Version 1's tables (version 2 added only the media indexes). */
        val V1_SCHEMA = listOf(
            """CREATE TABLE conversations (
                conversation_id TEXT PRIMARY KEY,
                name TEXT NOT NULL DEFAULT '',
                is_group INTEGER NOT NULL DEFAULT 0,
                is_rcs INTEGER NOT NULL DEFAULT 0,
                folder TEXT NOT NULL DEFAULT 'inbox',
                status_code INTEGER NOT NULL DEFAULT 0,
                unread INTEGER NOT NULL DEFAULT 0,
                pinned INTEGER NOT NULL DEFAULT 0,
                read_only INTEGER NOT NULL DEFAULT 0,
                muted INTEGER NOT NULL DEFAULT 0,
                last_message_ts INTEGER NOT NULL DEFAULT 0,
                latest_message_id TEXT NOT NULL DEFAULT '',
                snippet TEXT NOT NULL DEFAULT '',
                snippet_from_me INTEGER NOT NULL DEFAULT 0,
                snippet_sender TEXT NOT NULL DEFAULT '',
                avatar_color TEXT NOT NULL DEFAULT '',
                default_outgoing_id TEXT NOT NULL DEFAULT '',
                raw_json TEXT,
                updated_at INTEGER NOT NULL
            )""",
            "CREATE INDEX idx_conversations_folder ON conversations(folder, pinned, last_message_ts)",
            """CREATE TABLE participants (
                conversation_id TEXT NOT NULL REFERENCES conversations(conversation_id) ON DELETE CASCADE,
                participant_id TEXT NOT NULL,
                name TEXT NOT NULL DEFAULT '',
                first_name TEXT NOT NULL DEFAULT '',
                number TEXT NOT NULL DEFAULT '',
                formatted_number TEXT NOT NULL DEFAULT '',
                is_me INTEGER NOT NULL DEFAULT 0,
                color TEXT NOT NULL DEFAULT '',
                contact_id TEXT NOT NULL DEFAULT '',
                visible INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY (conversation_id, participant_id)
            )""",
            """CREATE TABLE contacts (
                contact_key TEXT PRIMARY KEY,
                participant_id TEXT NOT NULL DEFAULT '',
                contact_id TEXT NOT NULL DEFAULT '',
                phone_number TEXT NOT NULL DEFAULT '',
                formatted_number TEXT NOT NULL DEFAULT '',
                display_name TEXT NOT NULL DEFAULT '',
                custom_alias TEXT NOT NULL DEFAULT '',
                avatar_path TEXT NOT NULL DEFAULT '',
                color TEXT NOT NULL DEFAULT '',
                updated_at INTEGER NOT NULL DEFAULT 0
            )""",
            """CREATE TABLE messages (
                message_id TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL REFERENCES conversations(conversation_id) ON DELETE CASCADE,
                tmp_id TEXT NOT NULL DEFAULT '',
                sender_id TEXT NOT NULL DEFAULT '',
                sender_name TEXT NOT NULL DEFAULT '',
                timestamp_ms INTEGER NOT NULL,
                is_outgoing INTEGER NOT NULL DEFAULT 0,
                direction TEXT NOT NULL DEFAULT 'in',
                message_status TEXT NOT NULL DEFAULT '',
                status_code INTEGER NOT NULL DEFAULT 0,
                error_text TEXT NOT NULL DEFAULT '',
                transport_type TEXT NOT NULL DEFAULT '',
                text_body TEXT NOT NULL DEFAULT '',
                subject TEXT NOT NULL DEFAULT '',
                reply_to_msg_id TEXT NOT NULL DEFAULT '',
                reactions_json TEXT NOT NULL DEFAULT '[]',
                local_state TEXT NOT NULL DEFAULT 'synced',
                raw_json TEXT,
                updated_at INTEGER NOT NULL
            )""",
            "CREATE INDEX idx_messages_conv_ts ON messages(conversation_id, timestamp_ms DESC)",
            "CREATE INDEX idx_messages_tmp ON messages(tmp_id)",
            """CREATE TABLE media (
                message_id TEXT NOT NULL REFERENCES messages(message_id) ON DELETE CASCADE,
                part_id TEXT NOT NULL,
                media_id TEXT NOT NULL DEFAULT '',
                decryption_key BLOB,
                thumb_media_id TEXT NOT NULL DEFAULT '',
                thumb_key BLOB,
                mime TEXT NOT NULL DEFAULT '',
                file_name TEXT NOT NULL DEFAULT '',
                size INTEGER NOT NULL DEFAULT 0,
                width INTEGER NOT NULL DEFAULT 0,
                height INTEGER NOT NULL DEFAULT 0,
                inline_data BLOB,
                needs_full_size INTEGER NOT NULL DEFAULT 0,
                local_path TEXT NOT NULL DEFAULT '',
                thumb_path TEXT NOT NULL DEFAULT '',
                full_size_requested INTEGER NOT NULL DEFAULT 0,
                sticker_checked INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (message_id, part_id)
            )""",
            """CREATE VIRTUAL TABLE messages_fts USING fts4(
                text_body, message_id, conversation_id,
                notindexed=message_id, notindexed=conversation_id, tokenize=unicode61
            )""",
            """CREATE TABLE outbox_queue (
                idempotency_key TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                text_body TEXT NOT NULL DEFAULT '',
                media_local_path TEXT NOT NULL DEFAULT '',
                media_mime TEXT NOT NULL DEFAULT '',
                media_name TEXT NOT NULL DEFAULT '',
                reply_to_id TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'PENDING',
                attempts INTEGER NOT NULL DEFAULT 0,
                last_error TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )""",
            """CREATE TABLE connection_health (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                status TEXT NOT NULL DEFAULT 'unknown',
                last_event_ts INTEGER NOT NULL DEFAULT 0,
                last_token_refresh INTEGER NOT NULL DEFAULT 0,
                last_error TEXT NOT NULL DEFAULT '',
                account TEXT NOT NULL DEFAULT '',
                phone_id TEXT NOT NULL DEFAULT ''
            )""",
            """CREATE TABLE sync_state (
                conversation_id TEXT PRIMARY KEY REFERENCES conversations(conversation_id) ON DELETE CASCADE,
                cursor_id TEXT NOT NULL DEFAULT '',
                cursor_ts INTEGER NOT NULL DEFAULT 0,
                history_complete INTEGER NOT NULL DEFAULT 0,
                last_backfill_at INTEGER NOT NULL DEFAULT 0
            )""",
        )
    }
}
