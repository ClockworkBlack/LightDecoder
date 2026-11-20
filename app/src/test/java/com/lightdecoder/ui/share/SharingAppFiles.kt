package com.lightdecoder.ui.share

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File

/**
 * Another app's files, as it shares them: content: uris of its own provider ([AUTHORITY]),
 * with their names, sizes and types. Set up with Robolectric.setupContentProvider.
 */
class SharingAppFiles : ContentProvider() {
    private val dir: File get() = File(context!!.cacheDir, "sharing-app").apply { mkdirs() }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(File(dir, uri.lastPathSegment!!), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = File(dir, uri.lastPathSegment!!)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(file.name, file.length())) }
    }

    override fun getType(uri: Uri): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(uri.lastPathSegment!!.substringAfterLast('.').lowercase())
            ?: when (uri.lastPathSegment!!.substringAfterLast('.').lowercase()) {
                "jpg" -> "image/jpeg"
                "png" -> "image/png"
                "mov" -> "video/quicktime"
                else -> null
            }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.example.gallery.files"

        /** A file of the sharing app's, and its uri. */
        fun add(context: android.content.Context, name: String, bytes: ByteArray): Uri {
            File(File(context.cacheDir, "sharing-app").apply { mkdirs() }, name).writeBytes(bytes)
            return Uri.parse("content://$AUTHORITY/$name")
        }
    }
}
