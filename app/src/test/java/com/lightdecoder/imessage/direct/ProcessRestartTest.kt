package com.lightdecoder.imessage.direct

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.imessage.facetime.withLockWithin
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The ":imessage" process's restart for the Apple Account ([ProcessRestart.whenFree], what
 * [DirectEngine.restartForAccount] runs), in this order: iMessage's service started first (refused:
 * nothing else at all); then a start or a registration under way let finish (the engine's lock), no
 * FaceTime join (the calls' guard) and no call on (looked at again under it), still wanted, the main
 * process's requests being answered let finish (5 s at most), the event being read let finish, the
 * last things done, the end.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ProcessRestartTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val order = Collections.synchronizedList(ArrayList<String>())

    @After
    fun tearDown() {
        DirectPrefs.setRegistered(context, false)
    }

    private fun setUpImessage() {
        DirectPrefs.setRegistered(context, true)
        DirectLibrary.REQUIRED.forEach { File(DirectLibrary.dir(context), it).apply { parentFile?.mkdirs(); writeText("") } }
    }

    /** The app's context, where Android refuses to start a foreground service (from the background, Android 12 and later) while [refuse]. */
    private class Refusing(base: Context) : ContextWrapper(base) {
        @Volatile
        var refuse = true

        override fun getApplicationContext(): Context = this

        override fun startForegroundService(service: Intent): ComponentName? {
            if (refuse) throw IllegalStateException("startForegroundService() not allowed: app is in the background")
            return super.startForegroundService(service)
        }
    }

    @Test
    fun iMessagesServiceIsStarted() {
        setUpImessage()
        assertTrue(ProcessRestart.startService(context))
        assertEquals(DirectService::class.java.name, shadowOf(context as Application).peekNextStartedService()?.component?.className)
    }

    @Test
    fun aStartAndroidRefusesOrOfIMessageNotSetUpIsFalseNeverAThrow() {
        setUpImessage()
        val refusing = Refusing(context)
        assertFalse(ProcessRestart.startService(refusing))
        refusing.refuse = false
        assertTrue(ProcessRestart.startService(refusing))
        DirectPrefs.setRegistered(context, false)
        assertFalse(ProcessRestart.startService(context))
    }

    /** One restart with every step recorded in [order]. */
    private suspend fun restart(
        start: () -> Boolean = { true },
        lock: Mutex = Mutex(),
        guard: Mutex = Mutex(),
        busy: () -> Boolean = { false },
        wanted: () -> Boolean = { true },
        requests: RequestGate? = null,
        lockWaitMs: Long = 2_000,
        guardWaitMs: Long = 2_000,
        requestWaitMs: Long = ProcessRestart.REQUEST_WAIT_MS,
        quiesce: suspend () -> Boolean = { true },
        resume: () -> Unit = {},
        last: suspend () -> Unit = {},
        end: () -> Unit = {},
    ): ProcessRestart.Outcome = ProcessRestart.whenFree(
        start = { order += "start"; start() },
        engineLock = lock,
        guard = guard,
        busy = { order += "busy?"; busy() },
        wanted = { order += "wanted?"; wanted() },
        requests = requests,
        quiesce = { order += "quiesce"; quiesce() },
        resume = { order += "resume"; resume() },
        last = { order += "last"; last() },
        lockWaitMs = lockWaitMs,
        guardWaitMs = guardWaitMs,
        requestWaitMs = requestWaitMs,
    ) {
        order += "end"
        end()
    }

    private val restarted = ProcessRestart.Outcome.RESTARTED
    private val keptBack = ProcessRestart.Outcome.KEPT_BACK
    private val failed = ProcessRestart.Outcome.FAILED

    @Test
    fun withNothingHoldingItTheServiceIsStartedFirstThenItRestartsInOrder() = runBlocking {
        assertEquals(restarted, restart(requests = RequestGate()))
        assertEquals(listOf("start", "busy?", "wanted?", "quiesce", "busy?", "last", "busy?", "end"), order.toList())
    }

    // --- a refused start tells nobody and holds nothing -----------------------------------------------

    @Test
    fun aRefusedStartTellsNobodyAndHoldsNothing() = runBlocking {
        val lock = Mutex()
        val guard = Mutex()
        val requests = RequestGate()
        val events = EventGate()
        // Held by a registration: a refused start does not even wait for it.
        lock.lock()
        val outcome = ProcessRestart.whenFree(
            start = { order += "start"; false },
            engineLock = lock,
            guard = guard,
            busy = { order += "busy?"; false },
            wanted = { order += "wanted?"; true },
            requests = requests,
            quiesce = { order += "quiesce"; events.quiesce(1_000) },
            resume = { order += "resume"; events.resume() },
            last = { order += "last (the main process told)" },
            lockWaitMs = 60_000,
        ) { order += "end" }
        assertEquals(failed, outcome)
        assertEquals("nothing but the start", listOf("start"), order.toList())
        assertFalse("the guard never taken", guard.isLocked)
        lock.unlock()
        assertFalse(lock.isLocked)
        assertFalse("requests taken as ever", requests.isClosed)
        assertTrue(requests.enter())
        requests.leave()
        assertFalse("events taken as ever", events.isQuiet)
        // A start that throws: the same.
        order.clear()
        assertEquals(failed, restart(start = { throw IllegalStateException("not allowed") }))
        assertEquals(listOf("start"), order.toList())
    }

    @Test
    fun aRefusedStartNeverEndsTheProcessAndTheEventsKeepFlowingThenAnAllowedOneRestarts() = runBlocking {
        setUpImessage()
        val refusing = Refusing(context)
        val gate = EventGate()
        val taken = Collections.synchronizedList(ArrayList<String>())
        val consumer = CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                gate.take { taken += "event" }
                delay(2)
            }
        }
        var exits = 0
        suspend fun attempt() = ProcessRestart.whenFree(
            start = { ProcessRestart.startService(refusing) },
            engineLock = Mutex(),
            guard = Mutex(),
            busy = { false },
            wanted = { true },
            requests = RequestGate(),
            quiesce = { gate.quiesce(1_000) },
            resume = { gate.resume() },
            last = {},
        ) { exits++ }
        try {
            assertEquals(failed, attempt())
            assertEquals(0, exits)
            assertFalse("events not held back", gate.isQuiet)
            val before = taken.size
            withTimeout(5_000) { while (taken.size <= before + 2) delay(5) }
            // Allowed now (a screen of LightDecoder's in sight): the service is started, then the process ends.
            refusing.refuse = false
            assertEquals(restarted, attempt())
            assertEquals(1, exits)
            assertEquals(DirectService::class.java.name, shadowOf(context as Application).peekNextStartedService()?.component?.className)
        } finally {
            consumer.cancelAndJoin()
        }
    }

    // --- what holds it back, once the service is started ------------------------------------------------

    @Test
    fun aRegistrationUnderWayIsLetFinishFirst() = runBlocking {
        val lock = Mutex()
        val holding = CompletableDeferred<Unit>()
        val registration = CoroutineScope(Dispatchers.Default).launch {
            lock.withLock {
                holding.complete(Unit)
                delay(200)
                order += "registered"
            }
        }
        holding.await()
        assertEquals(restarted, restart(lock = lock))
        registration.join()
        assertEquals(listOf("start", "registered", "busy?", "wanted?", "quiesce", "busy?", "last", "busy?", "end"), order.toList())
    }

    @Test
    fun aRegistrationThatTakesLongerKeepsItBackAndNothingIsDone() = runBlocking {
        val lock = Mutex()
        lock.lock()
        assertEquals(keptBack, restart(lock = lock, lockWaitMs = 100))
        assertEquals("nothing of it ran but the start", listOf("start"), order.toList())
        lock.unlock()
        // And nothing was left held.
        assertEquals(restarted, restart(lock = lock))
    }

    @Test
    fun aFaceTimeJoinOrACallKeepsItBack() = runBlocking {
        val lock = Mutex()
        val guard = Mutex()
        guard.lock()
        assertEquals(keptBack, restart(lock = lock, guard = guard, guardWaitMs = 100))
        assertEquals(listOf("start"), order.toList())
        assertFalse("the engine's lock let go of", lock.isLocked)
        guard.unlock()
        order.clear()
        assertEquals(keptBack, restart(lock = lock, guard = guard, busy = { true }))
        assertEquals("a call on: looked at under the guard, nothing done", listOf("start", "busy?"), order.toList())
        assertFalse(lock.isLocked)
        assertFalse(guard.isLocked)
    }

    @Test
    fun aCallThatBeginsWhileTheRestartWaitsKeepsItBackRightUpToTheEnd() = runTest {
        // A ring comes in (or a call is placed) without the guard while the restart waits for a
        // request being answered: looked at again once that wait is over, and kept back.
        val requests = RequestGate(clock = { testScheduler.currentTime })
        assertTrue(requests.enter())
        var live = false
        val attempt = async { restart(requests = requests, busy = { live }) }
        advanceTimeBy(1_000)
        runCurrent()
        live = true
        requests.leave()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(keptBack, attempt.await())
        assertEquals(listOf("start", "busy?", "wanted?", "quiesce", "busy?", "resume"), order.toList())
        assertFalse("requests taken again", requests.isClosed)

        // One that begins while the main process is told (the last thing before the end): kept back too.
        order.clear()
        var n = 0
        assertEquals(keptBack, restart(requests = requests, busy = { ++n >= 3 }))
        assertEquals(listOf("start", "busy?", "wanted?", "quiesce", "busy?", "last", "busy?", "resume"), order.toList())
        assertFalse(order.contains("end"))
        assertFalse(requests.isClosed)
    }

    @Test
    fun anEventNotLetFinishInTimeKeepsItBack() = runBlocking {
        // As the engine has it: the event being taken waits (on the main process, say) longer than the restart waits for it.
        val gate = EventGate()
        val inEvent = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val taken = Collections.synchronizedList(ArrayList<String>())
        val consumer = CoroutineScope(Dispatchers.Default).launch {
            gate.take {
                inEvent.complete(Unit)
                finish.await()
                taken += "first"
            }
            gate.take { taken += "second" }
        }
        inEvent.await()
        val requests = RequestGate()
        val outcome = restart(requests = requests, quiesce = { gate.quiesce(100) }, resume = { gate.resume() }, end = { fail("never ended in the middle of an event") })
        assertEquals(keptBack, outcome)
        assertEquals(listOf("start", "busy?", "wanted?", "quiesce", "resume"), order.toList())
        assertFalse("nothing told", order.contains("last"))
        assertFalse(gate.isQuiet)
        assertFalse(requests.isClosed)
        // The event ends, and the next is taken as ever.
        finish.complete(Unit)
        withTimeout(5_000) { consumer.join() }
        assertEquals(listOf("first", "second"), taken.toList())
        // Through the engine, the gate's answer is what the restart goes by.
        val engine = File("src/main/java/com/lightdecoder/imessage/direct/DirectEngine.kt").readText()
        assertTrue(engine.contains("quiesce = { gate.quiesce(QUIESCE_MS) },"))
    }

    @Test
    fun oneNoLongerWantedDoesNothing() = runBlocking {
        assertEquals(ProcessRestart.Outcome.NOT_WANTED, restart(wanted = { false }))
        assertEquals(listOf("start", "busy?", "wanted?"), order.toList())
    }

    // --- the main process's requests being answered --------------------------------------------------------

    @Test
    fun aRestartWaitsForARequestBeingAnsweredAndIsKeptBackAfterFiveSeconds() = runTest {
        val requests = RequestGate(clock = { testScheduler.currentTime })
        // One request is being answered (a download, say), and goes on.
        assertTrue(requests.enter())
        val attempt = async { restart(requests = requests) }
        advanceTimeBy(4_900)
        runCurrent()
        assertFalse("waits for it", attempt.isCompleted)
        // Others are taken meanwhile (the restart may yet be kept back): never refused for nothing.
        assertTrue(requests.enter())
        requests.leave()
        assertEquals(listOf("start", "busy?", "wanted?"), order.toList())
        advanceTimeBy(200)
        runCurrent()
        assertEquals(keptBack, attempt.await())
        assertEquals("nothing done; the events taken again", listOf("start", "busy?", "wanted?", "resume"), order.toList())
        assertFalse(requests.isClosed)
        assertTrue("taken as ever", requests.enter())
        requests.leave()
        requests.leave()
        assertEquals(0, requests.inFlight)
    }

    @Test
    fun aRestartWaitsForARequestBeingAnsweredAndGoesOnOnceItsAnswerHasGone() = runTest {
        val requests = RequestGate(clock = { testScheduler.currentTime })
        assertTrue(requests.enter())
        val attempt = async { restart(requests = requests) }
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(attempt.isCompleted)
        assertFalse("not ended under it", order.contains("end"))
        // Its answer has gone.
        requests.leave()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(restarted, attempt.await())
        assertEquals(listOf("start", "busy?", "wanted?", "quiesce", "busy?", "last", "busy?", "end"), order.toList())
        assertTrue("the process ends with requests not taken", requests.isClosed)
        assertFalse("one asked now is not taken", requests.enter())
        assertEquals(0, requests.inFlight)
    }

    @Test
    fun aClosedRequestGateIsNeverLeftClosedLongerThanARestartTakes() {
        var now = 0L
        val requests = RequestGate(holdMs = 1_000, clock = { now })
        runBlocking { assertTrue(requests.close(0)) }
        assertFalse(requests.enter())
        now = 999
        assertFalse(requests.enter())
        now = 1_000
        assertTrue("a process still here after that long did not restart", requests.enter())
        requests.leave()
        assertEquals(0, requests.inFlight)
    }

    // --- the quiet is the attempt's own: events are never left held back by a restart that did not happen ---

    @Test
    fun aRestartThatFailsAfterTheEventsWereStoppedTakesThemAgain() = runBlocking {
        val lock = Mutex()
        val guard = Mutex()
        val requests = RequestGate()
        assertEquals(failed, restart(lock = lock, guard = guard, requests = requests, last = { throw IllegalStateException("the main process went") }))
        assertEquals(listOf("start", "busy?", "wanted?", "quiesce", "busy?", "last", "resume"), order.toList())
        assertFalse("requests taken again", requests.isClosed)
        order.clear()
        // The end itself failed: the same.
        assertEquals(failed, restart(lock = lock, guard = guard, requests = requests, end = { throw IllegalStateException("not ended") }))
        assertEquals(listOf("start", "busy?", "wanted?", "quiesce", "busy?", "last", "busy?", "end", "resume"), order.toList())
        assertFalse("nothing left held", lock.isLocked || guard.isLocked || requests.isClosed)
    }

    @Test
    fun aRestartWhoseCallerGoesWhileTheEventsAreStoppedTakesThemAgain() = runBlocking {
        val lock = Mutex()
        val requests = RequestGate()
        val stopping = CompletableDeferred<Unit>()
        val attempt = CoroutineScope(Dispatchers.Default).launch {
            restart(lock = lock, requests = requests, quiesce = {
                stopping.complete(Unit)
                delay(10_000)
                true
            })
        }
        stopping.await()
        attempt.cancelAndJoin()
        assertEquals(listOf("start", "busy?", "wanted?", "quiesce", "resume"), order.toList())
        assertFalse(lock.isLocked)
        assertFalse(requests.isClosed)
    }

    @Test
    fun aRestartThatHappensLeavesTheEventsStopped() = runBlocking {
        assertEquals(restarted, restart())
        assertFalse("the process ends with them stopped", order.contains("resume"))
    }

    @Test
    fun theEventsAreNeverHeldBackLongerThanARestartTakes() = runBlocking {
        // Stopped for a restart that, as far as anybody said, happened: a process still here after that long did not restart.
        val gate = EventGate(holdMs = 150)
        assertTrue(gate.quiesce(1_000))
        assertTrue(gate.isQuiet)
        val waiting = CoroutineScope(Dispatchers.Default).async {
            var taken = false
            gate.take { taken = true }
            taken
        }
        delay(50)
        assertFalse("held back meanwhile", waiting.isCompleted)
        assertTrue(withTimeout(5_000) { waiting.await() })
        assertFalse(gate.isQuiet)
        // A later restart stops them again.
        assertTrue(gate.quiesce(1_000))
        assertTrue(gate.isQuiet)
        gate.resume()
        assertFalse(gate.isQuiet)
    }

    @Test
    fun theEventsAreStoppedForARestartAndTakenAgainWhenItDoesNotHappen() = runBlocking {
        val gate = EventGate()
        val taken = Collections.synchronizedList(ArrayList<String>())
        // One being taken is let finish first.
        val inFirst = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val consumer = CoroutineScope(Dispatchers.Default).launch {
            gate.take {
                inFirst.complete(Unit)
                finishFirst.await()
                taken += "first"
            }
            gate.take { taken += "second" }
        }
        inFirst.await()
        val stopping = CoroutineScope(Dispatchers.Default).async { gate.quiesce(5_000) }
        delay(100)
        assertFalse("waits for the one being taken", stopping.isCompleted)
        finishFirst.complete(Unit)
        assertTrue(stopping.await())
        delay(200)
        assertEquals("none begun once stopped", listOf("first"), taken.toList())
        assertTrue(gate.isQuiet)
        // The restart did not happen: the one held back is taken, then the rest.
        gate.resume()
        withTimeout(5_000) { consumer.join() }
        assertEquals(listOf("first", "second"), taken.toList())
        // And a later restart stops them again.
        assertTrue(gate.quiesce(1_000))
        gate.resume()
    }

    @Test
    fun theEventsStoppedForAFailedRestartAreTakenAgain() = runBlocking {
        val gate = EventGate()
        assertEquals(
            failed,
            ProcessRestart.whenFree(
                start = { true },
                engineLock = Mutex(),
                guard = Mutex(),
                busy = { false },
                wanted = { true },
                requests = null,
                quiesce = { gate.quiesce(1_000) },
                resume = { gate.resume() },
                last = { throw IllegalStateException("failed") },
            ) { order += "end" },
        )
        assertFalse(gate.isQuiet)
        var taken = false
        withTimeout(5_000) { gate.take { taken = true } }
        assertTrue(taken)
    }

    // --- a lock is never left held by a wait that ran out --------------------------------------------------

    /** A lock that is slow to come back once taken: a wait for it can run out after it was taken. */
    private class SlowOnceTaken(val real: Mutex = Mutex()) : Mutex by real {
        override suspend fun lock(owner: Any?) {
            real.lock(owner)
            Thread.sleep(400)
        }

        override fun tryLock(owner: Any?): Boolean = real.tryLock(owner).also { if (it) Thread.sleep(400) }
    }

    @Test
    fun aLockTakenJustAsItsWaitRunsOutIsNeverLeftHeld() = runBlocking {
        val m = SlowOnceTaken()
        // Held by someone else for a moment: the wait for it suspends, then gets it, and runs out meanwhile.
        m.real.lock()
        val holder = CoroutineScope(Dispatchers.Default).launch {
            delay(50)
            m.real.unlock()
        }
        var ran = false
        val outcome = withContext(Dispatchers.Default) {
            runCatching { m.withLockWithin(150) { ran = true } }
        }
        holder.join()
        assertFalse("left held (ran: $ran, ${outcome.exceptionOrNull()?.javaClass?.simpleName})", m.real.isLocked)
        assertTrue("either it ran or it was given up", ran || outcome.exceptionOrNull() is com.lightdecoder.imessage.facetime.GuardBusy)
    }

    @Test
    fun aLockThatDoesNotComeFreeIsGivenUpWithNothingHeld() = runBlocking {
        val m = Mutex()
        m.lock()
        val thrown = runCatching { m.withLockWithin(100) { fail("not run") } }.exceptionOrNull()
        assertTrue(thrown is com.lightdecoder.imessage.facetime.GuardBusy)
        m.unlock()
        assertFalse(m.isLocked)
        // A wait cancelled holds nothing either.
        m.lock()
        val waiting = CoroutineScope(Dispatchers.Default).launch { m.withLockWithin(10_000) { fail("not run") } }
        delay(100)
        waiting.cancelAndJoin()
        m.unlock()
        assertFalse(m.isLocked)
        assertTrue(m.tryLock())
    }

    @Test
    fun theAccountsRestartGoesByItThroughTheEngine() {
        // AppleAccount -> Hooks.restart -> DirectEngine.restartForAccount -> ProcessRestart.whenFree: the
        // service started first, on the engine's lock, the calls' guard and the main process's requests.
        val engine = File("src/main/java/com/lightdecoder/imessage/direct/DirectEngine.kt").readText()
        val body = engine.substringAfter("internal suspend fun restartForAccount(").substringBefore("\n\n")
        assertTrue(body, body.contains("ProcessRestart.whenFree("))
        assertTrue(body, body.contains("start = { ProcessRestart.startService(app) },"))
        assertTrue(body, body.contains("engineLock = lock,"))
        assertTrue(body, body.contains("guard = callBridge.guard,"))
        assertTrue(body, body.contains("FaceTimeCalls.peek()?.busy == true"))
        assertTrue(body, body.contains("requests = requests,"))
        assertTrue(body, body.contains("resume = { gate.resume() },"))
        assertTrue(body, body.contains("{ ProcessRestart.exit() }"))
        assertTrue(engine.contains("override suspend fun restart(wanted: () -> Boolean, last: suspend () -> Unit): ProcessRestart.Outcome = restartForAccount(wanted, last)"))
        // The service's server is what the restart waits for.
        val service = File("src/main/java/com/lightdecoder/imessage/direct/DirectService.kt").readText()
        assertTrue(service.contains("engine.requests = server.requests"))
        // The only way the process is ended for it.
        val main = File("src/main/java/com/lightdecoder")
        val killers = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readLines().any { it.contains("ProcessRestart.exit") && !it.trimStart().startsWith("*") } }
            .map { it.name }.toList()
        assertEquals(listOf("DirectEngine.kt"), killers)
    }
}
