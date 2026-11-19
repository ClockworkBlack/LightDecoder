package com.lightdecoder.ui.chat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * A screen effect drawn over the whole thread ([EffectPlayer]): balloons rising, confetti falling, a
 * heart, light beams, fireworks, sparkles, the message's text echoed, a spotlight on its bubble;
 * and invisible ink's shimmer over the bubble. This app's own simple drawing, from [progress]
 * (0 to 1) of its animator. It takes no touches and is not read by TalkBack.
 */
internal class EffectOverlayView(context: Context) : View(context) {

    /** How far the effect has played, 0 to 1. */
    var progress = 0f
        set(value) {
            field = value
            invalidate()
        }

    private var kind: EffectKind = EffectKind.CONFETTI
    private var anchor: RectF? = null
    private var text = ""
    private var animator: ValueAnimator? = null
    private val random = Random(SEED)
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics); color = Color.WHITE }
    private val path = Path()
    private val rect = RectF()

    /** Each piece's own random numbers (where it starts, how fast, its colour), drawn once. */
    private var pieces: List<FloatArray> = emptyList()

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    /** Plays [kind] from [anchor] (the bubble, in this view's coordinates; null: the middle) as [animator] runs. */
    fun start(kind: EffectKind, anchor: RectF?, text: String, animator: ValueAnimator) {
        this.kind = kind
        this.anchor = anchor
        this.text = text.replace('\n', ' ').let { if (it.length > MAX_ECHO_CHARS) it.take(MAX_ECHO_CHARS) + "…" else it }
        this.animator = animator
        pieces = List(countFor(kind)) { FloatArray(PIECE_VALUES) { random.nextFloat() } }
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun countFor(kind: EffectKind) = when (kind) {
        EffectKind.BALLOONS -> 14
        EffectKind.CONFETTI -> 120
        EffectKind.LOVE -> 10
        EffectKind.LASERS -> 6
        EffectKind.FIREWORKS -> 5 * BURST_SPARKS
        EffectKind.CELEBRATION -> 90
        EffectKind.ECHO -> 22
        EffectKind.INK -> 70
        else -> 0
    }

    /** The bubble's middle, or the screen's. */
    private fun centerX() = anchor?.centerX() ?: (width / 2f)
    private fun centerY() = anchor?.centerY() ?: (height / 2f)

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val t = progress.coerceIn(0f, 1f)
        when (kind) {
            EffectKind.BALLOONS -> balloons(canvas, t)
            EffectKind.CONFETTI -> confetti(canvas, t)
            EffectKind.LOVE -> love(canvas, t)
            EffectKind.LASERS -> lasers(canvas, t)
            EffectKind.FIREWORKS -> fireworks(canvas, t)
            EffectKind.CELEBRATION -> celebration(canvas, t)
            EffectKind.ECHO -> echo(canvas, t)
            EffectKind.SPOTLIGHT -> spotlight(canvas, t)
            EffectKind.INK -> shimmer(canvas, t)
            else -> Unit
        }
    }

    /** Fades in over the first [inEnd] and out from [outStart]; 1 between. */
    private fun envelope(t: Float, inEnd: Float = 0.12f, outStart: Float = 0.8f): Float = when {
        t < inEnd -> t / inEnd
        t > outStart -> (1f - t) / (1f - outStart)
        else -> 1f
    }.coerceIn(0f, 1f)

    /** [t] made local to a piece that starts at [start] (of 0..1) and lasts [length]: 0 to 1, or outside it. */
    private fun local(t: Float, start: Float, length: Float) = (t - start) / length

    private fun dim(canvas: Canvas, alpha: Float) {
        paint.color = Color.argb((alpha * 255).toInt().coerceIn(0, 255), 0, 0, 0)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    private fun balloons(canvas: Canvas, t: Float) {
        for (p in pieces) {
            val r = (26 + p[2] * 18) * dp
            val local = local(t, p[1] * 0.35f, 0.65f)
            if (local <= 0f || local >= 1f) continue
            val y = height + 3 * r - local * (height + 6 * r)
            val x = p[0] * width + sin(local * 2 * PI.toFloat() * (1 + p[3]) + p[4] * 6f) * 18 * dp
            val color = BALLOON_COLORS[(p[5] * BALLOON_COLORS.size).toInt().coerceAtMost(BALLOON_COLORS.size - 1)]
            // The string, swaying.
            stroke.strokeWidth = 1.2f * dp
            stroke.color = Color.argb(160, 120, 120, 120)
            path.reset()
            path.moveTo(x, y + r)
            path.quadTo(x - 10 * dp, y + 2 * r, x + 4 * dp, y + 3 * r)
            canvas.drawPath(path, stroke)
            // The knot and the balloon, with a shine.
            paint.color = color
            path.reset()
            path.moveTo(x, y + r * 0.92f)
            path.lineTo(x - 5 * dp, y + r + 6 * dp)
            path.lineTo(x + 5 * dp, y + r + 6 * dp)
            path.close()
            canvas.drawPath(path, paint)
            rect.set(x - r * 0.85f, y - r, x + r * 0.85f, y + r)
            canvas.drawOval(rect, paint)
            paint.color = Color.argb(90, 255, 255, 255)
            rect.set(x - r * 0.5f, y - r * 0.7f, x - r * 0.15f, y - r * 0.15f)
            canvas.drawOval(rect, paint)
        }
    }

    private fun confetti(canvas: Canvas, t: Float) {
        val fade = envelope(t, inEnd = 0.02f, outStart = 0.82f)
        for (p in pieces) {
            val local = local(t, p[1] * 0.4f, 0.6f)
            if (local <= 0f || local >= 1f) continue
            val y = -20 * dp + local * (height + 40 * dp) * (0.8f + p[2] * 0.4f)
            val x = p[0] * width + sin(local * 8f + p[3] * 6f) * 22 * dp
            paint.color = CONFETTI_COLORS[(p[5] * CONFETTI_COLORS.size).toInt().coerceAtMost(CONFETTI_COLORS.size - 1)]
            paint.alpha = (fade * 255).toInt()
            canvas.save()
            canvas.rotate(local * 720f * (p[4] - 0.5f) * 2f, x, y)
            // A strip seen turning: its width swings.
            val w = 4 * dp * (0.3f + kotlin.math.abs(cos(local * 12f + p[3] * 5f)))
            canvas.drawRect(x - w, y - 6 * dp, x + w, y + 6 * dp, paint)
            canvas.restore()
        }
        paint.alpha = 255
    }

    private fun heartPath(cx: Float, cy: Float, size: Float) {
        path.reset()
        path.moveTo(cx, cy + size * 0.35f)
        path.cubicTo(cx - size * 1.1f, cy - size * 0.35f, cx - size * 0.45f, cy - size * 1.05f, cx, cy - size * 0.45f)
        path.cubicTo(cx + size * 0.45f, cy - size * 1.05f, cx + size * 1.1f, cy - size * 0.35f, cx, cy + size * 0.35f)
        path.close()
    }

    private fun love(canvas: Canvas, t: Float) {
        val cx = centerX()
        val grow = min(1f, t / 0.3f)
        val rise = max(0f, (t - 0.3f) / 0.7f)
        val size = (40 + 70 * grow) * dp
        val cy = centerY() - rise * height * 0.35f
        val alpha = envelope(t, inEnd = 0.05f, outStart = 0.7f)
        // Small hearts drifting up around it.
        for (p in pieces) {
            val local = local(t, 0.2f + p[1] * 0.4f, 0.4f)
            if (local <= 0f || local >= 1f) continue
            heartPath(cx + (p[0] - 0.5f) * 2.4f * size, centerY() - local * height * 0.4f, (8 + p[2] * 10) * dp)
            paint.color = Color.argb(((1f - local) * 200).toInt(), 255, 92, 138)
            canvas.drawPath(path, paint)
        }
        heartPath(cx, cy, size)
        paint.color = Color.argb((alpha * 235).toInt(), 236, 46, 92)
        canvas.drawPath(path, paint)
    }

    private fun lasers(canvas: Canvas, t: Float) {
        val alpha = envelope(t)
        dim(canvas, alpha * 0.6f)
        val ox = width / 2f
        val oy = centerY()
        val length = (width + height).toFloat()
        for ((i, p) in pieces.withIndex()) {
            val base = i * (360f / pieces.size)
            val angle = Math.toRadians((base + sin(t * PI.toFloat() * 3f + p[0] * 6f) * 40f + t * 120f).toDouble())
            val ex = ox + (cos(angle) * length).toFloat()
            val ey = oy + (sin(angle) * length).toFloat()
            val hue = (i * 60f + t * 360f) % 360f
            val color = Color.HSVToColor(floatArrayOf(hue, 0.8f, 1f))
            // A wide faint glow under a thin bright beam.
            stroke.color = color
            stroke.alpha = (alpha * 70).toInt()
            stroke.strokeWidth = 14 * dp
            canvas.drawLine(ox, oy, ex, ey, stroke)
            stroke.alpha = (alpha * 230).toInt()
            stroke.strokeWidth = 3 * dp
            canvas.drawLine(ox, oy, ex, ey, stroke)
        }
    }

    private fun fireworks(canvas: Canvas, t: Float) {
        dim(canvas, envelope(t) * 0.55f)
        val bursts = pieces.size / BURST_SPARKS
        for (b in 0 until bursts) {
            val seed = pieces[b * BURST_SPARKS]
            val start = b * 0.13f
            val local = local(t, start, 0.45f)
            if (local <= 0f || local >= 1f) continue
            val bx = (0.15f + seed[0] * 0.7f) * width
            val by = (0.15f + seed[1] * 0.45f) * height
            val reach = (70 + seed[2] * 60) * dp
            val color = CONFETTI_COLORS[(seed[3] * CONFETTI_COLORS.size).toInt().coerceAtMost(CONFETTI_COLORS.size - 1)]
            val spread = 1f - (1f - local) * (1f - local)
            for (s in 0 until BURST_SPARKS) {
                val p = pieces[b * BURST_SPARKS + s]
                val angle = s * (2 * PI.toFloat() / BURST_SPARKS) + p[4] * 0.2f
                val d = reach * spread * (0.75f + p[5] * 0.25f)
                val x = bx + cos(angle) * d
                val y = by + sin(angle) * d + local * local * 30 * dp
                paint.color = color
                paint.alpha = ((1f - local) * 255).toInt()
                canvas.drawCircle(x, y, 2.6f * dp, paint)
                stroke.color = color
                stroke.alpha = ((1f - local) * 110).toInt()
                stroke.strokeWidth = 1.4f * dp
                canvas.drawLine(bx + cos(angle) * d * 0.6f, by + sin(angle) * d * 0.6f, x, y, stroke)
            }
        }
        paint.alpha = 255
    }

    private fun sparkle(canvas: Canvas, x: Float, y: Float, size: Float) {
        path.reset()
        path.moveTo(x, y - size)
        path.lineTo(x + size * 0.22f, y - size * 0.22f)
        path.lineTo(x + size, y)
        path.lineTo(x + size * 0.22f, y + size * 0.22f)
        path.lineTo(x, y + size)
        path.lineTo(x - size * 0.22f, y + size * 0.22f)
        path.lineTo(x - size, y)
        path.lineTo(x - size * 0.22f, y - size * 0.22f)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun celebration(canvas: Canvas, t: Float) {
        // From the top corner across the screen, gold.
        val ox = width.toFloat()
        val oy = 0f
        for (p in pieces) {
            val local = local(t, p[1] * 0.55f, 0.45f)
            if (local <= 0f || local >= 1f) continue
            val angle = (PI / 2 + p[0] * PI / 2.6).toFloat()
            val d = local * (width + height) * 0.55f * (0.5f + p[2] * 0.5f)
            val x = ox + cos(angle) * d
            val y = oy + sin(angle) * d
            val twinkle = 0.5f + 0.5f * sin(local * 20f + p[3] * 6f)
            paint.color = SPARKLE_COLORS[(p[5] * SPARKLE_COLORS.size).toInt().coerceAtMost(SPARKLE_COLORS.size - 1)]
            paint.alpha = ((1f - local) * twinkle * 255).toInt()
            sparkle(canvas, x, y, (4 + p[4] * 7) * dp)
        }
        paint.alpha = 255
    }

    private fun echo(canvas: Canvas, t: Float) {
        if (text.isEmpty()) return
        val cx = centerX()
        val cy = centerY()
        val textWidth = textPaint.measureText(text)
        val pad = 10 * dp
        val fontHeight = textPaint.textSize
        for (p in pieces) {
            val local = local(t, p[1] * 0.5f, 0.5f)
            if (local <= 0f || local >= 1f) continue
            val tx = p[0] * width
            val ty = p[2] * height
            val x = cx + (tx - cx) * local
            val y = cy + (ty - cy) * local
            val scale = 0.6f + p[3] * 0.6f
            val alpha = (1f - local)
            canvas.save()
            canvas.scale(scale, scale, x, y)
            rect.set(x - textWidth / 2 - pad, y - fontHeight / 2 - pad, x + textWidth / 2 + pad, y + fontHeight / 2 + pad)
            paint.color = Color.argb((alpha * 200).toInt(), 10, 132, 255)
            canvas.drawRoundRect(rect, 18 * dp, 18 * dp, paint)
            textPaint.alpha = (alpha * 255).toInt()
            canvas.drawText(text, x - textWidth / 2, y + fontHeight / 3, textPaint)
            canvas.restore()
        }
    }

    private fun spotlight(canvas: Canvas, t: Float) {
        val alpha = envelope(t, inEnd = 0.2f, outStart = 0.75f)
        val a = anchor
        val radius = if (a == null) min(width, height) / 4f else max(a.width(), a.height()) / 2f + 24 * dp
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        path.addCircle(centerX(), centerY(), radius, Path.Direction.CW)
        paint.color = Color.argb((alpha * 200).toInt(), 0, 0, 0)
        canvas.drawPath(path, paint)
        path.fillType = Path.FillType.WINDING
        stroke.color = Color.argb((alpha * 90).toInt(), 255, 250, 220)
        stroke.strokeWidth = 6 * dp
        canvas.drawCircle(centerX(), centerY(), radius, stroke)
    }

    /** Invisible ink: specks twinkling over the bubble. */
    private fun shimmer(canvas: Canvas, t: Float) {
        val a = anchor ?: return
        val fade = envelope(t, inEnd = 0.1f, outStart = 0.7f)
        for (p in pieces) {
            val x = a.left + p[0] * a.width() + sin(t * 6f + p[3] * 6f) * 3 * dp
            val y = a.top + p[1] * a.height() + cos(t * 5f + p[4] * 6f) * 3 * dp
            val twinkle = 0.5f + 0.5f * sin(t * 18f + p[2] * 12f)
            paint.color = Color.argb((fade * twinkle * 220).toInt(), 235, 235, 245)
            canvas.drawCircle(x, y, (0.8f + p[5] * 1.4f) * dp, paint)
        }
    }

    private companion object {
        const val SEED = 7
        const val PIECE_VALUES = 6
        const val BURST_SPARKS = 28
        const val MAX_ECHO_CHARS = 40
        val BALLOON_COLORS = intArrayOf(0xFFE53950.toInt(), 0xFFFFB300.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFFF7043.toInt())
        val CONFETTI_COLORS = intArrayOf(0xFFEF5350.toInt(), 0xFFFFCA28.toInt(), 0xFF66BB6A.toInt(), 0xFF42A5F5.toInt(), 0xFFAB47BC.toInt(), 0xFF26C6DA.toInt(), 0xFFFF8A65.toInt())
        val SPARKLE_COLORS = intArrayOf(0xFFFFD54F.toInt(), 0xFFFFE082.toInt(), 0xFFFFF8E1.toInt(), 0xFFFFB300.toInt())
    }
}
