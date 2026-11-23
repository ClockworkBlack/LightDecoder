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

    /** A device joined the call: [handle] is whose ("" when it does not say); [ring]: it is an invitation to the user. */
    data class Join(override val guid: String, val handle: String, val ring: Boolean) : CallEvent()

    /** The user is rung without anyone joining: an explicit invitation, or the user was added to a call. */
    data class Invite(override val guid: String) : CallEvent()

    /** One invitee said no: [handle] is who ("" when it does not say). */
    data class Decline(override val guid: String, val handle: String) : CallEvent()

    /** A device left the call. */
    data class Leave(override val guid: String, val handle: String) : CallEvent()

    /** Another of the user's devices answered. */
    data class Elsewhere(override val guid: String) : CallEvent()

    companion object {
        /** [e] for the rules, or null: one they have no use for, or whose call id is no call's. */
        fun of(e: FaceTimeEvent): CallEvent? = when (e) {
            is FaceTimeEvent.JoinEvent -> guid(e.guid)?.let { Join(it, handle(e.handle), e.ring) }
            is FaceTimeEvent.Ring -> guid(e.guid)?.let(::Invite)
            is FaceTimeEvent.AddMembers -> if (e.ring) guid(e.guid)?.let(::Invite) else null
            is FaceTimeEvent.Decline -> guid(e.guid)?.let { Decline(it, handle(e.handle)) }
            is FaceTimeEvent.LeaveEvent -> guid(e.guid)?.let { Leave(it, handle(e.handle)) }
            is FaceTimeEvent.RespondedElsewhere -> guid(e.guid)?.let(::Elsewhere)
            // Not raised for anything a call's flow may depend on (Connected, Disconnected), or not a
            // one-to-one call's (links, members taken out).
            is FaceTimeEvent.Connected, is FaceTimeEvent.Disconnected, is FaceTimeEvent.RemoveMembers,
            is FaceTimeEvent.LinkChanged, is FaceTimeEvent.LetMeIn,
            -> null
        }

        private fun guid(raw: String): String? = raw.takeIf(CallIds::isGuid)

        /** An address of an event: as the library could call it, else "" (it says nothing then). */
        private fun handle(raw: String): String = CallIds.handle(raw).orEmpty()

        /** What kind of event [e] is, for the log: its type's name, never what it holds. */
        fun kindOf(e: FaceTimeEvent): String = e::class.simpleName.orEmpty().ifEmpty { "event" }

        /** The library's call list as the rules read it: only calls whose id is a call's, at most [MAX_SESSIONS] of them. */
        fun sessions(list: List<FtSessionInfo>): List<CallSessionInfo> = list.asSequence()
            .filter { CallIds.isGuid(it.groupId) }
            .take(MAX_SESSIONS)
            .map { s ->
                CallSessionInfo(
                    guid = s.groupId,
                    myHandles = s.myHandles.take(MAX_MEMBERS).mapNotNull(CallIds::handle),
                    members = s.members.take(MAX_MEMBERS).map { it.handle },
                    startMs = s.startTime?.let { if (it > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else it.toLong() },
                    video = s.isVideo,
                    active = s.isActive,
                )
            }.toList()

        /** The most calls of the library's list that are looked at, and members of each. */
        const val MAX_SESSIONS = 500
        const val MAX_MEMBERS = 64
    }
}
