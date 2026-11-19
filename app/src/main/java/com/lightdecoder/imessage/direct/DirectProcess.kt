package com.lightdecoder.imessage.direct

import android.app.Application
import android.os.Build

/**
 * OpenBubbles' library runs in a process of its own (":imessage": [DirectService] and the setup
 * screen). The main process, with the Messages archive and the Google Messages link, never loads
 * it, and a crash in it cannot take them down. The two talk through [DirectWire].
 */
object DirectProcess {

    const val SUFFIX = ":imessage"

    fun isImessageProcess(processName: String?, packageName: String): Boolean = processName == packageName + SUFFIX

    /**
     * Call first thing in [Application.onCreate]. In the iMessage process it returns true and the
     * caller must set up nothing else; elsewhere it returns false.
     */
    fun enterIfImessageProcess(app: Application): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName() else currentName(app)
        return isImessageProcess(name, app.packageName)
    }

    private fun currentName(app: Application): String? {
        val pid = android.os.Process.myPid()
        val am = app.getSystemService(android.app.ActivityManager::class.java) ?: return null
        return am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
    }
}
