package com.lightdecoder.imessage.direct

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.util.Log
import com.lightdecoder.heif.ExifOrientation
import com.lightdecoder.heif.ImageSniffer
import com.lightdecoder.heif.Media
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MmcsFile
import uniffi.rust_bridge.PhotoPosterContents
import uniffi.rust_bridge.PhotoPosterContentsFrame
import uniffi.rust_bridge.PhotoPosterContentsSize
import uniffi.rust_bridge.PhotoPosterLayer
import uniffi.rust_bridge.PhotoPosterLayout
import uniffi.rust_bridge.PhotoPosterProperties
import uniffi.rust_bridge.PosterAsset
import uniffi.rust_bridge.PosterRole
import uniffi.rust_bridge.PosterType
import uniffi.rust_bridge.PrPosterColor
import uniffi.rust_bridge.PrPosterContentMaterialStyle
import uniffi.rust_bridge.PrPosterSystemTimeFontConfiguration
import uniffi.rust_bridge.PrPosterTitleStyleConfiguration
import uniffi.rust_bridge.PushChatMessage
import uniffi.rust_bridge.SetTranscriptBackgroundMessage
import uniffi.rust_bridge.SimplifiedPoster
import uniffi.rust_bridge.SimplifiedTranscriptPoster
import uniffi.rust_bridge.UiColor
import uniffi.rust_bridge.WatchBackground

/**
 * iOS 26's chat backgrounds ("transcript backgrounds") as PosterKit makes them: a zip of a poster
 * archive, uploaded to Apple's file servers (MMCS); the message carries where it is and its key.
 *
 * A photo background is [PosterType.Photo]: one asset, its picture in layers (`background`, and
 * `background-backfill` under it) as HEIC files, with the part of the picture on screen
 * ([PhotoPosterLayout.visibleFrame], in the picture's points, y up from the bottom as Core Graphics
 * counts). Next to the poster is the Watch's picture of it ([WatchBackground.backgroundImageData]),
 * a PNG already cut to the part on screen. A dynamic or gradient background is one only an iPhone
 * draws; OpenBubbles takes those as the background being taken off, and so does this.
 *
 * Pure (no library calls): the picture to show for a poster ([pictureSources], [cropOf]), and a
 * poster for a photo to send ([photoPoster]), mirroring OpenBubbles' poster editor. Tested in
 * BackgroundPostersTest.
 */
object BackgroundPosters {

    /** PosterKit's provider of photo posters. */
    const val PHOTOS_PROVIDER = "com.apple.PhotosUIPrivate.PhotosPosterProvider"

    /** The layer files of a photo poster, as an iPhone (and OpenBubbles) names them. */
    const val LAYER_BACKGROUND = "portrait-layer_background.HEIC"
    const val LAYER_BACKFILL = "portrait-layer_background-backfill.HEIC"

    /** The Watch's picture of a background: this size, as OpenBubbles renders it. */
    const val WATCH_WIDTH = 820
    const val WATCH_HEIGHT = 1003

    /** The longest side of a background picture kept here: plenty for a phone's screen. */
    const val MAX_SIDE = 2048

    /** Whether [poster] means "no background" here: one only an iPhone draws (OpenBubbles: "dynamic posters are deleted posters"). */
    fun isRemoval(poster: SimplifiedTranscriptPoster): Boolean =
        poster.poster.type is PosterType.TranscriptDynamic || poster.poster.type is PosterType.TranscriptGradient

    /** Where a background's picture can come from: the bytes of an image file. */
    class Source(val bytes: ByteArray)

    /** A part of a picture, in its pixels: left, top, width, height. */
    data class CropBox(val left: Int, val top: Int, val width: Int, val height: Int)

    /**
     * The pictures [poster] can be shown from, best first: a photo poster's own picture (the
     * `background` layer, else its top layer) cut to the part on screen; then the Watch's picture
     * of it (any kind of poster: a monogram's too), which is already cut.
     */
    fun pictureSources(poster: SimplifiedTranscriptPoster): List<Pair<Source, PhotoCrop?>> {
        val out = ArrayList<Pair<Source, PhotoCrop?>>()
        (poster.poster.type as? PosterType.Photo)?.assets?.firstOrNull()?.let { asset ->
            val layers = asset.contents.layers
            val layer = layers.firstOrNull { it.identifier == "background" } ?: layers.maxByOrNull { it.zPosition }
            val bytes = layer?.let { asset.files[it.filename] }?.takeIf { it.isNotEmpty() }
            if (layer != null && bytes != null) {
                out += Source(bytes) to PhotoCrop(asset.contents.properties.portraitLayout, layer.frame)
            }
        }
        poster.watch.backgroundImageData.takeIf { it.isNotEmpty() }?.let { out += Source(it) to null }
        return out
    }

