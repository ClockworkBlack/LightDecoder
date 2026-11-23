package com.lightdecoder.imessage.facetime

/**
 * The shape of the call's picture-in-picture window, as OpenBubbles 2.0.0 makes it: the other side's
 * picture as it is shown ([Media.remoteShown]); with several tiles, the grid's shape; 9:16 while
 * nothing is known. Kept within what Android takes (2.39:1 to 1:2.39: anything else is refused).
 * Pure; the ratio as a numerator over [DENOMINATOR], for `android.util.Rational`. Tested in FaceTimeGroupTest.
 */
object PipParams {

    /** The widest and the tallest shape Android takes for a picture-in-picture window. */
    const val MAX_RATIO = 2.39

    const val DENOMINATOR = 10_000

    /** 2.39:1, and 1:2.39 rounded up (so it is never past the limit). */
    private const val MAX_NUMERATOR = 23_900
    private const val MIN_NUMERATOR = 4_185

    /** 9:16, an upright phone's picture. */
    private const val DEFAULT_NUMERATOR = 5_625

    /** The numerator over [DENOMINATOR] of the window's shape for a picture of [width] x [height] (as shown), or of [tiles] tiles. */
    fun aspect(width: Int, height: Int, tiles: Int = 1): Int {
        val ratio = when {
            tiles > 1 -> TileLayout.grid(tiles).let { (columns, rows) -> columns.toDouble() / rows }
            width > 0 && height > 0 -> width.toDouble() / height
            else -> return DEFAULT_NUMERATOR
        }
        return Math.round(ratio * DENOMINATOR).toInt().coerceIn(MIN_NUMERATOR, MAX_NUMERATOR)
    }
}
