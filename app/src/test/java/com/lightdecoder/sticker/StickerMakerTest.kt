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
}
