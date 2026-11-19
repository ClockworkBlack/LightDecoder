package com.lightdecoder.imessage.direct

import android.content.Context
import android.util.Log
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.chat.ChatService
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
import org.json.JSONObject

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
 * hands over what Apple sent ([apply]). What arrives for a message that is not stored yet (a
 * receipt for a message whose send has not returned yet) is held for a while ([HeldUpdates]) and
 * applied once it is.
 */
class DirectLink private constructor(private val app: Context, requests: Requests?) {

    /** Asks the ":imessage" process ([DirectIpc.Client]); tests answer in its place. */
    internal fun interface Requests {
        suspend fun call(what: Int, request: JSONObject, timeoutMs: Long): JSONObject
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy { DirectIpc.Client(app, DirectService::class.java) }
    private val requests: Requests = requests ?: Requests { what, request, timeoutMs -> client.call(what, request, timeoutMs) }
    private val manager: IMessageManager get() = IMessageManager.get(app)
    private val repo: ArchiveRepository get() = manager.repo

    /** Whether iMessage goes through the library: its files are there and it was registered. */
    val active: Boolean get() = DirectEngine.isSetUp(app)

    /** Changes waiting for their message to be stored. */
    private val held = HeldUpdates()

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
        val after = repo.latestMessageId(conversationId, DirectArchive.PREFIX)?.let(DirectArchive::guidOf)
        return DirectWire.Target(others, if (group) row.name.ifBlank { null } else null, guid, row.defaultOutgoingId, after)
    }

    /** Whether a reply to [row] threads under it on iPhones (sent through the library, to a message with a GUID). */
    fun repliesInline(row: MessageRow): Boolean = DirectArchive.isImessage(row.id) && canSend(row.conversationId)

    /** Whether [conversationId] can be sent to through the library (else Blue Bubbles' way is used). */
    fun canSend(conversationId: String): Boolean = active && runCatching { targetOf(conversationId) }.getOrNull() != null

