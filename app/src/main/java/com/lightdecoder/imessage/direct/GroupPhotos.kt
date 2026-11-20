package com.lightdecoder.imessage.direct

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.util.Log
import com.lightdecoder.heif.ExifOrientation
import com.lightdecoder.heif.HeifCodec
import com.lightdecoder.heif.ImageSniffer
import com.lightdecoder.ui.chat.MediaLoader
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import org.json.JSONObject
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MmcsFile
import uniffi.rust_bridge.PushChatMessage

/**
 * Each iMessage group's photo (what an iPhone shows as the group's picture, set with "Change Name
 * and Photo"), by the archive's conversation id: a square JPEG and the group's version Apple gave
 * it with ([PushChatMessage.IconChange]'s, [GroupVersions]).
 *
 * Kept as [ChatBackgrounds] keeps backgrounds: the pictures in [dir] (one per group,
 * `<key>-<version>.jpg`) and an index (`index.json`) the main process alone writes and reads
 * ([DirectLink] when Apple says, or the user, changes one; the screens to show it). The ":imessage"
 * process only writes new pictures into [dir] ([incomingFile]), which the main process then takes
 * over or deletes. Every read goes to the file, so nothing is cached across processes or tests.
 *
 * A photo taken off is kept as a version without a picture, so an older change that arrives late
 * (Apple's journal handing one over again, or a slow device) never puts it back.
 */
class GroupPhotos(context: Context) {

    private val dir = dir(context)
    private val index = File(dir, INDEX)

    /** A group's photo: its picture (null: none), and its version ([known] false: no change of it was ever taken). */
    data class Photo(val file: File?, val version: Long, val known: Boolean)

    /** How [apply] went. */
    enum class Applied { APPLIED, SAME, OLDER }

    /** [conversationId]'s photo now. */
    fun of(conversationId: String): Photo = synchronized(LOCK) {
        val entry = read().optJSONObject(conversationId) ?: return Photo(null, 0, known = false)
        Photo(entry.optString(KEY_PATH).takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }, entry.optLong(KEY_VERSION), known = true)
    }

    /** The picture [conversationId] is shown by, if it has one. */
    fun pictureOf(conversationId: String): File? = of(conversationId).file

    /** Every group's picture, by conversation id, in one read (for a list). */
    fun all(): Map<String, File> = synchronized(LOCK) {
        val all = read()
        val out = HashMap<String, File>()
        for (id in all.keys()) {
            val path = all.optJSONObject(id)?.optString(KEY_PATH).orEmpty()
            if (path.isNotEmpty()) File(path).takeIf { it.isFile }?.let { out[id] = it }
        }
        out
    }

    /**
     * [conversationId]'s photo is now [picture] (a file in [dir], moved into place), or none, as of
     * [version]. One older than the group's photo is ignored ([Applied.OLDER]), and the same version
     * again changes nothing ([Applied.SAME]); either way [picture] is deleted. A new one replaces the
     * old picture, which is deleted.
     */
    fun apply(conversationId: String, picture: File?, version: Long): Applied = synchronized(LOCK) {
        val all = read()
        val entry = all.optJSONObject(conversationId)
        if (entry != null) {
            val stored = entry.optLong(KEY_VERSION)
            if (version <= stored) {
                picture?.delete()
                return if (version == stored) Applied.SAME else Applied.OLDER
            }
        }
        val old = entry?.optString(KEY_PATH).orEmpty()
        val kept = picture?.let { moveIntoPlace(conversationId, it, version) }
        all.put(conversationId, JSONObject().put(KEY_PATH, kept?.path.orEmpty()).put(KEY_VERSION, version))
        write(all)
        if (old.isNotEmpty() && old != kept?.path) File(old).delete()
        Applied.APPLIED
    }

    /** Forgets [conversationId]'s photo (its picture goes): the group was deleted here. */
    fun forget(conversationId: String) = synchronized(LOCK) {
        val all = read()
        val old = all.optJSONObject(conversationId)?.optString(KEY_PATH).orEmpty()
        if (all.remove(conversationId) != null) write(all)
        if (old.isNotEmpty()) File(old).delete()
    }

    private fun moveIntoPlace(conversationId: String, picture: File, version: Long): File? {
        val target = File(dir, "${ChatBackgrounds.keyOf(conversationId)}-$version.jpg")
        if (picture.absolutePath == target.absolutePath) return target
        return try {
            target.delete()
            if (!picture.renameTo(target)) {
                picture.copyTo(target, overwrite = true)
                picture.delete()
            }
            target
        } catch (e: java.io.IOException) {
            Log.w(TAG, "group photo not kept", e)
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
        private const val TAG = "GroupPhotos"
        private const val INDEX = "index.json"
        private const val KEY_PATH = "path"
        private const val KEY_VERSION = "version"
        private val LOCK = Any()

        /** Where the pictures are, in either process. */
        fun dir(context: Context): File = File(context.applicationContext.filesDir, "imessage/group-photos")

        /** A new file in [dir] for a picture not yet taken over by the main process ([apply]). */
        fun incomingFile(context: Context, prefix: String = "incoming"): File =
            File(dir(context).apply { mkdirs() }, "$prefix-${UUID.randomUUID()}.jpg")

        /** Whether [file] is one of [dir]'s (a path from the other process is taken only from there). */
        fun isOurs(context: Context, file: File): Boolean = try {
            file.canonicalFile.parentFile == dir(context).canonicalFile
        } catch (e: java.io.IOException) {
            false
        }
    }
}

