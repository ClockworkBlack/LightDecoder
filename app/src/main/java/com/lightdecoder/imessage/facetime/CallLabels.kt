package com.lightdecoder.imessage.facetime

/**
 * What the call screen calls each of the people in a joined call, by whom Apple vouches for. The
 * library reports an address for each of them ([InCall.reported]), but any member of a group call can
 * relabel anyone; only the sender of a join is vouched for ([Call.joiners], by the participant id it
 * joined with). So:
 *
 * - someone who joined as Apple vouches is shown by that address, and only theirs is looked up
 *   (the main process's contact name and picture, [lookups]: once per address per call);
 * - anyone else is shown by the address the library reports, marked as not verified, with no
 *   picture and nothing looked up;
 * - a FaceTime link's guest ("temp:...") is a guest.
 *
 * Pure. Tested in FaceTimeGroupTest.
 */
object CallLabels {

    enum class Kind {
        /** Joined as Apple vouches for: [Label.handle] is theirs. */
        VERIFIED,

        /** Only the library's report says who: shown as it was reported. */
        NOT_VERIFIED,

        /** A FaceTime link's guest. */
        GUEST,
    }

    /**
     * One person of the call: the library's [id] for them, how sure the label is, the address it is
     * by ([handle]: for [Kind.VERIFIED] the one Apple vouches for; else the reported one where it is
     * an address, or null), and [reported] as cleaned to be shown ([CallIds.reported]).
     */
    data class Label(val id: Long, val kind: Kind, val handle: String?, val reported: String)

    /** The labels of [inCall], in its order, by who joined as Apple vouches ([joiners]). */
    fun of(inCall: List<InCall>, joiners: Map<Long, String>): List<Label> = inCall.take(MAX_PEOPLE).map { p ->
        val vouched = joiners[p.id]?.let(CallIds::handle)
        when {
            vouched != null -> Label(p.id, Kind.VERIFIED, vouched, p.reported)
            CallIds.isGuest(p.reported) -> Label(p.id, Kind.GUEST, null, p.reported)
            else -> Label(p.id, Kind.NOT_VERIFIED, CallIds.handle(p.reported), p.reported)
        }
    }

    /**
     * The addresses still to be looked up for [labels] (who each one is to the user), given those
     * looked up already in this call ([asked], by [CallIds.key]): only the ones Apple vouches for,
     * each once. Nothing else is ever looked up.
     */
    fun lookups(labels: List<Label>, asked: Set<String>): List<String> =
        labels.filter { it.kind == Kind.VERIFIED }.mapNotNull { it.handle }
            .filter { CallIds.key(it) !in asked }.distinctBy(CallIds::key)
}
