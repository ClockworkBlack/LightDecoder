package com.lightdecoder.imessage.facetime

/**
 * What is left of a call the ":imessage" process was stopped in (killed, crashed): nothing told the
 * other side, and the library's own list still has this phone in the call. The call engine notes
 * the call it holds in a file; once the session is up again that call is left, once (the library's
 * take-back, which only errors for a call it no longer has), unless it is the call live right now.
 */
internal object CallRecovery {
    /**
     * Leaves the call [remembered] names. [liveNow]: the call this process holds now (never that
     * one). [forget] takes the note away before anything is asked of the library: whatever the leave
     * does, it is tried once. True when a leave was asked for.
     */
    suspend fun leaveRemembered(remembered: String?, liveNow: String?, forget: (String) -> Unit, bridge: CallBridge): Boolean {
        val guid = remembered?.takeIf(CallIds::isGuid) ?: return false
        if (guid == liveNow) return false
        forget(guid)
        try {
            bridge.setLocalInterfaces()
            bridge.cancelFacetime(guid)
            FaceTimeLog.note("${FaceTimeLog.ref(guid)}: a call the app was stopped in was left")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            FaceTimeLog.note("${FaceTimeLog.ref(guid)}: a call the app was stopped in could not be left (${e::class.java.simpleName}); it may be gone already")
        }
        return true
    }
}
