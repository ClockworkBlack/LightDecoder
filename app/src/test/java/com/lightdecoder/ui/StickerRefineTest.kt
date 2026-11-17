package com.lightdecoder.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.sticker.StickerMaker
import com.lightdecoder.ui.sticker.RefineView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The fine-tune editor: one finger paints, the stroke is kept in the picture's pixels, two fingers do not paint. */
@RunWith(AndroidJUnit4::class)
class StickerRefineTest {

    private fun picture(): Bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
        val c = Canvas(this)
        c.drawColor(Color.rgb(20, 200, 30))
        c.drawRect(60f, 60f, 140f, 140f, Paint().apply { color = Color.RED })
    }

    private fun view(): RefineView = RefineView(ApplicationProvider.getApplicationContext()).apply {
        measure(
            android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, 400, 400)
        brushSizePx = 20f
    }

    private fun touch(view: RefineView, action: Int, x: Float, y: Float, t: Long) {
        val e = MotionEvent.obtain(0, t, action, x, y, 0)
        view.onTouchEvent(e)
        e.recycle()
    }

    @Test
    fun aFingerStrokeErasesAndIsKeptInPictureCoordinates() {
        val original = picture()
        val v = view()
        v.setPictures(original, original.copy(Bitmap.Config.ARGB_8888, true))
        var changes = 0
        v.onStrokesChanged = { changes++ }

        // The 200 px picture is shown at 400 px: the middle row of the view is row 100 of the picture.
        touch(v, MotionEvent.ACTION_DOWN, 20f, 200f, 0)
        for (i in 1..20) touch(v, MotionEvent.ACTION_MOVE, 20f + 18f * i, 200f, 10L * i)
        touch(v, MotionEvent.ACTION_UP, 380f, 200f, 300)

        assertEquals(1, changes)
        val stroke = v.strokes.single()
        assertFalse(stroke.keep)
        assertEquals("20 screen px at 2x is 10 picture px", 10f, stroke.width, 0.5f)
        val cut = StickerMaker.cutOut(original, StickerMaker.Options(), StickerMaker.Edits(original, v.strokes))
        assertEquals(0, Color.alpha(cut.getPixel(100, 100)))
        assertEquals(Color.RED, cut.getPixel(100, 80))

        assertTrue(v.undo())
        assertTrue(v.strokes.isEmpty())
        assertFalse(v.undo())
    }

    @Test
    fun twoFingersZoomInsteadOfPainting() {
        val original = picture()
        val v = view()
        v.setPictures(original, original.copy(Bitmap.Config.ARGB_8888, true))
        val start = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, 150f, 200f, 0)
        v.onTouchEvent(down)
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0 },
            MotionEvent.PointerProperties().apply { id = 1 },
        )
        fun coords(a: Float, b: Float) = arrayOf(
            MotionEvent.PointerCoords().apply { x = a; y = 200f },
            MotionEvent.PointerCoords().apply { x = b; y = 200f },
        )
        val second = MotionEvent.obtain(
            start, start + 10, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, props, coords(150f, 250f), 0, 0, 1f, 1f, 0, 0, 0, 0,
        )
        v.onTouchEvent(second)
        val spread = MotionEvent.obtain(start, start + 50, MotionEvent.ACTION_MOVE, 2, props, coords(100f, 300f), 0, 0, 1f, 1f, 0, 0, 0, 0)
        v.onTouchEvent(spread)
        val up2 = MotionEvent.obtain(
            start, start + 60, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, props, coords(100f, 300f), 0, 0, 1f, 1f, 0, 0, 0, 0,
        )
        v.onTouchEvent(up2)
        v.onTouchEvent(MotionEvent.obtain(start, start + 70, MotionEvent.ACTION_UP, 100f, 200f, 0))
        assertTrue("no stroke from a pinch", v.strokes.isEmpty())
    }

    private fun stroke(v: RefineView, y: Float, t0: Long) {
        touch(v, MotionEvent.ACTION_DOWN, 20f, y, t0)
        for (i in 1..10) touch(v, MotionEvent.ACTION_MOVE, 20f + 30f * i, y, t0 + 10L * i)
        touch(v, MotionEvent.ACTION_UP, 320f, y, t0 + 200)
    }

    @Test
    fun undoneStrokesCanBeRedoneUntilANewOneIsDrawn() {
        val original = picture()
        val v = view()
        v.setPictures(original, original.copy(Bitmap.Config.ARGB_8888, true))
        stroke(v, 100f, 0)
        stroke(v, 200f, 1_000)
        val second = v.strokes[1]
        assertFalse(v.canRedo)

        assertTrue(v.undo())
        assertEquals(1, v.strokes.size)
        assertTrue(v.canRedo)
        assertTrue(v.redo())
        assertEquals(2, v.strokes.size)
        assertTrue("the same stroke is back", v.strokes[1] === second)
        assertFalse(v.redo())

        // "Clear brushing" can be taken back, stroke by stroke, in order.
        val before = v.strokes.toList()
        v.clearByUser()
        assertTrue(v.strokes.isEmpty())
        assertTrue(v.redo())
        assertTrue(v.redo())
        assertEquals(before, v.strokes)

        // A new stroke after an undo: the undone one cannot come back on top of it.
        v.undo()
        stroke(v, 300f, 2_000)
        assertFalse(v.canRedo)
    }
}
