package com.lightdecoder.sticker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Choosing one of several people or objects the smart cut found, by tapping. */
@RunWith(AndroidJUnit4::class)
class SubjectPickTest {

    private fun picture(draw: Canvas.(Paint) -> Unit): Bitmap =
        Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GRAY)
            Canvas(this).draw(Paint())
        }

    private fun mask(inside: (x: Int, y: Int) -> Boolean) =
        SubjectMask(200, 100, ByteArray(200 * 100) { if (inside(it % 200, it / 200)) 255.toByte() else 0 })

    private fun SubjectMask.at(x: Int, y: Int) = confidence[y * width + x].toInt() and 0xFF

    /** Two things apart from each other: one on the left, one on the right. */
    private val apart = mask { x, y -> y in 20..80 && (x in 20..70 || x in 130..180) }
    private val plain = picture { }

    @Test
    fun aTapKeepsThatOneAndDropsTheOthers() {
        val picked = SubjectPick.apply(apart, plain, keep = floatArrayOf(45f, 50f), remove = FloatArray(0))
        assertEquals(255, picked.at(45, 50))
        assertEquals(255, picked.at(25, 25))
        assertEquals("the other one is gone", 0, picked.at(155, 50))
        // Both tapped: both stay.
        val both = SubjectPick.apply(apart, plain, floatArrayOf(45f, 50f, 155f, 50f), FloatArray(0))
        assertEquals(255, both.at(45, 50))
        assertEquals(255, both.at(155, 50))
    }

    @Test
    fun aRemoveTapDropsOnlyThatOne() {
        val picked = SubjectPick.apply(apart, plain, keep = FloatArray(0), remove = floatArrayOf(155f, 50f))
        assertEquals("the rest stays", 255, picked.at(45, 50))
        assertEquals(0, picked.at(155, 50))
    }

    @Test
    fun twoThatTouchAreDividedWhereTheirColoursMeet() {
        // One piece to the smart cut: a red person against a blue one, meeting off-centre at x = 120.
        val touching = mask { x, y -> y in 20..80 && x in 20..180 }
        val picture = picture { paint ->
            paint.color = Color.RED
            drawRect(0f, 0f, 120f, 100f, paint)
            paint.color = Color.BLUE
            drawRect(120f, 0f, 200f, 100f, paint)
        }
        val picked = SubjectPick.apply(touching, picture, keep = floatArrayOf(40f, 50f), remove = floatArrayOf(170f, 50f))
        assertEquals("the red one, all of it", 255, picked.at(40, 50))
        assertEquals(255, picked.at(110, 30))
        assertEquals("the blue one, all of it", 0, picked.at(130, 70))
        assertEquals(0, picked.at(170, 50))
        // With only the keep tap they are one piece, and all of it stays.
        val whole = SubjectPick.apply(touching, picture, floatArrayOf(40f, 50f), FloatArray(0))
        assertEquals(255, whole.at(170, 50))
    }

    @Test
    fun aTapOnNothingChangesNothing() {
        assertSame(apart, SubjectPick.apply(apart, plain, floatArrayOf(100f, 5f), FloatArray(0)))
        assertSame(apart, SubjectPick.apply(apart, plain, FloatArray(0), FloatArray(0)))
        assertFalse(SubjectPick.lands(apart, plain, 100f, 5f))
        assertTrue(SubjectPick.lands(apart, plain, 45f, 50f))
        assertTrue("right beside counts", SubjectPick.lands(apart, plain, 17f, 50f))
        assertFalse(SubjectPick.lands(null, plain, 45f, 50f))
    }

    @Test
    fun tappedThingsAreKeptAndRemovedWhateverWasFound() {
        fun mask(columns: IntRange) = SubjectMask(10, 1, ByteArray(10) { if (it in columns) 255.toByte() else 0 })
        fun levels(m: SubjectMask?) = m!!.confidence.map { if ((it.toInt() and 0xFF) > 127) 1 else 0 }.joinToString("")
        val found = mask(0..3)
        val person = mask(2..5)
        val bottle = mask(8..9)
        // Keep taps say what stays: the person and the bottle they hold, which was never found.
        assertEquals("0011110011", levels(TapCutter.combine(found, listOf(person, bottle), emptyList())))
        // A remove tap takes its thing out of that.
        assertEquals("0011000011", levels(TapCutter.combine(found, listOf(person, bottle), listOf(mask(4..5)))))
        // Only remove taps: what was found, less those.
        assertEquals("1100000000", levels(TapCutter.combine(found, emptyList(), listOf(person))))
        // Nothing found, but a tap: the tap is enough.
        assertEquals("0000000011", levels(TapCutter.combine(null, listOf(bottle), emptyList())))
        assertEquals(null, TapCutter.combine(null, emptyList(), listOf(bottle)))
    }

    @Test
    fun theTapModelFindsTheThingUnderATap() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        // A person holding a helmet in her lap (NASA, public domain).
        val picture = javaClass.getResourceAsStream("/pictures/astronaut.jpg")!!.use { android.graphics.BitmapFactory.decodeStream(it) }
        val w = picture.width
        val h = picture.height
        val taps = TapCutter.open(context, picture)
        assertTrue("the model shipped in the app ran", taps != null)
        try {
            val helmet = taps!!.at(w * 0.72f, h * 0.86f)!!
            assertEquals(w, helmet.width)
            assertEquals(h, helmet.height)
            assertTrue("the helmet under the tap", helmet.at(w * 72 / 100, h * 86 / 100) > 200)
            assertTrue("and not her face", helmet.at(w * 43 / 100, h * 22 / 100) < 40)
            assertTrue("or the flag", helmet.at(w * 3 / 100, h * 10 / 100) < 40)
            val face = taps.at(w * 0.43f, h * 0.22f)!!
            assertTrue("her, under a tap on her", face.at(w * 43 / 100, h * 22 / 100) > 200)
            assertTrue(face.at(w * 3 / 100, h * 10 / 100) < 40)
        } finally {
            taps?.close()
        }
        assertEquals("nothing after it is closed", null, taps!!.at(w * 0.72f, h * 0.86f))
    }

    @Test
    fun theTapsAreRememberedWithTheSticker() {
        val edits = StickerProject.Edits(
            StickerMaker.Options(removeBackground = true, smart = true), null, emptyList(),
            keepTaps = floatArrayOf(1f, 2f, 3f, 4f), removeTaps = floatArrayOf(9f, 8f),
        )
        val back = StickerProject.fromJson(StickerProject.toJson(edits))
        assertEquals(listOf(1f, 2f, 3f, 4f), back.keepTaps.toList())
        assertEquals(listOf(9f, 8f), back.removeTaps.toList())
        val none = StickerProject.fromJson(StickerProject.toJson(StickerProject.Edits(StickerMaker.Options(), null, emptyList())))
        assertTrue(none.keepTaps.isEmpty() && none.removeTaps.isEmpty())
    }
}
