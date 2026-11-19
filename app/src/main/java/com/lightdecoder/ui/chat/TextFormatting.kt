package com.lightdecoder.ui.chat

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.text.style.UpdateAppearance
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import com.lightdecoder.R
import com.lightdecoder.imessage.direct.TextEffectKind
import com.lightdecoder.imessage.direct.TextRun
import com.lightdecoder.imessage.direct.TextRuns
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The composer's own formatting spans (iOS 18's bold, italic, underline, strikethrough and text
 * effects). What the composer shows is what is sent: these, and plain bold, italic, underline and
 * strikethrough spans (pasted styled text, or the composer's own after a rotation brought them back
 * as plain spans), but never an input method's marking of the word being typed (composing spans).
 */
internal interface FormatMark

internal class BoldMark : StyleSpan(Typeface.BOLD), FormatMark
internal class ItalicMark : StyleSpan(Typeface.ITALIC), FormatMark
internal class UnderlineMark : UnderlineSpan(), FormatMark
internal class StrikeMark : StrikethroughSpan(), FormatMark

/** A stretch of the composer's text that goes with [effect]: a soft highlight shows it. */
internal class EffectMark(val effect: TextEffectKind, color: Int) : BackgroundColorSpan(color), FormatMark

/**
 * The composer's formatting as [TextRun]s and back. A run with a text effect has no flags (one or
 * the other, as Apple sends it): an effect laid over bold text wins there, and bold put on text with
 * an effect takes the effect off it. Tested in TextFormattingTest.
 */
internal object ComposerFormatting {

    /** The formatted stretches of [text], from its formatting spans (see [FormatMark]). */
    fun runs(text: CharSequence): List<TextRun> {
        if (text !is Spanned || text.isEmpty()) return emptyList()
        val raw = text.getSpans(0, text.length, Any::class.java).mapNotNull { span ->
            val s = text.getSpanStart(span)
            val e = text.getSpanEnd(span)
            if (s < 0 || e <= s || text.getSpanFlags(span) and Spanned.SPAN_COMPOSING != 0) return@mapNotNull null
            when (span) {
                is EffectMark -> TextRun(s, e, effect = span.effect)
                is StyleSpan -> when (span.style) {
                    Typeface.BOLD -> TextRun(s, e, bold = true)
                    Typeface.ITALIC -> TextRun(s, e, italic = true)
                    Typeface.BOLD_ITALIC -> TextRun(s, e, bold = true, italic = true)
                    else -> null
                }
                is UnderlineSpan -> TextRun(s, e, underline = true)
                is StrikethroughSpan -> TextRun(s, e, strikethrough = true)
                else -> null
            }
        }
        // Flags first, effects after: where both are, the effect wins.
        return TextRuns.normalize(text.toString(), raw.sortedBy { if (it.effect != null) 1 else 0 })
    }

    /** Puts [runs] on [text] as the composer's spans, in place of the ones it had. [effectColor] highlights a text effect. */
    fun write(text: Spannable, runs: List<TextRun>, effectColor: Int) {
        // The formatting spans read as runs ([runs]), and a highlight brought back without its effect.
        text.getSpans(0, text.length, CharacterStyle::class.java)
            .filter { text.getSpanFlags(it) and Spanned.SPAN_COMPOSING == 0 }
            .filter { it is FormatMark || it is StyleSpan || it is UnderlineSpan || it is StrikethroughSpan || it is BackgroundColorSpan }
            .forEach(text::removeSpan)
        for (r in TextRuns.normalize(text.toString(), runs)) {
            // Typing at the end of a formatted stretch carries it on, as on an iPhone.
            val flags = Spanned.SPAN_EXCLUSIVE_INCLUSIVE
            if (r.effect != null) {
                text.setSpan(EffectMark(r.effect, effectColor), r.start, r.end, flags)
                continue
            }
            if (r.bold) text.setSpan(BoldMark(), r.start, r.end, flags)
            if (r.italic) text.setSpan(ItalicMark(), r.start, r.end, flags)
            if (r.underline) text.setSpan(UnderlineMark(), r.start, r.end, flags)
            if (r.strikethrough) text.setSpan(StrikeMark(), r.start, r.end, flags)
        }
    }

