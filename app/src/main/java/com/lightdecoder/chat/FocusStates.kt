package com.lightdecoder.chat

import android.content.Context
import com.lightdecoder.imessage.IMessageAddress
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONException
import org.json.JSONObject

/**
 * Other people's Focus status (B6.3), in the main process: who has notifications silenced, as their
 * phones told it, for the handles this phone asked about ([subscribed]); everything else is
 * ignored. And when the user last pressed "Notify Anyway" in a chat with someone, for "Delivered
 * Quietly" ([deliveredQuietly]). Kept in memory and in `files/focus.json` ([MAX] people at most);
 * gone with a sign-out ([clear]). Never logged.
 */
class FocusStates internal constructor(private val file: File?) {

    data class State(val silenced: Boolean, val atMs: Long)

    private val lock = Any()
    private val states = LinkedHashMap<String, State>()
    private val notifiedAnyway = HashMap<String, Long>()
    private val subscribedKeys = HashSet<String>()

    /** When each one-to-one iMessage chat was last opened (by its id), [MAX] at most: whose Focus is asked about. */
    private val openedAt = LinkedHashMap<String, Long>()

    /** Bumped on every change, so a chat on screen shows it. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    init {
        load()
    }

    /** The handles asked about ([com.lightdecoder.imessage.direct.DirectWire.FOCUS_SUBSCRIBE]): only theirs are taken. */
    fun subscribed(handles: Collection<String>) = synchronized(lock) {
        subscribedKeys.clear()
        handles.mapNotNullTo(subscribedKeys) { IMessageAddress.key(it) }
    }

    /** [handle]'s Focus as of [atMs]: true when taken (asked about, and not older than what is known). */
    fun update(handle: String, silenced: Boolean, atMs: Long): Boolean = synchronized(lock) {
        val key = IMessageAddress.key(handle) ?: return false
        if (key !in subscribedKeys) return false
        val known = states[key]
        if (known != null && known.atMs > atMs) return false
        states.remove(key)
        states[key] = State(silenced, atMs)
        while (states.size > MAX) states.remove(states.keys.first())
        save()
        _changes.value++
        true
    }

    /** Whether [handle] has notifications silenced, as last told. */
    fun silenced(handle: String): Boolean = synchronized(lock) { IMessageAddress.key(handle)?.let { states[it] }?.silenced == true }

    /** The user pressed "Notify Anyway" in the chat with [handle] at [atMs]. */
    fun notifiedAnyway(handle: String, atMs: Long) = synchronized(lock) {
        IMessageAddress.key(handle)?.let { notifiedAnyway[it] = atMs }
        _changes.value++
    }

    /**
     * Whether a Delivered receipt from [handle] at [now] is "Delivered Quietly": their notifications
     * are silenced and no "Notify Anyway" went to them in the last [NOTIFY_ANYWAY_MS].
     */
    fun deliveredQuietly(handle: String, now: Long): Boolean = synchronized(lock) {
        val key = IMessageAddress.key(handle) ?: return false
        if (states[key]?.silenced != true) return false
        val last = notifiedAnyway[key] ?: return true
        now - last !in 0 until NOTIFY_ANYWAY_MS
    }

    /** The chat [conversationId] was opened at [atMs]. */
    fun opened(conversationId: String, atMs: Long) = synchronized(lock) {
        if (!openable(conversationId)) return
        openedAt.remove(conversationId)
        openedAt[conversationId] = atMs
        while (openedAt.size > MAX) openedAt.remove(openedAt.keys.first())
        save()
    }

    /** The chats opened at or after [sinceMs] (and not after [now]), the latest first. */
    fun openedSince(sinceMs: Long, now: Long): List<String> = synchronized(lock) {
        openedAt.entries.filter { it.value in sinceMs..now }.sortedByDescending { it.value }.map { it.key }
    }

    /** Whether nothing is kept (no state, no subscription, no file). */
    fun isEmpty(): Boolean = synchronized(lock) {
        states.isEmpty() && notifiedAnyway.isEmpty() && subscribedKeys.isEmpty() && openedAt.isEmpty() && file?.isFile != true
    }

    /** Signed out, or Focus status turned off: everything goes. */
    fun clear() = synchronized(lock) {
        states.clear()
        notifiedAnyway.clear()
        subscribedKeys.clear()
        openedAt.clear()
        file?.delete()
        _changes.value++
    }

    private fun load() {
        val f = file ?: return
        val o = try {
            f.takeIf { it.isFile && it.length() <= 256 * 1024 }?.readText()?.let(::JSONObject) ?: return
        } catch (e: JSONException) {
            return
        } catch (e: java.io.IOException) {
            return
        }
        o.optJSONObject(OPENED)?.let { c ->
            for (k in c.keys().asSequence().take(MAX)) {
                val t = (c.opt(k) as? Number)?.toLong() ?: continue
                if (openable(k) && t >= 0) openedAt[k] = t
            }
        }
        for (k in o.keys().asSequence().take(MAX)) {
            if (k == OPENED) continue
            val v = o.optJSONObject(k) ?: continue
            if (IMessageAddress.key(k) != k) continue
            states[k] = State(v.opt("s") == true, v.optLong("t"))
        }
    }

    private fun save() {
        val f = file ?: return
        val o = JSONObject()
        states.forEach { (k, v) -> o.put(k, JSONObject().put("s", v.silenced).put("t", v.atMs)) }
        if (openedAt.isNotEmpty()) o.put(OPENED, JSONObject().apply { openedAt.forEach { (k, t) -> put(k, t) } })
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) tmp.delete()
        } catch (e: java.io.IOException) {
            // Kept in memory.
        }
    }

    companion object {
        const val MAX = 200
        const val NOTIFY_ANYWAY_MS = 5 * 60 * 1000L
        private const val OPENED = "_opened"

        /** An iMessage chat's id, as kept for [opened]. */
        private fun openable(id: String): Boolean = id.startsWith("imsg:") && id.length <= 256 && id.none { it.isISOControl() }

        @Volatile
        private var instance: FocusStates? = null

        fun get(context: Context): FocusStates =
            instance ?: synchronized(this) { instance ?: FocusStates(File(context.applicationContext.filesDir, "focus.json")).also { instance = it } }

        internal fun resetForTesting() {
            instance = null
        }
    }
}
