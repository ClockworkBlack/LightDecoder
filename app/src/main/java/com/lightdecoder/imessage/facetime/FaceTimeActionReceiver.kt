package com.lightdecoder.imessage.facetime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The call notices' buttons that need no screen: Decline (the ringing notice) and Hang up (the
 * in-call notice). Not exported, in the ":imessage" process: only this app's own notices reach it.
 * It answers nothing and starts nothing: it can only end.
 */
class FaceTimeActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val guid = intent.getStringExtra(EXTRA_GUID)?.takeIf(CallIds::isGuid) ?: return
        when (intent.action) {
            ACTION_DECLINE -> {
                // Down at once, also when the call is no longer there to decline (the process started again since).
                FaceTimeNotices.cancelIncoming(context)
                FaceTimeCalls.peek()?.decline(guid)
            }
            ACTION_HANG_UP -> FaceTimeCalls.peek()?.hangUp(guid)
        }
    }

    companion object {
        const val ACTION_DECLINE = "com.lightdecoder.facetime.action.DECLINE"
        const val ACTION_HANG_UP = "com.lightdecoder.facetime.action.HANG_UP"
        const val EXTRA_GUID = "com.lightdecoder.facetime.extra.GUID"

        fun intent(context: Context, action: String, guid: String): Intent =
            FaceTimeNotices.explicit(context, FaceTimeActionReceiver::class.java, action).putExtra(EXTRA_GUID, guid)
    }
}
