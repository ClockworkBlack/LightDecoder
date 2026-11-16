package com.lightdecoder.sticker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StickerMakerTest {

    /** A red square in the middle of a plain green picture. */
    private fun redOnGreen(): Bitmap {
        val b = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(20, 200, 30))
        c.drawRect(60f, 60f, 140f, 140f, Paint().apply { color = Color.RED })
        return b
    }

    @Test
    fun aPlainBackgroundIsCutAwayAndTheSubjectKept() {
        val cut = StickerMaker.withoutBackground(redOnGreen(), 24)
        assertEquals(0, Color.alpha(cut.getPixel(5, 5)))
        assertEquals(0, Color.alpha(cut.getPixel(195, 100)))
        assertEquals(Color.RED, cut.getPixel(100, 100))
        val trimmed = StickerMaker.trimmed(cut)
        assertEquals(80, trimmed.width)
        assertEquals(80, trimmed.height)
    }

    @Test
    fun aStickerIsASquarePngSizedPictureWithAWhiteOutlineAroundTheSubject() {
        val made = StickerMaker.make(redOnGreen(), StickerMaker.Options(removeBackground = true, outline = true))
        assertEquals(StickerMaker.SIZE, made.width)
        assertEquals(StickerMaker.SIZE, made.height)
        val mid = StickerMaker.SIZE / 2
        assertEquals(Color.RED, made.getPixel(mid, mid))
        // Just outside the subject: the outline. The corner: nothing.
        assertEquals(Color.WHITE, made.getPixel(10, mid))
        assertEquals(0, Color.alpha(made.getPixel(0, 0)))
    }

    @Test
    fun theWhiteBackgroundLeavesNoTransparency() {
        val made = StickerMaker.make(
            redOnGreen(),
            StickerMaker.Options(removeBackground = true, shape = StickerMaker.Shape.CIRCLE, solidBackground = true),
        )
        assertEquals(Color.WHITE, made.getPixel(0, 0))
        assertEquals(255, Color.alpha(made.getPixel(StickerMaker.SIZE - 1, StickerMaker.SIZE - 1)))
    }

    @Test
    fun withoutCuttingThePictureIsKeptWhole() {
        val made = StickerMaker.make(redOnGreen(), StickerMaker.Options(outline = false))
        // Fitted to the square (minus the small margin): green reaches almost to the edge.
        assertEquals(Color.rgb(20, 200, 30), made.getPixel(8, 8))
    }

    @Test
    fun tracingAroundASubjectCutsEverythingElseAway() {
        val outline = android.graphics.Path().apply { addRect(50f, 50f, 150f, 150f, android.graphics.Path.Direction.CW) }
        val cut = StickerMaker.cutToPath(redOnGreen(), outline)
        assertEquals(0, Color.alpha(cut.getPixel(10, 10)))
        assertEquals(Color.RED, cut.getPixel(100, 100))
        assertEquals(Color.rgb(20, 200, 30), cut.getPixel(55, 100))
        // As a sticker: the traced shape with its outline, nothing of the frame around it.
        val made = StickerMaker.make(cut, StickerMaker.Options(outline = true))
        assertEquals(0, Color.alpha(made.getPixel(0, 0)))
        assertEquals(Color.RED, made.getPixel(StickerMaker.SIZE / 2, StickerMaker.SIZE / 2))
    }

    @Test
    fun theLibraryStoresPngFilesNewestFirstAndDeletesOnlyItsOwn() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val made = StickerMaker.make(redOnGreen(), StickerMaker.Options(removeBackground = true))
        val file = StickerLibrary.save(context, made)
        assertTrue(file.isFile && file.name.endsWith(".png"))
        val read = BitmapFactory.decodeFile(file.path)
        assertEquals(StickerMaker.SIZE, read.width)
        assertTrue(read.hasAlpha())
        assertEquals(listOf(file), StickerLibrary.list(context))
        val outside = File(context.filesDir, "other.png").apply { writeText("x") }
        assertFalse(StickerLibrary.delete(context, outside))
        assertTrue(outside.exists())
        assertTrue(StickerLibrary.delete(context, file))
        assertTrue(StickerLibrary.list(context).isEmpty())
    }

    @Test
    fun eraseStrokesRemoveAndKeepStrokesBringThePictureBack() {
        val picture = redOnGreen()
        // Background removal took the green away; a Keep stroke along the top brings a band of it back,
        // and an Erase stroke across the middle cuts through the red square.
        val keep = StickerMaker.Stroke(android.graphics.Path().apply { moveTo(20f, 20f); lineTo(180f, 20f) }, 10f, keep = true)
        val erase = StickerMaker.Stroke(android.graphics.Path().apply { moveTo(20f, 100f); lineTo(180f, 100f) }, 10f, keep = false)
        val cut = StickerMaker.cutOut(
            picture, StickerMaker.Options(removeBackground = true), StickerMaker.Edits(picture, listOf(keep, erase)),
        )
        assertEquals("kept", Color.rgb(20, 200, 30), cut.getPixel(100, 20))
        assertEquals("still removed elsewhere", 0, Color.alpha(cut.getPixel(100, 40)))
        assertEquals("erased", 0, Color.alpha(cut.getPixel(100, 100)))
        assertEquals("the rest of the subject stays", Color.RED, cut.getPixel(100, 80))

        // Without background removal, erasing alone cuts the picture.
        val erased = StickerMaker.cutOut(picture, StickerMaker.Options(), StickerMaker.Edits(picture, listOf(erase)))
        assertEquals(0, Color.alpha(erased.getPixel(30, 100)))
        assertEquals(Color.rgb(20, 200, 30), erased.getPixel(30, 30))
    }

    @Test
    fun strokesFollowThePictureWhenItIsScaledDown() {
        // A picture larger than the working size: strokes in its own coordinates still land in the same place.
        val big = Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val erase = StickerMaker.Stroke(android.graphics.Path().apply { moveTo(0f, 1024f); lineTo(2048f, 1024f) }, 40f, keep = false)
        val cut = StickerMaker.cutOut(big, StickerMaker.Options(), StickerMaker.Edits(big, listOf(erase)))
        assertEquals(StickerMaker.SIZE * 2, cut.width)
        assertEquals(0, Color.alpha(cut.getPixel(cut.width / 2, cut.height / 2)))
        assertEquals(Color.BLUE, cut.getPixel(cut.width / 2, 100))
        // And the sticker made from it is cut too (both halves remain, apart).
        val sticker = StickerMaker.make(big, StickerMaker.Options(outline = false), StickerMaker.Edits(big, listOf(erase)))
        assertEquals(0, Color.alpha(sticker.getPixel(StickerMaker.SIZE / 2, StickerMaker.SIZE / 2)))
    }

    @Test
    fun theOutlineCanBeThinOrThick() {
        fun whitePixels(width: Int): Int {
            val sticker = StickerMaker.make(redOnGreen(), StickerMaker.Options(removeBackground = true, outline = true, outlineWidth = width))
            val pixels = IntArray(sticker.width * sticker.height)
            sticker.getPixels(pixels, 0, sticker.width, 0, 0, sticker.width, sticker.height)
            return pixels.count { Color.alpha(it) > 200 && Color.red(it) > 240 && Color.green(it) > 240 && Color.blue(it) > 240 }
        }
        val thin = whitePixels(StickerMaker.MIN_OUTLINE)
        val normal = whitePixels(StickerMaker.OUTLINE)
        val thick = whitePixels(StickerMaker.MAX_OUTLINE)
        assertTrue("thin $thin < normal $normal < thick $thick", thin in 1 until normal && normal < thick)
        // Thick has no gaps: the band right around the subject is solid white.
        val sticker = StickerMaker.make(redOnGreen(), StickerMaker.Options(removeBackground = true, outline = true, outlineWidth = StickerMaker.MAX_OUTLINE))
        val y = sticker.height / 2
        var x = 0
        while (x < sticker.width && Color.alpha(sticker.getPixel(x, y)) < 200) x++
        for (i in 0 until StickerMaker.MAX_OUTLINE - 4) {
            val c = sticker.getPixel(x + i, y)
            assertTrue("solid at ${x + i}", Color.alpha(c) > 200 && Color.green(c) > 200)
        }
    }

    @Test
    fun stickersAddedFromTheGalleryJoinTheLibrary() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        StickerLibrary.list(context).forEach { StickerLibrary.delete(context, it) }
        val png = File(context.cacheDir, "saved-sticker.png").apply {
            outputStream().use { redOnGreen().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val notAPicture = File(context.cacheDir, "notes.txt").apply { writeText("hello") }
        val added = StickerLibrary.import(context, listOf(android.net.Uri.fromFile(png), android.net.Uri.fromFile(notAPicture)))
        assertEquals(1, added)
        val library = StickerLibrary.list(context)
        assertEquals(1, library.size)
        val kept = BitmapFactory.decodeFile(library.single().path)
        assertEquals(Color.RED, kept.getPixel(kept.width / 2, kept.height / 2))
        assertTrue(StickerLibrary.delete(context, library.single()))
        assertTrue(StickerLibrary.list(context).isEmpty())
        assertFalse("only the library's own files", StickerLibrary.delete(context, png))
    }
}
