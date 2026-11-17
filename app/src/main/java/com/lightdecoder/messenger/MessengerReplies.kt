package com.lightdecoder.messenger

import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import java.util.concurrent.ConcurrentHashMap

/**
 * The reply buttons of Messenger's notifications, by conversation, so a
 * reply typed in LightDecoder can be sent the way one typed in the
 * notification shade is: by the Messenger app itself.
 *
 * Android does not let these be kept on disk; they live as long as the app's
 * process and are collected again from the notifications still showing when
 * the listener reconnects. A conversation has a reply button here only if
 * Messenger posted a notification for it and offered one.
 */
object MessengerReplies {

    enum class Result {
        /** Handed to Messenger. Whether it went out is Messenger's to say. */
        HANDED_OVER,

        /** No notification of this conversation has offered a reply button. */
        NO_REPLY_BUTTON,

        /** Messenger has withdrawn the button (its notification is gone and so is the offer). */
        WITHDRAWN,
    }

    private class Entry(
        val notificationKey: String,
        val reply: MessengerReplyAction?,
        val open: PendingIntent?,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /** What the notification [notificationKey] of [conversationKey] offers now. */
    fun update(conversationKey: String, notificationKey: String, reply: MessengerReplyAction?, open: PendingIntent?) {
        val before = entries[conversationKey]
        // An update without a reply button does not take away one offered earlier.
        entries[conversationKey] = Entry(notificationKey, reply ?: before?.reply, open ?: before?.open)
    }

    fun canReply(conversationKey: String): Boolean = entries[conversationKey]?.reply != null

    /**
     * Fills [text] into the conversation's reply button and presses it, as
     * the notification shade would. Nothing else is sent anywhere.
     */
    fun send(context: Context, conversationKey: String, text: String): Result {
        val entry = entries[conversationKey]
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
            entries[conversationKey] = Entry(entry.notificationKey, null, entry.open)
            Result.WITHDRAWN
        }
    }

    /**
     * Opens the conversation in the Messenger app through its notification's
     * own tap action. False if there is none or Messenger has withdrawn it.
     */
    fun open(context: Context, conversationKey: String): Boolean {
        val open = entries[conversationKey]?.open ?: return false
        return try {
            open.send(context, 0, null, null, null, null, startFromHere())
            true
        } catch (e: PendingIntent.CanceledException) {
            false
        }
    }

    /**
     * Since Android 14 an app that sends another app's "open a screen" intent
     * has to say that it lends it the right to come to the front. The person
     * has just tapped "Open in Messenger" on a LightDecoder screen, so it does.
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

    /** Forget every button (the user cleared the inbox, or paused catching). */
    fun clear() = entries.clear()

    fun forget(conversationKey: String) {
        entries.remove(conversationKey)
    }
}
