package com.lightdecoder.ui.share

import android.content.Intent
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.R
import com.lightdecoder.chat.ChatService
import com.lightdecoder.chat.ShareShortcuts
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.TwoServicesEnv
import com.lightdecoder.ui.chat.ThreadActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/** Sharing from another app: pick a conversation, and what was shared is in its composer. */
@RunWith(AndroidJUnit4::class)
class ShareScreenTest {

    private lateinit var env: TwoServicesEnv

    private fun forgetFileProviderFolders() = runCatching {
        val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
    }

    @Before
    fun setUp() {
        forgetFileProviderFolders()
        env = TwoServicesEnv()
        env.seedConversation(ChatService.WHATSAPP, "111@s.whatsapp.net", "Nina")
        env.seedConversation(ChatService.WHATSAPP, "222@s.whatsapp.net", "Jay")
    }

    @After
    fun tearDown() {
        // FileProvider keeps the first test's folders for good; each test has its own (the copies are shared as its uris).
        forgetFileProviderFolders()
        ShareShortcuts.resetForTesting()
        androidx.core.content.pm.ShortcutManagerCompat.removeAllDynamicShortcuts(env.app)
        env.tearDown()
    }

    private val jpeg = byteArrayOf(-1, -40, -1, -32, 0, 16, 74, 70, 73, 70)

