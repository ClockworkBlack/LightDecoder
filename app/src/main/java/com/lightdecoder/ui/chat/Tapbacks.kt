package com.lightdecoder.ui.chat

import com.lightdecoder.archive.MessageRow
import com.lightdecoder.relay.ReactionDto

/**
 * An iPhone's reaction ("tapback") to a message, as it reaches a phone that
 * is not an iPhone: a text message of its own, such as "Loved an image" or
 * Liked “see you at 6”. This finds those texts in a thread, puts their emoji
 * on the message they react to, as any other reaction is shown, and leaves
 * the text out. A text whose message is not among those loaded (or that
 * matches none) stays as it is. Nothing stored is changed. Pure, so it can
 * be tested. English only, which is what an iPhone set to English sends.
 */
object Tapbacks {

    /** What a tapback text says: an emoji added or taken away, and what it was on. */
    data class Tapback(val emoji: String, val removed: Boolean, val target: Target)

    sealed interface Target {
        /** A message by its text (as quoted: possibly cut short, ending in "…"). */
        data class Text(val quoted: String) : Target

        /** The latest message with media of this kind. */
        data class Media(val kind: MediaKind) : Target
    }

    enum class MediaKind { PICTURE, VIDEO, AUDIO, ANY }

    private const val LOVE = "❤️"
    private const val LIKE = "👍"
    private const val DISLIKE = "👎"
    private const val LAUGH = "😂"
    private const val EMPHASIZE = "‼️"
    private const val QUESTION = "❓"

    private val added = listOf(
        "Loved " to LOVE,
        "Liked " to LIKE,
        "Disliked " to DISLIKE,
        "Laughed at " to LAUGH,
        "Emphasized " to EMPHASIZE,
        "Emphasised " to EMPHASIZE,
        "Questioned " to QUESTION,
    )

    private val removedNames = listOf(
        "a heart" to LOVE,
        "a like" to LIKE,
        "a dislike" to DISLIKE,
        "a laugh" to LAUGH,
        "an exclamation" to EMPHASIZE,
        "an exclamation point" to EMPHASIZE,
        "an exclamation mark" to EMPHASIZE,
        "a question mark" to QUESTION,
    )

    private val media = listOf(
        "an image" to MediaKind.PICTURE,
        "a photo" to MediaKind.PICTURE,
        "a picture" to MediaKind.PICTURE,
        "a sticker" to MediaKind.PICTURE,
        "a GIF" to MediaKind.PICTURE,
        "an animated image" to MediaKind.PICTURE,
        "a video" to MediaKind.VIDEO,
        "a movie" to MediaKind.VIDEO,
        "an audio message" to MediaKind.AUDIO,
        "an audio" to MediaKind.AUDIO,
        "an attachment" to MediaKind.ANY,
        "a file" to MediaKind.ANY,
    )

    private val reactedWith = Regex("^Reacted (.{1,16}?) to (.+)$", RegexOption.DOT_MATCHES_ALL)
    private val removedEmoji = Regex("^Removed (.{1,16}?) from (.+)$", RegexOption.DOT_MATCHES_ALL)

