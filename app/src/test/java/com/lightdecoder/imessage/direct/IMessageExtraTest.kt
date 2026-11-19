package com.lightdecoder.imessage.direct

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
            unsent = setOf(1, 3),
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
        val context: Context = ApplicationProvider.getApplicationContext()
        fun name(id: String) = IMessageExtra.effectName(id)?.let { context.getString(it) }
        assertEquals("Slam", name("com.apple.MobileSMS.expressivesend.impact"))
        assertEquals("Invisible Ink", name("com.apple.MobileSMS.expressivesend.invisibleink"))
        assertEquals("Celebration", name("com.apple.messages.effect.CKSparklesEffect"))
        assertEquals(12, IMessageExtra.EFFECTS.map { context.getString(it.second) }.toSet().size)
        assertNull(IMessageExtra.effectName("com.example.unknown"))
        assertNull(IMessageExtra.effectName(null))
        assertEquals(12, IMessageExtra.EFFECTS.size)
        assertTrue(IMessageExtra.isInvisibleInk("com.apple.MobileSMS.expressivesend.invisibleink"))
        assertFalse(IMessageExtra.isInvisibleInk("com.apple.MobileSMS.expressivesend.loud"))
    }

    @Test
    fun everyEffectHasTheNameAnIphoneGivesIt() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val names = IMessageExtra.EFFECTS.associate { (id, res) -> id to context.getString(res) }
        assertEquals(
            mapOf(
                "com.apple.MobileSMS.expressivesend.impact" to "Slam",
                "com.apple.MobileSMS.expressivesend.loud" to "Loud",
                "com.apple.MobileSMS.expressivesend.gentle" to "Gentle",
                "com.apple.MobileSMS.expressivesend.invisibleink" to "Invisible Ink",
                "com.apple.messages.effect.CKEchoEffect" to "Echo",
                "com.apple.messages.effect.CKSpotlightEffect" to "Spotlight",
                "com.apple.messages.effect.CKHappyBirthdayEffect" to "Balloons",
                "com.apple.messages.effect.CKConfettiEffect" to "Confetti",
                "com.apple.messages.effect.CKHeartEffect" to "Love",
                "com.apple.messages.effect.CKLasersEffect" to "Lasers",
                "com.apple.messages.effect.CKFireworksEffect" to "Fireworks",
                "com.apple.messages.effect.CKSparklesEffect" to "Celebration",
            ),
            names,
        )
    }

    @Test
    fun anAppsCardSaysItsCaptionElseItsSummaryElseItsName() {
        val app = IMessageExtra.App(name = "GamePigeon", ldText = "8 Ball", caption = "Your move")
        assertEquals("Your move", app.cardCaption)
        assertEquals("Your move", app.summary())
        assertEquals("8 Ball", app.copy(caption = "").summary())
        assertEquals("GamePigeon", app.copy(caption = "", ldText = "").summary())
        // The library's name for an app Apple did not name is not shown.
        assertEquals("", app.copy(name = IMessageExtra.UNNAMED_APP).shownName)
        assertEquals("", app.copy(name = IMessageExtra.UNNAMED_APP, caption = "", ldText = "").summary())
        // Only a web address opens in the browser (as on a Mac); an app's own state does not.
        assertTrue(app.copy(url = "https://example.com/poll").opensInBrowser)
        assertTrue(app.copy(url = "HTTP://example.com").opensInBrowser)
        assertFalse(app.copy(url = "data:game-state").opensInBrowser)
        assertFalse(app.copy(url = "").opensInBrowser)
    }

    @Test
    fun aLinksCardOpensOnlyAWebPage() {
        assertTrue(IMessageExtra.Link("https://example.com/a", "A page").opensInBrowser)
        assertTrue(IMessageExtra.Link("HTTP://example.com").opensInBrowser)
        // The sender chooses the address: one of any other kind, behind a title of theirs, is not opened.
        for (other in listOf("tel:+15551234567", "sms:+15551234567", "market://details?id=x", "content://x/y", "intent://x#Intent;end", "javascript:alert(1)", "")) {
            assertFalse(other, IMessageExtra.Link(other, "Your bank").opensInBrowser)
            assertFalse(other, IMessageExtra.isWebAddress(other))
        }
    }
}
