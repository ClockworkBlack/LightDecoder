package com.lightdecoder.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.random.Random

/** The encrypted envelope alone: what decrypts, and every way a changed file must fail. */
class BackupCipherTest {

    private val pass = "correct horse battery".toCharArray()
    private val params get() = BackupCipher.newParams(iterations = BackupCipher.MIN_ITERATIONS, chunkSize = 1024)

    private fun seal(plain: ByteArray, p: BackupCipher.Params = params, passphrase: CharArray = pass): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCipher.EncryptingOutputStream(out, passphrase.copyOf(), p).use { it.write(plain) }
        return out.toByteArray()
    }

    private fun open(sealed: ByteArray, passphrase: CharArray = pass): ByteArray {
        val input = BackupCipher.DecryptingInputStream(ByteArrayInputStream(sealed), passphrase.copyOf())
        val bytes = input.readBytes()
        input.drainAndVerify()
        assertTrue(input.complete)
        return bytes
    }

    private fun expectFailure(reason: BackupException.Reason, block: () -> Unit) {
        try {
            block()
            fail("expected $reason")
        } catch (e: BackupException) {
            assertEquals(reason, e.reason)
        }
    }

    /** Where each chunk record starts (after the header). */
    private fun records(sealed: ByteArray): List<IntRange> {
        val headerLength = BackupCipher.readHeader(ByteArrayInputStream(sealed)).headerBytes().size
        val out = ArrayList<IntRange>()
        var at = headerLength
        while (at < sealed.size) {
            val length = ByteBuffer.wrap(sealed, at + 1, 4).int
            out += at until at + 5 + length
            at += 5 + length
        }
        return out
    }

    @Test
    fun roundTripsAcrossChunks() {
        for (size in listOf(0, 1, 1023, 1024, 1025, 5000)) {
            val plain = Random(size).nextBytes(size)
            assertArrayEquals("size $size", plain, open(seal(plain)))
        }
    }

    @Test
    fun headerCarriesTheKdfParameters() {
        val p = BackupCipher.newParams(iterations = 312_345, chunkSize = 4096)
        val sealed = seal(ByteArray(10), p)
        assertArrayEquals(BackupCipher.MAGIC, sealed.copyOfRange(0, 4))
        val read = BackupCipher.readHeader(ByteArrayInputStream(sealed))
        assertEquals(312_345, read.iterations)
        assertEquals(4096, read.chunkSize)
        assertArrayEquals(p.salt, read.salt)
        assertEquals(16, read.salt.size)
        assertTrue(BackupCipher.ITERATIONS >= 310_000)
        assertEquals(BackupCipher.ITERATIONS, BackupCipher.newParams().iterations)
    }

    @Test
    fun wrongPassphraseFails() {
        val sealed = seal(Random(1).nextBytes(3000))
        expectFailure(BackupException.Reason.WRONG_PASSPHRASE) { open(sealed, "not the passphrase".toCharArray()) }
    }

    @Test
    fun aChangedByteFails() {
        val sealed = seal(Random(2).nextBytes(3000))
        val last = records(sealed).last()
        val changed = sealed.copyOf().also { it[last.last - 3] = (it[last.last - 3].toInt() xor 1).toByte() }
        expectFailure(BackupException.Reason.DAMAGED) { open(changed) }
        // A changed header (more iterations) changes every chunk's associated data.
        val header = sealed.copyOf().also { it[9] = (it[9].toInt() xor 1).toByte() }
        try {
            open(header)
            fail("a changed header decrypted")
        } catch (e: BackupException) {
            assertTrue(e.reason == BackupException.Reason.WRONG_PASSPHRASE || e.reason == BackupException.Reason.DAMAGED)
        }
    }

    @Test
    fun aCutShortFileFails() {
        val sealed = seal(Random(3).nextBytes(3000))
        val records = records(sealed)
        // Without the final chunk, at a chunk boundary.
        expectFailure(BackupException.Reason.DAMAGED) { open(sealed.copyOfRange(0, records.last().first)) }
        // In the middle of a chunk.
        expectFailure(BackupException.Reason.DAMAGED) { open(sealed.copyOfRange(0, sealed.size - 7)) }
        // A non-final chunk claiming to be the last.
        val flagged = sealed.copyOfRange(0, records[1].last + 1).also { it[records[1].first] = 1 }
        expectFailure(BackupException.Reason.DAMAGED) { open(flagged) }
    }

    @Test
    fun reorderedChunksFail() {
        val sealed = seal(Random(4).nextBytes(4000))
        val r = records(sealed)
        assertTrue(r.size >= 4)
        val swapped = ByteArrayOutputStream().apply {
            write(sealed, 0, r[0].first)
            write(sealed.copyOfRange(r[0].first, r[0].last + 1))
            write(sealed.copyOfRange(r[2].first, r[2].last + 1))
            write(sealed.copyOfRange(r[1].first, r[1].last + 1))
            for (i in 3 until r.size) write(sealed.copyOfRange(r[i].first, r[i].last + 1))
        }.toByteArray()
        expectFailure(BackupException.Reason.DAMAGED) { open(swapped) }
        // A chunk repeated.
        val repeated = ByteArrayOutputStream().apply {
            write(sealed, 0, r[1].last + 1)
            write(sealed.copyOfRange(r[1].first, r[1].last + 1))
            write(sealed, r[2].first, sealed.size - r[2].first)
        }.toByteArray()
        expectFailure(BackupException.Reason.DAMAGED) { open(repeated) }
    }

    @Test
    fun bytesAfterTheEndFail() {
        val sealed = seal(Random(5).nextBytes(100))
        expectFailure(BackupException.Reason.DAMAGED) { open(sealed + byteArrayOf(0)) }
    }

    @Test
    fun otherFilesAreNotBackups() {
        expectFailure(BackupException.Reason.NOT_A_BACKUP) { open("PK\u0003\u0004 a zip".toByteArray()) }
        expectFailure(BackupException.Reason.NOT_A_BACKUP) { open(byteArrayOf(1, 2)) }
        val later = seal(ByteArray(5)).also { it[4] = 2 }
        expectFailure(BackupException.Reason.UNSUPPORTED_FORMAT) { open(later) }
    }

    @Test
    fun abortedStreamIsNeverComplete() {
        val out = ByteArrayOutputStream()
        val stream = BackupCipher.EncryptingOutputStream(out, pass.copyOf(), params)
        stream.write(Random(6).nextBytes(5000))
        stream.abort()
        expectFailure(BackupException.Reason.DAMAGED) { open(out.toByteArray()) }
    }

    @Test
    fun passphraseStrengthIsOnlyAHint() {
        assertEquals(Passphrase.Strength.TOO_SHORT, Passphrase.strength("short".toCharArray()))
        assertEquals(Passphrase.Strength.WEAK, Passphrase.strength("abcdefgh".toCharArray()))
        assertEquals(Passphrase.Strength.STRONG, Passphrase.strength("Long passphrase 2026!".toCharArray()))
    }
}
