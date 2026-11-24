package com.lightdecoder.imessage.direct

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Messages in iCloud" > "Turn On…" (B6.5) over a stand-in library: the order of its steps, how it
 * fails closed (no device approval, Advanced Data Protection, not joined), that the code is kept only
 * when asked, the crash window put right with the number registered alone, and that nothing it must
 * never do (reset or set up a keychain, join with another device's passcode, delete in iCloud) is reached.
 */
@RunWith(AndroidJUnit4::class)
class KeychainJoinTest {

    private val dir: File = Files.createTempDirectory("ld-join").toFile()
    private val files = AccountFiles(dir, File(dir, "imessage/state").apply { mkdirs() }.path)
    private val steps = FakeJoinSteps()
    private val kept = ArrayList<String>()
    private var busy = false
    private var changes = 0
    private var restarts = 0

    private val join = KeychainJoin(
        steps, files, Mutex(), busy = { busy }, allowed = { files.readFlag().state == FlagState.SIGNED_IN && files.readFlag().history != AccountFlag.HISTORY_ON },
        keepCode = { kept += it }, changed = { changes++ }, touched = { restarts++ }, now = { 1_700_000_000_000L }, random = { "482913" },
    )

    init {
        files.writeFlag(AccountFlag(FlagState.SIGNED_IN, 1))
    }

    private fun throughToCode(): KeychainJoin.Page = runBlocking {
        assertEquals(KeychainJoin.Page.Intro, join.begin())
        assertEquals(KeychainJoin.Page.DeviceCode(), join.understood())
        join.verify("123456")
    }

    @Test
    fun theStepsGoInOrderAndOnlyThenIsMessagesInIcloudOn() {
        assertEquals(KeychainJoin.Page.Code("482913"), throughToCode())
        assertEquals(listOf("authSaved", "sendToDevices", "verify", "reconfigure", "setupClique", "inClique", "close"), steps.calls)
        assertEquals(listOf("482913"), steps.codes)
        val flag = files.readFlag()
        assertEquals(FlagState.SIGNED_IN, flag.state)
        assertEquals(AccountFlag.HISTORY_ON, flag.history)
        assertTrue(changes > 0)
    }

    @Test
    fun theCodeIsKeptOnlyWhenTheUserAsks() {
        throughToCode()
        assertEquals(KeychainJoin.Page.Done, join.codeSeen(keep = false))
        assertTrue(kept.isEmpty())
        // Shown once: a second "Done" keeps nothing either.
        join.codeSeen(keep = true)
        assertTrue(kept.isEmpty())
    }

    @Test
    fun theCodeIsKeptWhenTicked() {
        throughToCode()
        join.codeSeen(keep = true)
        assertEquals(listOf("482913"), kept)
    }

    @Test
    fun withoutADeviceApprovalNothingIsRebuilt() = runBlocking {
        steps.auth = AccountBridge.Step.LoggedIn
        join.begin()
        val p = join.understood()
        assertEquals(KeychainJoin.Page.Failed(KeychainJoin.Why.SIGN_IN_AGAIN), p)
        assertFalse("reconfigure" in steps.calls)
        assertEquals(AccountFlag.HISTORY_OFF, files.readFlag().history)
        // A text message only: not offered either (no other device's passcode is ever asked for).
        steps.calls.clear()
        steps.auth = AccountBridge.Step.NeedsSms2Fa
        join.begin()
        assertEquals(KeychainJoin.Page.Failed(KeychainJoin.Why.SIGN_IN_AGAIN), join.understood())
        assertFalse("sendToDevices" in steps.calls)
    }

    @Test
    fun aCodeThatDoesNotSignInCommitsNothing() = runBlocking {
        steps.verified = AccountBridge.Verified(AccountBridge.Step.NeedsExtraStep("x"), false)
        join.begin()
        join.understood()
        assertEquals(KeychainJoin.Page.Failed(KeychainJoin.Why.SIGN_IN_AGAIN), join.verify("123456"))
        assertFalse("reconfigure" in steps.calls)
        assertEquals(FlagState.SIGNED_IN, files.readFlag().state)
    }

    @Test
    fun advancedDataProtectionFailsClosed() {
        steps.cliqueFails = DirectEngine.ImessageError("Disable Advanced Data Protection")
        val p = throughToCode()
        assertEquals(KeychainJoin.Why.ADP, (p as KeychainJoin.Page.Failed).why)
        assertEquals(AccountFlag.HISTORY_OFF, files.readFlag().history)
        assertEquals(FlagState.SIGNED_IN, files.readFlag().state)
    }

    @Test
    fun notJoinedAfterAllFailsClosed() {
        steps.joined = false
        val p = throughToCode()
        assertEquals(KeychainJoin.Page.Failed(KeychainJoin.Why.NOT_JOINED), p)
        assertEquals(AccountFlag.HISTORY_OFF, files.readFlag().history)
    }

    @Test
    fun aRebuildThatFailsRegistersTheNumberAloneAgainAndSignsTheAccountOut() {
        steps.reconfigureFails = DirectEngine.ImessageError("boom")
        val p = throughToCode()
        assertEquals(KeychainJoin.Why.FAILED, (p as KeychainJoin.Page.Failed).why)
        assertEquals(listOf("recover", "signOutHere"), steps.calls.filter { it == "recover" || it == "signOutHere" })
        assertEquals(FlagState.ATTENTION, files.readFlag().state)
        assertEquals(AccountFlag.HISTORY_OFF, files.readFlag().history)
    }

    @Test
    fun aCrashInTheWindowIsPutRightAtTheNextStartWithTheNumberAlone() = runBlocking {
        // The process ended after the registration was deleted: no session could be restored.
        files.writeFlag(AccountFlag(FlagState.COMMITTING, 1, history = AccountFlag.HISTORY_JOINING))
        assertTrue(KeychainJoin.needsRecovery(files.readFlag(), restored = false))
        join.atStartLocked(restored = false)
        assertEquals(listOf("recover", "signOutHere"), steps.calls)
        assertEquals(FlagState.ATTENTION, files.readFlag().state)
        assertEquals(AccountNotice.SIGN_IN_AGAIN, files.readFlag().notice)
    }

    @Test
    fun aCrashOutsideTheWindowOnlyLeavesItOff() = runBlocking {
        files.writeFlag(AccountFlag(FlagState.SIGNED_IN, 1, history = AccountFlag.HISTORY_JOINING))
        assertFalse(KeychainJoin.needsRecovery(files.readFlag(), restored = true))
        join.atStartLocked(restored = true)
        assertTrue(steps.calls.isEmpty())
        assertEquals(AccountFlag.HISTORY_OFF, files.readFlag().history)
        assertEquals(FlagState.SIGNED_IN, files.readFlag().state)
    }

    @Test
    fun withNoJoinCutShortAStartDoesNothing() = runBlocking {
        join.atStartLocked(restored = false)
        assertTrue(steps.calls.isEmpty())
    }

    @Test
    fun neverDuringAFaceTimeCallAndNeverWithoutTheAccount() = runBlocking {
        busy = true
        assertEquals(KeychainJoin.Page.Failed(KeychainJoin.Why.BUSY), join.begin())
        assertEquals(KeychainJoin.Page.Failed(KeychainJoin.Why.BUSY), join.understood())
        busy = false
        files.writeFlag(AccountFlag(FlagState.ATTENTION, 1))
        assertEquals(KeychainJoin.Page.Failed(KeychainJoin.Why.NOT_ALLOWED), join.begin())
        assertFalse("authSaved" in steps.calls)
    }

    @Test
    fun aRebuildThatFailsBeforeAnythingWasDeletedChangesNothing() {
        steps.reconfigureFails = KeychainJoin.Untouched(DirectEngine.ImessageError("the phone's number is not kept"))
        val p = throughToCode()
        assertEquals(KeychainJoin.Why.FAILED, (p as KeychainJoin.Page.Failed).why)
        assertFalse("recover" in steps.calls)
        assertFalse("signOutHere" in steps.calls)
        assertEquals(FlagState.SIGNED_IN, files.readFlag().state)
        assertEquals(AccountFlag.HISTORY_OFF, files.readFlag().history)
        assertFalse(files.joinMarker.isFile)
        // The live session's connection was taken for the sign-in: iMessage restarts.
        assertEquals(1, restarts)
    }

    @Test
    fun aRecoveryThatFailsKeepsItsMarkAndIsTriedAgainAtTheNextStart() = runBlocking {
        steps.reconfigureFails = DirectEngine.ImessageError("boom")
        steps.recoverFails = DirectEngine.ImessageError("no network")
        throughToCode()
        // Not signed out, not given up on: the next start tries again.
        assertFalse("signOutHere" in steps.calls)
        assertTrue(files.joinMarker.isFile)
        assertEquals(AccountFlag.HISTORY_JOINING, files.readFlag().history)
        assertTrue(restarts > 0)
        steps.calls.clear()
        steps.recoverFails = null
        join.atStartLocked(restored = false)
        assertEquals(listOf("recover", "signOutHere"), steps.calls)
        assertEquals(FlagState.ATTENTION, files.readFlag().state)
        assertFalse(files.joinMarker.isFile)
    }

    @Test
    fun theMarkOutlivesTheAccountsFlag() = runBlocking {
        // The start found no account any more and wrote the flag empty: the join's own mark still says.
        files.mark(files.joinMarker, true)
        files.writeFlag(AccountFlag(FlagState.NONE, 0))
        join.atStartLocked(restored = false)
        assertEquals(listOf("recover", "signOutHere"), steps.calls)
        assertEquals("no account flag made again", FlagState.NONE, files.readFlag().state)
        assertFalse(files.joinMarker.isFile)
    }

    @Test
    fun withNothingToRebuildWithNoApprovalIsAskedFor() = runBlocking {
        steps.ready = false
        join.begin()
        assertEquals(KeychainJoin.Page.Failed(KeychainJoin.Why.NOT_READY), join.understood())
        assertFalse("authSaved" in steps.calls)
    }

    @Test
    fun aJoinLeftBeforeItsCommitRestartsIMessage() = runBlocking {
        join.begin()
        join.understood()
        join.cancel()
        assertEquals(1, restarts)
        assertFalse("reconfigure" in steps.calls)
    }

    @Test
    fun leavingTheScreenDuringTheCommitNeverCutsItShort() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        steps.reconfigureGate = gate
        join.begin()
        join.understood()
        val result = async(kotlinx.coroutines.Dispatchers.Default) { join.verify("123456") }
        while ("reconfigure" !in steps.calls) kotlinx.coroutines.delay(5)
        join.cancel()
        assertFalse("nothing let go of meanwhile", "close" in steps.calls)
        assertEquals(KeychainJoin.Page.Failed(KeychainJoin.Why.BUSY), join.begin())
        gate.complete(Unit)
        assertEquals(KeychainJoin.Page.Code("482913"), result.await())
        assertEquals(AccountFlag.HISTORY_ON, files.readFlag().history)
        // The screen made again: the code not yet seen is shown again.
        assertEquals(KeychainJoin.Page.Code("482913"), join.begin())
        assertEquals(0, restarts)
    }

    @Test
    fun aCodeIsSixRandomDigits() {
        repeat(20) { assertTrue(KeychainJoin.newCode().matches(Regex("[0-9]{6}"))) }
    }

    @Test
    fun nothingItMustNeverDoIsAStepAtAll() {
        val names = KeychainJoin.Steps::class.java.declaredMethods.map { it.name }.toSet()
        for (never in FakeAccountBridge.NEVER - FakeAccountBridge.JOIN_ONLY.toSet()) assertFalse(never, never in names)
        throughToCode()
        for (never in FakeAccountBridge.NEVER) assertFalse(never, steps.calls.any { it.equals(never, ignoreCase = true) })
    }
}
