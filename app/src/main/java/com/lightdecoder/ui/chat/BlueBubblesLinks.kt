package com.lightdecoder.ui.chat

import android.content.Context
import com.lightdecoder.archive.ConversationRow
import com.lightdecoder.archive.ParticipantRow
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageAddress
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.IMessagePrefs
import com.lightdecoder.relay.Folder

/**
 * With "Blue Bubbles" on, a person's iMessages show in their Messages
 * conversation, as on an iPhone: the iMessage chat with the same phone
 * number (or email address) is that conversation's "companion". The two
 * stay in their own archives; only the screens put them together.
 *
 * Everything here reads archives: not on the main thread.
 */
internal object BlueBubblesLinks {

    /** The one other person's address in a one-to-one conversation, compared loosely; null otherwise. */
    fun addressKey(participants: List<ParticipantRow>): String? {
        val other = participants.filter { !it.isMe }.singleOrNull() ?: return null
        return IMessageAddress.key(other.number.ifBlank { other.formattedNumber })
    }

    /** The iMessage chat to show inside the Messages conversation [conversation], if Blue Bubbles is on and there is one. */
    fun iMessageFor(context: Context, conversation: ConversationRow?, participants: List<ParticipantRow>): String? {
        if (!IMessagePrefs.enabled(context) || conversation == null || conversation.isGroup) return null
        val other = participants.filter { !it.isMe }.singleOrNull() ?: return null
        val address = other.number.ifBlank { other.formattedNumber }.takeIf { IMessageAddress.key(it) != null } ?: return null
        return runCatching { IMessageManager.get(context).conversationWith(address) }.getOrNull()
    }

    /** The Messages conversation iMessage chat [iMessageId] belongs inside, if there is one. */
    fun messagesFor(context: Context, iMessageId: String): String? {
        if (!IMessagePrefs.enabled(context)) return null
        val key = runCatching { IMessageManager.get(context).addressOf(iMessageId) }.getOrNull()?.let(IMessageAddress::key) ?: return null
        val repo = ScreenBackends.get(context, ChatService.MESSAGES).repo
        return runCatching {
            repo.conversations(null)
                .filter { !it.isGroup && it.folderEnum != Folder.DELETED }
                .firstOrNull { addressKey(repo.participants(it.id)) == key }
                ?.id
        }.getOrNull()
    }
}
