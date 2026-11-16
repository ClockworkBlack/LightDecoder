package com.lightdecoder.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Android 9+ tells the app when the user turns its notifications, or one of
 * its channels, off or on. The link never runs without its notification
 * (rule: while connected, a notification with Disconnect is always shown), so
 * turning it off takes the link down at once, keeping "stay connected" so it
 * comes back when notifications are turned on again.
 *
 * Both broadcasts are protected (only the system sends them), and the
 * receiver acts only on what [RelayNotifications.statusVisible] reads from
 * the system, never on the intent's extras.
 */
class NotificationBlockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        RelayManager.get(context).onNotificationSettingsChanged()
    }

    private companion object {
        /** NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED and ..._CHANNEL_BLOCK_STATE_CHANGED (API 28). */
        val ACTIONS = setOf(
            "android.app.action.APP_BLOCK_STATE_CHANGED",
            "android.app.action.NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED",
        )
    }
}
