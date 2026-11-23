package com.lightdecoder.imessage.facetime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import com.lightdecoder.R
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.direct.DirectPaths
import com.lightdecoder.imessage.direct.Handles
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.chat.Avatars
import com.lightdecoder.ui.imessage.FaceTimeCallActivity
import java.io.File

/**
 * FaceTime's notifications.
 *
 * - **Incoming** ([incoming], channel [CHANNEL_INCOMING]): the ringing itself. A call notification
 *   (Android 12's call style) with Decline and Answer, and "Audio only" for a video call; it rings
 *   with the phone's ringtone and vibrates through its channel, keeps on until it is answered,
 *   declined or taken down, and, with the screen off or locked, opens the call screen over the lock
 *   screen. It shows the caller, nothing of any chat. Answer opens the call screen (which asks for
 *   the microphone and joins from a visible screen); Decline is a broadcast.
 * - **In call** ([ongoing], channel [CHANNEL_ONGOING]): the foreground service's notice, with Hang up.
 * - **Missed** ([missed], channel [CHANNEL_MISSED]): posted by the main process, which knows the
 *   chat: a tap opens it (after the unlock: the chat's screen never shows over the lock screen).
 *
 * Every button is an explicit intent to a component of this app that is not exported, in a
 * PendingIntent nothing can change (immutable).
 */
object FaceTimeNotices {
    const val CHANNEL_INCOMING = "facetime_incoming"
    const val CHANNEL_ONGOING = "facetime_ongoing"
    const val CHANNEL_MISSED = "facetime_missed"

    const val ID_INCOMING = 7401
    const val ID_ONGOING = 7402
    const val ID_MISSED = 7403
    private const val TAG_INCOMING = "facetime_incoming"
    private const val TAG_MISSED = "facetime_missed:"

    /** A notice's intents are told apart by their data: the call (or chat) and what the button does. */
    private const val SCHEME = "lightdecoder-facetime"

