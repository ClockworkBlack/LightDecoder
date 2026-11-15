package com.lightdecoder

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.heif.HeifCodec
import com.lightdecoder.heif.Media
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Decodes real HEIC files through the whole stack: the JNI binding, the native
 * decoder, and the conversion into an android.graphics.Bitmap. The native
 * library is built for the test machine by the buildHostDecoder Gradle task.
 */
@RunWith(AndroidJUnit4::class)
class HeifCodecTest {

    @Before
    fun requireDecoder() {
        assumeTrue("native decoder not available on this machine", HeifCodec.isAvailable)
    }

    @Test
    fun versionsReportBothLibraries() {
        val v = HeifCodec.versions
        assertTrue("libheif named: $v", v.contains("libheif"))
        assertTrue("libde265 named: $v", v.contains("libde265"))
    }

    @Test
    fun probeReadsStillMetadata() {
        val info = HeifCodec.probe(TestMedia.bytes("still_alpha.heic"))
        assertEquals(TestMedia.WIDTH, info.width)
        assertEquals(TestMedia.HEIGHT, info.height)
        assertTrue("alpha", info.hasAlpha)
        assertFalse("not a sequence", info.isSequence)
    }

    @Test
    fun decodesTransparentStillWithCircleInPlace() {
        val bitmap = HeifCodec.decodeStill(TestMedia.bytes("still_alpha.heic"))
        TestMedia.assertTransparentFrame(bitmap, frame = 0)
    }

    @Test
    fun decodesOpaqueStillOnWhite() {
        val bitmap = HeifCodec.decodeStill(TestMedia.bytes("still_opaque.heic"))
        assertFalse("opaque file has no alpha", HeifCodec.probe(TestMedia.bytes("still_opaque.heic")).hasAlpha)
        TestMedia.assertColorNear("centre", TestMedia.circleColor(0), bitmap.getPixel(TestMedia.centreX(0), TestMedia.CENTRE_Y))
        TestMedia.assertColorNear("corner is white", android.graphics.Color.WHITE, bitmap.getPixel(2, 2))
    }

    @Test
    fun rejectsNonHeicBytes() {
        val e = assertThrows(HeifCodec.DecodeException::class.java) {
            HeifCodec.decodeStill("this is not a heic file".toByteArray())
        }
        assertNotNull(e.message)
    }

    @Test
    fun decodesEveryAnimationFrameInColourOrder() {
        val frames = HeifCodec.decodeAll(TestMedia.bytes("anim_alpha.heics"))
        assertEquals(TestMedia.FRAMES, frames.size)
        frames.forEachIndexed { i, frame ->
            TestMedia.assertTransparentFrame(frame.bitmap, i)
            assertEquals("frame $i duration", TestMedia.FRAME_MS, frame.durationMs)
        }
    }

    @Test
    fun decodesInterCodedAnimation() {
        // A low-delay GOP: most frames depend on the one before, so decoding
        // frame 5 correctly proves the decoder keeps reference frames.
        val frames = HeifCodec.decodeAll(TestMedia.bytes("anim_inter.heics"))
        assertEquals(TestMedia.FRAMES, frames.size)
        val frame5 = frames[5].bitmap
        TestMedia.assertColorNear(
            "inter-coded frame 5 centre",
            TestMedia.circleColor(5),
            frame5.getPixel(TestMedia.centreX(5), TestMedia.CENTRE_Y),
        )
    }

    @Test
    fun mediaDecodeReportsAnimationAndSize() {
        val decoded = Media.decode(TestMedia.bytes("anim_alpha.heics"))
        assertTrue("animated", decoded.animated)
        assertEquals(TestMedia.FRAMES, decoded.frames.size)
        assertEquals(TestMedia.WIDTH, decoded.width)
        assertTrue("transparent", decoded.hasAlpha)
        assertNotNull("HEIF info present", decoded.heif)
    }
}
