package com.lightdecoder.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The wake-up alarm behind a reconnect backoff. The backoff's own timer only
 * counts while the phone is awake, so with the screen off an errored link
 * could wait hours; this alarm (elapsed real time, allowed while idle) fires
 * on time and retries. Not exported: only the app's own alarm reaches it.
 *
 * The broadcast keeps the phone awake until [goAsync]'s result is finished,
 * so the connect gets a little while to run before the phone sleeps again.
 */
class ReconnectReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RECONNECT) return
        val connecting = RelayManager.get(context).onReconnectDue() ?: return
        val pending = goAsync()
        background.launch {
            try {
                withTimeoutOrNull(HOLD_AWAKE_MS) { connecting.join() }
            } finally {
                pending.finish()
            }
        }
    }

    internal companion object {
        const val ACTION_RECONNECT = "com.lightdecoder.relay.action.RECONNECT"

        /** Well below the time Android gives a background broadcast. */
        private const val HOLD_AWAKE_MS = 25_000L

        private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
