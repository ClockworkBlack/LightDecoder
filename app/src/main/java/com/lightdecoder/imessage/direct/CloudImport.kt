package com.lightdecoder.imessage.direct

import android.util.Log
import java.io.File
import java.util.Base64
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Importing older messages from Messages in iCloud (B6.5), read-only, in the ":imessage" process:
 * the library's sync pages (chats, then attachments' records, then messages), mapped
 * ([CloudMapping]) and handed to the main process as "cloudBatch" updates, which it stores through
 * its archive as any other change (blocked people and deleted messages left out there).
 *
 * - Read-only: only `syncCloud*` and, on demand, `downloadCloudAttachments`. Nothing is ever
 *   deleted, saved or reset in iCloud, and an iCloud record that is deleted is not deleted here.
 * - Runs when the user taps "Import Now", and once per start of this process while Messages in iCloud
 *   is on; never because Apple sent something. One run at a time; [stop] ends it.
 * - Its own thread with a large stack (the library's decoding recurses deeply), as OpenBubbles.
 * - Each kind's continuation token is kept after each page (`files/imessage/cloud/`), so a run
 *   stopped or cut short goes on where it was. "Too many requests" waits and tries again,
 *   [MAX_ATTEMPTS] times, [FIRST_WAIT_MS] then longer up to [LAST_WAIT_MS]; anything else ends the run
 *   ([Progress.failed]); one that says Advanced Data Protection is on turns it off ([adp]).
 */
class CloudImport(
    private val bridge: FeatureBridge,
    private val dir: File,
    private val enabled: () -> Boolean,
    /** Hands one batch to the main process; true when it was stored. */
    private val deliver: suspend (DirectArchive.Update.CloudBatch) -> Boolean,
    /** Turns Messages in iCloud off, saying why (Advanced Data Protection is on). */
    private val adp: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    context: CoroutineContext? = null,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    /** A call to Apple failed ([AccountFeatures]: an authentication error makes the account need attention). */
    private val failed: (Throwable) -> Unit = {},
) {

    /** How a run stands: running, how many chats and messages it imported, and why it ended if it failed. */
    data class Progress(val running: Boolean = false, val chats: Int = 0, val messages: Int = 0, val failed: String? = null, val done: Boolean = false)

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress

    private val scope by lazy { CoroutineScope(SupervisorJob() + (context ?: largeStack())) }
    private var job: Job? = null
    private val lock = Any()

    /** Starts a run of messages since [sinceMs] (0: all), unless one runs. False when it was not started. */
    fun start(sinceMs: Long): Boolean = synchronized(lock) {
        if (!enabled()) return false
        if (job?.isActive == true) return false
        _progress.value = Progress(running = true)
        job = scope.launch {
            try {
                run(sinceMs)
                currentCoroutineContext().ensureActive()
                _progress.value = _progress.value.copy(running = false, done = true)
            } catch (e: CancellationException) {
                _progress.value = _progress.value.copy(running = false)
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "the import from iCloud stopped (${e::class.java.simpleName})")
                val text = com.lightdecoder.imessage.Redact.secrets(DirectEngine.readable(e))
                if (isAdp(text)) adp()
                failed(e)
                _progress.value = _progress.value.copy(running = false, failed = text.take(200))
            }
        }
        true
    }

    /** Stops a run (it goes on from where it was next time). */
    fun stop() {
        synchronized(lock) { job }?.cancel()
    }

    /** Tests: waits for the run. */
    internal suspend fun join() {
        synchronized(lock) { job }?.join()
    }

    /**
     * Messages in iCloud turned off, or signed out: the run stops and its tokens and maps go (what was
     * imported stays). Waits for the run to have ended first, so that nothing of it writes them again.
     */
    suspend fun forget() {
        val running = synchronized(lock) { job }
        running?.cancel()
        running?.join()
        dir.deleteRecursively()
        _progress.value = Progress()
    }

    private suspend fun run(sinceMs: Long) {
        val state = State.load(dir)
        // A wider window than the messages' token was made with: their pages again from the start (what
        // is stored already is left out by the main process), else the older ones would never come.
        val since = state.since
        if (since == null || sinceMs < since) {
            if (since != null) state.tokens.remove("messages")
            state.since = sinceMs
        }
        // Chats first: messages are filed by them.
        var token = state.tokens["chats"]
        while (enabled()) {
            val page = attempt { bridge.syncCloudChats(token) }
            val chats = page.items.entries.mapNotNull { (rid, c) -> c?.let { CloudMapping.chat(rid, it) } }
            for (c in chats) state.chats[c.identifier] = c.conversation.id
            if (chats.isNotEmpty()) {
                for (b in CloudMapping.batches(chats.map { it.conversation }, emptyList())) if (!deliver(b)) throw DirectEngine.ImessageError("not stored")
                _progress.value = _progress.value.copy(chats = _progress.value.chats + chats.size)
            }
            token = page.token
            state.tokens["chats"] = token
            save(state)
            if (page.state == DONE || page.items.isEmpty()) break
        }
        token = state.tokens["attachments"]
        while (enabled()) {
            val page = attempt { bridge.syncCloudAttachments(token) }
            for ((rid, a) in page.items) {
                val (guid, att) = a?.let { CloudMapping.attachment(rid, it) } ?: continue
                state.attachments[guid] = att
            }
            state.trim()
            token = page.token
            state.tokens["attachments"] = token
            save(state)
            if (page.state == DONE || page.items.isEmpty()) break
        }
        token = state.tokens["messages"]
        while (enabled()) {
            val page = attempt { bridge.syncCloudMessages(token) }
            val messages = page.items.values.mapNotNull { m ->
                m?.let {
                    CloudMapping.message(
                        it, state.chats, state.attachments,
                        decode = { bytes -> bridge.decodeAttributedBody(bytes) },
                        summary = { bytes -> bridge.decodeMessageSummaryInfo(bytes) },
                        skip = { false }, sinceMs = sinceMs, now = now(),
                    )
                }
            }
            if (messages.isNotEmpty()) {
                for (b in CloudMapping.batches(emptyList(), messages)) if (!deliver(b)) throw DirectEngine.ImessageError("not stored")
                _progress.value = _progress.value.copy(messages = _progress.value.messages + messages.size)
            }
            token = page.token
            state.tokens["messages"] = token
            save(state)
            if (page.state == DONE || page.items.isEmpty()) break
        }
    }

    /** Kept, unless the run was stopped meanwhile (a [forget] deletes what is kept once it ended). */
    private suspend fun save(state: State) {
        currentCoroutineContext().ensureActive()
        state.save(dir)
    }

    /** [block], tried again after "Too many requests" ([MAX_ATTEMPTS] in all). */
    private suspend fun <T> attempt(block: suspend () -> T): T {
        var n = 0
        while (true) {
            // Stopped ([stop]): no page more.
            currentCoroutineContext().ensureActive()
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                n++
                val text = DirectEngine.readable(e)
                if (!text.contains("too many requests", ignoreCase = true) || n >= MAX_ATTEMPTS) throw e
                wait(waitFor(n))
            }
        }
    }

    /** Where a run is, kept after each page: each kind's token, the chats by Apple's identifier, attachments' records by GUID. */
    internal class State(
        val tokens: MutableMap<String, ByteArray?> = HashMap(),
        val chats: MutableMap<String, String> = LinkedHashMap(),
        val attachments: MutableMap<String, CloudMapping.Attachment> = LinkedHashMap(),
        /** The earliest time the messages' token was paged for (0: all); null before the first run. */
        var since: Long? = null,
    ) {
        fun trim() {
            while (attachments.size > MAX_ATTACHMENT_RECORDS) attachments.remove(attachments.keys.first())
            while (chats.size > MAX_CHATS) chats.remove(chats.keys.first())
        }

        fun save(dir: File) {
            trim()
            val o = JSONObject()
                .put("tokens", JSONObject().apply { tokens.forEach { (k, v) -> if (v != null) put(k, Base64.getEncoder().encodeToString(v)) } })
                .put("chats", JSONObject().apply { chats.forEach { (k, v) -> put(k, v) } })
                .apply { since?.let { put("since", it) } }
                .put(
                    "attachments",
                    JSONArray().apply {
                        attachments.forEach { (g, a) -> put(JSONObject().put("g", g).put("r", a.record).put("m", a.mime).put("n", a.name).put("s", a.size)) }
                    },
                )
            dir.mkdirs()
            val tmp = File(dir, "state.json.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(File(dir, "state.json"))) tmp.delete()
        }

        companion object {
            fun load(dir: File): State {
                val s = State()
                val o = try {
                    File(dir, "state.json").takeIf { it.isFile && it.length() <= MAX_STATE_BYTES }?.readText()?.let(::JSONObject) ?: return s
                } catch (e: JSONException) {
                    return s
                } catch (e: java.io.IOException) {
                    return s
                }
                o.optJSONObject("tokens")?.let { t ->
                    for (k in listOf("chats", "attachments", "messages")) {
                        (t.opt(k) as? String)?.let { v -> runCatching { Base64.getDecoder().decode(v) }.getOrNull() }?.let { s.tokens[k] = it }
                    }
                }
                (o.opt("since") as? Number)?.toLong()?.takeIf { it >= 0 }?.let { s.since = it }
                o.optJSONObject("chats")?.let { c ->
                    for (k in c.keys().asSequence().take(MAX_CHATS)) {
                        (c.opt(k) as? String)?.takeIf { DirectArchive.isImessage(it) && it.length <= DirectWire.MAX_CONVERSATION_ID_CHARS }?.let { s.chats[k] = it }
                    }
                }
                o.optJSONArray("attachments")?.let { a ->
                    for (i in 0 until minOf(a.length(), MAX_ATTACHMENT_RECORDS)) {
                        val x = a.optJSONObject(i) ?: continue
                        val g = (x.opt("g") as? String)?.takeIf(RecycleRules::isGuid) ?: continue
                        val r = (x.opt("r") as? String)?.takeIf(DirectWire::isCloudRecord) ?: continue
                        s.attachments[g] = CloudMapping.Attachment(r, x.optString("m").take(128), x.optString("n").take(128), x.optLong("s").coerceAtLeast(0))
                    }
                }
                return s
            }
        }
    }

    companion object {
        private const val TAG = "CloudImport"
        const val MAX_ATTEMPTS = 6
        const val FIRST_WAIT_MS = 10_000L
        const val LAST_WAIT_MS = 30_000L

        /** The library's page state once a kind is done. */
        const val DONE = 3
        private const val MAX_ATTACHMENT_RECORDS = 20_000
        private const val MAX_CHATS = 5_000
        private const val MAX_STATE_BYTES = 8L * 1024 * 1024
        private const val STACK_BYTES = 64L * 1024 * 1024

        /** The wait after the [n]th "Too many requests": 10 s, rising to 30 s. */
        fun waitFor(n: Int): Long = (FIRST_WAIT_MS + (n - 1) * 5_000L).coerceAtMost(LAST_WAIT_MS)

        /** Whether the library's [text] says Advanced Data Protection is on. */
        fun isAdp(text: String): Boolean =
            text.contains("Advanced Data Protection", ignoreCase = true) || text.contains("walrus", ignoreCase = true)

        private fun largeStack(): CoroutineContext =
            Executors.newSingleThreadExecutor { r -> Thread(null, r, "cloud-import", STACK_BYTES).apply { isDaemon = true } }.asCoroutineDispatcher()

        /** Where [window] ("month", "year", "all") reaches back to, from [now]; 0 for all. */
        fun sinceOf(window: String, now: Long): Long = when (window) {
            WINDOW_MONTH -> now - 31L * 24 * 60 * 60 * 1000
            WINDOW_ALL -> 0L
            else -> now - 366L * 24 * 60 * 60 * 1000
        }

        const val WINDOW_MONTH = "month"
        const val WINDOW_YEAR = "year"
        const val WINDOW_ALL = "all"
    }
}
