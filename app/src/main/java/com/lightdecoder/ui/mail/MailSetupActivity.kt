package com.lightdecoder.ui.mail

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lightdecoder.R
import com.lightdecoder.chat.ChatService
import com.lightdecoder.databinding.ActivityMailSetupBinding
import com.lightdecoder.databinding.DialogMailRemoveBinding
import com.lightdecoder.mail.MailCheckJob
import com.lightdecoder.mail.MailManager
import com.lightdecoder.mail.MailPrefs
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.chat.ChatErrors
import com.lightdecoder.ui.chat.ConversationsActivity
import com.lightdecoder.ui.chat.ServiceUi
import com.lightdecoder.ui.chat.applySystemBarAndImePadding
import com.lightdecoder.ui.relay.NotificationPermission
import com.lightdecoder.ui.relay.RelayUi
import com.lightdecoder.ui.relay.relayAttempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Adds the user's Gmail account (address and app password, checked with
 * Gmail before anything is kept) and, once added, is its settings screen:
 * which account, when it was last checked, background checks,
 * notifications, check now, and Remove. Opened with
 * [com.lightdecoder.relay.RelayIntents.mailSetup].
 */
class MailSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMailSetupBinding
    private lateinit var manager: MailManager
    private val notifications = NotificationPermission(this, ChatService.GMAIL)
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app password is typed here: kept out of screenshots and the recents preview.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivityMailSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarAndImePadding(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        manager = MailManager.get(this)

        binding.openAppPasswords.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APP_PASSWORDS_URL)))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, APP_PASSWORDS_URL, Toast.LENGTH_LONG).show()
            }
        }
        binding.add.setOnClickListener { add() }
        binding.password.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { add(); true } else false
        }
        binding.openInbox.setOnClickListener {
            startActivity(
                Intent(this, ConversationsActivity::class.java).putExtra(RelayIntents.EXTRA_SERVICE, ChatService.GMAIL.key)
            )
            finish()
        }
        binding.checkNow.setOnClickListener {
            lifecycleScope.launch {
                relayAttempt { manager.syncNow(notify = true) }
                    .onFailure { toast(ChatErrors.message(this@MailSetupActivity, ChatService.GMAIL, it)) }
                render()
            }
        }
        binding.switchAuto.setOnClickListener {
            MailPrefs.setAutoCheck(this, binding.switchAuto.isChecked)
            if (binding.switchAuto.isChecked) MailCheckJob.schedule(this) else MailCheckJob.cancel(this)
        }
        binding.switchNotify.setOnClickListener {
            val on = binding.switchNotify.isChecked
            MailPrefs.setNotifyMessages(this, on)
            if (on) notifications.requestIfNeeded()
        }
        binding.remove.setOnClickListener { confirmRemove() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { manager.state.collect { render() } }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun render() {
        val email = manager.accountEmail()
        binding.addSection.visibility = if (email == null) View.VISIBLE else View.GONE
        binding.statusSection.visibility = if (email == null) View.GONE else View.VISIBLE
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.add.isEnabled = !busy
        if (email == null) return
        binding.account.text = getString(R.string.mail_status_account, email)
        val state = manager.state.value
        binding.stateBody.setText(ServiceUi.phaseBody(ChatService.GMAIL, state.phase, RelayUi.Fix.NONE))
        binding.switchAuto.isChecked = MailPrefs.autoCheck(this)
        binding.switchNotify.isChecked = MailPrefs.notifyMessages(this)
        lifecycleScope.launch {
            val last = withContext(Dispatchers.IO) { runCatching { manager.repo.health().lastEventTs }.getOrDefault(0L) }
            binding.lastCheck.text = getString(
                R.string.mail_status_last_check,
                if (last <= 0) getString(R.string.link_status_never)
                else DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            )
        }
    }

    private fun add() {
        if (busy) return
        val email = binding.email.text?.toString().orEmpty()
        val password = binding.password.text?.toString().orEmpty()
        busy = true
        binding.error.visibility = View.GONE
        render()
        lifecycleScope.launch {
            val result = relayAttempt { manager.addAccount(email, password) }
            busy = false
            result.onSuccess {
                binding.password.setText("")
                toast(getString(R.string.mail_setup_added))
                notifications.requestIfNeeded()
            }.onFailure {
                binding.error.text = ChatErrors.message(this@MailSetupActivity, ChatService.GMAIL, it)
                binding.error.visibility = View.VISIBLE
            }
            render()
        }
    }

    private fun confirmRemove() {
        val dialog = DialogMailRemoveBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mail_remove_title)
            .setView(dialog.root)
            .setPositiveButton(R.string.mail_remove) { _, _ ->
                lifecycleScope.launch {
                    manager.removeAccount(deleteArchive = dialog.deleteArchive.isChecked)
                    toast(getString(R.string.mail_removed))
                    render()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        const val APP_PASSWORDS_URL = "https://myaccount.google.com/apppasswords"
    }
}
