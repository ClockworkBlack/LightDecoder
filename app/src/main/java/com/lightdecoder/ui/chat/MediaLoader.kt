package com.lightdecoder.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import androidx.annotation.RequiresApi
import com.lightdecoder.R
import com.lightdecoder.archive.MediaRow
import com.lightdecoder.heif.HeifCodec
import com.lightdecoder.heif.ImageSniffer
import com.lightdecoder.heif.Media
import com.lightdecoder.relay.RelayException
import com.lightdecoder.relay.RelayManager
import com.lightdecoder.ui.relay.RelayErrors
import com.lightdecoder.ui.relay.relayAttempt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.WeakHashMap

/**
 * Previews for the media in message bubbles. A preview comes from, in order:
 * the downloaded thumbnail, the downloaded file, inline bytes, or a download
 * through the link (the thumbnail when the phone has one, else the picture
 * itself if it is not too large). HEIC goes through the built-in decoder.
 *
 * Main thread only, except the decoding, which runs on IO with at most
 * [PARALLEL] at a time so a fast scroll does not decode fifty pictures at once.
 * A row that is scrolled away or rebound [cancel]s its loads.
 *
 * A download that needs the link connected (the bridge's download token has
 * expired while disconnected) shows "Connect to load" instead of staying a
 * blank placeholder. It is not tried again while the link is still offline,
 * and never counted as "not a picture": [clearFailures], once connected,
 * lets the rebound rows try again.
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

    /** Parts whose download needs the link connected; tried again once it is ([clearFailures]). */
    private val needsConnection = HashSet<String>()

    /** Set when a download failed (for example while offline), so the screen can retry later. */
    var hadFailures = false
        private set

    /**
     * Shows the preview of [part] in [view]. [status], if given, is the
     * "Connect to load" label over it: shown while the picture can only be
     * downloaded with the link connected, hidden otherwise.
     */
    fun bind(view: ImageView, messageId: String, part: MediaRow, maxSidePx: Int, status: View? = null) {
        val key = keyFor(messageId, part)
        jobs.remove(view)?.cancel()
        view.tag = key
        status?.visibility = View.GONE
        cache.get(key)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageResource(R.drawable.ic_image_placeholder)
        if (key in notPictures) return
        if (key in needsConnection && !manager.state.value.isOnline) {
            // Still offline: say so again without another try that cannot work.
            hadFailures = true
            status?.visibility = View.VISIBLE
            return
        }
        val job = scope.launch {
            try {
                val result = permits.withPermit {
                    // Another bind of the same picture may have finished while this one waited.
                    cache.get(key)?.let { Preview.Ready(it) }
                        ?: withContext(Dispatchers.IO) { loadPreview(messageId, part, maxSidePx) }
                }
                when (result) {
                    is Preview.Ready -> {
                        cache.put(key, result.bitmap)
                        if (view.tag == key) view.setImageBitmap(result.bitmap)
                    }
                    Preview.NotAPicture -> notPictures.add(key)
                    Preview.Unavailable -> hadFailures = true
                    Preview.NeedsConnection -> {
                        needsConnection.add(key)
                        hadFailures = true
                        if (view.tag == key) status?.visibility = View.VISIBLE
                    }
                }
            } finally {
                if (jobs[view] === coroutineContext[Job]) jobs.remove(view)
            }
        }
        if (job.isActive) jobs[view] = job
    }

    /**
     * Stops loading into [view]: its row was scrolled away or rebound. A load
     * still waiting for its turn gives up its place, so the pictures actually
     * on screen are not queued behind ones nobody is looking at.
     */
    fun cancel(view: ImageView) {
        jobs.remove(view)?.cancel()
        view.tag = null
    }

    /**
     * Forget earlier download failures, including "needs a connection" (call
     * it once the link is connected); the caller rebinds the visible rows.
     */
    fun clearFailures() {
        hadFailures = false
        needsConnection.clear()
    }

    private sealed class Preview {
        class Ready(val bitmap: Bitmap) : Preview()
        object NotAPicture : Preview()
        object Unavailable : Preview()
        object NeedsConnection : Preview()
    }

    /** What a download through the link gave. */
    private sealed class Fetched {
        class Got(val file: File) : Fetched()
        object None : Fetched()
        object NeedsConnection : Fetched()
    }

    private suspend fun loadPreview(messageId: String, part: MediaRow, maxSide: Int): Preview {
        val bytes = existing(part.thumbPath)?.let(::readCapped)
            ?: existing(part.localPath)?.let(::readCapped)
            ?: part.inlineData.takeIf { it.isNotEmpty() }
            ?: when (val fetched = download(messageId, part)) {
                is Fetched.Got -> readCapped(fetched.file)
                Fetched.None -> null
                Fetched.NeedsConnection -> return Preview.NeedsConnection
            }
            ?: return Preview.Unavailable
        currentCoroutineContext().ensureActive()
        val bitmap = try {
            decodePreview(bytes, maxSide)
        } catch (e: OutOfMemoryError) {
            // Memory was short right now, not "not a picture": try again later.
            return Preview.Unavailable
        } ?: return Preview.NotAPicture
        return Preview.Ready(bitmap)
    }

    private suspend fun download(messageId: String, part: MediaRow): Fetched {
        val thumbnail = part.thumbMediaId.isNotEmpty()
        if (!thumbnail) {
            // No thumbnail: fetch the file itself only when it is a picture of sensible size.
            val picture = part.mime.isEmpty() || part.mime.startsWith("image/")
            if (part.mediaId.isEmpty() || !picture || part.size > AUTO_DOWNLOAD_MAX) return Fetched.None
        }
        return relayAttempt { manager.mediaFile(messageId, part.partId, thumbnail) }.fold(
            onSuccess = { file -> if (file != null) Fetched.Got(file) else Fetched.None },
            onFailure = { e ->
                if (RelayErrors.codeOf(e) == RelayException.NOT_CONNECTED) Fetched.NeedsConnection else Fetched.None
            },
        )
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

        /** Pictures above this many pixels decode one at a time when they must be decoded at full size. */
        private const val LARGE_PIXELS = 4_000_000L

        /** Held while a large HEIC decodes at full size, so a few at once cannot exhaust memory. */
        private val largeDecode = Any()

        /**
         * Decodes a picture no larger than [maxSide] on its longest side.
         * Android's own formats are subsampled while decoding, so a
         * 12-megapixel photo never sits in memory at full size. HEIC goes
         * through Android's decoder too where it can (Android 9+, a single
         * picture without transparency), subsampled the same way; otherwise
         * through the built-in decoder at full size, large ones one at a time.
         * Null if it is not a picture.
         *
         * @throws OutOfMemoryError when memory runs out; that is not "not a
         *   picture", so the caller can try again later
         */
        fun decodePreview(bytes: ByteArray, maxSide: Int): Bitmap? = try {
            if (ImageSniffer.isHeif(bytes)) {
                decodeHeifPreview(bytes, maxSide)
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    null
                } else {
                    val decoded = BitmapFactory.decodeByteArray(
                        bytes, 0, bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) },
                    )
                    decoded?.let { fit(it, maxSide) }
                }
            }
        } catch (e: HeifCodec.DecodeException) {
            null
        }

        private fun decodeHeifPreview(bytes: ByteArray, maxSide: Int): Bitmap {
            val info = HeifCodec.probe(bytes)
            val largest = maxOf(info.width, info.height)
            if (Build.VERSION.SDK_INT >= 28 && !info.hasAlpha && !info.isSequence && largest > maxSide) {
                systemDecode(bytes, maxSide)?.let { return it }
            }
            val full = if (info.width.toLong() * info.height > LARGE_PIXELS) {
                synchronized(largeDecode) { HeifCodec.decodeStill(bytes) }
            } else {
                HeifCodec.decodeStill(bytes)
            }
            return fit(full, maxSide)
        }

        /** Android's HEIF decoder, subsampled; null if it cannot read this file. */
        @RequiresApi(28)
        private fun systemDecode(bytes: ByteArray, maxSide: Int): Bitmap? = try {
            val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height, maxSide))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            fit(decoded, maxSide)
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }

        /** The largest power of two that keeps the longest side at or above [maxSide]. */
        internal fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
            var sample = 1
            while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
            return sample
        }

        private fun fit(bitmap: Bitmap, maxSide: Int): Bitmap {
            val scaled = Media.scaledToFit(bitmap, maxSide)
            if (scaled !== bitmap) bitmap.recycle()
            return scaled
        }
    }
}
