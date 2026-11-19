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
import com.lightdecoder.imessage.direct.TextEffectKind

/**
 * "Send with effect", as on an iPhone when send is held down: Bubble (Slam, Loud, Gentle,
 * Invisible Ink), Screen (Echo, Spotlight, Balloons, ...) or Text (iOS 18's text effects, Big,
 * Shake, ..., on the whole message's text), the message being sent in a blue bubble at the top,
 * and the round blue arrow that sends it with the effect picked. One effect at a time. Under the
 * message, "Send Later" (iOS 18): sent at a time picked instead.
 */
internal object EffectSheet {

    /**
     * Shows the sheet for a message shown as [preview] (its text, or "2 attachments"); [onSend]
     * gets the id of the effect picked ([IMessageExtra.EFFECTS]) when the arrow is tapped, and
     * [onSendText] the text effect picked in the Text tab (shown only with it: a message with text).
     * With [onSendLater], "Send Later" (iOS 18) closes the sheet and calls it, to pick when the
     * message goes.
     */
    fun show(
        activity: Activity,
        preview: CharSequence,
        onSendText: ((TextEffectKind) -> Unit)? = null,
        onSendLater: (() -> Unit)? = null,
        onSend: (String) -> Unit,
    ): BottomSheetDialog {
        val sheet = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.sheet_effects, null)
        view.findViewById<TextView>(R.id.effectPreview).text = preview
        val send = view.findViewById<ImageButton>(R.id.effectSend)
        val bubbles = view.findViewById<RadioGroup>(R.id.effectBubbleList)
        val screens = view.findViewById<RadioGroup>(R.id.effectScreenList)
        val texts = view.findViewById<RadioGroup>(R.id.effectTextList)
        var picked: String? = null
        var pickedText: TextEffectKind? = null

        fun pick(id: String?, text: TextEffectKind? = null) {
            picked = id
            pickedText = text
            val any = id != null || text != null
            send.isEnabled = any
            send.alpha = if (any) 1f else DISABLED_ALPHA
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
        if (onSendText != null) {
            view.findViewById<View>(R.id.effectTabText).visibility = View.VISIBLE
            for (effect in TextEffectKind.values()) {
                texts.addView(
                    MaterialRadioButton(activity).apply {
                        this.id = View.generateViewId()
                        tag = effect
                        setText(effect.nameRes)
                        minHeight = rowHeight
                    },
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
        }
        // One effect at a time, whichever kind.
        val groups = listOf(bubbles, screens, texts)
        for (g in groups) {
            g.setOnCheckedChangeListener { group, checked ->
                if (checked == View.NO_ID) return@setOnCheckedChangeListener
                groups.filter { it !== group }.forEach { it.clearCheck() }
                when (val tag = group.findViewById<View>(checked).tag) {
                    is TextEffectKind -> pick(null, tag)
                    else -> pick(tag as String)
                }
            }
        }
        view.findViewById<MaterialButtonToggleGroup>(R.id.effectTabs).addOnButtonCheckedListener { _, button, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            bubbles.visibility = if (button == R.id.effectTabBubble) View.VISIBLE else View.GONE
            screens.visibility = if (button == R.id.effectTabScreen) View.VISIBLE else View.GONE
            texts.visibility = if (button == R.id.effectTabText) View.VISIBLE else View.GONE
        }
        send.setOnClickListener {
            val effect = picked
            val text = pickedText
            if (effect == null && text == null) return@setOnClickListener
            sheet.dismiss()
            if (text != null) onSendText?.invoke(text) else onSend(effect!!)
        }
        view.findViewById<View>(R.id.effectClose).setOnClickListener { sheet.cancel() }
        view.findViewById<View>(R.id.effectSendLater).apply {
            visibility = if (onSendLater != null) View.VISIBLE else View.GONE
            setOnClickListener {
                sheet.dismiss()
                onSendLater?.invoke()
            }
        }
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
