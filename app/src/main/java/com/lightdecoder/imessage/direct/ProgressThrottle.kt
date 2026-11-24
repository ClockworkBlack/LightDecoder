package com.lightdecoder.imessage.direct

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext

/**
 * Passes a transfer's progress on ([emit]) no more often than every [minMs], and only once it moved
 * by [minFraction] of the whole since the last one passed on; the first and the final value (all
 * done) always go, and [finish] hands on the last one held back. Never throws: whatever [emit] or
 * the clock throws stays here (the library's transfer goes on either way). Safe from any thread.
 */
class ProgressThrottle(
    private val minMs: Long = 200,
    private val minFraction: Double = 0.01,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val emit: (done: Long, total: Long) -> Unit,
) {
    private var lastMs = Long.MIN_VALUE
    private var lastFraction = -1.0
    private var lastTotal = -1L
    private var held: Pair<Long, Long>? = null
    private var finished = false

    /** [done] of [total] bytes: passed on, or held back. */
    fun offer(done: Long, total: Long) {
        try {
            val go = synchronized(this) {
                if (finished) return
                val d = done.coerceAtLeast(0)
                val t = total.coerceAtLeast(0)
                val fraction = if (t > 0) d.toDouble() / t else 0.0
                val now = clock()
                val final = t > 0 && d >= t
                val due = lastMs == Long.MIN_VALUE || t != lastTotal ||
                    (now - lastMs >= minMs && fraction - lastFraction >= minFraction)
                if (final || due) {
                    lastMs = now
                    lastFraction = fraction
                    lastTotal = t
                    held = null
                    if (final) finished = true
                    d to t
                } else {
                    held = d to t
                    null
                }
            } ?: return
            emit(go.first, go.second)
        } catch (e: Throwable) {
            // Only progress.
        }
    }

    /** The transfer ended: the value last held back, if any, goes now. */
    fun finish() {
        try {
            val last = synchronized(this) {
                finished = true
                held.also { held = null }
            } ?: return
            emit(last.first, last.second)
        } catch (e: Throwable) {
            // Only progress.
        }
    }
}

/**
 * One transfer's progress as the library reports it ([onProgress], on its own thread): the size cap
 * of a download ([maxBytes]: past it, [onOver] is called once and [over] is set) and the screen's
 * progress ([ui]) in one, all of it inside a catch of every [Throwable], so nothing ever goes back
 * into the library (a throw there would end the process).
 */
class TransferProgress(
    private val maxBytes: Long?,
    private val onOver: () -> Unit = {},
    private val ui: ProgressThrottle? = null,
) {
    val over = AtomicBoolean(false)

    fun onProgress(transferred: ULong, total: ULong) {
        try {
            val cap = maxBytes
            if (cap != null && (transferred > cap.toULong() || total > cap.toULong())) {
                if (over.compareAndSet(false, true)) onOver()
                return
            }
            ui?.offer(clampedLong(transferred), clampedLong(total))
        } catch (e: Throwable) {
            // Never into the library.
        }
    }

    private fun clampedLong(v: ULong): Long = if (v > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else v.toLong()
}

/**
 * The transfers the user may stop ([DirectWire.CANCEL]), by key ([DirectWire.transferKey],
 * [DirectWire.sendKey]): each one's coroutine while it runs ([run]). A send is stopped only until it is
 * [Handle.commit]ted (right before it goes to Apple): from then on [cancel] answers false, as the
 * message may already be on its way.
 */
class Transfers {
    /**
     * What a transfer the user stopped ends in: an ordinary failure (not a cancellation of the
     * request's own work), so its request is still answered, as stopped ([DirectWire.isStopped]).
     */
    class Stopped : Exception(STOPPED)

    class Handle internal constructor() {
        @Volatile
        internal var job: Job? = null

        private var committed = false
        private var cancelled = false

        /** The send goes to Apple now: it can no longer be stopped. False when it was stopped first (it must not go). */
        @Synchronized
        fun commit(): Boolean {
            if (cancelled) return false
            committed = true
            return true
        }

        @Synchronized
        internal fun stop(): Boolean {
            if (committed || cancelled) return false
            cancelled = true
            return true
        }

        /** Whether it was stopped by the user. */
        val stopped: Boolean @Synchronized get() = cancelled
    }

    private val running = ConcurrentHashMap<String, Handle>()

    /**
     * Runs [block] as the transfer [key] (null: one that cannot be stopped). A second transfer of a
     * key already running is run unregistered (the first keeps the key). A stop shows as [Stopped].
     */
    suspend fun <T> run(key: String?, block: suspend (Handle) -> T): T = coroutineScope {
        val handle = Handle()
        val work = async(start = CoroutineStart.LAZY) { block(handle) }
        handle.job = work
        val registered = key != null && running.putIfAbsent(key, handle) == null
        try {
            work.await()
        } catch (e: CancellationException) {
            // Its own work was stopped by the user; the request itself goes on, to answer so.
            if (handle.stopped && currentCoroutineContext()[Job]?.isActive == true) throw Stopped()
            throw e
        } finally {
            if (registered) running.remove(key, handle)
        }
    }

    /** Stops the transfer [key]: true when it was running and could still be stopped. */
    fun cancel(key: String): Boolean {
        val h = running[key] ?: return false
        if (!h.stop()) return false
        h.job?.cancel()
        return true
    }

    companion object {
        const val STOPPED = "Stopped."
    }

    /** Whether a transfer of [key] is running (tests). */
    internal fun isRunning(key: String): Boolean = running.containsKey(key)
}
