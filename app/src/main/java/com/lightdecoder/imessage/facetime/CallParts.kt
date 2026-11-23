package com.lightdecoder.imessage.facetime

import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
    /** The other side's picture, as its decoder makes it, and its rotation (the library's 0..3; 0 and 1 are quarter turns). */
    val remoteWidth: Int = 0,
    val remoteHeight: Int = 0,
    val remoteRotation: Int = ROTATION_NONE,
    /** Someone's shared screen is being drawn into the call screen's view for it, and its picture as its decoder makes it. */
    val screenVideo: Boolean = false,
    val screen: Picture = Picture(),
    /** A group call's tiles whose pictures are being drawn, by participant id: each one's picture as its decoder makes it. */
    val tiles: Map<Long, Picture> = emptyMap(),
) {
    /** The picture's width and height as shown: turned a quarter, they change places. */
    val remoteShown: Pair<Int, Int>
        get() = Picture(remoteWidth, remoteHeight, remoteRotation).shown

    /** Whether any picture of the other side (a camera's, a shared screen) is shown. */
    val anyPicture: Boolean get() = remoteVideo || screenVideo || tiles.isNotEmpty()

    /** One picture's size as its decoder makes it, and its rotation (the library's 0..3; 0 and 1 are quarter turns). */
    data class Picture(val width: Int = 0, val height: Int = 0, val rotation: Int = ROTATION_NONE) {
        /** Its width and height as shown: turned a quarter, they change places. */
        val shown: Pair<Int, Int> get() = if (rotation == 0 || rotation == 1) height to width else width to height
    }

    companion object {
        /** The library's value for "not turned". */
        const val ROTATION_NONE = 3
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
 * The views the library draws the other side's pictures into. A call with one person has one view
 * for their camera: the first camera stream that asks gets it, and keeps it until the library says
 * that stream is gone ([removed]); any other camera stream gets none. Someone's shared screen gets a
 * view of its own (one at a time). A group call ([tilesOnly]) draws each person's camera into a tile
 * of their own, which the call screen makes for those the library asked for ([requested]), at most
 * [TileLayout.MAX_CAMERA_TILES] at once; the rest get none (the library then drops their pictures,
 * and makes no decoder for them). Read on the library's threads ([forStream]), set on the main
 * thread: everything under one lock, and nothing waits.
 */
class CallSurfaces {
    private val lock = Any()
    private var remote: Surface? = null
    private var owner: Pair<Long, Int>? = null
    private var screen: Surface? = null
    private var screenOwner: Pair<Long, Int>? = null
    private val tiles = HashMap<Long, Surface>()
    private val tileOwners = HashMap<Long, Int>()

    /** The call screen's small view of the user's own camera, while it is there. */
    @Volatile
    var preview: Surface? = null

    /** A group call: cameras go into tiles only, never into the one big view. */
    @Volatile
    var tilesOnly: Boolean = false

    private val _requested = MutableStateFlow<List<Pair<Long, Int>>>(emptyList())

    /** The streams the library asked for a view for (participant, stream), the newest last, [MAX_REQUESTED] at most. */
    val requested: StateFlow<List<Pair<Long, Int>>> = _requested

    private fun request(key: Pair<Long, Int>) {
        if (key in _requested.value) return
        _requested.value = (_requested.value + key).takeLast(MAX_REQUESTED)
    }

    /** The Surface for [stream] of [participant], or null: no view (yet), or not a stream that is shown. */
    fun forStream(participant: Long, stream: Int): Surface? = synchronized(lock) {
        val key = participant to stream
        when (stream) {
            STREAM_SCREEN -> {
                request(key)
                val surface = screen?.takeIf { it.isValid } ?: return@synchronized null
                if (screenOwner == null || screenOwner == key) {
                    screenOwner = key
                    surface
                } else {
                    null
                }
            }
            STREAM_CAMERA, STREAM_CAMERA_ALTERNATE -> {
                if (tilesOnly) {
                    request(key)
                    val surface = tiles[participant]?.takeIf { it.isValid } ?: return@synchronized null
                    val drawing = tileOwners[participant]
                    when {
                        drawing == stream -> surface
                        drawing != null -> null
                        tileOwners.size >= TileLayout.MAX_CAMERA_TILES -> null
                        else -> {
                            tileOwners[participant] = stream
                            surface
                        }
                    }
                } else {
                    val surface = remote?.takeIf { it.isValid } ?: return@synchronized null
                    if (owner == null || owner == key) {
                        owner = key
                        surface
                    } else {
                        null
                    }
                }
            }
            else -> null
        }
    }

    /** [streams] of [participant] are gone. True when the shown one was among them (the view is free again). */
    fun removed(participant: Long, streams: IntArray): Boolean = synchronized(lock) {
        _requested.value = _requested.value.filterNot { it.first == participant && it.second in streams }
        screenOwner?.let { if (it.first == participant && it.second in streams) screenOwner = null }
        tileOwners[participant]?.let { if (it in streams) tileOwners.remove(participant) }
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

    /** Whether [stream] of [participant] is drawn into the shared screen's view, or into [participant]'s tile. */
    fun showsScreen(participant: Long, stream: Int): Boolean = synchronized(lock) { screenOwner == participant to stream }
    fun showsTile(participant: Long, stream: Int): Boolean = synchronized(lock) { tileOwners[participant] == stream }

    /** Whether a stream is being drawn into the view. */
    val showing: Boolean get() = synchronized(lock) { owner != null }

    /** The call screen's big view came ([surface]) or is going (null): the library must ask again after either. */
    fun setRemote(surface: Surface?) = synchronized(lock) {
        remote = surface
        owner = null
    }

    /** Whether [surface] is the view the library may still be drawing into. */
    fun isRemote(surface: Surface): Boolean = synchronized(lock) { remote === surface }

    /** The shared screen's view came ([surface]) or is going (null): the library must ask again after either. */
    fun setScreen(surface: Surface?) = synchronized(lock) {
        screen = surface
        screenOwner = null
    }

    fun isScreen(surface: Surface): Boolean = synchronized(lock) { screen === surface }

    /** [participant]'s tile came ([surface]) or is going (null). */
    fun setTile(participant: Long, surface: Surface?) = synchronized(lock) {
        tileOwners.remove(participant)
        if (surface == null) tiles.remove(participant) else tiles[participant] = surface
    }

    fun isTile(participant: Long, surface: Surface): Boolean = synchronized(lock) { tiles[participant] === surface }

    /** Whether [participant]'s tile has given its Surface (valid or not). */
    fun hasTile(participant: Long): Boolean = synchronized(lock) { participant in tiles }

    /**
     * A new call holds the line: nothing of the one before is asked for or drawn any more, and a
     * [group] call's cameras go into tiles ([tilesOnly]). The views themselves stay (the screen's).
     */
    fun newCall(group: Boolean) = synchronized(lock) {
        owner = null
        screenOwner = null
        tileOwners.clear()
        tilesOnly = group
        _requested.value = emptyList()
    }

    fun clear() = synchronized(lock) {
        remote = null
        owner = null
        screen = null
        screenOwner = null
        tiles.clear()
        tileOwners.clear()
        preview = null
        tilesOnly = false
        _requested.value = emptyList()
    }

    companion object {
        /** The library's stream indexes of a camera's picture (OpenBubbles' `StreamKey`): the first camera, and its alternate. */
        const val STREAM_CAMERA = 1
        const val STREAM_CAMERA_ALTERNATE = 5

        /** A participant's shared screen. (17, a second camera, is never shown.) */
        const val STREAM_SCREEN = 3

        /** The most streams remembered as asked for. */
        const val MAX_REQUESTED = 64

        /** The encoder groups that are not the first camera's: a shared screen, and a second camera. */
        const val GROUP_SCREEN = 3
        const val GROUP_SECOND_CAMERA = 17
    }
}
