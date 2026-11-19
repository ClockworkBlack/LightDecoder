package com.lightdecoder.imessage.direct

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tap on an iMessage app's card: an extension that plays it here first, else the browser, else an Apple device. */
class IMessageAppsTest {

    private val game = IMessageExtra.App(name = "GamePigeon", appId = 1124197642L, url = "data:state", caption = "Your move")

    @After
    fun tearDown() {
        IMessageApps.host = null
    }

    @Test
    fun anInstalledExtensionTakesItFirst() {
        var asked: IMessageExtra.App? = null
        assertEquals(IMessageApps.Tap.Engaged, IMessageApps.tap(game.copy(url = "https://example.com")) { asked = it; true })
        assertEquals("https://example.com", asked!!.url)
    }

    @Test
    fun elseAWebAddressOpensAndAnythingElseIsForAnAppleDevice() {
        assertEquals(IMessageApps.Tap.Open("https://example.com/poll"), IMessageApps.tap(game.copy(url = "https://example.com/poll")) { false })
        assertEquals(IMessageApps.Tap.OnlyOnApple("GamePigeon"), IMessageApps.tap(game) { false })
        assertEquals(IMessageApps.Tap.OnlyOnApple(""), IMessageApps.tap(game.copy(name = IMessageExtra.UNNAMED_APP)) { false })
    }

    @Test
    fun thereIsNoHostUntilOneIsSetUp() {
        assertEquals(null, IMessageApps.host)
        IMessageApps.host = IMessageApps.Host { _, _, _ -> true }
        assertTrue(IMessageApps.host != null)
    }
}
