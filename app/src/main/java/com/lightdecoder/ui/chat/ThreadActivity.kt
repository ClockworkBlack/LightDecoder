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
import com.lightdecoder.imessage.direct.UnsendWindow
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

    /** What the toolbar's avatar was last drawn for (name, key, colour). */
    private var shownAvatar: Triple<String, String, String>? = null

    /** How many of the newest messages are shown; grows as the user scrolls up. */
    private var windowSize = PAGE
    private var growWindow = false

    /** A wider window was asked for and not loaded yet; scroll events must not widen it again. */
    private var growPending = false
    private var messages: List<MessageRow> = emptyList()

    /**
     * Blue Bubbles: the iMessage chat with this conversation's person, whose
     * messages are shown here among the texts (as on an iPhone), and the
     * ids of the ones on screen. Null and empty otherwise.
     */
    private var companionId: String? = null
    private var iMessageIds: Set<String> = emptySet()

    /**
     * Every iMessage chat whose messages are shown here: [companionId] and the person's older ones
     * ([BlueBubblesLinks.alsoShown]). Their rows are iMessage's ([backendFor]). Empty otherwise.
     */
    private var iMessageChats: Set<String> = emptySet()

    /** "Delivered" / "Read …" under the user's latest iMessages, as the last load found them. */
    private var receipts: Map<String, Receipt> = emptyMap()

    /** The iMessage chat last told the user is typing ([pingTyping]); told it stopped when the composer is emptied. */
    private var typingIn: String? = null

    /** The newest incoming message of the companion chat a read receipt was asked for (direct mode). */
    private var companionReadId: String? = null

    /**
     * The iMessage chats on screen the iMessage library can reach ([com.lightdecoder.imessage.direct.DirectLink.canSend]),
     * as the last load found them: the long-press sheet offers tapbacks, Edit and Unsend by it
     * without reading the archive on the main thread. Empty without the library.
     */
    private var directReachable: Set<String> = emptySet()
    private val iMessageLink: com.lightdecoder.imessage.IMessageManager
        get() = ScreenBackends.get(this, ChatService.IMESSAGE) as com.lightdecoder.imessage.IMessageManager

    /** The service [row] is from: iMessage for the iMessage chats shown in this Messages conversation, else this conversation's. */
    private fun backendFor(row: MessageRow): ChatBackend =
        if (manager.service == ChatService.MESSAGES && row.conversationId in iMessageChats) iMessageLink else manager

    /** [backendFor] by a shown message's id (the pictures' loader knows only that). */
    private fun backendForMessage(messageId: String): ChatBackend =
        if (manager.service == ChatService.MESSAGES && messageId in iMessageIds) iMessageLink else manager

    /** Whether [row] is one of the user's iMessages whose receipts come from Apple (sent through the iMessage library). */
    private fun appleReceipts(row: MessageRow, service: ChatService, iMessageRows: Set<String>): Boolean =
        com.lightdecoder.imessage.direct.DirectArchive.isImessage(row.id) && (service == ChatService.IMESSAGE || row.id in iMessageRows)

    /** Whether the iMessage library is set up: nothing then ever opens the OpenBubbles app. */
    private fun directMode(): Boolean = com.lightdecoder.imessage.direct.DirectLink.get(this).active

    /** Whether a text typed now goes as an iMessage (the companion chat can take it). */
    private fun sendsAsIMessage(text: String? = null): Boolean {
        val companion = companionId ?: return false
        return iMessageLink.canReply(companion, text)
    }

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
    /** What the next message carries: pictures (several at once), a video or a voice message. */
    private var attachments: List<Uri> = emptyList()

    /**
     * The long-press sheet and the open question (delete, or send again), if
     * any. Both belong to the conversation they were opened in, so a thread
     * switch closes them.
     */
    private var actionSheet: Dialog? = null
    private var confirmDialog: Dialog? = null

    /** An iMessage games picker ("Games") and its "Send this game?", for this conversation; closed with it. */
    private var gamesSheet: com.lightdecoder.imessage.madrid.MadridHost.Sheet? = null

    private var started = false
    private var paired = true
    private var online = false
    private var lastMarkedId: String? = null
    private var lastMarkAtMs = 0L
    private var lastTypingPingMs = 0L
    private var remoteFailedAtMs = 0L
    private var scrollToBottomNext = false

    // Several pictures at once: they go out together, one after another, and show as one stack.
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS)) { uris ->
        if (uris.isNotEmpty()) setAttachments((attachments + uris).distinct().take(MAX_ATTACHMENTS))
    }

    // --- voice messages ---

    /** Plays voice messages inside their bubbles, one at a time. */
    private val voicePlayer = VoicePlayer()

    /** The recording under way, its file and when it started; null when not recording. */
    private var recorder: VoiceRecorder? = null
    private var recordingFile: File? = null
    private var recordingStartedMs = 0L
    private val recordingTick = object : Runnable {
        override fun run() {
            if (recorder == null) return
            showRecordingTime()
            binding.recordingTime.postDelayed(this, 500)
        }
    }

    private val askMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else toast(getString(R.string.chat_voice_permission_needed))
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
        // Blue Bubbles: the person's iMessages are shown here too (and one may have just appeared).
        val companion = change.service == ChatService.IMESSAGE && manager.service == ChatService.MESSAGES &&
            com.lightdecoder.imessage.IMessagePrefs.enabled(this) &&
            (companionId == null || change.conversationId == null || change.conversationId in iMessageChats)
        if ((mine || companion) && change.kind != ArchiveChange.Kind.HEALTH) scheduleReload()
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
        media = MediaLoader(manager, lifecycleScope, ::backendForMessage)

        conversationId = intent.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID).orEmpty()
        if (conversationId.isEmpty()) {
            finish()
            return
        }
        if (savedInstanceState == null) {
            targetMessageId = intent.getStringExtra(RelayIntents.EXTRA_MESSAGE_ID)
        } else {
            windowSize = savedInstanceState.getInt(STATE_WINDOW, PAGE)
            savedInstanceState.getStringArrayList(STATE_ATTACHMENTS)?.map(Uri::parse)?.takeIf { it.isNotEmpty() }?.let(::setAttachments)
        }

        setUpList()
        setUpComposer()
        lifecycleScope.launch { voicePlayer.state.collect { adapter.updateVoiceViews(binding.messages, it) } }
        if (VoicePrefs.readNewAloud(this) && !reader.isAvailable) noteReaderUnavailable()
        savedInstanceState?.getString(STATE_REPLY_TO)?.let(::restoreReply)
        if (savedInstanceState == null) takeShared(intent)
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
     * is typing (in the person's iMessage chats shown here too), and who is
     * online. Runs while the screen is started.
     */
    private fun followLink() {
        linkJob?.cancel()
        val link = manager
        linkJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { link.state.collect { onState(link, it) } }
                launch { link.typing.collect { renderPeerActivity() } }
                launch { link.presence.collect { renderPeerActivity() } }
                // Blue Bubbles: the person's iMessages are shown here, and so is who types in them.
                if (link.service == ChatService.MESSAGES && com.lightdecoder.imessage.IMessagePrefs.enabled(this@ThreadActivity)) {
                    launch { iMessageLink.typing.collect { renderPeerActivity() } }
                }
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
        media = MediaLoader(manager, lifecycleScope, ::backendForMessage)
        adapter = MessagesAdapter(media, this).also {
            it.blueOutgoing = service == ChatService.IMESSAGE
            it.blueFor = { row -> row.id in iMessageIds }
            it.appleReceipts = { row -> appleReceipts(row, manager.service, iMessageIds) }
            it.voiceDuration = voicePlayer::durationOf
            it.voiceState = voicePlayer.state.value
        }
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
            gamesSheet?.dismiss()
            gamesSheet = null
            closeOptionsMenu()
            // Leaving the thread: stop reading its messages; the next one's are new from its first read.
            readerInstance?.stop()
            readAloudSince = null
            dictationFor = null
            windowSize = PAGE
            growWindow = false
            growPending = false
            messages = emptyList()
            companionId?.let { if (MessageNotifier.visibleConversationId(ChatService.IMESSAGE) == it) MessageNotifier.setVisibleConversationId(ChatService.IMESSAGE, null) }
            companionId = null
            iMessageIds = emptySet()
            iMessageChats = emptySet()
            receipts = emptyMap()
            companionReadId = null
            directReachable = emptySet()
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
            cancelRecording(quietly = true)
            voicePlayer.release()
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
        takeShared(intent)
        reload()
    }

    /**
     * What another app shared to this conversation (the share screen's
     * intent) goes into the composer, for the user to send. Taken once: a
     * rotation keeps the composer as it then is.
     */
    private fun takeShared(intent: Intent) {
        val text = intent.getStringExtra(RelayIntents.EXTRA_SHARED_TEXT)
        val stream = intent.getStringExtra(RelayIntents.EXTRA_SHARED_STREAM)
        intent.removeExtra(RelayIntents.EXTRA_SHARED_TEXT)
        intent.removeExtra(RelayIntents.EXTRA_SHARED_STREAM)
        if (!text.isNullOrEmpty()) {
            binding.input.setText(text)
            binding.input.setSelection(binding.input.length())
        }
        if (!stream.isNullOrEmpty()) setAttachment(Uri.parse(stream))
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
        voicePlayer.pause()
        super.onStop()
    }

    override fun onDestroy() {
        // The extension app is told its picker closed, and let go.
        gamesSheet?.dismiss()
        gamesSheet = null
        cancelRecording(quietly = true)
        voicePlayer.release()
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
        com.lightdecoder.relay.ReadNotifier.cancel(this, manager.service, conversationId)
        markCompanionVisible()
        // iMessage pictures OpenBubbles has saved meanwhile.
        if (com.lightdecoder.imessage.IMessagePrefs.enabled(this) &&
            (manager.service == ChatService.MESSAGES || manager.service == ChatService.IMESSAGE)
        ) com.lightdecoder.imessage.IMessageMedia.schedule(this)
    }

    override fun onPause() {
        if (MessageNotifier.visibleConversationId(manager.service) == conversationId) {
            MessageNotifier.setVisibleConversationId(manager.service, null)
        }
        companionId?.let { if (MessageNotifier.visibleConversationId(ChatService.IMESSAGE) == it) MessageNotifier.setVisibleConversationId(ChatService.IMESSAGE, null) }
        // Nothing is recorded while the screen is not in front: a half-made recording is thrown away.
        if (recorder != null && !isChangingConfigurations) cancelRecording(quietly = false)
        super.onPause()
    }

    /** The person's iMessages shown here are being read too: they are not notified meanwhile. */
    private fun markCompanionVisible() {
        val companion = companionId ?: return
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        MessageNotifier.setVisibleConversationId(ChatService.IMESSAGE, companion)
        MessageNotifier.cancel(this, companion, ChatService.IMESSAGE)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_WINDOW, windowSize)
        if (attachments.isNotEmpty()) outState.putStringArrayList(STATE_ATTACHMENTS, ArrayList(attachments.map(Uri::toString)))
        replyTarget?.takeIf { it.conversationId == conversationId }?.let { outState.putString(STATE_REPLY_TO, it.id) }
    }

    // --- list ----------------------------------------------------------------

    private fun setUpList() {
        adapter = MessagesAdapter(media, this).also {
            it.blueOutgoing = manager.service == ChatService.IMESSAGE
            it.blueFor = { row -> row.id in iMessageIds }
            it.appleReceipts = { row -> appleReceipts(row, manager.service, iMessageIds) }
            it.voiceDuration = voicePlayer::durationOf
            it.voiceState = voicePlayer.state.value
        }
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
        /** Blue Bubbles: the companion iMessage chat, its messages in the window, and whether it is unread. */
        val companion: String? = null,
        val companionRows: List<MessageRow> = emptyList(),
        val companionUnread: Boolean = false,
        /** An iMessage chat opened by itself that belongs inside this Messages conversation. */
        val moveTo: String? = null,
        /** The iMessage chats shown that the iMessage library can reach ([directReachable]). */
        val directReachable: Set<String> = emptySet(),
        /** Every iMessage chat whose messages are shown ([iMessageChats]). */
        val iMessageChats: Set<String> = emptySet(),
        /** The rows shown: this conversation's and its iMessage chats', oldest first. */
        val shown: List<MessageRow> = rows,
        /** "Delivered" / "Read …" under the user's latest iMessages ([receipts]). */
        val receipts: Map<String, Receipt> = emptyMap(),
        /** Whether iMessage goes through the iMessage library (looked at only with iMessage chats shown). */
        val direct: Boolean = false,
        /**
         * The person's address, to ask Apple whether they have iMessage: a one-to-one Messages
         * conversation with no iMessage chat yet, through the iMessage library.
         */
        val reach: String? = null,
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
            val people = repo.participants(id)
            // Blue Bubbles: the person's iMessages, from as far back as the texts shown go.
            val companion = if (link.service == ChatService.MESSAGES) BlueBubblesLinks.iMessageFor(applicationContext, conversation, people) else null
            val imRepo = companion?.let { iMessageLink.repo }
            val ownFull = rows.size >= wanted
            // With the person's other iMessage chats (from before OpenBubbles was set up again), so their history stays.
            val chats = if (companion == null) emptyList() else listOf(companion) + BlueBubblesLinks.alsoShown(applicationContext, id)
            val companionRows = if (imRepo == null) emptyList() else chats.flatMap { chat ->
                runCatching { imRepo.messages(chat, limit = wanted) }.getOrDefault(emptyList())
            }.distinctBy { it.id }
                .let { im -> if (ownFull && rows.isNotEmpty()) im.filter { it.timestampMs >= rows.first().timestampMs } else im }
            val moveTo = if (link.service == ChatService.IMESSAGE) BlueBubblesLinks.messagesFor(applicationContext, id) else null
            val iMessageChats = if (link.service == ChatService.IMESSAGE) listOf(id) else chats
            val direct = if (iMessageChats.isEmpty()) null else com.lightdecoder.imessage.direct.DirectLink.get(applicationContext)
            val reachable = if (direct?.active != true) emptySet() else iMessageChats.filterTo(HashSet()) { direct.canSend(it) }
            val shown = if (companionRows.isEmpty()) rows else (rows + companionRows).sortedBy { it.timestampMs }
            val companionIds = companionRows.mapTo(HashSet()) { it.id }
            val reach = if (link.service == ChatService.MESSAGES && companion == null) reachAddress(conversation, people) else null
            Snapshot(
                conversation = conversation,
                participants = people,
                rows = rows,
                // Replies among the person's iMessages shown here quote from the iMessage archive.
                quotes = quotesFor(listOfNotNull(repo, imRepo), shown),
                historyComplete = repo.syncState(id)?.historyComplete == true,
                companion = companion,
                companionRows = companionRows,
                companionUnread = companion != null && runCatching { imRepo!!.conversation(companion)?.unread == true }.getOrDefault(false),
                moveTo = moveTo,
                directReachable = reachable,
                iMessageChats = chats.toSet(),
                shown = shown,
                receipts = receiptsFor(shown) { appleReceipts(it, link.service, companionIds) },
                direct = direct?.active == true,
                reach = reach,
            )
        }
        if (grow) growPending = false
        if (!isCurrent(link, id)) return@withLock
        // An iMessage chat that is also a Messages conversation is shown inside it.
        snapshot.moveTo?.let { messagesId ->
            startActivity(
                RelayIntents.thread(this@ThreadActivity, messagesId, service = ChatService.MESSAGES)
                    .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            return@withLock
        }
        companionId = snapshot.companion
        iMessageIds = snapshot.companionRows.mapTo(HashSet()) { it.id }
        iMessageChats = snapshot.iMessageChats
        receipts = snapshot.receipts
        directReachable = snapshot.directReachable
        media.awaitingLabel = if (snapshot.direct) R.string.imessage_media_waiting_direct else R.string.imessage_media_waiting
        markCompanionVisible()
        val localExhausted = snapshot.rows.size < wanted
        windowSize = maxOf(PAGE, snapshot.rows.size)
        messages = snapshot.shown
        localRowsFor = id
        conversation = snapshot.conversation
        participants = snapshot.participants
        historyComplete = snapshot.historyComplete
        renderConversation()
        readNewAloud(messages)

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
        // The companion iMessage chat is read along with it: through the iMessage library Apple is told
        // (once per message, also of one that came while the conversation is open: it is never unread
        // then), else OpenBubbles' Mark As Read button is pressed.
        val companion = snapshot.companion
        if (companion != null && started) {
            val newest = snapshot.companionRows.filter { !it.isOutgoing && it.direction == "in" }.maxByOrNull { it.timestampMs }?.id
            val direct = companion in snapshot.directReachable
            if (snapshot.companionUnread || (direct && newest != null && newest != companionReadId)) {
                if (direct) companionReadId = newest
                val im = iMessageLink
                runInBackground { im.markRead(companion) }
            }
        }
        // As on an iPhone: Apple is asked whether the person has iMessage (once in a while), and if
        // so their iMessage chat is made; it then shows here ([onArchiveChange]) and sends go blue.
        snapshot.reach?.let { address ->
            val direct = com.lightdecoder.imessage.direct.DirectLink.get(applicationContext)
            runInBackground { direct.reachOnce(address) }
        }
        // The archive has no more: ask the phone (also fills a thread that was never imported).
        if ((grow && localExhausted) || snapshot.rows.size < PAGE) loadOlderFromPhone(auto = !grow)
    }

    /**
     * The person's address in a one-to-one Messages conversation, to ask Apple whether they have
     * iMessage ([com.lightdecoder.imessage.direct.DirectLink.reachOnce]): with Blue Bubbles on,
     * through the iMessage library. Null otherwise. Runs on IO.
     */
    private fun reachAddress(conversation: ConversationRow?, people: List<ParticipantRow>): String? {
        if (conversation == null || conversation.isGroup || conversation.readOnly || conversation.folderEnum == Folder.DELETED) return null
        if (!com.lightdecoder.imessage.IMessagePrefs.enabled(applicationContext)) return null
        val other = people.filter { !it.isMe }.singleOrNull() ?: return null
        val address = other.number.ifBlank { other.formattedNumber }.trim()
        if (com.lightdecoder.imessage.IMessageAddress.key(address) == null) return null
        return address.takeIf { com.lightdecoder.imessage.direct.DirectLink.get(applicationContext).active }
    }

    /**
     * Whether conversation [id] of [link] is still the one on screen: a
     * notification tap may have switched the conversation, or the service
     * (where the same id is another conversation), while something waited.
     */
    private fun isCurrent(link: ChatBackend, id: String) = link === manager && id == conversationId

    /**
     * "Delivered" under the user's latest delivered iMessage and "Read …" under the latest read one,
     * with when it was seen to be read ([com.lightdecoder.reactions.ReadTimes]). Runs on IO.
     */
    private fun receiptsFor(rows: List<MessageRow>, hasReceipts: (MessageRow) -> Boolean): Map<String, Receipt> {
        val marks = ThreadItems.receipts(rows, hasReceipts)
        if (marks.isEmpty()) return emptyMap()
        val times = runCatching { com.lightdecoder.reactions.ReadTimes.get(applicationContext) }.getOrNull()
        return marks.mapValues { (id, read) ->
            val at = if (read) runCatching { times?.timeFor(ChatService.IMESSAGE, id)?.atMs }.getOrNull() ?: 0L else 0L
            Receipt(read, at)
        }
    }

    /** Quoted messages: from the window, else from the archives its messages come from ([repos]). Runs on IO. */
    private fun quotesFor(repos: List<ArchiveRepository>, rows: List<MessageRow>): Map<String, Quote> {
        val byId = rows.associateBy { it.id }
        val mediaLabel = getString(R.string.chat_media_picture)
        val quotes = HashMap<String, Quote>()
        for (replyId in rows.mapNotNull { it.replyToId.takeIf(String::isNotEmpty) }.distinct().take(MAX_QUOTE_LOOKUPS)) {
            val quoted = byId[replyId] ?: repos.firstNotNullOfOrNull { runCatching { it.message(replyId) }.getOrNull() } ?: continue
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
        val shownReceipts = receipts
        val isGroup = conversation?.isGroup == true
        val highlighted = highlightId
        lastQuotes = quotes
        val context = applicationContext
        val items = withContext(Dispatchers.Default) {
            ThreadItems.build(
                // An iPhone's reactions arrive as texts ("Loved an image"): shown on their message instead.
                messages = Tapbacks.apply(shown),
                isGroup = isGroup,
                quotes = quotes,
                header = header,
                highlightId = highlighted,
                dayStart = { TimeFormat.startOfDay(it) },
                dayLabel = { TimeFormat.dayLabel(context, it) },
                receipts = shownReceipts,
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
        // The person's (or group's) avatar next to the name, as in the conversation list:
        // only redrawn when it changes, and before the title, which is what the rest follows.
        val avatarKey = Triple(title, conv?.id ?: conversationId, conv?.avatarColor.orEmpty())
        if (avatarKey != shownAvatar) {
            shownAvatar = avatarKey
            val density = resources.displayMetrics.density
            val avatar = Avatars.drawable(this, avatarKey.first, avatarKey.second, avatarKey.third, (TOOLBAR_AVATAR_DP * density).toInt())
            binding.toolbar.logo = android.graphics.drawable.InsetDrawable(avatar, 0, 0, (TOOLBAR_AVATAR_GAP_DP * density).toInt(), 0)
        }
        supportActionBar?.title = title
        renderPeerActivity()
        invalidateOptionsMenu()
        renderComposerState()
    }

    /**
     * Under the title: how many people a group has; for one person, what the
     * service tells about them right now ("online", "typing…", "last seen
     * …") where it does ([Capability.PRESENCE]), else nothing (never their
     * phone number).
     * Who is typing shows above the composer, unless the line under the
     * title already says it.
     */
    private fun renderPeerActivity() {
        val conv = conversation
        val others = participants.filter { !it.isMe }
        val typing = manager.typing.value[conversationId].orEmpty() + iMessageTypists()
        val presence = if (conv?.isGroup == true) null else presenceLine(conv, others, typing)
        supportActionBar?.subtitle = when {
            conv?.isGroup == true -> resources.getQuantityString(R.plurals.chat_people, others.size + 1, others.size + 1)
            presence != null -> presence
            // No phone number under the name: the name alone, as Google Messages shows it.
            else -> null
        }
        renderTyping(if (presence != null) emptySet() else typing)
    }

    /** Who is typing in the person's iMessage chats shown in this Messages conversation (Blue Bubbles). */
    private fun iMessageTypists(): Set<String> {
        if (manager.service != ChatService.MESSAGES || iMessageChats.isEmpty()) return emptySet()
        val all = iMessageLink.typing.value
        return iMessageChats.flatMapTo(LinkedHashSet()) { all[it].orEmpty() }
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
        // As on an iPhone: held down, it offers Apple's effects, in a chat that goes through the iMessage library.
        binding.send.setOnLongClickListener { offerEffects() }
        binding.attach.setOnClickListener { onAttachTapped() }
        binding.stickers.setOnClickListener { showStickers() }
        binding.voiceInput.setOnClickListener { startDictation() }
        binding.voiceMessage.setOnClickListener { onRecordTapped() }
        binding.recordingCancel.setOnClickListener { cancelRecording(quietly = true) }
        binding.recordingSend.setOnClickListener { finishRecording() }
        binding.attachmentRemove.setOnClickListener { clearAttachment() }
        binding.replyClose.setOnClickListener { clearReply() }
        binding.input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                updateSendEnabled()
                if (s.isNullOrEmpty()) typingStopped() else pingTyping()
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
        // While recording, the recording bar stands in for the composer.
        binding.composer.visibility = if (readOnly || recorder != null) View.GONE else View.VISIBLE
        if (readOnly) {
            clearReply()
            clearAttachment()
        }
        binding.input.isEnabled = paired
        binding.attach.isEnabled = paired
        binding.stickers.isEnabled = paired
        binding.voiceInput.isEnabled = paired
        binding.voiceMessage.isEnabled = paired
        updateVoiceMessageButton()
        if (readOnly || !paired) cancelRecording(quietly = true)
        binding.input.hint = getString(
            when {
                !paired -> R.string.chat_compose_unpaired
                companionId != null -> R.string.imessage_compose_hint
                else -> ServiceUi.composeHint(manager.service)
            }
        )
        updateSendEnabled()
    }

    private fun updateSendEnabled() {
        val hasContent = !binding.input.text.isNullOrBlank() || attachments.isNotEmpty()
        binding.send.isEnabled = paired && hasContent
        // The tint has no disabled state: dim it the way Material dims disabled controls.
        binding.send.alpha = if (binding.send.isEnabled) 1f else DISABLED_ALPHA
    }

    // --- voice ------------------------------------------------------------------

    /** The dictation button, only when some app can recognise speech. */
    private fun updateVoiceInput() {
        binding.voiceInput.visibility = if (Dictation.isAvailable(packageManager)) View.VISIBLE else View.GONE
        updateVoiceMessageButton()
    }

    // --- voice messages -------------------------------------------------------

    /**
     * Voice messages: in Google Messages conversations (and so to iMessage chats shown in them) and,
     * through the iMessage library, in iMessage chats; on a phone with a mic.
     */
    private fun updateVoiceMessageButton() {
        val can = (manager.service == ChatService.MESSAGES || (manager.service == ChatService.IMESSAGE && directMode())) &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_MICROPHONE)
        binding.voiceMessage.visibility = if (can) View.VISIBLE else View.GONE
    }

    private fun onRecordTapped() {
        if (!binding.voiceMessage.isEnabled || recorder != null) return
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) startRecording() else askMicrophone.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    /** Where recordings are made; ones more than a day old (sent long ago, or left by a crash) are removed. */
    private fun voiceDir(): File = File(cacheDir, "voice")

    @androidx.annotation.VisibleForTesting
    internal fun startRecording() {
        if (recorder != null || !binding.voiceMessage.isEnabled) return
        val now = System.currentTimeMillis()
        voiceDir().listFiles()?.forEach { if (now - it.lastModified() > HAND_OFF_KEEP_MS) it.delete() }
        val file = File(voiceDir(), "voice-${java.util.UUID.randomUUID()}.${VoiceRecorder.EXTENSION}")
        val r = VoiceRecorder.factory(this)
        // An MMS is small; an RCS chat, and an iMessage through the iMessage library, take much longer
        // messages (without the library it is OpenBubbles' to send, as it always was).
        val asIMessage = directMode() && (manager.service == ChatService.IMESSAGE || companionId != null)
        val maxMs = if (conversation?.isRcs == true || asIMessage) MAX_VOICE_RCS_MS else MAX_VOICE_MMS_MS
        r.onMaxDuration = {
            runOnUiThread {
                if (recorder === r) {
                    finishRecording()
                    toast(getString(R.string.chat_voice_max_reached))
                }
            }
        }
        voicePlayer.pause()
        if (!r.start(file, maxMs)) {
            toast(getString(R.string.chat_voice_mic_busy))
            return
        }
        recorder = r
        recordingFile = file
        recordingStartedMs = android.os.SystemClock.elapsedRealtime()
        binding.composer.visibility = View.GONE
        binding.recordingBar.visibility = View.VISIBLE
        showRecordingTime()
        binding.recordingTime.postDelayed(recordingTick, 500)
    }

    private fun showRecordingTime() {
        val elapsed = (android.os.SystemClock.elapsedRealtime() - recordingStartedMs).toInt()
        binding.recordingTime.text = getString(R.string.chat_voice_recording, VoicePlayer.clock(elapsed))
    }

    /** Stops recording and sends it (to this conversation), unless it is too short to be anything. */
    @androidx.annotation.VisibleForTesting
    internal fun finishRecording() {
        val r = recorder ?: return
        val file = recordingFile
        val elapsed = android.os.SystemClock.elapsedRealtime() - recordingStartedMs
        endRecordingUi()
        if (elapsed < MIN_VOICE_MS) {
            r.cancel()
            toast(getString(R.string.chat_voice_too_short))
            return
        }
        if (!r.stop() || file == null || !file.isFile) {
            toast(getString(R.string.chat_voice_too_short))
            return
        }
        sendVoice(file)
    }

    /** Stops recording and throws it away; [quietly] when the user asked for it (no notice needed). */
    private fun cancelRecording(quietly: Boolean) {
        val r = recorder ?: return
        endRecordingUi()
        r.cancel()
        if (!quietly) toast(getString(R.string.chat_voice_discarded))
    }

    private fun endRecordingUi() {
        recorder = null
        recordingFile = null
        binding.recordingTime.removeCallbacks(recordingTick)
        binding.recordingBar.visibility = View.GONE
        renderComposerState()
    }

    /**
     * Sends a finished recording as a voice message: to someone with an iMessage chat as an
     * iPhone's voice message, through the iMessage library (or, without it, through OpenBubbles),
     * else through Google Messages. Never as a text: a chat the library cannot reach says why.
     */
    private fun sendVoice(file: File) {
        val id = conversationId
        val uri = Uri.fromFile(file)
        val companion = companionId
        val toIMessage = manager is com.lightdecoder.imessage.IMessageManager || (manager.service == ChatService.MESSAGES && companion != null)
        val chat = if (manager.service == ChatService.MESSAGES && companion != null) companion else id
        if (toIMessage && directMode()) {
            val im = iMessageLink
            if (chat !in directReachable) {
                toast(errorText(im, com.lightdecoder.imessage.IMessageErrors.NO_REPLY_BUTTON))
                return
            }
            if (chat != id) logRoute(chat, "voice message, through the iMessage library")
            val replyTo = replyTarget?.takeIf { it.conversationId == chat }?.id
            clearReply()
            scrollToBottomNext = true
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { relayAttempt { im.sendVoice(chat, uri, replyTo) } }
                    .onFailure { toast(errorText(im, it)) }
            }
            return
        }
        if (toIMessage) {
            handOffToOpenBubbles(iMessageLink, chat, "", listOf(uri), clearComposer = false)
            return
        }
        val replyTo = replyTarget?.takeIf { it.conversationId == id }?.id
        clearReply()
        scrollToBottomNext = true
        val link = manager
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { relayAttempt { link.sendMedia(id, uri, null, replyTo) } }
                .onFailure { toast(errorText(link, it)) }
        }
    }

    /** A voice message's file: the one already here, or downloaded now (through its own link). Null (with a notice) if there is none. */
    private suspend fun audioFileFor(row: MessageRow, part: MediaRow): File? {
        val link = backendFor(row)
        listOf(part.localPath).firstOrNull { it.isNotEmpty() && File(it).isFile }?.let { return File(it) }
        toast(getString(R.string.chat_media_downloading))
        return relayAttempt { link.mediaFile(row.id, part.partId, thumbnail = false) }
            .getOrElse {
                val code = ChatErrors.codeOf(it)
                toast(
                    if (code == RelayException.NOT_CONNECTED) getString(ServiceUi.mediaNeedsConnection(link.service, ServiceUi.direct(this@ThreadActivity, link.service)))
                    else errorText(link, it)
                )
                return null
            } ?: run {
            toast(getString(R.string.chat_media_unavailable))
            null
        }
    }

    override fun onAudioTap(row: MessageRow, part: MediaRow) {
        val key = VoicePlayer.keyOf(row.id, part.partId)
        // Already loaded: play/pause at once, with no download.
        if (voicePlayer.state.value.key == key) {
            voicePlayer.toggle(key, File(part.localPath))
            return
        }
        val forConversation = conversationId
        lifecycleScope.launch {
            val file = audioFileFor(row, part) ?: return@launch
            if (conversationId != forConversation) return@launch
            if (voicePlayer.toggle(key, file)) return@launch
            // An iPhone's voice message in a codec this phone cannot play: another app may.
            if (file.extension.equals("caf", ignoreCase = true)) openWithOtherApp(file, com.lightdecoder.media.CafAudio.MIME)
            else toast(getString(R.string.chat_voice_cannot_play))
        }
    }

    override fun onAudioSeek(row: MessageRow, part: MediaRow, positionMs: Int) =
        voicePlayer.seekTo(VoicePlayer.keyOf(row.id, part.partId), positionMs)

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

    /**
     * At most one ping every few seconds; the manager throttles too. The person's iMessage chat shown
     * here hears it as well when the iMessage library reaches it: their iPhone shows the user typing.
     * iMessage hears every keystroke: it throttles them itself, and says the user stopped a few
     * seconds after the last one ([com.lightdecoder.imessage.direct.DirectLink.typing]), which a
     * keystroke dropped here would make too early.
     */
    private fun pingTyping() {
        val id = conversationId
        val link = manager
        val own = paired && online
        val companion = companionId?.takeIf { link.service == ChatService.MESSAGES && it in directReachable }
        if (companion != null) {
            typingIn = companion
            val im = iMessageLink
            runInBackground { im.typing(companion) }
        }
        if (own && link is com.lightdecoder.imessage.IMessageManager) {
            typingIn = id
            runInBackground { link.typing(id) }
            return
        }
        val now = System.currentTimeMillis()
        if (!own || now - lastTypingPingMs < TYPING_PING_MS) return
        lastTypingPingMs = now
        runInBackground { link.typing(id) }
    }

    /** The user emptied the composer (not by sending): the iMessage chat told the user is typing hears it stopped. */
    private fun typingStopped() {
        val chat = typingIn ?: return
        typingIn = null
        lastTypingPingMs = 0L
        val im = iMessageLink
        runInBackground { im.stopTyping(chat) }
    }

    /** The composer emptied by a send: the message itself ends the typing on the other side. */
    private fun clearComposerForSend() {
        typingIn = null
        binding.input.setText("")
    }

    /**
     * Whether what is typed goes to an iMessage chat through the iMessage library, which can send
     * it with one of Apple's effects: this iMessage chat, or the person's iMessage chat shown in this
     * Messages conversation.
     */
    private fun sendsWithEffects(): Boolean {
        if (!directMode()) return false
        return when (manager.service) {
            ChatService.MESSAGES -> companionId?.let { it in directReachable } == true
            ChatService.IMESSAGE -> conversationId in directReachable
            else -> false
        }
    }

    /** The send button held down: Apple's effects to send it with ("Sent with Slam"), as on an iPhone. False when it offers none. */
    private fun offerEffects(): Boolean {
        if (!binding.send.isEnabled || !sendsWithEffects()) return false
        val effects = com.lightdecoder.imessage.direct.IMessageExtra.EFFECTS
        confirmDialog?.dismiss()
        confirmDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.imessage_effect_pick_title)
            .setItems(effects.map { getString(it.second) }.toTypedArray()) { _, which -> send(effect = effects[which].first) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        return true
    }

    /**
     * The attach button: the photo picker; in an iMessage chat the library can reach, with
     * iMessage games on, a choice of that or "Games" (OpenPigeon's picker, or the offer to install it).
     */
    private fun onAttachTapped() {
        val photos = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
        val chat = gamesChat() ?: return photos()
        confirmDialog?.dismiss()
        confirmDialog = MaterialAlertDialogBuilder(this)
            .setItems(arrayOf(getString(R.string.imessage_attach_photos), getString(R.string.imessage_attach_games))) { _, which ->
                if (which == 0) {
                    photos()
                } else {
                    gamesSheet?.dismiss()
                    gamesSheet = com.lightdecoder.imessage.madrid.MadridHost.get(this).openGames(this, chat)
                }
            }
            .show()
    }

    /**
     * The iMessage chat a game would be sent to ("Games" in the attach choices): this iMessage chat,
     * or the person's iMessage chat shown in this Messages conversation, when the library can reach
     * it and iMessage games are on (OpenPigeon installed or not); else null.
     */
    private fun gamesChat(): String? {
        if (!directMode()) return null
        val chat = when (manager.service) {
            ChatService.MESSAGES -> companionId?.takeIf { it in directReachable }
            ChatService.IMESSAGE -> conversationId.takeIf { it in directReachable }
            else -> null
        } ?: return null
        return chat.takeIf { com.lightdecoder.imessage.madrid.MadridHost.get(this).gamesOffered(this) }
    }

    /** Sends what the composer holds; [effect] is Apple's effect to send it with (iMessage through the library only). */
    private fun send(effect: String? = null) {
        val text = binding.input.text?.toString()?.trim().orEmpty()
        val files = attachments
        if (text.isEmpty() && files.isEmpty()) return
        val id = conversationId
        val direct = directMode()
        // Blue Bubbles: to someone with an iMessage chat, everything goes as an iMessage (blue on their
        // iPhone), never as a green text. Through the iMessage library it goes straight to Apple,
        // pictures and files too; a chat it cannot reach keeps it in the composer. Without the
        // library: silently through OpenBubbles' Reply button when it has one, else OpenBubbles opens
        // with the message filled in, for one tap on send.
        val companion = companionId
        if (manager.service == ChatService.MESSAGES && companion != null) {
            // Files go only through the library itself (OpenBubbles' Reply button takes text alone).
            val reachable = if (direct && files.isNotEmpty()) companion in directReachable else sendsAsIMessage(text)
            if (direct && !reachable) {
                logRoute(companion, "NOT sent: the iMessage library cannot reach this chat")
                toast(errorText(iMessageLink, com.lightdecoder.imessage.IMessageErrors.NO_REPLY_BUTTON))
                return
            }
            if (!direct && (files.isNotEmpty() || !reachable)) {
                logRoute(companion, if (files.isNotEmpty()) "${files.size} file(s): through OpenBubbles' screen" else "no usable Reply button: through OpenBubbles' screen")
                handOffToOpenBubbles(iMessageLink, companion, text, files, clearComposer = true)
                return
            }
            logRoute(
                companion,
                (if (files.isNotEmpty()) "${files.size} file(s), " else "") + if (direct) "through the iMessage library" else "silently through OpenBubbles' Reply button",
            )
            val im = iMessageLink
            // An inline reply to one of the iMessages shown here (threads under it through the iMessage library).
            val replyTo = replyTarget?.takeIf { it.conversationId == companion }?.id
            scrollToBottomNext = true
            clearComposerForSend()
            clearReply()
            clearAttachment()
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    relayAttempt {
                        if (files.isEmpty()) im.sendText(companion, text, replyTo, effect) else sendFiles(im, companion, files, text, replyTo, effect)
                    }
                }.onFailure { toast(errorText(im, it)) }
            }
            return
        }
        val iMessage = manager as? com.lightdecoder.imessage.IMessageManager
        // An iMessage to a chat with no Reply button to send through, or (without the iMessage
        // library) with a picture: OpenBubbles sends it. Through the library nothing is handed over:
        // a chat it cannot reach (files: the library itself, not a Reply button) says why, and the
        // composer keeps it.
        if (iMessage != null && direct && (if (files.isNotEmpty()) id !in directReachable else !iMessage.canReply(id, text))) {
            toast(errorText(iMessage, com.lightdecoder.imessage.IMessageErrors.NO_REPLY_BUTTON))
            return
        }
        if (iMessage != null && !direct && (files.isNotEmpty() || !iMessage.canReply(id, text))) {
            handOffToOpenBubbles(iMessage, id, text, files, clearComposer = true)
            return
        }
        if (manager.service == ChatService.MESSAGES && com.lightdecoder.imessage.IMessagePrefs.enabled(this) && conversation?.isGroup == false) {
            // Blue Bubbles is on but this goes green: say why, so "it went green" can be traced.
            com.lightdecoder.imessage.IMessageLog.add(
                this,
                "Messages conversation sent as a TEXT (green): no iMessage chat with this person " + (if (direct) "yet" else "in OpenBubbles yet") +
                    if (otherAddress() == null) " (no phone number or email to match one by)" else " (use Send iMessage to start one)",
            )
        }
        // Only ever a message of this conversation (onReply checks too).
        val replyTo = replyTarget?.takeIf { it.conversationId == id }?.id
        scrollToBottomNext = true
        clearComposerForSend()
        clearReply()
        clearAttachment()
        val link = manager
        lifecycleScope.launch {
            // The outbox writes to the archive (and copies an attachment), so not on the main thread.
            val result = withContext(Dispatchers.IO) {
                relayAttempt {
                    when {
                        files.isEmpty() && link is com.lightdecoder.imessage.IMessageManager -> link.sendText(id, text, replyTo, effect)
                        files.isEmpty() -> link.sendText(id, text, replyTo)
                        link is com.lightdecoder.imessage.IMessageManager -> sendFiles(link, id, files, text, replyTo, effect)
                        else -> {
                            // Each picture as a message of its own, in the order picked (queued in turn, they go
                            // out in it): the phone keeps only one part of a message with several. The text goes
                            // with the last, the reply with the first.
                            files.forEachIndexed { i, file ->
                                link.sendMedia(
                                    id, file,
                                    text.ifEmpty { null }.takeIf { i == files.lastIndex },
                                    replyTo.takeIf { i == 0 },
                                )
                            }
                        }
                    }
                }
            }
            result.onFailure { toast(errorText(link, it)) }
            // Someone the user writes to from here: offered in Android's share sheet from now on.
            // Not iMessage: the share screen cannot send through OpenBubbles, and its chat ids would be on show.
            val row = conversation?.takeIf { it.id == id && result.isSuccess && link.service != ChatService.IMESSAGE }
            val title = supportActionBar?.title?.toString().orEmpty()
            if (row != null && title != getString(R.string.chat_untitled)) withContext(Dispatchers.IO) {
                com.lightdecoder.chat.ShareShortcuts.push(applicationContext, link.service, id, title, row.avatarColor)
            }
        }
    }

    /**
     * Pictures and files to an iMessage chat through the iMessage library: each an iMessage of its
     * own, in the order picked, the text with the first (above it, as Apple numbers the parts), the
     * reply and the [effect] too. Runs on IO; stops at the first that cannot go.
     */
    private fun sendFiles(im: com.lightdecoder.imessage.IMessageManager, chat: String, files: List<Uri>, text: String, replyTo: String?, effect: String?) {
        files.forEachIndexed { i, file ->
            im.sendMedia(chat, file, text.ifEmpty { null }.takeIf { i == 0 }, replyTo.takeIf { i == 0 }, effect.takeIf { i == 0 })
        }
    }

    /** One line in the iMessage log: how a message from a Messages conversation is being sent. */
    private fun logRoute(companion: String, how: String) =
        com.lightdecoder.imessage.IMessageLog.add(
            this, "Messages conversation with ${com.lightdecoder.imessage.IMessageLog.chatRef(companion)}: sent as iMessage, $how",
        )

    /**
     * Opens OpenBubbles on this chat with [text] and [files] filled in
     * (copies of the files this app can hand over), for the user to tap send
     * there. With [clearComposer] (a send from the composer) the composer is
     * emptied once OpenBubbles has it; a sticker leaves it as it is.
     */
    private fun handOffToOpenBubbles(
        link: com.lightdecoder.imessage.IMessageManager,
        id: String,
        text: String,
        files: List<Uri>,
        clearComposer: Boolean,
    ) {
        // Through the iMessage library nothing is ever handed to the OpenBubbles app (callers do not get here).
        if (directMode()) {
            toast(errorText(link, com.lightdecoder.imessage.IMessageErrors.HAND_OFF))
            return
        }
        lifecycleScope.launch {
            val copies = withContext(Dispatchers.IO) {
                purgeHandOffCopies()
                files.map { shareableCopy(it) }
            }
            val shared = copies.filterNotNull()
            fun dropCopies() = runInBackground { shared.forEach { (uri, _) -> handOffFileOf(uri)?.delete() } }
            // The chat on screen, or the person's iMessage chat shown inside it (Blue Bubbles).
            if (!isCurrent(link, id) && id != companionId) {
                dropCopies()
                return@launch
            }
            if (shared.size < files.size) {
                dropCopies()
                toast(getString(R.string.share_could_not_read))
                return@launch
            }
            if (!link.handOff(this@ThreadActivity, id, text, shared)) {
                dropCopies()
                toast(errorText(link, com.lightdecoder.imessage.IMessageErrors.NOT_INSTALLED))
                return@launch
            }
            if (clearComposer) {
                binding.input.setText("")
                clearReply()
                clearAttachment()
            }
            toast(getString(R.string.imessage_finish_in_openbubbles))
        }
    }

    /** Where copies handed to OpenBubbles are kept: under the cache folder this app shares files from. */
    private fun handOffDir(): File = File(File(cacheDir, "shared"), "imessage")

    private fun handOffFileOf(uri: Uri): File? =
        uri.lastPathSegment?.substringAfterLast('/')?.let { File(handOffDir(), it) }?.takeIf { it.parentFile == handOffDir() }

    /** Copies handed over more than a day ago: OpenBubbles has long since sent them, or never will. */
    private fun purgeHandOffCopies() {
        val now = System.currentTimeMillis()
        handOffDir().listFiles()?.forEach { if (now - it.lastModified() > HAND_OFF_KEEP_MS) it.delete() }
    }

    /**
     * A failed iMessage with a file, sent through the iMessage library before it was turned off:
     * OpenBubbles opens with it (its copy kept here, and the caption) for the user to tap send, and
     * the failed one goes. Never the caption alone, as if the file had gone too.
     */
    private fun handOffFailedFile(link: com.lightdecoder.imessage.IMessageManager, row: MessageRow, part: MediaRow) {
        val tmpId = row.tmpId
        lifecycleScope.launch {
            val copy = withContext(Dispatchers.IO) {
                purgeHandOffCopies()
                File(part.localPath).takeIf { part.localPath.isNotEmpty() && it.isFile }?.let { shareableCopy(Uri.fromFile(it), part.mime) }
            }
            if (copy == null) {
                toast(getString(R.string.chat_media_unavailable))
                return@launch
            }
            if (!link.handOff(this@ThreadActivity, row.conversationId, row.text, listOf(copy))) {
                runInBackground { handOffFileOf(copy.first)?.delete() }
                toast(errorText(link, com.lightdecoder.imessage.IMessageErrors.NOT_INSTALLED))
                return@launch
            }
            runInBackground { link.discardFailed(tmpId) }
            toast(getString(R.string.imessage_finish_in_openbubbles))
        }
    }

    /**
     * A copy of [source] in [handOffDir], as a uri of this app's own provider
     * (another app may read it while it is granted), with its type ([type]
     * when it is known). Null if it cannot be read. Not on the main thread.
     */
    private fun shareableCopy(source: Uri, type: String? = null): Pair<Uri, String>? {
        val dir = handOffDir().apply { mkdirs() }
        var target: File? = null
        return try {
            val mime = type?.takeIf { it.isNotBlank() }
                ?: contentResolver.getType(source)
                ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(source.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
            val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { ".$it" }.orEmpty()
            val file = File(dir, java.util.UUID.randomUUID().toString() + extension).also { target = it }
            val read = (if (source.scheme == "file") java.io.FileInputStream(source.path!!) else contentResolver.openInputStream(source))?.use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            if (read == null || file.length() == 0L) {
                file.delete()
                null
            } else {
                androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file) to mime
            }
        } catch (e: Exception) {
            target?.delete()
            null
        }
    }

    // --- stickers ------------------------------------------------------------

    private var stickerSheet: BottomSheetDialog? = null

    /** Pictures picked to add to the sticker library (the gallery copies, after a reinstall). */
    private val restoreStickers = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_STICKER_IMPORT)) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) { com.lightdecoder.sticker.StickerLibrary.import(applicationContext, uris) }
            toast(if (added > 0) getString(R.string.sticker_restored, added) else getString(R.string.sticker_restore_none))
            if (added > 0) showStickers()
        }
    }

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
        // "Edit stickers": while on, a tapped sticker opens in the editor instead of being sent.
        val title = view.findViewById<TextView>(R.id.stickersTitle)
        val editMode = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.stickersEditMode)
        var editing = false
        // "Send several": while on, tapped stickers are marked, and sent together in the order tapped.
        val several = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.stickersSeveral)
        val sendChosen = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.stickersSendChosen)
        var choosing = false
        val chosen = ArrayList<File>()
        fun renderEditMode() {
            title.setText(
                when {
                    editing -> R.string.sticker_edit_mode_title
                    choosing -> R.string.sticker_send_several_title
                    else -> R.string.sticker_sheet_title
                }
            )
            editMode.setText(if (editing) R.string.sticker_edit_mode_done else R.string.sticker_edit_mode)
            several.setText(if (choosing) R.string.sticker_send_several_cancel else R.string.sticker_send_several)
            sendChosen.visibility = if (choosing) View.VISIBLE else View.GONE
            sendChosen.isEnabled = chosen.isNotEmpty()
            sendChosen.text = resources.getQuantityString(R.plurals.sticker_send_count, maxOf(1, chosen.size), maxOf(1, chosen.size))
            grid.adapter?.notifyDataSetChanged()
        }
        editMode.setOnClickListener {
            editing = !editing
            choosing = false
            chosen.clear()
            renderEditMode()
        }
        several.setOnClickListener {
            choosing = !choosing
            editing = false
            chosen.clear()
            renderEditMode()
        }
        sendChosen.setOnClickListener {
            val files = chosen.toList()
            sheet.dismiss()
            sendStickers(files)
        }
        fun edit(file: File) {
            sheet.dismiss()
            makeSticker.launch(com.lightdecoder.ui.sticker.StickerMakerActivity.editIntent(this, file))
        }
        view.findViewById<View>(R.id.stickersNew).setOnClickListener {
            sheet.dismiss()
            makeSticker.launch(com.lightdecoder.ui.sticker.StickerMakerActivity.intent(this))
        }
        view.findViewById<View>(R.id.stickersRestore).setOnClickListener {
            sheet.dismiss()
            restoreStickers.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        sheet.setContentView(view)
        sheet.setOnDismissListener { if (stickerSheet === sheet) stickerSheet = null }
        stickerSheet = sheet
        sheet.show()
        val side = (STICKER_TILE_DP * resources.displayMetrics.density).toInt()
        fun refresh() {
            lifecycleScope.launch {
                val tiles = withContext(Dispatchers.IO) {
                    // Stickers made before the gallery copies existed get theirs now.
                    runCatching { com.lightdecoder.sticker.StickerLibrary.copyMissingToGallery(applicationContext) }
                    com.lightdecoder.sticker.StickerLibrary.list(applicationContext).mapNotNull { file ->
                        runCatching { MediaLoader.decodePreview(file.readBytes(), side) }.getOrNull()?.let { file to it }
                    }
                }
                empty.visibility = if (tiles.isEmpty()) View.VISIBLE else View.GONE
                editMode.visibility = if (tiles.isEmpty()) View.GONE else View.VISIBLE
                several.visibility = if (tiles.size < 2) View.GONE else View.VISIBLE
                chosen.retainAll(tiles.map { it.first }.toSet())
                view.findViewById<View>(R.id.stickersHint).visibility = if (tiles.isEmpty()) View.GONE else View.VISIBLE
                grid.adapter = StickerTiles(
                    tiles,
                    isChosen = { it in chosen },
                    onTap = { file ->
                        if (editing) {
                            edit(file)
                        } else if (choosing) {
                            if (!chosen.remove(file)) {
                                if (chosen.size < MAX_STICKERS_AT_ONCE) chosen += file
                                else toast(getString(R.string.sticker_send_limit, MAX_STICKERS_AT_ONCE))
                            }
                            renderEditMode()
                        } else {
                            sheet.dismiss()
                            sendSticker(file)
                        }
                    },
                    onHold = { file ->
                        // Edit it (all the maker's tools, or back to its original picture) or delete it.
                        MaterialAlertDialogBuilder(this@ThreadActivity)
                            .setItems(arrayOf(getString(R.string.sticker_edit), getString(R.string.sticker_delete))) { _, which ->
                                if (which == 0) {
                                    edit(file)
                                } else {
                                    confirmStickerDelete(file) { refresh() }
                                }
                            }
                            .show()
                    },
                )
            }
        }
        refresh()
    }

    /** Downloads the picture if need be (through its own link) and adds it to the user's stickers. */
    private fun addToStickers(row: MessageRow, part: MediaRow) {
        val link = backendFor(row)
        lifecycleScope.launch {
            val local = listOf(part.localPath, if (part.needsFullSize) part.thumbPath else "")
                .firstOrNull { it.isNotEmpty() && File(it).isFile }?.let(::File)
            val file = local ?: relayAttempt { link.mediaFile(row.id, part.partId, thumbnail = part.needsFullSize) }.getOrElse {
                val code = ChatErrors.codeOf(it)
                toast(
                    if (code == RelayException.NOT_CONNECTED) getString(ServiceUi.mediaNeedsConnection(link.service, ServiceUi.direct(this@ThreadActivity, link.service)))
                    else errorText(link, code)
                )
                return@launch
            }
            val added = file?.let { f ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        if (f.length() > MAX_STICKER_SOURCE_BYTES) null
                        else com.lightdecoder.sticker.StickerLibrary.addPicture(applicationContext, f.readBytes())
                    }.getOrNull()
                }
            }
            toast(getString(if (added != null) R.string.sticker_added_to_mine else R.string.sticker_add_failed))
        }
    }

    private fun confirmStickerDelete(file: File, after: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sticker_delete_title)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.sticker_delete_confirm) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        com.lightdecoder.sticker.StickerLibrary.delete(applicationContext, file)
                    }
                    after()
                }
            }
            .show()
    }

    private fun sendSticker(file: File) = sendStickers(listOf(file))

    /**
     * Sends [files] to this conversation one after the other, in order; stops at the first that
     * fails. To someone with an iMessage chat they go as iMessages (pictures), never as texts.
     */
    @androidx.annotation.VisibleForTesting
    internal fun sendStickers(files: List<File>) {
        if (files.isEmpty()) return
        val companion = companionId
        val toIMessage = manager is com.lightdecoder.imessage.IMessageManager || (manager.service == ChatService.MESSAGES && companion != null)
        val link = if (toIMessage) iMessageLink else manager
        val id = if (manager.service == ChatService.MESSAGES && companion != null) companion else conversationId
        if (toIMessage && directMode()) {
            // Straight to Apple; a chat the iMessage library cannot reach says why.
            if (id !in directReachable) {
                toast(errorText(link, com.lightdecoder.imessage.IMessageErrors.NO_REPLY_BUTTON))
                return
            }
            if (id != conversationId) com.lightdecoder.imessage.IMessageLog.add(this, "Messages conversation with ${com.lightdecoder.imessage.IMessageLog.chatRef(id)}: sticker sent as iMessage, through the iMessage library")
        } else if (toIMessage) {
            // OpenBubbles sends them, from its own screen.
            if (id != conversationId) com.lightdecoder.imessage.IMessageLog.add(this, "Messages conversation with ${com.lightdecoder.imessage.IMessageLog.chatRef(id)}: sticker sent as iMessage, through OpenBubbles' screen")
            handOffToOpenBubbles(iMessageLink, id, "", files.map { Uri.fromFile(it) }, clearComposer = false)
            return
        }
        scrollToBottomNext = true
        lifecycleScope.launch {
            for (file in files) {
                val sent = withContext(Dispatchers.IO) { relayAttempt { link.sendMedia(id, Uri.fromFile(file), null, null) } }
                if (sent.isFailure) {
                    sent.onFailure { toast(errorText(link, it)) }
                    break
                }
                scrollToBottomNext = true
            }
        }
    }

    private class StickerTiles(
        private val tiles: List<Pair<File, Bitmap>>,
        private val isChosen: (File) -> Boolean,
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
            // Marked for "Send several".
            val chosen = isChosen(file)
            holder.image.isSelected = chosen
            if (chosen) {
                holder.image.setBackgroundResource(R.drawable.bg_sticker_selected)
            } else {
                val plain = android.util.TypedValue()
                holder.image.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, plain, true)
                holder.image.setBackgroundResource(plain.resourceId)
            }
            holder.image.setOnClickListener { onTap(file) }
            holder.image.setOnLongClickListener {
                onHold(file)
                true
            }
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun setAttachment(uri: Uri) = setAttachments(listOf(uri))

    /** The composer's attachments: the first one's picture, and its name or how many there are. */
    @androidx.annotation.VisibleForTesting
    internal fun setAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return clearAttachment()
        attachments = uris
        binding.attachmentBar.visibility = View.VISIBLE
        val several = uris.size > 1
        binding.attachmentName.text =
            if (several) resources.getQuantityString(R.plurals.chat_attachments, uris.size, uris.size)
            else getString(R.string.chat_attachment_ready)
        binding.attachmentThumb.setImageResource(R.drawable.ic_image_placeholder)
        updateSendEnabled()
        val side = (ATTACHMENT_THUMB_DP * resources.displayMetrics.density).toInt()
        val first = uris.first()
        lifecycleScope.launch {
            val (name, preview) = withContext(Dispatchers.IO) { describeAttachment(first, side) }
            if (attachments != uris) return@launch
            if (!several) name?.let { binding.attachmentName.text = it }
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
        attachments = emptyList()
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
        manager.service != ChatService.GMAIL &&
            !row.isLocal && row.direction != "system" && conversation?.readOnly != true && conversation?.folderEnum != Folder.DELETED

    // --- message callbacks ---------------------------------------------------

    /** Whether [row] belongs to the conversation on screen (a sheet may outlive a thread switch). */
    private fun isShown(row: MessageRow) =
        row.conversationId == conversationId || row.conversationId in iMessageChats

    override fun onReply(row: MessageRow) {
        if (!isShown(row) || !canReplyTo(row)) return
        replyTarget = row
        binding.replyBar.visibility = View.VISIBLE
        val who = if (row.isOutgoing) getString(R.string.chat_you)
        else row.senderName.ifBlank { getString(R.string.chat_unknown_sender) }
        // Through OpenBubbles' notification an iMessage goes as a message of its own: say so, rather than promise a thread.
        val asIMessage = (backendFor(row).service == ChatService.IMESSAGE || manager.service == ChatService.IMESSAGE ||
            (manager.service == ChatService.MESSAGES && companionId != null)) &&
            // Through the iMessage library a reply to an iMessage threads under it, as on an iPhone.
            !com.lightdecoder.imessage.direct.DirectLink.get(this).repliesInline(row)
        binding.replySender.text = getString(if (asIMessage) R.string.imessage_replying_to else R.string.chat_replying_to, who)
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
        val link = backendFor(row)
        val isIMessage = link.service == ChatService.IMESSAGE
        // Through the iMessage library, tapbacks, edits and unsends go to Apple: only for messages
        // it knows by their GUID (not ones taken from OpenBubbles' notifications), in chats it can reach.
        val direct = com.lightdecoder.imessage.direct.DirectLink.get(this)
        val directIMessage = isIMessage && direct.active
        // Whether the library reaches the row's chat, as the last load found (not read on this thread).
        val reachable = row.conversationId in directReachable

        // Email has no reactions.
        val reacts = real && manager.service != ChatService.GMAIL && (!directIMessage || direct.canReact(row, reachable))
        b.reactionRow.visibility = if (reacts) View.VISIBLE else View.GONE
        if (reacts) {
            // An iMessage through the library: Apple's six tapbacks (and the user's own if it is another
            // emoji). Otherwise five quick ones (the user's own in the last place if it is another
            // emoji). Then "+" for any emoji.
            val quick = if (directIMessage) tapbacks(mine) else quickReactions(mine)
            for (emoji in quick) {
                val view = layoutInflater.inflate(R.layout.item_quick_reaction, b.reactionRow, false) as TextView
                view.text = emoji
                view.isSelected = emoji == mine
                view.contentDescription = getString(R.string.chat_react_with, emoji)
                view.setOnClickListener {
                    sheet.dismiss()
                    reactionPicked(row, emoji, mine, isIMessage)
                }
                b.reactionRow.addView(view)
            }
            val more = layoutInflater.inflate(R.layout.item_quick_reaction_more, b.reactionRow, false)
            more.findViewById<View>(R.id.reactionMore).setOnClickListener {
                sheet.dismiss()
                showEmojiPicker(row, mine, isIMessage)
            }
            b.reactionRow.addView(more)
        }
        b.actionRemoveReaction.visibility = if (reacts && mine != null) View.VISIBLE else View.GONE
        b.actionRemoveReaction.setOnClickListener {
            sheet.dismiss()
            if (mine != null && isShown(row)) react(row, mine, remove = true)
        }
        // One of the user's messages that was read: when, as far as the link saw it (a companion
        // iMessage's as the iMessage library saw Apple's receipt).
        if (MessageStatus.tickFor(row) == Tick.READ && manager.service != ChatService.GMAIL) {
            val service = link.service
            lifecycleScope.launch {
                val time = withContext(Dispatchers.IO) {
                    runCatching { com.lightdecoder.reactions.ReadTimes.get(this@ThreadActivity).timeFor(service, row.id) }.getOrNull()
                }
                b.readInfo.text = ReadInfo.text(this@ThreadActivity, time)
                b.readInfo.visibility = View.VISIBLE
            }
        }
        b.actionReply.visibility = if (canReplyTo(row)) View.VISIBLE else View.GONE
        b.actionReply.setOnClickListener {
            sheet.dismiss()
            onReply(row)
        }
        // A picture or sticker in the message: kept among the user's own stickers.
        val picture = row.media.firstOrNull { PictureGalleryActivity.isPicture(it) }
        b.actionSaveSticker.visibility = if (picture != null) View.VISIBLE else View.GONE
        b.actionSaveSticker.setOnClickListener {
            sheet.dismiss()
            if (picture != null) addToStickers(row, picture)
        }
        b.actionCopy.visibility = if (row.text.isNotEmpty()) View.VISIBLE else View.GONE
        b.actionCopy.setOnClickListener {
            sheet.dismiss()
            copy(row.text)
        }
        // Without the iMessage library, one's own iMessage is edited and unsent in OpenBubbles
        // (Apple allows 15 minutes and 2), whose notifications cannot do either.
        val inOpenBubbles = isIMessage && !direct.active && (row.isOutgoing || row.direction == "out") &&
            row.localState != LocalState.FAILED && !row.isLocal
        val editInOpenBubbles = inOpenBubbles && row.text.isNotBlank() && System.currentTimeMillis() - row.timestampMs < EditRule.WINDOW_MS
        val editHere = if (directIMessage) direct.canEdit(row, reachable = reachable) else manager.isPaired() && EditRule.canEdit(row, link.capabilities)
        b.actionEdit.visibility = if (editHere || editInOpenBubbles) View.VISIBLE else View.GONE
        if (editInOpenBubbles) b.actionEdit.setText(R.string.imessage_edit)
        b.actionUnsend.visibility = if ((directIMessage && direct.canUnsend(row, reachable = reachable)) || inOpenBubbles) View.VISIBLE else View.GONE
        if (inOpenBubbles) b.actionUnsend.setText(R.string.imessage_unsend)
        b.actionUnsend.setOnClickListener {
            sheet.dismiss()
            if (isShown(row)) unsendIMessage(row)
        }
        b.actionEdit.setOnClickListener {
            sheet.dismiss()
            if (!isShown(row)) return@setOnClickListener
            if (editInOpenBubbles) openInOpenBubblesFor(row, getString(R.string.imessage_edit_how), "edit") else askEdit(row)
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
            // An iMessage that could not go through OpenBubbles' Reply button goes the way a tap on it does.
            if (backendFor(row) is com.lightdecoder.imessage.IMessageManager) onFailedTap(row) else if (isShown(row)) retryOrAsk(row)
        }
        val discardable = row.isLocal && row.localState == LocalState.FAILED
        b.actionDiscard.visibility = if (discardable) View.VISIBLE else View.GONE
        b.actionDiscard.setOnClickListener {
            sheet.dismiss()
            if (!isShown(row)) return@setOnClickListener
            val tmpId = row.tmpId
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

    /** The quick reactions: the five usual ones, with [mine] in the last place when it is another emoji. */
    private fun quickReactions(mine: String?): List<String> =
        if (mine == null || mine in QUICK_REACTIONS) QUICK_REACTIONS else QUICK_REACTIONS.take(QUICK_REACTIONS.size - 1) + mine

    /** An iMessage's quick reactions: Apple's six tapbacks, then [mine] when it is another emoji (iOS 18). */
    private fun tapbacks(mine: String?): List<String> = if (mine == null || mine in TAPBACKS) TAPBACKS else TAPBACKS + mine

    /**
     * [emoji] chosen for [row]: the same as [mine] takes it back. An iMessage's tapback goes to
     * Apple through the iMessage library; without it, it is put on in OpenBubbles.
     */
    @androidx.annotation.VisibleForTesting
    internal fun reactionPicked(row: MessageRow, emoji: String, mine: String?, isIMessage: Boolean) {
        if (!isShown(row)) return
        if (isIMessage && !com.lightdecoder.imessage.direct.DirectLink.get(this).active) {
            openInOpenBubblesFor(row, getString(R.string.imessage_react_how, emoji), "react")
        } else {
            react(row, emoji, remove = emoji == mine)
        }
    }

    /** Every emoji, by category, recently used first (Android's own picker); one tap reacts with it. */
    private fun showEmojiPicker(row: MessageRow, mine: String?, isIMessage: Boolean) {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_emoji_picker, binding.root as android.view.ViewGroup, false)
        view.findViewById<androidx.emoji2.emojipicker.EmojiPickerView>(R.id.emojiPicker).setOnEmojiPickedListener { item ->
            sheet.dismiss()
            reactionPicked(row, item.emoji, mine, isIMessage)
        }
        sheet.setContentView(view)
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
    }

    override fun onFailedTap(row: MessageRow) {
        // An iMessage that had no Reply button to go through: OpenBubbles sends it instead.
        val link = backendFor(row)
        if (link is com.lightdecoder.imessage.IMessageManager && row.isLocal) {
            if (!isShown(row)) return
            // Through the iMessage library: sent again from here (asked first when it may have gone
            // already, as after an interruption), never handed to OpenBubbles.
            if (directMode()) {
                if (row.conversationId in directReachable) retryOrAsk(row)
                else toast(errorText(link, com.lightdecoder.imessage.IMessageErrors.NO_REPLY_BUTTON))
                return
            }
            // A file (from while the library was on) goes through OpenBubbles' screen, never as its caption alone.
            row.media.firstOrNull()?.let { part ->
                handOffFailedFile(link, row, part)
                return
            }
            if (link.canReply(row.conversationId, row.text)) {
                retry(row)
            } else if (link.handOff(this, row.conversationId, row.text)) {
                val tmpId = row.tmpId
                runInBackground { link.discardFailed(tmpId) }
                toast(getString(R.string.imessage_finish_in_openbubbles))
            } else {
                toast(errorText(link, com.lightdecoder.imessage.IMessageErrors.NOT_INSTALLED))
            }
            return
        }
        if (MessageStatus.canRetryLocally(row)) retryOrAsk(row) else toast(getString(ServiceUi.failedOnPhone(link.service, ServiceUi.direct(this, link.service))))
    }

    override fun onReactionTap(row: MessageRow, reaction: ReactionDto) {
        if (row.isLocal) return
        react(row, reaction.emoji, remove = reaction.fromMe)
    }

    override fun onReactionDetails(row: MessageRow, reaction: ReactionDto) {
        // A companion iMessage's reactions are iMessage's, by the iMessage chat's members.
        val link = backendFor(row)
        val service = link.service
        val own = participants.takeIf { link === manager }
        lifecycleScope.launch {
            val (people, stored) = withContext(Dispatchers.IO) {
                val members = own ?: runCatching { link.repo.participants(row.conversationId) }.getOrDefault(emptyList())
                members to runCatching { com.lightdecoder.reactions.ReactionTimes.get(this@ThreadActivity).timesFor(service, row.id) }
                    .getOrDefault(emptyMap())
            }
            if (isFinishing || isDestroyed) return@launch
            ReactionDetails.show(this@ThreadActivity, row, reaction.emoji, people, stored)
        }
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
        // Only a service whose phone keeps originals back can be asked for them (never iMessage's rows shown here).
        if (backendFor(row) !== manager || Capability.FULL_SIZE_REQUEST !in manager.capabilities) return
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

    private val askMediaAccess = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (com.lightdecoder.imessage.IMessageMedia.hasPermission(this)) {
            com.lightdecoder.imessage.IMessageLog.add(this, "Pictures: photo and video access allowed")
            com.lightdecoder.imessage.IMessageMedia.schedule(this)
            explainIMessagePictures(null)
        } else {
            com.lightdecoder.imessage.IMessageLog.add(this, "Pictures: photo and video access not allowed (or only some photos)")
            toast(getString(R.string.imessage_media_partial))
        }
    }

    /**
     * An iMessage picture still waiting for OpenBubbles' saved copy: what it needs, and a way to it.
     * Through the iMessage library only that there is no copy here: nothing names or opens
     * OpenBubbles, and no photo access is asked for it.
     */
    private fun explainIMessagePictures(row: MessageRow?) {
        if (directMode()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.imessage_media_title)
                .setMessage(R.string.imessage_media_not_here_direct)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        if (!com.lightdecoder.imessage.IMessageMedia.hasPermission(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.imessage_media_title)
                .setMessage(getString(R.string.imessage_media_allow) + "\n\n" + getString(R.string.imessage_media_how))
                .setPositiveButton(R.string.imessage_media_allow_button) { _, _ -> askMediaAccess.launch(com.lightdecoder.imessage.IMessageMedia.permissions()) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        com.lightdecoder.imessage.IMessageMedia.schedule(this)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.imessage_media_title)
            .setMessage(R.string.imessage_media_how)
            .setPositiveButton(android.R.string.ok, null)
        // Only with the app there to open.
        if (com.lightdecoder.imessage.OpenBubbles.isInstalled(this)) {
            dialog.setNeutralButton(R.string.imessage_media_open) { _, _ ->
                val chat = row?.conversationId?.takeIf { row.let(::backendFor).service == ChatService.IMESSAGE } ?: companionId
                val opened = (chat != null && com.lightdecoder.imessage.IMessageReplies.open(this, chat)) ||
                    (chat != null && iMessageLink.handOff(this, chat, "")) || com.lightdecoder.imessage.OpenBubbles.launch(this)
                if (!opened) toast(errorText(iMessageLink, com.lightdecoder.imessage.IMessageErrors.NOT_INSTALLED))
            }
        }
        dialog.show()
    }

    override fun onMediaTap(row: MessageRow, part: MediaRow) {
        // An iMessage picture waiting for OpenBubbles' saved copy.
        if ((part.mime == com.lightdecoder.imessage.IMessageCapture.IMAGE || part.mime == com.lightdecoder.imessage.IMessageCapture.VIDEO) &&
            part.localPath.isEmpty()
        ) {
            explainIMessagePictures(row)
            return
        }
        // An iMessage shown in this Messages conversation comes through iMessage, from its own chat.
        val link = backendFor(row)
        if (PictureGalleryActivity.isPicture(part)) {
            // Pictures open full screen, zoomable, with the conversation's other pictures a swipe away.
            val chat = if (link === manager) conversationId else row.conversationId
            startActivity(PictureGalleryActivity.intent(this, link.service, chat, row.id, part.partId, title))
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
                            if (code == RelayException.NOT_CONNECTED) getString(ServiceUi.mediaNeedsConnection(link.service, ServiceUi.direct(this@ThreadActivity, link.service)))
                            else errorText(link, it)
                        )
                        return@launch
                    }
            }
            if (file == null) {
                toast(getString(R.string.chat_media_unavailable))
                return@launch
            }
            val mime = part.mime
            // Videos play here, as they came (an iPhone's .mov included).
            if (mime.startsWith("video/")) {
                startActivity(VideoPlayerActivity.intent(this@ThreadActivity, file, title?.toString()))
                return@launch
            }
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

    /** An iMessage's link preview tapped: the page opens in the browser. */
    override fun onOpenLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.chat_no_app))
        }
    }

    /**
     * An iMessage app's card tapped (a game, a poll): an installed extension app that plays it here
     * takes it; else one that keeps its state in a web address opens in the browser; else it opens
     * only on an Apple device, which a toast says ([com.lightdecoder.imessage.direct.IMessageApps]).
     */
    override fun onAppCardTap(row: MessageRow) {
        val app = com.lightdecoder.imessage.direct.IMessageExtra.parse(row.extra).app ?: return
        val apps = com.lightdecoder.imessage.direct.IMessageApps
        when (val tap = apps.tap(app) { apps.host?.engage(this, row, it) == true }) {
            com.lightdecoder.imessage.direct.IMessageApps.Tap.Engaged -> Unit
            is com.lightdecoder.imessage.direct.IMessageApps.Tap.Open -> onOpenLink(tap.url)
            is com.lightdecoder.imessage.direct.IMessageApps.Tap.OnlyOnApple ->
                toast(getString(R.string.imessage_app_only_on_apple, tap.name.ifBlank { getString(R.string.imessage_app_unnamed) }))
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
        val link = backendFor(row)
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
        val link = backendFor(row)
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
        val link = backendFor(row)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_resend_title)
            .setMessage(ServiceUi.resendBody(link.service, ServiceUi.direct(this, link.service)))
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
        val link = backendFor(row)
        val linked = link.isPaired()
        val id = row.id
        fun delete(forEveryone: Boolean) {
            // The thread switched under the question (onNewIntent closes it; this is the backstop).
            if (!isShown(row) || link !== backendFor(row)) return
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
            builder.setMessage(
                when {
                    // An iMessage goes from this device only: OpenBubbles (or, through the library, their phone) keeps it.
                    link.service == ChatService.IMESSAGE ->
                        if (ServiceUi.direct(this, link.service)) R.string.imessage_delete_message_question_direct else R.string.imessage_delete_message_question
                    linked -> R.string.chat_delete_message_question
                    else -> R.string.chat_delete_message_question_local
                }
            )
                .setPositiveButton(R.string.chat_action_delete) { _, _ -> delete(forEveryone = false) }
        }
        builder.setNegativeButton(android.R.string.cancel, null)
            .show()
            .let(::trackConfirm)
    }

    /**
     * Asks for the new text of one of the user's own messages and sends the change ([Capability.EDIT]),
     * through the service the message is from (a companion iMessage's is iMessage).
     */
    private fun askEdit(row: MessageRow) {
        if (!isShown(row)) return
        val link = backendFor(row)
        val field = com.google.android.material.textfield.TextInputEditText(this).apply {
            setText(row.text)
            setSelection(row.text.length)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 8
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(field)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_edit_title)
            .setMessage(if (link.service == ChatService.IMESSAGE) R.string.imessage_edit_note else R.string.chat_edit_note)
            .setView(box)
            .setPositiveButton(R.string.chat_edit_save) { _, _ ->
                val text = field.text?.toString()?.trim().orEmpty()
                // The thread switched under the question (onNewIntent closes it; this is the backstop).
                if (text.isEmpty() || text == row.text || !isShown(row) || link !== backendFor(row)) return@setPositiveButton
                lifecycleScope.launch {
                    relayAttempt { edit(link, row.id, text) }.onFailure {
                        val tooLate = it is RelayException && it.code == RelayException.INVALID_ARGUMENT
                        toast(if (tooLate) getString(R.string.chat_edit_too_late) else errorText(link, it))
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .let(::trackConfirm)
    }

    /** Like "delete for everyone", editing is the service's own manager's. */
    private suspend fun edit(link: ChatBackend, messageId: String, text: String) = when (link) {
        is WaManager -> link.editMessage(messageId, text)
        is com.lightdecoder.imessage.IMessageManager -> link.editMessage(messageId, text)
        else -> throw RelayException(WaErrors.UNSUPPORTED, "no editing")
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

    /**
     * The phone number to call from this conversation: its one other person's,
     * in a one-to-one conversation of a phone-number service. Null in a group,
     * in email, or without a number.
     */
    private fun callNumber(): String? {
        if (manager.service == ChatService.GMAIL) return null
        return otherAddress()?.takeIf { it.count(Char::isDigit) >= MIN_CALL_DIGITS && !it.contains('@') }
    }

    /** The one other person's number (or, for iMessage, email address) in a one-to-one conversation. */
    private fun otherAddress(): String? {
        val conv = conversation ?: return null
        if (conv.isGroup) return null
        val other = participants.filter { !it.isMe }.singleOrNull() ?: return null
        return other.number.ifBlank { other.formattedNumber }.trim().takeIf { it.isNotEmpty() }
    }

    /**
     * "Send iMessage" from a Messages conversation with Blue Bubbles on: the
     * iMessage chat with the same person if one is stored here, else a new
     * one in OpenBubbles. Through the iMessage library Apple is asked whether
     * they have iMessage, and the chat is made here (or it says they have not).
     */
    private fun sendIMessage() {
        val address = otherAddress()
        if (address == null) {
            toast(getString(R.string.imessage_no_address))
            return
        }
        val iMessage = com.lightdecoder.imessage.IMessageManager.get(this)
        val direct = com.lightdecoder.imessage.direct.DirectLink.get(this)
        lifecycleScope.launch {
            var chat = withContext(Dispatchers.IO) { iMessage.conversationWith(address) }
            if (chat == null && direct.active) {
                chat = withContext(Dispatchers.IO) { relayAttempt { direct.conversationFor(address) } }
                    .getOrElse {
                        toast(errorText(iMessage, it))
                        return@launch
                    }
                    ?: run {
                        toast(getString(R.string.imessage_not_on_imessage, address))
                        return@launch
                    }
            }
            if (chat != null) {
                startActivity(RelayIntents.thread(this@ThreadActivity, chat, service = ChatService.IMESSAGE).setFlags(0))
            } else {
                openNewIMessageChat(address, "")
            }
        }
    }

    /**
     * Opens OpenBubbles on a new chat with [address] and [text], saying what to do when it could
     * only open its share screen or its main screen. False if it could not be opened at all.
     */
    private fun openNewIMessageChat(address: String, text: String): Boolean {
        val iMessage = com.lightdecoder.imessage.IMessageManager.get(this)
        return when (iMessage.startInOpenBubbles(this, address, text)) {
            com.lightdecoder.imessage.OpenBubbles.NewChat.CHAT_OPENED -> true
            com.lightdecoder.imessage.OpenBubbles.NewChat.SHARE_OPENED -> {
                copyAddress(address)
                toast(getString(R.string.imessage_pick_chat_in_share))
                true
            }
            com.lightdecoder.imessage.OpenBubbles.NewChat.APP_OPENED -> {
                copyAddress(address)
                toast(getString(R.string.imessage_start_chat_in_app))
                true
            }
            com.lightdecoder.imessage.OpenBubbles.NewChat.NOT_INSTALLED -> {
                toast(errorText(iMessage, com.lightdecoder.imessage.IMessageErrors.NOT_INSTALLED))
                false
            }
            com.lightdecoder.imessage.OpenBubbles.NewChat.FAILED -> {
                toast(getString(R.string.imessage_would_not_open))
                false
            }
        }
    }

    /** Puts the person's number (or email address) on the clipboard, to start the chat with by hand. */
    private fun copyAddress(address: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.imessage_address_label), address))
    }

    // --- linking an iMessage chat by hand ---------------------------------------------------

    /** The iMessage chats not inside any conversation; the one picked is shown inside this one from now on. */
    private fun chooseIMessageToLink() {
        val id = conversationId
        lifecycleScope.launch {
            val chats = withContext(Dispatchers.IO) {
                BlueBubblesLinks.unpaired(applicationContext).map { row ->
                    val name = BlueBubblesLinks.iMessageName(applicationContext, row.id).ifBlank { getString(R.string.chat_untitled) }
                    row.id to (if (row.snippet.isBlank()) name else "$name \u2014 ${row.snippet.take(40)}")
                }
            }
            if (conversationId != id) return@launch
            if (chats.isEmpty()) {
                toast(getString(if (directMode()) R.string.imessage_link_none_direct else R.string.imessage_link_none))
                return@launch
            }
            MaterialAlertDialogBuilder(this@ThreadActivity)
                .setTitle(R.string.imessage_link_pick_title)
                .setItems(chats.map { it.second }.toTypedArray()) { _, which ->
                    if (conversationId != id) return@setItems
                    val chosen = chats[which].first
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { com.lightdecoder.imessage.IMessageLinks.setManual(applicationContext, chosen, id) }
                        toast(getString(R.string.imessage_linked))
                        reload()
                        invalidateOptionsMenu()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun confirmUnlinkIMessage() {
        val companion = companionId ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.imessage_unlink)
            .setMessage(R.string.imessage_unlink_body)
            .setPositiveButton(R.string.imessage_unlink_confirm) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { com.lightdecoder.imessage.IMessageLinks.setManual(applicationContext, companion, null) }
                    reload()
                    invalidateOptionsMenu()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- unsending an iMessage ------------------------------------------------------------

    /**
     * Without the iMessage library: opens OpenBubbles on [row]'s chat for something its notification
     * cannot do ([what]: edit, react), with [how] to do it there.
     */
    private fun openInOpenBubblesFor(row: MessageRow, how: String, what: String) {
        if (directMode()) return
        val chat = row.conversationId.takeIf { backendFor(row).service == ChatService.IMESSAGE } ?: companionId ?: return
        val opened = com.lightdecoder.imessage.IMessageReplies.open(this, chat) ||
            iMessageLink.handOff(this, chat, "") ||
            com.lightdecoder.imessage.OpenBubbles.launch(this)
        com.lightdecoder.imessage.IMessageLog.add(this, "${com.lightdecoder.imessage.IMessageLog.chatRef(chat)}: $what: ${if (opened) "OpenBubbles opened on the chat" else "OpenBubbles would not open"}")
        toast(if (opened) how else errorText(iMessageLink, com.lightdecoder.imessage.IMessageErrors.NOT_INSTALLED))
    }

    /**
     * One of the user's iMessages, within the 2 minutes Apple allows ([UnsendWindow]): through the
     * iMessage library it is retracted straight away. Without the library, OpenBubbles opens on the
     * chat (its notification has no unsend button), where a long press on it offers Undo Send.
     */
    @androidx.annotation.VisibleForTesting
    internal fun unsendIMessage(row: MessageRow) {
        // Sent through the iMessage library: unsent straight away, and Apple's answer is shown.
        val direct = com.lightdecoder.imessage.direct.DirectLink.get(this)
        if (direct.canUnsend(row, reachable = row.conversationId in directReachable)) {
            unsendDirectly(direct, row)
            return
        }
        val chat = row.conversationId
        val ref = com.lightdecoder.imessage.IMessageLog.chatRef(chat)
        val now = System.currentTimeMillis()
        val age = now - row.timestampMs
        // Through the library only a message still in time is offered; one that is not any more is too late.
        if (direct.active || !UnsendWindow.canUnsend(row.timestampMs, now)) {
            com.lightdecoder.imessage.IMessageLog.add(this, "$ref: unsend asked ${age / 60_000} min after sending (too late for Apple)")
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.imessage_action_unsend)
                .setMessage(R.string.imessage_unsend_too_late)
                .setPositiveButton(R.string.imessage_delete_here) { _, _ -> confirmDeleteMessage(row) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val opened = com.lightdecoder.imessage.IMessageReplies.open(this, chat) ||
            iMessageLink.handOff(this, chat, "") ||
            com.lightdecoder.imessage.OpenBubbles.launch(this)
        com.lightdecoder.imessage.IMessageLog.add(this, "$ref: unsend: ${if (opened) "OpenBubbles opened on the chat for Undo Send" else "OpenBubbles would not open"}")
        if (opened) toast(getString(R.string.imessage_unsend_how)) else toast(errorText(iMessageLink, com.lightdecoder.imessage.IMessageErrors.NOT_INSTALLED))
    }

    /**
     * Unsend through the iMessage library: who it went to comes from the archive (so it works after a
     * restart), Apple's answer is waited for, and a no (too late, not connected, refused, no answer)
     * is shown as "Couldn't unsend: <reason>". It disappears for people on iOS 16 or later.
     */
    private fun unsendDirectly(direct: com.lightdecoder.imessage.direct.DirectLink, row: MessageRow) {
        toast(getString(R.string.imessage_unsending))
        lifecycleScope.launch {
            // A screen closed meanwhile shows nothing: the unsend goes on without it (DirectLink.unsend).
            relayAttempt { withContext(Dispatchers.IO) { direct.unsend(row.id) } }
                .onSuccess { toast(getString(R.string.imessage_unsent_toast)) }
                .onFailure { e ->
                    if (isFinishing || isDestroyed) return@onFailure
                    MaterialAlertDialogBuilder(this@ThreadActivity)
                        .setTitle(R.string.imessage_unsend_failed_title)
                        .setMessage(getString(R.string.imessage_unsend_failed, com.lightdecoder.imessage.direct.DirectEngine.readable(e)))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
        }
    }

    // --- Check iMessage -------------------------------------------------------------------

    /** Shows what the check sends, and to whom, before anything is sent. */
    private fun confirmIMessageCheck() {
        val companion = companionId
        val address = if (companion == null) otherAddress() ?: return toast(getString(R.string.imessage_no_address)) else null
        val name = supportActionBar?.title?.toString().orEmpty()
        val text = com.lightdecoder.imessage.IMessageCheck.TEXT
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.imessage_check)
            .setMessage(
                getString(R.string.imessage_check_explain, name, text) +
                    if (companion == null) {
                        "\n\n" + getString(if (directMode()) R.string.imessage_check_new_chat_direct else R.string.imessage_check_new_chat)
                    } else {
                        ""
                    }
            )
            .setPositiveButton(R.string.imessage_check_send) { _, _ -> runIMessageCheck(companion, address, text) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Starts a check of [text] at once, without asking first (for the on-device test). */
    @androidx.annotation.VisibleForTesting
    internal fun startIMessageCheck(text: String) {
        val companion = companionId
        runIMessageCheck(companion, if (companion == null) otherAddress() else null, text)
    }

    /** The dialog of the check under way, while it is open. */
    @androidx.annotation.VisibleForTesting
    internal var checkDialog: androidx.appcompat.app.AlertDialog? = null

    /**
     * Sends [text] to the person's iMessage chat [companion] (silently through
     * OpenBubbles' Reply button, else OpenBubbles opens with it filled in, or
     * a new chat with [address] when there is none yet), then has
     * [com.lightdecoder.imessage.IMessageChecks] watch for the reply and shows it.
     * Through the iMessage library it goes from here, to a chat Apple says
     * [address] can have (or it says at once that they are not on iMessage).
     */
    private fun runIMessageCheck(companion: String?, address: String?, text: String) {
        if (companion == null && address == null) return
        val id = conversationId
        val name = supportActionBar?.title?.toString().orEmpty()
        val im = iMessageLink
        val textRepo = manager.repo
        val sentAt = System.currentTimeMillis()
        val ref = companion?.let(com.lightdecoder.imessage.IMessageLog::chatRef) ?: "(new chat)"
        fun log(line: String) = com.lightdecoder.imessage.IMessageLog.add(this, "Check iMessage $ref: $line")
        val app = applicationContext
        fun watch(chat: String?, silent: Boolean) = com.lightdecoder.imessage.IMessageChecks.watch(
            this@ThreadActivity, id, name, chat,
            findChat = {
                BlueBubblesLinks.iMessageFor(app, textRepo.conversation(id), textRepo.participants(id))
                    ?: address?.let(im::conversationWith)
            },
            text, silent, sentAt, im, textRepo,
        )
        val direct = com.lightdecoder.imessage.direct.DirectLink.get(this)
        lifecycleScope.launch {
            log("started; ${im.setupSummary()}")
            if (direct.active) {
                val chat = companion ?: withContext(Dispatchers.IO) { relayAttempt { direct.conversationFor(address!!) } }
                    .getOrElse {
                        log("Apple could not be asked whether they have iMessage")
                        toast(errorText(im, it))
                        return@launch
                    }
                    ?: run {
                        log("not on iMessage (Apple says so): nothing sent")
                        toast(getString(R.string.imessage_not_on_imessage, address))
                        return@launch
                    }
                val sent = withContext(Dispatchers.IO) { relayAttempt { im.sendText(chat, text) } }
                if (sent.isFailure) {
                    log("not sent: the iMessage library cannot reach this chat")
                    toast(errorText(im, sent.exceptionOrNull()!!))
                    return@launch
                }
                log("sent through the iMessage library")
                watch(chat, silent = true)
                showIMessageCheck()
                return@launch
            }
            val silent = companion != null &&
                withContext(Dispatchers.IO) { relayAttempt { im.sendText(companion, text) } }.isSuccess
            if (companion == null) {
                // No iMessage chat with them yet: OpenBubbles starts one, with the check filled in.
                log("no iMessage chat with this person yet: opening a new one in OpenBubbles")
                if (!openNewIMessageChat(address!!, text)) return@launch
            } else if (!silent) {
                log("no usable Reply button: OpenBubbles opened for the user to tap send")
                handOffToOpenBubbles(im, companion, text, emptyList(), clearComposer = false)
            }
            watch(companion, silent)
            showIMessageCheck()
        }
    }

    /** The check under way (or just finished) for this conversation, in its dialog. */
    private fun showIMessageCheck() {
        val checks = com.lightdecoder.imessage.IMessageChecks
        val first = checks.current.value?.takeIf { it.conversationId == conversationId } ?: return
        checkDialog?.dismiss()
        val view = layoutInflater.inflate(R.layout.dialog_imessage_check, binding.root as android.view.ViewGroup, false)
        val steps = view.findViewById<android.widget.TextView>(R.id.checkSteps)
        val verdict = view.findViewById<android.widget.TextView>(R.id.checkVerdict)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.imessage_check)
            .setView(view)
            .setPositiveButton(if (first.running) R.string.imessage_check_hide else R.string.imessage_check_close, null)
            .setNeutralButton(R.string.imessage_check_stop) { _, _ -> checks.stop(this) }
            .create()
        checkDialog = dialog
        val job = lifecycleScope.launch {
            checks.current.collect { run ->
                if (run == null || run.conversationId != conversationId) return@collect
                steps.text = checkSteps(run.progress, run.name, run.silent, run.waitedMs)
                verdict.text = checkVerdict(run.progress, run.name, run.waitedMs, run.ended)
                dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                    ?.setText(if (run.running) R.string.imessage_check_hide else R.string.imessage_check_close)
                dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)?.visibility = if (run.running) View.VISIBLE else View.GONE
            }
        }
        dialog.setOnShowListener {
            checks.dialogShowing = true
            val run = checks.current.value
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)?.visibility = if (run?.running == true) View.VISIBLE else View.GONE
        }
        dialog.setOnDismissListener {
            checks.dialogShowing = false
            job.cancel()
            checks.noteHidden(this)
            if (checkDialog === dialog) checkDialog = null
            invalidateOptionsMenu()
        }
        dialog.show()
    }

    private fun checkSteps(p: com.lightdecoder.imessage.IMessageCheck.Progress, name: String, silent: Boolean, waitedMs: Long): String {
        fun line(done: Boolean, text: String) = (if (done) "\u2713  " else "\u2026  ") + text
        // Through the iMessage library: sent from here, then confirmed by Apple.
        val direct = directMode()
        // Sent from OpenBubbles' own screen: nothing of it can be seen here until the reply, so no steps that never tick.
        val steps = if (silent || p.handedOver) listOf(
            line(p.handedOver, getString(if (direct) R.string.imessage_check_step_sent_direct else R.string.imessage_check_step_handed)),
            line(p.inOpenBubbles, getString(if (direct) R.string.imessage_check_step_confirmed_direct else R.string.imessage_check_step_openbubbles)),
            line(p.done, getString(R.string.imessage_check_step_reply, name)),
        ) else listOf(
            "\u2022  " + getString(R.string.imessage_check_step_tap_send),
            line(p.done, getString(R.string.imessage_check_step_reply, name)),
        )
        return steps.joinToString("\n") + when {
            p.sentAsText -> "\n\n" + getString(R.string.imessage_check_went_as_text)
            !silent && !p.handedOver && !p.done -> "\n\n" + getString(R.string.imessage_check_come_back)
            !p.inOpenBubbles && waitedMs > com.lightdecoder.imessage.IMessageChecks.SLOW_MS ->
                "\n\n" + getString(if (direct) R.string.imessage_check_not_confirmed_direct else R.string.imessage_check_not_in_openbubbles)
            else -> ""
        }
    }

    private fun checkVerdict(p: com.lightdecoder.imessage.IMessageCheck.Progress, name: String, waitedMs: Long, ended: Boolean): String = when (p.reply) {
        com.lightdecoder.imessage.IMessageCheck.Reply.IMESSAGE -> getString(R.string.imessage_check_blue, name)
        com.lightdecoder.imessage.IMessageCheck.Reply.TEXT -> getString(R.string.imessage_check_green, name)
        com.lightdecoder.imessage.IMessageCheck.Reply.NONE -> if (ended) getString(R.string.imessage_check_ended, name) else
            getString(if (waitedMs > com.lightdecoder.imessage.IMessageChecks.SLOW_MS) R.string.imessage_check_still_waiting else R.string.imessage_check_waiting, name)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.thread_actions, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val conv = conversation
        menu.findItem(R.id.action_call)?.isVisible = callNumber() != null
        // Never through the iMessage library (it opens no other app), nor without the app to open.
        menu.findItem(R.id.action_open_openbubbles)?.isVisible = manager.service == ChatService.IMESSAGE && !directMode() &&
            com.lightdecoder.imessage.OpenBubbles.isInstalled(this)
        // Not when the person's iMessages are already here.
        menu.findItem(R.id.action_send_imessage)?.isVisible = manager.service == ChatService.MESSAGES && companionId == null &&
            com.lightdecoder.imessage.IMessagePrefs.enabled(this) && conv != null && !conv.isGroup && otherAddress() != null
        // Also before they have an iMessage chat: the check then starts one (in OpenBubbles, or through the library once Apple says they have iMessage).
        menu.findItem(R.id.action_check_imessage)?.isVisible = manager.service == ChatService.MESSAGES &&
            (companionId != null || (com.lightdecoder.imessage.IMessagePrefs.enabled(this) && conv != null && !conv.isGroup && otherAddress() != null))
        // The iMessage light: how this conversation sends (Messages tab and its iMessage chats).
        menu.findItem(R.id.action_imessage_light)?.let { item ->
            val chat = if (manager.service == ChatService.IMESSAGE) conversationId else companionId
            if (manager.service == ChatService.MESSAGES || manager.service == ChatService.IMESSAGE) {
                IMessageLight.bind(this, item, IMessageLight.forConversation(this, chat, conv?.isGroup == true), hideWhenOff = true)
            } else {
                item.isVisible = false
            }
        }
        val blueOn = com.lightdecoder.imessage.IMessagePrefs.enabled(this)
        menu.findItem(R.id.action_link_imessage)?.isVisible =
            manager.service == ChatService.MESSAGES && blueOn && companionId == null && conv != null && !conv.isGroup
        menu.findItem(R.id.action_unlink_imessage)?.isVisible = manager.service == ChatService.MESSAGES && companionId != null
        val checking = com.lightdecoder.imessage.IMessageChecks.current.value?.let { it.running && it.conversationId == conversationId } == true
        menu.findItem(R.id.action_check_imessage)?.setTitle(if (checking) R.string.imessage_check_running else R.string.imessage_check)
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
        menu.findItem(R.id.action_notify_read)?.apply {
            // Where the service says when a message is read, and read notifications are on at all.
            isVisible = conv != null && !deleted && manager.service != ChatService.GMAIL && manager.service != ChatService.IMESSAGE &&
                com.lightdecoder.relay.ReadNotifyPrefs.enabled(this@ThreadActivity)
            isChecked = com.lightdecoder.relay.ReadNotifyPrefs.forConversation(this@ThreadActivity, manager.service, conversationId)
        }
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
            R.id.action_call -> callNumber()?.let { number ->
                try {
                    // The dialer, with the number filled in: the user places the call there.
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
                } catch (e: ActivityNotFoundException) {
                    toast(getString(R.string.chat_call_no_app))
                }
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
            R.id.action_send_imessage -> sendIMessage()
            R.id.action_link_imessage -> chooseIMessageToLink()
            R.id.action_unlink_imessage -> confirmUnlinkIMessage()
            R.id.action_check_imessage ->
                if (com.lightdecoder.imessage.IMessageChecks.current.value?.let { it.running && it.conversationId == conversationId } == true) {
                    showIMessageCheck()
                } else {
                    confirmIMessageCheck()
                }
            R.id.action_open_openbubbles -> if (!directMode()) {
                val opened = com.lightdecoder.imessage.IMessageReplies.open(this, conversationId) ||
                    (manager as? com.lightdecoder.imessage.IMessageManager)?.handOff(this, conversationId, "") == true ||
                    com.lightdecoder.imessage.OpenBubbles.launch(this)
                if (!opened) toast(errorText(manager, com.lightdecoder.imessage.IMessageErrors.NOT_INSTALLED))
            }
            R.id.action_link_status -> startActivity(ServiceUi.status(this, manager.service))
            R.id.action_notify_read -> {
                val on = !com.lightdecoder.relay.ReadNotifyPrefs.forConversation(this, manager.service, conversationId)
                com.lightdecoder.relay.ReadNotifyPrefs.setForConversation(this, manager.service, conversationId, on)
                item.isChecked = on
                toast(getString(if (on) R.string.read_notify_person_on else R.string.read_notify_person_off))
                invalidateOptionsMenu()
            }
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
                // iMessage chats are listed in the Messages tab.
                .putExtra(RelayIntents.EXTRA_SERVICE, ConversationsActivity.tabServiceOf(manager.service).key)
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
                if (linked) ServiceUi.deleteConversationBody(link.service, ServiceUi.direct(this, link.service)) else R.string.chat_delete_conversation_body_local
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
        private const val HAND_OFF_KEEP_MS = 24 * 60 * 60 * 1000L
        private const val STATE_WINDOW = "window"
        private const val STATE_ATTACHMENTS = "attachments"

        /** At most this many pictures are taken in at once as stickers. */
        private const val MAX_STICKER_IMPORT = 50

        /** At most this many stickers go out in one "Send several". */
        private const val MAX_STICKERS_AT_ONCE = 10

        /** A picture larger than this is not made into a sticker. */
        private const val MAX_STICKER_SOURCE_BYTES = 20L * 1024 * 1024

        /** Shorter than any callable number (short codes from businesses are not offered). */
        private const val MIN_CALL_DIGITS = 6
        private const val TOOLBAR_AVATAR_DP = 36
        private const val TOOLBAR_AVATAR_GAP_DP = 14

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

        /** Most pictures picked for one message. */
        private const val MAX_ATTACHMENTS = 10

        /** Voice messages: shorter than this is a slip of the finger; the longest for an RCS chat and for MMS. */
        private const val MIN_VOICE_MS = 1_000L
        private const val MAX_VOICE_RCS_MS = 10 * 60 * 1000
        private const val MAX_VOICE_MMS_MS = 3 * 60 * 1000
        private const val DISABLED_ALPHA = 0.38f

        /** How far behind this phone's clock a message may be stamped and still count as arriving now. */
        private const val READ_ALOUD_CLOCK_SLACK_MS = 2 * 60_000L

        /** The most new messages read out from one batch of arrivals (the newest ones). */
        private const val MAX_READ_AT_ONCE = 5

        /** The "no text-to-speech" toast was shown in this app run. */
        @VisibleForTesting
        internal var readerUnavailableShown = false

        /** The quick reactions offered on long press, in Google Messages' order. */
        val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢")

        /** An iMessage's quick reactions: Apple's six tapbacks, in an iPhone's order. */
        val TAPBACKS = with(com.lightdecoder.imessage.direct.DirectArchive.Tapback) { listOf(LOVE, LIKE, DISLIKE, LAUGH, EMPHASIZE, QUESTION) }

        /**
         * Tests only: runs on the IO thread before every read of a
         * conversation's rows, with its id (e.g. to hold one, as a busy database would).
         */
        @VisibleForTesting
        @Volatile
        internal var beforeLocalRead: ((String) -> Unit)? = null
    }
}
