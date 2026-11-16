package com.lightdecoder.relay

import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.R
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

/** The foreground service, its notification and Disconnect button, and the boot receiver. */
@RunWith(AndroidJUnit4::class)
class RelayServiceTest {

    private lateinit var env: RelayTestEnv

    @Before
    fun setUp() {
        env = RelayTestEnv()
    }

    @After
    fun tearDown() {
        env.tearDown()
    }

    @Test
    fun runsInTheForegroundWithDisconnectAndConnects() {
        val (manager, fake) = env.pairedManager()
        RelayPrefs.setLiveEnabled(env.context, true)

        val controller = Robolectric.buildService(RelayService::class.java).create()
        val service = controller.get()
        val result = service.onStartCommand(null, 0, 1)

        assertEquals(Service.START_STICKY, result)
        val notification = shadowOf(service).lastForegroundNotification
        assertNotNull("foreground with a notification", notification)
        assertEquals(RelayNotifications.CHANNEL_STATUS, notification!!.channelId)
        assertEquals(env.context.getString(R.string.relay_action_disconnect), notification.actions.single().title)
        env.eventually(manager, what = "connect") { fake.calls.contains("connect") }
        assertTrue(manager.serviceRunning)

        controller.destroy()
        assertFalse(manager.serviceRunning)
    }

    @Test
    fun theNotificationFollowsTheState() {
        val (manager, fake) = env.pairedManager()
        RelayPrefs.setLiveEnabled(env.context, true)
        val service = Robolectric.buildService(RelayService::class.java).create().get()
        service.onStartCommand(null, 0, 1)
        env.eventually(manager, what = "connect") { fake.calls.contains("connect") }
        fake.goOnline()
        manager.awaitIdleForTesting()
        shadowOf(Looper.getMainLooper()).idle()

        val shown = shadowOf(env.context.getSystemService(android.app.NotificationManager::class.java))
            .getNotification(RelayNotifications.ID_SERVICE)
        assertEquals(
            env.context.getString(R.string.relay_state_connected),
            shown.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString(),
        )
    }

