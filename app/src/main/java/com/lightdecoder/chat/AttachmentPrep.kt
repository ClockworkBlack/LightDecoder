package com.lightdecoder.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.lightdecoder.heif.HeifCodec
import com.lightdecoder.heif.ImageSniffer
import com.lightdecoder.ui.chat.MediaLoader
import java.io.File
import java.io.FileOutputStream

/**
 * Makes a picture the user attached one every phone can show, before it is
 * queued (for either service):
 *  - HEIC/HEIF (iPhone and many Android cameras) becomes JPEG, or PNG when it
 *    is transparent (a sticker), since many Android phones cannot show HEIC
 *    at all: the very problem this app exists for. Animated HEIC is left
 *    as it is (a still would lose the animation).
 *  - A very large photo (over [MAX_SIDE] pixels on its longest side, or
 *    over [MAX_BYTES]) is scaled down so that it goes through.
 * Everything else, and anything that cannot be decoded, is sent unchanged.
 */
object AttachmentPrep {

    /** What [prepare] made of the attachment. */
    data class Prepared(val mime: String, val name: String)

    /** Longest side a picture is sent with. */
    const val MAX_SIDE = 4096

    /** Pictures above this size are scaled down even when their sides are fine. */
    const val MAX_BYTES = 8L * 1024 * 1024

    private const val JPEG_QUALITY = 90
    private const val SNIFF_BYTES = 64

    /**
     * Rewrites [file] in place when it is a picture to convert or shrink, and
     * returns its new type and name; null when it is sent as it is. Never
     * throws: a picture that cannot be prepared goes out unchanged.
     */
    fun prepare(file: File, mime: String, name: String): Prepared? = try {
        prepareOrThrow(file, mime, name)
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: LinkageError) {
        // No decoder library for this CPU.
        null
    }

    private fun prepareOrThrow(file: File, mime: String, name: String): Prepared? {
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
            val bitmap = MediaLoader.decodePreview(bytes, MAX_SIDE) ?: return null
            return write(bitmap, file, name)
        }
        val isPhoto = mime == "image/jpeg" || mime == "image/png" || mime.isEmpty()
        if (!isPhoto) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val type = bounds.outMimeType.orEmpty()
        if (type != "image/jpeg" && type != "image/png") return null
        if (maxOf(bounds.outWidth, bounds.outHeight) <= MAX_SIDE && file.length() <= MAX_BYTES) return null
        val bitmap = MediaLoader.decodePreview(file.readBytes(), MAX_SIDE) ?: return null
        return write(bitmap, file, name)
    }

    /** PNG for a transparent picture, else JPEG; written next to [file], then put in its place. */
    private fun write(bitmap: Bitmap, file: File, name: String): Prepared {
        val png = bitmap.hasAlpha()
        val temp = File(file.path + ".prep")
        try {
            FileOutputStream(temp).use { out ->
                val ok = bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                check(ok) { "compress failed" }
            }
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
}
