package com.lightdecoder.messenger

/**
 * The names of the container that Facebook's web messaging page runs in: its
 * own process, and its own WebView data folder inside the app's private
 * storage.
 *
 * Only the web page ("Full history / New chat") lives there. The Messenger
 * inbox built from notifications is ordinary app data in the main process and
 * never meets the page: the page cannot read the inbox, and the inbox code
 * never reads the page, its cookies or its storage.
 *
 * Why a container: a web page's cookies and site storage live in the WebView's
 * data folder, which Android keeps one of per process. The main process's
 * folder is wiped after every Google sign-in of the Messages link
 * ([com.lightdecoder.ui.relay.SignInWebData]), and the Discord tab has a
 * folder of its own. Facebook's sign-in gets a third, so none of the three
 * can see or clear another's. See docs/messenger-hybrid.md.
 */
object MessengerContainer {

    /** The process the web screen runs in. Must match android:process in the manifest. */
    const val PROCESS_SUFFIX = ":messenger"

    /** What Android appends to the WebView data folder's name there: app_webview_messenger. */
    const val DATA_SUFFIX = "messenger"

    /** Whether [processName] is the Messenger web container of the app [packageName]. */
    fun isMessengerProcess(processName: String?, packageName: String): Boolean =
        processName == packageName + PROCESS_SUFFIX
}
