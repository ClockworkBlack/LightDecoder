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
        /** The file a sendMediaFile() was given (null for sendText/sendMedia). */
        val path: String? = null,
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

    /** Errors the next startPairing calls throw, one each, before behaving normally. */
    val startPairingFailsOnce = java.util.concurrent.ConcurrentLinkedQueue<RelayException>()
    /** What finishPairing returns: by default the phone status() reports, as in Go. */
    var finishPairing: () -> String = { phoneId }
    var sessionAfterPairing: ByteArray = SESSION

    // --- reads
    val conversations = CopyOnWriteArrayList<ConversationDto>()
    val contacts = CopyOnWriteArrayList<ContactDto>()

    /** Pages handed out in order per conversation; an empty, cursor-less page once they run out. */
    val pages = ConcurrentHashMap<String, ConcurrentLinkedQueue<MessagePage>>()
    val fetches = CopyOnWriteArrayList<Triple<String, Int, Cursor?>>()

    /**
     * When set, fetches of its conversations are answered from it as the
     * phone does (the newest [count] below the cursor, oldest first, with a
     * cursor while more remain) instead of from [pages].
     */
    val history = ConcurrentHashMap<String, List<MessageDto>>()

    /** Conversations whose every fetch fails with this. */
    val failFetches = ConcurrentHashMap<String, RelayException>()

    /** Runs at the start of every fetch (e.g. to hold it, as a slow phone would). */
    @Volatile
    var beforeFetch: ((String) -> Unit)? = null

    /** Runs inside connect() before it returns (e.g. to hold it, as Go's connect can for ~30 s). */
    @Volatile
    var duringConnect: (() -> Unit)? = null

    /** Runs inside listConversations() once the list is ready, before it is returned. */
    @Volatile
    var beforeListReturns: (() -> Unit)? = null

    /** Runs inside downloadMedia() once the bytes are ready, before they are returned. */
    @Volatile
    var beforeDownloadReturns: (() -> Unit)? = null

    /** Every download fails with this (e.g. not_connected: the token expired while disconnected). */
    @Volatile
    var downloadError: RelayException? = null

    // --- unpair
    /** The code of the "unpaired" state unpair() emits (Go: unpair_remote_failed when the revoke failed). */
    @Volatile
    var unpairCode: String = ""

    /** Runs inside unpair() before the pairing is dropped (Go may hold a short connection here). */
    @Volatile
    var duringUnpair: (() -> Unit)? = null

    /**
     * How long after unpair() returned its callbacks arrive. Go's Unpair
     * only queues them on its dispatcher, which calls into the app later.
     */
    @Volatile
    var unpairCallbackDelayMs = 50L

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

    /** What status() reports as the paired phone (another phone after a re-pair, or "" if unknown). */
    @Volatile
    var phoneId = "phone-1"

    override fun status() = RelayStatus(paired, connected, "someone@example.com", phoneId, "session-1")

    override fun startPairing(cookies: Map<String, String>): PairingChallenge {
        calls += "startPairing"
        startPairingFailsOnce.poll()?.let { throw it }
        startPairingError?.let { throw it }
        return challenge
    }

    override fun finishPairing(timeoutMs: Long): String {
        calls += "finishPairing"
        val phoneId = finishPairing.invoke()
        paired = true
        seeded = emptyList() // Go resets its cache on a new pairing
        seededSettings = null
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
        duringConnect?.invoke()
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
        duringUnpair?.invoke()
        paired = false
        connected = false
        seeded = emptyList() // Go resets its cache on unpair
        seededSettings = null
        val code = unpairCode
        val delayMs = unpairCallbackDelayMs
        // As Go's dispatcher would: in order, from its own thread, after unpair() returned.
        Thread {
            Thread.sleep(delayMs)
            sink.onSession(null)
            emitState("unpaired", code, if (code.isEmpty()) "" else "the phone may still list this device")
        }.apply { isDaemon = true }.start()
        // As Go's Unpair: the local wipe happened, the phone could not be told.
        if (code == RelayException.UNPAIR_REMOTE_FAILED) throw RelayException(code, "the phone may still list this device")
    }

    override fun exportSession(): ByteArray? = if (paired) SESSION else null

    /** Every seedProtocolSwitches() call's seed, in order (kept out of [calls]). */
    val seeds = CopyOnWriteArrayList<List<ProtocolSwitchSeed>>()

    /**
     * What Go holds as seeded right now: the last seed (each replaces the
     * one before), nothing after clearProtocolSwitches(), a new pairing or
     * unpair().
     */
    @Volatile
    var seeded: List<ProtocolSwitchSeed> = emptyList()

    /** How many times clearProtocolSwitches() was called (kept out of [calls]). */
    val clears = java.util.concurrent.atomic.AtomicInteger()

    /** clearProtocolSwitches() throws this when set (it must never stop a wipe). */
    @Volatile
    var clearError: Exception? = null

    override fun seedProtocolSwitches(seed: List<ProtocolSwitchSeed>) {
        seeds += seed
        seeded = seed
    }

    override fun clearProtocolSwitches() {
        clears.incrementAndGet()
        clearError?.let { throw it }
        seeded = emptyList()
    }

    /** Every seedSettings() payload, in order (kept out of [calls]). */
    val settingsSeeds = CopyOnWriteArrayList<String?>()

    /** What Go holds as seeded settings right now (null: none). */
    @Volatile
    var seededSettings: String? = null

    /** seedSettings() throws this when set. */
    @Volatile
    var seedSettingsError: RelayException? = null

    override fun seedSettings(settingsJson: String?) {
        settingsSeeds += settingsJson
        seedSettingsError?.let { throw it }
        seededSettings = settingsJson?.takeIf { it.isNotBlank() && it != "null" }
    }

    override fun listConversations(folder: Folder, count: Int): List<ConversationDto> {
        calls += "listConversations"
        if (!folder.listable) throw RelayException(RelayException.INVALID_ARGUMENT, "folder is local only")
        requireConnected()
        val list = conversations.filter { it.folder == folder.wire }.take(count)
        beforeListReturns?.invoke()
        return list
    }

    override fun getConversation(id: String): ConversationDto {
        requireConnected()
        return conversations.firstOrNull { it.id == id } ?: throw RelayException(RelayException.NOT_FOUND, "")
    }

    override fun fetchMessages(conversationId: String, count: Int, cursor: Cursor?): MessagePage {
        beforeFetch?.invoke(conversationId)
        requireConnected()
        failFetches[conversationId]?.let { throw it }
        fetches += Triple(conversationId, count, cursor)
        history[conversationId]?.let { all ->
            val below = all.filter { cursor == null || it.timestampMs < cursor.lastItemTimestampMs }.sortedByDescending { it.timestampMs }
            val page = below.take(count)
            val next = if (below.size > count) page.last().let { Cursor(it.id, it.timestampMs) } else null
            return MessagePage(page.reversed(), next)
        }
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
        downloadError?.let { throw it }
        val bytes = media[mediaId] ?: throw RelayException(RelayException.NOT_FOUND, "media is not available")
        beforeDownloadReturns?.invoke()
        return bytes
    }

    override fun requestFullSizeImage(messageId: String, partId: String) {
        requireConnected()
        fullSizeRequests += messageId to partId
    }

    override fun sendText(conversationId: String, tmpId: String, text: String, replyToId: String?): SendResult =
        send(Sent(conversationId, tmpId, text))

    /**
     * When set, [startText] works as Go's StartText: it returns as soon as the
     * text is "accepted by Google", and the phone's answer ([sendAnswers] or
     * SUCCESS) waits until this latch is released. Null: the whole send
     * happens in [startText], as the interface's default does.
     */
    @Volatile
    var holdPhoneAnswers: java.util.concurrent.CountDownLatch? = null

    /** How many started texts are waiting for their phone answer now. */
    val awaitingPhone = java.util.concurrent.atomic.AtomicInteger()

    override fun startText(conversationId: String, tmpId: String, text: String, replyToId: String?): RelayBridge.PendingSend {
        val hold = holdPhoneAnswers ?: return super.startText(conversationId, tmpId, text, replyToId)
        requireConnected()
        val what = Sent(conversationId, tmpId, text)
        sent += what
        duringSend?.invoke(what)
        val answer = sendAnswers.poll()
        awaitingPhone.incrementAndGet()
        return RelayBridge.PendingSend {
            try {
                hold.await(10, java.util.concurrent.TimeUnit.SECONDS)
            } finally {
                awaitingPhone.decrementAndGet()
            }
            answer?.invoke(tmpId) ?: SendResult(tmpId, ok = true, status = "SUCCESS")
        }
    }

    override fun sendMedia(
        conversationId: String,
        tmpId: String,
        data: ByteArray,
        fileName: String,
        mime: String,
        caption: String?,
        replyToId: String?,
    ): SendResult = send(Sent(conversationId, tmpId, caption.orEmpty(), data.copyOf(), mime, fileName))

    override fun sendMediaFile(
        conversationId: String,
        tmpId: String,
        path: String,
        fileName: String,
        mime: String,
        caption: String?,
        replyToId: String?,
    ): SendResult {
        requireConnected()
        // As Go: the bridge reads the file itself.
        val file = java.io.File(path)
        if (!file.isFile) throw RelayException(RelayException.INVALID_ARGUMENT, "attachment is missing")
        if (file.length() > 100L * 1024 * 1024) throw RelayException(RelayException.TOO_LARGE, "")
        return send(Sent(conversationId, tmpId, caption.orEmpty(), file.readBytes(), mime, fileName, path))
    }

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
        // Go sends these to the phone: they need a live link, like the other calls.
        requireConnected()
    }

    override fun setConversationStatus(conversationId: String, status: String) {
        calls += "setConversationStatus:$status"
        requireConnected()
    }

    override fun setConversationMuted(conversationId: String, muted: Boolean) {
        calls += "setConversationMuted:$muted"
        requireConnected()
    }

    override fun deleteConversation(conversationId: String) {
        calls += "deleteConversation"
        requireConnected()
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

    /** As Go on *events.AuthTokenRefreshed. */
    fun emitTokenRefreshed() = emit("token_refreshed", JSONObject())

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
