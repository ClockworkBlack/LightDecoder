package com.lightdecoder.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.ChatService
import com.lightdecoder.chat.Features
import com.lightdecoder.relay.RelayLog
import com.lightdecoder.relay.RelayManager
import com.lightdecoder.relay.RelayNotifications
import com.lightdecoder.relay.RelayPrefs
import com.lightdecoder.wa.WaLog
import com.lightdecoder.wa.WaPrefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A text the user can send to whoever helps them (by email, a message,
 * anything that takes text) when something does not work: the app and
 * phone versions, each link's state and settings, the send queue, the
 * latest outgoing messages' states, and the links' activity logs.
 *
 * Content-free on purpose: kinds, sizes, status names and error codes only.
 * No message text, names, phone numbers, email addresses, conversation ids
 * or keys. It is shown to the user in the share sheet before it goes anywhere.
 */
object ProblemReport {

    /** Builds the report. Reads the archives: call off the main thread. */
    fun build(context: Context): String = buildString {
        val app = context.applicationContext
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
        fun ts(ms: Long) = if (ms <= 0) "-" else time.format(Date(ms))
        val info = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        appendLine("LightDecoder problem report")
        appendLine("Made: ${ts(System.currentTimeMillis())}")
        @Suppress("DEPRECATION")
        appendLine("App: ${info?.versionName ?: "?"} (${if (Build.VERSION.SDK_INT >= 28) info?.longVersionCode else info?.versionCode})")
        appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Notifications allowed: ${NotificationManagerCompat.from(app).areNotificationsEnabled()}")
        appendLine("Battery optimisation off for the app: ${RelayPrefs.batteryExempt(app)}")
        appendLine()

        val messages = RelayManager.get(app)
        appendLine("== Google Messages link ==")
        appendLine("Paired: ${messages.isPaired()}; state: ${messages.state.value.phase} ${messages.state.value.code}".trimEnd())
        appendLine("Stay connected: ${RelayPrefs.liveEnabled(app)}; start automatically: ${RelayPrefs.autoStart(app)}; " +
            "notify: ${RelayPrefs.notifyMessages(app)}; its notification visible: ${RelayNotifications.statusVisible(app)}")
        section(this, ArchiveRepository.get(app, ChatService.MESSAGES), ::ts)
        appendLine()
        appendLine("-- Activity log (newest first) --")
        RelayLog.read(app).take(LOG_LINES).forEach { appendLine(it) }
        appendLine()

        appendLine("== WhatsApp link ==")
        appendLine("Stay connected: ${WaPrefs.liveEnabled(app)}; notify: ${WaPrefs.notifyMessages(app)}")
        val waLog = WaLog.read(app)
        if (waLog.isEmpty()) appendLine("(never used)") else waLog.take(LOG_LINES).forEach { appendLine(it) }
        appendLine()
        appendLine("Gmail tab: ${if (Features.GMAIL) "on" else "paused"}")
    }

    private fun section(out: StringBuilder, repo: ArchiveRepository, ts: (Long) -> String) {
        val health = runCatching { repo.health() }.getOrNull()
        out.appendLine("Last heard from Google: ${ts(health?.lastEventTs ?: 0)}; last error: ${health?.lastError.orEmpty().ifEmpty { "-" }}")
        out.appendLine()
        out.appendLine("-- Send queue --")
        val queue = runCatching { repo.allOutbox() }.getOrDefault(emptyList())
        if (queue.isEmpty()) out.appendLine("(empty)")
        for (row in queue) {
            val media = row.mediaLocalPath.isNotEmpty()
            val size = if (media) " ${File(row.mediaLocalPath).length() / 1024} KB ${row.mediaMime.ifEmpty { "?" }}" else ""
            out.appendLine(
                "${ts(row.createdAt)}  ${if (media) "attachment$size" else "text"}  ${row.status}  " +
                    "attempts ${row.attempts}  error ${row.lastError.ifEmpty { "-" }}",
            )
        }
        out.appendLine()
        out.appendLine("-- Latest outgoing messages --")
        val outgoing = runCatching { repo.recentOutgoing(RECENT) }.getOrDefault(emptyList())
        if (outgoing.isEmpty()) out.appendLine("(none)")
        for (row in outgoing) {
            val pictures = row.media.joinToString(",") { "${it.mime.ifEmpty { "?" }} ${it.size / 1024} KB" }.ifEmpty { "no attachment" }
            out.appendLine(
                "${ts(row.timestampMs)}  ${if (row.isLocal) "local copy" else "on the phone"}  $pictures  " +
                    "text ${if (row.text.isBlank()) "no" else "yes"}  status ${row.status.ifEmpty { "-" }}  " +
                    "local ${row.localState}  ${row.transport}  error ${row.errorText.ifEmpty { "-" }}",
            )
        }
    }

    /** Builds the report and opens the share sheet with it. */
    fun share(activity: Activity, report: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.report_subject))
            putExtra(Intent.EXTRA_TEXT, report)
        }
        try {
            activity.startActivity(Intent.createChooser(send, activity.getString(R.string.report_share)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.chat_no_app, Toast.LENGTH_LONG).show()
        }
    }

    private const val LOG_LINES = 200
    private const val RECENT = 20
}