    /** Applies [change] to [start]..[end] of [text] (the selection). False when nothing is selected. */
    fun apply(text: Spannable, start: Int, end: Int, change: TextRuns.Change, effectColor: Int): Boolean {
        val s = minOf(start, end).coerceAtLeast(0)
        val e = maxOf(start, end).coerceAtMost(text.length)
        if (e <= s) return false
        write(text, TextRuns.apply(text.toString(), runs(text), s, e, change), effectColor)
        return true
    }

    /** [text] with [runs] as the composer shows them (an edit's text, formatted as it was sent). */
    fun spanned(text: String, runs: List<TextRun>, effectColor: Int): CharSequence {
        if (runs.isEmpty()) return text
        return SpannableString(text).also { write(it, runs, effectColor) }
    }
}

/**
 * A bubble's text drawn with its formatting ([TextRun]s): bold, italic, underline and
 * strikethrough as spans; a text effect's stretch is plain text until it plays, when
 * [TextEffectView] draws its letters moving ([TextEffectSpan] then hides the still ones).
 */
internal object BubbleText {

    fun styled(text: String, runs: List<TextRun>): CharSequence {
        if (runs.isEmpty() || text.isEmpty()) return text
        val out = SpannableString(text)
        for (r in TextRuns.normalize(text, runs)) {
            val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            if (r.effect != null) {
                out.setSpan(TextEffectSpan(r.effect), r.start, r.end, flags)
                continue
            }
            when {
                r.bold && r.italic -> out.setSpan(StyleSpan(Typeface.BOLD_ITALIC), r.start, r.end, flags)
                r.bold -> out.setSpan(StyleSpan(Typeface.BOLD), r.start, r.end, flags)
                r.italic -> out.setSpan(StyleSpan(Typeface.ITALIC), r.start, r.end, flags)
            }
            if (r.underline) out.setSpan(UnderlineSpan(), r.start, r.end, flags)
            if (r.strikethrough) out.setSpan(StrikethroughSpan(), r.start, r.end, flags)
        }
        return out
    }
}

/** A stretch of a bubble's text with a text effect: drawn as it is, except while [TextEffectView] animates it ([hidden]). */
internal class TextEffectSpan(val effect: TextEffectKind) : CharacterStyle(), UpdateAppearance {
    var hidden = false

    override fun updateDrawState(tp: TextPaint) {
        if (hidden) tp.alpha = 0
    }
}

/**
 * Where one letter of a text effect is at time [t] (0 to 1 of the animation): moved by [dx] and
 * [dy] (in text sizes), scaled, turned ([rotation], degrees), faded ([alpha]) and glowing ([glow],
 * 0 to 1). LightDecoder's own animation of each effect. Pure. Tested in TextFormattingTest.
 */
