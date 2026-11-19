package com.lightdecoder.imessage

import android.content.Context
import android.util.Log
import com.lightdecoder.relay.ActivityLog
import java.security.MessageDigest

/**
 * What Blue Bubbles did, step by step, so "my texts went green" can be traced
 * on a real phone: whether OpenBubbles' notifications arrive, whether they
 * carry a Reply button, which way each message went (silently through the
 * Reply button, through OpenBubbles' screen, or, wrongly, as a text), how long
 * OpenBubbles took to show it back, and each step of Check iMessage.
 *
 * Kept in app storage (newest [MAX_LINES]) for the problem report, and also
 * written to logcat under [TAG], so the same lines can be read over USB
 * (`adb logcat -s LDiMessage`) and in the on-device test's log.
 *
 * Kinds, outcomes, counts and timings only. Message text, names, phone
 * numbers, email addresses and OpenBubbles' chat ids are never written; a
 * chat is named by [chatRef], a short one-way tag that tells chats apart in
 * one log but cannot be turned back into the chat.
 */
object IMessageLog {
    const val TAG = "LDiMessage"
    const val PREFIX = "iMessage: "
    private const val MAX_LINES = 200

    private val log = ActivityLog("imessage-log.txt", MAX_LINES)

    fun add(context: Context, line: String) {
        log.add(context, PREFIX + line)
        runCatching { Log.i(TAG, line) }
    }

    /** Newest first. */
    fun read(context: Context): List<String> = log.read(context)

    fun clear(context: Context) = log.clear(context)

    /** "chat#3f9a": the same chat gets the same tag; the tag does not give the chat away. */
    fun chatRef(chatGuid: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(("ld-chat:" + chatGuid).toByteArray())
        return "chat#" + digest.take(2).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}
