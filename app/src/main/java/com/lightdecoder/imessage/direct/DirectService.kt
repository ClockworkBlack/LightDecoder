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
 * told of the user's new device, who can be called with FaceTime, where the optional Apple Account
 * stands, where FaceTime stands), and hands what Apple sends to the
 * main process's [DirectSinkService] (which also says who a FaceTime caller is).
 */
class DirectService : Service() {

    private val scope = MainScope()
    private val notice by lazy { ServiceNotice.Poster(this) }
    private lateinit var engine: DirectEngine
    private lateinit var sinkClient: DirectIpc.Client

    /** FaceTime's lines for the main process's iMessage log, the oldest dropped when it cannot keep up. */
    private val notes = kotlinx.coroutines.channels.Channel<String>(64, kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    private val server = DirectIpc.Server(afterReply = ::afterReply) { what, request, progress ->
        when (what) {
            DirectWire.SEND -> {
                val r = DirectWire.SendRequest.fromJson(request)
                // Only files of the app's own (its outbox), never the library's state or anything outside: DirectPaths.
                for (a in r.attachments) DirectPaths.require(this, a.path, getString(R.string.imessage_direct_attachment_unreadable))
                // A link preview's picture only from where the main process keeps them (the iMessage media
                // folder); one from anywhere else goes without its picture.
                val link = r.link?.let { l -> if (l.imagePath != null && !DirectPaths.isAllowed(l.imagePath, DirectPaths.forImessageMedia(this))) l.copy(imagePath = null) else l }
                // Its upload's progress (attachments only) goes back on this request, and it can be stopped until it is handed to Apple.
                val shows = r.transfer != null && r.attachments.isNotEmpty()
                engine.send(
                    r.target, r.text, r.replyGuid, r.replyPart, r.effect, r.attachments, r.voice, r.app, r.runs, r.scheduledMs, r.mentions, link,
                    transfer = r.transfer, onProgress = if (shows) progress else null,
                ).toJson()
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
                // Progress only for one the user asked for (never an automatic one), and only when it can be stopped.
                DirectWire.downloaded(
                    engine.download(r.descriptor, r.path, r.maxBytes, r.automaticMs, r.transfer, if (r.automaticMs == null && r.transfer != null) progress else null),
                )
            }
            DirectWire.CANCEL -> {
                // Only a transfer's key, as the main process makes them; nothing is looked up by anything else.
                val key = DirectWire.cancelKeyOf(request) ?: throw DirectEngine.ImessageError("No transfer")
                DirectWire.cancelled(engine.cancel(key))
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
            DirectWire.FT_CAPABLE -> {
                val r = DirectWire.CapableRequest.fromJson(request)
                DirectWire.capable(engine.faceTimeCapable(r.targets, r.from))
            }
            // The optional Apple Account: where it stands, Booleans only (never the Apple ID).
            DirectWire.ACCOUNT -> DirectWire.accountReply(engine.account.state.value, engine.account.restartPending)
            // Where FaceTime stands, for the main process's "return to call" bar: only the call there is, if any (nothing is made to answer it).
            DirectWire.FT_STATE -> com.lightdecoder.imessage.facetime.FaceTimeNotices.state(this, com.lightdecoder.imessage.facetime.FaceTimeCalls.peek()?.state?.value).toJson()
            // The user's block list, as salted hashes only: FaceTime drops a blocked caller's ring here, with no question to the main process.
            DirectWire.BLOCKLIST -> {
                val r = DirectWire.BlockListRequest.fromJson(request) ?: return@Server DirectWire.error("Not a block list")
                com.lightdecoder.imessage.facetime.BlockedCallers.get(this).replace(r)
                DirectWire.ok()
            }
            else -> DirectWire.error("Unknown request $what")
        }
    }

    /**
     * Once a request's answer has gone. Only an [DirectWire.ACCOUNT] request that says one of the main
     * process's screens is in sight lets the Apple Account's pending restart be made (Android allows
     * iMessage's service to be started then); no other request ever does, and with nothing pending it
     * does nothing at all.
     */
    private fun afterReply(what: Int, request: JSONObject) {
        if (restartsAfter(what, request)) engine.account.screenInSight()
    }

    private fun target(request: JSONObject) = DirectWire.Target.fromJson(request.getJSONObject("target"))

    override fun onBind(intent: Intent?): IBinder = server.messenger.binder

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        engine = DirectEngine.get(this)
        // A restart for the Apple Account waits for the main process's requests being answered here.
        engine.requests = server.requests
        sinkClient = DirectIpc.Client(this, DirectSinkService::class.java)
        engine.sink = DirectEngine.Sink { update ->
            val reply = sinkClient.call(DirectWire.UPDATE, DirectWire.encodeUpdate(update), SINK_TIMEOUT_MS)
            Delivery.of(reply)
        }
        // FaceTime: who is calling is the main process's to say (it has the contacts and the chats),
        // and what the calls did goes to its iMessage log, in order.
        engine.callerLookup = { handle ->
            val reply = sinkClient.call(DirectWire.CALLER, DirectWire.caller(handle), CALLER_TIMEOUT_MS)
            if (DirectWire.errorOf(reply) != null) null else DirectWire.callerInfo(reply)
        }
        com.lightdecoder.imessage.facetime.FaceTimeLog.sink = { line -> notes.trySend(line) }
        scope.launch {
            for (line in notes) {
                runCatching { sinkClient.call(DirectWire.UPDATE, DirectWire.encode(DirectArchive.Update.Note(line)), NOTE_TIMEOUT_MS) }
            }
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
        if (engine.requests === server.requests) engine.requests = null
        engine.sink = null
        engine.callerLookup = null
        com.lightdecoder.imessage.facetime.FaceTimeLog.sink = null
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

        /** The main process's answer to "who is calling" is not waited for longer than the call engine would use it. */
        private const val CALLER_TIMEOUT_MS = com.lightdecoder.imessage.facetime.FaceTimeCalls.LOOKUP_MS
        private const val NOTE_TIMEOUT_MS = 5_000L

        fun ensureChannel(context: Context) = ServiceNotice.ensureChannel(context)

        /** Whether the answered request [what] lets a pending restart be made: only an ACCOUNT one with `"visible": true`. */
        internal fun restartsAfter(what: Int, request: JSONObject): Boolean =
            what == DirectWire.ACCOUNT && DirectWire.AccountRequest.fromJson(request).visible

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
