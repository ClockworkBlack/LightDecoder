package com.lightdecoder.ui.messenger

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.R
import com.lightdecoder.messenger.MessengerLinks
import com.lightdecoder.messenger.MessengerWebState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** Signing in to Facebook on the Messenger tab's page: the notes, and approving in the Facebook app. */
@RunWith(AndroidJUnit4::class)
class MessengerSignInTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theSignInPageGetsTheTipsAndTheCodePageOffersTheFacebookApp() {
        val signIn = MessengerWebActivity.banner(MessengerWebState.SIGNED_OUT, facebookInstalled = true)!!
        assertEquals(R.string.messenger_web_sign_in_banner, signIn.text)
        assertFalse(signIn.approveButton)
        assertTrue(context.getString(signIn.text).contains("password manager"))

        val code = MessengerWebActivity.banner(MessengerWebState.CHECK, facebookInstalled = true)!!
        assertEquals(R.string.messenger_web_check_banner, code.text)
        assertTrue(code.approveButton)

        val noApp = MessengerWebActivity.banner(MessengerWebState.CHECK, facebookInstalled = false)!!
        assertEquals(R.string.messenger_web_check_banner_no_app, noApp.text)
        assertFalse("nothing to open on this phone", noApp.approveButton)

        assertNull("no note over the conversations", MessengerWebActivity.banner(MessengerWebState.MESSAGES, true))
        assertNull(MessengerWebActivity.banner(MessengerWebState.OTHER, true))
    }

    @Test
    fun approveOpensTheFacebookAppAndNothingElse() {
        assertFalse(MessengerLinks.facebookInstalled(context))
        assertFalse(MessengerLinks.openFacebookApp(context))

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(MessengerLinks.FACEBOOK_PACKAGE)
        val pm = shadowOf(context.packageManager)
        pm.installPackage(PackageInfo().apply {
            packageName = MessengerLinks.FACEBOOK_PACKAGE
            applicationInfo = ApplicationInfo().apply { packageName = MessengerLinks.FACEBOOK_PACKAGE }
        })
        pm.addResolveInfoForIntent(launcher, android.content.pm.ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = MessengerLinks.FACEBOOK_PACKAGE; name = "com.facebook.katana.LoginActivity" }
        })
        assertTrue(MessengerLinks.facebookInstalled(context))
        assertTrue(MessengerLinks.openFacebookApp(context))
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(MessengerLinks.FACEBOOK_PACKAGE, started.`package` ?: started.component?.packageName)
        assertNull("nothing is handed to it", started.data)
    }
}
