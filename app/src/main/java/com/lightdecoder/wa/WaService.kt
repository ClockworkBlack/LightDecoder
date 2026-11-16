package com.lightdecoder.wa

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Keeps the WhatsApp link connected while the app is in the background, and
 * makes that visible: while it runs, its notification (channel
 * [com.lightdecoder.relay.RelayNotifications.CHANNEL_WA_STATUS], id
 * [com.lightdecoder.relay.RelayNotifications.ID_WA_SERVICE]) says what the
 * link is doing and has a one-tap Disconnect. Foreground type
 * remoteMessaging on Android 14+, dataSync before that, as declared in the
 * manifest. It never runs while that notification cannot be seen
 * ([com.lightdecoder.relay.RelayNotifications.statusVisible] for WhatsApp).
 *
 * NOT IMPLEMENTED YET: a placeholder, so the manifest entry has a class.
 * Nothing starts it, and a start it gets anyway (Android restoring a
 * service) ends at once: it connects to nothing and shows nothing.
 */
class WaService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
