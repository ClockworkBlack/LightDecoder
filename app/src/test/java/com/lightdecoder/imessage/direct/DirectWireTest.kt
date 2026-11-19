package com.lightdecoder.imessage.direct

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.PartDto
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What crosses between the ":imessage" process and the main process comes out as it went in. */
@RunWith(AndroidJUnit4::class)
class DirectWireTest {

    /** Through JSON text, as the Bundle carries it. */
    private fun trip(u: DirectArchive.Update) = DirectWire.decode(JSONObject(DirectWire.encode(u).toString()))

    @Test
    fun everyKindOfChangeSurvivesTheTrip() {
        val conv = ConversationDto(
            id = "imsg:tel:+15550000002", name = "+15550000002", type = DirectArchive.TRANSPORT, defaultOutgoingId = "tel:+15550000001",
            participants = listOf(
                ParticipantDto(id = "tel:+15550000002", name = "+15550000002", number = "+15550000002"),
                ParticipantDto(id = "tel:+15550000001", isMe = true),
            ),
            otherParticipantIds = listOf("tel:+15550000002"),
        )
        val msg = MessageDto(
            id = "imsg:G1", conversationId = conv.id, senderId = "tel:+15550000002", timestampMs = 1234, direction = "in",
            status = DirectArchive.STATUS_RECEIVED, transport = DirectArchive.TRANSPORT, text = "hi \"there\"\nnew line ✓",
            parts = listOf(PartDto(partId = "0", kind = "text", text = "hi \"there\"\nnew line ✓")), replyToId = "imsg:G0",
            editedAtMs = 99, extra = IMessageExtra(parts = mapOf(0 to "hi"), effect = "com.apple.MobileSMS.expressivesend.loud").toJson(),
        )
        val up = trip(DirectArchive.Update.Upsert(conv, msg)) as DirectArchive.Update.Upsert
        assertEquals(conv, up.conversation)
        assertEquals(msg.copy(parts = msg.parts), up.message)
        assertEquals(DirectArchive.Update.Remove("imsg:G1"), trip(DirectArchive.Update.Remove("imsg:G1")))
        assertEquals(
            DirectArchive.Update.Receipt(listOf("imsg:A", "imsg:B"), DirectArchive.STATUS_READ),
            trip(DirectArchive.Update.Receipt(listOf("imsg:A", "imsg:B"), DirectArchive.STATUS_READ)),
        )
        assertEquals(DirectArchive.Update.Edit("imsg:G1", "fixed", 2, 5_000), trip(DirectArchive.Update.Edit("imsg:G1", "fixed", 2, 5_000)))
        val kinds = listOf(
            DirectArchive.Update.Reaction("imsg:G1", "+15550000002", "❤️", true, false, 7_000),
            DirectArchive.Update.Reaction("imsg:G1", "+15550000001", "🦊", false, true, 8_000),
            DirectArchive.Update.Unsent("imsg:G1", 1, false, "Bob"),
            DirectArchive.Update.Unsent("imsg:G1", 0, true, ""),
            DirectArchive.Update.Typing("imsg:tel:+15550000002", "+15550000002", true),
            DirectArchive.Update.Typing("imsg:group:G", "a@b.c", false),
            DirectArchive.Update.ReadElsewhere("imsg:G1"),
            DirectArchive.Update.Failed("imsg:G1", "Not delivered"),
        )
        for (u in kinds) assertEquals(u, trip(u))
        assertEquals(DirectArchive.Update.Ignore, trip(DirectArchive.Update.Ignore))
        assertEquals(DirectArchive.Update.Ignore, DirectWire.decode(JSONObject().put("kind", "from-a-newer-version")))
    }

    @Test
    fun anOlderEditWithoutPartOrTimeIsPartZero() {
        val old = JSONObject().put("kind", "edit").put("messageId", "imsg:G1").put("text", "fixed")
        assertEquals(DirectArchive.Update.Edit("imsg:G1", "fixed", 0, 0), DirectWire.decode(old))
    }

    @Test
    fun targetsSurviveTheTripWithAndWithoutAGroup() {
        val one = DirectWire.Target(listOf("tel:+1", "tel:+2"), null, null, "tel:+1")
        assertEquals(one, DirectWire.Target.fromJson(JSONObject(one.toJson().toString())))
        val group = DirectWire.Target(listOf("tel:+1", "tel:+2", "mailto:a@b.c"), "Trip", "G-1", "tel:+1", afterGuid = "LAST")
        assertEquals(group, DirectWire.Target.fromJson(JSONObject(group.toJson().toString())))
        // From a side that does not know afterGuid yet.
        val older = JSONObject(one.toJson().toString()).apply { remove("afterGuid") }
        assertNull(DirectWire.Target.fromJson(older).afterGuid)
        assertTrue(DirectWire.Target(listOf("tel:+2"), null, null, "tel:+1").isOneToOne)
        assertFalse(group.isOneToOne)
    }

