package com.lightdecoder.imessage.facetime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * The phone's audio during a call. The library records and plays the call itself, as voice
 * communication; what is the app's to do is the phone's audio mode and where the sound goes, as
 * OpenBubbles 2.0.0 does without Android's Telecom: the communication mode while the call is joined
 * and the mode as it was after; the earpiece, the speaker, a wired or a Bluetooth headset through
 * Android 12's communication device (before that: the speakerphone switch and Bluetooth's call
 * audio). Other apps' sound is asked to pause for the call (audio focus), and may resume after.
 */
class FaceTimeAudioRoute(context: Context) : CallAudio {
    private val app = context.applicationContext
    private val manager: AudioManager? = app.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var entered = false
    private var modeBefore = AudioManager.MODE_NORMAL
    private var speakerBefore = false
    private var scoStarted = false
    private var focus: AudioFocusRequest? = null
    private var devices: AudioDeviceCallback? = null

    override fun routes(): List<Route> {
        val am = manager ?: return listOf(Route.SPEAKER)
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.availableCommunicationDevices.map { it.type }
        } else {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        }
        val found = LinkedHashSet<Route>()
        if (types.any { it == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }) found += Route.EARPIECE
        found += Route.SPEAKER
        if (types.any(::isWired)) found += Route.WIRED
        if (types.any(::isBluetooth)) found += Route.BLUETOOTH
        return found.toList()
    }

    @Synchronized
    override fun enter(changed: () -> Unit) {
        val am = manager ?: return
        if (entered) return
        entered = true
        modeBefore = am.mode
        @Suppress("DEPRECATION")
        speakerBefore = am.isSpeakerphoneOn
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setOnAudioFocusChangeListener({ }, handler)
            .build()
        focus = request
        runCatching { am.requestAudioFocus(request) }
        // A headset plugged in or taken off, Bluetooth coming or going: the route follows.
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = changed()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = changed()
        }
        devices = callback
        runCatching { am.registerAudioDeviceCallback(callback, handler) }
    }

    @Synchronized
    override fun route(route: Route): Boolean {
        val am = manager ?: return false
        if (!entered) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val wanted = am.availableCommunicationDevices.firstOrNull { device ->
                when (route) {
                    Route.EARPIECE -> device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    Route.SPEAKER -> device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    Route.WIRED -> isWired(device.type)
                    Route.BLUETOOTH -> isBluetooth(device.type)
                }
            } ?: return false
            return try {
                am.setCommunicationDevice(wanted)
            } catch (e: RuntimeException) {
                false
            }
        }
        @Suppress("DEPRECATION")
        when (route) {
            Route.BLUETOOTH -> {
                am.isSpeakerphoneOn = false
                if (!scoStarted) {
                    scoStarted = true
                    runCatching {
                        am.startBluetoothSco()
                        am.isBluetoothScoOn = true
                    }
                }
            }
            else -> {
                stopSco(am)
                am.isSpeakerphoneOn = route == Route.SPEAKER
            }
        }
        return true
    }

    @Synchronized
    override fun leave() {
        val am = manager ?: return
        if (!entered) return
        entered = false
        devices?.let { runCatching { am.unregisterAudioDeviceCallback(it) } }
        devices = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { am.clearCommunicationDevice() }
        } else {
            stopSco(am)
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = speakerBefore
        }
        // As it was: never left in a call's mode, and never put into another call's that ended meanwhile.
        am.mode = if (modeBefore == AudioManager.MODE_IN_CALL || modeBefore == AudioManager.MODE_IN_COMMUNICATION) AudioManager.MODE_NORMAL else modeBefore
        focus?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        focus = null
    }

    @Suppress("DEPRECATION")
    private fun stopSco(am: AudioManager) {
        if (!scoStarted) return
        scoStarted = false
        runCatching {
            am.isBluetoothScoOn = false
            am.stopBluetoothSco()
        }
    }

    private fun isWired(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || type == AudioDeviceInfo.TYPE_USB_HEADSET

    private fun isBluetooth(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_HEARING_AID

    companion object {
        /** [AudioDeviceInfo.TYPE_BLE_HEADSET] (Android 12). */
        private const val TYPE_BLE_HEADSET = 26

        /** Whether the phone is in a call of someone else's (a cellular call, another app's): two calls would fight over its audio. */
        fun phoneInCall(context: Context): Boolean {
            val mode = context.getSystemService(AudioManager::class.java)?.mode ?: return false
            return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE
        }
    }
}

/** The ringback a caller hears while the other side rings: the phone's own tone, on the call's stream. */
class ToneRingback : CallRingback {
    private var tone: ToneGenerator? = null

    @Synchronized
    override fun start() {
        if (tone != null) return
        tone = try {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, VOLUME).also { it.startTone(ToneGenerator.TONE_SUP_RINGTONE) }
        } catch (e: RuntimeException) {
            // No tone to be had (the audio service is busy): the call rings all the same.
            null
        }
    }

    @Synchronized
    override fun stop() {
        val t = tone ?: return
        tone = null
        runCatching {
            t.stopTone()
            t.release()
        }
    }

    private companion object {
        const val VOLUME = 80
    }
}
