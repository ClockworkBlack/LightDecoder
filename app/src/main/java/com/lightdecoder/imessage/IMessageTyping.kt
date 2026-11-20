package com.lightdecoder.imessage

/** Someone typing in a chat: their address as the archive keeps it ([participantId]) and their [name]. */
data class Typist(val participantId: String, val name: String)

/**
 * Who is typing where, as the iMessage library hears it: conversation id -> participant -> name.
 * An iPhone says when someone starts and when they stop; someone it never says stopped for (their
 * phone went out of reach) is forgotten after [expiryMs], as an iPhone's own indicator goes. Their
 * next message ends it too (the caller notes that as a stop). Times are passed in.
 */
internal class TypingBoard(private val expiryMs: Long = EXPIRY_MS) {

    /** Conversation id -> participant id -> (name, when they were last said to be typing). */
    private val typers = HashMap<String, LinkedHashMap<String, Pair<String, Long>>>()

    /** [participantId] ([name]) started or stopped typing in [conversationId] at [nowMs]. True when who is [shown] changed. */
    @Synchronized
    fun note(conversationId: String, participantId: String, name: String, typing: Boolean, nowMs: Long): Boolean {
        val before = shown()
        if (typing) {
            typers.getOrPut(conversationId) { LinkedHashMap() }[participantId] = name.ifBlank { participantId } to nowMs
        } else {
            typers[conversationId]?.let { here ->
                here.remove(participantId)
                if (here.isEmpty()) typers.remove(conversationId)
            }
        }
        forgetOld(nowMs)
        return shown() != before
    }

    /** Forgets whoever was said to be typing [expiryMs] or more before [nowMs]. True when who is [shown] changed. */
    @Synchronized
    fun expire(nowMs: Long): Boolean {
        val before = shown()
        forgetOld(nowMs)
        return shown() != before
    }

    /** Conversation id -> the names of who is typing in it. */
    @Synchronized
    fun shown(): Map<String, Set<String>> = typers.mapValues { (_, people) -> people.values.mapTo(LinkedHashSet()) { it.first } }

    /** Conversation id -> who is typing in it, in the order they started. */
    @Synchronized
    fun typists(): Map<String, List<Typist>> = typers.mapValues { (_, people) -> people.map { (id, v) -> Typist(id, v.first) } }

    private fun forgetOld(nowMs: Long) {
        val chats = typers.values.iterator()
        while (chats.hasNext()) {
            val people = chats.next()
            people.values.removeAll { (_, since) -> nowMs - since >= expiryMs }
            if (people.isEmpty()) chats.remove()
        }
    }

    companion object {
        /** How long someone shows as typing when nothing more is heard from them. */
        const val EXPIRY_MS = 60_000L
    }
}
