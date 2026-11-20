package com.lightdecoder.imessage.direct

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.IconChangeMessage
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MmcsFile
import uniffi.rust_bridge.PushChatMessage

/**
 * A group's photo through the iMessage library: kept per group with the group's version (an older
 * one ignored), its picture fetched from Apple (the library faked), decoded no larger than needed
 * and cut square; and the group's version a change from here goes as.
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

    // --- fetched from Apple (the library faked) ---------------------------------------------------------

    private class FakeDownloads(private val bytes: ByteArray?, private val fail: Exception? = null) : IncomingGroupPhotos.Natives {
        val asked = ArrayList<Pair<MmcsFile, Long>>()

        override suspend fun download(file: MmcsFile, path: String, maxBytes: Long) {
            asked += file to maxBytes
            fail?.let { throw it }
            File(path).writeBytes(bytes ?: ByteArray(0))
        }
    }

    private fun iconChange(id: String, sender: String?, file: MmcsFile?, version: ULong, guid: String? = "G-7", unverified: Boolean = false) =
        MessageInst(
            id, sender, ConversationData(listOf(me, bob, carol, dave), "Trip", guid, null),
            PushChatMessage.IconChange(IconChangeMessage(file, version)), 1_700_000_005_000UL, null, false, unverified, null,
        )

    private fun receive(i: MessageInst, natives: FakeDownloads) =
        runBlocking { IncomingGroupPhotos(context, natives).receive(i, setOf(me), Handles::display, DirectTexts(context)) }

    private val photoFile = MmcsFile(byteArrayOf(1), "OBJ", "https://mmcs.example/obj", byteArrayOf(2), 5_000UL)

    @Test
    fun aPhotoIsFetchedCutSquareAndWrittenForTheMainProcess() {
        val natives = FakeDownloads(jpeg(800, 400))
        val u = receive(iconChange("IC1", bob, photoFile, 4UL), natives) as DirectArchive.Update.GroupPhoto
        assertEquals("OBJ", natives.asked.single().first.`object`)
        assertEquals(IncomingGroupPhotos.MAX_DOWNLOAD_BYTES, natives.asked.single().second)
        assertEquals("imsg:group:G-7", u.conversationId)
        assertEquals(4L, u.version)
        val file = File(u.path!!)
        assertTrue("where group photos are kept", GroupPhotos.isOurs(context, file))
        val bitmap = BitmapFactory.decodeFile(file.path)!!
        assertEquals(bitmap.width, bitmap.height)
        assertEquals("+15550000002 changed the group photo.", u.row.message.text)
        // Nothing is left behind but the picture.
        assertEquals(listOf(file.name), GroupPhotos.dir(context).list()!!.toList())
        // As it crosses to the main process.
        val wired = DirectWire.decode(JSONObject(DirectWire.encode(u).toString())) as DirectArchive.Update.GroupPhoto
        assertEquals(u.path, wired.path)
        assertEquals(4L, wired.version)
    }

    @Test
    fun aPhotoTakenOffOrOneNobodyCanTakeIsNotFetched() {
        val natives = FakeDownloads(jpeg(10, 10))
        val removed = receive(iconChange("IC2", carol, null, 5UL), natives) as DirectArchive.Update.GroupPhoto
        assertNull(removed.path)
        assertEquals("+15550000003 removed the group photo.", removed.row.message.text)
        assertEquals(DirectArchive.Update.Ignore, receive(iconChange("IC3", bob, photoFile, 6UL, unverified = true), natives))
        assertEquals(DirectArchive.Update.Ignore, receive(iconChange("IC4", null, photoFile, 6UL), natives))
        assertEquals(DirectArchive.Update.Ignore, receive(iconChange("IC5", bob, photoFile, 6UL, guid = null), natives))
        assertTrue(natives.asked.isEmpty())
    }

    @Test
    fun aPhotoTooLargeUnreadableOrNotFetchedLeavesTheRowAlone() {
        // Said to be larger than it may be: not fetched.
        val big = FakeDownloads(jpeg(10, 10))
        val tooBig = receive(iconChange("IC6", bob, photoFile.copy(size = (IncomingGroupPhotos.MAX_DOWNLOAD_BYTES + 1).toULong()), 7UL), big)
        tooBig as DirectArchive.Update.Upsert
        assertEquals("+15550000002 changed the group photo.", tooBig.message.text)
        assertEquals(7L, tooBig.groupVersion)
        assertTrue(big.asked.isEmpty())
        // Stopped for growing past it, or not fetched at all: the row alone.
        for (fail in listOf(DirectEngine.TooLarge("stopped"), DirectEngine.ImessageError("no network"))) {
            val u = receive(iconChange("IC7", bob, photoFile, 8UL), FakeDownloads(null, fail))
            assertTrue(u.toString(), u is DirectArchive.Update.Upsert)
        }
        // Something that is no picture: the row alone, and nothing left behind.
        val garbage = receive(iconChange("IC8", bob, photoFile, 9UL), FakeDownloads("not a picture".toByteArray()))
        assertTrue(garbage is DirectArchive.Update.Upsert)
        assertTrue(GroupPhotos.dir(context).list().orEmpty().isEmpty())
    }

    // --- the group's version, and a chat's own read receipts ---------------------------------------

    @Test
    fun aChangeFromHereGoesAsTheVersionAfterTheHighestSeen() {
        val versions = GroupVersions(context)
        val id = "imsg:group:V-${System.nanoTime()}"
        assertNull(versions.of(id))
        // Never seen one: 0, as OpenBubbles starts.
        assertEquals(0L, versions.next(id))
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
        assertEquals(0L, versions.next(id))
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
