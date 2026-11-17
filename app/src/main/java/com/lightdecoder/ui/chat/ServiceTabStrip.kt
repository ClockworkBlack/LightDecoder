package com.lightdecoder.ui.chat

import android.view.View
import android.widget.PopupMenu
import com.google.android.material.tabs.TabLayout

/**
 * The strip of service tabs (Messages, WhatsApp, Discord...): it scrolls
 * sideways when they do not all fit, and the arrow at its right end opens a
 * list of every tab, the one showing ticked, to go straight to one.
 */
object ServiceTabStrip {

    fun attachDropDown(tabs: TabLayout, arrow: View) {
        arrow.setOnClickListener {
            val menu = PopupMenu(arrow.context, arrow)
            for (i in 0 until tabs.tabCount) {
                val tab = tabs.getTabAt(i) ?: continue
                menu.menu.add(0, i, i, tab.text).apply {
                    isCheckable = true
                    isChecked = i == tabs.selectedTabPosition
                }
            }
            menu.menu.setGroupCheckable(0, true, true)
            menu.setOnMenuItemClickListener { item ->
                val tab = tabs.getTabAt(item.itemId)
                if (tab != null) {
                    // Chosen again, it is the same as tapping the tab that shows.
                    tab.select()
                    // Bring it into view in the strip.
                    tabs.post { tabs.setScrollPosition(item.itemId, 0f, true) }
                }
                true
            }
            menu.show()
        }
    }
}
