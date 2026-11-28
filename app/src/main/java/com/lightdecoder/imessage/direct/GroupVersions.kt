package com.lightdecoder.imessage.direct

import android.content.Context

/**
 * Each iMessage group's version, by conversation id: the highest Apple's events said it is at (a
 * change of its members or of its photo carries the group's version, rustpush's `group_version`,
 * Apple's "pv"). A change made here goes as the next one ([next]), as OpenBubbles counts them: one
 * more than the highest seen.
 *
 * The version is what lets a phone tell a newer change of a group from an older one: a change as of
 * a version the group is past already is not taken. Only changes carry it, so of a group this
 * phone never saw change (one others made long ago; any group after the app was installed again:
 * what is kept here does not survive that) the version is not known. OpenBubbles counts from 0
 * then, which is right only for a group nothing ever changed in: in any other, adding someone from
 * here went as a change long past, and the iPhones in it left it out. So only a group made here
 * ([noteNew]) starts at 0; one whose version is not known goes as [UNKNOWN_FIRST], past where a
 * group gets by people coming, going and changing its photo. Main process only.
 */
class GroupVersions(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The highest version seen for [conversationId], or null when none was. */
    fun of(conversationId: String): Long? =
        if (prefs.contains(conversationId)) prefs.getLong(conversationId, 0).takeIf { it >= 0 } else null

    /** [conversationId] was made here just now: nothing has changed in it, and its first change goes as 0. */
    @Synchronized
    fun noteNew(conversationId: String) {
        if (!prefs.contains(conversationId)) prefs.edit().putLong(conversationId, NEW).apply()
    }

    /** [conversationId] is at [version] (or higher, when a higher one was seen before). */
    @Synchronized
    fun note(conversationId: String, version: Long) {
        if (version < 0) return
        val known = of(conversationId)
        if (known == null || version > known) prefs.edit().putLong(conversationId, version).apply()
    }

    /**
     * The version a change made here goes as: one more than the highest seen; 0 in a group made here
     * that nothing changed in yet ([noteNew]); [UNKNOWN_FIRST] in one whose version was never seen.
     */
    fun next(conversationId: String): Long = if (prefs.contains(conversationId)) nextAfter(of(conversationId)) else UNKNOWN_FIRST

    /** Forgets [conversationId] (deleted here). */
    fun forget(conversationId: String) {
        prefs.edit().remove(conversationId).apply()
    }

    companion object {
        private const val FILE = "imessage_group_versions"

        /** Kept for a group made here that nothing changed in yet ([noteNew]): no version seen, the first is 0. */
        private const val NEW = -1L

        /**
         * What the first change from here goes as in a group whose version was never seen: beyond what
         * a group reaches by its changes (each counts one), and within what a change from someone
         * else may be ahead by here ([MAX_STEP]).
         */
        const val UNKNOWN_FIRST = 500L

        /** The version after [known]: 0 for none; never past a Long's end. */
        fun nextAfter(known: Long?): Long = when {
            known == null || known < 0 -> 0
            known == Long.MAX_VALUE -> Long.MAX_VALUE
            else -> known + 1
        }

        /**
         * How far past the highest version known a group's version from someone's change may be.
         * Each change counts one (OpenBubbles, iPhones), so a few missed changes are far within it.
         */
        const val MAX_STEP = 1_000L

        /** A version this high or higher is none a group reaches by counting its changes. */
        const val MAX_PLAUSIBLE = 1L shl 32

        /**
         * Whether a group's [version] from someone's change (anyone in the group can send any) is one to
         * take, given the [known] versions of it here (the group's, its photo's; null: none): at most
         * [MAX_STEP] past the highest of them, and below [MAX_PLAUSIBLE] unless one known is that high
         * already. One far ahead would hold every later change back (only a newer one is taken), so
         * that the group's photo, say, could never change again.
         */
        fun plausible(version: Long, vararg known: Long?): Boolean {
            if (version < 0) return false
            val base = known.filterNotNull().filter { it >= 0 }.maxOrNull() ?: 0L
            if (version >= MAX_PLAUSIBLE && base < MAX_PLAUSIBLE) return false
            return version <= if (base > Long.MAX_VALUE - MAX_STEP) Long.MAX_VALUE else base + MAX_STEP
        }
    }
}

/**
 * Each iMessage chat's own "Send Read Receipts" (the chat's details, as on an iPhone), by conversation
 * id: on or off for that chat, or, when the user never chose, the iMessage screen's setting
 * ([DirectPrefs.sendReadReceipts]). Main process only; the choice goes with each read receipt
 * ([DirectWire.read]), and the ":imessage" process honours it.
 */
class ReadReceiptChoices(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The chat's own choice, or null when it follows the iMessage screen's setting. */
    fun of(conversationId: String): Boolean? = if (prefs.contains(conversationId)) prefs.getBoolean(conversationId, false) else null

    /** Whether read receipts go to the other person of [conversationId]: its own choice, else [global]. */
    fun effective(conversationId: String, global: Boolean): Boolean = of(conversationId) ?: global

    /** The chat's own choice from now on; null to follow the iMessage screen's setting again. */
    fun set(conversationId: String, on: Boolean?) {
        if (on == null) prefs.edit().remove(conversationId).apply() else prefs.edit().putBoolean(conversationId, on).apply()
    }

    private companion object {
        const val FILE = "imessage_read_receipts"
    }
}
