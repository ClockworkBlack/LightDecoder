package com.lightdecoder.imessage.direct

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Drawing of the chat backgrounds that are not pictures ([BackgroundStyle]), all of it this app's
 * own: a gradient as a smooth vertical blend of its colours with soft glows of each (close to how
 * iOS blends them), the aurora as slow-moving coloured light over a dark sky, and a dynamic one not
 * known here as a calm gradient whose colours come from its identifier.
 */
object BackgroundArt {

    /** The colours [style] is drawn from: its own, or a neutral pair for anything else. */
    fun colorsOf(style: BackgroundStyle): IntArray = when (style) {
        is BackgroundStyle.Gradient -> style.colors.map { it.argb() or OPAQUE }.takeIf { it.isNotEmpty() }?.let { cs ->
            (if (cs.size == 1) listOf(cs[0], cs[0]) else cs).toIntArray()
        } ?: NEUTRAL
        is BackgroundStyle.Dynamic -> if (style.isKnown) AURORA_SKY else neutralFor(style.identifier)
    }

    /** A soft, low-saturation pair of colours from [identifier]'s hash: the same one each time. */
    fun neutralFor(identifier: String): IntArray {
        val hue = ((identifier.hashCode().toLong() and 0xFFFFFFFFL) % 360).toFloat()
        val top = Color.HSVToColor(floatArrayOf(hue, 0.18f, 0.88f))
        val bottom = Color.HSVToColor(floatArrayOf((hue + 40f) % 360f, 0.28f, 0.62f))
        return intArrayOf(top, bottom)
    }

    /** [style] drawn into a new bitmap of [width] x [height] (the aurora as it is at its start). */
    fun render(style: BackgroundStyle, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val drawable = drawableFor(style, animate = false)
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    /** [style] as a PNG of [width] x [height]: the Watch's picture of it. */
    fun png(style: BackgroundStyle, width: Int, height: Int): ByteArray =
        ByteArrayOutputStream().also { render(style, width, height).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** How light [style] is, 0 to 1 (the Watch is told). */
    fun luminanceOf(style: BackgroundStyle): Double {
        val cs = colorsOf(style)
        return cs.map { (0.2126 * Color.red(it) + 0.7152 * Color.green(it) + 0.0722 * Color.blue(it)) / 255.0 }.average()
    }

    /** A drawable for [style]: the aurora animated (once started) when [animate], else still. */
    fun drawableFor(style: BackgroundStyle, animate: Boolean): Drawable =
        if (style is BackgroundStyle.Dynamic && style.isKnown) AuroraDrawable(animate) else GradientBackgroundDrawable(colorsOf(style))

    /** Draws a gradient of [colors] filling [width] x [height]. */
    internal fun drawGradient(canvas: Canvas, colors: IntArray, width: Float, height: Float, paint: Paint) {
        paint.shader = LinearGradient(0f, 0f, 0f, height, colors, null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width, height, paint)
        // A soft glow of each colour, on alternate sides, where it sits in the blend: a mesh-like look.
        if (colors.size >= 2) {
            val radius = maxOf(width, height) * 0.55f
            colors.forEachIndexed { i, c ->
                val x = if (i % 2 == 0) width * 0.15f else width * 0.85f
                val y = height * i / (colors.size - 1).toFloat()
                paint.shader = RadialGradient(x, y, radius, intArrayOf(withAlpha(c, 110), withAlpha(c, 0)), null, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, width, height, paint)
            }
        }
        paint.shader = null
    }

    internal fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private const val OPAQUE = 0xFF000000.toInt()
    private val NEUTRAL = intArrayOf(0xFFE3E6EB.toInt(), 0xFF9AA3AF.toInt())

    /** The aurora's sky, top to bottom. */
    internal val AURORA_SKY = intArrayOf(0xFF061026.toInt(), 0xFF0B2240.toInt(), 0xFF0E3A4A.toInt())
}

/** A gradient background ([BackgroundArt.drawGradient]); its shaders made once per size. */
class GradientBackgroundDrawable(val colors: IntArray) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private var cache: Bitmap? = null

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        // Drawn once into a bitmap (radial glows are not free), then just copied.
        val bitmap = cache?.takeIf { it.width == b.width() && it.height == b.height() } ?: run {
            val scale = (CACHE_MAX.toFloat() / maxOf(b.width(), b.height())).coerceAtMost(1f)
            val w = (b.width() * scale).toInt().coerceAtLeast(1)
            val h = (b.height() * scale).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { BackgroundArt.drawGradient(Canvas(it), colors, w.toFloat(), h.toFloat(), paint) }
                .let { if (it.width == b.width() && it.height == b.height()) it else Bitmap.createScaledBitmap(it, b.width(), b.height(), true) }
        }
        cache = bitmap
        canvas.drawBitmap(bitmap, b.left.toFloat(), b.top.toFloat(), null)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    private companion object {
        const val CACHE_MAX = 1280
    }
}

/**
 * The aurora: a dark sky with a few broad ribbons of green, teal and violet light drifting slowly.
 * When running ([start]), redrawn about [FPS] times a second; still otherwise (as when the system's
 * animations are off, or the screen is not in front). Each ribbon is one radial shader made once
 * and only moved, so a frame is a handful of fills.
 */
class AuroraDrawable(private val animate: Boolean) : Drawable(), Animatable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val sky = Paint(Paint.DITHER_FLAG)
    private val matrix = Matrix()
    private var running = false
    private var startedAt = 0L
    private var shownAt = 0f
    private val tick = Runnable { invalidateSelf() }

