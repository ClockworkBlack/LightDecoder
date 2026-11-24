package com.lightdecoder.ui.chat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs

/**
 * A picture that can be zoomed like in a phone's gallery: pinch to zoom,
 * double-tap to zoom in at that spot (and again to fit), drag to look
 * around a zoomed picture. A single tap goes to [onTap].
 *
 * Inside a horizontal pager it shares horizontal drags fairly: while the
 * picture is fitted, or zoomed and already at its edge in the drag's
 * direction, the pager gets the drag (the next picture); otherwise the
 * picture pans.
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {

    /** Single tap (not part of a double tap). */
    var onTap: (() -> Unit)? = null

    /** Touch and hold (a Live Photo plays): null, and a long press does nothing. */
    var onHold: (() -> Unit)? = null

    /** The finger that held ([onHold]) went up, or the gesture was taken away. */
    var onRelease: (() -> Unit)? = null

    /** While true (a Live Photo plays over it) the picture neither zooms nor pans. */
    var zoomSuspended = false

    private var holding = false

    /** Fits the picture into the view (centered). */
    private val base = Matrix()

    /** The user's zoom and pan, applied after [base], in view coordinates. */
    private val user = Matrix()
    private val combined = Matrix()
    private val values = FloatArray(9)
    private val bounds = RectF()

    /** False while the superclass constructor runs (it may set a drawable before the fields above exist). */
    private var ready = false

    private var animator: ValueAnimator? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragDecided = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val current = zoom
            val target = (current * detector.scaleFactor).coerceIn(MIN_ZOOM * 0.85f, MAX_ZOOM)
            val factor = target / current
            user.postScale(factor, factor, detector.focusX, detector.focusY)
            apply(clampZoom = false)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            // A pinch that went below "fitted" springs back.
            if (zoom < MIN_ZOOM) animateTo(Matrix()) else apply()
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!isZoomed || scaleDetector.isInProgress) return false
            user.postTranslate(-distanceX, -distanceY)
            apply()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap?.invoke()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val hold = onHold ?: return
            if (scaleDetector.isInProgress || dragDecided) return
            holding = true
            hold()
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isZoomed) {
                animateTo(Matrix())
            } else {
                animateTo(Matrix().apply { postScale(DOUBLE_TAP_ZOOM, DOUBLE_TAP_ZOOM, e.x, e.y) })
            }
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
        ready = true
        isClickable = true
    }

    /** How far the picture is zoomed beyond "fitted" (1 = fitted). */
    val zoom: Float
        get() {
            user.getValues(values)
            return values[Matrix.MSCALE_X]
        }

    val isZoomed: Boolean get() = zoom > MIN_ZOOM + 0.01f

    /** Back to fitted, at once. */
    fun resetZoom() {
        animator?.cancel()
        user.reset()
        apply()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        val before = super.getDrawable()
        super.setImageDrawable(drawable)
        if (!ready) return
        // A sharper version of the same picture keeps the zoom; another picture starts fitted.
        if (before == null || drawable == null || !sameShape(before, drawable)) user.reset()
        updateBase()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        user.reset()
        updateBase()
    }

    private fun sameShape(a: Drawable, b: Drawable): Boolean {
        if (a.intrinsicWidth <= 0 || b.intrinsicWidth <= 0 || a.intrinsicHeight <= 0 || b.intrinsicHeight <= 0) return false
        val ra = a.intrinsicWidth.toFloat() / a.intrinsicHeight
        val rb = b.intrinsicWidth.toFloat() / b.intrinsicHeight
        return abs(ra - rb) < 0.02f
    }

    private fun updateBase() {
        val d = drawable
        base.reset()
        if (d != null && d.intrinsicWidth > 0 && d.intrinsicHeight > 0 && width > 0 && height > 0) {
            base.setRectToRect(
                RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat()),
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                Matrix.ScaleToFit.CENTER,
            )
        }
        apply()
    }

    /** Keeps the zoom in range and the picture on screen (centered where it is smaller than the view), then shows it. */
    private fun apply(clampZoom: Boolean = true) {
        if (clampZoom) {
            val z = zoom
            if (z > MAX_ZOOM) user.postScale(MAX_ZOOM / z, MAX_ZOOM / z, width / 2f, height / 2f)
            if (z < MIN_ZOOM) user.reset()
        }
        val d = drawable
        if (d != null) {
            combined.set(base)
            combined.postConcat(user)
            bounds.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
            combined.mapRect(bounds)
            val dx = when {
                bounds.width() <= width -> width / 2f - bounds.centerX()
                bounds.left > 0 -> -bounds.left
                bounds.right < width -> width - bounds.right
                else -> 0f
            }
            val dy = when {
                bounds.height() <= height -> height / 2f - bounds.centerY()
                bounds.top > 0 -> -bounds.top
                bounds.bottom < height -> height - bounds.bottom
                else -> 0f
            }
            if (dx != 0f || dy != 0f) user.postTranslate(dx, dy)
        }
        combined.set(base)
        combined.postConcat(user)
        imageMatrix = combined
    }

    private fun animateTo(target: Matrix) {
        animator?.cancel()
        val from = FloatArray(9).also { user.getValues(it) }
        val to = FloatArray(9).also { target.getValues(it) }
        val step = FloatArray(9)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                for (i in 0 until 9) step[i] = from[i] + (to[i] - from[i]) * t
                user.setValues(step)
                apply(clampZoom = false)
            }
            start()
        }
    }

    /** Whether a drag of [dx] (finger moving right when positive) can pan the picture, rather than turn the page. */
    private fun canPan(dx: Float): Boolean {
        if (!isZoomed) return false
        val d = drawable ?: return false
        bounds.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        imageMatrix.mapRect(bounds)
        return if (dx > 0) bounds.left < -EDGE_PX else bounds.right > width + EDGE_PX
    }

    override fun canScrollHorizontally(direction: Int): Boolean = canPan(-direction.toFloat())

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                downX = event.x
                downY = event.y
                dragDecided = false
                // Decided on the first real move; until then the picture keeps the gesture.
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                dragDecided = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (!dragDecided && event.pointerCount == 1) {
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                    dragDecided = true
                    if (abs(dx) > abs(dy) && !canPan(dx)) parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
        if (holding && (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)) {
            holding = false
            onRelease?.invoke()
            return true
        }
        if (zoomSuspended || holding) return true
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }

    private companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 8f
        const val DOUBLE_TAP_ZOOM = 2.5f
        const val ANIMATION_MS = 220L
        const val EDGE_PX = 1f
    }
}
