package com.lightdecoder.imessage

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.lightdecoder.archive.MediaCache
import com.lightdecoder.chat.ChatService
import java.io.File
import java.io.InputStream

/**
 * Pictures and videos received over iMessage, in their bubbles.
 *
 * OpenBubbles' notification says only "Image" (or "Attachments: 2 images,
 * 1 movie"); the picture itself is never in it. With OpenBubbles' setting
 * Settings → Attachments → "Auto-save Attachments" on, OpenBubbles saves
 * each received picture and video, as it came, into the phone's gallery.
 * Android records which app saved a file there, so LightDecoder looks only
 * at OpenBubbles' own files saved around the time of the message, copies
 * the first one not yet used into its iMessage media folder, and gives it
 * to the waiting bubble.
 *
 * Needs the permission to see photos and videos (Android 13+:
 * READ_MEDIA_IMAGES / READ_MEDIA_VIDEO), and Android 10+ (which records
 * the saving app).
 */
object IMessageMedia {

    /** One of OpenBubbles' saved files. [addedSec] is when it was saved, in seconds. */
    data class Saved(val id: Long, val uri: Uri, val mime: String, val addedSec: Long, val size: Long, val width: Int, val height: Int) {
        val isVideo: Boolean get() = mime.startsWith("video/")
    }

    /** How long after a message OpenBubbles may take to save its picture, and how far before it a clock may be. */
    private const val BEFORE_SEC = 120L
    private const val AFTER_SEC = 15 * 60L

    /** Placeholders older than this are left as they are. */
    private const val LOOK_BACK_MS = 24 * 60 * 60 * 1000L

    /** OpenBubbles' files saved between two times (seconds); tests replace it. */
    @Volatile
    internal var source: (Context, Long, Long) -> List<Saved> = ::queryGallery

    /** Opens a saved file; tests replace it. */
    @Volatile
    internal var open: (Context, Saved) -> InputStream? = { context, saved -> context.contentResolver.openInputStream(saved.uri) }

    /** The permissions that let LightDecoder see OpenBubbles' saved pictures and videos. */
    fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun hasPermission(context: Context): Boolean =
        permissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** Whether this Android can tell which app saved a file (Android 10+). */
    fun supported(): Boolean = Build.VERSION.SDK_INT >= 29

    // --- when to look ---------------------------------------------------------------------

    private val thread by lazy { HandlerThread("imessage-media").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }

    @Volatile
    private var observing = false

    /**
     * Looks now and a few more times over the next minutes (OpenBubbles downloads and saves after the
     * notification), and from then on whenever the gallery changes.
     */
    fun schedule(context: Context) {
        val app = context.applicationContext
        observe(app)
        // A new round replaces the one under way (each look covers everything still waiting).
        handler.removeCallbacksAndMessages(null)
        for (delay in longArrayOf(1_000, 5_000, 15_000, 45_000, 120_000, 300_000)) {
            handler.postDelayed({ runCatching { fill(app) } }, delay)
        }
    }

