package com.lightdecoder.messenger

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput

/** Notifications shaped like a chat app's, for the Messenger tests. */
internal object MessengerTestNotifications {

    const val REPLY_ACTION = "com.lightdecoder.test.MESSENGER_REPLY"
    const val REPLY_KEY = "reply_text"

    val me: Person = Person.Builder().setName("Me").setKey("me").build()

    fun person(name: String): Person = Person.Builder().setName(name).setKey("id:$name").build()

    class Line(val text: String?, val timestampMs: Long, val from: Person?, val pictureMime: String? = null)

    /** A chat notification: [lines] in a conversation named [groupTitle] (null for one person). */
    fun chat(
        context: Context,
        lines: List<Line>,
        groupTitle: String? = null,
        shortcut: String? = "thread-1",
        withReply: Boolean = true,
        category: String? = Notification.CATEGORY_MESSAGE,
        configure: (NotificationCompat.Builder) -> Unit = {},
    ): Notification {
        val style = NotificationCompat.MessagingStyle(me)
        if (groupTitle != null) {
            style.conversationTitle = groupTitle
            style.isGroupConversation = true
        }
        for (line in lines) {
            val message = NotificationCompat.MessagingStyle.Message(line.text, line.timestampMs, line.from)
            if (line.pictureMime != null) message.setData(line.pictureMime, Uri.parse("content://messenger.test/picture/1"))
            style.addMessage(message)
        }
        val builder = NotificationCompat.Builder(context, "chats")
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setStyle(style)
            .setCategory(category)
        if (shortcut != null) builder.setShortcutId(shortcut)
        if (withReply) builder.addAction(replyAction(context))
        configure(builder)
        return builder.build()
    }

    fun replyAction(context: Context, requestCode: Int = 1): NotificationCompat.Action {
        val intent = PendingIntent.getBroadcast(
            context, requestCode, Intent(REPLY_ACTION).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.Action.Builder(android.R.drawable.ic_menu_send, "Reply", intent)
            .addRemoteInput(RemoteInput.Builder(REPLY_KEY).setLabel("Reply").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()
    }

    @Suppress("DEPRECATION")
    fun posted(notification: Notification, packageName: String = MessengerCapture.MESSENGER_PACKAGE, tag: String? = "t1", id: Int = 1): StatusBarNotification =
        StatusBarNotification(packageName, packageName, id, tag, 10123, 0, 0, notification, Process.myUserHandle(), 5_000L)
}
