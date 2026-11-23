package com.lightdecoder.imessage

/**
 * Takes phone numbers and email addresses (Apple IDs are one or the other) out of text that is
 * logged or goes into the problem report: Apple's and the iMessage library's own error messages can
 * name the registered number or Apple ID, or someone else's. What stays says what went wrong.
 * Dates, times and long hex strings (hashes) are left as they are by [text]; [secrets], for the Apple
 * Account's errors, also takes out tokens, keys and anything else that looks encoded. Pure; tested
 * in RedactTest.
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

    const val SECRET = "<secret>"

    /** A quoted string, with what is escaped in it. */
    private const val QUOTED = "\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'"

    /** What Rust writes in brackets, maybe after a name: an option, a record, a list (`Some(…)`, `Token { … }`, `[18, 52]`). */
    private const val GROUPED = "(?:[A-Za-z_][A-Za-z0-9_:]*\\s*)?" +
        "(?:\\((?:$QUOTED|[^)\"'])*\\)|\\{(?:$QUOTED|[^}\"'])*}|\\[(?:$QUOTED|[^\\]\"'])*])"

    /**
     * A value written after a name that says it is secret: `token`, `pet`, `password`, `dsid`, `adsid`
     * or an `X-Apple-` header, the name also inside a longer one (`hashed_password`, `mmeAuthToken`,
     * `X-Apple-I-MD-M`), after a ":" or "=" (and the name's closing quote, as JSON has it). The value
     * goes whole as it is written: quoted (`password: "…"`, `{"token":"…"}`), in brackets as Rust
     * writes it ([GROUPED]), or up to the next space. The name stays, so the text still says what it
     * was; a name with no value after it (in words: "password incorrect") stays as it is.
     */
    private val KEYED = Regex(
        "(?i)(token|pet|password|adsid|dsid|x-apple-)([A-Za-z0-9_\\-]*)[\"']?\\s*[:=]\\s*" +
            "(?:$QUOTED|$GROUPED|[^\\s\"',;)\\]}]+)",
    )

    /** A run of 16 or more characters that hex or base64 (standard or URL-safe) is written with, with its padding. */
    private val ENCODED = Regex("(?<![A-Za-z0-9+/_\\-])[A-Za-z0-9+/_\\-]{16,}={0,2}(?![A-Za-z0-9+/_=\\-])")
    private val HEX = Regex("[0-9A-Fa-f]+")

    /**
     * [text] for the Apple Account's and iCloud's errors, which can carry live credentials: as [text]
     * redacts it (numbers, email addresses), and also without what [KEYED] names, and without any run
     * of 16 characters or more that looks encoded (hex, or base64 with a digit, a "+" or "/" in it, or
     * both cases of letters): tokens, hashes and identifiers. An error's own words (all one case, like
     * "MOBILEME_TERMS_OF_SERVICE_UPDATE") stay. Unlike [text], long hex runs do not stay.
     */
    fun secrets(text: String): String {
        if (text.isEmpty()) return text
        var out = KEYED.replace(text) { m -> m.groupValues[1] + m.groupValues[2] + "=" + SECRET }
        // Addresses before encoded runs: a long run in an Apple ID's own part ("john.smith1990abcdef@…")
        // would otherwise go as encoded and leave the rest of the address (its domain) behind.
        out = addresses(out)
        out = ENCODED.replace(out) { m -> if (looksEncoded(m.value.trimEnd('='))) SECRET else m.value }
        return text(out)
    }

    private fun looksEncoded(run: String): Boolean =
        HEX.matches(run) || run.any { it.isDigit() || it == '+' || it == '/' } || (run.any { it.isLowerCase() } && run.any { it.isUpperCase() })

    /** [text]'s first step: addresses after a scheme (`tel:`, `mailto:`, …), then email addresses. */
    private fun addresses(text: String): String {
        if (text.none { it == '@' || it == ':' }) return text
        val out = URI.replace(text) { m ->
            val scheme = m.groupValues[1]
            scheme + m.groupValues[2] + if (scheme.equals("mailto", ignoreCase = true) || '@' in m.value) EMAIL else NUMBER
        }
        return EMAIL_ADDRESS.replace(out, EMAIL)
    }

    fun text(text: String): String {
        if (text.isEmpty() || text.none { it.isDigit() || it == '@' || it == ':' }) return text
        var out = addresses(text)
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
