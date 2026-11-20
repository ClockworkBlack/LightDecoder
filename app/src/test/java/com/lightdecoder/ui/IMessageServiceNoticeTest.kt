package com.lightdecoder.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.R
import com.lightdecoder.imessage.direct.DirectEngine.Status
import com.lightdecoder.imessage.direct.DirectLink
import com.lightdecoder.imessage.direct.DirectPrefs
import com.lightdecoder.imessage.direct.ServiceNotice
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.ui.imessage.DirectSetupActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The iMessage service's notice: a quiet "iMessage is on" on a channel of its own at the lowest
 * importance, one notification however often the status comes, never bundled with messages, and
 * a button on the setup screen that opens its channel's settings to turn it off.
 */
@RunWith(AndroidJUnit4::class)
class IMessageServiceNoticeTest {

    private lateinit var two: TwoServicesEnv
    private val nm get() = two.app.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        two = TwoServicesEnv()
        nm.cancelAll()
    }

    @After
    fun tearDown() {
        nm.cancelAll()
        DirectPrefs.setRegistered(two.app, false)
        DirectLink.resetForTesting()
        IMessageManager.resetForTesting()
        two.tearDown()
    }

    @Test
    fun aNewQuietChannelReplacesTheOldOne() {
        // As an earlier version left it (and as the user may have raised it).
        nm.createNotificationChannel(NotificationChannel("imessage_status", "iMessage connection", NotificationManager.IMPORTANCE_DEFAULT))
        ServiceNotice.ensureChannel(two.app)
        assertNull("the old channel is deleted", nm.getNotificationChannel("imessage_status"))
        val channel = nm.getNotificationChannel(ServiceNotice.CHANNEL)
        assertNotNull(channel)
        assertEquals("imessage_service_quiet", channel.id)
        assertEquals(NotificationManager.IMPORTANCE_MIN, channel.importance)
        assertFalse("no dot on the icon", channel.canShowBadge())
        assertNull("no sound", channel.sound)
        assertFalse(channel.shouldVibrate())
        assertFalse(channel.shouldShowLights())
        assertEquals(Notification.VISIBILITY_SECRET, channel.lockscreenVisibility)
        assertEquals("iMessage status", channel.name.toString())
        // Again: nothing changes, nothing fails.
        ServiceNotice.ensureChannel(two.app)
        assertEquals(NotificationManager.IMPORTANCE_MIN, nm.getNotificationChannel(ServiceNotice.CHANNEL).importance)
    }

    @Test
    fun connectedItSaysCalmlyThatIMessageIsOn() {
        val notice = ServiceNotice.Poster(two.app)
        val n = notice.forForeground(Status.Registered(listOf("tel:+15551234567")))
        assertEquals("iMessage is on", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Running in the background", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(ServiceNotice.CHANNEL, n.channelId)
        assertEquals("no count", 0, n.number)
        assertEquals(NotificationCompat.BADGE_ICON_NONE, n.badgeIconType)
        assertEquals(ServiceNotice.GROUP, n.group)
        assertTrue("ongoing", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue("alerts once at most", n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(Notification.CATEGORY_SERVICE, n.category)
        assertEquals(Notification.VISIBILITY_SECRET, n.visibility)
        assertFalse("no time beside it", n.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertEquals("the setup screen", DirectSetupActivity::class.java.name, shadowOf(n.contentIntent).savedIntent.component?.className)
        // The troubles say what they are.
        assertEquals("Trying again soon: no network", ServiceNotice.content(two.app, Status.Retrying("no network", 30)).text)
        assertEquals("Not working: refused", ServiceNotice.content(two.app, Status.Failed("refused")).text)
    }

    @Test
    fun repeatedStatusesKeepOneNotificationPostedOnlyWhenItsTextChanges() {
        val notice = ServiceNotice.Poster(two.app)
        val connected = Status.Registered(listOf("tel:+15551234567"))
        assertTrue("the first is posted", notice.update(Status.Working("Starting")))
        assertTrue(notice.update(connected))
        assertFalse("the same text again: not posted", notice.update(connected))
        assertFalse("other addresses, the same text: not posted", notice.update(Status.Registered(listOf("mailto:a@b.c"))))
        assertTrue(notice.update(Status.Retrying("no network", 30)))
        assertFalse(notice.update(Status.Retrying("no network", 25)))
        assertTrue(notice.update(connected))
        val shown = shadowOf(nm).allNotifications
        assertEquals("exactly one notification", 1, shown.size)
        assertEquals("iMessage is on", shown.single().extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(ServiceNotice.GROUP, shown.single().group)
    }

    @Test
    fun theStartForegroundNoticeCountsAsShown() {
        val notice = ServiceNotice.Poster(two.app)
        val connected = Status.Registered(listOf("tel:+15551234567"))
        notice.forForeground(connected)
        assertFalse("already shown by startForeground", notice.update(connected))
    }

    @Test
    fun theSetupScreenOpensTheNoticesChannelSettings() {
        ActivityScenario.launch(DirectSetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val button = activity.findViewById<android.widget.Button>(R.id.hideStatus)
                assertEquals("Hide status notification", button.text.toString())
                assertTrue(
                    "the help says iMessage keeps working",
                    two.findAll(activity.window.decorView) {
                        it is android.widget.TextView && "iMessage keeps working; only the status notice is hidden." in it.text.toString()
                    }.isNotEmpty(),
                )
                button.performClick()
                val started = shadowOf(activity).nextStartedActivity
                assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, started.action)
                assertEquals(activity.packageName, started.getStringExtra(Settings.EXTRA_APP_PACKAGE))
                assertEquals(ServiceNotice.CHANNEL, started.getStringExtra(Settings.EXTRA_CHANNEL_ID))
            }
        }
    }
}
