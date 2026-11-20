package com.lightdecoder.archive

import android.content.Context

/**
 * What the user chose for a conversation here, kept apart from what its service reports of it, so a
 * sync from the phone does not undo it ([ArchiveRepository]): "Pin" / "Unpin" (the user's choice wins
 * over the phone's pin from then on), and "Mark as Unread" (unread until it is read again here, or
 * on another of the user's devices that says so). One file per archive ([name]: its database's).
 * App-private, never backed up with anything else.
 */
internal class LocalMarks(context: Context, name: String) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE_PREFIX + name.filter { it.isLetterOrDigit() || it in "._-" }, Context.MODE_PRIVATE)

    /** The user's own pin for [conversationId]: pinned, unpinned, or null when they made no choice (the service's stands). */
    fun pinned(conversationId: String): Boolean? =
        if (prefs.contains(PIN + conversationId)) prefs.getBoolean(PIN + conversationId, false) else null

    fun setPinned(conversationId: String, pinned: Boolean) {
        prefs.edit().putBoolean(PIN + conversationId, pinned).apply()
    }

    /** Whether the user marked [conversationId] unread, and has not read it since. */
    fun unread(conversationId: String): Boolean = prefs.getBoolean(UNREAD + conversationId, false)

    fun setUnread(conversationId: String, unread: Boolean) {
        if (unread == unread(conversationId)) return
        prefs.edit().apply { if (unread) putBoolean(UNREAD + conversationId, true) else remove(UNREAD + conversationId) }.apply()
    }

    /** The conversation is gone: nothing of it is kept. */
    fun forget(conversationId: String) {
        if (!prefs.contains(PIN + conversationId) && !prefs.contains(UNREAD + conversationId)) return
        prefs.edit().remove(PIN + conversationId).remove(UNREAD + conversationId).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE_PREFIX = "archive_marks_"
        const val PIN = "pin:"
        const val UNREAD = "unread:"
    }
}
