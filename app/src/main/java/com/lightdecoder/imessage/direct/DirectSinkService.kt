package com.lightdecoder.imessage.direct

import android.app.Service
import android.content.Intent
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import android.os.IBinder
import android.util.Log
import org.json.JSONObject

/**
 * Takes in what Apple sent, from the ":imessage" process, and stores it in the iMessage archive
 * (main process). One change at a time, in the order the other process sends them (Apple's).
 * Also answers that process's question of who a FaceTime caller is ([DirectWire.CALLER]).
 */
class DirectSinkService : Service() {

    private val server = DirectIpc.Server { what, request, _ ->
        when (what) {
            DirectWire.UPDATE -> take(request) { update ->
                synchronized(DirectSinkService::class.java) { DirectLink.get(this).apply(update) }
            }
            // Who is calling (FaceTime): the contact's name, and whether they are known here. Only read.
            DirectWire.CALLER -> {
                val handle = DirectWire.callerOf(request) ?: return@Server DirectWire.error("No caller")
                DirectWire.callerReply(DirectLink.get(this).callerInfo(handle))
            }
            else -> DirectWire.error("Unknown request $what")
        }
    }

    override fun onBind(intent: Intent?): IBinder = server.messenger.binder

    override fun onDestroy() {
        server.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DirectSink"

        /**
         * The answer to the [DirectWire.UPDATE] [request], stored with [store]: [DirectWire.ok], a
         * plain error when storing failed for now (the storage full or busy: it is handed over
         * again), or [DirectWire.rejected] when it cannot be read or storing it failed otherwise,
         * as it would every time; [DirectWire.blocked] when it was taken and dropped as from someone the
         * user blocked. Nothing an update does takes the main process down: an error too
         * (memory run out on it, a stack run out on what it holds) is that update rejected.
         */
        fun take(request: JSONObject, store: (DirectArchive.Update) -> Any?): JSONObject {
            val update = try {
                DirectWire.decode(request)
            } catch (e: Throwable) {
                Log.w(TAG, "update not readable (${e::class.java.simpleName})")
                return DirectWire.rejected(e)
            }
            return try {
                // Dropped as from someone the user blocked: said so, and no delivery receipt follows.
                if (store(update) == DirectLink.Applied.BLOCKED) DirectWire.blocked() else DirectWire.ok()
            } catch (e: Throwable) {
                Log.w(TAG, "update ${update::class.simpleName} not stored (${e::class.java.simpleName})")
                if (isForNow(e)) DirectWire.failure(e) else DirectWire.rejected(e)
            }
        }

        private fun isForNow(e: Throwable): Boolean =
            e is SQLiteFullException || e is SQLiteDatabaseLockedException || e is SQLiteDiskIOException || e is SQLiteCantOpenDatabaseException ||
                // Not allowed by the Apple Account as known here yet: asked again meanwhile.
                e is DirectLink.AccountNotKnown
    }
}
