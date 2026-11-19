package com.lightdecoder.ui.messenger

import android.app.Activity
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.tabs.TabLayout
import com.lightdecoder.R
import com.lightdecoder.messenger.CapturedConversation
import com.lightdecoder.messenger.CapturedMessage
import com.lightdecoder.messenger.MessengerCapture
import com.lightdecoder.messenger.MessengerNotificationListener
import com.lightdecoder.messenger.MessengerPrefs
import com.lightdecoder.messenger.MessengerReplies
import com.lightdecoder.messenger.MessengerStore
import com.lightdecoder.messenger.MessengerTestNotifications
import com.lightdecoder.messenger.MessengerTestNotifications.Line
import com.lightdecoder.messenger.MessengerTestNotifications.chat
import com.lightdecoder.messenger.MessengerTestNotifications.person
import com.lightdecoder.ui.chat.ConversationsActivity
import com.lightdecoder.ui.discord.DiscordActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The Messenger tab's screens: the inbox, one conversation, and the way to Facebook's page. */
@RunWith(AndroidJUnit4::class)
class MessengerScreensTest {

    private lateinit var context: Context
    private lateinit var store: MessengerStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        MessengerStore.resetForTesting()
        store = MessengerStore.get(context)
        store.clear()
        MessengerReplies.clear()
        MessengerPrefs.setPaused(context, false)
    }

    @After
    fun tearDown() {
        MessengerReplies.clear()
        MessengerStore.resetForTesting()
    }

    private fun grantAccess() {
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationListenerAccessGranted(ComponentName(context, MessengerNotificationListener::class.java), true)
    }

    private fun seed(key: String, title: String, text: String, ts: Long = 1_000) {
        store.record(
            CapturedConversation(key, title, false, listOf(CapturedMessage(title, false, text, MessengerCapture.KIND_TEXT, ts)))
        )
    }

    private fun <A : Activity> ActivityScenario<A>.waitFor(what: String, timeoutMs: Long = 10_000, condition: (A) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var ok = false
        while (!ok && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            onActivity { ok = condition(it) }
            if (!ok) Thread.sleep(20)
        }
        assertTrue("timed out waiting for $what", ok)
    }

    private fun names(activity: Activity): List<String> {
        val list = activity.findViewById<RecyclerView>(R.id.list)
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST))
        list.layout(0, 0, 1080, 4000)
        return (0 until list.childCount).map { list.getChildAt(it).findViewById<TextView>(R.id.name).text.toString() }
    }

    private fun bubbles(activity: Activity): List<String> {
        val list = activity.findViewById<RecyclerView>(R.id.list)
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST))
        list.layout(0, 0, 1080, 4000)
        return (0 until list.childCount).map { list.getChildAt(it).findViewById<TextView>(R.id.bubble).text.toString() }
    }

    // --- the inbox -------------------------------------------------------------------------

    @Test
    fun withoutNotificationAccessItExplainsAndOpensAndroidsOwnSetting() {
        ActivityScenario.launch(MessengerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.setupCard).visibility)
                activity.findViewById<View>(R.id.setupButton).performClick()
                val started = shadowOf(activity).nextStartedActivity
                assertTrue(
                    started.action == Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS ||
                        started.action == Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                )
            }
        }
    }

    @Test
    fun withAccessItListsWhatWasCaughtNewestFirst() {
        grantAccess()
        seed("alice", "Alice", "Hi", ts = 1_000)
        seed("bob", "Bob", "Yo", ts = 2_000)
        ActivityScenario.launch(MessengerActivity::class.java).use { scenario ->
            scenario.waitFor("the conversations") { names(it) == listOf("Bob", "Alice") }
            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.findViewById<View>(R.id.setupCard).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.empty).visibility)
                // A tap opens that conversation.
                activity.findViewById<RecyclerView>(R.id.list).getChildAt(1).performClick()
                val started = shadowOf(activity).nextStartedActivity
                assertEquals(MessengerThreadActivity::class.java.name, started.component?.className)
            }
        }
    }

    @Test
    fun aMessageCaughtWhileTheListShowsAppearsInIt() {
        grantAccess()
        ActivityScenario.launch(MessengerActivity::class.java).use { scenario ->
            scenario.waitFor("the empty note") { it.findViewById<View>(R.id.empty).visibility == View.VISIBLE }
            seed("alice", "Alice", "Hi")
            com.lightdecoder.messenger.MessengerEvents.notifyChanged()
            scenario.waitFor("the new conversation") { names(it) == listOf("Alice") }
        }
    }

    @Test
    fun theTitleIsTheSwitcherWithMessengerBeforeWhatsAppAndChosen() {
        ActivityScenario.launch(MessengerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val tabs = activity.findViewById<TabLayout>(R.id.services)
                assertFalse("no tab row: the title is the switcher", tabs.isShown)
                assertEquals("Messenger", activity.findViewById<TextView>(R.id.switcherLabel).text.toString())
                activity.findViewById<View>(R.id.servicesMenu).performClick()
                val popup = org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu()!!
                val items = (0 until popup.menu.size()).map { popup.menu.getItem(it) }
                assertEquals(listOf("Messages", "Discord", "Messenger", "WhatsApp"), items.map { it.title.toString() })
                assertTrue("Messenger is ticked", items[2].isChecked)
                // Another app's screen takes over: the conversation list, switched in place.
                shadowOf(popup).onMenuItemClickListener.onMenuItemClick(items[3])
                val started = shadowOf(activity).nextStartedActivity
                assertEquals(ConversationsActivity::class.java.name, started.component?.className)
                assertTrue("in place, not made again", started.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
                assertTrue(activity.isFinishing)
            }
        }
        ActivityScenario.launch(MessengerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<TabLayout>(R.id.services).getTabAt(1)!!.select()
                assertEquals(DiscordActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
            }
        }
    }

    @Test
    fun fullHistoryOpensFacebooksPageInItsOwnScreen() {
        ActivityScenario.launch(MessengerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.openWeb).performClick()
                assertEquals(MessengerWebActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
            }
        }
    }

    @Test
    fun pausingShowsABannerAndResumeTurnsItBackOn() {
        grantAccess()
        MessengerPrefs.setPaused(context, true)
        ActivityScenario.launch(MessengerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pausedBanner).visibility)
                activity.findViewById<View>(R.id.resume).performClick()
                assertEquals(View.GONE, activity.findViewById<View>(R.id.pausedBanner).visibility)
                assertFalse(MessengerPrefs.isPaused(activity))
            }
        }
    }

    // --- one conversation ------------------------------------------------------------------

    @Test
    fun aReplyIsHandedToMessengersReplyButtonAndShownAsHandedOver() {
        grantAccess()
        seed("alice", "Alice", "Hi")
        val reply = MessengerCapture.replyAction(chat(context, listOf(Line("Hi", 1_000, person("Alice")))))
        MessengerReplies.update("alice", "0|pkg|1", reply, null)
        val received = ArrayList<String>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                received.add(RemoteInput.getResultsFromIntent(intent)?.getCharSequence(MessengerTestNotifications.REPLY_KEY).toString())
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(MessengerTestNotifications.REPLY_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            ActivityScenario.launch<MessengerThreadActivity>(MessengerThreadActivity.intent(context, "alice")).use { scenario ->
                scenario.waitFor("the conversation") { bubbles(it) == listOf("Hi") }
                scenario.onActivity { activity ->
                    assertEquals("Alice", (activity as androidx.appcompat.app.AppCompatActivity).supportActionBar?.title)
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.composer).visibility)
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.noReplyPanel).visibility)
                    assertFalse("nothing typed yet", activity.findViewById<View>(R.id.send).isEnabled)
                    activity.findViewById<EditText>(R.id.input).setText("On my way")
                    activity.findViewById<View>(R.id.send).performClick()
                    assertEquals("", activity.findViewById<EditText>(R.id.input).text.toString())
                }
                scenario.waitFor("the reply in the list") { bubbles(it) == listOf("Hi", "On my way") }
                assertEquals(listOf("On my way"), received)
                assertEquals(MessengerStore.STATE_SENT_UNCONFIRMED, store.messages("alice").last().state)
                assertEquals("opening it read it", 0, store.conversation("alice")!!.unread)
            }
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    @Test
    fun withoutAReplyButtonItOffersMessengerAndTheFullHistoryInstead() {
        grantAccess()
        seed("alice", "Alice", "Hi")
        ActivityScenario.launch<MessengerThreadActivity>(MessengerThreadActivity.intent(context, "alice")).use { scenario ->
            scenario.waitFor("the conversation") { bubbles(it) == listOf("Hi") }
            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.findViewById<View>(R.id.composer).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.noReplyPanel).visibility)
                activity.findViewById<View>(R.id.noReplyOpenWeb).performClick()
                assertEquals(MessengerWebActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
            }
        }
    }

    @Test
    fun aWithdrawnReplyButtonKeepsTheTextAndSendsNothing() {
        grantAccess()
        seed("alice", "Alice", "Hi")
        val reply = MessengerCapture.replyAction(chat(context, listOf(Line("Hi", 1_000, person("Alice")))))!!
        MessengerReplies.update("alice", "0|pkg|1", reply, null)
        ActivityScenario.launch<MessengerThreadActivity>(MessengerThreadActivity.intent(context, "alice")).use { scenario ->
            scenario.waitFor("the conversation") { bubbles(it) == listOf("Hi") }
            reply.intent.cancel()
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.input).setText("Still there?")
                activity.findViewById<View>(R.id.send).performClick()
                assertEquals("the text is kept", "Still there?", activity.findViewById<EditText>(R.id.input).text.toString())
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.noReplyPanel).visibility)
            }
            assertEquals(listOf("Hi"), store.messages("alice").map { it.text })
        }
    }

    @Test
    fun aPhotoOrVideoLineLeadsToTheRealThing() {
        grantAccess()
        store.record(
            CapturedConversation(
                "diner", "Gwen's all nite diner", true,
                listOf(
                    CapturedMessage("David", false, "There we go", MessengerCapture.KIND_TEXT, 1_000),
                    CapturedMessage("David", false, "Sent a video.", MessengerCapture.KIND_TEXT, 2_000),
                ),
            )
        )
        ActivityScenario.launch<MessengerThreadActivity>(MessengerThreadActivity.intent(context, "diner")).use { scenario ->
            scenario.waitFor("the conversation") { bubbles(it) == listOf("There we go", "Sent a video.") }
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.list)
                val text = list.getChildAt(0)
                val video = list.getChildAt(1)
                assertFalse("a message is not a link", text.findViewById<View>(R.id.bubble).hasOnClickListeners())
                assertTrue(video.findViewById<TextView>(R.id.meta).text.toString().endsWith("Tap to see it in Messenger"))
                assertTrue(video.findViewById<View>(R.id.bubble).hasOnClickListeners())
                // No Messenger app on the test phone: the tap opens Facebook's page instead.
                video.findViewById<View>(R.id.bubble).performClick()
                assertEquals(MessengerWebActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
            }
        }
    }

    @Test
    fun aConversationThatIsNotThereClosesTheScreen() {
        ActivityScenario.launch<MessengerThreadActivity>(MessengerThreadActivity.intent(context, "nobody")).use { scenario ->
            scenario.waitFor("the screen to close") { it.isFinishing }
        }
    }

    // --- Facebook's page -------------------------------------------------------------------

    @Test
    fun outsideItsContainerTheWebScreenRefusesToOpen() {
        // Tests run in the main process, which is not the container.
        ActivityScenario.launch(MessengerWebActivity::class.java).use { scenario ->
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
        }
    }
}
