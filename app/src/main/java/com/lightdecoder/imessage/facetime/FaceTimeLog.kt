package com.lightdecoder.imessage.facetime

import android.util.Log
import com.lightdecoder.imessage.IMessageLog
import com.lightdecoder.imessage.Redact

/**
 * What FaceTime did, for the iMessage log (in "Report a problem") and logcat (`adb logcat -s
 * LDiMessage`): kinds, outcomes and timings only. No number, email address or call id is ever
 * written: a call is named by [ref], a short keyed tag ([IMessageLog.chatRef]'s), and every line
 * goes through [Redact] on its way out, should an error's text name one.
 *
 * The ":imessage" process does not write the log's file (the main process keeps it): its lines go
 * to logcat here and, through [sink], to the main process, which adds them ([IMessageLog.add]).
 */
object FaceTimeLog {
    /** Hands a line to the main process's iMessage log; set by the ":imessage" process's call engine. */
    @Volatile
    var sink: ((String) -> Unit)? = null

    fun note(line: String) {
        val clean = Redact.text(line)
        // Not there on a computer (unit tests): never in the way.
        runCatching { Log.i(IMessageLog.TAG, PREFIX + clean) }
        runCatching { sink?.invoke(clean) }
    }

    /** "call#3f9a1c": the same call gets the same tag in one log; it cannot be turned back into the call's id. */
    fun ref(guid: String): String = IMessageLog.chatRef("facetime:$guid").replace("chat#", "call#")

    const val PREFIX = "FaceTime: "
}