    /** What [text] says as a tapback, or null when it is an ordinary message. */
    fun parse(text: String): Tapback? {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_TEXT) return null
        for ((verb, emoji) in added) {
            if (t.startsWith(verb)) return target(t.substring(verb.length))?.let { Tapback(emoji, false, it) }
        }
        reactedWith.find(t)?.let { m ->
            val emoji = m.groupValues[1].trim()
            if (looksLikeEmoji(emoji)) return target(m.groupValues[2])?.let { Tapback(emoji, false, it) }
        }
        if (t.startsWith("Removed ")) {
            val rest = t.substring("Removed ".length)
            for ((name, emoji) in removedNames.sortedByDescending { it.first.length }) {
                if (rest.startsWith("$name from ")) return target(rest.substring(name.length + " from ".length))?.let { Tapback(emoji, true, it) }
            }
            removedEmoji.find(t)?.let { m ->
                val emoji = m.groupValues[1].trim()
                if (looksLikeEmoji(emoji)) return target(m.groupValues[2])?.let { Tapback(emoji, true, it) }
            }
        }
        return null
    }

    /** The quoted text in curly or straight quotes, or one of the media words, and nothing after it. */
    private fun target(rest: String): Target? {
        val r = rest.trim()
        for ((word, kind) in media) if (r == word) return Target.Media(kind)
        val open = r.firstOrNull() ?: return null
        val close = when (open) {
            '“' -> '”'
            '"' -> '"'
            '‘' -> '’'
            '\'' -> '\''
            else -> return null
        }
        if (r.length < 3 || r.last() != close) return null
        return Target.Text(r.substring(1, r.length - 1))
    }

    /** An emoji, not a word: "Reacted happily to …" is someone's message, not a tapback. */
    private fun looksLikeEmoji(s: String): Boolean =
        s.isNotEmpty() && s.none { it.isLetterOrDigit() } && s.codePoints().anyMatch { it > 0x2000 }

    /**
     * [messages] (oldest first) with every tapback text whose message is
     * among them turned into a reaction on it and left out. The same list
     * when there are none.
     */
    fun apply(messages: List<MessageRow>): List<MessageRow> {
        if (messages.none { isCandidate(it) }) return messages
        // Per target message: reactions as they stand (starting from its own), changed by each tapback in turn.
        val reactions = HashMap<String, MutableList<ReactionDto>>()
        // Which emoji each person has put on a message by tapback: an iPhone allows one, so a new one replaces it.
        val byTapback = HashMap<Pair<String, String>, String>()
        val hidden = HashSet<String>()
        for ((index, row) in messages.withIndex()) {
            if (!isCandidate(row)) continue
            val tapback = parse(row.text) ?: continue
            val target = findTarget(messages, index, tapback.target) ?: continue
            val who = row.senderId.ifEmpty { row.senderName }.ifEmpty { "them" }
            val list = reactions.getOrPut(target.id) { target.reactions.toMutableList() }
            val key = target.id to who
            fun take(emoji: String) {
                val i = list.indexOfFirst { it.emoji == emoji && !it.fromMe && who in it.participantIds }
                if (i < 0) return
                val left = list[i].participantIds - who
                if (left.isEmpty()) list.removeAt(i) else list[i] = list[i].copy(participantIds = left, madeAtMs = list[i].madeAtMs - who)
            }
            if (tapback.removed) {
                take(tapback.emoji)
                if (byTapback[key] == tapback.emoji) byTapback.remove(key)
            } else {
                byTapback[key]?.let(::take)
                byTapback[key] = tapback.emoji
                val i = list.indexOfFirst { it.emoji == tapback.emoji && !it.fromMe }
                // The tapback text's own time is when the reaction was made.
                if (i < 0) {
                    list += ReactionDto(tapback.emoji, listOf(who), fromMe = false, madeAtMs = mapOf(who to row.timestampMs))
                } else if (who !in list[i].participantIds) {
                    list[i] = list[i].copy(
                        participantIds = list[i].participantIds + who,
                        madeAtMs = list[i].madeAtMs + (who to row.timestampMs),
                    )
                }
            }
            hidden += row.id
        }
        if (hidden.isEmpty()) return messages
        return messages.mapNotNull { row ->
            when {
                row.id in hidden -> null
                row.id in reactions -> row.copy(reactions = reactions.getValue(row.id))
                else -> row
            }
        }
    }

    /** A text that could be a tapback: received, text only. */
    private fun isCandidate(row: MessageRow) =
        !row.isOutgoing && row.media.isEmpty() && row.text.isNotBlank() && row.text.length <= MAX_TEXT

    /** The message a tapback at [index] is about: the latest before it that fits. */
    private fun findTarget(messages: List<MessageRow>, index: Int, target: Target): MessageRow? {
        for (i in index - 1 downTo 0) {
            val row = messages[i]
            if (isCandidate(row) && parse(row.text) != null) continue
            if (fits(row, target)) return row
        }
        return null
    }

    private fun fits(row: MessageRow, target: Target): Boolean = when (target) {
        is Target.Media -> row.media.any { m ->
            val mime = m.mime.lowercase()
            when (target.kind) {
                MediaKind.PICTURE -> mime.startsWith("image/") || mime.isEmpty()
                MediaKind.VIDEO -> mime.startsWith("video/")
                MediaKind.AUDIO -> mime.startsWith("audio/")
                MediaKind.ANY -> true
            }
        }
        is Target.Text -> {
            val said = normalised(row.text)
            val quoted = normalised(target.quoted)
            when {
                quoted.isEmpty() || said.isEmpty() -> false
                said == quoted -> true
                // Cut short by the iPhone: the start of the message, then "…" or "...".
                quoted.endsWith("…") -> said.startsWith(quoted.dropLast(1).trimEnd())
                quoted.endsWith("...") -> said.startsWith(quoted.dropLast(3).trimEnd())
                else -> false
            }
        }
    }

    /** Spacing and curly quotes as they come out the same on both phones. */
    private fun normalised(s: String) = s.trim()
        .replace(Regex("\\s+"), " ")
        .replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')

    /** Longer than any tapback text is (a quote of a long message is cut short). */
    private const val MAX_TEXT = 600
}
