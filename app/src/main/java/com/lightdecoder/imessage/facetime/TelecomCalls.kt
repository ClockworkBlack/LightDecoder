package com.lightdecoder.imessage.facetime

/**
 * FaceTime calls handed to Android's Telecom (SPEC2 B3.3), so that a Bluetooth headset's or a car's
 * buttons answer and end them, a car or a watch shows them, and a cellular call that comes during
 * one asks it to make way. LightDecoder's own self-managed calling account in the ":imessage"
 * process ([FaceTimeConnectionService]); this is the mapping between the call engine and Telecom's
 * call ([Line]), with nothing of Android in it (tested in TelecomCallsTest).
 *
 * - A call is handed over only after LightDecoder's own screening: when it rings here
 *   ([CallRules.Effect.ShowIncoming]) or is placed by the user ([CallRules.Effect.CreateCall]).
 *   Never for a silenced, limited, blocked or missed-only ring: those never ring.
 * - Telecom is told only the caller as Apple vouches for them, by the name the user knows them by,
 *   else their address; a group call rung here as "caller · Group FaceTime", one placed here as
 *   "Group FaceTime" with no address, never its chat's name or its members; an unknown
 *   caller while the phone is locked not at all (as the lock screen's notice hides who calls).
 * - What Telecom asks maps exactly to what the user's own buttons do ([Actions]): answer opens the
 *   call screen's answer (with the camera off, whatever Telecom says), reject declines, disconnect
 *   hangs up, hold ends the call (there is no holding a FaceTime call). Nothing is ever answered or
 *   placed by this on its own.
 * - The call engine's changes go to Telecom: active once the library joined, disconnected (and
 *   destroyed) on every end.
 * - While Telecom has the call, it owns the phone's audio ([ownsAudio]): [FaceTimeAudioRoute] sets
 *   no mode, device or focus. When Telecom is off ([enabled]), refuses, throws, or does not come back
 *   with the call in [WAIT_MS], the call goes on exactly as without Telecom ([fallback]).
 */
