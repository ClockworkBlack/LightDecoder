package com.lightdecoder.archive

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Where relay media lives on disk, all under app-private storage:
 *
 *   filesDir/relay-media/<sha256(mediaId), 32 hex>.<ext>   downloaded media and thumbnails
 *   filesDir/relay-outbox/<tmpId>                          copies of attachments waiting to be sent
 *
 * File names are hashes so no id from the phone ends up in a path.
 */
object MediaCache {
    private const val MEDIA_DIR = "relay-media"
    private const val OUTBOX_DIR = "relay-outbox"

    /** Cache keys of media that came inside the message (no media id) start with this. */
    const val INLINE_KEY_PREFIX = "inline:"

    /** Suffix of a file still being written by [writeAtomically]. */
    internal const val PART_SUFFIX = ".part"

    /** A [PART_SUFFIX] file this old was left by a write that never finished (the app died mid-write). */
    internal const val STALE_PART_MS = 60 * 60 * 1000L

    fun mediaDir(context: Context) = File(context.applicationContext.filesDir, MEDIA_DIR)

    fun outboxDir(context: Context) = File(context.applicationContext.filesDir, OUTBOX_DIR)

    /** The cache file for one media id (full size or thumbnail id), with [extension] like "heic". */
    fun fileFor(context: Context, mediaId: String, extension: String): File {
        val dir = mediaDir(context).apply { mkdirs() }
        val ext = extension.filter { it.isLetterOrDigit() }.take(8).ifEmpty { "bin" }
        return File(dir, "${hash(mediaId)}.$ext")
    }

    /** The cache key for media that came inside message [messageId], part [partId]. */
    fun inlineKey(messageId: String, partId: String) = "$INLINE_KEY_PREFIX$messageId:$partId"

    /**
     * Whether [path] is the file [fileFor] gives the inline bytes of message
     * [messageId], part [partId]. Earlier versions stored a part's inline
     * preview as its local file even when the real media could be
     * downloaded; such a file is not the picture.
     */
    fun isInlineFile(path: String, messageId: String, partId: String): Boolean =
        path.isNotEmpty() && File(path).nameWithoutExtension == hash(inlineKey(messageId, partId))

    /**
     * Writes [bytes] to [file] whole or not at all: into a [PART_SUFFIX] file
     * first, renamed into place once complete. If anything fails the partial
     * file is removed before the exception is passed on.
     *
     * Each write has a temporary file of its own: two downloads of the same
     * media (the sticker check and the conversation screen, say) can run at
     * once, and with one shared name each would truncate and rename the
     * other's half-written file. Whichever renames last wins, with a whole
     * file either way.
     */
    @Throws(IOException::class)
    fun writeAtomically(file: File, bytes: ByteArray) {
        val tmp = File.createTempFile(file.name + ".", PART_SUFFIX, file.parentFile)
        var done = false
        try {
            FileOutputStream(tmp).use { it.write(bytes) }
            if (!tmp.renameTo(file)) throw IOException("rename failed")
            done = true
        } finally {
            if (!done) tmp.delete()
        }
    }

    /** The local copy of an attachment queued for sending. */
    fun outboxFile(context: Context, tmpId: String): File {
        val dir = outboxDir(context).apply { mkdirs() }
        return File(dir, tmpId.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { hash(tmpId) })
    }

    internal fun mediaDir(filesDir: File) = File(filesDir, MEDIA_DIR)

    internal fun outboxDir(filesDir: File) = File(filesDir, OUTBOX_DIR)

    /**
     * Deletes [PART_SUFFIX] files in [dir] last written more than
     * [STALE_PART_MS] before [nowMs]: writes that never finished. A write in
     * progress is never that old, so this is safe at any time. Returns how
     * many were deleted.
     */
    internal fun deleteStaleParts(dir: File, nowMs: Long): Int =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(PART_SUFFIX) && nowMs - it.lastModified() > STALE_PART_MS }
            .count { it.delete() }

    internal fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
}
