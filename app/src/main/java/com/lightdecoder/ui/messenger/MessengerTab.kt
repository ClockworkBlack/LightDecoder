package com.lightdecoder.ui.messenger

import android.content.Context
import android.content.Intent
import com.google.android.material.tabs.TabLayout
import com.lightdecoder.R
import com.lightdecoder.chat.Features

/**
 * The Messenger tab in the strip of service tabs. Like Discord's, it is not a
 * [com.lightdecoder.chat.ChatService]: it has no link, bridge or archive, and
 * choosing it opens its own screen ([MessengerActivity]). The screens that
 * draw the strip add it with [add] and ask [isTab] when a tab is chosen, so
 * everything else about it stays in this package.
 */
object MessengerTab {

    private val TAG = Any()

    /** Adds the tab after the tabs already there, if the Messenger tab is switched on. */
    fun add(tabs: TabLayout, selected: Boolean = false) {
        if (!Features.MESSENGER) return
        tabs.addTab(tabs.newTab().setText(R.string.chat_service_messenger).setTag(TAG), selected)
    }

    fun isTab(tab: TabLayout.Tab): Boolean = tab.tag === TAG

    /** The Messenger inbox. */
    fun intent(context: Context): Intent = Intent(context, MessengerActivity::class.java)
}
