package com.lightdecoder.media

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * An iPhone's voice message is a CAF file (Apple's Core Audio Format), which Android cannot play.
 * Its sound is in a codec Android can play, though, so it is put into a file Android reads,
 * unchanged ([remux]):
 *
 * - Opus (`opus`) into Ogg Opus (OpusHead, OpusTags, then the packets, each page's granule
 *   position in 48 kHz samples as Ogg Opus counts them);
 * - AAC (`aac `) into ADTS frames, with the profile and sample rate from the file's magic cookie;
 * - AMR (`samr`) into an `.amr` file;
 * - linear PCM (`lpcm`) into WAV.
 *
 * Anything else stays a CAF (null). A file that is damaged or made to be too much (a packet table
 * that names millions of packets, say) is no sound either: the packets are counted and capped
 * ([MAX_PACKETS]) before anything is made of them, held as offsets into the file rather than
 * copies, and running out of memory on one is "cannot be played", never a crash.
 * Pure: tested on synthesized files in CafAudioTest.
 */
object CafAudio {

    /**
     * More packets than this are not taken (about 11 hours of Opus at 20 ms a packet; an iPhone's
     * voice message has a few thousand). Also never more packets than the sound has bytes.
     */
    const val MAX_PACKETS = 2_000_000

    /** The sound in a form Android plays: its bytes, type and file extension. */
    class Playable(val bytes: ByteArray, val mime: String, val extension: String)

    /** The `desc` chunk: how the sound is coded. */
    data class Format(
        val sampleRate: Double,
        val formatId: String,
        val flags: Int,
        val bytesPerPacket: Int,
        val framesPerPacket: Int,
        val channels: Int,
        val bitsPerChannel: Int,
    )

    /** The `pakt` chunk: each packet's size in bytes and in frames (the format's when it fixes them). */
    class PacketTable(
        val validFrames: Long,
        val primingFrames: Int,
        val remainderFrames: Int,
        val sizes: IntArray,
        val frames: IntArray,
    )

    /**
     * A CAF file's chunks: [format], the codec's [cookie] (`kuki`), [packets] and the sound itself,
     * [dataLength] bytes of [bytes] from [dataOffset] (the file's own array, not a copy).
     */
    class Caf(
        val format: Format,
        val cookie: ByteArray?,
        val packets: PacketTable?,
        val bytes: ByteArray,
        val dataOffset: Int,
        val dataLength: Int,
    )

    const val MIME = "audio/x-caf"

    /** Whether [bytes] start like a CAF file. */
    fun isCaf(bytes: ByteArray): Boolean =
        bytes.size >= 8 && bytes[0] == 'c'.code.toByte() && bytes[1] == 'a'.code.toByte() &&
            bytes[2] == 'f'.code.toByte() && bytes[3] == 'f'.code.toByte()

    /**
     * The sound of the CAF file [bytes] as a file Android plays, or null (not a CAF, damaged, made
     * to be too large to remux, or another codec). Never throws, not even for lack of memory.
     */
    fun remux(bytes: ByteArray): Playable? = try {
        parse(bytes)?.let { caf ->
            when (caf.format.formatId) {
                "opus" -> opus(caf)
                "aac " -> adts(caf)
                "samr" -> amr(caf)
                "lpcm" -> wav(caf)
                else -> null
            }
        }
    } catch (e: RuntimeException) {
        // IndexOutOfBounds, IllegalArgument, BufferUnderflow, NegativeArraySize...: a damaged file.
        null
    } catch (e: OutOfMemoryError) {
        // A file made to need more than there is: it cannot be played here, the app goes on.
        null
    }

    /** Reads a CAF file's chunks (without copying the sound); null when it is not one, or has no format or sound. */
    fun parse(bytes: ByteArray): Caf? {
        if (!isCaf(bytes)) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buf.position(8)
        var format: Format? = null
        var cookie: ByteArray? = null
        var pakt: IntRange? = null
        var data: IntRange? = null
        while (buf.remaining() >= 12) {
            val type = fourCc(buf)
            val declared = buf.long
            // The sound's size may be unknown (-1): it then runs to the end of the file.
            val size = if (declared < 0 || declared > buf.remaining()) buf.remaining() else declared.toInt()
            val start = buf.position()
            buf.position(start + size)
            when (type) {
                "desc" -> format = format(ByteBuffer.wrap(bytes, start, size).slice()) ?: return null
                // The codec's own header: small (19 bytes for Opus, a few dozen for AAC).
                "kuki" -> cookie = if (size <= MAX_COOKIE) bytes.copyOfRange(start, start + size) else null
                "pakt" -> pakt = start until start + size
                "data" -> data = if (size >= 4) (start + 4) until (start + size) else return null
            }
        }
        val f = format ?: return null
        val sound = data ?: return null
        val length = sound.last - sound.first + 1
        // A table that cannot be read (or names too many packets) is none: the format's fixed packet size may still cut the sound.
        val table = pakt?.let { packetTable(ByteBuffer.wrap(bytes, it.first, it.last - it.first + 1).slice(), f, length) }
        return Caf(f, cookie, table, bytes, sound.first, length)
    }

