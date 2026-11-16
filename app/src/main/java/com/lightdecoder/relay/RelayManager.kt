package com.lightdecoder.relay

import android.content.Context
import android.net.Uri
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.ContactRow
import com.lightdecoder.archive.MediaCache
import com.lightdecoder.archive.MediaRow
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.capture.StickerCapture
import com.lightdecoder.heif.ImageSniffer
import com.lightdecoder.inbox.InboxItem
import com.lightdecoder.inbox.InboxPrefs
import com.lightdecoder.inbox.InboxStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Where the link stands, as the UI shows it.
 *
 * [code] is a bridge error code (e.g. "phone_not_responding") when the state
 * has a cause; [detail] is a short, content-free hint.
 */
data class RelayState(val phase: Phase, val code: String = "", val detail: String = "") {
    enum class Phase {
        UNPAIRED, PAIRING, DISCONNECTED, CONNECTING, CONNECTED, ACTIVE, ACTIVE_ELSEWHERE,
        RECONNECTING, PHONE_OFFLINE, LOGGED_OUT, COOKIES_INVALID, ERROR,
    }

    /** True while messages can be sent and fetched. */
    val isOnline: Boolean get() = phase == Phase.CONNECTED || phase == Phase.ACTIVE
}

/** How far a history import has got. [error] is a bridge error code if it stopped early. */
data class BackfillProgress(
    val running: Boolean,
    val conversationsDone: Int,
    val conversationsTotal: Int,
    val messages: Int,
    val error: String = "",
)

/**
 * The Google Messages link at runtime: one per process ([get]).
 *
 * It owns the [RelayBridge] (created lazily off the main thread, since that
 * loads the Go library), turns the bridge's events into archive writes and
 * [state], runs the outbox and history imports, and decides when to connect.
 *
 * Threads:
 *  - Bridge callbacks arrive on Go's dispatcher thread and are handed to one
 *    event thread, in order, so Go never waits on the database for long.
 *  - New sends go through one intake thread, so they keep their order.
 *  - Everything that talks to the phone runs on [Dispatchers.IO]; nothing
 *    here calls the bridge on the main thread.
 *
 * Suspend functions that act for the user ([useHere], [refreshConversations],
 * [loadOlder], [requestFullSize], [react], [deleteMessage], [setArchived],
 * [setMuted], [deleteConversation], [startConversation], and pairing) throw
 * [RelayException] when the phone or Google says no; [markRead], [unpair] and
 * [mediaFile] do not.
 *
 * Logs (RelayLog) only ever get kinds and outcomes: never message text,
 * names, numbers, the pairing emoji, cookies or keys.
 */
