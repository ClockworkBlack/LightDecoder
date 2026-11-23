package com.lightdecoder.imessage.facetime

import com.localob.messaging.app.facetime.FaceTimeInCallService

/**
 * The library's side of a joined call (its JNI functions, on [FaceTimeInCallService]), as the call
 * code uses it: only what a one-to-one call needs. Tests put a fake here; the library itself cannot
 * run in them. Every one of these may throw (also an Error, when the library's functions are not
 * there): [NativeCall] is the only caller, and catches all of it.
 */
interface CallNative {
    /** What the library says the call is, written by [createAvc] before it returns. */
    val isVideo: Boolean

    /** Joins call [guid] with the one-use session [handle]; blocks until joined. The joined call, or 0. */
    fun createAvc(guid: String, handle: Long): Long

    /** Leaves; [avc] is dead after it. */
    fun destroyAvc(avc: Long)

    /** Sends the first camera stream, or stops; [captureMode] -1 leaves the capture mode as it is. */
    fun enableCamera(avc: Long, enabled: Boolean, captureMode: Int)
    fun enableMicrophone(avc: Long, enabled: Boolean)

    /** Makes the library ask for every stream's Surface again. */
    fun refreshSurfaces(avc: Long)

    /** The camera's orientation, 0..3 only: anything else takes the library's process down. */
    fun updateOrientation(avc: Long, orientation: Int)
}

/** Makes the library's side of one call, which calls [FaceTimeInCallService.Host] back on its own threads. */
fun interface CallNatives {
    fun create(host: FaceTimeInCallService.Host): CallNative
}

/** The real one: [FaceTimeInCallService]'s natives (the library must be loaded: DirectLibrary.load). */
class JniCallNative(host: FaceTimeInCallService.Host) : CallNative {
    private val jni = FaceTimeInCallService(host)
    override val isVideo: Boolean get() = jni.isVideo
    override fun createAvc(guid: String, handle: Long): Long = jni.createAvc(guid, handle)
    override fun destroyAvc(avc: Long) = jni.destroyAvc(avc)
    override fun enableCamera(avc: Long, enabled: Boolean, captureMode: Int) = jni.enableCamera(avc, enabled, captureMode)
    override fun enableMicrophone(avc: Long, enabled: Boolean) = jni.enableMicrophone(avc, enabled)
    override fun refreshSurfaces(avc: Long) = jni.refreshSurfaces(avc)
    override fun updateOrientation(avc: Long, orientation: Int) = jni.updateOrientation(avc, orientation)
}

/**
 * The library's FaceTime functions on the live iMessage session (`uniffi.rust_bridge`), as the call
 * code uses them; the real one is [com.lightdecoder.imessage.direct.DirectEngine]'s. All of them
 * may throw; none is ever called on the main thread.
 */
interface CallBridge {
    /** Whether there is a registered, connected session to call on. */
    val connected: Boolean

    /** How long that session has been up (ms); events just after it came up may be old ones handed over again. */
    val sinceStartMs: Long

    /** The user's registered addresses. */
    suspend fun handles(): List<String>

    /**
     * Tells the library this phone's network addresses. Before any call is placed or joined: without
     * them the library's first use of them takes the process down.
     */
    suspend fun setLocalInterfaces()
    suspend fun createFacetime(guid: String, handle: String, participants: List<String>, video: Boolean)
    suspend fun cancelFacetime(guid: String)
    suspend fun declineFacetime(guid: String)

    /** The library's call list. */
    suspend fun sessions(): List<CallSessionInfo>

    /**
     * Runs [block] with a session handle made for it: each handle is handed to [block] once and
     * never again, with nothing in between (the library takes it back in `createAvc`; one used twice
     * corrupts memory), and the session is kept alive until [block] returns. Null when there is no session.
     */
    suspend fun <T> withNativeHandle(block: (Long) -> T): T?

    /** Which of [targets] can be called (Apple is asked), from the address [from] (null: the user's own first choice). */
    suspend fun capable(targets: List<String>, from: String?): List<String>

    /**
     * What keeps the library's own calls on a call apart: joining one (the check that it is in the
     * list, and the join) never runs while a take-back or a decline of the app's does.
     */
    val guard: kotlinx.coroutines.sync.Mutex
}
