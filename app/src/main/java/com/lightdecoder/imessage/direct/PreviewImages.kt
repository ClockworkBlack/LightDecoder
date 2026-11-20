package com.lightdecoder.imessage.direct

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import com.lightdecoder.heif.ExifOrientation
import com.lightdecoder.heif.HeifCodec
import com.lightdecoder.heif.ImageSniffer
import com.lightdecoder.heif.Media
import com.lightdecoder.ui.chat.MediaLoader
import java.io.ByteArrayOutputStream

/**
 * Small pictures that go with a message but are no attachment of it: a link preview's picture
 * (as it is sent, and as one comes: [jpeg], at most [LINK_SIDE] on its longest side) and a sticker
 * someone put on a message ([png], transparency kept, at most [STICKER_SIDE]).
 *
 * Whatever comes in is anyone's to make, so nothing of it is trusted: at most [MAX_SOURCE_BYTES]
 * are looked at, its size is read first (never more than [MAX_PIXELS]), it is subsampled while it
 * decodes, and what is kept is drawn again here as a new file. Android graphics only.
 */
object PreviewImages {

    /** A link preview's picture's longest side, as it is sent and kept. */
    const val LINK_SIDE = 600

    /** A sticker's longest side, as it is kept to show on a bubble or a tapback. */
    const val STICKER_SIDE = 320

    /** The most bytes of a picture looked at. */
    const val MAX_SOURCE_BYTES = 3 * 1024 * 1024

    /** The most pixels a picture may have to be decoded at all. */
    const val MAX_PIXELS = 40_000_000L

    private const val JPEG_QUALITY = 85

    /** [bytes] (JPEG, PNG, WebP, GIF's first frame, HEIC) as a JPEG at most [maxSide] on its longest side; null when it is no picture or too large. */
    fun jpeg(bytes: ByteArray, maxSide: Int = LINK_SIDE): ByteArray? = encode(bytes, maxSide, Bitmap.CompressFormat.JPEG, JPEG_QUALITY)

    /** [bytes] as a PNG (transparency kept) at most [maxSide] on its longest side; null when it is no picture or too large. */
    fun png(bytes: ByteArray, maxSide: Int = STICKER_SIDE): ByteArray? = encode(bytes, maxSide, Bitmap.CompressFormat.PNG, 100)

    private fun encode(bytes: ByteArray, maxSide: Int, format: Bitmap.CompressFormat, quality: Int): ByteArray? {
        val bitmap = decode(bytes, maxSide) ?: return null
        return try {
            // A JPEG has no transparency: what was see-through shows on white.
            val drawn = if (format == Bitmap.CompressFormat.JPEG && bitmap.hasAlpha()) onWhite(bitmap) else bitmap
            ByteArrayOutputStream().use { out ->
                if (!drawn.compress(format, quality, out)) return null
                out.toByteArray().takeIf { it.isNotEmpty() }
            }
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    /** [bytes] decoded upright, at most [maxSide] on its longest side; null when it is no picture, larger than allowed, or memory ran out. */
    fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
        if (bytes.isEmpty() || bytes.size > MAX_SOURCE_BYTES) return null
        return try {
            if (ImageSniffer.isHeif(bytes)) decodeHeif(bytes, maxSide) else decodeBounded(bytes, maxSide)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "a picture too large to decode was left out")
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeBounded(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) return null
        val options = BitmapFactory.Options().apply { inSampleSize = MediaLoader.sampleSize(w, h, maxSide) }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return Media.scaledToFit(ExifOrientation.upright(bytes, decoded), maxSide)
    }

    private fun decodeHeif(bytes: ByteArray, maxSide: Int): Bitmap? {
        val info = HeifCodec.probe(bytes)
        if (info.width <= 0 || info.height <= 0) return null
        val pixels = info.width.toLong() * info.height
        if (pixels > MAX_PIXELS) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !info.hasAlpha && !info.isSequence) {
            MediaLoader.systemDecode(bytes, maxSide)?.let { return it }
        }
        // Decoded whole (transparency, or before Android 9): only a picture that small.
        if (pixels > BackgroundPosters.MAX_FULL_DECODE_PIXELS) return null
        return Media.scaledToFit(HeifCodec.decodeStill(bytes), maxSide)
    }

    private fun onWhite(bitmap: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return out
    }

    private const val TAG = "PreviewImages"
}
