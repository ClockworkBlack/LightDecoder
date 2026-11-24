package com.lightdecoder.imessage.direct

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.imessage.direct.AppleAccountRules.Next
import com.lightdecoder.imessage.direct.AppleAccountRules.Progress
import com.lightdecoder.imessage.direct.AppleAccountRules.StartAction
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The optional Apple Account's rules: where each answer leads, the limits, the masks, a start's table, the errors. */
@RunWith(AndroidJUnit4::class)
class AppleAccountRulesTest {

    private val body = FakeAccountBridge.Ref("body")

    // --- every LoginResult leads somewhere -----------------------------------------------------------

    @Test
    fun onlyAnAuthenticationErrorMakesTheAccountNeedAttention() {
        for (m in listOf("HTTP 401 Unauthorized", "Auth error: token expired", "authentication failed", "Invalid token", "token refresh failed")) {
            assertTrue(m, AppleAccountRules.isAuthFailure(m))
        }
        for (m in listOf(null, "", "Connection reset", "Request timed out (401 ms)", "StatusKit is unavailable without a keychain", "error 4010", "Advanced Data Protection")) {
            assertFalse("$m", AppleAccountRules.isAuthFailure(m))
        }
    }

    @Test
    fun everyAnswerOfTheLibraryLeadsToAPage() {
        val p = Progress(credentials = true)
        assertEquals(Next.IcloudLogin, AppleAccountRules.next(AccountBridge.Step.LoggedIn, p))
        assertEquals(Next.Commit, AppleAccountRules.next(AccountBridge.Step.LoggedIn, p.copy(user = true)))
        assertEquals("iCloud asked once already: given up on", Next.Incomplete, AppleAccountRules.next(AccountBridge.Step.LoggedIn, p.copy(icloudTried = true)))
        assertEquals(Next.SendToDevices, AppleAccountRules.next(AccountBridge.Step.NeedsDevice2Fa, p))
        assertEquals("no session to check it with: codes first", Next.SendToDevices, AppleAccountRules.next(AccountBridge.Step.Needs2FaVerification, p))
        assertEquals(Next.EnterDeviceCode, AppleAccountRules.next(AccountBridge.Step.Needs2FaVerification, p.copy(circle = true)))
        assertEquals(Next.TextOptions, AppleAccountRules.next(AccountBridge.Step.NeedsSms2Fa, p))
        val text = AppleAccountRules.next(AccountBridge.Step.NeedsSmsVerification(body), p)
        assertTrue(text is Next.EnterTextCode)
        assertSame(body, (text as Next.EnterTextCode).body)
        assertEquals(Next.AppleMessage("Sign in on your iPhone first."), AppleAccountRules.next(AccountBridge.Step.NeedsExtraStep("Sign in on your iPhone first."), p))
    }

    @Test
    fun needsLoginIsTriedOnceMoreWithThePasswordThenIsAWrongPassword() {
        assertEquals(Next.RetryPassword, AppleAccountRules.next(AccountBridge.Step.NeedsLogin, Progress(credentials = true)))
        assertEquals(Next.WrongPassword, AppleAccountRules.next(AccountBridge.Step.NeedsLogin, Progress(credentials = true, retried = true)))
        // What the phone saved, without a password (or one let go of): the password is asked for, nothing counted.
        assertEquals(Next.EnterPassword, AppleAccountRules.next(AccountBridge.Step.NeedsLogin, Progress(credentials = false)))
        assertEquals(Next.EnterPassword, AppleAccountRules.next(AccountBridge.Step.NeedsLogin, Progress(credentials = false, retried = true)))
    }

    @Test
    fun applesWordsAreShownPlainAndShort() {
        val next = AppleAccountRules.next(AccountBridge.Step.NeedsExtraStep("Sign in‮ on\u0007 your\niPhone " + "x".repeat(400)), Progress())
        val text = (next as Next.AppleMessage).text
        assertFalse(text.any { it == '‮' || it == '\u0007' || it == '\n' })
        assertTrue(text.startsWith("Sign in on your iPhone "))
        assertTrue(text.length <= AppleAccountRules.MAX_APPLE_TEXT + 1)
        assertTrue(text.endsWith("…"))
    }

    @Test
    fun errorsAreTakenByTheirWordsNeverByNumbers() {
        assertEquals(AppleAccountRules.ErrorKind.TERMS, AppleAccountRules.classify("Failed: MOBILEME_TERMS_OF_SERVICE_UPDATE (-22406)"))
        assertEquals(AppleAccountRules.ErrorKind.TIMEOUT, AppleAccountRules.classify("Timed Out!"))
        assertEquals(AppleAccountRules.ErrorKind.OTHER, AppleAccountRules.classify("-20101"))
        assertEquals(AppleAccountRules.ErrorKind.OTHER, AppleAccountRules.classify("Your Apple Account or password was incorrect."))
        assertEquals(AppleAccountRules.ErrorKind.OTHER, AppleAccountRules.classify(null))
    }

    // --- S7: limits that outlive the process ----------------------------------------------------------

    @Test
    fun threeFailedPasswordsInFifteenMinutesWaitTheRestOfThem() {
        val t = 1_000_000_000L
        var l = AppleAccountRules.Limits()
        assertEquals(0, l.passwordWaitMs(t))
        l = l.passwordFailed(t).passwordFailed(t + 60_000)
        assertEquals("two: a third may be tried at once", 0, l.passwordWaitMs(t + 60_000))
        l = l.passwordFailed(t + 120_000)
        assertEquals(15 * 60_000L - 120_000, l.passwordWaitMs(t + 120_000))
        assertEquals("the first one out of the window: allowed", 0, l.passwordWaitMs(t + 15 * 60_000L))
        // The clock put back by a year does not undo them; one too far ahead to be real is dropped.
        val back = AppleAccountRules.Limits(passwordFailures = listOf(t, t, t))
        assertTrue(back.passwordWaitMs(t - 60_000) > 0)
        assertEquals(0, AppleAccountRules.Limits(passwordFailures = listOf(t, t, t)).passwordWaitMs(t - 365L * 24 * 3600_000))
    }

