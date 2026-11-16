package com.lightdecoder.relay

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
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
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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

    companion object {
        /**
         * [code] of a DISCONNECTED state: the user wants the link, but its
         * notification cannot be seen (the app's notifications or the link's
         * channel are off), and it never runs without it.
         */
        const val NOTIFICATIONS_OFF = "notifications_off"
    }
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
        /** A send without an echo this long is looked up on the phone. */
        val unconfirmedSendCheckMs: Long = 15_000,
        /** A send without an echo this long stops showing "sending" (as the connector's pending-send age). */
        val unconfirmedSendGiveUpMs: Long = 3_600_000,
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

    /** A connect was asked for while one was still running: try again when it ends. */
    private var connectAgain = false
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var backfillJob: Job? = null
    private var gateTimer: Job? = null

    /** Parent of the forward catch-ups of the current connection; cancelled with it. */
    private val catchUps = SupervisorJob(scope.coroutineContext[Job])

    /** Catch-ups and history imports page through the phone one at a time, never both at once. */
    private val pager = Mutex()

    /** Serializes unpairs (a second tap, or a recreated screen asking again). */
    private val unpairLock = Mutex()
    private val _unpairing = MutableStateFlow(false)

    /** True while an unpair is running; the UI keeps Unpair and Disconnect disabled meanwhile. */
    val unpairing: StateFlow<Boolean> = _unpairing.asStateFlow()

    /** Drops what the phone sends back after the archive was deleted. */
    private val gate = ArchiveGate()

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

    private val outbox = Outbox(
        app, repo, ::requireBridge, timing.outboxRetryDelaysMs, ::canSend, gate,
        unconfirmedCheckMs = timing.unconfirmedSendCheckMs,
        unconfirmedGiveUpMs = timing.unconfirmedSendGiveUpMs,
        drainLater = ::kickOutboxLater,
    ) {
        outboxPausedFor = connection.get()
    }
    private val backfiller = Backfill(
        repo, ::requireBridge, gate, timing.backfillPagePauseMs, timing.backfillConversationPauseMs, _backfill,
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

    /**
     * Turns on "stay connected" and starts the foreground service, which
     * connects. Without a pairing it does nothing (and leaves "stay
     * connected" off). With the link's notification turned off it keeps the
     * wish but does not start: the state says why ([RelayState.NOTIFICATIONS_OFF]).
     *
     * Cheap and safe on the main thread, like [stopLive]: call both directly,
     * so that quick on/off taps take effect in the order they were made.
     */
    fun startLive() {
        if (!paired) {
            RelayPrefs.setLiveEnabled(app, false)
            return
        }
        RelayPrefs.setLiveEnabled(app, true)
        synchronized(jobsLock) { reconnectAttempts = 0 }
        if (!RelayNotifications.statusVisible(app)) {
            onNotificationsHidden()
            return
        }
        RelayService.start(app)
    }

    /** Disconnects and stops the service, keeping the pairing. */
    fun stopLive() {
        RelayPrefs.setLiveEnabled(app, false)
        resumeOnVisible = false
        stopConnectionWork()
        RelayService.stop(app)
        disconnectUnlessWanted()
        if (_state.value.code == RelayState.NOTIFICATIONS_OFF) setState(RelayState(RelayState.Phase.DISCONNECTED))
        RelayLog.add(app, "Disconnected by the user")
    }

    /**
     * Disconnects the bridge once no connect is in progress, unless by then
     * the link is wanted again and may run (a later [startLive]): whichever of
     * the two the user did last wins.
     */
    private fun disconnectUnlessWanted() {
        scope.launch {
            connectLock.withLock {
                if (linkAllowed()) return@withLock
                try {
                    bridge?.disconnect()
                } catch (e: RelayException) {
                    RelayLog.add(app, "Disconnect: ${e.code}")
                }
            }
        }
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
     *
     * It runs in the manager's own scope: if the screen that asked goes away
     * (Back, rotation) while it waits for a connect to finish, only the wait
     * is cancelled and the unpair still completes. [unpairing] is true
     * meanwhile; a second unpair waits for the first.
     */
    suspend fun unpair(deleteArchive: Boolean) {
        scope.async {
            unpairLock.withLock {
                _unpairing.value = true
                try {
                    unpairNow(deleteArchive)
                } finally {
                    _unpairing.value = false
                }
            }
        }.await()
    }

    private suspend fun unpairNow(deleteArchive: Boolean) {
        RelayPrefs.setLiveEnabled(app, false)
        resumeOnVisible = false
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
                wipeArchive()
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
        listAndStore(folder).size
    }

    /** Lists one folder and stores it (unless the archive was deleted meanwhile); returns the list. */
    private fun listAndStore(folder: Folder): List<ConversationDto> {
        val since = gate.epoch()
        val list = requireBridge().listConversations(folder, CONVERSATION_COUNT)
        gate.ifCurrent(since) { repo.upsertConversations(list) }
        return list
    }

    /** Fetches the page before where the history import got to; returns how many arrived, 0 at the start. */
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
                val since = gate.epoch()
                val contacts = requireBridge().listContacts()
                gate.ifCurrent(since) { repo.upsertContacts(contacts) }
            } catch (e: RelayException) {
                if (repo.contacts().isEmpty()) throw e
            }
        }
        repo.contacts()
    }

    /** Opens (or creates) the conversation with these numbers and returns its id. */
    suspend fun startConversation(numbers: List<String>, groupName: String?): String = withContext(Dispatchers.IO) {
        val since = gate.epoch()
        val conversation = requireBridge().getOrCreateConversation(numbers, groupName?.trim()?.ifEmpty { null })
        gate.ifCurrent(since) { repo.upsertConversation(conversation) }
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

    /**
     * A start the user wanted that could not happen: Android refused to start
     * the service from the background, or its notification was turned off.
     * The next screen the user opens repairs it ([onAppVisible]).
     */
    @Volatile
    internal var resumeOnVisible = false

    /** The foreground service is running: connect if the user wants the link live. */
    internal fun onServiceStarted() = connectIfWanted()

    /**
     * One of the app's screens came to the front (on the main thread).
     *
     * Android 12+ refuses to start a foreground service from the background,
     * which is where the app is when pairing finishes (the user is in Google
     * Messages tapping the emoji) or when a boot-time start was refused.
     * Starting it again now, while the user is looking, repairs that. Some
     * screens can be opened by other apps, so a link that is simply not
     * running (after a restart, say) only starts here when the user lets it
     * start by itself ("start automatically").
     *
     * With the service running: a link whose notification was turned off
     * goes down, and an errored one retries now instead of waiting out its
     * backoff.
     */
    internal fun onAppVisible() {
        if (serviceRunning) {
            if (!RelayNotifications.statusVisible(app)) {
                onNotificationsHidden()
            } else if (_state.value.phase == RelayState.Phase.ERROR) {
                retryNow()
            }
            return
        }
        if (!wantConnected() || serviceStartsPending.get() > 0) return
        if (!resumeOnVisible && !RelayPrefs.autoStart(app)) return
        if (!RelayNotifications.statusVisible(app)) {
            onNotificationsHidden()
            return
        }
        resumeOnVisible = false
        RelayService.start(app)
    }

    /** A network came up: an errored link retries now instead of waiting out its backoff. */
    internal fun onNetworkAvailable() {
        if (_state.value.phase != RelayState.Phase.ERROR) return
        retryNow()
    }

    /** The backoff's wake-up alarm fired. Returns the connect it started, if any. */
    internal fun onReconnectDue(): Job? {
        synchronized(jobsLock) {
            if (reconnectJob?.isActive != true) return null
            reconnectJob?.cancel()
            reconnectJob = null
        }
        connectIfWanted()
        return synchronized(jobsLock) { connectJob }
    }

    private fun retryNow() {
        synchronized(jobsLock) {
            reconnectJob?.cancel()
            reconnectJob = null
        }
        cancelReconnectAlarm()
        connectIfWanted()
    }

    /** The service hit Android's time limit: disconnect cleanly. */
    internal fun onServiceTimeout() {
        RelayLog.add(app, "Android's time limit for the service was reached")
        stopLive()
    }

    /**
     * The service is gone. Whatever stopped it (Disconnect, the user turning
     * notifications off, Android), the link does not stay connected without
     * its notification, unless a new start is already on its way.
     */
    internal fun onServiceDestroyed() {
        if (bridge == null) return
        if (_state.value.phase in LIVE_PHASES || bridge?.isConnected == true) disconnectUnlessWanted()
    }

    /**
     * The link's notification cannot be seen: the app's notifications or its
     * channel are off (on Android 13+ also: the permission was refused). The
     * link never runs without it, since its Disconnect button must always be
     * there. Stops the service and disconnects, but keeps "stay connected",
     * so the link comes back when notifications are on and a screen is shown.
     */
    internal fun onNotificationsHidden() {
        resumeOnVisible = true
        stopConnectionWork()
        RelayService.stop(app)
        if (bridge != null && (_state.value.phase in LIVE_PHASES || bridge?.isConnected == true)) disconnectUnlessWanted()
        val current = _state.value
        if (current.phase in NEEDS_USER_PHASES || current.code == RelayState.NOTIFICATIONS_OFF) return
        RelayLog.add(app, "Link not started: its notification is turned off")
        setState(RelayState(RelayState.Phase.DISCONNECTED, RelayState.NOTIFICATIONS_OFF))
    }

    /** The user turned the app's notifications, or one of its channels, off or on (Android 9+). */
    internal fun onNotificationSettingsChanged() {
        if (!wantConnected()) return
        if (!RelayNotifications.statusVisible(app)) {
            onNotificationsHidden()
        } else if (!serviceRunning && serviceStartsPending.get() == 0 && resumeOnVisible) {
            // Back on: try now; if Android refuses from the background, the next screen does it.
            resumeOnVisible = false
            RelayService.start(app)
        }
    }

    // ============================== bridge access ==============================

    /** The bridge, created on first use. Blocks (loads Go); call off the main thread. */
    private fun ensureBridge(): RelayBridge {
        bridge?.let { return it }
        synchronized(bridgeLock) {
            bridge?.let { return it }
            val factory = RelayBridges.factory ?: { session, sink -> GoRelayBridge(session, sink) }
            val session = try {
                SessionVault.load(app)
            } catch (e: SessionVault.UnreadableException) {
                // Passing (Keystore busy at boot, a read error): not the same as
                // "no session". Nothing is cached, `paired` and the state stay
                // as they are, and the next call tries again.
                RelayLog.add(app, "Stored session could not be read right now")
                throw RelayException(RelayException.INTERNAL, SESSION_UNREADABLE)
            }
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
            // A service started on the stored file alone (at boot, say) has nothing to connect.
            if (!paired && serviceRunning) giveUpLive()
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

    /**
     * A pairing was just made. Conversation ids are only meaningful on the
     * phone they came from (another phone can have someone else behind the
     * same id), so before the link goes live:
     *  - paired with a different phone or account than the archive came
     *    from: the old archive (with its queued sends and media) is deleted;
     *  - the same phone again (after "logged out" or expired cookies): queued
     *    sends go out as planned;
     *  - not known which: queued sends wait as failed for the user to retry
     *    or discard, as after an unpair.
     */
    private fun onPaired(b: RelayBridge) {
        val previousPhone = runCatching { RelayPrefs.archivePhone(app).ifEmpty { repo.health().phoneId } }.getOrDefault("")
        val previousAccount = runCatching { RelayPrefs.archiveAccount(app).ifEmpty { repo.health().account } }.getOrDefault("")
        paired = true
        RelayNotifications.cancelRepairNeeded(app)
        RelayPrefs.setBackfillDoneAt(app, 0)
        onEventThread("session") { b.exportSession()?.let { SessionVault.save(app, it) } }
        val status = runCatching { b.status() }.getOrNull()
        val phone = status?.phoneId.orEmpty()
        val account = status?.account.orEmpty()
        val otherPhone = previousPhone.isNotEmpty() && phone.isNotEmpty() && previousPhone != phone
        val otherAccount = previousAccount.isNotEmpty() && account.isNotEmpty() && !previousAccount.equals(account, ignoreCase = true)
        when {
            otherPhone || otherAccount -> archiveBelongsToAnotherPhone()
            previousPhone.isEmpty() || phone.isEmpty() -> holdQueuedSends()
            else -> Unit
        }
        if (phone.isNotEmpty()) RelayPrefs.setArchiveOwner(app, phone, account)
        repo.updateHealth(status = "disconnected", lastError = "", account = account, phoneId = phone)
        setState(RelayState(RelayState.Phase.DISCONNECTED))
        RelayLog.add(app, "Paired with the phone")
        startLive()
    }

    /** The archive came from another phone or account: it goes, before anything of the new one arrives. */
    private fun archiveBelongsToAnotherPhone() {
        try {
            // On the intake thread: after sends already handed in, before later ones.
            intake.submit { wipeArchive() }.get(WIPE_TIMEOUT_S, TimeUnit.SECONDS)
            RelayLog.add(app, "Paired with a different phone; the previous phone's archive was deleted")
        } catch (e: Exception) {
            RelayLog.add(app, "Previous archive could not be deleted: ${e.javaClass.simpleName}")
            holdQueuedSends()
        }
        _settings.value = null
        clearTyping()
        MessageNotifier.cancelAll(app)
    }

    /** Nothing queued under another (or an unknown) pairing goes out by itself. */
    private fun holdQueuedSends() {
        try {
            // On the intake thread: ordered after sends already handed in, and before startLive() opens the gate.
            intake.submit { outbox.holdAll(UNPAIRED_ERROR) }.get(EVENT_DRAIN_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            RelayLog.add(app, "Could not hold queued sends: ${e.javaClass.simpleName}")
        }
    }

    /** Deletes the archive (messages, media, queued sends) between two writes of what the phone sent. */
    private fun wipeArchive() {
        gate.wipe { repo.clearAll() }
        RelayPrefs.setArchiveOwner(app, "", "")
        RelayPrefs.setUnconfirmedSends(app, emptyMap())
        fetchedConversations.clear()
    }

    private fun pairingEnded(e: Throwable) {
        val code = (e as? RelayException)?.code ?: if (e is CancellationException) RelayException.PAIRING_CANCELLED else e.javaClass.simpleName
        RelayLog.add(app, "Pairing did not finish: $code")
        if (_state.value.phase == RelayState.Phase.PAIRING) setState(idleState())
    }

    private fun idleState() = RelayState(if (paired) RelayState.Phase.DISCONNECTED else RelayState.Phase.UNPAIRED)

    // ============================== connecting ==============================

    private fun wantConnected() = paired && RelayPrefs.liveEnabled(app)

    /**
     * Whether the link may be connected right now: the user wants it, its
     * service is running (or a start is on its way) and the service's
     * notification can be seen.
     */
    private fun linkAllowed(): Boolean =
        wantConnected() && (serviceRunning || serviceStartsPending.get() > 0) && RelayNotifications.statusVisible(app)

    private fun connectIfWanted() {
        // Only with the service there: its notification (with Disconnect) must
        // show while connected. A reconnect timer or network callback that
        // fires after the service went away must not connect without it.
        if (!paired && serviceRunning) {
            // The service started on a stale "paired" (the bridge has since
            // found no pairing): nothing will connect, so it must not stay up.
            giveUpLive()
            return
        }
        if (!wantConnected() || !serviceRunning) return
        if (!RelayNotifications.statusVisible(app)) {
            onNotificationsHidden()
            return
        }
        synchronized(jobsLock) {
            if (connectJob?.isActive == true) {
                // It may already be past its last look at what the user wants.
                connectAgain = true
                return
            }
            connectAgain = false
            val job = scope.launch { connectNow() }
            connectJob = job
            job.invokeOnCompletion {
                val again = synchronized(jobsLock) {
                    (connectAgain && connectJob === job).also { if (it) connectAgain = false }
                }
                if (again) connectIfWanted()
            }
        }
    }

    private suspend fun connectNow() = connectLock.withLock {
        if (!wantConnected()) {
            if (!paired && serviceRunning) giveUpLive()
            return@withLock
        }
        val b = try {
            ensureBridge()
        } catch (e: RelayException) {
            RelayLog.add(app, "Could not load the link: ${e.code}")
            if (e.message == SESSION_UNREADABLE) {
                // Passing: the service stays and retries with backoff.
                scheduleReconnect()
            } else {
                // The Go library cannot be loaded: no retry fixes that.
                paired = false
                giveUpLive()
            }
            return@withLock
        }
        if (!b.isPaired) {
            // A stored session without a pairing (kept after "logged out"):
            // nothing will ever connect, so the service must not stay up.
            paired = false
            giveUpLive()
            val phase = _state.value.phase
            if (phase == RelayState.Phase.LOGGED_OUT || phase == RelayState.Phase.COOKIES_INVALID) {
                RelayNotifications.showRepairNeeded(app, phase)
            }
            return@withLock
        }
        if (!RelayNotifications.statusVisible(app)) {
            onNotificationsHidden()
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
        // stopLive(), the service stopping, or notifications being turned off
        // may have happened while connect() was blocking.
        if (!linkAllowed()) b.disconnect()
    }

    /** The link cannot run (no pairing, no library): "stay connected" off, service stopped. */
    private fun giveUpLive() {
        RelayPrefs.setLiveEnabled(app, false)
        resumeOnVisible = false
        stopConnectionWork()
        RelayService.stop(app)
    }

    /**
     * Retries after a pause that grows per attempt. The coroutine timer only
     * counts while the phone is awake; a wake-up alarm on elapsed real time
     * (allowed while idle) backs it up, so a phone asleep with its screen off
     * still retries on time ([onReconnectDue]).
     */
    private fun scheduleReconnect() {
        synchronized(jobsLock) {
            if (!wantConnected() || reconnectJob?.isActive == true) return
            val delays = timing.reconnectDelaysMs
            val wait = delays[reconnectAttempts.coerceAtMost(delays.lastIndex)]
            reconnectAttempts++
            RelayLog.add(app, "Reconnecting in ${wait / 1000} s")
            reconnectJob = scope.launch {
                delay(wait)
                cancelReconnectAlarm()
                connectIfWanted()
            }
            setReconnectAlarm(wait)
        }
    }

    private fun reconnectAlarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        app, 0,
        Intent(app, ReconnectReceiver::class.java).setAction(ReconnectReceiver.ACTION_RECONNECT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun setReconnectAlarm(waitMs: Long) {
        try {
            app.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + waitMs, reconnectAlarmIntent(),
            )
        } catch (e: RuntimeException) {
            // The coroutine timer still runs (while awake).
        }
    }

    private fun cancelReconnectAlarm() {
        try {
            app.getSystemService(AlarmManager::class.java)?.cancel(reconnectAlarmIntent())
        } catch (e: RuntimeException) {
            // Nothing to cancel.
        }
    }

    /**
     * Stops what belongs to a live connection: reconnect timer and alarm,
     * send gate timer, forward catch-up, history import.
     */
    private fun stopConnectionWork() {
        synchronized(jobsLock) {
            reconnectJob?.cancel()
            reconnectJob = null
            gateTimer?.cancel()
            gateTimer = null
        }
        cancelReconnectAlarm()
        catchUps.cancelChildren()
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

    /** Another look at the outbox later (sends still waiting for their echo). */
    private fun kickOutboxLater(delayMs: Long) {
        scope.launch {
            delay(delayMs)
            kickOutbox()
        }
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
            // The phone's data changed in ways live events do not replay.
            "resync" -> scope.launch { refreshQuietly()?.let(::startCatchUp) }
            "account" -> RelayLog.add(app, "The phone reported an account change")
        }
    }

    private fun onState(event: RelayStateEvent, now: Long) {
        val phase = PHASES[event.state] ?: return
        val previous = _state.value
        // Down because its notification is off: the UI says so, not just "disconnected".
        val code = if (phase == RelayState.Phase.DISCONNECTED && event.code.isEmpty() && wantConnected() &&
            !RelayNotifications.statusVisible(app)
        ) {
            RelayState.NOTIFICATIONS_OFF
        } else {
            event.code
        }
        setState(RelayState(phase, code, event.detail))
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
        cancelReconnectAlarm()
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

    /**
     * Once per connection: re-list the inbox, fill what arrived while the
     * link was down (forward catch-up), and start the history import if it
     * never finished.
     */
    private suspend fun initialSync() {
        val list = refreshQuietly()
        try {
            val since = gate.epoch()
            val status = requireBridge().status()
            gate.ifCurrent(since) {
                repo.updateHealth(account = status.account, phoneId = status.phoneId)
                if (status.phoneId.isNotEmpty()) RelayPrefs.setArchiveOwner(app, status.phoneId, status.account)
            }
        } catch (e: RelayException) {
            // Only for display.
        }
        if (list != null) startCatchUp(list)
        val depth = RelayPrefs.backfillDepth(app)
        val running = synchronized(jobsLock) { backfillJob?.isActive == true }
        if (depth != BackfillDepth.OFF && RelayPrefs.backfillDoneAt(app) == 0L && !running) startBackfill(depth)
    }

    /** The inbox list as the phone has it now (also stored), or null if it could not be had. */
    private fun refreshQuietly(): List<ConversationDto>? = try {
        listAndStore(Folder.INBOX)
    } catch (e: RelayException) {
        RelayLog.add(app, "Conversation list refresh failed: ${e.code}")
        null
    }

    /**
     * Fetches, for each listed conversation whose latest message the archive
     * does not have, what arrived while the link was down. The phone does
     * not replay those messages as live events, and the history import only
     * pages backwards, so without this the gap would stay for good. Runs with
     * the connection (cancelled with it), one page at a time, never alongside
     * a history import.
     */
    private fun startCatchUp(conversations: List<ConversationDto>) {
        scope.launch(catchUps) {
            pager.withLock {
                try {
                    catchUp(conversations)
                } catch (e: RuntimeException) {
                    RelayLog.add(app, "Catch-up stopped: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private suspend fun catchUp(conversations: List<ConversationDto>) {
        val behind = conversations.filter { it.latestMessageId.isNotEmpty() && repo.message(it.latestMessageId) == null }
        if (behind.isEmpty()) return
        var messages = 0
        var conversationsFilled = 0
        var failedInARow = 0
        for (conversation in behind) {
            currentCoroutineContext().ensureActive()
            val fetched = try {
                backfiller.catchUp(conversation, ::onCaughtUp).also { failedInARow = 0 }
            } catch (e: RelayException) {
                if (e.code in Backfill.ABORT_CODES || ++failedInARow >= 2) {
                    RelayLog.add(app, "Catch-up stopped: ${e.code}")
                    return
                }
                0
            }
            if (fetched > 0) {
                messages += fetched
                conversationsFilled++
                // Only after asking the phone: conversations with nothing stored cost nothing.
                delay(timing.backfillConversationPauseMs)
            }
        }
        if (conversationsFilled > 0) RelayLog.add(app, "Caught up $conversationsFilled conversations, $messages messages")
    }

    /** A message the catch-up stored: stickers in it go to the inbox like live ones (no notifications). */
    private fun onCaughtUp(dto: MessageDto, result: ArchiveRepository.UpsertResult) {
        if (dto.isDeleted || dto.id.isEmpty() || result.newMediaParts.isEmpty() || !RelayPrefs.catchStickers(app)) return
        for (part in result.newMediaParts) {
            if (isStickerCandidate(part)) scope.launch(mediaDispatcher) { checkSticker(dto, part) }
        }
    }

    private suspend fun runBackfill(depth: BackfillDepth) {
        _backfill.value = BackfillProgress(running = true, 0, 0, 0)
        try {
            if (!_state.value.isOnline) {
                withTimeoutOrNull(timing.backfillWaitOnlineMs) { state.first { it.isOnline } }
                    ?: throw RelayException(RelayException.NOT_CONNECTED, "not online")
            }
            pager.withLock {
                RelayLog.add(app, "History import started (${depth.name.lowercase()})")
                val outcome = backfiller.run(depth)
                val done = _backfill.value
                _backfill.value = done.copy(running = false, messages = outcome.messages)
                RelayPrefs.setBackfillDoneAt(app, System.currentTimeMillis())
                RelayLog.add(
                    app,
                    "History import finished: ${done.conversationsDone} conversations, ${outcome.messages} messages" +
                        if (outcome.skipped > 0) ", ${outcome.skipped} skipped" else "",
                )
            }
        } catch (e: CancellationException) {
            // Whoever cancelled it (Cancel, a new import, disconnecting) set the
            // progress already; a run replaced by a newer one must not touch it.
            throw e
        } catch (e: RelayException) {
            // Cancelled while a page was on its way: the page's failure is not this run's to report.
            currentCoroutineContext().ensureActive()
            _backfill.value = _backfill.value.copy(running = false, error = e.code)
            RelayLog.add(app, "History import stopped: ${e.code}")
        } catch (e: RuntimeException) {
            currentCoroutineContext().ensureActive()
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
        resumeOnVisible = false
        stopConnectionWork()
        RelayService.stop(app)
        RelayNotifications.showRepairNeeded(app, phase)
    }

    private fun onMessage(dto: MessageDto) {
        // Read before the upsert moves it: the conversation's newest message so far.
        val previousLatest = if (dto.isDeleted) 0L else repo.conversation(dto.conversationId)?.lastMessageTs ?: 0L
        val result = repo.upsertMessage(dto)
        if (dto.isDeleted || dto.id.isEmpty()) return
        if (result.newMediaParts.isNotEmpty() && RelayPrefs.catchStickers(app)) {
            for (part in result.newMediaParts) {
                if (isStickerCandidate(part)) scope.launch(mediaDispatcher) { checkSticker(dto, part) }
            }
        }
        if (result.inserted) {
            maybeFetchConversation(dto.conversationId)
            // An old message seen for the first time (someone reacted to it, say)
            // is not news, as in the connector: over a day old and older than
            // what the conversation already had.
            val stale = System.currentTimeMillis() - dto.timestampMs > STALE_MESSAGE_MS && dto.timestampMs < previousLatest
            if (!dto.isOld && !stale && !dto.fromMe && dto.direction == "in") MessageNotifier.onIncoming(app, repo, dto)
        }
    }

    /** A message for a conversation known only as a placeholder: ask the phone for its details once. */
    private fun maybeFetchConversation(conversationId: String) {
        val known = repo.conversation(conversationId)
        if (known != null && known.name.isNotEmpty()) return
        if (!_state.value.isOnline || !fetchedConversations.add(conversationId)) return
        scope.launch {
            try {
                val since = gate.epoch()
                val conversation = requireBridge().getConversation(conversationId)
                gate.ifCurrent(since) { repo.upsertConversation(conversation) }
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
        val since = gate.epoch()
        val part = repo.mediaPart(messageId, partId) ?: return null
        val stored = (if (thumbnail) listOf(part.thumbPath, part.localPath) else listOf(part.localPath))
            .filter { it.isNotEmpty() }.map(::File).firstOrNull { it.isFile && it.length() > 0 }
        if (stored != null) return stored
        return try {
            when {
                thumbnail && part.thumbMediaId.isNotEmpty() ->
                    downloadToFile(since, messageId, partId, part.thumbMediaId, part.thumbKey, part.mime, thumbnail = true)
                part.inlineData.isNotEmpty() ->
                    writeMedia(since, messageId, partId, MediaCache.inlineKey(messageId, partId), part.inlineData, part.mime, thumbnail = false)
                part.mediaId.isNotEmpty() ->
                    downloadToFile(since, messageId, partId, part.mediaId, part.key, part.mime, thumbnail = false)
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

    private fun downloadToFile(
        since: Long, messageId: String, partId: String, mediaId: String, key: ByteArray, mime: String, thumbnail: Boolean,
    ): File? {
        val bytes = requireBridge().downloadMedia(mediaId, key)
        return writeMedia(since, messageId, partId, mediaId, bytes, mime, thumbnail)
    }

    /** Downloads a part (also keeping it in the media cache for the conversation screen). */
    private fun download(messageId: String, partId: String, mediaId: String, key: ByteArray, mime: String, thumbnail: Boolean): ByteArray {
        val since = gate.epoch()
        val bytes = requireBridge().downloadMedia(mediaId, key)
        try {
            writeMedia(since, messageId, partId, mediaId, bytes, mime, thumbnail)
        } catch (e: java.io.IOException) {
            // The bytes are still good for the sticker inbox.
        }
        return bytes
    }

    /**
     * Stores bytes in the media cache (whole or not at all) and records the
     * path. Null, with nothing left on disk, if the archive was deleted since
     * [since], or the message went away (or its part got other media) while
     * the download ran: a decrypted file no row points at would never be
     * cleaned up.
     */
    private fun writeMedia(
        since: Long, messageId: String, partId: String, cacheKey: String, bytes: ByteArray, mime: String, thumbnail: Boolean,
    ): File? = gate.ifCurrent(since) {
        if (repo.mediaPart(messageId, partId) == null) return@ifCurrent null
        val file = MediaCache.fileFor(app, cacheKey, ImageSniffer.extensionFor(bytes, mime))
        MediaCache.writeAtomically(file, bytes)
        // When not recorded, the repository has already deleted the file (unless another part uses it).
        if (repo.setMediaLocalPath(messageId, partId, cacheKey, file.path, thumbnail)) file else null
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
        private const val WIPE_TIMEOUT_S = 60L
        private const val STALE_MESSAGE_MS = 24 * 60 * 60 * 1000L

        /** The detail of the INTERNAL error ensureBridge throws when the stored session is unreadable for now. */
        private const val SESSION_UNREADABLE = "session unreadable"
        private const val GOOGLE_MESSAGES_PACKAGE = "com.google.android.apps.messaging"

        /** The error a send queued before an unpair is held with (content-free). */
        internal const val UNPAIRED_ERROR = "unpaired"

        /** Part ids the bridge made up from the part's position; the phone cannot be asked about them. */
        private const val INDEX_PART_PREFIX = "idx:"

        private val HEIF_MIMES = setOf("image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence")

        /** Phases in which Go keeps a connection (or keeps reconnecting one). */
        private val LIVE_PHASES = setOf(
            RelayState.Phase.CONNECTING, RelayState.Phase.CONNECTED, RelayState.Phase.ACTIVE,
            RelayState.Phase.ACTIVE_ELSEWHERE, RelayState.Phase.RECONNECTING, RelayState.Phase.PHONE_OFFLINE,
        )

        /** States that say something more pressing than "notifications are off". */
        private val NEEDS_USER_PHASES = setOf(
            RelayState.Phase.UNPAIRED, RelayState.Phase.PAIRING, RelayState.Phase.LOGGED_OUT, RelayState.Phase.COOKIES_INVALID,
        )

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
