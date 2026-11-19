package com.lightdecoder.imessage.direct

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * iMessage settings. "Registered" is a file, not SharedPreferences: SharedPreferences are cached
 * per process, and the ":imessage" process sets it while the main process reads it (to start the
 * service at boot and on app start).
 *
 * The rest ([prefs]) are the ":imessage" process's own, in a file of their own: the main process's
 * Blue Bubbles settings ([com.lightdecoder.imessage.IMessagePrefs]) are in "imessage", and a
 * second process writing that file would undo the main process's changes to it.
 */
object DirectPrefs {
    private const val FILE = "imessage_direct"

    /** Where the relay code and host were kept before; read once, never written. */
    private const val OLD_FILE = "imessage"
    private const val KEY_MIGRATED = "migrated_from_imessage"
    const val KEY_CODE = "relay_code"
    const val KEY_HOST = "relay_host"
    private const val KEY_READ_RECEIPTS = "send_read_receipts"

    /** Only used in the ":imessage" process (the setup screen and the library's engine). */
    fun prefs(context: Context): SharedPreferences {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_MIGRATED, false)) {
            val old = context.getSharedPreferences(OLD_FILE, Context.MODE_PRIVATE)
            prefs.edit().apply {
                for (key in listOf(KEY_CODE, KEY_HOST)) {
                    if (!prefs.contains(key)) old.getString(key, null)?.let { putString(key, it) }
                }
                putBoolean(KEY_MIGRATED, true)
            }.apply()
        }
        return prefs
    }

    /** Whether people are told when the user has read their messages (one-to-one chats). Off, as on an iPhone. */
    fun sendReadReceipts(context: Context): Boolean = prefs(context).getBoolean(KEY_READ_RECEIPTS, false)

    /** Turns read receipts to people on or off (see [sendReadReceipts]). */
    fun setSendReadReceipts(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_READ_RECEIPTS, on).apply()
    }

    private fun marker(context: Context) = File(context.filesDir, "imessage/registered")

    private fun shownMarker(context: Context) = File(context.filesDir, "imessage/blue-bubbles-turned-on")

    /** Whether Blue Bubbles was already turned on for the library's chats (done once). */
    fun blueBubblesTurnedOn(context: Context): Boolean = shownMarker(context).isFile

    fun setBlueBubblesTurnedOn(context: Context) {
        shownMarker(context).apply { parentFile?.mkdirs() }.writeText("1")
    }

    private fun statusFile(context: Context) = File(context.filesDir, "imessage/status")

    /**
     * What the ":imessage" process last said of iMessage ([DirectEngine.describe]), and when it
     * said it; null if it never did. A file, for the main process's logs and problem report.
     */
    fun lastStatus(context: Context): Pair<String, Long>? = runCatching {
        val f = statusFile(context)
        f.readText().trim().takeIf { it.isNotEmpty() }?.let { it to f.lastModified() }
    }.getOrNull()

    fun setLastStatus(context: Context, text: String) {
        runCatching { statusFile(context).apply { parentFile?.mkdirs() }.writeText(text) }
    }

    /** Set once Apple reported a registration; the background service runs while it is. */
    fun registered(context: Context): Boolean = marker(context).isFile

    fun setRegistered(context: Context, value: Boolean) {
        val f = marker(context)
        if (value) {
            f.parentFile?.mkdirs()
            if (!f.isFile) f.writeText("1")
        } else {
            f.delete()
        }
    }
}
