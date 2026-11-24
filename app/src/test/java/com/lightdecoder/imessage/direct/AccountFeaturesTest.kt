package com.lightdecoder.imessage.direct

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.rust_bridge.ShareProfileMessage
import uniffi.rust_bridge.StatusKitUpdate

/**
 * The Apple Account's features in the ":imessage" process (B6) over stand-in libraries: Name & Photo
 * ([Profiles]), Focus status in and out ([FocusStatus], [FocusSharer]), and that without a signed-in
 * account with iCloud's services none of it calls the library at all ([AccountFeatures]).
 */
@RunWith(AndroidJUnit4::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountFeaturesTest {

    private val rig = AccountRig()
    private val bridge = FakeFeatureBridge()
    private val delivered = ArrayList<DirectArchive.Update>()
    private val join = FakeJoinSteps()
    private val bob = "tel:+15550000002"

    private fun features(scope: CoroutineScope = CoroutineScope(UnconfinedTestDispatcher())) = AccountFeatures(
        rig.account, rig.files, bridge, rig.lock, busy = { false }, sender = { "tel:+15550000001" },
        deliver = { delivered += it; true }, joinSteps = { join }, keepCode = {}, scope = scope,
    )

    /** Signed in, the session up with iCloud's services. */
    private fun signedIn() {
        rig.signedIn()
        rig.bridge.icloud = true
        runBlocking { rig.account.sessionUpLocked() }
        assertTrue(rig.account.state.value.features)
    }

    private val descriptor = ProfileDescriptor(byteArrayOf(1, 2, 3), "record-1")

    // --- S1: nothing without the account -----------------------------------------------------------

    @Test
    fun withoutAnAccountNothingCallsTheLibrary() = runBlocking {
        val f = features()
        f.atStartLocked(restored = true)
        f.sessionUp()
        assertNull(f.fetchProfile(DirectWire.ProfileFetchRequest(descriptor, File(rig.filesDir, "x").path)))
        f.focusRequest(listOf(bob), listOf(bob))
        f.onStatusKit(StatusKitUpdate(bob, null, false))
        assertFalse(f.takes(DirectArchive.Update.ProfileOffer("imsg:$bob", bob, descriptor.encode())))
        assertFalse(f.importNow())
        assertEquals(Profiles.Publish.Off, f.profiles.publish("A", "B", null, replace = false))
        assertNull(f.profiles.pointer())
        f.sharer.dndChanged(true)
        assertTrue(bridge.calls.toString(), bridge.calls.isEmpty())
        assertTrue(join.calls.isEmpty())
        assertTrue(delivered.isEmpty())
        assertTrue(rig.bridge.calls.isEmpty())
        assertEquals(KeychainJoin.Page.Failed(KeychainJoin.Why.NOT_ALLOWED), f.join.begin())
        // Nothing written either.
        assertFalse(rig.files.flagFile.exists())
        assertFalse(rig.files.cloudDir.exists())
        assertFalse(rig.files.myProfileFile.exists())
    }

    @Test
    fun needingASignInIsAsWithout() = runBlocking {
        signedIn()
        rig.files.writeFlag(rig.files.readFlag().copy(state = FlagState.ATTENTION))
        rig.account.featuresChanged()
        val f = features()
        f.sessionUp()
        f.focusRequest(listOf(bob), emptyList())
        assertTrue(bridge.calls.isEmpty())
    }

    // --- Name & Photo --------------------------------------------------------------------------------

    @Test
    fun aDescriptorIsReadStrictlyAndNeverCarriesAPoster() {
        val d = ProfileDescriptor.decode(descriptor.encode())!!
        assertEquals(descriptor, d)
        assertNull(d.toMessage().poster)
        assertNull(ProfileDescriptor.decode("""{"v":2,"k":"AQID","r":"x"}"""))
        assertNull(ProfileDescriptor.decode("""{"v":1,"k":"!!","r":"x"}"""))
        assertNull(ProfileDescriptor.decode("""{"v":1,"k":"AQID","r":"has space"}"""))
        assertNull(ProfileDescriptor.decode("not json"))
        assertNull(ProfileDescriptor.of(ShareProfileMessage(ByteArray(0), "r", null)))
        assertNull(ProfileDescriptor.of(ShareProfileMessage(ByteArray(ProfileDescriptor.MAX_KEY_BYTES + 1), "r", null)))
    }

    @Test
    fun aFetchWritesAPhotoWithinItsCapAndCleansTheNames() = runBlocking {
        signedIn()
        val f = features()
        bridge.fetched = { FeatureBridge.Fetched("Bob‮​ Example\n", "Bob", "Example", byteArrayOf(7, 7, 7)) }
        val out = File(rig.filesDir, "imessage-profiles/incoming-1.img")
        val r = f.fetchProfile(DirectWire.ProfileFetchRequest(descriptor, out.path))!!
        assertEquals("Bob Example", r.name)
        assertTrue(r.image)
        assertArrayEquals(byteArrayOf(7, 7, 7), out.readBytes())
        // Over the cap: no photo written.
        bridge.fetched = { FeatureBridge.Fetched("Bob", "", "", ByteArray(Profiles.MAX_IMAGE_BYTES + 1)) }
        val big = File(rig.filesDir, "imessage-profiles/incoming-2.img")
        val r2 = f.fetchProfile(DirectWire.ProfileFetchRequest(descriptor, big.path))!!
        assertFalse(r2.image)
        assertFalse(big.exists())
    }

    @Test
    fun aFetchAppleDoesNotAnswerEndsInTwentySeconds() = runTest {
        signedIn()
        val f = features()
        bridge.fetched = { awaitCancellation() }
        val before = currentTime
        assertNull(f.fetchProfile(DirectWire.ProfileFetchRequest(descriptor, File(rig.filesDir, "y").path)))
        assertEquals(Profiles.FETCH_MS, currentTime - before)
    }

    @Test
    fun withNamesAndPhotosOffNothingIsTakenOrFetched() = runBlocking {
        signedIn()
        val f = features()
        f.setProfilesIn(false)
        assertFalse(f.takes(DirectArchive.Update.ProfileOffer("imsg:$bob", bob, descriptor.encode())))
        assertNull(f.fetchProfile(DirectWire.ProfileFetchRequest(descriptor, File(rig.filesDir, "z").path)))
        assertFalse("fetchProfile" in bridge.calls)
        assertFalse(rig.account.state.value.profiles)
    }

    @Test
    fun theUsersOwnIsNeverPublishedOverTheIphonesWithoutAsking() = runBlocking {
        signedIn()
        val f = features()
        bridge.hasRecord = true
        assertEquals(Profiles.Publish.AskReplace, f.profiles.publish("Ann", "Lee", null, replace = false))
        assertFalse("setProfile" in bridge.calls)
        assertEquals(Profiles.Publish.Done, f.profiles.publish("Ann", "Lee", null, replace = true))
        assertEquals("Ann Lee", f.profiles.own()!!.name)
        // Shared only once its setting is on (off by default), one chat at a time.
        assertNull(f.profiles.pointer())
        assertFalse(rig.account.state.value.profile)
        f.setProfileShare(true)
        assertEquals(bridge.pointer, f.profiles.pointer())
        assertTrue(rig.account.state.value.profile)
    }

    // --- Focus ---------------------------------------------------------------------------------------

    @Test
    fun focusIsTakenOnlyForSomeoneAskedAbout() = runBlocking {
        signedIn()
        val f = features()
        f.onStatusKit(StatusKitUpdate(bob, null, false))
        assertTrue(delivered.isEmpty())
        f.focusRequest(listOf(bob, "not an address"), emptyList())
        assertEquals(listOf(listOf(bob)), bridge.subscribed)
        f.onStatusKit(StatusKitUpdate(bob, null, false))
        val u = delivered.single() as DirectArchive.Update.Focus
        assertEquals(bob, u.handle)
        assertTrue(u.silenced)
        f.onStatusKit(StatusKitUpdate("tel:+15559999999", null, false))
        assertEquals(1, delivered.size)
    }

    @Test
    fun theSameHandlesAreNotAskedAgainWithinTenMinutes() = runBlocking {
        signedIn()
        val f = features()
        f.focusRequest(listOf(bob), emptyList())
        f.focusRequest(listOf(bob), emptyList())
        assertEquals(1, bridge.subscribed.size)
    }

    @Test
    fun anAuthenticationErrorFromAFeatureMakesTheAccountNeedAttentionOnceAndNothingLoops() = runBlocking {
        signedIn()
        val f = features()
        bridge.subscribeFails = DirectEngine.ImessageError("HTTP 401 Unauthorized")
        f.focusRequest(listOf(bob), emptyList())
        val deadline = System.currentTimeMillis() + 5_000
        while (rig.files.readFlag().state != FlagState.ATTENTION && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(FlagState.ATTENTION, rig.files.readFlag().state)
        assertEquals(AccountNotice.SIGN_IN_AGAIN, rig.files.readFlag().notice)
        assertTrue("the account is kept for a one-tap sign-in", rig.files.accountSaved())
        assertTrue(rig.account.state.value.attention)
        assertFalse("its features are off now", rig.account.state.value.features)
        // Nothing is tried again by itself, and nothing more asks Apple.
        bridge.calls.clear()
        f.focusRequest(listOf("tel:+15550000003"), emptyList())
        assertTrue(bridge.calls.toString(), bridge.calls.isEmpty())
        assertTrue("no sign-in was tried", rig.bridge.auths.isEmpty())
        // Said once: a second failure changes nothing.
        Thread.sleep(200)
        val told = rig.hooks.told.size
        rig.account.authFailed()
        Thread.sleep(200)
        assertEquals(told, rig.hooks.told.size)
        assertEquals(FlagState.ATTENTION, rig.files.readFlag().state)
    }

    @Test
    fun appleRefusingFocusMarksItNotAvailable() = runBlocking {
        signedIn()
        val f = features()
        bridge.subscribeFails = DirectEngine.ImessageError("StatusKit is unavailable without a keychain")
        f.focusRequest(listOf(bob), emptyList())
        assertTrue(rig.files.readFlag().focusUnavailable)
        assertFalse(rig.account.state.value.focus)
        bridge.calls.clear()
        f.focusRequest(listOf("tel:+15550000003"), emptyList())
        assertTrue("asked no more", bridge.calls.isEmpty())
    }

    @Test
    fun sharingTheUsersFocusIsOffByDefaultAndGoesOnlyFromThePhonesNumber() = runBlocking {
        signedIn()
        val f = features()
        assertFalse(rig.files.readFlag().focusShare)
        f.focusRequest(emptyList(), listOf(bob))
        f.sharer.dndChanged(true)
        assertTrue(bridge.shared.isEmpty())
        f.setFocusShare(true)
        f.sharer.dndChanged(true)
        assertEquals(listOf<String?>(FocusSharer.MODE), bridge.shared)
        assertEquals(listOf("tel:+15550000001" to listOf(bob)), bridge.invited)
        f.sharer.dndChanged(false)
        assertEquals(listOf(FocusSharer.MODE, null), bridge.shared)
        // Turned off: those who could see it are taken off and the keys rolled.
        f.setFocusShare(false)
        assertEquals(listOf(bob), bridge.removed)
        assertTrue("statusResetKeys" in bridge.calls)
    }

    @Test
    fun thoseLetSeeTheUsersFocusAreTakenOffEvenAfterARestart() = runBlocking {
        signedIn()
        val f = features()
        f.setFocusShare(true)
        f.focusRequest(emptyList(), listOf(bob))
        f.sharer.dndChanged(true)
        assertEquals(listOf("tel:+15550000001" to listOf(bob)), bridge.invited)
        // The process made again: who was let see it is still known, and taken off.
        val again = features()
        again.setFocusShare(false)
        assertEquals(listOf(bob), bridge.removed)
        assertFalse(rig.files.focusInvitedFile.exists())
    }

    @Test
    fun aWithdrawThatDidNotFinishKeepsThoseNotTakenOffForTheNextOne() = runBlocking {
        signedIn()
        val f = features()
        val carol = "tel:+15550000004"
        f.setFocusShare(true)
        f.focusRequest(emptyList(), listOf(bob, carol))
        f.sharer.dndChanged(true)
        // Carol not taken off: she is kept (on disk too), Bob is not.
        bridge.removeFails += carol
        f.setFocusShare(false)
        assertEquals(setOf(carol), f.sharer.invitedKeys())
        assertTrue(rig.files.focusInvitedFile.exists())
        // The keys not rolled: nobody is forgotten.
        bridge.removeFails.clear()
        bridge.resetFails = true
        bridge.removed.clear()
        val again = features()
        again.sessionUp()
        assertEquals(listOf(carol), bridge.removed)
        assertTrue(again.sharer.anyInvited())
        // A sign-out tries again although sharing is off, and this time it all goes.
        bridge.resetFails = false
        bridge.removed.clear()
        again.stopForSignOut()
        assertEquals(listOf(carol), bridge.removed)
        assertFalse(again.sharer.anyInvited())
        assertFalse(rig.files.focusInvitedFile.exists())
    }

    @Test
    fun turningMessagesInIcloudOnAgainInTheKeychainAlreadyJoinsNothing() = runBlocking {
        signedIn()
        val f = features()
        f.sessionUp()
        assertTrue(f.joined())
        // In the keychain already: no join is offered (nothing rebuilt, no new escrow record) ...
        assertEquals(KeychainJoin.Page.Failed(KeychainJoin.Why.NOT_ALLOWED), f.join.begin())
        // ... Messages in iCloud is simply on again.
        assertTrue(f.turnOnAgain())
        assertEquals(AccountFlag.HISTORY_ON, rig.files.readFlag().history)
        assertTrue(join.calls.isEmpty())
        f.cloud.stop()
    }

    @Test
    fun aSignOutStopsEverything() = runBlocking {
        signedIn()
        val f = features()
        f.focusRequest(listOf(bob), emptyList())
        f.stopForSignOut()
        assertTrue("statusUnsubscribe" in bridge.calls)
        assertFalse(rig.account.inClique)
    }

    @Test
    fun withTheAccountTheStartReadsTheKeychainAndImportsOnlyWhenOn() = runBlocking {
        signedIn()
        val f = features()
        f.sessionUp()
        assertEquals(listOf("isInClique"), bridge.calls)
        assertTrue(rig.account.state.value.clique)
        assertFalse(rig.account.state.value.history)
        assertNotNull(f.join.begin())
    }
}
