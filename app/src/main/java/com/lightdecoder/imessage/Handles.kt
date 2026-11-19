package com.lightdecoder.imessage

/**
 * iMessage addresses: `tel:+15551234567` or `mailto:someone@example.com`, as the library wants
 * them. The user types a phone number or an email address.
 */
object Handles {

    /** The library form of what the user typed, or null when it is neither a phone number nor an email. */
    fun normalize(input: String, defaultCountryCode: String = "1"): String? {
        val s = input.trim()
        if (s.isEmpty()) return null
        val lower = s.lowercase()
        if (lower.startsWith("mailto:")) return normalize(s.substring(7), defaultCountryCode)
        if (lower.startsWith("tel:")) return normalize(s.substring(4), defaultCountryCode)
        if ('@' in s) {
            val ok = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(s)
            return if (ok) "mailto:$lower" else null
        }
        val plus = s.startsWith("+")
        val digits = s.filter { it.isDigit() }
        if (digits.length < 7 || s.any { !(it.isDigit() || it in " +-().") }) return null
        val e164 = when {
            plus -> digits
            digits.length == 10 && defaultCountryCode == "1" -> "1$digits"
            digits.length == 11 && digits.startsWith("1") && defaultCountryCode == "1" -> digits
            else -> defaultCountryCode + digits.trimStart('0')
        }
        return "tel:+$e164"
    }

    /** For showing: the address without its scheme. */
    fun display(handle: String): String = handle.removePrefix("tel:").removePrefix("mailto:")

    /** The handle to send from: the phone number if there is one, else the first. */
    fun preferredSender(handles: List<String>): String? =
        handles.firstOrNull { it.startsWith("tel:") } ?: handles.firstOrNull()
}
