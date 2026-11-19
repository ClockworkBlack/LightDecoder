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
    /**
     * OpenBubbles' id of the chat: a UUID (Apple's id for the conversation,
     * or one OpenBubbles made up), which names no one.
     */
    val chatGuid: String,
    /** The newest message's OpenBubbles id (the notification's "messageGuid"); empty if not given. */
    val messageGuid: String,
    val title: String,
    val isGroup: Boolean,
    /**
     * The other person's address in a one-to-one chat ("+15551234567",
     * "name@example.com"), from the sender's handle; empty when the
     * notification did not carry one (a group, or a "Notify Anyways" post).
     */
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
 * extras "chatGuid" (the chat's UUID) and "messageGuid" (the newest message),
 * the sender as a Person whose uri is their handle ("tel:+1…", "mailto:…") in
 * a one-to-one chat, a MessagingStyle
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

    /** What OpenBubbles shows in place of a message with its "Hide Message Text" setting on. */
    const val HIDDEN_TEXT = "iMessage"

    /** [parseOrWhyNot]'s reason for such a notification. */
    const val HIDDEN_REASON = "OpenBubbles hides message text (its setting Hide Message Text is on)"


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
    fun parse(packageName: String?, tag: String?, notification: Notification, postTimeMs: Long): CapturedIMessageChat? =
        parseOrWhyNot(packageName, tag, notification, postTimeMs).first

    /**
     * [parse], with why a notification of OpenBubbles' was not used (for the
     * iMessage log) when it was not. Not every OpenBubbles build is the same:
     * the tag is not required, and a chat id missing from the extras is taken
     * from the notification's conversation shortcut.
     */
    fun parseOrWhyNot(packageName: String?, tag: String?, notification: Notification, postTimeMs: Long): Pair<CapturedIMessageChat?, String> {
        fun no(why: String) = null to why
        if (!OpenBubbles.isOpenBubbles(packageName)) return no("not OpenBubbles")
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return no("group summary")
        val extras = notification.extras ?: return no("no extras")
        val shortcut = if (android.os.Build.VERSION.SDK_INT >= 26) notification.shortcutId?.trim().orEmpty() else ""
        val chatGuid = extras.getString(OpenBubbles.EXTRA_CHAT_GUID)?.trim()?.takeIf { it.isNotEmpty() && it != "-1" }
            ?: shortcut.takeIf { it.isNotEmpty() && it != "-1" }
            // "-1" is the summary's "open the app" id.
            ?: return no("no chat id (no chatGuid extra, no conversation shortcut)")
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            ?: return no("not a chat-style (MessagingStyle) notification")
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
        // OpenBubbles' "Hide Message Text" setting: every line reads "iMessage". Not a message to keep.
        if (messages.isNotEmpty() && messages.all { it.text == HIDDEN_TEXT }) return no(HIDDEN_REASON)
        if (messages.isEmpty()) return no("no message with text in it (${style.messages.size} without text: a picture, a reaction?)")
        val isGroup = style.isGroupConversation
        val shownTitle = extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()?.trim().orEmpty().removePrefix(NOTIFY_ANYWAYS)
        val groupTitle = style.conversationTitle?.toString()?.trim().orEmpty().removePrefix(NOTIFY_ANYWAYS)
        val other = messages.lastOrNull { !it.fromMe && it.sender.isNotEmpty() }?.sender.orEmpty()
        // No handle on the person (some OpenBubbles builds leave it out), but the chat is named by a
        // number or an email address (someone not in the contacts): that is the address.
        val address = if (isGroup) "" else addressOf(messages).ifEmpty {
            listOf(other, shownTitle).firstOrNull { IMessageAddress.key(it) != null }.orEmpty()
        }
        val title = (if (isGroup) groupTitle else "")
            .ifEmpty { other }
            .ifEmpty { shownTitle.takeUnless { it == user.name?.toString()?.trim() }.orEmpty() }
            .ifEmpty { groupTitle }
            .ifEmpty { address }
        if (title.isEmpty()) return no("no name for the chat")
        val messageGuid = extras.getString(OpenBubbles.EXTRA_MESSAGE_GUID)?.trim().orEmpty()
        return CapturedIMessageChat(chatGuid, messageGuid, title, isGroup, address, messages) to ""
    }

    /**
     * What a notification is made of, without anything it says: tag, channel,
     * category, style, how many messages, which extras it has (names only)
     * and its buttons (whether each takes typed text, and its kind). For the
     * iMessage log when a notification of OpenBubbles' is not used.
     */
    fun shape(tag: String?, id: Int, notification: Notification): String {
        val extras = notification.extras
        val keys = extras?.keySet()?.sorted()?.joinToString(",").orEmpty()
        val template = extras?.getString(Notification.EXTRA_TEMPLATE)?.substringAfterLast('$')?.substringAfterLast('.').orEmpty()
        val messages = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)?.messages?.size }.getOrNull()
        val actions = notification.actions.orEmpty().joinToString(",") { a ->
            val input = if (a.remoteInputs.isNullOrEmpty()) "" else "+text"
            val kind = if (android.os.Build.VERSION.SDK_INT >= 28) "s${a.semanticAction}" else ""
            "[$kind$input]"
        }
        val shortcut = if (android.os.Build.VERSION.SDK_INT >= 26) !notification.shortcutId.isNullOrEmpty() else false
        // A channel of its own per chat may carry the chat's id: then only that it is one.
        val channel = (if (android.os.Build.VERSION.SDK_INT >= 26) notification.channelId.orEmpty() else "")
            .let { if (it.length > 48 || Regex("[0-9A-Fa-f]{8}|\\d{6}|@").containsMatchIn(it)) "(per-chat)" else it }
        return "tag=${if (tag == OpenBubbles.MESSAGE_TAG) "message" else if (tag == null) "none" else "other"} id=$id " +
            "channel=$channel category=${notification.category.orEmpty()} style=${template.ifEmpty { "none" }} " +
            "messages=${messages ?: "-"} shortcut=$shortcut summary=${notification.flags and Notification.FLAG_GROUP_SUMMARY != 0} " +
            "actions=$actions extras=[$keys]"
    }

    /** The other person's address, from a message's handle ("tel:…" or "mailto:…"). */
    private fun addressOf(messages: List<CapturedIMessage>): String {
        val handle = messages.lastOrNull { it.senderHandle.isNotEmpty() }?.senderHandle.orEmpty().trim()
        return when {
            handle.startsWith("tel:") -> handle.removePrefix("tel:")
            handle.startsWith("mailto:") -> handle.removePrefix("mailto:")
            else -> ""
        }.trim()
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
