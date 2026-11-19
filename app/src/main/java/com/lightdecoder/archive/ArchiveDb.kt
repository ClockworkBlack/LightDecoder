package com.lightdecoder.archive

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lightdecoder.chat.ChatService

/**
 * The local message archive: app-private SQLite, never backed up or synced.
 *
 * Each messaging service has a database file of its own ([nameFor]) with
 * this same schema, so the conversation and message ids of one service never
 * meet the other's.
 *
 * WAL lets the UI read while the relay writes. Full-text search uses FTS4
 * (FTS5 is not in every Android build); its rows are kept in step by
 * [ArchiveRepository] rather than by triggers, keyed by the message's rowid.
 *
 * @param name the file name, or null for an in-memory database (tests)
 */
class ArchiveDb(context: Context, name: String? = NAME) :
    SQLiteOpenHelper(context.applicationContext, name, null, VERSION) {

    val appContext: Context = context.applicationContext

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        SCHEMA.forEach(db::execSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 2: indexes on the media paths, for the check whether a file is still used.
        if (oldVersion < 2) MEDIA_PATH_INDEXES.forEach(db::execSQL)
        // Version 3: when a message was last edited, and a service's own display data.
        if (oldVersion < 3) addMessageColumns(db)
    }

    /**
     * The version 3 columns of messages that are not there yet (an upgrade that was cut short
     * may have added one of them already).
     */
    private fun addMessageColumns(db: SQLiteDatabase) {
        val have = db.rawQuery("PRAGMA table_info(messages)", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }
        for ((column, sql) in MESSAGE_V3_COLUMNS) if (column !in have) db.execSQL(sql)
    }

    companion object {
        /** The Google Messages archive (the only one there was before WhatsApp). */
        const val NAME = "relay-archive.db"

        /** The WhatsApp archive. */
        const val NAME_WHATSAPP = "whatsapp-archive.db"
        const val NAME_GMAIL = "gmail-archive.db"
        const val NAME_IMESSAGE = "imessage-archive.db"
        const val VERSION = 3

        /** The database file of [service]'s archive. */
        fun nameFor(service: ChatService): String = when (service) {
            ChatService.MESSAGES -> NAME
            ChatService.WHATSAPP -> NAME_WHATSAPP
            ChatService.GMAIL -> NAME_GMAIL
            ChatService.IMESSAGE -> NAME_IMESSAGE
        }

        /**
         * Version 2. Whether a media file is still used is asked of both path
         * columns ("local_path = ? OR thumb_path = ?") for every file that may
         * have become unused; without these each such question scanned the
         * whole media table.
         */
        private val MEDIA_PATH_INDEXES = listOf(
            "CREATE INDEX IF NOT EXISTS idx_media_local_path ON media(local_path)",
            "CREATE INDEX IF NOT EXISTS idx_media_thumb_path ON media(thumb_path)",
        )

        /**
         * Version 3, on messages: `edited_at`, when the sender last edited it (ms; 0 = never), and
         * `extra_json`, display data only one service has (iMessage: [com.lightdecoder.imessage.direct.IMessageExtra]).
         * A new archive has them from the start ([BASE_SCHEMA]); an older one gets them on upgrade.
         */
        private val MESSAGE_V3_COLUMNS = listOf(
            "edited_at" to "ALTER TABLE messages ADD COLUMN edited_at INTEGER NOT NULL DEFAULT 0",
            "extra_json" to "ALTER TABLE messages ADD COLUMN extra_json TEXT NOT NULL DEFAULT ''",
        )

        /** A new archive, as of [VERSION]. */
        private val SCHEMA get() = BASE_SCHEMA + MEDIA_PATH_INDEXES

        /** Version 1's tables, with the columns version 3 added to messages ([MESSAGE_V3_COLUMNS]). */
        private val BASE_SCHEMA = listOf(
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
    }
}
