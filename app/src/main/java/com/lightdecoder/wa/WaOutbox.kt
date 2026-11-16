package com.lightdecoder.wa

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.archive.OutboxRow
import com.lightdecoder.archive.OutboxStatus
import com.lightdecoder.relay.ArchiveGate
import com.lightdecoder.relay.Cursor
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.Outbox
import com.lightdecoder.relay.RelayException
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream

/**
 * WhatsApp messages waiting to be sent, kept in the WhatsApp archive's
 * outbox_queue so they survive the app being killed. The counterpart of the
 * Google Messages link's [Outbox], for a bridge that answers a send with
 * the sent message itself instead of an echo later.
 *
 * Every send is keyed by a tmpId chosen here before anything goes to
 * WhatsApp. The bridge returns the sent message with that tmpId, and
 * storing it replaces the local copy "tmp:<tmpId>" (see
 * [ArchiveRepository.upsertMessage]).
 *
 * Intake ([enqueueText], [enqueueMedia], [retry], [discard]) runs on one
 * thread so sends keep the order they were made in; [drain] runs on one
 * coroutine and sends one message at a time, in queue order, only while
 * [canSend] says the link is connected.
 *
 * Nothing is ever sent twice without the user saying so. The bridge makes a
 * new WhatsApp message id for every send call, so a second call for the
 * same message is a second message for the recipient. Therefore:
 *  - a send the bridge says did not go out (not connected, refused, too
 *    large) waits for the next connection, is retried a few times, or
 *    waits as failed, by the kind of failure;
 *  - a send whose outcome is unknown (WhatsApp did not answer in time, the
 *    connection closed while it was on its way, the bridge failed in an
 *    unexpected way) waits as failed with [Outbox.UNCONFIRMED], which the
 *    screens treat as "may have been sent" and ask before sending again;
 *  - an entry a previous run left SENDING (the app died mid-send) is looked
 *    up in the bridge's own store of sent messages first ([reconcile]); if
 *    it is there it arrived, otherwise it waits as failed with
 *    [Outbox.INTERRUPTED], asked about in the same way.
 *
 * Attachments are copied into the WhatsApp archive's own outbox directory
 * at intake and handed to the bridge by path: their bytes never pass
 * through the app's heap. Once sent, the copy becomes the message's local
 * file, so what was just sent shows without being downloaded again.
 */
