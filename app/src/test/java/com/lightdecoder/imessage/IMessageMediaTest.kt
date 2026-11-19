package com.lightdecoder.imessage

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.ChatService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File

/** iMessage pictures: OpenBubbles' "Image" becomes a picture slot, filled from its Auto-save copy. */
@RunWith(AndroidJUnit4::class)
class IMessageMediaTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: ArchiveDb
    private lateinit var repo: ArchiveRepository
    private lateinit var manager: IMessageManager
    private val chat = "5D2B5A4C-1F0E-4C3B-9A7D-2E6F1B0C9D11"
    private var saved = listOf<IMessageMedia.Saved>()
    private val bytes = HashMap<Long, ByteArray>()

    @Before
    fun setUp() {
        app.deleteDatabase(DB)
        db = ArchiveDb(app, DB)
        repo = ArchiveRepository.forTesting(db, ChatService.IMESSAGE)
        ArchiveRepository.installForTesting(repo)
        manager = IMessageManager.installForTesting(app, repo)
        IMessagePrefs.setEnabled(app, true)
        IMessagePrefs.setUsedMedia(app, emptySet())
        IMessageLog.clear(app)
        IMessageMedia.source = { _, from, to -> saved.filter { it.addedSec in from..to } }
        IMessageMedia.open = { _, s -> bytes[s.id]?.let(::ByteArrayInputStream) }
    }

    @After
    fun tearDown() {
        IMessageMedia.resetForTesting()
        IMessagePrefs.setEnabled(app, false)
        IMessageManager.resetForTesting()
        ArchiveRepository.resetForTesting()
        db.close()
    }

    private fun allow() = shadowOf(app).grantPermissions(*IMessageMedia.permissions())

    private fun record(vararg lines: Pair<String, Long>) = manager.record(
        CapturedIMessageChat(chat, "m1", "Nina", false, "+15551234567", lines.map { (text, ts) -> CapturedIMessage("Nina", "tel:+15551234567", false, text, ts) }),
        live = false,
    )

    private fun file(id: Long, mime: String, addedSec: Long): IMessageMedia.Saved {
        bytes[id] = "picture-$id".toByteArray()
        return IMessageMedia.Saved(id, Uri.parse("content://media/external/images/media/$id"), mime, addedSec, 10, 4032, 3024)
    }

    @Test
    fun openBubblesWordsForPicturesAreRecognised() {
        assertEquals(listOf(IMessageCapture.IMAGE), IMessageCapture.attachmentKinds("Image"))
        assertEquals(listOf(IMessageCapture.IMAGE), IMessageCapture.attachmentKinds("Attachment: 1 image"))
        assertEquals(
            listOf(IMessageCapture.IMAGE, IMessageCapture.IMAGE, IMessageCapture.VIDEO),
            IMessageCapture.attachmentKinds("Attachments: 2 images, 1 movie"),
        )
        assertEquals(listOf(IMessageCapture.VIDEO), IMessageCapture.attachmentKinds("Video"))
        assertNull("a PDF is not a picture", IMessageCapture.attachmentKinds("Attachment: 1 PDF"))
        assertNull("words are words", IMessageCapture.attachmentKinds("Image of the day was great"))
        assertNull(IMessageCapture.attachmentKinds("Did you get mail from mom?"))
    }

    @Test
    fun aPictureLineIsStoredAsAPictureSlot() {
        record("Look" to 1_000_000L, "Image" to 1_001_000L)
        val rows = repo.messages(chat)
        assertEquals("Look", rows[0].text)
        val picture = rows[1]
        assertEquals("", picture.text)
        assertEquals(IMessageCapture.IMAGE, picture.media.single().mime)
        assertEquals("", picture.media.single().localPath)
    }

    @Test
    fun aSlotIsFilledWithOpenBubblesSavedCopyAtFullQuality() {
        allow()
        val t = 1_700_000_000_000L
        record("Attachments: 1 image, 1 movie" to t)
        saved = listOf(
            file(1, "image/jpeg", t / 1000 - 3_600), // an hour before: someone else's, long ago
            file(2, "image/heic", t / 1000 + 4),
            file(3, "video/quicktime", t / 1000 + 6),
        )
        assertEquals(2, IMessageMedia.fill(app, nowMs = t + 30_000))
        val parts = repo.messages(chat).single().media.sortedBy { it.partId }
        assertEquals("image/heic", parts[0].mime)
        assertTrue(File(parts[0].localPath).readBytes().contentEquals("picture-2".toByteArray()))
        assertEquals(4032, parts[0].width)
        assertEquals("video/quicktime", parts[1].mime)
        assertTrue(File(parts[1].localPath).isFile)
        // Each saved file is used once: nothing left to give.
        assertEquals(0, IMessageMedia.fill(app, nowMs = t + 60_000))
        val log = IMessageLog.read(app).joinToString("\n")
        assertTrue(log, log.contains("picture from OpenBubbles' Auto-save shown (image/heic"))
        assertFalse("nothing of who", log.contains("Nina") || log.contains("5551234567"))
    }

    @Test
    fun withoutPhotoAccessNothingIsReadAndItSaysWhy() {
        val t = 1_700_000_000_000L
        record("Image" to t)
        saved = listOf(file(2, "image/jpeg", t / 1000 + 4))
        assertEquals(0, IMessageMedia.fill(app, nowMs = t + 30_000))
        assertEquals("", repo.messages(chat).single().media.single().localPath)
        assertTrue(IMessageLog.read(app).any { it.contains("may not see photos and videos yet") })
    }

    @Test
    fun aPictureThatNeverCameSaysAutoSaveMayBeOff() {
        allow()
        val t = 1_700_000_000_000L
        record("Image" to t)
        assertEquals(0, IMessageMedia.fill(app, nowMs = t + 10 * 60_000))
        assertTrue(IMessageLog.read(app).any { it.contains("Auto-save Attachments on?") })
    }

    private companion object {
        const val DB = "imessage-media-test.db"
    }
}
