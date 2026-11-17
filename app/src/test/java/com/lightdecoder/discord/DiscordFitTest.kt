package com.lightdecoder.discord

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.appbar.MaterialToolbar
import com.lightdecoder.R
import com.lightdecoder.databinding.ActivityDiscordBinding
import com.lightdecoder.discord.DiscordFit.PageSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** How large Discord's page is drawn, on a folding phone's two screens. */
@RunWith(AndroidJUnit4::class)
class DiscordFitTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    // About a Galaxy Z Fold's screens, in dp: the cover screen, and the main screen held upright.
    private val folded = 411
    private val unfolded = 750

    @Before
    fun setUp() {
        context.getSharedPreferences("discord_view", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun foldedIsPhoneSizeAndUnfoldedGetsTheSideBySideLayout() {
        assertFalse(DiscordFit.isWide(folded))
        assertTrue(DiscordFit.isWide(unfolded))
        assertEquals("folded: the page at its natural size", DiscordFit.Fit(411, 100), DiscordFit.fit(folded, PageSize.AUTO))
        assertEquals("unfolded: room for servers, channels and chat", DiscordFit.Fit(940, 100), DiscordFit.fit(unfolded, PageSize.AUTO))
        // A screen already wider than that (a tablet on its side) is left alone.
        assertEquals(DiscordFit.Fit(1280, 100), DiscordFit.fit(1280, PageSize.AUTO))
    }

    @Test
    fun smallerFitsMoreAndLargerEnlargesTheText() {
        assertEquals(DiscordFit.Fit(411, 100), DiscordFit.fit(folded, PageSize.NORMAL))
        assertEquals("80%: a quarter more page across", DiscordFit.Fit(514, 100), DiscordFit.fit(folded, PageSize.SMALL))
        assertEquals(DiscordFit.Fit(685, 100), DiscordFit.fit(folded, PageSize.TINY))
        assertEquals("larger: the same layout, bigger text", DiscordFit.Fit(411, 130), DiscordFit.fit(folded, PageSize.LARGEST))
        assertEquals("unfolded at normal size is the phone layout, large", DiscordFit.Fit(750, 100), DiscordFit.fit(unfolded, PageSize.NORMAL))
    }

    @Test
    fun thePageIsScaledToFillTheScreenExactly() {
        // The unfolded screen: 1968 pixels across at 2.625 pixels per dp, laid out 940 wide.
        val wide = DiscordQr.wide(1968, 1900, 2.625f, 940)
        assertEquals(2468, wide.width)
        assertEquals("fills the width", 1968f, wide.width * wide.scale, 1f)
        assertEquals("and the height", 1900f, wide.height * wide.scale, 2f)
        // Never laid out narrower than the screen.
        assertEquals(1f, DiscordQr.wide(1080, 2000, 2.625f, 300).scale, 0f)
    }

    @Test
    fun eachScreenRemembersItsOwnChoice() {
        assertSame(PageSize.AUTO, DiscordFit.pageSize(context, wide = false))
        assertSame(PageSize.AUTO, DiscordFit.pageSize(context, wide = true))
        DiscordFit.setPageSize(context, wide = false, PageSize.SMALLER)
        DiscordFit.setPageSize(context, wide = true, PageSize.NORMAL)
        assertSame(PageSize.SMALLER, DiscordFit.pageSize(context, wide = false))
        assertSame(PageSize.NORMAL, DiscordFit.pageSize(context, wide = true))
    }

    @Test
    fun theBarIsOneRowWithTheTabsInIt() {
        val themed = ContextThemeWrapper(context, R.style.Theme_LightDecoder)
        val binding = ActivityDiscordBinding.inflate(LayoutInflater.from(themed))
        var parent = binding.services.parent
        while (parent != null && parent !is MaterialToolbar) parent = parent.parent
        assertSame("the tab strip sits in the top bar itself", binding.toolbar, parent)
        assertTrue(binding.servicesMenu.parent === binding.services.parent)
    }
}
