package com.lightdecoder.imessage.madrid

import com.bluebubbles.messaging.MadridMessage
import com.lightdecoder.imessage.direct.IMessageExtra
import java.util.Base64
import java.util.UUID

/**
 * Between an extension app's [MadridMessage] and the iMessage app balloon LightDecoder stores with
 * a message ([IMessageExtra.App]). Only the balloon's own fields cross: never a contact's name, a
 * text message or a picture. The app's [MadridMessage.url] and [MadridMessage.session] are its own
 * state and are kept exactly as given.
 *
 * Pure. Tested in MadridMessagesTest.
 */
object MadridMessages {

    /** The longest summary or caption taken from an extension app. */
    const val MAX_TEXT = 200

    /**
     * The longest address ([MadridMessage.url]) taken from an extension app, in characters. It is
     * opaque, so one longer is refused, never cut; it crosses to the ":imessage" process in a
     * Bundle, which must stay well under Binder's limit. OpenPigeon's are a few kilobytes.
     */
    const val MAX_URL = 64 * 1024

    /** The largest picture taken from an extension app. */
    const val MAX_IMAGE_BYTES = 2 * 1024 * 1024

    /**
     * [app] (stored with message [guid], its GUID without the archive's prefix) as the extension
     * app reads it. Its picture is not sent along ([MadridMessage.imageBase64] null, as OpenBubbles
     * does): the extension does not need it.
     */
    fun toMadridMessage(guid: String, app: IMessageExtra.App): MadridMessage = MadridMessage().apply {
        messageGuid = guid
        ldText = app.ldText.ifEmpty { null }
        url = app.url
        session = app.session.ifEmpty { null }
        imageBase64 = null
        imageSubtitle = app.imageSubtitle.ifEmpty { null }
        imageTitle = app.imageTitle.ifEmpty { null }
        caption = app.caption.ifEmpty { null }
        secondaryCaption = app.secondarySubcaption.ifEmpty { null }
        tertiaryCaption = app.tertiarySubcaption.ifEmpty { null }
        subcaption = app.subcaption.ifEmpty { null }
        isLive = app.isLive
    }

    /**
     * The balloon of [m], from extension app [ext]: named as [ext] names its iMessage app, in
     * session [session] (for a move, the session of the card it was opened from, whatever [m]
     * says), [root] the archive id of the session's first card ("" for a new card: it is its own).
     * Summary and captions are cut to [MAX_TEXT].
     */
    fun appOf(m: MadridMessage, ext: MadridApps.MadridApp, session: String, root: String = ""): IMessageExtra.App =
        IMessageExtra.App(
            name = ext.name,
            appId = ext.appId,
            bundleId = ext.bundleId,
            url = m.url.orEmpty(),
            session = session,
            ldText = m.ldText.short(),
            isLive = m.isLive,
            imageTitle = m.imageTitle.short(),
            imageSubtitle = m.imageSubtitle.short(),
            caption = m.caption.short(),
            subcaption = m.subcaption.short(),
            secondarySubcaption = m.secondaryCaption.short(),
            tertiarySubcaption = m.tertiaryCaption.short(),
            root = root,
        )

    /**
     * Why a new card from an extension app cannot be sent, or null when it can: it needs an
     * address ([MadridMessage.url]) and a session that is a UUID (Apple's session id; the library
     * takes nothing else).
     */
    fun problemWithCard(m: MadridMessage): String? = problemWithMove(m) ?: if (!isSession(m.session)) "no valid session" else null

    /**
     * Why a move from an extension app cannot be sent, or null when it can: it needs an address
     * ([MadridMessage.url]) of at most [MAX_URL] characters. (Its session is the handle's.)
     */
    fun problemWithMove(m: MadridMessage): String? = when {
        m.url.isNullOrBlank() -> "no url"
        m.url.length > MAX_URL -> "url too long"
        else -> null
    }

    /** Whether [session] is a UUID, as Apple's session ids are. */
    fun isSession(session: String?): Boolean {
        if (session.isNullOrBlank()) return false
        return try {
            UUID.fromString(session)
            session.length == 36
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * The JPEG an extension app sent as base64 ([MadridMessage.imageBase64]); null when there is
     * none, or it is not a JPEG, or it is over [MAX_IMAGE_BYTES].
     */
    fun jpegOf(base64: String?): ByteArray? {
        if (base64.isNullOrBlank()) return null
        // A JPEG of MAX_IMAGE_BYTES is about 4/3 of that in base64.
        if (base64.length > MAX_IMAGE_BYTES / 3 * 4 + 1024) return null
        val bytes = try {
            Base64.getMimeDecoder().decode(base64)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (bytes.size < 3 || bytes.size > MAX_IMAGE_BYTES) return null
        if (bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        return bytes
    }

    /** At most [MAX_TEXT] characters, never half an emoji. */
    private fun String?.short(): String {
        val s = orEmpty()
        if (s.length <= MAX_TEXT) return s
        return s.take(if (Character.isHighSurrogate(s[MAX_TEXT - 1])) MAX_TEXT - 1 else MAX_TEXT)
    }
}