    /** How a photo poster's layer picture is cut: its layout and the layer's frame (in the picture's points). */
    class PhotoCrop(val layout: PhotoPosterLayout, val layerFrame: PhotoPosterContentsFrame)

    /**
     * The part of a layer's picture of [bitmapWidth] x [bitmapHeight] pixels that is on screen: the
     * layout's visible frame (y up from the bottom of the picture, as Core Graphics counts) moved
     * into the layer (y down from its top) and scaled to its pixels. Null when it says nothing
     * usable (then all of it is shown, centre-cropped by the screen).
     */
    fun cropOf(crop: PhotoCrop, bitmapWidth: Int, bitmapHeight: Int): CropBox? {
        val v = crop.layout.visibleFrame
        val image = crop.layout.imageSize
        val f = crop.layerFrame
        if (v.width <= 0 || v.height <= 0 || f.width <= 0 || f.height <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) return null
        val sx = bitmapWidth / f.width
        val sy = bitmapHeight / f.height
        val topInImage = (image.height.takeIf { it > 0 } ?: f.height) - v.y - v.height
        val left = ((v.x - f.x) * sx).toInt().coerceIn(0, bitmapWidth - 1)
        val top = ((topInImage - f.y) * sy).toInt().coerceIn(0, bitmapHeight - 1)
        val width = (v.width * sx).toInt().coerceAtMost(bitmapWidth - left)
        val height = (v.height * sy).toInt().coerceAtMost(bitmapHeight - top)
        if (width < MIN_CROP || height < MIN_CROP) return null
        if (left == 0 && top == 0 && width == bitmapWidth && height == bitmapHeight) return null
        return CropBox(left, top, width, height)
    }

    private const val MIN_CROP = 16

    /**
     * The part of a picture of [width] x [height] shown on a screen [aspect] (width / height) wide,
     * centred: OpenBubbles' `centeredAspectCrop`. Being centred, it reads the same with y counted
     * from the top or from the bottom.
     */
    fun centeredCrop(width: Double, height: Double, aspect: Double): PhotoPosterContentsFrame {
        val (cw, ch) = if (width / height > aspect) height * aspect to height else width to width / aspect
        return PhotoPosterContentsFrame(cw, ch, (width - cw) / 2, (height - ch) / 2)
    }

    /**
     * The poster for a photo of [width] x [height] pixels the user picked as a chat's background,
     * as OpenBubbles' poster editor makes one: the photo as both layers ([heic]: the picture as HEIC,
     * the format PosterKit keeps it in), the part on a screen [aspect] wide centred, title style
     * PosterKit's default, the Watch's picture [watchPng], role backdrop. [uuid] names the asset;
     * [timeNumberingSystem] is the library's encoded NSNull, as a new poster has it.
     */
    fun photoPoster(
        width: Int,
        height: Int,
        heic: ByteArray,
        watchPng: ByteArray,
        aspect: Double,
        uuid: String = UUID.randomUUID().toString().uppercase(),
        timeNumberingSystem: ByteArray? = null,
    ): SimplifiedTranscriptPoster {
        val w = width.toDouble()
        val h = height.toDouble()
        val frame = PhotoPosterContentsFrame(w, h, 0.0, 0.0)
        val zero = PhotoPosterContentsFrame(0.0, 0.0, 0.0, 0.0)
        val asset = PosterAsset(
            contents = PhotoPosterContents(
                version = 10u,
                layers = listOf(
                    PhotoPosterLayer(frame, LAYER_BACKFILL, 4.5f, "background-backfill"),
                    PhotoPosterLayer(frame, LAYER_BACKGROUND, 5f, "background"),
                ),
                properties = PhotoPosterProperties(
                    portraitLayout = PhotoPosterLayout(
                        clockIntersection = 2u,
                        deviceResolution = PhotoPosterContentsSize(1170.0, 2532.0),
                        visibleFrame = centeredCrop(w, h, aspect),
                        timeFrame = zero,
                        clockLayerOrder = "ClockAboveForeground",
                        hasTopEdgeContact = false,
                        inactiveFrame = zero,
                        imageSize = PhotoPosterContentsSize(w, h),
                        parallaxPadding = PhotoPosterContentsSize(0.0, 0.0),
                    ),
                    settlingEffectEnabled = false,
                    depthEnabled = false,
                    clockAreaLuminance = 0.0,
                    parallaxDisabled = false,
                ),
            ),
            files = mapOf(LAYER_BACKFILL to heic, LAYER_BACKGROUND to heic),
            uuid = uuid,
        )
        val title = PrPosterTitleStyleConfiguration(
            alternateDateEnabled = false,
            contentsLuminence = 0.0,
            groupName = "PREditingLook",
            preferredTitleAlignment = 0u,
            preferredTitleLayout = 0u,
            timeFontConfiguration = PrPosterSystemTimeFontConfiguration(true, "PRTimeFontIdentifierSFPro", 400.0),
            timeNumberingSystem = timeNumberingSystem,
            titleColor = PrPosterColor(
                2u, "vibrantMaterialColor", false,
                UiColor.GrayscaleAlphaColorSpace(2u, 1.0, 0.5, "1 0.5".toByteArray(Charsets.US_ASCII), 4u, "PRPosterColor"),
            ),
            titleContentStyle = ByteArray(0),
            userConfigured = false,
            // Packing needs one (the library archives it); PosterKit's default.
            titleStyle = PrPosterContentMaterialStyle.PrPosterContentVibrantMaterialStyle,
        )
        return SimplifiedTranscriptPoster(
            watch = WatchBackground(false, 0.0, watchPng, PHOTOS_PROVIDER),
            poster = SimplifiedPoster(title, PosterType.Photo(listOf(asset)), PosterRole.PR_POSTER_ROLE_BACKDROP),
        )
    }

