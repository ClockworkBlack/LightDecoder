package com.lightdecoder.imessage.direct

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The location card iMessage sends ("Send My Current Location"), written and read strictly. */
class AppleLocationTest {

    private fun card(url: String) = "BEGIN:VCARD\nVERSION:3.0\nN:;Current Location;;;\nFN:Current Location\n$url\nEND:VCARD\n"

    @Test
    fun aCardGoesAndComesBack() {
        val vcf = AppleLocation.toVcf(37.331820, -122.031180)
        assertTrue(vcf.startsWith("BEGIN:VCARD\nVERSION:3.0\nPRODID:-//Apple Inc.//macOS 13.0//EN\nN:;Current Location;;;\nFN:Current Location\n"))
        assertTrue(vcf, vcf.contains("URL;type=pref:https://maps.apple.com/?ll=37.331820\\,-122.031180&q=37.331820\\,-122.031180\n"))
        assertTrue(vcf.endsWith("END:VCARD\n"))
        assertEquals(37.33182 to -122.03118, AppleLocation.coordinatesFrom(vcf))
        assertEquals(37.33182 to -122.03118, AppleLocation.coordinatesFrom(vcf.toByteArray()))
    }

    @Test
    fun theNumbersAreFixedPointWithADotInEveryLanguage() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val vcf = AppleLocation.toVcf(0.0001, 52.5)
            assertTrue(vcf, vcf.contains("ll=0.000100\\,52.500000"))
            assertFalse(vcf.contains("E-"))
            assertEquals("geo:0.000100,52.500000?q=0.000100,52.500000", AppleLocation.geoUri(0.0001, 52.5))
            assertEquals(0.0001 to 52.5, AppleLocation.coordinatesFrom(vcf))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun theCommaComesInEachOfItsForms() {
        assertEquals(1.5 to 2.25, AppleLocation.coordinatesFrom(card("URL;type=pref:https://maps.apple.com/?ll=1.5\\,2.25&q=x")))
        assertEquals(1.5 to 2.25, AppleLocation.coordinatesFrom(card("URL:https://maps.apple.com/?ll=1.5%2C2.25")))
        assertEquals(1.5 to 2.25, AppleLocation.coordinatesFrom(card("URL:https://maps.apple.com/?q=a&ll=1.5%2c2.25")))
        assertEquals(-1.0 to -2.0, AppleLocation.coordinatesFrom(card("URL:https://maps.apple.com/?ll=-1,-2")))
    }

    @Test
    fun aCardWithoutCoordinatesInRangeIsNone() {
        assertNull(AppleLocation.coordinatesFrom("BEGIN:VCARD\nFN:Current Location\nEND:VCARD\n"))
        assertNull(AppleLocation.coordinatesFrom(card("URL:https://maps.apple.com/?q=1,2")))
        assertNull(AppleLocation.coordinatesFrom(card("URL:https://maps.apple.com/?ll=91,2")))
        assertNull(AppleLocation.coordinatesFrom(card("URL:https://maps.apple.com/?ll=-90.5,2")))
        assertNull(AppleLocation.coordinatesFrom(card("URL:https://maps.apple.com/?ll=1,180.01")))
        assertNull(AppleLocation.coordinatesFrom(card("URL:https://maps.apple.com/?ll=1,2,3")))
    }

    @Test
    fun onlyPlainDecimalsAreNumbers() {
        for (bad in listOf("NaN", "Infinity", "-Infinity", "0x1p3", "1e1", "1E1", "+1", "1.", ".5", "1d", "1f", " 1", "１")) {
            assertNull(bad, AppleLocation.coordinatesFrom(card("URL:https://maps.apple.com/?ll=$bad,2")))
            assertNull(bad, AppleLocation.coordinatesFrom(card("URL:https://maps.apple.com/?ll=2,$bad")))
        }
    }

    @Test
    fun aCardOverSixteenKilobytesIsNotRead() {
        val url = "URL:https://maps.apple.com/?ll=1,2"
        val padded = card(url) + "NOTE:" + "x".repeat(AppleLocation.MAX_BYTES)
        assertNull(AppleLocation.coordinatesFrom(padded))
        assertNull(AppleLocation.coordinatesFrom(padded.toByteArray()))
        assertEquals(1.0 to 2.0, AppleLocation.coordinatesFrom(card(url)))
    }

    @Test
    fun onlyTheFirstUrlLineCounts() {
        val two = "BEGIN:VCARD\nURL:https://maps.apple.com/?ll=1,2\nURL:https://maps.apple.com/?ll=3,4\nEND:VCARD\n"
        assertEquals(1.0 to 2.0, AppleLocation.coordinatesFrom(two))
        val firstEmpty = "BEGIN:VCARD\nURL:https://example.com/\nURL:https://maps.apple.com/?ll=3,4\nEND:VCARD\n"
        assertNull(AppleLocation.coordinatesFrom(firstEmpty))
        // A line that only starts like one is none.
        assertNull(AppleLocation.coordinatesFrom("BEGIN:VCARD\nURLX:https://maps.apple.com/?ll=3,4\nEND:VCARD\n"))
    }

    @Test
    fun aGroupedUrlLineAsAnIphoneWritesItIsRead() {
        val grouped = card("item1.URL;type=pref:http://maps.apple.com/?ll=37.331820\\,-122.031180&q=37.331820\\,-122.031180\nitem1.X-ABLabel:map url")
        assertEquals(37.33182 to -122.03118, AppleLocation.coordinatesFrom(grouped))
        assertEquals(1.0 to 2.0, AppleLocation.coordinatesFrom(card("ITEM2.url:https://maps.apple.com/?ll=1,2")))
        // Only a short plain group name, then exactly URL.
        assertNull(AppleLocation.coordinatesFrom(card("a.b.URL:https://maps.apple.com/?ll=1,2")))
        assertNull(AppleLocation.coordinatesFrom(card("item1.URLX:https://maps.apple.com/?ll=1,2")))
        assertNull(AppleLocation.coordinatesFrom(card("averyveryverylonggroup.URL:https://maps.apple.com/?ll=1,2")))
    }

    @Test
    fun anLlHiddenInAnotherParameterIsNotTaken() {
        assertNull(AppleLocation.coordinatesFrom(card("URL:https://maps.apple.com/?xll=1,2")))
        assertNull(AppleLocation.coordinatesFrom(card("URL:https://evil.example/?next=https://maps.apple.com/?ll=1,2")))
        assertNull(AppleLocation.coordinatesFrom(card("URL:https://maps.apple.com/?q=a%26ll=1,2")))
        assertNull(AppleLocation.coordinatesFrom(card("URL:https://maps.apple.com/#?ll=1,2")))
    }

    @Test
    fun aCardIsKnownByItsTypeOrName() {
        assertTrue(AppleLocation.isLocation("text/x-vlocation", "x"))
        assertTrue(AppleLocation.isLocation("", "ABC-CL.loc.vcf"))
        assertFalse(AppleLocation.isLocation("text/vcard", "Jane.vcf"))
        assertEquals("G1-CL.loc.vcf", AppleLocation.fileName("G1"))
    }
}
