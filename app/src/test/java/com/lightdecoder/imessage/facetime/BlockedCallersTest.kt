package com.lightdecoder.imessage.facetime

import com.lightdecoder.chat.BlockList
import com.lightdecoder.imessage.direct.DirectWire
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The ":imessage" process's copy of the block list ([BlockedCallers], SPEC2 B3.1): a caller matches
 * by HMAC under the copy's salt, a new copy (new salt) replaces the old one whole, it is kept on disk
 * for a start before the main process is up, and the request is read strictly.
 */
@RunWith(AndroidJUnit4::class)
class BlockedCallersTest {

    private lateinit var dir: File
    private lateinit var main: BlockList

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("blocked-callers").toFile()
        main = BlockList(File(dir, "blocked.json"))
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** The main process's copy, as it crosses to the other process. */
    private fun sent(): DirectWire.BlockListRequest {
        val m = main.mirror()
        return DirectWire.BlockListRequest.fromJson(JSONObject(DirectWire.blockList(m.salt, m.hashes).toString()))!!
    }

    @Test
    fun aCallerMatchesByHmacUnderTheCopysSalt() {
        main.block("+15551234567")
        main.block("Mallory@Example.com")
        val copy = BlockedCallers(File(dir, "imessage/blocked-callers.json"))
        assertFalse("nothing was sent yet", copy.isBlocked("tel:+15551234567"))
        copy.replace(sent())
        assertTrue(copy.isBlocked("tel:+15551234567"))
        assertTrue(copy.isBlocked("mailto:MALLORY@example.com"))
        assertFalse(copy.isBlocked("tel:+15551234568"))
        assertFalse(copy.isBlocked("tel:+915551234567"))
        assertFalse("no address the library takes", copy.isBlocked("temp:guest"))
        assertFalse(copy.isBlocked(""))
        assertEquals(2, copy.size)
    }

    @Test
    fun aNewCopyWithANewSaltReplacesTheOldOneWhole() {
        main.block("+15551234567")
        val copy = BlockedCallers(File(dir, "imessage/blocked-callers.json"))
        val first = sent()
        copy.replace(first)
        assertTrue(copy.isBlocked("tel:+15551234567"))
        // Unblocked, and someone else blocked: the next copy has a new salt and only them.
        main.unblock("+15551234567")
        main.block("+447700900123")
        val second = sent()
        assertFalse(first.salt == second.salt)
        copy.replace(second)
        assertFalse(copy.isBlocked("tel:+15551234567"))
        assertTrue(copy.isBlocked("tel:+447700900123"))
        // The old hashes under the new salt match nothing: a hash is only good with its own salt.
        copy.replace(DirectWire.BlockListRequest(second.salt, first.hashes))
        assertFalse(copy.isBlocked("tel:+15551234567"))
        // Everyone unblocked: an empty copy.
        main.unblock("+447700900123")
        copy.replace(sent())
        assertEquals(0, copy.size)
        assertFalse(copy.isBlocked("tel:+447700900123"))
    }

    @Test
    fun theCopyIsKeptOnDiskForAStartBeforeTheMainProcessIsUp() {
        main.block("+15551234567")
        val file = File(dir, "imessage/blocked-callers.json")
        BlockedCallers(file).replace(sent())
        assertTrue(file.isFile)
        assertFalse("no address is written", file.readText().contains("5551234567"))
        assertTrue(BlockedCallers(file).isBlocked("tel:+15551234567"))
        file.writeText("not json")
        assertFalse(BlockedCallers(file).isBlocked("tel:+15551234567"))
    }

    @Test
    fun theRequestIsReadStrictly() {
        assertNull(DirectWire.BlockListRequest.fromJson(JSONObject()))
        assertNull(DirectWire.BlockListRequest.fromJson(JSONObject().put("salt", "XYZ")))
        assertNull(DirectWire.BlockListRequest.fromJson(JSONObject().put("salt", "ab".repeat(15))))
        val salt = "ab".repeat(16)
        val good = "0f".repeat(32)
        val r = DirectWire.BlockListRequest.fromJson(
            JSONObject().put("salt", salt).put("hashes", org.json.JSONArray(listOf(good, "nothex", "AB".repeat(32), 5, good))),
        )!!
        assertEquals(listOf(good), r.hashes)
        val many = DirectWire.BlockListRequest.fromJson(
            JSONObject().put("salt", salt).put("hashes", org.json.JSONArray((0 until 1500).map { "%064x".format(it) })),
        )!!
        assertEquals(DirectWire.MAX_BLOCKED, many.hashes.size)
    }
}
