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
import com.lightdecoder.imessage.Handles
import com.lightdecoder.imessage.ImessageEngine
import com.lightdecoder.imessage.ImessageEngine.Status
import com.lightdecoder.imessage.ImessageLibrary
import com.lightdecoder.imessage.ImessagePrefs
import com.lightdecoder.imessage.SmsPhoneVerifier
import com.lightdecoder.ui.applySystemBarPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * iMessage setup, from the Messages tab's menu (docs/imessage-openbubbles.md): import OpenBubbles'
 * files, then register this phone's number with the iPhone relay's code. The status is green
 * only once Apple reports the registration. iMessage conversations are then in the Messages tab.
 */
class ImessageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImessageBinding
    private val engine by lazy { ImessageEngine.get(this) }
    private val prefs by lazy { ImessagePrefs.prefs(this) }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) { uris.map { ImessageLibrary.import(this@ImessageActivity, it) } }
            val lines = results.map {
                when (it) {
                    is ImessageLibrary.ImportResult.Installed ->
                        if (it.pinned) getString(R.string.imessage_import_ok, it.name)
                        else getString(R.string.imessage_import_unpinned, it.name, it.sha256)
                    is ImessageLibrary.ImportResult.Refused -> it.reason
                }
            }
            MaterialAlertDialogBuilder(this@ImessageActivity)
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

        binding.relayHost.setText(prefs.getString(ImessagePrefs.KEY_HOST, ImessageEngine.DEFAULT_RELAY_HOST))
        binding.relayCode.setText(prefs.getString(ImessagePrefs.KEY_CODE, ""))
        binding.importLibrary.setOnClickListener { pickFiles.launch(arrayOf("*/*")) }
        binding.register.setOnClickListener { onRegister() }

        showLibrary()
        engine.start()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                engine.status.collect { showStatus(it) }
            }
        }
    }

    private fun showLibrary() {
        val missing = ImessageLibrary.missing(this)
        binding.libraryBody.text = if (missing.isEmpty()) {
            getString(R.string.imessage_library_ready, ImessageLibrary.processAbi())
        } else {
            getString(R.string.imessage_library_missing, missing.joinToString(", "), ImessageLibrary.processAbi())
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
            .putString(ImessagePrefs.KEY_CODE, code)
            .putString(
                ImessagePrefs.KEY_HOST,
                binding.relayHost.text?.toString().orEmpty().trim().ifEmpty { ImessageEngine.DEFAULT_RELAY_HOST },
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
            prefs.getString(ImessagePrefs.KEY_CODE, "").orEmpty(),
            prefs.getString(ImessagePrefs.KEY_HOST, ImessageEngine.DEFAULT_RELAY_HOST).orEmpty(),
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
        val working = status is Status.Working
        binding.register.isEnabled = !working && status !is Status.LibraryMissing
        binding.importLibrary.isEnabled = !working
        binding.registerCard.alpha = if (status is Status.LibraryMissing) 0.5f else 1f
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
