package com.lightdecoder.imessage.direct

import android.content.Context

/**
 * When the user's devices change (a new one signed in), the people of the user's recent chats are
 * told, so that their phones look the user's devices up again and their next messages reach the new
 * one too: OpenBubbles' `invalidatePeerCaches`. Whom, pure: tested in PeerInvalidationTest.
 */
object PeerInvalidation {

    /** A chat counts as recent with something in it this long ago at most (OpenBubbles' 12 hours). */
    const val RECENT_MS = 12 * 60 * 60 * 1000L

    /** The people of the recent chats are told at most this often. */
    const val EVERY_MS = 10 * 60 * 1000L

    /** Whether a chat whose latest message is at [lastMessageTs] is recent at [now]. */
    fun recent(lastMessageTs: Long, now: Long): Boolean = lastMessageTs > 0 && lastMessageTs > now - RECENT_MS

    /**
     * Who is told, from the recent chats' [targets]: for each of the user's addresses the chats are
     * written from ([DirectWire.Target.from]), that address and the people of all of them, each once
     * (as OpenBubbles groups them: one notice per address of the user's). An address with nobody but
     * the user is left out; at most [DirectWire.MAX_PEER_GROUPS] addresses and [DirectWire.MAX_PEERS]
     * people each.
     */
    fun groups(targets: List<DirectWire.Target>): List<DirectWire.PeerGroup> {
        val byFrom = LinkedHashMap<String, LinkedHashSet<String>>()
        for (t in targets) {
            val from = t.from.takeIf { it.isNotBlank() } ?: continue
            val people = byFrom.getOrPut(from) { linkedSetOf(from) }
            for (p in t.participants) if (p.isNotBlank() && people.size < DirectWire.MAX_PEERS) people += p
        }
        return byFrom.entries.filter { (from, people) -> people.any { it != from } }
            .take(DirectWire.MAX_PEER_GROUPS)
            .map { (from, people) -> DirectWire.PeerGroup(from, people.toList()) }
    }
}

/** When the people of the recent chats were last told the user's devices changed ([PeerInvalidation]); main process only. */
internal class PeerNotices(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Whether they may be told again at [now]: never yet, or [PeerInvalidation.EVERY_MS] since (a clock that went back counts as since). */
    fun due(now: Long): Boolean {
        val at = prefs.getLong(KEY_AT, 0L)
        return at <= 0 || now - at >= PeerInvalidation.EVERY_MS || at > now
    }

    fun told(now: Long) {
        prefs.edit().putLong(KEY_AT, now).apply()
    }

    private companion object {
        const val FILE = "imessage_peer_notices"
        const val KEY_AT = "told_at"
    }
}
