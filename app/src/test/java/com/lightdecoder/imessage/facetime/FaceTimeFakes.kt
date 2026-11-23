package com.lightdecoder.imessage.facetime

import android.view.Surface
import com.localob.messaging.app.facetime.FaceTimeInCallService
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex

/** The library's FaceTime functions, as a test wants them: what was asked is written down in [log], in order. */
class FakeBridge : CallBridge {
    override val guard = Mutex()

    @Volatile
    override var connected = true

    @Volatile
    override var sinceStartMs = 10 * 60_000L

    @Volatile
    var myHandles = listOf(ME)
    val sessions: MutableList<CallSessionInfo> = Collections.synchronizedList(ArrayList())
    val log: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** Every handle made, in order: each is handed to one join. */
    val handlesMade: MutableList<Long> = Collections.synchronizedList(ArrayList())
    private var nextHandle = 1000L

    @Volatile
    var failCreate = false

    @Volatile
    var noSession = false

    /** What `validateTargetsFacetime` answers for each address asked about (default: everyone can be called). */
    @Volatile
    var capableOf: (String) -> Boolean = { true }

    override suspend fun handles(): List<String> = myHandles

    override suspend fun setLocalInterfaces() {
        log += "setLocalInterfaces"
    }

    override suspend fun createFacetime(guid: String, handle: String, participants: List<String>, video: Boolean) {
        log += "createFacetime:$guid:$handle:${participants.joinToString(",")}:$video"
        if (failCreate) throw IllegalStateException("the relay did not answer")
        sessions += CallSessionInfo(guid, myHandles, participants + handle, System.currentTimeMillis(), video, active = false)
    }

    override suspend fun ringMembers(guid: String, handles: List<String>) {
        log += "ringMembers:$guid:${handles.joinToString(",")}"
    }

    /** How long a take-back or a decline waits for a join that holds [guard] (the real one: [GUARD_WAIT_MS]). */
    @Volatile
    var guardWaitMs = GUARD_WAIT_MS

    override suspend fun cancelFacetime(guid: String) = guard.withLockWithin(guardWaitMs) {
        log += "cancelFacetime:$guid"
        if (sessions.none { it.guid == guid }) throw IllegalStateException("no such call")
        sessions.removeAll { it.guid == guid }
        Unit
    }

    override suspend fun declineFacetime(guid: String) = guard.withLockWithin(guardWaitMs) {
        log += "declineFacetime:$guid"
        Unit
    }

    /** Called as the call list is read (a test takes a call out at that very moment, or counts). */
    @Volatile
    var onSessionRead: (String) -> Unit = {}

    override suspend fun session(guid: String): CallSessionInfo? {
        log += "sessions"
        onSessionRead(guid)
        // The whole list, however long.
        return sessions.toList().firstOrNull { it.guid == guid }
    }

    override suspend fun <T> withNativeHandle(block: (Long) -> T): T? {
        if (noSession) return null
        val handle = synchronized(this) { nextHandle++ }
        handlesMade += handle
        log += "handle:$handle"
        return block(handle)
    }

    override suspend fun capable(targets: List<String>, from: String?): List<String> {
        log += "capable:${targets.joinToString(",")}"
        return targets.filter(capableOf)
    }

    /** A call from [caller] as the library lists it once its ring came. */
    fun incoming(guid: String, caller: String = ALICE, video: Boolean = false, active: Boolean = true, startMs: Long? = System.currentTimeMillis()) {
        sessions.removeAll { it.guid == guid }
        sessions += CallSessionInfo(guid, myHandles, listOf(caller) + myHandles, startMs, video, active)
    }

    companion object {
        const val ME = "tel:+15550000001"
        const val ALICE = "tel:+15551234567"
        const val BOB = "tel:+15557654321"
    }
}

