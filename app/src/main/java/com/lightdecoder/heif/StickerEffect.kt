package com.lightdecoder.heif

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The effect an iPhone user picked for a sticker (Outline, Comic, Puffy,
 * Shiny).
 *
 * iPhones do not bake the effect into the picture. The file holds the plain
 * cut-out plus an XMP note, `stickerEffect:type="stroke"`, and Messages on the
 * iPhone draws the effect when it shows the sticker. So a decoder that stops
 * at the pixels shows less than the sender saw.
 */
enum class StickerEffect(val label: String, val canDraw: Boolean) {
    NONE("None", false),
    OUTLINE("Outline", true),
    COMIC("Comic", false),
    PUFFY("Puffy", false),
    SHINY("Shiny", false),
    OTHER("Unknown", false);

    companion object {
        private val MARKER = "stickerEffect:type=\"".toByteArray(Charsets.US_ASCII)

        /** Reads the effect note from the file's metadata. */
        fun detect(bytes: ByteArray): StickerEffect {
            val at = indexOf(bytes, MARKER)
            if (at < 0) return NONE
            val start = at + MARKER.size
            val name = StringBuilder()
            var i = start
            while (i < bytes.size && i < start + 32) {
                val c = bytes[i].toInt().toChar()
                if (c == '"') break
                if (!c.isLetterOrDigit()) return OTHER
                name.append(c)
                i++
            }
            return when (name.toString()) {
                "" -> NONE
                "stroke" -> OUTLINE
                "comic" -> COMIC
                "puffy" -> PUFFY
                "iridescent" -> SHINY
                else -> OTHER
            }
        }

        /**
         * Draws the Outline effect: a white border that follows the edge of the
         * cut-out. The result is larger than [source] by the border on each
         * side. [source] is left untouched.
         */
        fun drawOutline(source: Bitmap): Bitmap {
            val radius = max(2, (max(source.width, source.height) * 0.022f).roundToInt())
            val out = Bitmap.createBitmap(
                source.width + radius * 2, source.height + radius * 2, Bitmap.Config.ARGB_8888,
            )
            val canvas = Canvas(out)
            val silhouette = source.extractAlpha()
            val white = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = Color.WHITE }

            // Stamp the white silhouette around a circle; together the stamps
            // form the silhouette grown outward by `radius`.
            val steps = max(16, (2 * PI * radius / 1.5).toInt())
            for (step in 0 until steps) {
                val angle = 2 * PI * step / steps
                canvas.drawBitmap(
                    silhouette,
                    radius + (radius * cos(angle)).toFloat(),
                    radius + (radius * sin(angle)).toFloat(),
                    white,
                )
            }
            canvas.drawBitmap(silhouette, radius.toFloat(), radius.toFloat(), white)
            silhouette.recycle()

            canvas.drawBitmap(source, radius.toFloat(), radius.toFloat(), null)
            return out
        }

        private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
            if (needle.isEmpty() || haystack.size < needle.size) return -1
            val first = needle[0]
            val last = haystack.size - needle.size
            var i = 0
            while (i <= last) {
                if (haystack[i] == first) {
                    var j = 1
                    while (j < needle.size && haystack[i + j] == needle[j]) j++
                    if (j == needle.size) return i
                }
                i++
            }
            return -1
        }
    }
}
