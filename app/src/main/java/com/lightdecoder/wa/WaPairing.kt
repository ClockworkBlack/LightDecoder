package com.lightdecoder.wa

/**
 * Where linking this device to the user's WhatsApp account stands, as
 * [WaManager.pairing] reports it to the setup screen.
 *
 * Linking is never automated: the user types the pairing code into WhatsApp
 * on their phone, or scans the QR code with it, themselves. Nothing here
 * answers for them.
 */
sealed class WaPairing {
    /** No linking in progress. */
    data object Idle : WaPairing()

    /**
     * WhatsApp handed out a pairing code for the user's number: [code] is
     * the 8 characters they type into WhatsApp on their phone (Linked
     * devices > Link a device > Link with phone number instead).
     */
    class WaitingForCode(val code: String) : WaPairing() {
        override fun equals(other: Any?): Boolean = other is WaitingForCode && other.code == code
        override fun hashCode(): Int = code.hashCode()

        /** Without the code: a state that ends up in a log line must not carry it. */
        override fun toString(): String = "WaitingForCode"
    }

    /**
     * [code] is the text of the QR code to show now, for WhatsApp on the
     * phone to scan (Linked devices > Link a device). WhatsApp replaces it
     * every few seconds, each time with a new state.
     */
    class WaitingForQr(val code: String) : WaPairing() {
        override fun equals(other: Any?): Boolean = other is WaitingForQr && other.code == code
        override fun hashCode(): Int = code.hashCode()

        /** Without the code, as for [WaitingForCode]. */
        override fun toString(): String = "WaitingForQr"
    }

    /** The phone confirmed: this device is linked. */
    data object Paired : WaPairing()

    /**
     * Linking ended without a link. [code] is a bridge error code (for
     * example "pairing_timeout", "pairing_cancelled", "rate_limited"; see
     * [WaErrors]), never a pairing code.
     */
    data class Failed(val code: String) : WaPairing()
}
