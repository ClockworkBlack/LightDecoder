package com.lightdecoder.ui.chat

import android.graphics.Bitmap

/** A small software blur, for what Android 11 and earlier cannot blur themselves ([ReplyThreadPanel]). */
object Blur {

    /** How many box passes: three are close to a Gaussian blur. */
    private const val PASSES = 3

    /** Blurs [bitmap] (mutable, ARGB_8888) in place: [PASSES] box blurs of [radius] pixels, across then down. */
    fun boxBlur(bitmap: Bitmap, radius: Int) {
        val w = bitmap.width
        val h = bitmap.height
        if (radius < 1 || w < 1 || h < 1) return
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val scratch = IntArray(w * h)
        repeat(PASSES) {
            pass(pixels, scratch, w, h, radius, horizontal = true)
            pass(scratch, pixels, w, h, radius, horizontal = false)
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    /** One box blur of [src] into [dst] along rows ([horizontal]) or columns; the edges are repeated. */
    internal fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        val window = 2 * radius + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val k = i.coerceIn(0, length - 1)
                return if (horizontal) src[line * w + k] else src[k * w + line]
            }
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val c = at(i)
                a += c ushr 24
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
            }
            for (i in 0 until length) {
                val out = ((a / window) shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
                val leaving = at(i - radius)
                val entering = at(i + radius + 1)
                a += (entering ushr 24) - (leaving ushr 24)
                r += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
                g += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
                b += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }
}
