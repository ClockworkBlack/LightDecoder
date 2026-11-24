package com.lightdecoder.imessage.facetime

import com.lightdecoder.imessage.facetime.CallRules.Effect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping between the call engine and Android's Telecom ([TelecomCalls], SPEC2 B3.3), with a
 * fake Telecom: only a call that rings or is placed is handed over (never a silenced, limited or
 * blocked one, which never ring); a headset's answer is the screen's answer with the camera off;
 * hold ends the call; a refusal or no call from Telecom in 10 s leaves the audio to the app's own
 * route; every end disconnects Telecom's call.
 */
class TelecomCallsTest {

    private val asked = ArrayList<TelecomCalls.Ask>()
    private val did = ArrayList<String>()
    private val timers = ArrayList<Pair<Long, () -> Unit>>()
    private var refuse = false
    private var throws = false
    private var on = true
    private var locked = false

    private val platform = object : TelecomCalls.Platform {
        override fun addIncoming(ask: TelecomCalls.Ask): Boolean {
            if (throws) throw SecurityException("no")
            asked += ask
            return !refuse
        }

        override fun placeOutgoing(ask: TelecomCalls.Ask): Boolean {
            if (throws) throw SecurityException("no")
            asked += ask
            return !refuse
        }
    }

    private val actions = object : TelecomCalls.Actions {
        var opens = true
        override fun answer(guid: String): Boolean {
            did += "answer $guid"
            return opens
        }
        override fun decline(guid: String) {
            did += "decline $guid"
        }
        override fun hangUp(guid: String) {
            did += "hangUp $guid"
        }
        override fun showIncoming(guid: String) {
            did += "notice $guid"
        }
    }

    private val telecom = TelecomCalls(platform, actions, enabled = { on }, now = { 0L }, after = { ms, block -> timers += ms to block }, locked = { locked })

    private class Line : TelecomCalls.Line {
        val told = ArrayList<String>()
        override fun markActive() {
            told += "active"
        }
        override fun end(cause: TelecomCalls.EndCause) {
            told += "end $cause"
        }
        override fun moveTo(route: Route): Boolean {
            told += "route $route"
            return true
        }
    }

    private fun ringing(guid: String = IN, known: Boolean = true, group: Boolean = false, video: Boolean = true) =
        Call(guid, ALICE, if (known) "Alice Martin" else "", video = video, outgoing = false, phase = Phase.RINGING_IN, startedMs = 0, known = known, group = group)

    private fun placed(guid: String = OUT) =
        Call(guid, ALICE, "Alice Martin", video = true, outgoing = true, phase = Phase.RINGING_OUT, startedMs = 0)

    private fun entry(guid: String, outcome: Outcome, outgoing: Boolean = false) =
        CallLogEntry(guid, ALICE, outgoing, video = true, startedMs = 0, durationMs = 0, outcome = outcome)

    @Test
    fun onlyARingAndAPlacedCallAreHandedOverAndWithTheAuthenticatedCallerOnly() {
        // What a silenced, limited, blocked or missed-only ring does: a row at most, and a lookup; never handed over.
        telecom.onEffect(Effect.LookupCaller(IN, ALICE))
        telecom.onEffect(Effect.LogCall(entry(IN, Outcome.MISSED).copy(quiet = true)))
        telecom.onEffect(Effect.Note("a FaceTime ring from a blocked caller was dropped"))
        assertTrue(asked.isEmpty())
        telecom.onEffect(Effect.ShowIncoming(ringing()))
        assertEquals(TelecomCalls.Ask(IN, incoming = true, address = ALICE, name = "Alice Martin", video = true), asked.single())
        telecom.onEffect(Effect.CreateCall(placed()))
        assertEquals(TelecomCalls.Ask(OUT, incoming = false, address = ALICE, name = "Alice Martin", video = true), asked.last())
    }

    @Test
    fun aGroupCallPlacedHereIsGivenAFixedLabelAndNoAddressNeverItsChatsNameOrMembers() {
        // The chat's name may be one a member wrote, or its members' names joined; the peer is one member.
        val group = Call(OUT, ALICE, "Bob's Party, Alice Martin", video = true, outgoing = true, phase = Phase.RINGING_OUT, startedMs = 0, group = true)
        telecom.onEffect(Effect.CreateCall(group))
        val ask = asked.single()
        assertEquals(TelecomCalls.Ask(OUT, incoming = false, address = null, name = "Group FaceTime", video = true), ask)
        assertFalse(ask.name!!.contains("Alice"))
        assertFalse(ask.name!!.contains("Bob"))
    }

