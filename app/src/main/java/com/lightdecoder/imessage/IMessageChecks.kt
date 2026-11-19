package com.lightdecoder.imessage

import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.RelayNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Check iMessage under way, kept by the app rather than by its dialog:
 * closing the dialog (or leaving the conversation) does not stop it. It
 * watches for the person's reply for up to [GIVE_UP_MS], logs each step,
 * and when the verdict comes while its dialog is not open, says it in a
 * notification that opens the conversation. One check at a time; starting
 * another replaces it. Lives as long as the app's process.
 */
object IMessageChecks {

    data class Run(
        /** The Google Messages conversation it was started from. */
        val conversationId: String,
        val name: String,
        val text: String,
        /** Sent silently through OpenBubbles' Reply button (else the user tapped send in OpenBubbles). */
        val silent: Boolean,
        val sentAtMs: Long,
        val progress: IMessageCheck.Progress,
        val waitedMs: Long = 0,
        /** No reply within [GIVE_UP_MS], or stopped by the user. */
        val ended: Boolean = false,
    ) {
        val running: Boolean get() = !progress.done && !ended
    }

    private val _current = MutableStateFlow<Run?>(null)
    val current: StateFlow<Run?> = _current

    /** Whether the check's dialog is on screen (then the verdict needs no notification). */
    @Volatile
    var dialogShowing = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    /**
     * Watches a check of [text] sent at [sentAtMs] to [companion] (or, when there was no
     * iMessage chat yet, the chat OpenBubbles starts with [address]).
     */
    fun watch(
        context: Context,
        conversationId: String,
        name: String,
        companion: String?,
        address: String?,
        text: String,
        silent: Boolean,
        sentAtMs: Long,
        iMessages: IMessageManager,
        texts: ArchiveRepository,
    ) {
        val app = context.applicationContext
        stop(app, quietly = true)
        val ref = companion?.let(IMessageLog::chatRef) ?: "(new chat)"
        fun log(line: String) = IMessageLog.add(app, "Check iMessage $ref: $line")
        var last = IMessageCheck.Progress(false, false, false, IMessageCheck.Reply.NONE)
        _current.value = Run(conversationId, name, text, silent, sentAtMs, last)
        var slowNoted = false
        job = scope.launch {
            while (true) {
                val progress = withContext(Dispatchers.IO) {
                    // A chat OpenBubbles has just started is found by the person's number once it shows up.
                    val chat = companion ?: address?.let(iMessages::conversationWith)
                    IMessageCheck.progress(
                        text, sentAtMs, chat?.let { iMessages.repo.messages(it, limit = 50) }.orEmpty(),
                        texts.messages(conversationId, limit = 50),
                    )
                }
                val waited = System.currentTimeMillis() - sentAtMs
                val secs = (waited + 500) / 1000
                if (progress.handedOver && !last.handedOver) log("step 1 handed to OpenBubbles after ${secs}s")
                if (progress.inOpenBubbles && !last.inOpenBubbles) log("step 2 shown back by OpenBubbles (sent to Apple) after ${secs}s")
                if (progress.sentAsText && !last.sentAsText) log("WRONG: the check also went out as a text through Google Messages")
                if (progress.reply != last.reply) log(
                    "step 3 reply after ${secs}s by " + when (progress.reply) {
                        IMessageCheck.Reply.IMESSAGE -> "iMessage: BLUE on their iPhone"
                        IMessageCheck.Reply.TEXT -> "text: GREEN on their iPhone"
                        IMessageCheck.Reply.NONE -> "nothing"
                    } + if (dialogShowing) "" else " (seen in the background)",
                )
                if (!slowNoted && waited > SLOW_MS && !progress.done) {
                    slowNoted = true
                    log("after ${secs}s: handed over ${progress.handedOver}, shown back ${progress.inOpenBubbles}, no reply yet")
                }
                last = progress
                val giveUp = !progress.done && waited > GIVE_UP_MS
                _current.value = Run(conversationId, name, text, silent, sentAtMs, progress, waited, ended = giveUp)
                if (progress.done) {
                    if (!dialogShowing) notifyVerdict(app, conversationId, name, progress.reply)
                    return@launch
                }
                if (giveUp) {
                    log("gave up after ${secs / 60} min with no reply (handed over ${progress.handedOver}, shown back ${progress.inOpenBubbles})")
                    return@launch
                }
                // Quick at first, when a reply is most likely; easy on the battery later.
                delay(if (waited < FAST_FOR_MS) POLL_MS else SLOW_POLL_MS)
            }
        }
    }

    /** The dialog was closed while the check runs: it carries on. */
    fun noteHidden(context: Context) {
        val run = _current.value ?: return
        if (run.running) {
            IMessageLog.add(context, "Check iMessage: dialog closed, still checking in the background")
        }
    }

    /** Ends the check under way (the user tapped Stop check, or another check replaces it). */
    fun stop(context: Context, quietly: Boolean = false) {
        val run = _current.value
        job?.cancel()
        job = null
        if (run != null && run.running) {
            if (!quietly) IMessageLog.add(context, "Check iMessage: stopped by the user before a reply")
            _current.value = run.copy(ended = true)
        }
    }

    private fun notifyVerdict(context: Context, conversationId: String, name: String, reply: IMessageCheck.Reply) {
        val text = context.getString(
            if (reply == IMessageCheck.Reply.IMESSAGE) R.string.imessage_check_blue else R.string.imessage_check_green, name,
        )
        val open = PendingIntent.getActivity(
            context, ID_VERDICT, RelayIntents.thread(context, conversationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, RelayNotifications.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(context.getString(R.string.imessage_check_done_title, name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val allowed = android.os.Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!allowed) {
            IMessageLog.add(context, "Check iMessage: verdict not shown as a notification (notifications not allowed)")
            return
        }
        try {
            RelayNotifications.ensureChannels(context)
            NotificationManagerCompat.from(context).notify(ID_VERDICT, notification)
        } catch (e: SecurityException) {
            // Taken away meanwhile: the verdict is in the log and in the check when reopened.
        } catch (e: RuntimeException) {
            // No permission to notify: the verdict is in the log and in the check when reopened.
        }
    }

    internal fun resetForTesting() {
        job?.cancel()
        job = null
        _current.value = null
        dialogShowing = false
    }

    private const val ID_VERDICT = 7_301
    private const val POLL_MS = 1_000L
    private const val SLOW_POLL_MS = 5_000L
    private const val FAST_FOR_MS = 10 * 60 * 1000L
    const val SLOW_MS = 60_000L
    const val GIVE_UP_MS = 2 * 60 * 60 * 1000L
}
