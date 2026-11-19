package com.lightdecoder.wa

import com.lightdecoder.relay.ContactDto
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.Cursor
import com.lightdecoder.relay.Folder
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.MessagePage
import com.lightdecoder.relay.RelayException
import java.io.File

/**
 * Everything the app asks of the WhatsApp link, as plain Kotlin: a mirror of
 * the Go bridge (gmbridge/wa, type WABridge), one function per Go method.
 *
 * The real implementation wraps the gomobile-generated
 * com.lightdecoder.gm.wa.WABridge; this interface keeps the rest of the app
 * (and its tests) free of that generated code. Every call blocks, some for
 * up to a minute, so call off the main thread. Failures are thrown as
 * [RelayException] with one of [WaErrors.CODES].
 *
 * Ids, all of which identify people and so never go into logs, exception
 * text that is logged, or notification tags (see [WaIds]):
 *  - a conversation id is the chat's JID ("<number>@s.whatsapp.net",
 *    "<id>@g.us");
 *  - a participant id is a user's JID (without device part);
 *  - a message id is WhatsApp's message id;
 *  - [com.lightdecoder.relay.PartDto.mediaId] is an opaque token only
 *    [downloadMedia] understands.
 *
 * The bridge keeps its own store (keys, chat index, message cache) in the
 * directory it was created with; there is no session for the app to hold.
 */
interface WaBridge : java.io.Closeable {

    /** Receives what the bridge pushes. Called in order from one bridge thread; must not block for long. */
    fun interface Sink {
        /** [kind] is one of the KIND_ constants of [WaBridge]; [payload] is JSON. */
        fun onEvent(kind: String, payload: String)
    }

    /** Whether this device is linked to a WhatsApp account. */
    val isPaired: Boolean

    /** The bridge's own view of the link. Holds the account's number: never log it. */
    fun status(): WaStatus

    /**
     * Connects to WhatsApp. When linked, it returns once the attempt has
     * started ("connected" follows as a [KIND_STATE] event, and the bridge
     * reconnects by itself until [disconnect]). When not linked, it opens the
     * linking connection [pairPhone] needs: the first step of pairing.
     * Already connected: nothing happens.
     */
    fun connect()

    /** Closes the connection, keeping the link, and abandons a linking attempt. The bridge stops reconnecting. */
    fun disconnect()

    /**
     * Asks WhatsApp for a pairing code for the user's own phone number
     * (international format; "+", spaces and dashes are ignored) and returns
     * its 8 characters, which the user types into WhatsApp on that phone
     * themselves. Needs [connect] first. The outcome arrives as
     * [KIND_PAIR_SUCCESS] or [KIND_PAIR_ERROR]. The code is a secret while
     * it is valid: never log it.
     */
    fun pairPhone(phoneNumber: String): String

    /**
     * Starts [KIND_QR] events, each the text of a QR code to show for
     * WhatsApp on the phone to scan, until linking succeeds, fails, times
     * out or [cancelPairing]. Opens the linking connection if [connect] has
     * not.
     */
    fun startQr()

    /** Abandons a linking attempt: its connection closes and "disconnected" follows. Nothing happens when none runs. */
    fun cancelPairing()

    /**
     * Unlinks this device: tells WhatsApp (best effort, connecting briefly if
     * needed), then wipes the bridge's store whatever happened. Returns
     * normally when WhatsApp was told or nothing was linked. Throws
     * [RelayException] with code [RelayException.UNPAIR_REMOTE_FAILED] when
     * only the local wipe happened (the unlink is done here all the same,
     * and the "unpaired" state carries the same code), so the user can be
     * told to remove the device on the phone.
     */
    fun logout()

    /** Conversations from the bridge's own chat index, most recent first. [Folder.INBOX] or [Folder.ARCHIVE]; [Folder.SPAM] is always empty. */
    fun listConversations(folder: Folder, count: Int): List<ConversationDto>

    /** The people the bridge knows a name for (the phone's address book, and push names). */
    fun listContacts(): List<ContactDto>

    /**
     * Up to [count] messages the bridge has stored for the chat (history
     * sync and live messages), oldest first, older than [cursor] (the newest
     * when it is null), with the cursor for the next older page (null when
     * nothing older is stored). When the stored messages run out and the
     * link is connected it also asks the phone for older history; those
     * messages arrive later as [KIND_MESSAGE] events.
     */
    fun fetchMessages(conversationId: String, count: Int, cursor: Cursor?): MessagePage

