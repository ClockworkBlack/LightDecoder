package com.lightdecoder.imessage.direct

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A transfer's progress, passed on sparingly and never thrown back into the library; transfers the user may stop. */
class ProgressThrottleTest {

    @Test
    fun progressGoesAtMostEveryFewHundredMillisecondsAndOnlyWhenItMoved() {
        var now = 0L
        val out = ArrayList<Pair<Long, Long>>()
        val t = ProgressThrottle(minMs = 200, minFraction = 0.01, clock = { now }) { d, total -> out += d to total }
        t.offer(0, 1000) // the first goes
        now = 50; t.offer(100, 1000) // too soon
        now = 250; t.offer(105, 1000) // time enough, moved enough
        now = 300; t.offer(300, 1000) // moved enough, too soon
        now = 460; t.offer(108, 1000) // time enough, moved too little since the last that went
        assertEquals(listOf(0L to 1000L, 105L to 1000L), out.toList())
        // The last held back goes when it ends.
        t.finish()
        assertEquals(108L to 1000L, out.last())
        t.offer(400, 1000)
        assertEquals("nothing after its end", 3, out.size)
    }

    @Test
    fun theFinalValueAlwaysGoesAndOnlyOnce() {
        var now = 0L
        val out = ArrayList<Pair<Long, Long>>()
        val t = ProgressThrottle(clock = { now }) { d, total -> out += d to total }
        t.offer(0, 10)
        now = 1; t.offer(10, 10)
        now = 2; t.offer(10, 10)
        t.finish()
        assertEquals(listOf(0L to 10L, 10L to 10L), out)
    }

    @Test
    fun aThrowingEmitOrClockIsSwallowed() {
        val calls = AtomicInteger()
        val t = ProgressThrottle(clock = { 0L }) { _, _ ->
            calls.incrementAndGet()
            throw OutOfMemoryError("ui")
        }
        t.offer(1, 2)
        t.offer(2, 2)
        t.finish()
        assertEquals(2, calls.get())
        ProgressThrottle(clock = { throw StackOverflowError() }) { _, _ -> }.offer(1, 2)
    }

    @Test
    fun theComposedCallbackKeepsTheCapAndNeverThrows() {
        val over = AtomicInteger()
        val ui = ArrayList<Long>()
        val p = TransferProgress(maxBytes = 100, onOver = { over.incrementAndGet() }, ui = ProgressThrottle(minMs = 0, minFraction = 0.0, clock = { 0L }) { d, _ -> ui += d })
        p.onProgress(10UL, 100UL)
        p.onProgress(50UL, 100UL)
        assertEquals(listOf(10L, 50L), ui)
        // Past the cap: stopped once, nothing more shown.
        p.onProgress(150UL, 100UL)
        p.onProgress(200UL, 100UL)
        assertEquals(1, over.get())
        assertTrue(p.over.get())
        assertEquals(listOf(10L, 50L), ui)
        // A total past the cap stops it too; a huge number is no crash.
        val q = TransferProgress(maxBytes = 100, onOver = { over.incrementAndGet() })
        q.onProgress(1UL, ULong.MAX_VALUE)
        assertTrue(q.over.get())
        // Whatever its parts throw stays in it.
        TransferProgress(maxBytes = 10, onOver = { throw OutOfMemoryError() }).onProgress(20UL, 20UL)
        TransferProgress(maxBytes = null, ui = ProgressThrottle { _, _ -> throw IllegalStateException() }).onProgress(ULong.MAX_VALUE, ULong.MAX_VALUE)
        TransferProgress(maxBytes = null).onProgress(1UL, 2UL)
    }

    @Test
    fun aSendIsStoppedBeforeItGoesToAppleAndNotAfter() = runBlocking {
        val transfers = Transfers()
        // Before its commit: stopped, and its request answers so (not as cancelled work).
        val started = CompletableDeferred<Unit>()
        val before = async(Dispatchers.Default) {
            runCatching {
                transfers.run("tmp:a") { _ ->
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        started.await()
        assertTrue(transfers.isRunning("tmp:a"))
        assertTrue(transfers.cancel("tmp:a"))
        val outcome = withTimeout(5_000) { before.await() }
        assertTrue(outcome.exceptionOrNull().toString(), outcome.exceptionOrNull() is Transfers.Stopped)
        assertFalse("gone once it ended", transfers.isRunning("tmp:a"))
        // After its commit: refused, and it goes on.
        val committed = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val after = async(Dispatchers.Default) {
            transfers.run("tmp:b") { h ->
                assertTrue(h.commit())
                committed.complete(Unit)
                finish.await()
                "sent"
            }
        }
        committed.await()
        assertFalse(transfers.cancel("tmp:b"))
        finish.complete(Unit)
        assertEquals("sent", withTimeout(5_000) { after.await() })
        // None by that key: refused.
        assertFalse(transfers.cancel("tmp:none"))
    }

    @Test
    fun aSendStoppedBeforeItsCommitNeverGoes() = runBlocking {
        val transfers = Transfers()
        val gate = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<Unit>()
        val sent = AtomicInteger()
        val job = async(Dispatchers.Default) {
            runCatching {
                transfers.run("tmp:c") { h ->
                    ready.complete(Unit)
                    // Uploading, in a part that does not see the cancellation (as a library call may not).
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
                    if (!h.commit()) throw Transfers.Stopped()
                    sent.incrementAndGet()
                }
            }
        }
        ready.await()
        assertTrue(transfers.cancel("tmp:c"))
        gate.complete(Unit)
        val r = withTimeout(5_000) { job.await() }
        assertTrue(r.exceptionOrNull() is Transfers.Stopped)
        assertEquals("never handed to Apple", 0, sent.get())
    }

    @Test
    fun aTransferWithoutAKeyCannotBeStopped() = runBlocking {
        val transfers = Transfers()
        assertEquals(1, transfers.run(null) { 1 })
        try {
            transfers.run(null) { throw IllegalStateException("its own failure") }
            fail()
        } catch (e: IllegalStateException) {
            assertEquals("its own failure", e.message)
        }
    }
}
