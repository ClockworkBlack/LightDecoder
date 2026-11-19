package com.lightdecoder.imessage.direct

import androidx.annotation.StringRes
import com.lightdecoder.R
import org.json.JSONArray
import org.json.JSONObject
import uniffi.rust_bridge.IndexedMessagePart
import uniffi.rust_bridge.MessagePart
import uniffi.rust_bridge.TextEffect
import uniffi.rust_bridge.TextFlags
import uniffi.rust_bridge.TextFormat

/**
 * iOS 18's text effects: one word, or the whole message, animates ([key] is how it is stored with
 * the message; [nameRes] the name an iPhone shows). Drawn here by this app's own code
 * ([com.lightdecoder.ui.chat.TextEffectView]).
 */
enum class TextEffectKind(val key: String, @StringRes val nameRes: Int) {
    BIG("big", R.string.imessage_text_effect_big),
    SMALL("small", R.string.imessage_text_effect_small),
    SHAKE("shake", R.string.imessage_text_effect_shake),
    NOD("nod", R.string.imessage_text_effect_nod),
    EXPLODE("explode", R.string.imessage_text_effect_explode),
    RIPPLE("ripple", R.string.imessage_text_effect_ripple),
    BLOOM("bloom", R.string.imessage_text_effect_bloom),
    JITTER("jitter", R.string.imessage_text_effect_jitter),
    ;

    /** As the library names it. */
    val wire: TextEffect get() = TextEffect.valueOf(name)

    companion object {
        fun of(key: String?): TextEffectKind? = if (key.isNullOrEmpty()) null else values().firstOrNull { it.key == key }

        fun of(effect: TextEffect): TextEffectKind = valueOf(effect.name)
    }
}

/**
 * One stretch of a message part's text, [start] (inclusive) to [end] (exclusive), in UTF-16
 * characters, with iOS 18's formatting: bold, italic, underline, strikethrough, or one text
 * [effect]. As on the wire (one `MessagePart.Text` carries flags or one effect, never both), a run
 * with an effect has no flags: where both are asked for, the effect wins.
 */
data class TextRun(
    val start: Int,
    val end: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val effect: TextEffectKind? = null,
) {
    val style: TextStyle get() = TextStyle(bold, italic, underline, strikethrough, effect)

    fun toJson(): JSONObject = JSONObject().put("s", start).put("e", end).apply {
        if (bold) put("b", true)
        if (italic) put("i", true)
        if (underline) put("u", true)
        if (strikethrough) put("x", true)
        if (effect != null) put("fx", effect.key)
    }

    companion object {
        fun fromJson(o: JSONObject): TextRun? {
            val start = o.optInt("s", -1)
            val end = o.optInt("e", -1)
            if (start < 0 || end <= start) return null
            return TextStyle(o.optBoolean("b"), o.optBoolean("i"), o.optBoolean("u"), o.optBoolean("x"), TextEffectKind.of(o.optString("fx")))
                .run(start, end)
        }
    }
}

/** What one character looks like: [TextRun]'s formatting without its place. */
data class TextStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val effect: TextEffectKind? = null,
) {
    val plain: Boolean get() = !bold && !italic && !underline && !strikethrough && effect == null

    /** As one part can carry it: with an effect, the effect alone. */
    fun sendable(): TextStyle = if (effect != null) TextStyle(effect = effect) else this

    fun run(start: Int, end: Int) = TextRun(start, end, bold, italic, underline, strikethrough, effect)

    companion object {
        val PLAIN = TextStyle()
    }
}

/**
 * How formatted text goes to and comes from Apple: a message part's text as a list of [TextRun]s
 * (only the formatted stretches), and the library's `MessagePart.Text` runs it is sent as (one
 * per stretch, plain text between). A stretch never splits a character pair (an emoji) or a
 * character from what joins it (a skin tone, a variation selector, a zero-width joiner).
 *
 * Pure. Tested in TextRunsTest.
 */
object TextRuns {

    private val NO_FLAGS get() = TextFormat.Flags(TextFlags(false, false, false, false))

    /** The library's format for [style] (its effect, else its flags). */
    fun format(style: TextStyle): TextFormat =
        style.effect?.let { TextFormat.Effect(it.wire) }
            ?: TextFormat.Flags(TextFlags(style.bold, style.italic, style.underline, style.strikethrough))

    /** The style of a part sent as [format]. */
    fun styleOf(format: TextFormat): TextStyle = when (format) {
        is TextFormat.Effect -> TextStyle(effect = TextEffectKind.of(format.v1))
        is TextFormat.Flags -> TextStyle(format.v1.bold, format.v1.italic, format.v1.underline, format.v1.strikethrough)
    }

