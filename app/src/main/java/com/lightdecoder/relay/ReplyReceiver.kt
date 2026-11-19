package com.lightdecoder.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.lightdecoder.chat.ChatBackends
import com.lightdecoder.chat.ChatService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Answers the buttons on [MessageNotifier]'s notifications: Reply (queued
 * through the service's outbox like any other send), Mark as read, and the
 * notification being swiped away. The intent says which service the
 * conversation belongs to ([RelayIntents.EXTRA_SERVICE]); everything goes to
 * that service's backend and archive. Not exported: only this app's own
 * notifications can reach it.
 */
class ReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val conversationId = intent.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID)?.takeIf { it.isNotEmpty() } ?: return
        val service = RelayIntents.serviceOf(intent)
        when (intent.action) {
            MessageNotifier.ACTION_REPLY -> reply(context, intent, service, conversationId)
            MessageNotifier.ACTION_MARK_READ -> markRead(context, service, conversationId)
            MessageNotifier.ACTION_DISMISSED -> MessageNotifier.forget(conversationId, service)
        }
    }

    private fun reply(context: Context, intent: Intent, service: ChatService, conversationId: String) {
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(MessageNotifier.KEY_REPLY_TEXT)
            ?.toString()?.trim()
        if (text.isNullOrEmpty()) return
        val backend = ChatBackends.get(context, service)
        // Blue Bubbles: to someone with an iMessage chat, a reply from the notification goes as iMessage, never green.
        val companion = if (service == ChatService.MESSAGES && com.lightdecoder.imessage.IMessagePrefs.enabled(context)) {
            runCatching {
                com.lightdecoder.ui.chat.BlueBubblesLinks.iMessageFor(context, backend.repo.conversation(conversationId), backend.repo.participants(conversationId))
            }.getOrNull()
        } else {
            null
        }
        try {
            if (companion != null) {
                val ref = com.lightdecoder.imessage.IMessageLog.chatRef(companion)
                try {
                    com.lightdecoder.imessage.IMessageManager.get(context).sendText(companion, text)
                    com.lightdecoder.imessage.IMessageLog.add(context, "Notification reply in a Messages conversation with $ref: sent as iMessage")
                } catch (e: RelayException) {
                    // No Reply button from OpenBubbles yet (or, through the iMessage library, a chat it cannot reach):
                    // it waits, failed, in the conversation, where a tap says what to do.
                    val why = if (com.lightdecoder.imessage.direct.DirectLink.get(context).active) "the chat cannot be reached through iMessage" else "no Reply button"
                    com.lightdecoder.imessage.IMessageLog.add(context, "Notification reply in a Messages conversation with $ref: $why, waits in the conversation (not sent as a text)")
                    MessageNotifier.cancel(context, conversationId, service)
                    return
                }
            } else {
                backend.sendText(conversationId, text)
            }
        } catch (e: RelayException) {
            // Could not even be queued (sends are queued, so this is not the
            // link being down): nothing was sent, and the notification must
            // not go on showing a reply in progress.
            MessageNotifier.cancel(context, conversationId, service)
            return
        }
        val pending = goAsync()
        background.launch {
            try {
                MessageNotifier.onReplySent(context, backend.repo, conversationId, text)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Clears the notification at once; telling the phone may take longer
     * than a receiver may run, so that part carries on in the manager.
     */
    private fun markRead(context: Context, service: ChatService, conversationId: String) {
        MessageNotifier.cancel(context, conversationId, service)
        val backend = ChatBackends.get(context, service)
        background.launch {
            try {
                backend.markRead(conversationId)
            } catch (t: Throwable) {
                // markRead does not throw; this is only a guard.
            }
        }
    }

    private companion object {
        val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
