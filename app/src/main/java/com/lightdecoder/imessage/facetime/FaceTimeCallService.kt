package com.lightdecoder.imessage.facetime

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What keeps a FaceTime call up while its screen is away: a foreground service of the ":imessage"
 * process, under the "in call" notice ([FaceTimeNotices.ongoing], with Hang up), of the microphone
 * and camera kinds (each only with its permission granted) and the phone-call kind (left out where
 * Android refuses it). Started only by the call screen, while it is in front, when a call is placed
 * or answered; it stops itself when [FaceTimeCalls] has no call holding the line any more.
 *
 * It holds nothing of the call itself (that is [FaceTimeCalls]'s): it shows the notice, tells the
 * call engine how the screen is turned (the camera's orientation), and darkens the screen at the
 * ear in an audio call on the earpiece.
 */
class FaceTimeCallService : Service() {

    private val scope = MainScope()
    private val calls by lazy { FaceTimeCalls.get(this) }
    private var proximity: PowerManager.WakeLock? = null
    private var shownFor: Call? = null
    private var shownEnding = false

    private val displays = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == android.view.Display.DEFAULT_DISPLAY) tellRotation()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // In the foreground before anything else: Android takes the process down if a service started
        // for the foreground stops without ever having gone there.
        foreground(heldCall() ?: calls.lingering.value ?: placeholder(), ending = heldCall() == null && calls.lingering.value != null)
        scope.launch {
            combine(calls.state, calls.media, calls.lingering) { call, media, lingering -> Triple(call, media, lingering) }.collect { (call, media, lingering) ->
                if (call != null && call.live && !call.incomingRing) {
                    show(call, ending = false)
                    holdProximity(call.phase == Phase.ACTIVE && !media.cameraOn && !media.remoteVideo && media.route == Route.EARPIECE)
                } else if (lingering != null) {
                    // Left while the library was still joining it (the join may yet open the microphone): the notice stays until that returned.
                    holdProximity(false)
                    show(lingering, ending = true)
                } else {
                    stopNow()
                }
            }
        }
        try {
            getSystemService(DisplayManager::class.java)?.registerDisplayListener(displays, Handler(Looper.getMainLooper()))
        } catch (e: RuntimeException) {
            FaceTimeLog.note("the screen's rotation is not watched (${e::class.java.simpleName})")
        }
        tellRotation()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val call = heldCall() ?: calls.lingering.value
        if (call == null) {
            // Nothing to hold (the call ended as this started): its notice was shown, now it goes.
            foreground(placeholder())
            stopNow()
            return START_NOT_STICKY
        }
        // Again on every start: with a permission just granted, its kind is added.
        foreground(call, ending = heldCall() == null)
        started.complete(Unit)
        return START_NOT_STICKY
    }

    /** The call that holds the line (placed, joining or joined), if there is one. */
    private fun heldCall(): Call? = calls.state.value?.takeIf { it.live && !it.incomingRing }

    /** What the notice says for the moment there is no call to name. */
    private fun placeholder(): Call = Call(
        guid = "", peer = "", name = getString(com.lightdecoder.R.string.facetime_title), video = false, outgoing = true,
        phase = Phase.CONNECTING, startedMs = System.currentTimeMillis(),
    )

    /** Into the foreground under [call]'s notice; again whenever a permission came (the kinds follow them). */
    private fun foreground(call: Call, ending: Boolean = false) {
        val notice = FaceTimeNotices.ongoing(this, call, ending)
        shownFor = call
        shownEnding = ending
        val kinds = kinds(this)
        try {
            ServiceCompat.startForeground(this, FaceTimeNotices.ID_ONGOING, notice, kinds or phoneCallKind())
        } catch (e: Exception) {
            // The phone-call kind refused (not every Android gives it to an app that is no dialer): without it.
            try {
                ServiceCompat.startForeground(this, FaceTimeNotices.ID_ONGOING, notice, kinds)
            } catch (e2: Exception) {
                FaceTimeLog.note("the in-call notice could not go to the foreground (${e2::class.java.simpleName})")
            }
        }
    }

    /** The notice again when what it says changed (calling, connecting, in the call with its clock). */
    @android.annotation.SuppressLint("MissingPermission") // without the permission Android just does not show it
    private fun show(call: Call, ending: Boolean) {
        val before = shownFor
        if (before != null && shownEnding == ending && before.guid == call.guid && before.phase == call.phase && before.video == call.video && before.name == call.name) return
        shownFor = call
        shownEnding = ending
        try {
            NotificationManagerCompat.from(this).notify(FaceTimeNotices.ID_ONGOING, FaceTimeNotices.ongoing(this, call, ending))
        } catch (e: RuntimeException) {
            // Notifications not allowed.
        }
    }

    private fun stopNow() {
        holdProximity(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun tellRotation() {
        @Suppress("DEPRECATION")
        val rotation = try {
            getSystemService(DisplayManager::class.java)?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation
        } catch (e: RuntimeException) {
            null
        } ?: return
        calls.displayRotation(rotation)
    }

    /** The screen goes dark while the phone is at the ear (an audio call on the earpiece), and comes back away from it. */
    private fun holdProximity(hold: Boolean) {
        try {
            if (hold) {
                val lock = proximity ?: run {
                    val pm = getSystemService(PowerManager::class.java) ?: return
                    if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
                    pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "lightdecoder:facetime-proximity").also { proximity = it }
                }
                if (!lock.isHeld) lock.acquire(MAX_PROXIMITY_MS)
            } else {
                proximity?.takeIf { it.isHeld }?.release()
            }
        } catch (e: RuntimeException) {
            FaceTimeLog.note("the proximity lock could not be ${if (hold) "taken" else "given back"} (${e::class.java.simpleName})")
        }
    }

    override fun onDestroy() {
        holdProximity(false)
        try {
            getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displays)
        } catch (e: RuntimeException) {
            // Not registered.
        }
        scope.cancel()
        started = CompletableDeferred()
        super.onDestroy()
    }

    /** Starts and stops the service for [FaceTimeCalls]. */
    class Starter(context: Context) : CallForeground {
        private val app = context.applicationContext

        override fun start() {
            try {
                ContextCompat.startForegroundService(app, Intent(app, FaceTimeCallService::class.java))
            } catch (e: RuntimeException) {
                // Not allowed from where the app is now (no screen in front): the call goes on as far as Android lets it.
                FaceTimeLog.note("the in-call service could not be started (${e::class.java.simpleName})")
            }
        }

        override fun stop() {
            // It stops itself once it sees there is no call; this is for one that never saw a call.
            runCatching { app.stopService(Intent(app, FaceTimeCallService::class.java)) }
        }

        override suspend fun awaitStarted(timeoutMs: Long) {
            withTimeoutOrNull(timeoutMs) { started.await() }
        }
    }

    companion object {
        /** A proximity lock is never held longer than a call could reasonably last. */
        private const val MAX_PROXIMITY_MS = 4 * 60 * 60 * 1000L

        /** Completed once the service is in the foreground; made anew when it is destroyed. */
        @Volatile
        private var started = CompletableDeferred<Unit>()

        /**
         * The foreground kinds a call may have now: the microphone's and the camera's, each only
         * with its permission granted (Android refuses the service otherwise).
         */
        fun kinds(context: Context): Int {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
            var kinds = 0
            if (granted(context, Manifest.permission.RECORD_AUDIO)) kinds = kinds or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (granted(context, Manifest.permission.CAMERA)) kinds = kinds or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            return kinds
        }

        private fun phoneCallKind(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0

        private fun granted(context: Context, permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
