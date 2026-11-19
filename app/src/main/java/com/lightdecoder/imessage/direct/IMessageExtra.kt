package com.lightdecoder.imessage.direct

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
    )

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
        return if (o.length() == 0) "" else o.toString()
    }

    companion object {
        /** An iPhone lets a message be edited this many times. */
        const val MAX_EDITS = 5

        const val INVISIBLE_INK = "com.apple.MobileSMS.expressivesend.invisibleink"

        /** Apple's effects, by id, with the names an iPhone shows ("Sent with Slam"). */
        val EFFECTS: List<Pair<String, String>> = listOf(
            "com.apple.MobileSMS.expressivesend.impact" to "Slam",
            "com.apple.MobileSMS.expressivesend.loud" to "Loud",
            "com.apple.MobileSMS.expressivesend.gentle" to "Gentle",
            INVISIBLE_INK to "Invisible Ink",
            "com.apple.messages.effect.CKEchoEffect" to "Echo",
            "com.apple.messages.effect.CKSpotlightEffect" to "Spotlight",
            "com.apple.messages.effect.CKHappyBirthdayEffect" to "Balloons",
            "com.apple.messages.effect.CKConfettiEffect" to "Confetti",
            "com.apple.messages.effect.CKHeartEffect" to "Love",
            "com.apple.messages.effect.CKLasersEffect" to "Lasers",
            "com.apple.messages.effect.CKFireworksEffect" to "Fireworks",
            "com.apple.messages.effect.CKSparklesEffect" to "Celebration",
        )

        /** The name of the effect [id] ("Slam"), or null for none or one this app does not know. */
        fun effectName(id: String?): String? = EFFECTS.firstOrNull { it.first == id }?.second

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
                )
            } catch (e: JSONException) {
                IMessageExtra()
            }
        }

        private fun JSONObject.text(key: String): String = if (!has(key) || isNull(key)) "" else optString(key)
    }
}
