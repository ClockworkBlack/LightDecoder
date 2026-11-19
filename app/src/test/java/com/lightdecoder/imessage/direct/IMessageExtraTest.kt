package com.lightdecoder.imessage.direct

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What an iMessage carries besides its text, as stored with it. */
@RunWith(AndroidJUnit4::class)
class IMessageExtraTest {

    @Test
    fun nothingIsStoredAsNothing() {
        assertEquals("", IMessageExtra().toJson())
        assertEquals(IMessageExtra(), IMessageExtra.parse(""))
    }

    @Test
    fun everyFieldSurvivesTheTrip() {
        val full = IMessageExtra(
            parts = mapOf(0 to "hello", 2 to "again"),
            edits = 3,
            effect = "com.apple.messages.effect.CKConfettiEffect",
            voice = true,
            link = IMessageExtra.Link("https://example.com", "Example", "A page", "example.com"),
            app = IMessageExtra.App(
                name = "GamePigeon", appId = 1124197642L, bundleId = "b", url = "data:x", session = "S", ldText = "8 Ball",
                isLive = true, imageTitle = "t", imageSubtitle = "st", caption = "c", subcaption = "sc",
                secondarySubcaption = "ssc", tertiarySubcaption = "tsc", root = "imsg:R",
            ),
            sticker = true,
        )
        assertEquals(full, IMessageExtra.parse(full.toJson()))
        val noAppId = IMessageExtra(app = IMessageExtra.App(name = "Polls"))
        assertNull(IMessageExtra.parse(noAppId.toJson()).app!!.appId)
    }

    @Test
    fun anythingUnreadableIsEmpty() {
        assertEquals(IMessageExtra(), IMessageExtra.parse("not json"))
        assertEquals(IMessageExtra(), IMessageExtra.parse("[1,2]"))
        // Unknown fields (from a newer version) are left alone; known ones still read.
        assertEquals(IMessageExtra(edits = 2), IMessageExtra.parse("{\"edits\":2,\"later\":true}"))
    }

    @Test
    fun theTextIsThePartsInOrder() {
        assertEquals("one\ntwo\nthree", IMessageExtra(parts = mapOf(2 to "three", 0 to "one", 1 to "two")).text())
        assertEquals("one\nthree", IMessageExtra(parts = mapOf(0 to "one", 1 to "", 2 to "three")).text())
        assertEquals("", IMessageExtra().text())
    }

    @Test
    fun effectsHaveTheirIphoneNames() {
        assertEquals("Slam", IMessageExtra.effectName("com.apple.MobileSMS.expressivesend.impact"))
        assertEquals("Invisible Ink", IMessageExtra.effectName("com.apple.MobileSMS.expressivesend.invisibleink"))
        assertEquals("Celebration", IMessageExtra.effectName("com.apple.messages.effect.CKSparklesEffect"))
        assertNull(IMessageExtra.effectName("com.example.unknown"))
        assertNull(IMessageExtra.effectName(null))
        assertEquals(12, IMessageExtra.EFFECTS.size)
        assertTrue(IMessageExtra.isInvisibleInk("com.apple.MobileSMS.expressivesend.invisibleink"))
        assertFalse(IMessageExtra.isInvisibleInk("com.apple.MobileSMS.expressivesend.loud"))
    }
}
