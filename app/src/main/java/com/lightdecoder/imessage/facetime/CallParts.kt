package com.lightdecoder.imessage.facetime

import android.view.Surface

/** Where a call's sound goes. */
enum class Route { EARPIECE, SPEAKER, WIRED, BLUETOOTH }

/**
 * Which route a call takes, as OpenBubbles 2.0.0 chooses: the user's pick while it is there; else a
 * Bluetooth headset, else a wired one; else the speaker for a video call and the earpiece for an
 * audio call (the speaker on a device without an earpiece). Pure.
 */
object CallRoutePolicy {
    fun target(available: Collection<Route>, video: Boolean, picked: Route?): Route = when {
        picked != null && picked in available -> picked
        Route.BLUETOOTH in available -> Route.BLUETOOTH
        Route.WIRED in available -> Route.WIRED
        video && Route.SPEAKER in available -> Route.SPEAKER
        Route.EARPIECE in available -> Route.EARPIECE
        Route.SPEAKER in available -> Route.SPEAKER
        else -> available.firstOrNull() ?: Route.SPEAKER
    }
}

/** What the call screen shows of the joined call, and the state of its buttons. */
data class Media(
    val muted: Boolean = false,
    /** The camera is on: this phone's picture is being sent. */
    val cameraOn: Boolean = false,
    val cameraFront: Boolean = true,
    /** The camera could not be used for the call (none, or none that gives what the library's encoder takes). */
    val cameraFailed: Boolean = false,
    val route: Route = Route.EARPIECE,
    val routes: List<Route> = listOf(Route.EARPIECE, Route.SPEAKER),
    /** The other side's camera picture is being drawn into the call screen's view. */
    val remoteVideo: Boolean = false,
    /**
     * The other side's picture, as its decoder makes it (0 until the library says), and its rotation:
     * the library's 0..3 (0 and 1 are quarter turns), or [ROTATION_UNKNOWN] while the library has
     * not said one for the stream shown.
     */
    val remoteWidth: Int = 0,
    val remoteHeight: Int = 0,
    val remoteRotation: Int = ROTATION_UNKNOWN,
) {
    /**
     * The picture's width and height as shown: turned a quarter, they change places. A rotation the
     * library has not said counts as a quarter turn (an iPhone held upright), as OpenBubbles takes it
     * (its `VideoRotation.DEFAULT`): the library does not say the rotation a stream starts out with.
     */
    val remoteShown: Pair<Int, Int>
        get() = if (quarterTurn(remoteRotation)) remoteHeight to remoteWidth else remoteWidth to remoteHeight

    companion object {
        /** The library's value for "not turned". */
        const val ROTATION_NONE = 3

        /** No rotation said by the library yet (never one of its own values). */
        const val ROTATION_UNKNOWN = -1

        /** Whether a picture with [rotation] is shown with its sides changed over ([ROTATION_UNKNOWN]: yes, see [remoteShown]). */
        fun quarterTurn(rotation: Int): Boolean = rotation == 0 || rotation == 1 || rotation == ROTATION_UNKNOWN
    }
}

/** Something to tell the user once (a toast on the call screen). */
sealed class CallMessage {
    data class NotStarted(val why: Refusal) : CallMessage()

    /** The camera could not be started: the call goes on with sound only. */
    object CameraUnavailable : CallMessage()
}

/** The main process, as the call engine needs it: who a caller is, and where a call's row goes. */
interface MainLink {
    /** Who [handle] is; null when the main process could not be asked. */
    suspend fun caller(handle: String): CallerInfo?

    /** Stores the call's row in its chat (and shows a missed call's notice). Returns once handed over, or given up on. */
    suspend fun log(entry: CallLogEntry)
}

/** The incoming-call notice (ringing), posted and taken down. */
interface CallNotices {
    fun showIncoming(call: Call)
    fun hideIncoming(guid: String)
}

/** The in-call foreground service: what keeps the call (its microphone and camera) up with the screen away. */
interface CallForeground {
    /** Starts it; only ever while the call screen is in front. */
    fun start()
    fun stop()

    /** Returns once it is in the foreground (its notice is up), or after [timeoutMs]. */
    suspend fun awaitStarted(timeoutMs: Long)
}

/** The phone's audio for a call: the communication mode and where the sound goes. */
interface CallAudio {
    /** The routes there are now. */
    fun routes(): List<Route>

    /** Takes the phone's audio for a call (the communication mode); [changed] is called when routes come or go. */
    fun enter(changed: () -> Unit)

    /** Sends the call's sound to [route]. False when it could not. */
    fun route(route: Route): Boolean

    /** Gives the phone's audio back as it was. */
    fun leave()
}

/** The camera, drawing into the library's encoder and the call screen's small view. */
interface CallCamera {
    /** Whether there is a camera on that side that can feed the encoder. */
    fun has(front: Boolean): Boolean

    /** How that side's camera is mounted (0, 90, 180, 270 degrees); null: not known (a usual phone's is assumed). */
    fun sensorOrientation(front: Boolean): Int? = null

