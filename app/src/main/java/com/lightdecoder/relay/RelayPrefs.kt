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
    private const val KEY_LOOKUP_SENDS = "lookup_sends"
    private const val KEY_LAST_SETTINGS = "last_settings"
    private const val KEY_CATCH_UP_GAPS = "catch_up_gaps"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Whether Android lets the link run in the background without battery
     * optimisation (Doze). Not a stored choice: it is asked of Android each
     * time, since the user can change it in the system settings at any time.
     * Same as [RelayNotifications.isIgnoringBatteryOptimizations].
     */
    fun batteryExempt(context: Context): Boolean = RelayNotifications.isIgnoringBatteryOptimizations(context)

    /** Whether the user wants the link connected ("stay connected"). Off until they turn it on. */
    fun liveEnabled(context: Context) = prefs(context).getBoolean(KEY_LIVE, false)

    fun setLiveEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_LIVE, enabled).apply()

    /**
     * "Start automatically": whether the link may start by itself when a
     * phone is paired and the user chose to stay connected, namely after the
     * phone restarts or the app is updated ([BootReceiver]), and when one of
     * the app's screens comes to the front while it is not running
     * ([RelayManager.onAppVisible]). On by default. Whatever it says, a start
     * Android refused from the background, or a link stopped because its
     * notification was turned off, is started again on the next screen.
     */
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
     * Post a notification for new messages. On by default, so LightDecoder
     * tells the user about messages it receives; it can be turned off in the
     * link's settings.
     */
    fun notifyMessages(context: Context) = prefs(context).getBoolean(KEY_NOTIFY_MESSAGES, true)

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
        decodeSends(prefs(context).getString(KEY_UNCONFIRMED_SENDS, null).orEmpty())

    /**
     * Written to disk before it returns (the outbox calls it off the main
     * thread): an entry lost to the app being killed right after a send
     * would leave that send "sending" with no look-up planned.
     */
    @SuppressLint("ApplySharedPref")
    fun setUnconfirmedSends(context: Context, sends: Map<String, Long>) {
        prefs(context).edit().putString(KEY_UNCONFIRMED_SENDS, encodeSends(sends)).commit()
    }

    /**
     * Failed sends whose outcome is unknown (phone_not_responding, or one an
     * unpair held while it went out) that the outbox still has to look up
     * on the phone: outbox key (a random tmpId) to when it was sent.
     */
    fun lookupSends(context: Context): Map<String, Long> =
        decodeSends(prefs(context).getString(KEY_LOOKUP_SENDS, null).orEmpty())

    /** Written to disk before it returns, like [setUnconfirmedSends]. */
    @SuppressLint("ApplySharedPref")
    fun setLookupSends(context: Context, sends: Map<String, Long>) {
        prefs(context).edit().putString(KEY_LOOKUP_SENDS, encodeSends(sends)).commit()
    }

    private fun encodeSends(sends: Map<String, Long>) = sends.entries.joinToString(";") { "${it.key}=${it.value}" }

    private fun decodeSends(stored: String): Map<String, Long> =
        stored.split(';')
            .mapNotNull { entry ->
                val key = entry.substringBefore('=', "")
                val at = entry.substringAfter('=', "").toLongOrNull()
                if (key.isEmpty() || at == null) null else key to at
            }
            .toMap()

    /**
     * The payload of the phone's last "settings" event (its SIMs and
     * options), kept so the bridge can be seeded with it before the next
     * connect. App-private and excluded from backups like everything here;
     * never logged. Null if none is kept.
     */
    fun lastSettings(context: Context): String? = prefs(context).getString(KEY_LAST_SETTINGS, null)?.takeIf { it.isNotBlank() }

    /** Keeps (or with null forgets) the last settings payload; on disk before it returns. */
    @SuppressLint("ApplySharedPref")
    fun setLastSettings(context: Context, settingsJson: String?) {
        val edit = prefs(context).edit()
        if (settingsJson.isNullOrBlank()) edit.remove(KEY_LAST_SETTINGS) else edit.putString(KEY_LAST_SETTINGS, settingsJson)
        edit.commit()
    }

    /**
     * Forward catch-ups that have not finished: per conversation, the gaps
     * still to fill (see RelayManager's catch-up), as JSON. Holds only
     * conversation and message ids and timestamps, never content.
     */
    internal fun catchUpGaps(context: Context): String? = prefs(context).getString(KEY_CATCH_UP_GAPS, null)

    @SuppressLint("ApplySharedPref")
    internal fun setCatchUpGaps(context: Context, json: String?) {
        val edit = prefs(context).edit()
        if (json.isNullOrEmpty()) edit.remove(KEY_CATCH_UP_GAPS) else edit.putString(KEY_CATCH_UP_GAPS, json)
        edit.commit()
    }
}
