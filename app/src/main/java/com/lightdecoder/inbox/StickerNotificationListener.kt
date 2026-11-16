package com.lightdecoder.inbox

import android.net.Uri
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.lightdecoder.capture.StickerCapture
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.Executors

/**
 * Catches picture attachments from incoming message notifications, so a
 * sticker that the messaging app cannot display shows up decoded in
 * LightDecoder without being shared by hand.
 *
 * How it works, all through public Android APIs:
 *  - Android only starts this service after the user turns on notification
 *    access for LightDecoder in system settings, and stops it when they turn
 *    it off.
 *  - A messaging app describes a chat notification with MessagingStyle. A
 *    picture message carries a content:// URI, and Android grants notification
 *    listeners read access to it for as long as the notification is showing.
 *  - That picture is read, decoded and stored in the app's private storage.
 *
 * Nothing is sent anywhere, and notifications from other apps are ignored.
 */
class StickerNotificationListener : NotificationListenerService() {

    private val worker = Executors.newSingleThreadExecutor()

    private class Attachment(
        val id: String,
        val appPackage: String,
        val conversation: String,
        val sender: String,
        val timestampMs: Long,
        val mime: String,
        val uri: Uri,
    )

    override fun onListenerConnected() {
        InboxLog.add(this, "Listener connected")
        // Pick up anything that arrived while the service was not running.
        val active = try { activeNotifications } catch (e: Exception) { null }
        active?.forEach { inspect(it) }
    }

    override fun onListenerDisconnected() {
        InboxLog.add(this, "Listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) inspect(sbn)
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    /** Runs on the main thread, so it only collects what to read and hands off. */
    private fun inspect(sbn: StatusBarNotification) {
        if (sbn.packageName !in InboxPrefs.MESSAGING_PACKAGES) return
        if (InboxPrefs.isPaused(this)) return

        val attachments = try {
            collectAttachments(sbn)
        } catch (e: Exception) {
            InboxLog.add(this, "Could not read a notification: ${e.javaClass.simpleName}")
            return
        }
        if (attachments.isEmpty()) return
        val context = applicationContext
        worker.execute {
            for (attachment in attachments) {
                try {
                    capture(attachment)
                } catch (t: Throwable) {
                    // Includes OutOfMemoryError on a huge picture: skip it and carry on.
                    InboxStore.get(context).markSeen(attachment.id)
                    InboxLog.add(context, "Attachment failed: ${t.javaClass.simpleName}")
                }
            }
        }
    }

    private fun collectAttachments(sbn: StatusBarNotification): List<Attachment> {
        val notification = sbn.notification ?: return emptyList()
        val style = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(notification) ?: return emptyList()
        val conversation = style.conversationTitle?.toString()
            ?: notification.extras?.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()
            ?: ""
        val result = ArrayList<Attachment>()
        for (message in style.messages) {
            val uri = message.dataUri ?: continue
            val sender = message.person?.name?.toString() ?: conversation
            result.add(
                Attachment(
                    id = InboxStore.idFor(sbn.packageName, conversation, sender, message.timestamp, uri.toString()),
                    appPackage = sbn.packageName,
                    conversation = conversation,
                    sender = sender,
                    timestampMs = message.timestamp,
                    mime = message.dataMimeType ?: "",
                    uri = uri,
                )
            )
        }
        return result
    }

    /** Runs on the worker thread. Reads the picture; [StickerCapture] does the rest. */
    private fun capture(attachment: Attachment) {
        val store = InboxStore.get(this)
        if (store.contains(attachment.id)) return
        val label = attachment.mime.ifEmpty { "no type" }

        val bytes = try {
            contentResolver.openInputStream(attachment.uri)?.use { readCapped(it) }
        } catch (e: SecurityException) {
            store.markSeen(attachment.id)
            InboxLog.add(this, "Attachment ($label): Android did not allow reading it")
            return
        } catch (e: Exception) {
            store.markSeen(attachment.id)
            InboxLog.add(this, "Attachment ($label): could not be opened (${e.javaClass.simpleName})")
            return
        }
        if (bytes == null || bytes.isEmpty()) {
            store.markSeen(attachment.id)
            InboxLog.add(this, "Attachment ($label): empty or larger than ${MAX_BYTES / (1024 * 1024)} MB")
            return
        }

        StickerCapture.capture(
            this,
            bytes,
            StickerCapture.Meta(
                id = attachment.id,
                sender = attachment.sender,
                title = attachment.conversation,
                appPackage = attachment.appPackage,
                timestampMs = attachment.timestampMs,
                mime = attachment.mime,
                source = InboxItem.SOURCE_NOTIFICATION,
            ),
        )
    }

    private fun readCapped(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_BYTES) return null
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_BYTES = StickerCapture.MAX_BYTES
    }
}
