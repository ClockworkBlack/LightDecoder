package com.lightdecoder.ui.relay

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lightdecoder.R
import com.lightdecoder.chat.ChatService
import com.lightdecoder.relay.RelayNotifications
import com.lightdecoder.relay.RelayState
import com.lightdecoder.relay.RelayState.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** How each screen describes the link's state, and what it offers to do about it. */
object RelayUi {

    /** The one thing a banner or card offers for a state. */
    enum class Fix { NONE, SET_UP, CONNECT, USE_HERE, STATUS, NOTIFICATIONS }

    @StringRes
    fun title(phase: Phase): Int = when (phase) {
        Phase.UNPAIRED -> R.string.link_phase_unpaired
        Phase.PAIRING -> R.string.link_phase_pairing
        Phase.DISCONNECTED -> R.string.link_phase_disconnected
        Phase.CONNECTING -> R.string.link_phase_connecting
        Phase.CONNECTED, Phase.ACTIVE -> R.string.link_phase_connected
        Phase.ACTIVE_ELSEWHERE -> R.string.link_phase_active_elsewhere
        Phase.RECONNECTING -> R.string.link_phase_reconnecting
        Phase.PHONE_OFFLINE -> R.string.link_phase_phone_offline
        Phase.LOGGED_OUT -> R.string.link_phase_logged_out
        Phase.COOKIES_INVALID -> R.string.link_phase_cookies_invalid
        Phase.ERROR -> R.string.link_phase_error
    }

    @StringRes
    fun body(phase: Phase): Int = when (phase) {
        Phase.UNPAIRED -> R.string.link_body_unpaired
        Phase.PAIRING -> R.string.link_body_pairing
        Phase.DISCONNECTED -> R.string.link_body_disconnected
        Phase.CONNECTING -> R.string.link_body_connecting
        Phase.CONNECTED, Phase.ACTIVE -> R.string.link_body_connected
        Phase.ACTIVE_ELSEWHERE -> R.string.link_body_active_elsewhere
        Phase.RECONNECTING -> R.string.link_body_reconnecting
        Phase.PHONE_OFFLINE -> R.string.link_body_phone_offline
        Phase.LOGGED_OUT -> R.string.link_body_logged_out
        Phase.COOKIES_INVALID -> R.string.link_body_cookies_invalid
        Phase.ERROR -> R.string.link_body_error
    }

    /** The title for what [fix] offers: [Fix.NOTIFICATIONS] says so instead of the phase. */
    @StringRes
    fun title(phase: Phase, fix: Fix): Int =
        if (fix == Fix.NOTIFICATIONS) R.string.link_phase_notifications_off else title(phase)

    @StringRes
    fun body(phase: Phase, fix: Fix): Int =
        if (fix == Fix.NOTIFICATIONS) R.string.link_body_notifications_off else body(phase)

    /**
     * What to offer. Logged out and rejected cookies both need a new pairing
     * (the old one is gone or cannot sign in), so they lead to setup; a
     * generic error leads to the status screen and its log.
     *
     * [notificationsOn] is [RelayNotifications.statusVisible] right now. The
     * link never runs without its notification (and its Disconnect button),
     * so while that is off a link that is not running offers the system
     * notification settings instead of Connect, which could not work.
     */
    fun fix(state: RelayState, paired: Boolean, notificationsOn: Boolean = true): Fix = when {
        state.phase == Phase.LOGGED_OUT || state.phase == Phase.COOKIES_INVALID -> Fix.SET_UP
        !paired || state.phase == Phase.UNPAIRED -> Fix.SET_UP
        !notificationsOn && (state.phase == Phase.DISCONNECTED || state.code == RelayState.NOTIFICATIONS_OFF) ->
            Fix.NOTIFICATIONS
        state.phase == Phase.ACTIVE_ELSEWHERE -> Fix.USE_HERE
        state.phase == Phase.DISCONNECTED -> Fix.CONNECT
        state.phase == Phase.ERROR -> Fix.STATUS
        else -> Fix.NONE
    }

    @StringRes
    fun fixLabel(fix: Fix): Int = when (fix) {
        Fix.SET_UP -> R.string.link_fix_set_up
        Fix.CONNECT -> R.string.link_fix_connect
        Fix.USE_HERE -> R.string.link_fix_use_here
        Fix.STATUS -> R.string.link_fix_status
        Fix.NOTIFICATIONS -> R.string.link_fix_notifications
        Fix.NONE -> R.string.link_fix_status
    }

