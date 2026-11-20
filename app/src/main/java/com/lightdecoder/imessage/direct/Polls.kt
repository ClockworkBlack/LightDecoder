package com.lightdecoder.imessage.direct

import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * iOS 26's Polls: an iMessage app card (bundle [BUNDLE_ID], named "Polls") whose state is in its
 * address, `data:,<base64 of a JSON object>?src=p&c=<n>`, in the model OpenBubbles reads and writes
 * (its `polls.dart`): a question, the ordered choices (each with an identifier and who added it)
 * and votes. A vote is an update of the card's session that is no card of its own (`isMeta`): its
 * address carries the voter's whole choice, `{"item":{"votes":[{voteOptionIdentifier,
 * participantHandle}]},"version":1}` (the latest from each person counts). "Add Choice" sends the
 * card again with one choice more, its count one higher.
 *
 * What comes from Apple is anyone's in the chat to write: decoded only up to [MAX_JSON_BYTES], with
 * at most [MAX_OPTIONS] choices and short texts; a vote counts for whoever sent it, never for whom
 * its JSON names. Pure. Tested in PollsTest.
 */
object Polls {

    const val BUNDLE_ID = "com.apple.messages.MSMessageExtensionBalloonPlugin:0000000000:com.apple.messages.Polls"

    /** The app's name, as Apple and OpenBubbles give it. */
    const val NAME = "Polls"

    /**
     * The largest poll decoded (its JSON). A card crosses between the processes in one Binder
     * transaction ([DirectWire.MAX_UPDATE_CHARS]), its address kept with it: this keeps one well within.
     */
    const val MAX_JSON_BYTES = 32 * 1024

    /** The longest address decoded (the JSON in base64, and what comes around it). */
    const val MAX_URL_CHARS = (MAX_JSON_BYTES / 3 + 1) * 4 + 256

    /** The most choices a poll is shown with (and can have one added to). */
    const val MAX_OPTIONS = 30

    /** The longest question or choice kept. */
    const val MAX_TEXT_CHARS = 300

    /** The most people whose votes a poll keeps. */
    const val MAX_BALLOTS = 500

    /** A count a poll starts with, as OpenBubbles sends a new one. */
    const val FIRST_COUNT = 2

    /** What a device without the app shows for a poll's card (as iPhones send it). */
    const val FALLBACK_CAPTION = "ENG: Update your device"
    const val FALLBACK_SUBCAPTION = "Learn about how to update your device to see this content."

    /** The text a vote carries for the user's other devices' notifications, as OpenBubbles sends it. */
    const val VOTE_NOTICE = "1 Polls Message"

    /** One choice: its identifier (an upper-case UUID), its words, and who added it (an address, bare). */
    data class Option(val id: String, val text: String, val creator: String = "", val canBeEdited: Boolean = false)

    /** A poll: its question ([title], may be empty), who made it, its choices, and its count ([count], the `c` of its address). */
    data class Poll(val title: String, val creator: String, val options: List<Option>, val count: Int = FIRST_COUNT)

    /**
     * One person's votes ([options], the identifiers of their choices), as of [atMs]: the user's own
     * ([voter] [ME]) or someone's by their address.
     */
    data class Ballot(val voter: String, val options: List<String>, val atMs: Long) {
        fun toJson(): JSONObject = JSONObject().put("v", voter).put("o", JSONArray(options)).put("t", atMs)

        companion object {
            fun fromJson(o: JSONObject): Ballot? {
                val voter = if (o.has("v") && !o.isNull("v")) o.optString("v") else ""
                if (voter.isBlank() || voter.length > Mentions.MAX_HANDLE_CHARS) return null
                val a = o.optJSONArray("o") ?: JSONArray()
                val options = (0 until minOf(a.length(), MAX_OPTIONS)).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() && s.length <= MAX_ID_CHARS } }
                return Ballot(voter, options.distinct(), o.optLong("t"))
            }
        }
    }

    /** The voter the user's own votes are kept as. */
    const val ME = "me"

    /** The longest choice identifier taken. */
    private const val MAX_ID_CHARS = 64

    /** Whether [app] is a poll's card or vote. */
    fun isPoll(app: IMessageExtra.App?): Boolean =
        app != null && (app.bundleId == BUNDLE_ID || app.bundleId.endsWith(":com.apple.messages.Polls"))

    /** The JSON in a poll's address [url] ("data:,<base64>?…"), or null when it is none or too large. */
    private fun payload(url: String): JSONObject? {
        if (url.length > MAX_URL_CHARS || !url.startsWith(PREFIX)) return null
        // Base64 as it is, or with its "+" read as a space or its signs escaped on the way.
        val encoded = url.substring(PREFIX.length).substringBefore('?').substringBefore('#').trim()
            .replace(' ', '+').replace("%2B", "+", ignoreCase = true).replace("%2F", "/", ignoreCase = true).replace("%3D", "=", ignoreCase = true)
        if (encoded.isEmpty()) return null
        val bytes = try {
            if ('-' in encoded || '_' in encoded) Base64.getUrlDecoder().decode(encoded) else Base64.getMimeDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (bytes.isEmpty() || bytes.size > MAX_JSON_BYTES) return null
        val json = String(bytes, Charsets.UTF_8)
        // org.json reads nested objects and arrays by calling itself: thousands deep, a thread's stack
        // runs out (StackOverflowError, which nothing else here would catch). Only plain JSON, so
        // deep, is handed to it.
        if (!plainJsonWithin(json, MAX_NESTING)) return null
        return try {
            JSONObject(json)
        } catch (e: JSONException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    /** The deepest a poll's JSON may nest objects and arrays (Apple's and OpenBubbles' go four deep). */
    const val MAX_NESTING = 32

    /**
     * Whether [json] is plain JSON (strings in double quotes; outside them only JSON's own signs,
     * numbers, `true`, `false` and `null`) nesting objects and arrays no deeper than [max]. Read in
     * one pass, without calling itself. org.json takes more than plain JSON (single quotes, words
     * without quotes that may hold a quote, comments), in which a bracket could hide from a count like
     * this one: anything of the kind is refused (a string starts only where a value or a name can:
     * first, or after `[`, `{`, `,` or `:`), so what org.json then reads nests exactly as deep as
     * counted here.
     */
    internal fun plainJsonWithin(json: String, max: Int): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        // The last sign outside strings and spaces: a string may start only after one of "[{,:" (or first).
        var last = ' '
        for (c in json) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> {
                        inString = false
                        last = '"'
                    }
                }
                continue
            }
            when (c) {
                ' ', '\t', '\n', '\r' -> continue
                '"' -> {
                    if (last != ' ' && last != '[' && last != '{' && last != ',' && last != ':') return false
                    inString = true
                    continue
                }
                '{', '[' -> if (++depth > max) return false
                '}', ']' -> if (--depth < 0) return false
                ',', ':', '-', '+', '.' -> Unit
                in '0'..'9', in 'a'..'z', in 'A'..'Z' -> Unit
                else -> return false
            }
            last = c
        }
        return !inString
    }

    /** The highest count ("c") a poll can have here: what [countOf] reads (six digits). */
    const val MAX_COUNT = 999_999

    /** The count's form in a card's address: "c=" and one to six digits, a parameter of its own. */
    private val COUNT = Regex("[?&]c=(\\d{1,6})(?:&|$)")

    /** The count ("c") in a poll's address, or [FIRST_COUNT] when it has none (or none of six digits at most). */
    fun countOf(url: String): Int =
        COUNT.find("?" + url.substringAfter('?', "").substringBefore('#'))?.groupValues?.get(1)?.toIntOrNull() ?: FIRST_COUNT

    /**
     * The most a poll's count may go up in one change it is sent again with: one for each choice
     * added ("Add Choice" adds one), and a few more for changes that never came here.
     */
    const val MAX_COUNT_STEP = 5

    /**
     * The poll [stored] as it is after a change someone in its chat sent ([changed]: the card sent again,
     * as "Add Choice" sends it), or null when the change is not one to take. A change only adds
     * choices: the question and every choice there already stay as they are (another's words, or
     * another order, never move a vote), and only choices with new identifiers are added, at the
     * end, in the order they came. Its count is one more than the stored one, or a few more
     * ([MAX_COUNT_STEP]) for changes missed, and at most as many choices are added as it went up;
     * one older, as old, or far ahead is not taken (nor could one then hold back every later one).
     */
    fun merged(stored: Poll, changed: Poll): Poll? {
        val step = changed.count.toLong() - stored.count
        if (step < 1 || step > MAX_COUNT_STEP || changed.count > MAX_COUNT) return null
        val known = stored.options.map { it.id }.toSet()
        val texts = stored.options.map { it.text.lowercase() }.toMutableSet()
        val added = changed.options.filter { it.id !in known && texts.add(it.text.lowercase()) }
            .take(minOf(step.toInt(), MAX_OPTIONS - stored.options.size))
        if (added.isEmpty()) return null
        return stored.copy(options = stored.options + added, count = changed.count)
    }

    /** The poll a card's address [url] holds, or null when it holds none that can be read. */
    fun decode(url: String): Poll? {
        val item = payload(url)?.optJSONObject("item") ?: return null
        val list = item.optJSONArray("orderedPollOptions") ?: return null
        val seen = HashSet<String>()
        val options = ArrayList<Option>()
        for (i in 0 until list.length()) {
            if (options.size >= MAX_OPTIONS) break
            val o = list.optJSONObject(i) ?: continue
            val id = o.text("optionIdentifier").take(MAX_ID_CHARS)
            val text = o.text("text").ifBlank { o.text("attributedText") }.let(::clean)
            if (id.isBlank() || text.isBlank() || !seen.add(id)) continue
            options += Option(id, text, o.text("creatorHandle").take(Mentions.MAX_HANDLE_CHARS), o.optBoolean("canBeEdited"))
        }
        if (options.isEmpty()) return null
        return Poll(clean(item.text("title")), item.text("creatorHandle").take(Mentions.MAX_HANDLE_CHARS), options, countOf(url))
    }

    /** The choices a vote's address [url] says its sender chose, or null when it is no vote. Whom its JSON names does not count. */
    fun decodeVotes(url: String): List<String>? {
        val item = payload(url)?.optJSONObject("item") ?: return null
        val votes = item.optJSONArray("votes") ?: return null
        return (0 until votes.length()).mapNotNull { i ->
            votes.optJSONObject(i)?.text("voteOptionIdentifier")?.takeIf { it.isNotBlank() && it.length <= MAX_ID_CHARS }
        }.distinct().take(MAX_OPTIONS)
    }

    /** [poll] as a card's address, its count as it says ([Poll.count]). */
    fun encode(poll: Poll): String {
        val item = JSONObject()
            .put("orderedPollOptions", JSONArray().apply {
                poll.options.forEach { o ->
                    put(
                        JSONObject().put("attributedText", o.text).put("canBeEdited", o.canBeEdited).put("creatorHandle", o.creator)
                            .put("optionIdentifier", o.id).put("text", o.text),
                    )
                }
            })
            .put("votes", JSONArray())
            .put("creatorHandle", poll.creator)
            .put("title", poll.title)
        return PREFIX + base64(JSONObject().put("item", item).put("version", VERSION)) + "?src=p&c=" + poll.count
    }

    /** A vote's address: the user ([voter], their address, bare) chose [optionIds] (none: took their vote back), as OpenBubbles sends it. */
    fun votesUrl(optionIds: List<String>, voter: String): String {
        val item = JSONObject()
            .put("orderedPollOptions", JSONArray())
            .put("votes", JSONArray().apply { optionIds.distinct().forEach { put(JSONObject().put("voteOptionIdentifier", it).put("participantHandle", voter)) } })
            .put("creatorHandle", JSONObject.NULL)
            .put("title", JSONObject.NULL)
        return PREFIX + base64(JSONObject().put("item", item).put("version", VERSION))
    }

    /** A new poll by [creator] (the user's address, bare): [question] (may be empty) and its [choices] (blank ones left out). */
    fun create(question: String, choices: List<String>, creator: String, newId: () -> String = ::newId): Poll? {
        val texts = choices.map(::clean).filter { it.isNotBlank() }.distinct().take(MAX_OPTIONS)
        if (texts.size < 2) return null
        return Poll(clean(question), creator, texts.map { Option(newId(), it, creator) }, FIRST_COUNT)
    }

    /**
     * [poll] with the choice [text] added by [creator], its count one higher than the stored one, as
     * OpenBubbles counts; null when it cannot take it (blank, there already, too many, or its count
     * already at [MAX_COUNT]: one higher could not be read back).
     */
    fun addChoice(poll: Poll, text: String, creator: String, id: String = newId()): Poll? {
        val t = clean(text)
        if (t.isBlank() || poll.options.size >= MAX_OPTIONS || poll.options.any { it.text.equals(t, ignoreCase = true) }) return null
        if (poll.count >= MAX_COUNT) return null
        return poll.copy(options = poll.options + Option(id, t, creator), count = poll.count + 1)
    }

    /** The app summary a poll's card carries (what a device without Polls shows in a list): its choices. */
    fun ldText(poll: Poll): String = poll.options.joinToString(", ") { it.text }

    /**
     * A poll's card ([IMessageExtra.App]) as it is sent: Polls' name and bundle, [poll] in its
     * address, its [session] (an upper-case UUID), the choices as its summary, the words a device
     * without the app shows, live. As OpenBubbles sends one (without Polls' icon).
     */
    fun card(poll: Poll, session: String, root: String = ""): IMessageExtra.App = IMessageExtra.App(
        name = NAME,
        bundleId = BUNDLE_ID,
        url = encode(poll),
        session = session,
        ldText = ldText(poll),
        isLive = true,
        caption = FALLBACK_CAPTION,
        subcaption = FALLBACK_SUBCAPTION,
        root = root,
    )

    /** A vote, as the app update sent to the card [targetGuid] (its session is that card's GUID, as OpenBubbles sends it). */
    fun vote(optionIds: List<String>, voter: String, targetGuid: String): IMessageExtra.App =
        IMessageExtra.App(name = NAME, bundleId = BUNDLE_ID, url = votesUrl(optionIds, voter), session = targetGuid)

    /**
     * Whether [s] is a UUID as the library takes a balloon's session (rustpush unwraps it: anything
     * else would take the ":imessage" process down), written in full.
     */
    fun isUuid(s: String): Boolean = UUID_TEXT.matches(s)

    private val UUID_TEXT = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /** How many voted for each of [poll]'s choices, and who (by [Ballot.voter]), in the poll's order. */
    data class Tally(val option: Option, val voters: List<String>) {
        val count: Int get() = voters.size
    }

    fun tally(poll: Poll, ballots: List<Ballot>): List<Tally> = poll.options.map { o ->
        Tally(o, ballots.filter { o.id in it.options }.sortedBy { it.atMs }.map { it.voter }.distinct())
    }

    /** The user's choice after a tap on [optionId]: added, or taken off when it was chosen (several may be chosen, as on an iPhone). */
    fun toggle(mine: List<String>, optionId: String): List<String> =
        if (optionId in mine) mine - optionId else mine + optionId

    /** [ballots] with [ballot] in its voter's place, unless theirs is newer (the latest counts); never more than [MAX_BALLOTS]. */
    fun withBallot(ballots: List<Ballot>, ballot: Ballot): List<Ballot> {
        val old = ballots.firstOrNull { it.voter == ballot.voter }
        if (old != null && old.atMs > ballot.atMs) return ballots
        val others = ballots.filter { it.voter != ballot.voter }
        if (old == null && others.size >= MAX_BALLOTS) return ballots
        return others + ballot.copy(options = ballot.options.distinct().take(MAX_OPTIONS))
    }

    /** The choices [voter] made, or none. */
    fun choicesOf(ballots: List<Ballot>, voter: String): List<String> = ballots.firstOrNull { it.voter == voter }?.options.orEmpty()

    /** The key a voter's ballot is kept under: [ME] for the user, else the address as [IMessageAddress.key] makes it. */
    fun voterKey(address: String, fromMe: Boolean): String =
        if (fromMe) ME else com.lightdecoder.imessage.IMessageAddress.key(address) ?: Handles.display(address)

    fun ballotsToJson(ballots: List<Ballot>): JSONArray = JSONArray().apply { ballots.forEach { put(it.toJson()) } }

    fun ballotsFromJson(a: JSONArray?): List<Ballot> {
        if (a == null) return emptyList()
        val out = ArrayList<Ballot>()
        for (i in 0 until minOf(a.length(), MAX_BALLOTS)) {
            val b = a.optJSONObject(i)?.let(Ballot::fromJson) ?: continue
            if (out.none { it.voter == b.voter }) out += b
        }
        return out
    }

    /** A choice's or a question's words as they are kept: no control characters, trimmed, at most [MAX_TEXT_CHARS]. */
    private fun clean(text: String): String {
        val t = text.filter { !it.isISOControl() || it == '\n' }.replace('\n', ' ').trim()
        if (t.length <= MAX_TEXT_CHARS) return t
        val end = if (Character.isHighSurrogate(t[MAX_TEXT_CHARS - 1])) MAX_TEXT_CHARS - 1 else MAX_TEXT_CHARS
        return t.substring(0, end)
    }

    private fun base64(o: JSONObject): String = Base64.getEncoder().encodeToString(o.toString().toByteArray(Charsets.UTF_8))

    private fun newId(): String = UUID.randomUUID().toString().uppercase()

    private const val PREFIX = "data:,"
    private const val VERSION = 1

    private fun JSONObject.text(key: String): String = if (!has(key) || isNull(key)) "" else optString(key)
}
