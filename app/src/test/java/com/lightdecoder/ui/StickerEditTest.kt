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
}
