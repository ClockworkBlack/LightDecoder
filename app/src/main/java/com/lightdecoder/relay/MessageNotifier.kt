package com.lightdecoder.relay

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.ConversationRow
import com.lightdecoder.chat.ChatService
import com.lightdecoder.wa.WaIds
import com.lightdecoder.wa.WaPrefs
import java.util.concurrent.ConcurrentHashMap

/**
 * Optional notifications for messages that arrive through a link, one per
 * conversation in MessagingStyle, with Reply (answered by [ReplyReceiver]
 * through the service's outbox) and Mark as read.
 *
 * Each [ChatService] has its own channel
 * ([RelayNotifications.messagesChannel]), its own switch ([enabled]), its own
 * notification id and its own tags, so the two services' notifications never
 * replace or cancel each other. Where an archive is passed in, the service is
 * the archive's ([ArchiveRepository.service]); elsewhere it is a parameter
 * that defaults to Google Messages, which was all there was.
 *
 * On by default for both services (each has its switch in its link's
 * settings, and its own channel in Android's settings).
 * Never for history, for the user's own messages, for a muted conversation (unless
 * the message mentions the user, who asked to be told of that),
 * or for the conversation on screen.
 *
 * The notification shows message text, which is its purpose; nothing about
 * it is logged. Its tag is something other apps can read (notification
 * listeners, bug reports): a Google Messages conversation id says nothing
 * about anyone, but a WhatsApp one holds a phone number, so WhatsApp's tags
 * are [WaIds.opaque] stand-ins.
 */
object MessageNotifier {
    const val ACTION_REPLY = "com.lightdecoder.relay.action.REPLY"
    const val ACTION_MARK_READ = "com.lightdecoder.relay.action.MARK_READ"
    const val ACTION_DISMISSED = "com.lightdecoder.relay.action.DISMISSED"
    const val KEY_REPLY_TEXT = "com.lightdecoder.relay.REPLY_TEXT"

    /** All of a service's message notifications share one id; the conversation's tag tells them apart. */
    private const val NOTIFICATION_ID = 0x5245_4c10
    private const val NOTIFICATION_ID_WHATSAPP = 0x5245_4c11
    private const val NOTIFICATION_ID_GMAIL = 0x5245_4c12
    private const val NOTIFICATION_ID_IMESSAGE = 0x5245_4c13
    private const val MAX_LINES = 6

    /** The size of a notification's picture. */
    private const val PHOTO_DP = 64

    /** Tags of WhatsApp's notifications start with this; Google Messages' are the bare conversation id, as before. */
    private const val WHATSAPP_TAG_PREFIX = "wa:"
    private const val GMAIL_TAG_PREFIX = "gm:"
    private const val IMESSAGE_TAG_PREFIX = "im:"

    /** What makes each conversation's Reply, Mark as read and dismissal intents distinct ([receiverIntent]). */
    private const val INTENT_SCHEME = "lightdecoder-notify"

    private val visible = ConcurrentHashMap<ChatService, String>()

    /**
     * The Google Messages conversation whose screen is showing; the same as
     * [visibleConversationId] / [setVisibleConversationId] with
     * [ChatService.MESSAGES].
     */
    var visibleConversationId: String?
        get() = visibleConversationId(ChatService.MESSAGES)
        set(value) = setVisibleConversationId(ChatService.MESSAGES, value)

    /**
     * The conversation of [service] whose screen is showing, so its messages
     * are not notified; null when none is. ThreadActivity sets it in onResume
     * and clears it in onPause.
     */
    fun visibleConversationId(service: ChatService): String? = visible[service]

    fun setVisibleConversationId(service: ChatService, conversationId: String?) {
        if (conversationId == null) visible.remove(service) else visible[service] = conversationId
    }

    /** Whether the user turned message notifications on for [service]. */
    fun enabled(context: Context, service: ChatService): Boolean = when (service) {
        ChatService.MESSAGES -> RelayPrefs.notifyMessages(context)
        ChatService.WHATSAPP -> WaPrefs.notifyMessages(context)
        ChatService.GMAIL -> com.lightdecoder.mail.MailPrefs.notifyMessages(context)
        ChatService.IMESSAGE -> com.lightdecoder.imessage.IMessagePrefs.notifyMessages(context) &&
            com.lightdecoder.imessage.IMessagePrefs.enabled(context)
    }

