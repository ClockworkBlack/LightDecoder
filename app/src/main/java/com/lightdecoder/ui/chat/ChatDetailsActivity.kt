package com.lightdecoder.ui.chat

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveChange
import com.lightdecoder.archive.ArchiveEvents
import com.lightdecoder.archive.ConversationRow
import com.lightdecoder.chat.ChatBackend
import com.lightdecoder.chat.ChatService
import com.lightdecoder.databinding.ActivityChatDetailsBinding
import com.lightdecoder.databinding.DialogGroupNamePhotoBinding
import com.lightdecoder.databinding.ItemChatMemberBinding
import com.lightdecoder.imessage.direct.DirectLink
import com.lightdecoder.relay.ContactPhotos
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.applySystemBarPadding
import com.lightdecoder.ui.relay.relayAttempt
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A chat's details, as an iPhone's (a tap on the name at the top of an iMessage chat): its picture
 * and name, large; for a group its members, with "Change Name and Photo", "Add Member", Remove and
 * "Leave this Conversation" where the iMessage library can do them ([DirectLink.groupRules]);
 * "Hide Alerts"; and for a chat with one person through the library, "Send Read Receipts" (the
 * chat's own choice, the iMessage screen's setting until one is made).
 *
 * Extras: [RelayIntents.EXTRA_CONVERSATION_ID], [RelayIntents.EXTRA_SERVICE], and for a Messages
 * conversation with the person's iMessage chat in it, that chat ([EXTRA_COMPANION]): its alerts are
 * hidden along with the conversation's, and its read receipts are the ones the switch is about.
 */
class ChatDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatDetailsBinding
    private lateinit var manager: ChatBackend
    private var conversationId = ""

    /** The person's iMessage chat shown in this Messages conversation, if any. */
    private var companionId: String? = null

    private val direct: DirectLink get() = DirectLink.get(this)

    /** A member of a group, as listed: their address, name and picture. */
    data class Member(val address: String, val name: String, val photo: File?)

    /** The chat as the last load found it. */
    data class Details(
        val conversation: ConversationRow,
        val title: String,
        /** Its members besides the user (a group's); the other person of a chat with one. */
        val members: List<Member>,
        val photo: File?,
        /** What the library may change of it (a group's). */
        val rules: DirectLink.GroupRules,
        /** "Send Read Receipts" as it is for this chat; null where it has none (a group, or not through the library). */
        val receipts: Boolean?,
        val muted: Boolean,
        /** A group the user is no longer in. */
        val left: Boolean,
        /** A group's own name, for "Change Name and Photo" ("" for none). */
        val groupName: String,
        /** Whom a FaceTime call from here goes to; null where there are no FaceTime buttons (a group, not through the library, "FaceTime" off, or Apple says they cannot be called). */
        val faceTime: DirectLink.FaceTimeTarget? = null,
        /** A group's FaceTime call (its members); null where it has none (not a group, not through the library, "FaceTime" off, or Apple says nobody of it can be called). */
        val faceTimeGroup: DirectLink.FaceTimeGroup? = null,
    )

    @VisibleForTesting
    internal var details: Details? = null
        private set

    /** The dialog open now ("Change Name and Photo", a question), closed with the screen. */
    @VisibleForTesting
    internal var dialog: Dialog? = null

    /** While the switches are set from what was loaded: their changes are not the user's. */
    private var rendering = false

    /** Changes under way (each shows the bar at the top). */
    private var busy = 0

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) setPhoto(uri)
    }

    private val onArchiveChange: (ArchiveChange) -> Unit = { change ->
        val chats = setOfNotNull(conversationId, companionId)
        if (change.kind == ArchiveChange.Kind.CONVERSATIONS && (change.conversationId == null || change.conversationId in chats)) load()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarPadding(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        conversationId = intent.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID).orEmpty()
        if (conversationId.isEmpty()) {
            finish()
            return
        }
        manager = ScreenBackends.get(this, RelayIntents.serviceOf(intent))
        companionId = intent.getStringExtra(EXTRA_COMPANION)?.takeIf { it.isNotEmpty() && manager.service == ChatService.MESSAGES }
        binding.changeNamePhoto.setOnClickListener { showNameAndPhoto() }
        binding.addMember.setOnClickListener { startActivity(NewConversationActivity.addMembers(this, conversationId)) }
        binding.leave.setOnClickListener { confirmLeave() }
        binding.block.setOnClickListener { details?.members?.singleOrNull()?.let(::toggleBlock) }
        binding.faceTimeAudio.setOnClickListener { startFaceTime(video = false) }
        binding.faceTimeVideo.setOnClickListener { startFaceTime(video = true) }
        binding.hideAlerts.setOnCheckedChangeListener { _, on -> if (!rendering) setHideAlerts(on) }
        binding.readReceipts.setOnCheckedChangeListener { _, on -> if (!rendering) setReadReceipts(on) }
        load()
    }

    override fun onStart() {
        super.onStart()
        ArchiveEvents.register(onArchiveChange)
        // Members added from "Add Member" (another screen) show on coming back.
        load()
    }

    override fun onStop() {
        ArchiveEvents.unregister(onArchiveChange)
        super.onStop()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // --- what is shown -----------------------------------------------------------------------

    private fun load() {
        if (!::manager.isInitialized) return
        val link = manager
        val id = conversationId
        val companion = companionId
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { read(link, id, companion) }.getOrNull() }
            if (loaded == null) {
                // Deleted meanwhile: nothing left to show.
                if (details == null) finish()
                return@launch
            }
            details = loaded
            render(loaded)
            askFaceTime(loaded)
        }
    }

    /** The chat whose person Apple was asked about ([askFaceTime]): once per screen. */
    private var faceTimeAsked: String? = null

    /**
     * Whether the person can be called with FaceTime is Apple's to say: asked once (through the
     * library; remembered there), and the buttons go if they cannot.
     */
    private fun askFaceTime(d: Details) {
        val group = d.faceTimeGroup
        val chat = d.faceTime?.conversationId ?: group?.conversationId ?: return
        if (faceTimeAsked == chat) return
        faceTimeAsked = chat
        val library = direct
        lifecycleScope.launch {
            val capable = withContext(Dispatchers.IO) {
                runCatching { if (group != null) library.faceTimeGroupCapable(chat) else library.faceTimeCapable(chat) }.getOrNull()
            }
            if (capable == false) load()
        }
    }

    /**
     * Places a FaceTime call to the chat's person, or a group's to the members who can be called:
     * the call screen is started with who to call, and does the rest.
     */
    @VisibleForTesting
    internal fun startFaceTime(video: Boolean) {
        val target = details?.faceTime
        val group = details?.faceTimeGroup
        try {
            when {
                target != null -> startActivity(
                    com.lightdecoder.ui.imessage.FaceTimeCallActivity.placeIntent(this, target.peer, target.name, video, target.from, target.conversationId, target.photo),
                )
                group != null -> {
                    val peers = direct.faceTimeCallable(group).ifEmpty { return toast(getString(R.string.facetime_not_available)) }
                    startActivity(com.lightdecoder.ui.imessage.FaceTimeCallActivity.placeIntent(this, peers, group.name, video, group.from, group.conversationId))
                }
            }
        } catch (e: RuntimeException) {
            toast(getString(R.string.facetime_not_available))
        }
    }

    /** The chat as the archives have it now. Runs on IO. */
    private fun read(link: ChatBackend, id: String, companion: String?): Details? {
        val repo = link.repo
        val row = repo.conversation(id) ?: return null
        val people = repo.participants(id)
        val iMessage = link.service == ChatService.IMESSAGE
        // The iMessage chat whose read receipts and group this is.
        val chat = if (iMessage) id else companion
        val library = direct
        val rules = if (iMessage && row.isGroup) library.groupRules(id) else DirectLink.GroupRules.NONE
        val receipts = chat?.takeIf { !row.isGroup && library.active && library.canSend(it) }?.let { library.sendsReadReceipts(it) }
        val pictures = ContactPhotos.get(applicationContext)
        val members = people.filter { !it.isMe }.map { p ->
            val address = p.number.ifBlank { p.formattedNumber }.ifBlank { p.id }
            Member(address, p.name.ifBlank { p.formattedNumber.ifBlank { address } }, pictures.forPerson(p))
        }
        return Details(
            conversation = row,
            title = ConversationTitles.of(this, repo, row),
            members = members,
            photo = AvatarPhotos.forConversation(applicationContext, link.service, id, row.isGroup, people),
            rules = rules,
            receipts = receipts,
            muted = row.muted,
            left = iMessage && library.isLeft(id),
            groupName = if (iMessage && row.isGroup) library.groupName(id) else "",
            // FaceTime: for the chat with one person the library reaches, unless Apple said they cannot be called.
            faceTime = chat?.takeIf { !row.isGroup }?.let { library.faceTimeTarget(it) }?.takeIf { target ->
                com.lightdecoder.imessage.facetime.FaceTimeButtons.shown(
                    faceTimeOn = true, reachable = true, capable = library.faceTimeCapableKnown(target.conversationId),
                )
            },
            // A group: its members, unless Apple said nobody of it can be called.
            faceTimeGroup = chat?.takeIf { row.isGroup && iMessage }?.let { library.faceTimeGroup(it) }?.takeIf { group ->
                com.lightdecoder.imessage.facetime.FaceTimeButtons.shown(
                    faceTimeOn = true, reachable = true, capable = library.faceTimeGroupCapableKnown(group.conversationId),
                )
            },
        )
    }

    private fun render(d: Details) {
        rendering = true
        val row = d.conversation
        Avatars.bind(binding.avatar, d.title, row.id, row.avatarColor, d.photo)
        binding.avatar.contentDescription = getString(R.string.chat_details_photo, d.title)
        binding.name.text = d.title
        // A person's address under their name; a group says how many it is over its members instead.
        val subtitle = if (row.isGroup) "" else d.members.singleOrNull()?.address?.takeIf { it != d.title }.orEmpty()
        binding.subtitle.text = subtitle
        binding.subtitle.visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE
        binding.changeNamePhoto.visibility = if (d.rules.manageable) View.VISIBLE else View.GONE
        binding.faceTimeRow.visibility = if (d.faceTime != null || d.faceTimeGroup != null) View.VISIBLE else View.GONE
        renderMembers(d)
        binding.hideAlerts.isChecked = d.muted
        binding.readReceipts.visibility = if (d.receipts != null) View.VISIBLE else View.GONE
        binding.readReceipts.isChecked = d.receipts == true
        binding.leftNote.visibility = if (d.left) View.VISIBLE else View.GONE
        binding.leave.visibility = if (d.rules.canLeave) View.VISIBLE else View.GONE
        binding.leave.isEnabled = busy == 0
        // A chat with one person: "Block this Caller", or "Unblock this Caller" when they are.
        val person = d.members.singleOrNull()?.takeIf { !row.isGroup && blockable(it) }
        binding.block.visibility = if (person != null) View.VISIBLE else View.GONE
        if (person != null) binding.block.setText(if (isBlocked(person)) R.string.unblock_caller else R.string.block_caller)
        rendering = false
    }

    /** A group's members (a chat with one person lists none: it is them at the top), and Add Member. */
    private fun renderMembers(d: Details) {
        val group = d.conversation.isGroup
        binding.membersHeader.visibility = if (group) View.VISIBLE else View.GONE
        binding.membersCard.visibility = if (group) View.VISIBLE else View.GONE
        binding.membersHeader.text = resources.getQuantityString(R.plurals.chat_people, d.members.size + 1, d.members.size + 1)
        binding.members.removeAllViews()
        if (!group) return
        for (member in d.members) {
            val item = ItemChatMemberBinding.inflate(layoutInflater, binding.members, false)
            Avatars.bind(item.avatar, member.name, member.address, "", member.photo)
            item.name.text = member.name
            item.address.text = member.address.takeIf { it != member.name }.orEmpty()
            item.address.visibility = if (item.address.text.isEmpty()) View.GONE else View.VISIBLE
            item.remove.visibility = if (d.rules.canRemove) View.VISIBLE else View.GONE
            item.remove.contentDescription = getString(R.string.chat_details_remove_member, member.name)
            item.remove.setOnClickListener { confirmRemove(member) }
            // A tap on a member: block or unblock them (and remove them, where the group allows it).
            item.root.setOnClickListener { memberMenu(item.root, member, d.rules.canRemove) }
            if (d.rules.canRemove) {
                item.root.setOnLongClickListener {
                    confirmRemove(member)
                    true
                }
            }
            binding.members.addView(item.root)
        }
        binding.addMember.visibility = if (d.rules.manageable) View.VISIBLE else View.GONE
    }

    // --- changes -----------------------------------------------------------------------------

    /** Runs [block] (a change through the library) with the bar showing; says why when it did not go through. */
    private fun change(block: suspend () -> Unit) {
        busy++
        binding.progress.visibility = View.VISIBLE
        binding.leave.isEnabled = false
        lifecycleScope.launch {
            relayAttempt { withContext(Dispatchers.IO) { block() } }
                .onFailure { toast(getString(R.string.chat_details_failed, ChatErrors.message(this@ChatDetailsActivity, ChatService.IMESSAGE, it))) }
            busy--
            if (busy == 0) {
                binding.progress.visibility = View.GONE
                binding.leave.isEnabled = true
            }
            load()
        }
    }

    /** "Change Name and Photo": the group's name, and its photo (choose one, or take it off), as on an iPhone. */
    @VisibleForTesting
    internal fun showNameAndPhoto() {
        val d = details ?: return
        if (!d.rules.manageable) return
        val view = DialogGroupNamePhotoBinding.inflate(layoutInflater)
        Avatars.bind(view.avatar, d.title, d.conversation.id, d.conversation.avatarColor, d.photo)
        view.nameInput.setText(d.groupName)
        view.removePhoto.visibility = if (d.photo != null) View.VISIBLE else View.GONE
        fun applyName() {
            val name = view.nameInput.text?.toString()?.trim().orEmpty()
            if (name != d.groupName.trim()) change { direct.renameGroup(conversationId, name) }
        }
        val shown = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_details_change_name_photo)
            .setView(view.root)
            .setPositiveButton(R.string.chat_details_done) { _, _ -> applyName() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog = shown
        view.choosePhoto.setOnClickListener {
            applyName()
            shown.dismiss()
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        view.removePhoto.setOnClickListener {
            applyName()
            shown.dismiss()
            change { direct.setGroupPhoto(conversationId, null) }
        }
    }

    /** The photo the user picked becomes the group's (cut to a centred square). */
    @VisibleForTesting
    internal fun setPhoto(uri: Uri) = change { direct.setGroupPhoto(conversationId, uri) }

    // --- the block list ------------------------------------------------------------------------

    private val blocks: com.lightdecoder.chat.BlockList get() = com.lightdecoder.chat.BlockList.get(this)

    private fun isBlocked(member: Member): Boolean = blocks.isBlocked(member.address)

    /** Someone the block list can hold: a phone number or an email address. */
    private fun blockable(member: Member): Boolean = com.lightdecoder.chat.BlockList.normalize(member.address) != null

    private fun memberMenu(anchor: View, member: Member, canRemove: Boolean) {
        android.widget.PopupMenu(this, anchor).apply {
            if (blockable(member)) menu.add(0, MENU_BLOCK, 0, if (isBlocked(member)) R.string.unblock_caller else R.string.block_caller)
            if (canRemove) menu.add(0, MENU_REMOVE, 1, R.string.chat_details_remove)
            if (menu.size() == 0) return
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_BLOCK -> toggleBlock(member)
                    MENU_REMOVE -> confirmRemove(member)
                }
                true
            }
            show()
        }
    }

    /**
     * Blocks [member] (asked first, in an iPhone's words), or unblocks them. Local only: nothing is
     * said to Apple or to them, and nothing promises that they see no "Delivered" (the user's iPhone
     * or Mac still acknowledges their messages).
     */
    @VisibleForTesting
    internal fun toggleBlock(member: Member) {
        if (isBlocked(member)) {
            blocks.unblock(member.address)
            toast(getString(R.string.unblocked_done))
            details?.let(::render)
            return
        }
        val sms = manager.service == ChatService.MESSAGES
        dialog?.dismiss()
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.block_caller_title, member.name))
            .setMessage(if (sms) R.string.block_caller_body_sms else R.string.block_caller_body)
            .setPositiveButton(R.string.block_contact) { _, _ -> block(member) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { shown -> shown.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.filterTouchesWhenObscured = true }
    }

    @VisibleForTesting
    internal fun block(member: Member) {
        val text = when (blocks.block(member.address)) {
            com.lightdecoder.chat.BlockList.Added.ADDED, com.lightdecoder.chat.BlockList.Added.ALREADY -> R.string.blocked_done
            com.lightdecoder.chat.BlockList.Added.OWN -> R.string.blocked_contacts_own
            com.lightdecoder.chat.BlockList.Added.FULL -> R.string.blocked_contacts_full
            com.lightdecoder.chat.BlockList.Added.INVALID -> R.string.blocked_contacts_invalid
        }
        toast(getString(text))
        details?.let(::render)
    }

    private fun confirmRemove(member: Member) {
        dialog?.dismiss()
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.chat_details_remove_title, member.name))
            .setPositiveButton(R.string.chat_details_remove) { _, _ -> change { direct.removeMember(conversationId, member.address) } }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmLeave() {
        dialog?.dismiss()
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_details_leave_title)
            .setMessage(R.string.chat_details_leave_body)
            .setPositiveButton(R.string.chat_details_leave_confirm) { _, _ -> change { direct.leaveGroup(conversationId) } }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * "Hide Alerts": the conversation is muted, or not; a Messages conversation with the person's
     * iMessage chats in it, those too (their iMessages alert on their own), as its menu and its long
     * press in the list do ([MergedChats]).
     */
    private fun setHideAlerts(on: Boolean) {
        val link = manager
        val id = conversationId
        val companion = companionId
        lifecycleScope.launch {
            relayAttempt {
                val companions = (listOfNotNull(companion) + withContext(Dispatchers.IO) { MergedChats.companions(applicationContext, link.service, id) }).distinct()
                MergedChats.setMuted(this@ChatDetailsActivity, link, id, on, companions)
            }.onFailure { toast(ChatErrors.message(this@ChatDetailsActivity, link.service, it)) }
            load()
        }
    }

    /** "Send Read Receipts" for this chat (its iMessage chat's), kept as the chat's own choice. */
    private fun setReadReceipts(on: Boolean) {
        val chat = if (manager.service == ChatService.IMESSAGE) conversationId else companionId ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { direct.setSendsReadReceipts(chat, on) }
            load()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).apply { setGravity(Gravity.CENTER, 0, 0) }.show()
    }

    companion object {
        private const val MENU_BLOCK = 1
        private const val MENU_REMOVE = 2

        /** A Messages conversation's iMessage chat (the person's, shown inside it). */
        const val EXTRA_COMPANION = "com.lightdecoder.ui.chat.extra.COMPANION"

        /** The details of conversation [conversationId] of [service]; [companion] for a Messages conversation with the person's iMessage chat in it. */
        fun intent(context: Context, conversationId: String, service: ChatService, companion: String? = null): Intent =
            Intent(context, ChatDetailsActivity::class.java)
                .putExtra(RelayIntents.EXTRA_CONVERSATION_ID, conversationId)
                .putExtra(RelayIntents.EXTRA_SERVICE, service.key)
                .apply { if (companion != null) putExtra(EXTRA_COMPANION, companion) }
    }
}
