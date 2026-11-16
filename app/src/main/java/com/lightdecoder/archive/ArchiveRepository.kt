package com.lightdecoder.archive

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.lightdecoder.archive.ArchiveChange.Kind
import com.lightdecoder.relay.ContactDto
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.Folder
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.PartDto
import com.lightdecoder.relay.ProtocolSwitchSeed
import com.lightdecoder.relay.ReactionDto
import org.json.JSONArray
import org.json.JSONException
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import android.database.Cursor as DbCursor

/**
 * The one way in and out of the local message archive.
 *
 * Every method blocks, so call it off the main thread. It is safe from any
 * thread: each write runs in one transaction, and WAL lets reads go on while
 * a write is in progress. After a write commits, the change is announced
 * through [ArchiveEvents], and media files nothing points to any more are
 * deleted.
 *
 * Messages are upserted, because the phone sends the same message again
 * whenever its status, reactions or media change. The rules are in
 * [upsertMessage].
 */
class ArchiveRepository private constructor(private val helper: ArchiveDb) {

    private val filesDir: File get() = helper.appContext.filesDir

    /** When this instance last looked for partial files left by writes that never finished. */
    private val lastPartSweep = AtomicLong(0)

    /**
     * Held while a media file is written and its path recorded
     * ([withMediaFileLock]), and while files that may have become unused are
     * checked once more and deleted after a write. A download that lands on
     * the same path as a file being cleaned up (the same media fetched
     * again) therefore either comes after the deletion or is seen as used.
     */
    private val mediaFileLock = Any()

    /** What one upsert did. [newMediaParts] are the media parts worth downloading now. */
    data class UpsertResult(val inserted: Boolean, val replacedTmpId: String?, val newMediaParts: List<MediaRow>)

    // --- conversations ----------------------------------------------------------

    /** Pinned first, then newest first. A null [folder] lists every folder. */
    fun conversations(folder: Folder? = Folder.INBOX, limit: Int = 500): List<ConversationRow> {
        val where = if (folder == null) "" else "WHERE folder = ?"
        val args = if (folder == null) emptyArray() else arrayOf(folder.wire)
        return query(
            "SELECT * FROM conversations $where ORDER BY pinned DESC, last_message_ts DESC LIMIT ${limit.coerceAtLeast(0)}",
            args,
        ) { it.toConversation() }
    }

    fun conversation(id: String): ConversationRow? =
        query("SELECT * FROM conversations WHERE conversation_id = ?", arrayOf(id)) { it.toConversation() }.firstOrNull()

    fun participants(conversationId: String): List<ParticipantRow> =
        query("SELECT * FROM participants WHERE conversation_id = ? ORDER BY rowid", arrayOf(conversationId)) {
            it.toParticipant()
        }

    /**
     * Stores what the phone says about a conversation and replaces its
     * participants. The phone is authoritative, except that a zero
     * lastMessageTs leaves the stored latest message alone, and mute (which
     * the phone does not report) is only ever turned on from here.
     * A conversation the phone reports as deleted (or trashed) is not
     * deleted here: it moves to the local [Folder.DELETED] folder with its
     * messages, until the user restores ([restoreDeleted]) or purges it
     * ([purgeDeleted]).
     */
    fun upsertConversation(dto: ConversationDto) = write { writeConversation(it, dto) }

    /** Like [upsertConversation] for many, in one transaction. */
    fun upsertConversations(dtos: List<ConversationDto>) = write { b -> dtos.forEach { writeConversation(b, it) } }

    /** Number of unread conversations in the inbox. */
    fun unreadCount(): Int =
        query("SELECT COUNT(*) FROM conversations WHERE unread = 1 AND folder = ?", arrayOf(Folder.INBOX.wire)) {
            it.getInt(0)
        }.firstOrNull() ?: 0

    /** Clears the local unread flag (the phone is told separately). */
    fun markConversationRead(conversationId: String) = write { b ->
        b.db.update("conversations", ContentValues().apply { put("unread", 0) }, "conversation_id = ?", arrayOf(conversationId))
        b.changed(Kind.CONVERSATIONS, conversationId)
    }

    fun setConversationFolder(conversationId: String, folder: Folder) = write { b ->
        b.db.update("conversations", ContentValues().apply { put("folder", folder.wire) }, "conversation_id = ?", arrayOf(conversationId))
        b.changed(Kind.CONVERSATIONS, conversationId)
    }

    /** The phone does not report mute, so the app keeps its own record of it. */
    fun setConversationMuted(conversationId: String, muted: Boolean) = write { b ->
        b.db.update("conversations", ContentValues().apply { put("muted", muted.toInt()) }, "conversation_id = ?", arrayOf(conversationId))
        b.changed(Kind.CONVERSATIONS, conversationId)
    }

    /** Deletes a conversation with its messages, media files, history cursor and queued sends. */
    fun deleteConversationLocal(conversationId: String) = write { deleteConversation(it, conversationId) }

    /**
     * Moves a conversation the phone deleted back to the inbox, here only.
     * False if it is not in [Folder.DELETED].
     */
    fun restoreDeleted(conversationId: String): Boolean = write { b ->
        val moved = b.db.update(
            "conversations", ContentValues().apply { put("folder", Folder.INBOX.wire) },
            "conversation_id = ? AND folder = ?", arrayOf(conversationId, Folder.DELETED.wire),
        ) > 0
        if (moved) b.changed(Kind.CONVERSATIONS, conversationId)
        moved
    }

    /**
     * Deletes for good, as [deleteConversationLocal] does, the conversation
     * [conversationId] if it is in [Folder.DELETED], or with null every
     * conversation there. Returns how many were deleted.
     */
    fun purgeDeleted(conversationId: String?): Int = write { b ->
        val ids = if (conversationId == null) {
            query(b.db, "SELECT conversation_id FROM conversations WHERE folder = ?", arrayOf(Folder.DELETED.wire)) { it.getString(0) }
        } else {
            query(
                b.db, "SELECT conversation_id FROM conversations WHERE conversation_id = ? AND folder = ?",
                arrayOf(conversationId, Folder.DELETED.wire),
            ) { it.getString(0) }
        }
        ids.forEach { deleteConversation(b, it) }
        ids.size
    }

    // --- messages ---------------------------------------------------------------

