package com.lightdecoder.inbox

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lightdecoder.R
import com.lightdecoder.heif.Media
import com.lightdecoder.ui.ViewerActivity

/**
 * Shows a freshly decoded sticker as a notification, so it can be seen the
 * moment it arrives, next to the message notification that could not show it.
 */
object StickerAlerts {
    private const val CHANNEL = "decoded_stickers"
    private const val PICTURE_MAX_SIDE = 720

    @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled()
    fun show(context: Context, item: InboxItem, preview: Bitmap) {
        if (!InboxPrefs.alertsEnabled(context)) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(context)

        // The caller recycles its bitmap, so the notification gets its own copy.
        val scaled = Media.scaledToFit(preview, PICTURE_MAX_SIDE)
        val picture = if (scaled === preview) preview.copy(Bitmap.Config.ARGB_8888, false) else scaled

        val open = PendingIntent.getActivity(
            context,
            item.id.hashCode(),
            ViewerActivity.intentForInboxItem(context, item.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (item.sender.isNotBlank()) {
            context.getString(R.string.alert_title_from, item.sender)
        } else {
            context.getString(R.string.alert_title)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sticker)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.alert_text))
            .setLargeIcon(picture)
            .setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(picture)
                    .bigLargeIcon(null as Bitmap?)
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .setWhen(item.timestampMs)
            .setShowWhen(true)
            .build()
        manager.notify(item.id.hashCode(), notification)
    }

    private fun ensureChannel(context: Context) {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        if (system.getNotificationChannel(CHANNEL) != null) return
        system.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.alert_channel),
                NotificationManager.IMPORTANCE_LOW, // quiet: the message itself already made a sound
            ).apply { description = context.getString(R.string.alert_channel_description) }
        )
    }
}
