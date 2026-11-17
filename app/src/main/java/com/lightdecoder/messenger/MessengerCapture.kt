package com.lightdecoder.messenger

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import java.security.MessageDigest

/** One message as Messenger's notification showed it. */
data class CapturedMessage(
    /** Who wrote it; empty for the phone's own user. */
    val sender: String,
    val fromMe: Boolean,
    /** The text; for a picture, whatever caption the notification gave (often none). */
    val text: String,
    val kind: Int,
    val timestampMs: Long,
)

/** What one of Messenger's notifications said about one conversation. */
data class CapturedConversation(
    /** Stable for the conversation as long as Messenger names it the same way. */
    val key: String,
    val title: String,
    val isGroup: Boolean,
    val messages: List<CapturedMessage>,
)

/**
 * The reply button of one of Messenger's notifications: the same "Reply"
 * the notification shade, a watch or a car shows. Filling it in and sending
 * it makes the Messenger app send the message; LightDecoder never talks to
 * Meta for it.
 */
class MessengerReplyAction(val intent: PendingIntent, val inputs: Array<RemoteInput>)

/**
 * Reads Messenger's own notifications, through the public notification
 * format (MessagingStyle) every chat app uses for the shade, watches and
 * cars. No Messenger code, protocol or account is involved: what is read is
 * what Android was given to show.
 *
 * What that means for what can be caught:
 *  - only messages that arrive as a notification, from the moment
 *    notification access is on. No history.
 *  - nothing while the conversation is open in Messenger, muted there, or
 *    while Messenger holds notifications back (it does when it sees you
 *    active on another device).
 *  - pictures, voice clips and stickers usually arrive as a line of text
 *    ("sent a photo"), not as the thing itself.
 */
object MessengerCapture {

    /** The Messenger app. The only package whose notifications are read. */
    const val MESSENGER_PACKAGE = "com.facebook.orca"

    const val KIND_TEXT = 0
    const val KIND_PICTURE = 1

    fun isMessenger(packageName: String?): Boolean = packageName == MESSENGER_PACKAGE

    fun parse(sbn: StatusBarNotification): CapturedConversation? {
        val notification = sbn.notification ?: return null
        return parse(sbn.packageName, sbn.tag, notification, sbn.postTime)
    }

    /**
     * The conversation and messages [notification] shows, or null when it is
     * not a message notification of Messenger's (another app's, a call, the
     * summary line of a bundle, a promotion).
     */
    fun parse(packageName: String?, tag: String?, notification: Notification, postTimeMs: Long): CapturedConversation? {
        if (!isMessenger(packageName)) return null
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return null
        if (notification.category in IGNORED_CATEGORIES) return null

        val extras = notification.extras
        val shownTitle = extras?.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val fallbackTime = notification.`when`.takeIf { it > 0 } ?: postTimeMs
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)

        if (style != null && style.messages.isNotEmpty()) {
            val user = style.user
            val groupTitle = style.conversationTitle?.toString()?.trim().orEmpty()
            val messages = style.messages.mapNotNull { message ->
                val person = message.person
                val fromMe = person == null || samePerson(person, user)
                val picture = message.dataUri != null && message.dataMimeType?.startsWith("image/") == true
                val text = message.text?.toString()?.trim().orEmpty()
                if (text.isEmpty() && !picture) return@mapNotNull null
                CapturedMessage(
                    sender = if (fromMe) "" else person?.name?.toString()?.trim().orEmpty(),
                    fromMe = fromMe,
                    text = text,
                    kind = if (picture) KIND_PICTURE else KIND_TEXT,
                    timestampMs = message.timestamp.takeIf { it > 0 } ?: fallbackTime,
                )
            }
            if (messages.isEmpty()) return null
            // A group's own name; else the name on the notification, unless
            // that is the user's (it can be, when the newest line is their
            // own reply); else whoever wrote to them.
            val other = messages.firstOrNull { !it.fromMe && it.sender.isNotEmpty() }?.sender.orEmpty()
            val title = groupTitle
                .ifEmpty { shownTitle.takeUnless { it == user.name?.toString()?.trim() }.orEmpty() }
                .ifEmpty { other }
                .ifEmpty { shownTitle }
            if (title.isEmpty()) return null
            return CapturedConversation(
                key = keyFor(notification, tag, title),
                title = title,
                isGroup = style.isGroupConversation || groupTitle.isNotEmpty(),
                messages = messages,
            )
        }

        // An older or plainer notification: a name and one line. Only when
        // Messenger itself marks it as a message, so that its other
        // notifications (suggestions, reminders) are not taken for chats.
        if (notification.category != Notification.CATEGORY_MESSAGE) return null
        val text = (extras?.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)
            ?: extras?.getCharSequence(NotificationCompat.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (shownTitle.isEmpty() || text.isEmpty()) return null
        return CapturedConversation(
            key = keyFor(notification, tag, shownTitle),
            title = shownTitle,
            isGroup = false,
            messages = listOf(CapturedMessage(shownTitle, false, text, KIND_TEXT, fallbackTime)),
        )
    }

    /**
     * The notification's reply button, if it has one that takes typed text.
     * The button marked as "reply" wins; a watch-only one is the last resort.
     */
    fun replyAction(notification: Notification): MessengerReplyAction? {
        val shade = notification.actions?.toList().orEmpty()
        val watch = Notification.WearableExtender(notification).actions.orEmpty()
        val candidates = (shade + watch).filter { action ->
            action.actionIntent != null && action.remoteInputs?.any { it.allowFreeFormInput } == true
        }
        val chosen = candidates.firstOrNull {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY
        } ?: candidates.firstOrNull() ?: return null
        return MessengerReplyAction(chosen.actionIntent, chosen.remoteInputs)
    }

    /**
     * The same id for the same message every time its notification is shown
     * again (a chat notification repeats its recent messages on every update).
     */
    fun messageId(conversationKey: String, message: CapturedMessage): String = sha(
        conversationKey, message.timestampMs.toString(), message.sender,
        if (message.fromMe) "me" else "them", message.kind.toString(), message.text,
    )

    /**
     * What tells conversations apart. The conversation shortcut Messenger
     * attaches is best (it is what Android itself files the conversation
     * under); then the notification's tag; the title only as a last resort,
     * where two people of the same name would share an entry.
     */
    private fun keyFor(notification: Notification, tag: String?, title: String): String {
        val shortcut = notification.shortcutId
        return when {
            !shortcut.isNullOrBlank() -> "s:" + sha(shortcut)
            !tag.isNullOrBlank() -> "g:" + sha(tag)
            else -> "t:" + sha(title)
        }
    }

    private fun samePerson(a: androidx.core.app.Person, b: androidx.core.app.Person): Boolean {
        val keyA = a.key
        val keyB = b.key
        if (keyA != null && keyB != null) return keyA == keyB
        return a.name != null && a.name.toString() == b.name?.toString()
    }

    private fun sha(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            digest.update(part.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().take(16).joinToString("") { "%02x".format(it) }
    }

    private val IGNORED_CATEGORIES = setOf(
        Notification.CATEGORY_CALL, Notification.CATEGORY_MISSED_CALL, Notification.CATEGORY_PROMO,
        Notification.CATEGORY_RECOMMENDATION, Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_SERVICE,
    )
}
