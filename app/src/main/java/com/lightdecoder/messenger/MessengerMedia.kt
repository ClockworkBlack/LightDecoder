package com.lightdecoder.messenger

/**
 * What a Messenger notification line stands for when it is not the message
 * itself. Messenger's notification carries only words for a photo or video
 * ("Sent a photo."), never the file, so the inbox shows those words and
 * offers the way to the real thing: the conversation in the Messenger app,
 * or Facebook's page.
 */
object MessengerMedia {

    enum class Kind { PHOTO, VIDEO, GIF, STICKER, VOICE, ATTACHMENT }

    /**
     * The kind of thing a line like "Sent a photo." or "Sent 3 photos" says
     * was sent, or null for an ordinary message. English only: Messenger
     * writes these in the phone's language, and other languages are shown
     * as plain text.
     */
    fun kindOf(text: String): Kind? {
        val line = text.trim().trimEnd('.', '!').lowercase()
        val match = SENT.matchEntire(line) ?: return null
        return when (match.groupValues[1]) {
            "photo", "photos", "picture", "pictures", "image", "images" -> Kind.PHOTO
            "video", "videos" -> Kind.VIDEO
            "gif", "gifs" -> Kind.GIF
            "sticker", "stickers" -> Kind.STICKER
            "voice message", "voice messages", "voice clip", "voice clips", "audio", "audio clip" -> Kind.VOICE
            "attachment", "attachments", "file", "files" -> Kind.ATTACHMENT
            else -> null
        }
    }

    /** "sent a photo", "sent 3 photos", "sent an attachment", optionally after "You " or a name. */
    private val SENT = Regex(
        "^(?:.*\\s)?sent (?:a |an |\\d+ )?" +
            "(photos?|pictures?|images?|videos?|gifs?|stickers?|voice messages?|voice clips?|audio(?: clip)?|attachments?|files?)$"
    )
}
