package com.lightdecoder.relay

import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.archive.SyncStateRow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Imports message history from the phone into the archive, page by page.
 *
 * It is deliberately polite: one request at a time, with a pause between
 * pages and between conversations, because every page is answered by the
 * user's phone over its own connection. Where each conversation's import got
 * to is kept in sync_state, so an import that was interrupted (disconnect,
 * app killed, cancelled) carries on from there next time instead of starting
 * over.
 *
 * Only plain bridge reads and archive writes happen here; the caller decides
 * when to run, and catches [RelayException].
 */
internal class Backfill(
    private val repo: ArchiveRepository,
    private val bridge: () -> RelayBridge,
    private val pagePauseMs: Long,
    private val conversationPauseMs: Long,
    private val progress: MutableStateFlow<BackfillProgress>,
) {

    /**
     * Imports the newest [BackfillDepth.conversations] inbox conversations,
     * up to [BackfillDepth.messagesPerConversation] messages each (counting
     * what the archive already has). Returns the number of messages fetched.
     */
    suspend fun run(depth: BackfillDepth): Int {
        if (depth == BackfillDepth.OFF) return 0
        val conversations = bridge().listConversations(Folder.INBOX, depth.conversations).take(depth.conversations)
        repo.upsertConversations(conversations)
        var messages = 0
        currentCoroutineContext().ensureActive()
        progress.value = BackfillProgress(true, 0, conversations.size, 0)
        for ((index, conversation) in conversations.withIndex()) {
            currentCoroutineContext().ensureActive()
            messages += importConversation(conversation.id, depth.messagesPerConversation)
            // A cancelled run may only notice once a page it was waiting for
            // arrives; it must not overwrite the progress of the next run.
            currentCoroutineContext().ensureActive()
            progress.value = BackfillProgress(true, index + 1, conversations.size, messages)
            if (index < conversations.lastIndex) delay(conversationPauseMs)
        }
        return messages
    }

    /**
     * Fetches the page before the oldest message known for a conversation.
     * Returns how many messages came back; 0 means the start of the
     * conversation has been reached.
     */
    fun loadOlder(conversationId: String, count: Int): Int {
        val sync = repo.syncState(conversationId)
        if (sync?.historyComplete == true) return 0
        val cursor = olderOf(sync?.cursor, oldestStoredCursor(conversationId))
        val page = bridge().fetchMessages(conversationId, count.coerceIn(1, PAGE_SIZE), cursor)
        repo.upsertMessages(page.messages)
        repo.setSyncState(nextSyncState(conversationId, sync, cursor, page))
        return page.messages.size
    }

    /** Pages back through one conversation until it has [target] messages or its start is reached. */
    private suspend fun importConversation(conversationId: String, target: Int): Int {
        var sync = repo.syncState(conversationId)
        if (sync?.historyComplete == true) return 0
        var have = storedCount(conversationId, target)
        var fetched = 0
        // With nothing imported yet the newest page comes first: it also
        // fills in anything that arrived while the link was down.
        var cursor = sync?.cursor
        while (have < target) {
            currentCoroutineContext().ensureActive()
            val page = bridge().fetchMessages(conversationId, (target - have).coerceIn(1, PAGE_SIZE), cursor)
            repo.upsertMessages(page.messages)
            fetched += page.messages.size
            sync = nextSyncState(conversationId, sync, cursor, page, System.currentTimeMillis())
            repo.setSyncState(sync)
            if (sync.historyComplete || page.messages.isEmpty()) break
            cursor = sync.cursor
            have = storedCount(conversationId, target)
            if (have < target) delay(pagePauseMs)
        }
        if (sync != null && sync.lastBackfillAt == 0L) {
            repo.setSyncState(sync.copy(lastBackfillAt = System.currentTimeMillis()))
        }
        return fetched
    }

    /**
     * Where the next older page starts. A page without a cursor means the
     * phone has nothing older: the history is complete. A complete history
     * keeps the last cursor (it is never used again) so the row stays readable.
     */
    private fun nextSyncState(
        conversationId: String,
        previous: SyncStateRow?,
        used: Cursor?,
        page: MessagePage,
        backfillAt: Long = previous?.lastBackfillAt ?: 0L,
    ): SyncStateRow {
        val next = page.cursor
        return SyncStateRow(
            conversationId = conversationId,
            cursorId = next?.lastItemId ?: used?.lastItemId.orEmpty(),
            cursorTs = next?.lastItemTimestampMs ?: used?.lastItemTimestampMs ?: 0L,
            historyComplete = next == null,
            lastBackfillAt = backfillAt,
        )
    }

    /** Messages from the phone stored for this conversation, counted up to [limit]. */
    private fun storedCount(conversationId: String, limit: Int): Int =
        repo.messages(conversationId, limit = limit).count { !it.isLocal }

    /**
     * A cursor at the oldest message the archive holds for this conversation
     * (live messages arrive without any history cursor). Like the mautrix
     * bridge's anchor cursor, it lets "load older" start below what is shown.
     */
    private fun oldestStoredCursor(conversationId: String): Cursor? {
        var before = Long.MAX_VALUE
        var oldest: MessageRow? = null
        while (true) {
            val batch = repo.messages(conversationId, beforeTs = before, limit = SCAN_BATCH)
            batch.firstOrNull { !it.isLocal }?.let { oldest = it }
            if (batch.size < SCAN_BATCH) break
            before = batch.first().timestampMs
        }
        return oldest?.let { Cursor(it.id, it.timestampMs) }
    }

    private fun olderOf(a: Cursor?, b: Cursor?): Cursor? = when {
        a == null -> b
        b == null -> a
        b.lastItemTimestampMs < a.lastItemTimestampMs -> b
        else -> a
    }

    companion object {
        /** Messages per request; the phone answers larger requests slowly. */
        const val PAGE_SIZE = 50
        private const val SCAN_BATCH = 500
    }
}
