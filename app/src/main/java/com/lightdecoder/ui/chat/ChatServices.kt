package com.lightdecoder.ui.chat

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.lightdecoder.R
import com.lightdecoder.chat.ChatBackend
import com.lightdecoder.chat.ChatBackends
import com.lightdecoder.chat.ChatService
import com.lightdecoder.relay.RelayException
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.RelayManager
import com.lightdecoder.relay.RelayState
import com.lightdecoder.relay.RelayState.Phase
import com.lightdecoder.ui.relay.NotificationPermission
import com.lightdecoder.ui.relay.RelayErrors
import com.lightdecoder.ui.relay.RelayUi
import com.lightdecoder.wa.WaErrors
import com.lightdecoder.wa.WaManager
import java.util.EnumMap

/*
 * The conversation screens show either service. What a service can do comes
 * from its ChatBackend (capabilities); what follows is the rest that differs
 * between them: wording, where linking and the link's settings are, and how
 * its error codes read.
 */

/**
 * Where the screens get each service's backend: the app's own
 * ([ChatBackends]), unless a test put another in its place.
 */
internal object ScreenBackends {
    /** Tests only: a backend to use for a service instead of the app's own (null for "the app's own"). */
    @VisibleForTesting
    @Volatile
    internal var forTesting: ((ChatService) -> ChatBackend?)? = null

    /** Cheap, and safe on the main thread, as [ChatBackends.get] is. */
    fun get(context: Context, service: ChatService): ChatBackend =
        forTesting?.invoke(service) ?: ChatBackends.get(context, service)

    /** Every service's backend, in the order of [ChatService]. */
    fun all(context: Context): List<ChatBackend> = com.lightdecoder.chat.Features.shownServices().map { get(context, it) }
}

/** Which service tab the conversation list showed last, so it opens there again. */
internal object ChatUiPrefs {
    private const val FILE = "chat_ui"
    private const val KEY_SERVICE = "service"

    /** Null until a tab has been shown once. */
    fun lastService(context: Context): ChatService? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_SERVICE, null)
            ?.let { key -> com.lightdecoder.chat.Features.shownServices().firstOrNull { it.key == key } }

    fun setLastService(context: Context, service: ChatService) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(KEY_SERVICE, service.key) }

    /**
     * The tab to open on when nothing says which: the last one shown, else a
     * linked service (Google Messages first), else Google Messages.
     */
    fun defaultService(context: Context): ChatService =
        lastService(context)
            ?: ScreenBackends.all(context).firstOrNull { it.isPaired() }?.service
            ?: ChatService.MESSAGES
}

/** Wording and destinations that depend on the service a screen shows. */
internal object ServiceUi {

    /**
     * Whether [service] is iMessage through the bundled library ("direct mode"): its texts then
     * never send the user to the OpenBubbles app. Pass it as the wording functions' `direct`.
     */
    fun direct(context: Context, service: ChatService): Boolean =
        service == ChatService.IMESSAGE && com.lightdecoder.imessage.direct.DirectLink.get(context).active

    /** The service's name on its tab of the conversation list. */
    @StringRes
    fun tabLabel(service: ChatService): Int = when (service) {
        ChatService.MESSAGES -> R.string.chat_service_messages
        ChatService.WHATSAPP -> R.string.chat_service_whatsapp
        ChatService.GMAIL -> R.string.chat_service_gmail
        ChatService.IMESSAGE -> R.string.chat_service_imessage
    }

    /**
     * Linking [service], started from one of the app's own screens. The
     * intents are [RelayIntents]' (so the screens stay the ones notifications
     * open), without the task flags a notification needs.
     */
    fun setup(context: Context, service: ChatService): Intent = when (service) {
        ChatService.MESSAGES -> RelayIntents.setup(context)
        ChatService.WHATSAPP -> RelayIntents.waSetup(context)
        ChatService.GMAIL -> RelayIntents.mailSetup(context)
        // No setup screen of its own: "Blue Bubbles" is ticked in the Messages tab's menu.
        ChatService.IMESSAGE -> RelayIntents.conversations(context, ChatService.MESSAGES)
    }.setFlags(0)

    /**
     * The status and settings of [service]'s link, started from one of the app's own screens.
     * iMessage's is the iMessage screen when it goes through the library, else the Messages tab
     * (whose menu has Blue Bubbles).
     */
    fun status(context: Context, service: ChatService): Intent = when (service) {
        ChatService.MESSAGES -> RelayIntents.status(context)
        ChatService.WHATSAPP -> RelayIntents.waStatus(context)
        ChatService.GMAIL -> RelayIntents.mailSetup(context)
        ChatService.IMESSAGE ->
            if (direct(context, service)) Intent(context, com.lightdecoder.ui.imessage.DirectSetupActivity::class.java)
            else RelayIntents.conversations(context, ChatService.MESSAGES)
    }.setFlags(0)

