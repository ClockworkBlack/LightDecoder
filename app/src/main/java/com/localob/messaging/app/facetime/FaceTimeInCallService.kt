package com.localob.messaging.app.facetime

import android.media.AudioRecord
import android.view.Surface
import androidx.annotation.Keep

/**
 * The Java side of librust_bridge.so's FaceTime calls. The library exports its JNI functions for
 * this exact class name (`Java_com_localob_messaging_app_facetime_FaceTimeInCallService_*`), calls
 * the eight methods at the bottom by name and writes [isVideo]: nothing here may be renamed, moved
 * or given other parameters (proguard-rules.pro keeps it; FaceTimeContractTest pins every name and
 * signature). It is no Android Service here: the library only uses the object it was called on.
 *
 * Nothing else lives here. What a call does is LightDecoder's own, behind [Host]
 * (com.lightdecoder.imessage.facetime): this class only carries the calls across, and never lets
 * anything thrown reach the library (several of its call sites would take the process down).
 *
 * The library is loaded by [com.lightdecoder.imessage.direct.DirectLibrary.load] (by path, for
 * imported files), never here: its natives bind the first time one is called.
 */
@Keep
class FaceTimeInCallService(private val host: Host) {

    /** What the library asks of the app, on the library's own threads. Nothing here may throw. */
    interface Host {
        fun buildSurface(participant: Long, stream: Int): Surface?
        fun streamsRemoved(participant: Long, streams: IntArray)
        fun videoSize(participant: Long, stream: Int, width: Int, height: Int)
        fun streamRotation(participant: Long, stream: Int, rotation: Int)
        fun participants(ids: LongArray, handles: Array<String?>)
        fun encoderSurface(surface: Surface, group: Int)
        fun systemAudio(sampleRate: Int): AudioRecord?
        fun failed()
    }

    /** Written by createAvc through JNI (field "isVideo", type Z) before it returns. */
    @JvmField
    @Volatile
    var isVideo: Boolean = true

    // --- called by the app (JNI functions of the library) -----------------------------------------

    external fun createAvc(guid: String, handle: Long): Long
    external fun destroyAvc(avc: Long)
    external fun enableCamera(avc: Long, enabled: Boolean, captureMode: Int)
    external fun enableCamera2(avc: Long, enabled: Boolean, width: Int, height: Int, captureMode: Int)
    external fun enableMicrophone(avc: Long, enabled: Boolean)
    external fun enableScreenShare(avc: Long, enabled: Boolean, width: Int, height: Int)
    external fun refreshSurfaces(avc: Long)
    external fun updateCamera2Orientation(avc: Long, orientation: Int)
    external fun updateCaptureMode(avc: Long, captureMode: Int)
    external fun updateOrientation(avc: Long, orientation: Int)
    external fun updateScreenOrientation(avc: Long, orientation: Int)

    // --- called by the library, by name ------------------------------------------------------------

    fun buildSurface(participant: Long, stream: Int): Surface? = try {
        host.buildSurface(participant, stream)
    } catch (e: Throwable) {
        null
    }

    fun streamsRemoved(participant: Long, streams: IntArray?) {
        try {
            host.streamsRemoved(participant, streams ?: IntArray(0))
        } catch (e: Throwable) {
            // Never back into the library.
        }
    }

    fun setParticipantVideoSize(participant: Long, stream: Int, width: Int, height: Int) {
        try {
            host.videoSize(participant, stream, width, height)
        } catch (e: Throwable) {
            // Never back into the library.
        }
    }

    fun setStreamRotation(participant: Long, stream: Int, rotation: Int) {
        try {
            host.streamRotation(participant, stream, rotation)
        } catch (e: Throwable) {
            // Never back into the library.
        }
    }

    fun updateParticipants(active: LongArray?, names: Array<String?>?) {
        try {
            host.participants(active ?: LongArray(0), names ?: arrayOfNulls(0))
        } catch (e: Throwable) {
            // Never back into the library.
        }
    }

    fun cameraEncodeSurface(surface: Surface?, group: Int) {
        try {
            if (surface != null) host.encoderSurface(surface, group)
        } catch (e: Throwable) {
            // Never back into the library.
        }
    }

    fun systemAudioRecord(sampleRate: Int): AudioRecord? = try {
        host.systemAudio(sampleRate)
    } catch (e: Throwable) {
        null
    }

    fun callFailed() {
        try {
            host.failed()
        } catch (e: Throwable) {
            // Never back into the library.
        }
    }
}
