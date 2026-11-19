package com.lightdecoder.ui.chat

import android.content.Context
import android.text.format.DateFormat
import com.lightdecoder.R
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * How times are written in the messaging screens. The rules follow what
 * people know from phone messaging apps: the list shows the time for today,
 * the weekday within the last week, then the date; a thread groups messages
 * under "Today", "Yesterday" or a date.
 *
 * Every function takes the clock, zone and locale as parameters so the rules
 * can be tested without depending on when or where the tests run.
 */
object TimeFormat {

    /** For the conversation list. */
    fun listTime(
        context: Context,
        timestampMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        if (timestampMs <= 0) return ""
        val days = daysBetween(timestampMs, nowMs, zone)
        return when {
            days == 0 -> clock(context, timestampMs, zone, locale)
            days in 1..6 -> pattern("EEE", timestampMs, zone, locale)
            sameYear(timestampMs, nowMs, zone) -> pattern(skeleton(locale, "MMMd"), timestampMs, zone, locale)
            else -> pattern(skeleton(locale, "yMMMd"), timestampMs, zone, locale)
        }
    }

    /** The time under one message bubble, in the phone's 12/24-hour setting. */
    fun clock(
        context: Context,
        timestampMs: Long,
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val skeleton = if (DateFormat.is24HourFormat(context)) "Hm" else "hm"
        return pattern(skeleton(locale, skeleton), timestampMs, zone, locale)
    }

    /**
     * When one of the user's iMessages was read, as an iPhone writes it under the message
     * ("Read 9:41", "Read Yesterday"): the time today, "Yesterday", then as in the conversation list.
     */
    fun receiptTime(
        context: Context,
        timestampMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
    ): String =
        if (daysBetween(timestampMs, nowMs, zone) == 1) context.getString(R.string.chat_day_yesterday)
        else listTime(context, timestampMs, nowMs, zone, locale)

    /**
     * When a Send Later message goes, as an iPhone writes it under the message: the time today,
     * "Tomorrow 9:00 AM", then the weekday and date with the time.
     */
    fun sendLaterTime(
        context: Context,
        timestampMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val time = clock(context, timestampMs, zone, locale)
        return when (daysBetween(nowMs, timestampMs, zone)) {
            0 -> time
            1 -> context.getString(R.string.imessage_send_later_tomorrow, time)
            else -> pattern(skeleton(locale, "EEEMMMd"), timestampMs, zone, locale) + " " + time
        }
    }

    /** The label of the day header above a group of messages. */
    fun dayLabel(
        context: Context,
        timestampMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val days = daysBetween(timestampMs, nowMs, zone)
        return when {
            days == 0 -> context.getString(R.string.chat_day_today)
            days == 1 -> context.getString(R.string.chat_day_yesterday)
            days in 2..6 -> pattern(skeleton(locale, "EEEEMMMMd"), timestampMs, zone, locale)
            sameYear(timestampMs, nowMs, zone) -> pattern(skeleton(locale, "EEEMMMMd"), timestampMs, zone, locale)
            else -> pattern(skeleton(locale, "yMMMMd"), timestampMs, zone, locale)
        }
    }

    /**
     * When someone was last online, as the line under a conversation's
     * title: "last seen today at 14:05", "last seen yesterday at 14:05",
     * then the weekday or date as in the conversation list.
     */
    fun lastSeen(
        context: Context,
        timestampMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val days = daysBetween(timestampMs, nowMs, zone)
        return when {
            // A little in the future is the other clock running ahead: still today.
            days <= 0 -> context.getString(R.string.chat_presence_last_seen_today, clock(context, timestampMs, zone, locale))
            days == 1 -> context.getString(R.string.chat_presence_last_seen_yesterday, clock(context, timestampMs, zone, locale))
            else -> context.getString(R.string.chat_presence_last_seen, listTime(context, timestampMs, nowMs, zone, locale))
        }
    }

    /** Midnight at the start of the day [timestampMs] falls in, for grouping. */
    fun startOfDay(timestampMs: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val z = zone.toZoneId()
        return Instant.ofEpochMilli(timestampMs).atZone(z).toLocalDate().atStartOfDay(z).toInstant().toEpochMilli()
    }

    /**
     * Whole calendar days from [earlierMs] to [laterMs] (0 = same day), by
     * calendar dates rather than 24-hour spans, so 23:59 and 00:01 are a day
     * apart and a day with a daylight-saving change still counts as one.
     * Negative if [earlierMs] is in the future. Constant time: a thread with
     * hundreds of day headers labels each one with this.
     */
    fun daysBetween(earlierMs: Long, laterMs: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        val z = zone.toZoneId()
        val a = Instant.ofEpochMilli(earlierMs).atZone(z).toLocalDate()
        val b = Instant.ofEpochMilli(laterMs).atZone(z).toLocalDate()
        return ChronoUnit.DAYS.between(a, b).coerceIn(-MAX_DAYS.toLong(), MAX_DAYS.toLong()).toInt()
    }

    private fun sameYear(a: Long, b: Long, zone: TimeZone): Boolean {
        val ca = Calendar.getInstance(zone).apply { timeInMillis = a }
        val cb = Calendar.getInstance(zone).apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
    }

    private fun skeleton(locale: Locale, skeleton: String): String =
        DateFormat.getBestDateTimePattern(locale, skeleton)

    private fun pattern(pattern: String, timestampMs: Long, zone: TimeZone, locale: Locale): String =
        SimpleDateFormat(pattern, locale).apply { timeZone = zone }.format(Date(timestampMs))

    /** Beyond this the exact count does not change any label. */
    private const val MAX_DAYS = 400
}
