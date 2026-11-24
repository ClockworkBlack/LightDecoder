package com.lightdecoder.imessage.direct

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject

/** How the main process took one [DirectWire.UPDATE]. */
enum class Delivery {
    /** Stored. */
    STORED,

    /** It can never be stored ([DirectWire.isRejected]). */
    REJECTED,

    /** Not stored this time: the main process failed or did not answer while it was handed over. */
    FAILED,

    /** The main process could not be reached: nothing it is handed is stored until it can be. */
    UNREACHABLE,

    /**
     * Taken, and dropped on purpose: its sender is on the user's block list ([DirectWire.blocked]).
     * Settled as a stored one is, but the sender is not told it arrived (no delivery receipt).
     */
    BLOCKED,
    ;

    /** Whether the sender may be told their message arrived (a delivery receipt): never for one dropped as blocked. */
    val acknowledges: Boolean get() = this != BLOCKED

    companion object {
        /** The [Delivery] the main process's answer [reply] says. */
        fun of(reply: JSONObject): Delivery {
            val error = DirectWire.errorOf(reply) ?: return if (DirectWire.isBlocked(reply)) BLOCKED else STORED
            return when {
                DirectWire.isRejected(reply) -> REJECTED
                error == DirectIpc.Client.UNREACHABLE -> UNREACHABLE
                else -> FAILED
            }
        }
    }
}

/**
 * Hands one of Apple's changes to the main process until it is taken (in the ":imessage" process,
 * [DirectEngine]), with a wait growing to about a minute between tries. It is given up on (so that
 * the ones after it, which wait for it, are stored) when it can never be stored ([Delivery.REJECTED]
 * [MAX_REJECTED_TRIES] times), or when the main process kept failing at it for [GIVE_UP_MS]. While
 * the main process cannot be reached at all ([Delivery.UNREACHABLE]) it is never given up on: no
 * change after it would be stored either. Pure; tested in DirectWireTest.
 */
object UpdateDelivery {
    const val MAX_REJECTED_TRIES = 3
    const val GIVE_UP_MS = 10 * 60_000L

    /** True once [deliver] stored it (or dropped it as blocked), false when it was given up on. */
    suspend fun untilSettled(
        deliver: suspend () -> Delivery,
        pause: suspend (Long) -> Unit = { delay(it) },
        now: () -> Long = System::currentTimeMillis,
    ): Boolean = settle(deliver, pause, now) != null

    /** How it was settled: [Delivery.STORED] or [Delivery.BLOCKED]; null when it was given up on. */
    suspend fun settle(
        deliver: suspend () -> Delivery,
        pause: suspend (Long) -> Unit = { delay(it) },
        now: () -> Long = System::currentTimeMillis,
    ): Delivery? {
        var attempt = 0
        var rejected = 0
        var failingSince = -1L
        while (true) {
            val delivery = try {
                deliver()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Delivery.FAILED
            }
            when (delivery) {
                Delivery.STORED, Delivery.BLOCKED -> return delivery
                Delivery.REJECTED -> if (++rejected >= MAX_REJECTED_TRIES) return null
                Delivery.FAILED -> {
                    val t = now()
                    if (failingSince < 0) failingSince = t else if (t - failingSince >= GIVE_UP_MS) return null
                }
                Delivery.UNREACHABLE -> Unit
            }
            pause(1000L shl attempt.coerceAtMost(6))
            attempt++
        }
    }
}
