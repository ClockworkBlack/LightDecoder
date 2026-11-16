package com.lightdecoder.ui.chat

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveChange
import com.lightdecoder.archive.ArchiveEvents
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.ConversationRow
import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MediaRow
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.archive.ParticipantRow
import com.lightdecoder.databinding.ActivityThreadBinding
import com.lightdecoder.databinding.SheetMessageActionsBinding
import com.lightdecoder.relay.Folder
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.ReactionDto
import com.lightdecoder.relay.RelayException
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.RelayManager
import com.lightdecoder.relay.RelayState
import com.lightdecoder.ui.ViewerActivity
import com.lightdecoder.ui.relay.NotificationPermission
import com.lightdecoder.ui.relay.RelayErrors
import com.lightdecoder.ui.relay.RelayStatusActivity
import com.lightdecoder.ui.relay.SignInWebData
import com.lightdecoder.ui.relay.relayAttempt
import com.lightdecoder.ui.relay.runInBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One conversation: its messages from the local archive, older history
 * fetched from the phone on scroll, and the composer. Sends go through the
 * link's outbox, so a message typed while offline shows as "sending" and goes
 * out once connected.
 *
 * Extras: [RelayIntents.EXTRA_CONVERSATION_ID] (required) and optionally
 * [RelayIntents.EXTRA_MESSAGE_ID] to open at that message.
 */
class ThreadActivity : AppCompatActivity(), MessagesAdapter.Callbacks {

    private lateinit var binding: ActivityThreadBinding
    private lateinit var manager: RelayManager
    private lateinit var repo: ArchiveRepository
    private lateinit var adapter: MessagesAdapter
    private lateinit var media: MediaLoader
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var banner: ConnectionBanner
    private val notifications = NotificationPermission(this)

    private var conversationId = ""
    private var conversation: ConversationRow? = null
    private var participants: List<ParticipantRow> = emptyList()
    private var title = ""

    /** How many of the newest messages are shown; grows as the user scrolls up. */
    private var windowSize = PAGE
    private var growWindow = false

    /** A wider window was asked for and not loaded yet; scroll events must not widen it again. */
    private var growPending = false
    private var messages: List<MessageRow> = emptyList()
    private var historyComplete = false
    private var remoteExhausted = false
    private var loadingOlder = false
    private val loadMutex = Mutex()
    private var reloadScheduled = false

    /** Jump here (and mark it briefly) once it is loaded. */
    private var targetMessageId: String? = null
    private var highlightId: String? = null

    private var replyTarget: MessageRow? = null
    private var attachment: Uri? = null

    /**
     * The long-press sheet and the open delete question, if any. Both belong
     * to the conversation they were opened in, so a thread switch closes them.
     */
    private var actionSheet: Dialog? = null
    private var confirmDialog: Dialog? = null

