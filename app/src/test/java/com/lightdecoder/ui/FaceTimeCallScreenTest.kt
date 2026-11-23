package com.lightdecoder.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.ImageReader
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.R
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.facetime.CallMessage
import com.lightdecoder.imessage.facetime.CallRules
import com.lightdecoder.imessage.facetime.CallSurfaces
import com.lightdecoder.imessage.facetime.FaceTimeCalls
import com.lightdecoder.imessage.facetime.FakeBridge.Companion.ALICE
import com.lightdecoder.imessage.facetime.FakeBridge.Companion.ME
import com.lightdecoder.imessage.facetime.FakeCallEngine
import com.lightdecoder.imessage.facetime.Outcome
import com.lightdecoder.imessage.facetime.Phase
import com.lightdecoder.imessage.facetime.Route
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.ui.imessage.FaceTimeCallActivity
import java.io.File
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.rust_bridge.FaceTimeEvent

/**
 * The FaceTime call screen over a stand-in call engine ([FakeCallEngine]): what it shows for a call
 * that rings, is placed, is on, and was not answered; that a call is placed or answered only on the
 * user's tap and only once the microphone is allowed (and what it says when it is not, or the
 * camera is not); what its buttons do; and that the chat opens from it only after the unlock.
 *
 * [screenshotsForReview] draws the screen to PNG files, only when asked (-Dlightdecoder.uiShots=<folder>).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h800dp-xhdpi")
class FaceTimeCallScreenTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var engine: FakeCallEngine
    private val calls: FaceTimeCalls get() = engine.calls
    private val readers = ArrayList<ImageReader>()

    @Before
    fun setUp() {
        engine = FakeCallEngine(app)
        FaceTimeCalls.installForTesting(engine.calls)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
    }

    @After
    fun tearDown() {
        FaceTimeCalls.installForTesting(null)
        engine.close()
        readers.forEach { it.close() }
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            if (condition()) return
            if (System.currentTimeMillis() > deadline) {
                fail("timed out waiting for $what (call ${calls.state.value?.phase}; library ${engine.bridge.log.toList()}; ${engine.natives.made.toList().map { it.calls.toList() }})")
            }
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

    /** Waits for the screen to close itself (it may be gone already). */
    private fun <A : Activity> ActivityScenario<A>.waitToClose() = waitUntil("the screen closing") {
        if (state == androidx.lifecycle.Lifecycle.State.DESTROYED) return@waitUntil true
        var finishing = false
        try {
            onActivity { finishing = it.isFinishing }
        } catch (e: NullPointerException) {
            finishing = true
        }
        finishing
    }

    private fun grant(vararg permissions: String) = shadowOf(app).grantPermissions(*permissions)

    private fun ring(video: Boolean = false) {
        engine.ring(IN, video)
        waitUntil("the ring") { calls.state.value?.phase == Phase.RINGING_IN }
    }

    private fun launchIncoming(): ActivityScenario<FaceTimeCallActivity> = ActivityScenario.launch(FaceTimeCallActivity.incomingIntent(app, IN))

    private fun placeIntent(video: Boolean = false): Intent =
        FaceTimeCallActivity.placeIntent(app, ALICE, "Alice Martin", video, ME, CHAT)

    private fun text(activity: Activity, id: Int): String = activity.findViewById<TextView>(id).text.toString()
    private fun shown(activity: Activity, id: Int): Boolean = activity.findViewById<View>(id).isShown
    private fun click(activity: Activity, id: Int) = activity.findViewById<View>(id).performClick()

    /** Answers Android's permission question as the phone's permissions now stand. */
    private fun answerPermissionQuestion(activity: Activity) {
        val request = shadowOf(activity).lastRequestedPermission
        assertNotNull("a permission was asked for", request)
        val results = request.requestedPermissions.map {
            if (app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }.toIntArray()
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, results)
    }

    private fun surface(): Surface = ImageReader.newInstance(64, 64, PixelFormat.RGBA_8888, 2).also { readers += it }.surface

    // --- a call that rings -----------------------------------------------------------------------

    @Test
    fun aRingingCallShowsWhoCallsWithAcceptAndDecline() {
        ring(video = false)
        launchIncoming().use { scenario ->
            scenario.waitFor("the ringing screen") { shown(it, R.id.incoming) }
            scenario.onActivity { activity ->
                assertEquals("Alice Martin", text(activity, R.id.name))
                assertEquals("Incoming FaceTime Audio", text(activity, R.id.status))
                assertEquals("+15551234567", text(activity, R.id.detail))
                assertTrue(shown(activity, R.id.avatar))
                assertTrue(shown(activity, R.id.accept) && shown(activity, R.id.decline))
                assertFalse("an audio call has no Audio only", shown(activity, R.id.audioOnly))
                assertFalse("no call buttons while it only rings", shown(activity, R.id.controls))
                assertFalse(shown(activity, R.id.endColumn))
                assertEquals("Accept", activity.findViewById<View>(R.id.accept).contentDescription.toString())
                assertEquals("Decline", activity.findViewById<View>(R.id.decline).contentDescription.toString())
                // As a phone call: over the lock screen, the screen on.
                assertTrue(shadowOf(activity).showWhenLocked)
                assertTrue(shadowOf(activity).turnScreenOn)
                assertTrue("the screen stays on while it rings", activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
                assertEquals(android.media.AudioManager.STREAM_VOICE_CALL, activity.volumeControlStream)
            }
            // Showing it answered nothing.
            assertEquals(Phase.RINGING_IN, calls.state.value?.phase)
            assertTrue(engine.natives.made.isEmpty())
            scenario.onActivity { assertNull("nor asked for anything", shadowOf(it).lastRequestedPermission) }
            // Decline.
            scenario.onActivity { click(it, R.id.decline) }
            waitUntil("the decline") { calls.state.value == null && engine.bridge.log.contains("declineFacetime:$IN") }
            scenario.waitToClose()
        }
        assertTrue(engine.natives.made.isEmpty())
    }

    @Test
    fun aRingingVideoCallAlsoOffersAudioOnly() {
        ring(video = true)
        launchIncoming().use { scenario ->
            scenario.waitFor("the ringing screen") { shown(it, R.id.incoming) }
            scenario.onActivity { activity ->
                assertEquals("Incoming FaceTime Video", text(activity, R.id.status))
                assertTrue(shown(activity, R.id.audioOnly))
                assertEquals("Audio only", text(activity, R.id.audioOnly))
            }
        }
    }

    @Test
    fun acceptingAsksForTheMicrophoneAndWithoutItNothingIsAnswered() {
        ring(video = false)
        launchIncoming().use { scenario ->
            scenario.waitFor("the ringing screen") { shown(it, R.id.incoming) }
            scenario.onActivity { activity ->
                click(activity, R.id.accept)
                val request = shadowOf(activity).lastRequestedPermission
                assertEquals(listOf(Manifest.permission.RECORD_AUDIO), request.requestedPermissions.toList())
                assertTrue(activity.waitingForPermissions)
            }
            settle()
            assertEquals("not answered while Android asks", Phase.RINGING_IN, calls.state.value?.phase)
            assertTrue(engine.natives.made.isEmpty())
            // Refused.
            scenario.onActivity { answerPermissionQuestion(it) }
            settle()
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.facetime_mic_needed), text(activity, R.id.notice))
                assertTrue(shown(activity, R.id.notice))
                assertTrue("with a way to the settings", shown(activity, R.id.openSettings))
                assertTrue("it goes on ringing: it can be declined, or answered once allowed", shown(activity, R.id.incoming))
                assertFalse(activity.isFinishing)
            }
            assertEquals(Phase.RINGING_IN, calls.state.value?.phase)
            assertTrue("nothing was joined, nothing opened the microphone", engine.natives.made.isEmpty())
            assertFalse(engine.audio.entered)
            assertEquals(0, engine.foreground.starts)
            // Allowed (in the settings), and accepted again: answered.
            grant(Manifest.permission.RECORD_AUDIO)
            scenario.onActivity { click(it, R.id.accept) }
            waitUntil("the call") { calls.state.value?.phase == Phase.ACTIVE }
            scenario.waitFor("the call's buttons") { shown(it, R.id.controls) && !shown(it, R.id.notice) }
            scenario.onActivity { activity ->
                assertFalse(shown(activity, R.id.incoming))
                assertTrue(shown(activity, R.id.end))
            }
            assertEquals(1, engine.foreground.starts)
        }
    }

    @Test
    fun aVideoCallAcceptedWithoutTheCameraIsAnsweredWithSoundOnlyAndSaysSo() {
        grant(Manifest.permission.RECORD_AUDIO)
        engine.natives.onMade = { it.isVideo = true }
        ring(video = true)
        launchIncoming().use { scenario ->
            scenario.waitFor("the ringing screen") { shown(it, R.id.incoming) }
            scenario.onActivity { activity ->
                click(activity, R.id.accept)
                assertEquals("only what is missing is asked for", listOf(Manifest.permission.CAMERA), shadowOf(activity).lastRequestedPermission.requestedPermissions.toList())
                // Refused.
                answerPermissionQuestion(activity)
            }
            waitUntil("the call") { calls.state.value?.phase == Phase.ACTIVE }
            assertTrue("joined with the camera off", engine.natives.last.calls.contains("enableCamera:77:false:-1"))
            assertTrue(engine.camera.starts.isEmpty())
            scenario.waitFor("what it says") { shown(it, R.id.notice) }
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.facetime_camera_denied), text(activity, R.id.notice))
                assertTrue(shown(activity, R.id.controls))
            }
        }
    }

    @Test
    fun audioOnlyAnswersAVideoCallWithoutAskingForTheCamera() {
        grant(Manifest.permission.RECORD_AUDIO)
        engine.natives.onMade = { it.isVideo = true }
        ring(video = true)
        launchIncoming().use { scenario ->
            scenario.waitFor("the ringing screen") { shown(it, R.id.audioOnly) }
            scenario.onActivity { activity ->
                click(activity, R.id.audioOnly)
                assertNull("nothing is asked for", shadowOf(activity).lastRequestedPermission)
            }
            waitUntil("the call") { calls.state.value?.phase == Phase.ACTIVE }
            assertFalse(calls.state.value!!.withCamera)
            assertTrue(engine.natives.last.calls.contains("enableCamera:77:false:-1"))
        }
    }

    @Test
    fun answerFromTheNoticeAnswersAndOneForACallThatIsGoneClosesTheScreen() {
        grant(Manifest.permission.RECORD_AUDIO)
        ring(video = false)
        ActivityScenario.launch<FaceTimeCallActivity>(FaceTimeCallActivity.answerIntent(app, IN, withCamera = false)).use { scenario ->
            waitUntil("the call") { calls.state.value?.phase == Phase.ACTIVE }
            scenario.waitFor("the call's buttons") { shown(it, R.id.controls) }
            // Turned (the screen made again), the same intent answers nothing again.
            val joins = engine.natives.made.size
            scenario.recreate()
            settle()
            assertEquals(joins, engine.natives.made.size)
            scenario.onActivity { click(it, R.id.end) }
            waitUntil("the end") { calls.state.value == null }
        }
        // The notice outlived its call.
        ActivityScenario.launch<FaceTimeCallActivity>(FaceTimeCallActivity.answerIntent(app, OTHER, withCamera = true)).use { scenario ->
            scenario.waitToClose()
            assertEquals(1, engine.natives.made.size)
        }
        // And one that is no call's id at all.
        ActivityScenario.launch<FaceTimeCallActivity>(FaceTimeCallActivity.answerIntent(app, "not-a-call", withCamera = true)).use { scenario ->
            scenario.waitToClose()
        }
    }

    // --- a placed call ---------------------------------------------------------------------------

    @Test
    fun placingACallAsksForTheMicrophoneAndWithoutItPlacesNothing() {
        ActivityScenario.launch<FaceTimeCallActivity>(placeIntent(video = true)).use { scenario ->
            scenario.onActivity { activity ->
                val request = shadowOf(activity).lastRequestedPermission
                assertEquals(setOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA), request.requestedPermissions.toSet())
                assertEquals("who is to be called shows meanwhile", "Alice Martin", text(activity, R.id.name))
                // Refused, both.
                answerPermissionQuestion(activity)
            }
            settle()
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.facetime_mic_needed), text(activity, R.id.notice))
                assertTrue(shown(activity, R.id.openSettings))
                assertTrue(shown(activity, R.id.close))
                assertFalse(shown(activity, R.id.callAgain))
                assertFalse(activity.isFinishing)
            }
            assertNull(calls.state.value)
            assertTrue("the library was asked nothing", engine.bridge.log.isEmpty())
            assertEquals(0, engine.foreground.starts)
            scenario.onActivity { click(it, R.id.openSettings) }
            assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, shadowOf(app).nextStartedActivity.action)
            scenario.onActivity { click(it, R.id.close) }
            scenario.waitToClose()
        }
    }

    @Test
    fun aVideoCallPlacedWithoutTheCameraGoesAsAnAudioCall() {
        grant(Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch<FaceTimeCallActivity>(placeIntent(video = true)).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(listOf(Manifest.permission.CAMERA), shadowOf(activity).lastRequestedPermission.requestedPermissions.toList())
                answerPermissionQuestion(activity)
            }
            waitUntil("the call placed") { engine.bridge.log.toList().any { it.startsWith("createFacetime") } }
            assertTrue("as audio", engine.bridge.log.toList().single { it.startsWith("createFacetime") }.endsWith(":false"))
            scenario.waitFor("what it says") { shown(it, R.id.notice) && text(it, R.id.notice) == it.getString(R.string.facetime_camera_denied) }
        }
    }

    @Test
    fun aPlacedCallShowsCallingThenTheCallWithItsTime() {
        grant(Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch<FaceTimeCallActivity>(placeIntent(video = false)).use { scenario ->
            scenario.waitFor("the call ringing out") { calls.state.value?.phase == Phase.RINGING_OUT && shown(it, R.id.endColumn) }
            scenario.onActivity { activity ->
                assertNull("nothing to ask for", shadowOf(activity).lastRequestedPermission)
                assertEquals("Alice Martin", text(activity, R.id.name))
                assertEquals("Calling…", text(activity, R.id.status))
                assertFalse(shown(activity, R.id.controls))
                assertFalse(shown(activity, R.id.incoming))
                assertTrue("the chat can be opened from it", shown(activity, R.id.message))
            }
            waitUntil("the call placed") { engine.bridge.log.toList().any { it.startsWith("createFacetime") } }
            val guid = calls.state.value!!.guid
            assertEquals("to the address the chat screen named, from the user's, as audio", "createFacetime:$guid:$ME:$ALICE:false", engine.bridge.log.toList().single { it.startsWith("createFacetime") })
            assertEquals(CHAT, calls.state.value!!.conversationId)
            // The other side joins.
            calls.onEvent(FaceTimeEvent.JoinEvent(guid, 9uL, ALICE, false))
            scenario.waitFor("the call") { calls.state.value?.phase == Phase.ACTIVE && shown(it, R.id.controls) }
            scenario.onActivity { activity ->
                assertTrue("its time, counted up: ${text(activity, R.id.status)}", Regex("\\d\\d:\\d\\d").matches(text(activity, R.id.status)))
                assertTrue(shown(activity, R.id.mute) && shown(activity, R.id.camera) && shown(activity, R.id.speaker) && shown(activity, R.id.end))
                assertFalse("no flip while the camera is off", shown(activity, R.id.flip))
                assertFalse("an audio call's screen may go dark (at the ear)", activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
            }
        }
    }

    @Test
    fun anIntentToPlaceIsActedOnOnlyOnceAndOnlyWhenItIsNew() {
        grant(Manifest.permission.RECORD_AUDIO)
        // Made long ago (kept by something, brought back): nothing is placed.
        val old = placeIntent().putExtra(FaceTimeCallActivity.EXTRA_AT, android.os.SystemClock.elapsedRealtime() - 5 * 60_000)
        ActivityScenario.launch<FaceTimeCallActivity>(old).use { scenario ->
            scenario.waitToClose()
        }
        // Brought back from the recent screens.
        ActivityScenario.launch<FaceTimeCallActivity>(placeIntent().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)).use { scenario ->
            scenario.waitToClose()
        }
        // Someone who is no one to call.
        ActivityScenario.launch<FaceTimeCallActivity>(FaceTimeCallActivity.placeIntent(app, "temp:guest", "", true, null, null)).use { scenario ->
            scenario.waitToClose()
        }
        settle()
        assertTrue("nothing was placed: ${engine.bridge.log}", engine.bridge.log.isEmpty())
        assertNull(calls.state.value)
        // A new one places one call, also when the screen is made again with it.
        ActivityScenario.launch<FaceTimeCallActivity>(placeIntent()).use { scenario ->
            waitUntil("the call placed") { engine.bridge.log.toList().any { it.startsWith("createFacetime") } }
            scenario.recreate()
            settle()
            assertEquals(1, engine.bridge.log.toList().count { it.startsWith("createFacetime") })
            scenario.onActivity { assertEquals("Calling…", text(it, R.id.status)) }
        }
    }

    @Test
    fun aCallNobodyTakesSaysSoAndOffersToCallAgain() {
        grant(Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch<FaceTimeCallActivity>(placeIntent()).use { scenario ->
            waitUntil("the call ringing its thirty seconds") { engine.timers.toList().any { it.first == CallRules.RING_OUT_MS } }
            engine.runOut(CallRules.RING_OUT_MS)
            scenario.waitFor("no answer") { calls.state.value?.phase == Phase.UNANSWERED && shown(it, R.id.unanswered) }
            scenario.onActivity { activity ->
                assertEquals("No answer", text(activity, R.id.status))
                assertTrue(shown(activity, R.id.callAgain) && shown(activity, R.id.close))
                assertFalse(shown(activity, R.id.endColumn))
                assertFalse(shown(activity, R.id.controls))
                click(activity, R.id.callAgain)
            }
            waitUntil("the second call") { engine.bridge.log.toList().count { it.startsWith("createFacetime") } == 2 && calls.state.value?.phase == Phase.RINGING_OUT }
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertEquals("Calling…", text(activity, R.id.status))
                // Taken back with End.
                click(activity, R.id.end)
            }
            waitUntil("the call taken back") { calls.state.value == null }
            scenario.waitToClose()
        }
        waitUntil("the rows") { engine.main.logged.size == 2 }
        assertEquals(listOf(Outcome.UNANSWERED, Outcome.CANCELLED), engine.main.logged.map { it.outcome })
    }

    @Test
    fun callAgainOnALockedPhonePlacesNothingUntilItIsUnlocked() {
        grant(Manifest.permission.RECORD_AUDIO)
        val keyguard = shadowOf(app.getSystemService(KeyguardManager::class.java))
        ActivityScenario.launch<FaceTimeCallActivity>(placeIntent()).use { scenario ->
            waitUntil("the call ringing its thirty seconds") { engine.timers.toList().any { it.first == CallRules.RING_OUT_MS } }
            engine.runOut(CallRules.RING_OUT_MS)
            scenario.waitFor("no answer") { calls.state.value?.phase == Phase.UNANSWERED && shown(it, R.id.callAgain) }
            fun placed() = engine.bridge.log.toList().count { it.startsWith("createFacetime") }
            assertEquals(1, placed())
            // The phone locks with "No answer" on its screen (which shows over the lock screen): Call Again places nothing.
            keyguard.setKeyguardLocked(true)
            scenario.onActivity { click(it, R.id.callAgain) }
            settle()
            assertEquals("no call is placed from a locked phone", 1, placed())
            assertEquals(Phase.UNANSWERED, calls.state.value?.phase)
            // Tapped again and again: still nothing.
            scenario.onActivity { click(it, R.id.callAgain) }
            settle()
            assertEquals(1, placed())
            // Unlocked by the user: now the call is placed.
            keyguard.setKeyguardLocked(false)
            waitUntil("the second call, after the unlock") { placed() == 2 && calls.state.value?.phase == Phase.RINGING_OUT }
            scenario.onActivity { click(it, R.id.end) }
            waitUntil("the call taken back") { calls.state.value == null }
        }
    }

    @Test
    fun anIntentToPlaceACallOnALockedPhonePlacesNothingUntilItIsUnlocked() {
        grant(Manifest.permission.RECORD_AUDIO)
        val keyguard = shadowOf(app.getSystemService(KeyguardManager::class.java))
        keyguard.setKeyguardLocked(true)
        ActivityScenario.launch<FaceTimeCallActivity>(placeIntent()).use {
            settle()
            assertNull(calls.state.value)
            assertTrue(engine.bridge.log.toList().none { it.startsWith("createFacetime") })
            keyguard.setKeyguardLocked(false)
            waitUntil("the call, after the unlock") { calls.state.value?.phase == Phase.RINGING_OUT }
            calls.hangUp()
            waitUntil("taken back") { calls.state.value == null }
        }
    }

    @Test
    fun theCameraStopsWhileTheCallScreenIsOutOfSightAndComesBackWithIt() {
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        ring(video = true)
        engine.natives.onMade = { it.isVideo = true }
        launchIncoming().use { scenario ->
            scenario.waitFor("the ringing screen") { shown(it, R.id.incoming) }
            scenario.onActivity { click(it, R.id.accept) }
            scenario.waitFor("the call") { calls.state.value?.phase == Phase.ACTIVE && shown(it, R.id.controls) }
            val native = engine.natives.last
            native.host!!.encoderSurface(surface(), 1)
            waitUntil("the camera") { engine.camera.running && native.calls.contains("enableCamera:77:true:-1") }
            // Home, another app, or the phone locked and dark: the screen is stopped.
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            waitUntil("the camera stopped") { !engine.camera.running && native.calls.toList().last { it.startsWith("enableCamera") } == "enableCamera:77:false:-1" }
            assertEquals("the call goes on", Phase.ACTIVE, calls.state.value?.phase)
            assertTrue("under its notice", engine.foreground.running)
            assertTrue(native.calls.none { it.startsWith("destroyAvc") })
            // Back: the camera again, as the user had it.
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            waitUntil("the camera again") { engine.camera.running && native.calls.toList().last { it.startsWith("enableCamera") } == "enableCamera:77:true:-1" }
            scenario.waitFor("its button still on") { it.findViewById<View>(R.id.camera).isSelected }
            // Only paused in front of something else (a permission question, the notification shade), it keeps running.
            val starts = engine.camera.starts.size
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            settle()
            assertTrue(engine.camera.running)
            assertEquals(starts, engine.camera.starts.size)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            calls.hangUp()
            waitUntil("the end") { calls.state.value == null }
        }
    }

    @Test
    fun theButtonsThatAnswerOrPlaceACallTakeNoTapThroughAnotherAppsWindow() {
        ring(video = true)
        launchIncoming().use { scenario ->
            scenario.waitFor("the ringing screen") { shown(it, R.id.incoming) }
            scenario.onActivity { activity ->
                for (id in listOf(R.id.accept, R.id.audioOnly, R.id.callAgain)) {
                    assertTrue(activity.resources.getResourceEntryName(id), activity.findViewById<View>(id).filterTouchesWhenObscured)
                }
                // A tap that came through a window of another app over the button is not a click.
                val accept = activity.findViewById<View>(R.id.accept)
                val at = android.os.SystemClock.uptimeMillis()
                fun touch(action: Int, flags: Int) = android.view.MotionEvent.obtain(
                    at, at, action, 1,
                    arrayOf(android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER }),
                    arrayOf(android.view.MotionEvent.PointerCoords().apply { x = 5f; y = 5f; pressure = 1f; size = 1f }),
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, flags,
                )
                val obscured = android.view.MotionEvent.FLAG_WINDOW_IS_OBSCURED
                assertTrue("a tap that came straight to it is taken", accept.dispatchTouchEvent(touch(android.view.MotionEvent.ACTION_DOWN, 0)))
                accept.dispatchTouchEvent(touch(android.view.MotionEvent.ACTION_CANCEL, 0))
                assertFalse(accept.dispatchTouchEvent(touch(android.view.MotionEvent.ACTION_DOWN, obscured)))
                assertFalse(accept.dispatchTouchEvent(touch(android.view.MotionEvent.ACTION_UP, obscured)))
            }
            settle()
            assertEquals("still ringing: nothing was answered", Phase.RINGING_IN, calls.state.value?.phase)
        }
    }

    @Test
    fun whyACallWasNotStartedIsSaid() {
        grant(Manifest.permission.RECORD_AUDIO)
        engine.settings = com.lightdecoder.imessage.facetime.FaceTimeSettings(on = false)
        ActivityScenario.launch<FaceTimeCallActivity>(placeIntent()).use { scenario ->
            scenario.waitToClose()
        }
        assertEquals(app.getString(R.string.facetime_refused_off), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        assertTrue(engine.bridge.log.isEmpty())
    }

    // --- a call that is on -----------------------------------------------------------------------

    @Test
    fun inACallTheButtonsDoWhatTheySay() {
        grant(Manifest.permission.RECORD_AUDIO)
        ring(video = false)
        launchIncoming().use { scenario ->
            scenario.waitFor("the ringing screen") { shown(it, R.id.incoming) }
            scenario.onActivity { click(it, R.id.accept) }
            scenario.waitFor("the call") { calls.state.value?.phase == Phase.ACTIVE && shown(it, R.id.controls) }
            val native = engine.natives.last
            // Mute, and back.
            scenario.onActivity { click(it, R.id.mute) }
            waitUntil("muted") { native.calls.toList().last() == "enableMicrophone:77:false" }
            scenario.waitFor("the button saying so") { text(it, R.id.muteLabel) == "Unmute" && it.findViewById<View>(R.id.mute).isSelected }
            scenario.onActivity { click(it, R.id.mute) }
            waitUntil("unmuted") { native.calls.toList().last() == "enableMicrophone:77:true" }
            scenario.waitFor("the button saying so") { text(it, R.id.muteLabel) == "Mute" && !it.findViewById<View>(R.id.mute).isSelected }
            // The speaker, and back to the earpiece.
            assertEquals(Route.EARPIECE, calls.media.value.route)
            scenario.onActivity { click(it, R.id.speaker) }
            scenario.waitFor("on the speaker") { calls.media.value.route == Route.SPEAKER && it.findViewById<View>(R.id.speaker).isSelected }
            assertEquals(Route.SPEAKER, engine.audio.routed.last())
            scenario.onActivity { click(it, R.id.speaker) }
            scenario.waitFor("on the earpiece") { calls.media.value.route == Route.EARPIECE && !it.findViewById<View>(R.id.speaker).isSelected }
            // The camera: asked for first; refused, it stays off and says why.
            scenario.onActivity { activity ->
                click(activity, R.id.camera)
                assertEquals(listOf(Manifest.permission.CAMERA), shadowOf(activity).lastRequestedPermission.requestedPermissions.toList())
                answerPermissionQuestion(activity)
            }
            settle()
            scenario.onActivity { assertEquals(it.getString(R.string.facetime_camera_denied), text(it, R.id.notice)) }
            assertFalse(calls.media.value.cameraOn)
            assertFalse(native.calls.contains("enableCamera:77:true:-1"))
            // Allowed: on (the call is a video call from then on), with Flip beside it and the small view of it.
            grant(Manifest.permission.CAMERA)
            native.host!!.encoderSurface(surface(), 1)
            scenario.onActivity { click(it, R.id.camera) }
            waitUntil("the camera") { engine.camera.running && native.calls.contains("enableCamera:77:true:-1") }
            scenario.waitFor("the camera's buttons") { shown(it, R.id.flip) && it.findViewById<View>(R.id.camera).isSelected && it.findViewById<View>(R.id.previewCard).alpha == 1f }
            scenario.onActivity { activity ->
                assertTrue("the screen stays on with a camera running", activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
                click(activity, R.id.flip)
            }
            waitUntil("the other camera") { engine.camera.starts.toList().last().startsWith("front=false") }
            scenario.onActivity { click(it, R.id.camera) }
            waitUntil("the camera off") { !engine.camera.running && native.calls.toList().contains("enableCamera:77:false:-1") }
            scenario.waitFor("no flip, no small view") { !shown(it, R.id.flip) && it.findViewById<View>(R.id.previewCard).alpha == 0f }
            // End.
            scenario.onActivity { click(it, R.id.end) }
            waitUntil("the end") { calls.state.value == null && native.calls.toList().last() == "destroyAvc:77" }
            scenario.waitToClose()
            assertTrue("no function of the library ran on the main thread", native.onMainThread.isEmpty())
        }
    }

    @Test
    fun withAHeadsetTheSpeakerButtonListsTheRoutes() {
        grant(Manifest.permission.RECORD_AUDIO)
        engine.audio.available = listOf(Route.EARPIECE, Route.SPEAKER, Route.BLUETOOTH)
        ring()
        launchIncoming().use { scenario ->
            scenario.waitFor("the ringing screen") { shown(it, R.id.incoming) }
            scenario.onActivity { click(it, R.id.accept) }
            scenario.waitFor("the call on the headset") { calls.state.value?.phase == Phase.ACTIVE && text(it, R.id.speakerLabel) == "Bluetooth" }
            scenario.onActivity { click(it, R.id.speaker) }
            settle()
            val popup = org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu()
            assertEquals(listOf("Phone", "Speaker", "Bluetooth"), (0 until popup.menu.size()).map { popup.menu.getItem(it).title.toString() })
            assertTrue(popup.menu.getItem(2).isChecked)
            popup.menu.performIdentifierAction(popup.menu.getItem(1).itemId, 0)
            scenario.waitFor("on the speaker") { calls.media.value.route == Route.SPEAKER && text(it, R.id.speakerLabel) == "Speaker" }
        }
    }

    @Test
    fun theOtherSidesPictureTakesTheScreenAndTheNameMovesToTheTop() {
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        engine.natives.onMade = { it.isVideo = true }
        ring(video = true)
        launchIncoming().use { scenario ->
            scenario.waitFor("the ringing screen") { shown(it, R.id.incoming) }
            scenario.onActivity { click(it, R.id.accept) }
            scenario.waitFor("the call") { calls.state.value?.phase == Phase.ACTIVE && shown(it, R.id.controls) }
            scenario.onActivity { activity ->
                assertTrue("without a picture yet, the person stands in the middle", shown(activity, R.id.avatar) && shown(activity, R.id.backdrop))
            }
            // The library asks for the view of the other side's camera, and gets it.
            val native = engine.natives.last
            calls.attachRemote(surface())
            assertNotNull(native.host!!.buildSurface(7, CallSurfaces.STREAM_CAMERA))
            native.host.videoSize(7, CallSurfaces.STREAM_CAMERA, 1920, 1080)
            native.host.streamRotation(7, CallSurfaces.STREAM_CAMERA, 0)
            scenario.waitFor("the picture's screen") { !shown(it, R.id.backdrop) && !shown(it, R.id.avatar) }
            scenario.onActivity { activity ->
                assertTrue(shown(activity, R.id.name) && shown(activity, R.id.status))
                assertEquals("Alice Martin", text(activity, R.id.name))
                assertFalse("the address makes way for the picture", shown(activity, R.id.detail))
                assertTrue(shown(activity, R.id.scrimTop) && shown(activity, R.id.scrimBottom))
                assertEquals("Video from Alice Martin", activity.findViewById<View>(R.id.remoteVideo).contentDescription.toString())
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
            }
            // It stops: the person again.
            native.host.streamsRemoved(7, intArrayOf(CallSurfaces.STREAM_CAMERA))
            scenario.waitFor("the person again") { shown(it, R.id.backdrop) && shown(it, R.id.avatar) }
            // The camera cannot be used after all: said, and the call goes on.
            engine.camera.works = false
            native.host.encoderSurface(surface(), 1)
            scenario.waitFor("what it says") { shown(it, R.id.notice) && text(it, R.id.notice) == it.getString(R.string.facetime_camera_unavailable) }
            assertEquals(Phase.ACTIVE, calls.state.value?.phase)
        }
    }

    @Test
    fun theOtherSideHangingUpClosesTheScreen() {
        grant(Manifest.permission.RECORD_AUDIO)
        ring()
        launchIncoming().use { scenario ->
            scenario.waitFor("the ringing screen") { shown(it, R.id.incoming) }
            scenario.onActivity { click(it, R.id.accept) }
            scenario.waitFor("the call") { calls.state.value?.phase == Phase.ACTIVE && shown(it, R.id.controls) }
            val host = engine.natives.last.host!!
            host.participants(longArrayOf(7), arrayOf(ALICE))
            host.participants(LongArray(0), arrayOfNulls(0))
            waitUntil("the end") { calls.state.value == null }
            scenario.waitToClose()
        }
    }

    @Test
    fun leavingTheScreenLeavesTheCallOn() {
        grant(Manifest.permission.RECORD_AUDIO)
        ring()
        launchIncoming().use { scenario ->
            scenario.waitFor("the ringing screen") { shown(it, R.id.incoming) }
            scenario.onActivity { click(it, R.id.accept) }
            scenario.waitFor("the call") { calls.state.value?.phase == Phase.ACTIVE && shown(it, R.id.controls) }
            scenario.onActivity { activity ->
                click(activity, R.id.minimize)
                activity.onBackPressedDispatcher.onBackPressed()
                assertFalse("the screen steps back, it does not end", activity.isFinishing)
            }
            settle()
            assertEquals(Phase.ACTIVE, calls.state.value?.phase)
            assertTrue(engine.natives.last.calls.none { it.startsWith("destroyAvc") })
        }
    }

    // --- the chat, only after the unlock ---------------------------------------------------------

    @Test
    fun theChatOpensFromTheCallScreenOnlyAfterTheUnlock() {
        grant(Manifest.permission.RECORD_AUDIO)
        val keyguard = shadowOf(app.getSystemService(KeyguardManager::class.java))
        ring()
        launchIncoming().use { scenario ->
            scenario.waitFor("the ringing screen") { shown(it, R.id.incoming) && shown(it, R.id.message) }
            // Locked: asking for the chat asks for the unlock, and opens nothing.
            keyguard.setKeyguardLocked(true)
            scenario.onActivity { click(it, R.id.message) }
            settle()
            assertNull("nothing of the chats shows over the lock screen", shadowOf(app).nextStartedActivity)
            // Unlocked: now it opens.
            keyguard.setKeyguardLocked(false)
            settle()
            val opened = shadowOf(app).nextStartedActivity
            assertEquals(ThreadActivity::class.java.name, opened.component?.className)
            assertEquals(CHAT, opened.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID))
            assertEquals(ChatService.IMESSAGE, RelayIntents.serviceOf(opened))
            // The unlock given up: nothing opens.
            keyguard.setKeyguardLocked(true)
            scenario.onActivity { click(it, R.id.message) }
            keyguard.setKeyguardLocked(true)
            settle()
            assertNull(shadowOf(app).nextStartedActivity)
            // Not locked at all: at once.
            keyguard.setKeyguardLocked(false)
            shadowOf(app).clearNextStartedActivities()
            scenario.onActivity { click(it, R.id.message) }
            assertEquals(ThreadActivity::class.java.name, shadowOf(app).nextStartedActivity.component?.className)
        }
    }

    @Test
    fun aCallerWithNoChatHasNoMessageButton() {
        engine.main.callers = { com.lightdecoder.imessage.facetime.CallerInfo("", known = false) }
        ring()
        launchIncoming().use { scenario ->
            scenario.waitFor("the ringing screen") { shown(it, R.id.incoming) }
            scenario.onActivity { activity ->
                assertFalse(shown(activity, R.id.message))
                assertEquals("an unknown caller is shown by their address", "+15551234567", text(activity, R.id.name))
                assertFalse("which is not said twice", shown(activity, R.id.detail))
            }
        }
    }

    @Test
    fun withNoCallThereIsNoScreen() {
        ActivityScenario.launch<FaceTimeCallActivity>(FaceTimeCallActivity.showIntent(app)).use { scenario ->
            scenario.waitToClose()
        }
        ActivityScenario.launch<FaceTimeCallActivity>(FaceTimeCallActivity.incomingIntent(app, IN)).use { scenario ->
            scenario.waitToClose()
        }
    }

    // --- how the pictures are laid out -----------------------------------------------------------

    @Test
    fun thePicturesKeepTheirShape() {
        fun scales(m: android.graphics.Matrix): Pair<Float, Float> = FloatArray(9).also(m::getValues).let { it[android.graphics.Matrix.MSCALE_X] to it[android.graphics.Matrix.MSCALE_Y] }
        // An upright picture in an upright view of another shape fills it: its sides are cut, never stretched.
        val (sx, sy) = scales(FaceTimeCallActivity.remoteTransform(1000, 2000, 1080, 1920))
        assertEquals(1.125f, sx, 0.001f)
        assertEquals(1f, sy, 0.001f)
        // A picture on its side in an upright view is shown whole, with bars.
        val (lx, ly) = scales(FaceTimeCallActivity.remoteTransform(1000, 2000, 1920, 1080))
        assertEquals(1f, lx, 0.001f)
        assertEquals(0.28125f, ly, 0.001f)
        // The same shape: as it is. Nothing known yet: as it is.
        assertTrue(FaceTimeCallActivity.remoteTransform(1080, 1920, 1080, 1920).isIdentity)
        assertTrue(FaceTimeCallActivity.remoteTransform(1000, 2000, 0, 0).isIdentity)
        assertTrue(FaceTimeCallActivity.remoteTransform(0, 0, 1080, 1920).isIdentity)
        // The user's own camera in its small view (2:3), upright: the 9:16 picture fills it, cut at top and bottom.
        val (px, py) = scales(FaceTimeCallActivity.previewTransform(200, 300, Surface.ROTATION_0))
        assertEquals(1f, px, 0.001f)
        assertEquals(1.185f, py, 0.001f)
        assertTrue(FaceTimeCallActivity.previewTransform(0, 0, Surface.ROTATION_0).isIdentity)
        for (rotation in listOf(Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270)) {
            assertFalse(FaceTimeCallActivity.previewTransform(200, 300, rotation).isIdentity)
        }
    }

    // --- the settings ---------------------------------------------------------------------------

    @Test
    fun theTwoFaceTimeSettingsAreOnTheIMessageScreenAndAreKept() {
        com.lightdecoder.imessage.direct.DirectPrefs.setFaceTimeOn(app, true)
        com.lightdecoder.imessage.direct.DirectPrefs.setSilenceUnknownFaceTime(app, false)
        try {
            assertTrue("FaceTime is on by default", com.lightdecoder.imessage.direct.DirectPrefs.faceTimeOn(app))
            assertFalse("unknown callers ring by default", com.lightdecoder.imessage.direct.DirectPrefs.silenceUnknownFaceTime(app))
            assertEquals(com.lightdecoder.imessage.facetime.FaceTimeSettings(on = true, silenceUnknown = false), com.lightdecoder.imessage.facetime.FaceTimeParts.settings(app))
            ActivityScenario.launch(com.lightdecoder.ui.imessage.DirectSetupActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val faceTime = activity.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchFaceTime)
                    val silence = activity.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchSilenceUnknown)
                    assertEquals("FaceTime", faceTime.text.toString())
                    assertEquals("Silence unknown FaceTime callers", silence.text.toString())
                    assertTrue(faceTime.isChecked)
                    assertFalse(silence.isChecked)
                    assertTrue(silence.isEnabled)
                    silence.isChecked = true
                    assertTrue(com.lightdecoder.imessage.direct.DirectPrefs.silenceUnknownFaceTime(activity))
                    faceTime.isChecked = false
                    assertFalse(com.lightdecoder.imessage.direct.DirectPrefs.faceTimeOn(activity))
                    assertFalse("with FaceTime off there is nobody to silence", silence.isEnabled)
                }
            }
            // Kept (files both processes read), and read as the call engine reads them.
            assertEquals(com.lightdecoder.imessage.facetime.FaceTimeSettings(on = false, silenceUnknown = true), com.lightdecoder.imessage.facetime.FaceTimeParts.settings(app))
            ActivityScenario.launch(com.lightdecoder.ui.imessage.DirectSetupActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val faceTime = activity.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchFaceTime)
                    val silence = activity.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchSilenceUnknown)
                    assertFalse(faceTime.isChecked)
                    assertTrue(silence.isChecked)
                    faceTime.isChecked = true
                    silence.isChecked = false
                }
            }
            assertTrue(com.lightdecoder.imessage.direct.DirectPrefs.faceTimeOn(app))
            assertFalse(com.lightdecoder.imessage.direct.DirectPrefs.silenceUnknownFaceTime(app))
        } finally {
            com.lightdecoder.imessage.direct.DirectPrefs.setFaceTimeOn(app, true)
            com.lightdecoder.imessage.direct.DirectPrefs.setSilenceUnknownFaceTime(app, false)
        }
    }

    @Test
    fun theIMessageScreenSaysWhatKeepsACallFromRinging() {
        // Notifications off: nothing can ring.
        shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(false)
        ActivityScenario.launch(com.lightdecoder.ui.imessage.DirectSetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(shown(activity, R.id.faceTimeStatus))
                assertEquals(activity.getString(R.string.facetime_notifications_off), text(activity, R.id.faceTimeStatus))
                click(activity, R.id.faceTimeFix)
                assertEquals(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS, shadowOf(app).nextStartedActivity.action)
                // With FaceTime off there is nothing to fix.
                activity.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchFaceTime).isChecked = false
                assertFalse(shown(activity, R.id.faceTimeStatus))
                assertFalse(shown(activity, R.id.faceTimeFix))
                activity.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchFaceTime).isChecked = true
            }
        }
        // Notifications on, but Android does not let the call screen show over the lock screen (Android 14+; as it is here).
        shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(true)
        assertFalse(app.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent())
        ActivityScenario.launch(com.lightdecoder.ui.imessage.DirectSetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.facetime_full_screen_help), text(activity, R.id.faceTimeStatus))
                shadowOf(app).clearNextStartedActivities()
                click(activity, R.id.faceTimeFix)
                val settings = shadowOf(app).nextStartedActivity
                assertEquals(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, settings.action)
                assertEquals("package:" + app.packageName, settings.dataString)
            }
        }
    }

    @Test
    fun registeringAgainIsRefusedDuringACall() {
        grant(Manifest.permission.RECORD_AUDIO)
        ring()
        assertTrue(calls.busy)
        ActivityScenario.launch(com.lightdecoder.ui.imessage.DirectSetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<TextView>(R.id.relayCode).text = "ABCD-EFGH"
                click(activity, R.id.register)
            }
            settle()
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            assertNotNull(dialog)
            assertTrue(dialog.isShowing)
            scenario.onActivity { activity ->
                assertNull("no text message permission was asked for: nothing was begun", shadowOf(activity).lastRequestedPermission)
            }
        }
    }

    // --- screenshots for review --------------------------------------------------------------

    /**
     * Draws the call screen (a video call ringing, an audio call that is on, a video call that is
     * on, with stand-ins where the camera pictures would be) to PNG files in the folder
     * -Dlightdecoder.uiShots names. Skipped without it: the suite writes nothing.
     */
    @Test
    fun screenshotsForReview() {
        val folder = System.getProperty(SHOTS_PROPERTY).orEmpty()
        assumeTrue("screenshots only when asked: -D$SHOTS_PROPERTY=<folder>", folder.isNotEmpty())
        val out = File(folder).apply { mkdirs() }
        RuntimeEnvironment.setQualifiers("+night")
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        // A video call ringing.
        ring(video = true)
        launchIncoming().use { scenario ->
            scenario.waitFor("the ringing screen") { shown(it, R.id.incoming) && shown(it, R.id.audioOnly) }
            scenario.onActivity { shoot(it, File(out, "facetime-incoming-dark.png")) }
            // Taken with sound only: an audio call that is on.
            scenario.onActivity { click(it, R.id.audioOnly) }
            scenario.waitFor("the call") { calls.state.value?.phase == Phase.ACTIVE && shown(it, R.id.controls) }
            scenario.waitFor("its time") { Regex("\\d\\d:\\d\\d").matches(text(it, R.id.status)) }
            scenario.onActivity { shoot(it, File(out, "facetime-audio-dark.png")) }
            scenario.onActivity { click(it, R.id.end) }
            waitUntil("the end") { calls.state.value == null }
        }
        // A video call that is on: the other side's picture full screen, the user's own in the corner.
        engine.natives.onMade = { it.isVideo = true }
        // From someone else: the same caller rings once in ten seconds.
        engine.ring(OTHER, video = true, caller = com.lightdecoder.imessage.facetime.FakeBridge.BOB)
        waitUntil("the ring") { calls.state.value?.phase == Phase.RINGING_IN }
        ActivityScenario.launch<FaceTimeCallActivity>(FaceTimeCallActivity.incomingIntent(app, OTHER)).use { scenario ->
            scenario.waitFor("the ringing screen") { shown(it, R.id.incoming) }
            scenario.onActivity { click(it, R.id.accept) }
            scenario.waitFor("the call") { calls.state.value?.phase == Phase.ACTIVE && shown(it, R.id.controls) }
            val native = engine.natives.last
            native.host!!.encoderSurface(surface(), 1)
            calls.attachRemote(surface())
            native.host.buildSurface(7, CallSurfaces.STREAM_CAMERA)
            native.host.videoSize(7, CallSurfaces.STREAM_CAMERA, 1080, 1920)
            scenario.waitFor("the pictures' screen") { !shown(it, R.id.backdrop) && it.findViewById<View>(R.id.previewCard).alpha == 1f && engine.camera.running }
            scenario.onActivity { activity ->
                // Where the camera pictures would be (a drawing to a file has none): stand-ins, right over the two views.
                val density = activity.resources.displayMetrics.density
                activity.findViewById<FrameLayout>(R.id.root).addView(
                    View(activity).apply {
                        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF27415A.toInt(), 0xFF7A5C4B.toInt(), 0xFF2C2A3A.toInt()))
                    },
                    1, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
                )
                // The two views themselves draw nothing sensible into a file.
                activity.findViewById<View>(R.id.localPreview).visibility = View.INVISIBLE
                activity.findViewById<FrameLayout>(R.id.previewCard).addView(
                    View(activity).apply {
                        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF4E6E58.toInt(), 0xFF2E3B4E.toInt())).apply { cornerRadius = 18 * density }
                    },
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
                )
            }
            settle()
            scenario.onActivity { shoot(it, File(out, "facetime-video-dark.png")) }
        }
    }

    private fun shoot(activity: Activity, file: File) {
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val IN = "BBBBBBBB-0000-4000-8000-000000000002"
        const val OTHER = "CCCCCCCC-0000-4000-8000-000000000003"
        const val CHAT = "imsg:tel:+15551234567"
        const val SHOTS_PROPERTY = "lightdecoder.uiShots"
    }
}
