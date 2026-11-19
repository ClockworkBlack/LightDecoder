package com.lightdecoder.imessage.direct

import android.net.Uri
import androidx.annotation.StringRes
import com.lightdecoder.R
import kotlin.math.roundToInt

/** A colour as PosterKit keeps one: red, green, blue and alpha, each 0 to 1. */
data class Rgba(val red: Double, val green: Double, val blue: Double, val alpha: Double = 1.0) {

    /** As an Android colour int. */
    fun argb(): Int {
        fun c(v: Double) = (v.coerceIn(0.0, 1.0) * 255).roundToInt()
        return (c(alpha) shl 24) or (c(red) shl 16) or (c(green) shl 8) or c(blue)
    }

    companion object {
        /** An Android colour int as PosterKit's. */
        fun of(argb: Int): Rgba = Rgba(
            ((argb shr 16) and 0xFF) / 255.0,
            ((argb shr 8) and 0xFF) / 255.0,
            (argb and 0xFF) / 255.0,
            ((argb ushr 24) and 0xFF) / 255.0,
        )
    }
}

/**
 * A chat background that is drawn rather than a picture, as iOS 26 has them: a gradient of a few
 * colours (PosterKit's `TranscriptGradient`, which an iPhone renders itself from the colours), or a
 * "dynamic" one named by an identifier (`TranscriptDynamic`; "aurora_1" is iOS's Aurora). Here both
 * are drawn by the app's own code ([BackgroundArt]).
 *
 * Kept and carried between the processes as [type] and [data]: the colours as PosterKit's user
 * info holds them ("r/g/b/a//r/g/b/a"), or the identifier.
 */
sealed class BackgroundStyle {

    data class Gradient(val colors: List<Rgba>) : BackgroundStyle()

    data class Dynamic(val identifier: String) : BackgroundStyle() {
        /** Whether this app knows how to draw it (an aurora); any other is drawn as a plain gradient. */
        val isKnown: Boolean get() = isAurora(identifier)
    }

    val type: String get() = when (this) {
        is Gradient -> TYPE_GRADIENT
        is Dynamic -> TYPE_DYNAMIC
    }

    val data: String get() = when (this) {
        is Gradient -> encodeColors(colors)
        is Dynamic -> identifier
    }

    companion object {
        const val TYPE_PHOTO = "photo"
        const val TYPE_GRADIENT = "gradient"
        const val TYPE_DYNAMIC = "dynamic"

        /** iOS's Aurora, the one dynamic background known by name. */
        const val AURORA = "aurora_1"

        /** The style [type] with [data] says, or null for a photo or none. */
        fun of(type: String?, data: String?): BackgroundStyle? = when (type) {
            TYPE_GRADIENT -> Gradient(decodeColors(data.orEmpty()))
            TYPE_DYNAMIC -> Dynamic(data.orEmpty())
            else -> null
        }

        fun isAurora(identifier: String): Boolean = identifier.startsWith("aurora")

        /** Colours as PosterKit's gradient user info has them: "r/g/b/a" each, joined by "//". */
        fun encodeColors(colors: List<Rgba>): String =
            colors.joinToString("//") { c -> listOf(c.red, c.green, c.blue, c.alpha).joinToString("/") { format(it) } }

        /**
         * [custom] as colours, skipping any that does not read. Components above 1 are taken as
         * 0-255 (Apple's are 0-1, but nothing here depends on it); a missing alpha is opaque.
         */
        fun decodeColors(custom: String): List<Rgba> = custom.split("//").mapNotNull { part ->
            val v = part.split("/").map { it.trim().toDoubleOrNull() ?: return@mapNotNull null }
            if (v.size < 3) return@mapNotNull null
            val scale = if (v.take(3).any { it > 1.0 }) 255.0 else 1.0
            Rgba(v[0] / scale, v[1] / scale, v[2] / scale, (v.getOrNull(3)?.let { if (it > 1.0) it / 255.0 else it } ?: 1.0))
        }

        /** As Rust prints a float: "1" for a whole number, else the shortest form that reads back the same. */
        private fun format(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
    }
}

/** What the user picked for a chat's background ([DirectLink.setBackground]). */
sealed class BackgroundChoice {
    /** A photo of theirs. */
    data class Photo(val uri: Uri) : BackgroundChoice()

