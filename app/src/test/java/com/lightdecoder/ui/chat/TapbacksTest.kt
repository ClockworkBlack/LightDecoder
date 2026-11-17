package com.lightdecoder.ui.chat

import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MediaRow
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.relay.ReactionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** An iPhone's reactions, which reach other phones as texts, shown on the message they react to. */
class TapbacksTest {

    private var clock = 1_000L

    private fun message(id: String, text: String = "", outgoing: Boolean = false, mime: String? = null, sender: String = "p1") = MessageRow(
        id = id, conversationId = "c1", tmpId = "", senderId = if (outgoing) "" else sender, senderName = "",
        isOutgoing = outgoing, timestampMs = clock++, direction = if (outgoing) "out" else "in", status = "", statusCode = 0,
        errorText = "", transport = "RCS", text = text, subject = "", replyToId = "",
        reactions = emptyList(), localState = LocalState.SYNCED,
        media = if (mime == null) emptyList() else listOf(media(id, mime)), updatedAt = 0,
    )

    private fun media(id: String, mime: String) = MediaRow(
        messageId = id, partId = "0", mediaId = "m$id", key = ByteArray(0), thumbMediaId = "", thumbKey = ByteArray(0),
        mime = mime, fileName = "", size = 10, width = 0, height = 0, inlineData = ByteArray(0), needsFullSize = false,
        localPath = "", thumbPath = "", fullSizeRequested = false, stickerChecked = false,
    )

    private fun List<MessageRow>.reactionsOf(id: String) = first { it.id == id }.reactions.map { it.emoji to it.participantIds.size }

    @Test
    fun theTextsAreRead() {
        fun said(text: String) = Tapbacks.parse(text)
        assertEquals(Tapbacks.Tapback("👍", false, Tapbacks.Target.Media(Tapbacks.MediaKind.PICTURE)), said("Liked an image"))
        assertEquals(Tapbacks.Tapback("❤️", false, Tapbacks.Target.Media(Tapbacks.MediaKind.PICTURE)), said("Loved an image"))
        assertEquals(Tapbacks.Tapback("😂", false, Tapbacks.Target.Text("see you at 6")), said("Laughed at “see you at 6”"))
        assertEquals("‼️", said("Emphasized \"ok\"")!!.emoji)
        assertEquals("❓", said("Questioned “where?”")!!.emoji)
        assertEquals("👎", said("Disliked a video")!!.emoji)
        assertEquals(Tapbacks.Target.Media(Tapbacks.MediaKind.VIDEO), said("Disliked a video")!!.target)
        assertEquals(Tapbacks.Tapback("🔥", false, Tapbacks.Target.Text("new shoes")), said("Reacted 🔥 to “new shoes”"))
        assertEquals(Tapbacks.Tapback("❤️", true, Tapbacks.Target.Text("hi")), said("Removed a heart from “hi”"))
        assertEquals(Tapbacks.Tapback("‼️", true, Tapbacks.Target.Media(Tapbacks.MediaKind.PICTURE)), said("Removed an exclamation from an image"))
        assertEquals(Tapbacks.Tapback("🔥", true, Tapbacks.Target.Text("x")), said("Removed 🔥 from “x”"))
        // Ordinary messages are left alone.
        assertNull(said("Loved it, thanks"))
        assertNull(said("Liked an image of the beach you sent"))
        assertNull(said("Reacted happily to “the news”"))
        assertNull(said("Laughed at you"))
        assertNull(said("hello"))
    }

    @Test
    fun aReactionToAPictureGoesOnThePicture() {
        // As in the user's thread: a picture they sent, then two tapbacks on it.
        val thread = listOf(
            message("a", "earlier", outgoing = true),
            message("pic", outgoing = true, mime = "image/png"),
            message("t1", "Liked an image"),
            message("t2", "Loved an image"),
        )
        val shown = Tapbacks.apply(thread)
        assertEquals("the texts are not shown", listOf("a", "pic"), shown.map { it.id })
        // One tapback per person, as on an iPhone: the later one replaces the first.
        assertEquals(listOf("❤️" to 1), shown.reactionsOf("pic"))
    }

    @Test
    fun aReactionToTextGoesOnThatText() {
        val thread = listOf(
            message("a", "see you at 6", outgoing = true),
            message("b", "Bring snacks", outgoing = true),
            message("t1", "Laughed at “see you at 6”"),
            message("t2", "Emphasized “Bring snacks”"),
        )
        val shown = Tapbacks.apply(thread)
        assertEquals(listOf("a", "b"), shown.map { it.id })
        assertEquals(listOf("😂" to 1), shown.reactionsOf("a"))
        assertEquals(listOf("‼️" to 1), shown.reactionsOf("b"))
    }

    @Test
    fun aLongMessageIsMatchedByItsStart() {
        val long = "This is a long message that an iPhone will not quote in full when someone reacts to it, so it is cut"
        val thread = listOf(message("a", long, outgoing = true), message("t", "Loved “This is a long message that an iPhone will not…”"))
        assertEquals(listOf("❤️" to 1), Tapbacks.apply(thread).reactionsOf("a"))
    }

    @Test
    fun aRemovedReactionComesOff() {
        val thread = listOf(
            message("a", "hi", outgoing = true),
            message("t1", "Loved “hi”"),
            message("t2", "Removed a heart from “hi”"),
        )
        val shown = Tapbacks.apply(thread)
        assertEquals(listOf("a"), shown.map { it.id })
        assertTrue(shown.reactionsOf("a").isEmpty())
    }

    @Test
    fun theLatestMatchingMessageIsTheOne() {
        val thread = listOf(
            message("p1", outgoing = true, mime = "image/jpeg"),
            message("p2", outgoing = true, mime = "image/jpeg"),
            message("t", "Liked an image"),
        )
        val shown = Tapbacks.apply(thread)
        assertTrue(shown.reactionsOf("p1").isEmpty())
        assertEquals(listOf("👍" to 1), shown.reactionsOf("p2"))
    }

    @Test
    fun inAGroupEachPersonCounts() {
        val thread = listOf(
            message("a", "pizza?", outgoing = true),
            message("t1", "Liked “pizza?”", sender = "p1"),
            message("t2", "Liked “pizza?”", sender = "p2"),
        )
        assertEquals(listOf("👍" to 2), Tapbacks.apply(thread).reactionsOf("a"))
    }

    @Test
    fun theMessagesOwnReactionsStay() {
        val pic = message("pic", outgoing = true, mime = "image/png").copy(reactions = listOf(ReactionDto("😮", listOf("p3"))))
        val shown = Tapbacks.apply(listOf(pic, message("t", "Loved an image")))
        assertEquals(listOf("😮" to 1, "❤️" to 1), shown.reactionsOf("pic"))
    }

    @Test
    fun withNothingToGoOnTheTextStays() {
        // The message is not loaded (or there is none): better the text than nothing.
        val thread = listOf(message("a", "hello", outgoing = true), message("t", "Loved “something else”"), message("u", "Liked an image"))
        assertEquals(listOf("a", "t", "u"), Tapbacks.apply(thread).map { it.id })
        // Our own messages are never taken for tapbacks.
        val mine = listOf(message("a", "hi"), message("b", "Loved “hi”", outgoing = true))
        assertEquals(listOf("a", "b"), Tapbacks.apply(mine).map { it.id })
        // Nothing to do: the same list.
        val plain = listOf(message("a", "hi"))
        assertSame(plain, Tapbacks.apply(plain))
    }
}
