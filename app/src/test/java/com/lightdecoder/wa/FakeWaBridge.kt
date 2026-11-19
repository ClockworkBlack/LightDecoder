package com.lightdecoder.wa

import com.lightdecoder.relay.ContactDto
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.Cursor
import com.lightdecoder.relay.Folder
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.MessagePage
import com.lightdecoder.relay.RelayException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A scriptable stand-in for the Go WhatsApp bridge. It answers calls from
 * what the test put in it, records what was asked, and pushes events into
 * the [WaBridge.Sink] the way the Go dispatcher would ([emit] and the
 * emit... helpers).
 *
 * Like the real bridge it keeps "the store" in [dataDir]: a marker file says
 * whether the device is linked, so a second fake over the same directory
 * (the app restarted) starts linked. [markPaired] writes it beforehand.
 *
 * Install it with
 * `WaBridges.factory = { dir, sink -> FakeWaBridge(dir, sink).also { fake = it } }`.
 */
class FakeWaBridge(val dataDir: File, private val sink: WaBridge.Sink) : WaBridge {

    data class Sent(
        val conversationId: String,
        val tmpId: String,
        val text: String,
        val replyToId: String = "",
        /** The file a sendMediaFile() was given (null for sendText). */
        val path: String? = null,
        val fileName: String = "",
        val mime: String = "",
    )

    @Volatile
    var paired: Boolean = File(dataDir, PAIRED_MARKER).exists()

    @Volatile
    var connected = false

    /** The linking connection is open (connect() or startQr() while not linked). */
    @Volatile
    var linking = false

    @Volatile
    var closed = false

    /** Every call by name, in order. */
    val calls = CopyOnWriteArrayList<String>()

    /** A call named here throws this instead of doing anything (until removed). */
    val errors = ConcurrentHashMap<String, RelayException>()

    /** Whether connect() on a linked device goes straight to "connected", as a working network does. */
    @Volatile
    var connectsAtOnce = true

    // --- pairing
    /** What pairPhone() hands out. */
    @Volatile
    var pairingCode = "ABCD2345"
    val pairPhoneNumbers = CopyOnWriteArrayList<String>()

    @Volatile
    var ownId = OWN_ID

    /** logout() could not tell WhatsApp: it wipes, says "unpaired" with unpair_remote_failed, and throws that. */
    @Volatile
    var logoutRemoteFails = false

    // --- reads
    val conversations = CopyOnWriteArrayList<ConversationDto>()
    val contacts = CopyOnWriteArrayList<ContactDto>()

    /** Pages handed out in order per conversation; an empty, cursor-less page once they run out. */
    val pages = ConcurrentHashMap<String, ConcurrentLinkedQueue<MessagePage>>()
    val fetches = CopyOnWriteArrayList<Triple<String, Int, Cursor?>>()
    val media = ConcurrentHashMap<String, ByteArray>()
    val downloads = CopyOnWriteArrayList<String>()

    // --- sends and the rest
    /** Answers to sends, in order; a sent message once they run out. Each may also throw. */
    val sendAnswers = ConcurrentLinkedQueue<(Sent) -> MessageDto>()
    val sent = CopyOnWriteArrayList<Sent>()

    /** conversation id, message id, sender id, emoji ("" removes). */
    val reactions = CopyOnWriteArrayList<List<String>>()

    /** conversation id, message id, sender id. */
    val reads = CopyOnWriteArrayList<Triple<String, String, String>>()
    val typings = CopyOnWriteArrayList<Pair<String, Boolean>>()
    val presenceSubscriptions = CopyOnWriteArrayList<String>()
    val onlineStates = CopyOnWriteArrayList<Boolean>()

    /** conversation id, message id, for everyone. */
    val deletions = CopyOnWriteArrayList<Triple<String, String, Boolean>>()
    val edits = CopyOnWriteArrayList<Triple<String, String, String>>()
    val archived = CopyOnWriteArrayList<Pair<String, Boolean>>()
    val muted = CopyOnWriteArrayList<Pair<String, Boolean>>()

    private val messageIds = AtomicInteger(0)

    // ============================== WaBridge ==============================

    override val isPaired: Boolean get() = paired

    override fun status(): WaStatus = WaStatus(paired, paired && connected, if (paired) ownId else "", "")

    override fun connect() {
        call("connect")
        if (connected) return
        emitState("connecting")
        if (!paired) {
            linking = true
        } else if (connectsAtOnce) {
            goOnline()
        }
    }

    override fun disconnect() {
        calls += "disconnect"
        val was = connected || linking
        connected = false
        linking = false
        if (was) emitState("disconnected")
    }

    override fun pairPhone(phoneNumber: String): String {
        call("pairPhone")
        pairPhoneNumbers += phoneNumber
        if (paired) throw RelayException(WaErrors.ALREADY_PAIRED, "already linked; log out first")
        if (!linking) throw RelayException(RelayException.NOT_CONNECTED, "call Connect first")
        return pairingCode
    }

