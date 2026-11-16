package com.lightdecoder.ui.chat

import android.content.Context
import android.text.format.DateFormat
import com.lightdecoder.R
import java.text.SimpleDateFormat
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

    /** Midnight at the start of the day [timestampMs] falls in, for grouping. */
    fun startOfDay(timestampMs: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = timestampMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /**
     * Whole calendar days from [earlierMs] to [laterMs] (0 = same day), by
     * calendar dates rather than 24-hour spans, so 23:59 and 00:01 are a day
     * apart. Negative if [earlierMs] is in the future.
     */
    fun daysBetween(earlierMs: Long, laterMs: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        val a = Calendar.getInstance(zone).apply { timeInMillis = startOfDay(earlierMs, zone) }
        val b = Calendar.getInstance(zone).apply { timeInMillis = startOfDay(laterMs, zone) }
        // Count by date fields: a day with a daylight-saving change is not 24 h long.
        var days = 0
        if (a.before(b)) {
            while (a.before(b) && days < MAX_DAYS) {
                a.add(Calendar.DAY_OF_MONTH, 1)
                days++
            }
        } else {
            while (b.before(a) && days > -MAX_DAYS) {
                b.add(Calendar.DAY_OF_MONTH, 1)
                days--
            }
        }
        return days
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

    /** Beyond this the exact count does not change any label; it bounds the loop. */
    private const val MAX_DAYS = 400
}
