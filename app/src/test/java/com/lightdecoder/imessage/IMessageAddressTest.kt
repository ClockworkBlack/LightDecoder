package com.lightdecoder.imessage

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.imessage.direct.Handles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phone numbers compared whole: two people whose numbers only end the same are two people; a
 * number written without its country code is read in this phone's country.
 */
@RunWith(AndroidJUnit4::class)
class IMessageAddressTest {

    @After
    fun tearDown() = IMessageAddress.resetForTesting()

    @Test
    fun aUsNumberIsTheSameHoweverItIsWritten() {
        IMessageAddress.setRegionForTesting("US")
        val forms = listOf("(555) 123-4567", "555-123-4567", "1 555 123 4567", "+1 555-123-4567", "tel:+15551234567", "+15551234567", "011 1 555 123 4567")
        for (f in forms) assertEquals(f, "+15551234567", IMessageAddress.key(f))
        assertTrue(IMessageAddress.same("(555) 123-4567", "tel:+15551234567"))
        assertEquals("cara@example.com", IMessageAddress.key("mailto:Cara@Example.com"))
        assertNull(IMessageAddress.key("12345"))
        assertNull(IMessageAddress.key("Alice"))
        // The locale's country until the SIM's is known: the US in the tests' locale.
        IMessageAddress.resetForTesting()
        assertEquals("US", IMessageAddress.region())
        assertEquals("+15551234567", IMessageAddress.key("(555) 123-4567"))
    }

    @Test
    fun numbersThatOnlyEndTheSameAreDifferentPeople() {
        IMessageAddress.setRegionForTesting("US")
        // São Paulo and Rio: the same last ten digits.
        assertNotEquals(IMessageAddress.key("+55 11 98765-4321"), IMessageAddress.key("+55 21 98765-4321"))
        assertFalse(IMessageAddress.same("+55 11 98765-4321", "+55 21 98765-4321"))
        assertTrue(IMessageAddress.same("+55 (11) 98765-4321", "tel:+5511987654321"))
        // New York and India: the same last ten digits too.
        assertNotEquals(IMessageAddress.key("+1 917 555 1234"), IMessageAddress.key("+91 91755 51234"))
        assertFalse(IMessageAddress.same("+1 917 555 1234", "+91 91755 51234"))
        // Written without a country code, here (the US) it is the US number.
        assertTrue(IMessageAddress.same("(917) 555-1234", "+1 917 555 1234"))
        assertFalse(IMessageAddress.same("(917) 555-1234", "+91 91755 51234"))
        // Two numbers that say their country are never compared by their national digits.
        assertFalse(IMessageAddress.sameKey("+19175551234", "+919175551234"))
    }

    @Test
    fun aNumberWithoutItsCountryCodeIsReadInThisPhonesCountry() {
        IMessageAddress.setRegionForTesting("BR")
        // Brazil's trunk "0" before the area code, or none.
        assertEquals("+5511987654321", IMessageAddress.key("(11) 98765-4321"))
        assertEquals("+5511987654321", IMessageAddress.key("011 98765 4321"))
        assertTrue(IMessageAddress.same("(11) 98765-4321", "+55 11 98765-4321"))
        assertFalse(IMessageAddress.same("(11) 98765-4321", "+55 21 98765-4321"))
        // "00" dialled in place of "+".
        assertEquals("+447700900123", IMessageAddress.key("00 44 7700 900123"))
        assertEquals("tel:+5511987654321", Handles.normalize("(11) 98765-4321"))
        // Russia's trunk is "8".
        IMessageAddress.setRegionForTesting("RU")
        assertEquals("+79123456789", IMessageAddress.key("8 912 345-67-89"))
        assertEquals("tel:+79123456789", Handles.normalize("8 912 345-67-89"))
        // The US as before.
        IMessageAddress.setRegionForTesting("US")
        assertEquals("tel:+15551234567", Handles.normalize("(555) 123-4567"))
        assertEquals("tel:+447700900123", Handles.normalize("+44 7700 900123"))
    }

    @Test
    fun withNoCountryKnownANationalNumberFallsBackToItsDigits() {
        IMessageAddress.setRegionForTesting(null)
        assertNull(IMessageAddress.region())
        assertEquals("#5551234567", IMessageAddress.key("(555) 123-4567"))
        // Only then, and only against a number that says its country: its national digits after a calling code.
        assertTrue(IMessageAddress.sameKey("#5551234567", "+15551234567"))
        assertTrue(IMessageAddress.same("07700 900123", "+44 7700 900123"))
        assertFalse(IMessageAddress.same("(555) 123-4567", "+1 555 765 4321"))
        // Not a calling code before it: no.
        assertFalse(IMessageAddress.sameKey("#5551234567", "+9995551234567"))
        // As before, a number typed without its country goes to North America.
        assertEquals("tel:+15551234567", Handles.normalize("(555) 123-4567"))
    }

    @Test
    fun aMessagesConversationIsNotPairedWithSomeoneWhoseNumberOnlyEndsTheSame() {
        IMessageAddress.setRegionForTesting("US")
        val pairs = IMessageLinks.pair(
            listOf(IMessageLinks.Chat("c-ana", IMessageAddress.key("+55 11 98765-4321"), "Ana")),
            listOf(IMessageLinks.Chat("im-bia", IMessageAddress.key("+55 21 98765-4321"), "Bia"), IMessageLinks.Chat("im-ana", IMessageAddress.key("+5511987654321"), "Ana S")),
            emptyMap(),
        )
        assertNull(pairs["im-bia"])
        assertEquals(IMessageLinks.Pair("c-ana", IMessageLinks.How.NUMBER), pairs["im-ana"])
    }
}
