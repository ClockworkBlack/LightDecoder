package com.lightdecoder.wa

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.R
import com.lightdecoder.relay.BootReceiver
import com.lightdecoder.relay.RelayNotifications
import com.lightdecoder.relay.RelayPrefs
import com.lightdecoder.relay.RelayService
import com.lightdecoder.relay.RelayState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/** The WhatsApp link's foreground service, its notification and Disconnect button, and the boot receiver. */
@RunWith(AndroidJUnit4::class)
class WaServiceTest {

    private lateinit var env: WaTestEnv

    @Before
    fun setUp() {
        env = WaTestEnv()
    }

    @After
    fun tearDown() {
        env.tearDown()
    }

    @Test
    fun runsInTheForegroundWithDisconnectAndConnects() {
        val (manager, fake) = env.linkedManager()
        WaPrefs.setLiveEnabled(env.context, true)

        val controller = Robolectric.buildService(WaService::class.java).create()
        val service = controller.get()
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))

        val notification = shadowOf(service).lastForegroundNotification
        assertNotNull("foreground with a notification", notification)
        assertEquals("on WhatsApp's own channel", RelayNotifications.CHANNEL_WA_STATUS, notification!!.channelId)
        assertEquals(env.context.getString(R.string.wa_action_disconnect), notification.actions.single().title)
        env.eventually(manager, what = "connect") { fake.calls.contains("connect") }
        assertTrue(manager.serviceRunning)

        controller.destroy()
        assertFalse(manager.serviceRunning)
        env.eventually(manager, what = "the link to go down with its notification") { fake.calls.contains("disconnect") }
    }

    @Test
    fun theNotificationFollowsTheState() {
        val (manager, _) = env.connectedManager()
        manager.awaitIdleForTesting()
        shadowOf(Looper.getMainLooper()).idle()

        val shown = shadowOf(env.context.getSystemService(NotificationManager::class.java))
            .getNotification(RelayNotifications.ID_WA_SERVICE)
        assertEquals(
            env.context.getString(R.string.wa_state_connected),
            shown.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
    }

    @Test
    fun stopsItselfWithoutALinkOrWhenNotWanted() {
        // Not linked: a start Android restored connects to nothing.
        val manager = env.manager()
        WaPrefs.setLiveEnabled(env.context, true)
        val unlinked = Robolectric.buildService(WaService::class.java).create().get()
        assertEquals(Service.START_NOT_STICKY, unlinked.onStartCommand(null, 0, 1))
        assertTrue(shadowOf(unlinked).isStoppedBySelf)
        assertNull("the bridge is not even loaded", env.fake)
        assertFalse(manager.isPaired())
    }

    @Test
    fun stopsItselfWhenTheUserDoesNotWantTheLinkLive() {
        val (_, fake) = env.linkedManager()
        WaPrefs.setLiveEnabled(env.context, false)
        val service = Robolectric.buildService(WaService::class.java).create().get()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(fake.calls.contains("connect"))
    }

    @Test
    fun disconnectButtonDisconnectsAndTurnsStayConnectedOff() {
        val (manager, fake) = env.connectedManager()
        val service = Robolectric.buildService(WaService::class.java).create().get()
        service.onStartCommand(WaService.disconnectIntent(env.context), 0, 2)

        assertFalse(WaPrefs.liveEnabled(env.context))
        env.eventually(manager, what = "disconnect") { fake.calls.contains("disconnect") }
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue("the link itself is kept", manager.isPaired())
    }

    @Test
    fun theLinkDoesNotRunWithoutItsNotification() {
        val (manager, fake) = env.linkedManager()
        WaPrefs.setLiveEnabled(env.context, true)
        shadowOf(env.context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        try {
            val service = Robolectric.buildService(WaService::class.java).create().get()
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
            assertTrue(shadowOf(service).isStoppedBySelf)
            manager.awaitIdleForTesting()
            assertFalse(fake.calls.contains("connect"))
            assertEquals(RelayState.NOTIFICATIONS_OFF, manager.state.value.code)
            assertTrue("the wish is kept for when notifications are back", WaPrefs.liveEnabled(env.context))
        } finally {
            shadowOf(env.context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        }
    }

    @Test
    fun afterARestartOnlyAWantedLinkStartsAndNeverTheOtherOne() {
        val boot = Intent(Intent.ACTION_BOOT_COMPLETED)
        val app = shadowOf(env.app)

        // Linked, but the user did not ask to stay connected.
        env.linkedManager()
        BootReceiver().onReceive(env.context, boot)
        assertNull(app.peekNextStartedService())

        // Wanted, but "start automatically" is off.
        WaPrefs.setLiveEnabled(env.context, true)
        WaPrefs.setAutoStart(env.context, false)
        BootReceiver().onReceive(env.context, boot)
        assertNull(app.peekNextStartedService())

        WaPrefs.setAutoStart(env.context, true)
        BootReceiver().onReceive(env.context, boot)
        assertEquals(ComponentName(env.context, WaService::class.java), app.nextStartedService?.component)
        assertNull("WhatsApp's settings do not start the Google Messages link", app.peekNextStartedService())
        assertFalse(RelayPrefs.liveEnabled(env.context))
        assertFalse(app.allStartedServices.any { it.component == ComponentName(env.context, RelayService::class.java) })
    }

    @Test
    fun withoutALinkARestartStartsNothing() {
        WaPrefs.setLiveEnabled(env.context, true)
        env.manager()
        BootReceiver().onReceive(env.context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(env.app).peekNextStartedService())
    }
}
