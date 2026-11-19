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
import org.json.JSONObject

/**
 * The ":imessage" process's service: keeps the iMessage session connected (in the foreground,
 * like the Google Messages link's service) so iMessages reach the Messages tab whether a screen is
 * open or not, answers the main process's requests ([ImessageWire.SEND], [ImessageWire.UNSEND],
 * [ImessageWire.REACH]), and hands what Apple sends to the main process's [ImessageSinkService].
 */
class ImessageService : Service() {

    private val scope = MainScope()
    private lateinit var engine: ImessageEngine
    private lateinit var sinkClient: ImessageIpc.Client

    private val server = ImessageIpc.Server { what, request ->
        when (what) {
            ImessageWire.SEND -> {
                val (guid, ts) = engine.send(
                    ImessageWire.Target.fromJson(request.getJSONObject("target")),
                    request.getString("text"),
                    request.optString("replyGuid").takeIf { !request.isNull("replyGuid") && it.isNotEmpty() },
                )
                ImessageWire.sent(guid, ts)
            }
            ImessageWire.UNSEND -> {
                engine.unsend(
                    ImessageWire.Target.fromJson(request.getJSONObject("target")),
                    request.getString("guid"),
                    request.optInt("part", 0),
                )
                ImessageWire.ok()
            }
            ImessageWire.REACH -> {
                val conversation = engine.reach(request.getString("recipient"))
                if (conversation != null) JSONObject().put("conversation", conversation.toJson()) else JSONObject()
            }
            else -> ImessageWire.error("Unknown request $what")
        }
    }

    override fun onBind(intent: Intent?): IBinder = server.messenger.binder

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        engine = ImessageEngine.get(this)
        sinkClient = ImessageIpc.Client(this, ImessageSinkService::class.java)
        engine.sink = ImessageEngine.Sink { update ->
            val reply = sinkClient.call(ImessageWire.UPDATE, ImessageWire.encode(update), SINK_TIMEOUT_MS)
            ImessageWire.errorOf(reply) == null
        }
        scope.launch { engine.status.collect { update(it) } }
        // Bound by the main process for a send or unsend, even before a start command.
        engine.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(engine.status.value),
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
        engine.start()
        return START_STICKY
    }

    override fun onDestroy() {
        engine.sink = null
        sinkClient.close()
        server.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun update(status: ImessageEngine.Status) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
        } catch (e: SecurityException) {
            // Notifications not allowed.
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
        private const val SINK_TIMEOUT_MS = 30_000L

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, context.getString(R.string.imessage_service_channel), NotificationManager.IMPORTANCE_MIN),
                )
            }
        }

        /** Starts the service if iMessage is set up (from either process). False if Android refused. */
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
