package com.lightdecoder.ui.chat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.text.Spanned
import android.text.TextPaint
import android.util.AttributeSet
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.annotation.VisibleForTesting
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.RecyclerView
import com.lightdecoder.imessage.direct.TextEffectKind
import java.text.BreakIterator

/**
 * A bubble's text that can play its text effects (iOS 18's Big, Shake, Explode, ...): the letters of
 * each stretch with an effect ([TextEffectSpan]) are drawn moving, by this app's own simple
 * drawing ([LetterMotion]), over where they stand, for [DURATION_MS]. The text's layout (and so
 * its wrapping) never changes; the still letters are hidden while it plays.
 */
class TextEffectView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var animator: ValueAnimator? = null

    /** How far the effects have played (0 to 1); null while nothing plays. */
    @VisibleForTesting
    internal var progress: Float? = null
        private set

    private val letterPaint = TextPaint()
    private val unclipped = ArrayList<ViewGroup>()

    /**
     * Whether the touch that last ended on the text was on one of its links (which then opened):
     * a click that follows it is the link's, not a tap on the bubble. Cleared once that click is handled.
     */
    var tappedLink = false
        private set

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> tappedLink = false
            android.view.MotionEvent.ACTION_UP -> tappedLink = linkAt(event.x, event.y)
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        val handled = super.performClick()
        tappedLink = false
        return handled
    }

    /** Whether a link is at ([x], [y]) of the view, found as the text's own link handling finds it. */
    private fun linkAt(x: Float, y: Float): Boolean {
        val layout = layout ?: return false
        val spanned = text as? Spanned ?: return false
        if (movementMethod == null) return false
        val line = layout.getLineForVertical((y - totalPaddingTop + scrollY).toInt())
        val offset = layout.getOffsetForHorizontal(line, x - totalPaddingLeft + scrollX)
        return spanned.getSpans(offset, offset, android.text.style.ClickableSpan::class.java).isNotEmpty()
    }

    /** The text's stretches with an effect, in order. */
    private fun effectSpans(): List<Pair<TextEffectSpan, IntRange>> {
        val t = text as? Spanned ?: return emptyList()
        return t.getSpans(0, t.length, TextEffectSpan::class.java)
            .map { it to (t.getSpanStart(it) until t.getSpanEnd(it)) }
            .filter { !it.second.isEmpty() }
            .sortedBy { it.second.first }
    }

    /** Whether the text has any stretch with an effect. */
    val hasEffects: Boolean get() = effectSpans().isNotEmpty()

    /** Whether the effects are playing now. */
    val playing: Boolean get() = animator != null

    /** Plays the text's effects once from the start; returns the animation (null: none to play). */
    fun play(): Animator? {
        stop()
        if (!hasEffects) return null
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) finish()
                }
            })
        }
        animator = a
        progress = 0f
        hide(true)
        a.start()
        return a
    }

    /** Stops the effects and shows the text still. */
    fun stop() {
        val a = animator ?: return
        animator = null
        a.cancel()
        finish()
    }

    private fun finish() {
        animator = null
        progress = null
        hide(false)
        unclipped.forEach { it.clipChildren = true }
        unclipped.clear()
        invalidate()
    }

    private fun hide(hidden: Boolean) {
        effectSpans().forEach { it.first.hidden = hidden }
        if (hidden) unclip()
        invalidate()
    }

    /** Lets the letters fly past the bubble while they play (put back at the end). */
    private fun unclip() {
        if (unclipped.isNotEmpty()) return
        var p = parent
        while (p is ViewGroup && p !is RecyclerView) {
            if (p.clipChildren) {
                p.clipChildren = false
                unclipped += p
            }
            p = p.parent
        }
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        // Another message's text: what played for the last one stops.
        if (animator != null) stop()
        super.setText(text, type)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = progress ?: return
        val layout = layout ?: return
        val text = text?.toString() ?: return
        letterPaint.set(paint)
        letterPaint.color = currentTextColor
        val left = compoundPaddingLeft.toFloat() - scrollX
        val top = extendedPaddingTop.toFloat() - scrollY
        val size = textSize
        for ((span, range) in effectSpans()) {
            val letters = letters(text, range.first, range.last + 1)
            letters.forEachIndexed { i, (s, e) ->
                if (text.substring(s, e).isBlank()) return@forEachIndexed
                val m = LetterMotion.of(span.effect, t, i, letters.size)
                val line = layout.getLineForOffset(s)
                val x = left + layout.getPrimaryHorizontal(s)
                val baseline = top + layout.getLineBaseline(line)
                val width = letterPaint.measureText(text, s, e)
                canvas.save()
                canvas.translate(x + width / 2 + m.dx * size, baseline - size / 3 + m.dy * size)
                canvas.rotate(m.rotation)
                canvas.scale(m.scale, m.scale)
                letterPaint.alpha = (android.graphics.Color.alpha(currentTextColor) * m.alpha).toInt().coerceIn(0, 255)
                if (m.glow > 0f) letterPaint.setShadowLayer(size * 0.6f * m.glow, 0f, 0f, currentTextColor) else letterPaint.clearShadowLayer()
                canvas.drawText(text, s, e, -width / 2, size / 3, letterPaint)
                canvas.restore()
            }
        }
        letterPaint.clearShadowLayer()
    }

    companion object {
        /** How long a message's text effects play. */
        const val DURATION_MS = 1_600L

        /** The letters (user-perceived characters) of [start] to [end] of [text], each as its own start and end. */
        internal fun letters(text: String, start: Int, end: Int): List<Pair<Int, Int>> {
            val it = BreakIterator.getCharacterInstance()
            it.setText(text)
            val out = ArrayList<Pair<Int, Int>>()
            var s = start
            while (s < end) {
                var e = it.following(s)
                if (e == BreakIterator.DONE || e > end) e = end
                if (e <= s) e = s + 1
                out += s to e
                s = e
            }
            return out
        }

        /** Which effects [view]'s text plays. */
        internal fun effectsOf(view: TextEffectView): List<TextEffectKind> = view.effectSpans().map { it.first.effect }.distinct()
    }
}
