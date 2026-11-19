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
 *    notification offered a Reply button since the app started, and not
 *    the same text twice in answer to the same message ([canReply]); for
 *    anything else, and for pictures and other files, OpenBubbles is opened
 *    with the message filled in ([handOff]);
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
        !MessengerNotificationListener.hasAccess(appContext) -> RelayState(RelayState.Phase.ERROR, IMessageErrors.NO_ACCESS)
        else -> RelayState(RelayState.Phase.CONNECTED)
    }

    /** Looks again at what [state] depends on (after the user changed a setting or came back from one). */
    fun refreshState() {
        val before = _state.value
        val now = currentState()
        _state.value = now
        if (now.phase != before.phase || now.code != before.code) {
            IMessageLog.add(appContext, "State: ${now.phase} ${now.code}".trimEnd() + "; " + setupSummary())
        }
    }

    /** "OpenBubbles com.openbubbles.messaging 1.15.0; notification access yes": what Blue Bubbles needs. */
    fun setupSummary(): String {
        val pkg = OpenBubbles.installedPackage(appContext)
        val version = pkg?.let { runCatching { appContext.packageManager.getPackageInfo(it, 0).versionName }.getOrNull() }
        return "OpenBubbles " + (if (pkg == null) "not installed" else "$pkg ${version.orEmpty()}".trimEnd()) +
            "; notification access " + (if (MessengerNotificationListener.hasAccess(appContext)) "yes" else "NO")
    }

    /** Turns Blue Bubbles on or off. Off forgets OpenBubbles' buttons and LightDecoder's iMessage notifications; the archive stays. */
    fun setEnabled(on: Boolean) {
        IMessagePrefs.setEnabled(appContext, on)
        IMessageLog.add(appContext, if (on) "Blue Bubbles turned on; ${setupSummary()}" else "Blue Bubbles turned off")
        if (!on) {
            IMessageReplies.clear()
            com.lightdecoder.chat.ShareShortcuts.removeAll(appContext, ChatService.IMESSAGE)
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
        val chat = try { IMessageCapture.parse(sbn) } catch (e: RuntimeException) { null }
        if (chat == null) {
            // Only a message notification that could not be read is worth a line (OpenBubbles posts others too).
            if (sbn.tag == OpenBubbles.MESSAGE_TAG) IMessageLog.add(appContext, "A message notification from ${sbn.packageName} could not be read")
            return
        }
        val reply = try { IMessageCapture.replyAction(notification) } catch (e: RuntimeException) { null }
        val markRead = try { IMessageCapture.markReadAction(notification) } catch (e: RuntimeException) { null }
        val hadReply = IMessageReplies.canReply(chat.chatGuid)
        // The buttons first: by the time the messages show, they can be answered.
        IMessageReplies.update(chat.chatGuid, reply, markRead, notification.contentIntent, chat.messageGuid)
        val ref = IMessageLog.chatRef(chat.chatGuid)
        if (hadReply && reply == null) IMessageLog.add(appContext, "$ref: OpenBubbles' notification has no Reply button now (messages to it open OpenBubbles)")
        try {
            record(chat, live, if (reply != null) "Reply button yes" else "Reply button NO")
        } catch (e: RuntimeException) {
            // A full disk or a damaged file: these messages are not kept.
            IMessageLog.add(appContext, "$ref: could not store a notification's messages (${e.javaClass.simpleName})")
        }
    }

    /**
     * Stores [chat]'s messages that are not stored yet and returns them.
     * Left out: lines of messages the user deleted here (OpenBubbles shows
     * them again until the chat is read there), and the line OpenBubbles
     * adds for a message sent from here, which takes that message's place.
     */
    internal fun record(chat: CapturedIMessageChat, live: Boolean, buttons: String = ""): List<MessageDto> = synchronized(captureLock) {
        val id = chat.chatGuid
        val existing = repo.conversation(id)
        val deleted = IMessagePrefs.deletedMessages(appContext)
        val clearedBefore = IMessagePrefs.clearedBefore(appContext, id)
        val fresh = chat.messages
            .filter { it.timestampMs > clearedBefore }
            .map { it to IMessageCapture.messageId(id, it) }
            .filter { it.second !in deleted }
        val known = repo.knownMessageIds(fresh.map { it.second })
        val recent = repo.messages(id, limit = ECHO_LOOKBACK)
        // Messages sent from here that OpenBubbles has not shown back yet; each stands for one line.
        val unmatched = recent.filter { it.isOutgoing && it.id.startsWith(SENT_PREFIX) }.toMutableList()
        val newestIncoming = recent.filter { !it.isOutgoing }.maxOfOrNull { it.timestampMs } ?: 0L
        val replaced = ArrayList<String>()
        val added = ArrayList<MessageDto>()
        for ((message, messageId) in fresh) {
            if (messageId in known) continue
            if (message.fromMe) {
                val sent = unmatched.firstOrNull { it.text == message.text && kotlin.math.abs(it.timestampMs - message.timestampMs) <= ECHO_WINDOW_MS }
                if (sent != null) {
                    unmatched.remove(sent)
                    replaced += sent.id
                    // OpenBubbles stamps it in whole seconds: about how long it took to show it back.
                    val secs = ((System.currentTimeMillis() - sent.timestampMs).coerceAtLeast(0) + 500) / 1000
                    IMessageLog.add(appContext, "${IMessageLog.chatRef(id)}: OpenBubbles showed a message sent from here back after ~$secs s (it went to Apple as an iMessage)")
                } else if (echoOfDeleted(id, message)) {
                    continue
                }
            }
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
                // Only what is newer than anything seen from them is announced.
                isOld = !live || message.fromMe || message.timestampMs <= newestIncoming,
            )
        }
        if (added.isNotEmpty()) {
            val fromThem = added.count { !it.fromMe }
            // Not counted: OpenBubbles' lines for messages sent from here (logged above).
            val fromMe = added.size - fromThem - replaced.size
            IMessageLog.add(
                appContext,
                "${IMessageLog.chatRef(id)}: ${if (live) "notification" else "notification already up"}" +
                    (if (chat.isGroup) " (group)" else "") +
                    ": $fromThem new from them, $fromMe from me (sent in OpenBubbles)" +
                    (if (buttons.isNotEmpty()) "; $buttons" else "") +
                    (if (existing == null) "; new chat" else ""),
            )
        }
        // A notification still up with someone's lines in it: unread in OpenBubbles (it takes it down once read).
        if (added.any { !it.fromMe } || (!live && chat.messages.any { !it.fromMe })) IMessageReplies.noteUnread(id)
        if (added.isEmpty() && existing != null && replaced.isEmpty()) return@synchronized added
        val onScreen = MessageNotifier.visibleConversationId(ChatService.IMESSAGE) == id
        val newIncoming = added.any { !it.fromMe && !it.isOld }
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
                participants = participantsFor(chat, added),
            ),
        )
        // A message sent from here gives way to OpenBubbles' line for it, whose id later posts repeat.
        for (sentId in replaced) repo.applyDeletion(sentId)
        for (dto in added) {
            repo.upsertMessage(dto)
            if (live) MessageNotifier.onIncoming(appContext, repo, dto)
        }
        added
    }

    /**
     * Who is in the chat: in a group everyone seen writing in it so far (a
     * notification names only who wrote lately); in a one-to-one chat the
     * other person, keeping the address known from before if this
     * notification carries none.
     */
    private fun participantsFor(chat: CapturedIMessageChat, added: List<MessageDto>): List<ParticipantDto> {
        val stored = runCatching { repo.participants(chat.chatGuid) }.getOrDefault(emptyList()).filter { !it.isMe }
        val me = ParticipantDto(id = ME, isMe = true)
        if (chat.isGroup) {
            val seen = stored.map { ParticipantDto(id = it.id, name = it.name, number = it.number, formattedNumber = it.formattedNumber) } +
                added.filter { !it.fromMe && it.senderName.isNotEmpty() }.map { ParticipantDto(id = it.senderId, name = it.senderName) }
            return listOf(me) + seen.reversed().distinctBy { it.id }.reversed()
        }
        val before = stored.firstOrNull()
        val address = chat.address.ifEmpty { before?.number.orEmpty() }
        return listOf(
            me,
            ParticipantDto(id = address.ifEmpty { before?.id ?: chat.chatGuid }, name = chat.title, number = address, formattedNumber = address),
        )
    }

    // ============================== sending ==============================

    /**
     * Whether a text to [conversationId] can be sent from here, without
     * opening OpenBubbles: it has a Reply button, and [text] (if given) is
     * not a repeat OpenBubbles would drop.
     */
    fun canReply(conversationId: String, text: String? = null): Boolean = IMessageReplies.canReply(conversationId, text?.trim())

    /**
     * Sends [text] through the chat's Reply button. It shows right away;
     * once OpenBubbles has it, it is marked sent (whether it reached the
     * other person is OpenBubbles' to say). If it cannot be handed over
     * ([canReply] is false), it is stored as failed and
     * [IMessageErrors.NO_REPLY_BUTTON] is thrown, so a reply typed in a
     * notification is not shown there as sent; the conversation screen
     * hands such a message to OpenBubbles instead.
     */
    override fun sendText(conversationId: String, text: String, replyToId: String?): String {
        requireEnabled()
        val body = text.trim()
        if (body.isEmpty()) throw RelayException(RelayException.INVALID_ARGUMENT, "empty message")
        val tmpId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        repo.insertLocalOutgoing(tmpId, conversationId, body, null, null, null, null, now)
        if (!deliver(tmpId, conversationId, body)) throw RelayException(IMessageErrors.NO_REPLY_BUTTON, "no reply button for this chat")
        return tmpId
    }

    /** False if it could not be handed over (it is then marked failed). */
    private fun deliver(tmpId: String, conversationId: String, text: String): Boolean =
        when (IMessageReplies.send(appContext, conversationId, text).also { result ->
            IMessageLog.add(
                appContext,
                "${IMessageLog.chatRef(conversationId)}: send through OpenBubbles' Reply button: " + when (result) {
                    IMessageReplies.Result.HANDED_OVER -> "handed over"
                    IMessageReplies.Result.NO_REPLY_BUTTON -> "NO Reply button held for this chat"
                    IMessageReplies.Result.WITHDRAWN -> "the button was withdrawn (OpenBubbles took the notification down)"
                    IMessageReplies.Result.REPEAT -> "same text as the last reply (OpenBubbles would drop it)"
                },
            )
        }) {
            IMessageReplies.Result.HANDED_OVER -> {
                repo.upsertMessage(
                    MessageDto(
                        id = SENT_PREFIX + tmpId,
                        conversationId = conversationId,
                        tmpId = tmpId,
                        fromMe = true,
                        // When it went: OpenBubbles' line for it is matched by it.
                        timestampMs = System.currentTimeMillis(),
                        direction = "out",
                        status = STATUS_SENT,
                        transport = TRANSPORT,
                        text = text,
                        isOld = true,
                    ),
                )
                true
            }
            IMessageReplies.Result.NO_REPLY_BUTTON, IMessageReplies.Result.WITHDRAWN, IMessageReplies.Result.REPEAT -> {
                repo.setLocalState(tmpId, LocalState.FAILED, IMessageErrors.NO_REPLY_BUTTON)
                false
            }
        }

    /** Pictures and files go through OpenBubbles' own screen ([handOff]). */
    override fun sendMedia(conversationId: String, uri: Uri, caption: String?, replyToId: String?): String =
        throw RelayException(IMessageErrors.HAND_OFF, "attachments are sent in OpenBubbles")

    override fun retry(tmpId: String) {
        val row = repo.message(MessageRow.tmpMessageId(tmpId)) ?: return
        if (row.localState != LocalState.FAILED || row.text.isEmpty()) return
        repo.setLocalState(tmpId, LocalState.SENDING)
        deliver(tmpId, row.conversationId, row.text)
    }

    override fun discardFailed(tmpId: String) {
        repo.applyDeletion(MessageRow.tmpMessageId(tmpId))
    }

    /**
     * Opens OpenBubbles with [text] and [attachments] (content uris this app
     * can hand over, with their types) filled in for [conversationId]: its
     * chat, or failing that a new chat with the person's address. The user
     * sends it there. False if OpenBubbles is not installed or could not be opened.
     */
    fun handOff(context: Context, conversationId: String, text: String, attachments: List<Pair<Uri, String>> = emptyList()): Boolean =
        handOffNow(context, conversationId, text, attachments).also { ok ->
            val what = (if (text.isNotBlank()) "text" else "") +
                (if (attachments.isNotEmpty()) (if (text.isNotBlank()) " + " else "") + "${attachments.size} file(s) " +
                    attachments.joinToString(",", "(", ")") { it.second.substringBefore('/') } else "")
            IMessageLog.add(
                appContext,
                "${IMessageLog.chatRef(conversationId)}: opened OpenBubbles with $what for the user to tap send" +
                    if (ok) "" else ": FAILED (OpenBubbles not installed or would not open)",
            )
        }

    private fun handOffNow(context: Context, conversationId: String, text: String, attachments: List<Pair<Uri, String>>): Boolean {
        val pkg = OpenBubbles.installedPackage(appContext) ?: return false
        if (OpenBubbles.start(context, OpenBubbles.shareToChatIntent(pkg, conversationId, text, attachments))) return true
        val address = addressOf(conversationId) ?: return false
        return OpenBubbles.start(context, OpenBubbles.newChatIntent(pkg, address, text))
    }

    /** Opens OpenBubbles on a new chat with [address] (a number or an email address) and [text] filled in. */
    fun startInOpenBubbles(context: Context, address: String, text: String = ""): Boolean {
        val pkg = OpenBubbles.installedPackage(appContext) ?: return false
        return OpenBubbles.start(context, OpenBubbles.newChatIntent(pkg, address, text))
    }

    /** The other person's number or email address in a stored one-to-one chat. */
    fun addressOf(conversationId: String): String? {
        val conversation = runCatching { repo.conversation(conversationId) }.getOrNull()
        if (conversation?.isGroup == true) return null
        return runCatching { repo.participants(conversationId) }.getOrDefault(emptyList())
            .firstOrNull { !it.isMe }?.number?.trim()?.takeIf { IMessageAddress.key(it) != null }
    }

    /** The stored iMessage conversation with [address], if one has been seen. */
    fun conversationWith(address: String): String? {
        val wanted = IMessageAddress.key(address) ?: return null
        return runCatching { repo.conversations(null) }.getOrDefault(emptyList())
            .filter { !it.isGroup && it.folderEnum != Folder.DELETED }
            .firstOrNull { row -> addressOf(row.id)?.let(IMessageAddress::key) == wanted }
            ?.id
    }

    // --- deleting here --------------------------------------------------------------------

    /** Messages sent from here and deleted before OpenBubbles showed them back: their lines are left out. */
    private val deletedSent = java.util.concurrent.ConcurrentLinkedQueue<Triple<String, String, Long>>()

    private fun echoOfDeleted(chatGuid: String, line: CapturedIMessage): Boolean {
        val match = deletedSent.firstOrNull { (chat, text, ts) ->
            chat == chatGuid && text == line.text && kotlin.math.abs(ts - line.timestampMs) <= ECHO_WINDOW_MS
        } ?: return false
        deletedSent.remove(match)
        return true
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
        val pressed = runCatching { IMessageReplies.markRead(appContext, conversationId) }.getOrDefault(false)
        if (!pressed) IMessageLog.add(appContext, "${IMessageLog.chatRef(conversationId)}: read here, but OpenBubbles offered no Mark As Read button (it stays unread there)")
    }

    override suspend fun loadOlder(conversationId: String, count: Int): Int = 0

    /** Removes it from this phone only; OpenBubbles keeps it. It is not taken in again from OpenBubbles' notification. */
    override suspend fun deleteMessage(messageId: String): Unit = withContext(Dispatchers.IO) {
        val row = repo.message(messageId)
        if (row != null && messageId.startsWith(SENT_PREFIX)) {
            deletedSent += Triple(row.conversationId, row.text, row.timestampMs)
            while (deletedSent.size > MAX_DELETED_SENT) deletedSent.poll()
        }
        if (!messageId.startsWith(MessageRow.TMP_PREFIX)) IMessagePrefs.addDeletedMessage(appContext, messageId)
        repo.applyDeletion(messageId)
    }

    /**
     * Removes the chat from this phone only; it comes back, from that
     * moment on, if someone writes in it again.
     */
    override suspend fun deleteConversation(conversationId: String): Unit = withContext(Dispatchers.IO) {
        val newest = runCatching { repo.messages(conversationId).maxOfOrNull { it.timestampMs } }.getOrNull()
        IMessagePrefs.setClearedBefore(appContext, conversationId, maxOf(newest ?: 0L, System.currentTimeMillis()))
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
        deletedSent.clear()
        IMessagePrefs.clearDeleted(appContext)
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
        /** How far apart a message sent from here and OpenBubbles' line for it may be (it sends when it can). */
        private const val ECHO_WINDOW_MS = 30 * 60 * 1000L
        private const val MAX_DELETED_SENT = 50
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
