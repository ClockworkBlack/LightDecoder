package com.lightdecoder.imessage.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Apple's attachment types (UTIs) and Android's (MIME types), both ways. */
class DirectMediaTest {

    @Test
    fun anIncomingTypeIsApplesOwnThenItsUtisThenItsNamesElseAnyData() {
        assertEquals("image/jpeg", DirectMedia.mimeFor("image/jpeg", "public.png", "a.png"))
        assertEquals("image/jpeg", DirectMedia.mimeFor("IMAGE/JPEG; q=1", "", ""))
        assertEquals("audio/x-caf", DirectMedia.mimeFor("", "com.apple.coreaudio-format", ""))
        assertEquals("image/jpeg", DirectMedia.mimeFor("image/*", "public.jpeg", ""))
        assertEquals("image/heic", DirectMedia.mimeFor("application/octet-stream", "public.heic", ""))
        assertEquals("video/quicktime", DirectMedia.mimeFor("", "com.apple.quicktime-movie", ""))
        assertEquals("video/mp4", DirectMedia.mimeFor("", "dyn.ah62d4rv4ge8", "Clip.MP4"))
        assertEquals("text/x-vlocation", DirectMedia.mimeFor("", "", "Current Location.loc.vcf"))
        assertEquals("text/vcard", DirectMedia.mimeFor("", "", "Bob.vcf"))
        assertEquals(DirectMedia.OCTET_STREAM, DirectMedia.mimeFor("", "", "README"))
    }

    @Test
    fun aTypeIsSpecificUnlessItIsAWildcardOrAnyData() {
        assertTrue(DirectMedia.isSpecific("image/png"))
        assertFalse(DirectMedia.isSpecific("image/*"))
        assertFalse(DirectMedia.isSpecific("*/*"))
        assertFalse(DirectMedia.isSpecific(DirectMedia.OCTET_STREAM))
        assertFalse(DirectMedia.isSpecific("image"))
        assertFalse(DirectMedia.isSpecific(""))
    }

    @Test
    fun aSentFileGoesWithOpenBubblesUti() {
        assertEquals("public.jpeg", DirectMedia.utiFor("image/jpeg"))
        assertEquals("public.jpeg", DirectMedia.utiFor("image/jpg"))
        assertEquals("public.png", DirectMedia.utiFor("image/png"))
        assertEquals("com.compuserve.gif", DirectMedia.utiFor("image/gif"))
        assertEquals("public.heic", DirectMedia.utiFor("image/heic"))
        assertEquals("org.webmproject.webp", DirectMedia.utiFor("image/webp"))
        assertEquals("public.mpeg-4", DirectMedia.utiFor("video/mp4"))
        assertEquals("com.apple.quicktime-movie", DirectMedia.utiFor("video/quicktime"))
        // A recorded voice message (m4a).
        assertEquals("public.mpeg-4-audio", DirectMedia.utiFor("audio/mp4"))
        assertEquals("public.mp3", DirectMedia.utiFor("audio/mpeg"))
        assertEquals("public.vcard", DirectMedia.utiFor("text/x-vcard"))
        assertEquals("com.adobe.pdf", DirectMedia.utiFor("application/pdf; charset=x"))
        // Anything else, Apple's own CAF included, as in OpenBubbles.
        assertEquals(DirectMedia.UTI_DATA, DirectMedia.utiFor("audio/x-caf"))
        assertEquals(DirectMedia.UTI_DATA, DirectMedia.utiFor("application/zip"))
    }

    @Test
    fun aFileIsKeptUnderItsNamesExtensionElseItsTypes() {
        assertEquals("heic", DirectMedia.extensionFor("image/jpeg", "IMG_1.HEIC"))
        assertEquals("caf", DirectMedia.extensionFor("audio/x-caf", "Audio Message.caf"))
        assertEquals("mov", DirectMedia.extensionFor("video/quicktime", "clip"))
        assertEquals("jpg", DirectMedia.extensionFor("image/jpeg", "photo.j p g"))
        assertEquals("m4a", DirectMedia.extensionFor("audio/mp4", ""))
        assertEquals("bin", DirectMedia.extensionFor("application/x-thing", "data.toolongextension"))
        assertEquals("attachment.png", DirectMedia.nameFor("image/png"))
        assertNull(DirectMedia.mimeOfName("noextension"))
    }

    @Test
    fun aFileFetchedWithoutAskingComesOnlyFromApplesFileServers() {
        for (url in listOf(
            "https://p42-content.icloud.com/M1A2B3", "https://p42-content.icloud.com:443/M1A2B3", "https://P42-CONTENT.ICLOUD.COM/x",
            "https://cvws.icloud-content.com/B/abc?o=1", "https://mmcs.apple.com", "https://p1-content.icloud.com/a/b?c=d&e=f",
        )) {
            assertTrue(url, DirectMedia.isAppleFileServer(url))
        }
        for (url in listOf(
            // Not https, not Apple's, only like it, or written to be read as another host.
            "http://p42-content.icloud.com/x", "https://example.com/x", "https://icloud.com/x", "https://evilicloud.com/x",
            "https://p42-content.icloud.com.evil.net/x", "https://evil.net/p42-content.icloud.com", "https://p42-content.icloud.com@evil.net/x",
            "https://evil.net\\@p42-content.icloud.com/", "https://p42-content.icloud.com:8443/x", "https://127.0.0.1/x", "https://[::1]/x",
            "https://p42-content.icloud.com/a b", "https://p42-content.icloud.com/a\\b", "", "u", "https://x", "file:///data/x",
            "https://" + "a".repeat(3_000) + ".icloud.com/",
        )) {
            assertFalse(url, DirectMedia.isAppleFileServer(url))
        }
    }
}
