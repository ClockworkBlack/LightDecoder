package com.lightdecoder.imessage.direct

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONException
import org.json.JSONObject

/**
 * Each iMessage chat's background (iOS 26's transcript backgrounds), by the archive's conversation
 * id: the picture shown behind its messages (or, for a gradient or dynamic one, its kind and
 * colours or identifier: [BackgroundStyle]) and the version Apple last gave it.
 *
 * Kept in files, not preferences: the pictures in [dir] (one per chat, `<key>-<version>.jpg`) and an
 * index (`index.json`) the main process alone writes and reads ([DirectLink] when Apple says, or the
 * user, changes one; the thread screen to show it). The ":imessage" process only writes new
 * pictures into [dir] ([incomingFile]), which the main process then takes over or deletes. Every
 * read goes to the file, so nothing is cached across processes or tests.
 *
 * A background taken off is kept as a version without a picture, so an older change that arrives
 * late (Apple's journal handing one over again, or a slow device) never puts it back.
 */
class ChatBackgrounds(context: Context) {

    private val dir = dir(context)
    private val index = File(dir, INDEX)

    /**
     * A chat's background: its picture (null: none), its version (0: never set), and, for one that
     * is drawn rather than a picture (a gradient's colours, a dynamic one's identifier), its [style].
     */
    data class Background(val file: File?, val version: Long, val style: BackgroundStyle? = null) {
        /** Whether there is a background to show. */
        val isSet: Boolean get() = file != null || style != null
    }

    /** How [apply] went. */
    enum class Applied { APPLIED, SAME, OLDER }

    /** [conversationId]'s background now. */
    fun of(conversationId: String): Background = synchronized(LOCK) {
        val entry = read().optJSONObject(conversationId) ?: return Background(null, 0)
        val path = entry.optString(KEY_PATH)
        val style = BackgroundStyle.of(entry.optString(KEY_TYPE), entry.optString(KEY_DATA))
        Background(path.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }, entry.optLong(KEY_VERSION), style)
    }

    /** The picture shown behind [conversationId]'s messages, if it has one. */
    fun pictureOf(conversationId: String): File? = of(conversationId).file

    /**
     * [conversationId]'s background is now the picture [picture] (a file in [dir], moved into place),
     * or the drawn background [style], or none (neither), as of [version]. One older than the chat's is ignored ([Applied.OLDER]), and the
     * same version again changes nothing ([Applied.SAME]); either way [picture] is deleted. A new
     * one replaces the old picture, which is deleted.
     */
    fun apply(conversationId: String, picture: File?, version: Long, style: BackgroundStyle? = null): Applied = synchronized(LOCK) {
        val all = read()
        val entry = all.optJSONObject(conversationId)
        val stored = entry?.optLong(KEY_VERSION) ?: 0L
        if (entry != null && version <= stored) {
            picture?.delete()
            return if (version == stored) Applied.SAME else Applied.OLDER
        }
        val old = entry?.optString(KEY_PATH).orEmpty()
        val kept = if (style == null) picture?.let { moveIntoPlace(conversationId, it, version) } else null
        if (style != null) picture?.delete()
        val type = style?.type ?: if (kept != null) BackgroundStyle.TYPE_PHOTO else ""
        all.put(
            conversationId,
            JSONObject().put(KEY_PATH, kept?.path.orEmpty()).put(KEY_VERSION, version).put(KEY_TYPE, type).put(KEY_DATA, style?.data.orEmpty()),
        )
        write(all)
        if (old.isNotEmpty() && old != kept?.path) File(old).delete()
        Applied.APPLIED
    }

    private fun moveIntoPlace(conversationId: String, picture: File, version: Long): File? {
        val target = File(dir, "${keyOf(conversationId)}-$version.jpg")
        if (picture.absolutePath == target.absolutePath) return target
        return try {
            target.delete()
            if (!picture.renameTo(target)) {
                picture.copyTo(target, overwrite = true)
                picture.delete()
            }
            target
        } catch (e: java.io.IOException) {
            Log.w(TAG, "background picture not kept", e)
            picture.delete()
            null
        }
    }

    private fun read(): JSONObject = try {
        if (index.isFile) JSONObject(index.readText()) else JSONObject()
    } catch (e: JSONException) {
        JSONObject()
    } catch (e: java.io.IOException) {
        JSONObject()
    }

    private fun write(all: JSONObject) {
        dir.mkdirs()
        val tmp = File(dir, "$INDEX.tmp")
        tmp.writeText(all.toString())
        if (!tmp.renameTo(index)) {
            index.writeText(all.toString())
            tmp.delete()
        }
    }

    companion object {
        private const val TAG = "ChatBackgrounds"
        private const val INDEX = "index.json"
        private const val KEY_PATH = "path"
        private const val KEY_VERSION = "version"
        private const val KEY_TYPE = "type"
        private const val KEY_DATA = "data"
        private val LOCK = Any()

        /** Where the pictures are, in either process. */
        fun dir(context: Context): File = File(context.applicationContext.filesDir, "imessage/backgrounds")

        /** A new file in [dir] for a picture not yet taken over by the main process ([apply]). */
        fun incomingFile(context: Context, prefix: String = "incoming"): File =
            File(dir(context).apply { mkdirs() }, "$prefix-${UUID.randomUUID()}.jpg")

        /** Whether [file] is one of [dir]'s (a path from the other process is taken only from there). */
        fun isOurs(context: Context, file: File): Boolean =
            file.canonicalFile.parentFile == dir(context).canonicalFile

        /** A conversation id as part of a file name. */
        fun keyOf(conversationId: String): String =
            MessageDigest.getInstance("SHA-1").digest(conversationId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        /**
         * A background version as Apple counts them: nanoseconds since 2001 (Core Data's epoch), now.
         * OpenBubbles mints them this way; a later one wins on every device.
         */
        fun versionNow(nowMs: Long = System.currentTimeMillis()): Long = (nowMs * 1000 - APPLE_EPOCH_MICROS) * 1000

        private const val APPLE_EPOCH_MICROS = 978_307_200_000_000L
    }
}
