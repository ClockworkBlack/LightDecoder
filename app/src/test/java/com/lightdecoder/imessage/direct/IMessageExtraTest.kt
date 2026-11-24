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
    // --- B4: Live Photos, stickers, batches, per-photo tapbacks --------------------------------------

    @Test
    fun theMediaMarksGoAndComeBack() {
        val e = IMessageExtra(
            live = mapOf("p0" to "p1"),
            stickerParts = listOf("p3"),
            pending = listOf(IMessageExtra.PendingFile("/x/outbox/t-1", "image/png", "a.png")),
            tapbackParts = mapOf("+15551234567" to 2, "me" to 1),
        )
        val back = IMessageExtra.parse(e.toJson())
        assertEquals(e.live, back.live)
        assertEquals(e.stickerParts, back.stickerParts)
        assertEquals(e.pending, back.pending)
        assertEquals(e.tapbackParts, back.tapbackParts)
        assertEquals(setOf("p1"), back.motionParts)
        assertEquals("", IMessageExtra().toJson())
    }

    @Test
    fun theMediaMarksAreReadWithLimits() {
        val live = org.json.JSONObject().apply {
            put("p0", "p1"); put("p2", "../x"); put("x", "p3"); put("p4", "p4"); put("p5", 6)
            for (i in 100 until 200) put("p$i", "p${i + 1000}")
        }
        val json = org.json.JSONObject()
            .put("live", live)
            .put("stickerParts", org.json.JSONArray(listOf("p1", "p1", "q", 5) + (0 until 100).map { "p$it" }))
            .put("pending", org.json.JSONArray((0 until 20).map { org.json.JSONObject().put("path", "/p/$it").put("mime", "image/png").put("name", "n") } + listOf("x")))
            .put("tapbackParts", org.json.JSONObject().put("a", 2).put("b", 0).put("c", "3").put("d", 99_999))
        val e = IMessageExtra.parse(json.toString())
        assertTrue(e.live.size <= IMessageExtra.MAX_LIVE)
        assertTrue(e.live.all { (k, v) -> IMessageExtra.isPartId(k) && IMessageExtra.isPartId(v) && k != v })
        assertFalse("p2" in e.live)
        assertTrue(e.stickerParts.size <= IMessageExtra.MAX_MEDIA_MARKS)
        assertEquals(e.stickerParts.distinct(), e.stickerParts)
        assertFalse("q" in e.stickerParts)
        assertEquals(IMessageExtra.MAX_PENDING, e.pending.size)
        assertEquals(mapOf("a" to 2, "b" to 0), e.tapbackParts)
    }

    @Test
    fun aPersonsTapbackMovesFromPhotoToPhotoAndOff() {
        var parts = IMessageExtra.withTapbackPart(emptyMap(), "a", 2, enable = true)
        assertEquals(mapOf("a" to 2), parts)
        parts = IMessageExtra.withTapbackPart(parts, "a", 3, enable = true)
        assertEquals(mapOf("a" to 3), parts)
        // The first photo is part 0; none named, or taken back: on no photo.
        assertEquals(mapOf("a" to 0), IMessageExtra.withTapbackPart(parts, "a", 0, enable = true))
        assertEquals(emptyMap<String, Int>(), IMessageExtra.withTapbackPart(parts, "a", null, enable = true))
        assertEquals(emptyMap<String, Int>(), IMessageExtra.withTapbackPart(parts, "a", 3, enable = false))
        // Full: nobody new, the ones there still move.
        val full = (0 until IMessageExtra.MAX_TAPBACK_PARTS).associate { "p$it" to 1 }
        assertEquals(full, IMessageExtra.withTapbackPart(full, "new", 2, enable = true))
        assertEquals(2, IMessageExtra.withTapbackPart(full, "p0", 2, enable = true)["p0"])
        // Apple's copy again: what was gathered stays.
        assertEquals(mapOf("a" to 3), IMessageExtra().keepingGathered(IMessageExtra(tapbackParts = mapOf("a" to 3))).tapbackParts)
    }
}
