package com.lightdecoder.ui.chat

import android.content.Context
import com.lightdecoder.chat.ChatBackend
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageManager

/**
 * A Messages conversation with the person's iMessage chats in it (Blue Bubbles: [BlueBubblesLinks])
 * is one conversation, as on an iPhone: what the user does to it (Hide Alerts, archive, delete, read
 * or unread, pin), from its menu, its long press in the list or its details, is done to its iMessage
 * chats too ([companions]). Anything else is done to the conversation alone, as before.
 *
 * The suspend functions throw what the conversation's own backend throws; its iMessage chats are
 * done after it, so a refusal leaves them as they were. Not on the main thread where it says so.
 */
internal object MergedChats {

    /**
     * The iMessage chats shown inside [service]'s conversation [conversationId]: its person's iMessage
     * chat and their older ones ([BlueBubblesLinks.alsoShown]); none for any conversation but a
     * Messages one with Blue Bubbles on. Reads the archives: not on the main thread.
     */
    fun companions(context: Context, service: ChatService, conversationId: String): List<String> {
        if (service != ChatService.MESSAGES) return emptyList()
        return runCatching {
            val repo = ScreenBackends.get(context, ChatService.MESSAGES).repo
            val row = repo.conversation(conversationId) ?: return emptyList()
            val first = BlueBubblesLinks.iMessageFor(context, row, repo.participants(conversationId)) ?: return emptyList()
            (listOf(first) + BlueBubblesLinks.alsoShown(context, conversationId)).distinct()
        }.getOrDefault(emptyList())
    }

    private fun iMessage(context: Context): ChatBackend = ScreenBackends.get(context, ChatService.IMESSAGE)

    /** "Hide Alerts" ([muted]) or not, for [conversationId] of [on] and its iMessage chats [companions]. */
    suspend fun setMuted(context: Context, on: ChatBackend, conversationId: String, muted: Boolean, companions: List<String>) {
        on.setMuted(conversationId, muted)
        companions.forEach { iMessage(context).setMuted(it, muted) }
    }

    /** Archived (or back in the inbox), with its iMessage chats. */
    suspend fun setArchived(context: Context, on: ChatBackend, conversationId: String, archived: Boolean, companions: List<String>) {
        on.setArchived(conversationId, archived)
        companions.forEach { iMessage(context).setArchived(it, archived) }
    }

    /** Deleted, with its iMessage chats (an iMessage chat goes from this phone only, as ever). */
    suspend fun delete(context: Context, on: ChatBackend, conversationId: String, companions: List<String>) {
        on.deleteConversation(conversationId)
        companions.forEach { iMessage(context).deleteConversation(it) }
    }

    /** Read, with its iMessage chats (Apple is told of theirs, through the iMessage library). */
    suspend fun markRead(context: Context, on: ChatBackend, conversationId: String, companions: List<String>) {
        on.markRead(conversationId)
        companions.forEach { iMessage(context).markRead(it) }
    }

    /**
     * "Mark as Unread", with its iMessage chats: unread here until read again; an iMessage chat's
     * other devices are told too, through the iMessage library ([IMessageManager.markUnread]).
     */
    suspend fun markUnread(context: Context, on: ChatBackend, conversationId: String, companions: List<String>) {
        val all = listOf(on to conversationId) + companions.map { iMessage(context) to it }
        for ((backend, id) in all) {
            if (backend is IMessageManager) backend.markUnread(id) else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { backend.repo.markConversationUnread(id) }
        }
    }

    /** Pinned at the top of the list (or not), here, with its iMessage chats. */
    suspend fun setPinned(context: Context, on: ChatBackend, conversationId: String, pinned: Boolean, companions: List<String>) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            on.repo.setConversationPinned(conversationId, pinned)
            companions.forEach { iMessage(context).repo.setConversationPinned(it, pinned) }
        }
}
