package com.lightdecoder.mail

import com.lightdecoder.relay.RelayException

/**
 * Error codes of the Gmail tab, as [RelayException] codes so the screens can
 * explain them like the other services' ([com.lightdecoder.ui.chat.ChatErrors]).
 * Shared meanings keep the shared codes: [RelayException.NOT_CONNECTED] for
 * no network, [RelayException.INVALID_ARGUMENT] for a bad address,
 * [RelayException.SEND_FAILED], [RelayException.NOT_FOUND],
 * [RelayException.NOT_PAIRED] (no account added).
 */
object MailErrors {
    /** Google refused the address and app password (wrong, revoked, or 2-step verification turned off). */
    const val AUTH_FAILED = "auth_failed"
}
