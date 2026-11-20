package com.lightdecoder.imessage.direct

import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * A chat background's poster as Apple's file servers hand it over (a zip: the Watch's picture and
 * the poster, itself a zip), checked here before OpenBubbles' library reads any of it. The library
 * unpacks the files it wants whole into memory, the inner zip and its pictures too: a poster that
 * unpacks to far more than it says, or to a lot, or that holds a great many files, would take the
 * ":imessage" process down, and since Apple's journal hands the same event over after each
 * restart, every iMessage after it with it. Anyone can send a chat a background, so this is
 * checked before anything is unpacked by the library ([problem]).
 *
 * Only a plain zip is taken: one central directory, right before the end record (no zip64, no
 * bytes before the entries or after the end record besides its comment); each entry stored or
 * deflated, not encrypted, inside the archive and before the directory, unpacking (here, counted,
 * nothing kept) to exactly the size and checksum it declares; at most [MAX_ENTRIES] entries, and
 * [MAX_UNPACKED_BYTES] unpacked in all. The inner poster (`transcriptBackground/poster`, with or
 * without a leading slash, as the library reads it) is unpacked and checked the same way, with
 * at most [MAX_POSTER_ENTRIES] entries.
 *
 * Pure. Tested in ChatBackgroundsTest.
 */
object PosterArchive {

    /** The most entries the outer zip may have (Apple's has three: a folder, the Watch's picture, the poster). */
    const val MAX_ENTRIES = 64

    /** The most entries the inner poster may have (a photo poster: a dozen or so). */
    const val MAX_POSTER_ENTRIES = 512

    /** The most either zip may unpack to, all its entries together. */
    const val MAX_UNPACKED_BYTES = 64L * 1024 * 1024

    /** The names the library looks for the inner poster under. */
    val POSTER_NAMES = setOf("transcriptBackground/poster", "/transcriptBackground/poster")

    /** Why [zip] (the poster as downloaded) is not handed to the library, or null when it can be. */
    fun problem(zip: ByteArray): String? = try {
        val outer = Zip(zip, MAX_ENTRIES)
        outer.problem ?: run {
            val posters = outer.entries.filter { it.name in POSTER_NAMES }
            if (posters.isEmpty()) return@run "no poster in it"
            for (p in posters) {
                val inner = outer.unpack(p) ?: return@run "its poster could not be unpacked"
                Zip(inner, MAX_POSTER_ENTRIES).problem?.let { return@run "its poster: $it" }
            }
            null
        }
    } catch (e: RuntimeException) {
        // Anything the reading below did not expect: not a zip to hand over.
        "not a zip that can be read (${e::class.java.simpleName})"
    }

    /** One entry as the central directory has it. */
    private class Entry(
        val name: String,
        val method: Int,
        val crc: Long,
        val packed: Long,
        val unpacked: Long,
        /** Where its data starts, past its local header. */
        val dataStart: Long,
    )

    /** A zip's central directory, read and checked ([problem]: why it is not taken, else null). */
    private class Zip(private val b: ByteArray, maxEntries: Int) {
        val entries = ArrayList<Entry>()
        val problem: String? = read(maxEntries)