    @Test
    fun stopsItselfWhenTheUserDoesNotWantTheLinkLive() {
        env.pairedManager()
        RelayPrefs.setLiveEnabled(env.context, false)
        val service = Robolectric.buildService(RelayService::class.java).create().get()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun disconnectButtonDisconnectsAndTurnsStayConnectedOff() {
        val (manager, fake) = env.pairedManager()
        RelayPrefs.setLiveEnabled(env.context, true)
        val service = Robolectric.buildService(RelayService::class.java).create().get()
        service.onStartCommand(RelayService.disconnectIntent(env.context), 0, 1)

        assertFalse(RelayPrefs.liveEnabled(env.context))
        env.eventually(manager, what = "disconnect") { fake.calls.contains("disconnect") }
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun aStopWhileAStartIsOnItsWayIsLeftToTheServiceItself() {
        // Android crashes the app if a service started with
        // startForegroundService is stopped before it called startForeground.
        val (manager, _) = env.pairedManager()
        val app = shadowOf(env.context as Application)
        manager.startLive()
        val start = app.nextStartedService
        assertNotNull(start)
        manager.stopLive()
        assertNull("no stopService before startForeground", app.nextStoppedService)

        // The start arrives: the service goes foreground, then stops itself.
        val service = Robolectric.buildService(RelayService::class.java, start).create().get()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(start, 0, 1))
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertTrue(shadowOf(service).isStoppedBySelf)

        // Nothing is pending any more: a stop goes straight to Android.
        manager.stopLive()
        assertEquals(RelayService::class.java.name, app.nextStoppedService?.component?.className)
    }

    @Test
    fun bootStartsTheServiceOnlyWhenPairedLiveAndAutoStart() {
        env.pairedManager()
        val app = env.context as Application
        val boot = Intent(Intent.ACTION_BOOT_COMPLETED)

        RelayPrefs.setLiveEnabled(env.context, true)
        RelayPrefs.setAutoStart(env.context, false)
        BootReceiver().onReceive(env.context, boot)
        assertNull(shadowOf(app).nextStartedService)

        RelayPrefs.setAutoStart(env.context, true)
        BootReceiver().onReceive(env.context, boot)
        assertEquals(RelayService::class.java.name, shadowOf(app).nextStartedService?.component?.className)

        RelayPrefs.setLiveEnabled(env.context, false)
        BootReceiver().onReceive(env.context, boot)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun bootDoesNotStartTheLinkWithItsNotificationOff() {
        env.pairedManager()
        RelayPrefs.setLiveEnabled(env.context, true)
        notifications().setNotificationsEnabled(false)
        BootReceiver().onReceive(env.context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(env.context as Application).nextStartedService)
    }

    @Test
    fun bootDoesNothingWhenNotPaired() {
        env.manager()
        RelayPrefs.setLiveEnabled(env.context, true)
        BootReceiver().onReceive(env.context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(env.context as Application).nextStartedService)
    }

    @Test
    fun theLinkDoesNotRunWhenItsNotificationCannotBeSeen() {
        // Android 13+: the user said "Don't allow" to notifications; or turned them off.
        val (manager, fake) = env.pairedManager()
        RelayPrefs.setLiveEnabled(env.context, true)
        notifications().setNotificationsEnabled(false)

        val service = Robolectric.buildService(RelayService::class.java).create().get()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertTrue(shadowOf(service).isStoppedBySelf)
        manager.awaitIdleForTesting()
        Thread.sleep(100)
        assertFalse("never connects without its notification", fake.calls.contains("connect"))
        assertEquals(RelayState(RelayState.Phase.DISCONNECTED, RelayState.NOTIFICATIONS_OFF), manager.state.value)
        assertTrue("stay connected is kept for when notifications are back", RelayPrefs.liveEnabled(env.context))
    }

    @Test
    fun stayConnectedWithNotificationsOffWaitsForThemAndResumes() {
        val (manager, _) = env.pairedManager()
        val app = shadowOf(env.context as Application)
        RelayPrefs.setAutoStart(env.context, false)
        notifications().setNotificationsEnabled(false)
        manager.startLive()
        assertNull("not started", app.nextStartedService)
        assertEquals(RelayState.NOTIFICATIONS_OFF, manager.state.value.code)
        manager.onAppVisible()
        assertNull("still off", app.nextStartedService)

        // The user turns notifications back on and comes back to the app.
        notifications().setNotificationsEnabled(true)
        manager.onAppVisible()
        assertEquals(RelayService::class.java.name, app.nextStartedService?.component?.className)
    }

    @Test
    fun turningNotificationsOffWhileConnectedTakesTheLinkDown() {
        val (manager, fake) = env.pairedManager()
        RelayPrefs.setLiveEnabled(env.context, true)
        val service = Robolectric.buildService(RelayService::class.java).create().get()
        service.onStartCommand(null, 0, 1)
        env.eventually(manager, what = "connect") { fake.calls.contains("connect") }
        fake.goOnline()
        manager.awaitIdleForTesting()

        notifications().setNotificationsEnabled(false)
        NotificationBlockReceiver().onReceive(env.context, Intent("android.app.action.APP_BLOCK_STATE_CHANGED"))
        env.eventually(manager, what = "the disconnect") { fake.calls.contains("disconnect") }
        assertEquals(RelayService::class.java.name, shadowOf(env.context as Application).nextStoppedService?.component?.className)
        manager.awaitIdleForTesting()
        assertEquals(RelayState(RelayState.Phase.DISCONNECTED, RelayState.NOTIFICATIONS_OFF), manager.state.value)
        assertTrue(RelayPrefs.liveEnabled(env.context))
    }

    @Test
    fun aSwipedAwayNotificationComesBackWhileConnected() {
        val (manager, fake) = env.pairedManager()
        RelayPrefs.setLiveEnabled(env.context, true)
        val service = Robolectric.buildService(RelayService::class.java).create().get()
        service.onStartCommand(null, 0, 1)
        env.eventually(manager, what = "connect") { fake.calls.contains("connect") }
        val posted = shadowOf(service).lastForegroundNotification!!
        assertNotNull("the notification asks to be put back when swiped away", posted.deleteIntent)

        // Android 14+: the user swipes it away.
        env.context.getSystemService(NotificationManager::class.java).cancel(RelayNotifications.ID_SERVICE)
        assertNull(notifications().getNotification(RelayNotifications.ID_SERVICE))
        assertEquals(Service.START_STICKY, service.onStartCommand(RelayService.repostIntent(env.context), 0, 2))
        assertNotNull(notifications().getNotification(RelayNotifications.ID_SERVICE))
        assertFalse(shadowOf(service).isStoppedBySelf)

        // A repost reaching a service that is not running in the foreground leaves nothing behind.
        val stray = Robolectric.buildService(RelayService::class.java).create().get()
        assertEquals(Service.START_NOT_STICKY, stray.onStartCommand(RelayService.repostIntent(env.context), 0, 1))
        assertTrue(shadowOf(stray).isStoppedBySelf)
    }

    @Test
    fun theServiceGoingAwayTakesTheConnectionWithIt() {
        val (manager, fake) = env.pairedManager()
        RelayPrefs.setLiveEnabled(env.context, true)
        val controller = Robolectric.buildService(RelayService::class.java).create()
        controller.get().onStartCommand(null, 0, 1)
        env.eventually(manager, what = "connect") { fake.calls.contains("connect") }
        fake.goOnline()
        manager.awaitIdleForTesting()

        controller.destroy() // e.g. stopped by Android
        env.eventually(manager, what = "the disconnect") { fake.calls.contains("disconnect") }
    }

    @Test
    fun anotherAppOpeningAScreenDoesNotStartTheLinkWhenAutoStartIsOff() {
        val (manager, _) = env.pairedManager()
        val app = shadowOf(env.context as Application)
        RelayPrefs.setLiveEnabled(env.context, true)
        RelayPrefs.setAutoStart(env.context, false)
        manager.onAppVisible()
        assertNull(app.nextStartedService)

        // But a start the user wanted that Android refused is repaired.
        manager.resumeOnVisible = true
        manager.onAppVisible()
        assertEquals(RelayService::class.java.name, app.nextStartedService?.component?.className)
        assertFalse(manager.resumeOnVisible)
    }

    @Test
    fun aScreenComingToTheFrontStartsALinkThatShouldBeRunning() {
        val (manager, _) = env.pairedManager()
        RelayPrefs.setLiveEnabled(env.context, true)
        manager.onAppVisible()
        assertEquals(RelayService::class.java.name, shadowOf(env.context as Application).nextStartedService?.component?.className)

        RelayPrefs.setLiveEnabled(env.context, false)
        manager.onAppVisible()
        assertNull(shadowOf(env.context as Application).nextStartedService)
    }

    private fun notifications() = shadowOf(env.context.getSystemService(NotificationManager::class.java))
}
