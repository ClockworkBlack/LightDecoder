package com.lightdecoder.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Reconnects the Google Messages link after the phone restarts (or the app
 * is updated), but only if the user paired, chose to stay connected, and left
 * "start automatically" on. Nothing happens for anyone else.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        if (!RelayPrefs.autoStart(context) || !RelayPrefs.liveEnabled(context)) return
        val manager = RelayManager.get(context)
        if (!manager.isPaired()) return
        if (!RelayNotifications.statusVisible(context)) {
            // The link never runs without its notification; it starts once a
            // screen is shown with notifications back on.
            manager.onNotificationsHidden()
            return
        }
        // start() catches ForegroundServiceStartNotAllowedException: Android
        // allows this start after boot and updates, but some builds do not.
        if (RelayService.start(context)) RelayLog.add(context, "Reconnecting after restart")
    }

    private companion object {
        val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
