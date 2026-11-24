package com.lightdecoder.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.lightdecoder.imessage.IMessageAddress
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A name someone else's phone wrote for itself (a shared name and photo's), as it may be shown: no
 * control characters, no format characters (nothing turned around, nothing invisible), no line or
 * paragraph separators, spaces run together, [MAX_CHARS] characters at most. Pure.
 */
object SharedNames {
    /** The most characters a name may be shown with. */
    const val MAX_CHARS = 64

    /** The most characters of a name read off the wire before it is cleaned. */
    const val MAX_RAW = 256

    fun clean(raw: String): String {
        val sb = StringBuilder()
        var space = false
        for (ch in raw.take(MAX_RAW)) {
            val type = Character.getType(ch)
            val drop = ch.isISOControl() || type == Character.FORMAT.toInt() || type == Character.LINE_SEPARATOR.toInt() ||
                type == Character.PARAGRAPH_SEPARATOR.toInt() || type == Character.PRIVATE_USE.toInt() || type == Character.UNASSIGNED.toInt() ||
                ch == '�'
            if (drop) continue
            if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)) {
                space = sb.isNotEmpty()
                continue
            }
            if (space) sb.append(' ')
            space = false
            sb.append(ch)
        }
        // Unpaired halves of a character pair go too.
        val paired = StringBuilder()
        var i = 0
        while (i < sb.length) {
            val c = sb[i]
            when {
                Character.isHighSurrogate(c) && i + 1 < sb.length && Character.isLowSurrogate(sb[i + 1]) -> {
                    paired.append(c).append(sb[i + 1])
                    i += 2
                    continue
                }
                Character.isSurrogate(c) -> Unit
                else -> paired.append(c)
            }
            i++
        }
        val s = paired.toString()
        if (s.length <= MAX_CHARS) return s
        val end = if (Character.isHighSurrogate(s[MAX_CHARS - 1])) MAX_CHARS - 1 else MAX_CHARS
        return s.substring(0, end).trimEnd()
    }

    /** The name to show for a shared profile: its full name, else its first and last. */
    fun shown(name: String, first: String, last: String): String =
        clean(name).ifEmpty { clean(listOf(first, last).filter { it.isNotBlank() }.joinToString(" ")) }
}

/**
 * The rules an offer of a name and photo is taken by (B6.1), pure: only for a chat with that one
 * person that already is here; [PER_SENDER] a day from one person and [GLOBAL] a day in all; the same
 * record again is not fetched again.
 */
object SharedProfileRules {
    const val PER_SENDER = 3
    const val GLOBAL = 20
    const val DAY_MS = 24 * 60 * 60 * 1000L

    enum class Decision { FETCH, NO_CHAT, LIMITED, REPEAT }

    fun decide(chatExists: Boolean, senderTimes: List<Long>, allTimes: List<Long>, seen: Boolean, now: Long): Decision = when {
        !chatExists -> Decision.NO_CHAT
        seen -> Decision.REPEAT
        senderTimes.count { now - it in 0 until DAY_MS } >= PER_SENDER -> Decision.LIMITED
        allTimes.count { now - it in 0 until DAY_MS } >= GLOBAL -> Decision.LIMITED
        else -> Decision.FETCH
    }

    /**
     * What a chat with one person is called with a shared [shared] name: the chat's own name ([stored],
     * a contact's name or one the user gave it) when it has one; the shared name only where it would
     * otherwise be just the person's [address] (or nothing).
     */
    fun title(stored: String, address: String, shared: String?): String {
        if (shared.isNullOrBlank()) return stored
        val own = stored.isNotBlank() && !IMessageAddress.same(stored, address) && stored.trim() != address.removePrefix("tel:").removePrefix("mailto:")
        return if (own) stored else shared
    }
}

/**
 * Names and photos people shared (B6.1), in the main process: offers waiting for the user's "Update"
 * or "Not Now", and those the user took, by the person's address. In `files/imessage-profiles/`
 * (app-private, never backed up, outside the ":imessage" process's folder), [MAX_PEOPLE] people and
 * [MAX_OFFERS] offers at most. A name taken is shown only where a chat has no name of its own
 * ([SharedProfileRules.title]); a photo only where there is no contact picture. Chat deletion and a
 * sign-out remove what is kept ([forget], [clear]). The user's answers to "Share your name and
 * photo?" are kept per chat here too ([shareAnswered]).
 *
 * Never logged: no name or address of it goes to any log.
 */
class SharedProfiles internal constructor(val dir: File) {

    data class Person(val name: String, val photo: String, val atMs: Long)
    data class Offer(val sender: String, val name: String, val photo: String, val record: String, val atMs: Long)

    private val lock = Any()
    private val file = File(dir, "profiles.json")

    private var people = LinkedHashMap<String, Person>()
    private var offers = LinkedHashMap<String, Offer>()
    private var times = LinkedHashMap<String, MutableList<Long>>()
    private var seen = ArrayList<String>()
    private var answered = LinkedHashSet<String>()

