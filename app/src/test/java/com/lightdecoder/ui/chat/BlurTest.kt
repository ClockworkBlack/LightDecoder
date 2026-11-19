package com.lightdecoder.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurTest {

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test
    fun aPassAveragesItsNeighboursAndKeepsAnEvenColour() {
        val w = 9
        val h = 3
        val flat = IntArray(w * h) { gray(120) }
        val out = IntArray(w * h)
        Blur.pass(flat, out, w, h, 2, horizontal = true)
        assertTrue("an even colour stays as it was", out.all { it == gray(120) })

        // One white column in black: spread over the five around it, a fifth each; nothing beyond.
        val line = IntArray(w * h) { if (it % w == 4) gray(250) else gray(0) }
        Blur.pass(line, out, w, h, 2, horizontal = true)
        for (y in 0 until h) {
            for (x in 0 until w) {
                assertEquals("x=$x y=$y", if (x in 2..6) gray(50) else gray(0), out[y * w + x])
            }
        }
        // Down the columns: a white row in black likewise.
        val row = IntArray(w * 5) { if (it / w == 2) gray(250) else gray(0) }
        val down = IntArray(w * 5)
        Blur.pass(row, down, w, 5, 1, horizontal = false)
        assertEquals(gray(83), down[1 * w])
        assertEquals(gray(83), down[3 * w])
        assertEquals(gray(0), down[0])
        assertEquals("alpha is averaged too, an opaque picture stays opaque", 0xFF, down[2 * w] ushr 24)
    }
}
