package com.lightdecoder.backup

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The encrypted envelope of a chat backup (a ".ldbackup" file).
 *
 *     header:  "LDBK" | format version (1 byte, 1) | KDF id (1 byte, 1 = PBKDF2-HMAC-SHA256)
 *              | iterations (int32) | salt length (1 byte) | salt | nonce prefix (8 bytes)
 *              | chunk size (int32)
 *     chunks:  final flag (1 byte, 0 or 1) | ciphertext length (int32) | AES-256-GCM ciphertext + tag
 *
 * All integers are big-endian. The key is PBKDF2-HMAC-SHA256 of the passphrase (UTF-8) with the
 * header's salt and iterations. Chunk i is encrypted under the nonce "nonce prefix | int32 i", with
 * the whole header, i and the final flag as associated data: a changed header, a changed, swapped,
 * dropped or repeated chunk, a lost end, or bytes after the final chunk all fail to decrypt.
 *
 * Nothing here logs or keeps the passphrase or the key beyond the streams' life.
 */
object BackupCipher {

    val MAGIC = byteArrayOf('L'.code.toByte(), 'D'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    const val FORMAT_VERSION = 1
    const val KDF_PBKDF2_SHA256 = 1

    /** What a new backup uses (OWASP's figure for PBKDF2-HMAC-SHA256). */
    const val ITERATIONS = 310_000

    /** Fewer than this is refused on reading, and so is more than [MAX_ITERATIONS] (it would take hours). */
    const val MIN_ITERATIONS = 100_000
    const val MAX_ITERATIONS = 20_000_000

    const val SALT_BYTES = 16
    const val NONCE_PREFIX_BYTES = 8
    const val CHUNK_SIZE = 1 shl 20
    private const val MIN_CHUNK = 1 shl 10
    private const val MAX_CHUNK = 8 shl 20
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val KEY_BITS = 256

    /** The KDF parameters a header holds. */
    data class Params(val iterations: Int, val salt: ByteArray, val noncePrefix: ByteArray, val chunkSize: Int) {
        init {
            require(noncePrefix.size == NONCE_PREFIX_BYTES)
            require(salt.size in 16..64)
        }

        fun headerBytes(): ByteArray {
            val buf = ByteBuffer.allocate(4 + 1 + 1 + 4 + 1 + salt.size + noncePrefix.size + 4)
            buf.put(MAGIC).put(FORMAT_VERSION.toByte()).put(KDF_PBKDF2_SHA256.toByte()).putInt(iterations)
                .put(salt.size.toByte()).put(salt).put(noncePrefix).putInt(chunkSize)
            return buf.array()
        }

        override fun equals(other: Any?) = other is Params && iterations == other.iterations &&
            salt.contentEquals(other.salt) && noncePrefix.contentEquals(other.noncePrefix) && chunkSize == other.chunkSize

        override fun hashCode() = iterations * 31 + salt.contentHashCode()
    }

    fun newParams(random: SecureRandom = SecureRandom(), iterations: Int = ITERATIONS, chunkSize: Int = CHUNK_SIZE) = Params(
        iterations = iterations,
        salt = ByteArray(SALT_BYTES).also(random::nextBytes),
        noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(random::nextBytes),
        chunkSize = chunkSize,
    )

    /**
     * Reads and checks a header from [input]. A file that is not a backup, or one of a later
     * format, fails with the [BackupException.Reason] saying so.
     */
    @Throws(IOException::class)
    fun readHeader(input: InputStream): Params {
        val data = DataInputStream(input)
        try {
            val magic = ByteArray(4).also(data::readFully)
            if (!magic.contentEquals(MAGIC)) throw BackupException(BackupException.Reason.NOT_A_BACKUP)
            val version = data.readUnsignedByte()
            if (version != FORMAT_VERSION) throw BackupException(BackupException.Reason.UNSUPPORTED_FORMAT, "format $version")
            val kdf = data.readUnsignedByte()
            if (kdf != KDF_PBKDF2_SHA256) throw BackupException(BackupException.Reason.UNSUPPORTED_FORMAT, "kdf $kdf")
            val iterations = data.readInt()
            if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw BackupException(BackupException.Reason.DAMAGED, "iterations")
            val saltLength = data.readUnsignedByte()
            if (saltLength !in 16..64) throw BackupException(BackupException.Reason.DAMAGED, "salt")
            val salt = ByteArray(saltLength).also(data::readFully)
            val prefix = ByteArray(NONCE_PREFIX_BYTES).also(data::readFully)
            val chunkSize = data.readInt()
            if (chunkSize !in MIN_CHUNK..MAX_CHUNK) throw BackupException(BackupException.Reason.DAMAGED, "chunk size")
            return Params(iterations, salt, prefix, chunkSize)
        } catch (e: EOFException) {
            throw BackupException(BackupException.Reason.NOT_A_BACKUP, "too short", e)
        }
    }

    /** The AES-256 key for [passphrase] under [params]. The caller clears [passphrase]; the key bytes here are cleared. */
    internal fun deriveKey(passphrase: CharArray, params: Params): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, params.salt, params.iterations, KEY_BITS)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try {
                return SecretKeySpec(raw, "AES")
            } finally {
                raw.fill(0)
            }
        } catch (e: GeneralSecurityException) {
            throw IOException("key derivation unavailable", e)
        } finally {
            spec.clearPassword()
        }
    }

    internal fun nonce(prefix: ByteArray, index: Int): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(index).array()

    internal fun aad(header: ByteArray, index: Int, last: Boolean): ByteArray =
        ByteBuffer.allocate(header.size + 5).put(header).putInt(index).put(if (last) 1 else 0).array()

    /**
     * Encrypts everything written to it into [out]: the header at once, then one chunk each
     * [Params.chunkSize] bytes, and the final chunk on [close] (which closes [out]). [abort]
     * stops without writing a final chunk, so what was written can never be read as complete.
     */
    class EncryptingOutputStream(
        private val out: OutputStream,
        passphrase: CharArray,
        private val params: Params = newParams(),
    ) : OutputStream() {
        private val header = params.headerBytes()
        private val key = deriveKey(passphrase, params)
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val buffer = ByteArray(params.chunkSize)
        private var filled = 0
        private var index = 0
        private var closed = false

        init {
            out.write(header)
        }

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "closed" }
            var o = off
            var left = len
            while (left > 0) {
                if (filled == buffer.size) flushChunk(last = false)
                val n = minOf(left, buffer.size - filled)
                System.arraycopy(b, o, buffer, filled, n)
                filled += n
                o += n
                left -= n
            }
        }

        private fun flushChunk(last: Boolean) {
            if (index == Int.MAX_VALUE) throw IOException("backup too large")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(params.noncePrefix, index)))
            cipher.updateAAD(aad(header, index, last))
            val sealed = cipher.doFinal(buffer, 0, filled)
            out.write(if (last) 1 else 0)
            out.write(ByteBuffer.allocate(4).putInt(sealed.size).array())
            out.write(sealed)
            buffer.fill(0, 0, filled)
            filled = 0
            index++
        }

        override fun flush() = out.flush()

        /** Writes the final chunk and closes [out]. */
        override fun close() {
            if (closed) return
            try {
                flushChunk(last = true)
                out.flush()
            } finally {
                closed = true
                buffer.fill(0)
                out.close()
            }
        }

        /** Closes [out] without a final chunk: the file is unreadable as a backup. */
        fun abort() {
            if (closed) return
            closed = true
            buffer.fill(0)
            runCatching { out.close() }
        }
    }

    /**
     * Decrypts a backup written by [EncryptingOutputStream]. Each chunk is authenticated before
     * any of it is returned. Reading past the end without a final chunk, bytes after it, or a chunk
     * that does not authenticate fails with a [BackupException]: [BackupException.Reason.WRONG_PASSPHRASE]
     * for the first chunk (a wrong passphrase looks like that), [BackupException.Reason.DAMAGED] after.
     */
    class DecryptingInputStream(input: InputStream, passphrase: CharArray) : InputStream() {
        private val data = DataInputStream(input)
        val params: Params = readHeader(data)
        private val header = params.headerBytes()
        private val key = deriveKey(passphrase, params)
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private var chunk = ByteArray(0)
        private var pos = 0
        private var index = 0

        /** Whether the final chunk was read and nothing followed it: the whole file is authentic. */
        var complete = false
            private set

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= chunk.size) {
                if (complete) return -1
                nextChunk()
            }
            val n = minOf(len, chunk.size - pos)
            System.arraycopy(chunk, pos, b, off, n)
            pos += n
            return n
        }

        private fun nextChunk() {
            val flag = data.read()
            if (flag == -1) throw BackupException(BackupException.Reason.DAMAGED, "the file ends too early")
            if (flag != 0 && flag != 1) throw damaged("chunk $index")
            val length = try {
                data.readInt()
            } catch (e: EOFException) {
                throw BackupException(BackupException.Reason.DAMAGED, "the file ends too early", e)
            }
            if (length < TAG_BYTES || length > params.chunkSize + TAG_BYTES) throw damaged("chunk $index")
            val sealed = ByteArray(length)
            try {
                data.readFully(sealed)
            } catch (e: EOFException) {
                throw BackupException(BackupException.Reason.DAMAGED, "the file ends too early", e)
            }
            val last = flag == 1
            val plain = try {
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(params.noncePrefix, index)))
                cipher.updateAAD(aad(header, index, last))
                cipher.doFinal(sealed)
            } catch (e: AEADBadTagException) {
                throw damaged("chunk $index", e)
            } catch (e: GeneralSecurityException) {
                throw damaged("chunk $index", e)
            }
            chunk.fill(0)
            chunk = plain
            pos = 0
            index++
            if (last) {
                if (data.read() != -1) throw BackupException(BackupException.Reason.DAMAGED, "data after the end")
                complete = true
            }
        }

        private fun damaged(what: String, cause: Throwable? = null) = BackupException(
            if (index == 0) BackupException.Reason.WRONG_PASSPHRASE else BackupException.Reason.DAMAGED, what, cause,
        )

        /** Reads (and authenticates) whatever is left, and fails unless the file ended properly. */
        fun drainAndVerify() {
            val skip = ByteArray(64 * 1024)
            while (read(skip, 0, skip.size) != -1) Unit
            if (!complete) throw BackupException(BackupException.Reason.DAMAGED, "the file ends too early")
        }

        override fun close() {
            chunk.fill(0)
            data.close()
        }
    }
}

/** Why a backup or restore failed; [reason] picks the words shown. Never carries the passphrase. */
class BackupException(val reason: Reason, detail: String? = null, cause: Throwable? = null) :
    IOException(detail?.let { "$reason: $it" } ?: reason.name, cause) {

    enum class Reason {
        /** Not a LightDecoder backup at all. */
        NOT_A_BACKUP,
        /** A backup of a later format than this app reads. */
        UNSUPPORTED_FORMAT,
        /** The first chunk does not decrypt: the passphrase is wrong (or that chunk was changed). */
        WRONG_PASSPHRASE,
        /** Changed, cut short or reordered. */
        DAMAGED,
        /** The chats in it are from a newer app version: restoring would need a downgrade. */
        NEWER_ARCHIVE,
        /** It decrypts but holds something a backup never does (unknown or unsafe names, too large). */
        INVALID_CONTENT,
        /** An archive on this phone could not be read for the backup (its database check failed). */
        LOCAL_ARCHIVE_DAMAGED,
        /** The archive was busy too long to be replaced, or another backup or restore is running. */
        BUSY,
        /** Unpacking the backup would leave the phone without free space. */
        NO_SPACE,
        /** Reading or writing a file failed. */
        IO,
    }
}
