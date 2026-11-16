package com.lightdecoder.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.lightdecoder.archive.ArchiveRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Answers the buttons on [MessageNotifier]'s notifications: Reply (queued
 * through the outbox like any other send), Mark as read, and the
 * notification being swiped away. Not exported: only this app's own
 * notifications can reach it.
 */
class ReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val conversationId = intent.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID)?.takeIf { it.isNotEmpty() } ?: return
        when (intent.action) {
            MessageNotifier.ACTION_REPLY -> reply(context, intent, conversationId)
            MessageNotifier.ACTION_MARK_READ -> markRead(context, conversationId)
            MessageNotifier.ACTION_DISMISSED -> MessageNotifier.forget(conversationId)
        }
    }

    private fun reply(context: Context, intent: Intent, conversationId: String) {
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(MessageNotifier.KEY_REPLY_TEXT)
            ?.toString()?.trim()
        if (text.isNullOrEmpty()) return
        RelayManager.get(context).sendText(conversationId, text)
        val pending = goAsync()
        background.launch {
            try {
                MessageNotifier.onReplySent(context, ArchiveRepository.get(context), conversationId, text)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Clears the notification at once; telling the phone may take longer
     * than a receiver may run, so that part carries on in the manager.
     */
    private fun markRead(context: Context, conversationId: String) {
        MessageNotifier.cancel(context, conversationId)
        val manager = RelayManager.get(context)
        background.launch {
            try {
                manager.markRead(conversationId)
            } catch (t: Throwable) {
                // markRead does not throw; this is only a guard.
            }
        }
    }

    private companion object {
        val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
