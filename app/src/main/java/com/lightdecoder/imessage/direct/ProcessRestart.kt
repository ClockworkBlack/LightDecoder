package com.lightdecoder.imessage.direct

import android.content.Context
import android.util.Log
import com.lightdecoder.imessage.facetime.GUARD_WAIT_MS
import com.lightdecoder.imessage.facetime.GuardBusy
import com.lightdecoder.imessage.facetime.LOCK_POLL_MS
import com.lightdecoder.imessage.facetime.lockWithin
import com.lightdecoder.imessage.facetime.withLockWithin
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Restarts the ":imessage" process, the way the optional Apple Account is turned on and off (SPEC2
 * §4.4, §4.5): the library builds its session from what is on disk only when it starts, so iCloud's
 * services come (from the saved account) or go (without it) with a fresh start, and nothing of the
 * registration changes. iMessage's service is started first ([DirectService] is sticky): Android
 * brings it back by itself, and the main process's link binds to it again on its next request
 * ([DirectLink] also starts it once soon after). Android allows that start only while one of
 * LightDecoder's screens is in sight, so a restart is only ever asked for then ([AppleAccount]);
 * should Android refuse it all the same, nothing else is done. Only ever in the ":imessage" process,
 * and only through [DirectEngine.restartForAccount], which goes by [whenFree].
 */
object ProcessRestart {
    private const val TAG = "ProcessRestart"

    /** How long a restart waits for a start or a registration under way before it is kept back. */
    const val LOCK_WAIT_MS = 5_000L

    /** How long a restart waits for the main process's requests being answered before it is kept back. */
    const val REQUEST_WAIT_MS = 5_000L

    /** Ends this process. Tests put something else here. */
    @Volatile
    internal var exit: () -> Unit = { android.os.Process.killProcess(android.os.Process.myPid()) }

    /** How a restart asked for went ([whenFree]). */
    enum class Outcome {
        /** The process ends now (iMessage's service was started first, so it comes back). */
        RESTARTED,

        /**
         * Not wanted now (a registration since made it needless, or a sign-in began meanwhile):
         * nothing was done.
         */
        NOT_WANTED,

        /**
         * Something held it (a start or a registration, a FaceTime join or call, a request of the main
         * process being answered): nothing was done, and it waits for the next moment one of
         * LightDecoder's screens is in sight.
         */
        KEPT_BACK,

        /**
         * Android refused to start iMessage's service (in the background, Android 12 and later), or a
         * step of the restart failed: the process goes on as it was, nothing held, nobody told.
         */
        FAILED,
    }

    /**
     * Starts iMessage's service, so that Android brings it back once the process has ended. False when
     * Android refused (from the background on Android 12 and later) or iMessage is not set up; never
     * throws.
     */
    fun startService(context: Context): Boolean = try {
        DirectService.start(context.applicationContext)
    } catch (e: RuntimeException) {
        Log.w(TAG, "iMessage's service was not started (${e::class.java.simpleName})")
        false
    }

