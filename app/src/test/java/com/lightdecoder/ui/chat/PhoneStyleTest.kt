package com.lightdecoder.ui.chat

import com.lightdecoder.R
import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.chat.ChatService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** The rules of iPhone-style chats ([PhoneStyle]): where they apply, the times between messages, runs and their tails. */
class PhoneStyleTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val dayStart: (Long) -> Long = { TimeFormat.startOfDay(it, utc) }

    /** Noon on a day in 2023 (UTC). */
    private val noon = 1_700_000_000_000L / 86_400_000L * 86_400_000L + 12 * 3_600_000L
    private val minute = 60_000L

    private fun row(id: String, at: Long, mine: Boolean = false, sender: String = "alice", system: Boolean = false) = MessageRow(
        id = id, conversationId = "c1", tmpId = "", senderId = if (mine) "" else sender, senderName = if (mine) "" else sender,
        isOutgoing = mine, timestampMs = at,
        direction = when {
            system -> "system"
            mine -> "out"
            else -> "in"
        },
        status = "", statusCode = 0, errorText = "", transport = "IMESSAGE", text = id, subject = "", replyToId = "",
        reactions = emptyList(), localState = LocalState.SYNCED, media = emptyList(), updatedAt = 0,
    )

    private fun build(rows: List<MessageRow>, group: Boolean = false, phone: Boolean = true) = ThreadItems.build(
        rows, isGroup = group, quotes = emptyMap(), header = null, highlightId = null,
        dayStart = dayStart, dayLabel = { "day" }, phone = phone, clock = { "t${(it - noon) / minute}" },
    )

    @Test
    fun itAppliesOnlyToConversationsThroughTheIMessageLibraryWithTheSettingOn() {
        assertTrue(PhoneStyle.applies(settingOn = true, direct = true, service = ChatService.IMESSAGE, hasCompanion = false))
        assertTrue(PhoneStyle.applies(settingOn = true, direct = true, service = ChatService.MESSAGES, hasCompanion = true))
        // A plain text conversation: no iMessage chat with the person.
        assertFalse(PhoneStyle.applies(settingOn = true, direct = true, service = ChatService.MESSAGES, hasCompanion = false))
        // The setting off, or no library (iMessages come from OpenBubbles' notifications).
        assertFalse(PhoneStyle.applies(settingOn = false, direct = true, service = ChatService.IMESSAGE, hasCompanion = false))
        assertFalse(PhoneStyle.applies(settingOn = false, direct = true, service = ChatService.MESSAGES, hasCompanion = true))
        assertFalse(PhoneStyle.applies(settingOn = true, direct = false, service = ChatService.IMESSAGE, hasCompanion = false))
        assertFalse(PhoneStyle.applies(settingOn = true, direct = false, service = ChatService.MESSAGES, hasCompanion = true))
        // No other service, whatever else.
        for (service in ChatService.entries - setOf(ChatService.IMESSAGE, ChatService.MESSAGES)) {
            assertFalse(service.name, PhoneStyle.applies(settingOn = true, direct = true, service = service, hasCompanion = true))
        }
    }

    @Test
    fun theComposerSaysIMessageOrTextMessageAndTheArrowIsBlueOrGreen() {
        assertEquals(R.string.imessage_compose_hint, PhoneStyle.hintRes(asIMessage = true))
        assertEquals(R.string.imessage_phone_hint_text, PhoneStyle.hintRes(asIMessage = false))
        assertEquals(R.color.phone_blue, PhoneStyle.sendColorRes(asIMessage = true))
        assertEquals(R.color.phone_green, PhoneStyle.sendColorRes(asIMessage = false))
    }

    @Test
    fun theTimeGoesAboveTheFirstMessageANewDayAndAfterAQuarterOfAnHour() {
        val first = row("a", noon)
        assertTrue("the first message", PhoneStyle.timeHeader(null, first, dayStart))
        assertFalse(PhoneStyle.timeHeader(first, row("b", noon + 15 * minute), dayStart))
        assertTrue(PhoneStyle.timeHeader(first, row("b", noon + 15 * minute + 1), dayStart))
        // Midnight between them, though only two minutes apart.
        val late = row("c", noon + 12 * 60 * minute - minute)
        assertTrue(PhoneStyle.timeHeader(late, row("d", late.timestampMs + 2 * minute), dayStart))
    }

    @Test
    fun aThreadHasTimesBetweenStretchesInsteadOfDays() {
        val rows = listOf(
            row("a", noon), row("b", noon + minute), row("c", noon + 20 * minute), row("d", noon + 24 * 60 * minute),
        )
        val items = build(rows)
        assertEquals(listOf("day", "a", "b", "day", "c", "day", "d"), items.map { if (it is ThreadItem.Message) it.row.id else "day" })
        val times = items.filterIsInstance<ThreadItem.Day>()
        assertEquals(listOf("t0", "t20", "t1440"), times.map { it.time })
        assertEquals(listOf(noon, noon + 20 * minute, noon + 24 * 60 * minute), times.map { it.dayStartMs })
        assertEquals("every row has its own key", items.size, items.map { it.key }.toSet().size)
        // Without the style: one separator per day, with no time.
        val plain = build(rows, phone = false)
        assertEquals(listOf("day", "a", "b", "c", "day", "d"), plain.map { if (it is ThreadItem.Message) it.row.id else "day" })
        assertTrue(plain.filterIsInstance<ThreadItem.Day>().all { it.time.isEmpty() })
        assertTrue(plain.filterIsInstance<ThreadItem.Message>().all { it.place == null && !it.besideAvatar })
    }

    @Test
    fun timeHeadersOfMessagesAtTheSameTimeKeepKeysOfTheirOwn() {
        // Out of order: a later row a day earlier, then one at the very time of the first.
        val rows = listOf(row("a", noon), row("b", noon - 24 * 60 * minute), row("c", noon))
        val items = build(rows)
        assertEquals(3, items.filterIsInstance<ThreadItem.Day>().size)
        assertEquals(items.size, items.map { it.key }.toSet().size)
    }

    @Test
    fun theLastBubbleOfARunHasTheTail() {
        val rows = listOf(
            row("a1", noon), row("a2", noon + 10_000), row("a3", noon + 20_000),
            // The user answers: another run.
            row("m1", noon + 30_000, mine = true), row("m2", noon + 40_000, mine = true),
            // More than a minute later: a run of its own, though the same sender.
            row("m3", noon + 40_000 + minute + 1, mine = true),
            // A notice is nobody's run, and ends the one before it.
            row("s", noon + 3 * minute, system = true),
            row("a4", noon + 3 * minute + 1_000),
            // A time between them (twenty minutes on): the run does not go on over it.
            row("a5", noon + 24 * minute),
        )
        val places = PhoneStyle.places(rows, dayStart)
        fun of(id: String) = places[rows.indexOfFirst { it.id == id }]
        assertEquals(PhoneStyle.Place(first = true, last = false), of("a1"))
        assertEquals(PhoneStyle.Place(first = false, last = false), of("a2"))
        assertEquals(PhoneStyle.Place(first = false, last = true), of("a3"))
        assertEquals(PhoneStyle.Place(first = true, last = false), of("m1"))
        assertEquals(PhoneStyle.Place(first = false, last = true), of("m2"))
        assertEquals(PhoneStyle.Place(first = true, last = true), of("m3"))
        assertEquals(PhoneStyle.Place(first = true, last = true), of("s"))
        assertEquals(PhoneStyle.Place(first = true, last = true), of("a4"))
        assertEquals(PhoneStyle.Place(first = true, last = true), of("a5"))
        // And the thread's rows carry them.
        val messages = build(rows).filterIsInstance<ThreadItem.Message>()
        assertEquals(places, messages.map { it.place })
        assertEquals(listOf("a3", "m2", "m3", "s", "a4", "a5"), messages.filter { it.place!!.last }.map { it.row.id })
    }

    @Test
    fun inAGroupARunHasItsSendersNameAboveAndPictureBesideItsLastBubble() {
        val rows = listOf(
            row("a1", noon, sender = "alice"), row("a2", noon + 10_000, sender = "alice"),
            // Another person at once: their own run.
            row("b1", noon + 20_000, sender = "ben"),
            row("m1", noon + 30_000, mine = true),
            row("b2", noon + 40_000, sender = "ben"), row("b3", noon + 50_000, sender = "ben"),
            // Ben again after a few minutes: named again.
            row("b4", noon + 5 * minute, sender = "ben"),
        )
        val messages = build(rows, group = true).filterIsInstance<ThreadItem.Message>()
        fun ids(pick: (ThreadItem.Message) -> Boolean) = messages.filter(pick).map { it.row.id }
        assertEquals("the name above the first of a run", listOf("a1", "b1", "b2", "b4"), ids { it.showSender })
        assertEquals("the picture beside the last", listOf("a2", "b1", "b3", "b4"), ids { it.besideAvatar && it.place!!.last })
        assertEquals("others' bubbles leave its room, the user's do not", listOf("a1", "a2", "b1", "b2", "b3", "b4"), ids { it.besideAvatar })
        // One to one: no names, no pictures.
        val single = build(rows, group = false).filterIsInstance<ThreadItem.Message>()
        assertTrue(single.none { it.showSender || it.besideAvatar })
        // A change of place is a change to draw.
        assertFalse(messages[0].sameContent(messages[0].copy(place = PhoneStyle.Place(first = true, last = true))))
        assertFalse(messages[0].sameContent(messages[0].copy(besideAvatar = false)))
    }
}
