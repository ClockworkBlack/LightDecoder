package com.lightdecoder.ui.share

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveChange
import com.lightdecoder.archive.ArchiveEvents
import com.lightdecoder.chat.ChatBackend
import com.lightdecoder.chat.ChatService
import com.lightdecoder.chat.ShareShortcuts
import com.lightdecoder.databinding.ActivityShareBinding
import com.lightdecoder.ui.chat.ChatUiPrefs
import com.lightdecoder.ui.chat.ConversationItem
import com.lightdecoder.ui.chat.ConversationsAdapter
import com.lightdecoder.ui.chat.InboxLists
import com.lightdecoder.ui.chat.ServiceUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The list a conversation is picked from to send something to (the share screen, [ShareActivity], and
 * Forward, [ForwardActivity]): a tab per paired service ([backends]), each its inbox as the
 * conversation list shows it ([InboxLists.inbox]: in Messages with Blue Bubbles on, a person's
 * iMessage chat folded into their Messages conversation), only the conversations that can be written
 * in ([keep]), a field to look one up by name, and the list following changes while it is open
 * ([onStart] / [onStop]). A tap on one is [onPick].
 */
class ConversationPicker(
    private val activity: AppCompatActivity,
    private val binding: ActivityShareBinding,
    private val backends: List<ChatBackend>,
    private val onPick: (ConversationItem) -> Unit,
    private val keep: (ConversationItem) -> Boolean = { !it.row.readOnly },
    /** Whether the inbox read is handed to the share sheet's top row ([ShareShortcuts.publishInbox]). */
    private val publishShortcuts: Boolean = true,
) {

    val adapter = ConversationsAdapter(onOpen = { onPick(it) }, onLongPress = {})

    /** Whether the list is up ([start]). */
    var listing = false
        private set

    private var backend: ChatBackend? = null
    private var all: List<ConversationItem> = emptyList()
    private var reloadScheduled = false

    /** A conversation of the tab shown, or (in Messages) of its iMessage chats, changed: the list follows. */
    private val onArchiveChange: (ArchiveChange) -> Unit = { change ->
        val shown = backend
        if (listing && shown != null && change.kind == ArchiveChange.Kind.CONVERSATIONS &&
            (change.service == shown.service || (change.service == ChatService.IMESSAGE && shown.service == ChatService.MESSAGES))
        ) {
            scheduleReload()
        }
    }

    init {
        binding.list.adapter = adapter
    }

    /** The tabs and the list, the last service used first. */
    fun start() {
        if (backends.isEmpty()) return
        listing = true
        val first = backends.firstOrNull { it.service == ChatUiPrefs.lastService(activity) } ?: backends.first()
        if (backends.size > 1) {
            binding.services.visibility = View.VISIBLE
            for (b in backends) {
                val tab = binding.services.newTab().setText(ServiceUi.tabLabel(b.service)).setTag(b)
                binding.services.addTab(tab, b === first)
            }
            binding.services.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) = show(tab.tag as ChatBackend)
                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }
        binding.query.doAfterTextChanged { filter() }
        show(first)
    }

    fun onStart() {
        ArchiveEvents.register(onArchiveChange)
        // What changed while it was hidden.
        if (listing) scheduleReload()
    }

    fun onStop() {
        ArchiveEvents.unregister(onArchiveChange)
    }

    private fun show(from: ChatBackend) {
        backend = from
        all = emptyList()
        adapter.submitList(emptyList())
        load(from)
    }

    /** Changes come in bursts (a sync writes many rows): the list is read again at most every [RELOAD_DEBOUNCE_MS]. */
    private fun scheduleReload() {
        if (reloadScheduled) return
        reloadScheduled = true
        activity.lifecycleScope.launch {
            delay(RELOAD_DEBOUNCE_MS)
            reloadScheduled = false
            backend?.let(::load)
        }
    }

    private fun load(from: ChatBackend) {
        activity.lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching {
                    InboxLists.inbox(activity, from)
                        .also { if (publishShortcuts) ShareShortcuts.publishInbox(activity.applicationContext, from.service, it) }
                        .filter(keep)
                }.getOrDefault(emptyList())
            }
            if (backend !== from) return@launch
            all = items
            filter()
        }
    }

    private fun filter() {
        val query = binding.query.text?.toString()?.trim().orEmpty()
        val shown = if (query.isEmpty()) all else all.filter { it.title.contains(query, ignoreCase = true) }
        adapter.submitList(shown)
        binding.empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        if (shown.isEmpty()) binding.empty.setText(if (all.isEmpty()) emptyText else R.string.share_no_match)
    }

    /** What the list says when there is nothing to pick. */
    var emptyText: Int = R.string.share_no_conversations

    private companion object {
        const val RELOAD_DEBOUNCE_MS = 200L
    }
}
