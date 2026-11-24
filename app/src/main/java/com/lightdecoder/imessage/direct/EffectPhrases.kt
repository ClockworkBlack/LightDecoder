package com.lightdecoder.imessage.direct

import java.util.Locale

/**
 * The screen effects an iPhone sends a message with by itself when the message is only one of these
 * phrases ("Happy Birthday" goes with balloons): the sender's phone picks it as the message goes, and
 * the receiver plays the effect it was sent with. Punctuation, case and extra spaces do not matter;
 * anything more ("Happy birthday John") is no phrase. English only, until other languages are seen on
 * an iPhone.
 *
 * Pure. Tested in EffectPhrasesTest.
 */
object EffectPhrases {

    private const val PREFIX = "com.apple.messages.effect."

    /** Each phrase, as [normalized] makes it, with its effect's id (the ids [com.lightdecoder.ui.chat.EffectKind] plays). */
    private val PHRASES: Map<String, String> = mapOf(
        "happy birthday" to PREFIX + "CKHappyBirthdayEffect",
        "congratulations" to PREFIX + "CKConfettiEffect",
        "congrats" to PREFIX + "CKConfettiEffect",
        "happy new year" to PREFIX + "CKFireworksEffect",
        "happy chinese new year" to PREFIX + "CKSparklesEffect",
        "happy lunar new year" to PREFIX + "CKSparklesEffect",
        "pew pew" to PREFIX + "CKLasersEffect",
    )

    /** The longest text looked at (a phrase is a few words). */
    private const val MAX_CHARS = 64

    /** The effect id [text] goes with, or null when it is no phrase. */
    fun effectFor(text: String): String? {
        if (text.length > MAX_CHARS) return null
        return PHRASES[normalized(text)]
    }

    /** [text] trimmed, in lower case, without punctuation ("!.,?~" and Unicode's), its spaces made single. */
    internal fun normalized(text: String): String {
        val kept = buildString(text.length) {
            for (c in text.lowercase(Locale.ROOT)) {
                if (c in "!.,?~" || isPunctuation(c)) continue
                append(if (c.isWhitespace()) ' ' else c)
            }
        }
        return kept.trim().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun isPunctuation(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
        -> true
        else -> false
    }
}
