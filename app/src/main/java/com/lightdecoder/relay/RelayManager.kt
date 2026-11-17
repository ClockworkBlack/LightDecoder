package com.lightdecoder.relay

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.ContactRow
import com.lightdecoder.archive.MediaCache
import com.lightdecoder.archive.MediaRow
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.capture.StickerCapture
import com.lightdecoder.chat.Capability
import com.lightdecoder.chat.ChatBackend
import com.lightdecoder.chat.ChatService
import com.lightdecoder.chat.Presence
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
import java.util.concurrent.RejectedExecutionException
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

        /**
         * [code] of an UNPAIRED state: the pairing is gone here, but the phone
         * could not be told (offline, or Google refused). The user should
         * remove the device from Google Messages on the phone ("Device
         * pairing").
         */
        const val UNPAIR_REMOTE_FAILED = "unpair_remote_failed"
    }
}

/**
 * The phone's contacts as [RelayManager.contacts] found them. [fromPhone] is
 * false when a refresh was asked for but the phone could not be reached and
 * the stored list is shown instead (the picker says it may be out of date).
 */
data class ContactsResult(val contacts: List<ContactRow>, val fromPhone: Boolean)

/** How far a history import has got. [error] is a bridge error code if it stopped early. */
data class BackfillProgress(
    val running: Boolean,
    val conversationsDone: Int,
    val conversationsTotal: Int,
    val messages: Int,
    val error: String = "",
)

/**
 * The Google Messages link at runtime: one per process ([get]). To the
 * conversation screens it is the [ChatBackend] of [ChatService.MESSAGES].
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
 * [setMuted], [markNotSpam], [deleteConversation], [startConversation],
 * [contacts], and pairing) throw [RelayException] when the phone or Google
 * says no; [deleteMessage] and [deleteConversation] work locally without a
 * pairing. [markRead], [unpair], [deleteLocalArchive],
 * [restoreDeletedLocally] and [purgeDeleted] do not throw; [mediaFile] only
 * throws not_connected.
 *
 * Logs (RelayLog) only ever get kinds and outcomes: never message text,
 * names, numbers, the pairing emoji, cookies or keys.
 */
