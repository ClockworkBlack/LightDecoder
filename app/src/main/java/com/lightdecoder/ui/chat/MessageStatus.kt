package com.lightdecoder.ui.chat

import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.relay.Outbox

/** What the little status mark under an outgoing message shows. */
enum class Tick { NONE, PENDING, SENT, DELIVERED, READ, FAILED }

/**
 * Turns the phone's message status (its enum name, e.g. "OUTGOING_DELIVERED")
 * and this app's own sending state into one [Tick].
 *
 * The local state wins while the app is still sending or has given up: the
 * phone has not seen the message yet, so its status says nothing.
 */
object MessageStatus {

    private val PENDING_STATUSES = setOf(
        "OUTGOING_DRAFT",
        "OUTGOING_SEND_AFTER_PROCESSING",
        "OUTGOING_YET_TO_SEND",
        "OUTGOING_SENDING",
        "OUTGOING_RESENDING",
        "OUTGOING_AWAITING_RETRY",
        "OUTGOING_SCHEDULED",
        "OUTGOING_VALIDATING",
        "OUTGOING_REVOCATION_PENDING",
    )

    private val FAILED_STATUSES = setOf(
        "OUTGOING_CANCELED",
        "OUTGOING_RESTRICTED",
        "MESSAGE_STATUS_OUTGOING_FAILED_EMERGENCY_PROTOCOL_DETERMINATION_MESSAGE",
    )

    fun tickFor(row: MessageRow): Tick {
        if (!row.isOutgoing || row.direction != "out") return Tick.NONE
        when (row.localState) {
            LocalState.FAILED -> return Tick.FAILED
            LocalState.PENDING, LocalState.SENDING -> return Tick.PENDING
        }
        if (row.isLocal) return Tick.PENDING
        return tickForStatus(row.status)
    }

    fun tickForStatus(status: String): Tick = when {
        isFailedStatus(status) -> Tick.FAILED
        status == "OUTGOING_DISPLAYED" -> Tick.READ
        status == "OUTGOING_DELIVERED" -> Tick.DELIVERED
        status in PENDING_STATUSES -> Tick.PENDING
        // COMPLETE, NOT_DELIVERED_YET (sent, not yet delivered), unknown and empty.
        else -> Tick.SENT
    }

    /** The phone gave up on this message; only the phone can retry it. */
    fun isFailedStatus(status: String): Boolean =
        status.startsWith("OUTGOING_FAILED") || status in FAILED_STATUSES

    /** A message this app could not hand to the phone; the user can retry or discard it. */
    fun canRetryLocally(row: MessageRow): Boolean =
        row.localState == LocalState.FAILED && row.tmpId.isNotEmpty()

    /**
     * A failed send that may have gone out all the same ([Outbox.outcomeUnknown]):
     * retrying it could deliver it twice, so the user is asked first.
     */
    fun outcomeUnknown(row: MessageRow): Boolean = canRetryLocally(row) && Outbox.outcomeUnknown(row)
}
