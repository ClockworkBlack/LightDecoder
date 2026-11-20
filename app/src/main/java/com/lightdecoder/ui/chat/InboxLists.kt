package com.lightdecoder.ui.chat

import android.content.Context
import com.lightdecoder.chat.ChatBackend
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessagePrefs
import com.lightdecoder.relay.Folder

/**
 * A tab's conversation list, as the conversation list shows it: the one place it is put
 * together, so the share screen ([com.lightdecoder.ui.share.ShareActivity]) and the share
 * sheet's conversations ([com.lightdecoder.chat.ShareShortcuts]) list them in the same order,
 * with the same last message, as the Messages tab does.
 */
internal object InboxLists {

    /**
     * [from]'s conversations in [folder], with [iMessage]'s chats folded in when given (the
     * Messages tab with Blue Bubbles on, inbox and archive): each in its person's Messages
     * conversation, or a row of its own. Runs on IO.
     */
    fun items(context: Context, from: ChatBackend, folder: Folder, iMessage: ChatBackend?): List<ConversationItem> {
        val own = from.repo.conversations(folder).map { ConversationItem(it, ConversationTitles.of(context, from.repo, it), from.service) }
        val extra = if (iMessage == null) emptyList() else runCatching { iMessage.repo.conversations(folder) }.getOrDefault(emptyList())
            .map { ConversationItem(it, ConversationTitles.of(context, iMessage.repo, it), iMessage.service) }
        val listed = if (extra.isEmpty()) own else foldIn(own, extra, BlueBubblesLinks.pairs(context.applicationContext))
        // The pictures they are shown by: a group's photo, a person's picture.
        return runCatching { AvatarPhotos.withPhotos(context.applicationContext, listed) }.getOrDefault(listed)
    }

    /** [from]'s inbox as its tab lists it: for Messages with Blue Bubbles on, the iMessage chats folded in. Runs on IO. */
    fun inbox(context: Context, from: ChatBackend): List<ConversationItem> {
        val iMessage = if (from.service == ChatService.MESSAGES && IMessagePrefs.enabled(context)) {
            ScreenBackends.get(context, ChatService.IMESSAGE)
        } else {
            null
        }
        return items(context, from, Folder.INBOX, iMessage)
    }

    /**
     * The Messages rows with the iMessage chats folded in: a chat whose
     * person has a Messages conversation is shown as that conversation
     * (its newest message, time and unread mark taken over when newer, and
     * pinned or with its alerts hidden when either is), as on an iPhone;
     * the rest are rows of their own.
     */
    internal fun foldIn(
        own: List<ConversationItem>,
        iMessages: List<ConversationItem>,
        pairs: Map<String, com.lightdecoder.imessage.IMessageLinks.Pair>,
    ): List<ConversationItem> {
        // Messages conversation id -> the iMessage chats shown inside it (the person may have more than one).
        val byConversation = HashMap<String, MutableList<ConversationItem>>()
        for (item in iMessages) {
            val to = pairs[item.row.id]?.messagesId ?: continue
            byConversation.getOrPut(to) { ArrayList() } += item
        }
        if (byConversation.isEmpty()) return mergeByTime(own, iMessages)
        val folded = HashSet<String>()
        val merged = own.map { item ->
            if (item.row.isGroup) return@map item
            val chats = byConversation[item.row.id] ?: return@map item
            chats.forEach { folded += it.row.id }
            val im = chats.maxBy { it.row.lastMessageTs }
            val newer = im.row.lastMessageTs > item.row.lastMessageTs
            item.copy(
                row = item.row.copy(
                    unread = item.row.unread || chats.any { it.row.unread },
                    pinned = item.row.pinned || chats.any { it.row.pinned },
                    muted = item.row.muted || chats.any { it.row.muted },
                    lastMessageTs = maxOf(item.row.lastMessageTs, im.row.lastMessageTs),
                    snippet = if (newer) im.row.snippet else item.row.snippet,
                    snippetFromMe = if (newer) im.row.snippetFromMe else item.row.snippetFromMe,
                    snippetSender = if (newer) im.row.snippetSender else item.row.snippetSender,
                ),
            )
        }
        return mergeByTime(merged, iMessages.filter { it.row.id !in folded })
    }

    /**
     * Two services' rows as one list, in the archive's own order: pinned
     * first, then the newest last message first.
     */
    fun mergeByTime(a: List<ConversationItem>, b: List<ConversationItem>): List<ConversationItem> =
        (a + b).sortedWith(compareByDescending<ConversationItem> { it.row.pinned }.thenByDescending { it.row.lastMessageTs })
}