    /** [names] as another app's gallery shares them (several at once: SEND_MULTIPLE), with [text]. */
    private fun shareFiles(names: List<String>, text: String? = null, shortcut: String? = null) =
        Robolectric.buildActivity(
            ShareActivity::class.java,
            run {
                Robolectric.setupContentProvider(SharingAppFiles::class.java, SharingAppFiles.AUTHORITY)
                val uris = names.map { SharingAppFiles.add(env.app, it, jpeg) }
                (if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
                else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)))
                    .setType("image/jpeg")
                    .apply {
                        if (text != null) putExtra(Intent.EXTRA_TEXT, text)
                        if (shortcut != null) putExtra(Intent.EXTRA_SHORTCUT_ID, shortcut)
                    }
            },
        ).setup().also { env.settle() }

    private fun pick(activity: ShareActivity, position: Int) {
        val list = activity.findViewById<RecyclerView>(R.id.list)
        env.waitUntil("the conversations") { (list.adapter?.itemCount ?: 0) > position }
        list.measure(0, 0)
        list.layout(0, 0, 1000, 1000)
        list.findViewHolderForAdapterPosition(position)!!.itemView.performClick()
        env.waitUntil("the thread to be opened") { activity.isFinishing }
    }

    private fun shareText(text: String, shortcut: String? = null) =
        Robolectric.buildActivity(
            ShareActivity::class.java,
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                .apply { if (shortcut != null) putExtra(Intent.EXTRA_SHORTCUT_ID, shortcut) },
        ).setup().also { env.settle() }

    @Test
    fun aPickedConversationOpensWithTheSharedTextToSend() {
        val controller = shareText("look at this")
        val activity = controller.get()
        val list = activity.findViewById<RecyclerView>(R.id.list)
        env.waitUntil("the conversations") { list.adapter?.itemCount == 2 }
        activity.findViewById<EditText>(R.id.query).setText("nin")
        env.waitUntil("the filter") { list.adapter?.itemCount == 1 }
        list.measure(0, 0)
        list.layout(0, 0, 1000, 1000)
        list.findViewHolderForAdapterPosition(0)!!.itemView.performClick()
        env.waitUntil("the thread to be opened") { activity.isFinishing }

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(ThreadActivity::class.java.name, started.component?.className)
        assertEquals("111@s.whatsapp.net", started.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID))
        assertEquals(ChatService.WHATSAPP, RelayIntents.serviceOf(started))
        assertEquals("look at this", started.getStringExtra(RelayIntents.EXTRA_SHARED_TEXT))
        // Nothing was sent: the user sends it from the conversation.
        assertTrue(env.wa.texts.isEmpty())

        val thread = Robolectric.buildActivity(ThreadActivity::class.java, started.setFlags(0)).setup()
        env.settle()
        assertEquals("look at this", thread.get().findViewById<EditText>(R.id.input).text.toString())
        assertNull(thread.get().intent.getStringExtra(RelayIntents.EXTRA_SHARED_TEXT))
    }

    @Test
    fun aConversationPickedInTheShareSheetSkipsTheList() {
        val activity = shareText("hi", ShareShortcuts.id(ChatService.WHATSAPP, "222@s.whatsapp.net")).get()
        env.waitUntil("the thread to be opened") { activity.isFinishing }
        val started = shadowOf(activity).nextStartedActivity
        assertEquals("222@s.whatsapp.net", started.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID))
        assertEquals("hi", started.getStringExtra(RelayIntents.EXTRA_SHARED_TEXT))
    }

    @Test
    fun withNothingLinkedItSaysSo() {
        env.wa.paired = false
        val activity = shareText("hi").get()
        val empty = activity.findViewById<TextView>(R.id.empty)
        assertTrue(empty.isShown)
        assertEquals(activity.getString(R.string.share_not_linked), empty.text.toString())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun anIntentWithNothingToSendSaysSo() {
        val activity = Robolectric.buildActivity(ShareActivity::class.java, Intent(Intent.ACTION_SEND).setType("text/plain")).setup().get()
        assertEquals(activity.getString(R.string.share_nothing), activity.findViewById<TextView>(R.id.empty).text.toString())
    }

    @Test
    fun picturesSharedTogetherAreAllInTheComposerWithTheTextAndNothingIsSent() {
        val activity = shareFiles(listOf("IMG_1.jpg", "IMG_2.jpg", "IMG_3.jpg"), text = "From the trip").get()
        pick(activity, 0)
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(ThreadActivity::class.java.name, started.component?.className)
        // In LightDecoder's own task, not the sharing app's.
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val streams = started.getStringArrayListExtra(RelayIntents.EXTRA_SHARED_STREAMS)!!
        assertEquals(3, streams.size)
        // Copies of the app's own, readable once the sharing app's grant is gone.
        assertTrue(streams.all { android.net.Uri.parse(it).authority == "${env.app.packageName}.fileprovider" })
        streams.forEach { assertTrue(env.app.contentResolver.openInputStream(android.net.Uri.parse(it))!!.use { s -> s.readBytes() }.contentEquals(jpeg)) }

        val thread = Robolectric.buildActivity(ThreadActivity::class.java, started.setFlags(0)).setup()
        env.settle()
        val screen = thread.get()
        assertEquals("From the trip", screen.findViewById<EditText>(R.id.input).text.toString())
        assertTrue(screen.findViewById<android.view.View>(R.id.attachmentBar).isShown)
        assertEquals(
            screen.resources.getQuantityString(R.plurals.chat_attachments, 3, 3),
            screen.findViewById<TextView>(R.id.attachmentName).text.toString(),
        )
        assertTrue("ready to send", screen.findViewById<android.view.View>(R.id.send).isEnabled)
        // Nothing went: the user sends it from there.
        assertTrue(env.wa.calls.none { it.startsWith("send") })
        thread.pause().stop().destroy()
    }

    @Test
    fun aPictureSharedStraightToAConversationInTheShareSheetIsInItsComposer() {
        val activity = shareFiles(listOf("IMG_4.jpg"), shortcut = ShareShortcuts.id(ChatService.WHATSAPP, "222@s.whatsapp.net")).get()
        env.waitUntil("the thread to be opened") { activity.isFinishing }
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(org.robolectric.shadows.ShadowToast.getTextOfLatestToast(), ThreadActivity::class.java.name, started?.component?.className)
        assertEquals("222@s.whatsapp.net", started.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID))
        assertEquals(1, started.getStringArrayListExtra(RelayIntents.EXTRA_SHARED_STREAMS)!!.size)
        assertTrue(env.wa.calls.none { it.startsWith("send") })
    }

    @Test
    fun theShareSheetOffersTheNewestConversationsInTheListsOrderAndNotDeletedOnes() {
        env.seedConversation(ChatService.WHATSAPP, "333@s.whatsapp.net", "Kai", lastTs = 5_000)
        env.seedConversation(ChatService.WHATSAPP, "444@s.whatsapp.net", "Lee", lastTs = 3_000)
        for (i in 1..9) env.seedConversation(ChatService.WHATSAPP, "9$i@s.whatsapp.net", "Old $i", lastTs = 100L + i)
        fun offered() = androidx.core.content.pm.ShortcutManagerCompat.getDynamicShortcuts(env.app).sortedBy { it.rank }.map { it.shortLabel.toString() }
        val scenario = androidx.test.core.app.ActivityScenario.launch<com.lightdecoder.ui.chat.ConversationsActivity>(
            RelayIntents.conversations(env.app, ChatService.WHATSAPP).setFlags(0),
        )
        scenario.use {
            env.waitUntil("the newest offered") { offered().size == ShareShortcuts.MAX_PUBLISHED }
            // As the list shows them: the newest first.
            assertEquals(listOf("Kai", "Lee"), offered().take(2))
            val shown = mutableListOf<String>()
            scenario.onActivity { a ->
                shown += (a.findViewById<RecyclerView>(R.id.list).adapter as com.lightdecoder.ui.chat.ConversationsAdapter).currentList.map { it.title }
            }
            assertEquals(shown.take(ShareShortcuts.MAX_PUBLISHED), offered())
            // A new message moves its conversation up; a deleted one is offered no more.
            env.seedIncoming(ChatService.WHATSAPP, "m1", "96@s.whatsapp.net", "Hi again", ts = 9_000)
            env.waitUntil("the share sheet follows the list") { offered().first() == "Old 6" }
            env.waRepo.deleteConversationLocal("333@s.whatsapp.net")
            env.waitUntil("its shortcut taken back") { "Kai" !in offered() }
            assertEquals(ShareShortcuts.MAX_PUBLISHED, offered().size)
        }
    }
}
