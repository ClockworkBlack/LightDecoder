package com.lightdecoder.relay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lightdecoder.R
import com.lightdecoder.chat.ChatService
import com.lightdecoder.wa.WaService

/**
 * The links' notification channels, each link's ongoing service
 * notification, and its notice that only the user can bring the link back
 * ("pair again", "link again"). Message notifications are in
 * [MessageNotifier].
 *
 * Each [ChatService] has channels of its own ([statusChannel],
 * [messagesChannel]) and its own id for its foreground service's
 * notification ([serviceNotificationId]), so the user can silence, and each
 * link can show, one service without the other. Where a function takes the
 * service it defaults to Google Messages, which was all there was.
 *
 * Notification text never says more than the state: no names, numbers or
 * message text.
 */
object RelayNotifications {
    const val CHANNEL_STATUS = "relay_status"
    const val CHANNEL_ALERTS = "relay_alerts"
    const val CHANNEL_MESSAGES = "relay_messages"

    /** WhatsApp's "WhatsApp link" channel: its foreground service's notification, with Disconnect. */
    const val CHANNEL_WA_STATUS = "wa_status"

    /** WhatsApp's "WhatsApp messages" channel. */
    const val CHANNEL_WA_MESSAGES = "wa_messages"

    const val ID_SERVICE = 0x5245_4c01
    private const val ID_REPAIR = 0x5245_4c02

    /** The notification of WhatsApp's foreground service. */
    const val ID_WA_SERVICE = 0x5245_4c21

    /** Gmail has no foreground service; an id is still reserved so the services never share one. */
    const val ID_MAIL_UNUSED = 0x5245_4c22

    /** New Gmail messages. */
    const val CHANNEL_MAIL_MESSAGES = "mail_messages"

    /** iMessage ("Blue Bubbles"): new messages only; there is no connection of its own to show. */
    const val CHANNEL_IMESSAGE_MESSAGES = "imessage_messages"

    /** iMessage runs no foreground service: never posted. */
    const val ID_IMESSAGE_UNUSED = 0x5245_4c23
    private const val ID_WA_RELINK = 0x5245_4c22

    /**
     * Request codes of the WhatsApp notifications' pending intents. Android
     * tells pending intents apart by request code, action, component and
     * data, never by extras, and WhatsApp's "open the conversations" intent
     * differs from Google Messages' only in an extra: with the same request
     * code each notification would open the other service's list.
     */
    private const val REQUEST_WA_OPEN = 10
    private const val REQUEST_WA_DISCONNECT = 11
    private const val REQUEST_WA_RELINK = 12
    private const val REQUEST_WA_REPOST = 13

    /** The channel of [service]'s ongoing "connected" notification, the one with Disconnect. */
    fun statusChannel(service: ChatService): String = when (service) {
        ChatService.MESSAGES -> CHANNEL_STATUS
        ChatService.WHATSAPP -> CHANNEL_WA_STATUS
        // Gmail keeps no connection open, so it has no ongoing notification: its messages channel stands in.
        ChatService.GMAIL -> CHANNEL_MAIL_MESSAGES
        // Nor does iMessage (OpenBubbles keeps the connection).
        ChatService.IMESSAGE -> CHANNEL_IMESSAGE_MESSAGES
    }

    /** The channel of [service]'s new-message notifications. */
    fun messagesChannel(service: ChatService): String = when (service) {
        ChatService.MESSAGES -> CHANNEL_MESSAGES
        ChatService.WHATSAPP -> CHANNEL_WA_MESSAGES
        ChatService.GMAIL -> CHANNEL_MAIL_MESSAGES
        ChatService.IMESSAGE -> CHANNEL_IMESSAGE_MESSAGES
    }

    /** The id of the notification [service]'s foreground service runs under. */
    fun serviceNotificationId(service: ChatService): Int = when (service) {
        ChatService.MESSAGES -> ID_SERVICE
        ChatService.WHATSAPP -> ID_WA_SERVICE
        ChatService.GMAIL -> ID_MAIL_UNUSED
        ChatService.IMESSAGE -> ID_IMESSAGE_UNUSED
    }

