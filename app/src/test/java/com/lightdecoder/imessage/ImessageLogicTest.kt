package com.lightdecoder.imessage

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The iMessage module's rules that do not need OpenBubbles' library or a phone. */
@RunWith(AndroidJUnit4::class)
class ImessageLogicTest {

    // --- unsend window ---------------------------------------------------------------------

    @Test
    fun theUnsendWindowIsTwoMinutes() {
        val sent = 1_000_000L
        assertTrue(UnsendWindow.canUnsend(sent, sent))
        assertTrue(UnsendWindow.canUnsend(sent, sent + 119_999))
        assertFalse(UnsendWindow.canUnsend(sent, sent + 120_000))
        assertEquals(90_000L, UnsendWindow.remaining(sent, sent + 30_000))
        assertEquals(0L, UnsendWindow.remaining(sent, sent + 500_000))
    }

    // --- phone registration by SMS -----------------------------------------------------------

    @Test
    fun theRequestCarriesTheTokenInUpperCaseHex() {
        val text = PhoneRegSms.request(byteArrayOf(0x0a, 0xff.toByte(), 0x00), "123456789")
        assertEquals("REG-REQ?v=3;t=0AFF00;r=123456789", text)
    }

    @Test
    fun requestIdsAreNineDigits() {
        repeat(50) {
            val id = PhoneRegSms.newRequestId()
            assertTrue(id, Regex("^[1-9][0-9]{8}$").matches(id))
        }
    }

    @Test
    fun appleReplyIsParsed() {
        val r = PhoneRegSms.parseResponse("REG-RESP?v=3;r=123456789;n=+15551234567;s=0AFF10")!!
        assertEquals("123456789", r.requestId)
        assertEquals("+15551234567", r.number)
        assertArrayEquals(byteArrayOf(0x0a, 0xff.toByte(), 0x10), r.signature)
    }

    @Test
    fun aReplyIsFoundInsideOtherTextAndEndsAtItsSignature() {
        // A data SMS read as raw bytes: header bytes before, padding after.
        val raw = "\u0000\u0005\u0004xxREG-RESP?v=3;r=662599697;n=+15551234567;s=0AFF10\u0000\u0000"
        val r = PhoneRegSms.parseResponse(raw)!!
        assertEquals("662599697", r.requestId)
        assertEquals("+15551234567", r.number)
        assertArrayEquals(byteArrayOf(0x0a, 0xff.toByte(), 0x10), r.signature)
        // Text parts: carrier headers starting with "~" are left out.
        assertEquals("REG-RESP?v=3;r=1;n=+15551234567;s=AB", PhoneRegSms.joinTextParts(listOf("~hdr", "REG-RESP?v=3;r=1;", "n=+15551234567;s=AB")))
    }

    @Test
    fun otherTextsAreNotReplies() {
        assertNull(PhoneRegSms.parseResponse("Your code is 123456"))
        assertNull(PhoneRegSms.parseResponse("REG-RESP?v=3;r=1;n=+1555"))           // no signature
        assertNull(PhoneRegSms.parseResponse("REG-RESP?v=3;r=1;n=+1555;s=XYZ1"))     // not hex
        assertNull(PhoneRegSms.parseResponse("REG-RESP?v=3;r=1;n=+1555;s=ABC"))      // odd length
        assertNull(PhoneRegSms.parseResponse("REG-REQ?v=3;t=00;r=1"))
    }

    // --- addresses ---------------------------------------------------------------------------

    @Test
    fun phoneNumbersAndEmailsBecomeHandles() {
        assertEquals("tel:+15551234567", Handles.normalize("(555) 123-4567"))
        assertEquals("tel:+15551234567", Handles.normalize("1 555 123 4567"))
        assertEquals("tel:+447700900123", Handles.normalize("+44 7700 900123"))
        assertEquals("mailto:a.b@example.com", Handles.normalize(" A.B@Example.com "))
        assertEquals("tel:+15551234567", Handles.normalize("tel:+15551234567"))
        assertNull(Handles.normalize("hello"))
        assertNull(Handles.normalize("12345"))
        assertNull(Handles.normalize("a@b"))
        assertEquals("+15551234567", Handles.display("tel:+15551234567"))
        assertEquals("tel:+1", Handles.preferredSender(listOf("mailto:x@y.z", "tel:+1")))
    }

