package com.lightdecoder.imessage.facetime

import android.content.Context
import com.lightdecoder.R

/**
 * A FaceTime call's row in the chat: a line of the chat's own (as "Bob named the conversation ..."
 * is), saying what the call was ("FaceTime Video · 5 min", "Missed FaceTime Audio", "FaceTime
 * Video · Declined"), kept with what it needs to be drawn with its sign and tapped to call back
 * ([com.lightdecoder.imessage.direct.IMessageExtra.call]). Main process; the words are pure but for
 * the resources.
 */
object CallRows {

    /**
     * The row's message id: by the call's guid, so the same call handed over again is the same row.
     * Not an iMessage's id ("imsg:"): Apple knows no message by it, and nothing that works by a
     * message's GUID (a reply, a tapback, the chat's latest message as a send names it) may take it for one.
     */
    fun messageId(guid: String): String = PREFIX + guid

    const val PREFIX = "ftcall:"

    fun isCallRow(messageId: String): Boolean = messageId.startsWith(PREFIX)

    /** What the words need: the app's strings. */
    interface Texts {
        fun kind(video: Boolean): String
        fun answered(kind: String, duration: String): String
        fun missed(kind: String): String
        fun cancelled(kind: String): String
        fun declined(kind: String): String
        fun unanswered(kind: String): String
        fun elsewhere(kind: String): String
        fun failed(kind: String): String
        fun seconds(n: Int): String
        fun minutes(n: Int): String
        fun hours(h: Int, m: Int): String
    }

    class ResourceTexts(private val context: Context) : Texts {
        override fun kind(video: Boolean): String = context.getString(if (video) R.string.facetime_video else R.string.facetime_audio)
        override fun answered(kind: String, duration: String): String = context.getString(R.string.facetime_row_answered, kind, duration)
        override fun missed(kind: String): String = context.getString(R.string.facetime_row_missed, kind)
        override fun cancelled(kind: String): String = context.getString(R.string.facetime_row_cancelled, kind)
        override fun declined(kind: String): String = context.getString(R.string.facetime_row_declined, kind)
        override fun unanswered(kind: String): String = context.getString(R.string.facetime_row_unanswered, kind)
        override fun elsewhere(kind: String): String = context.getString(R.string.facetime_row_elsewhere, kind)
        override fun failed(kind: String): String = context.getString(R.string.facetime_row_failed, kind)
        override fun seconds(n: Int): String = context.getString(R.string.facetime_duration_sec, n)
        override fun minutes(n: Int): String = context.getString(R.string.facetime_duration_min, n)
        override fun hours(h: Int, m: Int): String = context.getString(R.string.facetime_duration_hr, h, m)
    }

    /** How long a call was, in words: "12 sec" under a minute, "5 min" under an hour, "1 hr 5 min" from there. */
    fun duration(texts: Texts, ms: Long): String {
        val seconds = (ms.coerceAtLeast(0) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return when {
            seconds < 60 -> texts.seconds(seconds)
            seconds < 3600 -> texts.minutes(seconds / 60)
            else -> texts.hours(seconds / 3600, (seconds % 3600) / 60)
        }
    }

    /** The row's words. */
    fun text(texts: Texts, video: Boolean, outcome: Outcome, durationMs: Long): String {
        val kind = texts.kind(video)
        return when (outcome) {
            Outcome.ANSWERED -> texts.answered(kind, duration(texts, durationMs))
            Outcome.MISSED -> texts.missed(kind)
            Outcome.CANCELLED -> texts.cancelled(kind)
            Outcome.DECLINED -> texts.declined(kind)
            Outcome.UNANSWERED -> texts.unanswered(kind)
            Outcome.ELSEWHERE -> texts.elsewhere(kind)
            Outcome.FAILED -> texts.failed(kind)
        }
    }

    /**
     * Whether a row saying [fresh] takes the place of the one stored for the same call ([stored]: its
     * outcome; null when the stored row is no call's). A call has one end: the same call handed over
     * again (Apple's journal after a restart, an old ring noted as missed) never rewrites what is
     * known of it, but for a call that was talked in after all.
     */
    fun replaces(stored: Outcome?, fresh: Outcome): Boolean = when {
        stored == null -> false
        stored == fresh -> false
        else -> rank(fresh) > rank(stored)
    }

    private fun rank(outcome: Outcome): Int = when (outcome) {
        Outcome.ANSWERED -> 2
        Outcome.MISSED -> 0
        else -> 1
    }
}

/**
 * Who can be called with FaceTime, as Apple last said, by address: asked again after [yesMs] for
 * someone who can (they may have left it), after [noMs] for someone who cannot (they may have got
 * an iPhone). At most [max] people (the one longest unasked forgotten first). In memory.
 */
class CapabilityCache(
    private val yesMs: Long = 12 * 60 * 60 * 1000L,
    private val noMs: Long = 30 * 60 * 1000L,
    private val max: Int = 500,
) {
    private val known = LinkedHashMap<String, Pair<Boolean, Long>>(16, 0.75f, true)

    /** What is known of [key] at [now]: true, false, or null (not asked, or asked too long ago). */
    @Synchronized
    fun get(key: String, now: Long): Boolean? {
        val (capable, at) = known[key] ?: return null
        val age = now - at
        if (age < 0 || age >= (if (capable) yesMs else noMs)) {
            known.remove(key)
            return null
        }
        return capable
    }

    @Synchronized
    fun put(key: String, capable: Boolean, now: Long) {
        known[key] = capable to now
        while (known.size > max) known.remove(known.keys.first())
    }

    @Synchronized
    fun clear() = known.clear()
}

/** When a chat shows its FaceTime buttons. Pure; tested in FaceTimeButtonsTest. */
object FaceTimeButtons {
    /**
     * Shown for a chat with one person that goes through the iMessage library ([reachable]: the
     * library is set up and can send to it), with "FaceTime" on, unless Apple said that person
     * cannot be called ([capable] false; null: not known yet, so shown). Never for a group.
     */
    fun shown(faceTimeOn: Boolean, reachable: Boolean, oneToOne: Boolean, capable: Boolean?): Boolean =
        faceTimeOn && reachable && oneToOne && capable != false
}
