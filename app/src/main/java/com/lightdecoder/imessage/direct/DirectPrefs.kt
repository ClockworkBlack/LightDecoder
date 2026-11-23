package com.lightdecoder.imessage.direct

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * iMessage settings. "Registered" is a file, not SharedPreferences: SharedPreferences are cached
 * per process, and the ":imessage" process sets it while the main process reads it (to start the
 * service at boot and on app start). So is "iMessage games off" ([gamesOn]), for the same reason.
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

    private fun readReceiptsFile(context: Context) = File(context.filesDir, "imessage/read-receipts")

    /**
     * Whether people are told when the user has read their messages (one-to-one chats). Off, as on
     * an iPhone. Read in either process: the setup screen (":imessage") sets it, and a chat's details
     * (main process) show it as the chat's default. Kept in a file of its own once it is set, which a
     * SharedPreferences cache would not see change; one set before that is in [prefs].
     */
    fun sendReadReceipts(context: Context): Boolean {
        val stored = runCatching { readReceiptsFile(context).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
        return when (stored) {
            "1" -> true
            "0" -> false
            else -> prefs(context).getBoolean(KEY_READ_RECEIPTS, false)
        }
    }

    /** Turns read receipts to people on or off (see [sendReadReceipts]). */
    fun setSendReadReceipts(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_READ_RECEIPTS, on).apply()
        runCatching { readReceiptsFile(context).apply { parentFile?.mkdirs() }.writeText(if (on) "1" else "0") }
    }

    private fun gamesOffMarker(context: Context) = File(context.filesDir, "imessage/games-off")

    /**
     * Whether iMessage games are played here (through OpenPigeon, [com.lightdecoder.imessage.madrid.MadridHost]).
     * On by default. A file, not [prefs]: the setup screen (":imessage" process) sets it, and the
     * main process (the host, the chat screen) reads it, where a SharedPreferences cache would not see it change.
     */
    fun gamesOn(context: Context): Boolean = !gamesOffMarker(context).isFile

    /** Turns iMessage games on or off (see [gamesOn]). */
    fun setGamesOn(context: Context, on: Boolean) {
        runCatching {
            val f = gamesOffMarker(context)
            if (on) {
                f.delete()
            } else {
                f.parentFile?.mkdirs()
                if (!f.isFile) f.writeText("1")
            }
        }
    }

    private fun phoneStyleOffMarker(context: Context) = File(context.filesDir, "imessage/phone-style-off")

    /**
     * Whether iMessage chats are drawn as on an iPhone ([com.lightdecoder.ui.chat.PhoneStyle]). On by
     * default. A file, like [gamesOn]: the setup screen sets it, the chat screen (main process) reads it.
     */
    fun phoneStyleOn(context: Context): Boolean = !phoneStyleOffMarker(context).isFile

    /** Turns iPhone-style chats on or off (see [phoneStyleOn]). */
    fun setPhoneStyleOn(context: Context, on: Boolean) {
        runCatching {
            val f = phoneStyleOffMarker(context)
            if (on) {
                f.delete()
            } else {
                f.parentFile?.mkdirs()
                if (!f.isFile) f.writeText("1")
            }
        }
    }

    private fun replyThreadsInChatMarker(context: Context) = File(context.filesDir, "imessage/reply-threads-in-chat")

    /**
     * Whether iPhone-style chats draw inline replies in the transcript itself (a faded copy of the
     * message answered above a reply, and thin lines joining them). Off by default: a reply is then
     * an ordinary bubble with a small "Reply" under it, and a tap opens its thread. A file, like
     * [gamesOn]: the setup screen sets it, the chat screen (main process) reads it.
     */
    fun replyThreadsInChat(context: Context): Boolean = replyThreadsInChatMarker(context).isFile

    /** Turns reply threads drawn in the chat on or off (see [replyThreadsInChat]). */
    fun setReplyThreadsInChat(context: Context, on: Boolean) {
        runCatching {
            val f = replyThreadsInChatMarker(context)
            if (on) {
                f.parentFile?.mkdirs()
                if (!f.isFile) f.writeText("1")
            } else {
                f.delete()
            }
        }
    }

    private fun mentionsQuietMarker(context: Context) = File(context.filesDir, "imessage/mentions-quiet")

    /**
     * Whether a message that mentions the user notifies even in a chat whose alerts are hidden, as an
     * iPhone's "Notify Me" does. On by default. A file, like [gamesOn]: the setup screen sets it, the
     * main process (the notifications) reads it.
     */
    fun notifyWhenMentioned(context: Context): Boolean = !mentionsQuietMarker(context).isFile

    /** Turns "Notify me when mentioned" on or off (see [notifyWhenMentioned]). */
    fun setNotifyWhenMentioned(context: Context, on: Boolean) = setMarker(mentionsQuietMarker(context), !on)

    private fun linkPreviewsOffMarker(context: Context) = File(context.filesDir, "imessage/link-previews-off")

    /**
     * Whether a link the user sends goes with its page's preview ([LinkPreviews], fetched from this
     * phone before it goes). On by default, as on an iPhone. A file, like [gamesOn]: the setup screen
     * sets it, the main process (which fetches the page) reads it.
     */
    fun linkPreviews(context: Context): Boolean = !linkPreviewsOffMarker(context).isFile

    /** Turns link previews on or off (see [linkPreviews]). */
    fun setLinkPreviews(context: Context, on: Boolean) = setMarker(linkPreviewsOffMarker(context), !on)

    private fun faceTimeOffMarker(context: Context) = File(context.filesDir, "imessage/facetime-off")

    /**
     * Whether FaceTime calls are placed and taken here ([com.lightdecoder.imessage.facetime]). On by
     * default. Off: Apple's FaceTime events are acknowledged unread (nothing rings, nothing is noted
     * as missed) and the chats' FaceTime buttons are hidden. A file, like [gamesOn]: the setup screen
     * and the call engine (":imessage" process) and the chat screens (main process) all read it.
     */
    fun faceTimeOn(context: Context): Boolean = !faceTimeOffMarker(context).isFile

    /** Turns FaceTime on or off (see [faceTimeOn]). */
    fun setFaceTimeOn(context: Context, on: Boolean) = setMarker(faceTimeOffMarker(context), !on)

    private fun silenceUnknownFaceTimeMarker(context: Context) = File(context.filesDir, "imessage/facetime-silence-unknown")

    /**
     * "Silence unknown FaceTime callers": a call from someone who is no contact and has no chat here
     * does not ring; it only shows as a missed call. Off by default. A file, like [faceTimeOn].
     */
    fun silenceUnknownFaceTime(context: Context): Boolean = silenceUnknownFaceTimeMarker(context).isFile

    /** Turns "Silence unknown FaceTime callers" on or off (see [silenceUnknownFaceTime]). */
    fun setSilenceUnknownFaceTime(context: Context, on: Boolean) = setMarker(silenceUnknownFaceTimeMarker(context), on)

    private fun liveFaceTimeCallFile(context: Context) = File(context.filesDir, "imessage/facetime-live-call")

    /**
     * The FaceTime call this phone holds (placed and ringing out, being joined, or joined), by its
     * id; null for none. Noted by the call engine (":imessage" process) as a call begins and ends,
     * so that after the process was stopped in a call (nothing told the other side then) the next
     * start can leave it ([com.lightdecoder.imessage.facetime.CallRecovery]). Only ever a call's id.
     */
    fun liveFaceTimeCall(context: Context): String? =
        runCatching { liveFaceTimeCallFile(context).takeIf { it.isFile && it.length() <= 64 }?.readText()?.trim() }.getOrNull()
            ?.takeIf(com.lightdecoder.imessage.facetime.CallIds::isGuid)

    /** Notes the call held now ([liveFaceTimeCall]); null: none. */
    fun setLiveFaceTimeCall(context: Context, guid: String?) {
        runCatching {
            val f = liveFaceTimeCallFile(context)
            if (guid == null) {
                f.delete()
            } else {
                f.parentFile?.mkdirs()
                f.writeText(guid)
            }
        }
    }

    /** Puts [f] there ([present]) or takes it away, as a setting's marker. */
    private fun setMarker(f: File, present: Boolean) {
        runCatching {
            if (present) {
                f.parentFile?.mkdirs()
                if (!f.isFile) f.writeText("1")
            } else {
                f.delete()
            }
        }
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

    /** Kept without numbers or email addresses ([com.lightdecoder.imessage.Redact]): Apple's error text can name the registered ones. */
    fun setLastStatus(context: Context, text: String) {
        runCatching { statusFile(context).apply { parentFile?.mkdirs() }.writeText(com.lightdecoder.imessage.Redact.text(text)) }
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
