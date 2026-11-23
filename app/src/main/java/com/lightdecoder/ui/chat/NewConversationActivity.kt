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
import com.lightdecoder.chat.ChatService
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
 *
 * iMessage ("New iMessage group", through the iMessage library) has no
 * contacts of its own: they are Messages', and an email address can be typed
 * as well as a number.
 *
 * With [EXTRA_ADD_TO] (an iMessage group's id) it is a group's "Add Member" instead: the people
 * picked are added to that group ([com.lightdecoder.imessage.direct.DirectLink.addMembers], once
 * Apple says each of them is on iMessage); it closes once they are, and says why when they are not.
 *
 * With [EXTRA_FACETIME] it is "New FaceTime" (the FaceTime list's): everyone picked is called
 * together (it says that each of them sees who else is in the call), Video or Audio asked first.
 */
class NewConversationActivity : AppCompatActivity() {

    /** The iMessage group people are added to ([EXTRA_ADD_TO]), or null for a new conversation. */
    private var addTo: String? = null

    /** "New FaceTime" ([EXTRA_FACETIME]): the people picked are called, not written to. */
    private var faceTime = false

    private lateinit var binding: ActivityNewConversationBinding
    private lateinit var manager: ChatBackend

    /** Where the contacts come from: the service's own, or Messages' for iMessage. */
    private lateinit var contactsFrom: ChatBackend
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
        addTo = intent.getStringExtra(EXTRA_ADD_TO)?.takeIf { it.isNotEmpty() }
        faceTime = addTo == null && intent.getBooleanExtra(EXTRA_FACETIME, false)
        manager = ScreenBackends.get(this, if (addTo != null || faceTime) ChatService.IMESSAGE else RelayIntents.serviceOf(intent))
        val iMessage = manager.service == ChatService.IMESSAGE
        contactsFrom = if (iMessage) ScreenBackends.get(this, ChatService.MESSAGES) else manager
        repo = contactsFrom.repo
        groups = Capability.NEW_GROUP in manager.capabilities || addTo != null || faceTime
        if (iMessage) {
            supportActionBar?.title = getString(
                when {
                    addTo != null -> R.string.chat_details_add_member
                    faceTime -> R.string.facetime_calls_new
                    else -> R.string.imessage_new_group
                },
            )
            binding.queryLayout.hint = getString(R.string.imessage_to_hint)
        }
        binding.faceTimeHint.visibility = if (faceTime) View.VISIBLE else View.GONE

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
            val fromPhone = relayAttempt { contactsFrom.contacts(refresh = true) }.getOrNull()?.fromPhone == true
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
            if (query.isBlank()) R.string.chat_contacts_empty else ServiceUi.contactsNoMatch(manager.service, ServiceUi.direct(this, manager.service))
        )
    }

    // --- picking -------------------------------------------------------------

    /** The typed text when it looks like a phone number (digits, maybe +, spaces, dashes), or for iMessage an email address. */
    private fun typedNumber(): String? {
        val text = binding.query.text?.toString()?.trim().orEmpty()
        return text.takeIf { looksLikeNumber(it) || (manager.service == ChatService.IMESSAGE && looksLikeEmail(it)) }
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
        binding.start.setText(
            when {
                faceTime -> R.string.facetime_calls_start
                addTo != null -> R.string.chat_details_add
                selected.size > 1 -> R.string.chat_start_group
                else -> R.string.chat_start
            },
        )
    }

    // --- starting ------------------------------------------------------------

    private fun start() {
        val numbers = selected.keys.toList()
        if (numbers.isEmpty()) return
        if (faceTime) {
            startFaceTime(numbers)
            return
        }
        addTo?.let { group ->
            addMembers(group, numbers)
            return
        }
        if (numbers.size == 1 || !groups) {
            startConversation(numbers, null)
            return
        }
        val dialogBinding = DialogGroupNameBinding.inflate(layoutInflater)
        // Every iMessage group shows its name (the default words are about RCS groups).
        if (manager.service == ChatService.IMESSAGE) dialogBinding.groupBody.setText(R.string.imessage_group_body)
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

    /**
     * "New FaceTime": Video or Audio, then one call to everyone at [numbers] (as the library takes
     * their addresses), named after the people picked; the call screen asks for what it needs and
     * says why when someone cannot be called.
     */
    private fun startFaceTime(numbers: List<String>) {
        val peers = numbers.mapNotNull { com.lightdecoder.imessage.direct.Handles.normalize(it) }
            .mapNotNull(com.lightdecoder.imessage.facetime.CallIds::handle)
            .distinctBy(com.lightdecoder.imessage.facetime.CallIds::key)
            .take(com.lightdecoder.imessage.facetime.MAX_PEOPLE)
        if (peers.isEmpty()) {
            Toast.makeText(this, R.string.facetime_refused_not_callable, Toast.LENGTH_LONG).show()
            return
        }
        val name = selected.values.reduceOrNull { a, b -> getString(R.string.facetime_names_and, a, b) }.orEmpty()
        android.widget.PopupMenu(this, binding.start).apply {
            menu.add(0, 1, 0, R.string.facetime_video)
            menu.add(0, 2, 1, R.string.facetime_audio)
            setOnMenuItemClickListener { item ->
                placeFaceTime(peers, name, video = item.itemId == 1)
                true
            }
            show()
        }
    }

    /** The call screen, to call [peers] together. */
    @androidx.annotation.VisibleForTesting
    internal fun placeFaceTime(peers: List<String>, name: String, video: Boolean) {
        try {
            startActivity(
                if (peers.size == 1) {
                    com.lightdecoder.ui.imessage.FaceTimeCallActivity.placeIntent(this, peers.single(), name, video, null, null)
                } else {
                    com.lightdecoder.ui.imessage.FaceTimeCallActivity.placeIntent(this, peers, name, video, null, null)
                },
            )
            finish()
        } catch (e: RuntimeException) {
            Toast.makeText(this, R.string.facetime_not_available, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * "Add Member": the people at [numbers] go into the iMessage group [group] once Apple says each
     * of them is on iMessage; this closes once they are in it, and says why when they are not (who
     * is not on iMessage), keeping what was picked.
     */
    private fun addMembers(group: String, numbers: List<String>) {
        starting = true
        renderSelection()
        binding.progress.visibility = View.VISIBLE
        val direct = com.lightdecoder.imessage.direct.DirectLink.get(this)
        lifecycleScope.launch {
            relayAttempt { withContext(Dispatchers.IO) { direct.addMembers(group, numbers) } }
                .onSuccess { finish() }
                .onFailure {
                    starting = false
                    binding.progress.visibility = View.GONE
                    renderSelection()
                    Toast.makeText(
                        this@NewConversationActivity,
                        ChatErrors.message(this@NewConversationActivity, ChatService.IMESSAGE, it),
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    companion object {
        private const val STATE_NUMBERS = "numbers"
        private const val STATE_LABELS = "labels"
        private const val FILTER_DEBOUNCE_MS = 150L

        /** An iMessage group's conversation id: the screen adds people to it ("Add Member") instead of starting a conversation. */
        const val EXTRA_ADD_TO = "com.lightdecoder.ui.chat.extra.ADD_TO"

        /** "New FaceTime": people picked to call together ([EXTRA_FACETIME]). */
        const val EXTRA_FACETIME = "com.lightdecoder.ui.chat.extra.FACETIME"

        /** "New FaceTime", from the FaceTime list. */
        fun newFaceTime(context: android.content.Context): Intent =
            Intent(context, NewConversationActivity::class.java)
                .putExtra(EXTRA_FACETIME, true)
                .putExtra(RelayIntents.EXTRA_SERVICE, ChatService.IMESSAGE.key)

        /** "Add Member" for the iMessage group [conversationId]. */
        fun addMembers(context: android.content.Context, conversationId: String): Intent =
            Intent(context, NewConversationActivity::class.java)
                .putExtra(EXTRA_ADD_TO, conversationId)
                .putExtra(RelayIntents.EXTRA_SERVICE, ChatService.IMESSAGE.key)

        /** At least three digits, and nothing but digits and phone punctuation. */
        fun looksLikeNumber(text: String): Boolean {
            val t = text.trim()
            if (t.count { it.isDigit() } < 3) return false
            // A "+" only as the first character (international prefix).
            return t.all { it.isDigit() || it in "+-() ./" } && t.lastIndexOf('+') <= 0
        }

        /** An email address (someone's iMessage address). */
        fun looksLikeEmail(text: String): Boolean =
            com.lightdecoder.imessage.direct.Handles.normalize(text)?.startsWith("mailto:") == true
    }
}
