package com.lightdecoder.ui.chat

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Records a voice message into a file: AAC in an MPEG-4 container (.m4a,
 * sent as audio/mp4), mono, speech quality, what Google Messages and iPhones
 * both play. A minute is about 240 KB, so even an MMS takes a few minutes.
 */
interface VoiceRecorder {
    /** Starts recording into [file]. False if the microphone could not be used (busy, no permission). */
    fun start(file: File, maxDurationMs: Int): Boolean

    /** Stops and keeps the recording. False if nothing usable was recorded (the file is then deleted). */
    fun stop(): Boolean

    /** Stops and throws the recording away. */
    fun cancel()

    /** Called once if the recording stops by itself at the maximum length. */
    var onMaxDuration: (() -> Unit)?

    companion object {
        /** How it is made; tests replace it. */
        @Volatile
        var factory: (Context) -> VoiceRecorder = { MediaVoiceRecorder(it.applicationContext) }

        const val MIME = "audio/mp4"
        const val EXTENSION = "m4a"
    }
}

/** [VoiceRecorder] on Android's MediaRecorder. */
internal class MediaVoiceRecorder(private val context: Context) : VoiceRecorder {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    override var onMaxDuration: (() -> Unit)? = null

    override fun start(file: File, maxDurationMs: Int): Boolean {
        cancel()
        file.parentFile?.mkdirs()
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(SAMPLE_RATE)
            r.setAudioEncodingBitRate(BIT_RATE)
            r.setMaxDuration(maxDurationMs)
            r.setOutputFile(file.path)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onMaxDuration?.invoke()
            }
            r.prepare()
            r.start()
            recorder = r
            this.file = file
            true
        } catch (e: Exception) {
            // IllegalStateException, IOException, or RuntimeException("start failed") when the mic is busy.
            r.release()
            file.delete()
            false
        }
    }

    override fun stop(): Boolean {
        val r = recorder ?: return false
        val f = file
        recorder = null
        file = null
        val ok = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            // Stopped before any audio arrived: MediaRecorder throws and the file is unusable.
            false
        } finally {
            r.release()
        }
        if (!ok || f == null || !f.isFile || f.length() == 0L) {
            f?.delete()
            return false
        }
        return true
    }

    override fun cancel() {
        val r = recorder ?: return
        val f = file
        recorder = null
        file = null
        try {
            r.stop()
        } catch (e: RuntimeException) {
            // Nothing recorded yet: nothing to keep anyway.
        } finally {
            r.release()
        }
        f?.delete()
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val BIT_RATE = 32_000
    }
}
