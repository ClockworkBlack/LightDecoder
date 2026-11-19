package com.lightdecoder.imessage.direct

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.rust_bridge.AttachmentType
import uniffi.rust_bridge.IndexedMessagePart
import uniffi.rust_bridge.MessageAttachment
import uniffi.rust_bridge.MessagePart
import uniffi.rust_bridge.TextEffect
import uniffi.rust_bridge.TextFlags
import uniffi.rust_bridge.TextFormat

/** iOS 18's formatting and text effects to and from the library's message parts ([TextRuns]). */
@RunWith(AndroidJUnit4::class)
class TextRunsTest {

    private val plain = TextFormat.Flags(TextFlags(false, false, false, false))
    private val bold = TextFormat.Flags(TextFlags(true, false, false, false))
    private fun flags(b: Boolean = false, i: Boolean = false, u: Boolean = false, s: Boolean = false) = TextFormat.Flags(TextFlags(b, i, u, s))
    private fun fx(e: TextEffect) = TextFormat.Effect(e)
    private fun part(p: MessagePart, idx: Int? = null) = IndexedMessagePart(p, idx?.toULong(), null)
    private fun picture() = MessagePart.Attachment(MessageAttachment(AttachmentType.Inline(byteArrayOf(1)), 0UL, "public.jpeg", "image/jpeg", "a.jpg", false))

    @Test
    fun aFormattedWordSplitsTheTextIntoRunsOfOneStyle() {
        val parts = TextRuns.toParts("Hello bold world", listOf(TextRun(6, 10, bold = true)))
        assertEquals(
            listOf(MessagePart.Text("Hello ", plain), MessagePart.Text("bold", bold), MessagePart.Text(" world", plain)),
            parts,
        )
        // All of it formatted: one part; none: one plain part; no text: nothing.
        assertEquals(listOf(MessagePart.Text("Hi", fx(TextEffect.BIG))), TextRuns.toParts("Hi", listOf(TextRun(0, 2, effect = TextEffectKind.BIG))))
        assertEquals(listOf(MessagePart.Text("Hi", plain)), TextRuns.toParts("Hi", emptyList()))
        assertEquals(emptyList<MessagePart>(), TextRuns.toParts("", listOf(TextRun(0, 2, bold = true))))
    }

    @Test
    fun overlapsAddUpTouchingRunsMergeAndAnEffectWinsOverFlags() {
        val text = "one two three"
        val runs = listOf(
            TextRun(0, 3, bold = true), TextRun(3, 7, bold = true), // touching, same: one run
            TextRun(4, 13, italic = true), // overlaps: bold+italic, then italic
            TextRun(8, 13, underline = true, effect = TextEffectKind.SHAKE), // effect: its flags go
        )
        assertEquals(
            listOf(
                TextRun(0, 4, bold = true),
                TextRun(4, 7, bold = true, italic = true),
                TextRun(7, 8, italic = true),
                TextRun(8, 13, effect = TextEffectKind.SHAKE),
            ),
            TextRuns.normalize(text, runs),
        )
        // Out of range, empty and plain runs go.
        assertEquals(listOf(TextRun(1, 3, strikethrough = true)), TextRuns.normalize("abc", listOf(TextRun(1, 99, strikethrough = true), TextRun(2, 2, bold = true), TextRun(0, 1))))
        assertEquals(
            listOf(MessagePart.Text("one ", flags(b = true)), MessagePart.Text("two", flags(b = true, i = true)), MessagePart.Text(" ", flags(i = true)), MessagePart.Text("three", fx(TextEffect.SHAKE))),
            TextRuns.toParts(text, runs),
        )
    }

    @Test
    fun noRunSplitsAnEmojiOrWhatJoinsIt() {
        val text = "hi 😀 there"
        val emoji = text.indexOf("😀")
        // Starting or ending between the two halves of the pair: the whole emoji is in it.
        assertEquals(listOf(TextRun(emoji, emoji + 2, bold = true)), TextRuns.normalize(text, listOf(TextRun(emoji + 1, emoji + 2, bold = true))))
        assertEquals(listOf(TextRun(0, emoji + 2, italic = true)), TextRuns.normalize(text, listOf(TextRun(0, emoji + 1, italic = true))))
        val parts = TextRuns.toParts(text, listOf(TextRun(emoji + 1, text.length, effect = TextEffectKind.JITTER)))
        assertEquals(listOf(MessagePart.Text("hi ", plain), MessagePart.Text("😀 there", fx(TextEffect.JITTER))), parts)
        // A skin tone, a joined family, a flag and a keycap stay whole.
        for (whole in listOf("👍🏽", "👩‍👩‍👧", "🇫🇷", "1️⃣", "e\u0301")) {
            val t = "a${whole}b"
            for (cut in 2 until 1 + whole.length) {
                assertEquals("$whole at $cut", listOf(TextRun(1, 1 + whole.length, bold = true)), TextRuns.normalize(t, listOf(TextRun(cut, 1 + whole.length, bold = true))))
            }
        }
        // Two flags side by side are two.
        val flags = "🇫🇷🇩🇪"
        assertEquals(listOf(TextRun(4, 8, bold = true)), TextRuns.normalize(flags, listOf(TextRun(4, 8, bold = true))))
    }

