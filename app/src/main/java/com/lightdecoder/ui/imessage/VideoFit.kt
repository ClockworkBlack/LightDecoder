package com.lightdecoder.ui.imessage

import android.graphics.Matrix
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * How a video picture is laid into a [android.view.TextureView], which by itself stretches whatever
 * it is given to its own shape (that is what made calls look stretched, worst on a near-square
 * screen like a Galaxy Z Fold's inner one).
 *
 * The picture is [bufferWidth] x [bufferHeight] as it arrives, turned [degrees] clockwise to be
 * upright; it is undone from the stretch, turned, scaled to fill the view (edges cut) or to fit it
 * (bars), then zoomed by the user and moved by their pan. Pure: the tests check it.
 */
object VideoFit {
    /** The user's zoom goes this far beyond "filled or fitted". */
    const val MAX_ZOOM = 4f

    /**
     * Filling a view of another shape cuts the picture's edges. Up to this much (the longer of the
     * two shapes over the shorter) a call fills the screen, as FaceTime does on a phone and as a
     * messenger's call does on an unfolded Galaxy Z Fold (an upright picture over all of its
     * near-square screen, its top and bottom cut); beyond it (a picture on its side on an upright
     * phone) it is shown whole. A double tap switches between the two.
     */
    const val FILL_LIMIT = 2.0f

    /** The library's stream rotation (0..3; 3 is "not turned") as degrees clockwise that make the picture upright. */
    fun degreesForStream(rotation: Int): Int = when (rotation) {
        0 -> 90
        1 -> 270
        2 -> 180
        else -> 0
    }

    /** How a picture's decoder already turns it on its way into the view ([textureTurn]). */
    enum class Turn { NONE, QUARTER, HALF }

    /**
     * How a picture is laid out: [width] x [height] is its shape upright, and [degrees] (clockwise)
     * is how far the view still has to turn it to be upright (0 when its decoder turned it already).
     */
    data class Shape(val width: Int, val height: Int, val degrees: Int) {
        /** Whether there is a picture to lay out at all. */
        val known: Boolean get() = width > 0 && height > 0
    }

    /**
     * The shape of the other side's picture, [bufferWidth] x [bufferHeight] as its decoder makes it.
     *
     * What is on the screen decides, not what was said about it: [turn] is how the decoder turns the
     * frames being drawn (read off each frame, null before the first). The library has the decoder
     * turn a picture upright (as OpenBubbles relies on: it never turns a tile, only gives it the
     * turned shape), so a quarter turn there means the picture's sides have changed places, whatever
     * [rotation] the library said, late, or not at all. Laying it out by the said rotation alone is
     * what pulled faces wide: with none said, the upright picture was given the shape it has lying
     * on its side.
     *
     * [rotation] (the library's 0..3, anything else: none said) is used only where the decoder does
     * not turn the picture: before the first frame (the shape it will have; none said counts as a
     * quarter turn, [com.lightdecoder.imessage.facetime.Media.remoteShown]), and for a picture the
     * library says is turned while its frames are not (then the view turns it).
     */
    fun shape(bufferWidth: Int, bufferHeight: Int, rotation: Int, turn: Turn?): Shape {
        val said = rotation in 0..3
        return when (turn) {
            Turn.QUARTER -> Shape(bufferHeight, bufferWidth, 0)
            Turn.HALF -> Shape(bufferWidth, bufferHeight, 0)
            Turn.NONE -> {
                val degrees = if (said) degreesForStream(rotation) else 0
                val (w, h) = upright(bufferWidth, bufferHeight, degrees)
                Shape(w, h, degrees)
            }
            null -> if (!said || rotation == 0 || rotation == 1) Shape(bufferHeight, bufferWidth, 0) else Shape(bufferWidth, bufferHeight, 0)
        }
    }

    /** The picture's width and height once turned upright. */
    fun upright(width: Int, height: Int, degrees: Int): Pair<Int, Int> =
        if (quarter(degrees)) height to width else width to height

    /** Whether a picture [contentWidth] x [contentHeight] (upright) fills the view by default rather than fitting into it. */
    fun fillsByDefault(viewWidth: Int, viewHeight: Int, contentWidth: Int, contentHeight: Int): Boolean {
        if (viewWidth <= 0 || viewHeight <= 0 || contentWidth <= 0 || contentHeight <= 0) return true
        val ratio = (contentWidth.toFloat() / contentHeight) / (viewWidth.toFloat() / viewHeight)
        return max(ratio, 1f / ratio) <= FILL_LIMIT
    }

    /** The user's zoom and pan over the fitted or filled picture. [panX]/[panY] are in view pixels, from the middle. */
    class Zoom {
        var zoom = 1f
        var panX = 0f
        var panY = 0f

        /** Filling (true) or fitting (false) as the user chose with a double tap; null: as [fillsByDefault] says. */
        var fill: Boolean? = null

        val isZoomed: Boolean get() = zoom > 1.01f

        fun reset() {
            zoom = 1f
            panX = 0f
            panY = 0f
        }

        /** Zooms by [factor] keeping the point [focusX], [focusY] (view pixels from the view's middle) where it is. */
        fun scaleBy(factor: Float, focusX: Float, focusY: Float) {
            val target = (zoom * factor).coerceIn(1f, MAX_ZOOM)
            val f = target / zoom
            panX = focusX - (focusX - panX) * f
            panY = focusY - (focusY - panY) * f
            zoom = target
        }
    }

    /**
     * The TextureView transform. [zoom], when given, has its pan kept within the picture (it is
     * changed in place), so a drag never shows more than the picture's edge.
     */
    fun transform(
        viewWidth: Int,
        viewHeight: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        degrees: Int,
        fill: Boolean,
        zoom: Zoom? = null,
    ): Matrix {
        val matrix = Matrix()
        if (viewWidth <= 0 || viewHeight <= 0 || bufferWidth <= 0 || bufferHeight <= 0) return matrix
        val vw = viewWidth.toFloat()
        val vh = viewHeight.toFloat()
        val (cw, ch) = upright(bufferWidth, bufferHeight, degrees).let { it.first.toFloat() to it.second.toFloat() }
        val base = if (fill) max(vw / cw, vh / ch) else min(vw / cw, vh / ch)
        val z = zoom?.zoom?.coerceIn(1f, MAX_ZOOM) ?: 1f
        val scale = base * z
        if (zoom != null) {
            // The picture's edges stay at or beyond the view's where it is larger; centered where it is smaller.
            val maxX = max(0f, (cw * scale - vw) / 2f)
            val maxY = max(0f, (ch * scale - vh) / 2f)
            zoom.zoom = z
            zoom.panX = zoom.panX.coerceIn(-maxX, maxX)
            zoom.panY = zoom.panY.coerceIn(-maxY, maxY)
        }
        // From the stretched view back to the picture's own pixels, about the middle.
        matrix.setTranslate(-vw / 2f, -vh / 2f)
        matrix.postScale(bufferWidth / vw, bufferHeight / vh)
        matrix.postRotate(((degrees % 360) + 360) % 360f)
        matrix.postScale(scale, scale)
        matrix.postTranslate(vw / 2f + (zoom?.panX ?: 0f), vh / 2f + (zoom?.panY ?: 0f))
        return matrix
    }

    /**
     * Whether a SurfaceTexture's own transform ([android.graphics.SurfaceTexture.getTransformMatrix],
     * 4x4, column-major, for texture coordinates) already turns the picture: a decoder that was told
     * the stream's rotation turns it there, and then the picture must not be turned a second time.
     * A decoder's usual transform only flips texture y (it runs up, the screen's runs down): that
     * is not a turn. A quarter turn shows as the 2D part having nothing on its diagonal; a half turn
     * as x flipped as well.
     */
    fun textureIsTurned(m: FloatArray): Boolean = textureTurn(m) != Turn.NONE

    /** [textureIsTurned], saying which turn: a quarter (either way) changes the picture's sides over, a half does not. */
    fun textureTurn(m: FloatArray): Turn {
        if (m.size < 16) return Turn.NONE
        val eps = 0.01f
        val a = m[0]
        val b = m[4]
        val c = m[1]
        val d = m[5]
        return when {
            abs(a) < eps && abs(d) < eps && abs(b) > eps && abs(c) > eps -> Turn.QUARTER
            abs(b) < eps && abs(c) < eps && a < -eps -> Turn.HALF
            else -> Turn.NONE
        }
    }

    private fun quarter(degrees: Int): Boolean = ((degrees % 360) + 360) % 180 == 90
}
