package com.lightdecoder.imessage.direct

/**
 * The types of iMessage attachments: Apple names a file's type by a UTI ("public.jpeg"), Android
 * by a MIME type ("image/jpeg"). Both directions, and the file extension a downloaded attachment
 * is kept under. Pure: tested in DirectMediaTest.
 */
object DirectMedia {

    /** Apple takes attachments up to this size; a larger one is refused here, with a clear error. */
    const val MAX_SEND_BYTES = 100L * 1024 * 1024

    /**
     * The most of an attachment downloaded (Apple's own limit for one): whatever size its message
     * says it has, a download that brings more is stopped and thrown out.
     */
    const val MAX_DOWNLOAD_BYTES = MAX_SEND_BYTES

    /**
     * Where the ":imessage" process writes a small attachment that came inside a message, whose
     * part's file is [file], until the main process has taken the message: only then is it moved
     * into place, so a message the main process drops (one reusing another's GUID) never overwrites
     * another message's file. Named as a partial file, so one never taken is cleaned up with them
     * ([com.lightdecoder.archive.MediaCache.deleteStaleParts]).
     */
    fun pendingInlineFile(file: java.io.File): java.io.File =
        java.io.File(file.parentFile, file.name + ".inline" + com.lightdecoder.archive.MediaCache.PART_SUFFIX)

    const val OCTET_STREAM = "application/octet-stream"

    /** What any other file is to Apple. */
    const val UTI_DATA = "public.data"

    /** The MIME types Apple's UTIs stand for (OpenBubbles' `mimeToUti`, both ways, and Apple's audio types). */
    private val UTI_MIME = listOf(
        "public.jpeg" to "image/jpeg",
        "public.png" to "image/png",
        "com.compuserve.gif" to "image/gif",
        "public.heic" to "image/heic",
        "public.heics" to "image/heic-sequence",
        "public.heif" to "image/heif",
        "public.tiff" to "image/tiff",
        "org.webmproject.webp" to "image/webp",
        "com.microsoft.bmp" to "image/bmp",
        "public.mpeg-4" to "video/mp4",
        "com.apple.quicktime-movie" to "video/quicktime",
        "public.3gpp" to "video/3gpp",
        "public.3gpp2" to "video/3gpp2",
        "public.mpeg-4-audio" to "audio/mp4",
        "com.apple.m4a-audio" to "audio/mp4",
        "public.mp3" to "audio/mpeg",
        "public.aac-audio" to "audio/aac",
        "org.3gpp.adaptive-multi-rate-audio" to "audio/amr",
        "org.xiph.ogg-audio" to "audio/ogg",
        "com.apple.coreaudio-format" to "audio/x-caf",
        "com.microsoft.waveform-audio" to "audio/x-wav",
        "public.vcard" to "text/vcard",
        "public.vlocation" to "text/x-vlocation",
        "public.plain-text" to "text/plain",
        "com.adobe.pdf" to "application/pdf",
    )

