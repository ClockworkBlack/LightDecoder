package com.lightdecoder.imessage

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Opening a new chat in OpenBubbles: which links its version takes is not
 * documented, so each way is tried in turn, and "not installed" is said only
 * when it is not.
 */
@RunWith(AndroidJUnit4::class)
class OpenBubblesNewChatTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val pkg = OpenBubbles.PACKAGES.first()
    private val main = ComponentName(pkg, "com.localob.messaging.app.MainActivity")

    @Before
    fun setUp() {
        IMessageLog.clear(app)
        shadowOf(app).checkActivities(true)
    }

    /** The real OpenBubbles: installed, and its certificate the pinned one. */
    private fun install() {
        val pm = shadowOf(app.packageManager)
        pm.installPackage(PackageInfo().apply { packageName = pkg })
        pm.addActivityIfNotPresent(main)
        OpenBubbles.verifier = { _, p, _ -> p == pkg }
    }

    @org.junit.After
    fun tearDown() {
        OpenBubbles.resetForTesting()
    }

    private fun accept(filter: IntentFilter) {
        filter.addCategory(Intent.CATEGORY_DEFAULT)
        shadowOf(app.packageManager).addIntentFilterForActivity(main, filter)
    }

    private fun log() = IMessageLog.read(app).reversed().joinToString("\n")

    @Test
    fun notInstalledIsSaidOnlyWhenItIsNot() {
        assertEquals(OpenBubbles.NewChat.NOT_INSTALLED, OpenBubbles.openNewChat(app, "+15551234567", "hi"))
        assertTrue(log(), log().contains("OpenBubbles is not installed"))
    }

    @Test
    fun anOpenBubblesThatTakesNoLinkGetsTheTextOnItsShareScreen() {
        install()
        accept(IntentFilter(Intent.ACTION_SEND).apply { addDataType("text/plain") })
        assertEquals(OpenBubbles.NewChat.SHARE_OPENED, OpenBubbles.openNewChat(app, "+15551234567", "hi"))
        val log = log()
        assertTrue(log, log.contains("New chat by imessage: link not taken"))
        assertTrue(log, log.contains("New chat by smsto: link not taken"))
        assertTrue(log, log.contains("New chat by share screen opened"))
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_SEND, started.action)
        assertEquals("hi", started.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue("nothing private logged", !log.contains("5551234567") && !log.contains("hi\n"))
    }

    @Test
    fun anSmstoLinkIsUsedWhenThatIsWhatItTakes() {
        install()
        accept(IntentFilter(Intent.ACTION_SENDTO).apply { addDataScheme("smsto") })
        assertEquals(OpenBubbles.NewChat.CHAT_OPENED, OpenBubbles.openNewChat(app, "+15551234567", "hi"))
        val started = shadowOf(app).nextStartedActivity
        assertEquals("smsto", started.data?.scheme)
        assertEquals("hi", started.getStringExtra("sms_body"))
    }

    @Test
    fun anImessageLinkComesFirst() {
        install()
        accept(IntentFilter(Intent.ACTION_VIEW).apply { addDataScheme("imessage") })
        accept(IntentFilter(Intent.ACTION_SENDTO).apply { addDataScheme("smsto") })
        assertEquals(OpenBubbles.NewChat.CHAT_OPENED, OpenBubbles.openNewChat(app, "+15551234567", ""))
        assertEquals("imessage", shadowOf(app).nextStartedActivity.data?.scheme)
    }
}
