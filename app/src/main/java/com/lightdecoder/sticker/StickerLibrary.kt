package com.lightdecoder.sticker

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import com.lightdecoder.heif.Media
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The stickers the user made, as PNG files in the app's private storage.
 * Call off the main thread.
 *
 * That storage survives updates, but not uninstalling the app (or a new
 * phone). So each sticker is also copied to the phone's gallery, in
 * Pictures/[GALLERY_DIR] (Android 10+), where the user can see it and from
 * where [import] brings stickers back. These copies are the user's own
 * pictures, put there for them; the rest of the app's data is still never
 * backed up.
 */
object StickerLibrary {

    private const val DIR = "my-stickers"
    private const val PREFS = "stickers"
    private const val KEY_COPIED = "gallery_copies"

    /** The gallery folder the copies go to, under Pictures. */
    const val GALLERY_DIR = "LightDecoder Stickers"

    /** A picture larger than this is not taken in as a sticker. */
    private const val MAX_IMPORT_BYTES = 20 * 1024 * 1024

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** Newest first. */
    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".png") }.orEmpty().sortedByDescending { it.lastModified() }

    /** Stores a sticker just made, and copies it to the gallery. */
    fun save(context: Context, sticker: Bitmap): File {
        val target = write(context, sticker)
        copyToGallery(context, target)
        return target
    }

    /**
     * Puts an edited version in place of the library's sticker [file] (same
     * name, so it keeps its place), and renews its gallery copy.
     */
    fun replace(context: Context, file: File, sticker: Bitmap): File {
        require(file.parentFile?.canonicalFile == dir(context).canonicalFile) { "not a sticker of the library" }
        writeTo(file, sticker)
        removeFromGallery(context, file.name)
        copyToGallery(context, file)
        return file
    }

    /** Whether [file] is one of the library's stickers (only those can be edited or replaced). */
    fun owns(context: Context, file: File): Boolean =
        file.isFile && file.parentFile?.canonicalFile == dir(context).canonicalFile

    private fun write(context: Context, sticker: Bitmap): File {
        var target = File(dir(context), "sticker-${System.currentTimeMillis()}.png")
        while (target.exists()) target = File(dir(context), "sticker-${System.currentTimeMillis() + 1}.png")
        return writeTo(target, sticker)
    }

    private fun writeTo(target: File, sticker: Bitmap): File {
        val part = File(target.path + ".part")
        try {
            FileOutputStream(part).use { out ->
                if (!sticker.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("could not encode")
                out.fd.sync()
            }
            if (!part.renameTo(target)) throw IOException("could not store")
        } finally {
            part.delete()
        }
        return target
    }

    /** Deletes a sticker of the library itself, and its gallery copy. */
    fun delete(context: Context, file: File): Boolean {
        if (file.parentFile?.canonicalFile != dir(context).canonicalFile) return false
        val deleted = file.delete()
        if (deleted) {
            removeFromGallery(context, file.name)
            StickerProject.delete(context, file)
        }
        return deleted
    }

    /** Copies to the gallery the stickers that have no copy there yet (made before copies existed). */
    fun copyMissingToGallery(context: Context) {
        if (Build.VERSION.SDK_INT < 29) return
        val copied = copied(context)
        for (file in list(context)) if (file.name !in copied) copyToGallery(context, file)
    }

    /**
     * Takes pictures the user picked (stickers from the gallery copies, or any
     * picture) into the library, fitted to a sticker's size. Returns how many
     * were added. Their gallery originals are not copied again.
     */
    fun import(context: Context, uris: List<Uri>): Int {
        var added = 0
        for (uri in uris) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > MAX_IMPORT_BYTES) return@use null
                    }
                    out.toByteArray()
                }
            }.getOrNull() ?: continue
            val bitmap = runCatching { Media.decodeFirstFrame(bytes) }.getOrNull() ?: continue
            val fitted = Media.scaledToFit(bitmap, StickerMaker.SIZE)
            val file = runCatching { write(context, fitted) }.getOrNull() ?: continue
            markCopied(context, file.name)
            added++
        }
        return added
    }

    // --- the gallery copies -------------------------------------------------------------

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun copied(context: Context): Set<String> = prefs(context).getStringSet(KEY_COPIED, emptySet()).orEmpty()

    private fun markCopied(context: Context, name: String) =
        prefs(context).edit { putStringSet(KEY_COPIED, copied(context) + name) }

    private fun copyToGallery(context: Context, file: File) {
        if (Build.VERSION.SDK_INT < 29) return
        runCatching { insert(context, file) }.onSuccess { markCopied(context, file.name) }
    }

    @RequiresApi(29)
    private fun insert(context: Context, file: File) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$GALLERY_DIR")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("no gallery")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw IOException("no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun removeFromGallery(context: Context, name: String) {
        prefs(context).edit { putStringSet(KEY_COPIED, copied(context) - name) }
        if (Build.VERSION.SDK_INT < 29) return
        // Only this app's own copy can be found and deleted this way.
        runCatching {
            context.contentResolver.delete(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                arrayOf(name, "Pictures/$GALLERY_DIR%"),
            )
        }
    }
}
