package com.lightdecoder.wa

import android.annotation.SuppressLint
import android.content.Context
import java.security.SecureRandom

/**
 * The user's choices for the WhatsApp link, in a preferences file of their
 * own: nothing here is shared with the Google Messages link
 * ([com.lightdecoder.relay.RelayPrefs]), so turning one link on, or letting
 * it start by itself, never does the same for the other. App-private and
 * excluded from backups like everything else.
 */
object WaPrefs {
    /** The preferences file (tests clear it). */
    internal const val FILE = "whatsapp"
    private const val KEY_LIVE = "live_enabled"
    private const val KEY_AUTO_START = "auto_start"
    private const val KEY_NOTIFY_MESSAGES = "notify_messages"
    private const val KEY_ID_SALT = "id_salt"

    private val saltLock = Any()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Whether the user wants the WhatsApp link connected ("stay connected"). Off until they turn it on. */
    fun liveEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LIVE, false)

    fun setLiveEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_LIVE, enabled).apply()

    /**
     * "Start automatically": whether the WhatsApp link may start by itself
     * when it is linked and the user chose to stay connected (after the
     * phone restarts or the app is updated, and when one of the app's
     * screens comes to the front while it is not running). On by default,
     * as for the Google Messages link.
     */
    fun autoStart(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_START, true)

    fun setAutoStart(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO_START, enabled).apply()

    /**
     * Post a notification for new WhatsApp messages. Off by default:
     * WhatsApp on the phone already notifies.
     */
    fun notifyMessages(context: Context): Boolean = prefs(context).getBoolean(KEY_NOTIFY_MESSAGES, false)

    fun setNotifyMessages(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_NOTIFY_MESSAGES, enabled).apply()

    /**
     * This installation's random secret behind [WaIds.opaque], made on first
     * use and on disk before it is returned: a stand-in handed out before a
     * restart has to come out the same after it.
     */
    @SuppressLint("ApplySharedPref")
    internal fun idSalt(context: Context): String = synchronized(saltLock) {
        val prefs = prefs(context)
        prefs.getString(KEY_ID_SALT, null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_ID_SALT, salt).commit()
        salt
    }
}
