package com.lightdecoder.ui.sticker

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView

/**
 * The sticker maker's scrolling page. While the user draws on the picture
 * (tracing, brushing) it is [locked]: it neither scrolls nor takes the
 * finger away from the drawing, not even on the first touch of a fling
 * still settling, which used to swallow the start of a trace.
 */
class DrawingScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    var locked: Boolean = false
        set(value) {
            field = value
            // Stop a fling that is still moving the page.
            if (value) smoothScrollBy(0, 0)
        }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = if (locked) false else super.onInterceptTouchEvent(ev)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean = if (locked) false else super.onTouchEvent(ev)
}
