package com.lightdecoder.discord

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.webkit.WebSettings
import java.io.File

/**
 * Gets the Discord tab's process and its web engine ready while the
 * conversation list is on screen, so the tab opens without that wait. It
 * loads the engine only: no page, no connection to Discord, nothing sent.
 * Only for someone who has opened the tab before ([markUsed]). Runs in the
 * ":discord" process, like the tab.
 */
class DiscordWarmup : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DiscordProcess.contained) {
            // Loading the web engine is most of the wait on first opening; this does just that.
            runCatching { WebSettings.getDefaultUserAgent(this) }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val USED = "discord_tab_used"

        /** The tab has been opened on this phone: warming up for it is worth it. */
        fun markUsed(context: Context) {
            runCatching { File(context.filesDir, USED).createNewFile() }
        }

        fun request(context: Context) {
            if (!Features_DISCORD || !DiscordProcess.supported) return
            if (!File(context.filesDir, USED).exists()) return
            // Allowed: the app is on screen when this is asked for.
            runCatching { context.startService(Intent(context, DiscordWarmup::class.java)) }
        }

        private val Features_DISCORD get() = com.lightdecoder.chat.Features.DISCORD
    }
}
