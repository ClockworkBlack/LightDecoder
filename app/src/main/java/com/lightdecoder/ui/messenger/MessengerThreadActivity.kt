package com.lightdecoder.ui.messenger

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lightdecoder.R
import com.lightdecoder.databinding.ActivityMessengerThreadBinding
import com.lightdecoder.databinding.ItemMessengerMessageBinding
import com.lightdecoder.messenger.MessengerEvents
import com.lightdecoder.messenger.MessengerMessage
import com.lightdecoder.messenger.MessengerReplies
import com.lightdecoder.messenger.MessengerStore
import com.lightdecoder.ui.chat.TimeFormat
import com.lightdecoder.ui.chat.applySystemBarAndImePadding
import java.util.concurrent.Executors

/**
 * One Messenger conversation, as far as Messenger's notifications showed it,
 * with a box to reply.
 *
 * A reply is not sent by LightDecoder. It is filled into the Reply button of
 * Messenger's own notification and pressed ([MessengerReplies]), exactly as
 * typing it in the notification shade would, and the Messenger app sends it.
 * So replying is only possible while Messenger offers that button, and only
 * text: for anything else there are "Open in Messenger" and the full history.
 */
class MessengerThreadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMessengerThreadBinding
    private lateinit var key: String
    private val adapter = MessageAdapter()
    private val io = Executors.newSingleThreadExecutor()
    private val refresh = Runnable { load() }
    private var isGroup = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        if (key.isEmpty()) {
            finish()
            return
        }
        binding = ActivityMessengerThreadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarAndImePadding(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.list.adapter = adapter
        binding.send.setOnClickListener { send() }
        binding.send.isEnabled = false
        binding.input.doAfterTextChanged { binding.send.isEnabled = !it.isNullOrBlank() }
        binding.noReplyOpenApp.setOnClickListener { MessengerActivity.openApp(this, key) }
        binding.noReplyOpenWeb.setOnClickListener { MessengerActivity.openWeb(this) }
    }

    override fun onStart() {
        super.onStart()
        MessengerEvents.register(refresh)
        load()
    }

    override fun onStop() {
        MessengerEvents.unregister(refresh)
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun load() {
        val store = MessengerStore.get(this)
        io.execute {
            val conversation = runCatching { store.conversation(key) }.getOrNull()
            val messages = runCatching { store.messages(key) }.getOrDefault(emptyList())
            // Being looked at is being read.
            if (conversation != null && conversation.unread > 0) {
                runCatching { store.markRead(key) }
                MessengerEvents.notifyChanged()
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (conversation == null) {
                    Toast.makeText(this, R.string.messenger_gone, Toast.LENGTH_SHORT).show()
                    finish()
                    return@runOnUiThread
                }
                supportActionBar?.title = conversation.title
                isGroup = conversation.isGroup
                val atEnd = !binding.list.canScrollVertically(1)
                val grew = messages.size > adapter.itemCount
                adapter.submit(messages)
                if (grew && atEnd && messages.isNotEmpty()) binding.list.scrollToPosition(messages.size - 1)
                renderReply()
            }
        }
    }

    /** The reply box while Messenger offers its Reply button; otherwise where else to go. */
    private fun renderReply() {
        val canReply = MessengerReplies.canReply(key)
        binding.composer.visibility = if (canReply) View.VISIBLE else View.GONE
        binding.noReplyPanel.visibility = if (canReply) View.GONE else View.VISIBLE
    }

    private fun send() {
        val text = binding.input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        when (MessengerReplies.send(this, key, text)) {
            MessengerReplies.Result.HANDED_OVER -> {
                binding.input.text?.clear()
                val store = MessengerStore.get(this)
                val now = System.currentTimeMillis()
                io.execute {
                    runCatching { store.addOutgoing(key, text, now) }
                    MessengerEvents.notifyChanged()
                }
            }
            // The text stays in the box: nothing was sent.
            MessengerReplies.Result.WITHDRAWN -> {
                Toast.makeText(this, R.string.messenger_reply_withdrawn, Toast.LENGTH_LONG).show()
                renderReply()
            }
            MessengerReplies.Result.NO_REPLY_BUTTON -> renderReply()
        }
    }

    // --- menu ----------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.messenger_thread_actions, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_messenger_thread_open_app -> MessengerActivity.openApp(this, key)
            R.id.action_messenger_thread_open_web -> MessengerActivity.openWeb(this)
            R.id.action_messenger_thread_delete -> confirmDelete()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.messenger_delete_title)
            .setMessage(R.string.messenger_delete_body)
            .setNegativeButton(R.string.messenger_cancel, null)
            .setPositiveButton(R.string.messenger_delete_confirm) { _, _ ->
                val store = MessengerStore.get(this)
                io.execute {
                    runCatching { store.deleteConversation(key) }
                    MessengerReplies.forget(key)
                    MessengerEvents.notifyChanged()
                    runOnUiThread { finish() }
                }
            }
            .show()
    }

    // --- messages ------------------------------------------------------------

    private inner class MessageAdapter : RecyclerView.Adapter<MessageHolder>() {
        private var items: List<MessengerMessage> = emptyList()

        fun submit(next: List<MessengerMessage>) {
            items = next
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageHolder =
            MessageHolder(ItemMessengerMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: MessageHolder, position: Int) {
            val message = items[position]
            val previous = items.getOrNull(position - 1)
            holder.bind(message, showSender = isGroup && !message.fromMe && previous?.sender != message.sender)
        }
    }

    private inner class MessageHolder(private val row: ItemMessengerMessageBinding) : RecyclerView.ViewHolder(row.root) {

        fun bind(message: MessengerMessage, showSender: Boolean) {
            val context = row.root.context
            row.row.gravity = if (message.fromMe) Gravity.END else Gravity.START
            row.sender.visibility = if (showSender && message.sender.isNotEmpty()) View.VISIBLE else View.GONE
            row.sender.text = message.sender
            row.bubble.text = MessengerActivity.snippet(context, message.text, message.kind)
            val background = MaterialColors.getColor(
                row.bubble,
                if (message.fromMe) com.google.android.material.R.attr.colorPrimaryContainer
                else com.google.android.material.R.attr.colorSurfaceContainerHigh,
            )
            val foreground = MaterialColors.getColor(
                row.bubble,
                if (message.fromMe) com.google.android.material.R.attr.colorOnPrimaryContainer
                else com.google.android.material.R.attr.colorOnSurface,
            )
            row.bubble.backgroundTintList = ColorStateList.valueOf(background)
            row.bubble.setTextColor(foreground)
            val time = TimeFormat.clock(context, message.timestampMs)
            row.meta.text = if (message.state == MessengerStore.STATE_SENT_UNCONFIRMED) {
                context.getString(R.string.messenger_sent_unconfirmed) + " · " + time
            } else {
                time
            }
        }
    }

    companion object {
        private const val EXTRA_KEY = "com.lightdecoder.messenger.KEY"

        fun intent(context: Context, conversationKey: String): Intent =
            Intent(context, MessengerThreadActivity::class.java).putExtra(EXTRA_KEY, conversationKey)
    }
}
