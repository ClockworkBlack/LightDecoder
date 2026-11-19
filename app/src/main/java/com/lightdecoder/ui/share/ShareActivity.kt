package com.lightdecoder.ui.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.lightdecoder.R
import com.lightdecoder.chat.ChatBackend
import com.lightdecoder.chat.ShareShortcuts
import com.lightdecoder.databinding.ActivityShareBinding
import com.lightdecoder.relay.Folder
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.chat.applySystemBarAndImePadding
import com.lightdecoder.ui.chat.ChatUiPrefs
import com.lightdecoder.ui.chat.ConversationItem
import com.lightdecoder.ui.chat.ConversationTitles
import com.lightdecoder.ui.chat.ConversationsAdapter
import com.lightdecoder.ui.chat.ScreenBackends
import com.lightdecoder.ui.chat.ServiceUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "LightDecoder" in Android's share sheet: text, pictures, videos and sound
 * another app shares are sent to a conversation picked here.
 *
 * One thing (and any text) opens the conversation with it attached, for the
 * user to look at and send; several files are sent after one question.
 * Picking a conversation straight from the share sheet's top row
 * ([ShareShortcuts]) skips the list.
 *
 * Exported, so anything on the device can start it: it reads only the
 * standard share extras, takes only other apps' content: uris
 * ([SharedContent.from]), and nothing leaves without the user choosing whom
 * it goes to.
 */
class ShareActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShareBinding
    private lateinit var content: SharedContent
    private var backends: List<ChatBackend> = emptyList()
    private var backend: ChatBackend? = null
    private var all: List<ConversationItem> = emptyList()
    private var delivering = false
    private val adapter = ConversationsAdapter(onOpen = { deliver(backend ?: return@ConversationsAdapter, it) }, onLongPress = {})

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
        backends = ScreenBackends.all(this).filter { it.isPaired() }
        if (backends.isEmpty()) {
            stop(R.string.share_not_linked)
            return
        }
        // A conversation picked in the share sheet's top row: no list.
        val direct = ShareShortcuts.parse(intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID))
        val directBackend = direct?.let { (service, _) -> backends.firstOrNull { it.service == service } }
        if (direct != null && directBackend != null && savedInstanceState == null) {
            lifecycleScope.launch {
                val item = withContext(Dispatchers.IO) {
                    directBackend.repo.conversation(direct.second)
                        ?.takeIf { !it.readOnly }
                        ?.let { ConversationItem(it, ConversationTitles.of(this@ShareActivity, directBackend.repo, it), directBackend.service) }
                }
                if (item != null) deliver(directBackend, item) else showPicker()
            }
        } else {
            showPicker()
        }
    }

    private fun showPicker() {
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
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                from.repo.conversations(Folder.INBOX)
                    .filter { !it.readOnly }
                    .map { ConversationItem(it, ConversationTitles.of(this@ShareActivity, from.repo, it), from.service) }
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

    private fun deliver(to: ChatBackend, item: ConversationItem) {
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
                if (binding.list.adapter?.itemCount == 0 && all.isEmpty()) finish()
                return@launch
            }
            if (lost > 0) toast(R.string.share_some_not_read)
            if (copies.size <= 1) {
                open(to, item, copies.firstOrNull(), content.text)
            } else {
                askSendAll(to, item, copies)
            }
        }
    }

    /** Several files: one question, then they are all sent (a conversation holds one attachment at a time). */
    private fun askSendAll(to: ChatBackend, item: ConversationItem, copies: List<Uri>) {
        val counted = resources.getQuantityString(R.plurals.share_what_files, copies.size, copies.size)
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.share_send_many_question, counted, item.title))
            .setPositiveButton(R.string.share_send) { _, _ ->
                lifecycleScope.launch {
                    val id = item.row.id
                    val text = content.text
                    val failure = withContext(Dispatchers.IO) {
                        runCatching {
                            for (copy in copies) to.sendMedia(id, copy)
                            if (text.isNotEmpty()) to.sendText(id, text)
                        }.exceptionOrNull()
                    }
                    if (failure != null) {
                        Toast.makeText(this@ShareActivity, com.lightdecoder.ui.chat.ChatErrors.message(this@ShareActivity, to.service, failure), Toast.LENGTH_LONG).show()
                    }
                    open(to, item, null, "")
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> delivering = false }
            .setOnCancelListener { delivering = false }
            .show()
    }

    private fun open(to: ChatBackend, item: ConversationItem, attachment: Uri?, text: String) {
        val row = item.row
        val app = applicationContext
        lifecycleScope.launch(Dispatchers.IO) { ShareShortcuts.push(app, to.service, row.id, item.title, row.avatarColor) }
        startActivity(RelayIntents.threadWithShared(this, row.id, to.service, text, attachment))
        finish()
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
