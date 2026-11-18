package com.lightdecoder.ui.chat

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import com.google.android.material.tabs.TabLayout
import com.lightdecoder.R

/**
 * The app switcher in the top bar ([R.layout.view_app_switcher]): "Messages ▾".
 *
 * The apps (Messages, Discord, WhatsApp) are the tabs of a [TabLayout] that is
 * not shown: it holds which is chosen and each one's unread badge, and the
 * screens listen to it as before. The switcher shows the app on screen, and
 * how many conversations are unread in the others; a tap lists every app with
 * its count, the one showing ticked, and choosing one selects its tab. A
 * sideways swipe along the bar goes to the next app, or the one before.
 */
object ServiceTabStrip {

    /** Updates the switcher; call it again when the badges change. */
    fun interface Refresh {
        fun refresh()
    }

    /**
     * [current] is the name of the app on screen (a tab chosen to open another
     * screen, such as Discord's, does not change it).
     */
    fun attachSwitcher(tabs: TabLayout, switcher: View, current: () -> CharSequence): Refresh {
        val label = switcher.findViewById<TextView>(R.id.switcherLabel)
        val badge = switcher.findViewById<TextView>(R.id.switcherBadge)
        val refresh = Refresh {
            val showing = current()
            label.text = showing
            val elsewhere = (0 until tabs.tabCount).mapNotNull { tabs.getTabAt(it) }
                .filter { it.text != showing }
                .sumOf { it.badge?.number ?: 0 }
            badge.visibility = if (elsewhere > 0) View.VISIBLE else View.GONE
            badge.text = if (elsewhere > 99) "99+" else elsewhere.toString()
            switcher.contentDescription = switcher.context.getString(R.string.chat_switcher_description, showing)
        }
        switcher.setOnClickListener {
            val menu = PopupMenu(switcher.context, switcher)
            val showing = current()
            for (i in 0 until tabs.tabCount) {
                val tab = tabs.getTabAt(i) ?: continue
                val count = tab.badge?.number ?: 0
                val title = if (count > 0) switcher.context.getString(R.string.chat_switcher_item_count, tab.text, count) else tab.text
                menu.menu.add(0, i, i, title).apply {
                    isCheckable = true
                    isChecked = tab.text == showing
                }
            }
            menu.menu.setGroupCheckable(0, true, true)
            menu.setOnMenuItemClickListener { item ->
                tabs.getTabAt(item.itemId)?.select()
                switcher.post { refresh.refresh() }
                true
            }
            menu.show()
        }
        // A sideways swipe along the top bar goes to the next app (leftwards) or the one before.
        val swipe = swipeListener(switcher.resources.displayMetrics.density) { step ->
            val showing = current()
            val at = (0 until tabs.tabCount).firstOrNull { tabs.getTabAt(it)?.text == showing } ?: tabs.selectedTabPosition
            neighbour(at, tabs.tabCount, step)?.let { tabs.getTabAt(it)?.select() }
            switcher.post { refresh.refresh() }
        }
        switcher.setOnTouchListener(swipe)
        (switcher.parent as? View)?.setOnTouchListener(swipe)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = refresh.refresh()
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        refresh.refresh()
        return refresh
    }

    /** The app [step] places on from the one at [at], going round at the ends; null when there is no other. */
    internal fun neighbour(at: Int, count: Int, step: Int): Int? =
        if (count < 2 || at !in 0 until count) null else ((at + step) % count + count) % count

    /**
     * Which way a finger's movement asks to go: 1 (the next app) for a swipe
     * to the left, -1 for one to the right, 0 for anything that is not
     * clearly a sideways swipe of at least [minPx].
     */
    internal fun swipeStep(dx: Float, dy: Float, minPx: Float): Int = when {
        kotlin.math.abs(dx) < minPx || kotlin.math.abs(dx) < 2 * kotlin.math.abs(dy) -> 0
        dx < 0 -> 1
        else -> -1
    }

    /**
     * Calls [onSwipe] with [swipeStep]'s answer when a touch ends as a
     * sideways swipe, and takes that touch (so it is not also a tap);
     * every other touch is left to the view.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun swipeListener(density: Float, onSwipe: (Int) -> Unit): View.OnTouchListener {
        var downX = 0f
        var downY = 0f
        return View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    false
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val step = swipeStep(event.rawX - downX, event.rawY - downY, SWIPE_DP * density)
                    if (step == 0) {
                        false
                    } else {
                        view.isPressed = false
                        view.cancelLongPress()
                        onSwipe(step)
                        true
                    }
                }
                else -> false
            }
        }
    }

    private const val SWIPE_DP = 56

    /** Opens another app's screen with a quick fade, as one screen changing, not pages sliding. */
    fun open(from: Activity, intent: Intent) {
        from.startActivity(intent, ActivityOptions.makeCustomAnimation(from, R.anim.switch_in, R.anim.switch_hold).toBundle())
    }

    /** The same fade for a screen that closes as it hands over to another. */
    @Suppress("DEPRECATION")
    fun fadeOnClose(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 34) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, R.anim.switch_in, R.anim.switch_hold)
        } else {
            activity.overridePendingTransition(R.anim.switch_in, R.anim.switch_hold)
        }
    }
}