    @Test
    fun incomingPartsGiveEachPartsRunsWithMentionsPlainAndAttachmentsApart() {
        val parts = listOf(
            part(MessagePart.Text("Hey ", plain), 0),
            part(MessagePart.Mention("mailto:bob@example.com", "Bob"), 0),
            part(MessagePart.Text(" look ", plain), 0),
            part(MessagePart.Text("here", flags(b = true, u = true)), 0),
            part(picture(), 1),
            part(MessagePart.Text("Boom", fx(TextEffect.EXPLODE)), 2),
            part(MessagePart.Text("!", plain), 2),
        )
        assertEquals(mapOf(0 to "Hey Bob look here", 2 to "Boom!"), DirectArchive.partTexts(parts))
        assertEquals(
            mapOf(0 to listOf(TextRun(13, 17, bold = true, underline = true)), 2 to listOf(TextRun(0, 4, effect = TextEffectKind.EXPLODE))),
            TextRuns.fromParts(parts),
        )
        // Plain parts: nothing kept.
        assertEquals(emptyMap<Int, List<TextRun>>(), TextRuns.fromParts(listOf(part(MessagePart.Text("hi", plain), 0))))
        // Parts with no index (built here) are numbered as rustpush numbers them: text runs are one part.
        val built = TextRuns.toParts("a b", listOf(TextRun(2, 3, italic = true))).map { part(it) } + part(picture())
        assertEquals(listOf(0, 0, 1), DirectArchive.partIndexes(built))
        assertEquals(mapOf(0 to listOf(TextRun(2, 3, italic = true))), TextRuns.fromParts(built))
    }

    @Test
    fun runsSurviveTheTripToPartsAndBack() {
        val text = "Big news 🎉 for you"
        val runs = listOf(TextRun(0, 3, effect = TextEffectKind.BIG), TextRun(4, 8, bold = true, italic = true), TextRun(9, 11, strikethrough = true))
        val back = TextRuns.fromParts(TextRuns.toParts(text, runs).map { part(it, 0) })[0]
        assertEquals(TextRuns.normalize(text, runs), back)
        assertEquals(runs, back)
        // Every effect maps to the library's and back.
        for (k in TextEffectKind.values()) assertEquals(k, TextEffectKind.of(k.wire))
        assertEquals(TextEffectKind.values().map { it.name }, TextEffect.values().map { it.name })
    }

    @Test
    fun trimmingMovesTheRuns() {
        val (text, runs) = TextRuns.trimmed("  hi there \n", listOf(TextRun(0, 4, bold = true), TextRun(5, 12, effect = TextEffectKind.NOD)))
        assertEquals("hi there", text)
        assertEquals(listOf(TextRun(0, 2, bold = true), TextRun(3, 8, effect = TextEffectKind.NOD)), runs)
        assertEquals("" to emptyList<TextRun>(), TextRuns.trimmed("   ", listOf(TextRun(0, 3, bold = true))))
    }

    @Test
    fun aToggleTurnsAFlagOnOrOffAndAnEffectTakesTheFlagsOff() {
        val text = "make this bold"
        var runs = TextRuns.apply(text, emptyList(), 5, 9, TextRuns.Change.Toggle(TextRuns.Change.Flag.BOLD))
        assertEquals(listOf(TextRun(5, 9, bold = true)), runs)
        // Again over a selection all bold: off.
        assertEquals(emptyList<TextRun>(), TextRuns.apply(text, runs, 5, 9, TextRuns.Change.Toggle(TextRuns.Change.Flag.BOLD)))
        // Over a selection partly bold: all of it bold.
        assertEquals(listOf(TextRun(0, 9, bold = true)), TextRuns.apply(text, runs, 0, 9, TextRuns.Change.Toggle(TextRuns.Change.Flag.BOLD)))
        // An effect over bold text: the effect wins there.
        runs = TextRuns.apply(text, runs, 7, 14, TextRuns.Change.Effect(TextEffectKind.RIPPLE))
        assertEquals(listOf(TextRun(5, 7, bold = true), TextRun(7, 14, effect = TextEffectKind.RIPPLE)), runs)
        // Italic over text with an effect takes the effect off it.
        assertEquals(
            listOf(TextRun(5, 7, bold = true), TextRun(7, 9, italic = true), TextRun(9, 14, effect = TextEffectKind.RIPPLE)),
            TextRuns.apply(text, runs, 7, 9, TextRuns.Change.Toggle(TextRuns.Change.Flag.ITALIC)),
        )
        // The effect taken off.
        assertEquals(listOf(TextRun(5, 7, bold = true)), TextRuns.apply(text, runs, 0, 14, TextRuns.Change.Effect(null)))
        // A selection backwards is the same; an empty one changes nothing.
        assertEquals(listOf(TextRun(0, 4, underline = true)), TextRuns.apply(text, emptyList(), 4, 0, TextRuns.Change.Toggle(TextRuns.Change.Flag.UNDERLINE)))
        assertEquals(emptyList<TextRun>(), TextRuns.apply(text, emptyList(), 3, 3, TextRuns.Change.Toggle(TextRuns.Change.Flag.UNDERLINE)))
        assertTrue(TextRuns.allHave(text, listOf(TextRun(0, 14, strikethrough = true)), 2, 6, TextRuns.Change.Flag.STRIKETHROUGH))
    }

