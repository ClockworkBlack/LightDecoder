package com.lightdecoder.imessage.direct

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lightdecoder.R
import com.lightdecoder.ui.imessage.DirectSetupActivity
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Runs while the user is in Android's Wireless debugging screen ([AdbGrant]): watches for the
 * phone's pairing offer, asks for its six-digit code in a notification (the offer closes when its
 * screen is left, so the code cannot be typed in LightDecoder), pairs, allows SIM verification,
 * and stops. In the ":imessage" process; in the foreground so Android does not freeze it meanwhile.
 */
class AdbPairService : Service() {

    sealed class State {
        object Idle : State()
        /** Waiting for the user to tap "Pair device with pairing code". */
        object Searching : State()
        /** The pairing offer is up: waiting for its code. */
        data class CodeWanted(val port: Int) : State()
        object Working : State()
        object Done : State()
        data class Failed(val why: String) : State()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mdns: AdbMdns? = null

    @Volatile
    private var pairingPort = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.imessage_adb_channel), NotificationManager.IMPORTANCE_HIGH))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finish(State.Idle)
                return START_NOT_STICKY
            }
            ACTION_CODE -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()
                    ?: intent.getStringExtra(KEY_CODE)
                if (code != null) onCode(code)
                return START_NOT_STICKY
            }
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(State.Searching),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not go to the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        set(State.Searching)
        if (mdns == null) {
            mdns = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { address, port ->
                // The offer came up (a port) or went away (no address).
                if (address != null && port > 0) {
                    pairingPort = port
                    if (state.value !is State.Working) set(State.CodeWanted(port))
                } else if (state.value is State.CodeWanted) {
                    pairingPort = -1
                    set(State.Searching)
                }
            }.also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun onCode(code: String) {
        val port = pairingPort
        val digits = code.filter { it.isDigit() }
        if (port <= 0 || digits.length != 6) {
            set(State.Failed(getString(if (port <= 0) R.string.imessage_adb_no_offer else R.string.imessage_adb_bad_code)))
            return
        }
        set(State.Working)
        scope.launch {
            val problem = try {
                AdbGrant.pair(this@AdbPairService, port, digits)
                AdbGrant.allowSimAuth(this@AdbPairService)
            } catch (e: Exception) {
                Log.w(TAG, "pairing failed", e)
                getString(R.string.imessage_adb_pair_failed, e.message ?: e.javaClass.simpleName)
            }
            finish(if (problem == null) State.Done else State.Failed(problem))
        }
    }

    private fun set(s: State) {
        _state.value = s
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(s))
        } catch (e: SecurityException) {
            // Notifications not allowed: the setup screen shows the state too.
        }
    }

    /** Ends the watch, leaving the outcome as an ordinary notification. */
    private fun finish(s: State) {
        runCatching { mdns?.stop() }
        mdns = null
        _state.value = s
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (s is State.Done || s is State.Failed) {
            try {
                getSystemService(NotificationManager::class.java).notify(RESULT_ID, notification(s))
            } catch (e: SecurityException) {
                // As above.
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { mdns?.stop() }
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(s: State) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_chat)
        .setContentTitle(getString(R.string.imessage_adb_title))
        .setContentText(text(s))
        .setStyle(NotificationCompat.BigTextStyle().bigText(text(s)))
        .setOngoing(s is State.Searching || s is State.CodeWanted || s is State.Working)
        .setOnlyAlertOnce(s !is State.CodeWanted)
        .setAutoCancel(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, DirectSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .apply {
            if (s is State.CodeWanted) {
                val reply = PendingIntent.getService(
                    this@AdbPairService, 1, Intent(this@AdbPairService, AdbPairService::class.java).setAction(ACTION_CODE),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                addAction(
                    NotificationCompat.Action.Builder(0, getString(R.string.imessage_adb_enter_code), reply)
                        .addRemoteInput(RemoteInput.Builder(KEY_CODE).setLabel(getString(R.string.imessage_adb_code_label)).build())
                        .build(),
                )
            }
            if (s is State.Searching || s is State.CodeWanted) {
                addAction(
                    0, getString(android.R.string.cancel),
                    PendingIntent.getService(
                        this@AdbPairService, 2, Intent(this@AdbPairService, AdbPairService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }
        }
        .build()

    private fun text(s: State): String = when (s) {
        State.Idle, State.Searching -> getString(R.string.imessage_adb_searching)
        is State.CodeWanted -> getString(R.string.imessage_adb_code_wanted)
        State.Working -> getString(R.string.imessage_adb_working)
        State.Done -> getString(R.string.imessage_adb_done)
        is State.Failed -> getString(R.string.imessage_adb_failed, s.why)
    }

    companion object {
        private const val TAG = "AdbPairService"
        private const val CHANNEL = "imessage_adb"
        private const val NOTIFICATION_ID = 7303
        private const val RESULT_ID = 7304
        private const val ACTION_CODE = "com.lightdecoder.imessage.ADB_CODE"
        private const val ACTION_STOP = "com.lightdecoder.imessage.ADB_STOP"
        const val KEY_CODE = "code"

        private val _state = MutableStateFlow<State>(State.Idle)

        /** For the setup screen (same process). */
        val state: StateFlow<State> = _state

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AdbPairService::class.java))
        }

        /** The code typed on the setup screen (split screen) instead of in the notification. */
        fun submitCode(context: Context, code: String) {
            context.startService(Intent(context, AdbPairService::class.java).setAction(ACTION_CODE).putExtra(KEY_CODE, code))
        }
    }
}
