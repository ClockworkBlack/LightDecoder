package com.lightdecoder.chat

import android.content.Context
import com.lightdecoder.archive.MediaCache
import com.lightdecoder.imessage.IMessageAddress
import com.lightdecoder.imessage.direct.Handles
import java.io.File
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

/**
 * The user's own block list ("Blocked Contacts", as an iPhone's Settings > Messages has it), main
 * process. It is LightDecoder's alone: no Apple-side or iCloud list is read or written (the library
 * has none), and Android's own block list is not reachable for an app that is neither the dialer nor
 * the SMS app.
 *
 * - Kept in `files/blocked.json`, app-private (no backup), as normalized addresses: a phone number
 *   as its whole international number ("+15551234567", through [IMessageAddress] and the calling
 *   codes), an email address in lower case. [MAX_ENTRIES] at most.
 * - [isBlocked] compares whole addresses ([IMessageAddress.sameKey]): "+55 11 …" and "+55 21 …"
 *   are two people, as for every other address comparison of the archive.
 * - The user's own addresses ([own]) can never be blocked. The main process knows them from its own
 *   iMessage archive (the address each chat is carried on), never from the ":imessage" process's
 *   files; before any is stored, the ":imessage" process still never takes its own addresses for
 *   blocked ones, and nothing the user sent is ever dropped.
 * - [entries] is a StateFlow: the iMessage link pushes a salted-hash copy of it to the ":imessage"
 *   process on every change ([mirror]), where FaceTime is enforced without a round trip.
 *
 * Never logged: no address of it goes to any log.
 */
