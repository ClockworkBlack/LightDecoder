package com.lightdecoder.ui.chat

import com.lightdecoder.archive.MediaRow
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.imessage.direct.IMessageExtra
import com.lightdecoder.ui.share.SharedContent

/**
 * "Forward" (the long-press sheet): what of a message goes into another conversation's composer, for
 * the user to look at and send. Its text as plain text (one sent with invisible ink too, as an iPhone
 * forwards it), and its files: a voice message as its audio file, a sticker as its picture, a Live
 * Photo as its still only, the pictures of a stack all (up to [SharedContent.MAX_STREAMS]), sent there
 * as one batch again where the chat sends batches.
 *
 * Not forwarded: a local copy (waiting, sending or failed), a row of the chat's own, a failed message,
 * an iMessage app's card (a poll, a game), and a FaceTime call's row.
 *
 * Pure. Tested in ForwardContentTest.
 */
object ForwardContent {

    /** Whether [row] can be forwarded at all ([text] or [parts] to give). */
    fun forwardable(row: MessageRow, extra: IMessageExtra = IMessageExtra.parse(row.extra)): Boolean {
        if (row.isLocal || row.direction == "system" || row.id.startsWith(CALL_PREFIX)) return false
        if (row.localState == com.lightdecoder.archive.LocalState.FAILED || MessageStatus.isFailedStatus(row.status)) return false
        if (extra.app != null || extra.call != null) return false
        return text(row).isNotBlank() || parts(row, extra).isNotEmpty()
    }

    /** The text that goes: the message's own, as plain text. */
    fun text(row: MessageRow): String = row.text

    /** The files that go, in order: what is shown of the message (never a Live Photo's moving part), [SharedContent.MAX_STREAMS] at most. */
    fun parts(row: MessageRow, extra: IMessageExtra = IMessageExtra.parse(row.extra)): List<MediaRow> =
        row.shownMedia(extra).take(SharedContent.MAX_STREAMS)

    private const val CALL_PREFIX = "ftcall:"
}
