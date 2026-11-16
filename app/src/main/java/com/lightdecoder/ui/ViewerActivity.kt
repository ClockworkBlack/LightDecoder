package com.lightdecoder.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.lightdecoder.R
import com.lightdecoder.databinding.ActivityViewerBinding
import com.lightdecoder.heif.HeifCodec
import com.lightdecoder.heif.HeifFrame
import com.lightdecoder.heif.ImageSniffer
import com.lightdecoder.heif.Media
import com.lightdecoder.heif.StickerEffect
import com.lightdecoder.inbox.InboxStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.Executors

/**
 * Shows one picture: a file opened or shared from another app, or a sticker
 * from the inbox. HEIC goes through the built-in decoder; other formats through
 * Android's.
 */
class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Everything known about the picture on screen. */
    private class Loaded(
        val name: String,
        val bytes: ByteArray,
        val decoded: Media.Decoded,
        val effect: StickerEffect,
        /** The first frame with the sender's effect drawn, when it is one we can draw. */
        val withEffect: Bitmap?,
        val sender: String?,
    )

    private var loaded: Loaded? = null
    private var showEffect = true

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) load(queryName(uri)) { readCapped(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarPadding(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.openButton.setOnClickListener { openDocument.launch(arrayOf("image/*", "*/*")) }
        binding.bottomBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_play -> binding.stickerView.togglePlay()
                R.id.action_effect -> toggleEffect()
                R.id.action_save -> saveToGallery()
                R.id.action_share -> chooseShare()
                R.id.action_info -> showDetails()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }

        if (!HeifCodec.isAvailable) toast(getString(R.string.not_available))
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        val inboxId = intent?.getStringExtra(EXTRA_INBOX_ID)
        if (inboxId != null) {
            loadInboxItem(inboxId)
            return
        }
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            else -> null
        }
        // A title comes only with intentForFile (a picture from a message).
        val title = intent?.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
        if (uri != null) load(queryName(uri), title) { readCapped(uri) } else showEmpty()
    }

    private fun loadInboxItem(id: String) {
        val store = InboxStore.get(this)
        val item = store.list().firstOrNull { it.id == id }
        if (item == null) {
            showEmpty()
            toast(getString(R.string.inbox_item_gone))
            return
        }
        val name = if (item.sender.isNotBlank()) "sticker_${item.sender}" else "sticker"
        load(name, item.sender.ifBlank { null }) { store.originalFile(item).readBytes() }
    }

    private fun load(name: String, sender: String? = null, read: () -> ByteArray) {
        showLoading()
        io.execute {
            val result = try {
                val bytes = read()
                val decoded = Media.decode(bytes)
                val isHeifStill = decoded.heif != null && !decoded.animated
                val effect = if (isHeifStill) StickerEffect.detect(bytes) else StickerEffect.NONE
                val withEffect = if (effect == StickerEffect.OUTLINE && decoded.hasAlpha) {
                    StickerEffect.drawOutline(decoded.frames.first().bitmap)
                } else {
                    null
                }
                Result.success(Loaded(name, bytes, decoded, effect, withEffect, sender))
            } catch (t: Throwable) {
                // Throwable on purpose: a huge picture can run out of memory.
                Result.failure(t)
            }
            main.post {
                if (isDestroyed) return@post
                result.onSuccess { show(it) }.onFailure {
                    showEmpty()
                    val reason = if (it is OutOfMemoryError) getString(R.string.too_large) else it.message
                    toast(getString(R.string.decode_failed, reason ?: getString(R.string.unknown_error)))
                }
            }
        }
    }

    private fun show(l: Loaded) {
        loaded = l
        showEffect = true
        binding.progress.visibility = View.GONE
        binding.emptyState.visibility = View.GONE
        binding.stickerView.visibility = View.VISIBLE
        binding.bottomBar.visibility = View.VISIBLE
        binding.bottomBar.menu.findItem(R.id.action_play).isVisible = l.decoded.animated
        binding.bottomBar.menu.findItem(R.id.action_effect).isVisible = l.withEffect != null
        supportActionBar?.title = l.sender ?: getString(R.string.app_name)
        applyFrames()
    }

    private fun applyFrames() {
        val l = loaded ?: return
        val effectShown = showEffect && l.withEffect != null
        binding.stickerView.setFrames(
            if (effectShown) listOf(HeifFrame(l.withEffect!!, 0)) else l.decoded.frames
        )
        supportActionBar?.subtitle = buildString {
            append(l.decoded.summary)
            if (l.effect != StickerEffect.NONE) {
                append(" · ")
                append(l.effect.label)
                if (!effectShown) append(getString(R.string.effect_not_drawn_suffix))
            }
        }
    }

    private fun toggleEffect() {
        showEffect = !showEffect
        applyFrames()
    }

    private fun showEmpty() {
        loaded = null
        binding.progress.visibility = View.GONE
        binding.stickerView.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
        binding.emptyState.visibility = View.VISIBLE
        supportActionBar?.title = getString(R.string.app_name)
        supportActionBar?.subtitle = null
    }

    private fun showLoading() {
        binding.emptyState.visibility = View.GONE
        binding.stickerView.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
        binding.progress.visibility = View.VISIBLE
    }

    // --- save and share ------------------------------------------------------

    private fun chooseShare() {
        val l = loaded ?: return
        val original = getString(R.string.share_original, l.decoded.extension.uppercase())
        AlertDialog.Builder(this)
            .setTitle(R.string.action_share)
            .setItems(arrayOf(getString(R.string.share_png), original)) { _, which ->
                if (which == 0) sharePng() else shareOriginal()
            }
            .show()
    }

    private fun sharePng() {
        val l = loaded ?: return
        val bitmap = binding.stickerView.currentBitmap ?: return
        io.execute {
            val file = runCatching {
                File(sharedDir(), "${safeName(l.name)}.png").also { f ->
                    FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }.getOrNull()
            main.post { if (file != null) sendFile(file, "image/png") else toast(getString(R.string.share_failed)) }
        }
    }

    private fun shareOriginal() {
        val l = loaded ?: return
        io.execute {
            val file = runCatching {
                File(sharedDir(), "${safeName(l.name)}.${l.decoded.extension}").also { it.writeBytes(l.bytes) }
            }.getOrNull()
            main.post {
                if (file != null) {
                    sendFile(file, ImageSniffer.mimeFor(l.decoded.extension))
                } else {
                    toast(getString(R.string.share_failed))
                }
            }
        }
    }

    private fun sendFile(file: File, mime: String) {
        if (isDestroyed) return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.action_share)))
    }

    private fun sharedDir() = File(cacheDir, "shared").apply { mkdirs() }

    private fun saveToGallery() {
        val l = loaded ?: return
        val bitmap = binding.stickerView.currentBitmap ?: return
        io.execute {
            val name = "${safeName(l.name)}_${System.currentTimeMillis()}.png"
            val where = runCatching { saveBitmap(bitmap, name) }.getOrNull()
            main.post {
                toast(if (where != null) getString(R.string.saved_to, where) else getString(R.string.save_failed))
            }
        }
    }

    private fun saveBitmap(bitmap: Bitmap, name: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LightDecoder")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            try {
                contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }
            contentResolver.update(
                uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null,
            )
            return "Pictures/LightDecoder"
        }
        // Before Android 10: the app's own folder on shared storage needs no permission.
        val dir = File(getExternalFilesDir(null), "LightDecoder").apply { mkdirs() }
        val file = File(dir, name)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        MediaScannerConnection.scanFile(this, arrayOf(file.path), arrayOf("image/png"), null)
        return file.path
    }

    private fun showDetails() {
        val l = loaded ?: return
        val d = l.decoded
        val text = buildString {
            l.sender?.let { appendLine(getString(R.string.details_from, it)) }
            appendLine(getString(R.string.details_size, d.width, d.height))
            appendLine(getString(R.string.details_transparency, yesNo(d.hasAlpha)))
            appendLine(
                if (d.animated) getString(R.string.details_animated_frames, d.frames.size)
                else getString(R.string.details_animated, yesNo(false))
            )
            appendLine(getString(R.string.details_file, d.extension.uppercase(), d.bytes / 1024))
            if (d.heif != null) {
                val effectLine = when {
                    l.effect == StickerEffect.NONE -> getString(R.string.details_effect_none)
                    l.effect.canDraw -> getString(R.string.details_effect_drawn, l.effect.label)
                    else -> getString(R.string.details_effect_not_drawn, l.effect.label)
                }
                appendLine(effectLine)
                appendLine()
                appendLine(getString(R.string.details_type, d.heif.mime))
                if (d.heif.brands.isNotEmpty()) appendLine(getString(R.string.details_brands, d.heif.brands))
                d.heif.raw["color"]?.let { appendLine(getString(R.string.details_color, it)) }
                d.heif.raw["luma_bits"]?.let { appendLine(getString(R.string.details_bits, it)) }
                appendLine()
                append(HeifCodec.versions)
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_info)
            .setMessage(text.trimEnd())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun yesNo(value: Boolean) = getString(if (value) R.string.yes else R.string.no)

    // --- reading -------------------------------------------------------------

    private fun readCapped(uri: Uri): ByteArray {
        val stream: InputStream = contentResolver.openInputStream(uri)
            ?: throw HeifCodec.DecodeException(getString(R.string.cannot_open))
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_FILE_BYTES) throw HeifCodec.DecodeException(getString(R.string.too_large))
            }
            return out.toByteArray()
        }
    }

    private fun queryName(uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) return c.getString(0).substringBeforeLast('.')
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "sticker"
    }

    private fun safeName(s: String): String =
        s.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48).ifEmpty { "sticker" }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val EXTRA_INBOX_ID = "com.lightdecoder.extra.INBOX_ID"
        private const val EXTRA_TITLE = "com.lightdecoder.extra.TITLE"
        private const val MAX_FILE_BYTES = 64 * 1024 * 1024

        /** Opens a sticker that the inbox has stored. */
        fun intentForInboxItem(context: Context, id: String): Intent =
            Intent(context, ViewerActivity::class.java)
                .putExtra(EXTRA_INBOX_ID, id)
                // Distinct data per item, so pending intents for two stickers are not merged.
                .setData(Uri.parse("lightdecoder://inbox/$id"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        /**
         * Opens a picture from the app's own storage (media of a Google
         * Messages conversation). The file is handed over as a content:// uri
         * of the app's FileProvider rather than a path: the viewer is exported,
         * so it must not accept file paths from callers, and it already reads
         * content uris. [title] is shown in the toolbar.
         *
         * @throws IllegalArgumentException if [file] is outside the folders in res/xml/file_paths.xml
         */
        fun intentForFile(context: Context, file: File, mime: String, title: String): Intent {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            return Intent(context, ViewerActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime.ifBlank { "image/*" })
                .putExtra(EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
