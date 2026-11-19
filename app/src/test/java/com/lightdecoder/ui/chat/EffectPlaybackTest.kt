package com.lightdecoder.ui.chat

import android.app.Activity
import android.provider.Settings
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.imessage.direct.IMessageExtra
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/**
 * Message effects played on screen ([EffectPlayer]): which kind each is, which messages play by
 * themselves and only once ([EffectWatch], [PlayedEffects]), and nothing moving with animations off.
 * The thread screen's part is in BlueBubblesScreenTest.
 */
@RunWith(AndroidJUnit4::class)
class EffectPlaybackTest {

    private val slam = "com.apple.MobileSMS.expressivesend.impact"
    private val confetti = "com.apple.messages.effect.CKConfettiEffect"

    private fun row(id: String, ts: Long, outgoing: Boolean = false, effect: String? = null, text: String = "hi") = MessageRow(
        id = id, conversationId = "c1", tmpId = "", senderId = if (outgoing) "" else "p1", senderName = "Alice",
        isOutgoing = outgoing, timestampMs = ts, direction = if (outgoing) "out" else "in", status = "", statusCode = 0,
        errorText = "", transport = "iMessage", text = text, subject = "", replyToId = "",
        reactions = emptyList(), localState = "", media = emptyList(), updatedAt = 0,
        extra = IMessageExtra(effect = effect).toJson(),
    )

    @Test
    fun bubbleEffectsMoveTheBubbleAndTheRestTheScreen() {
        val bubble = listOf(
            slam, "com.apple.MobileSMS.expressivesend.loud", "com.apple.MobileSMS.expressivesend.gentle", IMessageExtra.INVISIBLE_INK,
        )
        for (id in bubble) assertEquals(id, false, EffectPlayer.isScreen(id))
        val screen = IMessageExtra.EFFECTS.map { it.first } - bubble.toSet()
        assertEquals(8, screen.size)
        for (id in screen) assertEquals(id, true, EffectPlayer.isScreen(id))
        // Every effect the app names it can play, and on the side the sheet puts it.
        for ((id, _) in IMessageExtra.EFFECTS) assertEquals(id, !IMessageExtra.isBubbleEffect(id), EffectPlayer.isScreen(id))
        assertNull(EffectPlayer.isScreen("com.example.new"))
        assertNull(EffectPlayer.isScreen(null))
        assertEquals(EffectKind.BALLOONS, EffectKind.of("com.apple.messages.effect.CKHappyBirthdayEffect"))
        assertEquals(EffectKind.CELEBRATION, EffectKind.of("com.apple.messages.effect.CKSparklesEffect"))
    }

    @Test
    fun playedOnceIsRememberedAndTheOldestGoFirst() {
        val played = PlayedEffects(cap = 3)
        assertTrue(played.add("a"))
        assertFalse("not twice", played.add("a"))
        played.add("b")
        played.add("c")
        played.add("d")
        assertEquals(listOf("b", "c", "d"), played.toList())
        assertFalse("a" in played)
        assertFalse(played.add(""))
        assertEquals(listOf("b", "c", "d"), PlayedEffects.decode(played.encode()).toList())
        assertEquals(0, PlayedEffects.decode(null).size)
        // The default keeps the last 500.
        val many = PlayedEffects()
        (1..600).forEach { many.add("m$it") }
        assertEquals(PlayedEffects.CAP, many.size)
        assertTrue("m600" in many)
        assertFalse("m100" in many)
    }

