package com.lightdecoder.archive

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * What changed in the archive. [conversationId] is null when the change is
 * not about one conversation (or about many at once).
 */
data class ArchiveChange(val conversationId: String?, val kind: Kind) {
    enum class Kind { CONVERSATIONS, MESSAGES, HEALTH, OUTBOX }
}

/** Lets open screens refresh the moment the archive changes. */
object ArchiveEvents {
    private val listeners = CopyOnWriteArraySet<(ArchiveChange) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun register(listener: (ArchiveChange) -> Unit) {
        listeners.add(listener)
    }

    fun unregister(listener: (ArchiveChange) -> Unit) {
        listeners.remove(listener)
    }

    /** Safe to call from any thread; listeners run on the main thread. */
    fun notify(change: ArchiveChange) {
        if (listeners.isEmpty()) return
        main.post { listeners.forEach { it(change) } }
    }
}
