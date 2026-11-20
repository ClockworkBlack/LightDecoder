package com.lightdecoder.imessage.direct

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.rust_bridge.AttachmentType
import uniffi.rust_bridge.IndexedMessagePart
import uniffi.rust_bridge.MessageAttachment
import uniffi.rust_bridge.MessagePart
import uniffi.rust_bridge.TextFlags
import uniffi.rust_bridge.TextFormat

/** Mentions (iOS 17) to and from the library's parts, in the composer's suggestions, and kept with a message ([Mentions]). */
@RunWith(AndroidJUnit4::class)
class MentionsTest {

    private val plain = TextFormat.Flags(TextFlags(false, false, false, false))
    private val bold = TextFormat.Flags(TextFlags(true, false, false, false))
    private fun part(p: MessagePart, idx: Int? = null) = IndexedMessagePart(p, idx?.toULong(), null)
    private fun picture() = MessagePart.Attachment(MessageAttachment(AttachmentType.Inline(byteArrayOf(1)), 0UL, "public.jpeg", "image/jpeg", "a.jpg", false))

    @Test
    fun aMentionIsSentAsAMentionPartWithTheBareAddressBetweenTheTextsRuns() {
        val text = "Hi Alice, see you"
        val parts = Mentions.toParts(text, emptyList(), listOf(Mention(3, 8, "tel:+15551234567")))
        assertEquals(
            listOf(MessagePart.Text("Hi ", plain), MessagePart.Mention("+15551234567", "Alice"), MessagePart.Text(", see you", plain)),
            parts,
        )
        // An email address the same: bare, as OpenBubbles sends it.
        assertEquals("carol@example.com", Mentions.uriFor("mailto:carol@example.com"))
        assertEquals("+15551234567", Mentions.uriFor("+15551234567"))
        // None: the text as TextRuns makes it.
        assertEquals(listOf(MessagePart.Text("plain", plain)), Mentions.toParts("plain", emptyList(), emptyList()))
    }

    @Test
    fun formattingAroundAMentionStaysOnItsTextAndTheMentionCarriesNone() {
        val text = "Go Bob now"
        // Bold over all of it: the mention's own part has no format to carry.
        val parts = Mentions.toParts(text, listOf(TextRun(0, text.length, bold = true)), listOf(Mention(3, 6, "+15550000002")))
        assertEquals(
            listOf(MessagePart.Text("Go ", bold), MessagePart.Mention("+15550000002", "Bob"), MessagePart.Text(" now", bold)),
            parts,
        )
        // All of them are the one part 0, as rustpush numbers them, and the text reads as it was.
        val indexed = parts.map { part(it) } + part(picture())
        assertEquals(listOf(0, 0, 0, 1), DirectArchive.partIndexes(indexed))
        assertEquals(mapOf(0 to text), DirectArchive.partTexts(indexed))
    }

    @Test
    fun mentionsThatOverlapOrFallOutsideTheTextAreLeftOut() {
        val text = "Ann and Ben"
        val norm = Mentions.normalize(text, listOf(Mention(8, 11, "+2"), Mention(0, 3, "+1"), Mention(1, 5, "+3"), Mention(20, 25, "+4"), Mention(4, 4, "+5")))
        assertEquals(listOf(Mention(0, 3, "+1"), Mention(8, 11, "+2")), norm)
        assertEquals(emptyList<Mention>(), Mentions.normalize(text, listOf(Mention(0, 3, ""))))
        val many = (0 until 80).map { Mention(it, it + 1, "+1555000$it") }
        assertEquals(Mentions.MAX_PER_PART, Mentions.normalize("x".repeat(100), many).size)
    }

    @Test
    fun anIncomingMessagesMentionsAreKeptByPartAndOneOfTheUserIsMarked() {
        val me = setOf("tel:+15550000001", "mailto:me@icloud.com")
        val parts = listOf(
            part(MessagePart.Text("Hey ", plain), 0),
            part(MessagePart.Mention("tel:+15550000001", "Me"), 0),
            part(MessagePart.Text(" and ", plain), 0),
            part(MessagePart.Mention("+15550000002", "Bob"), 0),
            part(picture(), 1),
            part(MessagePart.Mention("ME@icloud.com", "You"), 2),
        )
        val found = Mentions.fromParts(parts, me)
        assertEquals(
            mapOf(
                0 to listOf(Mention(4, 6, "tel:+15550000001", mine = true), Mention(11, 14, "+15550000002")),
                2 to listOf(Mention(0, 3, "ME@icloud.com", mine = true)),
            ),
            found,
        )
        // A number is the user's only by all its digits, never by its last ten.
        assertFalse(Mentions.isMine("+915550000001", me))
        assertTrue(Mentions.isMine("+1 555 000 0001", me))
        // An edit's new parts the same, as one part.
        val edit = Mentions.fromEdit(listOf(MessagePart.Text("ok ", plain), MessagePart.Mention("+15550000002", "Bob")), me)
        assertEquals(listOf(Mention(3, 6, "+15550000002")), edit)
    }

    @Test
    fun aMentionWithNoNameOrNoAddressIsNoMention() {
        val parts = listOf(part(MessagePart.Mention("", "Nobody"), 0), part(MessagePart.Mention("+15550000002", ""), 0), part(MessagePart.Text("x", plain), 0))
        assertEquals(emptyMap<Int, List<Mention>>(), Mentions.fromParts(parts, emptySet()))
        val long = "+" + "1".repeat(Mentions.MAX_HANDLE_CHARS + 5)
        assertEquals(emptyMap<Int, List<Mention>>(), Mentions.fromParts(listOf(part(MessagePart.Mention(long, "X"), 0)), emptySet()))
    }

