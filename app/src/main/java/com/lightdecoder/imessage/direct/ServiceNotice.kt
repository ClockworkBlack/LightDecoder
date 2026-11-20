package com.lightdecoder.imessage.direct

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lightdecoder.R
import com.lightdecoder.ui.imessage.DirectSetupActivity

/**
 * The notification [DirectService] runs under in the foreground: a quiet "iMessage is on", never
 * mistaken for a message.
 *
 * - Its own channel ([CHANNEL], created at [NotificationManager.IMPORTANCE_MIN], so no status-bar
 *   icon, sound, vibration, light, badge or lock-screen line). Android never lowers a channel's
 *   importance once created, so it is a new channel; the earlier ones ([OLD_CHANNELS]) are deleted.
 * - Its own group ([GROUP]), so Android never bundles it with LightDecoder's message notifications:
 *   once an app shows four notifications or more without a group, Android puts them under one
 *   summary, and on Samsung phones the bundle showed this notice's text with the count ("4")
 *   beside it, as if four messages had come.
 * - Posted again only when what it says changes ([Poster]).
 *
 * The user may swipe it away (Android 13+) or turn its channel off ([channelSettingsIntent]):
 * iMessage keeps running either way.
 */
object ServiceNotice {
    /** The channel since 0.5.69. */
    const val CHANNEL = "imessage_service_quiet"

    /** Channels of earlier versions, deleted (a channel's importance cannot be lowered). */
    val OLD_CHANNELS = listOf("imessage_status")

    const val GROUP = "com.lightdecoder.imessage.service"
    const val NOTIFICATION_ID = 7301

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        for (old in OLD_CHANNELS) {
            try {
                if (nm.getNotificationChannel(old) != null) nm.deleteNotificationChannel(old)
            } catch (e: RuntimeException) {
                // Still holding a foreground service's notification: deleted next time.
            }
        }
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.imessage_service_quiet_channel), NotificationManager.IMPORTANCE_MIN).apply {
                description = context.getString(R.string.imessage_service_quiet_channel_description)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            },
        )
    }

    /** What the notice says: a title, and a line under it. */
    data class Content(val title: String, val text: String)

    fun content(context: Context, status: DirectEngine.Status): Content = when (status) {
        is DirectEngine.Status.Registered ->
            Content(context.getString(R.string.imessage_service_on_title), context.getString(R.string.imessage_service_on_body))
        is DirectEngine.Status.Working -> Content(context.getString(R.string.imessage_title), status.step + "…")
        is DirectEngine.Status.Retrying ->
            Content(context.getString(R.string.imessage_title), context.getString(R.string.imessage_service_retrying, status.error))
        is DirectEngine.Status.Failed ->
            Content(context.getString(R.string.imessage_title), context.getString(R.string.imessage_service_failed, status.error))
        else -> Content(context.getString(R.string.imessage_title), context.getString(R.string.imessage_service_starting))
    }

    fun build(context: Context, content: Content): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .setNumber(0)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setGroup(GROUP)
            // Android may wait a few seconds before showing it; usually it never needs to.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, Intent(context, DirectSetupActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
    }

    /**
     * Posts the notice only when what it says changes: the engine's status comes again with
     * nothing new (the same registration read again, a step repeated), and every post would bring
     * the notice back up the shade.
     */
    class Poster(private val context: Context) {
        private var shown: Content? = null

        /** The notice for [status], for startForeground; remembered as shown. */
        fun forForeground(status: DirectEngine.Status): Notification {
            val content = content(context, status)
            shown = content
            return build(context, content)
        }

        /** Posts the notice for [status] if it says something else than the one shown. True if posted. */
        @android.annotation.SuppressLint("MissingPermission") // without the permission Android just does not show it
        fun update(status: DirectEngine.Status): Boolean {
            val content = content(context, status)
            if (content == shown) return false
            shown = content
            try {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, build(context, content))
            } catch (e: RuntimeException) {
                // Notifications not allowed.
            }
            return true
        }
    }

    /**
     * The system screen of the notice's channel, where the user can turn it off: the service keeps
     * running without a notice to see. The app's notification settings when all its notifications
     * are off already.
     */
    fun channelSettingsIntent(context: Context): Intent {
        ensureChannel(context)
        return if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
    }
}
