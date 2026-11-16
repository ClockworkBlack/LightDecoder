package com.lightdecoder.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.lightdecoder.heif.HeifCodec
import com.lightdecoder.heif.ImageSniffer
import com.lightdecoder.ui.chat.MediaLoader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Makes a picture the user attached one that gets through and that every
 * phone can show, before it is queued (for either service):
 *  - HEIC/HEIF (iPhone and many Android cameras) becomes JPEG, or PNG when it
 *    is transparent (a sticker), since many Android phones cannot show HEIC
 *    at all: the very problem this app exists for. Animated HEIC is left
 *    as it is (a still would lose the animation).
 *  - A picture larger than the conversation allows ([Limits]) is scaled
 *    down and compressed until it fits. This matters above all for MMS
 *    (an SMS conversation): carriers refuse pictures over roughly 300 KB to
 *    1 MB, and the phone then keeps the message "sending" for ever, with
 *    every later message to that person stuck behind it. Google Messages'
 *    own apps shrink pictures for MMS the same way.
 * Everything else, and anything that cannot be decoded, is sent unchanged.
 */
object AttachmentPrep {

    /** What [prepare] made of the attachment. */
    data class Prepared(val mime: String, val name: String)

    /** How large a picture may be sent: its longest side and its file size. */
    data class Limits(val maxSide: Int, val maxBytes: Long)

    /** RCS chats and WhatsApp: large pictures go through; only the extreme ones are shrunk. */
    val LARGE = Limits(maxSide = 4096, maxBytes = 8L * 1024 * 1024)

    /** SMS/MMS conversations: what carriers accept (most allow at least this). */
    val MMS = Limits(maxSide = 1600, maxBytes = 800L * 1024)

    /** Longest side a picture is sent with when nothing smaller is needed. */
    const val MAX_SIDE = 4096

    /** Pictures above this size are scaled down even when their sides are fine. */
    const val MAX_BYTES = 8L * 1024 * 1024

    private val QUALITIES = intArrayOf(88, 80, 72, 64, 56)
    private const val MIN_SIDE = 480
    private const val SNIFF_BYTES = 64

    /**
     * Rewrites [file] in place when it is a picture to convert or shrink, and
     * returns its new type and name; null when it is sent as it is. Never
     * throws: a picture that cannot be prepared goes out unchanged.
     */
    fun prepare(file: File, mime: String, name: String, limits: Limits = LARGE): Prepared? = try {
        prepareOrThrow(file, mime, name, limits)
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: LinkageError) {
        // No decoder library for this CPU.
        null
    }

    private fun prepareOrThrow(file: File, mime: String, name: String, limits: Limits): Prepared? {
        if (!file.isFile || file.length() == 0L) return null
        val head = ByteArray(SNIFF_BYTES)
        val read = file.inputStream().use { input ->
            var total = 0
            while (total < head.size) {
                val n = input.read(head, total, head.size - total)
                if (n < 0) break
                total += n
            }
            total
        }
        if (read < head.size) return null
        if (ImageSniffer.isHeif(head)) {
            val bytes = file.readBytes()
            if (HeifCodec.probe(bytes).isSequence) return null
            val bitmap = MediaLoader.decodePreview(bytes, limits.maxSide) ?: return null
            return write(bitmap, file, name, limits)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // GIFs may be animated, and shrinking would make them stills: left alone.
        val type = bounds.outMimeType.orEmpty()
        if (type != "image/jpeg" && type != "image/png" && type != "image/webp") return null
        if (maxOf(bounds.outWidth, bounds.outHeight) <= limits.maxSide && file.length() <= limits.maxBytes) return null
        val bitmap = MediaLoader.decodePreview(file.readBytes(), limits.maxSide) ?: return null
        return write(bitmap, file, name, limits)
    }

    /**
     * PNG for a transparent picture, else JPEG, made smaller (lower quality,
     * then fewer pixels) until it fits [limits]; written next to [file],
     * then put in its place.
     */
    private fun write(source: Bitmap, file: File, name: String, limits: Limits): Prepared {
        val png = source.hasAlpha() && hasTransparency(source)
        val encoded = encodeToFit(source, png, limits.maxBytes)
        val temp = File(file.path + ".prep")
        try {
            FileOutputStream(temp).use { it.write(encoded) }
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
        val extension = if (png) "png" else "jpg"
        val base = name.substringBeforeLast('.', name).ifBlank { "picture" }
        return Prepared(if (png) "image/png" else "image/jpeg", "$base.$extension")
    }

    /** The smallest effort that fits: quality steps first (JPEG), then scaling down by a quarter at a time. */
    internal fun encodeToFit(source: Bitmap, png: Boolean, maxBytes: Long): ByteArray {
        var bitmap = source
        while (true) {
            val qualities = if (png) intArrayOf(100) else QUALITIES
            var last: ByteArray? = null
            for (q in qualities) {
                val out = ByteArrayOutputStream()
                check(bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, q, out)) { "compress failed" }
                last = out.toByteArray()
                if (last.size <= maxBytes) return last
            }
            val longest = maxOf(bitmap.width, bitmap.height)
            if (longest <= MIN_SIDE) return last!! // as small as it sensibly gets; sent as it is
            val w = (bitmap.width * 3 / 4).coerceAtLeast(1)
            val h = (bitmap.height * 3 / 4).coerceAtLeast(1)
            bitmap = Bitmap.createScaledBitmap(bitmap, w, h, true)
        }
    }

    /** Whether any pixel is not fully opaque (a decoder may mark an opaque picture as having alpha). */
    private fun hasTransparency(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val row = IntArray(w)
        val step = maxOf(1, bitmap.height / 256)
        var y = 0
        while (y < bitmap.height) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (c in row) if ((c ushr 24) != 0xFF) return true
            y += step
        }
        // The last row too: a transparent border is common in stickers.
        bitmap.getPixels(row, 0, w, 0, bitmap.height - 1, w, 1)
        return row.any { (it ushr 24) != 0xFF }
    }
}
