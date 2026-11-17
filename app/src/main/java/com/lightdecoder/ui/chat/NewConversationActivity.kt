package com.lightdecoder.ui.chat

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.ContactRow
import com.lightdecoder.chat.Capability
import com.lightdecoder.chat.ChatBackend
import com.lightdecoder.databinding.ActivityNewConversationBinding
import com.lightdecoder.databinding.DialogGroupNameBinding
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.relay.SignInWebData
import com.lightdecoder.ui.relay.relayAttempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Starts a conversation in one service ([RelayIntents.EXTRA_SERVICE]; Google
 * Messages without it): pick one of its contacts, or type a number. Where
 * the service can start groups ([Capability.NEW_GROUP]) several picks start
 * one (RCS groups can have a name); elsewhere a new pick replaces the last.
 * The service finds an existing conversation with the same people instead
 * of making a second one.
 */
class NewConversationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewConversationBinding
    private lateinit var manager: ChatBackend
    private lateinit var repo: ArchiveRepository
    private lateinit var adapter: ContactsAdapter

    /** Whether several people can be picked, to start a group. */
    private var groups = false

    /** Picked numbers, in the order picked, with the label shown on their chip. */
    private val selected = LinkedHashMap<String, String>()
    private var filterJob: Job? = null
    private var starting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Sign-in cookies a killed setup screen left behind (once per process; see SignInWebData).
        SignInWebData.wipeIfDirty(this)
        super.onCreate(savedInstanceState)
        binding = ActivityNewConversationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarAndImePadding(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        manager = ScreenBackends.get(this, RelayIntents.serviceOf(intent))
        repo = manager.repo
        groups = Capability.NEW_GROUP in manager.capabilities

        savedInstanceState?.getStringArrayList(STATE_NUMBERS)?.let { numbers ->
            val labels = savedInstanceState.getStringArrayList(STATE_LABELS).orEmpty()
            numbers.forEachIndexed { i, n -> selected[n] = labels.getOrElse(i) { n } }
        }

        adapter = ContactsAdapter(onToggle = { toggle(it.number, it.name) })
        binding.contacts.layoutManager = LinearLayoutManager(this)
        binding.contacts.adapter = adapter

        binding.query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                renderUseNumber()
                filter(debounce = true)
            }
        })
        binding.useNumber.setOnClickListener {
            val number = typedNumber() ?: return@setOnClickListener
            toggle(number, number)
            binding.query.setText("")
        }
        binding.start.setOnClickListener { start() }

        renderSelection()
        renderUseNumber()
        filter(debounce = false)
        refreshContacts()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_NUMBERS, ArrayList(selected.keys))
        outState.putStringArrayList(STATE_LABELS, ArrayList(selected.values))
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // --- contacts ------------------------------------------------------------

    /**
     * Asks the phone for its contact list once; the local copy shows
     * meanwhile. When the phone could not be reached the hint says the list
     * is the one saved earlier (or that there is none).
     */
    private fun refreshContacts() {
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val fromPhone = relayAttempt { manager.contacts(refresh = true) }.getOrNull()?.fromPhone == true
            binding.offlineHint.visibility = if (fromPhone) View.GONE else View.VISIBLE
            binding.progress.visibility = View.GONE
            filter(debounce = false)
        }
    }

    private fun filter(debounce: Boolean) {
        filterJob?.cancel()
        val query = binding.query.text?.toString().orEmpty()
        filterJob = lifecycleScope.launch {
            if (debounce) delay(FILTER_DEBOUNCE_MS)
            val rows = withContext(Dispatchers.IO) { repo.contacts(query) }
            showContacts(rows, query)
        }
    }

    private fun showContacts(rows: List<ContactRow>, query: String) {
        val items = rows.mapNotNull { row ->
            val number = row.phoneNumber.ifBlank { row.formattedNumber }
            if (number.isBlank()) return@mapNotNull null
            val name = row.customAlias.ifBlank { row.displayName }.ifBlank { row.formattedNumber.ifBlank { number } }
            ContactItem(row, name, number, number in selected)
        }
        adapter.submitList(items)
        binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyText.setText(
            if (query.isBlank()) R.string.chat_contacts_empty else ServiceUi.contactsNoMatch(manager.service)
        )
    }

    // --- picking -------------------------------------------------------------

    /** The typed text when it looks like a phone number (digits, maybe +, spaces, dashes). */
    private fun typedNumber(): String? {
        val text = binding.query.text?.toString()?.trim().orEmpty()
        return text.takeIf { looksLikeNumber(it) }
    }

    private fun renderUseNumber() {
        val number = typedNumber()
        binding.useNumber.visibility = if (number != null) View.VISIBLE else View.GONE
        if (number != null) binding.useNumber.text = getString(R.string.chat_use_number, number)
    }

    private fun toggle(number: String, label: String) {
        if (selected.remove(number) == null) {
            // One person only where the service starts no groups from here.
            if (!groups) selected.clear()
            selected[number] = label
        }
        renderSelection()
        adapter.submitList(adapter.currentList.map { it.copy(selected = it.number in selected) })
    }

    private fun renderSelection() {
        val chips = binding.selectedChips
        chips.removeAllViews()
        for ((number, label) in selected) {
            val chip = Chip(this).apply {
                text = label
                isCloseIconVisible = true
                closeIconContentDescription = getString(R.string.chat_remove_recipient, label)
                setOnCloseIconClickListener { toggle(number, label) }
            }
            chips.addView(chip)
        }
        binding.selectedScroll.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        binding.start.isEnabled = selected.isNotEmpty() && !starting
        binding.start.setText(if (selected.size > 1) R.string.chat_start_group else R.string.chat_start)
    }

    // --- starting ------------------------------------------------------------

    private fun start() {
        val numbers = selected.keys.toList()
        if (numbers.isEmpty()) return
        if (numbers.size == 1 || !groups) {
            startConversation(numbers, null)
            return
        }
        val dialogBinding = DialogGroupNameBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_group_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.chat_start_group) { _, _ ->
                startConversation(numbers, dialogBinding.groupName.text?.toString()?.trim()?.ifEmpty { null })
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startConversation(numbers: List<String>, groupName: String?) {
        starting = true
        renderSelection()
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            relayAttempt { manager.startConversation(numbers, groupName) }
                .onSuccess { conversationId ->
                    startActivity(
                        Intent(this@NewConversationActivity, ThreadActivity::class.java)
                            .putExtra(RelayIntents.EXTRA_CONVERSATION_ID, conversationId)
                            .putExtra(RelayIntents.EXTRA_SERVICE, manager.service.key)
                    )
                    finish()
                }
                .onFailure {
                    starting = false
                    binding.progress.visibility = View.GONE
                    renderSelection()
                    Toast.makeText(
                        this@NewConversationActivity,
                        ChatErrors.message(this@NewConversationActivity, manager.service, it),
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    companion object {
        private const val STATE_NUMBERS = "numbers"
        private const val STATE_LABELS = "labels"
        private const val FILTER_DEBOUNCE_MS = 150L

        /** At least three digits, and nothing but digits and phone punctuation. */
        fun looksLikeNumber(text: String): Boolean {
            val t = text.trim()
            if (t.count { it.isDigit() } < 3) return false
            // A "+" only as the first character (international prefix).
            return t.all { it.isDigit() || it in "+-() ./" } && t.lastIndexOf('+') <= 0
        }
    }
}
