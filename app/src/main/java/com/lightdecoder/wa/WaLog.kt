package com.lightdecoder.wa

import android.content.Context
import com.lightdecoder.relay.ActivityLog

/**
 * A short, on-device record of what the WhatsApp link did, for its status
 * screen. It is a log of its own (the Google Messages link's is
 * [com.lightdecoder.relay.RelayLog]), and every line starts with [PREFIX],
 * so a line can never be taken for the other link's wherever it is shown.
 *
 * It records only kinds and outcomes ("connected", "send failed:
 * not_connected"). Never message text, names, push names, phone numbers,
 * pairing codes, QR codes or keys; and never a JID or a message id either:
 * a WhatsApp JID is a phone number. Where a line has to tell two chats
 * apart, use [WaIds.opaque]. An exception's message is not content-free
 * unless it is a [com.lightdecoder.relay.RelayException]'s code: log the
 * code, or the exception's class name, never its text.
 */
object WaLog {
    const val PREFIX = "WhatsApp: "

    private val log = ActivityLog("whatsapp-log.txt")

    /** Adds "[PREFIX][line]". */
    fun add(context: Context, line: String) = log.add(context, PREFIX + line)

    /** Newest first. */
    fun read(context: Context): List<String> = log.read(context)

    fun clear(context: Context) = log.clear(context)
}
