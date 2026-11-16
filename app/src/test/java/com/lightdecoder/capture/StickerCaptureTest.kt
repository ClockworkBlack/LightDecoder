package com.lightdecoder.capture

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.TestMedia
import com.lightdecoder.heif.HeifCodec
import com.lightdecoder.inbox.InboxItem
import com.lightdecoder.inbox.InboxLog
import com.lightdecoder.inbox.InboxPrefs
import com.lightdecoder.inbox.InboxStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The shared capture pipeline: what is kept, what is skipped, the log lines,
 * the alert, and that one picture caught by both sources is kept once.
 */
@RunWith(AndroidJUnit4::class)
class StickerCaptureTest {

    private lateinit var context: Context
    private lateinit var store: InboxStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = InboxStore.get(context)
        store.clear()
        InboxLog.clear(context)
        InboxPrefs.setCatchAllImages(context, false)
        InboxPrefs.setAlertsEnabled(context, true)
    }

    @Test
    fun savesAHeicStickerFromTheRelay() {
        assumeTrue("native decoder not available on this machine", HeifCodec.isAvailable)
        val bytes = TestMedia.bytes("still_alpha.heic")
        val id = InboxStore.idFor("relay", "conv-1", "msg-1", 0, "p1")

        assertEquals(StickerCapture.Outcome.Saved, StickerCapture.capture(context, bytes, meta(id, "image/heic", InboxItem.SOURCE_RELAY)))

        val item = store.list().single()
        assertEquals(id, item.id)
        assertEquals(InboxItem.SOURCE_RELAY, item.source)
        assertEquals(InboxStore.digestOf(bytes), item.digest)
        assertEquals(32, item.digest.length)
        assertEquals(TestMedia.WIDTH, item.width)
        assertEquals("heic", item.originalExt)
        assertTrue(store.originalFile(item).readBytes().contentEquals(bytes))
        assertTrue(store.pngFile(item).exists())
        assertEquals(
            "Relay: picture (image/heic, .heic, ${TestMedia.WIDTH}×${TestMedia.HEIGHT}): saved",
            InboxLog.read(context).first().substringAfter("  "),
        )
        val notifications = context.getSystemService(NotificationManager::class.java)
        assertEquals(1, shadowOf(notifications).allNotifications.size)
    }

    @Test
    fun samePictureFromBothSourcesIsKeptOnce() {
        assumeTrue("native decoder not available on this machine", HeifCodec.isAvailable)
        val bytes = TestMedia.bytes("anim_alpha.heics")
        val relayId = InboxStore.idFor("relay", "conv-1", "msg-1", 0, "p1")
        val notificationId = InboxStore.idFor("com.google.android.apps.messaging", "Sam", "Sam", 1_000, "content://x/1")

        assertEquals(StickerCapture.Outcome.Saved, StickerCapture.capture(context, bytes, meta(relayId, "", InboxItem.SOURCE_RELAY)))
        assertEquals(
            StickerCapture.Outcome.Duplicate,
            StickerCapture.capture(context, bytes, meta(notificationId, "image/heic", InboxItem.SOURCE_NOTIFICATION)),
        )
        assertEquals(StickerCapture.Outcome.Duplicate, StickerCapture.capture(context, bytes, meta(relayId, "", InboxItem.SOURCE_RELAY)))

        val item = store.list().single()
        assertTrue("animated", item.animated)
        assertEquals("heics", item.originalExt)
        assertEquals("image/heic-sequence", item.mime)
        assertTrue("the second id is remembered", store.contains(notificationId))
        assertEquals(
            "Picture (image/heic, .heics): already in the inbox",
            InboxLog.read(context).first().substringAfter("  "),
        )
    }

    @Test
    fun notificationLinesAreUnchanged() {
        assumeTrue("native decoder not available on this machine", HeifCodec.isAvailable)
        StickerCapture.capture(context, TestMedia.bytes("still_opaque.heic"), meta("n1", "image/heic", InboxItem.SOURCE_NOTIFICATION))
        assertEquals(
            "Picture (image/heic, .heic, ${TestMedia.WIDTH}×${TestMedia.HEIGHT}): saved",
            InboxLog.read(context).first().substringAfter("  "),
        )
        assertEquals(InboxItem.SOURCE_NOTIFICATION, store.list().single().source)
    }

    @Test
    fun skipsWhatIsNotAHeicPicture() {
        val text = "just some text".toByteArray()
        assertEquals(
            StickerCapture.Outcome.Skipped("not_picture"),
            StickerCapture.capture(context, text, meta("a", "", InboxItem.SOURCE_NOTIFICATION)),
        )
        assertEquals("Attachment (no type): not a picture, skipped", InboxLog.read(context).first().substringAfter("  "))

        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A) +
            ByteArray(16)
        assertEquals(
            StickerCapture.Outcome.Skipped("not_heic"),
            StickerCapture.capture(context, png, meta("b", "image/png", InboxItem.SOURCE_RELAY)),
        )
        assertEquals(
            "Relay: picture (image/png, .png): Messages can show it, skipped",
            InboxLog.read(context).first().substringAfter("  "),
        )
        assertEquals(StickerCapture.Outcome.Skipped("size"), StickerCapture.capture(context, ByteArray(0), meta("c", "", "relay")))
        assertTrue(store.list().isEmpty())
        assertTrue("skipped ids are remembered", store.contains("a") && store.contains("b"))
    }

    @Test
    fun undecodablePictureFails() {
        InboxPrefs.setCatchAllImages(context, true)
        val brokenPng = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(40)
        assertEquals(
            StickerCapture.Outcome.Failed("decode"),
            StickerCapture.capture(context, brokenPng, meta("d", "image/png", InboxItem.SOURCE_NOTIFICATION)),
        )
        assertTrue(store.list().isEmpty())
    }

    private fun meta(id: String, mime: String, source: String) = StickerCapture.Meta(
        id = id,
        sender = "Sam",
        title = "Sam",
        appPackage = "com.google.android.apps.messaging",
        timestampMs = 1_000,
        mime = mime,
        source = source,
    )
}
