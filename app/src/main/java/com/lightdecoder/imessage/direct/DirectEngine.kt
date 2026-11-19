package com.lightdecoder.imessage.direct

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import com.lightdecoder.R
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.ParticipantDto
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.rust_bridge.AttachmentType
import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.EditMessage
import uniffi.rust_bridge.EventPipeline
import uniffi.rust_bridge.IndexedMessagePart
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MessagePart
import uniffi.rust_bridge.MessageReceiver
import uniffi.rust_bridge.MessageType
import uniffi.rust_bridge.NormalMessage
import uniffi.rust_bridge.PushChatMessage
import uniffi.rust_bridge.PushEvent
import uniffi.rust_bridge.ReactMessage
import uniffi.rust_bridge.ReactMessageType
import uniffi.rust_bridge.RegisterState
import uniffi.rust_bridge.RustBridgeException
import uniffi.rust_bridge.SharedPushStateHandle
import uniffi.rust_bridge.TextFlags
import uniffi.rust_bridge.TextFormat
import uniffi.rust_bridge.UnsendMessage

/**
 * OpenBubbles' library, in the ":imessage" process only ([DirectProcess]): the rest of
 * LightDecoder never has it in memory, and a crash in it cannot take the Messages tab or the
 * Google Messages link down. The main process reaches it through [DirectService] (see
 * [DirectWire]); what Apple sends goes to the main process's archive through [sink].
 *
 * - [register]: relay code -> device identity -> Apple push connection -> this phone's number,
 *   verified by SMS -> iMessage registration -> live session. Green ([Status.Registered]) only
 *   once Apple reports the registration as done.
 * - [send], [unsend], [edit], [react], [typing], [read] for a [DirectWire.Target] the main
 *   process built from its archive, so they work after a restart of either process; [download]
 *   fetches an attachment.
 * - Incoming events are mapped to archive changes ([DirectArchive]) and handed to [sink] in
 *   order, once the user's own addresses are known (else the user's messages would look like
 *   someone else's); each is acknowledged to the library only once the main process stored it,
 *   and the sender gets a delivery receipt first, as from an iPhone.
 */
class DirectEngine private constructor(private val app: Context) {

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