    // --- importing OpenBubbles' files --------------------------------------------------------

    private val arm64Bridge = ImessageLibrary.PINS.first { it.abi == "arm64-v8a" && it.name == ImessageLibrary.BRIDGE }

    @Test
    fun thePinnedBridgeIsAccepted() {
        val r = ImessageLibrary.classify(arm64Bridge.sha256.uppercase(), null, "arm64-v8a")
        assertEquals(ImessageLibrary.ImportResult.Installed(ImessageLibrary.BRIDGE, true, arm64Bridge.sha256.uppercase()), r)
    }

    @Test
    fun aPinnedFileForAnotherCpuIsRefusedWithWhereToFindTheRightOne() {
        val r = ImessageLibrary.classify(arm64Bridge.sha256, null, "armeabi-v7a")
        assertTrue(r is ImessageLibrary.ImportResult.Refused)
        assertTrue((r as ImessageLibrary.ImportResult.Refused).reason, "config.armeabi_v7a" in r.reason)
    }

    @Test
    fun anotherBridgeBuildIsRefused() {
        val elf = ElfInfo("arm64-v8a", "librust_bridge.so", exportsHeifDecoder = false, exportsRustBridge = true)
        val r = ImessageLibrary.classify("00".repeat(32), elf, "arm64-v8a")
        assertTrue(r is ImessageLibrary.ImportResult.Refused)
        assertTrue((r as ImessageLibrary.ImportResult.Refused).reason, "2.0.0" in r.reason)
    }

    @Test
    fun anUnpinnedHeifDecoderIsAcceptedOnlyWhereNoneIsPinned() {
        val v7 = ElfInfo("armeabi-v7a", "libheifdec.so", exportsHeifDecoder = true, exportsRustBridge = false)
        assertEquals(
            ImessageLibrary.ImportResult.Installed(ImessageLibrary.HEIF, false, "ab".repeat(32)),
            ImessageLibrary.classify("ab".repeat(32), v7, "armeabi-v7a"),
        )
        val arm64 = ElfInfo("arm64-v8a", "libheifdec.so", exportsHeifDecoder = true, exportsRustBridge = false)
        assertTrue(ImessageLibrary.classify("ab".repeat(32), arm64, "arm64-v8a") is ImessageLibrary.ImportResult.Refused)
        val pinned = ImessageLibrary.PINS.first { it.name == ImessageLibrary.HEIF && it.abi == "arm64-v8a" }
        assertEquals(
            ImessageLibrary.ImportResult.Installed(ImessageLibrary.HEIF, true, pinned.sha256),
            ImessageLibrary.classify(pinned.sha256, arm64, "arm64-v8a"),
        )
    }

    @Test
    fun wrongCpuAndNonLibrariesAreRefused() {
        val elf = ElfInfo("armeabi-v7a", "libheifdec.so", exportsHeifDecoder = true, exportsRustBridge = false)
        assertTrue(ImessageLibrary.classify("cd".repeat(32), elf, "arm64-v8a") is ImessageLibrary.ImportResult.Refused)
        assertTrue(ImessageLibrary.classify("cd".repeat(32), null, "arm64-v8a") is ImessageLibrary.ImportResult.Refused)
        val other = ElfInfo("arm64-v8a", "libfoo.so", exportsHeifDecoder = false, exportsRustBridge = false)
        assertTrue(ImessageLibrary.classify("cd".repeat(32), other, "arm64-v8a") is ImessageLibrary.ImportResult.Refused)
    }

    @Test
    fun theBuildsPinsAreTheAppsPins() {
        // app/imessage-pins.txt decides what the build may build in; ImessageLibrary.PINS what an import accepts.
        val file = listOf(java.io.File("imessage-pins.txt"), java.io.File("app/imessage-pins.txt")).first { it.isFile }
        val fromFile = file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.split(Regex("\\s+")) }.map { ImessageLibrary.Pin(it[0], it[1], it[2]) }.toSet()
        assertEquals(ImessageLibrary.PINS.toSet(), fromFile)
    }

    @Test
    fun theHeifDecoderLoadsBeforeTheBridge() {
        assertEquals(listOf(ImessageLibrary.HEIF, ImessageLibrary.BRIDGE), ImessageLibrary.REQUIRED)
    }
}
