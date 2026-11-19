package com.lightdecoder.imessage.madrid

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.imessage.direct.IMessageApps
import com.lightdecoder.imessage.direct.IMessageExtra
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Which extension app plays an iMessage app's cards, and that it is the real one before it is used. */
@RunWith(AndroidJUnit4::class)
class MadridAppsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val gamePigeon = "com.apple.messages.MSMessageExtensionBalloonPlugin:EWFNLB79LQ:com.gamerdelights.gamepigeon.ext"

    @After
    fun tearDown() {
        MadridApps.resetForTesting()
        IMessageApps.host = null
        com.lightdecoder.imessage.direct.DirectLink.appEvents = null
    }

    @Test
    fun openPigeonIsRegisteredAsOpenBubblesPinsIt() {
        val o = MadridApps.OPENPIGEON
        assertEquals(1124197642L, o.appId)
        assertEquals(gamePigeon, o.bundleId)
        assertEquals("com.openbubbles.openpigeon", o.pkg)
        assertEquals("com.openbubbles.openpigeon.MadridExtensionService", o.service)
        assertEquals("CB:77:DB:97:3F:D8:CE:CD:55:75:B0:85:92:F4:4C:F5:4C:25:DE:21:B7:95:3F:ED:45:7C:BD:F4:36:D4:01:1D", o.certSha256)
        assertEquals(32, MadridApps.hexToBytes(o.certSha256)!!.size)
        assertEquals("market://details?id=com.openbubbles.openpigeon", o.marketUri)
        assertEquals("https://play.google.com/store/apps/details?id=com.openbubbles.openpigeon", o.webStoreUri)
        assertEquals("com.openbubbles.messaging.MadridExtensionService", MadridApps.ACTION)
    }

    @Test
    fun aCardFindsItsAppByItsStoreIdElseItsBundle() {
        assertSame(MadridApps.OPENPIGEON, MadridApps.forApp(IMessageExtra.App(appId = 1124197642L)))
        assertSame(MadridApps.OPENPIGEON, MadridApps.forApp(IMessageExtra.App(bundleId = gamePigeon)))
        assertNull(MadridApps.forApp(IMessageExtra.App(name = "Polls", appId = 1L, bundleId = "com.example.polls")))
        assertNull(MadridApps.forApp(IMessageExtra.App()))
    }

    @Test
    fun theCertificateIsMatchedByItsSha256() {
        assertArrayEquals(byteArrayOf(0xCB.toByte(), 0x07, 0x00), MadridApps.hexToBytes("CB:07:00"))
        assertNull(MadridApps.hexToBytes("CB:7"))
        assertNull(MadridApps.hexToBytes("ZZ:00"))
        val cert = "a certificate".toByteArray()
        val pin = MessageDigest.getInstance("SHA-256").digest(cert).joinToString(":") { "%02X".format(it) }
        assertEquals(pin, MadridApps.fingerprint(cert))
        assertTrue(MadridApps.matches(pin, listOf("another".toByteArray(), cert)))
        assertTrue(MadridApps.matches(pin.lowercase(), listOf(cert)))
        assertFalse(MadridApps.matches(pin, listOf("another".toByteArray())))
        assertFalse(MadridApps.matches(MadridApps.OPENPIGEON.certSha256, emptyList()))
    }

    @Test
    fun aTapOpensItOnlyInTheRealAppElseAsksToInstallIt() {
        val card = IMessageExtra.App(name = "GamePigeon", appId = 1124197642L, url = "data:x", session = "s")
        val o = MadridApps.OPENPIGEON
        assertEquals(MadridApps.Route.Engage(o), MadridApps.route(card) { MadridApps.Status.VERIFIED })
        assertEquals(MadridApps.Route.Unverified(o), MadridApps.route(card) { MadridApps.Status.UNVERIFIED })
        assertEquals(MadridApps.Route.Install(o), MadridApps.route(card) { MadridApps.Status.NOT_INSTALLED })
        assertEquals(MadridApps.Route.None, MadridApps.route(card.copy(appId = 5L)) { MadridApps.Status.VERIFIED })
    }

    @Test
    fun notInstalledHereIsNotInstalledAndOffersNoGames() {
        assertEquals(MadridApps.Status.NOT_INSTALLED, MadridApps.status(context.packageManager, MadridApps.OPENPIGEON))
        assertTrue(MadridApps.installed(context.packageManager).isEmpty())
        assertTrue(MadridHost.get(context).available(context).isEmpty())
    }

    @Test
    fun aCertificateCheckIsKeptUntilTheAppIsUpdated() {
        val info = android.content.pm.PackageInfo().apply {
            packageName = MadridApps.OPENPIGEON.pkg
            lastUpdateTime = 1_000
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = MadridApps.OPENPIGEON.pkg }
        }
        val shadowPm = org.robolectric.Shadows.shadowOf(context.packageManager)
        shadowPm.installPackage(info)
        var checks = 0
        var real = true
        MadridApps.verifier = { _, _ -> checks++; real }
        assertEquals(MadridApps.Status.VERIFIED, MadridApps.status(context.packageManager, MadridApps.OPENPIGEON))
        assertEquals(MadridApps.Status.VERIFIED, MadridApps.status(context.packageManager, MadridApps.OPENPIGEON))
        assertEquals("checked once", 1, checks)
        // Updated (replaced by another build): checked again.
        real = false
        shadowPm.installPackage(android.content.pm.PackageInfo().apply {
            packageName = info.packageName
            lastUpdateTime = 2_000
            applicationInfo = info.applicationInfo
        })
        assertEquals(MadridApps.Status.UNVERIFIED, MadridApps.status(context.packageManager, MadridApps.OPENPIGEON))
        assertEquals(2, checks)
        assertTrue(MadridApps.installed(context.packageManager).isEmpty())
        assertEquals(2, checks)
        shadowPm.deletePackage(info.packageName)
    }

    @Test
    fun theHostLeavesCardsOfOtherAppsToTheirOtherWays() {
        MadridHost.install(context)
        val poll = IMessageExtra.App(name = "Polls", appId = 1L, url = "https://example.com/poll")
        val row = com.lightdecoder.archive.MessageRow(
            id = "imsg:P1", conversationId = "imsg:tel:+15550000002", tmpId = "", senderId = "", senderName = "", isOutgoing = false,
            timestampMs = 1, direction = "in", status = "", statusCode = 0, errorText = "", transport = "", text = "", subject = "",
            replyToId = "", reactions = emptyList(), localState = "", media = emptyList(), updatedAt = 0, extra = IMessageExtra(app = poll).toJson(),
        )
        val tap = IMessageApps.tap(poll) { IMessageApps.host?.engage(context, row, it) == true }
        assertEquals(IMessageApps.Tap.Open("https://example.com/poll"), tap)
        // A game whose card has no session cannot be opened in the app: its other ways apply.
        val sessionless = IMessageExtra.App(name = "GamePigeon", appId = 1124197642L, url = "data:x")
        assertEquals(MadridApps.Route.Engage(MadridApps.OPENPIGEON), MadridApps.route(sessionless) { MadridApps.Status.VERIFIED })
        assertFalse(MadridMessages.isSession(sessionless.session))
    }
}
