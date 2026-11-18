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
    private const val KEY_NOTIFY = "notify_messages"
    private const val KEY_ID_SALT = "id_salt"
    private val saltLock = Any()

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * "Blue Bubbles": OpenBubbles' iMessages are read into the Messages tab
     * and can be answered from there. Off until the user ticks it.
     */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit(commit = true) { putBoolean(KEY_ENABLED, on) }

    /** LightDecoder's own notification for each new iMessage. On by default. */
    fun notifyMessages(context: Context): Boolean = prefs(context).getBoolean(KEY_NOTIFY, true)

    fun setNotifyMessages(context: Context, on: Boolean) = prefs(context).edit { putBoolean(KEY_NOTIFY, on) }

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
