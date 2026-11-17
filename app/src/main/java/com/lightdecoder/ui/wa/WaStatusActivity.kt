package com.lightdecoder.ui.wa

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveChange
import com.lightdecoder.archive.ArchiveEvents
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.HealthRow
import com.lightdecoder.chat.ChatService
import com.lightdecoder.databinding.ActivityWaStatusBinding
import com.lightdecoder.databinding.DialogWaUnlinkBinding
import com.lightdecoder.relay.RelayException
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.RelayNotifications
import com.lightdecoder.relay.RelayPrefs
import com.lightdecoder.relay.RelayState
import com.lightdecoder.ui.applySystemBarPadding
import com.lightdecoder.ui.chat.ChatErrors
import com.lightdecoder.ui.chat.ConversationsActivity
import com.lightdecoder.ui.chat.ServiceUi
import com.lightdecoder.ui.relay.LocalArchive
import com.lightdecoder.ui.relay.NotificationPermission
import com.lightdecoder.ui.relay.RelayUi
import com.lightdecoder.ui.relay.relayAttempt
import com.lightdecoder.ui.relay.runInBackground
import com.lightdecoder.wa.WaErrors
import com.lightdecoder.wa.WaLog
import com.lightdecoder.wa.WaManager
import com.lightdecoder.wa.WaPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything about the WhatsApp link in one place: whether it is connected
 * and why not, the switches that control it ("stay connected", "start
 * automatically", message notifications), and the ways out: Disconnect,
 * which keeps the link, and Unlink, which removes this device from the
 * account too. Opened with [com.lightdecoder.relay.RelayIntents.waStatus].
 *
 * Its settings are WhatsApp's own ([WaPrefs]): nothing here turns the
 * Google Messages link on or off.
 */
class WaStatusActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWaStatusBinding
    private lateinit var manager: WaManager
    private lateinit var repo: ArchiveRepository
    private val notifications = NotificationPermission(this, ChatService.WHATSAPP)

    private var health = HealthRow()

    /** Bumped by every render; only the newest one may write the screen (its reads can finish out of order). */
    private var renderSeq = 0

    private val onArchiveChange: (ArchiveChange) -> Unit = { change ->
        if (change.service == ChatService.WHATSAPP && change.kind == ArchiveChange.Kind.HEALTH) refreshHealth()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWaStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarPadding(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        manager = WaManager.get(this)
        repo = manager.repo

        bindSwitches()
        binding.setUp.setOnClickListener { startActivity(ServiceUi.setup(this, ChatService.WHATSAPP)) }
        binding.disconnect.setOnClickListener { disconnect() }
        binding.unpair.setOnClickListener { confirmUnlink() }
        binding.turnOnNotifications.setOnClickListener { notifications.openSettings() }
        binding.deleteArchive.setOnClickListener {
            LocalArchive.confirmDelete(this, manager) {
                binding.archiveCard.visibility = View.GONE
                // Supersedes a render still reading the archive from before the delete.
                renderState()
            }
        }
        binding.batterySettings.setOnClickListener { openBatterySettings() }
        binding.batteryExempt.setOnClickListener { askBatteryExemption() }
        binding.showLog.setOnClickListener { showLog() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { manager.state.collect { renderState() } }
                launch { manager.unpairing.collect { renderUnpairing(it) } }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ArchiveEvents.register(onArchiveChange)
        refreshHealth()
        syncSwitches()
    }

    /** Also on coming back from the system's battery question, which only pauses this screen. */
    override fun onResume() {
        super.onResume()
        renderBattery()
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

    // --- state and health ----------------------------------------------------

    private fun renderState() {
        val seq = ++renderSeq
        lifecycleScope.launch {
            val hasArchive = withContext(Dispatchers.IO) { runCatching { LocalArchive.hasAny(repo) }.getOrDefault(false) }
            // A newer render began meanwhile (a state change, or the archive deleted): it writes the screen.
            if (seq != renderSeq) return@launch
            val state = manager.state.value
            val paired = manager.isPaired()
            val phase = if (paired || state.phase == RelayState.Phase.LOGGED_OUT || state.phase == RelayState.Phase.PAIRING) {
                state.phase
            } else {
                RelayState.Phase.UNPAIRED
            }
            // Re-read on every render: this runs again whenever the screen comes back
            // to the front, for example from the notification settings.
            val fix = RelayUi.fix(state, paired, RelayUi.notificationsOn(this@WaStatusActivity, ChatService.WHATSAPP))
            binding.stateTitle.setText(
                if (fix == RelayUi.Fix.NOTIFICATIONS) RelayUi.title(phase, fix) else RelayNotifications.waTitleFor(phase)
            )
            binding.stateBody.setText(body(phase, fix))
            val reason = state.code.takeIf {
                it.isNotEmpty() && it != RelayState.NOTIFICATIONS_OFF && it != WaErrors.CONNECTION_LOST &&
                    phase != RelayState.Phase.CONNECTED && phase != RelayState.Phase.LOGGED_OUT
            }
            binding.stateReason.visibility = if (reason != null) View.VISIBLE else View.GONE
            if (reason != null) binding.stateReason.text = ChatErrors.message(this@WaStatusActivity, ChatService.WHATSAPP, reason)

            binding.setUp.visibility = if (fix == RelayUi.Fix.SET_UP && phase != RelayState.Phase.PAIRING) View.VISIBLE else View.GONE
            binding.turnOnNotifications.visibility = if (fix == RelayUi.Fix.NOTIFICATIONS) View.VISIBLE else View.GONE
            val unpairing = manager.unpairing.value
            binding.disconnect.isEnabled = paired && phase != RelayState.Phase.DISCONNECTED && !unpairing
            binding.unpair.isEnabled = paired && !unpairing
            binding.controls.visibility = if (paired) View.VISIBLE else View.GONE
            // An archive kept after unlinking can be deleted without linking again.
            binding.archiveCard.visibility = if (!paired && hasArchive && !unpairing) View.VISIBLE else View.GONE
            syncSwitches()
            renderHealth()
        }
    }

    private fun body(phase: RelayState.Phase, fix: RelayUi.Fix): Int {
        if (fix == RelayUi.Fix.NOTIFICATIONS) return RelayUi.body(phase, fix)
        return when (phase) {
            RelayState.Phase.DISCONNECTED -> R.string.wa_body_disconnected
            RelayState.Phase.CONNECTED, RelayState.Phase.ACTIVE, RelayState.Phase.ACTIVE_ELSEWHERE -> R.string.wa_body_connected
            RelayState.Phase.RECONNECTING, RelayState.Phase.PHONE_OFFLINE -> R.string.wa_body_reconnecting
            RelayState.Phase.ERROR -> R.string.wa_body_error
            else -> ServiceUi.phaseBody(ChatService.WHATSAPP, phase, fix)
        }
    }

    private fun renderUnpairing(unpairing: Boolean) {
        binding.unpairProgress.visibility = if (unpairing) View.VISIBLE else View.GONE
        if (unpairing) {
            binding.unpair.isEnabled = false
            binding.disconnect.isEnabled = false
        } else {
            renderState()
        }
    }

    private fun refreshHealth() {
        lifecycleScope.launch {
            health = withContext(Dispatchers.IO) { runCatching { repo.health() }.getOrDefault(HealthRow()) }
            renderHealth()
        }
    }

    private fun renderHealth() {
        val linked = manager.isPaired()
        val account = health.account.takeIf { linked && it.isNotEmpty() }?.let { "+$it" }
        binding.healthAccount.text = getString(R.string.wa_status_account, account ?: dash())
        binding.healthDevice.visibility = if (linked) View.VISIBLE else View.GONE
        binding.healthDevice.text = getString(R.string.wa_status_device, WaManager.DEVICE_NAME)
        binding.healthLastEvent.text = getString(R.string.wa_status_last_event, ago(health.lastEventTs))
        // Stored errors are bridge or state codes; anything else is not shown as it is.
        val error = health.lastError.substringBefore(":").trim()
        binding.healthLastError.text = getString(
            R.string.link_status_last_error,
            if (error.isEmpty()) dash() else ChatErrors.message(this, ChatService.WHATSAPP, error),
        )
    }

    private fun ago(timestampMs: Long): CharSequence =
        if (timestampMs <= 0) getString(R.string.link_status_never)
        else DateUtils.getRelativeTimeSpanString(timestampMs, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS)

    private fun dash() = getString(R.string.link_status_unknown)

    // --- switches ------------------------------------------------------------

    private fun bindSwitches() {
        binding.switchLive.setOnClickListener {
            // startLive and stopLive are cheap and main-thread safe; calling them
            // directly keeps quick on/off taps in order.
            if (binding.switchLive.isChecked) {
                // Never without its notification: if it cannot be shown, the switch goes back off.
                notifications.ensureThen(onRefused = { syncSwitches() }) {
                    manager.startLive()
                    syncSwitches()
                }
            } else {
                manager.stopLive()
            }
        }
        binding.switchAutoStart.setOnClickListener { WaPrefs.setAutoStart(this, binding.switchAutoStart.isChecked) }
        binding.switchNotify.setOnClickListener {
            val on = binding.switchNotify.isChecked
            WaPrefs.setNotifyMessages(this, on)
            // Only asks; message notifications are optional, so a refusal changes nothing here.
            if (on) notifications.requestIfNeeded()
        }
        com.lightdecoder.ui.relay.ReadNotifySection.bind(this, binding.readNotify.root, com.lightdecoder.chat.ChatService.WHATSAPP) {
            notifications.requestIfNeeded()
        }
    }

    /** From the saved preferences; the switches only change on a tap (click listeners, not change listeners). */
    private fun syncSwitches() {
        binding.switchLive.isChecked = WaPrefs.liveEnabled(this)
        binding.switchAutoStart.isChecked = WaPrefs.autoStart(this)
        binding.switchNotify.isChecked = WaPrefs.notifyMessages(this)
    }

    // --- actions -------------------------------------------------------------

    private fun disconnect() {
        binding.switchLive.isChecked = false
        manager.stopLive()
    }

    private fun confirmUnlink() {
        val dialogBinding = DialogWaUnlinkBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wa_unlink_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.wa_unlink) { _, _ -> unlink(dialogBinding.unpairDeleteArchive.isChecked) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * The unlink itself runs in the manager's scope: leaving or rotating this
     * screen only stops waiting for it, and [WaManager.unpairing] shows it
     * still running on the screen that comes back.
     */
    private fun unlink(deleteArchive: Boolean) {
        binding.unpair.isEnabled = false
        binding.disconnect.isEnabled = false
        lifecycleScope.launch {
            relayAttempt { manager.unpair(deleteArchive) }
                .onSuccess {
                    if (manager.state.value.code == RelayState.UNPAIR_REMOTE_FAILED) {
                        // WhatsApp could not be told: the phone may still list this
                        // device, and only the user can remove it there.
                        showRemoveOnPhone()
                    } else {
                        toast(getString(R.string.wa_unlinked))
                        leaveToChats()
                    }
                }
                .onFailure {
                    renderState()
                    toast(ChatErrors.message(this@WaStatusActivity, ChatService.WHATSAPP, it))
                }
        }
    }

    private fun showRemoveOnPhone() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wa_unlink_remote_failed_title)
            .setMessage(ChatErrors.message(this, ChatService.WHATSAPP, RelayException.UNPAIR_REMOTE_FAILED))
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { leaveToChats() }
            .show()
    }

    /** Back to WhatsApp's tab of the conversation list, which now offers to link again. */
    private fun leaveToChats() {
        if (isFinishing || isChangingConfigurations) return
        startActivity(
            Intent(this, ConversationsActivity::class.java)
                .putExtra(RelayIntents.EXTRA_SERVICE, ChatService.WHATSAPP.key)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    private fun renderBattery() {
        val exempt = RelayPrefs.batteryExempt(this)
        binding.batteryText.setText(if (exempt) R.string.link_battery_exempt else R.string.link_battery_optimized)
        binding.batteryExempt.visibility = if (exempt) View.GONE else View.VISIBLE
    }

    /** The system asks the user, in its own dialog, whether to exempt the app; its battery settings list where there is none. */
    private fun askBatteryExemption() {
        try {
            startActivity(RelayIntents.batteryExemptionOrSettings(this))
        } catch (e: ActivityNotFoundException) {
            openBatterySettings()
        } catch (e: SecurityException) {
            openBatterySettings()
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (e2: ActivityNotFoundException) {
                toast(getString(R.string.link_settings_unavailable))
            }
        }
    }

    private fun showLog() {
        lifecycleScope.launch {
            val lines = withContext(Dispatchers.IO) { WaLog.read(this@WaStatusActivity) }
            val body = buildString {
                appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine()
                if (lines.isEmpty()) append(getString(R.string.wa_log_empty)) else append(lines.joinToString("\n"))
            }
            MaterialAlertDialogBuilder(this@WaStatusActivity)
                .setTitle(R.string.link_status_log)
                .setMessage(body)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.link_log_clear) { _, _ ->
                    runInBackground { WaLog.clear(this@WaStatusActivity) }
                }
                .show()
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
