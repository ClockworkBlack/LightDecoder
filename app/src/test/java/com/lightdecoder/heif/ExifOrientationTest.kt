package com.lightdecoder.heif

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.chat.AttachmentPrep
import com.lightdecoder.ui.chat.MediaLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A camera photo stored sideways with an EXIF "rotate 90°" note is shown and
 * sent upright everywhere LightDecoder decodes pictures itself.
 */
@RunWith(AndroidJUnit4::class)
class ExifOrientationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /**
     * A JPEG as a phone camera writes a portrait photo: stored 80 wide and 40
     * high (the sensor's landscape), red on its left half, blue on its right,
     * with EXIF orientation [orientation]. Upright (turned 90° clockwise) it
     * is 40 wide and 80 high, red on top.
     */
    private fun cameraJpeg(orientation: Int, name: String = "camera.jpg"): File {
        val stored = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        for (x in 0 until 80) for (y in 0 until 40) stored.setPixel(x, y, if (x < 40) Color.RED else Color.BLUE)
        val file = File(context.cacheDir, name)
        file.outputStream().use { stored.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return file
    }

    private fun isRed(color: Int) = Color.red(color) > 200 && Color.blue(color) < 60
    private fun isBlue(color: Int) = Color.blue(color) > 200 && Color.red(color) < 60

    private fun assertUpright(bitmap: Bitmap) {
        assertEquals("portrait width", 40, bitmap.width)
        assertEquals("portrait height", 80, bitmap.height)
        assertTrue("red on top", isRed(bitmap.getPixel(20, 10)))
        assertTrue("blue at the bottom", isBlue(bitmap.getPixel(20, 70)))
    }

    @Test
    fun theStickerMakerAndTheViewerGetThePhotoUpright() {
        val bytes = cameraJpeg(ExifInterface.ORIENTATION_ROTATE_90).readBytes()
        // The sticker maker and the sticker library decode through this.
        assertUpright(Media.decodeFirstFrame(bytes))
        // The viewer.
        assertUpright(Media.decode(bytes).frames.single().bitmap)
    }

    @Test
    fun conversationsShowItUpright() {
        val bytes = cameraJpeg(ExifInterface.ORIENTATION_ROTATE_90).readBytes()
        assertUpright(MediaLoader.decodePreview(bytes, 1000)!!)
    }

    @Test
    fun aPhotoShrunkBeforeSendingIsSentUpright() {
        val file = cameraJpeg(ExifInterface.ORIENTATION_ROTATE_90, "send.jpg")
        // Limits that force a rewrite (the photo is "too big").
        val prepared = AttachmentPrep.prepare(file, "image/jpeg", "send.jpg", AttachmentPrep.Limits(maxSide = 60, maxBytes = 800L * 1024))
        assertNotNull("rewritten", prepared)
        val sent = BitmapFactory.decodeFile(file.path)
        assertTrue("taller than wide, without needing a note", sent.height > sent.width)
        assertTrue("red on top", isRed(sent.getPixel(sent.width / 2, sent.height / 8)))
    }

    @Test
    fun everyOrientationTurnsTheRightWay() {
        // Stored as the camera wrote it; what the top-left of the upright picture is.
        val stored = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            setPixel(0, 0, Color.RED) // marks the stored top-left
        }
        fun turned(o: Int) = ExifOrientation.apply(stored.copy(Bitmap.Config.ARGB_8888, true), o)
        assertEquals(Color.RED, turned(ExifInterface.ORIENTATION_NORMAL).getPixel(0, 0))
        turned(ExifInterface.ORIENTATION_ROTATE_90).let { assertEquals(2, it.width); assertEquals(Color.RED, it.getPixel(1, 0)) }
        turned(ExifInterface.ORIENTATION_ROTATE_180).let { assertEquals(4, it.width); assertEquals(Color.RED, it.getPixel(3, 1)) }
        turned(ExifInterface.ORIENTATION_ROTATE_270).let { assertEquals(2, it.width); assertEquals(Color.RED, it.getPixel(0, 3)) }
        turned(ExifInterface.ORIENTATION_FLIP_HORIZONTAL).let { assertEquals(Color.RED, it.getPixel(3, 0)) }
        turned(ExifInterface.ORIENTATION_FLIP_VERTICAL).let { assertEquals(Color.RED, it.getPixel(0, 1)) }
        turned(ExifInterface.ORIENTATION_TRANSPOSE).let { assertEquals(2, it.width); assertEquals(Color.RED, it.getPixel(0, 0)) }
        turned(ExifInterface.ORIENTATION_TRANSVERSE).let { assertEquals(2, it.width); assertEquals(Color.RED, it.getPixel(1, 3)) }
    }

    @Test
    fun aPictureWithoutANoteOrAHeicIsLeftAsItIs() {
        val plain = cameraJpeg(ExifInterface.ORIENTATION_NORMAL, "plain.jpg").readBytes()
        assertEquals(80, Media.decodeFirstFrame(plain).width)
        assertEquals(ExifInterface.ORIENTATION_NORMAL, ExifOrientation.of(ByteArray(0)))
        // HEIC applies its own rotation in its decoder: the EXIF note is not used again.
        val heic = javaClass.classLoader!!.getResourceAsStream("media/still_opaque.heic")!!.readBytes()
        assertEquals(ExifInterface.ORIENTATION_NORMAL, ExifOrientation.of(heic))
    }
}
