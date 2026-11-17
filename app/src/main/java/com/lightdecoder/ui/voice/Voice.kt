package com.lightdecoder.ui.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.core.content.edit
import com.lightdecoder.R
import com.lightdecoder.archive.MessageRow

/** What "Read aloud" says for a message. */
object ReadAloudText {

    /**
     * "<sender> said: <text>" for an incoming message, the text alone for the
     * user's own; "a picture" or "an attachment" stands in for the text of a
     * message that only carries media. Null when there is nothing to say.
     */
    fun sentence(context: Context, row: MessageRow): String? {
        val body = row.text.trim().ifEmpty {
            when {
                row.media.isEmpty() -> return null
                row.media.any { it.mime.isEmpty() || it.mime.startsWith("image/") } ->
                    context.getString(R.string.chat_read_aloud_picture)
                else -> context.getString(R.string.chat_read_aloud_attachment)
            }
        }
        if (isOwn(row)) return body
        val sender = row.senderName.trim().ifEmpty { context.getString(R.string.chat_unknown_sender) }
        return context.getString(R.string.chat_read_aloud_said, sender, body)
    }

    /** A message the other side sent (not the user's own, not a system line). */
    fun isIncoming(row: MessageRow) = !row.isOutgoing && row.direction == "in"

    private fun isOwn(row: MessageRow) = row.isOutgoing || row.isLocal
}

/** Speech typed into the composer through the system's speech recogniser. */
object Dictation {

    /** The recogniser request: free-form speech, offline where the recogniser can. */
    fun intent(context: Context): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.chat_voice_prompt))

    /** Whether any app can take [intent] (needs the manifest's <queries> entry on Android 11+). */
    fun isAvailable(packageManager: PackageManager): Boolean = runCatching {
        packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
    }.getOrDefault(false)

    /** The best guess in a recogniser result, or null. */
    fun resultText(data: Intent?): String? =
        data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * [spoken] as it goes in place of [start]..[end] of [current]: with a space
     * before it when it would otherwise run into the word before, and after it
     * when it would run into the word after.
     */
    fun piece(current: CharSequence, start: Int, end: Int, spoken: String): String {
        val before = if (start > 0 && !current[start - 1].isWhitespace()) " " else ""
        val after = if (end < current.length && !current[end].isWhitespace()) " " else ""
        return before + spoken + after
    }
}

/** The app's voice settings. */
object VoicePrefs {
    private const val FILE = "voice"
    private const val KEY_READ_NEW = "read_new_aloud"

    fun readNewAloud(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_READ_NEW, false)

    fun setReadNewAloud(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putBoolean(KEY_READ_NEW, on) }
    }
}