/** The library's side of one joined call: every call into it is written down, and an orientation it would die of fails the test. */
class FakeNative(val host: FaceTimeInCallService.Host? = null) : CallNative {
    val calls: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** The session handles `createAvc` was given. */
    val handlesUsed: MutableList<Long> = Collections.synchronizedList(ArrayList())

    @Volatile
    override var isVideo = false

    /** What `createAvc` returns (0: the join failed). */
    @Volatile
    var avc = 77L

    @Volatile
    var throwOnCreate: Throwable? = null

    /** `createAvc` waits for this before it returns (a join that takes its time). */
    @Volatile
    var gate: CountDownLatch? = null

    /** Counted down when `createAvc` was entered. */
    val entered = CountDownLatch(1)

    /** Whether the calling thread is the main thread (a test with a phone's looper sets it); what it said for each call, in order. */
    @Volatile
    var isMainThread: (() -> Boolean)? = null
    val onMainThread: MutableList<String> = Collections.synchronizedList(ArrayList())

    private fun note(call: String) {
        calls += call
        if (isMainThread?.invoke() == true) onMainThread += call
    }

    override fun createAvc(guid: String, handle: Long): Long {
        note("createAvc:$guid")
        handlesUsed += handle
        entered.countDown()
        gate?.await(10, TimeUnit.SECONDS)
        throwOnCreate?.let { throw it }
        return avc
    }

    override fun destroyAvc(avc: Long) {
        note("destroyAvc:$avc")
    }

    override fun enableCamera(avc: Long, enabled: Boolean, captureMode: Int) {
        note("enableCamera:$avc:$enabled:$captureMode")
    }

    override fun enableMicrophone(avc: Long, enabled: Boolean) {
        note("enableMicrophone:$avc:$enabled")
    }

    override fun refreshSurfaces(avc: Long) {
        note("refreshSurfaces:$avc")
    }

    override fun updateOrientation(avc: Long, orientation: Int) {
        note("updateOrientation:$avc:$orientation")
        check(orientation in 0..3) { "the library takes its process down on orientation $orientation" }
    }
}

/** Makes [FakeNative]s and keeps them, so a test can look at each call's. */
class FakeNatives : CallNatives {
    val made: MutableList<FakeNative> = Collections.synchronizedList(ArrayList())

    /** Set up each one as it is made (before it is joined). */
    @Volatile
    var onMade: (FakeNative) -> Unit = {}

    override fun create(host: FaceTimeInCallService.Host): CallNative = FakeNative(host).also {
        onMade(it)
        made += it
    }

    val last: FakeNative get() = made.last()
}

class FakeMain : MainLink {
    val logged: MutableList<CallLogEntry> = Collections.synchronizedList(ArrayList())
    val asked: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** Who each caller is; null: the main process does not answer. */
    @Volatile
    var callers: (String) -> CallerInfo? = { CallerInfo("Alice Martin", known = true, conversationId = "imsg:tel:+15551234567") }

    override suspend fun caller(handle: String): CallerInfo? {
        asked += handle
        return callers(handle)
    }

    override suspend fun log(entry: CallLogEntry) {
        logged += entry
    }
}

class FakeNotices : CallNotices {
    val shown: MutableList<Call> = Collections.synchronizedList(ArrayList())
    val hidden: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** The call ringing now, if any. */
    @Volatile
    var ringing: Call? = null

    override fun showIncoming(call: Call) {
        shown += call
        ringing = call
    }

    override fun hideIncoming(guid: String) {
        hidden += guid
        if (ringing?.guid == guid) ringing = null
    }
}

class FakeForeground : CallForeground {
    @Volatile
    var starts = 0

    @Volatile
    var stops = 0

    @Volatile
    var running = false

    override fun start() {
        starts++
        running = true
    }

    override fun stop() {
        stops++
        running = false
    }

    override suspend fun awaitStarted(timeoutMs: Long) = Unit
}

class FakeAudio : CallAudio {
    @Volatile
    var available = listOf(Route.EARPIECE, Route.SPEAKER)

    @Volatile
    var entered = false

