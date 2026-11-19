package com.lightdecoder.ui.share

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import java.io.File
import java.util.UUID

/** What another app shared to LightDecoder: some text, some files, or both. */
data class SharedContent(val text: String, val streams: List<Uri>) {
    val isEmpty: Boolean get() = text.isEmpty() && streams.isEmpty()

    companion object {
        /** More than this many files at once are not taken (the rest are left out). */
        const val MAX_STREAMS = 10

        /** A file larger than this is not taken. */
        const val MAX_BYTES = 100L * 1024 * 1024

        private const val DIR = "shared-in"
        private const val KEEP_MS = 24 * 60 * 60 * 1000L

        /**
         * Reads a share intent. Only content: uris of other apps are taken:
         * a file: path, or a uri of this app's own provider, could name the
         * app's private files, which nothing outside may make it send.
         */
        fun from(context: Context, intent: Intent?): SharedContent {
            if (intent == null) return SharedContent("", emptyList())
            val streams = when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                Intent.ACTION_SEND_MULTIPLE ->
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty().filterNotNull()
                else -> return SharedContent("", emptyList())
            }
            val own = context.packageName
            val taken = streams
                .filter { it.scheme == ContentResolver.SCHEME_CONTENT && it.authority?.startsWith(own) != true }
                .distinct()
                .take(MAX_STREAMS)
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            return SharedContent(text, taken)
        }

        /**
         * Copies [streams] into the app's cache and returns the copies as
         * uris of the app's own provider, in order; one that cannot be read
         * (or is too large) is left out. The sharing app's permission to
         * read its uri ends with the share screen, and the copy is what the
         * conversation then holds until it is sent. Not on the main thread.
         */
        fun copyIn(context: Context, streams: List<Uri>): List<Uri> {
            val root = File(context.cacheDir, DIR)
            purge(root, System.currentTimeMillis())
            return streams.mapNotNull { source ->
                val dir = File(root, UUID.randomUUID().toString())
                try {
                    dir.mkdirs()
                    val file = File(dir, fileName(nameOf(context, source), context.contentResolver.getType(source)))
                    val whole = context.contentResolver.openInputStream(source)?.use { input ->
                        file.outputStream().use { out -> copyCapped(input, out, MAX_BYTES) }
                    } ?: false
                    if (!whole || file.length() == 0L) {
                        dir.deleteRecursively()
                        null
                    } else {
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    }
                } catch (e: Exception) {
                    dir.deleteRecursively()
                    null
                }
            }
        }

        /** Copies of earlier shares that are a day old: sent, or given up on. */
        internal fun purge(root: File, nowMs: Long) {
            root.listFiles()?.forEach { if (nowMs - it.lastModified() > KEEP_MS) it.deleteRecursively() }
        }

        private fun copyCapped(input: java.io.InputStream, out: java.io.OutputStream, max: Long): Boolean {
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) return true
                total += n
                if (total > max) return false
                out.write(buffer, 0, n)
            }
        }

        private fun nameOf(context: Context, uri: Uri): String? = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        }.getOrNull()

        /**
         * A safe file name for the copy: no folders in it, and an extension
         * that says its type (the app's provider tells the type from it).
         */
        internal fun fileName(given: String?, mime: String?): String {
            val clean = given.orEmpty().substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim('.', ' ').takeLast(80)
            val base = clean.ifEmpty { "shared" }
            val known = MimeTypeMap.getSingleton()
            val extension = base.substringAfterLast('.', "").lowercase()
            if (extension.isNotEmpty() && known.hasExtension(extension)) return base
            val fromType = mime?.let { known.getExtensionFromMimeType(it) }
            return if (fromType.isNullOrEmpty()) base else "$base.$fromType"
        }
    }
}
