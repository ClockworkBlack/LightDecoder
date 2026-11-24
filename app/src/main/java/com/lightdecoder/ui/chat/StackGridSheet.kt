package com.lightdecoder.ui.chat

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lightdecoder.R
import com.lightdecoder.archive.MediaRow
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.imessage.direct.IMessageExtra

/**
 * All the photos of a stack ([MediaStacks]) at once, as an iPhone shows them on a tap: "N Photos"
 * over a grid of three across; a photo's tapback (iOS 15's per-photo tapbacks) shows on its corner.
 * A tap on one opens it full screen ([onOpen]).
 */
object StackGridSheet {

    fun show(activity: Activity, media: MediaLoader, row: MessageRow, parts: List<MediaRow>, onOpen: (MediaRow) -> Unit): BottomSheetDialog {
        val sheet = BottomSheetDialog(activity)
        val density = activity.resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val extra = IMessageExtra.parse(row.extra)
        val tapbacks = MediaStacks.tapbacksOn(row, extra, parts)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(
            TextView(activity).apply {
                text = activity.getString(R.string.media_stack_title, parts.size)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                setPadding(0, 0, 0, pad / 2)
            },
        )
        val cell = (activity.resources.displayMetrics.widthPixels - pad * 2) / COLUMNS
        val grid = RecyclerView(activity).apply {
            layoutManager = GridLayoutManager(activity, COLUMNS)
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = parts.size

                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val frame = FrameLayout(activity).apply { layoutParams = ViewGroup.LayoutParams(cell, cell) }
                    val image = ImageView(activity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        layoutParams = FrameLayout.LayoutParams(cell - 4, cell - 4, Gravity.CENTER)
                    }
                    val pill = TextView(activity).apply {
                        setBackgroundResource(R.drawable.bg_media_status)
                        setPadding(pad / 4, 0, pad / 4, 0)
                        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
                        visibility = View.GONE
                    }
                    frame.addView(image)
                    frame.addView(pill)
                    return object : RecyclerView.ViewHolder(frame) {}
                }

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val frame = holder.itemView as FrameLayout
                    val image = frame.getChildAt(0) as ImageView
                    val pill = frame.getChildAt(1) as TextView
                    val part = parts[position]
                    media.bind(image, row.id, part, cell)
                    image.contentDescription = activity.getString(
                        if (part.mime.startsWith("video/")) R.string.chat_media_video else R.string.chat_media_picture,
                    )
                    val on = tapbacks[part.partId].orEmpty()
                    pill.visibility = if (on.isEmpty()) View.GONE else View.VISIBLE
                    pill.text = on.distinct().joinToString("")
                    frame.setOnClickListener {
                        sheet.dismiss()
                        onOpen(part)
                    }
                }

                override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
                    ((holder.itemView as FrameLayout).getChildAt(0) as? ImageView)?.let(media::cancel)
                }
            }
        }
        root.addView(grid)
        sheet.setContentView(root)
        sheet.show()
        return sheet
    }

    private const val COLUMNS = 3
}
