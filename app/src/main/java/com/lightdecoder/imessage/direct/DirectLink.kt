package com.lightdecoder.imessage.direct

import android.content.Context
import android.util.Log
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageAddress
import com.lightdecoder.imessage.IMessageLog
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.Folder
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.Outbox
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.PartDto
import com.lightdecoder.relay.RelayException
import com.lightdecoder.relay.RelayManager
import com.lightdecoder.reactions.ReadTimes
import com.lightdecoder.ui.chat.EditRule
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import uniffi.rust_bridge.Reaction

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
 * [IMessageManager] hands sends, retries, unsends, edits, tapbacks, typing and read receipts here
 * while [active]; [DirectSinkService] hands over what Apple sent ([apply]). What arrives for a
 * message that is not stored yet (a receipt for a message whose send has not returned yet) is held
 * for a while ([HeldUpdates]) and applied once it is.
 */
class DirectLink private constructor(
    private val app: Context,
    requests: Requests?,
    /** How long after the last keystroke the chat is told the user stopped typing. */
    private val typingIdleMs: Long = TYPING_IDLE_MS,
    /** How often the chat is told again, while the user goes on typing, that they are. */
    typingRepeatMs: Long = TYPING_REPEAT_MS,
) {

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

    /** When the chats were last told the user is typing ([typing]). */
    private val typingThrottle = TypingThrottle(typingRepeatMs)

    /** Typing changes for Apple, sent one at a time in the order they happened. */
    private val typingOut = Channel<TypingNotice>(Channel.UNLIMITED)

    /** The GUIDs of the messages a read receipt went for ([markRead]): each goes once. */
    private val readSent = OnceSet()

    /** Addresses Apple was asked about when their conversation opened ([reachOnce]), and when. */
    private val reachAsked = HashMap<String, Long>()

    init {
        if (active) scope.launch { failInterruptedSends() }
        scope.launch { for (notice in typingOut) tell(notice) }
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
     * one, or a new one. Null when the library is not set up or Apple says it cannot get iMessages;
     * throws [DirectEngine.ImessageError] when Apple could not be asked.
     */
    suspend fun conversationFor(address: String): String? {
        if (!active) return null
        manager.conversationWith(address)?.let { return it }
        val reply = requests.call(DirectWire.REACH, DirectWire.reach(address), REACH_TIMEOUT_MS)
        // The library could not be asked: that says nothing about whether they have iMessage.
        DirectWire.errorOf(reply)?.let { throw DirectEngine.ImessageError(it) }
        val dto = reply.optJSONObject("conversation")?.let(ConversationDto::fromJson) ?: return null
        val stored = toArchive(dto)
        repo.upsertConversation(stored)
        return stored.id
    }

    /**
     * As an iPhone does when a conversation with [address] opens: Apple is asked whether they have
     * iMessage, and if they do their iMessage chat is made ([conversationFor]), so what is typed
     * there goes blue. Asked at most once in [REACH_AGAIN_MS] per address ([now] is the time);
     * asked again next time when Apple could not be asked. Their iMessage chat, or null.
     */
    suspend fun reachOnce(address: String, now: Long = System.currentTimeMillis()): String? {
        if (!active) return null
        val key = IMessageAddress.key(address) ?: return null
        synchronized(reachAsked) {
            val at = reachAsked[key]
            if (at != null && now - at < REACH_AGAIN_MS) return null
            reachAsked[key] = now
        }
        var answered = false
        try {
            return conversationFor(address).also { answered = true }
        } catch (e: DirectEngine.ImessageError) {
            Log.i(TAG, "could not ask Apple about a conversation's person: ${e.message}")
            return null
        } finally {
            if (!answered) synchronized(reachAsked) { reachAsked.remove(key) }
        }
    }

    // --- sending ----------------------------------------------------------------------------------

    /** Like every send in the app: a local copy now ("tmp:<tmpId>"), the sent message once Apple confirms it. */
    fun sendText(conversationId: String, text: String, replyToId: String?): String {
        // The message ends the typing indicator on their side: no "stopped typing" after it.
        typingThrottle.sent(conversationId)
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
            val target = targetOf(conversationId) ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_unreachable))
            // An inline reply needs the GUID Apple knows the answered message by: messages the
            // library sent or received have it (imsg:<GUID>); older Blue Bubbles rows do not, and
            // a reply to one goes as a message of its own.
            val replyGuid = replyToId?.let(DirectArchive::guidOf)
            val replyPart = replyGuid?.let { DirectWire.replyPart(repo.message(replyToId)?.text.orEmpty()) }
            val reply = requests.call(DirectWire.SEND, DirectWire.send(target, text, replyGuid, replyPart), SEND_TIMEOUT_MS)
            DirectWire.errorOf(reply)?.let {
                throw if (DirectWire.unconfirmed(reply)) DirectEngine.Unconfirmed(it) else DirectEngine.ImessageError(it)
            }
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
            // Apple not answering in time leaves it open whether it went: the user is asked before
            // it is sent again (MessageStatus.outcomeUnknown), as for any send that may have gone.
            repo.setLocalState(tmpId, LocalState.FAILED, if (e is DirectEngine.Unconfirmed) Outbox.UNCONFIRMED else DirectEngine.readable(e))
        }
    }

    /**
     * Whether [row] is one of the user's iMessages the library can unsend now: [unsendable], within
     * Apple's 2 minutes, in a chat it can reach. A caller that already knows whether the chat can be
     * reached ([canSend]) passes it as [reachable], so the archive is not read again.
     */
    fun canUnsend(row: MessageRow, now: Long = System.currentTimeMillis(), reachable: Boolean? = null): Boolean =
        active && unsendable(row) && UnsendWindow.canUnsend(row.timestampMs, now) && (reachable ?: canSend(row.conversationId))

    /**
     * Retracts one part of one of the user's iMessages within Apple's 2-minute window. Who it went
     * to comes from the archive, so this works after a restart of either process. Here that part
     * then goes, as when it is unsent on another device: with nothing left the message reads "You
     * unsent a message.", as on an iPhone. Throws [DirectEngine.ImessageError] saying why when it
     * does not go through.
     */
    suspend fun unsend(messageId: String, part: Int = 0) {
        val guid = DirectArchive.guidOf(messageId) ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_unsend_no_guid))
        val row = repo.message(messageId) ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_not_stored))
        // An unsent message's or a group event's row is the user's too, but there is nothing to unsend.
        if (!row.isOutgoing || row.direction != "out") throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_unsend_not_yours))
        if (!UnsendWindow.canUnsend(row.timestampMs, System.currentTimeMillis())) {
            throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_unsend_too_late))
        }
        val target = targetOf(row.conversationId) ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_unreachable))
        // Once asked, Apple's answer is waited for and stored even if the screen that asked closes.
        withContext(NonCancellable) {
            val reply = requests.call(DirectWire.UNSEND, DirectWire.unsend(target, guid, part), SEND_TIMEOUT_MS)
            DirectWire.errorOf(reply)?.let { throw DirectEngine.ImessageError(it) }
            apply(DirectArchive.Update.Unsent(messageId, part, fromMe = true, senderName = ""))
        }
        IMessageLog.add(app, "${IMessageLog.chatRef(row.conversationId)}: unsent through the iMessage library (Apple confirmed)")
    }

    // --- edits and tapbacks ---------------------------------------------------------------------

    /**
     * Whether [row] is one of the user's iMessages the library can edit now: text only, sent with
     * a GUID, younger than 15 minutes, edited fewer than 5 times, in a chat it can reach. A caller
     * that already knows whether the chat can be reached ([canSend]) passes it as [reachable].
     */
    fun canEdit(row: MessageRow, now: Long = System.currentTimeMillis(), reachable: Boolean? = null): Boolean =
        active && editable(row) && inEditWindow(row, now) && (reachable ?: canSend(row.conversationId))

    /**
     * Changes the text of one of the user's iMessages ([canEdit]): Apple's answer is waited for,
     * then the message reads [text] here too, marked edited. Throws a [RelayException] with
     * [RelayException.INVALID_ARGUMENT] when it is too late (15 minutes, or 5 edits), else
     * [DirectEngine.ImessageError] saying why when it does not go through.
     */
    suspend fun edit(messageId: String, text: String) {
        val row = repo.message(messageId) ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_not_stored))
        val guid = DirectArchive.guidOf(messageId)
        if (guid == null || !editable(row)) throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_edit_not_editable))
        if (!inEditWindow(row, System.currentTimeMillis())) throw RelayException(RelayException.INVALID_ARGUMENT, "too late to edit")
        val target = targetOf(row.conversationId) ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_unreachable))
        // Once asked, Apple's answer is waited for and stored even if the screen that asked closes.
        withContext(NonCancellable) {
            val reply = requests.call(DirectWire.EDIT, DirectWire.edit(target, guid, 0, text), SEND_TIMEOUT_MS)
            DirectWire.errorOf(reply)?.let { throw DirectEngine.ImessageError(it) }
            synchronized(this@DirectLink) {
                // Read again: a tapback or a receipt may have come in while Apple was asked, or it
                // may have been unsent meanwhile (here or on another device), and then it stays so.
                val current = repo.message(messageId) ?: return@withContext
                val extra = IMessageExtra.parse(current.extra)
                if (current.direction == "system" || 0 in extra.unsent) return@withContext
                val next = extra.copy(parts = extra.parts + (0 to text), edits = extra.edits + 1)
                repo.editText(messageId, next.text(), System.currentTimeMillis(), next.toJson())
            }
        }
        IMessageLog.add(app, "${IMessageLog.chatRef(row.conversationId)}: edited through the iMessage library (Apple confirmed)")
    }

    /**
     * Whether the user can put a tapback on [row] through the library: an iMessage with its GUID, in
     * a chat it can reach. A caller that already knows whether the chat can be reached ([canSend])
     * passes it as [reachable].
     */
    fun canReact(row: MessageRow, reachable: Boolean? = null): Boolean =
        active && reactable(row) && (reachable ?: canSend(row.conversationId))

    /**
     * Puts the user's tapback [emoji] on [messageId], or takes it back ([remove]). As on an iPhone
     * a person has one tapback per message: a new one replaces the user's earlier one. It shows
     * at once, and the message is put back as it was if Apple does not take it. Throws
     * [DirectEngine.ImessageError] saying why when it does not go through.
     */
    suspend fun react(messageId: String, emoji: String, remove: Boolean) {
        val row = repo.message(messageId) ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_not_stored))
        val guid = DirectArchive.guidOf(messageId)
        if (guid == null || !reactable(row)) throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_react_not_possible))
        val target = targetOf(row.conversationId) ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_unreachable))
        val enable = !remove
        val now = System.currentTimeMillis()
        // Shown at once, under the lock every archive write here takes; the user's tapback before
        // and after are kept, to put it back if Apple does not take it.
        val (before, after) = synchronized(this) {
            val was = myTapback(messageId)
            repo.applyReaction(messageId, ME, emoji, enable, fromMe = true, atMs = now)
            was to myTapback(messageId)
        }
        // Once asked, Apple's answer is waited for, and a refusal undone, even if the screen that asked closes.
        withContext(NonCancellable) {
            try {
                val reply = requests.call(
                    DirectWire.REACT, DirectWire.react(target, guid, 0, emoji, enable, tapbackText(row.text)), SEND_TIMEOUT_MS,
                )
                DirectWire.errorOf(reply)?.let { throw DirectEngine.ImessageError(it) }
            } catch (e: Exception) {
                synchronized(this@DirectLink) { undoTapback(messageId, before, after, now) }
                throw e
            }
        }
        IMessageLog.add(app, "${IMessageLog.chatRef(row.conversationId)}: tapback ${if (remove) "taken back" else "sent"} through the iMessage library (Apple confirmed)")
    }

    /** The user's tapback on [messageId], if any. */
    private fun myTapback(messageId: String): String? = repo.message(messageId)?.reactions?.firstOrNull { it.fromMe }?.emoji

    /**
     * Apple did not take the change of the user's tapback on [messageId] from [before] to [after]:
     * it is put back to [before] (the earlier tapback, or none). Only while it is still [after]: one
     * chosen since, here or on another of the user's devices, stays.
     */
    private fun undoTapback(messageId: String, before: String?, after: String?, atMs: Long) {
        if (before == after || myTapback(messageId) != after) return
        when {
            before != null -> repo.applyReaction(messageId, ME, before, enable = true, fromMe = true, atMs = atMs)
            after != null -> repo.applyReaction(messageId, ME, after, enable = false, fromMe = true, atMs = atMs)
        }
    }

    /**
     * Local copies a previous run was still sending: Apple's answer was lost with it, so they may
     * have gone; a retry asks first ([Outbox.INTERRUPTED], see MessageStatus.outcomeUnknown).
     */
    private fun failInterruptedSends() {
        try {
            repo.recentOutgoing(limit = 500)
                .filter { it.isLocal && it.localState != LocalState.FAILED && it.localState != LocalState.SYNCED }
                .filter { canSend(it.conversationId) }
                .forEach { repo.setLocalState(it.tmpId, LocalState.FAILED, Outbox.INTERRUPTED) }
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not check interrupted sends", e)
        }
    }

    // --- typing and read receipts ---------------------------------------------------------------

    /**
     * The user typed in [conversationId]'s composer. As an iPhone does, the chat is told the user is
     * typing at once and again every 50 seconds while it goes on, and that the user stopped once
     * nothing was typed for 5 seconds ([TypingThrottle]).
     */
    fun typing(conversationId: String) {
        if (!active) return
        val at = System.currentTimeMillis()
        typingThrottle.keystroke(conversationId, at)?.let { turn -> typingOut.trySend(TypingNotice(conversationId, true, turn)) }
        scope.launch {
            delay(typingIdleMs)
            if (typingThrottle.idle(conversationId, at)) typingOut.trySend(TypingNotice(conversationId, false))
        }
    }

    /** The user emptied [conversationId]'s composer: the chat is told the user stopped typing, if it was told otherwise. */
    fun stopTyping(conversationId: String) {
        if (typingThrottle.cleared(conversationId)) typingOut.trySend(TypingNotice(conversationId, false))
    }

    /** A typing change for Apple: [typing] or stopped, in [conversationId]; "typing" for the [turn] of typing it began ([TypingThrottle]). */
    private class TypingNotice(val conversationId: String, val typing: Boolean, val turn: Int = 0)

    private suspend fun tell(notice: TypingNotice) {
        val chat = notice.conversationId
        // Typing that ended while this waited (a message went, the composer was emptied, a pause):
        // it would come after the message, or after "stopped", and leave the bubble showing.
        if (notice.typing && !typingThrottle.current(chat, notice.turn)) return
        sendTyping(chat, notice.typing)
        // A message went while this was on its way, and may have reached them first: nothing
        // else ends the bubble it would leave under the message.
        if (notice.typing && typingThrottle.endedBySend(chat, notice.turn)) sendTyping(chat, false)
    }

    /** Apple does not answer typing: nothing waits for it, and a failure is only logged. */
    private suspend fun sendTyping(conversationId: String, typing: Boolean) {
        try {
            val target = targetOf(conversationId) ?: return
            val reply = requests.call(DirectWire.TYPING, DirectWire.typing(target, typing), NOTICE_TIMEOUT_MS)
            DirectWire.errorOf(reply)?.let { Log.i(TAG, "typing not sent: $it") }
        } catch (e: Exception) {
            Log.w(TAG, "typing not sent", e)
        }
    }

    /**
     * The user read [conversationId]: it is read here and its notification goes. Apple is told once
     * per latest message, for the user's other devices (they clear it too) and, with read receipts
     * on (the iMessage screen's setting, read by the library), for the other person of a one-to-one
     * chat, who then sees "Read".
     */
    suspend fun markRead(conversationId: String) {
        runCatching { repo.markConversationRead(conversationId) }
        MessageNotifier.cancel(app, conversationId, ChatService.IMESSAGE)
        if (!active) return
        val (guid, target) = try {
            val guid = repo.latestIncomingMessageId(conversationId, DirectArchive.PREFIX)?.let(DirectArchive::guidOf) ?: return
            guid to (targetOf(conversationId) ?: return)
        } catch (e: RuntimeException) {
            Log.w(TAG, "read receipt not sent", e)
            return
        }
        if (!readSent.claim(guid)) return
        var told = false
        try {
            // Once asked, it goes even if the screen that read the chat closes meanwhile.
            withContext(NonCancellable) {
                val error = DirectWire.errorOf(requests.call(DirectWire.READ, DirectWire.read(target, guid), NOTICE_TIMEOUT_MS))
                if (error == null) told = true else Log.i(TAG, "read receipt not sent: $error")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "read receipt not sent", e)
        } finally {
            // Not gone: the next look at the chat tries again.
            if (!told) readSent.release(guid)
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
            // A chat's latest message (time, id, snippet) moves with its messages only
            // (ArchiveRepository.upsertMessage). Given a time here, a stored chat's would be written
            // blank, and a chat just made ([conversationFor], nothing in it yet) would go to the top
            // of the list (and its Messages conversation with it) with nothing to show.
            lastMessageTs = 0,
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
                val advanced = HashSet<String>()
                val stored = repo.advanceMessageStatus(
                    update.messageIds, update.status, DirectArchive.statusCode(update.status), DirectArchive::statusRank, advanced,
                )
                // "Read 9:41" under the message: when it was seen to be read. Only a message this
                // receipt made read: one read before (the same receipt handed over again, or a
                // message read before read times were kept) keeps its time, or has none.
                if (update.status == DirectArchive.STATUS_READ) noteRead(advanced)
                for (id in update.messageIds - stored) hold(id, DirectArchive.Update.Receipt(listOf(id), update.status))
            }
            is DirectArchive.Update.Edit -> edit(update)
            is DirectArchive.Update.Reaction -> reaction(update)
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

    private fun noteRead(messageIds: Collection<String>) {
        if (messageIds.isEmpty()) return
        val now = System.currentTimeMillis()
        try {
            val times = ReadTimes.get(app)
            for (id in messageIds) times.note(ChatService.IMESSAGE, id, now, live = true)
        } catch (e: RuntimeException) {
            Log.w(TAG, "read time not kept", e)
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
     * Someone put a tapback on a message or took it back. A new one on one of the user's messages
     * is announced, as an iPhone does ("Loved “See you at 6”"), unless its chat is on screen.
     */
    private fun reaction(update: DirectArchive.Update.Reaction) {
        val row = repo.message(update.messageId) ?: return hold(update.messageId, update)
        val who = if (update.fromMe) ME else update.participantId
        // Apple's journal hands a tapback over again after a restart: one already shown is not announced again.
        val known = row.reactions.any { it.emoji == update.emoji && who in it.participantIds }
        repo.applyReaction(update.messageId, who, update.emoji, update.enable, update.fromMe, update.atMs)
        if (update.fromMe || !update.enable || known || !row.isOutgoing || row.direction != "out") return
        val quoted = tapbackText(row.text).takeIf { it.isNotBlank() }?.let { app.getString(R.string.imessage_tapback_quote, it) }
            ?: app.getString(R.string.imessage_tapback_a_message)
        MessageNotifier.onIncoming(
            app, repo,
            MessageDto(
                id = update.messageId,
                conversationId = row.conversationId,
                senderId = update.participantId,
                senderName = nameOf(update.participantId),
                timestampMs = update.atMs,
                direction = "in",
                transport = IMessageManager.TRANSPORT,
                text = tapbackNotice(app, update.emoji, quoted),
            ),
        )
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

        /**
         * Whether [row] is the kind of message an iPhone lets its sender edit: one of the user's own
         * iMessages (with its GUID), sent and not failed, text alone in its first part (no media,
         * no iMessage app), not a row of the conversation's own. The time and the number of edits
         * are [inEditWindow]'s.
         */
        internal fun editable(row: MessageRow): Boolean {
            if (!row.isOutgoing || row.direction != "out" || row.isLocal || !DirectArchive.isImessage(row.id)) return false
            if (row.localState == LocalState.FAILED || DirectArchive.statusRank(row.status) < 0) return false
            if (row.text.isBlank() || row.media.isNotEmpty()) return false
            val extra = IMessageExtra.parse(row.extra)
            return extra.app == null && extra.parts.keys.all { it == 0 }
        }

        /**
         * Whether [row] is the kind of message the library unsends whole: one of the user's own
         * iMessages (with its GUID), sent and not failed, all of it in its first part. An unsend takes
         * back one part, so a message with more (a caption and an attachment sent from an iPhone) is
         * not offered: unsending part 0 would leave the rest with the people it went to. The time is
         * [UnsendWindow]'s.
         */
        internal fun unsendable(row: MessageRow): Boolean {
            if (!row.isOutgoing || row.direction != "out" || row.isLocal || !DirectArchive.isImessage(row.id)) return false
            if (row.localState == LocalState.FAILED || DirectArchive.statusRank(row.status) < 0) return false
            val extra = IMessageExtra.parse(row.extra)
            if (0 in extra.unsent) return false
            return extra.parts.keys.all { it == 0 } && row.media.all { it.partId == DirectArchive.partId(0) }
        }

        /** Whether [row] is still young enough to edit (15 minutes) and was edited fewer than 5 times. */
        internal fun inEditWindow(row: MessageRow, now: Long): Boolean =
            now - row.timestampMs < EditRule.WINDOW_MS &&
                IMessageExtra.parse(row.extra).edits < IMessageExtra.MAX_EDITS

        /** Whether [row] can take a tapback: a message (not a row of the conversation's own) Apple knows by its GUID. */
        internal fun reactable(row: MessageRow): Boolean =
            DirectArchive.isImessage(row.id) && !row.isLocal && row.direction != "system"

        /** At most this much of a message's text goes with a tapback (for the other side's notification). */
        private const val TAPBACK_TEXT_MAX = 100

        /** A message's text as a tapback carries it: at most [TAPBACK_TEXT_MAX] characters, never half an emoji. */
        internal fun tapbackText(text: String): String {
            if (text.length <= TAPBACK_TEXT_MAX) return text
            val end = if (Character.isHighSurrogate(text[TAPBACK_TEXT_MAX - 1])) TAPBACK_TEXT_MAX - 1 else TAPBACK_TEXT_MAX
            return text.substring(0, end)
        }

        /**
         * The words of the notification for someone's tapback [emoji] on the user's message, as an
         * iPhone puts them ("Loved “See you at 6”"). [quoted] is the message as the notification
         * names it (its text in quotes, or "a message").
         */
        internal fun tapbackNotice(context: Context, emoji: String, quoted: String): String {
            val verb = when (DirectArchive.reactionOf(emoji)) {
                Reaction.Heart -> R.string.imessage_tapback_loved
                Reaction.Like -> R.string.imessage_tapback_liked
                Reaction.Dislike -> R.string.imessage_tapback_disliked
                Reaction.Laugh -> R.string.imessage_tapback_laughed
                Reaction.Emphasize -> R.string.imessage_tapback_emphasized
                Reaction.Question -> R.string.imessage_tapback_questioned
                else -> return context.getString(R.string.imessage_tapback_emoji, emoji, quoted)
            }
            return context.getString(verb, quoted)
        }

        /** A send waits for Apple's confirmation (up to 45 s) plus the session coming up. */
        private const val SEND_TIMEOUT_MS = 90_000L
        private const val REACH_TIMEOUT_MS = 25_000L

        /** Apple is asked again about a conversation's person ([reachOnce]) after this long: they may have got iMessage since. */
        const val REACH_AGAIN_MS = 6 * 60 * 60 * 1000L

        /** Typing and read receipts wait for no answer from Apple: only for the session coming up. */
        private const val NOTICE_TIMEOUT_MS = 30_000L

        /** "Stopped typing" goes this long after the last keystroke, as on an iPhone. */
        const val TYPING_IDLE_MS = 5_000L

        /** While the user goes on typing, the chat is told again this often. */
        const val TYPING_REPEAT_MS = 50_000L

        @Volatile
        private var instance: DirectLink? = null

        /** Only in the main process. */
        fun get(context: Context): DirectLink =
            instance ?: synchronized(this) {
                instance ?: DirectLink(context.applicationContext, null).also { instance = it }
            }

        /** Tests: a fresh link whose requests [requests] answers, instead of the ":imessage" process. */
        internal fun installForTesting(
            context: Context,
            typingIdleMs: Long = TYPING_IDLE_MS,
            typingRepeatMs: Long = TYPING_REPEAT_MS,
            requests: Requests,
        ): DirectLink =
            synchronized(this) {
                DirectLink(context.applicationContext, requests, typingIdleMs, typingRepeatMs).also { instance = it }
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

/**
 * When to tell a chat the user is typing, as an iPhone does: on the first keystroke, again every
 * [repeatMs] while typing goes on, and "stopped" once nothing more was typed for a while (the
 * caller waits, then asks [idle]) or the composer was emptied. A sent message ends it without a
 * "stopped". Times are passed in.
 *
 * Each stretch of typing in a chat is a turn, counted from 0; a send, a clear or a pause ends it.
 * A "typing" that waited to go is out of date once its turn ended ([current]), and one that was on
 * its way when a message went may have reached them after the message ([endedBySend]).
 */
internal class TypingThrottle(private val repeatMs: Long = DirectLink.TYPING_REPEAT_MS) {

    /** When "typing" last went, by chat: only chats that were told the user is typing. */
    private val told = HashMap<String, Long>()
    private val lastKeystroke = HashMap<String, Long>()

    /** The turn of typing each chat is in: how many of its turns ended. */
    private val turns = HashMap<String, Int>()

    /** The turn a sent message ended, by chat. */
    private val sentIn = HashMap<String, Int>()

    /** A keystroke in [conversationId] at [nowMs]: the turn of typing it is in when "typing" should go now, else null. */
    @Synchronized
    fun keystroke(conversationId: String, nowMs: Long): Int? {
        lastKeystroke[conversationId] = nowMs
        val at = told[conversationId]
        if (at != null && nowMs - at < repeatMs) return null
        told[conversationId] = nowMs
        return turnOf(conversationId)
    }

    /** A while after the keystroke at [keystrokeMs]: true when "stopped typing" should go now (nothing typed since). */
    @Synchronized
    fun idle(conversationId: String, keystrokeMs: Long): Boolean {
        if (lastKeystroke[conversationId] != keystrokeMs) return false
        return forget(conversationId)
    }

    /** The composer was emptied: true when "stopped typing" should go now. */
    @Synchronized
    fun cleared(conversationId: String): Boolean = forget(conversationId)

    /** A message went: it ends the typing on the other side, nothing more to send. */
    @Synchronized
    fun sent(conversationId: String) {
        val turn = turnOf(conversationId)
        if (forget(conversationId)) sentIn[conversationId] = turn
    }

    /** Whether [turn] of [conversationId]'s typing is still going on: its "typing" is not out of date. */
    @Synchronized
    fun current(conversationId: String, turn: Int): Boolean = turnOf(conversationId) == turn

    /** Whether a sent message ended [turn] of [conversationId]'s typing (asked once). */
    @Synchronized
    fun endedBySend(conversationId: String, turn: Int): Boolean {
        if (sentIn[conversationId] != turn) return false
        sentIn.remove(conversationId)
        return true
    }

    private fun turnOf(conversationId: String): Int = turns[conversationId] ?: 0

    /** Ends the chat's turn of typing: true when it was told the user is typing. */
    private fun forget(conversationId: String): Boolean {
        lastKeystroke.remove(conversationId)
        if (told.remove(conversationId) == null) return false
        turns[conversationId] = turnOf(conversationId) + 1
        return true
    }
}

/** Keys done once each ([claim]), the most recent [max] remembered. */
internal class OnceSet(private val max: Int = 500) {
    private val done = LinkedHashSet<String>()

    /** True the first time [key] is claimed (and after it was [release]d). */
    @Synchronized
    fun claim(key: String): Boolean {
        if (!done.add(key)) return false
        while (done.size > max) done.remove(done.first())
        return true
    }

    /** It did not get done after all: the next [claim] of it is the first again. */
    @Synchronized
    fun release(key: String) {
        done.remove(key)
    }
}
