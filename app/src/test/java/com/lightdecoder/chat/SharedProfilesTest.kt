package com.lightdecoder.chat

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.imessage.direct.DirectPaths
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Names and photos people share (B6.1) in the main process ([SharedProfiles]), and other people's
 * Focus status there ([FocusStates]): taken only for a chat that is here, within their limits; names
 * cleaned and never put over a name the chat has; everything gone with a sign-out.
 */
@RunWith(AndroidJUnit4::class)
class SharedProfilesTest {

    private val dir: File = Files.createTempDirectory("ld-profiles").toFile()
    private val profiles = SharedProfiles(dir)
    private val bob = "tel:+15550000002"
    private val now = 1_800_000_000_000L

    @Test
    fun withoutAChatNothingIsFetched() {
        assertEquals(SharedProfileRules.Decision.NO_CHAT, profiles.take(bob, "r1", chatExists = false, now = now))
        assertEquals(SharedProfileRules.Decision.FETCH, profiles.take(bob, "r1", chatExists = true, now = now))
    }

    @Test
    fun theSameRecordIsNotFetchedTwiceAndEachPersonThreeTimesADayAtMost() {
        assertEquals(SharedProfileRules.Decision.FETCH, profiles.take(bob, "r1", true, now))
        assertEquals(SharedProfileRules.Decision.REPEAT, profiles.take(bob, "r1", true, now + 1))
        assertEquals(SharedProfileRules.Decision.FETCH, profiles.take(bob, "r2", true, now + 2))
        assertEquals(SharedProfileRules.Decision.FETCH, profiles.take(bob, "r3", true, now + 3))
        assertEquals(SharedProfileRules.Decision.LIMITED, profiles.take(bob, "r4", true, now + 4))
        // A day later, again.
        assertEquals(SharedProfileRules.Decision.FETCH, profiles.take(bob, "r5", true, now + SharedProfileRules.DAY_MS + 5))
    }

    @Test
    fun twentyADayInAll() {
        val decisions = (1..25).map { i -> profiles.take("tel:+1555000%04d".format(i), "rec$i", true, now + i) }
        assertEquals(SharedProfileRules.GLOBAL, decisions.count { it == SharedProfileRules.Decision.FETCH })
        assertEquals(SharedProfileRules.Decision.LIMITED, decisions.last())
    }

    @Test
    fun namesAreCleanedOfWhatTurnsOrHidesText() {
        assertEquals("Bob Example", SharedNames.clean("  Bob‮ ​⁦Example\n\u0000 "))
        assertEquals("Mom", SharedNames.clean("M‍o m"))
        assertEquals(SharedNames.MAX_CHARS, SharedNames.clean("a".repeat(500)).length)
        // Never half of a character pair at the end.
        val emoji = "😀".repeat(40)
        val cut = SharedNames.clean(emoji)
        assertFalse(Character.isHighSurrogate(cut.last()))
        assertEquals("", SharedNames.clean("‮​"))
        assertEquals("Bob Example", SharedNames.shown("", "Bob", "Example"))
    }

    @Test
    fun aSharedNameNeverReplacesOneTheChatHas() {
        assertEquals("Mom", SharedProfileRules.title("Mom", bob, "Bank"))
        assertEquals("Bank", SharedProfileRules.title("+15550000002", bob, "Bank"))
        assertEquals("Bank", SharedProfileRules.title("", bob, "Bank"))
        assertEquals("+15550000002", SharedProfileRules.title("+15550000002", bob, null))
    }

