package com.lightdecoder.relay

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A scriptable stand-in for the Go bridge. It answers calls from what the
 * test put in it, records what was asked, and pushes events into the
 * manager's [RelayBridge.Sink] the way the Go dispatcher would ([emit]).
 */
class FakeRelayBridge(session: ByteArray?, private val sink: RelayBridge.Sink) : RelayBridge {

    data class Sent(
        val conversationId: String,
        val tmpId: String,
        val text: String,
        val media: ByteArray? = null,
        val mime: String = "",
        val fileName: String = "",
    )

    @Volatile
    var paired: Boolean = session != null

    @Volatile
    var connected = false

    /** Every call by name, in order. */
    val calls = CopyOnWriteArrayList<String>()

    // --- pairing
    var challenge = PairingChallenge("🦊", "https://example.invalid/emoji.svg", "someone@example.com")
    var startPairingError: RelayException? = null
    var finishPairing: () -> String = { "phone-1" }
    var sessionAfterPairing: ByteArray = SESSION

    // --- reads
    val conversations = CopyOnWriteArrayList<ConversationDto>()
    val contacts = CopyOnWriteArrayList<ContactDto>()

    /** Pages handed out in order per conversation; an empty, cursor-less page once they run out. */
    val pages = ConcurrentHashMap<String, ConcurrentLinkedQueue<MessagePage>>()
    val fetches = CopyOnWriteArrayList<Triple<String, Int, Cursor?>>()

    // --- media
    val media = ConcurrentHashMap<String, ByteArray>()
    val downloads = CopyOnWriteArrayList<String>()
    val fullSizeRequests = CopyOnWriteArrayList<Pair<String, String>>()

    // --- sends
    /** Answers to sends, in order; a success once they run out. Each may also throw. */
    val sendAnswers = ConcurrentLinkedQueue<(String) -> SendResult>()
    val sent = CopyOnWriteArrayList<Sent>()

    /** Runs inside a send, before it returns (e.g. to deliver the echo early, as the phone often does). */
    @Volatile
    var duringSend: ((Sent) -> Unit)? = null

    override val isPaired: Boolean get() = paired
    override val isConnected: Boolean get() = connected

    override fun status() = RelayStatus(paired, connected, "someone@example.com", "phone-1", "session-1")

    override fun startPairing(cookies: Map<String, String>): PairingChallenge {
        calls += "startPairing"
        startPairingError?.let { throw it }
        return challenge
    }

    override fun finishPairing(timeoutMs: Long): String {
        calls += "finishPairing"
        val phoneId = finishPairing.invoke()
        paired = true
        sink.onSession(sessionAfterPairing)
        return phoneId
    }

    override fun cancelPairing() {
        calls += "cancelPairing"
    }

    override fun connect() {
        calls += "connect"
        if (!paired) throw RelayException(RelayException.NOT_PAIRED, "pair first")
        connected = true
        emitState("connecting")
    }

    override fun disconnect() {
        calls += "disconnect"
        connected = false
        sink.onSession(SESSION)
        emitState("disconnected")
    }

    override fun setActiveSession() {
        calls += "setActiveSession"
    }

    override fun unpair() {
        calls += "unpair"
        paired = false
        connected = false
        sink.onSession(null)
        emitState("unpaired")
    }

    override fun exportSession(): ByteArray? = if (paired) SESSION else null

    override fun listConversations(folder: Folder, count: Int): List<ConversationDto> {
        calls += "listConversations"
        requireConnected()
        return conversations.filter { it.folder == folder.wire }.take(count)
    }

    override fun getConversation(id: String): ConversationDto {
        requireConnected()
        return conversations.firstOrNull { it.id == id } ?: throw RelayException(RelayException.NOT_FOUND, "")
    }

    override fun fetchMessages(conversationId: String, count: Int, cursor: Cursor?): MessagePage {
        requireConnected()
        fetches += Triple(conversationId, count, cursor)
        return pages[conversationId]?.poll() ?: MessagePage(emptyList(), null)
    }

    override fun listContacts(): List<ContactDto> {
        requireConnected()
        return contacts.toList()
    }

