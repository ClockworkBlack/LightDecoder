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

    /** The cut "Undo cut-out" took back, for "Redo cut-out"; a new cut forgets it. */
    private var undoneTrace: FloatArray? = null
    private var sticker: Bitmap? = null
    private var renderJob: Job? = null

    /** Where the picture's subject is, for the smart cut-out; found once per picture. */
    private var subjects: com.lightdecoder.sticker.Subjects? = null

    /** Taps on the people or objects to keep (true) or drop (false), in [original]'s pixels, in the order made. */
    private val taps = ArrayList<Triple<Float, Float, Boolean>>()
    private var tapped: com.lightdecoder.sticker.SubjectMask? = null
    private var tappedFrom: Any? = null

    private fun tapPoints(keep: Boolean) = taps.filter { it.third == keep }.flatMap { listOf(it.first, it.second) }.toFloatArray()

    /** The picture as studied for taps, what each tap turned out to be on, and whether taps can be told apart at all. */
    private var tapper: com.lightdecoder.sticker.TapCutter.Taps? = null
    private val tapMasks = HashMap<Triple<Float, Float, Boolean>, com.lightdecoder.sticker.SubjectMask>()
    private var tapsUnavailable = false
    private var tapJob: Job? = null

    /**
     * The mask the cut is made with: what was found, of the kind chosen, or,
     * once the user has tapped, what they tapped to keep less what they
     * tapped to remove.
     */
    private val subject: com.lightdecoder.sticker.SubjectMask?
        get() {
            val found = subjects?.pick(smartKind())
            val picture = original
            if (taps.isEmpty() || picture == null) return found
            val key = listOf(found, taps.toList(), tapsUnavailable, tapMasks.size)
            if (tappedFrom != key) {
                tapped = if (tapsUnavailable) {
                    // Without the tap model: the taps choose among the pieces that were found.
                    found?.let { com.lightdecoder.sticker.SubjectPick.apply(it, picture, tapPoints(true), tapPoints(false)) }
                } else {
                    // In the order tapped: a later tap on the same thing undoes an earlier one.
                    com.lightdecoder.sticker.TapCutter.combine(found, taps.mapNotNull { tap -> tapMasks[tap]?.let { it to tap.third } })
                }
                tappedFrom = key
            }
            return tapped
        }

    /**
     * Works out what each new tap is on. True while that is still awaited:
     * the preview is made when it arrives.
     */
    private fun resolvingTaps(): Boolean {
        if (!wantsSubject || tapsUnavailable) return false
        val pending = taps.filter { it !in tapMasks }
        if (pending.isEmpty()) return false
        if (tapJob?.isActive == true) return true
        val picture = original ?: return false
        val open = tapper
        tapJob = lifecycleScope.launch {
            val (studied, results) = withContext(Dispatchers.IO) {
                val t = open ?: com.lightdecoder.sticker.TapCutter.open(applicationContext, picture)
                t to t?.let { pending.map { tap -> tap to runCatching { it.at(tap.first, tap.second) }.getOrNull() } }
            }
            if (picture !== original) {
                if (studied !== open) studied?.close()
                return@launch
            }
            tapper = studied
            if (results == null) {
                tapsUnavailable = true
                // The older way needs a tap to be on something that was found.
                taps.removeAll { !com.lightdecoder.sticker.SubjectPick.lands(subjects?.pick(smartKind()), picture, it.first, it.second) }
            } else {
                var missed = false
                for ((tap, mask) in results) {
                    if (mask != null) tapMasks[tap] = mask else missed = taps.remove(tap) || missed
                }
                if (missed) Toast.makeText(this@StickerMakerActivity, R.string.sticker_pick_nothing_there, Toast.LENGTH_LONG).show()
            }
            tapJob = null
            tapsChanged()
        }
        updateToleranceVisible()
        return true
    }

    private var subjectState = Subject.NOT_ASKED
    private var subjectJob: Job? = null

    private enum class Subject { NOT_ASKED, FINDING, FOUND, UNAVAILABLE }

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
        // One cut or the other: the smart one, or the plain-background one.
        binding.removeBackground.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) binding.smartCut.isChecked = false
            render()
        }
        binding.smartCut.addOnCheckedChangeListener { _, isChecked ->
            if (isChecked) binding.removeBackground.isChecked = false
            render()
        }
        binding.smartKind.addOnButtonCheckedListener { _, _, isChecked -> if (isChecked) render() }
        binding.smartKeep.addOnChangeListener { _, _, fromUser -> if (fromUser) render() }
        binding.pickStart.setOnClickListener { startPicking() }
        binding.pickDone.setOnClickListener { stopPicking() }
        binding.pickUndo.setOnClickListener {
            if (taps.isNotEmpty()) taps.removeAt(taps.size - 1)
            tapsChanged()
        }
        binding.pickClear.setOnClickListener {
            taps.clear()
            tapsChanged()
        }
        binding.pickView.onTap = { x, y ->
            // Any tap is worth asking the tap model about; without it, only one on something found.
            if (!tapsUnavailable || com.lightdecoder.sticker.SubjectPick.lands(subjects?.pick(smartKind()), original, x, y)) {
                taps += Triple(x, y, binding.pickMode.checkedButtonId != R.id.pickRemove)
                tapsChanged()
            } else {
                Toast.makeText(this, R.string.sticker_pick_missed, Toast.LENGTH_LONG).show()
            }
        }
        binding.caption.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = render(refreshBrushedPicture = false)
        })
        binding.saveAsNew.setOnClickListener { save(asNew = true) }
        binding.refineStart.setOnClickListener { startRefining() }
        binding.refineDone.setOnClickListener { stopRefining() }
        binding.brushMode.addOnButtonCheckedListener { _, id, isChecked ->
            if (isChecked) binding.refine.keep = id == R.id.brushKeep
        }
        binding.brushSize.addOnChangeListener { _, value, _ ->
            binding.refine.brushSizePx = value * resources.displayMetrics.density
        }
        binding.brushUndo.setOnClickListener { binding.refine.undo() }
        binding.brushRedo.setOnClickListener { binding.refine.redo() }
        // Undoable: Redo brings the cleared strokes back.
        binding.brushReset.setOnClickListener { binding.refine.clearByUser() }
        binding.refine.onStrokesChanged = {
            updateBrushButtons()
            render(refreshBrushedPicture = false)
        }
        binding.outline.setOnCheckedChangeListener { _, _ -> render(refreshBrushedPicture = false) }
        // While the slider moves the preview follows; only the newest request is shown.
        binding.outlineWidth.addOnChangeListener { _, _, fromUser -> if (fromUser) render(refreshBrushedPicture = false) }
        binding.solid.setOnCheckedChangeListener { _, _ -> render() }
        binding.sharpen.addOnChangeListener { _, _, fromUser -> if (fromUser) render(refreshBrushedPicture = false) }
        binding.shape.addOnButtonCheckedListener { _, _, isChecked -> if (isChecked) render() }
        binding.tolerance.addOnChangeListener { _, _, fromUser -> if (fromUser) render() }
        binding.save.setOnClickListener { save(asNew = false) }
        binding.trace.setOnClickListener {
            stopRefining()
            startTracing()
        }
        binding.traceUndo.setOnClickListener {
            undoneTrace = trace
            source = original
            trace = null
            binding.traceUndo.visibility = View.GONE
            binding.traceRedo.visibility = if (undoneTrace != null) View.VISIBLE else View.GONE
            render()
        }
        binding.traceRedo.setOnClickListener {
            val again = undoneTrace ?: return@setOnClickListener
            val picture = original ?: return@setOnClickListener
            undoneTrace = null
            trace = again
            source = StickerMaker.cutToPath(picture, StickerProject.outlineOf(again))
            binding.traceRedo.visibility = View.GONE
            binding.traceUndo.visibility = View.VISIBLE
            render()
        }
        binding.resetOriginal.setOnClickListener { resetToOriginal() }
        binding.lasso.onTooSmall = {
            Toast.makeText(this, R.string.sticker_trace_too_small, Toast.LENGTH_SHORT).show()
        }
        binding.lasso.onTraced = { _, points ->
            // Cut from the original, so the same cut can be made again when the sticker is edited
            // later; along a smooth curve through the finger's line, not its straight pieces.
            original?.let { source = StickerMaker.cutToPath(it, StickerProject.outlineOf(points)) }
            trace = points
            undoneTrace = null
            binding.traceRedo.visibility = View.GONE
            binding.traceUndo.visibility = View.VISIBLE
            stopTracing()
            render()
        }
        updateToleranceVisible()

        val edit = intent.getStringExtra(EXTRA_EDIT)?.let(::File)
        if (edit != null) {
            supportActionBar?.setTitle(R.string.sticker_edit_title)
            binding.pick.visibility = View.GONE
            // Save keeps this sticker up to date; Save as new leaves it and adds another.
            binding.save.setText(R.string.sticker_save_replace)
            binding.saveAsNew.visibility = View.VISIBLE
            loadForEdit(edit)
        } else {
            savedInstanceState?.getString(STATE_URI)?.let { load(Uri.parse(it)) }
        }
    }

    override fun onDestroy() {
        tapJob?.cancel()
        val studied = tapper
        tapper = null
        if (studied != null) Thread { studied.close() }.start()
        super.onDestroy()
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
            forgetSubject()
            applyEdits(project.edits)
            binding.trace.isEnabled = true
            binding.refineStart.isEnabled = true
            binding.smartCut.isEnabled = true
            binding.resetOriginal.visibility = View.VISIBLE
            render()
        }
    }

    /** Puts the controls, the traced cut and the brush strokes as [edits] has them. */
    private fun applyEdits(edits: StickerProject.Edits) {
        val picture = original ?: return
        trace = edits.trace
        undoneTrace = null
        binding.traceRedo.visibility = View.GONE
        source = edits.trace?.let { StickerMaker.cutToPath(picture, StickerProject.outlineOf(it)) } ?: picture
        binding.traceUndo.visibility = if (edits.trace != null) View.VISIBLE else View.GONE
        val o = edits.options
        binding.smartCut.isChecked = o.removeBackground && o.smart
        binding.removeBackground.isChecked = o.removeBackground && !o.smart
        binding.smartKind.check(
            when (o.smartKind) {
                StickerMaker.SmartKind.PEOPLE -> R.id.kindPeople
                StickerMaker.SmartKind.OBJECTS -> R.id.kindObjects
                StickerMaker.SmartKind.BOTH -> R.id.kindBoth
                StickerMaker.SmartKind.AUTO -> R.id.kindAuto
            }
        )
        binding.smartKeep.value = o.smartKeep.toFloat().coerceIn(binding.smartKeep.valueFrom, binding.smartKeep.valueTo)
        if (binding.caption.text?.toString() != o.caption) binding.caption.setText(o.caption)
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
        binding.sharpen.value = o.sharpen.toFloat().coerceIn(binding.sharpen.valueFrom, binding.sharpen.valueTo)
        binding.refine.setStrokes(edits.strokes)
        taps.clear()
        for (i in 0 until edits.keepTaps.size / 2) taps += Triple(edits.keepTaps[2 * i], edits.keepTaps[2 * i + 1], true)
        for (i in 0 until edits.removeTaps.size / 2) taps += Triple(edits.removeTaps[2 * i], edits.removeTaps[2 * i + 1], false)
        updateBrushButtons()
        stopPicking()
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
            forgetSubject()
            trace = null
            undoneTrace = null
            binding.traceRedo.visibility = View.GONE
            binding.resetOriginal.visibility = View.VISIBLE
            // A new picture: the brushing and the taps were for the old one.
            taps.clear()
            stopPicking()
            binding.refine.clearStrokes()
            updateBrushButtons()
            stopRefining()
            binding.trace.isEnabled = true
            binding.refineStart.isEnabled = true
            binding.smartCut.isEnabled = true
            binding.traceUndo.visibility = View.GONE
            stopTracing()
            render()
        }
    }

    /** The picture replaces the preview; a line drawn around its subject cuts it out. */
    private fun startTracing() {
        val picture = source ?: return
        stopPicking()
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
        stopPicking()
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

    private val picking: Boolean get() = binding.pickView.visibility == View.VISIBLE

    /**
     * The picture replaces the preview, with what the cut drops dimmed; the
     * user taps the people or objects to keep (or, in Remove, to drop).
     */
    private fun startPicking() {
        if (original == null) return
        stopTracing()
        stopRefining()
        binding.pickView.visibility = View.VISIBLE
        binding.pickPanel.visibility = View.VISIBLE
        binding.preview.visibility = View.GONE
        binding.hint.setText(R.string.sticker_choose_hint)
        binding.hint.visibility = View.VISIBLE
        binding.page.post { binding.page.smoothScrollTo(0, binding.pickView.top) }
        refreshPickView()
    }

    private fun stopPicking() {
        if (!picking) return
        binding.pickView.visibility = View.GONE
        binding.pickPanel.visibility = View.GONE
        binding.preview.visibility = View.VISIBLE
        binding.hint.visibility = View.GONE
    }

    private fun tapsChanged() {
        binding.pickUndo.isEnabled = taps.isNotEmpty()
        binding.pickClear.isEnabled = taps.isNotEmpty()
        refreshPickView()
        render()
    }

    private var pickJob: Job? = null

    /** The picture as the cut stands: what stays as it is, what goes dimmed. */
    private fun refreshPickView() {
        if (!picking) return
        val picture = original ?: return
        val mask = subject
        val marks = taps.toList()
        val keep = binding.smartKeep.value.toInt()
        pickJob?.cancel()
        pickJob = lifecycleScope.launch {
            val shown = withContext(Dispatchers.Default) {
                try {
                    val kept = picture.copy(Bitmap.Config.ARGB_8888, true)
                    if (mask != null) StickerMaker.keepSubject(kept, mask, keep)
                    val out = Bitmap.createBitmap(picture.width, picture.height, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(out)
                    canvas.drawBitmap(picture, 0f, 0f, null)
                    canvas.drawColor(DIM)
                    canvas.drawBitmap(kept, 0f, 0f, null)
                    out
                } catch (e: OutOfMemoryError) {
                    null
                }
            } ?: return@launch
            binding.pickView.show(shown, marks)
        }
    }

    private fun updateBrushButtons() {
        val any = binding.refine.strokes.isNotEmpty()
        binding.brushUndo.isEnabled = any
        binding.brushRedo.isEnabled = binding.refine.canRedo
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
                    StickerMaker.cutOut(picture, options, subject = subject)
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

    private fun smartKind() = when (binding.smartKind.checkedButtonId) {
        R.id.kindPeople -> StickerMaker.SmartKind.PEOPLE
        R.id.kindObjects -> StickerMaker.SmartKind.OBJECTS
        R.id.kindBoth -> StickerMaker.SmartKind.BOTH
        else -> StickerMaker.SmartKind.AUTO
    }

    private fun options() = StickerMaker.Options(
        removeBackground = binding.removeBackground.isChecked || binding.smartCut.isChecked,
        tolerance = binding.tolerance.value.toInt(),
        shape = when (binding.shape.checkedButtonId) {
            R.id.shapeRounded -> StickerMaker.Shape.ROUNDED
            R.id.shapeCircle -> StickerMaker.Shape.CIRCLE
            else -> StickerMaker.Shape.ORIGINAL
        },
        outline = binding.outline.isChecked,
        outlineWidth = binding.outlineWidth.value.toInt(),
        solidBackground = binding.solid.isChecked,
        sharpen = binding.sharpen.value.toInt(),
        smart = binding.smartCut.isChecked,
        smartKind = smartKind(),
        smartKeep = binding.smartKeep.value.toInt(),
        caption = StickerMaker.captionOf(binding.caption.text?.toString().orEmpty()),
    )

    /** A new picture: what was found in the old one does not apply. */
    private fun forgetSubject() {
        subjectJob?.cancel()
        subjects = null
        tapJob?.cancel()
        tapJob = null
        val studied = tapper
        tapper = null
        tapMasks.clear()
        tapsUnavailable = false
        tappedFrom = null
        // Off the main thread, and after any tap it is still answering.
        if (studied != null) lifecycleScope.launch(Dispatchers.IO) { studied.close() }
        subjectState = Subject.NOT_ASKED
    }

    private val wantsSubject: Boolean get() = binding.smartCut.isChecked

    /**
     * Looks for the picture's subject, once. True while the answer is still
     * awaited: the preview is made when it arrives.
     */
    private fun findingSubject(): Boolean {
        if (!wantsSubject) return false
        if (subjectState == Subject.FINDING) return true
        if (subjectState != Subject.NOT_ASKED) return false
        val picture = original ?: return false
        subjectState = Subject.FINDING
        updateToleranceVisible()
        subjectJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                com.lightdecoder.sticker.SubjectCutter.find(applicationContext, picture)
            }
            if (picture !== original) return@launch
            when (result) {
                is com.lightdecoder.sticker.SubjectCutter.Result.Found -> {
                    subjects = result.subjects
                    subjectState = Subject.FOUND
                }
                com.lightdecoder.sticker.SubjectCutter.Result.Unavailable -> subjectState = Subject.UNAVAILABLE
            }
            render()
        }
        return true
    }

    private fun updateToleranceVisible() {
        binding.smartPanel.visibility = if (wantsSubject) View.VISIBLE else View.GONE
        val settled = subjectState == Subject.FOUND || subjectState == Subject.UNAVAILABLE
        val working = tapJob?.isActive == true
        val mask = if (wantsSubject && settled && !working) subject else null
        // Found, but nothing of the kind chosen (no people in it, say), and nothing tapped.
        val nothing = wantsSubject && settled && !working && mask == null
        val status = when {
            !wantsSubject -> 0
            subjectState == Subject.FINDING -> R.string.sticker_smart_finding
            working -> R.string.sticker_pick_working
            nothing && subjectState == Subject.UNAVAILABLE -> R.string.sticker_smart_unavailable
            nothing -> R.string.sticker_smart_nothing
            else -> 0
        }
        if (status != 0) binding.smartStatus.setText(status)
        binding.smartStatus.visibility = if (status != 0) View.VISIBLE else View.GONE
        // The plain-colour slider is for the cut that is actually in use: the plain one, also
        // when the smart cut has nothing to go by.
        val keeping = if (mask != null) View.VISIBLE else View.GONE
        binding.smartKeep.visibility = keeping
        binding.smartKeepLabel.visibility = keeping
        // Tapping can add what was not found, so it is offered whatever was.
        val choosable = wantsSubject && settled
        binding.pickStart.visibility = if (choosable) View.VISIBLE else View.GONE
        binding.pickStart.text =
            if (taps.isEmpty()) getString(R.string.sticker_pick_start) else getString(R.string.sticker_pick_start_some, taps.size)
        if (!choosable) stopPicking()
        val plain = binding.removeBackground.isChecked || nothing
        val shown = if (plain) View.VISIBLE else View.GONE
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
        if (findingSubject()) return
        if (resolvingTaps()) return
        updateToleranceVisible()
        refreshPickView()
        val options = options()
        val subject = subject
        // The options changed what the brushes paint on (a stroke alone does not).
        if (refining && refreshBrushedPicture) refreshRefineBase()
        val strokes = binding.refine.strokes.toList()
        val edits = original?.takeIf { strokes.isNotEmpty() }?.let { StickerMaker.Edits(it, strokes) }
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            val made = withContext(Dispatchers.Default) {
                try {
                    StickerMaker.make(picture, options, edits, subject)
                } catch (e: OutOfMemoryError) {
                    null
                }
            } ?: return@launch
            sticker = made
            binding.preview.setFrames(listOf(HeifFrame(made, 0)))
            binding.save.isEnabled = true
            binding.saveAsNew.isEnabled = true
        }
    }

    /** Saves the sticker; [asNew] while editing adds it beside the one being edited instead of replacing that. */
    private fun save(asNew: Boolean) {
        val made = sticker ?: return
        val from = original
        val edits = StickerProject.Edits(options(), trace, binding.refine.strokes.toList(), tapPoints(true), tapPoints(false))
        val target = editing.takeUnless { asNew }
        binding.save.isEnabled = false
        binding.saveAsNew.isEnabled = false
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
                binding.saveAsNew.isEnabled = true
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

        /** Over what the cut drops, while choosing who or what to keep. */
        private const val DIM = 0xB3000000.toInt()

        fun intent(context: Context) = Intent(context, StickerMakerActivity::class.java)

        /** Opens [sticker] of the library for editing; saving replaces it. */
        fun editIntent(context: Context, sticker: File) = intent(context).putExtra(EXTRA_EDIT, sticker.path)
    }
}
