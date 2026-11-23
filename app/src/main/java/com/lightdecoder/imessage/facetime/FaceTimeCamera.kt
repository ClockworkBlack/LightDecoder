package com.lightdecoder.imessage.facetime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface

/**
 * The camera of a video call (Android's Camera2). The library makes the picture's encoder (HEVC,
 * 1920x1080) and hands over its input Surface; this points one camera at it, and at the call
 * screen's small view when there is one: one capture session with those outputs and a repeating
 * recording request at 30 pictures a second. The front camera first; [start] with the other side
 * for a flip.
 *
 * A camera that cannot give the encoder's size to a video encoder (no 1080p stream) is not used:
 * [start] then says it did not start, and the call goes on with sound only, rather than leaving the
 * library's encoder without pictures of the size it expects.
 *
 * Everything of the camera runs on a thread of its own. [stop] closes the session and the device
 * and calls back once the device reported closed (or [CLOSE_TIMEOUT_MS] passed): only then may the
 * encoder's Surface be let go of. Never started before a call is joined, never left open after it
 * ([FaceTimeCalls] sees to both).
 */
class FaceTimeCamera(context: Context) : CallCamera {
    private val app = context.applicationContext
    private val manager: CameraManager? = app.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("facetime-camera").apply { start() }
    private val handler = Handler(thread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    /** What is called once the open device has closed. */
    private var closed: (() -> Unit)? = null

    /** Each start has a number: what a camera opened for an earlier one reports is not this one's. */
    private var generation = 0

    override fun has(front: Boolean): Boolean = idFor(front) != null

    /** The camera on that side that can feed a video encoder at the encoder's size; null for none. */
    private fun idFor(front: Boolean): String? {
        val m = manager ?: return null
        return try {
            m.cameraIdList.firstOrNull { id ->
                val c = m.getCameraCharacteristics(id)
                val facing = c.get(CameraCharacteristics.LENS_FACING)
                val wanted = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
                facing == wanted && feedsEncoder(c)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun feedsEncoder(c: CameraCharacteristics): Boolean {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        return map.getOutputSizes(MediaCodec::class.java)?.any { it.width == ENCODER.width && it.height == ENCODER.height } == true
    }

    @SuppressLint("MissingPermission") // the call engine starts the camera only with the permission
    override fun start(encoder: Surface, preview: Surface?, front: Boolean, started: (ok: Boolean, front: Boolean) -> Unit) {
        handler.post {
            val mine = ++generation
            closeNow()
            val m = manager
            val side = if (idFor(front) != null) front else !front
            val id = idFor(side)
            if (m == null || id == null) {
                started(false, front)
                return@post
            }
            try {
                m.openCamera(
                    id,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            if (mine != generation) {
                                camera.close()
                                return
                            }
                            device = camera
                            configure(camera, encoder, preview, mine) { ok -> started(ok, side) }
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            // Taken by something else: the call goes on without it.
                            camera.close()
                            if (mine == generation) started(false, side)
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            FaceTimeLog.note("the camera reported an error ($error)")
                            camera.close()
                            if (mine == generation) started(false, side)
                        }

                        override fun onClosed(camera: CameraDevice) {
                            if (device === camera) {
                                device = null
                                session = null
                            }
                            closed?.let {
                                closed = null
                                it()
                            }
                        }
                    },
                    handler,
                )
            } catch (e: Exception) {
                FaceTimeLog.note("the camera could not be opened (${e::class.java.simpleName})")
                started(false, side)
            }
        }
    }

    /** One session drawing into the encoder and, when there is one, the small view; without the view if it does not take both. */
    private fun configure(camera: CameraDevice, encoder: Surface, preview: Surface?, mine: Int, done: (Boolean) -> Unit) {
        val outputs = listOfNotNull(encoder, preview?.takeIf { it.isValid })
        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                outputs,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (mine != generation) {
                            return
                        }
                        session = s
                        try {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                outputs.forEach(::addTarget)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                frameRate(camera)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                            }
                            s.setRepeatingRequest(request.build(), null, handler)
                            done(true)
                        } catch (e: Exception) {
                            FaceTimeLog.note("the camera could not be started (${e::class.java.simpleName})")
                            done(false)
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (mine != generation) return
                        // The small view's size together with the encoder's is more than this camera gives: the encoder alone.
                        if (outputs.size > 1) configure(camera, encoder, null, mine, done) else done(false)
                    }
                },
                handler,
            )
        } catch (e: Exception) {
            FaceTimeLog.note("the camera's session could not be made (${e::class.java.simpleName})")
            if (outputs.size > 1) configure(camera, encoder, null, mine, done) else done(false)
        }
    }

    /** A steady 30 pictures a second where the camera has it, else its fastest range that reaches 30. */
    private fun frameRate(camera: CameraDevice): Range<Int>? = try {
        val ranges = manager?.getCameraCharacteristics(camera.id)?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        ranges.firstOrNull { it.lower == FPS && it.upper == FPS } ?: ranges.filter { it.upper == FPS }.maxByOrNull { it.lower }
    } catch (e: Exception) {
        null
    }

    override fun stop(stopped: () -> Unit) {
        handler.post {
            generation++
            val open = device
            if (open == null) {
                stopped()
                return@post
            }
            var told = false
            val once = {
                if (!told) {
                    told = true
                    stopped()
                }
            }
            closed = once
            // A camera that never says it closed does not hold the call's end up for good.
            handler.postDelayed({ once() }, CLOSE_TIMEOUT_MS)
            closeNow()
        }
    }

    private fun closeNow() {
        try {
            session?.close()
        } catch (e: Exception) {
            // Closed already.
        }
        session = null
        try {
            device?.close()
        } catch (e: Exception) {
            // Closed already.
        }
        device = null
    }

    companion object {
        /** The size of the library's encoder (read from the library: 1920x1080, HEVC, 30 a second). */
        val ENCODER = Size(1920, 1080)
        private const val FPS = 30

        /** The camera is given this long to report that it let go of the encoder's Surface. */
        const val CLOSE_TIMEOUT_MS = 3_000L

        /** The size the small view's pictures are made at (the encoder's shape, smaller). */
        val PREVIEW = Size(1280, 720)

        /** A Surface of [texture] for the small view, at [PREVIEW]'s size. */
        fun previewSurface(texture: SurfaceTexture): Surface {
            texture.setDefaultBufferSize(PREVIEW.width, PREVIEW.height)
            return Surface(texture)
        }
    }
}
