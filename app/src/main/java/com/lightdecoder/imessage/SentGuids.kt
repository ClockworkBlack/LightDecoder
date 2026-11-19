package com.lightdecoder.imessage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The iMessages the user sent from LightDecoder that may still be unsent: their GUID, which part
 * of the message, and when they went out. Nothing else is written down: no text, no attachment,
 * and not who they went to. The recipients needed to address an unsend are kept only in memory
 * (in [ImessageEngine]), so after LightDecoder restarts an entry can be shown but not unsent.
 *
 * Entries older than [KEEP_MS] are dropped; Apple refuses an unsend after [UNSEND_WINDOW_MS].
 */
class SentGuids(private val file: File) {

    data class Entry(val guid: String, val part: Int, val sentAt: Long)

    constructor(context: Context) : this(File(context.filesDir, "imessage/sent_guids.json"))

    @Synchronized
    fun all(now: Long = System.currentTimeMillis()): List<Entry> = read().filter { now - it.sentAt < KEEP_MS }

    @Synchronized
    fun add(entry: Entry, now: Long = System.currentTimeMillis()) {
        write(read().filter { now - it.sentAt < KEEP_MS && it.guid != entry.guid } + entry)
    }

    @Synchronized
    fun remove(guid: String) {
        write(read().filter { it.guid != guid })
    }

    fun find(guid: String): Entry? = read().firstOrNull { it.guid == guid }

    private fun read(): List<Entry> {
        if (!file.isFile) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                Entry(it.getString("guid"), it.optInt("part", 0), it.getLong("sentAt"))
            }
        } catch (e: org.json.JSONException) {
            emptyList()
        }
    }

    private fun write(entries: List<Entry>) {
        file.parentFile?.mkdirs()
        val arr = JSONArray()
        entries.forEach { arr.put(JSONObject().put("guid", it.guid).put("part", it.part).put("sentAt", it.sentAt)) }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString())
            tmp.delete()
        }
    }

    companion object {
        /** Apple lets a message be unsent for 2 minutes after it was sent. */
        const val UNSEND_WINDOW_MS = 2 * 60 * 1000L

        /** How long an entry is kept at all (it is useless after the window, kept a little for the list). */
        const val KEEP_MS = 15 * 60 * 1000L

        /** Milliseconds left to unsend, or 0 when the window has passed. */
        fun remaining(sentAt: Long, now: Long): Long = (sentAt + UNSEND_WINDOW_MS - now).coerceAtLeast(0)

        fun canUnsend(sentAt: Long, now: Long): Boolean = remaining(sentAt, now) > 0
    }
}
