package com.lightdecoder.imessage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Numbers and email addresses out of what is logged and reported; the rest as it is. */
class RedactTest {

    private fun r(text: String) = Redact.text(text)

    @Test
    fun internationalNumbersGo() {
        assertEquals("Registration failed for <number>", r("Registration failed for +15551234567"))
        assertEquals("<number> is not on iMessage", r("+44 20 7946 0958 is not on iMessage"))
        assertEquals("number <number>.", r("number +1 (555) 123-4567."))
        assertEquals("[<number>]", r("[+33612345678]"))
        assertEquals("(<number>)", r("(+49 30 1234567)"))
    }

    @Test
    fun nationalNumbersGo() {
        assertEquals("call <number> now", r("call (555) 123-4567 now"))
        assertEquals("<number>", r("555-123-4567"))
        assertEquals("<number>", r("555.123.4567"))
        assertEquals("<number>", r("06 12 34 56 78"))
        assertEquals("<number>", r("07700 900123"))
        assertEquals("<number>", r("11 98765-4321"))
        assertEquals("id <number>", r("id 5551234567"))
    }

    @Test
    fun telAndMailtoAddressesGo() {
        assertEquals("handle tel:<number> rejected", r("handle tel:+15551234567 rejected"))
        assertEquals("handle mailto:<email>", r("handle mailto:someone@icloud.com"))
        assertEquals("TEL:<number>", r("TEL:5551234567"))
        assertEquals("sms:<number>", r("sms:+15551234567?body=hi"))
        assertEquals("facetime:<email>", r("facetime:a.b@example.org"))
        assertEquals("\"tel:<number>\"", r("\"tel:+15551234567\""))
    }

    @Test
    fun emailAddressesAndAppleIdsGo() {
        assertEquals("Apple ID <email> needs a password", r("Apple ID jane.doe+test@icloud.com needs a password"))
        assertEquals("<email>, <email>", r("a@b.co, x_y@sub.example.com"))
        assertEquals("<email>", r("0123456789abcdef0123456789abcdef@privaterelay.appleid.com"))
    }

    @Test
    fun digitRunsOfSevenOrMoreGo() {
        assertEquals("user<number>", r("user15551234567"))
        assertEquals("code <number>x", r("code 1234567x"))
        // Six digits or fewer: counts, sizes, codes.
        assertEquals("error 503 after 123456 ms; 12 MB; code -1009", r("error 503 after 123456 ms; 12 MB; code -1009"))
    }

    @Test
    fun whatSaysWhatWentWrongStays() {
        val plain = "not connected, Apple's library tries again in 3600 s: Push connection failed (6001)"
        assertEquals(plain, r(plain))
        // Dates, times and the chat tags of the log.
        assertEquals("last status: connected to Apple (2026-11-20 10:30)", r("last status: connected to Apple (2026-11-20 10:30)"))
        assertEquals("at 10:30:05 on 2026-11-20T10:30:05", r("at 10:30:05 on 2026-11-20T10:30:05"))
        assertEquals("chat#123456: library send FAILED", r("chat#123456: library send FAILED"))
        // Hashes are no numbers.
        val sha = "b6dfe8ce44395ca48e041657dc990a534fdaa7acfda1c1d8f2e269d8e332c997"
        assertEquals("SHA-256 $sha", r("SHA-256 $sha"))
        assertEquals("OpenBubbles com.openbubbles.messaging 2.0.0; notification access yes", r("OpenBubbles com.openbubbles.messaging 2.0.0; notification access yes"))
        assertEquals("", r(""))
    }

    @Test
    fun aLineWithEverythingKeepsOnlyWhatIsSafe() {
        val line = "registerIds failed for tel:+15551234567 and jane@icloud.com (also 555-987-6543) at 2026-11-20 10:30"
        val out = r(line)
        assertFalse(out, out.contains("5551234567") || out.contains("jane") || out.contains("987"))
        assertTrue(out, out.endsWith("at 2026-11-20 10:30"))
        assertEquals("registerIds failed for tel:<number> and <email> (also <number>) at 2026-11-20 10:30", out)
    }
}
