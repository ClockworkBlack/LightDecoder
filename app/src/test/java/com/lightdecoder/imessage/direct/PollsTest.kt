package com.lightdecoder.imessage.direct

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** iOS 26's Polls, in OpenBubbles' model ([Polls]): cards and votes both ways, tallies, choices added, and what is refused. */
@RunWith(AndroidJUnit4::class)
class PollsTest {

    /** A poll's card's address as an iPhone (and OpenBubbles' polls.dart) writes one. */
    private fun cardUrl(json: String, count: Int = 2) = "data:," + Base64.getEncoder().encodeToString(json.toByteArray()) + "?src=p&c=$count"

    private val sample = """{"item":{"orderedPollOptions":[
        {"attributedText":"Pizza","canBeEdited":false,"creatorHandle":"+15551234567","optionIdentifier":"A1B2C3D4-0000-0000-0000-000000000001","text":"Pizza"},
        {"attributedText":"Tacos","canBeEdited":false,"creatorHandle":"+15551234567","optionIdentifier":"A1B2C3D4-0000-0000-0000-000000000002","text":"Tacos"},
        {"attributedText":"Sushi","canBeEdited":true,"creatorHandle":"+15557654321","optionIdentifier":"A1B2C3D4-0000-0000-0000-000000000003","text":"Sushi"}
        ],"votes":[],"creatorHandle":"+15551234567","title":"Dinner?"},"version":1}"""

    private val o1 = "A1B2C3D4-0000-0000-0000-000000000001"
    private val o2 = "A1B2C3D4-0000-0000-0000-000000000002"
    private val o3 = "A1B2C3D4-0000-0000-0000-000000000003"

    @Test
    fun anIphonesPollReadsAsItsQuestionAndChoices() {
        val poll = Polls.decode(cardUrl(sample, 3))!!
        assertEquals("Dinner?", poll.title)
        assertEquals("+15551234567", poll.creator)
        assertEquals(listOf("Pizza", "Tacos", "Sushi"), poll.options.map { it.text })
        assertEquals(listOf(o1, o2, o3), poll.options.map { it.id })
        assertEquals("+15557654321", poll.options[2].creator)
        assertTrue(poll.options[2].canBeEdited)
        assertEquals(3, poll.count)
        assertEquals("Pizza, Tacos, Sushi", Polls.ldText(poll))
    }

    @Test
    fun whatIsWrittenReadsBackTheSameAndInOpenBubblesShape() {
        val poll = Polls.decode(cardUrl(sample))!!
        val url = Polls.encode(poll)
        assertTrue(url.startsWith("data:,"))
        assertTrue(url.endsWith("?src=p&c=2"))
        assertEquals(poll, Polls.decode(url))
        val json = JSONObject(String(Base64.getDecoder().decode(url.removePrefix("data:,").substringBefore('?'))))
        assertEquals(1, json.getInt("version"))
        val item = json.getJSONObject("item")
        assertEquals("Dinner?", item.getString("title"))
        assertEquals(0, item.getJSONArray("votes").length())
        val first = item.getJSONArray("orderedPollOptions").getJSONObject(0)
        assertEquals(setOf("attributedText", "canBeEdited", "creatorHandle", "optionIdentifier", "text"), first.keys().asSequence().toSet())
        assertEquals("Pizza", first.getString("attributedText"))
    }

    @Test
    fun aVoteIsTheVotersWholeChoiceInOpenBubblesShape() {
        val url = Polls.votesUrl(listOf(o1, o3), "+15550000001")
        assertFalse("a vote has no count", url.contains('?'))
        val json = JSONObject(String(Base64.getDecoder().decode(url.removePrefix("data:,"))))
        assertEquals(1, json.getInt("version"))
        val votes = json.getJSONObject("item").getJSONArray("votes")
        assertEquals(2, votes.length())
        assertEquals(o1, votes.getJSONObject(0).getString("voteOptionIdentifier"))
        assertEquals("+15550000001", votes.getJSONObject(0).getString("participantHandle"))
        assertEquals(listOf(o1, o3), Polls.decodeVotes(url))
        // Taking every vote back: none.
        assertEquals(emptyList<String>(), Polls.decodeVotes(Polls.votesUrl(emptyList(), "+15550000001")))
        // A vote as OpenBubbles reads one: only "votes" matters.
        val bare = JSONObject().put("item", JSONObject().put("votes", JSONArray().put(JSONObject().put("voteOptionIdentifier", o2).put("participantHandle", "+15559999999")))).put("version", 1)
        assertEquals(listOf(o2), Polls.decodeVotes("data:," + Base64.getEncoder().encodeToString(bare.toString().toByteArray())))
        // As the vote's app update: Polls' bundle, its address, the card's GUID as its session.
        val app = Polls.vote(listOf(o2), "+15550000001", "CARD-GUID")
        assertTrue(Polls.isPoll(app))
        assertEquals("CARD-GUID", app.session)
        assertEquals(Polls.NAME, app.name)
        assertEquals(listOf(o2), Polls.decodeVotes(app.url))
    }

