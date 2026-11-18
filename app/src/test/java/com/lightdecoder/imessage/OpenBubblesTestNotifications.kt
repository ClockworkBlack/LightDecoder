package com.lightdecoder.imessage

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput

/**
 * Notifications built the way OpenBubbles builds its message notifications
 * (CreateIncomingMessageNotification, and InternalIntentReceiver's re-post
 * after a reply), for the Blue Bubbles tests.
 */
internal object OpenBubblesTestNotifications {

    const val PACKAGE = "com.openbubbles.messaging"
    const val REPLY_ACTION = "com.lightdecoder.test.OPENBUBBLES_REPLY"
    const val MARK_READ_ACTION = "com.lightdecoder.test.OPENBUBBLES_MARK_READ"
    const val REPLY_KEY = "text_reply"
    const val ALICE_GUID = "iMessage;-;+15551234567"
    const val GROUP_GUID = "iMessage;+;chat123456"

    /** OpenBubbles' own user: "You", with no key. */
    private val you: Person = Person.Builder().setName("You").build()

    fun sender(name: String, uri: String? = null): Person =
        Person.Builder().setName(name).setUri(uri).setImportant(true).build()

    /** A line of the chat; [from] null... for the user's reply that OpenBubbles adds after sending (time in seconds). */
    class Line(val text: String, val timestamp: Long, val from: Person)

    fun chat(
        context: Context,
        lines: List<Line>,
        chatGuid: String = ALICE_GUID,
        messageGuid: String = "msg-1",
        groupTitle: String? = null,
        withActions: Boolean = true,
        requestCode: Int = 1,
    ): Notification {
        val style = NotificationCompat.MessagingStyle(you)
        if (groupTitle != null) {
            style.isGroupConversation = true
            style.conversationTitle = groupTitle
        }
        for (line in lines) style.addMessage(NotificationCompat.MessagingStyle.Message(line.text, line.timestamp, line.from))
        val extras = Bundle().apply {
            putString("chatGuid", chatGuid)
            putString("messageGuid", messageGuid)
            putString("channelId", "com.bluebubbles.new_messages.$chatGuid")
            putString("tag", OpenBubbles.MESSAGE_TAG)
        }
        val builder = NotificationCompat.Builder(context, "com.bluebubbles.new_messages.$chatGuid")
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setStyle(style)
            .setShortcutId(chatGuid)
            .addExtras(extras)
        if (withActions) {
            builder.addAction(markReadAction(context, requestCode))
            builder.addAction(replyAction(context, requestCode))
        }
        return builder.build()
    }

    fun replyAction(context: Context, requestCode: Int = 1): NotificationCompat.Action {
        val intent = PendingIntent.getBroadcast(
            context, requestCode, Intent(REPLY_ACTION).setPackage(context.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(0, "Reply", intent)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .addRemoteInput(RemoteInput.Builder(REPLY_KEY).setLabel("Reply").build())
            .build()
    }

    fun markReadAction(context: Context, requestCode: Int = 1): NotificationCompat.Action {
        val intent = PendingIntent.getBroadcast(
            context, 100 + requestCode, Intent(MARK_READ_ACTION).setPackage(context.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(0, "Mark As Read", intent)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    @Suppress("DEPRECATION")
    fun posted(notification: Notification, packageName: String = PACKAGE, tag: String? = OpenBubbles.MESSAGE_TAG, id: Int = 7): StatusBarNotification =
        StatusBarNotification(packageName, packageName, id, tag, 10200, 0, 0, notification, Process.myUserHandle(), 5_000L)
}
