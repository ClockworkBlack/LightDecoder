package com.lightdecoder.ui.chat

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.MarginPageTransformer
import androidx.viewpager2.widget.ViewPager2
import com.lightdecoder.R
import com.lightdecoder.archive.MediaCache
import com.lightdecoder.archive.MediaRow
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.chat.ChatBackend
import com.lightdecoder.chat.ChatService
import com.lightdecoder.databinding.ActivityPictureGalleryBinding
import com.lightdecoder.databinding.ItemGalleryPageBinding
import com.lightdecoder.relay.RelayException
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.ViewerActivity
import com.lightdecoder.ui.relay.relayAttempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A conversation's pictures, full screen and dark, as a phone's own
 * messaging app shows them: swipe sideways for the conversation's other
 * pictures, pinch or double-tap to zoom, drag to look around, tap to hide
 * or show the bars. Opens at the picture that was tapped
 * ([intent]); Share hands the picture to another app, and "Save, details
 * and effects" opens it in LightDecoder's sticker viewer.
 *
 * Pictures come from the conversation's archive of [ChatService]
 * ([EXTRA_SERVICE]): what is on this phone already shows at once (the
 * thumbnail first, then the picture); the rest is downloaded through the
 * link, which needs it connected.
 */
class PictureGalleryActivity : AppCompatActivity() {

    /** One picture of the conversation. */
    internal class Picture(val message: MessageRow, val part: MediaRow)

