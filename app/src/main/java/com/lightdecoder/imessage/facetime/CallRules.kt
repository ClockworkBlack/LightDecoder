package com.lightdecoder.imessage.facetime

import kotlin.math.abs

/**
 * The rules of FaceTime calls here: one call at a time, and what each thing that happens (an event
 * from Apple, the user's tap, a timer, the library's word on the joined call) does to it. Pure: a
 * call and what happened in, the call and what is to be done out ([Step]); the ":imessage" process's
 * [FaceTimeCalls] does those things. Tested in CallRulesTest.
 *
 * - **Placed** ([Input.Place]): the call rings out; when the other person's device joins
 *   ([CallEvent.Join]) the library joins too ([Effect.StartNative]); with no answer in
 *   [RING_OUT_MS], or once they decline, it is taken back ([Effect.CancelCall]) and shown as not
 *   answered. A placed call that never got as far as joining is always taken back.
 * - **Incoming**: only a ring for a call that is in the library's list, began, is still alive
 *   (someone is joined), from one other person, while no other call is up, and within the caller's
 *   limits ([RingLimiter]) goes on: first the main process is asked who it is ([Effect.LookupCaller]),
 *   then it rings ([Effect.ShowIncoming]) for [RING_IN_MS], unless unknown callers are silenced and
 *   this is one. Nothing is ever answered but by the user ([Input.Answer]). Every other ring is at
 *   most a missed call ([Effect.LogCall]); one handed over again after a restart, for a call that
 *   began long before, never rings.
 * - **Joining** (answered either way): the library is given [CONNECT_MS] to join; a join that takes
 *   longer is given up and shown as a call that could not be connected.
 * - **Ends**: the user's hang-up, the other side leaving (the library's participants going from
 *   someone to no one, [Input.Participants]), or the library giving up ([Input.Failed]). A device
 *   leaving ([CallEvent.Leave]) ends a ringing call only.
 */
class CallRules(private val limiter: RingLimiter = RingLimiter()) {

    /** What happened. [now] is the time (ms). */
    sealed class Input {
        /**
         * An event from Apple, with the library's call list as of it ([sessions]), the user's own
         * addresses, and how long the library's session has been up ([sinceStartMs]: an event just
         * after a start may be one handed over again from before).
         */
        data class Event(
            val event: CallEvent,
            val sessions: List<CallSessionInfo>,
            val myHandles: Set<String>,
            val now: Long,
            val sinceStartMs: Long,
            val settings: FaceTimeSettings,
        ) : Input()

        /** The main process's word on who is calling ([info] null: it did not answer). [phoneBusy]: the phone is in another call. */
        data class Caller(val guid: String, val info: CallerInfo?, val now: Long, val settings: FaceTimeSettings, val phoneBusy: Boolean = false) : Input()

        /** The user places a call to [peer]. [phoneBusy]: the phone is in another call. */
        data class Place(
            val guid: String,
            val peer: String,
            val name: String,
            val video: Boolean,
            val from: String?,
            val conversationId: String?,
            val photo: String?,
            val now: Long,
            val phoneBusy: Boolean,
            val settings: FaceTimeSettings,
        ) : Input()

        /** The library placed call [guid] (it rings on the other side now), or could not. */
        data class Placed(val guid: String, val ok: Boolean, val now: Long) : Input()

        /** The user answers the ringing call [guid], with the camera or without ("Audio only"). */
        data class Answer(val guid: String, val withCamera: Boolean, val now: Long) : Input()

        /** The user declines the ringing call [guid]. */
        data class DeclineByUser(val guid: String, val now: Long) : Input()

        /** The user hangs up (the call [guid]; null: whichever it is), or leaves the "not answered" screen. */
        data class HangUp(val guid: String?, val now: Long) : Input()

        /** A timer ([Effect.StartTimer]) of call [guid] in [phase] ran out. */
        data class Timeout(val guid: String, val phase: Phase, val now: Long) : Input()

        /** The library joined call [guid] ([video]: what it says the call is). */
        data class NativeUp(val guid: String, val video: Boolean, val now: Long) : Input()

        /** The library could not join call [guid] (it is gone, or the join failed). */
        data class NativeFailed(val guid: String, val now: Long) : Input()

