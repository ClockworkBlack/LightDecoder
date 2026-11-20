package com.lightdecoder.imessage.direct

import android.util.Log
import java.io.File
import java.io.IOException

/**
 * How often one of Apple's events (by its journal id) took the ":imessage" process down while it
 * was being read: a crash nothing can catch (in a native library, or memory running out there).
 * Apple's journal hands an event that was not acknowledged over again after each restart, so one
 * such event would take the process down again and again, and every iMessage after it with it.
 * Kept in [file] (the ":imessage" process's own) across restarts, so that an event that did so
 * [maxDeaths] times is skipped (acknowledged, not read) the next time it comes ([start] says so).
 *
 * Events are taken one at a time, in order ([DirectEngine]): one record is enough, the event being
 * taken now, from [start] until [done] (once it is acknowledged). Only its risky steps count
 * ([risky] to [ended]: reading it, and the library's receipt for it), not the wait for the main
 * process to store it, during which Android may stop the process for reasons of its own.
 *
 * Tested in ChatBackgroundsTest.
 */
internal class EventAttempts(private val file: File, private val maxDeaths: Int = MAX_DEATHS) {

    private var current: String? = null
    private var deaths = 0

    /**
     * Event [id] is taken now. False when it took the process down [maxDeaths] times already: it is
     * to be skipped (and still acknowledged, then [done]).
     */
    @Synchronized
    fun start(id: String): Boolean {
        val kept = read()
        current = id
        deaths = if (kept?.first == id) kept.second else 0
        // Another event's (one acknowledged since, or never handed over again): nothing to it now.
        if (kept != null && kept.first != id) file.delete()
        return deaths < maxDeaths
    }

    /** A step of the event's that can take the process down begins: counted as a death until [ended]. */
    @Synchronized
    fun risky() {
        current?.let { write(it, deaths + 1) }
    }

    /** That step is over, the process still standing: not counted. */
    @Synchronized
    fun ended() {
        val id = current ?: return
        if (deaths == 0) file.delete() else write(id, deaths)
    }

    /** The event is done with (acknowledged): nothing is held against it any more. */
    @Synchronized
    fun done() {
        if (current == null && !file.exists()) return
        current = null
        deaths = 0
        file.delete()
    }

    private fun read(): Pair<String, Int>? = try {
        if (!file.isFile) {
            null
        } else {
            val lines = file.readLines()
            val id = lines.getOrNull(0)?.takeIf { it.isNotEmpty() }
            val count = lines.getOrNull(1)?.toIntOrNull()
            if (id != null && count != null) id to count else null
        }
    } catch (e: IOException) {
        null
    }

    private fun write(id: String, count: Int) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText("$id\n$count\n")
            if (!tmp.renameTo(file)) {
                file.writeText("$id\n$count\n")
                tmp.delete()
            }
        } catch (e: IOException) {
            // Not counted: the event is read all the same.
            Log.w(TAG, "event attempt not kept", e)
        }
    }

    companion object {
        private const val TAG = "EventAttempts"

        /** An event whose reading took the process down this often is skipped the next time. */
        const val MAX_DEATHS = 2
    }
}
