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
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.databinding.ActivityInboxBinding
import com.lightdecoder.inbox.InboxEvents
import com.lightdecoder.inbox.InboxItem
import com.lightdecoder.inbox.InboxLog
import com.lightdecoder.inbox.InboxPrefs
import com.lightdecoder.inbox.InboxStore
import com.lightdecoder.relay.RelayManager
import com.lightdecoder.relay.RelayState
import com.lightdecoder.ui.chat.ConversationsActivity
import com.lightdecoder.ui.relay.RelayErrors
import com.lightdecoder.ui.relay.RelaySetupActivity
import com.lightdecoder.ui.relay.RelayStatusActivity
import com.lightdecoder.ui.relay.RelayUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The home screen: stickers caught from message notifications, and the switch
 * that turns catching on. Nothing is read until the user turns on notification
 * access for LightDecoder in Android's settings. A second card leads to the
 * Google Messages link (set it up, open the messages, or fix it).
 */
class InboxActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInboxBinding
    private lateinit var store: InboxStore
    private lateinit var adapter: InboxAdapter
    private val refresh = Runnable { refreshItems() }
    private val relay by lazy { RelayManager.get(this) }
    private var relayState: RelayState? = null
    private var relayPaired = false
    private val onArchiveChange: (ArchiveChange) -> Unit = { change ->
        if (change.kind == ArchiveChange.Kind.CONVERSATIONS) renderRelayCard()
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
    private val askToPostNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { openNotificationAccessSettings() }

    override fun onCreate(savedInstanceState: Bundle?) {
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

        binding.statusAction.setOnClickListener { onStatusAction() }
        binding.statusHelp.setOnClickListener { showGreyedOutHelp() }

        binding.relayAction.setOnClickListener { onRelayAction() }
        binding.relaySettings.setOnClickListener { startActivity(Intent(this, RelayStatusActivity::class.java)) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                relay.state.collect {
                    relayState = it
                    renderRelayCard()
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
        refreshStatus()
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

    // --- status card ---------------------------------------------------------

    private fun refreshStatus() {
        val granted = InboxPrefs.hasNotificationAccess(this)
        val paused = InboxPrefs.isPaused(this)
        when {
            !granted -> {
                binding.statusTitle.setText(R.string.status_off_title)
                binding.statusBody.setText(R.string.status_off_body)
                binding.statusAction.setText(R.string.status_turn_on)
                binding.statusHelp.visibility = View.VISIBLE
            }
            paused -> {
                binding.statusTitle.setText(R.string.status_paused_title)
                binding.statusBody.setText(R.string.status_paused_body)
                binding.statusAction.setText(R.string.status_resume)
                binding.statusHelp.visibility = View.GONE
            }
            else -> {
                binding.statusTitle.setText(R.string.status_on_title)
                binding.statusBody.setText(
                    if (InboxPrefs.catchAllImages(this)) R.string.status_on_body_all else R.string.status_on_body
                )
                binding.statusAction.setText(R.string.status_pause)
                binding.statusHelp.visibility = View.GONE
            }
        }
    }

    private fun onStatusAction() {
        when {
            !InboxPrefs.hasNotificationAccess(this) -> {
                val needsAsk = Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                if (needsAsk) {
                    askToPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openNotificationAccessSettings()
                }
            }
            else -> {
                InboxPrefs.setPaused(this, !InboxPrefs.isPaused(this))
                refreshStatus()
            }
        }
    }

    private fun openNotificationAccessSettings() {
        val detail = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                InboxPrefs.listenerComponent(this).flattenToString(),
            )
        } else {
            null
        }
        try {
            startActivity(detail ?: Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e2: ActivityNotFoundException) {
                showMessage(getString(R.string.settings_unavailable))
            }
        }
    }

    /** Android blocks this switch for apps installed from a file until the user allows it. */
    private fun showGreyedOutHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.greyed_title)
            .setMessage(R.string.greyed_body)
            .setPositiveButton(R.string.greyed_open_app_info) { _, _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    )
                } catch (e: ActivityNotFoundException) {
                    showMessage(getString(R.string.settings_unavailable))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- Google Messages link card ---------------------------------------------

    private fun renderRelayCard() {
        val state = relayState ?: return
        lifecycleScope.launch {
            val (paired, unread) = withContext(Dispatchers.IO) {
                relay.isPaired() to ArchiveRepository.get(this@InboxActivity).unreadCount()
            }
            relayPaired = paired
            val problem = RelayUi.isProblem(state.phase)
            when {
                !paired && !problem -> {
                    binding.relayBody.setText(R.string.link_card_not_set_up)
                    binding.relayAction.setText(R.string.link_card_set_up)
                }
                problem -> {
                    val reason = if (state.code.isNotEmpty()) RelayErrors.message(this@InboxActivity, state.code)
                    else getString(RelayUi.body(state.phase))
                    binding.relayBody.text = getString(RelayUi.title(state.phase)) + "\n" + reason
                    binding.relayAction.setText(R.string.link_card_fix)
                }
                state.isOnline -> {
                    binding.relayBody.text = if (unread > 0) getString(R.string.link_card_connected_unread, unread)
                    else getString(R.string.link_card_connected)
                    binding.relayAction.setText(R.string.link_card_open)
                }
                else -> {
                    binding.relayBody.setText(RelayUi.title(state.phase))
                    binding.relayAction.setText(R.string.link_card_open)
                }
            }
            binding.relaySettings.visibility = if (paired || problem) View.VISIBLE else View.GONE
        }
    }

    private fun onRelayAction() {
        val state = relayState
        val target = when {
            state != null && RelayUi.isProblem(state.phase) ->
                if (RelayUi.fix(state, relayPaired) == RelayUi.Fix.SET_UP) RelaySetupActivity::class.java
                else RelayStatusActivity::class.java
            !relayPaired -> RelaySetupActivity::class.java
            else -> ConversationsActivity::class.java
        }
        startActivity(Intent(this, target))
    }

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
        menu.findItem(R.id.action_all_images).isChecked = InboxPrefs.catchAllImages(this)
        menu.findItem(R.id.action_alerts).isChecked = InboxPrefs.alertsEnabled(this)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_messages -> startActivity(Intent(this, ConversationsActivity::class.java))
            R.id.action_open -> openDocument.launch(arrayOf("image/*", "*/*"))
            R.id.action_all_images -> {
                InboxPrefs.setCatchAllImages(this, !InboxPrefs.catchAllImages(this))
                refreshStatus()
            }
            R.id.action_alerts -> InboxPrefs.setAlertsEnabled(this, !InboxPrefs.alertsEnabled(this))
            R.id.action_log -> showLog()
            R.id.action_clear -> confirmClear()
            R.id.action_about -> showMessage(getString(R.string.about_body))
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
