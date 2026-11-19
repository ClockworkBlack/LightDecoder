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
import com.lightdecoder.chat.Capability
import com.lightdecoder.chat.ChatBackend
import com.lightdecoder.chat.ChatService
import com.lightdecoder.chat.Features
import com.lightdecoder.discord.DiscordProcess
import com.lightdecoder.ui.discord.DiscordActivity
import com.lightdecoder.databinding.ActivityConversationsBinding
import com.lightdecoder.relay.BackfillProgress
import com.lightdecoder.relay.Folder
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.RelayState
import com.lightdecoder.ui.applySystemBarPadding
import com.lightdecoder.ui.relay.LocalArchive
import com.lightdecoder.ui.relay.SignInWebData
import com.lightdecoder.ui.relay.relayAttempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.EnumMap

/**
 * The conversation list of both services: a tab each for Google Messages and
 * WhatsApp (with its count of unread conversations), and under it the
 * folders of the service shown: Inbox and Archived, and for Google Messages
 * also Spam and the local Deleted folder (what the phone deleted, kept here
 * until restored or deleted for good). Full-text search covers the service
 * shown, and a conversation opens its thread.
 *
 * Everything listed comes from that service's local archive, so it still
 * works while its link is offline; the banner on top says when it is. A
 * service that is not linked and has nothing stored shows a card that leads
 * to its setup instead.
 *
 * With "Blue Bubbles" ticked in its menu ([BlueBubblesSetup]), the Messages
 * tab also lists the iMessage chats that arrived through the OpenBubbles app
 * ([ChatService.IMESSAGE], which has no tab of its own), merged by time and
 * marked iMessage; each row keeps its own service for opening and actions.
 *
 * Extras: [RelayIntents.EXTRA_SERVICE] opens on that service's tab (iMessage
 * opens on Messages). Without
 * it the list opens where it was last; and if no service is linked or has
 * anything stored, there is nothing to show, so setup takes over.
 */
class ConversationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConversationsBinding

    /** The service whose tab is shown. Everything on screen follows it. */
    private lateinit var backend: ChatBackend
    private val repo: ArchiveRepository get() = backend.repo
    private lateinit var adapter: ConversationsAdapter
    private lateinit var searchAdapter: SearchResultsAdapter
    private lateinit var banner: ConnectionBanner
    private val notifications = ServiceNotifications(this)

    private var folder = Folder.INBOX

    /** The folder each service was left in, for coming back to its tab. */
    private val folderOf = EnumMap<ChatService, Folder>(ChatService::class.java)
    private var buildingFolderTabs = false
    private var started = false

    private var paired = true

    /** The shown link's state; null until it has been read (at first, and after a change of tab). */
    private var relayState: RelayState? = null

    /** Whether the list has nothing at all to show in any folder (the shown service's archive, and iMessage's with it), as of its last load. */
    private var archiveEmpty = false

    /** Whether the shown service's own archive holds no conversation at all (what "Delete archive" would delete). */
    private var ownArchiveEmpty = false

    /** Non-null while the search field is open. */
    private var searchQuery: String? = null
    private var restoredQuery: String? = null
    private var searchItem: MenuItem? = null
    private var optionsMenu: Menu? = null
    private var searchJob: Job? = null
    private var reloadScheduled = false
    private var badgesScheduled = false

    /** Whether the Deleted folder had anything at its last load (for "Empty Deleted"). */
    private var hasDeleted = false

    private val blueBubbles = BlueBubblesSetup(this) {
        invalidateOptionsMenu()
        reload()
        renderBadges()
    }

    /** Whether the list shows iMessage chats too: the Messages tab, with Blue Bubbles on. */
    private fun showsIMessage(): Boolean = backend.service == ChatService.MESSAGES && blueBubbles.isOn()

    private val onArchiveChange: (ArchiveChange) -> Unit = { change ->
        // The other service's conversations only change its tab's unread count.
        if (change.kind == ArchiveChange.Kind.CONVERSATIONS) scheduleBadges()
        if (change.service == backend.service || (change.service == ChatService.IMESSAGE && showsIMessage())) {
            when (change.kind) {
                ArchiveChange.Kind.CONVERSATIONS -> scheduleReload()
                ArchiveChange.Kind.MESSAGES -> if (searchQuery != null) scheduleReload()
                else -> Unit
            }
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
        // The title is the app switcher.
        supportActionBar?.setDisplayShowTitleEnabled(false)

        val asked = intent.hasExtra(RelayIntents.EXTRA_SERVICE)
        val service = tabServiceOf(
            savedInstanceState?.getString(STATE_SERVICE)?.let(ChatService::fromKey)
                ?: if (asked) RelayIntents.serviceOf(intent) else ChatUiPrefs.defaultService(this)
        )
        backend = ScreenBackends.get(this, service)
        // A tab that was asked for is the one to come back to; the default is not a choice.
        if (asked && savedInstanceState == null) ChatUiPrefs.setLastService(this, service)
        banner = ConnectionBanner(this, binding.banner, backend, notifications)

        savedInstanceState?.let { state ->
            folder = Folder.entries.firstOrNull { it.name == state.getString(STATE_FOLDER) } ?: Folder.INBOX
            restoredQuery = state.getString(STATE_QUERY)
        }
        blueBubbles.restore(savedInstanceState)

        adapter = ConversationsAdapter(onOpen = { openThread(it.row.id, null, it.service) }, onLongPress = { showActions(it) })
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        searchAdapter = SearchResultsAdapter(onOpen = { openThread(it.conversationId, it.messageId, it.service) })
        binding.searchResults.layoutManager = LinearLayoutManager(this)
        binding.searchResults.adapter = searchAdapter
        binding.searchResults.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        setUpServiceTabs()
        setUpFolderTabs()
        renderFolderControls()
        binding.swipe.setOnRefreshListener { refreshFromPhone() }
        binding.fab.setOnClickListener {
            if (backend.service == ChatService.GMAIL) {
                // An email needs a subject and addresses, not a phone number.
                startActivity(Intent(this, com.lightdecoder.ui.mail.MailComposeActivity::class.java))
                return@setOnClickListener
            }
            startActivity(
                Intent(this, NewConversationActivity::class.java).putExtra(RelayIntents.EXTRA_SERVICE, backend.service.key)
            )
        }
        binding.linkCardAction.setOnClickListener { startActivity(ServiceUi.setup(this, backend.service)) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Both links are watched; only the one whose tab is shown is drawn.
                for (each in ScreenBackends.all(this@ConversationsActivity)) {
                    launch { each.state.collect { if (each.service == backend.service) onState(it) } }
                    launch { each.backfill.collect { if (each.service == backend.service) renderBackfill(it) } }
                }
            }
        }
        if (savedInstanceState == null && !asked) checkSetUp()
    }

    /** Up from a thread, or a hand-over from setup: the list is already open, and shows the service asked for. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!intent.hasExtra(RelayIntents.EXTRA_SERVICE)) return
        val service = tabServiceOf(RelayIntents.serviceOf(intent))
        if (service == backend.service) return
        // A search is over the service it was started in.
        searchItem?.collapseActionView()
        selectServiceTab(service)
    }

    override fun onStart() {
        super.onStart()
        // The Discord tab opens quicker with its process and web engine ready (nothing is loaded).
        if (Features.DISCORD) com.lightdecoder.discord.DiscordWarmup.request(this)
        started = true
        ChatVisibility.enter(backend)
        ArchiveEvents.register(onArchiveChange)
        reload()
        renderBadges()
    }

    override fun onResume() {
        super.onResume()
        // Back from the notification-access setting Blue Bubbles sent the user to.
        blueBubbles.onResume()
        // Back from the iMessage library's setup (another process): a new registration shows its chats.
        val wasOn = blueBubbles.isOn()
        com.lightdecoder.imessage.direct.DirectLink.onAppStart(this)
        if (!wasOn && blueBubbles.isOn()) {
            com.lightdecoder.imessage.IMessageManager.get(this).refreshState()
            reload()
        }
        // The iMessage light may have changed meanwhile (notification access, OpenBubbles installed).
        invalidateOptionsMenu()
    }

    override fun onStop() {
        ArchiveEvents.unregister(onArchiveChange)
        ChatVisibility.leave(backend)
        started = false
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SERVICE, backend.service.key)
        outState.putString(STATE_FOLDER, folder.name)
        searchQuery?.let { outState.putString(STATE_QUERY, it) }
        blueBubbles.save(outState)
    }

    /**
     * Opened without saying which service, with no service linked and
     * nothing archived, there is nothing to show in any tab, so setup takes
     * over. An archive kept after unpairing stays readable, and a tab that
     * was asked for shows its card instead.
     */
    private fun checkSetUp() {
        val service = backend.service
        lifecycleScope.launch {
            val nothingToShow = withContext(Dispatchers.IO) {
                // iMessage chats (Blue Bubbles) are something to show, in the Messages tab.
                val iMessage = blueBubbles.isOn() &&
                    runCatching { ScreenBackends.get(applicationContext, ChatService.IMESSAGE).repo.conversations(null, 1).isNotEmpty() }.getOrDefault(false)
                !iMessage && ScreenBackends.all(applicationContext).none { it.isPaired() || it.repo.conversations(null, 1).isNotEmpty() }
            }
            if (nothingToShow) {
                startActivity(ServiceUi.setup(this@ConversationsActivity, service))
                finish()
            }
        }
    }

    private fun onState(state: RelayState) {
        val shown = backend
        lifecycleScope.launch {
            val isPaired = withContext(Dispatchers.IO) { shown.isPaired() }
            if (shown !== backend) return@launch
            val wasPaired = paired
            paired = isPaired
            relayState = state
            binding.fab.isEnabled = paired
            renderLinkState()
            if (paired != wasPaired) refreshMenu()
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

    // --- services ------------------------------------------------------------

    private fun setUpServiceTabs() {
        val tabs = binding.services
        com.lightdecoder.chat.Features.shownServices().forEachIndexed { index, service ->
            tabs.addTab(tabs.newTab().setText(ServiceUi.tabLabel(service)).setTag(service), service == backend.service)
            // Discord's tab comes right after Messages. It is not a service of this screen: it
            // has no archive or link here, and its tab opens its own screen in its own process.
            if (index == 0 && Features.DISCORD && DiscordProcess.supported) {
                tabs.addTab(tabs.newTab().setText(R.string.chat_service_discord).setTag(DISCORD_TAB), false)
            }
        }
        // Messenger's tab goes right before WhatsApp's. Like Discord's it is not a service of this
        // screen and opens its own.
        com.lightdecoder.ui.messenger.MessengerTab.add(tabs)
        switcher = ServiceTabStrip.attachSwitcher(tabs, binding.root.findViewById(R.id.servicesMenu)) {
            getString(ServiceUi.tabLabel(backend.service))
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val service = tab.tag as? ChatService
                if (service != null) {
                    showService(service)
                } else {
                    ServiceTabStrip.open(
                        this@ConversationsActivity,
                        if (com.lightdecoder.ui.messenger.MessengerTab.isTab(tab)) {
                            com.lightdecoder.ui.messenger.MessengerTab.intent(this@ConversationsActivity)
                        } else {
                            Intent(this@ConversationsActivity, DiscordActivity::class.java)
                        },
                    )
                    // This screen keeps showing the service it showed; its tab is chosen again.
                    tabs.post { selectServiceTab(backend.service) }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) {
                binding.list.smoothScrollToPosition(0)
            }
        })
    }

    /** The app switcher in the top bar; refreshed when the unread counts change. */
    private var switcher: ServiceTabStrip.Refresh? = null

    private fun selectServiceTab(service: ChatService) {
        val tabs = binding.services
        for (i in 0 until tabs.tabCount) {
            val tab = tabs.getTabAt(i) ?: continue
            if (tab.tag == service) tab.select()
        }
    }

    /**
     * The other service's tab was chosen: its link, archive, folders and
     * state replace the ones on screen, and it is where the list opens next.
     */
    private fun showService(service: ChatService) {
        if (service == backend.service) return
        folderOf[backend.service] = folder
        if (started) ChatVisibility.leave(backend)
        backend = ScreenBackends.get(this, service)
        if (started) ChatVisibility.enter(backend)
        ChatUiPrefs.setLastService(this, service)
        banner.bind(backend)

        folder = folderOf[service] ?: Folder.INBOX
        hasDeleted = false
        archiveEmpty = false
        relayState = null
        // Cheap; the state that follows reads it again.
        paired = backend.isPaired()
        adapter.submitList(emptyList())
        binding.emptyText.visibility = View.GONE
        binding.swipe.isRefreshing = false
        binding.fab.isEnabled = paired
        setUpFolderTabs()
        renderFolderControls()
        renderLinkState()
        onState(backend.state.value)
        renderBackfill(backend.backfill.value)
        reload()
    }

    /** One badge per service tab: its unread conversations. */
    private fun renderBadges() {
        lifecycleScope.launch {
            val unread = withContext(Dispatchers.IO) {
                val counts = ScreenBackends.all(applicationContext).associate { it.service to it.repo.unreadCount() }.toMutableMap()
                // iMessage chats are in the Messages tab, and so are their unread ones.
                if (blueBubbles.isOn()) {
                    val iMessage = runCatching { ScreenBackends.get(applicationContext, ChatService.IMESSAGE).repo.unreadCount() }.getOrDefault(0)
                    counts[ChatService.MESSAGES] = (counts[ChatService.MESSAGES] ?: 0) + iMessage
                }
                counts
            }
            val tabs = binding.services
            for (i in 0 until tabs.tabCount) {
                val tab = tabs.getTabAt(i) ?: continue
                val count = unread[tab.tag] ?: 0
                if (count > 0) {
                    tab.orCreateBadge.apply {
                        number = count
                        setContentDescriptionQuantityStringsResource(R.plurals.chat_unread_conversations)
                    }
                } else {
                    tab.removeBadge()
                }
            }
            switcher?.refresh()
        }
    }

    private fun scheduleBadges() {
        if (badgesScheduled) return
        badgesScheduled = true
        lifecycleScope.launch {
            delay(RELOAD_DEBOUNCE_MS)
            badgesScheduled = false
            renderBadges()
        }
    }

    /**
     * What surrounds the list: the banner of the shown link, and, while that
     * service is not linked and has nothing stored, the card that leads to
     * its setup in place of the folders and the list.
     */
    private fun renderLinkState() {
        val searching = searchQuery != null
        val card = !searching && !paired && archiveEmpty
        binding.folders.visibility = if (searching || card) View.GONE else View.VISIBLE
        binding.swipe.visibility = if (searching || card) View.GONE else View.VISIBLE
        binding.searchResults.visibility = if (searching) View.VISIBLE else View.GONE
        binding.linkCard.visibility = if (card) View.VISIBLE else View.GONE
        if (searching || card) binding.fab.hide() else binding.fab.show()
        val state = relayState
        if (card) {
            val service = backend.service
            binding.emptyText.visibility = View.GONE
            binding.linkCardTitle.setText(ServiceUi.linkCardTitle(service))
            // Right after an unpair that could not reach the service, say how to
            // remove LightDecoder on the phone instead of "not set up".
            if (state?.code == RelayState.UNPAIR_REMOTE_FAILED) {
                binding.linkCardBody.text = ChatErrors.message(this, service, state.code)
            } else {
                binding.linkCardBody.setText(ServiceUi.linkCardBody(service))
            }
        }
        if (state != null) banner.render(state, paired, quietWhenUnlinked = card)
    }

    // --- folders -------------------------------------------------------------

    /** The folders the shown service has, with [folder] selected (the inbox if it has no such folder). */
    private fun setUpFolderTabs() {
        val tabs = binding.folders
        val available = FOLDERS.filter { (f, _) -> hasFolder(backend, f) }
        if (available.none { it.first == folder }) folder = Folder.INBOX
        buildingFolderTabs = true
        tabs.clearOnTabSelectedListeners()
        tabs.removeAllTabs()
        available.forEach { (f, label) ->
            tabs.addTab(tabs.newTab().setText(label).setTag(f), f == folder)
        }
        buildingFolderTabs = false
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (buildingFolderTabs) return
                folder = tab.tag as Folder
                adapter.submitList(emptyList())
                renderFolderControls()
                reload()
                // Archived and Spam are not part of the automatic sync; fetch them when opened.
                // Deleted is local only: the phone has nothing to list for it.
                if (folder != Folder.INBOX && folder.listable && relayState?.isOnline == true) refreshFromPhone(quiet = true)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) {
                binding.list.smoothScrollToPosition(0)
            }
        })
    }

    private fun hasFolder(backend: ChatBackend, folder: Folder): Boolean = when (folder) {
        Folder.SPAM -> Capability.SPAM_FOLDER in backend.capabilities
        Folder.DELETED -> Capability.DELETED_FOLDER in backend.capabilities
        Folder.INBOX, Folder.ARCHIVE -> true
    }

    /** Pull to refresh and the menu's refresh ask the phone, which has nothing for the local Deleted folder. */
    private fun renderFolderControls() {
        binding.swipe.isEnabled = folder.listable
        refreshMenu()
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
        val from = backend
        val iMessage = if (showsIMessage()) ScreenBackends.get(this, ChatService.IMESSAGE) else null
        val iMessageHere = iMessage != null && (shown == Folder.INBOX || shown == Folder.ARCHIVE)
        lifecycleScope.launch {
            val (items, stored) = withContext(Dispatchers.IO) {
                val own = from.repo.conversations(shown).map { ConversationItem(it, titleFor(from.repo, it), from.service) }
                val extra = if (!iMessageHere) emptyList() else runCatching { iMessage!!.repo.conversations(shown) }.getOrDefault(emptyList())
                    .map { ConversationItem(it, titleFor(iMessage!!.repo, it), iMessage.service) }
                val rows = if (extra.isEmpty()) own else foldIn(own, extra, BlueBubblesLinks.pairs(applicationContext))
                val ownEmpty = from.repo.conversations(null, 1).isEmpty()
                val iMessageEmpty = iMessage == null || runCatching { iMessage.repo.conversations(null, 1).isEmpty() }.getOrDefault(true)
                rows to (ownEmpty to (ownEmpty && iMessageEmpty))
            }
            val (ownEmpty, nothingStored) = stored
            if (from !== backend || shown != folder || searchQuery != null) return@launch
            adapter.submitList(items)
            if (shown == Folder.DELETED && hasDeleted != items.isNotEmpty()) {
                hasDeleted = items.isNotEmpty()
                refreshMenu()
            }
            binding.emptyText.setText(
                when (shown) {
                    Folder.INBOX -> if (paired) ServiceUi.emptyInbox(from.service) else R.string.chat_empty_unpaired
                    Folder.ARCHIVE -> R.string.chat_empty_archive
                    Folder.SPAM -> R.string.chat_empty_spam
                    Folder.DELETED -> R.string.chat_empty_deleted
                }
            )
            binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            if (archiveEmpty != nothingStored || ownArchiveEmpty != ownEmpty) {
                archiveEmpty = nothingStored
                ownArchiveEmpty = ownEmpty
                refreshMenu()
            }
            renderLinkState()
        }
    }

    /** The phone's name for the conversation, else its people, else a placeholder. Runs on IO. */
    private fun titleFor(repo: ArchiveRepository, row: ConversationRow): String = ConversationTitles.of(this, repo, row)

    private fun refreshFromPhone(quiet: Boolean = false) {
        val shown = folder
        val from = backend
        if (!shown.listable) {
            binding.swipe.isRefreshing = false
            return
        }
        lifecycleScope.launch {
            if (!quiet) binding.swipe.isRefreshing = true
            val result = relayAttempt { from.refreshConversations(shown) }
            // The tab changed meanwhile: the spinner and the message belong to the other service now.
            if (from !== backend) return@launch
            result.onFailure { if (!quiet) snackbar(ChatErrors.message(this@ConversationsActivity, from.service, it)) }
            binding.swipe.isRefreshing = false
        }
    }

    // --- search --------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.conversations_actions, menu)
        optionsMenu = menu
        val item = menu.findItem(R.id.action_search)
        searchItem = item
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

    /**
     * Brings the menu up to date. While a search is open the menu is not
     * rebuilt (that would collapse the search field); closing the search
     * rebuilds it.
     */
    private fun refreshMenu() {
        if (searchQuery != null) updateSearchDependentItems() else invalidateOptionsMenu()
    }

    /** The menu items whose visibility depends on a search being open, updated in place. */
    private fun updateSearchDependentItems() {
        optionsMenu?.findItem(R.id.action_empty_deleted)?.isVisible =
            folder == Folder.DELETED && hasDeleted && searchQuery == null
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_blue_bubbles)?.let { item ->
            item.isVisible = backend.service == ChatService.MESSAGES
            item.isChecked = blueBubbles.isOn()
        }
        menu.findItem(R.id.action_imessage_direct)?.isVisible = backend.service == ChatService.MESSAGES
        // The iMessage light: Messages tab, while Blue Bubbles is on.
        menu.findItem(R.id.action_imessage_light)?.let { item ->
            if (backend.service == ChatService.MESSAGES) IMessageLight.bind(this, item, IMessageLight.overall(this), hideWhenOff = true)
            else item.isVisible = false
        }
        // Without a pairing, what is listed is an archive an unpair kept: it can be deleted here.
        menu.findItem(R.id.action_delete_archive)?.isVisible = !paired && !ownArchiveEmpty
        menu.findItem(R.id.action_refresh)?.isVisible = paired && folder.listable
        menu.findItem(R.id.action_empty_deleted)?.isVisible = folder == Folder.DELETED && hasDeleted && searchQuery == null
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.action_refresh -> refreshFromPhone()
            R.id.action_blue_bubbles -> blueBubbles.toggle()
            R.id.action_imessage_direct -> startActivity(Intent(this, com.lightdecoder.ui.imessage.DirectSetupActivity::class.java))
            R.id.action_link_status -> startActivity(ServiceUi.status(this, backend.service))
            R.id.action_delete_archive -> LocalArchive.confirmDelete(this, backend) { reload() }
            R.id.action_empty_deleted -> confirmEmptyDeleted()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    /** The search is over the service whose tab was open; its tabs and folders wait behind it. */
    private fun enterSearch() {
        searchQuery = ""
        // Not invalidateOptionsMenu(): rebuilding the menu would collapse the search field just opened.
        updateSearchDependentItems()
        binding.emptyText.visibility = View.GONE
        searchAdapter.submitList(emptyList())
        renderLinkState()
    }

    private fun exitSearch() {
        searchQuery = null
        // The search field is closing anyway: the whole menu can be brought up to date.
        invalidateOptionsMenu()
        searchJob?.cancel()
        renderLinkState()
        reload()
    }

    private fun runSearch(query: String, debounce: Boolean) {
        searchQuery = query
        searchJob?.cancel()
        val from = backend
        val iMessage = if (showsIMessage()) ScreenBackends.get(this, ChatService.IMESSAGE) else null
        searchJob = lifecycleScope.launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MS)
            val hits: List<SearchHit> = if (query.isBlank()) emptyList() else withContext(Dispatchers.IO) {
                val own = from.repo.search(query).map { it.copy(service = from.service) }
                // The iMessage chats listed in the Messages tab are searched with it.
                val extra = iMessage?.let { im -> runCatching { im.repo.search(query) }.getOrDefault(emptyList()).map { it.copy(service = im.service) } }.orEmpty()
                if (extra.isEmpty()) own else (own + extra).sortedByDescending { it.timestampMs }
            }
            if (searchQuery != query || from !== backend) return@launch
            searchAdapter.submitList(hits)
            binding.emptyText.setText(R.string.chat_search_empty)
            binding.emptyText.visibility = if (hits.isEmpty() && query.isNotBlank()) View.VISIBLE else View.GONE
        }
    }

    // --- actions -------------------------------------------------------------

    private fun openThread(conversationId: String, messageId: String?, service: ChatService = backend.service) {
        startActivity(
            Intent(this, ThreadActivity::class.java)
                .putExtra(RelayIntents.EXTRA_CONVERSATION_ID, conversationId)
                .putExtra(RelayIntents.EXTRA_SERVICE, service.key)
                .apply { if (messageId != null) putExtra(RelayIntents.EXTRA_MESSAGE_ID, messageId) }
        )
    }

    private fun showActions(item: ConversationItem) {
        val row = item.row
        // The row is its service's: what is chosen acts on it, whatever tab is shown by then.
        val on = if (item.service == backend.service) backend else ScreenBackends.get(this, item.service)
        val actions = ArrayList<Pair<Int, () -> Unit>>()
        when (row.folderEnum) {
            Folder.INBOX -> actions += R.string.chat_action_archive to { setArchived(on, row, true) }
            Folder.ARCHIVE -> actions += R.string.chat_action_unarchive to { setArchived(on, row, false) }
            Folder.SPAM -> actions += R.string.chat_action_not_spam to { markNotSpam(on, row) }
            else -> Unit
        }
        if (row.folderEnum == Folder.DELETED) {
            // The phone already deleted it: only this device's copy is left to decide on.
            actions += R.string.chat_action_restore to { restoreDeleted(on, row) }
            actions += R.string.chat_action_delete_forever to { confirmDeleteForever(on, item) }
        } else {
            actions += (if (row.muted) R.string.chat_action_unmute else R.string.chat_action_mute) to { setMuted(on, row, !row.muted) }
            actions += R.string.chat_action_delete to { confirmDelete(on, item) }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(item.title)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun setArchived(on: ChatBackend, row: ConversationRow, archived: Boolean) = runAction(
        on, if (archived) R.string.chat_archived else R.string.chat_unarchived,
    ) { on.setArchived(row.id, archived) }

    private fun markNotSpam(on: ChatBackend, row: ConversationRow) =
        runAction(on, R.string.chat_not_spam_done) { on.markNotSpam(row.id) }

    private fun restoreDeleted(on: ChatBackend, row: ConversationRow) =
        runAction(on, R.string.chat_restored) { on.restoreDeletedLocally(row.id) }

    /** Deleted for good, here only: the phone already deleted it. */
    private fun confirmDeleteForever(on: ChatBackend, item: ConversationItem) {
        val id = item.row.id
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_delete_forever_title)
            .setMessage(R.string.chat_delete_forever_body)
            .setPositiveButton(R.string.chat_action_delete_forever) { _, _ ->
                runAction(on, R.string.chat_deleted_forever) { on.purgeDeleted(id) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Everything in the Deleted folder, for good, here only. */
    private fun confirmEmptyDeleted() {
        val on = backend
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_empty_deleted_title)
            .setMessage(R.string.chat_empty_deleted_body)
            .setPositiveButton(R.string.chat_empty_deleted_action) { _, _ ->
                runAction(on, R.string.chat_emptied_deleted) { on.purgeDeleted(null) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setMuted(on: ChatBackend, row: ConversationRow, muted: Boolean) = runAction(
        on, if (muted) R.string.chat_muted_done else R.string.chat_unmuted_done,
    ) { on.setMuted(row.id, muted) }

    /**
     * With a pairing it goes on the phone too; without one (an archive an
     * unpair kept) the manager deletes it here only. If the pairing changed
     * while the question was up, it is asked again with the right wording.
     */
    private fun confirmDelete(on: ChatBackend, item: ConversationItem) {
        val linked = on.isPaired()
        val id = item.row.id
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_delete_conversation_title)
            .setMessage(
                if (linked) ServiceUi.deleteConversationBody(on.service) else R.string.chat_delete_conversation_body_local
            )
            .setPositiveButton(R.string.chat_action_delete) { _, _ ->
                if (on.isPaired() != linked) {
                    confirmDelete(on, item)
                    return@setPositiveButton
                }
                runAction(on, R.string.chat_deleted) { on.deleteConversation(id) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runAction(on: ChatBackend, doneMessage: Int, block: suspend () -> Unit) {
        lifecycleScope.launch {
            relayAttempt { block() }
                .onSuccess { snackbar(getString(doneMessage)) }
                .onFailure { snackbar(ChatErrors.message(this@ConversationsActivity, on.service, it)) }
        }
    }

    private fun snackbar(message: String) {
        val bar = Snackbar.make(binding.coordinator, message, Snackbar.LENGTH_LONG)
        if (binding.fab.isShown) bar.setAnchorView(binding.fab)
        bar.show()
    }

    companion object {
        /** The tab a service's conversations are listed in: iMessage has none of its own and is in Messages. */
        internal fun tabServiceOf(service: ChatService): ChatService =
            if (service == ChatService.IMESSAGE) ChatService.MESSAGES else service

        /**
         * The Messages rows with the iMessage chats folded in: a chat whose
         * person has a Messages conversation is shown as that conversation
         * (its newest message, time and unread mark taken over when newer),
         * as on an iPhone; the rest are rows of their own. Runs on IO.
         */
        private fun foldIn(
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
        internal fun mergeByTime(a: List<ConversationItem>, b: List<ConversationItem>): List<ConversationItem> =
            (a + b).sortedWith(compareByDescending<ConversationItem> { it.row.pinned }.thenByDescending { it.row.lastMessageTs })

        private const val STATE_SERVICE = "service"
        private const val STATE_FOLDER = "folder"
        private const val STATE_QUERY = "query"
        private const val RELOAD_DEBOUNCE_MS = 200L
        private const val SEARCH_DEBOUNCE_MS = 250L

        /** The tag of the Discord tab, which is no [ChatService]. */
        private val DISCORD_TAB = Any()

        /** Every folder there is, in tab order; a service shows the ones it has. */
        private val FOLDERS = listOf(
            Folder.INBOX to R.string.chat_folder_inbox,
            Folder.ARCHIVE to R.string.chat_folder_archive,
            Folder.SPAM to R.string.chat_folder_spam,
            Folder.DELETED to R.string.chat_folder_deleted,
        )
    }
}
