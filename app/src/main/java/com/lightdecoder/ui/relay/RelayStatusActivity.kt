package com.lightdecoder.ui.relay

import android.content.ActivityNotFoundException
import android.content.Intent
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
import com.lightdecoder.databinding.ActivityRelayStatusBinding
import com.lightdecoder.databinding.DialogUnpairBinding
import com.lightdecoder.relay.BackfillDepth
import com.lightdecoder.relay.BackfillProgress
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.RelayLog
import com.lightdecoder.relay.RelayManager
import com.lightdecoder.relay.RelayPrefs
import com.lightdecoder.relay.RelayState
import com.lightdecoder.ui.InboxActivity
import com.lightdecoder.ui.applySystemBarPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything about the Google Messages link in one place: whether it is
 * connected and why not, the switches that control it, the history import,
 * and the ways out (Disconnect, which keeps the pairing, and Unpair, which
 * removes it from the phone too).
 */
class RelayStatusActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRelayStatusBinding
    private lateinit var manager: RelayManager
    private lateinit var repo: ArchiveRepository
    private val notifications = NotificationPermission(this)

    private var paired = false
    private var health = HealthRow()

    /**
     * Bumped by every render. Each render reads the pairing and the archive
     * on IO, and those reads can finish out of order: only the newest render
     * may write the screen.
     */
    private var renderSeq = 0

    private val onArchiveChange: (ArchiveChange) -> Unit = { change ->
        if (change.kind == ArchiveChange.Kind.HEALTH) refreshHealth()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Sign-in cookies a killed setup screen left behind (once per process; see SignInWebData).
        SignInWebData.wipeIfDirty(this)
        super.onCreate(savedInstanceState)
        binding = ActivityRelayStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarPadding(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        manager = RelayManager.get(this)
        repo = ArchiveRepository.get(this)

        bindSwitches()
        bindBackfill()
        binding.useHere.setOnClickListener { useHere() }
        binding.setUp.setOnClickListener { startActivity(Intent(this, RelaySetupActivity::class.java)) }
        binding.disconnect.setOnClickListener { disconnect() }
        binding.unpair.setOnClickListener { confirmUnpair() }
        binding.turnOnNotifications.setOnClickListener { notifications.openSettings() }
        binding.deleteArchive.setOnClickListener {
            LocalArchive.confirmDelete(this) {
                binding.archiveCard.visibility = View.GONE
                // Supersedes a render still reading the archive from before the delete,
                // which would bring the card back.
                renderState()
            }
        }
        binding.batterySettings.setOnClickListener { openBatterySettings() }
        binding.batteryExempt.setOnClickListener { askBatteryExemption() }
        binding.showLog.setOnClickListener { showLog() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { manager.state.collect { renderState() } }
                launch { manager.backfill.collect { renderBackfill(it) } }
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

    /**
     * Renders the link's current state. The state is read after the IO hop,
     * so the render that writes the screen also shows the newest state.
     */
    private fun renderState() {
        val seq = ++renderSeq
        lifecycleScope.launch {
            val (isPaired, hasArchive) = withContext(Dispatchers.IO) {
                manager.isPaired() to runCatching { LocalArchive.hasAny(repo) }.getOrDefault(false)
            }
            // A newer render began meanwhile (a state change, or the archive deleted): it writes the screen.
            if (seq != renderSeq) return@launch
            val state = manager.state.value
            paired = isPaired
            val phase = if (paired || RelayUi.isProblem(state.phase)) state.phase else RelayState.Phase.UNPAIRED
            // Re-read on every render: this runs again whenever the screen comes back
            // to the front, for example from the notification settings.
            val fix = RelayUi.fix(state, paired, RelayUi.notificationsOn(this@RelayStatusActivity))
            binding.stateTitle.setText(RelayUi.title(phase, fix))
            binding.stateBody.setText(RelayUi.body(phase, fix))
            val reason = state.code.takeIf {
                it.isNotEmpty() && it != RelayState.NOTIFICATIONS_OFF &&
                    phase != RelayState.Phase.CONNECTED && phase != RelayState.Phase.ACTIVE
            }
            binding.stateReason.visibility = if (reason != null) View.VISIBLE else View.GONE
            if (reason != null) binding.stateReason.text = RelayErrors.message(this@RelayStatusActivity, reason)

            binding.useHere.visibility = if (phase == RelayState.Phase.ACTIVE_ELSEWHERE) View.VISIBLE else View.GONE
            binding.setUp.visibility = if (fix == RelayUi.Fix.SET_UP) View.VISIBLE else View.GONE
            binding.turnOnNotifications.visibility = if (fix == RelayUi.Fix.NOTIFICATIONS) View.VISIBLE else View.GONE
            // A lost pairing or rejected sign-in still leaves the stored
            // session (and the archive) behind: Unpair stays offered so the
            // user can wipe them without pairing again first.
            val sessionLeft = paired || phase == RelayState.Phase.LOGGED_OUT || phase == RelayState.Phase.COOKIES_INVALID
            val unpairing = manager.unpairing.value
            binding.disconnect.isEnabled = paired && phase != RelayState.Phase.DISCONNECTED && !unpairing
            binding.unpair.isEnabled = sessionLeft && !unpairing
            binding.controls.visibility = if (sessionLeft) View.VISIBLE else View.GONE
            binding.importCard.visibility = if (paired) View.VISIBLE else View.GONE
            // An archive kept after unpairing can be deleted without pairing again.
            binding.archiveCard.visibility = if (!sessionLeft && hasArchive && !unpairing) View.VISIBLE else View.GONE
            syncSwitches()
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
            health = withContext(Dispatchers.IO) { repo.health() }
            renderHealth()
        }
    }

    private fun renderHealth() {
        binding.healthAccount.text = getString(R.string.link_status_account, health.account.ifEmpty { dash() })
        binding.healthPhone.text = getString(R.string.link_status_phone, health.phoneId.ifEmpty { dash() })
        binding.healthLastEvent.text = getString(R.string.link_status_last_event, ago(health.lastEventTs))
        binding.healthTokenRefresh.text = getString(R.string.link_status_token_refresh, ago(health.lastTokenRefresh))
        val error = health.lastError
        binding.healthLastError.text = getString(
            R.string.link_status_last_error,
            when {
                error.isEmpty() -> dash()
                // Stored errors start with a bridge or state code ("code: detail"); explain the known ones.
                RelayErrors.isKnown(error.substringBefore(":").trim()) ->
                    RelayErrors.message(this, error.substringBefore(":").trim())
                else -> error
            },
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
        binding.switchAutoStart.setOnClickListener { RelayPrefs.setAutoStart(this, binding.switchAutoStart.isChecked) }
        binding.switchNotify.setOnClickListener {
            val on = binding.switchNotify.isChecked
            RelayPrefs.setNotifyMessages(this, on)
            // Only asks; message notifications are optional, so a refusal changes nothing here.
            if (on) notifications.requestIfNeeded()
        }
        binding.switchStickers.setOnClickListener { RelayPrefs.setCatchStickers(this, binding.switchStickers.isChecked) }
    }

    /** From the saved preferences; the switches only change on a tap (click listeners, not change listeners). */
    private fun syncSwitches() {
        binding.switchLive.isChecked = RelayPrefs.liveEnabled(this)
        binding.switchAutoStart.isChecked = RelayPrefs.autoStart(this)
        binding.switchNotify.isChecked = RelayPrefs.notifyMessages(this)
        binding.switchStickers.isChecked = RelayPrefs.catchStickers(this)
    }

    // --- history import ------------------------------------------------------

    private fun bindBackfill() {
        when (RelayPrefs.backfillDepth(this)) {
            BackfillDepth.OFF -> binding.depthOff.isChecked = true
            BackfillDepth.RECENT -> binding.depthRecent.isChecked = true
            BackfillDepth.DEEP -> binding.depthDeep.isChecked = true
        }
        binding.depthGroup.setOnCheckedChangeListener { _, checkedId ->
            RelayPrefs.setBackfillDepth(this, depthFor(checkedId))
        }
        binding.importNow.setOnClickListener {
            val depth = depthFor(binding.depthGroup.checkedRadioButtonId)
            if (depth == BackfillDepth.OFF) {
                Toast.makeText(this, R.string.link_import_pick_depth, Toast.LENGTH_SHORT).show()
            } else {
                runInBackground { manager.startBackfill(depth) }
            }
        }
        binding.importCancel.setOnClickListener { runInBackground { manager.cancelBackfill() } }
    }

    private fun depthFor(checkedId: Int): BackfillDepth = when (checkedId) {
        R.id.depthOff -> BackfillDepth.OFF
        R.id.depthDeep -> BackfillDepth.DEEP
        else -> BackfillDepth.RECENT
    }

    private fun renderBackfill(progress: BackfillProgress) {
        binding.importProgress.visibility = if (progress.running) View.VISIBLE else View.GONE
        binding.importCancel.visibility = if (progress.running) View.VISIBLE else View.GONE
        binding.importNow.isEnabled = !progress.running
        if (progress.conversationsTotal > 0) {
            binding.importProgress.isIndeterminate = false
            binding.importProgress.max = progress.conversationsTotal
            binding.importProgress.setProgressCompat(progress.conversationsDone, true)
        } else {
            binding.importProgress.isIndeterminate = true
        }
        val doneAt = RelayPrefs.backfillDoneAt(this)
        binding.importStatus.text = when {
            progress.running -> getString(
                R.string.link_import_running, progress.conversationsDone, progress.conversationsTotal, progress.messages,
            )
            progress.error.isNotEmpty() -> getString(R.string.link_import_failed, RelayErrors.message(this, progress.error))
            doneAt > 0 -> getString(R.string.link_import_done_at, ago(doneAt))
            else -> getString(R.string.link_import_never)
        }
    }

    // --- actions -------------------------------------------------------------

    private fun useHere() {
        lifecycleScope.launch {
            relayAttempt { manager.useHere() }.onFailure { toast(RelayErrors.message(this@RelayStatusActivity, it)) }
        }
    }

    private fun disconnect() {
        binding.switchLive.isChecked = false
        manager.stopLive()
    }

    private fun confirmUnpair() {
        val dialogBinding = DialogUnpairBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.link_unpair_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.link_unpair_confirm) { _, _ -> unpair(dialogBinding.unpairDeleteArchive.isChecked) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * The unpair itself runs in the manager's scope: leaving or rotating this
     * screen only stops waiting for it, and [RelayManager.unpairing] shows it
     * still running on the screen that comes back.
     */
    private fun unpair(deleteArchive: Boolean) {
        binding.unpair.isEnabled = false
        binding.disconnect.isEnabled = false
        lifecycleScope.launch {
            relayAttempt { manager.unpair(deleteArchive) }
                .onSuccess {
                    if (manager.state.value.code == RelayState.UNPAIR_REMOTE_FAILED) {
                        // Google could not be told: the phone may still list this
                        // device, and only the user can remove it there.
                        showRemoveOnPhone()
                    } else {
                        toast(getString(R.string.link_unpaired))
                        leaveToInbox()
                    }
                }
                .onFailure {
                    renderState()
                    toast(RelayErrors.message(this@RelayStatusActivity, it))
                }
        }
    }

    /**
     * After an unpair that could not reach Google: how to remove LightDecoder
     * on the phone. Leaves for the inbox once read. If the screen is gone by
     * then (rotated, or left during the unpair), the state's reason and the
     * last error on this screen say the same.
     */
    private fun showRemoveOnPhone() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.link_unpair_remote_failed_title)
            .setMessage(R.string.link_unpair_remote_failed_body)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { leaveToInbox() }
            .show()
    }

    private fun leaveToInbox() {
        if (isFinishing || isChangingConfigurations) return
        startActivity(
            Intent(this, InboxActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    private fun renderBattery() {
        val exempt = RelayPrefs.batteryExempt(this)
        binding.batteryText.setText(if (exempt) R.string.link_battery_exempt else R.string.link_battery_optimized)
        binding.batteryExempt.visibility = if (exempt) View.GONE else View.VISIBLE
    }

    /**
     * "Let it run in the background": the system asks the user, in its own
     * dialog, whether to exempt the app. Where a build has no such dialog,
     * its battery settings list instead.
     */
    private fun askBatteryExemption() {
        try {
            startActivity(RelayIntents.batteryExemptionOrSettings(this))
        } catch (e: ActivityNotFoundException) {
            openBatterySettings()
        } catch (e: SecurityException) {
            openBatterySettings()
        }
    }

    /** The system list where the user can exempt the app; no special permission needed for this one. */
    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
            } catch (e2: ActivityNotFoundException) {
                toast(getString(R.string.link_settings_unavailable))
            }
        }
    }

    private fun showLog() {
        lifecycleScope.launch {
            val lines = withContext(Dispatchers.IO) { RelayLog.read(this@RelayStatusActivity) }
            val body = buildString {
                appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine()
                if (lines.isEmpty()) append(getString(R.string.link_log_empty)) else append(lines.joinToString("\n"))
            }
            MaterialAlertDialogBuilder(this@RelayStatusActivity)
                .setTitle(R.string.link_status_log)
                .setMessage(body)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.link_log_clear) { _, _ ->
                    runInBackground { RelayLog.clear(this@RelayStatusActivity) }
                }
                .show()
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
