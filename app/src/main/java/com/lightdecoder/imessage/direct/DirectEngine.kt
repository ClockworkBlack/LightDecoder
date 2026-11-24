package com.lightdecoder.imessage.direct

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import com.lightdecoder.R
import com.lightdecoder.archive.MediaCache
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.facetime.CallBridge
import com.lightdecoder.imessage.facetime.CallEvent
import com.lightdecoder.imessage.facetime.CallLogEntry
import com.lightdecoder.imessage.facetime.CallSessionInfo
import com.lightdecoder.imessage.facetime.CallerInfo
import com.lightdecoder.imessage.facetime.FaceTimeCalls
import com.lightdecoder.imessage.facetime.GUARD_WAIT_MS
import com.lightdecoder.imessage.facetime.SessionSwap
import com.lightdecoder.imessage.facetime.withLockWithin
import com.lightdecoder.imessage.facetime.LocalAddresses
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.ParticipantDto
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import uniffi.rust_bridge.AttachmentType
import uniffi.rust_bridge.ChatRenameMessage
import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.EditMessage
import uniffi.rust_bridge.EventPipeline
import uniffi.rust_bridge.MessageAttachment
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MessageReceiver
import uniffi.rust_bridge.MessageType
import uniffi.rust_bridge.MmcsFile
import uniffi.rust_bridge.NormalMessage
import uniffi.rust_bridge.PushChatMessage
import uniffi.rust_bridge.PushEvent
import uniffi.rust_bridge.ReactMessage
import uniffi.rust_bridge.ReactMessageType
import uniffi.rust_bridge.RegisterState
import uniffi.rust_bridge.RustBridgeException
import uniffi.rust_bridge.ScheduleMode
import uniffi.rust_bridge.SetTranscriptBackgroundMessage
import uniffi.rust_bridge.SharedPushStateHandle
import uniffi.rust_bridge.SimplifiedTranscriptPoster
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
 * - FaceTime: Apple's call events are handed to [FaceTimeCalls] as they come (queued there: no
 *   message waits behind a call), and the library's call functions on the session are [callBridge].
 *   With "FaceTime" off ([DirectPrefs.faceTimeOn]) the events are acknowledged unread, as before.
 * - The optional Apple Account ([account]): signed in and out from its own screen, on this session.
 *   Without one, nothing of it runs: the session comes up with no account, as it always did.
 */
class DirectEngine private constructor(private val app: Context) {

    sealed class Status {
        /** The OpenBubbles files have not been imported yet. */
        data class LibraryMissing(val names: List<String>) : Status()
        object NotRegistered : Status()
        data class Working(val step: String) : Status()
        data class Registered(val handles: List<String>) : Status()

        /** Apple's registration failed for now; the library tries again by itself in [retryWaitSec]. */
        data class Retrying(val error: String, val retryWaitSec: Long) : Status()
        data class Failed(val error: String) : Status()
    }

    /** A failure the screens show as it is. */
    open class ImessageError(message: String) : Exception(message)

    /** Apple did not answer in time: what was asked may have gone through all the same. */
    class Unconfirmed(message: String) : ImessageError(message)

    /** A download larger than it may be ([downloadCapped]): stopped, and nothing of it kept. */
    class TooLarge(message: String) : ImessageError(message)

    /** Stores an archive change in the main process: how it was taken. */
    fun interface Sink {
        suspend fun deliver(update: DirectArchive.Update): Delivery
    }

    /** Whatever escapes the work below is logged, never taken for a crash of the process. */
    private val failures = CoroutineExceptionHandler { _, e -> Log.e(TAG, "iMessage work failed (${e::class.java.simpleName})", e) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + failures)
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

    /** Whether [address] is one of the user's own addresses as the library registered them (never blocked). Never throws. */
    fun isOwnHandle(address: String): Boolean = try {
        myHandles.any { com.lightdecoder.imessage.IMessageAddress.sameKey(it, address) }
    } catch (e: Exception) {
        false
    }

    /** The one-to-one chats' guids (see [ChatGuids]). */
    private val chatGuids = ChatGuids(File(app.filesDir, "imessage/chat-guids.json"))

    private val texts = DirectTexts(app)

    /** Chat backgrounds from Apple: fetched, their picture written for the main process. */
    private val backgrounds = IncomingBackgrounds(
        app,
        object : IncomingBackgrounds.Natives {
            override fun toMmcs(message: SetTranscriptBackgroundMessage): MmcsFile? = uniffi.rust_bridge.transcriptBackgroundToMmcs(message)
            override suspend fun download(file: MmcsFile, path: String, maxBytes: Long) = downloadCapped(requireSession(), file, path, maxBytes)
            override fun parse(payload: ByteArray): SimplifiedTranscriptPoster = uniffi.rust_bridge.parseTranscriptPoster(payload)
        },
    )

    /** How often reading each of Apple's events took this process down ([EventAttempts]). */
    private val attempts = EventAttempts(File(app.filesDir, "imessage/event-attempts"))

    /** Waiting for Apple's answer to a send, by message id. */
    private val confirms = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    /** Events from Apple not handled yet, in the order Apple sent them, by journal id. */
    private val events = Channel<Pair<String, PushEvent>>(Channel.UNLIMITED)

    /** Events are taken through it ([consume]); stopped while the process restarts ([restartForAccount]). */
    private val gate = EventGate()

    @Volatile
    var sink: Sink? = null

    val isRegistered: Boolean get() = _status.value is Status.Registered