    @Volatile
    var enters = 0

    @Volatile
    var leaves = 0
    val routed: MutableList<Route> = Collections.synchronizedList(ArrayList())

    @Volatile
    var changed: (() -> Unit)? = null

    override fun routes(): List<Route> = available

    override fun enter(changed: () -> Unit) {
        entered = true
        enters++
        this.changed = changed
    }

    override fun route(route: Route): Boolean {
        routed += route
        return route in available
    }

    override fun leave() {
        if (entered) leaves++
        entered = false
        changed = null
    }
}

class FakeCamera : CallCamera {
    @Volatile
    var front = true

    @Volatile
    var back = true

    /** Whether a start goes through. */
    @Volatile
    var works = true

    @Volatile
    var running = false
    val starts: MutableList<String> = Collections.synchronizedList(ArrayList())

    @Volatile
    var stops = 0

    override fun has(front: Boolean): Boolean = if (front) this.front else back

    override fun start(encoder: Surface, preview: Surface?, front: Boolean, started: (ok: Boolean, front: Boolean) -> Unit) {
        starts += "front=$front preview=${preview != null}"
        running = works
        started(works, front)
    }

    override fun stop(stopped: () -> Unit) {
        stops++
        running = false
        stopped()
    }
}

class FakeRingback : CallRingback {
    @Volatile
    var on = false

    @Volatile
    var starts = 0

    override fun start() {
        on = true
        starts++
    }

    override fun stop() {
        on = false
    }
}

/**
 * A whole call engine over the fakes above, for tests of what is built on it (the call screen, the
 * notices): the library, the camera, the phone's audio and the main process are all stand-ins, and
 * the timers run out only when a test says so ([runOut]).
 */
class FakeCallEngine(app: android.content.Context) {
    val bridge = FakeBridge()
    val natives = FakeNatives()
    val main = FakeMain()
    val notices = FakeNotices()
    val foreground = FakeForeground()
    val audio = FakeAudio()
    val camera = FakeCamera()
    val ringback = FakeRingback()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    @Volatile
    var settings = FaceTimeSettings()

    @Volatile
    var phoneBusy = false

    /** The timers running: how long each is for, and what lets it run out. */
    val timers = java.util.concurrent.ConcurrentLinkedQueue<Pair<Long, kotlinx.coroutines.CompletableDeferred<Unit>>>()

    val calls = FaceTimeCalls(
        app, bridge, natives, main, notices, foreground, audio, camera, ringback,
        settings = { settings }, phoneBusy = { phoneBusy }, scope = scope,
        sleep = { ms ->
            val done = kotlinx.coroutines.CompletableDeferred<Unit>()
            timers += ms to done
            done.await()
        },
        connectWaitMs = 300,
    )

    /** Lets the newest timer of [ms] run out; false when there is none. */
    fun runOut(ms: Long): Boolean = timers.toList().lastOrNull { it.first == ms && !it.second.isCompleted }?.second?.complete(Unit) == true

    /** A call from [caller] that the library lists and Apple just announced. */
    fun ring(guid: String, video: Boolean = false, caller: String = FakeBridge.ALICE) {
        bridge.incoming(guid, caller, video)
        calls.onEvent(uniffi.rust_bridge.FaceTimeEvent.JoinEvent(guid, 7uL, caller, true))
    }

    /** A group call from [caller] (its member list also naming [others]) that the library lists and Apple just announced. */
    fun ringGroup(guid: String, others: List<String>, video: Boolean = false, caller: String = FakeBridge.ALICE) {
        bridge.sessions.removeAll { it.guid == guid }
        bridge.sessions += CallSessionInfo(guid, bridge.myHandles, listOf(caller) + others + bridge.myHandles, System.currentTimeMillis(), video, true)
        calls.onEvent(uniffi.rust_bridge.FaceTimeEvent.JoinEvent(guid, 7uL, caller, true))
    }

    fun close() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