    private var started = false
    private var paired = true
    private var online = false
    private var lastMarkedId: String? = null
    private var lastMarkAtMs = 0L
    private var lastTypingPingMs = 0L
    private var remoteFailedAtMs = 0L
    private var scrollToBottomNext = false

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) setAttachment(uri)
    }

    private val onArchiveChange: (ArchiveChange) -> Unit = { change ->
        val mine = change.conversationId == null || change.conversationId == conversationId
        if (mine && change.kind != ArchiveChange.Kind.HEALTH) scheduleReload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Sign-in cookies a killed setup screen left behind (once per process; see SignInWebData).
        SignInWebData.wipeIfDirty(this)
        super.onCreate(savedInstanceState)
        binding = ActivityThreadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarAndImePadding(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        manager = RelayManager.get(this)
        repo = ArchiveRepository.get(this)
        banner = ConnectionBanner(this, binding.banner, manager, notifications)
        media = MediaLoader(manager, lifecycleScope)

        conversationId = intent.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID).orEmpty()
        if (conversationId.isEmpty()) {
            finish()
            return
        }
        if (savedInstanceState == null) {
            targetMessageId = intent.getStringExtra(RelayIntents.EXTRA_MESSAGE_ID)
        } else {
            windowSize = savedInstanceState.getInt(STATE_WINDOW, PAGE)
            savedInstanceState.getString(STATE_ATTACHMENT)?.let { setAttachment(Uri.parse(it)) }
        }

        setUpList()
        setUpComposer()
        // Opened from a message notification on a cold start, the thread is the
        // whole task: Back and Up lead to the conversation list, not out of the app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isTaskRoot) openConversations()
                finish()
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { manager.state.collect { onState(it) } }
                launch { manager.typing.collect { renderTyping(it[conversationId].orEmpty()) } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val id = intent.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID).orEmpty()
        if (id.isEmpty()) return
        if (id != conversationId) {
            // Everything that belonged to the other conversation goes, above all
            // a half-typed reply: one tap on Send would deliver it to the wrong person.
            // A paging call still running for it is dropped when it returns (loadOlderFromPhone).
            conversationId = id
            // The long-press sheet and a delete question still act on the old conversation's
            // message or id: close them, and the overflow menu built from its row.
            actionSheet?.dismiss()
            confirmDialog?.dismiss()
            closeOptionsMenu()
            windowSize = PAGE
            growWindow = false
            growPending = false
            messages = emptyList()
            historyComplete = false
            remoteExhausted = false
            remoteFailedAtMs = 0L
            lastMarkedId = null
            lastMarkAtMs = 0L
            highlightId = null
            binding.input.setText("")
            clearReply()
            clearAttachment()
            renderTyping(manager.typing.value[id].orEmpty())
            submitSeq++
            pendingScrollTo = null
            adapter.submitList(emptyList())
            // The old conversation's row must not decide the title, the menu, mark-read
            // or who can be replied to until the new one's has been read.
            conversation = null
            participants = emptyList()
            renderConversation()
        }
        targetMessageId = intent.getStringExtra(RelayIntents.EXTRA_MESSAGE_ID)
        reload()
    }

    override fun onStart() {
        super.onStart()
        started = true
        ArchiveEvents.register(onArchiveChange)
        reload()
    }

    override fun onStop() {
        started = false
        ArchiveEvents.unregister(onArchiveChange)
        super.onStop()
    }

    /**
     * While this thread is in front its new messages are not notified (the
     * user is reading them), and a notification already up for it goes.
     * onNewIntent runs before onResume, so a switched thread is covered.
     */
    override fun onResume() {
        super.onResume()
        MessageNotifier.visibleConversationId = conversationId
        MessageNotifier.cancel(this, conversationId)
    }

    override fun onPause() {
        if (MessageNotifier.visibleConversationId == conversationId) MessageNotifier.visibleConversationId = null
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_WINDOW, windowSize)
        attachment?.let { outState.putString(STATE_ATTACHMENT, it.toString()) }
    }

    // --- list ----------------------------------------------------------------

    private fun setUpList() {
        adapter = MessagesAdapter(media, this)
        layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.messages.layoutManager = layoutManager
        binding.messages.adapter = adapter
        // Status and reaction changes rebind in place; a cross-fade on each would flicker.
        (binding.messages.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.messages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy < 0 && layoutManager.findFirstVisibleItemPosition() <= LOAD_OLDER_THRESHOLD) loadOlder()
            }
        })
        ItemTouchHelper(SwipeToReply()).attachToRecyclerView(binding.messages)
    }

    /** Swipe a message sideways to reply to it, as in most chat apps. */
    private inner class SwipeToReply : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.START or ItemTouchHelper.END) {
        override fun getSwipeDirs(recyclerView: RecyclerView, holder: RecyclerView.ViewHolder): Int {
            val item = adapter.currentList.getOrNull(holder.bindingAdapterPosition) as? ThreadItem.Message
            return if (item != null && canReplyTo(item.row)) super.getSwipeDirs(recyclerView, holder) else 0
        }

        override fun getSwipeThreshold(holder: RecyclerView.ViewHolder) = 0.3f

        override fun onMove(rv: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder) = false

        override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
            val position = holder.bindingAdapterPosition
            (adapter.currentList.getOrNull(position) as? ThreadItem.Message)?.let { onReply(it.row) }
            // Put the bubble back where it was.
            adapter.notifyItemChanged(position)
        }
    }

    private fun scheduleReload() {
        if (reloadScheduled) return
        reloadScheduled = true
        lifecycleScope.launch {
            delay(RELOAD_DEBOUNCE_MS)
            reloadScheduled = false
            loadNow()
        }
    }

    private fun reload() {
        lifecycleScope.launch { loadNow() }
    }

    private class Snapshot(
        val conversation: ConversationRow?,
        val participants: List<ParticipantRow>,
        val rows: List<MessageRow>,
        val quotes: Map<String, Quote>,
        val historyComplete: Boolean,
    )

    /**
     * Reads the shown window again. Normally that is every message from the
     * oldest one on screen to the newest, so new arrivals are added without
     * the top of the list moving; after "load older" the window grows.
     */
    private suspend fun loadNow() = loadMutex.withLock {
        val id = conversationId
        val target = targetMessageId
        val grow = growWindow
        growWindow = false
        // Back at the newest messages after reading far up (or after jumping to
        // an old search hit): shrink the window again, so later reloads (one per
        // incoming message or status change) do not re-read thousands of rows.
        val reset = target == null && !grow && windowSize > RESET_WINDOW && canShrinkWindow()
        if (reset) windowSize = PAGE
        val oldestShown = if (reset) null else messages.firstOrNull()?.timestampMs
        val wanted = windowSize
        val snapshot = withContext(Dispatchers.IO) {
            var rows: List<MessageRow>
            if (grow || oldestShown == null) {
                rows = repo.messages(id, limit = wanted)
            } else {
                rows = repo.messages(id, limit = wanted + RELOAD_SLACK).filter { it.timestampMs >= oldestShown }
            }
            if (target != null) {
                var size = maxOf(wanted, rows.size)
                while (rows.none { it.id == target } && rows.size >= size && size < MAX_WINDOW) {
                    size *= 2
                    rows = repo.messages(id, limit = size)
                }
            }
            val conversation = repo.conversation(id)
            Snapshot(
                conversation = conversation,
                participants = repo.participants(id),
                rows = rows,
                quotes = quotesFor(rows),
                historyComplete = repo.syncState(id)?.historyComplete == true,
            )
        }
        if (grow) growPending = false
        if (id != conversationId) return@withLock
        val localExhausted = snapshot.rows.size < wanted
        windowSize = maxOf(PAGE, snapshot.rows.size)
        messages = snapshot.rows
        conversation = snapshot.conversation
        participants = snapshot.participants
        historyComplete = snapshot.historyComplete
        renderConversation()

        val scrollTo = target?.takeIf { t -> snapshot.rows.any { it.id == t } }
        if (target != null) targetMessageId = null
        if (scrollTo != null) highlight(scrollTo)
        submit(snapshot.quotes, scrollTo)
        // submit() suspends while it builds the list: a notification tap may have switched
        // threads meanwhile. The reload onNewIntent queued handles the new conversation.
        // (Not "skip unless submit applied": a resubmit() in the same conversation also
        // supersedes it, and mark-read and auto-paging must still run then.)
        if (id != conversationId) return@withLock
        markReadIfNeeded()
        // The archive has no more: ask the phone (also fills a thread that was never imported).
        if ((grow && localExhausted) || snapshot.rows.size < PAGE) loadOlderFromPhone(auto = !grow)
    }

    /** Quoted messages: from the window, else from the archive. Runs on IO. */
    private fun quotesFor(rows: List<MessageRow>): Map<String, Quote> {
        val byId = rows.associateBy { it.id }
        val mediaLabel = getString(R.string.chat_media_picture)
        val quotes = HashMap<String, Quote>()
        for (replyId in rows.mapNotNull { it.replyToId.takeIf(String::isNotEmpty) }.distinct().take(MAX_QUOTE_LOOKUPS)) {
            val quoted = byId[replyId] ?: repo.message(replyId) ?: continue
            val sender = if (quoted.isOutgoing) getString(R.string.chat_you)
            else quoted.senderName.ifBlank { getString(R.string.chat_unknown_sender) }
            quotes[replyId] = Quote(replyId, sender, ThreadItems.preview(quoted, mediaLabel))
        }
        return quotes
    }

    /** Bumped by every render; a render whose list was built after a newer one began is dropped. */
    private var submitSeq = 0

    /** A jump a dropped render was asked for; the render that replaces it makes it. */
    private var pendingScrollTo: String? = null

    /**
     * Builds the list off the main thread (a long window has thousands of
     * rows and day headers) and hands it to the adapter. Where the list is
     * scrolled is read just before the hand-over, so it is current.
     */
    private suspend fun submit(quotes: Map<String, Quote>, scrollToMessage: String?) {
        val seq = ++submitSeq
        if (scrollToMessage != null) pendingScrollTo = scrollToMessage
        val header = when {
            loadingOlder -> ThreadItem.Header(loading = true, complete = false)
            historyComplete || remoteExhausted -> ThreadItem.Header(loading = false, complete = true)
            else -> null
        }
        val shown = messages
        val isGroup = conversation?.isGroup == true
        val highlighted = highlightId
        lastQuotes = quotes
        val context = applicationContext
        val items = withContext(Dispatchers.Default) {
            ThreadItems.build(
                messages = shown,
                isGroup = isGroup,
                quotes = quotes,
                header = header,
                highlightId = highlighted,
                dayStart = { TimeFormat.startOfDay(it) },
                dayLabel = { TimeFormat.dayLabel(context, it) },
            )
        }
        if (seq != submitSeq) return
        val scrollTo = pendingScrollTo
        pendingScrollTo = null
        val atBottom = isAtBottom() || scrollToBottomNext
        scrollToBottomNext = false
        val anchor = if (atBottom || scrollTo != null) null else firstVisibleAnchor()
        binding.emptyText.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        adapter.submitList(items) {
            when {
                scrollTo != null -> scrollToMessage(scrollTo)
                atBottom -> if (items.isNotEmpty()) binding.messages.scrollToPosition(items.lastIndex)
                anchor != null -> restoreAnchor(anchor)
            }
        }
    }

    private var lastQuotes: Map<String, Quote> = emptyMap()

    /** Re-renders without reading the archive (header or highlight changed). */
    private fun resubmit(scrollTo: String? = null) {
        lifecycleScope.launch { submit(lastQuotes, scrollTo) }
    }

    /**
     * Whether the window can go back to the newest [PAGE] messages without
     * anything on screen changing: the list is at the bottom and everything
     * visible is among the newest [PAGE] rows.
     */
    private fun canShrinkWindow(): Boolean {
        if (!isAtBottom()) return false
        val first = layoutManager.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return false
        // Items include day headers, so this many items hold at most as many messages.
        return adapter.itemCount - first <= PAGE
    }

    private fun isAtBottom(): Boolean {
        val count = adapter.itemCount
        if (count == 0) return true
        return layoutManager.findLastVisibleItemPosition() >= count - 2
    }

    private fun firstVisibleAnchor(): Pair<String, Int>? {
        val position = layoutManager.findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return null
        val key = adapter.currentList.getOrNull(position)?.key ?: return null
        val top = layoutManager.findViewByPosition(position)?.top ?: 0
        return key to top
    }

    /** Keeps the message the user is reading in place when older ones are added above it. */
    private fun restoreAnchor(anchor: Pair<String, Int>) {
        val index = adapter.currentList.indexOfFirst { it.key == anchor.first }
        if (index >= 0) layoutManager.scrollToPositionWithOffset(index, anchor.second)
    }

    private fun scrollToMessage(messageId: String) {
        val index = adapter.currentList.indexOfFirst { it is ThreadItem.Message && it.row.id == messageId }
        if (index >= 0) layoutManager.scrollToPositionWithOffset(index, binding.messages.height / 3)
    }

    private fun highlight(messageId: String) {
        highlightId = messageId
        lifecycleScope.launch {
            delay(HIGHLIGHT_MS)
            if (highlightId == messageId) {
                highlightId = null
                resubmit()
            }
        }
    }

    // --- older history -------------------------------------------------------

    private fun loadOlder() {
        if (loadingOlder || growPending) return
        if (messages.size >= windowSize) {
            // The archive may hold more: widen the window first.
            windowSize += PAGE
            growWindow = true
            growPending = true
            reload()
        } else {
            loadOlderFromPhone(auto = false)
        }
    }

    /**
     * Pages older history in from the phone. Quietly does nothing when there
     * is no more, or when offline (the banner already says so).
     */
    private fun loadOlderFromPhone(auto: Boolean) {
        if (loadingOlder || historyComplete || remoteExhausted || !paired) return
        // Automatic loads stop after a failure for a while, so an error never turns into a loop.
        if (auto && (!online || System.currentTimeMillis() - remoteFailedAtMs < REMOTE_RETRY_MS)) return
        loadingOlder = true
        resubmit()
        val id = conversationId
        lifecycleScope.launch {
            val result = relayAttempt { manager.loadOlder(id, PAGE) }
            loadingOlder = false
            // The screen may have switched to another conversation meanwhile
            // (a notification tap): this result is not about the one shown now.
            if (id == conversationId) {
                result
                    .onSuccess { added ->
                        if (added == 0) {
                            remoteExhausted = true
                        } else {
                            windowSize += added
                            growWindow = true
                        }
                    }
                    .onFailure {
                        remoteFailedAtMs = System.currentTimeMillis()
                        val code = RelayErrors.codeOf(it)
                        if (!auto && code != RelayException.NOT_CONNECTED) toast(RelayErrors.message(this@ThreadActivity, code))
                    }
            }
            // For a switched conversation this runs its own automatic load, now that loadingOlder is free.
            loadNow()
        }
    }

    // --- conversation header, state, typing ----------------------------------

    private fun renderConversation() {
        val conv = conversation
        val others = participants.filter { !it.isMe }
        title = conv?.name?.takeIf { it.isNotBlank() }
            ?: others.map { it.name.ifBlank { it.formattedNumber.ifBlank { it.number } } }.filter { it.isNotBlank() }
                .joinToString(", ").ifBlank { getString(R.string.chat_untitled) }
        supportActionBar?.title = title
        supportActionBar?.subtitle = when {
            conv?.isGroup == true -> resources.getQuantityString(R.plurals.chat_people, others.size + 1, others.size + 1)
            others.size == 1 -> others[0].formattedNumber.ifBlank { others[0].number }.takeIf { it != title }
            else -> null
        }
        invalidateOptionsMenu()
        renderComposerState()
    }

    private fun onState(state: RelayState) {
        lifecycleScope.launch {
            paired = withContext(Dispatchers.IO) { manager.isPaired() }
            val wasOnline = online
            online = paired && state.isOnline
            banner.render(state, paired)
            renderComposerState()
            if (online && !wasOnline) {
                // Pictures that failed to download while offline: try again now.
                if (media.hadFailures) {
                    media.clearFailures()
                    adapter.notifyItemRangeChanged(0, adapter.itemCount)
                }
                if (messages.size < PAGE) loadOlderFromPhone(auto = true)
                markReadIfNeeded(force = true)
            }
        }
    }

    private fun renderTyping(names: Set<String>) {
        val visible = names.filter { it.isNotBlank() }
        binding.typingText.visibility = if (names.isEmpty()) View.GONE else View.VISIBLE
        binding.typingText.text = when {
            names.isEmpty() -> ""
            visible.isEmpty() -> getString(R.string.chat_typing_someone)
            visible.size == 1 -> getString(R.string.chat_typing_one, visible[0])
            visible.size == 2 -> getString(R.string.chat_typing_two, visible[0], visible[1])
            else -> getString(R.string.chat_typing_many)
        }
    }

    /**
     * Tells the phone the conversation was read: when it opens, when it
     * comes back to the front, and when a new message arrives while it is
     * on screen. Offline, at least the local unread mark goes.
     */
    private fun markReadIfNeeded(force: Boolean = false) {
        if (!started) return
        val conv = conversation ?: return
        val newestIncoming = messages.lastOrNull { !it.isOutgoing && it.direction == "in" }?.id
        val now = System.currentTimeMillis()
        val newMessage = newestIncoming != null && newestIncoming != lastMarkedId
        val stillUnread = conv.unread && now - lastMarkAtMs > MARK_READ_RETRY_MS
        if (!newMessage && !stillUnread && !(force && conv.unread)) return
        lastMarkedId = newestIncoming
        lastMarkAtMs = now
        val id = conversationId
        lifecycleScope.launch {
            relayAttempt { manager.markRead(id) }.onFailure {
                withContext(Dispatchers.IO) { repo.markConversationRead(id) }
            }
        }
    }

    // --- composer ------------------------------------------------------------

    private fun setUpComposer() {
        binding.send.setOnClickListener { send() }
        binding.attach.setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
        binding.attachmentRemove.setOnClickListener { clearAttachment() }
        binding.replyClose.setOnClickListener { clearReply() }
        binding.input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                updateSendEnabled()
                if (!s.isNullOrEmpty()) pingTyping()
            }
        })
        updateSendEnabled()
    }

    private fun renderComposerState() {
        val readOnly = conversation?.readOnly == true
        binding.readOnlyText.visibility = if (readOnly) View.VISIBLE else View.GONE
        binding.composer.visibility = if (readOnly) View.GONE else View.VISIBLE
        if (readOnly) {
            clearReply()
            clearAttachment()
        }
        binding.input.isEnabled = paired
        binding.attach.isEnabled = paired
        binding.input.hint = getString(if (paired) R.string.chat_compose_hint else R.string.chat_compose_unpaired)
        updateSendEnabled()
    }

    private fun updateSendEnabled() {
        val hasContent = !binding.input.text.isNullOrBlank() || attachment != null
        binding.send.isEnabled = paired && hasContent
        // The tint has no disabled state: dim it the way Material dims disabled controls.
        binding.send.alpha = if (binding.send.isEnabled) 1f else DISABLED_ALPHA
    }

    /** At most one ping every few seconds; the manager throttles too. */
    private fun pingTyping() {
        if (!paired || !online) return
        val now = System.currentTimeMillis()
        if (now - lastTypingPingMs < TYPING_PING_MS) return
        lastTypingPingMs = now
        val id = conversationId
        runInBackground { manager.typing(id) }
    }

    private fun send() {
        val text = binding.input.text?.toString()?.trim().orEmpty()
        val file = attachment
        if (text.isEmpty() && file == null) return
        val id = conversationId
        // Only ever a message of this conversation (onReply checks too).
        val replyTo = replyTarget?.takeIf { it.conversationId == id }?.id
        scrollToBottomNext = true
        binding.input.setText("")
        clearReply()
        clearAttachment()
        lifecycleScope.launch {
            // The outbox writes to the archive (and copies an attachment), so not on the main thread.
            val result = withContext(Dispatchers.IO) {
                relayAttempt {
                    if (file != null) manager.sendMedia(id, file, text.ifEmpty { null }, replyTo)
                    else manager.sendText(id, text, replyTo)
                }
            }
            result.onFailure { toast(RelayErrors.message(this@ThreadActivity, it)) }
        }
    }

    private fun setAttachment(uri: Uri) {
        attachment = uri
        binding.attachmentBar.visibility = View.VISIBLE
        binding.attachmentName.text = getString(R.string.chat_attachment_ready)
        binding.attachmentThumb.setImageResource(R.drawable.ic_image_placeholder)
        updateSendEnabled()
        val side = (ATTACHMENT_THUMB_DP * resources.displayMetrics.density).toInt()
        lifecycleScope.launch {
            val (name, preview) = withContext(Dispatchers.IO) { describeAttachment(uri, side) }
            if (attachment != uri) return@launch
            name?.let { binding.attachmentName.text = it }
            preview?.let { binding.attachmentThumb.setImageBitmap(it) }
        }
    }

    private fun describeAttachment(uri: Uri, side: Int): Pair<String?, Bitmap?> {
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        }.getOrNull()
        val preview = runCatching {
            val isImage = contentResolver.getType(uri)?.startsWith("image/") != false
            if (!isImage) return@runCatching null
            contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytesCapped(MAX_PREVIEW_READ) ?: return@use null
                MediaLoader.decodePreview(bytes, side)
            }
        }.getOrNull()
        return name to preview
    }

    private fun java.io.InputStream.readNBytesCapped(max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > max) return null
        }
        return out.toByteArray()
    }

    private fun clearAttachment() {
        attachment = null
        binding.attachmentBar.visibility = View.GONE
        binding.attachmentThumb.setImageDrawable(null)
        updateSendEnabled()
    }

    private fun clearReply() {
        replyTarget = null
        binding.replyBar.visibility = View.GONE
    }

    private fun canReplyTo(row: MessageRow) = !row.isLocal && row.direction != "system" && conversation?.readOnly != true

    // --- message callbacks ---------------------------------------------------

    /** Whether [row] belongs to the conversation on screen (a sheet may outlive a thread switch). */
    private fun isShown(row: MessageRow) = row.conversationId == conversationId

    override fun onReply(row: MessageRow) {
        if (!isShown(row) || !canReplyTo(row)) return
        replyTarget = row
        binding.replyBar.visibility = View.VISIBLE
        val who = if (row.isOutgoing) getString(R.string.chat_you)
        else row.senderName.ifBlank { getString(R.string.chat_unknown_sender) }
        binding.replySender.text = getString(R.string.chat_replying_to, who)
        binding.replyText.text = ThreadItems.preview(row, getString(R.string.chat_media_picture))
        binding.input.requestFocus()
    }

    override fun onMessageLongPress(row: MessageRow) {
        if (row.direction == "system") return
        actionSheet?.dismiss()
        val sheet = BottomSheetDialog(this)
        val b = SheetMessageActionsBinding.inflate(layoutInflater)
        val real = !row.isLocal
        val mine = row.reactions.firstOrNull { it.fromMe }?.emoji

        b.reactionRow.visibility = if (real) View.VISIBLE else View.GONE
        if (real) {
            for (emoji in QUICK_REACTIONS) {
                val view = layoutInflater.inflate(R.layout.item_quick_reaction, b.reactionRow, false) as TextView
                view.text = emoji
                view.isSelected = emoji == mine
                view.contentDescription = getString(R.string.chat_react_with, emoji)
                view.setOnClickListener {
                    sheet.dismiss()
                    if (isShown(row)) react(row, emoji, remove = emoji == mine)
                }
                b.reactionRow.addView(view)
            }
        }
        b.actionRemoveReaction.visibility = if (real && mine != null) View.VISIBLE else View.GONE
        b.actionRemoveReaction.setOnClickListener {
            sheet.dismiss()
            if (mine != null && isShown(row)) react(row, mine, remove = true)
        }
        b.actionReply.visibility = if (canReplyTo(row)) View.VISIBLE else View.GONE
        b.actionReply.setOnClickListener {
            sheet.dismiss()
            onReply(row)
        }
        b.actionCopy.visibility = if (row.text.isNotEmpty()) View.VISIBLE else View.GONE
        b.actionCopy.setOnClickListener {
            sheet.dismiss()
            copy(row.text)
        }
        b.actionRetry.visibility = if (MessageStatus.canRetryLocally(row)) View.VISIBLE else View.GONE
        b.actionRetry.setOnClickListener {
            sheet.dismiss()
            if (isShown(row)) retry(row)
        }
        val discardable = row.isLocal && row.localState == LocalState.FAILED
        b.actionDiscard.visibility = if (discardable) View.VISIBLE else View.GONE
        b.actionDiscard.setOnClickListener {
            sheet.dismiss()
            if (!isShown(row)) return@setOnClickListener
            val tmpId = row.tmpId
            runInBackground { manager.discardFailed(tmpId) }
        }
        b.actionDelete.visibility = if (real) View.VISIBLE else View.GONE
        b.actionDelete.setOnClickListener {
            sheet.dismiss()
            confirmDeleteMessage(row)
        }
        sheet.setContentView(b.root)
        sheet.setOnDismissListener { if (actionSheet === sheet) actionSheet = null }
        actionSheet = sheet
        sheet.show()
    }

    override fun onFailedTap(row: MessageRow) {
        if (MessageStatus.canRetryLocally(row)) retry(row) else toast(getString(R.string.chat_failed_phone_detail))
    }

    override fun onReactionTap(row: MessageRow, reaction: ReactionDto) {
        if (row.isLocal) return
        react(row, reaction.emoji, remove = reaction.fromMe)
    }

    override fun onQuoteTap(messageId: String) {
        if (messages.any { it.id == messageId }) {
            highlight(messageId)
            resubmit(scrollTo = messageId)
        } else {
            targetMessageId = messageId
            reload()
        }
    }

    override fun onLoadFullSize(row: MessageRow, part: MediaRow) {
        lifecycleScope.launch {
            relayAttempt { manager.requestFullSize(row.id, part.partId) }
                .onSuccess { toast(getString(R.string.chat_full_size_requested)) }
                .onFailure { toast(RelayErrors.message(this@ThreadActivity, it)) }
        }
    }

    override fun onMediaTap(row: MessageRow, part: MediaRow) {
        lifecycleScope.launch {
            val local = listOf(part.localPath, if (part.needsFullSize) part.thumbPath else "")
                .firstOrNull { it.isNotEmpty() && File(it).isFile }?.let(::File)
            val file = local ?: run {
                toast(getString(R.string.chat_media_downloading))
                relayAttempt { manager.mediaFile(row.id, part.partId, thumbnail = part.needsFullSize) }
                    .getOrElse {
                        // not_connected: it can be downloaded, just not until the link is connected.
                        val code = RelayErrors.codeOf(it)
                        toast(
                            if (code == RelayException.NOT_CONNECTED) getString(R.string.chat_media_needs_connection)
                            else RelayErrors.message(this@ThreadActivity, code)
                        )
                        return@launch
                    }
            }
            if (file == null) {
                toast(getString(R.string.chat_media_unavailable))
                return@launch
            }
            val mime = part.mime
            if (mime.isEmpty() || mime.startsWith("image/")) {
                try {
                    startActivity(ViewerActivity.intentForFile(this@ThreadActivity, file, mime, title))
                } catch (e: IllegalArgumentException) {
                    toast(getString(R.string.chat_media_unavailable))
                }
            } else {
                openWithOtherApp(file, mime)
            }
        }
    }

    /** Videos, audio and other files: hand them to an app that can play or show them. */
    private fun openWithOtherApp(file: File, mime: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                clipData = ClipData.newRawUri(null, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(view, getString(R.string.chat_open_with)))
        } catch (e: IllegalArgumentException) {
            toast(getString(R.string.chat_media_unavailable))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.chat_no_app))
        }
    }

    private fun react(row: MessageRow, emoji: String, remove: Boolean) {
        lifecycleScope.launch {
            relayAttempt { manager.react(row.id, emoji, remove) }
                .onFailure { toast(RelayErrors.message(this@ThreadActivity, it)) }
        }
    }

    private fun retry(row: MessageRow) {
        val tmpId = row.tmpId
        if (tmpId.isEmpty()) return
        runInBackground { manager.retry(tmpId) }
    }

    private fun copy(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.chat_clip_label), text))
        // Android 13 and later confirm the copy themselves.
        if (Build.VERSION.SDK_INT < 33) toast(getString(R.string.chat_copied))
    }

    /**
     * With a pairing it goes on the phone too; without one (an archive an
     * unpair kept) the manager deletes it here only. If the pairing changed
     * while the question was up, it is asked again with the right wording.
     */
    private fun confirmDeleteMessage(row: MessageRow) {
        if (!isShown(row)) return
        val linked = manager.isPaired()
        val id = row.id
        MaterialAlertDialogBuilder(this)
            .setMessage(if (linked) R.string.chat_delete_message_question else R.string.chat_delete_message_question_local)
            .setPositiveButton(R.string.chat_action_delete) { _, _ ->
                // The thread switched under the question (onNewIntent closes it; this is the backstop).
                if (!isShown(row)) return@setPositiveButton
                if (manager.isPaired() != linked) {
                    confirmDeleteMessage(row)
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    relayAttempt { manager.deleteMessage(id) }
                        .onFailure { toast(RelayErrors.message(this@ThreadActivity, it)) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .let(::trackConfirm)
    }

    /** Keeps [dialog] as the open delete question until it is dismissed. */
    private fun trackConfirm(dialog: Dialog) {
        confirmDialog?.takeIf { it !== dialog }?.dismiss()
        confirmDialog = dialog
        dialog.setOnDismissListener { if (confirmDialog === dialog) confirmDialog = null }
    }

    // --- menu ----------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.thread_actions, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val conv = conversation
        val archived = conv?.folderEnum == Folder.ARCHIVE
        menu.findItem(R.id.action_archive)?.apply {
            isVisible = conv != null && (conv.folderEnum == Folder.INBOX || archived)
            setTitle(if (archived) R.string.chat_action_unarchive else R.string.chat_action_archive)
        }
        menu.findItem(R.id.action_mute)?.apply {
            isVisible = conv != null
            setTitle(if (conv?.muted == true) R.string.chat_action_unmute else R.string.chat_action_mute)
        }
        menu.findItem(R.id.action_delete_conversation)?.isVisible = conv != null
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val conv = conversation
        when (item.itemId) {
            android.R.id.home -> {
                openConversations()
                finish()
            }
            R.id.action_archive -> if (conv != null) {
                val archive = conv.folderEnum != Folder.ARCHIVE
                runAction(if (archive) R.string.chat_archived else R.string.chat_unarchived) {
                    manager.setArchived(conv.id, archive)
                }
            }
            R.id.action_mute -> if (conv != null) {
                runAction(if (conv.muted) R.string.chat_unmuted_done else R.string.chat_muted_done) {
                    manager.setMuted(conv.id, !conv.muted)
                }
            }
            R.id.action_delete_conversation -> confirmDeleteConversation()
            R.id.action_link_status -> startActivity(Intent(this, RelayStatusActivity::class.java))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    /**
     * Up: always the conversation list. CLEAR_TOP|SINGLE_TOP returns to the
     * one below this thread when there is one, else starts it (on top of the
     * sticker inbox, or as the task's root after a cold start).
     */
    private fun openConversations() {
        startActivity(
            Intent(this, ConversationsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    /** Like [confirmDeleteMessage]: on the phone too with a pairing, else here only. */
    private fun confirmDeleteConversation() {
        val id = conversation?.id ?: return
        val linked = manager.isPaired()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_delete_conversation_title)
            .setMessage(if (linked) R.string.chat_delete_conversation_body else R.string.chat_delete_conversation_body_local)
            .setPositiveButton(R.string.chat_action_delete) { _, _ ->
                // Deleting is final: never for a conversation that is no longer the one on screen.
                if (id != conversationId) return@setPositiveButton
                if (manager.isPaired() != linked) {
                    confirmDeleteConversation()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    relayAttempt { manager.deleteConversation(id) }
                        .onSuccess { if (id == conversationId) finish() }
                        .onFailure { toast(RelayErrors.message(this@ThreadActivity, it)) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .let(::trackConfirm)
    }

    private fun runAction(doneMessage: Int, block: suspend () -> Unit) {
        lifecycleScope.launch {
            relayAttempt { block() }
                .onSuccess { toast(getString(doneMessage)) }
                .onFailure { toast(RelayErrors.message(this@ThreadActivity, it)) }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).apply { setGravity(Gravity.CENTER, 0, 0) }.show()
    }

    companion object {
        private const val STATE_WINDOW = "window"
        private const val STATE_ATTACHMENT = "attachment"

        private const val PAGE = 50
        private const val RELOAD_SLACK = 50
        private const val MAX_WINDOW = 20_000

        /** A window larger than this shrinks back to [PAGE] once the user is at the newest messages. */
        private const val RESET_WINDOW = 4 * PAGE
        private const val MAX_QUOTE_LOOKUPS = 100
        private const val LOAD_OLDER_THRESHOLD = 3
        private const val RELOAD_DEBOUNCE_MS = 120L
        private const val HIGHLIGHT_MS = 2_000L
        private const val TYPING_PING_MS = 3_000L
        private const val MARK_READ_RETRY_MS = 10_000L
        private const val REMOTE_RETRY_MS = 30_000L
        private const val MAX_PREVIEW_READ = 32 * 1024 * 1024
        private const val ATTACHMENT_THUMB_DP = 96
        private const val DISABLED_ALPHA = 0.38f

        /** The quick reactions offered on long press, in Google Messages' order. */
        val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
    }
}
