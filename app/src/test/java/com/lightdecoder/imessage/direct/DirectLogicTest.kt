package com.lightdecoder.imessage.direct

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
class DirectLogicTest {

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
        assertEquals("REG-REQ?v=3;t=0AFF00;r=123456789;", text)
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

    private val arm64Bridge = DirectLibrary.PINS.first { it.abi == "arm64-v8a" && it.name == DirectLibrary.BRIDGE }

    @Test
    fun thePinnedBridgeIsAccepted() {
        val r = DirectLibrary.classify(arm64Bridge.sha256.uppercase(), null, "arm64-v8a")
        assertEquals(DirectLibrary.ImportResult.Installed(DirectLibrary.BRIDGE, arm64Bridge.sha256.uppercase()), r)
    }

    @Test
    fun aPinnedFileForAnotherCpuIsRefusedWithWhereToFindTheRightOne() {
        val r = DirectLibrary.classify(arm64Bridge.sha256, null, "armeabi-v7a")
        assertTrue(r is DirectLibrary.ImportResult.Refused)
        assertTrue((r as DirectLibrary.ImportResult.Refused).reason, "config.armeabi_v7a" in r.reason)
    }

    @Test
    fun anotherBridgeBuildIsRefused() {
        val elf = ElfInfo("arm64-v8a", "librust_bridge.so", exportsHeifDecoder = false, exportsRustBridge = true)
        val r = DirectLibrary.classify("00".repeat(32), elf, "arm64-v8a")
        assertTrue(r is DirectLibrary.ImportResult.Refused)
        assertTrue((r as DirectLibrary.ImportResult.Refused).reason, "2.0.0" in r.reason)
    }

