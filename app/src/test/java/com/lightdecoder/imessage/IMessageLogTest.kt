package com.lightdecoder.imessage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The iMessage log: chats named by a tag keyed with this install's secret, and no numbers or addresses in it. */
@RunWith(AndroidJUnit4::class)
class IMessageLogTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @org.junit.Before
    fun setUp() {
        // This test's own app storage: the key is read (or made) there.
        IMessageLog.resetKeyForTesting()
        IMessageLog.init(context)
    }

    @After
    fun tearDown() {
        IMessageLog.clear(context)
        IMessageLog.resetKeyForTesting()
    }

    @Test
    fun aChatsTagIsShortStableAndKeyed() {
        val guid = "5D2B5A4C-1F0E-4C3B-9A7D-2E6F1B0C9D11"
        val tag = IMessageLog.chatRef(guid)
        assertTrue(tag, Regex("chat#[0-9a-f]{6}").matches(tag))
        assertEquals("the same chat, the same tag", tag, IMessageLog.chatRef(guid))
        assertNotEquals(tag, IMessageLog.chatRef("8A1F3E2D-6C4B-4D5A-9E8F-7B6A5C4D3E2F"))
        // Not the old unkeyed hash, which anyone could work out from a chat id.
        val unkeyed = MessageDigest.getInstance("SHA-256").digest(("ld-chat:$guid").toByteArray())
            .take(3).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        assertNotEquals("chat#$unkeyed", tag)
        // The key is this install's, kept in its no-backup storage: the tag follows it.
        val key = File(context.noBackupFilesDir, IMessageLog.KEY_FILE)
        assertEquals(32L, key.length())
        IMessageLog.resetKeyForTesting()
        assertEquals("read back, the same", tag, IMessageLog.chatRef(guid))
        key.writeBytes(ByteArray(32) { 7 })
        IMessageLog.resetKeyForTesting()
        assertNotEquals("another install's key, another tag", tag, IMessageLog.chatRef(guid))
    }

    @Test
    fun theKeyIsMadeOnceAndADamagedOneIsReplaced() {
        val dir = File(context.cacheDir, "log-key-test").apply { deleteRecursively() }
        val first = IMessageLog.loadKey(dir)
        assertNotNull(first)
        assertEquals(32, first!!.size)
        assertArrayEquals("made once", first, IMessageLog.loadKey(dir))
        File(dir, IMessageLog.KEY_FILE).writeBytes(byteArrayOf(1, 2, 3))
        val second = IMessageLog.loadKey(dir)!!
        assertEquals(32, second.size)
        assertFalse(second.contentEquals(first))
        assertFalse("nothing left lying around", File(dir, IMessageLog.KEY_FILE + ".tmp").exists())
        dir.deleteRecursively()
    }

    @Test
    fun numbersAndAddressesNeverReachTheLog() {
        IMessageLog.clear(context)
        IMessageLog.add(context, "${IMessageLog.chatRef("x")}: library send FAILED: unknown handle tel:+15551234567 (jane@icloud.com)")
        val line = IMessageLog.read(context).first()
        assertFalse(line, line.contains("5551234567") || line.contains("jane@icloud.com"))
        assertTrue(line, line.endsWith("library send FAILED: unknown handle tel:<number> (<email>)"))
        assertTrue(line, line.contains(IMessageLog.chatRef("x")))
    }
}
