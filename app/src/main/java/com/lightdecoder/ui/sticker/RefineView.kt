package com.lightdecoder.ui.sticker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.lightdecoder.sticker.StickerMaker

/**
 * The fine-tune editor of the sticker maker: shows the cut-out over a
 * checkerboard, with what was removed still faintly visible, and lets the
 * user paint with one finger: [keep] brings the picture back, otherwise
 * the brush erases. Two fingers pinch to zoom and drag to move, for precise
 * edges; the brush keeps its size on screen ([brushSizePx]), so zoomed in
 * it is finer.
 *
 * Strokes are kept in the picture's own pixel coordinates ([strokes]), so
 * the sticker can be made from them at any size, and undone one at a time.
 */
class RefineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** After every finished stroke and every undo. */
    var onStrokesChanged: (() -> Unit)? = null

    /** Paint the picture back in (true) or erase (false). */
    var keep: Boolean = false

    /** The brush's diameter on screen, in pixels. */
    var brushSizePx: Float = 24f * resources.displayMetrics.density

    private val _strokes = ArrayList<StickerMaker.Stroke>()
    val strokes: List<StickerMaker.Stroke> get() = _strokes

    private var original: Bitmap? = null
    private var base: Bitmap? = null
    private var result: Bitmap? = null
    private var restoreShader: BitmapShader? = null

    /** Fit (picture → view), then the user's zoom and pan. */
    private val fit = Matrix()
    private val zoom = Matrix()
    private val toView = Matrix()
    private val toBitmap = Matrix()

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ghostPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = GHOST_ALPHA }
    private val checkerPaint = Paint()
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * resources.displayMetrics.density
        color = Color.WHITE
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }

    private var current: Path? = null
    private val currentPoints = ArrayList<Float>()
    private var currentWidth = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var cursorX = -1f
    private var cursorY = -1f
    private var twoFinger = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val current = currentZoom()
            val factor = (current * detector.scaleFactor).coerceIn(1f, MAX_ZOOM) / current
            zoom.postScale(factor, factor, detector.focusX, detector.focusY)
            updateMatrix()
            return true
        }
    })

    init {
        val cell = (8 * resources.displayMetrics.density).toInt().coerceAtLeast(4)
        val tile = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
        Canvas(tile).apply {
            drawColor(Color.rgb(237, 237, 237))
            val dark = Paint().apply { color = Color.rgb(207, 207, 207) }
            drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), dark)
            drawRect(cell.toFloat(), cell.toFloat(), cell * 2f, cell * 2f, dark)
        }
        checkerPaint.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    /**
     * [original] is the picture as chosen; [cutOut] the same picture with the
     * background removed and any tracing applied, without brush strokes (the
     * same size). The strokes so far are kept and drawn over it again.
     */
    fun setPictures(original: Bitmap, cutOut: Bitmap) {
        val sizeChanged = this.original?.let { it.width != original.width || it.height != original.height } ?: false
        this.original = original
        base = cutOut
        restoreShader = BitmapShader(original, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        if (sizeChanged) {
            _strokes.clear()
            redoStack.clear()
            zoom.reset()
        }
        rebuild()
        updateMatrix()
    }

    /** Strokes taken back, the most recent last: [redo] puts them back in order. A new stroke empties it. */
    private val redoStack = ArrayList<StickerMaker.Stroke>()

    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Takes back the last stroke. False when there is none. */
    fun undo(): Boolean {
        if (_strokes.isEmpty()) return false
        redoStack += _strokes.removeAt(_strokes.lastIndex)
        rebuild()
        invalidate()
        onStrokesChanged?.invoke()
        return true
    }

    /** Puts back the last stroke taken back (by [undo] or [clearByUser]). False when there is none. */
    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        _strokes += redoStack.removeAt(redoStack.lastIndex)
        rebuild()
        invalidate()
        onStrokesChanged?.invoke()
        return true
    }

    /** "Clear brushing": every stroke goes, and [redo] can bring them back one by one, in order. */
    fun clearByUser() {
        if (_strokes.isEmpty()) return
        redoStack.addAll(_strokes.asReversed())
        _strokes.clear()
        rebuild()
        invalidate()
        onStrokesChanged?.invoke()
    }

    /** Replaces the strokes (a sticker opened for editing brings its own). */
    fun setStrokes(strokes: List<StickerMaker.Stroke>) {
        redoStack.clear()
        _strokes.clear()
        _strokes.addAll(strokes)
        rebuild()
        invalidate()
    }

    fun clearStrokes() {
        redoStack.clear()
        _strokes.clear()
        rebuild()
        invalidate()
    }

    /** The cut-out with every stroke applied, from scratch. */
    private fun rebuild() {
        val b = base ?: return
        val o = original ?: return
        val r = b.copy(Bitmap.Config.ARGB_8888, true).apply { setHasAlpha(true) }
        StickerMaker.applyStrokes(r, o, _strokes)
        result = r
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        zoom.reset()
        updateMatrix()
    }

    private fun currentZoom(): Float {
        val v = FloatArray(9)
        zoom.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    private fun updateMatrix() {
        val o = original
        if (o != null && width > 0 && height > 0) {
            fit.setRectToRect(
                RectF(0f, 0f, o.width.toFloat(), o.height.toFloat()),
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                Matrix.ScaleToFit.CENTER,
            )
            if (currentZoom() <= 1.001f) zoom.reset()
            // Keep the picture on screen.
            val bounds = RectF(0f, 0f, o.width.toFloat(), o.height.toFloat())
            toView.set(fit)
            toView.postConcat(zoom)
            toView.mapRect(bounds)
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
            zoom.postTranslate(dx, dy)
            toView.set(fit)
            toView.postConcat(zoom)
            toView.invert(toBitmap)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val o = original ?: return
        val r = result ?: return
        canvas.save()
        canvas.concat(toView)
        val rect = RectF(0f, 0f, o.width.toFloat(), o.height.toFloat())
        canvas.drawRect(rect, checkerPaint)
        // What was removed, faintly: so the user can see what a Keep stroke would bring back.
        canvas.drawBitmap(o, 0f, 0f, ghostPaint)
        canvas.drawBitmap(r, 0f, 0f, imagePaint)
        canvas.restore()
        if (cursorX >= 0f && !twoFinger) canvas.drawCircle(cursorX, cursorY, brushSizePx / 2f, cursorPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (original == null) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                twoFinger = false
                startStroke(event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Two fingers zoom and move: the stroke the first finger began is dropped.
                twoFinger = true
                cancelStroke()
                lastFocusX = focusX(event)
                lastFocusY = focusY(event)
            }
            MotionEvent.ACTION_MOVE -> if (twoFinger) {
                val fx = focusX(event)
                val fy = focusY(event)
                zoom.postTranslate(fx - lastFocusX, fy - lastFocusY)
                lastFocusX = fx
                lastFocusY = fy
                updateMatrix()
            } else {
                extendStroke(event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // The remaining finger does nothing until all are lifted.
                lastFocusX = focusX(event, skip = event.actionIndex)
                lastFocusY = focusY(event, skip = event.actionIndex)
            }
            MotionEvent.ACTION_UP -> {
                if (!twoFinger) finishStroke()
                cursorX = -1f
                twoFinger = false
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelStroke()
                cursorX = -1f
                twoFinger = false
            }
        }
        invalidate()
        return true
    }

    private fun focusX(e: MotionEvent, skip: Int = -1): Float {
        var sum = 0f
        var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sum += e.getX(i); n++ }
        return if (n == 0) 0f else sum / n
    }

    private fun focusY(e: MotionEvent, skip: Int = -1): Float {
        var sum = 0f
        var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sum += e.getY(i); n++ }
        return if (n == 0) 0f else sum / n
    }

    private fun toBitmapPoint(x: Float, y: Float): FloatArray = floatArrayOf(x, y).also { toBitmap.mapPoints(it) }

    private fun startStroke(x: Float, y: Float) {
        val p = toBitmapPoint(x, y)
        current = Path().apply { moveTo(p[0], p[1]) }
        currentPoints.clear()
        currentPoints += p[0]
        currentPoints += p[1]
        // The brush's screen size, in picture pixels at the current zoom.
        currentWidth = brushSizePx * toBitmap.mapRadius(1f)
        lastX = p[0]
        lastY = p[1]
        cursorX = x
        cursorY = y
        // A tap leaves a dot.
        drawSegment(p[0], p[1], p[0] + 0.1f, p[1])
    }

    private fun extendStroke(x: Float, y: Float) {
        val path = current ?: return
        val p = toBitmapPoint(x, y)
        path.lineTo(p[0], p[1])
        currentPoints += p[0]
        currentPoints += p[1]
        drawSegment(lastX, lastY, p[0], p[1])
        lastX = p[0]
        lastY = p[1]
        cursorX = x
        cursorY = y
    }

    /** Applies one piece of the stroke being drawn to the shown result at once. */
    private fun drawSegment(x0: Float, y0: Float, x1: Float, y1: Float) {
        val r = result ?: return
        val shader = restoreShader ?: return
        Canvas(r).drawLine(x0, y0, x1, y1, StickerMaker.strokePaint(currentWidth, keep, shader))
    }

    private fun finishStroke() {
        val path = current ?: return
        current = null
        path.lineTo(lastX + 0.1f, lastY)
        _strokes += StickerMaker.Stroke(path, currentWidth, keep, currentPoints.toFloatArray())
        // A new stroke: what was taken back cannot be put back on top of it.
        redoStack.clear()
        onStrokesChanged?.invoke()
    }

    private fun cancelStroke() {
        if (current == null) return
        current = null
        // Undo what the dropped stroke already drew.
        rebuild()
    }

    private companion object {
        const val MAX_ZOOM = 8f
        const val GHOST_ALPHA = 70
    }
}
