package com.lightdecoder.ui.chat

import android.graphics.Rect
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs
import kotlin.math.max

/**
 * A sideways swipe anywhere on the conversation list goes to the next app (leftwards) or the one
 * before, as the swipe along the top bar does ([ServiceTabStrip.Refresh.go], the same path).
 *
 * The screen hands it every touch first ([handle], from `dispatchTouchEvent`). It only takes a
 * touch that is clearly sideways (more than [RATIO] times as far across as up or down): a scroll of
 * the list, a pull to refresh, a tap or a long press on a row are left alone, and so is a touch
 * that starts
 * - within [EDGE_DP] (or the system's gesture inset, if wider) of the left or right edge, where
 *   Android's back gesture is,
 * - in [excluded] views (the top bar, which has its own swipe),
 * - on something that scrolls sideways itself (the folder tabs when they overflow) or a snackbar
 *   (swiped away sideways).
 * Once it takes a touch, the views under the finger get a cancel, so the touch is not also a tap.
 * The switch happens when the finger lifts, after a swipe across a quarter of the screen or a quick
 * fling.
 */
internal class AppSwipe(
    private val root: View,
    private val excluded: () -> List<View>,
    private val enabled: () -> Boolean,
    private val onStep: (Int) -> Unit,
) {
    enum class Result {
        /** Not this one's: the screen dispatches the event as usual. */
        PASS,

        /** Taken from now on: the screen sends its views a cancel in place of this event. */
        CLAIMED,

        /** Already taken: the screen does nothing more with it. */
        CONSUMED,
    }

    private val density = root.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(root.context).scaledTouchSlop
    private var tracking = false
    private var claimed = false
    private var downX = 0f
    private var downY = 0f
    private var velocity: VelocityTracker? = null

    fun handle(event: MotionEvent): Result {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            reset()
            tracking = enabled() && startsHere(event.rawX, event.rawY)
            if (!tracking) return Result.PASS
            downX = event.rawX
            downY = event.rawY
            velocity = VelocityTracker.obtain().also { it.addMovement(event) }
            return Result.PASS
        }
        if (!tracking) return Result.PASS
        velocity?.addMovement(event)
        val dx = event.rawX - downX
        val dy = event.rawY - downY
        return when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> when {
                claimed -> Result.CONSUMED
                // Up or down first: a scroll or a pull, never a switch.
                abs(dy) > slop && abs(dy) * RATIO >= abs(dx) -> {
                    tracking = false
                    Result.PASS
                }
                abs(dx) > 2 * slop && abs(dx) > RATIO * abs(dy) -> {
                    claimed = true
                    Result.CLAIMED
                }
                else -> Result.PASS
            }
            MotionEvent.ACTION_UP -> {
                val vx = velocity?.let {
                    it.computeCurrentVelocity(1000)
                    it.xVelocity
                } ?: 0f
                val step = stepFor(dx, dy, vx, root.width.toFloat(), density)
                val wasClaimed = claimed
                reset()
                if (step != 0) onStep(step)
                when {
                    wasClaimed -> Result.CONSUMED
                    // A fling so quick no move came between: its views must not take it as a tap.
                    step != 0 -> Result.CLAIMED
                    else -> Result.PASS
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger: a pinch or the like, not a swipe.
                if (claimed) {
                    Result.CONSUMED
                } else {
                    reset()
                    Result.PASS
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                val wasClaimed = claimed
                reset()
                if (wasClaimed) Result.CONSUMED else Result.PASS
            }
            else -> if (claimed) Result.CONSUMED else Result.PASS
        }
    }

    private fun reset() {
        tracking = false
        claimed = false
        velocity?.recycle()
        velocity = null
    }

    /** Whether a touch going down at ([x], [y]) on the screen may become a switch. */
    private fun startsHere(x: Float, y: Float): Boolean {
        val at = IntArray(2)
        root.getLocationOnScreen(at)
        val left = x - at[0]
        val gestures = ViewCompat.getRootWindowInsets(root)?.getInsets(WindowInsetsCompat.Type.systemGestures())
        val edge = EDGE_DP * density
        if (left < max(edge, (gestures?.left ?: 0).toFloat())) return false
        if (left > root.width - max(edge, (gestures?.right ?: 0).toFloat())) return false
        if (excluded().any { it.isShown && contains(it, x, y) }) return false
        return !overSidewaysThing(root, x, y)
    }

    /** Whether something under ([x], [y]) scrolls sideways or is swiped away sideways itself. */
    private fun overSidewaysThing(view: View, x: Float, y: Float): Boolean {
        if (!view.isShown || !contains(view, x, y)) return false
        if (view !== root && (view.canScrollHorizontally(-1) || view.canScrollHorizontally(1))) return true
        if (view.javaClass.name.contains("Snackbar")) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (overSidewaysThing(view.getChildAt(i), x, y)) return true
            }
        }
        return false
    }

    private fun contains(view: View, x: Float, y: Float): Boolean {
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        return Rect(at[0], at[1], at[0] + view.width, at[1] + view.height).contains(x.toInt(), y.toInt())
    }

    companion object {
        /** How many times further across than up or down a swipe must go. */
        const val RATIO = 2.5f

        /** Near the left and right edges Android's back gesture comes first. */
        const val EDGE_DP = 24f

        /** A swipe this share of the screen's width switches, however slow. */
        const val FAR_SHARE = 0.25f

        /** A fling at least this fast (dp per second) and [FLING_MIN_DP] long switches too. */
        const val FLING_DP_PER_S = 1000f
        const val FLING_MIN_DP = 48f

        /**
         * Which way a finished swipe goes: 1 (the next app) for one to the left, -1 for one to the
         * right, 0 when it is not clearly sideways, or neither far ([FAR_SHARE] of [widthPx]) nor a
         * fling in its own direction.
         */
        fun stepFor(dx: Float, dy: Float, vx: Float, widthPx: Float, density: Float): Int {
            if (dx == 0f || abs(dx) <= RATIO * abs(dy)) return 0
            val far = abs(dx) >= FAR_SHARE * widthPx
            val fling = abs(vx) >= FLING_DP_PER_S * density && abs(dx) >= FLING_MIN_DP * density && (vx < 0) == (dx < 0)
            if (!far && !fling) return 0
            return if (dx < 0) 1 else -1
        }
    }
}
