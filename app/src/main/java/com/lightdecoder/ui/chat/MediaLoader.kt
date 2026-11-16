package com.lightdecoder.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import com.lightdecoder.R
import com.lightdecoder.archive.MediaRow
import com.lightdecoder.heif.HeifCodec
import com.lightdecoder.heif.ImageSniffer
import com.lightdecoder.heif.Media
import com.lightdecoder.relay.RelayManager
import com.lightdecoder.ui.relay.relayAttempt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.WeakHashMap

/**
 * Previews for the media in message bubbles. A preview comes from, in order:
 * the downloaded thumbnail, the downloaded file, inline bytes, or a download
 * through the link (the thumbnail when the phone has one, else the picture
 * itself if it is not too large). HEIC goes through the built-in decoder.
 *
 * Main thread only, except the decoding, which runs on IO with at most
 * [PARALLEL] at a time so a fast scroll does not decode fifty pictures at once.
 */
class MediaLoader(
    private val manager: RelayManager,
    private val scope: CoroutineScope,
) {
    private val cache = object : LruCache<String, Bitmap>(cacheKb()) {
        override fun sizeOf(key: String, value: Bitmap) = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val jobs = WeakHashMap<ImageView, Job>()
    private val permits = Semaphore(PARALLEL)

    /** Parts that turned out not to be pictures; not tried again. */
    private val notPictures = HashSet<String>()

    /** Set when a download failed (for example while offline), so the screen can retry later. */
    var hadFailures = false
        private set

    fun bind(view: ImageView, messageId: String, part: MediaRow, maxSidePx: Int) {
        val key = keyFor(messageId, part)
        jobs.remove(view)?.cancel()
        view.tag = key
        cache.get(key)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageResource(R.drawable.ic_image_placeholder)
        if (key in notPictures) return
        jobs[view] = scope.launch {
            val result = permits.withPermit { withContext(Dispatchers.IO) { loadPreview(messageId, part, maxSidePx) } }
            when (result) {
                is Preview.Ready -> {
                    cache.put(key, result.bitmap)
                    if (view.tag == key) view.setImageBitmap(result.bitmap)
                }
                Preview.NotAPicture -> notPictures.add(key)
                Preview.Unavailable -> hadFailures = true
            }
        }
    }

    /** Forget earlier download failures; the caller rebinds the visible rows. */
    fun clearFailures() {
        hadFailures = false
    }

    private sealed class Preview {
        class Ready(val bitmap: Bitmap) : Preview()
        object NotAPicture : Preview()
        object Unavailable : Preview()
    }

    private suspend fun loadPreview(messageId: String, part: MediaRow, maxSide: Int): Preview {
        val bytes = existing(part.thumbPath)?.let(::readCapped)
            ?: existing(part.localPath)?.let(::readCapped)
            ?: part.inlineData.takeIf { it.isNotEmpty() }
            ?: download(messageId, part)?.let(::readCapped)
            ?: return Preview.Unavailable
        val bitmap = decodePreview(bytes, maxSide) ?: return Preview.NotAPicture
        return Preview.Ready(bitmap)
    }

    private suspend fun download(messageId: String, part: MediaRow): File? {
        val thumbnail = part.thumbMediaId.isNotEmpty()
        if (!thumbnail) {
            // No thumbnail: fetch the file itself only when it is a picture of sensible size.
            val picture = part.mime.isEmpty() || part.mime.startsWith("image/")
            if (part.mediaId.isEmpty() || !picture || part.size > AUTO_DOWNLOAD_MAX) return null
        }
        return relayAttempt { manager.mediaFile(messageId, part.partId, thumbnail) }.getOrNull()
    }

    private fun existing(path: String): File? =
        if (path.isEmpty()) null else File(path).takeIf { it.isFile }

    private fun readCapped(file: File): ByteArray? =
        if (file.length() in 1..MAX_READ) runCatching { file.readBytes() }.getOrNull() else null

    companion object {
        private const val PARALLEL = 3
        private const val AUTO_DOWNLOAD_MAX = 25L * 1024 * 1024
        private const val MAX_READ = 32L * 1024 * 1024

        /** Includes everything that changes the picture: a new thumbnail or full size gets a new key. */
        fun keyFor(messageId: String, part: MediaRow): String =
            listOf(messageId, part.partId, part.mediaId, part.thumbMediaId, part.localPath, part.thumbPath)
                .joinToString("|")

        private fun cacheKb(): Int = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceIn(4 * 1024, 64 * 1024)

        /**
         * Decodes a picture no larger than [maxSide] on its longest side. HEIC
         * through [Media.decodeFirstFrame]; anything else through Android,
         * subsampled while decoding so a 12-megapixel photo never sits in
         * memory at full size. Null if it is not a picture.
         */
        fun decodePreview(bytes: ByteArray, maxSide: Int): Bitmap? = try {
            if (ImageSniffer.isHeif(bytes)) {
                val full = Media.decodeFirstFrame(bytes)
                val scaled = Media.scaledToFit(full, maxSide)
                if (scaled !== full) full.recycle()
                scaled
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    null
                } else {
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
                    val decoded = BitmapFactory.decodeByteArray(
                        bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                    decoded?.let {
                        val scaled = Media.scaledToFit(it, maxSide)
                        if (scaled !== it) it.recycle()
                        scaled
                    }
                }
            }
        } catch (e: HeifCodec.DecodeException) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}