    /** Each character's style in [text] with [runs] laid over it in order (flags add up, a later effect wins; an effect clears flags). */
    fun styles(text: String, runs: List<TextRun>): Array<TextStyle> {
        val out = Array(text.length) { TextStyle.PLAIN }
        for (r in runs) {
            val (s, e) = snap(text, r.start, r.end) ?: continue
            for (i in s until e) {
                val c = out[i]
                out[i] = TextStyle(
                    c.bold || r.bold, c.italic || r.italic, c.underline || r.underline, c.strikethrough || r.strikethrough,
                    r.effect ?: c.effect,
                )
            }
        }
        for (i in out.indices) out[i] = out[i].sendable()
        return out
    }

    /** The runs of [styles]: each stretch of one style, plain ones left out. */
    fun runsOf(styles: Array<TextStyle>): List<TextRun> {
        val out = ArrayList<TextRun>()
        var i = 0
        while (i < styles.size) {
            var j = i + 1
            while (j < styles.size && styles[j] == styles[i]) j++
            if (!styles[i].plain) out += styles[i].run(i, j)
            i = j
        }
        return out
    }

    /**
     * [runs] over [text] as they are kept and sent: within the text, never splitting a character,
     * overlaps resolved ([styles]), touching runs of one style merged, plain ones dropped.
     */
    fun normalize(text: String, runs: List<TextRun>): List<TextRun> =
        if (runs.isEmpty() || text.isEmpty()) emptyList() else runsOf(styles(text, runs))

    /**
     * The library's parts for a message part reading [text] with [runs]: one `Text` part per
     * stretch of one style (plain between), each numbered as the one message part they make up.
     */
    fun toParts(text: String, runs: List<TextRun>): List<MessagePart> {
        if (text.isEmpty()) return emptyList()
        val norm = normalize(text, runs)
        if (norm.isEmpty()) return listOf(MessagePart.Text(text, NO_FLAGS))
        val out = ArrayList<MessagePart>()
        var at = 0
        for (r in norm) {
            if (r.start > at) out += MessagePart.Text(text.substring(at, r.start), NO_FLAGS)
            out += MessagePart.Text(text.substring(r.start, r.end), format(r.style))
            at = r.end
        }
        if (at < text.length) out += MessagePart.Text(text.substring(at), NO_FLAGS)
        return out
    }

    /**
     * The formatting of a message's parts as they came, by part index ([DirectArchive.partIndexes]),
     * over each part's text as [DirectArchive.partTexts] joins it (a mention's name is plain text).
     * Parts with no formatting are left out.
     */
    fun fromParts(parts: List<IndexedMessagePart>): Map<Int, List<TextRun>> {
        val texts = LinkedHashMap<Int, StringBuilder>()
        val runs = LinkedHashMap<Int, ArrayList<TextRun>>()
        for ((idx, p) in DirectArchive.partIndexes(parts).zip(parts)) {
            val text = texts.getOrPut(idx) { StringBuilder() }
            when (val part = p.part) {
                is MessagePart.Text -> {
                    val style = styleOf(part.v2).sendable()
                    if (!style.plain && part.v1.isNotEmpty()) runs.getOrPut(idx) { ArrayList() } += style.run(text.length, text.length + part.v1.length)
                    text.append(part.v1)
                }
                is MessagePart.Mention -> text.append(part.v2)
                is MessagePart.Attachment, is MessagePart.Object -> Unit
            }
        }
        return runs.mapValues { (idx, r) -> normalize(texts[idx].toString(), r) }.filterValues { it.isNotEmpty() }
    }

    /** The formatting of one part's new text in an edit (all its runs are that one part). */
    fun fromEdit(parts: List<MessagePart>): List<TextRun> =
        fromParts(parts.map { IndexedMessagePart(it, 0UL, null) })[0].orEmpty()

    /** [text] with its outer white space trimmed (as a message is sent), [runs] moved along with it. */
    fun trimmed(text: String, runs: List<TextRun>): Pair<String, List<TextRun>> {
        val start = text.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return "" to emptyList()
        val end = text.indexOfLast { !it.isWhitespace() } + 1
        val body = text.substring(start, end)
        val moved = runs.mapNotNull { r ->
            val s = (r.start - start).coerceIn(0, body.length)
            val e = (r.end - start).coerceIn(0, body.length)
            if (e > s) r.copy(start = s, end = e) else null
        }
        return body to normalize(body, moved)
    }

    /** What a formatting action does to the selected text: a flag toggled, or an effect set (null: taken off). */
    sealed class Change {
        enum class Flag { BOLD, ITALIC, UNDERLINE, STRIKETHROUGH }

        /** Turns [flag] on over the selection, or off when all of it has it already. Takes any effect off it. */
        data class Toggle(val flag: Flag) : Change()