    private class Ribbon(val color: Int, val y: Float, val sx: Float, val sy: Float, val speed: Float, val phase: Float, val shader: RadialGradient)

    private val ribbons = listOf(
        ribbon(0xFF3DF2A0.toInt(), 0.30f, 0.95f, 0.16f, 0.050f, 0.0f),
        ribbon(0xFF2EC8E6.toInt(), 0.46f, 0.85f, 0.13f, 0.037f, 2.1f),
        ribbon(0xFF9B6BFF.toInt(), 0.20f, 0.75f, 0.11f, 0.029f, 4.0f),
        ribbon(0xFF7CFFCB.toInt(), 0.62f, 0.70f, 0.09f, 0.043f, 1.2f),
    )

    private fun ribbon(color: Int, y: Float, sx: Float, sy: Float, speed: Float, phase: Float) = Ribbon(
        color, y, sx, sy, speed, phase,
        RadialGradient(0f, 0f, 1f, intArrayOf(BackgroundArt.withAlpha(color, 150), BackgroundArt.withAlpha(color, 60), BackgroundArt.withAlpha(color, 0)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP),
    )

    /** Seconds into the animation shown last (0 while still). */
    val time: Float get() = shownAt

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        sky.shader = LinearGradient(0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(), BackgroundArt.AURORA_SKY, null, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val t = if (running) (SystemClock.uptimeMillis() - startedAt) / 1000f else shownAt
        shownAt = t
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        canvas.drawRect(b, sky)
        for (r in ribbons) {
            val a = t * r.speed * 2f * PI.toFloat() + r.phase
            val cx = b.left + w * (0.5f + 0.28f * sin(a))
            val cy = b.top + h * (r.y + 0.04f * cos(a * 0.7f))
            matrix.reset()
            matrix.setScale(w * r.sx, h * r.sy)
            matrix.postRotate(-12f + 10f * sin(a * 0.5f))
            matrix.postTranslate(cx, cy)
            r.shader.setLocalMatrix(matrix)
            paint.shader = r.shader
            canvas.drawRect(b, paint)
        }
        if (running) scheduleSelf(tick, SystemClock.uptimeMillis() + FRAME_MS)
    }

    override fun start() {
        if (!animate || running) return
        running = true
        startedAt = SystemClock.uptimeMillis() - (shownAt * 1000).toLong()
        invalidateSelf()
    }

    override fun stop() {
        if (!running) return
        running = false
        unscheduleSelf(tick)
    }

    override fun isRunning(): Boolean = running

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        sky.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        sky.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    companion object {
        const val FPS = 20
        private const val FRAME_MS = 1000L / FPS
    }
}
