package com.lightdecoder.discord

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Hand-off to the official Discord app (option 1), used as the Discord tab's fallback. */
object DiscordLinks {

    private const val DISCORD_PACKAGE = "com.discord"

    /**
     * Opens the conversation at [webUrl] in the installed Discord app. Falls back
     * to Discord's home when [webUrl] is not a conversation, and to whatever app
     * handles the link (usually the browser) when Discord is not installed.
     *
     * @return false only if nothing on the device could open the link.
     */
    fun openInDiscordApp(context: Context, webUrl: String?): Boolean {
        val target = Uri.parse(DiscordSession.channelLinkOrNull(webUrl) ?: DiscordSession.HOME_LINK)

        val appIntent = Intent(Intent.ACTION_VIEW, target)
            .setPackage(DISCORD_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(appIntent)
            return true
        } catch (e: ActivityNotFoundException) {
            // Discord is not installed; let the system pick a handler.
        }

        return openInBrowser(context, target)
    }

    /** Opens a plain web link outside the app. Ignores anything that is not http or https. */
    fun openInBrowser(context: Context, uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return false
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
