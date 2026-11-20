package com.lightdecoder.imessage.direct

/**
 * iMessage addresses: `tel:+15551234567` or `mailto:someone@example.com`, as the library wants
 * them. The user types a phone number or an email address.
 */
object Handles {

    /**
     * The library form of what the user typed, or null when it is neither a phone number nor an
     * email. A number without its country code is read in [defaultCountryCode]'s country: this
     * phone's ([com.lightdecoder.imessage.IMessageAddress.region]), North America's when none is known.
     */
    fun normalize(input: String, defaultCountryCode: String = com.lightdecoder.imessage.IMessageAddress.callingCode() ?: "1"): String? {
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
        // The country's own trunk prefix ("0"; "8" in Russia), when it is this phone's country.
        val home = com.lightdecoder.imessage.CallingCodes.of(com.lightdecoder.imessage.IMessageAddress.region())?.takeIf { it.code == defaultCountryCode }
        val e164 = when {
            plus -> digits
            // The international prefix dialled in place of "+".
            digits.startsWith("00") && digits.length - 2 >= 7 -> digits.substring(2)
            defaultCountryCode == "1" && digits.startsWith("011") && digits.length - 3 >= 7 -> digits.substring(3)
            digits.length == 10 && defaultCountryCode == "1" -> "1$digits"
            digits.length == 11 && digits.startsWith("1") && defaultCountryCode == "1" -> digits
            home != null && home.trunk.isNotEmpty() && digits.startsWith(home.trunk) -> defaultCountryCode + digits.substring(home.trunk.length)
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
