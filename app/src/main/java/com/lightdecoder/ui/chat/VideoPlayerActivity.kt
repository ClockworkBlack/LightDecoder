package com.lightdecoder.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.lightdecoder.R
import java.io.File

/**
 * Plays a video from a conversation inside LightDecoder, full screen, as it
 * came (an iPhone's .mov in HEVC or H.264, an MP4): Media3's player, with
 * play/pause, seeking and the system's volume. Rotating the phone keeps it
 * playing where it was. Only files in this app's own storage are played.
 */
class VideoPlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var position = 0L
    private var playWhenReady = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)
        position = savedInstanceState?.getLong(STATE_POSITION) ?: 0L
        playWhenReady = savedInstanceState?.getBoolean(STATE_PLAYING) ?: true
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
    }

    override fun onStart() {
        super.onStart()
        val file = ownFile(this, intent.getStringExtra(EXTRA_PATH))
        if (file == null) {
            Toast.makeText(this, R.string.chat_media_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val view = findViewById<PlayerView>(R.id.videoPlayer)
        val p = ExoPlayer.Builder(this).build().also { player = it }
        view.player = p
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@VideoPlayerActivity, R.string.chat_video_cannot_play, Toast.LENGTH_LONG).show()
            }
        })
        p.seekTo(position)
        p.playWhenReady = playWhenReady
        p.prepare()
    }

    override fun onStop() {
        player?.let {
            position = it.currentPosition
            playWhenReady = it.playWhenReady
            it.release()
        }
        player = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let {
            position = it.currentPosition
            playWhenReady = it.playWhenReady
        }
        outState.putLong(STATE_POSITION, position)
        outState.putBoolean(STATE_PLAYING, playWhenReady)
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_TITLE = "title"
        private const val STATE_POSITION = "position"
        private const val STATE_PLAYING = "playing"

        /**
         * The file at [path] if it is one of this app's own, else null. Both sides are compared as
         * their real (canonical) paths: on a phone the app's data directory is reached through
         * /data/user/0, a link to /data/data, so a file's real path does not begin with
         * [android.content.pm.ApplicationInfo.dataDir] as it is written ("This attachment is not
         * available" for every video, sent or received, when only the file's side was resolved).
         */
        internal fun ownFile(context: Context, path: String?): File? {
            val file = path?.takeIf { it.isNotEmpty() }?.let(::File) ?: return null
            return try {
                val real = file.canonicalFile
                if (!real.isFile) return null
                val roots = listOfNotNull(context.applicationInfo.dataDir?.let(::File), context.filesDir, context.cacheDir)
                    .map { it.canonicalPath.trimEnd(File.separatorChar) + File.separator }
                real.takeIf { f -> roots.any { f.path.startsWith(it) } }
            } catch (e: java.io.IOException) {
                null
            }
        }

        fun intent(context: Context, file: File, title: String?): Intent =
            Intent(context, VideoPlayerActivity::class.java)
                .putExtra(EXTRA_PATH, file.path)
                .putExtra(EXTRA_TITLE, title.orEmpty())
    }
}
