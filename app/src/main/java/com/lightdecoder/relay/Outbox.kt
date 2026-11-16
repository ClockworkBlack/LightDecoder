package com.lightdecoder.relay

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MediaCache
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.archive.OutboxRow
import com.lightdecoder.archive.OutboxStatus
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream

/**
 * Messages waiting to be sent, kept in the archive's outbox_queue so they
 * survive the app being killed.
 *
 * Every send is keyed by a tmpId chosen here before anything goes to the
 * phone. The phone echoes it on the real message, which replaces the local
 * copy "tmp:<tmpId>" (see [ArchiveRepository.upsertMessage]); that is what
 * makes a retry after an interruption safe to reason about.
 *
 * Intake ([enqueueText], [enqueueMedia], [retry], [discard]) runs on one
 * thread so sends keep the order they were made in; [drain] runs on one
 * coroutine and sends one message at a time, in queue order.
 */
internal class Outbox(
    private val context: Context,
    private val repo: ArchiveRepository,
    private val bridge: () -> RelayBridge,
    private val retryDelaysMs: List<Long>,
    private val canSend: () -> Boolean,
    /** The bridge said the link is down: stop trying until the next connection. */
    private val onOffline: () -> Unit,
) {
    /** What became of one send attempt. */
    private sealed class Verdict {
        object Sent : Verdict()

        /** Worth another try after a pause (the phone said "try again", or the upload broke off). */
        data class Retry(val reason: String) : Verdict()

        /** The link is down; the message waits for the next connection. */
        data class Offline(val reason: String) : Verdict()

        /** Will not work by trying again on its own; the user can retry or discard. */
        data class Failed(val reason: String) : Verdict()
    }

    /** Taken while an entry is claimed for sending, and by [retry] and [discard], so they never race. */
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
     * attachment that cannot be read, or is over [MAX_ATTACHMENT_BYTES], is
     * queued as failed so the user sees it and can discard it.
     */
    fun enqueueMedia(tmpId: String, conversationId: String, uri: Uri, caption: String?, replyToId: String?, nowMs: Long) {
        val file = MediaCache.outboxFile(context, tmpId)
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
     * After unpairing: nothing queued under the old pairing goes out by
     * itself once a pairing exists again. Conversation ids are only
     * meaningful on the phone they came from (another phone can have a
     * different person behind the same id), so each send waits as failed for
     * the user to retry or discard. An entry being sent right now is
     * included: its sender only puts back entries that are still SENDING.
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
        val tried = HashSet<String>()
        while (canSend()) {
            val row = repo.pendingOutbox().firstOrNull { it.idempotencyKey !in tried } ?: return
            tried.add(row.idempotencyKey)
            if (!sendOne(row)) return
        }
    }

    /** Returns false when the link went down and draining should stop. */
    private suspend fun sendOne(queued: OutboxRow): Boolean {
        val key = queued.idempotencyKey
        val row = claim(key) ?: return true
        val payload = try {
            payloadOf(row)
        } catch (e: OutOfMemoryError) {
            fail(row, RelayException.TOO_LARGE)
            return true
        } catch (e: java.io.IOException) {
            // Otherwise the entry would stay SENDING and stall the queue behind it.
            fail(row, "attachment_unreadable")
            return true
        }
        if (payload == null) {
            fail(row, if (File(row.mediaLocalPath).length() > MAX_ATTACHMENT_BYTES) RelayException.TOO_LARGE else "attachment_missing")
            return true
        }
        var attempt = 0
        while (true) {
            repo.updateOutbox(key, OutboxStatus.SENDING, attemptsDelta = 1)
            when (val verdict = attempt(row, payload)) {
                Verdict.Sent -> {
                    // Removes the entry at once if the echo already arrived;
                    // otherwise the echo removes it. The local copy stays
                    // "sending" until then.
                    repo.updateOutbox(key, OutboxStatus.SENT)
                    return true
                }
                is Verdict.Retry -> {
                    if (attempt >= retryDelaysMs.size) {
                        fail(row, verdict.reason)
                        return true
                    }
                    RelayLog.add(context, "Send of a ${kindOf(row)} failed (${verdict.reason}), retrying")
                    delay(retryDelaysMs[attempt])
                    attempt++
                    // The phone may have sent it after all (an upload or
                    // response that broke off): its echo replaced the local
                    // copy during the pause, and sending again would
                    // deliver it twice.
                    if (alreadyArrived(key)) return true
                    // Held by unpairing during the pause: it waits for the user now.
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
     * An entry found SENDING was interrupted mid-send (the app died). If its
     * local copy "tmp:<key>" is gone, the phone's echo already replaced it:
     * the message went out, so it is not sent again. The same holds for a
     * PENDING entry whose local copy is gone (the echo of an earlier attempt).
     */
    private fun claim(key: String): OutboxRow? = synchronized(claimLock) {
        val row = repo.outbox(key) ?: return null
        if (row.status != OutboxStatus.PENDING && row.status != OutboxStatus.SENDING) return null
        if (repo.message(MessageRow.tmpMessageId(key)) == null) {
            repo.deleteOutbox(key)
            deleteCopy(key)
            if (row.status == OutboxStatus.SENDING) RelayLog.add(context, "Interrupted send had already arrived")
            return null
        }
        repo.updateOutbox(key, OutboxStatus.SENDING)
        repo.setLocalState(key, LocalState.SENDING)
        row
    }

    /**
     * True if the local copy "tmp:<key>" is gone, i.e. the phone's echo
     * replaced it: the entry and its attachment copy are then forgotten.
     */
    private fun alreadyArrived(key: String): Boolean = synchronized(claimLock) {
        if (repo.message(MessageRow.tmpMessageId(key)) != null) return false
        repo.deleteOutbox(key)
        deleteCopy(key)
        true
    }

    private fun attempt(row: OutboxRow, payload: ByteArray): Verdict {
        val result = try {
            if (isMedia(row)) {
                bridge().sendMedia(
                    row.conversationId, row.idempotencyKey, payload,
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
        return when {
            result.ok -> Verdict.Sent
            // The connector's transient statuses: the phone asks to try again.
            result.status in TRANSIENT_STATUSES -> Verdict.Retry(result.status)
            else -> Verdict.Failed(result.status.ifEmpty { result.code.ifEmpty { RelayException.SEND_FAILED } })
        }
    }

    private fun verdictFor(code: String): Verdict = when (code) {
        RelayException.NOT_CONNECTED, RelayException.CONNECTION_CLOSED, RelayException.NOT_PAIRED,
        RelayException.LOGGED_OUT, RelayException.COOKIES_INVALID -> Verdict.Offline(code)
        // Upload broke off, or something unexpected: worth the connector's retries.
        RelayException.SEND_FAILED, RelayException.INTERNAL -> Verdict.Retry(code)
        // phone_not_responding: Google accepted it and the phone may still send
        // it later. Not retried automatically (that could send it twice); if
        // the phone does send it, its echo clears the failure.
        else -> Verdict.Failed(code)
    }

    // The message's state is written before the queue entry's in requeue() and
    // fail(), so whoever sees the entry's new status also sees the message's.

    private fun requeue(row: OutboxRow, reason: String) = synchronized(claimLock) {
        // Only an entry still being sent goes back: [holdAll] may have failed it meanwhile.
        if (repo.outbox(row.idempotencyKey)?.status != OutboxStatus.SENDING) return
        repo.setLocalState(row.idempotencyKey, LocalState.PENDING)
        repo.updateOutbox(row.idempotencyKey, OutboxStatus.PENDING, error = reason)
    }

    /** Marks a send failed for the user to retry or discard. If the phone already has it, just forgets it. */
    private fun fail(row: OutboxRow, reason: String) {
        val key = row.idempotencyKey
        synchronized(claimLock) {
            if (repo.message(MessageRow.tmpMessageId(key)) == null) {
                repo.deleteOutbox(key)
                deleteCopy(key)
                return
            }
            repo.setLocalState(key, LocalState.FAILED, reason)
            repo.updateOutbox(key, OutboxStatus.FAILED, error = reason)
        }
        RelayLog.add(context, "Send of a ${kindOf(row)} failed: $reason")
    }

    /** The bytes to send: the attachment for a media entry (null if it is gone or too large), else nothing. */
    private fun payloadOf(row: OutboxRow): ByteArray? {
        if (!isMedia(row)) return EMPTY
        val file = File(row.mediaLocalPath)
        if (!file.isFile || file.length() > MAX_ATTACHMENT_BYTES || file.length() == 0L) return null
        return file.readBytes()
    }

    private fun isMedia(row: OutboxRow) = row.mediaLocalPath.isNotEmpty() || row.mediaName.isNotEmpty()

    private fun kindOf(row: OutboxRow) = if (isMedia(row)) "attachment" else "message"

    private fun deleteCopy(tmpId: String) {
        MediaCache.outboxFile(context, tmpId).delete()
    }

    // --- reading the attachment -----------------------------------------------------

    /** Copies [uri] into [target]; returns an error code, or null on success. */
    private fun copyCapped(uri: Uri, target: File): String? = try {
        val input = context.contentResolver.openInputStream(uri) ?: return "attachment_unreadable"
        input.use { stream ->
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_ATTACHMENT_BYTES) return RelayException.TOO_LARGE
                    out.write(buffer, 0, n)
                }
                if (total == 0L) return "attachment_unreadable"
            }
        }
        null
    } catch (e: SecurityException) {
        "attachment_unreadable"
    } catch (e: java.io.IOException) {
        "attachment_unreadable"
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
        /** Attachments larger than this are refused (the Go side refuses them too). */
        const val MAX_ATTACHMENT_BYTES = 100L * 1024 * 1024

        private val TRANSIENT_STATUSES = setOf("FAILURE_2", "FAILURE_3")
        private val EMPTY = ByteArray(0)
    }
}
