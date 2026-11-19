package com.lightdecoder.imessage.madrid

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.Toast
import com.bluebubbles.messaging.IKeyboardHandle
import com.bluebubbles.messaging.IMadridExtension
import com.bluebubbles.messaging.IMessageViewHandle
import com.bluebubbles.messaging.ITaskCompleteCallback
import com.bluebubbles.messaging.IViewUpdateCallback
import com.bluebubbles.messaging.MadridMessage
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lightdecoder.R
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.imessage.IMessageLog
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.direct.DirectArchive
import com.lightdecoder.imessage.direct.DirectLink
import com.lightdecoder.imessage.direct.DirectPrefs
import com.lightdecoder.imessage.direct.IMessageApps
import com.lightdecoder.imessage.direct.IMessageExtra
import com.lightdecoder.relay.MessageDto
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The host for iMessage-app extension apps (BlueBubbles' "Madrid extension" interface, as
 * OpenBubbles hosts it): a separately installed app (OpenPigeon) plays the iMessage app (GamePigeon)
 * and LightDecoder only carries its messages, through the iMessage library ([DirectLink]). No game
 * is known here: a card's address and session are the extension's own and pass through as they are.
 *
 * - A card tapped ([engage]): its extension app opens it ([IMadridExtension.didTapTemplate]) with a
 *   view handle, through which the app sends its moves ([IMessageViewHandle.updateMessage]) and
 *   says when a game is open ([IMessageViewHandle.lock]) and the chat is to be quiet
 *   ([IMessageViewHandle.setSuppressNotifications]). The session stays open while a handle is
 *   locked ([MadridSessions]).
 * - A move from Apple for an open session goes to the app ([IMadridExtension.messageUpdated]).
 * - "Games" in the composer ([openGames]): the app's game picker, in a sheet; a game picked there
 *   is sent as a new card. Without the app, an offer to install it.
 *
 * Only while "iMessage games" is on ([DirectPrefs.gamesOn]): off, a card's tap is not taken (its
 * other ways apply), nothing is bound, and no move from Apple is handed over.
 *
 * Main process only. Nothing calls the extension on the main thread; nothing it calls here blocks.
 */
class MadridHost private constructor(private val app: Context) : IMessageApps.Host, DirectLink.AppEvents {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val sessions = MadridSessions()
    // A move that may have gone (Apple did not confirm it in time) is not sent again blindly either.
    private val dedupe = MoveDedupe<DirectLink.AppUpdate> { it.sent || it.unconfirmed }
    private val handleIds = AtomicInteger()
    private val connections = HashMap<String, MadridConnection>()

    /** Sessions whose connection is held while they are engaged ([holdWhileEngaged]). */
    private val held = HashSet<String>()