    /** Sends a text message (a reply when [replyToId] is set) and returns the sent message: WhatsApp's id and timestamp, with [tmpId] set. */
    fun sendText(conversationId: String, tmpId: String, text: String, replyToId: String?): MessageDto

    /**
     * Sends the file at [path] (an image, video, audio or any other document;
     * at most 100 MB), read by the bridge itself, and returns the sent
     * message like [sendText]. [mime] may be empty (the bridge then sniffs
     * it); [fileName] is what the recipient sees for a document.
     */
    fun sendMediaFile(
        conversationId: String,
        tmpId: String,
        path: String,
        fileName: String,
        mime: String,
        caption: String?,
        replyToId: String?,
    ): MessageDto

    /** Reacts to a message with [emoji]; "" removes this account's reaction. [senderId] is the message's sender ("" for the bridge to look up, or one's own message). */
    fun sendReaction(conversationId: String, messageId: String, senderId: String, emoji: String)

    /** Sends a read receipt up to [messageId] ([senderId]: its sender, may be "" in one-to-one chats) and marks the chat read. */
    fun markRead(conversationId: String, messageId: String, senderId: String)

    /** Tells the chat this account is typing, or stopped. */
    fun setTyping(conversationId: String, typing: Boolean)

    /** Asks for [KIND_PRESENCE] events of one user. WhatsApp only sends them while this account is online ([setOnline]). */
    fun subscribePresence(participantId: String)

    /** This account's own presence: available while the app is in front, unavailable otherwise (so the phone keeps getting notifications). */
    fun setOnline(online: Boolean)

    /**
     * Replaces the text of one of this account's own text messages for
     * everyone. WhatsApp only takes it for a while after the message was sent
     * (the bridge refuses later ones with invalid_argument). A [KIND_MESSAGE]
     * event with the new text follows.
     */
    fun editText(conversationId: String, messageId: String, text: String)

    /**
     * Deletes a message for everyone (one's own messages, or any as a group
     * admin) or only on this account's devices. A [KIND_MESSAGE] event with
     * direction "deleted" follows.
     */
    fun deleteMessage(conversationId: String, messageId: String, forEveryone: Boolean)

    /** Archives or unarchives a chat on all of this account's devices. */
    fun setConversationArchived(conversationId: String, archived: Boolean)

    /** Mutes (until unmuted) or unmutes a chat on all of this account's devices. */
    fun setConversationMuted(conversationId: String, muted: Boolean)

    /**
     * Downloads and decrypts one attachment. [mediaId] is the token in
     * [com.lightdecoder.relay.PartDto.mediaId]; [key] is only used when the
     * token carries none of its own. Needs a connection.
     */
    fun downloadMedia(mediaId: String, key: ByteArray): ByteArray

    /**
     * The one-to-one chat with a phone number (international format), after
     * checking that the number is on WhatsApp ([WaErrors.NOT_ON_WHATSAPP]
     * if not). More than one number, or a [groupName], asks for a new group,
     * which the bridge does not create: [WaErrors.UNSUPPORTED].
     */
    fun getOrCreateConversation(numbers: List<String>, groupName: String?): ConversationDto

    /** Disconnects, closes the store and stops the bridge's thread. The bridge is unusable afterwards. */
    override fun close()

    companion object {
        /** A [com.lightdecoder.relay.RelayStateEvent]: "connecting", "connected", "disconnected", "logged_out", "error" or "unpaired". */
        const val KIND_STATE = "state"

        /** {"code": the text of the QR code to show now}. Parse with [WaQr]. */
        const val KIND_QR = "qr"

        /** Linking succeeded: {"ownId": the account's number}. Parse with [WaPairSuccess]. */
        const val KIND_PAIR_SUCCESS = "pair_success"

        /** Linking failed: {"code": a bridge error code, "detail"}. Parse with [WaPairError]. */
        const val KIND_PAIR_ERROR = "pair_error"

        /**
         * A [MessageDto]: live messages, one's own messages sent from other
         * devices, and history sync messages (isOld). Edits and reactions
         * send the message again; a revoke has direction "deleted".
         */
        const val KIND_MESSAGE = "message"

        /** A [ConversationDto]. */
        const val KIND_CONVERSATION = "conversation"

        /** Delivery and read receipts of sent messages. Parse with [WaReceipt]. */
        const val KIND_RECEIPT = "receipt"

        /** A [com.lightdecoder.relay.TypingDto]. */
        const val KIND_TYPING = "typing"

        /** Someone's online state, after [subscribePresence]. Parse with [WaPresenceEvent]. */
        const val KIND_PRESENCE = "presence"

        /** How far the phone's history sync has got. Parse with [WaHistorySync]. */
        const val KIND_HISTORY_SYNC = "history_sync"
    }
}

