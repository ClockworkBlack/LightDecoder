package com.lightdecoder.ui

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.tabs.TabLayout
import com.lightdecoder.R
import com.lightdecoder.ui.chat.ServiceTabStrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowPopupMenu

/** The app switcher in the top bar: "Messages ▾", with the other apps' unread count beside it. */
@RunWith(AndroidJUnit4::class)
class AppSwitcherTest {

    private val themed = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_LightDecoder)

    @Test
    fun theCountBesideItIsTheOtherAppsUnreadAndTheListShowsEach() {
        val holder = FrameLayout(themed)
        val switcher = LayoutInflater.from(themed).inflate(R.layout.view_app_switcher, holder, false)
        holder.addView(switcher)
        val tabs = TabLayout(themed)
        for (name in listOf("Messages", "Discord", "WhatsApp")) tabs.addTab(tabs.newTab().setText(name), name == "Messages")
        val refresh = ServiceTabStrip.attachSwitcher(tabs, switcher) { "Messages" }
        val label = switcher.findViewById<TextView>(R.id.switcherLabel)
        val badge = switcher.findViewById<TextView>(R.id.switcherBadge)
        assertEquals("Messages", label.text.toString())
        assertEquals("nothing unread", View.GONE, badge.visibility)

        // 72 unread in Messages (the app showing) and 3 in WhatsApp.
        tabs.getTabAt(0)!!.orCreateBadge.number = 72
        tabs.getTabAt(2)!!.orCreateBadge.number = 3
        refresh.refresh()
        assertEquals("only the other apps' count: Messages' is on screen", "3", badge.text.toString())
        assertEquals(View.VISIBLE, badge.visibility)

        switcher.performClick()
        val popup = ShadowPopupMenu.getLatestPopupMenu()!!
        val titles = (0 until popup.menu.size()).map { popup.menu.getItem(it).title.toString() }
        assertEquals(listOf("Messages · 72", "Discord", "WhatsApp · 3"), titles)
        assertTrue(popup.menu.getItem(0).isChecked)
        shadowOf(popup).onMenuItemClickListener.onMenuItemClick(popup.menu.getItem(2))
        assertEquals(2, tabs.selectedTabPosition)
    }
}
