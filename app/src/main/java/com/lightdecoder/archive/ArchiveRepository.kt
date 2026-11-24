package com.lightdecoder.archive

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.lightdecoder.archive.ArchiveChange.Kind
import com.lightdecoder.chat.ChatService
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import android.database.Cursor as DbCursor

/**
 * The one way in and out of one service's local message archive.
 *
 * Each [ChatService] has an archive of its own ([get]): its own database
 * file and its own media directories ([MediaCache]). Nothing here ever looks
 * at another service's rows or files.
 *
 * Every method blocks, so call it off the main thread. It is safe from any
 * thread: each write runs in one transaction, and WAL lets reads go on while
 * a write is in progress. After a write commits, the change is announced
 * through [ArchiveEvents] (carrying [service]), and media files nothing
 * points to any more are deleted.
 *
 * Messages are upserted, because the phone sends the same message again
 * whenever its status, reactions or media change. The rules are in
 * [upsertMessage].
 */
class ArchiveRepository private constructor(
    private val helper: ArchiveDb,
    /** The service whose archive this is: named in every [ArchiveChange], and it decides the media directories. */
    val service: ChatService,
) {

    private val filesDir: File get() = helper.appContext.filesDir

    /** This archive's downloaded media and thumbnails; never another service's. */
    private val mediaDir: File get() = MediaCache.mediaDir(filesDir, service)

    /** This archive's copies of attachments waiting to be sent. */
    private val outboxDir: File get() = MediaCache.outboxDir(filesDir, service)

    /** When this instance last looked for partial files left by writes that never finished. */
    private val lastPartSweep = AtomicLong(0)

    /** The user's own pins and "Mark as Unread" ([LocalMarks]), which a sync from the phone keeps. */
    private val marks by lazy { LocalMarks(helper.appContext, helper.databaseName ?: "memory-${service.key}") }

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

    /**
     * The addresses the chats are carried on ([ConversationRow.defaultOutgoingId]): in iMessage's
     * archive only ever the user's own (the block list refuses them). At most [limit].
     */
    fun outgoingIds(limit: Int = 32): List<String> =
        query(
            "SELECT DISTINCT default_outgoing_id FROM conversations WHERE default_outgoing_id != '' LIMIT ${limit.coerceAtLeast(0)}",
            emptyArray(),
        ) { it.getString(0) }.filter { it.isNotBlank() }

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
     * Google Messages does not report) is only ever turned on from here for
     * Google Messages; WhatsApp reports it, and is followed both ways.
     * A conversation the phone reports as deleted (or trashed) is not
     * deleted here: it moves to the local [Folder.DELETED] folder with its
     * messages, until the user restores ([restoreDeleted]) or purges it
     * ([purgeDeleted]).
     */
    fun upsertConversation(dto: ConversationDto) = write { writeConversation(it, dto) }

    /**
     * As [upsertConversation]; should it make the conversation unread (it was read, or not stored),
     * its unread messages begin at [unreadSinceMs] (the message it came with), which "Jump to first
     * unread" goes to. While it is unread that time stays; read, it is cleared.
     */
    fun upsertConversation(dto: ConversationDto, unreadSinceMs: Long) = write { writeConversation(it, dto, unreadSinceMs) }

    /** Like [upsertConversation] for many, in one transaction. */
    fun upsertConversations(dtos: List<ConversationDto>) = write { b -> dtos.forEach { writeConversation(b, it) } }

    /** Number of unread conversations in the inbox. */
    fun unreadCount(): Int =
        query("SELECT COUNT(*) FROM conversations WHERE unread = 1 AND folder = ?", arrayOf(Folder.INBOX.wire)) {
            it.getInt(0)
        }.firstOrNull() ?: 0

    /** Clears the local unread flag (the phone is told separately), a "Mark as Unread" with it. */
    fun markConversationRead(conversationId: String) = write { b ->
        marks.setUnread(conversationId, false)
        b.db.update("conversations", ContentValues().apply { put("unread", 0); put("first_unread_ts", 0) }, "conversation_id = ?", arrayOf(conversationId))
        b.changed(Kind.CONVERSATIONS, conversationId)
    }

    /**
     * "Mark as Unread": the conversation is unread here, and stays so (whatever the phone says of it
     * meanwhile) until it is read again ([markConversationRead]). False if there is no such conversation.
     */
    fun markConversationUnread(conversationId: String): Boolean = write { b ->
        val changed = b.db.update("conversations", ContentValues().apply { put("unread", 1) }, "conversation_id = ?", arrayOf(conversationId)) > 0
        if (changed) {
            marks.setUnread(conversationId, true)
            b.changed(Kind.CONVERSATIONS, conversationId)
        }
        changed
    }

    /**
     * "Pin" / "Unpin", here: the conversation is listed at the top (or not), the user's choice kept
     * over what the phone reports of it from now on. False if there is no such conversation.
     */
    fun setConversationPinned(conversationId: String, pinned: Boolean): Boolean = write { b ->
        val changed = b.db.update("conversations", ContentValues().apply { put("pinned", pinned.toInt()) }, "conversation_id = ?", arrayOf(conversationId)) > 0
        if (changed) {
            marks.setPinned(conversationId, pinned)
            b.changed(Kind.CONVERSATIONS, conversationId)
        }
        changed
    }

    /** Whether the user ever wrote in [conversationId] (a message of theirs, sent or not, is stored in it). */
    fun hasOutgoing(conversationId: String): Boolean = query(
        "SELECT 1 FROM messages WHERE conversation_id = ? AND is_outgoing = 1 AND direction = 'out' LIMIT 1",
        arrayOf(conversationId),
    ) { true }.isNotEmpty()

    /**
     * Every message of [conversationId] whose extra data holds one of [extraNeedles] or whose reactions
     * hold one of [reactionNeedles] (a caller checks what it really holds), newest first, at most [limit].
     */
    fun messagesHolding(conversationId: String, extraNeedles: List<String>, reactionNeedles: List<String> = emptyList(), limit: Int = 5_000): List<MessageRow> {
        val tests = extraNeedles.filter { it.isNotEmpty() }.map { "instr(extra_json, ?) > 0" } +
            reactionNeedles.filter { it.isNotEmpty() }.map { "instr(reactions_json, ?) > 0" }
        if (tests.isEmpty()) return emptyList()
        val args = arrayOf(conversationId) + extraNeedles.filter { it.isNotEmpty() } + reactionNeedles.filter { it.isNotEmpty() }
        return query(
            "SELECT * FROM messages WHERE conversation_id = ? AND (${tests.joinToString(" OR ")}) " +
                "ORDER BY timestamp_ms DESC, rowid DESC LIMIT ${limit.coerceAtLeast(0)}",
            args,
        ) { it.toMessage() }
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
    fun deleteConversationLocal(conversationId: String) = write { b ->
        tombstoneConversation(b, conversationId)
        deleteConversation(b, conversationId)
        capTombstones(b.db)
    }

    /**
     * Moves a conversation the phone deleted back to the inbox, here only.
     * False if it is not in [Folder.DELETED].
     */
    fun restoreDeleted(conversationId: String): Boolean = write { b ->
        val moved = b.db.update(
            "conversations", ContentValues().apply { put("folder", Folder.INBOX.wire); put("deleted_at", 0) },
            "conversation_id = ? AND folder = ?", arrayOf(conversationId, Folder.DELETED.wire),
        ) > 0
        if (moved) b.changed(Kind.CONVERSATIONS, conversationId)
        moved
    }

    /**
     * Moves [conversationId] to Recently Deleted ([Folder.DELETED]) as of [atMs], with its messages.
     * False if there is no such conversation or it is there already (its time then stays).
     */
    fun moveToDeleted(conversationId: String, atMs: Long = System.currentTimeMillis()): Boolean = write { b ->
        moveToDeleted(b, conversationId, atMs)
    }

    /**
     * Sets when [conversationId] went to Recently Deleted to [atMs] (so that [purgeExpired] deletes it
     * then). False if it is not in [Folder.DELETED].
     */
    fun setDeletedAt(conversationId: String, atMs: Long): Boolean = write { b ->
        val changed = b.db.update(
            "conversations", ContentValues().apply { put("deleted_at", atMs) },
            "conversation_id = ? AND folder = ?", arrayOf(conversationId, Folder.DELETED.wire),
        ) > 0
        if (changed) b.changed(Kind.CONVERSATIONS, conversationId)
        changed
    }

    /**
     * The conversations in Recently Deleted since before [beforeMs]. One there with no time (moved
     * there before the time was kept) gets now as its time first, so it is kept its full period.
     */
    fun expiredDeleted(beforeMs: Long): List<String> = write { b ->
        b.db.update(
            "conversations", ContentValues().apply { put("deleted_at", b.now) },
            "folder = ? AND deleted_at <= 0", arrayOf(Folder.DELETED.wire),
        )
        query(
            b.db, "SELECT conversation_id FROM conversations WHERE folder = ? AND deleted_at < ?",
            arrayOf(Folder.DELETED.wire, beforeMs.toString()),
        ) { it.getString(0) }
    }

    /** Deletes for good ([purgeDeleted]) every conversation in Recently Deleted since before [beforeMs]; how many. */
    fun purgeExpired(beforeMs: Long): Int = expiredDeleted(beforeMs).sumOf { purgeDeleted(it) }

    // --- deleted message ids ----------------------------------------------------------

    /**
     * Whether [messageId] was deleted here (or on the user's other devices) within the last
     * [TOMBSTONE_DAYS] days: Apple handing it over again does not store it again.
     */
    fun isTombstoned(messageId: String): Boolean =
        query("SELECT 1 FROM deleted_guids WHERE guid = ? LIMIT 1", arrayOf(messageId)) { true }.isNotEmpty()

    /** Notes [messageIds] as deleted ([isTombstoned]); the oldest beyond [MAX_TOMBSTONES] go. */
    fun tombstone(messageIds: Collection<String>, atMs: Long = System.currentTimeMillis()) = write { b ->
        messageIds.forEach { addTombstone(b, it, atMs) }
        capTombstones(b.db)
    }

    /** Forgets deleted ids older than [TOMBSTONE_DAYS] days, then all but the newest [MAX_TOMBSTONES] (a deleted chat's mark stays: [conversationDeletedAt]). */
    fun pruneTombstones(now: Long = System.currentTimeMillis()) = write { b ->
        b.db.delete("deleted_guids", "at < ? AND $NOT_CHAT_MARK", arrayOf((now - TOMBSTONE_MS).toString()))
        capTombstones(b.db)
    }

    /** How many deleted message ids are kept. */
    fun tombstoneCount(): Int = query("SELECT COUNT(*) FROM deleted_guids WHERE $NOT_CHAT_MARK", emptyArray()) { it.getInt(0) }.firstOrNull() ?: 0

    /**
     * When the iMessage chat [conversationId] was deleted here for good (permanently, or as a local
     * delete), the latest time; null when it never was (or the mark went, the oldest beyond
     * [MAX_CHAT_TOMBSTONES]). Kept apart from the messages' ids, which are capped and expire: what
     * an import of older messages (Messages in iCloud) must not bring back.
     */
    fun conversationDeletedAt(conversationId: String): Long? =
        query("SELECT at FROM deleted_guids WHERE guid = ? LIMIT 1", arrayOf(CHAT_MARK + conversationId)) { it.getLong(0) }.firstOrNull()

    /** Whether [conversationId] holds a message (from the phone or Apple, [prefix]) whose `extra_json` lacks [needle]. */
    fun hasMessageWithout(conversationId: String, needle: String, prefix: String = "imsg:"): Boolean = query(
        "SELECT 1 FROM messages WHERE conversation_id = ? AND substr(message_id, 1, ${prefix.length}) = ? AND instr(extra_json, ?) = 0 LIMIT 1",
        arrayOf(conversationId, prefix, needle),
    ) { true }.isNotEmpty()

    /**
     * Deletes the message [messageId] with its media, as [applyDeletion] does but noting nothing as
     * deleted: for copies the user asked to remove (imported history), whose ids must not push the
     * real deletions' out of [MAX_TOMBSTONES]. False when it is not stored.
     */
    fun removeCopy(messageId: String): Boolean = write { b -> deleteMessage(b, messageId, tombstone = false) }

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
        ids.forEach {
            tombstoneConversation(b, it)
            deleteConversation(b, it)
        }
        capTombstones(b.db)
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
    fun upsertMessage(dto: MessageDto, snippet: String? = null): UpsertResult = write { writeMessage(it, dto, snippet) }

    /**
     * Stores a page of messages in one transaction, by the same rules.
     * Returns how many were inserted or updated (deletions not counted).
     */
    fun upsertMessages(dtos: List<MessageDto>): Int = write { b ->
        dtos.count { writeMessage(b, it) !== SKIPPED }
    }

    /** Removes a message the phone deleted, with its search entry and media files. */
    fun applyDeletion(messageId: String) = write { deleteMessage(it, messageId); Unit }

    /**
     * Moves messages this account sent forward to [status] (with
     * [statusCode]), as a delivery or read receipt reports it. Never
     * backwards: [rankOf] orders the status names, and a message already at
     * [status] or beyond keeps what it has (receipts arrive late, twice and
     * out of order). Incoming messages and local copies still being sent are
     * left alone. Returns the ids that are stored at all, changed or not, so
     * the caller knows which receipts came before their message; the ones
     * that did move forward are also added to [advanced], if given.
     */
    fun advanceMessageStatus(
        messageIds: Collection<String>,
        status: String,
        statusCode: Int,
        rankOf: (String) -> Int,
        advanced: MutableCollection<String>? = null,
    ): Set<String> =
        if (messageIds.isEmpty()) emptySet() else write { b ->
            val found = HashSet<String>()
            val target = rankOf(status)
            for (id in messageIds.distinct()) {
                val row = query(
                    b.db, "SELECT conversation_id, message_status, is_outgoing, direction FROM messages WHERE message_id = ?", arrayOf(id),
                ) { c -> listOf(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getInt(2).toString(), c.getString(3).orEmpty()) }
                    .firstOrNull() ?: continue
                found.add(id)
                val (conversationId, current, outgoing, direction) = row
                if (outgoing != "1" || direction != "out" || id.startsWith(MessageRow.TMP_PREFIX)) continue
                if (rankOf(current) >= target) continue
                b.db.update("messages", ContentValues().apply {
                    put("message_status", status)
                    put("status_code", statusCode)
                    put("updated_at", b.now)
                }, "message_id = ?", arrayOf(id))
                b.changed(Kind.MESSAGES, conversationId)
                advanced?.add(id)
            }
            found
        }

    /**
     * Marks one of the account's sent messages failed ([status], with
     * [errorText]) after the service reported that it did not reach someone,
     * unless it already got as far as [keepFromRank] ([rankOf] orders the
     * status names): a delivered or read message stays as it is. Returns
     * whether it changed.
     */
    fun markSentFailed(messageId: String, status: String, errorText: String, keepFromRank: Int, rankOf: (String) -> Int): Boolean = write { b ->
        val row = query(
            b.db, "SELECT conversation_id, message_status FROM messages WHERE message_id = ? AND is_outgoing = 1 AND direction = 'out'",
            arrayOf(messageId),
        ) { it.getString(0).orEmpty() to it.getString(1).orEmpty() }.firstOrNull() ?: return@write false
        if (rankOf(row.second) >= keepFromRank) return@write false
        b.db.update("messages", ContentValues().apply {
            put("message_status", status)
            put("error_text", errorText)
            put("updated_at", b.now)
        }, "message_id = ?", arrayOf(messageId))
        b.changed(Kind.MESSAGES, row.first)
        true
    }

    /**
     * Only a message's extra changes (what a service keeps with it, such as which photo a tapback is
     * on): its text, snippet and everything else stay. False if there is no such message.
     */
    fun setExtra(messageId: String, extra: String): Boolean = write { b ->
        val conversationId = query(b.db, "SELECT conversation_id FROM messages WHERE message_id = ?", arrayOf(messageId)) { it.getString(0).orEmpty() }
            .firstOrNull() ?: return@write false
        b.db.update("messages", ContentValues().apply {
            put("extra_json", extra)
            put("updated_at", b.now)
        }, "message_id = ?", arrayOf(messageId))
        b.changed(Kind.MESSAGES, conversationId)
        true
    }

    /**
     * The sender edited a message: its new [text], edited at [editedAtMs],
     * and (when not null) its new [extra]. Its reactions, media and status
     * stay as they are. The search index follows, and so does the
     * conversation's snippet when this is its latest message. False if
     * there is no such message.
     */
    fun editText(messageId: String, text: String, editedAtMs: Long, extra: String?, snippet: String? = null): Boolean = write { b ->
        val found = query(b.db, "SELECT rowid, conversation_id, subject FROM messages WHERE message_id = ?", arrayOf(messageId)) {
            Triple(it.getLong(0), it.getString(1).orEmpty(), it.getString(2).orEmpty())
        }.firstOrNull() ?: return@write false
        val (rowId, conversationId, subject) = found
        b.db.update("messages", ContentValues().apply {
            put("text_body", text)
            put("edited_at", editedAtMs)
            if (extra != null) put("extra_json", extra)
            put("updated_at", b.now)
        }, "message_id = ?", arrayOf(messageId))
        indexForSearch(b.db, rowId, messageId, conversationId, subject, text)
        followSnippet(b, conversationId, messageId, snippet ?: text)
        b.changed(Kind.MESSAGES, conversationId)
        true
    }

    /**
     * One person's reaction to a message, the way Apple's Messages keeps
     * them: one per person. With [enable] they are taken off every reaction
     * on the message and put on [emoji]'s; without, they are taken off
     * [emoji]'s only. The user is [fromMe] (the reaction's fromMe flag) as
     * well as [participantId]. Reactions left with nobody go. Only the
     * reactions change. The time a reaction appeared ([atMs]) is noted for
     * the reaction details. False if there is no such message.
     */
    fun applyReaction(messageId: String, participantId: String, emoji: String, enable: Boolean, fromMe: Boolean, atMs: Long): Boolean {
        var before: List<ReactionDto>? = null
        var after: List<ReactionDto> = emptyList()
        val stored = write { b ->
            val found = query(b.db, "SELECT conversation_id, reactions_json FROM messages WHERE message_id = ?", arrayOf(messageId)) {
                it.getString(0).orEmpty() to reactionsFrom(it.getString(1) ?: "[]")
            }.firstOrNull() ?: return@write false
            before = found.second
            after = withReaction(found.second, participantId, emoji, enable, fromMe)
            if (after == found.second) return@write true
            b.db.update("messages", ContentValues().apply {
                put("reactions_json", ReactionDto.listToJson(after).toString())
                put("updated_at", b.now)
            }, "message_id = ?", arrayOf(messageId))
            b.changed(Kind.MESSAGES, found.first)
            true
        }
        if (stored) {
            try {
                com.lightdecoder.reactions.ReactionTimes.get(helper.appContext).note(service, messageId, before, after, atMs, live = true)
            } catch (e: RuntimeException) {
                // Only the reaction's time is lost.
            }
        }
        return stored
    }

    /**
     * Puts [text] in a message's place, as a row of the conversation's own
     * ("You unsent a message."): same id, time and conversation; its
     * reactions, reply, subject and media go (media files nothing else uses
     * are deleted). As the conversation's latest message, the snippet reads
     * [text], with no "You:" or sender before it. False if there is no such
     * message.
     */
    fun replaceWithPlaceholder(messageId: String, text: String): Boolean = write { b ->
        val found = query(b.db, "SELECT rowid, conversation_id FROM messages WHERE message_id = ?", arrayOf(messageId)) {
            it.getLong(0) to it.getString(1).orEmpty()
        }.firstOrNull() ?: return@write false
        val (rowId, conversationId) = found
        media(b.db, messageId).forEach {
            b.maybeOrphaned.add(it.localPath)
            b.maybeOrphaned.add(it.thumbPath)
        }
        b.db.delete("media", "message_id = ?", arrayOf(messageId))
        b.db.update("messages", ContentValues().apply {
            put("direction", "system")
            put("text_body", text)
            put("subject", "")
            put("reply_to_msg_id", "")
            put("reactions_json", "[]")
            put("message_status", "")
            put("error_text", "")
            put("local_state", LocalState.SYNCED)
            put("edited_at", 0)
            put("extra_json", "")
            put("updated_at", b.now)
        }, "message_id = ?", arrayOf(messageId))
        indexForSearch(b.db, rowId, messageId, conversationId, "", text)
        followSnippet(b, conversationId, messageId, text, systemRow = true)
        b.changed(Kind.MESSAGES, conversationId)
        true
    }

    /**
     * Removes one media part of a message (the sender took that attachment
     * back); its files go if nothing else uses them. False if there was no
     * such part.
     */
    fun removeMediaPart(messageId: String, partId: String): Boolean = write { b ->
        val part = query(b.db, "SELECT * FROM media WHERE message_id = ? AND part_id = ?", arrayOf(messageId, partId)) {
            it.toMedia()
        }.firstOrNull() ?: return@write false
        b.maybeOrphaned.add(part.localPath)
        b.maybeOrphaned.add(part.thumbPath)
        b.db.delete("media", "message_id = ? AND part_id = ?", arrayOf(messageId, partId))
        query(b.db, "SELECT conversation_id FROM messages WHERE message_id = ?", arrayOf(messageId)) {
            it.getString(0)
        }.firstOrNull()?.let { b.changed(Kind.MESSAGES, it) }
        true
    }

    /** The newest message of a conversation whose id starts with [prefix], or null if there is none. */
    fun latestMessageId(conversationId: String, prefix: String = "imsg:"): String? = query(
        "SELECT message_id FROM messages WHERE conversation_id = ? AND substr(message_id, 1, ${prefix.length}) = ? " +
            "ORDER BY timestamp_ms DESC, rowid DESC LIMIT 1",
        arrayOf(conversationId, prefix),
    ) { it.getString(0) }.firstOrNull()

    /**
     * Up to [limit] messages of a conversation whose id starts with [prefix] and whose extra data
     * contains [needle] (a caller checks what it really holds), newest first.
     */
    fun messagesWithExtra(conversationId: String, needle: String, prefix: String = "imsg:", limit: Int = 20): List<MessageRow> {
        if (needle.isEmpty()) return emptyList()
        val rows = query(
            "SELECT * FROM messages WHERE conversation_id = ? AND substr(message_id, 1, ${prefix.length}) = ? " +
                "AND instr(extra_json, ?) > 0 ORDER BY timestamp_ms DESC, rowid DESC LIMIT ${limit.coerceAtLeast(0)}",
            arrayOf(conversationId, prefix, needle),
        ) { it.toMessage() }
        return withMedia(rows)
    }

    /**
     * The FaceTime calls' rows of every chat ([com.lightdecoder.imessage.facetime.CallRows]: their ids
     * are "ftcall:<guid>", so the primary key's order finds them), newest first, at most [limit], each
     * with its chat; none of a chat in [Folder.DELETED] or [Folder.SPAM].
     */
    fun calls(limit: Int): List<Pair<MessageRow, ConversationRow>> {
        val rows = query(
            "SELECT * FROM messages WHERE message_id >= ? AND message_id < ? AND conversation_id IN " +
                "(SELECT conversation_id FROM conversations WHERE folder != ? AND folder != ?) " +
                "ORDER BY timestamp_ms DESC, rowid DESC LIMIT ${limit.coerceAtLeast(0)}",
            arrayOf(CALL_ROWS_FROM, CALL_ROWS_TO, Folder.DELETED.wire, Folder.SPAM.wire),
        ) { it.toMessage() }
        val chats = HashMap<String, ConversationRow?>()
        return rows.mapNotNull { row -> chats.getOrPut(row.conversationId) { conversation(row.conversationId) }?.let { row to it } }
    }

    /** Like [latestMessageId], among the messages other people sent; with [excludingExtra], none whose `extra_json` holds it. */
    fun latestIncomingMessageId(conversationId: String, prefix: String = "imsg:", excludingExtra: String = ""): String? = query(
        "SELECT message_id FROM messages WHERE conversation_id = ? AND substr(message_id, 1, ${prefix.length}) = ? " +
            "AND is_outgoing = 0 AND direction = 'in' " + (if (excludingExtra.isEmpty()) "" else "AND instr(extra_json, ?) = 0 ") +
            "ORDER BY timestamp_ms DESC, rowid DESC LIMIT 1",
        if (excludingExtra.isEmpty()) arrayOf(conversationId, prefix) else arrayOf(conversationId, prefix, excludingExtra),
    ) { it.getString(0) }.firstOrNull()

    /**
     * The id and timestamp of the oldest message from the phone in one
     * conversation (ties broken by id), or null if there is none: where a
     * service whose history pages by "older than this message" starts from.
     */
    fun oldestStored(conversationId: String): Pair<String, Long>? = query(
        "SELECT message_id, timestamp_ms FROM messages WHERE conversation_id = ? AND substr(message_id, 1, ${MessageRow.TMP_PREFIX.length}) != ? " +
            "ORDER BY timestamp_ms ASC, message_id ASC LIMIT 1",
        arrayOf(conversationId, MessageRow.TMP_PREFIX),
    ) { it.getString(0) to it.getLong(1) }.firstOrNull()

    /** How many messages from the phone (local copies of sends not counted) one conversation holds. */
    fun storedMessageCount(conversationId: String): Int = query(
        "SELECT COUNT(*) FROM messages WHERE conversation_id = ? AND substr(message_id, 1, ${MessageRow.TMP_PREFIX.length}) != ?",
        arrayOf(conversationId, MessageRow.TMP_PREFIX),
    ) { it.getInt(0) }.firstOrNull() ?: 0

    /** Which of [messageIds] are stored. */
    fun knownMessageIds(messageIds: Collection<String>): Set<String> {
        val known = HashSet<String>()
        messageIds.distinct().chunked(500).forEach { ids ->
            query("SELECT message_id FROM messages WHERE message_id IN (${ids.joinToString(",") { "?" }})", ids.toTypedArray()) {
                known.add(it.getString(0))
            }
        }
        return known
    }

    /**
     * Shows a message being sent right away, as "tmp:<tmpId>" in state
     * pending, until the phone echoes the real one. A media attachment is
     * stored as part "0" pointing at [mediaPath]. [extra] is the service's
     * own display data for it (iMessage: a voice message). Does nothing if
     * that tmpId is already there.
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
        extra: String = "",
    ) = write { b ->
        requireOwnDirectory(mediaPath.orEmpty())
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
            put("extra_json", extra)
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

    /**
     * Moves the message being sent [tmpOrMessageId] from local state [from] to [to] (its error
     * cleared), only if it is still at [from]: of two callers at once, one gets true. False when
     * it is not at [from] (or there is no such message).
     */
    fun setLocalStateIf(tmpOrMessageId: String, from: String, to: String): Boolean = write { b ->
        val ids = arrayOf(MessageRow.tmpMessageId(tmpOrMessageId), tmpOrMessageId)
        val conversationId = query(
            b.db, "SELECT conversation_id FROM messages WHERE message_id IN (?, ?) AND local_state = ?", ids + from,
        ) { it.getString(0) }.firstOrNull() ?: return@write false
        val changed = b.db.update("messages", ContentValues().apply {
            put("local_state", to)
            put("error_text", "")
            put("updated_at", b.now)
        }, "message_id IN (?, ?) AND local_state = ?", ids + from)
        if (changed > 0) b.changed(Kind.MESSAGES, conversationId)
        changed > 0
    }

    // --- outbox -----------------------------------------------------------------

    /** Adds a send to the queue, replacing an entry with the same key. */
    fun enqueueOutbox(row: OutboxRow) = write { b ->
        requireOwnDirectory(row.mediaLocalPath)
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

    /** Every queued send whatever its status, oldest first (for the problem report). */
    fun allOutbox(): List<OutboxRow> = query(
        "SELECT * FROM outbox_queue ORDER BY created_at, rowid", emptyArray(),
    ) { it.toOutbox() }

    /** The newest outgoing messages of every conversation, newest first (for the problem report). */
    fun recentOutgoing(limit: Int = 20): List<MessageRow> = withMedia(
        query(
            "SELECT * FROM messages WHERE is_outgoing = 1 ORDER BY timestamp_ms DESC, rowid DESC LIMIT ${limit.coerceAtLeast(0)}",
            emptyArray(),
        ) { it.toMessage() },
    )

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

    /** A media part with no file and nothing to download it from (an iMessage picture waiting for OpenBubbles' copy). */
    data class AwaitingMedia(val messageId: String, val partId: String, val mime: String, val timestampMs: Long, val conversationId: String)

    /** [AwaitingMedia] of messages since [sinceMs], oldest first. */
    fun mediaAwaitingFiles(sinceMs: Long): List<AwaitingMedia> = query(
        "SELECT m.message_id, m.part_id, m.mime, msg.timestamp_ms, msg.conversation_id FROM media m " +
            "JOIN messages msg ON msg.message_id = m.message_id " +
            "WHERE m.local_path = '' AND m.media_id = '' AND m.thumb_media_id = '' AND (m.inline_data IS NULL OR length(m.inline_data) = 0) " +
            "AND msg.timestamp_ms >= ? ORDER BY msg.timestamp_ms, m.part_id",
        arrayOf(sinceMs.toString()),
    ) { AwaitingMedia(it.getString(0) ?: "", it.getString(1) ?: "", it.getString(2) ?: "", it.getLong(3), it.getString(4) ?: "") }

    /** The real type, size and dimensions of a media part, once its file is known. */
    fun setMediaDetails(messageId: String, partId: String, mime: String, size: Long, width: Int, height: Int) =
        updateMedia(messageId, partId, ContentValues().apply {
            if (mime.isNotEmpty()) put("mime", mime)
            if (size > 0) put("size", size)
            if (width > 0 && height > 0) {
                put("width", width)
                put("height", height)
            }
        })

    fun markFullSizeRequested(messageId: String, partId: String) =
        updateMedia(messageId, partId, ContentValues().apply { put("full_size_requested", 1) })

    fun markStickerChecked(messageId: String, partId: String) =
        updateMedia(messageId, partId, ContentValues().apply { put("sticker_checked", 1) })

    /**
     * The cache file for one media id (full size or thumbnail id), or a
     * [MediaCache.inlineKey], in this archive's own media directory: where a
     * download is written before [setMediaLocalPath] records it.
     */
    fun mediaFileFor(mediaId: String, extension: String): File =
        MediaCache.fileFor(helper.appContext, mediaId, extension, service)

    /** The local copy of an attachment queued for sending, in this archive's own outbox directory. */
    fun outboxFileFor(tmpId: String): File = MediaCache.outboxFile(helper.appContext, tmpId, service)

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
        marks.clear()
        mediaDir.deleteRecursively()
        outboxDir.deleteRecursively()
    }

    // --- writing ----------------------------------------------------------------

    /** One write transaction: what it changed, and which files it may have orphaned. */
    private class Batch(val db: SQLiteDatabase, private val service: ChatService) {
        val now = System.currentTimeMillis()
        val changes = LinkedHashSet<ArchiveChange>()
        val maybeOrphaned = LinkedHashSet<String>()
        val outboxFiles = ArrayList<String>()

        fun changed(kind: Kind, conversationId: String?) {
            changes.add(ArchiveChange(conversationId, kind, service))
        }
    }

    private fun <T> write(block: (Batch) -> T): T {
        sweepStaleParts()
        val db = helper.writableDatabase
        val batch = Batch(db, service)
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

    private fun writeConversation(b: Batch, dto: ConversationDto, unreadSinceMs: Long = 0) {
        if (dto.id.isEmpty()) return
        val stored = query(b.db, "SELECT unread, folder FROM conversations WHERE conversation_id = ?", arrayOf(dto.id)) {
            (it.getInt(0) != 0) to (it.getString(1) ?: "")
        }.firstOrNull()
        // An iMessage chat kept in Recently Deleted is still updated (its members, name, whether the
        // user is in it, its latest message), there and as of when it went there: should it come back,
        // it is as it is now. Else a conversation the phone deleted moves there, with all else as it was.
        val keptDeleted = dto.folder == Folder.DELETED.wire && service == ChatService.IMESSAGE && stored?.second == Folder.DELETED.wire
        if (dto.folder == Folder.DELETED.wire && !keptDeleted) {
            moveToDeleted(b, dto.id, b.now)
            return
        }
        val unread = dto.unread || marks.unread(dto.id)
        val wasUnread = stored?.first ?: false
        val values = ContentValues().apply {
            put("name", dto.name)
            put("is_group", dto.isGroup.toInt())
            put("is_rcs", dto.isRcs.toInt())
            put("folder", dto.folder)
            put("status_code", dto.statusCode)
            // The user's own "Mark as Unread" and pin outlast what the phone says.
            put("unread", unread.toInt())
            // Where its unread messages begin: set as it becomes unread (only then), cleared once read.
            if (!unread) put("first_unread_ts", 0)
            else if (!wasUnread) put("first_unread_ts", unreadSinceMs.takeIf { it > 0 } ?: dto.lastMessageTs.coerceAtLeast(0))
            // Out of Recently Deleted (or never in it); one kept there keeps its time.
            if (!keptDeleted) put("deleted_at", 0)
            put("pinned", (marks.pinned(dto.id) ?: dto.pinned).toInt())
            put("read_only", dto.readOnly.toInt())
            // Google Messages does not report mute, so there it is only ever turned
            // on from here; WhatsApp's bridge reports it either way.
            if (dto.muted || service == ChatService.WHATSAPP) put("muted", dto.muted.toInt())
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
    private fun moveToDeleted(b: Batch, conversationId: String, atMs: Long): Boolean {
        val moved = b.db.update(
            "conversations", ContentValues().apply {
                put("folder", Folder.DELETED.wire)
                put("deleted_at", atMs)
                put("updated_at", b.now)
            },
            "conversation_id = ? AND folder != ?", arrayOf(conversationId, Folder.DELETED.wire),
        )
        if (moved > 0) b.changed(Kind.CONVERSATIONS, conversationId)
        return moved > 0
    }

    /** Notes [messageId] as deleted ([isTombstoned]), when it is one whose id Apple may hand over again. */
    private fun addTombstone(b: Batch, messageId: String, atMs: Long): Boolean {
        if (!messageId.startsWith(TOMBSTONED_PREFIX) || messageId.length > MAX_TOMBSTONE_CHARS) return false
        b.db.insertWithOnConflict("deleted_guids", null, ContentValues().apply {
            put("guid", messageId)
            put("at", atMs)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        return true
    }

    /** The newest [TOMBSTONES_PER_CONVERSATION] message ids of [conversationId] are noted as deleted: it is going for good. */
    private fun tombstoneConversation(b: Batch, conversationId: String) {
        if (service != ChatService.IMESSAGE) return
        if (conversationId.startsWith(TOMBSTONED_PREFIX) && conversationId.length + CHAT_MARK.length <= MAX_TOMBSTONE_CHARS) {
            b.db.insertWithOnConflict("deleted_guids", null, ContentValues().apply {
                put("guid", CHAT_MARK + conversationId)
                put("at", b.now)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
        query(
            b.db,
            "SELECT message_id FROM messages WHERE conversation_id = ? AND message_id LIKE ? " +
                "ORDER BY timestamp_ms DESC, rowid DESC LIMIT $TOMBSTONES_PER_CONVERSATION",
            arrayOf(conversationId, "$TOMBSTONED_PREFIX%"),
        ) { it.getString(0) }.forEach { addTombstone(b, it, b.now) }
    }

    /** Keeps the newest [MAX_TOMBSTONES] deleted message ids, and apart from them the newest [MAX_CHAT_TOMBSTONES] deleted chats' marks. */
    private fun capTombstones(db: SQLiteDatabase) {
        val count = query(db, "SELECT COUNT(*) FROM deleted_guids WHERE $NOT_CHAT_MARK", emptyArray()) { it.getInt(0) }.firstOrNull() ?: 0
        if (count > MAX_TOMBSTONES) {
            db.execSQL(
                "DELETE FROM deleted_guids WHERE $NOT_CHAT_MARK AND rowid NOT IN " +
                    "(SELECT rowid FROM deleted_guids WHERE $NOT_CHAT_MARK ORDER BY at DESC, rowid DESC LIMIT $MAX_TOMBSTONES)",
            )
        }
        val chats = query(db, "SELECT COUNT(*) FROM deleted_guids WHERE NOT ($NOT_CHAT_MARK)", emptyArray()) { it.getInt(0) }.firstOrNull() ?: 0
        if (chats > MAX_CHAT_TOMBSTONES) {
            db.execSQL(
                "DELETE FROM deleted_guids WHERE NOT ($NOT_CHAT_MARK) AND rowid NOT IN " +
                    "(SELECT rowid FROM deleted_guids WHERE NOT ($NOT_CHAT_MARK) ORDER BY at DESC, rowid DESC LIMIT $MAX_CHAT_TOMBSTONES)",
            )
        }
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
        // Copies kept for sends with no queue entry (iMessage's, waiting or failed), unless
        // another conversation's queued send uses the same one.
        query(
            b.db,
            "SELECT local_path FROM media m WHERE m.message_id IN (SELECT message_id FROM messages WHERE conversation_id = ?) " +
                "AND NOT EXISTS (SELECT 1 FROM outbox_queue q WHERE q.media_local_path = m.local_path AND q.conversation_id != ?)",
            arrayOf(conversationId, conversationId),
        ) { c -> (c.getString(0) ?: "").takeIf { outboxFileOrNull(it) != null }?.let { b.outboxFiles.add(it) } }
        // The other files of batches still waiting or failed here (named only in their local copy's extra).
        query(
            b.db, "SELECT extra_json FROM messages WHERE conversation_id = ? AND message_id LIKE ?",
            arrayOf(conversationId, MessageRow.TMP_PREFIX + "%"),
        ) { c -> b.outboxFiles.addAll(pendingOutboxFiles(c.getString(0) ?: "")) }
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
        marks.forget(conversationId)
    }

    /** Returns [SKIPPED] for a message that was not stored (deleted or malformed). */
    /** [snippet], when given, is what the conversation list shows for it instead of its text. */
    private fun writeMessage(b: Batch, dto: MessageDto, snippet: String? = null): UpsertResult {
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
            put("edited_at", dto.editedAtMs)
            put("extra_json", dto.extra)
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
        // A row of the conversation's own ("You named the conversation …") already says who.
        val system = dto.direction == "system"
        advanceConversation(
            b, dto.conversationId, dto.id, dto.timestampMs, snippet ?: dto.text,
            dto.fromMe && !system, if (system) "" else dto.senderName,
        )
        b.changed(Kind.MESSAGES, dto.conversationId)
        return UpsertResult(inserted = existingRowId == null, replacedTmpId = replacedTmpId, newMediaParts = newMedia)
    }

    private fun deleteMessage(b: Batch, messageId: String, tombstone: Boolean = true): Boolean {
        val found = query(b.db, "SELECT rowid, conversation_id FROM messages WHERE message_id = ?", arrayOf(messageId)) {
            it.getLong(0) to it.getString(1)
        }.firstOrNull() ?: return false
        media(b.db, messageId).forEach {
            b.maybeOrphaned.add(it.localPath)
            b.maybeOrphaned.add(it.thumbPath)
            // The copy kept for a send with no queue entry (iMessage's): nothing else has it.
            if (outboxFileOrNull(it.localPath) != null && !outboxUses(b.db, it.localPath)) b.outboxFiles.add(it.localPath)
        }
        // A batch's local copy: its other files too, which only its extra names.
        if (messageId.startsWith(MessageRow.TMP_PREFIX)) {
            query(b.db, "SELECT extra_json FROM messages WHERE rowid = ?", arrayOf(found.first.toString())) { c ->
                b.outboxFiles.addAll(pendingOutboxFiles(c.getString(0) ?: ""))
            }
        }
        b.db.execSQL("DELETE FROM messages_fts WHERE docid = ?", arrayOf<Any>(found.first))
        b.db.delete("messages", "message_id = ?", arrayOf(messageId)) // cascades to media
        // Gone from iMessage's archive: Apple handing it over again does not bring it back.
        if (tombstone && service == ChatService.IMESSAGE && addTombstone(b, messageId, b.now)) capTombstones(b.db)
        b.changed(Kind.MESSAGES, found.second)
        return true
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

    /**
     * The conversation's snippet becomes [text] if [messageId] is its latest
     * message; for a [systemRow] (which says who itself) without "You:" or a
     * sender before it.
     */
    private fun followSnippet(b: Batch, conversationId: String, messageId: String, text: String, systemRow: Boolean = false) {
        val updated = b.db.update("conversations", ContentValues().apply {
            put("snippet", text)
            if (systemRow) {
                put("snippet_from_me", 0)
                put("snippet_sender", "")
            }
            put("updated_at", b.now)
        }, "conversation_id = ? AND latest_message_id = ?", arrayOf(conversationId, messageId))
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
        MediaCache.deleteStaleParts(mediaDir, now)
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
        requireOwnDirectory(path)
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

    /**
     * [path] as a file, only if it is inside this archive's own media
     * directory: nothing else is ever deleted, and so never a file of
     * another service's archive, whose rows this one cannot see.
     */
    private fun mediaFileOrNull(path: String): File? = fileInside(path, mediaDir)

    private fun outboxFileOrNull(path: String): File? = fileInside(path, outboxDir)

    /**
     * Refuses to record a file that lies in another service's media or
     * outbox directory (a caller that forgot to say which service's
     * [MediaCache] directory it wanted). This archive could never clean such
     * a file up, since it only deletes inside its own directories, and the
     * other service's "delete everything" would take it away under it.
     */
    private fun requireOwnDirectory(path: String) {
        if (path.isEmpty()) return
        for (other in ChatService.entries) {
            if (other == service) continue
            val foreign = fileInside(path, MediaCache.mediaDir(filesDir, other)) != null ||
                fileInside(path, MediaCache.outboxDir(filesDir, other)) != null
            require(!foreign) { "a file in $other's directory cannot belong to $service's archive" }
        }
    }

    /**
     * The other files of a batch whose local copy has [extraJson] (iMessage's
     * [com.lightdecoder.imessage.direct.IMessageExtra.pending]): only ones inside the outbox.
     */
    private fun pendingOutboxFiles(extraJson: String): List<String> {
        if (!extraJson.contains("\"pending\"")) return emptyList()
        return com.lightdecoder.imessage.direct.IMessageExtra.parse(extraJson).pending.map { it.path }
            .filter { outboxFileOrNull(it) != null }
    }

    /** Whether a queued send's attachment copy is [path]. */
    private fun outboxUses(db: SQLiteDatabase, path: String): Boolean =
        query(db, "SELECT 1 FROM outbox_queue WHERE media_local_path = ? LIMIT 1", arrayOf(path)) { true }.isNotEmpty()

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
            "contacts", "outbox_queue", "connection_health", "deleted_guids",
        )

        /** How long a conversation stays in Recently Deleted before it is deleted for good ([purgeExpired]). */
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000

        /** The most deleted message ids kept ([tombstone]): the oldest go first. */
        const val MAX_TOMBSTONES = 5_000

        /** How many days a deleted message id is kept ([tombstone]). */
        const val TOMBSTONE_DAYS = 60
        private const val TOMBSTONE_MS = TOMBSTONE_DAYS * 24L * 60 * 60 * 1000

        /** How many of a conversation's newest message ids are kept when it is deleted for good. */
        const val TOMBSTONES_PER_CONVERSATION = 2_000

        /** The ids kept when a message is deleted: iMessage's (Apple may hand one over again). */
        private const val TOMBSTONED_PREFIX = "imsg:"

        /** How a deleted chat is marked among the deleted ids ([conversationDeletedAt]): never a message's id, which starts with [TOMBSTONED_PREFIX]. */
        private const val CHAT_MARK = "chat:"
        private const val NOT_CHAT_MARK = "substr(guid, 1, 5) != 'chat:'"

        /** The most deleted chats' marks kept ([conversationDeletedAt]): the oldest go first. */
        const val MAX_CHAT_TOMBSTONES = 2_000

        /** The longest id kept as deleted (an iMessage GUID with its prefix, and some room). */
        private const val MAX_TOMBSTONE_CHARS = 128

        /** The process's archive of each service, opened on first use. */
        private val instances = ConcurrentHashMap<ChatService, ArchiveRepository>()

        /**
         * The archive of [service]: Google Messages' in [ArchiveDb.NAME] (as
         * before there was a second service), WhatsApp's in
         * [ArchiveDb.NAME_WHATSAPP]. One per service and process. Cheap, and
         * safe on the main thread: the database is only opened by the first
         * read or write.
         */
        fun get(context: Context, service: ChatService = ChatService.MESSAGES): ArchiveRepository =
            instances[service] ?: instances.computeIfAbsent(service) {
                ArchiveRepository(ArchiveDb(context, ArchiveDb.nameFor(it)), it)
            }

        /**
         * A repository over a database of the test's choosing, independent of
         * [get]. [service] is whose archive it stands for: it decides the
         * media directories and what its changes are announced as.
         */
        fun forTesting(db: ArchiveDb, service: ChatService = ChatService.MESSAGES): ArchiveRepository =
            ArchiveRepository(db, service)

        /**
         * Makes [get] return [repo] for [service] (tests only), so screens,
         * which always use [get], read the archive the test seeded and the
         * manager writes. [repo] must have been made for that service
         * ([forTesting]): one announced as, and keeping its files with,
         * another service would be told apart from nothing.
         */
        internal fun installForTesting(repo: ArchiveRepository, service: ChatService = repo.service) {
            require(repo.service == service) { "the repository is ${repo.service}'s, not $service's" }
            instances[service] = repo
        }

        /** Forgets the process instances of every service (tests only); the next [get] opens one for the current app. */
        internal fun resetForTesting() = instances.clear()

        /** The range of message ids the FaceTime calls' rows have ("ftcall:" up to the next character after ':'). */
        private const val CALL_ROWS_FROM = "ftcall:"
        private const val CALL_ROWS_TO = "ftcall;"

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
         * [reactions] after [participantId] (and, when [fromMe], the user)
         * reacted with [emoji] ([enable]) or took it back: see [applyReaction].
         */
        internal fun withReaction(
            reactions: List<ReactionDto>,
            participantId: String,
            emoji: String,
            enable: Boolean,
            fromMe: Boolean,
        ): List<ReactionDto> {
            val out = ArrayList<ReactionDto>()
            for (r in reactions) {
                if (!enable && r.emoji != emoji) {
                    out += r
                    continue
                }
                val left = r.copy(participantIds = r.participantIds - participantId, fromMe = r.fromMe && !fromMe)
                if (left.participantIds.isNotEmpty() || left.fromMe) out += left
            }
            if (enable && emoji.isNotEmpty()) {
                val i = out.indexOfFirst { it.emoji == emoji }
                if (i < 0) {
                    out += ReactionDto(emoji, listOf(participantId), fromMe)
                } else {
                    out[i] = out[i].copy(participantIds = out[i].participantIds + participantId, fromMe = out[i].fromMe || fromMe)
                }
            }
            return out
        }

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
    deletedAt = long("deleted_at"),
    firstUnreadTs = long("first_unread_ts"),
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
    editedAtMs = long("edited_at"),
    extra = str("extra_json"),
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