/**
 * Pictures of group photos: whatever comes in (anyone in a group can send one, so nothing of it is
 * trusted) or the user picked, decoded no larger than needed ([decode]: its size read first, never
 * more than [MAX_PIXELS], subsampled while it decodes) and cut to a centred square [SIDE] pixels on
 * a side ([square]), as a JPEG. Android graphics only; tested in GroupPhotosTest.
 */
object GroupPhotoImages {

    /** A group photo's side, as an iPhone makes them (rustpush: "file should be 570x570"). */
    const val SIDE = 570

    /** The most pixels a picture may have to be decoded at all (a 48 MP photo). */
    const val MAX_PIXELS = 50_000_000L

    /** The most pixels a picture is decoded at (subsampled to it): 16 MB as ARGB. */
    private const val MAX_DECODED_PIXELS = 4_000_000L

    private const val JPEG_QUALITY = 90

    /** [bytes] (JPEG, PNG, WebP, HEIC) decoded upright, no larger than needed for a [side]-pixel square; null when it is no picture or too large. */
    fun decode(bytes: ByteArray, side: Int = SIDE): Bitmap? = try {
        if (ImageSniffer.isHeif(bytes)) decodeHeif(bytes, side) else decodeBounded(bytes, side)
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "group photo too large to decode")
        null
    } catch (e: Exception) {
        null
    }

    private fun decodeBounded(bytes: ByteArray, side: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleFor(w, h, side) }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return ExifOrientation.upright(bytes, bitmap)
    }

    private fun decodeHeif(bytes: ByteArray, side: Int): Bitmap? {
        val info = HeifCodec.probe(bytes)
        if (info.width <= 0 || info.height <= 0) return null
        val pixels = info.width.toLong() * info.height
        if (pixels > MAX_PIXELS) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !info.hasAlpha && !info.isSequence) {
            MediaLoader.systemDecode(bytes, side * 2)?.let { return it }
        }
        if (pixels > BackgroundPosters.MAX_FULL_DECODE_PIXELS) return null
        return HeifCodec.decodeStill(bytes)
    }

    /**
     * The subsampling for a picture of [width] x [height] cut to a [side]-pixel square: as much as
     * keeps its shorter side at [side] or more, and at least enough to keep it within [MAX_DECODED_PIXELS].
     */
    internal fun sampleFor(width: Int, height: Int, side: Int): Int {
        var sample = 1
        while (minOf(width, height) / (sample * 2) >= side) sample *= 2
        while ((width.toLong() / sample) * (height.toLong() / sample) > MAX_DECODED_PIXELS) sample *= 2
        return sample
    }

    /** [bitmap]'s centred square, [side] pixels on a side. */
    fun square(bitmap: Bitmap, side: Int = SIDE): Bitmap {
        val edge = minOf(bitmap.width, bitmap.height)
        val left = (bitmap.width - edge) / 2
        val top = (bitmap.height - edge) / 2
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // A photo with transparency (a PNG) shows on white, as a JPEG has no transparency.
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawBitmap(bitmap, Rect(left, top, left + edge, top + edge), Rect(0, 0, side, side), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** [bytes] as a group photo (decoded, cut square) written to [out] as a JPEG. False when it is no picture (nothing is written then). */
    fun write(bytes: ByteArray, out: File, side: Int = SIDE): Boolean {
        val bitmap = decode(bytes, side) ?: return false
        return try {
            val squared = square(bitmap, side)
            out.parentFile?.mkdirs()
            out.outputStream().use { squared.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) } && out.length() > 0
        } catch (e: java.io.IOException) {
            Log.w(TAG, "group photo not written", e)
            false
        } catch (e: OutOfMemoryError) {
            false
        }
    }

    private const val TAG = "GroupPhotoImages"
}

