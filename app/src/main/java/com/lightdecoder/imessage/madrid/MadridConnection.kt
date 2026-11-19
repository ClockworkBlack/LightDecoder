package com.lightdecoder.imessage.madrid

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import com.bluebubbles.messaging.IMadridExtension
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The binding to one extension app's service ([MadridApps.MadridApp]), shared by everything that
 * talks to it: each [acquire] binds it (if it is not bound yet) and waits for it, each [release]
 * lets go; the last one unbinds it a little later, so that a quick next use does not bind again.
 * When the app's process dies, or its binding does (it was updated, or stopped), [onDied] is told.
 *
 * The extension is only bound after its certificate was checked, before every bind
 * ([MadridApps.status]); nothing here calls it on the main thread.
 */
internal class MadridConnection(
    private val context: Context,
    val ext: MadridApps.MadridApp,
    /** Whether [ext] is installed and signed as pinned now; asked before every bind (it is cached by update time). */
    private val verified: () -> Boolean = { MadridApps.status(context.packageManager, ext) == MadridApps.Status.VERIFIED },
    private val onDied: () -> Unit,
) {
    /**
     * Told each time the extension connects (again, after it died): on the main thread when it
     * connects, or on the caller's thread when [listen] finds it connected already. It only starts work.
     */
    fun interface Listener {
        fun connected(api: IMadridExtension)
    }

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private var refs = 0
    private var bound = false
    private var api: IMadridExtension? = null
    private var waiting = CompletableDeferred<IMadridExtension>()
    private val listeners = LinkedHashSet<Listener>()
    /**
     * Why the last [acquire] gave nothing ("could not be bound", "did not connect in 10 s"), for
     * the iMessage log; null after one that worked.
     */
    @Volatile
    var lastProblem: String? = null
        private set

    private val unbindLater = Runnable { synchronized(lock) { if (refs == 0) unbind() } }

    private val death = IBinder.DeathRecipient { died() }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) return
            val ext = IMadridExtension.Stub.asInterface(service)
            try {
                service.linkToDeath(death, 0)
            } catch (e: RemoteException) {
                died()
                return
            }
            val toTell = synchronized(lock) {
                api = ext
                waiting.complete(ext)
                listeners.toList()
            }
            for (l in toTell) l.connected(ext)
        }

        // Its process died: Android binds it again by itself.
        override fun onServiceDisconnected(name: ComponentName?) = died()

        // Updated or stopped: this binding is over; a new one is made when one is needed.
        override fun onBindingDied(name: ComponentName?) {
            died()
            synchronized(lock) {
                unbind()
                if (refs > 0) bind()
            }
        }

        override fun onNullBinding(name: ComponentName?) {
            died()
            synchronized(lock) { unbind() }
        }
    }

    /**
     * The extension, bound and connected; null when it could not be bound or did not connect in
     * [BIND_TIMEOUT_MS] (then nothing is held). Each non-null one is to be [release]d. Not on the
     * main thread.
     */
    suspend fun acquire(): IMadridExtension? {
        val wait = synchronized(lock) {
            refs++
            main.removeCallbacks(unbindLater)
            api?.let {
                lastProblem = null
                return it
            }
            if (!bound && !bind()) {
                refs--
                return null
            }
            waiting
        }
        val ext = withTimeoutOrNull(BIND_TIMEOUT_MS) { wait.await() }
        if (ext == null) {
            Log.w(TAG, "${ext()} did not connect")
            lastProblem = "bound, but it did not connect in ${BIND_TIMEOUT_MS / 1000} s"
            release()
        } else {
            lastProblem = null
        }
        return ext
    }

    /** Lets go of what [acquire] gave. */
    fun release() {
        synchronized(lock) {
            if (refs == 0) return
            refs--
            if (refs == 0) {
                main.removeCallbacks(unbindLater)
                main.postDelayed(unbindLater, UNBIND_DELAY_MS)
            }
        }
    }

    /** [l] hears of every connection from now on, the current one included. */
    fun listen(l: Listener) {
        val now = synchronized(lock) {
            listeners += l
            api
        }
        now?.let(l::connected)
    }

    fun unlisten(l: Listener) {
        synchronized(lock) { listeners -= l }
    }

    private fun bind(): Boolean {
        // Before every bind, a rebind after an update included: the app now there may be another.
        if (!verified()) {
            Log.w(TAG, "${ext()} is not the verified app: not bound")
            lastProblem = "not the verified app: not bound"
            return false
        }
        lastProblem = null
        val intent = Intent(MadridApps.ACTION).setComponent(ComponentName(ext.pkg, ext.service))
        var flags = Context.BIND_AUTO_CREATE
        // So that the game it opens on a tap may start its screen (Android 14 and later).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) flags = flags or Context.BIND_ALLOW_ACTIVITY_STARTS
        bound = try {
            context.bindService(intent, connection, flags)
        } catch (e: SecurityException) {
            Log.w(TAG, "${ext()} refused the binding", e)
            lastProblem = "refused the binding (${e.javaClass.simpleName})"
            false
        }
        if (!bound) {
            Log.w(TAG, "${ext()} could not be bound")
            if (lastProblem == null) lastProblem = "could not be bound (bindService said no)"
            // A failed bindService still has to be unbound.
            runCatching { context.unbindService(connection) }
        }
        return bound
    }

    private fun unbind() {
        if (!bound) return
        bound = false
        api?.asBinder()?.let { runCatching { it.unlinkToDeath(death, 0) } }
        api = null
        if (waiting.isCompleted) waiting = CompletableDeferred()
        runCatching { context.unbindService(connection) }
    }

    /** The extension's process is gone: so is all it had. */
    private fun died() {
        val wasUp = synchronized(lock) {
            val up = api != null
            api = null
            if (waiting.isCompleted) waiting = CompletableDeferred()
            up
        }
        if (wasUp) Log.i(TAG, "${ext()} stopped")
        onDied()
    }

    private fun ext() = ext.label

    private companion object {
        const val TAG = "MadridConnection"
        const val BIND_TIMEOUT_MS = 10_000L
        const val UNBIND_DELAY_MS = 5_000L
    }
}
