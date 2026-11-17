package com.lightdecoder.messenger

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.Executors

/**
 * Builds the Messenger inbox from the Messenger app's own notifications.
 *
 * All through public Android APIs, the ones watches and cars use:
 *  - Android starts this service only after the user turns on notification
 *    access for LightDecoder in system settings, and stops it when they turn
 *    it off.
 *  - Android hands a listener every app's notifications. This one looks at
 *    the package name first and drops everything that is not Messenger's
 *    without reading it.
 *  - What Messenger's notification shows (who, what, when) is stored on the
 *    phone, and its reply button is remembered so a reply can be sent
 *    through it ([MessengerReplies]).
 *
 * Nothing is sent anywhere by this service, and it never dismisses, changes
 * or answers a notification by itself: Messenger's notifications stay exactly
 * as Messenger posted them.
 */
class MessengerNotificationListener : NotificationListenerService() {

    private val worker = Executors.newSingleThreadExecutor()

    override fun onListenerConnected() {
        // Pick up what is showing: messages that arrived while the service
        // was not running, and the reply buttons a restart lost.
        val active = try { activeNotifications } catch (e: Exception) { null }
        active?.forEach { inspect(it) }
        MessengerEvents.notifyChanged()
    }

    override fun onListenerDisconnected() {
        MessengerEvents.notifyChanged()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) inspect(sbn)
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    /** Runs on the main thread: it only reads the notification and hands the storing off. */
    private fun inspect(sbn: StatusBarNotification) {
        if (!MessengerCapture.isMessenger(sbn.packageName)) return
        if (MessengerPrefs.isPaused(this)) return
        val captured = try {
            MessengerCapture.parse(sbn)
        } catch (e: Exception) {
            null
        } ?: return
        val notification = sbn.notification ?: return
        val reply = try { MessengerCapture.replyAction(notification) } catch (e: Exception) { null }
        MessengerReplies.update(captured.key, sbn.key, reply, notification.contentIntent)
        val context = applicationContext
        worker.execute {
            try {
                MessengerStore.get(context).record(captured)
            } catch (e: Exception) {
                // A full disk or a damaged file: this message is not kept. Nothing to tell Messenger.
            }
            MessengerEvents.notifyChanged()
        }
    }

    companion object {

        /** Whether the user has turned notification access on for LightDecoder. */
        fun hasAccess(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                val manager = context.getSystemService(NotificationManager::class.java)
                if (manager != null) {
                    return manager.isNotificationListenerAccessGranted(
                        ComponentName(context, MessengerNotificationListener::class.java)
                    )
                }
            }
            return context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
        }
    }
}
