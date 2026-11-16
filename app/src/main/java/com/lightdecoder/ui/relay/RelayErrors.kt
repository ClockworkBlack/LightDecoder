package com.lightdecoder.ui.relay

import android.content.Context
import androidx.annotation.StringRes
import com.lightdecoder.R
import com.lightdecoder.relay.RelayException
import com.lightdecoder.relay.RelayState
import kotlinx.coroutines.CancellationException

/**
 * Plain-language explanations for the link's error codes (contract §2.3).
 * Screens show these instead of the code; the code itself only ever goes to
 * the activity log.
 */
object RelayErrors {

    /** The explanation for [code]; codes this app does not know get the generic one. */
    @StringRes
    fun messageRes(code: String): Int = when (code) {
        RelayException.NOT_PAIRED -> R.string.link_error_not_paired
        RelayException.NOT_CONNECTED -> R.string.link_error_not_connected
        RelayException.NO_COOKIES -> R.string.link_error_no_cookies
        RelayException.COOKIES_INVALID -> R.string.link_error_cookies_invalid
        RelayException.NO_DEVICES -> R.string.link_error_no_devices
        RelayException.PAIRING_INIT_TIMEOUT -> R.string.link_error_pairing_init_timeout
        RelayException.INCORRECT_EMOJI -> R.string.link_error_incorrect_emoji
        RelayException.PAIRING_CANCELLED -> R.string.link_error_pairing_cancelled
        RelayException.PAIRING_TIMEOUT -> R.string.link_error_pairing_timeout
        RelayException.PAIRING_IN_PROGRESS -> R.string.link_error_pairing_in_progress
        RelayException.NO_PAIRING -> R.string.link_error_no_pairing
        RelayException.PHONE_NOT_RESPONDING -> R.string.link_error_phone_not_responding
        RelayException.CONNECTION_CLOSED -> R.string.link_error_connection_closed
        RelayException.NOT_FOUND -> R.string.link_error_not_found
        RelayException.INVALID_ARGUMENT -> R.string.link_error_invalid_argument
        RelayException.TOO_LARGE -> R.string.link_error_too_large
        RelayException.SEND_FAILED -> R.string.link_error_send_failed
        RelayException.LOGGED_OUT -> R.string.link_error_logged_out
        // Not a bridge code: the runtime's reason for not starting the link.
        RelayState.NOTIFICATIONS_OFF -> R.string.link_error_notifications_off
        else -> R.string.link_error_internal
    }

    fun message(context: Context, code: String): String = context.getString(messageRes(code))

    fun message(context: Context, t: Throwable): String = message(context, codeOf(t))

    /** The bridge code of any failure; anything that is not a [RelayException] counts as "internal". */
    fun codeOf(t: Throwable): String = RelayException.fromThrowable(t).code

    /** Pairing failures that only a new sign-in (fresh cookies) can fix. */
    fun needsNewSignIn(code: String): Boolean =
        code == RelayException.NO_COOKIES || code == RelayException.COOKIES_INVALID

    /** Pairing failures where simply trying again (same cookies) makes sense. */
    fun canRetryPairing(code: String): Boolean = !needsNewSignIn(code) && code != RelayException.NOT_PAIRED
}

/**
 * Runs one call into the link and captures its failure instead of throwing,
 * so a screen can show [RelayErrors.message]. Cancellation still propagates:
 * a screen that closes stops waiting.
 */
suspend fun <T> relayAttempt(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
