package com.lightdecoder.ui.chat

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * The reaction chips under a bubble. They go on as many lines as they need:
 * each chip is a 48dp touch target, and a bubble is at most 80% of the screen
 * wide, so one line holds only five or so on a small phone; a single line
 * would squeeze the last ones into slivers.
 *
 * With [alignEnd] each line sits at the end side (under an outgoing bubble).
 * Follows the layout direction. Child margins are honoured; padding too.
 */
class ReactionRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    var alignEnd = false
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    /** Per child: its line, and its offset from the line's start edge (before alignment). */
    private var lineOf = IntArray(0)
    private var offsetOf = IntArray(0)
    private var lineWidths = IntArray(0)
    private var lineHeights = IntArray(0)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val available = if (widthMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
        else MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val count = childCount
        if (lineOf.size < count) {
            lineOf = IntArray(count)
            offsetOf = IntArray(count)
        }
        val widths = ArrayList<Int>()
        val heights = ArrayList<Int>()
        var x = 0
        var lineHeight = 0
        var lineHasChips = false
        var widest = 0
        for (i in 0 until count) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            // Each chip may take the whole width (a chip wider than that is clipped, as in any layout).
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val lp = child.layoutParams as MarginLayoutParams
            val w = lp.marginStart + child.measuredWidth + lp.marginEnd
            val h = lp.topMargin + child.measuredHeight + lp.bottomMargin
            if (lineHasChips && x + w > available) {
                widths += x
                heights += lineHeight
                widest = maxOf(widest, x)
                x = 0
                lineHeight = 0
            }
            lineOf[i] = widths.size
            offsetOf[i] = x
            x += w
            lineHeight = maxOf(lineHeight, h)
            lineHasChips = true
        }
        if (lineHasChips) {
            widths += x
            heights += lineHeight
            widest = maxOf(widest, x)
        }
        lineWidths = widths.toIntArray()
        lineHeights = heights.toIntArray()
        val contentWidth = widest + paddingLeft + paddingRight
        val contentHeight = heights.sum() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(maxOf(contentWidth, suggestedMinimumWidth), widthMeasureSpec),
            resolveSize(maxOf(contentHeight, suggestedMinimumHeight), heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val inner = (r - l) - paddingLeft - paddingRight
        val lineTops = IntArray(lineHeights.size)
        var y = paddingTop
        for (i in lineHeights.indices) {
            lineTops[i] = y
            y += lineHeights[i]
        }
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            // Not measured yet (added since): the layout pass its addView asked for places it.
            if (child.visibility == View.GONE || i >= lineOf.size || lineOf[i] >= lineWidths.size) continue
            val lp = child.layoutParams as MarginLayoutParams
            val line = lineOf[i]
            val shift = if (alignEnd) maxOf(0, inner - lineWidths[line]) else 0
            // Distance from the start edge to the child's start side.
            val fromStart = shift + offsetOf[i] + lp.marginStart
            val left = if (rtl) paddingLeft + inner - fromStart - child.measuredWidth else paddingLeft + fromStart
            val top = lineTops[line] + lp.topMargin
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(p: LayoutParams?): LayoutParams =
        if (p is MarginLayoutParams) MarginLayoutParams(p) else MarginLayoutParams(p)

    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams
}