    /** Stores an archive change in the main process; true once it is stored. */
    fun interface Sink {
        suspend fun deliver(update: DirectArchive.Update): Boolean
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val _status = MutableStateFlow<Status>(Status.NotRegistered)
    val status: StateFlow<Status> = _status

    val statePath: String = File(app.filesDir, "imessage/state").apply { mkdirs() }.path

    private var started = false
    private var pipeline: EventPipeline? = null

    @Volatile
    private var session: SharedPushStateHandle? = null

    /** The user's own addresses, as the library registered them; empty until known. */
    private val handles = MutableStateFlow<List<String>>(emptyList())
    private val myHandles: List<String> get() = handles.value

    /** The one-to-one chats' guids (see [ChatGuids]). */
    private val chatGuids = ChatGuids(File(app.filesDir, "imessage/chat-guids.json"))

    private val texts = DirectTexts(app)

    /** Waiting for Apple's answer to a send, by message id. */
    private val confirms = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    /** Events from Apple not handled yet, in the order Apple sent them, by journal id. */
    private val events = Channel<Pair<String, PushEvent>>(Channel.UNLIMITED)

    @Volatile
    var sink: Sink? = null

    val isRegistered: Boolean get() = _status.value is Status.Registered

    init {
        scope.launch {
            for ((id, event) in events) {
                if (event is PushEvent.Message) handle(event.v1)
                // Acknowledged (in order) once the main process stored it; until then the library
                // keeps the event in its journal and hands it over again after a restart.
                pipeline?.ack(id)
            }
        }
    }

    /**
     * One message from Apple: once the user's addresses are known (until then nothing is
     * acknowledged, so the journal keeps it), into the archive, then the delivery receipt.
     */
    private suspend fun handle(inst: MessageInst) {
        val me = handles.first { it.isNotEmpty() }.toSet()
        DirectMessages.oneToOneChat(inst, me)?.let { (peer, guid) -> chatGuids.remember(peer, guid) }
        val update = try {
            DirectArchive.map(inst, me, Handles::display, texts)
        } catch (e: Exception) {
            Log.w(TAG, "message ${inst.message::class.simpleName} not mapped", e)
            DirectArchive.Update.Ignore
        }
        if (update != DirectArchive.Update.Ignore) store(update)
        sendDeliveryAck(inst, me)
    }

    /** Hands [update] to the main process until it is stored. */
    private suspend fun store(update: DirectArchive.Update) {
        var attempt = 0
        while (true) {
            val stored = try {
                sink?.deliver(update) == true
            } catch (e: Exception) {
                Log.w(TAG, "main process did not take an update", e)
                false
            }
            if (stored) return
            delay((1000L shl attempt.coerceAtMost(6)))
            attempt++
        }
    }

    /**
     * Tells the sender their message arrived (OpenBubbles' `markCertified`). Never in the way of
     * the rest: a failure is only logged, and Apple gets [RECEIPT_TIMEOUT_MS] at most.
     */
    private suspend fun sendDeliveryAck(inst: MessageInst, me: Set<String>) {
        val s = session ?: return
        val ack = DirectMessages.deliveryAck(inst, me)
        if (ack == DirectMessages.DeliveryAck.None) return
        try {
            val done = withTimeoutOrNull(RECEIPT_TIMEOUT_MS) {
                when (ack) {
                    is DirectMessages.DeliveryAck.Certify -> uniffi.rust_bridge.certifyDelivery(s, ack.context, ack.notify)
                    is DirectMessages.DeliveryAck.Delivered -> {
                        val receipt = uniffi.rust_bridge.newMsg(ack.conversation, ack.from, PushChatMessage.Delivered)
                            .copy(id = inst.id, target = inst.target)
                        uniffi.rust_bridge.sendMessage(s, receipt)
                    }
                    DirectMessages.DeliveryAck.None -> Unit
                }
            }
            if (done == null) Log.w(TAG, "delivery receipt: Apple did not answer in time")
        } catch (e: Exception) {
            Log.w(TAG, "delivery receipt not sent", e)
        }
    }

    /** Reads the user's addresses again (they change with the registration). */
    private suspend fun refreshHandles() {
        val s = session ?: return
        try {
            val now = uniffi.rust_bridge.getHandles(s)
            if (now.isEmpty()) return
            handles.value = now
            if (_status.value is Status.Registered) _status.value = Status.Registered(now)
        } catch (e: Throwable) {
            Log.w(TAG, "could not read the registered addresses", e)
        }
    }

    /** Loads the library and restores a previous registration, if any. Safe to call again. */
    fun start() {
        scope.launch {
            lock.withLock {
                val missing = DirectLibrary.missing(app)
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
        DirectLibrary.load(app)
        uniffi.rust_bridge.doFirstTimeInit(statePath)
        uniffi.rust_bridge.setupKeystore(statePath, DirectKeystore(app))
        // Needed before any attachment is prepared for upload.
        uniffi.rust_bridge.setFilePackager(DirectFilePackager(app))
        pipeline = EventPipeline(File(statePath, "journal").path).also { it.setReceiver(receiver) }
        started = true
    }

    private suspend fun refreshStatus() {
        val s = session
        if (s == null) {
            _status.value = Status.NotRegistered
            DirectPrefs.setRegistered(app, false)
            return
        }
        _status.value = when (val reg = uniffi.rust_bridge.getRegState(s)) {
            is RegisterState.Registered -> {
                handles.value = uniffi.rust_bridge.getHandles(s)
                DirectPrefs.setRegistered(app, true)
                Status.Registered(myHandles)
            }
            is RegisterState.Registering -> Status.Working("Registering with Apple")
            is RegisterState.Failed -> Status.Failed(reg.error)
        }
    }

    /**
     * Registers this phone's number with iMessage, using the iPhone relay that [relayCode] names.
     * [phone] does the SMS part. Progress shows in [status]; once registered the background
     * service is started.
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
            if (isRegistered) DirectService.start(app)
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

        val user = phoneUser(config, conn, phone, ::step)

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

    /**
     * This phone's number as an iMessage user. As OpenBubbles 2.0.0 does: with the SIM directly when
     * the user allowed that ([SimAuth]; the carrier vouches for the number, no text is sent), else,
     * or when the carrier has no such service, by Apple's text-message exchange ([phone]).
     */
    private suspend fun phoneUser(
        config: uniffi.rust_bridge.JoinedOsConfig,
        conn: uniffi.rust_bridge.ApsConnection,
        phone: PhoneVerifier,
        step: (String) -> Unit,
    ): uniffi.rust_bridge.IdsUser {
        val simAllowed = SimAuth.allowed(app)
        if (simAllowed) {
            step("Verifying this phone's number with its SIM")
            try {
                return SimAuth.verify(app, config, conn)
            } catch (e: ImessageError) {
                if (SimAuth.UNSUPPORTED !in e.message.orEmpty()) {
                    throw ImessageError("SIM verification failed: ${e.message}")
                }
                Log.i(TAG, "carrier has no SIM verification; using SMS")
            }
        }
        step("Verifying this phone's number by SMS")
        val reply = try {
            phone.verify(uniffi.rust_bridge.getToken(conn))
        } catch (e: ImessageError) {
            // No answer to the text and the SIM route not allowed yet: that route is the likely fix.
            throw if (!simAllowed && SmsPhoneVerifier.NO_ANSWER in e.message.orEmpty()) {
                ImessageError(e.message + " " + NEEDS_SIM)
            } else {
                e
            }
        }
        return uniffi.rust_bridge.authPhone(conn, config, reply.number, reply.signature)
    }

    /** Waits (a little) for [start] to restore the session, for requests that arrive right after the process starts. */
    private suspend fun requireSession(): SharedPushStateHandle {
        session?.let { return it }
        start()
        return withTimeoutOrNull(SESSION_WAIT_MS) {
            while (session == null || !isRegistered) {
                if (_status.value is Status.Failed || _status.value is Status.LibraryMissing || _status.value is Status.NotRegistered) {
                    if (started) break
                }
                delay(200)
            }
            session
        }?.takeIf { isRegistered } ?: throw ImessageError("iMessage is not connected.")
    }

    // --- requests from the main process ---------------------------------------------------------

    /**
     * The conversation with [recipient] if that address is on iMessage: as the archive should
     * store it (names are the addresses; the main process puts contact names in). Null otherwise.
     */
    suspend fun reach(recipient: String): ConversationDto? {
        val s = try {
            requireSession()
        } catch (e: ImessageError) {
            return null
        }
        val to = Handles.normalize(recipient) ?: return null
        val from = Handles.preferredSender(myHandles) ?: return null
        val reachable = try {
            uniffi.rust_bridge.validateTargets(s, listOf(to), from)
        } catch (e: RustBridgeException) {
            return null
        }
        if (to !in reachable) return null
        return ConversationDto(
            id = DirectArchive.conversationId(listOf(to, from), myHandles.toSet(), null),
            name = Handles.display(to),
            type = DirectArchive.TRANSPORT,
            defaultOutgoingId = from,
            lastMessageTs = System.currentTimeMillis(),
            participants = listOf(
                ParticipantDto(id = to, name = Handles.display(to), number = Handles.display(to), formattedNumber = Handles.display(to)),
                ParticipantDto(id = from, number = Handles.display(from), formattedNumber = Handles.display(from), isMe = true),
            ),
            otherParticipantIds = listOf(to),
        )
    }

    /**
     * Sends a message and returns its GUID, timestamp and attachment parts once Apple confirmed
     * it: [text], an inline reply to [replyGuid] (part [replyPart]), sent with [effect] (Apple's
     * effect id), [voice] when it is a recorded audio message.
     */
    suspend fun send(
        target: DirectWire.Target,
        text: String,
        replyGuid: String?,
        replyPart: String?,
        effect: String?,
        attachments: List<DirectWire.Attachment>,
        voice: Boolean,
    ): DirectWire.SendReply {
        if (attachments.isNotEmpty()) throw ImessageError(app.getString(R.string.imessage_direct_no_attachments_yet))
        val s = requireSession()
        val (conversation, from) = conversationOf(target)
        val message = NormalMessage(
            listOf(textPart(text)),
            effect, replyGuid, replyGuid?.let { replyPart ?: "0:0:0" }, MessageType.IMessage,
            null, null, null, voice, null, null,
        )
        val inst = uniffi.rust_bridge.newMsg(conversation, from, PushChatMessage.Message(message))
        sendAndConfirm(s, inst, Sent.MESSAGE)
        return DirectWire.SendReply(inst.id, inst.sentTimestamp.toLong().takeIf { it > 0 } ?: System.currentTimeMillis(), emptyList())
    }

    /** Retracts [guid]; the main process has checked the 2-minute window. */
    suspend fun unsend(target: DirectWire.Target, guid: String, part: Int) {
        val s = requireSession()
        val (conversation, from) = conversationOf(target)
        val inst = uniffi.rust_bridge.newMsg(conversation, from, PushChatMessage.Unsend(UnsendMessage(guid, part.toULong())))
        sendAndConfirm(s, inst, Sent.UNSEND)
    }

    /** Part [part] of the user's message [guid] now reads [text]; the main process checked the limits. */
    suspend fun edit(target: DirectWire.Target, guid: String, part: Int, text: String) {
        val s = requireSession()
        val (conversation, from) = conversationOf(target)
        val inst = uniffi.rust_bridge.newMsg(
            conversation, from, PushChatMessage.Edit(EditMessage(guid, part.toULong(), listOf(textPart(text)))),
        ).copy(sentTimestamp = System.currentTimeMillis().toULong())
        sendAndConfirm(s, inst, Sent.EDIT)
    }

    /**
     * Puts the user's tapback [emoji] on part [part] of message [guid] ([enable]) or takes it
     * back; returns the tapback's own GUID. [targetText] is for the other side's notification.
     */
    suspend fun react(target: DirectWire.Target, guid: String, part: Int, emoji: String, enable: Boolean, targetText: String): String {
        val s = requireSession()
        val (conversation, from) = conversationOf(target)
        val reaction = ReactMessage(guid, part.toULong(), ReactMessageType.React(DirectArchive.reactionOf(emoji), enable), targetText, null)
        val inst = uniffi.rust_bridge.newMsg(conversation, from, PushChatMessage.React(reaction))
            .copy(sentTimestamp = System.currentTimeMillis().toULong())
        sendAndConfirm(s, inst, Sent.REACTION)
        return inst.id
    }

    /** Tells the chat the user is typing, or stopped. Apple does not answer it. */
    suspend fun typing(target: DirectWire.Target, typing: Boolean) {
        val s = requireSession()
        val (conversation, from) = conversationOf(target)
        sendOnly(s, uniffi.rust_bridge.newMsg(conversation, from, PushChatMessage.Typing(typing, null)))
    }

    /**
     * The user read the chat up to [guid]: the user's other devices are told (they clear it too),
     * and with read receipts on, the other person of a one-to-one chat. Apple does not answer it.
     */
    suspend fun read(target: DirectWire.Target, guid: String) {
        val s = requireSession()
        val (conversation, from) = conversationOf(target)
        val to = DirectMessages.readReceiptParticipants(target, DirectPrefs.sendReadReceipts(app))
        val receipt = conversation.copy(participants = (to + from).distinct(), afterGuid = null)
        sendOnly(s, uniffi.rust_bridge.newMsg(receipt, from, PushChatMessage.Read).copy(id = guid))
    }

    /**
     * Fetches the attachment [descriptor] (as the library saved it, `saveAttachment`) into the
     * file [path], through `<path>.partial` so a half-written file is never taken for the whole.
     * Returns its size.
     */
    suspend fun download(descriptor: String, path: String): Long {
        val attachment = call { uniffi.rust_bridge.restoreAttachment(descriptor) }
        val file = File(path)
        val partial = File("$path.partial")
        file.parentFile?.mkdirs()
        try {
            when (val type = attachment.aType) {
                is AttachmentType.Inline -> partial.writeBytes(type.v1)
                is AttachmentType.Mmcs -> {
                    val s = requireSession()
                    val largest = type.v1.maxByOrNull { it.size } ?: throw ImessageError(app.getString(R.string.imessage_direct_attachment_no_file))
                    call { uniffi.rust_bridge.downloadMmcsFile(s, largest, partial.path, null) }
                }
            }
            if (partial.length() <= 0 || !partial.renameTo(file)) {
                throw ImessageError(app.getString(R.string.imessage_direct_attachment_not_saved))
            }
            return file.length()
        } finally {
            partial.delete()
        }
    }

    private fun textPart(text: String) =
        IndexedMessagePart(MessagePart.Text(text, TextFormat.Flags(TextFlags(false, false, false, false))), null, null)

    /**
     * The library's conversation for [target] and the address to send from. A one-to-one chat
     * keeps its chat guid ([ChatGuids]) so that iPhones keep it in one conversation.
     */
    private fun conversationOf(target: DirectWire.Target): Pair<ConversationData, String> {
        val from = target.from.takeIf { it in myHandles } ?: Handles.preferredSender(myHandles)
            ?: throw ImessageError("This device has no registered iMessage address.")
        val chatGuid = target.peer?.let(chatGuids::guidFor)
        return DirectMessages.conversation(target, from, chatGuid) to from
    }

    /** What [sendAndConfirm] sends, with the errors it can end in. */
    private enum class Sent(@StringRes val notConfirmed: Int, @StringRes val refused: Int) {
        MESSAGE(R.string.imessage_direct_message_not_confirmed, R.string.imessage_direct_message_refused),
        UNSEND(R.string.imessage_direct_unsend_not_confirmed, R.string.imessage_direct_unsend_refused),
        EDIT(R.string.imessage_direct_edit_not_confirmed, R.string.imessage_direct_edit_refused),
        REACTION(R.string.imessage_direct_reaction_not_confirmed, R.string.imessage_direct_reaction_refused),
    }

    /**
     * Sends [inst] and waits for Apple's answer. When the library had nobody to send it to (no
     * device besides this one), no answer comes and there is nothing to wait for.
     */
    private suspend fun sendAndConfirm(s: SharedPushStateHandle, inst: MessageInst, what: Sent) {
        val waiter = CompletableDeferred<String?>()
        confirms[inst.id] = waiter
        try {
            val sent = call { uniffi.rust_bridge.sendMessage(s, inst) }
            if (!sent) {
                Log.i(TAG, "${what.name.lowercase()} had no device to go to")
                return
            }
            val answer = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { waiter.await() }
                ?: throw ImessageError(app.getString(what.notConfirmed, (CONFIRM_TIMEOUT_MS / 1000).toInt()))
            if (answer.isNotEmpty()) throw ImessageError(app.getString(what.refused, answer))
        } finally {
            confirms.remove(inst.id)
        }
    }

    /** Sends something Apple does not answer (typing, read): never waits. */
    private suspend fun sendOnly(s: SharedPushStateHandle, inst: MessageInst) {
        call { uniffi.rust_bridge.sendMessage(s, inst) }
    }

    // --- events from Apple -----------------------------------------------------------------------

    private val receiver = object : MessageReceiver {
        override fun onMessage(id: String, event: PushEvent) {
            try {
                when (event) {
                    is PushEvent.SendConfirm -> confirms[event.uuid]?.complete(event.error ?: "")
                    is PushEvent.RegistrationState -> {
                        _status.value = when (val r = event.v1) {
                            is RegisterState.Registered -> (status.value as? Status.Registered) ?: Status.Registered(myHandles)
                            is RegisterState.Registering -> Status.Working("Registering with Apple")
                            is RegisterState.Failed -> Status.Failed(r.error)
                        }
                        scope.launch { refreshHandles() }
                    }
                    else -> Unit
                }
            } catch (e: Throwable) {
                Log.w(TAG, "event ${event::class.simpleName} not handled", e)
            }
            // Messages are mapped by the consumer, once the user's addresses are known; every
            // event is acknowledged there, in order.
            events.trySend(id to event)
        }
    }

    private fun fail(e: Throwable) {
        Log.w(TAG, "iMessage failed", e)
        _status.value = Status.Failed(readable(e))
    }

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

        /** How long a delivery receipt may hold up the next message from Apple. */
        private const val RECEIPT_TIMEOUT_MS = 15_000L

        /** Added to the error when Apple's text gateway stays silent and the SIM route is not allowed yet. */
        const val NEEDS_SIM = "If this keeps happening, your carrier may verify the number with the SIM instead of a text."
        private const val SESSION_WAIT_MS = 20_000L

        /** The public registration relay the iPhone relay apps use by default, and its public token. */
        const val DEFAULT_RELAY_HOST = "https://registration-relay.beeper.com"
        const val DEFAULT_RELAY_TOKEN = "5c175851953ecaf5209185d897591badb6c3e712"

        fun readable(e: Throwable): String = when (e) {
            is ImessageError -> e.message ?: "Unknown error"
            is RustBridgeException.Other -> e.v1
            else -> e.message ?: e::class.java.simpleName
        }

        @Volatile
        private var instance: DirectEngine? = null

        /** Only in the ":imessage" process. */
        fun get(context: Context): DirectEngine =
            instance ?: synchronized(this) {
                instance ?: DirectEngine(context.applicationContext).also { instance = it }
            }

        /** Whether iMessage is set up: its files imported and a registration made. Cheap; any process. */
        fun isSetUp(context: Context): Boolean = DirectPrefs.registered(context) && DirectLibrary.isInstalled(context)
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
