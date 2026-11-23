package com.lightdecoder.imessage.facetime

import android.content.Context
import com.lightdecoder.imessage.direct.DirectEngine
import com.lightdecoder.imessage.direct.DirectPrefs

/** The ":imessage" process's real parts of [FaceTimeCalls]: the library, the phone's camera and audio, the notices, the main process. */
internal object FaceTimeParts {

    fun real(app: Context): FaceTimeCalls {
        val engine = DirectEngine.get(app)
        return FaceTimeCalls(
            app = app,
            bridge = engine.callBridge,
            // The library is loaded by the time a call is joined: its session is what the join needs.
            natives = CallNatives { host -> JniCallNative(host) },
            main = object : MainLink {
                override suspend fun caller(handle: String): CallerInfo? = engine.callerLookup?.invoke(handle)
                override suspend fun log(entry: CallLogEntry) = engine.storeCall(entry)
            },
            notices = FaceTimeNotices.Poster(app),
            foreground = FaceTimeCallService.Starter(app),
            audio = FaceTimeAudioRoute(app),
            camera = FaceTimeCamera(app),
            ringback = ToneRingback(),
            settings = { settings(app) },
            phoneBusy = { FaceTimeAudioRoute.phoneInCall(app) },
            remember = { DirectPrefs.setLiveFaceTimeCall(app, it) },
            // The main process's "return to call" bar is told whenever where FaceTime stands changed.
            tell = { call -> engine.faceTimeChanged(FaceTimeNotices.state(app, call)) },
        )
    }

    /** The iMessage screen's two FaceTime settings, as they are now (files both processes read). */
    fun settings(context: Context): FaceTimeSettings =
        FaceTimeSettings(on = DirectPrefs.faceTimeOn(context), silenceUnknown = DirectPrefs.silenceUnknownFaceTime(context))
}
