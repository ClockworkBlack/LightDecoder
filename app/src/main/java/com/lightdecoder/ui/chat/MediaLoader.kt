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
import com.lightdecoder.archive.MediaCache
import com.lightdecoder.archive.MediaRow
import com.lightdecoder.chat.ChatBackend
import com.lightdecoder.heif.HeifCodec
import com.lightdecoder.heif.ImageSniffer
import com.lightdecoder.heif.Media
import com.lightdecoder.relay.RelayException
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
 * The pictures in a conversation of one service (its [ChatBackend] does the
 * downloading), shown the way messaging apps show them: the picture itself,
 * at full size, without being asked.
 *
 * - **The picture itself** comes from its downloaded file, a download through
 *   the link (up to [AUTO_DOWNLOAD_MAX]), or inline bytes when those are the
 *   whole media.
 * - **While it loads**, a stand-in shows blurred: the phone's thumbnail if it
 *   is already on this phone, else the small preview the phone sends inline
 *   next to a picture. The view takes the picture's real size from the start
 *   ([MediaImageView]), so nothing jumps when it arrives.
 * - **When the picture cannot be had** (for now), the best stand-in stays,
 *   unblurred. That includes the thumbnail downloaded from the phone. While
 *   the phone has only a thumbnail, the screen asks it for the original; the
 *   message then changes, and that loads it.
 * - **Videos** show their thumbnail or inline still; the video itself is
 *   played by another app.
 *
 * The inline preview is never taken for the picture: it is a postage stamp,
 * and shown as the picture it is a blur.
 *
 * Main thread only, except the loading, which runs on IO with at most
 * [PARALLEL] at a time, so a fast scroll does not decode fifty pictures at
 * once. A row that is scrolled away or rebound [cancel]s its loads.
 *
 * A download that needs the link connected (the bridge's download token has
 * expired while disconnected) shows "Connect to load". It is not tried again
 * while the link is still offline, and never counted as "not a picture":
 * [clearFailures], once connected, lets the rebound rows try again.
 */
