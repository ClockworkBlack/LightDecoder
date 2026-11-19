package com.lightdecoder.ui.chat

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.lightdecoder.R
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.chat.ChatService

/**
 * "iPhone-style chats": how a conversation that goes through the iMessage library is drawn, as
 * Messages on an iPhone draws one. Blue (iMessage) and green (text) bubbles of the user's own and
 * grey ones of other people, a tail on the last bubble of a run, times between the messages
 * instead of in them, the person's picture and name in the middle of the top bar, a round "+" and
 * a pill to write in. Everything is drawn here ([PhoneBubbleDrawable], [TypingDotsView], the
 * app's own vectors); the rules are plain functions, so they can be tested.
 *
 * It is a setting ([com.lightdecoder.imessage.direct.DirectPrefs.phoneStyleOn], on by default).
 * Other services' conversations, and Messages conversations without the person's iMessage chat,
 * look as they always did.
 */
object PhoneStyle {

    /** Messages further apart than this have the time between them. */
    const val HEADER_GAP_MS = 15 * 60 * 1000L

    /** A run of one person's messages: each within this of the one before. Its last bubble has the tail. */
    const val GROUP_GAP_MS = 60 * 1000L

    /**
     * Whether a conversation is drawn this way: the setting is on, the iMessage library is set up
     * ([direct]), and it is an iMessage chat, or a Messages conversation that shows the person's
     * iMessage chat ([hasCompanion]).
     */
    fun applies(settingOn: Boolean, direct: Boolean, service: ChatService, hasCompanion: Boolean): Boolean =
        settingOn && direct && (service == ChatService.IMESSAGE || (service == ChatService.MESSAGES && hasCompanion))

    /** What the empty composer says: "iMessage", or "Text Message" when what is typed would go as a text. */
    fun hintRes(asIMessage: Boolean): Int = if (asIMessage) R.string.imessage_compose_hint else R.string.imessage_phone_hint_text

    /** The send button's (and a bubble's) colour: blue for an iMessage, green for a text. */
    fun sendColorRes(asIMessage: Boolean): Int = if (asIMessage) R.color.phone_blue else R.color.phone_green

    /** Whether the time goes above [row]: the first message, a new day, or more than [HEADER_GAP_MS] since [previous]. */
    fun timeHeader(previous: MessageRow?, row: MessageRow, dayStart: (Long) -> Long): Boolean =
        previous == null || dayStart(previous.timestampMs) != dayStart(row.timestampMs) ||
            row.timestampMs - previous.timestampMs > HEADER_GAP_MS

    /**
     * Whether [next] goes on in [row]'s run: both someone's messages (not notices), the same side
     * and sender, no time between them, and at most [GROUP_GAP_MS] apart.
     */
    fun sameRun(row: MessageRow, next: MessageRow, dayStart: (Long) -> Long): Boolean =
        row.direction != "system" && next.direction != "system" &&
            row.direction == next.direction && row.isOutgoing == next.isOutgoing && row.senderId == next.senderId &&
            !timeHeader(row, next, dayStart) && next.timestampMs - row.timestampMs in 0..GROUP_GAP_MS

    /** Where a message is in its run, and what is drawn by it for that. */
    data class Place(
        /** The first of its run: a wider gap above it, and (group chats) the sender's name. */
        val first: Boolean,
        /** The last of its run: its bubble has the tail, and (group chats) the sender's picture beside it. */
        val last: Boolean,
    )

    /** [messages] (oldest first) each in their run. */
    fun places(messages: List<MessageRow>, dayStart: (Long) -> Long): List<Place> = messages.mapIndexed { i, row ->
        val before = messages.getOrNull(i - 1)
        val after = messages.getOrNull(i + 1)
        Place(
            first = before == null || !sameRun(before, row, dayStart),
            last = after == null || !sameRun(row, after, dayStart),
        )
    }
}