    /**
     * The optional Apple Account (its sign-in screen and the iMessage screen use it here, in this
     * process): run under this engine's lock, on this session.
     */
    val account: AppleAccount by lazy {
        AppleAccount(
            AccountFiles.of(app, statePath),
            NativeAccountBridge { session },
            lock,
            object : AppleAccount.Hooks {
                override val busy: Boolean get() = FaceTimeCalls.peek()?.busy == true
                override val registered: Boolean get() = isRegistered && session != null
                override val relayHost: String
                    get() = DirectPrefs.prefs(app).getString(DirectPrefs.KEY_HOST, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_RELAY_HOST

                override suspend fun restart(wanted: () -> Boolean, last: suspend () -> Unit): ProcessRestart.Outcome = restartForAccount(wanted, last)

                override suspend fun tell(state: AccountState, restarting: Boolean) {
                    val s = sink ?: return
                    withTimeoutOrNull(ACCOUNT_TELL_MS) { s.deliver(DirectArchive.Update.Account(state, restarting)) }
                }

                override fun deleteAccountKeys() = AccountKeys.delete()
            },
        )
    }

    /** The main process's requests being answered by [DirectService] (set while it runs): a restart waits for them. */
    @Volatile
    internal var requests: RequestGate? = null

    /**
     * Restarts this process for the Apple Account (§4.4): iMessage's service started first (should
     * Android refuse, nothing else is done); then never in the middle of a start or a registration
     * (they hold [lock]), nor while a FaceTime call is on, looked at again under the calls' guard (nor
     * while a join holds it, nor once a call began during its waits), nor under a request of the main
     * process being answered ([requests]); the event being read is let finish (kept back when it does
     * not in time), and should the restart then not happen, the requests and Apple's events are taken
     * again ([ProcessRestart.whenFree], [EventGate]).
     */
    internal suspend fun restartForAccount(wanted: () -> Boolean, last: suspend () -> Unit): ProcessRestart.Outcome =
        ProcessRestart.whenFree(
            start = { ProcessRestart.startService(app) },
            engineLock = lock,
            guard = callBridge.guard,
            busy = { FaceTimeCalls.peek()?.busy == true },
            wanted = wanted,
            requests = requests,
            quiesce = { gate.quiesce(QUIESCE_MS) },
            resume = { gate.resume() },
            last = last,
        ) { ProcessRestart.exit() }

    init {
        // Numbers written without their country code are read in this phone's country.
        com.lightdecoder.imessage.IMessageAddress.useRegionOf(app)
        consume()
    }

    /**
     * Takes Apple's events, one at a time in the order they came. Nothing one of them does stops
     * the ones after it: what it throws is caught ([take]), and should anything still end this,
     * it starts again (the events not taken yet wait for it).
     */
    private fun consume() {
        scope.launch {
            // While the process is about to restart, the gate holds the next one back (it, and the rest,
            // wait in the library's journal); should the restart not happen, they are taken again.
            for ((id, event) in events) gate.take { take(id, event) }
        }.invokeOnCompletion { e -> if (e != null && e !is CancellationException) consume() }
    }

    /**
     * One event: read and stored ([handle]), then acknowledged (in order) once the main process
     * stored it; until then the library keeps it in its journal and hands it over again after a
     * restart. One that took this process down [EventAttempts.MAX_DEATHS] times while it was read
     * is acknowledged without being read, and said so in the iMessage log; one that fails here
     * (memory running out included) is given up on, so neither holds up every event after it.
     */
    private suspend fun take(id: String, event: PushEvent) {
        try {
            when (routeOf(event)) {
                EventRoute.MESSAGE -> {
                    val inst = (event as PushEvent.Message).v1
                    if (!attempts.start(id)) {
                        Log.w(TAG, "an event that took the iMessage process down is skipped (${kindOf(inst)})")
                        store(DirectArchive.Update.Dropped(kindOf(inst), "reading it took the iMessage process down ${EventAttempts.MAX_DEATHS} times"))
                    } else {
                        handle(inst)
                    }
                }
                // Only handed on (queued there): a ring waits for nothing here, nor anything for it.
                // With "FaceTime" off it is acknowledged unread.
                EventRoute.FACETIME -> if (DirectPrefs.faceTimeOn(app)) FaceTimeCalls.get(app).onEvent((event as PushEvent.FaceTime).v1)
                // The rest (an Apple Account's sign-in requests, Find My, shared albums, ...) unread, and never logged.
                EventRoute.ACKNOWLEDGE -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "an event from Apple was not handled (${e::class.java.simpleName})", e)
        }
        try {
            pipeline?.ack(id)
        } catch (e: Exception) {
            Log.w(TAG, "event not acknowledged", e)
        }
        attempts.done()
    }

    /** What kind of event [inst] is, for the log: its type's name, never its content. */
    private fun kindOf(inst: MessageInst): String = inst.message::class.simpleName.orEmpty().ifEmpty { "event" }

    /**
     * One message from Apple: once the user's addresses are known (until then nothing is
     * acknowledged, so the journal keeps it), into the archive, then the delivery receipt. Its
     * reading and the receipt (the library's, on what the sender sent) are counted while they last
     * ([EventAttempts.risky]).
     */
    private suspend fun handle(inst: MessageInst) {
        val me = handles.first { it.isNotEmpty() }.toSet()
        // A chat's guid only from a message Apple could verify the sender of.
        if (!inst.verificationFailed) DirectMessages.oneToOneChat(inst, me)?.let { (peer, guid) -> chatGuids.remember(peer, guid) }
        attempts.risky()
        val update = try {
            // A group's photo (and a sticker) is not fetched here: the change names where it is, and the
            // main process fetches it once it took the change (the group stored, its sender in it).
            val mapped = when (inst.message) {
                is PushChatMessage.SetTranscriptBackground -> backgrounds.receive(inst, me, Handles::display, texts)
                else -> DirectArchive.map(inst, me, Handles::display, texts, ::describe, deleteSync = DirectPrefs.deleteOnAllDevices(app))
            }
            if (mapped is DirectArchive.Update.Upsert) keepInlineFiles(inst)
            keepPictures(inst, mapped)
            mapped
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Memory run out on a picture included: this one is given up on, the next ones go on.
            Log.w(TAG, "message ${kindOf(inst)} not mapped (${e::class.java.simpleName})", e)
            DirectArchive.Update.Dropped(kindOf(inst), "it could not be read (${e::class.java.simpleName})")
        } finally {
            attempts.ended()
        }
        // Dropped by the main process as from someone the user blocked: they are not told it arrived.
        storeThenAcknowledge(update, { store(it) }) {
            attempts.risky()
            try {
                sendDeliveryAck(inst, me)
            } finally {
                attempts.ended()
            }
        }
    }

    /** The library's description of an attachment, from which it is downloaded later; empty if it has none. */
    private fun describe(attachment: MessageAttachment): String = try {
        uniffi.rust_bridge.saveAttachment(attachment)
    } catch (e: RustBridgeException) {
        Log.w(TAG, "attachment not described", e)
        ""
    }

    /**
     * Small attachments come inside the message: they are written next to the files the main
     * process looks for them in ([DirectMedia.extensionFor]), so no media bytes cross between the
     * processes; aside ([DirectMedia.pendingInlineFile]), as the main process moves each into place
     * only once it has taken the message (one reusing another message's GUID is not, and never
     * overwrites that message's files).
     */
    private fun keepInlineFiles(inst: MessageInst) {
        for (a in DirectArchive.attachments(DirectArchive.storedParts(inst.message))) {
            val bytes = a.inlineBytes?.takeIf { it.isNotEmpty() } ?: continue
            val file = MediaCache.fileFor(app, DirectArchive.mediaId(inst.id, a.idx), DirectMedia.extensionFor(a.mime, a.name), ChatService.IMESSAGE)
            // The same message again (Apple's journal after a restart): its file is there.
            if (file.length() == bytes.size.toLong()) continue
            try {
                MediaCache.writeAtomically(DirectMedia.pendingInlineFile(file), bytes)
            } catch (e: java.io.IOException) {
                Log.w(TAG, "attachment not written", e)
            }
        }
    }

    /**
     * The pictures a change names that are no attachment of a message and came inside the message:
     * a link preview's (drawn again as a JPEG, [PreviewImages.jpeg]) and a sticker's put on a message
     * (as a tapback, or dragged onto it; drawn again as a PNG). Each is written aside under its key
     * ([DirectMedia.pendingInlineFile]), as a small attachment is: the main process moves it into
     * place only once it took the change. A sticker on Apple's file servers is not fetched here: the
     * change names where it is ([DirectArchive.Update.Reaction.stickerFile]), and the main process
     * fetches it once it took the change ([download]), so nothing is fetched for a change it drops.
     */
    private fun keepPictures(inst: MessageInst, update: DirectArchive.Update) {
        when (update) {
            is DirectArchive.Update.Upsert -> {
                val key = IMessageExtra.parse(update.message.extra).link?.image?.takeIf { it.isNotEmpty() } ?: return
                val bytes = DirectArchive.linkImage((inst.message as? PushChatMessage.Message)?.v1?.linkMeta) ?: return
                writePending(key, PreviewImages.jpeg(bytes, PreviewImages.LINK_SIDE))
            }
            is DirectArchive.Update.Reaction -> update.sticker?.let { key -> writePending(key, inlineSticker(inst)) }
            is DirectArchive.Update.Sticker -> {
                val guid = DirectArchive.guidOf(update.messageId) ?: return
                writePending(IMessageExtra.stickerKey(guid, update.stickerGuid), inlineSticker(inst))
            }
            else -> Unit
        }
    }

    /** The sticker [inst] puts on a message, when it came inside the message, as a PNG to show ([PreviewImages.png]); null otherwise or when it cannot be read. */
    private fun inlineSticker(inst: MessageInst): ByteArray? {
        val attachment = DirectArchive.stickerAttachment(inst.message) ?: return null
        val bytes = (attachment.aType as? AttachmentType.Inline)?.v1?.takeIf { it.isNotEmpty() && it.size <= STICKER_MAX_BYTES } ?: return null
        return PreviewImages.png(bytes, PreviewImages.STICKER_SIDE)
    }

    /** [bytes] written aside under [key] in the iMessage media folder, for the main process to move into place; nothing for none. */
    private fun writePending(key: String, bytes: ByteArray?) {
        if (bytes == null) return
        val file = MediaCache.fileFor(app, key, IMessageExtra.extensionOf(key), ChatService.IMESSAGE)
        try {
            MediaCache.writeAtomically(DirectMedia.pendingInlineFile(file), bytes)
        } catch (e: java.io.IOException) {
            Log.w(TAG, "a picture was not written", e)
        }
    }

    /**
     * Hands [update] to the main process until it is stored, or given up on ([UpdateDelivery]: one
     * the main process can never store does not hold up every later one).
     */
    private suspend fun store(update: DirectArchive.Update): Delivery? {
        val settled = deliver(sink, update)
        if (settled == null) Log.w(TAG, "an update the main process could not store was skipped: ${update::class.simpleName}")
        return settled
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
                    var restored = false
                    if (session == null) {
                        _status.value = Status.Working("Starting")
                        // The optional Apple Account: what an interrupted sign-in or commit left is put
                        // right before the session is built from disk (nothing at all without one).
                        account.atStartLocked()
                        session = uniffi.rust_bridge.restoreSession(statePath, pipeline!!)
                        sessionCameUp()
                        restored = session != null
                    }
                    refreshStatus()
                    if (restored) account.sessionUpLocked()
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
        val reg = uniffi.rust_bridge.getRegState(s)
        if (reg is RegisterState.Registered) {
            handles.value = uniffi.rust_bridge.getHandles(s)
            DirectPrefs.setRegistered(app, true)
        }
        _status.value = statusOf(reg, myHandles)
    }

    /**
     * Registers this phone's number with iMessage, using the iPhone relay that [relayCode] names.
     * [phone] does the SMS part. Progress shows in [status]; once registered the background
     * service is started.
     */
    fun register(relayCode: String, relayHost: String, phone: PhoneVerifier) {
        scope.launch {
            // Registering again puts a new session in the place of the one a call is on: not during a call.
            if (FaceTimeCalls.peek()?.busy == true) {
                Log.w(TAG, "not registering again during a FaceTime call")
                return@launch
            }
            lock.withLock {
                try {
                    // Only an https:// address of a real server (the setup screen checks it too; a stored one from before is checked here).
                    val checked = RelayHost.check(relayHost)
                    val host = checked.value ?: throw ImessageError(app.getString(RelayHost.messageRes(checked.problem)))
                    initLibrary()
                    doRegister(relayCode.trim(), host, phone)
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
        // A new device: an Apple Account saved for the old one is wiped first (S10).
        account.newDeviceLocked { uniffi.rust_bridge.setIdentity(statePath, config, identity) }
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
        keepPhoneUser((result.users ?: users).firstOrNull())
        step("Starting iMessage")
        val started = uniffi.rust_bridge.startSession(
            statePath, config, conn, anisette, null, result.users ?: users, identity, null, pipeline!!,
        )
        // Never while a call is being joined on the session this replaces ([callBridge]'s guard), nor
        // while a call that began meanwhile holds the line: looked at again under the guard, and put
        // off until that call is over.
        val waited = SessionSwap.whenFree(callBridge.guard, busy = { FaceTimeCalls.peek()?.busy == true }) { session = started }
        if (waited) Log.w(TAG, "the new session waited for a FaceTime call to end")
        // Built with no account: a restart the Apple Account still had pending is not needed now.
        account.sessionReplacedLocked()
        sessionCameUp()
        refreshStatus()
    }

    /**
     * This phone's number as registered, kept encrypted ([PhoneUserStore]) so that a later change of
     * the Apple Account never needs a new text message. Never in the way of the registration.
     */
    private fun keepPhoneUser(user: uniffi.rust_bridge.IdsUser?) {
        if (user == null) return
        try {
            PhoneUserStore.of(app).save(uniffi.rust_bridge.saveUser(user))
        } catch (e: Throwable) {
            Log.w(TAG, "the phone's iMessage user was not kept (${e::class.java.simpleName})")
        }
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

    // --- FaceTime -------------------------------------------------------------------------------

    /** When the session came up ([android.os.SystemClock.elapsedRealtime]; 0: not yet). */
    @Volatile
    private var sessionUpAt = 0L

    /** The session a call is being joined on, kept from being let go of meanwhile ([CallBridge.withNativeHandle]). */
    @Volatile
    private var pinnedSession: SharedPushStateHandle? = null

    /** Asks the main process who a caller is ([DirectWire.CALLER]); set by [DirectService], with [sink]. */
    @Volatile
    var callerLookup: (suspend (String) -> CallerInfo?)? = null

    /** Tells the library this phone's addresses again when its networks change (a call needs them). */
    private val addressWatcher by lazy {
        LocalAddresses.Watcher(app) {
            scope.launch {
                if (session != null && DirectPrefs.faceTimeOn(app)) runCatching { callBridge.setLocalInterfaces() }
            }
        }
    }

    /**
     * The session is there: from now on its age tells an old event handed over again from a new
     * one, and, with FaceTime on, the library is told this phone's network addresses (before any
     * call: it takes the process down if it has to use them untold) and again whenever they change.
     */
    private fun sessionCameUp() {
        sessionUpAt = android.os.SystemClock.elapsedRealtime()
        // A call the process was stopped in (nothing told the other side then): left now, once.
        scope.launch {
            runCatching {
                com.lightdecoder.imessage.facetime.CallRecovery.leaveRemembered(
                    DirectPrefs.liveFaceTimeCall(app), FaceTimeCalls.peek()?.state?.value?.guid,
                    forget = { guid -> if (DirectPrefs.liveFaceTimeCall(app) == guid) DirectPrefs.setLiveFaceTimeCall(app, null) },
                    bridge = callBridge,
                )
            }
        }
        if (!DirectPrefs.faceTimeOn(app)) return
        try {
            addressWatcher.start()
        } catch (e: RuntimeException) {
            Log.w(TAG, "network changes not watched (${e::class.java.simpleName})")
        }
        scope.launch { runCatching { callBridge.setLocalInterfaces() } }
    }

    /** "FaceTime" was turned on or off on the iMessage screen: the phone's addresses are told from now on, or no longer watched. */
    fun faceTimeSettingChanged() {
        if (!DirectPrefs.faceTimeOn(app)) {
            runCatching { addressWatcher.stop() }
            return
        }
        if (session == null) return
        runCatching { addressWatcher.start() }
        scope.launch { runCatching { callBridge.setLocalInterfaces() } }
    }

    /**
     * The library's FaceTime functions on the live session, for [FaceTimeCalls] and
     * [com.lightdecoder.imessage.facetime.NativeCall]. None of them runs on the main thread.
     */
    internal val callBridge: CallBridge = object : CallBridge {
        override val guard = Mutex()

        override val connected: Boolean get() = session != null && isRegistered

        override val sinceStartMs: Long
            get() = sessionUpAt.takeIf { it > 0 }?.let { android.os.SystemClock.elapsedRealtime() - it } ?: 0L

        private fun live(): SharedPushStateHandle = session ?: throw ImessageError("iMessage is not connected.")

        override suspend fun handles(): List<String> = myHandles.ifEmpty { uniffi.rust_bridge.getHandles(live()) }

        override suspend fun setLocalInterfaces() {
            val s = live()
            val picked = withContext(Dispatchers.IO) { LocalAddresses.current(app) }
            // The "default" address is nothing the library uses (it only writes it to its log): not given.
            uniffi.rust_bridge.setLocalInterfaces(s, "", picked.all)
        }

        override suspend fun createFacetime(guid: String, handle: String, participants: List<String>, video: Boolean) =
            uniffi.rust_bridge.createFacetime(live(), guid, handle, participants, video)

        override suspend fun ringMembers(guid: String, handles: List<String>) =
            uniffi.rust_bridge.ringFtMembers(live(), guid, handles)

        // Never while a join runs; and not waiting forever for one that does not come back.
        override suspend fun cancelFacetime(guid: String) =
            guard.withLockWithin(GUARD_WAIT_MS) { uniffi.rust_bridge.cancelFacetime(live(), guid) }

        override suspend fun declineFacetime(guid: String) =
            guard.withLockWithin(GUARD_WAIT_MS) { uniffi.rust_bridge.declineFacetime(live(), guid) }

        override suspend fun session(guid: String): CallSessionInfo? = CallEvent.session(uniffi.rust_bridge.ftSessions(live()), guid)

        override suspend fun <T> withNativeHandle(block: (Long) -> T): T? {
            val s = session ?: return null
            // The handle is a pointer into this session: the session stays until the join it goes to returned.
            pinnedSession = s
            try {
                val handle = uniffi.rust_bridge.facetimeNativeHandle(s).toLong()
                // Straight into the join: the library takes the handle back there, once.
                return block(handle)
            } finally {
                pinnedSession = null
            }
        }

        override suspend fun capable(targets: List<String>, from: String?): List<String> = faceTimeCapable(targets, from)
    }

    /**
     * Which of [targets] (addresses as the library has them) can be called with FaceTime, as Apple
     * says ([uniffi.rust_bridge.validateTargetsFacetime]), asked from [from] when it is one of the
     * user's addresses. Throws when Apple could not be asked. None while "FaceTime" is off.
     */
    suspend fun faceTimeCapable(targets: List<String>, from: String?): List<String> {
        if (targets.isEmpty() || !DirectPrefs.faceTimeOn(app)) return emptyList()
        val s = requireSession()
        val sender = from?.takeIf { it in myHandles } ?: Handles.preferredSender(myHandles)
            ?: throw ImessageError(app.getString(R.string.imessage_direct_no_own_address))
        return call { uniffi.rust_bridge.validateTargetsFacetime(s, targets, sender) }.filter { it in targets }
    }

    /** A finished FaceTime call, for its row in the chat: handed to the main process as Apple's changes are ([store]). */
    internal suspend fun storeCall(entry: CallLogEntry) = store(
        DirectArchive.Update.Call(
            entry.guid, entry.peer, entry.outgoing, entry.video, entry.startedMs, entry.durationMs, entry.outcome.wire, entry.quiet,
            entry.conversationId.orEmpty(), entry.group, entry.members,
        ),
    )

    /** Where FaceTime stands, as last said ([faceTimeChanged]), and as last told to the main process. */
    private val faceTimeNow = MutableStateFlow(FaceTimeState.NONE)
    private val faceTimeTelling = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Where FaceTime stands, said after every change of the call there is (most say nothing new):
     * the main process's "return to call" bar is told of each real change, in order, the newest
     * winning when it cannot keep up. Nothing is told before a call ever held the line.
     */
    internal fun faceTimeChanged(state: FaceTimeState) {
        faceTimeNow.value = state
        if (!faceTimeTelling.compareAndSet(false, true)) return
        scope.launch {
            var told = FaceTimeState.NONE
            faceTimeNow.collect { now ->
                if (now == told) return@collect
                told = now
                try {
                    val s = sink ?: return@collect
                    withTimeoutOrNull(FACETIME_TELL_MS) { s.deliver(DirectArchive.Update.FaceTime(now)) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "where FaceTime stands was not told (${e::class.java.simpleName})")
                }
            }
        }
    }

    /** Waits (a little) for [start] to restore the session, for requests that arrive right after the process starts. */
    private suspend fun requireSession(): SharedPushStateHandle {
        session?.let { return it }
        start()
        return withTimeoutOrNull(SESSION_WAIT_MS) {
            while (session == null || !isRegistered) {
                // Not coming up soon: a failure (also one Apple's library retries later by itself).
                val down = _status.value.let { it is Status.Failed || it is Status.Retrying || it is Status.LibraryMissing || it is Status.NotRegistered }
                if (down && started) break
                delay(200)
            }
            session
        }?.takeIf { isRegistered } ?: throw ImessageError("iMessage is not connected.")
    }

    // --- requests from the main process ---------------------------------------------------------

    /**
     * The conversation with [recipient] if that address is on iMessage: as the archive should
     * store it (names are the addresses; the main process puts contact names in). Null when Apple
     * says it is not (or it is no address at all). Throws when Apple could not be asked (not
     * connected, no address of the user's own to ask from, Apple's lookup failed): that says
     * nothing about whether they have iMessage.
     */
    suspend fun reach(recipient: String): ConversationDto? {
        val to = Handles.normalize(recipient) ?: return null
        val s = requireSession()
        val from = Handles.preferredSender(myHandles) ?: throw ImessageError(app.getString(R.string.imessage_direct_no_own_address))
        val reachable = uniffi.rust_bridge.validateTargets(s, listOf(to), from)
        if (to !in reachable) return null
        return ConversationDto(
            id = DirectArchive.conversationId(listOf(to, from), myHandles.toSet(), null),
            name = Handles.display(to),
            type = DirectArchive.TRANSPORT,
            defaultOutgoingId = from,
            participants = listOf(
                ParticipantDto(id = to, name = Handles.display(to), number = Handles.display(to), formattedNumber = Handles.display(to)),
                ParticipantDto(id = from, number = Handles.display(from), formattedNumber = Handles.display(from), isMe = true),
            ),
            otherParticipantIds = listOf(to),
        )
    }

    /**
     * Sends a message and returns its GUID, timestamp and attachment parts once Apple confirmed
     * it: [text], then [attachments] (each uploaded first), an inline reply to [replyGuid] (part
     * [replyPart]), sent with [effect] (Apple's effect id), [voice] when it is a recorded audio
     * message. With [app] it is that iMessage app's new card (a new game): its balloon, with
     * [attachments] as its picture. [runs] format the text (bold, a text effect, ...; [TextRuns]),
     * [mentions] name the members it mentions ([Mentions]), and [link] is its link's preview, sent
     * as an iPhone sends one ([DirectMessages.linkMeta]).
     */
    suspend fun send(
        target: DirectWire.Target,
        text: String,
        replyGuid: String?,
        replyPart: String?,
        effect: String?,
        attachments: List<DirectWire.Attachment>,
        voice: Boolean,
        app: IMessageExtra.App? = null,
        runs: List<TextRun> = emptyList(),
        scheduledMs: Long? = null,
        mentions: List<Mention> = emptyList(),
        link: DirectWire.LinkPreviewRequest? = null,
        transfer: String? = null,
        onProgress: ((JSONObject) -> Unit)? = null,
    ): DirectWire.SendReply = transfers.run(transfer) { handle ->
        val s = requireSession()
        val (conversation, from) = conversationOf(target)
        val uploaded = attachments.mapIndexed { i, a -> upload(s, a, uploadProgress(onProgress, i, attachments.size)) }
        val outgoing = if (app != null) DirectMessages.appParts(app, uploaded, move = false) else DirectMessages.outgoing(text, uploaded, runs, mentions)
        // The preview's picture, as the main process made it (a small JPEG): never more than a preview's.
        val linkMeta = link?.takeIf { app == null }?.let { l ->
            val image = l.imagePath?.let(::File)?.takeIf { it.isFile && it.length() in 1..LinkPreviews.MAX_IMAGE_BYTES }?.readBytes()
            DirectMessages.linkMeta(l, image)
        }
        val message = NormalMessage(
            outgoing.parts,
            effect, replyGuid, replyGuid?.let { replyPart ?: "0:0:0" }, MessageType.IMessage,
            null, app?.let { DirectMessages.extensionApp(it) }, linkMeta, voice,
            // Send Later: Apple's servers send it then ([SendLater]).
            scheduledMs?.let { ScheduleMode(it.toULong(), true) }, null,
        )
        val inst = uniffi.rust_bridge.newMsg(conversation, from, PushChatMessage.Message(message))
        // From here on it may be with Apple: no longer stopped ([cancel] answers so).
        if (!handle.commit()) throw Transfers.Stopped()
        sendAndConfirm(s, inst, Sent.MESSAGE)
        DirectWire.SendReply(inst.id, inst.sentTimestamp.toLong().takeIf { it > 0 } ?: System.currentTimeMillis(), outgoing.attachments)
    }

    /** The transfers the user may stop ([DirectWire.CANCEL]): sends until they go to Apple, downloads the user asked for. */
    private val transfers = Transfers()

    /** Stops the transfer [key] ([DirectWire.CANCEL]): true when it was stopped; false when there is none, or a send is already with Apple. */
    fun cancel(key: String): Boolean = transfers.cancel(key)

    /** The progress of attachment [part] of [parts] while it uploads, throttled, for [onProgress] (none without it). */
    private fun uploadProgress(onProgress: ((JSONObject) -> Unit)?, part: Int, parts: Int): ProgressThrottle? =
        onProgress?.let { emit -> ProgressThrottle { done, total -> emit(DirectWire.progress(done, total, part, parts)) } }

    /** The library's callback for one transfer, made of [p] (which catches whatever its parts throw). */
    private fun callbackOf(p: TransferProgress) = object : uniffi.rust_bridge.AttachmentProgressCallback {
        override fun onProgress(transferred: ULong, total: ULong) = p.onProgress(transferred, total)
    }

    /**
     * Sends an iMessage app's update (a game's move) to the session message [rootGuid] started, as
     * OpenBubbles does: [app]'s new balloon, with the picture at [imagePath] (uploaded with MMCS) if
     * there is a new one. Returns the update's GUID, time and picture part once Apple confirmed it.
     */
    suspend fun extension(
        target: DirectWire.Target,
        rootGuid: String,
        isMeta: Boolean,
        app: IMessageExtra.App,
        imagePath: String?,
    ): DirectWire.SendReply {
        val s = requireSession()
        val (conversation, from) = conversationOf(target)
        val image = imagePath?.let { upload(s, DirectWire.Attachment(it, APP_IMAGE_MIME, APP_IMAGE_NAME)) }
        val parts = DirectMessages.appParts(app, listOfNotNull(image), move = true)
        val inst = uniffi.rust_bridge.newMsg(conversation, from, PushChatMessage.React(DirectMessages.appUpdate(rootGuid, app, parts.parts, isMeta)))
        sendAndConfirm(s, inst, Sent.MESSAGE)
        return DirectWire.SendReply(inst.id, inst.sentTimestamp.toLong().takeIf { it > 0 } ?: System.currentTimeMillis(), parts.attachments)
    }

    /** Puts the file to send on Apple's servers ([DirectMedia.MAX_SEND_BYTES] at most): the attachment the message carries. */
    private suspend fun upload(s: SharedPushStateHandle, a: DirectWire.Attachment, ui: ProgressThrottle? = null): MessageAttachment {
        val file = File(a.path)
        if (!file.isFile || file.length() == 0L) throw ImessageError(app.getString(R.string.imessage_direct_attachment_unreadable))
        if (file.length() > DirectMedia.MAX_SEND_BYTES) {
            throw ImessageError(app.getString(R.string.imessage_direct_attachment_too_big, (DirectMedia.MAX_SEND_BYTES shr 20).toInt()))
        }
        val mime = a.mime.ifBlank { DirectMedia.mimeOfName(a.name) ?: DirectMedia.OCTET_STREAM }
        val name = a.name.ifBlank { DirectMedia.nameFor(mime) }
        // A fresh callback for this one transfer, as the library asks; none without anyone to tell.
        val progress = ui?.let { callbackOf(TransferProgress(maxBytes = null, ui = it)) }
        val uploaded = try {
            call { uniffi.rust_bridge.uploadMmcsAttachment(s, file.path, mime, DirectMedia.utiFor(mime), name, progress) }
        } finally {
            ui?.finish()
        }
        // With the type and name the main process stored it under.
        return uploaded.copy(mime = mime, name = name)
    }

    /**
     * Takes back the Send Later message [guid] before Apple sends it, as an iPhone's "Cancel Send
     * Later" does: rustpush's [PushChatMessage.Unschedule], sent as that message (its queue on
     * Apple's servers is found by its GUID; the user's other devices are told to delete it).
     */
    suspend fun unschedule(target: DirectWire.Target, guid: String) {
        val s = requireSession()
        val (conversation, from) = conversationOf(target)
        val inst = uniffi.rust_bridge.newMsg(conversation, from, PushChatMessage.Unschedule)
            .copy(id = guid, sentTimestamp = System.currentTimeMillis().toULong())
        sendAndConfirm(s, inst, Sent.UNSCHEDULE)
    }

    /**
     * Sets the chat's background (iOS 26) to the JPEG at [path], or to [style] (a gradient or a
     * dynamic one), or takes it off (neither), as of [version], as OpenBubbles does: the photo made
     * into a poster ([BackgroundPosters.photoPoster]; [BackgroundPosters.drawnPoster] for a style),
     * packed, uploaded to Apple's file servers and the message naming it sent. A group is told with
     * its guid; in a chat with one person the other person is told with none (it is the chat with
     * its sender for them) and the user's other devices with that person's address.
     */
    suspend fun setBackground(target: DirectWire.Target, path: String?, version: Long, style: BackgroundStyle? = null) {
        val s = requireSession()
        val (conversation, from) = conversationOf(target)
        val file = if (style != null || path != null) uploadPoster(s, path?.let(::File), style) else null
        val now = System.currentTimeMillis().toULong()
        fun message(chatId: String?) = PushChatMessage.SetTranscriptBackground(
            uniffi.rust_bridge.transcriptBackgroundFromMmcs(file, version.toULong(), chatId),
        )
        val guid = target.groupGuid
        if (guid != null || !target.isOneToOne) {
            val inst = uniffi.rust_bridge.newMsg(conversation, from, message(guid ?: conversation.senderGuid)).copy(sentTimestamp = now)
            sendAndConfirm(s, inst, Sent.BACKGROUND)
            return
        }
        val peer = target.peer!!
        val toThem = conversation.copy(participants = conversation.participants.filter { it != from && it !in myHandles }.ifEmpty { listOf(peer) })
        sendAndConfirm(s, uniffi.rust_bridge.newMsg(toThem, from, message(null)).copy(sentTimestamp = now), Sent.BACKGROUND)
        // The user's other devices: a copy naming the chat. Not waited for, nor in the way if it does not go.
        try {
            val toMe = conversation.copy(participants = listOf(from), afterGuid = null)
            sendOnly(s, uniffi.rust_bridge.newMsg(toMe, from, message(Handles.display(peer))).copy(sentTimestamp = now))
        } catch (e: Exception) {
            Log.w(TAG, "the user's other devices were not told of the new background", e)
        }
    }

    /**
     * Renames the group [target] to [name] ("" takes its name off), as OpenBubbles' `renameChat`
     * does: rustpush's [PushChatMessage.RenameMessage], to its members (and the user's other
     * devices). Returns its GUID once Apple confirmed it.
     */
    suspend fun rename(target: DirectWire.Target, name: String): String {
        val s = requireSession()
        val (conversation, from) = conversationOf(requireGroup(target))
        val inst = uniffi.rust_bridge.newMsg(conversation, from, PushChatMessage.RenameMessage(ChatRenameMessage(name)))
            .copy(sentTimestamp = System.currentTimeMillis().toULong())
        sendAndConfirm(s, inst, Sent.RENAME)
        return inst.id
    }

    /**
     * Changes the group [target]'s members to [participants] (the user's own address among them, or
     * not: the user leaves), as of [groupVersion], as OpenBubbles' `chatParticipant` and `leaveChat`
     * do: rustpush's [PushChatMessage.ChangeParticipants] to its members as they were, and the ones
     * added (rustpush adds them itself). Returns its GUID once Apple confirmed it.
     */
    suspend fun members(target: DirectWire.Target, participants: List<String>, groupVersion: Long): String {
        val s = requireSession()
        val group = requireGroup(target)
        val (conversation, from) = conversationOf(group)
        val newParticipants = DirectMessages.newParticipants(participants, group.from, from, myHandles.toSet())
        if (newParticipants.none { it != from }) throw ImessageError(app.getString(R.string.imessage_group_nobody))
        val change = uniffi.rust_bridge.ChangeParticipantMessage(newParticipants, groupVersion.coerceAtLeast(0).toULong())
        val inst = uniffi.rust_bridge.newMsg(conversation, from, PushChatMessage.ChangeParticipants(change))
            .copy(sentTimestamp = System.currentTimeMillis().toULong())
        sendAndConfirm(s, inst, Sent.MEMBERS)
        return inst.id
    }

    /**
     * Sets the group [target]'s photo to the square JPEG at [path], or takes it off ([path] null), as
     * of [groupVersion], as OpenBubbles' `setChatIcon` and `deleteChatIcon` do: the picture uploaded
     * to Apple's file servers (as a background's poster is), and rustpush's [PushChatMessage.IconChange]
     * naming it. Returns its GUID once Apple confirmed it.
     */
    suspend fun groupPhoto(target: DirectWire.Target, path: String?, groupVersion: Long): String {
        val s = requireSession()
        val (conversation, from) = conversationOf(requireGroup(target))
        val file = path?.let { uploadGroupPhoto(s, File(it)) }
        val change = uniffi.rust_bridge.IconChangeMessage(file, groupVersion.coerceAtLeast(0).toULong())
        val inst = uniffi.rust_bridge.newMsg(conversation, from, PushChatMessage.IconChange(change))
            .copy(sentTimestamp = System.currentTimeMillis().toULong())
        sendAndConfirm(s, inst, Sent.GROUP_PHOTO)
        return inst.id
    }

    /** [target], when it is a group (with its guid): only a group has a name, members and a photo to change. */
    private fun requireGroup(target: DirectWire.Target): DirectWire.Target {
        if (target.groupGuid.isNullOrBlank()) throw ImessageError(app.getString(R.string.imessage_group_not_a_group))
        return target
    }

    /** The group photo at [jpeg] on Apple's file servers: where it is, and its key. */
    private suspend fun uploadGroupPhoto(s: SharedPushStateHandle, jpeg: File): MmcsFile {
        if (!jpeg.isFile || jpeg.length() == 0L || jpeg.length() > GroupPhotos.MAX_DOWNLOAD_BYTES) {
            throw ImessageError(app.getString(R.string.imessage_group_photo_unreadable))
        }
        val uploaded = call { uniffi.rust_bridge.uploadMmcsAttachment(s, jpeg.path, APP_IMAGE_MIME, GROUP_PHOTO_UTI, GROUP_PHOTO_NAME, null) }
        return (uploaded.aType as? AttachmentType.Mmcs)?.v1?.singleOrNull()
            ?: throw ImessageError(app.getString(R.string.imessage_group_photo_upload))
    }

    /** The photo at [jpeg], or [style] (a gradient or dynamic one), as a background poster on Apple's file servers. */
    private suspend fun uploadPoster(s: SharedPushStateHandle, jpeg: File?, style: BackgroundStyle?): MmcsFile =
        OutgoingBackgrounds(
            app,
            object : OutgoingBackgrounds.Natives {
                override fun nsNull(): ByteArray? = uniffi.rust_bridge.nsNull()
                override suspend fun pack(poster: SimplifiedTranscriptPoster): ByteArray = call { uniffi.rust_bridge.packTranscriptPoster(poster) }
                override suspend fun upload(path: String): MmcsFile? {
                    val uploaded = call { uniffi.rust_bridge.uploadMmcsAttachment(s, path, POSTER_MIME, POSTER_UTI, POSTER_NAME, null) }
                    return (uploaded.aType as? AttachmentType.Mmcs)?.v1?.singleOrNull()
                }
            },
        ).upload(jpeg, style)

    /** Retracts [guid]; the main process has checked the 2-minute window. */
    suspend fun unsend(target: DirectWire.Target, guid: String, part: Int) {
        val s = requireSession()
        val (conversation, from) = conversationOf(target)
        val inst = uniffi.rust_bridge.newMsg(conversation, from, PushChatMessage.Unsend(UnsendMessage(guid, part.toULong())))
        sendAndConfirm(s, inst, Sent.UNSEND)
    }

    /** Part [part] of the user's message [guid] now reads [text], formatted with [runs], mentioning [mentions]; the main process checked the limits. */
    suspend fun edit(target: DirectWire.Target, guid: String, part: Int, text: String, runs: List<TextRun> = emptyList(), mentions: List<Mention> = emptyList()) {
        val s = requireSession()
        val (conversation, from) = conversationOf(target)
        val inst = uniffi.rust_bridge.newMsg(
            conversation, from, PushChatMessage.Edit(EditMessage(guid, part.toULong(), DirectMessages.editParts(text, runs, mentions))),
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
     * and with read receipts on, the other person of a one-to-one chat: as the chat's own choice
     * says ([receipts], its details' "Send Read Receipts"), else as the iMessage screen's setting
     * does. Apple does not answer it.
     */
    suspend fun read(target: DirectWire.Target, guid: String, receipts: Boolean? = null) {
        val s = requireSession()
        val (conversation, from) = conversationOf(target)
        val to = DirectMessages.readReceiptParticipants(target, receipts ?: DirectPrefs.sendReadReceipts(app))
        val receipt = conversation.copy(participants = (to + from).distinct(), afterGuid = null)
        sendOnly(s, uniffi.rust_bridge.newMsg(receipt, from, PushChatMessage.Read).copy(id = guid))
    }

    /**
     * "Mark as Unread": the user's other devices are told the chat [target] is unread again, as
     * OpenBubbles' `markUnread` tells them: rustpush's [PushChatMessage.MarkUnread] to the user's own
     * address alone (the chat named by its guid, as the user's devices know it), carrying the GUID
     * of the message it is unread from ([guid]). Apple does not answer it.
     */
    suspend fun markUnread(target: DirectWire.Target, guid: String) {
        val s = requireSession()
        val (conversation, from) = conversationOf(target)
        val toMe = conversation.copy(participants = listOf(from), afterGuid = null)
        sendOnly(s, uniffi.rust_bridge.newMsg(toMe, from, PushChatMessage.MarkUnread).copy(id = guid))
    }

    /**
     * "Delete on All Devices": the user's own devices (only them: the user's own address alone) are
     * told that the chat [target] went to Recently Deleted, came back or was deleted for good ([op],
     * [RecycleRules]), or that its messages [guids] were, as OpenBubbles tells them. Apple does not
     * answer it.
     */
    suspend fun recycle(target: DirectWire.Target, op: String, guids: List<String>?) {
        val s = requireSession()
        val (_, from) = conversationOf(target)
        val chatGuid = target.peer?.let(chatGuids::guidFor)
        val now = System.currentTimeMillis()
        val (toMe, message) = try {
            RecycleRules.outgoing(target, op, guids, from, myHandles, chatGuid, now)
        } catch (e: IllegalArgumentException) {
            throw ImessageError("Nothing to delete")
        }
        sendOnly(s, uniffi.rust_bridge.newMsg(toMe, from, message).copy(sentTimestamp = now.toULong()))
    }

    /**
     * "Report Junk": the chat [target]'s [messages] are reported to Apple as junk, as OpenBubbles'
     * `markAsSpam` reports them (rustpush's `report_spam`, from the address the chat is carried on:
     * one of the user's registered ones). Each goes with its text and the attachments the library
     * can still describe. Throws [ImessageError] when Apple did not take the report.
     */
    suspend fun reportJunk(target: DirectWire.Target, messages: List<DirectWire.JunkMessage>) {
        // As an iPhone offers it: a chat with one person, and only what that person sent.
        val peer = junkPeer(target) ?: throw ImessageError(app.getString(R.string.imessage_junk_not_offered))
        if (messages.isEmpty()) throw ImessageError(app.getString(R.string.imessage_junk_nothing))
        val s = requireSession()
        val (_, from) = conversationOf(target)
        val reports = messages.mapNotNull { m ->
            val sender = Handles.normalize(m.sender)?.takeIf { it == peer || com.lightdecoder.imessage.IMessageAddress.same(it, peer) }
                ?: return@mapNotNull null
            // One the library cannot read again goes without it: the rest of the report still goes.
            val attachments = m.attachments.mapNotNull { descriptor ->
                try {
                    uniffi.rust_bridge.restoreAttachment(descriptor)
                } catch (e: Exception) {
                    null
                }
            }
            uniffi.rust_bridge.ReportMessage(m.guid, sender, m.conversationSize.coerceAtLeast(1).toUInt(), DirectMessages.junkParts(m.text, attachments), m.timeOfMessage)
        }
        if (reports.isEmpty()) throw ImessageError(app.getString(R.string.imessage_junk_nothing))
        call { uniffi.rust_bridge.reportMessages(s, from, reports) }
    }

    /**
     * The user's devices changed: the people of [groups] (each the people one of the user's
     * addresses writes to in the recent chats, as the main process found them) are told, as
     * OpenBubbles' `invalidatePeerCaches` tells them: rustpush's [PushChatMessage.PeerCacheInvalidate]
     * from that address. Apple does not answer it; one that does not go is only logged.
     */
    suspend fun invalidatePeers(groups: List<DirectWire.PeerGroup>) {
        if (groups.isEmpty()) return
        val s = requireSession()
        val mine = myHandles.toSet()
        for (group in groups) {
            // Only from one of the user's own addresses.
            val from = group.from.takeIf { it in mine } ?: continue
            val people = (listOf(from) + group.participants).distinct()
            if (people.none { it != from }) continue
            try {
                sendOnly(s, uniffi.rust_bridge.newMsg(ConversationData(people, null, null, null), from, PushChatMessage.PeerCacheInvalidate))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the people of the recent chats were not told of the new device (${e::class.java.simpleName})")
            }
        }
    }

    /**
     * Fetches the attachment [descriptor] (as the library saved it, `saveAttachment`) into the
     * file [path], never more than [maxBytes] of it, through a partial file of its own so a
     * half-written file is never taken for the whole. Two downloads of the same file (one asked again
     * after the first took too long to answer) each write their own and rename it into place whole.
     * One nobody asked for ([automaticMs]: a sticker, a group's photo, fetched once the main process
     * took the change that names it) comes only from Apple's own file servers
     * ([DirectMedia.isAppleFileServer]) and is given that long at most. Returns its size.
     */
    suspend fun download(
        descriptor: String,
        path: String,
        maxBytes: Long = DirectMedia.MAX_DOWNLOAD_BYTES,
        automaticMs: Long? = null,
        transfer: String? = null,
        onProgress: ((JSONObject) -> Unit)? = null,
    ): Long {
        val cap = maxBytes.coerceIn(1L, DirectMedia.MAX_DOWNLOAD_BYTES)
        // One the user asked for may be stopped and shows its progress; one nobody asked for, neither.
        if (automaticMs == null) {
            val ui = onProgress?.let { emit -> ProgressThrottle { done, total -> emit(DirectWire.progress(done, total)) } }
            return transfers.run(transfer) { downloadInto(descriptor, path, cap, automatic = false, ui = ui) }
        }
        return withTimeoutOrNull(automaticMs) { downloadInto(descriptor, path, cap, automatic = true) }
            ?: throw ImessageError(app.getString(R.string.imessage_direct_attachment_not_saved))
    }

    private suspend fun downloadInto(descriptor: String, path: String, maxBytes: Long, automatic: Boolean, ui: ProgressThrottle? = null): Long {
        val attachment = call { uniffi.rust_bridge.restoreAttachment(descriptor) }
        val file = File(path)
        file.parentFile?.mkdirs()
        // Named as the media cache names its partial files, so one left by a crash is cleaned up.
        val partial = File.createTempFile(file.name + ".", MediaCache.PART_SUFFIX, file.parentFile)
        try {
            when (val type = attachment.aType) {
                is AttachmentType.Inline -> {
                    if (type.v1.size > maxBytes) throw tooBigToDownload(maxBytes)
                    partial.writeBytes(type.v1)
                }
                is AttachmentType.Mmcs -> {
                    val largest = type.v1.maxByOrNull { it.size } ?: throw ImessageError(app.getString(R.string.imessage_direct_attachment_no_file))
                    if (automatic && !DirectMedia.isAppleFileServer(largest.url)) {
                        Log.w(TAG, "a file not on Apple's file servers was not fetched")
                        throw ImessageError(app.getString(R.string.imessage_direct_attachment_not_saved))
                    }
                    val s = requireSession()
                    // The library makes the file itself, as it does for OpenBubbles: only its name is taken here.
                    partial.delete()
                    downloadCapped(s, largest, partial.path, maxBytes, ui)
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

    private fun tooBigToDownload(maxBytes: Long = DirectMedia.MAX_DOWNLOAD_BYTES) =
        TooLarge(app.getString(R.string.imessage_direct_attachment_download_too_big, (maxBytes shr 20).toInt()))

    /**
     * Fetches [file] into [path] with the library, never more than [maxBytes] of it, whatever size
     * the sender said it has: refused at once when it says more; else stopped once more has come,
     * as the library counts it or as the file on disk grows, the partial file deleted. Throws
     * [ImessageError] then, as for any download that fails.
     */
    private suspend fun downloadCapped(s: SharedPushStateHandle, file: MmcsFile, path: String, maxBytes: Long, ui: ProgressThrottle? = null) {
        if (file.size > maxBytes.toULong()) throw tooBigToDownload(maxBytes)
        val target = File(path)
        val fetching = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?>(null)
        // The cap and the screen's progress in one callback, which never throws into the library.
        val progress = TransferProgress(maxBytes, onOver = { fetching.get()?.cancel() }, ui = ui)
        val over = progress.over
        try {
            coroutineScope {
                val fetch = async {
                    fetching.set(coroutineContext[kotlinx.coroutines.Job])
                    try {
                        call { uniffi.rust_bridge.downloadMmcsFile(s, file, path, callbackOf(progress)) }
                    } finally {
                        ui?.finish()
                    }
                }
                val watch = launch {
                    while (isActive) {
                        if (target.length() > maxBytes) {
                            over.set(true)
                            fetch.cancel()
                            break
                        }
                        delay(DOWNLOAD_WATCH_MS)
                    }
                }
                try {
                    fetch.await()
                } finally {
                    watch.cancel()
                }
            }
        } catch (e: CancellationException) {
            if (!over.get()) throw e
        } finally {
            // Also one that grew past it after it was last looked at.
            if (target.length() > maxBytes) over.set(true)
            if (over.get()) target.delete()
        }
        if (over.get()) throw tooBigToDownload(maxBytes)
    }

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
        UNSCHEDULE(R.string.imessage_direct_unschedule_not_confirmed, R.string.imessage_direct_unschedule_refused),
        BACKGROUND(R.string.imessage_direct_background_not_confirmed, R.string.imessage_direct_background_refused),
        RENAME(R.string.imessage_direct_group_not_confirmed, R.string.imessage_direct_group_refused),
        MEMBERS(R.string.imessage_direct_group_not_confirmed, R.string.imessage_direct_group_refused),
        GROUP_PHOTO(R.string.imessage_direct_group_not_confirmed, R.string.imessage_direct_group_refused),
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
                ?: throw Unconfirmed(app.getString(what.notConfirmed, (CONFIRM_TIMEOUT_MS / 1000).toInt()))
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
                        _status.value = (status.value as? Status.Registered)?.takeIf { event.v1 is RegisterState.Registered }
                            ?: statusOf(event.v1, myHandles)
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

        /**
         * Whether a message is followed by its delivery receipt once the main process took it as
         * [delivery] (null: nothing was handed over, or it was given up on): always, but for one the
         * main process dropped as from someone the user blocked ([Delivery.BLOCKED]).
         */
        internal fun acknowledgesAfter(delivery: Delivery?): Boolean = delivery?.acknowledges ?: true

        /**
         * [update] into the main process ([store]; nothing for [DirectArchive.Update.Ignore]), then the
         * delivery receipt ([acknowledge]), unless the main process dropped it as from someone the user
         * blocked ([acknowledgesAfter]). What [store] said, for the caller.
         */
        internal suspend fun storeThenAcknowledge(
            update: DirectArchive.Update,
            store: suspend (DirectArchive.Update) -> Delivery?,
            acknowledge: suspend () -> Unit,
        ): Delivery? {
            val delivery = if (update != DirectArchive.Update.Ignore) store(update) else null
            if (acknowledgesAfter(delivery)) acknowledge()
            return delivery
        }

        /** Hands [update] to the main process through [sink] until it is settled ([UpdateDelivery.settle]). */
        internal suspend fun deliver(sink: Sink?, update: DirectArchive.Update): Delivery? = UpdateDelivery.settle({
            try {
                sink?.deliver(update) ?: Delivery.UNREACHABLE
            } catch (e: Exception) {
                Log.w(TAG, "main process did not take an update", e)
                Delivery.FAILED
            }
        })

        /** The name OpenBubbles gives an iMessage app's picture. */
        const val APP_IMAGE_NAME = "jpeg-image.jpeg"
        const val APP_IMAGE_MIME = "image/jpeg"

        /** How a chat background's poster (a zip) is uploaded. */
        private const val POSTER_MIME = "application/zip"
        private const val POSTER_UTI = "public.zip-archive"
        private const val POSTER_NAME = "transcriptBackground.zip"

        /** The largest sticker fetched to show on a message (an iPhone's is a small HEIC or PNG). */
        const val STICKER_MAX_BYTES = 2L * 1024 * 1024

        /** How a group photo is uploaded (a square JPEG; Apple keeps it as the transfer "GroupPhotoImage"). */
        private const val GROUP_PHOTO_UTI = "public.jpeg"
        private const val GROUP_PHOTO_NAME = "GroupPhotoImage.jpg"

        /** How long a delivery receipt may hold up the next message from Apple. */
        private const val RECEIPT_TIMEOUT_MS = 15_000L

        /** How often a download's file is looked at while it comes ([downloadCapped]). */
        private const val DOWNLOAD_WATCH_MS = 250L

        /** Added to the error when Apple's text gateway stays silent and the SIM route is not allowed yet. */
        const val NEEDS_SIM = "If this keeps happening, your carrier may verify the number with the SIM instead of a text."
        private const val SESSION_WAIT_MS = 20_000L

        /** How long the main process is given to take the Apple Account's state (it also asks for it). */
        private const val ACCOUNT_TELL_MS = 3_000L

        /** The main process is given this long to take where FaceTime stands. */
        private const val FACETIME_TELL_MS = 3_000L

        /** How long a restart waits for the event being read ([quiesce]). */
        private const val QUIESCE_MS = 3_000L

        /** What is done with each of Apple's events ([take]). */
        internal enum class EventRoute {
            /** A message (or a group's event): read and stored. */
            MESSAGE,

            /** A FaceTime call's event: handed to the calls. */
            FACETIME,

            /**
             * Acknowledged unread, and never logged: also the ones an Apple Account brings (other
             * devices' sign-in requests and their teardown, two-factor notices, the iCloud Keychain's,
             * Find My locations and shares, shared albums): nothing here acts on them.
             */
            ACKNOWLEDGE,
        }

        internal fun routeOf(event: PushEvent): EventRoute = when (event) {
            is PushEvent.Message -> EventRoute.MESSAGE
            is PushEvent.FaceTime -> EventRoute.FACETIME
            else -> EventRoute.ACKNOWLEDGE
        }

        /** The public registration relay the iPhone relay apps use by default, and its public token. */
        const val DEFAULT_RELAY_HOST = "https://registration-relay.beeper.com"
        const val DEFAULT_RELAY_TOKEN = "5c175851953ecaf5209185d897591badb6c3e712"

        /**
         * What the screens show for Apple's registration state [reg]. A failure Apple's library
         * retries by itself ([RegisterState.Failed.retryWait] set) is not "not working", as in
         * OpenBubbles: only one it does not retry is.
         */
        fun statusOf(reg: RegisterState, handles: List<String>): Status = when (reg) {
            is RegisterState.Registered -> Status.Registered(handles)
            is RegisterState.Registering -> Status.Working("Registering with Apple")
            is RegisterState.Failed -> reg.retryWait?.let { Status.Retrying(reg.error, it.toLong()) } ?: Status.Failed(reg.error)
        }

        /** [status] in a few words, for the logs and the problem report. */
        fun describe(status: Status): String = when (status) {
            is Status.Registered -> "connected to Apple"
            is Status.Working -> "working: ${status.step}"
            is Status.Retrying -> "not connected, Apple's library tries again in ${status.retryWaitSec} s: ${status.error}"
            is Status.Failed -> "not working: ${status.error}"
            is Status.LibraryMissing -> "library files missing"
            Status.NotRegistered -> "not registered"
        }

        fun readable(e: Throwable): String = when (e) {
            is ImessageError -> e.message ?: "Unknown error"
            is RustBridgeException.Other -> e.v1
            else -> e.message ?: e::class.java.simpleName
        }

        @Volatile
        private var instance: DirectEngine? = null

        /**
         * Whose messages a junk report of the chat [target] may carry: its one other person's (their
         * address as the library has it), as an iPhone offers "Report Junk" only there; null for a
         * group or a chat with more people, of which nothing is reported.
         */
        internal fun junkPeer(target: DirectWire.Target): String? =
            target.peer?.takeIf { target.groupGuid.isNullOrBlank() }?.let(Handles::normalize)

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
