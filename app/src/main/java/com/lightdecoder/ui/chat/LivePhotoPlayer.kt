package com.lightdecoder.ui.chat

import android.content.Context
import android.net.Uri
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * Plays a Live Photo's moving part over its still, as an iPhone does while it is held: once, with
 * sound, in a [TextureView] laid over the picture, sized to the video's own shape; [stop] (the finger
 * went up) or its end takes it away and releases the player. One at a time; main thread only.
 */
class LivePhotoPlayer(private val context: Context) {
    private var player: ExoPlayer? = null
    private var surface: TextureView? = null
    private var host: FrameLayout? = null
    private var onEnded: (() -> Unit)? = null

    val playing: Boolean get() = player != null

    /** Plays [file] over [over] (laid into [host], the page); [ended] runs once it stops, however it stops. */
    fun play(host: FrameLayout, over: View, file: File, ended: () -> Unit) {
        stop()
        this.host = host
        onEnded = ended
        val view = TextureView(context)
        surface = view
        host.addView(view, host.indexOfChild(over) + 1, FrameLayout.LayoutParams(over.width.coerceAtLeast(1), over.height.coerceAtLeast(1)))
        val p = try {
            ExoPlayer.Builder(context).build()
        } catch (e: RuntimeException) {
            stop()
            return
        }
        player = p
        p.setVideoTextureView(view)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) stop()
            }

            override fun onPlayerError(error: PlaybackException) = stop()

            override fun onVideoSizeChanged(size: VideoSize) {
                val (w, h) = fit(size.width, size.height, over.width, over.height)
                view.layoutParams = FrameLayout.LayoutParams(w, h, android.view.Gravity.CENTER)
            }
        })
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        p.prepare()
        p.playWhenReady = true
    }

    /** Takes the movie away (the finger went up, it ended, or the page went). */
    fun stop() {
        val p = player
        player = null
        try {
            p?.release()
        } catch (e: RuntimeException) {
            // Released all the same.
        }
        surface?.let { host?.removeView(it) }
        surface = null
        host = null
        onEnded?.also { onEnded = null }?.invoke()
    }

    companion object {
        /** The largest size of a [w] by [h] video that fits [maxW] by [maxH], its shape kept. */
        fun fit(w: Int, h: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
            if (w <= 0 || h <= 0 || maxW <= 0 || maxH <= 0) return maxW.coerceAtLeast(1) to maxH.coerceAtLeast(1)
            val scale = minOf(maxW.toFloat() / w, maxH.toFloat() / h)
            return (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
        }
    }
}
