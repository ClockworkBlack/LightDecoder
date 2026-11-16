package com.lightdecoder.ui.voice

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.annotation.VisibleForTesting

/**
 * Reads messages aloud. The thread screen holds one; tests swap in a fake
 * through [factory], so nothing here needs a real text-to-speech engine.
 *
 * All calls and all [Listener] callbacks happen on the main thread. Nothing
 * here logs what it is asked to say: it is message text.
 */
interface MessageReader {

    /** A text-to-speech engine is installed and has not failed to start. Cheap: starts nothing. */
    val isAvailable: Boolean

    /** Something is being said, or waits to be. */
    val isSpeaking: Boolean

    /**
     * Says [text]. [interrupt] true drops whatever is being said or queued
     * first (the user asked for this one); false queues it after the rest.
     * The engine is started on the first call.
     */
    fun speak(text: String, interrupt: Boolean)

    /** Stops speaking and forgets the queue. The engine stays up for the next [speak]. */
    fun stop()

    /** Stops and releases the engine. The reader says nothing after this. */
    fun shutdown()

    interface Listener {
        /** [MessageReader.isSpeaking] changed. */
        fun onSpeakingChanged(speaking: Boolean)

        /** No engine, or it failed to start: reading aloud is not available after all. */
        fun onUnavailable()
    }

    companion object {
        /** Tests only: builds the reader instead of the real text-to-speech one. */
        @VisibleForTesting
        @Volatile
        var factory: ((Context, Listener) -> MessageReader)? = null

        fun create(context: Context, listener: Listener): MessageReader =
            factory?.invoke(context, listener) ?: TtsMessageReader(context, listener)
    }
}

/**
 * [MessageReader] on Android's [TextToSpeech]. The engine is bound on the
 * first [speak] only (binding starts another app's service), and what is
 * asked for before it is ready waits for it.
 */
internal class TtsMessageReader(context: Context, private val listener: MessageReader.Listener) : MessageReader {

    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var shutDown = false
    private var nextId = 0
    private var lastReported = false

    /** Asked for before the engine was ready: (text, interrupt). */
    private val pending = ArrayList<Pair<String, Boolean>>()

    /** Utterances handed to the engine and not finished yet. */
    private val active = HashSet<String>()

    private val engineInstalled: Boolean by lazy {
        runCatching {
            context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
                .isNotEmpty()
        }.getOrDefault(false)
    }

    override val isAvailable: Boolean get() = !failed && !shutDown && engineInstalled

    override val isSpeaking: Boolean get() = active.isNotEmpty() || pending.isNotEmpty()

    override fun speak(text: String, interrupt: Boolean) {
        if (shutDown || failed || text.isBlank()) return
        if (!engineInstalled) {
            fail()
            return
        }
        if (ready) {
            say(text, interrupt)
        } else {
            if (interrupt) pending.clear()
            pending.add(text to interrupt)
            if (tts == null) {
                // onInit can come back inside the constructor (no engine to bind), before
                // [tts] is assigned: handle it on a later turn of the main loop.
                tts = TextToSpeech(context) { status -> main.post { onInit(status) } }
            }
        }
        report()
    }

    private fun onInit(status: Int) {
        if (shutDown) return
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            fail()
            return
        }
        ready = true
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = finished(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finished(utteranceId)
            override fun onError(utteranceId: String, errorCode: Int) = finished(utteranceId)
            override fun onStop(utteranceId: String, interrupted: Boolean) = finished(utteranceId)
        })
        val queued = pending.toList()
        pending.clear()
        for ((text, interrupt) in queued) say(text, interrupt)
        report()
    }

    private fun say(text: String, interrupt: Boolean) {
        val engine = tts ?: return
        val id = "ld-" + nextId++
        if (interrupt) active.clear()
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (engine.speak(text, mode, null, id) == TextToSpeech.SUCCESS) active.add(id)
    }

    /** Progress callbacks come on the engine's binder thread. */
    private fun finished(utteranceId: String) {
        main.post {
            if (active.remove(utteranceId)) report()
        }
    }

    private fun fail() {
        val wasFailed = failed
        failed = true
        ready = false
        pending.clear()
        active.clear()
        tts?.shutdown()
        tts = null
        report()
        if (!wasFailed) listener.onUnavailable()
    }

    override fun stop() {
        pending.clear()
        active.clear()
        if (ready) tts?.stop()
        report()
    }

    override fun shutdown() {
        if (shutDown) return
        stop()
        shutDown = true
        ready = false
        tts?.shutdown()
        tts = null
    }

    private fun report() {
        val speaking = isSpeaking
        if (speaking == lastReported || shutDown) return
        lastReported = speaking
        listener.onSpeakingChanged(speaking)
    }
}