    override fun participantThumbnails(ids: List<String>): List<ThumbnailDto> = emptyList()

    override fun downloadMedia(mediaId: String, key: ByteArray): ByteArray {
        downloads += mediaId
        if (!paired) throw RelayException(RelayException.NOT_PAIRED, "")
        return media[mediaId] ?: throw RelayException(RelayException.NOT_FOUND, "media is not available")
    }

    override fun requestFullSizeImage(messageId: String, partId: String) {
        requireConnected()
        fullSizeRequests += messageId to partId
    }

    override fun sendText(conversationId: String, tmpId: String, text: String, replyToId: String?): SendResult =
        send(Sent(conversationId, tmpId, text))

    override fun sendMedia(
        conversationId: String,
        tmpId: String,
        data: ByteArray,
        fileName: String,
        mime: String,
        caption: String?,
        replyToId: String?,
    ): SendResult = send(Sent(conversationId, tmpId, caption.orEmpty(), data.copyOf(), mime, fileName))

    private fun send(what: Sent): SendResult {
        requireConnected()
        sent += what
        duringSend?.invoke(what)
        val answer = sendAnswers.poll() ?: return SendResult(what.tmpId, ok = true, status = "SUCCESS")
        return answer(what.tmpId)
    }

    override fun sendReaction(messageId: String, emoji: String, action: String) {
        calls += "sendReaction:$action"
    }

    override fun markRead(conversationId: String, messageId: String) {
        calls += "markRead:$messageId"
    }

    override fun setTyping(conversationId: String) {
        calls += "setTyping"
    }

    override fun deleteMessage(messageId: String) {
        calls += "deleteMessage"
    }

    override fun setConversationStatus(conversationId: String, status: String) {
        calls += "setConversationStatus:$status"
    }

    override fun setConversationMuted(conversationId: String, muted: Boolean) {
        calls += "setConversationMuted:$muted"
    }

    override fun deleteConversation(conversationId: String) {
        calls += "deleteConversation"
    }

    override fun getOrCreateConversation(numbers: List<String>, rcsGroupName: String?): ConversationDto {
        requireConnected()
        return ConversationDto(id = "new-conv", name = "New", participants = emptyList())
    }

    override fun close() {
        calls += "close"
    }

    private fun requireConnected() {
        if (!paired) throw RelayException(RelayException.NOT_PAIRED, "")
        if (!connected) throw RelayException(RelayException.NOT_CONNECTED, "")
    }

    // --- pushing events, as Go's dispatcher would

    fun emit(kind: String, payload: JSONObject) = sink.onEvent(kind, payload.toString())

    /** As Go's OnSession (after a token refresh, every 30 min, on disconnect). */
    fun pushSession(data: ByteArray?) = sink.onSession(data)

    fun emitState(state: String, code: String = "", detail: String = "") =
        emit("state", JSONObject().put("state", state).put("code", code).put("detail", detail))

    fun emitMessage(message: MessageDto) = emit("message", message.toJson())

    fun emitSettings() = emit(
        "settings",
        JSONObject().put("sims", JSONArray().put(JSONObject().put("participantId", "me").put("number", "+15550000")))
            .put("rcsEnabled", true),
    )

    fun emitTyping(conversationId: String, participantId: String, typing: Boolean) = emit(
        "typing",
        JSONObject().put("conversationId", conversationId).put("participantId", participantId).put("typing", typing),
    )

    /** What Go does when the phone removes the pairing: session saved without it, then logged_out. */
    fun loseThePairing(sessionWithoutPairing: ByteArray) {
        paired = false
        connected = false
        sink.onSession(sessionWithoutPairing)
        emitState("logged_out", RelayException.LOGGED_OUT, "pairing no longer exists")
    }

    /** Brings the fake to "online": connecting → settings → connected. */
    fun goOnline(withSettings: Boolean = true) {
        connected = true
        emitState("connecting")
        if (withSettings) emitSettings()
        emitState("connected")
    }

    companion object {
        val SESSION = """{"envelope":1,"auth":"opaque"}""".toByteArray()
    }
}
