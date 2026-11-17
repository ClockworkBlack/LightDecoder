package com.lightdecoder.sticker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** A sticker or picture someone sent (from an iPhone or an Android phone) kept among the user's own stickers. */
@RunWith(AndroidJUnit4::class)
class ReceivedStickerTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() = StickerLibrary.list(context).forEach { StickerLibrary.delete(context, it) }

    @After
    fun tearDown() = StickerLibrary.list(context).forEach { StickerLibrary.delete(context, it) }

    /** A see-through sticker: a red disc on nothing, as a sent sticker usually is. */
    private fun sentSticker(size: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawCircle(size / 2f, size / 2f, size / 3f, Paint().apply { color = Color.RED })
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test
    fun aSentStickerBecomesOneOfMine() {
        val file = StickerLibrary.addPicture(context, sentSticker(300))
        assertNotNull(file)
        assertEquals(listOf(file), StickerLibrary.list(context))
        assertTrue(StickerLibrary.owns(context, file!!))
        val saved = BitmapFactory.decodeFile(file.path)
        assertTrue("fits a sticker", saved.width <= StickerMaker.SIZE && saved.height <= StickerMaker.SIZE)
    }

    @Test
    fun itCanBeEditedLikeOneIMade() {
        val file = StickerLibrary.addPicture(context, sentSticker(1500))!!
        val project = StickerProject.load(context, file)
        assertNotNull("kept with its picture, so the editor can open it", project)
        assertTrue(project!!.original.width <= StickerMaker.SIZE * 2)
        assertFalse("no outline is added to someone else's sticker", project.edits.options.outline)
        assertNull(project.edits.trace)
    }

    @Test
    fun somethingThatIsNotAPictureIsNotKept() {
        assertNull(StickerLibrary.addPicture(context, ByteArray(0)))
        assertNull(StickerLibrary.addPicture(context, "not a picture".toByteArray()))
        assertTrue(StickerLibrary.list(context).isEmpty())
    }
}
