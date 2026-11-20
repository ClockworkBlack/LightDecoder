package com.lightdecoder.imessage.direct

import com.lightdecoder.imessage.IMessageAddress
import org.json.JSONArray
import org.json.JSONObject
import uniffi.rust_bridge.IndexedMessagePart
import uniffi.rust_bridge.MessagePart

/**
 * A mention in a message part's text, as an iPhone makes one in a group ("@" and a member's name):
 * [start] (inclusive) to [end] (exclusive), in UTF-16 characters, naming the member at [handle]
 * (the address as it came, or as it is sent: OpenBubbles' bare form, "+15551234567" or
 * "someone@example.com"). [mine] marks a mention of the user; only the ":imessage" process sets it,
 * as it alone knows all the user's addresses ([Mentions.fromParts]).
 */
data class Mention(val start: Int, val end: Int, val handle: String, val mine: Boolean = false) {

    fun toJson(): JSONObject = JSONObject().put("s", start).put("e", end).put("h", handle).apply { if (mine) put("me", true) }

    companion object {
        /** Reads what [toJson] wrote; null for anything that is no mention (no address, nothing covered). */
        fun fromJson(o: JSONObject): Mention? {
            val start = o.optInt("s", -1)
            val end = o.optInt("e", -1)
            val handle = if (o.has("h") && !o.isNull("h")) o.optString("h") else ""
            if (start < 0 || end <= start || handle.isBlank() || handle.length > Mentions.MAX_HANDLE_CHARS) return null
            return Mention(start, end, handle, o.optBoolean("me"))
        }
    }
}

/**
 * Mentions both ways (iOS 17): the "@" suggestions in a group's composer, the library's
 * `MessagePart.Mention` parts a mention is sent as (part 0 with the text's runs, as an iPhone sends
 * them), and the ranges kept with an incoming message ([IMessageExtra.mentions]) to draw the names
 * highlighted. The address is sent bare, as OpenBubbles sends it (its `Mentionable.address`); an
 * incoming one is compared whole ([IMessageAddress.same]: a phone number by all its digits).
 *
 * Pure. Tested in MentionsTest.
 */
object Mentions {

    /** The most mentions kept for one part (a sender could send thousands). */
    const val MAX_PER_PART = 50

    /** The longest address a mention may name. */
    const val MAX_HANDLE_CHARS = 320

    /** The longest name typed after "@" that is still looked up. */
    const val MAX_QUERY_CHARS = 40

    /** At most this many members are suggested at once. */
    const val MAX_SUGGESTIONS = 8

    /** A group's member, as the composer offers them: their address and the name the chat shows. */
    data class Member(val handle: String, val name: String)

    /** The address a mention of [handle] ("tel:+15551234567") is sent with: bare, as OpenBubbles sends it. */
    fun uriFor(handle: String): String = Handles.display(handle.trim())

    /** Whether [uri] (a mention's address, with or without its scheme) is one of the user's addresses [me]. */
    fun isMine(uri: String, me: Collection<String>): Boolean =
        uri.isNotBlank() && me.any { IMessageAddress.same(it, uri) }