    /** One of the app's own designs ([BackgroundThemes]), sent as a photo. */
    data class Theme(val id: String) : BackgroundChoice()

    /** A gradient or a dynamic one, sent as such: an iPhone draws it itself. */
    data class Drawn(val style: BackgroundStyle) : BackgroundChoice()

    /** None. */
    object None : BackgroundChoice()
}

/** The gradients offered, iPhone-style: a few colours each, sent as [BackgroundStyle.Gradient]. */
object BackgroundPalettes {

    data class Palette(val id: String, @StringRes val name: Int, val colors: List<Int>) {
        val style: BackgroundStyle.Gradient get() = BackgroundStyle.Gradient(colors.map(Rgba::of))
    }

    val ALL: List<Palette> = listOf(
        Palette("sunset", R.string.imessage_background_palette_sunset, listOf(0xFFFFB36B.toInt(), 0xFFFF6F7D.toInt(), 0xFF8E4FC9.toInt())),
        Palette("ocean", R.string.imessage_background_palette_ocean, listOf(0xFF6FE3E1.toInt(), 0xFF2D8BD8.toInt(), 0xFF173E8C.toInt())),
        Palette("forest", R.string.imessage_background_palette_forest, listOf(0xFFB8E08A.toInt(), 0xFF4E9F5B.toInt(), 0xFF1F4D36.toInt())),
        Palette("lavender", R.string.imessage_background_palette_lavender, listOf(0xFFEFE1FF.toInt(), 0xFFC6A8F2.toInt(), 0xFF9C83E0.toInt())),
        Palette("peach", R.string.imessage_background_palette_peach, listOf(0xFFFFE8D6.toInt(), 0xFFFFC2A1.toInt(), 0xFFF4978E.toInt())),
        Palette("midnight", R.string.imessage_background_palette_midnight, listOf(0xFF2B3A67.toInt(), 0xFF141B3A.toInt(), 0xFF05070F.toInt())),
        Palette("mint", R.string.imessage_background_palette_mint, listOf(0xFFE4FFF3.toInt(), 0xFFA6EBCF.toInt(), 0xFF5FC9A8.toInt())),
        Palette("rose", R.string.imessage_background_palette_rose, listOf(0xFFFFE3EC.toInt(), 0xFFF7A1C0.toInt(), 0xFFD9577F.toInt())),
        Palette("sky", R.string.imessage_background_palette_sky, listOf(0xFFE8F6FF.toInt(), 0xFFA9DBFF.toInt(), 0xFF5AA9F0.toInt())),
        Palette("ember", R.string.imessage_background_palette_ember, listOf(0xFFFFD36E.toInt(), 0xFFF0703A.toInt(), 0xFFA3202E.toInt(), 0xFF3D0B1E.toInt())),
        Palette("grape", R.string.imessage_background_palette_grape, listOf(0xFFD9A7F5.toInt(), 0xFF8A3FC4.toInt(), 0xFF3E1466.toInt())),
        Palette("slate", R.string.imessage_background_palette_slate, listOf(0xFFCBD3DC.toInt(), 0xFF7D8A99.toInt(), 0xFF3A4350.toInt())),
    )
}

/** The dynamic backgrounds offered: iOS's Aurora, drawn here by [AuroraDrawable]. */
object BackgroundDynamics {
    data class Dynamic(val identifier: String, @StringRes val name: Int) {
        val style: BackgroundStyle.Dynamic get() = BackgroundStyle.Dynamic(identifier)
    }

    val ALL: List<Dynamic> = listOf(Dynamic(BackgroundStyle.AURORA, R.string.imessage_background_dynamic_aurora))
}
