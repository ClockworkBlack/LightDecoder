package com.lightdecoder.archive

import android.os.Handler
import android.os.Looper
import com.lightdecoder.chat.ChatService
import java.util.concurrent.CopyOnWriteArraySet

/**
 * What changed in an archive. [conversationId] is null when the change is
 * not about one conversation (or about many at once). [service] says whose
 * archive it was: conversation ids only mean something within one service,
 * so a screen showing one service ignores the other's changes.
 */
data class ArchiveChange(
    val conversationId: String?,
    val kind: Kind,
    val service: ChatService = ChatService.MESSAGES,
) {
    enum class Kind { CONVERSATIONS, MESSAGES, HEALTH, OUTBOX }
}

/**
 * Lets open screens refresh the moment an archive changes. Every service's
 * archive announces here; listeners tell them apart by [ArchiveChange.service].
 */
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