class BlockList internal constructor(
    private val file: File,
    /** The user's own addresses as far as they are known (any form). */
    private val own: () -> Collection<String> = { emptyList() },
) {

    private val lock = Any()
    private val _entries = MutableStateFlow(load())

    /** The blocked addresses, normalized ([normalize]), in the order they were added. */
    val entries: StateFlow<List<String>> = _entries

    /**
     * Told after every change. Held weakly: whoever listens keeps its own listener (so a listener
     * never keeps its owner alive), and one gone is dropped.
     */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<java.lang.ref.WeakReference<() -> Unit>>()

    /** [listener] is told after every change, as long as its owner holds it. */
    fun addListener(listener: () -> Unit) {
        listeners.removeAll { it.get() == null }
        listeners += java.lang.ref.WeakReference(listener)
    }

    private fun changed() {
        for (ref in listeners) {
            val l = ref.get()
            if (l == null) listeners.remove(ref) else runCatching { l() }
        }
    }

    /** What [block] did. */
    enum class Added { ADDED, ALREADY, OWN, INVALID, FULL }

    /** Whether [address] (any form: "tel:+1…", "+1 (555) …", "a@b.c", "mailto:A@B.c") is blocked. Never throws. */
    fun isBlocked(address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        val list = _entries.value
        if (list.isEmpty()) return false
        val key = runCatching { normalize(address) }.getOrNull() ?: return false
        if (key in list) return true
        return list.any { IMessageAddress.sameKey(it, key) }
    }

    /** Whether any of [addresses] is blocked. */
    fun anyBlocked(addresses: Collection<String>): Boolean = _entries.value.isNotEmpty() && addresses.any(::isBlocked)

    /** Blocks [address] (a number or an email address, as typed or as stored). */
    fun block(address: String): Added = synchronized(lock) {
        val key = normalize(address) ?: return Added.INVALID
        if (isOwn(key)) return Added.OWN
        val list = _entries.value
        if (list.any { it == key || IMessageAddress.sameKey(it, key) }) return Added.ALREADY
        if (list.size >= MAX_ENTRIES) return Added.FULL
        val next = list + key
        save(next)
        _entries.value = next
        Added.ADDED
    }.also { if (it == Added.ADDED) changed() }

    /** Unblocks [address]: every stored form it is the same address as. True when one was there. */
    fun unblock(address: String): Boolean = synchronized(lock) {
        val key = normalize(address) ?: return false
        val list = _entries.value
        val next = list.filterNot { it == key || IMessageAddress.sameKey(it, key) }
        if (next.size == list.size) return false
        save(next)
        _entries.value = next
        true
    }.also { if (it) changed() }

    private fun isOwn(key: String): Boolean = runCatching { own() }.getOrDefault(emptyList()).any { mine ->
        val k = runCatching { normalize(mine) }.getOrNull()
        k != null && (k == key || IMessageAddress.sameKey(k, key))
    }

    private fun load(): List<String> = try {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) {
            emptyList()
        } else {
            val a = JSONArray(file.readText())
            (0 until minOf(a.length(), MAX_ENTRIES)).mapNotNull { a.optString(it).takeIf(::wellFormed) }.distinct()
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(list: List<String>) {
        file.parentFile?.mkdirs()
        MediaCache.writeAtomically(file, JSONArray(list).toString().toByteArray(Charsets.UTF_8))
    }

    /** The salted-hash copy of the list for the ":imessage" process ([Mirror]): a new salt every time. */
    fun mirror(random: SecureRandom = SecureRandom()): Mirror {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val hashes = _entries.value.filter(::mirrorable).map { hmac(salt, it) }.distinct()
        return Mirror(hex(salt), hashes)
    }

    /** What the ":imessage" process is given of the list: a salt and HMAC-SHA256(salt, address) of each, in hex. */
    data class Mirror(val salt: String, val hashes: List<String>)

    companion object {
        /** The most addresses blocked. */
        const val MAX_ENTRIES = 1_000

        private const val MAX_FILE_BYTES = 256 * 1024L
        const val SALT_BYTES = 16
        private const val FILE = "blocked.json"

        @Volatile
        private var instance: BlockList? = null

        /** The main process's one. */
        fun get(context: Context): BlockList = instance ?: synchronized(this) {
            instance ?: BlockList(File(context.applicationContext.filesDir, FILE)) {
                // The user's own addresses as the main process's own archive has them: the address each iMessage
                // chat is carried on (only ever one of the user's own, DirectArchive's defaultOutgoingId).
                com.lightdecoder.imessage.IMessageManager.get(context.applicationContext).repo.outgoingIds()
            }.also { instance = it }
        }

        internal fun installForTesting(list: BlockList?) = synchronized(this) { instance = list }

        /**
         * [address] as the list keeps it: a phone number's whole international number ("+15551234567";
         * one written without its country code read in this phone's country), an email address in
         * lower case; null for anything else.
         */
        fun normalize(address: String): String? {
            val raw = address.trim()
            // The scheme in whatever case ("MAILTO:", "Tel:").
            val text = SCHEME.find(raw)?.let { raw.substring(it.range.last + 1).trim() } ?: raw
            if (text.isEmpty() || text.length > MAX_ADDRESS_CHARS) return null
            val key = IMessageAddress.key(text) ?: return null
            if (key.contains('@')) return key.takeIf { EMAIL.matches(it) }
            if (key.startsWith(IMessageAddress.NATIONAL)) {
                // No country known: read as Handles reads what the user types (North America's when none is known).
                val read = runCatching { Handles.normalize(text) }.getOrNull()?.let(IMessageAddress::key)
                if (read != null && read.startsWith("+")) return read
            }
            return key
        }

        private val EMAIL = Regex("^[^@\\s:]{1,64}@[^@\\s:]{1,255}$")
        private val SCHEME = Regex("^(?i)(tel|mailto):")
        private const val MAX_ADDRESS_CHARS = 320

        private fun wellFormed(s: String): Boolean =
            s.isNotEmpty() && s.length <= MAX_ADDRESS_CHARS && (s.startsWith("+") || s.startsWith(IMessageAddress.NATIONAL) || EMAIL.matches(s))

        /** Only whole addresses go to the other process: a number without its country could match nothing there. */
        private fun mirrorable(key: String): Boolean = key.startsWith("+") || key.contains('@')

        /** HMAC-SHA256 of [key] under [salt], in lower-case hex (as [com.lightdecoder.imessage.facetime.BlockedCallers] checks it). */
        fun hmac(salt: ByteArray, key: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(salt, "HmacSHA256"))
            return hex(mac.doFinal(key.toByteArray(Charsets.UTF_8)))
        }

        fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) { for (b in bytes) append("%02x".format(b.toInt() and 0xff)) }
    }
}