    @Test
    fun codesGoOneAMinuteAndFiveAnHour() {
        val t = 2_000_000_000L
        var l = AppleAccountRules.Limits().deviceCodeSent(t)
        assertEquals(60_000, l.deviceCodeWaitMs(t))
        assertEquals(30_000, l.deviceCodeWaitMs(t + 30_000))
        assertEquals(0, l.deviceCodeWaitMs(t + 60_000))
        for (i in 1..4) l = l.deviceCodeSent(t + i * 60_000L)
        assertEquals("five in the hour: until the first is an hour old", 60 * 60_000L - 5 * 60_000L, l.deviceCodeWaitMs(t + 5 * 60_000L))
        assertEquals(0, l.deviceCodeWaitMs(t + 60 * 60_000L + 1))
        // Texts are counted on their own.
        assertEquals(0, l.textCodeWaitMs(t))
        val sms = AppleAccountRules.Limits().textCodeSent(t)
        assertEquals(60_000, sms.textCodeWaitMs(t))
    }

    @Test
    fun theLimitsAreKeptAcrossARestartAsNumbersOnly() {
        val dir = Files.createTempDirectory("ld-rules").toFile()
        val files = AccountFiles(dir, File(dir, "imessage/state").path)
        val t = 3_000_000_000L
        val limits = AppleAccountRules.Limits().passwordFailed(t).passwordFailed(t + 1).passwordFailed(t + 2).deviceCodeSent(t).textCodeSent(t)
        files.writeFlag(AccountFlag(limits = limits))
        // Another process (a restart) reads the same file.
        val again = AccountFiles(dir, File(dir, "imessage/state").path).readFlag()
        assertEquals(limits, again.limits)
        assertEquals(FlagState.NONE, again.state)
        assertTrue(again.limits.passwordWaitMs(t + 3) > 0)
        val json = files.flagFile.readText()
        assertFalse("nothing but times", Regex("[A-Za-z@]").findAll(json.replace(Regex("\"(pw|dev|sms)\""), "")).any())
        // Read strictly: what is not a number is left out, too many are cut.
        val odd = AccountFlag.fromJson("""{"state":"signedIn","since":5,"pw":["x",12,true,null,13],"dev":${(1..100).toList()},"notice":"no such","extra":1}""")
        assertEquals(FlagState.SIGNED_IN, odd.state)
        assertEquals(listOf(12L, 13L), odd.limits.passwordFailures)
        assertEquals(AppleAccountRules.MAX_STAMPS, odd.limits.deviceCodes.size)
        assertNull(odd.notice)
        assertEquals(AccountFlag(), AccountFlag.fromJson("not json"))
        assertEquals(FlagState.NONE, AccountFlag.fromJson("""{"state":"loggedIn"}""").state)
    }

    // --- how the Apple ID shows ---------------------------------------------------------------------

    @Test
    fun theAppleIdIsMasked() {
        assertEquals("j•••@icloud.com", AppleAccountRules.mask("john@icloud.com"))
        assertEquals("j•••@icloud.com", AppleAccountRules.mask(" john.appleseed@icloud.com "))
        assertEquals("•••@example.com", AppleAccountRules.mask("@example.com"))
        assertEquals("+1•••••4567", AppleAccountRules.mask("+15551234567"))
        assertEquals("+1•••••4567", AppleAccountRules.mask("+1 (555) 123-4567"))
        assertEquals("+44•••••0958", AppleAccountRules.mask("+44 20 7946 0958"))
        assertEquals("•••••4567", AppleAccountRules.mask("555 123 4567"))
        assertEquals("•••••", AppleAccountRules.mask("12345"))
        assertFalse(AppleAccountRules.mask("‮evil@example.com").contains('‮'))
    }

    // --- §4.4: what a start finds ---------------------------------------------------------------------

    @Test
    fun everyRowOfTheStartTable() {
        assertEquals(StartAction.NOTHING, AppleAccountRules.atStart(accountSaved = false, FlagState.NONE))
        assertEquals(StartAction.RESET_INTERRUPTED, AppleAccountRules.atStart(accountSaved = true, FlagState.NONE))
        assertEquals(StartAction.FINISH_COMMIT, AppleAccountRules.atStart(accountSaved = true, FlagState.COMMITTING))
        assertEquals(StartAction.NORMAL, AppleAccountRules.atStart(accountSaved = true, FlagState.SIGNED_IN))
        assertEquals(StartAction.NORMAL, AppleAccountRules.atStart(accountSaved = true, FlagState.ATTENTION))
        assertEquals(StartAction.VANISHED, AppleAccountRules.atStart(accountSaved = false, FlagState.SIGNED_IN))
        assertEquals(StartAction.VANISHED, AppleAccountRules.atStart(accountSaved = false, FlagState.ATTENTION))
        assertEquals(StartAction.VANISHED, AppleAccountRules.atStart(accountSaved = false, FlagState.COMMITTING))
    }

    @Test
    fun theFlagNeverHoldsMoreThanItsOwnWords() {
        val flag = AccountFlag(FlagState.ATTENTION, 42, notice = AccountNotice.SIGN_IN_AGAIN)
        val json = flag.toJson()
        assertEquals(setOf("state", "since", "notice"), json.keys().asSequence().toSet())
        assertEquals(flag, AccountFlag.fromJson(json.toString()))
        assertEquals("nothing to keep: no file", 0, AccountFlag().toJson().length())
    }
}
