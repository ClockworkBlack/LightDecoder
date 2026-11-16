package com.lightdecoder.wa

import android.webkit.MimeTypeMap
import com.lightdecoder.heif.ImageSniffer

/** What the WhatsApp runtime needs to know about a media file to store it. */
internal object WaMedia {

    /** Attachments larger than this are not downloaded: the bridge hands a download over in memory, whole. */
    const val MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024

    /**
     * A file extension for a stored attachment, so that other apps the file
     * is opened with know what it is: by content for the pictures
     * [ImageSniffer] knows, else the file name's own, else the MIME type's.
     * WhatsApp carries videos, voice messages and documents, which the
     * sniffer (made for pictures) would all call "bin".
     *
     * @param head the file's first bytes (a few dozen are enough)
     */
    fun extensionFor(head: ByteArray, mime: String, fileName: String): String {
        val byContent = ImageSniffer.extensionFor(head, null)
        if (byContent != UNKNOWN) return byContent
        val fromName = fileName.substringAfterLast('.', "").lowercase()
        if (fromName.isNotEmpty() && fromName.length <= 8 && fromName.all { it.isLetterOrDigit() }) return fromName
        val base = mime.substringBefore(';').trim().lowercase()
        val fromMime = try {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(base)
        } catch (e: RuntimeException) {
            null
        }
        return fromMime?.filter { it.isLetterOrDigit() }?.take(8)?.ifEmpty { null } ?: ImageSniffer.extensionFor(head, mime)
    }

    private const val UNKNOWN = "bin"
}
