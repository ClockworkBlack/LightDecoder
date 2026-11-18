package com.lightdecoder.imessage

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import java.security.MessageDigest

/** One message as OpenBubbles' notification showed it. */
data class CapturedIMessage(
    /** Who wrote it; empty for the user's own messages. */
    val sender: String,
    /** The sender's handle ("tel:+1…" or "mailto:…") where OpenBubbles gave one (one-to-one chats). */
    val senderHandle: String,
    val fromMe: Boolean,
    val text: String,
    val timestampMs: Long,
)

/** What one of OpenBubbles' notifications said about one chat. */
data class CapturedIMessageChat(
    /** OpenBubbles' id of the chat ("iMessage;-;+15551234567", "iMessage;+;chat…"). */
    val chatGuid: String,
    val title: String,
    val isGroup: Boolean,
    /** The other person's address in a one-to-one chat ("+15551234567", "name@example.com"), else empty. */
    val address: String,
    val messages: List<CapturedIMessage>,
)

/** A button of one of OpenBubbles' notifications: [inputs] is empty for one that takes no text (Mark As Read). */
class IMessageAction(val intent: PendingIntent, val inputs: Array<RemoteInput>)

/**
 * Reads OpenBubbles' message notifications through the public notification
 * format (MessagingStyle) that phones, watches and cars use. No OpenBubbles
 * code is involved; what is read is what Android was given to show.
 *
 * How OpenBubbles posts them (its CreateIncomingMessageNotification and
 * InternalIntentReceiver): one notification per chat, tag [OpenBubbles.MESSAGE_TAG],
 * extras "chatGuid" and "messageGuid" (the newest message), a MessagingStyle
 * whose user is "You" and which keeps the chat's earlier lines while the
 * notification is up, a Reply button (text key "text_reply") and a Mark As
 * Read button. After a reply is sent from the notification, OpenBubbles
 * posts it again with the reply added: that line is the user's, under their
 * profile name, and its time is in seconds instead of milliseconds.
 *
 * What cannot be caught: chats open in OpenBubbles, muted there or from
 * blocked senders, messages sent from the user's other devices, and the
 * contents of pictures (they arrive as a line of text).
 */
object IMessageCapture {

    /**
     * Times below this are seconds, not milliseconds: OpenBubbles stamps the
     * line of a reply sent from its notification that way. (It is 1973 in
     * milliseconds and the year 5138 in seconds.)
     */
    private const val SECONDS_LIMIT = 100_000_000_000L

    private const val NOTIFY_ANYWAYS = "Notify Anyways: "

    fun parse(sbn: StatusBarNotification): CapturedIMessageChat? {
        val notification = sbn.notification ?: return null
        return parse(sbn.packageName, sbn.tag, notification, sbn.postTime)
    }

    /**
     * The chat and messages [notification] shows, or null when it is not one
     * of OpenBubbles' iMessage notifications (another app's, the group
     * summary, a call, a text-message chat, one with nothing in it).
     */
    fun parse(packageName: String?, tag: String?, notification: Notification, postTimeMs: Long): CapturedIMessageChat? {
        if (!OpenBubbles.isOpenBubbles(packageName)) return null
        if (tag != null && tag != OpenBubbles.MESSAGE_TAG) return null
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val extras = notification.extras ?: return null
        val chatGuid = extras.getString(OpenBubbles.EXTRA_CHAT_GUID)?.trim().orEmpty()
        if (chatGuid.isEmpty() || chatGuid == "-1" || !OpenBubbles.isIMessageChat(chatGuid)) return null
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification) ?: return null
        val user = style.user
        val fallbackTime = notification.`when`.takeIf { it > 0 } ?: postTimeMs
        val messages = style.messages.mapNotNull { message ->
            val text = message.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            val person = message.person
            val raw = message.timestamp
            val inSeconds = raw in 1 until SECONDS_LIMIT
            val fromMe = person == null || inSeconds || samePerson(person, user)
            CapturedIMessage(
                sender = if (fromMe) "" else person?.name?.toString()?.trim().orEmpty(),
                senderHandle = if (fromMe) "" else person?.uri.orEmpty(),
                fromMe = fromMe,
                text = text,
                timestampMs = when {
                    inSeconds -> raw * 1000
                    raw > 0 -> raw
                    else -> fallbackTime
                },
            )
        }
        if (messages.isEmpty()) return null
        val isGroup = style.isGroupConversation || chatGuid.contains(";+;")
        val shownTitle = extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()?.trim().orEmpty().removePrefix(NOTIFY_ANYWAYS)
        val groupTitle = style.conversationTitle?.toString()?.trim().orEmpty().removePrefix(NOTIFY_ANYWAYS)
        val other = messages.lastOrNull { !it.fromMe && it.sender.isNotEmpty() }?.sender.orEmpty()
        val address = if (isGroup) "" else addressOf(chatGuid, messages)
        val title = (if (isGroup) groupTitle else "")
            .ifEmpty { other }
            .ifEmpty { shownTitle.takeUnless { it == user.name?.toString()?.trim() }.orEmpty() }
            .ifEmpty { groupTitle }
            .ifEmpty { address }
        if (title.isEmpty()) return null
        return CapturedIMessageChat(chatGuid, title, isGroup, address, messages)
    }

    /** The other person's address: from a message's handle, else from the chat's id. */
    private fun addressOf(chatGuid: String, messages: List<CapturedIMessage>): String {
        val handle = messages.firstOrNull { it.senderHandle.isNotEmpty() }?.senderHandle.orEmpty()
        val fromHandle = handle.substringAfter("tel:", "").ifEmpty { handle.substringAfter("mailto:", "") }.trim()
        return fromHandle.ifEmpty { OpenBubbles.addressOf(chatGuid).orEmpty() }
    }

    /** The notification's Reply button (takes typed text). */
    fun replyAction(notification: Notification): IMessageAction? {
        val candidates = actions(notification).filter { action ->
            action.actionIntent != null && action.remoteInputs?.any { it.allowFreeFormInput } == true
        }
        val chosen = candidates.firstOrNull {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY
        } ?: candidates.firstOrNull() ?: return null
        return IMessageAction(chosen.actionIntent, chosen.remoteInputs)
    }

    /** The notification's Mark As Read button: it clears OpenBubbles' notification and tells the sender, as OpenBubbles is set to. */
    fun markReadAction(notification: Notification): IMessageAction? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val chosen = actions(notification).firstOrNull {
            it.actionIntent != null && it.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ
        } ?: return null
        return IMessageAction(chosen.actionIntent, emptyArray())
    }

    private fun actions(notification: Notification): List<Notification.Action> =
        notification.actions?.toList().orEmpty() + Notification.WearableExtender(notification).actions.orEmpty()

    /**
     * The same id for the same message every time its notification is shown
     * again (the chat's notification repeats its recent lines on every update).
     */
    fun messageId(chatGuid: String, message: CapturedIMessage): String = "ob:" + sha(
        chatGuid, message.timestampMs.toString(), message.sender, if (message.fromMe) "me" else "them", message.text,
    )

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
}
