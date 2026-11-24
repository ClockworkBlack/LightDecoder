package com.lightdecoder.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lightdecoder.R
import com.lightdecoder.chat.ChatService
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.ReplyReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "React…" on an iMessage's notification, for phones that show no reply chips
 * ([MessageNotifier.reactButton], off unless a device needs it): Apple's six tapbacks to pick from, put
 * on the message the notification named, through the same checks and the same path as a chip's
 * ([ReplyReceiver.tapbackRow], then [com.lightdecoder.imessage.direct.DirectLink.react]).
 *
 * Not exported, opened only by an immutable, explicit PendingIntent with the phone unlocked first
 * (never over the lock screen); it carries ids only, never a message's text.
 */
class TapbackActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val conversationId = intent.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID).orEmpty()
        val messageId = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()
        if (conversationId.isEmpty() || messageId.isEmpty() || savedInstanceState != null) {
            finish()
            return
        }
        val choices = MessageNotifier.TAPBACK_CHOICES
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tapback_title)
            .setItems(choices.toTypedArray()) { _, which -> react(conversationId, messageId, choices[which]) }
            .setOnCancelListener { finish() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .show()
    }

    private fun react(conversationId: String, messageId: String, emoji: String) {
        val app = applicationContext
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val row = ReplyReceiver.tapbackRow(app, ChatService.IMESSAGE, conversationId, messageId, emoji) ?: return@withContext false
                try {
                    val link = com.lightdecoder.imessage.direct.DirectLink.get(app)
                    link.react(row.id, emoji, remove = false)
                    MessageNotifier.onReplySent(app, com.lightdecoder.archive.ArchiveRepository.get(app, ChatService.IMESSAGE), conversationId, link.tapbackLine(row, emoji))
                    true
                } catch (e: Exception) {
                    false
                }
            }
            if (!ok) {
                MessageNotifier.cancel(app, conversationId, ChatService.IMESSAGE)
                android.widget.Toast.makeText(app, R.string.tapback_not_sent, android.widget.Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    companion object {
        private const val EXTRA_MESSAGE = "com.lightdecoder.tapback.MESSAGE"

        /** What "React…" opens for [messageId] of [conversationId] ([tag]: the notification's, which makes its data its own). */
        fun intent(context: Context, conversationId: String, messageId: String, tag: String): Intent =
            Intent(context, TapbackActivity::class.java)
                .setData(Uri.Builder().scheme("lightdecoder-tapback").authority(ChatService.IMESSAGE.key).appendPath(tag).build())
                .putExtra(RelayIntents.EXTRA_CONVERSATION_ID, conversationId)
                .putExtra(EXTRA_MESSAGE, messageId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
}
