package com.lightdecoder.imessage.facetime

import android.Manifest
import android.app.Application
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Looper
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.imessage.facetime.FakeBridge.Companion.ALICE
import com.lightdecoder.imessage.facetime.FakeBridge.Companion.ME
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import uniffi.rust_bridge.FaceTimeEvent
import uniffi.rust_bridge.FtMember

/**
 * The call engine ([FaceTimeCalls]) over fakes of the library ([FakeBridge], [FakeNative]: the real
 * one cannot run here), the camera, the phone's audio, the notices and the main process: what a
 * placed and an incoming call ask of the library, in which order, on which thread; that nothing of
 * the camera, the microphone or the audio is touched before the user placed or answered; and that
 * every way a call ends leaves nothing joined, ringing or switched on.
 */
@RunWith(AndroidJUnit4::class)
class FaceTimeCallsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val bridge = FakeBridge()
    private val natives = FakeNatives().apply { onMade = { it.isMainThread = { Looper.getMainLooper().thread === Thread.currentThread() } } }
    private val main = FakeMain()
    private val notices = FakeNotices()
    private val foreground = FakeForeground()
    private val audio = FakeAudio()
    private val camera = FakeCamera()
    private val ringback = FakeRingback()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var settings = FaceTimeSettings()
    private var phoneBusy = false

    /** The timers running: how long each is for, and what lets it run out. */
    private val timers = ConcurrentLinkedQueue<Pair<Long, CompletableDeferred<Unit>>>()

    /** The call noted as held, each time it changed (null: none). */
    private val remembered = java.util.Collections.synchronizedList(ArrayList<String?>())

    private val calls = FaceTimeCalls(
        app, bridge, natives, main, notices, foreground, audio, camera, ringback,
        settings = { settings }, phoneBusy = { phoneBusy }, scope = scope,
        sleep = { ms ->
            val done = CompletableDeferred<Unit>()
            timers += ms to done
            done.await()
        },
        connectWaitMs = 300,
        remember = { remembered += it },
    )

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
        fail("timed out waiting for $what (state ${calls.state.value?.phase}; bridge ${bridge.log.toList()}; natives ${natives.made.toList().map { it.calls.toList() }})")
    }

    /** Gives what is queued a moment, for checks that something did not happen. */
    private fun settle() = Thread.sleep(150)

    private fun phase(): Phase? = calls.state.value?.phase

    private fun ring(guid: String = IN, video: Boolean = false, caller: String = ALICE) {
        bridge.incoming(guid, caller, video)
        calls.onEvent(FaceTimeEvent.JoinEvent(guid, 7uL, caller, true))
        await("the ring") { phase() == Phase.RINGING_IN }
    }

    private fun placeAndGetAnswered(video: Boolean = false): String {
        calls.place(ALICE, "Alice Martin", video, ME, "imsg:tel:+15551234567")
        await("the placed call") { phase() == Phase.RINGING_OUT && bridge.log.toList().any { it.startsWith("createFacetime") } }
        val guid = calls.state.value!!.guid
        calls.onEvent(FaceTimeEvent.JoinEvent(guid, 9uL, ALICE, false))
        await("the joined call") { phase() == Phase.ACTIVE }
        return guid
    }

    /** A Surface that is really there (an image reader's), as the library's encoder's and a view's are. */
    private fun surface(): Surface = ImageReader.newInstance(64, 64, PixelFormat.RGBA_8888, 2).also { readers += it }.surface

    private val readers = ArrayList<ImageReader>()

    // --- a placed call ---------------------------------------------------------------------------

    @Test
    fun aPlacedCallIsCreatedThenJoinedOnceTheOtherSideJoinedAndLeftOnHangUp() {
        calls.place(ALICE, "Alice Martin", video = false, from = ME, conversationId = "imsg:tel:+15551234567")
        await("the call placed") { bridge.log.toList().any { it.startsWith("createFacetime") } }
        val guid = calls.state.value!!.guid
        assertTrue("an upper-case UUID (the library finds a call's relay by that form)", CallIds.isGuid(guid))
        assertEquals(Phase.RINGING_OUT, phase())
        // The addresses first, then the call: from the user's address, to the one person, as audio.
        assertEquals(listOf("setLocalInterfaces", "createFacetime:$guid:$ME:$ALICE:false"), bridge.log.toList())
        await("the ringback") { ringback.on }
        assertTrue("held under the in-call notice from now on", foreground.running)
        assertTrue("nothing is joined while it only rings", natives.made.isEmpty())
        assertFalse(audio.entered)
        await("its thirty seconds running") { timers.toList().any { it.first == CallRules.RING_OUT_MS } }
        // The other person's device joins: now, and only now, the library joins too.
        calls.onEvent(FaceTimeEvent.JoinEvent(guid, 9uL, ALICE, false))
        await("the join") { phase() == Phase.ACTIVE }
        val native = natives.last
        assertEquals("the handle made for the join is the one it got, once", bridge.handlesMade.toList(), native.handlesUsed.toList())
        assertEquals(1, bridge.handlesMade.size)
        val joinLog = bridge.log.drop(2)
        assertEquals("the addresses, then the call list read right before the handle is made", listOf("setLocalInterfaces", "sessions", "handle:1000"), joinLog.takeLast(3))
        // After the join: the orientation, the camera's state (off: an audio call), the microphone on.
        assertEquals(listOf("createAvc:$guid", "updateOrientation:77:1", "enableCamera:77:false:-1", "enableMicrophone:77:true"), native.calls.toList())
        assertTrue("no function of the library ran on the main thread: ${native.onMainThread}", native.onMainThread.isEmpty())
        assertFalse(ringback.on)
        assertTrue("the phone's audio is the call's", audio.entered)
        assertEquals("an audio call starts on the earpiece", Route.EARPIECE, audio.routed.last())
        assertFalse("no camera in an audio call", camera.running)
        // Hang up: left with destroyAvc, never taken back; everything given back.
        calls.hangUp()
        await("the call's end") { calls.state.value == null && native.calls.last() == "destroyAvc:77" }
        await("the audio given back") { !audio.entered }
        assertFalse(bridge.log.toList().any { it.startsWith("cancelFacetime") })
        assertFalse(foreground.running)
        await("the call's row") { main.logged.size == 1 }
        val row = main.logged.single()
        assertEquals(Outcome.ANSWERED, row.outcome)
        assertEquals(guid, row.guid)
        assertEquals(ALICE, row.peer)
        assertTrue(row.outgoing)
        assertEquals("imsg:tel:+15551234567", row.conversationId)
    }

    @Test
    fun aPlacedCallNobodyTakesIsCancelledWhenItsTimeRunsOut() {
        calls.place(ALICE, "Alice", video = true, from = null, conversationId = null)
        await("the call placed and ringing its thirty seconds") { timers.toList().any { it.first == CallRules.RING_OUT_MS } }
        val guid = calls.state.value!!.guid
        timers.toList().first { it.first == CallRules.RING_OUT_MS }.second.complete(Unit)
        await("no answer") { phase() == Phase.UNANSWERED }
        await("the call taken back") { bridge.log.contains("cancelFacetime:$guid") }
        assertTrue("never joined", natives.made.isEmpty())
        assertFalse(ringback.on)
        assertFalse("no line is held by a call nobody took", calls.busy)
        assertFalse(foreground.running)
        await("its row") { main.logged.toList().any { it.outcome == Outcome.UNANSWERED } }
        // The user leaves the screen.
        calls.hangUp()
        await("gone") { calls.state.value == null }
    }

    @Test
    fun hangingUpBeforeItIsAnsweredTakesThePlacedCallBack() {
        calls.place(ALICE, "Alice", video = false, from = ME, conversationId = null)
        await("the call placed") { bridge.log.toList().any { it.startsWith("createFacetime") } && timers.toList().any { it.first == CallRules.RING_OUT_MS } }
        val guid = calls.state.value!!.guid
        calls.hangUp(guid)
        await("the call taken back") { bridge.log.contains("cancelFacetime:$guid") && calls.state.value == null }
        assertTrue(natives.made.isEmpty())
        assertFalse(audio.entered)
        await("its row") { main.logged.singleOrNull()?.outcome == Outcome.CANCELLED }
        // The other side joining after that joins nothing.
        calls.onEvent(FaceTimeEvent.JoinEvent(guid, 9uL, ALICE, false))
        settle()
        assertTrue(natives.made.isEmpty())
    }

    @Test
    fun aCallThatCannotBePlacedLeavesNothingBehind() {
        bridge.failCreate = true
        calls.place(ALICE, "Alice", video = false, from = ME, conversationId = null)
        await("the failure shown") { phase() == Phase.UNANSWERED && calls.state.value?.unanswered == Unanswered.FAILED }
        val guid = calls.state.value!!.guid
        await("whatever was made taken back") { bridge.log.contains("cancelFacetime:$guid") }
        assertTrue(natives.made.isEmpty())
        assertFalse(ringback.on)
    }

    @Test
    fun whoCannotBeCalledIsNotCalledAndSaysWhy() {
        val said = java.util.Collections.synchronizedList(ArrayList<CallMessage>())
        val listening = scope.launchCollect(calls) { said += it }
        try {
            calls.place("temp:guest", "", video = false, from = null, conversationId = null)
            await("not someone to call") { said.size == 1 }
            // Not connected, and not within the moment a starting process is given.
            bridge.connected = false
            calls.place(ALICE, "", video = false, from = null, conversationId = null)
            await("not connected") { said.size == 2 }
            bridge.connected = true
            settings = FaceTimeSettings(on = false)
            calls.place(ALICE, "", video = false, from = null, conversationId = null)
            await("FaceTime off") { said.size == 3 }
            settings = FaceTimeSettings()
            phoneBusy = true
            calls.place(ALICE, "", video = false, from = null, conversationId = null)
            await("the phone busy") { said.size == 4 }
            assertEquals(
                listOf(Refusal.NOT_CALLABLE, Refusal.NOT_CONNECTED, Refusal.OFF, Refusal.PHONE_BUSY),
                said.map { (it as CallMessage.NotStarted).why },
            )
            assertNull(calls.state.value)
            assertTrue("nothing was asked of the library", bridge.log.isEmpty())
            assertFalse(foreground.running)
        } finally {
            listening.cancel()
        }
    }

    // --- an incoming call ------------------------------------------------------------------------

    @Test
    fun anIncomingCallRingsAndNothingOfThePhoneIsTouchedUntilTheUserAnswers() {
        bridge.incoming(IN, ALICE, video = true)
        calls.onEvent(FaceTimeEvent.JoinEvent(IN, 7uL, ALICE, true))
        await("the ring") { notices.ringing != null }
        val ringing = notices.ringing!!
        assertEquals(IN, ringing.guid)
        assertEquals("Alice Martin", ringing.name)
        assertTrue(ringing.video)
        assertEquals("the main process was asked who it is", listOf(ALICE), main.asked.toList())
        await("its thirty seconds running") { timers.toList().any { it.first == CallRules.RING_IN_MS } }
        settle()
        // Ringing is all: no join, no microphone, no camera, no audio mode, no in-call service.
        assertTrue(natives.made.isEmpty())
        assertTrue(bridge.handlesMade.isEmpty())
        assertFalse(audio.entered)
        assertTrue(camera.starts.isEmpty())
        assertEquals(0, foreground.starts)
        assertTrue("the ring holds the line", calls.busy)
        // The user answers, audio only.
        calls.answer(IN, withCamera = false)
        await("the join") { phase() == Phase.ACTIVE }
        assertNull("the ringing notice is down", notices.ringing)
        val native = natives.last
        assertEquals(listOf("createAvc:$IN", "updateOrientation:77:1", "enableCamera:77:false:-1", "enableMicrophone:77:true"), native.calls.toList())
        assertTrue(native.onMainThread.isEmpty())
        assertTrue(foreground.running)
        assertTrue(audio.entered)
        assertTrue("answered as audio: the camera stays off", camera.starts.isEmpty())
        // The other side is there, then hangs up.
        native.host!!.participants(longArrayOf(7), arrayOf(ALICE))
        native.host.participants(LongArray(0), arrayOfNulls(0))
        await("the call's end") { calls.state.value == null && native.calls.last() == "destroyAvc:77" }
        await("the audio given back") { !audio.entered }
        await("its row") { main.logged.singleOrNull()?.outcome == Outcome.ANSWERED }
        assertFalse(main.logged.single().outgoing)
        assertFalse("an answered call is not declined", bridge.log.toList().any { it.startsWith("declineFacetime") })
    }

    @Test
    fun decliningTellsTheCallerAndJoinsNothing() {
        ring()
        calls.decline(IN)
        await("the decline") { bridge.log.contains("declineFacetime:$IN") && calls.state.value == null }
        assertNull(notices.ringing)
        assertTrue(natives.made.isEmpty())
        await("its row") { main.logged.singleOrNull()?.outcome == Outcome.DECLINED }
    }

    @Test
    fun aRingThatRunsOutIsAMissedCall() {
        ring()
        await("its thirty seconds running") { timers.toList().any { it.first == CallRules.RING_IN_MS } }
        timers.toList().first { it.first == CallRules.RING_IN_MS }.second.complete(Unit)
        await("the missed call") { calls.state.value == null && main.logged.singleOrNull()?.outcome == Outcome.MISSED }
        assertNull(notices.ringing)
        assertTrue(natives.made.isEmpty())
        assertFalse(bridge.log.toList().any { it.startsWith("declineFacetime") })
    }

    @Test
    fun eventsWithAnIdThatIsNoCallsAreNotEvenLookedAt() {
        bridge.incoming(IN)
        // Lower case, not a UUID, a path: none is a call's id.
        for (bad in listOf(IN.lowercase(), "call-1", "../../etc/passwd", "", IN + "0")) {
            calls.onEvent(FaceTimeEvent.JoinEvent(bad, 7uL, ALICE, true))
            calls.onEvent(FaceTimeEvent.Ring(bad))
            calls.onEvent(FaceTimeEvent.Decline(bad, ALICE))
        }
        // And what a one-to-one call has no use for.
        calls.onEvent(FaceTimeEvent.Connected(IN))
        calls.onEvent(FaceTimeEvent.Disconnected(IN))
        calls.onEvent(FaceTimeEvent.LinkChanged(IN))
        calls.onEvent(FaceTimeEvent.RemoveMembers(IN, listOf(FtMember(null, ALICE))))
        calls.onEvent(FaceTimeEvent.AddMembers(IN, listOf(FtMember(null, ME)), false))
        settle()
        assertTrue("the library was asked nothing: ${bridge.log}", bridge.log.isEmpty())
        assertNull(calls.state.value)
        assertTrue(notices.shown.isEmpty())
        assertTrue(main.asked.isEmpty())
    }

    @Test
    fun withUnknownCallersSilencedSomeoneUnknownDoesNotRing() {
        settings = FaceTimeSettings(on = true, silenceUnknown = true)
        main.callers = { CallerInfo("", known = false) }
        bridge.incoming(IN)
        calls.onEvent(FaceTimeEvent.JoinEvent(IN, 7uL, ALICE, true))
        await("the missed call") { main.logged.singleOrNull()?.outcome == Outcome.MISSED }
        assertTrue("without a sound", main.logged.single().quiet)
        assertTrue(notices.shown.isEmpty())
        assertNull(calls.state.value)
    }

    @Test
    fun aCallPlacedAsTheProcessStartsWaitsAMomentForTheSession() {
        bridge.connected = false
        calls.place(ALICE, "Alice", video = false, from = ME, conversationId = null)
        Thread.sleep(100)
        assertNull(calls.state.value)
        bridge.connected = true
        await("the call placed") { phase() == Phase.RINGING_OUT && bridge.log.toList().any { it.startsWith("createFacetime") } }
    }

    @Test
    fun aRingWaitsForTheSessionWhenItComesAsTheProcessStarts() {
        bridge.connected = false
        bridge.incoming(IN)
        calls.onEvent(FaceTimeEvent.JoinEvent(IN, 7uL, ALICE, true))
        settle()
        assertTrue(bridge.log.isEmpty())
        bridge.connected = true
        await("the ring") { notices.ringing?.guid == IN }
    }

    @Test
    fun answeringACallThatIsNoLongerInTheLibrarysListJoinsNothing() {
        ring()
        // The caller gave up and the call is gone from the library's list by the time it is answered.
        bridge.sessions.clear()
        calls.answer(IN, withCamera = false)
        await("the failure shown") { phase() == Phase.UNANSWERED }
        assertTrue("the join was never called", natives.made.toList().all { it.calls.isEmpty() })
        assertTrue(bridge.handlesMade.isEmpty())
        await("the caller told") { bridge.log.contains("declineFacetime:$IN") }
        assertFalse(audio.entered)
        await("its row") { main.logged.singleOrNull()?.outcome == Outcome.FAILED }
    }

    @Test
    fun hangingUpWhileTheJoinRunsLeavesWhatItMadeAndGivesEverythingBack() {
        val gate = CountDownLatch(1)
        natives.onMade = { it.gate = gate }
        ring()
        calls.answer(IN, withCamera = false)
        await("the join begun") { natives.made.isNotEmpty() && natives.last.entered.count == 0L }
        calls.hangUp(IN)
        await("the hang-up taken") { calls.state.value == null }
        gate.countDown()
        await("what the join made destroyed") { natives.last.calls.toList() == listOf("createAvc:$IN", "destroyAvc:77") }
        settle()
        assertFalse("the phone's audio was never taken, or was given back", audio.entered)
        assertFalse(camera.running)
        assertFalse(foreground.running)
    }

    @Test
    fun aJoinThatDoesNotComeBackIsGivenUpAndWhatItMakesLaterIsLeftAtOnce() {
        val gate = CountDownLatch(1)
        natives.onMade = { it.gate = gate }
        ring()
        calls.answer(IN, withCamera = false)
        await("the join begun, its time running") {
            natives.made.isNotEmpty() && natives.last.entered.count == 0L && timers.toList().any { it.first == CallRules.CONNECT_MS }
        }
        // The join's time runs out with the library still in it.
        timers.toList().first { it.first == CallRules.CONNECT_MS }.second.complete(Unit)
        await("the call given up") { phase() == Phase.UNANSWERED && calls.state.value?.unanswered == Unanswered.FAILED }
        await("its row") { main.logged.singleOrNull()?.outcome == Outcome.FAILED }
        await("the in-call notice gone") { !foreground.running }
        assertFalse("nothing is destroyed while the join still runs", natives.last.calls.toList().any { it.startsWith("destroyAvc") })
        // The join comes back after all: what it made is left at once, and nothing of the phone was taken.
        gate.countDown()
        await("what the join made destroyed") { natives.last.calls.toList() == listOf("createAvc:$IN", "destroyAvc:77") }
        settle()
        assertEquals(Phase.UNANSWERED, phase())
        assertFalse(audio.entered)
        assertFalse(camera.running)
        assertFalse("a call that did join is left, not declined as well", bridge.log.toList().any { it.startsWith("declineFacetime") })
    }

    @Test
    fun theCallHeldIsNotedForAStartAfterTheProcessWasStoppedInIt() {
        // Ringing here holds nothing: there is nothing to leave.
        ring()
        settle()
        assertTrue(remembered.toList().isEmpty())
        calls.answer(IN, withCamera = false)
        await("the call noted") { remembered.toList() == listOf<String?>(IN) }
        await("joined") { phase() == Phase.ACTIVE }
        calls.hangUp(IN)
        await("the note taken away") { remembered.toList() == listOf(IN, null) }
        // A placed one, from the moment it rings out.
        calls.place(ALICE, "", video = false, from = ME, conversationId = null)
        await("the placed call noted") { remembered.toList().size == 3 && remembered.toList().last() == calls.state.value?.guid }
    }

    @Test
    fun aCallTheProcessWasStoppedInIsLeftOnceAtTheNextStart() = kotlinx.coroutines.runBlocking {
        val forgotten = ArrayList<String>()
        bridge.incoming(IN)
        assertTrue(CallRecovery.leaveRemembered(IN, null, { forgotten += it }, bridge))
        assertEquals(listOf(IN), forgotten)
        assertEquals(listOf("setLocalInterfaces", "cancelFacetime:$IN"), bridge.log.toList())
        // One the library no longer has: its error is nothing.
        bridge.log.clear()
        assertTrue(CallRecovery.leaveRemembered("CCCCCCCC-0000-4000-8000-000000000003", null, { forgotten += it }, bridge))
        // Never the call that is live now, nothing for no note or one that is no call's id.
        bridge.log.clear()
        forgotten.clear()
        assertFalse(CallRecovery.leaveRemembered(IN, IN, { forgotten += it }, bridge))
        assertFalse(CallRecovery.leaveRemembered(null, null, { forgotten += it }, bridge))
        assertFalse(CallRecovery.leaveRemembered("../x", null, { forgotten += it }, bridge))
        assertTrue(bridge.log.isEmpty() && forgotten.isEmpty())
        // The note itself: only ever a call's id.
        com.lightdecoder.imessage.direct.DirectPrefs.setLiveFaceTimeCall(app, IN)
        assertEquals(IN, com.lightdecoder.imessage.direct.DirectPrefs.liveFaceTimeCall(app))
        java.io.File(app.filesDir, "imessage/facetime-live-call").writeText("not a call")
        assertNull(com.lightdecoder.imessage.direct.DirectPrefs.liveFaceTimeCall(app))
        com.lightdecoder.imessage.direct.DirectPrefs.setLiveFaceTimeCall(app, null)
        assertFalse(java.io.File(app.filesDir, "imessage/facetime-live-call").exists())
    }

    @Test
    fun theLibraryGivingUpEndsTheCall() {
        val guid = placeAndGetAnswered()
        natives.last.host!!.failed()
        await("the call's end") { calls.state.value == null && natives.last.calls.last() == "destroyAvc:77" }
        await("the audio given back") { !audio.entered }
        await("its row") { main.logged.singleOrNull()?.guid == guid }
    }

    // --- the joined call's buttons, camera and pictures --------------------------------------------

    @Test
    fun aVideoCallAnsweredWithTheCameraStartsItOnlyOnceJoinedAndIntoTheLibrarysEncoder() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        ring(video = true)
        natives.onMade = { it.isVideo = true }
        calls.answer(IN, withCamera = true)
        await("the join") { phase() == Phase.ACTIVE }
        val native = natives.last
        assertEquals(listOf("createAvc:$IN", "updateOrientation:77:1", "enableCamera:77:true:-1", "enableMicrophone:77:true"), native.calls.toList())
        assertTrue(calls.media.value.cameraOn)
        assertEquals("a video call starts on the speaker", Route.SPEAKER, audio.routed.last())
        assertTrue("no encoder yet: nothing to draw into", camera.starts.isEmpty())
        // The library hands its encoder's input over (on its own thread).
        val encoder = surface()
        native.host!!.encoderSurface(encoder, 1)
        await("the camera") { camera.starts.size == 1 }
        assertEquals("front=true preview=false", camera.starts.single())
        // A shared screen's or a second camera's encoder is not used: let go at once.
        val screen = surface()
        val second = surface()
        native.host.encoderSurface(screen, CallSurfaces.GROUP_SCREEN)
        native.host.encoderSurface(second, CallSurfaces.GROUP_SECOND_CAMERA)
        assertFalse(screen.isValid)
        assertFalse(second.isValid)
        assertEquals(1, camera.starts.size)
        // Flip: the camera again on the other side, and the orientation for it (back camera, upright: 0).
        calls.flipCamera()
        await("the other camera") { camera.starts.size == 2 && native.calls.last() == "updateOrientation:77:0" }
        assertEquals("front=false preview=false", camera.starts.last())
        // Camera off: the library stops sending, the camera stops.
        calls.setCamera(false)
        await("the camera off") { native.calls.contains("enableCamera:77:false:-1") && !camera.running }
        assertFalse(calls.media.value.cameraOn)
        // The end: the encoder's Surface is let go only now (after the camera stopped).
        assertTrue(encoder.isValid)
        calls.hangUp()
        await("the end") { calls.state.value == null && !encoder.isValid }
    }

    @Test
    fun withoutTheCameraPermissionAVideoCallIsJoinedWithoutTheCamera() {
        ring(video = true)
        natives.onMade = { it.isVideo = true }
        calls.answer(IN, withCamera = true)
        await("the join") { phase() == Phase.ACTIVE }
        assertTrue(natives.last.calls.contains("enableCamera:77:false:-1"))
        assertFalse(calls.media.value.cameraOn)
        natives.last.host!!.encoderSurface(surface(), 1)
        // Nor can it be turned on without it.
        calls.setCamera(true)
        settle()
        assertTrue("the camera is never opened without the permission", camera.starts.isEmpty())
        assertFalse(calls.media.value.cameraOn)
    }

    @Test
    fun aCameraThatCannotFeedTheEncoderLeavesTheCallOnSoundOnlyAndSaysSo() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        camera.works = false
        val said = java.util.Collections.synchronizedList(ArrayList<CallMessage>())
        val listening = scope.launchCollect(calls) { said += it }
        try {
            ring(video = true)
            natives.onMade = { it.isVideo = true }
            calls.answer(IN, withCamera = true)
            await("the join") { phase() == Phase.ACTIVE }
            natives.last.host!!.encoderSurface(surface(), 1)
            await("the camera given up on") { !calls.media.value.cameraOn && calls.media.value.cameraFailed }
            await("the library told to stop sending") { natives.last.calls.last() == "enableCamera:77:false:-1" }
            await("the user told") { said.contains(CallMessage.CameraUnavailable) }
            assertEquals(Phase.ACTIVE, phase())
        } finally {
            listening.cancel()
        }
    }

    @Test
    fun turningTheCameraOnInAnAudioCallMakesItAVideoCall() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        placeAndGetAnswered(video = false)
        val native = natives.last
        native.host!!.encoderSurface(surface(), 1)
        settle()
        assertTrue("an audio call's camera stays off though the library has an encoder", camera.starts.isEmpty())
        calls.setCamera(true)
        await("the camera") { camera.running && native.calls.contains("enableCamera:77:true:-1") }
        await("a video call now") { calls.state.value?.video == true }
        assertTrue("the in-call notice is started again for the camera's kind", foreground.starts >= 2)
    }

    @Test
    fun muteAndTheSpeakerGoToTheLibraryAndThePhone() {
        placeAndGetAnswered()
        val native = natives.last
        calls.setMuted(true)
        await("muted") { native.calls.last() == "enableMicrophone:77:false" }
        assertTrue(calls.media.value.muted)
        calls.setMuted(false)
        await("unmuted") { native.calls.last() == "enableMicrophone:77:true" }
        calls.setRoute(Route.SPEAKER)
        assertEquals(Route.SPEAKER, audio.routed.last())
        assertEquals(Route.SPEAKER, calls.media.value.route)
        // A headset comes: with no pick of the user's it would take over; the user's pick stays.
        audio.available = listOf(Route.EARPIECE, Route.SPEAKER, Route.BLUETOOTH)
        audio.changed!!.invoke()
        assertEquals(Route.SPEAKER, calls.media.value.route)
        assertEquals(3, calls.media.value.routes.size)
        calls.setRoute(Route.BLUETOOTH)
        assertEquals(Route.BLUETOOTH, audio.routed.last())
        // It goes again: back to what the call would take by itself.
        audio.available = listOf(Route.EARPIECE, Route.SPEAKER)
        audio.changed!!.invoke()
        assertEquals(Route.EARPIECE, calls.media.value.route)
        assertTrue(native.onMainThread.isEmpty())
    }

    @Test
    fun theOtherSidesPictureGoesIntoTheScreensViewAndOnlyTheFirstCameraStreamGetsIt() {
        placeAndGetAnswered(video = true)
        val native = natives.last
        val host = native.host!!
        assertNull("no view yet: the library is told there is none", host.buildSurface(7, CallSurfaces.STREAM_CAMERA))
        assertFalse(calls.media.value.remoteVideo)
        val view = surface()
        calls.attachRemote(view)
        await("the library asked to ask again") { native.calls.last() == "refreshSurfaces:77" }
        assertSame(view, host.buildSurface(7, CallSurfaces.STREAM_CAMERA))
        assertTrue(calls.media.value.remoteVideo)
        assertSame("the same stream again gets the same view", view, host.buildSurface(7, CallSurfaces.STREAM_CAMERA))
        assertNull("a second stream gets none while the view is taken", host.buildSurface(7, CallSurfaces.STREAM_CAMERA_ALTERNATE))
        assertNull("nor a shared screen", host.buildSurface(7, 3))
        assertNull("nor someone else", host.buildSurface(8, CallSurfaces.STREAM_CAMERA))
        // Its size and rotation, as the library says them; nonsense is left out.
        host.videoSize(7, CallSurfaces.STREAM_CAMERA, 1920, 1080)
        host.streamRotation(7, CallSurfaces.STREAM_CAMERA, 0)
        host.videoSize(7, CallSurfaces.STREAM_CAMERA, 0, -4)
        host.videoSize(8, CallSurfaces.STREAM_CAMERA, 320, 240)
        host.streamRotation(7, CallSurfaces.STREAM_CAMERA, 9)
        assertEquals(1080 to 1920, calls.media.value.remoteShown)
        // The stream goes: the view is free again, and the library asks again.
        val before = native.calls.toList().count { it == "refreshSurfaces:77" }
        host.streamsRemoved(7, intArrayOf(CallSurfaces.STREAM_CAMERA))
        await("asked again") { native.calls.toList().count { it == "refreshSurfaces:77" } > before }
        assertFalse(calls.media.value.remoteVideo)
        assertSame(view, host.buildSurface(7, CallSurfaces.STREAM_CAMERA_ALTERNATE))
        // The screen goes: the library is told before the view is let go.
        var released = false
        calls.detachRemote(view) {
            assertEquals("told first", "refreshSurfaces:77", native.calls.last())
            released = true
        }
        await("the view let go") { released }
        assertNull(host.buildSurface(7, CallSurfaces.STREAM_CAMERA))
        assertNull(host.systemAudio(48_000))
    }

    @Test
    fun turningTheScreenTellsTheLibraryHowTheCameraLies() {
        placeAndGetAnswered()
        val native = natives.last
        calls.displayRotation(android.view.Surface.ROTATION_90)
        await("landscape") { native.calls.last() == "updateOrientation:77:3" }
        calls.displayRotation(android.view.Surface.ROTATION_180)
        await("upside down") { native.calls.last() == "updateOrientation:77:0" }
        // Whatever a display says, the library gets 0..3.
        calls.displayRotation(17)
        calls.displayRotation(-3)
        await("clamped") { native.calls.toList().count { it.startsWith("updateOrientation") } >= 5 }
        assertTrue(native.calls.toList().filter { it.startsWith("updateOrientation") }.all { it.substringAfterLast(':').toInt() in 0..3 })
    }

    @Test
    fun aCallbackForACallThatIsOverDoesNothing() {
        placeAndGetAnswered()
        val old = natives.last.host!!
        calls.hangUp()
        await("the end") { calls.state.value == null }
        val late = surface()
        old.encoderSurface(late, 1)
        assertFalse("an encoder handed over late is let go", late.isValid)
        assertNull(old.buildSurface(7, 1))
        old.participants(longArrayOf(7), arrayOf(ALICE))
        old.failed()
        old.streamsRemoved(7, intArrayOf(1))
        settle()
        assertNull(calls.state.value)
        await("its one row") { main.logged.size == 1 }
        assertTrue(camera.starts.isEmpty())
    }

    private companion object {
        const val IN = "BBBBBBBB-0000-4000-8000-000000000002"
    }
}

/** Collects [calls]' messages into [take] (from this call on) until cancelled. */
private fun CoroutineScope.launchCollect(calls: FaceTimeCalls, take: (CallMessage) -> Unit): Job =
    launch(Dispatchers.Unconfined) { calls.messages.collect { take(it) } }
