package com.lightdecoder.ui.chat

import android.content.Context
import com.lightdecoder.archive.ArchiveChange
import com.lightdecoder.archive.ArchiveEvents
import com.lightdecoder.archive.ConversationRow
import com.lightdecoder.archive.ParticipantRow
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageAddress
import com.lightdecoder.imessage.IMessageLinks
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
        return pairs(context).entries.firstOrNull { it.value.messagesId == conversation.id }?.key
    }

    /** The Messages conversation iMessage chat [iMessageId] belongs inside, if there is one. */
    fun messagesFor(context: Context, iMessageId: String): String? {
        if (!IMessagePrefs.enabled(context)) return null
        return pairs(context)[iMessageId]?.messagesId
    }

    /** iMessage chats not inside any Messages conversation (one-to-one only), newest first: for linking by hand. */
    fun unpaired(context: Context): List<ConversationRow> {
        val im = IMessageManager.get(context)
        val paired = pairs(context).keys
        return runCatching { im.repo.conversations(null) }.getOrDefault(emptyList())
            .filter { !it.isGroup && it.folderEnum != Folder.DELETED && it.id !in paired }
            .sortedByDescending { it.lastMessageTs }
    }

    /** The other person's name in a one-to-one iMessage chat. */
    fun iMessageName(context: Context, iMessageId: String): String =
        runCatching { IMessageManager.get(context).repo.participants(iMessageId) }.getOrDefault(emptyList())
            .firstOrNull { !it.isMe }?.let { it.name.ifBlank { it.number } }.orEmpty()

    /** Every iMessage chat that belongs inside a Messages conversation, and how it was matched. */
    fun pairs(context: Context): Map<String, IMessageLinks.Pair> {
        listenOnce()
        return IMessageLinks.cachedOr(System.currentTimeMillis()) {
            val repo = ScreenBackends.get(context, ChatService.MESSAGES).repo
            val im = IMessageManager.get(context)
            val messages = runCatching {
                repo.conversations(null).filter { !it.isGroup && it.folderEnum != Folder.DELETED }.map { row ->
                    val people = repo.participants(row.id)
                    IMessageLinks.Chat(row.id, addressKey(people), row.name.ifBlank { people.firstOrNull { !it.isMe }?.name.orEmpty() })
                }
            }.getOrDefault(emptyList())
            val iMessages = runCatching {
                im.repo.conversations(null).filter { !it.isGroup && it.folderEnum != Folder.DELETED }.map { row ->
                    val other = im.repo.participants(row.id).firstOrNull { !it.isMe }
                    IMessageLinks.Chat(row.id, im.addressOf(row.id)?.let(IMessageAddress::key), other?.name.orEmpty().ifBlank { row.name })
                }
            }.getOrDefault(emptyList())
            IMessageLinks.pair(messages, iMessages, IMessageLinks.manual(context)).also { pairs ->
                val byName = pairs.count { it.value.how == IMessageLinks.How.NAME }
                val alone = iMessages.count { it.id !in pairs }
                if (byName + alone > 0) logOnce(context, "Links: ${pairs.size} iMessage chat(s) inside Messages conversations ($byName by name), $alone on their own")
            }
        }
    }

    @Volatile
    private var listening = false

    /** Any change to a conversation of either archive may change the pairs. */
    private fun listenOnce() {
        if (listening) return
        listening = true
        ArchiveEvents.register { if (it.kind == ArchiveChange.Kind.CONVERSATIONS) IMessageLinks.invalidate() }
    }

    @Volatile
    private var lastLogged = ""

    private fun logOnce(context: Context, line: String) {
        if (line == lastLogged) return
        lastLogged = line
        com.lightdecoder.imessage.IMessageLog.add(context, line)
    }
}