    @Test
    fun theSendBuildsFormattedTextPartsAheadOfItsAttachments() {
        val runs = listOf(TextRun(0, 4, effect = TextEffectKind.BLOOM), TextRun(5, 9, underline = true))
        val att = MessageAttachment(AttachmentType.Inline(byteArrayOf(1, 2, 3)), 0UL, "public.jpeg", "image/jpeg", "a.jpg", false)
        val out = DirectMessages.outgoing("Glow look", listOf(att), runs)
        assertEquals(
            listOf(MessagePart.Text("Glow", fx(TextEffect.BLOOM)), MessagePart.Text(" ", plain), MessagePart.Text("look", flags(u = true)), MessagePart.Attachment(att)),
            out.parts.map { it.part },
        )
        assertTrue("numbered by rustpush", out.parts.all { it.idx == null })
        assertEquals(listOf(0, 0, 0, 1), DirectArchive.partIndexes(out.parts))
        // The picture is still part 1 (archive part "p1").
        assertEquals(1, out.attachments.single().idx)
        assertEquals("p1", out.attachments.single().partId)
        // No runs: as before, one plain part.
        assertEquals(listOf(MessagePart.Text("Glow look", plain)), DirectMessages.outgoing("Glow look", emptyList()).parts.map { it.part })
        // An edit's new text with its formatting.
        assertEquals(
            listOf(MessagePart.Text("fixed ", plain), MessagePart.Text("it", flags(i = true))),
            DirectMessages.editParts("fixed it", listOf(TextRun(6, 8, italic = true))).map { it.part },
        )
        assertEquals(listOf(MessagePart.Text("fixed", plain)), DirectMessages.editParts("fixed").map { it.part })
        assertEquals(listOf(TextRun(6, 8, italic = true)), TextRuns.fromEdit(DirectMessages.editParts("fixed it", listOf(TextRun(6, 8, italic = true))).map { it.part }))
    }

    @Test
    fun theExtraKeepsEachPartsRunsAndShowsThemOverTheWholeText() {
        val extra = IMessageExtra(
            parts = mapOf(0 to "first line", 2 to "second"),
            runs = mapOf(0 to listOf(TextRun(0, 5, bold = true)), 2 to listOf(TextRun(0, 6, effect = TextEffectKind.SMALL))),
        )
        val back = IMessageExtra.parse(extra.toJson())
        assertEquals(extra, back)
        assertEquals("first line\nsecond", back.text())
        assertEquals(listOf(TextRun(0, 5, bold = true), TextRun(11, 17, effect = TextEffectKind.SMALL)), back.textRuns(back.text()))
        assertEquals(listOf(TextEffectKind.SMALL), back.textEffects())
        // Its text no longer what the parts make: nothing drawn in the wrong place.
        assertEquals(emptyList<TextRun>(), back.textRuns("something else"))
        // No part texts kept (a message sent here): the text is part 0.
        val sent = IMessageExtra(runs = mapOf(0 to listOf(TextRun(3, 7, italic = true))))
        assertEquals(listOf(TextRun(3, 7, italic = true)), IMessageExtra.parse(sent.toJson()).textRuns("hi there"))
        // Plain: nothing stored.
        assertEquals("", IMessageExtra(runs = mapOf(0 to emptyList())).toJson())
        // Unknown effects and broken runs are skipped.
        val odd = JSONObject("""{"runs":{"0":[{"s":0,"e":2,"fx":"sparkle"},{"s":3,"e":1,"b":true},{"s":0,"e":1,"i":true}]}}""")
        assertEquals(mapOf(0 to listOf(TextRun(0, 2), TextRun(0, 1, italic = true))), IMessageExtra.parse(odd.toString()).runs)
        assertNull(TextEffectKind.of("sparkle"))
    }
}
