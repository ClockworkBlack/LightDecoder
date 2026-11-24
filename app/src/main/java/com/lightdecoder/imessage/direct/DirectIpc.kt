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
import android.os.TransactionTooLargeException
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
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
 * services are not exported, so only LightDecoder's own processes can bind to them; and every
 * message, request or reply, is also dropped unless it was sent by this app's own uid
 * ([fromThisApp], [Message.sendingUid]): a binder handed on by mistake reaches nothing.
 */
object DirectIpc {
    private const val TAG = "DirectIpc"

    /** Whether [msg] came from this app (either of its processes): [Message.sendingUid], which Android sets for a Messenger's messages. */
    internal fun fromThisApp(msg: Message): Boolean = msg.sendingUid == android.os.Process.myUid()

    /** A clock that only goes forward, in ms (for the idle timeouts of [Client.call]). */
    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000

    /**
     * Answers requests: [handle] runs off the main thread and returns the reply's JSON. Whatever a
     * request makes [handle] throw, an error too (memory or a stack run out), is its answer, never
     * the end of the process: the other process is told it failed.
     *
     * Each request is counted in [requests] from when it is taken until its answer has gone (a restart
     * of the process waits for them); while that is closed, a request is not taken and is answered at
     * once that it did not go ([Client.UNREACHABLE]). [afterReply] runs once a request's answer has
     * gone (and it is no longer counted), never for one not taken; what it throws is logged only.
     */
    class Server(
        private val afterReply: (what: Int, request: JSONObject) -> Unit = { _, _ -> },
        private val handle: suspend (what: Int, request: JSONObject, progress: (JSONObject) -> Unit) -> JSONObject,
    ) {
        /**
         * Sends a request's interim [DirectWire.PROGRESS] messages, one at a time and never on the
         * thread that reports them (the library's own, which a Binder send must not hold up).
         */
        private val progressOut = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "imessage-ipc-progress").apply { isDaemon = true }
        }
        /** The requests being answered ([RequestGate]). */
        internal val requests = RequestGate()
        private val thread = HandlerThread("imessage-ipc-server").apply { start() }
        private val failures = CoroutineExceptionHandler { _, e -> Log.e(TAG, "a request's work failed (${e::class.java.simpleName})") }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + failures)

        val messenger = Messenger(
            object : Handler(thread.looper) {
                override fun handleMessage(msg: Message) {
                    if (!fromThisApp(msg)) {
                        Log.w(TAG, "request ${msg.what} from uid ${msg.sendingUid} dropped: not this app")
                        return
                    }
                    val replyTo = msg.replyTo ?: return
                    val id = msg.data.getLong(DirectWire.KEY_ID)
                    val what = msg.what
                    // An interim message is never a request.
                    if (what == DirectWire.PROGRESS || what == DirectWire.REPLY) {
                        Log.w(TAG, "message $what is no request: dropped")
                        return
                    }
                    val request = try {
                        JSONObject(msg.data.getString(DirectWire.KEY_JSON) ?: "{}")
                    } catch (e: org.json.JSONException) {
                        JSONObject()
                    }
                    // Counted from here (a restart waits for it); not taken at all while one is under way.
                    val taken = requests.enter()
                    scope.launch {
                        try {
                            val reply = if (!taken) {
                                DirectWire.error(Client.UNREACHABLE)
                            } else {
                                try {
                                    handle(what, request) { p -> progress(replyTo, id, p) }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.w(TAG, "request $what failed (${e::class.java.simpleName})")
                                    DirectWire.failure(e)
                                } catch (e: Throwable) {
                                    Log.e(TAG, "request $what failed (${e::class.java.simpleName})")
                                    DirectWire.failure(e)
                                }
                            }
                            send(replyTo, id, reply)
                        } finally {
                            if (taken) requests.leave()
                        }
                        if (taken) {
                            try {
                                afterReply(what, request)
                            } catch (e: Throwable) {
                                Log.w(TAG, "after request $what: ${e::class.java.simpleName}")
                            }
                        }
                    }
                }
            },
        )

        private fun send(replyTo: Messenger, id: Long, reply: JSONObject) {
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

        /**
         * Hands [p] to the asking process as request [id]'s interim message, on [progressOut]: never
         * throws, whatever happens (the transfer goes on either way).
         */
        private fun progress(replyTo: Messenger, id: Long, p: JSONObject) {
            try {
                val json = p.toString()
                progressOut.execute {
                    try {
                        replyTo.send(
                            Message.obtain(null, DirectWire.PROGRESS).apply {
                                data = Bundle().apply {
                                    putLong(DirectWire.KEY_ID, id)
                                    putString(DirectWire.KEY_JSON, json)
                                }
                            },
                        )
                    } catch (e: Throwable) {
                        // The asking process went away, or the message could not go: only progress.
                    }
                }
            } catch (e: Throwable) {
                // Closed, or out of memory: only progress.
            }
        }

        fun close() {
            thread.quitSafely()
            progressOut.shutdown()
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

        /** Who hears each open request's [DirectWire.PROGRESS], by request id; removed when its reply comes. */
        private val progressListeners = ConcurrentHashMap<Long, (JSONObject) -> Unit>()

        /** When each open request with an idle timeout last heard of its progress ([monotonicMs]). */
        private val lastHeard = ConcurrentHashMap<Long, Long>()
        private val nextId = AtomicLong(1)
        private val bindLock = Mutex()

        @Volatile
        private var remote: Messenger? = null
        private var connecting: CompletableDeferred<Messenger?>? = null

        private val replies = Messenger(
            object : Handler(thread.looper) {
                override fun handleMessage(msg: Message) {
                    if (!fromThisApp(msg)) return
                    when (msg.what) {
                        DirectWire.REPLY -> {
                            val id = msg.data.getLong(DirectWire.KEY_ID)
                            val json = try {
                                JSONObject(msg.data.getString(DirectWire.KEY_JSON) ?: "{}")
                            } catch (e: org.json.JSONException) {
                                DirectWire.error("Unreadable answer from the other process.")
                            }
                            // No progress after its answer: one that comes late (Binder keeps no order between threads) is dropped.
                            progressListeners.remove(id)
                            lastHeard.remove(id)
                            pending.remove(id)?.complete(json)
                        }
                        DirectWire.PROGRESS -> progressOf(msg)
                    }
                }
            },
        )

        /** A [DirectWire.PROGRESS] message: only for a request still open with a listener; what the listener throws stays here. */
        private fun progressOf(msg: Message) {
            try {
                val id = msg.data.getLong(DirectWire.KEY_ID)
                val listener = progressListeners[id] ?: return
                if (!pending.containsKey(id)) return
                val json = JSONObject(msg.data.getString(DirectWire.KEY_JSON) ?: return)
                lastHeard.computeIfPresent(id) { _, _ -> monotonicMs() }
                listener(json)
            } catch (e: Throwable) {
                Log.w(TAG, "progress not taken (${e::class.java.simpleName})")
            }
        }

        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val m = binder?.let { Messenger(it) }
                remote = m
                connecting?.complete(m)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // The other process died: every open request fails now (the caller retries or
                // shows the error); the next request binds again once Android restarts it. The
                // connection made before is done with: the next request waits for the new one.
                remote = null
                connecting = null
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
                // Connected again while this was set up.
                remote?.let { d.complete(it) }
            }
            withTimeoutOrNull(BIND_TIMEOUT_MS) { wait.await() }
        }

        /**
         * The other side's answer, or an [DirectWire.error] when it cannot be reached in [timeoutMs].
         * [onProgress] hears the request's [DirectWire.PROGRESS] messages until its answer comes. With
         * [idleMs] the wait ends [idleMs] after the last of them (or after the request, before the
         * first), but never later than [timeoutMs] after the request: a long transfer that keeps
         * moving is waited for, one that stalls is not.
         */
        suspend fun call(what: Int, request: JSONObject, timeoutMs: Long, onProgress: ((JSONObject) -> Unit)? = null, idleMs: Long? = null): JSONObject {
            val m = connect() ?: return DirectWire.error(UNREACHABLE)
            val id = nextId.getAndIncrement()
            val answer = CompletableDeferred<JSONObject>()
            pending[id] = answer
            val started = monotonicMs()
            if (idleMs != null) lastHeard[id] = started
            if (onProgress != null) {
                progressListeners[id] = onProgress
            } else if (idleMs != null) {
                progressListeners[id] = { }
            }
            try {
                return await(id, answer, started, timeoutMs, idleMs, m, what, request)
            } finally {
                progressListeners.remove(id)
                lastHeard.remove(id)
            }
        }

        private suspend fun await(
            id: Long,
            answer: CompletableDeferred<JSONObject>,
            started: Long,
            timeoutMs: Long,
            idleMs: Long?,
            m: Messenger,
            what: Int,
            request: JSONObject,
        ): JSONObject {
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
                // Not handed over at all (a one-way send throws only then): it certainly did not
                // go, so it is never reported as one that may have ([DIED]).
                pending.remove(id)
                if (e !is TransactionTooLargeException) {
                    // The other process is gone: the next request binds again.
                    remote = null
                    bindLock.withLock { connecting = null }
                }
                return notHandedOver(e)
            }
            if (idleMs == null) {
                return withTimeoutOrNull(timeoutMs) { answer.await() } ?: run {
                    pending.remove(id)
                    DirectWire.error(NO_ANSWER)
                }
            }
            val deadline = started + timeoutMs
            while (true) {
                val now = monotonicMs()
                val idleEnd = (lastHeard[id] ?: started) + idleMs
                val wait = minOf(deadline, idleEnd) - now
                if (wait <= 0) {
                    pending.remove(id)
                    return DirectWire.error(NO_ANSWER)
                }
                withTimeoutOrNull(wait) { answer.await() }?.let { return it }
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

            /** The other process could not be reached, or the request could not be handed to it: it did not go. */
            const val UNREACHABLE = "LightDecoder's iMessage part could not be reached."

            /** The request was too large to be handed to the other process: it did not go, and never will as it is. */
            const val TOO_LARGE = "This is too large to send to LightDecoder's iMessage part."

            /** The other process stopped after the request was handed to it: it may have gone through. */
            const val DIED = "LightDecoder's iMessage part stopped while working on this."

            /** The answer to a request whose send threw [e]: it was never handed over. */
            fun notHandedOver(e: RemoteException): JSONObject =
                DirectWire.error(if (e is TransactionTooLargeException) TOO_LARGE else UNREACHABLE)
            const val NO_ANSWER = "LightDecoder's iMessage part did not answer in time."
        }
    }
}