    /** A magic cookie larger than this is no codec header: left out. */
    private const val MAX_COOKIE = 64 * 1024

    private fun fourCc(buf: ByteBuffer): String {
        val b = ByteArray(4).also { buf.get(it) }
        return String(b, Charsets.ISO_8859_1)
    }

    private fun format(body: ByteBuffer): Format? {
        if (body.remaining() < 32) return null
        val b = body.order(ByteOrder.BIG_ENDIAN)
        val rate = b.double
        val id = fourCc(b)
        return Format(rate, id, b.int, b.int, b.int, b.int, b.int)
    }

    /**
     * The packet table; packet sizes (and frame counts) are big-endian base-128 numbers. Null when
     * it names more packets than [MAX_PACKETS], than the sound's [dataLength] bytes could hold, or
     * than its own entries say.
     */
    private fun packetTable(body: ByteBuffer, format: Format, dataLength: Int): PacketTable? {
        if (body.remaining() < 24) return null
        val b = body.order(ByteOrder.BIG_ENDIAN)
        val count = b.long
        val valid = b.long
        val priming = b.int
        val remainder = b.int
        if (count < 0 || count > MAX_PACKETS || count > dataLength) return null
        // Each packet the table sizes takes at least one byte of it.
        val perEntry = (if (format.bytesPerPacket == 0) 1 else 0) + (if (format.framesPerPacket == 0) 1 else 0)
        if (count * perEntry > b.remaining()) return null
        val n = count.toInt()
        val sizes = IntArray(n)
        val frames = IntArray(n)
        for (i in 0 until n) {
            sizes[i] = if (format.bytesPerPacket == 0) varint(b) ?: return null else format.bytesPerPacket
            frames[i] = if (format.framesPerPacket == 0) varint(b) ?: return null else format.framesPerPacket
        }
        return PacketTable(valid, priming, remainder, sizes, frames)
    }

    private fun varint(b: ByteBuffer): Int? {
        var value = 0L
        repeat(5) {
            if (!b.hasRemaining()) return null
            val byte = b.get().toInt() and 0xFF
            value = (value shl 7) or (byte and 0x7F).toLong()
            if (byte and 0x80 == 0) return value.takeIf { it <= Int.MAX_VALUE }?.toInt()
        }
        return null
    }

    /**
     * Where each packet starts in [Caf.bytes], and (last) where the last one ends: by the packet
     * table, else by the fixed packet size. The packets are never copied out one by one. Null when
     * they do not fit in the sound, or are more than [MAX_PACKETS].
     */
    private fun packets(caf: Caf): IntArray? {
        val table = caf.packets
        val fixed = caf.format.bytesPerPacket
        val count = when {
            table != null -> table.sizes.size
            fixed > 0 -> caf.dataLength / fixed
            else -> return null
        }
        if (count > MAX_PACKETS) return null
        val bounds = IntArray(count + 1)
        var at = caf.dataOffset.toLong()
        val end = caf.dataOffset.toLong() + caf.dataLength
        bounds[0] = caf.dataOffset
        for (i in 0 until count) {
            val size = table?.sizes?.get(i) ?: fixed
            if (size < 0 || at + size > end) return null
            at += size
            bounds[i + 1] = at.toInt()
        }
        return bounds
    }

    // --- Opus -------------------------------------------------------------------------------

    private const val OPUS_RATE = 48_000

