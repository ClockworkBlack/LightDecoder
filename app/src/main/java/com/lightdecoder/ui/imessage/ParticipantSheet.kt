package com.lightdecoder.ui.imessage

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.lightdecoder.R
import com.lightdecoder.imessage.direct.Handles
import com.lightdecoder.imessage.facetime.Call
import com.lightdecoder.imessage.facetime.CallIds
import com.lightdecoder.imessage.facetime.CallLabels
import com.lightdecoder.imessage.facetime.CallerInfo
import com.lightdecoder.imessage.facetime.FaceTimeCalls
import kotlinx.coroutines.launch

/**
 * Who is in a group call and who was invited (as the iPhone call screen's info panel), opened by a
 * tap on the call's name. "In Call": the people the library says are in it, each named as the call
 * screen's tiles are ([CallLabels]: by name only those who joined as Apple vouches for). "Invited":
 * the addresses the call's member list names that are not in it (whoever wrote that list: shown as
 * addresses, never looked up), each with "Ring" (one address a tap, only of that list; there is no
 * "Ring Everyone"). Nobody can be added to the call: it says to start a new one with everyone.
 */
class ParticipantSheet(
    private val activity: AppCompatActivity,
    private val calls: FaceTimeCalls,
    private val labelText: (CallLabels.Label, Map<String, CallerInfo>) -> String,
) : BottomSheetDialog(activity) {

    private val title = TextView(activity)
    private val inCall = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val invited = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val invitedHeader = header(R.string.facetime_invited)

    /** The addresses shown as invited (for tests), and those rung again from here. */
    var shownInvited: List<String> = emptyList()
        private set
    private val rung = HashSet<String>()

    /** What it was last drawn for, so the invited are asked of the library again only for another call. */
    private var drawnFor: String? = null
    private var drawnJoined = 0

    init {
        val pad = dp(20)
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
        title.setPadding(0, 0, 0, dp(8))
        title.isFocusable = true
        val note = TextView(activity).apply {
            setText(R.string.facetime_add_hint)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(16), 0, 0)
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(title)
            addView(header(R.string.facetime_in_call))
            addView(inCall)
            addView(invitedHeader)
            addView(invited)
            addView(note)
        }
        setContentView(android.widget.ScrollView(activity).apply { addView(column) })
    }

    /** Draws [call]'s people as they are now ([known]: who the vouched-for ones are to the user). */
    fun update(call: Call, known: Map<String, CallerInfo>) {
        if (!call.group || !call.live) {
            dismiss()
            return
        }
        val labels = CallLabels.of(call.inCall, call.joiners)
        title.text = activity.resources.getQuantityString(R.plurals.facetime_people, labels.size + 1, labels.size + 1)
        inCall.removeAllViews()
        inCall.addView(line(activity.getString(R.string.facetime_you)))
        for (label in labels) inCall.addView(line(labelText(label, known)))
        if (drawnFor != call.guid) {
            drawnFor = call.guid
            drawnJoined = call.joiners.size
            rung.clear()
            loadInvited(call)
        } else if (drawnJoined != call.joiners.size) {
            // Someone invited joined (or another device did): who is still only invited is asked again.
            drawnJoined = call.joiners.size
            loadInvited(call)
        }
    }

    private fun loadInvited(call: Call) {
        activity.lifecycleScope.launch {
            val list = runCatching { calls.invited() }.getOrDefault(emptyList())
            if (drawnFor != call.guid) return@launch
            shownInvited = list
            invited.removeAllViews()
            if (list.isEmpty()) invited.addView(line(activity.getString(R.string.facetime_nobody_invited)))
            for (handle in list) invited.addView(invitedRow(handle, call.joined))
        }
    }

    /** One invited address, with "Ring" while the user is in the call. */
    private fun invitedRow(handle: String, joined: Boolean): View {
        val shown = CallIds.label(Handles.display(handle))
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
        }
        row.addView(line(shown), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (joined) {
            val ring = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                val key = CallIds.key(handle)
                setText(if (key in rung) R.string.facetime_rung else R.string.facetime_ring)
                isEnabled = key !in rung
                contentDescription = activity.getString(R.string.facetime_ring_description, shown)
                filterTouchesWhenObscured = true
                setOnClickListener { ring(handle, this) }
            }
            row.addView(ring)
        }
        return row
    }

    private fun ring(handle: String, button: MaterialButton) {
        button.isEnabled = false
        button.setText(R.string.facetime_rung)
        rung += CallIds.key(handle)
        activity.lifecycleScope.launch {
            if (!calls.ringMember(handle)) {
                rung -= CallIds.key(handle)
                button.isEnabled = true
                button.setText(R.string.facetime_ring)
                android.widget.Toast.makeText(activity, R.string.facetime_ring_failed, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun header(text: Int) = TextView(activity).apply {
        setText(text)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        setTextColor(ContextCompat.getColor(activity, R.color.call_accept))
        setPadding(0, dp(12), 0, dp(4))
        androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
    }

    private fun line(text: String) = TextView(activity).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        setPadding(0, dp(6), 0, dp(6))
        maxLines = 2
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
