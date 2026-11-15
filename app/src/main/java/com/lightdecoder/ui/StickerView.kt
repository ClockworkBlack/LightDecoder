package com.lightdecoder.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.lightdecoder.heif.HeifFrame
import kotlin.math.min

/**
 * Draws a decoded sticker over a transparency checkerboard and plays animated
 * ones.
 *
 * A sticker is a small picture. Stretching it to fill a large screen is what
 * makes it look soft, so the view never enlarges beyond [MAX_FIT_SCALE] on its
 * own, and a tap switches between that fitted size and the picture's true
 * pixel size.
 */
class StickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private var frames: List<HeifFrame> = emptyList()
    private var index = 0
    private var frameStartedAt = 0L
    private var playing = true
    private var actualSize = false

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val checkerPaint = Paint()
    private val src = Rect()
    private val dst = RectF()

    init {
        val cell = (resources.displayMetrics.density * 8).toInt().coerceAtLeast(4)
        checkerPaint.shader = buildCheckerShader(cell)
        setOnClickListener {
            actualSize = !actualSize
            invalidate()
        }
    }

    /** Shows an animation, or a still when there is one frame. Playback starts from the first frame. */
    fun setFrames(newFrames: List<HeifFrame>) {
        frames = newFrames
        index = 0
        frameStartedAt = SystemClock.uptimeMillis()
        playing = newFrames.size > 1
        invalidate()
    }

    val isAnimated: Boolean get() = frames.size > 1

    val isShowingActualSize: Boolean get() = actualSize

    fun togglePlay() {
        if (!isAnimated) return
        playing = !playing
        if (playing) {
            frameStartedAt = SystemClock.uptimeMillis()
            invalidate()
        }
    }

    /** The frame on screen right now. */
    val currentBitmap: Bitmap? get() = frames.getOrNull(index)?.bitmap

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), checkerPaint)

        val frame = frames.getOrNull(index) ?: return
        val bitmap = frame.bitmap
        if (bitmap.isRecycled || bitmap.width == 0 || bitmap.height == 0) return

        val fit = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val scale = if (actualSize) min(1f, fit) else min(fit, MAX_FIT_SCALE)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        src.set(0, 0, bitmap.width, bitmap.height)
        dst.set(left, top, left + w, top + h)
        canvas.drawBitmap(bitmap, src, dst, imagePaint)

        if (playing && frames.size > 1) {
            val now = SystemClock.uptimeMillis()
            if (now - frameStartedAt >= frame.durationMs.coerceAtLeast(MIN_FRAME_MS)) {
                index = (index + 1) % frames.size
                frameStartedAt = now
            }
            postInvalidateOnAnimation()
        }
    }

    private fun buildCheckerShader(cell: Int): Shader {
        val size = cell * 2
        val tile = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tile)
        canvas.drawColor(Color.rgb(237, 237, 237))
        val dark = Paint().apply { color = Color.rgb(207, 207, 207) }
        canvas.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), dark)
        canvas.drawRect(cell.toFloat(), cell.toFloat(), size.toFloat(), size.toFloat(), dark)
        return BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    companion object {
        /** Fitted pictures are enlarged at most this much. */
        private const val MAX_FIT_SCALE = 2f
        private const val MIN_FRAME_MS = 20
    }
}