    /** The MIME types file extensions stand for. */
    private val EXTENSION_MIME = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "heic" to "image/heic", "heics" to "image/heic-sequence", "heif" to "image/heif", "tif" to "image/tiff",
        "tiff" to "image/tiff", "webp" to "image/webp", "bmp" to "image/bmp",
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime", "3gp" to "video/3gpp", "3g2" to "video/3gpp2",
        "m4a" to "audio/mp4", "mp3" to "audio/mpeg", "aac" to "audio/aac", "amr" to "audio/amr", "ogg" to "audio/ogg",
        "opus" to "audio/ogg", "caf" to "audio/x-caf", "wav" to "audio/x-wav",
        "vcf" to "text/vcard", "txt" to "text/plain", "pdf" to "application/pdf",
        "zip" to "application/zip",
    )

    /** The extension a file of each MIME type is kept under, when its name has none. */
    private val MIME_EXTENSION = mapOf(
        "image/jpeg" to "jpg", "image/png" to "png", "image/gif" to "gif", "image/heic" to "heic",
        "image/heic-sequence" to "heics", "image/heif" to "heif", "image/tiff" to "tiff", "image/webp" to "webp",
        "image/bmp" to "bmp", "video/mp4" to "mp4", "video/quicktime" to "mov", "video/3gpp" to "3gp",
        "video/3gpp2" to "3g2", "audio/mp4" to "m4a", "audio/mpeg" to "mp3", "audio/aac" to "aac", "audio/amr" to "amr",
        "audio/ogg" to "ogg", "audio/x-caf" to "caf", "audio/x-wav" to "wav", "text/vcard" to "vcf",
        "text/x-vlocation" to "vcf", "text/plain" to "txt", "application/pdf" to "pdf", "application/zip" to "zip",
    )

    /**
     * An incoming attachment's MIME type: Apple's own when it names one ([mime], not a wildcard or
     * "any data"), else the one its UTI stands for, else its file name's, else "any data".
     */
    fun mimeFor(mime: String, uti: String, name: String): String {
        val given = mime.substringBefore(';').trim().lowercase()
        if (isSpecific(given)) return given
        UTI_MIME.firstOrNull { it.first == uti.trim().lowercase() }?.let { return it.second }
        return mimeOfName(name) ?: OCTET_STREAM
    }

    /** The MIME type a file name's extension stands for, or null. */
    fun mimeOfName(name: String): String? {
        val lower = name.trim().lowercase()
        if (lower.endsWith(".loc.vcf")) return "text/x-vlocation"
        return EXTENSION_MIME[lower.substringAfterLast('.', "")]
    }

    /** Whether [mime] names a type ("image/jpeg"), not just a kind (a wildcard such as any image) or "any data". */
    fun isSpecific(mime: String): Boolean {
        val type = mime.substringBefore('/')
        val subtype = mime.substringAfter('/', "")
        return type.isNotEmpty() && subtype.isNotEmpty() && '*' !in mime && mime != OCTET_STREAM
    }

    /** Apple's UTI for a file of type [mime] being sent (OpenBubbles' `mimeToUti`). */
    fun utiFor(mime: String): String = when (val m = mime.substringBefore(';').trim().lowercase()) {
        "image/jpg" -> "public.jpeg"
        "text/x-vcard" -> "public.vcard"
        else -> SENT_UTI[m] ?: UTI_DATA
    }

    /** OpenBubbles' table: these types and no others go with their own UTI (anything else, Apple's .caf included, is data). */
    private val SENT_UTI = mapOf(
        "image/jpeg" to "public.jpeg", "image/png" to "public.png", "image/gif" to "com.compuserve.gif",
        "image/heic" to "public.heic", "image/heif" to "public.heif", "image/tiff" to "public.tiff",
        "image/webp" to "org.webmproject.webp", "image/bmp" to "com.microsoft.bmp",
        "video/mp4" to "public.mpeg-4", "video/quicktime" to "com.apple.quicktime-movie",
        "video/3gpp" to "public.3gpp", "video/3gpp2" to "public.3gpp2",
        "audio/mp4" to "public.mpeg-4-audio", "audio/mpeg" to "public.mp3", "audio/amr" to "org.3gpp.adaptive-multi-rate-audio",
        "audio/ogg" to "org.xiph.ogg-audio", "text/vcard" to "public.vcard", "text/x-vlocation" to "public.vlocation",
        "application/pdf" to "com.adobe.pdf",
    )

    /**
     * The extension an attachment's file is kept under: its name's (letters and digits, short),
     * else its type's, else "bin". The ":imessage" process (which writes small attachments that
     * came inside the message) and the main process (which downloads the rest) both name the file
     * by this, so each finds what the other wrote.
     */
    fun extensionFor(mime: String, name: String): String {
        val fromName = name.substringAfterLast('.', "").lowercase()
        if (fromName.isNotEmpty() && fromName.length <= 8 && fromName.all { it.isLetterOrDigit() }) return fromName
        return MIME_EXTENSION[mime.substringBefore(';').trim().lowercase()] ?: "bin"
    }

    /** A name for a file being sent that has none of its own. */
    fun nameFor(mime: String, base: String = "attachment"): String = "$base.${extensionFor(mime, "")}"
}
