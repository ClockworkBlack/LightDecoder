package com.lightdecoder.ui.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.imessage.direct.IMessageExtra
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What an iMessage carries beyond its text, as its bubble shows it: effects, invisible ink, link and app cards. */
@RunWith(AndroidJUnit4::class)
class IMessageBubblesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aMessageSentWithAnEffectSaysWhichAsOnAnIphone() {
        assertEquals("Sent with Slam", IMessageBubbles.effectCaption(context, IMessageExtra(effect = "com.apple.MobileSMS.expressivesend.impact")))
        assertEquals("Sent with Echo", IMessageBubbles.effectCaption(context, IMessageExtra(effect = "com.apple.messages.effect.CKEchoEffect")))
        assertNull(IMessageBubbles.effectCaption(context, IMessageExtra()))
        assertNull(IMessageBubbles.effectCaption(context, IMessageExtra(effect = "com.example.new")))
    }

    @Test
    fun invisibleInkHidesTheWordsButNotTheirShapeUntilTapped() {
        val ink = IMessageExtra(effect = IMessageExtra.INVISIBLE_INK)
        assertTrue(IMessageBubbles.hidden(ink, "a secret", revealed = false))
        assertFalse(IMessageBubbles.hidden(ink, "a secret", revealed = true))
        assertFalse(IMessageBubbles.hidden(ink, "", revealed = false))
        assertFalse(IMessageBubbles.hidden(IMessageExtra(effect = "com.apple.MobileSMS.expressivesend.loud"), "loud", revealed = false))
        assertEquals("▒ ▒▒▒▒▒▒\n▒▒", IMessageBubbles.inked("a secret\nok"))
        // An emoji is one block, not two.
        assertEquals("▒▒", IMessageBubbles.inked("🦊!"))
    }

    @Test
    fun aLinkCardShowsTheTitleSummaryAndSite() {
        val link = IMessageExtra.Link("https://www.example.com/a", "A page", "About it", "example.com")
        assertEquals(IMessageBubbles.LinkLines("A page", "About it", "example.com"), IMessageBubbles.linkLines(link))
        // No title: the site stands for it (once), else the address.
        assertEquals(IMessageBubbles.LinkLines("example.com", "", ""), IMessageBubbles.linkLines(link.copy(title = "", summary = "")))
        assertEquals(IMessageBubbles.LinkLines("https://x", "", ""), IMessageBubbles.linkLines(IMessageExtra.Link("https://x")))
    }

    @Test
    fun anAppCardShowsItsLinesInTheOrderAnIphoneDoes() {
        val app = IMessageExtra.App(
            name = "GamePigeon", ldText = "8 Ball", imageTitle = "8 Ball", imageSubtitle = "Round 2", caption = "Your move",
            subcaption = "Bob is solids", secondarySubcaption = "2 left", tertiarySubcaption = "not shown",
        )
        assertEquals(
            IMessageBubbles.AppLines("8 Ball", "Round 2", "Your move", "Bob is solids", "2 left", "GamePigeon"),
            IMessageBubbles.appLines(app),
        )
        // No caption: the app's summary of it.
        assertEquals("8 Ball", IMessageBubbles.appLines(app.copy(caption = "")).caption)
        assertEquals("", IMessageBubbles.appLines(app.copy(name = IMessageExtra.UNNAMED_APP)).appName)
    }
}
