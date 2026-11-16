package com.lightdecoder.mail

/** Turns an email's body into what a conversation bubble shows. Pure text work; no Android. */
object MailText {

    private val QUOTE_HEADER = Regex("""^\s*On\b.{0,300}\bwrote:\s*$""", RegexOption.IGNORE_CASE)
    private val QUOTE_HEADER_START = Regex("""^\s*On\b.*""", RegexOption.IGNORE_CASE)
    private val ORIGINAL = Regex("""^\s*-{2,}\s*(Original Message|Forwarded message)\s*-{2,}\s*$""", RegexOption.IGNORE_CASE)
    private val OUTLOOK_RULE = Regex("""^\s*_{8,}\s*$""")
    private val REPLY_PREFIX = Regex("""^\s*((re|fwd?|aw|wg)\s*(\[\d+])?\s*:\s*)+""", RegexOption.IGNORE_CASE)

    /**
     * The new part of a reply: everything from the quoted earlier message
     * on ("On ... wrote:", "-----Original Message-----", an Outlook rule, or
     * a trailing block of ">" lines) is cut. A message that is all quote
     * keeps its text.
     */
    fun withoutQuote(text: String): String {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var cut = lines.size
        for (i in lines.indices) {
            val line = lines[i]
            val joined = if (i > 0 && QUOTE_HEADER_START.matches(lines[i - 1])) lines[i - 1] + " " + line else null
            when {
                QUOTE_HEADER.matches(line) -> { cut = i; break }
                joined != null && line.trimEnd().endsWith("wrote:") && QUOTE_HEADER.matches(joined) -> { cut = i - 1; break }
                ORIGINAL.matches(line) || OUTLOOK_RULE.matches(line) -> { cut = i; break }
            }
        }
        var kept = lines.subList(0, cut)
        // A trailing block of quoted lines (and blank lines around it).
        var end = kept.size
        while (end > 0 && (kept[end - 1].isBlank() || kept[end - 1].trimStart().startsWith(">"))) end--
        if (end < kept.size && kept.subList(end, kept.size).any { it.trimStart().startsWith(">") }) kept = kept.subList(0, end)
        val result = kept.joinToString("\n").trim()
        return result.ifEmpty { text.trim() }
    }

    /** HTML as plain text, without style and script blocks. */
    fun htmlToText(html: String, toText: (String) -> String): String {
        val cleaned = html
            .replace(Regex("(?is)<(style|script|head)[^>]*>.*?</\\1>"), "")
            .replace(Regex("(?i)<br\\s*/?>"), "<br>")
        return toText(cleaned).replace(' ', ' ').replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** The subject without "Re:", "Fwd:" and the like, for the conversation's name. */
    fun baseSubject(subject: String): String = subject.replace(REPLY_PREFIX, "").trim()

    /** "Re: <subject>" for a reply, unless it already starts so. */
    fun replySubject(subject: String): String {
        val base = baseSubject(subject)
        return if (base.isEmpty()) "Re:" else "Re: $base"
    }

    /** The ids in a References (or In-Reply-To) header, with their angle brackets. */
    fun ids(header: String?): List<String> =
        Regex("<[^<>\\s]+>").findAll(header.orEmpty()).map { it.value }.toList()

    private val EMAIL = Regex("^[^@\\s<>,;\"]+@[^@\\s<>,;\"]+\\.[^@\\s<>,;\"]+$")

    /** A plausible email address (the server has the last word). */
    fun isAddress(text: String): Boolean = EMAIL.matches(text.trim())

    /** Addresses typed into a To field: split at commas, semicolons and spaces. */
    fun parseAddresses(text: String): List<String> =
        text.split(',', ';', ' ', '\n').map { it.trim().removePrefix("<").removeSuffix(">") }.filter { it.isNotEmpty() }
}
