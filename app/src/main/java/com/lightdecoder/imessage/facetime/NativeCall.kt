package com.lightdecoder.imessage.facetime

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One call's joined session in the library, and the only place the library's call functions are
 * called from. What makes them safe is here, as the library takes its process down on four inputs:
 *
 * - **A call that is not in its list**: [start] looks the call up right before it joins, under the
 *   bridge's [CallBridge.guard] (so nothing of the app's takes it out in between), and joins nothing
 *   that is not there.
 * - **A session handle used twice, or of a session that is gone**: the handle is made right before
 *   the join and handed to it once ([CallBridge.withNativeHandle]); [start] runs once per object.
 * - **An orientation outside 0..3**: [orientation] clamps.
 * - **No network addresses**: [start] tells them first ([CallBridge.setLocalInterfaces]).
 *
 * Every function of the library is called in a try that catches everything: what is thrown ends in
 * a failed start or a skipped command, never above. The joined call ([avc]) is zeroed under [lock]
 * before it is destroyed, and read under it by every other call, so nothing uses it after. A
 * hang-up while the join still runs is remembered ([wanted]): the join's result is destroyed as it
 * returns. Nothing here runs on the main thread ([FaceTimeCalls] sees to that). The library's
 * callbacks never take [lock] (a command of the library's runs under it, and may wait for one of
 * them): all they may ask here is [isUp], which takes no lock.
 */
class NativeCall(
    val guid: String,
    private val native: CallNative,
    private val bridge: CallBridge,
) {
    private val lock = Any()
    private var wanted = true
    private var started = false
    private var joining = false

    /** Written under [lock] only; [isUp] reads it without. */
    @Volatile
    private var avc = 0L

    /** How a join ended. */
    sealed class Start {
        /** Joined: [video] is what the library says the call is. */
        data class Up(val video: Boolean) : Start()

        /** The call is not in the library's list (any more): nothing was joined. */
        object Gone : Start()

        /** The join failed, or threw. */
        object Failed : Start()

        /**
         * Hung up before or while it joined: nothing is left joined. [wasJoined]: the join had gone
         * through and was left again at once (else the call never joined, and is the caller's to take
         * back or decline).
         */
        data class Ended(val wasJoined: Boolean) : Start()
    }

    /** What a hang-up found. */
    enum class Leave {
        /** It was joined: destroyed, which leaves the call. */
        DESTROYED,

        /** The join is still running: its result is destroyed when it returns. */
        JOINING,

        /** It never joined: a placed call is to be taken back, an answered one declined. */
        NEVER_JOINED,
    }

    /**
     * Joins the call: blocks until the library has (its relay connection and the join), so only off
     * the main thread, and never on one of the library's own. Runs to its end whatever is cancelled.
     */
    suspend fun start(): Start = withContext(NonCancellable) {
        synchronized(lock) {
            if (started) return@withContext Start.Failed
            started = true
        }
        try {
            bridge.guard.withLock {
                bridge.setLocalInterfaces()
                // Right before the join, with nothing of the app's able to take the call out in between.
                if (bridge.sessions().none { it.guid == guid }) return@withContext Start.Gone
                val go = synchronized(lock) { wanted.also { joining = it } }
                if (!go) return@withContext Start.Ended(wasJoined = false)
                val made = try {
                    bridge.withNativeHandle { handle -> native.createAvc(guid, handle) } ?: 0L
                } catch (e: Throwable) {
                    FaceTimeLog.note("${FaceTimeLog.ref(guid)}: the join threw (${e::class.java.simpleName})")
                    0L
                }
                val mine = synchronized(lock) {
                    joining = false
                    (made != 0L && wanted && avc == 0L).also { if (it) avc = made }
                }
                when {
                    mine -> Start.Up(safely(true) { native.isVideo })
                    // Hung up while it joined (or a join that came back twice): what it made is left at once.
                    made != 0L -> {
                        safely(Unit) { native.destroyAvc(made) }
                        Start.Ended(wasJoined = true)
                    }
                    synchronized(lock) { !wanted } -> Start.Ended(wasJoined = false)
                    else -> Start.Failed
                }
            }
        } catch (e: Throwable) {
            FaceTimeLog.note("${FaceTimeLog.ref(guid)}: could not be joined (${e::class.java.simpleName})")
            synchronized(lock) { joining = false }
            Start.Failed
        }
    }

    /** Leaves the call, whatever state the join is in. Safe to call again. */
    fun hangUp(): Leave {
        val (joined, wasJoining) = synchronized(lock) {
            wanted = false
            Pair(avc.also { avc = 0L }, joining)
        }
        return when {
            joined != 0L -> {
                safely(Unit) { native.destroyAvc(joined) }
                Leave.DESTROYED
            }
            wasJoining -> Leave.JOINING
            else -> Leave.NEVER_JOINED
        }
    }

    /**
     * Whether the call is joined now. Takes no lock: it is asked from the library's callbacks too,
     * which must never wait for a command of the library's that runs under [lock].
     */
    val isUp: Boolean get() = avc != 0L

    fun microphone(on: Boolean) = withAvc { native.enableMicrophone(it, on) }

    /** Sends the camera's stream, or stops; turning it on in an audio call makes it a video call. */
    fun camera(on: Boolean) = withAvc { native.enableCamera(it, on, CAPTURE_MODE_UNCHANGED) }

    /** The camera's orientation for the library: only ever 0..3 ([CallOrientation]). */
    fun orientation(value: Int) = withAvc { native.updateOrientation(it, CallOrientation.clamp(value)) }

    fun refreshSurfaces() = withAvc { native.refreshSurfaces(it) }

    /** Runs [block] with the joined call, if it is joined; under [lock], so it is never destroyed meanwhile. */
    private inline fun withAvc(block: (Long) -> Unit): Boolean = synchronized(lock) {
        val joined = avc
        if (joined == 0L) return@synchronized false
        safely(Unit) { block(joined) }
        true
    }

    private inline fun <T> safely(otherwise: T, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        FaceTimeLog.note("${FaceTimeLog.ref(guid)}: a call function of the library threw (${e::class.java.simpleName})")
        otherwise
    }

    companion object {
        /** `enableCamera`'s capture mode that leaves it as it is (one camera). */
        const val CAPTURE_MODE_UNCHANGED = -1
    }
}

/**
 * The camera's orientation as the library takes it (0..3), from the screen's rotation, as
 * OpenBubbles 2.0.0 tells it: any other value takes the library's process down, so [clamp] is
 * between every value and the library.
 */
object CallOrientation {
    /** [android.view.Surface.ROTATION_0] .. ROTATION_270 are 0..3. */
    fun forCamera(displayRotation: Int, front: Boolean): Int = when (displayRotation.coerceIn(0, 3)) {
        0 -> if (front) 1 else 0
        1 -> 3
        2 -> if (front) 0 else 1
        else -> 2
    }

    fun clamp(value: Int): Int = value.coerceIn(0, 3)
}
