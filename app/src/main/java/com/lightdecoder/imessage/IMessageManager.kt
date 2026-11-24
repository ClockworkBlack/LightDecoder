package com.lightdecoder.imessage

import android.content.Context
import android.net.Uri
import android.service.notification.StatusBarNotification
import android.telephony.TelephonyManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
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
 * With OpenBubbles' library registered ([com.lightdecoder.imessage.direct.DirectLink.active],
 * "direct mode") none of that applies: messages, pictures and files, tapbacks, edits, typing and
 * read receipts go straight to Apple through it, and the OpenBubbles app is never opened.
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

    /**
     * Through the iMessage library the user's iMessages can be edited ([editMessage]) and groups
     * started ([startConversation]); through OpenBubbles' notifications nothing more.
     */
    override val capabilities: Set<Capability> get() = if (direct.active) DIRECT_CAPABILITIES else emptySet()

    private val _state = MutableStateFlow(currentState())
    override val state: StateFlow<RelayState> = _state.asStateFlow()
    private val _typing = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    override val typing: StateFlow<Map<String, Set<String>>> = _typing.asStateFlow()

    /** Who is typing where, by their address too (direct mode): a group shows the first one's picture beside the dots. */
    private val _typists = MutableStateFlow<Map<String, List<Typist>>>(emptyMap())
    val typists: StateFlow<Map<String, List<Typist>>> = _typists.asStateFlow()

    /** Who is typing where (direct mode). */
    private val typers = TypingBoard()

    /** Forgets typists nothing more was heard from ([TypingBoard.EXPIRY_MS]). */
    private val typingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val presence: StateFlow<Map<String, Presence>> = MutableStateFlow(emptyMap())
    override val backfill: StateFlow<BackfillProgress> = MutableStateFlow(BackfillProgress(running = false, 0, 0, 0))

    private val captureLock = Any()

    /** The direct link (OpenBubbles' library, in the ":imessage" process), used instead of the app once registered. */
    private val direct get() = com.lightdecoder.imessage.direct.DirectLink.get(appContext)

    override fun isPaired(): Boolean = IMessagePrefs.enabled(appContext) || direct.active

    /**
     * Connected while Blue Bubbles is on, OpenBubbles is installed and
     * LightDecoder may read notifications; otherwise why not.
     */
    private fun currentState(): RelayState = when {
        // The library is registered: iMessage needs neither the OpenBubbles app nor its notifications.
        direct.active -> RelayState(RelayState.Phase.CONNECTED)
        !IMessagePrefs.enabled(appContext) -> RelayState(RelayState.Phase.UNPAIRED)
        !OpenBubbles.isInstalled(appContext) -> RelayState(
            RelayState.Phase.ERROR,
            if (OpenBubbles.impostor(appContext) != null) IMessageErrors.NOT_VERIFIED else IMessageErrors.NOT_INSTALLED,
        )
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

    /**
     * "OpenBubbles com.openbubbles.messaging 1.15.0; notification access yes": what Blue Bubbles
     * needs. Through the iMessage library, the library's state instead: registered once on this
     * phone, and what its process last said of it ("connected to Apple", or why not).
     */
    fun setupSummary(): String {
        if (direct.active) {
            val library = com.lightdecoder.imessage.direct.DirectLibrary
            val where = if (library.isBundled(appContext)) "included in this build" else "imported"
            // Apple's and the library's own words can name the registered number or Apple ID: not in the report.
            val status = com.lightdecoder.imessage.direct.DirectPrefs.lastStatus(appContext)
                ?.let { (text, at) -> "${Redact.text(text)} (${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(at))})" }
                ?: "none yet"
            return "iMessage library $where (${library.processAbi()}); registered on this phone; last status: $status"
        }
        val pkg = OpenBubbles.installedPackage(appContext)
        val version = pkg?.let { runCatching { appContext.packageManager.getPackageInfo(it, 0).versionName }.getOrNull() }
        val fake = if (pkg == null) OpenBubbles.impostor(appContext) else null
        return "OpenBubbles " + when {
            pkg != null -> "$pkg ${version.orEmpty()}".trimEnd()
            fake != null -> "$fake NOT USED (not signed as OpenBubbles from the Play Store)"
            else -> "not installed"
        } + "; notification access " + (if (MessengerNotificationListener.hasAccess(appContext)) "yes" else "NO")
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
        // With the library registered, every iMessage comes from it: OpenBubbles' notifications
        // (if the app is still installed and registered too) would only add each message twice.
        if (direct.active) return
        val notification = sbn.notification ?: return
        // Only the real OpenBubbles, of this user (its certificate is checked here, not another
        // profile's copy): any app can take its package name and post "iMessages".
        if (sbn.user != android.os.Process.myUserHandle() || !OpenBubbles.isTrusted(appContext, sbn.packageName)) {
            noteUnused(
                if (sbn.user != android.os.Process.myUserHandle()) "from another user or profile" else "the app posting it is not signed as OpenBubbles",
                "package ${sbn.packageName}", live,
            )
            return
        }
        val (chat, whyNot) = try {
            IMessageCapture.parseOrWhyNot(sbn.packageName, sbn.tag, notification, sbn.postTime)
        } catch (e: RuntimeException) {
            null to "could not be read (${e.javaClass.simpleName})"
        }
        if (chat == null) {
            if (whyNot == IMessageCapture.HIDDEN_REASON && IMessagePrefs.hiddenTextAt(appContext) == 0L) {
                IMessagePrefs.setHiddenTextAt(appContext, System.currentTimeMillis())
                com.lightdecoder.messenger.MessengerEvents.notifyChanged()
            }
            noteUnused(whyNot, IMessageCapture.shape(sbn.tag, sbn.id, notification), live)
            return
        }
        // Readable again: OpenBubbles' Hide Message Text is off.
        if (IMessagePrefs.hiddenTextAt(appContext) != 0L) IMessagePrefs.setHiddenTextAt(appContext, 0L)
        // Only buttons OpenBubbles made itself: pressing another app's would hand it the user's text.
        val from = sbn.packageName
        val reply = try { IMessageCapture.replyAction(notification) } catch (e: RuntimeException) { null }?.takeIf { OpenBubbles.madeBy(it.intent, from) }
        val markRead = try { IMessageCapture.markReadAction(notification) } catch (e: RuntimeException) { null }?.takeIf { OpenBubbles.madeBy(it.intent, from) }
        val open = notification.contentIntent?.takeIf { OpenBubbles.madeBy(it, from) }
        val hadReply = IMessageReplies.canReply(chat.chatGuid)
        // The buttons first: by the time the messages show, they can be answered.
        IMessageReplies.update(chat.chatGuid, reply, markRead, open, chat.messageGuid)
        val ref = IMessageLog.chatRef(chat.chatGuid)
        if (hadReply && reply == null) IMessageLog.add(appContext, "$ref: OpenBubbles' notification has no Reply button now (messages to it open OpenBubbles)")
        try {
            record(chat, live, if (reply != null) "Reply button yes" else "Reply button NO")
        } catch (e: RuntimeException) {
            // A full disk or a damaged file: these messages are not kept.
            IMessageLog.add(appContext, "$ref: could not store a notification's messages (${e.javaClass.simpleName})")
        }
    }

    /** Unused notifications already logged (reason + shape), so a repeated one is one line, not hundreds. */
    private val unusedLogged = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun noteUnused(why: String, shape: String, live: Boolean) {
        // Its group summary is posted with every message: expected, not worth a line.
        if (why == "group summary") return
        val key = "$why|$shape"
        val now = System.currentTimeMillis()
        val last = unusedLogged[key]
        if (last != null && now - last < UNUSED_LOG_EVERY_MS) return
        unusedLogged[key] = now
        IMessageLog.add(appContext, "OpenBubbles notification not used${if (live) "" else " (already up)"}: $why; $shape")
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
            // A picture or video OpenBubbles described in words: a picture slot, filled from its Auto-save copy.
            val kinds = IMessageCapture.attachmentKinds(message.text)
            added += MessageDto(
                id = messageId,
                conversationId = id,
                parts = kinds?.mapIndexed { i, mime ->
                    com.lightdecoder.relay.PartDto(partId = i.toString(), kind = "media", mime = mime, width = 4, height = 3)
                }.orEmpty(),
                senderId = if (message.fromMe) "" else message.senderHandle.ifEmpty { message.sender },
                senderName = message.sender,
                fromMe = message.fromMe,
                timestampMs = message.timestampMs,
                direction = if (message.fromMe) "out" else "in",
                status = if (message.fromMe) STATUS_SENT else "",
                transport = TRANSPORT,
                text = if (kinds != null) "" else message.text,
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
        if (added.any { it.parts.isNotEmpty() }) IMessageMedia.schedule(appContext)
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
    fun canReply(conversationId: String, text: String? = null): Boolean =
        direct.canSend(conversationId) || IMessageReplies.canReply(conversationId, text?.trim())

    /**
     * Sends [text] through the chat's Reply button. It shows right away;
     * once OpenBubbles has it, it is marked sent (whether it reached the
     * other person is OpenBubbles' to say). If it cannot be handed over
     * ([canReply] is false), it is stored as failed and
     * [IMessageErrors.NO_REPLY_BUTTON] is thrown, so a reply typed in a
     * notification is not shown there as sent; the conversation screen
     * hands such a message to OpenBubbles instead.
     */
    override fun sendText(conversationId: String, text: String, replyToId: String?): String = sendText(conversationId, text, replyToId, effect = null)

    /**
     * [sendText], sent with Apple's [effect] ("Sent with Slam"; null: none) and with [runs] (iOS 18's
     * bold, italic, ... and text effects, over [text]). Both go only through the iMessage library:
     * without it they are left out.
     */
    fun sendText(
        conversationId: String,
        text: String,
        replyToId: String?,
        effect: String?,
        runs: List<com.lightdecoder.imessage.direct.TextRun> = emptyList(),
        scheduledAt: Long? = null,
        mentions: List<com.lightdecoder.imessage.direct.Mention> = emptyList(),
    ): String {
        val (body, formatted) = com.lightdecoder.imessage.direct.TextRuns.trimmed(text, runs)
        if (body.isEmpty()) throw RelayException(RelayException.INVALID_ARGUMENT, "empty message")
        // Straight to Apple through the library: a real iMessage with its own GUID (Unsend works),
        // replies inline, and Apple's answer decides sent or failed. Mentions go only that way too.
        if (direct.canSend(conversationId)) {
            val named = com.lightdecoder.imessage.direct.Mentions.trimmed(text, mentions)
            return direct.sendText(conversationId, body, replyToId, effect, formatted, scheduledAt, named)
        }
        // Send Later is Apple's: only through the library.
        if (scheduledAt != null) throw RelayException(IMessageErrors.NO_REPLY_BUTTON, "Send Later goes through the iMessage library only")
        requireEnabled()
        val tmpId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        repo.insertLocalOutgoing(tmpId, conversationId, body, null, null, null, null, now)
        // Through the library there is no Reply button to fall back on: the chat cannot be reached.
        if (direct.active) {
            repo.setLocalState(tmpId, LocalState.FAILED, IMessageErrors.NO_REPLY_BUTTON)
            throw RelayException(IMessageErrors.NO_REPLY_BUTTON, "this chat cannot be reached through the iMessage library")
        }
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

    /**
     * A picture, video or other file: through the iMessage library straight to Apple, as an
     * iMessage of its own with [caption] ([com.lightdecoder.imessage.direct.DirectLink.sendMedia]).
     * Without it they go through OpenBubbles' own screen ([handOff]), so this throws
     * [IMessageErrors.HAND_OFF]; a chat the library cannot reach throws
     * [IMessageErrors.NO_REPLY_BUTTON].
     */
    override fun sendMedia(conversationId: String, uri: Uri, caption: String?, replyToId: String?): String =
        sendThroughLibrary(conversationId, uri, caption, replyToId, voice = false, effect = null)

    /** [sendMedia], sent with Apple's [effect] (null: none), the caption formatted with [runs]. */
    fun sendMedia(
        conversationId: String,
        uri: Uri,
        caption: String?,
        replyToId: String?,
        effect: String?,
        runs: List<com.lightdecoder.imessage.direct.TextRun> = emptyList(),
        scheduledAt: Long? = null,
    ): String =
        sendThroughLibrary(conversationId, uri, caption, replyToId, voice = false, effect = effect, runs = runs, scheduledAt = scheduledAt)

    /**
     * Several files picked together, sent as one iMessage (an iPhone shows them as one stack), as
     * [sendMedia] otherwise; only through the iMessage library.
     */
    fun sendMediaBatch(
        conversationId: String,
        uris: List<Uri>,
        caption: String?,
        replyToId: String?,
        effect: String?,
        runs: List<com.lightdecoder.imessage.direct.TextRun> = emptyList(),
        scheduledAt: Long? = null,
    ): String {
        if (uris.size == 1) return sendMedia(conversationId, uris[0], caption, replyToId, effect, runs, scheduledAt)
        if (direct.canSend(conversationId)) return direct.sendMediaBatch(conversationId, uris, caption, replyToId, effect, runs, scheduledAt)
        if (direct.active) throw RelayException(IMessageErrors.NO_REPLY_BUTTON, "this chat cannot be reached through the iMessage library")
        throw RelayException(IMessageErrors.HAND_OFF, "attachments are sent in OpenBubbles")
    }

    /** A recorded voice message, which an iPhone shows as one: as [sendMedia]. */
    fun sendVoice(conversationId: String, uri: Uri, replyToId: String?): String =
        sendThroughLibrary(conversationId, uri, null, replyToId, voice = true, effect = null)

    private fun sendThroughLibrary(
        conversationId: String,
        uri: Uri,
        caption: String?,
        replyToId: String?,
        voice: Boolean,
        effect: String?,
        runs: List<com.lightdecoder.imessage.direct.TextRun> = emptyList(),
        scheduledAt: Long? = null,
    ): String {
        if (direct.canSend(conversationId)) return direct.sendMedia(conversationId, uri, caption, replyToId, voice, effect, runs, scheduledAt)
        if (direct.active) throw RelayException(IMessageErrors.NO_REPLY_BUTTON, "this chat cannot be reached through the iMessage library")
        throw RelayException(IMessageErrors.HAND_OFF, "attachments are sent in OpenBubbles")
    }

    override fun retry(tmpId: String) {
        if (direct.retry(tmpId)) return
        // Through the library a chat it cannot reach has no Reply button to fall back on either.
        if (direct.active) return
        val row = repo.message(MessageRow.tmpMessageId(tmpId)) ?: return
        if (row.localState != LocalState.FAILED || row.text.isEmpty()) return
        // A file (sent through the library before it was turned off) has no Reply button to go
        // through: not its caption alone, as if it had gone. It stays failed, for OpenBubbles' screen.
        if (row.media.isNotEmpty()) return
        repo.setLocalState(tmpId, LocalState.SENDING)
        deliver(tmpId, row.conversationId, row.text)
    }

    /**
     * Deletes a local copy still waiting or sending (iMessage library only): stopped first, before it
     * reaches Apple ([com.lightdecoder.imessage.direct.DirectLink.stopBeforeSent]), then gone with its
     * files. False, and nothing deleted, when it can no longer be stopped: it arrives as sent.
     */
    suspend fun deleteSending(tmpId: String): Boolean {
        if (!direct.active || !direct.stopBeforeSent(tmpId)) return false
        drop(tmpId)
        return true
    }

    /** Drops a message that did not go, with the copy of its file kept for sending it (and its link preview's picture). */
    override fun discardFailed(tmpId: String) {
        // One still uploading is stopped first (it may already be with Apple: then it still arrives, as sent).
        if (direct.active) direct.stopSending(tmpId)
        drop(tmpId)
    }

    /** Local copy [tmpId] gone, with its files. */
    private fun drop(tmpId: String) {
        repo.applyDeletion(MessageRow.tmpMessageId(tmpId))
        repo.outboxFileFor(tmpId).delete()
        // A batch's other files ([com.lightdecoder.imessage.direct.DirectLink.sendMediaBatch]).
        for (i in 1..com.lightdecoder.imessage.direct.DirectLink.MAX_BATCH) repo.outboxFileFor("$tmpId-$i").delete()
        com.lightdecoder.imessage.direct.DirectLink.pictureFile(
            appContext, com.lightdecoder.imessage.direct.IMessageExtra.linkImageKey(MessageRow.tmpMessageId(tmpId)),
        ).delete()
    }

    /**
     * Opens OpenBubbles with [text] and [attachments] (content uris this app
     * can hand over, with their types) filled in for [conversationId]: its
     * chat, or failing that a new chat with the person's address. The user
     * sends it there. False if OpenBubbles is not installed or could not be opened, and always
     * through the iMessage library, which never hands anything to the OpenBubbles app.
     */
    fun handOff(context: Context, conversationId: String, text: String, attachments: List<Pair<Uri, String>> = emptyList()): Boolean {
        if (direct.active) {
            IMessageLog.add(appContext, "${IMessageLog.chatRef(conversationId)}: kept here (the iMessage library opens no other app)")
            return false
        }
        return handOffNow(context, conversationId, text, attachments).also { ok ->
            val what = (if (text.isNotBlank()) "text" else "") +
                (if (attachments.isNotEmpty()) (if (text.isNotBlank()) " + " else "") + "${attachments.size} file(s) " +
                    attachments.joinToString(",", "(", ")") { it.second.substringBefore('/') } else "")
            IMessageLog.add(
                appContext,
                "${IMessageLog.chatRef(conversationId)}: opened OpenBubbles with $what for the user to tap send" +
                    if (ok) "" else ": FAILED (OpenBubbles not installed or would not open)",
            )
        }
    }

    private fun handOffNow(context: Context, conversationId: String, text: String, attachments: List<Pair<Uri, String>>): Boolean {
        val pkg = OpenBubbles.installedPackage(appContext) ?: return false
        if (OpenBubbles.start(context, OpenBubbles.shareToChatIntent(pkg, conversationId, text, attachments))) return true
        val address = addressOf(conversationId) ?: return false
        return when (OpenBubbles.openNewChat(context, address, text)) {
            OpenBubbles.NewChat.CHAT_OPENED, OpenBubbles.NewChat.SHARE_OPENED -> true
            else -> false
        }
    }

    /**
     * Opens OpenBubbles on a new chat with [address] (a number or an email address) and [text]
     * filled in. Never through the iMessage library (that starts chats itself, [startConversation]).
     */
    fun startInOpenBubbles(context: Context, address: String, text: String = ""): OpenBubbles.NewChat =
        if (direct.active) OpenBubbles.NewChat.FAILED else OpenBubbles.openNewChat(context, address, text)

    /** The other person's number or email address in a stored one-to-one chat. */
    fun addressOf(conversationId: String): String? {
        val conversation = runCatching { repo.conversation(conversationId) }.getOrNull()
        if (conversation?.isGroup == true) return null
        return runCatching { repo.participants(conversationId) }.getOrDefault(emptyList())
            .firstOrNull { !it.isMe }?.number?.trim()?.takeIf { IMessageAddress.key(it) != null }
    }

    /**
     * The stored iMessage conversation with [address], if one has been seen: the whole number (or
     * email address) the same ([IMessageAddress.key]); else, only where one of the two is written
     * without its country code and none is known, the same national number ([IMessageAddress.sameKey]).
     */
    fun conversationWith(address: String): String? {
        val wanted = IMessageAddress.key(address) ?: return null
        val chats = runCatching { repo.conversations(null) }.getOrDefault(emptyList())
            .filter { !it.isGroup && it.folderEnum != Folder.DELETED }
            .map { row -> row.id to addressOf(row.id)?.let(IMessageAddress::key) }
        return (chats.firstOrNull { it.second == wanted } ?: chats.firstOrNull { IMessageAddress.sameKey(it.second, wanted) })?.first
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

    /** The user is typing in [conversationId]: through the iMessage library the chat is told (throttled there). */
    override fun typing(conversationId: String) {
        if (direct.active) direct.typing(conversationId)
    }

    /** The user emptied [conversationId]'s composer: through the iMessage library the chat is told the typing stopped. */
    fun stopTyping(conversationId: String) {
        if (direct.active) direct.stopTyping(conversationId)
    }

    /**
     * [participantId] ([name]) started or stopped typing in [conversationId], as the iMessage
     * library heard it; [typing] follows. A message from them also stops it, and someone nothing
     * more is heard from stops showing after a minute ([TypingBoard]).
     */
    fun noteTyping(conversationId: String, participantId: String, name: String, typing: Boolean) {
        synchronized(typers) {
            if (typers.note(conversationId, participantId, name, typing, System.currentTimeMillis())) showTypists()
        }
        if (typing) {
            typingScope.launch {
                delay(TypingBoard.EXPIRY_MS)
                synchronized(typers) {
                    if (typers.expire(System.currentTimeMillis())) showTypists()
                }
            }
        }
    }

    private fun showTypists() {
        _typists.value = typers.typists()
        _typing.value = typers.shown()
    }
    override fun watchPresence(conversationId: String) = Unit
    override fun setAppVisible(visible: Boolean) {
        if (visible) refreshState()
    }

    /**
     * A tapback on an iMessage, through the iMessage library ([com.lightdecoder.imessage.direct.DirectLink.react]):
     * one per person, so another emoji replaces the user's earlier one. Messages known only from
     * OpenBubbles' notifications (no GUID) cannot take one.
     */
    override suspend fun react(messageId: String, emoji: String, remove: Boolean): Unit = withContext(Dispatchers.IO) {
        if (!direct.active || !com.lightdecoder.imessage.direct.DirectArchive.isImessage(messageId)) {
            throw RelayException(WA_UNSUPPORTED, "no reactions through OpenBubbles' notifications")
        }
        direct.react(messageId, emoji, remove)
    }

    /**
     * Changes the text of one of the user's iMessages, through the iMessage library
     * ([com.lightdecoder.imessage.direct.DirectLink.edit]): Apple allows it for 15 minutes, 5 times.
     * Too late is a [RelayException] with [RelayException.INVALID_ARGUMENT]. [runs] format the new
     * text (bold, a text effect, ...).
     */
    suspend fun editMessage(
        messageId: String,
        text: String,
        runs: List<com.lightdecoder.imessage.direct.TextRun> = emptyList(),
    ): Unit = withContext(Dispatchers.IO) {
        if (!direct.active) throw RelayException(WA_UNSUPPORTED, "no editing through OpenBubbles' notifications")
        val (body, formatted) = com.lightdecoder.imessage.direct.TextRuns.trimmed(text, runs)
        if (body.isEmpty()) throw RelayException(RelayException.INVALID_ARGUMENT, "empty message")
        direct.edit(messageId, body, formatted)
    }

    /**
     * Marks it read here and, through OpenBubbles' Mark As Read button, in OpenBubbles (which then
     * tells the sender if it is set to). Through the iMessage library Apple is told instead
     * ([com.lightdecoder.imessage.direct.DirectLink.markRead]).
     */
    override suspend fun markRead(conversationId: String): Unit = withContext(Dispatchers.IO) {
        if (direct.active) return@withContext direct.markRead(conversationId)
        runCatching { repo.markConversationRead(conversationId) }
        MessageNotifier.cancel(appContext, conversationId, ChatService.IMESSAGE)
        val pressed = runCatching { IMessageReplies.markRead(appContext, conversationId) }.getOrDefault(false)
        if (!pressed) IMessageLog.add(appContext, "${IMessageLog.chatRef(conversationId)}: read here, but OpenBubbles offered no Mark As Read button (it stays unread there)")
    }

    /**
     * "Mark as Unread": unread here until it is read again; through the iMessage library the user's
     * other devices are told too ([com.lightdecoder.imessage.direct.DirectLink.markUnread]). Does not throw.
     */
    suspend fun markUnread(conversationId: String): Unit = withContext(Dispatchers.IO) {
        if (direct.active) return@withContext direct.markUnread(conversationId)
        runCatching { repo.markConversationUnread(conversationId) }
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
        // The pictures kept with it that are no attachment of it (its link's, the stickers on it) go with it.
        row?.let { runCatching { direct.forgetPictures(it) } }
        repo.applyDeletion(messageId)
    }

    /**
     * Removes the chat from this phone only; it comes back, from that
     * moment on, if someone writes in it again. What is kept of it outside
     * the archive goes too: the pictures kept with its messages that are no
     * attachments (links' previews, stickers), a group's photo and version,
     * its own "Send Read Receipts" ([com.lightdecoder.imessage.direct.DirectLink.forgetConversation]).
     * Report Junk and a Messages conversation deleted with its iMessage chats
     * come here too.
     */
    override suspend fun deleteConversation(conversationId: String): Unit = withContext(Dispatchers.IO) {
        val newest = runCatching { repo.messages(conversationId).maxOfOrNull { it.timestampMs } }.getOrNull()
        IMessagePrefs.setClearedBefore(appContext, conversationId, maxOf(newest ?: 0L, System.currentTimeMillis()))
        MessageNotifier.cancel(appContext, conversationId, ChatService.IMESSAGE)
        runCatching { direct.forgetConversation(conversationId) }
        repo.deleteConversationLocal(conversationId)
    }

    override suspend fun setArchived(conversationId: String, archived: Boolean): Unit = withContext(Dispatchers.IO) {
        repo.setConversationFolder(conversationId, if (archived) Folder.ARCHIVE else Folder.INBOX)
    }

    override suspend fun setMuted(conversationId: String, muted: Boolean): Unit = withContext(Dispatchers.IO) {
        repo.setConversationMuted(conversationId, muted)
    }

    override suspend fun refreshConversations(folder: Folder): Int = 0

    /**
     * An attachment's file: through the iMessage library downloaded from Apple when it is not here
     * yet ([com.lightdecoder.imessage.direct.DirectLink.mediaFile]); a thumbnail is the file
     * itself. Pictures OpenBubbles' notifications named come from its saved copies ([IMessageMedia]),
     * never from here.
     */
    override suspend fun mediaFile(messageId: String, partId: String, thumbnail: Boolean): File? = withContext(Dispatchers.IO) {
        if (!direct.active || !com.lightdecoder.imessage.direct.DirectArchive.isImessage(messageId)) return@withContext null
        direct.mediaFile(messageId, partId)
    }

    override suspend fun requestFullSize(messageId: String, partId: String): Unit =
        throw RelayException(WA_UNSUPPORTED, "not for iMessage")

    override suspend fun contacts(refresh: Boolean): ContactsResult = withContext(Dispatchers.IO) {
        ContactsResult(runCatching { repo.contacts() }.getOrDefault(emptyList()), fromPhone = false)
    }

    /**
     * New chats start in OpenBubbles. Through the iMessage library, a chat with one person is
     * the stored one or a new one once Apple says they have iMessage; several people start a
     * group named [groupName], once Apple says each of them has iMessage
     * ([com.lightdecoder.imessage.direct.DirectLink.startGroup]).
     */
    override suspend fun startConversation(numbers: List<String>, groupName: String?): String = withContext(Dispatchers.IO) {
        if (!direct.active) throw RelayException(IMessageErrors.HAND_OFF, "new chats start in OpenBubbles")
        val addresses = numbers.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { IMessageAddress.key(it) ?: it }
        when (addresses.size) {
            0 -> throw com.lightdecoder.imessage.direct.DirectEngine.ImessageError(appContext.getString(com.lightdecoder.R.string.imessage_group_nobody))
            1 -> direct.conversationFor(addresses[0])
                ?: throw com.lightdecoder.imessage.direct.DirectEngine.ImessageError(appContext.getString(com.lightdecoder.R.string.imessage_not_on_imessage, addresses[0]))
            else -> direct.startGroup(addresses, groupName)
        }
    }

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
        /** The same unused notification is logged again at most this often. */
        private const val UNUSED_LOG_EVERY_MS = 10 * 60 * 1000L
        private const val SENT_PREFIX = "ld:"
        private const val ME = "me"
        private const val ECHO_LOOKBACK = 40
        /** How far apart a message sent from here and OpenBubbles' line for it may be (it sends when it can). */
        private const val ECHO_WINDOW_MS = 30 * 60 * 1000L
        private const val MAX_DELETED_SENT = 50
        private const val WA_UNSUPPORTED = "unsupported"

        /** What the iMessage library adds. */
        private val DIRECT_CAPABILITIES = setOf(Capability.EDIT, Capability.NEW_GROUP)

        @Volatile
        private var instance: IMessageManager? = null

        fun get(context: Context): IMessageManager =
            instance ?: synchronized(this) {
                instance ?: IMessageManager(context, ArchiveRepository.get(context, ChatService.IMESSAGE)).also { instance = it }
            }

        internal fun installForTesting(context: Context, repo: ArchiveRepository): IMessageManager {
            require(repo.service == ChatService.IMESSAGE) { "the repository is ${repo.service}'s, not iMessage's" }
            IMessageLinks.invalidate()
            return synchronized(this) { IMessageManager(context, repo).also { instance = it } }
        }

        internal fun resetForTesting() = synchronized(this) {
            instance = null
            IMessageReplies.clear()
            IMessageLinks.invalidate()
        }
    }
}

/** The iMessage link's own error codes (it shares the rest with [RelayException]). */
object IMessageErrors {
    /** OpenBubbles is not installed. */
    const val NOT_INSTALLED = "openbubbles_not_installed"

    /** An app is installed under OpenBubbles' name, but it is not signed as OpenBubbles from the Play Store: not used. */
    const val NOT_VERIFIED = "openbubbles_not_verified"

    /** LightDecoder may not read notifications, so nothing arrives from OpenBubbles. */
    const val NO_ACCESS = "no_notification_access"

    /** This chat has no Reply button to send through: it has to be sent in OpenBubbles. */
    const val NO_REPLY_BUTTON = "no_reply_button"

    /** This is done in OpenBubbles' own screen (attachments, new chats); never through the iMessage library. */
    const val HAND_OFF = "hand_off"
}

/**
 * Comparing a phone number or email address from OpenBubbles or Apple with one stored for the
 * Messages tab. Phone numbers are compared whole, as international (E.164) numbers: "+55 11
 * 98765-4321" and "+55 21 98765-4321" are two people, as are "+1 917 555 1234" and "+91 91755
 * 51234", though their last ten digits are the same. A number written without its country code is
 * read in this phone's country ([region]), as a phone reads one dialled that way. Only when no
 * country is known is such a number compared by its national digits, with a number that has one
 * ([sameKey]); two numbers that both say their country are never compared that way.
 */
object IMessageAddress {

    /** Fewer digits than this is no phone number. */
    private const val MIN_DIGITS = 7

    /** What a [key] of a number written without its country code, none known to read it in, starts with. */
    const val NATIONAL = "#"

    @Volatile
    private var regionSource: () -> String? = { Locale.getDefault().country }

    /**
     * Numbers written without a country code are read in this phone's country from now on: its
     * SIM's, else its mobile network's, else its locale's (until this is called: the locale's).
     * Called once per process.
     */
    fun useRegionOf(context: Context) {
        val app = context.applicationContext
        val found by lazy { regionOf(app) }
        regionSource = { found }
    }

    private fun regionOf(context: Context): String? {
        val tm = runCatching { context.getSystemService(TelephonyManager::class.java) }.getOrNull()
        val sim = runCatching { tm?.simCountryIso }.getOrNull()
        val network = runCatching { tm?.networkCountryIso }.getOrNull()
        return listOf(sim, network, Locale.getDefault().country).firstOrNull { CallingCodes.of(it) != null }?.uppercase()
    }

    /** Tests: numbers without a country code are read in [region] (null: in none). */
    internal fun setRegionForTesting(region: String?) {
        regionSource = { region }
    }

    internal fun resetForTesting() {
        regionSource = { Locale.getDefault().country }
    }

    /** The country (ISO 3166 alpha-2) a number written without its country code is read in, or null when none is known. */
    fun region(): String? = runCatching { regionSource() }.getOrNull()?.takeIf { CallingCodes.of(it) != null }?.uppercase()

    /** The calling code of [region] ("1", "55"), or null when no country is known. */
    fun callingCode(): String? = CallingCodes.of(region())?.code

    /**
     * [address] as its whole international number ("+5511987654321") when it is a phone number
     * whose country is known: the one it is written with ("+55 …", or "00 55 …"), else [region]'s,
     * its trunk prefix (the national "0", or "1" in North America) left out. Null for an email
     * address, for something too short to be a number, and for a number written without its
     * country code when no country is known.
     */
    fun e164(address: String, region: String? = region()): String? {
        val text = address.trim().removePrefix("tel:")
        if ('@' in text) return null
        val digits = text.filter { it.isDigit() }
        if (digits.length < MIN_DIGITS) return null
        if (text.startsWith("+")) return "+$digits"
        val home = CallingCodes.of(region) ?: return null
        // The international prefix dialled in place of "+".
        if (digits.startsWith("00") && digits.length - 2 >= MIN_DIGITS) return "+" + digits.substring(2)
        if (home.code == "1" && digits.startsWith("011") && digits.length - 3 >= MIN_DIGITS) return "+" + digits.substring(3)
        val national = if (home.trunk.isNotEmpty() && digits.startsWith(home.trunk)) digits.substring(home.trunk.length) else digits
        return "+" + home.code + national
    }

    /**
     * A key that two spellings of the same address share: an email address in lower case; a
     * phone number as its whole international number ([e164]: "+15551234567"), or, written
     * without its country code when no country is known, as [NATIONAL] and its national digits
     * (compared with [sameKey]). Null for something that is neither.
     */
    fun key(address: String): String? {
        val text = address.trim().removePrefix("tel:").removePrefix("mailto:")
        if (text.contains('@')) return text.lowercase().takeIf { it.length > 2 }
        val digits = text.filter { it.isDigit() }
        if (digits.length < MIN_DIGITS) return null
        return e164(text) ?: (NATIONAL + digits.trimStart('0').ifEmpty { digits })
    }

    /**
     * Whether keys [a] and [b] ([key]) are the same address: equal; or, as a fallback only when one
     * of them is a number without its country code ([NATIONAL]) and the other has one, when the
     * other is that national number after a country's calling code.
     */
    fun sameKey(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        if (a == b) return true
        val (national, full) = when {
            a.startsWith(NATIONAL) && b.startsWith("+") -> a to b
            b.startsWith(NATIONAL) && a.startsWith("+") -> b to a
            else -> return false
        }
        val n = national.removePrefix(NATIONAL)
        val f = full.removePrefix("+")
        if (f == n) return true
        return f.length > n.length && f.endsWith(n) && f.dropLast(n.length) in CallingCodes.codes
    }

    /** Whether [a] and [b] are the same phone number or email address ([sameKey]). */
    fun same(a: String, b: String): Boolean = sameKey(key(a), key(b))
}