    @Test
    fun trimmingTheTextMovesItsMentionsAlong() {
        val text = "  @ Bob hi  "
        assertEquals(listOf(Mention(2, 5, "+2")), Mentions.trimmed(text, listOf(Mention(4, 7, "+2"))))
        // One cut by the trim goes.
        assertEquals(emptyList<Mention>(), Mentions.trimmed("Bob  ", listOf(Mention(2, 5, "+2"))))
        assertEquals(emptyList<Mention>(), Mentions.trimmed("   ", listOf(Mention(0, 1, "+2"))))
    }

    @Test
    fun anEditKeepsTheMentionsWhoseNamesItLeftAsTheyWere() {
        val old = "Bob and Ann, lunch?"
        val mentions = listOf(Mention(0, 3, "+2"), Mention(8, 11, "+3"))
        // Words added at the end: both stay.
        assertEquals(mentions, Mentions.afterEdit(old, "Bob and Ann, lunch at 1?", mentions))
        // A comma put in after Bob: Ann moves with the rest.
        assertEquals(listOf(Mention(0, 3, "+2"), Mention(9, 12, "+3")), Mentions.afterEdit(old, "Bob, and Ann, lunch?", mentions))
        // Ann's name changed: only Bob is left.
        assertEquals(listOf(Mention(0, 3, "+2")), Mentions.afterEdit(old, "Bob and Anna, lunch?", mentions))
        assertEquals(emptyList<Mention>(), Mentions.afterEdit(old, "", mentions))
    }

    @Test
    fun anAtSignBeginsALookUpThatEndsAtASpace() {
        assertEquals(Mentions.Query(0, 1, ""), Mentions.query("@", 1))
        assertEquals(Mentions.Query(4, 7, "Al"), Mentions.query("Hey @Al", 7))
        assertEquals(Mentions.Query(5, 8, "Al"), Mentions.query("Hey\n @Al", 8))
        // Within a word ("me@home"), after a space, or past the cursor: none.
        assertNull(Mentions.query("me@home", 7))
        assertNull(Mentions.query("Hey @Al ", 8))
        assertNull(Mentions.query("Hey", 3))
        assertNull(Mentions.query("", 0))
        // Inside a mention already made: none.
        assertNull(Mentions.query("x @Al", 5, taken = listOf(2 until 5)))
        // Too long a name: not looked up.
        assertNull(Mentions.query("@" + "a".repeat(Mentions.MAX_QUERY_CHARS + 1), Mentions.MAX_QUERY_CHARS + 2))
    }

    @Test
    fun theMembersSuggestedAreThoseWithAWordOfTheirNameStartingWithWhatIsTyped() {
        val members = listOf(
            Mentions.Member("+15551234567", "Alice Martin"),
            Mentions.Member("+15557654321", "Ben Ortiz"),
            Mentions.Member("carol@example.com", "Carol Diaz"),
            Mentions.Member("+15559990000", "Élodie Brun"),
            Mentions.Member("+1 (555) 123-4567", "Alice again"),
        )
        assertEquals(listOf("Alice Martin", "Ben Ortiz", "Carol Diaz", "Élodie Brun"), Mentions.suggestions("", members).map { it.name })
        assertEquals(listOf("Alice Martin"), Mentions.suggestions("al", members).map { it.name })
        assertEquals(listOf("Ben Ortiz"), Mentions.suggestions("ORT", members).map { it.name })
        // Accents are not in the way, and an address works as a name.
        assertEquals(listOf("Élodie Brun"), Mentions.suggestions("elo", members).map { it.name })
        assertEquals(listOf("Carol Diaz"), Mentions.suggestions("carol@", members).map { it.name })
        assertEquals(emptyList<String>(), Mentions.suggestions("zed", members).map { it.name })
        assertTrue(Mentions.suggestions("", (0 until 20).map { Mentions.Member("+1555000000$it", "P$it") }).size <= Mentions.MAX_SUGGESTIONS)
    }

    @Test
    fun aMentionShowsAContactsFirstNameAndAnAddressAsItIs() {
        assertEquals("Alice", Mentions.shownName("Alice Martin"))
        assertEquals("Alice", Mentions.shownName("  Alice  "))
        assertEquals("+15551234567", Mentions.shownName("+15551234567"))
        assertEquals("carol@example.com", Mentions.shownName("carol@example.com"))
    }

    @Test
    fun aMessagesMentionsAreKeptAndReadBackOverItsText() {
        val extra = IMessageExtra(
            parts = mapOf(0 to "Hi Bob", 2 to "and Ann"),
            mentions = mapOf(0 to listOf(Mention(3, 6, "+2")), 2 to listOf(Mention(4, 7, "+3", mine = true))),
        )
        val back = IMessageExtra.parse(extra.toJson())
        assertEquals(extra.mentions, back.mentions)
        assertTrue(back.mentionsMe)
        // Over the text as shown (the parts one per line).
        assertEquals(listOf(Mention(3, 6, "+2"), Mention(11, 14, "+3", mine = true)), back.textMentions("Hi Bob\nand Ann"))
        // Not the text the parts make up: nothing in the wrong place.
        assertEquals(emptyList<Mention>(), back.textMentions("something else"))
        // Without parts, part 0's over the text itself.
        assertEquals(listOf(Mention(0, 3, "+2")), IMessageExtra(mentions = mapOf(0 to listOf(Mention(0, 3, "+2")))).textMentions("Bob!"))
        // A stored one that is no mention is left out.
        val odd = JSONObject().put("mentions", JSONObject().put("0", org.json.JSONArray().put(JSONObject().put("s", 4).put("e", 2).put("h", "+1"))))
        assertEquals(emptyMap<Int, List<Mention>>(), IMessageExtra.parse(odd.toString()).mentions)
    }
}
