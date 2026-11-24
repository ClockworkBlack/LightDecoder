package com.lightdecoder.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** A bubble's single tap told from its double tap, by clicks alone. */
class DoubleTapsTest {

    private var now = 0L
    private val posted = ArrayList<Pair<Runnable, Long>>()
    private val taps = DoubleTaps(300, { now }, { r, d -> posted += r to (now + d) }, { r -> posted.removeAll { it.first === r } })
    private val events = ArrayList<String>()

    /** Runs what was posted for [ms] from now. */
    private fun advance(ms: Long) {
        now += ms
        val due = posted.filter { it.second <= now }
        posted.removeAll(due)
        due.forEach { it.first.run() }
    }

    private fun tap(key: String) = taps.onTap(key, { events += "single:$key" }, { events += "double:$key" })

    @Test
    fun aSingleTapRunsAfterTheTimeout() {
        tap("a")
        assertEquals(emptyList<String>(), events)
        advance(299)
        assertEquals(emptyList<String>(), events)
        advance(1)
        assertEquals(listOf("single:a"), events)
    }

    @Test
    fun aDoubleTapRunsOnceAndNeverItsSingleTap() {
        tap("a")
        advance(120)
        tap("a")
        advance(1_000)
        assertEquals(listOf("double:a"), events)
        // A third tap starts again.
        tap("a")
        advance(400)
        assertEquals(listOf("double:a", "single:a"), events)
    }

    @Test
    fun twoRowsDoNotPair() {
        tap("a")
        advance(100)
        tap("b")
        advance(400)
        assertEquals(listOf("single:a", "single:b"), events)
    }

    @Test
    fun tooSlowIsTwoSingleTaps() {
        tap("a")
        advance(350)
        tap("a")
        advance(350)
        assertEquals(listOf("single:a", "single:a"), events)
    }

    @Test
    fun aNullSingleTapDoesNothingAndResetDropsAWaitingOne() {
        taps.onTap("ink", null) { events += "double" }
        advance(400)
        assertEquals(emptyList<String>(), events)
        tap("a")
        taps.reset()
        advance(400)
        assertEquals(emptyList<String>(), events)
    }
}
