package com.lightdecoder.imessage

import android.app.Application

/**
 * The iMessage screen runs in a process of its own (":imessage"). Only that process ever loads
 * OpenBubbles' library: the rest of LightDecoder (its links, archive and sticker tools) never has
 * it in memory, and a crash in it cannot take them down.
 */
object ImessageProcess {

    const val SUFFIX = ":imessage"

    fun isImessageProcess(processName: String?, packageName: String): Boolean = processName == packageName + SUFFIX

    /**
     * Call first thing in [Application.onCreate]. In the iMessage process it returns true and the
     * caller must set up nothing else; elsewhere it returns false.
     */
    fun enterIfImessageProcess(app: Application): Boolean {
        val name = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) Application.getProcessName() else null
        return isImessageProcess(name, app.packageName)
    }
}
