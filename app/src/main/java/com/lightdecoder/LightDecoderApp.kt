package com.lightdecoder

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.lightdecoder.discord.DiscordProcess
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
 *
 * The Discord tab runs in a second process of its own
 * ([com.lightdecoder.discord.DiscordContainer]); there this class sets up its
 * WebView data folder and stops.
 */
class LightDecoderApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // The Discord tab's process: it gets its own WebView data folder and
        // nothing else. No link, archive or notification channel is set up
        // there, and the sign-in wipe below never runs on its data.
        if (DiscordProcess.enterIfDiscordProcess(this)) return
        // The same for the process that shows Facebook's messaging page.
        if (com.lightdecoder.messenger.MessengerProcess.enterIfMessengerProcess(this)) return
        // And for the iMessage process, the only one that loads OpenBubbles' library.
        if (com.lightdecoder.imessage.direct.DirectProcess.enterIfImessageProcess(this)) return
        // Also for processes that start with no screen (boot, the link's service):
        // sign-in cookies a killed sign-in left behind go before anything else runs.
        SignInWebData.wipeIfDirty(this)
        RelayNotifications.ensureChannels(this)
        com.lightdecoder.relay.ReadNotifier.ensureChannel(this)
        RelayManager.get(this)
        // The iMessage library, once registered: its chats show in Messages (Blue Bubbles on, once),
        // and its service keeps it connected in the background.
        com.lightdecoder.imessage.direct.DirectLink.onAppStart(this)
        // With a Gmail account added, its background check is scheduled (survives updates).
        // While the Gmail tab is paused, nothing checks the mailbox in the background.
        if (com.lightdecoder.chat.Features.GMAIL && com.lightdecoder.mail.MailVault.exists(this)) {
            com.lightdecoder.mail.MailCheckJob.schedule(this)
        } else {
            com.lightdecoder.mail.MailCheckJob.cancel(this)
        }
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
                com.lightdecoder.relay.AppVisibility.started = started
                RelayManager.get(activity).onAppVisible()
                if (WaPrefs.liveEnabled(activity)) WaManager.get(activity).onAppVisible()
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                com.lightdecoder.relay.AppVisibility.started = started
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
