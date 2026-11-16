package com.lightdecoder.ui.chat

import android.content.Context
import android.util.AttributeSet
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

    var maxWidthFraction = 0.8f

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
}
