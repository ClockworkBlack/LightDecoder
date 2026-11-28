package com.lightdecoder.imessage.facetime

import com.lightdecoder.imessage.facetime.CallRules.Effect
import com.lightdecoder.imessage.facetime.CallRules.Input
import com.lightdecoder.imessage.facetime.FakeBridge.Companion.ALICE
import com.lightdecoder.imessage.facetime.FakeBridge.Companion.BOB
import com.lightdecoder.imessage.facetime.FakeBridge.Companion.ME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules of FaceTime calls ([CallRules]), with nothing of a phone: a placed call (rings out,
 * joined when the other side joins, taken back when nobody does), an incoming one (screened, rung,
 * answered only by the user, declined, answered elsewhere, missed), one call at a time, the
 * callers' limits, old rings after a restart, "FaceTime" off and unknown callers silenced.
 */
class CallRulesTest {

    private val rules = CallRules()
    private var state: Call? = null
    private var t = 1_700_000_000_000L
    private val on = FaceTimeSettings(on = true, silenceUnknown = false)

    /** What was to be done, over the whole test. */
    private val done = ArrayList<Effect>()

    private fun send(input: Input): List<Effect> {
        val step = rules.reduce(state, input)
        state = step.state
        done += step.effects
        return step.effects
    }

    private fun session(guid: String, caller: String = ALICE, video: Boolean = false, active: Boolean = true, startMs: Long? = t, others: List<String> = emptyList()) =
        CallSessionInfo(guid, listOf(ME), listOf(caller, ME) + others, startMs, video, active)

    private fun event(
        e: CallEvent,
        sessions: List<CallSessionInfo> = emptyList(),
        settings: FaceTimeSettings = on,
        sinceStartMs: Long = 10 * 60_000L,
    ) = send(Input.Event(e, sessions, setOf(ME), t, sinceStartMs, settings))

    private fun place(guid: String = OUT, video: Boolean = true, settings: FaceTimeSettings = on, phoneBusy: Boolean = false) =
        send(Input.Place(guid, ALICE, "Alice Martin", video, ME, "imsg:tel:+15551234567", null, t, phoneBusy, settings))

    /** A ring from [caller] taken as far as ringing here. */
    private fun ringing(guid: String = IN, caller: String = ALICE, video: Boolean = false) {
        event(CallEvent.Join(guid, caller, ring = true), listOf(session(guid, caller, video)))
        assertEquals("first the main process is asked who it is", Phase.SCREENING, state?.phase)
        send(Input.Caller(guid, CallerInfo("Alice Martin", known = true, conversationId = "imsg:tel:+15551234567"), t, on))
        assertEquals(Phase.RINGING_IN, state?.phase)
    }

    private inline fun <reified T : Effect> List<Effect>.only(): T = filterIsInstance<T>().single()
    private inline fun <reified T : Effect> List<Effect>.has(): Boolean = any { it is T }

    private fun logged(): List<CallLogEntry> = done.filterIsInstance<Effect.LogCall>().map { it.entry }

    // --- a placed call -------------------------------------------------------------------------

    @Test
    fun aPlacedCallRingsOutAndIsJoinedOnlyOnceTheOtherSideJoined() {
        val placed = place()
        assertEquals(Phase.RINGING_OUT, state?.phase)
        assertTrue(state!!.outgoing)
        assertEquals("the library is asked to place it", OUT, placed.only<Effect.CreateCall>().call.guid)
        assertTrue("the caller hears it ring", placed.only<Effect.Ringback>().on)
        assertFalse("nothing is joined by placing", placed.has<Effect.StartNative>())
        // The library says it rings: thirty seconds from now.
        assertEquals(CallRules.RING_OUT_MS, send(Input.Placed(OUT, ok = true, now = t)).only<Effect.StartTimer>().ms)
        // The other person's device joins.
        t += 4_000
        val joined = event(CallEvent.Join(OUT, ALICE, ring = false))
        assertEquals(Phase.CONNECTING, state?.phase)
        assertEquals(OUT, joined.only<Effect.StartNative>().call.guid)
        assertFalse(joined.only<Effect.Ringback>().on)
        // The ring's timer makes way for the join's.
        assertEquals(Phase.CONNECTING, joined.only<Effect.StartTimer>().phase)
        assertEquals(CallRules.CONNECT_MS, joined.only<Effect.StartTimer>().ms)
        // The library joined: nothing more to do, and the join's timer is over.
        t += 2_000
        assertEquals(listOf<Effect>(Effect.StopTimer(OUT)), send(Input.NativeUp(OUT, video = true, now = t)))
        assertEquals(Phase.ACTIVE, state?.phase)
        assertEquals(t, state?.activeSinceMs)
        send(Input.Participants(OUT, 1, t))
        // Five minutes on, the user hangs up.
        t += 5 * 60_000
        val ended = send(Input.HangUp(OUT, t))
        assertNull(state)
        assertEquals(OUT, ended.only<Effect.EndNative>().call.guid)
        val row = ended.only<Effect.LogCall>().entry
        assertEquals(Outcome.ANSWERED, row.outcome)
        assertEquals(5 * 60_000L, row.durationMs)
        assertTrue(row.outgoing && row.video)
        assertEquals("imsg:tel:+15551234567", row.conversationId)
        assertFalse("a joined call is left, not taken back", ended.has<Effect.CancelCall>())
    }

    @Test
    fun aPlacedCallNobodyAnswersIsTakenBackAfterThirtySeconds() {
        place()
        send(Input.Placed(OUT, ok = true, now = t))
        t += CallRules.RING_OUT_MS
        val out = send(Input.Timeout(OUT, Phase.RINGING_OUT, t))
        assertEquals(Phase.UNANSWERED, state?.phase)
        assertEquals(Unanswered.NO_ANSWER, state?.unanswered)
        assertEquals("never joined: taken back", OUT, out.only<Effect.CancelCall>().guid)
        assertEquals(Outcome.UNANSWERED, out.only<Effect.LogCall>().entry.outcome)
        assertFalse(out.only<Effect.Ringback>().on)
        assertFalse("never joined", done.has<Effect.StartNative>())
        // "No answer" stays until the user leaves it, and holds no line meanwhile.
        assertFalse(state!!.live)
        send(Input.HangUp(null, t))
        assertNull(state)
    }

