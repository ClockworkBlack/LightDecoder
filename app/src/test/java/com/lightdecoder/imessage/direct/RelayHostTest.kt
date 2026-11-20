package com.lightdecoder.imessage.direct

import com.lightdecoder.imessage.direct.RelayHost.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The registration relay's address: only https:// of a real host, with nothing secret in it. */
class RelayHostTest {

    private fun ok(text: String) = RelayHost.check(text).also { assertNull("$text: ${it.problem}", it.problem) }.value

    private fun problem(text: String): Problem? = RelayHost.check(text).also { assertNull(text, it.value) }.problem

    @Test
    fun theDefaultAndOtherHttpsServersAreTaken() {
        assertEquals(DirectEngine.DEFAULT_RELAY_HOST, ok(DirectEngine.DEFAULT_RELAY_HOST))
        // The engine picks the public token by comparing with the default: a trailing "/" or spaces keep it the default.
        assertEquals(DirectEngine.DEFAULT_RELAY_HOST, ok(" ${DirectEngine.DEFAULT_RELAY_HOST}/ "))
        assertEquals("https://relay.example.com:8443", ok("https://relay.example.com:8443"))
        assertEquals("https://relay.example.com/prefix", ok("https://relay.example.com/prefix/"))
        assertEquals("https://Relay.Example.com", ok("HTTPS://Relay.Example.com"))
        // A line break pasted with it is not part of it.
        assertEquals("https://relay.example.com", ok("https://relay.example.com\n"))
        assertEquals("https://192.168.1.20:9000", ok("https://192.168.1.20:9000"))
        assertEquals("https://[::1]:9000", ok("https://[::1]:9000"))
    }

    @Test
    fun onlyHttpsIsTaken() {
        assertEquals(Problem.NOT_HTTPS, problem("http://registration-relay.beeper.com"))
        assertEquals(Problem.NOT_HTTPS, problem("registration-relay.beeper.com"))
        assertEquals(Problem.NOT_HTTPS, problem("ftp://relay.example.com"))
        assertEquals(Problem.NOT_HTTPS, problem("file:///sdcard/relay"))
        assertEquals(Problem.NOT_HTTPS, problem("javascript:alert(1)"))
        assertEquals(Problem.NOT_HTTPS, problem("ws://relay.example.com"))
        assertEquals(Problem.NOT_HTTPS, problem("httpsx://relay.example.com"))
    }

    @Test
    fun noCredentialsInTheAddress() {
        assertEquals(Problem.CREDENTIALS, problem("https://user:secret@relay.example.com"))
        assertEquals(Problem.CREDENTIALS, problem("https://user@relay.example.com"))
        // What a browser would take for a host but is a user name: still refused.
        assertEquals(Problem.CREDENTIALS, problem("https://registration-relay.beeper.com@evil.example"))
        assertEquals(Problem.CREDENTIALS, problem("https://a%40b@relay.example.com/x"))
    }

    @Test
    fun aValidHostIsNeeded() {
        assertEquals(Problem.BAD_HOST, problem("https://"))
        assertEquals(Problem.BAD_HOST, problem("https:///path"))
        assertEquals(Problem.BAD_HOST, problem("https://bad_host.example.com"))
        assertEquals(Problem.BAD_HOST, problem("https://-relay.example.com"))
        assertEquals(Problem.BAD_HOST, problem("https://relay..example.com"))
        assertEquals(Problem.BAD_HOST, problem("https://12345"))
        assertEquals(Problem.BAD_HOST, problem("https://999.1.1.1"))
    }

    @Test
    fun anythingElseThatIsNoPlainAddressIsRefused() {
        assertEquals(Problem.MALFORMED, problem(""))
        assertEquals(Problem.MALFORMED, problem("   "))
        assertEquals(Problem.MALFORMED, problem("https://relay.example.com/a b"))
        assertEquals(Problem.MALFORMED, problem("https://relay.example.com?token=1"))
        assertEquals(Problem.MALFORMED, problem("https://relay.example.com/#x"))
        assertEquals(Problem.MALFORMED, problem("https://relay.example.com:0"))
        assertEquals(Problem.MALFORMED, problem("https://relay.example.com:99999"))
        assertEquals(Problem.MALFORMED, problem("https://relay.example.com:x"))
        assertEquals(Problem.MALFORMED, problem("https://relay.exa\nmple.com"))
        assertEquals(Problem.MALFORMED, problem("https://relay.example.com\u0000"))
        assertEquals(Problem.MALFORMED, problem("https://[::1]x"))
    }
}
