package com.lightdecoder.messenger

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.IMessagePrefs
import com.lightdecoder.imessage.OpenBubbles
import java.util.concurrent.Executors

/**
 * Builds the Messenger inbox from the Messenger app's own notifications, and,
 * with "Blue Bubbles" on, the iMessage chats of the Messages tab from the
 * OpenBubbles app's ([com.lightdecoder.imessage.IMessageManager]). One
 * listener for both, so notification access is granted once. Its class name
 * stays: Android files the user's grant under it.
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
        running = this
        // Pick up what is showing: messages that arrived while the service
        // was not running, and the reply buttons a restart lost.
        val active = try { activeNotifications } catch (e: Exception) { null }
        if (IMessagePrefs.enabled(this)) {
            val showing = active?.count { OpenBubbles.isOpenBubbles(it.packageName) } ?: -1
            com.lightdecoder.imessage.IMessageLog.add(this, "Notification reader connected; OpenBubbles notifications showing now: $showing")
        }
        active?.forEach { inspect(it, live = false) }
        MessengerEvents.notifyChanged()
        refreshIMessage()
    }

    override fun onListenerDisconnected() {
        if (running === this) running = null
        if (IMessagePrefs.enabled(this)) com.lightdecoder.imessage.IMessageLog.add(this, "Notification reader disconnected (Android stopped it; iMessages are missed until it is back)")
        MessengerEvents.notifyChanged()
        refreshIMessage()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) inspect(sbn, live = true)
    }

    private fun refreshIMessage() {
        if (IMessagePrefs.enabled(this)) IMessageManager.get(this).refreshState()
    }

    override fun onDestroy() {
        if (running === this) running = null
        worker.shutdown()
        super.onDestroy()
    }

    /**
     * Runs on the main thread: it only reads the notification and hands the
     * storing off. [live] is false for the ones already showing when the
     * listener connected.
     */
    private fun inspect(sbn: StatusBarNotification, live: Boolean) {
        if (OpenBubbles.isOpenBubbles(sbn.packageName)) {
            if (!IMessagePrefs.enabled(this)) return
            val context = applicationContext
            worker.execute {
                try {
                    IMessageManager.get(context).onNotification(sbn, live)
                } catch (e: Exception) {
                    // A full disk or a damaged file: this message is not kept.
                }
            }
            return
        }
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

    /** Reads OpenBubbles' notifications that are showing now (Blue Bubbles was just turned on). */
    private fun scanIMessage() {
        val active = try { activeNotifications } catch (e: Exception) { null } ?: return
        active.filter { OpenBubbles.isOpenBubbles(it.packageName) }.forEach { inspect(it, live = false) }
    }

    companion object {

        /** The listener while Android has it connected. */
        @Volatile
        private var running: MessengerNotificationListener? = null

        /**
         * Reads the OpenBubbles notifications showing now, if the listener is
         * connected (if not, it reads them when it connects).
         */
        fun requestIMessageScan() {
            val listener = running ?: return
            android.os.Handler(android.os.Looper.getMainLooper()).post { if (running === listener) listener.scanIMessage() }
        }

        /**
         * Opens the system setting where notification access is turned on
         * for LightDecoder: its own page where the phone has one, else the
         * list of all apps. False if neither could be opened.
         */
        fun openAccessSettings(activity: android.app.Activity): Boolean {
            val component = ComponentName(activity, MessengerNotificationListener::class.java)
            val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
            } else {
                null
            }
            for (intent in listOfNotNull(detail, android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) {
                try {
                    activity.startActivity(intent)
                    return true
                } catch (e: android.content.ActivityNotFoundException) {
                    // Try the next one.
                } catch (e: SecurityException) {
                    // Some phones guard the per-app screen; the list of all listeners is open.
                }
            }
            return false
        }

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
