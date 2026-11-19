package com.lightdecoder.imessage.direct

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
import com.lightdecoder.ui.imessage.DirectSetupActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The ":imessage" process's service: keeps the iMessage session connected (in the foreground,
 * like the Google Messages link's service) so iMessages reach the Messages tab whether a screen is
 * open or not, answers the main process's requests ([DirectWire]: send, unsend, reach, edit,
 * react, typing, read, download, an iMessage app's update, a chat's background, Send Later taken back), and hands what Apple sends to the main process's
 * [DirectSinkService].
 */
class DirectService : Service() {

    private val scope = MainScope()
    private lateinit var engine: DirectEngine
    private lateinit var sinkClient: DirectIpc.Client

    private val server = DirectIpc.Server { what, request ->
        when (what) {
            DirectWire.SEND -> {
                val r = DirectWire.SendRequest.fromJson(request)
                engine.send(r.target, r.text, r.replyGuid, r.replyPart, r.effect, r.attachments, r.voice, r.app, r.runs, r.scheduledMs).toJson()
            }
            DirectWire.EXTENSION -> {
                val r = DirectWire.ExtensionRequest.fromJson(request)
                engine.extension(r.target, r.rootGuid, r.isMeta, r.app, r.imagePath).toJson()
            }
            DirectWire.UNSEND -> {
                engine.unsend(target(request), request.getString("guid"), request.optInt("part", 0))
                DirectWire.ok()
            }
            DirectWire.REACH -> {
                val conversation = engine.reach(request.getString("recipient"))
                if (conversation != null) JSONObject().put("conversation", conversation.toJson()) else JSONObject()
            }
            DirectWire.EDIT -> {
                val r = DirectWire.EditRequest.fromJson(request)
                engine.edit(r.target, r.guid, r.part, r.text, r.runs)
                DirectWire.ok()
            }
            DirectWire.REACT -> DirectWire.guid(
                engine.react(
                    target(request), request.getString("guid"), request.optInt("part", 0), request.getString("emoji"),
                    request.optBoolean("enable", true), request.optString("targetText"),
                ),
            )
            DirectWire.TYPING -> {
                engine.typing(target(request), request.optBoolean("typing"))
                DirectWire.ok()
            }
            DirectWire.READ -> {
                engine.read(target(request), request.getString("guid"))
                DirectWire.ok()
            }
            DirectWire.DOWNLOAD -> DirectWire.downloaded(engine.download(request.getString("descriptor"), request.getString("path")))
            DirectWire.BACKGROUND -> {
                val r = DirectWire.BackgroundRequest.fromJson(request)
                // Only a picture the main process put where backgrounds are kept.
                val path = r.path?.takeIf { ChatBackgrounds.isOurs(this, java.io.File(it)) }
                if (r.path != null && path == null) throw DirectEngine.ImessageError(getString(R.string.imessage_background_unreadable))
                engine.setBackground(r.target, path, r.version)
                DirectWire.ok()
            }
            DirectWire.UNSCHEDULE -> {
                engine.unschedule(target(request), request.getString("guid"))
                DirectWire.ok()
            }
            else -> DirectWire.error("Unknown request $what")
        }
    }

    private fun target(request: JSONObject) = DirectWire.Target.fromJson(request.getJSONObject("target"))

    override fun onBind(intent: Intent?): IBinder = server.messenger.binder

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        engine = DirectEngine.get(this)
        sinkClient = DirectIpc.Client(this, DirectSinkService::class.java)
        engine.sink = DirectEngine.Sink { update ->
            val reply = sinkClient.call(DirectWire.UPDATE, DirectWire.encodeUpdate(update), SINK_TIMEOUT_MS)
            Delivery.of(reply)
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
        if (!DirectEngine.isSetUp(this)) {
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

    private fun update(status: DirectEngine.Status) {
        // For the main process's logs: it cannot ask the engine without starting it.
        DirectPrefs.setLastStatus(this, DirectEngine.describe(status))
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
        } catch (e: SecurityException) {
            // Notifications not allowed.
        }
    }

    private fun notification(status: DirectEngine.Status) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle(getString(R.string.imessage_title))
            .setContentText(
                when (status) {
                    is DirectEngine.Status.Registered -> getString(R.string.imessage_service_on)
                    is DirectEngine.Status.Working -> status.step + "…"
                    is DirectEngine.Status.Retrying -> getString(R.string.imessage_service_retrying, status.error)
                    is DirectEngine.Status.Failed -> getString(R.string.imessage_service_failed, status.error)
                    else -> getString(R.string.imessage_service_starting)
                },
            )
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, DirectSetupActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    companion object {
        private const val TAG = "DirectService"
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
            if (!DirectEngine.isSetUp(context)) return false
            return try {
                ContextCompat.startForegroundService(context, Intent(context, DirectService::class.java))
                true
            } catch (e: IllegalStateException) {
                Log.w(TAG, "start refused", e)
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DirectService::class.java))
        }
    }
}
