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
import com.lightdecoder.imessage.direct.AdbGrant
import com.lightdecoder.imessage.direct.AdbPairService
import com.lightdecoder.imessage.direct.SimAuth
import com.lightdecoder.imessage.direct.SmsPhoneVerifier
import com.lightdecoder.ui.applySystemBarPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * iMessage setup, from the Messages tab's menu (docs/imessage-openbubbles.md): import OpenBubbles'
 * files, then register this phone's number with the iPhone relay's code. The status is green
 * only once Apple reports the registration. iMessage conversations are then in the Messages tab.
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
                    is DirectLibrary.ImportResult.Installed ->
                        if (it.pinned) getString(R.string.imessage_import_ok, it.name)
                        else getString(R.string.imessage_import_unpinned, it.name, it.sha256)
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

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        AdbPairService.start(this)
        openDeveloperOptions()
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
        binding.allowHere.setOnClickListener { allowFromThisPhone() }
        binding.openDeveloper.setOnClickListener { openDeveloperOptions() }
        binding.pairSend.setOnClickListener {
            AdbPairService.submitCode(this, binding.pairCode.text?.toString().orEmpty())
            binding.pairCode.text = null
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { AdbPairService.state.collect { showAdb(it) } }
        }
        binding.adbCommand.setOnClickListener {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("adb", SimAuth.adbCommand(this)))
            android.widget.Toast.makeText(this, R.string.imessage_sim_copied, android.widget.Toast.LENGTH_SHORT).show()
        }

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
        showSim() // back from Settings, or after the adb command
    }

    /** Whether the SIM way of verifying the number is allowed, and how to allow it. */
    private fun showSim() {
        val allowed = SimAuth.allowed(this)
        binding.simBody.setText(if (allowed) R.string.imessage_sim_body_on else R.string.imessage_sim_body_off)
        binding.simActions.visibility = if (allowed) View.GONE else View.VISIBLE
        binding.adbCommand.visibility = if (allowed) View.GONE else View.VISIBLE
        binding.adbCommand.text = getString(R.string.imessage_sim_adb, SimAuth.adbCommand(this))
    }

    /** Starts watching for Wireless debugging's pairing offer and sends the user to its screen. */
    private fun allowFromThisPhone() {
        if (!AdbGrant.supported) {
            showError(getString(R.string.imessage_sim_needs_11))
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) // the code is asked for in a notification
            return
        }
        AdbPairService.start(this)
        openDeveloperOptions()
    }

    private fun openDeveloperOptions() {
        for (action in listOf(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, android.provider.Settings.ACTION_SETTINGS)) {
            try {
                startActivity(android.content.Intent(action))
                return
            } catch (e: android.content.ActivityNotFoundException) {
                // Developer options are hidden: plain Settings next.
            }
        }
    }

    private fun showAdb(state: AdbPairService.State) {
        val text = when (state) {
            AdbPairService.State.Idle -> null
            AdbPairService.State.Searching -> getString(R.string.imessage_adb_searching)
            is AdbPairService.State.CodeWanted -> getString(R.string.imessage_adb_code_wanted)
            AdbPairService.State.Working -> getString(R.string.imessage_adb_working)
            AdbPairService.State.Done -> getString(R.string.imessage_adb_done)
            is AdbPairService.State.Failed -> getString(R.string.imessage_adb_failed, state.why)
        }
        binding.adbState.text = text
        binding.adbState.visibility = if (text == null || SimAuth.allowed(this)) View.GONE else View.VISIBLE
        binding.codeRow.visibility = if (state is AdbPairService.State.CodeWanted) View.VISIBLE else View.GONE
        if (state is AdbPairService.State.Done) showSim()
    }

    private fun showLibrary() {
        if (DirectLibrary.isBundled(this)) {
            // This build carries OpenBubbles' files: nothing to import.
            binding.libraryBody.text = getString(R.string.imessage_library_bundled, DirectLibrary.processAbi())
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

    private fun onRegister() {
        val code = binding.relayCode.text?.toString().orEmpty().trim()
        if (code.isEmpty()) {
            showError(getString(R.string.imessage_relay_code_missing))
            return
        }
        prefs.edit()
            .putString(DirectPrefs.KEY_CODE, code)
            .putString(
                DirectPrefs.KEY_HOST,
                binding.relayHost.text?.toString().orEmpty().trim().ifEmpty { DirectEngine.DEFAULT_RELAY_HOST },
            )
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
        engine.register(
            prefs.getString(DirectPrefs.KEY_CODE, "").orEmpty(),
            prefs.getString(DirectPrefs.KEY_HOST, DirectEngine.DEFAULT_RELAY_HOST).orEmpty(),
            SmsPhoneVerifier(applicationContext),
        )
    }

    private fun showStatus(status: Status) {
        val (color, title, body) = when (status) {
            is Status.LibraryMissing -> Triple(GREY, getString(R.string.imessage_status_no_library), getString(R.string.imessage_status_no_library_body))
            is Status.NotRegistered -> Triple(GREY, getString(R.string.imessage_status_not_registered), getString(R.string.imessage_status_not_registered_body))
            is Status.Working -> Triple(AMBER, status.step + "…", getString(R.string.imessage_status_working_body))
            is Status.Registered -> Triple(GREEN, getString(R.string.imessage_status_registered), status.handles.joinToString(", ") { Handles.display(it) })
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