    /** Moves from Apple for the extension apps, handed over one at a time, in order. */
    private val incoming = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (next in incoming) {
                try {
                    next()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "move not handed over", e)
                }
            }
        }
    }

    private fun now() = System.currentTimeMillis()

    private fun connection(ext: MadridApps.MadridApp): MadridConnection = synchronized(connections) {
        connections.getOrPut(ext.pkg) { MadridConnection(app, ext, onDied = { died(ext) }) }
    }

    /** The extension app's process or binding died: its games hold no lock or quiet any more. */
    private fun died(ext: MadridApps.MadridApp) {
        sessions.died(ext.pkg)
    }

    /** The extension app's Android user id, to tell its calls from anyone else's; -1 when it is not installed. */
    private fun uidOf(ext: MadridApps.MadridApp): Int = try {
        app.packageManager.getPackageUid(ext.pkg, 0)
    } catch (e: Exception) {
        -1
    }

    /** Whether the binder call being answered comes from [ext] (the handles are its alone). */
    private fun fromExtension(ext: MadridApps.MadridApp): Boolean {
        val caller = Binder.getCallingUid()
        // Called from this process (a test, or this app itself): no other app.
        return caller == android.os.Process.myUid() || caller == uidOf(ext)
    }

    /** The number of people in [conversationId], the user included. */
    private fun userCount(conversationId: String): Int =
        IMessageManager.get(app).repo.participants(conversationId).filter { !it.isMe }.map { it.id }.distinct().size + 1

    // --- a card tapped ----------------------------------------------------------------------------

    override fun engage(context: Context, row: MessageRow, app: IMessageExtra.App): Boolean {
        if (!DirectPrefs.gamesOn(context)) return false
        return when (val route = MadridApps.route(app) { MadridApps.status(context.packageManager, it) }) {
            MadridApps.Route.None -> false
            is MadridApps.Route.Install -> {
                offerInstall(context, route.app)
                true
            }
            is MadridApps.Route.Unverified -> {
                toast(context.getString(R.string.imessage_app_unverified, route.app.label))
                true
            }
            is MadridApps.Route.Engage -> {
                // A card with no session is nothing the app can open.
                if (!MadridMessages.isSession(app.session)) return false
                // Whichever card of the session was tapped, the app is given its newest message:
                // it holds back a move made through a handle older than the newest it knows.
                val latest = DirectLink.get(context).latestOfAppSession(row.conversationId, app.session)
                    ?: row.takeIf { DirectArchive.guidOf(it.id) != null }
                val balloon = latest?.let { IMessageExtra.parse(it.extra).app }
                if (latest == null || balloon == null) {
                    // The user's own new card, still on its way (or failed): it has no GUID to open it by yet.
                    toast(context.getString(R.string.imessage_app_still_sending))
                    return true
                }
                open(route.app, latest, balloon)
                true
            }
        }
    }

    /** "Install OpenPigeon to play GamePigeon games with iPhone users", with its store page. */
    private fun offerInstall(context: Context, ext: MadridApps.MadridApp) {
        MaterialAlertDialogBuilder(context)
            .setTitle(ext.label)
            .setMessage(context.getString(R.string.imessage_app_install, ext.label, ext.name))
            .setPositiveButton(R.string.imessage_app_install_button) { _, _ -> openStore(context, ext) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Opens [balloon], the card of [row] (an iMessage, the newest of its session), in [ext], with a handle of its own. */
    private fun open(ext: MadridApps.MadridApp, row: MessageRow, balloon: IMessageExtra.App) {
        val guid = DirectArchive.guidOf(row.id) ?: return
        val root = balloon.root.ifEmpty { row.id }
        val handle = ViewHandle(handleIds.incrementAndGet(), ext)
        sessions.engage(MadridSessions.Handle(handle.id, balloon.session, root, row.conversationId, ext.pkg), now())
        scope.launch {
            val conn = connection(ext)
            val api = conn.acquire() ?: return@launch toast(app.getString(R.string.imessage_app_no_answer, ext.label))
            try {
                api.didTapTemplate(MadridMessages.toMadridMessage(guid, balloon), handle, userCount(row.conversationId))
                IMessageLog.add(app, "${IMessageLog.chatRef(row.conversationId)}: an iMessage app's card opened in ${ext.label}")
            } catch (e: RemoteException) {
                Log.w(TAG, "${ext.label} did not open the card", e)
                toast(app.getString(R.string.imessage_app_no_answer, ext.label))
            } catch (e: RuntimeException) {
                Log.w(TAG, "${ext.label} did not open the card", e)
                toast(app.getString(R.string.imessage_app_no_answer, ext.label))
            }
            holdWhileEngaged(balloon.session, conn)
        }
    }

    /**
     * Keeps [conn] (acquired) while [session] is engaged, then lets the session and the
     * connection go. One hold per session: a second is let go at once.
     */
    private suspend fun holdWhileEngaged(session: String, conn: MadridConnection) {
        val first = synchronized(held) { held.add(session) }
        if (!first) return conn.release()
        try {
            while (!sessions.releaseIfIdle(session, now())) delay(ENGAGED_CHECK_MS)
        } finally {
            synchronized(held) { held.remove(session) }
            conn.release()
        }
    }

    /** What the extension app was given for one card it opened: it sends that session's moves through it. */
    private inner class ViewHandle(val id: Int, private val ext: MadridApps.MadridApp) : IMessageViewHandle.Stub() {

        override fun updateMessage(message: MadridMessage?, callback: ITaskCompleteCallback?) {
            try {
                if (message != null && fromExtension(ext)) move(id, ext, message, callback)
            } catch (e: RuntimeException) {
                Log.w(TAG, "move not taken", e)
            }
        }

        override fun setSuppressNotifications(suppress: Boolean) {
            try {
                if (fromExtension(ext)) sessions.suppress(id, suppress, now())
            } catch (e: RuntimeException) {
                Log.w(TAG, "quiet not set", e)
            }
        }

        override fun lock() {
            try {
                if (fromExtension(ext)) sessions.lock(id, now())
            } catch (e: RuntimeException) {
                Log.w(TAG, "lock not taken", e)
            }
        }

        override fun unlock() {
            try {
                if (fromExtension(ext)) sessions.unlock(id, now())
            } catch (e: RuntimeException) {
                Log.w(TAG, "unlock not taken", e)
            }
        }
    }

    /**
     * A move [m] through handle [handleId]: to the handle's session (whatever [m] names), sent once
     * however often it is asked ([MoveDedupe]); [callback] hears of it only once Apple took it.
     * Only while the session is engaged. Returns at once.
     */
    private fun move(handleId: Int, ext: MadridApps.MadridApp, m: MadridMessage, callback: ITaskCompleteCallback?) {
        val now = now()
        val handle = sessions.handle(handleId) ?: return
        if (!sessions.acceptsMoves(handleId, now)) {
            Log.i(TAG, "a move came for a session no longer open; not sent")
            return
        }
        MadridMessages.problemWithMove(m)?.let { problem ->
            Log.w(TAG, "${ext.label} made a move that cannot be sent: $problem")
            toast(app.getString(R.string.imessage_app_move_failed, app.getString(R.string.imessage_app_move_invalid)))
            return
        }
        val balloon = MadridMessages.appOf(m, ext, handle.session, handle.rootMessageId)
        val jpeg = MadridMessages.jpegOf(m.imageBase64)
        val send = dedupe.join(handle.session, m.url.orEmpty(), now) {
            // At most one new move per session every 2 s (another app can drive the extension's handle).
            if (!sessions.takeMoveSlot(handleId, now)) {
                CompletableDeferred(DirectLink.AppUpdate(sent = false, error = app.getString(R.string.imessage_app_move_too_soon)))
            } else {
                scope.async { DirectLink.get(app).sendAppUpdate(handle.conversationId, handle.rootMessageId, balloon, jpeg) }
            }
        }
        scope.launch {
            val result = send.await()
            if (result.sent) {
                try {
                    callback?.complete()
                } catch (e: RemoteException) {
                    Log.w(TAG, "${ext.label} not told the move went", e)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "${ext.label} not told the move went", e)
                }
            } else {
                // The app keeps the move and offers to send it again.
                toast(app.getString(R.string.imessage_app_move_failed, result.error.orEmpty()))
            }
        }
    }

    // --- moves from Apple, and quiet ----------------------------------------------------------------

    override fun appUpdateArrived(conversationId: String, message: MessageDto) {
        if (!DirectPrefs.gamesOn(app)) return
        val balloon = IMessageExtra.parse(message.extra).app ?: return
        val ext = MadridApps.forApp(balloon) ?: return
        val guid = DirectArchive.guidOf(message.id) ?: return
        if (!sessions.isEngaged(balloon.session, now())) return
        incoming.trySend {
            val conn = connection(ext)
            val api = conn.acquire() ?: return@trySend
            try {
                api.messageUpdated(MadridMessages.toMadridMessage(guid, balloon))
            } catch (e: RemoteException) {
                Log.w(TAG, "${ext.label} not given the move", e)
            } finally {
                conn.release()
            }
        }
    }

    override fun silences(conversationId: String): Boolean = DirectPrefs.gamesOn(app) && sessions.suppressed(conversationId, now())

    // --- "Games" in the composer -------------------------------------------------------------------

    /** The extension apps whose game picker can be opened ([openPicker]): installed and verified; none while games are off. */
    fun available(context: Context): List<MadridApps.MadridApp> =
        if (DirectPrefs.gamesOn(context)) MadridApps.installed(context.packageManager) else emptyList()

    /**
     * Whether "Games" is offered in the composer: while games are on, installed or not (without
     * the app, choosing it offers to install it: [openGames]).
     */
    fun gamesOffered(context: Context): Boolean = DirectPrefs.gamesOn(context) && MadridApps.REGISTRY.isNotEmpty()

    /**
     * "Games" chosen in the composer, for [conversationId]: the picker of the first extension app
     * installed ([openPicker]); else the offer to install it, or why the installed copy is not
     * used. Null when no picker opened (or games are off).
     */
    fun openGames(activity: Activity, conversationId: String): Sheet? {
        if (!DirectPrefs.gamesOn(activity)) return null
        openPicker(activity, conversationId)?.let { return it }
        val ext = MadridApps.REGISTRY.firstOrNull() ?: return null
        when (MadridApps.status(activity.packageManager, ext)) {
            MadridApps.Status.NOT_INSTALLED -> offerInstall(activity, ext)
            MadridApps.Status.UNVERIFIED -> toast(activity.getString(R.string.imessage_app_unverified, ext.label))
            // Verified meanwhile: the picker next time.
            MadridApps.Status.VERIFIED -> Unit
        }
        return null
    }

    /** An open games picker, and the "Send this game?" it led to: the screen that opened it closes it when it goes or changes chat. */
    interface Sheet {
        /** Closes it (and tells the extension app); again is nothing. */
        fun dismiss()
    }

    /**
     * Opens the game picker of the first extension app installed, for [conversationId] (an
     * iMessage chat the library can reach); null when there is none. [activity] dismisses it
     * ([Sheet.dismiss]) when it is destroyed or shows another chat.
     */
    fun openPicker(activity: Activity, conversationId: String): Sheet? {
        val ext = available(activity).firstOrNull() ?: return null
        return Picker(activity, ext, conversationId).also { it.show() }
    }

    /**
     * The extension app's game picker (its own views, [RemoteViews]) in a sheet: replaced as the app
     * updates it; the app told when it is closed. A game picked there is offered to send.
     */
    private inner class Picker(
        private val activity: Activity,
        private val ext: MadridApps.MadridApp,
        private val conversationId: String,
    ) : MadridConnection.Listener, Sheet {

        private val lock = Any()
        private val sheet = BottomSheetDialog(activity)

        /** The "Send this game?" a game picked here led to. */
        private var confirm: android.app.Dialog? = null
        private val container = FrameLayout(activity)
        private var shown: View? = null

        @Volatile
        private var conn: MadridConnection? = null

        @Volatile
        private var closed = false

        @Volatile
        private var api: IMadridExtension? = null

        private val views = object : IViewUpdateCallback.Stub() {
            override fun updateView(views: RemoteViews?) {
                if (views == null || !fromExtension(ext)) return
                main.post { render(views) }
            }
        }

        private val keyboard = object : IKeyboardHandle.Stub() {
            override fun addMessage(message: MadridMessage?) {
                if (message == null || !fromExtension(ext)) return
                main.post { picked(message) }
            }
        }

        fun show() {
            val height = (PICKER_HEIGHT_DP * activity.resources.displayMetrics.density).toInt()
            container.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
            container.minimumHeight = height
            sheet.setContentView(container)
            sheet.setOnDismissListener { close() }
            sheet.show()
            scope.launch {
                val c = connection(ext)
                val got = c.acquire()
                if (got == null) {
                    toast(app.getString(R.string.imessage_app_no_answer, ext.label))
                    main.post { sheet.dismiss() }
                    return@launch
                }
                // Closed meanwhile: what was acquired goes at once. Else close() lets it go.
                val kept = synchronized(lock) {
                    if (!closed) conn = c
                    !closed
                }
                if (!kept) return@launch c.release()
                // Every connection (again, after the app was restarted) opens the picker anew.
                c.listen(this@Picker)
                // Closed while it started listening: close() may have missed the listener.
                if (closed) c.unlisten(this@Picker)
            }
        }

        override fun dismiss() {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                main.post { dismiss() }
                return
            }
            try {
                confirm?.dismiss()
                sheet.dismiss()
            } catch (e: RuntimeException) {
                // Its window is gone already.
            }
            confirm = null
            // Also when no dismiss is heard (the screen is being destroyed).
            close()
        }

        override fun connected(api: IMadridExtension) {
            if (closed) return
            this.api = api
            scope.launch {
                val first = try {
                    api.keyboardOpened(views, keyboard, userCount(conversationId))
                } catch (e: RemoteException) {
                    Log.w(TAG, "${ext.label} did not open its picker", e)
                    null
                } catch (e: RuntimeException) {
                    Log.w(TAG, "${ext.label} did not open its picker", e)
                    null
                }
                if (first != null) main.post { render(first, fresh = true) }
            }
        }

        /** Shows [views]: over what is shown when it can, else in its place. On the main thread. */
        private fun render(views: RemoteViews, fresh: Boolean = false) {
            if (closed) return
            try {
                val current = shown
                if (current != null && !fresh) {
                    try {
                        views.reapply(activity, current)
                        return
                    } catch (e: RuntimeException) {
                        // Another layout: drawn anew below.
                    }
                }
                container.removeAllViews()
                shown = views.apply(activity, container).also(container::addView)
            } catch (e: RuntimeException) {
                Log.w(TAG, "${ext.label}'s picker not shown", e)
            }
        }

        /** A game picked in the picker: the sheet closes and the new card is offered to send. */
        private fun picked(m: MadridMessage) {
            if (closed) return
            sheet.dismiss()
            if (MadridMessages.problemWithCard(m) != null) {
                toast(activity.getString(R.string.imessage_app_card_invalid, ext.label))
                return
            }
            val card = MadridMessages.appOf(m, ext, m.session.orEmpty())
            val jpeg = MadridMessages.jpegOf(m.imageBase64)
            confirm = confirmCard(activity, ext, conversationId, card, jpeg)
        }

        /** Once: the extension is told its picker closed, and let go. */
        private fun close() {
            val c = synchronized(lock) {
                if (closed) return
                closed = true
                conn
            } ?: return
            c.unlisten(this)
            val api = api
            scope.launch {
                try {
                    api?.keyboardClosed()
                } catch (e: RemoteException) {
                    Log.w(TAG, "${ext.label} not told its picker closed", e)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "${ext.label} not told its picker closed", e)
                } finally {
                    c.release()
                }
            }
        }
    }

    /** "Send this GamePigeon game?", with its picture and caption; Send sends it as a new card. */
    private fun confirmCard(activity: Activity, ext: MadridApps.MadridApp, conversationId: String, card: IMessageExtra.App, jpeg: ByteArray?): android.app.Dialog? {
        if (activity.isFinishing || activity.isDestroyed) return null
        val picture = jpeg?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.let { bitmap ->
            ImageView(activity).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                val pad = (16 * activity.resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, 0)
            }
        }
        return MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.imessage_app_send_title, ext.name))
            .setMessage(card.summary().ifBlank { null })
            .apply { if (picture != null) setView(picture) }
            .setPositiveButton(R.string.imessage_app_send) { _, _ ->
                scope.launch {
                    try {
                        DirectLink.get(app).sendAppCard(conversationId, card, jpeg)
                    } catch (e: Exception) {
                        Log.w(TAG, "new card not sent", e)
                        toast(app.getString(R.string.imessage_app_card_failed, e.message.orEmpty()))
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(text: String) {
        main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
    }

    companion object {
        private const val TAG = "MadridHost"

        /** The picker's height, as OpenPigeon draws it for a host like this one. */
        private const val PICKER_HEIGHT_DP = 300

        /** How often an engaged session is checked for having ended. */
        private const val ENGAGED_CHECK_MS = 10_000L

        @Volatile
        private var instance: MadridHost? = null

        /**
         * Opens [ext]'s page in the store app, else on the web; says so when neither opens. Any
         * process (the setup screen's "Get games" too).
         */
        fun openStore(context: Context, ext: MadridApps.MadridApp) {
            for (uri in listOf(ext.marketUri, ext.webStoreUri)) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return
                } catch (e: ActivityNotFoundException) {
                    // The next one.
                }
            }
            Toast.makeText(context, context.getString(R.string.imessage_app_no_store), Toast.LENGTH_LONG).show()
        }

        /** Main process only. */
        fun get(context: Context): MadridHost =
            instance ?: synchronized(this) {
                instance ?: MadridHost(context.applicationContext).also { instance = it }
            }

        /**
         * Makes the host the one card taps ([IMessageApps.host]) and the iMessage archive
         * ([DirectLink.appEvents]) go to. At app start, main process.
         */
        fun install(context: Context) {
            val host = get(context)
            IMessageApps.host = host
            DirectLink.appEvents = host
        }

        /** Tests: a new host next time. */
        internal fun resetForTesting() {
            synchronized(this) {
                instance?.scope?.coroutineContext?.get(kotlinx.coroutines.Job)?.cancel()
                instance = null
            }
        }
    }
}
