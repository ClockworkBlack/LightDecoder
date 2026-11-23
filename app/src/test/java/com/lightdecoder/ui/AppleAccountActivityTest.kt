package com.lightdecoder.ui

import android.os.Bundle
import android.os.Looper
import android.text.InputType
import android.util.SparseArray
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightdecoder.R
import com.lightdecoder.imessage.direct.AccountBridge
import com.lightdecoder.imessage.direct.AccountRig
import com.lightdecoder.imessage.direct.AppleAccount
import com.lightdecoder.imessage.direct.FlagState
import com.lightdecoder.ui.imessage.AppleAccountActivity
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The Apple Account's sign-in screen over a stand-in sign-in ([AccountRig]): kept out of screenshots,
 * "Continue" only once "I understand" is ticked, nothing typed kept in the saved state, the password
 * field as a password field, the buttons that go on safe from overlays, and a sign-in through it to
 * "Signed in" and the restart.
 */
@RunWith(AndroidJUnit4::class)
class AppleAccountActivityTest {

    private lateinit var rig: AccountRig

    @Before
    fun setUp() {
        rig = AccountRig()
        AppleAccount.testInstance = rig.account
    }

    @After
    fun tearDown() {
        AppleAccount.testInstance = null
    }

    private fun waitFor(scenario: ActivityScenario<AppleAccountActivity>, what: String, condition: (AppleAccountActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            var ok = false
            scenario.onActivity { ok = condition(it) }
            if (ok) return
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what (library ${rig.bridge.calls.toList()})")
            Thread.sleep(10)
        }
    }

    private fun shown(a: AppleAccountActivity, id: Int) = a.findViewById<View>(id).isShown
    private fun text(a: AppleAccountActivity, id: Int) = a.findViewById<TextView>(id).text.toString()

    /** Everything in a saved state, in words (a text field's saved text among them). */
    private fun dump(o: Any?): String = when (o) {
        null -> ""
        is Bundle -> o.keySet().joinToString(" ") { k -> k + "=" + dump(o.get(k)) }
        is SparseArray<*> -> (0 until o.size()).joinToString(" ") { dump(o.valueAt(it)) }
        is Array<*> -> o.joinToString(" ") { dump(it) }
        is Iterable<*> -> o.joinToString(" ") { dump(it) }
        else -> o.toString()
    }

