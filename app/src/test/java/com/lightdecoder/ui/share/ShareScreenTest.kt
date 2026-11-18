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

    @Before
    fun setUp() {
        env = TwoServicesEnv()
        env.seedConversation(ChatService.WHATSAPP, "111@s.whatsapp.net", "Nina")
        env.seedConversation(ChatService.WHATSAPP, "222@s.whatsapp.net", "Jay")
    }

    @After
    fun tearDown() = env.tearDown()

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
}
