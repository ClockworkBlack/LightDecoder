package com.lightdecoder.imessage.direct

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Takes in what Apple sent, from the ":imessage" process, and stores it in the iMessage archive
 * (main process). One change at a time, in the order the other process sends them (Apple's).
 */
class DirectSinkService : Service() {

    private val server = DirectIpc.Server { what, request ->
        if (what != DirectWire.UPDATE) return@Server DirectWire.error("Unknown request $what")
        synchronized(DirectSinkService::class.java) {
            DirectLink.get(this).apply(DirectWire.decode(request))
        }
        DirectWire.ok()
    }

    override fun onBind(intent: Intent?): IBinder = server.messenger.binder

    override fun onDestroy() {
        server.close()
        super.onDestroy()
    }
}
