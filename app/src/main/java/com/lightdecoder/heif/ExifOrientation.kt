package com.lightdecoder.heif

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

/**
 * Turns a decoded picture the way its EXIF orientation says.
 *
 * Phone cameras store a photo as the sensor saw it and write "rotate this by
 * 90°" (or a mirror) as a separate EXIF note. Android's BitmapFactory ignores
 * that note, so a portrait photo came out sideways wherever LightDecoder
 * decoded a JPEG (or WebP/PNG) with it: the sticker maker, the viewer, the
 * conversations, and a photo shrunk before sending (re-saved without the note).
 *
 * HEIF is left alone: its decoder (libheif, and Android's ImageDecoder)
 * already applies the file's own rotation, and its EXIF note must then be
 * ignored, or the picture would be turned twice.
 */
object ExifOrientation {

    /** The EXIF orientation of [bytes]; normal for HEIF, for no note, or for anything unreadable. */
    fun of(bytes: ByteArray): Int {
        if (ImageSniffer.isHeif(bytes)) return ExifInterface.ORIENTATION_NORMAL
        val value = try {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        // 0 is "undefined"; anything outside 1..8 is not an orientation.
        return if (value in 1..8) value else ExifInterface.ORIENTATION_NORMAL
    }

    /** [bitmap], decoded from [bytes], turned upright. [bitmap] itself when nothing needs turning. */
    fun upright(bytes: ByteArray, bitmap: Bitmap): Bitmap = apply(bitmap, of(bytes))

    /** [bitmap] turned as EXIF [orientation] says; [bitmap] itself for normal or unknown values. */
    fun apply(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> return bitmap
        }
        return try {
            val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (turned !== bitmap) bitmap.recycle()
            turned
        } catch (e: OutOfMemoryError) {
            // Sideways is better than nothing.
            bitmap
        }
    }
}
