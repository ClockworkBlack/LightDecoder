package com.lightdecoder.imessage.facetime

import android.content.Context
import com.lightdecoder.imessage.direct.DirectEngine
import com.lightdecoder.imessage.direct.DirectPrefs

/** The ":imessage" process's real parts of [FaceTimeCalls]: the library, the phone's camera and audio, the notices, the main process. */
internal object FaceTimeParts {

    fun real(app: Context): FaceTimeCalls {
        val engine = DirectEngine.get(app)
        val notices = FaceTimeNotices.Poster(app)
        val telecom = telecom(app, notices)
        // LightDecoder's calling account with Telecom, as the settings are now.
        TelecomAccount.sync(app)
        return FaceTimeCalls(
            app = app,
            bridge = engine.callBridge,
            // The library is loaded by the time a call is joined: its session is what the join needs.
            natives = CallNatives { host -> JniCallNative(host) },
            main = object : MainLink {
                override suspend fun caller(handle: String): CallerInfo? = engine.callerLookup?.invoke(handle)
                override suspend fun log(entry: CallLogEntry) { engine.storeCall(entry) }
            },
            notices = notices,
            foreground = FaceTimeCallService.Starter(app),
            // While Telecom has the call it owns the audio; without it, as before.
            audio = TelecomAudio(FaceTimeAudioRoute(app), telecom) { FaceTimeCalls.peek()?.state?.value?.guid },
            camera = FaceTimeCamera(app),
            ringback = ToneRingback(),
            settings = { settings(app) },
            phoneBusy = { FaceTimeAudioRoute.phoneInCall(app) },
            remember = { DirectPrefs.setLiveFaceTimeCall(app, it) },
            // The main process's "return to call" bar is told whenever where FaceTime stands changed.
            tell = { call -> engine.faceTimeChanged(FaceTimeNotices.state(app, call)) },
            // Whom the user blocked, as this process's copy of the block list has it: their rings are dropped first.
            // The user's own addresses never count as blocked, whatever the copy holds.
            rules = CallRules(blocked = { !engine.isOwnHandle(it) && BlockedCallers.get(app).isBlocked(it) }),
            telecom = telecom,
            cellularSource = CellularWatch.Real(app),
        )
    }

    /**
     * Telecom's side of the calls: what a headset or a car asks goes to exactly what the user's own
     * buttons do (an answer opens the call screen's answer with the camera off; a decline declines; a
     * hang-up hangs up), on the call engine there is. Handed calls only while [TelecomAccount.wanted].
     */
    private fun telecom(app: Context, notices: CallNotices): TelecomCalls {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        return TelecomCalls(
            platform = TelecomAccount.Real(app),
            actions = object : TelecomCalls.Actions {
                override fun answer(guid: String): Boolean = try {
                    app.startActivity(com.lightdecoder.ui.imessage.FaceTimeCallActivity.answerIntent(app, guid, withCamera = false))
                    true
                } catch (e: RuntimeException) {
                    false
                }

                override fun decline(guid: String) {
                    FaceTimeCalls.peek()?.decline(guid)
                }

                override fun hangUp(guid: String) {
                    FaceTimeCalls.peek()?.hangUp(guid)
                }

                override fun showIncoming(guid: String) {
                    FaceTimeCalls.peek()?.state?.value?.takeIf { it.guid == guid && it.incomingRing }?.let(notices::showIncoming)
                }
            },
            enabled = { TelecomAccount.wanted(app) },
            after = { ms, block -> handler.postDelayed({ runCatching(block) }, ms) },
            locked = { app.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked != false },
            groupTitle = { app.getString(com.lightdecoder.R.string.facetime_group_title, it) },
            groupLabel = { app.getString(com.lightdecoder.R.string.facetime_group) },
        )
    }

    /** The iMessage screen's two FaceTime settings, as they are now (files both processes read). */
    fun settings(context: Context): FaceTimeSettings =
        FaceTimeSettings(on = DirectPrefs.faceTimeOn(context), silenceUnknown = DirectPrefs.silenceUnknownFaceTime(context))
}
