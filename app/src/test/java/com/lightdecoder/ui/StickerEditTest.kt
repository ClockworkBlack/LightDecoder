package com.lightdecoder.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.lightdecoder.R
import com.lightdecoder.sticker.StickerLibrary
import com.lightdecoder.sticker.StickerMaker
import com.lightdecoder.sticker.StickerProject
import com.lightdecoder.ui.sticker.StickerMakerActivity
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration

/** Editing a sticker of the library: how it was made comes back, saving replaces it, and it can go back to its picture. */
@RunWith(AndroidJUnit4::class)
class StickerEditTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() = StickerLibrary.list(context).forEach { StickerLibrary.delete(context, it) }

    @After
    fun tearDown() = StickerLibrary.list(context).forEach { StickerLibrary.delete(context, it) }

    /** A red square in the middle of a plain green picture. */
    private fun picture(): Bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
        val c = Canvas(this)
        c.drawColor(Color.rgb(20, 200, 30))
        c.drawRect(60f, 60f, 140f, 140f, Paint().apply { color = Color.RED })
    }

    @Test
    fun howAStickerWasMadeIsKeptExactly() {
        val edits = StickerProject.Edits(
            StickerMaker.Options(removeBackground = true, tolerance = 33, shape = StickerMaker.Shape.CIRCLE, outline = true, outlineWidth = 27, solidBackground = true),
            trace = floatArrayOf(1f, 2f, 30f, 4f, 50f, 60f),
            strokes = listOf(StickerMaker.Stroke.of(floatArrayOf(5f, 5f, 9f, 9f), 7f, keep = true)),
        )
        val back = StickerProject.fromJson(StickerProject.toJson(edits))
        assertEquals(edits.options, back.options)
        assertArrayEquals(edits.trace, back.trace, 0f)
        assertEquals(1, back.strokes.size)
        assertArrayEquals(floatArrayOf(5f, 5f, 9f, 9f), back.strokes[0].points, 0f)
        assertEquals(7f, back.strokes[0].width, 0f)
        assertTrue(back.strokes[0].keep)
    }

    @Test
    fun aStickerIsEditedInPlaceAndCanGoBackToItsPicture() {
        val original = picture()
        val options = StickerMaker.Options(removeBackground = true, outline = true, outlineWidth = 4)
        val file = StickerLibrary.save(context, StickerMaker.make(original, options))
        StickerProject.save(context, file, original, StickerProject.Edits(options, null, emptyList()))

        ActivityScenario.launch<StickerMakerActivity>(StickerMakerActivity.editIntent(context, file)).use { scenario ->
            waitFor(scenario, "the sticker") { it.findViewById<View>(R.id.save).isEnabled }
            scenario.onActivity { a ->
                assertTrue("as it was made", a.findViewById<MaterialSwitch>(R.id.removeBackground).isChecked)
                assertTrue(a.findViewById<MaterialSwitch>(R.id.outline).isChecked)
                assertEquals(4f, a.findViewById<Slider>(R.id.outlineWidth).value, 0f)
                assertTrue(a.findViewById<View>(R.id.resetOriginal).isShown)
                assertFalse("no other picture is chosen while editing", a.findViewById<View>(R.id.pick).isShown)
                // Back to the original picture.
                a.findViewById<View>(R.id.resetOriginal).performClick()
                assertFalse(a.findViewById<MaterialSwitch>(R.id.removeBackground).isChecked)
                assertFalse(a.findViewById<MaterialSwitch>(R.id.outline).isChecked)
            }
            settle()
            scenario.onActivity { it.findViewById<View>(R.id.save).performClick() }
            waitFor(scenario, "the save") { it.isFinishing }
        }
        assertEquals("replaced, not added", listOf(file), StickerLibrary.list(context))
        val saved = BitmapFactory.decodeFile(file.path)
        // The whole picture again: its green background is back, and there is no white outline.
        val pixels = IntArray(saved.width * saved.height).also { saved.getPixels(it, 0, saved.width, 0, 0, saved.width, saved.height) }
        assertTrue(pixels.any { Color.green(it) > 180 && Color.red(it) < 60 && Color.alpha(it) > 200 })
        assertFalse(pixels.any { Color.alpha(it) > 200 && Color.red(it) > 240 && Color.green(it) > 240 && Color.blue(it) > 240 })
        val project = StickerProject.load(context, file)
        assertNotNull(project)
        assertFalse(project!!.edits.options.removeBackground)
    }

    @Test
    fun savingAsNewKeepsTheStickerAndAddsAnother() {
        val original = picture()
        val options = StickerMaker.Options(outline = false)
        val file = StickerLibrary.save(context, StickerMaker.make(original, options))
        StickerProject.save(context, file, original, StickerProject.Edits(options, null, emptyList()))
        val before = file.readBytes()

        ActivityScenario.launch<StickerMakerActivity>(StickerMakerActivity.editIntent(context, file)).use { scenario ->
            waitFor(scenario, "the sticker") { it.findViewById<View>(R.id.saveAsNew).isEnabled }
            scenario.onActivity { a ->
                assertTrue(a.findViewById<View>(R.id.saveAsNew).isShown)
                // A white-background version with a title, kept beside the first.
                a.findViewById<MaterialSwitch>(R.id.solid).isChecked = true
                a.findViewById<android.widget.EditText>(R.id.caption).setText("Mood")
            }
            settle()
            scenario.onActivity { it.findViewById<View>(R.id.saveAsNew).performClick() }
            waitFor(scenario, "the save") { it.isFinishing }
        }
        val all = StickerLibrary.list(context)
        assertEquals("one more sticker", 2, all.size)
        assertArrayEquals("the first is as it was", before, file.readBytes())
        assertFalse(StickerProject.load(context, file)!!.edits.options.solidBackground)
        val added = all.first { it != file }
        val edits = StickerProject.load(context, added)!!.edits
        assertTrue(edits.options.solidBackground)
        assertEquals("Mood", edits.options.caption)
        assertEquals("white behind it", Color.WHITE, BitmapFactory.decodeFile(added.path).getPixel(1, 1))
    }

    @Test
    fun aNewStickerHasNoSaveAsNew() {
        ActivityScenario.launch(StickerMakerActivity::class.java).use { scenario ->
            scenario.onActivity { assertFalse(it.findViewById<View>(R.id.saveAsNew).isShown) }
        }
    }

    @Test
    fun theSmartCutOutIsUsedWhenTheBackgroundIsRemoved() {
        val original = picture()
        val options = StickerMaker.Options(outline = false)
        val file = StickerLibrary.save(context, StickerMaker.make(original, options))
        StickerProject.save(context, file, original, StickerProject.Edits(options, null, emptyList()))
        // The subject, says the model, is the picture's left half.
        var asked = 0
        val real = com.lightdecoder.sticker.SubjectCutter.engine
        com.lightdecoder.sticker.SubjectCutter.engine = com.lightdecoder.sticker.SubjectCutter.Engine { _, p ->
            asked++
            com.lightdecoder.sticker.SubjectCutter.Result.Found(
                com.lightdecoder.sticker.SubjectMask(p.width, p.height, ByteArray(p.width * p.height) { if (it % p.width < p.width / 2) 255.toByte() else 0 })
            )
        }
        try {
            ActivityScenario.launch<StickerMakerActivity>(StickerMakerActivity.editIntent(context, file)).use { scenario ->
                waitFor(scenario, "the sticker") { it.findViewById<View>(R.id.save).isEnabled }
                scenario.onActivity { a ->
                    assertFalse("offered with background removal", a.findViewById<View>(R.id.smart).isShown)
                    a.findViewById<MaterialSwitch>(R.id.smart).isChecked = true
                    a.findViewById<MaterialSwitch>(R.id.removeBackground).isChecked = true
                    assertTrue(a.findViewById<View>(R.id.smart).isShown)
                    assertFalse("the plain-colour slider is for the other cut", a.findViewById<View>(R.id.tolerance).isShown)
                }
                waitFor(scenario, "the subject") { asked > 0 && !it.findViewById<View>(R.id.smartStatus).isShown }
                settle()
                scenario.onActivity { a ->
                    // Changing something else does not look for the subject again.
                    a.findViewById<MaterialSwitch>(R.id.solid).isChecked = true
                    a.findViewById<MaterialSwitch>(R.id.solid).isChecked = false
                }
                settle()
                scenario.onActivity { it.findViewById<View>(R.id.save).performClick() }
                waitFor(scenario, "the save") { it.isFinishing }
            }
        } finally {
            com.lightdecoder.sticker.SubjectCutter.engine = real
        }
        assertEquals("found once for the picture", 1, asked)
        val saved = BitmapFactory.decodeFile(file.path)
        // Only the left half was kept, so the red square's right part is gone: the sticker
        // (fitted to what is left) is taller than wide.
        val pixels = IntArray(saved.width * saved.height).also { saved.getPixels(it, 0, saved.width, 0, 0, saved.width, saved.height) }
        val columns = (0 until saved.width).count { x -> (0 until saved.height).any { y -> Color.alpha(pixels[y * saved.width + x]) > 200 } }
        val rows = (0 until saved.height).count { y -> (0 until saved.width).any { x -> Color.alpha(pixels[y * saved.width + x]) > 200 } }
        assertTrue("$columns columns, $rows rows", columns < rows * 0.7)
        assertTrue(StickerProject.load(context, file)!!.edits.options.smart)
    }

    @Test
    fun anOlderStickerWithoutItsPictureCanStillBeEdited() {
        val file = StickerLibrary.save(context, picture())
        ActivityScenario.launch<StickerMakerActivity>(StickerMakerActivity.editIntent(context, file)).use { scenario ->
            waitFor(scenario, "the sticker") { it.findViewById<View>(R.id.save).isEnabled }
            scenario.onActivity { a ->
                assertFalse("its outline is already in the picture", a.findViewById<MaterialSwitch>(R.id.outline).isChecked)
            }
        }
    }

    @Test
    fun onlyTheLibrarysOwnStickersOpen() {
        val elsewhere = File(context.cacheDir, "x.png").apply { outputStream().use { picture().compress(Bitmap.CompressFormat.PNG, 100, it) } }
        ActivityScenario.launch<StickerMakerActivity>(StickerMakerActivity.editIntent(context, elsewhere)).use { scenario ->
            waitFor(scenario, "it to close") { it.isFinishing }
        }
    }

    private fun settle() = repeat(5) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        Thread.sleep(20)
    }

    private fun <A : Activity> waitFor(scenario: ActivityScenario<A>, what: String, condition: (A) -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            var ok = false
            if (scenario.state != androidx.lifecycle.Lifecycle.State.DESTROYED) scenario.onActivity { ok = condition(it) } else ok = true
            if (ok) return
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Test
    fun anUndoneCutCanBeRedone() {
        val original = picture()
        val options = StickerMaker.Options(outline = false)
        val trace = floatArrayOf(50f, 50f, 150f, 50f, 150f, 150f, 50f, 150f)
        val file = StickerLibrary.save(context, StickerMaker.make(original, options))
        StickerProject.save(context, file, original, StickerProject.Edits(options, trace, emptyList()))
        ActivityScenario.launch<StickerMakerActivity>(StickerMakerActivity.editIntent(context, file)).use { scenario ->
            waitFor(scenario, "the sticker") { it.findViewById<View>(R.id.save).isEnabled }
            scenario.onActivity { a ->
                assertTrue(a.findViewById<View>(R.id.traceUndo).isShown)
                assertFalse(a.findViewById<View>(R.id.traceRedo).isShown)
                a.findViewById<View>(R.id.traceUndo).performClick()
                assertTrue(a.findViewById<View>(R.id.traceRedo).isShown)
                a.findViewById<View>(R.id.traceRedo).performClick()
                assertTrue("the cut is back", a.findViewById<View>(R.id.traceUndo).isShown)
                assertFalse(a.findViewById<View>(R.id.traceRedo).isShown)
            }
            settle()
            scenario.onActivity { it.findViewById<View>(R.id.save).performClick() }
            waitFor(scenario, "the save") { it.isFinishing }
        }
        assertArrayEquals("the redone cut is what is kept", trace, StickerProject.load(context, file)!!.edits.trace, 0f)
    }
}
