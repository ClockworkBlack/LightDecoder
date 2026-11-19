package com.lightdecoder.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lightdecoder.chat.ChatService
import com.lightdecoder.wa.WaLog
import com.lightdecoder.wa.WaManager
import com.lightdecoder.wa.WaPrefs
import com.lightdecoder.wa.WaService

/**
 * Reconnects the links after the phone restarts (or the app is updated):
 * each one only if the user linked it, chose to stay connected, and left its
 * own "start automatically" on. Nothing happens for anyone else, and one
 * link's settings never start the other.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        startMessages(context)
        startWhatsApp(context)
        // The iMessage library, once registered, reconnects after a reboot or an update too.
        com.lightdecoder.imessage.direct.DirectService.start(context)
    }

    private fun startMessages(context: Context) {
        if (!RelayPrefs.autoStart(context) || !RelayPrefs.liveEnabled(context)) return
        val manager = RelayManager.get(context)
        if (!manager.isPaired()) return
        if (!RelayNotifications.statusVisible(context)) {
            // The link never runs without its notification; it starts once a
            // screen is shown with notifications back on.
            manager.onNotificationsHidden()
            return
        }
        // start() catches ForegroundServiceStartNotAllowedException: Android
        // allows this start after boot and updates, but some builds do not.
        if (RelayService.start(context)) RelayLog.add(context, "Reconnecting after restart")
    }

    private fun startWhatsApp(context: Context) {
        // Read from the preferences first: without a link the manager is not even created.
        if (!WaPrefs.autoStart(context) || !WaPrefs.liveEnabled(context)) return
        val manager = WaManager.get(context)
        if (!manager.isPaired()) return
        if (!RelayNotifications.statusVisible(context, ChatService.WHATSAPP)) {
            manager.onNotificationsHidden()
            return
        }
        if (WaService.start(context, manager)) WaLog.add(context, "Reconnecting after restart")
    }

    private companion object {
        val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
