package com.lightdecoder.imessage.direct

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Requests between LightDecoder's two processes over a bound service's [Messenger]: each request
 * carries an id and JSON ([DirectWire]) and is answered with a reply of the same id. Both
 * services are not exported, so only LightDecoder's own processes can bind to them.
 */
object DirectIpc {
    private const val TAG = "DirectIpc"

    /** Answers requests: [handle] runs off the main thread and returns the reply's JSON. */
    class Server(private val handle: suspend (what: Int, request: JSONObject) -> JSONObject) {
        private val thread = HandlerThread("imessage-ipc-server").apply { start() }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val messenger = Messenger(
            object : Handler(thread.looper) {
                override fun handleMessage(msg: Message) {
                    val replyTo = msg.replyTo ?: return
                    val id = msg.data.getLong(DirectWire.KEY_ID)
                    val what = msg.what
                    val request = try {
                        JSONObject(msg.data.getString(DirectWire.KEY_JSON) ?: "{}")
                    } catch (e: org.json.JSONException) {
                        JSONObject()
                    }
                    scope.launch {
                        val reply = try {
                            handle(what, request)
                        } catch (e: Exception) {
                            Log.w(TAG, "request $what failed", e)
                            DirectWire.failure(e)
                        }
                        try {
                            replyTo.send(
                                Message.obtain(null, DirectWire.REPLY).apply {
                                    data = Bundle().apply {
                                        putLong(DirectWire.KEY_ID, id)
                                        putString(DirectWire.KEY_JSON, reply.toString())
                                    }
                                },
                            )
                        } catch (e: RemoteException) {
                            // The asking process went away; it will ask again.
                        }
                    }
                }
            },
        )

        fun close() {
            thread.quitSafely()
        }
    }

    /**
     * Asks a service in the other process. Binds on first use (BIND_AUTO_CREATE starts that
     * process if it is not running) and stays bound until [close].
     */
    class Client(context: Context, private val service: Class<out Service>) {
        private val app = context.applicationContext
        private val thread = HandlerThread("imessage-ipc-client").apply { start() }
        private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
        private val nextId = AtomicLong(1)
        private val bindLock = Mutex()

        @Volatile
        private var remote: Messenger? = null
        private var connecting: CompletableDeferred<Messenger?>? = null

        private val replies = Messenger(
            object : Handler(thread.looper) {
                override fun handleMessage(msg: Message) {
                    if (msg.what != DirectWire.REPLY) return
                    val id = msg.data.getLong(DirectWire.KEY_ID)
                    val json = try {
                        JSONObject(msg.data.getString(DirectWire.KEY_JSON) ?: "{}")
                    } catch (e: org.json.JSONException) {
                        DirectWire.error("Unreadable answer from the other process.")
                    }
                    pending.remove(id)?.complete(json)
                }
            },
        )

        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val m = binder?.let { Messenger(it) }
                remote = m
                connecting?.complete(m)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // The other process died: every open request fails now (the caller retries or
                // shows the error); the next request binds again once Android restarts it.
                remote = null
                pending.keys.toList().forEach { pending.remove(it)?.complete(DirectWire.error(DIED)) }
            }

            override fun onBindingDied(name: ComponentName?) {
                remote = null
                connecting = null
                try {
                    app.unbindService(this)
                } catch (e: IllegalArgumentException) {
                    // Not bound any more.
                }
            }
        }

        private suspend fun connect(): Messenger? = bindLock.withLock {
            remote?.let { return@withLock it }
            val wait = connecting ?: CompletableDeferred<Messenger?>().also { d ->
                connecting = d
                val ok = try {
                    app.bindService(Intent(app, service), connection, Context.BIND_AUTO_CREATE)
                } catch (e: SecurityException) {
                    false
                }
                if (!ok) {
                    connecting = null
                    d.complete(null)
                }
            }
            withTimeoutOrNull(BIND_TIMEOUT_MS) { wait.await() }
        }

        /** The other side's answer, or an [DirectWire.error] when it cannot be reached in [timeoutMs]. */
        suspend fun call(what: Int, request: JSONObject, timeoutMs: Long): JSONObject {
            val m = connect() ?: return DirectWire.error(UNREACHABLE)
            val id = nextId.getAndIncrement()
            val answer = CompletableDeferred<JSONObject>()
            pending[id] = answer
            try {
                m.send(
                    Message.obtain(null, what).apply {
                        replyTo = replies
                        data = Bundle().apply {
                            putLong(DirectWire.KEY_ID, id)
                            putString(DirectWire.KEY_JSON, request.toString())
                        }
                    },
                )
            } catch (e: RemoteException) {
                pending.remove(id)
                remote = null
                return DirectWire.error(DIED)
            }
            return withTimeoutOrNull(timeoutMs) { answer.await() } ?: run {
                pending.remove(id)
                DirectWire.error(NO_ANSWER)
            }
        }

        fun close() {
            try {
                app.unbindService(connection)
            } catch (e: IllegalArgumentException) {
                // Not bound.
            }
            remote = null
            thread.quitSafely()
        }

        companion object {
            private const val BIND_TIMEOUT_MS = 15_000L
            const val UNREACHABLE = "LightDecoder's iMessage part could not be reached."
            const val DIED = "LightDecoder's iMessage part stopped while working on this."
            const val NO_ANSWER = "LightDecoder's iMessage part did not answer in time."
        }
    }
}