    @Test
    fun theTallyCountsEachPersonsLatestChoice() {
        val poll = Polls.decode(cardUrl(sample))!!
        var ballots = emptyList<Polls.Ballot>()
        ballots = Polls.withBallot(ballots, Polls.Ballot("+15551234567", listOf(o1), 10))
        ballots = Polls.withBallot(ballots, Polls.Ballot("+15557654321", listOf(o1, o2), 11))
        ballots = Polls.withBallot(ballots, Polls.Ballot(Polls.ME, listOf(o2), 12))
        // Alice changes her mind; an older one of Ben's, come late, changes nothing.
        ballots = Polls.withBallot(ballots, Polls.Ballot("+15551234567", listOf(o3), 20))
        ballots = Polls.withBallot(ballots, Polls.Ballot("+15557654321", emptyList(), 5))
        val tally = Polls.tally(poll, ballots)
        assertEquals(listOf(1, 2, 1), tally.map { it.count })
        assertEquals(listOf("+15557654321"), tally[0].voters)
        assertEquals(listOf("+15557654321", Polls.ME), tally[1].voters)
        assertEquals(listOf(o2), Polls.choicesOf(ballots, Polls.ME))
        // A vote for a choice the card does not have (added since, not come yet) counts for none of its own.
        val withUnknown = Polls.withBallot(ballots, Polls.Ballot("+15550000009", listOf("SOMETHING-ELSE"), 30))
        assertEquals(listOf(1, 2, 1), Polls.tally(poll, withUnknown).map { it.count })
        // Kept and read back.
        assertEquals(withUnknown, Polls.ballotsFromJson(JSONArray(Polls.ballotsToJson(withUnknown).toString())))
    }

    @Test
    fun aTapChoosesOrTakesBackAndSeveralCanBeChosen() {
        assertEquals(listOf(o1), Polls.toggle(emptyList(), o1))
        assertEquals(listOf(o1, o2), Polls.toggle(listOf(o1), o2))
        assertEquals(listOf(o2), Polls.toggle(listOf(o1, o2), o1))
    }

    @Test
    fun aChoiceIsAddedWithTheCountOneHigherUnlessItCannotBe() {
        val poll = Polls.decode(cardUrl(sample, 3))!!
        val added = Polls.addChoice(poll, "  Curry ", "+15550000001", "NEW-ID")!!
        assertEquals(listOf("Pizza", "Tacos", "Sushi", "Curry"), added.options.map { it.text })
        assertEquals(Polls.Option("NEW-ID", "Curry", "+15550000001"), added.options.last())
        assertEquals(4, added.count)
        assertEquals(4, Polls.countOf(Polls.encode(added)))
        // Already there (any case), blank, or no room: nothing.
        assertNull(Polls.addChoice(poll, "pizza", "+1"))
        assertNull(Polls.addChoice(poll, "   ", "+1"))
        val full = poll.copy(options = (0 until Polls.MAX_OPTIONS).map { Polls.Option("ID$it", "C$it") })
        assertNull(Polls.addChoice(full, "One more", "+1"))
        // As the card sent again: Polls' card, live, its choices as its summary, the words an older device shows.
        val card = Polls.card(added, "SESSION-1", root = "imsg:ROOT")
        assertEquals(Polls.BUNDLE_ID, card.bundleId)
        assertEquals("SESSION-1", card.session)
        assertTrue(card.isLive)
        assertEquals("Pizza, Tacos, Sushi, Curry", card.ldText)
        assertEquals(Polls.FALLBACK_CAPTION, card.caption)
        assertEquals(Polls.FALLBACK_SUBCAPTION, card.subcaption)
        assertEquals("imsg:ROOT", card.root)
        assertEquals(added, Polls.decode(card.url))
    }

