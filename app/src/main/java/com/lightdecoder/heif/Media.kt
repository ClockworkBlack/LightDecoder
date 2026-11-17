package com.lightdecoder.heif

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Decodes any picture the app is handed: HEIC/HEIF through the built-in
 * decoder, everything else (PNG, JPEG, GIF, WebP, AVIF) through Android's own.
 */
object Media {

    class Decoded(
        val frames: List<HeifFrame>,
        /** Only set for HEIC/HEIF files. */
        val heif: HeifInfo?,
        val width: Int,
        val height: Int,
        val hasAlpha: Boolean,
        val extension: String,
        val bytes: Int,
    ) {
        val animated: Boolean get() = frames.size > 1

        val summary: String
            get() = buildString {
                append(if (animated) "Animated" else extension.uppercase())
                append(" · ${width}×$height")
                if (hasAlpha) append(" · transparent")
                append(" · ${bytes / 1024} KB")
            }
    }

    /** Decodes every frame. Throws [HeifCodec.DecodeException] if the bytes are not a readable picture. */
    fun decode(bytes: ByteArray): Decoded {
        val extension = ImageSniffer.extensionFor(bytes, null)
        if (ImageSniffer.isHeif(bytes)) {
            val info = HeifCodec.probe(bytes)
            val frames = HeifCodec.decodeAll(bytes)
            val first = frames.first().bitmap
            return Decoded(frames, info, first.width, first.height, info.hasAlpha, extension, bytes.size)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?.let { ExifOrientation.upright(bytes, it) }
            ?: throw HeifCodec.DecodeException("This file is not a picture LightDecoder can read")
        return Decoded(
            listOf(HeifFrame(bitmap, 0)), null, bitmap.width, bitmap.height, bitmap.hasAlpha(),
            extension, bytes.size,
        )
    }

    /** Decodes only the first frame, for previews. */
    fun decodeFirstFrame(bytes: ByteArray): Bitmap =
        if (ImageSniffer.isHeif(bytes)) {
            HeifCodec.decodeStill(bytes)
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?.let { ExifOrientation.upright(bytes, it) }
                ?: throw HeifCodec.DecodeException("This file is not a picture LightDecoder can read")
        }

    /** Returns [source] itself if it already fits, otherwise a smaller copy. */
    fun scaledToFit(source: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxSide) return source
        val scale = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }
}
