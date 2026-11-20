package com.lightdecoder.imessage.direct

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.ParticipantDto
import java.io.File
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.DeleteTarget
import uniffi.rust_bridge.IndexedMessagePart
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MessagePart
import uniffi.rust_bridge.MessageType
import uniffi.rust_bridge.NormalMessage
import uniffi.rust_bridge.PermanentDeleteMessage
import uniffi.rust_bridge.PushChatMessage
import uniffi.rust_bridge.ScheduleMode
import uniffi.rust_bridge.TextFlags
import uniffi.rust_bridge.TextFormat

/**
 * iOS 18's Send Later through the iMessage library: the time goes to Apple with the message (Apple
 * sends it then), the copy here shows it until then, and it can be taken back or sent now.
 */
@RunWith(AndroidJUnit4::class)
class SendLaterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ArchiveDb
    private lateinit var repo: ArchiveRepository
    private val me = "tel:+15550000001"
    private val bob = "tel:+15559876543"

    @Before
    fun setUp() {
        context.deleteDatabase(DB)
        db = ArchiveDb(context, DB)
        repo = ArchiveRepository.forTesting(db, ChatService.IMESSAGE)
        ArchiveRepository.installForTesting(repo)
        IMessageManager.installForTesting(context, repo)
        DirectPrefs.setRegistered(context, true)
        DirectLibrary.REQUIRED.forEach { File(DirectLibrary.dir(context), it).apply { parentFile?.mkdirs(); writeText("") } }
        repo.upsertConversation(
            ConversationDto(
                id = "imsg:$bob", type = IMessageManager.TRANSPORT, defaultOutgoingId = me,
                participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = "+15559876543", name = "Bob", number = "+15559876543")),
            ),
        )
    }

    @After
    fun tearDown() {
        DirectPrefs.setRegistered(context, false)
        DirectLink.resetForTesting()
        IMessageManager.resetForTesting()
        ArchiveRepository.resetForTesting()
        db.close()
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Test
    fun aTimeMustBeAMinuteToFourteenDaysAhead() {
        val now = 1_700_000_000_000L
        assertEquals(SendLater.Check.TOO_SOON, SendLater.check(now, now))
        assertEquals(SendLater.Check.TOO_SOON, SendLater.check(now + 59_000, now))
        assertEquals(SendLater.Check.OK, SendLater.check(now + 60_000, now))
        assertEquals(SendLater.Check.OK, SendLater.check(now + SendLater.MAX_AHEAD_MS, now))
        assertEquals(SendLater.Check.TOO_FAR, SendLater.check(now + SendLater.MAX_AHEAD_MS + 1, now))
    }

    @Test
    fun theTimeCrossesToTheLibraryProcess() {
        val target = DirectWire.Target(listOf(bob), null, null, me)
        val at = 1_800_000_000_000L
        val r = DirectWire.SendRequest.fromJson(JSONObject(DirectWire.send(target, "later", null, scheduledMs = at).toString()))
        assertEquals(at, r.scheduledMs)
        assertNull(DirectWire.SendRequest.fromJson(JSONObject(DirectWire.send(target, "now", null).toString())).scheduledMs)
        val u = JSONObject(DirectWire.unschedule(target, "G-1").toString())
        assertEquals("G-1", u.getString("guid"))
        assertEquals(target, DirectWire.Target.fromJson(u.getJSONObject("target")))
    }

    @Test
    fun aScheduledTextGoesToAppleWithItsTimeAndShowsUntilThen() {
        val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())
        var local: MessageRow? = null
        val link = DirectLink.installForTesting(context) { what, request, _ ->
            asked += what to request
            local = repo.messages("imsg:$bob").firstOrNull { it.isLocal }
            DirectWire.sent("SL1", System.currentTimeMillis())
        }
        val at = System.currentTimeMillis() + 3 * 60 * 60 * 1000L
        link.sendText("imsg:$bob", "Happy birthday!", null, scheduledAt = at)
        waitFor("the scheduled message") { repo.message("imsg:SL1") != null }
        val sent = DirectWire.SendRequest.fromJson(asked.single { it.first == DirectWire.SEND }.second)
        assertEquals(at, sent.scheduledMs)
        assertEquals("Happy birthday!", sent.text)
        // The local copy while it went, and the confirmed one: at its time, waiting for it.
        assertEquals(at, IMessageExtra.parse(local!!.extra).scheduledAt)
        assertEquals(at, local!!.timestampMs)
        val row = repo.message("imsg:SL1")!!
        assertEquals(at, SendLater.scheduledAt(row))
        assertEquals(at, row.timestampMs)
        assertTrue(SendLater.pending(row, System.currentTimeMillis()))
        assertTrue(link.canCancelScheduled(row))
        // Until it goes it is taken back, not edited, unsent or tapped back.
        assertFalse(DirectLink.editable(row))
        assertFalse(DirectLink.unsendable(row))
        assertFalse(DirectLink.reactable(row))
        // Its time reached, or delivered: an ordinary sent message.
        assertFalse(SendLater.pending(row, at + 1))
        link.apply(DirectArchive.Update.Receipt(listOf("imsg:SL1"), DirectArchive.STATUS_DELIVERED, bob))
        assertFalse(SendLater.pending(repo.message("imsg:SL1")!!, System.currentTimeMillis()))
    }

    @Test
    fun cancelSendLaterAsksAppleAndTheMessageGoes() {
        val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())
        val link = DirectLink.installForTesting(context) { what, request, _ ->
            asked += what to request
            if (what == DirectWire.SEND) DirectWire.sent("SL2", System.currentTimeMillis()) else DirectWire.ok()
        }
        link.sendText("imsg:$bob", "see you", null, scheduledAt = System.currentTimeMillis() + 60 * 60 * 1000L)
        waitFor("the scheduled message") { repo.message("imsg:SL2") != null }
        runBlocking { link.cancelScheduled("imsg:SL2") }
        val (what, request) = asked.last()
        assertEquals(DirectWire.UNSCHEDULE, what)
        assertEquals("SL2", request.getString("guid"))
        assertEquals(listOf(bob), DirectWire.Target.fromJson(request.getJSONObject("target")).participants)
        assertNull(repo.message("imsg:SL2"))
        // Nothing left to take back.
        try {
            runBlocking { link.cancelScheduled("imsg:SL2") }
            fail("cancelled twice")
        } catch (e: DirectEngine.ImessageError) {
            // Expected.
        }
    }

    @Test
    fun sendNowTakesItBackAndSendsItAtOnce() {
        val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())
        var sends = 0
        val link = DirectLink.installForTesting(context) { what, request, _ ->
            asked += what to request
            if (what == DirectWire.SEND) DirectWire.sent("SN${++sends}", System.currentTimeMillis()) else DirectWire.ok()
        }
        link.sendText("imsg:$bob", "now please", null, effect = "com.apple.MobileSMS.expressivesend.impact", scheduledAt = System.currentTimeMillis() + 60 * 60 * 1000L)
        waitFor("the scheduled message") { repo.message("imsg:SN1") != null }
        runBlocking { link.sendScheduledNow("imsg:SN1") }
        waitFor("sent again") { repo.message("imsg:SN2") != null }
        assertEquals(listOf(DirectWire.SEND, DirectWire.UNSCHEDULE, DirectWire.SEND), asked.map { it.first })
        val again = DirectWire.SendRequest.fromJson(asked.last().second)
        assertNull("not scheduled", again.scheduledMs)
        assertEquals("now please", again.text)
        assertEquals("com.apple.MobileSMS.expressivesend.impact", again.effect)
        assertNull(repo.message("imsg:SN1"))
        assertFalse(SendLater.pending(repo.message("imsg:SN2")!!, System.currentTimeMillis()))
    }

    private fun normal(text: String, scheduled: ScheduleMode?) = NormalMessage(
        listOf(IndexedMessagePart(MessagePart.Text(text, TextFormat.Flags(TextFlags(false, false, false, false))), null, null)),
        null, null, null, MessageType.IMessage, null, null, null, false, scheduled, null,
    )

    @Test
    fun scheduledOnAnotherOfTheUsersDevicesShowsSoAndGoesWhenTakenBackThere() {
        val at = System.currentTimeMillis() + 2 * 60 * 60 * 1000L
        val inst = MessageInst(
            "OD1", me, ConversationData(listOf(me, bob), null, null, null), PushChatMessage.Message(normal("from my iPhone", ScheduleMode(at.toULong(), true))),
            1_700_000_000_000UL, null, false, false, null,
        )
        val u = DirectArchive.map(inst, setOf(me), Handles::display, DirectTexts(context)) as DirectArchive.Update.Upsert
        assertEquals(at, IMessageExtra.parse(u.message.extra).scheduledAt)
        assertEquals(at, u.message.timestampMs)
        // Someone else's message says nothing of it.
        val theirs = inst.copy(id = "OD2", sender = bob)
        assertEquals(0L, IMessageExtra.parse((DirectArchive.map(theirs, setOf(me), Handles::display, DirectTexts(context)) as DirectArchive.Update.Upsert).message.extra).scheduledAt)
        // Taken back on the user's iPhone: deleted here too.
        val gone = MessageInst(
            "PD1", me, ConversationData(emptyList(), null, null, null),
            PushChatMessage.PermanentDelete(PermanentDeleteMessage(DeleteTarget.Messages(listOf("OD1")), true)),
            1_700_000_000_000UL, null, false, false, null,
        )
        // Only while it is still waiting for its time.
        assertEquals(DirectArchive.Update.Remove("imsg:OD1", scheduledOnly = true), DirectArchive.map(gone, setOf(me), Handles::display, DirectTexts(context)))
        val link = DirectLink.installForTesting(context) { _, _, _ -> DirectWire.ok() }
        link.apply(u)
        assertNotNull(repo.message("imsg:OD1"))
        link.apply(DirectArchive.map(gone, setOf(me), Handles::display, DirectTexts(context)))
        assertNull(repo.message("imsg:OD1"))
    }

    private companion object {
        const val DB = "send-later-test.db"
    }
}
