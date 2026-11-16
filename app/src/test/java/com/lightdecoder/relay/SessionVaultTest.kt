package com.lightdecoder.relay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The session file format and its failure handling. The Android Keystore is
 * not available on the JVM, so a software AES-256 key stands in for it.
 */
@RunWith(AndroidJUnit4::class)
class SessionVaultTest {

    private lateinit var context: Context
    private lateinit var original: SessionVault.CipherProvider
    private val session = """{"tachyon_token":"dG9rZW4=","cookies":{"SID":"x"}}""".toByteArray()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        original = SessionVault.cipherProvider
        SessionVault.cipherProvider = SessionVault.CipherProvider.forKey(newKey())
        SessionVault.save(context, null)
    }

    @After
    fun tearDown() {
        SessionVault.save(context, null)
        SessionVault.cipherProvider = original
    }

    @Test
    fun roundTrip() {
        assertFalse(SessionVault.exists(context))
        assertNull(SessionVault.load(context))

        SessionVault.save(context, session)
        assertTrue(SessionVault.exists(context))
        assertArrayEquals(session, SessionVault.load(context))

        val stored = file().readBytes()
        assertEquals("version byte", 1, stored[0].toInt())
        assertEquals("version + IV + ciphertext + tag", 1 + 12 + session.size + 16, stored.size)
        assertFalse("plaintext never on disk", String(stored, Charsets.ISO_8859_1).contains("tachyon"))
        assertFalse("no temp file left", File(file().parentFile, "session.bin.tmp").exists())
    }

    @Test
    fun eachSaveUsesAFreshIv() {
        SessionVault.save(context, session)
        val first = file().readBytes().copyOfRange(1, 13)
        SessionVault.save(context, session)
        val second = file().readBytes().copyOfRange(1, 13)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(session, SessionVault.load(context))
    }

    @Test
    fun nullOrEmptyDeletes() {
        SessionVault.save(context, session)
        SessionVault.save(context, null)
        assertFalse(SessionVault.exists(context))
        SessionVault.save(context, session)
        SessionVault.save(context, ByteArray(0))
        assertFalse(file().exists())
    }

    @Test
    fun tamperedFileIsDeleted() {
        SessionVault.save(context, session)
        val bytes = file().readBytes()
        bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 1).toByte()
        file().writeBytes(bytes)
        assertNull(SessionVault.load(context))
        assertFalse(file().exists())
    }

    @Test
    fun unknownVersionOrTruncatedFileIsDeleted() {
        SessionVault.save(context, session)
        val bytes = file().readBytes()
        bytes[0] = 2
        file().writeBytes(bytes)
        assertNull(SessionVault.load(context))
        assertFalse(file().exists())

        file().apply { parentFile?.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(SessionVault.load(context))
        assertFalse(file().exists())
    }

    @Test
    fun lostKeyMeansNoSession() {
        SessionVault.save(context, session)
        SessionVault.cipherProvider = SessionVault.CipherProvider.forKey(newKey())
        assertNull(SessionVault.load(context))
        assertFalse(SessionVault.exists(context))
    }

    @Test
    fun keystoreFailureKeepsTheSession() {
        SessionVault.save(context, session)
        val working = SessionVault.cipherProvider
        SessionVault.cipherProvider = SessionVault.CipherProvider { throw java.security.ProviderException("keystore busy") }
        assertNull(SessionVault.load(context))
        assertTrue("a passing failure must not cost the pairing", file().exists())

        SessionVault.cipherProvider = working
        assertArrayEquals(session, SessionVault.load(context))
    }

    @Test
    fun fileTooShortForATagIsDeleted() {
        SessionVault.save(context, session)
        file().writeBytes(file().readBytes().copyOf(1 + 12 + 10))
        assertNull(SessionVault.load(context))
        assertFalse(file().exists())
    }

    private fun file() = File(File(context.filesDir, "relay"), "session.bin")

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}
