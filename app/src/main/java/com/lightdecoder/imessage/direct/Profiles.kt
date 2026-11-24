package com.lightdecoder.imessage.direct

import android.util.Log
import com.lightdecoder.chat.SharedNames
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import uniffi.rust_bridge.ShareProfileMessage

/**
 * Where a shared name and photo is in iCloud (the library's `ShareProfileMessage` without its
 * poster, which is never taken): the record's key and its decryption key. Its text ([encode]) is
 * what crosses to the main process with an offer and comes back with [DirectWire.PROFILE_FETCH];
 * read strictly ([decode]): anything not as written here is no descriptor. Pure.
 */
data class ProfileDescriptor(val key: ByteArray, val record: String) {

    fun encode(): String = JSONObject().put("v", 1).put("k", Base64.getEncoder().encodeToString(key)).put("r", record).toString()

    fun toMessage(): ShareProfileMessage = ShareProfileMessage(key, record, null)

    override fun equals(other: Any?): Boolean = other is ProfileDescriptor && other.record == record && other.key.contentEquals(key)

    override fun hashCode(): Int = record.hashCode() * 31 + key.contentHashCode()

    companion object {
        const val MAX_KEY_BYTES = 256
        const val MAX_RECORD_CHARS = 256
        private val RECORD = Regex("^[\\x21-\\x7e]{1,$MAX_RECORD_CHARS}$")

        /** [m] as a descriptor (its poster left out); null when its parts are not as Apple's are. */
        fun of(m: ShareProfileMessage): ProfileDescriptor? = make(m.cloudKitDecryptionRecordKey, m.cloudKitRecordKey)

        private fun make(key: ByteArray, record: String): ProfileDescriptor? {
            if (key.isEmpty() || key.size > MAX_KEY_BYTES || !RECORD.matches(record)) return null
            return ProfileDescriptor(key, record)
        }

        fun decode(text: String?): ProfileDescriptor? {
            if (text == null || text.length > DirectWire.MAX_DESCRIPTOR_CHARS) return null
            return try {
                val o = JSONObject(text)
                if (o.opt("v") != 1) return null
                val key = Base64.getDecoder().decode(o.opt("k") as? String ?: return null)
                make(key, o.opt("r") as? String ?: return null)
            } catch (e: JSONException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

/**
 * Name & Photo in the ":imessage" process (B6.1, B6.2): fetching a name and photo the main process
 * took an offer of, and the user's own, published once they asked for it and shared with one chat at
 * a time. Nothing here is done unless [enabled] (signed in, iCloud's services, the feature on).
 *
 * - A fetch is the main process's ask, for an offer from someone it has a chat with: [FETCH_MS] at
 *   most, a photo of [MAX_IMAGE_BYTES] at most (a larger one is not written), no poster ever. The
 *   names come back as they came, cut and cleaned ([SharedNames.clean]); the main process cleans them again.
 * - The user's own ([own]) is kept in `files/imessage/my-profile.json` (removed by a sign-out): the
 *   name, the photo as the user chose it, and where it was published.
 */
class Profiles(
    private val bridge: FeatureBridge,
    private val file: File,
    private val enabled: () -> Boolean,
    private val sharing: () -> Boolean,
) {

    /** What a [publish] came to. */
    sealed class Publish {
        object Done : Publish()

        /** The account already shares a name and photo (the user's iPhone): only with [replace] is it replaced. */
        object AskReplace : Publish()
        object Off : Publish()
        data class Failed(val why: String) : Publish()
    }

    /** The user's own name and photo, as kept. */
    data class Own(val first: String, val last: String, val image: ByteArray?, val pointer: ProfileDescriptor?) {
        val name: String get() = listOf(first, last).filter { it.isNotBlank() }.joinToString(" ")

        fun toJson(): JSONObject = JSONObject().put("first", first).put("last", last)
            .apply { image?.let { put("image", Base64.getEncoder().encodeToString(it)) } }
            .apply { pointer?.let { put("pointer", it.encode()) } }

        companion object {
            fun fromJson(text: String): Own? = try {
                val o = JSONObject(text)
                Own(
                    SharedNames.clean((o.opt("first") as? String).orEmpty()),
                    SharedNames.clean((o.opt("last") as? String).orEmpty()),
                    (o.opt("image") as? String)?.let { Base64.getDecoder().decode(it) }?.takeIf { it.size <= MAX_OWN_IMAGE_BYTES },
                    ProfileDescriptor.decode(o.opt("pointer") as? String),
                )
            } catch (e: JSONException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }

    /**
     * Fetches what [descriptor] points to: the photo written to [imagePath] (a file the main process
     * chose, checked by the caller), the names cleaned. Null when the feature is off, or Apple did not
     * answer in [FETCH_MS].
     */
    suspend fun fetch(descriptor: ProfileDescriptor, imagePath: File): DirectWire.ProfileReply? {
        if (!enabled()) return null
        val fetched = try {
            withTimeoutOrNull(FETCH_MS) { bridge.fetchProfile(descriptor) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "a shared name and photo was not fetched (${e::class.java.simpleName})")
            null
        } ?: return null
        val image = fetched.image?.takeIf { it.isNotEmpty() && it.size <= MAX_IMAGE_BYTES }
        var wrote = false
        if (image != null) {
            try {
                imagePath.parentFile?.mkdirs()
                FileOutputStream(imagePath).use { it.write(image) }
                wrote = true
            } catch (e: java.io.IOException) {
                imagePath.delete()
            }
        }
        return DirectWire.ProfileReply(SharedNames.clean(fetched.name), SharedNames.clean(fetched.first), SharedNames.clean(fetched.last), wrote)
    }

    /** The user's own name and photo, as kept; null with none. */
    fun own(): Own? = try {
        file.takeIf { it.isFile && it.length() <= MAX_FILE_BYTES }?.readText()?.let(Own::fromJson)
    } catch (e: java.io.IOException) {
        null
    }

    /** Whether the user's own name and photo is published and may be shared (the setting on). */
    fun canShare(): Boolean = enabled() && sharing() && own()?.pointer != null

    /**
     * Publishes the user's name and photo ([first], [last], a square JPEG [image]). When the account
     * already shares one this phone did not publish, only with [replace] (the user said "Replace").
     */
    suspend fun publish(first: String, last: String, image: ByteArray?, replace: Boolean): Publish {
        if (!enabled()) return Publish.Off
        val f = SharedNames.clean(first)
        val l = SharedNames.clean(last)
        if (f.isEmpty() && l.isEmpty()) return Publish.Failed("no name")
        val pic = image?.takeIf { it.size <= MAX_OWN_IMAGE_BYTES }
        val existing = own()?.pointer
        return try {
            if (existing == null && !replace && bridge.hasMyProfileRecord()) return Publish.AskReplace
            val own = Own(f, l, pic, null)
            val pointer = bridge.setProfile(own.name, f, l, pic, existing)
            write(own.copy(pointer = pointer))
            Publish.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the user's name and photo was not published (${e::class.java.simpleName})")
            Publish.Failed(com.lightdecoder.imessage.Redact.secrets(DirectEngine.readable(e)))
        }
    }

    /** Where the user's own is, to share it with a chat; null when it may not be shared. */
    fun pointer(): ProfileDescriptor? = if (canShare()) own()?.pointer else null

    /** Forgets the user's own (it stays at Apple until replaced; it is simply never shared from here again). */
    fun forget() {
        file.delete()
    }

    private fun write(own: Own) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use {
            it.write(own.toJson().toString().toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("not kept")
        }
    }

    companion object {
        private const val TAG = "Profiles"
        const val FETCH_MS = 20_000L
        const val MAX_IMAGE_BYTES = 1024 * 1024
        const val MAX_OWN_IMAGE_BYTES = 256 * 1024
        private const val MAX_FILE_BYTES = 512 * 1024L
    }
}
