package com.lightdecoder.imessage

import com.lightdecoder.archive.MessageRow

/**
 * "Check iMessage": does a message to this person really go as an iMessage,
 * blue on their iPhone? No test machine can sign into iMessage, so the
 * check uses the iPhone itself: a check message is sent as an iMessage and
 * the person is asked to reply. An iPhone replies in the thread it shows,
 * by iMessage if that thread is blue (the reply arrives through OpenBubbles)
 * or by SMS if it is green (it arrives through Google Messages), so the
 * reply's way in tells the colour the person sees.
 *
 * Worked out from the two archives only, so it is the same on the phone and
 * in tests.
 */
object IMessageCheck {

    /** How the person's reply came. */
    enum class Reply {
        /** No reply since the check was sent. */
        NONE,

        /** By iMessage: the thread is blue on their iPhone. */
        IMESSAGE,

        /** By text: the thread is green on their iPhone (or they have no iPhone). */
        TEXT,
    }

    data class Progress(
        /** LightDecoder handed the check to OpenBubbles (through its Reply button, or its screen). */
        val handedOver: Boolean,
        /** OpenBubbles shows it in its iMessage chat: it went to Apple as an iMessage. */
        val inOpenBubbles: Boolean,
        /** It went out as a text through Google Messages instead (should never happen). */
        val sentAsText: Boolean,
        val reply: Reply,
    ) {
        val done: Boolean get() = reply != Reply.NONE
    }

    /** The check message: [code] tells it apart from anything else in the chat. */
    fun text(code: String): String = "LightDecoder iMessage check $code. Please reply OK."

    /** A four-digit code. */
    fun newCode(random: kotlin.random.Random = kotlin.random.Random.Default): String =
        random.nextInt(1000, 10000).toString()

    /**
     * Where a check of [text] sent at [sentAtMs] has got to.
     *
     * @param iMessageRows the person's iMessage chat (newest last or not; order does not matter)
     * @param textRows     the person's Google Messages conversation
     */
    fun progress(text: String, sentAtMs: Long, iMessageRows: List<MessageRow>, textRows: List<MessageRow>): Progress {
        // OpenBubbles stamps its lines in whole seconds: allow for it.
        val since = sentAtMs - CLOCK_SLACK_MS
        val mine = iMessageRows.filter { it.isOutgoing && it.text.trim() == text && it.timestampMs >= since }
        val echoed = mine.any { !it.id.startsWith(SENT_PREFIX) }
        val firstIMessageReply = iMessageRows.filter { !it.isOutgoing && it.timestampMs >= sentAtMs }.minOfOrNull { it.timestampMs }
        val firstTextReply = textRows.filter { !it.isOutgoing && it.direction != "out" && it.timestampMs >= sentAtMs }.minOfOrNull { it.timestampMs }
        val reply = when {
            firstIMessageReply != null && (firstTextReply == null || firstIMessageReply <= firstTextReply) -> Reply.IMESSAGE
            firstTextReply != null -> Reply.TEXT
            else -> Reply.NONE
        }
        return Progress(
            handedOver = mine.isNotEmpty(),
            inOpenBubbles = echoed,
            sentAsText = textRows.any { it.isOutgoing && it.text.trim() == text && it.timestampMs >= since },
            reply = reply,
        )
    }

    private const val CLOCK_SLACK_MS = 2_000L

    /** IMessageManager's id prefix for a message sent from here that OpenBubbles has not shown back yet. */
    private const val SENT_PREFIX = "ld:"
}
