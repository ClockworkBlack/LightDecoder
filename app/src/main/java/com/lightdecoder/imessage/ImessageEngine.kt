package com.lightdecoder.imessage

import android.content.Context
import android.util.Log
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.PartDto
import com.lightdecoder.relay.RelayManager
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.EventPipeline
import uniffi.rust_bridge.IndexedMessagePart
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MessagePart
import uniffi.rust_bridge.MessageReceiver
import uniffi.rust_bridge.MessageType
import uniffi.rust_bridge.NormalMessage
import uniffi.rust_bridge.PushChatMessage
import uniffi.rust_bridge.PushEvent
import uniffi.rust_bridge.RegisterState
import uniffi.rust_bridge.RustBridgeException
import uniffi.rust_bridge.SharedPushStateHandle
import uniffi.rust_bridge.TextFlags
import uniffi.rust_bridge.TextFormat
import uniffi.rust_bridge.UnsendMessage

/**
 * iMessage through OpenBubbles' library, part of the Messages tab: its conversations and messages
 * are stored in the Messages archive ([ImessageArchive] ids), shown in the same list and threads,
 * and [RelayManager] hands every action on an iMessage conversation to this class.
 *
 * - [register]: relay code -> device identity -> Apple push connection -> this phone's number,
 *   verified by SMS -> iMessage registration -> live session. The status is green
 *   ([Status.Registered]) only once Apple reports the registration as done.
 * - While registered, [ImessageService] keeps the session up, so messages arrive (from other
 *   people, and copies of what the user sent from their other devices, OpenBubbles included)
 *   whether a LightDecoder screen is open or not.
 * - [sendText] works like the Messages tab's own sends: a local copy at once, replaced by the sent
 *   message (id `imsg:<GUID>`) once Apple confirms it, or marked failed with the reason.
 * - [unsend] retracts a message within Apple's 2-minute window. Who it went to is read from the
 *   archive, so it works after LightDecoder restarted too.
 *
 * Only loaded once the user imported OpenBubbles' files ([ImessageLibrary]).
 */
class ImessageEngine private constructor(private val app: Context) {

    sealed class Status {
        /** The OpenBubbles files have not been imported yet. */
        data class LibraryMissing(val names: List<String>) : Status()
        object NotRegistered : Status()
        data class Working(val step: String) : Status()
        data class Registered(val handles: List<String>) : Status()
        data class Failed(val error: String) : Status()
    }

    /** A failure the screens show as it is. */
    class ImessageError(message: String) : Exception(message)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val _status = MutableStateFlow<Status>(Status.NotRegistered)
    val status: StateFlow<Status> = _status

    val statePath: String = File(app.filesDir, "imessage/state").apply { mkdirs() }.path

    private val repo: ArchiveRepository get() = RelayManager.get(app).repo

    private var started = false
    private var pipeline: EventPipeline? = null
    private var session: SharedPushStateHandle? = null

    /** The user's own iMessage addresses (filled once registered). */
    @Volatile
    private var myHandles: List<String> = emptyList()

    /** Waiting for Apple's answer to a send, by message id. */
    private val confirms = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    val isRegistered: Boolean get() = _status.value is Status.Registered

    /** Loads the library and restores a previous registration, if any. Safe to call again. */
    fun start() {
        scope.launch {
            lock.withLock {
                val missing = ImessageLibrary.missing(app)
                if (missing.isNotEmpty()) {
                    _status.value = Status.LibraryMissing(missing)
                    return@withLock
                }
                try {
                    initLibrary()
                    if (session == null) {
                        _status.value = Status.Working("Starting")
                        session = uniffi.rust_bridge.restoreSession(statePath, pipeline!!)
                    }
                    refreshStatus()
                } catch (e: Throwable) {
                    fail(e)
                }
            }
        }
    }

    private fun initLibrary() {
        if (started) return
        ImessageLibrary.load(app)
        uniffi.rust_bridge.doFirstTimeInit(statePath)
        uniffi.rust_bridge.setupKeystore(statePath, ImessageKeystore(app))
        pipeline = EventPipeline(File(statePath, "journal").path).also { it.setReceiver(receiver) }
        started = true
        failInterruptedSends()
    }

    /**
     * Local copies of iMessages a previous run was still sending: their answer from Apple was lost
     * with that run, so they wait as failed for the user (Retry may send them twice).
     */
    private fun failInterruptedSends() {
        try {
            repo.recentOutgoing(limit = 500)
                .filter { it.isLocal && ImessageArchive.isImessage(it.conversationId) && it.localState != LocalState.FAILED }
                .forEach { repo.setLocalState(it.tmpId, LocalState.FAILED, app.getString(com.lightdecoder.R.string.imessage_interrupted)) }
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not check interrupted sends", e)
        }
    }