    /**
     * Creates [service]'s channels. Cheap and idempotent. Google Messages'
     * are created at app start; WhatsApp's the first time anything of
     * WhatsApp needs them, so that someone who never links WhatsApp does not
     * find its channels in the system settings.
     */
    fun ensureChannels(context: Context, service: ChatService = ChatService.MESSAGES) {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        system.createNotificationChannels(
            when (service) {
                ChatService.MESSAGES -> messagesChannels(context)
                ChatService.WHATSAPP -> whatsAppChannels(context)
                ChatService.GMAIL -> mailChannels(context)
                ChatService.IMESSAGE -> iMessageChannels(context)
            }
        )
    }

    private fun messagesChannels(context: Context): List<NotificationChannel> = listOf(
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

    private fun mailChannels(context: Context): List<NotificationChannel> = listOf(
        NotificationChannel(
            CHANNEL_MAIL_MESSAGES,
            context.getString(R.string.mail_channel_messages),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.mail_channel_messages_description) },
    )

    /** Through the iMessage library its messages do not come from OpenBubbles, and the description says so. */
    private fun iMessageChannels(context: Context): List<NotificationChannel> = listOf(
        NotificationChannel(
            CHANNEL_IMESSAGE_MESSAGES,
            context.getString(R.string.imessage_channel_messages),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(
                if (com.lightdecoder.imessage.direct.DirectEngine.isSetUp(context)) R.string.imessage_channel_messages_description_direct
                else R.string.imessage_channel_messages_description,
            )
        },
    )

