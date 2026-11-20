package com.lightdecoder.media

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** iPhone voice messages (CAF) into files Android plays, from CAF files built here. */
class CafAudioTest {

    // --- building CAF files ---------------------------------------------------------------------

    private fun chunk(type: String, body: ByteArray, size: Long = body.size.toLong()): ByteArray =
        ByteBuffer.allocate(12 + body.size).order(ByteOrder.BIG_ENDIAN)
            .put(type.toByteArray(Charsets.ISO_8859_1)).putLong(size).put(body).array()

    private fun desc(rate: Double, format: String, flags: Int, bytesPerPacket: Int, framesPerPacket: Int, channels: Int, bits: Int) =
        chunk(
            "desc",
            ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN).putDouble(rate).put(format.toByteArray(Charsets.ISO_8859_1))
                .putInt(flags).putInt(bytesPerPacket).putInt(framesPerPacket).putInt(channels).putInt(bits).array(),
        )

    /** Base-128, big-endian, as CAF's packet table writes sizes. */
    private fun varint(value: Int): ByteArray {
        val groups = ArrayList<Int>()
        var v = value
        do {
            groups.add(0, v and 0x7F)
            v = v ushr 7
        } while (v != 0)
        return ByteArray(groups.size) { i -> (groups[i] or if (i < groups.size - 1) 0x80 else 0).toByte() }
    }

    private fun pakt(sizes: List<Int>, valid: Long, priming: Int, remainder: Int, frames: List<Int>? = null): ByteArray {
        val table = ByteArrayOutputStream()
        sizes.forEachIndexed { i, size ->
            table.write(varint(size))
            frames?.let { table.write(varint(it[i])) }
        }
        val head = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).putLong(sizes.size.toLong()).putLong(valid).putInt(priming).putInt(remainder).array()
        return chunk("pakt", head + table.toByteArray())
    }

    private fun data(sound: ByteArray, unknownSize: Boolean = false): ByteArray {
        val body = ByteArray(4) + sound
        return if (unknownSize) chunk("data", body, -1) else chunk("data", body)
    }

    private fun caf(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("caff".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0, 1, 0, 0))
        chunks.forEach { out.write(it) }
        return out.toByteArray()
    }

    // --- reading Ogg back -----------------------------------------------------------------------

    private class Page(val type: Int, val granule: Long, val sequence: Int, val crcOk: Boolean, val lacing: List<Int>, val body: ByteArray)

    private fun pages(ogg: ByteArray): List<Page> {
        val out = ArrayList<Page>()
        var at = 0
        while (at < ogg.size) {
            assertEquals("OggS", String(ogg, at, 4, Charsets.US_ASCII))
            val b = ByteBuffer.wrap(ogg, at, ogg.size - at).order(ByteOrder.LITTLE_ENDIAN)
            b.position(at + 5)
            val type = b.get().toInt()
            val granule = b.long
            b.int
            val sequence = b.int
            val crc = b.int
            val count = b.get().toInt() and 0xFF
            val lacing = (0 until count).map { b.get().toInt() and 0xFF }
            val length = 27 + count + lacing.sum()
            val page = ogg.copyOfRange(at, at + length)
            page[22] = 0; page[23] = 0; page[24] = 0; page[25] = 0
            out += Page(type, granule, sequence, OggStream.crc(page) == crc, lacing, ogg.copyOfRange(at + 27 + count, at + length))
            at += length
        }
        return out
    }

    /** The packets of a stream's pages, joined across pages. */
    private fun packets(pages: List<Page>): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var current = ByteArrayOutputStream()
        for (page in pages) {
            var at = 0
            for (n in page.lacing) {
                current.write(page.body, at, n)
                at += n
                if (n < 255) {
                    out += current.toByteArray()
                    current = ByteArrayOutputStream()
                }
            }
        }
        return out
    }

    // --- Opus -----------------------------------------------------------------------------------

    /** An Opus packet of one 20 ms CELT frame (config 31, full band): 960 samples at 48 kHz. */
    private fun opusPacket(fill: Int, size: Int = 40) = ByteArray(size) { if (it == 0) (31 shl 3).toByte() else fill.toByte() }

    @Test
    fun opusBecomesOggOpusWithHeadTagsAndGranulesIn48kHzSamples() {
        val packets = listOf(opusPacket(1), opusPacket(2), opusPacket(3, size = 300))
        // 24 kHz, as the format says: the priming and valid frames are counted at that rate.
        val file = caf(
            desc(24000.0, "opus", 0, 0, 480, 1, 0),
            chunk("kuki", byteArrayOf(1, 2, 3)),
            pakt(packets.map { it.size }, valid = 1000, priming = 156, remainder = 284),
            data(packets.fold(ByteArray(0)) { a, p -> a + p }),
        )
        val out = CafAudio.remux(file)
        assertNotNull(out)
        assertEquals("audio/ogg", out!!.mime)
        assertEquals("ogg", out.extension)
        val pages = pages(out.bytes)
        assertTrue("every page's checksum", pages.all { it.crcOk })
        assertEquals((0 until pages.size).toList(), pages.map { it.sequence })
        // The first page begins the stream, the last ends it.
        assertEquals(2, pages.first().type and 2)
        assertEquals(4, pages.last().type and 4)
        val all = packets(pages)
        val head = ByteBuffer.wrap(all[0]).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("OpusHead", String(all[0], 0, 8, Charsets.US_ASCII))
        assertEquals(1, all[0][9].toInt())
        // Pre-skip in 48 kHz samples: 156 frames at 24 kHz.
        assertEquals(312, head.getShort(10).toInt())
        assertEquals(24000, head.getInt(12))
        assertEquals("OpusTags", String(all[1], 0, 8, Charsets.US_ASCII))
        assertEquals(0L, pages[0].granule)
        assertEquals(0L, pages[1].granule)
        // The sound's packets as they were.
        assertEquals(3, all.size - 2)
        packets.forEachIndexed { i, p -> assertArrayEquals(p, all[i + 2]) }
        // The end: pre-skip and the valid frames (in 48 kHz samples), not the padding after them.
        assertEquals(312L + 2000L, pages.last().granule)
    }

    @Test
    fun anOpusHeadInTheCookieIsTakenAsItIs() {
        val head = "OpusHead".toByteArray(Charsets.US_ASCII) + byteArrayOf(1, 1, 0x38, 1, -128, -69, 0, 0, 0, 0, 0)
        val file = caf(
            desc(48000.0, "opus", 0, 0, 960, 1, 0),
            chunk("kuki", head),
            pakt(listOf(40), valid = 0, priming = 0, remainder = 0),
            data(opusPacket(5)),
        )
        val pages = pages(CafAudio.remux(file)!!.bytes)
        assertArrayEquals(head, packets(pages)[0])
        // Without valid frames, the packets' own length (its 312 samples of pre-skip among them).
        assertEquals(960L, pages.last().granule)
    }

    @Test
    fun granulesCountEveryDecodedSampleFromZeroPreSkipIncluded() {
        // 300 packets of 960 samples over several pages; 312 samples of priming, 100 of padding.
        val packets = List(300) { opusPacket(it, size = 300) }
        val total = 300L * 960
        val file = caf(
            desc(48000.0, "opus", 0, 0, 960, 1, 0),
            pakt(packets.map { it.size }, valid = total - 312 - 100, priming = 312, remainder = 100),
            data(packets.fold(ByteArray(0)) { a, p -> a + p }),
        )
        val pages = pages(CafAudio.remux(file)!!.bytes)
        assertEquals(312, ByteBuffer.wrap(packets(pages)[0]).order(ByteOrder.LITTLE_ENDIAN).getShort(10).toInt())
        // Each audio page: the samples of the packets completed so far, from 0 (RFC 7845 §4).
        var completed = 0
        val audio = pages.drop(2)
        audio.dropLast(1).forEach { page ->
            completed += page.lacing.count { it < 255 }
            if (page.granule >= 0) assertEquals(completed * 960L, page.granule)
        }
        assertTrue("more than one audio page", audio.size > 2)
        // The end: all decoded samples but the padding, so the pre-skip plus the valid ones.
        assertEquals(total - 100, pages.last().granule)
        val known = pages.map { it.granule }.filter { it >= 0 }
        assertEquals(known.sorted(), known)
    }

    @Test
    fun opusPacketLengthsComeFromTheirFirstByte() {
        // SILK narrow band 20 ms, one frame.
        assertEquals(960L, CafAudio.opusSamples(byteArrayOf((1 shl 3).toByte())))
        // Hybrid 10 ms, two frames.
        assertEquals(960L, CafAudio.opusSamples(byteArrayOf(((12 shl 3) or 1).toByte())))
        // CELT 2.5 ms, a count of frames: 4.
        assertEquals(480L, CafAudio.opusSamples(byteArrayOf(((16 shl 3) or 3).toByte(), 4)))
        // SILK 60 ms.
        assertEquals(2880L, CafAudio.opusSamples(byteArrayOf((3 shl 3).toByte())))
        assertNull(CafAudio.opusSamples(ByteArray(0)))
    }

    @Test
    fun aLongPacketCarriesOnInTheNextPage() {
        // 300 packets of 300 bytes: two lacing values each, more than one page holds.
        val packets = List(300) { opusPacket(it, size = 300) }
        val file = caf(
            desc(48000.0, "opus", 0, 0, 960, 1, 0),
            pakt(packets.map { it.size }, valid = 0, priming = 0, remainder = 0),
            data(packets.fold(ByteArray(0)) { a, p -> a + p }),
        )
        val pages = pages(CafAudio.remux(file)!!.bytes)
        assertTrue(pages.size > 3)
        assertTrue(pages.all { it.crcOk && it.lacing.size <= 255 })
        // A page that begins with the rest of a packet says so.
        assertTrue(pages.drop(2).any { it.type and 1 == 1 })
        val all = packets(pages).drop(2)
        assertEquals(300, all.size)
        packets.forEachIndexed { i, p -> assertArrayEquals(p, all[i]) }
        assertEquals(300L * 960, pages.last().granule)
        // Granules never go back.
        val known = pages.map { it.granule }.filter { it >= 0 }
        assertEquals(known.sorted(), known)
    }

    // --- crafted files: no sound, never a crash ------------------------------------------------

    /** A packet table that names [count] packets and holds no entries (as when the format fixes both sizes). */
    private fun paktHead(count: Long, valid: Long = 0) =
        chunk("pakt", ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).putLong(count).putLong(valid).putInt(0).putInt(0).array())

    @Test
    fun aPacketTableNamingMorePacketsThanItHoldsIsNoSound() {
        // Sizes in the table (the format fixes none): a count its entries cannot back is refused at once,
        // before anything is made for it (it used to be millions of boxed sizes, and the phone ran out of memory).
        val sound = ByteArray(4096) { (31 shl 3).toByte() }
        for (count in listOf(Long.MAX_VALUE, Int.MAX_VALUE.toLong(), CafAudio.MAX_PACKETS + 1L, 4097L, 100L)) {
            assertNull("opus $count", CafAudio.remux(caf(desc(48000.0, "opus", 0, 0, 960, 1, 0), paktHead(count), data(sound))))
            assertNull("aac $count", CafAudio.remux(caf(desc(44100.0, "aac ", 2, 0, 1024, 1, 0), paktHead(count), data(sound))))
        }
    }

    @Test
    fun aPacketTableIsNeverTakenForMorePacketsThanTheSoundHasBytes() {
        // Both sizes fixed: the table has no entries, only its count, which may say anything.
        val sound = ByteArray(4096) { (31 shl 3).toByte() }
        val started = System.nanoTime()
        for (count in listOf(Long.MAX_VALUE, CafAudio.MAX_PACKETS.toLong(), 5000L)) {
            // Not taken: the sound is cut by the fixed size instead, 4096 packets of one byte.
            val out = CafAudio.remux(caf(desc(48000.0, "opus", 0, 1, 960, 1, 0), paktHead(count), data(sound)))
            assertEquals(4096, packets(pages(out!!.bytes)).size - 2)
        }
        assertTrue("at once", System.nanoTime() - started < 10_000_000_000L)
    }

    @Test
    fun aSoundCutIntoTooManyPacketsIsNoSound() {
        // No table, one byte a packet: more packets than are taken.
        val sound = ByteArray(CafAudio.MAX_PACKETS + 1) { (31 shl 3).toByte() }
        assertNull(CafAudio.remux(caf(desc(44100.0, "aac ", 2, 1, 1024, 1, 0), data(sound))))
        assertNull(CafAudio.remux(caf(desc(48000.0, "opus", 0, 1, 960, 1, 0), data(sound))))
        // Exactly as many as are taken: still remuxed, in one go.
        val most = CafAudio.remux(caf(desc(44100.0, "aac ", 2, 1, 1024, 1, 0), data(ByteArray(CafAudio.MAX_PACKETS))))
        assertEquals(CafAudio.MAX_PACKETS * 8, most!!.bytes.size)
    }

    @Test
    fun packetsLargerThanTheSoundAreNoSound() {
        val packets = listOf(opusPacket(1), opusPacket(2))
        val file = caf(desc(48000.0, "opus", 0, 0, 960, 1, 0), pakt(listOf(40, 41), 0, 0, 0), data(packets.fold(ByteArray(0)) { a, p -> a + p }))
        assertNull(CafAudio.remux(file))
    }

    @Test
    fun noDamagedFileThrows() {
        val random = java.util.Random(7)
        val good = caf(
            desc(48000.0, "opus", 0, 0, 960, 1, 0),
            pakt(listOf(40, 40), valid = 0, priming = 0, remainder = 0),
            data(opusPacket(1) + opusPacket(2)),
        )
        repeat(500) {
            // The good file with bytes changed, cut short, or grown.
            val bytes = good.copyOf(good.size + random.nextInt(64) - 32).also { b ->
                repeat(1 + random.nextInt(8)) { if (b.size > 8) b[8 + random.nextInt(b.size - 8)] = random.nextInt(256).toByte() }
            }
            CafAudio.remux(bytes)
        }
        repeat(200) { CafAudio.remux("caff".toByteArray(Charsets.US_ASCII) + ByteArray(random.nextInt(200)).also(random::nextBytes)) }
    }

    // --- AAC ------------------------------------------------------------------------------------

    /** An `esds`-style magic cookie: version and flags, ES descriptor, decoder config, AudioSpecificConfig [asc]. */
    private fun esdsCookie(asc: ByteArray): ByteArray {
        val specific = byteArrayOf(5, asc.size.toByte()) + asc
        val config = byteArrayOf(4, (13 + specific.size).toByte(), 0x40, 0x15, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0) + specific
        val es = byteArrayOf(3, (3 + config.size).toByte(), 0, 0, 0) + config
        return ByteArray(4) + es
    }

    @Test
    fun aacBecomesAdtsFramesWithTheCookiesProfileAndRate() {
        val packets = listOf(ByteArray(100) { 1 }, ByteArray(7) { 2 })
        // AAC-LC (2), 44.1 kHz (index 4), mono: 00010 0100 0001 000
        val file = caf(
            desc(44100.0, "aac ", 2, 0, 1024, 1, 0),
            chunk("kuki", esdsCookie(byteArrayOf(0x12, 0x08))),
            pakt(packets.map { it.size }, valid = 2048, priming = 2112, remainder = 0),
            data(packets.fold(ByteArray(0)) { a, p -> a + p }),
        )
        val out = CafAudio.remux(file)!!
        assertEquals("audio/aac", out.mime)
        assertEquals("aac", out.extension)
        val b = out.bytes
        // First frame: header, then the packet.
        assertEquals(0xFF, b[0].toInt() and 0xFF)
        assertEquals(0xF1, b[1].toInt() and 0xFF)
        assertEquals(1, (b[2].toInt() and 0xFF) shr 6)
        assertEquals(4, (b[2].toInt() shr 2) and 0x0F)
        val channels = ((b[2].toInt() and 1) shl 2) or ((b[3].toInt() and 0xFF) shr 6)
        assertEquals(1, channels)
        val length = ((b[3].toInt() and 3) shl 11) or ((b[4].toInt() and 0xFF) shl 3) or ((b[5].toInt() and 0xFF) shr 5)
        assertEquals(107, length)
        assertArrayEquals(packets[0], b.copyOfRange(7, 107))
        // The second frame right after.
        assertEquals(0xFF, b[107].toInt() and 0xFF)
        assertEquals(107 + 14, b.size)
    }

    @Test
    fun theCookieGivesTheProfileWhereTheFormatDoesNot() {
        assertEquals(CafAudio.AacConfig(2, 4, 1), CafAudio.aacConfig(esdsCookie(byteArrayOf(0x12, 0x08))))
        // HE-AAC at a 24 kHz core (index 6), stereo: 00101 0110 0010 ...
        assertEquals(CafAudio.AacConfig(5, 6, 2), CafAudio.aacConfig(esdsCookie(byteArrayOf(0x2B, 0x10, 0, 0))))
        // Without the version and flags in front.
        assertEquals(CafAudio.AacConfig(2, 4, 1), CafAudio.aacConfig(esdsCookie(byteArrayOf(0x12, 0x08)).let { it.copyOfRange(4, it.size) }))
        assertNull(CafAudio.aacConfig(byteArrayOf(9, 9, 9)))
    }

    @Test
    fun withoutACookieTheFormatSaysTheProfileAndRate() {
        val file = caf(desc(16000.0, "aac ", 2, 0, 1024, 1, 0), pakt(listOf(5), 1024, 0, 0), data(ByteArray(5)))
        val b = CafAudio.remux(file)!!.bytes
        assertEquals(1, (b[2].toInt() and 0xFF) shr 6)
        assertEquals(8, (b[2].toInt() shr 2) and 0x0F)
    }

    @Test
    fun aacThatAdtsCannotCarryStaysACaf() {
        // AAC-ELD (39).
        val eld = caf(desc(48000.0, "aac ", 39, 0, 480, 1, 0), pakt(listOf(5), 480, 0, 0), data(ByteArray(5)))
        assertNull(CafAudio.remux(eld))
    }

    // --- AMR and PCM ----------------------------------------------------------------------------

    @Test
    fun amrBecomesAnAmrFile() {
        // Mode 7 (12.2 kbit/s) frames: header 0x3C and 31 bytes each.
        val frames = ByteArray(64) { if (it % 32 == 0) 0x3C else 1 }
        val file = caf(desc(8000.0, "samr", 0, 0, 160, 1, 0), pakt(listOf(32, 32), 320, 0, 0), data(frames))
        val out = CafAudio.remux(file)!!
        assertEquals("audio/amr", out.mime)
        assertEquals("amr", out.extension)
        assertArrayEquals("#!AMR\n".toByteArray(Charsets.US_ASCII) + frames, out.bytes)
        // Not frames with their header byte: left as it is.
        assertNull(CafAudio.remux(caf(desc(8000.0, "samr", 0, 0, 160, 1, 0), data(byteArrayOf(0x7F, 1)))))
    }

    @Test
    fun linearPcmBecomesWavInWavsByteOrder() {
        // Two 16-bit big-endian stereo frames, no packet table (a fixed size), size unknown.
        val sound = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        val file = caf(desc(44100.0, "lpcm", 0, 4, 1, 2, 16), data(sound, unknownSize = true))
        val out = CafAudio.remux(file)!!
        assertEquals("audio/x-wav", out.mime)
        assertEquals("wav", out.extension)
        val b = ByteBuffer.wrap(out.bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(out.bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + 8, b.getInt(4))
        assertEquals("WAVE", String(out.bytes, 8, 4, Charsets.US_ASCII))
        assertEquals(1, b.getShort(20).toInt())
        assertEquals(2, b.getShort(22).toInt())
        assertEquals(44100, b.getInt(24))
        assertEquals(44100 * 4, b.getInt(28))
        assertEquals(4, b.getShort(32).toInt())
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(8, b.getInt(40))
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x04, 0x03, 0x06, 0x05, 0x08, 0x07), out.bytes.copyOfRange(44, 52))
        // Little-endian floats stay as they are, marked float.
        val floats = CafAudio.remux(caf(desc(48000.0, "lpcm", 3, 4, 1, 1, 32), data(sound)))!!
        assertEquals(3, ByteBuffer.wrap(floats.bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(20).toInt())
        assertArrayEquals(sound, floats.bytes.copyOfRange(44, 52))
    }

    // --- not playable here ----------------------------------------------------------------------

    @Test
    fun anythingElseStaysACaf() {
        assertNull("another codec", CafAudio.remux(caf(desc(44100.0, "alac", 0, 0, 4096, 1, 16), data(ByteArray(10)))))
        assertNull("not a CAF", CafAudio.remux("RIFF....WAVE".toByteArray()))
        assertNull("no sound", CafAudio.remux(caf(desc(44100.0, "lpcm", 0, 2, 1, 1, 16))))
        assertNull("no format", CafAudio.remux(caf(data(ByteArray(10)))))
        assertNull("cut short", CafAudio.remux(caf(desc(24000.0, "opus", 0, 0, 480, 1, 0), pakt(listOf(400), 0, 0, 0), data(opusPacket(1)))))
        assertTrue(CafAudio.isCaf(caf()))
    }
}
