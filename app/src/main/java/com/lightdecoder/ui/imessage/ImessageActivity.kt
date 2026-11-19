package com.lightdecoder.ui.imessage

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.lightdecoder.R
import com.lightdecoder.databinding.ActivityImessageBinding
import com.lightdecoder.imessage.ImessageEngine
import com.lightdecoder.imessage.ImessageEngine.Status
import com.lightdecoder.imessage.ImessageLibrary
import com.lightdecoder.imessage.SentGuids
import com.lightdecoder.imessage.SmsPhoneVerifier
import com.lightdecoder.ui.applySystemBarPadding
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * iMessage, through OpenBubbles' library (docs/imessage-openbubbles.md). Runs in the ":imessage"
 * process. Three steps on one screen: import OpenBubbles' files, register this phone's number with
 * the iPhone relay's code, then send; each message sent here can be unsent for 2 minutes.
 */
class ImessageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImessageBinding
    private val engine by lazy { ImessageEngine.get(this) }
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** GUIDs being unsent right now (their button shows that). */
    private val unsending = mutableSetOf<String>()

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

        binding.relayHost.setText(prefs.getString(KEY_HOST, ImessageEngine.DEFAULT_RELAY_HOST))
        binding.relayCode.setText(prefs.getString(KEY_CODE, ""))
        binding.importLibrary.setOnClickListener { pickFiles.launch(arrayOf("*/*")) }
        binding.register.setOnClickListener { onRegister() }
        binding.send.setOnClickListener { onSend() }

        ensureChannel(applicationContext)
        val app = applicationContext
        engine.onIncoming = { sender, text -> notifyIncoming(app, sender, text) }
        showLibrary()
        engine.start()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { engine.status.collect { showStatus(it) } }
                // The Unsend buttons count down; redraw every second while the screen is shown.
                launch {
                    while (true) {
                        showSent()
                        delay(1000)
                    }
                }
            }
        }
    }

    // --- library ---------------------------------------------------------------------------

    private fun showLibrary() {
        val missing = ImessageLibrary.missing(this)
        binding.libraryBody.text = if (missing.isEmpty()) {
            getString(R.string.imessage_library_ready, ImessageLibrary.processAbi())
        } else {
            getString(R.string.imessage_library_missing, missing.joinToString(", "), ImessageLibrary.processAbi())
        }
        binding.importLibrary.setText(if (missing.isEmpty()) R.string.imessage_library_reimport else R.string.imessage_library_import)
    }

    // --- registration ----------------------------------------------------------------------

    private fun onRegister() {
        val code = binding.relayCode.text?.toString().orEmpty().trim()
        if (code.isEmpty()) {
            showError(getString(R.string.imessage_relay_code_missing))
            return
        }
        prefs.edit()
            .putString(KEY_CODE, code)
            .putString(KEY_HOST, binding.relayHost.text?.toString().orEmpty().trim().ifEmpty { ImessageEngine.DEFAULT_RELAY_HOST })
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
            prefs.getString(KEY_CODE, "").orEmpty(),
            prefs.getString(KEY_HOST, ImessageEngine.DEFAULT_RELAY_HOST).orEmpty(),
            SmsPhoneVerifier(applicationContext),
        )
    }

    private fun showStatus(status: Status) {
        val (color, title, body) = when (status) {
            is Status.LibraryMissing -> Triple(GREY, getString(R.string.imessage_status_no_library), getString(R.string.imessage_status_no_library_body))
            is Status.NotRegistered -> Triple(GREY, getString(R.string.imessage_status_not_registered), getString(R.string.imessage_status_not_registered_body))
            is Status.Working -> Triple(AMBER, status.step + "…", getString(R.string.imessage_status_working_body))
            is Status.Registered -> Triple(
                GREEN, getString(R.string.imessage_status_registered),
                status.handles.joinToString(", ") { com.lightdecoder.imessage.Handles.display(it) },
            )
            is Status.Failed -> Triple(RED, getString(R.string.imessage_status_failed), status.error)
        }
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(color)
        binding.statusTitle.text = title
        binding.statusBody.text = body
        binding.statusProgress.visibility = if (status is Status.Working) View.VISIBLE else View.GONE
        val working = status is Status.Working
        binding.register.isEnabled = !working && status !is Status.LibraryMissing
        binding.importLibrary.isEnabled = !working
        binding.send.isEnabled = status is Status.Registered
        binding.registerCard.alpha = if (status is Status.LibraryMissing) 0.5f else 1f
    }

    // --- send and unsend -------------------------------------------------------------------

    private fun onSend() {
        val to = binding.recipient.text?.toString().orEmpty()
        val text = binding.messageText.text?.toString().orEmpty()
        binding.send.isEnabled = false
        lifecycleScope.launch {
            try {
                val guid = engine.sendImessage(to, text)
                binding.messageText.text = null
                Snackbar.make(binding.root, getString(R.string.imessage_sent, guid), Snackbar.LENGTH_LONG).show()
            } catch (e: Exception) {
                showError(getString(R.string.imessage_send_failed, ImessageEngine.readable(e)))
            } finally {
                binding.send.isEnabled = engine.status.value is Status.Registered
                showSent()
            }
        }
    }

    private fun onUnsend(entry: SentGuids.Entry) {
        unsending += entry.guid
        showSent()
        lifecycleScope.launch {
            try {
                engine.unsend(entry.guid, entry.part)
                MaterialAlertDialogBuilder(this@ImessageActivity)
                    .setTitle(R.string.imessage_unsent_title)
                    .setMessage(R.string.imessage_unsent_body)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } catch (e: Exception) {
                showError(getString(R.string.imessage_unsend_failed, ImessageEngine.readable(e)))
            } finally {
                unsending -= entry.guid
                showSent()
            }
        }
    }

    private fun showSent() {
        val now = System.currentTimeMillis()
        val entries = engine.sent.all(now).sortedByDescending { it.sentAt }
        binding.sentEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        val list = binding.sentList
        list.removeAllViews()
        for (e in entries) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val label = TextView(this).apply {
                text = getString(
                    R.string.imessage_sent_row,
                    DateFormat.getTimeFormat(this@ImessageActivity).format(Date(e.sentAt)),
                    e.guid,
                )
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setTextIsSelectable(true)
            }
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val left = SentGuids.remaining(e.sentAt, now)
            val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                when {
                    e.guid in unsending -> {
                        text = getString(R.string.imessage_unsending)
                        isEnabled = false
                    }
                    left > 0 -> {
                        text = getString(R.string.imessage_unsend, left / 60000, (left / 1000) % 60)
                        setOnClickListener { onUnsend(e) }
                    }
                    else -> {
                        text = getString(R.string.imessage_unsend_expired)
                        isEnabled = false
                    }
                }
            }
            row.addView(button)
            list.addView(row)
        }
    }

    // --- incoming ----------------------------------------------------------------------------

    private fun showError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        /** The channel for incoming iMessages (shown while the iMessage process is running). */
        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, context.getString(R.string.imessage_channel), NotificationManager.IMPORTANCE_HIGH),
                )
            }
        }

        /** Shows an incoming iMessage. Its text is shown, not stored. */
        fun notifyIncoming(context: Context, sender: String, text: String) {
            val nm = NotificationManagerCompat.from(context)
            if (!nm.areNotificationsEnabled()) return
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_chat)
                .setContentTitle(sender)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            try {
                nm.notify("imessage:$sender", 1, n)
            } catch (e: SecurityException) {
                // Notifications not allowed: nothing to show.
            }
        }

        private const val PREFS = "imessage"
        private const val KEY_CODE = "relay_code"
        private const val KEY_HOST = "relay_host"
        private const val CHANNEL = "imessage"
        private val GREEN = Color.rgb(0x2E, 0x7D, 0x32)
        private val AMBER = Color.rgb(0xF9, 0xA8, 0x25)
        private val RED = Color.rgb(0xC6, 0x28, 0x28)
        private val GREY = Color.rgb(0x9E, 0x9E, 0x9E)
    }
}
