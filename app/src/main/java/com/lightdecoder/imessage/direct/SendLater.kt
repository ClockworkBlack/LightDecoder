package com.lightdecoder.imessage.direct

import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MessageRow

/**
 * iOS 18's Send Later: a message the user wrote now that Apple sends at a time they picked.
 *
 * Apple does the waiting: the message goes to Apple at once with its time
 * ([uniffi.rust_bridge.ScheduleMode], `schedule = true`); rustpush hands the user's own devices
 * their copy right away and gives Apple's servers the rest, queued by the message's GUID, to send
 * at that time. Nothing here has to be running then. Taking it back ("Cancel Send Later") is
 * [uniffi.rust_bridge.PushChatMessage.Unschedule] sent as that GUID.
 *
 * Until its time the user's message shows "Send Later · <time>" under its bubble; then it is an
 * ordinary sent message (its receipts come as they do for any other). Pure. Tested in SendLaterTest.
 */
object SendLater {

    /** An iPhone schedules at most 14 days ahead. */
    const val MAX_AHEAD_MS = 14L * 24 * 60 * 60 * 1000

    /** The earliest a message can be scheduled: a minute ahead (sooner is just sending it). */
    const val MIN_AHEAD_MS = 60_000L

    /** Whether a time picked for Send Later can be used. */
    enum class Check { OK, TOO_SOON, TOO_FAR }

    fun check(atMs: Long, nowMs: Long): Check = when {
        atMs < nowMs + MIN_AHEAD_MS -> Check.TOO_SOON
        atMs > nowMs + MAX_AHEAD_MS -> Check.TOO_FAR
        else -> Check.OK
    }

    /** When [row] is to be sent by Apple (Send Later), or 0 when it is not scheduled. */
    fun scheduledAt(row: MessageRow): Long =
        if (row.extra.isEmpty() || !row.extra.contains("scheduledAt")) 0 else IMessageExtra.parse(row.extra).scheduledAt

    /**
     * Whether [row] is one of the user's messages still waiting for its Send Later time: not
     * delivered (a receipt means it went), not failed, its time not reached yet.
     */
    fun pending(row: MessageRow, nowMs: Long): Boolean {
        if (!(row.isOutgoing || row.direction == "out") || row.direction == "system") return false
        if (row.localState == LocalState.FAILED || DirectArchive.statusRank(row.status) < 0) return false
        if (DirectArchive.statusRank(row.status) >= DirectArchive.statusRank(DirectArchive.STATUS_DELIVERED)) return false
        return scheduledAt(row) > nowMs
    }

    /**
     * Whether a scheduled [row] can be taken back (or sent now) from here: [pending], and Apple
     * knows it by its GUID (it was confirmed sent, not a local copy still going).
     */
    fun cancellable(row: MessageRow, nowMs: Long): Boolean =
        pending(row, nowMs) && !row.isLocal && DirectArchive.isImessage(row.id)
}