    private fun opus(caf: Caf): Playable? {
        val f = caf.format
        val packets = packets(caf)?.takeIf { it.size > 1 } ?: return null
        // Mapping family 0 (in OpusHead) is for mono and stereo only.
        if (f.channels !in 1..2 || f.sampleRate <= 0) return null
        fun to48k(frames: Long) = (frames * OPUS_RATE / f.sampleRate).toLong()
        // An OpusHead of the encoder's own, else one from the format and the frames it primed with.
        val cookie = caf.cookie?.takeIf { startsWith(it, "OpusHead") && it.size >= 19 }
        val preSkip = cookie?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).getShort(10).toInt() and 0xFFFF }
            ?: to48k(caf.packets?.primingFrames?.toLong() ?: 0L).coerceIn(0, 0xFFFF).toInt()
        val head = cookie ?: opusHead(f.channels, preSkip, f.sampleRate.toInt())
        val ogg = OggStream(SERIAL)
        ogg.add(head, 0)
        ogg.flush()
        ogg.add(opusTags(), 0)
        ogg.flush()
        // Every decoded sample counts, the pre-skip's too (RFC 7845 §4): the stream starts at 0.
        var granule = 0L
        val bytes = caf.bytes
        for (i in 0 until packets.size - 1) {
            val start = packets[i]
            val length = packets[i + 1] - start
            val samples = opusSamples(bytes, start, length) ?: caf.packets?.frames?.getOrNull(i)?.let { to48k(it.toLong()) } ?: return null
            granule += samples
            ogg.add(bytes, start, length, granule)
        }
        // The last page says where the sound ends: the encoder's padding after it is not played.
        val valid = caf.packets?.validFrames ?: 0L
        if (valid > 0) ogg.endAt(minOf(granule, preSkip + to48k(valid)))
        ogg.flush(eos = true)
        return Playable(ogg.bytes(), "audio/ogg", "ogg")
    }

    private fun opusHead(channels: Int, preSkip: Int, inputRate: Int): ByteArray =
        ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray(Charsets.US_ASCII))
            put(1)
            put(channels.toByte())
            putShort(preSkip.toShort())
            putInt(inputRate)
            putShort(0)
            put(0)
        }.array()

    private fun opusTags(): ByteArray {
        val vendor = "LightDecoder".toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(8 + 4 + vendor.size + 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusTags".toByteArray(Charsets.US_ASCII))
            putInt(vendor.size)
            put(vendor)
            putInt(0)
        }.array()
    }

    /** An Opus packet's length in 48 kHz samples, from its first byte (RFC 6716 §3.1); null if it has none. */
    internal fun opusSamples(packet: ByteArray): Long? = opusSamples(packet, 0, packet.size)

    /** [opusSamples] of the [length] bytes of [bytes] from [offset]. */
    internal fun opusSamples(bytes: ByteArray, offset: Int, length: Int): Long? {
        if (length <= 0) return null
        val toc = bytes[offset].toInt() and 0xFF
        val config = toc shr 3
        val frame = when {
            config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3]
            config < 16 -> intArrayOf(480, 960)[config and 1]
            else -> intArrayOf(120, 240, 480, 960)[config and 3]
        }
        val count = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (length < 2) return null else bytes[offset + 1].toInt() and 0x3F
        }
        return frame.toLong() * count
    }

    // --- AAC --------------------------------------------------------------------------------

    private val AAC_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /** What an ADTS header needs: the profile (object type − 1), the sample rate's index and the channels. */
    internal data class AacConfig(val objectType: Int, val rateIndex: Int, val channels: Int)

    private fun adts(caf: Caf): Playable? {
        val f = caf.format
        val packets = packets(caf)?.takeIf { it.size > 1 } ?: return null
        val cookie = caf.cookie?.let(::aacConfig)
        // Without a readable cookie: the object type the format names (AAC-LC when it names none) at its rate.
        val config = cookie ?: AacConfig(f.flags.takeIf { it != 0 } ?: 2, AAC_RATES.indexOf(f.sampleRate.toInt()), f.channels)
        val objectType = when (config.objectType) {
            in 1..4 -> config.objectType
            // HE-AAC: its AAC-LC core (the decoder finds the rest by itself).
            5, 29 -> 2
            else -> return null
        }
        val channels = config.channels.takeIf { it in 1..7 } ?: f.channels.takeIf { it in 1..7 } ?: return null
        if (config.rateIndex !in 0..12) return null
        val count = packets.size - 1
        // Each packet with its 7-byte header, in one array of the size it will be.
        val out = ByteArray((packets[count] - packets[0]) + 7 * count)
        var at = 0
        for (i in 0 until count) {
            val start = packets[i]
            val size = packets[i + 1] - start
            val length = size + 7
            if (length > 0x1FFF) return null
            out[at] = 0xFF.toByte()
            out[at + 1] = 0xF1.toByte()
            out[at + 2] = (((objectType - 1) shl 6) or (config.rateIndex shl 2) or (channels shr 2)).toByte()
            out[at + 3] = (((channels and 3) shl 6) or (length shr 11)).toByte()
            out[at + 4] = ((length shr 3) and 0xFF).toByte()
            out[at + 5] = (((length and 7) shl 5) or 0x1F).toByte()
            out[at + 6] = 0xFC.toByte()
            System.arraycopy(caf.bytes, start, out, at + 7, size)
            at += length
        }
        return Playable(out, "audio/aac", "aac")
    }

    /**
     * The AudioSpecificConfig in an AAC magic cookie: an MPEG-4 ES descriptor (as in an `esds`
     * box, maybe after its version and flags) holding a decoder config holding the decoder's
     * specific info. Null when there is none.
     */
    internal fun aacConfig(cookie: ByteArray): AacConfig? {
        for (start in intArrayOf(0, 4, 12)) {
            if (start >= cookie.size) break
            val info = try {
                specificInfo(ByteBuffer.wrap(cookie, start, cookie.size - start))
            } catch (e: java.nio.BufferUnderflowException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
            if (info != null) return audioSpecificConfig(info)
        }
        return null
    }

    private fun specificInfo(b: ByteBuffer): ByteArray? {
        var tag = b.get().toInt() and 0xFF
        descriptorLength(b)
        if (tag == 3) {
            b.short // ES id
            val flags = b.get().toInt() and 0xFF
            if (flags and 0x80 != 0) b.short
            if (flags and 0x40 != 0) b.position(b.position() + (b.get().toInt() and 0xFF))
            if (flags and 0x20 != 0) b.short
            tag = b.get().toInt() and 0xFF
            descriptorLength(b)
        }
        if (tag != 4) return null
        // Object type, stream type, buffer size and bit rates.
        b.position(b.position() + 13)
        if (b.get().toInt() and 0xFF != 5) return null
        val length = descriptorLength(b)
        if (length <= 0 || length > b.remaining()) return null
        return ByteArray(length).also { b.get(it) }
    }

    private fun descriptorLength(b: ByteBuffer): Int {
        var length = 0
        repeat(4) {
            val byte = b.get().toInt() and 0xFF
            length = (length shl 7) or (byte and 0x7F)
            if (byte and 0x80 == 0) return length
        }
        return length
    }

    private fun audioSpecificConfig(info: ByteArray): AacConfig? {
        val bits = Bits(info)
        var objectType = bits.read(5)
        if (objectType == 31) objectType = 32 + bits.read(6)
        val rateIndex = bits.read(4)
        // A rate given in full, not by index: an ADTS header cannot say it.
        if (rateIndex == 15) return null
        val channels = bits.read(4)
        return AacConfig(objectType, rateIndex, channels).takeIf { bits.ok }
    }

    private class Bits(private val bytes: ByteArray) {
        private var at = 0
        var ok = true
            private set

        fun read(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = at / 8
                if (byte >= bytes.size) {
                    ok = false
                    return v
                }
                v = (v shl 1) or ((bytes[byte].toInt() shr (7 - at % 8)) and 1)
                at++
            }
            return v
        }
    }

    // --- AMR and PCM --------------------------------------------------------------------------

    /** AMR frames carry their own header byte: the file is "#!AMR\n" and the frames as they are. */
    private fun amr(caf: Caf): Playable? {
        if (caf.dataLength <= 0) return null
        val first = caf.bytes[caf.dataOffset].toInt() and 0xFF
        // A frame header: 0, the 4-bit mode (0-8 speech, 15 nothing), the quality bit, 0, 0.
        val mode = (first shr 3) and 0x0F
        if (first and 0x83 != 0 || (mode > 8 && mode != 15)) return null
        val magic = "#!AMR\n".toByteArray(Charsets.US_ASCII)
        val out = magic.copyOf(magic.size + caf.dataLength)
        System.arraycopy(caf.bytes, caf.dataOffset, out, magic.size, caf.dataLength)
        return Playable(out, "audio/amr", "amr")
    }

    private const val PCM_FLOAT = 1
    private const val PCM_LITTLE_ENDIAN = 2

    private fun wav(caf: Caf): Playable? {
        val f = caf.format
        val bytesPerSample = f.bitsPerChannel / 8
        if (f.bitsPerChannel % 8 != 0 || bytesPerSample !in 1..8 || f.channels !in 1..8 || f.sampleRate <= 0) return null
        val float = f.flags and PCM_FLOAT != 0
        val frameBytes = bytesPerSample * f.channels
        val size = caf.dataLength / frameBytes * frameBytes
        // The WAV file in one array: its 44-byte header, then the samples, turned in place.
        val out = ByteArray(44 + size)
        System.arraycopy(caf.bytes, caf.dataOffset, out, 44, size)
        if (f.flags and PCM_LITTLE_ENDIAN == 0 && bytesPerSample > 1) {
            for (i in 44 until 44 + size step bytesPerSample) out.reverse(i, i + bytesPerSample)
        }
        // WAV's 8-bit samples are unsigned, CAF's signed.
        if (bytesPerSample == 1) for (i in 44 until 44 + size) out[i] = (out[i].toInt() xor 0x80).toByte()
        val rate = f.sampleRate.toInt()
        ByteBuffer.wrap(out, 0, 44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + size)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort((if (float) 3 else 1).toShort())
            putShort(f.channels.toShort())
            putInt(rate)
            putInt(rate * frameBytes)
            putShort(frameBytes.toShort())
            putShort(f.bitsPerChannel.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(size)
        }
        return Playable(out, "audio/x-wav", "wav")
    }

    private fun ByteArray.reverse(from: Int, to: Int) {
        var i = from
        var j = to - 1
        while (i < j) {
            val t = this[i]
            this[i] = this[j]
            this[j] = t
            i++
            j--
        }
    }

    private fun startsWith(bytes: ByteArray, text: String): Boolean =
        bytes.size >= text.length && text.indices.all { bytes[it] == text[it].code.toByte() }

    /** The Ogg stream's serial number (one stream per file, so any). */
    private const val SERIAL = 0x4C440001
}

