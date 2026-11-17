package com.lightdecoder.relay

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The JSON the Go bridge sends (contract §4) parses into the Kotlin DTOs, and
 * missing, null or unexpected fields fall back to defaults instead of failing.
 */
@RunWith(AndroidJUnit4::class)
class DtoTest {

    @Test
    fun parsesAnIncomingStickerMessage() {
        val key = ByteArray(32) { it.toByte() }
        val json = """
            {"id":"msg-77","tmpId":"","conversationId":"conv-3","senderId":"5","senderName":"Sam",
             "fromMe":false,"timestampMs":1731700000123,"direction":"in","status":"INCOMING_COMPLETE",
             "statusCode":100,"errorText":"","transport":"RCS","subject":"","text":"look at this",
             "parts":[
               {"partId":"p0","kind":"text","text":"look at this","mediaId":"","key":"","thumbMediaId":"",
                "thumbKey":"","mime":"","fileName":"","size":0,"width":0,"height":0,"inlineData":"","needsFullSize":false},
               {"partId":"p1","kind":"media","text":"","mediaId":"MEDIA-1","key":"${b64(key)}",
                "thumbMediaId":"THUMB-1","thumbKey":"${b64(ByteArray(32) { 9 })}","mime":"image/heic",
                "fileName":"sticker.heic","size":48211,"width":512,"height":512,"inlineData":"","needsFullSize":false}
             ],
             "replyToId":"msg-70","reactions":[{"emoji":"❤️","participantIds":["1","5"],"fromMe":true}],
             "isOld":false,"mmsPendingDownload":false}
        """.trimIndent()
        val m = MessageDto.fromJson(JSONObject(json))
        assertEquals("msg-77", m.id)
        assertEquals("conv-3", m.conversationId)
        assertEquals(1731700000123L, m.timestampMs)
        assertEquals(100, m.statusCode)
        assertEquals("RCS", m.transport)
        assertEquals("msg-70", m.replyToId)
        assertEquals(2, m.parts.size)
        assertEquals(1, m.mediaParts.size)
        val media = m.mediaParts.single()
        assertEquals("MEDIA-1", media.mediaId)
        assertArrayEquals(key, media.key)
        assertEquals(32, media.thumbKey.size)
        assertEquals(48211L, media.size)
        assertEquals("image/heic", media.mime)
        assertEquals(0, m.parts[0].key.size)
        assertEquals(listOf(ReactionDto("❤️", listOf("1", "5"), fromMe = true)), m.reactions)
        assertFalse(m.isDeleted)
    }

    @Test
    fun messageRoundTripsThroughJson() {
        val original = MessageDto(
            id = "m",
            conversationId = "c",
            text = "hi",
            parts = listOf(PartDto("p", kind = "media", mediaId = "M", key = byteArrayOf(1, 2, 3), inlineData = byteArrayOf(4))),
            reactions = listOf(ReactionDto("👍")),
        )
        val back = MessageDto.fromJson(original.toJson())
        assertEquals(original.copy(parts = emptyList()), back.copy(parts = emptyList()))
        assertArrayEquals(byteArrayOf(1, 2, 3), back.parts[0].key)
        assertArrayEquals(byteArrayOf(4), back.parts[0].inlineData)
        assertEquals(0, MessageDto.fromJson(original.toJson(includeInlineData = false)).parts[0].inlineData.size)
    }

    @Test
    fun missingAndNullFieldsBecomeDefaults() {
        val m = MessageDto.fromJson(JSONObject("""{"id":"x","conversationId":null,"parts":null,"reactions":null,"text":null}"""))
        assertEquals("x", m.id)
        assertEquals("", m.conversationId)
        assertEquals("", m.text)
        assertEquals("in", m.direction)
        assertEquals("UNKNOWN", m.transport)
        assertTrue(m.parts.isEmpty())
        assertTrue(m.reactions.isEmpty())

        val c = ConversationDto.fromJson(JSONObject("""{"id":"c","participants":null,"otherParticipantIds":[null,"2"]}"""))
        assertEquals("inbox", c.folder)
        assertEquals("UNKNOWN", c.type)
        assertTrue(c.participants.isEmpty())
        assertEquals(listOf("2"), c.otherParticipantIds)

        val p = PartDto.fromJson(JSONObject("""{"partId":"p","kind":"media","key":"%%% not base64 %%%"}"""))
        assertEquals(0, p.key.size)
        assertTrue(p.isMedia)
    }

    @Test
    fun parsesAConversation() {
        val json = """
            {"id":"conv-3","name":"Sam Lee","isGroup":false,"type":"RCS","folder":"archive","statusCode":2,
             "unread":true,"pinned":true,"readOnly":false,"muted":false,"lastMessageTs":1731700000123,
             "latestMessageId":"msg-77","snippet":"look at this","snippetFromMe":false,"snippetSender":"Sam",
             "avatarColor":"#FF8800","defaultOutgoingId":"1",
             "participants":[
               {"id":"1","name":"","firstName":"","number":"+15550100","formattedNumber":"(555) 0100","isMe":true,
                "color":"","contactId":"","visible":true},
               {"id":"5","name":"Sam Lee","firstName":"Sam","number":"+15550199","formattedNumber":"(555) 0199",
                "isMe":false,"color":"#FF8800","contactId":"42","visible":true}
             ],
             "otherParticipantIds":["5"]}
        """.trimIndent()
        val c = ConversationDto.fromJson(JSONObject(json))
        assertEquals("Sam Lee", c.name)
        assertTrue(c.isRcs)
        assertEquals("archive", c.folder)
        assertEquals(Folder.ARCHIVE, Folder.fromWire(c.folder))
        assertTrue(c.unread && c.pinned)
        assertEquals(2, c.participants.size)
        assertTrue(c.participants[0].isMe)
        assertEquals("Sam", c.participants[1].firstName)
        assertEquals(listOf("5"), c.otherParticipantIds)
        assertEquals(c, ConversationDto.fromJson(c.toJson()))
        assertEquals(listOf(c), ConversationDto.listFromJson(JSONArray("[$json]")))
        assertNull(Folder.fromWire("deleted"))
    }

