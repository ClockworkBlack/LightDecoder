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
        // In a call, too.
        send(Input.Answer(IN, false, t))
        send(Input.NativeUp(IN, false, t))
        t += 60_000
        val third = event(CallEvent.Join(THIRD, BOB, ring = true), listOf(session(IN), session(THIRD, BOB)))
        assertEquals(Phase.ACTIVE, state?.phase)
        assertEquals(Outcome.MISSED, third.only<Effect.LogCall>().entry.outcome)
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
    fun aGroupCallRingsAsItsCallerWithOnlyHowManyOthersItsMemberListNames() {
        // SPEC2 B2 changed this: a group call rings (in 0.5.72 it was only noted as missed).
        val out = event(CallEvent.Join(IN, ALICE, ring = true, participant = 7L), listOf(session(IN, others = listOf(BOB, MALLORY))))
        assertEquals(Phase.SCREENING, state?.phase)
        assertEquals("only the caller Apple vouches for is asked about", ALICE, out.only<Effect.LookupCaller>().handle)
        val call = state!!
        assertTrue(call.group)
        assertEquals(ALICE, call.peer)
        assertEquals("the member list is counted, never shown as who calls", 2, call.others)
        assertEquals(listOf(ALICE), call.peers)
        assertEquals(mapOf(7L to ALICE), call.joiners)
        val rung = send(Input.Caller(IN, CallerInfo("Alice Martin", known = true, conversationId = "imsg:tel:+15551234567"), t, on))
        val shown = rung.only<Effect.ShowIncoming>().call
        assertEquals("Alice Martin", shown.name)
        assertTrue(shown.group)
        assertFalse("from someone known: Decline, as any ring", shown.ignoreOnly)
        assertTrue(done.none { it is Effect.LookupCaller && it.handle != ALICE })
        // Declined, it is told (the user knows the caller).
        assertEquals(IN, send(Input.DeclineByUser(IN, t)).only<Effect.DeclineCall>().guid)
        val row = logged().single()
        assertTrue(row.group)
        assertEquals("its row goes where its people say (only ever held against a group chat's)", listOf(ALICE, BOB, MALLORY), row.members)
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
        // Three people: a group call (SPEC2 B2: it rings now), rung as the stranger's own, never as the contact's.
        assertEquals(MALLORY, out.only<Effect.LookupCaller>().handle)
        assertEquals(MALLORY, state?.peer)
        send(Input.Caller(IN, CallerInfo("", known = false), t, on))
        val ignored = send(Input.DeclineByUser(IN, t))
        assertNull(state)
        assertEquals(MALLORY, ignored.only<Effect.LogCall>().entry.peer)
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
        assertEquals(listOf(MALLORY), (out + ignored + invited).filterIsInstance<Effect.LogCall>().map { it.entry.peer }.distinct())
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

    // --- group calls (SPEC2 B2) -----------------------------------------------------------------------

    private fun groupSession(guid: String, caller: String = ALICE, others: List<String> = listOf(BOB, CAROL), video: Boolean = false) =
        session(guid, caller, video = video, others = others)

    @Test
    fun aGroupCallIsPlacedToEveryoneAtOnce() {
        val out = send(Input.Place(OUT, ALICE, "Hikers", true, ME, "imsg:group:G", null, t, false, on, peers = listOf(ALICE, BOB, "temp:guest", "mailto:Bob@Example.com", BOB)))
        val call = out.only<Effect.CreateCall>().call
        assertEquals("each once, only addresses, the first first", listOf(ALICE, BOB, "mailto:Bob@Example.com"), call.peers)
        assertTrue(call.group)
        assertEquals(ALICE, call.peer)
        assertEquals(Phase.RINGING_OUT, state?.phase)
        // Only so many.
        state = null
        val many = send(Input.Place(OTHER, ALICE, "", false, ME, null, null, t, false, on, peers = List(50) { "tel:+1555700%04d".format(it) }))
        assertEquals(MAX_PEOPLE, many.only<Effect.CreateCall>().call.peers.size)
        // One person through the group path is a one-to-one call.
        state = null
        assertFalse(send(Input.Place(THIRD, ALICE, "", false, ME, null, null, t, false, on, peers = listOf(ALICE))).only<Effect.CreateCall>().call.group)
    }

    @Test
    fun aPlacedGroupCallEndsDeclinedOnlyOnceEveryoneSaidNo() {
        send(Input.Place(OUT, ALICE, "", false, ME, null, null, t, false, on, peers = listOf(ALICE, BOB)))
        send(Input.Placed(OUT, ok = true, now = t))
        // One no: it goes on ringing for the other.
        assertTrue(event(CallEvent.Decline(OUT, ALICE)).isEmpty())
        assertEquals(Phase.RINGING_OUT, state?.phase)
        assertEquals(setOf(ALICE), state!!.declined)
        // A no that says nobody's, or someone's it never went to, says nothing.
        assertTrue(event(CallEvent.Decline(OUT, "")).isEmpty())
        assertTrue(event(CallEvent.Decline(OUT, MALLORY)).isEmpty())
        assertTrue(event(CallEvent.Decline(OUT, ME)).isEmpty())
        assertEquals(Phase.RINGING_OUT, state?.phase)
        assertEquals(setOf(ALICE), state!!.declined)
        // The same no twice is still one.
        event(CallEvent.Decline(OUT, ALICE))
        assertEquals(Phase.RINGING_OUT, state?.phase)
        // Everyone said no: taken back, and shown as declined.
        val out = event(CallEvent.Decline(OUT, BOB))
        assertEquals(Phase.UNANSWERED, state?.phase)
        assertEquals(Unanswered.DECLINED, state?.unanswered)
        assertEquals(OUT, out.only<Effect.CancelCall>().guid)
        val row = out.only<Effect.LogCall>().entry
        assertEquals(Outcome.DECLINED, row.outcome)
        assertTrue(row.group)
        assertEquals(listOf(ALICE, BOB), row.members)
    }

    @Test
    fun aOneToOneCallStillEndsAtItsOnlyPersonsNo() {
        place()
        // As in 0.5.72: any no that is not the user's own ends a one-to-one call.
        event(CallEvent.Decline(OUT, ""))
        assertEquals(Unanswered.DECLINED, state?.unanswered)
    }

    @Test
    fun aPlacedGroupCallIsAnsweredOnlyByOneOfThePeopleItWentTo() {
        send(Input.Place(OUT, ALICE, "", false, ME, null, null, t, false, on, peers = listOf(ALICE, BOB)))
        send(Input.Placed(OUT, ok = true, now = t))
        assertTrue(event(CallEvent.Join(OUT, MALLORY, ring = false)).none { it is Effect.StartNative })
        assertTrue(event(CallEvent.Join(OUT, "", ring = false)).isEmpty())
        assertTrue(event(CallEvent.Join(OUT, ME, ring = false)).isEmpty())
        assertEquals(Phase.RINGING_OUT, state?.phase)
        val joined = event(CallEvent.Join(OUT, BOB, ring = false, participant = 9L))
        assertEquals(Phase.CONNECTING, state?.phase)
        assertTrue(joined.has<Effect.StartNative>())
        assertEquals("who joined is known by the id they joined with", BOB, state!!.joiners[9L])
    }

    @Test
    fun anInvitationRingsAGroupCallOnlyRightAfterItsCallersOwnRingAndInTheCallsOwnAllowance() {
        val strict = CallRules(RingLimiter(maxRingsPerHour = 2))
        fun go(input: Input): List<Effect> = strict.reduce(state, input).also { state = it.state; done += it.effects }.effects
        fun at(e: CallEvent, sessions: List<CallSessionInfo> = listOf(groupSession(IN))) = go(Input.Event(e, sessions, setOf(ME), t, 10 * 60_000L, on))
        // Alice's own ring comes before the library has the call: not decided yet.
        assertTrue(at(CallEvent.Join(IN, ALICE, ring = true), emptyList()).all { it is Effect.Note })
        assertNull(state)
        // Fifteen seconds on, an invitation (from any member: it names nobody) rings it, as Alice's.
        t += 15_000
        assertTrue(at(CallEvent.Invite(IN)).has<Effect.LookupCaller>())
        assertEquals(ALICE, state?.peer)
        go(Input.Caller(IN, CallerInfo("Alice", known = true), t, on))
        go(Input.DeclineByUser(IN, t))
        // Declined: another invitation does not ring it again, even within the thirty seconds.
        t += 11_000
        val again = at(CallEvent.Invite(IN))
        assertNull(state)
        assertTrue(again.toString(), again.all { it is Effect.Note })
        // Thirty-one seconds after Alice's own ring: no more either.
        t += 16_000
        assertTrue(at(CallEvent.Invite(IN)).all { it is Effect.Note })
        assertNull(state)
        // A one-to-one call's invitation rings whenever (as in 0.5.72): its joiner is remembered.
        t += 60_000
        at(CallEvent.Join(OTHER, BOB, ring = false), listOf(session(OTHER, BOB)))
        t += 5 * 60_000
        assertTrue(at(CallEvent.Invite(OTHER), listOf(session(OTHER, BOB))).has<Effect.LookupCaller>())
    }

    @Test
    fun aGroupInvitationNeverRingsWhatItsCallersOwnRingCouldNot() {
        val strict = CallRules(RingLimiter(maxRingsPerHour = 1))
        fun go(input: Input): List<Effect> = strict.reduce(state, input).also { state = it.state; done += it.effects }.effects
        fun at(e: CallEvent, guid: String, sessions: List<CallSessionInfo> = listOf(groupSession(guid))) =
            go(Input.Event(e, sessions, setOf(ME), t, 10 * 60_000L, on))
        // Alice uses her one ring of the hour.
        at(CallEvent.Join(IN, ALICE, ring = true), IN)
        go(Input.Caller(IN, CallerInfo("Alice", known = true), t, on))
        go(Input.DeclineByUser(IN, t))
        assertNull(state)
        // A new group call: her own ring is past her limit (a missed call at most) ...
        t += 60_000
        val g2 = "9C7E1F2A-0000-4000-8000-00000000A002"
        assertFalse(at(CallEvent.Join(g2, ALICE, ring = true), g2).has<Effect.LookupCaller>())
        assertNull(state)
        // ... and an invitation for it eleven seconds later does not ring it either.
        t += 11_000
        val invited = at(CallEvent.Invite(g2), g2)
        assertNull(state)
        assertTrue(invited.toString(), invited.all { it is Effect.Note })
        // Nor does one that follows a ring the library could not check yet: her limit holds.
        t += 60_000
        val g3 = "9C7E1F2A-0000-4000-8000-00000000A003"
        at(CallEvent.Join(g3, ALICE, ring = true), g3, emptyList())
        t += 5_000
        val late = at(CallEvent.Invite(g3), g3)
        assertNull(state)
        assertTrue(late.toString(), late.all { it is Effect.Note })
    }

    @Test
    fun aGroupInvitationsAllowanceIsTheCallsNotTheCallers() {
        val limiter = RingLimiter(minGapMs = 10, maxRingsPerHour = 1, windowMs = 1_000)
        assertEquals(RingLimiter.Verdict.RING, limiter.ring("alice", "g1", 0))
        // The call's invitations, in a bucket of their own.
        assertEquals(RingLimiter.Verdict.RING, limiter.callRing("g1", 20))
        assertEquals("the call's own allowance is used up", RingLimiter.Verdict.MISSED_ONLY, limiter.callRing("g1", 40))
        // Alice's own was used once, by her ring only.
        assertEquals(RingLimiter.Verdict.MISSED_ONLY, limiter.ring("alice", "g2", 60))
        assertEquals(RingLimiter.Verdict.RING, limiter.ring("bob", "g3", 80))
        assertFalse("no address has the bucket's shape", CallIds.handle(RingLimiter.callKey("g1")) != null)
    }

    @Test
    fun aGroupInvitationWithNoRingOfItsCallerBeforeIsDropped() {
        // Ring and AddMembers(ring): no sender, no remembered ring of its caller.
        val out = event(CallEvent.Invite(IN), listOf(groupSession(IN)))
        assertNull(state)
        assertTrue(out.single() is Effect.Note)
        // A join that is no ring does not make one.
        event(CallEvent.Join(IN, ALICE, ring = false), listOf(groupSession(IN)))
        assertTrue(event(CallEvent.Invite(IN), listOf(groupSession(IN))).all { it is Effect.Note })
        assertNull(state)
        assertTrue(logged().isEmpty())
    }

    @Test
    fun aSecondRingOfACallThenDeclinedOrHungUpLeavesNoInvitationToRingItAgain() {
        // Declined: a second ring of the call while it rings is no ring to be invited by.
        event(CallEvent.Join(IN, ALICE, ring = true), listOf(groupSession(IN)))
        send(Input.Caller(IN, CallerInfo("Alice", true), t, on))
        assertEquals(Phase.RINGING_IN, state?.phase)
        t += 1_000
        event(CallEvent.Join(IN, ALICE, ring = true), listOf(groupSession(IN)))
        t += 4_000
        send(Input.DeclineByUser(IN, t))
        assertNull(state)
        t += 7_000
        assertTrue(event(CallEvent.Invite(IN), listOf(groupSession(IN))).all { it is Effect.Note })
        assertNull(state)
        // Answered, rung again while in it, then hung up with others still there: the same.
        t += 60 * 60_000
        event(CallEvent.Join(OTHER, BOB, ring = true), listOf(groupSession(OTHER, caller = BOB)))
        send(Input.Caller(OTHER, CallerInfo("Bob", true), t, on))
        send(Input.Answer(OTHER, false, t))
        send(Input.NativeUp(OTHER, false, t))
        t += 1_000
        event(CallEvent.Join(OTHER, BOB, ring = true), listOf(groupSession(OTHER, caller = BOB)))
        t += 4_000
        send(Input.HangUp(OTHER, t))
        assertNull(state)
        t += 7_000
        assertTrue(event(CallEvent.Invite(OTHER), listOf(groupSession(OTHER, caller = BOB))).all { it is Effect.Note })
        assertNull(state)
    }

    @Test
    fun inAGroupNobodyLeavingStopsTheRingButInOneToOneTheCallerGivingUpDoes() {
        event(CallEvent.Join(IN, ALICE, ring = true), listOf(groupSession(IN)))
        send(Input.Caller(IN, CallerInfo("Alice", true), t, on))
        // Any member can say anyone left: nothing.
        assertTrue(event(CallEvent.Leave(IN, ALICE), listOf(groupSession(IN))).isEmpty())
        assertTrue(event(CallEvent.Leave(IN, BOB), listOf(groupSession(IN))).isEmpty())
        assertEquals(Phase.RINGING_IN, state?.phase)
        // Joined: nobody leaving ends it either, only the library's own word that nobody is there.
        send(Input.Answer(IN, false, t))
        send(Input.NativeUp(IN, false, t))
        send(Input.Participants.of(IN, longArrayOf(7, 8), arrayOf(ALICE, BOB), t))
        assertTrue(event(CallEvent.Leave(IN, ALICE), listOf(groupSession(IN).copy(active = false))).isEmpty())
        assertEquals(Phase.ACTIVE, state?.phase)
        // One to one: the caller giving up stops it (as in 0.5.72).
        state = null
        t += 60_000
        ringing(guid = OTHER, caller = BOB)
        assertEquals(Outcome.MISSED, event(CallEvent.Leave(OTHER, BOB)).only<Effect.LogCall>().entry.outcome)
        assertNull(state)
    }

    @Test
    fun beingTakenOutOfACallStopsItsRing() {
        event(CallEvent.Join(IN, ALICE, ring = true), listOf(groupSession(IN)))
        send(Input.Caller(IN, CallerInfo("Alice", true), t, on))
        // Someone else taken out: nothing.
        assertTrue(event(CallEvent.Removed(IN, listOf(BOB)), listOf(groupSession(IN))).isEmpty())
        assertEquals(Phase.RINGING_IN, state?.phase)
        val out = event(CallEvent.Removed(IN, listOf(BOB, ME)), listOf(groupSession(IN)))
        assertNull(state)
        assertEquals(IN, out.only<Effect.HideIncoming>().guid)
        assertEquals(Outcome.MISSED, out.only<Effect.LogCall>().entry.outcome)
        assertFalse("nothing is sent", out.has<Effect.DeclineCall>())
    }

    @Test
    fun aGroupRingFromSomeoneUnknownIsIgnoredNotDeclinedAndAnsweredWithoutTheCamera() {
        event(CallEvent.Join(IN, MALLORY, ring = true), listOf(groupSession(IN, caller = MALLORY, video = true)))
        val shown = send(Input.Caller(IN, CallerInfo("", known = false), t, on)).only<Effect.ShowIncoming>().call
        assertTrue(shown.ignoreOnly)
        // Ignore: nothing is sent to the people its member list names.
        val ignored = send(Input.DeclineByUser(IN, t))
        assertNull(state)
        assertFalse(ignored.has<Effect.DeclineCall>())
        assertEquals(Outcome.DECLINED, ignored.only<Effect.LogCall>().entry.outcome)
        // The hang-up of a ring is the same.
        t += 60_000
        event(CallEvent.Join(OTHER, MALLORY, ring = true), listOf(groupSession(OTHER, caller = MALLORY, video = true)))
        assertFalse(send(Input.HangUp(OTHER, t)).has<Effect.DeclineCall>())
        // Answered, with the camera asked for: without it.
        t += 60_000
        event(CallEvent.Join(THIRD, MALLORY, ring = true), listOf(groupSession(THIRD, caller = MALLORY, video = true)))
        send(Input.Caller(THIRD, CallerInfo("", known = false), t, on))
        send(Input.Answer(THIRD, withCamera = true, now = t))
        assertFalse(state!!.withCamera)
        assertTrue(state!!.video)
        // A one-to-one ring from someone unknown is declined as before.
        state = null
        t += 60_000
        event(CallEvent.Join(guid(5), MALLORY, ring = true), listOf(session(guid(5), MALLORY)))
        send(Input.Caller(guid(5), CallerInfo("", known = false), t, on))
        assertFalse(state!!.ignoreOnly)
        assertTrue(send(Input.DeclineByUser(guid(5), t)).has<Effect.DeclineCall>())
    }

    @Test
    fun theLibrarysParticipantsAreKeptWithWhatItReportsAndTheHangUpRuleStays() {
        event(CallEvent.Join(IN, ALICE, ring = true, participant = 7L), listOf(groupSession(IN)))
        send(Input.Caller(IN, CallerInfo("Alice", true), t, on))
        send(Input.Answer(IN, false, t))
        send(Input.NativeUp(IN, false, t))
        send(Input.Participants.of(IN, longArrayOf(7, 8, 9), arrayOf(ALICE, "tel:+1555\u202E0000", null), t))
        assertEquals(3, state!!.present)
        assertEquals(listOf(InCall(7, ALICE), InCall(8, "tel:+15550000"), InCall(9, "")), state!!.inCall)
        assertEquals(mapOf(7L to ALICE), state!!.joiners)
        // Only so many are kept, all are counted.
        send(Input.Participants.of(IN, LongArray(40) { it.toLong() }, arrayOfNulls(3), t))
        assertEquals(40, state!!.present)
        assertEquals(MAX_PEOPLE, state!!.inCall.size)
        // Someone was there, now nobody is: over.
        t += 4_000
        val ended = send(Input.Participants.of(IN, LongArray(0), arrayOfNulls(0), t))
        assertNull(state)
        assertEquals(Outcome.ANSWERED, ended.only<Effect.LogCall>().entry.outcome)
    }

    @Test
    fun aBlockedCallerNeverRingsNorMakesARow() {
        val rules = CallRules(blocked = { it == MALLORY })
        val out = rules.reduce(null, Input.Event(CallEvent.Join(IN, MALLORY, ring = true), listOf(session(IN, MALLORY)), setOf(ME), t, 10 * 60_000L, on))
        assertNull(out.state)
        assertTrue(out.effects.toString(), out.effects.all { it is Effect.Note })
        assertTrue(rules.reduce(null, Input.Event(CallEvent.Join(OTHER, ALICE, ring = true), listOf(session(OTHER)), setOf(ME), t, 10 * 60_000L, on)).effects.any { it is Effect.LookupCaller })
    }

    private fun guid(n: Int): String = "0A1B2C3D-0000-4000-8000-%012d".format(n)

    private companion object {
        const val OUT = "AAAAAAAA-0000-4000-8000-000000000001"
        const val IN = "BBBBBBBB-0000-4000-8000-000000000002"
        const val OTHER = "CCCCCCCC-0000-4000-8000-000000000003"
        const val THIRD = "DDDDDDDD-0000-4000-8000-000000000004"

        /** A stranger. */
        const val MALLORY = "tel:+15559990000"
        const val CAROL = "tel:+15553334444"
    }
}
