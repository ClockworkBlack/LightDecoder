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

    // --- secrets(): the Apple Account's and iCloud's errors -----------------------------------------

    private fun s(text: String) = Redact.secrets(text)

    @Test
    fun secretsTakesOutEncodedRunsOfSixteenOrMore() {
        val sha = "b6dfe8ce44395ca48e041657dc990a534fdaa7acfda1c1d8f2e269d8e332c997"
        assertEquals("hash ${Redact.SECRET}", s("hash $sha"))
        assertEquals("id ${Redact.SECRET}.", s("id 0123456789abcdef."))
        assertEquals("pet ${Redact.SECRET}", s("pet AAAAB3NzaC1yc2EAAAADAQABAAAB"))
        assertEquals("b64 ${Redact.SECRET} end", s("b64 dGhpcyBpcyBhIHRva2Vu+/== end"))
        assertEquals("url-safe ${Redact.SECRET}", s("url-safe dGhpc_isA-tokenXyZ"))
        // Fifteen: an error's own number stays; a word of one case stays, however long.
        assertEquals("ref Ab1Cd2Ef3Gh4Ij5", s("ref Ab1Cd2Ef3Gh4Ij5"))
        assertEquals("login failed: MOBILEME_TERMS_OF_SERVICE_UPDATE", s("login failed: MOBILEME_TERMS_OF_SERVICE_UPDATE"))
        assertEquals("internationalization failed", s("internationalization failed"))
    }

    @Test
    fun secretsTakesOutWhatFollowsTheNamesOfSecrets() {
        assertEquals("token=${Redact.SECRET} next", s("token=abc123 next"))
        assertEquals("X-Apple-I-MD=${Redact.SECRET} rest", s("X-Apple-I-MD: AAAAAA== rest"))
        assertEquals("x-apple-i-md-m=${Redact.SECRET}", s("x-apple-i-md-m=qwerty"))
        assertEquals("password=${Redact.SECRET}", s("password=hunter2"))
        assertEquals("dsid=${Redact.SECRET}, adsid=${Redact.SECRET}", s("dsid=12345, adsid=ABCDE"))
        assertEquals("PET=${Redact.SECRET}", s("PET: AbCd"))
        // The names alone, in words, stay.
        assertEquals("tokens expired; password incorrect", s("tokens expired; password incorrect"))
    }

    @Test
    fun secretsTakesOutQuotedValuesAsJsonAndRustWriteThem() {
        // Quoted: the whole value, spaces and escaped quotes in it too.
        assertEquals("password=${Redact.SECRET}", s("password: \"hunter2\""))
        assertEquals("password=${Redact.SECRET} next", s("password: \"correct horse 9!\" next"))
        assertEquals("token=${Redact.SECRET}", s("token=\"abc\""))
        assertEquals("token=${Redact.SECRET}", s("token='a\\'b c'"))
        assertEquals("dsid=${Redact.SECRET}", s("dsid: \"123\""))
        // JSON: the name's closing quote, then the value.
        assertEquals("{\"token=${Redact.SECRET}}", s("{\"token\":\"abc123\"}"))
        assertEquals("{\"pet=${Redact.SECRET}, \"ok\": 1}", s("{\"pet\": \"abcdefgh\", \"ok\": 1}"))
        // Rust's Debug: an option, a list of bytes, a record.
        assertEquals("hashed_password=${Redact.SECRET} }", s("hashed_password: [18, 52, 86, 120] }"))
        assertEquals("hashed_password=${Redact.SECRET}", s("hashed_password=Some(\"deadbeef\")"))
        assertEquals("Account { dsid=${Redact.SECRET}, user: x }", s("Account { dsid: Some(12345), user: x }"))
        assertEquals("token=${Redact.SECRET}", s("token: Token { value: abc }"))
        for (text in listOf("password: \"hunter2\"", "{\"token\":\"abc123\"}", "dsid: \"123\"", "hashed_password: [18, 52]")) {
            val out = s(text)
            for (secret in listOf("hunter2", "abc123", "123", "18", "52")) assertFalse("$text -> $out", out.contains(secret))
        }
    }

    @Test
    fun secretsFindsTheNamesInsideLongerOnes() {
        assertEquals("mme_token=${Redact.SECRET}", s("mme_token=abc"))
        assertEquals("mmeAuthToken=${Redact.SECRET}", s("mmeAuthToken=abc"))
        assertEquals("\"mmeFMIPToken=${Redact.SECRET}", s("\"mmeFMIPToken\": \"x\""))
        assertEquals("X-Apple-Token=${Redact.SECRET}", s("X-Apple-Token: Zm9v"))
        // A name of 16 characters or more in both cases goes too, as anything that looks encoded: the value never stays.
        assertEquals("${Redact.SECRET}${Redact.SECRET}", s("X-Apple-GS-Token: Zm9v"))
        // Words with them inside, and no value after them, stay.
        assertEquals("the competition is over; tokens expire", s("the competition is over; tokens expire"))
    }

    @Test
    fun secretsAlsoTakesOutAddressesAndNumbers() {
        val out = s("Bad auth for jane@icloud.com (+1 555 123 4567): token=abcdef0123456789abcdef X-Apple-I-MD: AAAAABBBBBCCCCC123==")
        assertEquals("Bad auth for <email> (<number>): token=${Redact.SECRET} X-Apple-I-MD=${Redact.SECRET}", out)
        for (secret in listOf("jane", "4567", "abcdef0123456789abcdef", "AAAAABBBBBCCCCC123")) assertFalse(out, out.contains(secret))
        assertEquals("", s(""))
        assertEquals("Your Apple Account or password was incorrect.", s("Your Apple Account or password was incorrect."))
    }

    @Test
    fun secretsTakesOutAnAppleIdWholeHoweverLongItsOwnPart() {
        // A long run in the address's own part is no token: the address goes whole, its domain with it, as text() has it.
        for (id in listOf("john.smith1234567890abcdef@icloud.com", "firstname_lastname1990@smithfamily.net", "j.doe-work2020abcdefg@company.example")) {
            assertEquals(Redact.text(id), s(id))
            assertEquals("no account for <email>.", s("no account for $id."))
            assertEquals("mailto:<email> failed", s("mailto:$id failed"))
            val out = s("login of $id failed: token=abc")
            assertEquals("login of <email> failed: token=${Redact.SECRET}", out)
            for (part in id.split('@', '.')) assertFalse("$part in $out", out.contains(part))
        }
    }
}
