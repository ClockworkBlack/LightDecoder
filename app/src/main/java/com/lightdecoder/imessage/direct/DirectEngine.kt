package com.lightdecoder.imessage.direct

import android.content.Context
import android.util.Log
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
 * OpenBubbles' library, in the ":imessage" process only ([DirectProcess]): the rest of
 * LightDecoder never has it in memory, and a crash in it cannot take the Messages tab or the
 * Google Messages link down. The main process reaches it through [DirectService] (see
 * [DirectWire]); what Apple sends goes to the main process's archive through [sink].
 *
 * - [register]: relay code -> device identity -> Apple push connection -> this phone's number,
 *   verified by SMS -> iMessage registration -> live session. Green ([Status.Registered]) only
 *   once Apple reports the registration as done.
 * - [send] / [unsend] for a [DirectWire.Target] the main process built from its archive, so
 *   unsend works after a restart of either process.
 * - Incoming events are mapped to archive changes ([DirectArchive]) and handed to [sink] in
 *   order; each is acknowledged to the library only once the main process stored it.
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
    private var session: SharedPushStateHandle? = null

    @Volatile
    private var myHandles: List<String> = emptyList()

    /** Waiting for Apple's answer to a send, by message id. */
    private val confirms = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    /** Archive changes waiting for the main process, in the order Apple sent them. */
    private val outgoingEvents = Channel<Pair<String, DirectArchive.Update>>(Channel.UNLIMITED)

    @Volatile
    var sink: Sink? = null

    val isRegistered: Boolean get() = _status.value is Status.Registered

    init {
        scope.launch {
            for ((id, update) in outgoingEvents) {
                if (update != DirectArchive.Update.Ignore) {
                    var stored = false
                    var attempt = 0
                    while (!stored) {
                        stored = try {
                            sink?.deliver(update) == true
                        } catch (e: Exception) {
                            Log.w(TAG, "main process did not take an update", e)
                            false
                        }
                        if (!stored) delay((1000L shl attempt.coerceAtMost(6)))
                        attempt++
                    }
                }
                pipeline?.ack(id)
            }
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
                myHandles = uniffi.rust_bridge.getHandles(s)
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

    /** Sends a text and returns its GUID and timestamp once Apple confirmed it. */
    suspend fun send(target: DirectWire.Target, text: String, replyGuid: String?): Pair<String, Long> {
        val s = requireSession()
        val (conversation, from) = conversationOf(target)
        val message = NormalMessage(
            listOf(IndexedMessagePart(MessagePart.Text(text, TextFormat.Flags(TextFlags(false, false, false, false))), null, null)),
            null, replyGuid, replyGuid?.let { "0:0:${text.length}" }, MessageType.IMessage, null, null, null, false, null, null,
        )
        val inst = uniffi.rust_bridge.newMsg(conversation, from, PushChatMessage.Message(message))
        sendAndConfirm(s, inst, unsend = false)
        return inst.id to (inst.sentTimestamp.toLong().takeIf { it > 0 } ?: System.currentTimeMillis())
    }

    /** Retracts [guid]; the main process has checked the 2-minute window. */
    suspend fun unsend(target: DirectWire.Target, guid: String, part: Int) {
        val s = requireSession()
        val (conversation, from) = conversationOf(target)
        val inst = uniffi.rust_bridge.newMsg(conversation, from, PushChatMessage.Unsend(UnsendMessage(guid, part.toULong())))
        sendAndConfirm(s, inst, unsend = true)
    }

    private fun conversationOf(target: DirectWire.Target): Pair<ConversationData, String> {
        val from = target.from.takeIf { it in myHandles } ?: Handles.preferredSender(myHandles)
            ?: throw ImessageError("This device has no registered iMessage address.")
        val handles = (target.participants + from).distinct()
        return ConversationData(handles, target.groupName, target.groupGuid, null) to from
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

    // --- events from Apple -----------------------------------------------------------------------

    private val receiver = object : MessageReceiver {
        override fun onMessage(id: String, event: PushEvent) {
            var update: DirectArchive.Update = DirectArchive.Update.Ignore
            try {
                when (event) {
                    is PushEvent.SendConfirm -> confirms[event.uuid]?.complete(event.error ?: "")
                    is PushEvent.RegistrationState -> _status.value = when (val r = event.v1) {
                        is RegisterState.Registered -> (status.value as? Status.Registered) ?: Status.Registered(myHandles)
                        is RegisterState.Registering -> Status.Working("Registering with Apple")
                        is RegisterState.Failed -> Status.Failed(r.error)
                    }
                    is PushEvent.Message -> update = DirectArchive.map(event.v1, myHandles.toSet(), Handles::display)
                    else -> Unit
                }
            } catch (e: Throwable) {
                Log.w(TAG, "event ${event::class.simpleName} not handled", e)
            }
            // Acknowledged (in order) once the main process stored it; until then the library
            // keeps the event in its journal and hands it over again after a restart.
            outgoingEvents.trySend(id to update)
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

        /** Added to the error when Apple's text gateway stays silent and the SIM route is not allowed yet. */
        const val NEEDS_SIM = "Many carriers verify the number with the SIM instead of a text. " +
            "Allow \"Verify with the SIM\" below, then tap Register again."
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
