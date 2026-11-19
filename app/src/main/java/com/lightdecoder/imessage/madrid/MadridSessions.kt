package com.lightdecoder.imessage.madrid

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * The iMessage app sessions (games) an extension app has open here, with the handles it was
 * given for them ([MadridHost]'s view handles, by id). A session is engaged while one of its
 * handles is locked (a game is open on screen), or for [graceMs] after it was opened or last
 * unlocked; only then are moves taken from it, and its chat's moves handed to it. While a handle
 * asks for it, the session's conversation makes no notification sound ([suppressed]).
 *
 * Thread-safe; pure (the time is passed in). Tested in MadridSessionsTest.
 */
class MadridSessions(
    private val graceMs: Long = GRACE_MS,
    private val suppressMaxMs: Long = SUPPRESS_MAX_MS,
    private val moveIntervalMs: Long = MOVE_INTERVAL_MS,
) {
    /**
     * A handle given to an extension app: its session, the archive id of the session's first card
     * ([rootMessageId], which moves go to), its conversation and app.
     */
    data class Handle(val id: Int, val session: String, val rootMessageId: String, val conversationId: String, val pkg: String)

    private class Session(val conversationId: String, val pkg: String) {
        var engagedUntil = 0L

        /** When its last move was let go to Apple ([takeMoveSlot]); 0 for never. */
        var lastMoveAt = 0L
        val locked = HashSet<Int>()

        /** Handles asking for silence, with when they asked. */
        val suppressing = HashMap<Int, Long>()
    }

    private val sessions = HashMap<String, Session>()
    private val handles = HashMap<Int, Handle>()

    /** [handle] was given out for its session (a card was opened in the app): the session is engaged. */
    @Synchronized
    fun engage(handle: Handle, now: Long) {
        handles[handle.id] = handle
        val s = sessions.getOrPut(handle.session) { Session(handle.conversationId, handle.pkg) }
        s.engagedUntil = maxOf(s.engagedUntil, now + graceMs)
    }

    /** The handle [id] was given out for, while its session is known. */
    @Synchronized
    fun handle(id: Int): Handle? = handles[id]?.takeIf { it.session in sessions }

    /** The app locked handle [id]: a game is in progress. False for a handle not known. */
    @Synchronized
    fun lock(id: Int, now: Long): Boolean {
        val s = sessionOf(id) ?: return false
        s.locked += id
        s.engagedUntil = maxOf(s.engagedUntil, now + graceMs)
        return true
    }

    /**
     * The app unlocked handle [id]. Once no handle of its session is locked, the silence its
     * handles asked for ends, and the session stays engaged for [graceMs] more.
     */
    @Synchronized
    fun unlock(id: Int, now: Long): Boolean {
        val s = sessionOf(id) ?: return false
        s.locked -= id
        if (s.locked.isEmpty()) {
            s.suppressing.clear()
            s.engagedUntil = maxOf(s.engagedUntil, now + graceMs)
        }
        return true
    }

    /**
     * The app asks ([on]) for no notifications from handle [id]'s conversation, or no longer. Taken
     * whether or not the handle is locked: OpenPigeon asks before it locks.
     */
    @Synchronized
    fun suppress(id: Int, on: Boolean, now: Long): Boolean {
        val s = sessionOf(id) ?: return false
        if (on) s.suppressing[id] = now else s.suppressing.remove(id)
        return true
    }

    /** The extension app [pkg] died, or its binding did: none of its sessions holds a lock, silence or engagement any more. */
    @Synchronized
    fun died(pkg: String) {
        for (s in sessions.values) {
            if (s.pkg != pkg) continue
            s.locked.clear()
            s.suppressing.clear()
            s.engagedUntil = 0
        }
    }

    /** Whether [session] is engaged ([MadridSessions]). */
    @Synchronized
    fun isEngaged(session: String, now: Long): Boolean {
        val s = sessions[session] ?: return false
        return s.locked.isNotEmpty() || now < s.engagedUntil
    }

    /** Whether a move may be taken from handle [id] now: its session is engaged. */
    @Synchronized
    fun acceptsMoves(id: Int, now: Long): Boolean = handle(id)?.let { isEngaged(it.session, now) } == true

    /**
     * Whether a new move of handle [id]'s session may go to Apple now: at most one per
     * [moveIntervalMs] (another app can make the extension send through its handle; see
     * madrid-host.md §11). True takes the slot.
     */
    @Synchronized
    fun takeMoveSlot(id: Int, now: Long): Boolean {
        val s = sessionOf(id) ?: return false
        if (s.lastMoveAt != 0L && now - s.lastMoveAt < moveIntervalMs) return false
        s.lastMoveAt = now
        return true
    }

    /** Whether [conversationId] is to make no notification sound now (asked within [suppressMaxMs]). */
    @Synchronized
    fun suppressed(conversationId: String, now: Long): Boolean =
        sessions.values.any { s -> s.conversationId == conversationId && s.suppressing.values.any { now - it < suppressMaxMs } }

    /** The engaged sessions' ids. */
    @Synchronized
    fun engaged(now: Long): List<String> = sessions.keys.filter { isEngaged(it, now) }

    /** The app of [session], while it is known. */
    @Synchronized
    fun pkgOf(session: String): String? = sessions[session]?.pkg

    /** Forgets [session] and its handles, once it is no longer engaged; true when it was forgotten. */
    @Synchronized
    fun releaseIfIdle(session: String, now: Long): Boolean {
        if (isEngaged(session, now)) return false
        sessions.remove(session) ?: return false
        handles.values.removeAll { it.session == session }
        return true
    }

    /**
     * Handle [id] was never handed to the app (it could not be reached): it goes, and its session
     * with it unless another handle was given out for it since.
     */
    @Synchronized
    fun forget(id: Int) {
        val handle = handles.remove(id) ?: return
        if (handles.values.none { it.session == handle.session }) sessions.remove(handle.session)
    }

    private fun sessionOf(id: Int): Session? = handles[id]?.let { sessions[it.session] }

    companion object {
        /** How long a session stays engaged after a card was opened or its game was left. */
        const val GRACE_MS = 60_000L

        /** Silence asked for longer ago than this is not kept (the app may have stopped without saying). */
        const val SUPPRESS_MAX_MS = 2 * 60 * 60 * 1000L

        /** The least time between two moves of one session. */
        const val MOVE_INTERVAL_MS = 2_000L
    }
}

/**
 * One send per move: the same move (the same session and [url]) asked again while it is being
 * sent, or soon after it went, waits for that send instead of going twice. OpenPigeon asks again
 * once when Apple's answer takes long. A send whose result [keep] does not keep (it failed) is
 * forgotten: asked again, it goes again. One that may have gone (Apple did not confirm it in
 * time) is to be kept, so that it does not go twice blindly.
 *
 * Thread-safe. Tested in MadridSessionsTest.
 */
class MoveDedupe<T>(private val keepMs: Long = KEEP_MS, private val keep: (T) -> Boolean) {

    private class Entry<T>(val send: Deferred<T>, var doneAt: Long = 0)

    private val entries = HashMap<Pair<String, String>, Entry<T>>()

    /**
     * The send of the move ([session], [url]): one under way or done well within [keepMs], else
     * the one [start] starts.
     */
    @Synchronized
    fun join(session: String, url: String, now: Long, start: () -> Deferred<T>): Deferred<T> {
        for (e in entries.values) if (e.send.isCompleted && e.doneAt == 0L) e.doneAt = now
        entries.values.removeAll { e -> e.send.isCompleted && (!e.succeeded() || now - e.doneAt >= keepMs) }
        val key = session to url
        entries[key]?.let { return it.send }
        val send = start()
        entries[key] = Entry(send)
        return send
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun Entry<T>.succeeded(): Boolean = !send.isCancelled && runCatching { keep(send.getCompleted()) }.getOrDefault(false)

    companion object {
        /** A move that went is not sent again for this long. */
        const val KEEP_MS = 60_000L
    }
}
