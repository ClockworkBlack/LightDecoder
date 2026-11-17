package com.lightdecoder.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lightdecoder.R
import com.lightdecoder.archive.ContactRow
import com.lightdecoder.databinding.ItemContactBinding

/** One contact row; [number] is what gets sent to the phone when it is picked. */
data class ContactItem(val contact: ContactRow, val name: String, val number: String, val selected: Boolean)

/** The phone's contacts, tap to pick (several picks make a group). */
class ContactsAdapter(
    private val onToggle: (ContactItem) -> Unit,
) : ListAdapter<ContactItem, ContactsAdapter.Holder>(DIFF) {

    private val ids = StableIds()

    init {
        setHasStableIds(true)
    }

    class Holder(val binding: ItemContactBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemId(position: Int): Long = ids.of(getItem(position).contact.contactKey)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemContactBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val b = holder.binding
        val context = b.root.context
        Avatars.bind(b.avatar, item.name, item.contact.contactKey, item.contact.color)
        b.name.text = item.name
        b.number.text = item.contact.formattedNumber.ifBlank { item.number }
        b.check.isChecked = item.selected
        b.root.isSelected = item.selected
        b.root.contentDescription = listOf(
            item.name,
            b.number.text,
            context.getString(if (item.selected) R.string.chat_contact_selected else R.string.chat_contact_not_selected),
        ).joinToString(", ")
        b.root.setOnClickListener { onToggle(item) }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ContactItem>() {
            override fun areItemsTheSame(a: ContactItem, b: ContactItem) = a.contact.contactKey == b.contact.contactKey
            override fun areContentsTheSame(a: ContactItem, b: ContactItem) = a == b
        }
    }
}
