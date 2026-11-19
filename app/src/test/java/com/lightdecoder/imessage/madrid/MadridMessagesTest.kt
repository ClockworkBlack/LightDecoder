package com.lightdecoder.imessage.madrid

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bluebubbles.messaging.MadridMessage
import com.lightdecoder.imessage.direct.IMessageExtra
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** An extension app's messages and the balloons stored with iMessages: the same fields, the app's own state as it was. */
@RunWith(AndroidJUnit4::class)
class MadridMessagesTest {

    private val ext = MadridApps.OPENPIGEON
    private val session = "0f8fad5b-d9cb-469f-a165-70867728950e"

    private val stored = IMessageExtra.App(
        name = "GamePigeon", appId = 1124197642L, bundleId = ext.bundleId, url = "data:?ver=52&data=a%2Bb", session = session,
        ldText = "8 Ball", isLive = true, imageTitle = "title", imageSubtitle = "subtitle", caption = "Your move",
        subcaption = "sub", secondarySubcaption = "second", tertiarySubcaption = "third", root = "imsg:ROOT",
    )

    @Test
    fun aStoredBalloonGoesToTheAppWithItsCaptionsRenamedAndNoPicture() {
        val m = MadridMessages.toMadridMessage("GUID1", stored)
        assertEquals("GUID1", m.messageGuid)
        assertEquals("8 Ball", m.ldText)
        assertEquals("data:?ver=52&data=a%2Bb", m.url)
        assertEquals(session, m.session)
        assertNull("the extension is never sent the picture", m.imageBase64)
        assertEquals("title", m.imageTitle)
        assertEquals("subtitle", m.imageSubtitle)
        assertEquals("Your move", m.caption)
        assertEquals("sub", m.subcaption)
        assertEquals("second", m.secondaryCaption)
        assertEquals("third", m.tertiaryCaption)
        assertTrue(m.isLive)
        // What the balloon does not have, the app gets as null.
        val bare = MadridMessages.toMadridMessage("GUID2", IMessageExtra.App(url = "data:x"))
        assertNull(bare.ldText)
        assertNull(bare.session)
        assertNull(bare.caption)
        assertNull(bare.secondaryCaption)
        assertFalse(bare.isLive)
    }

    @Test
    fun anAppsMessageIsStoredAsTheBalloonItCameFrom() {
        val m = MadridMessages.toMadridMessage("GUID1", stored)
        val back = MadridMessages.appOf(m, ext, session, "imsg:ROOT")
        assertEquals(stored, back)
    }

    @Test
    fun aMoveKeepsTheHandlesSessionWhateverItSays() {
        val m = MadridMessage().apply {
            messageGuid = "random"
            url = "data:?ver=52&data=z"
            session = "11111111-2222-3333-4444-555555555555"
            caption = "x".repeat(500)
            ldText = "8 Ball"
        }
        val app = MadridMessages.appOf(m, ext, session, "imsg:ROOT")
        assertEquals(session, app.session)
        assertEquals("imsg:ROOT", app.root)
        assertEquals("GamePigeon", app.name)
        assertEquals(1124197642L, app.appId)
        assertEquals(ext.bundleId, app.bundleId)
        assertEquals("data:?ver=52&data=z", app.url)
        assertEquals(MadridMessages.MAX_TEXT, app.caption.length)
        assertEquals("", app.subcaption)
        // Never half an emoji.
        m.caption = "x".repeat(MadridMessages.MAX_TEXT - 1) + "😀"
        assertEquals(MadridMessages.MAX_TEXT - 1, MadridMessages.appOf(m, ext, session).caption.length)
    }

    @Test
    fun aNewCardNeedsAnAddressAndAUuidSession() {
        val m = MadridMessage().apply { url = "data:x"; session = session() }
        assertNull(MadridMessages.problemWithCard(m))
        assertNotNull(MadridMessages.problemWithCard(MadridMessage().apply { url = ""; session = session() }))
        assertNotNull(MadridMessages.problemWithCard(MadridMessage().apply { url = "data:x"; session = null }))
        assertNotNull(MadridMessages.problemWithCard(MadridMessage().apply { url = "data:x"; session = "S-1" }))
        // An address too long for the way to the iMessage library is refused, card or move, never cut.
        val long = "data:" + "a".repeat(MadridMessages.MAX_URL)
        assertNotNull(MadridMessages.problemWithCard(MadridMessage().apply { url = long; session = session() }))
        assertNotNull(MadridMessages.problemWithMove(MadridMessage().apply { url = long }))
        assertNull(MadridMessages.problemWithMove(MadridMessage().apply { url = long.take(MadridMessages.MAX_URL) }))
        assertNotNull(MadridMessages.problemWithMove(MadridMessage().apply { url = null }))
        assertTrue(MadridMessages.isSession(session))
        assertTrue(MadridMessages.isSession(session.uppercase()))
        assertFalse(MadridMessages.isSession("1-1-1-1-1"))
        assertFalse(MadridMessages.isSession(""))
    }

    private fun session() = session

    @Test
    fun onlyAJpegOfAReasonableSizeIsTaken() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2)
        assertArrayEquals(jpeg, MadridMessages.jpegOf(Base64.getEncoder().encodeToString(jpeg)))
        assertArrayEquals(jpeg, MadridMessages.jpegOf(Base64.getMimeEncoder(4, "\n".toByteArray()).encodeToString(jpeg)))
        assertNull(MadridMessages.jpegOf(null))
        assertNull(MadridMessages.jpegOf(""))
        assertNull("not a JPEG", MadridMessages.jpegOf(Base64.getEncoder().encodeToString(byteArrayOf(-119, 80, 78, 71))))
        assertNull("not base64", MadridMessages.jpegOf("%%%"))
        val huge = ByteArray(MadridMessages.MAX_IMAGE_BYTES + 1).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() }
        assertNull(MadridMessages.jpegOf(Base64.getEncoder().encodeToString(huge)))
    }
}
