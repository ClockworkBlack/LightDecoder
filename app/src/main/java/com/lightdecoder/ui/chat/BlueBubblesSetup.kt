package com.lightdecoder.ui.chat

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lightdecoder.R
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.IMessagePrefs
import com.lightdecoder.imessage.OpenBubbles
import com.lightdecoder.messenger.MessengerNotificationListener

/**
 * The "Blue Bubbles" checkbox in the Messages tab's menu.
 *
 * Ticking it checks what it needs, in order, and only turns it on once both
 * are there: the OpenBubbles app (offered from the Play Store), and
 * notification access for LightDecoder (the system setting is opened, and
 * Blue Bubbles comes on when the user comes back with it granted). With the
 * iMessage library set up it needs neither, and comes on at once.
 * Unticking turns it off at once; the chats already stored stay, hidden.
 */
internal class BlueBubblesSetup(
    private val activity: Activity,
    /** Blue Bubbles was turned on or off: the list and the menu should be redrawn. */
    private val onChanged: () -> Unit,
) {
    /** The user was sent to the notification-access setting to finish turning Blue Bubbles on. */
    private var awaitingAccess = false

    fun isOn(): Boolean = IMessagePrefs.enabled(activity)

    fun toggle() {
        if (isOn()) turnOff() else turnOn()
    }

    private fun turnOn() {
        when {
            // Through the iMessage library: neither the OpenBubbles app nor its notifications are needed.
            direct() -> enable()
            !OpenBubbles.isInstalled(activity) -> MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.blue_bubbles_need_app_title)
                .setMessage(R.string.blue_bubbles_need_app_body)
                .setPositiveButton(R.string.blue_bubbles_get_app) { _, _ -> OpenBubbles.start(activity, OpenBubbles.storeIntent()) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            !MessengerNotificationListener.hasAccess(activity) -> MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.blue_bubbles_need_access_title)
                .setMessage(R.string.blue_bubbles_need_access_body)
                .setPositiveButton(R.string.blue_bubbles_open_settings) { _, _ ->
                    awaitingAccess = MessengerNotificationListener.openAccessSettings(activity)
                    if (!awaitingAccess) Toast.makeText(activity, R.string.messenger_setup_failed, Toast.LENGTH_LONG).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            else -> enable()
        }
    }

    /** Back on screen: if the user went to grant notification access and did, Blue Bubbles comes on. */
    fun onResume() {
        if (!awaitingAccess) return
        awaitingAccess = false
        if (!isOn() && OpenBubbles.isInstalled(activity) && MessengerNotificationListener.hasAccess(activity)) enable()
    }

    private fun enable() {
        IMessageManager.get(activity).setEnabled(true)
        val direct = direct()
        // What OpenBubbles shows right now comes in without waiting for the next message.
        if (!direct) MessengerNotificationListener.requestIMessageScan()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.blue_bubbles_on_title)
            .setMessage(if (direct) R.string.blue_bubbles_on_body_direct else R.string.blue_bubbles_on_body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        onChanged()
    }

    private fun direct(): Boolean = com.lightdecoder.imessage.direct.DirectLink.get(activity).active

    private fun turnOff() {
        IMessageManager.get(activity).setEnabled(false)
        Toast.makeText(activity, R.string.blue_bubbles_off, Toast.LENGTH_LONG).show()
        onChanged()
    }

    fun save(out: Bundle) {
        out.putBoolean(STATE_AWAITING, awaitingAccess)
    }

    fun restore(state: Bundle?) {
        awaitingAccess = state?.getBoolean(STATE_AWAITING) == true
    }

    private companion object {
        const val STATE_AWAITING = "blue_bubbles_awaiting_access"
    }
}