/**
 * A group photo change from Apple, in the ":imessage" process: its picture is fetched from Apple's
 * file servers (never more than [MAX_DOWNLOAD_BYTES]), decoded no larger than needed and cut square
 * ([GroupPhotoImages]), and written where the main process takes it from ([GroupPhotos.incomingFile]);
 * then it becomes the archive's change ([DirectArchive.groupPhoto]). Nothing is fetched for a change
 * nobody can take (one Apple could not verify, one not a group's). A photo that cannot be fetched or
 * read leaves the row alone ("Bob changed the group photo."), as [DirectArchive.map] makes it. The
 * library's download is passed in ([Natives]) so this can be tested without it.
 */
class IncomingGroupPhotos(private val app: Context, private val natives: Natives) {

    fun interface Natives {
        /** Fetches [file] into [path] ([uniffi.rust_bridge.downloadMmcsFile]), never more than [maxBytes] of it ([DirectEngine.TooLarge] past it). */
        suspend fun download(file: MmcsFile, path: String, maxBytes: Long)
    }

    suspend fun receive(inst: MessageInst, me: Set<String>, nameOf: (String) -> String, texts: DirectArchive.Texts): DirectArchive.Update {
        val m = (inst.message as? PushChatMessage.IconChange)?.v1 ?: return DirectArchive.Update.Ignore
        // Taken off, or nothing anyone can take: nothing to fetch.
        val removed = DirectArchive.groupPhoto(inst, me, nameOf, texts, null)
        if (removed == DirectArchive.Update.Ignore) return removed
        val file = m.file ?: return removed
        val rowAlone = DirectArchive.map(inst, me, nameOf, texts)
        if (file.size > MAX_DOWNLOAD_BYTES.toULong()) {
            Log.w(TAG, "a group photo larger than ${MAX_DOWNLOAD_BYTES shr 20} MB was not fetched")
            return rowAlone
        }
        val out = GroupPhotos.incomingFile(app)
        val partial = File(GroupPhotos.dir(app), "photo-${UUID.randomUUID()}.part")
        try {
            try {
                // The library makes the file itself.
                natives.download(file, partial.path, MAX_DOWNLOAD_BYTES)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "a group photo was not fetched (${e::class.java.simpleName})")
                return rowAlone
            }
            // Whatever the library or the sender said: what is on disk is what counts.
            if (!partial.isFile || partial.length() <= 0 || partial.length() > MAX_DOWNLOAD_BYTES) return rowAlone
            if (!GroupPhotoImages.write(partial.readBytes(), out)) {
                Log.w(TAG, "a group photo that is no picture that can be shown here")
                out.delete()
                return rowAlone
            }
        } finally {
            partial.delete()
        }
        val update = DirectArchive.groupPhoto(inst, me, nameOf, texts, out.path)
        if (update !is DirectArchive.Update.GroupPhoto) out.delete()
        return update
    }

    companion object {
        private const val TAG = "IncomingGroupPhotos"

        /** The largest group photo fetched (an iPhone's is a small square picture). */
        const val MAX_DOWNLOAD_BYTES = 10L * 1024 * 1024
    }
}