    init {
        load()
    }

    /** Where an offer's photo is fetched to: a new name under [dir], `incoming-`, chosen here, never from anything remote. */
    fun incomingFile(): File = File(dir, INCOMING_PREFIX + UUID.randomUUID().toString() + ".img")

    /** Whether an offer from [sender] with record [record] is fetched now ([SharedProfileRules]); counted when it is. */
    fun take(sender: String, record: String, chatExists: Boolean, now: Long = System.currentTimeMillis()): SharedProfileRules.Decision = synchronized(lock) {
        val key = keyOf(sender) ?: return SharedProfileRules.Decision.NO_CHAT
        val all = times.values.flatten()
        val d = SharedProfileRules.decide(chatExists, times[key].orEmpty(), all, record in seen, now)
        if (d == SharedProfileRules.Decision.FETCH) {
            times.getOrPut(key) { ArrayList() }.apply { add(now); retainAll { now - it in 0 until SharedProfileRules.DAY_MS } }
            seen.add(record)
            while (seen.size > MAX_SEEN) seen.removeAt(0)
            while (times.size > MAX_PEOPLE) times.remove(times.keys.first())
            save()
        }
        d
    }

    /**
     * An offer fetched, for chat [conversationId] from [sender]: its name cleaned, its photo (fetched
     * to [incoming]) decoded with bounds and kept as a small JPEG (or none). Replaces an earlier offer
     * in that chat. Null when there is neither a name nor a photo.
     */
    fun offered(conversationId: String, sender: String, record: String, name: String, incoming: File?, now: Long = System.currentTimeMillis()): Offer? {
        val photo = incoming?.let { keepPhoto(it) }.orEmpty()
        incoming?.delete()
        val clean = SharedNames.clean(name)
        if (clean.isEmpty() && photo.isEmpty()) return null
        return synchronized(lock) {
            offers.remove(conversationId)?.let { if (it.photo != photo) deletePhoto(it.photo) }
            val o = Offer(sender, clean, photo, record, now)
            offers[conversationId] = o
            while (offers.size > MAX_OFFERS) offers.remove(offers.keys.first())?.let { deletePhoto(it.photo) }
            save()
            o
        }
    }

    fun offerFor(conversationId: String): Offer? = synchronized(lock) { offers[conversationId] }

    /** "Update": the offer's name and photo are this person's from now on. */
    fun accept(conversationId: String): Boolean = synchronized(lock) {
        val o = offers.remove(conversationId) ?: return false
        val key = keyOf(o.sender) ?: return false
        people.remove(key)?.let { if (it.photo != o.photo) deletePhoto(it.photo) }
        people[key] = Person(o.name, o.photo, o.atMs)
        while (people.size > MAX_PEOPLE) people.remove(people.keys.first())?.let { deletePhoto(it.photo) }
        save()
        true
    }

    /** "Not Now": the offer goes. */
    fun dismiss(conversationId: String) = synchronized(lock) {
        offers.remove(conversationId)?.let { deletePhoto(it.photo) }
        save()
    }

    fun nameFor(address: String): String? = synchronized(lock) { keyOf(address)?.let { people[it] }?.name?.takeIf { it.isNotBlank() } }

    fun photoFor(address: String): File? = synchronized(lock) {
        keyOf(address)?.let { people[it] }?.photo?.takeIf { it.isNotEmpty() }?.let { File(dir, it) }?.takeIf { it.isFile }
    }

    /** "Share your name and photo with …?" was answered for [conversationId] (shared, or "Not Now"). */
    fun shareAnswered(conversationId: String): Boolean = synchronized(lock) { conversationId in answered }

    fun answerShare(conversationId: String) = synchronized(lock) {
        answered.add(conversationId)
        while (answered.size > MAX_ANSWERS) answered.remove(answered.first())
        save()
    }

    /** A chat with [address] deleted: what it brought goes. */
    fun forget(conversationId: String, address: String?) = synchronized(lock) {
        offers.remove(conversationId)?.let { deletePhoto(it.photo) }
        answered.remove(conversationId)
        address?.let(::keyOf)?.let { key ->
            people.remove(key)?.let { deletePhoto(it.photo) }
            // Nor when they last offered one (their address, kept for the limit).
            times.remove(key)
        }
        save()
    }