    @Test
    fun anUnpinnedHeifDecoderIsNeverAccepted() {
        // No libheifdec.so is pinned for 32-bit ARM: one that looks right is still refused, saying why.
        val v7 = ElfInfo("armeabi-v7a", "libheifdec.so", exportsHeifDecoder = true, exportsRustBridge = false)
        val refused = DirectLibrary.classify("ab".repeat(32), v7, "armeabi-v7a")
        assertTrue(refused is DirectLibrary.ImportResult.Refused)
        assertTrue((refused as DirectLibrary.ImportResult.Refused).reason, "no pinned libheifdec.so for armeabi-v7a" in refused.reason)
        // Without a soname, by what it exports: the same.
        val bare = ElfInfo("armeabi-v7a", null, exportsHeifDecoder = true, exportsRustBridge = false)
        assertTrue(DirectLibrary.classify("ab".repeat(32), bare, "armeabi-v7a") is DirectLibrary.ImportResult.Refused)
        val arm64 = ElfInfo("arm64-v8a", "libheifdec.so", exportsHeifDecoder = true, exportsRustBridge = false)
        assertTrue(DirectLibrary.classify("ab".repeat(32), arm64, "arm64-v8a") is DirectLibrary.ImportResult.Refused)
        val pinned = DirectLibrary.PINS.first { it.name == DirectLibrary.HEIF && it.abi == "arm64-v8a" }
        assertEquals(
            DirectLibrary.ImportResult.Installed(DirectLibrary.HEIF, pinned.sha256),
            DirectLibrary.classify(pinned.sha256, arm64, "arm64-v8a"),
        )
        // No CPU type takes anything but a pinned build.
        for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")) {
            for (elf in listOf(
                ElfInfo(abi, "libheifdec.so", exportsHeifDecoder = true, exportsRustBridge = false),
                ElfInfo(abi, "librust_bridge.so", exportsHeifDecoder = false, exportsRustBridge = true),
                ElfInfo(abi, null, exportsHeifDecoder = true, exportsRustBridge = true),
                null,
            )) {
                assertTrue("$abi $elf", DirectLibrary.classify("ef".repeat(32), elf, abi) is DirectLibrary.ImportResult.Refused)
            }
        }
    }

    @Test
    fun aCpuTypeWithoutPinnedFilesCannotUseTheLibrary() {
        assertEquals(emptyList<String>(), DirectLibrary.unpinned("arm64-v8a"))
        // librust_bridge.so needs libheifdec.so to load: without a pinned one the bridge alone is no use.
        assertEquals(listOf(DirectLibrary.HEIF), DirectLibrary.unpinned("armeabi-v7a"))
        assertEquals(DirectLibrary.REQUIRED, DirectLibrary.unpinned("x86_64"))
        val v7Bridge = ElfInfo("armeabi-v7a", "librust_bridge.so", exportsHeifDecoder = false, exportsRustBridge = true)
        assertTrue(DirectLibrary.classify("01".repeat(32), v7Bridge, "armeabi-v7a") is DirectLibrary.ImportResult.Refused)
        val x86Bridge = ElfInfo("x86_64", "librust_bridge.so", exportsHeifDecoder = false, exportsRustBridge = true)
        val r = DirectLibrary.classify("01".repeat(32), x86Bridge, "x86_64")
        assertTrue((r as DirectLibrary.ImportResult.Refused).reason, "no pinned librust_bridge.so for x86_64" in r.reason)
    }

    @Test
    fun anImportedFileThatIsNotPinnedIsNeverLoaded() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        // A build that carries the libraries for this CPU type loads those, from the APK.
        org.junit.Assume.assumeFalse(DirectLibrary.isBundled(context))
        val dir = DirectLibrary.dir(context).apply { mkdirs() }
        try {
            // As an earlier version could leave them: there, but not the pinned builds.
            DirectLibrary.REQUIRED.forEach { java.io.File(dir, it).writeBytes(byteArrayOf(9, 9, 9)) }
            val e = org.junit.Assert.assertThrows(DirectLibrary.NotPinned::class.java) { DirectLibrary.load(context) }
            assertTrue(e.message, e.message!!.contains("pinned"))
            assertFalse(DirectLibrary.loaded)
            assertTrue("asked for again", DirectLibrary.missing(context).isNotEmpty())
            assertEquals("the screens show why", e.message, DirectEngine.readable(e))
        } finally {
            DirectLibrary.remove(context)
        }
    }

    @Test
    fun theFilesAreHashedAgainBeforeTheyAreLoaded() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "ld-libs-" + java.util.UUID.randomUUID()).apply { mkdirs() }
        try {
            val heif = java.io.File(dir, DirectLibrary.HEIF).apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val bridge = java.io.File(dir, DirectLibrary.BRIDGE).apply { writeBytes(byteArrayOf(4, 5, 6)) }
            val pins = listOf(
                DirectLibrary.Pin(DirectLibrary.HEIF, "arm64-v8a", DirectLibrary.sha256(heif)),
                DirectLibrary.Pin(DirectLibrary.BRIDGE, "arm64-v8a", DirectLibrary.sha256(bridge).uppercase()),
            )
            assertNull("both are the pinned builds", DirectLibrary.verify(dir, "arm64-v8a", pins))
            // Another CPU type has none of these pinned: nothing is loaded, and nothing deleted.
            assertTrue(DirectLibrary.verify(dir, "armeabi-v7a", pins)!!.contains("no pinned"))
            assertTrue(heif.isFile && bridge.isFile)
            // Changed since it was imported (or kept from before the pins were enforced): refused and removed.
            bridge.writeBytes(byteArrayOf(4, 5, 6, 7))
            val why = DirectLibrary.verify(dir, "arm64-v8a", pins)!!
            assertTrue(why, why.contains(DirectLibrary.BRIDGE) && why.contains("not the pinned"))
            assertFalse("removed, so it is asked for again", bridge.exists())
            assertTrue("the other one is fine", heif.isFile)
            assertTrue(DirectLibrary.verify(dir, "arm64-v8a", pins)!!.contains("not imported"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun wrongCpuAndNonLibrariesAreRefused() {
        val elf = ElfInfo("armeabi-v7a", "libheifdec.so", exportsHeifDecoder = true, exportsRustBridge = false)
        assertTrue(DirectLibrary.classify("cd".repeat(32), elf, "arm64-v8a") is DirectLibrary.ImportResult.Refused)
        assertTrue(DirectLibrary.classify("cd".repeat(32), null, "arm64-v8a") is DirectLibrary.ImportResult.Refused)
        val other = ElfInfo("arm64-v8a", "libfoo.so", exportsHeifDecoder = false, exportsRustBridge = false)
        assertTrue(DirectLibrary.classify("cd".repeat(32), other, "arm64-v8a") is DirectLibrary.ImportResult.Refused)
    }

    @Test
    fun theBuildsPinsAreTheAppsPins() {
        // app/imessage-pins.txt decides what the build may build in; DirectLibrary.PINS what an import accepts.
        val file = listOf(java.io.File("imessage-pins.txt"), java.io.File("app/imessage-pins.txt")).first { it.isFile }
        val fromFile = file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.split(Regex("\\s+")) }.map { DirectLibrary.Pin(it[0], it[1], it[2]) }.toSet()
        assertEquals(DirectLibrary.PINS.toSet(), fromFile)
    }

    @Test
    fun theHeifDecoderLoadsBeforeTheBridge() {
        assertEquals(listOf(DirectLibrary.HEIF, DirectLibrary.BRIDGE), DirectLibrary.REQUIRED)
    }
}
