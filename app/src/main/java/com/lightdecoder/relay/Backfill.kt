package com.lightdecoder.relay

import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.SyncStateRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * Every page is stored through [gate] with the epoch taken before asking, so
 * a page that comes back after the archive was deleted is dropped; and a run
 * that was cancelled while it waited for a page writes nothing more.
 *
 * Only plain bridge reads and archive writes happen here; the caller decides
 * when to run, and catches [RelayException].
 *
 * [pager] is shared with the connection's catch-ups, which page through the
 * phone too: never both at once. An import holds it for one conversation at
 * a time, so a catch-up waits for one conversation, not the whole import.
 */
internal class Backfill(
    private val repo: ArchiveRepository,
    private val bridge: () -> RelayBridge,
    private val gate: ArchiveGate,
    private val pagePauseMs: Long,
    private val conversationPauseMs: Long,
    private val progress: MutableStateFlow<BackfillProgress>,
    private val pager: Mutex = Mutex(),
) {
    /** What a run did: messages fetched, and conversations skipped because the phone kept failing on them. */
    data class Outcome(val messages: Int, val skipped: Int)

    /**
     * Imports the newest [BackfillDepth.conversations] inbox conversations,
     * up to [BackfillDepth.messagesPerConversation] messages each (counting
     * what the archive already has).
     *
     * A conversation the phone cannot serve (not found, a reply that does
     * not parse) is skipped, so one bad conversation does not stop the rest;
     * the thread's "load older" can still reach it. The run stops when the
     * link itself is down, or after two failures in a row (the phone is not
     * answering at all).
     */
    suspend fun run(depth: BackfillDepth): Outcome {
        if (depth == BackfillDepth.OFF) return Outcome(0, 0)
        val since = gate.epoch()
        val conversations = pager.withLock {
            bridge().listConversations(Folder.INBOX, depth.conversations).take(depth.conversations)
        }
        currentCoroutineContext().ensureActive()
        gate.ifCurrent(since) { repo.upsertConversations(conversations) } ?: throw ArchiveWiped()
        // Cancelled during that write: the progress belongs to whoever cancelled it now.
        currentCoroutineContext().ensureActive()
        var messages = 0
        var skipped = 0
        var failedInARow = 0
        progress.value = BackfillProgress(true, 0, conversations.size, 0)
        for ((index, conversation) in conversations.withIndex()) {
            currentCoroutineContext().ensureActive()
            try {
                messages += pager.withLock { importConversation(conversation.id, depth.messagesPerConversation) }
                failedInARow = 0
            } catch (e: RelayException) {
                if (e.code in ABORT_CODES || ++failedInARow >= 2) throw e
                skipped++
            }
            // A cancelled run may only notice once a page it was waiting for
            // arrives; it must not overwrite the progress of the next run.
            currentCoroutineContext().ensureActive()
            progress.value = BackfillProgress(true, index + 1, conversations.size, messages)
            if (index < conversations.lastIndex) delay(conversationPauseMs)
        }
        return Outcome(messages, skipped)
    }

    /**
     * Fetches the page before where the import got to (the newest page if
     * nothing was imported yet). Returns how many messages came back; 0 means
     * the start of the conversation has been reached.
     *
     * It never pages from the oldest message stored: a live update can store
     * one old message on its own (a reaction to something from last year),
     * and paging below it would skip everything between it and the import.
     * Messages already stored come back again harmlessly (stored by id).
     */
    fun loadOlder(conversationId: String, count: Int): Int {
        val sync = repo.syncState(conversationId)
        if (sync?.historyComplete == true) return 0
        val cursor = sync?.cursor
        val since = gate.epoch()
        val page = bridge().fetchMessages(conversationId, requestSize(count, cursor), cursor)
        return gate.ifCurrent(since) {
            repo.upsertMessages(page.messages)
            repo.setSyncState(nextSyncState(conversationId, sync, cursor, page))
            page.messages.size
        } ?: 0
    }

    /**
     * Fills a conversation's gap between [bound] and the phone's newest
     * message: what arrived while the link was down. Live events after a gap
     * are not replayed, so without this those messages would never be in
     * the thread or in search.
     *
     * [bound] is the timestamp of the newest message the archive had from
     * the phone when the link came back (the caller notes it then). Pages
     * back from the newest until a page reaches it (at most [maxPages]).
     * Only the bound ends the paging: messages stored since the link came
     * back (live ones, say) are on the first pages and are stored again
     * harmlessly, so they cannot hide the rest of the gap. The import's
     * cursor is never touched. [onStored] is told about each message stored
     * (for the sticker inbox); no notifications. Returns the number of
     * messages fetched.
     */
    suspend fun catchUp(
        conversationId: String,
        bound: Long,
        onStored: (MessageDto, ArchiveRepository.UpsertResult) -> Unit = { _, _ -> },
        maxPages: Int = CATCH_UP_PAGES,
    ): Int {
        var cursor: Cursor? = null
        var fetched = 0
        repeat(maxPages) {
            currentCoroutineContext().ensureActive()
            val since = gate.epoch()
            val page = bridge().fetchMessages(conversationId, PAGE_SIZE, cursor)
            currentCoroutineContext().ensureActive()
            if (page.messages.isEmpty()) return fetched
            gate.ifCurrent(since) {
                for (message in page.messages) onStored(message, repo.upsertMessage(message))
            } ?: throw ArchiveWiped()
            fetched += page.messages.size
            if (page.messages.any { it.timestampMs <= bound } || page.cursor == null) return fetched
            cursor = page.cursor
            delay(pagePauseMs)
        }
        // Hit the cap: what lies between is left to "load older" and the import.
        return fetched
    }

    /** Pages back through one conversation until it has [target] messages or its start is reached. */
    private suspend fun importConversation(conversationId: String, target: Int): Int {
        var sync = repo.syncState(conversationId)
        if (sync?.historyComplete == true) return 0
        var have = storedCount(conversationId, target)
        var fetched = 0
        // With nothing imported yet the newest page comes first.
        var cursor = sync?.cursor
        var since = gate.epoch()
        while (have < target) {
            currentCoroutineContext().ensureActive()
            since = gate.epoch()
            val page = bridge().fetchMessages(conversationId, requestSize(target - have, cursor), cursor)
            // A run cancelled while it waited must not store a stale cursor over a newer run's.
            currentCoroutineContext().ensureActive()
            val next = nextSyncState(conversationId, sync, cursor, page, System.currentTimeMillis())
            gate.ifCurrent(since) {
                repo.upsertMessages(page.messages)
                repo.setSyncState(next)
            } ?: throw ArchiveWiped()
            sync = next
            fetched += page.messages.size
            if (next.historyComplete || page.messages.isEmpty()) break
            cursor = next.cursor
            have = storedCount(conversationId, target)
            if (have < target) delay(pagePauseMs)
        }
        val finished = sync
        if (finished != null && finished.lastBackfillAt == 0L) {
            gate.ifCurrent(since) { repo.setSyncState(finished.copy(lastBackfillAt = System.currentTimeMillis())) }
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

    /** The archive was deleted while a page was on its way: this run is over. */
    class ArchiveWiped : CancellationException("archive deleted")

    companion object {
        /** Messages per request; the phone answers larger requests slowly. */
        const val PAGE_SIZE = 50

        /** At most this many pages (newest first) to fill one conversation's gap. */
        const val CATCH_UP_PAGES = 10

        /**
         * Messages to ask for. Below a cursor always a full page: the phone's
         * reply can start with the cursor's own message (or ones from the same
         * millisecond), which the bridge drops; a request for one or two
         * would then come back empty and without a cursor, and that would be
         * stored as "history complete" with years of history left.
         */
        fun requestSize(wanted: Int, cursor: Cursor?): Int =
            if (cursor == null) wanted.coerceIn(1, PAGE_SIZE) else PAGE_SIZE

        /** Failures that mean the link, not the conversation, is the problem: stop the whole run. */
        val ABORT_CODES = setOf(
            RelayException.NOT_CONNECTED, RelayException.CONNECTION_CLOSED, RelayException.NOT_PAIRED,
            RelayException.LOGGED_OUT, RelayException.COOKIES_INVALID,
        )
    }
}
