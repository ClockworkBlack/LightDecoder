package com.lightdecoder.ui.chat

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.TextPaint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.annotation.VisibleForTesting
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * iPhone-style chats: drag the transcript to the left (to the right in a right-to-left language) and
 * every message's time shows, small and grey, in a column at the far edge, as Messages on an iPhone
 * shows them: the user's own bubbles (blue and green) slide over by at most [MAX_SHIFT_DP] to make
 * room for it, while other people's bubbles, the notices and the times between the messages stay
 * where they are, their times in the free space beside them; all spring back when the finger lets
 * go. A time never covers a bubble: beside one of someone else's that reaches into the column it is
 * moved over into what room is left, or not drawn for that row when there is none.
 *
 * It only ever takes a drag that is plainly sideways that way: a scroll up or down is the list's, a
 * drag the other way is swipe-to-reply's (the thread's [androidx.recyclerview.widget.ItemTouchHelper]),
 * and a finger held still is a long press, as before. The rows are moved by their translation, set on
 * every row on screen whenever the shift changes and on each row the list lays out meanwhile
 * ([onChildViewAttachedToWindow]), and taken off again as a row leaves ([onChildViewDetachedFromWindow]),
 * so it holds as rows are recycled; the times are drawn over the list ([onDrawOver]) from where the
 * rows are, frame by frame.
 *
 * @param enabled whether it is on now (iPhone-style chats)
 * @param timeOf the time to show beside a row (its message's); null for a row with none (a time
 *   between messages, a notice)
 * @param anchorOf the view of a row its time lines up with and keeps clear of (its bubble); null for
 *   the row's middle
 * @param slides whether a row with a time slides over to make room for it (the user's own messages,
 *   on the far side); the others stay put
 * @param color the times' colour now (grey; white over a chat's picture)
 */