    @Test
    fun aTimerOfAnotherPhaseOrCallDoesNothing() {
        place()
        event(CallEvent.Join(OUT, ALICE, ring = false))
        assertTrue(send(Input.Timeout(OUT, Phase.RINGING_OUT, t)).isEmpty())
        assertEquals(Phase.CONNECTING, state?.phase)
        assertTrue(send(Input.Timeout(IN, Phase.RINGING_IN, t)).isEmpty())
    }

    @Test
    fun hangingUpWhileItRingsTakesItBackAlsoWhenItWasStillBeingPlaced() {
        place()
        val cancelled = send(Input.HangUp(OUT, t))
        assertNull(state)
        assertEquals(OUT, cancelled.only<Effect.CancelCall>().guid)
        assertEquals(Outcome.CANCELLED, cancelled.only<Effect.LogCall>().entry.outcome)
        // The library finishes placing it only now: what it made is taken back again.
        assertEquals(OUT, send(Input.Placed(OUT, ok = true, now = t)).only<Effect.CancelCall>().guid)
        assertNull(state)
    }

    @Test
    fun aDeclineFromTheOtherPersonEndsTheRinging() {
        place()
        val out = event(CallEvent.Decline(OUT, ALICE))
        assertEquals(Phase.UNANSWERED, state?.phase)
        assertEquals(Unanswered.DECLINED, state?.unanswered)
        assertEquals(OUT, out.only<Effect.CancelCall>().guid)
        assertEquals(Outcome.DECLINED, out.only<Effect.LogCall>().entry.outcome)
    }

    @Test
    fun aCallThatCouldNotBePlacedSaysSoAndLeavesNothingBehind() {
        place()
        val out = send(Input.Placed(OUT, ok = false, now = t))
        assertEquals(Unanswered.FAILED, state?.unanswered)
        assertEquals(OUT, out.only<Effect.CancelCall>().guid)
        assertEquals(Outcome.FAILED, out.only<Effect.LogCall>().entry.outcome)
    }

    @Test
    fun onlyTheOtherSidesJoinAnswersAPlacedCall() {
        place()
        // One of the user's own devices joining is not the other person answering.
        assertTrue(event(CallEvent.Join(OUT, ME, ring = false)).isEmpty())
        assertEquals(Phase.RINGING_OUT, state?.phase)
        // A join for some other call is none of this one's.
        event(CallEvent.Join(OTHER, ALICE, ring = false))
        assertEquals(Phase.RINGING_OUT, state?.phase)
        // Nor is a join that does not say whose it is: nobody Apple vouches for answered.
        assertTrue(event(CallEvent.Join(OUT, "", ring = false)).isEmpty())
        assertEquals(Phase.RINGING_OUT, state?.phase)
        event(CallEvent.Join(OUT, ALICE, ring = false))
        assertEquals(Phase.CONNECTING, state?.phase)
    }

    @Test
    fun aCallIsNotPlacedDuringAnotherNorWhileThePhoneIsInACallNorWithFaceTimeOff() {
        assertEquals(Refusal.OFF, place(settings = FaceTimeSettings(on = false)).only<Effect.Refused>().why)
        assertNull(state)
        assertEquals(Refusal.PHONE_BUSY, place(phoneBusy = true).only<Effect.Refused>().why)
        assertNull(state)
        place()
        assertEquals(Refusal.IN_CALL, place(guid = OTHER).only<Effect.Refused>().why)
        assertEquals("the first call is untouched", OUT, state?.guid)
        // Someone who cannot be called at all.
        state = null
        val bad = send(Input.Place(OTHER, "temp:guest", "", false, null, null, null, t, false, on))
        assertEquals(Refusal.NOT_CALLABLE, bad.only<Effect.Refused>().why)
        assertNull(state)
    }

    @Test
    fun aJoinThatFailsTakesAPlacedCallBackAndDeclinesAnAnsweredOne() {
        place()
        event(CallEvent.Join(OUT, ALICE, ring = false))
        val out = send(Input.NativeFailed(OUT, t))
        assertEquals(Unanswered.FAILED, state?.unanswered)
        assertEquals(OUT, out.only<Effect.CancelCall>().guid)
        assertEquals(Outcome.FAILED, out.only<Effect.LogCall>().entry.outcome)
        state = null
        ringing()
        send(Input.Answer(IN, withCamera = false, now = t))
        val incoming = send(Input.NativeFailed(IN, t))
        assertEquals("the caller stops waiting", IN, incoming.only<Effect.DeclineCall>().guid)
        assertFalse(incoming.has<Effect.CancelCall>())
    }

    // --- an incoming call ----------------------------------------------------------------------

    @Test
    fun anIncomingCallIsScreenedThenRingsAndOnlyTheUserAnswersIt() {
        val first = event(CallEvent.Join(IN, ALICE, ring = true), listOf(session(IN, video = true)))
        assertEquals(Phase.SCREENING, state?.phase)
        assertEquals(ALICE, first.only<Effect.LookupCaller>().handle)
        assertFalse("nothing rings before it is known who calls", first.has<Effect.ShowIncoming>())
        val rung = send(Input.Caller(IN, CallerInfo("Alice Martin", known = true, conversationId = "chat-1", photo = "/p.jpg"), t, on))
        assertEquals(Phase.RINGING_IN, state?.phase)
        val shown = rung.only<Effect.ShowIncoming>().call
        assertEquals("Alice Martin", shown.name)
        assertTrue(shown.video)
        assertEquals("chat-1", shown.conversationId)
        assertEquals(CallRules.RING_IN_MS, rung.only<Effect.StartTimer>().ms)
        // More of the same call's events change nothing, and nothing but the user's answer joins it.
        assertTrue(event(CallEvent.Invite(IN), listOf(session(IN, video = true))).isEmpty())
        assertTrue(event(CallEvent.Join(IN, ALICE, ring = true), listOf(session(IN, video = true))).isEmpty())
        assertFalse("no event answers a call", done.has<Effect.StartNative>())
        assertEquals(Phase.RINGING_IN, state?.phase)
        // Answered, with the camera.
        val answered = send(Input.Answer(IN, withCamera = true, now = t))
        assertEquals(Phase.CONNECTING, state?.phase)
        assertTrue(state!!.withCamera)
        assertEquals(IN, answered.only<Effect.StartNative>().call.guid)
        assertEquals(IN, answered.only<Effect.HideIncoming>().guid)
        // The ring's timer makes way for the join's.
        assertEquals(Phase.CONNECTING, answered.only<Effect.StartTimer>().phase)
        assertEquals(CallRules.CONNECT_MS, answered.only<Effect.StartTimer>().ms)
        t += 1_000
        send(Input.NativeUp(IN, video = true, now = t))
        send(Input.Participants(IN, 1, t))
        assertEquals(Phase.ACTIVE, state?.phase)
        // The other side hangs up: nobody is in it any more.
        t += 12_000
        val ended = send(Input.Participants(IN, 0, t))
        assertNull(state)
        assertTrue(ended.has<Effect.EndNative>())
        val row = ended.only<Effect.LogCall>().entry
        assertEquals(Outcome.ANSWERED, row.outcome)
        assertEquals(12_000L, row.durationMs)
        assertFalse(row.outgoing)
        assertEquals(ALICE, row.peer)
    }

