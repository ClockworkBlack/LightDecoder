package com.lightdecoder.relay

/**
 * Everything the app asks of the Google Messages link, as plain Kotlin.
 *
 * The real implementation wraps the gomobile-generated Go bridge; this
 * interface keeps the rest of the app (and its tests) free of that generated
 * code. Every call blocks, some for up to a minute, so call off the main
 * thread. Failures are thrown as [RelayException].
 */
interface RelayBridge : java.io.Closeable {

    /** Receives what the bridge pushes. Called in order from one bridge thread. */
    interface Sink {
        /** Persist this session (encrypted). Null means the session was deleted. */
        fun onSession(data: ByteArray?)

        /** [kind] is one of the event kinds of contract §3; [payload] is JSON. */
        fun onEvent(kind: String, payload: String)
    }

    val isPaired: Boolean
    val isConnected: Boolean

    fun status(): RelayStatus

    fun startPairing(cookies: Map<String, String>): PairingChallenge
    fun finishPairing(timeoutMs: Long): String
    fun cancelPairing()

    fun connect()
    fun disconnect()
    fun setActiveSession()
    fun unpair()
    fun exportSession(): ByteArray?

    /**
     * Tells the bridge each chat's newest protocol-switch tombstone that the
     * archive holds, so that after a restart a send keeps an end-to-end
     * encrypted chat on RCS before that tombstone shows up again (Go:
     * SeedProtocolSwitches). Never touches the network.
     */
    fun seedProtocolSwitches(seed: List<ProtocolSwitchSeed>)

    fun listConversations(folder: Folder, count: Int): List<ConversationDto>
    fun getConversation(id: String): ConversationDto
    fun fetchMessages(conversationId: String, count: Int, cursor: Cursor?): MessagePage
    fun listContacts(): List<ContactDto>
    fun participantThumbnails(ids: List<String>): List<ThumbnailDto>

    fun downloadMedia(mediaId: String, key: ByteArray): ByteArray
    fun requestFullSizeImage(messageId: String, partId: String)

    fun sendText(conversationId: String, tmpId: String, text: String, replyToId: String?): SendResult
    fun sendMedia(
        conversationId: String,
        tmpId: String,
        data: ByteArray,
        fileName: String,
        mime: String,
        caption: String?,
        replyToId: String?,
    ): SendResult
    fun sendReaction(messageId: String, emoji: String, action: String)
    fun markRead(conversationId: String, messageId: String)
    fun setTyping(conversationId: String)
    fun deleteMessage(messageId: String)
    fun setConversationStatus(conversationId: String, status: String)
    fun setConversationMuted(conversationId: String, muted: Boolean)
    fun deleteConversation(conversationId: String)
    fun getOrCreateConversation(numbers: List<String>, rcsGroupName: String?): ConversationDto
}

/**
 * A failed bridge call. [code] is one of the bridge's error codes (contract
 * §2.3), e.g. "not_connected" or "incorrect_emoji", and is what the app acts
 * on. The message is the content-free detail after the code.
 */
class RelayException(val code: String, message: String) : Exception(message) {

    override fun toString(): String = "RelayException($code)"

    companion object {
        const val NOT_PAIRED = "not_paired"
        const val NOT_CONNECTED = "not_connected"
        const val NO_COOKIES = "no_cookies"
        const val COOKIES_INVALID = "cookies_invalid"
        const val NO_DEVICES = "no_devices"
        const val PAIRING_INIT_TIMEOUT = "pairing_init_timeout"
        const val INCORRECT_EMOJI = "incorrect_emoji"
        const val PAIRING_CANCELLED = "pairing_cancelled"
        const val PAIRING_TIMEOUT = "pairing_timeout"
        const val PAIRING_IN_PROGRESS = "pairing_in_progress"
        const val NO_PAIRING = "no_pairing"
        const val PHONE_NOT_RESPONDING = "phone_not_responding"
        const val CONNECTION_CLOSED = "connection_closed"
        const val NOT_FOUND = "not_found"
        const val INVALID_ARGUMENT = "invalid_argument"
        const val TOO_LARGE = "too_large"
        const val SEND_FAILED = "send_failed"
        const val LOGGED_OUT = "logged_out"
        const val INTERNAL = "internal"

        val CODES = setOf(
            NOT_PAIRED, NOT_CONNECTED, NO_COOKIES, COOKIES_INVALID, NO_DEVICES, PAIRING_INIT_TIMEOUT,
            INCORRECT_EMOJI, PAIRING_CANCELLED, PAIRING_TIMEOUT, PAIRING_IN_PROGRESS, NO_PAIRING,
            PHONE_NOT_RESPONDING, CONNECTION_CLOSED, NOT_FOUND, INVALID_ARGUMENT, TOO_LARGE,
            SEND_FAILED, LOGGED_OUT, INTERNAL,
        )

        /**
         * Turns anything the bridge threw into a [RelayException]. The Go side
         * throws messages shaped "<code>: <detail>"; anything else becomes
         * "internal", keeping only the exception's class name, since its text
         * was never checked for content.
         */
        fun fromThrowable(t: Throwable): RelayException {
            if (t is RelayException) return t
            val message = t.message.orEmpty()
            val code = message.substringBefore(": ").trim()
            if (code in CODES) {
                val detail = if (message.contains(": ")) message.substringAfter(": ") else ""
                return RelayException(code, detail).also { it.initCause(t) }
            }
            // Not chained as the cause: a cause's message (a JSONException quotes
            // the payload it choked on) would reach any log that prints the
            // stack trace. Where it happened is kept.
            return RelayException(INTERNAL, t.javaClass.simpleName).also { it.stackTrace = t.stackTrace }
        }
    }
}

/**
 * Where the app gets its [RelayBridge] when something other than the Go
 * bridge should be used. Nothing installs a factory in the app: while it is
 * null (always, outside tests), RelayManager creates a [GoRelayBridge]. Tests
 * install a fake one.
 */
object RelayBridges {
    @Volatile
    var factory: ((session: ByteArray?, sink: RelayBridge.Sink) -> RelayBridge)? = null
}
