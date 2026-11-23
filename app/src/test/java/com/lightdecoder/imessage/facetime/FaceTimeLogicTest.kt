package com.lightdecoder.imessage.facetime

import com.lightdecoder.imessage.direct.DirectArchive
import com.lightdecoder.imessage.direct.DirectWire
import com.lightdecoder.imessage.direct.IMessageExtra
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.rust_bridge.FaceTimeEvent
import uniffi.rust_bridge.FtLetMeInRequest
import uniffi.rust_bridge.FtMember
import uniffi.rust_bridge.FtMode
import uniffi.rust_bridge.FtSessionInfo

/**
 * The plain parts of FaceTime: what is taken of the library's events and call list, what crosses
 * between the two processes ([DirectWire]) and comes back the same, a call's row and its words,
 * the phone's addresses, the audio route's choice, and when a chat has its FaceTime buttons.
 */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class FaceTimeLogicTest {

    // --- the library's events and call list -----------------------------------------------------

    @Test
    fun onlyEventsOfARealCallAreTakenAndOnlyWhatAOneToOneCallNeeds() {
        assertEquals(CallEvent.Join(GUID, ALICE, ring = true), CallEvent.of(FaceTimeEvent.JoinEvent(GUID, 1uL, ALICE, true)))
        assertEquals(CallEvent.Invite(GUID), CallEvent.of(FaceTimeEvent.Ring(GUID)))
        assertEquals(CallEvent.Invite(GUID), CallEvent.of(FaceTimeEvent.AddMembers(GUID, emptyList(), true)))
        assertNull("added without a ring", CallEvent.of(FaceTimeEvent.AddMembers(GUID, emptyList(), false)))
        assertEquals(CallEvent.Decline(GUID, ALICE), CallEvent.of(FaceTimeEvent.Decline(GUID, ALICE)))
        assertEquals(CallEvent.Leave(GUID, ALICE), CallEvent.of(FaceTimeEvent.LeaveEvent(GUID, 1uL, ALICE)))
        assertEquals(CallEvent.Elsewhere(GUID), CallEvent.of(FaceTimeEvent.RespondedElsewhere(GUID)))
        // Nothing a call's flow may depend on, and nothing of FaceTime links (never answered: no link is ever made here).
        assertNull(CallEvent.of(FaceTimeEvent.Connected(GUID)))
        assertNull(CallEvent.of(FaceTimeEvent.Disconnected(GUID)))
        assertNull(CallEvent.of(FaceTimeEvent.LinkChanged(GUID)))
        assertNull(CallEvent.of(FaceTimeEvent.RemoveMembers(GUID, emptyList())))
        assertNull(CallEvent.of(FaceTimeEvent.LetMeIn(FtLetMeInRequest(ByteArray(0), "p", "r", null, ByteArray(0), null, null))))
        // An id that is no call's: lower case, too long, not a UUID.
        for (bad in listOf(GUID.lowercase(), GUID + "A", "", "x".repeat(10_000), "../" + GUID)) {
            assertNull(bad.take(40), CallEvent.of(FaceTimeEvent.JoinEvent(bad, 1uL, ALICE, true)))
            assertNull(CallEvent.of(FaceTimeEvent.Ring(bad)))
            assertNull(CallEvent.of(FaceTimeEvent.LeaveEvent(bad, 1uL, ALICE)))
        }
        // An address that is none says nothing (it is not taken as it is).
        assertEquals(CallEvent.Join(GUID, "", ring = true), CallEvent.of(FaceTimeEvent.JoinEvent(GUID, 1uL, "temp:guest", true)))
        assertEquals(CallEvent.Decline(GUID, ""), CallEvent.of(FaceTimeEvent.Decline(GUID, "javascript:alert(1)")))
        assertEquals("JoinEvent", CallEvent.kindOf(FaceTimeEvent.JoinEvent(GUID, 1uL, ALICE, true)))
    }

    @Test
    fun theLibrarysCallListIsReadWithLimits() {
        val list = listOf(
            FtSessionInfo(GUID, listOf(ME), listOf(FtMember("Alice", ALICE), FtMember(null, ME)), 1_700_000_000_000uL, null, FtMode.INCOMING, true, true),
            FtSessionInfo("not-a-call", listOf(ME), emptyList(), null, null, null, false, false),
            FtSessionInfo(OTHER, listOf("nonsense", ME), emptyList(), ULong.MAX_VALUE, null, FtMode.MISSED, false, false),
        )
        val read = CallEvent.sessions(list)
        assertEquals(listOf(GUID, OTHER), read.map { it.guid })
        assertEquals(CallSessionInfo(GUID, listOf(ME), listOf(ALICE, ME), 1_700_000_000_000L, video = true, active = true), read[0])
        assertEquals("only addresses are the user's own", listOf(ME), read[1].myHandles)
        assertEquals(Long.MAX_VALUE, read[1].startMs)
        assertEquals(CallEvent.MAX_SESSIONS, CallEvent.sessions(List(2_000) { list[0] }).size)
    }

    // --- between the two processes ---------------------------------------------------------------

    @Test
    fun aCallsRowCrossesToTheMainProcessAndBackTheSame() {
        val call = DirectArchive.Update.Call(GUID, ALICE, outgoing = true, video = true, startedMs = 1_700_000_000_000, durationMs = 300_000, outcome = "answered", quiet = false, conversationId = "imsg:tel:+15551234567")
        assertEquals(call, DirectWire.decode(JSONObject(DirectWire.encodeUpdate(call).toString())))
        val missed = DirectArchive.Update.Call(GUID, ALICE, outgoing = false, video = false, startedMs = 5, durationMs = 0, outcome = "missed", quiet = true)
        assertEquals(missed, DirectWire.decode(JSONObject(DirectWire.encode(missed).toString())))
        // What is no call's is not taken: an id that is none, an address that is none, an outcome there is not.
        fun decoded(change: (JSONObject) -> Unit) = DirectWire.decode(DirectWire.encode(call).also(change))
        assertEquals(DirectArchive.Update.Ignore, decoded { it.put("guid", GUID.lowercase()) })
        assertEquals(DirectArchive.Update.Ignore, decoded { it.put("peer", "temp:guest") })
        assertEquals(DirectArchive.Update.Ignore, decoded { it.put("outcome", "won") })
        assertEquals(DirectArchive.Update.Ignore, decoded { it.remove("guid") })
        // Times as a call could have them.
        val odd = decoded { it.put("durationMs", Long.MAX_VALUE).put("startedMs", -5) } as DirectArchive.Update.Call
        assertEquals(DirectWire.MAX_CALL_MS, odd.durationMs)
        assertEquals(0L, odd.startedMs)
        assertEquals("", (decoded { it.put("conversationId", "x".repeat(5_000)) } as DirectArchive.Update.Call).conversationId)
    }

    @Test
    fun aLogLineCrossesCutToSize() {
        val note = DirectArchive.Update.Note("call#3f9a1c: ringing (video)")
        assertEquals(note, DirectWire.decode(JSONObject(DirectWire.encode(note).toString())))
        val long = DirectWire.decode(DirectWire.encode(DirectArchive.Update.Note("x".repeat(5_000)))) as DirectArchive.Update.Note
        assertEquals(DirectWire.MAX_NOTE_CHARS, long.line.length)
        assertEquals(DirectArchive.Update.Ignore, DirectWire.decode(JSONObject().put("kind", "note").put("line", "  ")))
    }

    @Test
    fun whoCanBeCalledIsAskedAndAnsweredWithAddressesOnly() {
        assertEquals(19, DirectWire.FT_CAPABLE)
        assertEquals(20, DirectWire.CALLER)
        val request = DirectWire.CapableRequest.fromJson(JSONObject(DirectWire.faceTimeCapable(listOf(ALICE, "mailto:a@b.c"), ME).toString()))
        assertEquals(DirectWire.CapableRequest(listOf(ALICE, "mailto:a@b.c"), ME), request)
        // Anything that is no address is left out; never more than a handful at once; no sender is none.
        val odd = DirectWire.CapableRequest.fromJson(
            JSONObject().put("targets", JSONArray(listOf(ALICE, "temp:guest", "", ALICE, "https://example.com") + List(50) { "tel:+1555000%04d".format(it) })).put("from", "me"),
        )
        assertEquals(ALICE, odd.targets.first())
        assertTrue(odd.targets.size <= DirectWire.MAX_FT_TARGETS)
        assertTrue(odd.targets.all { CallIds.handle(it) != null })
        assertEquals(odd.targets.distinct(), odd.targets)
        assertNull(odd.from)
        assertEquals(emptyList<String>(), DirectWire.CapableRequest.fromJson(JSONObject()).targets)
        // The answer.
        assertEquals(listOf(ALICE), DirectWire.capableOf(JSONObject(DirectWire.capable(listOf(ALICE)).toString())))
        assertEquals(emptyList<String>(), DirectWire.capableOf(DirectWire.error("not connected")))
        assertEquals(listOf(ALICE), DirectWire.capableOf(JSONObject().put("capable", JSONArray(listOf("everyone", ALICE)))))
    }

    @Test
    fun whoIsCallingIsAskedAndAnsweredWithLimits() {
        assertEquals(ALICE, DirectWire.callerOf(JSONObject(DirectWire.caller(ALICE).toString())))
        assertNull(DirectWire.callerOf(JSONObject().put("handle", "temp:guest")))
        assertNull(DirectWire.callerOf(JSONObject()))
        val info = CallerInfo("Alice Martin", known = true, conversationId = "imsg:tel:+15551234567", photo = "/data/user/0/com.lightdecoder/files/contact-photos/a.jpg")
        assertEquals(info, DirectWire.callerInfo(JSONObject(DirectWire.callerReply(info).toString())))
        assertEquals(CallerInfo("", known = false), DirectWire.callerInfo(JSONObject(DirectWire.callerReply(CallerInfo("", false)).toString())))
        // A name of any size is one short line; an id or a path of any size is none.
        val odd = DirectWire.callerInfo(
            JSONObject().put("name", "A\nB" + "c".repeat(2_000)).put("known", "yes").put("conversationId", "x".repeat(10_000)).put("photo", "p".repeat(10_000)),
        )
        assertEquals(CallIds.MAX_NAME_CHARS, odd.name.length)
        assertFalse(odd.name.contains('\n'))
        assertNull(odd.conversationId)
        assertNull(odd.photo)
    }

    // --- a call's row ------------------------------------------------------------------------------

    private val words = object : CallRows.Texts {
        override fun kind(video: Boolean) = if (video) "FaceTime Video" else "FaceTime Audio"
        override fun answered(kind: String, duration: String) = "$kind · $duration"
        override fun missed(kind: String) = "Missed $kind"
        override fun cancelled(kind: String) = "$kind · Cancelled"
        override fun declined(kind: String) = "$kind · Declined"
        override fun unanswered(kind: String) = "$kind · No answer"
        override fun elsewhere(kind: String) = "$kind · Answered on another device"
        override fun failed(kind: String) = "$kind · Call failed"
        override fun seconds(n: Int) = "$n sec"
        override fun minutes(n: Int) = "$n min"
        override fun hours(h: Int, m: Int) = "$h hr $m min"
    }

    @Test
    fun aCallsRowSaysWhatTheCallWas() {
        assertEquals("FaceTime Video · 5 min", CallRows.text(words, video = true, Outcome.ANSWERED, 5 * 60_000 + 20_000))
        assertEquals("FaceTime Audio · 12 sec", CallRows.text(words, video = false, Outcome.ANSWERED, 12_400))
        assertEquals("FaceTime Audio · 0 sec", CallRows.text(words, video = false, Outcome.ANSWERED, -5))
        assertEquals("FaceTime Video · 1 hr 5 min", CallRows.text(words, video = true, Outcome.ANSWERED, 65 * 60_000 + 59_000))
        assertEquals("FaceTime Audio · 59 min", CallRows.text(words, video = false, Outcome.ANSWERED, 59 * 60_000 + 59_999))
        assertEquals("Missed FaceTime Video", CallRows.text(words, video = true, Outcome.MISSED, 0))
        assertEquals("Missed FaceTime Audio", CallRows.text(words, video = false, Outcome.MISSED, 0))
        assertEquals("FaceTime Video · Cancelled", CallRows.text(words, video = true, Outcome.CANCELLED, 0))
        assertEquals("FaceTime Audio · Declined", CallRows.text(words, video = false, Outcome.DECLINED, 0))
        assertEquals("FaceTime Video · No answer", CallRows.text(words, video = true, Outcome.UNANSWERED, 0))
        assertEquals("FaceTime Audio · Answered on another device", CallRows.text(words, video = false, Outcome.ELSEWHERE, 0))
        assertEquals("FaceTime Video · Call failed", CallRows.text(words, video = true, Outcome.FAILED, 0))
        for (outcome in Outcome.entries) assertEquals(outcome, Outcome.of(outcome.wire))
        assertNull(Outcome.of("won"))
        assertNull(Outcome.of(null))
    }

    @Test
    fun aCallsRowIsNoIMessageAndKeepsWhatItNeedsToCallBack() {
        val id = CallRows.messageId(GUID)
        assertTrue(CallRows.isCallRow(id))
        assertFalse("Apple knows no message by it", DirectArchive.isImessage(id))
        assertFalse(CallRows.isCallRow(DirectArchive.messageId(GUID)))
        val extra = IMessageExtra(call = IMessageExtra.CallInfo(GUID, outgoing = true, video = true, outcome = "answered", durationMs = 300_000))
        assertEquals(extra, IMessageExtra.parse(extra.toJson()))
        assertEquals(IMessageExtra.CallInfo(GUID, false, false, "missed", 0), IMessageExtra.parse(IMessageExtra(call = IMessageExtra.CallInfo(GUID, false, false, "missed")).toJson()).call)
        assertNull("a message that is no call has none", IMessageExtra.parse("""{"effect":"x"}""").call)
        assertNull(IMessageExtra.parse("""{"call":{"guid":"","outcome":"missed"}}""").call)
        assertNull(IMessageExtra.parse("""{"call":{"guid":"$GUID"}}""").call)
    }

    @Test
    fun theSameCallAgainNeverRewritesItsRowUnlessItSaysMore() {
        // An old ring handed over again (missed) does not undo what is known.
        assertFalse(CallRows.replaces(Outcome.ANSWERED, Outcome.MISSED))
        assertFalse(CallRows.replaces(Outcome.DECLINED, Outcome.MISSED))
        assertFalse(CallRows.replaces(Outcome.MISSED, Outcome.MISSED))
        assertFalse(CallRows.replaces(Outcome.ANSWERED, Outcome.ANSWERED))
        assertFalse(CallRows.replaces(Outcome.DECLINED, Outcome.CANCELLED))
        // A call noted as missed that was talked in after all.
        assertTrue(CallRows.replaces(Outcome.MISSED, Outcome.ANSWERED))
        assertTrue(CallRows.replaces(Outcome.MISSED, Outcome.DECLINED))
        assertTrue(CallRows.replaces(Outcome.UNANSWERED, Outcome.ANSWERED))
        // A row under that id that is no call's is left alone.
        assertFalse(CallRows.replaces(null, Outcome.ANSWERED))
    }

    // --- the phone's addresses ---------------------------------------------------------------------

    @Test
    fun onlyTheAddressesACallCanUseAreToldToTheLibrary() {
        val interfaces = listOf(
            LocalAddresses.Iface("lo", up = true, loopback = true, ipv4 = listOf("127.0.0.1")),
            LocalAddresses.Iface("wlan0", up = true, loopback = false, ipv4 = listOf("192.168.1.20", "224.0.0.1", "0.0.0.0", "nonsense", "300.1.1.1")),
            LocalAddresses.Iface("rmnet_data1", up = true, loopback = false, ipv4 = listOf("10.20.30.40")),
            LocalAddresses.Iface("rmnet_data2", up = false, loopback = false, ipv4 = listOf("10.9.9.9")),
            LocalAddresses.Iface("dummy0", up = true, loopback = false, ipv4 = listOf("10.1.1.1")),
            LocalAddresses.Iface("tun0", up = true, loopback = false, ipv4 = listOf("10.8.0.2")),
            LocalAddresses.Iface("ETH0", up = true, loopback = false, ipv4 = listOf("172.16.0.5")),
            LocalAddresses.Iface("v4-rmnet_data0", up = true, loopback = false, ipv4 = listOf("192.0.0.4")),
        )
        val picked = LocalAddresses.pick(interfaces, "wlan0")
        assertEquals(listOf("192.168.1.20", "10.20.30.40", "172.16.0.5", "192.0.0.4"), picked.all)
        assertEquals("192.168.1.20", picked.default)
        // On mobile data through a translation, the default is that one's address.
        assertEquals("192.0.0.4", LocalAddresses.pick(interfaces, "rmnet_data0").default)
        // No active network known: the first there is.
        assertEquals("192.168.1.20", LocalAddresses.pick(interfaces, null).default)
        // Nothing usable: an empty list is told (nothing breaks, the call just has nothing to offer).
        val none = LocalAddresses.pick(interfaces.take(1), "wlan0")
        assertEquals(LocalAddresses.Picked("", emptyList()), none)
        assertTrue(LocalAddresses.pick(List(100) { LocalAddresses.Iface("wlan$it", true, false, listOf("10.0.0.$it")) }, null).all.size <= LocalAddresses.MAX_ADDRESSES)
    }

    // --- the audio route, the buttons, who can be called -------------------------------------------

    @Test
    fun aCallsSoundGoesWhereTheUserPickedElseToAHeadsetElseByTheKindOfCall() {
        val phone = listOf(Route.EARPIECE, Route.SPEAKER)
        assertEquals(Route.EARPIECE, CallRoutePolicy.target(phone, video = false, picked = null))
        assertEquals(Route.SPEAKER, CallRoutePolicy.target(phone, video = true, picked = null))
        assertEquals(Route.SPEAKER, CallRoutePolicy.target(phone, video = false, picked = Route.SPEAKER))
        assertEquals(Route.BLUETOOTH, CallRoutePolicy.target(phone + Route.BLUETOOTH + Route.WIRED, video = true, picked = null))
        assertEquals(Route.WIRED, CallRoutePolicy.target(phone + Route.WIRED, video = true, picked = null))
        assertEquals("a pick that is gone is no pick", Route.EARPIECE, CallRoutePolicy.target(phone, video = false, picked = Route.BLUETOOTH))
        assertEquals("a tablet has no earpiece", Route.SPEAKER, CallRoutePolicy.target(listOf(Route.SPEAKER), video = false, picked = null))
        assertEquals(Route.SPEAKER, CallRoutePolicy.target(emptyList(), video = false, picked = null))
    }

    @Test
    fun aChatHasItsFaceTimeButtonsOnlyWhenACallCouldGoFromIt() {
        assertTrue(FaceTimeButtons.shown(faceTimeOn = true, reachable = true, oneToOne = true, capable = true))
        assertTrue("not known yet whether they can be called: shown", FaceTimeButtons.shown(true, true, true, null))
        assertFalse("Apple says they cannot be called", FaceTimeButtons.shown(true, true, true, false))
        assertFalse("FaceTime is off", FaceTimeButtons.shown(false, true, true, true))
        assertFalse("not through the iMessage library", FaceTimeButtons.shown(true, false, true, true))
        assertFalse("a group", FaceTimeButtons.shown(true, true, false, true))
    }

    @Test
    fun whoCanBeCalledIsRememberedForAWhile() {
        val cache = CapabilityCache(yesMs = 1_000, noMs = 100, max = 3)
        assertNull(cache.get("a", 0))
        cache.put("a", true, 0)
        cache.put("b", false, 0)
        assertEquals(true, cache.get("a", 999))
        assertEquals(false, cache.get("b", 99))
        assertNull("who could not be called is asked about again sooner", cache.get("b", 100))
        assertNull(cache.get("a", 1_000))
        assertNull("a clock set back is no reason to keep it", cache.apply { put("c", true, 500) }.get("c", 100))
        // No more than so many people.
        for (i in 0 until 10) cache.put("p$i", true, 0)
        assertNull(cache.get("p0", 1))
        assertEquals(true, cache.get("p9", 1))
        cache.clear()
        assertNull(cache.get("p9", 1))
    }

    @Test
    fun theRingLimiterCountsRingsAndNoticesPerCaller() {
        val limiter = RingLimiter(minGapMs = 10, maxRingsPerHour = 2, maxNoticesPerHour = 3, maxCallers = 2, windowMs = 1_000)
        assertEquals(RingLimiter.Verdict.RING, limiter.ring("a", "g1", 0))
        assertEquals("the same call again", RingLimiter.Verdict.DROP, limiter.ring("a", "g1", 5))
        assertEquals("another call too soon", RingLimiter.Verdict.MISSED_ONLY, limiter.ring("a", "g2", 5))
        assertEquals(RingLimiter.Verdict.RING, limiter.ring("a", "g3", 20))
        assertEquals("the hour's rings are used up", RingLimiter.Verdict.MISSED_ONLY, limiter.ring("a", "g4", 40))
        assertTrue(limiter.notice("a", "g5", 60))
        assertFalse("and its notices", limiter.notice("a", "g6", 80))
        assertEquals(RingLimiter.Verdict.DROP, limiter.ring("a", "g7", 100))
        // Someone else is counted by themselves.
        assertEquals(RingLimiter.Verdict.RING, limiter.ring("b", "h1", 100))
        // After the window, again.
        assertEquals(RingLimiter.Verdict.RING, limiter.ring("a", "g8", 1_200))
        // Only so many callers are remembered: the one longest quiet is forgotten, never the one asking.
        assertEquals(RingLimiter.Verdict.RING, limiter.ring("c", "i1", 1_300))
        assertEquals(RingLimiter.Verdict.RING, limiter.ring("d", "j1", 1_300))
        assertEquals(RingLimiter.Verdict.RING, limiter.ring("e", "k1", 1_300))
    }

    private companion object {
        const val GUID = "AAAAAAAA-0000-4000-8000-000000000001"
        const val OTHER = "CCCCCCCC-0000-4000-8000-000000000003"
        const val ME = "tel:+15550000001"
        const val ALICE = "tel:+15551234567"
    }
}
