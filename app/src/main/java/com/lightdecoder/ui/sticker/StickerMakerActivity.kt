package com.lightdecoder.ui.sticker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lightdecoder.R
import com.lightdecoder.databinding.ActivityStickerMakerBinding
import com.lightdecoder.heif.HeifCodec
import com.lightdecoder.heif.HeifFrame
import com.lightdecoder.heif.Media
import com.lightdecoder.sticker.StickerLibrary
import com.lightdecoder.sticker.StickerMaker
import com.lightdecoder.ui.applySystemBarPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Makes a sticker from a picture the user chooses (any format the app reads,
 * HEIC included) and saves it to their sticker library. Everything happens
 * on the phone. Finishes with [EXTRA_PATH] set to the saved file.
 */
class StickerMakerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStickerMakerBinding
    private var sourceUri: Uri? = null
    private var source: Bitmap? = null
    private var sticker: Bitmap? = null
    private var renderJob: Job? = null

    private val pick = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) load(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStickerMakerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarPadding(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.pick.setOnClickListener {
            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.removeBackground.setOnCheckedChangeListener { _, _ -> render() }
        binding.outline.setOnCheckedChangeListener { _, _ -> render() }
        binding.solid.setOnCheckedChangeListener { _, _ -> render() }
        binding.shape.addOnButtonCheckedListener { _, _, isChecked -> if (isChecked) render() }
        binding.tolerance.addOnChangeListener { _, _, fromUser -> if (fromUser) render() }
        binding.save.setOnClickListener { save() }
        updateToleranceVisible()

        savedInstanceState?.getString(STATE_URI)?.let { load(Uri.parse(it)) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        sourceUri?.let { outState.putString(STATE_URI, it.toString()) }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun load(uri: Uri) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { readPicture(uri) }
            if (bitmap == null) {
                Toast.makeText(this@StickerMakerActivity, R.string.sticker_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            sourceUri = uri
            source = bitmap
            binding.hint.visibility = View.GONE
            render()
        }
    }

    private fun readPicture(uri: Uri): Bitmap? = try {
        contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_SOURCE_BYTES) return null
            }
            val first = Media.decodeFirstFrame(out.toByteArray())
            Media.scaledToFit(first, StickerMaker.SIZE * 2).also { if (it !== first) first.recycle() }
        }
    } catch (e: HeifCodec.DecodeException) {
        null
    } catch (e: java.io.IOException) {
        null
    } catch (e: SecurityException) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun options() = StickerMaker.Options(
        removeBackground = binding.removeBackground.isChecked,
        tolerance = binding.tolerance.value.toInt(),
        shape = when (binding.shape.checkedButtonId) {
            R.id.shapeRounded -> StickerMaker.Shape.ROUNDED
            R.id.shapeCircle -> StickerMaker.Shape.CIRCLE
            else -> StickerMaker.Shape.ORIGINAL
        },
        outline = binding.outline.isChecked,
        solidBackground = binding.solid.isChecked,
    )

    private fun updateToleranceVisible() {
        val shown = if (binding.removeBackground.isChecked) View.VISIBLE else View.GONE
        binding.tolerance.visibility = shown
        binding.toleranceLabel.visibility = shown
    }

    /** Re-makes the preview with the current options; only the latest request is shown. */
    private fun render() {
        updateToleranceVisible()
        val picture = source ?: return
        val options = options()
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            val made = withContext(Dispatchers.Default) {
                try {
                    StickerMaker.make(picture, options)
                } catch (e: OutOfMemoryError) {
                    null
                }
            } ?: return@launch
            sticker = made
            binding.preview.setFrames(listOf(HeifFrame(made, 0)))
            binding.save.isEnabled = true
        }
    }

    private fun save() {
        val made = sticker ?: return
        binding.save.isEnabled = false
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching { StickerLibrary.save(applicationContext, made) }.getOrNull()
            }
            if (file == null) {
                binding.save.isEnabled = true
                Toast.makeText(this@StickerMakerActivity, R.string.sticker_failed, Toast.LENGTH_LONG).show()
            } else {
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PATH, file.path))
                finish()
            }
        }
    }

    companion object {
        const val EXTRA_PATH = "com.lightdecoder.extra.STICKER_PATH"
        private const val STATE_URI = "uri"
        private const val MAX_SOURCE_BYTES = 40 * 1024 * 1024

        fun intent(context: Context) = Intent(context, StickerMakerActivity::class.java)
    }
}