        /** The library's count of the other people in call [guid]. */
        data class Participants(val guid: String, val count: Int, val now: Long) : Input()

        /** The library gave up on call [guid]. */
        data class Failed(val guid: String, val now: Long) : Input()

        /** The joined call [guid] became a video call (the camera was turned on in it). */
        data class BecameVideo(val guid: String) : Input()
    }

    /** What is to be done. */
    sealed class Effect {
        /** Ask the main process who [handle] is; answered with [Input.Caller]. */
        data class LookupCaller(val guid: String, val handle: String) : Effect()

        /** Ring: the incoming-call notice (and, with the screen off or locked, the call screen). */
        data class ShowIncoming(val call: Call) : Effect()
        data class HideIncoming(val guid: String) : Effect()

        /** After [ms], [Input.Timeout] of [guid] and [phase]; replaces the call's timer. */
        data class StartTimer(val guid: String, val phase: Phase, val ms: Long) : Effect()
        data class StopTimer(val guid: String) : Effect()

        /** Place it (the library's `createFacetime`); answered with [Input.Placed]. */
        data class CreateCall(val call: Call) : Effect()

        /** Take a placed call back (`cancelFacetime`); errors are nothing. */
        data class CancelCall(val guid: String) : Effect()

        /** Decline an incoming call (`declineFacetime`); errors are nothing. */
        data class DeclineCall(val guid: String) : Effect()

        /** Join it (`createAvc`), off the main thread; answered with [Input.NativeUp] or [Input.NativeFailed]. */
        data class StartNative(val call: Call) : Effect()

        /**
         * Leave it: the joined call is destroyed; one whose join is still running is destroyed when
         * that returns; one that never joined is taken back (placed) or declined (incoming).
         */
        data class EndNative(val call: Call) : Effect()

        /** The ringback tone of a placed call, on or off. */
        data class Ringback(val on: Boolean) : Effect()

        /** The call's row in the chat (and a missed call's notice), through the main process. */
        data class LogCall(val entry: CallLogEntry) : Effect()

        /** Tell the user why what they asked for did not happen. */
        data class Refused(val why: Refusal) : Effect()

        /** A line for the iMessage log: what happened, in words that name nobody. */
        data class Note(val text: String) : Effect()
    }

    data class Step(val state: Call?, val effects: List<Effect> = emptyList())

    fun reduce(state: Call?, input: Input): Step = when (input) {
        is Input.Event -> event(state, input)
        is Input.Caller -> caller(state, input)
        is Input.Place -> place(state, input)
        is Input.Placed -> placed(state, input)
        is Input.Answer -> answer(state, input)
        is Input.DeclineByUser -> if (state != null && state.guid == input.guid && state.incomingRing) declined(state, input.now) else Step(state)
        is Input.HangUp -> hangUp(state, input)
        is Input.Timeout -> timeout(state, input)
        is Input.NativeUp -> nativeUp(state, input)
        is Input.NativeFailed -> nativeFailed(state, input.guid, input.now)
        is Input.Participants -> participants(state, input)
        is Input.Failed -> failed(state, input)
        is Input.BecameVideo -> if (state != null && state.guid == input.guid && state.joined) Step(state.copy(video = true)) else Step(state)
    }

    // --- events from Apple -------------------------------------------------------------------------