    private suspend fun refreshStatus() {
        val s = session
        if (s == null) {
            _status.value = Status.NotRegistered
            ImessagePrefs.setRegistered(app, false)
            return
        }
        _status.value = when (val reg = uniffi.rust_bridge.getRegState(s)) {
            is RegisterState.Registered -> {
                myHandles = uniffi.rust_bridge.getHandles(s)
                ImessagePrefs.setRegistered(app, true)
                Status.Registered(myHandles)
            }
            is RegisterState.Registering -> Status.Working("Registering with Apple")
            is RegisterState.Failed -> Status.Failed(reg.error)
        }
    }

    /**
     * Registers this phone's number with iMessage, using the iPhone relay that [relayCode] names.
     * [phone] does the SMS part. Progress shows in [status]; the result is [Status.Registered] or
     * [Status.Failed]. Once registered, the background service is started.
     */
    fun register(relayCode: String, relayHost: String, phone: PhoneVerifier) {
        scope.launch {
            lock.withLock {
                try {
                    initLibrary()
                    doRegister(relayCode.trim(), relayHost.trim().trimEnd('/'), phone)
                } catch (e: Throwable) {
                    fail(e)
                }
            }
            if (isRegistered) ImessageService.start(app)
        }
    }

    private suspend fun doRegister(code: String, host: String, phone: PhoneVerifier) {
        if (code.isEmpty()) throw ImessageError("Enter the relay code from the iPhone's relay app.")
        fun step(s: String) {
            _status.value = Status.Working(s)
        }
        step("Contacting the relay")
        val token = if (host == DEFAULT_RELAY_HOST) DEFAULT_RELAY_TOKEN else null
        val config = uniffi.rust_bridge.configFromRelay(code, host, token)
        step("Creating this device's identity")
        val identity = uniffi.rust_bridge.newNgmIdentity()
        uniffi.rust_bridge.setIdentity(statePath, config, identity)
        step("Connecting to Apple")
        val push = uniffi.rust_bridge.setupPush(config, identity, null, statePath)
        push.error?.let { Log.w(TAG, "push setup reported: $it") }
        val conn = push.connection
        val anisette = uniffi.rust_bridge.makeAnisette(statePath, config, conn)

        step("Verifying this phone's number by SMS")
        val reply = phone.verify(uniffi.rust_bridge.getToken(conn))
        val user = uniffi.rust_bridge.authPhone(conn, config, reply.number, reply.signature)

        step("Registering with iMessage")
        val users = listOf(user)
        val result = uniffi.rust_bridge.registerIds(statePath, config, conn, identity, users.map { uniffi.rust_bridge.duplicateUser(it) })
        result.alert?.let { throw ImessageError("Apple: ${it.title}. ${it.body}".trim()) }
        step("Starting iMessage")
        session = uniffi.rust_bridge.startSession(
            statePath, config, conn, anisette, null, result.users ?: users, identity, null, pipeline!!,
        )
        refreshStatus()
    }

    // --- conversations ----------------------------------------------------------------------

    /**
     * The iMessage conversation with [recipient] (a phone number or email), if that address is on
     * iMessage: made in the archive if it is new. Null when iMessage is not registered or the
     * address cannot get iMessages (the caller then uses SMS).
     */
    suspend fun conversationFor(recipient: String): String? {
        val s = session ?: return null
        if (!isRegistered) return null
        val to = Handles.normalize(recipient) ?: return null
        val from = Handles.preferredSender(myHandles) ?: return null
        val reachable = try {
            uniffi.rust_bridge.validateTargets(s, listOf(to), from)
        } catch (e: RustBridgeException) {
            return null
        }
        if (to !in reachable) return null
        val id = ImessageArchive.conversationId(listOf(to, from), myHandles.toSet(), null)
        if (repo.conversation(id) == null) {
            repo.upsertConversation(
                ConversationDto(
                    id = id,
                    name = nameOf(to),
                    type = ImessageArchive.TRANSPORT,
                    defaultOutgoingId = from,
                    lastMessageTs = System.currentTimeMillis(),
                    participants = listOf(
                        ParticipantDto(id = to, name = nameOf(to), number = Handles.display(to), formattedNumber = Handles.display(to)),
                        ParticipantDto(id = from, number = Handles.display(from), formattedNumber = Handles.display(from), isMe = true),
                    ),
                    otherParticipantIds = listOf(to),
                ),
            )
        }
        return id
    }