    @Test
    fun aVideoCallTakenAsAudioOnlyIsAnsweredWithoutTheCamera() {
        ringing(video = true)
        send(Input.Answer(IN, withCamera = false, now = t))
        assertFalse(state!!.withCamera)
        // And an audio call has no camera to answer with.
        state = null
        t += 60_000
        ringing(guid = OTHER, video = false)
        send(Input.Answer(OTHER, withCamera = true, now = t))
        assertFalse(state!!.withCamera)
    }

    @Test
    fun decliningStopsTheRingingAndTellsTheCaller() {
        ringing()
        val out = send(Input.DeclineByUser(IN, t))
        assertNull(state)
        assertEquals(IN, out.only<Effect.DeclineCall>().guid)
        assertEquals(IN, out.only<Effect.HideIncoming>().guid)
        assertEquals(Outcome.DECLINED, out.only<Effect.LogCall>().entry.outcome)
        assertFalse(done.has<Effect.StartNative>())
        // Declining a call that is not ringing does nothing.
        assertTrue(send(Input.DeclineByUser(IN, t)).isEmpty())
    }

    @Test
    fun answeredOnAnotherDeviceItStopsRingingAndIsNoMissedCall() {
        ringing()
        val out = event(CallEvent.Elsewhere(IN))
        assertNull(state)
        assertEquals(IN, out.only<Effect.HideIncoming>().guid)
        assertEquals(Outcome.ELSEWHERE, out.only<Effect.LogCall>().entry.outcome)
        assertFalse(out.has<Effect.DeclineCall>())
        // Declined on another of the user's devices: the same.
        t += 60_000
        ringing(guid = OTHER)
        val declined = event(CallEvent.Decline(OTHER, ME))
        assertNull(state)
        assertEquals(Outcome.DECLINED, declined.only<Effect.LogCall>().entry.outcome)
        assertTrue(logged().none { it.outcome == Outcome.MISSED })
    }

    @Test
    fun theCallerGivingUpOrThirtySecondsMakeItAMissedCall() {
        ringing()
        val left = event(CallEvent.Leave(IN, ALICE))
        assertNull(state)
        assertEquals(Outcome.MISSED, left.only<Effect.LogCall>().entry.outcome)
        assertEquals(IN, left.only<Effect.HideIncoming>().guid)
        assertFalse("a missed call's notice may sound", left.only<Effect.LogCall>().entry.quiet)
        t += 60_000
        ringing(guid = OTHER)
        t += CallRules.RING_IN_MS
        val timedOut = send(Input.Timeout(OTHER, Phase.RINGING_IN, t))
        assertNull(state)
        assertEquals(Outcome.MISSED, timedOut.only<Effect.LogCall>().entry.outcome)
        assertFalse("nothing is sent for a call that rang out", timedOut.has<Effect.DeclineCall>())
    }

    @Test
    fun answeringACallThatIsGoneSaysSo() {
        assertEquals(Refusal.GONE, send(Input.Answer(IN, true, t)).only<Effect.Refused>().why)
        assertNull(state)
        assertFalse(done.has<Effect.StartNative>())
    }

    @Test
    fun aDeviceLeavingNeverEndsAJoinedCallWhileTheLibrarySaysSomeoneIsInIt() {
        ringing()
        send(Input.Answer(IN, false, t))
        send(Input.NativeUp(IN, false, t))
        send(Input.Participants(IN, 1, t))
        // One of the caller's devices leaves; the call is still on.
        assertTrue(event(CallEvent.Leave(IN, ALICE), listOf(session(IN, active = true))).isEmpty())
        assertEquals(Phase.ACTIVE, state?.phase)
        // Even with the call's list saying nobody is in it, while the joined call still has its other side.
        assertTrue(event(CallEvent.Leave(IN, ALICE), listOf(session(IN, active = false))).isEmpty())
        assertEquals(Phase.ACTIVE, state?.phase)
    }

    @Test
    fun theCallerLeavingWhileItIsBeingJoinedEndsItOnceTheJoinReturns() {
        ringing()
        send(Input.Answer(IN, false, t))
        // The caller hung up as it was answered: the library's list has nobody in it.
        event(CallEvent.Leave(IN, ALICE), listOf(session(IN, active = false)))
        assertEquals(Phase.CONNECTING, state?.phase)
        val out = send(Input.NativeUp(IN, false, t))
        assertNull(state)
        assertTrue("what was joined is left", out.has<Effect.EndNative>())
        assertEquals(Outcome.MISSED, out.only<Effect.LogCall>().entry.outcome)
    }

    @Test
    fun theLibraryGivingUpEndsTheCall() {
        place()
        event(CallEvent.Join(OUT, ALICE, ring = false))
        // While joining: shown as failed.
        val joining = send(Input.Failed(OUT, t))
        assertEquals(Unanswered.FAILED, state?.unanswered)
        assertTrue(joining.has<Effect.EndNative>())
        // In the call: ended, with its time.
        state = null
        place(guid = OTHER)
        event(CallEvent.Join(OTHER, ALICE, ring = false))
        send(Input.NativeUp(OTHER, true, t))
        t += 3_000
        val active = send(Input.Failed(OTHER, t))
        assertNull(state)
        assertTrue(active.has<Effect.EndNative>())
        assertEquals(3_000L, active.only<Effect.LogCall>().entry.durationMs)
    }