    private fun event(state: Call?, input: Input.Event): Step {
        val mine = (input.myHandles + input.sessions.firstOrNull { it.guid == input.event.guid }?.myHandles.orEmpty()).map(CallIds::key).toSet()
        fun isMine(handle: String) = handle.isNotEmpty() && CallIds.key(handle) in mine
        val current = state?.takeIf { it.guid == input.event.guid }
        return when (val e = input.event) {
            is CallEvent.Join -> when {
                // The other person's device joined the call the user placed: answered.
                current != null && current.outgoing && current.phase == Phase.RINGING_OUT && !isMine(e.handle) -> {
                    val next = current.copy(phase = Phase.CONNECTING)
                    Step(next, listOf(Effect.StartTimer(e.guid, Phase.CONNECTING, CONNECT_MS), Effect.Ringback(false), Effect.StartNative(next)))
                }
                e.ring -> ring(state, e.guid, e.handle, input, mine)
                else -> Step(state)
            }
            is CallEvent.Invite -> ring(state, e.guid, "", input, mine)
            is CallEvent.Decline -> when {
                current == null -> Step(state)
                // The one person a placed call went to said no.
                current.outgoing && current.phase == Phase.RINGING_OUT && !isMine(e.handle) -> unanswered(current, Unanswered.DECLINED, Outcome.DECLINED, input.now)
                // Declined on another of the user's devices: it stops ringing here, and is no missed call.
                current.incomingRing && isMine(e.handle) ->
                    Step(null, ringDown(current) + Effect.LogCall(entry(current, Outcome.DECLINED, input.now)))
                else -> Step(state)
            }
            is CallEvent.Leave -> when {
                current == null || isMine(e.handle) -> Step(state)
                // The caller gave up.
                current.incomingRing -> Step(null, ringDown(current) + Effect.LogCall(entry(current, Outcome.MISSED, input.now)))
                // A device leaving does not end a call that is joined: several of a person's devices
                // may come and go. Only when the library itself says that nobody is in it any more.
                current.joined && current.peers == 0 && input.sessions.firstOrNull { it.guid == e.guid }?.active == false ->
                    if (current.phase == Phase.ACTIVE) ended(current, input.now) else Step(current.copy(abandoned = true))
                else -> Step(state)
            }
            is CallEvent.Elsewhere ->
                if (current != null && current.incomingRing) {
                    Step(null, ringDown(current) + Effect.LogCall(entry(current, Outcome.ELSEWHERE, input.now)))
                } else {
                    Step(state)
                }
        }
    }

    /**
     * A ring for call [guid]: screened, then at most a missed call, unless everything says it is a
     * call ringing now that may ring here (see the class's notes).
     */
    private fun ring(state: Call?, guid: String, hint: String, input: Input.Event, mine: Set<String>): Step {
        if (!input.settings.on) return Step(state)
        // This call already: ringing, answered, or the user's own.
        if (state != null && state.guid == guid && state.live) return Step(state)
        // Only a call the library knows, and that began (said in the log: it would explain a call that never rang).
        val session = input.sessions.firstOrNull { it.guid == guid }
            ?: return Step(state, listOf(Effect.Note("a FaceTime ring for a call that is not in the library's list was ignored")))
        val startMs = session.startMs
            ?: return Step(state, listOf(Effect.Note("a FaceTime ring for a call that has not begun was ignored")))
        val others = session.members.mapNotNull(CallIds::handle).filter { CallIds.key(it) !in mine }.distinctBy(CallIds::key)
        val caller = CallIds.handle(hint)?.takeIf { CallIds.key(it) !in mine } ?: others.firstOrNull()
            ?: return Step(state, listOf(Effect.Note("a FaceTime ring from nobody a call could come from was ignored")))
        val who = CallIds.key(caller)
        val missed = CallLogEntry(guid, caller, outgoing = false, video = session.video, startedMs = input.now, durationMs = 0, outcome = Outcome.MISSED)
        fun notRung(why: String, quiet: Boolean): Step =
            if (limiter.notice(who, guid, input.now)) {
                Step(state, listOf(Effect.LogCall(missed.copy(quiet = quiet)), Effect.Note(why)))
            } else {
                Step(state, listOf(Effect.Note("$why; not noted as missed either (it was already, or too many from this caller)")))
            }
        return when {
            others.size > 1 -> notRung("a group FaceTime call was not rung: group calls are not answered here yet", quiet = false)
            // Nobody is in it any more: a ring that comes late.
            !session.active -> notRung("a FaceTime ring for a call nobody is in any more was not rung", quiet = true)
            // Handed over again after a restart, for a call that began long before.
            input.sinceStartMs < REPLAY_WINDOW_MS && abs(input.now - startMs) > MAX_RING_AGE_MS ->
                notRung("an old FaceTime ring handed over after a restart was not rung", quiet = true)
            state != null && state.live -> notRung("a FaceTime call during another call was not rung", quiet = false)
            else -> when (limiter.ring(who, guid, input.now)) {
                RingLimiter.Verdict.DROP -> Step(state, listOf(Effect.Note("a FaceTime ring was dropped: the same call again, or too many from this caller")))
                RingLimiter.Verdict.MISSED_ONLY ->
                    Step(state, listOf(Effect.LogCall(missed.copy(quiet = true)), Effect.Note("a FaceTime ring past the caller's limit was not rung")))
                RingLimiter.Verdict.RING -> {
                    val call = Call(
                        guid = guid, peer = caller, name = "", video = session.video, outgoing = false, phase = Phase.SCREENING,
                        from = session.myHandles.firstOrNull() ?: input.myHandles.firstOrNull(), startedMs = input.now,
                    )
                    // A placed call's "not answered" left on screen makes way.
                    Step(call, listOfNotNull(state?.let { Effect.StopTimer(it.guid) }, Effect.LookupCaller(guid, caller)))
                }
            }
        }
    }