    private class Line(val sender: String, val text: CharSequence, val timestampMs: Long, val fromMe: Boolean)

    private data class Key(val service: ChatService, val conversationId: String)

    /** Recent lines per notified conversation; guarded by this object. */
    private val lines = HashMap<Key, ArrayDeque<Line>>()

    /**
     * Called for every new live message stored in [repo]; decides whether to
     * notify. The service is [repo]'s. [mentioned]: the message mentions the
     * user, who asked to hear of that even in a muted conversation (an
     * iPhone's "Notify Me"): its mute does not keep it quiet.
     */
    fun onIncoming(context: Context, repo: ArchiveRepository, message: MessageDto, mentioned: Boolean = false) {
        val service = repo.service
        if (!enabled(context, service)) return
        if (message.isOld || message.fromMe || message.direction != "in") return
        if (message.conversationId == visibleConversationId(service)) return
        val conversation = repo.conversation(message.conversationId)
        if (conversation?.muted == true && !mentioned) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val sender = message.senderName.ifBlank { conversation?.name.orEmpty() }
            .ifBlank { context.getString(R.string.relay_notify_someone) }
        val line = Line(sender, bodyOf(context, message), message.timestampMs.takeIf { it > 0 } ?: System.currentTimeMillis(), false)
        val key = Key(service, message.conversationId)
        post(context, key, conversation, add(key, line), alert = true, photo = photoFor(context, repo, message.conversationId, conversation))
    }

    /**
     * Shows the user's reply in the notification (which also ends the reply
     * spinner). The service is [repo]'s.
     */
    fun onReplySent(context: Context, repo: ArchiveRepository, conversationId: String, text: String) {
        val service = repo.service
        if (!enabled(context, service)) {
            // Turned off since the notification was posted: no message text
            // in notifications any more; removing it also ends the spinner.
            cancel(context, conversationId, service)
            return
        }
        val line = Line(context.getString(R.string.relay_notify_you), text, System.currentTimeMillis(), true)
        val key = Key(service, conversationId)
        val conversation = repo.conversation(conversationId)
        post(context, key, conversation, add(key, line), alert = false, photo = photoFor(context, repo, conversationId, conversation))
    }

    /**
     * The picture a conversation is shown by ([com.lightdecoder.ui.chat.AvatarPhotos]): an iMessage
     * group's photo, a person's picture; in a circle, for the notification. Null for none; never throws.
     */
    private fun photoFor(context: Context, repo: ArchiveRepository, conversationId: String, conversation: ConversationRow?): android.graphics.Bitmap? =
        try {
            val file = com.lightdecoder.ui.chat.AvatarPhotos.forConversation(
                context.applicationContext, repo.service, conversationId, conversation?.isGroup == true, repo.participants(conversationId),
            )
            file?.let { com.lightdecoder.ui.chat.Avatars.circlePhoto(it, (PHOTO_DP * context.resources.displayMetrics.density).toInt()) }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }

    fun cancel(context: Context, conversationId: String, service: ChatService = ChatService.MESSAGES) {
        synchronized(this) { lines.remove(Key(service, conversationId)) }
        NotificationManagerCompat.from(context).cancel(tagFor(context, service, conversationId), idFor(service))
    }

    /** The user swiped the notification away: start afresh next time. */
    fun forget(conversationId: String, service: ChatService = ChatService.MESSAGES) {
        synchronized(this) { lines.remove(Key(service, conversationId)) }
    }

    /** Removes every message notification of [service]; the other service's stay. */
    fun cancelAll(context: Context, service: ChatService = ChatService.MESSAGES) {
        synchronized(this) { lines.keys.removeAll { it.service == service } }
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        val id = idFor(service)
        try {
            system.activeNotifications
                .filter { it.id == id && it.tag != null }
                .forEach { system.cancel(it.tag, id) }
        } catch (e: RuntimeException) {
            // Best effort.
        }
    }

