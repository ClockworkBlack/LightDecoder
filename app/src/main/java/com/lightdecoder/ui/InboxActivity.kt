package com.lightdecoder.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveChange
import com.lightdecoder.archive.ArchiveEvents
import com.lightdecoder.chat.ChatBackend
import com.lightdecoder.chat.ChatService
import com.lightdecoder.databinding.ActivityInboxBinding
import com.lightdecoder.inbox.InboxEvents
import com.lightdecoder.inbox.InboxItem
import com.lightdecoder.inbox.InboxLog
import com.lightdecoder.inbox.InboxPrefs
import com.lightdecoder.inbox.InboxStore
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.RelayState
import com.lightdecoder.ui.chat.ChatErrors
import com.lightdecoder.ui.chat.ConversationsActivity
import com.lightdecoder.ui.chat.ScreenBackends
import com.lightdecoder.ui.chat.ServiceUi
import com.lightdecoder.ui.relay.RelayUi
import com.lightdecoder.ui.relay.SignInWebData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The home screen: the sticker inbox (stickers opened, shared or decoded from
 * the links). Catching stickers from other apps' notifications is switched
 * off: the notification listener is not declared, so Android never offers
 * notification access for LightDecoder. A card leads to the
 * two links, Google Messages and WhatsApp: for each, set it up, open its
 * messages, or fix it.
 */
class InboxActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInboxBinding
    private lateinit var store: InboxStore
    private lateinit var adapter: InboxAdapter
    private val refresh = Runnable { refreshItems() }
    private lateinit var links: List<LinkBlock>
    private val onArchiveChange: (ArchiveChange) -> Unit = { change ->
        if (change.kind == ArchiveChange.Kind.CONVERSATIONS) links.firstOrNull { it.service == change.service }?.render()
    }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            startActivity(
                Intent(this, ViewerActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
    }

    /** Asked once, just before sending the user to the notification-access screen. */
    override fun onCreate(savedInstanceState: Bundle?) {
        // Sign-in cookies a killed setup screen left behind (once per process; see SignInWebData).
        SignInWebData.wipeIfDirty(this)
        super.onCreate(savedInstanceState)
        binding = ActivityInboxBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarPadding(binding.root)
        setSupportActionBar(binding.toolbar)

        store = InboxStore.get(this)
        adapter = InboxAdapter(
            store,
            onOpen = { startActivity(ViewerActivity.intentForInboxItem(this, it.id)) },
            onLongPress = { confirmDelete(it) },
        )
        val columns = (resources.configuration.screenWidthDp / 120).coerceIn(3, 8)
        binding.grid.layoutManager = GridLayoutManager(this, columns)
        binding.grid.adapter = adapter


        links = listOf(
            LinkBlock(ChatService.MESSAGES, binding.relayBody, binding.relayAction, binding.relaySettings),
            LinkBlock(ChatService.WHATSAPP, binding.waBody, binding.waAction, binding.waSettings),
        )
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                for (link in links) {
                    launch {
                        link.backend.state.collect {
                            link.state = it
                            link.render()
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ArchiveEvents.register(onArchiveChange)
    }

    override fun onStop() {
        ArchiveEvents.unregister(onArchiveChange)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        InboxEvents.register(refresh)
        refreshItems()
    }

    override fun onPause() {
        InboxEvents.unregister(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        adapter.shutdown()
        super.onDestroy()
    }

    // --- the links' card -------------------------------------------------------

    /**
     * One link's part of the card: what state it is in, the one thing to do
     * about it (set it up, fix it, or open its messages), and its settings
     * once there is something to set.
     */
    private inner class LinkBlock(
        val service: ChatService,
        private val body: TextView,
        private val action: TextView,
        private val settings: View,
    ) {
        val backend: ChatBackend = ScreenBackends.get(this@InboxActivity, service)
        var state: RelayState? = null
        private var paired = false

        init {
            action.setOnClickListener { act() }
            settings.setOnClickListener { startActivity(ServiceUi.status(this@InboxActivity, service)) }
        }

        fun render() {
            val state = state ?: return
            lifecycleScope.launch {
                val (isPaired, unread) = withContext(Dispatchers.IO) { backend.isPaired() to backend.repo.unreadCount() }
                paired = isPaired
                val problem = RelayUi.isProblem(state.phase)
                when {
                    !paired && !problem -> {
                        // Right after an unpair that could not reach the service, say how
                        // to remove LightDecoder on the phone instead of "not set up".
                        if (state.code == RelayState.UNPAIR_REMOTE_FAILED) {
                            body.text = ChatErrors.message(this@InboxActivity, service, state.code)
                        } else {
                            body.setText(ServiceUi.linkCardBody(service))
                        }
                        action.setText(R.string.link_card_set_up)
                    }
                    problem -> {
                        val reason = if (state.code.isNotEmpty()) ChatErrors.message(this@InboxActivity, service, state.code)
                        else getString(ServiceUi.phaseBody(service, state.phase, RelayUi.Fix.NONE))
                        body.text = getString(RelayUi.title(state.phase)) + "\n" + reason
                        action.setText(R.string.link_card_fix)
                    }
                    state.isOnline -> {
                        body.text = if (unread > 0) getString(R.string.link_card_connected_unread, unread)
                        else getString(R.string.link_card_connected)
                        action.setText(openLabel())
                    }
                    else -> {
                        // "Notifications are off" rather than just "Disconnected": the link
                        // never runs without its notification (the conversations' banner
                        // then offers the settings).
                        val fix = RelayUi.fix(state, paired, RelayUi.notificationsOn(this@InboxActivity, service))
                        body.setText(RelayUi.title(state.phase, fix))
                        action.setText(openLabel())
                    }
                }
                settings.visibility = if (paired || problem) View.VISIBLE else View.GONE
            }
        }

        private fun openLabel(): Int = when (service) {
            ChatService.MESSAGES -> R.string.link_card_open
            ChatService.WHATSAPP -> R.string.chat_wa_card_open
        }

        private fun act() {
            val state = state
            val context = this@InboxActivity
            startActivity(
                when {
                    state != null && RelayUi.isProblem(state.phase) ->
                        if (RelayUi.fix(state, paired) == RelayUi.Fix.SET_UP) ServiceUi.setup(context, service)
                        else ServiceUi.status(context, service)
                    !paired -> ServiceUi.setup(context, service)
                    else -> conversations(service)
                }
            )
        }
    }

    /** The conversation list, on [service]'s tab. */
    private fun conversations(service: ChatService): Intent =
        Intent(this, ConversationsActivity::class.java).putExtra(RelayIntents.EXTRA_SERVICE, service.key)

    // --- list ----------------------------------------------------------------

    private fun refreshItems() {
        val items = store.list()
        adapter.submit(items)
        binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.grid.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun confirmDelete(item: InboxItem) {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_one_question)
            .setPositiveButton(R.string.delete) { _, _ ->
                store.delete(item)
                refreshItems()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- menu ----------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.inbox_actions, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            // Wherever the list was last: it remembers its tab.
            R.id.action_messages -> startActivity(Intent(this, ConversationsActivity::class.java))
            R.id.action_open -> openDocument.launch(arrayOf("image/*", "*/*"))
            R.id.action_log -> showLog()
            R.id.action_clear -> confirmClear()
            R.id.action_about -> showAbout()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun showLog() {
        val lines = InboxLog.read(this)
        val access = getString(
            if (InboxPrefs.hasNotificationAccess(this)) R.string.log_access_on else R.string.log_access_off
        )
        val body = buildString {
            appendLine(access)
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            if (lines.isEmpty()) append(getString(R.string.log_empty)) else append(lines.joinToString("\n"))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_log)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.log_clear) { _, _ -> InboxLog.clear(this) }
            .show()
    }

    /** What the app is and what it is built from; Licences shows the full texts. */
    private fun showAbout() {
        AlertDialog.Builder(this)
            .setMessage(R.string.about_body)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.action_licenses) { _, _ ->
                startActivity(Intent(this, LicensesActivity::class.java))
            }
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage(R.string.clear_question)
            .setPositiveButton(R.string.delete) { _, _ ->
                store.clear()
                refreshItems()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showMessage(message: String) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