    /**
     * One restart (§4.4 step 6, S9), in this order:
     * 1. iMessage's service is started ([start]). Refused, or failed: [Outcome.FAILED], and nothing
     *    else is done (no lock taken, nothing held back, nobody told).
     * 2. The engine's [engineLock], so a start or a registration under way (which holds it from the
     *    new identity to the registration) is let finish, [lockWaitMs] at most; then FaceTime's [guard]
     *    (no join running), and [busy] looked at again under it; then [wanted] (a registration since
     *    may have made the restart needless, or a sign-in begun meanwhile).
     * 3. A moment no request of the main process is being answered ([requests]) is waited for,
     *    [requestWaitMs] at most, and none is begun from then on.
     * 4. The event being read is let finish ([quiesce], which says whether it did in time: kept back
     *    when not, so the process never ends in the middle of one), [last] is done, and the process
     *    ends ([end]).
     *
     * A FaceTime call can begin without the guard (a ring comes in, a call is placed, an answered one
     * waits for the guard to join), so [busy] is looked at again after the waits of step 3 and 4, and
     * once more right before [end]: a call on by then keeps it back.
     *
     * Both locks are only ever taken by looking ([lockWithin]), so neither is left held by a wait that
     * ran out. Unless [end] was reached, the requests and Apple's events are taken again ([resume]),
     * however the attempt ended (kept back, a step that failed, the caller cancelled).
     */
    internal suspend fun whenFree(
        start: () -> Boolean,
        engineLock: Mutex,
        guard: Mutex,
        busy: () -> Boolean,
        wanted: () -> Boolean,
        requests: RequestGate?,
        quiesce: suspend () -> Boolean,
        resume: () -> Unit,
        last: suspend () -> Unit,
        lockWaitMs: Long = LOCK_WAIT_MS,
        guardWaitMs: Long = GUARD_WAIT_MS,
        requestWaitMs: Long = REQUEST_WAIT_MS,
        end: () -> Unit,
    ): Outcome {
        val started = try {
            start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!started) return Outcome.FAILED
        return try {
            engineLock.withLockWithin(lockWaitMs) {
                guard.withLockWithin(guardWaitMs) {
                    when {
                        busy() -> Outcome.KEPT_BACK
                        !wanted() -> Outcome.NOT_WANTED
                        else -> {
                            var outcome: Outcome? = null
                            try {
                                outcome = toTheEnd(busy, requests, requestWaitMs, quiesce, last, end)
                                outcome
                            } finally {
                                if (outcome != Outcome.RESTARTED) {
                                    requests?.open()
                                    resume()
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: GuardBusy) {
            Outcome.KEPT_BACK
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the restart failed (${e::class.java.simpleName})")
            Outcome.FAILED
        }
    }

    /** [whenFree]'s steps 3 and 4, under both locks: [Outcome.RESTARTED] only once [end] returned. */
    private suspend fun toTheEnd(
        busy: () -> Boolean,
        requests: RequestGate?,
        requestWaitMs: Long,
        quiesce: suspend () -> Boolean,
        last: suspend () -> Unit,
        end: () -> Unit,
    ): Outcome {
        // A request being answered all along: nothing done.
        if (requests != null && !requests.close(requestWaitMs)) return Outcome.KEPT_BACK
        // The event being taken did not finish in time (it waits on the main process, say).
        if (!quiesce()) return Outcome.KEPT_BACK
        // A call that began during those waits (rung, placed, or answered and waiting to join).
        if (busy()) return Outcome.KEPT_BACK
        last()
        // And one that began while the main process was told: looked at right before the end.
        if (busy()) return Outcome.KEPT_BACK
        end()
        return Outcome.RESTARTED
    }
}

/**
 * The main process's requests being answered ([DirectIpc.Server]): each one is counted from when it
 * is taken until its answer has gone ([enter], [leave]), so a restart never ends the process under
 * one ([close] waits for none to be, a few seconds at most, taking new ones meanwhile); and once it
 * is closed none is begun ([enter] is false: it is answered at once that it did not go, and the
 * main process may ask again), for the moments left before the process ends.
 *
 * Closed for [holdMs] at most, as [EventGate]: a process still here that long after it was closed
 * for a restart did not restart, and takes requests again even had nobody said so.
 */
internal class RequestGate(
    private val holdMs: Long = EventGate.HOLD_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val count = AtomicInteger()

    /** When ([clock]) it was closed; null while requests are taken. */
    @Volatile
    private var closedAt: Long? = null

    /** Requests being answered now. */
    val inFlight: Int get() = count.get()

    val isClosed: Boolean get() = closedAt?.let { clock() - it < holdMs } ?: false

    /** One request is taken: true, and [leave] once its answer has gone; false while closed (not taken). */
    fun enter(): Boolean {
        count.incrementAndGet()
        if (isClosed) {
            count.decrementAndGet()
            return false
        }
        return true
    }

    fun leave() {
        count.decrementAndGet()
    }

    /**
     * Waits for a moment no request is being answered, [waitMs] at most (looked at every [pollMs];
     * new ones are taken meanwhile), and closes then: true, and until [open] (or [holdMs] from then)
     * no new one is taken. False when one was always being answered: not closed, nothing changed.
     */
    suspend fun close(waitMs: Long, pollMs: Long = LOCK_POLL_MS): Boolean {
        var waited = 0L
        while (true) {
            if (count.get() == 0) {
                closedAt = clock()
                // One taken just as it closed: open again, and wait for it too.
                if (count.get() == 0) return true
                closedAt = null
            }
            if (waited >= waitMs) return false
            val step = minOf(pollMs.coerceAtLeast(1), waitMs - waited)
            delay(step)
            waited += step
        }
    }

    /** Requests are taken again (the restart [close] was for did not happen). */
    fun open() {
        closedAt = null
    }
}

/**
 * Lets Apple's events be taken one at a time ([take]), and stops the taking for a restart of the
 * process: [quiesce] lets the event being taken finish and takes no more, so the process never ends
 * in the middle of one (the rest wait in the library's journal and come again after the restart);
 * [resume], for a restart that did not happen, takes them again, the one held back first.
 *
 * The quiet never outlasts [holdMs] either: a restart takes seconds, so a process still here that
 * long after it was stopped for one did not restart, and its events are taken again even had nobody
 * said so.
 */
internal class EventGate(
    private val holdMs: Long = HOLD_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** When ([clock]) the taking was stopped; null while events are taken. */
    private val quietSince = MutableStateFlow<Long?>(null)
    private val taking = Mutex()

    /** Whether events are held back now. */
    val isQuiet: Boolean get() = quietSince.value?.let { clock() - it < holdMs } ?: false

    /** Runs [block] for one event, under the gate: it waits while the gate is quiet, and is never begun once it is. */
    suspend fun take(block: suspend () -> Unit) {
        while (true) {
            open()
            val ran = taking.withLock {
                if (isQuiet) {
                    false
                } else {
                    block()
                    true
                }
            }
            if (ran) return
        }
    }

    /** Waits until events may be taken: [resume], or [holdMs] after [quiesce] at most. */
    private suspend fun open() {
        while (true) {
            val since = quietSince.value ?: return
            val left = holdMs - (clock() - since)
            if (left <= 0) {
                // Stopped for a restart that did not end the process: taken again.
                quietSince.compareAndSet(since, null)
                return
            }
            withTimeoutOrNull(left) { quietSince.first { it != since } }
        }
    }

    /**
     * No event is begun from now; the one being taken is waited for, [waitMs] at most. True when none
     * is being taken any more. Until [resume] (or [holdMs] from now), events wait.
     */
    suspend fun quiesce(waitMs: Long): Boolean {
        quietSince.value = clock()
        if (!taking.lockWithin(waitMs)) return false
        taking.unlock()
        return true
    }

    /** Events are taken again (the restart [quiesce] was for did not happen). */
    fun resume() {
        quietSince.value = null
    }

    companion object {
        /** The longest events are held back for one restart (it takes seconds: the event being read, the main process told). */
        const val HOLD_MS = 60_000L
    }
}
