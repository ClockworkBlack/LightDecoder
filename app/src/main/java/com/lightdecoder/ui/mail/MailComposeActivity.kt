package com.lightdecoder.ui.mail

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lightdecoder.R
import com.lightdecoder.chat.ChatService
import com.lightdecoder.databinding.ActivityMailComposeBinding
import com.lightdecoder.mail.MailManager
import com.lightdecoder.mail.MailText
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.chat.ChatErrors
import com.lightdecoder.ui.chat.applySystemBarAndImePadding
import com.lightdecoder.ui.relay.relayAttempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A new email: To (with suggestions from the people this account has
 * emailed), Subject, the message and an optional attachment. Send queues it
 * like a message and opens its thread, where replies continue it.
 */
class MailComposeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMailComposeBinding
    private lateinit var manager: MailManager
    private var attachment: Uri? = null

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            attachment = uri
            binding.attachmentName.text = nameOf(uri)
            binding.attachmentName.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMailComposeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarAndImePadding(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        manager = MailManager.get(this)

        binding.attach.setOnClickListener { pick.launch(arrayOf("*/*")) }
        binding.send.setOnClickListener { send() }
        lifecycleScope.launch {
            val people = withContext(Dispatchers.IO) {
                runCatching { manager.contacts(refresh = false).contacts.map { it.phoneNumber.ifBlank { it.formattedNumber } } }
                    .getOrDefault(emptyList()).filter { MailText.isAddress(it) }.distinct()
            }
            binding.to.setAdapter(ArrayAdapter(this@MailComposeActivity, android.R.layout.simple_dropdown_item_1line, people))
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun send() {
        val to = MailText.parseAddresses(binding.to.text?.toString().orEmpty())
        if (to.isEmpty()) {
            showError(getString(R.string.mail_compose_need_to))
            return
        }
        if (to.any { !MailText.isAddress(it) }) {
            showError(getString(R.string.mail_error_invalid_address))
            return
        }
        val subject = binding.subject.text?.toString().orEmpty()
        val body = binding.body.text?.toString().orEmpty()
        val file = attachment
        binding.send.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                relayAttempt {
                    val thread = manager.startEmail(to, subject)
                    if (file != null) manager.sendMedia(thread, file, body, null) else manager.sendText(thread, body, null)
                    thread
                }
            }
            result.onSuccess { thread ->
                startActivity(RelayIntents.thread(this@MailComposeActivity, thread, service = ChatService.GMAIL).setFlags(0))
                finish()
            }.onFailure {
                binding.send.isEnabled = true
                showError(ChatErrors.message(this@MailComposeActivity, ChatService.GMAIL, it))
            }
        }
    }

    private fun showError(text: String) {
        binding.error.text = text
        binding.error.visibility = View.VISIBLE
    }

    private fun nameOf(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()
}
