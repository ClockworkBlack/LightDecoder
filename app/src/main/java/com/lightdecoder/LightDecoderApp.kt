package com.lightdecoder

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.lightdecoder.relay.RelayManager
import com.lightdecoder.relay.RelayNotifications
import com.lightdecoder.ui.relay.SignInWebData
import com.lightdecoder.wa.WaManager
import com.lightdecoder.wa.WaPrefs

/**
 * The app process. Sets up the Google Messages link's notification channels
 * and its manager. Neither touches the network or loads the Go bridge unless
 * the user has paired; for everyone else LightDecoder stays the sticker
 * decoder it was. The WhatsApp link's manager is not created here at all
 * unless the user linked WhatsApp and wants it connected.
 */
class LightDecoderApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Also for processes that start with no screen (boot, the link's service):
        // sign-in cookies a killed sign-in left behind go before anything else runs.
        SignInWebData.wipeIfDirty(this)
        RelayNotifications.ensureChannels(this)
        RelayManager.get(this)
        // With a Gmail account added, its background check is scheduled (survives updates).
        if (com.lightdecoder.mail.MailVault.exists(this)) com.lightdecoder.mail.MailCheckJob.schedule(this)
        // If the user wants the link connected and it is not running (Android
        // refused to start its service from the background, its notification
        // was turned off, or, with "start automatically" on, the app was
        // restarted), start it once a screen is shown (RelayManager.onAppVisible).
        // The WhatsApp link follows the same rule, and is also told when the
        // last screen leaves the front (it must not stay "online" then). Its
        // manager is only made once the user wants WhatsApp connected.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                started++
                RelayManager.get(activity).onAppVisible()
                if (WaPrefs.liveEnabled(activity)) WaManager.get(activity).onAppVisible()
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                // A rotation stops and starts the same screen: that is not leaving.
                if (started == 0 && !activity.isChangingConfigurations && WaPrefs.liveEnabled(activity)) {
                    WaManager.get(activity).onAppBackground()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
