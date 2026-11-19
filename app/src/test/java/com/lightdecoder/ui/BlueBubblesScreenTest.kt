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
        com.lightdecoder.imessage.IMessageLog.clear(two.app)
        com.lightdecoder.imessage.IMessageChecks.resetForTesting()
    }

    @After
    fun tearDown() {
        reader?.onListenerDisconnected()
        com.lightdecoder.imessage.IMessageChecks.resetForTesting()
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
    fun checkIMessageSaysGreenWhenTheyReplyByTextEvenWithItsDialogClosed() = runCheck(replyByIMessage = false, closeFirst = true)

    /**
     * Check iMessage in Alice's Messages conversation: it goes through OpenBubbles' Reply button,
     * OpenBubbles shows it back, and Alice's reply (by iMessage, or by text) gives the verdict.
     */
    private fun runCheck(replyByIMessage: Boolean, closeFirst: Boolean = false) {
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
                assertEquals(com.lightdecoder.imessage.IMessageCheck.TEXT, check)
                assertTrue("not as a text", two.env.repo.messages("c1").none { it.text == check })
                scenario.waitFor("step 1 ticked") { steps(it).lines().first().startsWith("\u2713") }
                // OpenBubbles shows it back (it went to Apple as an iMessage).
                val now = System.currentTimeMillis()
                iMessage.record(
                    CapturedIMessageChat(ALICE, "m2", "Alice", false, "+15551234567", listOf(CapturedIMessage("", "", true, check, now))),
                    live = true,
                )
                scenario.waitFor("step 2 ticked") { steps(it).lines()[1].startsWith("\u2713") }
                if (closeFirst) {
                    shadowOf(two.app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
                    // Closed while waiting: it keeps checking, and the menu says so.
                    scenario.onActivity { activity ->
                        assertEquals(activity.getString(R.string.imessage_check_hide),
                            activity.checkDialog!!.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
                        activity.checkDialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    }
                    org.robolectric.shadows.ShadowLooper.idleMainLooper()
                    scenario.onActivity { activity ->
                        assertEquals(null, activity.checkDialog)
                        assertEquals(activity.getString(R.string.imessage_check_running), menu(activity).findItem(R.id.action_check_imessage).title.toString())
                    }
                }
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
                if (closeFirst) {
                    // The verdict comes as a notification; the menu reopens the finished check.
                    val notifications = shadowOf(two.app.getSystemService(NotificationManager::class.java))
                    scenario.waitFor("the verdict notification") {
                        notifications.allNotifications.any { n -> n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() == expected }
                    }
                    scenario.onActivity { activity ->
                        assertEquals(activity.getString(R.string.imessage_check), menu(activity).findItem(R.id.action_check_imessage).title.toString())
                    }
                    assertTrue(com.lightdecoder.imessage.IMessageLog.read(two.app).any { it.contains("still checking in the background") })
                    assertTrue(com.lightdecoder.imessage.IMessageLog.read(two.app).any { it.contains("(seen in the background)") })
                } else {
                    scenario.waitFor("the verdict") { verdict(it) == expected }
                    scenario.onActivity { assertTrue(steps(it).lines()[2].startsWith("\u2713")) }
                }

                // The log tells the same story, with nothing of who or what.
                val log = com.lightdecoder.imessage.IMessageLog.read(two.app).reversed()
                val ref = com.lightdecoder.imessage.IMessageLog.chatRef(ALICE)
                fun has(what: String) = assertTrue("log has \"$what\":\n" + log.joinToString("\n"), log.any { it.contains(what) })
                has("Check iMessage $ref: started")
                has("$ref: send through OpenBubbles' Reply button: handed over")
                has("Check iMessage $ref: step 1 handed to OpenBubbles")
                has("$ref: OpenBubbles showed a message sent from here back after")
                has("Check iMessage $ref: step 2 shown back by OpenBubbles")
                has(if (replyByIMessage) "step 3 reply after" else "step 3 reply after")
                has(if (replyByIMessage) "by iMessage: BLUE on their iPhone" else "by text: GREEN on their iPhone")
                val report = com.lightdecoder.ui.ProblemReport.build(two.app)
                assertTrue(report.contains("== iMessage (Blue Bubbles) =="))
                assertTrue(report.contains("Blue Bubbles: on"))
                assertTrue(report.contains("step 2 shown back by OpenBubbles"))
                for (secret in listOf("Alice", "5551234567", "555-123", check, ALICE, "OK\n")) {
                    assertFalse("must not be in the log: $secret", log.any { it.contains(secret) })
                    assertFalse("must not be in the report: $secret", report.contains(secret))
                }
            }
        } finally {
            two.app.unregisterReceiver(receiver)
        }
    }

    @Test
    fun aTextThatGoesGreenSaysWhyInTheLog() {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        two.seedConversation(ChatService.MESSAGES, "c2", "Bob", lastTs = 1_000, number = "+1 555-987-6543")
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c2").setFlags(0)).use { scenario ->
            scenario.waitFor("the composer") { it.findViewById<View>(R.id.input).isEnabled }
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.input).setText("A green one")
                activity.findViewById<View>(R.id.send).performClick()
            }
            val log = com.lightdecoder.imessage.IMessageLog.read(two.app)
            assertTrue(log.joinToString("\n"), log.any { it.contains("sent as a TEXT (green): no iMessage chat with this person in OpenBubbles yet") })
            assertTrue(log.none { it.contains("Bob") || it.contains("5559876543") || it.contains("A green one") })
        }
    }

    // --- iMessages in the right conversation, and unsending -----------------------------

    @Test
    fun anIMessageChatWithoutANumberShowsInTheConversationWithItsName() {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        two.seedConversation(ChatService.MESSAGES, "c1", "Nina", lastTs = 1_000, number = "+1 555-123-4567")
        two.seedIncoming(ChatService.MESSAGES, "sms1", "c1", "An earlier text", ts = 1_000)
        // OpenBubbles' notification names her but carries no number.
        iMessage.record(
            CapturedIMessageChat(ALICE, "m1", "Nina", false, "", listOf(CapturedIMessage("Nina", "", false, "OK", 5_000))),
            live = true,
        )
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("her iMessage inside the ongoing conversation") { shownTexts(it) == listOf("An earlier text", "OK") }
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.imessage_compose_hint), activity.findViewById<EditText>(R.id.input).hint.toString())
                assertTrue(menu(activity).findItem(R.id.action_unlink_imessage).isVisible)
            }
        }
        launchList().use { scenario ->
            scenario.waitFor("one row for Nina") { titles(it) == listOf("Nina") }
        }
    }

    @Test
    fun anIMessageChatCanBeLinkedByHand() {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        two.seedConversation(ChatService.MESSAGES, "c1", "Bob", lastTs = 1_000, number = "+1 555-987-6543")
        // His iMessages come from his Apple ID under another name: nothing links them by itself.
        iMessage.record(
            CapturedIMessageChat(ALICE, "m1", "Robert", false, "robert@example.com", listOf(CapturedIMessage("Robert", "mailto:robert@example.com", false, "From my Mac", 5_000))),
            live = false,
        )
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("the conversation") { it.findViewById<View>(R.id.input).isEnabled }
            scenario.onActivity { activity ->
                assertFalse(shownTexts(activity).contains("From my Mac"))
                val item = menu(activity).findItem(R.id.action_link_imessage)
                assertTrue(item.isVisible)
                activity.onOptionsItemSelected(item)
            }
            scenario.waitFor("the list of chats") { ShadowDialog.getLatestDialog()?.isShowing == true }
            val dialog = latestDialog()
            assertEquals(1, dialog.listView.adapter.count)
            assertTrue(dialog.listView.adapter.getItem(0).toString().startsWith("Robert"))
            shadowOf(dialog.listView).performItemClick(0)
            scenario.waitFor("his iMessages here") { shownTexts(it).contains("From my Mac") }
            assertEquals("c1", IMessageManager.get(two.app).let { com.lightdecoder.imessage.IMessageLinks.manual(two.app)[ALICE] })
        }
    }

    @Test
    fun myIMessageCanBeUnsentInOpenBubblesWithinTwoMinutes() {
        two.env.pairedManager()
        installOpenBubbles()
        IMessagePrefs.setEnabled(two.app, true)
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        seedIMessage("An iMessage", ts = 5_000)
        val now = System.currentTimeMillis()
        iMessage.record(
            CapturedIMessageChat(ALICE, "m2", "Alice", false, "+15551234567", listOf(
                CapturedIMessage("", "", true, "Oops, wrong one", now - 30_000),
                CapturedIMessage("", "", true, "An old one", now - 10 * 60_000),
            )),
            live = false,
        )
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("my iMessages") { shownTexts(it).contains("Oops, wrong one") }
            fun rowOf(activity: ThreadActivity, text: String) =
                ((activity.findViewById<RecyclerView>(R.id.messages).adapter as MessagesAdapter).currentList)
                    .filterIsInstance<com.lightdecoder.ui.chat.ThreadItem.Message>().map { it.row }.single { it.text == text }
            // Within two minutes: OpenBubbles opens for Undo Send.
            scenario.onActivity { activity -> activity.onMessageLongPress(rowOf(activity, "Oops, wrong one")) }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val sheet = ShadowDialog.getLatestDialog()
            val unsend = sheet.findViewById<View>(R.id.actionUnsend)
            assertEquals(View.VISIBLE, unsend.visibility)
            unsend.performClick()
            scenario.onActivity { activity ->
                val opened = shadowOf(activity).nextStartedActivity
                assertEquals(OpenBubbles.PACKAGES.first(), opened?.`package` ?: opened?.component?.packageName)
            }
            assertTrue(com.lightdecoder.imessage.IMessageLog.read(two.app).any { it.contains("OpenBubbles opened on the chat for Undo Send") })
            // Too late: Apple would not undo it; it can be deleted here only.
            scenario.onActivity { activity -> activity.onMessageLongPress(rowOf(activity, "An old one")) }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            ShadowDialog.getLatestDialog().findViewById<View>(R.id.actionUnsend).performClick()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            scenario.onActivity { activity ->
                val shown = latestDialog().findViewById<android.widget.TextView>(android.R.id.message)?.text?.toString()
                assertEquals(activity.getString(R.string.imessage_unsend_too_late), shown)
            }
            // Not offered on someone else's message.
            scenario.onActivity { activity -> activity.onMessageLongPress(rowOf(activity, "An iMessage")) }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertEquals(View.GONE, ShadowDialog.getLatestDialog().findViewById<View>(R.id.actionUnsend).visibility)
        }
    }

    // --- the iMessage light ----------------------------------------------------------------

    private fun light(activity: Activity): Pair<Boolean, com.lightdecoder.ui.chat.IMessageLight.State?> {
        val item = menu(activity).findItem(R.id.action_imessage_light)
        val dot = item.actionView?.findViewById<View>(R.id.imessageLightDot)
        return item.isVisible to (dot?.tag as? com.lightdecoder.ui.chat.IMessageLight.State)
    }

    private var reader: MessengerNotificationListener? = null

    private fun startReader() {
        reader = org.robolectric.Robolectric.setupService(MessengerNotificationListener::class.java).also { it.onListenerConnected() }
    }

    @Test
    fun theListsLightSaysWhetherIMessageIsWorking() {
        two.env.pairedManager()
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        launchList().use { scenario ->
            scenario.waitFor("the list") { titles(it).isNotEmpty() }
            scenario.onActivity { assertFalse("no light while Blue Bubbles is off", light(it).first) }
        }
        installOpenBubbles()
        IMessagePrefs.setEnabled(two.app, true)
        iMessage.refreshState()
        launchList().use { scenario ->
            scenario.waitFor("the list") { titles(it).isNotEmpty() }
            scenario.onActivity { assertEquals("on, but no notification access", true to com.lightdecoder.ui.chat.IMessageLight.State.PROBLEM, light(it)) }
        }
        grantAccess()
        startReader()
        iMessage.refreshState()
        launchList().use { scenario ->
            scenario.waitFor("the list") { titles(it).isNotEmpty() }
            scenario.onActivity { activity ->
                assertEquals(true to com.lightdecoder.ui.chat.IMessageLight.State.ACTIVE, light(activity))
                // A tap says why, in words.
                menu(activity).findItem(R.id.action_imessage_light).actionView!!.performClick()
            }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertEquals(two.app.getString(R.string.imessage_light_on), latestDialog().findViewById<android.widget.TextView>(android.R.id.message)?.text?.toString())
        }
    }

    @Test
    fun aConversationsLightSaysHowItSends() {
        two.env.pairedManager()
        installOpenBubbles()
        grantAccess()
        startReader()
        IMessagePrefs.setEnabled(two.app, true)
        iMessage.refreshState()
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        two.seedConversation(ChatService.MESSAGES, "c2", "Bob", lastTs = 1_000, number = "+1 555-987-6543")
        seedIMessage("An iMessage", ts = 5_000)
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("her iMessage") { shownTexts(it).contains("An iMessage") }
            // Her iMessage chat, but no Reply button from OpenBubbles yet: amber (one tap in OpenBubbles).
            scenario.onActivity { assertEquals(com.lightdecoder.ui.chat.IMessageLight.State.HAND_OFF, light(it).second) }
            val reply = com.lightdecoder.imessage.OpenBubblesTestNotifications.replyAction(two.app)
            com.lightdecoder.imessage.IMessageReplies.update(
                ALICE, com.lightdecoder.imessage.IMessageAction(reply.actionIntent!!, arrayOf(android.app.RemoteInput.Builder("text_reply").build())),
                null, null, "m1",
            )
            scenario.onActivity { assertEquals("straight through OpenBubbles: blue", com.lightdecoder.ui.chat.IMessageLight.State.ACTIVE, light(it).second) }
        }
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c2").setFlags(0)).use { scenario ->
            scenario.waitFor("the conversation") { it.findViewById<View>(R.id.input).isEnabled }
            scenario.onActivity { assertEquals("texts only: grey, but shown", true to com.lightdecoder.ui.chat.IMessageLight.State.OFF, light(it)) }
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
