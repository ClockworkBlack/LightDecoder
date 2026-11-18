package com.lightdecoder.wa

import com.lightdecoder.relay.ReadNotifier
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.MediaCache
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.chat.Capability
import com.lightdecoder.chat.ChatBackend
import com.lightdecoder.chat.ChatService
import com.lightdecoder.chat.Presence
import com.lightdecoder.relay.ArchiveGate
import com.lightdecoder.relay.BackfillProgress
import com.lightdecoder.relay.ContactsResult
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.Cursor
import com.lightdecoder.relay.Folder
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.ReconnectReceiver
import com.lightdecoder.relay.RelayException
import com.lightdecoder.relay.RelayNotifications
import com.lightdecoder.relay.RelayState
import com.lightdecoder.relay.RelayStateEvent
import com.lightdecoder.relay.TypingDto
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The WhatsApp link at runtime: one per process ([get]). To the conversation
 * screens it is the [ChatBackend] of [ChatService.WHATSAPP]; to the WhatsApp
 * setup and status screens it is also the pairing, connect and unpair API
 * below.
 *
 * It owns the [WaBridge] (created lazily off the main thread, since that
 * loads the Go library and opens the bridge's store), turns the bridge's
 * events into archive writes, [state] and the other flows, runs the outbox,
 * and decides when to connect.
 *
 * The rules it follows, as the Google Messages link does:
 *  - Linking is never automated. [requestPairingCode] and [startQr] only
 *    ask WhatsApp for a code; the user types it into WhatsApp on their own
 *    phone, or scans it, themselves.
 *  - While connected, [WaService] shows a notification with Disconnect, and
 *    the link does not run without it. [stopLive] and [unpair] always work.
 *  - Everything stays on this phone: the bridge's store in [storeDir], the
 *    archive in [repo], media in the archive's own directories. All of it is
 *    excluded from backup.
 *  - [WaLog] and anything else that leaves the app's storage never get
 *    message text, names, numbers, JIDs, message ids, pairing or QR codes,
 *    or keys.
 *
 * Threads:
 *  - Bridge callbacks arrive on Go's dispatcher thread and are handed to one
 *    event thread, in order, so Go never waits on the database. Runs of
 *    history messages are stored together.
 *  - New sends go through one intake thread, so they keep their order.
 *  - Everything that talks to WhatsApp runs on [Dispatchers.IO]; nothing
 *    here calls the bridge on the main thread.
 *
 * Without a link, the suspend functions that need one throw [RelayException]
 * with [RelayException.NOT_PAIRED] and never load the Go library: for
 * someone who never links WhatsApp, the library is never touched from here.
 * [deleteMessage] ("for me") and [deleteConversation] work on the archive
 * alone then. [markRead], [unpair], [deleteLocalArchive],
 * [restoreDeletedLocally] and [purgeDeleted] do not throw; [mediaFile] only
 * throws not_connected.
 */
class WaManager private constructor(
    context: Context,
    override val repo: ArchiveRepository,
    private val timing: Timing,
) : ChatBackend {

    /** Delays, as one object so tests can shorten them. */
    internal data class Timing(
        /** Pauses before sending again a message WhatsApp refused or whose upload broke off. */
        val outboxRetryDelaysMs: List<Long> = listOf(3_000, 8_000, 20_000),
        /** How long "typing…" shows without news; WhatsApp sends it once, and "stopped" when it stops. */
        val typingExpiryMs: Long = 25_000,
        /** This account's own "typing" is told again at most this often. */
        val typingThrottleMs: Long = 8_000,
        /** This long after the last keystroke the chat is told typing stopped. */
        val typingPauseMs: Long = 10_000,
        /** Pauses before reconnecting after an error the bridge does not retry, growing per attempt. */
        val reconnectDelaysMs: List<Long> = listOf(15_000, 30_000, 60_000, 120_000, 300_000, 900_000),
        /** last_event_ts is written at most this often. */
        val healthEventIntervalMs: Long = 10_000,
        /** A history sync that says nothing for this long is taken as over. */
        val historyIdleMs: Long = 90_000,
        /** How long [loadOlder] waits for the phone to answer a request for older history. */
        val loadOlderWaitMs: Long = 6_000,
        /** After the first older message arrived, how long [loadOlder] waits for the rest of the batch. */
        val loadOlderSettleMs: Long = 400,
        /** The bridge asks the phone for older history of one chat at most this often. */
        val historyRequestIntervalMs: Long = 60_000,
        /** "Online" is tried again after this long while WhatsApp has not finished syncing. */
        val presenceRetryMs: Long = 3_000,
        /** How long after the last stored event the "events were being stored" mark is taken back. */
        val busyClearMs: Long = 2_000,
        /** Longest wait for the intake thread to hold queued sends or delete an archive. */
        val intakeWaitMs: Long = 60_000,
        /** As for the Google Messages link: Android 8 does not report notifications being turned off. */
        val pollNotifications: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.P,
        val notificationCheckMs: Long = 120_000,
    )

    /** The application's context, never a screen's (the manager outlives every screen). */
    private val appContext: Context = context.applicationContext

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            WaLog.add(appContext, "Internal error: ${t.javaClass.simpleName}")
        }
    )
    private val intake: ExecutorService = Executors.newSingleThreadExecutor(named("wa-intake"))

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val mediaDispatcher = Dispatchers.IO.limitedParallelism(2)

    /** Typing and presence calls, one at a time and in the order they were asked for. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val signals = Dispatchers.IO.limitedParallelism(1)

    override val service: ChatService get() = ChatService.WHATSAPP

    /**
     * Presence and "delete for everyone". No spam or deleted folder, no
     * full-size requests, no "use here", no subjects; and no new groups,
     * which the bridge does not create.
     */
    override val capabilities: Set<Capability> = setOf(Capability.PRESENCE, Capability.DELETE_FOR_EVERYONE, Capability.EDIT)

    /** Whether a link is stored, as far as known without the bridge ([WaPrefs.link]); the bridge corrects it once loaded. */
    @Volatile
    private var paired: Boolean = WaPrefs.link(appContext) == WaPrefs.LINK_LINKED

    private val _state = MutableStateFlow(idleState())

    /**
     * Where the link stands. WhatsApp uses UNPAIRED, PAIRING (a linking
     * attempt is open), DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING
     * (the connection dropped and the bridge is retrying), LOGGED_OUT (the
     * phone or WhatsApp ended the link) and ERROR, whose code says why
     * ([WaErrors]): after [WaErrors.TEMP_BANNED] and
     * [WaErrors.CLIENT_OUTDATED] nothing is retried and "stay connected" is
     * turned off; other errors are retried with a growing pause.
     */
    override val state: StateFlow<RelayState> = _state.asStateFlow()

    private val _typing = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    override val typing: StateFlow<Map<String, Set<String>>> = _typing.asStateFlow()

    private val _presence = MutableStateFlow<Map<String, Presence>>(emptyMap())

    /**
     * Participant id (a user's JID) → presence, for the people
     * [watchPresence] asked about. WhatsApp only reports it while this
     * account shows as online itself ([setAppVisible]); it is emptied when
     * no conversation screen is in front any more, and when the connection
     * goes.
     */
    override val presence: StateFlow<Map<String, Presence>> = _presence.asStateFlow()

    private val _backfill = MutableStateFlow(BackfillProgress(running = false, 0, 0, 0))

    /**
     * The phone's history sync after linking, as far as it has got. WhatsApp
     * reports a percentage, not conversations: [BackfillProgress.conversationsDone]
     * is that percentage and [BackfillProgress.conversationsTotal] is 100;
     * [BackfillProgress.messages] counts the history messages stored since
     * the sync began.
     */
    override val backfill: StateFlow<BackfillProgress> = _backfill.asStateFlow()

    // ============================== pairing, for the setup screen ==============================

    private val _pairing = MutableStateFlow<WaPairing>(WaPairing.Idle)

    /**
     * Where linking stands. Idle until [requestPairingCode] or [startQr];
     * then WaitingForCode / WaitingForQr while the user confirms on their
     * phone; then Paired or Failed. [cancelPairing] takes it back to Idle.
     */
    val pairing: StateFlow<WaPairing> = _pairing.asStateFlow()

    private val _qr = MutableStateFlow<String?>(null)

    /**
     * The text of the QR code to show now, after [startQr]; null when there
     * is none (not asked for, linked, failed or cancelled). WhatsApp replaces
     * it every few seconds. Never log it.
     */
    val qr: StateFlow<String?> = _qr.asStateFlow()

    private val _unpairing = MutableStateFlow(false)

    /** True while an [unpair] is running; the status screen keeps Unpair and Disconnect disabled meanwhile. */
    val unpairing: StateFlow<Boolean> = _unpairing.asStateFlow()

    /** What the user asked for in the linking attempt that is open (if any). */
    private enum class PairingMode { NONE, CODE, QR }

    @Volatile
    private var pairingMode = PairingMode.NONE

    // --- bridge -------------------------------------------------------------------

    private val bridgeLock = Any()

    @Volatile
    private var bridge: WaBridge? = null

    private val sink = WaBridge.Sink { kind, payload -> enqueueEvent(kind, payload) }

    // --- connection bookkeeping -------------------------------------------------------

    private val jobsLock = Any()
    private val connectLock = Mutex()
    private var connectJob: Job? = null

    /** A connect was asked for while one was still running: try again when it ends. */
    private var connectAgain = false
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var notificationWatch: Job? = null

    /** Serializes unpairs (a second tap, or a recreated screen asking again). */
    private val unpairLock = Mutex()

    /** Drops what the bridge hands back after the archive was deleted. */
    private val gate = ArchiveGate()

    /**
     * The archive belongs to another WhatsApp account and could not be
     * deleted yet. Nothing is stored in it or sent from it until it is; the
     * link is not connected meanwhile ([connectNow]).
     */
    @Volatile
    private var archiveForeign = false

    /** The bridge said "not connected" to a send; the outbox waits for the next "connected". */
    @Volatile
    private var sendsPaused = false

    /**
     * The bridge was told to connect (or connects by itself, after linking)
     * and has not been told to disconnect, nor ended the connection itself,
     * since. It says so before [state] does: the bridge's "connecting"
     * arrives through the event thread, later.
     */
    @Volatile
    private var bridgeWanted = false

    /** Intake tasks that hold queued sends and have not run yet: nothing is sent meanwhile. */
    private val holdsPending = AtomicInteger(0)

    @Volatile
    private var lastHealthEventWrite = 0L

    // --- events ---------------------------------------------------------------------

    private class Event(val kind: String, val payload: String)

    private class Task(val block: () -> Unit)

    /** Bridge events ([Event]) and things to do in order with them ([Task]), for the event thread. */
    private val inbox = LinkedBlockingQueue<Any>()

    private val eventThread = Thread(::pump, "wa-events").apply { isDaemon = true }

    /**
     * Guards [busy], [pendingStored] and the busy mark on disk.
     *
     * The bridge keeps every message in its own store and hands each over
     * once; one handed over but not stored yet when the app dies would be
     * missing from the archive for good. So while events that go into the
     * archive are queued, a mark stands on disk ([busyMark]). If it is found
     * at the next start, the archive is compared with the bridge's store
     * before the link connects ([reconcileStore]), and what is missing is
     * stored then.
     */
    private val busyLock = Any()
    private var busy = false
    private var pendingStored = 0
    private var busyClear: Job? = null

    /** The archive may be behind the bridge's store: compare them before the next connect. */
    @Volatile
    private var storeCheckNeeded = false

    // --- outbox, media, typing, presence, history ---------------------------------------------

    private val outbox = WaOutbox(
        appContext, repo, ::requireBridge, timing.outboxRetryDelaysMs, ::canSend, gate,
        onOffline = { sendsPaused = true },
        onSent = { applyEarlyReceipt(it.id) },
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
                    WaLog.add(appContext, "Outbox stopped: ${t.javaClass.simpleName}")
                }
            }
        }
    }
    private val mediaLoads = ConcurrentHashMap<String, Deferred<File?>>()

    private class Typist(val name: String, val until: Long)

    private val typingLock = Any()
    private val typists = HashMap<String, HashMap<String, Typist>>()

    /** Conversations this account is shown as typing in: when that was last told. */
    private val typingSentAt = ConcurrentHashMap<String, Long>()
    private val typingPauses = HashMap<String, Job>()

    /** How many conversation screens are in front ([setAppVisible]); guarded by [presenceLock]. */
    private var visibleScreens = 0
    private val presenceLock = Any()

    /** The people whose presence is wanted while a screen is in front. */
    private val watched: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** What this connection was told: touched only by [presenceWorker]. */
    private var announcedOnline = false
    private val subscribed = HashSet<String>()
    private var onlineTries = 0
    private val presenceKicks = Channel<Unit>(Channel.CONFLATED)
    private val presenceWorker: Job by lazy {
        scope.launch(signals) {
            for (kick in presenceKicks) {
                try {
                    syncPresence()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    WaLog.add(appContext, "Presence stopped: ${t.javaClass.simpleName}")
                }
            }
        }
    }

    /** Receipts that came before their message was stored (a send still returning): message id → status. */
    private val earlyReceipts = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > MAX_EARLY_RECEIPTS
    }

    /** Per conversation, up to when this process told WhatsApp its messages were read. */
    private val readUpTo = ConcurrentHashMap<String, Long>()

    /** Chats deleted here, as [WaPrefs.deletedChats] keeps them; guarded by itself. */
    private val deletedChats: MutableMap<String, Long> by lazy { HashMap(WaPrefs.deletedChats(appContext)) }

    /** Conversations a [loadOlder] is waiting on: how many history messages arrived for each. */
    private val olderArrivals = ConcurrentHashMap<String, AtomicInteger>()

    /** When this process last made the bridge ask the phone for a chat's older history. */
    private val historyAskedAt = ConcurrentHashMap<String, Long>()

    /** Chats whose phone had nothing older than this message when last asked. */
    private val historyEnd = ConcurrentHashMap<String, String>()

    private var historyIdle: Job? = null

    /** History messages stored since linking (in this process); touched only by the event thread. */
    private var historyStored = 0

    // --- what the service and the manager tell each other (see "service hooks") ----------------

    /** Whether [WaService] exists right now (set by the service itself). */
    @Volatile
    internal var serviceRunning = false

    /**
     * Foreground starts Android accepted whose onStartCommand has not called
     * startForeground yet. Stopping the service in that window makes Android
     * crash the app, so [WaService.stop] leaves it to the pending start,
     * which goes foreground and then stops itself if the link is no longer
     * wanted.
     */
    internal val serviceStartsPending = AtomicInteger(0)

    /** Held by [WaService.start] and [WaService.stop] around the count and the call to Android, as for the Google Messages link's service. */
    internal val serviceStartLock = Any()

    /**
     * A start the user wanted that could not happen: Android refused to start
     * the service from the background (which is where the app is when
     * linking finishes: the user is in WhatsApp typing the code), or its
     * notification was turned off. The next screen the user opens repairs it
     * ([onAppVisible]).
     */
    @Volatile
    internal var resumeOnVisible = false

    // Last, once everything above exists: the event thread and the coroutine below use it.
    init {
        storeCheckNeeded = busyMark().exists()
        busy = storeCheckNeeded
        eventThread.start()
        // With a link stored, load the bridge in the background so that
        // isPaired() and the state settle before anyone needs them.
        if (paired) scope.launch { runCatching { ensureBridge() } }
    }

    /** Whether this device is linked to a WhatsApp account. Cheap; safe on the main thread. */
    override fun isPaired(): Boolean = paired

    /**
     * Asks WhatsApp for a pairing code for the user's own [phoneNumber]
     * (international format; "+", spaces and dashes are ignored) and returns
     * its 8 characters; [pairing] becomes WaitingForCode with the same code.
     * The user types it into WhatsApp on that phone themselves (Settings >
     * Linked devices > Link a device > Link with phone number instead);
     * [pairing] then becomes Paired, or Failed. Throws [RelayException]
     * (invalid_argument, rate_limited, timeout, already_paired, ...).
     * The code is a secret while it is valid: show it, never log it.
     */
    suspend fun requestPairingCode(phoneNumber: String): String {
        val b = withContext(Dispatchers.IO) { ensureBridge() }
        if (paired) throw RelayException(WaErrors.ALREADY_PAIRED, "already linked; unlink first")
        pairingMode = PairingMode.CODE
        _qr.value = null
        _pairing.value = WaPairing.Idle
        setState(RelayState(RelayState.Phase.PAIRING))
        WaLog.add(appContext, "Linking started (pairing code)")
        return try {
            val code = blockingCall(onCancel = { b.cancelPairing() }) {
                // The linking connection first; one that is open already is kept.
                b.connect()
                b.pairPhone(phoneNumber)
            }
            // Only if this attempt is still the one the user is waiting on.
            if (pairingMode == PairingMode.CODE && !paired) _pairing.value = WaPairing.WaitingForCode(code)
            code
        } catch (e: Throwable) {
            pairingEnded(e)
            throw e
        }
    }

    /**
     * Starts linking by QR code instead: [qr] (and [pairing], as
     * WaitingForQr) then carry each code WhatsApp hands out, until the user
     * scans one with their phone, the codes run out, or [cancelPairing].
     * Throws [RelayException] when linking cannot start.
     */
    suspend fun startQr() {
        val b = withContext(Dispatchers.IO) { ensureBridge() }
        if (paired) throw RelayException(WaErrors.ALREADY_PAIRED, "already linked; unlink first")
        pairingMode = PairingMode.QR
        _pairing.value = WaPairing.Idle
        setState(RelayState(RelayState.Phase.PAIRING))
        WaLog.add(appContext, "Linking started (QR code)")
        try {
            blockingCall(onCancel = { b.cancelPairing() }) { b.startQr() }
        } catch (e: Throwable) {
            pairingEnded(e)
            throw e
        }
    }

    /** Abandons the linking in progress: [pairing] goes back to Idle and [qr] to null. Nothing happens when none runs. */
    fun cancelPairing() {
        val was = pairingMode
        pairingMode = PairingMode.NONE
        _qr.value = null
        if (_pairing.value !is WaPairing.Paired) _pairing.value = WaPairing.Idle
        if (_state.value.phase == RelayState.Phase.PAIRING) setState(idleState())
        // Without a bridge nothing was ever started; and it is not loaded for this.
        val b = bridge ?: return
        if (was == PairingMode.NONE || paired) return
        scope.launch {
            try {
                b.cancelPairing()
            } catch (e: RelayException) {
                // Nothing to cancel.
            }
        }
        WaLog.add(appContext, "Linking cancelled")
    }

    private fun pairingEnded(e: Throwable) {
        val code = (e as? RelayException)?.code
            ?: if (e is CancellationException) RelayException.PAIRING_CANCELLED else e.javaClass.simpleName
        WaLog.add(appContext, "Linking did not start: $code")
        pairingMode = PairingMode.NONE
        _qr.value = null
        if (!paired && e !is CancellationException) _pairing.value = WaPairing.Failed((e as? RelayException)?.code ?: RelayException.INTERNAL)
        if (_state.value.phase == RelayState.Phase.PAIRING) setState(idleState())
    }

    /**
     * Unlinks this device: tells WhatsApp (best effort), disconnects and
     * wipes the bridge's store. With [deleteArchive] the local archive and
     * its media go too. Does not throw. Ends in UNPAIRED; with code
     * [RelayState.UNPAIR_REMOTE_FAILED] when WhatsApp could not be told, so
     * the user can remove the device in WhatsApp on the phone themselves.
     * [unpairing] is true meanwhile.
     *
     * Telling WhatsApp can need a short connection of its own. If the
     * link's service is running, it is stopped only afterwards, so its
     * notification, with Disconnect, stays up while that connection exists;
     * if it is not, the unlink happens while the user watches it in
     * progress on the screen that asked. It runs in the manager's own scope:
     * a screen that goes away meanwhile does not leave it half done.
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
        val wasLinked = paired || WaPrefs.link(appContext) == WaPrefs.LINK_LINKED
        // A store on disk may hold a link even if nothing here remembers one.
        val storeExists = storeDir(appContext).isDirectory
        // Nothing was ever linked or loaded: there is nothing to tell anyone, and the library stays unloaded.
        if (!wasLinked && bridge == null && !storeExists && !deleteArchive) {
            if (_state.value.phase != RelayState.Phase.UNPAIRED) setState(RelayState(RelayState.Phase.UNPAIRED))
            WaPrefs.setLink(appContext, WaPrefs.LINK_NONE)
            return
        }
        // No connect from here on: the service, if running, only stays for its notification.
        WaPrefs.setLiveEnabled(appContext, false)
        resumeOnVisible = false
        stopConnectionWork()
        pairingMode = PairingMode.NONE
        _qr.value = null
        _pairing.value = WaPairing.Idle
        var revokeFailed = false
        val b = try {
            if (bridge != null || wasLinked || storeExists) ensureBridge() else null
        } catch (e: RelayException) {
            // A store that cannot be opened cannot tell WhatsApp either. It goes all the same.
            revokeFailed = wasLinked
            null
        }
        connectLock.withLock {
            val bridgeLinked = b?.isPaired == true
            try {
                // Returns once WhatsApp was told; unpair_remote_failed once the link is
                // gone here but WhatsApp could not be told.
                b?.logout()
            } catch (e: RelayException) {
                if (e.code == RelayException.UNPAIR_REMOTE_FAILED) {
                    revokeFailed = true
                } else {
                    WaLog.add(appContext, "Unlinking with WhatsApp: ${e.code}")
                    // Not known whether WhatsApp heard of it, nor whether the store is gone.
                    if (bridgeLinked) revokeFailed = true
                }
            }
            bridgeWanted = false
            // The bridge could not wipe its store itself: it goes here.
            if ((b == null && storeExists) || b?.isPaired == true) discardStore(b)
        }
        // Only now, so that its notification was there for any connection the unlink needed.
        WaService.stop(appContext, this)
        // What the bridge sent so far lands first.
        awaitEventThread()
        paired = false
        WaPrefs.setLink(appContext, WaPrefs.LINK_NONE)
        // The bridge's store is gone: there is nothing left to compare the archive with.
        storeCheckNeeded = false
        scheduleBusyClear()
        try {
            if (deleteArchive) {
                onIntakeAndWait { wipeArchive() }
            } else {
                // Queued sends must not go out by themselves under the next link, which may be another account.
                onIntakeAndWait { outbox.holdAll(UNPAIRED_ERROR) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            WaLog.add(appContext, "Archive could not be cleared: ${e.javaClass.simpleName}")
        }
        val code = if (revokeFailed) RelayState.UNPAIR_REMOTE_FAILED else ""
        try {
            repo.updateHealth(status = "unpaired", account = "", phoneId = "", lastError = code)
        } catch (e: RuntimeException) {
            WaLog.add(appContext, "Link status could not be stored: ${e.javaClass.simpleName}")
        }
        clearTyping()
        endPresence()
        endHistorySync()
        MessageNotifier.cancelAll(appContext, ChatService.WHATSAPP)
        RelayNotifications.cancelWaRelinkNeeded(appContext)
        setState(RelayState(RelayState.Phase.UNPAIRED, code))
        WaLog.add(appContext, if (deleteArchive) "Unlinked; local archive deleted" else "Unlinked; local archive kept")
        if (revokeFailed) WaLog.add(appContext, "WhatsApp could not be told; the phone may still list this device")
    }

    /**
     * The bridge could not wipe its store itself (it cannot be opened, or
     * its logout failed before the wipe): the store's files go here, so that
     * no keys of the link stay behind. The bridge, if any, is closed first
     * and a new one is made on next use.
     */
    private fun discardStore(b: WaBridge?) {
        synchronized(bridgeLock) {
            try {
                b?.close()
            } catch (e: RelayException) {
                // Closed or not, its files go.
            }
            if (bridge === b) bridge = null
            if (!storeDir(appContext).deleteRecursively()) WaLog.add(appContext, "The link's store could not be deleted")
        }
    }

    /**
     * Turns on "stay connected" ([WaPrefs.liveEnabled]) and starts
     * [WaService], which connects. Without a link it does nothing (and
     * leaves "stay connected" off). With the link's notification turned off
     * it keeps the wish but does not start: the state says why
     * ([RelayState.NOTIFICATIONS_OFF]). Cheap and safe on the main thread,
     * like [stopLive].
     */
    fun startLive() {
        if (!paired) {
            WaPrefs.setLiveEnabled(appContext, false)
            return
        }
        WaPrefs.setLiveEnabled(appContext, true)
        synchronized(jobsLock) { reconnectAttempts = 0 }
        if (!RelayNotifications.statusVisible(appContext, ChatService.WHATSAPP)) {
            onNotificationsHidden()
            return
        }
        WaService.start(appContext, this)
    }

    /** Disconnects and stops the service, keeping the link. */
    fun stopLive() {
        val wanted = WaPrefs.liveEnabled(appContext)
        WaPrefs.setLiveEnabled(appContext, false)
        resumeOnVisible = false
        if (!paired) return
        stopConnectionWork()
        WaService.stop(appContext, this)
        disconnectUnlessWanted()
        // A link that was not up gets no "disconnected" from the bridge.
        val current = _state.value
        if (current.phase == RelayState.Phase.ERROR || current.code == RelayState.NOTIFICATIONS_OFF) {
            setState(RelayState(RelayState.Phase.DISCONNECTED))
        }
        if (wanted || current.phase in LIVE_PHASES) WaLog.add(appContext, "Disconnected by the user")
    }

    /**
     * Disconnects the bridge once no connect is in progress, unless by then
     * the link is wanted again and may run (a later [startLive]): whichever
     * of the two the user did last wins.
     */
    private fun disconnectUnlessWanted() {
        val b = bridge ?: return
        scope.launch {
            connectLock.withLock {
                if (linkAllowed()) return@withLock
                // Only a bridge that is (being) connected: one that is not would only report "disconnected" over whatever the state says now.
                if (!bridgeWanted && _state.value.phase !in LIVE_PHASES) return@withLock
                try {
                    b.disconnect()
                    bridgeWanted = false
                } catch (e: RelayException) {
                    WaLog.add(appContext, "Disconnect: ${e.code}")
                }
            }
        }
    }

    // ============================== ChatBackend ==============================

    /**
     * Queues a text message and returns its tmpId at once. It shows in the
     * archive as "tmp:<tmpId>" right away and is sent when the link is
     * connected, surviving restarts. Throws [RelayException.NOT_PAIRED]
     * without a link: nothing is queued for an account that is not there.
     */
    override fun sendText(conversationId: String, text: String, replyToId: String?): String {
        requireLinked()
        val tmpId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        stopTyping(conversationId, tell = false)
        onIntake("queue a message") { outbox.enqueueText(tmpId, conversationId, text, replyToId, now) }
        return tmpId
    }

    /** Like [sendText] for an attachment (at most 100 MB), copied into app storage first. */
    override fun sendMedia(conversationId: String, uri: Uri, caption: String?, replyToId: String?): String {
        requireLinked()
        val tmpId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        stopTyping(conversationId, tell = false)
        onIntake("queue an attachment") { outbox.enqueueMedia(tmpId, conversationId, uri, caption, replyToId, now) }
        return tmpId
    }

    override fun retry(tmpId: String) {
        onIntake("retry a message") { outbox.retry(tmpId) }
    }

    override fun discardFailed(tmpId: String) {
        onIntake("discard a message") { outbox.discard(tmpId) }
    }

    /**
     * Tells the chat the user is typing: at most once every
     * [Timing.typingThrottleMs] per conversation, and "stopped" a while
     * after the last call (or at once when a message is sent). Best effort.
     */
    override fun typing(conversationId: String) {
        if (!isOnline()) return
        val now = System.nanoTime() / 1_000_000
        val last = typingSentAt[conversationId]
        if (last == null || now - last >= timing.typingThrottleMs) {
            typingSentAt[conversationId] = now
            scope.launch(signals) {
                try {
                    bridge?.setTyping(conversationId, true)
                } catch (e: RelayException) {
                    // Typing is best effort.
                }
            }
        }
        synchronized(typingPauses) {
            typingPauses.remove(conversationId)?.cancel()
            typingPauses[conversationId] = scope.launch {
                delay(timing.typingPauseMs)
                stopTyping(conversationId, tell = true)
            }
        }
    }

    /** This account no longer types in [conversationId]; with [tell] the chat is told (a sent message says so by itself). */
    private fun stopTyping(conversationId: String, tell: Boolean) {
        synchronized(typingPauses) { typingPauses.remove(conversationId)?.cancel() }
        typingSentAt.remove(conversationId) ?: return
        if (!tell || !isOnline()) return
        scope.launch(signals) {
            try {
                bridge?.setTyping(conversationId, false)
            } catch (e: RelayException) {
                // Best effort.
            }
        }
    }

    /**
     * Subscribes to the presence of the other person in a one-to-one chat; it
     * then shows up in [presence] under their participant id while a
     * conversation screen is in front ([setAppVisible]). Nothing happens for
     * a group, or without a link.
     */
    override fun watchPresence(conversationId: String) {
        if (!paired) return
        scope.launch {
            val target = try {
                presenceTarget(conversationId)
            } catch (e: RuntimeException) {
                null
            } ?: return@launch
            if (watched.add(target)) kickPresence()
        }
    }

    /** The other person of a one-to-one chat, or null (a group, or not known). */
    private fun presenceTarget(conversationId: String): String? {
        val conversation = repo.conversation(conversationId)
        if (conversation?.isGroup == true) return null
        val others = repo.participants(conversationId).filter { !it.isMe }
        return when {
            others.size == 1 -> others[0].id
            others.isNotEmpty() -> null
            // Not described yet: a one-to-one chat's id is the other person's.
            conversationId.endsWith(USER_SERVER) -> conversationId
            else -> null
        }
    }

    /**
     * Shows this account as online while a WhatsApp conversation screen is
     * in front, and as away otherwise. Calls are counted (a screen says true
     * when it comes to the front and false when it leaves, and two screens
     * overlap briefly); when the whole app leaves the front the count starts
     * over ([onAppBackground]), so a screen that forgot to say false cannot
     * keep the account online.
     */
    override fun setAppVisible(visible: Boolean) {
        synchronized(presenceLock) {
            visibleScreens = (visibleScreens + if (visible) 1 else -1).coerceAtLeast(0)
            if (visibleScreens == 0) watched.clear()
        }
        if (!visible && !screenVisible()) clearPresence()
        if (bridge != null) kickPresence()
    }

    private fun screenVisible(): Boolean = synchronized(presenceLock) { visibleScreens > 0 }

    private fun kickPresence() {
        presenceWorker // started on first use
        presenceKicks.trySend(Unit)
    }

    /**
     * Brings what WhatsApp was told on this connection in line with what is
     * wanted now: online and subscribed to the watched people while a screen
     * is in front, away otherwise. Runs on one coroutine.
     */
    private suspend fun syncPresence() {
        val b = bridge ?: return
        if (!isOnline()) return
        if (!screenVisible()) {
            subscribed.clear()
            if (announcedOnline) {
                announcedOnline = false
                try {
                    b.setOnline(false)
                } catch (e: RelayException) {
                    // Gone with the connection.
                }
            }
            return
        }
        if (!announcedOnline) {
            try {
                b.setOnline(true)
                announcedOnline = true
                onlineTries = 0
            } catch (e: RelayException) {
                // WhatsApp has not finished syncing after linking: a little later.
                if (e.code == WaErrors.NOT_READY && onlineTries++ < MAX_ONLINE_TRIES) {
                    scope.launch {
                        delay(timing.presenceRetryMs)
                        kickPresence()
                    }
                }
                return
            }
        }
        for (target in watched.toList()) {
            if (!subscribed.add(target)) continue
            try {
                b.subscribePresence(target)
            } catch (e: RelayException) {
                subscribed.remove(target)
                if (e.code == RelayException.NOT_CONNECTED) return
            }
        }
    }

    /** A new connection knows nothing of what the last one was told. */
    private fun resetPresence() {
        scope.launch(signals) {
            announcedOnline = false
            subscribed.clear()
            onlineTries = 0
        }
    }

    private fun clearPresence() {
        _presence.value = emptyMap()
    }

    /** The connection is gone: what was known of anyone's presence is stale. */
    private fun endPresence() {
        resetPresence()
        clearPresence()
    }

    /** Adds, switches or (with [remove]) removes the user's reaction on a message. */
    override suspend fun react(messageId: String, emoji: String, remove: Boolean): Unit = withContext(Dispatchers.IO) {
        val b = requireLinkedBridge()
        val row = repo.message(messageId) ?: throw RelayException(RelayException.NOT_FOUND, "message not found")
        if (row.isLocal) throw RelayException(RelayException.INVALID_ARGUMENT, "the message is still being sent")
        b.sendReaction(row.conversationId, messageId, if (row.isOutgoing) "" else row.senderId, if (remove) "" else emoji)
    }

    /**
     * Marks a conversation read: locally at once, and for WhatsApp (read
     * receipts for the messages that came since) when connected. Also clears
     * its notification. Does not throw.
     */
    override suspend fun markRead(conversationId: String): Unit = withContext(Dispatchers.IO) {
        val wasUnread = try {
            val unread = repo.conversation(conversationId)?.unread == true
            repo.markConversationRead(conversationId)
            unread
        } catch (e: RuntimeException) {
            false
        }
        MessageNotifier.cancel(appContext, conversationId, ChatService.WHATSAPP)
        if (!paired || !isOnline()) return@withContext
        try {
            tellRead(conversationId, wasUnread)
        } catch (e: RelayException) {
            if (e.code != RelayException.NOT_CONNECTED) WaLog.add(appContext, "Mark read failed: ${e.code}")
        } catch (e: RuntimeException) {
            WaLog.add(appContext, "Mark read failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Sends WhatsApp a read receipt for each incoming message not told about
     * yet: WhatsApp's receipts name messages one by one, and one for the
     * newest alone would leave the ones before it "delivered" for their
     * sender. On the first call for a conversation in this process those are
     * the messages since this account's last own message if the
     * conversation was unread, else only the newest.
     */
    private fun tellRead(conversationId: String, wasUnread: Boolean) {
        val rows = repo.messages(conversationId, limit = READ_LOOKBACK).filter { !it.isLocal }
        val latest = rows.lastOrNull { it.direction == "in" } ?: return
        val from = readUpTo[conversationId]
            ?: if (wasUnread) rows.lastOrNull { it.isOutgoing }?.timestampMs ?: 0L else latest.timestampMs - 1
        val unread = rows.filter { it.direction == "in" && it.timestampMs > from }
        if (unread.isEmpty()) return
        val b = requireBridge()
        for (row in unread) {
            b.markRead(conversationId, row.id, row.senderId)
            readUpTo[conversationId] = row.timestampMs
        }
    }

    /**
     * Brings older messages of a conversation into the archive and returns
     * how many came; 0 when the phone has none (or did not answer).
     *
     * The bridge keeps what the phone has sent so far, and the archive
     * normally holds all of it already. When that runs out the bridge asks
     * the phone for the messages before the oldest one (at most once a
     * minute per chat); they arrive as events, and this waits for them
     * ([Timing.loadOlderWaitMs]; longer when the bridge has to let its
     * minute pass first).
     */
    override suspend fun loadOlder(conversationId: String, count: Int): Int = withContext(Dispatchers.IO) {
        val b = requireLinkedBridge()
        val arrivals = AtomicInteger(0)
        olderArrivals[conversationId] = arrivals
        try {
            var stored = fetchOlder(b, conversationId, count) ?: return@withContext 0
            if (stored.added > 0 || !stored.askedPhone) return@withContext stored.added
            if (stored.throttledMs > 0) {
                // The bridge asked the phone for this chat less than a minute ago and will not ask again yet.
                delay(stored.throttledMs)
                stored = fetchOlder(b, conversationId, count) ?: return@withContext 0
                if (stored.added > 0 || !stored.askedPhone) return@withContext stored.added + arrivals.get()
            }
            val deadline = System.nanoTime() / 1_000_000 + timing.loadOlderWaitMs
            while (arrivals.get() == 0 && System.nanoTime() / 1_000_000 < deadline) delay(WAIT_STEP_MS)
            if (arrivals.get() == 0) {
                // Nothing older on the phone (or it is not answering): not asked again for this message.
                stored.oldestId?.let { historyEnd[conversationId] = it }
                return@withContext 0
            }
            // The phone answers with a batch: let it land.
            var seen: Int
            do {
                seen = arrivals.get()
                delay(timing.loadOlderSettleMs)
            } while (arrivals.get() != seen)
            seen
        } finally {
            olderArrivals.remove(conversationId, arrivals)
        }
    }

    /**
     * @param added       messages stored from the bridge's own store
     * @param askedPhone  the bridge's store ran out, so the phone is (to be) asked
     * @param throttledMs how long until the bridge asks the phone for this chat again (0: it has asked now)
     */
    private class Older(val added: Int, val askedPhone: Boolean, val throttledMs: Long, val oldestId: String?)

    /** One page from the bridge's store, from below the archive's oldest message; null if the archive was deleted meanwhile. */
    private fun fetchOlder(b: WaBridge, conversationId: String, count: Int): Older? {
        val since = gate.epoch()
        val oldest = repo.oldestStored(conversationId)
        val cursor = oldest?.let { Cursor(it.first, it.second) }
        val page = b.fetchMessages(conversationId, count, cursor)
        val fresh = page.messages.filter { !it.isDeleted && it.id.isNotEmpty() }.map { it.copy(isOld = true) }
        val added = gate.ifCurrent(since) {
            val known = repo.knownMessageIds(fresh.map { it.id })
            val missing = fresh.filter { it.id !in known }
            if (missing.isNotEmpty()) repo.upsertMessages(missing)
            missing.size
        } ?: return null
        // The bridge asks the phone when its page comes up short, while connected, and only with a message to start from.
        val short = page.messages.size < count
        val anchor = page.messages.firstOrNull()?.id ?: oldest?.first
        if (!short || anchor == null || !isOnline() || historyEnd[conversationId] == anchor) {
            return Older(added, askedPhone = false, throttledMs = 0, oldestId = anchor)
        }
        val now = SystemClock.elapsedRealtime()
        val last = historyAskedAt[conversationId]
        val wait = if (last == null) 0L else (last + timing.historyRequestIntervalMs - now).coerceAtLeast(0L)
        if (wait == 0L) historyAskedAt[conversationId] = now
        return Older(added, askedPhone = true, throttledMs = wait, oldestId = anchor)
    }

    /** "Delete for me": the same as [deleteMessage] with forEveryone false. */
    override suspend fun deleteMessage(messageId: String) = deleteMessage(messageId, forEveryone = false)

    /**
     * Deletes a message: with [forEveryone] for everyone in the chat (one's
     * own messages, or any as a group admin), otherwise only on this
     * account's devices. Either way it goes from the archive. What
     * [Capability.DELETE_FOR_EVERYONE] offers.
     *
     * A message still being sent is just dropped. Without a link "for me"
     * deletes it here only; "for everyone" then throws
     * [RelayException.NOT_PAIRED].
     */
    suspend fun deleteMessage(messageId: String, forEveryone: Boolean) {
        if (messageId.startsWith(MessageRow.TMP_PREFIX)) {
            val tmpId = messageId.removePrefix(MessageRow.TMP_PREFIX)
            onIntakeAndWait { outbox.discard(tmpId) }
            return
        }
        if (!paired) {
            if (forEveryone) notLinked()
            deleteLocally { repo.applyDeletion(messageId) }
            return
        }
        withContext(Dispatchers.IO) {
            val b = requireLinkedBridge()
            val since = gate.epoch()
            val row = repo.message(messageId) ?: throw RelayException(RelayException.NOT_FOUND, "message not found")
            try {
                b.deleteMessage(row.conversationId, messageId, forEveryone)
            } catch (e: RelayException) {
                // The bridge no longer has a message kept from an earlier link: there is
                // nothing to tell the account's other devices, and it goes here.
                if (forEveryone || e.code != RelayException.NOT_FOUND) throw e
            }
            gate.ifCurrent(since) { repo.applyDeletion(messageId) }
        }
    }

    /**
     * Changes the text of one of the user's own sent messages, for everyone
     * ([Capability.EDIT]). WhatsApp takes it only for a while after sending,
     * and only for a message that is text alone; the new text arrives in the
     * archive as the bridge's message event.
     */
    suspend fun editMessage(messageId: String, text: String) {
        if (!paired) notLinked()
        withContext(Dispatchers.IO) {
            val b = requireLinkedBridge()
            val row = repo.message(messageId) ?: throw RelayException(RelayException.NOT_FOUND, "message not found")
            b.editText(row.conversationId, messageId, text.trim())
        }
    }

    /**
     * Deletes a conversation here, with its messages, media and queued
     * sends. The bridge cannot delete a chat on the phone, so WhatsApp on
     * the phone keeps it; here it stays deleted until a new message arrives
     * in it.
     */
    override suspend fun deleteConversation(conversationId: String) {
        com.lightdecoder.chat.ShareShortcuts.remove(appContext, ChatService.WHATSAPP, conversationId)
        deleteLocally {
            if (paired) forgetChat(conversationId)
            repo.deleteConversationLocal(conversationId)
        }
        readUpTo.remove(conversationId)
        MessageNotifier.cancel(appContext, conversationId, ChatService.WHATSAPP)
    }

    override suspend fun setArchived(conversationId: String, archived: Boolean): Unit = withContext(Dispatchers.IO) {
        val b = requireLinkedBridge()
        val since = gate.epoch()
        b.setConversationArchived(conversationId, archived)
        gate.ifCurrent(since) { repo.setConversationFolder(conversationId, if (archived) Folder.ARCHIVE else Folder.INBOX) }
    }

    override suspend fun setMuted(conversationId: String, muted: Boolean): Unit = withContext(Dispatchers.IO) {
        val b = requireLinkedBridge()
        val since = gate.epoch()
        b.setConversationMuted(conversationId, muted)
        gate.ifCurrent(since) { repo.setConversationMuted(conversationId, muted) }
    }

    /**
     * Re-lists the inbox or the archive from the bridge's own chat index
     * into [repo]; returns how many conversations came back. WhatsApp has no
     * other folders: 0 at once for them.
     */
    override suspend fun refreshConversations(folder: Folder): Int = withContext(Dispatchers.IO) {
        if (folder != Folder.INBOX && folder != Folder.ARCHIVE) return@withContext 0
        val b = requireLinkedBridge()
        val since = gate.epoch()
        val list = b.listConversations(folder, CONVERSATION_COUNT)
        gate.ifCurrent(since) { storeConversations(list) }
        list.size
    }

    /**
     * The file for one media part, downloading it into the archive's media
     * directory first if needed. Null if it cannot be had (the download
     * failed, it is larger than [WaMedia.MAX_DOWNLOAD_BYTES], or only the
     * inline preview exists). WhatsApp has no separate thumbnail to
     * download: with [thumbnail] only a file already here is returned.
     *
     * Throws [RelayException] with code [RelayException.NOT_CONNECTED] when
     * the file has to be downloaded and the link is down: the caller can say
     * "connect to load" instead of "unavailable". Nothing else is thrown
     * (except cancellation).
     */
    override suspend fun mediaFile(messageId: String, partId: String, thumbnail: Boolean): File? {
        val key = "$messageId\u0000$partId\u0000$thumbnail"
        val load = mediaLoads.computeIfAbsent(key) {
            scope.async(mediaDispatcher, start = CoroutineStart.LAZY) {
                try {
                    loadMedia(messageId, partId, thumbnail)
                } catch (e: RelayException) {
                    if (e.code == RelayException.NOT_CONNECTED) throw e
                    WaLog.add(appContext, "Media could not be loaded: ${e.code}")
                    null
                } catch (t: Throwable) {
                    // Includes OutOfMemoryError on a large file.
                    WaLog.add(appContext, "Media could not be loaded: ${t.javaClass.simpleName}")
                    null
                }
            }.also { d -> d.invokeOnCompletion { mediaLoads.remove(key, d) } }
        }
        return load.await()
    }

    /** WhatsApp has no full-size requests (see [capabilities]): always throws. */
    override suspend fun requestFullSize(messageId: String, partId: String): Unit =
        throw RelayException(WaErrors.UNSUPPORTED, "WhatsApp has no full-size requests")

    /**
     * The contacts stored in [repo]; with [refresh], listed from the bridge
     * first (the phone's address book as WhatsApp synced it, and the names
     * people gave themselves). A failed refresh falls back to the stored
     * list, and only throws if there is none.
     */
    override suspend fun contacts(refresh: Boolean): ContactsResult = withContext(Dispatchers.IO) {
        var fromPhone = true
        if (refresh) {
            try {
                val b = requireLinkedBridge()
                val since = gate.epoch()
                val contacts = b.listContacts()
                gate.ifCurrent(since) { repo.upsertContacts(contacts) }
            } catch (e: RelayException) {
                if (repo.contacts().isEmpty()) throw e
                fromPhone = false
            }
        }
        ContactsResult(repo.contacts(), fromPhone)
    }

    /**
     * Opens the one-to-one conversation with a phone number (after WhatsApp
     * confirmed the number is on WhatsApp) and returns its id. More than
     * one number, or a [groupName], asks for a new group, which the bridge
     * does not create: [WaErrors.UNSUPPORTED].
     */
    override suspend fun startConversation(numbers: List<String>, groupName: String?): String = withContext(Dispatchers.IO) {
        val b = requireLinkedBridge()
        val since = gate.epoch()
        val conversation = b.getOrCreateConversation(numbers, groupName?.trim()?.ifEmpty { null })
        gate.ifCurrent(since) {
            // The user asked for this chat: one deleted here earlier is back.
            recallChat(conversation.id)
            repo.upsertConversation(conversation)
        }
        conversation.id
    }

    /** WhatsApp has no spam folder (see [capabilities]): always throws. */
    override suspend fun markNotSpam(conversationId: String): Unit =
        throw RelayException(WaErrors.UNSUPPORTED, "WhatsApp has no spam folder")

    /**
     * WhatsApp keeps no local deleted folder (see [capabilities]): a chat
     * deleted on the phone is deleted here too, since no screen would show
     * what was kept. So there is never anything to restore: always false.
     */
    override suspend fun restoreDeletedLocally(conversationId: String): Boolean = false

    /** As [restoreDeletedLocally]: nothing is ever kept in a deleted folder, so always 0. */
    override suspend fun purgeDeleted(conversationId: String?): Int = 0

    /**
     * Deletes the local WhatsApp archive (messages, media, queued sends)
     * while nothing is linked. Refuses, deleting nothing, while a link
     * exists: then [unpair] with deleteArchive is the way. Returns whether
     * the archive was deleted. Does not throw.
     */
    override suspend fun deleteLocalArchive(): Boolean {
        if (paired) return false
        // In the manager's scope: a screen that goes away meanwhile does not leave it half done.
        return scope.async {
            val deleted = try {
                // On the intake thread: after sends already handed in, before later ones.
                onIntakeAndWait {
                    // Checked again here: a link may have been made meanwhile.
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
                WaLog.add(appContext, "Local archive could not be deleted: ${e.javaClass.simpleName}")
                false
            }
            if (deleted) {
                MessageNotifier.cancelAll(appContext, ChatService.WHATSAPP)
                WaLog.add(appContext, "Local archive deleted")
            }
            deleted
        }.await()
    }

    /** WhatsApp has no "use here" (see [capabilities]): always throws. */
    override suspend fun useHere(): Unit = throw RelayException(WaErrors.UNSUPPORTED, "WhatsApp has no use here")

    /**
     * A deletion in the archive alone: on the intake thread, so it is
     * ordered with queued sends, and through the archive gate, so it never
     * interleaves with a wipe.
     */
    private suspend fun deleteLocally(block: () -> Unit) {
        onIntakeAndWait { gate.ifCurrent(gate.epoch(), block) }
    }

    // ============================== service hooks ==============================

    /** The foreground service is running: connect if the user wants the link live. */
    internal fun onServiceStarted() {
        watchNotification()
        connectIfWanted()
    }

    /** Android 8 does not say when notifications are turned off: look every [Timing.notificationCheckMs] while the service runs. */
    private fun watchNotification() {
        if (!timing.pollNotifications) return
        synchronized(jobsLock) {
            if (notificationWatch?.isActive == true) return
            notificationWatch = scope.launch {
                while (true) {
                    delay(timing.notificationCheckMs)
                    if (!serviceRunning) break
                    if (wantConnected() && !RelayNotifications.statusVisible(appContext, ChatService.WHATSAPP)) {
                        WaLog.add(appContext, "The link's notification was turned off")
                        onNotificationsHidden()
                        break
                    }
                }
            }
        }
    }

    /**
     * One of the app's screens came to the front (on the main thread). As
     * for the Google Messages link: a start Android refused from the
     * background, or a link stopped because its notification was turned
     * off, is repaired now; a link that is simply not running starts only
     * if "start automatically" is on. With the service running, a link
     * whose notification was turned off goes down, and an errored one that
     * is being retried tries now. Nothing is touched (no channel made, no
     * library loaded) unless WhatsApp is linked and wanted connected.
     */
    internal fun onAppVisible() {
        if (serviceRunning) {
            if (!RelayNotifications.statusVisible(appContext, ChatService.WHATSAPP)) {
                onNotificationsHidden()
            } else if (retrying()) {
                retryNow()
            }
            return
        }
        if (!wantConnected() || serviceStartsPending.get() > 0) return
        if (!resumeOnVisible && !WaPrefs.autoStart(appContext)) return
        if (!RelayNotifications.statusVisible(appContext, ChatService.WHATSAPP)) {
            onNotificationsHidden()
            return
        }
        resumeOnVisible = false
        WaService.start(appContext, this)
    }

    /** None of the app's screens is in front any more: this account is not shown as online, whatever the screens last said. */
    internal fun onAppBackground() {
        val was = synchronized(presenceLock) {
            val was = visibleScreens > 0
            visibleScreens = 0
            watched.clear()
            was
        }
        if (!was) return
        clearPresence()
        if (bridge != null) kickPresence()
    }

    /**
     * A network came up. An errored link that is being retried tries now
     * instead of waiting out its pause; one whose connection dropped is
     * connected again at once (the bridge's own retries slow down the longer
     * the network is away). Never piles up: while a connect is running
     * nothing more is asked for.
     */
    internal fun onNetworkAvailable() {
        when {
            retrying() -> retryNow(queueIfBusy = false)
            _state.value.phase == RelayState.Phase.RECONNECTING -> connectIfWanted(queueIfBusy = false)
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

    private fun retrying(): Boolean = synchronized(jobsLock) { reconnectJob?.isActive == true }

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
        WaLog.add(appContext, "Android's time limit for the service was reached")
        stopLive()
    }

    /**
     * The service is gone. Whatever stopped it, the link does not stay
     * connected without its notification, unless a new start is already on
     * its way.
     */
    internal fun onServiceDestroyed() {
        disconnectUnlessWanted()
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
        WaService.stop(appContext, this)
        disconnectUnlessWanted()
        val current = _state.value
        if (current.phase in NEEDS_USER_PHASES || current.code == RelayState.NOTIFICATIONS_OFF) return
        WaLog.add(appContext, "Link not started: its notification is turned off")
        setState(RelayState(RelayState.Phase.DISCONNECTED, RelayState.NOTIFICATIONS_OFF))
    }

    /** The user turned the app's notifications, or one of its channels, off or on (Android 9+). */
    internal fun onNotificationSettingsChanged() {
        if (!wantConnected()) return
        if (!RelayNotifications.statusVisible(appContext, ChatService.WHATSAPP)) {
            onNotificationsHidden()
        } else if (!serviceRunning && serviceStartsPending.get() == 0 && resumeOnVisible) {
            // Back on: try now; if Android refuses from the background, the next screen does it.
            resumeOnVisible = false
            WaService.start(appContext, this)
        }
    }

    // ============================== bridge access ==============================

    /** The bridge, created on first use. Blocks (loads Go, opens the store); call off the main thread. */
    private fun ensureBridge(): WaBridge {
        bridge?.let { return it }
        synchronized(bridgeLock) {
            bridge?.let { return it }
            val factory = WaBridges.factory ?: { dir, sink -> GoWaBridge(dir, sink) }
            val created = try {
                factory(storeDir(appContext), sink)
            } catch (e: RelayException) {
                WaLog.add(appContext, "The link's store could not be opened: ${e.code}")
                throw e
            } catch (e: LinkageError) {
                // The Go library is missing for this device's CPU.
                WaLog.add(appContext, "The link's library could not be loaded")
                throw RelayException(RelayException.INTERNAL, LIBRARY_UNAVAILABLE)
            }
            bridge = created
            settleLink(created.isPaired)
            return created
        }
    }

    /** The bridge says whether a link exists; what was remembered ([WaPrefs.link]) follows it. */
    private fun settleLink(linked: Boolean) {
        if (linked == paired) {
            if (linked) WaPrefs.setLink(appContext, WaPrefs.LINK_LINKED)
            return
        }
        paired = linked
        if (linked) {
            // A link made just before the app died, say.
            WaPrefs.setLink(appContext, WaPrefs.LINK_LINKED)
            if (_state.value.phase in UNLINKED_PHASES) setState(RelayState(RelayState.Phase.DISCONNECTED))
        } else {
            // The store has no link after all (it was ended while the app was not running).
            val loggedOut = runCatching { repo.health().status == "logged_out" }.getOrDefault(false)
            WaPrefs.setLink(appContext, if (loggedOut) WaPrefs.LINK_LOGGED_OUT else WaPrefs.LINK_NONE)
            if (_state.value.phase !in UNLINKED_PHASES) setState(idleState())
            // A service started on what was remembered (at boot, say) has nothing to connect.
            if (serviceRunning || WaPrefs.liveEnabled(appContext)) giveUpLive()
        }
    }

    private fun requireBridge(): WaBridge = ensureBridge()

    /** Without a link nothing is asked of the bridge, and it is not loaded for the asking. */
    private fun requireLinked() {
        if (!paired) notLinked()
    }

    private fun requireLinkedBridge(): WaBridge {
        requireLinked()
        return ensureBridge()
    }

    private fun notLinked(): Nothing = throw RelayException(RelayException.NOT_PAIRED, "WhatsApp is not linked")

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
                    Result.failure(WaErrors.fromThrowable(e))
                } catch (t: Throwable) {
                    Result.failure(t)
                }
                result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
            }
            cont.invokeOnCancellation { scope.launch { runCatching(onCancel) } }
        }

    private fun idleState(): RelayState = RelayState(
        when {
            paired -> RelayState.Phase.DISCONNECTED
            WaPrefs.link(appContext) == WaPrefs.LINK_LOGGED_OUT -> RelayState.Phase.LOGGED_OUT
            else -> RelayState.Phase.UNPAIRED
        }
    )

    private fun isOnline(): Boolean = _state.value.phase == RelayState.Phase.CONNECTED

    // ============================== the archive's owner ==============================

    /**
     * Makes sure the archive is the linked account's before anything of
     * that account is stored in it or sent from it. [ownId] is the account
     * (its number, as the bridge reports it; empty if not known).
     *
     * A WhatsApp chat's id is the other person's, whoever the account is,
     * so an archive kept from another account would show that account's
     * messages in this one's chats, and send what it had queued from this
     * one. So:
     *  - linked to a different account than the archive came from: the old
     *    archive (with its queued sends and media) is deleted;
     *  - the same account again: queued sends go out as planned;
     *  - not known which: queued sends wait as failed for the user.
     *
     * Returns false while the archive is another account's and could not be
     * deleted: nothing is stored or sent until a later try deletes it.
     */
    private fun reconcileOwner(ownId: String): Boolean {
        val owner = WaPrefs.archiveOwner(appContext).ifEmpty { runCatching { repo.health().account }.getOrNull().orEmpty() }
        when {
            ownId.isEmpty() -> return !archiveForeign
            owner.isNotEmpty() && owner != ownId -> {
                WaLog.add(appContext, "The archive is from a different WhatsApp account than the link")
                // Until the deletion has run.
                archiveForeign = true
                try {
                    wipeArchive()
                    WaLog.add(appContext, "Linked to a different account; the previous account's archive was deleted")
                } catch (e: RuntimeException) {
                    WaLog.add(appContext, "Previous archive could not be deleted: ${e.javaClass.simpleName}")
                    holdQueuedSends()
                    // What arrives meanwhile is dropped; it is fetched from the bridge's store once the archive is this account's.
                    storeCheckNeeded = true
                    return false
                }
                clearTyping()
                MessageNotifier.cancelAll(appContext, ChatService.WHATSAPP)
            }
            owner.isEmpty() -> holdQueuedSends()
        }
        archiveForeign = false
        try {
            WaPrefs.setArchiveOwner(appContext, ownId)
            repo.updateHealth(account = ownId)
        } catch (e: RuntimeException) {
            WaLog.add(appContext, "The archive's account could not be recorded: ${e.javaClass.simpleName}")
        }
        return true
    }

    /** Nothing queued under another (or an unknown) link goes out by itself; nothing is sent until the hold has run. */
    private fun holdQueuedSends() {
        holdsPending.incrementAndGet()
        val hold = try {
            intake.submit {
                try {
                    outbox.holdAll(UNPAIRED_ERROR)
                } finally {
                    holdsPending.decrementAndGet()
                }
            }
        } catch (e: RejectedExecutionException) {
            holdsPending.decrementAndGet() // shut down
            return
        }
        try {
            hold.get(timing.intakeWaitMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            WaLog.add(appContext, "Could not hold queued sends: ${e.javaClass.simpleName}")
        }
    }

    /** Deletes the archive (messages, media, queued sends) between two writes of what the bridge sent, and what was kept about it. */
    private fun wipeArchive() {
        gate.wipe { repo.clearAll() }
        runCatching { com.lightdecoder.reactions.ReadTimes.get(appContext).clear(ChatService.WHATSAPP) }
        com.lightdecoder.chat.ShareShortcuts.removeAll(appContext, ChatService.WHATSAPP)
        WaPrefs.setArchiveOwner(appContext, "")
        synchronized(deletedChats) {
            deletedChats.clear()
            WaPrefs.setDeletedChats(appContext, emptyMap())
        }
        synchronized(earlyReceipts) { earlyReceipts.clear() }
        readUpTo.clear()
        historyEnd.clear()
        historyAskedAt.clear()
        archiveForeign = false
    }

    // ============================== connecting ==============================

    private fun wantConnected() = paired && WaPrefs.liveEnabled(appContext)

    /**
     * Whether the link may be connected right now: the user wants it, its
     * service is running (or a start is on its way) and the service's
     * notification can be seen.
     */
    private fun linkAllowed(): Boolean =
        wantConnected() && (serviceRunning || serviceStartsPending.get() > 0) &&
            RelayNotifications.statusVisible(appContext, ChatService.WHATSAPP)

    /**
     * Connects if the link is wanted and may run. While a connect is
     * running, another one follows it, unless [queueIfBusy] is false.
     */
    private fun connectIfWanted(queueIfBusy: Boolean = true) {
        // Only with the service there: its notification (with Disconnect) must
        // show while connected.
        if (!paired && serviceRunning) {
            giveUpLive()
            return
        }
        if (!wantConnected() || !serviceRunning) return
        if (!RelayNotifications.statusVisible(appContext, ChatService.WHATSAPP)) {
            onNotificationsHidden()
            return
        }
        synchronized(jobsLock) {
            if (connectJob?.isActive == true) {
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
            WaLog.add(appContext, "Could not load the link: ${e.code}")
            // A missing library is not fixed by trying again; a store that could not be opened may be.
            if (e.message == LIBRARY_UNAVAILABLE) giveUpLive() else scheduleReconnect()
            return@withLock
        }
        if (!b.isPaired) {
            // settleLink has taken "stay connected" back.
            if (serviceRunning) giveUpLive()
            return@withLock
        }
        if (!RelayNotifications.statusVisible(appContext, ChatService.WHATSAPP)) {
            onNotificationsHidden()
            return@withLock
        }
        // Before anything of this connection is stored or any queued send goes out.
        val ownId = try {
            b.status().ownId
        } catch (e: RelayException) {
            ""
        }
        if (!reconcileOwner(ownId)) {
            WaLog.add(appContext, "Not connecting until the previous account's archive is deleted")
            scheduleReconnect()
            return@withLock
        }
        // Only before connecting: nothing new arrives meanwhile (a message stored here first would
        // not be notified when its event came), and the bridge is not led to ask the phone for anything.
        if (storeCheckNeeded && !bridgeWanted && _state.value.phase !in LIVE_PHASES) reconcileStore(b)
        // That can take a while: the user may have tapped Disconnect meanwhile.
        if (!linkAllowed()) return@withLock
        try {
            bridgeWanted = true
            b.connect()
        } catch (e: RelayException) {
            WaLog.add(appContext, "Could not connect: ${e.code}")
            // A link that is gone arrives as its own state; anything else is retried.
            if (e.code !in NEEDS_USER) scheduleReconnect()
            return@withLock
        }
        // stopLive(), the service stopping, or notifications being turned off
        // may have happened while connect() was blocking.
        if (!linkAllowed()) {
            b.disconnect()
            bridgeWanted = false
        }
    }

    /** The link cannot run (no link, no library, or WhatsApp said not to come back): "stay connected" off, service stopped. */
    private fun giveUpLive() {
        WaPrefs.setLiveEnabled(appContext, false)
        resumeOnVisible = false
        stopConnectionWork()
        WaService.stop(appContext, this)
    }

    /**
     * Retries after a pause that grows per attempt, backed by a wake-up
     * alarm so a phone asleep still retries on time ([onReconnectDue]).
     * Only for what the bridge does not retry itself: a connection that
     * merely dropped is the bridge's to bring back.
     */
    private fun scheduleReconnect() {
        synchronized(jobsLock) {
            if (!wantConnected() || reconnectJob?.isActive == true) return
            val delays = timing.reconnectDelaysMs
            val wait = delays[reconnectAttempts.coerceAtMost(delays.lastIndex)]
            reconnectAttempts++
            WaLog.add(appContext, "Reconnecting in ${wait / 1000} s")
            reconnectJob = scope.launch {
                delay(wait)
                cancelReconnectAlarm()
                connectIfWanted()
            }
            setReconnectAlarm(wait)
        }
    }

    private fun reconnectAlarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext, RECONNECT_REQUEST,
        Intent(appContext, ReconnectReceiver::class.java).setAction(ReconnectReceiver.ACTION_WA_RECONNECT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun setReconnectAlarm(waitMs: Long) {
        try {
            appContext.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + waitMs, reconnectAlarmIntent(),
            )
        } catch (e: RuntimeException) {
            // The coroutine timer still runs (while awake).
        }
    }

    private fun cancelReconnectAlarm() {
        try {
            appContext.getSystemService(AlarmManager::class.java)?.cancel(reconnectAlarmIntent())
        } catch (e: RuntimeException) {
            // Nothing to cancel.
        }
    }

    /** Stops what belongs to a live connection: the reconnect timer and its alarm. */
    private fun stopConnectionWork() {
        synchronized(jobsLock) {
            reconnectJob?.cancel()
            reconnectJob = null
        }
        cancelReconnectAlarm()
    }

    private fun canSend(): Boolean =
        paired && isOnline() && !sendsPaused && !archiveForeign && holdsPending.get() == 0

    private fun kickOutbox() {
        outboxWorker // started on first use
        outboxKicks.trySend(Unit)
    }

    // ============================== the bridge's store ==============================

    private fun busyMark(): File = File(appContext.filesDir, BUSY_MARK)

    /**
     * Stores what the bridge's store holds and the archive does not: events
     * the last run was handed but did not get to store ([busyLock]). Every
     * conversation the bridge lists is compared by message count; where the
     * archive has fewer, the conversation is paged through and the missing
     * messages stored, as history (no notifications). Nothing is asked of
     * the phone: each page asks for no more than the store holds.
     */
    private fun reconcileStore(b: WaBridge) {
        try {
            val since = gate.epoch()
            val conversations = listOf(Folder.INBOX, Folder.ARCHIVE).flatMap { b.listConversations(it, RECONCILE_CONVERSATIONS) }
            gate.ifCurrent(since) { storeConversations(conversations) } ?: return
            var recovered = 0
            for (conversation in conversations) {
                if (!wantConnected()) return
                if (isForgotten(conversation.id, conversation.lastMessageTs)) continue
                val total = b.fetchMessages(conversation.id, 1, null).total
                if (total <= repo.storedMessageCount(conversation.id)) continue
                var cursor: Cursor? = null
                var seen = 0
                while (seen < total) {
                    val page = b.fetchMessages(conversation.id, minOf(RECONCILE_PAGE, total - seen), cursor)
                    if (page.messages.isEmpty()) break
                    seen += page.messages.size
                    val candidates = page.messages.filter { !it.isDeleted && it.id.isNotEmpty() }
                    val known = repo.knownMessageIds(candidates.map { it.id })
                    val missing = candidates.filter { it.id !in known }.map { it.copy(isOld = true) }
                    if (missing.isNotEmpty()) {
                        recovered += gate.ifCurrent(since) { repo.upsertMessages(missing) } ?: return
                    }
                    cursor = page.cursor ?: break
                }
            }
            storeCheckNeeded = false
            scheduleBusyClear()
            if (recovered > 0) WaLog.add(appContext, "Stored $recovered message(s) the last run did not get to")
        } catch (e: RelayException) {
            WaLog.add(appContext, "The archive could not be compared with the link's store: ${e.code}")
        } catch (e: RuntimeException) {
            WaLog.add(appContext, "The archive could not be compared with the link's store: ${e.javaClass.simpleName}")
        }
    }

    // ============================== events ==============================

    /** From the bridge's thread: queue it and return. */
    private fun enqueueEvent(kind: String, payload: String) {
        if (kind in STORED_KINDS) {
            synchronized(busyLock) {
                pendingStored++
                if (!busy) {
                    busy = true
                    try {
                        busyMark().createNewFile()
                    } catch (e: java.io.IOException) {
                        // Without the mark a crash right now would go unnoticed; nothing else is lost.
                    }
                }
            }
        }
        inbox.put(Event(kind, payload))
    }

    /** [count] events that go into the archive have been stored (or dropped for good). */
    private fun eventsStored(count: Int) {
        if (count <= 0) return
        val idle = synchronized(busyLock) {
            pendingStored = (pendingStored - count).coerceAtLeast(0)
            pendingStored == 0
        }
        if (idle) scheduleBusyClear()
    }

    /** Takes the busy mark back once nothing has been queued for a while (not on every message: they come in bursts). */
    private fun scheduleBusyClear() {
        synchronized(busyLock) {
            busyClear?.cancel()
            busyClear = scope.launch {
                delay(timing.busyClearMs)
                synchronized(busyLock) {
                    if (pendingStored == 0 && busy && !storeCheckNeeded) {
                        busyMark().delete()
                        busy = false
                    }
                }
            }
        }
    }

    /** The event thread: bridge events and [Task]s, in the order they were queued. */
    private fun pump() {
        while (true) {
            val first = try {
                inbox.take()
            } catch (e: InterruptedException) {
                return
            }
            when (first) {
                is Task -> guarded("task") { first.block() }
                is Event -> if (first.kind == WaBridge.KIND_MESSAGE) {
                    // A history sync hands messages over by the thousand: stored together.
                    val batch = ArrayList<Event>()
                    batch.add(first)
                    while (batch.size < MAX_BATCH) {
                        val next = inbox.peek() as? Event ?: break
                        if (next.kind != WaBridge.KIND_MESSAGE) break
                        batch.add(inbox.poll() as Event)
                    }
                    guarded(first.kind, stored = batch.size) { handleMessages(batch) }
                } else {
                    guarded(first.kind, stored = if (first.kind in STORED_KINDS) 1 else 0) { handleEvent(first.kind, first.payload) }
                }
            }
        }
    }

    /** Never lets a failure end the event thread. An event that could not be stored is fetched from the bridge's store later. */
    private fun guarded(kind: String, stored: Int = 0, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            // The class name only: an exception's message can quote the payload.
            WaLog.add(appContext, "Could not handle a $kind event: ${t.javaClass.simpleName}")
            if (stored > 0 && t !is JSONException) storeCheckNeeded = true
        } finally {
            eventsStored(stored)
        }
    }

    private fun handleEvent(kind: String, payload: String) {
        val now = System.currentTimeMillis()
        if (kind != WaBridge.KIND_STATE && now - lastHealthEventWrite >= timing.healthEventIntervalMs) {
            lastHealthEventWrite = now
            repo.updateHealth(lastEventTs = now)
        }
        // The archive is another account's: nothing of this one is stored in it yet.
        if (archiveForeign && kind in STORED_KINDS) return
        val json = JSONObject(payload)
        when (kind) {
            WaBridge.KIND_STATE -> onState(RelayStateEvent.fromJson(json), now)
            WaBridge.KIND_CONVERSATION -> onConversation(ConversationDto.fromJson(json))
            WaBridge.KIND_RECEIPT -> onReceipt(WaReceipt.fromJson(json))
            WaBridge.KIND_TYPING -> onTyping(TypingDto.fromJson(json))
            WaBridge.KIND_PRESENCE -> onPresence(WaPresenceEvent.fromJson(json))
            WaBridge.KIND_HISTORY_SYNC -> onHistorySync(WaHistorySync.fromJson(json))
            WaBridge.KIND_QR -> onQr(WaQr.fromJson(json))
            WaBridge.KIND_PAIR_SUCCESS -> onPairSuccess(WaPairSuccess.fromJson(json))
            WaBridge.KIND_PAIR_ERROR -> onPairError(WaPairError.fromJson(json))
        }
    }

    // --- state ----------------------------------------------------------------------

    private fun onState(event: RelayStateEvent, now: Long) {
        val previous = _state.value
        val next: RelayState = when (event.state) {
            "connecting" -> when {
                // The linking connection: the setup screen's business, not a connection of the link.
                !paired -> if (pairingMode != PairingMode.NONE) RelayState(RelayState.Phase.PAIRING) else return
                event.code == WaErrors.CONNECTION_LOST -> RelayState(RelayState.Phase.RECONNECTING, event.code, event.detail)
                else -> RelayState(RelayState.Phase.CONNECTING, event.code, event.detail)
            }
            "connected" -> if (paired) RelayState(RelayState.Phase.CONNECTED) else return
            "disconnected" -> when {
                // The end of a linking attempt, which whoever ended it has already shown; one
                // that arrives once the next attempt is open is not about that one.
                !paired -> if (pairingMode != PairingMode.NONE) return else idleState()
                // Down because its notification is off: the screens say so, not just "disconnected".
                event.code.isEmpty() && wantConnected() && !RelayNotifications.statusVisible(appContext, ChatService.WHATSAPP) ->
                    RelayState(RelayState.Phase.DISCONNECTED, RelayState.NOTIFICATIONS_OFF)
                else -> RelayState(RelayState.Phase.DISCONNECTED, event.code, event.detail)
            }
            "logged_out" -> RelayState(RelayState.Phase.LOGGED_OUT, event.code, event.detail)
            // A linking attempt reports its own end (pair_error).
            "error" -> if (paired) RelayState(RelayState.Phase.ERROR, event.code, event.detail) else return
            // The echo of an unlink, which sets the state itself; one that comes
            // once a new link is under way or made is not about it.
            "unpaired" -> if (paired || pairingMode != PairingMode.NONE || previous.phase == RelayState.Phase.UNPAIRED) {
                return
            } else {
                RelayState(RelayState.Phase.UNPAIRED, event.code, event.detail)
            }
            else -> return
        }
        if (next.phase == RelayState.Phase.LOGGED_OUT) {
            // Before the state shows: whoever sees "logged out" also sees that nothing is linked.
            paired = false
            WaPrefs.setLink(appContext, WaPrefs.LINK_LOGGED_OUT)
        }
        // The bridge has ended the connection itself, and does not bring it back.
        if (next.phase == RelayState.Phase.LOGGED_OUT || next.phase == RelayState.Phase.ERROR) bridgeWanted = false
        setState(next)
        if (paired || next.phase == RelayState.Phase.LOGGED_OUT) {
            lastHealthEventWrite = now
            repo.updateHealth(
                status = event.state,
                lastEventTs = now,
                lastError = if (event.code.isEmpty()) null else listOf(event.code, event.detail).filter { it.isNotEmpty() }.joinToString(": "),
            )
        }
        if (previous.phase != next.phase) {
            WaLog.add(appContext, "State: ${event.state}" + if (event.code.isEmpty()) "" else " (${event.code})")
        }
        when (next.phase) {
            RelayState.Phase.CONNECTED -> onOnline()
            RelayState.Phase.CONNECTING, RelayState.Phase.RECONNECTING -> endPresence()
            RelayState.Phase.DISCONNECTED -> {
                clearTyping()
                endPresence()
                endHistorySync()
            }
            RelayState.Phase.LOGGED_OUT -> onLoggedOut()
            RelayState.Phase.ERROR -> {
                clearTyping()
                endPresence()
                endHistorySync()
                if (event.code in NO_RETRY) {
                    // WhatsApp said not to come back for now: only the user starts it again.
                    WaLog.add(appContext, "Not reconnecting: ${event.code}")
                    giveUpLive()
                } else {
                    scheduleReconnect()
                }
            }
            else -> Unit
        }
    }

    /** Connected: this connection knows nothing of presence yet, and queued sends may go. */
    private fun onOnline() {
        synchronized(jobsLock) {
            reconnectAttempts = 0
            reconnectJob?.cancel()
            reconnectJob = null
        }
        cancelReconnectAlarm()
        sendsPaused = false
        resetPresence()
        kickPresence()
        kickOutbox()
    }

    /** The phone, or WhatsApp, ended the link (the bridge has wiped its store): only the user can link again. */
    private fun onLoggedOut() {
        WaPrefs.setLiveEnabled(appContext, false)
        resumeOnVisible = false
        stopConnectionWork()
        WaService.stop(appContext, this)
        pairingMode = PairingMode.NONE
        _qr.value = null
        _pairing.value = WaPairing.Idle
        clearTyping()
        endPresence()
        endHistorySync()
        RelayNotifications.showWaRelinkNeeded(appContext)
        WaLog.add(appContext, "The link was ended on the phone or by WhatsApp; link again to use it")
    }

    // --- pairing --------------------------------------------------------------------

    private fun onQr(event: WaQr) {
        if (paired || pairingMode != PairingMode.QR || event.code.isEmpty()) return
        _qr.value = event.code
        _pairing.value = WaPairing.WaitingForQr(event.code)
    }

    /**
     * The user confirmed on their phone. The bridge now connects by itself,
     * so the service must be there at once: without it (Android does not
     * start one from the background, where the app is while the user types
     * the code into WhatsApp) the connection is closed again until a screen
     * is shown.
     */
    private fun onPairSuccess(event: WaPairSuccess) {
        // Before anything of this account is stored.
        reconcileOwner(event.ownId)
        paired = true
        WaPrefs.setLink(appContext, WaPrefs.LINK_LINKED)
        pairingMode = PairingMode.NONE
        _qr.value = null
        _pairing.value = WaPairing.Paired
        RelayNotifications.cancelWaRelinkNeeded(appContext)
        repo.updateHealth(status = "disconnected", lastError = "", account = event.ownId)
        setState(RelayState(RelayState.Phase.DISCONNECTED))
        WaLog.add(appContext, "Linked with WhatsApp")
        historyStored = 0
        // The bridge connects by itself now.
        bridgeWanted = true
        startLive()
        disconnectUnlessWanted()
    }

    private fun onPairError(event: WaPairError) {
        if (paired) return
        WaLog.add(appContext, "Linking did not finish: ${event.code.ifEmpty { WaErrors.PAIR_FAILED }}")
        pairingMode = PairingMode.NONE
        _qr.value = null
        _pairing.value = WaPairing.Failed(event.code.ifEmpty { WaErrors.PAIR_FAILED })
        if (_state.value.phase == RelayState.Phase.PAIRING) setState(idleState())
    }

    // --- messages and conversations ----------------------------------------------------------

    private fun handleMessages(batch: List<Event>) {
        val now = System.currentTimeMillis()
        if (now - lastHealthEventWrite >= timing.healthEventIntervalMs) {
            lastHealthEventWrite = now
            repo.updateHealth(lastEventTs = now)
        }
        if (archiveForeign) return
        val history = ArrayList<MessageDto>()
        fun flush() {
            if (history.isEmpty()) return
            storeHistory(history.toList())
            history.clear()
        }
        for (event in batch) {
            val dto = try {
                MessageDto.fromJson(JSONObject(event.payload))
            } catch (e: JSONException) {
                WaLog.add(appContext, "Could not read a message event")
                continue
            }
            if (!dto.isDeleted && isForgotten(dto.conversationId, dto.timestampMs)) continue
            if (dto.isOld && !dto.isDeleted) {
                history.add(dto)
            } else {
                flush()
                onMessage(dto)
            }
        }
        flush()
    }

    /** History (the sync after linking, or older messages asked of the phone): stored together, never notified. */
    private fun storeHistory(messages: List<MessageDto>) {
        repo.upsertMessages(messages)
        for ((conversationId, list) in messages.groupBy { it.conversationId }) {
            olderArrivals[conversationId]?.addAndGet(list.size)
        }
        val progress = _backfill.value
        if (progress.running) _backfill.value = progress.copy(messages = progress.messages + messages.size)
    }

    private fun onMessage(dto: MessageDto) {
        // Read before the upsert moves it: the conversation's newest message so far.
        val previousLatest = if (dto.isDeleted) 0L else repo.conversation(dto.conversationId)?.lastMessageTs ?: 0L
        val result = repo.upsertMessage(dto)
        if (dto.isDeleted || dto.id.isEmpty()) return
        if (dto.fromMe) applyEarlyReceipt(dto.id)
        if (!result.inserted) return
        // An old message seen for the first time is not news: over a day old
        // and older than what the conversation already had.
        val stale = System.currentTimeMillis() - dto.timestampMs > STALE_MESSAGE_MS && dto.timestampMs < previousLatest
        if (!dto.isOld && !stale && !dto.fromMe && dto.direction == "in") MessageNotifier.onIncoming(appContext, repo, dto)
    }

    private fun onConversation(dto: ConversationDto) {
        if (dto.id.isEmpty()) return
        val before = repo.conversation(dto.id)
        storeConversations(listOf(dto))
        // Read on the phone (or answered from it): its notification here has done its job.
        if (before?.unread == true && !dto.unread) MessageNotifier.cancel(appContext, dto.id, ChatService.WHATSAPP)
    }

    /**
     * Stores what the bridge says about conversations. One the phone deleted
     * is deleted here too (WhatsApp keeps no local deleted folder: no screen
     * would show it), and one deleted here stays deleted until it has a
     * message newer than its deletion.
     */
    private fun storeConversations(list: List<ConversationDto>) {
        val kept = ArrayList<ConversationDto>(list.size)
        for (dto in list) {
            if (dto.id.isEmpty()) continue
            if (dto.folder == Folder.DELETED.wire) {
                repo.deleteConversationLocal(dto.id)
                recallChat(dto.id)
                readUpTo.remove(dto.id)
                MessageNotifier.cancel(appContext, dto.id, ChatService.WHATSAPP)
                continue
            }
            if (isForgotten(dto.id, dto.lastMessageTs)) continue
            kept.add(dto)
        }
        if (kept.isNotEmpty()) repo.upsertConversations(kept)
    }

    /** Remembers that the user deleted this chat here, as of its newest message (or now, if that is later). */
    private fun forgetChat(conversationId: String) {
        val latest = runCatching { repo.conversation(conversationId)?.lastMessageTs }.getOrNull() ?: 0L
        synchronized(deletedChats) {
            deletedChats[conversationId] = maxOf(System.currentTimeMillis(), latest)
            WaPrefs.setDeletedChats(appContext, deletedChats)
        }
    }

    /** The chat is wanted again (a new message, the phone deleted it too, or the user opened it anew). */
    private fun recallChat(conversationId: String) {
        synchronized(deletedChats) {
            if (deletedChats.remove(conversationId) != null) WaPrefs.setDeletedChats(appContext, deletedChats)
        }
    }

    /**
     * Whether something of a chat the user deleted here is from before the
     * deletion ([timestampMs] is the message's, or the conversation's
     * newest). Something newer brings the chat back.
     */
    private fun isForgotten(conversationId: String, timestampMs: Long): Boolean {
        val deletedAt = synchronized(deletedChats) { deletedChats[conversationId] } ?: return false
        if (timestampMs <= deletedAt) return true
        recallChat(conversationId)
        return false
    }

    // --- receipts ---------------------------------------------------------------------

    private fun onReceipt(receipt: WaReceipt) {
        val status = when (receipt.status) {
            WaReceipt.DELIVERED -> STATUS_DELIVERED
            WaReceipt.READ, WaReceipt.PLAYED -> STATUS_READ
            else -> return
        }
        if (receipt.messageIds.isEmpty()) return
        // The user's messages this receipt is the first to say were read.
        val newlyRead = if (status != STATUS_READ) emptyList() else receipt.messageIds.distinct().mapNotNull { id ->
            repo.message(id)?.takeIf { it.isOutgoing && !ReadNotifier.isRead(it.status) }
        }
        val found = repo.advanceMessageStatus(receipt.messageIds, status, STATUS_CODES.getValue(status), ::statusRank)
        if (newlyRead.isNotEmpty()) {
            val now = System.currentTimeMillis()
            runCatching {
                val times = com.lightdecoder.reactions.ReadTimes.get(appContext)
                newlyRead.forEach { times.note(ChatService.WHATSAPP, it.id, now, live = true) }
            }
            ReadNotifier.onRead(appContext, repo, newlyRead.mapNotNull { repo.message(it.id) }.filter { ReadNotifier.isRead(it.status) })
        }
        val early = receipt.messageIds.filter { it !in found }
        if (early.isEmpty()) return
        // The message is not stored yet (its send is still returning): applied when it is.
        synchronized(earlyReceipts) {
            for (id in early) {
                val known = earlyReceipts[id]
                if (known == null || statusRank(known) < statusRank(status)) earlyReceipts[id] = status
            }
        }
    }

    private fun applyEarlyReceipt(messageId: String) {
        val status = synchronized(earlyReceipts) { earlyReceipts.remove(messageId) } ?: return
        try {
            repo.advanceMessageStatus(listOf(messageId), status, STATUS_CODES.getValue(status), ::statusRank)
        } catch (e: RuntimeException) {
            WaLog.add(appContext, "A receipt could not be stored: ${e.javaClass.simpleName}")
        }
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
            ?: appContext.getString(R.string.relay_notify_someone)
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

    // --- presence and history sync ----------------------------------------------------------

    private fun onPresence(event: WaPresenceEvent) {
        if (event.participantId.isEmpty()) return
        // Asked for while a screen was in front; by now none may be.
        if (!screenVisible()) return
        _presence.value = _presence.value + (event.participantId to event.toPresence())
    }

    private fun onHistorySync(event: WaHistorySync) {
        val current = _backfill.value
        val messages = if (current.running) current.messages else 0
        _backfill.value = BackfillProgress(
            running = !event.done, conversationsDone = event.progress, conversationsTotal = 100, messages = messages,
        )
        synchronized(jobsLock) {
            historyIdle?.cancel()
            historyIdle = if (event.done) {
                null
            } else {
                // The phone went quiet (or away) mid-sync: it is not "running" for ever.
                scope.launch {
                    delay(timing.historyIdleMs)
                    endHistorySync()
                }
            }
        }
        if (event.done && current.running) WaLog.add(appContext, "History sync finished: $messages messages")
        if (!current.running && !event.done) WaLog.add(appContext, "History sync started")
    }

    private fun endHistorySync() {
        synchronized(jobsLock) {
            historyIdle?.cancel()
            historyIdle = null
        }
        val current = _backfill.value
        if (current.running) _backfill.value = current.copy(running = false)
    }

    // --- media ----------------------------------------------------------------------

    /**
     * A part's file. The file itself comes from the media id; inline bytes
     * only when they are the media itself (a contact card), never the small
     * preview WhatsApp sends next to a picture, exactly as for Google
     * Messages. With [thumbnail] only what is on this phone already.
     */
    private fun loadMedia(messageId: String, partId: String, thumbnail: Boolean): File? {
        val since = gate.epoch()
        val part = repo.mediaPart(messageId, partId) ?: return null
        val local = part.localPath.takeUnless { part.inlineIsPreview && MediaCache.isInlineFile(it, messageId, partId) }.orEmpty()
        val stored = (if (thumbnail) listOf(part.thumbPath, local) else listOf(local))
            .filter { it.isNotEmpty() }.map(::File).firstOrNull { it.isFile && it.length() > 0 }
        if (stored != null || thumbnail) return stored
        return try {
            when {
                part.mediaId.isNotEmpty() -> {
                    if (part.size > WaMedia.MAX_DOWNLOAD_BYTES) {
                        WaLog.add(appContext, "Media not loaded: too large to load here")
                        return null
                    }
                    // Needs the link and a connection; without them the caller says "connect to load".
                    if (!paired) throw RelayException(RelayException.NOT_CONNECTED, "WhatsApp is not linked")
                    val bytes = requireBridge().downloadMedia(part.mediaId, part.key)
                    writeMedia(since, messageId, partId, part.mediaId, bytes, part.mime, part.fileName)
                }
                part.inlineIsMedia ->
                    writeMedia(since, messageId, partId, MediaCache.inlineKey(messageId, partId), part.inlineData, part.mime, part.fileName)
                else -> null
            }
        } catch (e: RelayException) {
            if (e.code == RelayException.NOT_CONNECTED || e.code == RelayException.NOT_PAIRED) {
                throw RelayException(RelayException.NOT_CONNECTED, "connect to load")
            }
            WaLog.add(appContext, "Media download failed: ${e.code}")
            null
        } catch (e: java.io.IOException) {
            WaLog.add(appContext, "Media could not be stored: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Stores bytes in the archive's own media directory (whole or not at
     * all) and records the path. Null, with nothing left on disk, if the
     * archive was deleted since [since], or the message went away while the
     * download ran.
     */
    private fun writeMedia(
        since: Long, messageId: String, partId: String, cacheKey: String, bytes: ByteArray, mime: String, fileName: String,
    ): File? = gate.ifCurrent(since) {
        if (repo.mediaPart(messageId, partId) == null) return@ifCurrent null
        val file = repo.mediaFileFor(cacheKey, WaMedia.extensionFor(bytes.copyOf(minOf(bytes.size, 64)), mime, fileName))
        // Written and recorded in one step as far as the cleanup of unused files can tell.
        repo.withMediaFileLock {
            MediaCache.writeAtomically(file, bytes)
            // When not recorded, the repository has already deleted the file (unless another part uses it).
            if (repo.setMediaLocalPath(messageId, partId, cacheKey, file.path, thumbnail = false)) file else null
        }
    }

    // ============================== threads ==============================

    private fun setState(state: RelayState) {
        _state.value = state
    }

    private fun onIntake(what: String, block: () -> Unit) {
        try {
            intake.execute {
                try {
                    block()
                } catch (t: Throwable) {
                    WaLog.add(appContext, "Could not $what: ${t.javaClass.simpleName}")
                }
                kickOutbox()
            }
        } catch (e: RejectedExecutionException) {
            // Shut down.
        }
    }

    private suspend fun <T> onIntakeAndWait(block: () -> T): T = suspendCancellableCoroutine { cont ->
        try {
            intake.execute {
                try {
                    cont.resume(block())
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                }
            }
        } catch (e: RejectedExecutionException) {
            cont.resumeWithException(e)
        }
    }

    /** Waits until the events queued so far have been handled. Not for the event thread itself. */
    private fun awaitEventThread() {
        if (Thread.currentThread() === eventThread || !eventThread.isAlive) return
        val done = CountDownLatch(1)
        inbox.put(Task { done.countDown() })
        try {
            done.await(EVENT_DRAIN_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ============================== testing ==============================

    /** Waits for queued callbacks and sends to be taken in (not for network calls to finish). */
    internal fun awaitIdleForTesting() {
        awaitEventThread()
        try {
            intake.submit {}.get(EVENT_DRAIN_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // Timed out or shut down: carry on.
        }
        awaitEventThread()
    }

    /** Creates the bridge now (it is otherwise created on first use). */
    internal fun bridgeForTesting(): WaBridge = ensureBridge()

    /** Whether the archive is to be compared with the bridge's store before the next connect. */
    internal fun storeCheckNeededForTesting(): Boolean = storeCheckNeeded

    internal fun shutdownForTesting() {
        scope.cancel()
        eventThread.interrupt()
        intake.shutdownNow()
    }

    companion object {
        /** The name this device has in WhatsApp's "Linked devices" list on the phone. */
        const val DEVICE_NAME = "LightDecoder"

        private const val STORE_DIR = "whatsapp"

        /** The mark that events were being stored ([busyLock]); a file, so it is there the moment it is made. */
        private const val BUSY_MARK = "whatsapp-events-pending"

        /** Event kinds whose content goes into the archive. */
        private val STORED_KINDS = setOf(WaBridge.KIND_MESSAGE, WaBridge.KIND_CONVERSATION, WaBridge.KIND_RECEIPT)

        private const val MAX_BATCH = 200
        private const val CONVERSATION_COUNT = 500
        private const val RECONCILE_CONVERSATIONS = 2_000
        private const val RECONCILE_PAGE = 200
        private const val EVENT_DRAIN_TIMEOUT_S = 10L
        private const val STALE_MESSAGE_MS = 24 * 60 * 60 * 1000L
        private const val MAX_EARLY_RECEIPTS = 256
        private const val MAX_ONLINE_TRIES = 10
        private const val READ_LOOKBACK = 50
        private const val WAIT_STEP_MS = 50L
        private const val RECONNECT_REQUEST = 1

        /** The server part of a user's JID: a one-to-one chat's id ends with it. */
        private const val USER_SERVER = "@s.whatsapp.net"

        /** The detail of the INTERNAL error ensureBridge throws when the Go library cannot be loaded. */
        private const val LIBRARY_UNAVAILABLE = "bridge library unavailable"

        /** The error a send queued before an unlink is held with (content-free), as for Google Messages. */
        internal const val UNPAIRED_ERROR = "unpaired"

        /** The bridge reuses Google Messages' status names (and codes), which the screens already turn into ticks. */
        private const val STATUS_DELIVERED = "OUTGOING_DELIVERED"
        private const val STATUS_READ = "OUTGOING_DISPLAYED"
        private val STATUS_CODES = mapOf(STATUS_DELIVERED to 2, STATUS_READ to 11)

        /** Orders outgoing statuses so that a late or repeated receipt never moves a message backwards. */
        internal fun statusRank(status: String): Int = when {
            status.startsWith("OUTGOING_FAILED") -> -1
            status == "OUTGOING_COMPLETE" -> 1
            status == STATUS_DELIVERED -> 2
            status == STATUS_READ -> 3
            else -> 0
        }

        /** Phases in which the bridge keeps a connection (or keeps reconnecting one). */
        private val LIVE_PHASES = setOf(
            RelayState.Phase.CONNECTING, RelayState.Phase.CONNECTED, RelayState.Phase.RECONNECTING,
        )

        /** Phases without a link. */
        private val UNLINKED_PHASES = setOf(
            RelayState.Phase.UNPAIRED, RelayState.Phase.PAIRING, RelayState.Phase.LOGGED_OUT,
        )

        /** States that say something more pressing than "notifications are off". */
        private val NEEDS_USER_PHASES = UNLINKED_PHASES

        /** Connect failures that only the user can fix; they are not retried. */
        private val NEEDS_USER = setOf(RelayException.LOGGED_OUT, RelayException.NOT_PAIRED)

        /** Errors after which WhatsApp must not be tried again by itself: a ban, or a client it no longer accepts. */
        private val NO_RETRY = setOf(WaErrors.TEMP_BANNED, WaErrors.CLIENT_OUTDATED)

        /**
         * The app-private directory the bridge keeps its store in (keys,
         * chat index, message cache): filesDir/whatsapp. Excluded from
         * backup like everything else.
         */
        fun storeDir(context: Context): File = File(context.applicationContext.filesDir, STORE_DIR)

        @Volatile
        private var instance: WaManager? = null

        /** The process's WhatsApp manager, over [ArchiveRepository.get] for [ChatService.WHATSAPP]. Cheap; safe on the main thread. */
        fun get(context: Context): WaManager =
            instance ?: synchronized(this) {
                instance ?: WaManager(context, ArchiveRepository.get(context, ChatService.WHATSAPP), Timing()).also { instance = it }
            }

        /**
         * Replaces the process instance with one over [repo] (tests only);
         * the old one is shut down. [repo] must be a WhatsApp archive
         * ([ArchiveRepository.forTesting] with [ChatService.WHATSAPP]).
         */
        internal fun installForTesting(context: Context, repo: ArchiveRepository, timing: Timing = Timing()): WaManager {
            require(repo.service == ChatService.WHATSAPP) { "the repository is ${repo.service}'s, not WhatsApp's" }
            return synchronized(this) {
                instance?.shutdownForTesting()
                WaManager(context, repo, timing).also { instance = it }
            }
        }

        internal fun resetForTesting() = synchronized(this) {
            instance?.shutdownForTesting()
            instance = null
        }

        private fun named(name: String) = ThreadFactory { r -> Thread(r, name).apply { isDaemon = true } }
    }
}
