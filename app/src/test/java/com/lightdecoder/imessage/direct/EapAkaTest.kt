package com.lightdecoder.imessage.direct

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The SIM handshake's arithmetic and packet formats (RFC 4187; key generator vectors from RFC 4186 appendix A). */
class EapAkaTest {

    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private val rand = hex("101112131415161718191a1b1c1d1e1f")
    private val autn = hex("202122232425262728292a2b2c2d2e2f")
    private val res = hex("a1a2a3a4a5a6a7a8")
    private val ck = hex("c0c1c2c3c4c5c6c7c8c9cacbcccdcecf")
    private val ik = hex("e0e1e2e3e4e5e6e7e8e9eaebecedeeef")
    private val identity = "0310260123456789@nai.epc.mnc260.mcc310.3gppnetwork.org"

    private fun challenge(id: Int = 0x37): String {
        val p = ByteArray(48)
        p[0] = 1; p[1] = id.toByte(); p[3] = 48; p[4] = 23; p[5] = 1
        p[8] = 1; p[9] = 5; System.arraycopy(rand, 0, p, 12, 16)
        p[28] = 2; p[29] = 5; System.arraycopy(autn, 0, p, 32, 16)
        return Base64.getEncoder().encodeToString(p)
    }

    private fun simOk() = Base64.getEncoder().encodeToString(
        byteArrayOf(0xDB.toByte(), 8) + res + byteArrayOf(16) + ck + byteArrayOf(16) + ik,
    )

    @Test
    fun theCompressionFunctionIsSha1s() {
        // SHA-1("abc") is one compression of its padded block.
        val block = ByteArray(64).also { "abc".toByteArray().copyInto(it); it[3] = 0x80.toByte(); it[63] = 24 }
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", EapAka.sha1Compress(block).hex())
    }

    @Test
    fun theKeyGeneratorMatchesRfc4186() {
        val out = EapAka.prf(hex("e576d5ca 332e9930 018bf1ba ee2763c7 95b3c712"), 160)
        assertEquals("536e5ebc4465582aa6a8ec9986ebb620", out.copyOfRange(0, 16).hex())
        assertEquals("25af1942efcbf4bc72b3943421f2a974", out.copyOfRange(16, 32).hex())
        assertEquals(
            "39d45aeaf4e30601983e972b6cfd46d1c363773365690d09cd44976b525f47d3" +
                "a60a985e955c53b090b2e4b73719196a402542968fd14a888f46b9a7886e4488",
            out.copyOfRange(32, 96).hex(),
        )
        assertEquals(
            "5949eab0fff69d52315c6c634fd14a7f0d52023d56f79698fa6596abeed4f93f" +
                "bb48eb534d985414ceed0d9a8ed33c387c9dfdab92ffbdf240fcecf65a2c93b9",
            out.copyOfRange(96, 160).hex(),
        )
    }

    @Test
    fun theSimsIdentityIsTheThreeGppOne() {
        assertEquals(identity, EapAka.identity("310260", "310260123456789"))
        // A two-digit network code is written with three digits.
        assertEquals("0234151234567890@nai.epc.mnc015.mcc234.3gppnetwork.org", EapAka.identity("23415", "234151234567890"))
        assertNull(EapAka.identity("", "1"))
        assertNull(EapAka.identity("310260", null))
    }

    @Test
    fun theChallengeIsReadAndTheSimIsAskedWithIt() {
        val ch = EapAka.parseChallenge(challenge())
        assertEquals(0x37.toByte(), ch.identifier)
        assertArrayEquals(rand, ch.rand)
        assertArrayEquals(autn, ch.autn)
        assertArrayEquals(byteArrayOf(16) + rand + byteArrayOf(16) + autn, Base64.getDecoder().decode(ch.simRequest()))
    }

    @Test
    fun whatIsNotAChallengeIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { EapAka.parseChallenge("not base64 !!") }
        assertThrows(IllegalArgumentException::class.java) { EapAka.parseChallenge(Base64.getEncoder().encodeToString(ByteArray(48))) }
        // A challenge without AUTN.
        val p = Base64.getDecoder().decode(challenge()).copyOf(28).also { it[3] = 28 }
        assertThrows(IllegalArgumentException::class.java) { EapAka.parseChallenge(Base64.getEncoder().encodeToString(p)) }
    }

    @Test
    fun theResponseCarriesTheSimsResAndAValidSignature() {
        var asked: String? = null
        val response = Base64.getDecoder().decode(EapAka.respond(challenge(), identity) { asked = it; simOk() })
        assertEquals(EapAka.parseChallenge(challenge()).simRequest(), asked)
        // Response, same identifier, 40 bytes, EAP-AKA challenge; AT_RES of 64 bits; AT_MAC last.
        assertEquals("02370028170100000303" + "0040" + res.hex() + "0b050000", response.copyOf(24).hex())
        val kAut = EapAka.kAut(identity, ik, ck)
        assertTrue(EapAka.macIsValid(response, kAut))
        assertFalse(EapAka.macIsValid(response.copyOf().also { it[12] = 0 }, kAut))
        assertFalse(EapAka.macIsValid(response, EapAka.kAut(identity + "x", ik, ck)))
    }

    @Test
    fun aResNotAMultipleOfFourBytesIsPadded() {
        val p = EapAka.challengeResponse(1, hex("a1a2a3a4a5a6"), ByteArray(16))
        assertEquals(40, p.size)
        assertEquals("0303" + "0030" + "a1a2a3a4a5a60000", p.copyOfRange(8, 20).hex())
    }

    @Test
    fun aSimOutOfStepAsksForAnotherTry() {
        val resync = Base64.getEncoder().encodeToString(byteArrayOf(0xDC.toByte(), 14) + ByteArray(14) { 7 })
        assertThrows(EapAka.ResyncNeeded::class.java) { EapAka.respond(challenge(), identity) { resync } }
        assertEquals("0201001817040000" + "0404" + "07".repeat(14), EapAka.syncFailureResponse(1, ByteArray(14) { 7 }).hex())
    }

    @Test
    fun aSimThatRefusesOrSaysNothingIsAnError() {
        assertThrows(IllegalStateException::class.java) { EapAka.respond(challenge(), identity) { null } }
        val refused = Base64.getEncoder().encodeToString(byteArrayOf(0x6A, 0x00))
        assertThrows(IllegalArgumentException::class.java) { EapAka.respond(challenge(), identity) { refused } }
    }
}
