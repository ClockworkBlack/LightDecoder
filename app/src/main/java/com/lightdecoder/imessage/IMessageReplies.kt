package com.lightdecoder.imessage

import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import java.util.concurrent.ConcurrentHashMap

/**
 * The buttons of OpenBubbles' notifications, by chat: Reply (so a reply
 * typed in LightDecoder is sent by OpenBubbles, the way one typed in the
 * notification shade is), Mark As Read, and the tap that opens the chat.
 *
 * Android does not let these be kept on disk; they live as long as the
 * app's process and are collected again from the notifications still
 * showing when the listener reconnects. They keep working after
 * OpenBubbles' notification is gone, until OpenBubbles withdraws them.
 */
object IMessageReplies {

    enum class Result {
        /** Handed to OpenBubbles, which sends it in the background. */
        HANDED_OVER,

        /** No notification of this chat has offered a Reply button since the app started. */
        NO_REPLY_BUTTON,

        /** OpenBubbles has withdrawn the button. */
        WITHDRAWN,
    }

    private class Entry(
        val reply: IMessageAction?,
        val markRead: IMessageAction?,
        val open: PendingIntent?,
        /** Something arrived since the chat was last marked read through OpenBubbles. */
        val unread: Boolean,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /** What the latest notification of [chatGuid] offers; [incoming] when it brought a new message from someone else. */
    fun update(chatGuid: String, reply: IMessageAction?, markRead: IMessageAction?, open: PendingIntent?, incoming: Boolean) {
        entries.compute(chatGuid) { _, before ->
            // An update without a button does not take away one offered earlier.
            Entry(reply ?: before?.reply, markRead ?: before?.markRead, open ?: before?.open, incoming || before?.unread == true)
        }
    }

    fun canReply(chatGuid: String): Boolean = entries[chatGuid]?.reply != null

    /** Fills [text] into the chat's Reply button and presses it, as the notification shade would. */
    fun send(context: Context, chatGuid: String, text: String): Result {
        val entry = entries[chatGuid]
        val reply = entry?.reply ?: return Result.NO_REPLY_BUTTON
        val fill = Intent()
        val results = Bundle()
        for (input in reply.inputs) results.putCharSequence(input.resultKey, text)
        RemoteInput.addResultsToIntent(reply.inputs, fill, results)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT)
        }
        return try {
            reply.intent.send(context, 0, fill)
            Result.HANDED_OVER
        } catch (e: PendingIntent.CanceledException) {
            entries.computeIfPresent(chatGuid) { _, e2 -> Entry(null, e2.markRead, e2.open, e2.unread) }
            Result.WITHDRAWN
        }
    }

    /**
     * Presses the chat's Mark As Read button if something arrived since the
     * last time: OpenBubbles then clears its notification and sends a read
     * receipt if it is set to. True if it was pressed.
     */
    fun markRead(context: Context, chatGuid: String): Boolean {
        val entry = entries[chatGuid] ?: return false
        if (!entry.unread) return false
        val action = entry.markRead ?: return false
        entries.computeIfPresent(chatGuid) { _, e -> Entry(e.reply, e.markRead, e.open, unread = false) }
        return try {
            action.intent.send(context, 0, null)
            true
        } catch (e: PendingIntent.CanceledException) {
            entries.computeIfPresent(chatGuid) { _, e2 -> Entry(e2.reply, null, e2.open, e2.unread) }
            false
        }
    }

    /** Opens the chat in OpenBubbles through its notification's own tap. False if there is none. */
    fun open(context: Context, chatGuid: String): Boolean {
        val open = entries[chatGuid]?.open ?: return false
        return try {
            open.send(context, 0, null, null, null, null, startFromHere())
            true
        } catch (e: PendingIntent.CanceledException) {
            false
        }
    }

    /**
     * Since Android 14 an app that sends another app's "open a screen" intent
     * has to say that it lends it the right to come to the front. The user
     * has just asked for OpenBubbles on a LightDecoder screen, so it does.
     */
    @Suppress("DEPRECATION")
    private fun startFromHere(): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val mode = if (Build.VERSION.SDK_INT >= 36) {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
        } else {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }
        return ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode).toBundle()
    }

    fun forget(chatGuid: String) {
        entries.remove(chatGuid)
    }

    fun clear() = entries.clear()
}
