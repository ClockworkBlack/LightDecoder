package com.lightdecoder.ui.chat

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.LruCache
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.lightdecoder.R
import java.io.File

/**
 * Letter avatars for people and conversations. The phone sends a colour per
 * conversation; when it does not, one is picked from [PALETTE] by a hash of
 * the conversation id, so the same chat keeps the same colour.
 *
 * With a picture (an iMessage group's photo, a person's contact picture), the picture in a circle
 * instead: decoded no larger than the avatar ([photo]: its size read first), and kept decoded for
 * the next time it is drawn; the initials when it cannot be read.
 */
object Avatars {

    /** Decoded pictures, by file, its time and the size drawn: a list scrolls without decoding them again. */
    private val photos = object : LruCache<String, Bitmap>(PHOTO_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Pictures that are none (or too large to read), so they are not read again each time a row is drawn. */
    private val unreadable = object : LruCache<String, Boolean>(64) {}

    /**
     * [file] as a square picture [sizePx] pixels on a side (its centre, as an avatar shows it),
     * decoded no larger than needed: its size read first, nothing decoded past [MAX_PHOTO_PIXELS].
     * Kept decoded for the next time; null when it is no picture.
     */
    fun photo(file: File, sizePx: Int): Bitmap? {
        val side = sizePx.coerceIn(1, MAX_PHOTO_SIDE)
        val key = "${file.path}#${file.lastModified()}#${file.length()}#$side"
        photos.get(key)?.let { return it }
        if (unreadable.get(key) == true) return null
        val bitmap = try {
            decodeSquare(file, side)
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: RuntimeException) {
            null
        }
        if (bitmap == null) unreadable.put(key, true) else photos.put(key, bitmap)
        return bitmap
    }

    private fun decodeSquare(file: File, side: Int): Bitmap? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PHOTO_PIXELS) return null
        var sample = 1
        while (minOf(w, h) / (sample * 2) >= side) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val edge = minOf(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(decoded, (decoded.width - edge) / 2, (decoded.height - edge) / 2, edge, edge)
        return if (edge == side) square else Bitmap.createScaledBitmap(square, side, side, true)
    }

    /** The size an avatar [view] is drawn at: its own, else a list's. */
    private fun sizeOf(view: TextView): Int =
        view.layoutParams?.width?.takeIf { it > 0 } ?: view.width.takeIf { it > 0 } ?: (DEFAULT_SIDE_DP * view.resources.displayMetrics.density).toInt()

    private const val PHOTO_CACHE_BYTES = 8 * 1024 * 1024
    private const val MAX_PHOTO_PIXELS = 50_000_000L
    private const val MAX_PHOTO_SIDE = 1024
    private const val DEFAULT_SIDE_DP = 40

    /** Dark enough that white initials stay readable (large text, ≥ 3:1) in light and dark themes. */
    private val PALETTE = intArrayOf(
        0xFF1565C0.toInt(), // blue
        0xFF2E7D32.toInt(), // green
        0xFFC62828.toInt(), // red
        0xFF6A1B9A.toInt(), // purple
        0xFF00695C.toInt(), // teal
        0xFFAD1457.toInt(), // pink
        0xFF4527A0.toInt(), // deep purple
        0xFF283593.toInt(), // indigo
        0xFF5D4037.toInt(), // brown
        0xFF37474F.toInt(), // blue grey
        0xFF006064.toInt(), // cyan
        0xFFBF360C.toInt(), // deep orange
    )

    /**
     * Up to two letters: the first letter of the first and of the last word.
     * A name that is a phone number gets "#", a blank one "?".
     */
    fun initials(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return "?"
        val first = words.first()
        val firstCp = first.codePointAt(0)
        if (!Character.isLetter(firstCp)) {
            return if (first.first() == '+' || Character.isDigit(firstCp) || first.first() == '(') "#" else "?"
        }
        val a = String(Character.toChars(firstCp)).uppercase()
        if (words.size == 1) return a
        val lastCp = words.last().codePointAt(0)
        if (!Character.isLetter(lastCp)) return a
        return a + String(Character.toChars(lastCp)).uppercase()
    }

    /** The phone's colour ("#RRGGBB") when it gave a usable one, else a stable pick for [key]. */
    fun colorFor(key: String, phoneColor: String): Int {
        parseColor(phoneColor)?.let { return it }
        return PALETTE[Math.floorMod(key.hashCode(), PALETTE.size)]
    }

    /** White or black, whichever reads better on [background]. */
    fun textColorOn(background: Int): Int {
        val opaque = ColorUtils.setAlphaComponent(background, 255)
        val white = ColorUtils.calculateContrast(Color.WHITE, opaque)
        val black = ColorUtils.calculateContrast(Color.BLACK, opaque)
        return if (white >= black) Color.WHITE else Color.BLACK
    }

    /**
     * The same avatar as a drawable of [sizePx] (a coloured circle with the
     * initials, or [photo] in a circle), for places that take a drawable, such
     * as a toolbar's logo.
     */
    fun drawable(
        context: android.content.Context,
        name: String,
        key: String,
        phoneColor: String,
        sizePx: Int,
        photo: File? = null,
    ): android.graphics.drawable.Drawable {
        photo?.let { photo(it, sizePx) }?.let { picture ->
            return RoundedBitmapDrawableFactory.create(context.resources, picture).apply { isCircular = true }
        }
        return initialsBitmap(name, key, phoneColor, sizePx).let { android.graphics.drawable.BitmapDrawable(context.resources, it) }
    }

    /** [photo] cut to a circle [sizePx] across (a notification's picture), or null when it is no picture. */
    fun circlePhoto(photo: File, sizePx: Int): Bitmap? {
        val square = photo(photo, sizePx) ?: return null
        val out = Bitmap.createBitmap(square.width, square.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            shader = android.graphics.BitmapShader(square, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        }
        val r = square.width / 2f
        canvas.drawCircle(r, r, r, paint)
        return out
    }

    /** [name]'s initials on a circle in its colour, [sizePx] across (a notification's picture, a toolbar's logo). */
    fun initialsBitmap(name: String, key: String, phoneColor: String, sizePx: Int): Bitmap {
        val background = colorFor(key, phoneColor)
        val bitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val r = sizePx / 2f
        canvas.drawCircle(r, r, r, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = background })
        val text = initials(name)
        if (text.isNotEmpty()) {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = textColorOn(background)
                textSize = sizePx * 0.42f
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            }
            canvas.drawText(text, r, r - (paint.descent() + paint.ascent()) / 2f, paint)
        }
        return bitmap
    }

    /**
     * Shows [photo] in a circle on [view], or, without one (or when it cannot be read), [name]'s
     * initials on a circle in its colour. The view needs @drawable/bg_avatar.
     */
    fun bind(view: TextView, name: String, key: String, phoneColor: String, photo: File? = null) {
        val picture = photo?.let { photo(it, sizeOf(view)) }
        if (picture != null) {
            view.text = ""
            view.backgroundTintList = null
            view.background = RoundedBitmapDrawableFactory.create(view.resources, picture).apply { isCircular = true }
            return
        }
        val color = colorFor(key, phoneColor)
        view.text = initials(name)
        view.setBackgroundResource(R.drawable.bg_avatar)
        view.backgroundTintList = ColorStateList.valueOf(color)
        view.setTextColor(textColorOn(color))
    }

    internal fun parseColor(text: String): Int? {
        val t = text.trim()
        if (!t.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) return null
        return try {
            Color.parseColor(t)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
