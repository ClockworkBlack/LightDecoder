package com.lightdecoder.ui.imessage

import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.material.tabs.TabLayout
import com.lightdecoder.R
import com.lightdecoder.chat.Features

/**
 * The iMessage entry in the app switcher. Like Discord's and Messenger's it is not a
 * [com.lightdecoder.chat.ChatService]: choosing it opens [ImessageActivity] in its own process.
 * Shown from Android 9, which the separate process needs to tell itself apart.
 */
object ImessageTab {

    private val TAG = Any()

    val shown: Boolean
        get() = Features.IMESSAGE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /** Adds the tab last. Call it after the other tabs are in. */
    fun add(tabs: TabLayout, selected: Boolean = false) {
        if (!shown) return
        tabs.addTab(tabs.newTab().setText(R.string.imessage_tab).setTag(TAG), selected)
    }

    fun isTab(tab: TabLayout.Tab): Boolean = tab.tag === TAG

    fun intent(context: Context): Intent = Intent(context, ImessageActivity::class.java)
}
