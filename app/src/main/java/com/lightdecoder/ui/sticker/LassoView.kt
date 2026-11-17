package com.lightdecoder.ui.sticker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Shows a picture and lets the user draw around the part to keep. When the
 * finger lifts, [onTraced] gets the closed outline in the picture's own
 * pixel coordinates.
 */
class LassoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** The closed outline, and the same outline as x,y pairs (both in the picture's pixels). */
    var onTraced: ((Path, FloatArray) -> Unit)? = null

    /** A stroke too small to cut anything out (a tap, a short flick): the user is told how to trace. */
    var onTooSmall: (() -> Unit)? = null

    private var bitmap: Bitmap? = null
    private val toView = Matrix()
    private val toBitmap = Matrix()
    private val path = Path()
    private var points = 0
    private val traced = ArrayList<Float>()
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * resources.displayMetrics.density
        color = Color.WHITE
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    fun setBitmap(picture: Bitmap) {
        bitmap = picture
        path.reset()
        updateMatrix()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = updateMatrix()

    private fun updateMatrix() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        toView.setRectToRect(
            RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()),
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            Matrix.ScaleToFit.CENTER,
        )
        toView.invert(toBitmap)
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        canvas.drawBitmap(b, toView, imagePaint)
        canvas.drawPath(path, linePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                path.reset()
                path.moveTo(event.x, event.y)
                points = 1
                traced.clear()
                addPoint(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                // A fast finger arrives as a few events carrying many points: take them all.
                for (h in 0 until event.historySize) {
                    path.lineTo(event.getHistoricalX(h), event.getHistoricalY(h))
                    points++
                    addPoint(event.getHistoricalX(h), event.getHistoricalY(h))
                }
                path.lineTo(event.x, event.y)
                points++
                addPoint(event.x, event.y)
            }
            MotionEvent.ACTION_UP -> {
                // Judged by its size on screen, not by how many events the phone sent.
                val bounds = android.graphics.RectF().also { path.computeBounds(it, true) }
                val min = MIN_SIZE_DP * resources.displayMetrics.density
                if (points >= 3 && bounds.width() >= min && bounds.height() >= min) {
                    path.close()
                    val outline = Path(path).apply { transform(toBitmap) }
                    onTraced?.invoke(outline, traced.toFloatArray())
                } else {
                    onTooSmall?.invoke()
                }
                path.reset()
            }
            MotionEvent.ACTION_CANCEL -> path.reset()
        }
        invalidate()
        return true
    }

    private fun addPoint(x: Float, y: Float) {
        val p = floatArrayOf(x, y).also { toBitmap.mapPoints(it) }
        traced += p[0]
        traced += p[1]
    }

    private companion object {
        /** A trace smaller than this (both ways, on screen) cuts nothing out. */
        const val MIN_SIZE_DP = 24
    }
}
