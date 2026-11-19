package com.lightdecoder.imessage

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
class ImessageWireTest {

    /** Through JSON text, as the Bundle carries it. */
    private fun trip(u: ImessageArchive.Update) = ImessageWire.decode(JSONObject(ImessageWire.encode(u).toString()))

    @Test
    fun everyKindOfChangeSurvivesTheTrip() {
        val conv = ConversationDto(
            id = "imsg:tel:+15550000002", name = "+15550000002", type = ImessageArchive.TRANSPORT, defaultOutgoingId = "tel:+15550000001",
            participants = listOf(
                ParticipantDto(id = "tel:+15550000002", name = "+15550000002", number = "+15550000002"),
                ParticipantDto(id = "tel:+15550000001", isMe = true),
            ),
            otherParticipantIds = listOf("tel:+15550000002"),
        )
        val msg = MessageDto(
            id = "imsg:G1", conversationId = conv.id, senderId = "tel:+15550000002", timestampMs = 1234, direction = "in",
            status = ImessageArchive.STATUS_RECEIVED, transport = ImessageArchive.TRANSPORT, text = "hi \"there\"\nnew line ✓",
            parts = listOf(PartDto(partId = "0", kind = "text", text = "hi \"there\"\nnew line ✓")), replyToId = "imsg:G0",
        )
        val up = trip(ImessageArchive.Update.Upsert(conv, msg)) as ImessageArchive.Update.Upsert
        assertEquals(conv, up.conversation)
        assertEquals(msg.copy(parts = msg.parts), up.message)
        assertEquals(ImessageArchive.Update.Remove("imsg:G1"), trip(ImessageArchive.Update.Remove("imsg:G1")))
        assertEquals(
            ImessageArchive.Update.Receipt(listOf("imsg:A", "imsg:B"), ImessageArchive.STATUS_READ),
            trip(ImessageArchive.Update.Receipt(listOf("imsg:A", "imsg:B"), ImessageArchive.STATUS_READ)),
        )
        assertEquals(ImessageArchive.Update.Edit("imsg:G1", "fixed"), trip(ImessageArchive.Update.Edit("imsg:G1", "fixed")))
        assertEquals(ImessageArchive.Update.Ignore, trip(ImessageArchive.Update.Ignore))
    }

    @Test
    fun targetsSurviveTheTripWithAndWithoutAGroup() {
        val one = ImessageWire.Target(listOf("tel:+1", "tel:+2"), null, null, "tel:+1")
        assertEquals(one, ImessageWire.Target.fromJson(JSONObject(one.toJson().toString())))
        val group = ImessageWire.Target(listOf("tel:+1", "tel:+2", "mailto:a@b.c"), "Trip", "G-1", "tel:+1")
        assertEquals(group, ImessageWire.Target.fromJson(JSONObject(group.toJson().toString())))
    }

    @Test
    fun requestsCarryWhatTheOtherSideReads() {
        val t = ImessageWire.Target(listOf("tel:+1", "tel:+2"), null, null, "tel:+1")
        val send = JSONObject(ImessageWire.send(t, "hello", null).toString())
        assertEquals("hello", send.getString("text"))
        assertTrue(send.isNull("replyGuid"))
        val unsend = JSONObject(ImessageWire.unsend(t, "GUID-1", 0).toString())
        assertEquals("GUID-1", unsend.getString("guid"))
        assertEquals(0, unsend.getInt("part"))
        assertEquals(t, ImessageWire.Target.fromJson(unsend.getJSONObject("target")))
    }

    @Test
    fun errorsAreTellableFromAnswers() {
        assertEquals("nope", ImessageWire.errorOf(ImessageWire.error("nope")))
        assertNull(ImessageWire.errorOf(ImessageWire.ok()))
        assertNull(ImessageWire.errorOf(ImessageWire.sent("G", 1)))
        assertFalse(ImessageWire.sent("G", 1).has("error"))
    }

    @Test
    fun onlyTheImessageProcessIsRecognised() {
        assertTrue(ImessageProcess.isImessageProcess("com.lightdecoder:imessage", "com.lightdecoder"))
        assertFalse(ImessageProcess.isImessageProcess("com.lightdecoder", "com.lightdecoder"))
        assertFalse(ImessageProcess.isImessageProcess("com.lightdecoder:discord", "com.lightdecoder"))
        assertFalse(ImessageProcess.isImessageProcess(null, "com.lightdecoder"))
    }
}