    /**
     * The iMessage chat with [address] (number or email) if that address is on iMessage: the stored
     * one, or a new one. Null when the library is not set up or Apple says it cannot get iMessages.
     */
    suspend fun conversationFor(address: String): String? {
        if (!active) return null
        manager.conversationWith(address)?.let { return it }
        val reply = requests.call(DirectWire.REACH, DirectWire.reach(address), REACH_TIMEOUT_MS)
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
            // An inline reply needs the GUID Apple knows the answered message by: messages the
            // library sent or received have it (imsg:<GUID>); older Blue Bubbles rows do not, and
            // a reply to one goes as a message of its own.
            val replyGuid = replyToId?.let(DirectArchive::guidOf)
            val replyPart = replyGuid?.let { DirectWire.replyPart(repo.message(replyToId)?.text.orEmpty()) }
            val reply = requests.call(DirectWire.SEND, DirectWire.send(target, text, replyGuid, replyPart), SEND_TIMEOUT_MS)
            DirectWire.errorOf(reply)?.let { throw DirectEngine.ImessageError(it) }
            val sent = DirectWire.SendReply.fromJson(reply)
            val messageId = DirectArchive.messageId(sent.guid)
            val stored = MessageDto(
                id = messageId,
                conversationId = conversationId,
                tmpId = tmpId,
                fromMe = true,
                timestampMs = sent.timestampMs.takeIf { it > 0 } ?: System.currentTimeMillis(),
                direction = "out",
                status = DirectArchive.STATUS_SENT,
                statusCode = 1,
                transport = IMessageManager.TRANSPORT,
                text = text,
                parts = listOf(PartDto(partId = "0", kind = "text", text = text)),
                replyToId = replyToId.orEmpty(),
                isOld = true,
            )
            // A receipt (or a reaction) can come from Apple before the send's answer did: what
            // [apply] held for it is applied now, under the same lock, so none slips between.
            synchronized(this) {
                repo.upsertMessage(stored)
                replayHeld(messageId)
            }
            IMessageLog.add(app, "${IMessageLog.chatRef(conversationId)}: sent through the iMessage library (Apple confirmed)")
        } catch (e: Exception) {
            Log.w(TAG, "direct send failed", e)
            IMessageLog.add(app, "${IMessageLog.chatRef(conversationId)}: library send FAILED: ${DirectEngine.readable(e)}")
            repo.setLocalState(tmpId, LocalState.FAILED, DirectEngine.readable(e))
        }
    }

    /** Whether [messageId] is one of the user's iMessages the library can unsend, and still in time. */
    fun canUnsend(row: MessageRow, now: Long = System.currentTimeMillis()): Boolean =
        active && row.isOutgoing && row.direction == "out" && DirectArchive.isImessage(row.id) && UnsendWindow.canUnsend(row.timestampMs, now)

    /**
     * Retracts one of the user's iMessages within Apple's 2-minute window. Who it went to comes from
     * the archive, so this works after a restart of either process. Throws
     * [DirectEngine.ImessageError] saying why when it does not go through.
     */
    suspend fun unsend(messageId: String, part: Int = 0) {
        val guid = DirectArchive.guidOf(messageId) ?: throw DirectEngine.ImessageError("Only messages sent through the iMessage library can be unsent here.")
        val row = repo.message(messageId) ?: throw DirectEngine.ImessageError("This message is not in the archive any more.")
        // An unsent message's or a group event's row is the user's too, but there is nothing to unsend.
        if (!row.isOutgoing || row.direction != "out") throw DirectEngine.ImessageError("Only your own messages can be unsent.")
        if (!UnsendWindow.canUnsend(row.timestampMs, System.currentTimeMillis())) {
            throw DirectEngine.ImessageError("Too late to unsend: Apple only allows it within 2 minutes of sending.")
        }
        val target = targetOf(row.conversationId) ?: throw DirectEngine.ImessageError("This chat cannot be reached directly.")
        val reply = requests.call(DirectWire.UNSEND, DirectWire.unsend(target, guid, part), SEND_TIMEOUT_MS)
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
        // A group's own name (commas and all), else its members' names.
        val name = if (c.isGroup) c.name.ifBlank { people.joinToString(", ") { it.name } } else ""
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
            // A stored chat's latest message (time, id, snippet) moves with its messages only
            // (ArchiveRepository.upsertMessage); given a time here, they would be written blank.
            lastMessageTs = if (existing == null) c.lastMessageTs else 0,
            defaultOutgoingId = c.defaultOutgoingId.ifBlank { existing?.defaultOutgoingId.orEmpty() },
            participants = listOf(ParticipantDto(id = ME, isMe = true)) + people,
        )
    }

    /** Stores one change from the ":imessage" process. Returns once it is in the archive. */
    fun apply(update: DirectArchive.Update) {
        synchronized(this) { store(update) }
    }

    private fun store(update: DirectArchive.Update) {
        when (update) {
            is DirectArchive.Update.Upsert -> upsert(update)
            is DirectArchive.Update.Remove -> repo.applyDeletion(update.messageId)
            is DirectArchive.Update.Receipt -> {
                val stored = repo.advanceMessageStatus(
                    update.messageIds, update.status, DirectArchive.statusCode(update.status), DirectArchive::statusRank,
                )
                for (id in update.messageIds - stored) hold(id, DirectArchive.Update.Receipt(listOf(id), update.status))
            }
            is DirectArchive.Update.Edit -> edit(update)
            is DirectArchive.Update.Reaction -> {
                val who = if (update.fromMe) ME else update.participantId
                if (!repo.applyReaction(update.messageId, who, update.emoji, update.enable, update.fromMe, update.atMs)) {
                    hold(update.messageId, update)
                }
            }
            is DirectArchive.Update.Unsent -> unsent(update)
            is DirectArchive.Update.Typing -> {
                val id = archiveConversationId(update.conversationId, update.participantId)
                manager.noteTyping(id, update.participantId, nameOf(update.participantId), update.typing)
            }
            is DirectArchive.Update.ReadElsewhere -> readElsewhere(update)
            is DirectArchive.Update.Failed -> {
                if (repo.message(update.messageId) == null) {
                    hold(update.messageId, update)
                } else {
                    repo.markSentFailed(
                        update.messageId, DirectArchive.STATUS_FAILED, update.error,
                        DirectArchive.statusRank(DirectArchive.STATUS_DELIVERED), DirectArchive::statusRank,
                    )
                }
            }
            DirectArchive.Update.Ignore -> Unit
        }
    }

    private fun upsert(update: DirectArchive.Update.Upsert) {
        val conversation = toArchive(update.conversation)
        val onScreen = MessageNotifier.visibleConversationId(ChatService.IMESSAGE) == conversation.id
        // A group's event (renamed, someone added) is shown, but is not a message to read.
        val system = update.message.direction == "system"
        val incoming = !update.message.fromMe && !system
        val existing = repo.message(update.message.id)
        // Unsent since: it stays unsent.
        if (existing != null && existing.direction == "system" && !system) return
        // The message moves the chat's latest message, time and snippet (see toArchive).
        repo.upsertConversation(conversation.copy(unread = (conversation.unread || incoming) && !onScreen, lastMessageTs = 0))
        val fresh = update.message.copy(
            conversationId = conversation.id,
            senderId = if (update.message.fromMe) "" else Handles.display(update.message.senderId),
            senderName = if (update.message.fromMe) "" else nameOf(update.message.senderId),
            transport = IMessageManager.TRANSPORT,
        )
        val message = existing?.let { merged(fresh, it) } ?: fresh
        val result = repo.upsertMessage(message)
        if (incoming) manager.noteTyping(conversation.id, message.senderId, "", typing = false)
        // What came for it first (read on another device, unsent, edited) applies before it is
        // announced, so a message already read or unsent makes no sound.
        replayHeld(message.id)
        if (result.inserted && incoming) notifyIfStillNew(message)
        if (result.inserted && !system) {
            IMessageLog.add(
                app,
                "${IMessageLog.chatRef(conversation.id)}: " +
                    (if (incoming) "received" else "sent from another device") + " through the iMessage library",
            )
        }
    }

    /** A notification for [message], just stored, unless it was unsent or its chat read since; with its text as it now is. */
    private fun notifyIfStillNew(message: MessageDto) {
        val row = repo.message(message.id) ?: return
        if (row.direction != "in") return
        if (repo.conversation(row.conversationId)?.unread != true) return
        MessageNotifier.onIncoming(app, repo, message.copy(text = row.text))
    }

    /**
     * The sender edited a part: the message's text is rebuilt from its parts, marked edited. The
     * same edit handed over again (Apple's journal does that after a restart) counts once.
     */
    private fun edit(update: DirectArchive.Update.Edit) {
        val row = repo.message(update.messageId) ?: return hold(update.messageId, update)
        if (row.direction == "system") return
        val extra = IMessageExtra.parse(row.extra)
        if (update.part in extra.unsent) return
        val parts = partsOf(row, extra)
        if (parts[update.part] == update.text && update.atMs <= row.editedAtMs) return
        val next = extra.copy(parts = parts + (update.part to update.text), edits = extra.edits + 1)
        repo.editText(row.id, next.text(), update.atMs, next.toJson())
    }

    /**
     * The sender unsent a part. Nothing of the message left: it gives way to "… unsent a
     * message." Else only that part goes, and is remembered as unsent, so the same unsend handed
     * over again changes nothing.
     */
    private fun unsent(update: DirectArchive.Update.Unsent) {
        val row = repo.message(update.messageId) ?: return hold(update.messageId, update)
        if (row.direction == "system") return
        val extra = IMessageExtra.parse(row.extra)
        if (update.part in extra.unsent) return
        val parts = partsOf(row, extra)
        val partId = DirectArchive.partId(update.part)
        val media = row.media.map { it.partId }
        val textLeft = parts - update.part
        if (textLeft.isEmpty() && (media - partId).isEmpty()) {
            val text = if (update.fromMe || row.isOutgoing) {
                app.getString(R.string.imessage_you_unsent)
            } else {
                app.getString(R.string.imessage_unsent, row.senderName.ifBlank { update.senderName.ifBlank { nameOf(row.senderId) } })
            }
            repo.replaceWithPlaceholder(row.id, text)
            return
        }
        if (partId in media) repo.removeMediaPart(row.id, partId)
        val next = extra.copy(parts = textLeft, unsent = extra.unsent + update.part)
        repo.editText(row.id, next.text(), row.editedAtMs, next.toJson())
    }

    /** The user read [update]'s message on another device: the chat is read here too, unless something newer came since. */
    private fun readElsewhere(update: DirectArchive.Update.ReadElsewhere) {
        val row = repo.message(update.messageId) ?: return hold(update.messageId, update)
        val newest = repo.latestIncomingMessageId(row.conversationId, DirectArchive.PREFIX)?.let(repo::message)
        if (newest != null && newest.timestampMs > row.timestampMs) return
        repo.markConversationRead(row.conversationId)
        MessageNotifier.cancel(app, row.conversationId, ChatService.IMESSAGE)
    }

    /** A message's text by part: as stored with it, or, for one that has none stored, its text as part 0. */
    private fun partsOf(row: MessageRow, extra: IMessageExtra): Map<Int, String> =
        extra.parts.ifEmpty { if (row.text.isEmpty()) emptyMap() else mapOf(0 to row.text) }

    /** The archive's conversation for one the library names: a one-to-one chat may be a stored Blue Bubbles one. */
    private fun archiveConversationId(conversationId: String, participantId: String): String =
        if (DirectArchive.isGroup(conversationId)) conversationId else manager.conversationWith(participantId) ?: conversationId

    private fun hold(messageId: String, update: DirectArchive.Update) {
        if (DirectArchive.isImessage(messageId)) held.hold(messageId, update, System.currentTimeMillis())
    }

    /** What arrived for [messageId] before it was stored. */
    private fun replayHeld(messageId: String) {
        for (update in held.take(messageId, System.currentTimeMillis())) apply(update)
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

        /**
         * A message Apple sent again, over the stored copy: what happened to it since (reactions,
         * edits, parts unsent, later receipts) is kept.
         */
        internal fun merged(fresh: MessageDto, stored: MessageRow): MessageDto {
            val further = DirectArchive.statusRank(stored.status) > DirectArchive.statusRank(fresh.status)
            val unsent = IMessageExtra.parse(stored.extra).unsent
            val changed = stored.editedAtMs > 0 || unsent.isNotEmpty()
            val gone = unsent.map(DirectArchive::partId).toSet()
            return fresh.copy(
                reactions = stored.reactions,
                status = if (further) stored.status else fresh.status,
                statusCode = if (further) stored.statusCode else fresh.statusCode,
                text = if (changed) stored.text else fresh.text,
                parts = if (changed) {
                    listOfNotNull(stored.text.takeIf { it.isNotEmpty() }?.let { PartDto(partId = "0", kind = "text", text = it) }) +
                        fresh.parts.filter { it.isMedia && it.partId !in gone }
                } else {
                    fresh.parts
                },
                editedAtMs = if (changed) stored.editedAtMs else fresh.editedAtMs,
                extra = if (changed) stored.extra else fresh.extra,
            )
        }

        /** A send waits for Apple's confirmation (up to 45 s) plus the session coming up. */
        private const val SEND_TIMEOUT_MS = 90_000L
        private const val REACH_TIMEOUT_MS = 25_000L

        @Volatile
        private var instance: DirectLink? = null

        /** Only in the main process. */
        fun get(context: Context): DirectLink =
            instance ?: synchronized(this) {
                instance ?: DirectLink(context.applicationContext, null).also { instance = it }
            }

        /** Tests: a fresh link whose requests [requests] answers, instead of the ":imessage" process. */
        internal fun installForTesting(context: Context, requests: Requests): DirectLink = synchronized(this) {
            DirectLink(context.applicationContext, requests).also { instance = it }
        }

        internal fun resetForTesting() = synchronized(this) {
            instance = null
        }
    }
}

