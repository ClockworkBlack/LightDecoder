package com.lightdecoder.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lightdecoder.databinding.ItemInboxBinding
import com.lightdecoder.inbox.InboxItem
import com.lightdecoder.inbox.InboxStore
import java.util.concurrent.Executors

/** The grid of caught stickers. */
class InboxAdapter(
    private val store: InboxStore,
    private val onOpen: (InboxItem) -> Unit,
    private val onLongPress: (InboxItem) -> Unit,
) : RecyclerView.Adapter<InboxAdapter.Holder>() {

    private var items: List<InboxItem> = emptyList()
    private val thumbnails = LruCache<String, Bitmap>(96)
    private val loader = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    class Holder(val binding: ItemInboxBinding) : RecyclerView.ViewHolder(binding.root) {
        var boundId: String? = null
    }

    fun submit(newItems: List<InboxItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun shutdown() {
        loader.shutdown()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemInboxBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.boundId = item.id

        val who = item.sender.ifBlank { item.title }
        val time = DateUtils.getRelativeTimeSpanString(
            item.timestampMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        )
        holder.binding.caption.text = if (who.isBlank()) time else "$who · $time"
        holder.binding.root.setOnClickListener { onOpen(item) }
        holder.binding.root.setOnLongClickListener { onLongPress(item); true }

        val cached = thumbnails.get(item.id)
        if (cached != null) {
            holder.binding.thumb.setImageBitmap(cached)
            return
        }
        holder.binding.thumb.setImageDrawable(null)
        if (loader.isShutdown) return
        loader.execute {
            val bitmap = decodeThumbnail(item) ?: return@execute
            main.post {
                thumbnails.put(item.id, bitmap)
                // The cell may have been reused for another sticker by now.
                if (holder.boundId == item.id) holder.binding.thumb.setImageBitmap(bitmap)
            }
        }
    }

    private fun decodeThumbnail(item: InboxItem): Bitmap? {
        val path = store.pngFile(item).path
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMBNAIL_SIDE) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private companion object {
        const val THUMBNAIL_SIDE = 320
    }
}
