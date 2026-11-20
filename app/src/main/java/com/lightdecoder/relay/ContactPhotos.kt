package com.lightdecoder.relay

import android.content.Context
import android.util.Log
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.ParticipantRow
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageAddress
import com.lightdecoder.imessage.direct.GroupPhotoImages
import java.io.File
import java.security.MessageDigest
import org.json.JSONException
import org.json.JSONObject

/**
 * People's pictures as Google Messages has them: the thumbnails of its conversations' participants
 * ([RelayBridge.participantThumbnails]), fetched from the paired phone through the link, so no access
 * to this phone's contacts is needed. Kept as small square JPEGs ([GroupPhotoImages]) in the app's
 * own storage, by the person's number as a whole international number ([IMessageAddress.key]):
 * a person's iMessage chat shows the same picture as their Messages conversation, and two people
 * whose numbers only end the same never share one.
 *
 * Each person is asked about again after [REFRESH_MS] (a picture changed on the phone), and one with
 * no picture is remembered as such meanwhile. Main process only; the index is read once and written
 * through.
 */
class ContactPhotos private constructor(context: Context) {

    private val app = context.applicationContext
    private val dir = File(app.filesDir, DIR)
    private val indexFile = File(dir, INDEX)

    /** What is known of one person: their picture's file ("" for none), and when they were last asked about. */
    private data class Entry(val path: String, val askedAt: Long)

    private var loaded: MutableMap<String, Entry>? = null

    @Synchronized
    private fun entries(): MutableMap<String, Entry> = loaded ?: read().also { loaded = it }

    /** Whether no one's picture is kept (then nothing needs looking up). */
    @Synchronized
    fun isEmpty(): Boolean = entries().values.none { it.path.isNotEmpty() }

    /** The picture of whoever has the number (or email address) [address], if one is kept. */
    @Synchronized
    fun forAddress(address: String): File? {
        val key = IMessageAddress.key(address) ?: return null
        val path = entries()[key]?.path?.takeIf { it.isNotEmpty() } ?: return null
        return File(path).takeIf { it.isFile }
    }

    /** [p]'s picture, by their number, if one is kept. */
    fun forPerson(p: ParticipantRow): File? = forAddress(p.number.ifBlank { p.formattedNumber }.ifBlank { p.id })

    /** The picture of the one other person of conversation [conversationId] of [service], if one is kept. Reads the archive: not on the main thread. */
    fun forConversation(service: ChatService, conversationId: String): File? {
        if (isEmpty()) return null
        val people = runCatching { ArchiveRepository.get(app, service).participants(conversationId) }.getOrDefault(emptyList()).filter { !it.isMe }
        return people.singleOrNull()?.let(::forPerson)
    }

    /**
     * Which of [people] (Google Messages' participant id -> their number) are due to be asked about:
     * never asked, or asked more than [REFRESH_MS] before [now]. Only people with a number.
     */
    @Synchronized
    fun due(people: Map<String, String>, now: Long): Map<String, String> {
        val all = entries()
        return people.filter { (_, number) ->
            val key = IMessageAddress.key(number) ?: return@filter false
            val known = all[key] ?: return@filter true
            now - known.askedAt > REFRESH_MS || known.askedAt > now
        }
    }

    /**
     * What Google Messages answered for [asked] (participant id -> number), at [now]: each picture it
     * had kept as a square JPEG (its old one replaced), and the others remembered as having none.
     * True when a picture shown changed.
     */
    @Synchronized
    fun store(asked: Map<String, String>, thumbnails: List<ThumbnailDto>, now: Long): Boolean {
        val all = entries()
        val pictures = thumbnails.associateBy { it.participantId }
        var changed = false
        for ((id, number) in asked) {
            val key = IMessageAddress.key(number) ?: continue
            val old = all[key]
            val bytes = pictures[id]?.data?.takeIf { it.isNotEmpty() && it.size <= MAX_THUMBNAIL_BYTES }
            val path = bytes?.let { write(key, it) }.orEmpty()
            if (path.isEmpty() && bytes != null && old != null && old.path.isNotEmpty()) {
                // Unreadable this time: the picture kept stays.
                all[key] = old.copy(askedAt = now)
                continue
            }
            all[key] = Entry(path, now)
            if (old?.path.orEmpty() != path) {
                changed = true
                old?.path?.takeIf { it.isNotEmpty() && it != path }?.let { File(it).delete() }
            }
        }
        save(all)
        return changed
    }

    /** Forgets every picture (the archive they came with was deleted). */
    @Synchronized
    fun clear() {
        dir.deleteRecursively()
        loaded = HashMap()
    }

    /** [bytes] as the square JPEG of the person [key], named after its content (so a changed picture is a new file); its path, or null. */
    private fun write(key: String, bytes: ByteArray): String? {
        val name = hex(digest(key.toByteArray(Charsets.UTF_8))).take(16) + "-" + hex(digest(bytes)).take(12) + ".jpg"
        val out = File(dir, name)
        if (out.isFile && out.length() > 0) return out.path
        val tmp = File(dir, "$name.tmp")
        return try {
            if (!GroupPhotoImages.write(bytes, tmp, SIDE)) return null
            if (!tmp.renameTo(out)) return null
            out.path
        } finally {
            tmp.delete()
        }
    }

    private fun read(): MutableMap<String, Entry> = try {
        val o = if (indexFile.isFile) JSONObject(indexFile.readText()) else JSONObject()
        val out = HashMap<String, Entry>()
        for (key in o.keys()) {
            val e = o.optJSONObject(key) ?: continue
            out[key] = Entry(e.optString(KEY_PATH), e.optLong(KEY_ASKED))
        }
        out
    } catch (e: JSONException) {
        HashMap()
    } catch (e: java.io.IOException) {
        HashMap()
    }

    private fun save(all: Map<String, Entry>) {
        try {
            dir.mkdirs()
            val o = JSONObject()
            for ((key, e) in all) o.put(key, JSONObject().put(KEY_PATH, e.path).put(KEY_ASKED, e.askedAt))
            val tmp = File(dir, "$INDEX.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(indexFile)) {
                indexFile.writeText(o.toString())
                tmp.delete()
            }
        } catch (e: java.io.IOException) {
            Log.w(TAG, "contact pictures' index not saved", e)
        }
    }

    companion object {
        private const val TAG = "ContactPhotos"
        private const val DIR = "contact-photos"
        private const val INDEX = "index.json"
        private const val KEY_PATH = "path"
        private const val KEY_ASKED = "asked"

        /** A person is asked about again after a week. */
        const val REFRESH_MS = 7L * 24 * 60 * 60 * 1000

        /** A picture's side here: enough for the details' big picture. */
        const val SIDE = 256

        /** A thumbnail larger than this is not read (Google's are a few KB). */
        private const val MAX_THUMBNAIL_BYTES = 2 * 1024 * 1024

        private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        @Volatile
        private var instance: ContactPhotos? = null

        /** The app's (one per process; a new one for another application object, as each test has). */
        fun get(context: Context): ContactPhotos {
            val app = context.applicationContext
            instance?.takeIf { it.app === app }?.let { return it }
            return synchronized(this) { instance?.takeIf { it.app === app } ?: ContactPhotos(app).also { instance = it } }
        }

        /** Tests: the next [get] reads the files again. */
        internal fun resetForTesting() = synchronized(this) { instance = null }
    }
}