/**
 * Changes for messages that are not stored yet, by message id, for up to [windowMs] (then they
 * are forgotten): Apple's receipt for a message sent from here can come before the send's own
 * answer, and a reaction or edit before its message. At most [maxMessages] messages are held.
 */
internal class HeldUpdates(private val windowMs: Long = 2 * 60 * 1000L, private val maxMessages: Int = 200) {

    private val held = LinkedHashMap<String, MutableList<Pair<Long, DirectArchive.Update>>>()

    @Synchronized
    fun hold(messageId: String, update: DirectArchive.Update, nowMs: Long) {
        evict(nowMs)
        held.getOrPut(messageId) { ArrayList() }.add(nowMs to update)
        while (held.size > maxMessages) held.remove(held.keys.first())
    }

    /** What is held for [messageId], in the order it came; it is not held any more. */
    @Synchronized
    fun take(messageId: String, nowMs: Long): List<DirectArchive.Update> {
        evict(nowMs)
        return held.remove(messageId)?.map { it.second }.orEmpty()
    }

    private fun evict(nowMs: Long) {
        val lists = held.values.iterator()
        while (lists.hasNext()) {
            val list = lists.next()
            list.removeAll { (at, _) -> nowMs - at > windowMs }
            if (list.isEmpty()) lists.remove()
        }
    }
}
