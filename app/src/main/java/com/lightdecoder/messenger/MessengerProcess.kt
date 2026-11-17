package com.lightdecoder.messenger

import android.app.Application
import android.os.Build
import android.webkit.WebView

/**
 * Puts the Messenger web process into its container ([MessengerContainer])
 * before anything else in it can touch a WebView.
 */
object MessengerProcess {

    /**
     * A WebView data folder per process needs Android 9. Before that every
     * process of an app shares one folder, so the web screen is not offered;
     * the inbox built from notifications works without it.
     */
    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /** True once this process has its own WebView data folder. Only ever true in the container. */
    @Volatile
    var contained: Boolean = false
        private set

    /**
     * Call at the top of [Application.onCreate]. In the Messenger web process
     * it names the WebView data folder and returns true, and the caller must
     * then set up nothing else: no link, no archive, no notification channel.
     * In every other process it does nothing and returns false.
     *
     * If the folder cannot be named (a WebView was somehow created first),
     * [contained] stays false and the web screen refuses to open rather than
     * run on a shared folder.
     */
    fun enterIfMessengerProcess(app: Application): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        if (!MessengerContainer.isMessengerProcess(Application.getProcessName(), app.packageName)) return false
        contained = try {
            WebView.setDataDirectorySuffix(MessengerContainer.DATA_SUFFIX)
            true
        } catch (e: RuntimeException) {
            false
        }
        return true
    }
}