    @Test
    fun theScreenIsSecureAndContinueWaitsForIUnderstand() {
        ActivityScenario.launch(AppleAccountActivity::class.java).use { scenario ->
            waitFor(scenario, "before you sign in") { shown(it, R.id.introContinue) }
            scenario.onActivity { a ->
                assertTrue("kept out of screenshots", a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertFalse("never over the lock screen", shadowOf(a).showWhenLocked)
                assertEquals("Sign in with your Apple Account", text(a, R.id.introTitle))
                val lines = a.findViewById<android.widget.LinearLayout>(R.id.bullets)
                val all = (0 until lines.childCount).joinToString("\n") { (lines.getChildAt(it) as TextView).text.toString() }
                assertTrue(all, all.contains("relay.example.com"))
                assertTrue(all, all.contains("iPhone14,2, 17.5, serial F2LXXXXXXXX1"))
                assertTrue(all, all.contains("Advanced Data Protection and Contact Key Verification must be off."))
                assertFalse(all, all.contains("OpenBubbles"))
                val go = a.findViewById<View>(R.id.introContinue)
                assertFalse("not before I understand", go.isEnabled)
                a.findViewById<CheckBox>(R.id.understand).performClick()
                assertTrue(go.isEnabled)
                a.findViewById<CheckBox>(R.id.understand).performClick()
                assertFalse(go.isEnabled)
                // The buttons that go on are not pressed through an overlay.
                for (id in listOf(
                    R.id.introContinue, R.id.signIn, R.id.continueAs, R.id.deviceVerify, R.id.noCode, R.id.textMe, R.id.sendAgain,
                    R.id.sendText, R.id.textVerify, R.id.resend, R.id.messageOk,
                )) {
                    assertTrue(a.resources.getResourceEntryName(id), a.findViewById<View>(id).filterTouchesWhenObscured)
                }
            }
            assertEquals("nothing of the session touched yet", listOf("readHardware", "getDeviceInfo"), rig.bridge.calls.toList())
        }
    }

    @Test
    fun nothingTypedIsKeptInTheSavedStateAndThePasswordFieldIsOne() {
        ActivityScenario.launch(AppleAccountActivity::class.java).use { scenario ->
            waitFor(scenario, "before you sign in") { shown(it, R.id.introContinue) }
            scenario.onActivity { a ->
                a.findViewById<CheckBox>(R.id.understand).performClick()
                a.findViewById<View>(R.id.introContinue).performClick()
            }
            waitFor(scenario, "the Apple Account and password") { shown(it, R.id.password) }
            scenario.onActivity { a ->
                val password = a.findViewById<EditText>(R.id.password)
                assertEquals(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, password.inputType)
                val appleId = a.findViewById<EditText>(R.id.appleId)
                assertEquals(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, appleId.inputType and InputType.TYPE_MASK_VARIATION)
                for (id in listOf(R.id.appleId, R.id.password, R.id.deviceCode, R.id.textCode)) {
                    val field = a.findViewById<EditText>(id)
                    assertFalse(a.resources.getResourceEntryName(id), field.isSaveEnabled)
                    assertTrue("not learned by the keyboard", field.imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
                }
                appleId.setText("user@example.com")
                password.setText(SECRET)
                a.findViewById<EditText>(R.id.deviceCode).setText("12345")
                a.findViewById<EditText>(R.id.textCode).setText("54321")
                // A field that does keep its text, to show the look through the saved state finds one.
                (a.findViewById<android.view.ViewGroup>(R.id.root)).addView(EditText(a).apply { id = View.generateViewId(); setText("KEPT-CONTROL") })
                val saved = Bundle()
                InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(a, saved)
                val words = dump(saved)
                assertTrue("the look finds a kept field", words.contains("KEPT-CONTROL"))
                for (typed in listOf("user@example.com", SECRET, "12345", "54321")) assertFalse(typed, words.contains(typed))
            }
        }
    }

    @Test
    fun aSignInThroughTheScreenEndsInSignedInAndTheRestart() {
        rig.bridge.onTryAuth = { _, _ -> AccountBridge.Step.NeedsDevice2Fa }
        rig.bridge.onVerify = { AccountBridge.Verified(AccountBridge.Step.LoggedIn, true) }
        ActivityScenario.launch(AppleAccountActivity::class.java).use { scenario ->
            waitFor(scenario, "before you sign in") { shown(it, R.id.introContinue) }
            scenario.onActivity { a ->
                a.findViewById<CheckBox>(R.id.understand).performClick()
                a.findViewById<View>(R.id.introContinue).performClick()
            }
            waitFor(scenario, "the password") { shown(it, R.id.password) }
            scenario.onActivity { a ->
                a.findViewById<EditText>(R.id.appleId).setText("user@example.com")
                a.findViewById<EditText>(R.id.password).setText(SECRET)
                a.findViewById<View>(R.id.signIn).performClick()
                assertEquals("out of the field once handed over", "", a.findViewById<EditText>(R.id.password).text.toString())
            }
            waitFor(scenario, "the code page") { shown(it, R.id.deviceCode) }
            scenario.onActivity { a ->
                assertEquals("Enter the verification code shown on your other Apple devices.", a.getString(R.string.apple_account_device_code_body))
                // Six digits typed: checked at once.
                a.findViewById<EditText>(R.id.deviceCode).setText("123456")
            }
            waitFor(scenario, "signed in") { shown(it, R.id.messageOk) && text(it, R.id.messageText) == it.getString(R.string.apple_account_done) }
            assertEquals(FlagState.SIGNED_IN, rig.files.readFlag().state)
            assertEquals(0, rig.hooks.restarts.get())
            // Left and come back to (Home, then the app again): "Signed in" waits for its "OK".
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            repeat(10) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
                Thread.sleep(10)
            }
            assertEquals("not under the page being read", 0, rig.hooks.tries.get())
            scenario.onActivity { a ->
                a.findViewById<View>(R.id.messageOk).performClick()
                assertTrue("the screen goes before the restart", a.isFinishing)
            }
            val deadline = System.currentTimeMillis() + 10_000
            while (rig.hooks.restarts.get() == 0) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
                if (System.currentTimeMillis() > deadline) fail("no restart")
                Thread.sleep(10)
            }
            assertEquals(1, rig.hooks.restarts.get())
            assertTrue(rig.hooks.told.last().signedIn)
            assertTrue(rig.filesHolding(SECRET).isEmpty())
        }
    }

