package com.lightdecoder.imessage.madrid

import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** An extension app's open games: locks, quiet, the end of a session, its app's death; and each move sent once. */
class MadridSessionsTest {

    private val pkg = "com.openbubbles.openpigeon"
    private val chat = "imsg:tel:+15550000002"

    private fun handle(id: Int, session: String = "S1", conversation: String = chat, app: String = pkg) =
        MadridSessions.Handle(id, session, "imsg:ROOT", conversation, app)

    @Test
    fun aTappedCardIsOpenForAWhileThenGoes() {
        val s = MadridSessions(graceMs = 100)
        s.engage(handle(1), now = 0)
        assertTrue(s.isEngaged("S1", 50))
        assertTrue(s.acceptsMoves(1, 50))
        assertFalse(s.releaseIfIdle("S1", 50))
        assertFalse(s.isEngaged("S1", 100))
        assertFalse(s.acceptsMoves(1, 100))
        assertTrue(s.releaseIfIdle("S1", 100))
        assertNull(s.handle(1))
        assertFalse("unknown handles are refused", s.lock(1, 100))
    }

    @Test
    fun aLockedGameStaysOpenAndHandlesAreCountedNotFlagged() {
        val s = MadridSessions(graceMs = 100)
        s.engage(handle(1), now = 0)
        s.engage(handle(2), now = 0)
        assertTrue(s.lock(1, 10))
        // OpenPigeon locks the new handle before it unlocks the old one.
        assertTrue(s.lock(2, 20))
        assertTrue(s.unlock(1, 30))
        assertTrue("still locked by the other", s.isEngaged("S1", 10_000))
        assertFalse(s.releaseIfIdle("S1", 10_000))
        assertTrue(s.unlock(2, 10_000))
        assertTrue("a grace after the last unlock", s.isEngaged("S1", 10_050))
        assertFalse(s.isEngaged("S1", 10_100))
    }

    @Test
    fun quietIsTakenBeforeTheLockAndEndsWithTheLastUnlock() {
        val s = MadridSessions(graceMs = 100)
        s.engage(handle(1), now = 0)
        s.engage(handle(9, session = "S2", conversation = "imsg:other"), now = 0)
        assertTrue(s.suppress(1, true, 5))
        assertTrue(s.suppressed(chat, 6))
        assertFalse("another chat is not quiet", s.suppressed("imsg:other", 6))
        s.lock(1, 7)
        s.unlock(1, 8)
        assertFalse(s.suppressed(chat, 9))
        s.suppress(1, true, 10)
        s.suppress(1, false, 11)
        assertFalse(s.suppressed(chat, 12))
        assertFalse("unknown handles are refused", s.suppress(42, true, 12))
    }

    @Test
    fun quietAskedLongAgoIsNotKept() {
        val s = MadridSessions(graceMs = 100, suppressMaxMs = 1_000)
        s.engage(handle(1), now = 0)
        s.lock(1, 0)
        s.suppress(1, true, 0)
        assertTrue(s.suppressed(chat, 999))
        assertFalse(s.suppressed(chat, 1_000))
    }

    @Test
    fun theAppsDeathEndsItsLocksQuietAndSessions() {
        val s = MadridSessions(graceMs = 100)
        s.engage(handle(1), now = 0)
        s.engage(handle(2, session = "S2", app = "other.app"), now = 0)
        s.lock(1, 0)
        s.suppress(1, true, 0)
        s.lock(2, 0)
        s.died(pkg)
        assertFalse(s.suppressed(chat, 1))
        assertFalse(s.isEngaged("S1", 1))
        assertFalse(s.acceptsMoves(1, 1))
        assertTrue("another app's game goes on", s.isEngaged("S2", 1_000))
        assertEquals(pkg, s.pkgOf("S1"))
        assertTrue(s.releaseIfIdle("S1", 1))
        assertNull(s.pkgOf("S1"))
    }