    @Test
    fun aNewPollHasItsQuestionAndTwoChoicesAtLeast() {
        var n = 0
        val poll = Polls.create("Where?", listOf("Here", "", "There", "here ", "Here"), "+15550000001") { "ID-${++n}" }!!
        assertEquals("Where?", poll.title)
        assertEquals(listOf("Here", "There", "here"), poll.options.map { it.text })
        assertEquals(listOf("ID-1", "ID-2", "ID-3"), poll.options.map { it.id })
        assertTrue(poll.options.all { it.creator == "+15550000001" })
        assertEquals(Polls.FIRST_COUNT, poll.count)
        assertNull(Polls.create("Q", listOf("Only one", " "), "+1"))
        // A question is optional.
        assertEquals("", Polls.create("", listOf("A", "B"), "+1")!!.title)
    }

    @Test
    fun whatCannotBeReadOrIsTooLargeIsNoPoll() {
        assertNull(Polls.decode("https://example.com/poll"))
        assertNull(Polls.decode("data:,"))
        assertNull(Polls.decode("data:,not base64 at all!!!"))
        assertNull(Polls.decode(cardUrl("not json")))
        assertNull(Polls.decode(cardUrl("""{"item":{"orderedPollOptions":[]},"version":1}""")))
        assertNull(Polls.decode(cardUrl("""{"version":1}""")))
        assertNull(Polls.decodeVotes(cardUrl("""{"item":{},"version":1}""")))
        // Larger than a poll is allowed to be: not decoded at all.
        val huge = """{"item":{"orderedPollOptions":[{"optionIdentifier":"X","text":"${"a".repeat(Polls.MAX_JSON_BYTES)}"}]},"version":1}"""
        assertNull(Polls.decode(cardUrl(huge)))
        // Choices with no identifier or words, or the same identifier twice, are left out; at most MAX_OPTIONS; long words cut.
        val options = JSONArray()
        options.put(JSONObject().put("optionIdentifier", "").put("text", "no id"))
        options.put(JSONObject().put("optionIdentifier", "A").put("text", ""))
        options.put(JSONObject().put("optionIdentifier", "B").put("text", "b"))
        options.put(JSONObject().put("optionIdentifier", "B").put("text", "again"))
        for (i in 0 until 40) options.put(JSONObject().put("optionIdentifier", "N$i").put("text", "n$i"))
        options.put(JSONObject().put("optionIdentifier", "L").put("text", "x".repeat(1000)))
        val many = Polls.decode(cardUrl(JSONObject().put("item", JSONObject().put("orderedPollOptions", options).put("title", "t\u0000\nq")).put("version", 1).toString()))!!
        assertEquals(Polls.MAX_OPTIONS, many.options.size)
        assertEquals("B", many.options.first().id)
        assertEquals("t q", many.title)
        val long = Polls.decode(cardUrl(JSONObject().put("item", JSONObject().put("orderedPollOptions", JSONArray().put(JSONObject().put("optionIdentifier", "L").put("text", "x".repeat(1000))))).toString()))!!
        assertEquals(Polls.MAX_TEXT_CHARS, long.options.single().text.length)
    }

    @Test
    fun aCardIsAPollByItsBundleAndItsAddressMayComeEscaped() {
        assertTrue(Polls.isPoll(IMessageExtra.App(name = "Polls", bundleId = Polls.BUNDLE_ID)))
        assertTrue(Polls.isPoll(IMessageExtra.App(bundleId = "com.apple.messages.MSMessageExtensionBalloonPlugin:ABCDE12345:com.apple.messages.Polls")))
        assertFalse(Polls.isPoll(IMessageExtra.App(name = "Polls", bundleId = "com.example.polls")))
        assertFalse(Polls.isPoll(null))
        // A "+" read as a space, or escaped, on its way.
        val url = Polls.encode(Polls.decode(cardUrl(sample))!!)
        assertNotNull(Polls.decode(url.replace('+', ' ')))
        assertNotNull(Polls.decode(url.replace("+", "%2B").replace("/", "%2F")))
        assertEquals(7, Polls.countOf("data:,abc?src=p&c=7"))
        assertEquals(Polls.FIRST_COUNT, Polls.countOf("data:,abc"))
    }

    // --- a change of someone's poll, and what could break the reading --------------------------------

