package com.lightdecoder.inbox

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Inbox items gained a source and a digest; old indexes must still load, and digests dedupe. */
@RunWith(AndroidJUnit4::class)
class InboxStoreDedupeTest {

    private lateinit var context: Context
    private lateinit var store: InboxStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = InboxStore.get(context)
        store.clear()
    }

    @Test
    fun oldJsonWithoutSourceOrDigestStillLoads() {
        val old = JSONObject(
            """{"id":"abc","sender":"Sam","title":"Sam","appPackage":"com.google.android.apps.messaging",
               "timestampMs":1000,"mime":"image/heic","width":10,"height":20,"animated":false,"originalExt":"heic"}"""
        )
        val item = InboxItem.fromJson(old)
        assertEquals("abc", item.id)
        assertEquals(InboxItem.SOURCE_NOTIFICATION, item.source)
        assertEquals("", item.digest)
        assertEquals(10, item.width)
    }

    @Test
    fun newFieldsRoundTrip() {
        val item = item("x", digest = "0123456789abcdef0123456789abcdef", source = InboxItem.SOURCE_RELAY)
        assertEquals(item, InboxItem.fromJson(item.toJson()))
    }

    @Test
    fun sameDigestIsStoredOnce() {
        val preview = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val bytes = byteArrayOf(1, 2, 3)
        val digest = InboxStore.digestOf(bytes)
        assertTrue(store.add(item("one", digest), bytes, preview))
        assertTrue(store.containsDigest(digest))
        assertFalse(store.add(item("two", digest, InboxItem.SOURCE_RELAY), bytes, preview))
        assertTrue("the duplicate's id is remembered", store.contains("two"))
        assertEquals(listOf("one"), store.list().map { it.id })
    }

    @Test
    fun itemsWithoutDigestAreNotDeduped() {
        val preview = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        assertTrue(store.add(item("one", ""), byteArrayOf(1), preview))
        assertTrue(store.add(item("two", ""), byteArrayOf(1), preview))
        assertFalse(store.containsDigest(""))
        assertEquals(listOf("two", "one"), store.list().map { it.id })
        assertFalse("same id still refused", store.add(item("one", ""), byteArrayOf(1), preview))
    }

    @Test
    fun relayIdsIncludeThePhoneTheMessageCameFrom() {
        // Conversation, message and part ids are phone-local: another phone can reuse them.
        val first = InboxStore.relayIdFor("phone-1", "conv-1", "msg-1", "p1")
        assertEquals("stable", first, InboxStore.relayIdFor("phone-1", "conv-1", "msg-1", "p1"))
        assertNotEquals(first, InboxStore.relayIdFor("phone-2", "conv-1", "msg-1", "p1"))
        assertNotEquals(first, InboxStore.relayIdFor("", "conv-1", "msg-1", "p1"))
        assertNotEquals(first, InboxStore.relayIdFor("phone-1", "conv-1", "msg-1", "p2"))
        assertNotEquals(first, InboxStore.idFor(InboxItem.SOURCE_RELAY, "conv-1", "msg-1", 0, "p1"))

        // The second phone's picture (other bytes, same ids) is not taken for the first one's.
        val preview = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val one = byteArrayOf(1, 2, 3)
        val two = byteArrayOf(4, 5, 6)
        assertTrue(store.add(item(first, InboxStore.digestOf(one), InboxItem.SOURCE_RELAY), one, preview))
        val second = InboxStore.relayIdFor("phone-2", "conv-1", "msg-1", "p1")
        assertFalse(store.contains(second))
        assertTrue(store.add(item(second, InboxStore.digestOf(two), InboxItem.SOURCE_RELAY), two, preview))
        // The same picture under another phone's id is still kept once (digest).
        val third = InboxStore.relayIdFor("phone-3", "conv-1", "msg-1", "p1")
        assertFalse(store.add(item(third, InboxStore.digestOf(one), InboxItem.SOURCE_RELAY), one, preview))
        assertEquals(listOf(second, first), store.list().map { it.id })
    }

    private fun item(id: String, digest: String, source: String = InboxItem.SOURCE_NOTIFICATION) = InboxItem(
        id = id,
        sender = "Sam",
        title = "Sam",
        appPackage = "com.google.android.apps.messaging",
        timestampMs = 1_000,
        mime = "image/heic",
        width = 4,
        height = 4,
        animated = false,
        originalExt = "heic",
        source = source,
        digest = digest,
    )
}