    /** Signed out: every name, photo and offer goes. */
    fun clear() = synchronized(lock) {
        people.clear()
        offers.clear()
        times.clear()
        seen.clear()
        answered.clear()
        dir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Whether nothing is kept (a sign-out has nothing to remove): no one's name, photo or offer, no
     * answer, nor when someone last offered one or which records were fetched, and no file of it.
     */
    fun isEmpty(): Boolean = synchronized(lock) {
        people.isEmpty() && offers.isEmpty() && answered.isEmpty() && times.isEmpty() && seen.isEmpty() && dir.listFiles().isNullOrEmpty()
    }

    private fun keepPhoto(incoming: File): String? {
        if (!incoming.isFile || incoming.length() <= 0 || incoming.length() > MAX_INCOMING_BYTES) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(incoming.path, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) return null
            var sample = 1
            while (minOf(w, h) / (sample * 2) >= PHOTO_SIDE) sample *= 2
            val decoded = BitmapFactory.decodeFile(incoming.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val edge = minOf(decoded.width, decoded.height)
            val square = Bitmap.createBitmap(decoded, (decoded.width - edge) / 2, (decoded.height - edge) / 2, edge, edge)
            val out = if (edge > PHOTO_SIDE) Bitmap.createScaledBitmap(square, PHOTO_SIDE, PHOTO_SIDE, true) else square
            val name = PHOTO_PREFIX + UUID.randomUUID().toString() + ".jpg"
            dir.mkdirs()
            FileOutputStream(File(dir, name)).use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            name
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: RuntimeException) {
            null
        } catch (e: java.io.IOException) {
            null
        }
    }

    private fun deletePhoto(name: String) {
        if (name.isNotEmpty() && name.startsWith(PHOTO_PREFIX) && '/' !in name) File(dir, name).delete()
    }

    private fun keyOf(address: String): String? = IMessageAddress.key(address)

    private fun load() {
        val o = try {
            file.takeIf { it.isFile && it.length() <= MAX_FILE_BYTES }?.readText()?.let(::JSONObject) ?: return
        } catch (e: JSONException) {
            return
        } catch (e: java.io.IOException) {
            return
        }
        o.optJSONObject("people")?.let { p ->
            for (k in p.keys().asSequence().take(MAX_PEOPLE)) {
                val v = p.optJSONObject(k) ?: continue
                people[k] = Person(SharedNames.clean(v.optString("name")), photoName(v.optString("photo")), v.optLong("at"))
            }
        }
        o.optJSONObject("offers")?.let { p ->
            for (k in p.keys().asSequence().take(MAX_OFFERS)) {
                val v = p.optJSONObject(k) ?: continue
                offers[k] = Offer(v.optString("sender"), SharedNames.clean(v.optString("name")), photoName(v.optString("photo")), v.optString("record"), v.optLong("at"))
            }
        }
        o.optJSONObject("times")?.let { p ->
            for (k in p.keys().asSequence().take(MAX_PEOPLE)) {
                val a = p.optJSONArray(k) ?: continue
                times[k] = (0 until minOf(a.length(), SharedProfileRules.GLOBAL)).map { a.optLong(it) }.toMutableList()
            }
        }
        o.optJSONArray("seen")?.let { a -> for (i in 0 until minOf(a.length(), MAX_SEEN)) seen.add(a.optString(i)) }
        o.optJSONArray("answered")?.let { a -> for (i in 0 until minOf(a.length(), MAX_ANSWERS)) answered.add(a.optString(i)) }
    }

    private fun photoName(n: String): String = n.takeIf { it.startsWith(PHOTO_PREFIX) && '/' !in it && it.length <= 64 }.orEmpty()

    private fun save() {
        val o = JSONObject()
            .put("people", JSONObject().apply { people.forEach { (k, v) -> put(k, JSONObject().put("name", v.name).put("photo", v.photo).put("at", v.atMs)) } })
            .put("offers", JSONObject().apply { offers.forEach { (k, v) -> put(k, JSONObject().put("sender", v.sender).put("name", v.name).put("photo", v.photo).put("record", v.record).put("at", v.atMs)) } })
            .put("times", JSONObject().apply { times.forEach { (k, v) -> put(k, JSONArray(v)) } })
            .put("seen", JSONArray(seen))
            .put("answered", JSONArray(answered.toList()))
        try {
            dir.mkdirs()
            val tmp = File(dir, "profiles.json.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: java.io.IOException) {
            // Kept in memory; the next change writes it again.
        }
    }

    companion object {
        const val MAX_PEOPLE = 500
        const val MAX_OFFERS = 50
        private const val MAX_SEEN = 500
        private const val MAX_ANSWERS = 500
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024
        const val MAX_INCOMING_BYTES = 1024L * 1024
        private const val MAX_PIXELS = 16_000_000L
        private const val PHOTO_SIDE = 512
        const val INCOMING_PREFIX = "incoming-"
        private const val PHOTO_PREFIX = "photo-"

        fun dirOf(context: Context) = File(context.filesDir, "imessage-profiles")

        @Volatile
        private var instance: SharedProfiles? = null

        fun get(context: Context): SharedProfiles =
            instance ?: synchronized(this) { instance ?: SharedProfiles(dirOf(context.applicationContext)).also { instance = it } }

        /** Tests: a fresh one. */
        internal fun resetForTesting() {
            instance = null
        }
    }
}