    @Test
    fun aCallFromAGroupChatThatReachesOnlyOneMemberIsStillGivenTheFixedLabelAndNoAddress() {
        // One member left, or only one callable: the call is one-to-one, but its name is the chat's.
        val one = Call(OUT, ALICE, "Bob's Party", video = false, outgoing = true, phase = Phase.RINGING_OUT, startedMs = 0, fromGroup = true)
        telecom.onEffect(Effect.CreateCall(one))
        assertEquals(TelecomCalls.Ask(OUT, incoming = false, address = null, name = "Group FaceTime", video = false), asked.single())
    }

    @Test
    fun aOneToOneCallPlacedHereIsNamedAsTheChatCallsThePerson() {
        telecom.onEffect(Effect.CreateCall(placed()))
        assertEquals("Alice Martin", asked.single().name)
        assertEquals(ALICE, asked.single().address)
    }

    @Test
    fun aGroupRingIsItsCallersAndAnUnknownCallerOnALockedPhoneIsNotNamed() {
        telecom.onEffect(Effect.ShowIncoming(ringing(group = true)))
        assertEquals("Alice Martin · Group FaceTime", asked.last().name)
        locked = true
        telecom.onEffect(Effect.ShowIncoming(ringing(OTHER, known = false)))
        assertNull(asked.last().address)
        assertNull(asked.last().name)
        locked = false
        telecom.onEffect(Effect.ShowIncoming(ringing(THIRD, known = false)))
        assertEquals(ALICE, asked.last().address)
        assertEquals("+15551234567", asked.last().name)
    }

    @Test
    fun withTelecomOffNothingIsHandedOver() {
        on = false
        telecom.onEffect(Effect.ShowIncoming(ringing()))
        telecom.onEffect(Effect.CreateCall(placed()))
        assertTrue(asked.isEmpty())
        assertFalse(telecom.ownsAudio(IN))
        assertFalse(telecom.attach(IN, true, Line()))
    }

    @Test
    fun aHeadsetsAnswerIsTheScreensAnswerWithTheCameraOffAndNothingElse() {
        telecom.onEffect(Effect.ShowIncoming(ringing(video = true)))
        val line = Line()
        assertTrue(telecom.attach(IN, incoming = true, line))
        assertTrue(telecom.ownsAudio(IN))
        telecom.answered(IN)
        assertEquals(listOf("answer $IN"), did)
        // The screen could not be opened from the background: the ringing notice again, nothing answered.
        actions.opens = false
        telecom.answered(IN)
        assertEquals(listOf("answer $IN", "answer $IN", "notice $IN"), did)
        telecom.rejected(IN)
        assertEquals("decline $IN", did.last())
        telecom.disconnected(IN)
        assertEquals("hangUp $IN", did.last())
        // Nothing for a call Telecom does not have.
        telecom.answered(OTHER)
        telecom.rejected(null)
        assertEquals("hangUp $IN", did.last())
    }

    @Test
    fun holdEndsTheCall() {
        telecom.onEffect(Effect.CreateCall(placed()))
        val line = Line()
        assertTrue(telecom.attach(OUT, incoming = false, line))
        telecom.held(OUT)
        assertEquals(listOf("hangUp $OUT"), did)
    }

    @Test
    fun aRefusalAThrowOrNoCallIn10SecondsLeavesTheAudioToTheAppsOwnRoute() {
        // Refused.
        refuse = true
        telecom.onEffect(Effect.ShowIncoming(ringing()))
        assertFalse(telecom.attach(IN, true, Line()))
        assertFalse(telecom.audioEntered(IN))
        // Thrown.
        refuse = false
        throws = true
        telecom.onEffect(Effect.ShowIncoming(ringing(OTHER)))
        assertFalse(telecom.attach(OTHER, true, Line()))
        // No call from Telecom in time: once the 10 s are up, a late one is refused.
        throws = false
        telecom.onEffect(Effect.CreateCall(placed()))
        assertEquals(TelecomCalls.WAIT_MS, timers.last().first)
        timers.last().second()
        val late = Line()
        assertFalse(telecom.attach(OUT, false, late))
        assertFalse(telecom.ownsAudio(OUT))
        // Telecom's own failure.
        telecom.onEffect(Effect.ShowIncoming(ringing(THIRD)))
        telecom.failed(THIRD)
        assertFalse(telecom.attach(THIRD, true, Line()))
    }