class TelecomCalls(
    private val platform: Platform,
    private val actions: Actions,
    /** "Use with Bluetooth and car" is on, and Telecom is there to be used. */
    private val enabled: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    /** Runs [block] after [ms] (the wait for Telecom's call); tests run it when they choose. */
    private val after: (ms: Long, block: () -> Unit) -> Unit = { _, _ -> },
    /** Whether the phone is locked now (an unknown caller is then not named to Telecom). */
    private val locked: () -> Boolean = { false },
    /** What a group call rung here is called: "caller · Group FaceTime" (the call screen's own words). */
    private val groupTitle: (String) -> String = { "$it · Group FaceTime" },
    /** What a group call the user places is called: "Group FaceTime", never the chat's name or its members. */
    private val groupLabel: () -> String = { "Group FaceTime" },
) {

    /** What is asked of Telecom. */
    interface Platform {
        /** Telecom is to ring [ask] (`addNewIncomingCall`). False, or a throw, when it would not. */
        fun addIncoming(ask: Ask): Boolean

        /** Telecom is to place [ask] (`placeCall`). False, or a throw, when it would not. */
        fun placeOutgoing(ask: Ask): Boolean
    }

    /** Telecom's call of one FaceTime call ([FaceTimeConnection]), as this tells it. Every one of these may throw. */
    interface Line {
        /** The call is joined (Telecom's `setActive`). */
        fun markActive()

        /** The call is over (Telecom's `setDisconnected`, then `destroy`). */
        fun end(cause: EndCause)

        /** Sends the call's sound to [route] through Telecom. False when it could not. */
        fun moveTo(route: Route): Boolean
    }

    /** The user's own buttons, as the call engine takes them. */
    interface Actions {
        /** Opens the call screen's answer of [guid], camera off (as the notice's "Audio only"); false when the screen could not be opened. */
        fun answer(guid: String): Boolean
        fun decline(guid: String)
        fun hangUp(guid: String)

        /** The ringing notice, again (the call screen could not be opened for a headset's answer). */
        fun showIncoming(guid: String) {}
    }

    /** What is handed to Telecom of a call. [address] null: not named (an unknown caller on a locked phone). */
    data class Ask(val guid: String, val incoming: Boolean, val address: String?, val name: String?, val video: Boolean)

    /** Why Telecom's call ended, as Telecom's DisconnectCause says it. */
    enum class EndCause { LOCAL, REMOTE, MISSED, REJECTED, CANCELED, ANSWERED_ELSEWHERE, ERROR }

    private enum class Stage { PENDING, ATTACHED, FALLBACK }

    private class Entry(val guid: String, val incoming: Boolean, var stage: Stage, val since: Long, var line: Line? = null)

    private val lock = Any()

    /** The calls handed over (one at a time; a few kept so late answers from Telecom find theirs). */
    private val entries = LinkedHashMap<String, Entry>()

    /** The routes Telecom says there are for the call it has, and the one in use (its last word). */
    @Volatile
    var routes: List<Route> = emptyList()
        private set

    @Volatile
    var route: Route? = null
        private set

    /** Told when Telecom's routes change (the call engine shows them). */
    @Volatile
    var routesChanged: () -> Unit = {}

    // --- the call engine -----------------------------------------------------------------------------

    /** What the call engine is about to do: a ring or a placed call is handed to Telecom; a finished call's end is told. */
    fun onEffect(effect: CallRules.Effect) {
        when (effect) {
            is CallRules.Effect.ShowIncoming -> handOver(effect.call, incoming = true)
            is CallRules.Effect.CreateCall -> handOver(effect.call, incoming = false)
            is CallRules.Effect.LogCall -> end(effect.entry.guid, causeOf(effect.entry))
            else -> Unit
        }
    }

    /** The call engine's call changed from [before] to [after]: active once joined, disconnected once it is no longer the call. */
    fun onState(before: Call?, after: Call?) {
        if (after != null && after.phase == Phase.ACTIVE && (before?.guid != after.guid || before.phase != Phase.ACTIVE)) {
            lineOf(after.guid)?.let { line -> safely { line.markActive() } }
        }
        if (before != null && (after == null || after.guid != before.guid || !after.live)) {
            end(before.guid, EndCause.LOCAL)
        }
    }

    private fun handOver(call: Call, incoming: Boolean) {
        if (!runCatching(enabled).getOrDefault(false)) return
        val ask = ask(call, incoming)
        val entry = Entry(call.guid, incoming, Stage.PENDING, now())
        synchronized(lock) {
            entries.remove(call.guid)
            entries[call.guid] = entry
            while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
        }
        val ok = try {
            if (incoming) platform.addIncoming(ask) else platform.placeOutgoing(ask)
        } catch (e: Throwable) {
            false
        }
        if (!ok) {
            fallback(call.guid, "Telecom would not take a FaceTime call: it goes on without it")
            return
        }
        after(WAIT_MS) {
            val late = synchronized(lock) { entries[call.guid]?.takeIf { it === entry && it.stage == Stage.PENDING } }
            if (late != null) fallback(call.guid, "Telecom did not take a FaceTime call in time: it goes on without it")
        }
    }

    /** What Telecom is told of [call] (see the class's notes). */
    internal fun ask(call: Call, incoming: Boolean): Ask {
        // A group call placed here: its chat's name may be one any member wrote, or its members' names, and its
        // peer is only one member of several. Telecom is told neither (RF R2.6): a fixed label and no address.
        // The same for one placed from a group chat that reaches only one member: its name is still the chat's.
        if (!incoming && (call.group || call.fromGroup)) {
            return Ask(guid = call.guid, incoming = false, address = null, name = runCatching(groupLabel).getOrDefault("Group FaceTime"), video = call.video)
        }
        val hidden = incoming && !call.known && runCatching(locked).getOrDefault(true)
        val name = call.name.takeIf { it.isNotBlank() } ?: com.lightdecoder.imessage.direct.Handles.display(call.peer)
        val shown = if (incoming && call.group) runCatching { groupTitle(name) }.getOrDefault(name) else name
        return Ask(
            guid = call.guid,
            incoming = incoming,
            address = if (hidden) null else CallIds.handle(call.peer),
            name = if (hidden) null else CallIds.name(shown),
            video = call.video,
        )
    }

    /** The call [guid] goes on without Telecom: anything Telecom brings for it later is refused. */
    fun fallback(guid: String, why: String? = null) {
        val line = synchronized(lock) {
            val e = entries[guid] ?: return
            if (e.stage == Stage.FALLBACK) return
            e.stage = Stage.FALLBACK
            e.line.also { e.line = null }
        }
        line?.let { safely { it.end(EndCause.ERROR) } }
        why?.let(FaceTimeLog::note)
    }

    private fun end(guid: String, cause: EndCause) {
        val e = synchronized(lock) { entries.remove(guid) } ?: return
        e.line?.let { line -> safely { line.end(cause) } }
        if (e.line != null) {
            routes = emptyList()
            route = null
        }
    }

    // --- Telecom ------------------------------------------------------------------------------------

    /**
     * Telecom made its call for [guid] ([line]): taken only for the call handed over and waiting for
     * it, the same way (incoming or not); anything else (a call that ended, went on without Telecom,
     * or was never handed over) is refused, and Telecom's call is to fail.
     */
    fun attach(guid: String?, incoming: Boolean, line: Line): Boolean = synchronized(lock) {
        val e = guid?.let { entries[it] } ?: return false
        if (e.stage != Stage.PENDING || e.incoming != incoming) return false
        e.stage = Stage.ATTACHED
        e.line = line
        true
    }

    /** Telecom could not make its call for [guid]: it goes on without Telecom. */
    fun failed(guid: String?) {
        guid?.let { fallback(it, "Telecom refused a FaceTime call: it goes on without it") }
    }

    /** Whether Telecom has call [guid] now (and with it the phone's audio). */
    fun ownsAudio(guid: String?): Boolean = guid != null && synchronized(lock) { entries[guid]?.stage == Stage.ATTACHED }

    /**
     * The call engine takes the phone's audio for call [guid]: Telecom has it, or from now on never
     * does (one still on its way is refused when it comes), so that there is one owner of the audio.
     */
    fun audioEntered(guid: String?): Boolean {
        if (guid == null) return false
        if (ownsAudio(guid)) return true
        val pending = synchronized(lock) { entries[guid]?.stage == Stage.PENDING }
        if (pending) fallback(guid, "Telecom's call came too late for the audio: the call goes on without it")
        return false
    }

    /** Sends call [guid]'s sound to [route] through Telecom. False when Telecom does not have it, or could not. */
    fun route(guid: String?, route: Route): Boolean {
        val line = lineOf(guid) ?: return false
        // Already there (Telecom said so): not asked again, so a change Telecom tells of is not asked back of it.
        if (this.route == route) return true
        return try {
            line.moveTo(route)
        } catch (e: Throwable) {
            false
        }
    }

    /** Telecom's word on the routes of the call it has ([available], the one in use [current]). */
    fun audioChanged(guid: String?, available: List<Route>, current: Route?) {
        if (!ownsAudio(guid)) return
        routes = available
        route = current
        runCatching { routesChanged() }
    }

    /** A headset's, car's or watch's Answer (whatever video it asks for: the call starts with the camera off). */
    fun answered(guid: String?) {
        val g = attachedGuid(guid) ?: return
        val opened = try {
            actions.answer(g)
        } catch (e: Throwable) {
            false
        }
        // The call screen could not be opened from the background: the ringing notice is there to answer from.
        if (!opened) runCatching { actions.showIncoming(g) }
    }

    /** A headset's or car's Decline. */
    fun rejected(guid: String?) {
        attachedGuid(guid)?.let { g -> runCatching { actions.decline(g) } }
    }

    /** A headset's or car's Hang up (also of a call ringing here: that declines it, as the call screen's End does). */
    fun disconnected(guid: String?) {
        attachedGuid(guid)?.let { g -> runCatching { actions.hangUp(g) } }
    }

    /** Telecom asks the call to hold (a cellular call was answered over it): a FaceTime call cannot be held, so it ends. */
    fun held(guid: String?) {
        attachedGuid(guid)?.let { g ->
            FaceTimeLog.note("${FaceTimeLog.ref(g)}: Telecom asked to hold the FaceTime call, which cannot be held: it ends")
            runCatching { actions.hangUp(g) }
        }
    }

    private fun attachedGuid(guid: String?): String? = guid?.takeIf { ownsAudio(it) }

    private fun lineOf(guid: String?): Line? = guid?.let { g -> synchronized(lock) { entries[g]?.takeIf { it.stage == Stage.ATTACHED }?.line } }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            FaceTimeLog.note("Telecom's call could not be told (${e::class.java.simpleName})")
        }
    }

    companion object {
        /** Telecom is given this long to come back with its call (OpenBubbles 2.0.0's TELECOM_WAIT_MS). */
        const val WAIT_MS = 10_000L

        private const val MAX_ENTRIES = 4

        /** How a finished call's [entry] ended, as Telecom says it. */
        fun causeOf(entry: CallLogEntry): EndCause = when (entry.outcome) {
            Outcome.ANSWERED -> EndCause.LOCAL
            Outcome.MISSED -> EndCause.MISSED
            Outcome.DECLINED -> if (entry.outgoing) EndCause.REMOTE else EndCause.REJECTED
            Outcome.UNANSWERED -> EndCause.REMOTE
            Outcome.CANCELLED -> EndCause.CANCELED
            Outcome.ELSEWHERE -> EndCause.ANSWERED_ELSEWHERE
            Outcome.FAILED -> EndCause.ERROR
        }
    }
}