    private fun idFor(service: ChatService): Int = when (service) {
        ChatService.MESSAGES -> NOTIFICATION_ID
        ChatService.WHATSAPP -> NOTIFICATION_ID_WHATSAPP
        ChatService.GMAIL -> NOTIFICATION_ID_GMAIL
        ChatService.IMESSAGE -> NOTIFICATION_ID_IMESSAGE
    }

    /**
     * The notification's tag, which other apps can read. Stable across
     * restarts, so a notification posted before one can still be cancelled.
     */
    internal fun tagFor(context: Context, service: ChatService, conversationId: String): String = when (service) {
        ChatService.MESSAGES -> conversationId
        ChatService.WHATSAPP -> WHATSAPP_TAG_PREFIX + WaIds.opaque(context, conversationId)
        // A mail conversation id is a hash of a Message-ID: it names no one.
        ChatService.GMAIL -> GMAIL_TAG_PREFIX + conversationId
        // iMessage chat ids carry phone numbers and email addresses.
        ChatService.IMESSAGE -> IMESSAGE_TAG_PREFIX + com.lightdecoder.imessage.IMessagePrefs.opaque(context, conversationId)
    }

    private fun add(key: Key, line: Line): List<Line> = synchronized(this) {
        val queue = lines.getOrPut(key) { ArrayDeque() }
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
    private fun post(context: Context, key: Key, conversation: ConversationRow?, recent: List<Line>, alert: Boolean, photo: android.graphics.Bitmap? = null) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val service = key.service
        val conversationId = key.conversationId
        RelayNotifications.ensureChannels(context, service)

        val me = Person.Builder().setName(context.getString(R.string.relay_notify_you)).setKey("me").build()
        val isGroup = conversation?.isGroup == true
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(if (isGroup) conversation?.name else null)
            .setGroupConversation(isGroup)
        // A chat with one person: their picture with what they said.
        val icon = photo?.takeIf { !isGroup }?.let { androidx.core.graphics.drawable.IconCompat.createWithBitmap(it) }
        for (line in recent) {
            val person = if (line.fromMe) null else Person.Builder().setName(line.sender).apply { if (icon != null) setIcon(icon) }.build()
            style.addMessage(line.text, line.timestampMs, person)
        }

        val tag = tagFor(context, service, conversationId)
        val base = tag.hashCode() * 4
        val open = PendingIntent.getActivity(
            context, base, RelayIntents.thread(context, conversationId, service = service),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val reply = NotificationCompat.Action.Builder(
            R.drawable.ic_relay_reply,
            context.getString(R.string.relay_notify_reply),
            // RemoteInput fills in the reply, so this one must be mutable; the intent is explicit.
            PendingIntent.getBroadcast(
                context, base + 1, receiverIntent(context, ACTION_REPLY, service, conversationId, tag),
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
                context, base + 2, receiverIntent(context, ACTION_MARK_READ, service, conversationId, tag),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
        val dismissed = PendingIntent.getBroadcast(
            context, base + 3, receiverIntent(context, ACTION_DISMISSED, service, conversationId, tag),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, RelayNotifications.messagesChannel(service))
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
            // The group's photo, or the person's picture, as the notification's.
            .apply { if (photo != null) setLargeIcon(photo) }
            .build()
        try {
            manager.notify(tag, idFor(service), notification)
        } catch (e: RuntimeException) {
            // Without the permission on some builds.
        }
    }

    /**
     * The intent behind one of a notification's buttons, for [ReplyReceiver].
     *
     * Android tells pending intents apart by request code, action, component
     * and data, never by extras. The data names the service and the
     * conversation's [tag], so two conversations can never share a pending
     * intent, whatever their request codes hash to: with a shared one, a
     * reply typed into one notification would be sent to the other's
     * conversation. The receiver reads only the extras.
     */
    private fun receiverIntent(context: Context, action: String, service: ChatService, conversationId: String, tag: String): Intent =
        Intent(context, ReplyReceiver::class.java)
            .setAction(action)
            .setData(Uri.Builder().scheme(INTENT_SCHEME).authority(service.key).appendPath(tag).build())
            .putExtra(RelayIntents.EXTRA_CONVERSATION_ID, conversationId)
            .putExtra(RelayIntents.EXTRA_SERVICE, service.key)

    private fun mutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
}
