package com.lightdecoder.imessage.direct

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** This phone's registered iMessage user, kept encrypted where the main process never looks. */
@RunWith(AndroidJUnit4::class)
class PhoneUserStoreTest {

    private val dir = Files.createTempDirectory("ld-phone-user").toFile()
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val file = AccountFiles.phoneUserFile(dir)
    private val store = PhoneUserStore(file, AccountCiphers.forKey(key))

    private val user = "<?xml version=\"1.0\"?><plist><dict><key>user_id</key><string>P:+15551234567</string></dict></plist>"

    @Test
    fun itComesBackAsItWasAndIsNotReadableWithoutTheKey() {
        assertNull(store.load())
        store.save(user)
        assertEquals(user, store.load())
        val bytes = file.readBytes()
        assertFalse("encrypted", String(bytes, Charsets.ISO_8859_1).contains("+15551234567"))
        assertEquals(1, bytes[0].toInt())
        // Saved again: a new IV each time.
        store.save(user)
        assertFalse(bytes.contentEquals(file.readBytes()))
        assertEquals(user, store.load())
        store.clear()
        assertFalse(file.exists())
    }

    @Test
    fun itIsUnderTheIMessageFolderTheMainProcessNeverNames() {
        assertEquals(File(dir, "imessage/phone-user.enc"), file)
        assertTrue(file.path.startsWith(File(dir, "imessage").path + File.separator))
        assertEquals("outside the library's own key names", "ld-phone-user", PhoneUserStore.KEY_ALIAS)
        assertFalse(PhoneUserStore.KEY_ALIAS.startsWith(DirectKeystore.PREFIX))
    }

    @Test
    fun aDamagedFileOrAnotherKeysReadsAsNone() {
        store.save(user)
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertNull(store.load())
        assertFalse("a damaged one is removed", file.exists())

        store.save(user)
        val other = PhoneUserStore(file, AccountCiphers.forKey(SecretKeySpec(ByteArray(32) { 7 }, "AES")))
        assertNull(other.load())

        file.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(store.load())
        file.writeText("not sealed at all, but long enough to look like something")
        assertNull(store.load())
    }
}
