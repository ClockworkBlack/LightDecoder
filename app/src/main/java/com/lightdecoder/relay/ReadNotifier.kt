package com.lightdecoder.relay

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.chat.ChatService

/**
 * Whether LightDecoder is on screen: some screen of it is started. Kept by
 * the app's activity callbacks.
 */
object AppVisibility {
    @Volatile
    var started: Int = 0
        internal set

    val inFront: Boolean get() = started > 0
}

/**
 * The user's choices for "read" notifications: on for everyone by default,
 * only while LightDecoder is not on screen unless they ask for it then too,
 * and off for the people (conversations) they turn it off for.
 */
object ReadNotifyPrefs {
    private const val PREFS = "read_notify"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_WHILE_LOOKING = "while_looking"
    private const val KEY_OFF = "off"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit { putBoolean(KEY_ENABLED, on) }

    /** Also while LightDecoder is on screen (off by default). */
    fun whileLooking(context: Context): Boolean = prefs(context).getBoolean(KEY_WHILE_LOOKING, false)

    fun setWhileLooking(context: Context, on: Boolean) = prefs(context).edit { putBoolean(KEY_WHILE_LOOKING, on) }

    private fun key(service: ChatService, conversationId: String) = "${service.key}:$conversationId"

    private fun off(context: Context): Set<String> = prefs(context).getStringSet(KEY_OFF, emptySet()).orEmpty()

    /** Whether this person (conversation) is notified about; everyone is, unless turned off. */
    fun forConversation(context: Context, service: ChatService, conversationId: String): Boolean =
        key(service, conversationId) !in off(context)

    fun setForConversation(context: Context, service: ChatService, conversationId: String, on: Boolean) {
        val k = key(service, conversationId)
        val now = off(context).toMutableSet()
        if (on) now.remove(k) else now.add(k)
        prefs(context).edit { putStringSet(KEY_OFF, now) }
    }
}

/**
 * "Read" notifications: when someone reads a message the user sent (the
 * phone or WhatsApp says it was seen), a quiet notification says so, with
 * the start of the message. One per conversation, replaced by the next.
 * Its own channel ("Read receipts"), so it can be silenced on its own.
 * Never for a message read long after it was sent (history being imported,
 * say), and nothing about it is logged.
 */
object ReadNotifier {
    const val CHANNEL_READ = "read_receipts"
    private const val ID_READ = 0x5245_4c30
    private const val TAG_PREFIX = "read:"
    private const val REQUEST_BASE = 0x5000

    /** A message read later than this after it was sent is not news. */
    const val MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000

    /** Whether a status means the message was seen. */
    fun isRead(status: String) = status == "OUTGOING_DISPLAYED"

    /**
     * Whether, by the user's choices, a read message in this conversation is
     * notified now.
     */
    fun wanted(context: Context, service: ChatService, conversationId: String, nowMs: Long, sentMs: Long): Boolean {
        if (!ReadNotifyPrefs.enabled(context)) return false
        if (!ReadNotifyPrefs.forConversation(context, service, conversationId)) return false
        if (AppVisibility.inFront && !ReadNotifyPrefs.whileLooking(context)) return false
        if (sentMs > 0 && nowMs - sentMs > MAX_AGE_MS) return false
        return true
    }

    /**
     * Called with the user's messages that the phone now says were read and
     * were not before ([newlyRead], from [repo]). The latest of each
     * conversation is notified.
     */
    fun onRead(context: Context, repo: ArchiveRepository, newlyRead: List<MessageRow>) {
        if (newlyRead.isEmpty()) return
        val now = System.currentTimeMillis()
        for ((conversationId, rows) in newlyRead.filter { it.isOutgoing }.groupBy { it.conversationId }) {
            val latest = rows.maxByOrNull { it.timestampMs } ?: continue
            if (!wanted(context, repo.service, conversationId, now, latest.timestampMs)) continue
            post(context, repo, latest, rows.size)
        }
    }

    fun cancel(context: Context, service: ChatService, conversationId: String) {
        try {
            NotificationManagerCompat.from(context).cancel(TAG_PREFIX + MessageNotifier.tagFor(context, service, conversationId), ID_READ)
        } catch (e: RuntimeException) {
            // Nothing to cancel.
        }
    }

    fun ensureChannel(context: Context) {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        system.createNotificationChannel(
            NotificationChannel(
                CHANNEL_READ,
                context.getString(R.string.read_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.read_channel_description)
                setShowBadge(false)
            }
        )
    }

    /** What the notification says about the message: its start, or what kind of thing it was. */
    internal fun describe(context: Context, row: MessageRow): String {
        val text = row.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return when {
            text.isNotEmpty() -> context.getString(R.string.read_notify_text, text.take(PREVIEW_CHARS))
            row.media.any { it.mime.startsWith("image/") || it.mime.isEmpty() } -> context.getString(R.string.read_notify_picture)
            row.media.isNotEmpty() -> context.getString(R.string.read_notify_attachment)
            else -> context.getString(R.string.read_notify_message)
        }
    }

    @SuppressLint("MissingPermission") // checked with areNotificationsEnabled()
    private fun post(context: Context, repo: ArchiveRepository, row: MessageRow, count: Int) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(context)
        val service = repo.service
        val conversation = repo.conversation(row.conversationId)
        val name = conversation?.name?.takeIf { it.isNotBlank() } ?: context.getString(R.string.relay_notify_someone)
        val tag = TAG_PREFIX + MessageNotifier.tagFor(context, service, row.conversationId)
        val open = PendingIntent.getActivity(
            context, REQUEST_BASE + tag.hashCode() % 0x1000,
            RelayIntents.thread(context, row.conversationId, service = service),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = context.getString(R.string.read_notify_title, name)
        val body = if (count > 1) {
            context.resources.getQuantityString(R.plurals.read_notify_several, count, count, describe(context, row))
        } else {
            describe(context, row)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_READ)
            .setSmallIcon(R.drawable.ic_msg_delivered)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setOnlyAlertOnce(true)
            .build()
        try {
            manager.notify(tag, ID_READ, notification)
        } catch (e: RuntimeException) {
            // Without the permission on some builds.
        }
    }

    private const val PREVIEW_CHARS = 80
}