    @Test
    fun aJoinThatNeverComesBackIsGivenUp() {
        // A placed call the other side took: the library's join hangs.
        place()
        event(CallEvent.Join(OUT, ALICE, ring = false))
        // The ring's own timer running out now is nothing (it was replaced).
        assertTrue(send(Input.Timeout(OUT, Phase.RINGING_OUT, t)).isEmpty())
        assertEquals(Phase.CONNECTING, state?.phase)
        t += CallRules.CONNECT_MS
        val out = send(Input.Timeout(OUT, Phase.CONNECTING, t))
        assertEquals(Phase.UNANSWERED, state?.phase)
        assertEquals(Unanswered.FAILED, state?.unanswered)
        assertEquals("the join is given up: what it makes is left, or the call taken back", OUT, out.only<Effect.EndNative>().call.guid)
        assertEquals(Outcome.FAILED, out.only<Effect.LogCall>().entry.outcome)
        assertEquals(Phase.UNANSWERED, out.only<Effect.StartTimer>().phase)
        assertTrue(out.has<Effect.Note>())
        // The join coming back after all changes nothing.
        assertTrue(send(Input.NativeUp(OUT, true, t)).isEmpty())
        assertTrue(send(Input.NativeFailed(OUT, t)).isEmpty())
        assertEquals(Phase.UNANSWERED, state?.phase)

        // An answered one, the same.
        state = null
        t += 60_000
        ringing()
        send(Input.Answer(IN, false, t))
        t += CallRules.CONNECT_MS
        val incoming = send(Input.Timeout(IN, Phase.CONNECTING, t))
        assertEquals(Unanswered.FAILED, state?.unanswered)
        assertEquals(IN, incoming.only<Effect.EndNative>().call.guid)
        assertEquals(Outcome.FAILED, incoming.only<Effect.LogCall>().entry.outcome)
    }

    @Test
    fun theJoinsTimerRunningOutInACallThatIsUpDoesNothing() {
        ringing()
        send(Input.Answer(IN, false, t))
        assertEquals(listOf<Effect>(Effect.StopTimer(IN)), send(Input.NativeUp(IN, false, t)))
        send(Input.Participants(IN, 1, t))
        t += CallRules.CONNECT_MS
        assertTrue(send(Input.Timeout(IN, Phase.CONNECTING, t)).isEmpty())
        assertEquals(Phase.ACTIVE, state?.phase)
    }

    @Test
    fun hangingUpWhileItIsBeingJoinedGivesTheJoinUp() {
        place()
        event(CallEvent.Join(OUT, ALICE, ring = false))
        val out = send(Input.HangUp(OUT, t))
        assertNull(state)
        assertEquals(OUT, out.only<Effect.EndNative>().call.guid)
        assertEquals(Outcome.CANCELLED, out.only<Effect.LogCall>().entry.outcome)
        assertTrue("the join's timer is over", out.has<Effect.StopTimer>())
        // The join returning after that changes nothing.
        assertTrue(send(Input.NativeUp(OUT, true, t)).isEmpty())
        assertNull(state)
    }

    // --- one call at a time ----------------------------------------------------------------------

    @Test
    fun aSecondCallDoesNotRingItIsAMissedCall() {
        ringing()
        val second = event(CallEvent.Join(OTHER, BOB, ring = true), listOf(session(IN), session(OTHER, BOB)))
        assertEquals("the first goes on ringing", IN, state?.guid)
        assertEquals(Phase.RINGING_IN, state?.phase)
        val missed = second.only<Effect.LogCall>().entry
        assertEquals(Outcome.MISSED, missed.outcome)
        assertEquals(BOB, missed.peer)
        assertFalse(second.has<Effect.LookupCaller>())
        assertFalse(second.has<Effect.ShowIncoming>())
    }

    // --- a call while in a call: it waits ----------------------------------------------------------

    /** In an audio call with Alice ([IN]), Bob calls ([THIRD]) and is offered over it. */
    private fun bobWaits(video: Boolean = true): List<Effect> {
        ringing()
        send(Input.Answer(IN, false, t))
        send(Input.NativeUp(IN, false, t))
        t += 60_000
        val rung = event(CallEvent.Join(THIRD, BOB, ring = true), listOf(session(IN), session(THIRD, BOB, video)))
        assertEquals("the call goes on", IN, state?.guid)
        assertEquals(Phase.ACTIVE, state?.phase)
        assertEquals("who is it: asked first, as for any ring", THIRD, rung.only<Effect.LookupCaller>().guid)
        assertFalse(rung.has<Effect.LogCall>())
        val offered = send(Input.Caller(THIRD, CallerInfo("Bob Stone", known = true, conversationId = "imsg:tel:+15559876543"), t, on))
        assertEquals(IN, state?.guid)
        assertEquals("Bob Stone", offered.only<Effect.ShowWaiting>().call.name)
        assertEquals(Phase.RINGING_IN, rules.waiting?.phase)
        assertEquals(CallRules.RING_IN_MS, offered.only<Effect.StartTimer>().ms)
        return offered
    }

    @Test
    fun aCallDuringACallWaitsAndEndAndAcceptSwitchesToIt() {
        bobWaits()
        t += 5_000
        val out = send(Input.Answer(THIRD, withCamera = true, now = t))
        // The call with Alice ends (with its time), and Bob's is joined in its place: never a moment with no call.
        assertEquals(THIRD, state?.guid)
        assertEquals(Phase.CONNECTING, state?.phase)
        assertTrue(state!!.withCamera)
        assertNull(rules.waiting)
        assertEquals(IN, out.only<Effect.EndNative>().call.guid)
        val ended = out.only<Effect.LogCall>().entry
        assertEquals(IN, ended.guid)
        assertEquals(Outcome.ANSWERED, ended.outcome)
        assertEquals(65_000L, ended.durationMs)
        assertEquals(THIRD, out.only<Effect.StartNative>().call.guid)
        assertEquals(THIRD, out.only<Effect.HideWaiting>().guid)
        assertTrue("the old call is left before the new one is joined", out.indexOfFirst { it is Effect.EndNative } < out.indexOfFirst { it is Effect.StartNative })
        send(Input.NativeUp(THIRD, video = true, now = t))
        assertEquals(Phase.ACTIVE, state?.phase)
        // What the old call's library still says changes nothing.
        assertTrue(send(Input.Participants(IN, 0, t)).isEmpty())
        assertEquals(THIRD, state?.guid)
    }

