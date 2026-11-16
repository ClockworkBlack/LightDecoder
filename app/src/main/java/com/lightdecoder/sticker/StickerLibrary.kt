package com.lightdecoder.sticker

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/**
 * The stickers the user made, as PNG files in the app's private storage
 * (never backed up, like everything else the app keeps). Call off the main
 * thread.
 */
object StickerLibrary {

    private const val DIR = "my-stickers"

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** Newest first. */
    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".png") }.orEmpty().sortedByDescending { it.lastModified() }

    fun save(context: Context, sticker: Bitmap): File {
        val target = File(dir(context), "sticker-${System.currentTimeMillis()}.png")
        val part = File(target.path + ".part")
        try {
            FileOutputStream(part).use { out ->
                if (!sticker.compress(Bitmap.CompressFormat.PNG, 100, out)) throw java.io.IOException("could not encode")
                out.fd.sync()
            }
            if (!part.renameTo(target)) throw java.io.IOException("could not store")
        } finally {
            part.delete()
        }
        return target
    }

    /** Only deletes files of the library itself. */
    fun delete(context: Context, file: File): Boolean =
        file.parentFile?.canonicalFile == dir(context).canonicalFile && file.delete()
}
