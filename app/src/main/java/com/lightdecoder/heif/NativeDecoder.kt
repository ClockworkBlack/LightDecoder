package com.lightdecoder.heif

/**
 * Thin 1:1 binding to the native decoder in liblightdecoder.so.
 *
 * A handle is an opaque pointer to a native decode session. Every [open] that
 * returns a non-zero handle must be balanced by exactly one [nativeClose]; the
 * higher-level [HeifCodec] owns that lifecycle so callers never touch handles.
 */
internal object NativeDecoder {

    @Volatile
    private var loaded = false

    /** Loads the native library once. Returns false if it is unavailable. */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("lightdecoder")
            loaded = true
            true
        } catch (t: UnsatisfiedLinkError) {
            false
        }
    }

    external fun nativeVersions(): String

    /** Returns a native session handle, or 0 if the bytes are not a usable HEIF file. */
    external fun nativeOpen(data: ByteArray): Long

    external fun nativeClose(handle: Long)

    external fun nativeDescribe(handle: Long): String

    external fun nativeLastError(handle: Long): String

    external fun nativeImageCount(handle: Long): Int

    external fun nativeHasSequence(handle: Long): Boolean

    external fun nativePrepareStill(handle: Long, index: Int): Boolean

    /** 1 = a frame is ready, 0 = end of the sequence, -1 = error (see [nativeLastError]). */
    external fun nativePrepareNextFrame(handle: Long): Int

    external fun nativeRewind(handle: Long): Boolean

    external fun nativePreparedWidth(handle: Long): Int

    external fun nativePreparedHeight(handle: Long): Int

    external fun nativePreparedHasAlpha(handle: Long): Boolean

    external fun nativePreparedDurationMs(handle: Long): Int

    /** Fills an ARGB_8888, premultiplied Bitmap of exactly the prepared size. */
    external fun nativeFillBitmap(handle: Long, bitmap: android.graphics.Bitmap): Boolean

    /** Straight (non-premultiplied) RGBA bytes of the prepared frame, row-major. */
    external fun nativeCopyRGBA(handle: Long): ByteArray?
}
