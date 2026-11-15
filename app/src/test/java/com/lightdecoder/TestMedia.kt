package com.lightdecoder

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertTrue
import kotlin.math.abs

/**
 * The sample files under src/test/resources/media, and what is in them.
 *
 * They are made by tools/make-test-media: a filled circle on a transparent
 * background, 150×110. In frame i of an animation the circle is centred at
 * (30 + 12 i, 55) and coloured (255 - 20 i, 60 + 20 i, 40).
 */
object TestMedia {
    const val WIDTH = 150
    const val HEIGHT = 110
    const val RADIUS = 25
    const val FRAMES = 8
    const val FRAME_MS = 80
    const val CENTRE_Y = 55

    fun bytes(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/media/$name")) { "missing test file $name" }
            .use { it.readBytes() }

    fun centreX(frame: Int) = 30 + 12 * frame

    fun circleColor(frame: Int) = Color.rgb(255 - 20 * frame, 60 + 20 * frame, 40)

    /** HEVC is lossy, so colours are compared with a little slack. */
    fun assertColorNear(message: String, expected: Int, actual: Int, tolerance: Int = 6) {
        val ok = abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
            abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
            abs(Color.blue(expected) - Color.blue(actual)) <= tolerance &&
            abs(Color.alpha(expected) - Color.alpha(actual)) <= tolerance
        assertTrue(
            "$message: expected ${hex(expected)} but was ${hex(actual)}", ok,
        )
    }

    /** Checks one decoded frame of the transparent files: circle in place, corners clear. */
    fun assertTransparentFrame(bitmap: Bitmap, frame: Int) {
        assertTrue("size ${bitmap.width}×${bitmap.height}", bitmap.width == WIDTH && bitmap.height == HEIGHT)
        assertColorNear("circle centre, frame $frame", circleColor(frame), bitmap.getPixel(centreX(frame), CENTRE_Y))
        assertTrue("top-left is transparent", Color.alpha(bitmap.getPixel(1, 1)) <= 6)
        assertTrue("bottom-right is transparent", Color.alpha(bitmap.getPixel(WIDTH - 2, HEIGHT - 2)) <= 6)
    }

    private fun hex(color: Int) = "#%08X".format(color)
}
