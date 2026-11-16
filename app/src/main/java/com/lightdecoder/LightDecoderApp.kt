package com.lightdecoder

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.lightdecoder.relay.RelayManager
import com.lightdecoder.relay.RelayNotifications
import com.lightdecoder.ui.relay.SignInWebData

/**
 * The app process. Sets up the Google Messages link's notification channels
 * and its manager. Neither touches the network or loads the Go bridge unless
 * the user has paired; for everyone else LightDecoder stays the sticker
 * decoder it was.
 */
class LightDecoderApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Also for processes that start with no screen (boot, the link's service):
        // sign-in cookies a killed sign-in left behind go before anything else runs.
        SignInWebData.wipeIfDirty(this)
        RelayNotifications.ensureChannels(this)
        RelayManager.get(this)
        // If the user wants the link connected and it is not running (Android
        // refused to start its service from the background, its notification
        // was turned off, or, with "start automatically" on, the app was
        // restarted), start it once a screen is shown (RelayManager.onAppVisible).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = RelayManager.get(activity).onAppVisible()
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
