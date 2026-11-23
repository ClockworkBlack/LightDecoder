package com.lightdecoder.imessage.facetime

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Putting a new iMessage session in the place of the one there is (registering again): a call is
 * on the old one, and its join holds a pointer into it, so the swap is done under the call code's
 * guard ([CallBridge.guard]: never while a join runs) and only while no call holds the line. That
 * is looked at again inside the guard, each time: a call that began after registering was started
 * (it takes minutes, and a call rings in a moment) puts the swap off until it is over.
 */
object SessionSwap {

    enum class Decision { SWAP, WAIT }

    /** Pure: what to do with the new session, [callBusy] being whether a call holds the line right now. */
    fun decide(callBusy: Boolean): Decision = if (callBusy) Decision.WAIT else Decision.SWAP

    /**
     * Runs [swap] under [guard] as soon as [busy] says no call holds the line (asked inside the
     * guard, right before), looking again every [pollMs]. True when it had to wait.
     */
    suspend fun whenFree(guard: Mutex, busy: () -> Boolean, pollMs: Long = POLL_MS, swap: () -> Unit): Boolean {
        var waited = false
        while (true) {
            val done = guard.withLock {
                when (decide(busy())) {
                    Decision.SWAP -> {
                        swap()
                        true
                    }
                    Decision.WAIT -> false
                }
            }
            if (done) return waited
            waited = true
            delay(pollMs)
        }
    }

    private const val POLL_MS = 1_000L
}