class RelayManager private constructor(
    context: Context,
    private val repo: ArchiveRepository,
    private val timing: Timing,
) {
    /** Delays, as one object so tests can shorten them. */
    internal data class Timing(
        /** The connector's backoff for sends the phone asked to retry. */
        val outboxRetryDelaysMs: List<Long> = listOf(3_000, 8_000, 20_000),
        /** Longest wait for a connection's first settings event before sending anyway. */
        val settingsGateMs: Long = 15_000,
        val backfillPagePauseMs: Long = 400,
        val backfillConversationPauseMs: Long = 800,
        /** How long an import waits for the link to come online before giving up. */
        val backfillWaitOnlineMs: Long = 60_000,
        val typingExpiryMs: Long = 6_000,
        val typingThrottleMs: Long = 5_000,
        /** Pauses before reconnecting after a fatal error, growing per attempt. */
        val reconnectDelaysMs: List<Long> = listOf(15_000, 30_000, 60_000, 120_000, 300_000, 900_000),
        /** last_event_ts is written at most this often. */
        val healthEventIntervalMs: Long = 10_000,
    )

    private val app: Context = context.applicationContext

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            RelayLog.add(app, "Internal error: ${t.javaClass.simpleName}")
        }
    )
    private val events: ExecutorService = Executors.newSingleThreadExecutor(named("relay-events"))
    private val intake: ExecutorService = Executors.newSingleThreadExecutor(named("relay-intake"))

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val mediaDispatcher = Dispatchers.IO.limitedParallelism(2)

    private val _state = MutableStateFlow(
        RelayState(if (SessionVault.exists(app)) RelayState.Phase.DISCONNECTED else RelayState.Phase.UNPAIRED)
    )
    val state: StateFlow<RelayState> = _state.asStateFlow()

    private val _typing = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** Conversation id → names of the people typing there now. Entries expire after 6 s. */
    val typing: StateFlow<Map<String, Set<String>>> = _typing.asStateFlow()

    private val _backfill = MutableStateFlow(BackfillProgress(running = false, 0, 0, 0))
    val backfill: StateFlow<BackfillProgress> = _backfill.asStateFlow()

    private val _settings = MutableStateFlow<SettingsDto?>(null)

    /** The phone's settings, once it has sent them on this run of the app. */
    val settings: StateFlow<SettingsDto?> = _settings.asStateFlow()

    // --- bridge -------------------------------------------------------------------

    private val bridgeLock = Any()

    @Volatile
    private var bridge: RelayBridge? = null

    @Volatile
    private var paired: Boolean = _state.value.phase != RelayState.Phase.UNPAIRED

    private val sink = object : RelayBridge.Sink {
        override fun onSession(data: ByteArray?) {
            val copy = data?.copyOf()
            onEventThread("session") { handleSession(copy) }
        }

        override fun onEvent(kind: String, payload: String) {
            onEventThread(kind) { handleEvent(kind, payload) }
        }
    }

    // --- connection bookkeeping -------------------------------------------------------

    private val jobsLock = Any()
    private val connectLock = Mutex()
    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var backfillJob: Job? = null
    private var gateTimer: Job? = null

    /** Bumped by every "connecting": one number per connection. */
    private val connection = AtomicLong(0)

    @Volatile
    private var syncedConnection = -1L

    @Volatile
    private var settingsConnection = -1L

    @Volatile
    private var gateConnection = -1L

    /** The connection on which the bridge said "not connected" to a send; the outbox waits for news. */
    @Volatile
    private var outboxPausedFor = -1L

    @Volatile
    private var lastHealthEventWrite = 0L

    // --- outbox, history, media, typing ---------------------------------------------

    private val outbox = Outbox(app, repo, ::requireBridge, timing.outboxRetryDelaysMs, ::canSend) {
        outboxPausedFor = connection.get()
    }
    private val backfiller = Backfill(
        repo, ::requireBridge, timing.backfillPagePauseMs, timing.backfillConversationPauseMs, _backfill,
    )
    private val outboxKicks = Channel<Unit>(Channel.CONFLATED)
    private val outboxWorker: Job by lazy {
        scope.launch {
            for (kick in outboxKicks) {
                try {
                    outbox.drain()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    RelayLog.add(app, "Outbox stopped: ${t.javaClass.simpleName}")
                }
            }
        }
    }
    private val mediaLoads = ConcurrentHashMap<String, Deferred<File?>>()
    private val fetchedConversations = ConcurrentHashMap.newKeySet<String>()

    private class Typist(val name: String, val until: Long)

    private val typingLock = Any()
    private val typists = HashMap<String, HashMap<String, Typist>>()
    private val typingSentAt = ConcurrentHashMap<String, Long>()

    init {
        // With a stored session, load the bridge in the background so that
        // isPaired() and the state settle before anyone needs them.
        if (paired) scope.launch { runCatching { ensureBridge() } }
    }

    // ============================== public API (contract §6) ==============================

    /** Whether a pairing is stored. Cheap; safe on the main thread. */
    fun isPaired(): Boolean = paired

    // --- pairing --------------------------------------------------------------------

    /**
     * Starts Google-account pairing with the user's own cookies and returns
     * the emoji to show. The user confirms it on their phone; nothing here
     * answers for them. Throws [RelayException] (e.g. cookies_invalid,
     * no_devices, pairing_init_timeout).
     */
    suspend fun startPairing(cookies: Map<String, String>): PairingChallenge {
        val b = withContext(Dispatchers.IO) { ensureBridge() }
        setState(RelayState(RelayState.Phase.PAIRING))
        RelayLog.add(app, "Pairing started")
        return try {
            blockingCall(onCancel = { b.cancelPairing() }) { b.startPairing(cookies) }
        } catch (e: Throwable) {
            pairingEnded(e)
            throw e
        }
    }

    /**
     * Waits (up to 3 minutes) for the user to tap the emoji on their phone.
     * On success the pairing is stored, the link goes live and the first
     * sync starts; returns the phone's id. Throws [RelayException] (e.g.
     * incorrect_emoji, pairing_timeout, pairing_cancelled).
     */
    suspend fun finishPairing(): String {
        val b = withContext(Dispatchers.IO) { ensureBridge() }
        return try {
            blockingCall(onCancel = { b.cancelPairing() }) {
                val phoneId = b.finishPairing(PAIRING_TIMEOUT_MS)
                // Runs even if the caller stopped waiting: the pairing exists now.
                try {
                    onPaired(b)
                } catch (t: Throwable) {
                    RelayLog.add(app, "After pairing: ${t.javaClass.simpleName}")
                }
                phoneId
            }
        } catch (e: Throwable) {
            pairingEnded(e)
            throw e
        }
    }

    /** Abandons the pairing in progress. A waiting [finishPairing] throws pairing_cancelled. */
    fun cancelPairing() {
        scope.launch { bridge?.cancelPairing() }
        if (_state.value.phase == RelayState.Phase.PAIRING) setState(idleState())
    }

    // --- lifecycle ------------------------------------------------------------------

    /** Turns on "stay connected" and starts the foreground service, which connects. */
    fun startLive() {
        RelayPrefs.setLiveEnabled(app, true)
        synchronized(jobsLock) { reconnectAttempts = 0 }
        if (!paired) return
        RelayService.start(app)
    }

    /** Disconnects and stops the service, keeping the pairing. */
    fun stopLive() {
        RelayPrefs.setLiveEnabled(app, false)
        stopConnectionWork()
        RelayService.stop(app)
        scope.launch {
            connectLock.withLock {
                try {
                    bridge?.disconnect()
                } catch (e: RelayException) {
                    RelayLog.add(app, "Disconnect: ${e.code}")
                }
            }
        }
        RelayLog.add(app, "Disconnected by the user")
    }

    /** Makes this device the phone's active web session again, after another one took over. */
    suspend fun useHere(): Unit = withContext(Dispatchers.IO) {
        requireBridge().setActiveSession()
    }

    /**
     * Revokes the pairing on the phone (best effort, it may be offline),
     * disconnects, and deletes the stored session. With [deleteArchive] the
     * local message archive and its media go too. The sticker inbox is never
     * touched. Does not throw.
     */
    suspend fun unpair(deleteArchive: Boolean): Unit = withContext(Dispatchers.IO) {
        RelayPrefs.setLiveEnabled(app, false)
        stopConnectionWork()
        RelayService.stop(app)
        val b = try {
            if (bridge != null || SessionVault.exists(app)) ensureBridge() else null
        } catch (e: RelayException) {
            null
        }
        connectLock.withLock {
            try {
                b?.unpair()
            } catch (e: RelayException) {
                RelayLog.add(app, "Unpair on the phone: ${e.code}")
            }
        }
        // Let the bridge's last callbacks ("session deleted", "unpaired") land first.
        awaitEventThread()
        SessionVault.save(app, null)
        paired = false
        RelayPrefs.setBackfillDoneAt(app, 0)
        try {
            if (deleteArchive) {
                repo.clearAll()
            } else {
                repo.updateHealth(status = "unpaired", account = "", phoneId = "", lastError = "")
                // Queued sends must not go out by themselves under the next
                // pairing, which may be another phone where the same
                // conversation id is someone else.
                outbox.holdAll(UNPAIRED_ERROR)
            }
        } catch (e: RuntimeException) {
            RelayLog.add(app, "Archive could not be cleared: ${e.javaClass.simpleName}")
        }
        _settings.value = null
        clearTyping()
        MessageNotifier.cancelAll(app)
        RelayNotifications.cancelRepairNeeded(app)
        setState(RelayState(RelayState.Phase.UNPAIRED))
        RelayLog.add(app, if (deleteArchive) "Unpaired; local archive deleted" else "Unpaired; local archive kept")
    }

    // --- sync -----------------------------------------------------------------------

    /** Re-lists one folder from the phone into the archive; returns how many conversations came back. */
    suspend fun refreshConversations(folder: Folder = Folder.INBOX): Int = withContext(Dispatchers.IO) {
        val list = requireBridge().listConversations(folder, CONVERSATION_COUNT)
        repo.upsertConversations(list)
        list.size
    }

    /** Fetches the page before the oldest stored message; returns how many arrived, 0 at the start. */
    suspend fun loadOlder(conversationId: String, count: Int = 50): Int = withContext(Dispatchers.IO) {
        backfiller.loadOlder(conversationId, count)
    }

    /** Starts a history import of [depth] (cancelling one that is running). OFF just cancels. */
    fun startBackfill(depth: BackfillDepth) {
        synchronized(jobsLock) {
            val previous = backfillJob
            if (depth == BackfillDepth.OFF) {
                previous?.cancel()
                backfillJob = null
                _backfill.value = _backfill.value.copy(running = false)
                return
            }
            backfillJob = scope.launch {
                previous?.cancelAndJoin()
                runBackfill(depth)
            }
        }
    }

    fun cancelBackfill() {
        synchronized(jobsLock) {
            backfillJob?.cancel()
            backfillJob = null
        }
        _backfill.value = _backfill.value.copy(running = false)
    }

    // --- media ----------------------------------------------------------------------

    /**
     * The file for one media part (or its thumbnail), downloading it into the
     * media cache first if needed. Null if it cannot be had right now (not
     * uploaded yet, only a thumbnail exists, or the download failed).
     */
    suspend fun mediaFile(messageId: String, partId: String, thumbnail: Boolean = false): File? {
        val key = "$messageId\u0000$partId\u0000$thumbnail"
        val load = mediaLoads.computeIfAbsent(key) {
            scope.async(mediaDispatcher, start = CoroutineStart.LAZY) {
                try {
                    loadMedia(messageId, partId, thumbnail)
                } catch (t: Throwable) {
                    RelayLog.add(app, "Media could not be loaded: ${t.javaClass.simpleName}")
                    null
                }
            }.also { d -> d.invokeOnCompletion { mediaLoads.remove(key, d) } }
        }
        return load.await()
    }

    /** Asks the phone to upload the full-size original of a part that only has a thumbnail. */
    suspend fun requestFullSize(messageId: String, partId: String): Unit = withContext(Dispatchers.IO) {
        requireBridge().requestFullSizeImage(messageId, partId)
        repo.markFullSizeRequested(messageId, partId)
    }

    // --- sending --------------------------------------------------------------------

    /**
     * Queues a text message and returns its tmpId at once. It shows in the
     * archive as "tmp:<tmpId>" right away and is sent when the link is
     * connected, surviving restarts.
     */
    fun sendText(conversationId: String, text: String, replyToId: String? = null): String {
        val tmpId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        onIntake("queue a message") { outbox.enqueueText(tmpId, conversationId, text, replyToId, now) }
        return tmpId
    }

    /** Like [sendText] for an attachment (at most 100 MB), copied into app storage first. */
    fun sendMedia(conversationId: String, uri: Uri, caption: String? = null, replyToId: String? = null): String {
        val tmpId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        onIntake("queue an attachment") { outbox.enqueueMedia(tmpId, conversationId, uri, caption, replyToId, now) }
        return tmpId
    }

    /** Sends a failed message again. */
    fun retry(tmpId: String) {
        onIntake("retry a message") { outbox.retry(tmpId) }
    }

    /** Drops a failed (or still queued) message and its local copy. */
    fun discardFailed(tmpId: String) {
        onIntake("discard a message") { outbox.discard(tmpId) }
    }

    /** Adds, switches or (with [remove]) removes the user's reaction on a message. */
    suspend fun react(messageId: String, emoji: String, remove: Boolean): Unit = withContext(Dispatchers.IO) {
        val mine = repo.message(messageId)?.reactions?.firstOrNull { it.fromMe }
        val action = when {
            remove -> "remove"
            mine != null && mine.emoji != emoji -> "switch"
            else -> "add"
        }
        requireBridge().sendReaction(messageId, emoji, action)
    }

    /**
     * Marks a conversation read: locally at once, and on the phone up to its
     * latest incoming message when connected. Also clears its notification.
     * Does not throw.
     */
    suspend fun markRead(conversationId: String): Unit = withContext(Dispatchers.IO) {
        repo.markConversationRead(conversationId)
        MessageNotifier.cancel(app, conversationId)
        val latest = repo.messages(conversationId, limit = 50).lastOrNull { !it.isLocal && it.direction == "in" }
            ?: return@withContext
        if (!_state.value.isOnline) return@withContext
        try {
            requireBridge().markRead(conversationId, latest.id)
        } catch (e: RelayException) {
            if (e.code != RelayException.NOT_CONNECTED) RelayLog.add(app, "Mark read failed: ${e.code}")
        }
    }

    /** Tells the phone the user is typing; at most one ping per conversation every 5 s. */
    fun typing(conversationId: String) {
        if (!_state.value.isOnline) return
        val now = System.nanoTime() / 1_000_000
        val last = typingSentAt[conversationId]
        if (last != null && now - last < timing.typingThrottleMs) return
        typingSentAt[conversationId] = now
        scope.launch {
            try {
                bridge?.setTyping(conversationId)
            } catch (e: RelayException) {
                // Typing is best effort.
            }
        }
    }

    /** Deletes a message on the phone and here. A message still being sent is just dropped. */
    suspend fun deleteMessage(messageId: String) {
        if (messageId.startsWith(MessageRow.TMP_PREFIX)) {
            val tmpId = messageId.removePrefix(MessageRow.TMP_PREFIX)
            onIntakeAndWait { outbox.discard(tmpId) }
            return
        }
        withContext(Dispatchers.IO) {
            requireBridge().deleteMessage(messageId)
            repo.applyDeletion(messageId)
        }
    }

    suspend fun setArchived(conversationId: String, archived: Boolean): Unit = withContext(Dispatchers.IO) {
        requireBridge().setConversationStatus(conversationId, if (archived) "archived" else "active")
        repo.setConversationFolder(conversationId, if (archived) Folder.ARCHIVE else Folder.INBOX)
    }

    suspend fun setMuted(conversationId: String, muted: Boolean): Unit = withContext(Dispatchers.IO) {
        requireBridge().setConversationMuted(conversationId, muted)
        repo.setConversationMuted(conversationId, muted)
    }

    suspend fun deleteConversation(conversationId: String): Unit = withContext(Dispatchers.IO) {
        requireBridge().deleteConversation(conversationId)
        repo.deleteConversationLocal(conversationId)
    }

    /**
     * The phone's contacts from the archive; with [refresh], fetched from the
     * phone first. A failed refresh falls back to the stored list (and only
     * throws if there is none).
     */
    suspend fun listContacts(refresh: Boolean): List<ContactRow> = withContext(Dispatchers.IO) {
        if (refresh) {
            try {
                repo.upsertContacts(requireBridge().listContacts())
            } catch (e: RelayException) {
                if (repo.contacts().isEmpty()) throw e
            }
        }
        repo.contacts()
    }

    /** Opens (or creates) the conversation with these numbers and returns its id. */
    suspend fun startConversation(numbers: List<String>, groupName: String?): String = withContext(Dispatchers.IO) {
        val conversation = requireBridge().getOrCreateConversation(numbers, groupName?.trim()?.ifEmpty { null })
        repo.upsertConversation(conversation)
        conversation.id
    }

    // ============================== service hooks ==============================

    /** Whether [RelayService] exists right now (set by the service itself). */
    @Volatile
    internal var serviceRunning = false

    /**
     * Foreground starts Android accepted whose onStartCommand has not called
     * startForeground yet. Stopping the service in that window makes Android
     * crash the app ("startForegroundService() did not then call
     * startForeground()"), so [RelayService.stop] leaves it to the pending
     * start, which goes foreground and then stops itself if the link is no
     * longer wanted.
     */
    internal val serviceStartsPending = AtomicInteger(0)

    /** The foreground service is running: connect if the user wants the link live. */
    internal fun onServiceStarted() = connectIfWanted()

    /**
     * One of the app's screens came to the front. Android 12+ refuses to start
     * a foreground service from the background, which is where the app is
     * when pairing finishes (the user is in Google Messages tapping the
     * emoji) or when a boot-time start was refused. Starting it again now,
     * while the user is looking, repairs that.
     */
    internal fun onAppVisible() {
        if (!serviceRunning && wantConnected()) RelayService.start(app)
    }

    /** A network came up: an errored link retries now instead of waiting out its backoff. */
    internal fun onNetworkAvailable() {
        if (_state.value.phase != RelayState.Phase.ERROR) return
        synchronized(jobsLock) { reconnectJob?.cancel() }
        connectIfWanted()
    }

    /** The service hit Android's time limit: disconnect cleanly. */
    internal fun onServiceTimeout() {
        RelayLog.add(app, "Android's time limit for the service was reached")
        stopLive()
    }

    // ============================== bridge access ==============================

    /** The bridge, created on first use. Blocks (loads Go); call off the main thread. */
    private fun ensureBridge(): RelayBridge {
        bridge?.let { return it }
        synchronized(bridgeLock) {
            bridge?.let { return it }
            val factory = RelayBridges.factory ?: { session, sink -> GoRelayBridge(session, sink) }
            val session = SessionVault.load(app)
            val created = try {
                factory(session, sink)
            } catch (e: RelayException) {
                if (session == null) throw e
                // A stored session the bridge cannot read is useless: start unpaired.
                RelayLog.add(app, "Stored session could not be used (${e.code}); pair again")
                SessionVault.save(app, null)
                factory(null, sink)
            } catch (e: LinkageError) {
                // The Go library is missing for this device's CPU.
                RelayLog.add(app, "The link's library could not be loaded")
                throw RelayException(RelayException.INTERNAL, "bridge library unavailable")
            }
            bridge = created
            paired = created.isPaired
            if (!paired && _state.value.phase == RelayState.Phase.DISCONNECTED) {
                setState(RelayState(if (repo.health().status == "logged_out") RelayState.Phase.LOGGED_OUT else RelayState.Phase.UNPAIRED))
            }
            return created
        }
    }

    private fun requireBridge(): RelayBridge = ensureBridge()

    /**
     * Runs a long blocking bridge call on IO in a way the caller can abandon:
     * cancelling the caller runs [onCancel] (which makes Go return early)
     * instead of waiting for the call to come back by itself.
     */
    private suspend fun <T> blockingCall(onCancel: () -> Unit, block: () -> T): T =
        suspendCancellableCoroutine { cont ->
            scope.launch {
                val result = try {
                    Result.success(block())
                } catch (e: Exception) {
                    Result.failure(RelayException.fromThrowable(e))
                } catch (t: Throwable) {
                    Result.failure(t)
                }
                result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
            }
            cont.invokeOnCancellation { scope.launch { runCatching(onCancel) } }
        }

    private fun onPaired(b: RelayBridge) {
        paired = true
        RelayNotifications.cancelRepairNeeded(app)
        RelayPrefs.setBackfillDoneAt(app, 0)
        onEventThread("session") { b.exportSession()?.let { SessionVault.save(app, it) } }
        val status = runCatching { b.status() }.getOrNull()
        repo.updateHealth(
            status = "disconnected", lastError = "",
            account = status?.account.orEmpty(), phoneId = status?.phoneId.orEmpty(),
        )
        setState(RelayState(RelayState.Phase.DISCONNECTED))
        RelayLog.add(app, "Paired with the phone")
        startLive()
    }

    private fun pairingEnded(e: Throwable) {
        val code = (e as? RelayException)?.code ?: if (e is CancellationException) RelayException.PAIRING_CANCELLED else e.javaClass.simpleName
        RelayLog.add(app, "Pairing did not finish: $code")
        if (_state.value.phase == RelayState.Phase.PAIRING) setState(idleState())
    }

    private fun idleState() = RelayState(if (paired) RelayState.Phase.DISCONNECTED else RelayState.Phase.UNPAIRED)

    // ============================== connecting ==============================

    private fun wantConnected() = paired && RelayPrefs.liveEnabled(app)

    private fun connectIfWanted() {
        // Only with the service there: its notification (with Disconnect) must
        // show while connected. A reconnect timer or network callback that
        // fires after the service went away must not connect without it.
        if (!wantConnected() || !serviceRunning) return
        synchronized(jobsLock) {
            if (connectJob?.isActive == true) return
            connectJob = scope.launch { connectNow() }
        }
    }

    private suspend fun connectNow() = connectLock.withLock {
        if (!wantConnected()) return@withLock
        val b = try {
            ensureBridge()
        } catch (e: RelayException) {
            RelayLog.add(app, "Could not load the link: ${e.code}")
            return@withLock
        }
        if (!b.isPaired) {
            paired = false
            return@withLock
        }
        try {
            b.connect()
        } catch (e: RelayException) {
            RelayLog.add(app, "Could not connect: ${e.code}")
            // An expired sign-in or a lost pairing arrives as its own state;
            // anything else (no network, Google unreachable) is retried.
            if (e.code !in NEEDS_USER) scheduleReconnect()
            return@withLock
        }
        // stopLive() may have run while connect() was blocking.
        if (!wantConnected()) b.disconnect()
    }

    private fun scheduleReconnect() {
        synchronized(jobsLock) {
            if (!wantConnected() || reconnectJob?.isActive == true) return
            val delays = timing.reconnectDelaysMs
            val wait = delays[reconnectAttempts.coerceAtMost(delays.lastIndex)]
            reconnectAttempts++
            RelayLog.add(app, "Reconnecting in ${wait / 1000} s")
            reconnectJob = scope.launch {
                delay(wait)
                connectIfWanted()
            }
        }
    }

    /** Stops what belongs to a live connection: reconnect timer, send gate timer, history import. */
    private fun stopConnectionWork() {
        synchronized(jobsLock) {
            reconnectJob?.cancel()
            reconnectJob = null
            gateTimer?.cancel()
            gateTimer = null
        }
        cancelBackfill()
    }

    private fun canSend(): Boolean {
        val current = connection.get()
        return _state.value.isOnline && gateConnection == current && outboxPausedFor != current
    }

    private fun kickOutbox() {
        outboxWorker // started on first use
        outboxKicks.trySend(Unit)
    }

    // ============================== events ==============================

    private fun handleSession(data: ByteArray?) {
        SessionVault.save(app, data)
        if (data != null) repo.updateHealth(lastTokenRefresh = System.currentTimeMillis())
    }

    private fun handleEvent(kind: String, payload: String) {
        val now = System.currentTimeMillis()
        if (kind != KIND_STATE && now - lastHealthEventWrite >= timing.healthEventIntervalMs) {
            lastHealthEventWrite = now
            repo.updateHealth(lastEventTs = now)
        }
        when (kind) {
            KIND_STATE -> onState(RelayStateEvent.fromJson(JSONObject(payload)), now)
            "message" -> onMessage(MessageDto.fromJson(JSONObject(payload)))
            "conversation" -> repo.upsertConversation(ConversationDto.fromJson(JSONObject(payload)))
            "typing" -> onTyping(TypingDto.fromJson(JSONObject(payload)))
            "settings" -> onSettings(SettingsDto.fromJson(JSONObject(payload)))
            "resync" -> scope.launch { refreshQuietly() }
            "account" -> RelayLog.add(app, "The phone reported an account change")
        }
    }

    private fun onState(event: RelayStateEvent, now: Long) {
        val phase = PHASES[event.state] ?: return
        val previous = _state.value
        setState(RelayState(phase, event.code, event.detail))
        lastHealthEventWrite = now
        repo.updateHealth(
            status = event.state,
            lastEventTs = now,
            lastError = if (event.code.isEmpty()) null else listOf(event.code, event.detail).filter { it.isNotEmpty() }.joinToString(": "),
        )
        if (previous.phase != phase) {
            RelayLog.add(app, "State: ${event.state}" + if (event.code.isEmpty()) "" else " (${event.code})")
        }
        when (phase) {
            RelayState.Phase.CONNECTING -> {
                connection.incrementAndGet()
                synchronized(jobsLock) { gateTimer?.cancel() }
            }
            RelayState.Phase.CONNECTED, RelayState.Phase.ACTIVE -> onOnline()
            RelayState.Phase.LOGGED_OUT -> needsRepair(phase, pairingLost = true)
            RelayState.Phase.COOKIES_INVALID -> needsRepair(phase, pairingLost = false)
            RelayState.Phase.ERROR -> scheduleReconnect()
            RelayState.Phase.UNPAIRED -> paired = false
            RelayState.Phase.DISCONNECTED -> clearTyping()
            else -> Unit
        }
    }

    /**
     * Connected (or made active). Once per connection: re-list the inbox and
     * start the history import if it never finished. Sending waits for the
     * connection's first settings event (the bridge needs the SIM details
     * from it), or 15 s, whichever comes first.
     */
    private fun onOnline() {
        synchronized(jobsLock) { reconnectAttempts = 0 }
        val current = connection.get()
        // The bridge says it is (again) connected: sends that found it down may go now.
        outboxPausedFor = -1L
        if (syncedConnection != current) {
            syncedConnection = current
            scope.launch { initialSync() }
        }
        if (settingsConnection == current) {
            openGate(current)
        } else if (gateConnection != current) {
            synchronized(jobsLock) {
                if (gateTimer?.isActive != true) {
                    gateTimer = scope.launch {
                        delay(timing.settingsGateMs)
                        openGate(current)
                    }
                }
            }
        }
        kickOutbox()
    }

    private fun openGate(forConnection: Long) {
        if (connection.get() != forConnection || gateConnection == forConnection) return
        gateConnection = forConnection
        synchronized(jobsLock) { gateTimer?.cancel() }
        kickOutbox()
    }

    private suspend fun initialSync() {
        refreshQuietly()
        try {
            val status = requireBridge().status()
            repo.updateHealth(account = status.account, phoneId = status.phoneId)
        } catch (e: RelayException) {
            // Only for display.
        }
        val depth = RelayPrefs.backfillDepth(app)
        val running = synchronized(jobsLock) { backfillJob?.isActive == true }
        if (depth != BackfillDepth.OFF && RelayPrefs.backfillDoneAt(app) == 0L && !running) startBackfill(depth)
    }

    private suspend fun refreshQuietly() {
        try {
            refreshConversations(Folder.INBOX)
        } catch (e: RelayException) {
            RelayLog.add(app, "Conversation list refresh failed: ${e.code}")
        }
    }

    private suspend fun runBackfill(depth: BackfillDepth) {
        _backfill.value = BackfillProgress(running = true, 0, 0, 0)
        try {
            if (!_state.value.isOnline) {
                withTimeoutOrNull(timing.backfillWaitOnlineMs) { state.first { it.isOnline } }
                    ?: throw RelayException(RelayException.NOT_CONNECTED, "not online")
            }
            RelayLog.add(app, "History import started (${depth.name.lowercase()})")
            val messages = backfiller.run(depth)
            val done = _backfill.value
            _backfill.value = done.copy(running = false, messages = messages)
            RelayPrefs.setBackfillDoneAt(app, System.currentTimeMillis())
            RelayLog.add(app, "History import finished: ${done.conversationsDone} conversations, $messages messages")
        } catch (e: CancellationException) {
            _backfill.value = _backfill.value.copy(running = false)
            throw e
        } catch (e: RelayException) {
            _backfill.value = _backfill.value.copy(running = false, error = e.code)
            RelayLog.add(app, "History import stopped: ${e.code}")
        } catch (e: RuntimeException) {
            // A database or parsing failure: without this the progress would
            // say "running" forever and the UI would never offer a new import.
            _backfill.value = _backfill.value.copy(running = false, error = RelayException.INTERNAL)
            RelayLog.add(app, "History import stopped: ${e.javaClass.simpleName}")
        }
    }

    /** Pairing lost, or Google stopped accepting the sign-in: only the user can fix it. */
    private fun needsRepair(phase: RelayState.Phase, pairingLost: Boolean) {
        if (pairingLost) paired = false
        RelayPrefs.setLiveEnabled(app, false)
        stopConnectionWork()
        RelayService.stop(app)
        RelayNotifications.showRepairNeeded(app, phase)
    }

    private fun onMessage(dto: MessageDto) {
        val result = repo.upsertMessage(dto)
        if (dto.isDeleted || dto.id.isEmpty()) return
        if (result.newMediaParts.isNotEmpty() && RelayPrefs.catchStickers(app)) {
            for (part in result.newMediaParts) {
                if (isStickerCandidate(part)) scope.launch(mediaDispatcher) { checkSticker(dto, part) }
            }
        }
        if (result.inserted) {
            maybeFetchConversation(dto.conversationId)
            if (!dto.isOld && !dto.fromMe && dto.direction == "in") MessageNotifier.onIncoming(app, repo, dto)
        }
    }

    /** A message for a conversation known only as a placeholder: ask the phone for its details once. */
    private fun maybeFetchConversation(conversationId: String) {
        val known = repo.conversation(conversationId)
        if (known != null && known.name.isNotEmpty()) return
        if (!_state.value.isOnline || !fetchedConversations.add(conversationId)) return
        scope.launch {
            try {
                repo.upsertConversation(requireBridge().getConversation(conversationId))
            } catch (e: RelayException) {
                fetchedConversations.remove(conversationId)
            }
        }
    }

    private fun onSettings(dto: SettingsDto) {
        _settings.value = dto
        val current = connection.get()
        settingsConnection = current
        if (_state.value.isOnline) openGate(current)
    }

    // --- typing ---------------------------------------------------------------------

    private fun onTyping(dto: TypingDto) {
        if (dto.conversationId.isEmpty()) return
        val key = dto.participantId.ifEmpty { dto.number }
        val until = System.nanoTime() / 1_000_000 + timing.typingExpiryMs
        synchronized(typingLock) {
            val inConversation = typists.getOrPut(dto.conversationId) { HashMap() }
            if (dto.typing) {
                inConversation[key] = Typist(typistName(dto), until)
            } else {
                inConversation.remove(key)
            }
            if (inConversation.isEmpty()) typists.remove(dto.conversationId)
            publishTyping()
        }
        if (dto.typing) {
            scope.launch {
                delay(timing.typingExpiryMs)
                expireTyping()
            }
        }
    }

    private fun typistName(dto: TypingDto): String {
        val participants = repo.participants(dto.conversationId)
        val match = participants.firstOrNull { dto.participantId.isNotEmpty() && it.id == dto.participantId }
            ?: participants.firstOrNull { dto.number.isNotEmpty() && it.number == dto.number }
        return listOfNotNull(match?.name, match?.firstName, match?.formattedNumber, match?.number, dto.number)
            .firstOrNull { it.isNotBlank() }
            ?: app.getString(R.string.relay_notify_someone)
    }

    private fun expireTyping() = synchronized(typingLock) {
        val now = System.nanoTime() / 1_000_000
        typists.values.forEach { map -> map.values.removeAll { it.until <= now } }
        typists.values.removeAll { it.isEmpty() }
        publishTyping()
    }

    private fun clearTyping() = synchronized(typingLock) {
        typists.clear()
        publishTyping()
    }

    private fun publishTyping() {
        _typing.value = typists.mapValues { (_, map) -> map.values.map { it.name }.toSet() }
    }

    // --- stickers and media ------------------------------------------------------------

    /**
     * Parts worth reading for the sticker inbox: HEIC/HEIF, or no or a generic
     * type (sniffing decides), or any picture when the user keeps every
     * picture. Never over 32 MB.
     */
    private fun isStickerCandidate(part: MediaRow): Boolean {
        if (part.stickerChecked || part.size > StickerCapture.MAX_BYTES) return false
        val mime = part.mime.substringBefore(';').trim().lowercase()
        return mime in HEIF_MIMES || mime.isEmpty() || mime == "application/octet-stream" ||
            (mime.startsWith("image/") && InboxPrefs.catchAllImages(app))
    }

    private fun checkSticker(dto: MessageDto, part: MediaRow) {
        try {
            val bytes = when {
                part.inlineData.isNotEmpty() -> part.inlineData
                part.mediaId.isNotEmpty() -> download(dto.id, part.partId, part.mediaId, part.key, part.mime, thumbnail = false)
                part.needsFullSize || part.thumbMediaId.isNotEmpty() -> {
                    requestFullSizeOnce(dto.id, part)
                    null
                }
                else -> null
            } ?: return
            val title = repo.conversation(dto.conversationId)?.name.orEmpty()
            StickerCapture.capture(
                app,
                bytes,
                StickerCapture.Meta(
                    id = InboxStore.idFor(InboxItem.SOURCE_RELAY, dto.conversationId, dto.id, 0, part.partId),
                    sender = dto.senderName,
                    title = title,
                    appPackage = GOOGLE_MESSAGES_PACKAGE,
                    timestampMs = dto.timestampMs.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    mime = part.mime,
                    source = InboxItem.SOURCE_RELAY,
                ),
            )
            repo.markStickerChecked(dto.id, part.partId)
        } catch (e: RelayException) {
            RelayLog.add(app, "Sticker download failed: ${e.code}")
        } catch (t: Throwable) {
            // Includes OutOfMemoryError on a huge picture: skip it, as the listener does.
            RelayLog.add(app, "Sticker check failed: ${t.javaClass.simpleName}")
            runCatching { repo.markStickerChecked(dto.id, part.partId) }
        }
    }

    /** Only a thumbnail so far: ask the phone for the original, once. Its update comes back as a message event. */
    private fun requestFullSizeOnce(messageId: String, part: MediaRow) {
        if (part.fullSizeRequested || part.partId.isEmpty() || part.partId.startsWith(INDEX_PART_PREFIX)) return
        if (!_state.value.isOnline) return
        try {
            requireBridge().requestFullSizeImage(messageId, part.partId)
            repo.markFullSizeRequested(messageId, part.partId)
        } catch (e: RelayException) {
            RelayLog.add(app, "Full-size request failed: ${e.code}")
        }
    }

    private fun loadMedia(messageId: String, partId: String, thumbnail: Boolean): File? {
        val part = repo.mediaPart(messageId, partId) ?: return null
        val stored = (if (thumbnail) listOf(part.thumbPath, part.localPath) else listOf(part.localPath))
            .filter { it.isNotEmpty() }.map(::File).firstOrNull { it.isFile && it.length() > 0 }
        if (stored != null) return stored
        return try {
            when {
                thumbnail && part.thumbMediaId.isNotEmpty() ->
                    downloadToFile(messageId, partId, part.thumbMediaId, part.thumbKey, part.mime, thumbnail = true)
                part.inlineData.isNotEmpty() ->
                    writeMedia(messageId, partId, "inline:$messageId:$partId", part.inlineData, part.mime, thumbnail = false)
                part.mediaId.isNotEmpty() ->
                    downloadToFile(messageId, partId, part.mediaId, part.key, part.mime, thumbnail = false)
                else -> null
            }
        } catch (e: RelayException) {
            RelayLog.add(app, "Media download failed: ${e.code}")
            null
        } catch (e: java.io.IOException) {
            RelayLog.add(app, "Media could not be stored: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun downloadToFile(messageId: String, partId: String, mediaId: String, key: ByteArray, mime: String, thumbnail: Boolean): File {
        val bytes = requireBridge().downloadMedia(mediaId, key)
        return writeMedia(messageId, partId, mediaId, bytes, mime, thumbnail)
    }

    /** Downloads a part (also keeping it in the media cache for the conversation screen). */
    private fun download(messageId: String, partId: String, mediaId: String, key: ByteArray, mime: String, thumbnail: Boolean): ByteArray {
        val bytes = requireBridge().downloadMedia(mediaId, key)
        try {
            writeMedia(messageId, partId, mediaId, bytes, mime, thumbnail)
        } catch (e: java.io.IOException) {
            // The bytes are still good for the sticker inbox.
        }
        return bytes
    }

    /** Stores bytes in the media cache (temp file, then rename) and records the path. */
    private fun writeMedia(messageId: String, partId: String, cacheKey: String, bytes: ByteArray, mime: String, thumbnail: Boolean): File {
        val file = MediaCache.fileFor(app, cacheKey, ImageSniffer.extensionFor(bytes, mime))
        val tmp = File(file.parentFile, file.name + ".part")
        FileOutputStream(tmp).use { it.write(bytes) }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("rename failed")
        }
        repo.setMediaLocalPath(messageId, partId, file.path, thumbnail)
        return file
    }

    // ============================== threads ==============================

    private fun setState(state: RelayState) {
        _state.value = state
    }

    /** Runs bridge callbacks on the event thread, in order; never throws back into the bridge. */
    private fun onEventThread(kind: String, block: () -> Unit) {
        try {
            events.execute {
                try {
                    block()
                } catch (t: Throwable) {
                    // The class name only: an exception's message can quote the payload.
                    RelayLog.add(app, "Could not handle a $kind event: ${t.javaClass.simpleName}")
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // Shut down.
        }
    }

    private fun onIntake(what: String, block: () -> Unit) {
        intake.execute {
            try {
                block()
            } catch (t: Throwable) {
                RelayLog.add(app, "Could not $what: ${t.javaClass.simpleName}")
            }
            kickOutbox()
        }
    }

    private suspend fun <T> onIntakeAndWait(block: () -> T): T = suspendCancellableCoroutine { cont ->
        intake.execute {
            try {
                cont.resume(block())
            } catch (t: Throwable) {
                cont.resumeWithException(t)
            }
        }
    }

    /** Waits until the events queued so far have been handled. Not for the event thread itself. */
    private fun awaitEventThread() {
        try {
            events.submit {}.get(EVENT_DRAIN_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // Timed out or shut down: carry on.
        }
    }

    // ============================== testing ==============================

    /** Waits for queued callbacks and sends to be taken in (not for network calls to finish). */
    internal fun awaitIdleForTesting() {
        awaitEventThread()
        try {
            intake.submit {}.get(EVENT_DRAIN_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // As above.
        }
        awaitEventThread()
    }

    /** Creates the bridge now (it is otherwise created on first use). */
    internal fun bridgeForTesting(): RelayBridge = ensureBridge()

    internal fun shutdownForTesting() {
        scope.cancel()
        events.shutdownNow()
        intake.shutdownNow()
    }

    companion object {
        private const val KIND_STATE = "state"
        private const val PAIRING_TIMEOUT_MS = 180_000L
        private const val CONVERSATION_COUNT = 100
        private const val EVENT_DRAIN_TIMEOUT_S = 10L
        private const val GOOGLE_MESSAGES_PACKAGE = "com.google.android.apps.messaging"

        /** The error a send queued before an unpair is held with (content-free). */
        internal const val UNPAIRED_ERROR = "unpaired"

        /** Part ids the bridge made up from the part's position; the phone cannot be asked about them. */
        private const val INDEX_PART_PREFIX = "idx:"

        private val HEIF_MIMES = setOf("image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence")

        /** Connect failures that only the user can fix; they are not retried. */
        private val NEEDS_USER = setOf(RelayException.COOKIES_INVALID, RelayException.LOGGED_OUT, RelayException.NOT_PAIRED)

        private val PHASES = mapOf(
            "connecting" to RelayState.Phase.CONNECTING,
            "connected" to RelayState.Phase.CONNECTED,
            "active" to RelayState.Phase.ACTIVE,
            "active_elsewhere" to RelayState.Phase.ACTIVE_ELSEWHERE,
            "reconnecting" to RelayState.Phase.RECONNECTING,
            "phone_offline" to RelayState.Phase.PHONE_OFFLINE,
            "logged_out" to RelayState.Phase.LOGGED_OUT,
            "cookies_invalid" to RelayState.Phase.COOKIES_INVALID,
            "error" to RelayState.Phase.ERROR,
            "disconnected" to RelayState.Phase.DISCONNECTED,
            "unpaired" to RelayState.Phase.UNPAIRED,
        )

        @Volatile
        private var instance: RelayManager? = null

        fun get(context: Context): RelayManager =
            instance ?: synchronized(this) {
                instance ?: RelayManager(context, ArchiveRepository.get(context), Timing()).also { instance = it }
            }

        /** Replaces the process instance with one over [repo] (tests only); the old one is shut down. */
        internal fun installForTesting(context: Context, repo: ArchiveRepository, timing: Timing): RelayManager =
            synchronized(this) {
                instance?.shutdownForTesting()
                RelayManager(context, repo, timing).also { instance = it }
            }

        internal fun resetForTesting() = synchronized(this) {
            instance?.shutdownForTesting()
            instance = null
        }

        private fun named(name: String) = ThreadFactory { r -> Thread(r, name).apply { isDaemon = true } }
    }
}
