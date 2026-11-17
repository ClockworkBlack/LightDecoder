package com.lightdecoder.ui.relay

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.R
import com.lightdecoder.relay.RelayException
import com.lightdecoder.relay.RelayNotifications
import com.lightdecoder.relay.RelayState
import com.lightdecoder.relay.RelayState.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * Error wording, the state-to-action rules (including "notifications are
 * off"), which pages the sign-in WebView may open, and the wipe of sign-in
 * data a killed setup left behind.
 */
@RunWith(AndroidJUnit4::class)
class RelayUiTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun everyBridgeCodeHasItsOwnExplanation() {
        val specific = RelayException.CODES - RelayException.INTERNAL
        for (code in specific) {
            assertNotEquals("code $code fell through to the generic text", R.string.link_error_internal, RelayErrors.messageRes(code))
            assertTrue(context.getString(RelayErrors.messageRes(code)).isNotBlank())
        }
        assertEquals(specific.size, specific.map { RelayErrors.messageRes(it) }.toSet().size)
        assertEquals(R.string.link_error_internal, RelayErrors.messageRes("something_new"))
    }

    @Test
    fun codesComeFromAnyThrowable() {
        assertEquals("incorrect_emoji", RelayErrors.codeOf(Exception("incorrect_emoji: a different emoji was chosen")))
        assertEquals("incorrect_emoji", RelayErrors.codeOf(RelayException("incorrect_emoji", "")))
        assertEquals("internal", RelayErrors.codeOf(IllegalStateException("boom")))
        assertTrue(RelayErrors.needsNewSignIn(RelayException.COOKIES_INVALID))
        assertFalse(RelayErrors.canRetryPairing(RelayException.NO_COOKIES))
        assertTrue(RelayErrors.canRetryPairing(RelayException.INCORRECT_EMOJI))
    }

    @Test
    fun whatEachStateOffers() {
        assertEquals(RelayUi.Fix.SET_UP, RelayUi.fix(RelayState(Phase.UNPAIRED), paired = false))
        assertEquals(RelayUi.Fix.SET_UP, RelayUi.fix(RelayState(Phase.LOGGED_OUT), paired = false))
        assertEquals(RelayUi.Fix.SET_UP, RelayUi.fix(RelayState(Phase.COOKIES_INVALID), paired = true))
        assertEquals(RelayUi.Fix.USE_HERE, RelayUi.fix(RelayState(Phase.ACTIVE_ELSEWHERE), paired = true))
        assertEquals(RelayUi.Fix.CONNECT, RelayUi.fix(RelayState(Phase.DISCONNECTED), paired = true))
        assertEquals(RelayUi.Fix.STATUS, RelayUi.fix(RelayState(Phase.ERROR, "internal"), paired = true))
        assertEquals(RelayUi.Fix.NONE, RelayUi.fix(RelayState(Phase.CONNECTED), paired = true))
        assertEquals(RelayUi.Fix.NONE, RelayUi.fix(RelayState(Phase.RECONNECTING), paired = true))
        // Every phase has a title and a body.
        for (phase in Phase.entries) {
            assertTrue(context.getString(RelayUi.title(phase)).isNotBlank())
            assertTrue(context.getString(RelayUi.body(phase)).isNotBlank())
        }
    }

    @Test
    fun withItsNotificationOffTheLinkOffersNotificationSettingsNotConnect() {
        val off = false
        // The runtime held the link back for that reason, or it is simply not running.
        assertEquals(
            RelayUi.Fix.NOTIFICATIONS,
            RelayUi.fix(RelayState(Phase.DISCONNECTED, RelayState.NOTIFICATIONS_OFF), paired = true, notificationsOn = off),
        )
        assertEquals(RelayUi.Fix.NOTIFICATIONS, RelayUi.fix(RelayState(Phase.DISCONNECTED), paired = true, notificationsOn = off))
        // Turned back on: an ordinary disconnected link again.
        assertEquals(
            RelayUi.Fix.CONNECT,
            RelayUi.fix(RelayState(Phase.DISCONNECTED, RelayState.NOTIFICATIONS_OFF), paired = true, notificationsOn = true),
        )
        // Pairing problems come first; a live link is left alone.
        assertEquals(RelayUi.Fix.SET_UP, RelayUi.fix(RelayState(Phase.UNPAIRED), paired = false, notificationsOn = off))
        assertEquals(RelayUi.Fix.SET_UP, RelayUi.fix(RelayState(Phase.LOGGED_OUT), paired = true, notificationsOn = off))
        assertEquals(RelayUi.Fix.NONE, RelayUi.fix(RelayState(Phase.CONNECTING), paired = true, notificationsOn = off))

        val fix = RelayUi.Fix.NOTIFICATIONS
        assertEquals(R.string.link_phase_notifications_off, RelayUi.title(Phase.DISCONNECTED, fix))
        assertEquals(R.string.link_body_notifications_off, RelayUi.body(Phase.DISCONNECTED, fix))
        assertEquals(R.string.link_fix_notifications, RelayUi.fixLabel(fix))
        assertNotEquals(R.string.link_error_internal, RelayErrors.messageRes(RelayState.NOTIFICATIONS_OFF))
    }

    @Test
    fun notificationSettingsOpenWhereTheLinksNotificationIsTurnedOff() {
        val system = context.getSystemService(NotificationManager::class.java)
        shadowOf(system).setNotificationsEnabled(false)
        val app = RelayUi.notificationSettingsIntent(context)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, app.action)
        assertEquals(context.packageName, app.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertFalse(RelayUi.notificationsOn(context))

        // The app's notifications are on, so only the link's channel can be the problem.
        shadowOf(system).setNotificationsEnabled(true)
        val channel = RelayUi.notificationSettingsIntent(context)
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, channel.action)
        assertEquals(RelayNotifications.CHANNEL_STATUS, channel.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    @Test
    fun signInDataAKilledSetupLeftIsWipedAtTheNextStart() {
        SignInWebData.resetForTesting()
        try {
            // What a process that died mid-sign-in leaves: the marker and Chromium's cookie store.
            SignInWebData.webViewCreating(context)
            val cookies = File(context.dataDir, "app_webview/Default/Cookies").apply {
                parentFile!!.mkdirs()
                writeText("SID=secret")
            }
            assertTrue(SignInWebData.isDirty(context))

            SignInWebData.wipeIfDirty(context)
            assertFalse("cookie store left behind", cookies.exists())
            assertFalse(File(context.dataDir, "app_webview").exists())
            assertFalse(SignInWebData.isDirty(context))

            // Once per process: a later screen does not touch a sign-in that is under way.
            SignInWebData.webViewCreating(context)
            SignInWebData.webViewCreated()
            cookies.parentFile!!.mkdirs()
            cookies.writeText("SID=in-progress")
            SignInWebData.wipeIfDirty(context)
            assertTrue(cookies.exists())
            assertTrue(SignInWebData.isDirty(context))
        } finally {
            SignInWebData.resetForTesting()
            File(context.dataDir, "app_webview").deleteRecursively()
            File(context.noBackupFilesDir, "signin-webview-dirty").delete()
        }
    }

    @Test
    fun signInWebViewOnlyOpensGoogleSignIn() {
        val allowed = listOf(
            "https://accounts.google.com/AccountChooser?continue=https://messages.google.com/web/config",
            "https://messages.google.com/web/config",
            "https://google.com/",
            "https://accounts.youtube.com/accounts/SetSID",
            "https://accounts.google.co.uk/accounts/SetSID",
            "https://accounts.google.de/accounts/SetSID",
            "about:blank",
        )
        val refused = listOf(
            "http://accounts.google.com/",
            "https://evil.example/",
            "https://google.com.evil.example/",
            "https://accounts.google.com.evil.example/",
            "https://notgoogle.com/",
            "https://www.youtube.com/",
            "javascript:alert(1)",
            "file:///data/data/com.lightdecoder/files/relay/session.bin",
            "intent://scan#Intent;end",
        )
        allowed.forEach { assertTrue(it, RelaySetupActivity.isAllowedSignInUrl(Uri.parse(it))) }
        refused.forEach { assertFalse(it, RelaySetupActivity.isAllowedSignInUrl(Uri.parse(it))) }
    }
}