    @Test
    fun aMoveAskedAgainWaitsForTheFirstSend() {
        val d = MoveDedupe<Boolean>(keepMs = 1_000) { it }
        var started = 0
        val first = CompletableDeferred<Boolean>()
        val a = d.join("S1", "data:1", 0) { started++; first }
        val b = d.join("S1", "data:1", 10) { started++; CompletableDeferred() }
        assertSame(a, b)
        assertEquals(1, started)
        // Another move, or the same in another session, is a send of its own.
        assertNotSame(a, d.join("S1", "data:2", 10) { started++; CompletableDeferred() })
        assertNotSame(a, d.join("S2", "data:1", 10) { started++; CompletableDeferred() })
        assertEquals(3, started)
        first.complete(true)
        assertSame("sent: asked again soon after, it is not sent again", a, d.join("S1", "data:1", 500) { started++; CompletableDeferred() })
        assertEquals(3, started)
        val later = d.join("S1", "data:1", 1_600) { started++; CompletableDeferred(true) }
        assertNotSame("long after, it is a new move", a, later)
        assertEquals(4, started)
    }

    @Test
    fun aMoveThatFailedGoesAgainWhenAskedAgain() {
        val d = MoveDedupe<Boolean>(keepMs = 1_000) { it }
        val failed = CompletableDeferred(false)
        assertSame(failed, d.join("S1", "data:1", 0) { failed })
        val again = CompletableDeferred<Boolean>()
        assertSame(again, d.join("S1", "data:1", 10) { again })
    }

    @Test
    fun aMoveThatMayHaveGoneIsNotSentAgainBlindly() {
        // Kept: sent, or not confirmed in time.
        val d = MoveDedupe<Pair<Boolean, Boolean>>(keepMs = 1_000) { (sent, unconfirmed) -> sent || unconfirmed }
        val unsure = CompletableDeferred(false to true)
        assertSame(unsure, d.join("S1", "data:1", 0) { unsure })
        assertSame(unsure, d.join("S1", "data:1", 10) { CompletableDeferred(true to false) })
        val refused = CompletableDeferred(false to false)
        assertSame(refused, d.join("S1", "data:2", 20) { refused })
        val again = CompletableDeferred(true to false)
        assertSame(again, d.join("S1", "data:2", 30) { again })
    }

    @Test
    fun movesOfOneSessionAreLetGoAtMostOnceEveryTwoSeconds() {
        val s = MadridSessions(moveIntervalMs = 2_000)
        s.engage(handle(1), 0)
        s.engage(handle(2, session = "S2"), 0)
        assertTrue(s.takeMoveSlot(1, 100))
        assertFalse("too soon", s.takeMoveSlot(1, 1_500))
        assertTrue("another session is not held back", s.takeMoveSlot(2, 1_500))
        assertTrue(s.takeMoveSlot(1, 2_100))
        assertFalse("a handle not known", s.takeMoveSlot(9, 5_000))
    }

    @Test
    fun aHandleNeverHandedToTheAppIsForgottenWithItsSessionUnlessAnotherHasIt() {
        val s = MadridSessions()
        s.engage(MadridSessions.Handle(1, "S1", "imsg:R1", "imsg:c", "pkg"), 0)
        // The app could not be reached: nothing of it is kept.
        s.forget(1)
        assertNull(s.handle(1))
        assertNull(s.pkgOf("S1"))
        assertFalse(s.isEngaged("S1", 1))
        // Opened again meanwhile: the session stays, with the handle given out since.
        s.engage(MadridSessions.Handle(2, "S2", "imsg:R2", "imsg:c", "pkg"), 0)
        s.engage(MadridSessions.Handle(3, "S2", "imsg:R2", "imsg:c", "pkg"), 10)
        s.forget(2)
        assertNull(s.handle(2))
        assertEquals("S2", s.handle(3)!!.session)
        assertTrue(s.isEngaged("S2", 20))
        // A handle not known: nothing happens.
        s.forget(9)
        assertEquals("pkg", s.pkgOf("S2"))
    }
}
