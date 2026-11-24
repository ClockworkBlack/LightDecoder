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

    /** Signed out, or Focus status turned off: everything goes. */
    fun clear() = synchronized(lock) {
        states.clear()
        notifiedAnyway.clear()
        subscribedKeys.clear()
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
        for (k in o.keys().asSequence().take(MAX)) {
            val v = o.optJSONObject(k) ?: continue
            if (IMessageAddress.key(k) != k) continue
            states[k] = State(v.opt("s") == true, v.optLong("t"))
        }
    }

    private fun save() {
        val f = file ?: return
        val o = JSONObject()
        states.forEach { (k, v) -> o.put(k, JSONObject().put("s", v.silenced).put("t", v.atMs)) }
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

        @Volatile
        private var instance: FocusStates? = null

        fun get(context: Context): FocusStates =
            instance ?: synchronized(this) { instance ?: FocusStates(File(context.applicationContext.filesDir, "focus.json")).also { instance = it } }

        internal fun resetForTesting() {
            instance = null
        }
    }
}