    /** Up to [limit] messages older than [beforeTs], returned oldest first. */
    fun messages(conversationId: String, beforeTs: Long = Long.MAX_VALUE, limit: Int = 100): List<MessageRow> {
        val rows = query(
            "SELECT * FROM messages WHERE conversation_id = ? AND timestamp_ms < $beforeTs " +
                "ORDER BY timestamp_ms DESC, rowid DESC LIMIT ${limit.coerceAtLeast(0)}",
            arrayOf(conversationId),
        ) { it.toMessage() }
        return withMedia(rows).reversed()
    }

    fun message(id: String): MessageRow? =
        withMedia(query("SELECT * FROM messages WHERE message_id = ?", arrayOf(id)) { it.toMessage() }).firstOrNull()

    /**
     * Per conversation, the timestamp of the newest message from the phone
     * (local copies of sends not counted); conversations with none are left
     * out. One indexed look per conversation, so cheap enough to take on
     * every connection.
     */
    fun newestStoredTimestamps(): Map<String, Long> = query(
        "SELECT c.conversation_id, (SELECT m.timestamp_ms FROM messages m WHERE m.conversation_id = c.conversation_id " +
            "AND substr(m.message_id, 1, ${MessageRow.TMP_PREFIX.length}) != ? ORDER BY m.timestamp_ms DESC LIMIT 1) " +
            "FROM conversations c",
        arrayOf(MessageRow.TMP_PREFIX),
    ) { c -> if (c.isNull(1)) null else c.getString(0) to c.getLong(1) }.filterNotNull().toMap()

    /**
     * Each conversation's newest protocol-switch tombstone, for
     * [com.lightdecoder.relay.RelayBridge.seedProtocolSwitches]. (SQLite takes
     * the bare columns from the row that has the MAX.)
     */
    fun protocolSwitchSeeds(): List<ProtocolSwitchSeed> = query(
        "SELECT conversation_id, message_status, status_code, MAX(timestamp_ms) FROM messages " +
            "WHERE message_status IN (${ProtocolSwitchSeed.STATUSES.joinToString { "?" }}) GROUP BY conversation_id",
        ProtocolSwitchSeed.STATUSES.toTypedArray(),
    ) { c -> ProtocolSwitchSeed(c.getString(0), c.getString(1), c.getInt(2), c.getLong(3)) }

    /** The timestamp of the newest message from the phone in one conversation, or null if there is none. */
    fun newestStoredTimestamp(conversationId: String): Long? = query(
        "SELECT timestamp_ms FROM messages WHERE conversation_id = ? AND substr(message_id, 1, ${MessageRow.TMP_PREFIX.length}) != ? " +
            "ORDER BY timestamp_ms DESC LIMIT 1",
        arrayOf(conversationId, MessageRow.TMP_PREFIX),
    ) { it.getLong(0) }.firstOrNull()

    /**
     * Stores one message from the phone, in one transaction:
     *
     *  1. If it echoes a tmpId and the local copy "tmp:<tmpId>" exists, that
     *     copy is replaced by the real message, and [UpsertResult.replacedTmpId]
     *     says so. Its outbox entry and attachment copy go too, unless the
     *     entry is SENDING (the sender is still working on it).
     *  2. A message already stored is updated in place. Media parts keep what
     *     was done with them (downloaded file, full size requested, sticker
     *     checked) as long as their media id did not change.
     *  3. A deleted message is removed, with its media files.
     *  4. An unknown conversation gets a placeholder row until the phone
     *     describes it.
     *  5. The conversation's latest-message summary moves forward if this
     *     message is newer.
     */
    fun upsertMessage(dto: MessageDto): UpsertResult = write { writeMessage(it, dto) }

    /**
     * Stores a page of messages in one transaction, by the same rules.
     * Returns how many were inserted or updated (deletions not counted).
     */
    fun upsertMessages(dtos: List<MessageDto>): Int = write { b ->
        dtos.count { writeMessage(b, it) !== SKIPPED }
    }

    /** Removes a message the phone deleted, with its search entry and media files. */
    fun applyDeletion(messageId: String) = write { deleteMessage(it, messageId) }

    /**
     * Shows a message being sent right away, as "tmp:<tmpId>" in state
     * pending, until the phone echoes the real one. A media attachment is
     * stored as part "0" pointing at [mediaPath]. Does nothing if that tmpId
     * is already there.
     */
    fun insertLocalOutgoing(
        tmpId: String,
        conversationId: String,
        text: String,
        mediaPath: String?,
        mime: String?,
        fileName: String?,
        replyToId: String?,
        nowMs: Long,
    ) = write { b ->
        val id = MessageRow.tmpMessageId(tmpId)
        if (rowIdOf(b.db, id) != null) return@write
        ensureConversation(b, conversationId)
        val values = ContentValues().apply {
            put("message_id", id)
            put("conversation_id", conversationId)
            put("tmp_id", tmpId)
            put("timestamp_ms", nowMs)
            put("is_outgoing", 1)
            put("direction", "out")
            put("text_body", text)
            put("reply_to_msg_id", replyToId.orEmpty())
            put("local_state", LocalState.PENDING)
            put("updated_at", b.now)
        }
        val rowId = b.db.insertOrThrow("messages", null, values)
        if (mediaPath != null) {
            b.db.insertOrThrow("media", null, ContentValues().apply {
                put("message_id", id)
                put("part_id", "0")
                put("mime", mime.orEmpty())
                put("file_name", fileName.orEmpty())
                put("size", File(mediaPath).length())
                put("local_path", mediaPath)
            })
        }
        indexForSearch(b.db, rowId, id, conversationId, "", text)
        advanceConversation(b, conversationId, id, nowMs, text, fromMe = true, sender = "")
        b.changed(Kind.MESSAGES, conversationId)
    }

    /**
     * Sets the local state (one of [LocalState]) of a message being sent,
     * found by its tmpId or by its message id.
     */
    fun setLocalState(tmpOrMessageId: String, state: String, errorText: String = "") = write { b ->
        val ids = arrayOf(MessageRow.tmpMessageId(tmpOrMessageId), tmpOrMessageId)
        val conversationId = query(
            b.db, "SELECT conversation_id FROM messages WHERE message_id IN (?, ?)", ids,
        ) { it.getString(0) }.firstOrNull() ?: return@write
        b.db.update("messages", ContentValues().apply {
            put("local_state", state)
            put("error_text", errorText)
            put("updated_at", b.now)
        }, "message_id IN (?, ?)", ids)
        b.changed(Kind.MESSAGES, conversationId)
    }

