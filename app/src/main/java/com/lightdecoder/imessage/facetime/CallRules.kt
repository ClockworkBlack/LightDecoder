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
 * - **Incoming**: who calls is the sender of the call's join as Apple vouches for it, and nothing
 *   else (never the call's member list, which its sender writes; never the user's own address).
 *   Only a ring for a call that is in the library's list, began, is still alive
 *   (someone is joined), from one other person, while no other call is up, and within the caller's
 *   and everyone's limits ([RingLimiter]) goes on: first the main process is asked who it is ([Effect.LookupCaller]),
 *   then it rings ([Effect.ShowIncoming]) for [RING_IN_MS], unless unknown callers are silenced and
 *   this is one. Nothing is ever answered but by the user ([Input.Answer]). Every other ring is at
 *   most a missed call ([Effect.LogCall]); one handed over again after a restart, for a call that
 *   began long before, never rings.
 * - **Joining** (answered either way): the library is given [CONNECT_MS] to join; a join that takes
 *   longer is given up and shown as a call that could not be connected.
 * - **Ends**: the user's hang-up, the other side leaving (the library's participants going from
 *   someone to no one, [Input.Participants]), or the library giving up ([Input.Failed]). A device
 *   leaving ([CallEvent.Leave]) ends a ringing call only.
 * - **Groups**: a call placed to several people ends unanswered only once every one of them said
 *   no, and is answered by the join of one of them. An incoming group call rings only on the join
 *   of its caller (as for one person), shown as theirs ("caller · Group FaceTime"): its member list
 *   is the caller's word, so it is only counted. An invitation that names nobody rings a group call
 *   only right after its caller's own ring ([RING_IN_MS]), counted in the call's own allowance.
 *   Nobody leaving ([CallEvent.Leave], which any member can say of anyone) stops or ends a group
 *   call; being taken out of it ([CallEvent.Removed]) stops its ring. Dismissed, a group ring from
 *   someone the user does not know is ignored, not declined ([Call.ignoreOnly]).
 *
 * [blocked] says whom the user blocked (the ":imessage" process's copy of the block list,
 * [BlockedCallers]): their rings are dropped before anything else is done, with no ring, no row and
 * no missed-call notice, whether the main process answers or not; the main process's own word
 * ([CallerInfo.blocked]) drops them as well.
 */
