package com.lightdecoder.ui.chat

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveChange
import com.lightdecoder.archive.ArchiveEvents
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.ConversationRow
import com.lightdecoder.archive.SearchHit
import com.lightdecoder.databinding.ActivityConversationsBinding
import com.lightdecoder.relay.BackfillProgress
import com.lightdecoder.relay.Folder
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.RelayManager
import com.lightdecoder.relay.RelayState
import com.lightdecoder.ui.applySystemBarPadding
import com.lightdecoder.ui.relay.LocalArchive
import com.lightdecoder.ui.relay.NotificationPermission
import com.lightdecoder.ui.relay.RelayErrors
import com.lightdecoder.ui.relay.RelaySetupActivity
import com.lightdecoder.ui.relay.RelayStatusActivity
import com.lightdecoder.ui.relay.SignInWebData
import com.lightdecoder.ui.relay.relayAttempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Google Messages conversation list: Inbox, Archived, Spam and the local
 * Deleted folder (what the phone deleted, kept here until restored or
 * deleted for good), full-text
 * search over the local archive, and the way into a thread. Everything shown
 * comes from the local archive, so it still works while the link is offline;
 * the banner on top says when it is.
 */
class ConversationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConversationsBinding
    private lateinit var manager: RelayManager
    private lateinit var repo: ArchiveRepository
    private lateinit var adapter: ConversationsAdapter
    private lateinit var searchAdapter: SearchResultsAdapter
    private lateinit var banner: ConnectionBanner
    private val notifications = NotificationPermission(this)

    private var folder = Folder.INBOX
    private var paired = true
    private var relayState = RelayState(RelayState.Phase.DISCONNECTED)

    /** Non-null while the search field is open. */
    private var searchQuery: String? = null
    private var restoredQuery: String? = null
    private var searchJob: Job? = null
    private var reloadScheduled = false

    /** Whether the Deleted folder had anything at its last load (for "Empty Deleted"). */
    private var hasDeleted = false

    private val onArchiveChange: (ArchiveChange) -> Unit = { change ->
        when (change.kind) {
            ArchiveChange.Kind.CONVERSATIONS -> scheduleReload()
            ArchiveChange.Kind.MESSAGES -> if (searchQuery != null) scheduleReload()
            else -> Unit
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Sign-in cookies a killed setup screen left behind (once per process; see SignInWebData).
        SignInWebData.wipeIfDirty(this)
        super.onCreate(savedInstanceState)
        binding = ActivityConversationsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarPadding(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        manager = RelayManager.get(this)
        repo = ArchiveRepository.get(this)
        banner = ConnectionBanner(this, binding.banner, manager, notifications)

        savedInstanceState?.let { state ->
            folder = Folder.entries.firstOrNull { it.name == state.getString(STATE_FOLDER) } ?: Folder.INBOX
            restoredQuery = state.getString(STATE_QUERY)
        }

        adapter = ConversationsAdapter(onOpen = { openThread(it.row.id, null) }, onLongPress = { showActions(it) })
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        searchAdapter = SearchResultsAdapter(onOpen = { openThread(it.conversationId, it.messageId) })
        binding.searchResults.layoutManager = LinearLayoutManager(this)
        binding.searchResults.adapter = searchAdapter
        binding.searchResults.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        setUpTabs()
        renderFolderControls()
        binding.swipe.setOnRefreshListener { refreshFromPhone() }
        binding.fab.setOnClickListener { startActivity(Intent(this, NewConversationActivity::class.java)) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { manager.state.collect { onState(it) } }
                launch { manager.backfill.collect { renderBackfill(it) } }
            }
        }
        if (savedInstanceState == null) checkSetUp()
    }

    override fun onStart() {
        super.onStart()
        ArchiveEvents.register(onArchiveChange)
        reload()
    }

    override fun onStop() {
        ArchiveEvents.unregister(onArchiveChange)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_FOLDER, folder.name)
        searchQuery?.let { outState.putString(STATE_QUERY, it) }
    }

    /**
     * Without a pairing and with nothing archived there is nothing to show,
     * so setup takes over. An archive kept after unpairing stays readable.
     */
    private fun checkSetUp() {
        lifecycleScope.launch {
            val (isPaired, hasArchive) = withContext(Dispatchers.IO) {
                manager.isPaired() to repo.conversations(null, 1).isNotEmpty()
            }
            if (!isPaired && !hasArchive) {
                startActivity(Intent(this@ConversationsActivity, RelaySetupActivity::class.java))
                finish()
            }
        }
    }

    private fun onState(state: RelayState) {
        lifecycleScope.launch {
            val wasPaired = paired
            paired = withContext(Dispatchers.IO) { manager.isPaired() }
            relayState = state
            banner.render(state, paired)
            binding.fab.isEnabled = paired
            if (paired != wasPaired) invalidateOptionsMenu()
        }
    }

    private fun renderBackfill(progress: BackfillProgress) {
        binding.backfillBar.visibility = if (progress.running) View.VISIBLE else View.GONE
        if (!progress.running) return
        binding.backfillText.text = if (progress.conversationsTotal > 0) {
            getString(R.string.chat_importing_count, progress.conversationsDone, progress.conversationsTotal)
        } else {
            getString(R.string.chat_importing)
        }
        if (progress.conversationsTotal > 0) {
            binding.backfillProgress.isIndeterminate = false
            binding.backfillProgress.max = progress.conversationsTotal
            binding.backfillProgress.setProgressCompat(progress.conversationsDone, true)
        } else {
            binding.backfillProgress.isIndeterminate = true
        }
    }

    // --- folders -------------------------------------------------------------

    private fun setUpTabs() {
        val tabs = binding.folders
        FOLDERS.forEach { (f, label) ->
            tabs.addTab(tabs.newTab().setText(label).setTag(f), f == folder)
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                folder = tab.tag as Folder
                adapter.submitList(emptyList())
                renderFolderControls()
                reload()
                // Archived and Spam are not part of the automatic sync; fetch them when opened.
                // Deleted is local only: the phone has nothing to list for it.
                if (folder != Folder.INBOX && folder.listable && relayState.isOnline) refreshFromPhone(quiet = true)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) {
                binding.list.smoothScrollToPosition(0)
            }
        })
    }

    /** Pull to refresh and the menu's refresh ask the phone, which has nothing for the local Deleted folder. */
    private fun renderFolderControls() {
        binding.swipe.isEnabled = folder.listable
        invalidateOptionsMenu()
    }

    // --- loading -------------------------------------------------------------

    /** Changes come in bursts (a sync writes many rows); reload at most every [RELOAD_DEBOUNCE_MS]. */
    private fun scheduleReload() {
        if (reloadScheduled) return
        reloadScheduled = true
        lifecycleScope.launch {
            delay(RELOAD_DEBOUNCE_MS)
            reloadScheduled = false
            reload()
        }
    }

    private fun reload() {
        val query = searchQuery
        if (query != null) {
            runSearch(query, debounce = false)
            return
        }
        val shown = folder
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { repo.conversations(shown).map { ConversationItem(it, titleFor(it)) } }
            if (shown != folder || searchQuery != null) return@launch
            adapter.submitList(items)
            if (shown == Folder.DELETED && hasDeleted != items.isNotEmpty()) {
                hasDeleted = items.isNotEmpty()
                invalidateOptionsMenu()
            }
            binding.emptyText.setText(
                when (shown) {
                    Folder.INBOX -> if (paired) R.string.chat_empty_inbox else R.string.chat_empty_unpaired
                    Folder.ARCHIVE -> R.string.chat_empty_archive
                    Folder.SPAM -> R.string.chat_empty_spam
                    Folder.DELETED -> R.string.chat_empty_deleted
                }
            )
            binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /** The phone's name for the conversation, else its people, else a placeholder. Runs on IO. */
    private fun titleFor(row: ConversationRow): String {
        if (row.name.isNotBlank()) return row.name
        val people = repo.participants(row.id).filter { !it.isMe }
            .map { it.name.ifBlank { it.formattedNumber.ifBlank { it.number } } }
            .filter { it.isNotBlank() }
        return if (people.isEmpty()) getString(R.string.chat_untitled) else people.joinToString(", ")
    }

    private fun refreshFromPhone(quiet: Boolean = false) {
        val shown = folder
        if (!shown.listable) {
            binding.swipe.isRefreshing = false
            return
        }
        lifecycleScope.launch {
            if (!quiet) binding.swipe.isRefreshing = true
            relayAttempt { manager.refreshConversations(shown) }
                .onFailure { if (!quiet) snackbar(RelayErrors.message(this@ConversationsActivity, it)) }
            binding.swipe.isRefreshing = false
        }
    }

    // --- search --------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.conversations_actions, menu)
        val item = menu.findItem(R.id.action_search)
        val searchView = item.actionView as SearchView
        searchView.queryHint = getString(R.string.chat_search_hint)
        searchView.maxWidth = Int.MAX_VALUE
        item.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                enterSearch()
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                exitSearch()
                return true
            }
        })
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                searchView.clearFocus()
                runSearch(query, debounce = false)
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                if (searchQuery != null) runSearch(newText, debounce = true)
                return true
            }
        })
        restoredQuery?.let { query ->
            restoredQuery = null
            item.expandActionView()
            searchView.setQuery(query, false)
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // Without a pairing, what is listed is an archive an unpair kept: it can be deleted here.
        menu.findItem(R.id.action_delete_archive)?.isVisible = !paired
        menu.findItem(R.id.action_refresh)?.isVisible = paired && folder.listable
        menu.findItem(R.id.action_empty_deleted)?.isVisible = folder == Folder.DELETED && hasDeleted && searchQuery == null
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.action_refresh -> refreshFromPhone()
            R.id.action_link_status -> startActivity(Intent(this, RelayStatusActivity::class.java))
            R.id.action_delete_archive -> LocalArchive.confirmDelete(this) { reload() }
            R.id.action_empty_deleted -> confirmEmptyDeleted()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun enterSearch() {
        searchQuery = ""
        invalidateOptionsMenu()
        binding.folders.visibility = View.GONE
        binding.swipe.visibility = View.GONE
        binding.fab.hide()
        binding.searchResults.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        searchAdapter.submitList(emptyList())
    }

    private fun exitSearch() {
        searchQuery = null
        invalidateOptionsMenu()
        searchJob?.cancel()
        binding.folders.visibility = View.VISIBLE
        binding.swipe.visibility = View.VISIBLE
        binding.searchResults.visibility = View.GONE
        binding.fab.show()
        reload()
    }

    private fun runSearch(query: String, debounce: Boolean) {
        searchQuery = query
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MS)
            val hits: List<SearchHit> =
                if (query.isBlank()) emptyList() else withContext(Dispatchers.IO) { repo.search(query) }
            if (searchQuery != query) return@launch
            searchAdapter.submitList(hits)
            binding.emptyText.setText(R.string.chat_search_empty)
            binding.emptyText.visibility = if (hits.isEmpty() && query.isNotBlank()) View.VISIBLE else View.GONE
        }
    }

    // --- actions -------------------------------------------------------------

    private fun openThread(conversationId: String, messageId: String?) {
        startActivity(
            Intent(this, ThreadActivity::class.java)
                .putExtra(RelayIntents.EXTRA_CONVERSATION_ID, conversationId)
                .apply { if (messageId != null) putExtra(RelayIntents.EXTRA_MESSAGE_ID, messageId) }
        )
    }

    private fun showActions(item: ConversationItem) {
        val row = item.row
        val actions = ArrayList<Pair<Int, () -> Unit>>()
        when (row.folderEnum) {
            Folder.INBOX -> actions += R.string.chat_action_archive to { setArchived(row, true) }
            Folder.ARCHIVE -> actions += R.string.chat_action_unarchive to { setArchived(row, false) }
            Folder.SPAM -> actions += R.string.chat_action_not_spam to { markNotSpam(row) }
            else -> Unit
        }
        if (row.folderEnum == Folder.DELETED) {
            // The phone already deleted it: only this device's copy is left to decide on.
            actions += R.string.chat_action_restore to { restoreDeleted(row) }
            actions += R.string.chat_action_delete_forever to { confirmDeleteForever(item) }
        } else {
            actions += (if (row.muted) R.string.chat_action_unmute else R.string.chat_action_mute) to { setMuted(row, !row.muted) }
            actions += R.string.chat_action_delete to { confirmDelete(item) }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(item.title)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun setArchived(row: ConversationRow, archived: Boolean) = runAction(
        if (archived) R.string.chat_archived else R.string.chat_unarchived,
    ) { manager.setArchived(row.id, archived) }

    private fun markNotSpam(row: ConversationRow) = runAction(R.string.chat_not_spam_done) { manager.markNotSpam(row.id) }

    private fun restoreDeleted(row: ConversationRow) = runAction(R.string.chat_restored) { manager.restoreDeletedLocally(row.id) }

    /** Deleted for good, here only: the phone already deleted it. */
    private fun confirmDeleteForever(item: ConversationItem) {
        val id = item.row.id
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_delete_forever_title)
            .setMessage(R.string.chat_delete_forever_body)
            .setPositiveButton(R.string.chat_action_delete_forever) { _, _ ->
                runAction(R.string.chat_deleted_forever) { manager.purgeDeleted(id) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Everything in the Deleted folder, for good, here only. */
    private fun confirmEmptyDeleted() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_empty_deleted_title)
            .setMessage(R.string.chat_empty_deleted_body)
            .setPositiveButton(R.string.chat_empty_deleted_action) { _, _ ->
                runAction(R.string.chat_emptied_deleted) { manager.purgeDeleted(null) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setMuted(row: ConversationRow, muted: Boolean) = runAction(
        if (muted) R.string.chat_muted_done else R.string.chat_unmuted_done,
    ) { manager.setMuted(row.id, muted) }

    /**
     * With a pairing it goes on the phone too; without one (an archive an
     * unpair kept) the manager deletes it here only. If the pairing changed
     * while the question was up, it is asked again with the right wording.
     */
    private fun confirmDelete(item: ConversationItem) {
        val linked = manager.isPaired()
        val id = item.row.id
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_delete_conversation_title)
            .setMessage(if (linked) R.string.chat_delete_conversation_body else R.string.chat_delete_conversation_body_local)
            .setPositiveButton(R.string.chat_action_delete) { _, _ ->
                if (manager.isPaired() != linked) {
                    confirmDelete(item)
                    return@setPositiveButton
                }
                runAction(R.string.chat_deleted) { manager.deleteConversation(id) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runAction(doneMessage: Int, block: suspend () -> Unit) {
        lifecycleScope.launch {
            relayAttempt { block() }
                .onSuccess { snackbar(getString(doneMessage)) }
                .onFailure { snackbar(RelayErrors.message(this@ConversationsActivity, it)) }
        }
    }

    private fun snackbar(message: String) {
        val bar = Snackbar.make(binding.coordinator, message, Snackbar.LENGTH_LONG)
        if (binding.fab.isShown) bar.setAnchorView(binding.fab)
        bar.show()
    }

    companion object {
        private const val STATE_FOLDER = "folder"
        private const val STATE_QUERY = "query"
        private const val RELOAD_DEBOUNCE_MS = 200L
        private const val SEARCH_DEBOUNCE_MS = 250L

        private val FOLDERS = listOf(
            Folder.INBOX to R.string.chat_folder_inbox,
            Folder.ARCHIVE to R.string.chat_folder_archive,
            Folder.SPAM to R.string.chat_folder_spam,
            Folder.DELETED to R.string.chat_folder_deleted,
        )
    }
}
