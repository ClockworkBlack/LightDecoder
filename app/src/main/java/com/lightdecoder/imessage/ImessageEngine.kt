package com.lightdecoder.imessage

import android.content.Context
import android.util.Log
import java.io.File
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
 * iMessage through OpenBubbles' library, inside the ":imessage" process only (the library is
 * never loaded into the main process). One per process.
 *
 * - [register]: relay code -> device identity -> Apple push connection -> this phone's number,
 *   verified by SMS -> iMessage registration -> live session. The status is green
 *   ([Status.Registered]) only once Apple reports the registration as done.
 * - [sendImessage] returns the GUID of the message it sent, once Apple has confirmed it.
 * - [unsend] retracts a message by GUID within Apple's 2-minute window and reports Apple's answer.
 *
 * What is written down: the library's own state (keys in the Android Keystore, its registration
 * files under [statePath]) and, for unsend, [SentGuids] (GUID, part, time; never content).
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

    /** A failure the screen shows as it is. */
    class ImessageError(message: String) : Exception(message)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val _status = MutableStateFlow<Status>(Status.NotRegistered)
    val status: StateFlow<Status> = _status

    val statePath: String = File(app.filesDir, "imessage/state").apply { mkdirs() }.path
    val sent = SentGuids(app)

    private var started = false
    private var pipeline: EventPipeline? = null
    private var session: SharedPushStateHandle? = null

    /** Waiting for Apple's answer to a send, by message id. */
    private val confirms = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    /** For unsend: who each recent message went to and from which handle (memory only). */
    private val conversations = ConcurrentHashMap<String, Pair<ConversationData, String>>()

    /** Incoming iMessages are shown as notifications by this (set by the process's screen). */
    @Volatile
    var onIncoming: ((sender: String, text: String) -> Unit)? = null

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
    }

    private suspend fun refreshStatus() {
        val s = session
        if (s == null) {
            _status.value = Status.NotRegistered
            return
        }
        _status.value = when (val reg = uniffi.rust_bridge.getRegState(s)) {
            is RegisterState.Registered -> Status.Registered(uniffi.rust_bridge.getHandles(s))
            is RegisterState.Registering -> Status.Working("Registering with Apple")
            is RegisterState.Failed -> Status.Failed(reg.error)
        }
    }

    /**
     * Registers this phone's number with iMessage, using the iPhone relay that [relayCode] names.
     * [phone] does the SMS part. Progress shows in [status]; the result is [Status.Registered] or
     * [Status.Failed].
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

    /**
     * Sends [text] to [recipient] (a phone number or email) over iMessage and returns its GUID,
     * once Apple has confirmed the send. Throws [ImessageError] with a readable reason otherwise.
     */
    suspend fun sendImessage(recipient: String, text: String): String {
        val s = session ?: throw ImessageError("iMessage is not registered yet.")
        val to = Handles.normalize(recipient) ?: throw ImessageError("\"$recipient\" is not a phone number or email address.")
        if (text.isBlank()) throw ImessageError("The message is empty.")
        val from = Handles.preferredSender(call { uniffi.rust_bridge.getHandles(s) })
            ?: throw ImessageError("This device has no registered iMessage address.")
        val reachable = call { uniffi.rust_bridge.validateTargets(s, listOf(to), from) }
        if (to !in reachable) throw ImessageError("${Handles.display(to)} is not on iMessage.")
        val conversation = ConversationData(listOf(to, from), null, null, null)
        val message = NormalMessage(
            listOf(IndexedMessagePart(MessagePart.Text(text, TextFormat.Flags(TextFlags(false, false, false, false))), null, null)),
            null, null, null, MessageType.IMessage, null, null, null, false, null, null,
        )
        val inst = uniffi.rust_bridge.newMsg(conversation, from, PushChatMessage.Message(message))
        sendAndConfirm(s, inst, "send")
        val guid = inst.id
        sent.add(SentGuids.Entry(guid, 0, System.currentTimeMillis()))
        conversations[guid] = conversation to from
        return guid
    }

    /**
     * Retracts the message [guid] (its [part], 0 for a plain text). Apple allows this for 2 minutes
     * after sending; the recipient needs iOS 16 or later for it to disappear on their side.
     * Throws [ImessageError] saying why when it does not go through.
     */
    suspend fun unsend(guid: String, part: Int = 0) {
        val s = session ?: throw ImessageError("iMessage is not registered.")
        val entry = sent.find(guid) ?: throw ImessageError("This message was not sent from LightDecoder, or is too old.")
        if (!SentGuids.canUnsend(entry.sentAt, System.currentTimeMillis())) {
            throw ImessageError("Too late to unsend: Apple only allows it within 2 minutes of sending.")
        }
        val (conversation, from) = conversations[guid]
            ?: throw ImessageError("LightDecoder restarted since this was sent, so it no longer knows who it went to. It can't be unsent from here.")
        val inst = uniffi.rust_bridge.newMsg(conversation, from, PushChatMessage.Unsend(UnsendMessage(guid, part.toULong())))
        sendAndConfirm(s, inst, "unsend")
        sent.remove(guid)
        conversations.remove(guid)
    }

    private suspend fun sendAndConfirm(s: SharedPushStateHandle, inst: MessageInst, what: String) {
        val waiter = CompletableDeferred<String?>()
        confirms[inst.id] = waiter
        try {
            call { uniffi.rust_bridge.sendMessage(s, inst) }
            val answer = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { waiter.await() }
                ?: throw ImessageError(
                    if (what == "unsend") {
                        "Apple did not confirm the unsend within ${CONFIRM_TIMEOUT_MS / 1000} seconds. It may not have gone through."
                    } else {
                        "Apple did not confirm the message within ${CONFIRM_TIMEOUT_MS / 1000} seconds. It may not have been delivered."
                    },
                )
            if (answer.isNotEmpty()) {
                throw ImessageError(if (what == "unsend") "Apple refused the unsend: $answer" else "Apple refused the message: $answer")
            }
        } finally {
            confirms.remove(inst.id)
        }
    }

    private val receiver = object : MessageReceiver {
        override fun onMessage(id: String, event: PushEvent) {
            try {
                when (event) {
                    is PushEvent.SendConfirm -> confirms[event.uuid]?.complete(event.error ?: "")
                    is PushEvent.RegistrationState -> _status.value = when (val r = event.v1) {
                        is RegisterState.Registered -> (status.value as? Status.Registered) ?: Status.Registered(emptyList())
                        is RegisterState.Registering -> Status.Working("Registering with Apple")
                        is RegisterState.Failed -> Status.Failed(r.error)
                    }
                    is PushEvent.Message -> incoming(event.v1)
                    else -> Unit
                }
            } catch (e: Throwable) {
                Log.w(TAG, "event ${event::class.simpleName} not handled", e)
            } finally {
                pipeline?.ack(id)
            }
        }
    }

    private fun incoming(inst: MessageInst) {
        val msg = (inst.message as? PushChatMessage.Message)?.v1 ?: return
        val text = msg.parts.mapNotNull { (it.part as? MessagePart.Text)?.v1 }.joinToString("\n")
        val sender = inst.sender ?: return
        if (text.isNotEmpty()) onIncoming?.invoke(Handles.display(sender), text)
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
    }
}

/** Gets Apple's REG-RESP for this phone's number (see [PhoneRegSms]). */
fun interface PhoneVerifier {
    suspend fun verify(pushToken: ByteArray): PhoneRegSms.Response
}