    @Test
    fun okOnSignedInDuringAFaceTimeCallRestartsWhenTheCallScreenEndsTheCallOrTheScreenComesBack() {
        for (how in listOf("call screen", "screen back")) {
            rig = AccountRig()
            AppleAccount.testInstance = rig.account
            ActivityScenario.launch(AppleAccountActivity::class.java).use { scenario ->
                waitFor(scenario, "before you sign in") { shown(it, R.id.introContinue) }
                scenario.onActivity { a ->
                    a.findViewById<CheckBox>(R.id.understand).performClick()
                    a.findViewById<View>(R.id.introContinue).performClick()
                }
                waitFor(scenario, "the password") { shown(it, R.id.password) }
                scenario.onActivity { a ->
                    a.findViewById<EditText>(R.id.appleId).setText("user@example.com")
                    a.findViewById<EditText>(R.id.password).setText(SECRET)
                    a.findViewById<View>(R.id.signIn).performClick()
                }
                waitFor(scenario, "signed in") { shown(it, R.id.messageOk) && text(it, R.id.messageText) == it.getString(R.string.apple_account_done) }
                // A FaceTime call comes first: "OK" says iMessage restarts once it is over.
                rig.hooks.busy = true
                scenario.onActivity { a -> a.findViewById<View>(R.id.messageOk).performClick() }
                waitFor(scenario, "the restart said to wait") { text(it, R.id.messageText) == it.getString(R.string.apple_account_restart_pending) }
                // The call ends; with no screen of LightDecoder's doing anything, nothing is done by itself.
                rig.hooks.busy = false
                repeat(15) {
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
                    Thread.sleep(10)
                }
                assertEquals("$how: never by itself", 0, rig.hooks.tries.get())
                if (how == "call screen") {
                    // The call screen ends the call while in sight.
                    AppleAccount.callScreenEnded()
                } else {
                    // This screen comes back into sight.
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                }
                val deadline = System.currentTimeMillis() + 10_000
                while (rig.hooks.restarts.get() == 0) {
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
                    if (System.currentTimeMillis() > deadline) fail("$how: no restart")
                    Thread.sleep(10)
                }
                assertEquals(how, 1, rig.hooks.restarts.get())
                assertTrue(rig.hooks.told.last().signedIn)
            }
        }
    }

    @Test
    fun duringAFaceTimeCallItSaysToFinishTheCallFirst() {
        rig.hooks.busy = true
        ActivityScenario.launch(AppleAccountActivity::class.java).use { scenario ->
            waitFor(scenario, "the refusal") { shown(it, R.id.messageText) }
            scenario.onActivity { a -> assertEquals("Finish your FaceTime call first.", text(a, R.id.messageText)) }
            assertTrue(rig.bridge.calls.isEmpty())
        }
    }

    @Test
    fun theIMessageScreenHasNoAccountRowUntilThisPhoneIsRegistered() {
        ActivityScenario.launch(com.lightdecoder.ui.imessage.DirectSetupActivity::class.java).use { scenario ->
            repeat(10) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
                Thread.sleep(10)
            }
            scenario.onActivity { a -> assertEquals(View.GONE, a.findViewById<View>(R.id.appleAccountCard).visibility) }
        }
        assertTrue("nothing of the account asked", rig.bridge.calls.isEmpty())
    }

    companion object {
        private const val SECRET = "correct horse 9!"
    }
}
