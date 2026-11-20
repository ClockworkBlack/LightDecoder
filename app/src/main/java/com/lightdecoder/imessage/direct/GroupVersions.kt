package com.lightdecoder.imessage.direct

import android.content.Context

/**
 * Each iMessage group's version, by conversation id: the highest Apple's events said it is at (a
 * change of its members or of its photo carries the group's version, rustpush's `group_version`).
 * A change made here goes as the next one ([next]), as OpenBubbles counts them: one more than the
 * highest seen, 0 when none was ever seen. Main process only.
 */
class GroupVersions(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The highest version seen for [conversationId], or null when none was. */
    fun of(conversationId: String): Long? = if (prefs.contains(conversationId)) prefs.getLong(conversationId, 0) else null

    /** [conversationId] is at [version] (or higher, when a higher one was seen before). */
    @Synchronized
    fun note(conversationId: String, version: Long) {
        if (version < 0) return
        val known = of(conversationId)
        if (known == null || version > known) prefs.edit().putLong(conversationId, version).apply()
    }

    /** The version a change made here goes as: one more than the highest seen, 0 when none was ever seen. */
    fun next(conversationId: String): Long = nextAfter(of(conversationId))

    /** Forgets [conversationId] (deleted here). */
    fun forget(conversationId: String) {
        prefs.edit().remove(conversationId).apply()
    }

    companion object {
        private const val FILE = "imessage_group_versions"

        /** The version after [known]: 0 for none; never past a Long's end. */
        fun nextAfter(known: Long?): Long = when {
            known == null || known < 0 -> 0
            known == Long.MAX_VALUE -> Long.MAX_VALUE
            else -> known + 1
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