    // --- pictures (Android graphics) ----------------------------------------------------------------

    /**
     * Writes the picture to show for [poster] to [out] as a JPEG, from the first of its
     * [pictureSources] that decodes (HEIC included: the app's own decoder). False when none does.
     */
    fun writePicture(poster: SimplifiedTranscriptPoster, out: File): Boolean {
        for ((source, crop) in pictureSources(poster)) {
            val bitmap = try {
                Media.decodeFirstFrame(source.bytes)
            } catch (e: Exception) {
                Log.w(TAG, "background picture not decoded", e)
                null
            } ?: continue
            val box = crop?.let { cropOf(it, bitmap.width, bitmap.height) }
            val cut = box?.let { Bitmap.createBitmap(bitmap, it.left, it.top, it.width, it.height) } ?: bitmap
            if (writeJpeg(Media.scaledToFit(cut, MAX_SIDE), out)) return true
        }
        return false
    }

    /** [bitmap] as a JPEG at [out]. */
    fun writeJpeg(bitmap: Bitmap, out: File, quality: Int = JPEG_QUALITY): Boolean = try {
        out.parentFile?.mkdirs()
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) } && out.length() > 0
    } catch (e: java.io.IOException) {
        Log.w(TAG, "background picture not written", e)
        false
    }

    /**
     * The picture at [source] (a photo the user picked, any format Android or the app reads),
     * upright and at most [MAX_SIDE] on its longest side, as a JPEG at [out]: the picture that is
     * shown here and sent. False when it is no picture.
     */
    fun prepareOutgoing(source: ByteArray, out: File): Boolean {
        val bitmap = try {
            if (ImageSniffer.isHeif(source)) Media.decodeFirstFrame(source) else decodeBounded(source)
        } catch (e: Exception) {
            null
        } ?: return false
        return writeJpeg(Media.scaledToFit(bitmap, MAX_SIDE), out)
    }

    /** A JPEG/PNG/WebP decoded at no more than about twice [MAX_SIDE], upright. */
    private fun decodeBounded(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return ExifOrientation.upright(bytes, bitmap)
    }

    /** The Watch's picture of [bitmap] as a background: centre-cut to [WATCH_WIDTH] x [WATCH_HEIGHT], PNG. */
    fun watchPng(bitmap: Bitmap): ByteArray {
        val out = Bitmap.createBitmap(WATCH_WIDTH, WATCH_HEIGHT, Bitmap.Config.ARGB_8888)
        val c = centeredCrop(bitmap.width.toDouble(), bitmap.height.toDouble(), WATCH_WIDTH.toDouble() / WATCH_HEIGHT)
        val src = Rect(c.x.toInt(), c.y.toInt(), (c.x + c.width).toInt(), (c.y + c.height).toInt())
        Canvas(out).drawBitmap(bitmap, src, Rect(0, 0, WATCH_WIDTH, WATCH_HEIGHT), Paint(Paint.FILTER_BITMAP_FLAG))
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /**
     * [bitmap] as HEIC (the phone's HEVC encoder, Android 9+), or null where it cannot be made:
     * the caller then sends the JPEG it has.
     */
    fun heic(bitmap: Bitmap, scratch: File): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        // HEVC wants even sides.
        val even = if (bitmap.width % 2 == 0 && bitmap.height % 2 == 0) bitmap else {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width and 1.inv()).coerceAtLeast(2), (bitmap.height and 1.inv()).coerceAtLeast(2), true)
        }
        return try {
            scratch.delete()
            val writer = androidx.heifwriter.HeifWriter.Builder(scratch.path, even.width, even.height, androidx.heifwriter.HeifWriter.INPUT_MODE_BITMAP)
                .setQuality(HEIC_QUALITY)
                .setMaxImages(1)
                .build()
            try {
                writer.start()
                writer.addBitmap(even)
                writer.stop(HEIC_TIMEOUT_MS)
            } finally {
                writer.close()
            }
            scratch.readBytes().takeIf { ImageSniffer.isHeif(it) }
        } catch (e: Exception) {
            Log.w(TAG, "no HEIC encoder; the background goes as JPEG", e)
            null
        } finally {
            scratch.delete()
        }
    }

    private const val TAG = "BackgroundPosters"
    private const val JPEG_QUALITY = 88
    private const val HEIC_QUALITY = 85
    private const val HEIC_TIMEOUT_MS = 15_000L
}

