package com.lightdecoder.imessage

import android.content.Context
import org.json.JSONObject

/**
 * Which iMessage chat belongs inside which Messages conversation (Blue
 * Bubbles: a person's iMessages show in their Messages conversation, as on
 * an iPhone). In this order:
 *
 * 1. A link the user made or broke by hand (thread menu), kept here.
 * 2. The same phone number (the whole international number: [IMessageAddress.key]; by its
 *    national digits only where one side has no country code and none is known to read it in,
 *    [IMessageAddress.sameKey]), or the same email address.
 *
 * A name is never enough by itself: anyone can write from an Apple ID email address under any
 * name (iMessage's shared name and photo), and a chat linked by name alone would show a stranger's
 * messages as a contact's and send the contact's replies to the stranger. A chat with no number
 * of its own, or with an email address, that has the same name as a conversation is only offered
 * first when the user links one by hand ([sameName]): the user's tap is the confirmation.
 *
 * Each conversation and each chat is in at most one pair.
 */
object IMessageLinks {

    /** One side of a pair: its id, its phone number or email key ([IMessageAddress.key]), and its name. */
    data class Chat(val id: String, val addressKey: String?, val name: String)

    enum class How {
        BY_HAND, NUMBER,

        /** Another iMessage chat of the same person (same number or email): its history shows too; replies go to the first. */
        SAME_PERSON,
    }

    data class Pair(val messagesId: String, val how: How)

    /**
     * The pairs, by iMessage chat id. [manual] maps an iMessage chat id to
     * the Messages conversation the user linked it to, or to "" when the
     * user unlinked it (then it is never linked by number or name).
     */
    fun pair(messages: List<Chat>, iMessages: List<Chat>, manual: Map<String, String>): Map<String, Pair> {
        val out = HashMap<String, Pair>()
        val usedMessages = HashSet<String>()
        val messageIds = messages.mapTo(HashSet()) { it.id }
        // 1. By hand.
        for (im in iMessages) {
            val to = manual[im.id] ?: continue
            if (to.isEmpty() || to !in messageIds || to in usedMessages) continue
            out[im.id] = Pair(to, How.BY_HAND)
            usedMessages += to
        }
        fun free(im: Chat) = im.id !in out && manual[im.id] != ""
        // 2. By number (or email address).
        val byKey = HashMap<String, String>()
        for (m in messages) m.addressKey?.let { byKey.putIfAbsent(it, m.id) }
        for (im in iMessages) {
            if (!free(im)) continue
            val to = im.addressKey?.let { byKey.lookup(it) } ?: continue
            if (to in usedMessages) continue
            out[im.id] = Pair(to, How.NUMBER)
            usedMessages += to
        }
        // 3. The same person's other chats (OpenBubbles registered again, or a chat per address):
        //    shown in the same conversation, so no history goes missing.
        val byPerson = HashMap<String, String>()
        for (im in iMessages) {
            val key = im.addressKey ?: continue
            out[im.id]?.takeIf { it.how != How.SAME_PERSON }?.let { byPerson.putIfAbsent(key, it.messagesId) }
        }
        for (im in iMessages) {
            if (!free(im)) continue
            val to = im.addressKey?.let { byPerson.lookup(it) } ?: continue
            out[im.id] = Pair(to, How.SAME_PERSON)
        }
        return out
    }

    /**
     * Of [iMessages] (chats not in any pair), the ones that may be the person of the Messages
     * conversation named [name]: the same name, and no phone number of its own (one that has a
     * number would have been linked by it). Never linked by this; the "link by hand" list shows
     * them first, for the user to confirm.
     */
    fun sameName(name: String, iMessages: List<Chat>): List<Chat> {
        val wanted = nameKey(name) ?: return emptyList()
        return iMessages.filter { nameKey(it.name) == wanted && (it.addressKey == null || it.addressKey.contains('@')) }
    }

    /** The value under [key] ([IMessageAddress.key]), else under a key that is the same address written otherwise ([IMessageAddress.sameKey]). */
    private fun <V> Map<String, V>.lookup(key: String): V? =
        get(key) ?: entries.firstOrNull { IMessageAddress.sameKey(it.key, key) }?.value

    /** A name to compare, or null when there is none worth comparing (empty, or only a number or an address). */
    fun nameKey(name: String): String? {
        val n = name.trim().lowercase().replace(Regex("\\s+"), " ")
        if (n.isEmpty() || IMessageAddress.key(n) != null) return null
        return n
    }

    // --- links made by hand -----------------------------------------------------------------

    private const val FILE = "imessage_links"
    private const val KEY = "manual"

    fun manual(context: Context): Map<String, String> {
        val json = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyMap()
        return try {
            val o = JSONObject(json)
            o.keys().asSequence().associateWith { o.optString(it) }
        } catch (e: org.json.JSONException) {
            emptyMap()
        }
    }

    /** Links iMessage chat [iMessageId] to Messages conversation [messagesId], or with null unlinks it for good. */
    fun setManual(context: Context, iMessageId: String, messagesId: String?) {
        val now = manual(context).toMutableMap()
        // A conversation has one iMessage chat: one linked to it before lets go.
        if (messagesId != null) now.entries.removeAll { it.value == messagesId }
        now[iMessageId] = messagesId.orEmpty()
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY, JSONObject(now as Map<*, *>).toString()).apply()
        invalidate()
        IMessageLog.add(
            context,
            "${IMessageLog.chatRef(iMessageId)}: ${if (messagesId == null) "unlinked by hand from its Messages conversation" else "linked by hand to a Messages conversation"}",
        )
    }

    // --- a short cache, dropped on any change to either archive -----------------------------

    @Volatile
    private var cached: Map<String, Pair>? = null

    @Volatile
    private var cachedAt = 0L

    fun invalidate() {
        cached = null
    }

    internal fun cachedOr(nowMs: Long, compute: () -> Map<String, Pair>): Map<String, Pair> {
        cached?.takeIf { nowMs - cachedAt < CACHE_MS }?.let { return it }
        return compute().also {
            cached = it
            cachedAt = nowMs
        }
    }

    private const val CACHE_MS = 30_000L
}
