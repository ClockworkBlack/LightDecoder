package com.lightdecoder.imessage.direct

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MediaCache
import com.lightdecoder.archive.MediaRow
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
import com.lightdecoder.media.CafAudio
import com.lightdecoder.reactions.ReadTimes
import com.lightdecoder.ui.chat.EditRule
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * [IMessageManager] hands sends (attachments and effects too), retries, unsends, edits, tapbacks,
 * typing, read receipts, downloads of attachments and new groups here while [active]; [DirectSinkService] hands over what
 * Apple sent ([apply]). What arrives for a message that is not stored yet (a receipt for a message
 * whose send has not returned yet) is held for a while ([HeldUpdates]) and applied once it is.
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

    /** Each group's own name, as Apple has it ([GroupNames]). */
    private val groupNames = GroupNames(app)

    /** Each group's version, as Apple's events said it ([GroupVersions]): what a change from here goes as. */
    private val groupVersions = GroupVersions(app)

    /** Each chat's own "Send Read Receipts" ([ReadReceiptChoices]). */
    private val receiptChoices = ReadReceiptChoices(app)

    init {
        if (active) scope.launch { failInterruptedSends() }
        scope.launch { for (notice in typingOut) tell(notice) }
        // New group photos left by work that never ended (the app stopped in the middle of it).
        scope.launch { runCatching { GroupPhotos.sweep(app) } }
    }

    // --- where a message goes ------------------------------------------------------------------

    /** The library's addresses for a stored chat's members, the user's own left to the library. */
    fun targetOf(conversationId: String): DirectWire.Target? {
        val row = repo.conversation(conversationId) ?: return null
        // A group the user is no longer in ([isLeft]): nothing goes to it, as on an iPhone.
        if (row.readOnly && row.isGroup) return null
        val others = repo.participants(conversationId).filter { !it.isMe }
            .mapNotNull { Handles.normalize(it.number.ifBlank { it.id }) }
            .distinct()
        if (others.isEmpty()) return null
        val group = row.isGroup || others.size > 1
        // A group needs its iMessage group id: only chats the library itself made have one.
        val guid = DirectArchive.groupGuid(conversationId)
        if (group && guid == null) return null
        val after = repo.latestMessageId(conversationId, DirectArchive.PREFIX)?.let(DirectArchive::guidOf)
        // The group's own name, never the members' names it is shown by here: the iPhones in it
        // would take those for its name.
        val name = if (group) ownName(conversationId).ifBlank { null } else null
        return DirectWire.Target(others, name, guid, row.defaultOutgoingId, after)
    }

    /** Whether [conversationId] is an iMessage group the user was removed from or left (on another device). */
    fun isLeft(conversationId: String): Boolean =
        DirectArchive.isGroup(conversationId) && runCatching { repo.conversation(conversationId) }.getOrNull()?.let { it.isGroup && it.readOnly } == true

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

    /**
     * Starts an iMessage group with the people at [addresses] (numbers or email addresses), named
     * [groupName] (none: here it is named after its members, and on their iPhones too). Apple is
     * asked first whether each of them is on iMessage; if any is not, nothing is made and the
     * error names them. The group is kept as `imsg:group:<a new GUID>`, the GUID its first message
     * carries, so the iPhones in it keep it together. A group here with the same people (and the
     * same name, when one is given) is that one; the same person twice is a chat with them
     * ([conversationFor]). Throws [DirectEngine.ImessageError] saying why it cannot be started.
     */
    suspend fun startGroup(addresses: List<String>, groupName: String?): String {
        if (!active) throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_unreachable))
        val notOnIMessage = ArrayList<String>()
        // The library's address of each person, by the key that two spellings of it share.
        val people = LinkedHashMap<String, String>()
        for (address in addresses.map { it.trim() }.filter { it.isNotEmpty() }) {
            val handle = Handles.normalize(address)
            val key = handle?.let(IMessageAddress::key)
            if (handle == null || key == null) notOnIMessage += address else people.putIfAbsent(key, handle)
        }
        if (notOnIMessage.isEmpty() && people.size <= 1) {
            val only = people.values.singleOrNull() ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_group_nobody))
            return conversationFor(only) ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_not_on_imessage, Handles.display(only)))
        }
        var from = ""
        for (handle in people.values) {
            val reply = requests.call(DirectWire.REACH, DirectWire.reach(handle), REACH_TIMEOUT_MS)
            // The library could not be asked: that says nothing about whether they have iMessage.
            DirectWire.errorOf(reply)?.let { throw DirectEngine.ImessageError(it) }
            val reached = reply.optJSONObject("conversation")?.let(ConversationDto::fromJson)
            if (reached == null) notOnIMessage += nameOf(handle) else if (from.isEmpty()) from = reached.defaultOutgoingId
        }
        if (notOnIMessage.isNotEmpty()) {
            throw DirectEngine.ImessageError(
                app.resources.getQuantityString(R.plurals.imessage_group_not_on_imessage, notOnIMessage.size, notOnIMessage.joinToString(", ")),
            )
        }
        val name = groupName?.trim()?.takeIf { it.isNotEmpty() }
        sameGroup(people.keys, name)?.let { return it }
        val id = DirectArchive.groupConversationId(UUID.randomUUID().toString().uppercase())
        val members = people.values.map { h ->
            val address = Handles.display(h)
            ParticipantDto(id = address, name = nameOf(h), number = address, formattedNumber = address)
        }
        synchronized(this) {
            repo.upsertConversation(
                ConversationDto(
                    id = id,
                    name = name ?: members.joinToString(", ") { it.name },
                    isGroup = true,
                    type = IMessageManager.TRANSPORT,
                    folder = Folder.INBOX.wire,
                    defaultOutgoingId = from,
                    participants = listOf(ParticipantDto(id = ME, isMe = true)) + members,
                ),
            )
            groupNames.note(id, name.orEmpty(), System.currentTimeMillis())
        }
        IMessageLog.add(app, "${IMessageLog.chatRef(id)}: iMessage group of ${members.size + 1} started through the iMessage library")
        return id
    }

    /** A group here that the library can send to, with exactly the people [keys] ([IMessageAddress.key]) and, when given, [name]. */
    private fun sameGroup(keys: Set<String>, name: String?): String? =
        repo.conversations(null)
            .filter { it.isGroup && !it.readOnly && it.folderEnum != Folder.DELETED && DirectArchive.groupGuid(it.id) != null }
            .firstOrNull { row ->
                (name == null || row.name == name) &&
                    repo.participants(row.id).filter { !it.isMe }.mapNotNull { IMessageAddress.key(it.number.ifBlank { it.id }) }.toSet() == keys
            }?.id

    // --- sending ----------------------------------------------------------------------------------

    /**
     * Like every send in the app: a local copy now ("tmp:<tmpId>"), the sent message once Apple
     * confirms it. [effect] is the Apple effect it is sent with ("Sent with Slam"), if any; [runs]
     * the text's formatting and text effects (iOS 18), over [text], kept with both copies. With
     * [scheduledAt] it is Send Later ([SendLater]): Apple sends it then, and until then it shows
     * "Send Later · <time>", at that time.
     */
    fun sendText(
        conversationId: String,
        text: String,
        replyToId: String?,
        effect: String? = null,
        runs: List<TextRun> = emptyList(),
        scheduledAt: Long? = null,
        mentions: List<Mention> = emptyList(),
    ): String {
        // The message ends the typing indicator on their side: no "stopped typing" after it.
        if (scheduledAt == null) typingThrottle.sent(conversationId)
        val tmpId = UUID.randomUUID().toString()
        val formatted = TextRuns.normalize(text, runs)
        val named = Mentions.normalize(text, mentions)
        repo.insertLocalOutgoing(
            tmpId, conversationId, text, null, null, null, replyToId, scheduledAt ?: System.currentTimeMillis(),
            IMessageExtra(effect = effect, runs = runsOf(formatted), scheduledAt = scheduledAt ?: 0, mentions = mentionsOf(named)).toJson(),
        )
        inTurn(conversationId) {
            deliver(tmpId, conversationId, text, replyToId, effect = effect, runs = formatted, scheduledAt = scheduledAt, mentions = named)
        }
        return tmpId
    }

    /**
     * Sends the picture, video, voice message or other file at [uri], with [caption], as an
     * iMessage; [voice] when it is a recorded audio message (an iPhone shows it as one). As a
     * text: a local copy now ("tmp:<tmpId>"), the sent message once Apple confirms it. The file
     * is copied here first, so the send (and a retry, even after a restart) no longer needs [uri].
     * Throws [DirectEngine.ImessageError] when the file cannot be read or is over Apple's
     * [DirectMedia.MAX_SEND_BYTES]. [effect] is the Apple effect it is sent with, if any; [runs]
     * format the caption.
     */
    fun sendMedia(
        conversationId: String,
        uri: Uri,
        caption: String?,
        replyToId: String?,
        voice: Boolean = false,
        effect: String? = null,
        runs: List<TextRun> = emptyList(),
        scheduledAt: Long? = null,
    ): String {
        val tmpId = UUID.randomUUID().toString()
        val file = repo.outboxFileFor(tmpId)
        copyCapped(uri, file)
        val given = displayName(uri)
        val mime = mimeOf(uri, given)
        // Named as an iPhone names its own voice messages.
        val name = if (voice) "$VOICE_NAME.${DirectMedia.extensionFor(mime, given)}" else given.ifBlank { DirectMedia.nameFor(mime) }
        val (text, formatted) = TextRuns.trimmed(caption.orEmpty(), runs)
        if (scheduledAt == null) typingThrottle.sent(conversationId)
        val extra = IMessageExtra(voice = voice, effect = effect, runs = runsOf(formatted), scheduledAt = scheduledAt ?: 0).toJson()
        repo.insertLocalOutgoing(tmpId, conversationId, text, file.path, mime, name, replyToId, scheduledAt ?: System.currentTimeMillis(), extra)
        inTurn(conversationId) {
            deliver(tmpId, conversationId, text, replyToId, Outgoing(file, mime, name, voice), effect, runs = formatted, scheduledAt = scheduledAt)
        }
        return tmpId
    }

    /**
     * Sends an iMessage app's new card (a new game an extension app made, [com.lightdecoder.imessage.madrid.MadridHost]):
     * [app]'s balloon, with the picture [jpeg] if it has one. As a text: a local copy now
     * ("tmp:<tmpId>"), the sent card once Apple confirms it; the card is the root of its session.
     */
    fun sendAppCard(conversationId: String, app: IMessageExtra.App, jpeg: ByteArray?, text: String = app.copy(root = "").summary()): String {
        val tmpId = UUID.randomUUID().toString()
        val media = jpeg?.let { bytes ->
            val file = repo.outboxFileFor(tmpId)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            Outgoing(file, DirectEngine.APP_IMAGE_MIME, DirectEngine.APP_IMAGE_NAME, voice = false)
        }
        typingThrottle.sent(conversationId)
        val card = app.copy(root = "")
        repo.insertLocalOutgoing(
            tmpId, conversationId, text, media?.file?.path, media?.mime, media?.name, null,
            System.currentTimeMillis(), IMessageExtra(app = card).toJson(),
        )
        inTurn(conversationId) { deliver(tmpId, conversationId, text, null, media, appCard = card) }
        return tmpId
    }

    /** How an iMessage app's update went ([sendAppUpdate]). */
    data class AppUpdate(
        /** Apple confirmed it; it is stored. */
        val sent: Boolean,
        /** Why it did not go, for the screens; null when it went. */
        val error: String? = null,
        /** Apple's confirmation did not come in time: it may have gone all the same. */
        val unconfirmed: Boolean = false,
    )

    /**
     * Sends an iMessage app's update (a game's move) to its session, whose first card is
     * [rootMessageId]: [app]'s new state, with the new picture [jpeg] if there is one (else the
     * stored copy shows the session's previous picture). In turn with the chat's other sends. Once
     * Apple confirmed it, it is stored as a card of its own (as an iPhone shows a move).
     */
    suspend fun sendAppUpdate(conversationId: String, rootMessageId: String, app: IMessageExtra.App, jpeg: ByteArray?): AppUpdate {
        val rootGuid = DirectArchive.guidOf(rootMessageId)
            ?: return AppUpdate(sent = false, error = this.app.getString(R.string.imessage_direct_unreachable))
        val done = kotlinx.coroutines.CompletableDeferred<AppUpdate>()
        inTurn(conversationId) {
            try {
                done.complete(deliverAppUpdate(conversationId, rootGuid, app.copy(root = rootMessageId), jpeg))
            } finally {
                done.complete(AppUpdate(sent = false, error = this.app.getString(R.string.imessage_direct_unreachable)))
            }
        }
        return done.await()
    }

    /**
     * The newest stored iMessage of [conversationId] in iMessage app session [session] (a card or
     * a move; the one a tap opens), or null when there is none.
     */
    fun latestOfAppSession(conversationId: String, session: String): MessageRow? =
        appSessionRows(conversationId, session).firstOrNull()

    /** The stored iMessages of [conversationId] in iMessage app session [session], newest first. */
    private fun appSessionRows(conversationId: String, session: String, limit: Int = 20): List<MessageRow> {
        if (session.isEmpty()) return emptyList()
        return repo.messagesWithExtra(conversationId, session, limit = limit)
            .filter { IMessageExtra.parse(it.extra).app?.session == session }
    }

    /** The newest picture shown here for iMessage app session [session] (a file and its type), or null. */
    private fun previousAppImage(conversationId: String, session: String): Pair<File, String>? {
        for (row in appSessionRows(conversationId, session)) {
            val m = row.media.firstOrNull { it.mime.startsWith("image/") && existing(it.localPath) != null } ?: continue
            return File(m.localPath) to m.mime
        }
        return null
    }

    private suspend fun deliverAppUpdate(conversationId: String, rootGuid: String, balloon: IMessageExtra.App, jpeg: ByteArray?): AppUpdate {
        val file = jpeg?.let { bytes -> repo.outboxFileFor(UUID.randomUUID().toString()).also { it.parentFile?.mkdirs(); it.writeBytes(bytes) } }
        try {
            val target = targetOf(conversationId) ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_unreachable))
            val reply = requests.call(DirectWire.EXTENSION, DirectWire.extension(target, rootGuid, false, balloon, file?.path), MEDIA_SEND_TIMEOUT_MS)
            DirectWire.errorOf(reply)?.let {
                throw if (DirectWire.unconfirmed(reply)) DirectEngine.Unconfirmed(it) else DirectEngine.ImessageError(it)
            }
            val sent = DirectWire.SendReply.fromJson(reply)
            val messageId = DirectArchive.messageId(sent.guid)
            val mediaParts = sent.parts.map { p ->
                PartDto(
                    partId = p.partId, kind = "media", mediaId = DirectArchive.mediaId(sent.guid, p.idx),
                    mime = p.mime, fileName = p.name, size = p.size,
                )
            }
            val kept = file?.let { f -> mediaParts.firstOrNull()?.let { part -> part to keepSent(f, part) } }
            // No new picture: the stored copy shows the session's previous one (as an iPhone does),
            // a copy of its own, here only (Apple was sent none).
            val reused = if (file != null || mediaParts.isNotEmpty()) null else previousAppImage(conversationId, balloon.session)?.let { (old, mime) ->
                val part = PartDto(
                    partId = DirectArchive.partId(1), kind = "media", mediaId = DirectArchive.mediaId(sent.guid, 1),
                    mime = mime, fileName = old.name, size = old.length(),
                )
                val copy = repo.mediaFileFor(part.mediaId, DirectMedia.extensionFor(part.mime, part.fileName))
                try {
                    old.copyTo(copy, overwrite = true)
                    part to copy
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "previous picture not copied", e)
                    copy.delete()
                    null
                }
            }
            val text = balloon.summary()
            val stored = MessageDto(
                id = messageId,
                conversationId = conversationId,
                fromMe = true,
                timestampMs = sent.timestampMs.takeIf { it > 0 } ?: System.currentTimeMillis(),
                direction = "out",
                status = DirectArchive.STATUS_SENT,
                statusCode = 1,
                transport = IMessageManager.TRANSPORT,
                text = text,
                parts = listOfNotNull(text.takeIf { it.isNotEmpty() }?.let { PartDto(partId = "0", kind = "text", text = it) }) +
                    mediaParts + listOfNotNull(reused?.first),
                isOld = true,
                extra = IMessageExtra(app = balloon).toJson(),
            )
            synchronized(this) {
                repo.upsertMessage(stored, snippetOf(stored.text, stored.extra))
                kept?.let { (part, f) -> if (f != null) record(messageId, part.partId, part.mediaId, f, part.mime) }
                reused?.let { (part, f) -> if (record(messageId, part.partId, part.mediaId, f, part.mime) == null) f.delete() }
                replayHeld(messageId)
            }
            IMessageLog.add(app, "${IMessageLog.chatRef(conversationId)}: iMessage app update sent through the iMessage library (Apple confirmed)")
            return AppUpdate(sent = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "iMessage app update not sent", e)
            IMessageLog.add(app, "${IMessageLog.chatRef(conversationId)}: iMessage app update FAILED: ${DirectEngine.readable(e)}")
            return AppUpdate(sent = false, error = DirectEngine.readable(e), unconfirmed = e is DirectEngine.Unconfirmed)
        } finally {
            // Sent, it was moved to the message's media; else it is not needed any more.
            file?.delete()
        }
    }

    /** Copies [uri] into [target], at most [DirectMedia.MAX_SEND_BYTES]; throws why it could not. */
    private fun copyCapped(uri: Uri, target: File) {
        val tooBig = DirectEngine.ImessageError(app.getString(R.string.imessage_direct_attachment_too_big, (DirectMedia.MAX_SEND_BYTES shr 20).toInt()))
        val unreadable = DirectEngine.ImessageError(app.getString(R.string.imessage_direct_attachment_unreadable))
        // Too big as it is said to be: refused before anything is copied.
        if ((sizeOf(uri) ?: 0L) > DirectMedia.MAX_SEND_BYTES) throw tooBig
        var done = false
        try {
            val input = app.contentResolver.openInputStream(uri) ?: throw unreadable
            input.use { stream ->
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > DirectMedia.MAX_SEND_BYTES) throw tooBig
                        out.write(buffer, 0, n)
                    }
                    if (total == 0L) throw unreadable
                }
            }
            done = true
        } catch (e: java.io.IOException) {
            throw unreadable
        } catch (e: RuntimeException) {
            // No longer allowed to read it, or no app answers for it any more.
            throw unreadable
        } finally {
            if (!done) target.delete()
        }
    }

    /** The file's size as the app it came from says it, if it does. */
    private fun sizeOf(uri: Uri): Long? = try {
        if (uri.scheme == "file") {
            uri.path?.let { File(it).length() }
        } else {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }
    } catch (e: Exception) {
        null
    }

    /** The file's own name, as the app it came from gives it, else its path's last part. */
    private fun displayName(uri: Uri): String {
        val fromProvider = try {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
        return (fromProvider ?: uri.lastPathSegment).orEmpty().substringAfterLast('/').filterNot { it.isISOControl() }.take(120)
    }

    private fun mimeOf(uri: Uri, name: String): String {
        val fromProvider = try {
            app.contentResolver.getType(uri)
        } catch (e: Exception) {
            null
        }
        if (!fromProvider.isNullOrBlank() && DirectMedia.isSpecific(fromProvider)) return fromProvider
        return DirectMedia.mimeOfName(name)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: DirectMedia.OCTET_STREAM
    }

    /**
     * Sends a failed message again, from its local copy (which survives restarts), its file and
     * effect included. Only one still failed: a second tap (or a tap on a bubble shown before the
     * send went again) sends nothing, so it never goes twice. Retrying a message that was not
     * failed counts as handled (true), as the send it asks for is under way.
     */
    fun retry(tmpId: String): Boolean {
        val row = repo.message(MessageRow.tmpMessageId(tmpId)) ?: return false
        if (!canSend(row.conversationId)) return false
        if (row.localState != LocalState.FAILED) return true
        val extra = IMessageExtra.parse(row.extra)
        val media = row.media.firstOrNull()?.let { Outgoing(File(it.localPath), it.mime, it.fileName, extra.voice) }
        // From failed to waiting, once: of two retries at once only one goes.
        if (!repo.setLocalStateIf(tmpId, LocalState.FAILED, LocalState.PENDING)) return true
        // Send Later whose time has passed meanwhile: it goes now.
        val scheduledAt = extra.scheduledAt.takeIf { SendLater.check(it, System.currentTimeMillis()) == SendLater.Check.OK }
        inTurn(row.conversationId) {
            deliver(
                tmpId, row.conversationId, row.text, row.replyToId.ifEmpty { null }, media, extra.effect, extra.app, extra.textRuns(row.text), scheduledAt,
                extra.textMentions(row.text),
            )
        }
        return true
    }

    /** Sends waiting their turn, by conversation ([inTurn]). */
    private val sendQueues = HashMap<String, Channel<suspend () -> Unit>>()

    /**
     * Runs [send] once the sends asked of [conversationId] before it are done: one at a time, in the
     * order asked, so that they reach Apple (and the other person) in that order, a small picture
     * never ahead of the large video picked before it, and each names the one before it
     * ([DirectWire.Target.afterGuid]). Other conversations' sends do not wait for these.
     */
    private fun inTurn(conversationId: String, send: suspend () -> Unit) {
        synchronized(sendQueues) {
            sendQueues.getOrPut(conversationId) {
                Channel<suspend () -> Unit>(Channel.UNLIMITED).also { queue ->
                    scope.launch {
                        for (next in queue) {
                            try {
                                next()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // The next one still goes.
                                Log.w(TAG, "send not finished", e)
                            }
                        }
                    }
                }
            }.trySend(send)
        }
    }

    /** A file going with a message: the copy here, its type and name, and whether it is a voice message. */
    private class Outgoing(val file: File, val mime: String, val name: String, val voice: Boolean)

    private suspend fun deliver(
        tmpId: String,
        conversationId: String,
        text: String,
        replyToId: String?,
        media: Outgoing? = null,
        effect: String? = null,
        appCard: IMessageExtra.App? = null,
        runs: List<TextRun> = emptyList(),
        scheduledAt: Long? = null,
        mentions: List<Mention> = emptyList(),
    ) {
        try {
            repo.setLocalState(tmpId, LocalState.SENDING)
            val target = targetOf(conversationId) ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_unreachable))
            // A text with a link goes with the page's preview, as from an iPhone: fetched first (a few
            // seconds at most; without one it goes as it is), and shown on the copy here meanwhile.
            val preview = if (appCard == null && media == null) linkPreviewFor(tmpId, text) else null
            // An inline reply needs the GUID Apple knows the answered message by: messages the
            // library sent or received have it (imsg:<GUID>); older Blue Bubbles rows do not, and
            // a reply to one goes as a message of its own.
            val replyGuid = replyToId?.let(DirectArchive::guidOf)
            val replyPart = replyGuid?.let { DirectWire.replyPart(repo.message(replyToId)?.text.orEmpty()) }
            val request = DirectWire.send(
                target, if (appCard != null) "" else text, replyGuid, replyPart, effect,
                attachments = listOfNotNull(media?.let { DirectWire.Attachment(it.file.path, it.mime, it.name) }),
                voice = media?.voice == true,
                app = appCard,
                runs = if (appCard != null) emptyList() else runs,
                scheduledMs = scheduledAt,
                mentions = if (appCard != null) emptyList() else mentions,
                link = preview?.request,
            )
            // A file is uploaded first: that takes longer than a text.
            val reply = requests.call(DirectWire.SEND, request, if (media == null) SEND_TIMEOUT_MS else MEDIA_SEND_TIMEOUT_MS)
            DirectWire.errorOf(reply)?.let {
                throw if (DirectWire.unconfirmed(reply)) DirectEngine.Unconfirmed(it) else DirectEngine.ImessageError(it)
            }
            val sent = DirectWire.SendReply.fromJson(reply)
            val messageId = DirectArchive.messageId(sent.guid)
            val mediaParts = sent.parts.map { p ->
                PartDto(
                    partId = p.partId, kind = "media", mediaId = DirectArchive.mediaId(sent.guid, p.idx),
                    mime = p.mime, fileName = p.name, size = p.size,
                )
            }
            // The file sent is the message's from now on: where a download of it would be kept.
            val kept = media?.let { m -> mediaParts.firstOrNull()?.let { part -> part to keepSent(m.file, part) } }
            // The preview's picture is the sent message's, as one that came with it would be.
            val link = preview?.let { p -> p.link.copy(image = p.keepFor(sent.guid)) }
            val stored = MessageDto(
                id = messageId,
                conversationId = conversationId,
                tmpId = tmpId,
                fromMe = true,
                // Send Later: at the time Apple sends it, as on an iPhone.
                timestampMs = scheduledAt ?: sent.timestampMs.takeIf { it > 0 } ?: System.currentTimeMillis(),
                direction = "out",
                status = DirectArchive.STATUS_SENT,
                statusCode = 1,
                transport = IMessageManager.TRANSPORT,
                text = text,
                parts = listOfNotNull(text.takeIf { it.isNotEmpty() }?.let { PartDto(partId = "0", kind = "text", text = it) }) + mediaParts,
                replyToId = replyToId.orEmpty(),
                isOld = true,
                // An app's card is the first of its session.
                extra = IMessageExtra(
                    voice = media?.voice == true, effect = effect, app = appCard?.copy(root = messageId),
                    runs = if (appCard != null) emptyMap() else runsOf(runs),
                    scheduledAt = scheduledAt ?: 0,
                    mentions = if (appCard != null) emptyMap() else mentionsOf(mentions),
                    link = link,
                ).toJson(),
            )
            // A receipt (or a reaction) can come from Apple before the send's answer did: what
            // [apply] held for it is applied now, under the same lock, so none slips between.
            synchronized(this) {
                repo.upsertMessage(stored)
                kept?.let { (part, file) -> if (file != null) record(messageId, part.partId, part.mediaId, file, part.mime) }
                replayHeld(messageId)
            }
            // Apple numbered no attachment: the copy has nothing to belong to.
            if (media != null && kept == null) media.file.delete()
            IMessageLog.add(
                app,
                "${IMessageLog.chatRef(conversationId)}: sent through the iMessage library (Apple confirmed)" +
                    (if (media != null) " with ${if (media.voice) "a voice message" else media.mime.substringBefore('/')}" else "") +
                    (if (effect != null) ", with an effect" else "") +
                    (if (runs.isNotEmpty()) ", formatted" else "") +
                    (if (mentions.isNotEmpty()) ", with ${mentions.size} mention(s)" else "") +
                    (if (preview != null) ", with a link preview" + (if (link?.image.isNullOrEmpty()) "" else " and its picture") else "") +
                    (if (scheduledAt != null) ", Send Later" else "") +
                    (if (appCard != null) ", an iMessage app's card" else ""),
            )
        } catch (e: Exception) {
            Log.w(TAG, "direct send failed", e)
            IMessageLog.add(app, "${IMessageLog.chatRef(conversationId)}: library send FAILED: ${DirectEngine.readable(e)}")
            // Apple not answering in time leaves it open whether it went: the user is asked before
            // it is sent again (MessageStatus.outcomeUnknown), as for any send that may have gone.
            repo.setLocalState(tmpId, LocalState.FAILED, if (e is DirectEngine.Unconfirmed) Outbox.UNCONFIRMED else DirectEngine.readable(e))
        }
    }

    // --- link previews (sent) ----------------------------------------------------------------------

    /** How link previews reach the network ([LinkPreviews]); tests answer in its place. */
    @Volatile
    internal var linkFetcher: LinkPreviews.Fetcher = LinkPreviews.HttpFetcher()

    /** The rules a link preview's fetch follows; tests let their own server in. */
    @Volatile
    internal var linkPolicy: LinkPreviews.Policy = LinkPreviews.Policy()

    /**
     * A preview made for a message being sent: what goes to Apple ([request]), what its copies here
     * show ([link]), and its picture, kept under the local copy's key until the message is sent.
     */
    private inner class SentPreview(val request: DirectWire.LinkPreviewRequest, val link: IMessageExtra.Link, private val picture: File?) {
        /** The picture as the sent message [guid]'s from now on (moved to its key): that key, or "" with none. */
        fun keepFor(guid: String): String {
            val from = picture?.takeIf { it.isFile } ?: return ""
            val key = IMessageExtra.linkImageKey(guid)
            val to = pictureFile(key)
            return try {
                if (!from.renameTo(to)) {
                    from.copyTo(to, overwrite = true)
                    from.delete()
                }
                key
            } catch (e: java.io.IOException) {
                Log.w(TAG, "link preview picture not kept", e)
                from.delete()
                ""
            }
        }
    }

    /**
     * The preview of the first web address in [text], fetched now ([LinkPreviews.fetch], with the
     * iMessage screen's "Link previews" on), its picture made small ([PreviewImages.jpeg]) and kept
     * under local copy [tmpId]'s key, and shown on that copy at once; null when there is none (no
     * address, the setting off, nothing to show, not in time). Never logs the address, and never
     * throws: whatever goes wrong making it (an error too: a page that runs memory or a stack out),
     * the message goes without one.
     */
    private suspend fun linkPreviewFor(tmpId: String, text: String): SentPreview? = try {
        makeLinkPreview(tmpId, text)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.w(TAG, "link preview not made (${e::class.java.simpleName})")
        null
    }

    private suspend fun makeLinkPreview(tmpId: String, text: String): SentPreview? {
        if (!DirectPrefs.linkPreviews(app)) return null
        val url = LinkPreviews.firstUrl(text) ?: return null
        val fetched = try {
            withTimeoutOrNull(LinkPreviews.BUDGET_MS + LINK_PREVIEW_SLACK_MS) {
                kotlinx.coroutines.runInterruptible(Dispatchers.IO) { LinkPreviews.fetch(url, linkFetcher, linkPolicy) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "link preview not made (${e::class.java.simpleName})")
            null
        }
        if (fetched == null) {
            IMessageLog.add(app, "link preview: none made (the page had none, could not be fetched, or took too long); sent without one")
            return null
        }
        val key = IMessageExtra.linkImageKey(MessageRow.tmpMessageId(tmpId))
        val picture = fetched.image?.let { PreviewImages.jpeg(it, PreviewImages.LINK_SIDE) }?.let { jpeg ->
            val file = pictureFile(key)
            try {
                MediaCache.writeAtomically(file, jpeg)
                file
            } catch (e: java.io.IOException) {
                Log.w(TAG, "link preview picture not written", e)
                null
            }
        }
        val page = fetched.page
        val request = DirectWire.LinkPreviewRequest(
            url = page.url, originalUrl = fetched.originalUrl, title = page.title, summary = page.summary,
            imageUrl = page.imageUrl.takeIf { picture != null }, imagePath = picture?.path, imageMime = if (picture != null) DirectEngine.APP_IMAGE_MIME else "",
        )
        val link = IMessageExtra.Link(fetched.originalUrl, page.title, page.summary, LinkPreviews.hostOf(fetched.originalUrl), if (picture != null) key else "")
        // The copy here shows it while the message goes.
        try {
            val row = repo.message(MessageRow.tmpMessageId(tmpId))
            if (row != null) repo.editText(row.id, row.text, 0, IMessageExtra.parse(row.extra).copy(link = link).toJson())
        } catch (e: RuntimeException) {
            Log.w(TAG, "link preview not shown on the local copy", e)
        }
        IMessageLog.add(app, "link preview: made" + if (picture != null) ", with its picture" else ", without a picture")
        return SentPreview(request, link, picture)
    }

    /** The file a picture kept under [key] is in (a link's preview, a sticker), in the iMessage media folder. */
    private fun pictureFile(key: String): File = repo.mediaFileFor(key, IMessageExtra.extensionOf(key))

    /** The picture the ":imessage" process wrote aside under [key] becomes the one kept under it (the change it came with was taken). */
    private fun claimPicture(key: String) {
        if (key.isEmpty()) return
        val file = pictureFile(key)
        val pending = DirectMedia.pendingInlineFile(file)
        if (pending.isFile && !pending.renameTo(file)) pending.delete()
    }

    /** The picture written aside under [key] goes: the change it came with was not taken. */
    private fun dropPicture(key: String) {
        if (key.isNotEmpty()) DirectMedia.pendingInlineFile(pictureFile(key)).delete()
    }

    /** The picture kept under [key] goes, and one written aside for it. */
    private fun deletePicture(key: String) {
        if (key.isEmpty()) return
        val file = pictureFile(key)
        file.delete()
        DirectMedia.pendingInlineFile(file).delete()
    }

    /**
     * The pictures kept with [conversationId]'s messages that are no attachments of them (links'
     * previews, stickers put on them) go: the chat is being deleted. Its attachments' files go with
     * the archive's rows ([ArchiveRepository.deleteConversationLocal]); these are kept by key, which
     * no row of the archive names as a file.
     */
    fun forgetConversationPictures(conversationId: String) {
        val rows = repo.messagesHolding(
            conversationId,
            extraNeedles = listOf("\"image\"", "\"stickers\""),
            reactionNeedles = listOf(DirectArchive.STICKER_TAPBACK),
        )
        for (row in rows) forgetPictures(row)
    }

    /**
     * What is kept of [conversationId] outside the archive goes: the chat is being deleted here (by
     * the user, with Report Junk, or with the Messages conversation it is shown in). Its pictures
     * ([forgetConversationPictures]), a group's photo and version ([GroupPhotos], [GroupVersions]: should
     * it come back, it starts again from what Apple says then, not from a version someone set before),
     * and its own "Send Read Receipts". Each is tried whatever happens to the others.
     */
    fun forgetConversation(conversationId: String) {
        runCatching { forgetConversationPictures(conversationId) }
        runCatching { GroupPhotos(app).forget(conversationId) }
        runCatching { groupVersions.forget(conversationId) }
        runCatching { receiptChoices.set(conversationId, null) }
    }

    /** The pictures kept with [row] that are no attachment of it (its link's, the stickers on it) go: the message is gone. */
    internal fun forgetPictures(row: MessageRow) {
        val extra = IMessageExtra.parse(row.extra)
        extra.link?.image?.let(::deletePicture)
        val guid = DirectArchive.guidOf(row.id) ?: return
        extra.stickers.forEach { deletePicture(IMessageExtra.stickerKey(guid, it.id)) }
        row.reactions.filter { it.emoji == DirectArchive.STICKER_TAPBACK }.flatMap { it.participantIds }
            .forEach { deletePicture(IMessageExtra.tapbackStickerKey(guid, it)) }
    }

    // --- attachments -------------------------------------------------------------------------------

    /**
     * The file just sent ([file], the outbox copy) becomes the sent message's part [part]: moved to
     * where a download of it would be kept, so that it is found there however the message is
     * shown. Null when it could not be moved (the message then shows without it).
     */
    private fun keepSent(file: File, part: PartDto): File? {
        val target = repo.mediaFileFor(part.mediaId, DirectMedia.extensionFor(part.mime, part.fileName))
        return try {
            if (!file.renameTo(target)) {
                file.copyTo(target, overwrite = true)
                file.delete()
            }
            target
        } catch (e: java.io.IOException) {
            Log.w(TAG, "sent file not kept", e)
            file.delete()
            null
        }
    }

    /** Downloads under way, by media id: a second ask for the same file waits for the first. */
    private val downloads = HashMap<String, Deferred<File?>>()

    /**
     * The file of an iMessage attachment: the one already here (one sent from here, or downloaded
     * before, or a small one that came inside the message), else downloaded from Apple now (once,
     * however many ask). An iPhone's voice message (CAF) is made playable here ([CafAudio]); the
     * original is kept with it. Null when the message or part is gone, or there is nothing to
     * download it from; throws [DirectEngine.ImessageError] when the download fails.
     */
    suspend fun mediaFile(messageId: String, partId: String): File? {
        val part = repo.mediaPart(messageId, partId) ?: return null
        existing(part.localPath)?.let { return it }
        if (part.mediaId.isEmpty()) return null
        val job = synchronized(downloads) {
            downloads[part.mediaId]?.takeIf { it.isActive } ?: scope.async { fetch(messageId, part) }.also { downloads[part.mediaId] = it }
        }
        try {
            return job.await()
        } finally {
            synchronized(downloads) { if (downloads[part.mediaId] === job && job.isCompleted) downloads.remove(part.mediaId) }
        }
    }

    private suspend fun fetch(messageId: String, part: MediaRow): File? {
        val file = repo.mediaFileFor(part.mediaId, DirectMedia.extensionFor(part.mime, part.fileName))
        if (file.length() <= 0) {
            // Nothing to download it by: a file that came inside the message, whose copy is gone.
            val descriptor = String(part.key, Charsets.UTF_8).ifEmpty { return null }
            val reply = requests.call(DirectWire.DOWNLOAD, DirectWire.download(descriptor, file.path), DOWNLOAD_TIMEOUT_MS)
            DirectWire.errorOf(reply)?.let { throw DirectEngine.ImessageError(it) }
            if (file.length() <= 0) return null
        }
        val playable = if (isCaf(part.mime, part.fileName)) playable(part, file) else null
        // Not while Apple's copy of the message is stored again ([apply]): that keeps what is recorded here.
        return synchronized(this) {
            if (playable == null) return@synchronized record(messageId, part.partId, part.mediaId, file, part.mime)
            // Both files at once, so no cleanup comes between them.
            repo.withMediaFileLock {
                val shown = record(messageId, part.partId, part.mediaId, playable.first, playable.second)
                if (shown != null) {
                    // The original, as it came, stays with it (and goes with it).
                    repo.setMediaLocalPath(messageId, part.partId, file.path, thumbnail = true)
                } else {
                    // The message or part is gone (record dropped the playable file): nothing shows the original either.
                    file.delete()
                }
                shown
            }
        }
    }

    /**
     * An iPhone's voice message in a file Android plays, with its type; null when its sound is in a
     * codec that cannot be, or the file cannot be remuxed here (damaged, or made to need more memory
     * than there is): it is then kept as it came, and the screen says it cannot be played.
     */
    private fun playable(part: MediaRow, caf: File): Pair<File, String>? {
        if (caf.length() > MAX_REMUX_BYTES) return null
        val sound = try {
            CafAudio.remux(caf.readBytes())
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "voice message too large to remux here: kept as it came")
            null
        } catch (e: java.io.IOException) {
            Log.w(TAG, "voice message could not be read for remuxing", e)
            null
        } ?: return null.also { Log.i(TAG, "voice message kept as it came: its codec cannot be played here") }
        val file = repo.mediaFileFor(part.mediaId + PLAYABLE, sound.extension)
        return try {
            MediaCache.writeAtomically(file, sound.bytes)
            file to sound.mime
        } catch (e: java.io.IOException) {
            Log.w(TAG, "playable voice message not written", e)
            null
        }
    }

    /**
     * [file] is part [partId]'s file from now on (if the part still has media [mediaId]), of type
     * [mime], with its size, and a picture's or video's width and height. The file, or null when
     * the part is gone.
     */
    private fun record(messageId: String, partId: String, mediaId: String, file: File, mime: String): File? = repo.withMediaFileLock {
        if (!repo.setMediaLocalPath(messageId, partId, mediaId, file.path, thumbnail = false)) return@withMediaFileLock null
        val (width, height) = sizeOf(file, mime)
        repo.setMediaDetails(messageId, partId, mime, file.length(), width, height)
        if (mime.startsWith("video/")) keepStill(messageId, partId, mediaId, file)
        file
    }

    /**
     * A video's bubble shows its first frame, as on an iPhone: taken from [video] (part
     * [partId]'s file, of media [mediaId]) and kept as the part's thumbnail, unless it has one.
     */
    private fun keepStill(messageId: String, partId: String, mediaId: String, video: File) {
        val part = repo.mediaPart(messageId, partId) ?: return
        if (part.thumbPath.isNotEmpty() && File(part.thumbPath).length() > 0) return
        val bytes = DirectFilePackager.videoStill(video.path) ?: return
        val still = repo.mediaFileFor(mediaId + STILL, "jpg")
        try {
            MediaCache.writeAtomically(still, bytes)
        } catch (e: java.io.IOException) {
            Log.w(TAG, "video still not written", e)
            return
        }
        repo.setMediaLocalPath(messageId, partId, still.path, thumbnail = true)
    }

    /**
     * The files the ":imessage" process wrote for [message]'s small attachments, which come inside
     * the message ([DirectEngine]), become its parts' files at once, once it is taken: written aside
     * ([DirectMedia.pendingInlineFile]), each is moved into place only now, so a message that is not
     * taken never overwrites another's. They then go with the message (unsent, deleted) even if
     * never shown. One whose part is not stored (that part unsent since) is deleted. An iPhone's
     * voice message is made playable only when it is played: its original is kept as the part's
     * second file meanwhile.
     */
    private fun claimInlineFiles(message: MessageDto) {
        for (part in message.mediaParts) {
            if (part.mediaId.isEmpty()) continue
            val file = repo.mediaFileFor(part.mediaId, DirectMedia.extensionFor(part.mime, part.fileName))
            // Written aside by the ":imessage" process until the message was taken: its file from now on.
            val pending = DirectMedia.pendingInlineFile(file)
            if (pending.isFile && !pending.renameTo(file)) pending.delete()
            if (file.length() <= 0) continue
            try {
                val stored = repo.mediaPart(message.id, part.partId)?.takeIf { it.mediaId == part.mediaId }
                if (stored != null && (stored.localPath == file.path || stored.thumbPath == file.path)) continue
                if (stored != null && isCaf(part.mime, part.fileName)) {
                    if (stored.localPath.isEmpty()) repo.setMediaLocalPath(message.id, part.partId, file.path, thumbnail = true)
                    continue
                }
                // Not stored: setting its path deletes it (ArchiveRepository.setMediaLocalPath).
                record(message.id, part.partId, part.mediaId, file, part.mime)
            } catch (e: RuntimeException) {
                Log.w(TAG, "inline attachment not recorded", e)
            }
        }
    }

    /**
     * The files the ":imessage" process wrote aside for [message]'s small attachments
     * ([DirectMedia.pendingInlineFile]), when the message is not taken: they go, and never become
     * the files of another message with the same media ids.
     */
    private fun dropInlineFiles(message: MessageDto) {
        for (part in message.mediaParts) {
            if (part.mediaId.isEmpty()) continue
            DirectMedia.pendingInlineFile(repo.mediaFileFor(part.mediaId, DirectMedia.extensionFor(part.mime, part.fileName))).delete()
        }
        // Its link preview's picture, written aside the same way.
        IMessageExtra.parse(message.extra).link?.image?.let(::dropPicture)
    }

    private fun isCaf(mime: String, fileName: String): Boolean = mime == CafAudio.MIME || fileName.endsWith(".caf", ignoreCase = true)

    /** A picture's or video's width and height as it is shown; 0 and 0 for anything else or when it cannot be read. */
    private fun sizeOf(file: File, mime: String): Pair<Int, Int> = try {
        when {
            mime.startsWith("image/") -> DirectFilePackager.pictureSize(file.path)
            mime.startsWith("video/") -> DirectFilePackager.videoSize(file.path)
            else -> null
        } ?: (0 to 0)
    } catch (e: RuntimeException) {
        0 to 0
    }

    private fun existing(path: String): File? = if (path.isEmpty()) null else File(path).takeIf { it.isFile }

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
     * [DirectEngine.ImessageError] saying why when it does not go through. [runs] format the new
     * text (none: plain), as an iPhone sends an edit of formatted text.
     */
    suspend fun edit(messageId: String, text: String, runs: List<TextRun> = emptyList()) {
        val row = repo.message(messageId) ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_not_stored))
        val guid = DirectArchive.guidOf(messageId)
        if (guid == null || !editable(row)) throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_edit_not_editable))
        if (!inEditWindow(row, System.currentTimeMillis())) throw RelayException(RelayException.INVALID_ARGUMENT, "too late to edit")
        val target = targetOf(row.conversationId) ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_unreachable))
        // Once asked, Apple's answer is waited for and stored even if the screen that asked closes.
        val formatted = TextRuns.normalize(text, runs)
        // The members it mentioned whose names the edit left as they were still are.
        val mentioned = Mentions.afterEdit(row.text, text, IMessageExtra.parse(row.extra).textMentions(row.text))
        withContext(NonCancellable) {
            val reply = requests.call(DirectWire.EDIT, DirectWire.edit(target, guid, 0, text, formatted, mentioned), SEND_TIMEOUT_MS)
            DirectWire.errorOf(reply)?.let { throw DirectEngine.ImessageError(it) }
            synchronized(this@DirectLink) {
                // Read again: a tapback or a receipt may have come in while Apple was asked, or it
                // may have been unsent meanwhile (here or on another device), and then it stays so.
                val current = repo.message(messageId) ?: return@withContext
                val extra = IMessageExtra.parse(current.extra)
                if (current.direction == "system" || 0 in extra.unsent) return@withContext
                val next = extra.copy(
                    parts = extra.parts + (0 to text), edits = extra.edits + 1, runs = extra.withRuns(0, formatted),
                    mentions = extra.withMentions(0, mentioned),
                    // What it read until now, as an iPhone keeps it for "Edited".
                    history = extra.withEarlier(current.text, current.editedAtMs.takeIf { it > 0 } ?: current.timestampMs),
                )
                repo.editText(messageId, next.text(), System.currentTimeMillis(), next.toJson(), snippetOf(next.text(), current.extra))
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
        // A sticker shows as this, but is no tapback: taking back an emoji tapback of it would
        // change nothing on Apple's side while it went here.
        if (remove && emoji == DirectArchive.STICKER_TAPBACK) throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_sticker_stays))
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
     * on (the chat's own choice, [setSendsReadReceipts], else the iMessage screen's setting, read by
     * the library), for the other person of a one-to-one chat, who then sees "Read".
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
                val choice = runCatching { receiptChoices.of(conversationId) }.getOrNull()
                val error = DirectWire.errorOf(requests.call(DirectWire.READ, DirectWire.read(target, guid, choice), NOTICE_TIMEOUT_MS))
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
    private fun toArchive(
        c: ConversationDto,
        groupId: String? = null,
        groupName: String? = null,
        member: Boolean? = null,
        /** The members to store, when not [c]'s (a stored group's own: [upsert]). */
        members: List<ParticipantDto>? = null,
    ): ConversationDto {
        val group = c.isGroup || groupId != null
        val id = archiveIdOf(c, groupId)
        val existing = repo.conversation(id)
        var people = members ?: c.participants.filter { !it.isMe }.map { personOf(it.id) }
        if (!group && existing != null) {
            // A person's chat never gets someone else's number: the one stored stays.
            val stored = repo.participants(id).filter { !it.isMe }
            val storedKey = stored.singleOrNull()?.let { IMessageAddress.key(it.number.ifBlank { it.id }) }
            val newKey = people.singleOrNull()?.let { IMessageAddress.key(it.number.ifBlank { it.id }) }
            if (storedKey != null && newKey != null && storedKey != newKey && storedKey.startsWith("+") && newKey.startsWith("+")) {
                people = stored.map { ParticipantDto(id = it.id, name = it.name, number = it.number, formattedNumber = it.formattedNumber) }
            }
        }
        // A group's own name (commas and all), else its members' names.
        val name = if (group) (groupName ?: c.name).ifBlank { people.joinToString(", ") { it.name } } else ""
        return ConversationDto(
            id = id,
            name = name,
            isGroup = group,
            type = IMessageManager.TRANSPORT,
            folder = existing?.folder?.takeIf { it.isNotEmpty() && it != Folder.DELETED.wire } ?: Folder.INBOX.wire,
            unread = existing?.unread == true,
            pinned = existing?.pinned == true,
            // A group the user was removed from or left: read only, as on an iPhone, until they are
            // in it again ([DirectArchive.Update.Upsert.member]).
            readOnly = if (group && member != null) !member else existing?.readOnly == true,
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

    /**
     * The archive's id of a conversation the library describes: the group [groupId] when it is
     * stored as one; else, for one other person, their stored chat (a Blue Bubbles one too:
     * [IMessageManager.conversationWith], the whole number); else its own.
     */
    private fun archiveIdOf(c: ConversationDto, groupId: String?): String {
        if (groupId != null) return groupId
        val others = c.participants.filter { !it.isMe }
        val existingId = if (!c.isGroup && others.size == 1) manager.conversationWith(Handles.display(others[0].id)) else null
        return existingId ?: c.id
    }

    /** A member as the archive keeps them: the address without its scheme, and their contact name. */
    private fun personOf(handle: String): ParticipantDto {
        val address = Handles.display(handle)
        return ParticipantDto(id = address, name = nameOf(handle), number = address, formattedNumber = address)
    }

    /** Whether [address] (with or without its scheme) is [p]'s: the whole number, or the email address. */
    private fun isAddressOf(p: com.lightdecoder.archive.ParticipantRow, address: String): Boolean =
        address.isNotBlank() && (IMessageAddress.same(p.number.ifBlank { p.id }, address) || IMessageAddress.same(p.id, address))

    /** Whether [address] is one of [conversationId]'s members (besides the user), as stored. */
    private fun isMember(conversationId: String, address: String): Boolean =
        address.isNotBlank() && repo.participants(conversationId).any { !it.isMe && isAddressOf(it, address) }

    /** Stores one change from the ":imessage" process. Returns once it is in the archive. */
    fun apply(update: DirectArchive.Update) {
        synchronized(this) { store(update) }
    }

    private fun store(update: DirectArchive.Update) {
        when (update) {
            is DirectArchive.Update.Upsert -> upsert(update)
            is DirectArchive.Update.Remove -> remove(update)
            is DirectArchive.Update.Receipt -> receipt(update)
            is DirectArchive.Update.Edit -> edit(update)
            is DirectArchive.Update.Reaction -> reaction(update)
            is DirectArchive.Update.Sticker -> placedSticker(update)
            is DirectArchive.Update.PollVote -> pollVote(update)
            is DirectArchive.Update.PollChange -> pollChange(update)
            is DirectArchive.Update.Unsent -> unsent(update)
            is DirectArchive.Update.Typing -> {
                // A group down to one other person is still that group, as for its messages ([upsert]).
                val id = update.groupGuid?.let(DirectArchive::groupConversationId)?.takeIf { repo.conversation(it)?.isGroup == true }
                    ?: archiveConversationId(update.conversationId, update.participantId)
                // In a group, only one of its members shows as typing.
                if (repo.conversation(id)?.isGroup == true && !isMember(id, update.participantId)) return
                manager.noteTyping(id, update.participantId, nameOf(update.participantId), update.typing)
            }
            is DirectArchive.Update.Dropped ->
                IMessageLog.add(app, "an event from Apple was not taken (${loggableText(update.what, 40)}): ${loggableText(update.why, 160)}")
            is DirectArchive.Update.ReadElsewhere -> readElsewhere(update)
            is DirectArchive.Update.MarkUnread -> markedUnreadElsewhere(update)
            DirectArchive.Update.PeerCacheInvalidate -> devicesChanged()
            is DirectArchive.Update.Background -> background(update)
            is DirectArchive.Update.GroupPhoto -> groupPhoto(update)
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

    /**
     * A message, or a group's event, from Apple: stored in its chat. Who may write where:
     *
     * - A group stored here takes messages and events (renamed, a new photo or background, members
     *   changed) only from its members, or the user on another device; anyone else's are dropped.
     * - A stored group's members change only with a change of its members
     *   ([DirectArchive.Update.Upsert.membersChanged]) one of those made. What other messages say
     *   its members are is not taken as such, unlike OpenBubbles, which takes every message's list
     *   (adding and removing people) and says so in rows of the chat's own: here a message from a
     *   member may only add someone the group does not have yet (an addition missed), said in a
     *   row of its own ("Bob added Dave."), never take anyone out.
     * - A message whose GUID another message already has (in another chat, from someone else, or
     *   the other way round) is dropped, not moved or rewritten.
     *
     * Its small attachments that came inside it become its files only once it is taken
     * ([claimInlineFiles]); a dropped one's go ([dropInlineFiles]).
     */
    private fun upsert(update: DirectArchive.Update.Upsert) {
        // A group down to one other person is still that group, when it is stored as one.
        val groupId = update.groupGuid?.let(DirectArchive::groupConversationId)?.takeIf { repo.conversation(it)?.isGroup == true }
        if (update.groupOnly && groupId == null) return dropInlineFiles(update.message)
        val target = archiveIdOf(update.conversation, groupId)
        // A group's event (renamed, someone added) is shown, but is not a message to read.
        val system = update.message.direction == "system"
        val fromMe = update.message.fromMe
        val storedMembers = repo.conversation(target)?.takeIf { it.isGroup }?.let { repo.participants(target).filter { p -> !p.isMe } }
        if (storedMembers != null && !fromMe && storedMembers.none { isAddressOf(it, update.message.senderId) }) {
            IMessageLog.add(app, "${IMessageLog.chatRef(target)}: ${if (system) "a group event" else "a message"} from someone not in the group was dropped")
            return dropInlineFiles(update.message)
        }
        val existing = repo.message(update.message.id)
        // Unsent since: it stays unsent.
        if (existing != null && existing.direction == "system" && !system) return dropInlineFiles(update.message)
        if (existing != null && !sameMessage(existing, target, update.message)) {
            IMessageLog.add(app, "${IMessageLog.chatRef(target)}: a message whose id another message already has was dropped")
            return dropInlineFiles(update.message)
        }
        // A stored group keeps its members, but for someone a member's message names that it does not have.
        val added = if (storedMembers != null && !update.membersChanged && !system) {
            update.conversation.participants.filter { p -> !p.isMe && storedMembers.none { isAddressOf(it, p.id) } }.map { personOf(it.id) }
                .distinctBy { IMessageAddress.key(it.number) ?: it.number }
        } else {
            emptyList()
        }
        val members = if (storedMembers == null || update.membersChanged) null else {
            storedMembers.map { ParticipantDto(id = it.id, name = it.name, number = it.number, formattedNumber = it.formattedNumber) } + added
        }
        // Whether the user is in the group: a change of its members says; so does a message the
        // user wrote in it on another device.
        val member = update.member ?: true.takeIf { fromMe && !system }
        val conversation = toArchive(update.conversation, groupId, if (DirectArchive.isGroup(target)) groupNameAfter(target, update) else null, member, members)
        val onScreen = MessageNotifier.visibleConversationId(ChatService.IMESSAGE) == conversation.id
        val incoming = !fromMe && !system
        // The message moves the chat's latest message, time and snippet (see toArchive).
        // Only a message not stored before makes the chat unread: Apple handing one over again
        // (after a restart) leaves a chat the user read as read.
        repo.upsertConversation(conversation.copy(unread = (conversation.unread || (incoming && existing == null)) && !onScreen, lastMessageTs = 0))
        // The group's version a change of its members (or photo) carried: a change from here goes as the
        // next one. One far ahead of the group's is not taken (every change from here would go as one
        // after it, and the group's photo be held against any change); the user's own from here, one
        // after the highest known, always is.
        update.groupVersion?.let { v ->
            if (!DirectArchive.isGroup(conversation.id)) return@let
            val photo = GroupPhotos(app).of(conversation.id)
            if (GroupVersions.plausible(v, groupVersions.of(conversation.id), photo.version.takeIf { photo.known })) {
                groupVersions.note(conversation.id, v)
            } else {
                IMessageLog.add(app, "${IMessageLog.chatRef(conversation.id)}: a group version far ahead of the group's was not taken")
            }
        }
        if (added.isNotEmpty()) noteAdded(conversation.id, update.message, added)
        val fresh = update.message.copy(
            conversationId = conversation.id,
            senderId = if (update.message.fromMe) "" else Handles.display(update.message.senderId),
            senderName = if (update.message.fromMe) "" else nameOf(update.message.senderId),
            transport = IMessageManager.TRANSPORT,
        )
        val message = existing?.let { merged(fresh, it) } ?: fresh
        // Its link preview's picture is in place before the message is, so that its card shows with it.
        IMessageExtra.parse(update.message.extra).link?.image?.let(::claimPicture)
        val result = repo.upsertMessage(message, snippetOf(message.text, message.extra))
        claimInlineFiles(update.message)
        if (incoming) manager.noteTyping(conversation.id, message.senderId, "", typing = false)
        // What came for it first (read on another device, unsent, edited) applies before it is
        // announced, so a message already read or unsent makes no sound.
        replayHeld(message.id)
        if (result.inserted && incoming) notifyIfStillNew(message)
        // A move in an iMessage app's session (from them, or from the user's other device): the
        // extension app that has the session open hears of it.
        if (result.inserted && !system) {
            val balloon = IMessageExtra.parse(message.extra).app
            if (balloon != null && balloon.root.isNotEmpty() && balloon.root != message.id) appEvents?.appUpdateArrived(conversation.id, message)
        }
        if (result.inserted && !system) {
            IMessageLog.add(
                app,
                "${IMessageLog.chatRef(conversation.id)}: " +
                    (if (incoming) "received" else "sent from another device") + " through the iMessage library",
            )
        }
    }

    /**
     * Whether Apple's [fresh] copy of a message is [stored], handed over again: filed in the same
     * chat ([chatId]), the same way (the user's, or to them, or a row of the chat's own) and from
     * the same person.
     */
    private fun sameMessage(stored: MessageRow, chatId: String, fresh: MessageDto): Boolean {
        if (stored.conversationId != chatId) return false
        if (stored.direction != fresh.direction || stored.isOutgoing != fresh.fromMe) return false
        return fresh.fromMe || IMessageAddress.same(stored.senderId, fresh.senderId)
    }

    /**
     * A row of [chatId]'s own, right before [message], saying that its sender added [added] to the
     * group ("Bob added Dave."): people a member's message named that the group did not have.
     */
    private fun noteAdded(chatId: String, message: MessageDto, added: List<ParticipantDto>) {
        val texts = DirectTexts(app)
        val who = if (message.fromMe) texts.you else nameOf(message.senderId)
        val text = texts.added(who, added.joinToString(", ") { it.name.ifBlank { it.number } })
        repo.upsertMessage(
            MessageDto(
                id = message.id + ADDED,
                conversationId = chatId,
                senderId = if (message.fromMe) "" else Handles.display(message.senderId),
                senderName = if (message.fromMe) "" else nameOf(message.senderId),
                fromMe = message.fromMe,
                timestampMs = message.timestampMs - 1,
                direction = "system",
                transport = IMessageManager.TRANSPORT,
                text = text,
                parts = listOf(PartDto(partId = "0", kind = "text", text = text)),
                isOld = true,
            ),
        )
        IMessageLog.add(app, "${IMessageLog.chatRef(chatId)}: ${added.size} member(s) a member's message names added to the group")
    }

    /**
     * What group [id] is called on Apple's side after [update]: its name, when it carries one (or
     * is a rename) and is not older than the name known; else the name known. A message without a
     * name says nothing about it: only a rename takes a name off.
     */
    private fun groupNameAfter(id: String, update: DirectArchive.Update.Upsert): String {
        // Filed as a chat with one person, the group's name comes apart from it.
        val name = if (update.groupGuid != null) update.groupName else update.conversation.name
        if ((name.isNotBlank() || update.renamed) && groupNames.note(id, name, update.message.timestampMs)) return name
        return ownName(id)
    }

    /**
     * Group [id]'s own name on Apple's side ("" for none). For a group stored before these were
     * kept, its stored name unless that is its members' names (what an unnamed group is shown by).
     */
    private fun ownName(id: String): String {
        groupNames.of(id)?.let { return it }
        val row = repo.conversation(id) ?: return ""
        val members = repo.participants(id).filter { !it.isMe }.joinToString(", ") { it.name }
        val name = row.name.takeIf { it != members }.orEmpty()
        // As old as can be: whatever Apple says next wins.
        groupNames.note(id, name, 0)
        return name
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

    /** What stands for a text sent with invisible ink outside its bubble: "Sent with Invisible Ink". */
    private fun inkedLabel(): String = app.getString(R.string.imessage_sent_with, app.getString(R.string.imessage_effect_invisible_ink))

    /**
     * What the conversation list shows for a message whose text is [text] and stored extra
     * [extraJson]: null (its text) unless it was sent with invisible ink, which shows only in its bubble.
     */
    private fun snippetOf(text: String, extraJson: String): String? =
        IMessageExtra.shownText(text, extraJson, inkedLabel()).takeIf { it != text }

    /**
     * A notification for [message], just stored, unless it was unsent or its chat read since; with
     * its text as it now is. Invisible ink stays hidden there too: it says only how it was sent.
     */
    private fun notifyIfStillNew(message: MessageDto) {
        val row = repo.message(message.id) ?: return
        if (row.direction != "in") return
        if (repo.conversation(row.conversationId)?.unread != true) return
        // A game is open in an extension app, which asked for quiet.
        if (appEvents?.silences(row.conversationId) == true) return
        // Mentioned in a group: heard of even with the group's alerts hidden, as an iPhone's "Notify Me"
        // does. Only a group's: in a chat with one person a mention is no reason to be heard (anyone can
        // write one), and the chat's hidden alerts stay hidden.
        val group = repo.conversation(row.conversationId)?.isGroup == true
        val mentioned = group && IMessageExtra.parse(row.extra).mentionsMe && DirectPrefs.notifyWhenMentioned(app)
        MessageNotifier.onIncoming(app, repo, message.copy(text = IMessageExtra.shownText(row.text, row.extra, inkedLabel())), mentioned)
    }

    /**
     * A delivery or read receipt: taken only for the user's own messages (as
     * [ArchiveRepository.advanceMessageStatus] does), and only from someone in the message's chat.
     * One for a message not stored yet is held for it ([hold]) and checked once it is.
     */
    private fun receipt(update: DirectArchive.Update.Receipt) {
        val taken = ArrayList<String>()
        for (id in update.messageIds.distinct()) {
            val row = repo.message(id)
            if (row == null) {
                hold(id, update.copy(messageIds = listOf(id)))
            } else if (!isMember(row.conversationId, update.sender)) {
                refused("a receipt", row.conversationId, "from someone not in the chat")
            } else {
                taken += id
            }
        }
        if (taken.isEmpty()) return
        val advanced = HashSet<String>()
        repo.advanceMessageStatus(taken, update.status, DirectArchive.statusCode(update.status), DirectArchive::statusRank, advanced)
        // "Read 9:41" under the message: when it was seen to be read. Only a message this
        // receipt made read: one read before (the same receipt handed over again, or a
        // message read before read times were kept) keeps its time, or has none.
        if (update.status == DirectArchive.STATUS_READ) noteRead(advanced)
    }

    /** A message taken out ([DirectArchive.Update.Remove]); a Send Later one only while it is still waiting for its time. */
    private fun remove(update: DirectArchive.Update.Remove) {
        val row = repo.message(update.messageId)
        if (update.scheduledOnly) {
            if (row == null) return
            if (!SendLater.pending(row, System.currentTimeMillis())) {
                refused("a deletion of a Send Later message", row.conversationId, "for a message not waiting for its time")
                return
            }
        }
        row?.let(::forgetPictures)
        repo.applyDeletion(update.messageId)
    }

    /** One of Apple's changes not taken, said in the iMessage log ([what] it was, [why]; never the chat by more than its tag). */
    private fun refused(what: String, conversationId: String, why: String) {
        IMessageLog.add(app, "${IMessageLog.chatRef(conversationId)}: $what $why was dropped")
    }

    /**
     * Whether a change for [row] came in its chat: the chat Apple named for it ([conversationId] and
     * [groupGuid]; [DirectArchive.conversationId]'s) is [row]'s, as the archive files it. One that
     * names none (Apple names none with an edit or an unsend) is taken by who sent it alone.
     */
    private fun inChatOf(row: MessageRow, conversationId: String, groupGuid: String?): Boolean {
        if (conversationId.isEmpty() && groupGuid == null) return true
        groupGuid?.let(DirectArchive::groupConversationId)?.takeIf { repo.conversation(it)?.isGroup == true }?.let { return it == row.conversationId }
        if (conversationId.isEmpty()) return true
        if (DirectArchive.isGroup(conversationId)) return conversationId == row.conversationId
        val peer = conversationId.removePrefix(DirectArchive.PREFIX)
        return (manager.conversationWith(Handles.display(peer)) ?: conversationId) == row.conversationId
    }

    /**
     * Whether [row] is the message of whoever an edit or unsend says did it: the user's own
     * (sent from here or another of their devices) for one from the user ([fromMe]); else one
     * [sender] sent.
     */
    private fun authoredBy(row: MessageRow, fromMe: Boolean, sender: String): Boolean =
        if (fromMe) {
            row.isOutgoing && row.direction == "out"
        } else {
            !row.isOutgoing && row.direction == "in" && sender.isNotBlank() && IMessageAddress.same(row.senderId, sender)
        }

    /**
     * Whether a change at [atMs] (Apple's time of it; 0: now) of a message sent at [sentAt] is within
     * Apple's [windowMs] of it, give or take a device's clock ([DirectArchive.MAX_FUTURE_MS]). The
     * change's own time is what counts, not when it got here: one the user made on another device,
     * or anyone's, can arrive long after (the phone off, or Apple's journal after a restart).
     */
    private fun inWindow(sentAt: Long, atMs: Long, windowMs: Long): Boolean {
        val at = atMs.takeIf { it > 0 } ?: System.currentTimeMillis()
        return at - sentAt <= windowMs + DirectArchive.MAX_FUTURE_MS
    }

    /**
     * Someone put a tapback on a message or took it back. A new one on one of the user's messages
     * is announced, as an iPhone does ("Loved “See you at 6”"), unless its chat is on screen. Taken
     * only from someone in the message's chat (or the user, on another device), in that chat.
     */
    private fun reaction(update: DirectArchive.Update.Reaction) {
        val row = repo.message(update.messageId) ?: return hold(update.messageId, update)
        if (!update.fromMe && !isMember(row.conversationId, update.participantId)) {
            update.sticker?.let(::dropPicture)
            return refused("a tapback", row.conversationId, "from someone not in the chat")
        }
        if (!inChatOf(row, update.conversationId, update.groupGuid)) {
            update.sticker?.let(::dropPicture)
            return refused("a tapback", row.conversationId, "from another chat")
        }
        val who = if (update.fromMe) ME else update.participantId
        // Apple's journal hands a tapback over again after a restart: one already shown is not announced again.
        val known = row.reactions.any { it.emoji == update.emoji && who in it.participantIds }
        // A sticker as a tapback shows as its picture (kept by who put it there, one each): in place
        // before the tapback is, so that it shows with it. Another tapback in its place, or the
        // sticker taken back, and it goes.
        val stickerKey = DirectArchive.guidOf(row.id)?.let { IMessageExtra.tapbackStickerKey(it, who) }
        val sticker = stickerKey != null && update.enable && update.emoji == DirectArchive.STICKER_TAPBACK && update.sticker == stickerKey
        if (sticker) claimPicture(stickerKey!!)
        repo.applyReaction(update.messageId, who, update.emoji, update.enable, update.fromMe, update.atMs)
        if (stickerKey != null && !sticker && (update.enable || update.emoji == DirectArchive.STICKER_TAPBACK)) {
            update.sticker?.let(::dropPicture)
            deletePicture(stickerKey)
        }
        // On Apple's file servers: fetched now that the tapback was taken, while it is still this one
        // (an earlier sticker of theirs does not show meanwhile).
        if (sticker && update.stickerFile.isNotEmpty()) {
            deletePicture(stickerKey!!)
            fetchSticker(row.id, row.conversationId, stickerKey, update.stickerFile, who) { current ->
                current.reactions.any { it.emoji == DirectArchive.STICKER_TAPBACK && who in it.participantIds }
            }
        }
        if (update.fromMe || !update.enable || known || !row.isOutgoing || row.direction != "out") return
        if (appEvents?.silences(row.conversationId) == true) return
        val quoted = tapbackText(IMessageExtra.shownText(row.text, row.extra, inkedLabel())).takeIf { it.isNotBlank() }?.let { app.getString(R.string.imessage_tapback_quote, it) }
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
     * Someone put a sticker on a message (dragged onto its bubble): kept with the message (its
     * picture under its own key), as an iPhone shows it on the bubble. Taken as a tapback is: only
     * from someone in the message's chat (or the user, on another device), in that chat; the same
     * sticker again changes nothing, and a message keeps its latest [IMessageExtra.MAX_STICKERS].
     */
    private fun placedSticker(update: DirectArchive.Update.Sticker) {
        val guid = DirectArchive.guidOf(update.messageId) ?: return
        val key = IMessageExtra.stickerKey(guid, update.stickerGuid)
        // Apple's GUIDs are UUIDs: anything else is no sticker's message.
        if (update.stickerGuid.isBlank() || update.stickerGuid.length > MAX_GUID_CHARS) return dropPicture(key)
        val row = repo.message(update.messageId) ?: return hold(update.messageId, update)
        fun refuse(why: String) {
            dropPicture(key)
            refused("a sticker", row.conversationId, why)
        }
        if (row.direction == "system") return dropPicture(key)
        if (!update.fromMe && !isMember(row.conversationId, update.participantId)) return refuse("from someone not in the chat")
        if (!inChatOf(row, update.conversationId, update.groupGuid)) return refuse("from another chat")
        val extra = IMessageExtra.parse(row.extra)
        claimPicture(key)
        if (extra.stickers.any { it.id == update.stickerGuid }) return
        val sticker = IMessageExtra.PlacedSticker(update.stickerGuid, if (update.fromMe) ME else update.participantId, update.atMs)
        val kept = (extra.stickers + sticker).sortedBy { it.atMs }.takeLast(IMessageExtra.MAX_STICKERS)
        // Those that no longer fit go, with their pictures (this one too, when it is the oldest).
        (extra.stickers + sticker).filter { it !in kept }.forEach { deletePicture(IMessageExtra.stickerKey(guid, it.id)) }
        if (sticker !in kept) return
        repo.editText(row.id, row.text, row.editedAtMs, extra.copy(stickers = kept).toJson(), snippetOf(row.text, row.extra))
        // On Apple's file servers: fetched now that it was taken, while the message still has it.
        if (update.file.isNotEmpty()) {
            fetchSticker(row.id, row.conversationId, key, update.file, sticker.by) { current ->
                IMessageExtra.parse(current.extra).stickers.any { it.id == update.stickerGuid }
            }
        }
    }

    // --- pictures fetched from Apple once a change was taken -------------------------------------

    /** How many pictures each person's changes had fetched from Apple without anyone asking ([AutoFetches]). */
    private val autoFetches = AutoFetches()

    /** The latest fetch of each sticker picture's key: an earlier one that ends later is not kept. */
    private val stickerFetches = HashMap<String, Any>()

    /**
     * The sticker [by] put on message [messageId] (in [conversationId]), on Apple's file servers where
     * the library's description [file] says: fetched now that its change was taken (never more than
     * [DirectEngine.STICKER_MAX_BYTES], [STICKER_FETCH_MS] at most, from Apple's own servers only:
     * [DirectWire.download]), drawn again no larger than shown ([PreviewImages.png]) and kept under
     * [key], while [stillWanted] says the message still has it; at most [AutoFetches.MAX_PER_HOUR] a
     * person's an hour. Not waited for: the message shows it once it is there. Under this link's lock.
     */
    private fun fetchSticker(messageId: String, conversationId: String, key: String, file: String, by: String, stillWanted: (MessageRow) -> Boolean) {
        if (!autoFetches.take(by, System.currentTimeMillis())) {
            refused("a sticker's picture", conversationId, "past what one person's changes may fetch in an hour")
            return
        }
        val token = Any()
        stickerFetches[key] = token
        scope.launch {
            val picture = try {
                fetchFile(file, DirectEngine.STICKER_MAX_BYTES, STICKER_FETCH_MS)?.let { PreviewImages.png(it, PreviewImages.STICKER_SIDE) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "a sticker's picture was not made (${e::class.java.simpleName})")
                null
            }
            synchronized(this@DirectLink) {
                if (stickerFetches[key] !== token) return@synchronized
                stickerFetches.remove(key)
                if (picture == null) return@synchronized
                val current = repo.message(messageId)?.takeIf(stillWanted) ?: return@synchronized
                try {
                    MediaCache.writeAtomically(pictureFile(key), picture)
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "a sticker's picture was not written", e)
                    return@synchronized
                }
                com.lightdecoder.archive.ArchiveEvents.notify(
                    com.lightdecoder.archive.ArchiveChange(current.conversationId, com.lightdecoder.archive.ArchiveChange.Kind.MESSAGES, ChatService.IMESSAGE),
                )
            }
        }
    }

    /**
     * The file the library's description [file] names, fetched from Apple through the ":imessage"
     * process ([DirectWire.DOWNLOAD]) as one nobody asked for: only from Apple's own file servers,
     * never more than [maxBytes], in [timeoutMs] at most. Its bytes, or null when it did not come.
     */
    private suspend fun fetchFile(file: String, maxBytes: Long, timeoutMs: Long): ByteArray? {
        // Named as a partial file, so one the other process wrote after this gave up is cleaned up.
        val raw = repo.mediaFileFor("fetched/" + UUID.randomUUID(), "part")
        try {
            val reply = withTimeoutOrNull(timeoutMs + FETCH_SLACK_MS) {
                requests.call(DirectWire.DOWNLOAD, DirectWire.download(file, raw.path, maxBytes, timeoutMs), timeoutMs + FETCH_SLACK_MS)
            } ?: return null
            DirectWire.errorOf(reply)?.let {
                Log.i(TAG, "a picture was not fetched: ${com.lightdecoder.imessage.Redact.text(it)}")
                return null
            }
            if (!raw.isFile || raw.length() !in 1..maxBytes) return null
            return raw.readBytes()
        } finally {
            raw.delete()
        }
    }

    /**
     * The archive's chat an event names ([DirectArchive.Update.PollVote]'s): the group when it is
     * stored, else the person's stored chat (a Blue Bubbles one too), else as it is named; null for none.
     */
    private fun chatNamed(conversationId: String, groupGuid: String?): String? {
        groupGuid?.let(DirectArchive::groupConversationId)?.takeIf { repo.conversation(it)?.isGroup == true }?.let { return it }
        if (conversationId.isEmpty()) return null
        if (DirectArchive.isGroup(conversationId)) return conversationId
        return manager.conversationWith(Handles.display(conversationId.removePrefix(DirectArchive.PREFIX))) ?: conversationId
    }

    /**
     * The stored card of the poll a vote or change names by [messageId]: that message when it is a
     * poll's card, else, in the chat named ([chatNamed]), the card whose session that message is a
     * later card of ([IMessageExtra.pollCards]) or started ([IMessageExtra.App.root]).
     */
    private fun pollCard(messageId: String, chat: String?): MessageRow? {
        repo.message(messageId)?.takeIf { Polls.isPoll(IMessageExtra.parse(it.extra).app) && it.direction != "system" }?.let { return it }
        val guid = DirectArchive.guidOf(messageId) ?: return null
        if (chat == null) return null
        return repo.messagesWithExtra(chat, guid).firstOrNull { r ->
            val e = IMessageExtra.parse(r.extra)
            r.direction != "system" && Polls.isPoll(e.app) && (guid in e.pollCards || e.app?.root == messageId)
        }
    }

    /**
     * A vote in a poll: counted on the poll's card (the voter's latest choice replaces their earlier
     * one), with no row of its own, as on an iPhone. Taken only from someone in the poll's chat (or
     * the user, on another device), in that chat; for whoever sent it. One for a card not stored yet
     * waits for it ([hold]).
     */
    private fun pollVote(update: DirectArchive.Update.PollVote) {
        val card = pollCard(update.messageId, chatNamed(update.conversationId, update.groupGuid)) ?: return hold(update.messageId, update)
        if (!update.fromMe && !isMember(card.conversationId, update.voter)) return refused("a vote", card.conversationId, "from someone not in the chat")
        if (!inChatOf(card, update.conversationId, update.groupGuid)) return refused("a vote", card.conversationId, "from another chat")
        val extra = IMessageExtra.parse(card.extra)
        if (extra.app?.let { Polls.decode(it.url) } == null) return
        val ballot = Polls.Ballot(Polls.voterKey(update.voter, update.fromMe), update.options, update.atMs)
        val votes = Polls.withBallot(extra.pollVotes, ballot)
        if (votes == extra.pollVotes) return
        repo.editText(card.id, card.text, card.editedAtMs, extra.copy(pollVotes = votes).toJson(), snippetOf(card.text, card.extra))
    }

    /**
     * A poll's card sent again with its new state (a choice added): where its card is stored, that
     * card shows the new state (votes and all), as an iPhone updates a poll in place; else the new
     * card is stored as the poll's. Only from someone in the chat (or the user, on another device),
     * in it, and only as "Add Choice" changes a poll ([Polls.merged]): new choices added at the end,
     * the question and every choice there already kept as they are (nobody rewrites someone else's
     * poll, or moves its votes to other words), its count one higher (or a few, for changes missed):
     * never an older state over a newer one, nor one far ahead that no later change could follow.
     */
    private fun pollChange(update: DirectArchive.Update.PollChange) {
        val row = update.row.message
        val changed = IMessageExtra.parse(row.extra).app?.takeIf { Polls.isPoll(it) && Polls.decode(it.url) != null }
        val groupId = update.row.groupGuid?.let(DirectArchive::groupConversationId)?.takeIf { repo.conversation(it)?.isGroup == true }
        val chat = archiveIdOf(update.row.conversation, groupId)
        val card = pollCard(update.rootId, chat)
        if (card == null || changed == null) return upsert(update.row)
        if (card.conversationId != chat) return refused("a poll's change", card.conversationId, "from another chat")
        if (!row.fromMe && !isMember(card.conversationId, row.senderId)) return refused("a poll's change", card.conversationId, "from someone not in the chat")
        val guid = DirectArchive.guidOf(row.id) ?: return
        val extra = IMessageExtra.parse(card.extra)
        if (guid in extra.pollCards) return
        val shown = extra.app?.takeIf(Polls::isPoll) ?: return
        val stored = Polls.decode(shown.url) ?: return
        val incoming = Polls.decode(changed.url) ?: return
        val poll = Polls.merged(stored, incoming)
            ?: return refused("a poll's change", card.conversationId, "that is no choice added to it")
        val next = extra.copy(
            app = shown.copy(url = Polls.encode(poll), ldText = Polls.ldText(poll), root = shown.root.ifEmpty { card.id }),
            pollCards = (extra.pollCards + guid).takeLast(IMessageExtra.MAX_POLL_CARDS),
        )
        val text = card.text
        repo.editText(card.id, text, card.editedAtMs, next.toJson(), snippetOf(text, card.extra))
        // Votes on the new card that came before it.
        replayHeld(row.id)
        IMessageLog.add(app, "${IMessageLog.chatRef(card.conversationId)}: a poll's new state shown on its card (through the iMessage library)")
    }

    /**
     * The sender edited a part: the message's text is rebuilt from its parts, marked edited. The
     * same edit handed over again (Apple's journal does that after a restart) counts once. Taken
     * only from the message's own sender, in its chat, within Apple's 15 minutes of it being sent
     * and for 5 edits at most ([inWindow]).
     */
    private fun edit(update: DirectArchive.Update.Edit) {
        val row = repo.message(update.messageId) ?: return hold(update.messageId, update)
        if (row.direction == "system") return
        if (!authoredBy(row, update.fromMe, update.sender)) return refused("an edit", row.conversationId, "of someone else's message")
        if (!inChatOf(row, update.conversationId, update.groupGuid)) return refused("an edit", row.conversationId, "from another chat")
        val extra = IMessageExtra.parse(row.extra)
        if (update.part in extra.unsent) return
        val parts = partsOf(row, extra)
        val newRuns = TextRuns.normalize(update.text, update.runs)
        val newMentions = Mentions.normalize(update.text, update.mentions)
        // The same edit again (Apple's journal after a restart), or one that changes nothing of the part: nothing to do.
        val same = extra.runs[update.part].orEmpty() == newRuns && extra.mentions[update.part].orEmpty() == newMentions
        if (parts[update.part] == update.text && (update.atMs <= row.editedAtMs || same)) return
        if (!inWindow(row.timestampMs, update.atMs, EditRule.WINDOW_MS)) return refused("an edit", row.conversationId, "after Apple's 15 minutes")
        if (extra.edits >= IMessageExtra.MAX_EDITS) return refused("an edit", row.conversationId, "past Apple's ${IMessageExtra.MAX_EDITS} edits")
        val runs = extra.withRuns(update.part, newRuns)
        val mentions = extra.withMentions(update.part, newMentions)
        val next = extra.copy(
            parts = parts + (update.part to update.text), edits = extra.edits + 1, runs = runs, mentions = mentions,
            // What it read until now, as an iPhone keeps it for "Edited".
            history = extra.withEarlier(row.text, row.editedAtMs.takeIf { it > 0 } ?: row.timestampMs),
        )
        repo.editText(row.id, next.text(), update.atMs, next.toJson(), snippetOf(next.text(), row.extra))
    }

    /**
     * The sender unsent a part. Nothing of the message left: it gives way to "… unsent a
     * message." Else only that part goes, and is remembered as unsent, so the same unsend handed
     * over again changes nothing. Taken only from the message's own sender, in its chat, within
     * Apple's 2 minutes of it being sent ([inWindow]).
     */
    private fun unsent(update: DirectArchive.Update.Unsent) {
        val row = repo.message(update.messageId) ?: return hold(update.messageId, update)
        if (row.direction == "system") return
        if (!authoredBy(row, update.fromMe, update.sender)) return refused("an unsend", row.conversationId, "of someone else's message")
        if (!inChatOf(row, update.conversationId, update.groupGuid)) return refused("an unsend", row.conversationId, "from another chat")
        val extra = IMessageExtra.parse(row.extra)
        if (update.part in extra.unsent) return
        if (!inWindow(row.timestampMs, update.atMs, UnsendWindow.MS)) return refused("an unsend", row.conversationId, "after Apple's 2 minutes")
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
            forgetPictures(row)
            repo.replaceWithPlaceholder(row.id, text)
            return
        }
        if (partId in media) repo.removeMediaPart(row.id, partId)
        // What it read before an edit is the whole message's, the part unsent among it: that goes too,
        // with the "Edited" by it (the count of edits stays, Apple's five are five).
        val next = extra.copy(
            parts = textLeft, unsent = extra.unsent + update.part, runs = extra.runs - update.part, mentions = extra.mentions - update.part,
            history = emptyList(),
        )
        repo.editText(row.id, next.text(), 0, next.toJson(), snippetOf(next.text(), row.extra))
    }

    /**
     * The chat's background was set or taken off (by someone in it, or the user on another device):
     * the picture the ":imessage" process wrote becomes the chat's ([ChatBackgrounds]) and the row
     * saying so is stored, unless the chat already has a newer one (then neither: the picture goes).
     */
    private fun background(update: DirectArchive.Update.Background) {
        val incoming = update.path?.let(::File)?.takeIf { ChatBackgrounds.isOurs(app, it) }
        // From the future: it would hold the chat's background against every later change.
        if (!ChatBackgrounds.isPlausibleVersion(update.version)) {
            incoming?.delete()
            IMessageLog.add(app, "a chat background from Apple whose version is from the future was dropped")
            return
        }
        val store = ChatBackgrounds(app)
        val id = backgroundChatId(update) ?: run {
            // A group not stored here: nothing to show it in.
            incoming?.delete()
            return
        }
        // Only someone in the chat sets it: the user (on another device), the other person of a
        // chat with one person (it is the chat with them: DirectArchive.backgroundChat), a member of a group.
        val by = update.row?.message
        if (by?.fromMe != true && update.groupGuid != null && !isMember(id, by?.senderId.orEmpty())) {
            incoming?.delete()
            refused("a chat background", id, "from someone not in the group")
            return
        }
        // Older than the chat's, or that one again (Apple's journal after a restart, or the user's
        // own change from here coming back): nothing new to show or to say.
        val stored = store.of(id).version
        if (stored > 0 && update.version <= stored && ChatBackgrounds.isPlausibleVersion(stored)) {
            incoming?.delete()
            return
        }
        update.row?.let(::upsert)
        // A chat with one person stored only now (the row made it): where it was filed.
        val chat = backgroundChatId(update) ?: id
        val style = update.style
        if (store.apply(chat, if (style == null) incoming else null, update.version, style) == ChatBackgrounds.Applied.APPLIED) {
            IMessageLog.add(app, "${IMessageLog.chatRef(chat)}: chat background ${describe(incoming != null, style)} (through the iMessage library)")
            if (style is BackgroundStyle.Dynamic && !style.isKnown) {
                // One not drawn here yet (shown as a plain gradient): its name, to learn it.
                IMessageLog.add(app, "${IMessageLog.chatRef(chat)}: unknown dynamic background \"${loggable(style.identifier)}\"")
            }
        }
        if (style != null) incoming?.delete()
        com.lightdecoder.archive.ArchiveEvents.notify(
            com.lightdecoder.archive.ArchiveChange(chat, com.lightdecoder.archive.ArchiveChange.Kind.CONVERSATIONS, ChatService.IMESSAGE),
        )
    }

    /** A background change as the log says it: "set (photo)", "set (gradient, 3 colours)", "taken off". */
    private fun describe(picture: Boolean, style: BackgroundStyle?): String = when (style) {
        is BackgroundStyle.Gradient -> "set (gradient, ${style.colors.size} colours)"
        is BackgroundStyle.Dynamic -> "set (dynamic${if (style.isKnown) ", ${loggable(style.identifier)}" else ""})"
        null -> if (picture) "set (photo)" else "taken off"
    }

    /** Apple's identifier of a dynamic background, safe for the log: short, plain characters only. */
    private fun loggable(identifier: String): String =
        identifier.take(64).map { if (it.isLetterOrDigit() || it in "._-") it else '?' }.joinToString("")

    /** Words the ":imessage" process gave for the log ([DirectArchive.Update.Dropped]): at most [max] characters, plain ones only. */
    private fun loggableText(text: String, max: Int): String =
        text.take(max).map { if (it.isLetterOrDigit() || it in " .,:;()/'_-") it else '?' }.joinToString("")

    /**
     * The archive's chat a background change is for: the group, when it is stored (it is not made
     * for its background, nor would anyone in it be known), or the person's stored chat (a Blue
     * Bubbles one too), else theirs as it is made now.
     */
    private fun backgroundChatId(update: DirectArchive.Update.Background): String? {
        update.groupGuid?.let { guid ->
            val group = DirectArchive.groupConversationId(guid)
            return group.takeIf { repo.conversation(it)?.isGroup == true }
        }
        val peer = update.peer ?: return update.conversationId
        return manager.conversationWith(Handles.display(peer)) ?: update.conversationId
    }

    /**
     * Sets [conversationId]'s background to the picture at [uri] (one the user picked), or takes it
     * off ([uri] null), as on an iPhone: every member's iPhone (and the user's other devices) shows
     * it. In turn with the chat's sends ([inTurn]); once Apple confirmed it, it shows here too, with
     * "You changed the chat background.". Throws [DirectEngine.ImessageError] saying why when it
     * does not go through.
     */
    suspend fun setBackground(conversationId: String, uri: Uri?) =
        setBackground(conversationId, if (uri != null) BackgroundChoice.Photo(uri) else BackgroundChoice.None)

    /**
     * Sets [conversationId]'s background to [choice]: a photo of the user's, one of the app's own
     * designs ([BackgroundThemes], drawn now at this screen's size and sent as a photo), a gradient
     * or dynamic one (sent as such: an iPhone draws it itself), or none. As [setBackground] with a picture.
     */
    suspend fun setBackground(conversationId: String, choice: BackgroundChoice) {
        targetOf(conversationId) ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_unreachable))
        val style = (choice as? BackgroundChoice.Drawn)?.style
        val picture = when (choice) {
            is BackgroundChoice.Photo -> preparePicture(choice.uri)
            is BackgroundChoice.Theme -> drawTheme(choice.id)
            else -> null
        }
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        inTurn(conversationId) {
            try {
                // Read again in turn: the chat as it is when it goes.
                val target = targetOf(conversationId) ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_unreachable))
                val version = ChatBackgrounds.nextVersion(ChatBackgrounds(app).of(conversationId).version)
                val reply = requests.call(DirectWire.BACKGROUND, DirectWire.background(target, picture?.path, version, style), MEDIA_SEND_TIMEOUT_MS)
                DirectWire.errorOf(reply)?.let { throw DirectEngine.ImessageError(it) }
                synchronized(this@DirectLink) { backgroundSetHere(conversationId, picture, version, style) }
                done.complete(Unit)
            } catch (e: Exception) {
                picture?.delete()
                done.completeExceptionally(e)
            }
        }
        done.await()
    }

    /**
     * The background just set from here: shown, with the row saying so, as one from Apple would be.
     * Not when the chat has one as new (a change from another device came meanwhile): then nothing
     * changed here, and no row says it did.
     */
    private fun backgroundSetHere(conversationId: String, picture: File?, version: Long, style: BackgroundStyle?) {
        if (ChatBackgrounds(app).apply(conversationId, picture, version, style) != ChatBackgrounds.Applied.APPLIED) {
            Log.i(TAG, "a background set here was not newer than the chat's")
            return
        }
        val texts = DirectTexts(app)
        val text = if (picture != null || style != null) texts.changedBackground(texts.you) else texts.removedBackground(texts.you)
        repo.upsertMessage(
            MessageDto(
                id = DirectArchive.messageId("background-" + UUID.randomUUID().toString().uppercase()),
                conversationId = conversationId,
                fromMe = true,
                timestampMs = System.currentTimeMillis(),
                direction = "system",
                transport = IMessageManager.TRANSPORT,
                text = text,
                parts = listOf(PartDto(partId = "0", kind = "text", text = text)),
                isOld = true,
            ),
        )
        IMessageLog.add(app, "${IMessageLog.chatRef(conversationId)}: chat background ${describe(picture != null, style)} through the iMessage library (Apple confirmed)")
        com.lightdecoder.archive.ArchiveEvents.notify(
            com.lightdecoder.archive.ArchiveChange(conversationId, com.lightdecoder.archive.ArchiveChange.Kind.CONVERSATIONS, ChatService.IMESSAGE),
        )
    }

    /** The app's design [id] ([BackgroundThemes]) drawn at this screen's size as the JPEG that is sent and shown. */
    private fun drawTheme(id: String): File {
        val metrics = app.resources.displayMetrics
        val (w, h) = themeSize(metrics.widthPixels, metrics.heightPixels)
        val bitmap = try {
            BackgroundThemes.render(id, w, h)
        } catch (e: OutOfMemoryError) {
            null
        } ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_background_unreadable))
        val out = ChatBackgrounds.incomingFile(app, "outgoing")
        if (!BackgroundPosters.writeJpeg(bitmap, out)) {
            out.delete()
            throw DirectEngine.ImessageError(app.getString(R.string.imessage_background_unreadable))
        }
        return out
    }

    /** The picture at [uri] as the JPEG that is sent and shown ([BackgroundPosters.prepareOutgoing]), in the backgrounds' folder. */
    private fun preparePicture(uri: Uri): File {
        val raw = ChatBackgrounds.incomingFile(app, "picked")
        copyCapped(uri, raw)
        if (raw.length() > MAX_BACKGROUND_SOURCE_BYTES) {
            raw.delete()
            throw DirectEngine.ImessageError(app.getString(R.string.imessage_background_too_big))
        }
        val out = ChatBackgrounds.incomingFile(app, "outgoing")
        val ok = try {
            BackgroundPosters.prepareOutgoing(raw.readBytes(), out)
        } catch (e: OutOfMemoryError) {
            false
        } finally {
            raw.delete()
        }
        if (!ok) {
            out.delete()
            throw DirectEngine.ImessageError(app.getString(R.string.imessage_background_unreadable))
        }
        return out
    }

    // --- Send Later ---------------------------------------------------------------------------------

    /** Whether [row] is one of the user's Send Later messages the library can take back (or send now). */
    fun canCancelScheduled(row: MessageRow, now: Long = System.currentTimeMillis(), reachable: Boolean? = null): Boolean =
        active && SendLater.cancellable(row, now) && (reachable ?: canSend(row.conversationId))

    /**
     * "Cancel Send Later": Apple is told not to send the user's scheduled message [messageId]; once
     * it confirmed, the message is gone here too (as on the user's other devices). Throws
     * [DirectEngine.ImessageError] saying why when it cannot be taken back.
     */
    suspend fun cancelScheduled(messageId: String) {
        val row = repo.message(messageId) ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_not_stored))
        val guid = DirectArchive.guidOf(messageId)
        if (guid == null || !SendLater.cancellable(row, System.currentTimeMillis())) {
            throw DirectEngine.ImessageError(app.getString(R.string.imessage_send_later_not_cancellable))
        }
        val target = targetOf(row.conversationId) ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_unreachable))
        withContext(NonCancellable) {
            val reply = requests.call(DirectWire.UNSCHEDULE, DirectWire.unschedule(target, guid), SEND_TIMEOUT_MS)
            DirectWire.errorOf(reply)?.let { throw DirectEngine.ImessageError(it) }
            apply(DirectArchive.Update.Remove(messageId))
        }
        IMessageLog.add(app, "${IMessageLog.chatRef(row.conversationId)}: Send Later cancelled through the iMessage library (Apple confirmed)")
    }

    /**
     * "Send Now" for a scheduled text: it is taken back ([cancelScheduled]) and sent again at once,
     * with its effect, formatting and reply. Returns the new local copy's id.
     */
    suspend fun sendScheduledNow(messageId: String): String {
        val row = repo.message(messageId) ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_not_stored))
        if (row.media.isNotEmpty() || row.text.isBlank()) throw DirectEngine.ImessageError(app.getString(R.string.imessage_send_later_not_cancellable))
        val extra = IMessageExtra.parse(row.extra)
        cancelScheduled(messageId)
        return sendText(row.conversationId, row.text, row.replyToId.ifEmpty { null }, extra.effect, extra.textRuns(row.text))
    }

    // --- a group's name, members and photo, from here (a chat's details) ---------------------------

    /** What a chat's details offer for a group ([groupRules]): what the library can change of it now. */
    data class GroupRules(
        /** It can be renamed, given a photo, and have people added (the library reaches it, the user is in it). */
        val manageable: Boolean,
        /** Someone can be removed from it: it has [MIN_OTHERS_TO_LEAVE] others besides the user at least, as on an iPhone. */
        val canRemove: Boolean,
        /** The user can leave it: the same. */
        val canLeave: Boolean,
    ) {
        companion object {
            val NONE = GroupRules(manageable = false, canRemove = false, canLeave = false)
        }
    }

    /**
     * What a chat's details may change of [conversationId] through the library: nothing but for an
     * iMessage group (with its guid) it can reach, the user still in it; people removed, or the user
     * leaving, only while it has [MIN_OTHERS_TO_LEAVE] others at least (an iPhone's rule: unverified).
     */
    fun groupRules(conversationId: String): GroupRules {
        if (!active || DirectArchive.groupGuid(conversationId) == null) return GroupRules.NONE
        val target = runCatching { targetOf(conversationId) }.getOrNull()?.takeIf { it.groupGuid != null } ?: return GroupRules.NONE
        val enough = target.participants.size >= MIN_OTHERS_TO_LEAVE && target.from.isNotBlank()
        return GroupRules(manageable = true, canRemove = enough, canLeave = enough)
    }

    /** Group [conversationId]'s own name ("" for none: it is shown by its members' names), as "Change Name and Photo" starts from. */
    fun groupName(conversationId: String): String = synchronized(this) { ownName(conversationId) }

    /** The group [conversationId] to change, as the library reaches it; throws why it cannot be changed. */
    private fun groupTarget(conversationId: String, members: Boolean = false): DirectWire.Target {
        if (!active || DirectArchive.groupGuid(conversationId) == null) throw DirectEngine.ImessageError(app.getString(R.string.imessage_group_not_a_group))
        if (isLeft(conversationId)) throw DirectEngine.ImessageError(app.getString(R.string.imessage_not_in_group))
        val target = targetOf(conversationId)?.takeIf { it.groupGuid != null }
            ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_unreachable))
        // A change of its members names the user's own address among them: without it the user would leave.
        if (members && target.from.isBlank()) throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_unreachable))
        return target
    }

    /** Runs [block] in turn with [conversationId]'s sends ([inTurn]) and returns what it did, or throws what it threw. */
    private suspend fun <T> inTurnAwait(conversationId: String, block: suspend () -> T): T {
        val done = kotlinx.coroutines.CompletableDeferred<T>()
        inTurn(conversationId) {
            try {
                done.complete(block())
            } catch (e: Throwable) {
                // Never thrown on: the chat's next send still goes.
                done.completeExceptionally(e)
            }
        }
        return done.await()
    }

    /** Throws what [reply] says went wrong, if anything. */
    private fun failOn(reply: JSONObject) {
        DirectWire.errorOf(reply)?.let { throw if (DirectWire.unconfirmed(reply)) DirectEngine.Unconfirmed(it) else DirectEngine.ImessageError(it) }
    }

    /**
     * Renames the group [conversationId] to [name] ("" takes its name off), as on an iPhone: every
     * member's iPhone (and the user's other devices) shows it. In turn with the chat's sends; once
     * Apple confirmed it, it is the group's name here too, with "You named the conversation “…”.".
     * Throws [DirectEngine.ImessageError] saying why when it does not go through.
     */
    suspend fun renameGroup(conversationId: String, name: String) {
        val newName = name.trim().take(MAX_GROUP_NAME)
        groupTarget(conversationId)
        inTurnAwait(conversationId) {
            // Read again in turn: the group as it is when it goes.
            val target = groupTarget(conversationId)
            val reply = requests.call(DirectWire.RENAME, DirectWire.rename(target, newName), SEND_TIMEOUT_MS)
            failOn(reply)
            val texts = DirectTexts(app)
            val text = if (newName.isEmpty()) texts.nameRemoved(texts.you) else texts.named(texts.you, newName)
            synchronized(this) { upsert(ownGroupEvent(conversationId, target, reply, text, name = newName, renamed = true)) }
            IMessageLog.add(app, "${IMessageLog.chatRef(conversationId)}: group ${if (newName.isEmpty()) "name taken off" else "renamed"} through the iMessage library (Apple confirmed)")
        }
    }

    /**
     * Adds the people at [addresses] (numbers or email addresses) to the group [conversationId], as
     * on an iPhone. Apple is asked first whether each of them is on iMessage; if any is not (or is no
     * address), nobody is added and the error names them. People already in it are left out. Once
     * Apple confirmed it, they are in it here too, with "You added …". Throws
     * [DirectEngine.ImessageError] saying why when it does not go through.
     */
    suspend fun addMembers(conversationId: String, addresses: List<String>) {
        val target = groupTarget(conversationId, members = true)
        val own = IMessageAddress.key(target.from)
        val notOnIMessage = ArrayList<String>()
        val adding = LinkedHashMap<String, String>()
        for (address in addresses.map { it.trim() }.filter { it.isNotEmpty() }) {
            val handle = Handles.normalize(address)
            val key = handle?.let(IMessageAddress::key)
            when {
                handle == null || key == null -> notOnIMessage += address
                IMessageAddress.sameKey(key, own) -> Unit
                target.participants.any { IMessageAddress.sameKey(IMessageAddress.key(it), key) } -> Unit
                else -> adding.putIfAbsent(key, handle)
            }
        }
        if (notOnIMessage.isEmpty() && adding.isEmpty()) throw DirectEngine.ImessageError(app.getString(R.string.imessage_group_already_in))
        for (handle in adding.values) {
            val reply = requests.call(DirectWire.REACH, DirectWire.reach(handle), REACH_TIMEOUT_MS)
            // The library could not be asked: that says nothing about whether they have iMessage.
            DirectWire.errorOf(reply)?.let { throw DirectEngine.ImessageError(it) }
            if (reply.optJSONObject("conversation") == null) notOnIMessage += nameOf(handle)
        }
        if (notOnIMessage.isNotEmpty()) {
            throw DirectEngine.ImessageError(
                app.resources.getQuantityString(R.plurals.imessage_group_add_not_on_imessage, notOnIMessage.size, notOnIMessage.joinToString(", ")),
            )
        }
        inTurnAwait(conversationId) {
            val now = groupTarget(conversationId, members = true)
            val added = adding.values.filter { h -> now.participants.none { IMessageAddress.same(it, h) } }
            if (added.isEmpty()) throw DirectEngine.ImessageError(app.getString(R.string.imessage_group_already_in))
            val others = now.participants + added
            val version = groupVersions.next(conversationId)
            val reply = requests.call(DirectWire.MEMBERS, DirectWire.members(now, others + now.from, version), SEND_TIMEOUT_MS)
            failOn(reply)
            val texts = DirectTexts(app)
            val text = texts.added(texts.you, added.joinToString(", ") { nameOf(it) })
            synchronized(this) { upsert(ownGroupEvent(conversationId, now, reply, text, members = others, member = true, groupVersion = version)) }
            IMessageLog.add(app, "${IMessageLog.chatRef(conversationId)}: ${added.size} member(s) added through the iMessage library (Apple confirmed)")
        }
    }

    /**
     * Removes the member at [address] from the group [conversationId], as on an iPhone: only while it
     * has [MIN_OTHERS_TO_LEAVE] others at least ([groupRules]). Once Apple confirmed it, they are out
     * of it here too, with "You removed …". Throws [DirectEngine.ImessageError] saying why when it
     * does not go through.
     */
    suspend fun removeMember(conversationId: String, address: String) {
        val key = IMessageAddress.key(address) ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_group_not_member))
        fun check(target: DirectWire.Target): String {
            if (target.participants.size < MIN_OTHERS_TO_LEAVE) throw DirectEngine.ImessageError(app.getString(R.string.imessage_group_too_small))
            return target.participants.firstOrNull { IMessageAddress.sameKey(IMessageAddress.key(it), key) }
                ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_group_not_member))
        }
        check(groupTarget(conversationId, members = true))
        inTurnAwait(conversationId) {
            val now = groupTarget(conversationId, members = true)
            val removed = check(now)
            val others = now.participants - removed
            val version = groupVersions.next(conversationId)
            val reply = requests.call(DirectWire.MEMBERS, DirectWire.members(now, others + now.from, version), SEND_TIMEOUT_MS)
            failOn(reply)
            val texts = DirectTexts(app)
            synchronized(this) {
                upsert(ownGroupEvent(conversationId, now, reply, texts.removed(texts.you, nameOf(removed)), members = others, member = true, groupVersion = version))
            }
            IMessageLog.add(app, "${IMessageLog.chatRef(conversationId)}: a member removed through the iMessage library (Apple confirmed)")
        }
    }

    /**
     * The user leaves the group [conversationId] ("Leave this Conversation"), as on an iPhone: only
     * while it has [MIN_OTHERS_TO_LEAVE] others at least. Once Apple confirmed it, the group is read
     * only here ("You left the conversation."): nothing more goes to it. Throws
     * [DirectEngine.ImessageError] saying why when it does not go through.
     */
    suspend fun leaveGroup(conversationId: String) {
        val first = groupTarget(conversationId, members = true)
        if (first.participants.size < MIN_OTHERS_TO_LEAVE) throw DirectEngine.ImessageError(app.getString(R.string.imessage_group_too_small))
        inTurnAwait(conversationId) {
            val now = groupTarget(conversationId, members = true)
            if (now.participants.size < MIN_OTHERS_TO_LEAVE) throw DirectEngine.ImessageError(app.getString(R.string.imessage_group_too_small))
            val version = groupVersions.next(conversationId)
            // Everyone but the user.
            val reply = requests.call(DirectWire.MEMBERS, DirectWire.members(now, now.participants, version), SEND_TIMEOUT_MS)
            failOn(reply)
            val texts = DirectTexts(app)
            synchronized(this) {
                upsert(ownGroupEvent(conversationId, now, reply, texts.left(texts.you), members = now.participants, member = false, groupVersion = version))
            }
            IMessageLog.add(app, "${IMessageLog.chatRef(conversationId)}: left the group through the iMessage library (Apple confirmed)")
        }
    }

    /**
     * Sets the group [conversationId]'s photo to the picture at [uri] (one the user picked: cut to a
     * centred square, [GroupPhotoImages]), or takes it off ([uri] null), as on an iPhone. In turn with
     * the chat's sends; once Apple confirmed it, it shows here too, with "You changed the group
     * photo.". Throws [DirectEngine.ImessageError] saying why when it does not go through.
     */
    suspend fun setGroupPhoto(conversationId: String, uri: Uri?) {
        groupTarget(conversationId)
        val picture = uri?.let(::prepareGroupPhoto)
        try {
            inTurnAwait(conversationId) {
                val target = groupTarget(conversationId)
                val version = nextPhotoVersion(conversationId)
                val reply = requests.call(DirectWire.GROUP_PHOTO, DirectWire.groupPhoto(target, picture?.path, version), MEDIA_SEND_TIMEOUT_MS)
                failOn(reply)
                synchronized(this) { groupPhotoSetHere(conversationId, target, reply, picture, version) }
            }
        } catch (e: Exception) {
            picture?.delete()
            throw e
        }
    }

    /** The version a group photo set here goes as: after the group's, and after its photo's. */
    private fun nextPhotoVersion(conversationId: String): Long {
        val photo = GroupPhotos(app).of(conversationId)
        val afterPhoto = if (photo.known) GroupVersions.nextAfter(photo.version) else 0
        return maxOf(groupVersions.next(conversationId), afterPhoto)
    }

    /** The picture at [uri] as the square JPEG that is sent and shown ([GroupPhotoImages]), where the group photos are kept. */
    private fun prepareGroupPhoto(uri: Uri): File {
        val raw = GroupPhotos.incomingFile(app, GroupPhotos.PICKED)
        copyCapped(uri, raw)
        if (raw.length() > MAX_BACKGROUND_SOURCE_BYTES) {
            raw.delete()
            throw DirectEngine.ImessageError(app.getString(R.string.imessage_group_photo_too_big))
        }
        val out = GroupPhotos.incomingFile(app, GroupPhotos.OUTGOING)
        val ok = try {
            GroupPhotoImages.write(raw.readBytes(), out)
        } catch (e: OutOfMemoryError) {
            false
        } finally {
            raw.delete()
        }
        if (!ok) {
            out.delete()
            throw DirectEngine.ImessageError(app.getString(R.string.imessage_group_photo_unreadable))
        }
        return out
    }

    /**
     * The photo just set from here, Apple having confirmed it: shown, with the row saying so, as one
     * from Apple would be; whatever version the group's photo is at here, as the user's own change it
     * always shows ([GroupPhotos.apply]'s own: a version someone else claimed holds nothing back).
     */
    private fun groupPhotoSetHere(conversationId: String, target: DirectWire.Target, reply: JSONObject, picture: File?, version: Long) {
        groupVersions.note(conversationId, version)
        if (GroupPhotos(app).apply(conversationId, picture, version, own = true) != GroupPhotos.Applied.APPLIED) {
            Log.i(TAG, "a group photo set here was not kept")
            return
        }
        val texts = DirectTexts(app)
        val text = if (picture != null) texts.changedPhoto(texts.you) else texts.removedPhoto(texts.you)
        upsert(ownGroupEvent(conversationId, target, reply, text, groupVersion = version))
        IMessageLog.add(app, "${IMessageLog.chatRef(conversationId)}: group photo ${if (picture != null) "set" else "taken off"} through the iMessage library (Apple confirmed)")
        com.lightdecoder.archive.ArchiveEvents.notify(
            com.lightdecoder.archive.ArchiveChange(conversationId, com.lightdecoder.archive.ArchiveChange.Kind.CONVERSATIONS, ChatService.IMESSAGE),
        )
    }

    /**
     * A change of the group [conversationId] the user made from here, as the user's other device's
     * would come from Apple ([upsert] then takes it the same way): the group with [members] (the
     * library's addresses of the others; null: as they are) and, for a rename, [name]; the row [text]
     * by the user, known by the GUID Apple's answer [reply] named.
     */
    private fun ownGroupEvent(
        conversationId: String,
        target: DirectWire.Target,
        reply: JSONObject,
        text: String,
        name: String? = null,
        renamed: Boolean = false,
        members: List<String>? = null,
        member: Boolean? = null,
        groupVersion: Long? = null,
    ): DirectArchive.Update.Upsert {
        val others = members ?: target.participants
        val from = target.from
        val guid = reply.optString("guid").takeIf { it.isNotBlank() } ?: ("group-" + UUID.randomUUID().toString().uppercase())
        val conversation = ConversationDto(
            id = conversationId,
            // A name only with a rename: any other event says nothing about it (its own name stays).
            name = name.orEmpty(),
            isGroup = true,
            type = DirectArchive.TRANSPORT,
            defaultOutgoingId = from,
            participants = others.map { h -> ParticipantDto(id = h, name = nameOf(h), number = Handles.display(h), formattedNumber = Handles.display(h)) } +
                listOfNotNull(from.takeIf { it.isNotBlank() }?.let { ParticipantDto(id = it, isMe = true) }),
            otherParticipantIds = others,
        )
        val row = MessageDto(
            id = DirectArchive.messageId(guid),
            conversationId = conversationId,
            senderId = from,
            fromMe = true,
            timestampMs = System.currentTimeMillis(),
            direction = "system",
            transport = DirectArchive.TRANSPORT,
            text = text,
            parts = listOf(PartDto(partId = "0", kind = "text", text = text)),
            isOld = true,
        )
        return DirectArchive.Update.Upsert(
            conversation, row, renamed = renamed, member = member, membersChanged = members != null, groupVersion = groupVersion,
        )
    }

    /**
     * A group's photo was set or taken off (by someone in it, or the user on another device): the
     * photo becomes the group's ([GroupPhotos]) and the row saying so is stored, unless the group's
     * photo is as new already (then neither). Only a group stored here, only from one of its members,
     * and only as of a version that could be the group's ([GroupVersions.plausible]: one far ahead
     * would hold every later change back). A photo on Apple's file servers is fetched only now that
     * all of that is so ([fetchGroupPhoto]); a picture already made ([DirectArchive.Update.GroupPhoto.path])
     * is taken only from where new group photos are made, and goes with a change that is not taken.
     */
    private fun groupPhoto(update: DirectArchive.Update.GroupPhoto) {
        val incoming = update.path?.let(::File)?.takeIf { GroupPhotos.isNew(app, it, GroupPhotos.INCOMING) }
        // A path that is not a new picture where group photos are kept (their index, another group's photo): nothing of it is taken.
        if (update.path != null && incoming == null) return
        val id = DirectArchive.groupConversationId(update.groupGuid).takeIf { repo.conversation(it)?.isGroup == true } ?: run {
            // A group not stored here: nothing to show it in.
            incoming?.delete()
            return
        }
        val by = update.row.message
        if (!by.fromMe && !isMember(id, by.senderId)) {
            incoming?.delete()
            refused("a group photo", id, "from someone not in the group")
            return
        }
        val store = GroupPhotos(app)
        val stored = store.of(id)
        if (!GroupVersions.plausible(update.version, groupVersions.of(id), stored.version.takeIf { stored.known })) {
            incoming?.delete()
            refused("a group photo", id, "as of a version far ahead of the group's")
            return
        }
        groupVersions.note(id, update.version)
        // Older than the group's photo, or that one again (Apple's journal after a restart): nothing new.
        if (stored.known && update.version <= stored.version) {
            incoming?.delete()
            return
        }
        upsert(update.row)
        if (incoming == null && !update.removed) {
            fetchGroupPhoto(id, update.file, update.version, by.senderId.takeIf { !by.fromMe } ?: ME)
            return
        }
        if (store.apply(id, incoming, update.version) == GroupPhotos.Applied.APPLIED) {
            IMessageLog.add(app, "${IMessageLog.chatRef(id)}: group photo ${if (incoming != null) "set" else "taken off"} (through the iMessage library)")
        }
        com.lightdecoder.archive.ArchiveEvents.notify(
            com.lightdecoder.archive.ArchiveChange(id, com.lightdecoder.archive.ArchiveChange.Kind.CONVERSATIONS, ChatService.IMESSAGE),
        )
    }

    /** The group photos being fetched, by group and version: Apple's journal handing a change over again fetches it once. */
    private val groupPhotoFetches = HashSet<String>()

    /**
     * Group [id]'s photo as of [version], on Apple's file servers where the library's description
     * [file] says, set by [by]: fetched now that its change was taken (never more than
     * [GroupPhotos.MAX_DOWNLOAD_BYTES], [GROUP_PHOTO_FETCH_MS] at most, from Apple's own servers only),
     * decoded no larger than needed and cut square ([GroupPhotoImages]), then the group's photo,
     * unless one as new came meanwhile; at most [AutoFetches.MAX_PER_HOUR] a person's an hour. Not
     * waited for: the group shows it once it is there; one that does not come leaves the photo as it was.
     */
    private fun fetchGroupPhoto(id: String, file: String, version: Long, by: String) {
        val key = "$id@$version"
        if (file.isEmpty() || key in groupPhotoFetches) return
        if (!autoFetches.take(by, System.currentTimeMillis())) {
            refused("a group photo", id, "past what one person's changes may fetch in an hour")
            return
        }
        groupPhotoFetches += key
        scope.launch {
            var picture: File? = null
            try {
                val bytes = fetchFile(file, GroupPhotos.MAX_DOWNLOAD_BYTES, GROUP_PHOTO_FETCH_MS)
                if (bytes != null) {
                    val out = GroupPhotos.incomingFile(app)
                    picture = out.takeIf { GroupPhotoImages.write(bytes, it) } ?: run {
                        out.delete()
                        Log.w(TAG, "a group photo that is no picture that can be shown here")
                        null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "a group photo was not made (${e::class.java.simpleName})")
                picture?.delete()
                picture = null
            }
            synchronized(this@DirectLink) {
                groupPhotoFetches -= key
                val made = picture ?: return@synchronized
                if (repo.conversation(id)?.isGroup != true) {
                    made.delete()
                    return@synchronized
                }
                if (GroupPhotos(app).apply(id, made, version) == GroupPhotos.Applied.APPLIED) {
                    IMessageLog.add(app, "${IMessageLog.chatRef(id)}: group photo set (through the iMessage library)")
                    com.lightdecoder.archive.ArchiveEvents.notify(
                        com.lightdecoder.archive.ArchiveChange(id, com.lightdecoder.archive.ArchiveChange.Kind.CONVERSATIONS, ChatService.IMESSAGE),
                    )
                }
            }
        }
    }

    // --- polls (iOS 26) ----------------------------------------------------------------------------

    /** Whether the user can vote in (or add a choice to) the poll card [row] through the library: a poll Apple knows by its GUID, in a chat it reaches. */
    fun canVote(row: MessageRow, reachable: Boolean? = null): Boolean =
        active && !row.isLocal && row.direction != "system" && DirectArchive.isImessage(row.id) &&
            IMessageExtra.parse(row.extra).app?.let { Polls.isPoll(it) && Polls.decode(it.url) != null } == true &&
            (reachable ?: canSend(row.conversationId))

    /** The poll card [messageId], its poll and its card's balloon; throws why it cannot be voted in. */
    private fun pollFor(messageId: String): Triple<MessageRow, Polls.Poll, IMessageExtra.App> {
        val row = repo.message(messageId) ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_not_stored))
        val card = IMessageExtra.parse(row.extra).app?.takeIf(Polls::isPoll)
        val poll = card?.let { Polls.decode(it.url) }
        if (card == null || poll == null || row.isLocal || DirectArchive.guidOf(row.id) == null) throw DirectEngine.ImessageError(app.getString(R.string.imessage_poll_unreadable))
        return Triple(row, poll, card)
    }

    /**
     * The user's vote on choice [optionId] of the poll card [messageId]: chosen, or taken back when it
     * was (several can be chosen, as on an iPhone). Shown at once; sent as OpenBubbles sends one (an
     * update of the session that is no card of its own, to the poll's latest card, naming all the
     * user's choices); put back as it was if Apple does not take it. Throws
     * [DirectEngine.ImessageError] saying why when it does not go through.
     */
    suspend fun votePoll(messageId: String, optionId: String) {
        val (row, poll, _) = pollFor(messageId)
        if (poll.options.none { it.id == optionId }) throw DirectEngine.ImessageError(app.getString(R.string.imessage_poll_unreadable))
        val target = targetOf(row.conversationId) ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_unreachable))
        val now = System.currentTimeMillis()
        // Shown at once, under the lock every archive write here takes; what it was, to put it back.
        val (before, chosen, cardGuid) = synchronized(this) {
            val current = repo.message(messageId) ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_not_stored))
            val extra = IMessageExtra.parse(current.extra)
            val was = Polls.choicesOf(extra.pollVotes, Polls.ME)
            val next = Polls.toggle(was, optionId).filter { id -> poll.options.any { it.id == id } }
            val votes = Polls.withBallot(extra.pollVotes, Polls.Ballot(Polls.ME, next, now))
            repo.editText(current.id, current.text, current.editedAtMs, extra.copy(pollVotes = votes).toJson(), snippetOf(current.text, current.extra))
            Triple(was, next, extra.pollCards.lastOrNull() ?: DirectArchive.guidOf(current.id)!!)
        }
        withContext(NonCancellable) {
            try {
                val vote = Polls.vote(chosen, Handles.display(target.from), cardGuid)
                failOn(requests.call(DirectWire.EXTENSION, DirectWire.extension(target, cardGuid, true, vote, null), SEND_TIMEOUT_MS))
            } catch (e: Exception) {
                synchronized(this@DirectLink) {
                    // Put back only while it is still this one: a vote chosen since (here or on another device) stays.
                    val current = repo.message(messageId) ?: return@synchronized
                    val extra = IMessageExtra.parse(current.extra)
                    val mine = extra.pollVotes.firstOrNull { it.voter == Polls.ME }
                    if (mine != null && mine.atMs == now && mine.options == chosen) {
                        val votes = Polls.withBallot(extra.pollVotes, Polls.Ballot(Polls.ME, before, now))
                        repo.editText(current.id, current.text, current.editedAtMs, extra.copy(pollVotes = votes).toJson(), snippetOf(current.text, current.extra))
                    }
                }
                throw e
            }
        }
        IMessageLog.add(app, "${IMessageLog.chatRef(row.conversationId)}: a vote in a poll sent through the iMessage library (Apple confirmed)")
    }

    /**
     * Adds the choice [text] to the poll card [messageId] ("Add Choice"), as OpenBubbles does: the
     * poll's card sent again to its session with the choice added and its count one higher. Once
     * Apple confirmed it, the card here shows it too. Throws [DirectEngine.ImessageError] saying why
     * when it does not go through (no room, already there, not sent).
     */
    suspend fun addPollChoice(messageId: String, text: String) {
        val (row, poll, card) = pollFor(messageId)
        val first = targetOf(row.conversationId) ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_unreachable))
        val next = Polls.addChoice(poll, text, Handles.display(first.from))
            ?: throw DirectEngine.ImessageError(
                if (poll.options.size >= Polls.MAX_OPTIONS) app.getString(R.string.imessage_poll_too_many, Polls.MAX_OPTIONS) else app.getString(R.string.imessage_poll_choice_not_added),
            )
        val root = card.root.ifEmpty { row.id }
        val rootGuid = DirectArchive.guidOf(root) ?: DirectArchive.guidOf(row.id)!!
        // Its session as it came (Apple's), else the poll's first card's GUID, as OpenBubbles sends one; a UUID either way (the library takes no other).
        val session = card.session.takeIf(Polls::isUuid) ?: rootGuid.takeIf(Polls::isUuid) ?: UUID.randomUUID().toString().uppercase()
        val balloon = Polls.card(next, session, root)
        inTurnAwait(row.conversationId) {
            val target = targetOf(row.conversationId) ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_unreachable))
            val reply = requests.call(DirectWire.EXTENSION, DirectWire.extension(target, rootGuid, false, balloon, null), SEND_TIMEOUT_MS)
            failOn(reply)
            val sent = DirectWire.SendReply.fromJson(reply)
            synchronized(this) {
                val current = repo.message(messageId) ?: return@synchronized
                val extra = IMessageExtra.parse(current.extra)
                // A newer state came meanwhile (someone else's choice): it stays, and the card gets this one when it comes back.
                val shown = extra.app?.takeIf(Polls::isPoll)
                if (shown != null && Polls.countOf(shown.url) >= next.count) return@synchronized
                val updated = extra.copy(app = balloon, pollCards = (extra.pollCards + sent.guid).distinct().takeLast(IMessageExtra.MAX_POLL_CARDS))
                repo.editText(current.id, current.text, current.editedAtMs, updated.toJson(), snippetOf(current.text, current.extra))
            }
            IMessageLog.add(app, "${IMessageLog.chatRef(row.conversationId)}: a choice added to a poll through the iMessage library (Apple confirmed)")
        }
    }

    /**
     * Starts a poll in [conversationId] (iOS 26): [question] (may be empty) and its [choices] (two at
     * least), sent as an iMessage app's new card, as OpenBubbles sends one. As a text: a local copy
     * now ("tmp:<tmpId>"), the sent card once Apple confirms it. Throws [DirectEngine.ImessageError]
     * when it cannot be made.
     */
    fun sendPoll(conversationId: String, question: String, choices: List<String>): String {
        val target = targetOf(conversationId) ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_unreachable))
        val poll = Polls.create(question, choices, Handles.display(target.from)) ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_poll_needs_two))
        val card = Polls.card(poll, UUID.randomUUID().toString().uppercase())
        return sendAppCard(conversationId, card, null, DirectTexts(app).poll(poll.title))
    }

    // --- a chat's own "Send Read Receipts" ----------------------------------------------------------

    /** Whether the other person of [conversationId] is told when the user read it: the chat's own choice, else the iMessage screen's setting. */
    fun sendsReadReceipts(conversationId: String): Boolean = receiptChoices.effective(conversationId, DirectPrefs.sendReadReceipts(app))

    /** The chat's own choice ([sendsReadReceipts]); null to follow the iMessage screen's setting again. */
    fun setSendsReadReceipts(conversationId: String, on: Boolean?) {
        receiptChoices.set(conversationId, on)
    }

    /**
     * The user marked a chat unread on another of their devices (only ever taken from the user's own
     * verified devices: [DirectArchive.map]): it is unread here too, as an iPhone shows it, with no
     * notification. One for a message not stored yet waits for it ([hold]).
     */
    private fun markedUnreadElsewhere(update: DirectArchive.Update.MarkUnread) {
        val row = repo.message(update.messageId) ?: return hold(update.messageId, update)
        if (!repo.markConversationUnread(row.conversationId)) return
        IMessageLog.add(app, "${IMessageLog.chatRef(row.conversationId)}: marked unread on another of the user's devices")
    }

    /**
     * "Mark as Unread" ([conversationId], from the list or the chat's menu): unread here until it is
     * read again, and, through the library, the user's other devices are told, as an iPhone tells
     * them (Apple's MarkUnread naming the newest message someone else sent in it; none in a chat
     * nobody else wrote in). Does not throw: a notice that does not go is only logged.
     */
    suspend fun markUnread(conversationId: String) {
        runCatching { repo.markConversationUnread(conversationId) }
        if (!active) return
        val (guid, target) = try {
            val guid = repo.latestIncomingMessageId(conversationId, DirectArchive.PREFIX)?.let(DirectArchive::guidOf) ?: return
            guid to (targetOf(conversationId) ?: return)
        } catch (e: RuntimeException) {
            Log.w(TAG, "unread mark not sent (${e::class.java.simpleName}): ${com.lightdecoder.imessage.Redact.text(e.message.orEmpty())}")
            return
        }
        // Read again later, the read receipt for it goes again.
        readSent.release(guid)
        try {
            withContext(NonCancellable) {
                val error = DirectWire.errorOf(requests.call(DirectWire.MARK_UNREAD, DirectWire.markUnread(target, guid), NOTICE_TIMEOUT_MS))
                if (error == null) {
                    IMessageLog.add(app, "${IMessageLog.chatRef(conversationId)}: marked unread, the user's other devices told through the iMessage library")
                } else {
                    Log.i(TAG, "unread mark not sent: ${com.lightdecoder.imessage.Redact.text(error)}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "unread mark not sent (${e::class.java.simpleName}): ${com.lightdecoder.imessage.Redact.text(e.message.orEmpty())}")
        }
    }

    // --- the user's devices changed ---------------------------------------------------------------

    /** When the people of the recent chats were last told the user's devices changed ([devicesChanged]). */
    private val peerNotices = PeerNotices(app)

    /**
     * The user's devices changed (Apple's library said so of the user's own account): the people of
     * the chats with something in them in the last [PeerInvalidation.RECENT_MS] are told, so that
     * their phones look the user's devices up again (as OpenBubbles' `invalidatePeerCaches` does), at
     * most once in [PeerInvalidation.EVERY_MS]. Only chats the user wrote in, or with someone among the
     * user's contacts ([knownPeople]): nobody unknown who merely wrote to the user hears from them
     * (that this number is in use, and that its owner just got a new device). Asked of the ":imessage"
     * process without waiting here.
     */
    private fun devicesChanged() {
        val now = System.currentTimeMillis()
        if (!peerNotices.due(now)) {
            IMessageLog.add(app, "the user's devices changed again: the people of the recent chats were told less than 10 minutes ago")
            return
        }
        val recent = runCatching { repo.conversations(null) }.getOrDefault(emptyList())
            .filter { it.folderEnum != Folder.DELETED && PeerInvalidation.recent(it.lastMessageTs, now) }
            .filter { runCatching { repo.hasOutgoing(it.id) || knownPeople(it.id) }.getOrDefault(false) }
            .mapNotNull { runCatching { targetOf(it.id) }.getOrNull() }
        val groups = PeerInvalidation.groups(recent)
        if (groups.isEmpty()) return
        peerNotices.told(now)
        val people = groups.sumOf { g -> g.participants.count { it != g.from } }
        scope.launch {
            try {
                val error = DirectWire.errorOf(requests.call(DirectWire.PEERS, DirectWire.peers(groups), NOTICE_TIMEOUT_MS))
                IMessageLog.add(
                    app,
                    if (error == null) "the user's devices changed: $people people of ${recent.size} recent chat(s) told through the iMessage library"
                    else "the user's devices changed: the people of the recent chats were not told (${loggableText(com.lightdecoder.imessage.Redact.text(error), 120)})",
                )
            } catch (e: Exception) {
                Log.w(TAG, "the people of the recent chats were not told (${e::class.java.simpleName}): ${com.lightdecoder.imessage.Redact.text(e.message.orEmpty())}")
            }
        }
    }

    /** Whether someone of [conversationId] (besides the user) is among the user's contacts: has a name of their own ([knownName]). */
    private fun knownPeople(conversationId: String): Boolean = repo.participants(conversationId).filter { !it.isMe }.any { p ->
        val handle = Handles.normalize(p.number.ifBlank { p.formattedNumber }.ifBlank { p.id })
        handle != null && knownName(p, handle)
    }

    // --- "Report Junk" ---------------------------------------------------------------------------

    /**
     * Whether "Report Junk" is offered for [conversationId], as an iPhone offers it: a chat with one
     * person the library reaches, who is not among the user's contacts (no contact's name for their
     * address: a picture alone, as Google Messages may have one of anyone who wrote, makes nobody
     * known), and in which the user never wrote, with something of theirs to report
     * ([JunkReports.offered]). Reads the archives: not on the main thread.
     */
    fun junkReportable(conversationId: String): Boolean {
        if (!active || DirectArchive.isGroup(conversationId)) return false
        return try {
            val row = repo.conversation(conversationId) ?: return false
            val others = repo.participants(conversationId).filter { !it.isMe }
            val other = others.singleOrNull()
            val address = other?.let { it.number.ifBlank { it.formattedNumber }.ifBlank { it.id } }.orEmpty()
            val handle = Handles.normalize(address)
            val known = other != null && handle != null && knownName(other, handle)
            val incoming = repo.latestIncomingMessageId(conversationId, DirectArchive.PREFIX) != null
            JunkReports.offered(
                isGroup = row.isGroup, readOnly = row.readOnly, deleted = row.folderEnum == Folder.DELETED, others = others.size,
                knownContact = known, wroteIn = repo.hasOutgoing(conversationId), somethingToReport = incoming,
            ) && targetOf(conversationId) != null
        } catch (e: RuntimeException) {
            false
        }
    }

    /** Whether [p] (whose address is [handle]) has a name of their own: a contact's, or one stored with them that is not their address. */
    private fun knownName(p: com.lightdecoder.archive.ParticipantRow, handle: String): Boolean {
        val address = Handles.display(handle)
        val stored = p.name.trim()
        if (stored.isNotEmpty() && !IMessageAddress.same(stored, address) && stored != p.number && stored != p.formattedNumber && stored != address) return true
        return nameOf(handle) != address
    }

    /**
     * "Report Junk" ([junkReportable]): the last [DirectWire.MAX_JUNK_MESSAGES] messages the person
     * sent in [conversationId] are reported to Apple as junk, as OpenBubbles' `markAsSpam` reports
     * them, then the chat is deleted here. Throws [DirectEngine.ImessageError] saying why when Apple
     * did not take the report (the chat then stays).
     */
    suspend fun reportJunk(conversationId: String) {
        if (!junkReportable(conversationId)) throw DirectEngine.ImessageError(app.getString(R.string.imessage_junk_not_offered))
        val target = targetOf(conversationId) ?: throw DirectEngine.ImessageError(app.getString(R.string.imessage_direct_unreachable))
        val rows = JunkReports.pick(repo.messages(conversationId, limit = JUNK_LOOKBACK))
        val messages = rows.mapNotNull { JunkReports.message(it, target) }
        if (messages.isEmpty()) throw DirectEngine.ImessageError(app.getString(R.string.imessage_junk_nothing))
        withContext(NonCancellable) {
            failOn(requests.call(DirectWire.REPORT_JUNK, DirectWire.reportJunk(target, messages), SEND_TIMEOUT_MS))
            IMessageLog.add(app, "${IMessageLog.chatRef(conversationId)}: ${messages.size} message(s) reported to Apple as junk through the iMessage library; the chat deleted here")
            manager.deleteConversation(conversationId)
        }
    }

    // --- a failed iMessage sent again -----------------------------------------------------------

    /**
     * Whether [row] is one of the user's iMessages Apple took and then could not deliver (it said so
     * afterwards): not a local copy, which [retry] sends again, but the sent message itself.
     */
    fun undelivered(row: MessageRow): Boolean =
        row.isOutgoing && row.direction == "out" && !row.isLocal && DirectArchive.isImessage(row.id) && DirectArchive.statusRank(row.status) < 0

    /**
     * "Try Again" on [messageId], one of the user's iMessages Apple could not deliver ([undelivered]):
     * it is sent again, as a new message with its text, its file when that is still here, its effect
     * and formatting, and the failed one goes. Returns the new local copy's id, or null when it could
     * not go (gone, not the user's, not reachable).
     */
    fun sendAgain(messageId: String): String? {
        val row = repo.message(messageId)?.takeIf(::undelivered) ?: return null
        if (!canSend(row.conversationId)) return null
        val extra = IMessageExtra.parse(row.extra)
        val file = row.media.firstNotNullOfOrNull { m -> existing(m.localPath)?.let { it to m } }
        val reply = row.replyToId.ifEmpty { null }
        val tmpId = if (file != null) {
            try {
                sendMedia(row.conversationId, Uri.fromFile(file.first), row.text.ifEmpty { null }, reply, extra.voice, extra.effect, extra.textRuns(row.text))
            } catch (e: DirectEngine.ImessageError) {
                Log.w(TAG, "failed message not sent again: ${com.lightdecoder.imessage.Redact.text(e.message.orEmpty())}")
                return null
            }
        } else {
            if (row.text.isBlank()) return null
            sendText(row.conversationId, row.text, reply, extra.effect, extra.textRuns(row.text), mentions = extra.textMentions(row.text))
        }
        synchronized(this) {
            repo.message(messageId)?.let(::forgetPictures)
            repo.applyDeletion(messageId)
        }
        IMessageLog.add(app, "${IMessageLog.chatRef(row.conversationId)}: a message Apple could not deliver was sent again")
        return tmpId
    }

    /** The user read [update]'s message on another device: the chat is read here too, unless something newer came since. */
    private fun readElsewhere(update: DirectArchive.Update.ReadElsewhere) {
        val row = repo.message(update.messageId) ?: return hold(update.messageId, update)
        val newest = repo.latestIncomingMessageId(row.conversationId, DirectArchive.PREFIX)?.let(repo::message)
        if (newest != null && newest.timestampMs > row.timestampMs) return
        repo.markConversationRead(row.conversationId)
        MessageNotifier.cancel(app, row.conversationId, ChatService.IMESSAGE)
    }

    /** A message's formatting kept with it: [runs] (over its text, part 0) by part. */
    private fun runsOf(runs: List<TextRun>): Map<Int, List<TextRun>> = if (runs.isEmpty()) emptyMap() else mapOf(0 to runs)

    /** A message's mentions kept with it: [mentions] (over its text, part 0) by part. */
    private fun mentionsOf(mentions: List<Mention>): Map<Int, List<Mention>> = if (mentions.isEmpty()) emptyMap() else mapOf(0 to mentions)

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

    /**
     * A display name for an address: the Messages archive's contact with that number (the whole
     * number, [IMessageAddress.same]: never someone whose number only ends the same), else the address.
     */
    private fun nameOf(handle: String): String {
        val address = Handles.display(handle)
        if (handle.startsWith("mailto:") || '@' in address) return address
        val digits = address.filter { it.isDigit() }.takeLast(10)
        if (digits.length < 7) return address
        return try {
            RelayManager.get(app).repo.contacts(digits).firstOrNull { c ->
                IMessageAddress.same(c.phoneNumber, address) && (c.customAlias.isNotBlank() || c.displayName.isNotBlank())
            }?.let { it.customAlias.ifBlank { it.displayName } } ?: address
        } catch (e: RuntimeException) {
            address
        }
    }

    /** What the iMessage apps' host ([com.lightdecoder.imessage.madrid.MadridHost]) hears of the archive's iMessages. */
    interface AppEvents {
        /** [message], just stored in [conversationId], is an update (a move) in an iMessage app's session. Returns at once. */
        fun appUpdateArrived(conversationId: String, message: MessageDto)

        /** Whether [conversationId]'s notifications are to stay quiet now (a game is open). */
        fun silences(conversationId: String): Boolean
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

        /** The iMessage apps' host, once there is one. */
        @Volatile
        var appEvents: AppEvents? = null

        /**
         * A message Apple sent again, over the stored copy: what happened to it since (reactions,
         * edits, parts unsent, later receipts, what a download learned of its files) is kept.
         */
        internal fun merged(fresh: MessageDto, stored: MessageRow): MessageDto {
            val further = DirectArchive.statusRank(stored.status) > DirectArchive.statusRank(fresh.status)
            val unsent = IMessageExtra.parse(stored.extra).unsent
            val changed = stored.editedAtMs > 0 || unsent.isNotEmpty()
            val gone = unsent.map(DirectArchive::partId).toSet()
            // A downloaded file's real type (a voice message made playable), size and dimensions.
            val known = stored.media.associateBy { it.partId }
            val parts = fresh.parts.map { p ->
                val m = known[p.partId]?.takeIf { p.isMedia && it.mediaId == p.mediaId } ?: return@map p
                p.copy(
                    mime = m.mime.ifEmpty { p.mime },
                    size = m.size.takeIf { it > 0 } ?: p.size,
                    width = m.width.takeIf { it > 0 } ?: p.width,
                    height = m.height.takeIf { it > 0 } ?: p.height,
                )
            }
            return fresh.copy(
                reactions = stored.reactions,
                status = if (further) stored.status else fresh.status,
                statusCode = if (further) stored.statusCode else fresh.statusCode,
                text = if (changed) stored.text else fresh.text,
                parts = if (changed) {
                    listOfNotNull(stored.text.takeIf { it.isNotEmpty() }?.let { PartDto(partId = "0", kind = "text", text = it) }) +
                        parts.filter { it.isMedia && it.partId !in gone }
                } else {
                    parts
                },
                editedAtMs = if (changed) stored.editedAtMs else fresh.editedAtMs,
                // What was gathered on it since it first came stays: a poll's votes and newer state, the stickers on it.
                extra = if (changed) stored.extra else IMessageExtra.parse(fresh.extra).keepingGathered(IMessageExtra.parse(stored.extra)).toJson(),
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
            // Waiting for its Send Later time: it is taken back instead.
            if (SendLater.pending(row, System.currentTimeMillis())) return false
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
            if (SendLater.pending(row, System.currentTimeMillis())) return false
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
            DirectArchive.isImessage(row.id) && !row.isLocal && row.direction != "system" && !SendLater.pending(row, System.currentTimeMillis())

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

        /** The longest GUID taken for a sticker's message (Apple's are UUIDs). */
        private const val MAX_GUID_CHARS = 64

        /** How many of a chat's newest messages are looked through for the ones a junk report carries. */
        private const val JUNK_LOOKBACK = 50

        /** A link preview's fetch is given this long past its own budget before the message goes without it. */
        private const val LINK_PREVIEW_SLACK_MS = 2_000L

        /** The file a picture kept with an iMessage under [key] is in ([IMessageExtra.linkImageKey], a sticker's): whether there or not. */
        fun pictureFile(context: Context, key: String): File =
            MediaCache.fileFor(context, key, IMessageExtra.extensionOf(key), ChatService.IMESSAGE)

        /** A send with a file also waits for the file to go up to Apple (up to 100 MB). */
        private const val MEDIA_SEND_TIMEOUT_MS = 10 * 60_000L

        /** The largest picture taken as a chat's background or a group's photo (it is read whole to be made smaller). */
        private const val MAX_BACKGROUND_SOURCE_BYTES = 40L * 1024 * 1024

        /**
         * As on an iPhone, someone can be removed from a group, and the user leave it, only while it
         * has this many others at least (so that it stays a group). Apple's rule as iPhones show it:
         * not verified against Apple's servers.
         */
        const val MIN_OTHERS_TO_LEAVE = 3

        /** The longest group name sent. */
        private const val MAX_GROUP_NAME = 255

        /**
         * The size a design ([BackgroundThemes]) is drawn at for a screen of [width] x [height]:
         * portrait, the screen's own size, its longest side at most [BackgroundPosters.MAX_SIDE].
         */
        fun themeSize(width: Int, height: Int): Pair<Int, Int> {
            var w = minOf(width, height).coerceAtLeast(360)
            var h = maxOf(width, height).coerceAtLeast(640)
            if (h > BackgroundPosters.MAX_SIDE) {
                w = w * BackgroundPosters.MAX_SIDE / h
                h = BackgroundPosters.MAX_SIDE
            }
            return w to h
        }

        /** A download from Apple may take this long. */
        private const val DOWNLOAD_TIMEOUT_MS = 5 * 60_000L

        /** A sticker's picture fetched without anyone asking may take this long ([fetchSticker]). */
        const val STICKER_FETCH_MS = 20_000L

        /** A group's photo fetched without anyone asking may take this long ([fetchGroupPhoto]). */
        const val GROUP_PHOTO_FETCH_MS = 60_000L

        /** What the ":imessage" process is given past a fetch's own time to answer that it did not come. */
        private const val FETCH_SLACK_MS = 5_000L

        /** A voice message larger than this is not made playable here (it is opened in another app). */
        private const val MAX_REMUX_BYTES = 32L * 1024 * 1024

        /** Added to a voice message's media id for the file it is played from. */
        private const val PLAYABLE = "#playable"

        /** Added to a video's media id for the file of its still ([keepStill]). */
        private const val STILL = "#still"

        /** Added to a message's id for the row saying that its sender added people to the group ([noteAdded]). */
        private const val ADDED = "~added"

        /** The name an iPhone gives a voice message's file (before its extension). */
        private const val VOICE_NAME = "Audio Message"

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
 * How many pictures each person's changes had fetched from Apple without anyone asking (a sticker put
 * on a message, a group's photo: [DirectLink]) in the last [windowMs]: at most [maxPerWindow] each,
 * so nobody in a chat can make this phone fetch on and on. By the person's address ([IMessageAddress.key]),
 * the user's own as "me"; at most [maxPeople] people remembered (the one longest quiet forgotten
 * first). In memory: a restart starts again.
 */
internal class AutoFetches(
    private val maxPerWindow: Int = MAX_PER_HOUR,
    private val windowMs: Long = 60 * 60 * 1000L,
    private val maxPeople: Int = 500,
) {
    private val times = LinkedHashMap<String, ArrayDeque<Long>>(16, 0.75f, true)

    /** Whether [who]'s change may fetch one more at [nowMs]; counted when it may. */
    @Synchronized
    fun take(who: String, nowMs: Long): Boolean {
        val key = IMessageAddress.key(who) ?: who
        val list = times.getOrPut(key) { ArrayDeque() }
        // Older than the window, or from a clock that went back since.
        while (list.isNotEmpty() && (nowMs - list.first() >= windowMs || list.first() > nowMs)) list.removeFirst()
        if (list.size >= maxPerWindow) return false
        list.addLast(nowMs)
        if (times.size > maxPeople) times.keys.iterator().let { it.next(); it.remove() }
        return true
    }

    companion object {
        /** A person's changes fetch at most this many pictures an hour. */
        const val MAX_PER_HOUR = 60
    }
}

/**
 * Changes for messages that are not stored yet, by message id, for up to [windowMs] (then they
 * are forgotten): Apple's receipt for a message sent from here can come before the send's own
 * answer, and a reaction or edit before its message. At most [maxMessages] messages are held, and
 * [maxPerMessage] changes for each (the latest kept). Each is held as it came, who sent it and
 * all, and checked as any other once its message is stored ([DirectLink.apply]).
 */
internal class HeldUpdates(
    private val windowMs: Long = 2 * 60 * 1000L,
    private val maxMessages: Int = 200,
    private val maxPerMessage: Int = 50,
) {

    private val held = LinkedHashMap<String, MutableList<Pair<Long, DirectArchive.Update>>>()

    @Synchronized
    fun hold(messageId: String, update: DirectArchive.Update, nowMs: Long) {
        evict(nowMs)
        val list = held.getOrPut(messageId) { ArrayList() }
        list.add(nowMs to update)
        while (list.size > maxPerMessage) list.removeAt(0)
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

/**
 * Each iMessage group's own name, as Apple has it, by conversation id: "" for a group its members
 * did not name, which the archive names after its members, for showing. What is sent to a group
 * carries this one: the iPhones in it would take its members' names for the group's name. Main
 * process only.
 */
internal class GroupNames(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** [conversationId]'s own name ("" for none), or null when not known (a group stored before these were kept). */
    fun of(conversationId: String): String? = prefs.getString(conversationId, null)

    /**
     * Notes that [conversationId] was called [name] as of [atMs], unless the name known is newer
     * (a message from before a rename, come late). True when [name] is now its name.
     */
    @Synchronized
    fun note(conversationId: String, name: String, atMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        val known = prefs.getString(conversationId, null)
        // A name dated in the future (kept before event times were held to now) holds no later one back.
        val knownAt = prefs.getLong(conversationId + AT, Long.MIN_VALUE).takeIf { it <= nowMs + DirectArchive.MAX_FUTURE_MS } ?: Long.MIN_VALUE
        if (known != null && atMs < knownAt) return false
        if (known != name || atMs != knownAt) prefs.edit().putString(conversationId, name).putLong(conversationId + AT, atMs).apply()
        return true
    }

    private companion object {
        const val FILE = "imessage_group_names"
        const val AT = "@at"
    }
}
