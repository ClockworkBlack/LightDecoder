package com.lightdecoder.backup

/** The rules for a backup's passphrase, and a rough strength hint (only a hint: nothing is refused for it). */
object Passphrase {

    const val MIN_LENGTH = 8

    enum class Strength { TOO_SHORT, WEAK, FAIR, STRONG }

    /** Of the first [length] characters of [chars]; nothing is copied or kept. */
    fun strength(chars: CharArray, length: Int = chars.size): Strength {
        if (length < MIN_LENGTH) return Strength.TOO_SHORT
        var lower = false
        var upper = false
        var digit = false
        var other = false
        for (i in 0 until length) {
            val c = chars[i]
            when {
                c.isLowerCase() -> lower = true
                c.isUpperCase() -> upper = true
                c.isDigit() -> digit = true
                else -> other = true
            }
        }
        val classes = listOf(lower, upper, digit, other).count { it }
        val score = (if (length >= 12) 1 else 0) + (if (length >= 16) 1 else 0) + (if (length >= 24) 1 else 0) + (classes - 1)
        return when {
            score >= 3 -> Strength.STRONG
            score >= 1 -> Strength.FAIR
            else -> Strength.WEAK
        }
    }
}