    private fun caller(state: Call?, input: Input.Caller): Step {
        if (state == null || state.guid != input.guid || state.phase != Phase.SCREENING) return Step(state)
        val info = input.info
        // The phone is in another call (a cellular one): two calls would fight over its sound.
        if (input.phoneBusy) {
            return Step(
                null,
                listOf(Effect.LogCall(entry(state, Outcome.MISSED, input.now)), Effect.Note("a FaceTime call during a phone call was not rung")),
            )
        }
        // Unknown callers silenced: someone with no contact name and no chat does not ring. Not
        // told who it is (the main process did not answer), it is not known either.
        if (input.settings.silenceUnknown && info?.known != true) {
            return Step(
                null,
                listOf(
                    Effect.LogCall(entry(state, Outcome.MISSED, input.now).copy(quiet = true)),
                    Effect.Note("a FaceTime call from an unknown caller was silenced"),
                ),
            )
        }
        val call = state.copy(
            phase = Phase.RINGING_IN,
            name = CallIds.name(info?.name),
            conversationId = info?.conversationId,
            photo = info?.photo,
        )
        return Step(call, listOf(Effect.ShowIncoming(call), Effect.StartTimer(call.guid, Phase.RINGING_IN, RING_IN_MS)))
    }

    // --- the user ----------------------------------------------------------------------------------

    private fun place(state: Call?, input: Input.Place): Step {
        fun refuse(why: Refusal) = Step(state, listOf(Effect.Refused(why)))
        if (!input.settings.on) return refuse(Refusal.OFF)
        if (state != null && state.live) return refuse(Refusal.IN_CALL)
        if (input.phoneBusy) return refuse(Refusal.PHONE_BUSY)
        val peer = CallIds.handle(input.peer) ?: return refuse(Refusal.NOT_CALLABLE)
        if (!CallIds.isGuid(input.guid)) return refuse(Refusal.NOT_CALLABLE)
        val call = Call(
            guid = input.guid, peer = peer, name = CallIds.name(input.name), video = input.video, outgoing = true,
            phase = Phase.RINGING_OUT, from = input.from?.let(CallIds::handle), conversationId = input.conversationId,
            photo = input.photo, startedMs = input.now, withCamera = input.video,
        )
        return Step(
            call,
            listOfNotNull(
                state?.let { Effect.StopTimer(it.guid) },
                Effect.CreateCall(call),
                Effect.Ringback(true),
                // Until the library says it rings: the time to place it, and the ring's own.
                Effect.StartTimer(call.guid, Phase.RINGING_OUT, PLACE_MS + RING_OUT_MS),
            ),
        )
    }

    private fun placed(state: Call?, input: Input.Placed): Step {
        val current = state?.takeIf { it.guid == input.guid && it.outgoing }
        return when {
            // Ended while it was being placed: whatever the library made of it is taken back.
            current == null || current.phase == Phase.UNANSWERED -> Step(state, listOf(Effect.CancelCall(input.guid)))
            !input.ok -> if (current.phase == Phase.RINGING_OUT) unanswered(current, Unanswered.FAILED, Outcome.FAILED, input.now) else Step(state)
            current.phase == Phase.RINGING_OUT -> Step(state, listOf(Effect.StartTimer(input.guid, Phase.RINGING_OUT, RING_OUT_MS)))
            else -> Step(state)
        }
    }

