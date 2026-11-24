package com.lightdecoder.imessage.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The phrases an iPhone sends with a screen effect by itself. */
class EffectPhrasesTest {

    private val p = "com.apple.messages.effect."

    @Test
    fun everyPhraseWithPunctuationCaseAndSpacing() {
        val cases = mapOf(
            "Happy Birthday!!!" to "CKHappyBirthdayEffect",
            "  happy   BIRTHDAY  " to "CKHappyBirthdayEffect",
            "Congratulations!" to "CKConfettiEffect",
            "congrats." to "CKConfettiEffect",
            "Happy New Year!!!" to "CKFireworksEffect",
            "Happy Chinese New Year~" to "CKSparklesEffect",
            "happy lunar new year?!" to "CKSparklesEffect",
            "Pew pew!" to "CKLasersEffect",
            "¡Happy birthday!" to "CKHappyBirthdayEffect",
            "“Congrats”" to "CKConfettiEffect",
            "happy\\nbirthday" to "CKHappyBirthdayEffect",
        )
        for ((text, effect) in cases) assertEquals(text, p + effect, EffectPhrases.effectFor(text.replace("\\n", "\n")))
    }

    @Test
    fun anythingMoreIsNoPhrase() {
        assertNull(EffectPhrases.effectFor("happy birthday John"))
        assertNull(EffectPhrases.effectFor("happybirthday"))
        assertNull(EffectPhrases.effectFor(""))
        assertNull(EffectPhrases.effectFor("   !!! "))
        assertNull(EffectPhrases.effectFor("congratulations " + "!".repeat(100)))
        assertNull(EffectPhrases.effectFor("Feliz cumpleaños"))
    }

    @Test
    fun theIdsAreTheOnesTheAppPlays() {
        for (text in listOf("happy birthday", "congrats", "happy new year", "happy lunar new year", "pew pew")) {
            assertEquals(text, true, com.lightdecoder.ui.chat.EffectKind.of(EffectPhrases.effectFor(text)) != null)
        }
    }
}
