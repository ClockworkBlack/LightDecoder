package com.lightdecoder.ui.chat

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.radiobutton.MaterialRadioButton
import com.lightdecoder.R
import com.lightdecoder.imessage.direct.IMessageExtra

/**
 * "Send with effect", as on an iPhone when send is held down: Bubble (Slam, Loud, Gentle,
 * Invisible Ink) or Screen (Echo, Spotlight, Balloons, ...), the message being sent in a blue
 * bubble at the top, and the round blue arrow that sends it with the effect picked.
 */
internal object EffectSheet {

    /**
     * Shows the sheet for a message shown as [preview] (its text, or "2 attachments"); [onSend]
     * gets the id of the effect picked ([IMessageExtra.EFFECTS]) when the arrow is tapped.
     */
    fun show(activity: Activity, preview: String, onSend: (String) -> Unit): BottomSheetDialog {
        val sheet = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.sheet_effects, null)
        view.findViewById<TextView>(R.id.effectPreview).text = preview
        val send = view.findViewById<ImageButton>(R.id.effectSend)
        val bubbles = view.findViewById<RadioGroup>(R.id.effectBubbleList)
        val screens = view.findViewById<RadioGroup>(R.id.effectScreenList)
        var picked: String? = null

        fun pick(id: String?) {
            picked = id
            send.isEnabled = id != null
            send.alpha = if (id != null) 1f else DISABLED_ALPHA
        }

        val rowHeight = (ROW_HEIGHT_DP * activity.resources.displayMetrics.density).toInt()
        for ((id, name) in IMessageExtra.EFFECTS) {
            val group = if (IMessageExtra.isBubbleEffect(id)) bubbles else screens
            group.addView(
                MaterialRadioButton(activity).apply {
                    this.id = View.generateViewId()
                    tag = id
                    setText(name)
                    minHeight = rowHeight
                },
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        // One effect at a time, whichever kind.
        bubbles.setOnCheckedChangeListener { group, checked ->
            if (checked == View.NO_ID) return@setOnCheckedChangeListener
            screens.clearCheck()
            pick(group.findViewById<View>(checked).tag as String)
        }
        screens.setOnCheckedChangeListener { group, checked ->
            if (checked == View.NO_ID) return@setOnCheckedChangeListener
            bubbles.clearCheck()
            pick(group.findViewById<View>(checked).tag as String)
        }
        view.findViewById<MaterialButtonToggleGroup>(R.id.effectTabs).addOnButtonCheckedListener { _, button, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val bubbleTab = button == R.id.effectTabBubble
            bubbles.visibility = if (bubbleTab) View.VISIBLE else View.GONE
            screens.visibility = if (bubbleTab) View.GONE else View.VISIBLE
        }
        send.setOnClickListener {
            val effect = picked ?: return@setOnClickListener
            sheet.dismiss()
            onSend(effect)
        }
        view.findViewById<View>(R.id.effectClose).setOnClickListener { sheet.cancel() }
        pick(null)
        sheet.setContentView(view)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
        return sheet
    }

    private const val ROW_HEIGHT_DP = 48
    private const val DISABLED_ALPHA = 0.4f
}
