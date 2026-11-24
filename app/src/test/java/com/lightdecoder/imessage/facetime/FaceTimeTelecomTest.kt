package com.lightdecoder.imessage.facetime

import android.app.Application
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.imessage.facetime.FakeBridge.Companion.ALICE
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.rust_bridge.FaceTimeEvent

/**
 * The call engine with Telecom and the cellular watcher (SPEC2 B3.2, B3.3), over the fakes: a ring is
 * handed to Telecom only once it rings, a headset's answer joins with the camera off through the
 * screen's own answer, Telecom's call is made active once joined and ended with the call, Telecom's
 * hold ends it; without Telecom, a phone call answered mutes then ends the call.
 */
@RunWith(AndroidJUnit4::class)
class FaceTimeTelecomTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val bridge = FakeBridge()
    private val natives = FakeNatives()
    private val main = FakeMain()
    private val notices = FakeNotices()
    private val audio = FakeAudio()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Line : TelecomCalls.Line {
        val told: MutableList<String> = Collections.synchronizedList(ArrayList())
        override fun markActive() {
            told += "active"
        }
        override fun end(cause: TelecomCalls.EndCause) {
            told += "end $cause"
        }
        val moved: MutableList<Route> = Collections.synchronizedList(ArrayList())
        override fun moveTo(route: Route): Boolean {
            moved += route
            return true
        }
    }

    private val handed: MutableList<TelecomCalls.Ask> = Collections.synchronizedList(ArrayList())
    private val lines: MutableList<Line> = Collections.synchronizedList(ArrayList())
    private var telecomOn = true

    /** Telecom as a fake: it takes every call at once and makes its call for it. */
    private lateinit var telecom: TelecomCalls
    private lateinit var calls: FaceTimeCalls

    private class Mode : CellularWatch.Source {
        @Volatile
        var changed: ((Int) -> Unit)? = null
        override fun mode() = AudioManager.MODE_NORMAL
        override fun start(changed: (Int) -> Unit) {
            this.changed = changed
        }
        override fun stop() {
            changed = null
        }
    }

    private val mode = Mode()

    private fun engine(): FaceTimeCalls {
        telecom = TelecomCalls(
            platform = object : TelecomCalls.Platform {
                override fun addIncoming(ask: TelecomCalls.Ask): Boolean = take(ask)
                override fun placeOutgoing(ask: TelecomCalls.Ask): Boolean = take(ask)
                private fun take(ask: TelecomCalls.Ask): Boolean {
                    handed += ask
                    val line = Line()
                    lines += line
                    // Telecom comes back with its call for it.
                    telecom.attach(ask.guid, ask.incoming, line)
                    return true
                }
            },
            actions = object : TelecomCalls.Actions {
                // The call screen's answer from a headset: camera off, as FaceTimeCallActivity.answerIntent(withCamera = false) does.
                override fun answer(guid: String): Boolean {
                    calls.answer(guid, withCamera = false)
                    return true
                }
                override fun decline(guid: String) = calls.decline(guid)
                override fun hangUp(guid: String) = calls.hangUp(guid)
            },
            enabled = { telecomOn },
        )
        return FaceTimeCalls(
            app, bridge, natives, main, notices, FakeForeground(), TelecomAudio(audio, telecom) { calls.state.value?.guid }, FakeCamera(), FakeRingback(),
            settings = { FaceTimeSettings() }, phoneBusy = { false }, scope = scope,
            sleep = { CompletableDeferred<Unit>().await() }, connectWaitMs = 300,
            telecom = telecom, cellularSource = mode,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun await(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            if (condition()) return
            Thread.sleep(5)
        }
        fail("timed out waiting for $what (state ${calls.state.value?.phase})")
    }

    private fun ring(video: Boolean = true) {
        bridge.incoming(IN, ALICE, video)
        calls.onEvent(FaceTimeEvent.JoinEvent(IN, 7uL, ALICE, true))
        await("the ring") { calls.state.value?.phase == Phase.RINGING_IN }
    }

    @Test
    fun aHeadsetsAnswerJoinsWithTheCameraOffAndTelecomFollowsTheCall() {
        calls = engine()
        ring(video = true)
        await("handed to Telecom") { handed.isNotEmpty() }
        assertEquals(TelecomCalls.Ask(IN, incoming = true, address = ALICE, name = "Alice Martin", video = true), handed.single())
        assertTrue(telecom.ownsAudio(IN))
        // The headset's button.
        telecom.answered(IN)
        await("joined") { calls.state.value?.phase == Phase.ACTIVE }
        assertFalse("the camera stays off", calls.state.value!!.withCamera)
        await("Telecom's call active") { lines.single().told.contains("active") }
        // Telecom has the audio: the app's own route set nothing.
        assertFalse(audio.entered)
        // The car's hang-up.
        telecom.disconnected(IN)
        await("over") { calls.state.value == null }
        await("Telecom's call ended") { lines.single().told.any { it.startsWith("end") } }
        assertEquals(listOf("active", "end LOCAL"), lines.single().told.toList())
    }

    @Test
    fun aRouteChosenOnTheCarOrTheSystemStandsAndOnlyTheScreensPickIsAskedOfTelecom() {
        calls = engine()
        ring(video = false)
        await("handed to Telecom") { handed.isNotEmpty() }
        calls.answer(IN, withCamera = false)
        await("joined") { calls.state.value?.phase == Phase.ACTIVE }
        val line = lines.single()
        // Telecom says the call's sound is on Bluetooth (the car).
        telecom.audioChanged(IN, listOf(Route.EARPIECE, Route.SPEAKER, Route.BLUETOOTH), Route.BLUETOOTH)
        line.moved.clear()
        // The user moves it to the phone on the car's or the system's switcher: Telecom says so.
        telecom.audioChanged(IN, listOf(Route.EARPIECE, Route.SPEAKER, Route.BLUETOOTH), Route.EARPIECE)
        await("shown") { calls.media.value.route == Route.EARPIECE }
        assertEquals(listOf(Route.EARPIECE, Route.SPEAKER, Route.BLUETOOTH), calls.media.value.routes)
        Thread.sleep(50)
        assertTrue("Telecom's route is not undone: ${line.moved}", line.moved.isEmpty())
        // The call screen's own pick goes through Telecom.
        calls.setRoute(Route.SPEAKER)
        await("asked of Telecom") { line.moved.contains(Route.SPEAKER) }
        assertFalse(audio.entered)
        telecom.disconnected(IN)
        await("over") { calls.state.value == null }
    }

    @Test
    fun telecomsHoldEndsTheCall() {
        calls = engine()
        ring(video = false)
        await("handed to Telecom") { handed.isNotEmpty() }
        calls.answer(IN, withCamera = false)
        await("joined") { calls.state.value?.phase == Phase.ACTIVE }
        telecom.held(IN)
        await("over") { calls.state.value == null }
        await("its row") { main.logged.isNotEmpty() }
        assertEquals(Outcome.ANSWERED, main.logged.last().outcome)
        await("Telecom's call ended") { lines.single().told.contains("end LOCAL") }
    }

    @Test
    fun withTelecomOffTheCallIsAsBeforeAndAPhoneCallAnsweredMutesThenEndsIt() {
        telecomOn = false
        calls = engine()
        ring(video = false)
        Thread.sleep(150)
        assertTrue("nothing handed to Telecom", handed.isEmpty())
        calls.answer(IN, withCamera = false)
        await("joined") { calls.state.value?.phase == Phase.ACTIVE }
        await("the app's own audio") { audio.entered }
        await("watching") { mode.changed != null }
        mode.changed!!(AudioManager.MODE_RINGTONE)
        await("the banner") { calls.media.value.phoneRinging }
        assertEquals(Phase.ACTIVE, calls.state.value?.phase)
        mode.changed!!(AudioManager.MODE_IN_CALL)
        await("over") { calls.state.value == null }
        await("the banner gone") { !calls.media.value.phoneRinging }
        await("left") { natives.made.single().calls.any { it.startsWith("destroyAvc") } }
        val native = natives.made.single()
        val mic = native.calls.indexOfFirst { it.endsWith(":false") && it.startsWith("enableMicrophone") }
        val destroy = native.calls.indexOfFirst { it.startsWith("destroyAvc") }
        assertTrue(native.calls.toString(), mic in 0 until destroy)
        assertNull(calls.state.value)
    }

    private companion object {
        const val IN = "BBBBBBBB-0000-4000-8000-000000000002"
    }
}
