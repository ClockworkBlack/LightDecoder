package com.lightdecoder.sticker

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Turns a picture into a sticker: optionally cuts away a plain background,
 * clips it to a shape, puts the classic white outline around it, and fits the
 * result into a square of [SIZE] pixels.
 *
 * The result is an ordinary PNG, because that is what every phone shows: an
 * iPhone displays it as a picture in the conversation (with its transparency
 * over RCS and iMessage-free chats; a carrier that still converts MMS
 * pictures to JPEG loses transparency, which is what [Options.solidBackground]
 * is for). The background removal either follows the picture's subject
 * ([SubjectMask], found on the phone by [SubjectCutter]) or, without one, is
 * a flood fill from the picture's edges, which suits plain backgrounds.
 */
object StickerMaker {

    const val SIZE = 512

    enum class Shape { ORIGINAL, ROUNDED, CIRCLE }

    /** What the smart cut keeps: people if there are any and otherwise objects, only one of them, or both. */
    enum class SmartKind { AUTO, PEOPLE, OBJECTS, BOTH }

    data class Options(
        val removeBackground: Boolean = false,
        /** 0..100: how different from the edge colour a pixel may be and still count as background. */
        val tolerance: Int = 24,
        val shape: Shape = Shape.ORIGINAL,
        val outline: Boolean = true,
        /** The outline's thickness in pixels of the 512 px sticker ([MIN_OUTLINE]..[MAX_OUTLINE]). */
        val outlineWidth: Int = OUTLINE,
        /** White behind the sticker instead of transparency (for carriers that turn MMS pictures into JPEG). */
        val solidBackground: Boolean = false,
        /** Remove the background by finding the subject, when a [SubjectMask] is at hand; else by its plain colour. */
        val smart: Boolean = false,
        val smartKind: SmartKind = SmartKind.AUTO,
        /** 0..100: how readily the smart cut keeps what it is unsure of (50 is the model's own judgement). */
        val smartKeep: Int = 50,
        /** 0..100: how much the picture's detail is sharpened (for soft pictures such as video frames); 0 leaves it. */
        val sharpen: Int = 0,
        /** A short title in white letters beneath the picture; blank for none. */
        val caption: String = "",
    )

    /**
     * One brush stroke of the fine-tune editor, in the source picture's pixel
     * coordinates: [keep] paints the original picture back in, otherwise it
     * erases. [width] is the brush's diameter in source pixels.
     */
    class Stroke(val path: Path, val width: Float, val keep: Boolean, val points: FloatArray = FloatArray(0)) {
        companion object {
            /** A stroke through x,y pairs, kept so it can be saved and drawn again ([StickerProject]). */
            fun of(points: FloatArray, width: Float, keep: Boolean): Stroke {
                val path = StickerProject.pathOf(points, close = false)
                if (points.size == 2) path.lineTo(points[0] + 0.1f, points[1]) // a dot
                return Stroke(path, width, keep, points)
            }
        }
    }

    /** The user's brush strokes, and the picture a Keep stroke paints back from (same size as the source). */
    class Edits(val restoreFrom: Bitmap, val strokes: List<Stroke>)

    /**
     * The cut-out before shape, outline and fitting: [source] scaled to the
     * working size, with the plain background removed if asked, and [edits]
     * applied. A new bitmap; [source] is not changed.
     */
    fun cutOut(source: Bitmap, options: Options, edits: Edits? = null, subject: SubjectMask? = null): Bitmap {
        var work = scaledDown(source, SIZE * 2)
        if (options.removeBackground) {
            if (options.smart && subject != null) keepSubject(work, subject, options.smartKeep)
            else work = withoutBackground(work, options.tolerance)
        }
        if (edits != null && edits.strokes.isNotEmpty()) {
            val scale = work.width.toFloat() / source.width
            val restore = if (edits.restoreFrom.width == work.width && edits.restoreFrom.height == work.height) {
                edits.restoreFrom
            } else {
                Bitmap.createScaledBitmap(edits.restoreFrom, work.width, work.height, true)
            }
            applyStrokes(work, restore, edits.strokes, scale)
        }
        return work
    }