    @Test
    fun parsesPagesAndCursors() {
        val page = MessagePage.fromJson(
            JSONObject(
                """{"messages":[{"id":"a","conversationId":"c","timestampMs":1},{"id":"b","conversationId":"c","timestampMs":2}],
                   "cursor":{"lastItemId":"a","lastItemTimestampMs":1},"total":120}"""
            )
        )
        assertEquals(listOf("a", "b"), page.messages.map { it.id })
        assertEquals(Cursor("a", 1), page.cursor)
        assertEquals(120, page.total)
        assertEquals(Cursor("a", 1), Cursor.fromJson(page.cursor!!.toJson()))

        assertNull(MessagePage.fromJson(JSONObject("""{"messages":[],"cursor":null,"total":0}""")).cursor)
        assertNull(MessagePage.fromJson(JSONObject("""{"messages":null}""")).cursor)
        assertNull(Cursor.fromJson(JSONObject("""{"lastItemId":"","lastItemTimestampMs":5}""")))
    }

    @Test
    fun parsesTheSmallerObjects() {
        assertEquals(
            RelayStatus(paired = true, connected = false, account = "me@example.com", phoneId = "ph", sessionId = "s"),
            RelayStatus.fromJson(JSONObject("""{"paired":true,"connected":false,"account":"me@example.com","phoneId":"ph","sessionId":"s"}""")),
        )
        assertEquals(
            PairingChallenge("🦊", "https://fonts.gstatic.com/x/emoji.svg", "me@example.com"),
            PairingChallenge.fromJson(JSONObject("""{"emoji":"🦊","emojiUrl":"https://fonts.gstatic.com/x/emoji.svg","account":"me@example.com"}""")),
        )
        assertEquals(
            TypingDto("c", "5", "+1555", typing = true),
            TypingDto.fromJson(JSONObject("""{"conversationId":"c","participantId":"5","number":"+1555","typing":true}""")),
        )
        assertEquals(
            SettingsDto(
                sims = listOf(SimDto("1", "+15550100", "Work", rcsEnabled = true)),
                rcsEnabled = true,
                sendReadReceipts = true,
                showTypingIndicators = false,
                isDefaultSmsApp = true,
            ),
            SettingsDto.fromJson(
                JSONObject(
                    """{"sims":[{"participantId":"1","number":"+15550100","label":"Work","rcsEnabled":true}],
                       "rcsEnabled":true,"sendReadReceipts":true,"showTypingIndicators":false,"isDefaultSmsApp":true}"""
                )
            ),
        )
        assertEquals(
            SendResult("t1", ok = false, status = "FAILURE_2", code = ""),
            SendResult.fromJson(JSONObject("""{"tmpId":"t1","ok":false,"status":"FAILURE_2","code":""}""")),
        )
        assertEquals(
            RelayStateEvent("reconnecting", "connection_closed", "listen error"),
            RelayStateEvent.fromJson(JSONObject("""{"state":"reconnecting","code":"connection_closed","detail":"listen error"}""")),
        )
        assertEquals(
            listOf(ContactDto("5", "Sam Lee", "+15550199", "(555) 0199", "#FF8800", "42")),
            ContactDto.listFromJson(
                JSONArray("""[{"id":"5","name":"Sam Lee","number":"+15550199","formattedNumber":"(555) 0199","color":"#FF8800","contactId":"42"}]""")
            ),
        )
        val thumbs = ThumbnailDto.listFromJson(JSONArray("""[{"participantId":"5","data":"${b64(byteArrayOf(-1, -40, -1))}"}]"""))
        assertEquals("5", thumbs.single().participantId)
        assertArrayEquals(byteArrayOf(-1, -40, -1), thumbs.single().data)
    }

    @Test
    fun relayExceptionParsesBridgeErrors() {
        val e = RelayException.fromThrowable(Exception("incorrect_emoji: the other emoji was chosen"))
        assertEquals(RelayException.INCORRECT_EMOJI, e.code)
        assertEquals("the other emoji was chosen", e.message)

        assertEquals("not_paired", RelayException.fromThrowable(Exception("not_paired")).code)
        assertEquals("pairing_timeout", RelayException.fromThrowable(Exception("pairing_timeout: ")).code)

        val unknown = RelayException.fromThrowable(IllegalStateException("weird: something +15550100 said"))
        assertEquals(RelayException.INTERNAL, unknown.code)
        assertEquals("IllegalStateException", unknown.message)
        assertTrue(
            "unchecked text is not kept anywhere, not even as the cause",
            generateSequence<Throwable>(unknown) { it.cause }.none { it.message.orEmpty().contains("+15550100") },
        )
        assertEquals(RelayException.INTERNAL, RelayException.fromThrowable(RuntimeException()).code)

        val same = RelayException("not_connected", "")
        assertSame(same, RelayException.fromThrowable(same))
        assertEquals("RelayException(not_connected)", same.toString())
    }

    private fun b64(bytes: ByteArray) = java.util.Base64.getEncoder().encodeToString(bytes)
}