    private lateinit var binding: ActivityPictureGalleryBinding
    private lateinit var backend: ChatBackend
    private var conversationTitle = ""
    private var pictures: List<Picture> = emptyList()
    private var barsShown = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION") // Before Android 15 the bars are not see-through by themselves.
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        binding = ActivityPictureGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.toolbar.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            binding.counter.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = bars.bottom + (24 * resources.displayMetrics.density).toInt()
            }
            insets
        }

        val service = RelayIntents.serviceOf(intent)
        backend = ScreenBackends.get(this, service)
        conversationTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val conversationId = intent.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID).orEmpty()
        val messageId = intent.getStringExtra(RelayIntents.EXTRA_MESSAGE_ID).orEmpty()
        val partId = intent.getStringExtra(EXTRA_PART_ID).orEmpty()
        val restoredIndex = savedInstanceState?.getInt(STATE_INDEX, -1) ?: -1

        binding.pager.setPageTransformer(MarginPageTransformer((PAGE_GAP_DP * resources.displayMetrics.density).toInt()))
        binding.pager.offscreenPageLimit = 1
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = showPosition(position)
        })

        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { picturesOf(backend, conversationId) }.getOrDefault(emptyList())
            }
            // The tapped picture may be one the list does not hold (a message still being sent): show it alone.
            var start = found.indexOfFirst { it.message.id == messageId && it.part.partId == partId }
            pictures = if (start >= 0 || messageId.isEmpty()) {
                found
            } else {
                val single = withContext(Dispatchers.IO) {
                    runCatching { backend.repo.message(messageId) }.getOrNull()
                        ?.let { row -> row.shownMedia().firstOrNull { it.partId == partId }?.let { Picture(row, it) } }
                }
                start = 0
                listOfNotNull(single)
            }
            if (pictures.isEmpty()) {
                Toast.makeText(this@PictureGalleryActivity, R.string.chat_media_unavailable, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            binding.pager.adapter = Pages()
            val index = if (restoredIndex in pictures.indices) restoredIndex else start.coerceIn(pictures.indices)
            binding.pager.setCurrentItem(index, false)
            showPosition(index)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_INDEX, binding.pager.currentItem)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.gallery_actions, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val picture = pictures.getOrNull(binding.pager.currentItem)
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.action_gallery_share -> picture?.let { withFile(it) { file -> share(file, it.part.mime) } }
            R.id.action_gallery_add_sticker -> picture?.let { p ->
                withFile(p) { file ->
                    lifecycleScope.launch {
                        val added = withContext(Dispatchers.IO) {
                            runCatching {
                                if (file.length() > MAX_READ) null
                                else com.lightdecoder.sticker.StickerLibrary.addPicture(applicationContext, file.readBytes())
                            }.getOrNull()
                        }
                        toast(getString(if (added != null) R.string.sticker_added_to_mine else R.string.sticker_add_failed))
                    }
                }
            }
            R.id.action_gallery_details -> picture?.let { p ->
                withFile(p) { file ->
                    try {
                        startActivity(ViewerActivity.intentForFile(this, file, p.part.mime, senderOf(p)))
                    } catch (e: IllegalArgumentException) {
                        toast(getString(R.string.chat_media_unavailable))
                    }
                }
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun showPosition(position: Int) {
        // Another page: a Live Photo playing on the last one stops.
        if (livePlayer.playing) livePlayer.stop()
        val picture = pictures.getOrNull(position) ?: return
        // Through the action bar: set on the toolbar alone, the activity's own title could replace it.
        supportActionBar?.title = senderOf(picture)
        supportActionBar?.subtitle = DateUtils.formatDateTime(
            this, picture.message.timestampMs,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
        )
        binding.counter.text = getString(R.string.gallery_counter, position + 1, pictures.size)
        binding.counter.visibility = if (pictures.size > 1 && barsShown) View.VISIBLE else View.GONE
    }

    private fun senderOf(picture: Picture): String = when {
        picture.message.isOutgoing -> getString(R.string.chat_you)
        picture.message.senderName.isNotBlank() -> picture.message.senderName
        else -> conversationTitle
    }

    /** Tap: the bars (and the system bars) go, for nothing but the picture; tap again to bring them back. */
    private fun toggleBars() {
        barsShown = !barsShown
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (barsShown) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        binding.toolbar.animate().alpha(if (barsShown) 1f else 0f).withEndAction {
            binding.toolbar.visibility = if (barsShown) View.VISIBLE else View.INVISIBLE
        }.withStartAction { binding.toolbar.visibility = View.VISIBLE }.start()
        binding.counter.visibility = if (barsShown && pictures.size > 1) View.VISIBLE else View.GONE
    }

    // --- files ------------------------------------------------------------------------------

    /** The picture's file on this phone, downloading it first if need be; [then] runs on the main thread. */
    private fun withFile(picture: Picture, then: (File) -> Unit) {
        lifecycleScope.launch {
            val file = localFile(picture.part) ?: run {
                relayAttempt { backend.mediaFile(picture.message.id, picture.part.partId, thumbnail = picture.part.needsFullSize) }
                    .getOrElse {
                        toast(loadFailure(it))
                        return@launch
                    }
            }
            if (file == null) toast(getString(R.string.chat_media_unavailable)) else then(file)
        }
    }

    private fun share(file: File, mime: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime.ifBlank { "image/*" }
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(null, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.action_share)))
        } catch (e: IllegalArgumentException) {
            toast(getString(R.string.share_failed))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.chat_no_app))
        }
    }

    private fun loadFailure(t: Throwable): String =
        if (ChatErrors.codeOf(t) == RelayException.NOT_CONNECTED) getString(ServiceUi.mediaNeedsConnection(backend.service, ServiceUi.direct(this, backend.service)))
        else ChatErrors.message(this, backend.service, t)

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // --- pages ------------------------------------------------------------------------------

    private inner class Holder(val page: ItemGalleryPageBinding) : RecyclerView.ViewHolder(page.root) {
        var job: Job? = null
        var liveJob: Job? = null
    }

    private inner class Pages : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = pictures.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val page = ItemGalleryPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            page.picture.onTap = { toggleBars() }
            page.root.setOnClickListener { toggleBars() }
            return Holder(page)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.job?.cancel()
            val page = holder.page
            page.picture.setImageDrawable(null)
            page.message.visibility = View.GONE
            page.loading.visibility = View.VISIBLE
            val picture = pictures[position]
            val side = maxSide()
            bindLive(holder, picture)
            holder.job = lifecycleScope.launch {
                // What is on this phone already first: a quick, small stand-in.
                val standIn = withContext(Dispatchers.IO) { decodeStandIn(picture.part) }
                if (standIn != null) page.picture.setImageBitmap(standIn)
                val result = withContext(Dispatchers.IO) { loadFull(picture, side) }
                page.loading.visibility = View.GONE
                result.fold(
                    onSuccess = { bitmap -> page.picture.setImageBitmap(bitmap) },
                    onFailure = { e ->
                        if (standIn == null) {
                            page.message.text = loadFailure(e)
                            page.message.visibility = View.VISIBLE
                        }
                    },
                )
            }
        }

        override fun onViewRecycled(holder: Holder) {
            holder.job?.cancel()
            holder.liveJob?.cancel()
            if (livePlayer.playing) livePlayer.stop()
            holder.page.picture.setImageDrawable(null)
        }
    }

    // --- Live Photos ------------------------------------------------------------------------

    private val livePlayer by lazy { LivePhotoPlayer(this) }

    override fun onPause() {
        super.onPause()
        if (livePlayer.playing) livePlayer.stop()
    }

    /**
     * A Live Photo's page: "LIVE" on it, and touch and hold plays its moving part, fetched only
     * then (the usual download, with its caps and its progress on the badge), never before.
     */
    private fun bindLive(holder: Holder, picture: Picture) {
        val page = holder.page
        val motion = picture.message.motionOf(picture.part.partId)
        page.live.text = getString(R.string.live_photo_badge)
        page.live.visibility = if (motion != null) View.VISIBLE else View.GONE
        page.picture.zoomSuspended = false
        if (motion == null) {
            page.picture.onHold = null
            page.picture.onRelease = null
            return
        }
        page.picture.contentDescription = getString(R.string.gallery_picture) + ", " + getString(R.string.live_photo_hint)
        var held = false
        page.picture.onRelease = {
            held = false
            if (livePlayer.playing) livePlayer.stop()
        }
        page.picture.onHold = hold@{
            held = true
            if (holder.liveJob?.isActive == true) return@hold
            holder.liveJob = lifecycleScope.launch {
                val watch = launch { showLiveProgress(page, motion) }
                val file = try {
                    localFile(motion) ?: relayAttempt { backend.mediaFile(picture.message.id, motion.partId) }.getOrNull()
                } finally {
                    watch.cancel()
                    page.live.text = getString(R.string.live_photo_badge)
                }
                if (file == null) {
                    toast(getString(R.string.live_photo_unavailable))
                    return@launch
                }
                // Let go meanwhile: nothing plays (the next hold plays at once).
                if (!held) return@launch
                page.picture.resetZoom()
                page.picture.zoomSuspended = true
                livePlayer.play(page.root as android.widget.FrameLayout, page.picture, file) { page.picture.zoomSuspended = false }
            }
        }
    }

    /** The moving part's download, as a percentage on the LIVE badge, while it comes. */
    private suspend fun showLiveProgress(page: ItemGalleryPageBinding, motion: MediaRow) {
        if (backend.service != ChatService.IMESSAGE) return
        com.lightdecoder.imessage.direct.DirectLink.get(applicationContext).transfers.collect { all ->
            val t = all[motion.mediaId]
            page.live.text = if (t != null && t.total > 0) {
                getString(R.string.live_photo_badge) + " " + TransferRing.percentOf(t.fraction) + "%"
            } else {
                getString(R.string.live_photo_badge)
            }
        }
    }

    /** As sharp as a zoom needs, without holding several huge bitmaps. */
    private fun maxSide(): Int {
        val metrics = resources.displayMetrics
        return (maxOf(metrics.widthPixels, metrics.heightPixels) * 3 / 2).coerceIn(MIN_SIDE, MAX_SIDE)
    }

    private fun decodeStandIn(part: MediaRow): Bitmap? {
        val bytes = existing(part.thumbPath)?.let(::readCapped) ?: part.inlineData.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { MediaLoader.decodePreview(bytes, STAND_IN_SIDE) }.getOrNull()
    }

    private suspend fun loadFull(picture: Picture, side: Int): Result<Bitmap> {
        val part = picture.part
        val bytes = localFile(part)?.let(::readCapped)
            ?: part.inlineData.takeIf { part.inlineIsMedia }
            ?: run {
                val file = relayAttempt { backend.mediaFile(picture.message.id, part.partId, thumbnail = part.needsFullSize) }
                    .getOrElse { return Result.failure(it) }
                file?.let(::readCapped)
            }
            ?: return Result.failure(RelayException(RelayException.NOT_FOUND, "no picture"))
        return try {
            MediaLoader.decodePreview(bytes, side)?.let { Result.success(it) }
                ?: Result.failure(RelayException(RelayException.INVALID_ARGUMENT, "not a picture"))
        } catch (e: OutOfMemoryError) {
            // Smaller, then.
            runCatching { MediaLoader.decodePreview(bytes, side / 2) }.getOrNull()?.let { Result.success(it) }
                ?: Result.failure(RelayException(RelayException.INTERNAL, "out of memory"))
        }
    }

    /** The picture's own file on this phone (not an inline preview stored as a file). */
    private fun localFile(part: MediaRow): File? {
        if (part.inlineIsPreview && MediaCache.isInlineFile(part.localPath, part.messageId, part.partId)) {
            return existing(part.thumbPath)?.takeIf { part.needsFullSize }
        }
        return existing(part.localPath) ?: existing(part.thumbPath)?.takeIf { part.needsFullSize }
    }

    private fun existing(path: String): File? = if (path.isEmpty()) null else File(path).takeIf { it.isFile }

    private fun readCapped(file: File): ByteArray? =
        if (file.length() in 1..MAX_READ) runCatching { file.readBytes() }.getOrNull() else null

    companion object {
        const val EXTRA_PART_ID = "com.lightdecoder.extra.PART_ID"
        const val EXTRA_TITLE = "com.lightdecoder.extra.GALLERY_TITLE"
        private const val STATE_INDEX = "index"
        private const val PAGE_GAP_DP = 16
        private const val STAND_IN_SIDE = 512
        private const val MIN_SIDE = 1024
        private const val MAX_SIDE = 3072
        private const val MAX_READ = 64L * 1024 * 1024

        /** How many of the conversation's latest messages are looked through for pictures. */
        private const val MESSAGES_LOOKED_AT = 2_000

        /** Opens [service]'s conversation [conversationId] at the picture [partId] of [messageId]. */
        fun intent(
            context: Context,
            service: ChatService,
            conversationId: String,
            messageId: String,
            partId: String,
            title: String,
        ): Intent = Intent(context, PictureGalleryActivity::class.java)
            .putExtra(RelayIntents.EXTRA_SERVICE, service.key)
            .putExtra(RelayIntents.EXTRA_CONVERSATION_ID, conversationId)
            .putExtra(RelayIntents.EXTRA_MESSAGE_ID, messageId)
            .putExtra(EXTRA_PART_ID, partId)
            .putExtra(EXTRA_TITLE, title)

        /** Whether a part is a picture this screen shows (not a video, audio or other file). */
        fun isPicture(part: MediaRow): Boolean = part.mime.isEmpty() || part.mime.startsWith("image/")

        /** The conversation's pictures, oldest first, as the thread shows them. Reads the archive: off the main thread. */
        internal fun picturesOf(backend: ChatBackend, conversationId: String): List<Picture> =
            backend.repo.messages(conversationId, limit = MESSAGES_LOOKED_AT)
                .filter { it.direction != "deleted" }
                .flatMap { row -> row.shownMedia().filter(::isPicture).map { Picture(row, it) } }
    }
}
