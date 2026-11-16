package com.lightdecoder.wa

import android.content.Context
import androidx.core.content.edit
import org.json.JSONException
import org.json.JSONObject
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
    private const val KEY_LINK = "link"
    private const val KEY_ARCHIVE_OWNER = "archive_owner"
    private const val KEY_DELETED_CHATS = "deleted_chats"

    /** Values of [link]. */
    internal const val LINK_NONE = ""
    internal const val LINK_LINKED = "linked"
    internal const val LINK_LOGGED_OUT = "logged_out"

    /** At most this many locally deleted chats are remembered ([deletedChats]); the oldest go first. */
    private const val MAX_DELETED_CHATS = 500

    private val saltLock = Any()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Whether the user wants the WhatsApp link connected ("stay connected"). Off until they turn it on. */
    fun liveEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LIVE, false)

    fun setLiveEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit { putBoolean(KEY_LIVE, enabled) }

    /**
     * "Start automatically": whether the WhatsApp link may start by itself
     * when it is linked and the user chose to stay connected (after the
     * phone restarts or the app is updated, and when one of the app's
     * screens comes to the front while it is not running). On by default,
     * as for the Google Messages link.
     */
    fun autoStart(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_START, true)

    fun setAutoStart(context: Context, enabled: Boolean) =
        prefs(context).edit { putBoolean(KEY_AUTO_START, enabled) }

    /**
     * Post a notification for new WhatsApp messages. Off by default:
     * WhatsApp on the phone already notifies.
     */
    fun notifyMessages(context: Context): Boolean = prefs(context).getBoolean(KEY_NOTIFY_MESSAGES, false)

    fun setNotifyMessages(context: Context, enabled: Boolean) =
        prefs(context).edit { putBoolean(KEY_NOTIFY_MESSAGES, enabled) }

    /**
     * This installation's random secret behind [WaIds.opaque], made on first
     * use and on disk before it is returned: a stand-in handed out before a
     * restart has to come out the same after it.
     */
    internal fun idSalt(context: Context): String = synchronized(saltLock) {
        val prefs = prefs(context)
        prefs.getString(KEY_ID_SALT, null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs.edit(commit = true) { putString(KEY_ID_SALT, salt) }
        salt
    }

    // --- what the runtime keeps (not choices; never shown as settings) ----------------------

    /**
     * Whether this device was linked when the runtime last knew: [LINK_LINKED],
     * [LINK_LOGGED_OUT] (the phone or WhatsApp ended the link) or [LINK_NONE].
     * The link itself lives in the bridge's store, which only the Go library
     * can read; this copy lets [WaManager.isPaired] answer at once, without
     * loading that library for someone who never linked WhatsApp. The bridge
     * is asked as soon as it is loaded, and wins.
     */
    internal fun link(context: Context): String = prefs(context).getString(KEY_LINK, null).orEmpty()

    /** On disk before it returns: a link made just before the app died must be known after it. Call off the main thread. */
    internal fun setLink(context: Context, link: String) {
        if (link(context) == link) return
        prefs(context).edit(commit = true) { if (link.isEmpty()) remove(KEY_LINK) else putString(KEY_LINK, link) }
    }

    /**
     * The WhatsApp account (its number, as the bridge's ownId) the local
     * archive came from; empty if not known. Kept when unlinking keeps the
     * archive, so linking again can tell whether the archive is the new
     * account's: one account's chats must never show up as another's.
     */
    internal fun archiveOwner(context: Context): String = prefs(context).getString(KEY_ARCHIVE_OWNER, null).orEmpty()

    /** On disk before it returns, as [com.lightdecoder.relay.RelayPrefs.setArchiveOwner] is, for the same reason. */
    internal fun setArchiveOwner(context: Context, ownId: String) {
        if (archiveOwner(context) == ownId) return
        prefs(context).edit(commit = true) { if (ownId.isEmpty()) remove(KEY_ARCHIVE_OWNER) else putString(KEY_ARCHIVE_OWNER, ownId) }
    }

    /**
     * Chats the user deleted here: conversation id to when (epoch ms). The
     * bridge cannot delete a chat on the phone, and keeps listing it, so
     * without this record the next refresh would put it back. A chat stays
     * deleted until it gets a message newer than its deletion. App-private,
     * like the archive that holds the same ids.
     */
    internal fun deletedChats(context: Context): Map<String, Long> {
        val stored = prefs(context).getString(KEY_DELETED_CHATS, null) ?: return emptyMap()
        return try {
            val json = JSONObject(stored)
            json.keys().asSequence().associateWith { json.optLong(it) }
        } catch (e: JSONException) {
            emptyMap()
        }
    }

    /** On disk before it returns: a deletion the user asked for must not come undone by the app dying. */
    internal fun setDeletedChats(context: Context, chats: Map<String, Long>) {
        val kept = if (chats.size <= MAX_DELETED_CHATS) chats else chats.entries.sortedByDescending { it.value }.take(MAX_DELETED_CHATS).associate { it.key to it.value }
        prefs(context).edit(commit = true) {
            if (kept.isEmpty()) remove(KEY_DELETED_CHATS) else putString(KEY_DELETED_CHATS, JSONObject(kept as Map<*, *>).toString())
        }
    }
}
