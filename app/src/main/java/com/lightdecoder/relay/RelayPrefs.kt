package com.lightdecoder.relay

import android.annotation.SuppressLint
import android.content.Context

/**
 * How much history to import after pairing.
 * RECENT: the 25 newest conversations, 50 messages each. DEEP: 200 × 1000.
 */
enum class BackfillDepth(val conversations: Int, val messagesPerConversation: Int) {
    OFF(0, 0),
    RECENT(25, 50),
    DEEP(200, 1000),
}

/** The user's choices for the Google Messages link. */
object RelayPrefs {
    private const val FILE = "relay"
    private const val KEY_LIVE = "live_enabled"
    private const val KEY_AUTO_START = "auto_start"
    private const val KEY_BACKFILL_DEPTH = "backfill_depth"
    private const val KEY_NOTIFY_MESSAGES = "notify_messages"
    private const val KEY_CATCH_STICKERS = "catch_stickers"
    private const val KEY_BACKFILL_DONE_AT = "backfill_done_at"
    private const val KEY_ARCHIVE_PHONE = "archive_phone"
    private const val KEY_ARCHIVE_ACCOUNT = "archive_account"
    private const val KEY_UNCONFIRMED_SENDS = "unconfirmed_sends"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Whether the user wants the link connected ("stay connected"). Off until they turn it on. */
    fun liveEnabled(context: Context) = prefs(context).getBoolean(KEY_LIVE, false)

    fun setLiveEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_LIVE, enabled).apply()

    /** Reconnect after the phone restarts, if paired and live. On by default. */
    fun autoStart(context: Context) = prefs(context).getBoolean(KEY_AUTO_START, true)

    fun setAutoStart(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO_START, enabled).apply()

    fun backfillDepth(context: Context): BackfillDepth {
        val name = prefs(context).getString(KEY_BACKFILL_DEPTH, null)
        return BackfillDepth.entries.firstOrNull { it.name == name } ?: BackfillDepth.RECENT
    }

    fun setBackfillDepth(context: Context, depth: BackfillDepth) =
        prefs(context).edit().putString(KEY_BACKFILL_DEPTH, depth.name).apply()

    /**
     * Post a notification for new messages. Off by default: Google Messages on
     * the phone already notifies.
     */
    fun notifyMessages(context: Context) = prefs(context).getBoolean(KEY_NOTIFY_MESSAGES, false)

    fun setNotifyMessages(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_NOTIFY_MESSAGES, enabled).apply()

    /** Decode HEIC stickers that arrive through the link into the sticker inbox. On by default. */
    fun catchStickers(context: Context) = prefs(context).getBoolean(KEY_CATCH_STICKERS, true)

    fun setCatchStickers(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_CATCH_STICKERS, enabled).apply()

    /** When the history import last finished (epoch ms), 0 if never. */
    fun backfillDoneAt(context: Context) = prefs(context).getLong(KEY_BACKFILL_DONE_AT, 0L)

    fun setBackfillDoneAt(context: Context, timestampMs: Long) =
        prefs(context).edit().putLong(KEY_BACKFILL_DONE_AT, timestampMs).apply()

    /**
     * The phone (and Google account) the local archive came from; empty if
     * unknown. Kept when unpairing keeps the archive, so pairing again can tell
     * whether the archive belongs to the new phone: conversation ids only mean
     * something on the phone they came from.
     */
    fun archivePhone(context: Context): String = prefs(context).getString(KEY_ARCHIVE_PHONE, null).orEmpty()

    fun archiveAccount(context: Context): String = prefs(context).getString(KEY_ARCHIVE_ACCOUNT, null).orEmpty()

    /**
     * Records (or, with empty values, forgets) who the archive belongs to.
     * Unlike the other settings it is on disk before this returns: if the app
     * died just after the archive was cleared for a new phone and the write
     * were lost, the owner would still name the old phone, and pairing that
     * phone again later would mix its messages into the new phone's. Call it
     * off the main thread.
     */
    @SuppressLint("ApplySharedPref")
    fun setArchiveOwner(context: Context, phoneId: String, account: String) {
        if (archivePhone(context) == phoneId && archiveAccount(context) == account) return
        prefs(context).edit().putString(KEY_ARCHIVE_PHONE, phoneId).putString(KEY_ARCHIVE_ACCOUNT, account).commit()
    }

    /**
     * Sends Google accepted whose echo from the phone has not arrived yet:
     * outbox key (a random tmpId, never content) to when it was sent.
     */
    fun unconfirmedSends(context: Context): Map<String, Long> =
        prefs(context).getString(KEY_UNCONFIRMED_SENDS, null).orEmpty()
            .split(';')
            .mapNotNull { entry ->
                val key = entry.substringBefore('=', "")
                val at = entry.substringAfter('=', "").toLongOrNull()
                if (key.isEmpty() || at == null) null else key to at
            }
            .toMap()

    fun setUnconfirmedSends(context: Context, sends: Map<String, Long>) =
        prefs(context).edit()
            .putString(KEY_UNCONFIRMED_SENDS, sends.entries.joinToString(";") { "${it.key}=${it.value}" })
            .apply()
}
