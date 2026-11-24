package com.lightdecoder.imessage.facetime

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor

/**
 * A cellular call during a FaceTime call, when Telecom does not have the FaceTime call (SPEC2 B3.2):
 * Android's dialer knows nothing of it then, so the phone's audio mode is watched while a call holds
 * the line. A phone call ringing ([AudioManager.MODE_RINGTONE]) shows "Phone call incoming" on the
 * call screen; one answered ([AudioManager.MODE_IN_CALL]) mutes the FaceTime microphone at once (the
 * library's microphone keeps running: the call would hear the phone call) and then ends the
 * FaceTime call, "Ended for a phone call". Only a change of the mode after the watch began counts
 * (what it was then is the phone's own state, never taken for a call that came). With Telecom having
 * the call, Telecom's hold or disconnect does this instead ([telecomHas]).
 */
class CellularWatch(
    private val source: Source,
    /** "Phone call incoming" on the call screen, or not. */
    private val banner: (Boolean) -> Unit,
    /** Mutes the FaceTime microphone, at once. */
    private val mute: () -> Unit,
    /** Ends the FaceTime call, "Ended for a phone call". */
    private val end: () -> Unit,
    /** Whether Telecom has the call now (nothing is done here then). */
    private val telecomHas: () -> Boolean = { false },
) {

    /** The phone's audio mode, and its changes. */
    interface Source {
        fun mode(): Int

        /** Calls [changed] with the mode on every change until [stop] (Android 12's listener, else a look every second). */
        fun start(changed: (Int) -> Unit)
        fun stop()
    }

    /** What a mode means here. */
    enum class Action { NONE, BANNER, CLEAR, END }

    private val lock = Any()
    private var watching = false
    private var last = AudioManager.MODE_NORMAL
    private var ended = false
    private var shown = false

    /** Begins watching (a call holds the line now). */
    fun start() {
        synchronized(lock) {
            if (watching) return
            watching = true
            ended = false
            shown = false
            last = runCatching { source.mode() }.getOrDefault(AudioManager.MODE_NORMAL)
        }
        runCatching { source.start(::onMode) }
    }

    /** Stops watching (no call holds the line); a banner shown goes. */
    fun stop() {
        val hide = synchronized(lock) {
            if (!watching) return
            watching = false
            shown.also { shown = false }
        }
        runCatching { source.stop() }
        if (hide) runCatching { banner(false) }
    }

    /** The mode is [mode] now. */
    fun onMode(mode: Int) {
        val action = synchronized(lock) {
            if (!watching) return
            val before = last
            last = mode
            if (mode == before) return
            decide(mode).also { a ->
                when (a) {
                    Action.END -> if (ended) return else ended = true
                    Action.BANNER -> shown = true
                    Action.CLEAR -> if (!shown) return else shown = false
                    Action.NONE -> return
                }
            }
        }
        if (runCatching(telecomHas).getOrDefault(false) && action != Action.CLEAR) return
        when (action) {
            Action.BANNER -> runCatching { banner(true) }
            Action.CLEAR -> runCatching { banner(false) }
            Action.END -> {
                // The microphone first, at once: the FaceTime call must not hear the phone call.
                runCatching(mute)
                runCatching { banner(false) }
                runCatching(end)
            }
            Action.NONE -> Unit
        }
    }

    companion object {
        /** A phone call rings: the banner; a phone call is answered: the call ends; anything else: the banner goes. */
        fun decide(mode: Int): Action = when (mode) {
            AudioManager.MODE_RINGTONE -> Action.BANNER
            AudioManager.MODE_IN_CALL -> Action.END
            else -> Action.CLEAR
        }

        /** How often the mode is looked at before Android 12 (which tells of every change). */
        const val POLL_MS = 1_000L
    }

    /** The phone's own [AudioManager]. */
    class Real(context: Context) : Source {
        private val manager: AudioManager? = context.applicationContext.getSystemService(AudioManager::class.java)
        private val handler = Handler(Looper.getMainLooper())
        private var listener: Any? = null
        private var poll: Runnable? = null

        override fun mode(): Int = manager?.mode ?: AudioManager.MODE_NORMAL

        @Synchronized
        override fun start(changed: (Int) -> Unit) {
            val am = manager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val l = AudioManager.OnModeChangedListener { mode -> changed(mode) }
                listener = l
                am.addOnModeChangedListener(Executor { handler.post(it) }, l)
            } else {
                val r = object : Runnable {
                    override fun run() {
                        runCatching { changed(am.mode) }
                        handler.postDelayed(this, POLL_MS)
                    }
                }
                poll = r
                handler.postDelayed(r, POLL_MS)
            }
        }

        @Synchronized
        override fun stop() {
            val am = manager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (listener as? AudioManager.OnModeChangedListener)?.let { runCatching { am.removeOnModeChangedListener(it) } }
            }
            listener = null
            poll?.let(handler::removeCallbacks)
            poll = null
        }
    }
}
