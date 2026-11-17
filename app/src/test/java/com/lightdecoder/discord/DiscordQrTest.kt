package com.lightdecoder.discord

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** Signing in to the Discord tab with Discord's QR code. */
@RunWith(AndroidJUnit4::class)
class DiscordQrTest {

    private val link = "https://discord.com/ra/UZ0-kOVzXDZTFVV5_QlpURSO2BQHrtkKWHNpIGoDI0k"

    @Test
    fun onlyADiscordSignInLinkIsTaken() {
        assertEquals(link, DiscordQr.loginLinkOrNull(link))
        assertEquals(link, DiscordQr.loginLinkOrNull(" $link\n"))
        for (other in listOf(
            "http://discord.com/ra/UZ0-kOVzXDZTFVV5_QlpURSO2BQHrtkKWHNpIGoDI0k",
            "https://discord.com.evil.example/ra/UZ0-kOVzXDZTFVV5_QlpURSO2BQHrtkKWHNpIGoDI0k",
            "https://evil.example/ra/UZ0-kOVzXDZTFVV5_QlpURSO2BQHrtkKWHNpIGoDI0k",
            "https://discord.com/invite/abc",
            "https://discord.com/ra/short",
            "https://discord.com/ra/UZ0-kOVzXDZTFVV5_QlpURSO2BQHrtkKWHNpIGoDI0k?next=https://evil.example",
            "",
        )) assertNull(other, DiscordQr.loginLinkOrNull(other))
        assertNull(DiscordQr.loginLinkOrNull(null))
    }

    @Test
    fun theCodeIsReadOffTheScreen() {
        assertEquals("a code as drawn", link, DiscordQr.find(DiscordQr.draw(link, 300)))
        // As on the phone: Discord's dark computer-size page scaled down to fit, the code in a white box.
        val screen = Bitmap.createBitmap(1080, 1600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(screen)
        canvas.drawColor(Color.rgb(49, 51, 56))
        canvas.drawRect(Rect(600, 500, 900, 800), Paint().apply { color = Color.WHITE })
        canvas.drawBitmap(DiscordQr.draw(link, 180), null, Rect(660, 560, 840, 740), Paint(Paint.FILTER_BITMAP_FLAG))
        assertEquals(link, DiscordQr.find(screen))
        // Another site's code, or none at all, is not taken.
        assertNull(DiscordQr.find(DiscordQr.draw("https://evil.example/ra/UZ0-kOVzXDZTFVV5_QlpURSO2BQHrtkKWHNpIGoDI0k", 300)))
        assertNull(DiscordQr.find(Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }))
    }

    @Test
    fun thePageGetsAComputerSizeWindowScaledToFit() {
        // A phone 1080 pixels wide at 2.625 pixels per dp.
        val wide = DiscordQr.wide(1080, 1800, 2.625f)
        assertEquals(2888, wide.width)
        assertEquals(1080f / 2888, wide.scale, 0.0001f)
        assertEquals("the same screen area, at the smaller scale", 1800f, wide.height * wide.scale, 2f)
        // Already that wide (a tablet): left as it is.
        assertEquals(1f, DiscordQr.wide(3000, 2000, 2f).scale, 0f)
    }

    @Test
    fun approvingHandsTheLinkToTheDiscordAppOnly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(DiscordQr.approveInDiscordApp(context, link))
        val started = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("com.discord", started.`package`)
        assertEquals(link, started.dataString)
        // Anything but a sign-in link is never sent.
        assertFalse(DiscordQr.approveInDiscordApp(context, "https://evil.example/x"))
        assertNull(shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity)
    }
}
