package com.lightdecoder.imessage.facetime

/**
 * How often one person may make this phone ring, and be told of as a missed call: anyone who knows
 * the user's number can call, so a caller rings at most once in [minGapMs] and [maxRingsPerHour]
 * times an hour (after that their calls only show as missed), and is noted as missed at most
 * [maxNoticesPerHour] times an hour (after that their calls are dropped). The same call's events
 * coming again within [minGapMs] are that call, not another. By the caller's address; at most
 * [maxCallers] remembered (the one longest quiet forgotten first). In memory: a restart starts again.
 *
 * Addresses cost a caller nothing, so everyone together is limited too: the phone rings at most
 * [maxAllRingsPerHour] times an hour and notes at most [maxAllNoticesPerHour] missed calls an hour
 * whoever calls, and callers the main process does not know (no contact, no chat the user wrote in)
 * share [maxUnknownRingsPerHour] rings an hour between them ([unknownRing]).
 */
class RingLimiter(
    private val minGapMs: Long = MIN_GAP_MS,
    private val maxRingsPerHour: Int = MAX_RINGS_PER_HOUR,
    private val maxNoticesPerHour: Int = MAX_NOTICES_PER_HOUR,
    private val maxCallers: Int = 200,
    private val windowMs: Long = 60 * 60 * 1000L,
    private val maxAllRingsPerHour: Int = MAX_ALL_RINGS_PER_HOUR,
    private val maxAllNoticesPerHour: Int = MAX_ALL_NOTICES_PER_HOUR,
    private val maxUnknownRingsPerHour: Int = MAX_UNKNOWN_RINGS_PER_HOUR,
) {
    enum class Verdict { RING, MISSED_ONLY, DROP }

    private class Caller {
        val rings = ArrayDeque<Long>()
        val notices = ArrayDeque<Long>()
    }

    private val callers = LinkedHashMap<String, Caller>(16, 0.75f, true)

    /** Everyone's rings and notices, and the rings of callers nobody knows, within the window. */
    private val allRings = ArrayDeque<Long>()
    private val allNotices = ArrayDeque<Long>()
    private val unknownRings = ArrayDeque<Long>()

    /** When each call was last rung or noted, by its guid. */
    private val calls = LinkedHashMap<String, Long>(16, 0.75f, true)

    /** What a ring of call [guid] from [caller] at [now] may do; counted as it is answered. */
    @Synchronized
    fun ring(caller: String, guid: String, now: Long): Verdict {
        val c = prune(caller, now)
        if (sameCall(guid, now)) return Verdict.DROP
        if (c.notices.size >= maxNoticesPerHour) return Verdict.DROP
        val tooSoon = c.rings.lastOrNull()?.let { now - it < minGapMs } == true
        if (tooSoon || c.rings.size >= maxRingsPerHour || allRings.size >= maxAllRingsPerHour) {
            if (allNotices.size >= maxAllNoticesPerHour) return Verdict.DROP
            c.notices.addLast(now)
            allNotices.addLast(now)
            note(guid, now)
            return Verdict.MISSED_ONLY
        }
        c.rings.addLast(now)
        allRings.addLast(now)
        note(guid, now)
        return Verdict.RING
    }

    /**
     * Whether a call from someone the main process does not know may ring at [now]: all such
     * callers share one allowance. Counted when it may.
     */
    @Synchronized
    fun unknownRing(now: Long): Boolean {
        trim(unknownRings, now)
        if (unknownRings.size >= maxUnknownRingsPerHour) return false
        unknownRings.addLast(now)
        return true
    }

    /** Whether call [guid] from [caller], which will not ring, may be noted as missed at [now]; counted when it may. */
    @Synchronized
    fun notice(caller: String, guid: String, now: Long): Boolean {
        val c = prune(caller, now)
        if (sameCall(guid, now)) return false
        if (c.notices.size >= maxNoticesPerHour || allNotices.size >= maxAllNoticesPerHour) return false
        c.notices.addLast(now)
        allNotices.addLast(now)
        note(guid, now)
        return true
    }

    private fun sameCall(guid: String, now: Long): Boolean = calls[guid]?.let { now - it in 0 until minGapMs } == true

    private fun note(guid: String, now: Long) {
        calls[guid] = now
        while (calls.size > MAX_CALLS) calls.remove(calls.keys.first())
    }

    private fun prune(caller: String, now: Long): Caller {
        val c = callers.getOrPut(caller) { Caller() }
        trim(c.rings, now)
        trim(c.notices, now)
        trim(allRings, now)
        trim(allNotices, now)
        while (callers.size > maxCallers) callers.remove(callers.keys.first())
        return c
    }

    private fun trim(times: ArrayDeque<Long>, now: Long) {
        while (times.firstOrNull()?.let { now - it >= windowMs } == true) times.removeFirst()
    }

    companion object {
        const val MIN_GAP_MS = 10_000L
        const val MAX_RINGS_PER_HOUR = 6
        const val MAX_NOTICES_PER_HOUR = 20

        /** Everyone together: rings and missed-call notes an hour. */
        const val MAX_ALL_RINGS_PER_HOUR = 30
        const val MAX_ALL_NOTICES_PER_HOUR = 60

        /** Callers the main process does not know, together: rings an hour. */
        const val MAX_UNKNOWN_RINGS_PER_HOUR = 8
        private const val MAX_CALLS = 400
    }
}
