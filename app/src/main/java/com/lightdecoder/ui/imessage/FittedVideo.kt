package com.lightdecoder.ui.imessage

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import com.lightdecoder.imessage.facetime.Media

/**
 * One view of someone's picture in a call (the other side's camera, a group call's tile, a shared
 * screen) laid out as [VideoFit] says: turned upright by its stream's rotation, undone from the
 * view's stretch, filled or fitted by shape, with the user's pinch, drag and double tap. Every
 * picture of the call screen is shown through one of these, so each looks as the big one does.
 *
 * [fixedFill]: the picture is always filled (true) or always whole (false, a shared screen: a
 * double tap then only takes the zoom back); null: by shape, the user's double tap switching it.
 */
internal class FittedVideo(val view: TextureView, private val fixedFill: Boolean? = null) {

    /** The user's pinch, drag and double tap on the picture. */
    val zoom = VideoFit.Zoom()

    /** The picture's decoder already turns it (its SurfaceTexture's transform says so): it is not turned again. */
    private var textureTurned = false
    private var textureChecked = false
    private val textureMatrix = FloatArray(16)

    /** The shape and turn the zoom was made for: another stream starts fresh. */
    private var zoomFor: Triple<Int, Int, Int>? = null

    /** The picture last laid out. */
    private var picture = Media.Picture()

    /** Lays [shown] into the view (as its decoder makes it, with the library's rotation). */
    fun fit(shown: Media.Picture = picture) {
        picture = shown
        val key = Triple(shown.width, shown.height, shown.rotation)
        if (zoomFor != key) {
            // Another stream, or the other side turned their phone: back to fitted, and the decoder looked at again.
            val before = zoomFor
            if (before != null && (before.first != key.first || before.second != key.second)) textureChecked = false
            zoomFor = key
            zoom.reset()
        }
        if (fixedFill != null) zoom.fill = fixedFill
        view.setTransform(
            FaceTimeCallActivity.remoteTransform(view.width, view.height, shown.width, shown.height, shown.rotation, textureTurned, zoom),
        )
    }

    /** There is no picture now: whatever the user did to the last one is forgotten, and the next one's decoder is looked at again. */
    fun forget() {
        if (!zoom.isZoomed && zoom.fill == null && !textureChecked) return
        zoom.reset()
        zoom.fill = null
        textureChecked = false
        textureTurned = false
        zoomFor = null
    }

    /** A frame was drawn: once per stream, whether the decoder turns the picture itself (then it is not turned again). */
    fun frameDrawn(texture: SurfaceTexture) {
        if (textureChecked) return
        textureChecked = true
        val turned = try {
            texture.getTransformMatrix(textureMatrix)
            VideoFit.textureIsTurned(textureMatrix)
        } catch (e: RuntimeException) {
            false
        }
        if (turned != textureTurned) {
            textureTurned = turned
            fit()
        }
    }

    /**
     * Pinch to zoom, drag to look around, double tap to switch between filled and whole (or, for a
     * picture that is always one, back to unzoomed); [tapped] for a single tap, if wanted. Only
     * while [active]; otherwise the touch goes to what is behind, as before.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun gestures(context: Context, active: () -> Boolean, tapped: (() -> Unit)? = null) {
        val scale = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    zoom.scaleBy(detector.scaleFactor, detector.focusX - view.width / 2f, detector.focusY - view.height / 2f)
                    fit()
                    return true
                }
            },
        )
        val detector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                    if (scale.isInProgress) return false
                    zoom.panX -= distanceX
                    zoom.panY -= distanceY
                    fit()
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    tapped?.invoke() ?: return false
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (fixedFill == null) {
                        val (w, h) = VideoFit.upright(picture.width, picture.height, VideoFit.degreesForStream(picture.rotation))
                        val filled = zoom.fill ?: VideoFit.fillsByDefault(view.width, view.height, w, h)
                        zoom.fill = !filled
                    }
                    zoom.reset()
                    fit()
                    return true
                }
            },
        )
        view.setOnTouchListener { _, event ->
            if (!active()) return@setOnTouchListener false
            scale.onTouchEvent(event)
            detector.onTouchEvent(event)
            true
        }
    }
}