    /** The library's description of a stored iMessage conversation, and the address to send from. */
    private fun conversationOf(conversationId: String): Pair<ConversationData, String> {
        val row = repo.conversation(conversationId) ?: throw ImessageError("This conversation is not in the archive any more.")
        val participants = repo.participants(conversationId)
        val from = participants.firstOrNull { it.isMe }?.id?.takeIf { it in myHandles }
            ?: row.defaultOutgoingId.takeIf { it in myHandles }
            ?: Handles.preferredSender(myHandles)
            ?: throw ImessageError("This device has no registered iMessage address.")
        val data = ImessageArchive.conversationData(conversationId, participants, row.name)
        val handles = (data.participants + from).distinct()
        return data.copy(participants = handles) to from
    }

    // --- sending ----------------------------------------------------------------------------

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
            val s = session ?: throw ImessageError("iMessage is not connected.")
            val (conversation, from) = conversationOf(conversationId)
            val replyGuid = replyToId?.let(ImessageArchive::guidOf)
            val message = NormalMessage(
                listOf(IndexedMessagePart(MessagePart.Text(text, TextFormat.Flags(TextFlags(false, false, false, false))), null, null)),
                null, replyGuid, replyGuid?.let { "0:0:${text.length}" }, MessageType.IMessage, null, null, null, false, null, null,
            )
            val inst = uniffi.rust_bridge.newMsg(conversation, from, PushChatMessage.Message(message))
            sendAndConfirm(s, inst, unsend = false)
            repo.upsertMessage(
                MessageDto(
                    id = ImessageArchive.messageId(inst.id),
                    conversationId = conversationId,
                    tmpId = tmpId,
                    senderId = from,
                    fromMe = true,
                    timestampMs = inst.sentTimestamp.toLong().takeIf { it > 0 } ?: System.currentTimeMillis(),
                    direction = "out",
                    status = ImessageArchive.STATUS_SENT,
                    statusCode = 1,
                    transport = ImessageArchive.TRANSPORT,
                    text = text,
                    parts = listOf(PartDto(partId = "0", kind = "text", text = text)),
                    replyToId = replyToId.orEmpty(),
                ),
            )
        } catch (e: Throwable) {
            Log.w(TAG, "iMessage send failed", e)
            repo.setLocalState(tmpId, LocalState.FAILED, readable(e))
        }
    }

    /**
     * Retracts one of the user's iMessages (by its archive id, `imsg:<GUID>`) within Apple's 2-minute
     * window; the recipient needs iOS 16 or later for it to disappear on their side. On success the
     * message is removed here too. Throws [ImessageError] saying why when it does not go through.
     */
    suspend fun unsend(messageId: String, part: Int = 0) {
        val guid = ImessageArchive.guidOf(messageId) ?: throw ImessageError("Only iMessages can be unsent.")
        val row = repo.message(messageId) ?: throw ImessageError("This message is not in the archive any more.")
        if (!row.isOutgoing) throw ImessageError("Only your own messages can be unsent.")
        if (!UnsendWindow.canUnsend(row.timestampMs, System.currentTimeMillis())) {
            throw ImessageError("Too late to unsend: Apple only allows it within 2 minutes of sending.")
        }
        val s = session ?: throw ImessageError("iMessage is not connected.")
        val (conversation, from) = conversationOf(row.conversationId)
        val inst = uniffi.rust_bridge.newMsg(conversation, from, PushChatMessage.Unsend(UnsendMessage(guid, part.toULong())))
        sendAndConfirm(s, inst, unsend = true)
        repo.applyDeletion(messageId)
    }

    private suspend fun sendAndConfirm(s: SharedPushStateHandle, inst: MessageInst, unsend: Boolean) {
        val waiter = CompletableDeferred<String?>()
        confirms[inst.id] = waiter
        try {
            call { uniffi.rust_bridge.sendMessage(s, inst) }
            val answer = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { waiter.await() }
                ?: throw ImessageError(
                    if (unsend) {
                        "Apple did not confirm the unsend within ${CONFIRM_TIMEOUT_MS / 1000} seconds. It may not have gone through."
                    } else {
                        "Apple did not confirm the message within ${CONFIRM_TIMEOUT_MS / 1000} seconds. It may not have been delivered."
                    },
                )
            if (answer.isNotEmpty()) {
                throw ImessageError(if (unsend) "Apple refused the unsend: $answer" else "Apple refused the message: $answer")
            }
        } finally {
            confirms.remove(inst.id)
        }
    }

    // --- incoming ---------------------------------------------------------------------------

    private val receiver = object : MessageReceiver {
        override fun onMessage(id: String, event: PushEvent) {
            try {
                when (event) {
                    is PushEvent.SendConfirm -> confirms[event.uuid]?.complete(event.error ?: "")
                    is PushEvent.RegistrationState -> _status.value = when (val r = event.v1) {
                        is RegisterState.Registered -> (status.value as? Status.Registered) ?: Status.Registered(myHandles)
                        is RegisterState.Registering -> Status.Working("Registering with Apple")
                        is RegisterState.Failed -> Status.Failed(r.error)
                    }
                    is PushEvent.Message -> incoming(event.v1)
                    else -> Unit
                }
            } catch (e: Throwable) {
                Log.w(TAG, "event ${event::class.simpleName} not handled", e)
            } finally {
                // Acknowledged only after the archive has it: until then the library keeps the
                // event in its journal and hands it over again after a restart.
                pipeline?.ack(id)
            }
        }
    }

    private fun incoming(inst: MessageInst) {
        when (val u = ImessageArchive.map(inst, myHandles.toSet(), ::nameOf)) {
            is ImessageArchive.Update.Upsert -> {
                val existing = repo.conversation(u.conversation.id)
                val conversation = if (existing != null && !u.conversation.isGroup) {
                    u.conversation.copy(name = existing.name.ifBlank { u.conversation.name })
                } else {
                    u.conversation
                }
                repo.upsertConversation(conversation.copy(lastMessageTs = maxOf(existing?.lastMessageTs ?: 0, u.message.timestampMs)))
                val result = repo.upsertMessage(u.message)
                if (result.inserted && !u.message.fromMe) MessageNotifier.onIncoming(app, repo, u.message)
            }
            is ImessageArchive.Update.Remove -> repo.applyDeletion(u.messageId)
            is ImessageArchive.Update.Receipt -> repo.advanceMessageStatus(
                u.messageIds, u.status, ImessageArchive.statusCode(u.status), ImessageArchive::statusRank,
            )
            is ImessageArchive.Update.Edit -> {
                val row = repo.message(u.messageId) ?: return
                repo.upsertMessage(
                    MessageDto(
                        id = row.id, conversationId = row.conversationId, senderId = row.senderId, senderName = row.senderName,
                        fromMe = row.isOutgoing, timestampMs = row.timestampMs, direction = row.direction, status = row.status,
                        statusCode = row.statusCode, transport = ImessageArchive.TRANSPORT, text = u.text,
                        parts = listOf(PartDto(partId = "0", kind = "text", text = u.text)), replyToId = row.replyToId,
                    ),
                )
            }
            ImessageArchive.Update.Ignore -> Unit
        }
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

    private fun fail(e: Throwable) {
        Log.w(TAG, "iMessage failed", e)
        _status.value = Status.Failed(readable(e))
    }

    /** Runs a library call, turning its errors into readable ones. */
    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: ImessageError) {
        throw e
    } catch (e: RustBridgeException) {
        throw ImessageError(readable(e))
    }

    companion object {
        private const val TAG = "Imessage"
        const val CONFIRM_TIMEOUT_MS = 45_000L

        /** The public registration relay the iPhone relay apps use by default, and its public token. */
        const val DEFAULT_RELAY_HOST = "https://registration-relay.beeper.com"
        const val DEFAULT_RELAY_TOKEN = "5c175851953ecaf5209185d897591badb6c3e712"

        fun readable(e: Throwable): String = when (e) {
            is ImessageError -> e.message ?: "Unknown error"
            is RustBridgeException.Other -> e.v1
            else -> e.message ?: e::class.java.simpleName
        }

        @Volatile
        private var instance: ImessageEngine? = null

        fun get(context: Context): ImessageEngine =
            instance ?: synchronized(this) {
                instance ?: ImessageEngine(context.applicationContext).also { instance = it }
            }

        /** Whether iMessage is set up here: its files imported and a registration made. Cheap. */
        fun isSetUp(context: Context): Boolean = ImessagePrefs.registered(context) && ImessageLibrary.isInstalled(context)
    }
}

/** Gets Apple's REG-RESP for this phone's number (see [PhoneRegSms]). */
fun interface PhoneVerifier {
    suspend fun verify(pushToken: ByteArray): PhoneRegSms.Response
}

/** Apple lets an iMessage be unsent for 2 minutes after it was sent. */
object UnsendWindow {
    const val MS = 2 * 60 * 1000L

    /** Milliseconds left to unsend, or 0 when the window has passed. */
    fun remaining(sentAt: Long, now: Long): Long = (sentAt + MS - now).coerceAtLeast(0)

    fun canUnsend(sentAt: Long, now: Long): Boolean = remaining(sentAt, now) > 0
}
