package com.lightdecoder.inbox

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/** Lets the inbox screen refresh the moment the listener stores something. */
object InboxEvents {
    private val listeners = CopyOnWriteArraySet<Runnable>()
    private val main = Handler(Looper.getMainLooper())

    fun register(listener: Runnable) {
        listeners.add(listener)
    }

    fun unregister(listener: Runnable) {
        listeners.remove(listener)
    }

    /** Safe to call from any thread; listeners run on the main thread. */
    fun notifyChanged() {
        main.post { listeners.forEach { it.run() } }
    }
}
