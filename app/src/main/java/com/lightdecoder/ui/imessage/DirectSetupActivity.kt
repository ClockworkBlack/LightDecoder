package com.lightdecoder.ui.imessage

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lightdecoder.R
import com.lightdecoder.databinding.ActivityImessageBinding
import com.lightdecoder.imessage.direct.Handles
import com.lightdecoder.imessage.direct.DirectEngine
import com.lightdecoder.imessage.direct.DirectEngine.Status
import com.lightdecoder.imessage.direct.DirectLibrary
import com.lightdecoder.imessage.direct.DirectPrefs
import com.lightdecoder.imessage.direct.RelayHost
import com.lightdecoder.imessage.direct.ServiceNotice
import com.lightdecoder.imessage.direct.SmsPhoneVerifier
import com.lightdecoder.imessage.madrid.MadridApps
import com.lightdecoder.imessage.madrid.MadridHost
import com.lightdecoder.ui.applySystemBarPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * iMessage setup, from the Messages tab's menu (docs/imessage-openbubbles.md): import OpenBubbles'
 * files, then register this phone's number with the iPhone relay's code. The status is green
 * only once Apple reports the registration. iMessage conversations are then in the Messages tab.
 * Runs in the ":imessage" process, with the library's own settings (read receipts), and "iMessage
 * games" (a file the main process reads: [DirectPrefs.gamesOn]) with whether OpenPigeon is here.
 * And FaceTime's: "FaceTime" ([DirectPrefs.faceTimeOn]) and "Silence unknown FaceTime callers"
 * ([DirectPrefs.silenceUnknownFaceTime]), with what Android still has to allow for a call to ring.
 */
class DirectSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImessageBinding
    private val engine by lazy { DirectEngine.get(this) }
    private val prefs by lazy { DirectPrefs.prefs(this) }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) { uris.map { DirectLibrary.import(this@DirectSetupActivity, it) } }
            val lines = results.map {
                when (it) {
                    // Only the pinned builds are ever kept (DirectLibrary.classify).
                    is DirectLibrary.ImportResult.Installed -> getString(R.string.imessage_import_ok, it.name)
                    is DirectLibrary.ImportResult.Refused -> it.reason
                }
            }
            MaterialAlertDialogBuilder(this@DirectSetupActivity)
                .setTitle(R.string.imessage_import_result)
                .setMessage(lines.joinToString("\n\n"))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            showLibrary()
            engine.start()
        }
    }

    private val askSms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) startRegistration() else showError(getString(R.string.imessage_sms_needed))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImessageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarPadding(binding.root)
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.relayHost.setText(prefs.getString(DirectPrefs.KEY_HOST, DirectEngine.DEFAULT_RELAY_HOST))
        binding.relayCode.setText(prefs.getString(DirectPrefs.KEY_CODE, ""))
        binding.importLibrary.setOnClickListener { pickFiles.launch(arrayOf("*/*")) }
        binding.register.setOnClickListener { onRegister() }
        binding.pasteReply.setOnClickListener { askForReply() }
        // Off by default, as on an iPhone; the library reads it for every read receipt.
        binding.switchReadReceipts.isChecked = DirectPrefs.sendReadReceipts(this)
        binding.switchReadReceipts.setOnCheckedChangeListener { _, on -> DirectPrefs.setSendReadReceipts(this, on) }
        // On by default; the chat screen and the games host (main process) read it.
        binding.switchGames.isChecked = DirectPrefs.gamesOn(this)
        binding.switchGames.setOnCheckedChangeListener { _, on ->
            DirectPrefs.setGamesOn(this, on)
            showGames()
        }
        // On by default; the chat screen (main process) reads it.
        binding.switchPhoneStyle.isChecked = DirectPrefs.phoneStyleOn(this)
        binding.switchPhoneStyle.setOnCheckedChangeListener { _, on -> DirectPrefs.setPhoneStyleOn(this, on) }
        // Off by default; the chat screen (main process) reads it when it comes back in front.
        binding.switchReplyThreads.isChecked = DirectPrefs.replyThreadsInChat(this)
        binding.switchReplyThreads.setOnCheckedChangeListener { _, on -> DirectPrefs.setReplyThreadsInChat(this, on) }
        // Read in the main process (it fetches the page, and posts the notifications): files, as above.
        binding.switchLinkPreviews.isChecked = DirectPrefs.linkPreviews(this)
        binding.switchLinkPreviews.setOnCheckedChangeListener { _, on -> DirectPrefs.setLinkPreviews(this, on) }
        binding.switchNotifyMentions.isChecked = DirectPrefs.notifyWhenMentioned(this)
        binding.switchNotifyMentions.setOnCheckedChangeListener { _, on -> DirectPrefs.setNotifyWhenMentioned(this, on) }
        // FaceTime: on by default; the call engine (this process) and the chat screens (main process) read it.
        binding.switchFaceTime.isChecked = DirectPrefs.faceTimeOn(this)
        binding.switchFaceTime.setOnCheckedChangeListener { _, on ->
            DirectPrefs.setFaceTimeOn(this, on)
            // Turned on: from now on the library is told this phone's addresses (a call needs them).
            engine.faceTimeSettingChanged()
            showFaceTime()
        }
        // Off by default: an unknown caller rings like anyone else.
        binding.switchSilenceUnknown.isChecked = DirectPrefs.silenceUnknownFaceTime(this)
        binding.switchSilenceUnknown.setOnCheckedChangeListener { _, on -> DirectPrefs.setSilenceUnknownFaceTime(this, on) }
        binding.faceTimeFix.setOnClickListener { fixFaceTime() }
        binding.getGames.setOnClickListener { MadridHost.openStore(this, MadridApps.OPENPIGEON) }
        // The service's status notice: turned off in its channel's settings, iMessage keeps running.
        binding.hideStatus.setOnClickListener { openStatusSettings() }

        showLibrary()
        engine.start()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                engine.status.collect { showStatus(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the store, maybe with OpenPigeon installed.
        showGames()
        // Back from Android's settings, maybe with notifications or the call screen allowed.
        showFaceTime()
    }

    /** What keeps an incoming FaceTime call from ringing as it should, if anything; nothing while FaceTime is off. */
    private enum class FaceTimeBlock { NONE, NOTIFICATIONS, FULL_SCREEN }

    private fun faceTimeBlock(): FaceTimeBlock {
        if (!DirectPrefs.faceTimeOn(this)) return FaceTimeBlock.NONE
        if (!androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()) return FaceTimeBlock.NOTIFICATIONS
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            // Not to be asked on every phone: then nothing is said of it.
            val allowed = try {
                nm == null || nm.canUseFullScreenIntent()
            } catch (e: RuntimeException) {
                true
            }
            if (!allowed) return FaceTimeBlock.FULL_SCREEN
        }
        return FaceTimeBlock.NONE
    }

    /** The FaceTime switches as they stand, and what Android still has to allow for a call to ring (with a way there). */
    private fun showFaceTime() {
        val on = DirectPrefs.faceTimeOn(this)
        binding.switchSilenceUnknown.isEnabled = on
        binding.silenceUnknownHelp.alpha = if (on) 1f else 0.5f
        val block = faceTimeBlock()
        binding.faceTimeStatus.visibility = if (block == FaceTimeBlock.NONE) View.GONE else View.VISIBLE
        binding.faceTimeFix.visibility = binding.faceTimeStatus.visibility
        when (block) {
            FaceTimeBlock.NOTIFICATIONS -> binding.faceTimeStatus.setText(R.string.facetime_notifications_off)
            FaceTimeBlock.FULL_SCREEN -> binding.faceTimeStatus.setText(R.string.facetime_full_screen_help)
            FaceTimeBlock.NONE -> Unit
        }
    }

    /** Android's own screen for what is missing: the app's notifications, or (Android 14) its full-screen notifications. */
    private fun fixFaceTime() {
        val intent = when (faceTimeBlock()) {
            FaceTimeBlock.NOTIFICATIONS -> android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
            FaceTimeBlock.FULL_SCREEN ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.fromParts("package", packageName, null))
                } else {
                    return
                }
            FaceTimeBlock.NONE -> return
        }
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            showError(getString(R.string.imessage_hide_status_help))
        }
    }

    private fun openStatusSettings() {
        try {
            startActivity(ServiceNotice.channelSettingsIntent(this))
        } catch (e: android.content.ActivityNotFoundException) {
            showError(getString(R.string.imessage_hide_status_help))
        }
    }

    /** Whether games can be played: OpenPigeon installed (and the real one), with "Get games" without it. Nothing while games are off. */
    private fun showGames() {
        val ext = MadridApps.OPENPIGEON
        if (!binding.switchGames.isChecked) {
            binding.gamesStatus.visibility = View.GONE
            binding.getGames.visibility = View.GONE
            return
        }
        val status = MadridApps.status(packageManager, ext)
        binding.gamesStatus.visibility = View.VISIBLE
        binding.gamesStatus.text = when (status) {
            MadridApps.Status.NOT_INSTALLED -> getString(R.string.imessage_games_not_installed, ext.label)
            MadridApps.Status.VERIFIED -> getString(R.string.imessage_games_ready)
            MadridApps.Status.UNVERIFIED -> getString(R.string.imessage_app_unverified, ext.label)
        }
        binding.getGames.visibility = if (status == MadridApps.Status.NOT_INSTALLED) View.VISIBLE else View.GONE
    }

    private fun showLibrary() {
        if (DirectLibrary.isBundled(this)) {
            // This build carries OpenBubbles' files: nothing to import.
            binding.libraryBody.text = getString(R.string.imessage_library_bundled, DirectLibrary.processAbi())
            binding.importLibrary.visibility = android.view.View.GONE
            return
        }
        val unpinned = DirectLibrary.unpinned(DirectLibrary.processAbi())
        if (unpinned.isNotEmpty()) {
            // No pinned build of a file for this CPU type: nothing that could be imported would be loaded.
            binding.libraryBody.text = unsupportedText(unpinned)
            binding.importLibrary.visibility = android.view.View.GONE
            return
        }
        val missing = DirectLibrary.missing(this)
        binding.libraryBody.text = if (missing.isEmpty()) {
            getString(R.string.imessage_library_ready, DirectLibrary.processAbi())
        } else {
            getString(R.string.imessage_library_missing, missing.joinToString(", "), DirectLibrary.processAbi())
        }
        binding.importLibrary.setText(if (missing.isEmpty()) R.string.imessage_library_reimport else R.string.imessage_library_import)
    }

    /** The library cannot be used on this CPU type: [unpinned] have no pinned build for it. */
    private fun unsupportedText(unpinned: List<String>): String =
        getString(R.string.imessage_library_unsupported, DirectLibrary.processAbi(), unpinned.joinToString(", "))

    private fun onRegister() {
        // Registering again replaces the session a call is on: not during a call.
        if (com.lightdecoder.imessage.facetime.FaceTimeCalls.peek()?.busy == true) {
            showError(getString(R.string.facetime_register_in_call))
            return
        }
        val code = binding.relayCode.text?.toString().orEmpty().trim()
        if (code.isEmpty()) {
            showError(getString(R.string.imessage_relay_code_missing))
            return
        }
        // Only an https:// address of a real server, with nothing secret in it (RelayHost).
        val host = RelayHost.check(binding.relayHost.text?.toString().orEmpty().ifBlank { DirectEngine.DEFAULT_RELAY_HOST })
        if (host.value == null) {
            val why = getString(RelayHost.messageRes(host.problem))
            binding.relayHost.error = why
            showError(why)
            return
        }
        binding.relayHost.error = null
        prefs.edit()
            .putString(DirectPrefs.KEY_CODE, code)
            .putString(DirectPrefs.KEY_HOST, host.value)
            .apply()
        val needed = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startRegistration()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.imessage_sms_title)
                .setMessage(R.string.imessage_sms_explain)
                .setPositiveButton(R.string.imessage_continue) { _, _ -> askSms.launch(needed) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun startRegistration() {
        // Checked again by the engine: a stored address from before this check is refused there too.
        engine.register(
            prefs.getString(DirectPrefs.KEY_CODE, "").orEmpty(),
            prefs.getString(DirectPrefs.KEY_HOST, DirectEngine.DEFAULT_RELAY_HOST).orEmpty(),
            SmsPhoneVerifier(applicationContext),
        )
    }

    private fun showStatus(status: Status) {
        val (color, title, body) = when (status) {
            is Status.LibraryMissing -> Triple(
                GREY, getString(R.string.imessage_status_no_library),
                DirectLibrary.unpinned(DirectLibrary.processAbi()).takeIf { it.isNotEmpty() }?.let(::unsupportedText)
                    ?: getString(R.string.imessage_status_no_library_body),
            )
            is Status.NotRegistered -> Triple(GREY, getString(R.string.imessage_status_not_registered), getString(R.string.imessage_status_not_registered_body))
            is Status.Working -> Triple(AMBER, status.step + "…", getString(R.string.imessage_status_working_body))
            is Status.Registered -> Triple(GREEN, getString(R.string.imessage_status_registered), status.handles.joinToString(", ") { Handles.display(it) })
            // Apple's library tries again by itself: not "not working".
            is Status.Retrying -> Triple(
                AMBER, getString(R.string.imessage_status_retrying),
                getString(R.string.imessage_status_retrying_body, status.error, status.retryWaitSec.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()),
            )
            is Status.Failed -> Triple(RED, getString(R.string.imessage_status_failed), status.error)
        }
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(color)
        binding.statusTitle.text = title
        binding.statusBody.text = body
        binding.statusProgress.visibility = if (status is Status.Working) View.VISIBLE else View.GONE
        // While Apple's reply text is awaited: it can be pasted if LightDecoder does not catch it.
        binding.pasteReply.visibility = if (status is Status.Working && "SMS" in status.step) View.VISIBLE else View.GONE
        val working = status is Status.Working
        binding.register.isEnabled = !working && status !is Status.LibraryMissing
        binding.importLibrary.isEnabled = !working
        binding.registerCard.alpha = if (status is Status.LibraryMissing) 0.5f else 1f
    }

    /** Apple's REG-RESP text, copied by the user from their messages app. */
    private fun askForReply() {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.imessage_paste_reply_hint)
            minLines = 3
        }
        val clip = (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (clip != null && "REG-RESP" in clip) input.setText(clip)
        val box = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.imessage_paste_reply)
            .setMessage(R.string.imessage_paste_reply_body)
            .setView(box)
            .setPositiveButton(R.string.imessage_continue) { _, _ ->
                when (SmsPhoneVerifier.paste(input.text?.toString().orEmpty())) {
                    SmsPhoneVerifier.Companion.Paste.TAKEN -> Unit
                    SmsPhoneVerifier.Companion.Paste.NOT_WAITING -> showError(getString(R.string.imessage_paste_not_waiting))
                    SmsPhoneVerifier.Companion.Paste.NOT_A_REPLY -> showError(getString(R.string.imessage_paste_not_reply))
                    SmsPhoneVerifier.Companion.Paste.OTHER_REQUEST -> showError(getString(R.string.imessage_paste_other_request))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        private val GREEN = Color.rgb(0x2E, 0x7D, 0x32)
        private val AMBER = Color.rgb(0xF9, 0xA8, 0x25)
        private val RED = Color.rgb(0xC6, 0x28, 0x28)
        private val GREY = Color.rgb(0x9E, 0x9E, 0x9E)
    }
}
