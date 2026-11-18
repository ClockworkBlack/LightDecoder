package com.lightdecoder.fakebubbles

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput

/**
 * What OpenBubbles' notifications look like (CreateIncomingMessageNotification and
 * InternalIntentReceiver in OpenBubbles' source, 2026-04): tag NEW_MESSAGE_NOTIFICATION, a
 * MessagingStyle whose user is "You" and which keeps the chat's earlier lines, the sender as a
 * Person with the handle as its uri, extras chatGuid and messageGuid, a Mark As Read button and a
 * Reply button (RemoteInput "text_reply"). After a reply it posts the notification again with the
 * reply added under the user's profile name, timed in seconds.
 */
object FakeBubbles {
    const val TAG = "com.bluebubbles.messaging.NEW_MESSAGE_NOTIFICATION"
    const val NOTIFICATION_ID = 7

    /** Broadcast to the test: what came through the notification's buttons. */
    const val ACTION_REPLIED = "com.lightdecoder.fakebubbles.REPLIED"
    const val ACTION_MARKED_READ = "com.lightdecoder.fakebubbles.MARKED_READ"
    const val EXTRA_TEXT = "text"

    private class Line(val text: String, val timestamp: Long, val name: String, val uri: String?)

    private var lines = ArrayList<Line>()
    private var chatGuid = ""
    private var messageGuid = ""

    fun post(context: Context, guid: String, msgGuid: String, from: String, uri: String?, text: String, timestamp: Long) {
        if (guid != chatGuid) lines = ArrayList()
        chatGuid = guid
        messageGuid = msgGuid
        lines.add(Line(text, timestamp, from, uri))
        show(context)
    }

    fun addReply(context: Context, text: String) {
        lines.add(Line(text, System.currentTimeMillis() / 1000, "Ryan", null))
        show(context)
    }

    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancel(TAG, NOTIFICATION_ID)
        lines = ArrayList()
    }

    private fun show(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId = "com.bluebubbles.new_messages.$chatGuid"
        manager.createNotificationChannel(NotificationChannel(channelId, "Messages", NotificationManager.IMPORTANCE_HIGH))
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
        for (line in lines) {
            style.addMessage(
                NotificationCompat.MessagingStyle.Message(
                    line.text, line.timestamp, Person.Builder().setName(line.name).setUri(line.uri).setImportant(true).build(),
                ),
            )
        }
        val extras = Bundle().apply {
            putString("chatGuid", chatGuid)
            putString("messageGuid", messageGuid)
            putString("channelId", channelId)
            putString("tag", TAG)
        }
        fun action(type: String, code: Int) = PendingIntent.getBroadcast(
            context, code,
            Intent(context, InternalIntentReceiver::class.java).putExtras(extras).setType(type),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val markRead = NotificationCompat.Action.Builder(0, "Mark As Read", action("MarkChatRead", 2))
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
        val reply = NotificationCompat.Action.Builder(0, "Reply", action("ReplyChat", 1))
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .addRemoteInput(RemoteInput.Builder("text_reply").setLabel("Reply").build())
            .build()
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setStyle(style)
            .setShortcutId(chatGuid)
            .addAction(markRead)
            .addAction(reply)
            .addExtras(extras)
            .build()
        manager.notify(TAG, NOTIFICATION_ID, notification)
    }
}

/** "Someone wrote": extras guid, msgGuid, from, uri, text, ts. */
class PostReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("clear", false)) {
            FakeBubbles.clear(context)
            return
        }
        FakeBubbles.post(
            context,
            intent.getStringExtra("guid") ?: return,
            intent.getStringExtra("msgGuid").orEmpty(),
            intent.getStringExtra("from").orEmpty(),
            intent.getStringExtra("uri"),
            intent.getStringExtra("text") ?: return,
            intent.getLongExtra("ts", System.currentTimeMillis()),
        )
    }
}

/** The notification's buttons, pressed by LightDecoder (as a watch or the shade would). */
class InternalIntentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.type) {
            "ReplyChat" -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence("text_reply")?.toString() ?: return
                context.sendBroadcast(Intent(FakeBubbles.ACTION_REPLIED).putExtra(FakeBubbles.EXTRA_TEXT, text))
                // OpenBubbles shows the reply in its notification afterwards.
                FakeBubbles.addReply(context, text)
            }
            "MarkChatRead" -> {
                NotificationManagerCompat.from(context).cancel(FakeBubbles.TAG, FakeBubbles.NOTIFICATION_ID)
                context.sendBroadcast(Intent(FakeBubbles.ACTION_MARKED_READ))
            }
        }
    }
}
