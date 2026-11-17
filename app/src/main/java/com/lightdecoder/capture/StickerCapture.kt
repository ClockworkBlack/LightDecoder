package com.lightdecoder.capture

import android.content.Context
import com.lightdecoder.heif.HeifCodec
import com.lightdecoder.heif.ImageSniffer
import com.lightdecoder.heif.Media
import com.lightdecoder.inbox.InboxEvents
import com.lightdecoder.inbox.InboxItem
import com.lightdecoder.inbox.InboxLog
import com.lightdecoder.inbox.InboxPrefs
import com.lightdecoder.inbox.InboxStore
import com.lightdecoder.inbox.StickerAlerts

/**
 * Turns the bytes of one received picture into a sticker inbox item: sniff,
 * filter, decode a preview, store, announce. Shared by the notification
 * listener and the Google Messages link, so both behave the same and the same
 * picture caught by both is kept once (by its digest).
 *
 * Runs on a worker thread. It can throw on a pathological picture (for
 * example OutOfMemoryError); callers catch, as the listener always has.
 */
object StickerCapture {
    /** Larger attachments are not read at all. */
    const val MAX_BYTES = 32 * 1024 * 1024
    private const val PREVIEW_MAX_SIDE = 1024
    private val PICTURE_EXTENSIONS = setOf("png", "jpg", "gif", "webp", "avif")

    sealed class Outcome {
        object Saved : Outcome()
        object Duplicate : Outcome()

        /** Deliberately not kept, e.g. "not_picture" or "not_heic". */
        data class Skipped(val reason: String) : Outcome()

        /** Could not be kept, e.g. "decode" or "store". */
        data class Failed(val reason: String) : Outcome()
    }

    /**
     * What is known about the picture besides its bytes.
     *
     * @param id     inbox id, from [InboxStore.idFor]
     * @param mime   MIME type as the sender labelled it, possibly empty or wrong
     * @param source [InboxItem.SOURCE_NOTIFICATION] or [InboxItem.SOURCE_RELAY]
     */
    data class Meta(
        val id: String,
        val sender: String,
        val title: String,
        val appPackage: String,
        val timestampMs: Long,
        val mime: String,
        val source: String,
    )

    fun capture(context: Context, bytes: ByteArray, meta: Meta): Outcome {
        val store = InboxStore.get(context)
        if (store.contains(meta.id)) return Outcome.Duplicate
        val label = meta.mime.ifEmpty { "no type" }

        if (bytes.isEmpty() || bytes.size > MAX_BYTES) {
            store.markSeen(meta.id)
            log(context, meta, "Attachment ($label): empty or larger than ${MAX_BYTES / (1024 * 1024)} MB")
            return Outcome.Skipped("size")
        }

        val heif = ImageSniffer.isHeif(bytes)
        val extension = ImageSniffer.extensionFor(bytes, meta.mime)
        val isPicture = heif || extension in PICTURE_EXTENSIONS
        if (!isPicture) {
            store.markSeen(meta.id)
            log(context, meta, "Attachment ($label): not a picture, skipped")
            return Outcome.Skipped("not_picture")
        }
        if (!heif && !InboxPrefs.catchAllImages(context)) {
            store.markSeen(meta.id)
            log(context, meta, "Picture ($label, .$extension): Messages can show it, skipped")
            return Outcome.Skipped("not_heic")
        }

        val digest = InboxStore.digestOf(bytes)
        if (store.containsDigest(digest)) {
            store.markSeen(meta.id)
            log(context, meta, "Picture ($label, .$extension): already in the inbox")
            return Outcome.Duplicate
        }

        val animated = heif && runCatching { HeifCodec.probe(bytes).isSequence }.getOrDefault(false)
        val full = try {
            Media.decodeFirstFrame(bytes)
        } catch (e: HeifCodec.DecodeException) {
            store.markSeen(meta.id)
            log(context, meta, "Picture ($label, .$extension): could not be decoded: ${e.message}")
            return Outcome.Failed("decode")
        }
        val preview = Media.scaledToFit(full, PREVIEW_MAX_SIDE)
        try {
            val item = InboxItem(
                id = meta.id,
                sender = meta.sender,
                title = meta.title,
                appPackage = meta.appPackage,
                timestampMs = meta.timestampMs,
                mime = meta.mime.ifEmpty { ImageSniffer.mimeFor(extension) },
                width = full.width,
                height = full.height,
                animated = animated,
                originalExt = extension,
                source = meta.source,
                digest = digest,
            )
            if (!store.add(item, bytes, preview)) {
                return if (store.contains(meta.id) || store.containsDigest(digest)) {
                    Outcome.Duplicate
                } else {
                    Outcome.Failed("store")
                }
            }
            log(context, meta, "Picture ($label, .$extension, ${full.width}×${full.height}): saved")
            InboxEvents.notifyChanged()
            StickerAlerts.show(context, item, preview)
            return Outcome.Saved
        } finally {
            if (preview !== full) preview.recycle()
            full.recycle()
        }
    }

    /** Same lines as the listener always wrote; pictures from the link say so. */
    private fun log(context: Context, meta: Meta, line: String) {
        val text = if (meta.source == InboxItem.SOURCE_RELAY) {
            "Relay: " + line.replaceFirstChar { it.lowercase() }
        } else {
            line
        }
        InboxLog.add(context, text)
    }
}