    @Test
    fun aWaitingCallIsDeclinedOrGivenUpWhileTheCallGoesOn() {
        bobWaits()
        val declined = send(Input.DeclineByUser(THIRD, t))
        assertEquals(IN, state?.guid)
        assertEquals(Phase.ACTIVE, state?.phase)
        assertNull(rules.waiting)
        assertEquals(THIRD, declined.only<Effect.DeclineCall>().guid)
        assertEquals(THIRD, declined.only<Effect.HideWaiting>().guid)
        assertEquals(Outcome.DECLINED, declined.only<Effect.LogCall>().entry.outcome)
        assertFalse(declined.has<Effect.EndNative>())
        // Another caller: gives up before it is answered. A missed call; the call goes on.
        t += 60_000
        event(CallEvent.Join(OTHER, CAROL, ring = true), listOf(session(IN), session(OTHER, CAROL)))
        send(Input.Caller(OTHER, CallerInfo("Carol", known = true, conversationId = "c"), t, on))
        assertEquals(OTHER, rules.waiting?.guid)
        val left = event(CallEvent.Leave(OTHER, CAROL), listOf(session(IN)))
        assertNull(rules.waiting)
        assertEquals(Outcome.MISSED, left.only<Effect.LogCall>().entry.outcome)
        assertTrue(left.has<Effect.HideWaiting>())
        assertEquals(Phase.ACTIVE, state?.phase)
    }

    @Test
    fun aWaitingCallNobodyAnswersIsAMissedCallAndASecondOneWaitingIsMissedAtOnce() {
        bobWaits()
        // Someone else meanwhile: one waits at a time.
        val second = event(CallEvent.Join(OTHER, CAROL, ring = true), listOf(session(IN), session(THIRD, BOB), session(OTHER, CAROL)))
        assertEquals(Outcome.MISSED, second.only<Effect.LogCall>().entry.outcome)
        assertEquals(THIRD, rules.waiting?.guid)
        t += CallRules.RING_IN_MS
        val out = send(Input.Timeout(THIRD, Phase.RINGING_IN, t))
        assertNull(rules.waiting)
        assertEquals(Outcome.MISSED, out.only<Effect.LogCall>().entry.outcome)
        assertEquals(THIRD, out.only<Effect.HideWaiting>().guid)
        assertEquals(IN, state?.guid)
    }

    @Test
    fun whenTheCallEndsFirstTheWaitingCallRingsAsAnyCallDoes() {
        bobWaits()
        t += 2_000
        val out = send(Input.HangUp(IN, t))
        assertEquals("Bob's call is the call now", THIRD, state?.guid)
        assertEquals(Phase.RINGING_IN, state?.phase)
        assertNull(rules.waiting)
        assertEquals(IN, out.only<Effect.EndNative>().call.guid)
        assertEquals(THIRD, out.only<Effect.ShowIncoming>().call.guid)
        // And is answered as usual.
        val answered = send(Input.Answer(THIRD, true, t))
        assertEquals(Phase.CONNECTING, state?.phase)
        assertEquals(THIRD, answered.only<Effect.StartNative>().call.guid)
    }

    @Test
    fun aCallerNobodyKnowsDoesNotWaitWhenUnknownCallersAreSilenced() {
        ringing()
        send(Input.Answer(IN, false, t))
        send(Input.NativeUp(IN, false, t))
        val silence = FaceTimeSettings(on = true, silenceUnknown = true)
        event(CallEvent.Join(THIRD, BOB, ring = true), listOf(session(IN), session(THIRD, BOB)), settings = silence)
        val out = send(Input.Caller(THIRD, CallerInfo("", known = false, conversationId = null), t, silence))
        assertNull(rules.waiting)
        assertFalse(out.has<Effect.ShowWaiting>())
        assertTrue(out.only<Effect.LogCall>().entry.quiet)
        assertEquals(IN, state?.guid)
    }

    @Test
    fun aRingWhileAPlacedCallRingsOutIsMissedToo() {
        place()
        val out = event(CallEvent.Join(IN, BOB, ring = true), listOf(session(IN, BOB)))
        assertEquals(OUT, state?.guid)
        assertEquals(Outcome.MISSED, out.only<Effect.LogCall>().entry.outcome)
    }

    @Test
    fun noAnswerLeftOnScreenHoldsNoLine() {
        place()
        send(Input.Timeout(OUT, Phase.RINGING_OUT, t))
        assertEquals(Phase.UNANSWERED, state?.phase)
        // A call comes in: it is screened and rings.
        event(CallEvent.Join(IN, BOB, ring = true), listOf(session(IN, BOB)))
        assertEquals(IN, state?.guid)
        assertEquals(Phase.SCREENING, state?.phase)
        // And another call can be placed from it.
        state = null
        place()
        send(Input.Timeout(OUT, Phase.RINGING_OUT, t))
        assertTrue(place(guid = OTHER).has<Effect.CreateCall>())
        assertEquals(OTHER, state?.guid)
    }

    // --- what does not ring ----------------------------------------------------------------------

