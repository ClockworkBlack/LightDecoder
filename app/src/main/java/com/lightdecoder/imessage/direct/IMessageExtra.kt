package com.lightdecoder.imessage.direct

import androidx.annotation.StringRes
import com.lightdecoder.R
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * What an iMessage carries beyond its text and media, kept with it in the archive
 * ([com.lightdecoder.relay.MessageDto.extra]) as a JSON object whose fields are all optional.
 *
 * @param parts   each message part's text by its part index, so that an edit or an unsend of one
 *   part can rebuild the message's text ([text])
 * @param edits   how many edits were applied (an iPhone allows [MAX_EDITS])
 * @param effect  Apple's id of the bubble or screen effect it was sent with
 * @param voice   an audio message
 * @param link    the rich link preview it came with (no image)
 * @param app     the iMessage app (or game) balloon it is
 * @param sticker it is a sticker
 * @param unsent  the part indexes the sender unsent (gone from [parts] and from the media), so
 *   that the same unsend handed over again, or Apple sending the message again, changes nothing
 * @param runs    iOS 18's formatting (bold, italic, underline, strikethrough) and text effects
 *   (Big, Shake, ...), by part index: the formatted stretches of each part's text ([TextRuns])
 * @param scheduledAt Send Later: when Apple sends it (ms; 0: sent at once). Until then the user's
 *   message shows "Send Later · <time>" ([SendLater.pending]), as on an iPhone
 * @param mentions the mentions in its text ("@" and a member's name, iOS 17), by part index: who
 *   each names, over each part's text ([Mentions]); one of the user is marked
 * @param pollVotes a poll's card ([Polls]): each person's votes, the latest of each
 * @param pollCards a poll's card: the GUIDs of the cards its session sent since (a choice added), whose
 *   votes are this card's too
 * @param stickers stickers people put on the message (iOS 17), each by the message that put it there
 * @param history an edited message's earlier texts (iOS 16's edit history), oldest first, each with
 *   when it became the text; the latest [MAX_HISTORY] of them, each at most [MAX_HISTORY_TEXT_CHARS]
 * @param call    a FaceTime call's row ([com.lightdecoder.imessage.facetime.CallRows]): what the call
 *   was, for its sign and for calling back
 *
 * Pure. Tested in IMessageExtraTest.
 */
