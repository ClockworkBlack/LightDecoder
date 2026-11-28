package com.lightdecoder.imessage.facetime

import android.Manifest
import android.app.Application
import android.content.Intent
import android.graphics.PixelFormat
import android.media.ImageReader
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.imessage.facetime.FakeBridge.Companion.ALICE
import com.lightdecoder.imessage.facetime.FakeBridge.Companion.BOB
import com.lightdecoder.imessage.facetime.FakeBridge.Companion.ME
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import uniffi.rust_bridge.FaceTimeEvent

/**
 * Group FaceTime through the call engine over its fakes (SPEC2 B2): one call placed to several
 * people, the tiles' views, who is looked up (only whom Apple vouches for), ringing an invited person
 * again, where FaceTime stands told to the main process, and the picture-in-picture window's buttons
 * ([FaceTimeActionReceiver]), which answer and place nothing.
 */
@RunWith(AndroidJUnit4::class)
class FaceTimeGroupCallsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var engine: FakeCallEngine
    private val calls: FaceTimeCalls get() = engine.calls
    private val readers = ArrayList<ImageReader>()

    @Before
    fun setUp() {
        engine = FakeCallEngine(app)
        FaceTimeCalls.installForTesting(engine.calls)
    }

    @After
    fun tearDown() {
        FaceTimeCalls.installForTesting(null)
        engine.close()
        readers.forEach { it.close() }
    }

    private fun await(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            if (condition()) return
            Thread.sleep(5)
        }
        fail("timed out waiting for $what (state ${calls.state.value?.phase}; bridge ${engine.bridge.log.toList()})")
    }

    private fun settle() = Thread.sleep(150)

    private fun surface(): Surface = ImageReader.newInstance(64, 64, PixelFormat.RGBA_8888, 2).also { readers += it }.surface

    /** A group call from Alice (its list also naming Bob and Carol), rung, answered and joined. */
    private fun inGroupCall(): FakeNative {
        engine.ringGroup(IN, listOf(BOB, CAROL))
        await("the ring") { calls.state.value?.phase == Phase.RINGING_IN }
        calls.answer(IN, withCamera = false)
        await("the call") { calls.state.value?.phase == Phase.ACTIVE }
        return engine.natives.last
    }

    @Test
    fun aGroupCallIsPlacedOnceToEveryoneButTheUser() {
        calls.place(ALICE, "Hikers", video = true, from = ME, conversationId = "imsg:group:H", peers = listOf(ALICE, BOB, ME, "temp:guest", "+1 555 333 4444"))
        await("the call placed") { engine.bridge.log.toList().any { it.startsWith("createFacetime") } }
        val guid = calls.state.value!!.guid
        assertEquals("one call, to everyone but the user, each once", "createFacetime:$guid:$ME:$ALICE,$BOB,$CAROL:true", engine.bridge.log.toList().single { it.startsWith("createFacetime") })
        assertTrue(calls.state.value!!.group)
        // Bob answers: the library joins.
        calls.onEvent(FaceTimeEvent.JoinEvent(guid, 9uL, BOB, false))
        await("the join") { calls.state.value?.phase == Phase.ACTIVE }
        assertTrue("a group's cameras go into tiles", calls.surfaces.tilesOnly)
        calls.hangUp()
        await("the end") { calls.state.value == null }
        await("its row") { engine.main.logged.size == 1 }
        val row = engine.main.logged.single()
        assertTrue(row.group)
        assertEquals(listOf(ALICE, BOB, CAROL), row.members)
        assertEquals("imsg:group:H", row.conversationId)
        // One person picked is a one-to-one call, as always.
        calls.place(ALICE, "Alice", video = false, from = ME, conversationId = null, peers = listOf(ALICE))
        await("the second call") { engine.bridge.log.toList().count { it.startsWith("createFacetime") } == 2 }
        assertFalse(calls.state.value!!.group)
        assertTrue(engine.bridge.log.toList().last { it.startsWith("createFacetime") }.endsWith(":$ALICE:false"))
    }

    @Test
    fun onlyThoseWhoJoinedAsAppleVouchesAreLookedUpEachOncePerCall() {
        engine.main.callers = { CallerInfo(if (it == ALICE) "Alice Martin" else "Someone", known = true) }
        val native = inGroupCall()
        val asked = engine.main.asked.toList()
        assertEquals("the caller, when it rang", listOf(ALICE), asked)
        // The library says who is in it: Alice (as she joined, id 7), "Mom" (a label any member can write), a guest.
        native.host!!.participants(longArrayOf(7, 8, 9), arrayOf(ALICE, MOM, "temp:guest"))
        await("Alice looked up for her tile") { calls.labels.value.containsKey(CallIds.key(ALICE)) }
        settle()
        assertEquals("never Mom, nor the guest", listOf(ALICE, ALICE), engine.main.asked.toList())
        // Bob joins (Apple vouches for him): he is looked up, once, however often the list comes.
        calls.onEvent(FaceTimeEvent.JoinEvent(IN, 8uL, BOB, false))
        await("his join taken") { calls.state.value?.joiners?.get(8L) == BOB }
        native.host.participants(longArrayOf(7, 8, 9), arrayOf(ALICE, MOM, "temp:guest"))
        native.host.participants(longArrayOf(7, 8, 9), arrayOf(ALICE, MOM, "temp:guest"))
        await("Bob looked up") { calls.labels.value.containsKey(CallIds.key(BOB)) }
        settle()
        assertEquals(listOf(ALICE, ALICE, BOB), engine.main.asked.toList())
        val labels = CallLabels.of(calls.state.value!!.inCall, calls.state.value!!.joiners)
        assertEquals(listOf(CallLabels.Kind.VERIFIED, CallLabels.Kind.VERIFIED, CallLabels.Kind.GUEST), labels.map { it.kind })
        assertEquals("participant 8 is Bob, whatever the list says", BOB, labels[1].handle)
    }

    @Test
    fun aGroupCallsTilesAndASharedScreenGetTheirViewsAndTheLibraryIsAskedAgain() {
        val native = inGroupCall()
        val host = native.host!!
        assertTrue(calls.surfaces.tilesOnly)
        assertNull("no tile yet", host.buildSurface(7, CallSurfaces.STREAM_CAMERA))
        assertEquals(listOf(7L to CallSurfaces.STREAM_CAMERA), calls.surfaces.requested.value)
        val tile = surface()
        val before = native.calls.toList().count { it == "refreshSurfaces:77" }
        calls.attachTile(7, tile)
        await("asked again") { native.calls.toList().count { it == "refreshSurfaces:77" } > before }
        assertSame(tile, host.buildSurface(7, CallSurfaces.STREAM_CAMERA))
        assertTrue(7L in calls.media.value.tiles)
        host.videoSize(7, CallSurfaces.STREAM_CAMERA, 640, 480)
        host.streamRotation(7, CallSurfaces.STREAM_CAMERA, 0)
        assertEquals(480 to 640, calls.media.value.tiles[7]!!.shown)
        // A shared screen: its own view, whole.
        val screen = surface()
        val count = native.calls.toList().count { it == "refreshSurfaces:77" }
        calls.attachScreen(screen)
        await("asked again for the screen") { native.calls.toList().count { it == "refreshSurfaces:77" } > count }
        assertSame(screen, host.buildSurface(8, CallSurfaces.STREAM_SCREEN))
        assertTrue(calls.media.value.screenVideo)
        host.videoSize(8, CallSurfaces.STREAM_SCREEN, 1170, 2532)
        // No rotation said for it yet: taken as OpenBubbles takes every stream then (a quarter turn), until the library says.
        assertEquals(2532 to 1170, calls.media.value.screen.shown)
        host.streamRotation(8, CallSurfaces.STREAM_SCREEN, Media.ROTATION_NONE)
        assertEquals(1170 to 2532, calls.media.value.screen.shown)
        assertNull("a second camera, never", host.buildSurface(8, CallSurfaces.GROUP_SECOND_CAMERA))
        // They stop.
        host.streamsRemoved(8, intArrayOf(CallSurfaces.STREAM_SCREEN))
        host.streamsRemoved(7, intArrayOf(CallSurfaces.STREAM_CAMERA))
        assertFalse(calls.media.value.screenVideo)
        assertTrue(calls.media.value.tiles.isEmpty())
        // The tile goes: the library is told before it is let go of.
        var released = false
        calls.detachTile(7, tile) {
            assertEquals("told first", "refreshSurfaces:77", native.calls.last())
            released = true
        }
        await("let go") { released }
    }

    @Test
    fun anInvitedPersonIsRungAgainOnlyFromTheMemberListAndNotTooOften() = runBlocking {
        inGroupCall()
        assertEquals("invited, not in it: Bob and Carol (never the user)", listOf(BOB, CAROL), calls.invited())
        assertTrue(calls.ringMember(BOB))
        assertEquals("ringMembers:$IN:$BOB", engine.bridge.log.toList().last { it.startsWith("ringMembers") })
        assertFalse("once in a while", calls.ringMember(BOB))
        assertFalse("nobody the list does not name", calls.ringMember(MOM))
        assertFalse("never the user", calls.ringMember(ME))
        assertFalse("nothing that is no address", calls.ringMember("temp:guest"))
        assertTrue(calls.ringMember(CAROL))
        assertEquals(2, engine.bridge.log.toList().count { it.startsWith("ringMembers") })
        // Bob joins: no longer invited.
        calls.onEvent(FaceTimeEvent.JoinEvent(IN, 8uL, BOB, false))
        await("his join taken") { calls.state.value?.joiners?.get(8L) == BOB }
        assertEquals(listOf(CAROL), calls.invited())
    }

    @Test
    fun nothingIsRungAgainInAOneToOneCall() = runBlocking {
        engine.ring(IN)
        await("the ring") { calls.state.value?.phase == Phase.RINGING_IN }
        calls.answer(IN, withCamera = false)
        await("the call") { calls.state.value?.phase == Phase.ACTIVE }
        assertFalse(calls.ringMember(ALICE))
        assertTrue(calls.invited().isEmpty())
        assertTrue(engine.bridge.log.toList().none { it.startsWith("ringMembers") })
    }

    @Test
    fun thePictureInPictureButtonsOnlyMuteTurnTheCameraOffOrEndAndNeverAnswerOrPlace() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val receiver = FaceTimeActionReceiver()
        fun send(action: String, guid: String) = receiver.onReceive(app, FaceTimeActionReceiver.intent(app, action, guid))
        // A call that only rings: none of them answers it.
        engine.ring(IN, video = true)
        await("the ring") { calls.state.value?.phase == Phase.RINGING_IN }
        send(FaceTimeActionReceiver.ACTION_TOGGLE_MIC, IN)
        send(FaceTimeActionReceiver.ACTION_CAMERA_OFF, IN)
        settle()
        assertEquals(Phase.RINGING_IN, calls.state.value?.phase)
        assertTrue("nothing joined", engine.natives.made.isEmpty())
        assertFalse(calls.media.value.muted)
        // Answered with the camera (by the user, on the screen): now they work, for this call only.
        engine.natives.onMade = { it.isVideo = true }
        calls.answer(IN, withCamera = true)
        await("the call") { calls.state.value?.phase == Phase.ACTIVE }
        val native = engine.natives.last
        native.host!!.encoderSurface(surface(), 1)
        await("the camera") { engine.camera.running }
        send(FaceTimeActionReceiver.ACTION_TOGGLE_MIC, OTHER)
        send(FaceTimeActionReceiver.ACTION_CAMERA_OFF, OTHER)
        settle()
        assertFalse("another call's buttons do nothing", calls.media.value.muted)
        assertTrue(calls.media.value.cameraOn)
        send(FaceTimeActionReceiver.ACTION_TOGGLE_MIC, IN)
        await("muted") { calls.media.value.muted && native.calls.last() == "enableMicrophone:77:false" }
        send(FaceTimeActionReceiver.ACTION_TOGGLE_MIC, IN)
        await("unmuted") { !calls.media.value.muted && native.calls.last() == "enableMicrophone:77:true" }
        send(FaceTimeActionReceiver.ACTION_CAMERA_OFF, IN)
        await("the camera off") { !calls.media.value.cameraOn && !engine.camera.running }
        // Off again: it never turns the camera on.
        val starts = engine.camera.starts.size
        send(FaceTimeActionReceiver.ACTION_CAMERA_OFF, IN)
        settle()
        assertFalse(calls.media.value.cameraOn)
        assertEquals(starts, engine.camera.starts.size)
        // Junk is nothing.
        receiver.onReceive(app, Intent(FaceTimeActionReceiver.ACTION_TOGGLE_MIC).putExtra(FaceTimeActionReceiver.EXTRA_GUID, "not-a-call"))
        receiver.onReceive(app, Intent("com.lightdecoder.facetime.action.ANSWER").putExtra(FaceTimeActionReceiver.EXTRA_GUID, IN))
        receiver.onReceive(app, Intent())
        settle()
        assertEquals(Phase.ACTIVE, calls.state.value?.phase)
        // End.
        send(FaceTimeActionReceiver.ACTION_HANG_UP, IN)
        await("the end") { calls.state.value == null }
        assertTrue("nothing was ever placed", engine.bridge.log.toList().none { it.startsWith("createFacetime") })
        assertEquals("joined only by the user's answer", 1, engine.natives.made.size)
    }

    @Test
    fun whereFaceTimeStandsIsToldAsACallComesToHoldTheLineAndLetsGo() {
        val told = java.util.Collections.synchronizedList(ArrayList<Pair<Boolean, Long>>())
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val bridge = FakeBridge()
        val natives = FakeNatives()
        val telling = FaceTimeCalls(
            app, bridge, natives, FakeMain(), FakeNotices(), FakeForeground(), FakeAudio(), FakeCamera(), FakeRingback(),
            settings = { FaceTimeSettings() }, phoneBusy = { false }, scope = scope,
            sleep = { kotlinx.coroutines.CompletableDeferred<Unit>().await() }, connectWaitMs = 300,
            tell = { call -> told += (call != null && call.live && !call.incomingRing) to (call?.activeSinceMs ?: 0L) },
        )
        try {
            telling.place(ALICE, "Alice", video = false, from = ME, conversationId = null)
            await("placed") { telling.state.value?.phase == Phase.RINGING_OUT }
            val guid = telling.state.value!!.guid
            telling.onEvent(FaceTimeEvent.JoinEvent(guid, 9uL, ALICE, false))
            await("on") { telling.state.value?.phase == Phase.ACTIVE }
            telling.hangUp()
            await("over") { telling.state.value == null }
            val states = told.toList()
            assertTrue(states.toString(), states.first().first)
            assertTrue("told once it is on, with since when", states.any { it.first && it.second > 0 })
            assertFalse("and that it is over", states.last().first)
            // As the bar is told it: never anything of a call that only rings here.
            val app = ApplicationProvider.getApplicationContext<Application>()
            val ringing = Call(IN, ALICE, "Alice", video = false, outgoing = false, phase = Phase.RINGING_IN, startedMs = 1)
            assertEquals(com.lightdecoder.imessage.direct.FaceTimeState.NONE, FaceTimeNotices.state(app, ringing))
            val group = ringing.copy(phase = Phase.ACTIVE, activeSinceMs = 5, group = true, others = 2)
            val state = FaceTimeNotices.state(app, group)
            assertEquals("Alice · Group FaceTime", state.title)
            assertTrue(state.live && state.group)
            assertEquals(5L, state.sinceMs)
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    private companion object {
        const val IN = "BBBBBBBB-0000-4000-8000-000000000002"
        const val OTHER = "CCCCCCCC-0000-4000-8000-000000000003"
        const val CAROL = "tel:+15553334444"
        const val MOM = "tel:+15550001111"
    }
}
