package com.lightdecoder.wa

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lightdecoder.chat.ChatService
import com.lightdecoder.relay.RelayNotifications
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the WhatsApp link connected while the app is in the background, and
 * makes that visible: while it runs, its notification (channel
 * [RelayNotifications.CHANNEL_WA_STATUS], id [RelayNotifications.ID_WA_SERVICE])
 * says what the link is doing and has a one-tap Disconnect. Foreground type
 * remoteMessaging on Android 14+, dataSync before that, as declared in the
 * manifest.
 *
 * It is the WhatsApp twin of [com.lightdecoder.relay.RelayService] and
 * follows the same rules. The connection itself is [WaManager]'s. The
 * service is started (through [start]) only while WhatsApp is linked and the
 * user chose to stay connected, by [WaManager.startLive] (Connect, or the
 * end of linking), [com.lightdecoder.relay.BootReceiver] (if "start
 * automatically" is on), [WaManager.onAppVisible] and
 * [WaManager.onNotificationSettingsChanged]. Any other start (Android
 * restoring it after killing the process, say) stops at once unless a link
 * exists and is still wanted. It never runs while its notification cannot
 * be seen ([RelayNotifications.statusVisible] for WhatsApp).
 */
class WaService : Service() {

    private val scope = MainScope()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** startForeground succeeded for this instance. */
    private var inForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = WaManager.get(this)
        manager.serviceRunning = true
        scope.launch {
            combine(manager.state, manager.unpairing) { state, unpairing -> state to unpairing }
                .collect { (state, unpairing) -> RelayNotifications.updateWaService(this@WaService, state, unpairing) }
        }
        watchNetwork(manager)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = WaManager.get(this)
        if (intent?.action == ACTION_DISCONNECT) {
            if (manager.unpairing.value && inForeground) {
                // An unlink is telling WhatsApp, perhaps over a short
                // connection of its own: the link is already off, and the
                // service (with its "Unpairing…" notification) stays until
                // the revoke has ended; the unpair stops it then.
                WaLog.add(this, "Disconnect waits for the unpair to finish")
                return START_NOT_STICKY
            }
            // From the notification's Disconnect button. stopSelf(startId):
            // a foreground start queued behind this one still has to reach
            // startForeground, and stops the service itself after that.
            manager.stopLive()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REPOST) {
            // The user swiped the notification away (Android 14+ allows it).
            // While the link runs it must stay visible, so it goes straight back.
            if (inForeground && manager.isPaired() && WaPrefs.liveEnabled(this) && RelayNotifications.statusVisible(this, ChatService.WHATSAPP)) {
                RelayNotifications.updateWaService(this, manager.state.value)
                return START_STICKY
            }
            if (inForeground && manager.unpairing.value) {
                // Shown until the unpair's revoke has ended (the unpair stops the service then).
                RelayNotifications.updateWaService(this, manager.state.value, unpairing = true)
                return START_NOT_STICKY
            }
            // Not (or no longer) a live foreground service: leave no stray notification.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // Every start (also a restart after the process was killed) has to
        // reach startForeground promptly, even one that stops right away.
        val foreground = goForeground(manager)
        if (intent?.getBooleanExtra(EXTRA_FOREGROUND_START, false) == true) {
            // This start is answered: WaService.stop may stop the service again.
            manager.serviceStartsPending.updateAndGet { (it - 1).coerceAtLeast(0) }
        }
        if (!foreground || !manager.isPaired() || !WaPrefs.liveEnabled(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!RelayNotifications.statusVisible(this, ChatService.WHATSAPP)) {
            // Its notification (with Disconnect) cannot be seen: the link does
            // not run. "Stay connected" stays on, so it comes back once
            // notifications are on again.
            manager.onNotificationsHidden()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        manager.onServiceStarted()
        return START_STICKY
    }

    /** Android 14: the time limit of a shortService (not used here, but answered). */
    override fun onTimeout(startId: Int) {
        WaManager.get(this).onServiceTimeout()
        stopSelf()
    }

    /** Android 15+: the daily limit of a dataSync service was reached. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        WaManager.get(this).onServiceTimeout()
        stopSelf()
    }

    override fun onDestroy() {
        val manager = WaManager.get(this)
        manager.serviceRunning = false
        inForeground = false
        // No service, no connection: whatever stopped it, the link goes down
        // with its notification (unless a new start is already on its way).
        manager.onServiceDestroyed()
        scope.cancel()
        networkCallback?.let { callback ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(RelayNotifications.ID_WA_SERVICE)
        super.onDestroy()
    }

    private fun goForeground(manager: WaManager): Boolean = try {
        val type = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        ServiceCompat.startForeground(
            this, RelayNotifications.ID_WA_SERVICE,
            RelayNotifications.waServiceNotification(this, manager.state.value, manager.unpairing.value), type,
        )
        inForeground = true
        true
    } catch (e: RuntimeException) {
        // ForegroundServiceStartNotAllowedException and friends (Android 12+
        // background limits, or a type the system refuses). Tried again when
        // one of the app's screens is shown.
        manager.resumeOnVisible = true
        WaLog.add(this, "Could not start the link in the foreground: ${e.javaClass.simpleName}")
        false
    }

    /**
     * A link that failed, or whose connection dropped, for lack of network
     * tries again as soon as a network comes back ([WaManager.onNetworkAvailable]).
     */
    private fun watchNetwork(manager: WaManager) {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                manager.onNetworkAvailable()
            }
        }
        try {
            connectivity.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: RuntimeException) {
            // Too many callbacks or no permission: the reconnect timer still works.
        }
    }

    companion object {
        const val ACTION_DISCONNECT = "com.lightdecoder.wa.action.DISCONNECT"

        /** Sent when the user swipes the notification away: show it again. */
        const val ACTION_REPOST = "com.lightdecoder.wa.action.REPOST_NOTIFICATION"

        /** Marks a start made with startForegroundService, which onStartCommand must answer with startForeground. */
        private const val EXTRA_FOREGROUND_START = "com.lightdecoder.wa.extra.FOREGROUND_START"

        /** Starts the service in the foreground. False if Android refused (e.g. started from the background). */
        fun start(context: Context, manager: WaManager = WaManager.get(context)): Boolean {
            val pending = manager.serviceStartsPending
            // Counted and handed to Android in one step as far as stop() can tell.
            val refused = synchronized(manager.serviceStartLock) {
                pending.incrementAndGet()
                try {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, WaService::class.java).putExtra(EXTRA_FOREGROUND_START, true),
                    )
                    null
                } catch (e: IllegalStateException) {
                    // Includes ForegroundServiceStartNotAllowedException (Android 12+).
                    pending.decrementAndGet()
                    e
                } catch (e: SecurityException) {
                    pending.decrementAndGet()
                    e
                }
            } ?: return true
            // The next screen the user opens starts it again.
            manager.resumeOnVisible = true
            WaLog.add(
                context,
                if (refused is SecurityException) {
                    "Android did not allow starting the link: ${refused.javaClass.simpleName}"
                } else {
                    "Android did not allow starting the link now: ${refused.javaClass.simpleName}"
                },
            )
            return false
        }

        /**
         * Stops the service. While a foreground start is still on its way it
         * is left to that start: onStartCommand goes foreground (Android
         * requires it) and then stops, since the link is no longer wanted.
         */
        fun stop(context: Context, manager: WaManager = WaManager.get(context)) {
            // Checked and asked in one step as far as start() can tell (see serviceStartLock).
            synchronized(manager.serviceStartLock) {
                if (manager.serviceStartsPending.get() > 0) return
                context.stopService(Intent(context, WaService::class.java))
            }
        }

        /** The notification's Disconnect button. */
        fun disconnectIntent(context: Context): Intent =
            Intent(context, WaService::class.java).setAction(ACTION_DISCONNECT)

        /** The notification's delete intent (swiped away on Android 14+). */
        fun repostIntent(context: Context): Intent =
            Intent(context, WaService::class.java).setAction(ACTION_REPOST)
    }
}
