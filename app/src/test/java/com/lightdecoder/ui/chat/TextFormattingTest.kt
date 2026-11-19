package com.lightdecoder.ui.chat

import android.app.Activity
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.view.menu.MenuBuilder
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.R
import com.lightdecoder.imessage.direct.TextEffectKind
import com.lightdecoder.imessage.direct.TextRun
import com.lightdecoder.imessage.direct.TextRuns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * iOS 18's formatting in the composer ([ComposerFormatting], [FormattingActionMode]) and in a
 * bubble ([BubbleText], [TextEffectView], [LetterMotion]). The thread screen's part is in BlueBubblesScreenTest.
 */
@RunWith(AndroidJUnit4::class)
class TextFormattingTest {

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test
    fun theComposersMarksAreItsRunsAndOtherSpansAreNot() {
        val text = SpannableStringBuilder("make this bold")
        ComposerFormatting.write(text, listOf(TextRun(0, 4, italic = true), TextRun(5, 9, effect = TextEffectKind.SHAKE)), 0x3300ff00)
        assertEquals(listOf(TextRun(0, 4, italic = true), TextRun(5, 9, effect = TextEffectKind.SHAKE)), ComposerFormatting.runs(text))
        // Shown live: italic as italic, the effect highlighted.
        assertEquals(Typeface.ITALIC, text.getSpans(0, 4, StyleSpan::class.java).single().style)
        assertEquals(TextEffectKind.SHAKE, text.getSpans(5, 9, EffectMark::class.java).single().effect)
        // An input method's underline of the word being typed is not formatting the user chose.
        text.setSpan(UnderlineSpan(), 10, 14, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or Spanned.SPAN_COMPOSING)
        assertEquals(2, ComposerFormatting.runs(text).size)
        // Pasted bold shows bold, and so is sent bold.
        text.setSpan(StyleSpan(Typeface.BOLD), 10, 14, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        assertEquals(TextRun(10, 14, bold = true), ComposerFormatting.runs(text).last())
        // Written again, the composing underline stays the input method's.
        ComposerFormatting.write(text, ComposerFormatting.runs(text).dropLast(1), 0)
        assertEquals(1, text.getSpans(10, 14, UnderlineSpan::class.java).size)
        assertEquals(2, ComposerFormatting.runs(text).size)
        // Bold over "this": the effect gives way; bold over "make": both.
        assertTrue(ComposerFormatting.apply(text, 0, 9, TextRuns.Change.Toggle(TextRuns.Change.Flag.BOLD), 0))
        assertEquals(listOf(TextRun(0, 4, bold = true, italic = true), TextRun(4, 9, bold = true)), ComposerFormatting.runs(text))
        // An effect over it all: the effect wins.
        ComposerFormatting.apply(text, 0, text.length, TextRuns.Change.Effect(TextEffectKind.BLOOM), 0)
        assertEquals(listOf(TextRun(0, 14, effect = TextEffectKind.BLOOM)), ComposerFormatting.runs(text))
        assertTrue(text.getSpans(0, text.length, BoldMark::class.java).isEmpty())
        // Nothing selected: nothing done.
        assertFalse(ComposerFormatting.apply(text, 3, 3, TextRuns.Change.Toggle(TextRuns.Change.Flag.BOLD), 0))
        // Typing at the end of a formatted stretch carries it on.
        val typed = SpannableStringBuilder("bold")
        ComposerFormatting.write(typed, listOf(TextRun(0, 4, bold = true)), 0)
        typed.append("er")
        assertEquals(listOf(TextRun(0, 6, bold = true)), ComposerFormatting.runs(typed))
    }

    @Test
    fun aBubbleDrawsFlagsAsSpansAndMarksEffectStretches() {
        val styled = BubbleText.styled(
            "bold it under strike fx",
            listOf(
                TextRun(0, 4, bold = true), TextRun(5, 7, bold = true, italic = true), TextRun(8, 13, underline = true),
                TextRun(14, 20, strikethrough = true), TextRun(21, 23, effect = TextEffectKind.RIPPLE),
            ),
        ) as Spanned
        assertEquals(Typeface.BOLD, styled.getSpans(0, 4, StyleSpan::class.java).single().style)
        assertEquals(Typeface.BOLD_ITALIC, styled.getSpans(5, 7, StyleSpan::class.java).single().style)
        assertEquals(1, styled.getSpans(8, 13, UnderlineSpan::class.java).size)
        assertEquals(1, styled.getSpans(14, 20, StrikethroughSpan::class.java).size)
        val fx = styled.getSpans(21, 23, TextEffectSpan::class.java).single()
        assertEquals(TextEffectKind.RIPPLE, fx.effect)
        assertFalse("still, it shows", fx.hidden)
        assertEquals("plain text stays a plain string", "plain", BubbleText.styled("plain", emptyList()))
    }

    @Test
    fun eachEffectMovesItsLettersAndEndsAtRest() {
        for (effect in TextEffectKind.values()) {
            assertEquals(effect.name, LetterMotion.REST, LetterMotion.of(effect, 0f, 0, 5))
            assertEquals(effect.name, LetterMotion.REST, LetterMotion.of(effect, 1f, 3, 5))
            val moving = (1..19).map { LetterMotion.of(effect, it / 20f, 2, 5) }
            assertTrue("$effect moves", moving.any { it != LetterMotion.REST })
        }
        // Big grows, Small shrinks, Shake goes sideways, Nod up and down, Bloom glows.
        assertTrue(LetterMotion.of(TextEffectKind.BIG, 0.5f, 0, 3).scale > 1.4f)
        assertTrue(LetterMotion.of(TextEffectKind.SMALL, 0.5f, 0, 3).scale < 0.7f)
        assertTrue((1..19).all { LetterMotion.of(TextEffectKind.SHAKE, it / 20f, 0, 3).dy == 0f })
        assertTrue((1..19).all { LetterMotion.of(TextEffectKind.NOD, it / 20f, 0, 3).dx == 0f })
        assertTrue(LetterMotion.of(TextEffectKind.BLOOM, 0.5f, 0, 3).glow > 0.9f)
        // Explode sends letters different ways; Ripple reaches the first letter before the last.
        assertNotEquals(LetterMotion.of(TextEffectKind.EXPLODE, 0.5f, 0, 4).dx, LetterMotion.of(TextEffectKind.EXPLODE, 0.5f, 1, 4).dx)
        assertTrue(LetterMotion.of(TextEffectKind.RIPPLE, 0.15f, 0, 10).dy < LetterMotion.of(TextEffectKind.RIPPLE, 0.15f, 9, 10).dy)
        // Jitter is the same at the same moment (no flicker between two draws of a frame).
        assertEquals(LetterMotion.of(TextEffectKind.JITTER, 0.4f, 2, 5), LetterMotion.of(TextEffectKind.JITTER, 0.4f, 2, 5))
        // Letters are whole characters.
        assertEquals(listOf(0 to 1, 1 to 3, 3 to 4), TextEffectView.letters("a😀b", 0, 4))
    }

    @Test
    fun aBubblesTextEffectsPlayHidingTheStillLettersAndStopShowingThem() {
        val activity = activity()
        val view = TextEffectView(activity)
        activity.findViewById<FrameLayout>(android.R.id.content).addView(view, 400, 100)
        view.text = "plain"
        assertFalse(view.hasEffects)
        assertNull("nothing to play", view.play())
        view.text = BubbleText.styled("shake it", listOf(TextRun(0, 5, effect = TextEffectKind.SHAKE)))
        assertEquals(listOf(TextEffectKind.SHAKE), TextEffectView.effectsOf(view))
        val span = (view.text as Spanned).getSpans(0, 8, TextEffectSpan::class.java).single()
        val animator = view.play()!!
        assertTrue(view.playing)
        assertTrue(span.hidden)
        assertEquals(0f, view.progress)
        ShadowLooper.idleMainLooper(TextEffectView.DURATION_MS * 2, TimeUnit.MILLISECONDS)
        assertFalse(animator.isRunning)
        assertFalse(view.playing)
        assertFalse("shown still again", span.hidden)
        // Stopped part way (the row reused): still at once.
        view.play()
        view.stop()
        assertFalse(view.playing)
        assertFalse(span.hidden)
    }

    @Test
    fun withAnimationsOffATextEffectIsRecordedButNothingMoves() {
        val activity = activity()
        val list = FrameLayout(activity)
        val view = TextEffectView(activity)
        list.addView(view, 400, 100)
        activity.findViewById<FrameLayout>(android.R.id.content).addView(list)
        view.text = BubbleText.styled("big", listOf(TextRun(0, 3, effect = TextEffectKind.BIG)))
        val off = EffectPlayer(activity, list) { true }
        val play = off.playText("m1", view, EffectPlayer.Reason.RECEIVED)
        assertEquals(EffectPlayer.TEXT, play.effect)
        assertFalse(play.animated)
        assertFalse(view.playing)
        val on = EffectPlayer(activity, list) { false }
        assertTrue(on.playText("m1", view, EffectPlayer.Reason.REPLAY).animated)
        assertTrue(view.playing)
        // Leaving the thread stops it.
        on.cancel()
        assertFalse(view.playing)
    }

    @Test
    fun theSelectionMenuOffersFormattingOnlyWhereItCanBeSent() {
        val activity = activity()
        val field = EditText(activity)
        activity.findViewById<FrameLayout>(android.R.id.content).addView(field)
        field.setText("hello world")
        var offered = false
        var picked: Pair<Int, Int>? = null
        val mode = FormattingActionMode(field, { offered }, { 0 }) { s, e -> picked = s to e }
        val menu = MenuBuilder(activity)
        mode.addItems(menu)
        assertEquals("not an iMessage chat: nothing added", 0, menu.size())
        offered = true
        mode.addItems(menu)
        assertEquals(
            listOf("Text effects", "Bold", "Italic", "Underline", "Strikethrough"),
            (0 until menu.size()).map { menu.getItem(it).title.toString() },
        )
        // Added once however often the menu is made.
        mode.addItems(menu)
        assertEquals(5, menu.size())
        field.setSelection(6, 11)
        assertTrue(mode.handle(R.id.text_format_bold))
        assertEquals(listOf(TextRun(6, 11, bold = true)), ComposerFormatting.runs(field.text))
        assertTrue(mode.handle(R.id.text_format_bold))
        assertEquals("toggled off", emptyList<TextRun>(), ComposerFormatting.runs(field.text))
        assertTrue(mode.handle(R.id.text_format_strikethrough))
        assertEquals(listOf(TextRun(6, 11, strikethrough = true)), ComposerFormatting.runs(field.text))
        assertTrue(mode.handle(R.id.text_format_effects))
        assertEquals(6 to 11, picked)
        assertFalse("the system's own items are its own", mode.handle(android.R.id.copy))
    }
}