    private fun whatsAppChannels(context: Context): List<NotificationChannel> = listOf(
        NotificationChannel(
            CHANNEL_WA_STATUS,
            context.getString(R.string.wa_channel_status),
            NotificationManager.IMPORTANCE_LOW, // always there while connected, so never noisy
        ).apply {
            description = context.getString(R.string.wa_channel_status_description)
            setShowBadge(false)
        },
        NotificationChannel(
            CHANNEL_WA_MESSAGES,
            context.getString(R.string.wa_channel_messages),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.wa_channel_messages_description) },
    )

    /**
     * Whether [service]'s ongoing notification can actually be seen: the
     * app's notifications are on (on Android 13+ that includes the
     * POST_NOTIFICATIONS permission) and its channel ([statusChannel]) is
     * not turned off. A link must not run without it, since its Disconnect
     * button is the user's way to stop it at any time.
     */
    fun statusVisible(context: Context, service: ChatService = ChatService.MESSAGES): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        ensureChannels(context, service)
        val system = context.getSystemService(NotificationManager::class.java) ?: return true
        val channel = system.getNotificationChannel(statusChannel(service)) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * Whether the app is exempt from battery optimisation (Doze): then the
     * link's connection and its reconnect alarms are not held back while the
     * phone is idle. False when it is not exempt, or Android cannot say. The
     * status screen offers [RelayIntents.batteryExemption] while it is false.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean = try {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    } catch (e: RuntimeException) {
        false
    }

    /**
     * The Google Messages foreground service's notification: what the link
     * is doing, a tap that opens the conversations, and the one-tap
     * Disconnect the product rules require. Android 14+ lets the user swipe it away even so; its
     * delete intent puts it straight back while the service runs.
     *
     * While an unpair runs ([unpairing]) it says "Unpairing…": the service
     * stays (and Disconnect waits) until the revoke, which may need a short
     * connection of its own, has ended.
     */
    fun serviceNotification(context: Context, state: RelayState, unpairing: Boolean = false): Notification {
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
        val text = when {
            unpairing -> R.string.relay_service_text_unpairing
            state.phase == RelayState.Phase.ACTIVE_ELSEWHERE -> R.string.relay_service_text_elsewhere
            state.phase == RelayState.Phase.PHONE_OFFLINE -> R.string.relay_service_text_phone_offline
            else -> R.string.relay_service_text
        }
        val title = if (unpairing) R.string.relay_service_title_unpairing else titleFor(state.phase)
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(context.getString(title))
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
    fun updateService(context: Context, state: RelayState, unpairing: Boolean = false) {
        try {
            NotificationManagerCompat.from(context).notify(ID_SERVICE, serviceNotification(context, state, unpairing))
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

    // ============================== WhatsApp ==============================

    /**
     * The WhatsApp foreground service's notification, as [serviceNotification]
     * is the Google Messages one: what the link is doing, a tap that opens
     * WhatsApp's conversations, the one-tap Disconnect, and a delete intent
     * that puts it back if it is swiped away (Android 14+).
     */
    fun waServiceNotification(context: Context, state: RelayState, unpairing: Boolean = false): Notification {
        ensureChannels(context, ChatService.WHATSAPP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(
            context, REQUEST_WA_OPEN, RelayIntents.conversations(context, ChatService.WHATSAPP), flags,
        )
        val disconnect = PendingIntent.getService(context, REQUEST_WA_DISCONNECT, WaService.disconnectIntent(context), flags)
        val repost = PendingIntent.getService(context, REQUEST_WA_REPOST, WaService.repostIntent(context), flags)
        val title = if (unpairing) R.string.wa_service_title_unpairing else waTitleFor(state.phase)
        val text = if (unpairing) R.string.wa_service_text_unpairing else R.string.wa_service_text
        return NotificationCompat.Builder(context, CHANNEL_WA_STATUS)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(context.getString(title))
            .setContentText(context.getString(text))
            .setContentIntent(open)
            .addAction(R.drawable.ic_relay_disconnect, context.getString(R.string.wa_action_disconnect), disconnect)
            .setOngoing(true)
            .setDeleteIntent(repost)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** The title of WhatsApp's service notification (and a short name for the state anywhere else). */
    fun waTitleFor(phase: RelayState.Phase): Int = when (phase) {
        RelayState.Phase.UNPAIRED -> R.string.wa_state_unpaired
        RelayState.Phase.PAIRING -> R.string.wa_state_pairing
        RelayState.Phase.DISCONNECTED -> R.string.wa_state_disconnected
        RelayState.Phase.CONNECTING -> R.string.wa_state_connecting
        // WhatsApp has no "active elsewhere" or "phone offline"; they are named for completeness.
        RelayState.Phase.CONNECTED, RelayState.Phase.ACTIVE, RelayState.Phase.ACTIVE_ELSEWHERE -> R.string.wa_state_connected
        RelayState.Phase.RECONNECTING, RelayState.Phase.PHONE_OFFLINE -> R.string.wa_state_reconnecting
        RelayState.Phase.LOGGED_OUT, RelayState.Phase.COOKIES_INVALID -> R.string.wa_state_logged_out
        RelayState.Phase.ERROR -> R.string.wa_state_error
    }

    /** Re-posts WhatsApp's service notification after a state change (the service owns it while running). */
    @SuppressLint("MissingPermission") // without the permission Android just does not show it
    fun updateWaService(context: Context, state: RelayState, unpairing: Boolean = false) {
        try {
            NotificationManagerCompat.from(context).notify(ID_WA_SERVICE, waServiceNotification(context, state, unpairing))
        } catch (e: RuntimeException) {
            // A SecurityException on some builds without POST_NOTIFICATIONS.
        }
    }

    /**
     * Tells the user the WhatsApp link ended for a reason only they can
     * fix: the device was removed on the phone, or by WhatsApp. Tapping
     * opens the setup screen, where they link again themselves. On
     * WhatsApp's own status channel (WhatsApp has no alerts channel), so it
     * is quiet; the conversation list says the same.
     */
    @SuppressLint("MissingPermission")
    fun showWaRelinkNeeded(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannels(context, ChatService.WHATSAPP)
        val open = PendingIntent.getActivity(
            context, REQUEST_WA_RELINK, RelayIntents.waSetup(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.getString(R.string.wa_relink_text)
        val notification = NotificationCompat.Builder(context, CHANNEL_WA_STATUS)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(context.getString(R.string.wa_relink_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        try {
            manager.notify(ID_WA_RELINK, notification)
        } catch (e: RuntimeException) {
            // As above.
        }
    }

    fun cancelWaRelinkNeeded(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_WA_RELINK)
    }
}
