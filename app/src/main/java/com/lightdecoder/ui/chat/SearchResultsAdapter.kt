package com.lightdecoder.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lightdecoder.R
import com.lightdecoder.archive.SearchHit
import com.lightdecoder.databinding.ItemSearchHitBinding

/** Full-text search results: which conversation, an excerpt around the match, and when. */
class SearchResultsAdapter(
    private val onOpen: (SearchHit) -> Unit,
) : ListAdapter<SearchHit, SearchResultsAdapter.Holder>(DIFF) {

    private val ids = StableIds()

    /** The pictures the results' conversations are shown by ([AvatarPhotos.forHits]), set with each list. */
    var photos: Map<String, java.io.File> = emptyMap()

    init {
        setHasStableIds(true)
    }

    class Holder(val binding: ItemSearchHitBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemId(position: Int): Long = getItem(position).let { ids.of(it.service.key + ":" + it.messageId) }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemSearchHitBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val hit = getItem(position)
        val b = holder.binding
        val context = b.root.context
        val name = hit.conversationName.ifBlank { context.getString(R.string.chat_untitled) }
        Avatars.bind(b.avatar, name, hit.conversationId, "", photos[AvatarPhotos.key(hit.service, hit.conversationId)])
        b.name.text = name
        b.snippet.text = hit.snippet.replace('\n', ' ')
        b.time.text = TimeFormat.listTime(context, hit.timestampMs)
        b.root.contentDescription = listOf(name, b.snippet.text, b.time.text).filter { it.isNotEmpty() }.joinToString(", ")
        b.root.setOnClickListener { onOpen(hit) }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<SearchHit>() {
            override fun areItemsTheSame(a: SearchHit, b: SearchHit) = a.service == b.service && a.messageId == b.messageId
            override fun areContentsTheSame(a: SearchHit, b: SearchHit) = a == b
        }
    }
}
