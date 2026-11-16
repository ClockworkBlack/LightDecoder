package com.lightdecoder.relay

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Every pasted cookie format the setup screen accepts. The values here are made up. */
@RunWith(AndroidJUnit4::class)
class CookieParserTest {

    private val full = mapOf(
        "SID" to "sid.v=1",
        "HSID" to "hsid",
        "SSID" to "ssid",
        "APISID" to "api/sid",
        "SAPISID" to "sapi/sid",
        "OSID" to "osid",
        "__Secure-1PSIDTS" to "sidts-1",
    )

    @Test
    fun jsonObject() {
        val parsed = CookieParser.parse(
            """{"SID":"sid.v=1","HSID":"hsid","SSID":"ssid","APISID":"api/sid","SAPISID":"sapi/sid",
               "OSID":"osid","__Secure-1PSIDTS":"sidts-1","NID":"dropped","_ga":"dropped"}"""
        )
        assertEquals(full, parsed)
        assertTrue(CookieParser.missingRequired(parsed).isEmpty())
    }

    @Test
    fun jsonArrayPrefersTheMessagesDomain() {
        val parsed = CookieParser.parse(
            """[
              {"domain":".google.com","name":"SID","value":"sid.v=1","path":"/","secure":false},
              {"domain":".google.com","name":"HSID","value":"hsid"},
              {"domain":".google.com","name":"SSID","value":"ssid"},
              {"domain":".google.com","name":"APISID","value":"api/sid"},
              {"domain":".google.com","name":"SAPISID","value":"sapi/sid"},
              {"domain":"accounts.google.com","name":"OSID","value":"wrong-site"},
              {"domain":"messages.google.com","name":"OSID","value":"osid"},
              {"domain":"myaccount.google.com","name":"OSID","value":"also-wrong"},
              {"domain":".youtube.com","name":"SID","value":"not-google"},
              {"domain":null,"name":"__Secure-1PSIDTS","value":"sidts-1"},
              {"domain":".google.com","name":"NID","value":"dropped"},
              {"name":"HSID","value":null}
            ]"""
        )
        assertEquals(full, parsed)
    }

    @Test
    fun wrappedCookieList() {
        val parsed = CookieParser.parse("""{"url":"https://messages.google.com","cookies":[{"name":"SID","value":"a"}]}""")
        assertEquals(mapOf("SID" to "a"), parsed)
    }

    @Test
    fun cookieHeader() {
        val header = "SID=sid.v=1; HSID=hsid; SSID=ssid; APISID=api/sid; SAPISID=sapi/sid; OSID=osid; " +
            "__Secure-1PSIDTS=sidts-1; NID=dropped; OSID=second-copy"
        assertEquals(full, CookieParser.parse(header))
        assertEquals(full, CookieParser.parse("Cookie: $header"))
        assertEquals(full, CookieParser.parse("cookie:" + header.replace("; ", ";\n")))
    }

    @Test
    fun curlWithCookieHeader() {
        val curl = """
            curl 'https://messages.google.com/web/config' \
              -H 'accept: */*' \
              -H 'cookie: SID=sid.v=1; HSID=hsid; SSID=ssid; APISID=api/sid; SAPISID=sapi/sid; OSID=osid; __Secure-1PSIDTS=sidts-1' \
              -H 'user-agent: Mozilla/5.0'
        """.trimIndent()
        assertEquals(full, CookieParser.parse(curl))
    }

    @Test
    fun curlWithCookieOption() {
        val curl = "curl \"https://messages.google.com/web/config\" -b \$'SID=sid.v=1; HSID=hsid; SSID=ssid' " +
            "--cookie \"APISID=api/sid; SAPISID=sapi/sid\" --header='Cookie: OSID=osid; __Secure-1PSIDTS=sidts-1' " +
            "-b cookies.txt -H \"Referer: https://messages.google.com/\""
        assertEquals(full, CookieParser.parse(curl))
    }

    @Test
    fun reportsMissingRequiredCookies() {
        val parsed = CookieParser.parse("SID=a; OSID=b")
        assertEquals(listOf("HSID", "SSID", "APISID", "SAPISID"), CookieParser.missingRequired(parsed))
        assertEquals(CookieParser.REQUIRED, CookieParser.missingRequired(emptyMap()))
        assertEquals(listOf("SID"), CookieParser.missingRequired(full - "SID" + ("SID" to "")))
    }

    @Test
    fun garbageGivesNothing() {
        for (input in listOf("", "   ", "{not json", "[1, 2", "hello world", "curl https://example.com", "=x; ;")) {
            assertTrue(input, CookieParser.parse(input).isEmpty())
        }
    }

    @Test
    fun shellWordsHandlesQuotingAndContinuations() {
        assertEquals(
            listOf("curl", "a b", "c\"d", "e\nf", "g h", "ij"),
            CookieParser.shellWords("curl 'a b' \"c\\\"d\" \$'e\\nf' g\\ h \\\n i'j'"),
        )
    }
}
