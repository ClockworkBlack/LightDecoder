package com.lightdecoder.ui

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageInfo
import android.provider.Settings
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.tabs.TabLayout
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.CapturedIMessage
import com.lightdecoder.imessage.CapturedIMessageChat
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.IMessagePrefs
import com.lightdecoder.imessage.OpenBubbles
import com.lightdecoder.messenger.MessengerNotificationListener
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.chat.ConversationsActivity
import com.lightdecoder.ui.chat.ConversationsAdapter
import com.lightdecoder.ui.chat.MessagesAdapter
import com.lightdecoder.ui.chat.ThreadActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

/**
 * "Blue Bubbles" in the Messages tab: the checkbox in its menu, the iMessage
 * chats listed with Messages' own, and an iMessage chat's screen.
 */
@RunWith(AndroidJUnit4::class)
class BlueBubblesScreenTest {

    private lateinit var two: TwoServicesEnv
    private lateinit var imDb: ArchiveDb
    private lateinit var imRepo: ArchiveRepository
    private lateinit var iMessage: IMessageManager

    @Before
    fun setUp() {
        two = TwoServicesEnv()
        two.app.deleteDatabase(IM_DB)
        imDb = ArchiveDb(two.app, IM_DB)
        imRepo = ArchiveRepository.forTesting(imDb, ChatService.IMESSAGE)
        ArchiveRepository.installForTesting(imRepo)
        iMessage = IMessageManager.installForTesting(two.app, imRepo)
        IMessagePrefs.setEnabled(two.app, false)
    }

    @After
    fun tearDown() {
        IMessagePrefs.setEnabled(two.app, false)
        IMessageManager.resetForTesting()
        two.tearDown()
        imDb.close()
    }

    private fun seedIMessage(text: String = "Hi from an iPhone", ts: Long = 5_000) {
        iMessage.record(
            CapturedIMessageChat(ALICE, "m1", "Alice", false, "+15551234567", listOf(CapturedIMessage("Alice", "tel:+15551234567", false, text, ts))),
            live = false,
        )
    }

    private fun installOpenBubbles() {
        shadowOf(two.app.packageManager).installPackage(PackageInfo().apply { packageName = OpenBubbles.PACKAGES.first() })
    }

    private fun grantAccess() {
        shadowOf(two.app.getSystemService(NotificationManager::class.java))
            .setNotificationListenerAccessGranted(ComponentName(two.app, MessengerNotificationListener::class.java), true)
    }

    @Test
    fun theCheckboxIsInTheMessagesMenuOnly() {
        two.env.pairedManager()
        two.seedConversation(ChatService.MESSAGES, "c1", "Bob")
        two.seedConversation(ChatService.WHATSAPP, "111@s.whatsapp.net", "Carol")
        launchList().use { scenario ->
            scenario.waitFor("the Messages inbox") { titles(it) == listOf("Bob") }
            scenario.onActivity { activity ->
                val item = menu(activity).findItem(R.id.action_blue_bubbles)
                assertTrue(item.isVisible)
                assertTrue(item.isCheckable)
                assertFalse(item.isChecked)
                assertEquals("Blue Bubbles", item.title.toString())
                selectService(activity, ChatService.WHATSAPP)
            }
            scenario.waitFor("the WhatsApp inbox") { titles(it) == listOf("Carol") }
            scenario.onActivity { activity -> assertFalse(menu(activity).findItem(R.id.action_blue_bubbles).isVisible) }
        }
    }

