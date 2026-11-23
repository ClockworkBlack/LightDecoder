package com.lightdecoder.imessage.facetime

/**
 * How many FaceTime events are taken in a minute, whoever sends them: at most [maxPerWindow] in
 * any [windowMs]. Every event from Apple is from someone else's phone and costs a look into the
 * library's call list, and many at once push calls out of that list; past the limit they are
 * acknowledged and dropped unread. Pure.
 */
class EventRate(val maxPerWindow: Int = MAX_PER_MINUTE, val windowMs: Long = 60_000L) {
    private val times = ArrayDeque<Long>()

    /** Whether one more event may be taken at [now]; counted when it may. */
    @Synchronized
    fun allow(now: Long): Boolean {
        // A clock set back never holds events off for longer than the window.
        while (times.firstOrNull()?.let { now - it >= windowMs || now < it } == true) times.removeFirst()
        if (times.size >= maxPerWindow) return false
        times.addLast(now)
        return true
    }

    companion object {
        /** A call is a handful of events; this is many calls' worth. */
        const val MAX_PER_MINUTE = 120
    }
}