    /**
     * The name a mention of a member called [name] shows, as an iPhone puts it: the first name of a
     * contact; an address (no contact) as it is.
     */
    fun shownName(name: String): String {
        val n = name.trim()
        if (n.isEmpty() || '@' in n || n.firstOrNull()?.let { it == '+' || it.isDigit() } == true) return n
        return n.split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotEmpty() } ?: n
    }

    /**
     * [mentions] as they are kept and sent over [text]: within it, never splitting a character
     * ([TextRuns.snap]), with an address, in order, none overlapping (the first kept), at most
     * [MAX_PER_PART].
     */
    fun normalize(text: String, mentions: List<Mention>): List<Mention> {
        if (text.isEmpty() || mentions.isEmpty()) return emptyList()
        val out = ArrayList<Mention>()
        for (m in mentions.sortedBy { it.start }) {
            if (m.handle.isBlank() || m.handle.length > MAX_HANDLE_CHARS) continue
            val (s, e) = TextRuns.snap(text, m.start, m.end) ?: continue
            if (out.isNotEmpty() && s < out.last().end) continue
            out += m.copy(start = s, end = e)
            if (out.size >= MAX_PER_PART) break
        }
        return out
    }

    /**
     * The library's parts for a message part reading [text], formatted with [runs], with
     * [mentions]: the stretches between the mentions as [TextRuns.toParts] makes them (formatting
     * laid over each), each mention a `Mention` part (which carries no formatting), all of them the
     * one message part they make up (part 0, as rustpush numbers a run of text and mentions).
     */
    fun toParts(text: String, runs: List<TextRun>, mentions: List<Mention>): List<MessagePart> {
        val norm = normalize(text, mentions)
        if (norm.isEmpty()) return TextRuns.toParts(text, runs)
        val formatted = TextRuns.normalize(text, runs)
        val out = ArrayList<MessagePart>()
        var at = 0
        fun plain(from: Int, to: Int) {
            if (to <= from) return
            val piece = text.substring(from, to)
            val shifted = formatted.mapNotNull { r ->
                val s = maxOf(r.start, from) - from
                val e = minOf(r.end, to) - from
                if (e > s) r.copy(start = s, end = e) else null
            }
            out += TextRuns.toParts(piece, shifted)
        }
        for (m in norm) {
            plain(at, m.start)
            out += MessagePart.Mention(uriFor(m.handle), text.substring(m.start, m.end))
            at = m.end
        }
        plain(at, text.length)
        return out
    }

    /**
     * The mentions of a message's parts as they came, by part index ([DirectArchive.partIndexes]),
     * over each part's text as [DirectArchive.partTexts] joins it; [me] are the user's addresses,
     * which mark the mentions of the user. Parts without any are left out.
     */
    fun fromParts(parts: List<IndexedMessagePart>, me: Collection<String>): Map<Int, List<Mention>> {
        val texts = LinkedHashMap<Int, StringBuilder>()
        val found = LinkedHashMap<Int, ArrayList<Mention>>()
        for ((idx, p) in DirectArchive.partIndexes(parts).zip(parts)) {
            val text = texts.getOrPut(idx) { StringBuilder() }
            when (val part = p.part) {
                is MessagePart.Text -> text.append(part.v1)
                is MessagePart.Mention -> {
                    val uri = part.v1.trim()
                    if (part.v2.isNotEmpty() && uri.isNotEmpty() && uri.length <= MAX_HANDLE_CHARS) {
                        val list = found.getOrPut(idx) { ArrayList() }
                        if (list.size < MAX_PER_PART) list += Mention(text.length, text.length + part.v2.length, uri, isMine(uri, me))
                    }
                    text.append(part.v2)
                }
                is MessagePart.Attachment, is MessagePart.Object -> Unit
            }
        }
        return found.mapValues { (idx, m) -> normalize(texts[idx].toString(), m) }.filterValues { it.isNotEmpty() }
    }

    /** The mentions of one part's new text in an edit (all its parts are that one part). */
    fun fromEdit(parts: List<MessagePart>, me: Collection<String>): List<Mention> =
        fromParts(parts.map { IndexedMessagePart(it, 0UL, null) }, me)[0].orEmpty()

    /**
     * [mentions] over [text] once its outer white space is trimmed (as a message is sent, see
     * [TextRuns.trimmed]): moved along with it, a mention cut by the trim left out.
     */
    fun trimmed(text: String, mentions: List<Mention>): List<Mention> {
        val start = text.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return emptyList()
        val end = text.indexOfLast { !it.isWhitespace() } + 1
        val body = text.substring(start, end)
        return normalize(body, mentions.mapNotNull { m ->
            val s = m.start - start
            val e = m.end - start
            if (s < 0 || e > body.length) null else m.copy(start = s, end = e)
        })
    }

    /**
     * The mentions that are still there after [old] was edited into [new]: those wholly in the
     * stretch both share at their start, or at their end (moved with it), and not run into by what
     * was typed; the rest go, as the edit changed their names.
     */
    fun afterEdit(old: String, new: String, mentions: List<Mention>): List<Mention> {
        if (mentions.isEmpty() || new.isEmpty()) return emptyList()
        var prefix = 0
        val most = minOf(old.length, new.length)
        while (prefix < most && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < most - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
        val shift = new.length - old.length
        val kept = normalize(old, mentions).mapNotNull { m ->
            val moved = when {
                m.end <= prefix -> m
                m.start >= old.length - suffix -> m.copy(start = m.start + shift, end = m.end + shift)
                else -> return@mapNotNull null
            }
            // Letters typed right against its name made it another word ("Ann" into "Anna").
            moved.takeIf { !glued(new, it.start, it.end) || glued(old, m.start, m.end) }
        }
        return normalize(new, kept)
    }

    /** Whether the stretch [start] to [end] of [text] has a letter or digit right against it. */
    private fun glued(text: String, start: Int, end: Int): Boolean =
        (start > 0 && text[start - 1].isLetterOrDigit()) || (end < text.length && text[end].isLetterOrDigit())

    /**
     * The name being looked up right before the cursor: "@" (at the start or after a space or a
     * line break) and what follows it up to [cursor] ([text] excluded: no space, at most
     * [MAX_QUERY_CHARS]). [start] is where the "@" is: a pick replaces [start] to [cursor].
     */
    data class Query(val start: Int, val end: Int, val text: String)

    /** The [Query] being typed at [cursor] in [text], or null when none is; [taken] are stretches that are mentions already. */
    fun query(text: CharSequence, cursor: Int, taken: List<IntRange> = emptyList()): Query? {
        if (cursor <= 0 || cursor > text.length) return null
        var i = cursor - 1
        while (i >= 0 && cursor - i <= MAX_QUERY_CHARS + 1) {
            val c = text[i]
            if (c == '@') {
                val before = if (i == 0) ' ' else text[i - 1]
                if (!before.isWhitespace()) return null
                if (taken.any { i in it }) return null
                return Query(i, cursor, text.subSequence(i + 1, cursor).toString())
            }
            if (c.isWhitespace()) return null
            i--
        }
        return null
    }

    /**
     * The members to suggest for [query] (what is typed after "@"): those with a word of their name
     * (or their address) starting with it, ignoring case and accents; all of them for none. In the
     * order given, at most [MAX_SUGGESTIONS].
     */
    fun suggestions(query: String, members: List<Member>): List<Member> {
        val q = fold(query.trim())
        val seen = HashSet<String>()
        return members.filter { m ->
            m.handle.isNotBlank() && seen.add(IMessageAddress.key(m.handle) ?: m.handle) &&
                (q.isEmpty() || fold(m.name).split(Regex("[\\s\\-]+")).any { it.startsWith(q) } || fold(Handles.display(m.handle)).startsWith(q))
        }.take(MAX_SUGGESTIONS)
    }

    /** [s] in lower case without its accents, for matching a name. */
    private fun fold(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** As stored with a message ([IMessageExtra.mentions]): each part's mentions by its index. */
    fun toJson(byPart: Map<Int, List<Mention>>): JSONObject = JSONObject().apply {
        byPart.toSortedMap().forEach { (idx, list) -> if (list.isNotEmpty()) put(idx.toString(), toJsonArray(list)) }
    }

    fun toJsonArray(mentions: List<Mention>): JSONArray = JSONArray().apply { mentions.forEach { put(it.toJson()) } }

    fun fromJsonArray(a: JSONArray?): List<Mention> =
        if (a == null) emptyList() else (0 until minOf(a.length(), MAX_PER_PART)).mapNotNull { a.optJSONObject(it)?.let(Mention::fromJson) }

    fun fromJson(o: JSONObject?): Map<Int, List<Mention>> {
        if (o == null) return emptyMap()
        return o.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to fromJsonArray(o.optJSONArray(k)) } }
            .filter { it.second.isNotEmpty() }.toMap()
    }
}
