package com.lightdecoder.imessage

/**
 * Takes phone numbers and email addresses (Apple IDs are one or the other) out of text that is
 * logged or goes into the problem report: Apple's and the iMessage library's own error messages can
 * name the registered number or Apple ID, or someone else's. What stays says what went wrong.
 * Dates, times and long hex strings (hashes) are left as they are. Pure; tested in RedactTest.
 */
object Redact {

    const val NUMBER = "<number>"
    const val EMAIL = "<email>"

    /** Fewer digits than this together are no phone number (counts, sizes, codes, years). */
    private const val MIN_DIGITS = 7

    /** tel:, sms:, mailto:, facetime: … addresses: the scheme stays, what follows it up to a space or quote goes. */
    private val URI = Regex("(?i)\\b(tel|telprompt|sms|smsto|mms|mmsto|mailto|facetime|facetime-audio|imessage|im)(:/{0,2})[^\\s\"'<>,;)\\]}]+")

    /** An email address (an Apple ID among them). */
    private val EMAIL_ADDRESS = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9\\-]+(?:\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,}")

    /**
     * Something written like a phone number: digits with the separators numbers are written with
     * (spaces, dots, hyphens, slashes, brackets), maybe after a "+". Taken out at [MIN_DIGITS] digits.
     */
    private val PHONE = Regex("(?<![\\w+])\\+?\\(?\\d(?:[\\d\\s().\\-/]*\\d)?(?!\\w)")

    /** A run of [MIN_DIGITS] digits or more anywhere, inside a word too ("user15551234567"). */
    private val DIGITS = Regex("\\d{$MIN_DIGITS,}")

    /** What is left as it is: dates and times ("2026-11-20 10:30:05"), and hex strings of 32 or more (hashes). */
    private val KEEP = Regex("\\d{4}-\\d{2}-\\d{2}(?:[ T]\\d{2}:\\d{2}(?::\\d{2})?)?|\\d{2}:\\d{2}(?::\\d{2})?|(?<![0-9A-Fa-f])[0-9A-Fa-f]{32,}(?![0-9A-Fa-f])")

    /** Kept text stands in as markers with no ASCII digits in them while the rest is redacted. */
    private const val OPEN = '\uE000'
    private const val CLOSE = '\uE001'
    private val MARKER = Regex("$OPEN([\uE010-\uE019]+)$CLOSE")

    fun text(text: String): String {
        if (text.isEmpty() || text.none { it.isDigit() || it == '@' || it == ':' }) return text
        var out = URI.replace(text) { m ->
            val scheme = m.groupValues[1]
            scheme + m.groupValues[2] + if (scheme.equals("mailto", ignoreCase = true) || '@' in m.value) EMAIL else NUMBER
        }
        out = EMAIL_ADDRESS.replace(out, EMAIL)
        // Then numbers, with dates, times and hashes set aside first.
        val kept = ArrayList<String>()
        out = KEEP.replace(out) { m ->
            kept += m.value
            OPEN + (kept.size - 1).toString().map { (0xE010 + (it - '0')).toChar() }.joinToString("") + CLOSE
        }
        out = PHONE.replace(out) { m -> if (m.value.count { it.isDigit() } >= MIN_DIGITS) NUMBER else m.value }
        out = DIGITS.replace(out, NUMBER)
        return MARKER.replace(out) { m ->
            val index = m.groupValues[1].map { '0' + (it.code - 0xE010) }.joinToString("").toInt()
            kept[index]
        }
    }
}
