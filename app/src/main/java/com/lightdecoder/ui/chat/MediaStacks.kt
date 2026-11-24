package com.lightdecoder.ui.chat

import com.lightdecoder.archive.MediaRow
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.imessage.direct.AppleLocation
import com.lightdecoder.imessage.direct.IMessageExtra

/**
 * The media of one message as its bubble draws them (pure; tested in MediaStacksTest): its parts
 * without a Live Photo's moving parts ([shownMedia]), and its runs of photos and videos sent
 * together, which show as one collage (2-3) or stack (4 or more), as on an iPhone ([segments]).
 */
object MediaStacks {

    /** How a run of photos shows. */
    enum class Style { COLLAGE, STACK }

    /** One piece of a bubble's media: a part of its own, or a run of photos and videos shown together. */
    sealed class Segment {
        data class Single(val part: MediaRow) : Segment()

        data class Stack(val parts: List<MediaRow>) : Segment() {
            val style: Style get() = styleOf(parts.size)
        }
    }

    /** A collage for 2 or 3, a stack for more. */
    fun styleOf(count: Int): Style = if (count <= 3) Style.COLLAGE else Style.STACK

    /** Whether [part] may be in a run: a photo or a video, never a sticker, a location's card or a Live Photo's moving part. */
    fun stackable(part: MediaRow, extra: IMessageExtra): Boolean =
        isVisualPart(part) && part.partId !in extra.stickerParts && part.partId !in extra.motionParts &&
            !AppleLocation.isLocation(part.mime, part.fileName)

    /** The runs of 2 or more consecutive [stackable] parts in [parts] (indexes into it). */
    fun runs(parts: List<MediaRow>, extra: IMessageExtra): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = -1
        for (i in 0..parts.size) {
            val ok = i < parts.size && stackable(parts[i], extra)
            if (ok && start < 0) start = i
            if (!ok && start >= 0) {
                if (i - start >= 2) out += start until i
                start = -1
            }
        }
        return out
    }

    /** [parts] as the bubble draws them: each run one [Segment.Stack], every other part one [Segment.Single]. */
    fun segments(parts: List<MediaRow>, extra: IMessageExtra): List<Segment> {
        val runs = runs(parts, extra)
        val out = ArrayList<Segment>()
        var i = 0
        while (i < parts.size) {
            val run = runs.firstOrNull { it.first == i }
            if (run != null) {
                out += Segment.Stack(parts.subList(run.first, run.last + 1).toList())
                i = run.last + 1
            } else {
                out += Segment.Single(parts[i])
                i++
            }
        }
        return out
    }

    /** How many cards a stack draws (the top one and two behind it); a collage draws each of its 2-3. */
    fun cardsDrawn(count: Int): Int = if (styleOf(count) == Style.COLLAGE) count else minOf(count, 3)

    /** The tilt of card [index] of a collage of [count], in degrees: alternately one way and the other, 3 to 5 degrees. */
    fun collageRotation(index: Int, count: Int): Float {
        if (count <= 1) return 0f
        val sign = if (index % 2 == 0) -1f else 1f
        return sign * (3f + 2f * index / (count - 1).coerceAtLeast(1))
    }

    /** The side a stack's cards are decoded at: half a single picture's. */
    fun thumbSide(maxSide: Int): Int = (maxSide / 2).coerceAtLeast(1)

    /** The emoji on each photo of [parts]: the tapbacks of [row] whose person [IMessageExtra.tapbackParts] puts on it. */
    fun tapbacksOn(row: MessageRow, extra: IMessageExtra, parts: List<MediaRow>): Map<String, List<String>> {
        if (extra.tapbackParts.isEmpty()) return emptyMap()
        val byPart = HashMap<String, MutableList<String>>()
        for (reaction in row.reactions) {
            for (who in reaction.participantIds) {
                val idx = extra.tapbackParts[who] ?: continue
                val partId = "p$idx"
                if (parts.any { it.partId == partId }) byPart.getOrPut(partId) { ArrayList() } += reaction.emoji
            }
        }
        return byPart
    }
}

/**
 * The rest of a batch still being sent ([IMessageExtra.pending]: its local copy keeps only its first
 * file as a part), as parts of their own, so the copy shows the whole stack while it goes: only files
 * [allowed] (the outbox), at most [IMessageExtra.MAX_PENDING]. Nothing for any other message.
 */
fun MediaStacks.waitingParts(row: MessageRow, extra: IMessageExtra, allowed: (String) -> Boolean): List<MediaRow> {
    if (!row.isLocal || extra.pending.isEmpty()) return emptyList()
    return extra.pending.take(IMessageExtra.MAX_PENDING).withIndex().filter { allowed(it.value.path) }.map { (i, p) ->
        MediaRow(
            messageId = row.id, partId = "$WAITING_PART${i + 1}", mediaId = "", key = ByteArray(0), thumbMediaId = "", thumbKey = ByteArray(0),
            mime = p.mime, fileName = p.name, size = 0, width = 0, height = 0, inlineData = ByteArray(0), needsFullSize = false,
            localPath = p.path, thumbPath = "", fullSizeRequested = false, stickerChecked = false,
        )
    }
}

/** The part id a waiting file of a batch shows under ([waitingParts]): never one Apple numbers (`p<idx>`). */
const val WAITING_PART = "waiting"

/** This message's media as shown: without its Live Photos' moving parts ([IMessageExtra.live]), which are only played. */
fun MessageRow.shownMedia(extra: IMessageExtra = IMessageExtra.parse(this.extra)): List<MediaRow> {
    val motion = extra.motionParts
    return if (motion.isEmpty()) media else media.filter { it.partId !in motion }
}

/** The moving part of the Live Photo whose still is [stillPartId], if this message has one. */
fun MessageRow.motionOf(stillPartId: String, extra: IMessageExtra = IMessageExtra.parse(this.extra)): MediaRow? =
    extra.live[stillPartId]?.let { motion -> media.firstOrNull { it.partId == motion } }
