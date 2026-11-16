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
import com.lightdecoder.sticker.StickerProject
import android.graphics.BitmapFactory
import java.io.File
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

    /** The picture as chosen, before any traced cut-out. */
    private var original: Bitmap? = null

    /** The library sticker being edited; null while making a new one. */
    private var editing: java.io.File? = null

    /** The traced outline (x,y pairs in [original]'s pixels), kept so the sticker can be edited later. */
    private var trace: FloatArray? = null
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
        binding.refineStart.setOnClickListener { startRefining() }
        binding.refineDone.setOnClickListener { stopRefining() }
        binding.brushMode.addOnButtonCheckedListener { _, id, isChecked ->
            if (isChecked) binding.refine.keep = id == R.id.brushKeep
        }
        binding.brushSize.addOnChangeListener { _, value, _ ->
            binding.refine.brushSizePx = value * resources.displayMetrics.density
        }
        binding.brushUndo.setOnClickListener { binding.refine.undo() }
        binding.brushReset.setOnClickListener {
            binding.refine.clearStrokes()
            updateBrushButtons()
            render(refreshBrushedPicture = false)
        }
        binding.refine.onStrokesChanged = {
            updateBrushButtons()
            render(refreshBrushedPicture = false)
        }
        binding.outline.setOnCheckedChangeListener { _, _ -> render(refreshBrushedPicture = false) }
        // While the slider moves the preview follows; only the newest request is shown.
        binding.outlineWidth.addOnChangeListener { _, _, fromUser -> if (fromUser) render(refreshBrushedPicture = false) }
        binding.solid.setOnCheckedChangeListener { _, _ -> render() }
        binding.shape.addOnButtonCheckedListener { _, _, isChecked -> if (isChecked) render() }
        binding.tolerance.addOnChangeListener { _, _, fromUser -> if (fromUser) render() }
        binding.save.setOnClickListener { save() }
        binding.trace.setOnClickListener {
            stopRefining()
            startTracing()
        }
        binding.traceUndo.setOnClickListener {
            source = original
            trace = null
            binding.traceUndo.visibility = View.GONE
            render()
        }
        binding.resetOriginal.setOnClickListener { resetToOriginal() }
        binding.lasso.onTooSmall = {
            Toast.makeText(this, R.string.sticker_trace_too_small, Toast.LENGTH_SHORT).show()
        }
        binding.lasso.onTraced = { outline, points ->
            // Cut from the original, so the same cut can be made again when the sticker is edited later.
            original?.let { source = StickerMaker.cutToPath(it, outline) }
            trace = points
            binding.traceUndo.visibility = View.VISIBLE
            stopTracing()
            render()
        }
        updateToleranceVisible()

        val edit = intent.getStringExtra(EXTRA_EDIT)?.let(::File)
        if (edit != null) {
            supportActionBar?.setTitle(R.string.sticker_edit_title)
            binding.pick.visibility = View.GONE
            loadForEdit(edit)
        } else {
            savedInstanceState?.getString(STATE_URI)?.let { load(Uri.parse(it)) }
        }
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

    /** Opens a sticker of the library with how it was made, or (made before that was kept) as it is. */
    private fun loadForEdit(file: File) {
        lifecycleScope.launch {
            val project = withContext(Dispatchers.IO) {
                if (!StickerLibrary.owns(applicationContext, file)) return@withContext null
                StickerProject.load(applicationContext, file)
                    ?: runCatching { BitmapFactory.decodeFile(file.path)?.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                        ?.let { StickerProject.Project(it, StickerProject.Edits(StickerMaker.Options(outline = false), null, emptyList())) }
                        ?.also { withContext(Dispatchers.Main) { Toast.makeText(this@StickerMakerActivity, R.string.sticker_old_no_original, Toast.LENGTH_LONG).show() } }
            }
            if (project == null) {
                Toast.makeText(this@StickerMakerActivity, R.string.sticker_failed, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            editing = file
            original = project.original
            applyEdits(project.edits)
            binding.trace.isEnabled = true
            binding.refineStart.isEnabled = true
            binding.resetOriginal.visibility = View.VISIBLE
            render()
        }
    }

    /** Puts the controls, the traced cut and the brush strokes as [edits] has them. */
    private fun applyEdits(edits: StickerProject.Edits) {
        val picture = original ?: return
        trace = edits.trace
        source = edits.trace?.let { StickerMaker.cutToPath(picture, StickerProject.pathOf(it, close = true)) } ?: picture
        binding.traceUndo.visibility = if (edits.trace != null) View.VISIBLE else View.GONE
        val o = edits.options
        binding.removeBackground.isChecked = o.removeBackground
        binding.tolerance.value = o.tolerance.toFloat().coerceIn(binding.tolerance.valueFrom, binding.tolerance.valueTo)
        binding.shape.check(
            when (o.shape) {
                StickerMaker.Shape.ROUNDED -> R.id.shapeRounded
                StickerMaker.Shape.CIRCLE -> R.id.shapeCircle
                StickerMaker.Shape.ORIGINAL -> R.id.shapeOriginal
            }
        )
        binding.outline.isChecked = o.outline
        binding.outlineWidth.value = o.outlineWidth.toFloat().coerceIn(binding.outlineWidth.valueFrom, binding.outlineWidth.valueTo)
        binding.solid.isChecked = o.solidBackground
        binding.refine.setStrokes(edits.strokes)
        updateBrushButtons()
        stopRefining()
        stopTracing()
    }

    /** Every edit undone: the picture the sticker was made from, with nothing cut, brushed or outlined. */
    private fun resetToOriginal() {
        if (original == null) return
        applyEdits(StickerProject.Edits(StickerMaker.Options(outline = false), null, emptyList()))
        render()
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
            original = bitmap
            trace = null
            binding.resetOriginal.visibility = View.VISIBLE
            // A new picture: the brushing was for the old one.
            binding.refine.clearStrokes()
            updateBrushButtons()
            stopRefining()
            binding.trace.isEnabled = true
            binding.refineStart.isEnabled = true
            binding.traceUndo.visibility = View.GONE
            stopTracing()
            render()
        }
    }

    /** The picture replaces the preview; a line drawn around its subject cuts it out. */
    private fun startTracing() {
        val picture = source ?: return
        binding.lasso.setBitmap(picture)
        binding.lasso.visibility = View.VISIBLE
        // The page holds still while the finger draws, with the whole picture in view.
        binding.page.locked = true
        binding.page.post { binding.page.smoothScrollTo(0, binding.lasso.top) }
        binding.preview.visibility = View.GONE
        binding.hint.setText(R.string.sticker_trace_hint)
        binding.hint.visibility = View.VISIBLE
    }

    /**
     * Fine-tune: the picture replaces the preview, and the user brushes the
     * background away (Erase) or the subject back in (Keep). The options
     * below still apply; changing them updates what is brushed on.
     */
    private fun startRefining() {
        if (source == null) return
        stopTracing()
        binding.refine.visibility = View.VISIBLE
        binding.page.locked = true
        binding.page.post { binding.page.smoothScrollTo(0, binding.refine.top) }
        binding.refinePanel.visibility = View.VISIBLE
        binding.preview.visibility = View.GONE
        binding.hint.setText(R.string.sticker_refine_hint)
        binding.hint.visibility = View.VISIBLE
        refreshRefineBase()
    }

    private fun stopRefining() {
        if (binding.refine.visibility != View.VISIBLE) return
        binding.page.locked = false
        binding.refine.visibility = View.GONE
        binding.refinePanel.visibility = View.GONE
        binding.preview.visibility = View.VISIBLE
        binding.hint.visibility = View.GONE
    }

    private val refining: Boolean get() = binding.refine.visibility == View.VISIBLE

    private fun updateBrushButtons() {
        val any = binding.refine.strokes.isNotEmpty()
        binding.brushUndo.isEnabled = any
        binding.brushReset.isEnabled = any
    }

    /** What the brushes paint on: the picture with background removal and tracing, without the strokes. */
    private var refineJob: Job? = null

    private fun refreshRefineBase() {
        val picture = source ?: return
        val from = original ?: return
        val options = options()
        refineJob?.cancel()
        refineJob = lifecycleScope.launch {
            val base = withContext(Dispatchers.Default) {
                try {
                    StickerMaker.cutOut(picture, options)
                } catch (e: OutOfMemoryError) {
                    null
                }
            } ?: return@launch
            // The brushes work on the picture's own pixels: both at the same size.
            val restore = if (from.width == base.width && from.height == base.height) from
            else Bitmap.createScaledBitmap(from, base.width, base.height, true)
            binding.refine.setPictures(restore, base)
        }
    }

    private fun stopTracing() {
        binding.page.locked = binding.refine.visibility == View.VISIBLE
        binding.lasso.visibility = View.GONE
        binding.preview.visibility = View.VISIBLE
        binding.hint.visibility = View.GONE
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
        outlineWidth = binding.outlineWidth.value.toInt(),
        solidBackground = binding.solid.isChecked,
    )

    private fun updateToleranceVisible() {
        val shown = if (binding.removeBackground.isChecked) View.VISIBLE else View.GONE
        binding.tolerance.visibility = shown
        binding.toleranceLabel.visibility = shown
        val outlined = if (binding.outline.isChecked) View.VISIBLE else View.GONE
        binding.outlineWidth.visibility = outlined
        binding.outlineWidthLabel.visibility = outlined
    }

    /** Re-makes the preview with the current options; only the latest request is shown. */
    private fun render(refreshBrushedPicture: Boolean = true) {
        updateToleranceVisible()
        val picture = source ?: return
        val options = options()
        // The options changed what the brushes paint on (a stroke alone does not).
        if (refining && refreshBrushedPicture) refreshRefineBase()
        val strokes = binding.refine.strokes.toList()
        val edits = original?.takeIf { strokes.isNotEmpty() }?.let { StickerMaker.Edits(it, strokes) }
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            val made = withContext(Dispatchers.Default) {
                try {
                    StickerMaker.make(picture, options, edits)
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
        val from = original
        val edits = StickerProject.Edits(options(), trace, binding.refine.strokes.toList())
        val target = editing
        binding.save.isEnabled = false
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val saved = if (target != null) StickerLibrary.replace(applicationContext, target, made)
                    else StickerLibrary.save(applicationContext, made)
                    // How it was made, for editing it later (failing that, the sticker is still saved).
                    if (from != null) runCatching { StickerProject.save(applicationContext, saved, from, edits) }
                    saved
                }.getOrNull()
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

        /** A sticker of the library to edit (its file path). */
        const val EXTRA_EDIT = "com.lightdecoder.extra.EDIT_STICKER"
        private const val STATE_URI = "uri"
        private const val MAX_SOURCE_BYTES = 40 * 1024 * 1024

        fun intent(context: Context) = Intent(context, StickerMakerActivity::class.java)

        /** Opens [sticker] of the library for editing; saving replaces it. */
        fun editIntent(context: Context, sticker: File) = intent(context).putExtra(EXTRA_EDIT, sticker.path)
    }
}