    @Test
    fun theCountIsAParameterOfItsOwnOfSixDigitsAtMost() {
        assertEquals(3, Polls.countOf("data:,abc?src=p&c=3"))
        assertEquals(3, Polls.countOf("data:,abc?c=3&src=p"))
        assertEquals(999_999, Polls.countOf("data:,abc?src=p&c=999999"))
        // Seven digits are no count that can be read (not its first six), nor is one followed by more.
        assertEquals(Polls.FIRST_COUNT, Polls.countOf("data:,abc?src=p&c=1000000"))
        assertEquals(Polls.FIRST_COUNT, Polls.countOf("data:,abc?src=p&c=12abc"))
        assertEquals(Polls.FIRST_COUNT, Polls.countOf("data:,abc?src=p&cc=5"))
        assertEquals(Polls.FIRST_COUNT, Polls.countOf("data:,abc?src=p&xc=5"))
        // Its own count at the end: "Add Choice" never goes past what reads back.
        val poll = Polls.decode(cardUrl(sample, Polls.MAX_COUNT))!!
        assertEquals(Polls.MAX_COUNT, poll.count)
        assertNull(Polls.addChoice(poll, "Curry", "+1"))
        assertEquals(Polls.MAX_COUNT, Polls.addChoice(poll.copy(count = Polls.MAX_COUNT - 1), "Curry", "+1")!!.count)
    }

    @Test
    fun aChangeOnlyAddsChoicesAndItsCountGoesUpByAFewAtMost() {
        val stored = Polls.decode(cardUrl(sample, 3))!!
        val added = Polls.addChoice(stored, "Curry", "+15550000009", "NEW-1")!!
        assertEquals(added, Polls.merged(stored, added))
        // A few changes missed: the choices they added come too, as many as the count went up.
        val two = Polls.addChoice(added, "Soup", "+15550000009", "NEW-2")!!
        assertEquals(listOf("Pizza", "Tacos", "Sushi", "Curry", "Soup"), Polls.merged(stored, two)!!.options.map { it.text })
        val skipped = two.copy(count = stored.count + 1)
        assertEquals("one count up adds one choice", listOf("Pizza", "Tacos", "Sushi", "Curry"), Polls.merged(stored, skipped)!!.options.map { it.text })
        // The same count, an older one, or one far ahead (it would freeze the poll: nothing later could follow): none.
        assertNull(Polls.merged(stored, added.copy(count = stored.count)))
        assertNull(Polls.merged(stored, added.copy(count = stored.count - 1)))
        assertNull(Polls.merged(stored, added.copy(count = stored.count + Polls.MAX_COUNT_STEP + 1)))
        assertNull(Polls.merged(stored, added.copy(count = 999_999)))
        assertEquals(stored.count + Polls.MAX_COUNT_STEP, Polls.merged(stored, added.copy(count = stored.count + Polls.MAX_COUNT_STEP))!!.count)
        // Nothing new in it: no change to take.
        assertNull(Polls.merged(stored, stored.copy(count = stored.count + 1)))
        // Someone else's words for the choices there, another order, another question, a choice taken out: the
        // stored ones stay, as they were (a vote never moves to other words); only the new choice is added.
        val rewritten = added.copy(
            title = "Lunch?",
            options = listOf(
                stored.options[1].copy(text = "Pizza"), stored.options[0].copy(text = "Tacos"), added.options.last(),
            ),
        )
        val merged = Polls.merged(stored, rewritten)!!
        assertEquals("Dinner?", merged.title)
        assertEquals(listOf(o1 to "Pizza", o2 to "Tacos", o3 to "Sushi", "NEW-1" to "Curry"), merged.options.map { it.id to it.text })
        // A new choice with the words of one there already (any case) is none.
        assertNull(Polls.merged(stored, stored.copy(options = stored.options + Polls.Option("NEW-3", "PIZZA"), count = stored.count + 1)))
        // Never more than a poll's choices.
        val full = stored.copy(options = (0 until Polls.MAX_OPTIONS).map { Polls.Option("ID$it", "C$it") })
        assertNull(Polls.merged(full, full.copy(options = full.options + Polls.Option("X", "One more"), count = full.count + 1)))
    }

