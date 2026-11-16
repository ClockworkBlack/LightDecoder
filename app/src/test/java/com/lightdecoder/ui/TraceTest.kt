package com.lightdecoder.ui

import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.ui.sticker.DrawingScrollView
import com.lightdecoder.ui.sticker.LassoView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Tracing takes on the first try: fast strokes count, taps are explained, and the page does not steal the finger. */
@RunWith(AndroidJUnit4::class)
class TraceTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun lasso(): LassoView = LassoView(context).apply {
        measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
        layout(0, 0, 400, 400)
        setBitmap(Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888))
    }

    @Test
    fun aFastCircleOfFewEventsStillCuts() {
        val view = lasso()
        var traced: FloatArray? = null
        var tooSmall = false
        view.onTraced = { _, points -> traced = points }
        view.onTooSmall = { tooSmall = true }
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        view.onTouchEvent(down)
        // One move event carrying the whole loop as history, as a fast finger does.
        val move = MotionEvent.obtain(0, 10, MotionEvent.ACTION_MOVE, 300f, 100f, 0)
        move.addBatch(20, 300f, 300f, 1f, 1f, 0)
        move.addBatch(30, 100f, 300f, 1f, 1f, 0)
        view.onTouchEvent(move)
        view.onTouchEvent(MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, 100f, 120f, 0))
        assertNotNull("the loop was cut out", traced)
        assertTrue("every corner was kept: ${traced!!.size / 2} points", traced!!.size / 2 >= 4)
        assertFalse(tooSmall)
    }

    @Test
    fun aTapIsExplainedNotIgnored() {
        val view = lasso()
        var traced: FloatArray? = null
        var tooSmall = false
        view.onTraced = { _, points -> traced = points }
        view.onTooSmall = { tooSmall = true }
        view.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 100f, 100f, 0))
        view.onTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, 102f, 101f, 0))
        assertNull(traced)
        assertTrue(tooSmall)
    }

    @Test
    fun aLockedPageNeverTakesTheFinger() {
        val page = DrawingScrollView(context)
        page.locked = true
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        val move = MotionEvent.obtain(0, 10, MotionEvent.ACTION_MOVE, 10f, 300f, 0)
        assertFalse(page.onInterceptTouchEvent(down))
        assertFalse(page.onInterceptTouchEvent(move))
        assertFalse(page.onTouchEvent(move))
        page.locked = false
        assertEquals(false, page.locked)
    }
}
