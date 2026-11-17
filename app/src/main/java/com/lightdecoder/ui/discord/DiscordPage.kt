package com.lightdecoder.ui.discord

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView

/**
 * The view Discord's page is shown in, made to sit still: the page cannot be
 * dragged sideways as a whole, does not stretch or bounce at its edges, and
 * has no pinch or double-tap zoom to trip over ("Page size" in the menu sets
 * its size). Nothing in the page is changed; these are the view's own
 * settings, as a browser has.
 */
@SuppressLint("ViewConstructor")
class FirmWebView(context: MutableContextWrapper) : WebView(context) {

    init {
        overScrollMode = View.OVER_SCROLL_NEVER
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = false
        settings.apply {
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
    }

    // The page as a whole never moves sideways; what scrolls inside it (channel lists,
    // messages, its own sliding panels) is the page's business and is left alone.
    override fun scrollTo(x: Int, y: Int) = super.scrollTo(0, y)

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (l != 0) super.scrollTo(0, t)
    }

    override fun overScrollBy(
        deltaX: Int, deltaY: Int, scrollX: Int, scrollY: Int, scrollRangeX: Int, scrollRangeY: Int,
        maxOverScrollX: Int, maxOverScrollY: Int, isTouchEvent: Boolean,
    ): Boolean = super.overScrollBy(0, deltaY, 0, scrollY, 0, scrollRangeY, 0, 0, isTouchEvent)
}

/**
 * Keeps Discord's page loaded between visits to the tab. The page view
 * belongs to the Discord process, not to one showing of the screen: leaving
 * the tab takes the view off the screen and pauses it (its scripts and
 * timers stop, so it does nothing in the background), and coming back puts
 * the same view on again, where it was, instead of loading Discord afresh.
 * If Android has ended the process meanwhile, the page loads again as before
 * (the sign-in is kept on disk either way).
 *
 * Main thread only, and only in the Discord process.
 */
object DiscordPage {

    private var page: FirmWebView? = null

    /** The page view, on [activity]'s terms, and whether it is new (nothing loaded in it yet). */
    fun obtain(activity: Activity): Pair<FirmWebView, Boolean> {
        val kept = page
        if (kept != null) {
            // Still on a screen that is closing: it comes with us.
            (kept.parent as? ViewGroup)?.removeView(kept)
            (kept.context as MutableContextWrapper).baseContext = activity
            kept.resumeTimers()
            kept.onResume()
            return kept to false
        }
        val made = FirmWebView(MutableContextWrapper(activity))
        // In case an earlier page view left this process's timers paused.
        made.resumeTimers()
        page = made
        return made to true
    }

    /**
     * [web] leaves [activity]'s screen and waits, paused, for the next
     * visit. Does nothing if another showing of the screen has taken it.
     */
    fun release(web: WebView, from: ViewGroup, appContext: Context) {
        if (web !== page || web.parent !== from) return
        from.removeView(web)
        web.onPause()
        // Every page view of this process, which is this one: Discord's scripts stop until the next visit.
        web.pauseTimers()
        (web.context as MutableContextWrapper).baseContext = appContext.applicationContext
    }

    /** The page view is done with (its renderer died, say): the next visit makes a new one. */
    fun discard(web: WebView) {
        if (web === page) page = null
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
    }

    @androidx.annotation.VisibleForTesting
    internal fun resetForTesting() {
        page = null
    }
}