/**
 * One logical Ogg stream (RFC 3533) written page by page: packets are laced into pages of at most
 * 255 segments, a packet longer than a page carries on in the next; each page's granule position is
 * that of the last packet that ends on it (-1 when none does).
 */
internal class OggStream(private val serial: Int) {
    private val out = ByteArrayOutputStream()
    private var sequence = 0
    private val lacing = ArrayList<Int>()
    private val body = ByteArrayOutputStream()
    private var granule = -1L
    private var ended = false
    private var continued = false

    /** Adds [packet], after which the stream is at [granuleAfter]. */
    fun add(packet: ByteArray, granuleAfter: Long) = add(packet, 0, packet.size, granuleAfter)

    /** Adds the packet that is the [length] bytes of [bytes] from [offset] (not copied out first). */
    fun add(bytes: ByteArray, offset: Int, length: Int, granuleAfter: Long) {
        var at = 0
        while (true) {
            if (lacing.size == 255) page(eos = false, nextContinued = at > 0)
            val n = minOf(255, length - at)
            lacing += n
            body.write(bytes, offset + at, n)
            at += n
            if (n < 255) break
        }
        granule = granuleAfter
        ended = true
    }

    /** The granule position the stream ends at (less than its last packet's: the rest is padding). */
    fun endAt(granulePosition: Long) {
        granule = granulePosition
    }