    private fun answer(state: Call?, input: Input.Answer): Step {
        if (state == null || state.guid != input.guid || state.phase != Phase.RINGING_IN) return Step(state, listOf(Effect.Refused(Refusal.GONE)))
        val next = state.copy(phase = Phase.CONNECTING, withCamera = input.withCamera && state.video)
        return Step(next, listOf(Effect.HideIncoming(next.guid), Effect.StartTimer(next.guid, Phase.CONNECTING, CONNECT_MS), Effect.StartNative(next)))
    }

    private fun declined(call: Call, now: Long): Step =
        Step(null, ringDown(call) + listOf(Effect.DeclineCall(call.guid), Effect.LogCall(entry(call, Outcome.DECLINED, now))))

    private fun hangUp(state: Call?, input: Input.HangUp): Step {
        val call = state?.takeIf { input.guid == null || it.guid == input.guid } ?: return Step(state)
        return when (call.phase) {
            Phase.SCREENING, Phase.RINGING_IN -> declined(call, input.now)
            Phase.RINGING_OUT -> Step(
                null,
                listOf(Effect.StopTimer(call.guid), Effect.Ringback(false), Effect.CancelCall(call.guid), Effect.LogCall(entry(call, Outcome.CANCELLED, input.now))),
            )
            Phase.UNANSWERED -> Step(null, listOf(Effect.StopTimer(call.guid)))
            // Answered, not joined yet: the join is given up (taken back or declined if it never got that far).
            Phase.CONNECTING -> Step(
                null,
                listOf(
                    Effect.StopTimer(call.guid),
                    Effect.EndNative(call),
                    Effect.LogCall(entry(call, if (call.outgoing) Outcome.CANCELLED else Outcome.DECLINED, input.now)),
                ),
            )
            Phase.ACTIVE -> ended(call, input.now)
        }
    }

    // --- timers ------------------------------------------------------------------------------------

    private fun timeout(state: Call?, input: Input.Timeout): Step {
        val call = state?.takeIf { it.guid == input.guid && it.phase == input.phase } ?: return Step(state)
        return when (call.phase) {
            Phase.RINGING_OUT -> unanswered(call, Unanswered.NO_ANSWER, Outcome.UNANSWERED, input.now)
            Phase.SCREENING, Phase.RINGING_IN -> Step(null, listOf(Effect.HideIncoming(call.guid), Effect.LogCall(entry(call, Outcome.MISSED, input.now))))
            Phase.UNANSWERED -> Step(null)
            // The library's join never came back: given up (what it makes of it later is left at once).
            Phase.CONNECTING -> {
                val step = notJoined(call, input.now)
                step.copy(effects = step.effects + Effect.Note("a FaceTime call's join took too long and was given up"))
            }
            Phase.ACTIVE -> Step(state)
        }
    }

    // --- the library's word on the joined call -----------------------------------------------------

    private fun nativeUp(state: Call?, input: Input.NativeUp): Step {
        val call = state?.takeIf { it.guid == input.guid && it.phase == Phase.CONNECTING } ?: return Step(state)
        // The other side left while it was joining, and nobody is in it: there is no call to be in.
        if (call.peers == 0 && (call.abandoned || call.peerSeen)) {
            val outcome = if (call.outgoing) Outcome.DECLINED else Outcome.MISSED
            return Step(null, listOf(Effect.StopTimer(call.guid), Effect.EndNative(call), Effect.LogCall(entry(call, outcome, input.now))))
        }
        return Step(call.copy(phase = Phase.ACTIVE, activeSinceMs = input.now, video = input.video), listOf(Effect.StopTimer(call.guid)))
    }

    private fun nativeFailed(state: Call?, guid: String, now: Long): Step {
        val call = state?.takeIf { it.guid == guid && it.phase == Phase.CONNECTING } ?: return Step(state)
        // Never joined: a placed call is taken back, an answered one declined (the caller stops waiting).
        val undo = if (call.outgoing) Effect.CancelCall(guid) else Effect.DeclineCall(guid)
        val shown = call.copy(phase = Phase.UNANSWERED, unanswered = Unanswered.FAILED)
        return Step(
            shown,
            listOf(undo, Effect.LogCall(entry(call, Outcome.FAILED, now)), Effect.StartTimer(guid, Phase.UNANSWERED, UNANSWERED_SHOWN_MS)),
        )
    }

