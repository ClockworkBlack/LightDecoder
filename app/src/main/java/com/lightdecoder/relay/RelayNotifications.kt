package com.lightdecoder.relay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lightdecoder.R

/**
 * The link's notification channels, its ongoing service notification and the
 * "pair again" alert. Message notifications are in [MessageNotifier].
 *
 * Notification text never says more than the state: no names, numbers or
 * message text.
 */
object RelayNotifications {
    const val CHANNEL_STATUS = "relay_status"
    const val CHANNEL_ALERTS = "relay_alerts"
    const val CHANNEL_MESSAGES = "relay_messages"

    const val ID_SERVICE = 0x5245_4c01
    private const val ID_REPAIR = 0x5245_4c02

    /** Creates the channels. Cheap and idempotent; called at app start. */
    fun ensureChannels(context: Context) {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        system.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_STATUS,
                    context.getString(R.string.relay_channel_status),
                    NotificationManager.IMPORTANCE_LOW, // always there while connected, so never noisy
                ).apply {
                    description = context.getString(R.string.relay_channel_status_description)
                    setShowBadge(false)
                },
                NotificationChannel(
                    CHANNEL_ALERTS,
                    context.getString(R.string.relay_channel_alerts),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(R.string.relay_channel_alerts_description) },
                NotificationChannel(
                    CHANNEL_MESSAGES,
                    context.getString(R.string.relay_channel_messages),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = context.getString(R.string.relay_channel_messages_description) },
            )
        )
    }

    /**
     * Whether the link's ongoing notification can actually be seen: the
     * app's notifications are on (on Android 13+ that includes the
     * POST_NOTIFICATIONS permission) and its channel is not turned off. The
     * link must not run without it, since its Disconnect button is the
     * user's way to stop it at any time.
     */
    fun statusVisible(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        ensureChannels(context)
        val system = context.getSystemService(NotificationManager::class.java) ?: return true
        val channel = system.getNotificationChannel(CHANNEL_STATUS) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * The foreground service's notification: what the link is doing, a tap
     * that opens the conversations, and the one-tap Disconnect the product
     * rules require. Android 14+ lets the user swipe it away even so; its
     * delete intent puts it straight back while the service runs.
     */
    fun serviceNotification(context: Context, state: RelayState): Notification {
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context, 0, RelayIntents.conversations(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            context, 1, RelayService.disconnectIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val repost = PendingIntent.getService(
            context, 3, RelayService.repostIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (state.phase) {
            RelayState.Phase.ACTIVE_ELSEWHERE -> R.string.relay_service_text_elsewhere
            RelayState.Phase.PHONE_OFFLINE -> R.string.relay_service_text_phone_offline
            else -> R.string.relay_service_text
        }
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(context.getString(titleFor(state.phase)))
            .setContentText(context.getString(text))
            .setContentIntent(open)
            .addAction(R.drawable.ic_relay_disconnect, context.getString(R.string.relay_action_disconnect), disconnect)
            .setOngoing(true)
            .setDeleteIntent(repost)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun titleFor(phase: RelayState.Phase): Int = when (phase) {
        RelayState.Phase.UNPAIRED -> R.string.relay_state_unpaired
        RelayState.Phase.PAIRING -> R.string.relay_state_pairing
        RelayState.Phase.DISCONNECTED -> R.string.relay_state_disconnected
        RelayState.Phase.CONNECTING -> R.string.relay_state_connecting
        RelayState.Phase.CONNECTED, RelayState.Phase.ACTIVE -> R.string.relay_state_connected
        RelayState.Phase.ACTIVE_ELSEWHERE -> R.string.relay_state_active_elsewhere
        RelayState.Phase.RECONNECTING -> R.string.relay_state_reconnecting
        RelayState.Phase.PHONE_OFFLINE -> R.string.relay_state_phone_offline
        RelayState.Phase.LOGGED_OUT -> R.string.relay_state_logged_out
        RelayState.Phase.COOKIES_INVALID -> R.string.relay_state_cookies_invalid
        RelayState.Phase.ERROR -> R.string.relay_state_error
    }

    /** Re-posts the service notification after a state change (the service owns it while running). */
    @SuppressLint("MissingPermission") // without the permission Android just does not show it
    fun updateService(context: Context, state: RelayState) {
        try {
            NotificationManagerCompat.from(context).notify(ID_SERVICE, serviceNotification(context, state))
        } catch (e: RuntimeException) {
            // A SecurityException on some builds without POST_NOTIFICATIONS.
        }
    }

    /**
     * Tells the user the link stopped for a reason only they can fix: the
     * pairing was removed, or Google stopped accepting the sign-in. Tapping
     * opens setup, where they sign in and confirm the emoji on their phone.
     */
    @SuppressLint("MissingPermission")
    fun showRepairNeeded(context: Context, phase: RelayState.Phase) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannels(context)
        val text = if (phase == RelayState.Phase.COOKIES_INVALID) {
            R.string.relay_repair_text_cookies
        } else {
            R.string.relay_repair_text_logged_out
        }
        val open = PendingIntent.getActivity(
            context, 2, RelayIntents.setup(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(context.getString(R.string.relay_repair_title))
            .setContentText(context.getString(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(text)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        try {
            manager.notify(ID_REPAIR, notification)
        } catch (e: RuntimeException) {
            // As above.
        }
    }

    fun cancelRepairNeeded(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_REPAIR)
    }
}