    @Test
    fun aCallerRingsAtMostOnceInTenSecondsAndSixTimesAnHour() {
        ringing()
        send(Input.DeclineByUser(IN, t))
        // Again three seconds later, as another call: it does not ring, it is a missed call, without a sound.
        t += 3_000
        val soon = event(CallEvent.Join(OTHER, ALICE, ring = true), listOf(session(OTHER)))
        assertNull(state)
        assertFalse(soon.has<Effect.LookupCaller>())
        assertTrue(soon.only<Effect.LogCall>().entry.quiet)
        // The very same call again within the ten seconds is dropped outright.
        val same = event(CallEvent.Join(OTHER, ALICE, ring = true), listOf(session(OTHER)))
        assertFalse(same.has<Effect.LogCall>())
        assertFalse(same.has<Effect.LookupCaller>())
        // Five more over the hour ring (six in all), the seventh does not.
        for (i in 1..5) {
            t += 60_000
            val guid = guid(i)
            event(CallEvent.Join(guid, ALICE, ring = true), listOf(session(guid)))
            assertEquals("ring $i", Phase.SCREENING, state?.phase)
            send(Input.Caller(guid, CallerInfo("Alice", true), t, on))
            send(Input.DeclineByUser(guid, t))
        }
        t += 60_000
        val seventh = event(CallEvent.Join(guid(7), ALICE, ring = true), listOf(session(guid(7))))
        assertNull(state)
        assertTrue(seventh.only<Effect.LogCall>().entry.quiet)
        // Someone else still rings.
        event(CallEvent.Join(guid(8), BOB, ring = true), listOf(session(guid(8), BOB)))
        assertEquals(Phase.SCREENING, state?.phase)
        // An hour on, the first caller rings again.
        state = null
        t += 61 * 60_000
        event(CallEvent.Join(guid(9), ALICE, ring = true), listOf(session(guid(9))))
        assertEquals(Phase.SCREENING, state?.phase)
    }

    @Test
    fun aCallerCanOnlyFillTheListWithSoManyMissedCalls() {
        // Twenty-five calls nobody is in any more (so none rings), a minute apart: twenty are noted, the rest dropped.
        var noted = 0
        for (i in 1..25) {
            t += 60_000
            val guid = guid(100 + i)
            if (event(CallEvent.Join(guid, ALICE, ring = true), listOf(session(guid, active = false))).has<Effect.LogCall>()) noted++
        }
        assertEquals(RingLimiter.MAX_NOTICES_PER_HOUR, noted)
        assertNull(state)
    }

    @Test
    fun aRingForACallNobodyIsInAnyMoreDoesNotRing() {
        val out = event(CallEvent.Join(IN, ALICE, ring = true), listOf(session(IN, active = false)))
        assertNull(state)
        assertFalse(out.has<Effect.LookupCaller>())
        val missed = out.only<Effect.LogCall>().entry
        assertEquals(Outcome.MISSED, missed.outcome)
        assertTrue("an old call's notice makes no sound", missed.quiet)
    }

    @Test
    fun anOldRingHandedOverAfterARestartDoesNotRingButAFreshOneDoes() {
        // Just after the library's session came up, a ring for a call that began ten minutes ago, though the list still says someone is in it.
        val old = event(
            CallEvent.Join(IN, ALICE, ring = true), listOf(session(IN, active = true, startMs = t - 10 * 60_000)), sinceStartMs = 5_000,
        )
        assertNull(state)
        assertFalse(old.has<Effect.LookupCaller>())
        assertTrue(old.only<Effect.LogCall>().entry.quiet)
        // One that began seconds ago (it landed while the process was starting) still rings.
        t += 60_000
        event(CallEvent.Join(OTHER, ALICE, ring = true), listOf(session(OTHER, startMs = t - 8_000)), sinceStartMs = 5_000)
        assertEquals(Phase.SCREENING, state?.phase)
        // And long after the start, a ring is a ring whenever the call began (someone adds the user to a call that has been going on).
        state = null
        t += 60_000
        event(CallEvent.Join(THIRD, BOB, ring = false), listOf(session(THIRD, BOB, startMs = t - 30 * 60_000)), sinceStartMs = 3_600_000)
        assertNull("a join that is no ring rings nothing", state)
        event(CallEvent.Invite(THIRD), listOf(session(THIRD, BOB, startMs = t - 30 * 60_000)), sinceStartMs = 3_600_000)
        assertEquals(Phase.SCREENING, state?.phase)
        assertEquals("the invitation is from whoever was seen joining that call", BOB, state?.peer)
    }

    @Test
    fun aRingForACallTheLibraryDoesNotKnowOrThatNeverBeganDoesNothing() {
        /** Nothing but a line for the log. */
        fun onlyNoted(effects: List<Effect>) = assertTrue(effects.toString(), effects.all { it is Effect.Note })
        onlyNoted(event(CallEvent.Join(IN, ALICE, ring = true), emptyList()))
        onlyNoted(event(CallEvent.Join(IN, ALICE, ring = true), listOf(session(IN, startMs = null))))
        // Nor one with nobody but the user in it, nor from a link's guest.
        onlyNoted(event(CallEvent.Invite(IN), listOf(CallSessionInfo(IN, listOf(ME), listOf(ME), t, false, true))))
        onlyNoted(event(CallEvent.Invite(IN), listOf(CallSessionInfo(IN, listOf(ME), listOf(ME, "temp:guest"), t, false, true))))
        assertNull(state)
        assertTrue(logged().isEmpty())
        // What is said names nobody and no call.
        for (note in done.filterIsInstance<Effect.Note>()) {
            assertFalse(note.text, note.text.contains(IN) || note.text.contains("555") || note.text.contains("tel:"))
        }
    }

    @Test
    fun aGroupCallDoesNotRingHereYet() {
        val out = event(CallEvent.Join(IN, ALICE, ring = true), listOf(session(IN, others = listOf(BOB))))
        assertNull(state)
        assertFalse(out.has<Effect.LookupCaller>())
        assertEquals(Outcome.MISSED, out.only<Effect.LogCall>().entry.outcome)
    }

    @Test
    fun aJoinThatIsNoRingDoesNothing() {
        assertTrue(event(CallEvent.Join(IN, ALICE, ring = false), listOf(session(IN))).isEmpty())
        assertNull(state)
    }

    @Test
    fun withFaceTimeOffNothingRingsAndNothingIsNoted() {
        val off = FaceTimeSettings(on = false)
        assertTrue(event(CallEvent.Join(IN, ALICE, ring = true), listOf(session(IN)), settings = off).isEmpty())
        assertTrue(event(CallEvent.Invite(IN), listOf(session(IN)), settings = off).isEmpty())
        assertNull(state)
    }

