package com.lightdecoder.chat

import com.lightdecoder.imessage.IMessageAddress
import java.io.File
import java.nio.file.Files
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The user's own block list ([BlockList], SPEC2 B3.1): whole-address matching (a number written
 * nationally or internationally is one person; two numbers that only end the same are two), email
 * addresses in any case, the user's own addresses refused, at most 1,000, kept across a restart,
 * and a salted copy that names nobody.
 */
@RunWith(AndroidJUnit4::class)
class BlockListTest {

    private lateinit var dir: File
    private val own = mutableListOf("tel:+15550000001", "mailto:me@example.com")

    @Before
    fun setUp() {
        IMessageAddress.setRegionForTesting("US")
        dir = Files.createTempDirectory("blocklist").toFile()
    }

    @After
    fun tearDown() {
        IMessageAddress.resetForTesting()
        dir.deleteRecursively()
    }

    private fun list() = BlockList(File(dir, "blocked.json")) { own }

    @Test
    fun aNumberIsBlockedHoweverItIsWrittenAndNeverOneThatOnlyEndsTheSame() {
        val list = list()
        assertEquals(BlockList.Added.ADDED, list.block("(555) 123-4567"))
        assertEquals(listOf("+15551234567"), list.entries.value)
        assertTrue(list.isBlocked("+1 555 123 4567"))
        assertTrue(list.isBlocked("tel:+15551234567"))
        assertTrue(list.isBlocked("15551234567"))
        assertTrue(list.isBlocked("555-123-4567"))
        // Another country with the same last ten digits is someone else.
        assertFalse(list.isBlocked("+915551234567"))
        assertFalse(list.isBlocked("+15551234568"))
        assertFalse(list.isBlocked(""))
        assertFalse(list.isBlocked(null))
        assertEquals(BlockList.Added.ALREADY, list.block("+1 (555) 123-4567"))
    }

    @Test
    fun anEmailAddressIsBlockedInAnyCase() {
        val list = list()
        assertEquals(BlockList.Added.ADDED, list.block("Mallory@Example.COM"))
        assertEquals(listOf("mallory@example.com"), list.entries.value)
        assertTrue(list.isBlocked("mailto:MALLORY@example.com"))
        assertTrue(list.isBlocked("mallory@example.com"))
        assertFalse(list.isBlocked("mallory@example.org"))
        assertTrue(list.unblock("MAILTO:mallory@EXAMPLE.com"))
        assertFalse(list.isBlocked("mallory@example.com"))
        assertFalse(list.unblock("mallory@example.com"))
    }

    @Test
    fun theUsersOwnAddressesAreNeverBlocked() {
        val list = list()
        assertEquals(BlockList.Added.OWN, list.block("555-000-0001"))
        assertEquals(BlockList.Added.OWN, list.block("ME@example.com"))
        assertTrue(list.entries.value.isEmpty())
        assertEquals(BlockList.Added.INVALID, list.block("not an address"))
        assertEquals(BlockList.Added.INVALID, list.block("12"))
    }

    @Test
    fun atMostAThousandAreKept() {
        val list = list()
        for (i in 0 until BlockList.MAX_ENTRIES) assertEquals(BlockList.Added.ADDED, list.block("+4420%08d".format(i)))
        assertEquals(BlockList.MAX_ENTRIES, list.entries.value.size)
        assertEquals(BlockList.Added.FULL, list.block("+33612345678"))
        assertFalse(list.isBlocked("+33612345678"))
        // A file grown larger by hand is read up to the cap.
        File(dir, "blocked.json").writeText(org.json.JSONArray((0 until 1500).map { "+4430%08d".format(it) }).toString())
        assertEquals(BlockList.MAX_ENTRIES, list().entries.value.size)
    }

    @Test
    fun theListIsKeptAcrossARestartAndAnUnreadableFileIsAnEmptyList() {
        val first = list()
        first.block("+447700900123")
        first.block("mallory@example.com")
        val again = list()
        assertEquals(listOf("+447700900123", "mallory@example.com"), again.entries.value)
        assertTrue(again.isBlocked("+44 7700 900123"))
        again.unblock("+447700900123")
        assertEquals(listOf("mallory@example.com"), list().entries.value)
        File(dir, "blocked.json").writeText("{ not json")
        assertTrue(list().entries.value.isEmpty())
    }

    @Test
    fun theCopyForTheIMessageProcessNamesNobodyAndHasANewSaltEachTime() {
        val list = list()
        list.block("+15551234567")
        list.block("mallory@example.com")
        val a = list.mirror()
        val b = list.mirror()
        assertEquals(32, a.salt.length)
        assertNotEquals(a.salt, b.salt)
        assertNotEquals(a.hashes, b.hashes)
        assertEquals(2, a.hashes.size)
        assertTrue(a.hashes.all { it.matches(Regex("^[0-9a-f]{64}$")) })
        assertFalse(a.hashes.joinToString().contains("5551234567"))
        assertFalse(a.hashes.joinToString().contains("mallory"))
    }
}
