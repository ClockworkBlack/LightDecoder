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
 * Draws a decoded sticker over a transparency checkerboard, so the alpha that
 * phones usually mishandle is visible. Animated stickers play on the view's own
 * frame clock; stills just draw once.
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

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val src = Rect()

    private val checkerPaint = Paint()

    init {
        val cell = (resources.displayMetrics.density * 8).toInt().coerceAtLeast(4)
        checkerPaint.shader = buildCheckerShader(cell)
    }

    /** Shows a single still image. */
    fun setStill(bitmap: Bitmap) {
        setFrames(listOf(HeifFrame(bitmap, 0)))
    }

    /** Shows an animation (or a single frame). Playback restarts from frame 0. */
    fun setFrames(newFrames: List<HeifFrame>) {
        frames = newFrames
        index = 0
        frameStartedAt = SystemClock.uptimeMillis()
        playing = newFrames.size > 1
        requestLayout()
        invalidate()
    }

    val isAnimated: Boolean get() = frames.size > 1

    fun togglePlay() {
        if (!isAnimated) return
        playing = !playing
        if (playing) {
            frameStartedAt = SystemClock.uptimeMillis()
            invalidate()
        }
    }

    val currentBitmap: Bitmap? get() = frames.getOrNull(index)?.bitmap

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), checkerPaint)

        val frame = frames.getOrNull(index) ?: return
        val bmp = frame.bitmap
        if (bmp.isRecycled) return

        // Fit inside the view, preserving aspect ratio, pixel-centre aligned.
        val scale = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        src.set(0, 0, bmp.width, bmp.height)
        dst.set(left, top, left + w, top + h)
        canvas.drawBitmap(bmp, src, dst, imagePaint)

        if (playing && frames.size > 1) {
            val now = SystemClock.uptimeMillis()
            val due = frame.durationMs.coerceAtLeast(20)
            if (now - frameStartedAt >= due) {
                index = (index + 1) % frames.size
                frameStartedAt = now
            }
            postInvalidateOnAnimation()
        }
    }

    private fun buildCheckerShader(cell: Int): Shader {
        val size = cell * 2
        val tile = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(tile)
        val light = Color.rgb(237, 237, 237)
        val dark = Color.rgb(207, 207, 207)
        c.drawColor(light)
        val p = Paint().apply { color = dark }
        c.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), p)
        c.drawRect(cell.toFloat(), cell.toFloat(), size.toFloat(), size.toFloat(), p)
        return BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
}
