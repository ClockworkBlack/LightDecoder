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
        )
        val up = trip(DirectArchive.Update.Upsert(conv, msg)) as DirectArchive.Update.Upsert
        assertEquals(conv, up.conversation)
        assertEquals(msg.copy(parts = msg.parts), up.message)
        assertEquals(DirectArchive.Update.Remove("imsg:G1"), trip(DirectArchive.Update.Remove("imsg:G1")))
        assertEquals(
            DirectArchive.Update.Receipt(listOf("imsg:A", "imsg:B"), DirectArchive.STATUS_READ),
            trip(DirectArchive.Update.Receipt(listOf("imsg:A", "imsg:B"), DirectArchive.STATUS_READ)),
        )
        assertEquals(DirectArchive.Update.Edit("imsg:G1", "fixed"), trip(DirectArchive.Update.Edit("imsg:G1", "fixed")))
        assertEquals(DirectArchive.Update.Ignore, trip(DirectArchive.Update.Ignore))
    }

    @Test
    fun targetsSurviveTheTripWithAndWithoutAGroup() {
        val one = DirectWire.Target(listOf("tel:+1", "tel:+2"), null, null, "tel:+1")
        assertEquals(one, DirectWire.Target.fromJson(JSONObject(one.toJson().toString())))
        val group = DirectWire.Target(listOf("tel:+1", "tel:+2", "mailto:a@b.c"), "Trip", "G-1", "tel:+1")
        assertEquals(group, DirectWire.Target.fromJson(JSONObject(group.toJson().toString())))
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