    private fun observe(context: Context) {
        if (observing || !supported()) return
        observing = true
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                handler.removeCallbacks(fillSoon)
                handler.postDelayed(fillSoon, 1_500)
            }
        }
        fillSoonContext = context
        runCatching {
            context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            context.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        }
    }

    @Volatile
    private var fillSoonContext: Context? = null
    private val fillSoon = Runnable { fillSoonContext?.let { runCatching { fill(it) } } }

    // --- matching ---------------------------------------------------------------------------

    private val lock = Any()

    /**
     * Gives each iMessage picture or video still waiting its file: OpenBubbles' saved file from around
     * its time, of the same kind, in order, each file used once. Returns how many it gave.
     */
    fun fill(context: Context, nowMs: Long = System.currentTimeMillis()): Int = synchronized(lock) {
        if (!IMessagePrefs.enabled(context)) return 0
        val repo = runCatching { IMessageManager.get(context).repo }.getOrNull() ?: return 0
        val waiting = runCatching { repo.mediaAwaitingFiles(nowMs - LOOK_BACK_MS) }.getOrDefault(emptyList())
        if (waiting.isEmpty()) return 0
        if (!supported()) return 0.also { note(context, "unsupported", "Pictures: this Android cannot tell which app saved a file (Android 10+ needed)") }
        if (!hasPermission(context)) {
            return 0.also { note(context, "permission", "Pictures: ${waiting.size} waiting; LightDecoder may not see photos and videos yet (allow it in the conversation)") }
        }
        val fromSec = waiting.first().timestampMs / 1000 - BEFORE_SEC
        val toSec = waiting.last().timestampMs / 1000 + AFTER_SEC
        val saved = runCatching { source(context, fromSec, toSec) }.getOrElse {
            return 0.also { note(context, "query", "Pictures: the gallery could not be read (${it.javaClass.simpleName})") }
        }.sortedBy { it.addedSec }
        val used = IMessagePrefs.usedMedia(context).toMutableSet()
        var given = 0
        for (item in waiting) {
            val video = item.mime.startsWith("video/")
            val t = item.timestampMs / 1000
            val match = saved.firstOrNull { s ->
                s.id.toString() !in used && s.isVideo == video && s.addedSec in (t - BEFORE_SEC)..(t + AFTER_SEC)
            } ?: continue
            val file = copy(context, match) ?: continue
            used += match.id.toString()
            repo.setMediaDetails(item.messageId, item.partId, match.mime, file.length(), match.width, match.height)
            repo.setMediaLocalPath(item.messageId, item.partId, file.path, thumbnail = false)
            if (match.isVideo) videoStill(context, match, file)?.let { repo.setMediaLocalPath(item.messageId, item.partId, it.path, thumbnail = true) }
            given++
            IMessageLog.add(
                context,
                "${IMessageLog.chatRef(item.conversationId)}: ${if (match.isVideo) "video" else "picture"} from OpenBubbles' Auto-save " +
                    "shown (${match.mime}, ${file.length() / 1024} KB, saved ${match.addedSec - t}s after the message)",
            )
        }
        IMessagePrefs.setUsedMedia(context, used)
        // Still waiting after its last look: say what is likely wrong, once each.
        for (item in waiting) {
            val age = nowMs - item.timestampMs
            if (age > 6 * 60 * 1000L && age < LOOK_BACK_MS && IMessagePrefs.markMissingNoted(context, item.messageId + "/" + item.partId)) {
                IMessageLog.add(
                    context,
                    "${IMessageLog.chatRef(item.conversationId)}: no saved ${if (item.mime.startsWith("video/")) "video" else "picture"} from " +
                        "OpenBubbles for a message ${age / 60_000} min old: is OpenBubbles' Settings → Attachments → Auto-save Attachments on?",
                )
            }
        }
        given
    }

    private fun copy(context: Context, saved: Saved): File? {
        val ext = when {
            saved.mime.endsWith("heic") || saved.mime.endsWith("heif") -> "heic"
            saved.mime.endsWith("png") -> "png"
            saved.mime.endsWith("gif") -> "gif"
            saved.mime.endsWith("webp") -> "webp"
            saved.mime.startsWith("video/") -> if (saved.mime.endsWith("quicktime")) "mov" else "mp4"
            else -> "jpg"
        }
        val dir = MediaCache.mediaDir(context, ChatService.IMESSAGE).apply { mkdirs() }
        val target = File(dir, "ob-saved-${saved.id}.$ext")
        if (target.isFile && target.length() > 0) return target
        return try {
            val input = open(context, saved) ?: return null
            input.use { inp -> target.outputStream().use { inp.copyTo(it) } }
            target.takeIf { it.length() > 0 } ?: run { target.delete(); null }
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    /** A still for a video, from Android's own thumbnail (Android 10+). */
    private fun videoStill(context: Context, saved: Saved, copy: File? = null): File? {
        val bitmap = (if (Build.VERSION.SDK_INT >= 29) runCatching {
            context.contentResolver.loadThumbnail(saved.uri, android.util.Size(720, 720), null)
        }.getOrNull() else null) ?: copy?.let { frameOf(it) } ?: return null
        return runCatching {
            val file = File(MediaCache.mediaDir(context, ChatService.IMESSAGE), "ob-saved-${saved.id}-still.jpg")
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            file
        }.getOrNull()
    }

    /** A frame from the video file itself, when Android has no thumbnail for it. */
    private fun frameOf(video: File): android.graphics.Bitmap? = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(video.path)
            r.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            r.release()
        }
    }.getOrNull()

    private val noted = HashSet<String>()

    /** A line said once per process for each kind of problem. */
    private fun note(context: Context, kind: String, line: String) {
        if (noted.add(kind)) IMessageLog.add(context, line)
    }

    /** OpenBubbles' pictures and videos saved between [fromSec] and [toSec]. */
    private fun queryGallery(context: Context, fromSec: Long, toSec: Long): List<Saved> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        val owners = OpenBubbles.PACKAGES
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MIME_TYPE, MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.WIDTH, MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        val selection = "${MediaStore.Files.FileColumns.OWNER_PACKAGE_NAME} IN (${owners.joinToString(",") { "?" }}) AND " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?) AND " +
            "${MediaStore.Files.FileColumns.DATE_ADDED} BETWEEN ? AND ?"
        val args = owners.toTypedArray() + arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            fromSec.toString(), toSec.toString(),
        )
        val out = ArrayList<Saved>()
        context.contentResolver.query(collection, projection, selection, args, "${MediaStore.Files.FileColumns.DATE_ADDED} ASC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val video = c.getInt(6) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                out += Saved(
                    id = id, uri = ContentUris.withAppendedId(base, id),
                    mime = c.getString(1) ?: if (video) "video/mp4" else "image/jpeg",
                    addedSec = c.getLong(2), size = c.getLong(3), width = c.getInt(4), height = c.getInt(5),
                )
            }
        }
        return out
    }

    internal fun resetForTesting() {
        source = ::queryGallery
        open = { context, saved -> context.contentResolver.openInputStream(saved.uri) }
        synchronized(noted) { noted.clear() }
    }
}