    private val VIBRATION = longArrayOf(0, 1000, 1000)
    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    /** The ringing and in-call channels (":imessage" process, which sets up nothing else of the app's). */
    fun ensureCallChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_INCOMING) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_INCOMING, context.getString(R.string.facetime_channel_incoming), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.facetime_channel_incoming_description)
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                    )
                    enableVibration(true)
                    vibrationPattern = VIBRATION
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setShowBadge(false)
                },
            )
        }
        if (nm.getNotificationChannel(CHANNEL_ONGOING) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ONGOING, context.getString(R.string.facetime_channel_ongoing), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.facetime_channel_ongoing_description)
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
            )
        }
    }

    /** The missed calls' channel (main process). */
    fun ensureMissedChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_MISSED) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MISSED, context.getString(R.string.facetime_channel_missed), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.facetime_channel_missed_description)
            },
        )
    }

    /** What a call's person is called: their name, else their address without its scheme. */
    fun nameOf(call: Call): String = call.name.ifBlank { Handles.display(call.peer) }

    /** "FaceTime Video" or "FaceTime Audio". */
    fun kind(context: Context, video: Boolean): String = context.getString(if (video) R.string.facetime_video else R.string.facetime_audio)

    private fun person(context: Context, call: Call): Person {
        val name = nameOf(call)
        val builder = Person.Builder().setName(name).setImportant(true)
        // The person's picture, only from the app's own files (the main process names it).
        val photo = call.photo?.takeIf { DirectPaths.isAllowed(it, DirectPaths.forApp(context)) }?.let(::File)
        val side = (48 * context.resources.displayMetrics.density).toInt()
        val bitmap = photo?.let { Avatars.circlePhoto(it, side) } ?: Avatars.initialsBitmap(name, call.peer, "", side)
        return builder.setIcon(IconCompat.createWithBitmap(bitmap)).build()
    }

    private fun data(what: String, id: String): Uri = Uri.Builder().scheme(SCHEME).authority(what).appendPath(id).build()

    /** The ringing notice of [call]. */
    fun incoming(context: Context, call: Call): Notification {
        ensureCallChannels(context)
        val person = person(context, call)
        val decline = PendingIntent.getBroadcast(
            context, 1, FaceTimeActionReceiver.intent(context, FaceTimeActionReceiver.ACTION_DECLINE, call.guid).setData(data("decline", call.guid)), FLAGS,
        )
        // Answering opens the call screen: it asks for the microphone if it has to, and joins from a screen in front.
        val answer = PendingIntent.getActivity(
            context, 2, FaceTimeCallActivity.answerIntent(context, call.guid, withCamera = call.video).setData(data("answer", call.guid)), FLAGS,
        )
        val screen = PendingIntent.getActivity(context, 3, FaceTimeCallActivity.incomingIntent(context, call.guid).setData(data("show", call.guid)), FLAGS)
        val builder = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(if (call.video) R.drawable.ic_ft_video else R.drawable.ic_ft_audio)
            .setContentTitle(nameOf(call))
            .setContentText(context.getString(if (call.video) R.string.facetime_incoming_video else R.string.facetime_incoming_audio))
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, answer).setIsVideo(call.video))
            .addPerson(person)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            // On a locked phone that hides what notices say: that a call rings, not who calls (no name, no picture).
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_INCOMING)
                    .setSmallIcon(if (call.video) R.drawable.ic_ft_video else R.drawable.ic_ft_audio)
                    .setContentTitle(context.getString(R.string.facetime_incoming_public))
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setShowWhen(false)
                    .build(),
            )
            .setFullScreenIntent(screen, true)
            .setContentIntent(screen)
            .setShowWhen(false)
            // Should the call engine go before it takes it down.
            .setTimeoutAfter(CallRules.RING_IN_MS + 5_000)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), android.media.AudioManager.STREAM_RING)
            .setVibrate(VIBRATION)
        if (call.video) {
            val audioOnly = PendingIntent.getActivity(
                context, 4, FaceTimeCallActivity.answerIntent(context, call.guid, withCamera = false).setData(data("answer-audio", call.guid)), FLAGS,
            )
            builder.addAction(NotificationCompat.Action.Builder(R.drawable.ic_ft_audio, context.getString(R.string.facetime_audio_only), audioOnly).build())
        }
        return builder.build().apply { flags = flags or Notification.FLAG_INSISTENT }
    }

    /**
     * The in-call notice of [call] (the foreground service's): who, how it stands, the time once
     * joined, and Hang up. [ending]: the call was left while the library was still joining it; the
     * notice stays until that is over.
     */
    fun ongoing(context: Context, call: Call, ending: Boolean = false): Notification {
        ensureCallChannels(context)
        val person = person(context, call)
        val hangUp = PendingIntent.getBroadcast(
            context, 5, FaceTimeActionReceiver.intent(context, FaceTimeActionReceiver.ACTION_HANG_UP, call.guid).setData(data("hangup", call.guid)), FLAGS,
        )
        val open = PendingIntent.getActivity(context, 6, FaceTimeCallActivity.showIntent(context), FLAGS)
        val active = !ending && call.phase == Phase.ACTIVE && call.activeSinceMs > 0
        val text = when {
            ending -> context.getString(R.string.facetime_ending)
            call.phase == Phase.RINGING_OUT -> context.getString(R.string.facetime_calling)
            call.phase == Phase.CONNECTING -> context.getString(R.string.facetime_connecting)
            else -> kind(context, call.video)
        }
        return NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(if (call.video) R.drawable.ic_ft_video else R.drawable.ic_ft_audio)
            .setContentTitle(nameOf(call))
            .setContentText(text)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangUp).setIsVideo(call.video))
            .addPerson(person)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            // On a locked phone that hides what notices say: that there is a call, not with whom.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ONGOING)
                    .setSmallIcon(if (call.video) R.drawable.ic_ft_video else R.drawable.ic_ft_audio)
                    .setContentTitle(context.getString(R.string.facetime_ongoing_public))
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setShowWhen(false)
                    .build(),
            )
            .setContentIntent(open)
            .setUsesChronometer(active)
            .setShowWhen(active)
            .setWhen(if (active) call.activeSinceMs else System.currentTimeMillis())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * The notice of a missed call from [name] in the chat [conversationId] (the iMessage archive's),
     * for the main process to post ([postMissed]). A tap opens the chat. [quiet]: without sound.
     */
    fun missed(context: Context, name: String, video: Boolean, conversationId: String, atMs: Long, quiet: Boolean, photo: File? = null): Notification {
        ensureMissedChannel(context)
        val open = PendingIntent.getActivity(
            context, 7,
            RelayIntents.thread(context, conversationId, service = ChatService.IMESSAGE).setData(data("missed", missedTag(conversationId))),
            FLAGS,
        )
        val text = context.getString(if (video) R.string.facetime_missed_video else R.string.facetime_missed_audio)
        val side = (48 * context.resources.displayMetrics.density).toInt()
        val large = photo?.let { Avatars.circlePhoto(it, side) }
        return NotificationCompat.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(if (video) R.drawable.ic_ft_video else R.drawable.ic_ft_audio)
            .setContentTitle(name)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setWhen(atMs)
            .setShowWhen(true)
            .setSilent(quiet)
            .setOnlyAlertOnce(quiet)
            // On the lock screen: that a call was missed, not from whom.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_MISSED).setSmallIcon(if (video) R.drawable.ic_ft_video else R.drawable.ic_ft_audio)
                    .setContentTitle(context.getString(R.string.facetime_title)).setContentText(text).build(),
            )
            .apply { if (large != null) setLargeIcon(large) }
            .build()
    }

    /** A chat's missed-call notice replaces the one before it: one per chat. */
    private fun missedTag(conversationId: String): String = Integer.toHexString(conversationId.hashCode())

    /** Posts [missed] (main process). Nothing when notifications are not allowed. */
    @android.annotation.SuppressLint("MissingPermission") // checked with areNotificationsEnabled()
    fun postMissed(context: Context, name: String, video: Boolean, conversationId: String, atMs: Long, quiet: Boolean, photo: File? = null) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(TAG_MISSED + missedTag(conversationId), ID_MISSED, missed(context, name, video, conversationId, atMs, quiet, photo))
        } catch (e: RuntimeException) {
            // Without the permission on some builds.
        }
    }

    /** Takes a chat's missed-call notice down (the chat was opened). */
    fun cancelMissed(context: Context, conversationId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(TAG_MISSED + missedTag(conversationId), ID_MISSED) }
    }

    /** Posts and takes down the ringing notice: one at a time, as there is one ringing call at a time. */
    class Poster(context: Context) : CallNotices {
        private val app = context.applicationContext

        @android.annotation.SuppressLint("MissingPermission") // without the permission Android just does not show it
        override fun showIncoming(call: Call) {
            try {
                NotificationManagerCompat.from(app).notify(TAG_INCOMING, ID_INCOMING, incoming(app, call))
            } catch (e: RuntimeException) {
                FaceTimeLog.note("the incoming-call notice could not be shown (${e::class.java.simpleName})")
            }
        }

        override fun hideIncoming(guid: String) = cancelIncoming(app)
    }

    /** Takes the ringing notice down. */
    fun cancelIncoming(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(TAG_INCOMING, ID_INCOMING) }
    }

    /** An intent of this app's own for [cls] and [action]: explicit, so nothing else can receive it. */
    internal fun explicit(context: Context, cls: Class<*>, action: String): Intent = Intent(context, cls).setAction(action).setPackage(context.packageName)
}