/**
 * The phone's audio for a call, as the call engine uses it ([CallAudio]), with Telecom: while
 * Telecom has the call ([TelecomCalls.ownsAudio]) the routes are Telecom's and a pick goes through
 * it, and [fallback] ([FaceTimeAudioRoute]) sets nothing (no mode, no device, no focus); without
 * Telecom it is [fallback] alone, as before. [guid] is the call there is.
 */
class TelecomAudio(
    private val fallback: CallAudio,
    private val telecom: TelecomCalls,
    private val guid: () -> String?,
) : CallAudio {

    /** The call whose audio Telecom has, from [enter] to [leave]; null: [fallback]'s (or none). */
    @Volatile
    private var viaTelecom: String? = null

    @Volatile
    private var viaFallback = false

    override fun routes(): List<Route> {
        val g = viaTelecom
        if (g != null && telecom.ownsAudio(g)) telecom.routes.takeIf { it.isNotEmpty() }?.let { return it }
        return fallback.routes()
    }

    @Synchronized
    override fun enter(changed: () -> Unit) {
        val g = guid()
        if (telecom.audioEntered(g)) {
            viaTelecom = g
            telecom.routesChanged = changed
            return
        }
        viaFallback = true
        fallback.enter(changed)
    }

    override fun current(): Route? {
        val g = viaTelecom ?: return fallback.current()
        return if (telecom.ownsAudio(g)) telecom.route else null
    }

    override fun route(route: Route): Boolean {
        val g = viaTelecom
        if (g != null) return telecom.route(g, route)
        return fallback.route(route)
    }

    @Synchronized
    override fun leave() {
        if (viaTelecom != null) {
            viaTelecom = null
            telecom.routesChanged = {}
        }
        if (viaFallback) {
            viaFallback = false
            fallback.leave()
        }
    }
}
