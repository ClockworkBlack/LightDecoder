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
import androidx.annotation.VisibleForTesting
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
import com.lightdecoder.chat.Capability
import com.lightdecoder.chat.ChatBackend
import com.lightdecoder.chat.ChatService
import com.lightdecoder.databinding.ActivityThreadBinding
import com.lightdecoder.databinding.SheetMessageActionsBinding
import com.lightdecoder.relay.Folder
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.ReactionDto
import com.lightdecoder.relay.RelayException
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.RelayState
import com.lightdecoder.ui.ViewerActivity
import com.lightdecoder.ui.relay.SignInWebData
import com.lightdecoder.ui.relay.relayAttempt
import com.lightdecoder.ui.relay.runInBackground
import com.lightdecoder.ui.voice.Dictation
import com.lightdecoder.ui.voice.MessageReader
import com.lightdecoder.ui.voice.ReadAloudText
import com.lightdecoder.ui.voice.VoicePrefs
import com.lightdecoder.wa.WaErrors
import com.lightdecoder.wa.WaManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One conversation of either service: its messages from that service's
 * local archive, older history fetched from the phone on scroll, and the
 * composer. Sends go through the link's outbox, so a message typed while
 * offline shows as "sending" and goes out once connected. What the service
 * cannot do (its [ChatBackend.capabilities]) is not offered; where it tells
 * who is online, a one-to-one conversation says so under its title.
 *
 * Extras: [RelayIntents.EXTRA_CONVERSATION_ID] (required),
 * [RelayIntents.EXTRA_SERVICE] (Google Messages without it) and optionally
 * [RelayIntents.EXTRA_MESSAGE_ID] to open at that message.
 */
class ThreadActivity : AppCompatActivity(), MessagesAdapter.Callbacks {

    private lateinit var binding: ActivityThreadBinding

    /** The service this conversation belongs to: its link, and through [repo] its archive. */
    private lateinit var manager: ChatBackend
    private lateinit var repo: ArchiveRepository
    private lateinit var adapter: MessagesAdapter
    private lateinit var media: MediaLoader
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var banner: ConnectionBanner
    private val notifications = ServiceNotifications(this)

    /** Follows [manager]'s state, typing and presence while the screen is started; restarted when the service changes. */
    private var linkJob: Job? = null

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

    /**
     * The conversation whose own rows [messages] holds: null from opening or a
     * thread switch until that conversation's first local read is in. Paging
     * from the phone waits for it: before then [messages] is the empty list a
     * switch leaves, which says nothing about how much the archive holds.
     * (That first read pages the thread itself when it holds less than a page.)
     */
    private var localRowsFor: String? = null
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
     * The long-press sheet and the open question (delete, or send again), if
     * any. Both belong to the conversation they were opened in, so a thread
     * switch closes them.
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

    /** The conversation dictation was started in; a result for another one is dropped. */
    private var dictationFor: String? = null