    /**
     * Draws [strokes] onto [target] (mutable, ARGB_8888): erasing strokes
     * clear it, keep strokes paint [restoreFrom] (the same size as [target])
     * back in. Stroke coordinates are multiplied by [scale] first.
     */
    fun applyStrokes(target: Bitmap, restoreFrom: Bitmap, strokes: List<Stroke>, scale: Float = 1f) {
        val canvas = Canvas(target)
        val matrix = Matrix().apply { setScale(scale, scale) }
        val shader = BitmapShader(restoreFrom, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        for (stroke in strokes) {
            val path = if (scale == 1f) stroke.path else Path(stroke.path).apply { transform(matrix) }
            canvas.drawPath(path, strokePaint(stroke.width * scale, stroke.keep, shader))
        }
    }

    /** The paint of one brush stroke: round, soft-edged, clearing or restoring. */
    fun strokePaint(width: Float, keep: Boolean, restore: BitmapShader): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width.coerceAtLeast(1f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            if (keep) {
                shader = restore
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
            } else {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
        }

    /**
     * Multiplies [target]'s transparency by [subject] (stretched over it if
     * their sizes differ): what is not the subject goes. [keep] (0..100) moves
     * the line between the two: higher keeps more of what the model was
     * unsure of. The short ramp across the line keeps the edge crisp but not
     * stair-stepped.
     */
    internal fun keepSubject(target: Bitmap, subject: SubjectMask, keep: Int = 50) {
        val line = 0.8f - 0.6f * keep.coerceIn(0, 100) / 100f
        val kept = IntArray(256) { level ->
            val t = ((level / 255f - (line - EDGE_HALF)) / (2 * EDGE_HALF)).coerceIn(0f, 1f)
            (t * t * (3 - 2 * t) * 255).roundToInt()
        }
        val w = target.width
        val h = target.height
        val pixels = IntArray(w * h)
        target.getPixels(pixels, 0, w, 0, 0, w, h)
        val same = subject.width == w && subject.height == h
        for (y in 0 until h) {
            val my = if (same) y else (y * subject.height / h).coerceAtMost(subject.height - 1)
            for (x in 0 until w) {
                val mx = if (same) x else (x * subject.width / w).coerceAtMost(subject.width - 1)
                val share = kept[subject.confidence[my * subject.width + mx].toInt() and 0xFF]
                if (share == 255) continue
                val i = y * w + x
                val c = pixels[i]
                val a = (c ushr 24) * share / 255
                pixels[i] = if (a == 0) 0 else (a shl 24) or (c and 0xFFFFFF)
            }
        }
        target.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    /**
     * [bitmap] with its detail brought out (an unsharp mask): each pixel is
     * pushed away from the average of its neighbours. Transparent neighbours
     * are left out of the average, so the cut edge gets no halo, and the
     * transparency itself is untouched.
     */
    internal fun sharpened(bitmap: Bitmap, amount: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val src = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val k = amount.coerceIn(0, 100) / 100f * SHARPEN_MAX
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val c = src[i]
            if ((c ushr 24) < 8) {
                out[i] = c
                continue
            }
            var r = 0
            var g = 0
            var b = 0
            var weight = 0
            for (dy in -1..1) {
                val yy = y + dy
                if (yy < 0 || yy >= h) continue
                for (dx in -1..1) {
                    val xx = x + dx
                    if (xx < 0 || xx >= w) continue
                    val p = src[yy * w + xx]
                    if ((p ushr 24) < 8) continue
                    val wt = (2 - kotlin.math.abs(dx)) * (2 - kotlin.math.abs(dy))
                    r += ((p shr 16) and 0xFF) * wt
                    g += ((p shr 8) and 0xFF) * wt
                    b += (p and 0xFF) * wt
                    weight += wt
                }
            }
            fun push(own: Int, sum: Int): Int = (own + k * (own - sum.toFloat() / weight)).roundToInt().coerceIn(0, 255)
            out[i] = (c and 0xFF000000.toInt()) or
                (push((c shr 16) and 0xFF, r) shl 16) or (push((c shr 8) and 0xFF, g) shl 8) or push(c and 0xFF, b)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(out, 0, w, 0, 0, w, h) }
    }

    /** The title as it is drawn: one line, trimmed, at most [MAX_CAPTION] characters. */
    fun captionOf(text: String): String = text.replace('\n', ' ').trim().take(MAX_CAPTION)

    /** [source] is not changed or recycled. */
    fun make(source: Bitmap, options: Options, edits: Edits? = null, subject: SubjectMask? = null): Bitmap {
        var work = cutOut(source, options, edits, subject)
        if (options.shape != Shape.ORIGINAL) work = clipped(work, options.shape)
        work = trimmed(work)
        val border = if (options.outline) options.outlineWidth.coerceIn(MIN_OUTLINE, MAX_OUTLINE) else 0
        val inner = SIZE - 2 * (border + MARGIN)
        val caption = captionOf(options.caption)
        // The title's band is taken from the picture's height, so both fit the square.
        val band = if (caption.isEmpty()) 0 else CAPTION_BAND
        val scale = min(inner.toFloat() / work.width, (inner - band).toFloat() / work.height)
        val w = max(1, (work.width * scale).roundToInt())
        val h = max(1, (work.height * scale).roundToInt())
        var fitted = if (w == work.width && h == work.height) work else Bitmap.createScaledBitmap(work, w, h, true)
        if (options.sharpen > 0) fitted = sharpened(fitted, options.sharpen)
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        if (options.solidBackground) canvas.drawColor(Color.WHITE)
        val left = (SIZE - w) / 2f
        val top = (SIZE - band - h) / 2f
        if (options.outline) {
            val white = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            }
            // The picture's own silhouette in white, stamped around circles out to the
            // thickness: an even outline, without gaps however thick it is.
            val steps = max(OUTLINE_STEPS, border * 4)
            var radius = border.toFloat()
            while (radius > 0f) {
                for (step in 0 until steps) {
                    val angle = 2 * Math.PI * step / steps
                    canvas.drawBitmap(fitted, left + (cos(angle) * radius).toFloat(), top + (sin(angle) * radius).toFloat(), white)
                }
                radius -= OUTLINE_RING_STEP
            }
        }
        canvas.drawBitmap(fitted, left, top, Paint(Paint.FILTER_BITMAP_FLAG))
        if (caption.isNotEmpty()) drawCaption(canvas, caption, top + h + border)
        return out
    }

    /** White letters centred beneath the picture, with a thin dark edge so they read on any chat background. */
    private fun drawCaption(canvas: Canvas, caption: String, below: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            textSize = CAPTION_TEXT
        }
        val room = SIZE - 4f * MARGIN
        // A long title is written smaller rather than cut off.
        while (paint.textSize > CAPTION_TEXT_MIN && paint.measureText(caption) > room) paint.textSize -= 2f
        var text = caption
        while (text.length > 1 && paint.measureText(text) > room) text = text.dropLast(2).trimEnd() + "…"
        val metrics = paint.fontMetrics
        val baseline = below + (CAPTION_BAND - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = paint.textSize / 9f
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = 0xD9000000.toInt()
        canvas.drawText(text, SIZE / 2f, baseline, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawText(text, SIZE / 2f, baseline, paint)
    }

    private fun scaledDown(source: Bitmap, maxSide: Int): Bitmap {
        val largest = max(source.width, source.height)
        val copy = if (largest <= maxSide) {
            source.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            val scale = maxSide.toFloat() / largest
            Bitmap.createScaledBitmap(
                source, max(1, (source.width * scale).roundToInt()), max(1, (source.height * scale).roundToInt()), true,
            ).let { if (it.config == Bitmap.Config.ARGB_8888 && it.isMutable) it else it.copy(Bitmap.Config.ARGB_8888, true) }
        }
        copy.setHasAlpha(true)
        return copy
    }

    /**
     * Clears every pixel connected to the picture's edge whose colour is
     * within [tolerance] of the edge pixel the fill started from.
     */
    internal fun withoutBackground(bitmap: Bitmap, tolerance: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val limit = (tolerance.coerceIn(0, 100) * 441 / 100).let { it * it } // 441 ≈ the RGB cube's diagonal
        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val seedColor = IntArray(w * h)
        var head = 0
        var tail = 0
        fun seed(i: Int) {
            if (!seen[i]) {
                seen[i] = true
                seedColor[i] = pixels[i]
                queue[tail++] = i
            }
        }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
        while (head < tail) {
            val i = queue[head++]
            val reference = seedColor[i]
            val x = i % w
            val y = i / w
            fun visit(n: Int) {
                if (seen[n]) return
                val c = pixels[n]
                if (Color.alpha(c) < 8 || distance(c, reference) <= limit) {
                    seen[n] = true
                    seedColor[n] = reference
                    queue[tail++] = n
                }
            }
            if (x > 0) visit(i - 1)
            if (x < w - 1) visit(i + 1)
            if (y > 0) visit(i - w)
            if (y < h - 1) visit(i + w)
        }
        // Edge seeds that are not alike (a subject touching the edge) stay: only keep
        // a seed's clearing if most of the border agrees with its colour.
        val border = borderColor(pixels, w, h)
        for (i in 0 until tail) {
            val p = queue[i]
            if (distance(seedColor[p], border) <= limit) pixels[p] = 0
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /** The most common (coarsely bucketed) colour along the picture's border. */
    private fun borderColor(pixels: IntArray, w: Int, h: Int): Int {
        val counts = HashMap<Int, IntArray>()
        fun add(c: Int) {
            val key = (Color.red(c) shr 4 shl 8) or (Color.green(c) shr 4 shl 4) or (Color.blue(c) shr 4)
            val sum = counts.getOrPut(key) { IntArray(4) }
            sum[0]++; sum[1] += Color.red(c); sum[2] += Color.green(c); sum[3] += Color.blue(c)
        }
        for (x in 0 until w) { add(pixels[x]); add(pixels[(h - 1) * w + x]) }
        for (y in 0 until h) { add(pixels[y * w]); add(pixels[y * w + w - 1]) }
        val best = counts.values.maxByOrNull { it[0] } ?: return Color.WHITE
        return Color.rgb(best[1] / best[0], best[2] / best[0], best[3] / best[0])
    }

    private fun distance(a: Int, b: Int): Int {
        val r = Color.red(a) - Color.red(b)
        val g = Color.green(a) - Color.green(b)
        val bl = Color.blue(a) - Color.blue(b)
        return r * r + g * g + bl * bl
    }

    private fun clipped(bitmap: Bitmap, shape: Shape): Bitmap {
        val side = min(bitmap.width, bitmap.height)
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val rect = RectF(0f, 0f, side.toFloat(), side.toFloat())
        val path = Path().apply {
            if (shape == Shape.CIRCLE) addOval(rect, Path.Direction.CW)
            else addRoundRect(rect, side * 0.18f, side * 0.18f, Path.Direction.CW)
        }
        canvas.drawPath(path, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, (side - bitmap.width) / 2f, (side - bitmap.height) / 2f, paint)
        return out
    }

    /** Keeps only what lies inside [outline] (in [source]'s pixel coordinates); the rest becomes transparent. */
    fun cutToPath(source: Bitmap, outline: Path): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawPath(outline, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return out
    }

    /** Cuts away fully transparent rows and columns around the picture. */
    internal fun trimmed(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var left = w; var right = -1; var top = h; var bottom = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (Color.alpha(pixels[y * w + x]) > 8) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < left || bottom < top) return bitmap // nothing left: keep it as it is
        if (left == 0 && top == 0 && right == w - 1 && bottom == h - 1) return bitmap
        return Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
    }

    /** The default outline thickness, and its range on the slider. */
    const val OUTLINE = 12
    const val MIN_OUTLINE = 2
    const val MAX_OUTLINE = 40
    private const val OUTLINE_RING_STEP = 6f
    private const val MARGIN = 4
    private const val OUTLINE_STEPS = 32
    private const val EDGE_HALF = 0.15f
    private const val SHARPEN_MAX = 2.5f

    /** The longest title, and the room it takes beneath the picture (pixels of the 512 px sticker). */
    const val MAX_CAPTION = 40
    private const val CAPTION_BAND = 64
    private const val CAPTION_TEXT = 44f
    private const val CAPTION_TEXT_MIN = 22f

}