    override fun startQr() {
        call("startQr")
        if (paired) throw RelayException(WaErrors.ALREADY_PAIRED, "already linked; log out first")
        if (!linking) {
            emitState("connecting")
            linking = true
        }
    }

    override fun cancelPairing() {
        calls += "cancelPairing"
        if (!linking) return
        linking = false
        emitState("disconnected")
    }

    override fun logout() {
        call("logout")
        val failed = logoutRemoteFails && paired
        paired = false
        connected = false
        linking = false
        File(dataDir, PAIRED_MARKER).delete()
        emitState("unpaired", if (failed) RelayException.UNPAIR_REMOTE_FAILED else "")
        if (failed) throw RelayException(RelayException.UNPAIR_REMOTE_FAILED, "WhatsApp could not be told")
    }

    override fun listConversations(folder: Folder, count: Int): List<ConversationDto> {
        call("listConversations")
        requirePaired()
        return conversations.filter { it.folder == folder.wire }.take(count)
    }

    override fun listContacts(): List<ContactDto> {
        call("listContacts")
        requirePaired()
        return contacts.toList()
    }

    override fun fetchMessages(conversationId: String, count: Int, cursor: Cursor?): MessagePage {
        call("fetchMessages")
        requirePaired()
        fetches += Triple(conversationId, count, cursor)
        return pages[conversationId]?.poll() ?: MessagePage(emptyList(), null, 0)
    }

    override fun sendText(conversationId: String, tmpId: String, text: String, replyToId: String?): MessageDto {
        call("sendText")
        return send(Sent(conversationId, tmpId, text, replyToId.orEmpty()))
    }

    override fun sendMediaFile(
        conversationId: String,
        tmpId: String,
        path: String,
        fileName: String,
        mime: String,
        caption: String?,
        replyToId: String?,
    ): MessageDto {
        call("sendMediaFile")
        return send(Sent(conversationId, tmpId, caption.orEmpty(), replyToId.orEmpty(), path, fileName, mime))
    }

    override fun sendReaction(conversationId: String, messageId: String, senderId: String, emoji: String) {
        call("sendReaction")
        requireConnected()
        reactions += listOf(conversationId, messageId, senderId, emoji)
    }

    override fun markRead(conversationId: String, messageId: String, senderId: String) {
        call("markRead")
        requireConnected()
        reads += Triple(conversationId, messageId, senderId)
    }

    override fun setTyping(conversationId: String, typing: Boolean) {
        call("setTyping")
        requireConnected()
        typings += conversationId to typing
    }

    override fun subscribePresence(participantId: String) {
        call("subscribePresence")
        requireConnected()
        presenceSubscriptions += participantId
    }

    override fun setOnline(online: Boolean) {
        call("setOnline")
        requireConnected()
        onlineStates += online
    }

    override fun editText(conversationId: String, messageId: String, text: String) {
        call("editText")
        requireConnected()
        edits += Triple(conversationId, messageId, text)
    }

    override fun deleteMessage(conversationId: String, messageId: String, forEveryone: Boolean) {
        call("deleteMessage")
        requireConnected()
        deletions += Triple(conversationId, messageId, forEveryone)
        emitMessage(MessageDto(id = messageId, conversationId = conversationId, direction = "deleted"))
    }

    override fun setConversationArchived(conversationId: String, archived: Boolean) {
        call("setConversationArchived")
        requireConnected()
        this.archived += conversationId to archived
    }

    override fun setConversationMuted(conversationId: String, muted: Boolean) {
        call("setConversationMuted")
        requireConnected()
        this.muted += conversationId to muted
    }

    override fun downloadMedia(mediaId: String, key: ByteArray): ByteArray {
        call("downloadMedia")
        requireConnected()
        downloads += mediaId
        return media[mediaId] ?: throw RelayException(RelayException.NOT_FOUND, "media is no longer on the WhatsApp server")
    }

    override fun getOrCreateConversation(numbers: List<String>, groupName: String?): ConversationDto {
        call("getOrCreateConversation")
        requireConnected()
        if (numbers.size != 1 || !groupName.isNullOrEmpty()) {
            throw RelayException(WaErrors.UNSUPPORTED, "creating groups is not supported")
        }
        val id = jid(numbers.single())
        return conversations.firstOrNull { it.id == id } ?: ConversationDto(id = id, type = "WHATSAPP")
    }

    override fun close() {
        calls += "close"
        connected = false
        linking = false
        closed = true
    }

    // ============================== what the test drives ==============================

    /** Pushes one event, as the Go dispatcher does. */
    fun emit(kind: String, payload: JSONObject) = sink.onEvent(kind, payload.toString())

    fun emitState(state: String, code: String = "", detail: String = "") =
        emit(WaBridge.KIND_STATE, JSONObject().put("state", state).put("code", code).put("detail", detail))

