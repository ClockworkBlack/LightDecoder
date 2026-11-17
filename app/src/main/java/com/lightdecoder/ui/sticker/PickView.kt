package com.lightdecoder.ui.sticker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Shows the picture with what the smart cut will drop dimmed, and takes
 * taps on the people or objects in it: [onTap] gets each tap in the
 * picture's own pixel coordinates. The taps so far are drawn as marks,
 * green for keep and red for remove.
 */
class PickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onTap: ((Float, Float) -> Unit)? = null

    private var bitmap: Bitmap? = null
    private var marks: List<Triple<Float, Float, Boolean>> = emptyList()
    private val toView = Matrix()
    private val toBitmap = Matrix()
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.WHITE
        setShadowLayer(3f * density, 0f, 0f, Color.BLACK)
    }
    private val sign = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    /** The picture as the cut stands (the same size each time), and the taps made, as x, y, keep. */
    fun show(picture: Bitmap, taps: List<Triple<Float, Float, Boolean>>) {
        bitmap = picture
        marks = taps
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
        val radius = 11f * density
        val arm = 5f * density
        val at = FloatArray(2)
        for ((x, y, keep) in marks) {
            at[0] = x
            at[1] = y
            toView.mapPoints(at)
            fill.color = if (keep) KEEP_COLOUR else REMOVE_COLOUR
            canvas.drawCircle(at[0], at[1], radius, fill)
            canvas.drawCircle(at[0], at[1], radius, ring)
            canvas.drawLine(at[0] - arm, at[1], at[0] + arm, at[1], sign)
            if (keep) canvas.drawLine(at[0], at[1] - arm, at[0], at[1] + arm, sign)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val b = bitmap ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val at = floatArrayOf(event.x, event.y)
                toBitmap.mapPoints(at)
                if (at[0] >= 0f && at[1] >= 0f && at[0] < b.width && at[1] < b.height) onTap?.invoke(at[0], at[1])
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private companion object {
        const val KEEP_COLOUR = 0xFF2E9E4F.toInt()
        const val REMOVE_COLOUR = 0xFFD43C3C.toInt()
    }
}
