package com.lightdecoder.imessage.direct

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An imported iCloud attachment's download (B6.5) as the user's other downloads: stopped once the
 * file on disk passes its cap (what came deleted), heard from while it comes, and stoppable.
 */
class CloudDownloadsTest {

    private val dir: File = Files.createTempDirectory("ld-cloud").toFile()

    @Test
    fun oneThatGrowsPastItsCapIsStoppedAndDeletedWhileItComes() = runBlocking {
        val partial = File(dir, "a.part")
        var stopped = false
        val within = CloudDownloads.capped(partial, maxBytes = 1_000, onProgress = null, watchMs = 5) {
            try {
                // A sender's file far larger than it said, written a piece at a time.
                repeat(1_000) {
                    partial.appendBytes(ByteArray(100))
                    delay(2)
                }
            } catch (e: CancellationException) {
                stopped = true
                throw e
            }
        }
        assertFalse(within)
        assertTrue("stopped while it came, not once it was all on disk", stopped)
        assertFalse(partial.exists())
    }

    @Test
    fun oneWithinItsCapIsKeptAndHeardFromWhileItComes() = runBlocking {
        val partial = File(dir, "b.part")
        val heard = ArrayList<Long>()
        val within = CloudDownloads.capped(partial, maxBytes = 10_000, onProgress = { heard += it }, watchMs = 5, heartbeatMs = 20) {
            repeat(10) {
                partial.appendBytes(ByteArray(100))
                delay(15)
            }
        }
        assertTrue(within)
        assertTrue(partial.length() == 1_000L)
        assertTrue("heard from more than once: $heard", heard.size >= 3)
    }

    @Test
    fun theUserStoppingItStopsTheDownload() = runBlocking {
        val partial = File(dir, "c.part")
        var started = false
        var stopped = false
        val transfers = Transfers()
        val run = async {
            runCatching {
                transfers.run("t1") {
                    CloudDownloads.capped(partial, maxBytes = 10_000, onProgress = null, watchMs = 5) {
                        try {
                            started = true
                            delay(60_000)
                        } catch (e: CancellationException) {
                            stopped = true
                            throw e
                        }
                    }
                }
            }
        }
        // Stopped once the download is under way (a stop before it starts downloads nothing at all).
        while (!started || !transfers.isRunning("t1")) delay(5)
        assertTrue(transfers.cancel("t1"))
        // As any stopped transfer ends.
        assertTrue("${run.await()}", run.await().exceptionOrNull() is Transfers.Stopped)
        assertTrue(stopped)
    }
}
