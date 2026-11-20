package com.lightdecoder.imessage.direct

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lightdecoder.R
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The ":imessage" process's service: keeps the iMessage session connected (in the foreground,
 * like the Google Messages link's service) so iMessages reach the Messages tab whether a screen is
 * open or not, answers the main process's requests ([DirectWire]: send (with mentions and a link's
 * preview), unsend, reach, edit, react, typing, read, download, an iMessage app's update (a vote in a
 * poll too), a chat's background, Send Later taken back,
 * a group's name, members and photo, a chat marked unread, a junk report, the people of recent chats
 * told of the user's new device), and hands what Apple sends to the main process's
 * [DirectSinkService].
 */
class DirectService : Service() {

    private val scope = MainScope()
    private val notice by lazy { ServiceNotice.Poster(this) }
    private lateinit var engine: DirectEngine
    private lateinit var sinkClient: DirectIpc.Client

    private val server = DirectIpc.Server { what, request ->
        when (what) {
            DirectWire.SEND -> {
                val r = DirectWire.SendRequest.fromJson(request)
                // Only files of the app's own (its outbox), never the library's state or anything outside: DirectPaths.
                for (a in r.attachments) DirectPaths.require(this, a.path, getString(R.string.imessage_direct_attachment_unreadable))
                // A link preview's picture only from where the main process keeps them (the iMessage media
                // folder); one from anywhere else goes without its picture.
                val link = r.link?.let { l -> if (l.imagePath != null && !DirectPaths.isAllowed(l.imagePath, DirectPaths.forImessageMedia(this))) l.copy(imagePath = null) else l }
                engine.send(r.target, r.text, r.replyGuid, r.replyPart, r.effect, r.attachments, r.voice, r.app, r.runs, r.scheduledMs, r.mentions, link).toJson()
            }
            DirectWire.EXTENSION -> {
                val r = DirectWire.ExtensionRequest.fromJson(request)
                r.imagePath?.let { DirectPaths.require(this, it, getString(R.string.imessage_direct_attachment_unreadable)) }
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
                engine.edit(r.target, r.guid, r.part, r.text, r.runs, r.mentions)
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
                val r = DirectWire.ReadRequest.fromJson(request)
                engine.read(r.target, r.guid, r.receipts)
                DirectWire.ok()
            }
            DirectWire.RENAME -> {
                val r = DirectWire.RenameRequest.fromJson(request)
                DirectWire.guid(engine.rename(r.target, r.name))
            }
            DirectWire.MEMBERS -> {
                val r = DirectWire.MembersRequest.fromJson(request)
                DirectWire.guid(engine.members(r.target, r.participants, r.groupVersion))
            }
            DirectWire.GROUP_PHOTO -> {
                val r = DirectWire.GroupPhotoRequest.fromJson(request)
                // Only the square picture the main process made to send, where group photos are kept (not
                // their index, nor a group's photo kept there).
                val path = r.path?.takeIf { GroupPhotos.isNew(this, java.io.File(it), GroupPhotos.OUTGOING) }
                if (r.path != null && path == null) throw DirectEngine.ImessageError(getString(R.string.imessage_group_photo_unreadable))
                DirectWire.guid(engine.groupPhoto(r.target, path, r.groupVersion))
            }
            DirectWire.DOWNLOAD -> {
                val r = DirectWire.DownloadRequest.fromJson(request)
                // Written only into the app's own media folders, never over the library's state or outside the app.
                DirectPaths.require(this, r.path, getString(R.string.imessage_direct_attachment_not_saved))
                DirectWire.downloaded(engine.download(r.descriptor, r.path, r.maxBytes, r.automaticMs))
            }
            DirectWire.BACKGROUND -> {
                val r = DirectWire.BackgroundRequest.fromJson(request)
                // Only a picture the main process put where backgrounds are kept.
                val path = r.path?.takeIf { ChatBackgrounds.isOurs(this, java.io.File(it)) }
                if (r.path != null && path == null) throw DirectEngine.ImessageError(getString(R.string.imessage_background_unreadable))
                engine.setBackground(r.target, path, r.version, r.style)
                DirectWire.ok()
            }
            DirectWire.UNSCHEDULE -> {
                engine.unschedule(target(request), request.getString("guid"))
                DirectWire.ok()
            }
            DirectWire.MARK_UNREAD -> {
                val r = DirectWire.MarkUnreadRequest.fromJson(request)
                engine.markUnread(r.target, r.guid)
                DirectWire.ok()
            }
            DirectWire.REPORT_JUNK -> {
                val r = DirectWire.ReportJunkRequest.fromJson(request)
                engine.reportJunk(r.target, r.messages)
                DirectWire.ok()
            }
            DirectWire.PEERS -> {
                engine.invalidatePeers(DirectWire.PeersRequest.fromJson(request).groups)
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
                this, NOTIFICATION_ID, notice.forForeground(engine.status.value),
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
        notice.update(status)
    }

    companion object {
        private const val TAG = "DirectService"
        /** The notice's channel ([ServiceNotice]). */
        const val CHANNEL = ServiceNotice.CHANNEL
        private const val NOTIFICATION_ID = ServiceNotice.NOTIFICATION_ID
        private const val SINK_TIMEOUT_MS = 30_000L

        fun ensureChannel(context: Context) = ServiceNotice.ensureChannel(context)

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
