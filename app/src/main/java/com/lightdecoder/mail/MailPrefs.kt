package com.lightdecoder.mail

import android.content.Context
import androidx.core.content.edit

/**
 * The Gmail tab's own choices and sync bookmarks, in a preferences file of
 * its own (nothing is shared with the other services). App-private and
 * excluded from backup like everything else.
 */
object MailPrefs {
    internal const val FILE = "mail"
    private const val KEY_NOTIFY = "notify_messages"
    private const val KEY_AUTO_CHECK = "auto_check"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** A notification for each new email. On by default. */
    fun notifyMessages(context: Context): Boolean = prefs(context).getBoolean(KEY_NOTIFY, true)

    fun setNotifyMessages(context: Context, on: Boolean) = prefs(context).edit { putBoolean(KEY_NOTIFY, on) }

    /** Check for new email in the background (about every 15 minutes, as Android allows). On by default. */
    fun autoCheck(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_CHECK, true)

    fun setAutoCheck(context: Context, on: Boolean) = prefs(context).edit { putBoolean(KEY_AUTO_CHECK, on) }

    /** Where the last sync of a mailbox folder ([MailBridge.Role]) stopped; null before the first. */
    internal fun folderState(context: Context, role: MailBridge.Role): MailBridge.FolderState? {
        val p = prefs(context)
        val validity = p.getLong("uidvalidity_${role.name}", -1)
        if (validity < 0) return null
        return MailBridge.FolderState(validity, p.getLong("lastuid_${role.name}", 0))
    }

    internal fun setFolderState(context: Context, role: MailBridge.Role, state: MailBridge.FolderState?) = prefs(context).edit(commit = true) {
        if (state == null) {
            remove("uidvalidity_${role.name}")
            remove("lastuid_${role.name}")
        } else {
            putLong("uidvalidity_${role.name}", state.uidValidity)
            putLong("lastuid_${role.name}", state.lastUid)
        }
    }

    /** Forgets the sync bookmarks (the account was removed). The user's choices stay. */
    internal fun clearSync(context: Context) {
        for (role in MailBridge.Role.entries) setFolderState(context, role, null)
    }
}
