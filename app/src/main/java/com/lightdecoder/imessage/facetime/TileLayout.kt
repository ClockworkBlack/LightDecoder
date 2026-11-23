package com.lightdecoder.imessage.facetime

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where the pictures of a call with several people go on the call screen, as OpenBubbles 2.0.0 lays
 * them out (its `layoutSlots`): one picture fills the screen; more are square tiles, at the number of
 * columns that makes them biggest, each row centred. A tile tapped fills the screen ([focused]), the
 * others small along the top. Pure: sizes in pixels in, places out. Tested in FaceTimeGroupTest.
 */
object TileLayout {

    /** One tile's place: its left and top edge, and its size. */
    data class Slot(val x: Int, val y: Int, val width: Int, val height: Int)

    /** The most pictures that are decoded at once: camera tiles, and one shared screen besides. */
    const val MAX_CAMERA_TILES = 6
    const val MAX_SCREEN_TILES = 1

    /**
     * The places of [count] tiles in a [width] x [height] area, [margin] from its edges and [gap]
     * between tiles: one fills it all; more are squares (see the object's notes). Empty for none or
     * for an area of no size.
     */
    fun slots(count: Int, width: Int, height: Int, margin: Int, gap: Int): List<Slot> {
        if (count <= 0 || width <= 0 || height <= 0) return emptyList()
        if (count == 1) return listOf(Slot(0, 0, width, height))
        val innerW = width - 2 * margin
        val innerH = height - 2 * margin
        var bestColumns = 1
        var bestSide = -1
        for (columns in 1..count) {
            val rows = (count + columns - 1) / columns
            val side = min((innerW - (columns - 1) * gap) / columns, (innerH - (rows - 1) * gap) / rows)
            if (side > bestSide) {
                bestSide = side
                bestColumns = columns
            }
        }
        val side = max(bestSide, 0)
        val rows = (count + bestColumns - 1) / bestColumns
        val gridH = rows * side + (rows - 1) * gap
        val top = (height - gridH) / 2
        val out = ArrayList<Slot>(count)
        for (row in 0 until rows) {
            val inRow = min(bestColumns, count - row * bestColumns)
            val rowW = inRow * side + (inRow - 1) * gap
            val left = (width - rowW) / 2
            for (i in 0 until inRow) out += Slot(left + i * (side + gap), top + row * (side + gap), side, side)
        }
        return out
    }

    /**
     * The places of [count] tiles with tile [focus] filling the [width] x [height] area and the
     * others as [thumb]-sized squares in rows from [top] down, [gap] apart and from the edges.
     * [focus] outside the tiles: as [slots].
     */
    fun focused(count: Int, focus: Int, width: Int, height: Int, thumb: Int, gap: Int, top: Int, margin: Int = gap): List<Slot> {
        if (focus !in 0 until count) return slots(count, width, height, margin, gap)
        if (width <= 0 || height <= 0) return emptyList()
        val perRow = max(1, (width - gap) / (thumb + gap))
        var placed = 0
        return List(count) { i ->
            if (i == focus) {
                Slot(0, 0, width, height)
            } else {
                val at = placed++
                Slot(gap + (at % perRow) * (thumb + gap), top + (at / perRow) * (thumb + gap), thumb, thumb)
            }
        }
    }

    /**
     * Where the user's own small picture ([cardWidth] x [cardHeight]) settles once let go at
     * [x], [y] (its left and top edge) in a [width] x [height] area: the nearest corner, [margin] in
     * from the sides, [top] from the top and [bottom] from the bottom (clear of the call's buttons).
     */
    fun nearestCorner(
        x: Float,
        y: Float,
        width: Int,
        height: Int,
        cardWidth: Int,
        cardHeight: Int,
        margin: Int,
        top: Int = margin,
        bottom: Int = margin,
    ): Pair<Int, Int> {
        val left = margin
        val right = max(margin, width - cardWidth - margin)
        val topEdge = top
        val bottom = max(top, height - cardHeight - bottom)
        val cx = x + cardWidth / 2f
        val cy = y + cardHeight / 2f
        return (if (cx < width / 2f) left else right) to (if (cy < height / 2f) topEdge else bottom)
    }

    /** The columns and rows a grid of [count] square tiles takes as near a square as it can be (for its shape in picture-in-picture). */
    fun grid(count: Int): Pair<Int, Int> {
        if (count <= 1) return 1 to 1
        val columns = ceil(sqrt(count.toDouble())).toInt()
        return columns to (count + columns - 1) / columns
    }
}
