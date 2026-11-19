package com.lightdecoder.ui.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.chat.ChatService
import com.lightdecoder.chat.ShareShortcuts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SharedContentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun sharedTextIsTaken() {
        val content = SharedContent.from(context, Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, " https://example.com/a "))
        assertEquals("https://example.com/a", content.text)
        assertTrue(content.streams.isEmpty())
    }

    @Test
    fun anotherAppsPictureIsTaken() {
        val uri = Uri.parse("content://media/external/images/1")
        val content = SharedContent.from(context, Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri))
        assertEquals(listOf(uri), content.streams)
    }

    @Test
    fun filePathsAndTheAppsOwnFilesAreNotTaken() {
        val own = Uri.parse("content://${context.packageName}.fileprovider/relay-media/x.jpg")
        val path = Uri.fromFile(File(context.filesDir, "relay-media/x.jpg"))
        val other = Uri.parse("content://media/external/images/2")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(own, path, other))
        assertEquals(listOf(other), SharedContent.from(context, intent).streams)
    }

    @Test
    fun onlyShareIntentsAreRead() {
        val intent = Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_TEXT, "x")
        assertTrue(SharedContent.from(context, intent).isEmpty)
        assertTrue(SharedContent.from(context, null).isEmpty)
    }

    @Test
    fun noMoreThanTheLimitAtOnce() {
        val many = ArrayList((1..SharedContent.MAX_STREAMS + 5).map { Uri.parse("content://media/external/images/$it") })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, many)
        assertEquals(SharedContent.MAX_STREAMS, SharedContent.from(context, intent).streams.size)
    }

    @Test
    fun aCopysNameHasNoFoldersInIt() {
        val name = SharedContent.fileName("../../databases/archive.db", null)
        assertFalse(name.contains('/'))
        assertFalse(name.startsWith("."))
        assertEquals("shared", SharedContent.fileName(null, null))
        assertEquals("shared", SharedContent.fileName("..", null))
    }

    @Test
    fun oldCopiesArePurgedAndNewOnesKept() {
        val root = File(context.cacheDir, "purge-test").apply { deleteRecursively(); mkdirs() }
        val now = System.currentTimeMillis()
        val old = File(root, "old").apply { mkdirs(); setLastModified(now - 25 * 60 * 60 * 1000L) }
        val fresh = File(root, "fresh").apply { mkdirs() }
        SharedContent.purge(root, now)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun shortcutIdsNameTheirConversation() {
        val id = ShareShortcuts.id(ChatService.WHATSAPP, "123:45@s.whatsapp.net")
        assertEquals(ChatService.WHATSAPP to "123:45@s.whatsapp.net", ShareShortcuts.parse(id))
        assertNull(ShareShortcuts.parse(null))
        assertNull(ShareShortcuts.parse("chat:nosuchservice:1"))
        assertNull(ShareShortcuts.parse("chat:${ChatService.MESSAGES.key}:"))
        assertNull(ShareShortcuts.parse("something else"))
    }
}
