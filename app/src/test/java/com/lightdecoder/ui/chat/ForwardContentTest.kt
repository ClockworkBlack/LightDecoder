package com.lightdecoder.ui.chat

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MediaRow
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.imessage.direct.IMessageExtra
import com.lightdecoder.ui.share.SharedContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** "Forward": what of a message is forwarded, and how its files are handed to the composer. */
@RunWith(AndroidJUnit4::class)
class ForwardContentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun part(id: String, mime: String = "image/jpeg") = MediaRow(
        messageId = "m", partId = id, mediaId = "M/$id", key = ByteArray(0), thumbMediaId = "", thumbKey = ByteArray(0), mime = mime,
        fileName = "$id.jpg", size = 10, width = 0, height = 0, inlineData = ByteArray(0), needsFullSize = false, localPath = "",
        thumbPath = "", fullSizeRequested = false, stickerChecked = false,
    )

    private fun row(
        id: String = "imsg:M1",
        text: String = "hello",
        direction: String = "in",
        status: String = "INCOMING_COMPLETE",
        state: String = LocalState.SYNCED,
        media: List<MediaRow> = emptyList(),
        extra: IMessageExtra? = null,
    ) = MessageRow(
        id = id, conversationId = "c", tmpId = "", senderId = "p", senderName = "", isOutgoing = direction == "out", timestampMs = 1,
        direction = direction, status = status, statusCode = 0, errorText = "", transport = "IMESSAGE", text = text, subject = "",
        replyToId = "", reactions = emptyList(), localState = state, media = media, updatedAt = 0, extra = extra?.toJson().orEmpty(),
    )

    @Test
    fun textAndMediaAreForwardable() {
        assertTrue(ForwardContent.forwardable(row()))
        assertEquals("hello", ForwardContent.text(row()))
        val pictures = row(text = "", media = listOf(part("p1"), part("p2")))
        assertTrue(ForwardContent.forwardable(pictures))
        assertEquals(listOf("p1", "p2"), ForwardContent.parts(pictures).map { it.partId })
        // Invisible ink: its text, as plain text.
        assertEquals("secret", ForwardContent.text(row(text = "secret", extra = IMessageExtra(effect = IMessageExtra.INVISIBLE_INK))))
        // A photo stack: all of it, as many as a share may hold.
        assertEquals(SharedContent.MAX_STREAMS, ForwardContent.parts(row(media = (1..15).map { part("p$it") })).size)
    }

    @Test
    fun appCardsPollsCallsSystemRowsFailedAndLocalRowsAreNot() {
        assertFalse(ForwardContent.forwardable(row(extra = IMessageExtra(app = IMessageExtra.App(name = "GamePigeon", url = "data:x")))))
        assertFalse(ForwardContent.forwardable(row(extra = IMessageExtra(app = IMessageExtra.App(name = "Polls", url = "https://example.com/poll")))))
        assertFalse(ForwardContent.forwardable(row(id = "ftcall:G", extra = IMessageExtra(call = IMessageExtra.CallInfo("G", false, false, "missed")))))
        assertFalse(ForwardContent.forwardable(row(direction = "system")))
        assertFalse(ForwardContent.forwardable(row(id = "tmp:t1", direction = "out")))
        assertFalse(ForwardContent.forwardable(row(direction = "out", status = "OUTGOING_FAILED")))
        assertFalse(ForwardContent.forwardable(row(direction = "out", state = LocalState.FAILED)))
        assertFalse(ForwardContent.forwardable(row(text = "  ")))
    }

    @Test
    fun aLivePhotoForwardsItsStillOnly() {
        val live = row(text = "", media = listOf(part("p1"), part("p2", "video/quicktime")), extra = IMessageExtra(live = mapOf("p1" to "p2")))
        assertEquals(listOf("p1"), ForwardContent.parts(live).map { it.partId })
    }

    @Test
    fun theCopiesAreOwnCopiesWithinTheLimits() {
        // FileProvider keeps the first test's folders for good; each test has its own.
        runCatching {
            val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
            (cache.get(null) as MutableMap<*, *>).clear()
        }
        val dir = File(context.cacheDir, "forward-test").apply { deleteRecursively(); mkdirs() }
        val photo = File(dir, "abc").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val empty = File(dir, "empty.jpg").apply { writeBytes(ByteArray(0)) }
        val missing = File(dir, "gone.jpg")
        val copies = SharedContent.copyOwn(context, listOf(photo, missing, empty), listOf("image/jpeg", "image/jpeg", "image/jpeg"))
        assertEquals(1, copies.size)
        assertTrue(SharedContent.isOwnCopy(context, copies.single()))
        context.contentResolver.openInputStream(copies.single())!!.use { assertEquals(listOf<Byte>(1, 2, 3), it.readBytes().toList()) }
        // At most as many as a share.
        val many = (1..15).map { File(dir, "f$it.png").apply { writeBytes(byteArrayOf(9)) } }
        assertEquals(SharedContent.MAX_STREAMS, SharedContent.copyOwn(context, many).size)
        // Not some other file of the app's.
        assertFalse(SharedContent.isOwnCopy(context, Uri.fromFile(photo)))
    }

    @Test
    fun forwardAndTapbackScreensAreNotExported() {
        val android = "http://schemas.android.com/apk/res/android"
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val application = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml")).getElementsByTagName("application").item(0) as Element
        val nodes = application.childNodes
        val components = (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
        for (name in listOf(".ui.share.ForwardActivity", ".ui.chat.TapbackActivity")) {
            val c = components.single { it.getAttributeNS(android, "name") == name }
            assertEquals(name, "false", c.getAttributeNS(android, "exported"))
            assertEquals(name, 0, c.getElementsByTagName("intent-filter").length)
        }
        val tapback = components.single { it.getAttributeNS(android, "name") == ".ui.chat.TapbackActivity" }
        assertEquals("false", tapback.getAttributeNS(android, "showWhenLocked"))
        assertEquals("true", tapback.getAttributeNS(android, "excludeFromRecents"))
        assertEquals("", tapback.getAttributeNS(android, "taskAffinity"))
        assertTrue(tapback.hasAttributeNS(android, "taskAffinity"))
        // The exported share screen takes no forward of any kind.
        val share = File("src/main/java/com/lightdecoder/ui/share/ShareActivity.kt").readText()
        assertFalse(share.contains("ForwardActivity") || share.contains("forward", ignoreCase = true) && share.contains("EXTRA_FILES"))
    }
}