/**
 * The error codes of the WhatsApp bridge (gmbridge/wa/errors.go). The ones
 * it shares with the Google Messages bridge are spelled the same and are the
 * constants of [RelayException]; the ones only WhatsApp has are here.
 */
object WaErrors {
    /** Linking was asked for while this device is linked: log out first. */
    const val ALREADY_PAIRED = "already_paired"

    /** WhatsApp did not answer in time. */
    const val TIMEOUT = "timeout"

    /** WhatsApp is rate limiting (pairing codes, chat settings). */
    const val RATE_LIMITED = "rate_limited"

    /** The number is not on WhatsApp. */
    const val NOT_ON_WHATSAPP = "not_on_whatsapp"

    /** WhatsApp refused: no permission (in this group, say). */
    const val NOT_ALLOWED = "not_allowed"

    /** The bridge does not do this (creating groups, broadcast lists). */
    const val UNSUPPORTED = "unsupported"

    /** WhatsApp has not finished syncing yet; try again shortly. */
    const val NOT_READY = "not_ready"

    /** The phone or WhatsApp rejected the link. */
    const val PAIR_FAILED = "pair_failed"

    /** Another connection took this device's place. */
    const val STREAM_REPLACED = "stream_replaced"

    /** WhatsApp temporarily banned the account. */
    const val TEMP_BANNED = "temp_banned"

    /** WhatsApp no longer accepts this version of the bridge. */
    const val CLIENT_OUTDATED = "client_outdated"

    /** WhatsApp refused the connection. */
    const val CONNECT_FAILURE = "connect_failure"

    /** The connection dropped; the bridge reconnects by itself. */
    const val CONNECTION_LOST = "connection_lost"

    /** Every code the WhatsApp bridge reports, shared ones included. */
    val CODES: Set<String> = setOf(
        RelayException.NOT_PAIRED, RelayException.NOT_CONNECTED, RelayException.CONNECTION_CLOSED,
        RelayException.NOT_FOUND, RelayException.INVALID_ARGUMENT, RelayException.TOO_LARGE,
        RelayException.SEND_FAILED, RelayException.LOGGED_OUT, RelayException.INTERNAL,
        RelayException.PAIRING_TIMEOUT, RelayException.PAIRING_CANCELLED, RelayException.UNPAIR_REMOTE_FAILED,
        ALREADY_PAIRED, TIMEOUT, RATE_LIMITED, NOT_ON_WHATSAPP, NOT_ALLOWED, UNSUPPORTED, NOT_READY,
        PAIR_FAILED, STREAM_REPLACED, TEMP_BANNED, CLIENT_OUTDATED, CONNECT_FAILURE, CONNECTION_LOST,
    )

    /**
     * Turns anything the WhatsApp bridge threw into a [RelayException], as
     * [RelayException.fromThrowable] does for the Google Messages bridge,
     * but knowing WhatsApp's codes. Anything that is not "<code>: <detail>"
     * with one of [CODES] becomes "internal" with only the exception's class
     * name, since its text was never checked for content.
     */
    fun fromThrowable(t: Throwable): RelayException = RelayException.fromThrowable(t, CODES)
}

/**
 * Where the app gets its [WaBridge] when something other than the Go bridge
 * should be used. Nothing installs a factory in the app: while it is null
 * (always, outside tests), [WaManager] creates the Go bridge. Tests install
 * a fake one.
 *
 * [dataDir] is the app-private directory the bridge keeps its store in
 * ([WaManager.storeDir]).
 */
object WaBridges {
    @Volatile
    var factory: ((dataDir: File, sink: WaBridge.Sink) -> WaBridge)? = null
}
