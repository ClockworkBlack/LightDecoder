package com.lightdecoder.imessage

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The iMessage link's own choices ("Blue Bubbles" in the Messages tab's
 * menu), in a preferences file of its own so they never touch the other
 * links' settings.
 */
object IMessagePrefs {
    private const val FILE = "imessage"
    private const val KEY_ENABLED = "blue_bubbles"
    private const val KEY_HIDDEN_TEXT = "hidden_text_at"
    private const val KEY_NOTIFY = "notify_messages"
    private const val KEY_ID_SALT = "id_salt"
    private const val KEY_DELETED = "deleted_messages"
    private const val KEY_CLEARED_PREFIX = "cleared:"
    private const val MAX_DELETED = 500
    private val saltLock = Any()

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * "Blue Bubbles": OpenBubbles' iMessages are read into the Messages tab
     * and can be answered from there. Off until the user ticks it.
     */
    /** When OpenBubbles was last seen hiding message text (0: not since its last readable message). */
    fun hiddenTextAt(context: Context): Long = prefs(context).getLong(KEY_HIDDEN_TEXT, 0L)

    fun setHiddenTextAt(context: Context, at: Long) = prefs(context).edit { putLong(KEY_HIDDEN_TEXT, at) }

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit(commit = true) { putBoolean(KEY_ENABLED, on) }

    /** LightDecoder's own notification for each new iMessage. On by default. */
    fun notifyMessages(context: Context): Boolean = prefs(context).getBoolean(KEY_NOTIFY, true)

    fun setNotifyMessages(context: Context, on: Boolean) = prefs(context).edit { putBoolean(KEY_NOTIFY, on) }

    /**
     * Messages the user deleted here. OpenBubbles' notification shows a
     * chat's earlier lines again with every new message until the chat is
     * read, so without this they would come back. The newest
     * [MAX_DELETED] are kept.
     */
    fun deletedMessages(context: Context): Set<String> =
        prefs(context).getString(KEY_DELETED, null)?.split('\n')?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    fun addDeletedMessage(context: Context, messageId: String) = synchronized(saltLock) {
        val now = prefs(context).getString(KEY_DELETED, null)?.split('\n')?.filter { it.isNotEmpty() && it != messageId }.orEmpty()
        prefs(context).edit { putString(KEY_DELETED, (now + messageId).takeLast(MAX_DELETED).joinToString("\n")) }
    }

    /**
     * The time up to which a chat the user deleted here is cleared: lines of
     * OpenBubbles' notification from before it are not taken in again.
     * Keyed by the hashed chat id.
     */
    fun clearedBefore(context: Context, chatGuid: String): Long =
        prefs(context).getLong(KEY_CLEARED_PREFIX + opaque(context, chatGuid), 0L)

    fun setClearedBefore(context: Context, chatGuid: String, timestampMs: Long) =
        prefs(context).edit { putLong(KEY_CLEARED_PREFIX + opaque(context, chatGuid), timestampMs) }

    /** Forgets what was deleted (the whole iMessage archive went). */
    fun clearDeleted(context: Context) = prefs(context).edit {
        remove(KEY_DELETED)
        for (key in prefs(context).all.keys) if (key.startsWith(KEY_CLEARED_PREFIX)) remove(key)
    }

    /**
     * A name for a conversation that other apps may read (a notification's
     * tag): iMessage conversation ids carry phone numbers and email
     * addresses, so they are hashed with a secret of this install first.
     */
    fun opaque(context: Context, conversationId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt(context).toByteArray())
        digest.update(0)
        digest.update(conversationId.toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun salt(context: Context): String = synchronized(saltLock) {
        val prefs = prefs(context)
        prefs.getString(KEY_ID_SALT, null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs.edit(commit = true) { putString(KEY_ID_SALT, salt) }
        salt
    }
}