    @Test
    fun anOfferIsTakenOnlyWhenTheUserSaysUpdate() {
        val photo = File(dir, "incoming-x.img")
        FileOutputStream(photo).use { Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertNotNull(profiles.offered("imsg:$bob", bob, "r1", "Bob‮", photo, now))
        assertFalse("the fetched file is not kept as it came", photo.exists())
        assertEquals("Bob", profiles.offerFor("imsg:$bob")!!.name)
        assertNull(profiles.nameFor(bob))
        assertTrue(profiles.accept("imsg:$bob"))
        assertEquals("Bob", profiles.nameFor(bob))
        assertNull(profiles.offerFor("imsg:$bob"))
        // "Not Now" drops an offer and keeps nothing.
        profiles.offered("imsg:tel:+15550000003", "tel:+15550000003", "r2", "Carol", null, now)
        profiles.dismiss("imsg:tel:+15550000003")
        assertNull(profiles.nameFor("tel:+15550000003"))
        // Kept across a restart of the process.
        assertEquals("Bob", SharedProfiles(dir).nameFor(bob))
    }

    @Test
    fun aChatDeletedOrASignOutRemovesWhatWasKept() {
        profiles.offered("imsg:$bob", bob, "r1", "Bob", null, now)
        profiles.accept("imsg:$bob")
        profiles.forget("imsg:$bob", bob)
        assertNull(profiles.nameFor(bob))
        profiles.offered("imsg:$bob", bob, "r2", "Bob", null, now)
        profiles.answerShare("imsg:$bob")
        profiles.clear()
        assertTrue(profiles.isEmpty())
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun anOfferThatCameToNothingStillLeavesNothingAfterASignOut() {
        // Fetched, but nothing to offer (no name, no photo): only the limit's record of it is kept.
        assertEquals(SharedProfileRules.Decision.FETCH, profiles.take(bob, "rec-1", chatExists = true, now = now))
        assertNull(profiles.offered("imsg:$bob", bob, "rec-1", "", null, now))
        val kept = File(dir, "profiles.json")
        assertTrue(kept.isFile)
        assertFalse("their address and the record are still on disk: not empty", profiles.isEmpty())
        // The sign-out, as DirectLink makes it.
        if (!profiles.isEmpty()) profiles.clear()
        assertFalse(kept.exists())
        assertTrue(profiles.isEmpty())
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aChatDeletedTakesTheirAddressOutOfTheLimitsRecordToo() {
        profiles.take(bob, "rec-1", chatExists = true, now = now)
        profiles.offered("imsg:$bob", bob, "rec-1", "Bob", null, now)
        profiles.dismiss("imsg:$bob")
        assertTrue(File(dir, "profiles.json").readText().contains("5550000002"))
        profiles.forget("imsg:$bob", bob)
        assertFalse(File(dir, "profiles.json").readText().contains("5550000002"))
    }

    @Test
    fun aPhotoIsFetchedOnlyToANewFileOfTheirFolder() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fresh = SharedProfiles.get(context).incomingFile()
        assertTrue(DirectPaths.isProfileImage(context, fresh.path))
        assertFalse(DirectPaths.isProfileImage(context, File(SharedProfiles.dirOf(context), "photo-1.jpg").path))
        assertFalse(DirectPaths.isProfileImage(context, File(context.filesDir, "imessage/state/incoming-1").path))
        assertFalse(DirectPaths.isProfileImage(context, File(SharedProfiles.dirOf(context), "../incoming-1").path))
        SharedProfiles.dirOf(context).mkdirs()
        fresh.writeText("x")
        assertFalse("never over a file that is there", DirectPaths.isProfileImage(context, fresh.path))
        fresh.delete()
    }

    // --- Focus ---------------------------------------------------------------------------------------

    private val focus = FocusStates(File(dir, "focus.json"))

    @Test
    fun focusIsTakenOnlyForSomeoneAskedAbout() {
        assertFalse(focus.update(bob, true, now))
        focus.subscribed(listOf(bob))
        assertTrue(focus.update(bob, true, now))
        assertTrue(focus.silenced("+15550000002"))
        // An older word does not undo a newer one.
        assertFalse(focus.update(bob, false, now - 1))
        assertTrue(focus.silenced(bob))
    }

    @Test
    fun deliveredQuietlyOnlyWhileSilencedAndNotJustAfterNotifyAnyway() {
        focus.subscribed(listOf(bob))
        assertFalse(focus.deliveredQuietly(bob, now))
        focus.update(bob, true, now)
        assertTrue(focus.deliveredQuietly(bob, now))
        focus.notifiedAnyway(bob, now)
        assertFalse(focus.deliveredQuietly(bob, now + 60_000))
        assertTrue(focus.deliveredQuietly(bob, now + FocusStates.NOTIFY_ANYWAY_MS + 1))
        focus.clear()
        assertFalse(focus.silenced(bob))
    }
}