    /** The connection of a linked device came up. */
    fun goOnline() {
        connected = true
        emitState("connected")
    }

    /** The connection dropped by itself and the bridge is retrying: "connecting" with connection_lost, as in Go. */
    fun dropConnection() {
        connected = false
        emitState("connecting", WaErrors.CONNECTION_LOST)
    }

    /** The connection ended for a reason the bridge does not retry (stream_replaced, temp_banned, client_outdated, ...). */
    fun failConnection(code: String) {
        connected = false
        emitState("error", code)
    }

    /** WhatsApp hands out the next QR code (after startQr()). */
    fun emitQr(code: String) = emit(WaBridge.KIND_QR, JSONObject().put("code", code))

    /**
     * The user confirmed on the phone: the device is linked, and the bridge
     * connects by itself (pair_success, then "connecting", then "connected",
     * as in Go).
     */
    fun completePairing(ownId: String = this.ownId) {
        this.ownId = ownId
        paired = true
        linking = false
        markPaired(dataDir)
        emit(WaBridge.KIND_PAIR_SUCCESS, JSONObject().put("ownId", ownId))
        emitState("connecting")
        if (connectsAtOnce) goOnline()
    }

    /** Linking ended without a link: pair_error, then "disconnected", as in Go. */
    fun failPairing(code: String = RelayException.PAIRING_TIMEOUT, detail: String = "") {
        linking = false
        emit(WaBridge.KIND_PAIR_ERROR, JSONObject().put("code", code).put("detail", detail))
        emitState("disconnected")
    }

    /** The phone unlinked this device: "logged_out", and the store is gone. */
    fun logOutFromPhone() {
        paired = false
        connected = false
        File(dataDir, PAIRED_MARKER).delete()
        emitState("logged_out", RelayException.LOGGED_OUT)
    }

    fun emitMessage(message: MessageDto) = emit(WaBridge.KIND_MESSAGE, message.toJson())

    fun emitConversation(conversation: ConversationDto) = emit(WaBridge.KIND_CONVERSATION, conversation.toJson())

    fun emitReceipt(conversationId: String, messageIds: List<String>, status: String, timestampMs: Long = System.currentTimeMillis()) =
        emit(
            WaBridge.KIND_RECEIPT,
            JSONObject().put("conversationId", conversationId).put("messageIds", JSONArray(messageIds))
                .put("status", status).put("timestampMs", timestampMs),
        )

    fun emitTyping(conversationId: String, participantId: String, typing: Boolean = true) =
        emit(
            WaBridge.KIND_TYPING,
            JSONObject().put("conversationId", conversationId).put("participantId", participantId).put("typing", typing),
        )

    fun emitPresence(participantId: String, online: Boolean, lastSeenMs: Long = 0) =
        emit(
            WaBridge.KIND_PRESENCE,
            JSONObject().put("participantId", participantId).put("online", online).put("lastSeenMs", lastSeenMs),
        )

    fun emitHistorySync(progress: Int, done: Boolean = progress >= 100) =
        emit(WaBridge.KIND_HISTORY_SYNC, JSONObject().put("progress", progress).put("done", done))

    // ============================== internals ==============================

    private fun call(name: String) {
        calls += name
        if (closed) throw RelayException(RelayException.INTERNAL, "bridge is closed")
        errors[name]?.let { throw it }
    }

    private fun requirePaired() {
        if (!paired) throw RelayException(RelayException.NOT_PAIRED, "link with your phone first")
    }

    private fun requireConnected() {
        requirePaired()
        if (!connected) throw RelayException(RelayException.NOT_CONNECTED, "not connected to WhatsApp")
    }

    private fun send(sent: Sent): MessageDto {
        requireConnected()
        this.sent += sent
        sendAnswers.poll()?.let { return it(sent) }
        return MessageDto(
            id = "wa-sent-${messageIds.incrementAndGet()}",
            conversationId = sent.conversationId,
            tmpId = sent.tmpId,
            senderId = jid(ownId),
            fromMe = true,
            timestampMs = System.currentTimeMillis(),
            direction = "out",
            status = "OUTGOING_COMPLETE",
            transport = "WHATSAPP",
            text = sent.text,
            replyToId = sent.replyToId,
        )
    }

    companion object {
        /** The account's number, as StatusJSON's ownId. */
        const val OWN_ID = "15550100"

        private const val PAIRED_MARKER = "fake-paired"

        /** The chat (and user) JID of a phone number. */
        fun jid(number: String): String = number.filter { it.isDigit() } + "@s.whatsapp.net"

        /** Makes the next fake over [dataDir] start linked, as a store with a device in it does. */
        fun markPaired(dataDir: File) {
            dataDir.mkdirs()
            File(dataDir, PAIRED_MARKER).writeText("1")
        }
    }
}
