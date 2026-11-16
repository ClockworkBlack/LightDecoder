package com.lightdecoder.archive

import android.content.Context
import java.io.File
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

    fun mediaDir(context: Context) = File(context.applicationContext.filesDir, MEDIA_DIR)

    fun outboxDir(context: Context) = File(context.applicationContext.filesDir, OUTBOX_DIR)

    /** The cache file for one media id (full size or thumbnail id), with [extension] like "heic". */
    fun fileFor(context: Context, mediaId: String, extension: String): File {
        val dir = mediaDir(context).apply { mkdirs() }
        val ext = extension.filter { it.isLetterOrDigit() }.take(8).ifEmpty { "bin" }
        return File(dir, "${hash(mediaId)}.$ext")
    }

    /** The local copy of an attachment queued for sending. */
    fun outboxFile(context: Context, tmpId: String): File {
        val dir = outboxDir(context).apply { mkdirs() }
        return File(dir, tmpId.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { hash(tmpId) })
    }

    internal fun mediaDir(filesDir: File) = File(filesDir, MEDIA_DIR)

    internal fun outboxDir(filesDir: File) = File(filesDir, OUTBOX_DIR)

    internal fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
}
