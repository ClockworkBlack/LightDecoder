package com.lightdecoder.relay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.lightdecoder.chat.ChatService
import com.lightdecoder.ui.chat.ConversationsActivity
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.ui.relay.RelaySetupActivity
import com.lightdecoder.ui.relay.RelayStatusActivity
import com.lightdecoder.ui.wa.WaSetupActivity
import com.lightdecoder.ui.wa.WaStatusActivity

/**
 * Intents into the messaging screens, for notifications and receivers.
 *
 * The extras are the contract between the runtimes (which build these
 * intents) and the screens (which read them); a notification tap with the
 * wrong key would open an empty thread, and one without its service would
 * look the conversation up in the other service's archive.
 */
object RelayIntents {
    const val EXTRA_CONVERSATION_ID = "com.lightdecoder.extra.CONVERSATION_ID"
    const val EXTRA_MESSAGE_ID = "com.lightdecoder.extra.MESSAGE_ID"

    /**
     * Which service a conversation (or the conversation list) belongs to: a
     * [ChatService.key]. Read it with [serviceOf]: an intent without it, as
     * every intent was before there was a second service, means Google
     * Messages.
     */
    const val EXTRA_SERVICE = "com.lightdecoder.extra.SERVICE"

    /** The service an intent built here is for; [ChatService.MESSAGES] when it does not say (or is null). */
    fun serviceOf(intent: Intent?): ChatService = ChatService.fromKey(intent?.getStringExtra(EXTRA_SERVICE))

    /** The conversation list, showing [service]. */
    fun conversations(context: Context, service: ChatService = ChatService.MESSAGES): Intent =
        activity(context, ConversationsActivity::class.java).putExtra(EXTRA_SERVICE, service.key)

    /**
     * One conversation of [service], optionally scrolled to [messageId].
     * SINGLE_TOP so a thread already on screen takes the new conversation in
     * onNewIntent instead of being torn down and rebuilt.
     *
     * No back stack is synthesized (a TaskStackBuilder would clear whatever
     * the user had open in the app on every tap). From a cold start the
     * thread is the task's root, and [ThreadActivity] itself puts the
     * conversation list behind it: Up always leads there, and so does Back
     * when nothing of the app is below.
     */
    fun thread(
        context: Context,
        conversationId: String,
        messageId: String? = null,
        service: ChatService = ChatService.MESSAGES,
    ): Intent =
        activity(context, ThreadActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_CONVERSATION_ID, conversationId)
            .putExtra(EXTRA_SERVICE, service.key)
            .apply { if (messageId != null) putExtra(EXTRA_MESSAGE_ID, messageId) }

    /** Google Messages: pairing. */
    fun setup(context: Context): Intent = activity(context, RelaySetupActivity::class.java)

    /** Google Messages: the link's status and settings. */
    fun status(context: Context): Intent = activity(context, RelayStatusActivity::class.java)

    /** WhatsApp: linking (the pairing code, or the QR code). */
    fun waSetup(context: Context): Intent = activity(context, WaSetupActivity::class.java)

    /** Gmail: adding the account (or, with one, its settings). */
    fun mailSetup(context: Context): Intent = activity(context, com.lightdecoder.ui.mail.MailSetupActivity::class.java)

    /** WhatsApp: the link's status and settings. */
    fun waStatus(context: Context): Intent = activity(context, WaStatusActivity::class.java)

    /**
     * "Let it run in the background": asks Android, in its own dialog, to
     * exempt the app from battery optimisation (Doze), so the link keeps its
     * connection while the phone is idle. The user decides; nothing is
     * granted by this. Needs the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
     * permission in the manifest. Start it from a screen; if no activity
     * handles it (some builds remove the dialog), use [batterySettings].
     * See [RelayNotifications.isIgnoringBatteryOptimizations].
     */
    fun batteryExemption(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))

    /** The system's list of apps and their battery optimisation, the fallback for [batteryExemption]. */
    fun batterySettings(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /**
     * [batteryExemption] if something on this device handles it, else
     * [batterySettings]: what a "Let it run in the background" button starts.
     */
    fun batteryExemptionOrSettings(context: Context): Intent {
        val ask = batteryExemption(context)
        val handled = try {
            ask.resolveActivity(context.packageManager) != null
        } catch (e: RuntimeException) {
            false
        }
        return if (handled) ask else batterySettings()
    }

    private fun activity(context: Context, screen: Class<out Activity>): Intent =
        Intent(context, screen)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}