class TimeReveal(
    private val list: RecyclerView,
    private val enabled: () -> Boolean,
    private val timeOf: (RecyclerView.ViewHolder) -> String?,
    private val anchorOf: (RecyclerView.ViewHolder) -> View? = { null },
    private val color: () -> Int = { Color.GRAY },
    private val shadow: () -> Boolean = { false },
    private val slides: (RecyclerView.ViewHolder) -> Boolean = { true },
) : RecyclerView.ItemDecoration(), RecyclerView.OnItemTouchListener, RecyclerView.OnChildAttachStateChangeListener {

    private val density = list.resources.displayMetrics.density

    /** The furthest the rows go. */
    val maxShift: Float = MAX_SHIFT_DP * density
    private val gap = GAP_DP * density
    private val edge = EDGE_DP * density
    private val slop = ViewConfiguration.get(list.context).scaledTouchSlop

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP, list.resources.displayMetrics)
    }

    /** How far the user's own rows are slid now (how far the times are in), 0 to [maxShift]. */
    var offset = 0f
        private set

    /** The rows this moved (and has not put back yet). */
    private val moved = HashSet<View>()

    private var tracking = false
    private var dragging = false
    private var pointer = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var back: ValueAnimator? = null

    /** Adds it to [list]: its touches, its drawing, and its eye on the rows coming and going. */
    fun attach(): TimeReveal {
        list.addOnItemTouchListener(this)
        list.addItemDecoration(this)
        list.addOnChildAttachStateChangeListener(this)
        return this
    }

    private val rtl: Boolean get() = list.layoutDirection == View.LAYOUT_DIRECTION_RTL

    /** How far the finger went the way that reveals the times (to the start of the line), since it came down. */
    private fun revealing(e: MotionEvent, index: Int): Float {
        val dx = e.getX(index) - downX
        return if (rtl) dx else -dx
    }

    // --- touches ---------------------------------------------------------------------------

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = enabled()
                dragging = false
                pointer = e.getPointerId(0)
                downX = e.x
                downY = e.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (!dragging) tracking = false
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                // The list is scrolling: the gesture is the scroll's.
                if (rv.scrollState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    tracking = false
                    return false
                }
                val index = e.findPointerIndex(pointer)
                if (index < 0) {
                    tracking = false
                    return false
                }
                val along = revealing(e, index)
                val dy = abs(e.getY(index) - downY)
                when {
                    // Up or down first: a scroll.
                    dy > slop && dy >= abs(along) -> tracking = false
                    // The other way: swipe-to-reply's.
                    -along > slop -> tracking = false
                    along > slop && along > dy * SIDEWAYS -> {
                        dragging = true
                        back?.cancel()
                        back = null
                        // Followed from here on, so the rows do not jump by the slop.
                        downX = e.getX(index)
                        rv.parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return false
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        if (!dragging) return
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val index = e.findPointerIndex(pointer)
                if (index < 0) return release()
                shift(revealing(e, index).coerceIn(0f, maxShift))
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == pointer) release()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
        }
    }

    /** The finger let go (or the gesture was taken away): the rows spring back. */
    private fun release() {
        dragging = false
        tracking = false
        springBack()
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // A row's own control (a voice message's bar) took the gesture.
        if (disallowIntercept && !dragging) tracking = false
    }

    private fun springBack() {
        back?.cancel()
        if (offset == 0f) return
        if (!ValueAnimator.areAnimatorsEnabled()) {
            shift(0f)
            return
        }
        back = ValueAnimator.ofFloat(offset, 0f).apply {
            duration = SPRING_MS
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { shift(it.animatedValue as Float) }
            start()
        }
    }

    /** Stops at once, the rows back in place (the list is about to change under it, or it is turned off). */
    fun reset() {
        back?.cancel()
        back = null
        dragging = false
        tracking = false
        shift(0f)
    }

    // --- the rows --------------------------------------------------------------------------

    /** Slides every row that moves (the user's own) to [to]; at 0 they are back where they were. */
    private fun shift(to: Float) {
        if (to == offset && (to != 0f || moved.isEmpty())) return
        offset = to
        val dx = if (rtl) to else -to
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            if (moves(child)) {
                child.translationX = dx
                if (to == 0f) moved.remove(child) else moved.add(child)
            }
        }
        if (to == 0f) {
            // Rows moved, then rebound to something that stays put, or gone from the list.
            for (view in moved) view.translationX = 0f
            moved.clear()
        }
        list.invalidate()
    }

    private fun moves(child: View): Boolean {
        val holder = list.getChildViewHolder(child) ?: return false
        return timeOf(holder) != null && slides(holder)
    }

    override fun onChildViewAttachedToWindow(view: View) {
        if (offset == 0f || !moves(view)) return
        view.translationX = if (rtl) offset else -offset
        moved.add(view)
    }

    override fun onChildViewDetachedFromWindow(view: View) {
        if (moved.remove(view)) view.translationX = 0f
    }

    // --- the times -------------------------------------------------------------------------

    /**
     * A time as drawn: its words, where its text starts (and ends: [right]) and its baseline, how
     * opaque (0..1), and the row it is the time of.
     */
    data class Label(val time: String, val x: Float, val right: Float, val baseline: Float, val alpha: Float, val row: View)

    private val rect = Rect()

    /**
     * The times drawn now, one per message row on screen (none while the rows are in place): in the
     * column at the far edge, coming in as the drag goes on, never past the list's edge. Beside a row
     * that stays put, never over its bubble: moved into the room left beside a bubble that reaches into
     * the column, or left out for that row when there is no room for it at all.
     */
    @VisibleForTesting
    fun labels(): List<Label> {
        if (offset <= 0f) return emptyList()
        val alpha = (offset / maxShift).coerceIn(0f, 1f)
        val out = ArrayList<Label>()
        val metrics = paint.fontMetrics
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            val holder = list.getChildViewHolder(child) ?: continue
            val time = timeOf(holder) ?: continue
            val anchor = anchorOf(holder)?.takeIf { it.isShown && it.height > 0 && child is ViewGroup && isInside(it, child) }
            if (anchor != null) {
                rect.set(0, 0, anchor.width, anchor.height)
                (child as ViewGroup).offsetDescendantRectToMyCoords(anchor, rect)
            }
            val middle = if (anchor != null) rect.exactCenterY() else child.height / 2f
            val y = child.top + child.translationY + middle
            val width = paint.measureText(time)
            // The column: just beyond where the user's own rows now end, held inside the list's edge.
            var x = if (rtl) (offset - gap - width).coerceAtLeast(edge) else (list.width - offset + gap).coerceAtMost(list.width - edge - width)
            if (anchor != null && !slides(holder)) {
                // A row that stays put: its bubble is never covered.
                if (rtl) {
                    val bubbleStart = child.left + child.translationX + rect.left
                    val room = bubbleStart - gap
                    if (room - width < edge) continue
                    x = minOf(x, room - width)
                } else {
                    val bubbleEnd = child.left + child.translationX + rect.right
                    val from = bubbleEnd + gap
                    if (from + width > list.width - edge) continue
                    x = maxOf(x, from)
                }
            }
            out += Label(time, x, x + width, y - (metrics.ascent + metrics.descent) / 2f, alpha, child)
        }
        return out
    }

    private fun isInside(view: View, parent: ViewGroup): Boolean {
        var p = view.parent
        while (p != null) {
            if (p === parent) return true
            p = p.parent
        }
        return false
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val labels = labels()
        if (labels.isEmpty()) return
        paint.color = color()
        if (shadow()) paint.setShadowLayer(4f, 0f, 1f, SHADOW) else paint.clearShadowLayer()
        val base = Color.alpha(paint.color)
        for (label in labels) {
            paint.alpha = (base * label.alpha).toInt()
            c.drawText(label.time, label.x, label.baseline, paint)
        }
    }

    companion object {
        /** How far the rows slide at most, as an iPhone's. */
        const val MAX_SHIFT_DP = 68f

        /** Between a row's end and its time, and the least room left at the list's edge. */
        private const val GAP_DP = 6f
        private const val EDGE_DP = 6f
        private const val TEXT_SP = 11f

        /** How much more sideways than up or down a drag must be to be one. */
        private const val SIDEWAYS = 1.2f

        /** How long the rows take to spring back. */
        private const val SPRING_MS = 240L
        private const val SHADOW = 0xB3000000.toInt()
    }
}
