package com.lightdecoder.relay

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.ConversationRow

/**
 * Optional notifications for messages that arrive through the link, one per
 * conversation in MessagingStyle, with Reply (answered by [ReplyReceiver]
 * through the outbox) and Mark as read.
 *
 * Off by default ([RelayPrefs.notifyMessages]): Google Messages on the phone
 * already notifies. Never for history, for the user's own messages, for a
 * muted conversation, or for the conversation on screen.
 *
 * The notification shows message text, which is its purpose; nothing about
 * it is logged.
 */
object MessageNotifier {
    const val ACTION_REPLY = "com.lightdecoder.relay.action.REPLY"
    const val ACTION_MARK_READ = "com.lightdecoder.relay.action.MARK_READ"
    const val ACTION_DISMISSED = "com.lightdecoder.relay.action.DISMISSED"
    const val KEY_REPLY_TEXT = "com.lightdecoder.relay.REPLY_TEXT"

    /** All message notifications share this id; the conversation id is the tag. */
    private const val NOTIFICATION_ID = 0x5245_4c10
    private const val MAX_LINES = 6

    /**
     * The conversation whose screen is showing, so its messages are not
     * notified. ThreadActivity sets it in onResume and clears it in onPause.
     */
    @Volatile
    var visibleConversationId: String? = null

    private class Line(val sender: String, val text: CharSequence, val timestampMs: Long, val fromMe: Boolean)

    /** Recent lines per notified conversation; guarded by this object. */
    private val lines = HashMap<String, ArrayDeque<Line>>()

    /** Called for every new live message stored; decides whether to notify. */
    fun onIncoming(context: Context, repo: ArchiveRepository, message: MessageDto) {
        if (!RelayPrefs.notifyMessages(context)) return
        if (message.isOld || message.fromMe || message.direction != "in") return
        if (message.conversationId == visibleConversationId) return
        val conversation = repo.conversation(message.conversationId)
        if (conversation?.muted == true) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val sender = message.senderName.ifBlank { conversation?.name.orEmpty() }
            .ifBlank { context.getString(R.string.relay_notify_someone) }
        val line = Line(sender, bodyOf(context, message), message.timestampMs.takeIf { it > 0 } ?: System.currentTimeMillis(), false)
        post(context, message.conversationId, conversation, add(message.conversationId, line), alert = true)
    }

    /** Shows the user's reply in the notification (which also ends the reply spinner). */
    fun onReplySent(context: Context, repo: ArchiveRepository, conversationId: String, text: String) {
        if (!RelayPrefs.notifyMessages(context)) {
            // Turned off since the notification was posted: no message text
            // in notifications any more; removing it also ends the spinner.
            cancel(context, conversationId)
            return
        }
        val line = Line(context.getString(R.string.relay_notify_you), text, System.currentTimeMillis(), true)
        post(context, conversationId, repo.conversation(conversationId), add(conversationId, line), alert = false)
    }

    fun cancel(context: Context, conversationId: String) {
        synchronized(this) { lines.remove(conversationId) }
        NotificationManagerCompat.from(context).cancel(conversationId, NOTIFICATION_ID)
    }

    /** The user swiped the notification away: start afresh next time. */
    fun forget(conversationId: String) {
        synchronized(this) { lines.remove(conversationId) }
    }

    fun cancelAll(context: Context) {
        synchronized(this) { lines.clear() }
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            system.activeNotifications
                .filter { it.id == NOTIFICATION_ID && it.tag != null }
                .forEach { system.cancel(it.tag, NOTIFICATION_ID) }
        } catch (e: RuntimeException) {
            // Best effort.
        }
    }

    private fun add(conversationId: String, line: Line): List<Line> = synchronized(this) {
        val queue = lines.getOrPut(conversationId) { ArrayDeque() }
        queue.addLast(line)
        while (queue.size > MAX_LINES) queue.removeFirst()
        queue.toList()
    }

    private fun bodyOf(context: Context, message: MessageDto): CharSequence {
        if (message.text.isNotBlank()) return message.text
        val media = message.mediaParts.firstOrNull() ?: return ""
        return context.getString(if (media.mime.startsWith("image/")) R.string.relay_notify_photo else R.string.relay_notify_attachment)
    }

    @SuppressLint("MissingPermission") // checked with areNotificationsEnabled()
    private fun post(context: Context, conversationId: String, conversation: ConversationRow?, recent: List<Line>, alert: Boolean) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        RelayNotifications.ensureChannels(context)

        val me = Person.Builder().setName(context.getString(R.string.relay_notify_you)).setKey("me").build()
        val isGroup = conversation?.isGroup == true
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(if (isGroup) conversation?.name else null)
            .setGroupConversation(isGroup)
        for (line in recent) {
            val person = if (line.fromMe) null else Person.Builder().setName(line.sender).build()
            style.addMessage(line.text, line.timestampMs, person)
        }

        val base = conversationId.hashCode() * 4
        val open = PendingIntent.getActivity(
            context, base, RelayIntents.thread(context, conversationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val reply = NotificationCompat.Action.Builder(
            R.drawable.ic_relay_reply,
            context.getString(R.string.relay_notify_reply),
            // RemoteInput fills in the reply, so this one must be mutable; the intent is explicit.
            PendingIntent.getBroadcast(
                context, base + 1, receiverIntent(context, ACTION_REPLY, conversationId),
                PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag(),
            ),
        )
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY_TEXT).setLabel(context.getString(R.string.relay_notify_reply)).build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        val markRead = NotificationCompat.Action.Builder(
            R.drawable.ic_relay_done,
            context.getString(R.string.relay_notify_mark_read),
            PendingIntent.getBroadcast(
                context, base + 2, receiverIntent(context, ACTION_MARK_READ, conversationId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
        val dismissed = PendingIntent.getBroadcast(
            context, base + 3, receiverIntent(context, ACTION_DISMISSED, conversationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, RelayNotifications.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setStyle(style)
            .setContentIntent(open)
            .setDeleteIntent(dismissed)
            .addAction(reply)
            .addAction(markRead)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setWhen(recent.lastOrNull()?.timestampMs ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setOnlyAlertOnce(!alert)
            .setSilent(!alert)
            .build()
        try {
            manager.notify(conversationId, NOTIFICATION_ID, notification)
        } catch (e: RuntimeException) {
            // Without the permission on some builds.
        }
    }

    private fun receiverIntent(context: Context, action: String, conversationId: String): Intent =
        Intent(context, ReplyReceiver::class.java)
            .setAction(action)
            .putExtra(RelayIntents.EXTRA_CONVERSATION_ID, conversationId)

    private fun mutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
}