    /**
     * Starts the camera on [front] (or the other side when there is none), drawing into [encoder]
     * and, when there is one, [preview]. [started] says whether it runs, and with which side.
     */
    fun start(encoder: Surface, preview: Surface?, front: Boolean, started: (ok: Boolean, front: Boolean) -> Unit)

    /** Stops it; [stopped] is called once nothing of it draws into the surfaces any more (or after a few seconds). */
    fun stop(stopped: () -> Unit)
}

/** The ringback tone a caller hears while the other side rings. */
interface CallRingback {
    fun start()
    fun stop()
}

/**
 * The views the library draws the other side's picture into. One call with one person has one
 * view: the first camera stream that asks gets it, and keeps it until the library says that stream
 * is gone ([removed]); any other stream gets none. Read on the library's threads ([forStream]),
 * set on the main thread: everything under one lock, and nothing waits.
 */
class CallSurfaces {
    private val lock = Any()
    private var remote: Surface? = null
    private var owner: Pair<Long, Int>? = null

    /**
     * What the library said of each stream's picture, kept whether or not that stream is the one
     * shown (as OpenBubbles keeps them, by stream): the library says a size or a rotation once, when
     * it changes, and may say it before it asks for the stream's view ([forStream]), or while the
     * view is away. One said then must not be lost: the picture would be laid out in another shape
     * than it has (squeezed or pulled wide) until the other side turned their phone.
     */
    private val sizes = HashMap<Pair<Long, Int>, Pair<Int, Int>>()
    private val rotations = HashMap<Pair<Long, Int>, Int>()

    /** [stream] of [participant] makes pictures [width] x [height] from now on. True when it is the stream shown. */
    fun noteSize(participant: Long, stream: Int, width: Int, height: Int): Boolean = synchronized(lock) {
        val key = participant to stream
        if (key in sizes || sizes.size < MAX_STREAMS) sizes[key] = width to height
        owner == key
    }

    /** [stream] of [participant] is turned [rotation] (the library's 0..3) from now on. True when it is the stream shown. */
    fun noteRotation(participant: Long, stream: Int, rotation: Int): Boolean = synchronized(lock) {
        val key = participant to stream
        if (key in rotations || rotations.size < MAX_STREAMS) rotations[key] = rotation
        owner == key
    }

    /** The picture size the library last said for [stream] of [participant], or null. */
    fun sizeOf(participant: Long, stream: Int): Pair<Int, Int>? = synchronized(lock) { sizes[participant to stream] }

    /** The rotation the library last said for [stream] of [participant], or null when it said none. */
    fun rotationOf(participant: Long, stream: Int): Int? = synchronized(lock) { rotations[participant to stream] }

    /** The call screen's small view of the user's own camera, while it is there. */
    @Volatile
    var preview: Surface? = null

    /** The Surface for [stream] of [participant], or null: no view (yet), or not a stream that is shown. */
    fun forStream(participant: Long, stream: Int): Surface? = synchronized(lock) {
        if (stream != STREAM_CAMERA && stream != STREAM_CAMERA_ALTERNATE) return@synchronized null
        val surface = remote?.takeIf { it.isValid } ?: return@synchronized null
        val key = participant to stream
        if (owner == null || owner == key) {
            owner = key
            surface
        } else {
            null
        }
    }

    /** [streams] of [participant] are gone. True when the shown one was among them (the view is free again). */
    fun removed(participant: Long, streams: IntArray): Boolean = synchronized(lock) {
        val o = owner ?: return@synchronized false
        if (o.first == participant && o.second in streams) {
            owner = null
            true
        } else {
            false
        }
    }

    /** Whether [stream] of [participant] is the one drawn into the view. */
    fun shows(participant: Long, stream: Int): Boolean = synchronized(lock) { owner == participant to stream }

    /** Whether a stream is being drawn into the view. */
    val showing: Boolean get() = synchronized(lock) { owner != null }

    /** The call screen's big view came ([surface]) or is going (null): the library must ask again after either. */
    fun setRemote(surface: Surface?) = synchronized(lock) {
        remote = surface
        owner = null
    }

    /** Whether [surface] is the view the library may still be drawing into. */
    fun isRemote(surface: Surface): Boolean = synchronized(lock) { remote === surface }

    /** Another call is joined: no stream of the last one has the view any more, and nothing said of them is kept. */
    fun newCall() = synchronized(lock) {
        owner = null
        sizes.clear()
        rotations.clear()
    }

    fun clear() = synchronized(lock) {
        remote = null
        owner = null
        preview = null
        sizes.clear()
        rotations.clear()
    }

    companion object {
        /** The library's stream indexes of a camera's picture (OpenBubbles' `StreamKey`): the first camera, and its alternate. */
        const val STREAM_CAMERA = 1
        const val STREAM_CAMERA_ALTERNATE = 5

        /** The most streams a size and a rotation are kept for (a call with one person has a handful). */
        const val MAX_STREAMS = 64

        /** The encoder groups that are not the first camera's: a shared screen, and a second camera. */
        const val GROUP_SCREEN = 3
        const val GROUP_SECOND_CAMERA = 17
    }
}
