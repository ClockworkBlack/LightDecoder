package com.lightdecoder.imessage.facetime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.Surface
import androidx.core.content.ContextCompat
import com.lightdecoder.imessage.direct.Handles
import com.localob.messaging.app.facetime.FaceTimeInCallService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.rust_bridge.FaceTimeEvent

/**
 * FaceTime calls, in the ":imessage" process (where the library and its session are): the one call
 * there is ([state]), driven by [CallRules]. This does what the rules say is to be done: asks the
 * library to place, take back and decline calls ([CallBridge]), joins and leaves them ([NativeCall],
 * the only caller of the library's call functions), rings (the incoming notice), keeps the timers,
 * the camera and the audio route, and hands every finished call to the main process for its row in
 * the chat ([MainLink]).
 *
 * What comes in, and from where:
 * - Apple's events, from [com.lightdecoder.imessage.direct.DirectEngine]'s consumer ([onEvent]):
 *   only queued there, so no message waits behind a call and its event is acknowledged at once.
 *   They come from other people's phones: [CallEvent.of] and the rules take nothing from them
 *   as it is, and nothing they say answers a call or turns on the microphone or the camera.
 * - The user, from the call screen and the notices ([place], [answer], [decline], [hangUp], the
 *   buttons): the only way a call is placed or answered.
 * - The library's callbacks for the joined call (its own threads; they only queue or read).
 *
 * Everything goes through one queue, in order. No function of the library is ever called on the
 * main thread: the queue runs on a background dispatcher, joins on [io], the small commands of a
 * joined call (mute, camera, orientation, surfaces) on one thread of their own ([commands]).
 */
