package com.lightdecoder.imessage.facetime

import uniffi.rust_bridge.FaceTimeEvent
import uniffi.rust_bridge.FtSessionInfo

/**
 * What the library says of FaceTime calls ([FaceTimeEvent]), as [CallRules] takes it. Every one of
 * these comes from someone else's phone (anyone who knows the user's number can send one), so
 * nothing is taken as it is: a call's guid must be a UUID as iPhones write them, an address one the
 * library could call, and what a one-to-one call has no use for is left out ([of] gives null).
 */
sealed class CallEvent {
    abstract val guid: String

    /**
     * A device joined the call: [handle] is whose ("" when it does not say), as Apple vouches for it
     * (the sender of the join); [ring]: it is an invitation to the user.
     */
    data class Join(override val guid: String, val handle: String, val ring: Boolean) : CallEvent() {
        /**
         * The id the device joined with (the joiner chose it: it only ever labels the joiner's own
         * address). Carried along, not part of what makes two joins the same event.
         */
        var participant: Long = 0L
            private set

        constructor(guid: String, handle: String, ring: Boolean, participant: Long) : this(guid, handle, ring) {
            this.participant = participant
        }
    }

    /** The user is rung without anyone joining: an explicit invitation, or the user was added to a call. */
    data class Invite(override val guid: String) : CallEvent()

    /** One invitee said no: [handle] is who ("" when it does not say). */
    data class Decline(override val guid: String, val handle: String) : CallEvent()

    /** A device left the call. */
    data class Leave(override val guid: String, val handle: String) : CallEvent()

    /** Another of the user's devices answered. */
    data class Elsewhere(override val guid: String) : CallEvent()

    /** People were taken out of the call ([handles]: their addresses, as the event names them; never empty). */
    data class Removed(override val guid: String, val handles: List<String>) : CallEvent()

    companion object {
        /** [e] for the rules, or null: one they have no use for, or whose call id is no call's. */
        fun of(e: FaceTimeEvent): CallEvent? = when (e) {
            is FaceTimeEvent.JoinEvent -> guid(e.guid)?.let { Join(it, handle(e.handle), e.ring, participant(e.participant)) }
            is FaceTimeEvent.Ring -> guid(e.guid)?.let(::Invite)
            is FaceTimeEvent.AddMembers -> if (e.ring) guid(e.guid)?.let(::Invite) else null
            is FaceTimeEvent.Decline -> guid(e.guid)?.let { Decline(it, handle(e.handle)) }
            is FaceTimeEvent.LeaveEvent -> guid(e.guid)?.let { Leave(it, handle(e.handle)) }
            is FaceTimeEvent.RespondedElsewhere -> guid(e.guid)?.let(::Elsewhere)
            // Only ever used to stop the user's own ring: nothing when it names no address.
            is FaceTimeEvent.RemoveMembers -> guid(e.guid)?.let { g ->
                e.members.take(MAX_MEMBERS).mapNotNull { CallIds.handle(it.handle) }.takeIf { it.isNotEmpty() }?.let { Removed(g, it) }
            }
            // Not raised for anything a call's flow may depend on (Connected, Disconnected), or
            // FaceTime links (none is ever made here).
            is FaceTimeEvent.Connected, is FaceTimeEvent.Disconnected,
            is FaceTimeEvent.LinkChanged, is FaceTimeEvent.LetMeIn,
            -> null
        }

        /** A participant id as the rules keep it ([Long]; the library's are unsigned 64-bit). */
        private fun participant(raw: ULong): Long = raw.toLong()

        private fun guid(raw: String): String? = raw.takeIf(CallIds::isGuid)

        /** An address of an event: as the library could call it, else "" (it says nothing then). */
        private fun handle(raw: String): String = CallIds.handle(raw).orEmpty()

        /** What kind of event [e] is, for the log: its type's name, never what it holds. */
        fun kindOf(e: FaceTimeEvent): String = e::class.simpleName.orEmpty().ifEmpty { "event" }

        /**
         * Call [guid] of the library's call list, as the rules read it, or null: not in it. The
         * whole list is looked through, however long (anyone's message puts a call into it, so a
         * cut-off list would let a stranger push a real call out of sight); only what is taken of
         * the one call found is limited ([MAX_MEMBERS]).
         */
        fun session(list: List<FtSessionInfo>, guid: String): CallSessionInfo? {
            if (!CallIds.isGuid(guid)) return null
            return list.firstOrNull { it.groupId == guid }?.let(::read)
        }

        private fun read(s: FtSessionInfo) = CallSessionInfo(
            guid = s.groupId,
            myHandles = s.myHandles.take(MAX_MEMBERS).mapNotNull(CallIds::handle),
            members = s.members.take(MAX_MEMBERS).map { it.handle },
            startMs = s.startTime?.let { if (it > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else it.toLong() },
            video = s.isVideo,
            active = s.isActive,
        )

        /** The most members of a call that are looked at. */
        const val MAX_MEMBERS = 64
    }
}
