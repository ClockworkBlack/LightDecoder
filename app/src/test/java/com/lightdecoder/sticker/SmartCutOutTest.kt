package com.lightdecoder.sticker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The smart cut-out (the model that finds a picture's subject), the title under a sticker, and the smooth traced cut. */
@RunWith(AndroidJUnit4::class)
class SmartCutOutTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** A photograph of a coffee cup on a saucer, on a table (CC0, from scikit-image's sample pictures). */
    private fun photo(): Bitmap =
        javaClass.getResourceAsStream("/pictures/coffee.jpg")!!.use { BitmapFactory.decodeStream(it) }

    /** A portrait: a person in an orange suit in front of a flag (NASA, public domain, from scikit-image's sample pictures). */
    private fun portrait(): Bitmap =
        javaClass.getResourceAsStream("/pictures/astronaut.jpg")!!.use { BitmapFactory.decodeStream(it) }

    private fun alphaAt(mask: SubjectMask, x: Int, y: Int) = mask.confidence[y * mask.width + x].toInt() and 0xFF

    @Test
    fun aPersonIsCutOutWholeByThePeopleModel() {
        val picture = portrait()
        val result = SubjectCutter.find(context, picture)
        assertTrue("the models shipped in the app ran: $result", result is SubjectCutter.Result.Found)
        val subjects = (result as SubjectCutter.Result.Found).subjects
        val people = subjects.people!!
        assertEquals(picture.width, people.width)
        assertEquals(picture.height, people.height)
        assertTrue("people are what Auto cuts by when there are any", subjects.pick(StickerMaker.SmartKind.AUTO) === people)
        val w = picture.width
        val h = picture.height
        // Her face, and her body below it, down to the bottom of the picture ...
        assertTrue("face", alphaAt(people, w * 43 / 100, h * 22 / 100) > 200)
        assertTrue("chest", alphaAt(people, w * 35 / 100, h * 60 / 100) > 200)
        assertTrue("body at the bottom edge", alphaAt(people, w * 30 / 100, h * 95 / 100) > 200)
        // ... but not the flag behind her or the wall.
        assertTrue("flag", alphaAt(people, w * 3 / 100, h * 10 / 100) < 40)
        assertTrue("wall", alphaAt(people, w * 90 / 100, h * 8 / 100) < 40)
        val kept = people.confidence.count { (it.toInt() and 0xFF) > 127 }
        assertTrue("a whole person, not a face: $kept of ${people.confidence.size}", kept > people.confidence.size / 4)
    }

    @Test
    fun theModelFindsThePhotosSubject() {
        val picture = photo()
        val result = SubjectCutter.find(context, picture)
        assertTrue("the model shipped in the app ran: $result", result is SubjectCutter.Result.Found)
        val subjects = (result as SubjectCutter.Result.Found).subjects
        assertFalse("no people in a picture of a cup", subjects.people!!.hasSubject)
        assertEquals(null, subjects.pick(StickerMaker.SmartKind.PEOPLE))
        val mask = subjects.pick(StickerMaker.SmartKind.AUTO)!!
        assertTrue("so Auto cuts by what stands out", mask === subjects.objects)
        assertEquals(picture.width, mask.width)
        assertEquals(picture.height, mask.height)
        assertTrue(mask.hasSubject)
        // The cup, in the middle of the picture, is kept ...
        assertTrue(alphaAt(mask, picture.width / 2, picture.height * 2 / 5) > 200)
        // ... and the table in the corners is not.
        for ((x, y) in listOf(4 to 4, picture.width - 5 to 4, 4 to picture.height - 5, picture.width - 5 to picture.height - 5)) {
            assertTrue("corner $x,$y", alphaAt(mask, x, y) < 40)
        }
        // A crisp edge: nearly every pixel is fully in or fully out.
        val between = mask.confidence.count { (it.toInt() and 0xFF) in 60..195 }
        assertTrue("$between unsure pixels of ${mask.confidence.size}", between < mask.confidence.size / 12)

        val sticker = StickerMaker.make(picture, StickerMaker.Options(removeBackground = true, smart = true, outline = false), subject = mask)
        assertEquals(0, Color.alpha(sticker.getPixel(2, 2)))
        assertTrue(Color.alpha(sticker.getPixel(StickerMaker.SIZE / 2, StickerMaker.SIZE / 2)) > 200)
    }

    @Test
    fun theSubjectMaskDecidesWhatStays() {
        val picture = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        // The left half is the subject, even though the whole picture is one plain colour.
        val mask = SubjectMask(100, 100, ByteArray(100 * 100) { if (it % 100 < 50) 255.toByte() else 0 })
        val cut = StickerMaker.cutOut(picture, StickerMaker.Options(removeBackground = true, smart = true), subject = mask)
        assertEquals(255, Color.alpha(cut.getPixel(10, 50)))
        assertEquals(0, Color.alpha(cut.getPixel(90, 50)))
        // Without a mask (the model could not run), the plain-background cut is what happens.
        val plain = StickerMaker.cutOut(picture, StickerMaker.Options(removeBackground = true, smart = true))
        assertEquals(0, Color.alpha(plain.getPixel(10, 50)))
        // A mask of another size is stretched over the picture.
        val small = SubjectMask(2, 1, byteArrayOf(255.toByte(), 0))
        val stretched = StickerMaker.cutOut(picture, StickerMaker.Options(removeBackground = true, smart = true), subject = small)
        assertEquals(255, Color.alpha(stretched.getPixel(10, 50)))
        assertEquals(0, Color.alpha(stretched.getPixel(90, 50)))
    }

    @Test
    fun keepMoreAndKeepLessMoveTheLine() {
        val picture = Bitmap.createBitmap(3, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        // Sure, unsure, and nearly sure it is background.
        val mask = SubjectMask(3, 1, byteArrayOf(250.toByte(), 75, 5))
        fun cut(keep: Int) = StickerMaker.cutOut(picture, StickerMaker.Options(removeBackground = true, smart = true, smartKeep = keep), subject = mask)
            .let { c -> (0 until 3).map { Color.alpha(c.getPixel(it, 0)) } }
        assertEquals(listOf(255, 0, 0), cut(50))
        assertEquals("keep more takes the unsure part too", listOf(255, 255, 0), cut(100).map { if (it > 200) 255 else it })
        assertEquals("keep less still keeps what is sure", 255, cut(0)[0])
        assertEquals(255, cut(0)[0])
    }

    @Test
    fun theKindChosenDecidesTheMask() {
        fun mask(vararg levels: Int) = SubjectMask(levels.size, 1, ByteArray(levels.size) { levels[it].toByte() })
        val people = mask(255, 0, 0, 0)
        val objects = mask(0, 255, 0, 0)
        val subjects = Subjects(people, objects)
        assertTrue(subjects.pick(StickerMaker.SmartKind.PEOPLE) === people)
        assertTrue(subjects.pick(StickerMaker.SmartKind.OBJECTS) === objects)
        assertTrue(subjects.pick(StickerMaker.SmartKind.AUTO) === people)
        val both = subjects.pick(StickerMaker.SmartKind.BOTH)!!
        assertEquals(listOf(255, 255, 0, 0), both.confidence.map { it.toInt() and 0xFF })
        // No people: Auto goes by what stands out, and People has nothing.
        val none = Subjects(mask(0, 0, 0, 0), objects)
        assertTrue(none.pick(StickerMaker.SmartKind.AUTO) === objects)
        assertEquals(null, none.pick(StickerMaker.SmartKind.PEOPLE))
        // One model could not run: the other still serves.
        assertTrue(Subjects(null, objects).pick(StickerMaker.SmartKind.BOTH) === objects)
    }

    @Test
    fun aPictureWithNoSubjectSaysSo() {
        assertFalse(SubjectMask(10, 10, ByteArray(100)).hasSubject)
        assertTrue(SubjectMask(10, 10, ByteArray(100) { 255.toByte() }).hasSubject)
    }

    @Test
    fun aTitleSitsBeneathThePictureInWhite() {
        val picture = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val plain = StickerMaker.make(picture, StickerMaker.Options(outline = false))
        val titled = StickerMaker.make(picture, StickerMaker.Options(outline = false, caption = "Hello there"))
        fun Bitmap.rowsWhere(match: (Int) -> Boolean): List<Int> =
            (0 until height).filter { y -> (0 until width).any { x -> match(getPixel(x, y)) } }
        val blue = { c: Int -> Color.alpha(c) > 200 && Color.blue(c) > 200 && Color.red(c) < 60 }
        val white = { c: Int -> Color.alpha(c) > 200 && Color.red(c) > 235 && Color.green(c) > 235 && Color.blue(c) > 235 }
        assertTrue("no white without a title", plain.rowsWhere(white).isEmpty())
        val letters = titled.rowsWhere(white)
        assertTrue("white letters", letters.isNotEmpty())
        assertTrue("beneath the picture", letters.first() > titled.rowsWhere(blue).last())
        assertTrue("inside the sticker", letters.last() < StickerMaker.SIZE)
        assertTrue("the picture makes room", titled.rowsWhere(blue).size < plain.rowsWhere(blue).size)
        // A long title still fits across.
        val long = StickerMaker.make(picture, StickerMaker.Options(outline = false, caption = "W".repeat(60)))
        assertEquals(0, Color.alpha(long.getPixel(0, long.rowsWhere(white).first())))
        assertEquals(StickerMaker.MAX_CAPTION, StickerMaker.captionOf("W".repeat(60)).length)
        assertEquals("two lines", StickerMaker.captionOf("  two\nlines "))
    }

    @Test
    fun theTitleAndTheSmartCutAreRemembered() {
        val edits = StickerProject.Edits(StickerMaker.Options(
                removeBackground = true, smart = true, smartKind = StickerMaker.SmartKind.PEOPLE, smartKeep = 70, caption = "Mood",
            ),
            null, emptyList(),
        )
        val back = StickerProject.fromJson(StickerProject.toJson(edits))
        assertEquals(edits.options, back.options)
        // A sticker saved before these existed has neither.
        val old = StickerProject.fromJson(JSONObject().put("removeBackground", true))
        assertFalse(old.options.smart)
        assertEquals(StickerMaker.SmartKind.AUTO, old.options.smartKind)
        assertEquals(50, old.options.smartKeep)
        assertEquals("", old.options.caption)
    }

    @Test
    fun aTracedCutFollowsASmoothCurve() {
        val picture = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        // A square drawn with four points: cut straight, its corners are sharp.
        val square = floatArrayOf(40f, 40f, 160f, 40f, 160f, 160f, 40f, 160f)
        val straight = StickerMaker.cutToPath(picture, StickerProject.pathOf(square, close = true))
        val smooth = StickerMaker.cutToPath(picture, StickerProject.outlineOf(square))
        assertEquals(255, Color.alpha(straight.getPixel(42, 42)))
        assertEquals("the corner is rounded off", 0, Color.alpha(smooth.getPixel(42, 42)))
        assertEquals("the middle stays", 255, Color.alpha(smooth.getPixel(100, 100)))
        assertEquals(0, Color.alpha(smooth.getPixel(10, 10)))
        assertNotEquals(0, Color.alpha(smooth.getPixel(100, 45)))
    }
}