class RelayManager private constructor(
    context: Context,
    override val repo: ArchiveRepository,
    private val timing: Timing,
) : ChatBackend {
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
        /** Longest wait for the intake thread to delete another phone's archive. */
        val archiveWipeWaitMs: Long = 60_000,
        /** Longest wait for the intake thread to hold the queued sends of another (or an unknown) pairing. */
        val holdSendsWaitMs: Long = 10_000,
        /**
         * Whether to look at the link's notification every [notificationCheckMs]
         * while the service runs: Android 8 does not report notifications being
         * turned off (Android 9+ broadcasts it, see NotificationBlockReceiver).
         */
        val pollNotifications: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.P,
        val notificationCheckMs: Long = 120_000,
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
    override val state: StateFlow<RelayState> = _state.asStateFlow()

    override val service: ChatService get() = ChatService.MESSAGES

    /** Everything but presence and "delete for everyone", which Google Messages does not have. */
    override val capabilities: Set<Capability> = MESSAGES_CAPABILITIES

    private val _typing = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** Conversation id → names of the people typing there now. Entries expire after 6 s. */
    override val typing: StateFlow<Map<String, Set<String>>> = _typing.asStateFlow()

    /** Google Messages reports nobody's presence: always empty. */
    override val presence: StateFlow<Map<String, Presence>> = MutableStateFlow<Map<String, Presence>>(emptyMap()).asStateFlow()

    private val _backfill = MutableStateFlow(BackfillProgress(running = false, 0, 0, 0))
    override val backfill: StateFlow<BackfillProgress> = _backfill.asStateFlow()

    private val _settings = MutableStateFlow(storedSettings(app))

    /**
     * The phone's settings: the last ones it sent, kept across restarts (so
     * known right after start), null until it has sent any to this pairing.
     */
    val settings: StateFlow<SettingsDto?> = _settings.asStateFlow()

    /**
     * The bridge was seeded with the stored settings before this connect
     * ([seedSettings]): sends need not wait for the connection's first
     * settings event. Cleared with them.
     */
    @Volatile
    private var settingsSeeded = false

    // --- bridge -------------------------------------------------------------------

    private val bridgeLock = Any()

    @Volatile
    private var bridge: RelayBridge? = null

    /**
     * The archive's protocol switches were handed to the bridge: once per
     * pairing (Go forgets them on a new one or on unpair), and again after
     * the archive was deleted ([forgetProtocolSwitches]). Guarded by
     * [seedLock].
     */
    private var protocolSwitchesSeeded = false

    /** Taken while the bridge is seeded from the archive, or told to forget the seed. */
    private val seedLock = Any()

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

    /** Parent of the current connection's initial sync (and resyncs); cancelled with it. */
    private val syncs = SupervisorJob(scope.coroutineContext[Job])

    /**
     * Catch-ups and history imports page through the phone one at a time,
     * never both at once. An import takes it per conversation, so a
     * connection's catch-up waits for one conversation at most.
     */
    private val pager = Mutex()

    /**
     * The archive as it was when a connection began (or a resync arrived):
     * per conversation, the newest message from the phone stored by then
     * ([newest] null if that could not be read). A catch-up fills the gap
     * above these, so what was stored since (live messages, echoes the
     * outbox looked up, a page "load older" fetched) neither hides a
     * conversation's gap nor ends its paging early. Only valid while the
     * archive is not deleted ([epoch]).
     */
    private class CatchUpMark(val connection: Long, val epoch: Long, val newest: Map<String, Long>?)

    /** The mark of the latest connection; written on the event thread at "connecting". */
    @Volatile
    private var catchUpMark: CatchUpMark? = null

    /**
     * One stretch of a conversation a catch-up still has to fetch: from
     * below [cursor] (null: from the phone's newest message) down to the
     * message at [bound].
     */
    private data class Gap(val bound: Long, val cursor: Cursor?)

    /** Guards [gaps] and their copy on disk. */
    private val gapsLock = Any()

    /**
     * Per conversation, the gaps its catch-ups have not filled yet (the link
     * dropped, the phone stopped answering, the app was killed), newest
     * first. Each page a catch-up stores moves its gap's cursor down, and
     * the list is kept on disk, so the next catch-up (also after a restart)
     * resumes where the last one stopped instead of starting over or leaving
     * the rest behind. Cleared when the archive is deleted.
     */
    private val gaps: MutableMap<String, MutableList<Gap>> by lazy { loadGaps() }

    /** Serializes unpairs (a second tap, or a recreated screen asking again). */
    private val unpairLock = Mutex()
    private val _unpairing = MutableStateFlow(false)

    /** True while an unpair is running; the UI keeps Unpair and Disconnect disabled meanwhile. */
    val unpairing: StateFlow<Boolean> = _unpairing.asStateFlow()

    /** Drops what the phone sends back after the archive was deleted. */
    private val gate = ArchiveGate()

    /** The code of the bridge's last "unpaired" state (null until one arrives); read by [unpairNow]. */
    @Volatile
    private var bridgeUnpairedCode: String? = null

    /**
     * Unpairs whose last callbacks from Go ("session deleted", then the
     * "unpaired" state) may still be on their way: Go queues them and
     * returns, and [unpairNow] no longer waits for them. One that arrives
     * once a new pairing is under way or made belongs to the old one and is
     * ignored ([handleSession], [onState]).
     */
    private val unpairEchoes = AtomicInteger(0)

    /**
     * Intake tasks that hold the queued sends of another (or an unknown)
     * pairing and have not run yet. Nothing is sent meanwhile, even if the
     * wait for them timed out ([holdQueuedSends]).
     */
    private val ownerTasksPending = AtomicInteger(0)

    /**
     * The archive belongs to another phone or account and could not be
     * deleted yet. Nothing is sent until it is (or the owner turns out to
     * match after all); the link is not connected meanwhile ([connectNow]).
     */
    @Volatile
    private var archiveForeign = false

    /**
     * The archive has an owner, but the paired phone could not be told yet
     * (the bridge did not know it when the connection began). Until the
     * initial sync settles it ([reconcileArchiveOwner]), what the phone sends
     * live is dropped ([handleEvent]) and nothing is sent: it could be
     * another phone's, landing in this archive. The connection's catch-up
     * fetches what was dropped once ownership is settled.
     */
    @Volatile
    private var ownerPending = false

    /** Bumped by every "connecting": one number per connection. */
    private val connection = AtomicLong(0)

    /** The connection whose initial sync ran (or is running); -1 for none. Set on the event thread. */
    private val syncedConnection = AtomicLong(-1L)

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
        lookupPagePauseMs = timing.backfillPagePauseMs,
    ) {
        outboxPausedFor = connection.get()
    }
    private val backfiller = Backfill(
        repo, ::requireBridge, gate, timing.backfillPagePauseMs, timing.backfillConversationPauseMs, _backfill, pager,
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
    override fun isPaired(): Boolean = paired

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
                    onPaired(b, phoneId)
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

    /**
     * Loads the bridge ahead of pairing (the Go library and its store take a
     * moment), so that it is ready by the time the user has signed in.
     * Cheap; safe on the main thread; touches no network.
     */
    fun prepareForPairing() {
        scope.launch { runCatching { ensureBridge() } }
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
    override suspend fun useHere(): Unit = withContext(Dispatchers.IO) {
        requireBridge().setActiveSession()
    }

    /**
     * Revokes the pairing on the phone (best effort, it may be offline),
     * disconnects, and deletes the stored session. With [deleteArchive] the
     * local message archive and its media go too. The sticker inbox is never
     * touched. Does not throw.
     *
     * Ends in [RelayState.Phase.UNPAIRED]; with code
     * [RelayState.UNPAIR_REMOTE_FAILED] (also recorded as the health's last
     * error) when the phone could not be told, so the user can remove the
     * device in Google Messages on the phone themselves.
     *
     * The revoke can need a short connection of its own (Go refreshes an
     * expired token that way). If the link's service is running, it is
     * stopped only after the revoke, so its notification, with Disconnect,
     * stays up while that connection exists. If it is not running, the
     * revoke happens while the user watches the unpair in progress (the
     * screen that asked shows it while [unpairing] is true); no service is
     * started for it.
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
        // No connect from here on: the service, if running, only stays for its notification.
        RelayPrefs.setLiveEnabled(app, false)
        resumeOnVisible = false
        stopConnectionWork()
        val hadSession = SessionVault.exists(app)
        var revokeFailed = false
        val b = try {
            if (bridge != null || hadSession) ensureBridge() else null
        } catch (e: RelayException) {
            // A stored pairing that cannot be loaded cannot be revoked either.
            revokeFailed = hadSession
            null
        }
        bridgeUnpairedCode = null
        connectLock.withLock {
            val wasPaired = b?.isPaired == true
            try {
                // Returns once the phone was told; unpair_remote_failed once the
                // pairing is gone here but the phone could not be told. Its
                // "unpaired" state still follows, but is not waited for.
                // Counted first: Go may deliver them before unpair() returns.
                if (b != null) unpairEchoes.incrementAndGet()
                b?.unpair()
            } catch (e: RelayException) {
                if (e.code == RelayException.UNPAIR_REMOTE_FAILED) {
                    revokeFailed = true
                } else {
                    // Not known whether Go got as far as queueing them.
                    unpairEchoes.updateAndGet { (it - 1).coerceAtLeast(0) }
                    RelayLog.add(app, "Unpair on the phone: ${e.code}")
                    // Not known whether the phone heard of it.
                    if (wasPaired) revokeFailed = true
                }
            }
        }
        // Only now, so that its notification was there for any connection the
        // revoke needed (a Disconnect tapped meanwhile waited for this too).
        RelayService.stop(app)
        // What the bridge sent so far lands first.
        awaitEventThread()
        SessionVault.save(app, null)
        paired = false
        RelayPrefs.setBackfillDoneAt(app, 0)
        try {
            if (deleteArchive) {
                wipeArchive()
            } else {
                // Queued sends must not go out by themselves under the next
                // pairing, which may be another phone where the same
                // conversation id is someone else.
                outbox.holdAll(UNPAIRED_ERROR)
            }
        } catch (e: RuntimeException) {
            RelayLog.add(app, "Archive could not be cleared: ${e.javaClass.simpleName}")
        }
        // Read only now and merged, never overwritten: an "unpaired" state that
        // came after the wait above (it timed out) still counts.
        if (bridgeUnpairedCode == RelayState.UNPAIR_REMOTE_FAILED) revokeFailed = true
        val code = if (revokeFailed) RelayState.UNPAIR_REMOTE_FAILED else ""
        try {
            repo.updateHealth(status = "unpaired", account = "", phoneId = "", lastError = code)
        } catch (e: RuntimeException) {
            RelayLog.add(app, "Link status could not be stored: ${e.javaClass.simpleName}")
        }
        forgetSettings()
        clearTyping()
        MessageNotifier.cancelAll(app)
        RelayNotifications.cancelRepairNeeded(app)
        setState(RelayState(RelayState.Phase.UNPAIRED, code))
        RelayLog.add(app, if (deleteArchive) "Unpaired; local archive deleted" else "Unpaired; local archive kept")
        if (revokeFailed) RelayLog.add(app, "The phone could not be told; it may still list this device")
    }

    /**
     * Deletes the local message archive (messages, media, queued sends) while
     * no phone is paired. Refuses, deleting nothing, while one is: then
     * [unpair] with deleteArchive is the way. Goes through the same gate as
     * every other wipe, so nothing the phone sent just before lands after it.
     * Returns whether the archive was deleted. Does not throw.
     */
    override suspend fun deleteLocalArchive(): Boolean {
        if (paired) return false
        // In the manager's scope: a screen that goes away meanwhile does not leave it half done.
        return scope.async {
            val deleted = try {
                // On the intake thread: after sends already handed in, before later ones.
                onIntakeAndWait {
                    // Checked again here: a pairing may have finished meanwhile.
                    if (paired) {
                        false
                    } else {
                        // The link's status survives (it says "logged out" rather than "unpaired", say).
                        val health = repo.health()
                        wipeArchive()
                        if (health.status.isNotEmpty()) repo.updateHealth(status = health.status, lastError = health.lastError)
                        true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RelayLog.add(app, "Local archive could not be deleted: ${e.javaClass.simpleName}")
                false
            }
            if (deleted) {
                RelayPrefs.setBackfillDoneAt(app, 0)
                MessageNotifier.cancelAll(app)
                RelayLog.add(app, "Local archive deleted")
            }
            deleted
        }.await()
    }

    // --- sync -----------------------------------------------------------------------

    /**
     * Re-lists one folder from the phone into the archive; returns how many
     * conversations came back. The local [Folder.DELETED] is never asked of
     * the phone: it returns 0 at once.
     */
    override suspend fun refreshConversations(folder: Folder): Int = withContext(Dispatchers.IO) {
        if (!folder.listable) return@withContext 0
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
    override suspend fun loadOlder(conversationId: String, count: Int): Int = withContext(Dispatchers.IO) {
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
     *
     * Throws [RelayException] with code [RelayException.NOT_CONNECTED] when
     * the file has to be downloaded and the bridge needs a connection for
     * that (its download token expired): the caller can say "connect to load"
     * instead of "unavailable". Nothing else is thrown (except cancellation).
     */
    override suspend fun mediaFile(messageId: String, partId: String, thumbnail: Boolean): File? {
        val key = "$messageId\u0000$partId\u0000$thumbnail"
        val load = mediaLoads.computeIfAbsent(key) {
            scope.async(mediaDispatcher, start = CoroutineStart.LAZY) {
                try {
                    loadMedia(messageId, partId, thumbnail)
                } catch (e: RelayException) {
                    if (e.code == RelayException.NOT_CONNECTED) throw e
                    RelayLog.add(app, "Media could not be loaded: ${e.code}")
                    null
                } catch (t: Throwable) {
                    RelayLog.add(app, "Media could not be loaded: ${t.javaClass.simpleName}")
                    null
                }
            }.also { d -> d.invokeOnCompletion { mediaLoads.remove(key, d) } }
        }
        return load.await()
    }

    /** Asks the phone to upload the full-size original of a part that only has a thumbnail. */
    override suspend fun requestFullSize(messageId: String, partId: String): Unit = withContext(Dispatchers.IO) {
        requireBridge().requestFullSizeImage(messageId, partId)
        repo.markFullSizeRequested(messageId, partId)
    }

    // --- sending --------------------------------------------------------------------

    /**
     * Queues a text message and returns its tmpId at once. It shows in the
     * archive as "tmp:<tmpId>" right away and is sent when the link is
     * connected, surviving restarts.
     */
    override fun sendText(conversationId: String, text: String, replyToId: String?): String {
        val tmpId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        onIntake("queue a message") { outbox.enqueueText(tmpId, conversationId, text, replyToId, now) }
        return tmpId
    }

    /** Like [sendText] for an attachment (at most 100 MB), copied into app storage first. */
    override fun sendMedia(conversationId: String, uri: Uri, caption: String?, replyToId: String?): String {
        val tmpId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        // The picture alone, then its text as a message of its own, as Google's apps
        // deliver them: a picture and its caption in one request reached the phone as
        // the text only. Queued in this order on the one intake thread, they go out in it.
        onIntake("queue an attachment") { outbox.enqueueMedia(tmpId, conversationId, uri, null, replyToId, now) }
        val text = caption?.trim().orEmpty()
        if (text.isNotEmpty()) {
            val textTmpId = UUID.randomUUID().toString()
            onIntake("queue a message") { outbox.enqueueText(textTmpId, conversationId, text, null, now + 1) }
        }
        return tmpId
    }

    /** Sends a failed message again. */
    override fun retry(tmpId: String) {
        onIntake("retry a message") { outbox.retry(tmpId) }
    }

    /** Drops a failed (or still queued) message and its local copy. */
    override fun discardFailed(tmpId: String) {
        onIntake("discard a message") { outbox.discard(tmpId) }
    }

    /** Adds, switches or (with [remove]) removes the user's reaction on a message. */
    override suspend fun react(messageId: String, emoji: String, remove: Boolean): Unit = withContext(Dispatchers.IO) {
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
    override suspend fun markRead(conversationId: String): Unit = withContext(Dispatchers.IO) {
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
    override fun typing(conversationId: String) {
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

    /** Nothing to watch: Google Messages has no presence (see [capabilities]). */
    override fun watchPresence(conversationId: String) = Unit

    /** Nothing to tell: Google Messages has no "online" for this device. */
    override fun setAppVisible(visible: Boolean) = Unit

    /**
     * Deletes a message on the phone and here. A message still being sent is
     * just dropped. Without a pairing (unpaired with the archive kept, or
     * logged out) it is deleted here only, and the phone keeps it.
     */
    override suspend fun deleteMessage(messageId: String) {
        if (messageId.startsWith(MessageRow.TMP_PREFIX)) {
            val tmpId = messageId.removePrefix(MessageRow.TMP_PREFIX)
            onIntakeAndWait { outbox.discard(tmpId) }
            return
        }
        if (!paired) {
            deleteLocally { repo.applyDeletion(messageId) }
            return
        }
        withContext(Dispatchers.IO) {
            requireBridge().deleteMessage(messageId)
            repo.applyDeletion(messageId)
        }
    }

    override suspend fun setArchived(conversationId: String, archived: Boolean): Unit = withContext(Dispatchers.IO) {
        requireBridge().setConversationStatus(conversationId, if (archived) "archived" else "active")
        repo.setConversationFolder(conversationId, if (archived) Folder.ARCHIVE else Folder.INBOX)
    }

    /**
     * Moves a conversation out of spam: on the phone (Go: SetConversationStatus
     * "not_spam"), then here, into the inbox. Throws [RelayException] when the
     * phone cannot be told; nothing changes here then.
     */
    override suspend fun markNotSpam(conversationId: String): Unit = withContext(Dispatchers.IO) {
        val since = gate.epoch()
        requireBridge().setConversationStatus(conversationId, STATUS_NOT_SPAM)
        gate.ifCurrent(since) { repo.setConversationFolder(conversationId, Folder.INBOX) }
    }

    override suspend fun setMuted(conversationId: String, muted: Boolean): Unit = withContext(Dispatchers.IO) {
        requireBridge().setConversationMuted(conversationId, muted)
        repo.setConversationMuted(conversationId, muted)
    }

    /**
     * Deletes a conversation on the phone and here, with its queued sends.
     * Without a pairing it is deleted here only, and the phone keeps it.
     */
    override suspend fun deleteConversation(conversationId: String) {
        if (!paired) {
            deleteLocally { repo.deleteConversationLocal(conversationId) }
        } else {
            withContext(Dispatchers.IO) {
                requireBridge().deleteConversation(conversationId)
                repo.deleteConversationLocal(conversationId)
            }
        }
        MessageNotifier.cancel(app, conversationId)
    }

    /**
     * Moves a conversation the phone deleted (it is in [Folder.DELETED]) back
     * to the inbox, here only: the phone is not asked. Returns false if it was
     * not in the deleted folder. Does not throw [RelayException].
     */
    override suspend fun restoreDeletedLocally(conversationId: String): Boolean {
        var restored = false
        deleteLocally { restored = repo.restoreDeleted(conversationId) }
        return restored
    }

    /**
     * Deletes for good, here only, the conversation [conversationId] if the
     * phone had deleted it (it is in [Folder.DELETED]), or with null every
     * conversation in that folder, with their messages, media and queued
     * sends. Returns how many were deleted. Does not throw [RelayException].
     */
    override suspend fun purgeDeleted(conversationId: String?): Int {
        var purged = 0
        deleteLocally { purged = repo.purgeDeleted(conversationId) }
        if (conversationId != null) MessageNotifier.cancel(app, conversationId)
        if (purged > 0) RelayLog.add(app, "Deleted $purged conversation(s) for good")
        return purged
    }

    /**
     * A deletion in the archive alone (no pairing to tell): on the intake
     * thread, so it is ordered with queued sends, and through the archive
     * gate, so it never interleaves with a wipe.
     */
    private suspend fun deleteLocally(block: () -> Unit) {
        onIntakeAndWait { gate.ifCurrent(gate.epoch(), block) }
    }

    /**
     * The phone's contacts from the archive; with [refresh], fetched from the
     * phone first. A failed refresh falls back to the stored list (and only
     * throws if there is none).
     */
    suspend fun listContacts(refresh: Boolean): List<ContactRow> = contacts(refresh).contacts

    /**
     * Like [listContacts], also saying where the list came from:
     * [ContactsResult.fromPhone] is false when a refresh was asked for but
     * the phone could not be reached, and the stored list was returned
     * instead. Without [refresh] it is the stored list, and fromPhone is true
     * (nothing failed). Throws [RelayException] only if the refresh failed
     * and nothing is stored.
     */
    override suspend fun contacts(refresh: Boolean): ContactsResult = withContext(Dispatchers.IO) {
        var fromPhone = true
        if (refresh) {
            try {
                val since = gate.epoch()
                val contacts = requireBridge().listContacts()
                gate.ifCurrent(since) { repo.upsertContacts(contacts) }
            } catch (e: RelayException) {
                if (repo.contacts().isEmpty()) throw e
                fromPhone = false
            }
        }
        ContactsResult(repo.contacts(), fromPhone)
    }

    /** Opens (or creates) the conversation with these numbers and returns its id. */
    override suspend fun startConversation(numbers: List<String>, groupName: String?): String = withContext(Dispatchers.IO) {
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
     * Held by [RelayService.start] from counting a start to handing it to
     * Android, and by [RelayService.stop] from checking the count to asking
     * Android to stop. They run on different threads; without it a stop that
     * found no start pending could reach Android just after a new start,
     * stopping a service that still has to go foreground (the same crash).
     */
    internal val serviceStartLock = Any()

    /**
     * A start the user wanted that could not happen: Android refused to start
     * the service from the background, or its notification was turned off.
     * The next screen the user opens repairs it ([onAppVisible]).
     */
    @Volatile
    internal var resumeOnVisible = false

    /** The foreground service is running: connect if the user wants the link live. */
    internal fun onServiceStarted() {
        watchNotification()
        connectIfWanted()
    }

    private var notificationWatch: Job? = null

    /**
     * Android 8 does not tell the app when its notifications are turned off
     * (Android 9+ does: NotificationBlockReceiver). There, while the service
     * runs, the link's notification is looked at every
     * [Timing.notificationCheckMs] (at most 2 minutes), and a link whose
     * notification cannot be seen goes down as on the newer versions.
     */
    private fun watchNotification() {
        if (!timing.pollNotifications) return
        synchronized(jobsLock) {
            if (notificationWatch?.isActive == true) return
            notificationWatch = scope.launch {
                while (true) {
                    delay(timing.notificationCheckMs)
                    if (!serviceRunning) break
                    if (wantConnected() && !RelayNotifications.statusVisible(app)) {
                        RelayLog.add(app, "The link's notification was turned off")
                        onNotificationsHidden()
                        break
                    }
                }
            }
        }
    }

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

    /**
     * A network came up (it can be called again for every network change).
     *
     *  - ERROR: the link retries now instead of waiting out its backoff.
     *  - RECONNECTING: Go's long poll is down and may be asleep in libgm's
     *    own backoff, which grows without limit; connecting again while live
     *    restarts it at once (Go does nothing if the poll is fine).
     *  - PHONE_OFFLINE: the same, since Go reports an unresponsive phone
     *    ahead of a long poll that is down, so that state can hide one.
     *
     * Never piles up: while a connect is running nothing more is asked for.
     */
    internal fun onNetworkAvailable() {
        when (_state.value.phase) {
            RelayState.Phase.ERROR -> retryNow(queueIfBusy = false)
            RelayState.Phase.RECONNECTING, RelayState.Phase.PHONE_OFFLINE -> connectIfWanted(queueIfBusy = false)
            else -> Unit
        }
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

    private fun retryNow(queueIfBusy: Boolean = true) {
        synchronized(jobsLock) {
            if (!queueIfBusy && connectJob?.isActive == true) return
            reconnectJob?.cancel()
            reconnectJob = null
        }
        cancelReconnectAlarm()
        connectIfWanted(queueIfBusy)
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
     * A pairing was just made with the phone [phoneId] (as finishPairing
     * returned it; the bridge's status is asked if that is empty).
     * Conversation ids are only meaningful on the phone they came from
     * (another phone can have someone else behind the same id), so before
     * the link goes live:
     *  - paired with a different phone or account than the archive came
     *    from: the old archive (with its queued sends and media) is deleted;
     *  - the same phone again (after "logged out" or expired cookies): queued
     *    sends go out as planned;
     *  - not known which: queued sends wait as failed for the user to retry
     *    or discard, as after an unpair.
     * The new phone is recorded as the archive's owner unless the old
     * archive could not be deleted: then the next connect tries again
     * ([reconcileArchiveOwner]).
     */
    private fun onPaired(b: RelayBridge, phoneId: String) {
        val (previousPhone, previousAccount) = storedArchiveOwner()
        paired = true
        synchronized(seedLock) { protocolSwitchesSeeded = false }
        // Another pairing: the last one's settings (its SIMs) are not this one's.
        forgetSettings()
        RelayNotifications.cancelRepairNeeded(app)
        RelayPrefs.setBackfillDoneAt(app, 0)
        onEventThread("session") { b.exportSession()?.let { SessionVault.save(app, it) } }
        val status = runCatching { b.status() }.getOrNull()
        val phone = phoneId.ifEmpty { status?.phoneId.orEmpty() }
        val account = status?.account.orEmpty()
        val owned = when {
            isOtherOwner(previousPhone, previousAccount, phone, account) -> archiveBelongsToAnotherPhone()
            previousPhone.isEmpty() || phone.isEmpty() -> {
                archiveForeign = false
                holdQueuedSends()
                true
            }
            else -> {
                archiveForeign = false
                true
            }
        }
        if (owned && phone.isNotEmpty()) RelayPrefs.setArchiveOwner(app, phone, account)
        // Settled by this pairing, or checked again by the next connect.
        ownerPending = false
        repo.updateHealth(status = "disconnected", lastError = "", account = account, phoneId = phone)
        setState(RelayState(RelayState.Phase.DISCONNECTED))
        RelayLog.add(app, "Paired with the phone")
        startLive()
    }

    /** Who the archive belongs to (phone, account) as far as is known; empty if not known. */
    private fun storedArchiveOwner(): Pair<String, String> {
        val health = runCatching { repo.health() }.getOrNull()
        val phone = RelayPrefs.archivePhone(app).ifEmpty { health?.phoneId.orEmpty() }
        val account = RelayPrefs.archiveAccount(app).ifEmpty { health?.account.orEmpty() }
        return phone to account
    }

    /** Whether a known archive owner is a different phone or Google account than [phone]/[account]. */
    private fun isOtherOwner(ownerPhone: String, ownerAccount: String, phone: String, account: String): Boolean {
        val otherPhone = ownerPhone.isNotEmpty() && phone.isNotEmpty() && ownerPhone != phone
        val otherAccount = ownerAccount.isNotEmpty() && account.isNotEmpty() && !ownerAccount.equals(account, ignoreCase = true)
        return otherPhone || otherAccount
    }

    /**
     * Checks, before a connection goes live (and again in [initialSync]),
     * that the archive belongs to the paired phone, and records the phone as
     * its owner when no other owner is known.
     *
     * The archive can belong to another phone here only if a re-pairing did
     * not get as far as clearing it (the app was killed in between, or the
     * deletion failed). That is treated as [onPaired] treats a new phone: the
     * archive is deleted (on the intake thread, before anything of the new
     * phone is stored and before any queued send can go out), since the old
     * phone's conversation ids would otherwise mix someone else's messages
     * into a thread and send replies to whoever has that id on the new phone.
     * If it cannot be deleted, queued sends are held, nothing is sent and the
     * owner is not changed, so the next connection tries again.
     *
     * Returns whether the archive may take this pairing's messages: false
     * while it belongs to another phone (it could not be deleted, or not in
     * time). The caller then does not connect, or does not sync.
     */
    private fun reconcileArchiveOwner(b: RelayBridge): Boolean {
        // Nothing is recorded if the archive is deleted meanwhile (an unpair, a new pairing).
        var since = gate.epoch()
        val status = try {
            b.status()
        } catch (e: RelayException) {
            return ownerUnknown()
        }
        val phone = status.phoneId
        val account = status.account
        if (phone.isEmpty()) return ownerUnknown()
        val (ownerPhone, ownerAccount) = storedArchiveOwner()
        if (isOtherOwner(ownerPhone, ownerAccount, phone, account)) {
            RelayLog.add(app, "The archive is from a different phone or account than the pairing")
            if (!archiveBelongsToAnotherPhone()) return false
            since = gate.epoch()
        }
        // The archive is this phone's (or nobody's): nothing foreign to wait for.
        archiveForeign = false
        if (ownerPending) {
            ownerPending = false
            RelayLog.add(app, "The archive belongs to the paired phone; live events are stored again")
            // Sends waited for this too.
            kickOutbox()
        }
        gate.ifCurrent(since) {
            // Only over no owner or the same one (a pairing may have recorded another meanwhile).
            val now = RelayPrefs.archivePhone(app)
            if (now.isNotEmpty() && now != phone) return@ifCurrent
            RelayPrefs.setArchiveOwner(app, phone, account)
            repo.updateHealth(account = account, phoneId = phone)
        }
        return true
    }

    /**
     * The paired phone is not known right now. With an archive whose owner
     * is known, that leaves open whose archive it is: live events are held
     * back ([ownerPending]) until a later check knows. Returns whether the
     * link may connect: unless the archive is known to be another phone's.
     */
    private fun ownerUnknown(): Boolean {
        val (ownerPhone, ownerAccount) = storedArchiveOwner()
        if ((ownerPhone.isNotEmpty() || ownerAccount.isNotEmpty()) && !ownerPending) {
            ownerPending = true
            RelayLog.add(app, "The paired phone is not known yet; live events wait until it is")
        }
        return !archiveForeign
    }

    /**
     * The archive came from another phone or account: it goes, before
     * anything of the new one arrives. Returns false if it could not be
     * deleted (queued sends are then held instead, and nothing is sent
     * until a later try deletes it).
     */
    private fun archiveBelongsToAnotherPhone(): Boolean {
        // Until the deletion has run (wipeArchive clears it).
        archiveForeign = true
        val wiped = try {
            // On the intake thread: after sends already handed in, before later ones.
            val wipe = intake.submit { wipeArchive() }
            try {
                wipe.get(timing.archiveWipeWaitMs, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                // Not left queued: run later, it would delete the new phone's
                // messages mid-connection. The next try queues a new one.
                wipe.cancel(false)
                throw e
            }
            RelayLog.add(app, "Paired with a different phone; the previous phone's archive was deleted")
            true
        } catch (e: Exception) {
            RelayLog.add(app, "Previous archive could not be deleted: ${e.javaClass.simpleName}")
            holdQueuedSends()
            false
        }
        if (wiped) {
            // What was under way for the old archive stops; the import starts over for the new phone.
            catchUps.cancelChildren()
            cancelBackfill()
            RelayPrefs.setBackfillDoneAt(app, 0)
        }
        forgetSettings()
        clearTyping()
        MessageNotifier.cancelAll(app)
        return wiped
    }

    /**
     * Nothing queued under another (or an unknown) pairing goes out by
     * itself: each waits as failed for the user. Nothing is sent until the
     * hold has run, even if the wait for it here times out.
     */
    private fun holdQueuedSends() {
        ownerTasksPending.incrementAndGet()
        val hold = try {
            // On the intake thread: ordered after sends already handed in, and before startLive() opens the gate.
            intake.submit {
                try {
                    outbox.holdAll(UNPAIRED_ERROR)
                } finally {
                    ownerTasksPending.decrementAndGet()
                }
            }
        } catch (e: RejectedExecutionException) {
            ownerTasksPending.decrementAndGet() // shut down
            return
        }
        try {
            hold.get(timing.holdSendsWaitMs, TimeUnit.MILLISECONDS)
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
        clearGaps()
        RelayPrefs.setLookupSends(app, emptyMap())
        // Nothing of another phone is left to keep sends from.
        archiveForeign = false
        ownerPending = false
        forgetProtocolSwitches()
        forgetSettings()
    }

    /** Forgets the phone's stored settings (unpair, a new pairing, the archive deleted). */
    private fun forgetSettings() {
        settingsSeeded = false
        RelayPrefs.setLastSettings(app, null)
        _settings.value = null
    }

    /**
     * Before each connect: hands the bridge the settings the phone last sent
     * (Go: SeedSettings), so a send right after a restart goes from the right
     * SIM without waiting for the connection's first settings event. Nothing
     * stored: nothing to seed, and sends wait for that event as before.
     */
    private fun seedSettings(b: RelayBridge) {
        val stored = RelayPrefs.lastSettings(app)
        if (stored == null) {
            settingsSeeded = false
            return
        }
        settingsSeeded = try {
            b.seedSettings(stored)
            true
        } catch (e: Exception) {
            val kind = (e as? RelayException)?.code ?: e.javaClass.simpleName
            RelayLog.add(app, "Could not hand the link the phone's stored settings: $kind")
            false
        }
    }

    /**
     * After the archive was deleted: the bridge forgets the protocol switches
     * it was seeded with from it (they may be another phone's chats, where
     * the same conversation id is someone else), and the next connect seeds
     * it from the archive as it is now. What the bridge learned from the
     * paired phone itself stays. Best effort, never throws: if the bridge
     * cannot be told, that next seed replaces the old one all the same.
     * Runs after the deletion, so a seed being read meanwhile ([seedLock])
     * is undone here.
     */
    private fun forgetProtocolSwitches() {
        synchronized(seedLock) {
            protocolSwitchesSeeded = false
            // No bridge yet: nothing was seeded (a new one starts empty).
            val b = bridge ?: return
            try {
                b.clearProtocolSwitches()
            } catch (e: Exception) {
                val kind = (e as? RelayException)?.code ?: e.javaClass.simpleName
                RelayLog.add(app, "Could not clear the link's list of encrypted chats: $kind")
            }
        }
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

    /**
     * Connects (Go's connect: a no-op while connected, except that it
     * restarts a long poll that is down) if the link is wanted and may run.
     * While a connect is running, another one follows it, unless
     * [queueIfBusy] is false (then the running one is enough).
     */
    private fun connectIfWanted(queueIfBusy: Boolean = true) {
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
                if (queueIfBusy) connectAgain = true
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
        // Before anything of this connection is stored or any queued send goes out.
        if (!reconcileArchiveOwner(b)) {
            // Another phone's archive is still there: this phone's messages would mix into it.
            RelayLog.add(app, "Not connecting until the previous phone's archive is deleted")
            scheduleReconnect()
            return@withLock
        }
        seedProtocolSwitches(b)
        seedSettings(b)
        // That can take a while (it waits for the intake thread): the user may have tapped Disconnect meanwhile.
        if (!linkAllowed()) return@withLock
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

    /**
     * Before the first connection of a pairing, and the first one after the
     * archive was deleted (once the archive is known to be this phone's):
     * which chats the archive saw switch to or from end-to-end encryption,
     * so a send after a restart keeps such a chat on RCS even when its switch
     * lies far back in the history. On failure the bridge still learns from
     * live events and pages, and the next connect tries again.
     */
    private fun seedProtocolSwitches(b: RelayBridge) {
        synchronized(seedLock) {
            if (protocolSwitchesSeeded) return
            try {
                // Replaces any earlier seed in the bridge (e.g. one of an archive since deleted).
                b.seedProtocolSwitches(repo.protocolSwitchSeeds())
                protocolSwitchesSeeded = true
            } catch (e: Exception) {
                RelayLog.add(app, "Could not tell the link which chats are encrypted: ${e.javaClass.simpleName}")
            }
        }
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
            // Under the lock: a sync past its (uninterruptible) list call checks for this before it starts anything.
            syncs.cancelChildren()
            catchUps.cancelChildren()
        }
        cancelReconnectAlarm()
        cancelBackfill()
    }

    private fun canSend(): Boolean {
        val current = connection.get()
        return _state.value.isOnline && gateConnection == current && outboxPausedFor != current &&
            ownerTasksPending.get() == 0 && !archiveForeign && !ownerPending
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
        // A finished unpair's late "session deleted" must not delete a new pairing's session.
        if (data == null && unpairEchoes.get() > 0 && staleForUnpairEcho()) return
        // Every save (also the periodic one and on disconnect): not a token refresh by itself.
        SessionVault.save(app, data)
    }

    /** A new pairing is under way or made: an earlier unpair's late callbacks are not about it. */
    private fun staleForUnpairEcho(): Boolean = paired || _state.value.phase == RelayState.Phase.PAIRING

    private fun handleEvent(kind: String, payload: String) {
        val now = System.currentTimeMillis()
        if (kind != KIND_STATE && now - lastHealthEventWrite >= timing.healthEventIntervalMs) {
            lastHealthEventWrite = now
            repo.updateHealth(lastEventTs = now)
        }
        // The archive may be another phone's: nothing that phone sends is stored in it yet.
        if ((ownerPending || archiveForeign) && kind in ARCHIVED_KINDS) return
        when (kind) {
            KIND_STATE -> onState(RelayStateEvent.fromJson(JSONObject(payload)), now)
            "message" -> onMessage(MessageDto.fromJson(JSONObject(payload)))
            "conversation" -> repo.upsertConversation(ConversationDto.fromJson(JSONObject(payload)))
            "typing" -> onTyping(TypingDto.fromJson(JSONObject(payload)))
            "settings" -> onSettings(SettingsDto.fromJson(JSONObject(payload)), payload)
            // Go: *events.AuthTokenRefreshed. The health's "last session refresh" counts only these.
            KIND_TOKEN_REFRESHED -> repo.updateHealth(lastTokenRefresh = now)
            // The phone's data changed in ways live events do not replay.
            "resync" -> {
                val mark = markArchive(connection.get())
                scope.launch(syncs) {
                    val list = refreshQuietly() ?: return@launch
                    val sync = currentCoroutineContext()[Job]
                    // As in initialSync: nothing starts once the connection's work was stopped.
                    synchronized(jobsLock) { if (sync?.isActive != false) startCatchUp(list, mark) }
                }
            }
            "account" -> RelayLog.add(app, "The phone reported an account change")
        }
    }

    private fun onState(event: RelayStateEvent, now: Long) {
        val phase = PHASES[event.state] ?: return
        if (phase == RelayState.Phase.UNPAIRED && unpairEchoes.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) {
            // The echo of an unpair that has finished: only news if nothing was paired since.
            if (staleForUnpairEcho()) return
        }
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
                // Here, on the event thread: before anything of this connection is stored.
                catchUpMark = markArchive(connection.incrementAndGet())
                synchronized(jobsLock) { gateTimer?.cancel() }
            }
            RelayState.Phase.CONNECTED, RelayState.Phase.ACTIVE -> onOnline()
            RelayState.Phase.LOGGED_OUT -> needsRepair(phase, pairingLost = true)
            RelayState.Phase.COOKIES_INVALID -> needsRepair(phase, pairingLost = false)
            RelayState.Phase.ERROR -> scheduleReconnect()
            RelayState.Phase.UNPAIRED -> {
                paired = false
                bridgeUnpairedCode = event.code
            }
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
        // Also when this connection reached "online" only after phone_offline or
        // active_elsewhere: the connection number changes only on "connecting".
        if (syncedConnection.get() != current) {
            syncedConnection.set(current)
            // Under syncs: Disconnect, Unpair and the like cancel it with the connection.
            scope.launch(syncs) {
                // Failed: the next "online" of this connection (the phone answering again) retries.
                if (!initialSync(current)) syncedConnection.compareAndSet(current, -1L)
            }
        }
        if (settingsConnection == current || settingsSeeded) {
            // The bridge has the SIMs: from this connection's settings event, or seeded from the stored ones.
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
     * Once per connection: check the archive's owner, re-list the inbox,
     * fill what arrived while the link was down (forward catch-up), and start
     * the history import if it never finished. False if the inbox could not
     * be listed (the phone did not answer, say): then there was nothing to
     * catch up from, and the connection's next "online" state tries again.
     */
    private suspend fun initialSync(current: Long): Boolean {
        val archiveSafe = try {
            reconcileArchiveOwner(requireBridge())
        } catch (e: RelayException) {
            true // No bridge: the refresh below fails too.
        }
        // Another phone's archive that could not be deleted, or one whose owner is
        // still not known: nothing of this phone is fetched into it (the next
        // "online" of this connection tries again).
        if (!archiveSafe || ownerPending) return false
        val list = refreshQuietly()
        val sync = currentCoroutineContext()[Job]
        // The list call blocks and cannot be interrupted: Disconnect, Unpair or a
        // new connection may have come meanwhile. Checked under the lock that
        // stopConnectionWork() cancels under, so no work starts after it.
        synchronized(jobsLock) {
            if (sync?.isActive == false || connection.get() != current) return false
            if (list != null) startCatchUp(list, catchUpMark?.takeIf { it.connection == current })
            val depth = RelayPrefs.backfillDepth(app)
            val running = backfillJob?.isActive == true
            if (depth != BackfillDepth.OFF && RelayPrefs.backfillDoneAt(app) == 0L && !running) startBackfill(depth)
        }
        return list != null
    }

    /** The archive's newest stored message per conversation, now; see [CatchUpMark]. */
    private fun markArchive(forConnection: Long): CatchUpMark {
        val epoch = gate.epoch()
        val newest = try {
            repo.newestStoredTimestamps()
        } catch (e: RuntimeException) {
            RelayLog.add(app, "Could not note where the archive stands: ${e.javaClass.simpleName}")
            null
        }
        return CatchUpMark(forConnection, epoch, newest)
    }

    /** The inbox list as the phone has it now (also stored), or null if it could not be had. */
    private fun refreshQuietly(): List<ConversationDto>? = try {
        listAndStore(Folder.INBOX)
    } catch (e: RelayException) {
        RelayLog.add(app, "Conversation list refresh failed: ${e.code}")
        null
    }

    /**
     * Fetches, for each listed conversation that got messages while the link
     * was down ([behind]), what arrived meanwhile. The phone does not replay
     * those messages as live events, and the history import only pages
     * backwards, so without this the gap would stay for good. Runs with the
     * connection (cancelled with it), one page at a time, never alongside a
     * history import's paging. [mark] is where the archive stood when the
     * link came back.
     */
    private fun startCatchUp(conversations: List<ConversationDto>, mark: CatchUpMark?) {
        scope.launch(catchUps) {
            pager.withLock {
                try {
                    catchUp(conversations, mark)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    RelayLog.add(app, "Catch-up stopped: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * The bound of the new gap a listed conversation has, if any: the newest
     * message stored from the phone as of [mark], unless the phone's newest
     * message was already stored by then. A conversation with nothing stored
     * has no gap: its history is the import's (or "load older"'s).
     */
    private fun newGapBound(conversation: ConversationDto, mark: CatchUpMark?, epoch: Long): Long? {
        if (conversation.latestMessageId.isEmpty()) return null
        val bound = when {
            // The archive was deleted since: nothing in it to fill a gap above.
            mark != null && mark.epoch != epoch -> null
            mark?.newest != null -> mark.newest[conversation.id]
            else -> repo.newestStoredTimestamp(conversation.id)
        } ?: return null
        val latest = repo.message(conversation.latestMessageId)
        if (latest != null && !latest.isLocal && latest.timestampMs <= bound) return null
        return bound
    }

    /**
     * Fills, for each listed conversation, what arrived while the link was
     * down: its new gap (see [newGapBound]) and any gap an earlier catch-up
     * left unfinished ([gaps]), newest first, each to its end however many
     * pages that takes. Every page is recorded as it is stored, so a
     * catch-up cancelled with its connection (or the app) resumes there.
     */
    private suspend fun catchUp(conversations: List<ConversationDto>, mark: CatchUpMark?) {
        val epoch = gate.epoch()
        val fresh = conversations.mapNotNull { c -> newGapBound(c, mark, epoch)?.let { c.id to it } }
        // Not if the archive was deleted meanwhile (the bounds would be stale).
        gate.ifCurrent(epoch) {
            synchronized(gapsLock) {
                var changed = false
                for ((id, bound) in fresh) {
                    val list = gaps.getOrPut(id) { ArrayList() }
                    // A gap from the newest message down to this bound or below already covers it.
                    if (list.any { it.cursor == null && it.bound <= bound }) continue
                    list.add(0, Gap(bound, null))
                    changed = true
                }
                if (changed) saveGaps()
            }
        } ?: return
        val work = synchronized(gapsLock) { conversations.filter { gaps[it.id]?.isNotEmpty() == true } }
        var messages = 0
        var conversationsFilled = 0
        var failedInARow = 0
        for (conversation in work) {
            currentCoroutineContext().ensureActive()
            var fetched = 0
            try {
                while (true) {
                    val gap = synchronized(gapsLock) { gaps[conversation.id]?.firstOrNull() } ?: break
                    fetched += fillGap(conversation.id, gap)
                }
                failedInARow = 0
            } catch (e: RelayException) {
                if (e.code in Backfill.ABORT_CODES || ++failedInARow >= 2) {
                    RelayLog.add(app, "Catch-up stopped: ${e.code}")
                    return
                }
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

    /** Pages through one gap to its end, recording each page; the gap is forgotten once filled. */
    private suspend fun fillGap(conversationId: String, gap: Gap): Int {
        var current = gap
        val fetched = backfiller.catchUp(
            conversationId, gap.bound, ::onCaughtUp,
            startCursor = gap.cursor,
            onPage = { next ->
                val moved = Gap(gap.bound, next)
                synchronized(gapsLock) {
                    val list = gaps[conversationId]
                    val index = list?.indexOf(current) ?: -1
                    if (index >= 0) {
                        list!![index] = moved
                        saveGaps()
                    }
                }
                current = moved
            },
        )
        synchronized(gapsLock) {
            val list = gaps[conversationId]
            if (list != null && list.remove(current)) {
                if (list.isEmpty()) gaps.remove(conversationId)
                saveGaps()
            }
        }
        return fetched
    }

    private fun loadGaps(): MutableMap<String, MutableList<Gap>> {
        val out = HashMap<String, MutableList<Gap>>()
        try {
            val json = JSONObject(RelayPrefs.catchUpGaps(app) ?: return out)
            for (id in json.keys()) {
                val array = json.getJSONArray(id)
                val list = ArrayList<Gap>()
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val cursor = if (o.has("ci")) Cursor(o.getString("ci"), o.getLong("ct")) else null
                    list.add(Gap(o.getLong("b"), cursor))
                }
                if (list.isNotEmpty()) out[id] = list
            }
        } catch (e: Exception) {
            // Unreadable: those gaps are left to "load older" and the import.
            RelayLog.add(app, "Unfinished catch-ups could not be read: ${e.javaClass.simpleName}")
        }
        return out
    }

    /** Under [gapsLock]. */
    private fun saveGaps() {
        val json = JSONObject()
        for ((id, list) in gaps) {
            if (list.isEmpty()) continue
            json.put(id, org.json.JSONArray(list.map { gap ->
                JSONObject().put("b", gap.bound).apply {
                    gap.cursor?.let { put("ci", it.lastItemId).put("ct", it.lastItemTimestampMs) }
                }
            }))
        }
        try {
            RelayPrefs.setCatchUpGaps(app, if (json.length() == 0) null else json.toString())
        } catch (e: RuntimeException) {
            RelayLog.add(app, "Catch-up progress could not be kept: ${e.javaClass.simpleName}")
        }
    }

    private fun clearGaps() = synchronized(gapsLock) {
        gaps.clear()
        RelayPrefs.setCatchUpGaps(app, null)
    }

    /** A message the catch-up stored: stickers in it go to the inbox like live ones (no notifications). */
    private fun onCaughtUp(dto: MessageDto, result: ArchiveRepository.UpsertResult) {
        if (dto.isDeleted || dto.id.isEmpty() || result.newMediaParts.isEmpty()) return
        requestFullSizes(dto, result.newMediaParts)
        if (!RelayPrefs.catchStickers(app)) return
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
            RelayLog.add(app, "History import started (${depth.name.lowercase()})")
            // It takes the pager per conversation (see Backfill.run).
            val outcome = backfiller.run(depth)
            val done = _backfill.value
            _backfill.value = done.copy(running = false, messages = outcome.messages)
            RelayPrefs.setBackfillDoneAt(app, System.currentTimeMillis())
            RelayLog.add(
                app,
                "History import finished: ${done.conversationsDone} conversations, ${outcome.messages} messages" +
                    if (outcome.skipped > 0) ", ${outcome.skipped} skipped" else "",
            )
        } catch (e: CancellationException) {
            // Cancelled (Cancel, disconnecting), or the archive was deleted under
            // it (Backfill.ArchiveWiped, which nobody cancelled). Whoever cancelled
            // it reset the progress, but the run may have written "running" again
            // just after; so it is reset here too, unless a newer run has
            // replaced this one (that run's progress is not this one's to touch).
            val self = currentCoroutineContext()[Job]
            synchronized(jobsLock) {
                if (backfillJob == null || backfillJob === self) _backfill.value = _backfill.value.copy(running = false)
            }
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
        if (dto.fromMe && !dto.isDeleted) logOutgoing(dto)
        // One of the user's messages the phone now says was read, and did not before.
        val wasUnread = dto.fromMe && !dto.isDeleted && dto.id.isNotEmpty() && ReadNotifier.isRead(dto.status) &&
            repo.message(dto.id)?.let { it.isOutgoing && !ReadNotifier.isRead(it.status) } == true
        val result = repo.upsertMessage(dto)
        if (dto.isDeleted || dto.id.isEmpty()) return
        if (wasUnread) repo.message(dto.id)?.let { ReadNotifier.onRead(app, repo, listOf(it)) }
        requestFullSizes(dto, result.newMediaParts)
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

    /**
     * For the problem report: what the phone did with a message sent from
     * here (its echo) and how an outgoing picture's status moves. Kinds and
     * status names only, never text, names or numbers.
     */
    private fun logOutgoing(dto: MessageDto) {
        try {
            val queued = dto.tmpId.takeIf { it.isNotEmpty() }?.let { repo.outbox(it) }
            val local = dto.tmpId.takeIf { it.isNotEmpty() }?.let { repo.message(MessageRow.tmpMessageId(it)) }
            val pictures = dto.mediaParts.size
            if (local != null) {
                val sentPicture = queued?.mediaLocalPath?.isNotEmpty() == true || local.media.isNotEmpty()
                RelayLog.add(
                    app,
                    "Phone's copy of a sent ${if (sentPicture) "picture" else "message"} arrived: " +
                        "$pictures picture part(s), text ${if (dto.text.isBlank()) "none" else "yes"}, " +
                        "status ${dto.status.ifEmpty { "-" }}, ${dto.transport}" +
                        if (sentPicture && pictures == 0) " - THE PICTURE IS MISSING" else "",
                )
                return
            }
            if (pictures == 0) return
            val before = repo.message(dto.id)?.status
            if (before != null && before != dto.status) {
                RelayLog.add(app, "Sent picture: status ${before.ifEmpty { "-" }} -> ${dto.status.ifEmpty { "-" }}, ${dto.transport}")
            }
        } catch (e: RuntimeException) {
            // Only a log line.
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

    private fun onSettings(dto: SettingsDto, payload: String) {
        _settings.value = dto
        // Kept for the next run's seed (never logged): the event is this pairing's.
        try {
            RelayPrefs.setLastSettings(app, payload)
        } catch (e: RuntimeException) {
            RelayLog.add(app, "Could not keep the phone's settings: ${e.javaClass.simpleName}")
        }
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
            // The real media first: inline bytes next to a media id or a thumbnail
            // are only a small preview, and a sticker made of that is a blur.
            val bytes = when {
                part.mediaId.isNotEmpty() -> download(dto.id, part.partId, part.mediaId, part.key, part.mime, thumbnail = false)
                part.needsFullSize || part.thumbMediaId.isNotEmpty() -> {
                    requestFullSizeOnce(dto.id, part)
                    null
                }
                part.inlineData.isNotEmpty() -> part.inlineData
                else -> null
            } ?: return
            val title = repo.conversation(dto.conversationId)?.name.orEmpty()
            StickerCapture.capture(
                app,
                bytes,
                StickerCapture.Meta(
                    id = InboxStore.relayIdFor(stickerPhoneId(), dto.conversationId, dto.id, part.partId),
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

    /** The phone a message comes from, for sticker ids: the archive's owner, else the pairing's phone, else "". */
    private fun stickerPhoneId(): String =
        RelayPrefs.archivePhone(app).ifEmpty { runCatching { bridge?.status()?.phoneId }.getOrNull().orEmpty() }

    /** Only a thumbnail so far: ask the phone for the original, once. Its update comes back as a message event. */
    private fun requestFullSizeOnce(messageId: String, part: MediaRow) {
        if (part.fullSizeRequested || part.partId.isEmpty() || part.partId.startsWith(INDEX_PART_PREFIX)) return
        if (!_state.value.isOnline) return
        // The sticker check and the automatic request can both get here for one part.
        val key = "$messageId\u0000${part.partId}"
        if (!fullSizeAsking.add(key)) return
        try {
            if (repo.mediaPart(messageId, part.partId)?.fullSizeRequested == true) return
            requireBridge().requestFullSizeImage(messageId, part.partId)
            repo.markFullSizeRequested(messageId, part.partId)
        } catch (e: RelayException) {
            RelayLog.add(app, "Full-size request failed: ${e.code}")
        } finally {
            fullSizeAsking.remove(key)
        }
    }

    /** Parts whose full-size request is being sent right now. */
    private val fullSizeAsking: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Pictures and videos that arrived with only a thumbnail on the phone:
     * ask for the originals at once, as Messages for web does when they are
     * shown, so the conversation shows them full size without a tap. Only for
     * messages of the last [FULL_SIZE_AUTO_MS] (live ones and what a catch-up
     * brings), not for old history.
     */
    private fun requestFullSizes(dto: MessageDto, parts: List<MediaRow>) {
        if (System.currentTimeMillis() - dto.timestampMs > FULL_SIZE_AUTO_MS) return
        for (part in parts) {
            if (!part.needsFullSize || part.fullSizeRequested) continue
            val mime = part.mime.substringBefore(';').trim().lowercase()
            if (mime.isNotEmpty() && !mime.startsWith("image/") && !mime.startsWith("video/")) continue
            scope.launch(mediaDispatcher) { requestFullSizeOnce(dto.id, part) }
        }
    }

    /**
     * A part's file: the original ([thumbnail] false) or the phone's thumbnail.
     * The original comes from the media id; inline bytes only when they are
     * the media itself (nothing to download), never the small preview the
     * phone sends next to a picture. A local file earlier versions made of
     * that preview does not count.
     */
    private fun loadMedia(messageId: String, partId: String, thumbnail: Boolean): File? {
        val since = gate.epoch()
        val part = repo.mediaPart(messageId, partId) ?: return null
        val local = part.localPath.takeUnless { part.inlineIsPreview && MediaCache.isInlineFile(it, messageId, partId) }.orEmpty()
        val stored = (if (thumbnail) listOf(part.thumbPath, local) else listOf(local))
            .filter { it.isNotEmpty() }.map(::File).firstOrNull { it.isFile && it.length() > 0 }
        if (stored != null) return stored
        return try {
            when {
                thumbnail && part.thumbMediaId.isNotEmpty() ->
                    downloadToFile(since, messageId, partId, part.thumbMediaId, part.thumbKey, part.mime, thumbnail = true)
                part.mediaId.isNotEmpty() ->
                    downloadToFile(since, messageId, partId, part.mediaId, part.key, part.mime, thumbnail = false)
                part.inlineIsMedia ->
                    writeMedia(since, messageId, partId, MediaCache.inlineKey(messageId, partId), part.inlineData, part.mime, thumbnail = false)
                else -> null
            }
        } catch (e: RelayException) {
            // Not connected (the download needs a fresh token): the caller says "connect to load".
            if (e.code == RelayException.NOT_CONNECTED) throw e
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
        // Written and recorded in one step as far as the cleanup of unused files can tell.
        repo.withMediaFileLock {
            MediaCache.writeAtomically(file, bytes)
            // When not recorded, the repository has already deleted the file (unless another part uses it).
            if (repo.setMediaLocalPath(messageId, partId, cacheKey, file.path, thumbnail)) file else null
        }
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

    /** Whether a connect is running right now. */
    internal fun connectRunningForTesting(): Boolean = synchronized(jobsLock) { connectJob?.isActive == true }

    internal fun shutdownForTesting() {
        scope.cancel()
        events.shutdownNow()
        intake.shutdownNow()
    }

    companion object {
        private const val KIND_STATE = "state"
        private const val KIND_TOKEN_REFRESHED = "token_refreshed"

        /** Events whose content goes into the archive (or is read against it); held back while its owner is unsettled. */
        private val ARCHIVED_KINDS = setOf("message", "conversation", "typing", "resync")
        private const val PAIRING_TIMEOUT_MS = 180_000L
        private const val CONVERSATION_COUNT = 100
        private const val EVENT_DRAIN_TIMEOUT_S = 10L
        private const val STALE_MESSAGE_MS = 24 * 60 * 60 * 1000L

        /** Originals of pictures this recent are asked of the phone without a tap ([requestFullSizes]). */
        private const val FULL_SIZE_AUTO_MS = 7 * 24 * 60 * 60 * 1000L

        /** The detail of the INTERNAL error ensureBridge throws when the stored session is unreadable for now. */
        private const val SESSION_UNREADABLE = "session unreadable"
        private const val GOOGLE_MESSAGES_PACKAGE = "com.google.android.apps.messaging"

        /** The conversation status that moves one out of spam (Go: SetConversationStatus). */
        internal const val STATUS_NOT_SPAM = "not_spam"

        /** The error a send queued before an unpair is held with (content-free). */
        internal const val UNPAIRED_ERROR = "unpaired"

        /** Part ids the bridge made up from the part's position; the phone cannot be asked about them. */
        private const val INDEX_PART_PREFIX = "idx:"

        private val HEIF_MIMES = setOf("image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence")

        /**
         * What Google Messages can do: all of [Capability] except presence
         * and "delete for everyone". Listed one by one, so a capability added
         * for another service is not claimed here by accident.
         */
        private val MESSAGES_CAPABILITIES: Set<Capability> = setOf(
            Capability.SPAM_FOLDER, Capability.DELETED_FOLDER, Capability.FULL_SIZE_REQUEST,
            Capability.USE_HERE, Capability.SUBJECT, Capability.NEW_GROUP,
        )

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

        /** The settings kept from the last run, or null (none, or unreadable). */
        private fun storedSettings(context: Context): SettingsDto? = try {
            RelayPrefs.lastSettings(context)?.let { SettingsDto.fromJson(JSONObject(it)) }
        } catch (e: Exception) {
            null
        }

        private fun named(name: String) = ThreadFactory { r -> Thread(r, name).apply { isDaemon = true } }
    }
}