        /** Gives the selection [effect] (its flags go: one or the other), or takes the effect off (null). */
        data class Effect(val effect: TextEffectKind?) : Change()
    }

    /** [runs] over [text] after [change] to the stretch [start] to [end] (the selection). */
    fun apply(text: String, runs: List<TextRun>, start: Int, end: Int, change: Change): List<TextRun> {
        val (s, e) = snap(text, minOf(start, end), maxOf(start, end)) ?: return normalize(text, runs)
        val styles = styles(text, runs)
        when (change) {
            is Change.Toggle -> {
                val on = !(s until e).all { has(styles[it], change.flag) }
                for (i in s until e) styles[i] = with(styles[i], change.flag, on).copy(effect = null)
            }
            is Change.Effect -> for (i in s until e) styles[i] = change.effect?.let { TextStyle(effect = it) } ?: styles[i].copy(effect = null)
        }
        return runsOf(styles)
    }

    /** Whether all of [start] to [end] of [text] with [runs] has [flag]. */
    fun allHave(text: String, runs: List<TextRun>, start: Int, end: Int, flag: Change.Flag): Boolean {
        val (s, e) = snap(text, start, end) ?: return false
        val styles = styles(text, runs)
        return (s until e).all { has(styles[it], flag) }
    }

    private fun has(style: TextStyle, flag: Change.Flag) = when (flag) {
        Change.Flag.BOLD -> style.bold
        Change.Flag.ITALIC -> style.italic
        Change.Flag.UNDERLINE -> style.underline
        Change.Flag.STRIKETHROUGH -> style.strikethrough
    }

    private fun with(style: TextStyle, flag: Change.Flag, on: Boolean) = when (flag) {
        Change.Flag.BOLD -> style.copy(bold = on)
        Change.Flag.ITALIC -> style.copy(italic = on)
        Change.Flag.UNDERLINE -> style.copy(underline = on)
        Change.Flag.STRIKETHROUGH -> style.copy(strikethrough = on)
    }

    /**
     * [start] to [end] within [text], widened so it splits no character: not a pair of surrogates,
     * nor a character from what joins it to the one before. Null when nothing is left.
     */
    fun snap(text: String, start: Int, end: Int): Pair<Int, Int>? {
        var s = start.coerceIn(0, text.length)
        var e = end.coerceIn(0, text.length)
        while (s > 0 && joinsPrevious(text, s)) s--
        while (e < text.length && e > 0 && joinsPrevious(text, e)) e++
        return if (e > s) s to e else null
    }

    /** Whether the character at [i] belongs with the one before it. */
    private fun joinsPrevious(text: String, i: Int): Boolean {
        if (i <= 0 || i >= text.length) return false
        val c = text[i]
        val prev = text[i - 1]
        if (Character.isLowSurrogate(c) && Character.isHighSurrogate(prev)) return true
        if (c == ZWJ || prev == ZWJ) return true
        if (c in '︀'..'️' || c == '⃣') return true
        val cp = text.codePointAt(i)
        if (cp in 0x1F3FB..0x1F3FF) return true
        // Tag characters (subdivision flags) and the regional indicator after another one (a flag).
        if (cp in 0xE0020..0xE007F) return true
        if (cp in REGIONAL && i >= 2 && text.codePointBefore(i) in REGIONAL && regionalBefore(text, i) % 2 == 1) return true
        val type = Character.getType(c)
        return type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt()
    }

    /** How many regional indicators come right before [i]. */
    private fun regionalBefore(text: String, i: Int): Int {
        var n = 0
        var at = i
        while (at > 0) {
            val cp = text.codePointBefore(at)
            if (cp !in REGIONAL) break
            n++
            at -= Character.charCount(cp)
        }
        return n
    }

    private const val ZWJ = '‍'
    private val REGIONAL = 0x1F1E6..0x1F1FF

    /** As stored with a message ([IMessageExtra.runs]): each part's runs by its index. */
    fun toJson(byPart: Map<Int, List<TextRun>>): JSONObject = JSONObject().apply {
        byPart.toSortedMap().forEach { (idx, runs) -> if (runs.isNotEmpty()) put(idx.toString(), toJsonArray(runs)) }
    }

    fun toJsonArray(runs: List<TextRun>): JSONArray = JSONArray().apply { runs.forEach { put(it.toJson()) } }

    fun fromJsonArray(a: JSONArray?): List<TextRun> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(TextRun::fromJson) }

    fun fromJson(o: JSONObject?): Map<Int, List<TextRun>> {
        if (o == null) return emptyMap()
        return o.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to fromJsonArray(o.optJSONArray(k)) } }
            .filter { it.second.isNotEmpty() }.toMap()
    }
}
