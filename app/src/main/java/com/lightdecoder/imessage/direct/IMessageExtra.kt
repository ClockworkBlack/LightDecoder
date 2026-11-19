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
) {
    /** A link preview: the address, the page's title and summary, and the site it is on. */
    data class Link(val url: String, val title: String = "", val summary: String = "", val site: String = "")

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
        val opensInBrowser: Boolean
            get() = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)
    }

    /** The message's text from [parts]: each part's text in part order, one per line. */
    fun text(): String = parts.toSortedMap().values.filter { it.isNotEmpty() }.joinToString("\n")

    /** The JSON stored with the message; empty when there is nothing to store. */
    fun toJson(): String {
        val o = JSONObject()
        if (parts.isNotEmpty()) o.put("parts", JSONObject().apply { parts.toSortedMap().forEach { (i, t) -> put(i.toString(), t) } })
        if (edits > 0) o.put("edits", edits)
        if (!effect.isNullOrEmpty()) o.put("effect", effect)
        if (voice) o.put("voice", true)
        link?.let {
            o.put("link", JSONObject().put("url", it.url).put("title", it.title).put("summary", it.summary).put("site", it.site))
        }
        app?.let {
            o.put(
                "app",
                JSONObject()
                    .put("name", it.name)
                    .put("appId", it.appId ?: JSONObject.NULL)
                    .put("bundleId", it.bundleId)
                    .put("url", it.url)
                    .put("session", it.session)
                    .put("ldText", it.ldText)
                    .put("isLive", it.isLive)
                    .put("imageTitle", it.imageTitle)
                    .put("imageSubtitle", it.imageSubtitle)
                    .put("caption", it.caption)
                    .put("subcaption", it.subcaption)
                    .put("secondarySubcaption", it.secondarySubcaption)
                    .put("tertiarySubcaption", it.tertiarySubcaption)
                    .put("root", it.root),
            )
        }
        if (sticker) o.put("sticker", true)
        if (unsent.isNotEmpty()) o.put("unsent", JSONArray(unsent.sorted()))
        return if (o.length() == 0) "" else o.toString()
    }

    companion object {
        /** An iPhone lets a message be edited this many times. */
        const val MAX_EDITS = 5

        /** Apple's id of the invisible ink effect: the text stays hidden until tapped. */
        const val INVISIBLE_INK = "com.apple.MobileSMS.expressivesend.invisibleink"

        /** The name the library gives an app Apple did not name. */
        const val UNNAMED_APP = "None"

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

        /** Whether [id] is the invisible ink effect. */
        fun isInvisibleInk(id: String?): Boolean = id == INVISIBLE_INK

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
                        Link(it.text("url"), it.text("title"), it.text("summary"), it.text("site"))
                    },
                    app = o.optJSONObject("app")?.let {
                        App(
                            name = it.text("name"),
                            appId = if (it.has("appId") && !it.isNull("appId")) it.optLong("appId") else null,
                            bundleId = it.text("bundleId"),
                            url = it.text("url"),
                            session = it.text("session"),
                            ldText = it.text("ldText"),
                            isLive = it.optBoolean("isLive"),
                            imageTitle = it.text("imageTitle"),
                            imageSubtitle = it.text("imageSubtitle"),
                            caption = it.text("caption"),
                            subcaption = it.text("subcaption"),
                            secondarySubcaption = it.text("secondarySubcaption"),
                            tertiarySubcaption = it.text("tertiarySubcaption"),
                            root = it.text("root"),
                        )
                    },
                    sticker = o.optBoolean("sticker"),
                    unsent = o.optJSONArray("unsent")?.let { a ->
                        (0 until a.length()).map { a.optInt(it, -1) }.filter { it >= 0 }.toSet()
                    }.orEmpty(),
                )
            } catch (e: JSONException) {
                IMessageExtra()
            }
        }

        private fun JSONObject.text(key: String): String = if (!has(key) || isNull(key)) "" else optString(key)
    }
}
