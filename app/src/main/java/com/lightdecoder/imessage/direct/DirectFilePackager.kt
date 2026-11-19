package com.lightdecoder.imessage.direct

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import uniffi.rust_bridge.FileInfo
import uniffi.rust_bridge.KotlinFilePackager
import uniffi.rust_bridge.PackagedFile

/**
 * What the library asks of a file before it uploads it: a picture's or video's size, a video's or
 * audio file's length, and a small still of a video. Set once when the library loads
 * ([uniffi.rust_bridge.setFilePackager]). The ":imessage" process only; the main process uses
 * its [pictureSize] and [videoSize] for attachments it downloaded.
 */
class DirectFilePackager(context: Context) : KotlinFilePackager {

    private val app = context.applicationContext

    override fun getFile(path: String): PackagedFile = try {
        media(path) ?: picture(path) ?: PackagedFile.Failure("$path is not a picture, video or audio file")
    } catch (e: Exception) {
        Log.w(TAG, "could not look into a file to send", e)
        PackagedFile.Failure(e.message ?: e.javaClass.simpleName)
    }

    /** Files the library saved for the user, shown in the gallery. */
    override fun scanFiles(paths: List<String>) {
        MediaScannerConnection.scanFile(app, paths.toTypedArray(), null, null)
    }

    /** A video or audio file; null for anything Android cannot play. */
    private fun media(path: String): PackagedFile? {
        val r = MediaMetadataRetriever()
        try {
            try {
                r.setDataSource(path)
            } catch (e: RuntimeException) {
                return null
            }
            val video = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val audio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            if (!video && !audio) return null
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { it / 1000.0 }
            if (!video) return PackagedFile.Info(FileInfo(duration, 0u, 0u, null))
            val (width, height) = videoSize(r)
            return PackagedFile.Info(FileInfo(duration, width.toUInt(), height.toUInt(), still(r)))
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
                // Nothing to release.
            }
        }
    }

    /** A JPEG of a video's first frame, at most [STILL_PX] on its longer side; null if there is none. */
    private fun still(r: MediaMetadataRetriever): ByteArray? {
        val frame = r.frameAtTime ?: return null
        val scale = STILL_PX.toFloat() / maxOf(frame.width, frame.height)
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt().coerceAtLeast(1), (frame.height * scale).toInt().coerceAtLeast(1), true)
        } else {
            frame
        }
        return ByteArrayOutputStream().use { out ->
            small.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        }
    }

    /** A picture, by its size as it is shown; null for anything else. */
    private fun picture(path: String): PackagedFile? {
        val (w, h) = pictureSize(path) ?: return null
        return PackagedFile.Info(FileInfo(null, w.toUInt(), h.toUInt(), null))
    }

    companion object {
        private const val TAG = "DirectFilePackager"
        private const val STILL_PX = 320

        /** A picture's width and height as it is shown (turned as its EXIF says); null for anything else. */
        fun pictureSize(path: String): Pair<Int, Int>? {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            val turned = try {
                ExifInterface(path).rotationDegrees.let { it == 90 || it == 270 }
            } catch (e: java.io.IOException) {
                false
            }
            return if (turned) options.outHeight to options.outWidth else options.outWidth to options.outHeight
        }

        /** A video's width and height as it is shown (turned as it says); 0 and 0 when [r] has none. */
        fun videoSize(r: MediaMetadataRetriever): Pair<Int, Int> {
            val width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            return if (rotation == 90 || rotation == 270) height to width else width to height
        }

        /** The width and height of the video at [path] as it is shown; null when it cannot be read. */
        fun videoSize(path: String): Pair<Int, Int>? {
            val r = MediaMetadataRetriever()
            return try {
                r.setDataSource(path)
                videoSize(r).takeIf { it.first > 0 && it.second > 0 }
            } catch (e: RuntimeException) {
                null
            } finally {
                try {
                    r.release()
                } catch (e: Exception) {
                    // Nothing to release.
                }
            }
        }
    }
}
