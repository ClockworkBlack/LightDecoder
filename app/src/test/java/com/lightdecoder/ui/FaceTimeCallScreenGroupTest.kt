package com.lightdecoder.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.button.MaterialButton
import com.lightdecoder.R
import com.lightdecoder.imessage.facetime.CallSurfaces
import com.lightdecoder.imessage.facetime.CallerInfo
import com.lightdecoder.imessage.facetime.FaceTimeActionReceiver
import com.lightdecoder.imessage.facetime.FaceTimeCalls
import com.lightdecoder.imessage.facetime.FakeBridge.Companion.ALICE
import com.lightdecoder.imessage.facetime.FakeBridge.Companion.BOB
import com.lightdecoder.imessage.facetime.FakeBridge.Companion.ME
import com.lightdecoder.imessage.facetime.FakeCallEngine
import com.lightdecoder.imessage.facetime.Phase
import com.lightdecoder.ui.imessage.FaceTimeCallActivity
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.rust_bridge.FaceTimeEvent

/**
 * The call screen with group FaceTime, a shared screen and picture-in-picture (SPEC2 B2), over a
 * stand-in call engine: a group ring shows its caller only ("· Group FaceTime"), and one from someone
 * unknown offers Ignore; a group call's people are tiled and named only as Apple vouches; the people
 * sheet rings an invited person again; a shared screen is the big picture; only a video call that is
 * on floats in a window, whose buttons only mute, turn the camera off or end, and whose camera stops
 * when the screen goes off.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h800dp-xhdpi")
class FaceTimeCallScreenGroupTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var engine: FakeCallEngine
    private val calls: FaceTimeCalls get() = engine.calls
    private val readers = ArrayList<ImageReader>()

    @Before
    fun setUp() {
        engine = FakeCallEngine(app)
        FaceTimeCalls.installForTesting(engine.calls)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    @After
    fun tearDown() {
        FaceTimeCalls.installForTesting(null)
        engine.close()
        readers.forEach { it.close() }
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            if (condition()) return
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what (call ${calls.state.value?.phase}; library ${engine.bridge.log.toList()})")
            Thread.sleep(10)
        }
    }

    private fun settle() = repeat(6) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        Thread.sleep(10)
    }

    private fun <A : Activity> ActivityScenario<A>.waitFor(what: String, condition: (A) -> Boolean) =
        waitUntil(what) {
            var ok = false
            onActivity { ok = condition(it) }
            ok
        }

    private fun text(activity: Activity, id: Int): String = activity.findViewById<TextView>(id).text.toString()
    private fun shown(activity: Activity, id: Int): Boolean = activity.findViewById<View>(id).isShown
    private fun surface(): Surface = ImageReader.newInstance(64, 64, PixelFormat.RGBA_8888, 2).also { readers += it }.surface

    private fun launchIncoming(): ActivityScenario<FaceTimeCallActivity> = ActivityScenario.launch(FaceTimeCallActivity.incomingIntent(app, IN))

    /** All the texts of [view] and what is under it. */
    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is android.view.ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    // --- a group ring --------------------------------------------------------------------------------

    @Test
    fun aGroupRingShowsItsCallerOnlyAndFromSomeoneUnknownOffersIgnore() {
        engine.main.callers = { CallerInfo("", known = false) }
        engine.ringGroup(IN, listOf(BOB, CAROL), video = true)
        waitUntil("the ring") { calls.state.value?.phase == Phase.RINGING_IN }
        launchIncoming().use { scenario ->
            scenario.waitFor("the ringing screen") { shown(it, R.id.incoming) }
            scenario.onActivity { activity ->
                assertEquals("+15551234567 · Group FaceTime", text(activity, R.id.name))
                assertEquals("how many others, never who", "and 2 others", text(activity, R.id.detail))
                assertEquals("Ignore", text(activity, R.id.declineLabel))
                assertEquals("Ignore", activity.findViewById<View>(R.id.decline).contentDescription.toString())
                assertFalse("answered without the camera anyway", shown(activity, R.id.audioOnly))
                assertTrue(texts(activity.window.decorView).none { it.contains("555765") || it.contains("555333") })
                activity.findViewById<View>(R.id.decline).performClick()
            }
            waitUntil("ignored") { calls.state.value == null }
            settle()
            assertTrue("nothing was sent to the people it names", engine.bridge.log.toList().none { it.startsWith("declineFacetime") })
        }
    }

    @Test
    fun aGroupRingFromSomeoneKnownIsDeclinedAsAnyAndTheirAcceptKeepsTheCamera() {
        engine.ringGroup(IN, listOf(BOB, CAROL), video = true)
        waitUntil("the ring") { calls.state.value?.phase == Phase.RINGING_IN }
        launchIncoming().use { scenario ->
            scenario.waitFor("the ringing screen") { shown(it, R.id.incoming) }
            scenario.onActivity { activity ->
                assertEquals("Alice Martin · Group FaceTime", text(activity, R.id.name))
                assertEquals("Decline", text(activity, R.id.declineLabel))
                assertTrue(shown(activity, R.id.audioOnly))
                activity.findViewById<View>(R.id.decline).performClick()
            }
            waitUntil("declined") { calls.state.value == null && engine.bridge.log.contains("declineFacetime:$IN") }
        }
    }

    // --- in a group call -------------------------------------------------------------------------------

    @Test
    fun aGroupCallsPeopleAreTiledAndNamedOnlyAsAppleVouchesAndAnInvitedOneCanBeRungAgain() {
        engine.main.callers = { if (it == ALICE) CallerInfo("Alice Martin", known = true) else CallerInfo("Somebody", known = true) }
        engine.ringGroup(IN, listOf(BOB, CAROL))
        waitUntil("the ring") { calls.state.value?.phase == Phase.RINGING_IN }
        launchIncoming().use { scenario ->
            scenario.waitFor("the ringing screen") { shown(it, R.id.incoming) }
            scenario.onActivity { it.findViewById<View>(R.id.accept).performClick() }
            waitUntil("the call") { calls.state.value?.phase == Phase.ACTIVE }
            val host = engine.natives.last.host!!
            // In it: Alice (as she joined) and someone the call itself labels as Mom.
            host.participants(longArrayOf(7, 8), arrayOf(ALICE, MOM))
            host.buildSurface(7, CallSurfaces.STREAM_CAMERA)
            scenario.waitFor("two tiles") { shown(it, R.id.tiles) && it.findViewById<FrameLayout>(R.id.tiles).childCount == 2 }
            scenario.waitFor("their names") { texts(it.findViewById(R.id.tiles)).containsAll(listOf("Alice Martin", "+15550001111 (not verified)")) }
            scenario.onActivity { activity ->
                val tiles = activity.findViewById<FrameLayout>(R.id.tiles)
                assertTrue("a camera's tile has a view for its picture", (0 until tiles.childCount).any { i -> (tiles.getChildAt(i) as FrameLayout).let { f -> (0 until f.childCount).any { f.getChildAt(it) is TextureView } } })
                // A tile tapped is big; tapped again, the grid.
                val first = tiles.getChildAt(0)
                first.performClick()
            }
            settle()
            assertEquals("nobody looked up but whom Apple vouches for", listOf(ALICE, ALICE), engine.main.asked.toList())
            // The call's name opens who is in it and who was invited.
            scenario.onActivity { it.findViewById<View>(R.id.info).performClick() }
            scenario.waitFor("the people") { it.sheet?.shownInvited == listOf(BOB, CAROL) }
            scenario.onActivity { activity ->
                val sheet = activity.sheet!!
                val shownTexts = texts(sheet.window!!.decorView)
                assertTrue(shownTexts.toString(), shownTexts.containsAll(listOf("3 People", "In Call", "You", "Alice Martin", "+15550001111 (not verified)", "Invited", "+15557654321", "+15553334444")))
                assertTrue(shownTexts.contains(activity.getString(R.string.facetime_add_hint)))
                val ring = findRing(sheet.window!!.decorView)
                assertEquals(2, ring.size)
                assertTrue(ring.all { it.filterTouchesWhenObscured })
                ring.first().performClick()
            }
            waitUntil("rung again") { engine.bridge.log.contains("ringMembers:$IN:$BOB") }
            scenario.onActivity { activity -> assertEquals("Ringing…", findRing(activity.sheet!!.window!!.decorView).first().text.toString()) }
        }
    }

    /** The picture views of [tiles]'s tiles, by tile. */
    private fun videosOf(tiles: FrameLayout): List<TextureView?> = (0 until tiles.childCount).map { i ->
        (tiles.getChildAt(i) as FrameLayout).let { f -> (0 until f.childCount).map(f::getChildAt).filterIsInstance<TextureView>().singleOrNull() }
    }

    @Test
    fun aTileGetsItsPictureWhenItsCameraIsAskedForLaterAndTellsTheLibraryBeforeItsSurfaceGoes() {
        // A contact photo, for whoever the main process is asked about.
        val photo = java.io.File(app.filesDir, "alice.png")
        photo.outputStream().use { android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        engine.main.callers = { CallerInfo("Alice Martin", known = true, photo = photo.path) }
        engine.ringGroup(IN, listOf(BOB, CAROL))
        waitUntil("the ring") { calls.state.value?.phase == Phase.RINGING_IN }
        launchIncoming().use { scenario ->
            scenario.waitFor("the ringing screen") { shown(it, R.id.incoming) }
            scenario.onActivity { it.findViewById<View>(R.id.accept).performClick() }
            waitUntil("the call") { calls.state.value?.phase == Phase.ACTIVE }
            val host = engine.natives.last.host!!
            // The library says who is in the call before it asks for anyone's camera: tiles, no pictures.
            host.participants(longArrayOf(7, 8), arrayOf(ALICE, BOB))
            scenario.waitFor("two tiles") { it.findViewById<FrameLayout>(R.id.tiles).childCount == 2 }
            scenario.waitFor("Alice's name") { texts(it.findViewById(R.id.tiles)).contains("Alice Martin") }
            scenario.onActivity {
                val tiles = it.findViewById<FrameLayout>(R.id.tiles)
                assertTrue(videosOf(tiles).all { v -> v == null })
                // Alice joined as Apple vouches for: her photo, so no initials. Bob did not: his initials, no photo.
                assertEquals(listOf("", "Alice Martin"), texts(tiles.getChildAt(0)))
                assertTrue(texts(tiles.getChildAt(1)).toString(), texts(tiles.getChildAt(1)).first().isNotEmpty())
            }
            // Then it asks for Bob's camera: his tile gets a view for it.
            host.buildSurface(8, CallSurfaces.STREAM_CAMERA)
            scenario.waitFor("Bob's picture view") { videosOf(it.findViewById(R.id.tiles)).count { v -> v != null } == 1 }
            var listener: TextureView.SurfaceTextureListener? = null
            scenario.onActivity { listener = videosOf(it.findViewById(R.id.tiles)).filterNotNull().single().surfaceTextureListener }
            val native = engine.natives.last
            // The texture writes down, among the library's calls, when it is let go of.
            val texture = object : android.graphics.SurfaceTexture(false) {
                override fun release() {
                    native.calls += "textureReleased"
                    super.release()
                }
            }
            scenario.onActivity { listener!!.onSurfaceTextureAvailable(texture, 64, 64) }
            assertTrue("the library is given the tile's Surface", calls.surfaces.hasTile(8))
            // Bob goes: his tile is let go of, and its view, once taken away, tells the library first.
            host.streamsRemoved(8, intArrayOf(CallSurfaces.STREAM_CAMERA))
            host.participants(longArrayOf(7), arrayOf(ALICE))
            scenario.waitFor("one tile") { it.findViewById<FrameLayout>(R.id.tiles).childCount == 1 }
            var released = true
            val before = native.calls.size
            scenario.onActivity { released = listener!!.onSurfaceTextureDestroyed(texture) }
            assertFalse("its Surface is let go of only after the library was told", released)
            assertFalse("the library no longer has the tile's Surface", calls.surfaces.hasTile(8))
            waitUntil("the texture let go of") { native.calls.toList().contains("textureReleased") }
            val after = synchronized(native.calls) { native.calls.drop(before) }
            val refreshed = after.indexOfFirst { it.startsWith("refreshSurfaces") }
            assertTrue("the library is told (asked to look again) before the texture goes: $after", refreshed in 0 until after.indexOf("textureReleased"))
        }
    }

    private fun findRing(view: View): List<MaterialButton> = when (view) {
        is MaterialButton -> listOf(view)
        is android.view.ViewGroup -> (0 until view.childCount).flatMap { findRing(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun aGroupCallIsPlacedToEveryoneFromOneIntent() {
        ActivityScenario.launch<FaceTimeCallActivity>(FaceTimeCallActivity.placeIntent(app, listOf(ALICE, BOB, "temp:guest"), "Hikers", false, ME, "imsg:group:H")).use { scenario ->
            waitUntil("the call placed") { engine.bridge.log.toList().any { it.startsWith("createFacetime") } }
            val guid = calls.state.value!!.guid
            assertEquals("createFacetime:$guid:$ME:$ALICE,$BOB:false", engine.bridge.log.toList().single { it.startsWith("createFacetime") })
            scenario.waitFor("calling") { text(it, R.id.name) == "Hikers" }
            assertTrue(calls.state.value!!.group)
        }
    }

    // --- a shared screen -------------------------------------------------------------------------------

    @Test
    fun aSharedScreenIsTheBigPictureAndTheCameraGoesSmall() {
        engine.natives.onMade = { it.isVideo = true }
        engine.ring(IN, video = true)
        waitUntil("the ring") { calls.state.value?.phase == Phase.RINGING_IN }
        launchIncoming().use { scenario ->
            scenario.waitFor("the ringing screen") { shown(it, R.id.incoming) }
            scenario.onActivity { it.findViewById<View>(R.id.audioOnly).performClick() }
            waitUntil("the call") { calls.state.value?.phase == Phase.ACTIVE }
            val host = engine.natives.last.host!!
            calls.attachRemote(surface())
            host.buildSurface(7, CallSurfaces.STREAM_CAMERA)
            calls.attachScreen(surface())
            assertNotNull(host.buildSurface(7, CallSurfaces.STREAM_SCREEN))
            host.videoSize(7, CallSurfaces.STREAM_SCREEN, 1170, 2532)
            scenario.waitFor("the shared screen") { it.findViewById<View>(R.id.screenVideo).alpha == 1f }
            scenario.onActivity { activity ->
                val remote = activity.findViewById<View>(R.id.remoteVideo)
                assertTrue("the camera small over it", remote.layoutParams.width > 0 && remote.layoutParams.width < activity.findViewById<View>(R.id.root).width)
                assertFalse(shown(activity, R.id.backdrop))
            }
            // It stops: the camera big again.
            host.streamsRemoved(7, intArrayOf(CallSurfaces.STREAM_SCREEN))
            scenario.waitFor("the camera big again") { it.findViewById<View>(R.id.screenVideo).alpha == 0f && it.findViewById<View>(R.id.remoteVideo).layoutParams.width == FrameLayout.LayoutParams.MATCH_PARENT }
        }
    }

    // --- every picture as the 0.5.73 call screen lays it -----------------------------------------------

    /** [view]'s transform, as the call screen set it. */
    private fun transformOf(view: TextureView): android.graphics.Matrix = android.graphics.Matrix().also { view.getTransform(it) }

    private fun values(m: android.graphics.Matrix): List<Float> = FloatArray(9).also(m::getValues).toList()

    @Test
    fun everyTileAndTheSharedScreenAreTurnedByTheirStreamAndKeptInTheirShapeAsTheBigPictureIs() {
        engine.main.callers = { CallerInfo("Alice Martin", known = true) }
        engine.ringGroup(IN, listOf(BOB, CAROL))
        waitUntil("the ring") { calls.state.value?.phase == Phase.RINGING_IN }
        launchIncoming().use { scenario ->
            scenario.waitFor("the ringing screen") { shown(it, R.id.incoming) }
            scenario.onActivity { it.findViewById<View>(R.id.accept).performClick() }
            waitUntil("the call") { calls.state.value?.phase == Phase.ACTIVE }
            val host = engine.natives.last.host!!
            host.participants(longArrayOf(7), arrayOf(ALICE))
            calls.attachTile(7, surface())
            assertNotNull(host.buildSurface(7, CallSurfaces.STREAM_CAMERA))
            // A landscape stream that the other side's phone says is turned a quarter (as in 0.5.73's fix).
            host.videoSize(7, CallSurfaces.STREAM_CAMERA, 1920, 1080)
            host.streamRotation(7, CallSurfaces.STREAM_CAMERA, 1)
            scenario.waitFor("the tile") { it.findViewById<FrameLayout>(R.id.tiles).let { t -> t.childCount == 1 && t.width > 0 && t.getChildAt(0).width > 0 } }
            // Laid out: drawn again.
            calls.setMuted(true)
            settle()
            scenario.onActivity { activity ->
                val frame = activity.findViewById<FrameLayout>(R.id.tiles).getChildAt(0) as FrameLayout
                val video = (0 until frame.childCount).map(frame::getChildAt).filterIsInstance<TextureView>().single()
                assertEquals("one person's tile fills the screen", activity.findViewById<View>(R.id.tiles).width, frame.width)
                val expected = FaceTimeCallActivity.remoteTransform(video.width, video.height, 1920, 1080, 1, false, com.lightdecoder.ui.imessage.VideoFit.Zoom())
                assertFalse(expected.isIdentity)
                assertEquals("the tile is laid out as the big picture of a call with one person", values(expected), values(transformOf(video)))
                // The window's shape: the picture upright (1080 x 1920), as the screen shows it.
                assertEquals(5625, activity.pipAspect(calls.state.value!!, calls.media.value))
            }
            // A shared screen: whole (never cut), turned by its stream too.
            calls.attachScreen(surface())
            assertNotNull(host.buildSurface(7, CallSurfaces.STREAM_SCREEN))
            host.videoSize(7, CallSurfaces.STREAM_SCREEN, 2532, 1170)
            host.streamRotation(7, CallSurfaces.STREAM_SCREEN, 0)
            scenario.waitFor("the shared screen") { it.findViewById<View>(R.id.screenVideo).alpha == 1f }
            calls.setMuted(false)
            settle()
            scenario.onActivity { activity ->
                val screen = activity.findViewById<TextureView>(R.id.screenVideo)
                val whole = com.lightdecoder.ui.imessage.VideoFit.Zoom().apply { fill = false }
                val expected = FaceTimeCallActivity.remoteTransform(screen.width, screen.height, 2532, 1170, 0, false, whole)
                assertEquals(values(expected), values(transformOf(screen)))
                assertEquals(com.lightdecoder.imessage.facetime.PipParams.aspect(1170, 2532), activity.pipAspect(calls.state.value!!, calls.media.value))
            }
        }
    }

    // --- picture in picture ----------------------------------------------------------------------------

    @Test
    fun onlyAVideoCallThatIsOnWithAPictureFloatsAndItsCameraStopsWhenTheScreenGoesOff() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        engine.natives.onMade = { it.isVideo = true }
        engine.ring(IN, video = true)
        waitUntil("the ring") { calls.state.value?.phase == Phase.RINGING_IN }
        launchIncoming().use { scenario ->
            scenario.waitFor("the ringing screen") { shown(it, R.id.incoming) }
            scenario.onActivity { activity -> assertFalse("a ring never floats", activity.canPip()) }
            scenario.onActivity { it.findViewById<View>(R.id.accept).performClick() }
            waitUntil("the call") { calls.state.value?.phase == Phase.ACTIVE }
            val native = engine.natives.last
            native.host!!.encoderSurface(surface(), 1)
            waitUntil("the camera") { engine.camera.running }
            scenario.onActivity { activity -> assertFalse("no picture of the other side yet", activity.canPip()) }
            calls.attachRemote(surface())
            native.host.buildSurface(7, CallSurfaces.STREAM_CAMERA)
            scenario.waitFor("a picture") { it.canPip() }
            // The window's buttons: Mute, Camera Off, End; each only to the receiver that never answers or places.
            scenario.onActivity { activity ->
                val actions = activity.pipActions(calls.state.value!!, calls.media.value)
                assertEquals(listOf("Mute", "Turn camera off", "End"), actions.map { it.title.toString() })
                val intents = actions.map { shadowOf(it.actionIntent).savedIntent }
                assertEquals(listOf(FaceTimeActionReceiver.ACTION_TOGGLE_MIC, FaceTimeActionReceiver.ACTION_CAMERA_OFF, FaceTimeActionReceiver.ACTION_HANG_UP), intents.map { it.action })
                assertTrue(intents.all { it.component?.className == FaceTimeActionReceiver::class.java.name && it.getStringExtra(FaceTimeActionReceiver.EXTRA_GUID) == IN })
                assertTrue("immutable", actions.all { shadowOf(it.actionIntent).isImmutable })
                assertEquals("each its own", 3, intents.map { it.data }.toSet().size)
                // Back: it floats instead of going behind.
                activity.onBackPressedDispatcher.onBackPressed()
                assertTrue(activity.isInPictureInPictureMode)
                activity.pipChanged(true)
                assertFalse("only the pictures in the window", activity.findViewById<View>(R.id.controls).isShown)
            }
            settle()
            assertTrue("the camera goes on sending in the window", engine.camera.running)
            assertEquals(Phase.ACTIVE, calls.state.value?.phase)
            // The screen goes off: the camera stops at once.
            app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            waitUntil("the camera paused") { !engine.camera.running && native.calls.toList().last { it.startsWith("enableCamera") } == "enableCamera:77:false:-1" }
            assertTrue("the user still has it on", calls.media.value.cameraOn)
            // The screen comes back on, still locked, and the window is started again over the lock
            // screen: its camera stays paused until the unlock.
            val keyguard = shadowOf(app.getSystemService(android.app.KeyguardManager::class.java))
            keyguard.setKeyguardLocked(true)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            settle()
            assertFalse("not sent over the lock screen", engine.camera.running)
            assertEquals("enableCamera:77:false:-1", native.calls.toList().last { it.startsWith("enableCamera") })
            keyguard.setKeyguardLocked(false)
            // Unlocked: back.
            app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
            waitUntil("the camera again") { engine.camera.running && native.calls.toList().last { it.startsWith("enableCamera") } == "enableCamera:77:true:-1" }
            // Out of the window: no longer watched.
            scenario.onActivity { activity ->
                activity.pipChanged(false)
                assertEquals(null, activity.screenOffReceiver)
            }
            calls.hangUp()
            waitUntil("the end") { calls.state.value == null }
        }
    }

    @Test
    fun anAudioCallNeverFloats() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        engine.ring(IN, video = false)
        waitUntil("the ring") { calls.state.value?.phase == Phase.RINGING_IN }
        launchIncoming().use { scenario ->
            scenario.waitFor("the ringing screen") { shown(it, R.id.incoming) }
            scenario.onActivity { it.findViewById<View>(R.id.accept).performClick() }
            waitUntil("the call") { calls.state.value?.phase == Phase.ACTIVE }
            scenario.onActivity { activity ->
                assertFalse(activity.canPip())
                activity.onBackPressedDispatcher.onBackPressed()
                assertFalse(activity.isInPictureInPictureMode)
            }
        }
    }

    private companion object {
        const val IN = "BBBBBBBB-0000-4000-8000-000000000002"
        const val CAROL = "tel:+15553334444"
        const val MOM = "tel:+15550001111"
    }
}