/**
 * A background change from Apple, in the ":imessage" process: its poster is fetched from Apple's
 * file servers and its picture written where the main process takes it from
 * ([ChatBackgrounds.incomingFile]), then it becomes the archive's change
 * ([DirectArchive.background]). The library's calls are passed in ([Natives]) so this can be tested
 * without it.
 */
class IncomingBackgrounds(private val app: Context, private val natives: Natives) {

    /** What is asked of the library (and of the picture code). */
    interface Natives {
        /** Where the poster is ([uniffi.rust_bridge.transcriptBackgroundToMmcs]). */
        fun toMmcs(message: SetTranscriptBackgroundMessage): MmcsFile?

        /** Fetches [file] into [path] ([uniffi.rust_bridge.downloadMmcsFile]). */
        suspend fun download(file: MmcsFile, path: String)

        /** Reads the poster ([uniffi.rust_bridge.parseTranscriptPoster]). */
        fun parse(payload: ByteArray): SimplifiedTranscriptPoster

        /** Writes the poster's picture to [out] ([BackgroundPosters.writePicture]). */
        fun writePicture(poster: SimplifiedTranscriptPoster, out: File): Boolean = BackgroundPosters.writePicture(poster, out)
    }

    /**
     * [inst] (a [PushChatMessage.SetTranscriptBackground]) as the archive's change. Ignored when it
     * names no chat or its picture cannot be made; throws when the poster could not be fetched or read.
     */
    suspend fun receive(inst: MessageInst, me: Set<String>, nameOf: (String) -> String, texts: DirectArchive.Texts): DirectArchive.Update {
        val m = (inst.message as? PushChatMessage.SetTranscriptBackground)?.v1 ?: return DirectArchive.Update.Ignore
        if (DirectArchive.backgroundChat(inst, me) == null) return DirectArchive.Update.Ignore
        val path = when (m) {
            is SetTranscriptBackgroundMessage.Remove -> null
            is SetTranscriptBackgroundMessage.Set -> {
                val file = natives.toMmcs(m) ?: return DirectArchive.Update.Ignore
                val payload = fetch(file)
                val poster = natives.parse(payload)
                if (BackgroundPosters.isRemoval(poster)) {
                    null
                } else {
                    val out = ChatBackgrounds.incomingFile(app)
                    if (!natives.writePicture(poster, out)) {
                        out.delete()
                        Log.w(TAG, "a chat background with no picture that can be shown here (${poster.poster.type::class.simpleName})")
                        return DirectArchive.Update.Ignore
                    }
                    out.path
                }
            }
        }
        return DirectArchive.background(inst, me, nameOf, texts, path)
    }

    private suspend fun fetch(file: MmcsFile): ByteArray {
        val partial = File(ChatBackgrounds.dir(app).apply { mkdirs() }, "poster-${UUID.randomUUID()}.zip")
        try {
            // The library makes the file itself.
            natives.download(file, partial.path)
            return partial.readBytes()
        } finally {
            partial.delete()
        }
    }

    private companion object {
        const val TAG = "IncomingBackgrounds"
    }
}
