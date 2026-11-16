package com.lightdecoder.ui.chat

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.R
import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MediaRow
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.relay.Outbox
import com.lightdecoder.relay.RelayException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** The messaging screens' pure helpers: times, avatars, delivery ticks, thread rows, number input. */
@RunWith(AndroidJUnit4::class)
class ChatHelpersTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(zone: TimeZone, year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute)
        }.timeInMillis

    // --- TimeFormat ------------------------------------------------------------

    @Test
    fun daysBetweenCountsCalendarDays() {
        val lateNight = at(utc, 2026, 11, 15, 23, 59)
        val earlyMorning = at(utc, 2026, 11, 16, 0, 1)
        assertEquals(1, TimeFormat.daysBetween(lateNight, earlyMorning, utc))
        assertEquals(0, TimeFormat.daysBetween(earlyMorning, earlyMorning + 3_600_000, utc))
        assertEquals(-1, TimeFormat.daysBetween(earlyMorning, lateNight, utc))

        // A 23-hour day (daylight saving starts on 8 March 2026 in New York) is still one day.
        val ny = TimeZone.getTimeZone("America/New_York")
        assertEquals(2, TimeFormat.daysBetween(at(ny, 2026, 3, 7, 12), at(ny, 2026, 3, 9, 0, 30), ny))
    }

    @Test
    fun dayArithmeticIsConstantTimeAndStillCalendarBased() {
        val ny = TimeZone.getTimeZone("America/New_York")
        // Midnight on the day daylight saving starts, and on the day after (a 23-hour day apart).
        assertEquals(at(ny, 2026, 3, 8, 0), TimeFormat.startOfDay(at(ny, 2026, 3, 8, 15, 30), ny))
        assertEquals(at(ny, 2026, 3, 9, 0), TimeFormat.startOfDay(at(ny, 2026, 3, 9, 0, 1), ny))
        assertEquals(1, TimeFormat.daysBetween(at(ny, 2026, 11, 1, 0, 30), at(ny, 2026, 11, 2, 23, 30), ny))
        // Far apart: clamped like before (no label changes beyond a year), and answered at once.
        val start = System.nanoTime()
        repeat(10_000) { assertEquals(400, TimeFormat.daysBetween(at(utc, 2001, 1, 1, 0), at(utc, 2026, 1, 1, 0), utc)) }
        assertEquals(-400, TimeFormat.daysBetween(at(utc, 2026, 1, 1, 0), at(utc, 2001, 1, 1, 0), utc))
        assertTrue("10k far-apart day counts took too long", System.nanoTime() - start < 5_000_000_000L)
    }

    @Test
    fun listTimeNarrowsWithAge() {
        val now = at(utc, 2026, 11, 16, 15)
        val today = TimeFormat.listTime(context, at(utc, 2026, 11, 16, 9, 5), now, utc, Locale.US)
        assertEquals(TimeFormat.clock(context, at(utc, 2026, 11, 16, 9, 5), utc, Locale.US), today)
        assertEquals("Fri", TimeFormat.listTime(context, at(utc, 2026, 11, 13, 9), now, utc, Locale.US))
        assertEquals("Mar 3", TimeFormat.listTime(context, at(utc, 2026, 3, 3, 9), now, utc, Locale.US))
        assertTrue(TimeFormat.listTime(context, at(utc, 2024, 3, 3, 9), now, utc, Locale.US).contains("2024"))
        assertEquals("", TimeFormat.listTime(context, 0, now, utc, Locale.US))
    }

    @Test
    fun dayLabels() {
        val now = at(utc, 2026, 11, 16, 15)
        assertEquals(context.getString(R.string.chat_day_today), TimeFormat.dayLabel(context, now - 60_000, now, utc, Locale.US))
        assertEquals(
            context.getString(R.string.chat_day_yesterday),
            TimeFormat.dayLabel(context, at(utc, 2026, 11, 15, 1), now, utc, Locale.US),
        )
        assertTrue(TimeFormat.dayLabel(context, at(utc, 2025, 12, 24, 1), now, utc, Locale.US).contains("2025"))
    }

    // --- Avatars ----------------------------------------------------------------

    @Test
    fun initials() {
        assertEquals("AL", Avatars.initials("Ada Lovelace"))
        assertEquals("AB", Avatars.initials("  ada   maria   byron "))
        assertEquals("A", Avatars.initials("ada"))
        assertEquals("ÉZ", Avatars.initials("Émile Zola"))
        assertEquals("#", Avatars.initials("+1 555 0100"))
        assertEquals("#", Avatars.initials("(555) 0100"))
        assertEquals("?", Avatars.initials(""))
        assertEquals("A", Avatars.initials("Ada 2"))
    }

    @Test
    fun colorsAreStableAndReadable() {
        assertEquals(0xFF112233.toInt(), Avatars.colorFor("any", "#112233"))
        assertEquals(Avatars.colorFor("conv-1", ""), Avatars.colorFor("conv-1", "not a colour"))
        assertNull(Avatars.parseColor("red"))
        // Every palette colour carries white initials at large-text contrast or better.
        for (i in 0 until 200) {
            val color = Avatars.colorFor("conversation $i", "")
            assertEquals(Color.WHITE, Avatars.textColorOn(color))
            assertTrue(ColorUtils.calculateContrast(Color.WHITE, color) >= 3.0)
        }
        assertEquals(Color.BLACK, Avatars.textColorOn(0xFFFFEB3B.toInt()))
    }

    // --- MessageStatus ----------------------------------------------------------

    private fun message(
        id: String = "m1",
        tmpId: String = "",
        outgoing: Boolean = true,
        direction: String = if (outgoing) "out" else "in",
        status: String = "",
        localState: String = LocalState.SYNCED,
        senderId: String = "p1",
        timestampMs: Long = at(utc, 2026, 11, 16, 9),
        replyToId: String = "",
        text: String = "hi",
    ) = MessageRow(
        id = id, conversationId = "c1", tmpId = tmpId, senderId = senderId, senderName = "Sender",
        isOutgoing = outgoing, timestampMs = timestampMs, direction = direction, status = status, statusCode = 0,
        errorText = "", transport = "RCS", text = text, subject = "", replyToId = replyToId,
        reactions = emptyList(), localState = localState, media = emptyList(), updatedAt = 0,
    )

    @Test
    fun ticks() {
        assertEquals(Tick.NONE, MessageStatus.tickFor(message(outgoing = false, status = "INCOMING_COMPLETE")))
        assertEquals(Tick.PENDING, MessageStatus.tickFor(message(id = "tmp:a", tmpId = "a", localState = LocalState.PENDING)))
        assertEquals(Tick.PENDING, MessageStatus.tickFor(message(id = "tmp:a", tmpId = "a", localState = LocalState.SENDING)))
        assertEquals(Tick.FAILED, MessageStatus.tickFor(message(id = "tmp:a", tmpId = "a", localState = LocalState.FAILED)))
        assertEquals(Tick.SENT, MessageStatus.tickFor(message(status = "OUTGOING_COMPLETE")))
        assertEquals(Tick.DELIVERED, MessageStatus.tickFor(message(status = "OUTGOING_DELIVERED")))
        assertEquals(Tick.READ, MessageStatus.tickFor(message(status = "OUTGOING_DISPLAYED")))
        assertEquals(Tick.PENDING, MessageStatus.tickFor(message(status = "OUTGOING_YET_TO_SEND")))
        assertEquals(Tick.FAILED, MessageStatus.tickFor(message(status = "OUTGOING_FAILED_GENERIC")))
        assertEquals(Tick.FAILED, MessageStatus.tickFor(message(status = "OUTGOING_CANCELED")))
        assertEquals(Tick.SENT, MessageStatus.tickFor(message(status = "SOMETHING_NEW")))

        assertTrue(MessageStatus.canRetryLocally(message(id = "tmp:a", tmpId = "a", localState = LocalState.FAILED)))
        assertFalse(MessageStatus.canRetryLocally(message(status = "OUTGOING_FAILED_GENERIC")))

        // A failed send that may have gone out all the same is asked about before a retry.
        val failed = message(id = "tmp:a", tmpId = "a", localState = LocalState.FAILED)
        for (reason in listOf(Outbox.UNCONFIRMED, Outbox.INTERRUPTED, RelayException.PHONE_NOT_RESPONDING)) {
            assertTrue(reason, MessageStatus.outcomeUnknown(failed.copy(errorText = reason)))
        }
        for (reason in listOf("", RelayException.SEND_FAILED, RelayException.TOO_LARGE, "FAILURE_4")) {
            assertFalse(reason, MessageStatus.outcomeUnknown(failed.copy(errorText = reason)))
        }
        assertFalse(MessageStatus.outcomeUnknown(failed.copy(localState = LocalState.PENDING, errorText = Outbox.UNCONFIRMED)))
        assertFalse(MessageStatus.outcomeUnknown(message(status = "OUTGOING_FAILED_GENERIC").copy(errorText = Outbox.UNCONFIRMED)))
    }

    // --- ThreadItems ------------------------------------------------------------

    @Test
    fun threadRows() {
        val day1 = at(utc, 2026, 11, 15, 10)
        val day2 = at(utc, 2026, 11, 16, 10)
        val rows = listOf(
            message(id = "a", outgoing = false, senderId = "p1", timestampMs = day1),
            message(id = "b", outgoing = false, senderId = "p1", timestampMs = day1 + 1000),
            message(id = "c", outgoing = false, senderId = "p2", timestampMs = day1 + 2000, replyToId = "a"),
            message(id = "d", tmpId = "t1", outgoing = true, timestampMs = day2),
        )
        val quotes = mapOf("a" to Quote("a", "Sender", "hi"))
        val items = ThreadItems.build(
            rows, isGroup = true, quotes = quotes, header = null, highlightId = "b",
            dayStart = { TimeFormat.startOfDay(it, utc) }, dayLabel = { "day $it" },
        )
        assertEquals(listOf("day", "m", "m", "m", "day", "m"), items.map { if (it is ThreadItem.Day) "day" else "m" })
        val messages = items.filterIsInstance<ThreadItem.Message>()
        assertEquals(listOf(true, false, true, false), messages.map { it.showSender })
        assertEquals(quotes["a"], messages[2].quote)
        assertTrue(messages[1].highlighted)
        // The sent message is keyed by its tmpId, so the local copy and the phone's echo share a row.
        assertEquals("t:t1", messages[3].key)
        assertEquals(ThreadItems.keyFor(message(id = "tmp:t1", tmpId = "t1")), messages[3].key)

        // One-to-one conversations never show sender names.
        val single = ThreadItems.build(rows, false, emptyMap(), null, null, { 0L }, { "" })
        assertTrue(single.filterIsInstance<ThreadItem.Message>().none { it.showSender })
    }

    @Test
    fun threadKeysStayUnique() {
        val rows = listOf(message(id = "x", tmpId = "same"), message(id = "y", tmpId = "same"))
        val keys = ThreadItems.build(rows, false, emptyMap(), ThreadItem.Header(false, true), null, { 0L }, { "" })
            .map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    private fun part(localPath: String = "") = MediaRow(
        messageId = "m1", partId = "p0", mediaId = "media", key = byteArrayOf(1, 2), thumbMediaId = "",
        thumbKey = ByteArray(0), mime = "image/heic", fileName = "", size = 10, width = 0, height = 0,
        inlineData = ByteArray(0), needsFullSize = false, localPath = localPath, thumbPath = "",
        fullSizeRequested = false, stickerChecked = false,
    )

    @Test
    fun sameContentIgnoresByteArrayIdentity() {
        // Each reload reads fresh byte arrays; equals() would call every message changed.
        val first = message().copy(media = listOf(part()))
        val reloaded = message().copy(media = listOf(part()))
        assertNotEquals(first, reloaded)
        val a = ThreadItem.Message(first, "m:m1", null, false, false)
        val b = ThreadItem.Message(reloaded, "m:m1", null, false, false)
        assertTrue(a.sameContent(b))
        assertFalse(a.sameContent(b.copy(row = reloaded.copy(status = "OUTGOING_DELIVERED"))))
        assertFalse(a.sameContent(b.copy(row = reloaded.copy(media = listOf(part(localPath = "/x.heic"))))))
    }

    // --- new conversation input -------------------------------------------------

    @Test
    fun phoneNumberInput() {
        assertTrue(NewConversationActivity.looksLikeNumber("+1 (555) 010-0123"))
        assertTrue(NewConversationActivity.looksLikeNumber("555"))
        assertFalse(NewConversationActivity.looksLikeNumber("55"))
        assertFalse(NewConversationActivity.looksLikeNumber("Bob 555"))
        assertFalse(NewConversationActivity.looksLikeNumber("1+555"))
    }
}
