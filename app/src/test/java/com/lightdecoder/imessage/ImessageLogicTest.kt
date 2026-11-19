package com.lightdecoder.imessage

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
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

    // --- unsend window and the GUID store ---------------------------------------------------

    @Test
    fun theUnsendWindowIsTwoMinutes() {
        val sent = 1_000_000L
        assertTrue(SentGuids.canUnsend(sent, sent))
        assertTrue(SentGuids.canUnsend(sent, sent + 119_999))
        assertFalse(SentGuids.canUnsend(sent, sent + 120_000))
        assertEquals(90_000L, SentGuids.remaining(sent, sent + 30_000))
        assertEquals(0L, SentGuids.remaining(sent, sent + 500_000))
    }

    @Test
    fun theStoreKeepsOnlyGuidPartAndTime() {
        val file = File(Files.createTempDirectory("sent").toFile(), "sent_guids.json")
        val store = SentGuids(file)
        store.add(SentGuids.Entry("GUID-1", 0, 1000), now = 1000)
        store.add(SentGuids.Entry("GUID-2", 1, 2000), now = 2000)
        assertEquals(listOf("GUID-1", "GUID-2"), store.all(now = 3000).map { it.guid })
        assertEquals(SentGuids.Entry("GUID-2", 1, 2000), store.find("GUID-2"))
        // On disk: only these three fields per entry, nothing about content or recipients.
        val text = file.readText()
        val keys = Regex("\"(\\w+)\":").findAll(text).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("guid", "part", "sentAt"), keys)
        store.remove("GUID-1")
        assertNull(store.find("GUID-1"))
    }

    @Test
    fun oldEntriesAreDropped() {
        val file = File(Files.createTempDirectory("sent").toFile(), "sent_guids.json")
        val store = SentGuids(file)
        store.add(SentGuids.Entry("OLD", 0, 0), now = 0)
        assertEquals(emptyList<SentGuids.Entry>(), store.all(now = SentGuids.KEEP_MS))
        store.add(SentGuids.Entry("NEW", 0, SentGuids.KEEP_MS), now = SentGuids.KEEP_MS)
        assertEquals(listOf("NEW"), store.all(now = SentGuids.KEEP_MS).map { it.guid })
    }

    @Test
    fun aDamagedStoreReadsAsEmpty() {
        val file = File(Files.createTempDirectory("sent").toFile(), "sent_guids.json")
        file.writeText("{not json")
        assertEquals(emptyList<SentGuids.Entry>(), SentGuids(file).all())
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
    fun anUnpinnedHeifDecoderForThisCpuIsAcceptedAndShownForPinning() {
        val elf = ElfInfo("arm64-v8a", "libheifdec.so", exportsHeifDecoder = true, exportsRustBridge = false)
        val r = ImessageLibrary.classify("ab".repeat(32), elf, "arm64-v8a")
        assertEquals(ImessageLibrary.ImportResult.Installed(ImessageLibrary.HEIF, false, "ab".repeat(32)), r)
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
    fun theHeifDecoderLoadsBeforeTheBridge() {
        assertEquals(listOf(ImessageLibrary.HEIF, ImessageLibrary.BRIDGE), ImessageLibrary.REQUIRED)
    }

    // --- process ------------------------------------------------------------------------------

    @Test
    fun onlyTheImessageProcessIsRecognised() {
        assertTrue(ImessageProcess.isImessageProcess("com.lightdecoder:imessage", "com.lightdecoder"))
        assertFalse(ImessageProcess.isImessageProcess("com.lightdecoder", "com.lightdecoder"))
        assertFalse(ImessageProcess.isImessageProcess("com.lightdecoder:discord", "com.lightdecoder"))
        assertFalse(ImessageProcess.isImessageProcess(null, "com.lightdecoder"))
    }
}