    /**
     * "Stay connected" for [backend]'s link. Cheap and safe on the main
     * thread; call it behind that link's [NotificationPermission] gate.
     */
    fun startLive(backend: ChatBackend) {
        when (backend) {
            is RelayManager -> backend.startLive()
            is WaManager -> backend.startLive()
            is com.lightdecoder.mail.MailManager -> backend.syncSoon()
        }
    }

    /** What the banner says under the title for a state of [service]'s link. */
    @StringRes
    fun phaseBody(service: ChatService, phase: Phase, fix: RelayUi.Fix): Int {
        if (service == ChatService.GMAIL && fix != RelayUi.Fix.NOTIFICATIONS) {
            when (phase) {
                Phase.UNPAIRED -> return R.string.mail_body_unpaired
                Phase.CONNECTED, Phase.ACTIVE -> return R.string.mail_body_connected
                Phase.CONNECTING -> return R.string.mail_body_checking
                Phase.LOGGED_OUT -> return R.string.mail_body_auth_failed
                Phase.DISCONNECTED, Phase.ERROR -> return R.string.mail_body_offline
                else -> Unit
            }
        }
        if (service == ChatService.WHATSAPP && fix != RelayUi.Fix.NOTIFICATIONS) {
            when (phase) {
                Phase.UNPAIRED -> return R.string.chat_wa_body_unpaired
                Phase.PAIRING -> return R.string.chat_wa_body_pairing
                Phase.CONNECTING -> return R.string.chat_wa_body_connecting
                Phase.LOGGED_OUT -> return R.string.chat_wa_body_logged_out
                else -> Unit
            }
        }
        return RelayUi.body(phase, fix)
    }

    /** The card an unlinked service shows in place of its conversations. */
    @StringRes
    fun linkCardTitle(service: ChatService): Int = when (service) {
        ChatService.MESSAGES -> R.string.link_setup_title
        ChatService.WHATSAPP -> R.string.chat_wa_link_title
        ChatService.GMAIL -> R.string.mail_link_title
        ChatService.IMESSAGE -> R.string.imessage_link_title
    }

    @StringRes
    fun linkCardBody(service: ChatService): Int = when (service) {
        ChatService.MESSAGES -> R.string.link_card_not_set_up
        ChatService.WHATSAPP -> R.string.chat_wa_link_body
        ChatService.GMAIL -> R.string.mail_link_body
        ChatService.IMESSAGE -> R.string.imessage_link_body
    }

    @StringRes
    fun emptyInbox(service: ChatService, direct: Boolean): Int = when (service) {
        ChatService.MESSAGES -> R.string.chat_empty_inbox
        ChatService.WHATSAPP -> R.string.chat_wa_empty_inbox
        ChatService.GMAIL -> R.string.mail_empty_inbox
        ChatService.IMESSAGE -> if (direct) R.string.imessage_empty_inbox_direct else R.string.imessage_empty_inbox
    }

    @StringRes
    fun composeHint(service: ChatService): Int = when (service) {
        ChatService.MESSAGES -> R.string.chat_compose_hint
        ChatService.WHATSAPP -> R.string.chat_wa_compose_hint
        ChatService.GMAIL -> R.string.mail_compose_hint
        ChatService.IMESSAGE -> R.string.imessage_compose_hint
    }

    /** Said when a message the service itself failed to send is tapped: only the phone's app can retry it. */
    @StringRes
    fun failedOnPhone(service: ChatService, direct: Boolean): Int = when (service) {
        ChatService.MESSAGES -> R.string.chat_failed_phone_detail
        ChatService.WHATSAPP -> R.string.chat_wa_failed_phone_detail
        ChatService.GMAIL -> R.string.mail_failed_detail
        ChatService.IMESSAGE -> if (direct) R.string.imessage_failed_detail_direct else R.string.imessage_failed_detail
    }

    /** The "Send again?" question for a send that was never confirmed. */
    @StringRes
    fun resendBody(service: ChatService, direct: Boolean): Int = when (service) {
        ChatService.MESSAGES -> R.string.chat_resend_body
        ChatService.WHATSAPP -> R.string.chat_wa_resend_body
        ChatService.GMAIL -> R.string.mail_resend_body
        ChatService.IMESSAGE -> if (direct) R.string.imessage_resend_body_direct else R.string.imessage_resend_body
    }

    @StringRes
    fun mediaNeedsConnection(service: ChatService, direct: Boolean): Int = when (service) {
        ChatService.MESSAGES -> R.string.chat_media_needs_connection
        ChatService.WHATSAPP -> R.string.chat_wa_media_needs_connection
        ChatService.GMAIL -> R.string.mail_media_needs_connection
        ChatService.IMESSAGE -> if (direct) R.string.imessage_media_needs_connection_direct else R.string.imessage_media_needs_connection
    }