    @Test
    fun withBlueBubblesOnIMessageChatsAreListedInMessagesMarkedIMessage() {
        two.env.pairedManager()
        two.seedConversation(ChatService.MESSAGES, "c1", "Bob", lastTs = 1_000)
        seedIMessage(ts = 5_000)
        launchList().use { scenario ->
            scenario.waitFor("Messages only while it is off") { titles(it) == listOf("Bob") }
        }
        IMessagePrefs.setEnabled(two.app, true)
        launchList().use { scenario ->
            scenario.waitFor("both, newest first") { titles(it) == listOf("Alice", "Bob") }
            scenario.onActivity { activity ->
                assertTrue(menu(activity).findItem(R.id.action_blue_bubbles).isChecked)
                val list = activity.findViewById<RecyclerView>(R.id.list)
                list.measure(0, 0)
                list.layout(0, 0, 1080, 2000)
                val badges = (0 until 2).map { list.findViewHolderForAdapterPosition(it)!!.itemView.findViewById<View>(R.id.imessageBadge).visibility }
                assertEquals(listOf(View.VISIBLE, View.GONE), badges)
                // A tap opens the iMessage chat in the conversation screen.
                list.findViewHolderForAdapterPosition(0)!!.itemView.performClick()
                val started = shadowOf(activity).nextStartedActivity
                assertEquals(ThreadActivity::class.java.name, started.component?.className)
                assertEquals(ChatService.IMESSAGE, RelayIntents.serviceOf(started))
                assertEquals(ALICE, started.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID))
            }
        }
    }

    @Test
    fun tickingItWithoutOpenBubblesOffersTheApp() {
        two.env.pairedManager()
        two.seedConversation(ChatService.MESSAGES, "c1", "Bob")
        launchList().use { scenario ->
            scenario.waitFor("the Messages inbox") { titles(it) == listOf("Bob") }
            scenario.onActivity { activity ->
                activity.onOptionsItemSelected(menu(activity).findItem(R.id.action_blue_bubbles))
                val dialog = latestDialog()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                val started = shadowOf(activity).nextStartedActivity
                assertEquals(Intent.ACTION_VIEW, started.action)
                assertTrue(started.dataString!!.contains("com.openbubbles.messaging"))
                assertFalse(IMessagePrefs.enabled(activity))
            }
        }
    }

    @Test
    fun tickingItAsksForNotificationAccessAndComesOnOnceGranted() {
        two.env.pairedManager()
        installOpenBubbles()
        two.seedConversation(ChatService.MESSAGES, "c1", "Bob")
        launchList().use { scenario ->
            scenario.waitFor("the Messages inbox") { titles(it) == listOf("Bob") }
            scenario.onActivity { activity ->
                activity.onOptionsItemSelected(menu(activity).findItem(R.id.action_blue_bubbles))
                latestDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                val started = shadowOf(activity).nextStartedActivity
                assertTrue(started.action == Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS || started.action == Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                assertFalse("not before access is there", IMessagePrefs.enabled(activity))
            }
            grantAccess()
            // Back from the setting.
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertTrue(IMessagePrefs.enabled(activity))
                val shown = latestDialog().findViewById<android.widget.TextView>(android.R.id.message)?.text?.toString()
                assertEquals(activity.getString(R.string.blue_bubbles_on_body), shown)
                assertTrue(menu(activity).findItem(R.id.action_blue_bubbles).isChecked)
            }
        }
    }

    @Test
    fun untickingItTurnsItOff() {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        two.seedConversation(ChatService.MESSAGES, "c1", "Bob", lastTs = 1_000)
        seedIMessage()
        launchList().use { scenario ->
            scenario.waitFor("both") { titles(it) == listOf("Alice", "Bob") }
            scenario.onActivity { activity -> activity.onOptionsItemSelected(menu(activity).findItem(R.id.action_blue_bubbles)) }
            scenario.waitFor("Messages only") { titles(it) == listOf("Bob") }
            assertFalse(IMessagePrefs.enabled(two.app))
        }
    }

    @Test
    fun anIMessageChatHasBlueBubblesAndHandsOffToOpenBubblesWithoutAReplyButton() {
        two.env.pairedManager()
        installOpenBubbles()
        IMessagePrefs.setEnabled(two.app, true)
        seedIMessage()
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, ALICE, service = ChatService.IMESSAGE).setFlags(0)).use { scenario ->
            scenario.waitFor("the message") { (it.findViewById<RecyclerView>(R.id.messages).adapter as MessagesAdapter).itemCount > 1 }
            scenario.onActivity { activity ->
                assertTrue((activity.findViewById<RecyclerView>(R.id.messages).adapter as MessagesAdapter).blueOutgoing)
                activity.findViewById<EditText>(R.id.input).setText("See you soon")
                activity.findViewById<View>(R.id.send).performClick()
            }
            scenario.waitFor("OpenBubbles opened") { shadowOf(it).peekNextStartedActivity() != null }
            scenario.onActivity { activity ->
                val handOff = shadowOf(activity).nextStartedActivity
                assertEquals(OpenBubbles.PACKAGES.first(), handOff.`package`)
                assertEquals(Intent.ACTION_SEND, handOff.action)
                assertEquals(ALICE, handOff.getStringExtra(Intent.EXTRA_SHORTCUT_ID))
                assertEquals("See you soon", handOff.getStringExtra(Intent.EXTRA_TEXT))
                assertEquals("", activity.findViewById<EditText>(R.id.input).text.toString())
                // Up goes back to the Messages tab, where iMessage chats are listed.
                activity.onOptionsItemSelected(org.robolectric.fakes.RoboMenuItem(android.R.id.home))
                val up = shadowOf(activity).nextStartedActivity
                assertEquals(ConversationsActivity::class.java.name, up.component?.className)
                assertEquals(ChatService.MESSAGES, RelayIntents.serviceOf(up))
            }
        }
    }

    @Test
    fun aPersonsIMessagesAreInTheirMessagesConversationNotARowOfTheirOwn() {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        two.seedIncoming(ChatService.MESSAGES, "sms1", "c1", "A text", ts = 1_000)
        seedIMessage("An iMessage", ts = 5_000)
        launchList().use { scenario ->
            scenario.waitFor("one row for Alice") { titles(it) == listOf("Alice") }
            scenario.onActivity { activity ->
                val item = (activity.findViewById<RecyclerView>(R.id.list).adapter as ConversationsAdapter).currentList.single()
                assertEquals(ChatService.MESSAGES, item.service)
                assertEquals("the newer iMessage is its last message", "An iMessage", item.row.snippet)
            }
        }
    }

    @Test
    fun theMessagesConversationShowsTheIMessagesInBlueAndSendsAsIMessage() {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        two.seedIncoming(ChatService.MESSAGES, "sms1", "c1", "A text", ts = 1_000)
        seedIMessage("An iMessage", ts = 5_000)
        two.seedOutgoing(ChatService.MESSAGES, "sms2", "c1", "My text", ts = 2_000)
        iMessage.record(
            CapturedIMessageChat(ALICE, "m1", "Alice", false, "+15551234567", listOf(CapturedIMessage("", "", true, "My iMessage", 6_000))),
            live = false,
        )
        // OpenBubbles' notification offered a Reply button.
        val replies = ArrayList<String>()
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: Intent) {
                replies += android.app.RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(com.lightdecoder.imessage.OpenBubblesTestNotifications.REPLY_KEY).toString()
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            two.app, receiver, android.content.IntentFilter(com.lightdecoder.imessage.OpenBubblesTestNotifications.REPLY_ACTION),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val reply = com.lightdecoder.imessage.OpenBubblesTestNotifications.replyAction(two.app)
        com.lightdecoder.imessage.IMessageReplies.update(
            ALICE, com.lightdecoder.imessage.IMessageAction(reply.actionIntent!!, arrayOf(android.app.RemoteInput.Builder("text_reply").build())),
            null, null, "m1",
        )
        try {
            ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
                scenario.waitFor("texts and iMessages together") { shownTexts(it) == listOf("A text", "My text", "An iMessage", "My iMessage") }
                scenario.onActivity { activity ->
                    val adapter = activity.findViewById<RecyclerView>(R.id.messages).adapter as MessagesAdapter
                    val rows = adapter.currentList.filterIsInstance<com.lightdecoder.ui.chat.ThreadItem.Message>().map { it.row }
                    assertTrue("my iMessage is blue", adapter.blueFor(rows.single { it.text == "My iMessage" }))
                    assertFalse("my text is not", adapter.blueFor(rows.single { it.text == "My text" }))
                    assertEquals(activity.getString(R.string.imessage_compose_hint), activity.findViewById<EditText>(R.id.input).hint.toString())
                    assertFalse("its iMessages are already here", menu(activity).findItem(R.id.action_send_imessage).isVisible)
                    activity.findViewById<EditText>(R.id.input).setText("Blue one")
                    activity.findViewById<View>(R.id.send).performClick()
                }
                scenario.waitFor("sent as an iMessage") {
                    org.robolectric.shadows.ShadowLooper.idleMainLooper()
                    replies == listOf("Blue one")
                }
                scenario.waitFor("shown in blue") { shownTexts(it).last() == "Blue one" }
            }
        } finally {
            two.app.unregisterReceiver(receiver)
        }
    }

    @Test
    fun toSomeoneWithAnIMessageChatATextNeverGoesGreen() {
        two.env.pairedManager()
        installOpenBubbles()
        IMessagePrefs.setEnabled(two.app, true)
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        seedIMessage("An iMessage", ts = 5_000)
        // No OpenBubbles notification has offered a Reply button since the app started.
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("the iMessage shown") { shownTexts(it).contains("An iMessage") }
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.imessage_compose_hint), activity.findViewById<EditText>(R.id.input).hint.toString())
                activity.findViewById<EditText>(R.id.input).setText("Blue please")
                activity.findViewById<View>(R.id.send).performClick()
            }
            scenario.waitFor("OpenBubbles opened on the chat") { shadowOf(it).peekNextStartedActivity() != null }
            scenario.onActivity { activity ->
                val handOff = shadowOf(activity).nextStartedActivity
                assertEquals(OpenBubbles.PACKAGES.first(), handOff.`package`)
                assertEquals(ALICE, handOff.getStringExtra(Intent.EXTRA_SHORTCUT_ID))
                assertEquals("Blue please", handOff.getStringExtra(Intent.EXTRA_TEXT))
            }
            // Nothing went out as a text.
            assertTrue(two.env.repo.messages("c1").none { it.text == "Blue please" })
        }
    }

    @Test
    fun anIMessageNotificationOpensThePersonsMessagesConversation() {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", number = "+1 555-123-4567")
        seedIMessage()
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, ALICE, service = ChatService.IMESSAGE).setFlags(0)).use { scenario ->
            scenario.waitFor("moved to Messages") { shadowOf(it).peekNextStartedActivity() != null }
            scenario.onActivity { activity ->
                val moved = shadowOf(activity).nextStartedActivity
                assertEquals(ChatService.MESSAGES, RelayIntents.serviceOf(moved))
                assertEquals("c1", moved.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID))
            }
        }
    }

    @Test
    fun withOnlyBlueBubblesTheListOpensOnItsChatsInsteadOfSetup() {
        two.env.manager()
        two.wa.paired = false
        IMessagePrefs.setEnabled(two.app, true)
        seedIMessage()
        ActivityScenario.launch(ConversationsActivity::class.java).use { scenario ->
            scenario.waitFor("the iMessage chat") { titles(it) == listOf("Alice") }
            scenario.onActivity { activity ->
                assertFalse("not sent to Google Messages setup", activity.isFinishing)
                assertFalse("the Messages archive is empty: nothing to delete", menu(activity).findItem(R.id.action_delete_archive).isVisible)
            }
        }
    }

    @Test
    fun searchInMessagesFindsIMessagesToo() {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        seedIMessage("Dinner at eight?")
        launchList().use { scenario ->
            scenario.waitFor("the iMessage chat") { titles(it) == listOf("Alice") }
            scenario.onActivity { activity ->
                val search = menu(activity).findItem(R.id.action_search)
                search.expandActionView()
                (search.actionView as androidx.appcompat.widget.SearchView).setQuery("dinner", true)
            }
            scenario.waitFor("the hit") {
                (it.findViewById<RecyclerView>(R.id.searchResults).adapter as com.lightdecoder.ui.chat.SearchResultsAdapter).currentList.isNotEmpty()
            }
            scenario.onActivity { activity ->
                val hit = (activity.findViewById<RecyclerView>(R.id.searchResults).adapter as com.lightdecoder.ui.chat.SearchResultsAdapter).currentList.single()
                assertEquals(ChatService.IMESSAGE, hit.service)
                assertEquals(ALICE, hit.conversationId)
            }
        }
    }

    // --- Check iMessage -----------------------------------------------------------------

    @Test
    fun checkIMessageSaysBlueWhenTheyReplyByIMessage() = runCheck(replyByIMessage = true)

    @Test
    fun checkIMessageSaysGreenWhenTheyReplyByText() = runCheck(replyByIMessage = false)

    /**
     * Check iMessage in Alice's Messages conversation: it goes through OpenBubbles' Reply button,
     * OpenBubbles shows it back, and Alice's reply (by iMessage, or by text) gives the verdict.
     */
    private fun runCheck(replyByIMessage: Boolean) {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        seedIMessage("An iMessage", ts = 5_000)
        val replies = ArrayList<String>()
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: Intent) {
                replies += android.app.RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(com.lightdecoder.imessage.OpenBubblesTestNotifications.REPLY_KEY).toString()
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            two.app, receiver, android.content.IntentFilter(com.lightdecoder.imessage.OpenBubblesTestNotifications.REPLY_ACTION),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val reply = com.lightdecoder.imessage.OpenBubblesTestNotifications.replyAction(two.app)
        com.lightdecoder.imessage.IMessageReplies.update(
            ALICE, com.lightdecoder.imessage.IMessageAction(reply.actionIntent!!, arrayOf(android.app.RemoteInput.Builder("text_reply").build())),
            null, null, "m1",
        )
        try {
            ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
                scenario.waitFor("the iMessage shown") { shownTexts(it).contains("An iMessage") }
                scenario.onActivity { activity ->
                    val item = menu(activity).findItem(R.id.action_check_imessage)
                    assertTrue("offered where the person's iMessages are", item.isVisible)
                    activity.onOptionsItemSelected(item)
                }
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                latestDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                scenario.waitFor("the check sent through OpenBubbles' Reply button") {
                    org.robolectric.shadows.ShadowLooper.idleMainLooper()
                    replies.size == 1
                }
                val check = replies.single()
                assertTrue(check, check.startsWith("LightDecoder iMessage check "))
                assertTrue("not as a text", two.env.repo.messages("c1").none { it.text == check })
                scenario.waitFor("step 1 ticked") { steps(it).lines().first().startsWith("\u2713") }
                // OpenBubbles shows it back (it went to Apple as an iMessage).
                val now = System.currentTimeMillis()
                iMessage.record(
                    CapturedIMessageChat(ALICE, "m2", "Alice", false, "+15551234567", listOf(CapturedIMessage("", "", true, check, now))),
                    live = true,
                )
                scenario.waitFor("step 2 ticked") { steps(it).lines()[1].startsWith("\u2713") }
                // Alice answers from her iPhone.
                if (replyByIMessage) {
                    iMessage.record(
                        CapturedIMessageChat(ALICE, "m3", "Alice", false, "+15551234567", listOf(CapturedIMessage("Alice", "tel:+15551234567", false, "OK", now + 1_000))),
                        live = true,
                    )
                } else {
                    two.seedIncoming(ChatService.MESSAGES, "sms-ok", "c1", "OK", ts = now + 1_000)
                }
                val expected = scenario.let {
                    var text = ""
                    it.onActivity { a -> text = a.getString(if (replyByIMessage) R.string.imessage_check_blue else R.string.imessage_check_green, "Alice") }
                    text
                }
                scenario.waitFor("the verdict") { verdict(it) == expected }
                scenario.onActivity { assertTrue(steps(it).lines()[2].startsWith("\u2713")) }
            }
        } finally {
            two.app.unregisterReceiver(receiver)
        }
    }

    private fun steps(activity: Activity): String =
        (activity as ThreadActivity).checkDialog?.findViewById<android.widget.TextView>(R.id.checkSteps)?.text?.toString().orEmpty()

    private fun verdict(activity: Activity): String =
        (activity as ThreadActivity).checkDialog?.findViewById<android.widget.TextView>(R.id.checkVerdict)?.text?.toString().orEmpty()

    // --- helpers ------------------------------------------------------------------------

    private fun launchList(): ActivityScenario<ConversationsActivity> =
        ActivityScenario.launch(RelayIntents.conversations(two.app, ChatService.MESSAGES).setFlags(0))

    private fun <A : Activity> ActivityScenario<A>.waitFor(what: String, condition: (A) -> Boolean) =
        two.waitUntil(what) {
            var ok = false
            onActivity { ok = condition(it) }
            ok
        }

    private fun shownTexts(activity: Activity): List<String> =
        ((activity.findViewById<RecyclerView>(R.id.messages).adapter as MessagesAdapter).currentList)
            .filterIsInstance<com.lightdecoder.ui.chat.ThreadItem.Message>().map { it.row.text }

    private fun titles(activity: Activity): List<String> =
        (activity.findViewById<RecyclerView>(R.id.list).adapter as ConversationsAdapter).currentList.map { it.title }

    private fun menu(activity: Activity): android.view.Menu {
        val menu = activity.findViewById<Toolbar>(R.id.toolbar).menu
        activity.onPrepareOptionsMenu(menu)
        return menu
    }

    private fun selectService(activity: Activity, service: ChatService) {
        val tabs = activity.findViewById<TabLayout>(R.id.services)
        (0 until tabs.tabCount).map { tabs.getTabAt(it)!! }.first { it.tag == service }.select()
    }

    private fun latestDialog(): AlertDialog {
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("no dialog", dialog)
        return dialog as AlertDialog
    }

    private companion object {
        const val IM_DB = "imessage-screen-test.db"
        const val ALICE = "5D2B5A4C-1F0E-4C3B-9A7D-2E6F1B0C9D11"
    }
}
