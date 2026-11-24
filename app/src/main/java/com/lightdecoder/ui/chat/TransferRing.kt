package com.lightdecoder.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * A download's progress over its picture, as an iPhone draws it: an arc on a translucent disc,
 * with a small square in the middle (a tap stops it). [fraction] is 0..1; under 0 it spins in place
 * of a known amount (the total is not known yet).
 */
class TransferRing @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88000000.toInt() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        color = 0x55FFFFFF
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val stop = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val oval = RectF()

    /** How much is done, 0..1; negative while not known. */
    var fraction: Float = -1f
        set(value) {
            val v = if (value.isNaN()) -1f else value.coerceAtMost(1f)
            if (v == field) return
            field = v
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        if (size <= 0) return
        val cx = width / 2f
        val cy = height / 2f
        val r = size / 2f
        canvas.drawCircle(cx, cy, r, disc)
        val inset = arc.strokeWidth * 2
        oval.set(cx - r + inset, cy - r + inset, cx + r - inset, cy + r - inset)
        canvas.drawOval(oval, track)
        if (fraction >= 0f) {
            canvas.drawArc(oval, -90f, 360f * sweepOf(fraction), false, arc)
        } else {
            val start = (System.currentTimeMillis() % 1200L) * 360f / 1200f
            canvas.drawArc(oval, start, 90f, false, arc)
            postInvalidateOnAnimation()
        }
        val half = r / 4.5f
        canvas.drawRect(cx - half, cy - half, cx + half, cy + half, stop)
    }

    companion object {
        /** The arc's share of the circle for [fraction] done: a sliver shows from the start. */
        fun sweepOf(fraction: Float): Float = fraction.coerceIn(0.02f, 1f)

        /** [fraction] as whole percent, for TalkBack. */
        fun percentOf(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 100).toInt()
    }
}
