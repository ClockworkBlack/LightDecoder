package com.lightdecoder.ui.chat

import android.content.Context
import com.lightdecoder.R
import com.lightdecoder.imessage.direct.IMessageExtra

/**
 * How what an iMessage carries beyond its text ([IMessageExtra]) shows in its bubble, as on an
 * iPhone: the effect it was sent with ("Sent with Slam"), invisible ink, a link's preview card and
 * an iMessage app's card. Pure apart from the app's strings. Tested in IMessageBubblesTest.
 */
internal object IMessageBubbles {

    /** What the block of hidden text is drawn with. */
    private const val INK = '▒'

    /** "Sent with Slam" for a message sent with an effect this app knows; null for none. */
    fun effectCaption(context: Context, extra: IMessageExtra): String? =
        IMessageExtra.effectName(extra.effect)?.let { context.getString(R.string.imessage_sent_with, context.getString(it)) }

    /** Whether [text] sent with [extra]'s effect is hidden until tapped (invisible ink), unless [revealed]. */
    fun hidden(extra: IMessageExtra, text: String, revealed: Boolean): Boolean =
        !revealed && text.isNotEmpty() && IMessageExtra.isInvisibleInk(extra.effect)

    /** What stands for a text sent with invisible ink outside its bubble: "Sent with Invisible Ink". */
    fun inkedLabel(context: Context): String =
        context.getString(R.string.imessage_sent_with, context.getString(R.string.imessage_effect_invisible_ink))

    /** [text] under invisible ink: every character but spaces and line breaks a block, so its shape shows but not its words. */
    fun inked(text: String): String = buildString {
        text.codePoints().forEach { c -> if (Character.isWhitespace(c)) appendCodePoint(c) else append(INK) }
    }

    /** A link preview card's lines: its title (else the site, else the address), summary and site (each empty when not shown). */
    data class LinkLines(val title: String, val summary: String, val site: String)

    fun linkLines(link: IMessageExtra.Link): LinkLines {
        val title = link.title.ifBlank { link.site.ifBlank { link.url } }
        return LinkLines(title, link.summary, link.site.takeIf { it != title }.orEmpty())
    }

    /**
     * An app card's lines, in the order they show: the picture's title and subtitle, the caption
     * (else the app's summary text), the subcaption and second subcaption, then the app's name (each
     * empty when not shown).
     */
    data class AppLines(
        val imageTitle: String,
        val imageSubtitle: String,
        val caption: String,
        val subcaption: String,
        val secondary: String,
        val appName: String,
    )

    fun appLines(app: IMessageExtra.App): AppLines =
        AppLines(app.imageTitle, app.imageSubtitle, app.cardCaption, app.subcaption, app.secondarySubcaption, app.shownName)
}
