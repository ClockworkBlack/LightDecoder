package com.lightdecoder.ui.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveChange
import com.lightdecoder.archive.ArchiveEvents
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessagePrefs
import com.lightdecoder.chat.ChatBackend
import com.lightdecoder.chat.ShareShortcuts
import com.lightdecoder.databinding.ActivityShareBinding
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.chat.applySystemBarAndImePadding
import com.lightdecoder.ui.chat.ChatUiPrefs
import com.lightdecoder.ui.chat.ConversationItem
import com.lightdecoder.ui.chat.ConversationTitles
import com.lightdecoder.ui.chat.ConversationsAdapter
import com.lightdecoder.ui.chat.InboxLists
import com.lightdecoder.ui.chat.ScreenBackends
import com.lightdecoder.ui.chat.ServiceUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "LightDecoder" in Android's share sheet: text, pictures, videos and sound
 * another app shares go to a conversation picked here.
 *
 * The list is the conversation list's own ([InboxLists]): in the Messages tab
 * with Blue Bubbles on, a person's iMessage chat is folded into their Messages
 * conversation (its newest message, time and snippet when newer) and the other
 * iMessage chats are rows of their own, in the same order as the tab shows
 * them; it follows changes while it is open. Picking a conversation sends
 * nothing: it opens with what was shared in its composer (the text in the
 * field, every file attached), for the user to look at and send, as any
 * message from there (a Messages conversation with an iMessage chat sends as
 * iMessage). Picking a conversation straight from the share sheet's top row
 * ([ShareShortcuts]) skips the list.
 *
 * Exported, so anything on the device can start it: it reads only the
 * standard share extras, takes only other apps' content: uris
 * ([SharedContent.from]), and nothing leaves without the user sending it.
 */
class ShareActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShareBinding
    private lateinit var content: SharedContent
    private var backends: List<ChatBackend> = emptyList()
    private var backend: ChatBackend? = null
    private var all: List<ConversationItem> = emptyList()
    private var delivering = false
    private var listing = false
    private var reloadScheduled = false
    private val adapter = ConversationsAdapter(onOpen = { deliver(it) }, onLongPress = {})

    /** A conversation of the tab shown, or (in Messages) of its iMessage chats, changed: the list follows. */
    private val onArchiveChange: (ArchiveChange) -> Unit = { change ->
        val shown = backend
        if (listing && shown != null && change.kind == ArchiveChange.Kind.CONVERSATIONS &&
            (change.service == shown.service || (change.service == ChatService.IMESSAGE && shown.service == ChatService.MESSAGES))
        ) {
            scheduleReload()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShareBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarAndImePadding(binding.root)
        binding.toolbar.setNavigationIcon(R.drawable.ic_close)
        binding.toolbar.setNavigationContentDescription(android.R.string.cancel)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.list.adapter = adapter

        content = SharedContent.from(this, intent)
        if (content.isEmpty) {
            stop(R.string.share_nothing)
            return
        }
        binding.what.text = describe(content)
        backends = ScreenBackends.all(this).filter { it.isPaired() || (it.service == ChatService.MESSAGES && iMessageOn()) }
        if (backends.isEmpty()) {
            stop(R.string.share_not_linked)
            return
        }
        // A conversation picked in the share sheet's top row: no list.
        val direct = ShareShortcuts.parse(intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID))
        val directBackend = direct?.let { (service, _) -> backendOf(service) }
        if (direct != null && directBackend != null && savedInstanceState == null) {
            lifecycleScope.launch {
                val item = withContext(Dispatchers.IO) {
                    directBackend.repo.conversation(direct.second)
                        ?.takeIf { !it.readOnly }
                        ?.let { ConversationItem(it, ConversationTitles.of(this@ShareActivity, directBackend.repo, it), directBackend.service) }
                }
                if (item != null) deliver(item) else showPicker()
            }
        } else {
            showPicker()
        }
    }

    override fun onStart() {
        super.onStart()
        ArchiveEvents.register(onArchiveChange)
        // What changed while it was hidden.
        if (listing) scheduleReload()
    }

    override fun onStop() {
        ArchiveEvents.unregister(onArchiveChange)
        super.onStop()
    }

    /** Blue Bubbles on, with iMessage set up: its chats are in the Messages tab. */
    private fun iMessageOn(): Boolean =
        IMessagePrefs.enabled(this) && ScreenBackends.get(this, ChatService.IMESSAGE).isPaired()

    /** The backend a shortcut's conversation is of: a tab's, or iMessage's (listed in Messages). */
    private fun backendOf(service: ChatService): ChatBackend? =
        if (service == ChatService.IMESSAGE) ScreenBackends.get(this, service).takeIf { iMessageOn() }
        else backends.firstOrNull { it.service == service }

    private fun showPicker() {
        listing = true
        val first = backends.firstOrNull { it.service == ChatUiPrefs.lastService(this) } ?: backends.first()
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
        lifecycleScope.launch {
            delay(RELOAD_DEBOUNCE_MS)
            reloadScheduled = false
            backend?.let(::load)
        }
    }

    private fun load(from: ChatBackend) {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching {
                    InboxLists.inbox(this@ShareActivity, from)
                        .also { ShareShortcuts.publishInbox(applicationContext, from.service, it) }
                        .filter { !it.row.readOnly }
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
        if (shown.isEmpty()) binding.empty.setText(if (all.isEmpty()) R.string.share_no_conversations else R.string.share_no_match)
    }

    /** Nothing to pick from: says why, with only "close" left. */
    private fun stop(message: Int) {
        binding.what.visibility = View.GONE
        binding.queryBox.visibility = View.GONE
        binding.list.visibility = View.GONE
        binding.empty.visibility = View.VISIBLE
        binding.empty.setText(message)
    }

    private fun describe(content: SharedContent): String {
        val files = content.streams.size
        val counted = resources.getQuantityString(R.plurals.share_what_files, files, files)
        return when {
            files == 0 -> content.text
            content.text.isEmpty() -> counted
            else -> getString(R.string.share_what_files_and_text, counted, content.text)
        }
    }

    /**
     * The conversation [item] opens with what was shared in its composer. The files are copied
     * here first: the sharing app's permission to read them ends with this screen.
     */
    private fun deliver(item: ConversationItem) {
        if (delivering) return
        delivering = true
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val copies = withContext(Dispatchers.IO) { SharedContent.copyIn(applicationContext, content.streams) }
            binding.progress.visibility = View.INVISIBLE
            val lost = content.streams.size - copies.size
            if (copies.isEmpty() && content.text.isEmpty()) {
                delivering = false
                toast(R.string.share_could_not_read)
                // Straight from the share sheet there is no list behind this: nothing more to do here.
                if (!listing) finish()
                return@launch
            }
            if (lost > 0) toast(R.string.share_some_not_read)
            open(item, copies, content.text)
        }
    }

    private fun open(item: ConversationItem, attachments: List<Uri>, text: String) {
        val row = item.row
        val app = applicationContext
        lifecycleScope.launch(Dispatchers.IO) { ShareShortcuts.push(app, item.service, row.id, item.title, row.avatarColor) }
        // In LightDecoder's own task (this screen is the share's, out of recents): back from the
        // conversation goes to the conversation list.
        startActivity(RelayIntents.threadWithShared(this, row.id, item.service, text, attachments).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val RELOAD_DEBOUNCE_MS = 200L
    }
}