    /** A problem the user has to act on (as opposed to the link working on it by itself). */
    fun isProblem(phase: Phase): Boolean =
        phase == Phase.LOGGED_OUT || phase == Phase.COOKIES_INVALID || phase == Phase.ERROR

    /** Whether the ongoing notification of [service]'s link can be seen (see [RelayNotifications.statusVisible]). */
    fun notificationsOn(context: Context, service: ChatService = ChatService.MESSAGES): Boolean =
        RelayNotifications.statusVisible(context, service)

    /**
     * The system screen where the notification of [service]'s link can be
     * turned back on: the app's notification settings when all of them are
     * off (or, on Android 13+, not allowed), else the settings of that
     * link's channel.
     */
    fun notificationSettingsIntent(context: Context, service: ChatService = ChatService.MESSAGES): Intent =
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, RelayNotifications.statusChannel(service))
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
}

/**
 * For the link's fire-and-forget calls (retry, typing pings, imports).
 * They may touch the bridge, which must never run on the main thread, and
 * some should finish even when the screen that asked closes.
 */
internal val relayBackground = CoroutineScope(SupervisorJob() + Dispatchers.IO)

internal fun runInBackground(block: suspend () -> Unit) {
    relayBackground.launch {
        try {
            block()
        } catch (e: Exception) {
            // The manager logs its own failures; a screen that is gone cannot show them.
        }
    }
}

/**
 * The gate in front of everything that makes the link go live (setup's
 * Continue, "Stay connected", the banner's Connect). The link only ever runs
 * with its "connected, tap to disconnect" notification on screen, so the
 * action runs only once that notification can actually be seen: on Android
 * 13+ the POST_NOTIFICATIONS permission is asked for first, and the app's
 * notifications and the link's channel must be on.
 *
 * When they are not (the user refused, refused for good, or turned them off
 * in system settings), the action does not run; a dialog explains why and
 * offers the system settings, and the action runs on return if they were
 * turned on there. The runtime enforces the same rule by itself
 * ([RelayNotifications.statusVisible]); this only makes it understandable.
 *
 * Each link has its own notification channel, so a gate is for one
 * [service]'s link: the Google Messages one unless told otherwise.
 *
 * Create it during the activity's construction or onCreate.
 */
class NotificationPermission(
    private val activity: ComponentActivity,
    private val service: ChatService = ChatService.MESSAGES,
) {
    private var then: (() -> Unit)? = null
    private var refused: (() -> Unit)? = null
    private var afterSettings: (() -> Unit)? = null

    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = then
        val onRefused = refused
        then = null
        refused = null
        // After a rotation the pending action is gone: nothing to run or explain.
        if (action == null) return@registerForActivityResult
        if (granted && RelayUi.notificationsOn(activity, service)) {
            action()
        } else {
            onRefused?.invoke()
            explain(action)
        }
    }

    private val settings = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val action = afterSettings
        afterSettings = null
        if (action != null && RelayUi.notificationsOn(activity, service)) action()
    }

    /**
     * Runs [action] if the link's notification can be seen, asking for the
     * permission first where needed. Otherwise [onRefused] runs (for example
     * to put a switch back) and [action] does not.
     */
    fun ensureThen(onRefused: () -> Unit = {}, action: () -> Unit) {
        if (RelayUi.notificationsOn(activity, service)) {
            action()
            return
        }
        val canAsk = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (canAsk) {
            then = action
            refused = onRefused
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        onRefused()
        explain(action)
    }

    /** Only asks for the permission where Android still lets the app ask; for optional notifications. */
    fun requestIfNeeded() {
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        then = null
        refused = null
        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Opens the system screen for the link's notification; [after] runs on return if it is on by then. */
    fun openSettings(after: (() -> Unit)? = null) {
        afterSettings = after
        try {
            settings.launch(RelayUi.notificationSettingsIntent(activity, service))
        } catch (e: ActivityNotFoundException) {
            try {
                settings.launch(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
                )
            } catch (e2: ActivityNotFoundException) {
                afterSettings = null
                Toast.makeText(activity, R.string.link_settings_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun explain(action: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.link_notifications_needed_title)
            .setMessage(
                when (service) {
                    ChatService.MESSAGES -> R.string.link_notifications_needed_body
                    ChatService.WHATSAPP -> R.string.link_notifications_needed_body_whatsapp
                    ChatService.GMAIL -> R.string.mail_notifications_needed_body
                }
            )
            .setPositiveButton(R.string.link_notifications_open_settings) { _, _ -> openSettings(action) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
