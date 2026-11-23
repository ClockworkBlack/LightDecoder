package com.lightdecoder.ui.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveChange
import com.lightdecoder.archive.ArchiveEvents
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.ConversationRow
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.chat.ChatService
import com.lightdecoder.databinding.ActivityCallsBinding
import com.lightdecoder.databinding.ItemCallHeaderBinding
import com.lightdecoder.databinding.ItemCallRowBinding
import com.lightdecoder.imessage.direct.DirectLink
import com.lightdecoder.imessage.direct.IMessageExtra
import com.lightdecoder.imessage.facetime.CallRows
import com.lightdecoder.imessage.facetime.Outcome
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.applySystemBarPadding
import java.io.File
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The FaceTime list (as the FaceTime app's recents): every FaceTime call's row of the iMessage
 * archive, newest first, by day, each with its person or group chat, whether it was video or audio,
 * how it went (a missed one in red), when and how long. A tap asks Video or Audio and calls again
 * (a group chat's call goes to its members again); a long press opens the chat or deletes the row.
 * "New FaceTime" picks people to call together. Main process, not exported; from the chats' menu.
 *
 * Only LightDecoder's own rows are listed (who called, as Apple vouches for them, or whom the user
 * called): never a list of calls made from what other people's phones say. None of a chat in Deleted
 * or Spam ([ArchiveRepository.calls]), nor of anyone [hidden] says to leave out.
 */
class CallsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallsBinding
    private lateinit var adapter: CallsAdapter
    private val repo: ArchiveRepository get() = ScreenBackends.get(this, ChatService.IMESSAGE).repo

    /** What the list shows now. */
    @VisibleForTesting
    internal var items: List<CallList.Item> = emptyList()
        private set

    private val onArchiveChange: (ArchiveChange) -> Unit = { runOnUiThread { if (!isFinishing) load() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarPadding(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        adapter = CallsAdapter(onTap = ::callAgain, onLongPress = ::more)
        binding.calls.layoutManager = LinearLayoutManager(this)
        binding.calls.adapter = adapter
        binding.newCall.setOnClickListener { startActivity(NewConversationActivity.newFaceTime(this)) }
        markOpened(this)
    }

    override fun onStart() {
        super.onStart()
        ArchiveEvents.register(onArchiveChange)
        load()
    }

    override fun onStop() {
        ArchiveEvents.unregister(onArchiveChange)
        super.onStop()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun load() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { runCatching { read() }.getOrDefault(emptyList()) }
            val built = CallList.items(rows, System.currentTimeMillis(), dayTexts())
            items = built
            adapter.submitList(built)
            binding.empty.visibility = if (built.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /** The calls as the archive has them, with who each is. Runs on IO. */
    private fun read(): List<CallList.Call> {
        val pictures = com.lightdecoder.relay.ContactPhotos.get(applicationContext)
        val titles = HashMap<String, Triple<String, File?, String?>>()
        return repo.calls(MAX_CALLS).mapNotNull { (row, chat) ->
            val (title, photo, peer) = titles.getOrPut(chat.id) {
                val people = repo.participants(chat.id)
                // A chat with one person: whose, so that a blocked person's every call is left out, placed ones too.
                val peer = if (chat.isGroup) null else people.filter { !it.isMe }.singleOrNull()?.let { it.number.ifBlank { it.id } }
                val photo = if (chat.isGroup) {
                    AvatarPhotos.forConversation(applicationContext, ChatService.IMESSAGE, chat.id, true, people)
                } else {
                    people.firstOrNull { !it.isMe }?.let { pictures.forPerson(it) }
                }
                Triple(ConversationTitles.of(this, repo, chat), photo, peer)
            }
            CallList.call(row, chat, title, photo, peer = peer, hidden = ::hidden)
        }
    }

    /**
     * Whom the list leaves out, by address: nobody yet. (Where the user's own block list goes, so
     * that a blocked person's calls are never listed.)
     */
    @Suppress("UNUSED_PARAMETER")
    private fun hidden(address: String): Boolean = false

    private fun dayTexts() = object : CallList.DayTexts {
        override fun today() = getString(R.string.facetime_calls_today)
        override fun yesterday() = getString(R.string.facetime_calls_yesterday)
        override fun date(ms: Long) = DateUtils.formatDateTime(this@CallsActivity, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY)
    }

    /** A tap: Video or Audio, asked at the row, then the call again ([placeIntentFor]). */
    private fun callAgain(call: CallList.Call, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, VIDEO, 0, R.string.facetime_video)
            menu.add(0, AUDIO, 1, R.string.facetime_audio)
            setOnMenuItemClickListener { item ->
                place(call, video = item.itemId == VIDEO)
                true
            }
            show()
        }
    }

    /** Calls [call]'s person or group again: the call screen is started with whom to call, and does the rest. */
    @VisibleForTesting
    internal fun place(call: CallList.Call, video: Boolean) {
        val direct = DirectLink.get(applicationContext)
        lifecycleScope.launch {
            val intent = withContext(Dispatchers.IO) { runCatching { placeIntentFor(this@CallsActivity, direct, call, video) }.getOrNull() }
            if (intent == null) {
                Toast.makeText(this@CallsActivity, R.string.facetime_not_available, Toast.LENGTH_LONG).show()
                return@launch
            }
            try {
                startActivity(intent)
            } catch (e: RuntimeException) {
                Toast.makeText(this@CallsActivity, R.string.facetime_not_available, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** A long press: Open Chat, or Delete (this call's row). */
    private fun more(call: CallList.Call, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, OPEN, 0, R.string.facetime_calls_open_chat)
            menu.add(0, DELETE, 1, R.string.facetime_calls_delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    OPEN -> openChat(call)
                    DELETE -> delete(call)
                }
                true
            }
            show()
        }
    }

    @VisibleForTesting
    internal fun openChat(call: CallList.Call) {
        startActivity(RelayIntents.thread(this, call.conversationId, service = ChatService.IMESSAGE))
    }

    @VisibleForTesting
    internal fun delete(call: CallList.Call) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { repo.applyDeletion(call.messageId) } }
            Toast.makeText(this@CallsActivity, R.string.facetime_calls_deleted, Toast.LENGTH_SHORT).show()
            load()
        }
    }

    companion object {
        private const val VIDEO = 1
        private const val AUDIO = 2
        private const val OPEN = 3
        private const val DELETE = 4

        /** The most calls listed. */
        const val MAX_CALLS = 300

        private const val PREFS = "facetime_calls"
        private const val KEY_OPENED = "opened"

        /** The list was opened now: the chats' menu counts missed calls from here on. */
        fun markOpened(context: Context, now: Long = System.currentTimeMillis()) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_OPENED, now).apply()
        }

        /** The missed calls since the list was last opened (as the chats' menu says them). Reads the archive: not on the main thread. */
        fun missedSinceOpened(context: Context): Int {
            val since = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_OPENED, 0L)
            val repo = ScreenBackends.get(context, ChatService.IMESSAGE).repo
            return CallList.missed(repo.calls(MAX_CALLS).map { it.first }, since)
        }

        /** The chats' menu entry: "FaceTime", or with how many calls were missed, in red. */
        fun menuTitle(context: Context, missed: Int): CharSequence {
            if (missed <= 0) return context.getString(R.string.facetime_calls)
            val text = context.getString(R.string.facetime_calls_missed, missed)
            return SpannableString(text).apply {
                setSpan(ForegroundColorSpan(ContextCompat.getColor(context, R.color.call_end)), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        /**
         * The call screen's intent to call [call] again: a group chat's call to its members who can be
         * called, a chat with one person's to them; null when no call can go from that chat now (iMessage
         * not set up, FaceTime off, the chat gone). Reads the archive: not on the main thread.
         */
        fun placeIntentFor(context: Context, direct: DirectLink, call: CallList.Call, video: Boolean): Intent? {
            if (call.group) {
                val group = direct.faceTimeGroup(call.conversationId) ?: return null
                val peers = direct.faceTimeCallable(group).ifEmpty { return null }
                return com.lightdecoder.ui.imessage.FaceTimeCallActivity.placeIntent(context, peers, group.name, video, group.from, group.conversationId)
            }
            val target = direct.faceTimeTarget(call.conversationId) ?: return null
            return com.lightdecoder.ui.imessage.FaceTimeCallActivity.placeIntent(
                context, target.peer, target.name, video, target.from, target.conversationId, target.photo,
            )
        }
    }
}

/** The FaceTime list's rows, by day. Pure; tested in CallsScreenTest. */
object CallList {

    /** One call: its row and chat, what the list shows of them. */
    data class Call(
        val messageId: String,
        val conversationId: String,
        val title: String,
        val group: Boolean,
        val photo: File?,
        val video: Boolean,
        val outgoing: Boolean,
        val outcome: Outcome,
        val startedMs: Long,
        val durationMs: Long,
        /** The person's address for an incoming one ("" for the user's own calls). */
        val address: String,
    ) {
        val missed: Boolean get() = outcome == Outcome.MISSED && !outgoing
    }

    sealed class Item {
        data class Day(val label: String, val dayStartMs: Long) : Item()
        data class Row(val call: Call) : Item()
    }

    /** The day headers' words. */
    interface DayTexts {
        fun today(): String
        fun yesterday(): String
        fun date(ms: Long): String
    }

    /**
     * [row] of [chat] as the list shows it, or null: no call's row, or one of someone [hidden] says to
     * leave out: whoever called, or [peer] (the one person of a chat with one person), whichever
     * way the call went.
     */
    fun call(row: MessageRow, chat: ConversationRow, title: String, photo: File?, peer: String? = null, hidden: (String) -> Boolean = { false }): Call? {
        if (!CallRows.isCallRow(row.id)) return null
        val info = IMessageExtra.parse(row.extra).call ?: return null
        val outcome = Outcome.of(info.outcome) ?: return null
        val address = if (row.isOutgoing) "" else row.senderId
        val people = listOfNotNull(address.takeIf { it.isNotEmpty() }, peer?.takeIf { it.isNotEmpty() && !chat.isGroup })
        if (people.any { runCatching { hidden(it) }.getOrDefault(false) }) return null
        return Call(row.id, chat.id, title, chat.isGroup, photo, info.video, info.outgoing, outcome, row.timestampMs, info.durationMs, address)
    }

    /** [calls] (newest first) with a header before each day's first, as of [now]. */
    fun items(calls: List<Call>, now: Long, texts: DayTexts, calendar: () -> Calendar = { Calendar.getInstance() }): List<Item> {
        val today = startOfDay(now, calendar())
        val out = ArrayList<Item>(calls.size + 8)
        var day = Long.MIN_VALUE
        for (c in calls.sortedByDescending { it.startedMs }) {
            val start = startOfDay(c.startedMs, calendar())
            if (start != day) {
                day = start
                val label = when (start) {
                    today -> texts.today()
                    startOfDay(today - 12 * 60 * 60 * 1000L, calendar()) -> texts.yesterday()
                    else -> texts.date(c.startedMs)
                }
                out += Item.Day(label, start)
            }
            out += Item.Row(c)
        }
        return out
    }

    /** How many of [rows] are calls the user missed after [sinceMs]. */
    fun missed(rows: List<MessageRow>, sinceMs: Long): Int = rows.count { row ->
        row.timestampMs > sinceMs && !row.isOutgoing && CallRows.isCallRow(row.id) &&
            IMessageExtra.parse(row.extra).call?.let { !it.outgoing && Outcome.of(it.outcome) == Outcome.MISSED } == true
    }

    private fun startOfDay(ms: Long, c: Calendar): Long {
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}

/** The FaceTime list's adapter: day headers and calls. */
class CallsAdapter(
    private val onTap: (CallList.Call, View) -> Unit,
    private val onLongPress: (CallList.Call, View) -> Unit,
) : ListAdapter<CallList.Item, RecyclerView.ViewHolder>(DIFF) {

    class Header(val binding: ItemCallHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    class Row(val binding: ItemCallRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int = if (getItem(position) is CallList.Item.Day) DAY else ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == DAY) Header(ItemCallHeaderBinding.inflate(inflater, parent, false)) else Row(ItemCallRowBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is CallList.Item.Day -> (holder as Header).binding.day.text = item.label
            is CallList.Item.Row -> bind(holder as Row, item.call)
        }
    }

    private fun bind(holder: Row, call: CallList.Call) {
        val b = holder.binding
        val context = b.root.context
        Avatars.bind(b.avatar, call.title, call.conversationId, "", call.photo)
        b.name.text = call.title
        val what = CallRows.text(CallRows.ResourceTexts(context), call.video, call.outcome, call.durationMs)
        b.detail.text = what
        b.name.setTextColor(
            if (call.missed) ContextCompat.getColor(context, R.color.call_end)
            else com.google.android.material.color.MaterialColors.getColor(b.name, com.google.android.material.R.attr.colorOnSurface),
        )
        b.kind.setImageResource(if (call.video) R.drawable.ic_ft_video else R.drawable.ic_ft_audio)
        val time = DateUtils.formatDateTime(context, call.startedMs, DateUtils.FORMAT_SHOW_TIME)
        b.time.text = time
        b.root.contentDescription = context.getString(R.string.facetime_calls_row_description, call.title, what, time)
        b.root.setOnClickListener { onTap(call, it) }
        b.root.setOnLongClickListener {
            onLongPress(call, it)
            true
        }
    }

    companion object {
        private const val DAY = 0
        private const val ROW = 1

        private val DIFF = object : DiffUtil.ItemCallback<CallList.Item>() {
            override fun areItemsTheSame(a: CallList.Item, b: CallList.Item): Boolean = when {
                a is CallList.Item.Day && b is CallList.Item.Day -> a.dayStartMs == b.dayStartMs
                a is CallList.Item.Row && b is CallList.Item.Row -> a.call.messageId == b.call.messageId
                else -> false
            }

            override fun areContentsTheSame(a: CallList.Item, b: CallList.Item): Boolean = a == b
        }
    }
}
