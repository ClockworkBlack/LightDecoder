package com.lightdecoder.chat

/**
 * Parts of the app that are built but switched off for now, by the user's
 * choice. Their code and tests stay; switching one back on is this flag.
 */
object Features {
    /** The Gmail tab: paused while the app's own features come first. */
    const val GMAIL = false

    /** The services shown as tabs (and set up from the home screen). */
    fun shownServices(): List<ChatService> = ChatService.entries.filter { it != ChatService.GMAIL || GMAIL }
}
