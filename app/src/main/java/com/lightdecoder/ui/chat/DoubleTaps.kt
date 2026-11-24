package com.lightdecoder.ui.chat

/**
 * Double-tap a bubble to react, told from a single tap by clicks alone (no gesture detector takes
 * the bubble's touches: long press, the ripple, swipe to reply and TalkBack stay as they are). A tap
 * on a row whose double-tap is on ([onTap]) waits [timeoutMs]: a second tap on the same row within
 * that time is a double tap (its single tap never runs); else the single tap runs then. Taps on two
 * different rows never pair.
 *
 * [clock] is the time in ms; [post] runs a task after a delay and [cancel] takes a posted one back
 * (a Handler's, on the main thread). Pure. Tested in DoubleTapsTest.
 */
class DoubleTaps(
    private val timeoutMs: Long,
    private val clock: () -> Long,
    private val post: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
) {
    private var lastKey: String? = null
    private var lastAt = 0L
    private var pending: Runnable? = null

    /**
     * A tap on the row [key]: [double] when it is the second within [timeoutMs] on that row, else
     * [single] (null: nothing) once [timeoutMs] has passed with no second one.
     */
    fun onTap(key: String, single: (() -> Unit)?, double: () -> Unit) {
        val now = clock()
        val waiting = pending
        if (waiting != null && lastKey == key && now - lastAt <= timeoutMs) {
            cancel(waiting)
            pending = null
            lastKey = null
            double()
            return
        }
        lastKey = key
        lastAt = now
        val task = object : Runnable {
            override fun run() {
                if (pending === this) {
                    pending = null
                    lastKey = null
                }
                single?.invoke()
            }
        }
        pending = task
        post(task, timeoutMs)
    }

    /** A single tap still waiting is dropped (the list went, or another chat is shown). */
    fun reset() {
        pending?.let(cancel)
        pending = null
        lastKey = null
    }
}
