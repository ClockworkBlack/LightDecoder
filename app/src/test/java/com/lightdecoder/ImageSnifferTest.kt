package com.lightdecoder

import com.lightdecoder.heif.ImageSniffer
import com.lightdecoder.heif.StickerEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The format sniffing and effect detection are pure byte work, no decoder needed. */
class ImageSnifferTest {

    @Test
    fun recognisesHeicStickers() {
        for (name in listOf("still_alpha.heic", "still_outline.heic", "anim_alpha.heics")) {
            assertTrue("$name should be HEIF", ImageSniffer.isHeif(TestMedia.bytes(name)))
        }
    }

    @Test
    fun heicExtensionAndMime() {
        val bytes = TestMedia.bytes("still_alpha.heic")
        assertEquals("heic", ImageSniffer.extensionFor(bytes, "image/heic"))
        assertEquals("image/heic", ImageSniffer.mimeFor("heic"))
        assertEquals("image/heic-sequence", ImageSniffer.mimeFor("heics"))
    }

    @Test
    fun animatedHeicSnifsAsSequence() {
        val brands = ImageSniffer.ftypBrands(TestMedia.bytes("anim_alpha.heics"))
        assertTrue("msf1 brand present: $brands", brands.contains("msf1"))
    }

    @Test
    fun pngIsNotHeif() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 0, 0, 0, 0)
        assertFalse(ImageSniffer.isHeif(png))
        assertEquals("png", ImageSniffer.extensionFor(png, null))
    }

    @Test
    fun avifIsNotHandledByBuiltInDecoder() {
        // AVIF shares the container but uses AV1, which the built-in decoder
        // does not include, so it must not be claimed as HEIF.
        val avif = "0000001c".hexToBytes() + "ftypavif".toByteArray() +
            "0000000061766966".hexToBytes()
        assertFalse(ImageSniffer.isHeif(avif))
    }

    @Test
    fun detectsOutlineEffectFromXmp() {
        assertEquals(StickerEffect.OUTLINE, StickerEffect.detect(TestMedia.bytes("still_outline.heic")))
    }

    @Test
    fun noEffectWhenAbsent() {
        assertEquals(StickerEffect.NONE, StickerEffect.detect(TestMedia.bytes("still_alpha.heic")))
    }

    @Test
    fun mapsKnownEffectNames() {
        assertEquals(StickerEffect.OUTLINE, effectOf("stroke"))
        assertEquals(StickerEffect.COMIC, effectOf("comic"))
        assertEquals(StickerEffect.PUFFY, effectOf("puffy"))
        assertEquals(StickerEffect.SHINY, effectOf("iridescent"))
        assertEquals(StickerEffect.OTHER, effectOf("sparkle"))
    }

    private fun effectOf(name: String): StickerEffect =
        StickerEffect.detect("...stickerEffect:type=\"$name\"/>...".toByteArray())

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
