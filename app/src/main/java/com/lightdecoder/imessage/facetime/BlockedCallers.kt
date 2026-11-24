package com.lightdecoder.imessage.facetime

import android.content.Context
import com.lightdecoder.archive.MediaCache
import com.lightdecoder.chat.BlockList
import com.lightdecoder.imessage.IMessageAddress
import com.lightdecoder.imessage.direct.DirectWire
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * The ":imessage" process's copy of the user's block list ([BlockList], main process), so that a
 * blocked person's FaceTime call is dropped here before anything else is done, with no question to
 * the main process and while it is not even running ([CallRules]'s `blocked`). It holds no address:
 * only a salt and HMAC-SHA256(salt, address) of each blocked one ([DirectWire.BLOCKLIST]), replaced
 * whole each time the main process sends it (with a new salt), in memory and in
 * `files/imessage/blocked-callers.json`. A caller's address ("tel:+15551234567", "mailto:a@b.c") is
 * matched by its own HMAC under the salt it holds.
 */
class BlockedCallers internal constructor(private val file: File?) {

    private class Held(val salt: ByteArray, val hashes: Set<String>)

    @Volatile
    private var held: Held? = null

    @Volatile
    private var loaded = false

    /** Whether [handle] (a caller's address) is on the user's block list. Never throws; false while nothing was ever sent. */
    fun isBlocked(handle: String): Boolean {
        val h = current() ?: return false
        if (h.hashes.isEmpty()) return false
        val key = keyOf(handle) ?: return false
        return try {
            val mine = BlockList.hmac(h.salt, key)
            h.hashes.any { MessageDigest.isEqual(it.toByteArray(), mine.toByteArray()) }
        } catch (e: Exception) {
            false
        }
    }

    /** Replaces the copy with the one [request] holds (a new salt and its hashes); kept on disk too. */
    @Synchronized
    fun replace(request: DirectWire.BlockListRequest) {
        val salt = unhex(request.salt) ?: return
        val next = Held(salt, request.hashes.take(DirectWire.MAX_BLOCKED).toSet())
        held = next
        loaded = true
        file?.let { f ->
            runCatching {
                f.parentFile?.mkdirs()
                val json = JSONObject().put("salt", request.salt).put("hashes", JSONArray(next.hashes.toList()))
                MediaCache.writeAtomically(f, json.toString().toByteArray(Charsets.UTF_8))
            }
        }
    }

    /** How many are blocked (for tests and the log's counts only). */
    val size: Int get() = current()?.hashes?.size ?: 0

    private fun current(): Held? {
        if (!loaded) load()
        return held
    }

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        val f = file ?: return
        held = try {
            if (!f.isFile || f.length() > MAX_FILE_BYTES) {
                null
            } else {
                DirectWire.BlockListRequest.fromJson(JSONObject(f.readText()))?.let { r -> unhex(r.salt)?.let { Held(it, r.hashes.toSet()) } }
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val MAX_FILE_BYTES = 256 * 1024L

        @Volatile
        private var instance: BlockedCallers? = null

        /** The ":imessage" process's one. */
        fun get(context: Context): BlockedCallers = instance ?: synchronized(this) {
            instance ?: BlockedCallers(File(context.applicationContext.filesDir, "imessage/blocked-callers.json")).also { instance = it }
        }

        internal fun installForTesting(callers: BlockedCallers?) = synchronized(this) { instance = callers }

        /** A caller's address as the block list keys it ([BlockList.normalize]): only a whole number or an email address. */
        internal fun keyOf(handle: String): String? {
            val h = CallIds.handle(handle) ?: return null
            val key = IMessageAddress.key(h) ?: return null
            return key.takeIf { it.startsWith("+") || it.contains('@') }
        }

        private fun unhex(s: String): ByteArray? {
            if (s.length % 2 != 0) return null
            return try {
                ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}
