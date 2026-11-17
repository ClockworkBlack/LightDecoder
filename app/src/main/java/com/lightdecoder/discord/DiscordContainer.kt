package com.lightdecoder.discord

/**
 * The names of the Discord tab's container: its own process, and its own
 * WebView data folder inside the app's private storage.
 *
 * Why it has one. The other tabs keep their sessions, archives and media in
 * files of their own, and none of them leaves a WebView's data behind. The
 * Discord tab is a web page, and a web page's cookies and site storage live in
 * the WebView's data folder, which is one per process. Left in the main
 * process, Discord's sign-in would share that folder with the Google sign-in
 * page of the Messages link, and [com.lightdecoder.ui.relay.SignInWebData]
 * deletes the whole folder after every sign-in. So the Discord tab runs in a
 * process of its own, with a data folder of its own, and the main process's
 * runtimes (the Messages and WhatsApp links, the archives) are never created
 * there. See docs/discord-web-view.md.
 */
object DiscordContainer {

    /** The process the Discord screen runs in. Must match android:process in the manifest. */
    const val PROCESS_SUFFIX = ":discord"

    /**
     * What Android appends to the WebView data folder's name in that process:
     * app_webview_discord, beside the main process's app_webview.
     */
    const val DATA_SUFFIX = "discord"

    /** Whether [processName] is the Discord container of the app [packageName]. */
    fun isDiscordProcess(processName: String?, packageName: String): Boolean =
        processName == packageName + PROCESS_SUFFIX
}
