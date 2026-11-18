package com.lightdecoder.imessage

import android.content.Context
import android.net.Uri
import android.service.notification.StatusBarNotification
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.chat.Capability
import com.lightdecoder.chat.ChatBackend
import com.lightdecoder.chat.ChatService
import com.lightdecoder.chat.Presence
import com.lightdecoder.messenger.MessengerNotificationListener
import com.lightdecoder.relay.BackfillProgress
import com.lightdecoder.relay.ContactsResult
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.Folder
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.RelayException
import com.lightdecoder.relay.RelayState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * iMessage, through the OpenBubbles app: what "Blue Bubbles" in the
 * Messages tab's menu turns on.
 *
 * OpenBubbles is the iMessage client (it is registered with Apple, with the
 * help of the user's iPhone). LightDecoder keeps an archive of what its
 * notifications show, and answers through their Reply button, which makes
 * OpenBubbles send the message in the background: a real iMessage, a blue
 * bubble on the other person's iPhone. Nothing here talks to Apple.
 *
 * Because everything comes from notifications:
 *  - a chat appears once someone writes in it; there is no older history;
 *  - a reply can be sent straight from here only to a chat whose
 *    notification offered a Reply button since the app started
 *    ([canReply]); for any other chat, and for pictures and other files,
 *    OpenBubbles is opened with the message filled in ([handOff]);
 *  - there are no reactions, typing or delivery reports.
 *
 * [isPaired] is whether "Blue Bubbles" is on. Nothing runs while it is off:
 * the listener drops OpenBubbles' notifications unread.
 */
class IMessageManager private constructor(
    context: Context,
    override val repo: ArchiveRepository,
) : ChatBackend {

    private val appContext: Context = context.applicationContext

    override val service: ChatService get() = ChatService.IMESSAGE
    override val capabilities: Set<Capability> = emptySet()

    private val _state = MutableStateFlow(currentState())
    override val state: StateFlow<RelayState> = _state.asStateFlow()
    override val typing: StateFlow<Map<String, Set<String>>> = MutableStateFlow(emptyMap())
    override val presence: StateFlow<Map<String, Presence>> = MutableStateFlow(emptyMap())
    override val backfill: StateFlow<BackfillProgress> = MutableStateFlow(BackfillProgress(running = false, 0, 0, 0))

    private val captureLock = Any()

    override fun isPaired(): Boolean = IMessagePrefs.enabled(appContext)

    /**
     * Connected while Blue Bubbles is on, OpenBubbles is installed and
     * LightDecoder may read notifications; otherwise why not.
     */
    private fun currentState(): RelayState = when {
        !IMessagePrefs.enabled(appContext) -> RelayState(RelayState.Phase.UNPAIRED)
        !OpenBubbles.isInstalled(appContext) -> RelayState(RelayState.Phase.ERROR, IMessageErrors.NOT_INSTALLED)
        !MessengerNotificationListener.hasAccess(appContext) -> RelayState(RelayState.Phase.DISCONNECTED, IMessageErrors.NO_ACCESS)
        else -> RelayState(RelayState.Phase.CONNECTED)
    }

    /** Looks again at what [state] depends on (after the user changed a setting or came back from one). */
    fun refreshState() {
        _state.value = currentState()
    }

    /** Turns Blue Bubbles on or off. Off forgets OpenBubbles' buttons and LightDecoder's iMessage notifications; the archive stays. */
    fun setEnabled(on: Boolean) {
        IMessagePrefs.setEnabled(appContext, on)
        if (!on) {
            IMessageReplies.clear()
            for (row in runCatching { repo.conversations(null) }.getOrDefault(emptyList())) {
                MessageNotifier.cancel(appContext, row.id, ChatService.IMESSAGE)
            }
        }
        refreshState()
    }

    // ============================== capture ==============================

    /**
     * One of OpenBubbles' notifications was posted (or was showing when the
     * listener connected: [live] false, nothing in it is announced again).
     * Runs on the listener's worker thread.
     */
    fun onNotification(sbn: StatusBarNotification, live: Boolean) {
        if (!IMessagePrefs.enabled(appContext)) return
        val notification = sbn.notification ?: return
        val chat = try { IMessageCapture.parse(sbn) } catch (e: RuntimeException) { null } ?: return
        val reply = try { IMessageCapture.replyAction(notification) } catch (e: RuntimeException) { null }
        val markRead = try { IMessageCapture.markReadAction(notification) } catch (e: RuntimeException) { null }
        val added = try { record(chat, live) } catch (e: RuntimeException) { emptyList() }
        IMessageReplies.update(chat.chatGuid, reply, markRead, notification.contentIntent, incoming = added.any { !it.fromMe })
    }

    /**
     * Stores [chat]'s messages that are not stored yet and returns them. The
     * user's own lines that are the echo of a message sent from here are
     * not stored twice ([isEcho]).
     */
    internal fun record(chat: CapturedIMessageChat, live: Boolean): List<MessageDto> = synchronized(captureLock) {
        val id = chat.chatGuid
        val existing = repo.conversation(id)
        val fresh = chat.messages.map { it to IMessageCapture.messageId(id, it) }
        val known = repo.knownMessageIds(fresh.map { it.second })
        val recent = repo.messages(id, limit = ECHO_LOOKBACK)
        val added = ArrayList<MessageDto>()
        for ((message, messageId) in fresh) {
            if (messageId in known) continue
            if (message.fromMe && isEcho(message, recent)) continue
            added += MessageDto(
                id = messageId,
                conversationId = id,
                senderId = if (message.fromMe) "" else message.senderHandle.ifEmpty { message.sender },
                senderName = message.sender,
                fromMe = message.fromMe,
                timestampMs = message.timestampMs,
                direction = if (message.fromMe) "out" else "in",
                status = if (message.fromMe) STATUS_SENT else "",
                transport = TRANSPORT,
                text = message.text,
                isOld = !live,
            )
        }
        val onScreen = MessageNotifier.visibleConversationId(ChatService.IMESSAGE) == id
        val newIncoming = added.any { !it.fromMe && !it.isOld }
        val me = ParticipantDto(id = ME, isMe = true)
        val others = if (chat.isGroup) {
            added.filter { !it.fromMe && it.senderName.isNotEmpty() }.map { ParticipantDto(id = it.senderId, name = it.senderName) }
                .ifEmpty { runCatching { repo.participants(id) }.getOrDefault(emptyList()).filter { !it.isMe }.map { ParticipantDto(id = it.id, name = it.name) } }
                .distinctBy { it.id }
        } else {
            listOf(ParticipantDto(id = chat.address.ifEmpty { id }, name = chat.title, number = chat.address, formattedNumber = chat.address))
        }
        repo.upsertConversation(
            ConversationDto(
                id = id,
                name = if (chat.isGroup) chat.title else "",
                isGroup = chat.isGroup,
                type = TRANSPORT,
                folder = existing?.folder?.takeIf { it.isNotEmpty() && it != Folder.DELETED.wire } ?: Folder.INBOX.wire,
                unread = (existing?.unread == true || newIncoming) && !onScreen,
                pinned = existing?.pinned == true,
                muted = existing?.muted == true,
                avatarColor = existing?.avatarColor.orEmpty(),
                participants = listOf(me) + others,
            ),
        )
        for (dto in added) {
            repo.upsertMessage(dto)
            if (live) MessageNotifier.onIncoming(appContext, repo, dto)
        }
        added
    }

    /**
     * Whether [line], one of the user's own lines in OpenBubbles'
     * notification, is a message already stored: one sent from here (it is
     * stored when it is handed over) or seen before under a slightly
     * different time. OpenBubbles stamps a reply's line itself, a moment
     * after LightDecoder stored it.
     */
    private fun isEcho(line: CapturedIMessage, recent: List<MessageRow>): Boolean = recent.any { row ->
        row.isOutgoing && row.text == line.text && kotlin.math.abs(row.timestampMs - line.timestampMs) <= ECHO_WINDOW_MS
    }

    // ============================== sending ==============================

    /** Whether a text to [conversationId] can be sent from here, without opening OpenBubbles. */
    fun canReply(conversationId: String): Boolean = IMessageReplies.canReply(conversationId)

    /**
     * Sends [text] through the chat's Reply button. It shows right away;
     * once OpenBubbles has it, it is marked sent (whether it reached the
     * other person is OpenBubbles' to say). Without a Reply button it shows
     * as failed with [IMessageErrors.NO_REPLY_BUTTON]: the screens offer
     * [handOff] instead.
     */
    override fun sendText(conversationId: String, text: String, replyToId: String?): String {
        requireEnabled()
        val body = text.trim()
        if (body.isEmpty()) throw RelayException(RelayException.INVALID_ARGUMENT, "empty message")
        val tmpId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        repo.insertLocalOutgoing(tmpId, conversationId, body, null, null, null, null, now)
        deliver(tmpId, conversationId, body, now)
        return tmpId
    }

    private fun deliver(tmpId: String, conversationId: String, text: String, nowMs: Long) {
        when (IMessageReplies.send(appContext, conversationId, text)) {
            IMessageReplies.Result.HANDED_OVER -> repo.upsertMessage(
                MessageDto(
                    id = SENT_PREFIX + tmpId,
                    conversationId = conversationId,
                    tmpId = tmpId,
                    fromMe = true,
                    timestampMs = nowMs,
                    direction = "out",
                    status = STATUS_SENT,
                    transport = TRANSPORT,
                    text = text,
                    isOld = true,
                ),
            )
            IMessageReplies.Result.NO_REPLY_BUTTON, IMessageReplies.Result.WITHDRAWN ->
                repo.setLocalState(tmpId, LocalState.FAILED, IMessageErrors.NO_REPLY_BUTTON)
        }
    }

    /** Pictures and files go through OpenBubbles' own screen ([handOff]). */
    override fun sendMedia(conversationId: String, uri: Uri, caption: String?, replyToId: String?): String =
        throw RelayException(IMessageErrors.HAND_OFF, "attachments are sent in OpenBubbles")

    override fun retry(tmpId: String) {
        val row = repo.message(MessageRow.tmpMessageId(tmpId)) ?: return
        if (row.localState != LocalState.FAILED || row.text.isEmpty()) return
        repo.setLocalState(tmpId, LocalState.SENDING)
        deliver(tmpId, row.conversationId, row.text, row.timestampMs)
    }

    override fun discardFailed(tmpId: String) {
        repo.applyDeletion(MessageRow.tmpMessageId(tmpId))
    }

    /**
     * Opens OpenBubbles with [text] (and the file at [attachment], if any)
     * filled in for [conversationId]: its chat if OpenBubbles knows it,
     * else a new chat with the person's address. The user sends it there.
     * False if OpenBubbles is not installed or could not be opened.
     */
    fun handOff(context: Context, conversationId: String, text: String, attachment: Uri? = null, mime: String? = null): Boolean {
        val pkg = OpenBubbles.installedPackage(appContext) ?: return false
        val shared = OpenBubbles.start(context, OpenBubbles.shareToChatIntent(pkg, conversationId, text, attachment, mime))
        if (shared) return true
        val address = OpenBubbles.addressOf(conversationId) ?: return false
        return OpenBubbles.start(context, OpenBubbles.newChatIntent(pkg, address, text))
    }

    /** Opens OpenBubbles on a new chat with [address] (a number or an email address) and [text] filled in. */
    fun startInOpenBubbles(context: Context, address: String, text: String = ""): Boolean {
        val pkg = OpenBubbles.installedPackage(appContext) ?: return false
        return OpenBubbles.start(context, OpenBubbles.newChatIntent(pkg, address, text))
    }

    /** The stored iMessage conversation with [address], if one has been seen. */
    fun conversationWith(address: String): String? {
        val wanted = IMessageAddress.key(address) ?: return null
        return runCatching { repo.conversations(null) }.getOrDefault(emptyList())
            .filter { !it.isGroup }
            .firstOrNull { row -> OpenBubbles.addressOf(row.id)?.let(IMessageAddress::key) == wanted }
            ?.id
    }

    // ============================== the rest of the backend ==============================

    override fun typing(conversationId: String) = Unit
    override fun watchPresence(conversationId: String) = Unit
    override fun setAppVisible(visible: Boolean) {
        if (visible) refreshState()
    }

    override suspend fun react(messageId: String, emoji: String, remove: Boolean): Unit =
        throw RelayException(WA_UNSUPPORTED, "no reactions through OpenBubbles' notifications")

    /** Marks it read here and, through OpenBubbles' Mark As Read button, in OpenBubbles (which then tells the sender if it is set to). */
    override suspend fun markRead(conversationId: String): Unit = withContext(Dispatchers.IO) {
        runCatching { repo.markConversationRead(conversationId) }
        MessageNotifier.cancel(appContext, conversationId, ChatService.IMESSAGE)
        runCatching { IMessageReplies.markRead(appContext, conversationId) }
    }

    override suspend fun loadOlder(conversationId: String, count: Int): Int = 0

    /** Removes it from this phone only; OpenBubbles keeps it. */
    override suspend fun deleteMessage(messageId: String): Unit = withContext(Dispatchers.IO) {
        repo.applyDeletion(messageId)
    }

    /** Removes the chat from this phone only; it comes back if someone writes in it again. */
    override suspend fun deleteConversation(conversationId: String): Unit = withContext(Dispatchers.IO) {
        com.lightdecoder.chat.ShareShortcuts.remove(appContext, ChatService.IMESSAGE, conversationId)
        MessageNotifier.cancel(appContext, conversationId, ChatService.IMESSAGE)
        repo.deleteConversationLocal(conversationId)
    }

    override suspend fun setArchived(conversationId: String, archived: Boolean): Unit = withContext(Dispatchers.IO) {
        repo.setConversationFolder(conversationId, if (archived) Folder.ARCHIVE else Folder.INBOX)
    }

    override suspend fun setMuted(conversationId: String, muted: Boolean): Unit = withContext(Dispatchers.IO) {
        repo.setConversationMuted(conversationId, muted)
    }

    override suspend fun refreshConversations(folder: Folder): Int = 0

    override suspend fun mediaFile(messageId: String, partId: String, thumbnail: Boolean): File? = null

    override suspend fun requestFullSize(messageId: String, partId: String): Unit =
        throw RelayException(WA_UNSUPPORTED, "not for iMessage")

    override suspend fun contacts(refresh: Boolean): ContactsResult = withContext(Dispatchers.IO) {
        ContactsResult(runCatching { repo.contacts() }.getOrDefault(emptyList()), fromPhone = false)
    }

    override suspend fun startConversation(numbers: List<String>, groupName: String?): String =
        throw RelayException(IMessageErrors.HAND_OFF, "new chats start in OpenBubbles")

    override suspend fun markNotSpam(conversationId: String): Unit = throw RelayException(WA_UNSUPPORTED, "no spam folder")

    override suspend fun restoreDeletedLocally(conversationId: String): Boolean = false

    override suspend fun purgeDeleted(conversationId: String?): Int = 0

    /** Deletes the iMessage archive while Blue Bubbles is off. */
    override suspend fun deleteLocalArchive(): Boolean = withContext(Dispatchers.IO) {
        if (isPaired()) return@withContext false
        IMessageReplies.clear()
        com.lightdecoder.chat.ShareShortcuts.removeAll(appContext, ChatService.IMESSAGE)
        runCatching { repo.clearAll() }.isSuccess
    }

    override suspend fun useHere(): Unit = throw RelayException(WA_UNSUPPORTED, "not for iMessage")

    private fun requireEnabled() {
        if (!isPaired()) throw RelayException(RelayException.NOT_PAIRED, "Blue Bubbles is off")
    }

    companion object {
        /** messages.transport_type and conversations' type of everything here. */
        const val TRANSPORT = "IMESSAGE"

        /** The status of the user's messages once OpenBubbles has them. */
        internal const val STATUS_SENT = "OUTGOING_COMPLETE"

        /** Id prefix of a message sent from here (the rest is its tmpId). */
        private const val SENT_PREFIX = "ld:"
        private const val ME = "me"
        private const val ECHO_LOOKBACK = 40
        private const val ECHO_WINDOW_MS = 3 * 60 * 1000L
        private const val WA_UNSUPPORTED = "unsupported"

        @Volatile
        private var instance: IMessageManager? = null

        fun get(context: Context): IMessageManager =
            instance ?: synchronized(this) {
                instance ?: IMessageManager(context, ArchiveRepository.get(context, ChatService.IMESSAGE)).also { instance = it }
            }

        internal fun installForTesting(context: Context, repo: ArchiveRepository): IMessageManager {
            require(repo.service == ChatService.IMESSAGE) { "the repository is ${repo.service}'s, not iMessage's" }
            return synchronized(this) { IMessageManager(context, repo).also { instance = it } }
        }

        internal fun resetForTesting() = synchronized(this) {
            instance = null
            IMessageReplies.clear()
        }
    }
}

/** The iMessage link's own error codes (it shares the rest with [RelayException]). */
object IMessageErrors {
    /** OpenBubbles is not installed. */
    const val NOT_INSTALLED = "openbubbles_not_installed"

    /** LightDecoder may not read notifications, so nothing arrives from OpenBubbles. */
    const val NO_ACCESS = "no_notification_access"

    /** This chat has no Reply button to send through: it has to be sent in OpenBubbles. */
    const val NO_REPLY_BUTTON = "no_reply_button"

    /** This is done in OpenBubbles' own screen (attachments, new chats). */
    const val HAND_OFF = "hand_off"
}

/** Comparing a phone number or email address from OpenBubbles with one stored for the Messages tab. */
object IMessageAddress {
    /**
     * A key that two spellings of the same address share: an email address
     * in lower case; a phone number as its last ten digits (enough to tell
     * numbers apart, and blind to "+1", spaces and dashes). Null for
     * something that is neither.
     */
    fun key(address: String): String? {
        val text = address.trim().removePrefix("tel:").removePrefix("mailto:")
        if (text.contains('@')) return text.lowercase().takeIf { it.length > 2 }
        val digits = text.filter { it.isDigit() }
        if (digits.length < 7) return null
        return "#" + digits.takeLast(10)
    }
}
