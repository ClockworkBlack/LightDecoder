package com.lightdecoder.ui.chat

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lightdecoder.R
import com.lightdecoder.archive.ConversationRow
import com.lightdecoder.databinding.ItemConversationBinding

/**
 * One row of the list: the stored conversation, the title worked out for it,
 * and the service it is from (the Messages tab also lists iMessage chats).
 */
data class ConversationItem(
    val row: ConversationRow,
    val title: String,
    val service: com.lightdecoder.chat.ChatService = com.lightdecoder.chat.ChatService.MESSAGES,
    /** The picture it is shown by (an iMessage group's photo, a person's contact picture: [AvatarPhotos]); "" for its initials. */
    val photo: String = "",
)

/** What a conversation is called in a list: its name, else the people in it. */
internal object ConversationTitles {
    /** Reads the participants when the conversation has no name: not on the main thread. */
    fun of(context: android.content.Context, repo: com.lightdecoder.archive.ArchiveRepository, row: ConversationRow): String {
        if (row.name.isNotBlank()) return row.name
        val people = repo.participants(row.id).filter { !it.isMe }
            .map { it.name.ifBlank { it.formattedNumber.ifBlank { it.number } } }
            .filter { it.isNotBlank() }
        return if (people.isEmpty()) context.getString(R.string.chat_untitled) else people.joinToString(", ")
    }
}

/** The conversation list: avatar, name, last message, time, unread dot, pin and mute marks. */
class ConversationsAdapter(
    private val onOpen: (ConversationItem) -> Unit,
    private val onLongPress: (ConversationItem) -> Unit,
) : ListAdapter<ConversationItem, ConversationsAdapter.Holder>(DIFF) {

    private val ids = StableIds()

    init {
        setHasStableIds(true)
    }

    class Holder(val binding: ItemConversationBinding) : RecyclerView.ViewHolder(binding.root)

    // A conversation id is only unique within its service.
    override fun getItemId(position: Int): Long = getItem(position).let { ids.of(it.service.key + ":" + it.row.id) }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemConversationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val row = item.row
        val b = holder.binding
        val context = b.root.context

        Avatars.bind(b.avatar, item.title, row.id, row.avatarColor, item.photo.takeIf { it.isNotEmpty() }?.let { java.io.File(it) })
        b.name.text = item.title
        b.name.setTypeface(null, if (row.unread) Typeface.BOLD else Typeface.NORMAL)
        val snippet = snippetText(holder, row)
        b.snippet.text = snippet
        b.snippet.setTypeface(null, if (row.unread) Typeface.BOLD else Typeface.NORMAL)
        val time = TimeFormat.listTime(context, row.lastMessageTs)
        b.time.text = time
        b.unreadDot.visibility = if (row.unread) View.VISIBLE else View.GONE
        b.pinned.visibility = if (row.pinned) View.VISIBLE else View.GONE
        b.muted.visibility = if (row.muted) View.VISIBLE else View.GONE
        val iMessage = item.service == com.lightdecoder.chat.ChatService.IMESSAGE
        b.imessageBadge.visibility = if (iMessage) View.VISIBLE else View.GONE

        // One spoken summary instead of five separate bits.
        b.root.contentDescription = buildList {
            add(item.title)
            if (iMessage) add(context.getString(R.string.imessage_badge))
            if (row.unread) add(context.getString(R.string.chat_unread))
            if (row.pinned) add(context.getString(R.string.chat_pinned))
            if (row.muted) add(context.getString(R.string.chat_muted))
            if (snippet.isNotEmpty()) add(snippet)
            if (time.isNotEmpty()) add(time)
        }.joinToString(", ")
        b.root.setOnClickListener { onOpen(item) }
        b.root.setOnLongClickListener {
            onLongPress(item)
            true
        }
    }

    private fun snippetText(holder: Holder, row: ConversationRow): String {
        val context = holder.binding.root.context
        val text = row.snippet.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val body = when {
            text.isNotEmpty() -> text
            row.lastMessageTs > 0 -> context.getString(R.string.chat_snippet_attachment)
            else -> return context.getString(R.string.chat_snippet_none)
        }
        return when {
            row.snippetFromMe -> context.getString(R.string.chat_snippet_you, body)
            row.isGroup && row.snippetSender.isNotBlank() ->
                context.getString(R.string.chat_snippet_sender, row.snippetSender.trim().substringBefore(' '), body)
            else -> body
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ConversationItem>() {
            override fun areItemsTheSame(a: ConversationItem, b: ConversationItem) = a.service == b.service && a.row.id == b.row.id
            override fun areContentsTheSame(a: ConversationItem, b: ConversationItem) = a == b
        }
    }
}
