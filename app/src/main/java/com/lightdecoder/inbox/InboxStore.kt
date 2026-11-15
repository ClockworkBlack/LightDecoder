package com.lightdecoder.inbox

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * On-device store for caught stickers. Layout under filesDir/inbox/:
 *
 *   index.json          the list of items, newest first
 *   <id>.<ext>          the original media bytes, exactly as received
 *   <id>.png            a decoded PNG of the first frame, for the grid
 *
 * Everything is app-private and nothing is uploaded anywhere. All access goes
 * through one lock: writes come from the listener's worker thread and reads
 * from the UI.
 */
class InboxStore private constructor(private val dir: File) {

    private val indexFile = File(dir, "index.json")
    private val lock = Any()

    /** Keys of messages already handled in this process, oldest first. */
    private val seen = LinkedHashSet<String>()

    fun originalFile(item: InboxItem) = File(dir, "${item.id}.${item.originalExt}")
    fun pngFile(item: InboxItem) = File(dir, "${item.id}.png")

    fun list(): List<InboxItem> = synchronized(lock) { readIndex() }

    /** True if this message was already stored, now or in an earlier run. */
    fun contains(id: String): Boolean = synchronized(lock) {
        id in seen || readIndex().any { it.id == id }
    }

    /**
     * Marks a message as handled without storing it (for media we chose to
     * skip), so a notification that is posted again is not read again.
     */
    fun markSeen(id: String) = synchronized(lock) { remember(id) }

    /** Stores an item with its bytes. Returns false if it was already there or could not be written. */
    fun add(item: InboxItem, originalBytes: ByteArray, preview: Bitmap): Boolean = synchronized(lock) {
        val items = readIndex().toMutableList()
        if (items.any { it.id == item.id }) {
            remember(item.id)
            return false
        }
        try {
            FileOutputStream(originalFile(item)).use { it.write(originalBytes) }
            FileOutputStream(pngFile(item)).use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) {
            originalFile(item).delete()
            pngFile(item).delete()
            return false
        }
        items.add(0, item)
        items.drop(MAX_ITEMS).forEach {
            originalFile(it).delete()
            pngFile(it).delete()
        }
        writeIndex(items.take(MAX_ITEMS))
        remember(item.id)
        true
    }

    fun delete(item: InboxItem) = synchronized(lock) {
        originalFile(item).delete()
        pngFile(item).delete()
        writeIndex(readIndex().filterNot { it.id == item.id })
    }

    fun clear() = synchronized(lock) {
        dir.listFiles()?.forEach { it.delete() }
        seen.clear()
    }

    private fun remember(id: String) {
        seen.add(id)
        while (seen.size > MAX_SEEN) {
            val it = seen.iterator()
            it.next()
            it.remove()
        }
    }

    private fun readIndex(): List<InboxItem> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).map { InboxItem.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeIndex(items: List<InboxItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        // Write-then-rename, so a crash mid-write cannot leave a broken index.
        val tmp = File(dir, "index.json.tmp")
        try {
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(indexFile)) {
                indexFile.writeText(arr.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
        }
    }

    companion object {
        private const val MAX_ITEMS = 500
        private const val MAX_SEEN = 512

        @Volatile
        private var instance: InboxStore? = null

        fun get(context: Context): InboxStore =
            instance ?: synchronized(this) {
                instance ?: InboxStore(File(context.applicationContext.filesDir, "inbox").apply { mkdirs() })
                    .also { instance = it }
            }

        /**
         * A stable id for one message attachment: the same message posted again
         * (notifications are re-posted whenever a chat updates) gets the same id.
         */
        fun idFor(appPackage: String, conversation: String, sender: String, timestampMs: Long, uri: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$appPackage\u0000$conversation\u0000$sender\u0000$timestampMs\u0000$uri".toByteArray())
            return digest.take(12).joinToString("") { "%02x".format(it) }
        }
    }
}