        private fun u16(at: Long): Int {
            require(at >= 0 && at + 2 <= b.size)
            val i = at.toInt()
            return (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        }

        private fun u32(at: Long): Long {
            require(at >= 0 && at + 4 <= b.size)
            return u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        }

        private fun read(maxEntries: Int): String? {
            if (b.size < EOCD_SIZE) return "too short for a zip"
            // The end record: the last one whose comment runs exactly to the end, as a zip is
            // written (the library takes the last one whose comment fits: the same one, as no
            // record may be inside its comment). One further back is an inner zip's, stored.
            val lowest = maxOf(0, b.size - EOCD_SIZE - MAX_COMMENT)
            val eocd = (b.size - EOCD_SIZE downTo lowest).firstOrNull { at ->
                u32(at.toLong()) == SIG_EOCD && at + EOCD_SIZE + u16(at + 20L) == b.size
            }?.toLong() ?: return "no end record"
            if ((eocd + 1..b.size - 4L).any { u32(it) == SIG_EOCD }) return "an end record in its comment"
            if (eocd >= 20 && u32(eocd - 20) == SIG_ZIP64_LOCATOR) return "zip64"
            if (u16(eocd + 4) != 0 || u16(eocd + 6) != 0) return "split over disks"
            val onDisk = u16(eocd + 8)
            val total = u16(eocd + 10)
            val dirSize = u32(eocd + 12)
            val dirStart = u32(eocd + 16)
            if (onDisk != total || total == 0xFFFF || dirSize == 0xFFFFFFFFL || dirStart == 0xFFFFFFFFL) return "zip64"
            if (total > maxEntries) return "$total entries"
            if (dirStart + dirSize != eocd) return "its directory is not right before its end record"
            var at = dirStart
            var unpackedTotal = 0L
            repeat(total) {
                if (at + CEN_SIZE > eocd || u32(at) != SIG_CEN) return "a broken directory"
                val flags = u16(at + 8)
                val method = u16(at + 10)
                val crc = u32(at + 16)
                val packed = u32(at + 20)
                val unpacked = u32(at + 24)
                val nameLen = u16(at + 28)
                val extraLen = u16(at + 30)
                val commentLen = u16(at + 32)
                val disk = u16(at + 34)
                val local = u32(at + 42)
                if (flags and (FLAG_ENCRYPTED or FLAG_STRONG_ENCRYPTION) != 0) return "an encrypted entry"
                if (method != STORED && method != DEFLATED) return "an entry packed with method $method"
                if (packed == 0xFFFFFFFFL || unpacked == 0xFFFFFFFFL || local == 0xFFFFFFFFL || disk != 0) return "zip64"
                if (method == STORED && packed != unpacked) return "a stored entry of two sizes"
                val nameEnd = at + CEN_SIZE + nameLen
                val next = nameEnd + extraLen + commentLen
                if (next > eocd) return "a broken directory"
                val name = String(b, (at + CEN_SIZE).toInt(), nameLen, Charsets.UTF_8)
                // Its data: past its local header, inside the archive, before the directory.
                if (local + LOC_SIZE > dirStart || u32(local) != SIG_LOC) return "an entry with no local header"
                val dataStart = local + LOC_SIZE + u16(local + 26) + u16(local + 28)
                if (dataStart + packed > dirStart) return "an entry outside the archive"
                unpackedTotal += unpacked
                if (unpackedTotal > MAX_UNPACKED_BYTES) return "more than ${MAX_UNPACKED_BYTES shr 20} MB unpacked"
                entries += Entry(name, method, crc, packed, unpacked, dataStart)
                at = next
            }
            if (at != eocd) return "a broken directory"
            // What each entry really unpacks to: exactly what it says (nothing kept).
            for (e in entries) if (!check(e)) return "an entry that does not unpack to what it says"
            return null
        }

        /** Whether [e] unpacks to exactly its declared size and checksum (counted, nothing kept). */
        private fun check(e: Entry): Boolean {
            val crc = CRC32()
            var count = 0L
            val ok = inflate(e) { chunk, n ->
                count += n
                if (count > e.unpacked) return@inflate false
                crc.update(chunk, 0, n)
                true
            }
            return ok && count == e.unpacked && crc.value == e.crc
        }

        /** [e]'s bytes, unpacked (it was checked to be at most its declared size), or null. */
        fun unpack(e: Entry): ByteArray? {
            if (e.unpacked > MAX_UNPACKED_BYTES) return null
            val out = ByteArray(e.unpacked.toInt())
            var at = 0
            val ok = inflate(e) { chunk, n ->
                if (at + n > out.size) return@inflate false
                System.arraycopy(chunk, 0, out, at, n)
                at += n
                true
            }
            return out.takeIf { ok && at == out.size }
        }

        /** Unpacks [e], handing each piece to [take] (false: stop, and false is returned). True once all of it came. */
        private inline fun inflate(e: Entry, take: (ByteArray, Int) -> Boolean): Boolean {
            val start = e.dataStart.toInt()
            val packed = e.packed.toInt()
            if (e.method == STORED) {
                var off = 0
                val chunk = ByteArray(CHUNK)
                while (off < packed) {
                    val n = minOf(CHUNK, packed - off)
                    System.arraycopy(b, start + off, chunk, 0, n)
                    if (!take(chunk, n)) return false
                    off += n
                }
                return true
            }
            val inflater = Inflater(true)
            try {
                inflater.setInput(b, start, packed)
                val chunk = ByteArray(CHUNK)
                var idle = 0
                while (!inflater.finished()) {
                    val n = try {
                        inflater.inflate(chunk)
                    } catch (x: DataFormatException) {
                        return false
                    }
                    if (n == 0) {
                        // Its end (nothing more to it: an empty entry, a folder's).
                        if (inflater.finished()) break
                        // More is needed than the entry has (it is cut short), or a dictionary: not a poster's.
                        if (inflater.needsInput() || inflater.needsDictionary() || ++idle > MAX_IDLE_INFLATES) return false
                        continue
                    }
                    idle = 0
                    if (!take(chunk, n)) return false
                }
                // Nothing of the entry's data left over.
                return inflater.remaining == 0
            } finally {
                inflater.end()
            }
        }
    }

    private const val SIG_EOCD = 0x06054b50L
    private const val SIG_CEN = 0x02014b50L
    private const val SIG_LOC = 0x04034b50L
    private const val SIG_ZIP64_LOCATOR = 0x07064b50L
    private const val EOCD_SIZE = 22
    private const val MAX_COMMENT = 0xFFFF
    private const val CEN_SIZE = 46
    private const val LOC_SIZE = 30
    private const val STORED = 0
    private const val DEFLATED = 8
    private const val FLAG_ENCRYPTED = 1
    private const val FLAG_STRONG_ENCRYPTION = 1 shl 6
    private const val CHUNK = 64 * 1024
    private const val MAX_IDLE_INFLATES = 16
}