    @StringRes
    fun contactsNoMatch(service: ChatService, direct: Boolean): Int = when (service) {
        ChatService.MESSAGES -> R.string.chat_contacts_no_match
        ChatService.WHATSAPP -> R.string.chat_wa_contacts_no_match
        ChatService.GMAIL -> R.string.mail_contacts_no_match
        ChatService.IMESSAGE -> if (direct) R.string.imessage_contacts_no_match_direct else R.string.imessage_contacts_no_match
    }

    @StringRes
    fun deleteConversationBody(service: ChatService, direct: Boolean, everywhere: Boolean = false): Int = when (service) {
        ChatService.MESSAGES -> R.string.chat_delete_conversation_body
        ChatService.WHATSAPP -> R.string.chat_wa_delete_conversation_body
        ChatService.GMAIL -> R.string.mail_delete_conversation_body
        // Through the iMessage library it goes to Recently Deleted ("Delete on All Devices": on the user's other devices too).
        ChatService.IMESSAGE -> when {
            !direct -> R.string.imessage_delete_conversation_body
            everywhere -> R.string.imessage_delete_conversation_body_everywhere
            else -> R.string.imessage_delete_conversation_body_recently
        }
    }

    /**
     * What a Messages conversation's delete dialog adds when its iMessage chats ([companions]: any)
     * go with it through the iMessage library ([direct]): they move to Recently Deleted (with
     * [everywhere], "Delete on All Devices", they are deleted from the user's other devices too).
     * Null when nothing is added.
     */
    @StringRes
    fun deleteCompanionsNote(companions: Boolean, direct: Boolean, everywhere: Boolean): Int? = when {
        !companions || !direct -> null
        everywhere -> R.string.imessage_delete_companion_everywhere
        else -> R.string.imessage_delete_companion_recently
    }

    /** The body of "Delete All" in Recently Deleted: with [everywhere], from the user's other devices too. */
    @StringRes
    fun deleteAllBody(everywhere: Boolean): Int =
        if (everywhere) R.string.imessage_delete_all_body_everywhere else R.string.imessage_delete_all_body
}

/**
 * Plain-language text for a failure in either service. Google Messages'
 * codes read as [RelayErrors] has them; WhatsApp's own codes, and the shared
 * ones where the wording names Google or the phone, have WhatsApp's.
 */
internal object ChatErrors {

    /** The bridge code of any failure ("internal" for anything that is not a link error). */
    fun codeOf(t: Throwable): String = RelayErrors.codeOf(t)

    /** The iMessage library's failures already say why in words ([com.lightdecoder.imessage.direct.DirectEngine.ImessageError]): shown as they are. */
    fun message(context: Context, service: ChatService, t: Throwable): String =
        if (t is com.lightdecoder.imessage.direct.DirectEngine.ImessageError) {
            com.lightdecoder.imessage.direct.DirectEngine.readable(t)
        } else {
            message(context, service, codeOf(t))
        }

    fun message(context: Context, service: ChatService, code: String): String =
        context.getString(messageRes(service, code, ServiceUi.direct(context, service)))

    /** [direct]: iMessage through the bundled library ([ServiceUi.direct]), worded without OpenBubbles. */
    @StringRes
    fun messageRes(service: ChatService, code: String, direct: Boolean): Int = when (service) {
        ChatService.MESSAGES -> RelayErrors.messageRes(code)
        ChatService.WHATSAPP -> whatsAppRes(code)
        ChatService.GMAIL -> mailRes(code)
        ChatService.IMESSAGE -> iMessageRes(code, direct)
    }

    @StringRes
    private fun iMessageRes(code: String, direct: Boolean): Int = when (code) {
        RelayException.NOT_PAIRED -> R.string.imessage_error_not_enabled
        com.lightdecoder.imessage.IMessageErrors.NOT_INSTALLED -> R.string.imessage_error_not_installed
        com.lightdecoder.imessage.IMessageErrors.NOT_VERIFIED -> R.string.imessage_error_not_verified
        com.lightdecoder.imessage.IMessageErrors.NO_ACCESS -> R.string.imessage_error_no_access
        com.lightdecoder.imessage.IMessageErrors.NO_REPLY_BUTTON -> if (direct) R.string.imessage_error_no_reply_direct else R.string.imessage_error_no_reply
        // Through the library nothing is handed to another app.
        com.lightdecoder.imessage.IMessageErrors.HAND_OFF -> if (direct) R.string.imessage_error_unsupported else R.string.imessage_error_hand_off
        WaErrors.UNSUPPORTED -> R.string.imessage_error_unsupported
        RelayState.NOTIFICATIONS_OFF -> R.string.link_error_notifications_off
        else -> if (direct) R.string.imessage_error_internal_direct else R.string.imessage_error_internal
    }

