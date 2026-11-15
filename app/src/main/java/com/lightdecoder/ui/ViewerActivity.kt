package com.lightdecoder.ui

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.lightdecoder.R
import com.lightdecoder.databinding.ActivityViewerBinding
import com.lightdecoder.heif.HeifCodec
import com.lightdecoder.heif.HeifFrame
import com.lightdecoder.heif.HeifInfo
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var frames: List<HeifFrame> = emptyList()
    private var info: HeifInfo? = null
    private var sourceName: String = "sticker"

    private val openDocument =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) load(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.openButton.setOnClickListener {
            openDocument.launch(arrayOf("image/heic", "image/heif", "image/*", "*/*"))
        }
        binding.bottomBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_play -> { binding.stickerView.togglePlay(); true }
                R.id.action_save -> { saveToGallery(); true }
                R.id.action_share -> { share(); true }
                R.id.action_info -> { showDetails(); true }
                else -> false
            }
        }

        if (!HeifCodec.isAvailable) {
            toast(getString(R.string.not_available))
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) load(uri) else showEmpty()
    }

    private fun load(uri: Uri) {
        sourceName = queryName(uri)
        showLoading()
        io.execute {
            val result = runCatching {
                val bytes = readAllBytes(uri)
                val meta = HeifCodec.probe(bytes)
                val decoded = HeifCodec.decodeAll(bytes)
                meta to decoded
            }
            main.post {
                result.onSuccess { (meta, decoded) ->
                    info = meta
                    frames = decoded
                    showSticker(decoded, meta)
                }.onFailure { e ->
                    showEmpty()
                    toast(getString(R.string.decode_failed, e.message ?: "unknown error"))
                }
            }
        }
    }

    private fun showSticker(decoded: List<HeifFrame>, meta: HeifInfo) {
        binding.progress.visibility = android.view.View.GONE
        binding.emptyState.visibility = android.view.View.GONE
        binding.stickerView.visibility = android.view.View.VISIBLE
        binding.bottomBar.visibility = android.view.View.VISIBLE
        binding.stickerView.setFrames(decoded)
        binding.bottomBar.menu.findItem(R.id.action_play).isVisible = decoded.size > 1
        supportActionBar?.subtitle = meta.summary
    }

    private fun showEmpty() {
        binding.progress.visibility = android.view.View.GONE
        binding.stickerView.visibility = android.view.View.GONE
        binding.bottomBar.visibility = android.view.View.GONE
        binding.emptyState.visibility = android.view.View.VISIBLE
        supportActionBar?.subtitle = null
    }

    private fun showLoading() {
        binding.emptyState.visibility = android.view.View.GONE
        binding.stickerView.visibility = android.view.View.GONE
        binding.bottomBar.visibility = android.view.View.GONE
        binding.progress.visibility = android.view.View.VISIBLE
    }

    // --- export --------------------------------------------------------------

    /** The frame currently on screen, as a PNG file in cache/shared/. */
    private fun writeSharePng(): File? {
        val bmp = binding.stickerView.currentBitmap ?: return null
        val dir = File(cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "${safeName(sourceName)}.png")
        return runCatching {
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            file
        }.getOrNull()
    }

    private fun share() {
        io.execute {
            val file = writeSharePng()
            main.post {
                if (file == null) { toast("Nothing to share"); return@post }
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.share_title)))
            }
        }
    }

    private fun saveToGallery() {
        val bmp = binding.stickerView.currentBitmap ?: return
        io.execute {
            val name = "${safeName(sourceName)}_${System.currentTimeMillis()}.png"
            val where = runCatching { saveBitmap(bmp, name) }.getOrNull()
            main.post { toast(where?.let { getString(R.string.saved_to, it) } ?: "Save failed") }
        }
    }

    private fun saveBitmap(bmp: Bitmap, name: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LightDecoder")
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            resolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return "Pictures/LightDecoder"
        }
        // Pre-Q: app-specific external dir, no runtime permission needed.
        val dir = File(getExternalFilesDir(null), "LightDecoder").apply { mkdirs() }
        val file = File(dir, name)
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        android.media.MediaScannerConnection.scanFile(this, arrayOf(file.path), arrayOf("image/png"), null)
        return file.path
    }

    private fun showDetails() {
        val meta = info ?: return
        val text = buildString {
            appendLine("Type: ${meta.mime}")
            if (meta.brands.isNotEmpty()) appendLine("Brands: ${meta.brands}")
            appendLine("Size: ${meta.width}×${meta.height}")
            appendLine("Transparency: ${if (meta.hasAlpha) "yes" else "no"}")
            appendLine("Animated: ${if (meta.isSequence) "yes (${frames.size} frames)" else "no"}")
            appendLine("File: ${meta.bytes / 1024} KB")
            meta.raw["color"]?.let { appendLine("Color: $it") }
            appendLine()
            append(HeifCodec.versions)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_info)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // --- io ------------------------------------------------------------------

    private fun readAllBytes(uri: Uri): ByteArray {
        contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "Cannot open the file" }
            return stream.readBytes()
        }
    }

    private fun queryName(uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) return c.getString(i).substringBeforeLast('.')
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "sticker"
    }

    private fun safeName(s: String): String =
        s.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48).ifEmpty { "sticker" }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }
}
