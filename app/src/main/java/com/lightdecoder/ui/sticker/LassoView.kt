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

    var onTraced: ((Path) -> Unit)? = null

    private var bitmap: Bitmap? = null
    private val toView = Matrix()
    private val toBitmap = Matrix()
    private val path = Path()
    private var points = 0
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
            }
            MotionEvent.ACTION_MOVE -> {
                path.lineTo(event.x, event.y)
                points++
            }
            MotionEvent.ACTION_UP -> {
                if (points >= MIN_POINTS) {
                    path.close()
                    val traced = Path(path).apply { transform(toBitmap) }
                    onTraced?.invoke(traced)
                }
                path.reset()
            }
            MotionEvent.ACTION_CANCEL -> path.reset()
        }
        invalidate()
        return true
    }

    private companion object {
        const val MIN_POINTS = 8
    }
}
