package com.lightdecoder.imessage.direct

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.MediaCache
import com.lightdecoder.chat.ChatService
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The files a request from the main process may name: the app's own, never the library's state or anything outside. */
@RunWith(AndroidJUnit4::class)
class DirectPathsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val rules get() = DirectPaths.forApp(context)

    private fun allowed(path: String) = DirectPaths.isAllowed(path, rules)

    @Test
    fun theAppsOwnMediaAndOutboxAreAllowed() {
        assertTrue(allowed(MediaCache.outboxFile(context, "1b2c-3d", ChatService.IMESSAGE).path))
        assertTrue(allowed(MediaCache.fileFor(context, "media-1", "jpg", ChatService.IMESSAGE).path))
        assertTrue(allowed(File(context.cacheDir, "shared-in/x/photo.jpg").path))
    }

    @Test
    fun theLibrarysStateAndEverythingOutsideAreNot() {
        val state = File(context.filesDir, "imessage/state/keys.plist")
        assertFalse(allowed(state.path))
        assertFalse(allowed(File(context.filesDir, "imessage").path))
        assertFalse(allowed(File(context.filesDir, "imessage/lib/arm64-v8a/librust_bridge.so").path))
        // A way round by "..": read as the path really is.
        assertFalse(allowed(File(context.filesDir, "imessage-outbox/../imessage/state/keys.plist").path))
        assertFalse(allowed(File(context.filesDir, "../shared_prefs/x.xml").path))
        assertFalse(allowed(File(context.filesDir, "../databases/archive.db").path))
        assertFalse(allowed("/sdcard/Download/x.jpg"))
        assertFalse(allowed("/proc/self/environ"))
        assertFalse(allowed("relative/x.jpg"))
        assertFalse(allowed(""))
        assertFalse(allowed(context.filesDir.path))
        assertFalse(allowed(context.filesDir.path + "\u0000/x"))
        // A sibling whose name starts like the folder is not inside it.
        assertFalse(allowed(context.filesDir.path + "-evil/x.jpg"))
        assertTrue("imessage-media is not imessage/", allowed(File(context.filesDir, "imessage-media/x.jpg").path))
    }

    @Test
    fun aLinkOutOfTheAppsFoldersIsFollowed() {
        val outside = Files.createTempDirectory("ld-outside").toFile()
        try {
            val link = File(context.filesDir, "imessage-outbox/escape").apply { parentFile!!.mkdirs(); delete() }
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertFalse(allowed(File(link, "x.jpg").path))
            val toState = File(context.filesDir, "imessage-outbox/state-link").apply { delete() }
            File(context.filesDir, "imessage/state").mkdirs()
            Files.createSymbolicLink(toState.toPath(), File(context.filesDir, "imessage/state").toPath())
            assertFalse(allowed(File(toState, "keys.plist").path))
            link.delete()
            toState.delete()
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun aRefusedRequestSaysSo() {
        val e = org.junit.Assert.assertThrows(DirectEngine.ImessageError::class.java) {
            DirectPaths.require(context, "/sdcard/x.jpg", "This file could not be read.")
        }
        assertTrue(e.message == "This file could not be read.")
        DirectPaths.require(context, MediaCache.outboxFile(context, "abc", ChatService.IMESSAGE).path, "never")
    }
}