    @StringRes
    private fun mailRes(code: String): Int = when (code) {
        RelayException.NOT_PAIRED -> R.string.mail_error_not_set_up
        RelayException.NOT_CONNECTED, RelayException.CONNECTION_CLOSED -> R.string.mail_error_offline
        RelayException.LOGGED_OUT, com.lightdecoder.mail.MailErrors.AUTH_FAILED -> R.string.mail_error_auth_failed
        RelayException.INVALID_ARGUMENT -> R.string.mail_error_invalid_address
        RelayException.TOO_LARGE -> R.string.mail_error_too_large
        RelayException.SEND_FAILED -> R.string.mail_error_send_failed
        RelayException.NOT_FOUND -> R.string.mail_error_not_found
        RelayState.NOTIFICATIONS_OFF -> R.string.link_error_notifications_off
        else -> R.string.mail_error_internal
    }

    @StringRes
    private fun whatsAppRes(code: String): Int = when (code) {
        RelayException.NOT_PAIRED -> R.string.chat_wa_error_not_paired
        RelayException.NOT_CONNECTED -> R.string.chat_wa_error_not_connected
        RelayException.CONNECTION_CLOSED -> R.string.chat_wa_error_connection_closed
        RelayException.NOT_FOUND -> R.string.chat_wa_error_not_found
        RelayException.INVALID_ARGUMENT -> R.string.chat_wa_error_invalid_argument
        RelayException.TOO_LARGE -> R.string.link_error_too_large
        RelayException.SEND_FAILED -> R.string.chat_wa_error_send_failed
        RelayException.LOGGED_OUT -> R.string.chat_wa_error_logged_out
        // Linking (the setup screen).
        WaErrors.PAIR_FAILED -> R.string.wa_error_pair_failed
        WaErrors.ALREADY_PAIRED -> R.string.wa_error_already_paired
        RelayException.PAIRING_TIMEOUT -> R.string.wa_error_pairing_timeout
        RelayException.PAIRING_CANCELLED -> R.string.wa_error_pairing_cancelled
        WaErrors.TIMEOUT -> R.string.chat_wa_error_timeout
        WaErrors.RATE_LIMITED -> R.string.chat_wa_error_rate_limited
        WaErrors.NOT_ON_WHATSAPP -> R.string.chat_wa_error_not_on_whatsapp
        WaErrors.NOT_ALLOWED -> R.string.chat_wa_error_not_allowed
        WaErrors.UNSUPPORTED -> R.string.chat_wa_error_unsupported
        WaErrors.NOT_READY -> R.string.chat_wa_error_not_ready
        WaErrors.STREAM_REPLACED -> R.string.chat_wa_error_stream_replaced
        WaErrors.TEMP_BANNED -> R.string.chat_wa_error_temp_banned
        WaErrors.CLIENT_OUTDATED -> R.string.chat_wa_error_client_outdated
        WaErrors.CONNECT_FAILURE -> R.string.chat_wa_error_connect_failure
        WaErrors.CONNECTION_LOST -> R.string.chat_wa_error_connection_lost
        // Not bridge codes: why the runtime did not start the link, and an
        // unlink that could not reach WhatsApp (the phone may still list this device).
        RelayState.NOTIFICATIONS_OFF -> R.string.link_error_notifications_off
        RelayState.UNPAIR_REMOTE_FAILED -> R.string.chat_wa_error_unpair_remote_failed
        else -> R.string.chat_wa_error_internal
    }
}

/**
 * One [NotificationPermission] gate per service: each link has its own
 * notification channel, and a screen that can show either service needs
 * both gates registered before it starts. Create it during the activity's
 * construction.
 */
internal class ServiceNotifications(activity: ComponentActivity) {
    private val gates = EnumMap<ChatService, NotificationPermission>(ChatService::class.java).apply {
        for (service in ChatService.entries) put(service, NotificationPermission(activity, service))
    }

    operator fun get(service: ChatService): NotificationPermission = gates.getValue(service)
}

/**
 * Tells each backend whether one of its conversation screens is in front
 * ([ChatBackend.setAppVisible]): true when the first one starts, false when
 * the last one stops. Counted, because going from the list into a thread
 * starts the thread before it stops the list.
 */
internal object ChatVisibility {
    private val shown = EnumMap<ChatService, Int>(ChatService::class.java)

    @MainThread
    fun enter(backend: ChatBackend) {
        val count = (shown[backend.service] ?: 0) + 1
        shown[backend.service] = count
        if (count == 1) backend.setAppVisible(true)
    }

    @MainThread
    fun leave(backend: ChatBackend) {
        val count = (shown[backend.service] ?: 0) - 1
        if (count < 0) return
        shown[backend.service] = count
        if (count == 0) backend.setAppVisible(false)
    }

    @VisibleForTesting
    internal fun resetForTesting() = shown.clear()
}