    // --- outbox -----------------------------------------------------------------

    /** Adds a send to the queue, replacing an entry with the same key. */
    fun enqueueOutbox(row: OutboxRow) = write { b ->
        b.db.insertWithOnConflict("outbox_queue", null, row.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
        b.changed(Kind.OUTBOX, row.conversationId)
    }

    /**
     * Sends still to do, oldest first: PENDING ones, and SENDING ones that
     * were interrupted (for example by the process dying mid-send).
     */
    fun pendingOutbox(): List<OutboxRow> = query(
        "SELECT * FROM outbox_queue WHERE status IN (?, ?) ORDER BY created_at, rowid",
        arrayOf(OutboxStatus.PENDING, OutboxStatus.SENDING),
    ) { it.toOutbox() }

    /** Keys of sends Google accepted that still wait for the phone's echo (status SENT). */
    fun sentOutboxKeys(): List<String> =
        query("SELECT idempotency_key FROM outbox_queue WHERE status = ?", arrayOf(OutboxStatus.SENT)) { it.getString(0) }

    fun outbox(key: String): OutboxRow? =
        query("SELECT * FROM outbox_queue WHERE idempotency_key = ?", arrayOf(key)) { it.toOutbox() }.firstOrNull()

    /**
     * Sets the status, adds [attemptsDelta] to the attempt count, and records [error] (content-free).
     *
     * The phone's echo often arrives before the send call returns. The echo
     * then finds the entry still SENDING and leaves it alone, so an entry
     * marked SENT after its local copy "tmp:<key>" is already gone is removed
     * here, with its attachment copy. Otherwise it would stay forever.
     */
    fun updateOutbox(key: String, status: String, attemptsDelta: Int = 0, error: String = "") = write { b ->
        val conversationId = outboxConversation(b.db, key) ?: return@write
        if (status == OutboxStatus.SENT && rowIdOf(b.db, MessageRow.tmpMessageId(key)) == null) {
            query(b.db, "SELECT media_local_path FROM outbox_queue WHERE idempotency_key = ?", arrayOf(key)) {
                b.outboxFiles.add(it.getString(0) ?: "")
            }
            b.db.delete("outbox_queue", "idempotency_key = ?", arrayOf(key))
        } else {
            b.db.execSQL(
                "UPDATE outbox_queue SET status = ?, attempts = attempts + ?, last_error = ? WHERE idempotency_key = ?",
                arrayOf<Any>(status, attemptsDelta, error, key),
            )
        }
        b.changed(Kind.OUTBOX, conversationId)
    }

    /** Removes a queue entry. Its attachment copy is the caller's to delete. */
    fun deleteOutbox(key: String) = write { b ->
        val conversationId = outboxConversation(b.db, key) ?: return@write
        b.db.delete("outbox_queue", "idempotency_key = ?", arrayOf(key))
        b.changed(Kind.OUTBOX, conversationId)
    }

    // --- media ------------------------------------------------------------------

    fun media(messageId: String): List<MediaRow> =
        query("SELECT * FROM media WHERE message_id = ? ORDER BY rowid", arrayOf(messageId)) { it.toMedia() }

    fun mediaPart(messageId: String, partId: String): MediaRow? =
        query("SELECT * FROM media WHERE message_id = ? AND part_id = ?", arrayOf(messageId, partId)) {
            it.toMedia()
        }.firstOrNull()

    /**
     * Records where a part's file (or, with [thumbnail], its thumbnail) was
     * saved, the file having been fetched for [cacheKey]: the media id (or
     * thumbnail id) it was downloaded under, or a [MediaCache.inlineKey] for
     * data that came inside the message.
     *
     * Only if the part still has that media. If the message was deleted, or
     * the part now points to other media, while the file was being fetched,
     * nothing is recorded and the file is deleted (unless another part uses
     * it), so a late download never leaves a picture on disk that nothing
     * shows or cleans up. Returns whether the path was recorded.
     */
    /**
     * Runs [block], which writes a file into the media cache and records its
     * path ([setMediaLocalPath]), so that no cleanup of unused files deletes
     * it in between. Keep it short: other writes' cleanups wait for it.
     */
    fun <T> withMediaFileLock(block: () -> T): T = synchronized(mediaFileLock) { block() }

    fun setMediaLocalPath(messageId: String, partId: String, cacheKey: String, path: String, thumbnail: Boolean): Boolean =
        when {
            cacheKey.startsWith(MediaCache.INLINE_KEY_PREFIX) ->
                recordMediaPath(messageId, partId, path, thumbnail, " AND inline_data IS NOT NULL", emptyArray())
            thumbnail -> recordMediaPath(messageId, partId, path, thumbnail = true, " AND thumb_media_id = ?", arrayOf(cacheKey))
            else -> recordMediaPath(messageId, partId, path, thumbnail = false, " AND media_id = ?", arrayOf(cacheKey))
        }

    /**
     * As the overload with a cache key, except that the part's media is not
     * compared: a part that changed media meanwhile gets this file anyway.
     * The file is still deleted if the message or part is gone.
     */
    fun setMediaLocalPath(messageId: String, partId: String, path: String, thumbnail: Boolean): Boolean =
        recordMediaPath(messageId, partId, path, thumbnail, "", emptyArray())

    fun markFullSizeRequested(messageId: String, partId: String) =
        updateMedia(messageId, partId, ContentValues().apply { put("full_size_requested", 1) })

    fun markStickerChecked(messageId: String, partId: String) =
        updateMedia(messageId, partId, ContentValues().apply { put("sticker_checked", 1) })

    // --- search -----------------------------------------------------------------

    /**
     * Full-text search over message text, newest first. Every word must
     * match; the last one also matches as a prefix ("hel" finds "hello").
     * The query is never parsed as search syntax, so any input is safe.
     */
    fun search(query: String, limit: Int = 100): List<SearchHit> {
        val match = ftsQuery(query) ?: return emptyList()
        return query(
            "SELECT m.message_id, m.conversation_id, COALESCE(c.name, ''), " +
                "snippet(messages_fts, '', '', '…', 0, 16), m.timestamp_ms " +
                "FROM messages_fts JOIN messages m ON m.rowid = messages_fts.docid " +
                "LEFT JOIN conversations c ON c.conversation_id = m.conversation_id " +
                "WHERE messages_fts MATCH ? ORDER BY m.timestamp_ms DESC LIMIT ${limit.coerceAtLeast(0)}",
            arrayOf(match),
        ) {
            SearchHit(
                messageId = it.getString(0),
                conversationId = it.getString(1),
                conversationName = it.getString(2) ?: "",
                snippet = it.getString(3) ?: "",
                timestampMs = it.getLong(4),
            )
        }
    }

    // --- contacts ---------------------------------------------------------------

    /** Stores the phone's contacts, keeping the alias and avatar set locally. */
    fun upsertContacts(contacts: List<ContactDto>) = write { b ->
        for (contact in contacts) {
            val key = contact.id.ifEmpty { if (contact.number.isEmpty()) "" else "${contact.contactId}|${contact.number}" }
            if (key.isEmpty()) continue
            val values = ContentValues().apply {
                put("participant_id", contact.id)
                put("contact_id", contact.contactId)
                put("phone_number", contact.number)
                put("formatted_number", contact.formattedNumber)
                put("display_name", contact.name)
                put("color", contact.color)
                put("updated_at", b.now)
            }
            if (b.db.update("contacts", values, "contact_key = ?", arrayOf(key)) == 0) {
                values.put("contact_key", key)
                b.db.insertOrThrow("contacts", null, values)
            }
        }
    }

    /** Contacts whose name or number contains [query] (all if blank), by name. */
    fun contacts(query: String = ""): List<ContactRow> {
        val q = query.trim()
        val sql = StringBuilder("SELECT * FROM contacts")
        val args = if (q.isEmpty()) {
            emptyArray()
        } else {
            sql.append(
                " WHERE display_name LIKE ? ESCAPE '\\' OR custom_alias LIKE ? ESCAPE '\\'" +
                    " OR phone_number LIKE ? ESCAPE '\\' OR formatted_number LIKE ? ESCAPE '\\'"
            )
            val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            arrayOf(like, like, like, like)
        }
        sql.append(" ORDER BY display_name COLLATE NOCASE, phone_number")
        return query(sql.toString(), args) { it.toContact() }
    }

    // --- health and sync state ---------------------------------------------------

    fun health(): HealthRow =
        query("SELECT * FROM connection_health WHERE id = 1", emptyArray()) {
            HealthRow(
                status = it.str("status"),
                lastEventTs = it.long("last_event_ts"),
                lastTokenRefresh = it.long("last_token_refresh"),
                lastError = it.str("last_error"),
                account = it.str("account"),
                phoneId = it.str("phone_id"),
            )
        }.firstOrNull() ?: HealthRow()

    /** Updates the fields that are not null. [lastError] must be content-free. */
    fun updateHealth(
        status: String? = null,
        lastEventTs: Long? = null,
        lastTokenRefresh: Long? = null,
        lastError: String? = null,
        account: String? = null,
        phoneId: String? = null,
    ) = write { b ->
        val values = ContentValues().apply {
            status?.let { put("status", it) }
            lastEventTs?.let { put("last_event_ts", it) }
            lastTokenRefresh?.let { put("last_token_refresh", it) }
            lastError?.let { put("last_error", it) }
            account?.let { put("account", it) }
            phoneId?.let { put("phone_id", it) }
        }
        if (values.size() == 0) return@write
        b.db.execSQL("INSERT OR IGNORE INTO connection_health (id) VALUES (1)")
        b.db.update("connection_health", values, "id = 1", null)
        b.changed(Kind.HEALTH, null)
    }

    fun syncState(conversationId: String): SyncStateRow? =
        query("SELECT * FROM sync_state WHERE conversation_id = ?", arrayOf(conversationId)) {
            SyncStateRow(
                conversationId = it.str("conversation_id"),
                cursorId = it.str("cursor_id"),
                cursorTs = it.long("cursor_ts"),
                historyComplete = it.bool("history_complete"),
                lastBackfillAt = it.long("last_backfill_at"),
            )
        }.firstOrNull()

    fun setSyncState(row: SyncStateRow) = write { b ->
        ensureConversation(b, row.conversationId)
        b.db.insertWithOnConflict("sync_state", null, ContentValues().apply {
            put("conversation_id", row.conversationId)
            put("cursor_id", row.cursorId)
            put("cursor_ts", row.cursorTs)
            put("history_complete", row.historyComplete.toInt())
            put("last_backfill_at", row.lastBackfillAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Wipes every table, and the downloaded media and queued attachments on disk. */
    fun clearAll() {
        write { b ->
            TABLES.forEach { b.db.delete(it, null, null) }
            Kind.entries.forEach { b.changed(it, null) }
        }
        MediaCache.mediaDir(filesDir).deleteRecursively()
        MediaCache.outboxDir(filesDir).deleteRecursively()
    }

    // --- writing ----------------------------------------------------------------

    /** One write transaction: what it changed, and which files it may have orphaned. */
    private class Batch(val db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        val changes = LinkedHashSet<ArchiveChange>()
        val maybeOrphaned = LinkedHashSet<String>()
        val outboxFiles = ArrayList<String>()

        fun changed(kind: Kind, conversationId: String?) {
            changes.add(ArchiveChange(conversationId, kind))
        }
    }

    private fun <T> write(block: (Batch) -> T): T {
        sweepStaleParts()
        val db = helper.writableDatabase
        val batch = Batch(db)
        val result: T
        val orphans: List<String>
        db.beginTransaction()
        try {
            result = block(batch)
            orphans = batch.maybeOrphaned.filter { mediaFileOrNull(it) != null && !isReferenced(db, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        // Only once the rows are gone for good, and checked again under the
        // media file lock: a download may have recorded the same path since.
        if (orphans.isNotEmpty()) {
            synchronized(mediaFileLock) {
                for (path in orphans) {
                    if (!isReferenced(helper.readableDatabase, path)) mediaFileOrNull(path)?.delete()
                }
            }
        }
        batch.outboxFiles.mapNotNull { outboxFileOrNull(it) }.forEach { it.delete() }
        batch.changes.forEach(ArchiveEvents::notify)
        return result
    }

    private fun writeConversation(b: Batch, dto: ConversationDto) {
        if (dto.id.isEmpty()) return
        if (dto.folder == Folder.DELETED.wire) {
            moveToDeleted(b, dto.id)
            return
        }
        val values = ContentValues().apply {
            put("name", dto.name)
            put("is_group", dto.isGroup.toInt())
            put("is_rcs", dto.isRcs.toInt())
            put("folder", dto.folder)
            put("status_code", dto.statusCode)
            put("unread", dto.unread.toInt())
            put("pinned", dto.pinned.toInt())
            put("read_only", dto.readOnly.toInt())
            if (dto.muted) put("muted", 1)
            if (dto.lastMessageTs > 0) {
                put("last_message_ts", dto.lastMessageTs)
                put("latest_message_id", dto.latestMessageId)
                put("snippet", dto.snippet)
                put("snippet_from_me", dto.snippetFromMe.toInt())
                put("snippet_sender", dto.snippetSender)
            }
            put("avatar_color", dto.avatarColor)
            put("default_outgoing_id", dto.defaultOutgoingId)
            put("raw_json", dto.toJson().toString())
            put("updated_at", b.now)
        }
        if (b.db.update("conversations", values, "conversation_id = ?", arrayOf(dto.id)) == 0) {
            values.put("conversation_id", dto.id)
            b.db.insertOrThrow("conversations", null, values)
        }
        b.db.delete("participants", "conversation_id = ?", arrayOf(dto.id))
        for (p in dto.participants) {
            if (p.id.isEmpty()) continue
            b.db.insertWithOnConflict("participants", null, ContentValues().apply {
                put("conversation_id", dto.id)
                put("participant_id", p.id)
                put("name", p.name)
                put("first_name", p.firstName)
                put("number", p.number)
                put("formatted_number", p.formattedNumber)
                put("is_me", p.isMe.toInt())
                put("color", p.color)
                put("contact_id", p.contactId)
                put("visible", p.visible.toInt())
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
        b.changed(Kind.CONVERSATIONS, dto.id)
    }

    /**
     * The phone deleted (or trashed) a conversation: it is kept here, in the
     * local deleted folder, with everything else as it was. One not stored
     * yet is not created for that.
     */
    private fun moveToDeleted(b: Batch, conversationId: String) {
        val moved = b.db.update(
            "conversations", ContentValues().apply {
                put("folder", Folder.DELETED.wire)
                put("updated_at", b.now)
            },
            "conversation_id = ? AND folder != ?", arrayOf(conversationId, Folder.DELETED.wire),
        )
        if (moved > 0) b.changed(Kind.CONVERSATIONS, conversationId)
    }

    private fun deleteConversation(b: Batch, conversationId: String) {
        val args = arrayOf(conversationId)
        query(
            b.db,
            "SELECT local_path, thumb_path FROM media WHERE message_id IN " +
                "(SELECT message_id FROM messages WHERE conversation_id = ?)",
            args,
        ) { b.maybeOrphaned.add(it.getString(0) ?: ""); b.maybeOrphaned.add(it.getString(1) ?: "") }
        query(b.db, "SELECT media_local_path FROM outbox_queue WHERE conversation_id = ?", args) {
            b.outboxFiles.add(it.getString(0) ?: "")
        }
        b.db.execSQL(
            "DELETE FROM messages_fts WHERE docid IN (SELECT rowid FROM messages WHERE conversation_id = ?)",
            args,
        )
        b.db.delete("outbox_queue", "conversation_id = ?", args)
        // Cascades to participants, messages, media and sync_state.
        if (b.db.delete("conversations", "conversation_id = ?", args) > 0) {
            b.changed(Kind.CONVERSATIONS, conversationId)
            b.changed(Kind.MESSAGES, conversationId)
        }
    }

    /** Returns [SKIPPED] for a message that was not stored (deleted or malformed). */
    private fun writeMessage(b: Batch, dto: MessageDto): UpsertResult {
        if (dto.id.isEmpty()) return SKIPPED
        // A deletion needs only the message id; it may arrive without a conversation.
        if (dto.isDeleted) {
            deleteMessage(b, dto.id)
            return SKIPPED
        }
        if (dto.conversationId.isEmpty()) return SKIPPED
        ensureConversation(b, dto.conversationId)

        var replacedTmpId: String? = null
        if (dto.tmpId.isNotEmpty()) {
            val tmpMessageId = MessageRow.tmpMessageId(dto.tmpId)
            if (tmpMessageId != dto.id && rowIdOf(b.db, tmpMessageId) != null) {
                deleteMessage(b, tmpMessageId)
                // The phone has the message, so its queue entry is done with,
                // whatever it says: one left PENDING or FAILED (say after
                // phone_not_responding, when the phone sent it later anyway)
                // would be sent a second time on retry. Only an entry being
                // sent right now stays, for the sender to finish; its
                // attachment copy may still be in use.
                val done = arrayOf(dto.tmpId, OutboxStatus.SENDING)
                query(b.db, "SELECT media_local_path FROM outbox_queue WHERE idempotency_key = ? AND status != ?", done) {
                    b.outboxFiles.add(it.getString(0) ?: "")
                }
                b.db.delete("outbox_queue", "idempotency_key = ? AND status != ?", done)
                b.db.update(
                    "conversations", ContentValues().apply { put("latest_message_id", dto.id) },
                    "conversation_id = ? AND latest_message_id = ?", arrayOf(dto.conversationId, tmpMessageId),
                )
                replacedTmpId = dto.tmpId
            }
        }

        val oldMedia = media(b.db, dto.id).associateBy { it.partId }
        val values = ContentValues().apply {
            put("conversation_id", dto.conversationId)
            put("tmp_id", dto.tmpId)
            put("sender_id", dto.senderId)
            put("sender_name", dto.senderName)
            put("timestamp_ms", dto.timestampMs)
            put("is_outgoing", dto.fromMe.toInt())
            put("direction", dto.direction)
            put("message_status", dto.status)
            put("status_code", dto.statusCode)
            put("error_text", dto.errorText)
            put("transport_type", dto.transport)
            put("text_body", dto.text)
            put("subject", dto.subject)
            put("reply_to_msg_id", dto.replyToId)
            put("reactions_json", ReactionDto.listToJson(dto.reactions).toString())
            put("local_state", LocalState.SYNCED)
            put("raw_json", dto.toJson(includeInlineData = false).toString())
            put("updated_at", b.now)
        }
        val existingRowId = rowIdOf(b.db, dto.id)
        val rowId = if (existingRowId != null) {
            b.db.update("messages", values, "message_id = ?", arrayOf(dto.id))
            existingRowId
        } else {
            values.put("message_id", dto.id)
            b.db.insertOrThrow("messages", null, values)
        }

        b.db.delete("media", "message_id = ?", arrayOf(dto.id))
        val newMedia = ArrayList<MediaRow>()
        val kept = HashSet<String>()
        for (part in dto.mediaParts) {
            val old = oldMedia[part.partId]
            val sameMedia = old != null && old.mediaId == part.mediaId
            val sameThumb = old != null && old.thumbMediaId == part.thumbMediaId
            val row = part.toMediaRow(
                messageId = dto.id,
                localPath = if (sameMedia) old!!.localPath else "",
                thumbPath = if (sameThumb) old!!.thumbPath else "",
                fullSizeRequested = sameMedia && old!!.fullSizeRequested,
                stickerChecked = sameMedia && old!!.stickerChecked,
            )
            b.db.insertWithOnConflict("media", null, row.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            kept.add(row.localPath)
            kept.add(row.thumbPath)
            val gainedInline = old != null && old.inlineData.isEmpty() && part.inlineData.isNotEmpty()
            val hasMedia = part.mediaId.isNotEmpty() || part.thumbMediaId.isNotEmpty() || part.inlineData.isNotEmpty()
            if (hasMedia && (!sameMedia || !sameThumb || gainedInline)) newMedia.add(row)
        }
        oldMedia.values.forEach { old ->
            if (old.localPath !in kept) b.maybeOrphaned.add(old.localPath)
            if (old.thumbPath !in kept) b.maybeOrphaned.add(old.thumbPath)
        }

        indexForSearch(b.db, rowId, dto.id, dto.conversationId, dto.subject, dto.text)
        advanceConversation(b, dto.conversationId, dto.id, dto.timestampMs, dto.text, dto.fromMe, dto.senderName)
        b.changed(Kind.MESSAGES, dto.conversationId)
        return UpsertResult(inserted = existingRowId == null, replacedTmpId = replacedTmpId, newMediaParts = newMedia)
    }

    private fun deleteMessage(b: Batch, messageId: String) {
        val found = query(b.db, "SELECT rowid, conversation_id FROM messages WHERE message_id = ?", arrayOf(messageId)) {
            it.getLong(0) to it.getString(1)
        }.firstOrNull() ?: return
        media(b.db, messageId).forEach {
            b.maybeOrphaned.add(it.localPath)
            b.maybeOrphaned.add(it.thumbPath)
        }
        b.db.execSQL("DELETE FROM messages_fts WHERE docid = ?", arrayOf<Any>(found.first))
        b.db.delete("messages", "message_id = ?", arrayOf(messageId)) // cascades to media
        b.changed(Kind.MESSAGES, found.second)
    }

    /** Creates a placeholder for a conversation the phone has not described yet. */
    private fun ensureConversation(b: Batch, conversationId: String) {
        val inserted = b.db.insertWithOnConflict("conversations", null, ContentValues().apply {
            put("conversation_id", conversationId)
            put("updated_at", b.now)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        if (inserted != -1L) b.changed(Kind.CONVERSATIONS, conversationId)
    }

    /** Moves the conversation's latest-message summary to this message if it is newer. */
    private fun advanceConversation(
        b: Batch,
        conversationId: String,
        messageId: String,
        timestampMs: Long,
        text: String,
        fromMe: Boolean,
        sender: String,
    ) {
        val updated = b.db.update("conversations", ContentValues().apply {
            put("last_message_ts", timestampMs)
            put("latest_message_id", messageId)
            put("snippet", text)
            put("snippet_from_me", fromMe.toInt())
            put("snippet_sender", sender)
            put("updated_at", b.now)
        }, "conversation_id = ? AND last_message_ts < ?", arrayOf(conversationId, timestampMs.toString()))
        if (updated > 0) b.changed(Kind.CONVERSATIONS, conversationId)
    }

    private fun indexForSearch(db: SQLiteDatabase, rowId: Long, messageId: String, conversationId: String, subject: String, text: String) {
        db.execSQL("DELETE FROM messages_fts WHERE docid = ?", arrayOf<Any>(rowId))
        val body = listOf(subject, text).filter { it.isNotBlank() }.joinToString("\n")
        if (body.isEmpty()) return
        db.execSQL(
            "INSERT INTO messages_fts (docid, text_body, message_id, conversation_id) VALUES (?, ?, ?, ?)",
            arrayOf<Any>(rowId, body, messageId, conversationId),
        )
    }

    /** At most once per [MediaCache.STALE_PART_MS]: partial files of downloads the app died in the middle of. */
    private fun sweepStaleParts() {
        val now = System.currentTimeMillis()
        val last = lastPartSweep.get()
        if (now - last < MediaCache.STALE_PART_MS || !lastPartSweep.compareAndSet(last, now)) return
        MediaCache.deleteStaleParts(MediaCache.mediaDir(filesDir), now)
    }

    /**
     * Sets a part's local_path (or thumb_path) to [path] where the part also
     * meets [condition]. If no part does, [path] goes to orphan cleanup; a
     * different file the part pointed to before does too.
     */
    private fun recordMediaPath(
        messageId: String,
        partId: String,
        path: String,
        thumbnail: Boolean,
        condition: String,
        conditionArgs: Array<String>,
    ): Boolean = write { b ->
        val column = if (thumbnail) "thumb_path" else "local_path"
        val where = "message_id = ? AND part_id = ?$condition"
        val args = arrayOf(messageId, partId, *conditionArgs)
        val previous = query(b.db, "SELECT $column FROM media WHERE $where", args) { it.getString(0) ?: "" }.firstOrNull()
        if (previous == null) {
            // Nothing shows this file: deleted after commit unless another part uses it.
            b.maybeOrphaned.add(path)
            return@write false
        }
        if (previous != path) b.maybeOrphaned.add(previous)
        b.db.update("media", ContentValues().apply { put(column, path) }, where, args)
        query(b.db, "SELECT conversation_id FROM messages WHERE message_id = ?", arrayOf(messageId)) {
            it.getString(0)
        }.firstOrNull()?.let { b.changed(Kind.MESSAGES, it) }
        true
    }

    private fun updateMedia(messageId: String, partId: String, values: ContentValues) = write { b ->
        if (b.db.update("media", values, "message_id = ? AND part_id = ?", arrayOf(messageId, partId)) == 0) return@write
        query(b.db, "SELECT conversation_id FROM messages WHERE message_id = ?", arrayOf(messageId)) {
            it.getString(0)
        }.firstOrNull()?.let { b.changed(Kind.MESSAGES, it) }
    }

    // --- reading helpers --------------------------------------------------------

    private fun <T> query(sql: String, args: Array<String>, map: (DbCursor) -> T): List<T> =
        query(helper.readableDatabase, sql, args, map)

    private fun <T> query(db: SQLiteDatabase, sql: String, args: Array<String>, map: (DbCursor) -> T): List<T> =
        db.rawQuery(sql, args).use { c ->
            val out = ArrayList<T>(c.count.coerceAtLeast(0))
            while (c.moveToNext()) out.add(map(c))
            out
        }

    /** Attaches each message's media parts, read in batches. */
    private fun withMedia(rows: List<MessageRow>): List<MessageRow> {
        if (rows.isEmpty()) return rows
        val byMessage = HashMap<String, MutableList<MediaRow>>()
        rows.map { it.id }.chunked(500).forEach { ids ->
            val placeholders = ids.joinToString(",") { "?" }
            query("SELECT * FROM media WHERE message_id IN ($placeholders) ORDER BY rowid", ids.toTypedArray()) {
                it.toMedia()
            }.forEach { byMessage.getOrPut(it.messageId) { ArrayList() }.add(it) }
        }
        return rows.map { row -> byMessage[row.id]?.let { row.copy(media = it) } ?: row }
    }

    private fun rowIdOf(db: SQLiteDatabase, messageId: String): Long? =
        query(db, "SELECT rowid FROM messages WHERE message_id = ?", arrayOf(messageId)) { it.getLong(0) }.firstOrNull()

    private fun media(db: SQLiteDatabase, messageId: String): List<MediaRow> =
        query(db, "SELECT * FROM media WHERE message_id = ? ORDER BY rowid", arrayOf(messageId)) { it.toMedia() }

    private fun outboxConversation(db: SQLiteDatabase, key: String): String? =
        query(db, "SELECT conversation_id FROM outbox_queue WHERE idempotency_key = ?", arrayOf(key)) {
            it.getString(0)
        }.firstOrNull()

    private fun isReferenced(db: SQLiteDatabase, path: String): Boolean =
        path.isEmpty() || query(db, "SELECT 1 FROM media WHERE local_path = ? OR thumb_path = ? LIMIT 1", arrayOf(path, path)) {
            true
        }.isNotEmpty()

    /** [path] as a file, only if it is inside the media cache (nothing else is ever deleted). */
    private fun mediaFileOrNull(path: String): File? = fileInside(path, MediaCache.mediaDir(filesDir))

    private fun outboxFileOrNull(path: String): File? = fileInside(path, MediaCache.outboxDir(filesDir))

    private fun fileInside(path: String, dir: File): File? {
        if (path.isEmpty()) return null
        return try {
            val file = File(path).canonicalFile
            if (file.parentFile == dir.canonicalFile) file else null
        } catch (e: java.io.IOException) {
            null
        }
    }

    companion object {
        private val SKIPPED = UpsertResult(inserted = false, replacedTmpId = null, newMediaParts = emptyList())

        private val TABLES = listOf(
            "messages_fts", "media", "messages", "participants", "sync_state", "conversations",
            "contacts", "outbox_queue", "connection_health",
        )

        @Volatile
        private var instance: ArchiveRepository? = null

        fun get(context: Context): ArchiveRepository =
            instance ?: synchronized(this) {
                instance ?: ArchiveRepository(ArchiveDb(context)).also { instance = it }
            }

        /** A repository over a database of the test's choosing, independent of [get]. */
        fun forTesting(db: ArchiveDb): ArchiveRepository = ArchiveRepository(db)

        /**
         * Makes [get] return [repo] (tests only), so screens, which always
         * use [get], read the archive the test seeded and the manager writes.
         */
        internal fun installForTesting(repo: ArchiveRepository) = synchronized(this) { instance = repo }

        /** Forgets the process instance (tests only); the next [get] opens one for the current app. */
        internal fun resetForTesting() = synchronized(this) { instance = null }

        /**
         * Turns what the user typed into an FTS4 query that cannot be misread
         * as search syntax: each word becomes a quoted phrase (quotes and
         * stars removed), and the last word also matches as a prefix.
         * Null when nothing searchable is left.
         */
        internal fun ftsQuery(input: String): String? {
            // Inside a quoted phrase FTS4 still reads '"' (end of phrase), '*'
            // (prefix) and '^' (first token), and stops at NUL, which leaves an
            // unbalanced quote ("malformed MATCH expression").
            val cleaned = buildString(input.length) {
                for (c in input) append(if (c == '"' || c == '*' || c == '^' || c.isISOControl()) ' ' else c)
            }
            // A word unicode61 reads as no token at all (emoji, symbols) would
            // make the whole AND match nothing, so only words with a letter or
            // digit are kept. The cap keeps the expression tree under FTS's
            // depth limit of 12 (about 4000 words throws).
            val words = cleaned.split(Regex("\\s+"))
                .filter { word -> word.codePoints().anyMatch { isTokenCodePoint(it) } }
                .take(MAX_SEARCH_WORDS)
            if (words.isEmpty()) return null
            return words.mapIndexed { i, word ->
                if (i == words.lastIndex) "\"${trimTrailingNonToken(word)}*\"" else "\"$word\""
            }.joinToString(" ")
        }

        private const val MAX_SEARCH_WORDS = 32

        /**
         * Letters and numbers, which unicode61 always treats as token
         * characters. It may treat some other characters (newer emoji) as
         * tokens too; leaving those out only widens the search.
         */
        private fun isTokenCodePoint(cp: Int): Boolean {
            if (Character.isLetterOrDigit(cp)) return true
            val type = Character.getType(cp)
            return type == Character.LETTER_NUMBER.toInt() || type == Character.OTHER_NUMBER.toInt()
        }

        private fun trimTrailingNonToken(word: String): String {
            var end = word.length
            while (end > 0) {
                val cp = Character.codePointBefore(word, end)
                if (isTokenCodePoint(cp)) break
                end -= Character.charCount(cp)
            }
            return word.substring(0, end)
        }
    }
}

private fun Boolean.toInt() = if (this) 1 else 0

private fun DbCursor.str(column: String): String = getString(getColumnIndexOrThrow(column)) ?: ""

private fun DbCursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))

private fun DbCursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))

private fun DbCursor.bool(column: String): Boolean = getInt(getColumnIndexOrThrow(column)) != 0

private fun DbCursor.blob(column: String): ByteArray = getBlob(getColumnIndexOrThrow(column)) ?: ByteArray(0)

private fun DbCursor.toConversation() = ConversationRow(
    id = str("conversation_id"),
    name = str("name"),
    isGroup = bool("is_group"),
    isRcs = bool("is_rcs"),
    folder = str("folder"),
    statusCode = int("status_code"),
    unread = bool("unread"),
    pinned = bool("pinned"),
    readOnly = bool("read_only"),
    muted = bool("muted"),
    lastMessageTs = long("last_message_ts"),
    latestMessageId = str("latest_message_id"),
    snippet = str("snippet"),
    snippetFromMe = bool("snippet_from_me"),
    snippetSender = str("snippet_sender"),
    avatarColor = str("avatar_color"),
    defaultOutgoingId = str("default_outgoing_id"),
    updatedAt = long("updated_at"),
)

private fun DbCursor.toParticipant() = ParticipantRow(
    conversationId = str("conversation_id"),
    id = str("participant_id"),
    name = str("name"),
    firstName = str("first_name"),
    number = str("number"),
    formattedNumber = str("formatted_number"),
    isMe = bool("is_me"),
    color = str("color"),
    contactId = str("contact_id"),
    visible = bool("visible"),
)

/** Media is attached separately. */
private fun DbCursor.toMessage() = MessageRow(
    id = str("message_id"),
    conversationId = str("conversation_id"),
    tmpId = str("tmp_id"),
    senderId = str("sender_id"),
    senderName = str("sender_name"),
    isOutgoing = bool("is_outgoing"),
    timestampMs = long("timestamp_ms"),
    direction = str("direction"),
    status = str("message_status"),
    statusCode = int("status_code"),
    errorText = str("error_text"),
    transport = str("transport_type"),
    text = str("text_body"),
    subject = str("subject"),
    replyToId = str("reply_to_msg_id"),
    reactions = reactionsFrom(str("reactions_json")),
    localState = str("local_state"),
    media = emptyList(),
    updatedAt = long("updated_at"),
)

private fun DbCursor.toMedia() = MediaRow(
    messageId = str("message_id"),
    partId = str("part_id"),
    mediaId = str("media_id"),
    key = blob("decryption_key"),
    thumbMediaId = str("thumb_media_id"),
    thumbKey = blob("thumb_key"),
    mime = str("mime"),
    fileName = str("file_name"),
    size = long("size"),
    width = int("width"),
    height = int("height"),
    inlineData = blob("inline_data"),
    needsFullSize = bool("needs_full_size"),
    localPath = str("local_path"),
    thumbPath = str("thumb_path"),
    fullSizeRequested = bool("full_size_requested"),
    stickerChecked = bool("sticker_checked"),
)

private fun DbCursor.toOutbox() = OutboxRow(
    idempotencyKey = str("idempotency_key"),
    conversationId = str("conversation_id"),
    textBody = str("text_body"),
    mediaLocalPath = str("media_local_path"),
    mediaMime = str("media_mime"),
    mediaName = str("media_name"),
    replyToId = str("reply_to_id"),
    status = str("status"),
    attempts = int("attempts"),
    lastError = str("last_error"),
    createdAt = long("created_at"),
)

private fun DbCursor.toContact() = ContactRow(
    contactKey = str("contact_key"),
    participantId = str("participant_id"),
    contactId = str("contact_id"),
    phoneNumber = str("phone_number"),
    formattedNumber = str("formatted_number"),
    displayName = str("display_name"),
    customAlias = str("custom_alias"),
    avatarPath = str("avatar_path"),
    color = str("color"),
    updatedAt = long("updated_at"),
)

private fun PartDto.toMediaRow(
    messageId: String,
    localPath: String,
    thumbPath: String,
    fullSizeRequested: Boolean,
    stickerChecked: Boolean,
) = MediaRow(
    messageId = messageId,
    partId = partId,
    mediaId = mediaId,
    key = key,
    thumbMediaId = thumbMediaId,
    thumbKey = thumbKey,
    mime = mime,
    fileName = fileName,
    size = size,
    width = width,
    height = height,
    inlineData = inlineData,
    needsFullSize = needsFullSize,
    localPath = localPath,
    thumbPath = thumbPath,
    fullSizeRequested = fullSizeRequested,
    stickerChecked = stickerChecked,
)

private fun MediaRow.toValues() = ContentValues().apply {
    put("message_id", messageId)
    put("part_id", partId)
    put("media_id", mediaId)
    put("decryption_key", key.takeIf { it.isNotEmpty() })
    put("thumb_media_id", thumbMediaId)
    put("thumb_key", thumbKey.takeIf { it.isNotEmpty() })
    put("mime", mime)
    put("file_name", fileName)
    put("size", size)
    put("width", width)
    put("height", height)
    put("inline_data", inlineData.takeIf { it.isNotEmpty() })
    put("needs_full_size", if (needsFullSize) 1 else 0)
    put("local_path", localPath)
    put("thumb_path", thumbPath)
    put("full_size_requested", if (fullSizeRequested) 1 else 0)
    put("sticker_checked", if (stickerChecked) 1 else 0)
}

private fun OutboxRow.toValues() = ContentValues().apply {
    put("idempotency_key", idempotencyKey)
    put("conversation_id", conversationId)
    put("text_body", textBody)
    put("media_local_path", mediaLocalPath)
    put("media_mime", mediaMime)
    put("media_name", mediaName)
    put("reply_to_id", replyToId)
    put("status", status)
    put("attempts", attempts)
    put("last_error", lastError)
    put("created_at", createdAt)
}

private fun reactionsFrom(json: String): List<ReactionDto> = try {
    ReactionDto.listFromJson(JSONArray(json))
} catch (e: JSONException) {
    emptyList()
}
