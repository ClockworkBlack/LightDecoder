package com.lightdecoder.ui.messenger

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.lightdecoder.R
import com.lightdecoder.chat.ChatService
import com.lightdecoder.chat.Features
import com.lightdecoder.databinding.ActivityMessengerBinding
import com.lightdecoder.databinding.ItemMessengerConversationBinding
import com.lightdecoder.discord.DiscordProcess
import com.lightdecoder.messenger.MessengerCapture
import com.lightdecoder.messenger.MessengerConversation
import com.lightdecoder.messenger.MessengerEvents
import com.lightdecoder.messenger.MessengerLinks
import com.lightdecoder.messenger.MessengerNotificationListener
import com.lightdecoder.messenger.MessengerPrefs
import com.lightdecoder.messenger.MessengerProcess
import com.lightdecoder.messenger.MessengerReplies
import com.lightdecoder.messenger.MessengerStore
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.applySystemBarPadding
import com.lightdecoder.ui.chat.Avatars
import com.lightdecoder.ui.chat.ServiceTabStrip
import com.lightdecoder.ui.chat.ServiceUi
import com.lightdecoder.ui.chat.TimeFormat
import com.lightdecoder.ui.discord.DiscordActivity
import java.util.concurrent.Executors

/**
 * The Messenger tab: the conversations the Messenger app has notified the
 * user about, kept on the phone ([MessengerStore]), and the way to Facebook's
 * own page for everything a notification cannot carry
 * ([MessengerWebActivity]).
 *
 * The two halves do not touch. This screen and the conversation screen read
 * only what Messenger's notifications showed; the web screen runs in another
 * process and is never read. See docs/messenger-hybrid.md.
 */
class MessengerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMessengerBinding
    private val adapter = ConversationAdapter()
    private val io = Executors.newSingleThreadExecutor()
    private val refresh = Runnable { load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMessengerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarPadding(binding.root)
        setSupportActionBar(binding.toolbar)
        // No back arrow and no title: the title is the app switcher, as on Discord's screen.
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        setUpServiceTabs()
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
                ServiceTabStrip.fadeOnClose(this@MessengerActivity)
            }
        })

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.setupButton.setOnClickListener { openAccessSettings() }
        binding.resume.setOnClickListener {
            MessengerPrefs.setPaused(this, false)
            render()
        }
        binding.openWeb.setOnClickListener { openWeb(this) }
    }

    override fun onStart() {
        super.onStart()
        MessengerEvents.register(refresh)
        render()
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

    /**
     * The same app switcher the conversation list shows, with Messenger
     * chosen. Choosing another app goes to that app's own screen.
     */
    private fun setUpServiceTabs() {
        val tabs = binding.services
        // The same order as the conversation list: Messages, Discord, Messenger, then WhatsApp.
        Features.shownServices().forEachIndexed { index, service ->
            tabs.addTab(tabs.newTab().setText(ServiceUi.tabLabel(service)).setTag(service), false)
            if (index == 0 && Features.DISCORD && DiscordProcess.supported) {
                tabs.addTab(tabs.newTab().setText(R.string.chat_service_discord).setTag(DISCORD_TAB), false)
            }
        }
        MessengerTab.add(tabs, selected = true)
        com.lightdecoder.ui.imessage.ImessageTab.add(tabs)
        val messenger = getString(R.string.chat_service_messenger)
        ServiceTabStrip.attachSwitcher(tabs, binding.root.findViewById(R.id.servicesMenu)) { messenger }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val service = tab.tag as? ChatService
                val next = when {
                    // Back to the conversation list underneath, switched to that app in place.
                    service != null -> RelayIntents.conversations(this@MessengerActivity, service)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    tab.tag === DISCORD_TAB -> Intent(this@MessengerActivity, DiscordActivity::class.java)
                    com.lightdecoder.ui.imessage.ImessageTab.isTab(tab) -> com.lightdecoder.ui.imessage.ImessageTab.intent(this@MessengerActivity)
                    else -> return
                }
                ServiceTabStrip.open(this@MessengerActivity, next)
                finish()
                ServiceTabStrip.fadeOnClose(this@MessengerActivity)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) {
                binding.list.smoothScrollToPosition(0)
            }
        })
    }

    /** The cards and banners that depend on settings, not on what is stored. */
    private fun render() {
        val access = MessengerNotificationListener.hasAccess(this)
        binding.setupCard.visibility = if (access) View.GONE else View.VISIBLE
        binding.pausedBanner.visibility = if (access && MessengerPrefs.isPaused(this)) View.VISIBLE else View.GONE
        invalidateOptionsMenu()
    }

    private fun load() {
        val store = MessengerStore.get(this)
        io.execute {
            val conversations = try { store.conversations() } catch (e: Exception) { emptyList() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                adapter.submit(conversations)
                val access = MessengerNotificationListener.hasAccess(this)
                binding.empty.visibility = if (conversations.isEmpty() && access) View.VISIBLE else View.GONE
                binding.empty.setText(
                    if (messengerInstalled()) R.string.messenger_empty else R.string.messenger_empty_no_app
                )
                render()
            }
        }
    }

    private fun messengerInstalled(): Boolean =
        packageManager.getLaunchIntentForPackage(MessengerCapture.MESSENGER_PACKAGE) != null

    /** Android's own notification access screen: only the user can turn it on, and only there. */
    private fun openAccessSettings() {
        val component = ComponentName(this, MessengerNotificationListener::class.java)
        val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        } else {
            null
        }
        for (intent in listOfNotNull(detail, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) {
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // Try the next one.
            } catch (e: SecurityException) {
                // Some phones guard the per-app screen; the list of all listeners is open.
            }
        }
        Toast.makeText(this, R.string.messenger_setup_failed, Toast.LENGTH_LONG).show()
    }

    // --- menu ----------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.messenger_actions, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val access = MessengerNotificationListener.hasAccess(this)
        menu.findItem(R.id.action_messenger_pause)?.apply {
            isVisible = access
            isChecked = MessengerPrefs.isPaused(this@MessengerActivity)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_messenger_open_app -> openApp(this, null)
            R.id.action_messenger_pause -> {
                MessengerPrefs.setPaused(this, !MessengerPrefs.isPaused(this))
                render()
            }
            R.id.action_messenger_clear -> confirmClear()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.messenger_clear_title)
            .setMessage(R.string.messenger_clear_body)
            .setNegativeButton(R.string.messenger_cancel, null)
            .setPositiveButton(R.string.messenger_clear_confirm) { _, _ ->
                val store = MessengerStore.get(this)
                io.execute {
                    runCatching { store.clear() }
                    MessengerReplies.clear()
                    MessengerEvents.notifyChanged()
                }
            }
            .show()
    }

    // --- list ----------------------------------------------------------------

    private inner class ConversationAdapter : RecyclerView.Adapter<ConversationHolder>() {
        private var items: List<MessengerConversation> = emptyList()

        fun submit(next: List<MessengerConversation>) {
            items = next
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationHolder =
            ConversationHolder(ItemMessengerConversationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: ConversationHolder, position: Int) = holder.bind(items[position])
    }

    private inner class ConversationHolder(private val row: ItemMessengerConversationBinding) :
        RecyclerView.ViewHolder(row.root) {

        fun bind(conversation: MessengerConversation) {
            val context = row.root.context
            Avatars.bind(row.avatar, conversation.title, conversation.key, "")
            row.name.text = conversation.title
            row.time.text = TimeFormat.listTime(context, conversation.lastTimestampMs)
            val shown = snippet(context, conversation.lastText, conversation.lastKind)
            row.snippet.text =
                if (conversation.lastFromMe) context.getString(R.string.messenger_you_prefix, shown) else shown
            val unread = conversation.unread > 0
            row.unreadDot.visibility = if (unread) View.VISIBLE else View.GONE
            row.root.contentDescription = listOf(
                if (unread) context.getString(R.string.messenger_unread_description, conversation.title) else conversation.title,
                row.snippet.text, row.time.text,
            ).joinToString(", ")
            row.root.setOnClickListener {
                startActivity(MessengerThreadActivity.intent(context, conversation.key))
            }
        }
    }

    companion object {
        /** The tag of the Discord tab on this screen's strip. */
        private val DISCORD_TAB = Any()

        /** What a message shows as in one line: its text, or "Picture" for a picture without a caption. */
        internal fun snippet(context: android.content.Context, text: String, kind: Int): String =
            if (kind == MessengerCapture.KIND_PICTURE && text.isEmpty()) context.getString(R.string.messenger_picture) else text

        /** Facebook's page, in its own process. Says why not where it cannot be kept apart. */
        internal fun openWeb(activity: AppCompatActivity) {
            if (!MessengerProcess.supported) {
                Toast.makeText(activity, R.string.messenger_web_needs_android9, Toast.LENGTH_LONG).show()
                return
            }
            activity.startActivity(Intent(activity, MessengerWebActivity::class.java))
        }

        internal fun openApp(activity: AppCompatActivity, conversationKey: String?) {
            if (!MessengerLinks.openMessengerApp(activity, conversationKey)) {
                Toast.makeText(activity, R.string.messenger_no_app, Toast.LENGTH_LONG).show()
            }
        }
    }
}