    @Test
    fun playsAreKeptAcrossOpenings() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        EffectPlays(context).add("imsg:A")
        assertTrue("imsg:A" in EffectPlays(context))
        assertFalse("imsg:B" in EffectPlays(context))
    }

    @Test
    fun onOpeningTheNewestIncomingEffectPlaysIfNewOrUnread() {
        val now = 10_000_000L
        val rows = listOf(row("1", now - 5_000, effect = slam), row("2", now - 4_000, effect = confetti), row("3", now - 3_000))
        assertEquals("2", EffectWatch().arrived(rows, unreadAtOpen = false, nowMs = now) { false }?.id)
        // Already played: nothing.
        assertNull(EffectWatch().arrived(rows, unreadAtOpen = false, nowMs = now) { it == "2" })
        // The user answered it already: nothing.
        assertNull(EffectWatch().arrived(rows + row("4", now - 1_000, outgoing = true), unreadAtOpen = false, nowMs = now) { false })
        // Old and read: nothing; old but unread: it plays.
        val old = listOf(row("1", now - 2 * EffectWatch.FRESH_MS, effect = confetti))
        assertNull(EffectWatch().arrived(old, unreadAtOpen = false, nowMs = now) { false })
        assertEquals("1", EffectWatch().arrived(old, unreadAtOpen = true, nowMs = now) { false }?.id)
        // The user's own never plays by itself.
        assertNull(EffectWatch().arrived(listOf(row("5", now, outgoing = true, effect = slam)), unreadAtOpen = true, nowMs = now) { false })
    }

    @Test
    fun whileOpenOnlyANewArrivalPlays() {
        val now = 10_000_000L
        val watch = EffectWatch()
        val first = listOf(row("1", now - 10 * EffectWatch.FRESH_MS, effect = slam), row("2", now - 1_000))
        assertNull(watch.arrived(first, unreadAtOpen = false, nowMs = now) { false })
        // The same rows again (a status change): nothing.
        assertNull(watch.arrived(first, unreadAtOpen = false, nowMs = now) { false })
        // Older ones paged in at the top: nothing.
        val older = listOf(row("0", now - 20 * EffectWatch.FRESH_MS, effect = confetti)) + first
        assertNull(watch.arrived(older, unreadAtOpen = false, nowMs = now) { false })
        // One arrives with confetti: it plays, once.
        val arrived = older + row("3", now, effect = confetti)
        assertEquals("3", watch.arrived(arrived, unreadAtOpen = false, nowMs = now) { false }?.id)
        assertNull(watch.arrived(arrived, unreadAtOpen = false, nowMs = now) { false })
        // Without an effect: nothing.
        assertNull(watch.arrived(arrived + row("4", now + 1), unreadAtOpen = false, nowMs = now) { false })
    }

    @Test
    fun theSentMessageIsTheNewestOfTheUsersWithThatEffect() {
        val rows = listOf(row("old", 1_000, outgoing = true, effect = slam), row("in", 6_000, effect = slam), row("tmp:a", 6_000, outgoing = true, effect = slam))
        assertEquals("tmp:a", EffectWatch.sentRow(rows, slam, 5_000) { false }?.id)
        assertNull("another effect", EffectWatch.sentRow(rows, confetti, 5_000) { false })
        assertNull("played", EffectWatch.sentRow(rows, slam, 5_000) { it == "tmp:a" })
    }

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test
    fun aScreenEffectDrawsOverTheScreenAndABubbleEffectMovesTheBubble() {
        val activity = activity()
        val content = activity.findViewById<FrameLayout>(android.R.id.content)
        val list = FrameLayout(activity)
        val bubble = android.view.View(activity)
        list.addView(bubble, 200, 80)
        content.addView(list)
        val player = EffectPlayer(activity, list) { false }
        val seen = ArrayList<EffectPlayer.Play>()
        player.onPlay = { seen += it }
        val play = player.play("m1", confetti, EffectPlayer.Reason.RECEIVED, bubble, "Party")!!
        assertTrue(play.screen)
        assertTrue(play.animated)
        assertEquals(listOf(play), seen)
        assertTrue("an overlay on top", (0 until content.childCount).any { content.getChildAt(it) is EffectOverlayView })
        assertEquals(1, player.overlaysShown)
        player.play("m2", slam, EffectPlayer.Reason.SENT, bubble, "Boom")
        assertEquals("a bubble effect draws nothing over the screen", 1, player.overlaysShown)
        // Done: the overlay goes, the bubble is as it was.
        org.robolectric.shadows.ShadowLooper.idleMainLooper(5, java.util.concurrent.TimeUnit.SECONDS)
        assertFalse((0 until content.childCount).any { content.getChildAt(it) is EffectOverlayView })
        assertEquals(1f, bubble.scaleX)
        assertEquals(0f, bubble.translationY)
        assertEquals(2, player.plays.size)
        assertNull("unknown effects do not play", player.play("m3", "com.example.new", EffectPlayer.Reason.REPLAY, bubble, ""))
    }

    @Test
    fun withAnimationsRemovedNothingMoves() {
        val activity = activity()
        Settings.Global.putFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        try {
            assertTrue(EffectPlayer.animationsOff(activity))
            val content = activity.findViewById<FrameLayout>(android.R.id.content)
            val bubble = android.view.View(activity)
            content.addView(bubble, 200, 80)
            val player = EffectPlayer(activity, content)
            val play = player.play("m1", confetti, EffectPlayer.Reason.RECEIVED, bubble, "Party")!!
            assertFalse(play.animated)
            player.play("m2", slam, EffectPlayer.Reason.SENT, bubble, "Boom")
            assertFalse((0 until content.childCount).any { content.getChildAt(it) is EffectOverlayView })
            assertEquals(0, player.overlaysShown)
            assertEquals(1f, bubble.scaleX)
            assertEquals(2, player.plays.size)
        } finally {
            Settings.Global.putFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
        assertFalse(EffectPlayer.animationsOff(activity))
    }
}