class CallRules(
    private val limiter: RingLimiter = RingLimiter(),
    private val blocked: (String) -> Boolean = { false },
) {

    /**
     * Who joined each call, by its guid, and with which participant id: the sender of a join as
     * Apple's servers vouch for it ([CallEvent.Join.handle]), never the user's own. The only word on
     * who a call is from that is taken: what a call's member list says is written by whoever sent
     * it. At most [MAX_JOINERS] calls (the one longest unheard of forgotten first), and per call
     * [MAX_JOINERS_PER_CALL] people ([MAX_PEOPLE] for the call there is), the last to join last.
     * Only touched from [reduce] (one queue).
     */
    private val joiners = LinkedHashMap<String, LinkedHashMap<Long, String>>(16, 0.75f, true)

    /**
     * The caller's own ring of each call ([CallEvent.Join] with a ring) that could not be checked
     * yet (the library did not list the call, or it had not begun): who, and when. A group call's
     * invitation ([CallEvent.Invite]) rings only within [RING_IN_MS] of it, once. Forgotten as soon
     * as that ring is decided any other way (rung, not rung, noted as missed, dropped), so an
     * invitation never rings a call its caller's own ring was not allowed to, nor one the user
     * already dismissed.
     */
    private val ringJoins = LinkedHashMap<String, Pair<String, Long>>(16, 0.75f, true)

    /** Who last joined call [guid] (the latest of [joiners]), or null. */
    private fun lastJoiner(guid: String): String? = joiners[guid]?.values?.lastOrNull()

    private fun remember(guid: String, participant: Long, handle: String, state: Call?) {
        val people = joiners.getOrPut(guid) { LinkedHashMap() }
        people.remove(participant)
        people[participant] = handle
        val cap = if (state != null && state.guid == guid && state.live) MAX_PEOPLE else MAX_JOINERS_PER_CALL
        while (people.size > cap) people.remove(people.keys.first())
        while (joiners.size > MAX_JOINERS) joiners.remove(joiners.keys.first())
    }

    /** Who joined call [guid], by participant id, as the screens may label them. */
    private fun joinedOf(guid: String): Map<Long, String> = joiners[guid]?.toMap().orEmpty()

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

        /**
         * The user places a call to [peer], and with a group call to everyone of [peers] ([peer]
         * first). [phoneBusy]: the phone is in another call. [fromGroup]: placed from a group chat, or
         * to several people (even if only one of them is called in the end).
         */
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
            val peers: List<String> = listOf(peer),
            val fromGroup: Boolean = false,
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

        /**
         * The library's word on the other people in call [guid]: how many ([count]), their ids and the
         * address it reports for each ([handles], in the same order; never taken as who they are).
         */
        data class Participants(
            val guid: String,
            val count: Int,
            val now: Long,
            val ids: List<Long> = emptyList(),
            val handles: List<String?> = emptyList(),
        ) : Input() {
            companion object {
                /** From the library's arrays, as they come ([MAX_PEOPLE] at most are kept, all are counted). */
                fun of(guid: String, ids: LongArray, handles: Array<String?>, now: Long) = Participants(
                    guid, ids.size, now, ids.take(MAX_PEOPLE), List(minOf(ids.size, MAX_PEOPLE)) { handles.getOrNull(it) },
                )
            }
        }

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

        /**
         * Someone calls while the user is in a call ([call] waits): it is offered over the call there
         * is ("End & Accept", Decline), ringing as any call does, and the call there is goes on.
         */
        data class ShowWaiting(val call: Call) : Effect()
        data class HideWaiting(val guid: String) : Effect()
    }

    data class Step(val state: Call?, val effects: List<Effect> = emptyList())

    /**
     * The call that waits while the user is in another ([Effect.ShowWaiting]): one at most. Answered
     * ([Input.Answer]), the call the user is in ends and this one is joined in its place; declined,
     * timed out or given up by its caller, it goes as a ringing call would. When the call the user is
     * in ends first, it becomes the call that rings. Only touched from [reduce] (one queue).
     */
    var waiting: Call? = null
        private set

    fun reduce(state: Call?, input: Input): Step {
        val step = waitingStep(state, input) ?: reduceOne(state, input)
        // A call that was here and is no longer (declined, hung up, missed, ended): its caller's ring
        // was handled, so no invitation that follows rings it again.
        if (state != null && step.state?.guid != state.guid) ringJoins.remove(state.guid)
        // The call the user was in is over while another waits: that one is the call now, ringing as usual.
        val w = waiting
        if (w != null && (step.state == null || !step.state.live)) {
            waiting = null
            val ring = if (w.phase == Phase.RINGING_IN) listOf(Effect.HideWaiting(w.guid), Effect.ShowIncoming(w)) else emptyList()
            return Step(w, step.effects + listOfNotNull(step.state?.let { Effect.StopTimer(it.guid) }) + ring)
        }
        return step
    }

    /** What [input] does when it is about the call that waits; null when it is not. */
    private fun waitingStep(state: Call?, input: Input): Step? {
        val w = waiting ?: return null
        fun gone(outcome: Outcome, vararg more: Effect): Step {
            waiting = null
            return Step(state, listOf(Effect.StopTimer(w.guid), Effect.HideWaiting(w.guid)) + more + Effect.LogCall(entry(w, outcome, nowOf(input))))
        }
        return when (input) {
            is Input.Caller -> if (input.guid != w.guid || w.phase != Phase.SCREENING) null else {
                val known = input.info?.known == true
                if ((input.settings.silenceUnknown && !known) || (!known && !limiter.unknownRing(input.now))) {
                    waiting = null
                    Step(state, listOf(Effect.LogCall(entry(w, Outcome.MISSED, input.now).copy(quiet = true)), Effect.Note("a FaceTime call from an unknown caller during a call was not offered")))
                } else {
                    val call = w.copy(phase = Phase.RINGING_IN, name = CallIds.name(input.info?.name), conversationId = input.info?.conversationId, photo = input.info?.photo)
                    waiting = call
                    Step(state, listOf(Effect.ShowWaiting(call), Effect.StartTimer(call.guid, Phase.RINGING_IN, RING_IN_MS)))
                }
            }
            is Input.Answer -> if (input.guid != w.guid || w.phase != Phase.RINGING_IN) null else {
                // The call the user is in ends, and this one is joined in its place.
                val ended = if (state != null && state.live) hangUp(state, Input.HangUp(state.guid, input.now)).effects else emptyList()
                waiting = null
                val next = w.copy(phase = Phase.CONNECTING, withCamera = input.withCamera && w.video)
                Step(next, ended + listOf(Effect.HideWaiting(next.guid), Effect.StartTimer(next.guid, Phase.CONNECTING, CONNECT_MS), Effect.StartNative(next)))
            }
            is Input.DeclineByUser -> if (input.guid != w.guid) null else gone(Outcome.DECLINED, Effect.DeclineCall(w.guid))
            is Input.HangUp -> if (input.guid != w.guid) null else gone(Outcome.DECLINED, Effect.DeclineCall(w.guid))
            is Input.Timeout -> if (input.guid != w.guid) null else if (input.phase == w.phase) gone(Outcome.MISSED) else Step(state)
            is Input.Event -> if (input.event.guid != w.guid) null else {
                val mine = (input.myHandles + input.sessions.firstOrNull { it.guid == w.guid }?.myHandles.orEmpty()).map(CallIds::key).toSet()
                fun isMine(handle: String) = handle.isNotEmpty() && CallIds.key(handle) in mine
                when (val e = input.event) {
                    // Declined, or answered, on another of the user's devices.
                    is CallEvent.Decline -> if (isMine(e.handle)) gone(Outcome.DECLINED) else Step(state)
                    is CallEvent.Elsewhere -> gone(Outcome.ELSEWHERE)
                    // The caller gave up.
                    is CallEvent.Leave -> if (isMine(e.handle)) Step(state) else gone(Outcome.MISSED)
                    // It rings again: it waits already.
                    else -> Step(state)
                }
            }
            else -> null
        }
    }

    private fun nowOf(input: Input): Long = when (input) {
        is Input.Event -> input.now
        is Input.Caller -> input.now
        is Input.Place -> input.now
        is Input.Placed -> input.now
        is Input.Answer -> input.now
        is Input.DeclineByUser -> input.now
        is Input.HangUp -> input.now
        is Input.Timeout -> input.now
        is Input.NativeUp -> input.now
        is Input.NativeFailed -> input.now
        is Input.Participants -> input.now
        is Input.Failed -> input.now
        is Input.BecameVideo -> 0L
    }

    private fun reduceOne(state: Call?, input: Input): Step = when (input) {
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
            is CallEvent.Join -> {
                // Remembered on every join, a ring or not: an invitation that names nobody is this one's.
                val joiner = e.handle.takeIf { it.isNotEmpty() && !isMine(it) }
                if (joiner != null) {
                    remember(e.guid, e.participant, joiner, state)
                    if (e.ring) {
                        ringJoins[e.guid] = joiner to input.now
                        while (ringJoins.size > MAX_JOINERS) ringJoins.remove(ringJoins.keys.first())
                    }
                }
                // The call there is learns who joined it (its screen labels only them by name).
                val seen = if (joiner != null && current != null) current.copy(joiners = joinedOf(e.guid)) else null
                val now = seen ?: state
                when {
                    // The other person's device joined the call the user placed: answered. Only a join
                    // that says whose it is, and is not the user's own (the person called, or another
                    // address of theirs: which those are is not known here); in a group call, one of
                    // the people it was placed to.
                    seen != null && seen.outgoing && seen.phase == Phase.RINGING_OUT && joiner != null &&
                        (!seen.group || seen.peers.any { CallIds.key(it) == CallIds.key(joiner) }) -> {
                        val next = seen.copy(phase = Phase.CONNECTING)
                        Step(next, listOf(Effect.StartTimer(e.guid, Phase.CONNECTING, CONNECT_MS), Effect.Ringback(false), Effect.StartNative(next)))
                    }
                    !e.ring -> Step(now)
                    !input.settings.on -> { ringJoins.remove(e.guid); Step(now) }
                    // This call is already ringing, answered or the user's own: the ring is handled.
                    now != null && now.guid == e.guid && now.live -> { ringJoins.remove(e.guid); Step(now) }
                    // A ring is from its sender, and from nobody else: none named, or the user's own
                    // (a call placed on another of their devices), is no call for here.
                    joiner == null -> Step(now, listOf(Effect.Note("a FaceTime ring that names no sender, or the user's own address, was ignored")))
                    else -> ring(now, e.guid, joiner, input, mine, invite = false)
                }
            }
            is CallEvent.Invite -> when {
                !input.settings.on -> Step(state)
                state != null && state.guid == e.guid && state.live -> Step(state)
                // A group call's invitation may come from any of its members (it names nobody): only
                // right after its caller's own ring, and in the call's own allowance.
                isGroup(input.sessions.firstOrNull { it.guid == e.guid }, mine) -> {
                    val rung = ringJoins[e.guid]
                    if (rung == null || input.now - rung.second !in 0..RING_IN_MS) {
                        Step(state, listOf(Effect.Note("a Group FaceTime invitation that did not follow its caller's own ring was ignored")))
                    } else {
                        ring(state, e.guid, rung.first, input, mine, invite = true)
                    }
                }
                // An invitation names nobody: it is from whoever joined that call before, as Apple said.
                else -> when (val joiner = lastJoiner(e.guid)) {
                    null -> Step(state, listOf(Effect.Note("a FaceTime invitation to a call nobody was seen joining was ignored")))
                    else -> ring(state, e.guid, joiner, input, mine, invite = false)
                }
            }
            is CallEvent.Decline -> when {
                current == null -> Step(state)
                // The one person a placed call went to said no.
                current.outgoing && current.phase == Phase.RINGING_OUT && !current.group && !isMine(e.handle) ->
                    unanswered(current, Unanswered.DECLINED, Outcome.DECLINED, input.now)
                // One of the people of a placed group call said no: it ends once everyone has. A no
                // that says nobody's, or not of someone it went to, says nothing.
                current.outgoing && current.phase == Phase.RINGING_OUT && current.group -> {
                    val key = e.handle.takeIf { it.isNotEmpty() && !isMine(it) }?.let(CallIds::key)
                    when {
                        key == null || current.peers.none { CallIds.key(it) == key } -> Step(state)
                        else -> {
                            val next = current.copy(declined = current.declined + key)
                            if (next.peers.all { CallIds.key(it) in next.declined }) {
                                unanswered(next, Unanswered.DECLINED, Outcome.DECLINED, input.now)
                            } else {
                                Step(next)
                            }
                        }
                    }
                }
                // Declined on another of the user's devices: it stops ringing here, and is no missed call.
                current.incomingRing && isMine(e.handle) ->
                    Step(null, ringDown(current) + Effect.LogCall(entry(current, Outcome.DECLINED, input.now)))
                else -> Step(state)
            }
            is CallEvent.Leave -> when {
                current == null || isMine(e.handle) -> Step(state)
                // In a group any member can say anyone left: nothing is stopped or ended by it (the
                // library's own word on who is in the call does that, [Input.Participants]).
                current.group -> Step(state)
                // The caller gave up.
                current.incomingRing -> Step(null, ringDown(current) + Effect.LogCall(entry(current, Outcome.MISSED, input.now)))
                // A device leaving does not end a call that is joined: several of a person's devices
                // may come and go. Only when the library itself says that nobody is in it any more.
                current.joined && current.present == 0 && input.sessions.firstOrNull { it.guid == e.guid }?.active == false ->
                    if (current.phase == Phase.ACTIVE) ended(current, input.now) else Step(current.copy(abandoned = true))
                else -> Step(state)
            }
            is CallEvent.Elsewhere ->
                if (current != null && current.incomingRing) {
                    Step(null, ringDown(current) + Effect.LogCall(entry(current, Outcome.ELSEWHERE, input.now)))
                } else {
                    Step(state)
                }
            // The user was taken out of a call that rings here: it stops.
            is CallEvent.Removed ->
                if (current != null && current.incomingRing && e.handles.any(::isMine)) {
                    Step(null, ringDown(current) + Effect.LogCall(entry(current, Outcome.MISSED, input.now)))
                } else {
                    Step(state)
                }
        }
    }

    /** The other people [session]'s member list names (the caller's word), each once: none for no session. */
    private fun othersOf(session: CallSessionInfo?, mine: Set<String>): List<String> =
        session?.members.orEmpty().mapNotNull(CallIds::handle).filter { CallIds.key(it) !in mine }.distinctBy(CallIds::key)

    /** Whether [session]'s member list names more people than the caller and the user. */
    private fun isGroup(session: CallSessionInfo?, mine: Set<String>): Boolean = othersOf(session, mine).size > 1

    /**
     * A ring for call [guid] from [caller] (the sender Apple vouches for, never the user's own):
     * screened, then at most a missed call, unless everything says it is a call ringing now that
     * may ring here (see the class's notes). The call's member list never says who calls: it is
     * only held against the caller (who must be in it, where it names anyone) and counted. [invite]:
     * rung by an invitation that named nobody, after the caller's own ring of a group call: counted
     * in that call's allowance, not the caller's.
     */
    private fun ring(state: Call?, guid: String, caller: String, input: Input.Event, mine: Set<String>, invite: Boolean): Step {
        if (!input.settings.on) return Step(state)
        // This call already: ringing, answered, or the user's own.
        if (state != null && state.guid == guid && state.live) return Step(state)
        // Only a call the library knows, and that began (said in the log: it would explain a call that never rang).
        // These two leave the caller's ring to be checked again by an invitation ([ringJoins]).
        val session = input.sessions.firstOrNull { it.guid == guid }
            ?: return Step(state, listOf(Effect.Note("a FaceTime ring for a call that is not in the library's list was ignored")))
        val startMs = session.startMs
            ?: return Step(state, listOf(Effect.Note("a FaceTime ring for a call that has not begun was ignored")))
        // Decided from here on, whichever way: no invitation rings it again.
        ringJoins.remove(guid)
        val members = session.members.mapNotNull(CallIds::handle)
        val others = members.filter { CallIds.key(it) !in mine }.distinctBy(CallIds::key)
        if (members.isNotEmpty() && members.none { CallIds.key(it) == CallIds.key(caller) }) {
            return Step(state, listOf(Effect.Note("a FaceTime ring from someone who is not in the call was ignored")))
        }
        // Someone the user blocked: no ring, no row, no notice.
        if (runCatching { blocked(caller) }.getOrDefault(false)) {
            return Step(state, listOf(Effect.Note("a FaceTime ring from a blocked caller was dropped")))
        }
        val group = others.size > 1
        val who = if (invite && group) RingLimiter.callKey(guid) else CallIds.key(caller)
        val listed = if (group) others.take(MAX_PEOPLE) else emptyList()
        val missed = CallLogEntry(
            guid, caller, outgoing = false, video = session.video, startedMs = input.now, durationMs = 0, outcome = Outcome.MISSED,
            group = group, members = listed,
        )
        // An invitation's missed call is counted in the call's bucket, and held to and charged against
        // its caller's own notes too: a new guid each time never notes past their own limit.
        val heldByCaller = invite && group
        fun notRung(why: String, quiet: Boolean): Step =
            if ((!heldByCaller || limiter.noticeAllows(CallIds.key(caller), input.now)) && limiter.notice(who, guid, input.now)) {
                if (heldByCaller) limiter.charge(CallIds.key(caller), RingLimiter.Verdict.MISSED_ONLY, input.now)
                Step(state, listOf(Effect.LogCall(missed.copy(quiet = quiet)), Effect.Note(why)))
            } else {
                Step(state, listOf(Effect.Note("$why; not noted as missed either (it was already, or too many from this caller)")))
            }
        return when {
            // Nobody is in it any more: a ring that comes late.
            !session.active -> notRung("a FaceTime ring for a call nobody is in any more was not rung", quiet = true)
            // Handed over again after a restart, for a call that began long before.
            input.sinceStartMs < REPLAY_WINDOW_MS && abs(input.now - startMs) > MAX_RING_AGE_MS ->
                notRung("an old FaceTime ring handed over after a restart was not rung", quiet = true)
            // In a call: this one waits, offered over it (one at a time; a second one meanwhile is a missed call).
            state != null && state.phase == Phase.ACTIVE && waiting == null -> when (limiter.ring(who, guid, input.now)) {
                RingLimiter.Verdict.RING -> {
                    waiting = Call(
                        guid = guid, peer = caller, name = "", video = session.video, outgoing = false, phase = Phase.SCREENING,
                        from = session.myHandles.firstOrNull() ?: input.myHandles.firstOrNull(), startedMs = input.now,
                    )
                    Step(state, listOf(Effect.LookupCaller(guid, caller)))
                }
                RingLimiter.Verdict.MISSED_ONLY -> Step(state, listOf(Effect.LogCall(missed.copy(quiet = true)), Effect.Note("a FaceTime ring past the caller's limit was not offered")))
                RingLimiter.Verdict.DROP -> Step(state, listOf(Effect.Note("a FaceTime ring was dropped: the same call again, or too many from this caller")))
            }
            state != null && state.live -> notRung("a FaceTime call during another call was not rung", quiet = false)
            // An invitation rings no call its caller could not ring here now: their own limits hold.
            invite && group && !limiter.allows(CallIds.key(caller), input.now) ->
                Step(state, listOf(Effect.Note("a Group FaceTime invitation past its caller's limit was not rung")))
            else -> when (
                if (invite && group) {
                    // Counted in the call's bucket (each guid rings once), and charged to its caller too:
                    // a new guid each time never rings past their own limits.
                    limiter.callRing(guid, input.now).also { limiter.charge(CallIds.key(caller), it, input.now) }
                } else {
                    limiter.ring(who, guid, input.now)
                }
            ) {
                RingLimiter.Verdict.DROP -> Step(state, listOf(Effect.Note("a FaceTime ring was dropped: the same call again, or too many from this caller")))
                RingLimiter.Verdict.MISSED_ONLY ->
                    Step(state, listOf(Effect.LogCall(missed.copy(quiet = true)), Effect.Note("a FaceTime ring past the caller's limit was not rung")))
                RingLimiter.Verdict.RING -> {
                    val call = Call(
                        guid = guid, peer = caller, name = "", video = session.video, outgoing = false, phase = Phase.SCREENING,
                        from = session.myHandles.firstOrNull() ?: input.myHandles.firstOrNull(), startedMs = input.now,
                        group = group, others = (others.size - 1).coerceIn(0, MAX_PEOPLE), listed = listed, joiners = joinedOf(guid),
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
        // The main process says the user blocked them (its copy here had not said so yet): no ring, no row, no notice.
        if (info?.blocked == true) return Step(null, listOf(Effect.Note("a FaceTime ring from a blocked caller was dropped")))
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
        // Callers nobody knows share one small allowance of rings an hour, whoever they say they are.
        if (info?.known != true && !limiter.unknownRing(input.now)) {
            return Step(
                null,
                listOf(
                    Effect.LogCall(entry(state, Outcome.MISSED, input.now).copy(quiet = true)),
                    Effect.Note("a FaceTime call from an unknown caller was not rung: too many unknown callers this hour"),
                ),
            )
        }
        val call = state.copy(
            phase = Phase.RINGING_IN,
            name = CallIds.name(info?.name),
            conversationId = info?.conversationId,
            photo = info?.photo,
            known = info?.known == true,
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
        // Everyone it goes to, each once, [MAX_PEOPLE] at most, the first first.
        val people = (listOf(peer) + input.peers.mapNotNull(CallIds::handle)).distinctBy(CallIds::key).take(MAX_PEOPLE)
        val call = Call(
            guid = input.guid, peer = peer, name = CallIds.name(input.name), video = input.video, outgoing = true,
            phase = Phase.RINGING_OUT, from = input.from?.let(CallIds::handle), conversationId = input.conversationId,
            photo = input.photo, startedMs = input.now, withCamera = input.video,
            peers = people, group = people.size > 1, fromGroup = input.fromGroup || people.size > 1, joiners = joinedOf(input.guid),
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
        // A group ring from someone the user does not know is answered without the camera (it can be turned on in the call).
        val next = state.copy(phase = Phase.CONNECTING, withCamera = input.withCamera && state.video && !state.ignoreOnly)
        return Step(next, listOf(Effect.HideIncoming(next.guid), Effect.StartTimer(next.guid, Phase.CONNECTING, CONNECT_MS), Effect.StartNative(next)))
    }

    /**
     * The user declined the ringing [call]. A group ring from someone they do not know is ignored
     * instead: nothing is sent (a decline goes to every address its member list names).
     */
    private fun declined(call: Call, now: Long): Step =
        if (call.ignoreOnly) {
            Step(null, ringDown(call) + listOf(Effect.LogCall(entry(call, Outcome.DECLINED, now)), Effect.Note("a Group FaceTime ring was ignored (nothing was sent)")))
        } else {
            Step(null, ringDown(call) + listOf(Effect.DeclineCall(call.guid), Effect.LogCall(entry(call, Outcome.DECLINED, now))))
        }

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
        if (call.present == 0 && (call.abandoned || call.peerSeen)) {
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
        val inCall = input.ids.take(MAX_PEOPLE).mapIndexed { i, id -> InCall(id, CallIds.reported(input.handles.getOrNull(i))) }
        val next = call.copy(present = count, peerSeen = call.peerSeen || count > 0, abandoned = call.abandoned && count == 0, inCall = inCall)
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
        group = call.group,
        members = if (!call.group) emptyList() else if (call.outgoing) call.peers else call.listed,
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

        /** The most calls whose joiners are remembered. */
        const val MAX_JOINERS = 256

        /** The most joiners remembered of a call other than the one there is (of that one, [MAX_PEOPLE]). */
        const val MAX_JOINERS_PER_CALL = 8
    }
}
