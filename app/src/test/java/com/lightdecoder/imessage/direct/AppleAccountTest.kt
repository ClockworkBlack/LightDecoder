package com.lightdecoder.imessage.direct

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.imessage.direct.AppleAccountRules.Page
import com.lightdecoder.imessage.direct.AppleAccountRules.Problem
import com.lightdecoder.imessage.direct.AppleAccountRules.Refusal
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.rust_bridge.RustBridgeException

/**
 * The optional Apple Account's sign-in over a stand-in library ([FakeAccountBridge]): each way through
 * it (a code from the trusted devices, a text to one of two numbers, a code not confirmed in time),
 * what leaving it lets go of, the limits, the commit by a restart, the local sign-out, what a start
 * and a new registration do, and that without an account nothing of it runs at all.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppleAccountTest {

    private val rig = AccountRig()
    private val account get() = rig.account
    private val bridge get() = rig.bridge
    private val hooks get() = rig.hooks

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    private fun assertNoForbiddenCall() {
        for (name in FakeAccountBridge.NEVER) assertFalse(name, bridge.calls.any { it.startsWith(name) })
    }

    private fun toPassword(): Page = runBlocking {
        val intro = account.begin()
        assertTrue("$intro", intro is Page.Intro)
        account.understood()
    }

    // --- S1: nothing without an account --------------------------------------------------------------

    @Test
    fun withNoAccountNothingOfItRuns() = runBlocking {
        val order = ArrayList<String>()
        bridge.order = order
        // A whole start, then a registration (a new device), as the engine has it done.
        account.atStartLocked()
        account.sessionUpLocked()
        account.newDeviceLocked { order += "setIdentity" }
        account.sessionReplacedLocked()
        assertEquals(listOf("setIdentity"), order)
        assertTrue("the library was not called: ${bridge.calls}", bridge.calls.isEmpty())
        assertTrue("nothing to restart for", account.restartNow())
        account.screenShown()
        account.screenInSight()
        AppleAccount.callScreenEnded()
        assertFalse(account.restartPending)
        assertEquals(AccountState.SIGNED_OUT, account.state.value)
        assertEquals(AccountState.SIGNED_OUT, AccountState.fromJson(account.state.value.toJson()))
        assertFalse(account.state.value.toJson().getBoolean("signedIn"))
        assertNull(account.maskedName())
        assertNull(account.notice())
        assertTrue("nobody was told anything", hooks.told.isEmpty())
        assertFalse("no file of its own", rig.files.flagFile.exists())
        assertFalse(rig.files.logsMarker.exists())
        assertEquals(0, hooks.tries.get())
        assertTrue(bridge.calls.isEmpty())
    }

    // --- signing in ---------------------------------------------------------------------------------

    @Test
    fun aSignInWithACodeFromTheTrustedDevicesIsCommittedByARestart() = runBlocking {
        bridge.onTryAuth = { _, _ -> AccountBridge.Step.NeedsDevice2Fa }
        bridge.icloudUser = { false }
        bridge.onVerify = { code -> AccountBridge.Verified(AccountBridge.Step.LoggedIn, user = code == "123456") }
        val intro = account.begin() as Page.Intro
        assertEquals("iPhone14,2", intro.device?.name)
        assertEquals("F2LXXXXXXXX1", intro.device?.serial)
        assertEquals("https://relay.example.com", intro.relayHost)
        assertEquals("nothing touched the session before the user went on", listOf("readHardware", "getDeviceInfo"), bridge.calls.toList())
        assertEquals(Page.Credentials(), account.understood())
        assertEquals(Page.DeviceCode(sent = true), account.signIn(" User@Example.COM ", PASSWORD))
        assertTrue(account.holdsPassword)
        assertEquals(listOf("user@example.com" to PASSWORD), bridge.auths.toList())
        // The listeners are there before any code is asked for.
        assertEquals(
            listOf("readHardware", "getDeviceInfo", "reusableConnection", "subscribeConn", "makeIdms", "tryAuth", "tryIcloudLogin", "send2faToDevices"),
            bridge.calls.toList(),
        )
        // While it commits, it is marked as committing first: seen as the account is let go of.
        val flagWhenLetGo = ArrayList<FlagState>()
        bridge.refs.filter { it.name == "account" }.forEach { r -> r.onClose = { flagWhenLetGo += rig.files.readFlag().state } }
        assertEquals(Page.DeviceCode(sent = true, problem = Problem.CodeFormat), account.verifyDeviceCode("12 34"))
        assertEquals(Page.Done, account.verifyDeviceCode("123456"))
        assertEquals(listOf(FlagState.COMMITTING), flagWhenLetGo)
        assertEquals(FlagState.SIGNED_IN, rig.files.readFlag().state)
        assertFalse("the password is let go of once signed in", account.holdsPassword)
        assertTrue("everything of the sign-in let go of, its two-factor session too", bridge.refs.all { it.closed })
        assertTrue(account.state.value.signedIn)
        assertFalse(account.state.value.icloud)
        assertEquals(0, hooks.restarts.get())
        // "OK": the main process is told, then iMessage restarts.
        assertTrue(account.finishSignIn())
        assertEquals(1, hooks.restarts.get())
        assertTrue(hooks.told.last().signedIn)
        assertFalse(account.restartPending)
        // S2: the password is in no file of the app's.
        assertTrue(rig.filesHolding(PASSWORD).toString(), rig.filesHolding(PASSWORD).isEmpty())
        assertNoForbiddenCall()
        assertFalse("never registered the Apple ID, nor got its name", bridge.calls.contains("getUserName"))
    }

    @Test
    fun aSignInByTextToTheSecondOfTwoNumbers() = runBlocking {
        bridge.onTryAuth = { _, _ -> AccountBridge.Step.NeedsSms2Fa }
        bridge.icloudUser = { false }
        bridge.smsOptions = {
            AccountBridge.SmsOptions(listOf(AccountBridge.TrustedNumber(1, "+1 (•••) •••-••12"), AccountBridge.TrustedNumber(2, "+44 ••••• ••••34‮")), null)
        }
        toPassword()
        val numbers = account.signIn("user@example.com", PASSWORD) as Page.TextNumbers
        assertEquals(listOf("+1 (•••) •••-••12", "+44 ••••• ••••34"), numbers.numbers)
        val code = account.chooseNumber(1) as Page.TextCode
        assertEquals("+44 ••••• ••••34", code.number)
        assertTrue("resend waits a minute", code.resendAtMs > rig.clock[0])
        assertTrue(bridge.calls.contains("send2faSms:2"))
        assertNull("no trusted devices' session to hand over", bridge.textCircles.single())
        // Resend: not yet.
        val again = account.resendText() as Page.TextCode
        assertTrue(again.problem is Problem.CodeWait)
        assertEquals(1, bridge.calls.count { it.startsWith("send2faSms") })
        assertEquals(Page.Done, account.verifyTextCode("654321"))
        assertEquals(FlagState.SIGNED_IN, rig.files.readFlag().state)
        assertTrue(bridge.refs.all { it.closed })
        assertNoForbiddenCall()
    }

    @Test
    fun textMeInsteadEndsTheTrustedDevicesSession() = runBlocking {
        bridge.onTryAuth = { _, _ -> AccountBridge.Step.NeedsDevice2Fa }
        toPassword()
        assertEquals(Page.DeviceCode(sent = true), account.signIn("user@example.com", PASSWORD))
        // One number: texted at once.
        val code = account.textMeInstead() as Page.TextCode
        assertEquals("+1 (•••) •••-••12", code.number)
        val circle = bridge.textCircles.single()
        assertNotNull("the session was handed to the library, which ends it", circle)
        assertTrue("and let go of here", circle!!.closed)
    }

    @Test
    fun aCodeNotConfirmedInTimeCanBeTriedAgain() = runBlocking {
        bridge.onTryAuth = { _, _ -> AccountBridge.Step.NeedsDevice2Fa }
        var tries = 0
        bridge.onVerify = {
            if (++tries == 1) throw RustBridgeException.Other("Timed Out!")
            AccountBridge.Verified(AccountBridge.Step.LoggedIn, true)
        }
        toPassword()
        account.signIn("user@example.com", PASSWORD)
        assertEquals(Page.DeviceCode(sent = true, timedOut = true), account.verifyDeviceCode("123456"))
        assertTrue("not a failed attempt", rig.files.readFlag().limits.passwordFailures.isEmpty())
        assertEquals("no automatic retry", 1, tries)
        assertEquals(Page.Done, account.verifyDeviceCode("123456"))
        assertEquals(2, tries)
    }

    @Test
    fun leavingTheSignInLetsGoOfThePasswordAndOfWhatItBegan() = runBlocking {
        bridge.onTryAuth = { _, _ -> AccountBridge.Step.NeedsDevice2Fa }
        toPassword()
        account.signIn("user@example.com", PASSWORD)
        assertTrue(File(rig.statePath, "account.plist").isFile)
        account.cancel()
        assertFalse(account.holdsPassword)
        account.settled()
        assertTrue(bridge.refs.all { it.closed })
        assertFalse("what the library saved for it is wiped", File(rig.statePath, "account.plist").exists())
        assertEquals(AccountState.SIGNED_OUT, account.state.value)
        // The screen, late: nothing goes on, the password reaches nothing.
        assertEquals(Page.Refused(Refusal.NOT_STARTED), account.verifyDeviceCode("123456"))
        assertEquals(1, bridge.auths.size)
        assertFalse(bridge.calls.contains("verify2fa"))
        assertTrue(rig.filesHolding(PASSWORD).isEmpty())
    }

    @Test
    fun thePasswordIsLetGoOfAfterTenMinutes() = runBlocking {
        bridge.onTryAuth = { _, _ -> AccountBridge.Step.NeedsDevice2Fa }
        // The code checked: Apple wants the password again (as after a device approval).
        bridge.onVerify = { AccountBridge.Verified(AccountBridge.Step.NeedsLogin, false) }
        toPassword()
        account.signIn("user@example.com", PASSWORD)
        rig.advance(AppleAccountRules.CREDENTIALS_MS + 1_000)
        val page = account.verifyDeviceCode("123456") as Page.Credentials
        assertEquals(Problem.EnterPassword, page.problem)
        assertEquals("no later call got the password", listOf("user@example.com" to PASSWORD), bridge.auths.toList())
        assertFalse(account.holdsPassword)
    }

    @Test
    fun withinTenMinutesNeedsLoginIsTriedOnceMoreThenCountsAsAFailure() = runBlocking {
        bridge.onTryAuth = { _, _ -> AccountBridge.Step.NeedsLogin }
        toPassword()
        val page = account.signIn("user@example.com", "wrong") as Page.Credentials
        assertEquals(Problem.WrongPassword, page.problem)
        assertTrue(page.iforgot)
        assertEquals("once more, then not", 2, bridge.auths.size)
        assertEquals(1, rig.files.readFlag().limits.passwordFailures.size)
        assertFalse(account.holdsPassword)
        assertFalse("nothing of a refused sign-in is kept", File(rig.statePath, "account.plist").exists())
        // Two more: then the next waits, and the library is not asked.
        account.signIn("user@example.com", "wrong")
        account.signIn("user@example.com", "wrong")
        assertEquals(6, bridge.auths.size)
        val limited = account.signIn("user@example.com", "right") as Page.Credentials
        assertTrue(limited.problem is Problem.TooManyAttempts)
        assertEquals(6, bridge.auths.size)
        // A restart of the process does not start them again.
        account.cancel()
        account.settled()
        rig.restart()
        toPassword()
        assertTrue((account.signIn("user@example.com", "right") as Page.Credentials).problem is Problem.TooManyAttempts)
        assertEquals(6, bridge.auths.size)
        rig.advance(AppleAccountRules.PASSWORD_WINDOW_MS)
        bridge.onTryAuth = { _, _ -> AccountBridge.Step.LoggedIn }
        assertEquals(Page.Done, account.signIn("user@example.com", "right"))
    }

    @Test
    fun codesToTheDevicesWaitAMinuteBetween() = runBlocking {
        bridge.onTryAuth = { _, _ -> AccountBridge.Step.NeedsDevice2Fa }
        toPassword()
        account.signIn("user@example.com", PASSWORD)
        val wait = account.resendToDevices() as Page.DeviceCode
        assertTrue(wait.problem is Problem.CodeWait)
        assertEquals(1, bridge.calls.count { it == "send2faToDevices" })
        rig.advance(AppleAccountRules.CODE_COOLDOWN_MS)
        assertEquals(Page.DeviceCode(sent = true), account.resendToDevices())
        assertEquals(2, bridge.calls.count { it == "send2faToDevices" })
    }

    @Test
    fun aSignInNeverGoesOnWithoutAppleListeners() = runBlocking {
        bridge.idmsFailures = 1
        bridge.onTryAuth = { _, _ -> AccountBridge.Step.NeedsDevice2Fa }
        toPassword()
        val page = account.signIn("user@example.com", PASSWORD) as Page.Credentials
        assertTrue(page.problem is Problem.Apple)
        assertTrue("nothing was asked of Apple without them", bridge.auths.isEmpty())
        assertTrue("what was made for it let go of", bridge.refs.filter { it.name != "config" }.all { it.closed })
        // The next try makes all of them again.
        assertEquals(Page.DeviceCode(sent = true), account.signIn("user@example.com", PASSWORD))
        assertEquals(2, bridge.calls.count { it == "reusableConnection" })
        assertEquals(1, bridge.auths.size)
    }

    @Test
    fun appleErrorsAreShownWithoutWhatIsSecretAndCounted() = runBlocking {
        bridge.onTryAuth = { _, _ -> throw RustBridgeException.Other("Bad auth for user@example.com: token=abcdef0123456789abcdef X-Apple-I-MD: AAAAABBBBBCCCCC123==") }
        toPassword()
        val page = account.signIn("user@example.com", PASSWORD) as Page.Credentials
        val text = (page.problem as Problem.Apple).text
        assertTrue(text, text.startsWith("Bad auth for"))
        for (secret in listOf("user@example.com", "abcdef0123456789abcdef", "AAAAABBBBBCCCCC123")) assertFalse(text, text.contains(secret))
        assertTrue(page.iforgot)
        assertEquals(1, rig.files.readFlag().limits.passwordFailures.size)
    }

    @Test
    fun newTermsAndApplesOwnStepEndTheSignIn() = runBlocking {
        bridge.onTryAuth = { _, _ -> throw RustBridgeException.Other("login failed: MOBILEME_TERMS_OF_SERVICE_UPDATE") }
        toPassword()
        assertEquals(Page.Terms, account.signIn("user@example.com", PASSWORD))
        assertNull("the sign-in is over", account.page)
        assertFalse(account.holdsPassword)
        assertTrue(bridge.refs.all { it.closed })
        assertFalse(File(rig.statePath, "account.plist").exists())

        bridge.onTryAuth = { _, _ -> AccountBridge.Step.NeedsExtraStep("Sign in on an Apple device first.") }
        toPassword()
        assertEquals(Page.AppleMessage("Sign in on an Apple device first."), account.signIn("user@example.com", PASSWORD))
        assertNull(account.page)
        assertEquals(FlagState.NONE, rig.files.readFlag().state)
    }

    @Test
    fun aFaceTimeCallRefusesTheSignInItsCommitAndTheSignOut() = runBlocking {
        hooks.busy = true
        assertEquals(Page.Refused(Refusal.BUSY), account.begin())
        assertTrue(bridge.calls.isEmpty())
        rig.signedIn()
        hooks.busy = true
        assertEquals(AppleAccount.SignOut.Refused(Refusal.BUSY), account.signOut())
        assertTrue("nothing to restart for", account.finishSignIn())
        assertTrue(account.restartNow())
        assertEquals(0, hooks.tries.get())
        assertTrue(bridge.calls.isEmpty())
        assertTrue(File(rig.statePath, "account.plist").exists())
    }

    @Test
    fun aRestartAFaceTimeCallKeptFromIsLeftPending() = runBlocking {
        toPassword()
        assertEquals(Page.Done, account.signIn("user@example.com", PASSWORD))
        hooks.restartAnswer = false
        assertFalse(account.finishSignIn())
        assertTrue(account.restartPending)
        hooks.restartAnswer = true
        assertTrue(account.restartNow())
        assertFalse(account.restartPending)
    }

    @Test
    fun aSignInIsOnlyOfferedWhenNotSignedIn() = runBlocking {
        rig.signedIn()
        assertEquals(Page.Refused(Refusal.SIGNED_IN), account.begin())
        assertEquals(listOf<String>(), bridge.calls.toList())
        hooks.registered = false
        rig.files.writeFlag(AccountFlag())
        assertEquals(Page.Refused(Refusal.NOT_REGISTERED), account.begin())
    }

    @Test
    fun aSignInAgainTriesWhatWasSavedFirstWithoutThePassword() = runBlocking {
        rig.signedIn(FlagState.ATTENTION, user = "user@example.com")
        val page = account.begin() as Page.Credentials
        assertEquals("no 'Before you sign in' again", "u•••@example.com", page.continueAs)
        assertEquals(Page.Done, account.continueAsSaved())
        assertEquals(listOf<Pair<String?, String?>>(null to null), bridge.auths.toList())
        assertEquals(FlagState.SIGNED_IN, rig.files.readFlag().state)
    }

    @Test
    fun aNewPasswordFromNeedsAttentionThatIsLeftSignsOut() = runBlocking {
        rig.signedIn(FlagState.ATTENTION)
        bridge.onTryAuth = { _, _ -> AccountBridge.Step.NeedsDevice2Fa }
        assertTrue(account.begin() is Page.Credentials)
        // A password typed instead of "Continue as": "Before you sign in" first (S6), the password not used.
        assertTrue(account.signIn("user@example.com", PASSWORD) is Page.Intro)
        assertTrue(bridge.auths.isEmpty())
        assertFalse(account.holdsPassword)
        assertTrue(account.understood() is Page.Credentials)
        account.signIn("user@example.com", PASSWORD)
        account.cancel()
        account.settled()
        assertFalse(File(rig.statePath, "account.plist").exists())
        assertEquals(FlagState.NONE, rig.files.readFlag().state)
        assertEquals(AccountNotice.SIGNED_OUT, account.notice())
        assertFalse(account.state.value.signedIn)
        assertEquals("the session was worked on: iMessage restarts", 1, hooks.restarts.get())
    }

    @Test
    fun afterANewRegistrationTheSignInStartsWithBeforeYouSignIn() = runBlocking {
        // S10 left "needs you to sign in again" with nothing saved: the device may be another relay iPhone.
        rig.signedIn()
        account.newDeviceLocked { }
        assertEquals(FlagState.ATTENTION, rig.files.readFlag().state)
        assertFalse(File(rig.statePath, "account.plist").exists())
        bridge.calls.clear()
        val intro = account.begin() as Page.Intro
        assertEquals("F2LXXXXXXXX1", intro.device?.serial)
        // No path past it without "I understand": a password handed over anyway goes nowhere.
        assertTrue(account.signIn("user@example.com", PASSWORD) is Page.Intro)
        assertEquals(listOf("readHardware", "getDeviceInfo", "getDeviceInfo"), bridge.calls.toList())
        assertFalse(account.holdsPassword)
        val credentials = account.understood() as Page.Credentials
        assertNull("nothing saved to continue as", credentials.continueAs)
        assertEquals(Page.Done, account.signIn("user@example.com", PASSWORD))
    }

    // --- a sign-in left part-way ends in a restart (S12, §4.3 step 2) ------------------------------------

    @Test
    fun aSignInLeftAtBeforeYouSignInTouchesNothingAndRestartsNothing() = runBlocking {
        account.begin()
        account.cancel()
        account.settled()
        assertEquals(0, hooks.restarts.get())
        assertFalse(account.restartPending)
        assertEquals(listOf("readHardware", "getDeviceInfo"), bridge.calls.toList())
    }

    @Test
    fun aSignInLeftAfterItTookTheSessionsConnectionRestartsIMessage() = runBlocking {
        bridge.onTryAuth = { _, _ -> AccountBridge.Step.NeedsDevice2Fa }
        toPassword()
        account.signIn("user@example.com", PASSWORD)
        assertEquals(0, hooks.restarts.get())
        account.cancel()
        account.settled()
        assertEquals(1, hooks.restarts.get())
        assertFalse(account.restartPending)
        assertTrue("the main process told first", hooks.told.isNotEmpty())
    }

    @Test
    fun aWrongPasswordThenLeavingRestartsIMessage() = runBlocking {
        bridge.onTryAuth = { _, _ -> AccountBridge.Step.NeedsLogin }
        toPassword()
        assertEquals(Problem.WrongPassword, (account.signIn("user@example.com", "wrong") as Page.Credentials).problem)
        // Still on the page: no restart under the user's hands.
        assertEquals(0, hooks.restarts.get())
        account.cancel()
        account.settled()
        assertEquals(1, hooks.restarts.get())
    }

    @Test
    fun newTermsThenOkRestartsIMessageOnlyOnceTheScreenIsLeft() = runBlocking {
        bridge.onTryAuth = { _, _ -> throw RustBridgeException.Other("login failed: MOBILEME_TERMS_OF_SERVICE_UPDATE") }
        toPassword()
        assertEquals(Page.Terms, account.signIn("user@example.com", PASSWORD))
        assertTrue(account.restartPending)
        assertEquals("the page is read first", 0, hooks.restarts.get())
        // "OK" leaves the screen.
        account.cancel()
        account.settled()
        assertEquals(1, hooks.restarts.get())
        assertFalse(account.restartPending)
    }

    // --- the restart: only at a moment one of LightDecoder's screens is in sight, never by itself ---------

    /** An account whose own work runs on this test's virtual time. */
    private fun kotlinx.coroutines.test.TestScope.virtual(): AccountRig =
        AccountRig(work = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))

    /** A long way on in virtual time, and whatever was due by then run. */
    private fun kotlinx.coroutines.test.TestScope.wait() {
        advanceTimeBy(30 * DAY_MS)
        runCurrent()
    }

    private fun refusals(): Int = org.robolectric.shadows.ShadowLog.getLogsForTag("AppleAccount").count { it.msg.contains("did not restart now") }

    @Test
    fun aRefusedStartTellsNobodyHoldsNothingAndIsLoggedOncePerPendingRestart() = runTest {
        val r = virtual()
        r.signedIn()
        assertEquals(AppleAccount.SignOut.Done, r.account.signOut())
        r.hooks.told.clear()
        val logged = refusals()
        // Android refuses iMessage's service (no screen of LightDecoder's in sight): tried three times, at three screens.
        r.hooks.refused = true
        repeat(3) { assertFalse(r.account.restartNow()) }
        assertEquals(3, r.hooks.tries.get())
        assertEquals(0, r.hooks.restarts.get())
        assertTrue("nobody told", r.hooks.told.isEmpty())
        assertTrue("still pending", r.account.restartPending)
        assertFalse("not a call's", r.account.restartHeldByCall)
        assertFalse("the engine's lock not held", r.lock.isLocked)
        assertEquals("logged once, not per try", logged + 1, refusals())
        // Nothing is tried again by itself.
        wait()
        assertEquals(3, r.hooks.tries.get())
        // Allowed at the next screen: made, the main process told just before.
        r.hooks.refused = false
        r.account.screenShown()
        runCurrent()
        assertEquals(1, r.hooks.restarts.get())
        assertEquals(listOf(AccountState.SIGNED_OUT), r.hooks.told.toList())
        assertFalse(r.account.restartPending)
        // A later pending restart that is refused is logged again, once.
        r.bridge.onTryAuth = { _, _ -> AccountBridge.Step.NeedsDevice2Fa }
        r.account.begin()
        r.account.understood()
        r.account.signIn("user@example.com", PASSWORD)
        r.hooks.refused = true
        r.account.cancel()
        r.account.settled()
        assertTrue(r.account.restartPending)
        assertFalse(r.account.restartNow())
        assertEquals(logged + 2, refusals())
    }

    @Test
    fun aPendingRestartIsNeverMadeByItselfHoweverLongItWaits() = runTest {
        val r = virtual()
        r.signedIn()
        // Signed out, and its message not read (left with Home).
        assertEquals(AppleAccount.SignOut.Done, r.account.signOut())
        assertTrue(r.account.restartPending)
        wait()
        assertEquals("no try by itself", 0, r.hooks.tries.get())
        // Tried at a screen and kept back (a request being answered), refused, or held by a call that is
        // then over with no call screen to end it: still nothing by itself.
        r.hooks.restartAnswer = false
        assertFalse(r.account.restartNow())
        r.hooks.restartAnswer = true
        r.hooks.refused = true
        assertFalse(r.account.restartNow())
        r.hooks.refused = false
        r.hooks.busy = true
        assertFalse(r.account.restartNow())
        r.hooks.busy = false
        val tried = r.hooks.tries.get()
        wait()
        assertEquals(tried, r.hooks.tries.get())
        assertEquals(0, r.hooks.restarts.get())
        assertTrue(r.account.restartPending)
        // The next moment a screen is in sight: made, once.
        r.account.screenShown()
        runCurrent()
        assertEquals(1, r.hooks.restarts.get())
        assertFalse(r.account.restartPending)
        r.account.screenShown()
        r.account.screenInSight()
        wait()
        assertEquals("once", tried + 1, r.hooks.tries.get())
    }

    @Test
    fun aSignInEndedOnSignedInApplesWordsOrTheTermsWaitsForItsOkThenRestartsOnce() = runTest {
        for (end in listOf("signed in", "words", "terms")) {
            val r = virtual()
            r.bridge.onTryAuth = { _, _ ->
                when (end) {
                    "words" -> AccountBridge.Step.NeedsExtraStep("Sign in on an Apple device first.")
                    "terms" -> throw RustBridgeException.Other("MOBILEME_TERMS_OF_SERVICE_UPDATE")
                    else -> AccountBridge.Step.LoggedIn
                }
            }
            r.account.begin()
            r.account.understood()
            val page = r.account.signIn("user@example.com", PASSWORD)
            assertTrue("$end: $page", page == Page.Done || page is Page.AppleMessage || page == Page.Terms)
            assertTrue(end, r.account.restartPending)
            // Home instead of "OK", and the screen kept: nothing by itself.
            wait()
            assertEquals(end, 0, r.hooks.tries.get())
            // "OK".
            if (end == "signed in") {
                assertTrue(r.account.finishSignIn())
            } else {
                r.account.cancel()
                r.account.settled()
            }
            assertEquals(end, 1, r.hooks.restarts.get())
            assertFalse(end, r.account.restartPending)
        }
    }

    @Test
    fun aSignInScreenClosedOutOfSightLeavesItsRestartForTheNextScreen() = runTest {
        // Swiped away on "Signed in", or part-way once the session was worked on.
        for (done in listOf(true, false)) {
            val r = virtual()
            r.bridge.onTryAuth = { _, _ -> if (done) AccountBridge.Step.LoggedIn else AccountBridge.Step.NeedsDevice2Fa }
            r.account.begin()
            r.account.understood()
            r.account.signIn("user@example.com", PASSWORD)
            if (done) {
                r.account.finishSignInLater()
            } else {
                r.account.cancel(restart = false)
                r.account.settled()
            }
            assertNull("$done: nothing of the sign-in left current", r.account.page)
            assertFalse(r.account.holdsPassword)
            assertTrue(r.account.restartPending)
            wait()
            assertEquals("$done: never by itself", 0, r.hooks.tries.get())
            r.account.screenShown()
            runCurrent()
            assertEquals(1, r.hooks.restarts.get())
        }
    }

    @Test
    fun aScreenComingUpNeverRestartsUnderASignInsStep() = runTest {
        val r = virtual()
        r.signedIn()
        assertEquals(AppleAccount.SignOut.Done, r.account.signOut())
        // Signed in again straight away: "Before you sign in" shows.
        assertTrue(r.account.begin() is Page.Intro)
        r.account.screenShown()
        r.account.screenInSight()
        wait()
        assertEquals("not under the user's hands", 0, r.hooks.tries.get())
        // Leaving it ("Not Now"): made then.
        r.account.cancel()
        r.account.settled()
        assertEquals(1, r.hooks.restarts.get())
    }

    @Test
    fun aRestartACallKeptBackIsMadeWhenTheCallScreenEndsTheCall() = runTest {
        val r = virtual()
        r.signedIn()
        assertEquals(AppleAccount.SignOut.Done, r.account.signOut())
        r.hooks.busy = true
        assertFalse(r.account.restartNow())
        assertTrue(r.account.restartHeldByCall)
        assertEquals("not even started during the call", 0, r.hooks.tries.get())
        // The call screen goes while the call is still on: nothing.
        AppleAccount.callScreenEnded()
        runCurrent()
        assertEquals(0, r.hooks.tries.get())
        // The call is over (ended elsewhere): nothing by itself.
        r.hooks.busy = false
        wait()
        assertEquals(0, r.hooks.tries.get())
        // The call screen ends the call, in sight: made, once.
        AppleAccount.callScreenEnded()
        runCurrent()
        assertEquals(1, r.hooks.restarts.get())
        assertFalse(r.account.restartPending)
        assertFalse(r.account.restartHeldByCall)
        AppleAccount.callScreenEnded()
        wait()
        assertEquals(1, r.hooks.tries.get())
    }

    @Test
    fun theCallScreenDoesNothingForARestartNoCallKeptBack() = runTest {
        val r = virtual()
        r.signedIn()
        assertEquals(AppleAccount.SignOut.Done, r.account.signOut())
        r.hooks.refused = true
        assertFalse(r.account.restartNow())
        r.hooks.refused = false
        AppleAccount.callScreenEnded()
        wait()
        assertEquals(1, r.hooks.tries.get())
        assertTrue(r.account.restartPending)
    }

    @Test
    fun okOnSignedInDuringACallWaitsForTheCallScreenToEndIt() = runTest {
        val r = virtual()
        r.account.begin()
        r.account.understood()
        assertEquals(Page.Done, r.account.signIn("user@example.com", PASSWORD))
        r.hooks.busy = true
        assertFalse(r.account.canRestart)
        r.account.finishSignInAfterCall()
        assertNull("nothing of the sign-in left current", r.account.page)
        assertTrue(r.account.restartHeldByCall)
        r.hooks.busy = false
        wait()
        assertEquals(0, r.hooks.tries.get())
        AppleAccount.callScreenEnded()
        runCurrent()
        assertEquals(1, r.hooks.restarts.get())
        assertTrue(r.hooks.told.last().signedIn)
    }

    @Test
    fun aMainProcessScreenInSightRestartsOnceAndWithNothingPendingDoesNothing() = runTest {
        val r = virtual()
        // No account at all (S1): nothing.
        r.account.screenInSight()
        wait()
        assertEquals(0, r.hooks.tries.get())
        assertTrue(r.bridge.calls.isEmpty())
        assertTrue(r.hooks.told.isEmpty())
        r.signedIn()
        r.account.screenInSight()
        wait()
        assertEquals("signed in, nothing pending: nothing", 0, r.hooks.tries.get())
        assertEquals(AppleAccount.SignOut.Done, r.account.signOut())
        r.account.screenInSight()
        runCurrent()
        assertEquals(1, r.hooks.restarts.get())
        assertFalse(r.account.restartPending)
    }

    @Test
    fun theMainProcessIsToldWhenARestartBecomesPendingAndRightBeforeItIsMade() = runTest {
        // Signed in, and Home instead of "OK": the main process is told, so its screens say when they are in sight.
        val r = virtual()
        r.account.begin()
        r.account.understood()
        assertEquals(Page.Done, r.account.signIn("user@example.com", PASSWORD))
        runCurrent()
        assertTrue(r.account.restartPending)
        assertEquals(listOf(AccountState(signedIn = true)), r.hooks.told.toList())
        assertEquals(listOf(false), r.hooks.toldRestarting.toList())
        r.account.finishSignInLater()
        wait()
        assertEquals("told, nothing tried", 0, r.hooks.tries.get())
        // A chat comes up: made, the main process told once more just before (it checks that iMessage came back).
        r.account.screenInSight()
        runCurrent()
        assertEquals(1, r.hooks.restarts.get())
        assertEquals(listOf(false, true), r.hooks.toldRestarting.toList())

        // A sign-in left after it worked on the session (Home, the screen swiped away): told too.
        val s = virtual()
        s.bridge.onTryAuth = { _, _ -> AccountBridge.Step.NeedsDevice2Fa }
        s.account.begin()
        s.account.understood()
        s.account.signIn("user@example.com", PASSWORD)
        s.account.cancel(restart = false)
        s.account.settled()
        runCurrent()
        assertTrue(s.account.restartPending)
        assertEquals(listOf(AccountState.SIGNED_OUT), s.hooks.told.toList())
        assertEquals(0, s.hooks.tries.get())
    }

    @Test
    fun aScreenThatComesUpWhileARestartIsTriedGetsOneTryOfItsOwnAfterIt() = runTest {
        val r = virtual()
        r.signedIn()
        assertEquals(AppleAccount.SignOut.Done, r.account.signOut())
        // A chat comes up: its try waits (a request of the main process being answered), then is kept back.
        val free = kotlinx.coroutines.CompletableDeferred<Unit>()
        r.hooks.beforeRestart = {
            if (r.hooks.tries.get() == 1) {
                free.await()
                r.hooks.restartAnswer = false
            } else {
                r.hooks.restartAnswer = true
            }
        }
        r.account.screenInSight()
        runCurrent()
        assertEquals(1, r.hooks.tries.get())
        // Meanwhile the iMessage screen comes up (and again): noted, not tried under the first.
        r.account.screenShown()
        r.account.screenShown()
        runCurrent()
        assertEquals(1, r.hooks.tries.get())
        free.complete(Unit)
        runCurrent()
        assertEquals("one more try, for the moments that came meanwhile", 2, r.hooks.tries.get())
        assertEquals(1, r.hooks.restarts.get())
        assertFalse(r.account.restartPending)
        wait()
        assertEquals(2, r.hooks.tries.get())

        // The same for "OK" on the "Signed out" message while a chat's try runs, and for a screen
        // coming up while "OK"'s own try runs.
        val s = virtual()
        s.signedIn()
        assertEquals(AppleAccount.SignOut.Done, s.account.signOut())
        val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
        s.hooks.restartAnswer = false
        s.hooks.beforeRestart = { if (s.hooks.tries.get() == 1) hold.await() }
        val ok = async { s.account.restartNow() }
        runCurrent()
        s.account.screenInSight()
        runCurrent()
        assertEquals(1, s.hooks.tries.get())
        hold.complete(Unit)
        assertFalse(ok.await())
        runCurrent()
        assertEquals(2, s.hooks.tries.get())
        assertTrue(s.account.restartPending)
        // Kept back both times: nothing more by itself.
        wait()
        assertEquals(2, s.hooks.tries.get())
    }

    @Test
    fun theSignedOutMessageHoldsEveryOtherMomentBackUntilItIsReadOrItsScreenGoes() = runTest {
        val r = virtual()
        r.signedIn()
        assertEquals(AppleAccount.SignOut.Done, r.account.signOut())
        val screen = Any()
        r.account.reading(screen, true)
        // The iMessage screen comes back with it unread, a chat comes up: nothing, it stays to be read.
        r.account.screenShown()
        r.account.screenInSight()
        wait()
        assertEquals(0, r.hooks.tries.get())
        assertTrue(r.account.restartPending)
        // "OK": made.
        r.account.reading(screen, false)
        assertTrue(r.account.restartNow())
        assertEquals(1, r.hooks.restarts.get())

        // Its screen gone with it unread: the next moment makes it.
        val s = virtual()
        s.signedIn()
        assertEquals(AppleAccount.SignOut.Done, s.account.signOut())
        s.account.reading(screen, true)
        s.account.screenInSight()
        wait()
        assertEquals(0, s.hooks.tries.get())
        s.account.reading(screen, false)
        s.account.screenInSight()
        runCurrent()
        assertEquals(1, s.hooks.restarts.get())

        // Shown while a try was already under way: that try finds it no longer wanted.
        val t = virtual()
        t.signedIn()
        assertEquals(AppleAccount.SignOut.Done, t.account.signOut())
        t.hooks.beforeRestart = { t.account.reading(screen, true) }
        t.account.screenInSight()
        runCurrent()
        assertEquals(1, t.hooks.needless.get())
        assertEquals(0, t.hooks.restarts.get())
        assertTrue(t.account.restartPending)
    }

    @Test
    fun aSignInLeftWhileItWaitedToBeginBeginsNothingAndHoldsNoRestartBack() = runBlocking {
        rig.signedIn()
        assertEquals(AppleAccount.SignOut.Done, account.signOut())
        // The engine is held (a restart's try waits for a request being answered) as the sign-in screen begins.
        rig.lock.lock()
        val begin = CoroutineScope(Dispatchers.Default).async { account.begin() }
        Thread.sleep(100)
        // Back, during the spinner (its own try is refused: Android, say).
        hooks.refused = true
        account.cancel()
        account.settled()
        rig.lock.unlock()
        assertEquals(Page.Refused(Refusal.NOT_STARTED), begin.await())
        assertNull("no sign-in left behind with no screen to end it", account.page)
        assertFalse(bridge.calls.contains("readHardware"))
        assertTrue(account.restartPending)
        // The next moment a screen is in sight makes the restart.
        hooks.refused = false
        account.screenShown()
        waitFor("the restart") { hooks.restarts.get() == 1 }
        // And a sign-in begun after it begins as ever.
        assertTrue(account.begin() is Page.Intro)
    }

    @Test
    fun whatTheLibraryLogsBeforeTheRestartIsDeletedAtTheNextStartHoweverTheProcessEnded() = runBlocking {
        // Never signed in: the library's own logs are left as they are, and no file is made (S1).
        File(rig.statePath, "logs").apply { mkdirs() }.resolve("rust.log").writeText("as before")
        account.atStartLocked()
        assertTrue(File(rig.statePath, "logs").exists())
        assertFalse(rig.files.logsMarker.exists())
        assertTrue(bridge.calls.isEmpty())
        File(rig.statePath, "logs").deleteRecursively()

        // Signed out: the session still holds the account until the restart, and may log on.
        rig.signedIn()
        assertEquals(AppleAccount.SignOut.Done, account.signOut())
        assertTrue(rig.files.logsMarker.exists())
        File(rig.statePath, "logs.1").writeText("Got spd")
        // The process ends some other way than by its restart (a crash, Android stopping it).
        rig.restart()
        bridge.calls.clear()
        account.atStartLocked()
        assertFalse(File(rig.statePath, "logs.1").exists())
        assertFalse("its own file gone once done", rig.files.logsMarker.exists())
        assertTrue("only files deleted", bridge.calls.isEmpty())
        // The start after that is as for a phone never signed in.
        File(rig.statePath, "logs.2").writeText("later")
        rig.restart()
        account.atStartLocked()
        assertTrue(File(rig.statePath, "logs.2").exists())

        // A sign-in left after it worked on the session: the same.
        bridge.onTryAuth = { _, _ -> AccountBridge.Step.NeedsDevice2Fa }
        toPassword()
        account.signIn("user@example.com", PASSWORD)
        account.cancel(restart = false)
        account.settled()
        assertTrue(rig.files.logsMarker.exists())
        rig.restart()
        account.atStartLocked()
        assertFalse(File(rig.statePath, "logs.2").exists())
        assertFalse(rig.files.logsMarker.exists())
    }

    @Test
    fun aRestartAskedForByAScreenThatGoesIsNotLost() {
        rig.signedIn()
        runBlocking { assertEquals(AppleAccount.SignOut.Done, rig.account.signOut()) }
        // The restart waits (a registration under way holds the engine), and the screen that asked goes meanwhile.
        val free = kotlinx.coroutines.CompletableDeferred<Unit>()
        rig.hooks.beforeRestart = { free.await() }
        val screen = CoroutineScope(Dispatchers.Default).launch { rig.account.restartNow() }
        Thread.sleep(100)
        runBlocking { screen.cancelAndJoin() }
        free.complete(Unit)
        waitFor("the restart all the same") { rig.hooks.restarts.get() == 1 }
        waitFor("no longer pending") { !rig.account.restartPending }
    }

    @Test
    fun aSignOutWhoseScreenGoesIsFinishedAtOnceInTheAccountsOwnScope() {
        rig.signedIn()
        // A start or a registration holds the engine: the sign-out waits for it, and the screen goes meanwhile.
        runBlocking { rig.lock.lock() }
        val screen = CoroutineScope(Dispatchers.Default).launch { rig.account.signOut() }
        Thread.sleep(100)
        runBlocking { screen.cancelAndJoin() }
        rig.lock.unlock()
        waitFor("signed out all the same") { !File(rig.statePath, "account.plist").exists() && !rig.files.flagFile.exists() }
        waitFor("pending") { rig.account.restartPending }
        Thread.sleep(200)
        assertEquals("the restart waits for a screen", 0, rig.hooks.tries.get())
        rig.account.screenShown()
        waitFor("the restart") { rig.hooks.restarts.get() == 1 }
    }

    @Test
    fun theScreensAndTheMainProcessAreWhereARestartIsAskedFor() {
        fun src(path: String) = File("src/main/java/com/lightdecoder/$path").readText()
        fun body(text: String, start: String) = text.substringAfter(start).substringBefore("\n    }")
        // (b) the sign-in screen and the iMessage screen, when they come up.
        assertTrue(body(src("ui/imessage/AppleAccountActivity.kt"), "override fun onResume() {").contains("account.screenShown()"))
        val setupResume = body(src("ui/imessage/DirectSetupActivity.kt"), "override fun onResume() {")
        assertTrue(setupResume.contains("account.screenShown()"))
        // Not left out while iMessage starts or registers: the restart waits for those itself.
        assertFalse(setupResume, setupResume.contains("Status."))
        // The "Signed out" message holds every other moment back until it is read, or its screen goes.
        val setup = src("ui/imessage/DirectSetupActivity.kt")
        val signOut = body(setup, "private fun signOut() {")
        assertTrue(signOut, signOut.indexOf("account.reading(this@DirectSetupActivity, true)") in 0 until signOut.indexOf(".show()"))
        assertTrue(signOut, signOut.contains("account.reading(this@DirectSetupActivity, false)\n                            restartForAccount()"))
        assertTrue(body(setup, "override fun onDestroy() {").contains("account.reading(this, false)"))
        // Closed out of sight: never a restart then.
        val destroy = body(src("ui/imessage/AppleAccountActivity.kt"), "override fun onDestroy() {")
        assertTrue(destroy, destroy.contains("account.finishSignInLater()") && destroy.contains("account.cancel(restart = false)"))
        // (c) the call screen ending a call, while in sight.
        val finish = body(src("ui/imessage/FaceTimeCallActivity.kt"), "private fun finishCall() {")
        assertTrue(finish, finish.contains("isAtLeast(Lifecycle.State.STARTED)) com.lightdecoder.imessage.direct.AppleAccount.callScreenEnded()"))
        // (d) the main process's chats and chat, when they come up.
        assertTrue(body(src("ui/chat/ConversationsActivity.kt"), "override fun onResume() {").contains("DirectLink.screenShown(this)"))
        assertTrue(body(src("ui/chat/ThreadActivity.kt"), "override fun onResume() {").contains("DirectLink.screenShown(this)"))
        // Its only "visible" request, and its check after a change never says it.
        val link = src("imessage/direct/DirectLink.kt")
        assertEquals(1, Regex("account\\(visible = true\\)").findAll(link).count())
        assertTrue(body(link, "    fun screenShown() {").contains("DirectWire.account(visible = true)"))
        assertFalse(body(link, "private fun accountChanged(").contains("visible"))
        // Nothing by itself: no timer, no retry, no nudge.
        val account = src("imessage/direct/AppleAccount.kt")
        for (gone in listOf("keepTrying", "backoffMs", "RESTART_BACKOFF_MAX_MS", "nudgeRestart", "retryIn", "RESTART_RETRY_MS", "READ_GRACE_MS")) {
            assertFalse(gone, account.contains(gone))
        }
        assertFalse(account.substringAfter("private fun trySoon() {").substringBefore("\n    }").contains("delay("))
    }

    @Test
    fun noRequestOfTheMainProcessButTheVisibleAccountOneCallsIntoTheRestart() {
        val service = File("src/main/java/com/lightdecoder/imessage/direct/DirectService.kt").readText()
        val restartWords = Regex("screenInSight|screenShown|restartNow|finishSignIn|callScreenEnded|nudgeRestart|restartForAccount|whenFree|ProcessRestart|account\\.cancel")
        // The request handler itself reaches none of it, whatever the request.
        val handler = service.substringAfter("private val server = DirectIpc.Server(").substringBefore("    /**\n     * Once a request's answer has gone.")
        assertTrue(handler, handler.contains("DirectWire.ACCOUNT -> DirectWire.accountReply(engine.account.state.value, engine.account.restartPending, engine.features.removeImported)"))
        assertEquals(handler, null, restartWords.find(handler)?.value)
        // Once an answer has gone: only through restartsAfter, and only the account's screenInSight.
        val after = service.substringAfter("private fun afterReply(what: Int, request: JSONObject) {").substringBefore("\n    }")
        assertEquals("if (restartsAfter(what, request)) engine.account.screenInSight()", after.trim())
        assertEquals("nowhere else in the service", listOf("screenInSight"), restartWords.findAll(service).map { it.value }.toList())
        // restartsAfter: ACCOUNT, with a real true, and nothing else.
        for ((name, code) in DirectWire.REQUEST_CODES) {
            assertFalse(name, DirectService.restartsAfter(code, org.json.JSONObject()))
            if (code != DirectWire.ACCOUNT) assertFalse(name, DirectService.restartsAfter(code, DirectWire.account(visible = true)))
        }
        assertTrue(DirectService.restartsAfter(DirectWire.ACCOUNT, DirectWire.account(visible = true)))
        for (bad in listOf("{}", """{"visible":"true"}""", """{"visible":1}""", """{"visible":false}""", """{"visible":null}""")) {
            assertFalse(bad, DirectService.restartsAfter(DirectWire.ACCOUNT, org.json.JSONObject(bad)))
        }
        assertFalse(DirectService.restartsAfter(DirectWire.ACCOUNT, DirectWire.account()))
        // The engine reaches it only through the account's hook.
        val engine = File("src/main/java/com/lightdecoder/imessage/direct/DirectEngine.kt").readText()
        assertEquals(2, Regex("restartForAccount\\(").findAll(engine).count())
    }

    @Test
    fun aNewRegistrationMakesAPendingRestartNeedless() = runBlocking {
        rig.signedIn()
        hooks.busy = true
        assertEquals(AppleAccount.SignOut.Refused(Refusal.BUSY), account.signOut())
        hooks.busy = false
        account.signOut()
        assertTrue(account.restartPending)
        // The registration's new session is built with no account.
        account.newDeviceLocked { }
        account.sessionReplacedLocked()
        assertFalse(account.restartPending)
        assertTrue(account.restartNow())
        assertEquals(0, hooks.restarts.get())
    }

    // --- a sign-in left while Apple answered is never committed ------------------------------------------

    @Test
    fun aSignInLeftWhileTheCodeIsCheckedIsNeverCommitted() = runBlocking {
        bridge.onTryAuth = { _, _ -> AccountBridge.Step.NeedsDevice2Fa }
        val checking = java.util.concurrent.CountDownLatch(1)
        val answer = java.util.concurrent.CountDownLatch(1)
        bridge.onVerify = {
            checking.countDown()
            answer.await()
            AccountBridge.Verified(AccountBridge.Step.LoggedIn, true)
        }
        toPassword()
        account.signIn("user@example.com", PASSWORD)
        val step = CoroutineScope(Dispatchers.Default).async { account.verifyDeviceCode("123456") }
        assertTrue(checking.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // "Not Now" while Apple answers; then Apple says yes.
        account.cancel()
        answer.countDown()
        assertEquals(Page.Refused(Refusal.NOT_STARTED), step.await())
        account.settled()
        assertEquals(FlagState.NONE, rig.files.readFlag().state)
        assertFalse("what the library saved is wiped", File(rig.statePath, "account.plist").exists())
        assertFalse(account.state.value.signedIn)
        assertTrue(bridge.refs.all { it.closed })
        assertEquals(1, hooks.restarts.get())
    }

    // --- never in the way of iMessage ----------------------------------------------------------------------

    /** The account's file cannot be written from here on (its temporary file's place taken by a folder). */
    private fun breakTheFlag() {
        File(rig.files.flagFile.parentFile, rig.files.flagFile.name + ".tmp").mkdirs()
    }

    @Test
    fun aStartGoesOnWhenTheAccountsFileCannotBeWritten() = runBlocking {
        File(rig.statePath, "account.plist").writeText("half")
        val limits = AppleAccountRules.Limits(passwordFailures = listOf(rig.clock[0]))
        rig.files.writeFlag(AccountFlag(limits = limits, notice = AccountNotice.SIGNED_OUT))
        rig.restart()
        breakTheFlag()
        // An interrupted sign-in: wiped all the same, and the start goes on (no throw).
        account.atStartLocked()
        assertFalse(File(rig.statePath, "account.plist").exists())
        assertEquals("its own notice could not be written", AccountNotice.SIGNED_OUT, account.notice())
        account.sessionUpLocked()
        // The notice cannot be marked as shown: said again next time, nothing worse.
        account.noticeShown()
        assertEquals(AccountNotice.SIGNED_OUT, account.notice())
        assertEquals(limits, rig.files.readFlag().limits)
    }

    @Test
    fun aRegistrationGoesOnWhenTheAccountCannotBeWiped() = runBlocking {
        rig.signedIn()
        val order = ArrayList<String>()
        val failing = object : AccountBridge by bridge {
            override fun resetAppleLogin(statePath: String): Unit = throw RustBridgeException.Other("busy")
        }
        val a = AppleAccount(rig.files, failing, rig.lock, hooks, now = { rig.clock[0] })
        a.newDeviceLocked { order += "setIdentity" }
        assertEquals(listOf("setIdentity"), order)
        // Signed out as far as LightDecoder goes: the next start wipes it, before the session is built.
        assertEquals(FlagState.NONE, rig.files.readFlag().state)
        assertEquals(AccountNotice.SIGN_IN_AGAIN, rig.files.readFlag().notice)
        assertEquals(AppleAccountRules.StartAction.RESET_INTERRUPTED, AppleAccountRules.atStart(rig.files.accountSaved(), rig.files.readFlag().state))
        rig.restart()
        account.atStartLocked()
        assertFalse(File(rig.statePath, "account.plist").exists())
        assertEquals(AccountNotice.SIGN_IN_AGAIN, account.notice())
    }

    @Test
    fun aSignInLeftWhenTheAccountsFileCannotBeWrittenStillEnds() = runBlocking {
        rig.signedIn(FlagState.ATTENTION)
        bridge.onTryAuth = { _, _ -> AccountBridge.Step.NeedsDevice2Fa }
        account.begin()
        account.understood()
        account.signIn("user@example.com", PASSWORD)
        breakTheFlag()
        account.cancel()
        account.settled()
        assertTrue(bridge.refs.all { it.closed })
        assertFalse(File(rig.statePath, "account.plist").exists())
        assertEquals(1, hooks.restarts.get())
    }

    // --- signing out ----------------------------------------------------------------------------------

    @Test
    fun theSignOutIsLocalLeavesNoAccountFileAndRestarts() = runBlocking {
        rig.signedIn()
        val state = File(rig.statePath)
        // What later features and the library leave, and a record set aside for a next device.
        File(state, "backup.plist").writeText("set aside")
        File(state, "previous_device.tmp").writeText("set aside")
        File(state, "logs").apply { mkdirs() }.resolve("rust.log").writeText("Got spd")
        File(state, "logs.1").writeText("old log")
        rig.files.keychainCodeFile.writeText("sealed")
        rig.files.cloudDir.apply { mkdirs() }.resolve("tokens").writeText("t")
        rig.files.myProfileFile.writeText("{}")
        rig.files.phoneUserFile.writeText("sealed phone user")
        File(state, "id.plist").writeText("registration")
        File(state, "hw_info.plist").writeText("hardware")

        assertEquals(AppleAccount.SignOut.Done, account.signOut())
        assertEquals(listOf("resetAppleLogin", "getAvailableUser", "getAvailableUser"), bridge.calls.toList())
        assertNoForbiddenCall()
        for (gone in listOf("account.plist", "backup.plist", "previous_device.tmp", "logs", "logs.1")) assertFalse(gone, File(state, gone).exists())
        assertFalse(rig.files.flagFile.exists())
        assertFalse(rig.files.keychainCodeFile.exists())
        assertFalse(rig.files.cloudDir.exists())
        assertFalse(rig.files.myProfileFile.exists())
        // The registration stays whole: iMessage goes on with the phone's number.
        assertTrue(rig.files.phoneUserFile.exists())
        assertTrue(File(state, "id.plist").exists())
        assertTrue(File(state, "hw_info.plist").exists())
        assertEquals(1, hooks.keysDeleted)
        assertEquals(AccountState.SIGNED_OUT, account.state.value)
        assertEquals(AccountState.SIGNED_OUT, hooks.told.last())
        assertTrue(account.restartPending)
        assertTrue(account.restartNow())
        assertEquals(1, hooks.restarts.get())
        assertEquals(AppleAccount.SignOut.NotSignedIn, account.signOut())
    }

    // --- what the engine has it do ----------------------------------------------------------------------

    @Test
    fun atAStartTheTableIsFollowed() = runBlocking {
        // An account saved, never committed: an interrupted sign-in, wiped and said.
        File(rig.statePath, "account.plist").writeText("half")
        File(rig.statePath, "logs").mkdirs()
        rig.restart()
        account.atStartLocked()
        assertEquals(listOf("resetAppleLogin"), bridge.calls.toList())
        assertFalse(File(rig.statePath, "account.plist").exists())
        assertFalse(File(rig.statePath, "logs").exists())
        assertEquals(AccountNotice.INTERRUPTED, account.notice())
        assertFalse(account.state.value.signedIn)
        bridge.calls.clear()

        // A commit interrupted after the account was in place: finished.
        rig.signedIn(FlagState.COMMITTING)
        account.atStartLocked()
        assertEquals(FlagState.SIGNED_IN, rig.files.readFlag().state)
        assertTrue(bridge.calls.isEmpty())
        // Signed in: the session's iCloud services read, the main process told.
        account.sessionUpLocked()
        assertEquals(listOf("hasIcloudServices"), bridge.calls.toList())
        assertTrue(account.state.value.icloud)
        waitFor("the main process told") { hooks.told.any { it.icloud } }
        bridge.calls.clear()

        // Signed in, the library's logs deleted at every start.
        File(rig.statePath, "logs").mkdirs()
        rig.restart()
        account.atStartLocked()
        assertFalse(File(rig.statePath, "logs").exists())
        assertTrue(bridge.calls.isEmpty())
        assertTrue(File(rig.statePath, "account.plist").exists())

        // The account gone (Apple or the library took it): signed out, and said. Never wiped again here;
        // what the library logged while it was there goes.
        File(rig.statePath, "account.plist").delete()
        File(rig.statePath, "logs").mkdirs()
        rig.restart()
        account.atStartLocked()
        assertTrue(bridge.calls.isEmpty())
        assertFalse(File(rig.statePath, "logs").exists())
        assertEquals(FlagState.NONE, rig.files.readFlag().state)
        assertEquals(AccountNotice.SIGNED_OUT, account.notice())
        account.noticeShown()
        assertNull(account.notice())

        // Nothing at all, with neither.
        rig.files.flagFile.delete()
        rig.restart()
        account.atStartLocked()
        account.sessionUpLocked()
        assertTrue(bridge.calls.isEmpty())
    }

    @Test
    fun aNewRegistrationWipesTheAccountBeforeTheNewIdentity() = runBlocking {
        rig.signedIn()
        File(rig.statePath, "logs.1").writeText("Got spd")
        val order = ArrayList<String>()
        bridge.order = order
        account.newDeviceLocked { order += "setIdentity" }
        assertEquals(listOf("resetAppleLogin", "setIdentity"), order)
        assertFalse(File(rig.statePath, "account.plist").exists())
        assertFalse("the library's logs of the account go with it", File(rig.statePath, "logs.1").exists())
        val flag = rig.files.readFlag()
        assertEquals(FlagState.ATTENTION, flag.state)
        assertEquals(AccountNotice.SIGN_IN_AGAIN, flag.notice)
        assertTrue(account.state.value.attention)
        waitFor("the main process told") { hooks.told.any { it.attention } }
        // At the next start, with the account gone, the notice stays the one that says why.
        rig.restart()
        account.atStartLocked()
        assertEquals(AccountNotice.SIGN_IN_AGAIN, account.notice())
    }

    @Test
    fun theEventsAnAccountBringsAreAcknowledgedUnread() {
        val events = listOf(
            uniffi.rust_bridge.PushEvent.IdmsSignInRequest(uniffi.rust_bridge.SignInRequest("Sign-in", "near", "", "Allow", "Don't", "TX1", 1f, 2f)),
            uniffi.rust_bridge.PushEvent.IdmsTeardownSignIn("TX1"),
            uniffi.rust_bridge.PushEvent.TwoFaAuthEvent(true),
            uniffi.rust_bridge.PushEvent.CircleFinishEvent,
            uniffi.rust_bridge.PushEvent.FindMyUpdatedLocation(emptyList()),
            uniffi.rust_bridge.PushEvent.NewPhotostream(uniffi.rust_bridge.SharedAlbum(null, null, null, "A1", "pending", null, null, emptyList(), null)),
            uniffi.rust_bridge.PushEvent.BeaconShared("someone", "B1", uniffi.rust_bridge.FmBeaconAttributes("Keys", 1, "🔑")),
        )
        for (event in events) {
            assertEquals(event::class.simpleName, DirectEngine.Companion.EventRoute.ACKNOWLEDGE, DirectEngine.routeOf(event))
        }
        assertEquals(DirectEngine.Companion.EventRoute.ACKNOWLEDGE, DirectEngine.routeOf(uniffi.rust_bridge.PushEvent.Unhandled))
    }

    companion object {
        /** A password only these tests know (no real account's). */
        const val PASSWORD = "correct horse 9!"
        const val DAY_MS = 24 * 60 * 60_000L
    }
}
