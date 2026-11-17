package com.lightdecoder.inbox

import org.json.JSONObject

/**
 * One sticker or image caught from an incoming message notification and decoded
 * on-device. Everything is stored locally; nothing leaves the phone.
 *
 * @param id          stable id (also the on-disk file stem)
 * @param sender      display name of the sender, if the notification gave one
 * @param title       conversation title, if any
 * @param appPackage  the messaging app the notification came from
 * @param timestampMs when it was caught
 * @param mime        original media MIME type
 * @param width       decoded width (0 if not an image we could decode)
 * @param height      decoded height
 * @param animated    true if the source is an animated sticker
 * @param originalExt file extension of the stored original media (e.g. "heic")
 * @param source      where it was caught: "notification" or "relay" (the Google Messages link)
 * @param digest      SHA-256 of the original bytes (32 hex characters), so the same
 *                    picture caught by both sources is kept once; empty for old items
 */
data class InboxItem(
    val id: String,
    val sender: String,
    val title: String,
    val appPackage: String,
    val timestampMs: Long,
    val mime: String,
    val width: Int,
    val height: Int,
    val animated: Boolean,
    val originalExt: String,
    val source: String = SOURCE_NOTIFICATION,
    val digest: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("sender", sender)
        put("title", title)
        put("appPackage", appPackage)
        put("timestampMs", timestampMs)
        put("mime", mime)
        put("width", width)
        put("height", height)
        put("animated", animated)
        put("originalExt", originalExt)
        put("source", source)
        put("digest", digest)
    }

    companion object {
        const val SOURCE_NOTIFICATION = "notification"
        const val SOURCE_RELAY = "relay"

        fun fromJson(o: JSONObject): InboxItem = InboxItem(
            id = o.getString("id"),
            sender = o.optString("sender"),
            title = o.optString("title"),
            appPackage = o.optString("appPackage"),
            timestampMs = o.optLong("timestampMs"),
            mime = o.optString("mime"),
            width = o.optInt("width"),
            height = o.optInt("height"),
            animated = o.optBoolean("animated"),
            originalExt = o.optString("originalExt", "bin"),
            source = o.optString("source", SOURCE_NOTIFICATION),
            digest = o.optString("digest", ""),
        )
    }
}
