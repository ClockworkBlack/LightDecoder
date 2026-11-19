package com.lightdecoder.ui.chat

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout

/**
 * A vertical LinearLayout that never grows wider than [maxWidthFraction] of
 * the screen, so message bubbles leave room on the other side the way chat
 * apps do, whatever the length of the text.
 */
class BubbleLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var maxWidthFraction = DEFAULT_MAX_WIDTH_FRACTION

    /**
     * iPhone-style chats: [cornerChild] (the tapbacks) sits on [cornerAnchor]'s (the bubble's) top
     * corner, at its left ([cornerLeft]) or right, whatever else in the column is wider. Kept inside
     * the column, so all of it can be touched. Null: every child stays where the column put it.
     */
    var cornerChild: View? = null
    var cornerAnchor: View? = null
    var cornerLeft = false

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val child = cornerChild ?: return
        val anchor = cornerAnchor ?: return
        if (child.visibility == GONE || child.parent !== this || anchor.parent !== this) return
        val wanted = if (cornerLeft) anchor.left else anchor.right - child.width
        val left = wanted.coerceIn(0, maxOf(0, width - child.width))
        child.offsetLeftAndRight(left - child.left)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val screen = resources.displayMetrics.widthPixels
        val cap = (screen * maxWidthFraction).toInt()
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val limited = when (mode) {
            MeasureSpec.UNSPECIFIED -> MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST)
            else -> MeasureSpec.makeMeasureSpec(minOf(size, cap), mode)
        }
        super.onMeasure(limited, heightMeasureSpec)
    }

    companion object {
        const val DEFAULT_MAX_WIDTH_FRACTION = 0.8f
    }
}