    @Test
    fun aSendCarriesItsEffectAttachmentsAndVoiceFlag() {
        val t = DirectWire.Target(listOf("tel:+2"), null, null, "tel:+1", "G0")
        val files = listOf(DirectWire.Attachment("/data/x/a.jpg", "image/jpeg", "a.jpg"), DirectWire.Attachment("/data/x/b.m4a", "audio/mp4", "b.m4a"))
        val wire = JSONObject(DirectWire.send(t, "look", "G-R", "0:0:4", "com.apple.MobileSMS.expressivesend.impact", files, voice = true).toString())
        val r = DirectWire.SendRequest.fromJson(wire)
        assertEquals(DirectWire.SendRequest(t, "look", "G-R", "0:0:4", "com.apple.MobileSMS.expressivesend.impact", files, true), r)
        // A plain text, as before.
        val plain = DirectWire.SendRequest.fromJson(JSONObject(DirectWire.send(t, "hi", null).toString()))
        assertNull(plain.replyGuid)
        assertNull(plain.effect)
        assertTrue(plain.attachments.isEmpty())
        assertFalse(plain.voice)
        // The answer: the GUID, Apple's time and the attachment parts as Apple numbers them.
        val parts = listOf(DirectWire.SentPart(1, "p1", "image/jpeg", "a.jpg", 1234), DirectWire.SentPart(2, "p2", "audio/mp4", "b.m4a", 99))
        val reply = DirectWire.SendReply("GUID-9", 42, parts)
        assertEquals(reply, DirectWire.SendReply.fromJson(JSONObject(reply.toJson().toString())))
        assertEquals(emptyList<DirectWire.SentPart>(), DirectWire.SendReply.fromJson(JSONObject().put("guid", "G").put("ts", 1)).parts)
    }

    @Test
    fun theNewRequestsCarryWhatTheEngineReads() {
        val t = DirectWire.Target(listOf("tel:+2"), null, null, "tel:+1")
        val edit = JSONObject(DirectWire.edit(t, "G1", 0, "new text").toString())
        assertEquals(t, DirectWire.Target.fromJson(edit.getJSONObject("target")))
        assertEquals("G1", edit.getString("guid"))
        assertEquals(0, edit.getInt("part"))
        assertEquals("new text", edit.getString("text"))
        val react = JSONObject(DirectWire.react(t, "G1", 0, "❤️", false, "dinner?").toString())
        assertEquals("❤️", react.getString("emoji"))
        assertFalse(react.getBoolean("enable"))
        assertEquals("dinner?", react.getString("targetText"))
        assertTrue(JSONObject(DirectWire.typing(t, true).toString()).getBoolean("typing"))
        assertEquals("G2", JSONObject(DirectWire.read(t, "G2").toString()).getString("guid"))
        val download = JSONObject(DirectWire.download("<plist/>", "/data/x/f.jpg").toString())
        assertEquals("<plist/>", download.getString("descriptor"))
        assertEquals("/data/x/f.jpg", download.getString("path"))
        assertEquals(1234L, DirectWire.downloaded(1234).getLong("size"))
        assertNull(DirectWire.errorOf(DirectWire.downloaded(1)))
        assertEquals("R1", DirectWire.guid("R1").getString("guid"))
        // Every code is its own.
        val codes = listOf(
            DirectWire.SEND, DirectWire.UNSEND, DirectWire.REACH, DirectWire.EDIT, DirectWire.REACT, DirectWire.TYPING,
            DirectWire.READ, DirectWire.DOWNLOAD, DirectWire.EXTENSION, DirectWire.UPDATE, DirectWire.REPLY,
        )
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun requestsCarryWhatTheOtherSideReads() {
        val t = DirectWire.Target(listOf("tel:+1", "tel:+2"), null, null, "tel:+1")
        val send = JSONObject(DirectWire.send(t, "hello", null).toString())
        assertEquals("hello", send.getString("text"))
        assertTrue(send.isNull("replyGuid"))
        val unsend = JSONObject(DirectWire.unsend(t, "GUID-1", 0).toString())
        assertEquals("GUID-1", unsend.getString("guid"))
        assertEquals(0, unsend.getInt("part"))
        assertEquals(t, DirectWire.Target.fromJson(unsend.getJSONObject("target")))
    }

    @Test
    fun anInlineReplyNamesTheAnsweredMessageAndItsText() {
        val t = DirectWire.Target(listOf("tel:+1"), null, null, "tel:+2")
        val send = JSONObject(DirectWire.send(t, "yes", "GUID-A", DirectWire.replyPart("are you coming?")).toString())
        assertEquals("GUID-A", send.getString("replyGuid"))
        // Part 0, from its start to the end of its 15 characters, as OpenBubbles writes it.
        assertEquals("0:0:15", send.getString("replyPart"))
        assertTrue(JSONObject(DirectWire.send(t, "hi", null).toString()).isNull("replyPart"))
    }

    @Test
    fun errorsAreTellableFromAnswers() {
        assertEquals("nope", DirectWire.errorOf(DirectWire.error("nope")))
        assertNull(DirectWire.errorOf(DirectWire.ok()))
        assertNull(DirectWire.errorOf(DirectWire.sent("G", 1)))
        assertFalse(DirectWire.sent("G", 1).has("error"))
    }

    @Test
    fun onlyTheImessageProcessIsRecognised() {
        assertTrue(DirectProcess.isImessageProcess("com.lightdecoder:imessage", "com.lightdecoder"))
        assertFalse(DirectProcess.isImessageProcess("com.lightdecoder", "com.lightdecoder"))
        assertFalse(DirectProcess.isImessageProcess("com.lightdecoder:discord", "com.lightdecoder"))
        assertFalse(DirectProcess.isImessageProcess(null, "com.lightdecoder"))
    }
}