    @Test
    fun theAppsOwnAudioTakesOverWhenTelecomIsNotThereAndNeverBoth() {
        val own = object : CallAudio {
            val log = ArrayList<String>()
            override fun routes() = listOf(Route.EARPIECE, Route.SPEAKER)
            override fun enter(changed: () -> Unit) {
                log += "enter"
            }
            override fun route(route: Route): Boolean {
                log += "route $route"
                return true
            }
            override fun leave() {
                log += "leave"
            }
        }
        var current: String? = IN
        val audio = TelecomAudio(own, telecom) { current }
        // Telecom has the call: the app's own route sets nothing (no mode, no device, no focus).
        telecom.onEffect(Effect.ShowIncoming(ringing()))
        val line = Line()
        telecom.attach(IN, true, line)
        telecom.audioChanged(IN, listOf(Route.EARPIECE, Route.BLUETOOTH), Route.BLUETOOTH)
        audio.enter {}
        assertEquals(listOf(Route.EARPIECE, Route.BLUETOOTH), audio.routes())
        assertTrue(audio.route(Route.EARPIECE))
        assertEquals(listOf("route EARPIECE"), line.told)
        audio.leave()
        assertTrue(own.log.isEmpty())
        // A placed call whose Telecom call has not come yet when the audio is taken: the app's own, and Telecom's late call is refused.
        current = OUT
        telecom.onEffect(Effect.CreateCall(placed()))
        audio.enter {}
        assertEquals(listOf("enter"), own.log)
        assertFalse(telecom.attach(OUT, false, Line()))
        audio.route(Route.SPEAKER)
        audio.leave()
        assertEquals(listOf("enter", "route SPEAKER", "leave"), own.log)
    }

    @Test
    fun everyEndDisconnectsTelecomsCallAndJoinedMakesItActive() {
        // Joined, then hung up: active, then ended.
        telecom.onEffect(Effect.ShowIncoming(ringing()))
        val a = Line()
        telecom.attach(IN, true, a)
        val ringingCall = ringing()
        val active = ringingCall.copy(phase = Phase.ACTIVE, activeSinceMs = 5)
        telecom.onState(ringingCall.copy(phase = Phase.CONNECTING), active)
        assertEquals(listOf("active"), a.told)
        telecom.onEffect(Effect.LogCall(entry(IN, Outcome.ANSWERED)))
        telecom.onState(active, null)
        assertEquals(listOf("active", "end LOCAL"), a.told)
        assertFalse(telecom.ownsAudio(IN))
        // Missed: told as missed.
        telecom.onEffect(Effect.ShowIncoming(ringing(OTHER)))
        val b = Line()
        telecom.attach(OTHER, true, b)
        telecom.onEffect(Effect.LogCall(entry(OTHER, Outcome.MISSED)))
        assertEquals(listOf("end MISSED"), b.told)
        // A placed call nobody answered: told, and its "no answer" screen changes nothing more.
        telecom.onEffect(Effect.CreateCall(placed()))
        val c = Line()
        telecom.attach(OUT, false, c)
        telecom.onEffect(Effect.LogCall(entry(OUT, Outcome.UNANSWERED, outgoing = true)))
        telecom.onState(placed().copy(phase = Phase.UNANSWERED), null)
        assertEquals(listOf("end REMOTE"), c.told)
        // An end with no row (the call simply gone): still disconnected.
        telecom.onEffect(Effect.ShowIncoming(ringing(THIRD)))
        val d = Line()
        telecom.attach(THIRD, true, d)
        telecom.onState(ringing(THIRD), null)
        assertEquals(listOf("end LOCAL"), d.told)
        // Causes as Telecom says them.
        assertEquals(TelecomCalls.EndCause.REJECTED, TelecomCalls.causeOf(entry(IN, Outcome.DECLINED)))
        assertEquals(TelecomCalls.EndCause.REMOTE, TelecomCalls.causeOf(entry(IN, Outcome.DECLINED, outgoing = true)))
        assertEquals(TelecomCalls.EndCause.ANSWERED_ELSEWHERE, TelecomCalls.causeOf(entry(IN, Outcome.ELSEWHERE)))
        assertEquals(TelecomCalls.EndCause.ERROR, TelecomCalls.causeOf(entry(IN, Outcome.FAILED)))
    }

    @Test
    fun telecomsCallIsTakenOnlyForTheCallWaitingForItTheSameWay() {
        assertFalse("never handed over", telecom.attach(IN, true, Line()))
        assertFalse(telecom.attach(null, true, Line()))
        telecom.onEffect(Effect.ShowIncoming(ringing()))
        assertFalse("not the same way", telecom.attach(IN, false, Line()))
        assertTrue(telecom.attach(IN, true, Line()))
        assertFalse("only once", telecom.attach(IN, true, Line()))
    }

    private companion object {
        const val ALICE = "tel:+15551234567"
        const val IN = "BBBBBBBB-0000-4000-8000-000000000002"
        const val OUT = "AAAAAAAA-0000-4000-8000-000000000001"
        const val OTHER = "CCCCCCCC-0000-4000-8000-000000000003"
        const val THIRD = "DDDDDDDD-0000-4000-8000-000000000004"
    }
}
