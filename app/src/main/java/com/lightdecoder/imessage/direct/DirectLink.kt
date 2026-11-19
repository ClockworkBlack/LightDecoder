package com.lightdecoder.imessage.direct

import android.content.Context
import android.util.Log
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.imessage.IMessageLog
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.Folder
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.PartDto
import com.lightdecoder.relay.RelayManager
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The direct iMessage link, main-process side: OpenBubbles' library (in the ":imessage" process,
 * [DirectService]) instead of the OpenBubbles app, for the same iMessage archive Blue Bubbles uses
 * ([IMessageManager.repo]). Its chats therefore show where Blue Bubbles' do: in blue, inside the
 * Messages conversation with the same person ([com.lightdecoder.imessage.IMessageLinks]).
 *
 * The archive's conventions are kept: the user is the participant `me`; the other person's id and
 * number are their address ("+15551234567" or an email); a person's existing Blue Bubbles chat is
 * reused, so its history and the new messages stay together. New chats get `imsg:` ids
 * ([DirectArchive]); messages sent or received here are `imsg:<GUID>`, the GUID Apple knows them by.
 *
 * [IMessageManager] hands sends, retries and unsends here while [active]; [DirectSinkService]
 * hands over what Apple sent ([apply]).
 */
class DirectLink private constructor(private val app: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy { DirectIpc.Client(app, DirectService::class.java) }
    private val manager: IMessageManager get() = IMessageManager.get(app)
    private val repo: ArchiveRepository get() = manager.repo

    /** Whether iMessage goes through the library: its files are there and it was registered. */
    val active: Boolean get() = DirectEngine.isSetUp(app)

    init {
        if (active) scope.launch { failInterruptedSends() }
    }

    // --- where a message goes ------------------------------------------------------------------

    /** The library's addresses for a stored chat's members, the user's own left to the library. */
    fun targetOf(conversationId: String): DirectWire.Target? {
        val row = repo.conversation(conversationId) ?: return null
        val others = repo.participants(conversationId).filter { !it.isMe }
            .mapNotNull { Handles.normalize(it.number.ifBlank { it.id }) }
            .distinct()
        if (others.isEmpty()) return null
        val group = row.isGroup || others.size > 1
        // A group needs its iMessage group id: only chats the library itself made have one.
        val guid = DirectArchive.groupGuid(conversationId)
        if (group && guid == null) return null
        return DirectWire.Target(others, if (group) row.name.ifBlank { null } else null, guid, row.defaultOutgoingId)
    }

    /** Whether [conversationId] can be sent to through the library (else Blue Bubbles' way is used). */
    fun canSend(conversationId: String): Boolean = active && runCatching { targetOf(conversationId) }.getOrNull() != null

    /**
     * The iMessage chat with [address] (number or email) if that address is on iMessage: the stored
     * one, or a new one. Null when the library is not set up or Apple says it cannot get iMessages.
     */
    suspend fun conversationFor(address: String): String? {
        if (!active) return null
        manager.conversationWith(address)?.let { return it }
        val reply = client.call(DirectWire.REACH, DirectWire.reach(address), REACH_TIMEOUT_MS)
        val dto = reply.optJSONObject("conversation")?.let(ConversationDto::fromJson) ?: return null
        val stored = toArchive(dto)
        repo.upsertConversation(stored)
        return stored.id
    }

    // --- sending ----------------------------------------------------------------------------------

    /** Like every send in the app: a local copy now ("tmp:<tmpId>"), the sent message once Apple confirms it. */
    fun sendText(conversationId: String, text: String, replyToId: String?): String {
        val tmpId = UUID.randomUUID().toString()
        repo.insertLocalOutgoing(tmpId, conversationId, text, null, null, null, replyToId, System.currentTimeMillis())
        scope.launch { deliver(tmpId, conversationId, text, replyToId) }
        return tmpId
    }

    /** Sends a failed message again, from its local copy (which survives restarts). */
    fun retry(tmpId: String): Boolean {
        val row = repo.message(MessageRow.tmpMessageId(tmpId)) ?: return false
        if (!canSend(row.conversationId)) return false
        repo.setLocalState(tmpId, LocalState.PENDING)
        scope.launch { deliver(tmpId, row.conversationId, row.text, row.replyToId.ifEmpty { null }) }
        return true
    }

    private suspend fun deliver(tmpId: String, conversationId: String, text: String, replyToId: String?) {
        try {
            repo.setLocalState(tmpId, LocalState.SENDING)
            val target = targetOf(conversationId) ?: throw DirectEngine.ImessageError("This chat cannot be reached directly.")
            val reply = client.call(
                DirectWire.SEND, DirectWire.send(target, text, replyToId?.let(DirectArchive::guidOf)), SEND_TIMEOUT_MS,
            )
            DirectWire.errorOf(reply)?.let { throw DirectEngine.ImessageError(it) }
            repo.upsertMessage(
                MessageDto(
                    id = DirectArchive.messageId(reply.getString("guid")),
                    conversationId = conversationId,
                    tmpId = tmpId,
                    fromMe = true,
                    timestampMs = reply.optLong("ts").takeIf { it > 0 } ?: System.currentTimeMillis(),
                    direction = "out",
                    status = DirectArchive.STATUS_SENT,
                    statusCode = 1,
                    transport = IMessageManager.TRANSPORT,
                    text = text,
                    parts = listOf(PartDto(partId = "0", kind = "text", text = text)),
                    replyToId = replyToId.orEmpty(),
                    isOld = true,
                ),
            )
            IMessageLog.add(app, "${IMessageLog.chatRef(conversationId)}: sent through the iMessage library (Apple confirmed)")
        } catch (e: Exception) {
            Log.w(TAG, "direct send failed", e)
            IMessageLog.add(app, "${IMessageLog.chatRef(conversationId)}: library send FAILED: ${DirectEngine.readable(e)}")
            repo.setLocalState(tmpId, LocalState.FAILED, DirectEngine.readable(e))
        }
    }

    /** Whether [messageId] is one of the user's iMessages the library can unsend, and still in time. */
    fun canUnsend(row: MessageRow, now: Long = System.currentTimeMillis()): Boolean =
        active && row.isOutgoing && DirectArchive.isImessage(row.id) && UnsendWindow.canUnsend(row.timestampMs, now)

    /**
     * Retracts one of the user's iMessages within Apple's 2-minute window. Who it went to comes from
     * the archive, so this works after a restart of either process. Throws
     * [DirectEngine.ImessageError] saying why when it does not go through.
     */
    suspend fun unsend(messageId: String, part: Int = 0) {
        val guid = DirectArchive.guidOf(messageId) ?: throw DirectEngine.ImessageError("Only messages sent through the iMessage library can be unsent here.")
        val row = repo.message(messageId) ?: throw DirectEngine.ImessageError("This message is not in the archive any more.")
        if (!row.isOutgoing) throw DirectEngine.ImessageError("Only your own messages can be unsent.")
        if (!UnsendWindow.canUnsend(row.timestampMs, System.currentTimeMillis())) {
            throw DirectEngine.ImessageError("Too late to unsend: Apple only allows it within 2 minutes of sending.")
        }
        val target = targetOf(row.conversationId) ?: throw DirectEngine.ImessageError("This chat cannot be reached directly.")
        val reply = client.call(DirectWire.UNSEND, DirectWire.unsend(target, guid, part), SEND_TIMEOUT_MS)
        DirectWire.errorOf(reply)?.let { throw DirectEngine.ImessageError(it) }
        repo.applyDeletion(messageId)
        IMessageLog.add(app, "${IMessageLog.chatRef(row.conversationId)}: unsent through the iMessage library (Apple confirmed)")
    }

    /** Local copies a previous run was still sending: Apple's answer was lost with it. */
    private fun failInterruptedSends() {
        try {
            repo.recentOutgoing(limit = 500)
                .filter { it.isLocal && it.localState != LocalState.FAILED && it.localState != LocalState.SYNCED }
                .filter { canSend(it.conversationId) }
                .forEach { repo.setLocalState(it.tmpId, LocalState.FAILED, app.getString(R.string.imessage_interrupted)) }
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not check interrupted sends", e)
        }
    }

    // --- what Apple sent ------------------------------------------------------------------------------

    /**
     * The archive's form of a conversation the library describes: the person's stored chat if
     * there is one (one-to-one), `me` for the user, addresses without their scheme, contact names.
     */
    private fun toArchive(c: ConversationDto): ConversationDto {
        val others = c.participants.filter { !it.isMe }
        val existingId = if (!c.isGroup && others.size == 1) manager.conversationWith(Handles.display(others[0].id)) else null
        val id = existingId ?: c.id
        val existing = repo.conversation(id)
        val people = others.map {
            val address = Handles.display(it.id)
            ParticipantDto(id = address, name = nameOf(it.id), number = address, formattedNumber = address)
        }
        val name = when {
            !c.isGroup -> ""
            c.name.isNotBlank() && others.none { Handles.display(it.id) == c.name } && ',' !in c.name -> c.name
            else -> people.joinToString(", ") { it.name }
        }
        return ConversationDto(
            id = id,
            name = name,
            isGroup = c.isGroup,
            type = IMessageManager.TRANSPORT,
            folder = existing?.folder?.takeIf { it.isNotEmpty() && it != Folder.DELETED.wire } ?: Folder.INBOX.wire,
            unread = existing?.unread == true,
            pinned = existing?.pinned == true,
            muted = existing?.muted == true,
            avatarColor = existing?.avatarColor.orEmpty(),
            lastMessageTs = maxOf(existing?.lastMessageTs ?: 0, c.lastMessageTs),
            defaultOutgoingId = c.defaultOutgoingId.ifBlank { existing?.defaultOutgoingId.orEmpty() },
            participants = listOf(ParticipantDto(id = ME, isMe = true)) + people,
        )
    }

    /** Stores one change from the ":imessage" process. Returns once it is in the archive. */
    fun apply(update: DirectArchive.Update) {
        when (update) {
            is DirectArchive.Update.Upsert -> {
                val conversation = toArchive(update.conversation)
                val onScreen = MessageNotifier.visibleConversationId(com.lightdecoder.chat.ChatService.IMESSAGE) == conversation.id
                val incoming = !update.message.fromMe
                repo.upsertConversation(
                    conversation.copy(
                        unread = (conversation.unread || incoming) && !onScreen,
                        lastMessageTs = maxOf(conversation.lastMessageTs, update.message.timestampMs),
                    ),
                )
                val message = update.message.copy(
                    conversationId = conversation.id,
                    senderId = if (update.message.fromMe) "" else Handles.display(update.message.senderId),
                    senderName = if (update.message.fromMe) "" else nameOf(update.message.senderId),
                    transport = IMessageManager.TRANSPORT,
                )
                val result = repo.upsertMessage(message)
                if (result.inserted && incoming) MessageNotifier.onIncoming(app, repo, message)
                if (result.inserted) {
                    IMessageLog.add(
                        app,
                        "${IMessageLog.chatRef(conversation.id)}: " +
                            (if (incoming) "received" else "sent from another device") + " through the iMessage library",
                    )
                }
            }
            is DirectArchive.Update.Remove -> repo.applyDeletion(update.messageId)
            is DirectArchive.Update.Receipt -> repo.advanceMessageStatus(
                update.messageIds, update.status, DirectArchive.statusCode(update.status), DirectArchive::statusRank,
            )
            is DirectArchive.Update.Edit -> {
                val row = repo.message(update.messageId) ?: return
                repo.upsertMessage(
                    MessageDto(
                        id = row.id, conversationId = row.conversationId, senderId = row.senderId, senderName = row.senderName,
                        fromMe = row.isOutgoing, timestampMs = row.timestampMs, direction = row.direction, status = row.status,
                        statusCode = row.statusCode, transport = IMessageManager.TRANSPORT, text = update.text,
                        parts = listOf(PartDto(partId = "0", kind = "text", text = update.text)), replyToId = row.replyToId,
                        isOld = true,
                    ),
                )
            }
            DirectArchive.Update.Ignore -> Unit
        }
    }

    /** A display name for an address: the Messages archive's contact with that number, else the address. */
    private fun nameOf(handle: String): String {
        val address = Handles.display(handle)
        if (handle.startsWith("mailto:") || '@' in address) return address
        val digits = address.filter { it.isDigit() }.takeLast(10)
        if (digits.length < 7) return address
        return try {
            RelayManager.get(app).repo.contacts(digits).firstOrNull { c ->
                c.phoneNumber.filter { it.isDigit() }.endsWith(digits) && (c.customAlias.isNotBlank() || c.displayName.isNotBlank())
            }?.let { it.customAlias.ifBlank { it.displayName } } ?: address
        } catch (e: RuntimeException) {
            address
        }
    }

    companion object {
        private const val TAG = "DirectLink"

        /**
         * At app start (main process): with the library registered, Blue Bubbles is turned on the
         * first time (so its chats show in Messages; turning it off later is respected) and the
         * library's service is started.
         */
        fun onAppStart(context: Context) {
            if (!DirectEngine.isSetUp(context)) return
            if (!DirectPrefs.blueBubblesTurnedOn(context)) {
                com.lightdecoder.imessage.IMessagePrefs.setEnabled(context, true)
                DirectPrefs.setBlueBubblesTurnedOn(context)
                IMessageLog.add(context, "iMessage library registered: Blue Bubbles turned on to show its chats")
            }
            DirectService.start(context)
        }

        /** The archive's id for the user (as Blue Bubbles stores it). */
        const val ME = "me"

        /** A send waits for Apple's confirmation (up to 45 s) plus the session coming up. */
        private const val SEND_TIMEOUT_MS = 90_000L
        private const val REACH_TIMEOUT_MS = 25_000L

        @Volatile
        private var instance: DirectLink? = null

        /** Only in the main process. */
        fun get(context: Context): DirectLink =
            instance ?: synchronized(this) {
                instance ?: DirectLink(context.applicationContext).also { instance = it }
            }
    }
}
