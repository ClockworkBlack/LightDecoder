package com.lightdecoder.ui.chat

import com.lightdecoder.archive.MediaRow
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.imessage.direct.IMessageExtra
import com.lightdecoder.relay.ReactionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Photos sent together in one message: which run together, and how they are laid out. */
@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class MediaStacksTest {

    private fun part(id: String, mime: String = "image/jpeg", name: String = "$id.jpg") = MediaRow(
        messageId = "imsg:G", partId = id, mediaId = "G/$id", key = ByteArray(0), thumbMediaId = "", thumbKey = ByteArray(0),
        mime = mime, fileName = name, size = 10, width = 0, height = 0, inlineData = ByteArray(0), needsFullSize = false,
        localPath = "", thumbPath = "", fullSizeRequested = false, stickerChecked = false,
    )

    private val none = IMessageExtra()

    @Test
    fun consecutivePhotosAndVideosOfOneMessageRunTogether() {
        val parts = listOf(part("p0"), part("p1", "video/quicktime", "a.mov"), part("p2"))
        assertEquals(listOf(0..2), MediaStacks.runs(parts, none))
        val segments = MediaStacks.segments(parts, none)
        assertEquals(1, segments.size)
        assertEquals(MediaStacks.Style.COLLAGE, (segments.single() as MediaStacks.Segment.Stack).style)
    }

    @Test
    fun aStickerOrAFileInBetweenSplitsTheRun() {
        val parts = listOf(part("p0"), part("p1"), part("p2"), part("p3"), part("p4"), part("p5", "application/pdf", "a.pdf"), part("p6"))
        val sticker = IMessageExtra(stickerParts = listOf("p2"))
        assertEquals(listOf(0..1, 3..4), MediaStacks.runs(parts, sticker))
        val segments = MediaStacks.segments(parts, sticker)
        assertEquals(listOf("Stack", "Single", "Stack", "Single", "Single"), segments.map { it::class.simpleName })
    }

    @Test
    fun aSinglePartIsNoStack() {
        assertTrue(MediaStacks.runs(listOf(part("p0")), none).isEmpty())
        val single = MediaStacks.segments(listOf(part("p0")), none).single()
        assertEquals("p0", (single as MediaStacks.Segment.Single).part.partId)
        assertTrue(MediaStacks.runs(emptyList(), none).isEmpty())
    }

    @Test
    fun aLivePhotosMovingPartAndALocationCardAreNeverInARun() {
        val parts = listOf(part("p0"), part("p1", "video/quicktime", "IMG.MOV"), part("p2"))
        val live = IMessageExtra(live = mapOf("p0" to "p1"))
        assertTrue(MediaStacks.runs(parts, live).isEmpty())
        val row = row(parts, live)
        assertEquals(listOf("p0", "p2"), row.shownMedia().map { it.partId })
        assertEquals(listOf(0..1), MediaStacks.runs(row.shownMedia(), live))
        assertEquals("p1", row.motionOf("p0")?.partId)
        val location = listOf(part("p0"), part("p1", "text/x-vlocation", "G-CL.loc.vcf"), part("p2"))
        assertTrue(MediaStacks.runs(location, none).isEmpty())
    }

    @Test
    fun twoOrThreeAreACollageFourOrMoreAStack() {
        assertEquals(MediaStacks.Style.COLLAGE, MediaStacks.styleOf(2))
        assertEquals(MediaStacks.Style.COLLAGE, MediaStacks.styleOf(3))
        assertEquals(MediaStacks.Style.STACK, MediaStacks.styleOf(4))
        assertEquals(MediaStacks.Style.STACK, MediaStacks.styleOf(10))
        assertEquals(3, MediaStacks.cardsDrawn(3))
        assertEquals(3, MediaStacks.cardsDrawn(9))
        assertEquals(2, MediaStacks.cardsDrawn(2))
        // Each card a little askew, alternately one way and the other, 3 to 5 degrees.
        val tilts = (0 until 3).map { MediaStacks.collageRotation(it, 3) }
        assertTrue(tilts.all { kotlin.math.abs(it) in 3f..5f })
        assertTrue(tilts[0] < 0 && tilts[1] > 0 && tilts[2] < 0)
        assertEquals(200, MediaStacks.thumbSide(400))
    }

    @Test
    fun theCardsFitTheRoomTheyTake() {
        for ((count, style) in listOf(2 to MediaStacks.Style.COLLAGE, 3 to MediaStacks.Style.COLLAGE, 5 to MediaStacks.Style.STACK)) {
            val p = MediaStackView.placementOf(count, style, side = 300, density = 2f)
            assertEquals(MediaStacks.cardsDrawn(count), p.cards.size)
            for (c in p.cards) assertTrue("$count $style $c", c.dx >= 0 && c.dy >= 0 && c.dx + 300 <= p.width && c.dy + 300 <= p.height)
        }
        val stack = MediaStackView.placementOf(4, MediaStacks.Style.STACK, side = 300, density = 2f)
        // The top card (the last) sits level at the corner; those behind it offset.
        assertEquals(MediaStackView.Card(0, 0, 0f), stack.cards.last())
        assertTrue(stack.cards.first().dx > 0)
    }

    @Test
    fun aTapbackShowsOnThePhotoItIsOn() {
        val parts = listOf(part("p0"), part("p1"), part("p2"))
        val extra = IMessageExtra(tapbackParts = mapOf("+15551234567" to 2))
        val r = row(parts, extra).copy(reactions = listOf(ReactionDto("❤️", listOf("+15551234567", "me"))))
        assertEquals(mapOf("p2" to listOf("❤️")), MediaStacks.tapbacksOn(r, extra, parts))
        assertTrue(MediaStacks.tapbacksOn(r, none, parts).isEmpty())
    }

    private fun row(parts: List<MediaRow>, extra: IMessageExtra) = MessageRow(
        id = "imsg:G", conversationId = "imsg:c", tmpId = "", senderId = "", senderName = "", isOutgoing = false, timestampMs = 1,
        direction = "in", status = "", statusCode = 0, errorText = "", transport = "iMessage", text = "", subject = "", replyToId = "",
        reactions = emptyList(), localState = "", media = parts, updatedAt = 0, extra = extra.toJson(),
    )
}
