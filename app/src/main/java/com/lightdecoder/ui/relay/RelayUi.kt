package com.lightdecoder.ui.relay

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.lightdecoder.R
import com.lightdecoder.relay.RelayState
import com.lightdecoder.relay.RelayState.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** How each screen describes the link's state, and what it offers to do about it. */
object RelayUi {

    /** The one thing a banner or card offers for a state. */
    enum class Fix { NONE, SET_UP, CONNECT, USE_HERE, STATUS }

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

    /**
     * What to offer. Logged out and rejected cookies both need a new pairing
     * (the old one is gone or cannot sign in), so they lead to setup; a
     * generic error leads to the status screen and its log.
     */
    fun fix(state: RelayState, paired: Boolean): Fix = when {
        state.phase == Phase.LOGGED_OUT || state.phase == Phase.COOKIES_INVALID -> Fix.SET_UP
        !paired || state.phase == Phase.UNPAIRED -> Fix.SET_UP
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
        Fix.NONE -> R.string.link_fix_status
    }

    /** A problem the user has to act on (as opposed to the link working on it by itself). */
    fun isProblem(phase: Phase): Boolean =
        phase == Phase.LOGGED_OUT || phase == Phase.COOKIES_INVALID || phase == Phase.ERROR
}

/**
 * For the link's fire-and-forget calls (start/stop, retry, typing pings).
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
 * Asks for POST_NOTIFICATIONS (Android 13+) right before the link goes live,
 * so its "connected, tap to disconnect" notification is actually visible.
 * The link starts either way; refusing only hides the notification.
 * Create it during the activity's construction or onCreate.
 */
class NotificationPermission(private val activity: ComponentActivity) {
    private var then: (() -> Unit)? = null

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        then?.invoke()
        then = null
    }

    fun ensureThen(action: () -> Unit) {
        val needsAsk = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (!needsAsk) {
            action()
            return
        }
        then = action
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
