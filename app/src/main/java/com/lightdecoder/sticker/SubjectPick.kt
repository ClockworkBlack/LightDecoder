package com.lightdecoder.sticker

import android.graphics.Bitmap
import java.util.PriorityQueue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Narrows what the smart cut found to the people or objects the user
 * tapped. A "keep" tap keeps the piece it is on and drops every piece
 * without one; a "remove" tap drops its piece. When a kept and a removed
 * tap are on the same piece (two people side by side, touching), the piece
 * is divided between them: each part of it goes to the tap it can be
 * reached from without crossing a change of colour, so the dividing line
 * falls along the edge between the two.
 */
object SubjectPick {

    /**
     * [keep] and [remove] are x,y pairs in [picture]'s pixels. Returns
     * [mask] itself when no tap lands on anything.
     */
    fun apply(mask: SubjectMask, picture: Bitmap, keep: FloatArray, remove: FloatArray): SubjectMask {
        if (keep.size < 2 && remove.size < 2) return mask
        val scale = min(1f, GRID.toFloat() / max(mask.width, mask.height))
        val gw = max(1, (mask.width * scale).roundToInt())
        val gh = max(1, (mask.height * scale).roundToInt())
        val n = gw * gh
        val inside = BooleanArray(n) { i ->
            val x = ((i % gw + 0.5f) * mask.width / gw).toInt().coerceAtMost(mask.width - 1)
            val y = ((i / gw + 0.5f) * mask.height / gh).toInt().coerceAtMost(mask.height - 1)
            (mask.confidence[y * mask.width + x].toInt() and 0xFF) > INSIDE
        }
        val small = Bitmap.createScaledBitmap(picture, gw, gh, true)
        val colour = IntArray(n)
        small.getPixels(colour, 0, gw, 0, 0, gw, gh)
        if (small !== picture) small.recycle()

        fun seeds(points: FloatArray): List<Int> = (0 until points.size / 2).mapNotNull { p ->
            val gx = (points[2 * p] * gw / picture.width).toInt().coerceIn(0, gw - 1)
            val gy = (points[2 * p + 1] * gh / picture.height).toInt().coerceIn(0, gh - 1)
            nearestInside(inside, gw, gh, gx, gy)
        }
        val kept = seeds(keep)
        val removed = seeds(remove)
        if (kept.isEmpty() && removed.isEmpty()) return mask

        // Each cell of the subject goes to the tap that reaches it most cheaply.
        val owner = ByteArray(n) // 0 none, KEEP, REMOVE
        val cost = FloatArray(n) { Float.MAX_VALUE }
        val queue = PriorityQueue<Long>() // cost (as int bits of a non-negative float) above, cell below
        fun start(cell: Int, who: Byte) {
            cost[cell] = 0f
            owner[cell] = who
            queue.add(cell.toLong())
        }
        kept.forEach { start(it, KEEP) }
        removed.forEach { if (owner[it] != KEEP) start(it, REMOVE) }
        while (queue.isNotEmpty()) {
            val entry = queue.poll()!!
            val cell = (entry and 0xFFFFFFFFL).toInt()
            val at = Float.fromBits((entry ushr 32).toInt())
            if (at > cost[cell]) continue
            val x = cell % gw
            val y = cell / gw
            fun step(next: Int) {
                if (!inside[next]) return
                val c = at + 1f + COLOUR_WEIGHT * distance(colour[cell], colour[next])
                if (c < cost[next]) {
                    cost[next] = c
                    owner[next] = owner[cell]
                    queue.add((c.toBits().toLong() shl 32) or next.toLong())
                }
            }
            if (x > 0) step(cell - 1)
            if (x < gw - 1) step(cell + 1)
            if (y > 0) step(cell - gw)
            if (y < gh - 1) step(cell + gw)
        }
        // A piece no tap reached: dropped when the user said what to keep, kept when
        // they only said what to remove.
        val untouched = if (kept.isEmpty()) KEEP else REMOVE
        for (i in 0 until n) if (inside[i] && owner[i] == NONE) owner[i] = untouched
        // The soft edge around the subject (hair, say) follows the piece next to it.
        val spread = IntArray(n)
        var head = 0
        var tail = 0
        for (i in 0 until n) if (inside[i]) spread[tail++] = i
        while (head < tail) {
            val cell = spread[head++]
            val x = cell % gw
            val y = cell / gw
            fun reach(next: Int) {
                if (owner[next] == NONE) {
                    owner[next] = owner[cell]
                    spread[tail++] = next
                }
            }
            if (x > 0) reach(cell - 1)
            if (x < gw - 1) reach(cell + 1)
            if (y > 0) reach(cell - gw)
            if (y < gh - 1) reach(cell + gw)
        }

        // Back at full size, with a smooth line between kept and dropped.
        val out = ByteArray(mask.confidence.size)
        for (y in 0 until mask.height) {
            val fy = ((y + 0.5f) * gh / mask.height - 0.5f).coerceIn(0f, gh - 1f)
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, gh - 1)
            val ty = fy - y0
            for (x in 0 until mask.width) {
                val fx = ((x + 0.5f) * gw / mask.width - 0.5f).coerceIn(0f, gw - 1f)
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, gw - 1)
                val tx = fx - x0
                fun k(cx: Int, cy: Int) = if (owner[cy * gw + cx] == KEEP) 1f else 0f
                val share = (k(x0, y0) * (1 - tx) + k(x1, y0) * tx) * (1 - ty) + (k(x0, y1) * (1 - tx) + k(x1, y1) * tx) * ty
                val t = ((share - 0.3f) / 0.4f).coerceIn(0f, 1f)
                val i = y * mask.width + x
                out[i] = ((mask.confidence[i].toInt() and 0xFF) * t).roundToInt().toByte()
            }
        }
        return SubjectMask(mask.width, mask.height, out)
    }

    /** True when a tap at [x], [y] of [picture] is on, or right beside, something [mask] found. */
    fun lands(mask: SubjectMask?, picture: Bitmap?, x: Float, y: Float): Boolean {
        if (mask == null || picture == null) return false
        val mx = (x * mask.width / picture.width).toInt()
        val my = (y * mask.height / picture.height).toInt()
        val reach = max(2, max(mask.width, mask.height) * REACH / GRID)
        for (cy in max(0, my - reach)..min(mask.height - 1, my + reach) step 2) {
            for (cx in max(0, mx - reach)..min(mask.width - 1, mx + reach) step 2) {
                if ((mask.confidence[cy * mask.width + cx].toInt() and 0xFF) > INSIDE) return true
            }
        }
        return false
    }

    /** The cell of the subject at, or closest to, a tap; null when the tap is nowhere near it. */
    private fun nearestInside(inside: BooleanArray, gw: Int, gh: Int, gx: Int, gy: Int): Int? {
        if (inside[gy * gw + gx]) return gy * gw + gx
        var best: Int? = null
        var bestDistance = Int.MAX_VALUE
        for (y in max(0, gy - REACH)..min(gh - 1, gy + REACH)) for (x in max(0, gx - REACH)..min(gw - 1, gx + REACH)) {
            if (!inside[y * gw + x]) continue
            val d = (x - gx) * (x - gx) + (y - gy) * (y - gy)
            if (d < bestDistance) {
                bestDistance = d
                best = y * gw + x
            }
        }
        return best
    }

    /** How different two colours are, 0..1. */
    private fun distance(a: Int, b: Int): Float {
        val r = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val g = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val bl = (a and 0xFF) - (b and 0xFF)
        return kotlin.math.sqrt((r * r + g * g + bl * bl).toFloat()) / 441f
    }

    private const val NONE: Byte = 0
    private const val KEEP: Byte = 1
    private const val REMOVE: Byte = 2
    private const val GRID = 256
    private const val INSIDE = 60
    private const val REACH = 10
    private const val COLOUR_WEIGHT = 80f
}