/**
 * A bubble as iPhone-style chats draw it: a rounded box with, on the last of a run ([tail]), a
 * small tail at its bottom corner, on the right ([tailRight]: the user's own, left to right) or
 * the left. The view leaves [gutter] free on that side for it, tail or not, so a run's bubbles
 * line up. Its colour is the view's background tint.
 */
class PhoneBubbleDrawable(private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()
    private val body = RectF()

    var tail = false
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    var tailRight = true
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    /** The room at the tail's side, in pixels. */
    val gutter: Int get() = (GUTTER_DP * density).toInt()

    var color: Int
        get() = paint.color
        set(value) {
            if (paint.color != value) {
                paint.color = value
                invalidateSelf()
            }
        }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val g = gutter.toFloat()
        val count = canvas.save()
        // Drawn with the tail on the right; mirrored for the left.
        if (!tailRight) canvas.scale(-1f, 1f, b.exactCenterX(), b.exactCenterY())
        body.set(b.left.toFloat(), b.top.toFloat(), b.right - g, b.bottom.toFloat())
        val r = minOf(RADIUS_DP * density, body.height() / 2f, body.width() / 2f)
        canvas.drawRoundRect(body, r, r, paint)
        if (tail) {
            val x = body.right
            val y = body.bottom
            val d = density
            path.rewind()
            // Down the side to the tip beyond the corner, and back under the corner into the box.
            path.moveTo(x, y - minOf(15f * d, body.height() / 2f))
            path.quadTo(x, y - 3f * d, x + g, y)
            path.quadTo(x - 3f * d, y - 0.5f * d, x - minOf(10f * d, body.width() / 2f), y - 4.5f * d)
            path.close()
            canvas.drawPath(path, paint)
        }
        canvas.restoreToCount(count)
    }

    override fun setTintList(tint: ColorStateList?) {
        if (tint != null) color = tint.defaultColor
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        const val RADIUS_DP = 18f
        const val GUTTER_DP = 6f
    }
}

/**
 * "Someone is typing", as iPhone-style chats show it: a grey bubble (as an incoming message's)
 * with three dots that light up in turn. They stand still where the system's animations are off.
 */
class TypingDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val bubble = PhoneBubbleDrawable(density).apply {
        tail = true
        color = ContextCompat.getColor(context, R.color.phone_incoming)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.phone_typing_dot) }

    /** 0..1 through one round of the three dots. */
    private var phase = 0f
    private var animator: ValueAnimator? = null

    /** Whether the dots are moving (shown, attached, and animations on). */
    val isAnimating: Boolean get() = animator?.isRunning == true

    override fun onDraw(canvas: Canvas) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        bubble.tailRight = rtl
        bubble.setBounds(0, 0, width, height)
        bubble.draw(canvas)
        val gutter = bubble.gutter
        val left = if (rtl) 0f else gutter.toFloat()
        val inner = width - gutter
        val radius = DOT_RADIUS_DP * density
        val spacing = DOT_SPACING_DP * density
        val cx = left + inner / 2f
        val cy = height / 2f
        for (i in 0 until DOTS) {
            // Each dot is at its brightest a third of a round after the one before it.
            val at = ((phase - i / DOTS.toFloat()) % 1f + 1f) % 1f
            val lit = 1f - minOf(at, 1f - at) * 2f
            dot.alpha = (MIN_ALPHA + (255 - MIN_ALPHA) * lit * lit).toInt()
            canvas.drawCircle(cx + (i - 1) * spacing, cy, radius, dot)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sync()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        sync()
    }

    private fun sync() {
        if (isAttachedToWindow && isShown && ValueAnimator.areAnimatorsEnabled()) start() else stop()
    }

    private fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ROUND_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
    }

    private companion object {
        const val DOTS = 3
        const val DOT_RADIUS_DP = 4f
        const val DOT_SPACING_DP = 12f
        const val MIN_ALPHA = 90
        const val ROUND_MS = 1200L
    }
}