class FaceTimeCalls internal constructor(
    private val app: Context,
    private val bridge: CallBridge,
    private val natives: CallNatives,
    private val main: MainLink,
    private val notices: CallNotices,
    private val foreground: CallForeground,
    private val audio: CallAudio,
    private val camera: CallCamera,
    private val ringback: CallRingback,
    private val settings: () -> FaceTimeSettings,
    private val phoneBusy: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    scope: CoroutineScope? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    commands: CoroutineDispatcher? = null,
    private val rules: CallRules = CallRules(),
    /** How a timer waits ([CallRules.Effect.StartTimer]); tests let them run out when they choose. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /** How long a call to place waits for the session to come up (the process may have just started). */
    private val connectWaitMs: Long = CONNECT_WAIT_MS,
    /** Notes the call that holds the line (null: none), for a start after the process was stopped in it ([CallRecovery]). */
    private val remember: (String?) -> Unit = {},
    /** How many of Apple's FaceTime events are taken a minute, whatever they are ([EventRate]). */
    private val eventRate: EventRate = EventRate(),
    /** The most events that wait to be taken; the oldest makes way for one more. */
    eventBacklog: Int = MAX_EVENT_BACKLOG,
) {
    private val failures = CoroutineExceptionHandler { _, e -> FaceTimeLog.note("call work failed (${e::class.java.simpleName})") }
    private val scope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default + failures)

    /** The small commands of a joined call, one after another, never on the main thread. */
    private val commands: CoroutineDispatcher = commands ?: Executors.newSingleThreadExecutor { r -> Thread(r, "facetime-commands").apply { isDaemon = true } }.asCoroutineDispatcher()

    private val _state = MutableStateFlow<Call?>(null)

    /** The one call, or null. */
    val state: StateFlow<Call?> = _state

    /** Someone calling while the user is in a call ([CallRules.waiting]), once they may be offered: "End & Accept" answers them ([answer]). */
    private val _waiting = MutableStateFlow<Call?>(null)
    val waiting: StateFlow<Call?> = _waiting

    private val _media = MutableStateFlow(Media())

    /** The joined call's buttons and pictures. */
    val media: StateFlow<Media> = _media

    private val _messages = MutableSharedFlow<CallMessage>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Things to tell the user once. */
    val messages: SharedFlow<CallMessage> = _messages

    /** Whether a call holds the line (ringing either way, joining or joined). */
    val busy: Boolean get() = _state.value?.live == true

    /** The call screen's views. */
    val surfaces = CallSurfaces()

    private val inputs = Channel<CallRules.Input>(Channel.UNLIMITED)

    /** When something about too many events was last said in the log, by what was said: once a minute each, however many come. */
    private val saidAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Apple's events waiting to be taken: they come from other people's phones, so only so many
     * wait (each may wait for the session, or for the library's call list); the oldest is dropped
     * for one more, and that is said in the log.
     */
    private val events = Channel<FaceTimeEvent>(eventBacklog, BufferOverflow.DROP_OLDEST) {
        sayRarely("backlog", "FaceTime events came faster than they could be taken: the oldest were dropped")
    }

    /** The events of the call there is have an allowance of their own: a flood about other calls does not take its hang-up away. */
    private val ownEventRate = EventRate(eventRate.maxPerWindow, eventRate.windowMs)

    /** An event waited for the session in vain: until it is up, none waits again. */
    private var sessionWaitedFor = false
    private val timers = HashMap<String, Job>()

    /** The joined (or joining) call's library side. */
    private class Joined(val guid: String, val native: NativeCall)

    @Volatile
    private var joined: Joined? = null

    private val mediaLock = Any()

    /** The library's encoder input for the camera's picture, while it has one; and whether the camera draws into it. */
    private var encoder: Surface? = null
    private var cameraRunning = false

    /** The call screens that are visible now ([screenShown]); with none after one was, the camera is paused ([screenHidden]). */
    private val screens = HashSet<Any>()

    @Volatile
    private var cameraPaused = false

    private val foregroundLock = Any()

    /** Joins of the library that have not returned yet, and whether the in-call notice is to go once they have. */
    private var joinsRunning = 0
    private var stopOwed = false

    private val _lingering = MutableStateFlow<Call?>(null)

    /**
     * A call that was given up or left while the library's join for it had not returned: the join
     * may still open the microphone, so the in-call notice stays up ("ending") until it has. Null: none.
     */
    val lingering: StateFlow<Call?> = _lingering

    /** The route the user picked in this call, if any. */
    private var pickedRoute: Route? = null

    /** The screen's rotation as last told ([displayRotation]). */
    @Volatile
    private var rotation = 0

    /** The camera's orientation for the library, for the screen's rotation and how that side's camera is mounted. */
    private fun orientationFor(front: Boolean): Int =
        CallOrientation.forCamera(rotation, front, runCatching { camera.sensorOrientation(front) }.getOrNull())

    init {
        this.scope.launch { for (input in inputs) step(input) }
        this.scope.launch { for (event in events) take(event) }
    }

    // --- Apple's events ----------------------------------------------------------------------------

    /**
     * One FaceTime event from Apple: queued, and returned from at once (the engine acknowledges it
     * then; nothing here ever waits for the user). Never rings by itself: see [CallRules].
     */
    fun onEvent(event: FaceTimeEvent) {
        events.trySend(event)
    }

    /** What a look into the library's call list found (null inside: the call is not there). */
    private class Found(val session: CallSessionInfo?)

    /** Says [text] in the log, at most once a minute for each [what]. */
    private fun sayRarely(what: String, text: String) {
        val t = now()
        val last = saidAt[what]
        if (last != null && t - last in 0 until UNTAKEN_NOTE_MS) return
        saidAt[what] = t
        FaceTimeLog.note(text)
    }

    /** When an event that could not be taken was last said so in the log: once a minute at most, however many come. */
    private var saidUntaken = 0L

    private suspend fun take(event: FaceTimeEvent) {
        val mapped = CallEvent.of(event)
        if (mapped == null) {
            // Nothing a call here has a use for, or one whose call id is no call's (said, rarely: it would explain a call that never rings).
            val odd = event is FaceTimeEvent.JoinEvent || event is FaceTimeEvent.Ring || event is FaceTimeEvent.Decline || event is FaceTimeEvent.LeaveEvent
            if (odd && now() - saidUntaken >= UNTAKEN_NOTE_MS) {
                saidUntaken = now()
                FaceTimeLog.note("a FaceTime event (${CallEvent.kindOf(event)}) was ignored: its call id is not one a call has")
            }
            return
        }
        // Only so many a minute, whoever sends them: each costs a look into the library's call list.
        val own = _state.value?.guid == mapped.guid
        if (!(if (own) ownEventRate else eventRate).allow(now())) {
            sayRarely("rate", "too many FaceTime events in a minute: some were dropped unread")
            return
        }
        try {
            // Handed over right as the process starts: the session it is about comes up in a moment.
            if (bridge.connected) {
                sessionWaitedFor = false
            } else if (!sessionWaitedFor) {
                sessionWaitedFor = true
                withTimeoutOrNull(SESSION_WAIT_MS) { while (!bridge.connected) delay(200) }
            }
            // The one call it is about, looked for in the whole of the library's list.
            val found = withTimeoutOrNull(LIBRARY_MS) { Found(bridge.session(mapped.guid)) }
            if (found == null) {
                FaceTimeLog.note("a FaceTime event (${CallEvent.kindOf(event)}) was not taken: the call list could not be read")
                return
            }
            val sessions = listOfNotNull(found.session)
            val mine = withTimeoutOrNull(LIBRARY_MS) { bridge.handles() }.orEmpty().toSet()
            inputs.trySend(CallRules.Input.Event(mapped, sessions, mine, now(), bridge.sinceStartMs, settings()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            FaceTimeLog.note("a FaceTime event (${CallEvent.kindOf(event)}) was not taken (${e::class.java.simpleName})")
        }
    }

    // --- the user ----------------------------------------------------------------------------------

    /**
     * Places a call to [peer] (their address as the library has it, or as typed). Only ever from
     * the call screen, on the user's tap. Why it was not started, if it was not, comes in [messages].
     */
    fun place(peer: String, name: String, video: Boolean, from: String?, conversationId: String?, photo: String? = null) {
        val to = CallIds.handle(peer) ?: Handles.normalize(peer)?.let(CallIds::handle)
        if (to == null) {
            _messages.tryEmit(CallMessage.NotStarted(Refusal.NOT_CALLABLE))
            return
        }
        scope.launch {
            // The process may have started with this very screen: its session comes up in a moment.
            if (!bridge.connected) withTimeoutOrNull(connectWaitMs) { while (!bridge.connected) delay(100) }
            if (!bridge.connected) {
                _messages.tryEmit(CallMessage.NotStarted(Refusal.NOT_CONNECTED))
                return@launch
            }
            inputs.trySend(
                CallRules.Input.Place(CallIds.newGuid(), to, name, video, from, conversationId?.take(MAX_ID_CHARS), photo, now(), phoneBusy(), settings()),
            )
        }
    }

    /** Answers the ringing call [guid]: the user's tap on Accept (with the camera) or "Audio only". */
    fun answer(guid: String, withCamera: Boolean) {
        inputs.trySend(CallRules.Input.Answer(guid, withCamera, now()))
    }

    /** Declines the ringing call [guid]. */
    fun decline(guid: String) {
        inputs.trySend(CallRules.Input.DeclineByUser(guid, now()))
    }

    /** Hangs up (call [guid]; null: whichever it is), takes a placed call back, or leaves "no answer". */
    fun hangUp(guid: String? = null) {
        inputs.trySend(CallRules.Input.HangUp(guid, now()))
    }

    fun setMuted(muted: Boolean) {
        _media.update { it.copy(muted = muted) }
        command { it.microphone(!muted) }
    }

    /**
     * Turns the camera on or off in the joined call. On needs the camera permission (the call
     * screen asks first) and a camera; in an audio call it makes the call a video call.
     */
    fun setCamera(on: Boolean) {
        val call = _state.value ?: return
        if (on && !cameraAllowed()) return
        _media.update { it.copy(cameraOn = on, cameraFailed = if (on) false else it.cameraFailed) }
        if (on) {
            command { it.orientation(orientationFor(_media.value.cameraFront)) }
            // With the call screen away the camera waits for it ([screenHidden]).
            command { if (!cameraPaused) it.camera(true) }
            // More of the foreground notice's kind now: the camera's.
            foreground.start()
            if (!call.video) inputs.trySend(CallRules.Input.BecameVideo(call.guid))
            startCamera()
        } else {
            command { it.camera(false) }
            // Turned on again, the library may go on with this encoder or make another: remembered as the one from before.
            synchronized(mediaLock) { encoderFromBefore = encoder }
            stopCamera {}
        }
    }

    /** The other camera (front or back). */
    fun flipCamera() {
        val front = !_media.value.cameraFront
        if (!camera.has(front)) return
        _media.update { it.copy(cameraFront = front) }
        if (_media.value.cameraOn) {
            stopCamera { startCamera() }
        }
        command { it.orientation(orientationFor(front)) }
    }

    /** Sends the call's sound to [route], the user's pick. */
    fun setRoute(route: Route) {
        pickedRoute = route
        applyRoute()
    }

    /** The screen was turned ([android.view.Display.getRotation]): the library is told how the camera's picture lies now. */
    fun displayRotation(value: Int) {
        rotation = value.coerceIn(0, 3)
        command { it.orientation(orientationFor(_media.value.cameraFront)) }
    }

    /** The call screen's big view has a Surface: the library is asked to draw the other side's picture into it. */
    fun attachRemote(surface: Surface) {
        surfaces.setRemote(surface)
        command { it.refreshSurfaces() }
    }

    /**
     * The call screen's big view is going: the library is told first (it asks again and gets none),
     * and only then [released] is called, which may let go of the Surface.
     */
    fun detachRemote(surface: Surface, released: () -> Unit) {
        if (surfaces.isRemote(surface)) {
            surfaces.setRemote(null)
            _media.update { it.copy(remoteVideo = false) }
        }
        scope.launch(commands) {
            joined?.native?.refreshSurfaces()
            released()
        }
    }

    /** The call screen's small view (the user's own camera) has a Surface: a running camera draws into it too from now on. */
    fun attachPreview(surface: Surface) {
        if (surfaces.preview === surface) return
        surfaces.preview = surface
        // A running camera draws into what it was started with: started again, with the view.
        if (synchronized(mediaLock) { cameraRunning }) stopCamera { startCamera() }
    }

    /**
     * The small view is going: a camera drawing into it stops first (and goes on without it), and
     * only then [released] is called, which may let go of the Surface.
     */
    fun detachPreview(surface: Surface, released: () -> Unit) {
        if (surfaces.preview !== surface) {
            released()
            return
        }
        surfaces.preview = null
        if (synchronized(mediaLock) { cameraRunning }) {
            stopCamera {
                released()
                startCamera()
            }
        } else {
            released()
        }
    }

    /**
     * The call screen [screen] is visible (its onStart): a camera that was paused because no screen
     * was ([screenHidden]) is sent again, if the user still has it on.
     */
    fun screenShown(screen: Any) {
        val resume = synchronized(mediaLock) {
            screens.add(screen)
            cameraPaused.also { cameraPaused = false }
        }
        if (resume && _media.value.cameraOn) {
            command { it.camera(true) }
            startCamera()
        }
    }

    /**
     * The call screen [screen] is no longer visible (its onStop: left for another app, or the phone
     * locked): with no call screen to be seen, the camera stops sending this phone's picture, and
     * stops running, until one is back. The call, its sound and its notice go on.
     */
    fun screenHidden(screen: Any) {
        val pause = synchronized(mediaLock) {
            screens.remove(screen)
            (screens.isEmpty() && !cameraPaused).also { if (it) cameraPaused = true }
        }
        if (pause && _media.value.cameraOn) {
            command { it.camera(false) }
            stopCamera {}
        }
    }

    /** Whether the camera may be used: the permission is there. */
    fun cameraAllowed(): Boolean = ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // --- the queue ---------------------------------------------------------------------------------

    private fun step(input: CallRules.Input) {
        val before = _state.value
        val step = try {
            rules.reduce(before, input)
        } catch (e: Throwable) {
            FaceTimeLog.note("the call rules failed on ${input::class.simpleName} (${e::class.java.simpleName})")
            return
        }
        _state.value = step.state
        try {
            stateChanged(before, step.state)
        } catch (e: Throwable) {
            // Nothing thrown here may end the queue: every call after it would be lost.
            FaceTimeLog.note("what follows a call's change could not be done (${e::class.java.simpleName})")
        }
        for (effect in step.effects) {
            try {
                perform(effect)
            } catch (e: Throwable) {
                FaceTimeLog.note("${effect::class.simpleName} could not be done (${e::class.java.simpleName})")
            }
        }
    }

    /** What follows a call coming to hold the line, or letting go of it. */
    private fun stateChanged(before: Call?, after: Call?) {
        val held = after != null && after.live && !after.incomingRing
        val wasHeld = before != null && before.live && !before.incomingRing
        if (held && (!wasHeld || before?.guid != after?.guid)) {
            // A new call: its buttons as a call begins.
            pickedRoute = null
            val routes = runCatching { audio.routes() }.getOrDefault(listOf(Route.EARPIECE, Route.SPEAKER))
            _media.value = Media(routes = routes, route = CallRoutePolicy.target(routes, after!!.video && after.withCamera, null))
            synchronized(foregroundLock) { stopOwed = false }
            _lingering.value = null
            foreground.start()
            runCatching { remember(after.guid) }
        }
        if (!held && wasHeld) {
            // A join of the library's that has not returned may still open the microphone: its notice stays until it has.
            val linger = synchronized(foregroundLock) { (joinsRunning > 0).also { stopOwed = it } }
            if (linger) _lingering.value = before else foreground.stop()
            runCatching { remember(null) }
        }
        if (after != null && before != null && after.guid == before.guid && after.phase == Phase.ACTIVE && before.phase != Phase.ACTIVE) {
            FaceTimeLog.note("${FaceTimeLog.ref(after.guid)}: in the call (${if (after.outgoing) "placed" else "answered"}, ${if (after.video) "video" else "audio"})")
        }
    }

    private fun perform(effect: CallRules.Effect) {
        when (effect) {
            is CallRules.Effect.LookupCaller -> scope.launch {
                val info = try {
                    withTimeoutOrNull(LOOKUP_MS) { main.caller(effect.handle) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                }
                inputs.trySend(CallRules.Input.Caller(effect.guid, info, now(), settings(), phoneBusy()))
            }
            is CallRules.Effect.ShowIncoming -> {
                FaceTimeLog.note("${FaceTimeLog.ref(effect.call.guid)}: ringing (${if (effect.call.video) "video" else "audio"})")
                notices.showIncoming(effect.call)
            }
            is CallRules.Effect.HideIncoming -> notices.hideIncoming(effect.guid)
            is CallRules.Effect.StartTimer -> {
                timers.remove(effect.guid)?.cancel()
                timers[effect.guid] = scope.launch {
                    sleep(effect.ms)
                    inputs.trySend(CallRules.Input.Timeout(effect.guid, effect.phase, now()))
                }
            }
            is CallRules.Effect.StopTimer -> timers.remove(effect.guid)?.cancel()
            is CallRules.Effect.CreateCall -> create(effect.call)
            is CallRules.Effect.CancelCall -> scope.launch(io) {
                try {
                    bridge.cancelFacetime(effect.guid)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: GuardBusy) {
                    FaceTimeLog.note("${FaceTimeLog.ref(effect.guid)}: not taken back: a join in the library has not returned")
                } catch (e: Throwable) {
                    // Nothing to take back (any more): as it should be.
                }
            }
            is CallRules.Effect.DeclineCall -> scope.launch(io) {
                try {
                    bridge.declineFacetime(effect.guid)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: GuardBusy) {
                    FaceTimeLog.note("${FaceTimeLog.ref(effect.guid)}: not declined: a join in the library has not returned")
                } catch (e: Throwable) {
                    // The caller hung up first: nothing to decline.
                }
            }
            is CallRules.Effect.StartNative -> startNative(effect.call)
            is CallRules.Effect.EndNative -> endNative(effect.call)
            is CallRules.Effect.Ringback -> if (effect.on) ringback.start() else ringback.stop()
            is CallRules.Effect.LogCall -> {
                val e = effect.entry
                FaceTimeLog.note(
                    "${FaceTimeLog.ref(e.guid)}: ${if (e.outgoing) "placed" else "incoming"} ${if (e.video) "video" else "audio"} call ended: ${e.outcome.wire}" +
                        if (e.outcome == Outcome.ANSWERED) " after ${e.durationMs / 1000} s" else "",
                )
                scope.launch { main.log(e) }
            }
            is CallRules.Effect.Refused -> _messages.tryEmit(CallMessage.NotStarted(effect.why))
            is CallRules.Effect.Note -> FaceTimeLog.note(effect.text)
            is CallRules.Effect.ShowWaiting -> {
                FaceTimeLog.note("${FaceTimeLog.ref(effect.call.guid)}: waiting during another call (${if (effect.call.video) "video" else "audio"})")
                _waiting.value = effect.call
                notices.showIncoming(effect.call)
            }
            is CallRules.Effect.HideWaiting -> {
                if (_waiting.value?.guid == effect.guid) _waiting.value = null
                notices.hideIncoming(effect.guid)
            }
        }
    }

    // --- placing -----------------------------------------------------------------------------------

    private fun create(call: Call) {
        scope.launch(io) {
            val ok = try {
                val handles = bridge.handles()
                val from = call.from?.takeIf { it in handles } ?: Handles.preferredSender(handles)
                    ?: throw IllegalStateException("no registered address")
                bridge.setLocalInterfaces()
                bridge.createFacetime(call.guid, from, listOf(call.peer), call.video)
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                FaceTimeLog.note("${FaceTimeLog.ref(call.guid)}: could not be placed (${e::class.java.simpleName})")
                false
            }
            inputs.trySend(CallRules.Input.Placed(call.guid, ok, now()))
        }
    }

    // --- the joined call ---------------------------------------------------------------------------

    private fun startNative(call: Call) {
        val native = NativeCall(call.guid, natives.create(host(call.guid)), bridge)
        // The view is free for this call's picture (the last call's stream may still be down as its owner).
        surfaces.newCall()
        _media.update { it.copy(remoteVideo = false, remoteWidth = 0, remoteHeight = 0, remoteRotation = Media.ROTATION_UNKNOWN) }
        joined = Joined(call.guid, native)
        scope.launch(io) {
            // Its notice is up before the microphone opens (the library opens it in the join).
            foreground.awaitStarted(FOREGROUND_WAIT_MS)
            synchronized(foregroundLock) { joinsRunning++ }
            val result = try {
                native.start()
            } finally {
                joinReturned()
            }
            when (result) {
                is NativeCall.Start.Up -> {
                    joinedUp(call, native, result.video)
                    inputs.trySend(CallRules.Input.NativeUp(call.guid, result.video, now()))
                }
                NativeCall.Start.Gone, NativeCall.Start.Failed -> {
                    if (joined?.native === native) joined = null
                    FaceTimeLog.note("${FaceTimeLog.ref(call.guid)}: not joined (${if (result == NativeCall.Start.Gone) "the call is gone" else "the join failed"})")
                    inputs.trySend(CallRules.Input.NativeFailed(call.guid, now()))
                }
                is NativeCall.Start.Ended -> if (!result.wasJoined) undo(call)
            }
        }
    }

    /** A join of the library's returned (however): a notice kept up for it alone goes now. */
    private fun joinReturned() {
        val stop = synchronized(foregroundLock) {
            joinsRunning--
            (joinsRunning <= 0 && stopOwed).also { if (it) stopOwed = false }
        }
        if (stop) {
            _lingering.value = null
            runCatching { foreground.stop() }
        }
    }

    /** As OpenBubbles 2.0.0 does once the join returned: the orientation, the camera's state, the microphone's; then the phone's audio. */
    private fun joinedUp(call: Call, native: NativeCall, video: Boolean) {
        val cameraOn = video && call.withCamera && cameraAllowed() && (camera.has(true) || camera.has(false))
        val front = if (camera.has(true)) true else !camera.has(false)
        _media.update { it.copy(cameraOn = cameraOn, cameraFront = front) }
        native.orientation(orientationFor(front))
        native.camera(cameraOn && !cameraPaused)
        native.microphone(!_media.value.muted)
        try {
            audio.enter { applyRoute() }
        } catch (e: Throwable) {
            FaceTimeLog.note("the phone's audio could not be taken for the call (${e::class.java.simpleName})")
        }
        applyRoute(video && cameraOn)
        if (cameraOn) startCamera()
        // Hung up while all this was set up: given back again.
        if (joined?.native !== native || !native.isUp) releaseMedia()
    }

    private fun endNative(call: Call) {
        val was = joined?.takeIf { it.guid == call.guid }
        if (was != null) joined = null
        when (was?.native?.hangUp() ?: NativeCall.Leave.NEVER_JOINED) {
            NativeCall.Leave.DESTROYED, NativeCall.Leave.JOINING -> Unit
            NativeCall.Leave.NEVER_JOINED -> undo(call)
        }
        releaseMedia()
    }

    /** A call that never joined in the library: a placed one is taken back, an answered one declined. */
    private fun undo(call: Call) {
        perform(if (call.outgoing) CallRules.Effect.CancelCall(call.guid) else CallRules.Effect.DeclineCall(call.guid))
    }

    /** Runs [block] with the joined call's library side, on the commands' thread; nothing when no call is joined. */
    private fun command(block: (NativeCall) -> Unit) {
        scope.launch(commands) { joined?.native?.let(block) }
    }

    /** The library's callbacks for call [guid], on its own threads: they read, queue, and return. */
    private fun host(guid: String) = object : FaceTimeInCallService.Host {
        private fun current(): Boolean = joined?.guid == guid

        override fun buildSurface(participant: Long, stream: Int): Surface? {
            if (!current()) return null
            val surface = surfaces.forStream(participant, stream)
            if (surface != null) {
                // With what the library said of this stream so far, also before it asked for the view.
                val size = surfaces.sizeOf(participant, stream)
                val rotation = surfaces.rotationOf(participant, stream) ?: Media.ROTATION_UNKNOWN
                _media.update {
                    it.copy(remoteVideo = true, remoteWidth = size?.first ?: 0, remoteHeight = size?.second ?: 0, remoteRotation = rotation)
                }
            }
            return surface
        }

        override fun streamsRemoved(participant: Long, streams: IntArray) {
            if (!current()) return
            if (surfaces.removed(participant, streams)) {
                _media.update { it.copy(remoteVideo = false, remoteWidth = 0, remoteHeight = 0, remoteRotation = Media.ROTATION_UNKNOWN) }
            }
            command { it.refreshSurfaces() }
        }

        override fun videoSize(participant: Long, stream: Int, width: Int, height: Int) {
            if (!current() || width <= 0 || height <= 0 || width > MAX_SIDE || height > MAX_SIDE) return
            // Kept for every stream: the library says it once, and may say it before it asks for the view.
            if (surfaces.noteSize(participant, stream, width, height)) _media.update { it.copy(remoteWidth = width, remoteHeight = height) }
        }

        override fun streamRotation(participant: Long, stream: Int, rotation: Int) {
            if (!current() || rotation !in 0..3) return
            if (surfaces.noteRotation(participant, stream, rotation)) _media.update { it.copy(remoteRotation = rotation) }
        }

        override fun participants(ids: LongArray, handles: Array<String?>) {
            if (current()) inputs.trySend(CallRules.Input.Participants(guid, ids.size, now()))
        }

        override fun encoderSurface(surface: Surface, group: Int) {
            // A shared screen's or a second camera's encoder: neither is used here.
            if (group == CallSurfaces.GROUP_SCREEN || group == CallSurfaces.GROUP_SECOND_CAMERA || !current()) {
                surface.release()
                return
            }
            newEncoder(surface)
        }

        override fun systemAudio(sampleRate: Int): android.media.AudioRecord? = null

        override fun failed() {
            inputs.trySend(CallRules.Input.Failed(guid, now()))
        }
    }

    // --- camera and audio --------------------------------------------------------------------------

    /** The library made (another) encoder for the camera's picture: the camera draws into it when it is on. */
    private fun newEncoder(surface: Surface) {
        val old = synchronized(mediaLock) {
            encoderFromBefore = null
            encoder.also { encoder = surface }
        }
        if (old != null && old !== surface) {
            // Whatever drew into the old one stops first; only then it is let go.
            stopCamera {
                old.release()
                startCamera()
            }
        } else {
            startCamera()
        }
    }

    /** Counts the camera's starts: what an earlier one says after a later one began is not about the camera any more. */
    private var cameraStarts = 0

    /** Starts the camera if it is to be on and there is an encoder to draw into. Never before the call is joined. */
    private fun startCamera() {
        val m = _media.value
        val (target, mine) = synchronized(mediaLock) {
            if (!m.cameraOn || cameraPaused || cameraRunning || joined?.native?.isUp != true) return
            val e = encoder?.takeIf { it.isValid } ?: return
            cameraRunning = true
            e to ++cameraStarts
        }
        if (!cameraAllowed()) {
            synchronized(mediaLock) { cameraRunning = false }
            return
        }
        camera.start(target, surfaces.preview?.takeIf { it.isValid }, m.cameraFront) { ok, front ->
            // An earlier start's word, come after the camera was started again (another encoder from the
            // library, the camera turned off and on): it must not turn the picture being sent off.
            val current = synchronized(mediaLock) { mine == cameraStarts && encoder === target }
            if (!current) return@start
            if (ok) {
                if (front != _media.value.cameraFront) {
                    _media.update { it.copy(cameraFront = front) }
                    command { it.orientation(orientationFor(front)) }
                }
            } else if (staleEncoder(target)) {
                // The library's encoder from before the camera was turned off takes no pictures any more:
                // it makes another when the camera is turned on again, and the camera starts with that one.
                FaceTimeLog.note("the camera did not start with the encoder from before; waiting for the library's new one")
            } else {
                // No camera that gives what the encoder takes: sound only, and said so.
                synchronized(mediaLock) { cameraRunning = false }
                _media.update { it.copy(cameraOn = false, cameraFailed = true) }
                command { it.camera(false) }
                _messages.tryEmit(CallMessage.CameraUnavailable)
            }
        }
    }

    /** The encoder the camera was turned off with ([setCamera]): one that may be dead, while the library's next is waited for. */
    private var encoderFromBefore: Surface? = null

    /**
     * The camera could not start with [target], the encoder it had before it was turned off: that
     * one is given up (the library makes a new one for a camera turned on again), and the camera
     * waits [ENCODER_WAIT_MS] for the next. True when it waits; false when [target] is no such
     * encoder (then the camera really cannot be used). If none comes, the camera is said to be
     * unavailable then.
     */
    private fun staleEncoder(target: Surface): Boolean {
        synchronized(mediaLock) {
            if (encoderFromBefore !== target) return false
            encoderFromBefore = null
            cameraRunning = false
            if (encoder === target) encoder = null
        }
        scope.launch {
            kotlinx.coroutines.delay(ENCODER_WAIT_MS)
            val waiting = synchronized(mediaLock) { encoder == null && !cameraRunning } && _media.value.cameraOn && joined?.native?.isUp == true
            if (waiting) {
                _media.update { it.copy(cameraOn = false, cameraFailed = true) }
                command { it.camera(false) }
                _messages.tryEmit(CallMessage.CameraUnavailable)
            }
        }
        return true
    }

    private fun stopCamera(then: () -> Unit) {
        val running = synchronized(mediaLock) { cameraRunning.also { cameraRunning = false } }
        if (running) camera.stop(then) else then()
    }

    /** The call is over: the camera stops, then the encoder's Surface is let go; the phone's audio is given back. */
    private fun releaseMedia() {
        val old = synchronized(mediaLock) { encoder.also { encoder = null } }
        stopCamera { old?.release() }
        ringback.stop()
        try {
            audio.leave()
        } catch (e: Throwable) {
            FaceTimeLog.note("the phone's audio could not be given back (${e::class.java.simpleName})")
        }
        _media.update { it.copy(cameraOn = false, remoteVideo = false) }
    }

    private fun applyRoute(video: Boolean = _state.value?.video == true && _media.value.cameraOn) {
        try {
            val routes = audio.routes()
            val target = CallRoutePolicy.target(routes, video, pickedRoute)
            if (joined?.native?.isUp == true) audio.route(target)
            _media.update { it.copy(routes = routes, route = target) }
        } catch (e: Throwable) {
            FaceTimeLog.note("the call's audio route could not be set (${e::class.java.simpleName})")
        }
    }

    companion object {
        /** A camera turned on again waits this long for the library's new encoder before it is said to be unavailable. */
        const val ENCODER_WAIT_MS = 4_000L

        /** The main process is given this long to say who is calling; then the call rings (or, with unknown callers silenced, does not). */
        const val LOOKUP_MS = 2_500L

        /** The library's own answers (its call list, the user's addresses) are waited for this long. */
        private const val LIBRARY_MS = 3_000L

        /** An event handed over as the process starts waits this long for the session. */
        private const val SESSION_WAIT_MS = 15_000L

        /** A call to place waits this long for the session (a process that just started restores it first). */
        const val CONNECT_WAIT_MS = 8_000L

        /** An event that could not be taken is said in the log at most this often. */
        private const val UNTAKEN_NOTE_MS = 60_000L

        /** The in-call notice is given this long to come up before the call is joined all the same. */
        private const val FOREGROUND_WAIT_MS = 3_000L

        /** The largest picture side taken from the library's word on a stream. */
        private const val MAX_SIDE = 8_192

        private const val MAX_ID_CHARS = 256

        /** The most of Apple's events that wait to be taken. */
        const val MAX_EVENT_BACKLOG = 256

        @Volatile
        private var instance: FaceTimeCalls? = null

        /** The ":imessage" process's one. */
        fun get(context: Context): FaceTimeCalls =
            instance ?: synchronized(this) {
                instance ?: FaceTimeParts.real(context.applicationContext).also { instance = it }
            }

        /** The one there is, if any was made: never makes one (nor loads anything). */
        fun peek(): FaceTimeCalls? = instance

        internal fun installForTesting(calls: FaceTimeCalls?) = synchronized(this) {
            instance = calls
        }
    }
}
