package com.lightdecoder.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A sideways swipe along the top bar goes to the neighbouring app. */
class SwitcherSwipeTest {
    @Test
    fun aSwipeToTheLeftIsTheNextAppAndToTheRightTheOneBefore() {
        assertEquals(1, ServiceTabStrip.swipeStep(dx = -200f, dy = 10f, minPx = 100f))
        assertEquals(-1, ServiceTabStrip.swipeStep(dx = 200f, dy = -10f, minPx = 100f))
    }

    @Test
    fun aTapOrAnUpAndDownMovementIsNoSwipe() {
        assertEquals(0, ServiceTabStrip.swipeStep(dx = 5f, dy = 2f, minPx = 100f))
        assertEquals(0, ServiceTabStrip.swipeStep(dx = 150f, dy = 120f, minPx = 100f))
    }

    @Test
    fun theAppsGoRoundAtTheEnds() {
        assertEquals(1, ServiceTabStrip.neighbour(at = 0, count = 4, step = 1))
        assertEquals(0, ServiceTabStrip.neighbour(at = 3, count = 4, step = 1))
        assertEquals(3, ServiceTabStrip.neighbour(at = 0, count = 4, step = -1))
        assertNull(ServiceTabStrip.neighbour(at = 0, count = 1, step = 1))
        assertNull(ServiceTabStrip.neighbour(at = -1, count = 4, step = 1))
    }
}
