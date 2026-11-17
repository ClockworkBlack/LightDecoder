package com.lightdecoder.discord

import android.content.Context
import androidx.core.content.edit
import kotlin.math.roundToInt

/**
 * How large Discord's page is drawn in the Discord tab, which is how much of
 * it fits: the page is laid out at a width (in CSS pixels, the page's own
 * unit) and the view is scaled to fill the screen, the way a browser's zoom
 * works. The page itself is not changed.
 *
 * A folding phone has two screens to fit: narrow when folded, wide when
 * open. Each remembers its own choice ([PageSize]); left on Automatic, a
 * narrow screen shows the page at its natural size, and a wide one gives it
 * at least [DESKTOP_MIN_CSS] pixels, where Discord lays out its servers,
 * channels and conversation side by side.
 */
object DiscordFit {

    /** From this width (dp) a screen counts as wide: an unfolded phone, a tablet. */
    const val WIDE_DP = 600

    /** The narrowest width at which Discord's side-by-side layout has room (its desktop app's minimum). */
    const val DESKTOP_MIN_CSS = 940

    /**
     * [percent] above 100 enlarges the text (the layout stays the screen's
     * width); below 100 the whole page is drawn smaller, so more of it fits.
     */
    enum class PageSize(val percent: Int) {
        AUTO(0), LARGEST(130), LARGER(115), NORMAL(100), SMALLER(90), SMALL(80), SMALLEST(70), TINY(60)
    }

    /** The page's layout width in CSS pixels, and its text size in percent. */
    data class Fit(val cssWidth: Int, val textZoom: Int)

    fun isWide(widthDp: Int) = widthDp >= WIDE_DP

    fun fit(widthDp: Int, size: PageSize): Fit = when {
        size == PageSize.AUTO -> Fit(if (isWide(widthDp)) maxOf(widthDp, DESKTOP_MIN_CSS) else widthDp, 100)
        size.percent > 100 -> Fit(widthDp, size.percent)
        else -> Fit((widthDp * 100f / size.percent).roundToInt(), 100)
    }

    // --- the choice, per kind of screen -------------------------------------------------

    private const val PREFS = "discord_view"

    private fun key(wide: Boolean) = if (wide) "page_size_wide" else "page_size_narrow"

    fun pageSize(context: Context, wide: Boolean): PageSize {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(wide), null)
        return PageSize.entries.firstOrNull { it.name == name } ?: PageSize.AUTO
    }

    fun setPageSize(context: Context, wide: Boolean, size: PageSize) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(key(wide), size.name) }
}
