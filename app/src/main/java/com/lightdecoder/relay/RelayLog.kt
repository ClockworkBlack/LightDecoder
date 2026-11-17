package com.lightdecoder.relay

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One short, on-device record of what a link did, kept in a file of its own
 * under app-private storage: the newest [maxLines] lines, each with the time
 * it was added. Each link has one ([RelayLog], [com.lightdecoder.wa.WaLog]).
 *
 * What may be written is the caller's rule to keep: kinds and outcomes only.
 */
internal class ActivityLog(private val fileName: String, private val maxLines: Int = 120) {
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private var loaded = false

    fun add(context: Context, line: String) = synchronized(lock) {
        load(context)
        val stamp = SimpleDateFormat("MMM d HH:mm:ss", Locale.US).format(Date())
        lines.addLast("$stamp  $line")
        while (lines.size > maxLines) lines.removeFirst()
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
            if (f.exists()) f.readLines().takeLast(maxLines).forEach { lines.addLast(it) }
        }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, fileName)
}

/**
 * A short, on-device record of what the Google Messages link did, so that
 * "it stopped working" can be diagnosed from a screenshot of the status screen.
 *
 * It records only kinds and outcomes ("connected", "send failed: not_connected",
 * "imported 3 conversations"). Message text, names, phone numbers, the pairing
 * emoji, cookies and keys are never written.
 */
object RelayLog {
    private val log = ActivityLog("relay-log.txt")

    fun add(context: Context, line: String) = log.add(context, line)

    /** Newest first. */
    fun read(context: Context): List<String> = log.read(context)

    fun clear(context: Context) = log.clear(context)
}