    /** Ends the page being built (the stream's last with [eos]). */
    fun flush(eos: Boolean = false) {
        if (lacing.isNotEmpty() || eos) page(eos, nextContinued = false)
    }

    fun bytes(): ByteArray = out.toByteArray()

    private fun page(eos: Boolean, nextContinued: Boolean) {
        val header = ByteBuffer.allocate(27 + lacing.size).order(ByteOrder.LITTLE_ENDIAN)
        header.put("OggS".toByteArray(Charsets.US_ASCII))
        header.put(0)
        header.put(((if (continued) 1 else 0) or (if (sequence == 0) 2 else 0) or (if (eos) 4 else 0)).toByte())
        header.putLong(if (ended) granule else -1L)
        header.putInt(serial)
        header.putInt(sequence)
        header.putInt(0)
        header.put(lacing.size.toByte())
        lacing.forEach { header.put(it.toByte()) }
        val page = header.array() + body.toByteArray()
        val crc = crc(page)
        page[22] = crc.toByte()
        page[23] = (crc shr 8).toByte()
        page[24] = (crc shr 16).toByte()
        page[25] = (crc shr 24).toByte()
        out.write(page)
        sequence++
        lacing.clear()
        body.reset()
        ended = false
        continued = nextContinued
    }

    companion object {
        private val TABLE = IntArray(256) { i ->
            var r = i shl 24
            repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
            r
        }

        /** Ogg's CRC-32 (polynomial 0x04C11DB7, not reflected, starting at 0). */
        fun crc(bytes: ByteArray): Int {
            var crc = 0
            for (b in bytes) crc = (crc shl 8) xor TABLE[((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
            return crc
        }
    }
}
