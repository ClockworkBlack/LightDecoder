package com.lightdecoder.sticker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
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
 * is for). No network, no model download: the background removal is a flood
 * fill from the picture's edges, which suits plain backgrounds.
 */
object StickerMaker {

    const val SIZE = 512

    enum class Shape { ORIGINAL, ROUNDED, CIRCLE }

    data class Options(
        val removeBackground: Boolean = false,
        /** 0..100: how different from the edge colour a pixel may be and still count as background. */
        val tolerance: Int = 24,
        val shape: Shape = Shape.ORIGINAL,
        val outline: Boolean = true,
        /** White behind the sticker instead of transparency (for carriers that turn MMS pictures into JPEG). */
        val solidBackground: Boolean = false,
    )

    /** [source] is not changed or recycled. */
    fun make(source: Bitmap, options: Options): Bitmap {
        var work = scaledDown(source, SIZE * 2)
        if (options.removeBackground) work = withoutBackground(work, options.tolerance)
        if (options.shape != Shape.ORIGINAL) work = clipped(work, options.shape)
        work = trimmed(work)
        val border = if (options.outline) OUTLINE else 0
        val inner = SIZE - 2 * (border + MARGIN)
        val scale = min(inner.toFloat() / work.width, inner.toFloat() / work.height)
        val w = max(1, (work.width * scale).roundToInt())
        val h = max(1, (work.height * scale).roundToInt())
        val fitted = if (w == work.width && h == work.height) work else Bitmap.createScaledBitmap(work, w, h, true)
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        if (options.solidBackground) canvas.drawColor(Color.WHITE)
        val left = (SIZE - w) / 2f
        val top = (SIZE - h) / 2f
        if (options.outline) {
            val white = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            }
            // The picture's own silhouette in white, stamped around a circle: an even outline.
            for (step in 0 until OUTLINE_STEPS) {
                val angle = 2 * Math.PI * step / OUTLINE_STEPS
                canvas.drawBitmap(fitted, left + (cos(angle) * border).toFloat(), top + (sin(angle) * border).toFloat(), white)
            }
        }
        canvas.drawBitmap(fitted, left, top, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
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

    private const val OUTLINE = 12
    private const val MARGIN = 4
    private const val OUTLINE_STEPS = 32

}
