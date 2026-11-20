package com.lightdecoder.imessage

import android.content.Context
import android.util.Log
import com.lightdecoder.relay.ActivityLog
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * What Blue Bubbles did, step by step, so "my texts went green" can be traced
 * on a real phone: whether OpenBubbles' notifications arrive, whether they
 * carry a Reply button, which way each message went (silently through the
 * Reply button, through OpenBubbles' screen, or, wrongly, as a text), how long
 * OpenBubbles took to show it back, and each step of Check iMessage.
 *
 * Kept in app storage (newest [MAX_LINES]) for the problem report, and also
 * written to logcat under [TAG], so the same lines can be read over USB
 * (`adb logcat -s LDiMessage`) and in the on-device test's log.
 *
 * Kinds, outcomes, counts and timings only. Message text, names, phone
 * numbers, email addresses and OpenBubbles' chat ids are never written; a
 * chat is named by [chatRef], a short tag keyed with a secret of this install
 * that tells chats apart in one log but cannot be turned back into the chat,
 * nor matched against a chat id by anyone without the phone. Every line also
 * goes through [Redact] on its way in: an error message from Apple or the
 * iMessage library that names a number or an Apple ID loses it.
 */
object IMessageLog {
    const val TAG = "LDiMessage"
    const val PREFIX = "iMessage: "
    private const val MAX_LINES = 200

    /** The chat-tag key's file, in no-backup storage: a key of this install only, never restored elsewhere. */
    internal const val KEY_FILE = "imessage-log-key"
    private const val KEY_BYTES = 32

    /** Hex digits of a [chatRef]: 24 bits, enough to tell a phone's chats apart in one log. */
    private const val REF_HEX = 6

    private val log = ActivityLog("imessage-log.txt", MAX_LINES)

    @Volatile
    private var app: Context? = null

    @Volatile
    private var key: ByteArray? = null

    /** Lets [chatRef] find this install's key (it takes no context). Cheap: the key is read on first use. */
    fun init(context: Context) {
        app = context.applicationContext
    }

    fun add(context: Context, line: String) {
        init(context)
        val clean = Redact.text(line)
        log.add(context, PREFIX + clean)
        runCatching { Log.i(TAG, clean) }
    }

    /** Newest first. */
    fun read(context: Context): List<String> = log.read(context)

    fun clear(context: Context) = log.clear(context)

    /**
     * "chat#3f9a1c": the same chat gets the same tag on this phone (in both of the app's processes,
     * and after a restart); the tag gives the chat away to no one, being an HMAC-SHA256 of its id
     * with a random key that never leaves the phone ([KEY_FILE]).
     */
    fun chatRef(chatGuid: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key(), "HmacSHA256")) }
        val digest = mac.doFinal(("ld-chat:" + chatGuid).toByteArray(Charsets.UTF_8))
        return "chat#" + digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.take(REF_HEX)
    }

    /**
     * This install's key: made once, at random, and kept in the app's no-backup storage; read by
     * both processes. Without a context yet (a line built before [init]), a key of this process's
     * own, so a tag is never left unkeyed.
     */
    private fun key(): ByteArray {
        key?.let { return it }
        synchronized(this) {
            key?.let { return it }
            val context = app ?: return fallback
            // Kept even when it could not be read or written (a full disk): the process's own then, not a retry per line.
            return (loadKey(context.noBackupFilesDir) ?: fallback).also { key = it }
        }
    }

    /** A key for this process alone, for tags made before [init]. */
    private val fallback: ByteArray by lazy { ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) } }

    /**
     * The key in [dir], made there if there is none. Made under a lock on a file next to it, so two
     * processes asking at once end with one key, whichever came first; written aside and renamed
     * into place, so it is never seen half-written. Null if it cannot be read or written.
     */
    internal fun loadKey(dir: File): ByteArray? {
        val file = File(dir, KEY_FILE)
        read(file)?.let { return it }
        return try {
            dir.mkdirs()
            RandomAccessFile(File(dir, "$KEY_FILE.lock"), "rw").use { lockFile ->
                lockFile.channel.lock().use {
                    read(file) ?: run {
                        val fresh = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
                        val tmp = File(dir, "$KEY_FILE.tmp")
                        tmp.writeBytes(fresh)
                        if (!tmp.renameTo(file)) {
                            tmp.delete()
                            return null
                        }
                        read(file)
                    }
                }
            }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    private fun read(file: File): ByteArray? = try {
        file.takeIf { it.isFile }?.readBytes()?.takeIf { it.size == KEY_BYTES }
    } catch (e: IOException) {
        null
    }

    /** Tests: forget the key read (the file stays). */
    internal fun resetKeyForTesting() {
        key = null
    }
}