class MediaLoader(
    private val manager: ChatBackend,
    private val scope: CoroutineScope,
) {
    /** What was shown: the picture under [keyFor], a stand-in under [standInKey]. */
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
     * The label over an iMessage picture a notification only named ("Picture from OpenBubbles ·
     * tap"); through the iMessage library one that names no other app ([ThreadActivity] sets it).
     */
    var awaitingLabel: Int = R.string.imessage_media_waiting

    /**
     * Shows [part] in [view]. [status], if given, is the "Connect to load"
     * label over it: shown while the picture can only be downloaded with the
     * link connected, hidden otherwise.
     */
    fun bind(view: ImageView, messageId: String, part: MediaRow, maxSidePx: Int, status: View? = null) {
        val key = keyFor(messageId, part)
        jobs.remove(view)?.cancel()
        view.tag = key
        status?.visibility = View.GONE
        (status as? android.widget.TextView)?.setText(com.lightdecoder.R.string.chat_media_connect_to_load)
        (view as? MediaImageView)?.setMediaSize(part.width, part.height)
        // An iMessage picture OpenBubbles described in words: it comes from its Auto-save copy, when there is one.
        if (awaitsFile(part)) {
            showLoading(view)
            settle(view)
            (status as? android.widget.TextView)?.setText(awaitingLabel)
            status?.visibility = View.VISIBLE
            return
        }
        cache.get(key)?.let {
            show(view, it, standIn = false)
            return
        }
        val cachedStandIn = cache.get(standInKey(key))
        if (cachedStandIn != null) show(view, cachedStandIn, standIn = true) else showLoading(view)
        if (key in notPictures) {
            settle(view)
            return
        }
        if (key in needsConnection && !manager.state.value.isOnline) {
            // Still offline: say so again without another try that cannot work.
            hadFailures = true
            settle(view)
            status?.visibility = View.VISIBLE
            return
        }
        if (cachedStandIn != null && !canLoadPicture(messageId, part)) {
            // The stand-in is the best there is until the part changes (its original arrives).
            settle(view)
            return
        }
        val job = scope.launch {
            try {
                var shownStandIn = cachedStandIn != null
                val result = permits.withPermit {
                    // Another bind of the same picture may have finished while this one waited.
                    cache.get(key)?.let { Loaded.Picture(it) }
                        ?: withContext(Dispatchers.IO) {
                            load(messageId, part, maxSidePx, haveStandIn = shownStandIn) { standIn ->
                                withContext(Dispatchers.Main) {
                                    cache.put(standInKey(key), standIn)
                                    shownStandIn = true
                                    if (view.tag == key) show(view, standIn, standIn = true)
                                }
                            }
                        }
                }
                val failure = when (result) {
                    is Loaded.Picture -> {
                        cache.put(key, result.bitmap)
                        if (view.tag == key) show(view, result.bitmap, standIn = false)
                        null
                    }
                    is Loaded.StandIn -> {
                        cache.put(standInKey(key), result.bitmap)
                        if (view.tag == key) show(view, result.bitmap, standIn = true)
                        result.failure
                    }
                    is Loaded.Failed -> result.failure
                }
                when (failure) {
                    Failure.NOT_A_PICTURE -> notPictures.add(key)
                    Failure.UNAVAILABLE -> hadFailures = true
                    Failure.NEEDS_CONNECTION -> {
                        needsConnection.add(key)
                        hadFailures = true
                        if (view.tag == key) status?.visibility = View.VISIBLE
                    }
                    null -> Unit
                }
                // Whatever stand-in is left is the best there is for now: no longer "loading".
                if (result !is Loaded.Picture && view.tag == key) settle(view)
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

    private fun show(view: ImageView, bitmap: Bitmap, standIn: Boolean) {
        if (view is MediaImageView) view.showPicture(bitmap, preview = standIn) else view.setImageBitmap(bitmap)
    }

    private fun showLoading(view: ImageView) {
        if (view is MediaImageView) view.showLoading() else view.setImageResource(R.drawable.ic_image_placeholder)
    }

    private fun settle(view: ImageView) {
        (view as? MediaImageView)?.settlePreview()
    }

    /** Why the picture itself could not be had. */
    private enum class Failure { NOT_A_PICTURE, UNAVAILABLE, NEEDS_CONNECTION }

    private sealed class Loaded {
        /** The picture itself (for a video: its still). */
        class Picture(val bitmap: Bitmap) : Loaded()

        /**
         * Only a stand-in (thumbnail or inline preview) for now. [failure] is
         * why the picture itself did not come; null when there was nothing to
         * try (the phone has only a thumbnail, and the original is asked for).
         */
        class StandIn(val bitmap: Bitmap, val failure: Failure?) : Loaded()

        /** Nothing to show. */
        class Failed(val failure: Failure) : Loaded()
    }

    /** What a download through the link gave. */
    private sealed class Fetched {
        class Got(val file: File) : Fetched()
        object None : Fetched()
        object NeedsConnection : Fetched()
    }

    private fun isVideo(part: MediaRow) = part.mime.startsWith("video/")

    /** Nothing to show it from yet: no file, nothing to download, no thumbnail, nothing inline. */
    private fun awaitsFile(part: MediaRow) =
        (part.mime == com.lightdecoder.imessage.IMessageCapture.IMAGE || part.mime == com.lightdecoder.imessage.IMessageCapture.VIDEO) &&
            part.localPath.isEmpty() && part.thumbPath.isEmpty() && part.mediaId.isEmpty() && part.thumbMediaId.isEmpty() && part.inlineData.isEmpty()

    /** A local file that really is the media (not an inline preview an older version stored as one). */
    private fun pictureFile(messageId: String, part: MediaRow): File? {
        if (part.inlineIsPreview && MediaCache.isInlineFile(part.localPath, messageId, part.partId)) return null
        return existing(part.localPath)
    }

    /** Whether the picture itself can be had now: on this phone, downloadable, or inline. */
    private fun canLoadPicture(messageId: String, part: MediaRow): Boolean {
        if (isVideo(part)) return false
        val picture = part.mime.isEmpty() || part.mime.startsWith("image/")
        return part.localPath.isNotEmpty() && !(part.inlineIsPreview && MediaCache.isInlineFile(part.localPath, messageId, part.partId)) ||
            (part.mediaId.isNotEmpty() && picture && part.size <= AUTO_DOWNLOAD_MAX) ||
            part.inlineIsMedia
    }

    /**
     * Loads what to show for [part]. While the picture itself loads,
     * [onStandIn] gets a quick stand-in from what is on this phone already
     * (its thumbnail file or the inline preview), unless [haveStandIn].
     */
    private suspend fun load(
        messageId: String,
        part: MediaRow,
        maxSide: Int,
        haveStandIn: Boolean,
        onStandIn: suspend (Bitmap) -> Unit,
    ): Loaded {
        var needsConnection = false
        suspend fun fetch(thumbnail: Boolean): ByteArray? = when (val fetched = download(messageId, part, thumbnail)) {
            is Fetched.Got -> readCapped(fetched.file)
            Fetched.None -> null
            Fetched.NeedsConnection -> {
                needsConnection = true
                null
            }
        }
        fun decode(bytes: ByteArray?, side: Int): Bitmap? = bytes?.let {
            try {
                decodePreview(it, side)
            } catch (e: OutOfMemoryError) {
                null
            }
        }

        if (isVideo(part)) {
            // A still is all there is to show: the thumbnail, else the inline one.
            val still = decode(existing(part.thumbPath)?.let(::readCapped), maxSide)
                ?: decode(part.inlineData.takeIf { it.isNotEmpty() }, maxSide)
                ?: decode(fetch(thumbnail = true), maxSide)
            return when {
                still != null -> Loaded.Picture(still)
                needsConnection -> Loaded.Failed(Failure.NEEDS_CONNECTION)
                else -> Loaded.Failed(Failure.UNAVAILABLE)
            }
        }

        val inlinePreview = part.inlineData.takeIf { part.inlineIsPreview }
        if (!canLoadPicture(messageId, part)) {
            // Only a thumbnail on the phone (the screen asks for the original): the best
            // stand-in, downloading the thumbnail if need be; the inline preview is the last resort.
            val best = decode(existing(part.thumbPath)?.let(::readCapped), maxSide)
                ?: decode(fetch(thumbnail = true), maxSide)
                ?: decode(inlinePreview, maxSide)
            val failure = if (needsConnection) Failure.NEEDS_CONNECTION else null
            return when {
                best != null -> Loaded.StandIn(best, failure)
                else -> Loaded.Failed(failure ?: Failure.UNAVAILABLE)
            }
        }

        // A quick stand-in from what is on this phone already, while the picture loads.
        var standIn: Bitmap? = null
        if (!haveStandIn) {
            standIn = decode(existing(part.thumbPath)?.let(::readCapped), maxSide) ?: decode(inlinePreview, maxSide)
            standIn?.let { onStandIn(it) }
        }
        currentCoroutineContext().ensureActive()

        val bytes = pictureFile(messageId, part)?.let(::readCapped)
            ?: (if (part.inlineIsMedia) part.inlineData else null)
            ?: fetch(thumbnail = false)
        val failure = if (bytes == null) {
            if (needsConnection) Failure.NEEDS_CONNECTION else Failure.UNAVAILABLE
        } else {
            currentCoroutineContext().ensureActive()
            try {
                decodePreview(bytes, maxSide)?.let { return Loaded.Picture(it) }
                Failure.NOT_A_PICTURE
            } catch (e: OutOfMemoryError) {
                // Memory was short right now, not "not a picture": try again later.
                Failure.UNAVAILABLE
            }
        }
        // No picture for now: the thumbnail, downloaded only now, if nothing shows yet.
        if (standIn == null && !haveStandIn) standIn = decode(fetch(thumbnail = true), maxSide)
        return if (standIn != null) Loaded.StandIn(standIn, failure) else Loaded.Failed(failure)
    }

    private suspend fun download(messageId: String, part: MediaRow, thumbnail: Boolean): Fetched {
        if (thumbnail) {
            if (part.thumbMediaId.isEmpty()) return Fetched.None
        } else {
            // The file itself: only a picture of sensible size.
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

        private fun standInKey(key: String) = "stand-in|$key"

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
                    // Upright as the camera's EXIF note says (BitmapFactory ignores it).
                    decoded?.let { fit(com.lightdecoder.heif.ExifOrientation.upright(bytes, it), maxSide) }
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
