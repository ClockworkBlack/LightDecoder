package com.lightdecoder.messenger

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Hand-off to the official Messenger app, and plain links to the browser. */
object MessengerLinks {

    /**
     * Opens [conversationKey] in the Messenger app if its notification still
     * offers that, else the Messenger app as it opens from the launcher.
     *
     * @return false if Messenger is not installed (or could not be started).
     */
    fun openMessengerApp(context: Context, conversationKey: String? = null): Boolean {
        if (conversationKey != null && MessengerReplies.open(context, conversationKey)) return true
        val launch = context.packageManager.getLaunchIntentForPackage(MessengerCapture.MESSENGER_PACKAGE)
            ?: return false
        return try {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /** Opens a plain web link outside the app. Ignores anything that is not http or https. */
    fun openInBrowser(context: Context, uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return false
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