    private val dictate = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val forConversation = dictationFor
        dictationFor = null
        if (result.resultCode != RESULT_OK || forConversation != conversationId) return@registerForActivityResult
        Dictation.resultText(result.data)?.let(::insertDictated)
    }

    // --- reading aloud ---
    // The reader is made on first use; its text-to-speech engine only on the first thing said.

    private var readerInstance: MessageReader? = null
    private val reader: MessageReader
        get() = readerInstance ?: MessageReader.create(this, readerListener).also { readerInstance = it }

    private val readerListener = object : MessageReader.Listener {
        override fun onSpeakingChanged(speaking: Boolean) = invalidateOptionsMenu()
        override fun onUnavailable() = noteReaderUnavailable()
    }

    /**
     * Incoming messages at or after this time that are not in [readAloudSeen]
     * arrived while the thread was open. Null until the first read of the
     * thread's rows after it opened, came back to the front or was switched:
     * that read sets it, and what it finds is never read out.
     */
    private var readAloudSince: Long? = null
    private val readAloudSeen = HashSet<String>()

    private val onArchiveChange: (ArchiveChange) -> Unit = { change ->
        // The same id can be a conversation of the other service: only this one's archive counts.
        val mine = change.service == manager.service &&
            (change.conversationId == null || change.conversationId == conversationId)
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
        manager = ScreenBackends.get(this, RelayIntents.serviceOf(intent))
        repo = manager.repo
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
        if (VoicePrefs.readNewAloud(this) && !reader.isAvailable) noteReaderUnavailable()
        savedInstanceState?.getString(STATE_REPLY_TO)?.let(::restoreReply)
        // Opened from a message notification on a cold start, the thread is the
        // whole task: Back and Up lead to the conversation list, not out of the app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isTaskRoot) openConversations()
                finish()
            }
        })

        followLink()
    }

    /**
     * Draws [manager]'s link from now on: its state (banner, composer), who
     * is typing, and who is online. Runs while the screen is started.
     */
    private fun followLink() {
        linkJob?.cancel()
        val link = manager
        linkJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { link.state.collect { onState(link, it) } }
                launch { link.typing.collect { renderPeerActivity() } }
                launch { link.presence.collect { renderPeerActivity() } }
            }
        }
    }

    /**
     * The conversation belongs to another service than the one on screen (a
     * notification of the other service was tapped): everything that came
     * from the old one's link and archive is replaced by the new one's.
     */
    private fun switchService(service: ChatService) {
        if (started) ChatVisibility.leave(manager)
        manager = ScreenBackends.get(this, service)
        repo = manager.repo
        if (started) ChatVisibility.enter(manager)
        banner.bind(manager)
        // Pictures are fetched through the link, and the list draws them through the loader.
        media = MediaLoader(manager, lifecycleScope)
        adapter = MessagesAdapter(media, this)
        binding.messages.adapter = adapter
        // Until the new link's state is in: cheap to ask, and nothing is tried while "offline".
        paired = manager.isPaired()
        online = false
        followLink()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val id = intent.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID).orEmpty()
        if (id.isEmpty()) return
        val service = RelayIntents.serviceOf(intent)
        val otherService = service != manager.service
        // The same id in the other service is another conversation.
        if (id != conversationId || otherService) {
            // Everything that belonged to the other conversation goes, above all
            // a half-typed reply: one tap on Send would deliver it to the wrong person.
            // A paging call still running for it is dropped when it returns (loadOlderFromPhone).
            // The notifier's "on screen" mark is per service: the old service's goes with it
            // (onResume, which follows a new intent, sets the new one).
            if (otherService && MessageNotifier.visibleConversationId(manager.service) == conversationId) {
                MessageNotifier.setVisibleConversationId(manager.service, null)
            }
            conversationId = id
            // The long-press sheet and an open question (delete, send again) still act on the old
            // conversation's message or id: close them, and the overflow menu built from its row.
            actionSheet?.dismiss()
            stickerSheet?.dismiss()
            fullSizeAsked.clear()
            fullSizeWanted.clear()
            confirmDialog?.dismiss()
            closeOptionsMenu()
            // Leaving the thread: stop reading its messages; the next one's are new from its first read.
            readerInstance?.stop()
            readAloudSince = null
            dictationFor = null
            windowSize = PAGE
            growWindow = false
            growPending = false
            messages = emptyList()
            // Until the new conversation's own rows are read, nothing pages it from the phone.
            localRowsFor = null
            historyComplete = false
            remoteExhausted = false
            remoteFailedAtMs = 0L
            lastMarkedId = null
            lastMarkAtMs = 0L
            highlightId = null
            binding.input.setText("")
            clearReply()
            clearAttachment()
            submitSeq++
            pendingScrollTo = null
            if (otherService) switchService(service) else adapter.submitList(emptyList())
            // The old conversation's row must not decide the title, the menu, mark-read
            // or who can be replied to until the new one's has been read.
            conversation = null
            participants = emptyList()
            renderConversation()
            if (started) manager.watchPresence(id)
            // Android pauses a thread before handing it a new intent (onResume then sets the mark);
            // one that is still resumed marks the new conversation as on screen right away.
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                MessageNotifier.setVisibleConversationId(manager.service, conversationId)
            }
        }
        targetMessageId = intent.getStringExtra(RelayIntents.EXTRA_MESSAGE_ID)
        reload()
    }

    override fun onStart() {
        super.onStart()
        started = true
        // In front: the service may show this account as online, and tells who else is.
        ChatVisibility.enter(manager)
        if (conversationId.isNotEmpty()) manager.watchPresence(conversationId)
        ArchiveEvents.register(onArchiveChange)
        // Back in front: what arrived meanwhile is not read out, only what comes from now on.
        readAloudSince = null
        updateVoiceInput()
        reload()
    }

    override fun onStop() {
        started = false
        ChatVisibility.leave(manager)
        ArchiveEvents.unregister(onArchiveChange)
        readerInstance?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        readerInstance?.shutdown()
        readerInstance = null
        super.onDestroy()
    }

    /**
     * While this thread is in front its new messages are not notified (the
     * user is reading them), and a notification already up for it goes.
     * onNewIntent runs before onResume, so a switched thread is covered.
     */
    override fun onResume() {
        super.onResume()
        MessageNotifier.setVisibleConversationId(manager.service, conversationId)
        MessageNotifier.cancel(this, conversationId, manager.service)
    }

    override fun onPause() {
        if (MessageNotifier.visibleConversationId(manager.service) == conversationId) {
            MessageNotifier.setVisibleConversationId(manager.service, null)
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_WINDOW, windowSize)
        attachment?.let { outState.putString(STATE_ATTACHMENT, it.toString()) }
        replyTarget?.takeIf { it.conversationId == conversationId }?.let { outState.putString(STATE_REPLY_TO, it.id) }
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

    /**
     * Swipe a message sideways to reply to it, as in Google Messages: the
     * bubble follows the finger a short way and springs back into its place
     * in the thread when let go; past [REPLY_TRIGGER_DP] it opens the reply
     * bar. The swipe never "completes" (a completed ItemTouchHelper swipe
     * leaves the bubble off screen, and the message would vanish from the
     * thread).
     */
    private inner class SwipeToReply : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.START or ItemTouchHelper.END) {
        private val density = resources.displayMetrics.density
        private val maxShift = REPLY_MAX_SHIFT_DP * density
        private val trigger = REPLY_TRIGGER_DP * density

        /** The message the current drag has pulled far enough to reply to. */
        private var armed: RecyclerView.ViewHolder? = null

        override fun getSwipeDirs(recyclerView: RecyclerView, holder: RecyclerView.ViewHolder): Int {
            val item = adapter.currentList.getOrNull(holder.bindingAdapterPosition) as? ThreadItem.Message
            return if (item != null && canReplyTo(item.row)) super.getSwipeDirs(recyclerView, holder) else 0
        }

        // Never reached: the bubble always comes back.
        override fun getSwipeThreshold(holder: RecyclerView.ViewHolder) = Float.MAX_VALUE

        override fun getSwipeEscapeVelocity(defaultValue: Float) = Float.MAX_VALUE

        override fun getSwipeVelocityThreshold(defaultValue: Float) = 0f

        override fun onMove(rv: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder) = false

        override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
            // Cannot happen (see the thresholds); if it does, the bubble goes back and the reply opens.
            replyTo(holder)
            adapter.notifyItemChanged(holder.bindingAdapterPosition)
        }

        override fun onChildDraw(
            c: android.graphics.Canvas,
            recyclerView: RecyclerView,
            holder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean,
        ) {
            val shift = dX.coerceIn(-maxShift, maxShift)
            if (isCurrentlyActive && kotlin.math.abs(shift) >= trigger && armed !== holder) {
                armed = holder
                holder.itemView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            } else if (isCurrentlyActive && kotlin.math.abs(shift) < trigger && armed === holder) {
                armed = null
            }
            super.onChildDraw(c, recyclerView, holder, shift, dY, actionState, isCurrentlyActive)
        }

        /** Let go and back in place: reply if it was pulled far enough. */
        override fun clearView(recyclerView: RecyclerView, holder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, holder)
            holder.itemView.translationX = 0f
            if (armed === holder) {
                armed = null
                replyTo(holder)
            }
        }

        private fun replyTo(holder: RecyclerView.ViewHolder) {
            (adapter.currentList.getOrNull(holder.bindingAdapterPosition) as? ThreadItem.Message)?.let { onReply(it.row) }
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
        val link = manager
        val repo = link.repo
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
            beforeLocalRead?.invoke(id)
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
                quotes = quotesFor(repo, rows),
                historyComplete = repo.syncState(id)?.historyComplete == true,
            )
        }
        if (grow) growPending = false
        if (!isCurrent(link, id)) return@withLock
        val localExhausted = snapshot.rows.size < wanted
        windowSize = maxOf(PAGE, snapshot.rows.size)
        messages = snapshot.rows
        localRowsFor = id
        conversation = snapshot.conversation
        participants = snapshot.participants
        historyComplete = snapshot.historyComplete
        renderConversation()
        readNewAloud(snapshot.rows)

        val scrollTo = target?.takeIf { t -> snapshot.rows.any { it.id == t } }
        if (target != null) targetMessageId = null
        if (scrollTo != null) highlight(scrollTo)
        submit(snapshot.quotes, scrollTo)
        // submit() suspends while it builds the list: a notification tap may have switched
        // threads meanwhile. The reload onNewIntent queued handles the new conversation.
        // (Not "skip unless submit applied": a resubmit() in the same conversation also
        // supersedes it, and mark-read and auto-paging must still run then.)
        if (!isCurrent(link, id)) return@withLock
        markReadIfNeeded()
        // The archive has no more: ask the phone (also fills a thread that was never imported).
        if ((grow && localExhausted) || snapshot.rows.size < PAGE) loadOlderFromPhone(auto = !grow)
    }

    /**
     * Whether conversation [id] of [link] is still the one on screen: a
     * notification tap may have switched the conversation, or the service
     * (where the same id is another conversation), while something waited.
     */
    private fun isCurrent(link: ChatBackend, id: String) = link === manager && id == conversationId

    /** Quoted messages: from the window, else from the archive. Runs on IO. */
    private fun quotesFor(repo: ArchiveRepository, rows: List<MessageRow>): Map<String, Quote> {
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
        if (loadingOlder || growPending || localRowsFor != conversationId) return
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
        // The phone deleted it: it has no history left to page.
        if (conversation?.folderEnum == Folder.DELETED) return
        // Automatic loads stop after a failure for a while, so an error never turns into a loop.
        if (auto && (!online || System.currentTimeMillis() - remoteFailedAtMs < REMOTE_RETRY_MS)) return
        loadingOlder = true
        resubmit()
        val id = conversationId
        val link = manager
        lifecycleScope.launch {
            val result = relayAttempt { link.loadOlder(id, PAGE) }
            loadingOlder = false
            // The screen may have switched to another conversation meanwhile
            // (a notification tap): this result is not about the one shown now.
            if (isCurrent(link, id)) {
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
                        val code = ChatErrors.codeOf(it)
                        if (!auto && code != RelayException.NOT_CONNECTED) toast(errorText(link, code))
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
        renderPeerActivity()
        invalidateOptionsMenu()
        renderComposerState()
    }

    /**
     * Under the title: how many people a group has; for one person, what the
     * service tells about them right now ("online", "typing…", "last seen
     * …") where it does ([Capability.PRESENCE]), else their number.
     * Who is typing shows above the composer, unless the line under the
     * title already says it.
     */
    private fun renderPeerActivity() {
        val conv = conversation
        val others = participants.filter { !it.isMe }
        val typing = manager.typing.value[conversationId].orEmpty()
        val presence = if (conv?.isGroup == true) null else presenceLine(conv, others, typing)
        supportActionBar?.subtitle = when {
            conv?.isGroup == true -> resources.getQuantityString(R.plurals.chat_people, others.size + 1, others.size + 1)
            presence != null -> presence
            others.size == 1 -> others[0].formattedNumber.ifBlank { others[0].number }.takeIf { it != title }
            else -> null
        }
        renderTyping(if (presence != null) emptySet() else typing)
    }

    /**
     * "typing…", "online" or "last seen …" for the other person of a
     * one-to-one conversation; null when the service does not tell, the
     * link is not connected (what it last said may be stale), or nothing is
     * known about them yet.
     */
    private fun presenceLine(conv: ConversationRow?, others: List<ParticipantRow>, typing: Set<String>): String? {
        if (Capability.PRESENCE !in manager.capabilities || conv == null || conv.isGroup || others.size > 1 || !online) return null
        if (typing.isNotEmpty()) return getString(R.string.chat_presence_typing)
        val known = manager.presence.value
        // Presence is kept per person; a one-to-one conversation's id is that person's too.
        val presence = others.firstOrNull()?.let { known[it.id] } ?: known[conv.id] ?: return null
        return when {
            presence.online -> getString(R.string.chat_presence_online)
            presence.lastSeenMs > 0 -> TimeFormat.lastSeen(this, presence.lastSeenMs)
            else -> null
        }
    }

    private fun onState(link: ChatBackend, state: RelayState) {
        lifecycleScope.launch {
            val isPaired = withContext(Dispatchers.IO) { link.isPaired() }
            // The screen moved to the other service's conversation meanwhile: its own state follows.
            if (link !== manager) return@launch
            paired = isPaired
            val wasOnline = online
            online = paired && state.isOnline
            banner.render(state, paired)
            renderComposerState()
            renderPeerActivity()
            if (online && !wasOnline) {
                // Asked again on every connection: a subscription does not outlive one.
                if (started) link.watchPresence(conversationId)
                // Originals of pictures shown while offline: ask for them now.
                for ((key, wanted) in fullSizeWanted.entries.toList()) requestOriginal(key, wanted.first, wanted.second)
                // Pictures that failed to download while offline: try again now.
                if (media.hadFailures) {
                    media.clearFailures()
                    adapter.notifyItemRangeChanged(0, adapter.itemCount)
                }
                // Only once this thread's own rows are in (see localRowsFor). During a thread
                // switch the new thread's first load pages it, reading `online` when it is done.
                if (localRowsFor == conversationId && messages.size < PAGE) loadOlderFromPhone(auto = true)
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
        val link = manager
        if (conv.folderEnum == Folder.DELETED) {
            // Gone from the phone: read here only.
            if (conv.unread) runInBackground { link.repo.markConversationRead(id) }
            return
        }
        lifecycleScope.launch {
            relayAttempt { link.markRead(id) }.onFailure {
                withContext(Dispatchers.IO) { link.repo.markConversationRead(id) }
            }
        }
    }

    // --- composer ------------------------------------------------------------

    private fun setUpComposer() {
        binding.send.setOnClickListener { send() }
        binding.attach.setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
        binding.stickers.setOnClickListener { showStickers() }
        binding.voiceInput.setOnClickListener { startDictation() }
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
        // The phone deleted it: there is nothing on the phone to send into any more.
        val deleted = conversation?.folderEnum == Folder.DELETED
        val readOnly = conversation?.readOnly == true || deleted
        binding.readOnlyText.setText(if (deleted) R.string.chat_deleted_banner else R.string.chat_read_only)
        binding.readOnlyText.visibility = if (readOnly) View.VISIBLE else View.GONE
        binding.composer.visibility = if (readOnly) View.GONE else View.VISIBLE
        if (readOnly) {
            clearReply()
            clearAttachment()
        }
        binding.input.isEnabled = paired
        binding.attach.isEnabled = paired
        binding.stickers.isEnabled = paired
        binding.voiceInput.isEnabled = paired
        binding.input.hint = getString(if (paired) ServiceUi.composeHint(manager.service) else R.string.chat_compose_unpaired)
        updateSendEnabled()
    }

    private fun updateSendEnabled() {
        val hasContent = !binding.input.text.isNullOrBlank() || attachment != null
        binding.send.isEnabled = paired && hasContent
        // The tint has no disabled state: dim it the way Material dims disabled controls.
        binding.send.alpha = if (binding.send.isEnabled) 1f else DISABLED_ALPHA
    }

    // --- voice ------------------------------------------------------------------

    /** The dictation button, only when some app can recognise speech. */
    private fun updateVoiceInput() {
        binding.voiceInput.visibility = if (Dictation.isAvailable(packageManager)) View.VISIBLE else View.GONE
    }

    private fun startDictation() {
        if (!binding.input.isEnabled) return
        dictationFor = conversationId
        try {
            dictate.launch(Dictation.intent(this))
        } catch (e: ActivityNotFoundException) {
            dictationFor = null
            binding.voiceInput.visibility = View.GONE
            toast(getString(R.string.chat_voice_unavailable))
        }
    }

    /** Recognised speech goes in at the cursor (over a selection), for the user to check and send. */
    private fun insertDictated(spoken: String) {
        if (!binding.input.isEnabled || binding.composer.visibility != View.VISIBLE) return
        val editable = binding.input.text ?: return
        // No cursor yet (never focused): at the end.
        val rawStart = binding.input.selectionStart
        val rawEnd = binding.input.selectionEnd
        val a = if (rawStart < 0) editable.length else rawStart.coerceIn(0, editable.length)
        val b = if (rawEnd < 0) a else rawEnd.coerceIn(0, editable.length)
        val start = minOf(a, b)
        val end = maxOf(a, b)
        val piece = Dictation.piece(editable, start, end, spoken)
        editable.replace(start, end, piece)
        binding.input.setSelection((start + piece.length).coerceAtMost(binding.input.length()))
        binding.input.requestFocus()
    }

    private fun readAloud(row: MessageRow) {
        if (!isShown(row)) return
        val sentence = ReadAloudText.sentence(this, row) ?: return
        if (!reader.isAvailable) {
            noteReaderUnavailable()
            return
        }
        reader.speak(sentence, interrupt = true)
    }

    /**
     * With "Read new messages aloud" on, says the incoming messages that
     * arrived since the thread opened (or came back to the front). Messages
     * loaded from further back (scrolling up, a jump to an old one) are older
     * than that, so they are never read out.
     */
    private fun readNewAloud(rows: List<MessageRow>) {
        val incoming = rows.filter(ReadAloudText::isIncoming)
        val since = readAloudSince
        if (since == null) {
            // The phone's clock and this one may differ a little: a message sent just
            // before the thread opened (and not here yet) still counts as new.
            val newest = incoming.maxOfOrNull { it.timestampMs } ?: Long.MIN_VALUE
            val start = maxOf(newest, System.currentTimeMillis() - READ_ALOUD_CLOCK_SLACK_MS)
            readAloudSince = start
            readAloudSeen.clear()
            incoming.filter { it.timestampMs >= start }.mapTo(readAloudSeen) { it.id }
            return
        }
        val fresh = incoming.filter { it.timestampMs >= since && it.id !in readAloudSeen }
        if (fresh.isEmpty()) return
        fresh.mapTo(readAloudSeen) { it.id }
        if (!started || !VoicePrefs.readNewAloud(this) || !reader.isAvailable) return
        // A burst (the link catching up) is not read out in full.
        for (row in fresh.sortedBy { it.timestampMs }.takeLast(MAX_READ_AT_ONCE)) {
            ReadAloudText.sentence(this, row)?.let { reader.speak(it, interrupt = false) }
        }
    }

    /** No text-to-speech: said once per app run, and the options go. */
    private fun noteReaderUnavailable() {
        if (!readerUnavailableShown) {
            readerUnavailableShown = true
            toast(getString(R.string.chat_read_aloud_unavailable))
        }
        actionSheet?.findViewById<View>(R.id.actionReadAloud)?.visibility = View.GONE
        invalidateOptionsMenu()
    }

    /** At most one ping every few seconds; the manager throttles too. */
    private fun pingTyping() {
        if (!paired || !online) return
        val now = System.currentTimeMillis()
        if (now - lastTypingPingMs < TYPING_PING_MS) return
        lastTypingPingMs = now
        val id = conversationId
        val link = manager
        runInBackground { link.typing(id) }
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
        val link = manager
        lifecycleScope.launch {
            // The outbox writes to the archive (and copies an attachment), so not on the main thread.
            val result = withContext(Dispatchers.IO) {
                relayAttempt {
                    if (file != null) link.sendMedia(id, file, text.ifEmpty { null }, replyTo)
                    else link.sendText(id, text, replyTo)
                }
            }
            result.onFailure { toast(errorText(link, it)) }
        }
    }

    // --- stickers ------------------------------------------------------------

    private var stickerSheet: BottomSheetDialog? = null

    private val makeSticker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // A new sticker was saved: show the library again with it in front.
        if (result.resultCode == RESULT_OK) showStickers()
    }

    /** The user's own stickers: tap sends one to this conversation, touch-and-hold deletes it. */
    private fun showStickers() {
        stickerSheet?.dismiss()
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_stickers, null)
        val grid = view.findViewById<RecyclerView>(R.id.stickersGrid)
        val empty = view.findViewById<View>(R.id.stickersEmpty)
        grid.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, STICKER_COLUMNS)
        view.findViewById<View>(R.id.stickersNew).setOnClickListener {
            sheet.dismiss()
            makeSticker.launch(com.lightdecoder.ui.sticker.StickerMakerActivity.intent(this))
        }
        sheet.setContentView(view)
        sheet.setOnDismissListener { if (stickerSheet === sheet) stickerSheet = null }
        stickerSheet = sheet
        sheet.show()
        val side = (STICKER_TILE_DP * resources.displayMetrics.density).toInt()
        fun refresh() {
            lifecycleScope.launch {
                val tiles = withContext(Dispatchers.IO) {
                    com.lightdecoder.sticker.StickerLibrary.list(applicationContext).mapNotNull { file ->
                        runCatching { MediaLoader.decodePreview(file.readBytes(), side) }.getOrNull()?.let { file to it }
                    }
                }
                empty.visibility = if (tiles.isEmpty()) View.VISIBLE else View.GONE
                grid.adapter = StickerTiles(
                    tiles,
                    onTap = { file ->
                        sheet.dismiss()
                        sendSticker(file)
                    },
                    onHold = { file ->
                        MaterialAlertDialogBuilder(this@ThreadActivity)
                            .setTitle(R.string.sticker_delete_title)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.sticker_delete_confirm) { _, _ ->
                                lifecycleScope.launch {
                                    withContext(Dispatchers.IO) {
                                        com.lightdecoder.sticker.StickerLibrary.delete(applicationContext, file)
                                    }
                                    refresh()
                                }
                            }
                            .show()
                    },
                )
            }
        }
        refresh()
    }

    private fun sendSticker(file: File) {
        val id = conversationId
        val link = manager
        scrollToBottomNext = true
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { relayAttempt { link.sendMedia(id, Uri.fromFile(file), null, null) } }
                .onFailure { toast(errorText(link, it)) }
        }
    }

    private class StickerTiles(
        private val tiles: List<Pair<File, Bitmap>>,
        private val onTap: (File) -> Unit,
        private val onHold: (File) -> Unit,
    ) : RecyclerView.Adapter<StickerTiles.Holder>() {
        class Holder(val image: android.widget.ImageView) : RecyclerView.ViewHolder(image)

        override fun getItemCount() = tiles.size

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) = Holder(
            android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_sticker_tile, parent, false) as android.widget.ImageView
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val (file, bitmap) = tiles[position]
            holder.image.setImageBitmap(bitmap)
            holder.image.contentDescription = holder.image.context.getString(R.string.sticker_tile)
            holder.image.setOnClickListener { onTap(file) }
            holder.image.setOnLongClickListener {
                onHold(file)
                true
            }
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun setAttachment(uri: Uri) {
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

    /**
     * The reply bar after a rotation (or the process being recreated): the
     * message is read again from the archive. Gone (deleted meanwhile), or a
     * reply picked or a thread switched to in the meantime: nothing to restore.
     */
    private fun restoreReply(messageId: String) {
        val id = conversationId
        val link = manager
        lifecycleScope.launch {
            val row = withContext(Dispatchers.IO) { link.repo.message(messageId) } ?: return@launch
            if (!isCurrent(link, id) || replyTarget != null || row.conversationId != id) return@launch
            onReply(row)
        }
    }

    // Email replies always go to the whole thread: no quoting one message.
    private fun canReplyTo(row: MessageRow) =
        manager.service != ChatService.GMAIL && !row.isLocal && row.direction != "system" && conversation?.readOnly != true && conversation?.folderEnum != Folder.DELETED

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
        // Ready to type the reply, with the message replied to still in its place above.
        androidx.core.view.WindowCompat.getInsetsController(window, binding.input)
            .show(androidx.core.view.WindowInsetsCompat.Type.ime())
    }

    override fun onMessageLongPress(row: MessageRow) {
        if (row.direction == "system") return
        actionSheet?.dismiss()
        val sheet = BottomSheetDialog(this)
        val b = SheetMessageActionsBinding.inflate(layoutInflater)
        val real = !row.isLocal
        val mine = row.reactions.firstOrNull { it.fromMe }?.emoji

        // Email has no reactions.
        val reacts = real && manager.service != ChatService.GMAIL
        b.reactionRow.visibility = if (reacts) View.VISIBLE else View.GONE
        if (reacts) {
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
        val sayable = reader.isAvailable && ReadAloudText.sentence(this, row) != null
        b.actionReadAloud.visibility = if (sayable) View.VISIBLE else View.GONE
        b.actionReadAloud.setOnClickListener {
            sheet.dismiss()
            readAloud(row)
        }
        b.actionRetry.visibility = if (MessageStatus.canRetryLocally(row)) View.VISIBLE else View.GONE
        b.actionRetry.setOnClickListener {
            sheet.dismiss()
            if (isShown(row)) retryOrAsk(row)
        }
        val discardable = row.isLocal && row.localState == LocalState.FAILED
        b.actionDiscard.visibility = if (discardable) View.VISIBLE else View.GONE
        b.actionDiscard.setOnClickListener {
            sheet.dismiss()
            if (!isShown(row)) return@setOnClickListener
            val tmpId = row.tmpId
            val link = manager
            runInBackground { link.discardFailed(tmpId) }
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
        if (MessageStatus.canRetryLocally(row)) retryOrAsk(row) else toast(getString(ServiceUi.failedOnPhone(manager.service)))
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

    /** Originals already asked of the phone from this screen (asked once each, without a tap). */
    private val fullSizeAsked = HashSet<String>()

    /** Originals to ask for once the link is connected: shown while it was not. */
    private val fullSizeWanted = LinkedHashMap<String, Pair<String, String>>()

    /**
     * A picture the phone only has a thumbnail of so far came on screen: ask
     * for the original, quietly (or as soon as the link is connected). It
     * arrives as an update of the message, which then shows it full size.
     */
    override fun onLoadFullSize(row: MessageRow, part: MediaRow) {
        // Only a service whose phone keeps originals back can be asked for them.
        if (Capability.FULL_SIZE_REQUEST !in manager.capabilities) return
        val key = row.id + "|" + part.partId
        if (key in fullSizeAsked) return
        if (!online) {
            fullSizeWanted[key] = row.id to part.partId
            return
        }
        requestOriginal(key, row.id, part.partId)
    }

    private fun requestOriginal(key: String, messageId: String, partId: String) {
        fullSizeWanted.remove(key)
        if (!fullSizeAsked.add(key)) return
        val forConversation = conversationId
        val link = manager
        lifecycleScope.launch {
            relayAttempt { link.requestFullSize(messageId, partId) }
                .onFailure {
                    fullSizeAsked.remove(key)
                    // Not connected after all: ask again when it is (unless the thread changed).
                    if (isCurrent(link, forConversation)) fullSizeWanted[key] = messageId to partId
                }
        }
    }

    override fun onMediaTap(row: MessageRow, part: MediaRow) {
        val link = manager
        if (PictureGalleryActivity.isPicture(part)) {
            // Pictures open full screen, zoomable, with the conversation's other pictures a swipe away.
            startActivity(PictureGalleryActivity.intent(this, link.service, conversationId, row.id, part.partId, title))
            return
        }
        lifecycleScope.launch {
            val local = listOf(part.localPath, if (part.needsFullSize) part.thumbPath else "")
                .firstOrNull { it.isNotEmpty() && File(it).isFile }?.let(::File)
            val file = local ?: run {
                toast(getString(R.string.chat_media_downloading))
                relayAttempt { link.mediaFile(row.id, part.partId, thumbnail = part.needsFullSize) }
                    .getOrElse {
                        // not_connected: it can be downloaded, just not until the link is connected.
                        val code = ChatErrors.codeOf(it)
                        toast(
                            if (code == RelayException.NOT_CONNECTED) getString(ServiceUi.mediaNeedsConnection(link.service))
                            else errorText(link, code)
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
        val link = manager
        lifecycleScope.launch {
            relayAttempt { link.react(row.id, emoji, remove) }
                .onFailure { toast(errorText(link, it)) }
        }
    }

    /**
     * A send that definitely did not go out is retried at once. One whose
     * outcome is unknown may have been sent already, so the user is asked first.
     */
    private fun retryOrAsk(row: MessageRow) {
        if (MessageStatus.outcomeUnknown(row)) confirmResend(row) else retry(row)
    }

    private fun retry(row: MessageRow) {
        val tmpId = row.tmpId
        if (tmpId.isEmpty()) return
        val link = manager
        runInBackground { link.retry(tmpId) }
    }

    /**
     * "Send again?" for a send the phone never confirmed: sending it again
     * could deliver it twice. Send again, Discard (only the copy here), or Cancel.
     * Like the delete questions, it belongs to the thread on screen.
     */
    private fun confirmResend(row: MessageRow) {
        if (!isShown(row)) return
        val tmpId = row.tmpId
        val link = manager
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_resend_title)
            .setMessage(ServiceUi.resendBody(link.service))
            .setPositiveButton(R.string.chat_resend_send) { _, _ ->
                if (isShown(row)) retry(row)
            }
            .setNeutralButton(R.string.chat_action_discard) { _, _ ->
                if (isShown(row)) runInBackground { link.discardFailed(tmpId) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .let(::trackConfirm)
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
     *
     * Where the service can take a message back from everyone
     * ([Capability.DELETE_FOR_EVERYONE]), the question is which: "for me"
     * (this account's devices), or for one's own messages also "for everyone".
     */
    private fun confirmDeleteMessage(row: MessageRow) {
        if (!isShown(row)) return
        val link = manager
        val linked = link.isPaired()
        val id = row.id
        fun delete(forEveryone: Boolean) {
            // The thread switched under the question (onNewIntent closes it; this is the backstop).
            if (!isShown(row) || link !== manager) return
            if (link.isPaired() != linked) {
                confirmDeleteMessage(row)
                return
            }
            lifecycleScope.launch {
                relayAttempt { if (forEveryone) deleteForEveryone(link, id) else link.deleteMessage(id) }
                    .onFailure { toast(errorText(link, it)) }
            }
        }
        val builder = MaterialAlertDialogBuilder(this)
        if (linked && Capability.DELETE_FOR_EVERYONE in link.capabilities) {
            if (row.isOutgoing) {
                builder.setMessage(R.string.chat_delete_message_choice)
                    .setPositiveButton(R.string.chat_delete_for_everyone) { _, _ -> delete(forEveryone = true) }
                    .setNeutralButton(R.string.chat_delete_for_me) { _, _ -> delete(forEveryone = false) }
            } else {
                builder.setMessage(R.string.chat_delete_message_for_me_question)
                    .setPositiveButton(R.string.chat_delete_for_me) { _, _ -> delete(forEveryone = false) }
            }
        } else {
            builder.setMessage(if (linked) R.string.chat_delete_message_question else R.string.chat_delete_message_question_local)
                .setPositiveButton(R.string.chat_action_delete) { _, _ -> delete(forEveryone = false) }
        }
        builder.setNegativeButton(android.R.string.cancel, null)
            .show()
            .let(::trackConfirm)
    }

    /** "Delete for everyone" is the one thing [ChatBackend] leaves to the service's own manager. */
    private suspend fun deleteForEveryone(link: ChatBackend, messageId: String) {
        if (link is WaManager) link.deleteMessage(messageId, forEveryone = true)
        else throw RelayException(WaErrors.UNSUPPORTED, "no delete for everyone")
    }

    /** Keeps [dialog] as the open question (delete, or send again) until it is dismissed. */
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
        val deleted = conv?.folderEnum == Folder.DELETED
        menu.findItem(R.id.action_not_spam)?.isVisible =
            conv?.folderEnum == Folder.SPAM && Capability.SPAM_FOLDER in manager.capabilities
        // Deleted on the phone already: what is left is this device's copy.
        menu.findItem(R.id.action_restore)?.isVisible = deleted
        menu.findItem(R.id.action_delete_forever)?.isVisible = deleted
        menu.findItem(R.id.action_mute)?.apply {
            isVisible = conv != null && !deleted
            setTitle(if (conv?.muted == true) R.string.chat_action_unmute else R.string.chat_action_mute)
        }
        menu.findItem(R.id.action_delete_conversation)?.isVisible = conv != null && !deleted
        menu.findItem(R.id.action_read_new_aloud)?.apply {
            isVisible = reader.isAvailable
            isChecked = VoicePrefs.readNewAloud(this@ThreadActivity)
        }
        menu.findItem(R.id.action_stop_reading)?.isVisible = readerInstance?.isSpeaking == true
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
                    it.setArchived(conv.id, archive)
                }
            }
            R.id.action_mute -> if (conv != null) {
                runAction(if (conv.muted) R.string.chat_unmuted_done else R.string.chat_muted_done) {
                    it.setMuted(conv.id, !conv.muted)
                }
            }
            R.id.action_not_spam -> if (conv != null) {
                runAction(R.string.chat_not_spam_done) { it.markNotSpam(conv.id) }
            }
            R.id.action_restore -> if (conv != null) {
                runAction(R.string.chat_restored) { it.restoreDeletedLocally(conv.id) }
            }
            R.id.action_delete_forever -> confirmDeleteForever()
            R.id.action_delete_conversation -> confirmDeleteConversation()
            R.id.action_link_status -> startActivity(ServiceUi.status(this, manager.service))
            R.id.action_read_new_aloud -> {
                val on = !VoicePrefs.readNewAloud(this)
                VoicePrefs.setReadNewAloud(this, on)
                item.isChecked = on
                if (!on) readerInstance?.stop()
                invalidateOptionsMenu()
            }
            R.id.action_stop_reading -> {
                readerInstance?.stop()
                invalidateOptionsMenu()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    /**
     * Up: always the conversation list, on this conversation's service.
     * CLEAR_TOP|SINGLE_TOP returns to the one below this thread when there
     * is one, else starts it (on top of the sticker inbox, or as the task's
     * root after a cold start).
     */
    private fun openConversations() {
        startActivity(
            Intent(this, ConversationsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(RelayIntents.EXTRA_SERVICE, manager.service.key)
        )
    }

    /** Like [confirmDeleteMessage]: on the phone too with a pairing, else here only. */
    private fun confirmDeleteConversation() {
        val id = conversation?.id ?: return
        val link = manager
        val linked = link.isPaired()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_delete_conversation_title)
            .setMessage(
                if (linked) ServiceUi.deleteConversationBody(link.service) else R.string.chat_delete_conversation_body_local
            )
            .setPositiveButton(R.string.chat_action_delete) { _, _ ->
                // Deleting is final: never for a conversation that is no longer the one on screen.
                if (!isCurrent(link, id)) return@setPositiveButton
                if (link.isPaired() != linked) {
                    confirmDeleteConversation()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    relayAttempt { link.deleteConversation(id) }
                        .onSuccess { if (isCurrent(link, id)) finish() }
                        .onFailure { toast(errorText(link, it)) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .let(::trackConfirm)
    }

    /** A conversation the phone deleted, removed from this device for good. */
    private fun confirmDeleteForever() {
        val id = conversation?.id ?: return
        val link = manager
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_delete_forever_title)
            .setMessage(R.string.chat_delete_forever_body)
            .setPositiveButton(R.string.chat_action_delete_forever) { _, _ ->
                if (!isCurrent(link, id)) return@setPositiveButton
                lifecycleScope.launch {
                    relayAttempt { link.purgeDeleted(id) }
                        .onSuccess { if (isCurrent(link, id)) finish() }
                        .onFailure { toast(errorText(link, it)) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .let(::trackConfirm)
    }

    /** Runs [block] on the service this conversation belongs to, and says how it went. */
    private fun runAction(doneMessage: Int, block: suspend (ChatBackend) -> Unit) {
        val link = manager
        lifecycleScope.launch {
            relayAttempt { block(link) }
                .onSuccess { toast(getString(doneMessage)) }
                .onFailure { toast(errorText(link, it)) }
        }
    }

    /** A failure of [link], in its service's words. */
    private fun errorText(link: ChatBackend, t: Throwable): String = ChatErrors.message(this, link.service, t)

    private fun errorText(link: ChatBackend, code: String): String = ChatErrors.message(this, link.service, code)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).apply { setGravity(Gravity.CENTER, 0, 0) }.show()
    }

    companion object {
        private const val STATE_WINDOW = "window"
        private const val STATE_ATTACHMENT = "attachment"

        /** How far a bubble follows a swipe to reply, and how far it must go to start the reply. */
        private const val REPLY_MAX_SHIFT_DP = 96
        private const val REPLY_TRIGGER_DP = 64
        private const val STICKER_COLUMNS = 4
        private const val STICKER_TILE_DP = 96
        private const val STATE_REPLY_TO = "replyTo"

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

        /** How far behind this phone's clock a message may be stamped and still count as arriving now. */
        private const val READ_ALOUD_CLOCK_SLACK_MS = 2 * 60_000L

        /** The most new messages read out from one batch of arrivals (the newest ones). */
        private const val MAX_READ_AT_ONCE = 5

        /** The "no text-to-speech" toast was shown in this app run. */
        @VisibleForTesting
        internal var readerUnavailableShown = false

        /** The quick reactions offered on long press, in Google Messages' order. */
        val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

        /**
         * Tests only: runs on the IO thread before every read of a
         * conversation's rows, with its id (e.g. to hold one, as a busy database would).
         */
        @VisibleForTesting
        @Volatile
        internal var beforeLocalRead: ((String) -> Unit)? = null
    }
}
