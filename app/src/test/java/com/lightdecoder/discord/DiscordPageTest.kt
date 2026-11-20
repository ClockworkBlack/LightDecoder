package com.lightdecoder.discord

import android.app.Activity
import android.content.MutableContextWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.ui.discord.DiscordPage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/** Discord's page stays loaded between visits to the tab, and sits still while used. */
@RunWith(AndroidJUnit4::class)
class DiscordPageTest {

    @Before
    fun setUp() = DiscordPage.resetForTesting()

    @After
    fun tearDown() = DiscordPage.resetForTesting()

    @Test
    fun thePageReachesNothingElseOnThePhone() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val (page, _) = DiscordPage.obtain(activity)
        com.lightdecoder.ui.discord.DiscordWebSettings.apply(page.settings)
        val s = page.settings
        assertTrue("Discord's web app runs", s.javaScriptEnabled)
        assertTrue(s.domStorageEnabled)
        assertFalse("no file: urls", s.allowFileAccess)
        // Attachments still come in through the system picker (onShowFileChooser), which this does not touch.
        assertFalse("no content: urls of other apps", s.allowContentAccess)
        assertFalse(s.supportMultipleWindows())
        assertFalse(s.javaScriptCanOpenWindowsAutomatically)
        assertTrue(s.mediaPlaybackRequiresUserGesture)
    }

    @Test
    fun thePageIsKeptBetweenVisits() {
        val first = Robolectric.buildActivity(Activity::class.java).setup().get()
        val frame = FrameLayout(first)
        val (page, fresh) = DiscordPage.obtain(first)
        assertTrue("nothing loaded in it yet", fresh)
        frame.addView(page)

        // The tab is left: the page comes off the screen and waits.
        DiscordPage.release(page, frame, first.applicationContext)
        assertNull(page.parent)
        assertSame("no longer holding on to the closed screen", first.applicationContext, (page.context as MutableContextWrapper).baseContext)

        // And back: the same page, not a new load.
        val second = Robolectric.buildActivity(Activity::class.java).setup().get()
        val (again, freshAgain) = DiscordPage.obtain(second)
        assertSame(page, again)
        assertFalse(freshAgain)
        assertSame(second, (again.context as MutableContextWrapper).baseContext)
    }

    @Test
    fun aScreenThatOpensBeforeTheOldOneClosesTakesThePage() {
        val old = Robolectric.buildActivity(Activity::class.java).setup().get()
        val oldFrame = FrameLayout(old)
        val (page, _) = DiscordPage.obtain(old)
        oldFrame.addView(page)
        val new = Robolectric.buildActivity(Activity::class.java).setup().get()
        val newFrame = FrameLayout(new)
        val (taken, fresh) = DiscordPage.obtain(new)
        assertSame(page, taken)
        assertFalse(fresh)
        assertNull("off the old screen", page.parent)
        newFrame.addView(taken)
        // The old screen closing afterwards must not take it off the new one.
        DiscordPage.release(page, oldFrame, old.applicationContext)
        assertSame(newFrame, page.parent)
    }

    @Test
    fun aDeadPageIsReplaced() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val (page, _) = DiscordPage.obtain(activity)
        DiscordPage.discard(page)
        val (next, fresh) = DiscordPage.obtain(activity)
        assertNotSame(page, next)
        assertTrue(fresh)
    }

    @Test
    fun thePageSitsStill() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val (page, _) = DiscordPage.obtain(activity)
        assertEquals("no stretch or bounce at the edges", View.OVER_SCROLL_NEVER, page.overScrollMode)
        assertFalse("no pinch or double-tap zoom", page.settings.supportZoom())
        assertFalse(page.settings.builtInZoomControls)
        // The page as a whole cannot be moved sideways; up and down is the page's own.
        page.scrollTo(120, 40)
        assertEquals(0, page.scrollX)
        assertEquals(40, page.scrollY)
    }
}
