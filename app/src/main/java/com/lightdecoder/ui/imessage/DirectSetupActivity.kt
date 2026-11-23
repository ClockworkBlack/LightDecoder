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
import com.lightdecoder.imessage.direct.AccountNotice
import com.lightdecoder.imessage.direct.AppleAccount
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
 * Once registered, the optional Apple Account ([AppleAccount]): "Apple Account (optional)" to sign in
 * ([AppleAccountActivity]), or who is signed in, whether iCloud's features are on, and Sign Out.
 */
class DirectSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImessageBinding
    private val engine by lazy { DirectEngine.get(this) }
    private val prefs by lazy { DirectPrefs.prefs(this) }
    private val accountOnce = lazy { AppleAccount.of(this) }
    private val account by accountOnce
    private var accountShown: kotlinx.coroutines.Job? = null
    private var noticeOnScreen: AccountNotice? = null

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
        binding.appleAccountAction.setOnClickListener { onAppleAccountAction() }

        showLibrary()
        engine.start()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    engine.status.collect {
                        showStatus(it)
                        showAccount()
                    }
                }
                launch { account.state.collect { showAccount() } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the store, maybe with OpenPigeon installed.
        showGames()
        // Back from Android's settings, maybe with notifications or the call screen allowed.
        showFaceTime()
        // Signed in or out, and the restart for it not made yet (kept back by a FaceTime call, or not
        // allowed while no screen of LightDecoder's was in sight): this screen in sight may start
        // iMessage's service, so it is made now. A start or a registration under way is let finish
        // by the restart itself; a call still on keeps it back, said in the section; and never under
        // the "Signed out" message still to be read ([AppleAccount.reading]): its "OK" makes it.
        if (account.restartPending) account.screenShown()
    }

    override fun onDestroy() {
        // Gone with its "Signed out" message unread: no longer in the way of the restart.
        if (accountOnce.isInitialized()) account.reading(this, false)
        super.onDestroy()
    }

    // --- the optional Apple Account --------------------------------------------------------------

    /** The Apple Account's section, once this phone's number is registered: sign in, or who is signed in and Sign Out. */
    private fun showAccount() {
        val registered = engine.status.value is Status.Registered
        binding.appleAccountCard.visibility = if (registered) View.VISIBLE else View.GONE
        if (!registered) return
        val state = account.state.value
        accountShown?.cancel()
        accountShown = lifecycleScope.launch {
            val name = if (state.signedIn) account.maskedName() else null
            // Said once: kept on this screen while it is open, and forgotten.
            val fresh = withContext(Dispatchers.IO) { account.notice() }
            if (fresh != null) {
                noticeOnScreen = fresh
                account.noticeShown()
            }
            val notice = noticeOnScreen
            binding.appleAccountNotice.visibility = if (notice != null) View.VISIBLE else View.GONE
            notice?.let {
                binding.appleAccountNotice.setText(
                    when (it) {
                        AccountNotice.INTERRUPTED -> R.string.apple_account_notice_interrupted
                        AccountNotice.SIGNED_OUT -> R.string.apple_account_notice_signed_out
                        AccountNotice.SIGN_IN_AGAIN -> R.string.apple_account_notice_sign_in_again
                    },
                )
            }
            binding.appleAccountIcloud.visibility = if (state.signedIn && !state.attention) View.VISIBLE else View.GONE
            binding.appleAccountIcloud.setText(if (state.icloud) R.string.apple_account_icloud_on else R.string.apple_account_icloud_off)
            when {
                !state.signedIn -> {
                    binding.appleAccountBody.setText(R.string.apple_account_row_help)
                    binding.appleAccountAction.setText(R.string.apple_account_sign_in_row)
                }
                state.attention -> {
                    binding.appleAccountBody.setText(R.string.apple_account_again_body)
                    binding.appleAccountAction.setText(R.string.apple_account_sign_in_row)
                }
                else -> {
                    binding.appleAccountBody.text = name?.let { getString(R.string.apple_account_signed_in_as, it) } ?: getString(R.string.apple_account_signed_in)
                    binding.appleAccountAction.setText(R.string.apple_account_sign_out)
                }
            }
            if (account.restartPending) binding.appleAccountIcloud.apply {
                visibility = View.VISIBLE
                text = restartPendingText()
            }
        }
    }

    private fun onAppleAccountAction() {
        val state = account.state.value
        if (state.signedIn && !state.attention) {
            confirmSignOut()
        } else {
            startActivity(android.content.Intent(this, AppleAccountActivity::class.java))
        }
    }

    /** Sign Out, asked first: on this phone only; iMessage restarts for it. */
    private fun confirmSignOut() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.apple_account_sign_out_title)
            .setMessage(R.string.apple_account_sign_out_body)
            .setPositiveButton(R.string.apple_account_sign_out) { _, _ -> signOut() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.filterTouchesWhenObscured = true
    }

    private fun signOut() {
        lifecycleScope.launch {
            when (account.signOut()) {
                AppleAccount.SignOut.Done -> {
                    // What to do at Apple, read first: nothing else restarts iMessage under it meanwhile
                    // (this screen coming back, a chat or the call screen), which would take it away.
                    account.reading(this@DirectSetupActivity, true)
                    MaterialAlertDialogBuilder(this@DirectSetupActivity)
                        .setMessage(R.string.apple_account_signed_out)
                        .setPositiveButton(android.R.string.ok, null)
                        // Read: iMessage restarts without the account.
                        .setOnDismissListener {
                            account.reading(this@DirectSetupActivity, false)
                            restartForAccount()
                        }
                        .show()
                }
                AppleAccount.SignOut.NotSignedIn -> showAccount()
                is AppleAccount.SignOut.Refused -> showError(getString(R.string.apple_account_busy))
            }
        }
    }

    /** "Signed out" read: iMessage restarts now, or says when it will. */
    private fun restartForAccount() {
        lifecycleScope.launch {
            if (!account.restartNow()) {
                showError(restartPendingText())
                showAccount()
            }
        }
    }

    /** When a restart still pending is made: once the FaceTime call that kept it back is over, or the next time LightDecoder is open. */
    private fun restartPendingText(): String =
        getString(if (account.restartHeldByCall) R.string.apple_account_restart_pending else R.string.apple_account_restart_later)

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
