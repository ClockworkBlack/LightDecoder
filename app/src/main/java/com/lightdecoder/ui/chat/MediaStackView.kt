package com.lightdecoder.ui.chat

import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Photos sent together, drawn as an iPhone draws them: a collage of 2-3 overlapping cards, each a
 * little askew, or a stack of 4 or more (the top card, two offset behind it; the count shows on a
 * badge over it, [MediaStacks]). Its cards are plain [ImageView]s the [MediaLoader] fills ([cards]),
 * the last one on top. Taps go to the whole view: one shows them all.
 */
class MediaStackView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    /** The cards, the bottom one first. */
    val cards: List<ImageView> get() = (0 until childCount).mapNotNull { getChildAt(it) as? ImageView }

    private var placement: Placement? = null

    /** Draws [count] photos as [style] does, each card [sidePx] square. */
    fun show(count: Int, style: MediaStacks.Style, sidePx: Int) {
        val p = placementOf(count, style, sidePx, resources.displayMetrics.density)
        placement = p
        removeAllViews()
        val radius = 12 * resources.displayMetrics.density
        for (card in p.cards) {
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
                clipToOutline = true
                rotation = card.rotation
                translationX = card.dx.toFloat()
                translationY = card.dy.toFloat()
                setBackgroundColor(0x33808080)
            }
            addView(image, LayoutParams(sidePx, sidePx))
        }
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val p = placement ?: return
        setMeasuredDimension(p.width, p.height)
    }

    /** Where each card goes: its offset from the top left and its tilt. */
    data class Card(val dx: Int, val dy: Int, val rotation: Float)

    /** The cards of a stack or collage, the bottom one first, and the room they take. */
    data class Placement(val cards: List<Card>, val width: Int, val height: Int)

    companion object {
        /**
         * Where the cards of [count] photos go (pure, tested): a collage lays its 2-3 cards across,
         * each overlapping the one before by a third and tilted ([MediaStacks.collageRotation]); a
         * stack offsets the two behind the top card down and to the right, the top one level.
         */
        fun placementOf(count: Int, style: MediaStacks.Style, side: Int, density: Float): Placement {
            val drawn = MediaStacks.cardsDrawn(count).coerceAtLeast(1)
            return when (style) {
                MediaStacks.Style.COLLAGE -> {
                    val step = side * 2 / 3
                    val margin = (8 * density).toInt()
                    val cards = (0 until drawn).map { i -> Card(margin + i * step / 2, margin + (if (i % 2 == 0) 0 else (6 * density).toInt()), MediaStacks.collageRotation(i, drawn)) }
                    Placement(cards, margin * 2 + side + (drawn - 1) * step / 2, margin * 2 + side + (6 * density).toInt())
                }
                MediaStacks.Style.STACK -> {
                    val offset = (6 * density).toInt()
                    // The bottom card is the farthest behind; the top one, last, sits level at the top left.
                    val cards = (0 until drawn).map { i -> val back = drawn - 1 - i; Card(back * offset, back * offset, if (back == 0) 0f else back * 2f) }
                    Placement(cards, side + (drawn - 1) * offset, side + (drawn - 1) * offset)
                }
            }
        }
    }
}
