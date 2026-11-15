package com.lightdecoder.inbox

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short, on-device record of what the notification listener did, so that
 * "nothing showed up" can be diagnosed from a screenshot.
 *
 * It records only kinds and outcomes ("picture attachment, image/heic, saved").
 * Message text, sender names and conversation names are never written.
 */
object InboxLog {
    private const val MAX_LINES = 80
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private var loaded = false

    fun add(context: Context, line: String) = synchronized(lock) {
        load(context)
        val stamp = SimpleDateFormat("MMM d HH:mm:ss", Locale.US).format(Date())
        lines.addLast("$stamp  $line")
        while (lines.size > MAX_LINES) lines.removeFirst()
        runCatching { file(context).writeText(lines.joinToString("\n")) }
        Unit
    }

    /** Newest first. */
    fun read(context: Context): List<String> = synchronized(lock) {
        load(context)
        lines.reversed()
    }

    fun clear(context: Context) = synchronized(lock) {
        lines.clear()
        loaded = true
        file(context).delete()
        Unit
    }

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val f = file(context)
            if (f.exists()) f.readLines().takeLast(MAX_LINES).forEach { lines.addLast(it) }
        }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, "inbox-log.txt")
}
