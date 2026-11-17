package com.lightdecoder.chat

/**
 * Parts of the app that are built but switched off for now, by the user's
 * choice. Their code and tests stay; switching one back on is this flag.
 */
object Features {
    /** The Gmail tab: paused while the app's own features come first. */
    const val GMAIL = false

    /**
     * The Discord tab: Discord's website in its own container, beside the
     * services' tabs. It is not a [ChatService]: it has no backend or archive.
     */
    const val DISCORD = true

    /**
     * The Messenger tab: an inbox built from the Messenger app's own
     * notifications, with Facebook's web page (in its own container) for
     * history and new chats. Like Discord it is not a [ChatService]: no
     * bridge, no link, no archive. See docs/messenger-hybrid.md.
     */
    const val MESSENGER = true

    /** The services shown as tabs (and set up from the home screen). */
    fun shownServices(): List<ChatService> = ChatService.entries.filter { it != ChatService.GMAIL || GMAIL }
}