    private fun participants(state: Call?, input: Input.Participants): Step {
        val call = state?.takeIf { it.guid == input.guid && it.joined } ?: return Step(state)
        val count = input.count.coerceAtLeast(0)
        val next = call.copy(peers = count, peerSeen = call.peerSeen || count > 0, abandoned = call.abandoned && count == 0)
        // Someone was in it, and now nobody is: the other side hung up.
        if (call.phase == Phase.ACTIVE && count == 0 && call.peerSeen) return ended(next, input.now)
        return Step(next)
    }

    private fun failed(state: Call?, input: Input.Failed): Step {
        val call = state?.takeIf { it.guid == input.guid } ?: return Step(state)
        return when (call.phase) {
            Phase.ACTIVE -> ended(call, input.now)
            Phase.CONNECTING -> notJoined(call, input.now)
            else -> Step(state)
        }
    }

    /**
     * A call that was being joined is given up (the library said it failed, or never came back):
     * whatever the join made or still makes is left, and the screen says it could not be connected.
     */
    private fun notJoined(call: Call, now: Long): Step {
        val shown = call.copy(phase = Phase.UNANSWERED, unanswered = Unanswered.FAILED)
        return Step(
            shown,
            listOf(Effect.EndNative(call), Effect.LogCall(entry(call, Outcome.FAILED, now)), Effect.StartTimer(call.guid, Phase.UNANSWERED, UNANSWERED_SHOWN_MS)),
        )
    }

    // --- shared ------------------------------------------------------------------------------------

    /** A joined call is over: left, and its row says how long it was. */
    private fun ended(call: Call, now: Long): Step =
        Step(null, listOf(Effect.EndNative(call), Effect.LogCall(entry(call, Outcome.ANSWERED, now))))

    /** A placed call nobody took: taken back, and shown as such until the user leaves the screen (or [UNANSWERED_SHOWN_MS]). */
    private fun unanswered(call: Call, why: Unanswered, outcome: Outcome, now: Long): Step =
        Step(
            call.copy(phase = Phase.UNANSWERED, unanswered = why),
            listOf(
                Effect.Ringback(false),
                Effect.CancelCall(call.guid),
                Effect.LogCall(entry(call, outcome, now)),
                Effect.StartTimer(call.guid, Phase.UNANSWERED, UNANSWERED_SHOWN_MS),
            ),
        )

    private fun ringDown(call: Call): List<Effect> = listOf(Effect.HideIncoming(call.guid), Effect.StopTimer(call.guid))

    private fun entry(call: Call, outcome: Outcome, now: Long): CallLogEntry = CallLogEntry(
        guid = call.guid,
        peer = call.peer,
        outgoing = call.outgoing,
        video = call.video,
        startedMs = call.startedMs,
        durationMs = if (outcome == Outcome.ANSWERED && call.activeSinceMs > 0) (now - call.activeSinceMs).coerceAtLeast(0) else 0,
        outcome = outcome,
        conversationId = call.conversationId,
    )

    companion object {
        /** A placed call rings this long before it is taken back. */
        const val RING_OUT_MS = 30_000L

        /** What placing a call (reaching Apple's relay, sending the invitation) may take on top of that. */
        const val PLACE_MS = 20_000L

        /** An incoming call rings this long before it is a missed one. */
        const val RING_IN_MS = 30_000L

        /** The library is given this long to join an answered call (reach Apple's relay, join) before it is given up. */
        const val CONNECT_MS = 45_000L

        /** "No answer" stays on screen this long when the user does not leave it. */
        const val UNANSWERED_SHOWN_MS = 60_000L

        /** Events in the first minute after the library's session came up may be ones handed over again from before. */
        const val REPLAY_WINDOW_MS = 60_000L

        /** A ring handed over then rings only for a call that began no longer ago than this. */
        const val MAX_RING_AGE_MS = 2 * 60_000L
    }
}
