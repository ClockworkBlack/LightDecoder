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

    /** True if a picture with these exact bytes is stored (see [InboxItem.digest]). */
    fun containsDigest(digest: String): Boolean = synchronized(lock) {
        digest.isNotEmpty() && readIndex().any { it.digest == digest }
    }

    /**
     * Marks a message as handled without storing it (for media we chose to
     * skip), so a notification that is posted again is not read again.
     */
    fun markSeen(id: String) = synchronized(lock) { remember(id) }

    /**
     * Stores an item with its bytes. Returns false if it was already there (same
     * id, or the same picture caught another way: same non-empty digest) or
     * could not be written.
     */
    fun add(item: InboxItem, originalBytes: ByteArray, preview: Bitmap): Boolean = synchronized(lock) {
        val items = readIndex().toMutableList()
        if (items.any { it.id == item.id || (item.digest.isNotEmpty() && it.digest == item.digest) }) {
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

        /** The store for this app's files directory (tests get a fresh one per test). */
        fun get(context: Context): InboxStore {
            val dir = File(context.applicationContext.filesDir, "inbox")
            instance?.let { if (it.dir == dir) return it }
            return synchronized(this) {
                instance?.takeIf { it.dir == dir }
                    ?: InboxStore(dir.apply { mkdirs() }).also { instance = it }
            }
        }

        /** SHA-256 of a picture's bytes, as stored in [InboxItem.digest]. */
        fun digestOf(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(32)

        /**
         * A stable id for one message attachment: the same message posted again
         * (notifications are re-posted whenever a chat updates) gets the same id.
         */
        fun idFor(appPackage: String, conversation: String, sender: String, timestampMs: Long, uri: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$appPackage\u0000$conversation\u0000$sender\u0000$timestampMs\u0000$uri".toByteArray())
            return digest.take(12).joinToString("") { "%02x".format(it) }
        }

        /**
         * The id for one part of a message that came over the Google Messages
         * link. Conversation, message and part ids are only unique on the phone
         * they came from, so the phone's id ([phoneId], the archive's owner;
         * empty if not known) is part of it: a message of another phone with
         * the same ids is not taken for one already seen. The same picture is
         * still kept once, by its digest.
         */
        fun relayIdFor(phoneId: String, conversationId: String, messageId: String, partId: String): String =
            idFor("${InboxItem.SOURCE_RELAY}\u0000$phoneId", conversationId, messageId, 0, partId)
    }
}