    @Test
    fun unknownCallersAreSilencedWhenAskedFor() {
        val silence = FaceTimeSettings(on = true, silenceUnknown = true)
        event(CallEvent.Join(IN, ALICE, ring = true), listOf(session(IN)), settings = silence)
        val out = send(Input.Caller(IN, CallerInfo("", known = false), t, silence))
        assertNull(state)
        assertFalse(out.has<Effect.ShowIncoming>())
        val missed = out.only<Effect.LogCall>().entry
        assertEquals(Outcome.MISSED, missed.outcome)
        assertTrue("silenced: its notice makes no sound either", missed.quiet)
        // Someone known rings.
        t += 60_000
        event(CallEvent.Join(OTHER, BOB, ring = true), listOf(session(OTHER, BOB)), settings = silence)
        assertTrue(send(Input.Caller(OTHER, CallerInfo("Bob", known = true), t, silence)).has<Effect.ShowIncoming>())
        // Not told who it is (the main process did not answer), nobody is known.
        state = null
        t += 60_000
        event(CallEvent.Join(THIRD, BOB, ring = true), listOf(session(THIRD, BOB)), settings = silence)
        assertFalse(send(Input.Caller(THIRD, null, t, silence)).has<Effect.ShowIncoming>())
        assertNull(state)
    }

    @Test
    fun withoutTheSettingAnUnknownCallerRingsByTheirAddress() {
        event(CallEvent.Join(IN, ALICE, ring = true), listOf(session(IN)))
        val out = send(Input.Caller(IN, null, t, on))
        assertEquals(Phase.RINGING_IN, state?.phase)
        assertEquals("", out.only<Effect.ShowIncoming>().call.name)
        assertEquals(ALICE, state?.peer)
    }

    @Test
    fun duringAPhoneCallAFaceTimeCallDoesNotRing() {
        event(CallEvent.Join(IN, ALICE, ring = true), listOf(session(IN)))
        val out = send(Input.Caller(IN, CallerInfo("Alice", true), t, on, phoneBusy = true))
        assertNull(state)
        assertFalse(out.has<Effect.ShowIncoming>())
        assertEquals(Outcome.MISSED, out.only<Effect.LogCall>().entry.outcome)
    }

    @Test
    fun theCallersWordOnWhoTheyAreIsNotTaken() {
        // The event names one caller, the library's own list of the call another: a ring names its sender, who is checked against nobody's word but Apple's.
        event(CallEvent.Join(IN, ALICE, ring = true), listOf(session(IN, caller = ALICE)))
        assertEquals(ALICE, state?.peer)
        // A name of any length and shape from the main process is cut to one line.
        send(Input.Caller(IN, CallerInfo("A".repeat(500) + "\nsecond line", true), t, on))
        assertEquals(CallIds.MAX_NAME_CHARS, state?.name?.length)
    }

    @Test
    fun turningTheCameraOnInAnAudioCallMakesItAVideoCall() {
        ringing()
        send(Input.Answer(IN, false, t))
        send(Input.NativeUp(IN, video = false, now = t))
        assertFalse(state!!.video)
        send(Input.BecameVideo(IN))
        assertTrue(state!!.video)
        t += 2_000
        assertTrue(send(Input.HangUp(IN, t)).only<Effect.LogCall>().entry.video)
    }

    @Test
    fun aCallsIdsAndAddressesAreCheckedBeforeAnythingElse() {
        assertTrue(CallIds.isGuid("0A1B2C3D-0000-4000-8000-00000000000A"))
        assertFalse("lower case: the library would never find its relay", CallIds.isGuid("0a1b2c3d-0000-4000-8000-00000000000a"))
        assertFalse(CallIds.isGuid("not-a-guid"))
        assertFalse(CallIds.isGuid("0A1B2C3D-0000-4000-8000-00000000000A\n"))
        assertFalse(CallIds.isGuid(null))
        val made = CallIds.newGuid()
        assertTrue(made, CallIds.isGuid(made))
        assertEquals("tel:+15551234567", CallIds.handle(" tel:+15551234567 "))
        assertEquals("mailto:a@b.c", CallIds.handle("mailto:a@b.c"))
        assertNull(CallIds.handle("temp:guest"))
        assertNull(CallIds.handle("tel:15551234567"))
        assertNull(CallIds.handle("tel:+1555 123"))
        assertNull(CallIds.handle("mailto:" + "a".repeat(400) + "@b.c"))
        assertNull(CallIds.handle("https://example.com"))
        assertNull(CallIds.handle(null))
        assertEquals("mailto:a@b.c", CallIds.key("mailto:A@B.c"))
        assertNotNull(CallIds.handle("tel:+4915112345678"))
    }

    // --- who calls: only whom Apple vouches for ------------------------------------------------------

    @Test
    fun aCallsMemberListNeverSaysWhoCalls() {
        // A stranger's ring whose member list (which the stranger writes) puts a contact first: the caller is the stranger.
        val spoofed = CallSessionInfo(IN, listOf(ME), listOf(ALICE, ME, MALLORY), t, false, true)
        // A list with the stranger and the user only, the contact slipped in as the first "member" that is no address of the stranger's.
        val out = event(CallEvent.Join(IN, MALLORY, ring = true), listOf(CallSessionInfo(IN, listOf(ME), listOf(ALICE, MALLORY, ME), t, false, true)))
        // Three people: a group call, which does not ring; its missed call is the stranger's, never the contact's.
        assertNull(state)
        assertEquals(MALLORY, out.only<Effect.LogCall>().entry.peer)
        assertFalse(out.has<Effect.LookupCaller>())
        // The member list naming the contact only, the ring coming from the stranger: not in the call they ring for, so nothing.
        t += 60_000
        val absent = event(CallEvent.Join(OTHER, MALLORY, ring = true), listOf(session(OTHER, caller = ALICE)))
        assertNull(state)
        assertTrue(absent.toString(), absent.all { it is Effect.Note })
        // An invitation (which names nobody) to a call the stranger joined, listed as the contact's: still the stranger's.
        t += 60_000
        event(CallEvent.Join(THIRD, MALLORY, ring = false), listOf(spoofed.copy(guid = THIRD)))
        val invited = event(CallEvent.Invite(THIRD), listOf(spoofed.copy(guid = THIRD)))
        assertNull(state)
        assertEquals(listOf(MALLORY), (out + invited).filterIsInstance<Effect.LogCall>().map { it.entry.peer }.distinct())
        // And one-to-one, with a list of the stranger and the user: the main process is asked about the stranger, nobody else.
        t += 60_000
        val honest = event(CallEvent.Join(guid(1), MALLORY, ring = true), listOf(session(guid(1), caller = MALLORY)))
        assertEquals(MALLORY, honest.only<Effect.LookupCaller>().handle)
        assertEquals(MALLORY, state?.peer)
        // Nothing anywhere named the contact.
        assertTrue(done.none { it is Effect.LookupCaller && it.handle == ALICE })
        assertTrue(logged().none { it.peer == ALICE })
    }

