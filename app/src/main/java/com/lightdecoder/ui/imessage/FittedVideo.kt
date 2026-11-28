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
 * screen) laid out as [VideoFit] says: upright and in its own shape (by how its frames come,
 * [VideoFit.shape]), undone from the view's stretch, filled or fitted by shape, with the user's
 * pinch, drag and double tap. Every picture of the call screen is shown through one of these, so
 * each looks as the big one does.
 *
 * [fixedFill]: the picture is always filled (true) or always whole (false, a shared screen: a
 * double tap then only takes the zoom back); null: by shape, the user's double tap switching it.
 * [noted]: where this view says how its picture is laid out, whenever that changes (the iMessage
 * log: what to look at, should a picture ever look pulled or squeezed again); null: nowhere.
 */
internal class FittedVideo(val view: TextureView, private val fixedFill: Boolean? = null, private val noted: ((String) -> Unit)? = null) {

    /** The user's pinch, drag and double tap on the picture. */
    val zoom = VideoFit.Zoom()

    /**
     * How the decoder turns the frames being drawn (each frame's SurfaceTexture transform says so):
     * what the picture's shape goes by. Null before a stream's first frame.
     */
    private var textureTurn: VideoFit.Turn? = null
    private val textureMatrix = FloatArray(16)

    /** The shape the zoom was made for: another one (another stream, the phone turned) starts fresh. */
    private var zoomFor: VideoFit.Shape? = null

    /** The picture last laid out. */
    private var picture = Media.Picture()

    /** What was last said of the layout ([noted]), and how often so far. */
    private var said: List<Any?>? = null
    private var notes = 0

    private fun shape(): VideoFit.Shape = VideoFit.shape(picture.width, picture.height, picture.rotation, textureTurn)

    /** Lays [shown] into the view (as its decoder makes it, with the rotation the library said, if any). */
    fun fit(shown: Media.Picture = picture) {
        picture = shown
        val shape = shape()
        if (zoomFor != shape) {
            // Another stream, or the other side turned their phone: back to fitted.
            zoomFor = shape
            zoom.reset()
        }
        if (fixedFill != null) zoom.fill = fixedFill
        view.setTransform(FaceTimeCallActivity.remoteTransform(view.width, view.height, shape, zoom))
        note(shape)
    }

    /** There is no picture now: whatever the user did to the last one is forgotten, and the next one starts by its own first frame. */
    fun forget() {
        if (!zoom.isZoomed && zoom.fill == null && textureTurn == null && zoomFor == null) return
        zoom.reset()
        zoom.fill = null
        textureTurn = null
        zoomFor = null
    }

    /** The view has a new Surface: how its frames are turned is read off the first one. */
    fun newSurface() {
        textureTurn = null
    }

    /**
     * A frame was drawn: how the decoder turns it. That changes in the middle of a call (the other
     * side turns their phone, and the frames from then on come turned otherwise), and what the
     * library says of it may come before those frames, after them, or not at all: the picture is
     * laid out again with the first frame that is turned otherwise, by that frame.
     */
    fun frameDrawn(texture: SurfaceTexture) {
        val turn = try {
            texture.getTransformMatrix(textureMatrix)
            VideoFit.textureTurn(textureMatrix)
        } catch (e: RuntimeException) {
            VideoFit.Turn.NONE
        }
        if (turn != textureTurn) {
            textureTurn = turn
            fit()
        }
    }

    /** Says how the picture is laid out ([noted]) when that changed since it was last said; a few times a call, never more than [FaceTimeCallActivity.MAX_PICTURE_NOTES]. */
    private fun note(shape: VideoFit.Shape) {
        val to = noted ?: return
        val turn = textureTurn ?: return
        if (!shape.known || view.width <= 0 || view.height <= 0) return
        val fill = zoom.fill ?: VideoFit.fillsByDefault(view.width, view.height, shape.width, shape.height)
        val now = listOf(picture, turn, shape, view.width, view.height, fill)
        if (now == said || notes >= FaceTimeCallActivity.MAX_PICTURE_NOTES) return
        said = now
        notes++
        to(FaceTimeCallActivity.pictureLine(picture.width, picture.height, picture.rotation, turn, shape, view.width, view.height, fill))
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
                        val shape = shape()
                        val filled = zoom.fill ?: VideoFit.fillsByDefault(view.width, view.height, shape.width, shape.height)
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
