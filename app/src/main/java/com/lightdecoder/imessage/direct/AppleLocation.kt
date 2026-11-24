package com.lightdecoder.imessage.direct

import java.util.Locale

/**
 * A location as iMessage sends one ("Send My Current Location"): a small vCard named
 * `<guid>-CL.loc.vcf` of type `text/x-vlocation`, whose URL line is a maps.apple.com link with the
 * coordinates. Pure; tested in AppleLocationTest.
 *
 * A received card is read strictly ([coordinatesFrom]): only two numbers in range are taken from it,
 * and only LightDecoder's own `geo:` address is ever opened ([geoUri]), never the card's URL (the
 * sender wrote it). Coordinates are never logged.
 */
object AppleLocation {
    const val MIME = "text/x-vlocation"
    const val UTI = "public.vlocation"

    /** The name ending of a location's card. */
    const val SUFFIX = "-CL.loc.vcf"

    /** The largest card read (a real one is a few hundred bytes). */
    const val MAX_BYTES = 16 * 1024

    /** The card for [lat], [lon], as an iPhone writes it: fixed-point numbers with a dot, whatever the phone's language. */
    fun toVcf(lat: Double, lon: Double): String {
        val la = format(lat)
        val lo = format(lon)
        return listOf(
            "BEGIN:VCARD",
            "VERSION:3.0",
            "PRODID:-//Apple Inc.//macOS 13.0//EN",
            "N:;Current Location;;;",
            "FN:Current Location",
            "URL;type=pref:https://maps.apple.com/?ll=$la\\,$lo&q=$la\\,$lo",
            "END:VCARD",
        ).joinToString("\n") + "\n"
    }

    /** [v] with six decimals and a dot ([Locale.ROOT]): never "1.0E-4", never a comma. */
    fun format(v: Double): String = String.format(Locale.ROOT, "%.6f", v)

    /** The card's file name for message [guid]. */
    fun fileName(guid: String): String = "$guid$SUFFIX"

    /** Whether an attachment of type [mime] named [name] is a location's card. */
    fun isLocation(mime: String?, name: String?): Boolean =
        mime.equals(MIME, ignoreCase = true) || name.orEmpty().endsWith(".loc.vcf", ignoreCase = true)

    /** A plain decimal: an optional minus, digits, an optional dot and digits. No exponent, hex, sign "+", NaN or Infinity. */
    private const val NUMBER = "-?[0-9]{1,3}(?:\\.[0-9]{1,15})?"

    /** An `ll` parameter's value, `<lat>,<lon>`, the comma as `\,`, `,` or `%2C`. */
    private val LL = Regex("^($NUMBER)(?:\\\\,|,|%2[Cc])($NUMBER)$")

    /** A URL property's name: `URL`, its parameters after `;`, and an optional short vCard group (`item1.URL;type=pref`, as an iPhone writes it). */
    private val URL_NAME = Regex("^(?:[A-Za-z0-9-]{1,16}\\.)?URL(?:;[^:]*)?$", RegexOption.IGNORE_CASE)

    /**
     * The coordinates of the card [text]: from its first URL line only (`URL`, `URL;...:` or `item1.URL...:`), its
     * `ll=` parameter, each in range ([-90, 90], [-180, 180]). Null when the card is larger than
     * [MAX_BYTES], has no such line, or its numbers are anything but plain decimals in range.
     */
    fun coordinatesFrom(text: String): Pair<Double, Double>? {
        if (text.length > MAX_BYTES) return null
        val line = text.lineSequence().map { it.trimEnd('\r') }.firstOrNull { l ->
            URL_NAME.matches(l.substringBefore(':'))
        } ?: return null
        val url = line.substringAfter(':', "")
        // The query's own parameters only (never one inside another's value), the first `ll` of them.
        val query = url.substringBefore('#').substringAfter('?', "")
        val ll = query.split('&').firstOrNull { it.startsWith("ll=") }?.removePrefix("ll=") ?: return null
        val m = LL.matchEntire(ll) ?: return null
        val lat = m.groupValues[1].toDouble()
        val lon = m.groupValues[2].toDouble()
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }

    /** The card's coordinates from its bytes ([MAX_BYTES] at most), or null. */
    fun coordinatesFrom(bytes: ByteArray): Pair<Double, Double>? =
        if (bytes.size > MAX_BYTES) null else coordinatesFrom(String(bytes, Charsets.UTF_8))

    /** LightDecoder's own address for a map app: `geo:<lat>,<lon>?q=<lat>,<lon>`. */
    fun geoUri(lat: Double, lon: Double): String {
        val la = format(lat)
        val lo = format(lon)
        return "geo:$la,$lo?q=$la,$lo"
    }

    /** The coordinates as the card shows them: five decimals, a dot. */
    fun shown(v: Double): String = String.format(Locale.ROOT, "%.5f", v)
}
