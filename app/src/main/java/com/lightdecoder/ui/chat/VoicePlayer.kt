package com.lightdecoder.ui.chat

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Plays voice messages (and any other audio in a conversation) inside the
 * bubble: one at a time, play/pause, and a position the bubble's bar shows
 * and can move. Lengths learnt by playing are remembered, so a bubble shows
 * its length again after it has been played once.
 *
 * Main thread only.
 */
class VoicePlayer(
    private val makePlayer: () -> MediaPlayer = ::MediaPlayer,
) {
    /** What is playing: [key] is the part's ("<message id>/<part id>"), null when nothing is loaded. */
    data class State(
        val key: String? = null,
        val playing: Boolean = false,
        val positionMs: Int = 0,
        val durationMs: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val durations = HashMap<String, Int>()
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!_state.value.playing) return
            _state.value = _state.value.copy(positionMs = safePosition(p))
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** The length of [key] if it has been played (or is loaded); 0 if not known yet. */
    fun durationOf(key: String): Int = durations[key] ?: 0

    /**
     * Plays [file] as [key], or pauses it if [key] is playing, or carries on
     * from where it was paused. Another voice message playing stops first.
     * False if the file could not be played.
     */
    fun toggle(key: String, file: File): Boolean {
        val current = _state.value
        val p = player
        if (p != null && current.key == key) {
            if (current.playing) pause() else resume(p)
            return true
        }
        return play(key, file)
    }

    /** Moves [key] to [positionMs] if it is the one loaded. */
    fun seekTo(key: String, positionMs: Int) {
        val p = player ?: return
        if (_state.value.key != key) return
        val to = positionMs.coerceIn(0, maxOf(0, _state.value.durationMs))
        try {
            p.seekTo(to)
        } catch (e: IllegalStateException) {
            return
        }
        _state.value = _state.value.copy(positionMs = to)
    }

    fun pause() {
        val p = player ?: return
        if (!_state.value.playing) return
        try {
            p.pause()
        } catch (e: IllegalStateException) {
            // Already stopped.
        }
        handler.removeCallbacks(tick)
        _state.value = _state.value.copy(playing = false, positionMs = safePosition(p))
    }

    /** Stops and lets go of the player (the screen is going away). */
    fun release() {
        handler.removeCallbacks(tick)
        player?.let { runCatching { it.release() } }
        player = null
        _state.value = State()
    }

    private fun play(key: String, file: File): Boolean {
        release()
        val p = makePlayer()
        return try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            p.setDataSource(file.path)
            p.prepare()
            val duration = p.duration.coerceAtLeast(0)
            durations[key] = duration
            p.setOnCompletionListener {
                handler.removeCallbacks(tick)
                // Back to the start, ready to be played again.
                _state.value = _state.value.copy(playing = false, positionMs = 0)
                runCatching { it.seekTo(0) }
            }
            p.start()
            player = p
            _state.value = State(key, playing = true, positionMs = 0, durationMs = duration)
            handler.postDelayed(tick, TICK_MS)
            true
        } catch (e: Exception) {
            // IOException (unreadable), IllegalStateException, IllegalArgumentException.
            runCatching { p.release() }
            false
        }
    }

    private fun resume(p: MediaPlayer) {
        try {
            p.start()
        } catch (e: IllegalStateException) {
            return
        }
        _state.value = _state.value.copy(playing = true)
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
    }

    private fun safePosition(p: MediaPlayer): Int = try {
        p.currentPosition
    } catch (e: IllegalStateException) {
        _state.value.positionMs
    }

    companion object {
        private const val TICK_MS = 200L

        /** The key of a media part. */
        fun keyOf(messageId: String, partId: String) = "$messageId/$partId"

        /** "1:05" */
        fun clock(ms: Int): String {
            val total = (ms.coerceAtLeast(0) + 500) / 1000
            return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
        }
    }
}
