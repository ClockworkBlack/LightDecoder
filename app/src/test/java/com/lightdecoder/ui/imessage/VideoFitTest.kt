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
            val m = FaceTimeCallActivity.remoteTransform(1968, 2184, 1920, 1080, rotation, turn = VideoFit.Turn.NONE)
            val r = shown(m, 1968, 2184)
            assertEquals("upright 9:16", 1080f / 1920f, aspect(r), 0.005f)
            // A 9:16 picture on the unfolded screen fills all of it, as a messenger's call does: the full width, top and bottom cut.
            assertEquals(1968f, r.width(), 1f)
            assertTrue(r.top < 0f && r.bottom > 2184f)
            assertEquals(1968f / 2, r.centerX(), 1f)
            assertEquals(2184f / 2, r.centerY(), 1f)
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
            // On the unfolded screen it fills too: the full height, its sides cut.
            assertEquals(2184f, r.height(), 1f)
        }
    }

    @Test
    fun aDecoderThatTurnsThePictureItselfIsNotTurnedAgain() {
        // The view then already gets it upright, stretched: only its shape is put right.
        val m = FaceTimeCallActivity.remoteTransform(1968, 2184, 1920, 1080, 0, turn = VideoFit.Turn.QUARTER)
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

    private val quarter = VideoFit.Turn.QUARTER
    private val asItIs = VideoFit.Turn.NONE

    /** The picture's width over its height as it ends up on the screen, and whether the screen turns it. */
    private fun laidOut(viewWidth: Int, viewHeight: Int, width: Int, height: Int, rotation: Int, turn: VideoFit.Turn?): Pair<Float, Boolean> {
        val m = FaceTimeCallActivity.remoteTransform(viewWidth, viewHeight, width, height, rotation, turn)
        val values = FloatArray(9).also(m::getValues)
        val turned = kotlin.math.abs(values[Matrix.MSKEW_X]) > 0.0001f || values[Matrix.MSCALE_X] < 0f
        return aspect(shown(m, viewWidth, viewHeight)) to turned
    }

    @Test
    fun anUprightIPhonesPictureIsNotPulledWideWhenTheLibrarySaysNoRotation() {
        // What looked like a fun house (0.5.73): the iPhone's 1920 x 1080 frames come turned upright by
        // the decoder, the library says no rotation for them (it does not say the one a stream starts
        // with), and the upright picture was given the shape it has lying on its side: 16:9, over three times too wide.
        for ((vw, vh) in listOf(1968 to 2184, 1080 to 2520, 1080 to 2340)) {
            val (shape, turned) = laidOut(vw, vh, 1920, 1080, Media.ROTATION_UNKNOWN, quarter)
            assertEquals("upright 9:16 in a $vw x $vh view", 1080f / 1920f, shape, 0.005f)
            assertFalse("not turned a second time", turned)
        }
        // Before its first frame it is laid out as it will come (an upright phone's).
        assertEquals(1080f / 1920f, laidOut(1968, 2184, 1920, 1080, Media.ROTATION_UNKNOWN, null).first, 0.005f)
    }

    @Test
    fun thePicturesShapeGoesByHowItsFramesComeWhateverWasSaid() {
        // Frames turned a quarter by the decoder: upright, sides changed over, whatever rotation was said (late, wrong, or none).
        for (rotation in listOf(Media.ROTATION_UNKNOWN, 0, 1, 2, Media.ROTATION_NONE)) {
            val (shape, turned) = laidOut(1968, 2184, 1920, 1080, rotation, quarter)
            assertEquals("rotation $rotation", 1080f / 1920f, shape, 0.005f)
            assertFalse(turned)
        }
        // Frames turned half: the same shape, the right way up already.
        for (rotation in listOf(Media.ROTATION_UNKNOWN, 0, 2, Media.ROTATION_NONE)) {
            val (shape, turned) = laidOut(1968, 2184, 1920, 1080, rotation, VideoFit.Turn.HALF)
            assertEquals(1920f / 1080f, shape, 0.005f)
            assertFalse(turned)
        }
        // Frames as they are and nothing said, or "not turned" said: a phone on its side, shown as it comes.
        for (rotation in listOf(Media.ROTATION_UNKNOWN, Media.ROTATION_NONE)) {
            val (shape, turned) = laidOut(1968, 2184, 1920, 1080, rotation, asItIs)
            assertEquals(1920f / 1080f, shape, 0.005f)
            assertFalse(turned)
        }
        // Only a picture the library says is turned while its frames are not is turned by the screen.
        for (rotation in listOf(0, 1)) {
            val (shape, turned) = laidOut(1968, 2184, 1920, 1080, rotation, asItIs)
            assertEquals(1080f / 1920f, shape, 0.005f)
            assertTrue(turned)
        }
        assertEquals(VideoFit.Shape(1920, 1080, 180), VideoFit.shape(1920, 1080, 2, asItIs))
        // A 4:3 stream (FaceTime on a slow connection) the same way.
        assertEquals(480f / 640f, laidOut(1080, 2520, 640, 480, Media.ROTATION_UNKNOWN, quarter).first, 0.005f)
        assertEquals(640f / 480f, laidOut(1080, 2520, 640, 480, Media.ROTATION_NONE, asItIs).first, 0.005f)
    }

    @Test
    fun theOtherSideTurningTheirPhoneFollowsTheFramesNotTheOrderThingsAreSaidIn() {
        // Upright, then on its side: the library says "not turned" (3) before the frames change, and after.
        assertEquals(VideoFit.Shape(1080, 1920, 0), VideoFit.shape(1920, 1080, 0, quarter))
        assertEquals("said first, frames still turned", VideoFit.Shape(1080, 1920, 0), VideoFit.shape(1920, 1080, 3, quarter))
        assertEquals("the frames follow", VideoFit.Shape(1920, 1080, 0), VideoFit.shape(1920, 1080, 3, asItIs))
        // On its side, then upright, with the frames changing before it is said.
        assertEquals("frames first", VideoFit.Shape(1080, 1920, 0), VideoFit.shape(1920, 1080, 3, quarter))
        assertEquals(VideoFit.Shape(1080, 1920, 0), VideoFit.shape(1920, 1080, 0, quarter))
        // Nothing to lay out without a size.
        assertFalse(VideoFit.shape(0, 0, 0, quarter).known)
        assertTrue(FaceTimeCallActivity.remoteTransform(1968, 2184, VideoFit.shape(0, 0, Media.ROTATION_UNKNOWN, quarter)).isIdentity)
        // Which turn a frame's transform is (column-major 4x4, as SurfaceTexture gives it).
        assertEquals(asItIs, VideoFit.textureTurn(floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)))
        assertEquals(quarter, VideoFit.textureTurn(floatArrayOf(0f, -1f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)))
        assertEquals(quarter, VideoFit.textureTurn(floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)))
        // With the decoder's own cut of the frame's edge in it (a 1080 picture in a 1088 buffer).
        assertEquals(quarter, VideoFit.textureTurn(floatArrayOf(0f, -0.9926f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0.9963f, 0f, 1f)))
        assertEquals(VideoFit.Turn.HALF, VideoFit.textureTurn(floatArrayOf(-1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f)))
        assertEquals(asItIs, VideoFit.textureTurn(FloatArray(3)))
    }

    @Test
    fun whatIsSaidOfThePictureInTheLogKeepsItsNumbers() {
        val line = FaceTimeCallActivity.pictureLine(1920, 1080, Media.ROTATION_UNKNOWN, quarter, VideoFit.Shape(1080, 1920, 0), 1968, 2184, false)
        assertEquals(
            "the other side's picture is 1920x1080 as decoded (no rotation said; its frames come turned a quarter): " +
                "shown as 1080x1920, whole in a 1968x2184 view",
            line,
        )
        // Sizes are not taken for phone numbers on their way into the log.
        assertEquals(line, com.lightdecoder.imessage.Redact.text(line))
        val turned = FaceTimeCallActivity.pictureLine(1280, 720, 1, asItIs, VideoFit.Shape(720, 1280, 270), 1080, 2520, true)
        assertTrue(turned.contains("rotation 1 said; its frames come as they are") && turned.contains("turned 270 more by the screen") && turned.endsWith("filling a 1080x2520 view"))
        assertEquals(turned, com.lightdecoder.imessage.Redact.text(turned))
    }

    @Test
    fun pinchZoomsAboutTheFingersAndADragStopsAtThePicturesEdge() {
        val zoom = VideoFit.Zoom()
        // Zoomed twice about the middle: twice the size, still centered.
        zoom.scaleBy(2f, 0f, 0f)
        val r = shown(FaceTimeCallActivity.remoteTransform(1968, 2184, 1920, 1080, 1, zoom = zoom), 1968, 2184)
        assertEquals(2 * 1968f, r.width(), 2f)
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
        // The unfolded Fold: an upright picture fills it; one on its side there too; one on its side on an upright phone is shown whole.
        assertTrue(VideoFit.fillsByDefault(1968, 2184, 1080, 1920))
        assertTrue(VideoFit.fillsByDefault(1968, 2184, 1920, 1080))
        assertFalse(VideoFit.fillsByDefault(1080, 2340, 1920, 1080))
    }
}