internal data class LetterMotion(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val alpha: Float = 1f,
    val glow: Float = 0f,
) {
    companion object {
        val REST = LetterMotion()

        /** Letter [index] of [count] in a stretch with [effect], at [t]. Back at rest at 0 and 1. */
        fun of(effect: TextEffectKind, t: Float, index: Int, count: Int): LetterMotion {
            if (t <= 0f || t >= 1f) return REST
            val swell = sin(PI * t).toFloat()
            val fade = 1f - t
            val place = if (count <= 1) 0f else index.toFloat() / (count - 1)
            return when (effect) {
                // Grows large, then settles.
                TextEffectKind.BIG -> LetterMotion(scale = 1f + 0.6f * swell, dy = -0.1f * swell)
                // Shrinks, then comes back.
                TextEffectKind.SMALL -> LetterMotion(scale = 1f - 0.45f * swell)
                // Side to side, dying down.
                TextEffectKind.SHAKE -> LetterMotion(dx = 0.14f * fade * sin(2 * PI * 6 * t).toFloat())
                // Up and down, a nod or three.
                TextEffectKind.NOD -> LetterMotion(dy = 0.2f * fade * sin(2 * PI * 3 * t).toFloat())
                // Each letter flies out its own way, turning, and comes back.
                TextEffectKind.EXPLODE -> {
                    val angle = index * GOLDEN_ANGLE
                    val out = sin(PI * t).toFloat().let { it * it }
                    LetterMotion(
                        dx = 1.3f * out * cos(angle).toFloat(),
                        dy = 1.3f * out * sin(angle).toFloat(),
                        rotation = (if (index % 2 == 0) 50f else -50f) * out,
                        alpha = 1f - 0.35f * out,
                    )
                }
                // A wave rolls across the letters.
                TextEffectKind.RIPPLE -> {
                    val local = ((t * (1f + RIPPLE_WIDTH) - place) / RIPPLE_WIDTH).coerceIn(0f, 1f)
                    val lift = sin(PI * local).toFloat()
                    LetterMotion(dy = -0.3f * lift, scale = 1f + 0.18f * lift)
                }
                // Swells softly with a glow.
                TextEffectKind.BLOOM -> LetterMotion(scale = 1f + 0.25f * swell, glow = swell)
                // Small random jumps, a new one every few frames.
                TextEffectKind.JITTER -> {
                    val step = (t * JITTER_STEPS).toInt()
                    val amount = 0.07f * (if (t > 0.85f) (1f - t) / 0.15f else 1f)
                    LetterMotion(dx = amount * noise(index, step, 1), dy = amount * noise(index, step, 2), rotation = 6f * amount / 0.07f * noise(index, step, 3))
                }
            }
        }

        /** A steady pseudo-random number from -1 to 1 for letter [index] at [step]. */
        private fun noise(index: Int, step: Int, salt: Int): Float {
            var h = index * 73856093 xor step * 19349663 xor salt * 83492791
            h = h xor (h ushr 13)
            h *= 0x5bd1e995
            h = h xor (h ushr 15)
            return ((h and 0xffff) / 32767.5f) - 1f
        }

        private const val GOLDEN_ANGLE = 2.39996323
        private const val RIPPLE_WIDTH = 0.35f
        private const val JITTER_STEPS = 30
    }
}

/**
 * The composer's text selection menu in an iMessage chat: "Text effects" (a picker of the eight)
 * and Bold, Italic, Underline, Strikethrough on the selected words, as iOS 18's. [offered] says
 * whether this chat sends them (an iMessage chat through the library); otherwise the menu is the
 * system's alone. [pickEffect] asks which effect, then applies it ([ComposerFormatting.apply]).
 */
internal class FormattingActionMode(
    private val field: EditText,
    private val offered: () -> Boolean,
    private val effectColor: () -> Int,
    private val pickEffect: (start: Int, end: Int) -> Unit,
) : ActionMode.Callback {

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        addItems(menu)
        return true
    }

    /** Adds the formatting items to [menu] (when [offered]); also used by tests. */
    fun addItems(menu: Menu) {
        ITEMS.forEach { (id, _) -> menu.removeItem(id) }
        if (!offered()) return
        ITEMS.forEachIndexed { i, (id, title) -> menu.add(Menu.NONE, id, ORDER + i, title) }
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem): Boolean = handle(item.itemId).also { if (it) mode?.finish() }

    /** What the item [id] does to the selection; false for an item that is not one of these. */
    fun handle(id: Int): Boolean {
        val flag = when (id) {
            R.id.text_format_bold -> TextRuns.Change.Flag.BOLD
            R.id.text_format_italic -> TextRuns.Change.Flag.ITALIC
            R.id.text_format_underline -> TextRuns.Change.Flag.UNDERLINE
            R.id.text_format_strikethrough -> TextRuns.Change.Flag.STRIKETHROUGH
            R.id.text_format_effects -> {
                pickEffect(field.selectionStart, field.selectionEnd)
                return true
            }
            else -> return false
        }
        val text = field.text ?: return true
        val start = field.selectionStart
        val end = field.selectionEnd
        ComposerFormatting.apply(text, start, end, TextRuns.Change.Toggle(flag), effectColor())
        field.setSelection(start.coerceIn(0, text.length), end.coerceIn(0, text.length))
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode?) = Unit

    companion object {
        /** After the system's own items (cut, copy, paste, select all). */
        private const val ORDER = 100

        val ITEMS = listOf(
            R.id.text_format_effects to R.string.imessage_text_effects,
            R.id.text_format_bold to R.string.imessage_format_bold,
            R.id.text_format_italic to R.string.imessage_format_italic,
            R.id.text_format_underline to R.string.imessage_format_underline,
            R.id.text_format_strikethrough to R.string.imessage_format_strikethrough,
        )
    }
}
