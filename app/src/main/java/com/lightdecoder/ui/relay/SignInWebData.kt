package com.lightdecoder.ui.relay

import android.content.Context
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.annotation.VisibleForTesting
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The sign-in WebView's data: Google account cookies (a full session for that
 * account) and web storage, which Chromium keeps unencrypted in the app's
 * data folder. They are wiped as soon as the sign-in ends; this makes sure
 * that also happens when it never properly ends (the process was killed
 * mid-sign-in, or the user left and never came back).
 *
 * A marker file says "a sign-in WebView may have left data behind". It is
 * created before a WebView exists and removed only by a wipe that ran with no
 * sign-in WebView alive. The first screen of every process ([wipeIfDirty])
 * wipes whatever a marker says is left.
 *
 * Main thread only, like WebView itself.
 */
internal object SignInWebData {

    private const val MARKER = "signin-webview-dirty"

    /** Sign-in WebViews alive in this process. */
    private var live = 0

    /** Whether this process already looked for leftovers. */
    private val checked = AtomicBoolean(false)

    private fun marker(context: Context) = File(context.noBackupFilesDir, MARKER)

    /** Whether a sign-in WebView may have left data behind. */
    fun isDirty(context: Context): Boolean = marker(context).exists()

    /** Call right before creating the sign-in WebView, before any cookie can be set. */
    fun webViewCreating(context: Context) {
        runCatching { marker(context).createNewFile() }
    }

    fun webViewCreated() {
        live++
    }

    fun webViewDestroyed() {
        if (live > 0) live--
    }

    /**
     * Wipes every cookie and all web storage of the app's WebViews, then runs
     * [then]. The marker goes only if no sign-in WebView is alive by the time
     * the cookies are gone. Without a usable WebView (missing, disabled or
     * being updated) there is nothing to wipe through it; [then] still runs.
     */
    fun wipe(context: Context, then: (() -> Unit)? = null) {
        val app = context.applicationContext
        val cookies = try {
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance()
        } catch (e: RuntimeException) {
            // No WebView provider: nothing can have been stored through one in this process.
            then?.invoke()
            return
        }
        cookies.removeAllCookies {
            cookies.flush()
            if (live == 0) marker(app).delete()
            then?.invoke()
        }
    }

    /**
     * Once per process, from the first screen's onCreate (before any WebView
     * can exist in this process): if an earlier process left sign-in data,
     * delete it. With no WebView loaded yet the files are simply removed,
     * which also drops deleted cookie rows SQLite may keep in free pages, and
     * does not load Chromium at all; if that fails, the WebView API wipes.
     */
    fun wipeIfDirty(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        if (!checked.compareAndSet(false, true)) return
        val app = context.applicationContext
        if (!isDirty(app) || live > 0) return
        if (deleteFiles(app)) {
            marker(app).delete()
        } else {
            wipe(app)
        }
    }

    /** Forgets that this process already checked, and any live WebView count. */
    @VisibleForTesting
    internal fun resetForTesting() {
        checked.set(false)
        live = 0
    }

    /** Removes the WebView's data folders; true if no cookie store is left. */
    private fun deleteFiles(context: Context): Boolean {
        val data = context.dataDir
        val folders = listOf(
            File(data, "app_webview"),
            File(context.cacheDir, "WebView"),
            File(context.cacheDir, "org.chromium.android_webview"),
        )
        folders.forEach { runCatching { it.deleteRecursively() } }
        return folders.none { it.exists() }
    }
}
