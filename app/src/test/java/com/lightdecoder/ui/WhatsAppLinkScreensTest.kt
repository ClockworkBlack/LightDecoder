package com.lightdecoder.ui

import android.app.Activity
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.R
import com.lightdecoder.relay.RelayState
import com.lightdecoder.ui.wa.WaSetupActivity
import com.lightdecoder.ui.wa.WaStatusActivity
import com.lightdecoder.wa.WaLog
import com.lightdecoder.wa.WaPrefs
import com.lightdecoder.wa.WaTestEnv
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

/**
 * The WhatsApp setup and settings screens against the real WhatsApp runtime
 * over a fake bridge. The phone's side of linking is always the test
 * calling [com.lightdecoder.wa.FakeWaBridge.completePairing]: the screen
 * only shows the code.
 */
@RunWith(AndroidJUnit4::class)
class WhatsAppLinkScreensTest {

    private lateinit var env: WaTestEnv

    @Before
    fun setUp() {
        env = WaTestEnv()
    }

    @After
    fun tearDown() {
        env.tearDown()
    }

    // --- setup ------------------------------------------------------------------------------

    @Test
    fun setupStartsAtTheDisclosureAndWaitsForTheTick() {
        env.manager()
        ActivityScenario.launch(WaSetupActivity::class.java).use { scenario ->
            settle()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.stepIntro).isShown)
                assertFalse(activity.findViewById<View>(R.id.stepNumber).isShown)
                assertTrue(hasText(activity, activity.getString(R.string.wa_intro_terms)))
                assertFalse("Continue waits for the user to tick that they understood",
                    activity.findViewById<View>(R.id.introContinue).isEnabled)
            }
            assertNull("nothing is loaded or asked before the user goes on", env.fake)
        }
    }

    @Test
    fun aPairingCodeIsShownAndThePhoneConfirmingFinishesSetup() {
        val manager = env.manager()
        ActivityScenario.launch(WaSetupActivity::class.java).use { scenario ->
            scenario.toNumberStep()
            scenario.onActivity { activity ->
                activity.findViewById<TextView>(R.id.number).text = "+1 555 010 0100"
                activity.findViewById<View>(R.id.getCode).performClick()
            }
            scenario.waitFor("the pairing code") { it.findViewById<View>(R.id.stepCode).isShown }
            scenario.onActivity { activity ->
                assertEquals("ABCD-2345", activity.findViewById<TextView>(R.id.code).text.toString())
                assertTrue(hasText(activity, activity.getString(R.string.wa_code_steps)))
            }
            assertFalse("showing a code links nothing", manager.isPaired())
            assertEquals(listOf("+1 555 010 0100"), requireNotNull(env.fake).pairPhoneNumbers.toList())

            // The user typed the code into WhatsApp on their phone.
            requireNotNull(env.fake).completePairing()
            scenario.waitFor("the done step") { it.findViewById<View>(R.id.stepDone).isShown }
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.wa_done_title), activity.findViewById<TextView>(R.id.doneTitle).text.toString())
            }
            assertTrue(manager.isPaired())
        }
        assertFalse(WaLog.read(env.context).joinToString("\n").contains("ABCD2345"))
    }

    @Test
    fun aNumberWithoutCountryCodeIsRefusedHere() {
        env.manager()
        ActivityScenario.launch(WaSetupActivity::class.java).use { scenario ->
            scenario.toNumberStep()
            scenario.onActivity { activity ->
                activity.findViewById<TextView>(R.id.number).text = "12345"
                activity.findViewById<View>(R.id.getCode).performClick()
            }
            scenario.waitFor("the error") { it.findViewById<View>(R.id.numberError).isShown }
            scenario.onActivity { activity ->
                assertEquals(
                    activity.getString(R.string.wa_number_invalid),
                    activity.findViewById<TextView>(R.id.numberError).text.toString(),
                )
            }
            assertNull("WhatsApp was not asked", env.fake)
        }
    }

    @Test
    fun aQrCodeIsDrawnAndCancellingGoesBackToTheNumber() {
        val manager = env.manager()
        ActivityScenario.launch(WaSetupActivity::class.java).use { scenario ->
            scenario.toNumberStep()
            scenario.onActivity { it.findViewById<View>(R.id.useQr).performClick() }
            waitUntil("the linking connection") { env.fake?.linking == true }
            requireNotNull(env.fake).emitQr("2@abc,def,ghi")
            scenario.waitFor("the QR code") {
                it.findViewById<View>(R.id.stepQr).isShown && it.findViewById<ImageView>(R.id.qr).drawable != null
            }
            scenario.onActivity { it.findViewById<View>(R.id.qrUseCode).performClick() }
            scenario.waitFor("the number step") { it.findViewById<View>(R.id.stepNumber).isShown }
            waitUntil("the bridge to be told") { requireNotNull(env.fake).calls.contains("cancelPairing") }
            assertFalse(manager.isPaired())
        }
    }

    @Test
    fun leavingTheScreenAbandonsAnUnfinishedAttempt() {
        env.manager()
        val scenario = ActivityScenario.launch(WaSetupActivity::class.java)
        scenario.toNumberStep()
        scenario.onActivity { activity ->
            activity.findViewById<TextView>(R.id.number).text = "+15550100100"
            activity.findViewById<View>(R.id.getCode).performClick()
        }
        scenario.waitFor("the pairing code") { it.findViewById<View>(R.id.stepCode).isShown }
        scenario.onActivity { it.finish() }
        scenario.close()
        waitUntil("the attempt to be abandoned") { requireNotNull(env.fake).calls.contains("cancelPairing") }
    }

    @Test
    fun setupSaysAlreadyLinkedWhenLinked() {
        env.linkedManager()
        ActivityScenario.launch(WaSetupActivity::class.java).use { scenario ->
            scenario.waitFor("the already-linked step") { it.findViewById<View>(R.id.stepDone).isShown }
            scenario.onActivity { activity ->
                assertEquals(
                    activity.getString(R.string.wa_done_already_title),
                    activity.findViewById<TextView>(R.id.doneTitle).text.toString(),
                )
            }
        }
    }

    @Test
    fun theQrBitmapIsBlackOnWhiteAndScannableInSize() {
        val bitmap = requireNotNull(WaSetupActivity.qrBitmap("2@abc,def,ghi"))
        assertEquals(bitmap.width, bitmap.height)
        assertTrue("several pixels per module", bitmap.width >= 21 * 8)
        val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        assertEquals(setOf(Color.BLACK, Color.WHITE), pixels.toSet())
        assertEquals("a quiet zone around it", Color.WHITE, bitmap.getPixel(0, 0))
        assertNull(WaSetupActivity.qrBitmap(""))
        assertEquals("ABCD-2345", WaSetupActivity.spaced("ABCD2345"))
        assertEquals("an unexpected length is shown as it is", "ABC", WaSetupActivity.spaced("ABC"))
    }

    // --- status and settings ------------------------------------------------------------------

    @Test
    fun statusOffersLinkingWhenUnlinked() {
        env.manager()
        ActivityScenario.launch(WaStatusActivity::class.java).use { scenario ->
            scenario.waitFor("the state") { it.findViewById<View>(R.id.setUp).isShown }
            scenario.onActivity { activity ->
                assertEquals(
                    activity.getString(R.string.wa_state_unpaired),
                    activity.findViewById<TextView>(R.id.stateTitle).text.toString(),
                )
                assertFalse("no switches without a link", activity.findViewById<View>(R.id.controls).isShown)
            }
            assertNull("looking at the settings loads nothing", env.fake)
        }
    }

    @Test
    fun statusShowsTheConnectionAndDisconnectKeepsTheLink() {
        val (manager, fake) = env.connectedManager()
        ActivityScenario.launch(WaStatusActivity::class.java).use { scenario ->
            scenario.waitFor("connected") {
                it.findViewById<TextView>(R.id.stateTitle).text.toString() == it.getString(R.string.wa_state_connected)
            }
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.controls).isShown)
                assertTrue(activity.findViewById<CompoundButton>(R.id.switchLive).isChecked)
                assertTrue(hasText(activity, activity.getString(R.string.wa_status_device, "LightDecoder")))
                assertTrue(hasText(activity, activity.getString(R.string.wa_status_account, "+15550100")))
                activity.findViewById<View>(R.id.disconnect).performClick()
            }
            waitUntil("the disconnect") { fake.calls.contains("disconnect") }
            scenario.waitFor("disconnected") {
                it.findViewById<TextView>(R.id.stateTitle).text.toString() == it.getString(R.string.wa_state_disconnected)
            }
            scenario.onActivity { assertFalse(it.findViewById<CompoundButton>(R.id.switchLive).isChecked) }
            assertFalse(WaPrefs.liveEnabled(env.context))
            assertTrue(manager.isPaired())
        }
    }

    @Test
    fun theSwitchesAreWhatsAppsOwn() {
        env.linkedManager()
        ActivityScenario.launch(WaStatusActivity::class.java).use { scenario ->
            scenario.waitFor("the controls") { it.findViewById<View>(R.id.controls).isShown }
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<CompoundButton>(R.id.switchLive).isChecked)
                assertTrue(activity.findViewById<CompoundButton>(R.id.switchAutoStart).isChecked)
                activity.findViewById<CompoundButton>(R.id.switchAutoStart).performClick()
                activity.findViewById<CompoundButton>(R.id.switchNotify).performClick()
            }
            assertFalse(WaPrefs.autoStart(env.context))
            assertFalse("on by default, so the tap turned it off", WaPrefs.notifyMessages(env.context))
            assertTrue("the Google Messages link's choices are untouched", com.lightdecoder.relay.RelayPrefs.autoStart(env.context))
            assertTrue(com.lightdecoder.relay.RelayPrefs.notifyMessages(env.context))
        }
    }

    @Test
    fun unlinkAsksFirstThenUnlinks() {
        val (manager, fake) = env.connectedManager()
        ActivityScenario.launch(WaStatusActivity::class.java).use { scenario ->
            scenario.waitFor("the controls") { it.findViewById<View>(R.id.unpair).isEnabled && it.findViewById<View>(R.id.unpair).isShown }
            scenario.onActivity { it.findViewById<View>(R.id.unpair).performClick() }
            settle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(dialog.isShowing)
            assertFalse("nothing happens until the user confirms", fake.calls.contains("logout"))
            assertNotNull(dialog.findViewById<View>(R.id.unpairDeleteArchive))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitUntil("the unlink") { !manager.isPaired() && manager.state.value.phase == RelayState.Phase.UNPAIRED }
            assertTrue(fake.calls.contains("logout"))
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun ActivityScenario<WaSetupActivity>.toNumberStep() {
        settle()
        onActivity { activity ->
            activity.findViewById<CompoundButton>(R.id.introAgree).performClick()
            activity.findViewById<View>(R.id.introContinue).performClick()
        }
        waitFor("the number step") { it.findViewById<View>(R.id.stepNumber).isShown }
    }

    private fun settle(rounds: Int = 5) {
        repeat(rounds) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            Thread.sleep(10)
        }
    }

    private fun waitUntil(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            if (condition()) return
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    private fun <A : Activity> ActivityScenario<A>.waitFor(what: String, condition: (A) -> Boolean) {
        waitUntil(what) {
            var met = false
            onActivity { met = condition(it) }
            met
        }
    }

    private fun hasText(activity: Activity, text: String): Boolean {
        fun walk(view: View): Boolean {
            if (view is TextView && view.text.toString() == text) return true
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) if (walk(view.getChildAt(i))) return true
            return false
        }
        return walk(activity.findViewById(android.R.id.content))
    }
}
