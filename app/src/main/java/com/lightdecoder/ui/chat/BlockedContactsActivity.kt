package com.lightdecoder.ui.chat

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lightdecoder.R
import com.lightdecoder.chat.BlockList
import com.lightdecoder.databinding.ActivityBlockedContactsBinding
import com.lightdecoder.databinding.ItemChatMemberBinding
import com.lightdecoder.ui.applySystemBarPadding
import kotlinx.coroutines.launch

/**
 * "Blocked Contacts" (an iPhone's Settings > Apps > Messages > Blocked Contacts): the user's own
 * block list ([BlockList]), each with Unblock (its button, a long press, or a swipe), and "Add New…"
 * by number or email address. Main process, not exported; from the iMessage screen.
 */
class BlockedContactsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlockedContactsBinding
    private lateinit var adapter: Adapter
    private val blocks: BlockList get() = BlockList.get(this)

    /** The dialog open now ("Add New…"), closed with the screen. */
    @VisibleForTesting
    internal var dialog: androidx.appcompat.app.AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockedContactsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarPadding(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        adapter = Adapter(onUnblock = ::unblock, onMore = ::more)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.add.setOnClickListener { addNew() }
        // A swipe either way unblocks.
        ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
                override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
                override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                    adapter.currentList.getOrNull(vh.bindingAdapterPosition)?.let(::unblock) ?: adapter.notifyDataSetChanged()
                }
            },
        ).attachToRecyclerView(binding.list)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { blocks.entries.collect(::show) }
        }
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

    private fun show(entries: List<String>) {
        adapter.submitList(entries)
        binding.empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    @VisibleForTesting
    internal fun unblock(address: String) {
        blocks.unblock(address)
        Toast.makeText(this, R.string.unblocked_done, Toast.LENGTH_SHORT).show()
    }

    private fun more(address: String, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.unblock)
            setOnMenuItemClickListener {
                unblock(address)
                true
            }
            show()
        }
    }

    /** "Add New…": a phone number or an email address, blocked as typed (read as this phone reads a number). */
    @VisibleForTesting
    internal fun addNew() {
        val input = EditText(this).apply {
            hint = getString(R.string.blocked_contacts_add_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            isSingleLine = true
        }
        val frame = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        dialog?.dismiss()
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.blocked_contacts_add)
            .setMessage(R.string.block_caller_body)
            .setView(frame)
            .setPositiveButton(R.string.block_contact) { _, _ -> add(input.text?.toString().orEmpty()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { it.getButton(DialogInterface.BUTTON_POSITIVE)?.filterTouchesWhenObscured = true }
    }

    @VisibleForTesting
    internal fun add(typed: String) {
        val text = when (blocks.block(typed)) {
            BlockList.Added.ADDED -> R.string.blocked_done
            BlockList.Added.ALREADY -> R.string.blocked_contacts_already
            BlockList.Added.OWN -> R.string.blocked_contacts_own
            BlockList.Added.FULL -> R.string.blocked_contacts_full
            BlockList.Added.INVALID -> R.string.blocked_contacts_invalid
        }
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    /** The list's rows: the address (as the user knows it, from the Messages contacts where there is one), and Unblock. */
    private class Adapter(
        private val onUnblock: (String) -> Unit,
        private val onMore: (String, View) -> Unit,
    ) : ListAdapter<String, Adapter.Holder>(DIFF) {

        class Holder(val binding: ItemChatMemberBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemChatMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val address = getItem(position)
            val b = holder.binding
            val context = b.root.context
            val shown = address.removePrefix(com.lightdecoder.imessage.IMessageAddress.NATIONAL)
            Avatars.bind(b.avatar, shown, address, "", null)
            b.name.text = shown
            b.address.visibility = View.GONE
            b.remove.visibility = View.VISIBLE
            b.remove.contentDescription = context.getString(R.string.blocked_contacts_unblock_description, shown)
            b.remove.setOnClickListener { onUnblock(address) }
            b.root.setOnLongClickListener {
                onMore(address, it)
                true
            }
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<String>() {
                override fun areItemsTheSame(a: String, b: String) = a == b
                override fun areContentsTheSame(a: String, b: String) = a == b
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, BlockedContactsActivity::class.java)
    }
}
