package com.lightdecoder.ui.imessage

import android.graphics.Matrix
import android.graphics.RectF
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.imessage.facetime.Media
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The call screen's pictures keep their shape whatever the screen: a TextureView stretches what it
 * is given to its own shape, and the transform must undo that, turn the picture upright and fill or
 * fit it. Checked on a usual phone's shape and on a Galaxy Z Fold 7's (inner screen 1968 x 2184,
 * near square; cover screen 1080 x 2520).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoFitTest {

    /** Where the picture lands: the TextureView first stretches it over the whole view, then the transform moves it. */
    private fun shown(m: Matrix, vw: Int, vh: Int): RectF = RectF(0f, 0f, vw.toFloat(), vh.toFloat()).also(m::mapRect)

    private fun aspect(r: RectF) = r.width() / r.height()

    @Test
    fun anIPhoneHeldUprightIsShownUprightAndUnstretchedOnTheFoldsInnerScreen() {
        // The iPhone's encoder makes 1920 x 1080 pictures and says "turn a quarter" (0 or 1).
        for (rotation in listOf(0, 1)) {
            val m = FaceTimeCallActivity.remoteTransform(1968, 2184, 1920, 1080, rotation)
            val r = shown(m, 1968, 2184)
            assertEquals("upright 9:16", 1080f / 1920f, aspect(r), 0.005f)
            // A 9:16 picture on a near-square screen is shown whole (not cut to a sliver): the full height, bars at the sides.
            assertEquals(2184f, r.height(), 1f)
            assertTrue(r.left > 0f && r.right < 1968f)
            assertEquals(1968f / 2, r.centerX(), 1f)
        }
    }

    @Test
    fun onAUsualPhoneAnUprightPictureFillsTheScreenWithItsSidesCut() {
        val m = FaceTimeCallActivity.remoteTransform(1080, 2340, 1920, 1080, 1)
        val r = shown(m, 1080, 2340)
        assertEquals(1080f / 1920f, aspect(r), 0.005f)
        assertEquals(2340f, r.height(), 1f)
        assertTrue(r.left < 0f && r.right > 1080f)
        // On the Fold's tall cover screen too.
        val c = shown(FaceTimeCallActivity.remoteTransform(1080, 2520, 1920, 1080, 0), 1080, 2520)
        assertEquals(1080f / 1920f, aspect(c), 0.005f)
    }

    @Test
    fun aPictureNotTurnedOrTurnedHalfKeepsItsShape() {
        for (rotation in listOf(Media.ROTATION_NONE, 2)) {
            val r = shown(FaceTimeCallActivity.remoteTransform(1968, 2184, 1920, 1080, rotation), 1968, 2184)
            assertEquals(1920f / 1080f, aspect(r), 0.005f)
            assertEquals(1968f, r.width(), 1f)
        }
    }

    @Test
    fun aDecoderThatTurnsThePictureItselfIsNotTurnedAgain() {
        // The view then already gets it upright, stretched: only its shape is put right.
        val m = FaceTimeCallActivity.remoteTransform(1968, 2184, 1920, 1080, 0, decoderTurns = true)
        val values = FloatArray(9).also(m::getValues)
        assertEquals(0f, values[Matrix.MSKEW_X], 0.0001f)
        assertEquals(0f, values[Matrix.MSKEW_Y], 0.0001f)
        assertEquals(1080f / 1920f, aspect(shown(m, 1968, 2184)), 0.005f)
        // A decoder's usual transform (texture y flipped) is not a turn; a quarter or half turn is.
        assertFalse(VideoFit.textureIsTurned(floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)))
        assertTrue(VideoFit.textureIsTurned(floatArrayOf(0f, -1f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)))
        assertTrue(VideoFit.textureIsTurned(floatArrayOf(-1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f)))
        assertFalse(VideoFit.textureIsTurned(FloatArray(3)))
    }

    @Test
    fun pinchZoomsAboutTheFingersAndADragStopsAtThePicturesEdge() {
        val zoom = VideoFit.Zoom()
        // Zoomed twice about the middle: twice the size, still centered.
        zoom.scaleBy(2f, 0f, 0f)
        val r = shown(FaceTimeCallActivity.remoteTransform(1968, 2184, 1920, 1080, 1, zoom = zoom), 1968, 2184)
        assertEquals(2 * 2184f, r.height(), 2f)
        assertEquals(1080f / 1920f, aspect(r), 0.005f)
        // A long drag right goes only as far as the picture's left edge reaching the screen's.
        zoom.panX += 10_000f
        val dragged = shown(FaceTimeCallActivity.remoteTransform(1968, 2184, 1920, 1080, 1, zoom = zoom), 1968, 2184)
        assertEquals(0f, dragged.left, 1f)
        // Never beyond the most zoom, never below fitted.
        zoom.scaleBy(100f, 0f, 0f)
        assertEquals(VideoFit.MAX_ZOOM, zoom.zoom, 0.001f)
        zoom.scaleBy(0.001f, 0f, 0f)
        assertEquals(1f, zoom.zoom, 0.001f)
        val back = shown(FaceTimeCallActivity.remoteTransform(1968, 2184, 1920, 1080, 1, zoom = zoom), 1968, 2184)
        assertEquals(1968f / 2, back.centerX(), 1f)
    }

    @Test
    fun aDoubleTapsChoiceOfFilledOrWholeIsKept() {
        val zoom = VideoFit.Zoom().apply { fill = true }
        val r = shown(FaceTimeCallActivity.remoteTransform(1968, 2184, 1920, 1080, 1, zoom = zoom), 1968, 2184)
        assertEquals(1968f, r.width(), 1f)
        assertTrue(r.height() > 2184f)
        zoom.fill = false
        val whole = shown(FaceTimeCallActivity.remoteTransform(1080, 2340, 1920, 1080, 1, zoom = zoom), 1080, 2340)
        assertEquals(1080f, whole.width(), 1f)
        assertTrue(whole.height() < 2340f)
    }

    @Test
    fun theOwnCameraKeepsItsShapeHoweverTheCameraIsMounted() {
        // Usual phone (90): the 1280 x 720 picture comes upright as 720 x 1280.
        for (rotation in 0..3) {
            val m = FaceTimeCallActivity.previewTransform(200, 300, rotation, 90)
            val r = shown(m, 200, 300)
            val expected = if (rotation % 2 == 0) 720f / 1280f else 1280f / 720f
            assertEquals("rotation $rotation", expected, aspect(r), 0.005f)
            // It fills the small view.
            assertTrue(r.width() >= 199f && r.height() >= 299f)
        }
        // A camera mounted along the screen's natural way (0 or 180): the picture is 1280 x 720, not squeezed into 9:16.
        for (sensor in listOf(0, 180)) {
            val r = shown(FaceTimeCallActivity.previewTransform(200, 300, Surface.ROTATION_0, sensor), 200, 300)
            assertEquals(1280f / 720f, aspect(r), 0.005f)
        }
    }

    @Test
    fun aStreamsRotationIsTurnedTheWayTheLibrarySaysIt() {
        assertEquals(listOf(90, 270, 180, 0), (0..3).map(VideoFit::degreesForStream))
        assertEquals(0, VideoFit.degreesForStream(-1))
        assertTrue(VideoFit.fillsByDefault(1080, 2340, 1080, 1920))
        assertFalse(VideoFit.fillsByDefault(1968, 2184, 1080, 1920))
    }
}
