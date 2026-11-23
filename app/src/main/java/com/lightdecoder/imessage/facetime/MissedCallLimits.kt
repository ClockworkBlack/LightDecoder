package com.lightdecoder.imessage.facetime

/**
 * What missed FaceTime calls may do in the main process, whoever they are from: anyone who knows
 * the user's number can call, from as many addresses as they like, so missed-call notices are
 * limited for everyone together ([maxNoticesPerHour] an hour), more tightly for callers the user
 * does not know ([maxUnknownNoticesPerHour] between them all), and such callers' missed calls
 * make at most [maxNewChatsPerHour] new chats an hour. In memory: a restart starts again. Pure.
 */
class MissedCallLimits(
    private val maxNoticesPerHour: Int = MAX_NOTICES_PER_HOUR,
    private val maxUnknownNoticesPerHour: Int = MAX_UNKNOWN_NOTICES_PER_HOUR,
    private val maxNewChatsPerHour: Int = MAX_NEW_CHATS_PER_HOUR,
    private val windowMs: Long = 60 * 60 * 1000L,
) {
    private val notices = ArrayDeque<Long>()
    private val unknownNotices = ArrayDeque<Long>()
    private val newChats = ArrayDeque<Long>()

    /** Whether a missed call from a caller who is [known] or not may be told in a notice at [now]; counted when it may. */
    @Synchronized
    fun notice(known: Boolean, now: Long): Boolean {
        trim(notices, now)
        trim(unknownNotices, now)
        if (notices.size >= maxNoticesPerHour) return false
        if (!known && unknownNotices.size >= maxUnknownNoticesPerHour) return false
        notices.addLast(now)
        if (!known) unknownNotices.addLast(now)
        return true
    }

    /** Whether a missed call from an unknown caller may make a new chat at [now]; counted when it may. */
    @Synchronized
    fun newChat(now: Long): Boolean {
        trim(newChats, now)
        if (newChats.size >= maxNewChatsPerHour) return false
        newChats.addLast(now)
        return true
    }

    private fun trim(times: ArrayDeque<Long>, now: Long) {
        // A clock set back empties nothing early and blocks nothing for longer than the window.
        while (times.firstOrNull()?.let { now - it >= windowMs || now < it } == true) times.removeFirst()
    }

    companion object {
        const val MAX_NOTICES_PER_HOUR = 30
        const val MAX_UNKNOWN_NOTICES_PER_HOUR = 8
        const val MAX_NEW_CHATS_PER_HOUR = 5

        /**
         * Whether a missed call's notice makes no sound: the call engine said so ([engineQuiet]), the
         * chat's alerts are hidden ([chatMuted]), or unknown callers are silenced ([silenceUnknown])
         * and this caller is not [known], on whichever way the call came to be missed.
         */
        fun quiet(engineQuiet: Boolean, chatMuted: Boolean, silenceUnknown: Boolean, known: Boolean): Boolean =
            engineQuiet || chatMuted || (silenceUnknown && !known)
    }
}
