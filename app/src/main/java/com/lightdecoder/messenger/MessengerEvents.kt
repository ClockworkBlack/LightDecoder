package com.lightdecoder.messenger

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/** Lets the Messenger screens refresh the moment something is caught, sent or cleared. */
object MessengerEvents {
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
