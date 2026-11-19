package com.lightdecoder.ui.chat

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.view.MenuItem
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lightdecoder.R
import com.lightdecoder.imessage.IMessageErrors
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.IMessagePrefs
import com.lightdecoder.imessage.IMessageReplies
import com.lightdecoder.messenger.MessengerNotificationListener
import com.lightdecoder.relay.RelayState

/**
 * The iMessage light: a small dot in the Messages tab's top bar that says at
 * a glance whether iMessage is in use. In the conversation list it is the
 * state of Blue Bubbles as a whole; in a conversation, how that conversation
 * sends. A tap says why, in words.
 */
object IMessageLight {

    enum class State(val colorRes: Int) {
        /** iMessage in use: blue (in a conversation, sent silently through OpenBubbles). */
        ACTIVE(R.color.imessage_blue),

        /** iMessage, but through OpenBubbles' own screen (one tap there): amber. */
        HAND_OFF(R.color.imessage_light_amber),

        /** Blue Bubbles is on but something it needs is missing: red. */
        PROBLEM(R.color.imessage_light_red),

        /** No iMessage: Blue Bubbles off, or this conversation goes as texts. Grey. */
        OFF(R.color.imessage_light_grey),
    }

    data class Reading(val state: State, val why: Int)

    /** What Blue Bubbles as a whole needs and has: the list's light. */
    fun overall(context: Context): Reading {
        if (!IMessagePrefs.enabled(context)) return Reading(State.OFF, R.string.imessage_light_off)
        problem(context)?.let { return Reading(State.PROBLEM, it) }
        return Reading(State.ACTIVE, R.string.imessage_light_on)
    }

    /** How a Messages conversation sends: [companion] is its iMessage chat, if it has one. */
    fun forConversation(context: Context, companion: String?, isGroup: Boolean): Reading {
        if (!IMessagePrefs.enabled(context)) return Reading(State.OFF, R.string.imessage_light_off)
        problem(context)?.let { return Reading(State.PROBLEM, it) }
        if (isGroup) return Reading(State.OFF, R.string.imessage_light_group)
        if (companion == null) return Reading(State.OFF, R.string.imessage_light_no_chat)
        return if (IMessageReplies.canReply(companion)) Reading(State.ACTIVE, R.string.imessage_light_silent)
        else Reading(State.HAND_OFF, R.string.imessage_light_hand_off)
    }

    /** What stops Blue Bubbles from working, or null. */
    private fun problem(context: Context): Int? {
        val state = runCatching { IMessageManager.get(context).state.value }.getOrNull()
        return when {
            state?.phase == RelayState.Phase.ERROR && state.code == IMessageErrors.NOT_INSTALLED -> R.string.imessage_light_not_installed
            !MessengerNotificationListener.hasAccess(context) -> R.string.imessage_light_no_access
            !MessengerNotificationListener.isConnected() -> R.string.imessage_light_reader_stopped
            else -> null
        }
    }

    /** Shows [reading] on the top bar's item [item] (hidden when Blue Bubbles is off and [hideWhenOff]). */
    fun bind(activity: Activity, item: MenuItem?, reading: Reading, hideWhenOff: Boolean) {
        item ?: return
        item.isVisible = !(hideWhenOff && reading.state == State.OFF && reading.why == R.string.imessage_light_off)
        val root = item.actionView ?: return
        val dot = root.findViewById<View>(R.id.imessageLightDot) ?: return
        dot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, reading.state.colorRes))
        dot.tag = reading.state
        val words = activity.getString(reading.why)
        root.contentDescription = activity.getString(R.string.imessage_light) + ": " + words
        androidx.appcompat.widget.TooltipCompat.setTooltipText(root, words)
        root.setOnClickListener {
            MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(stateTitle(reading.state)))
                .setMessage(words)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun stateTitle(state: State): Int = when (state) {
        State.ACTIVE -> R.string.imessage_light_title_active
        State.HAND_OFF -> R.string.imessage_light_title_hand_off
        State.PROBLEM -> R.string.imessage_light_title_problem
        State.OFF -> R.string.imessage_light_title_off
    }
}