internal class WaOutbox(
    private val context: Context,
    private val repo: ArchiveRepository,
    private val bridge: () -> WaBridge,
    private val retryDelaysMs: List<Long>,
    private val canSend: () -> Boolean,
    private val gate: ArchiveGate,
    /** The bridge said the link is down: stop trying until the next connection. */
    private val onOffline: () -> Unit,
    /** A message went out and was stored (receipts that came before it can now be applied). */
    private val onSent: (MessageDto) -> Unit = {},
) {
    /** What became of one send attempt. */
    private sealed class Verdict {
        class Sent(val message: MessageDto, val since: Long) : Verdict()

        /** Did not go out, and worth another try after a pause. */
        data class Retry(val reason: String) : Verdict()

        /** The link is down; the message waits for the next connection. */
        data class Offline(val reason: String) : Verdict()

        /** Will not work by trying again on its own (or may have gone out); the user can retry or discard. */
        data class Failed(val reason: String) : Verdict()
    }

    /** Taken while an entry is claimed for sending, and by [retry], [discard] and [holdAll], so they never race. */
    private val claimLock = Any()

    // --- intake (one thread) -----------------------------------------------------

    fun enqueueText(tmpId: String, conversationId: String, text: String, replyToId: String?, nowMs: Long) {
        repo.insertLocalOutgoing(tmpId, conversationId, text, null, null, null, replyToId, nowMs)
        repo.enqueueOutbox(
            OutboxRow(
                idempotencyKey = tmpId,
                conversationId = conversationId,
                textBody = text,
                replyToId = replyToId.orEmpty(),
                createdAt = nowMs,
            )
        )
    }

    /**
     * Copies the attachment into app storage first, so the send (and any
     * retry, even after a restart) no longer depends on the Uri's grant. An
     * attachment that cannot be read, or is over
     * [Outbox.MAX_ATTACHMENT_BYTES], is queued as failed so the user sees it
     * and can discard it.
     */
    fun enqueueMedia(tmpId: String, conversationId: String, uri: Uri, caption: String?, replyToId: String?, nowMs: Long) {
        val file = repo.outboxFileFor(tmpId)
        val name = displayName(uri)
        val mime = mimeOf(uri, name)
        val error = copyCapped(uri, file)
        if (error != null) file.delete()
        val text = caption?.trim().orEmpty()
        repo.insertLocalOutgoing(tmpId, conversationId, text, file.path, mime, name, replyToId, nowMs)
        repo.enqueueOutbox(
            OutboxRow(
                idempotencyKey = tmpId,
                conversationId = conversationId,
                textBody = text,
                mediaLocalPath = file.path,
                mediaMime = mime,
                mediaName = name,
                replyToId = replyToId.orEmpty(),
                status = if (error == null) OutboxStatus.PENDING else OutboxStatus.FAILED,
                lastError = error.orEmpty(),
                createdAt = nowMs,
            )
        )
        if (error != null) repo.setLocalState(tmpId, LocalState.FAILED, error)
    }

    /** Puts a failed send back in the queue. False if there is nothing failed to retry. */
    fun retry(tmpId: String): Boolean = synchronized(claimLock) {
        val row = repo.outbox(tmpId) ?: return false
        if (row.status != OutboxStatus.FAILED) return false
        repo.setLocalState(tmpId, LocalState.PENDING)
        repo.updateOutbox(tmpId, OutboxStatus.PENDING)
        true
    }

    /**
     * Drops a send that has not gone out, with its local copy and attachment.
     * One being sent right now is left alone (its outcome is about to be known).
     */
    fun discard(tmpId: String): Boolean = synchronized(claimLock) {
        val row = repo.outbox(tmpId)
        if (row?.status == OutboxStatus.SENDING) return false
        if (row != null) repo.deleteOutbox(tmpId)
        repo.applyDeletion(MessageRow.tmpMessageId(tmpId))
        deleteCopy(tmpId)
        true
    }

    /**
     * After unlinking, or linking again when it is not known to be the same
     * account: nothing queued goes out by itself. Each send waits as failed
     * for the user to retry or discard. An entry being sent right now is
     * included: if it goes out all the same, its message is stored and the
     * entry forgotten ([settle]).
     */
    fun holdAll(reason: String) = synchronized(claimLock) {
        for (row in repo.pendingOutbox()) {
            repo.setLocalState(row.idempotencyKey, LocalState.FAILED, reason)
            repo.updateOutbox(row.idempotencyKey, OutboxStatus.FAILED, error = reason)
        }
    }

    // --- sending (one coroutine) --------------------------------------------------

    /** Sends queued messages in order for as long as the link can send. */
    suspend fun drain() {
        if (!canSend()) return
        // Found SENDING before this drain sent anything: an earlier send was interrupted.
        val interrupted = repo.pendingOutbox()
            .filter { it.status == OutboxStatus.SENDING }
            .mapTo(HashSet()) { it.idempotencyKey }
        if (!reconcile(interrupted)) return
        val tried = HashSet<String>()
        while (canSend()) {
            val row = repo.pendingOutbox().firstOrNull { it.idempotencyKey !in tried } ?: return
            tried.add(row.idempotencyKey)
            if (!sendOne(row, row.idempotencyKey in interrupted)) return
        }
    }

    /**
     * Looks [interrupted] sends up in the bridge's own store, where a
     * message that went out is recorded with its tmpId before the send call
     * returns: one found there arrived just as the app died, and storing it
     * replaces the local copy. The ones not found are settled by [claim].
     * Returns false if the link went down.
     */
    private suspend fun reconcile(interrupted: Set<String>): Boolean {
        val byConversation = LinkedHashMap<String, MutableMap<String, Long>>()
        for (key in interrupted) {
            val row = repo.outbox(key) ?: continue
            byConversation.getOrPut(row.conversationId) { HashMap() }[key] = row.createdAt
        }
        for ((conversationId, keys) in byConversation) {
            try {
                lookUpSent(conversationId, keys.keys, keys.values.min() - CLOCK_SLACK_MS)
            } catch (e: RelayException) {
                if (verdictFor(e.code) is Verdict.Offline) {
                    onOffline()
                    return false
                }
                // Unanswered: the entries wait for the user (claim).
                WaLog.add(context, "Could not look interrupted sends up: ${e.code}")
            }
        }
        return true
    }

    /**
     * Pages back through what the bridge has stored for a conversation,
     * newest first, until the messages sent as [keys] are found, or the
     * pages reach [notBefore] (before any of them can have been sent), or
     * the store's start, or [LOOKUP_PAGES] pages. Only those messages are
     * stored. Each page asks for no more than the store holds, so the bridge
     * is never led to ask the phone for older history over this.
     */
    private suspend fun lookUpSent(conversationId: String, keys: Set<String>, notBefore: Long) {
        val missing = HashSet(keys)
        val probe = bridge().fetchMessages(conversationId, 1, null)
        var left = probe.total.coerceAtLeast(probe.messages.size)
        var cursor: Cursor? = null
        var pages = 0
        while (left > 0 && missing.isNotEmpty() && pages++ < LOOKUP_PAGES) {
            if (pages > 1) delay(LOOKUP_PAGE_PAUSE_MS)
            val since = gate.epoch()
            val page = bridge().fetchMessages(conversationId, minOf(LOOKUP_PAGE, left), cursor)
            if (page.messages.isEmpty()) return
            left -= page.messages.size
            val found = page.messages.filter { it.tmpId.isNotEmpty() && it.tmpId in missing && it.id.isNotEmpty() && !it.isDeleted }
            if (found.isNotEmpty()) {
                gate.ifCurrent(since) { repo.upsertMessages(found) }
                found.forEach {
                    missing.remove(it.tmpId)
                    onSent(it)
                }
            }
            if (page.messages.any { it.timestampMs < notBefore }) return
            cursor = page.cursor ?: return
        }
    }

    /** Returns false when the link went down and draining should stop. */
    private suspend fun sendOne(queued: OutboxRow, interrupted: Boolean): Boolean {
        val key = queued.idempotencyKey
        val row = claim(key, interrupted) ?: return true
        attachmentProblem(row)?.let { problem ->
            // Otherwise the entry would stay SENDING and stall the queue behind it.
            fail(row, problem)
            return true
        }
        var attempt = 0
        while (true) {
            repo.updateOutbox(key, OutboxStatus.SENDING, attemptsDelta = 1)
            when (val verdict = attempt(row)) {
                is Verdict.Sent -> {
                    settle(row, verdict)
                    return true
                }
                is Verdict.Retry -> {
                    if (attempt >= retryDelaysMs.size) {
                        fail(row, verdict.reason)
                        return true
                    }
                    WaLog.add(context, "Send of a ${kindOf(row)} failed (${verdict.reason}), retrying")
                    delay(retryDelaysMs[attempt])
                    attempt++
                    // Held (unlinking), discarded with the archive, or settled during the pause.
                    if (repo.outbox(key)?.status != OutboxStatus.SENDING) return true
                    if (!canSend()) {
                        requeue(row, verdict.reason)
                        return false
                    }
                }
                is Verdict.Offline -> {
                    requeue(row, verdict.reason)
                    onOffline()
                    return false
                }
                is Verdict.Failed -> {
                    fail(row, verdict.reason)
                    return true
                }
            }
        }
    }

    /**
     * Marks an entry SENDING, or returns null if there is nothing to send.
     *
     * An entry whose local copy "tmp:<key>" is gone was sent: the message
     * that replaced the copy is stored ([reconcile] found it, say), so it is
     * not sent again.
     *
     * An [interrupted] entry whose copy is still there may have gone out all
     * the same (the app died after WhatsApp took the message and before the
     * bridge recorded it). Sending it again could deliver it twice, so it
     * waits as failed ("interrupted") for the user.
     */
    private fun claim(key: String, interrupted: Boolean): OutboxRow? = synchronized(claimLock) {
        val row = repo.outbox(key) ?: return null
        if (row.status != OutboxStatus.PENDING && row.status != OutboxStatus.SENDING) return null
        if (repo.message(MessageRow.tmpMessageId(key)) == null) {
            repo.deleteOutbox(key)
            deleteCopy(key)
            if (row.status == OutboxStatus.SENDING) WaLog.add(context, "Interrupted send had already arrived")
            return null
        }
        if (interrupted && row.status == OutboxStatus.SENDING) {
            repo.setLocalState(key, LocalState.FAILED, Outbox.INTERRUPTED)
            repo.updateOutbox(key, OutboxStatus.FAILED, error = Outbox.INTERRUPTED)
            WaLog.add(context, "Interrupted send of a ${kindOf(row)} waits for the user")
            return null
        }
        repo.updateOutbox(key, OutboxStatus.SENDING)
        repo.setLocalState(key, LocalState.SENDING)
        row
    }

    private fun attempt(row: OutboxRow): Verdict {
        val since = gate.epoch()
        val sent = try {
            if (isMedia(row)) {
                // The bridge reads the file itself (at most 100 MB, as checked at intake).
                bridge().sendMediaFile(
                    row.conversationId, row.idempotencyKey, row.mediaLocalPath,
                    row.mediaName.ifEmpty { "attachment" }, row.mediaMime,
                    row.textBody.ifBlank { null }, row.replyToId.ifEmpty { null },
                )
            } else {
                bridge().sendText(row.conversationId, row.idempotencyKey, row.textBody, row.replyToId.ifEmpty { null })
            }
        } catch (e: RelayException) {
            return verdictFor(e.code)
        } catch (e: OutOfMemoryError) {
            return Verdict.Failed(RelayException.TOO_LARGE)
        }
        // The bridge answered, so the message went out. One it describes without an id
        // cannot be stored in place of the local copy; all that is known is that it was sent.
        if (sent.id.isEmpty() || sent.isDeleted) return Verdict.Failed(Outbox.UNCONFIRMED)
        return Verdict.Sent(sent.copy(tmpId = row.idempotencyKey, conversationId = sent.conversationId.ifEmpty { row.conversationId }), since)
    }

    private fun verdictFor(code: String): Verdict = when (code) {
        // Said before anything was sent: the message waits for the next connection.
        RelayException.NOT_CONNECTED, RelayException.NOT_PAIRED, RelayException.LOGGED_OUT -> Verdict.Offline(code)
        // WhatsApp refused it, or the upload broke off: it did not go out.
        RelayException.SEND_FAILED -> Verdict.Retry(code)
        // No answer in time, the connection closed under it, or the bridge failed in a
        // way it does not know: the message may be on its way all the same.
        WaErrors.TIMEOUT, RelayException.CONNECTION_CLOSED, RelayException.INTERNAL -> Verdict.Failed(Outbox.UNCONFIRMED)
        // Everything else is a plain "no" (too large, not on WhatsApp, not allowed, ...).
        else -> Verdict.Failed(code)
    }

    /**
     * The message went out: stores it in place of the local copy, keeps the
     * attachment's copy as its local file, and forgets the queue entry.
     * Nothing is stored if the archive was deleted while the send ran.
     */
    private fun settle(row: OutboxRow, sent: Verdict.Sent) {
        val key = row.idempotencyKey
        val stored = gate.ifCurrent(sent.since) {
            repo.upsertMessage(sent.message)
            if (isMedia(row)) keepAttachment(row, sent.message)
            true
        } ?: false
        synchronized(claimLock) {
            when (repo.outbox(key)?.status) {
                // Removes the entry (and what is left of its attachment copy): the local copy is gone.
                OutboxStatus.SENDING -> repo.updateOutbox(key, OutboxStatus.SENT)
                // Held while it was being sent (unlinking): it went out all the same.
                OutboxStatus.FAILED -> if (repo.message(MessageRow.tmpMessageId(key)) == null) {
                    repo.deleteOutbox(key)
                    deleteCopy(key)
                } else {
                    // The message could not be stored: all that is known is that it was sent.
                    repo.setLocalState(key, LocalState.FAILED, Outbox.UNCONFIRMED)
                    repo.updateOutbox(key, OutboxStatus.FAILED, error = Outbox.UNCONFIRMED)
                }
                // Gone: the archive was deleted meanwhile.
                else -> Unit
            }
        }
        if (stored) onSent(sent.message)
    }

    /**
     * Makes the attachment's outbox copy the sent message's local file: it
     * is what was sent, so the conversation shows it without downloading it
     * from WhatsApp again. Best effort; if anything fails the copy is simply
     * deleted with its queue entry and the screen downloads the file.
     */
    private fun keepAttachment(row: OutboxRow, sent: MessageDto) {
        val part = sent.mediaParts.firstOrNull { it.mediaId.isNotEmpty() } ?: return
        val copy = File(row.mediaLocalPath)
        if (!copy.isFile || copy.length() == 0L) return
        try {
            val head = try {
                copy.inputStream().use { input -> ByteArray(64).let { it.copyOf(input.read(it).coerceAtLeast(0)) } }
            } catch (e: java.io.IOException) {
                ByteArray(0)
            }
            val target = repo.mediaFileFor(part.mediaId, WaMedia.extensionFor(head, row.mediaMime, row.mediaName))
            repo.withMediaFileLock {
                if (!copy.renameTo(target)) return@withMediaFileLock
                try {
                    // When not recorded, the repository has already deleted the file.
                    repo.setMediaLocalPath(sent.id, part.partId, part.mediaId, target.path, thumbnail = false)
                } catch (e: RuntimeException) {
                    target.delete()
                    throw e
                }
            }
        } catch (e: RuntimeException) {
            WaLog.add(context, "Sent attachment could not be kept: ${e.javaClass.simpleName}")
        }
    }

    // The message's state is written before the queue entry's in requeue() and
    // fail(), so whoever sees the entry's new status also sees the message's.

    private fun requeue(row: OutboxRow, reason: String) = synchronized(claimLock) {
        // Only an entry still being sent goes back: [holdAll] may have failed it meanwhile.
        if (repo.outbox(row.idempotencyKey)?.status != OutboxStatus.SENDING) return
        repo.setLocalState(row.idempotencyKey, LocalState.PENDING)
        repo.updateOutbox(row.idempotencyKey, OutboxStatus.PENDING, error = reason)
    }

    /** Marks a send failed for the user to retry or discard. */
    private fun fail(row: OutboxRow, reason: String) {
        val key = row.idempotencyKey
        synchronized(claimLock) {
            if (repo.outbox(key) == null) return
            if (repo.message(MessageRow.tmpMessageId(key)) == null) {
                repo.deleteOutbox(key)
                deleteCopy(key)
                return
            }
            repo.setLocalState(key, LocalState.FAILED, reason)
            repo.updateOutbox(key, OutboxStatus.FAILED, error = reason)
        }
        WaLog.add(context, "Send of a ${kindOf(row)} failed: $reason")
    }

    /** Why a media entry's attachment cannot be sent (gone, empty, too large); null if it can, or for text. */
    private fun attachmentProblem(row: OutboxRow): String? {
        if (!isMedia(row)) return null
        val file = File(row.mediaLocalPath)
        return when {
            row.mediaLocalPath.isEmpty() || !file.isFile || file.length() == 0L -> ATTACHMENT_MISSING
            file.length() > Outbox.MAX_ATTACHMENT_BYTES -> RelayException.TOO_LARGE
            else -> null
        }
    }

    private fun isMedia(row: OutboxRow) = row.mediaLocalPath.isNotEmpty() || row.mediaName.isNotEmpty()

    private fun kindOf(row: OutboxRow) = if (isMedia(row)) "attachment" else "message"

    private fun deleteCopy(tmpId: String) {
        repo.outboxFileFor(tmpId).delete()
    }

    // --- reading the attachment -----------------------------------------------------

    /** Copies [uri] into [target]; returns an error code, or null on success. */
    private fun copyCapped(uri: Uri, target: File): String? = try {
        val input = context.contentResolver.openInputStream(uri) ?: return ATTACHMENT_UNREADABLE
        input.use { stream ->
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > Outbox.MAX_ATTACHMENT_BYTES) return RelayException.TOO_LARGE
                    out.write(buffer, 0, n)
                }
                if (total == 0L) return ATTACHMENT_UNREADABLE
            }
        }
        null
    } catch (e: SecurityException) {
        ATTACHMENT_UNREADABLE
    } catch (e: java.io.IOException) {
        ATTACHMENT_UNREADABLE
    }

    private fun displayName(uri: Uri): String {
        val fromProvider = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
        return (fromProvider ?: uri.lastPathSegment).orEmpty()
            .substringAfterLast('/')
            .filterNot { it.isISOControl() }
            .take(120)
            .ifBlank { "attachment" }
    }

    private fun mimeOf(uri: Uri, name: String): String {
        val fromProvider = try {
            context.contentResolver.getType(uri)
        } catch (e: Exception) {
            null
        }
        if (!fromProvider.isNullOrBlank()) return fromProvider
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension).orEmpty()
    }

    companion object {
        /** Content-free reasons a queued attachment cannot be sent, as the Google Messages outbox words them. */
        const val ATTACHMENT_MISSING = "attachment_missing"
        const val ATTACHMENT_UNREADABLE = "attachment_unreadable"

        /** Messages per page, and at most this many pages, when looking an interrupted send up. */
        private const val LOOKUP_PAGE = 50
        private const val LOOKUP_PAGES = 10
        private const val LOOKUP_PAGE_PAUSE_MS = 20L

        /** The bridge stamps a sent message with WhatsApp's time, which can be a little off this device's. */
        private const val CLOCK_SLACK_MS = 10 * 60 * 1000L
    }
}