data class IMessageExtra(
    val parts: Map<Int, String> = emptyMap(),
    val edits: Int = 0,
    val effect: String? = null,
    val voice: Boolean = false,
    val link: Link? = null,
    val app: App? = null,
    val sticker: Boolean = false,
    val unsent: Set<Int> = emptySet(),
    val runs: Map<Int, List<TextRun>> = emptyMap(),
    val scheduledAt: Long = 0,
    val mentions: Map<Int, List<Mention>> = emptyMap(),
    val pollVotes: List<Polls.Ballot> = emptyList(),
    val pollCards: List<String> = emptyList(),
    val stickers: List<PlacedSticker> = emptyList(),
    val history: List<Version> = emptyList(),
    val call: CallInfo? = null,
) {
    /**
     * A FaceTime call, as its row keeps it: the call's guid, who placed it ([outgoing]: the user),
     * audio or [video], how it ended ([outcome]: an [com.lightdecoder.imessage.facetime.Outcome]'s
     * wire name) and, when it was talked in, for how long.
     */
    data class CallInfo(val guid: String, val outgoing: Boolean, val video: Boolean, val outcome: String, val durationMs: Long = 0) {
        fun toJson(): JSONObject = JSONObject().put("guid", guid).put("outgoing", outgoing).put("video", video).put("outcome", outcome)
            .apply { if (durationMs > 0) put("ms", durationMs) }

        companion object {
            fun fromJson(o: JSONObject): CallInfo? {
                val guid = o.text("guid").takeIf { it.isNotBlank() && it.length <= 64 } ?: return null
                val outcome = o.text("outcome").takeIf { it.isNotBlank() && it.length <= 32 } ?: return null
                return CallInfo(guid, o.optBoolean("outgoing"), o.optBoolean("video"), outcome, o.optLong("ms").coerceAtLeast(0))
            }
        }
    }

    /**
     * An earlier text of an edited message: what it read ([text]), from [atMs] on (when it was sent,
     * or edited to read that).
     */
    data class Version(val text: String, val atMs: Long) {
        fun toJson(): JSONObject = JSONObject().put("text", text).put("t", atMs)

        companion object {
            fun fromJson(o: JSONObject): Version? {
                val text = o.text("text").takeIf { it.isNotEmpty() } ?: return null
                return Version(capped(text), o.optLong("t"))
            }
        }
    }

    /**
     * [history] once the message's text until now, [previous] (its text since [sinceMs]), is edited
     * away: that version kept after the others (the same one again, as Apple hands an edit over
     * again after a restart, is kept once), the latest [MAX_HISTORY] of them.
     */
    fun withEarlier(previous: String, sinceMs: Long): List<Version> {
        if (previous.isEmpty()) return history
        val version = Version(capped(previous), sinceMs)
        if (history.lastOrNull() == version) return history
        return (history + version).takeLast(MAX_HISTORY)
    }

    /**
     * A link preview: the address, the page's title and summary, and the site it is on; [image] is
     * the key its picture is kept under in the iMessage media folder ([linkImageKey]),
     * empty when it has none.
     */
    data class Link(val url: String, val title: String = "", val summary: String = "", val site: String = "", val image: String = "") {
        /**
         * Whether a tap may open it: a web page only. The sender chooses the address, so one of any
         * other kind (a phone number to call, another app's link) behind a title of their choosing
         * is not opened.
         */
        val opensInBrowser: Boolean get() = isWebAddress(url)
    }

    /**
     * A sticker someone put on the message (dragged onto its bubble, iOS 17): the GUID of the message
     * that put it there ([id]), who did ([by]: [Polls.ME] for the user, else their address, bare), and when.
     * Its picture is kept under [stickerKey].
     */
    data class PlacedSticker(val id: String, val by: String, val atMs: Long) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("by", by).put("t", atMs)

        companion object {
            fun fromJson(o: JSONObject): PlacedSticker? {
                val id = o.text("id")
                val by = o.text("by")
                if (id.isBlank() || id.length > 64 || by.isBlank() || by.length > Mentions.MAX_HANDLE_CHARS) return null
                return PlacedSticker(id, by, o.optLong("t"))
            }
        }
    }

    /**
     * An iMessage app's balloon. [root] is the archive id of the message that started its session
     * (a game's first move); [url] and [session] are the app's own state.
     */
    data class App(
        val name: String = "",
        val appId: Long? = null,
        val bundleId: String = "",
        val url: String = "",
        val session: String = "",
        val ldText: String = "",
        val isLive: Boolean = false,
        val imageTitle: String = "",
        val imageSubtitle: String = "",
        val caption: String = "",
        val subcaption: String = "",
        val secondarySubcaption: String = "",
        val tertiarySubcaption: String = "",
        val root: String = "",
    ) {
        /** The app's name as an iPhone shows it; empty when Apple did not say (the library then calls it "None"). */
        val shownName: String get() = name.takeIf { it.isNotBlank() && it != UNNAMED_APP }.orEmpty()

        /** The card's main line: its caption, else the app's summary of it. */
        val cardCaption: String get() = caption.ifBlank { ldText }

        /** What the card says in one line, as a message's text (the list's snippet, search): [cardCaption], else the app's name. */
        fun summary(): String = cardCaption.ifBlank { shownName }

        /** Whether a tap can open it in the browser (an app that keeps its state in a web address, as a Mac opens it). */
        val opensInBrowser: Boolean get() = isWebAddress(url)

        /** As stored with the message (and as it crosses to the ":imessage" process, [DirectWire]). */
        fun toJson(): JSONObject = JSONObject()
            .put("name", name)
            .put("appId", appId ?: JSONObject.NULL)
            .put("bundleId", bundleId)
            .put("url", url)
            .put("session", session)
            .put("ldText", ldText)
            .put("isLive", isLive)
            .put("imageTitle", imageTitle)
            .put("imageSubtitle", imageSubtitle)
            .put("caption", caption)
            .put("subcaption", subcaption)
            .put("secondarySubcaption", secondarySubcaption)
            .put("tertiarySubcaption", tertiarySubcaption)
            .put("root", root)

        companion object {
            /** Reads what [toJson] wrote. */
            fun fromJson(o: JSONObject) = App(
                name = o.text("name"),
                appId = if (o.has("appId") && !o.isNull("appId")) o.optLong("appId") else null,
                bundleId = o.text("bundleId"),
                url = o.text("url"),
                session = o.text("session"),
                ldText = o.text("ldText"),
                isLive = o.optBoolean("isLive"),
                imageTitle = o.text("imageTitle"),
                imageSubtitle = o.text("imageSubtitle"),
                caption = o.text("caption"),
                subcaption = o.text("subcaption"),
                secondarySubcaption = o.text("secondarySubcaption"),
                tertiarySubcaption = o.text("tertiarySubcaption"),
                root = o.text("root"),
            )
        }
    }

    /** The message's text from [parts]: each part's text in part order, one per line. */
    fun text(): String = parts.toSortedMap().values.filter { it.isNotEmpty() }.joinToString("\n")

    /**
     * The formatting of a message whose text (as shown) is [text], over that text: each part's
     * [runs] moved to where its text is in [text()][text]. With no part texts kept, [text] is part 0.
     * Empty when the stored parts no longer make up [text] (nothing is drawn in the wrong place).
     */
    fun textRuns(text: String): List<TextRun> {
        if (runs.isEmpty() || text.isEmpty()) return emptyList()
        if (parts.isEmpty()) return if (runs.keys == setOf(0)) TextRuns.normalize(text, runs.getValue(0)) else emptyList()
        if (text() != text) return emptyList()
        val out = ArrayList<TextRun>()
        var at = 0
        for ((idx, t) in parts.toSortedMap()) {
            if (t.isEmpty()) continue
            runs[idx]?.let { r -> TextRuns.normalize(t, r).forEach { out += it.copy(start = it.start + at, end = it.end + at) } }
            at += t.length + 1
        }
        return out
    }

    /**
     * The mentions of a message whose text (as shown) is [text], over that text, as [textRuns] moves
     * runs. Empty when the stored parts no longer make up [text].
     */
    fun textMentions(text: String): List<Mention> {
        if (mentions.isEmpty() || text.isEmpty()) return emptyList()
        if (parts.isEmpty()) return if (mentions.keys == setOf(0)) Mentions.normalize(text, mentions.getValue(0)) else emptyList()
        if (text() != text) return emptyList()
        val out = ArrayList<Mention>()
        var at = 0
        for ((idx, t) in parts.toSortedMap()) {
            if (t.isEmpty()) continue
            mentions[idx]?.let { m -> Mentions.normalize(t, m).forEach { out += it.copy(start = it.start + at, end = it.end + at) } }
            at += t.length + 1
        }
        return out
    }

    /** [mentions] with part [idx]'s set to [partMentions] (none: taken off). */
    fun withMentions(idx: Int, partMentions: List<Mention>): Map<Int, List<Mention>> =
        if (partMentions.isEmpty()) mentions - idx else mentions + (idx to partMentions)

    /** Whether the message mentions the user (as the ":imessage" process marked it). */
    val mentionsMe: Boolean get() = mentions.values.any { list -> list.any { it.mine } }

    /**
     * This copy of a message (Apple's, handed over again) with what was gathered on [stored] since
     * it first came: the poll's votes and later cards, a poll's newer state (a choice added since),
     * and the stickers put on it. Anything else is this copy's.
     */
    fun keepingGathered(stored: IMessageExtra): IMessageExtra {
        val newerPoll = stored.app?.takeIf { s -> Polls.isPoll(s) && app != null && Polls.isPoll(app) && Polls.countOf(s.url) >= Polls.countOf(app.url) }
        return copy(
            pollVotes = stored.pollVotes.ifEmpty { pollVotes },
            pollCards = (stored.pollCards + pollCards).distinct().take(MAX_POLL_CARDS),
            stickers = (stored.stickers + stickers).distinctBy { it.id }.take(MAX_STICKERS),
            app = newerPoll?.copy(root = app?.root ?: newerPoll.root) ?: app,
            link = link?.let { l -> if (l.image.isEmpty() && stored.link?.url == l.url) l.copy(image = stored.link.image) else l },
        )
    }

    /** Which text effects the message's text has, in the order they first come. */
    fun textEffects(): List<TextEffectKind> =
        runs.toSortedMap().values.flatten().mapNotNull { it.effect }.distinct()

    /** [runs] with part [idx]'s set to [partRuns] (none: taken off). */
    fun withRuns(idx: Int, partRuns: List<TextRun>): Map<Int, List<TextRun>> =
        if (partRuns.isEmpty()) runs - idx else runs + (idx to partRuns)

    /** The JSON stored with the message; empty when there is nothing to store. */
    fun toJson(): String {
        val o = JSONObject()
        if (parts.isNotEmpty()) o.put("parts", JSONObject().apply { parts.toSortedMap().forEach { (i, t) -> put(i.toString(), t) } })
        if (edits > 0) o.put("edits", edits)
        if (!effect.isNullOrEmpty()) o.put("effect", effect)
        if (voice) o.put("voice", true)
        link?.let {
            o.put(
                "link",
                JSONObject().put("url", it.url).put("title", it.title).put("summary", it.summary).put("site", it.site)
                    .apply { if (it.image.isNotEmpty()) put("image", it.image) },
            )
        }
        app?.let { o.put("app", it.toJson()) }
        if (sticker) o.put("sticker", true)
        if (unsent.isNotEmpty()) o.put("unsent", JSONArray(unsent.sorted()))
        if (runs.values.any { it.isNotEmpty() }) o.put("runs", TextRuns.toJson(runs))
        if (scheduledAt > 0) o.put("scheduledAt", scheduledAt)
        if (mentions.values.any { it.isNotEmpty() }) o.put("mentions", Mentions.toJson(mentions))
        if (pollVotes.isNotEmpty()) o.put("pollVotes", Polls.ballotsToJson(pollVotes))
        if (pollCards.isNotEmpty()) o.put("pollCards", JSONArray(pollCards))
        if (stickers.isNotEmpty()) o.put("stickers", JSONArray().apply { stickers.forEach { put(it.toJson()) } })
        if (history.isNotEmpty()) o.put("history", JSONArray().apply { history.forEach { put(it.toJson()) } })
        call?.let { o.put("call", it.toJson()) }
        return if (o.length() == 0) "" else o.toString()
    }

    companion object {
        /** An iPhone lets a message be edited this many times. */
        const val MAX_EDITS = 5

        /** The most earlier texts an edited message keeps ([history]): as many as an iPhone's five edits leave. */
        const val MAX_HISTORY = 5

        /** The most of an earlier text kept; a longer one is cut, with "…". */
        const val MAX_HISTORY_TEXT_CHARS = 2_000

        /** [text] at most [MAX_HISTORY_TEXT_CHARS] long, never ending in half a character pair. */
        internal fun capped(text: String): String {
            if (text.length <= MAX_HISTORY_TEXT_CHARS) return text
            var end = MAX_HISTORY_TEXT_CHARS - 1
            if (Character.isHighSurrogate(text[end - 1])) end--
            return text.substring(0, end) + "…"
        }

        /** Apple's id of the invisible ink effect: the text stays hidden until tapped. */
        const val INVISIBLE_INK = "com.apple.MobileSMS.expressivesend.invisibleink"

        /** The name the library gives an app Apple did not name. */
        const val UNNAMED_APP = "None"

        /** The bundle of a rich link's balloon, which is no app's card. */
        const val LINK_BALLOON = "com.apple.messages.URLBalloonProvider"

        /** The most later cards a poll keeps ([pollCards]) and stickers a message keeps ([stickers]). */
        const val MAX_POLL_CARDS = 20
        const val MAX_STICKERS = 20

        /** The key the link preview picture of message [guid] is kept under (in the iMessage media folder, a JPEG). */
        fun linkImageKey(guid: String): String = "$guid/link"

        /** The key the sticker [participant] put as a tapback on message [guid] is kept under (a PNG). */
        fun tapbackStickerKey(guid: String, participant: String): String = "$guid/tapback/$participant"

        /** The key of the sticker message [stickerGuid] put on message [guid] (a PNG). */
        fun stickerKey(guid: String, stickerGuid: String): String = "$guid/sticker/$stickerGuid"

        /** The file extension of a key's picture: a link's a JPEG, a sticker's a PNG. */
        fun extensionOf(key: String): String = if (key.endsWith("/link")) "jpg" else "png"

        /** Apple's effects, by id, with the string resource of the name an iPhone shows ("Sent with Slam"). */
        val EFFECTS: List<Pair<String, Int>> = listOf(
            "com.apple.MobileSMS.expressivesend.impact" to R.string.imessage_effect_slam,
            "com.apple.MobileSMS.expressivesend.loud" to R.string.imessage_effect_loud,
            "com.apple.MobileSMS.expressivesend.gentle" to R.string.imessage_effect_gentle,
            INVISIBLE_INK to R.string.imessage_effect_invisible_ink,
            "com.apple.messages.effect.CKEchoEffect" to R.string.imessage_effect_echo,
            "com.apple.messages.effect.CKSpotlightEffect" to R.string.imessage_effect_spotlight,
            "com.apple.messages.effect.CKHappyBirthdayEffect" to R.string.imessage_effect_balloons,
            "com.apple.messages.effect.CKConfettiEffect" to R.string.imessage_effect_confetti,
            "com.apple.messages.effect.CKHeartEffect" to R.string.imessage_effect_love,
            "com.apple.messages.effect.CKLasersEffect" to R.string.imessage_effect_lasers,
            "com.apple.messages.effect.CKFireworksEffect" to R.string.imessage_effect_fireworks,
            "com.apple.messages.effect.CKSparklesEffect" to R.string.imessage_effect_celebration,
        )

        /**
         * The string resource of the effect [id]'s name ("Slam"), or null for none or one this app
         * does not know.
         */
        @StringRes
        fun effectName(id: String?): Int? = EFFECTS.firstOrNull { it.first == id }?.second

        /** Whether [url] is a web page's address (http or https). */
        fun isWebAddress(url: String): Boolean =
            url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

        /**
         * Whether [id] is one of Apple's bubble effects (Slam, Loud, Gentle, Invisible Ink: the
         * bubble itself moves), not a screen effect (Echo, Balloons, ...: the whole screen).
         */
        fun isBubbleEffect(id: String): Boolean = id.startsWith(BUBBLE_EFFECT_PREFIX)

        private const val BUBBLE_EFFECT_PREFIX = "com.apple.MobileSMS.expressivesend."

        /** Whether [id] is the invisible ink effect. */
        fun isInvisibleInk(id: String?): Boolean = id == INVISIBLE_INK

        /**
         * A message's [text] wherever it shows outside its bubble (the conversation list, a reply's
         * quote, a notification, read aloud): [inkedLabel] ("Sent with Invisible Ink") for a text
         * sent with invisible ink ([extraJson] is its stored extra), as on an iPhone; else [text].
         */
        fun shownText(text: String, extraJson: String, inkedLabel: String): String =
            if (text.isNotEmpty() && extraJson.isNotEmpty() && isInvisibleInk(parse(extraJson).effect)) inkedLabel else text

        /** Reads what [toJson] wrote; anything unreadable gives an empty one. */
        fun parse(json: String): IMessageExtra {
            if (json.isBlank()) return IMessageExtra()
            return try {
                val o = JSONObject(json)
                IMessageExtra(
                    parts = o.optJSONObject("parts")?.let { p ->
                        p.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to p.optString(k) } }.toMap()
                    }.orEmpty(),
                    edits = o.optInt("edits"),
                    effect = o.text("effect").ifEmpty { null },
                    voice = o.optBoolean("voice"),
                    link = o.optJSONObject("link")?.let {
                        Link(it.text("url"), it.text("title"), it.text("summary"), it.text("site"), it.text("image"))
                    },
                    app = o.optJSONObject("app")?.let(App::fromJson),
                    sticker = o.optBoolean("sticker"),
                    unsent = o.optJSONArray("unsent")?.let { a ->
                        (0 until a.length()).map { a.optInt(it, -1) }.filter { it >= 0 }.toSet()
                    }.orEmpty(),
                    runs = TextRuns.fromJson(o.optJSONObject("runs")),
                    scheduledAt = o.optLong("scheduledAt"),
                    mentions = Mentions.fromJson(o.optJSONObject("mentions")),
                    pollVotes = Polls.ballotsFromJson(o.optJSONArray("pollVotes")),
                    pollCards = o.optJSONArray("pollCards")?.let { a ->
                        (0 until minOf(a.length(), MAX_POLL_CARDS)).map { a.optString(it) }.filter { it.isNotBlank() && it.length <= 64 }
                    }.orEmpty(),
                    stickers = o.optJSONArray("stickers")?.let { a ->
                        (0 until minOf(a.length(), MAX_STICKERS)).mapNotNull { a.optJSONObject(it)?.let(PlacedSticker::fromJson) }
                    }.orEmpty(),
                    history = o.optJSONArray("history")?.let { a ->
                        (maxOf(0, a.length() - MAX_HISTORY) until a.length()).mapNotNull { a.optJSONObject(it)?.let(Version::fromJson) }
                    }.orEmpty(),
                    call = o.optJSONObject("call")?.let(CallInfo::fromJson),
                )
            } catch (e: JSONException) {
                IMessageExtra()
            }
        }
    }
}

private fun JSONObject.text(key: String): String = if (!has(key) || isNull(key)) "" else optString(key)