    @Test
    fun theMakerTakesAChoiceOffAndNobodyElseCan() {
        val stored = Polls.decode(cardUrl(sample, 3))!!
        val removed = Polls.removeChoice(stored, o2)!!
        assertEquals(listOf(o1 to "Pizza", o3 to "Sushi"), removed.options.map { it.id to it.text })
        assertEquals(stored.count + 1, removed.count)
        assertEquals("Dinner?", removed.title)
        // It goes as any card of the poll goes, and reads back the same.
        assertEquals(removed, Polls.decode(Polls.encode(removed)))
        assertEquals("Pizza, Sushi", Polls.ldText(removed))
        // Not one of its choices, only two left, or a count that could not be read back: nothing to send.
        assertNull(Polls.removeChoice(stored, "NOPE"))
        assertNull(Polls.removeChoice(removed, o1))
        assertNull(Polls.removeChoice(stored.copy(count = Polls.MAX_COUNT), o2))
        // From the poll's maker the card without it is the poll; from anyone else it is no change at all.
        assertEquals(removed, Polls.merged(stored, removed, byMaker = true))
        assertNull(Polls.merged(stored, removed))
        // The maker's card may add one as well; what stays keeps the stored words and order.
        val both = removed.copy(options = listOf(removed.options[1].copy(text = "Sashimi"), removed.options[0], Polls.Option("NEW-1", "Curry")))
        assertEquals(listOf(o1 to "Pizza", o3 to "Sushi", "NEW-1" to "Curry"), Polls.merged(stored, both, byMaker = true)!!.options.map { it.id to it.text })
        // The count still has to go up, by a few at most; and a card that leaves nothing is not taken.
        assertNull(Polls.merged(stored, removed.copy(count = stored.count), byMaker = true))
        assertNull(Polls.merged(stored, removed.copy(count = stored.count + Polls.MAX_COUNT_STEP + 1), byMaker = true))
        assertNull(Polls.merged(stored, stored.copy(count = stored.count + 1), byMaker = true))
        // Votes for a choice taken off are not counted any more; the others are.
        val ballots = listOf(Polls.Ballot("+15559876543", listOf(o2, o3), 1), Polls.Ballot(Polls.ME, listOf(o2), 2))
        assertEquals(listOf(0, 1), Polls.tally(removed, ballots).map { it.count })
    }

    @Test
    fun aLockedPollIsSentAgainWithItsOwnChoicesAboveTheCardSeen() {
        val stored = Polls.decode(cardUrl(sample, 3))!!
        // Someone's card went as far as 4: the maker's goes as 5, with the maker's choices.
        assertEquals(stored.copy(count = 5), Polls.reasserted(stored, 4))
        // An older one seen: still above the poll's own count.
        assertEquals(stored.copy(count = 4), Polls.reasserted(stored, 2))
        assertNull(Polls.reasserted(stored, Polls.MAX_COUNT))
        assertNull(Polls.reasserted(stored.copy(count = Polls.MAX_COUNT), 3))
        // Where this app shows the poll, the maker's card takes the choice someone added off again.
        val theirs = Polls.addChoice(stored, "Curry", "+15559876543", "NEW-1")!!
        val shown = Polls.merged(stored, theirs)!!
        assertEquals(stored.options, Polls.merged(shown, Polls.reasserted(stored, theirs.count)!!, byMaker = true)!!.options)
    }

    @Test
    fun aPollNestingDeeperThanAnyPollIsNotReadAtAll() {
        // Thousands deep: org.json would run its stack out reading it.
        val deep = """{"item":{"orderedPollOptions":[{"optionIdentifier":"X","text":"a","x":""" + "[".repeat(5_000) + "]".repeat(5_000) + "}]},\"version\":1}"
        val start = System.nanoTime()
        assertNull(Polls.decode(cardUrl(deep)))
        assertNull(Polls.decodeVotes(cardUrl(deep)))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000)
        // As deep as allowed is read; one deeper is not.
        fun nested(levels: Int) = """{"item":{"orderedPollOptions":[{"optionIdentifier":"X","text":"a","x":""" + "[".repeat(levels) + "]".repeat(levels) + "}]}}"
        assertNotNull(Polls.decode(cardUrl(nested(Polls.MAX_NESTING - 4))))
        assertNull(Polls.decode(cardUrl(nested(Polls.MAX_NESTING))))
        // Brackets inside strings are words, not nesting.
        assertTrue(Polls.plainJsonWithin("""{"a":"[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[","b":"\"{{{{"}""", 2))
        // What org.json reads beyond plain JSON, where brackets could hide from the count, is refused:
        // a quote inside a word without quotes, single quotes, comments, other signs.
        assertFalse(Polls.plainJsonWithin("""[a", [[[[[[[[ ]]]]]]]] ,"]""", 4))
        assertFalse(Polls.plainJsonWithin("""{'a':'[[[['}""", 4))
        assertFalse(Polls.plainJsonWithin("""{"a":1 /* [[[[ */}""", 4))
        assertFalse(Polls.plainJsonWithin("""{"a":1 # [[[[""", 4))
        assertFalse(Polls.plainJsonWithin("""{"a";1}""", 4))
        assertFalse(Polls.plainJsonWithin("""{"a":"unterminated}""", 4))
        assertFalse(Polls.plainJsonWithin("""{"a":1}}""", 4))
        assertTrue(Polls.plainJsonWithin(sample, 4))
    }
}
