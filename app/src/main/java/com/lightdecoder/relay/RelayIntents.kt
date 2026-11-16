package com.lightdecoder.relay

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.lightdecoder.ui.chat.ConversationsActivity
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.ui.relay.RelaySetupActivity
import com.lightdecoder.ui.relay.RelayStatusActivity

/**
 * Intents into the messaging screens, for notifications and receivers.
 *
 * The extras are the contract between the runtime (which builds these
 * intents) and [ThreadActivity] (which reads them); a notification tap with
 * the wrong key would open an empty thread.
 */
object RelayIntents {
    const val EXTRA_CONVERSATION_ID = "com.lightdecoder.extra.CONVERSATION_ID"
    const val EXTRA_MESSAGE_ID = "com.lightdecoder.extra.MESSAGE_ID"

    fun conversations(context: Context): Intent = activity(context, ConversationsActivity::class.java)

    /**
     * One conversation, optionally scrolled to [messageId]. SINGLE_TOP so a
     * thread already on screen takes the new conversation in onNewIntent
     * instead of being torn down and rebuilt.
     */
    fun thread(context: Context, conversationId: String, messageId: String? = null): Intent =
        activity(context, ThreadActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_CONVERSATION_ID, conversationId)
            .apply { if (messageId != null) putExtra(EXTRA_MESSAGE_ID, messageId) }

    fun setup(context: Context): Intent = activity(context, RelaySetupActivity::class.java)

    fun status(context: Context): Intent = activity(context, RelayStatusActivity::class.java)

    private fun activity(context: Context, screen: Class<out Activity>): Intent =
        Intent(context, screen)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}
