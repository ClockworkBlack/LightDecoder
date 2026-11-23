package com.lightdecoder.imessage.facetime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The call's buttons that need no screen: Decline (the ringing notice; Ignore for a group ring from
 * someone the user does not know, which the call rules send nothing for), Hang up (the in-call
 * notice and the picture-in-picture window), and the picture-in-picture window's Mute (and Unmute)
 * and Camera Off. Not exported, in the ":imessage" process: only this app's own notices and window
 * reach it. It answers nothing, places nothing and turns nothing on (the microphone only back on
 * after its own Mute): it can only end a call or take something of it away. Nothing thrown here
 * goes further.
 */
class FaceTimeActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            val guid = intent.getStringExtra(EXTRA_GUID)?.takeIf(CallIds::isGuid) ?: return
            when (intent.action) {
                ACTION_DECLINE -> {
                    // Down at once, also when the call is no longer there to decline (the process started again since).
                    FaceTimeNotices.cancelIncoming(context)
                    FaceTimeCalls.peek()?.decline(guid)
                }
                ACTION_HANG_UP -> FaceTimeCalls.peek()?.hangUp(guid)
                // Only the call that is joined, only what the window's buttons say.
                ACTION_TOGGLE_MIC -> FaceTimeCalls.peek()?.toggleMuted(guid)
                ACTION_CAMERA_OFF -> FaceTimeCalls.peek()?.cameraOff(guid)
            }
        } catch (e: Throwable) {
            FaceTimeLog.note("a call button could not be done (${e::class.java.simpleName})")
        }
    }

    companion object {
        const val ACTION_DECLINE = "com.lightdecoder.facetime.action.DECLINE"
        const val ACTION_HANG_UP = "com.lightdecoder.facetime.action.HANG_UP"
        const val ACTION_TOGGLE_MIC = "com.lightdecoder.facetime.action.TOGGLE_MIC"
        const val ACTION_CAMERA_OFF = "com.lightdecoder.facetime.action.CAMERA_OFF"
        const val EXTRA_GUID = "com.lightdecoder.facetime.extra.GUID"

        fun intent(context: Context, action: String, guid: String): Intent =
            FaceTimeNotices.explicit(context, FaceTimeActionReceiver::class.java, action).putExtra(EXTRA_GUID, guid)
    }
}
