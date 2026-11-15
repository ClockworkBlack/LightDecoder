package com.lightdecoder.heif

/** Tells file types apart by their first bytes, since senders often mislabel HEIC. */
object ImageSniffer {

    /** HEVC-coded HEIF brands, the ones the built-in decoder handles. */
    private val HEVC_BRANDS = setOf(
        "heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs", "mif1", "msf1",
    )
    private val AV1_BRANDS = setOf("avif", "avis")

    /**
     * True for a HEIC/HEIF file (still or animated) that the built-in decoder
     * should handle. AVIF shares the container but not the codec, so it is
     * left to Android's own decoder.
     */
    fun isHeif(bytes: ByteArray): Boolean {
        val brands = ftypBrands(bytes)
        if (brands.isEmpty() || brands.any { it in AV1_BRANDS }) return false
        return brands.any { it in HEVC_BRANDS }
    }

    /** The major and compatible brands of an ISO-BMFF file, or empty if it is not one. */
    fun ftypBrands(bytes: ByteArray): List<String> {
        if (bytes.size < 16) return emptyList()
        if (String(bytes, 4, 4, Charsets.ISO_8859_1) != "ftyp") return emptyList()
        val boxSize = ((bytes[0].toLong() and 0xFF) shl 24) or
            ((bytes[1].toLong() and 0xFF) shl 16) or
            ((bytes[2].toLong() and 0xFF) shl 8) or
            (bytes[3].toLong() and 0xFF)
        val end = minOf(boxSize, bytes.size.toLong(), 1024L).toInt()
        val brands = ArrayList<String>()
        brands.add(String(bytes, 8, 4, Charsets.ISO_8859_1))
        var offset = 16 // skip the minor version
        while (offset + 4 <= end) {
            brands.add(String(bytes, offset, 4, Charsets.ISO_8859_1))
            offset += 4
        }
        return brands
    }

    /** A file extension that fits the content, for naming stored and shared files. */
    fun extensionFor(bytes: ByteArray, mime: String?): String = when {
        isHeif(bytes) -> if (ftypBrands(bytes).any { it == "msf1" || it == "hevc" || it == "hevx" }) "heics" else "heic"
        startsWith(bytes, 0x89, 'P'.code, 'N'.code, 'G'.code) -> "png"
        startsWith(bytes, 0xFF, 0xD8, 0xFF) -> "jpg"
        startsWith(bytes, 'G'.code, 'I'.code, 'F'.code, '8'.code) -> "gif"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
            String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"
        ftypBrands(bytes).any { it in AV1_BRANDS } -> "avif"
        mime != null && mime.startsWith("image/") ->
            mime.substringAfter('/').substringBefore(';').filter { it.isLetterOrDigit() }.take(8).ifEmpty { "img" }
        else -> "bin"
    }

    /** The MIME type to use when handing the original file to another app. */
    fun mimeFor(extension: String): String = when (extension) {
        "heic" -> "image/heic"
        "heics" -> "image/heic-sequence"
        "png" -> "image/png"
        "jpg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        else -> "application/octet-stream"
    }

    private fun startsWith(bytes: ByteArray, vararg prefix: Int): Boolean {
        if (bytes.size < prefix.size) return false
        for (i in prefix.indices) {
            if ((bytes[i].toInt() and 0xFF) != prefix[i]) return false
        }
        return true
    }
}
