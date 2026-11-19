package com.lightdecoder.ui.chat

import android.app.Activity
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lightdecoder.R
import com.lightdecoder.imessage.direct.BackgroundArt
import com.lightdecoder.imessage.direct.BackgroundDynamics
import com.lightdecoder.imessage.direct.BackgroundPalettes
import com.lightdecoder.imessage.direct.BackgroundStyle
import com.lightdecoder.imessage.direct.BackgroundThemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The thread menu's "Background" for an iMessage chat, Messenger-like: a bottom sheet with a grid of
 * rounded previews in sections. "Colors" (gradients an iPhone draws itself), "Dynamic" (iOS's
 * Aurora), "Themes" (the app's own designs, sent as photos), and "Your photos" (Choose Photo, and
 * None when the chat has a background). What is tapped goes to [onPick].
 */
internal object BackgroundPicker {

    /** What a tile stands for. */
    sealed class Pick {
        data class Drawn(val style: BackgroundStyle) : Pick()
        data class Theme(val id: String) : Pick()
        object ChoosePhoto : Pick()
        object Remove : Pick()
    }

    sealed class Item {
        data class Header(val title: Int) : Item()
        data class Tile(val name: String, val pick: Pick) : Item()
    }

    /** The picker's items, sections first: None only when the chat has a background ([hasBackground]). */
    fun items(activity: Activity, hasBackground: Boolean): List<Item> = buildList {
        add(Item.Header(R.string.imessage_background_section_colors))
        BackgroundPalettes.ALL.forEach { add(Item.Tile(activity.getString(it.name), Pick.Drawn(it.style))) }
        add(Item.Header(R.string.imessage_background_section_dynamic))
        BackgroundDynamics.ALL.forEach { add(Item.Tile(activity.getString(it.name), Pick.Drawn(it.style))) }
        add(Item.Header(R.string.imessage_background_section_themes))
        BackgroundThemes.ALL.forEach { add(Item.Tile(activity.getString(it.name), Pick.Theme(it.id))) }
        add(Item.Header(R.string.imessage_background_section_photos))
        add(Item.Tile(activity.getString(R.string.imessage_background_photo), Pick.ChoosePhoto))
        if (hasBackground) add(Item.Tile(activity.getString(R.string.imessage_background_none), Pick.Remove))
    }

    fun show(activity: Activity, scope: CoroutineScope, hasBackground: Boolean, onPick: (Pick) -> Unit): BottomSheetDialog {
        val sheet = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.sheet_background_picker, null)
        val grid = view.findViewById<RecyclerView>(R.id.backgroundGrid)
        val adapter = Adapter(items(activity, hasBackground), scope) { pick ->
            sheet.dismiss()
            onPick(pick)
        }
        grid.layoutManager = GridLayoutManager(activity, COLUMNS).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = if (adapter.items[position] is Item.Header) COLUMNS else 1
            }
        }
        grid.adapter = adapter
        sheet.setContentView(view)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
        return sheet
    }

    class Adapter(val items: List<Item>, private val scope: CoroutineScope, private val onPick: (Pick) -> Unit) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        /** The designs' previews, drawn once each (off the main thread). */
        private val thumbs = HashMap<String, Bitmap>()

        override fun getItemCount(): Int = items.size

        override fun getItemViewType(position: Int): Int = if (items[position] is Item.Header) HEADER else TILE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == HEADER) {
                object : RecyclerView.ViewHolder(inflater.inflate(R.layout.item_background_header, parent, false)) {}
            } else {
                val v = inflater.inflate(R.layout.item_background_tile, parent, false)
                v.findViewById<ImageView>(R.id.backgroundTileImage).apply {
                    outlineProvider = ViewOutlineProvider.BACKGROUND
                    clipToOutline = true
                }
                object : RecyclerView.ViewHolder(v) {}
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is Item.Header -> (holder.itemView as TextView).setText(item.title)
                is Item.Tile -> {
                    holder.itemView.findViewById<TextView>(R.id.backgroundTileName).text = item.name
                    holder.itemView.contentDescription = item.name
                    val image = holder.itemView.findViewById<ImageView>(R.id.backgroundTileImage)
                    image.tag = item.pick
                    when (val pick = item.pick) {
                        is Pick.Drawn -> {
                            image.scaleType = ImageView.ScaleType.FIT_XY
                            image.setImageDrawable(BackgroundArt.drawableFor(pick.style, animate = false))
                        }
                        is Pick.Theme -> {
                            image.scaleType = ImageView.ScaleType.CENTER_CROP
                            val thumb = thumbs[pick.id]
                            image.setImageBitmap(thumb)
                            if (thumb == null) scope.launch {
                                val drawn = withContext(Dispatchers.Default) { BackgroundThemes.render(pick.id, THUMB_W, THUMB_H) } ?: return@launch
                                thumbs[pick.id] = drawn
                                if (image.tag == pick) image.setImageBitmap(drawn)
                            }
                        }
                        Pick.ChoosePhoto -> {
                            image.scaleType = ImageView.ScaleType.CENTER
                            image.setImageResource(R.drawable.ic_image_placeholder)
                        }
                        Pick.Remove -> {
                            image.scaleType = ImageView.ScaleType.CENTER
                            image.setImageResource(R.drawable.ic_close)
                        }
                    }
                    holder.itemView.setOnClickListener { onPick(item.pick) }
                }
            }
        }
    }

    private const val COLUMNS = 4
    private const val HEADER = 0
    private const val TILE = 1
    private const val THUMB_W = 160
    private const val THUMB_H = 240
}

