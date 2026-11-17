package com.lightdecoder.discord

import android.app.Application
import android.os.Build
import android.webkit.WebView

/**
 * Puts the Discord process into its container ([DiscordContainer]) before
 * anything else in it can touch a WebView.
 */
object DiscordProcess {

    /**
     * A WebView data folder per process needs Android 9. Before that every
     * process of an app shares one folder, so the tab is not offered at all.
     */
    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /** True once this process has its own WebView data folder. Only ever true in the Discord process. */
    @Volatile
    var contained: Boolean = false
        private set

    /**
     * Call first thing in [Application.onCreate]. In the Discord process it
     * names the WebView data folder and returns true, and the caller must then
     * set up nothing else: no link, no archive, no notification channel. In
     * every other process it does nothing and returns false.
     *
     * If the folder cannot be named (a WebView was somehow created first),
     * [contained] stays false and the Discord screen refuses to open, rather
     * than run on the shared folder.
     */
    fun enterIfDiscordProcess(app: Application): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        if (!DiscordContainer.isDiscordProcess(Application.getProcessName(), app.packageName)) return false
        contained = try {
            WebView.setDataDirectorySuffix(DiscordContainer.DATA_SUFFIX)
            true
        } catch (e: RuntimeException) {
            false
        }
        return true
    }
}
