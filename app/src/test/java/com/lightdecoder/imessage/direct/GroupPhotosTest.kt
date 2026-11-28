package com.lightdecoder.imessage.direct

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A group's photo through the iMessage library: kept per group with the group's version (an older
 * one ignored, the user's own always shown), decoded no larger than needed and cut square, its new
 * pictures taken only by their own names and swept away when left; and the group's version a change
 * from here goes as, and one from someone else's change taken only within reach of what is known.
 */
@RunWith(AndroidJUnit4::class)
class GroupPhotosTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val me = "tel:+15550000001"
    private val bob = "tel:+15550000002"
    private val carol = "tel:+15550000003"
    private val dave = "tel:+15550000004"

    @Before
    fun setUp() {
        GroupPhotos.dir(context).deleteRecursively()
    }

    @After
    fun tearDown() {
        GroupPhotos.dir(context).deleteRecursively()
    }

    private fun jpeg(width: Int, height: Int, paint: (Bitmap) -> Unit = { it.eraseColor(Color.RED) }): ByteArray {
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also(paint)
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
    }

    private fun incoming(bytes: ByteArray = jpeg(40, 40)): File = GroupPhotos.incomingFile(context).apply { writeBytes(bytes) }

    // --- kept per group, with its version ----------------------------------------------------------

    @Test
    fun aNewerPhotoReplacesTheOldOneAndAnOlderOrTheSameIsIgnored() {
        val store = GroupPhotos(context)
        val id = "imsg:group:G-1"
        assertEquals(GroupPhotos.Photo(null, 0, known = false), store.of(id))
        val first = incoming()
        assertEquals(GroupPhotos.Applied.APPLIED, store.apply(id, first, 3))
        val kept = store.pictureOf(id)!!
        assertFalse("moved into place", first.exists())
        assertTrue(GroupPhotos.isOurs(context, kept))
        assertEquals(3L, store.of(id).version)
        assertEquals(mapOf(id to kept), store.all())
        // The same version again (Apple's journal after a restart), or an older one: nothing changes, and the picture goes.
        val again = incoming()
        assertEquals(GroupPhotos.Applied.SAME, store.apply(id, again, 3))
        assertFalse(again.exists())
        val older = incoming()
        assertEquals(GroupPhotos.Applied.OLDER, store.apply(id, older, 2))
        assertFalse(older.exists())
        assertEquals(kept, store.pictureOf(id))
        // A newer one replaces it; the old picture goes.
        assertEquals(GroupPhotos.Applied.APPLIED, store.apply(id, incoming(), 4))
        val newer = store.pictureOf(id)!!
        assertFalse(newer == kept)
        assertFalse(kept.exists())
        // Taken off: kept as a version without a picture, so an older photo never comes back.
        assertEquals(GroupPhotos.Applied.APPLIED, store.apply(id, null, 5))
        assertNull(store.pictureOf(id))
        assertFalse(newer.exists())
        assertEquals(GroupPhotos.Photo(null, 5, known = true), store.of(id))
        assertEquals(GroupPhotos.Applied.OLDER, store.apply(id, incoming(), 4))
        assertNull(store.pictureOf(id))
        assertTrue(store.all().isEmpty())
        // Each group its own.
        assertEquals(GroupPhotos.Applied.APPLIED, store.apply("imsg:group:G-2", incoming(), 0))
        assertNotNull(store.pictureOf("imsg:group:G-2"))
        assertNull(store.pictureOf(id))
        store.forget("imsg:group:G-2")
        assertNull(store.pictureOf("imsg:group:G-2"))
    }

    @Test
    fun onlyAFileWhereGroupPhotosAreKeptIsOurs() {
        assertTrue(GroupPhotos.isOurs(context, GroupPhotos.incomingFile(context)))
        assertFalse(GroupPhotos.isOurs(context, File(context.filesDir, "imessage/state/keys.plist")))
        assertFalse(GroupPhotos.isOurs(context, File(GroupPhotos.dir(context), "../backgrounds/x.jpg")))
        assertFalse(GroupPhotos.isOurs(context, File(context.cacheDir, "x.jpg")))
    }

    // --- pictures ---------------------------------------------------------------------------------------

    @Test
    fun aPictureIsCutToItsCentredSquareAsAJpeg() {
        // Wide: blue edges, a red middle that is the square.
        val wide = jpeg(900, 300) { b ->
            b.eraseColor(Color.BLUE)
            android.graphics.Canvas(b).drawRect(300f, 0f, 600f, 300f, android.graphics.Paint().apply { color = Color.RED })
        }
        val out = File(context.cacheDir, "square.jpg")
        assertTrue(GroupPhotoImages.write(wide, out))
        val bitmap = BitmapFactory.decodeFile(out.path)!!
        assertEquals(GroupPhotoImages.SIDE, bitmap.width)
        assertEquals(GroupPhotoImages.SIDE, bitmap.height)
        for ((x, y) in listOf(10 to 10, bitmap.width / 2 to bitmap.height / 2, bitmap.width - 10 to bitmap.height - 10)) {
            val px = bitmap.getPixel(x, y)
            assertTrue("red at $x,$y", Color.red(px) > 200 && Color.blue(px) < 60)
        }
        // A small one is made as large; something that is no picture is nothing (nothing written).
        assertTrue(GroupPhotoImages.write(jpeg(20, 30), out))
        assertEquals(GroupPhotoImages.SIDE, BitmapFactory.decodeFile(out.path)!!.width)
        val none = File(context.cacheDir, "none.jpg").apply { delete() }
        assertFalse(GroupPhotoImages.write("not a picture".toByteArray(), none))
        assertFalse(none.exists())
    }

    @Test
    fun aPictureIsDecodedNoLargerThanNeeded() {
        // A 48 MP photo: subsampled to a little more than the square needs.
        assertEquals(8, GroupPhotoImages.sampleFor(8000, 6000, GroupPhotoImages.SIDE))
        assertEquals(1, GroupPhotoImages.sampleFor(570, 570, GroupPhotoImages.SIDE))
        assertEquals(1, GroupPhotoImages.sampleFor(100, 100, GroupPhotoImages.SIDE))
        // A strip far longer than it is wide: subsampled for memory all the same.
        val sample = GroupPhotoImages.sampleFor(100, 200_000, GroupPhotoImages.SIDE)
        assertTrue(sample >= 4)
        assertTrue((100L / sample) * (200_000L / sample) <= 4_000_000L)
    }

    // --- new pictures: which a request may name, and those left behind -----------------------------------

    @Test
    fun aNewPictureIsTakenOnlyByItsOwnNameWhereGroupPhotosAreKept() {
        val incoming = GroupPhotos.incomingFile(context)
        val outgoing = GroupPhotos.incomingFile(context, GroupPhotos.OUTGOING)
        assertTrue(GroupPhotos.isNew(context, incoming, GroupPhotos.INCOMING))
        assertTrue(GroupPhotos.isNew(context, outgoing, GroupPhotos.OUTGOING))
        // Each only as what it is.
        assertFalse(GroupPhotos.isNew(context, incoming, GroupPhotos.OUTGOING))
        assertFalse(GroupPhotos.isNew(context, outgoing, GroupPhotos.INCOMING))
        // Not the index, nor a group's photo kept there, nor anything elsewhere or named otherwise.
        val store = GroupPhotos(context)
        store.apply("imsg:group:G-1", GroupPhotos.incomingFile(context).apply { writeBytes(jpeg(10, 10)) }, 3)
        val kept = store.pictureOf("imsg:group:G-1")!!
        for (f in listOf(
            File(GroupPhotos.dir(context), "index.json"), kept, File(GroupPhotos.dir(context), "incoming-x.jpg"),
            File(GroupPhotos.dir(context), "incoming-${java.util.UUID.randomUUID()}.png"),
            File(context.cacheDir, "incoming-${java.util.UUID.randomUUID()}.jpg"),
            File(GroupPhotos.dir(context), "../backgrounds/incoming-${java.util.UUID.randomUUID()}.jpg"),
        )) {
            assertFalse(f.path, GroupPhotos.isNew(context, f, GroupPhotos.INCOMING))
            assertFalse(f.path, GroupPhotos.isNew(context, f, GroupPhotos.OUTGOING))
        }
    }

    @Test
    fun newPicturesLeftByWorkThatNeverEndedAreSweptAway() {
        val now = System.currentTimeMillis()
        val store = GroupPhotos(context)
        store.apply("imsg:group:G-1", incoming(), 3)
        val kept = store.pictureOf("imsg:group:G-1")!!
        val index = File(GroupPhotos.dir(context), "index.json")
        val old = listOf(
            GroupPhotos.incomingFile(context), GroupPhotos.incomingFile(context, GroupPhotos.PICKED), GroupPhotos.incomingFile(context, GroupPhotos.OUTGOING),
            File(GroupPhotos.dir(context), "photo-${java.util.UUID.randomUUID()}.part"),
        ).onEach { it.writeBytes(byteArrayOf(1)) }
        val fresh = GroupPhotos.incomingFile(context).apply { writeBytes(byteArrayOf(2)) }
        for (f in old + kept + index) f.setLastModified(now - 2 * GroupPhotos.STALE_MS)
        assertEquals(old.size, GroupPhotos.sweep(context, now))
        assertTrue(old.none { it.exists() })
        // One still being worked on, a group's photo and the index stay, however old.
        assertTrue(fresh.exists())
        assertTrue(kept.exists())
        assertTrue(index.exists())
        assertEquals(kept, GroupPhotos(context).pictureOf("imsg:group:G-1"))
    }

    @Test
    fun theUsersOwnChangeIsThePhotoWhateverVersionTheGroupsIsAt() {
        val store = GroupPhotos(context)
        val id = "imsg:group:G-9"
        // Someone set it as of a version far ahead (before such were refused).
        assertEquals(GroupPhotos.Applied.APPLIED, store.apply(id, incoming(), Long.MAX_VALUE))
        val theirs = store.pictureOf(id)!!
        // Anyone else's next one is older: ignored.
        assertEquals(GroupPhotos.Applied.OLDER, store.apply(id, incoming(), 5))
        // The user's own, Apple having confirmed it: shown, kept as of the higher version.
        val mine = incoming(jpeg(40, 40) { it.eraseColor(Color.GREEN) })
        assertEquals(GroupPhotos.Applied.APPLIED, store.apply(id, mine, 5, own = true))
        assertFalse(mine.exists())
        // In theirs' place: the user's picture.
        val shown = BitmapFactory.decodeFile(store.pictureOf(id)!!.path)!!
        val px = shown.getPixel(shown.width / 2, shown.height / 2)
        assertTrue("the user's own", Color.green(px) > 180 && Color.red(px) < 90)
        assertEquals("one picture kept", 1, GroupPhotos.dir(context).list()!!.count { it.endsWith(".jpg") })
        assertTrue(theirs.path == store.pictureOf(id)!!.path || !theirs.exists())
        assertEquals(Long.MAX_VALUE, store.of(id).version)
        // Taken off by the user: none.
        assertEquals(GroupPhotos.Applied.APPLIED, store.apply(id, null, 6, own = true))
        assertNull(store.pictureOf(id))
        assertTrue(GroupPhotos.dir(context).list()!!.none { it.endsWith(".jpg") })
    }

    @Test
    fun aGroupsVersionFromSomeonesChangeIsTakenOnlyWithinReachOfWhatIsKnown() {
        // Nothing known: up to a thousand changes on.
        assertTrue(GroupVersions.plausible(0))
        assertTrue(GroupVersions.plausible(1_000))
        assertFalse(GroupVersions.plausible(1_001))
        assertFalse(GroupVersions.plausible(-1))
        // From the highest known, the group's or its photo's.
        assertTrue(GroupVersions.plausible(1_500, 700, null))
        assertTrue(GroupVersions.plausible(1_500, null, 600))
        assertFalse(GroupVersions.plausible(1_701, 700, 600))
        assertTrue(GroupVersions.plausible(3, 700, 600))
        // Past 2^32: none, unless one known is that high already (then a thousand on from it).
        assertFalse(GroupVersions.plausible(GroupVersions.MAX_PLAUSIBLE, GroupVersions.MAX_PLAUSIBLE - 1))
        assertTrue(GroupVersions.plausible(GroupVersions.MAX_PLAUSIBLE + 5, GroupVersions.MAX_PLAUSIBLE))
        assertFalse(GroupVersions.plausible(Long.MAX_VALUE, 3))
        assertTrue(GroupVersions.plausible(Long.MAX_VALUE, Long.MAX_VALUE - 10))
    }

    // --- the group's version, and a chat's own read receipts ---------------------------------------

    @Test
    fun aChangeFromHereGoesAsTheVersionAfterTheHighestSeen() {
        val versions = GroupVersions(context)
        val id = "imsg:group:V-${System.nanoTime()}"
        assertNull(versions.of(id))
        // Never seen one, in a group not made here: how often it changed is not known, so well past where it may be
        // (0, as OpenBubbles starts, is a change long past in any group that ever changed: iPhones leave it out).
        assertEquals(GroupVersions.UNKNOWN_FIRST, versions.next(id))
        // Made here: nothing changed in it yet, the first is 0.
        val made = "imsg:group:M-${System.nanoTime()}"
        versions.noteNew(made)
        assertNull(versions.of(made))
        assertEquals(0L, versions.next(made))
        versions.note(made, 0)
        assertEquals(1L, versions.next(made))
        versions.note(id, 4)
        assertEquals(5L, versions.next(id))
        // An older one says nothing new.
        versions.note(id, 2)
        assertEquals(4L, versions.of(id))
        versions.note(id, 9)
        assertEquals(10L, versions.next(id))
        versions.note(id, -1)
        assertEquals(9L, versions.of(id))
        // Never past a Long's end.
        versions.note(id, Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, versions.next(id))
        versions.forget(id)
        assertEquals(GroupVersions.UNKNOWN_FIRST, versions.next(id))
        assertEquals(0L, GroupVersions.nextAfter(null))
        assertEquals(1L, GroupVersions.nextAfter(0))
    }

    @Test
    fun aChatsOwnReadReceiptsChoiceWinsOverTheSetting() {
        val choices = ReadReceiptChoices(context)
        val id = "imsg:tel:+1555${System.nanoTime() % 10_000_000}"
        assertNull(choices.of(id))
        assertTrue(choices.effective(id, global = true))
        assertFalse(choices.effective(id, global = false))
        choices.set(id, true)
        assertEquals(true, choices.of(id))
        assertTrue(choices.effective(id, global = false))
        choices.set(id, false)
        assertFalse(choices.effective(id, global = true))
        choices.set(id, null)
        assertNull(choices.of(id))
    }

    @Test
    fun theGlobalReadReceiptsSettingIsReadInEitherProcess() {
        try {
            DirectPrefs.setSendReadReceipts(context, true)
            assertTrue(DirectPrefs.sendReadReceipts(context))
            // Kept in a file both processes read (no cache of another process's preferences in the way).
            assertEquals("1", File(context.filesDir, "imessage/read-receipts").readText())
            DirectPrefs.setSendReadReceipts(context, false)
            assertFalse(DirectPrefs.sendReadReceipts(context))
        } finally {
            DirectPrefs.setSendReadReceipts(context, false)
        }
    }

    @Test
    fun aChangeOfMembersNamesTheUserByTheAddressSentFromOrLeavesThemOut() {
        val mine = setOf(me, "mailto:me@example.com")
        // Staying: the others, then the user's address sent from, once.
        assertEquals(listOf(bob, carol, me), DirectMessages.newParticipants(listOf(bob, carol, me), me, me, mine))
        assertEquals(listOf(bob, carol, me), DirectMessages.newParticipants(listOf(me, bob, bob, carol), me, me, mine))
        // Named by another of the user's addresses: the one sent from.
        assertEquals(listOf(bob, carol, me), DirectMessages.newParticipants(listOf(bob, "mailto:me@example.com", carol), me, me, mine))
        // Without any: the user leaves.
        assertEquals(listOf(bob, carol), DirectMessages.newParticipants(listOf(bob, carol), me, me, mine))
        assertEquals(listOf(bob), DirectMessages.newParticipants(listOf(bob, ""), me, me, mine))
    }
}
