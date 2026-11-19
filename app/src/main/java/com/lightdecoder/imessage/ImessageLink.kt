package com.lightdecoder.imessage

import android.content.Context
import android.util.Log
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.PartDto
import com.lightdecoder.relay.RelayManager
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * iMessage in the Messages tab, main-process side. Everything that touches the Messages archive
 * is here; everything that needs OpenBubbles' library is asked of the ":imessage" process
 * ([ImessageService], over [ImessageIpc]), which never sees the archive.
 *
 * [RelayManager] hands every action on an `imsg:` conversation here; [ImessageSinkService] hands
 * over what Apple sent ([apply]).
 */
class ImessageLink private constructor(private val app: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy { ImessageIpc.Client(app, ImessageService::class.java) }
    private val repo: ArchiveRepository get() = RelayManager.get(app).repo

    init {
        scope.launch { failInterruptedSends() }
    }

    // --- conversations ----------------------------------------------------------------------------

    /**
     * The iMessage conversation with [recipient] if that address is on iMessage (made in the
     * archive if new); null when iMessage is not set up or cannot reach it (the caller uses SMS).
     */
    suspend fun conversationFor(recipient: String): String? {
        if (!ImessageEngine.isSetUp(app)) return null
        val reply = client.call(ImessageWire.REACH, ImessageWire.reach(recipient), REACH_TIMEOUT_MS)
        val dto = reply.optJSONObject("conversation")?.let(ConversationDto::fromJson) ?: return null
        if (repo.conversation(dto.id) == null) repo.upsertConversation(named(dto))
        return dto.id
    }

    /** Where a message to a stored conversation goes, read from the archive (works after any restart). */
    private fun targetOf(conversationId: String): ImessageWire.Target {
        val row = repo.conversation(conversationId) ?: throw ImessageEngine.ImessageError("This conversation is not in the archive any more.")
        val participants = repo.participants(conversationId)
        val data = ImessageArchive.conversationData(conversationId, participants, row.name)
        val from = participants.firstOrNull { it.isMe }?.id ?: row.defaultOutgoingId
        if (from.isBlank()) throw ImessageEngine.ImessageError("This conversation has no address of yours to send from.")
        return ImessageWire.Target(data.participants, data.cvName, data.senderGuid, from)
    }

    // --- sending ------------------------------------------------------------------------------------

    /** Like the Messages tab's sends: a local copy now ("tmp:<tmpId>"), the sent message once Apple confirms it. */
    fun sendText(conversationId: String, text: String, replyToId: String?): String {
        val tmpId = UUID.randomUUID().toString()
        repo.insertLocalOutgoing(tmpId, conversationId, text, null, null, null, replyToId, System.currentTimeMillis())
        scope.launch { deliver(tmpId, conversationId, text, replyToId) }
        return tmpId
    }

    /** Sends a failed message again, from its local copy (which survives restarts). */
    fun retry(tmpId: String) {
        val row = repo.message(MessageRow.tmpMessageId(tmpId)) ?: return
        repo.setLocalState(tmpId, LocalState.PENDING)
        scope.launch { deliver(tmpId, row.conversationId, row.text, row.replyToId.ifEmpty { null }) }
    }

    fun discard(tmpId: String) {
        repo.applyDeletion(MessageRow.tmpMessageId(tmpId))
    }

    private suspend fun deliver(tmpId: String, conversationId: String, text: String, replyToId: String?) {
        try {
            repo.setLocalState(tmpId, LocalState.SENDING)
            val target = targetOf(conversationId)
            val reply = client.call(
                ImessageWire.SEND, ImessageWire.send(target, text, replyToId?.let(ImessageArchive::guidOf)), SEND_TIMEOUT_MS,
            )
            ImessageWire.errorOf(reply)?.let { throw ImessageEngine.ImessageError(it) }
            repo.upsertMessage(
                MessageDto(
                    id = ImessageArchive.messageId(reply.getString("guid")),
                    conversationId = conversationId,
                    tmpId = tmpId,
                    senderId = target.from,
                    fromMe = true,
                    timestampMs = reply.optLong("ts").takeIf { it > 0 } ?: System.currentTimeMillis(),
                    direction = "out",
                    status = ImessageArchive.STATUS_SENT,
                    statusCode = 1,
                    transport = ImessageArchive.TRANSPORT,
                    text = text,
                    parts = listOf(PartDto(partId = "0", kind = "text", text = text)),
                    replyToId = replyToId.orEmpty(),
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "iMessage send failed", e)
            repo.setLocalState(tmpId, LocalState.FAILED, ImessageEngine.readable(e))
        }
    }

    /**
     * Retracts one of the user's iMessages within Apple's 2-minute window. Who it went to comes
     * from the archive, so this works after a restart of either process. Throws
     * [ImessageEngine.ImessageError] saying why when it does not go through.
     */
    suspend fun unsend(messageId: String, part: Int = 0) {
        val guid = ImessageArchive.guidOf(messageId) ?: throw ImessageEngine.ImessageError("Only iMessages can be unsent.")
        val row = repo.message(messageId) ?: throw ImessageEngine.ImessageError("This message is not in the archive any more.")
        if (!row.isOutgoing) throw ImessageEngine.ImessageError("Only your own messages can be unsent.")
        if (!UnsendWindow.canUnsend(row.timestampMs, System.currentTimeMillis())) {
            throw ImessageEngine.ImessageError("Too late to unsend: Apple only allows it within 2 minutes of sending.")
        }
        val reply = client.call(ImessageWire.UNSEND, ImessageWire.unsend(targetOf(row.conversationId), guid, part), SEND_TIMEOUT_MS)
        ImessageWire.errorOf(reply)?.let { throw ImessageEngine.ImessageError(it) }
        repo.applyDeletion(messageId)
    }

    /** Local copies a previous run was still sending: Apple's answer was lost with it. */
    private fun failInterruptedSends() {
        try {
            repo.recentOutgoing(limit = 500)
                .filter { it.isLocal && ImessageArchive.isImessage(it.conversationId) && it.localState != LocalState.FAILED }
                .forEach { repo.setLocalState(it.tmpId, LocalState.FAILED, app.getString(R.string.imessage_interrupted)) }
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not check interrupted sends", e)
        }
    }

    // --- what Apple sent ------------------------------------------------------------------------------

    /** Stores one change from the ":imessage" process. Returns once it is in the archive. */
    fun apply(update: ImessageArchive.Update) {
        when (update) {
            is ImessageArchive.Update.Upsert -> {
                val existing = repo.conversation(update.conversation.id)
                var conversation = named(update.conversation)
                if (existing != null && !conversation.isGroup) conversation = conversation.copy(name = existing.name.ifBlank { conversation.name })
                repo.upsertConversation(conversation.copy(lastMessageTs = maxOf(existing?.lastMessageTs ?: 0, update.message.timestampMs)))
                val message = update.message.let {
                    if (it.fromMe || it.senderId.isEmpty()) it else it.copy(senderName = nameOf(it.senderId))
                }
                val result = repo.upsertMessage(message)
                if (result.inserted && !message.fromMe) MessageNotifier.onIncoming(app, repo, message)
            }
            is ImessageArchive.Update.Remove -> repo.applyDeletion(update.messageId)
            is ImessageArchive.Update.Receipt -> repo.advanceMessageStatus(
                update.messageIds, update.status, ImessageArchive.statusCode(update.status), ImessageArchive::statusRank,
            )
            is ImessageArchive.Update.Edit -> {
                val row = repo.message(update.messageId) ?: return
                repo.upsertMessage(
                    MessageDto(
                        id = row.id, conversationId = row.conversationId, senderId = row.senderId, senderName = row.senderName,
                        fromMe = row.isOutgoing, timestampMs = row.timestampMs, direction = row.direction, status = row.status,
                        statusCode = row.statusCode, transport = ImessageArchive.TRANSPORT, text = update.text,
                        parts = listOf(PartDto(partId = "0", kind = "text", text = update.text)), replyToId = row.replyToId,
                    ),
                )
            }
            ImessageArchive.Update.Ignore -> Unit
        }
    }

    /** The conversation with contact names in place of addresses where the archive knows them. */
    private fun named(c: ConversationDto): ConversationDto {
        val participants = c.participants.map { p -> if (p.isMe) p else p.copy(name = nameOf(p.id)) }
        val name = when {
            !c.isGroup -> participants.firstOrNull { !it.isMe }?.name ?: c.name
            c.name.isNotBlank() && c.participants.none { Handles.display(it.id) == c.name } && ',' !in c.name -> c.name
            else -> participants.filter { !it.isMe }.joinToString(", ") { it.name }
        }
        return c.copy(name = name, participants = participants)
    }

    /** A display name for an address: the Messages archive's contact with that number, else the address. */
    private fun nameOf(handle: String): String {
        val address = Handles.display(handle)
        if (!handle.startsWith("tel:")) return address
        val digits = address.filter { it.isDigit() }.takeLast(10)
        if (digits.length < 7) return address
        return try {
            repo.contacts(digits).firstOrNull { c ->
                c.phoneNumber.filter { it.isDigit() }.endsWith(digits) && (c.customAlias.isNotBlank() || c.displayName.isNotBlank())
            }?.let { it.customAlias.ifBlank { it.displayName } } ?: address
        } catch (e: RuntimeException) {
            address
        }
    }

    companion object {
        private const val TAG = "ImessageLink"

        /** A send waits for Apple's confirmation (up to 45 s) plus the session coming up. */
        private const val SEND_TIMEOUT_MS = 90_000L
        private const val REACH_TIMEOUT_MS = 25_000L

        @Volatile
        private var instance: ImessageLink? = null

        /** Only in the main process. */
        fun get(context: Context): ImessageLink =
            instance ?: synchronized(this) {
                instance ?: ImessageLink(context.applicationContext).also { instance = it }
            }
    }
}

/** Takes in what Apple sent, from the ":imessage" process, and stores it (main process). */
class ImessageSinkService : android.app.Service() {

    private val server = ImessageIpc.Server { what, request ->
        if (what != ImessageWire.UPDATE) return@Server ImessageWire.error("Unknown request $what")
        synchronized(ImessageSinkService::class.java) {
            // One at a time and in order: the ":imessage" process sends them in Apple's order.
            ImessageLink.get(this).apply(ImessageWire.decode(request))
        }
        ImessageWire.ok()
    }

    override fun onBind(intent: android.content.Intent?): android.os.IBinder = server.messenger.binder

    override fun onDestroy() {
        server.close()
        super.onDestroy()
    }
}
