package com.lightdecoder.ui.chat

import com.lightdecoder.archive.MessageRow
import com.lightdecoder.chat.Capability

/** Which messages "Edit" is offered for. */
internal object EditRule {
    /**
     * WhatsApp's own apps let a message be edited for 15 minutes. (The
     * bridge allows a little longer, so one opened at the last moment still
     * goes through.)
     */
    const val WINDOW_MS = 15 * 60 * 1000L

    /** One's own sent message, text alone, still young enough, in a service that can edit. */
    fun canEdit(row: MessageRow, capabilities: Set<Capability>, nowMs: Long = System.currentTimeMillis()): Boolean =
        Capability.EDIT in capabilities &&
            !row.isLocal && row.isOutgoing && row.direction != "system" &&
            row.text.isNotBlank() && row.media.isEmpty() &&
            nowMs - row.timestampMs < WINDOW_MS
}
