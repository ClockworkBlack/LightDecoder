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
    /** OpenBubbles' chat ids are UUIDs (Apple's conversation id, or one it made up). */
    const val ALICE_GUID = "5D2B5A4C-1F0E-4C3B-9A7D-2E6F1B0C9D11"
    const val GROUP_GUID = "8A1F3E2D-6C4B-4D5A-9E8F-7B6A5C4D3E2F"

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
        /** Which app made its buttons: OpenBubbles itself, unless a test says otherwise. */
        buttonsBy: String = PACKAGE,
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
            builder.addAction(markReadAction(context, requestCode, buttonsBy))
            builder.addAction(replyAction(context, requestCode, buttonsBy))
        }
        return builder.build()
    }

    /**
     * OpenBubbles installed here, and its certificate taken for the pinned one (Robolectric fakes
     * no signing certificates): what a phone with the real OpenBubbles from the Play Store has.
     */
    fun trust(context: Context) {
        val pm = org.robolectric.Shadows.shadowOf(context.packageManager)
        if (runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isFailure) {
            pm.installPackage(android.content.pm.PackageInfo().apply { packageName = PACKAGE })
        }
        OpenBubbles.verifier = { _, pkg, _ -> pkg == PACKAGE }
    }

    /** A button OpenBubbles made: its PendingIntent's maker is [PACKAGE] (Robolectric says the test app otherwise). */
    private fun madeByOpenBubbles(intent: PendingIntent, creator: String): PendingIntent =
        intent.also { org.robolectric.Shadows.shadowOf(it).setCreatorPackage(creator) }

    fun replyAction(context: Context, requestCode: Int = 1, creator: String = PACKAGE): NotificationCompat.Action {
        val intent = madeByOpenBubbles(
            PendingIntent.getBroadcast(
                context, requestCode, Intent(REPLY_ACTION).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
            creator,
        )
        return NotificationCompat.Action.Builder(0, "Reply", intent)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .addRemoteInput(RemoteInput.Builder(REPLY_KEY).setLabel("Reply").build())
            .build()
    }

    fun markReadAction(context: Context, requestCode: Int = 1, creator: String = PACKAGE): NotificationCompat.Action {
        val intent = madeByOpenBubbles(
            PendingIntent.getBroadcast(
                context, 100 + requestCode, Intent(MARK_READ_ACTION).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
            creator,
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
