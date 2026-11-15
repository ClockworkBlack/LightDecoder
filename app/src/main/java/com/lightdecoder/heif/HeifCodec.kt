package com.lightdecoder.heif

import android.graphics.Bitmap

/** One decoded animation frame and how long to show it. */
class HeifFrame(val bitmap: Bitmap, val durationMs: Int)

/** The facts the native layer reports about a file, parsed from its describe() text. */
data class HeifInfo(
    val mime: String,
    val brands: String,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val hasAlpha: Boolean,
    val isSequence: Boolean,
    val stillCount: Int,
    val raw: Map<String, String>,
) {
    val summary: String
        get() = buildString {
            append(if (isSequence) "Animated sticker" else "Sticker")
            if (width > 0 && height > 0) append(" · ${width}×$height")
            if (hasAlpha) append(" · transparent")
            append(" · ${bytes / 1024} KB")
        }
}

/**
 * The high-level entry point: turn HEIC bytes into Android [Bitmap]s, decoding
 * transparency and animation that the system image pipeline on many phones
 * cannot. Everything here is main-thread-safe to *call* but does real work, so
 * run it off the UI thread.
 */
object HeifCodec {

    class DecodeException(message: String) : Exception(message)

    val isAvailable: Boolean get() = NativeDecoder.ensureLoaded()

    val versions: String
        get() = if (NativeDecoder.ensureLoaded()) NativeDecoder.nativeVersions() else "unavailable"

    /** Reads just the metadata without decoding pixels. */
    @Throws(DecodeException::class)
    fun probe(data: ByteArray): HeifInfo = withHandle(data) { handle ->
        parseInfo(NativeDecoder.nativeDescribe(handle))
    }

    /** Decodes the primary still (or the first frame of an animation) to one Bitmap. */
    @Throws(DecodeException::class)
    fun decodeStill(data: ByteArray): Bitmap = withHandle(data) { handle ->
        if (NativeDecoder.nativeHasSequence(handle)) {
            if (NativeDecoder.nativePrepareNextFrame(handle) != 1) {
                throw DecodeException(NativeDecoder.nativeLastError(handle))
            }
        } else if (!NativeDecoder.nativePrepareStill(handle, 0)) {
            throw DecodeException(NativeDecoder.nativeLastError(handle))
        }
        preparedToBitmap(handle)
    }

    /**
     * Decodes every frame of an animated sticker. For a still, returns a single
     * frame. [maxFrames] guards against pathological files.
     */
    @Throws(DecodeException::class)
    fun decodeAll(data: ByteArray, maxFrames: Int = 1000): List<HeifFrame> = withHandle(data) { handle ->
        val frames = ArrayList<HeifFrame>()
        if (NativeDecoder.nativeHasSequence(handle)) {
            while (frames.size < maxFrames) {
                when (NativeDecoder.nativePrepareNextFrame(handle)) {
                    1 -> frames.add(HeifFrame(preparedToBitmap(handle),
                        NativeDecoder.nativePreparedDurationMs(handle)))
                    0 -> break
                    else -> throw DecodeException(NativeDecoder.nativeLastError(handle))
                }
            }
        } else {
            if (!NativeDecoder.nativePrepareStill(handle, 0)) {
                throw DecodeException(NativeDecoder.nativeLastError(handle))
            }
            frames.add(HeifFrame(preparedToBitmap(handle), 0))
        }
        if (frames.isEmpty()) throw DecodeException("No frames could be decoded")
        frames
    }

    // --- internals -----------------------------------------------------------

    private fun <T> withHandle(data: ByteArray, block: (Long) -> T): T {
        if (!NativeDecoder.ensureLoaded()) throw DecodeException("Decoder library not available")
        val handle = NativeDecoder.nativeOpen(data)
        if (handle == 0L) throw DecodeException("This file is not a readable HEIC image")
        try {
            return block(handle)
        } finally {
            NativeDecoder.nativeClose(handle)
        }
    }

    private fun preparedToBitmap(handle: Long): Bitmap {
        val w = NativeDecoder.nativePreparedWidth(handle)
        val h = NativeDecoder.nativePreparedHeight(handle)
        if (w <= 0 || h <= 0) throw DecodeException("Decoded frame has no size")
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (!NativeDecoder.nativeFillBitmap(handle, bitmap)) {
            bitmap.recycle()
            throw DecodeException(NativeDecoder.nativeLastError(handle))
        }
        return bitmap
    }

    private fun parseInfo(describe: String): HeifInfo {
        val map = HashMap<String, String>()
        for (line in describe.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq > 0) map[line.substring(0, eq)] = line.substring(eq + 1)
        }
        val isSeq = map["sequence"] == "1"
        return HeifInfo(
            mime = map["mime"] ?: "image/heic",
            brands = map["brands"] ?: "",
            bytes = map["bytes"]?.toLongOrNull() ?: 0L,
            width = (if (isSeq) map["seq_width"] else map["width"])?.toIntOrNull() ?: 0,
            height = (if (isSeq) map["seq_height"] else map["height"])?.toIntOrNull() ?: 0,
            hasAlpha = (if (isSeq) map["seq_alpha"] else map["alpha"]) == "1",
            isSequence = isSeq,
            stillCount = map["still_images"]?.toIntOrNull() ?: 0,
            raw = map,
        )
    }
}
