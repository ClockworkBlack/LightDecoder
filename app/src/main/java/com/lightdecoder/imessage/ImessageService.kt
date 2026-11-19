package com.lightdecoder.imessage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lightdecoder.R
import com.lightdecoder.ui.imessage.ImessageActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the iMessage session connected while LightDecoder is in the background, as the Google
 * Messages link's service does, so iMessages reach the Messages tab (and its notifications)
 * whether a screen is open or not. Runs only while iMessage is set up ([ImessageEngine.isSetUp]);
 * started after registration, with the app, and after a reboot or update.
 */
class ImessageService : Service() {

    private val scope = MainScope()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        val engine = ImessageEngine.get(this)
        scope.launch { engine.status.collect { update(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(ImessageEngine.get(this).status.value),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0,
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not go to the foreground", e)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!ImessageEngine.isSetUp(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        ImessageEngine.get(this).start()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun update(status: ImessageEngine.Status) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
        } catch (e: SecurityException) {
            // Notifications not allowed: the service runs without the update.
        }
    }

    private fun notification(status: ImessageEngine.Status) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle(getString(R.string.imessage_title))
            .setContentText(
                when (status) {
                    is ImessageEngine.Status.Registered -> getString(R.string.imessage_service_on)
                    is ImessageEngine.Status.Working -> status.step + "…"
                    is ImessageEngine.Status.Failed -> getString(R.string.imessage_service_failed, status.error)
                    else -> getString(R.string.imessage_service_starting)
                },
            )
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, ImessageActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    companion object {
        private const val TAG = "ImessageService"
        const val CHANNEL = "imessage_status"
        private const val NOTIFICATION_ID = 7301

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, context.getString(R.string.imessage_service_channel), NotificationManager.IMPORTANCE_MIN),
                )
            }
        }

        /** Starts the service if iMessage is set up. False if Android refused (a start from the background). */
        fun start(context: Context): Boolean {
            if (!ImessageEngine.isSetUp(context)) return false
            return try {
                ContextCompat.startForegroundService(context, Intent(context, ImessageService::class.java))
                true
            } catch (e: IllegalStateException) {
                Log.w(TAG, "start refused", e)
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ImessageService::class.java))
        }
    }
}