    @Test
    fun anInvitationWithoutAJoinSeenBeforeDoesNotRing() {
        // The call is in the list, alive, its members say a contact calls: but nobody Apple vouches for was seen joining it.
        val out = event(CallEvent.Invite(IN), listOf(session(IN, caller = ALICE)))
        assertNull(state)
        assertTrue(out.toString(), out.single() is Effect.Note)
        assertFalse((out.single() as Effect.Note).text.contains("555"))
        assertTrue(logged().isEmpty())
        // A join of another call makes no caller for this one.
        event(CallEvent.Join(OTHER, ALICE, ring = false), listOf(session(OTHER)))
        assertTrue(event(CallEvent.Invite(IN), listOf(session(IN, caller = ALICE))).all { it is Effect.Note })
        assertNull(state)
        // Nor does a join that names nobody, or the user.
        event(CallEvent.Join(IN, "", ring = false), listOf(session(IN)))
        event(CallEvent.Join(IN, ME, ring = false), listOf(session(IN)))
        assertTrue(event(CallEvent.Invite(IN), listOf(session(IN))).all { it is Effect.Note })
        assertNull(state)
        assertTrue(logged().isEmpty())
    }

    @Test
    fun aRingFromTheUsersOwnAddressOrFromNobodyDoesNotRing() {
        // A call the user placed on another of their devices: its join comes from their own address.
        val own = event(CallEvent.Join(IN, ME, ring = true), listOf(session(IN, caller = ALICE)))
        assertNull(state)
        assertTrue(own.toString(), own.all { it is Effect.Note })
        // An alias of the user's that only the call's own list knows of.
        val alias = "mailto:me@example.com"
        val aliased = event(CallEvent.Join(OTHER, "mailto:Me@Example.com", ring = true), listOf(CallSessionInfo(OTHER, listOf(ME, alias), listOf(ALICE, ME), t, false, true)))
        assertNull(state)
        assertTrue(aliased.all { it is Effect.Note })
        // A ring that does not say who sent it is not the first member's.
        val nobody = event(CallEvent.Join(THIRD, "", ring = true), listOf(session(THIRD, caller = ALICE)))
        assertNull(state)
        assertTrue(nobody.all { it is Effect.Note })
        assertTrue("no row, no missed call", logged().isEmpty())
        assertFalse(done.has<Effect.LookupCaller>())
    }

    @Test
    fun aPlacedCallIsAnsweredOnlyByAJoinThatSaysWhose() {
        place()
        send(Input.Placed(OUT, ok = true, now = t))
        assertTrue(event(CallEvent.Join(OUT, "", ring = false), listOf(session(OUT))).isEmpty())
        assertTrue(event(CallEvent.Join(OUT, ME, ring = false), listOf(session(OUT))).isEmpty())
        assertEquals(Phase.RINGING_OUT, state?.phase)
        // Another address of the person called answers too.
        assertTrue(event(CallEvent.Join(OUT, "mailto:alice@example.com", ring = false), listOf(session(OUT))).has<Effect.StartNative>())
        assertEquals(Phase.CONNECTING, state?.phase)
    }

    @Test
    fun onlySoManyCallersAreRememberedByTheirJoin() {
        for (i in 0..CallRules.MAX_JOINERS) event(CallEvent.Join(guid(1000 + i), BOB, ring = false))
        // The first one's is forgotten, the last one's is not.
        assertTrue(event(CallEvent.Invite(guid(1000)), listOf(session(guid(1000), BOB))).all { it is Effect.Note })
        assertNull(state)
        event(CallEvent.Invite(guid(1000 + CallRules.MAX_JOINERS)), listOf(session(guid(1000 + CallRules.MAX_JOINERS), BOB)))
        assertEquals(BOB, state?.peer)
    }

    // --- everyone together ---------------------------------------------------------------------------

    @Test
    fun strangersTogetherRingOnlySoOftenAnHourWhateverAddressTheyUse() {
        var rung = 0
        var quiet = 0
        for (i in 1..RingLimiter.MAX_UNKNOWN_RINGS_PER_HOUR + 4) {
            t += 20_000
            val guid = guid(2000 + i)
            val caller = "tel:+1555900%04d".format(i)
            event(CallEvent.Join(guid, caller, ring = true), listOf(session(guid, caller)))
            assertEquals(Phase.SCREENING, state?.phase)
            val out = send(Input.Caller(guid, CallerInfo("", known = false), t, on))
            if (out.has<Effect.ShowIncoming>()) {
                rung++
                send(Input.DeclineByUser(guid, t))
            } else {
                assertNull(state)
                assertTrue("missed, without a sound", out.only<Effect.LogCall>().entry.quiet)
                quiet++
            }
        }
        assertEquals(RingLimiter.MAX_UNKNOWN_RINGS_PER_HOUR, rung)
        assertEquals(4, quiet)
        // Someone the user knows still rings.
        t += 20_000
        event(CallEvent.Join(guid(2999), ALICE, ring = true), listOf(session(guid(2999))))
        assertTrue(send(Input.Caller(guid(2999), CallerInfo("Alice", known = true), t, on)).has<Effect.ShowIncoming>())
    }

    private fun guid(n: Int): String = "0A1B2C3D-0000-4000-8000-%012d".format(n)

    private companion object {
        const val OUT = "AAAAAAAA-0000-4000-8000-000000000001"
        const val IN = "BBBBBBBB-0000-4000-8000-000000000002"
        const val OTHER = "CCCCCCCC-0000-4000-8000-000000000003"
        const val THIRD = "DDDDDDDD-0000-4000-8000-000000000004"
        const val CAROL = "tel:+15553334444"

        /** A stranger. */
        const val MALLORY = "tel:+15559990000"
    }
}
