package com.lightdecoder.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lightdecoder.R

/**
 * A message's row that leaves room above its bubble for a reply's faded copy of the message it
 * answers ([R.id.threadQuote], iPhone-style chats): the copy sits at the top, on that message's
 * side, and the bubble's column goes under it. Without one it lays out as a FrameLayout.
 */
class ReplyRowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private var quote: View? = null
    private var column: View? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        quote = findViewById(R.id.threadQuote)
        column = findViewById(R.id.column)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val quote = quote
        val column = column
        if (quote != null && column != null) {
            val room = if (quote.visibility != GONE) {
                measureChildWithMargins(quote, widthMeasureSpec, 0, heightMeasureSpec, 0)
                quote.measuredHeight + (quote.layoutParams as MarginLayoutParams).bottomMargin
            } else {
                0
            }
            val params = column.layoutParams as LayoutParams
            if (params.topMargin != room) params.topMargin = room
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/**
 * The thin lines of inline replies in iPhone-style chats ([ReplyThreads.Mark.connectTo]): from a
 * message down to the reply that answers it (or to the reply's faded copy of it), and from a
 * faded copy to its reply. On the same side, a bracket in the margin beside the bubbles; from one
 * side to the other, an elbow out of the message's inner edge into the top of the reply. Drawn
 * under the rows each frame from where their bubbles are now, so it follows scrolling, swipes,
 * recycling and rows changing height; an end scrolled out of sight runs off the list's edge.
 * Without [copyLines] (the default look, reply threads not drawn in the chat) a faded copy stands
 * alone: no line joins it to its reply.
 */
class ReplyLines(
    context: Context,
    /** Whether a faded copy is joined to its reply by a line ("Show reply threads in the chat"). */
    private val copyLines: () -> Boolean = { true },
) : RecyclerView.ItemDecoration() {

    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_DP * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.phone_thread_line)
    }
    private val path = Path()
    private val upper = RectF()
    private val lower = RectF()

    /** The list the positions below were read from, and each message's position in it. */
    private var indexed: List<ThreadItem>? = null
    private val positions = HashMap<String, Int>()

    /** How many lines were drawn at the last frame (for tests). */
    var drawn = 0
        private set

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        drawn = 0
        val adapter = parent.adapter as? MessagesAdapter ?: return
        if (!adapter.phoneStyle) return
        val lm = parent.layoutManager as? LinearLayoutManager ?: return
        val list = adapter.currentList
        if (list !== indexed) {
            indexed = list
            positions.clear()
            list.forEachIndexed { i, item -> if (item is ThreadItem.Message) positions[item.row.id] = i }
        }
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return
        val rtl = parent.layoutDirection == View.LAYOUT_DIRECTION_RTL
        // A reply below the list's end can still have a line up to a message on screen.
        val end = minOf(list.lastIndex, last + LOOK_BELOW)
        for (pos in first..end) {
            val item = list[pos] as? ThreadItem.Message ?: continue
            val mark = item.thread ?: continue
            val holder = parent.findViewHolderForAdapterPosition(pos) as? MessagesAdapter.MessageHolder
            val replyRight = rightSide(item, rtl)
            val quote = holder?.binding?.threadQuote?.takeIf { it.visibility == View.VISIBLE }
            // The faded copy (on the answered message's side) to its reply.
            if (holder != null && quote != null && copyLines() && bounds(quote, parent, upper) && bodyBounds(holder, parent, lower)) {
                val quoteRight = upper.centerX() > parent.width / 2f
                connect(c, upper, quoteRight, lower, replyRight, parent)
            }
            val target = mark.connectTo ?: continue
            val at = positions[target] ?: continue
            if (at >= pos || at > last) continue
            val targetItem = list[at] as? ThreadItem.Message ?: continue
            val targetRight = rightSide(targetItem, rtl)
            val targetHolder = parent.findViewHolderForAdapterPosition(at) as? MessagesAdapter.MessageHolder
            // Where it starts: the message's bubble, or above the list's top.
            if (targetHolder == null || !bodyBounds(targetHolder, parent, upper)) {
                if (at >= first) continue
                upper.set(0f, -OFF_SCREEN_DP * density, 0f, -OFF_SCREEN_DP * density + 1f)
                offEdge(upper, targetRight, parent, holder, quote)
            }
            // Where it ends: the faded copy (same side as the message), the bubble, or below the list's bottom.
            val endsOnQuote = quote != null
            val ok = when {
                holder == null -> false
                endsOnQuote -> bounds(quote!!, parent, lower)
                else -> bodyBounds(holder, parent, lower)
            }
            if (!ok) {
                if (holder != null) continue
                val y = parent.height + OFF_SCREEN_DP * density
                lower.set(upper.left, y, upper.right, y + 1f)
            }
            val lowerRight = if (endsOnQuote) targetRight else replyRight
            connect(c, upper, targetRight, lower, if (holder == null) targetRight else lowerRight, parent)
        }
    }

    /** A message on the right of the screen (the user's own, left to right). */
    private fun rightSide(item: ThreadItem.Message, rtl: Boolean): Boolean =
        (item.row.isOutgoing || item.row.direction == "out") != rtl

    /** An end out of sight: as wide as the end that is on screen, on its own side, so the line runs straight off the edge. */
    private fun offEdge(rect: RectF, right: Boolean, parent: RecyclerView, holder: MessagesAdapter.MessageHolder?, quote: View?) {
        val other = RectF()
        val known = when {
            quote != null -> bounds(quote, parent, other)
            holder != null -> bodyBounds(holder, parent, other)
            else -> false
        }
        if (known) {
            rect.left = other.left
            rect.right = other.right
        } else {
            val w = parent.width.toFloat()
            rect.left = if (right) w * 0.5f else SIDE_DP * density
            rect.right = if (right) w - SIDE_DP * density else w * 0.5f
        }
    }

    /**
     * A line from [a] (above, on the right when [aRight]) to [b] (below): a bracket in the margin
     * when both are on one side, else an elbow from [a]'s inner edge into [b]'s top.
     */
    private fun connect(c: Canvas, a: RectF, aRight: Boolean, b: RectF, bRight: Boolean, parent: RecyclerView) {
        if (b.top <= a.bottom - 1f && aRight == bRight) return
        val d = density
        path.rewind()
        if (aRight == bRight) {
            val ya = a.bottom - minOf(a.height() / 2f, END_INSET_DP * d)
            val yb = b.top + minOf(b.height() / 2f, END_INSET_DP * d)
            if (yb <= ya) return
            val r = minOf(RADIUS_DP * d, (yb - ya) / 2f)
            val edge = if (aRight) {
                (maxOf(a.right, b.right) + MARGIN_DP * d).coerceAtMost(parent.width - paint.strokeWidth)
            } else {
                (minOf(a.left, b.left) - MARGIN_DP * d).coerceAtLeast(paint.strokeWidth)
            }
            val sa = if (aRight) a.right else a.left
            val sb = if (aRight) b.right else b.left
            val dir = if (aRight) 1f else -1f
            path.moveTo(sa, ya)
            path.lineTo(edge - dir * r, ya)
            path.quadTo(edge, ya, edge, ya + r)
            path.lineTo(edge, yb - r)
            path.quadTo(edge, yb, edge - dir * r, yb)
            path.lineTo(sb, yb)
        } else {
            // Into the reply's top, a little in from its inner edge.
            val x = if (bRight) b.left + ELBOW_IN_DP * d else b.right - ELBOW_IN_DP * d
            val inner = if (aRight) a.left else a.right
            val beyond = if (bRight) x - inner else inner - x
            val r = RADIUS_DP * d
            if (beyond > r) {
                val y = a.bottom - minOf(a.height() / 2f, END_INSET_DP * d)
                if (b.top - y < r) return
                val dir = if (bRight) 1f else -1f
                path.moveTo(inner, y)
                path.lineTo(x - dir * r, y)
                path.quadTo(x, y, x, y + r)
                path.lineTo(x, b.top)
            } else {
                // They overlap across: straight down from under the message.
                if (b.top - a.bottom < 1f) return
                path.moveTo(x, a.bottom)
                path.lineTo(x, b.top)
            }
        }
        c.drawPath(path, paint)
        drawn++
    }

    /** [view]'s box in [parent]'s coordinates, as drawn now (with its and its parents' translations). */
    private fun bounds(view: View, parent: RecyclerView, out: RectF): Boolean {
        if (!view.isLaidOut || view.width == 0) return false
        var x = 0f
        var y = 0f
        var v: View = view
        while (v !== parent) {
            x += v.left + v.translationX
            y += v.top + v.translationY
            v = v.parent as? View ?: return false
        }
        out.set(x, y, x + view.width, y + view.height)
        return true
    }

    /** The bubble's own box: without the room beside it kept for its tail. */
    private fun bodyBounds(holder: MessagesAdapter.MessageHolder, parent: RecyclerView, out: RectF): Boolean {
        val bubble = holder.binding.bubble
        if (!bounds(bubble, parent, out)) return false
        val shape = bubble.background as? PhoneBubbleDrawable ?: return true
        if (shape.tailRight) out.right -= shape.gutter else out.left += shape.gutter
        return true
    }

    private companion object {
        const val STROKE_DP = 1.5f
        const val RADIUS_DP = 9f
        const val MARGIN_DP = 7f
        const val END_INSET_DP = 14f
        const val ELBOW_IN_DP = 22f
        const val SIDE_DP = 16f
        const val OFF_SCREEN_DP = 24f
        const val LOOK_BELOW = 24
    }
}
