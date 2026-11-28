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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        // FileProvider keeps the first test's folders for good; each test has its own (a hand-off shares files).
        runCatching {
            val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
            (cache.get(null) as MutableMap<*, *>).clear()
        }
        com.lightdecoder.imessage.IMessageChecks.resetForTesting()
        IMessagePrefs.setEnabled(two.app, false)
        com.lightdecoder.imessage.direct.DirectPrefs.setGamesOn(two.app, true)
        com.lightdecoder.imessage.madrid.MadridApps.resetForTesting()
        OpenBubbles.resetForTesting()
        com.lightdecoder.imessage.direct.DirectPrefs.setRegistered(two.app, false)
        com.lightdecoder.imessage.direct.DirectLink.resetForTesting()
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

    /** The real OpenBubbles from the Play Store: installed, and signed as pinned (Robolectric fakes no certificates). */
    private fun installOpenBubbles() {
        com.lightdecoder.imessage.OpenBubblesTestNotifications.trust(two.app)
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
    fun aFileThatFailedThroughTheLibraryGoesToOpenBubblesWithItOnceTheLibraryIsOff() {
        two.env.pairedManager()
        installOpenBubbles()
        IMessagePrefs.setEnabled(two.app, true)
        seedIMessage()
        // Sent through the library before it was turned off, and failed: its copy is still here.
        val picture = byteArrayOf(-119, 80, 78, 71, 5)
        val copy = imRepo.outboxFileFor("t-m").apply { parentFile?.mkdirs(); writeBytes(picture) }
        imRepo.insertLocalOutgoing("t-m", ALICE, "Look", copy.path, "image/png", "IMG_3.png", null, System.currentTimeMillis())
        imRepo.setLocalState("t-m", com.lightdecoder.archive.LocalState.FAILED, "Apple refused the message")
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, ALICE, service = ChatService.IMESSAGE).setFlags(0)).use { scenario ->
            scenario.waitFor("the failed one") { shownTexts(it).contains("Look") }
            scenario.onActivity { activity -> activity.onFailedTap(rowOf(activity, "Look")) }
            scenario.waitFor("OpenBubbles opened") { shadowOf(it).peekNextStartedActivity() != null }
            scenario.onActivity { activity ->
                // With the picture, not its caption alone.
                val handOff = shadowOf(activity).nextStartedActivity
                assertEquals(OpenBubbles.PACKAGES.first(), handOff.`package`)
                assertEquals(Intent.ACTION_SEND, handOff.action)
                assertEquals("image/png", handOff.type)
                assertEquals("Look", handOff.getStringExtra(Intent.EXTRA_TEXT))
                @Suppress("DEPRECATION")
                val stream = handOff.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
                assertTrue(two.app.contentResolver.openInputStream(stream)!!.use { it.readBytes() }.contentEquals(picture))
            }
            two.waitUntil("the failed one gone, its copy too") { imRepo.message(com.lightdecoder.archive.MessageRow.tmpMessageId("t-m")) == null && !copy.exists() }
            assertTrue("nothing marked sent", imRepo.messages(ALICE).none { it.isOutgoing && !it.isLocal })
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
        // OpenBubbles is here: its Reply button is pressed only while it is the real one.
        installOpenBubbles()
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
        // OpenBubbles is here: its Reply button is pressed only while it is the real one.
        installOpenBubbles()
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
    fun anIMessageChatWithoutANumberIsOfferedByItsNameButLinkedOnlyByTheUser() {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        two.seedConversation(ChatService.MESSAGES, "c1", "Nina", lastTs = 1_000, number = "+1 555-123-4567")
        two.seedIncoming(ChatService.MESSAGES, "sms1", "c1", "An earlier text", ts = 1_000)
        // OpenBubbles' notification names her but carries no number: anyone could write under that name.
        iMessage.record(
            CapturedIMessageChat(ALICE, "m1", "Nina", false, "", listOf(CapturedIMessage("Nina", "", false, "OK", 5_000))),
            live = true,
        )
        launchList().use { scenario ->
            scenario.waitFor("a row of its own: a name alone links nothing") { titles(it).count { t -> t == "Nina" } == 2 }
        }
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("the conversation") { it.findViewById<View>(R.id.input).isEnabled }
            scenario.onActivity { activity ->
                assertEquals("not shown here by itself", listOf("An earlier text"), shownTexts(activity))
                val item = menu(activity).findItem(R.id.action_link_imessage)
                assertTrue(item.isVisible)
                activity.onOptionsItemSelected(item)
            }
            scenario.waitFor("the list of chats") { ShadowDialog.getLatestDialog()?.isShowing == true }
            val dialog = latestDialog()
            // Offered first, saying why, for the user to confirm it is her.
            val first = dialog.listView.adapter.getItem(0).toString()
            assertTrue(first, first.startsWith(two.app.getString(R.string.imessage_link_same_name, "Nina")))
            shadowOf(dialog.listView).performItemClick(0)
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
    fun everyConversationWithTheNumberKeepsShowingTheIMessages() {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        // Two Messages conversations with the same number (an old text thread and a newer one).
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 2_000, number = "+1 555-123-4567")
        two.seedConversation(ChatService.MESSAGES, "c2", "Alice", lastTs = 1_000, number = "+15551234567")
        seedIMessage("An iMessage", ts = 5_000)
        for (id in listOf("c1", "c2")) {
            ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, id).setFlags(0)).use { scenario ->
                scenario.waitFor("her iMessage in $id") { shownTexts(it).contains("An iMessage") }
            }
        }
    }

    @Test
    fun thePersonsOlderIMessageChatKeepsItsHistoryInTheConversation() {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        // Before OpenBubbles was set up again: one chat; after it: a new chat for the same person.
        iMessage.record(
            CapturedIMessageChat("OLD-CHAT-GUID-0000-000000000001", "m0", "Alice", false, "+15551234567",
                listOf(CapturedIMessage("Alice", "tel:+15551234567", false, "From before", 3_000))),
            live = false,
        )
        seedIMessage("After setting up again", ts = 9_000)
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("both chats' iMessages") { shownTexts(it).containsAll(listOf("From before", "After setting up again")) }
        }
        launchList().use { scenario ->
            scenario.waitFor("one row for Alice") { titles(it) == listOf("Alice") }
        }
    }

    @Test
    fun everyOldIMessageCanBeScrolledBackToInAConversationWithFewTexts() {
        // What looked like iMessages being lost (until 0.5.73.1): a person with a few texts and hundreds of iMessages
        // showed the newest hundred iMessages at most, under "Start of the conversation".
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        val t0 = System.currentTimeMillis() - 400L * 60_000
        for (i in 0 until 4) two.seedIncoming(ChatService.MESSAGES, "sms$i", "c1", "text $i", ts = t0 + (300L + i) * 60_000)
        // The phone has no older texts than these (all of its history is here).
        two.env.repo.setSyncState(com.lightdecoder.archive.SyncStateRow("c1", "", 0, historyComplete = true, lastBackfillAt = 0))
        directMode(java.util.Collections.synchronizedList(ArrayList()))
        for (i in 0 until 230) directMessage("IM$i", "imessage $i", t0 + i * 60_000L, mine = i % 3 == 0)
        fun header(a: Activity) = ((a.findViewById<RecyclerView>(R.id.messages).adapter as MessagesAdapter).currentList)
            .filterIsInstance<com.lightdecoder.ui.chat.ThreadItem.Header>().firstOrNull()
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("the newest messages") { shownTexts(it).containsAll(listOf("imessage 229", "text 3")) }
            scenario.onActivity { activity ->
                assertTrue("not everything at once", shownTexts(activity).size < 120)
                assertFalse("older ones are not shown yet", "imessage 0" in shownTexts(activity))
            }
            // Scrolled to the top again and again, as a finger would: older messages come each time, down to the first.
            var rounds = 0
            while (true) {
                var before = 0
                // Near the top first (laid out there), then the finger's last stretch up to it.
                scenario.onActivity { activity ->
                    before = shownTexts(activity).size
                    activity.findViewById<RecyclerView>(R.id.messages).scrollToPosition(6)
                }
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                scenario.onActivity { activity -> activity.findViewById<RecyclerView>(R.id.messages).scrollBy(0, -4_000) }
                if (before == 234) break
                assertTrue("it ends", ++rounds < 40)
                scenario.waitFor("older messages, round $rounds (from $before)") { shownTexts(it).size > before }
                scenario.onActivity { activity ->
                    if (shownTexts(activity).size < 234) assertNull("no \"Start of the conversation\" above messages still to come", header(activity)?.takeIf { it.complete })
                }
            }
            scenario.onActivity { activity ->
                val texts = shownTexts(activity)
                assertEquals(234, texts.size)
                assertEquals("imessage 0", texts.first())
                assertTrue(texts.containsAll((0 until 230).map { "imessage $it" } + (0 until 4).map { "text $it" }))
            }
            // Only now, above the very first message, the conversation's start.
            scenario.waitFor("the start of the conversation") { header(it)?.complete == true }
        }
    }

    // --- received pictures ---------------------------------------------------------------

    @Test
    fun aPictureFromIMessageShowsInItsBubbleFromOpenBubblesSavedCopy() {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        val t = System.currentTimeMillis()
        iMessage.record(
            CapturedIMessageChat(ALICE, "m1", "Alice", false, "+15551234567", listOf(CapturedIMessage("Alice", "tel:+15551234567", false, "Image", t))),
            live = false,
        )
        val png = java.io.ByteArrayOutputStream().also {
            android.graphics.Bitmap.createBitmap(40, 30, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        com.lightdecoder.imessage.IMessageMedia.source = { _, from, to ->
            listOf(com.lightdecoder.imessage.IMessageMedia.Saved(9, android.net.Uri.parse("content://media/9"), "image/png", t / 1000 + 3, png.size.toLong(), 40, 30))
                .filter { it.addedSec in from..to }
        }
        com.lightdecoder.imessage.IMessageMedia.open = { _, _ -> java.io.ByteArrayInputStream(png) }
        try {
            ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
                fun status(a: Activity) = a.findViewById<RecyclerView>(R.id.messages).findViewById<android.widget.TextView>(R.id.mediaStatus)
                scenario.waitFor("a picture slot, not the word Image") { a ->
                    shownTexts(a).none { it == "Image" } && status(a)?.let { it.isShown && it.text == a.getString(R.string.imessage_media_waiting) } == true
                }
                // Tapped without photo access: what it needs, and Allow.
                scenario.onActivity { a -> a.findViewById<RecyclerView>(R.id.messages).findViewById<View>(R.id.mediaFrame).performClick() }
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                assertTrue(latestDialog().findViewById<android.widget.TextView>(android.R.id.message)!!.text.contains("Auto-save Attachments"))
                latestDialog().dismiss()
                shadowOf(two.app).grantPermissions(*com.lightdecoder.imessage.IMessageMedia.permissions())
                assertEquals(1, com.lightdecoder.imessage.IMessageMedia.fill(two.app))
                scenario.waitFor("the picture itself") { a -> status(a)?.isShown != true }
                val part = imRepo.messages(ALICE).single().media.single()
                assertEquals("image/png", part.mime)
                assertTrue(java.io.File(part.localPath).readBytes().contentEquals(png))
            }
        } finally {
            com.lightdecoder.imessage.IMessageMedia.resetForTesting()
        }
    }

    // --- LightDecoder's own features with iMessages --------------------------------------

    /** Alice's Messages conversation with her iMessages in it, and OpenBubbles' Reply button held; the replies it gets. */
    private fun withReplyButton(block: (ArrayList<String>) -> Unit) {
        // OpenBubbles is here: its Reply button is pressed only while it is the real one.
        installOpenBubbles()
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
            block(replies)
        } finally {
            two.app.unregisterReceiver(receiver)
        }
    }

    private fun rowOf(activity: ThreadActivity, text: String) =
        ((activity.findViewById<RecyclerView>(R.id.messages).adapter as MessagesAdapter).currentList)
            .filterIsInstance<com.lightdecoder.ui.chat.ThreadItem.Message>().map { it.row }.single { it.text == text }

    @Test
    fun anIMessageCanBeRepliedToAndTheReplyGoesAsIMessage() {
        val (_, fake) = two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        seedIMessage("Dinner at 7?", ts = 5_000)
        withReplyButton { replies ->
            ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
                scenario.waitFor("her iMessage") { shownTexts(it).contains("Dinner at 7?") }
                scenario.onActivity { activity -> activity.onMessageLongPress(rowOf(activity, "Dinner at 7?")) }
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                val reply = ShadowDialog.getLatestDialog().findViewById<View>(R.id.actionReply)
                assertEquals("Reply is offered on an iMessage", View.VISIBLE, reply.visibility)
                reply.performClick()
                scenario.onActivity { activity ->
                    assertEquals(activity.getString(R.string.imessage_replying_to, "Alice"),
                        activity.findViewById<android.widget.TextView>(R.id.replySender).text.toString())
                    activity.findViewById<EditText>(R.id.input).setText("Yes!")
                    activity.findViewById<View>(R.id.send).performClick()
                }
                scenario.waitFor("sent through OpenBubbles' Reply button") {
                    org.robolectric.shadows.ShadowLooper.idleMainLooper()
                    replies == listOf("Yes!")
                }
                assertTrue("never as a green text", fake.sent.isEmpty())
            }
        }
    }

    @Test
    fun aStickerToSomeoneWithAnIMessageChatGoesAsIMessage() {
        val (manager, fake) = two.env.pairedManager()
        fake.goOnline()
        installOpenBubbles()
        IMessagePrefs.setEnabled(two.app, true)
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        seedIMessage("An iMessage", ts = 5_000)
        val sticker = java.io.File(two.app.filesDir, "sticker.webp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("her iMessage") { shownTexts(it).contains("An iMessage") }
            scenario.onActivity { it.sendStickers(listOf(sticker)) }
            scenario.waitFor("OpenBubbles opened with the sticker") { shadowOf(it).peekNextStartedActivity() != null }
            scenario.onActivity { activity ->
                val handOff = shadowOf(activity).nextStartedActivity
                assertEquals(OpenBubbles.PACKAGES.first(), handOff.`package`)
                assertEquals(ALICE, handOff.getStringExtra(Intent.EXTRA_SHORTCUT_ID))
            }
            manager.awaitIdleForTesting()
            assertTrue("not through Google Messages", fake.sent.isEmpty())
        }
    }

    @Test
    fun withoutTheLibraryMyIMessageIsEditedAndReactedToInOpenBubbles() {
        two.env.pairedManager()
        installOpenBubbles()
        IMessagePrefs.setEnabled(two.app, true)
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        seedIMessage("Hers", ts = 5_000)
        iMessage.record(
            CapturedIMessageChat(ALICE, "m2", "Alice", false, "+15551234567", listOf(CapturedIMessage("", "", true, "Mine, with a tpyo", System.currentTimeMillis() - 60_000))),
            live = false,
        )
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("my iMessage") { shownTexts(it).contains("Mine, with a tpyo") }
            scenario.onActivity { activity -> activity.onMessageLongPress(rowOf(activity, "Mine, with a tpyo")) }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val editItem = ShadowDialog.getLatestDialog().findViewById<android.widget.TextView>(R.id.actionEdit)
            assertEquals(View.VISIBLE, editItem.visibility)
            assertEquals(two.app.getString(R.string.imessage_edit), editItem.text.toString())
            editItem.performClick()
            scenario.onActivity { activity -> assertEquals(OpenBubbles.PACKAGES.first(), shadowOf(activity).nextStartedActivity?.`package`) }
            // A reaction on her iMessage: the reactions row is there, and a tap opens OpenBubbles.
            scenario.onActivity { activity -> activity.onMessageLongPress(rowOf(activity, "Hers")) }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val row = ShadowDialog.getLatestDialog().findViewById<android.view.ViewGroup>(R.id.reactionRow)
            assertEquals(View.VISIBLE, row.visibility)
            // The usual quick reactions, as before the library: Apple's tapbacks are the library's.
            val quick = ThreadActivity.QUICK_REACTIONS.indices.map { (row.getChildAt(it) as android.widget.TextView).text.toString() }
            assertEquals(ThreadActivity.QUICK_REACTIONS, quick)
            assertNotNull("then any emoji", row.getChildAt(ThreadActivity.QUICK_REACTIONS.size).findViewById<View>(R.id.reactionMore))
            row.getChildAt(0).performClick()
            scenario.onActivity { activity -> assertEquals(OpenBubbles.PACKAGES.first(), shadowOf(activity).nextStartedActivity?.`package`) }
            assertTrue(com.lightdecoder.imessage.IMessageLog.read(two.app).any { it.contains(": react: OpenBubbles opened on the chat") })
        }
    }

    @Test
    fun aReplyFromTheNotificationGoesAsIMessageNotGreen() {
        val (manager, fake) = two.env.pairedManager()
        fake.goOnline()
        IMessagePrefs.setEnabled(two.app, true)
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        seedIMessage("An iMessage", ts = 5_000)
        withReplyButton { replies ->
            val intent = Intent(two.app, com.lightdecoder.relay.ReplyReceiver::class.java)
                .setAction(com.lightdecoder.relay.MessageNotifier.ACTION_REPLY)
                .putExtra(RelayIntents.EXTRA_CONVERSATION_ID, "c1")
                .putExtra(RelayIntents.EXTRA_SERVICE, ChatService.MESSAGES.key)
            android.app.RemoteInput.addResultsToIntent(
                arrayOf(android.app.RemoteInput.Builder(com.lightdecoder.relay.MessageNotifier.KEY_REPLY_TEXT).build()),
                intent, android.os.Bundle().apply { putCharSequence(com.lightdecoder.relay.MessageNotifier.KEY_REPLY_TEXT, "From the notification") },
            )
            two.app.sendBroadcast(intent)
            two.waitUntil("sent as iMessage") {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                replies == listOf("From the notification")
            }
            manager.awaitIdleForTesting()
            assertTrue("not as a text", fake.sent.isEmpty())
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
    fun withoutTheLibraryMyIMessageIsUnsentInOpenBubblesWithinTwoMinutes() {
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
            val unsend = sheet.findViewById<android.widget.TextView>(R.id.actionUnsend)
            assertEquals(View.VISIBLE, unsend.visibility)
            assertEquals(two.app.getString(R.string.imessage_unsend), unsend.text.toString())
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

    // --- with the iMessage library (direct mode) ---------------------------------------------

    /**
     * The iMessage library set up, its requests to the other process kept in [asked] and answered
     * as Apple would (or by [answer]); Alice's iMessage chat as the library stores it. Typing and
     * read receipts (which go whenever a conversation is open) are kept only with [notices]. The
     * user's typing is told again after [typingRepeatMs] and stopped after [typingIdleMs].
     */
    private fun directMode(
        asked: MutableList<Pair<Int, org.json.JSONObject>>,
        notices: Boolean = false,
        typingRepeatMs: Long = com.lightdecoder.imessage.direct.DirectLink.TYPING_REPEAT_MS,
        typingIdleMs: Long = com.lightdecoder.imessage.direct.DirectLink.TYPING_IDLE_MS,
        answer: (Int) -> org.json.JSONObject = { what ->
            if (what == com.lightdecoder.imessage.direct.DirectWire.REACT) com.lightdecoder.imessage.direct.DirectWire.guid("T-OWN")
            else com.lightdecoder.imessage.direct.DirectWire.ok()
        },
    ) {
        com.lightdecoder.imessage.direct.DirectPrefs.setRegistered(two.app, true)
        com.lightdecoder.imessage.direct.DirectLibrary.REQUIRED.forEach {
            java.io.File(com.lightdecoder.imessage.direct.DirectLibrary.dir(two.app), it).apply { parentFile?.mkdirs(); writeText("") }
        }
        val notice = setOf(com.lightdecoder.imessage.direct.DirectWire.TYPING, com.lightdecoder.imessage.direct.DirectWire.READ)
        com.lightdecoder.imessage.direct.DirectLink.installForTesting(two.app, typingIdleMs, typingRepeatMs) { what, request, _ ->
            // Whether the person can be called with FaceTime is looked up when a chat opens: nothing the user did.
            if (what == com.lightdecoder.imessage.direct.DirectWire.FT_CAPABLE) {
                return@installForTesting com.lightdecoder.imessage.direct.DirectWire.capable(emptyList())
            }
            if (notices || what !in notice) asked += what to request
            answer(what)
        }
        imRepo.upsertConversation(
            com.lightdecoder.relay.ConversationDto(
                id = ALICE_DIRECT, type = IMessageManager.TRANSPORT,
                participants = listOf(
                    com.lightdecoder.relay.ParticipantDto(id = "me", isMe = true),
                    com.lightdecoder.relay.ParticipantDto(id = "+15551234567", name = "Alice", number = "+15551234567", formattedNumber = "+15551234567"),
                ),
            ),
        )
    }

    private fun directMessage(guid: String, text: String, ts: Long, mine: Boolean) {
        imRepo.upsertMessage(
            com.lightdecoder.relay.MessageDto(
                id = "imsg:$guid", conversationId = ALICE_DIRECT, fromMe = mine,
                senderId = if (mine) "" else "+15551234567", senderName = if (mine) "" else "Alice",
                timestampMs = ts, direction = if (mine) "out" else "in", status = if (mine) "OUTGOING_COMPLETE" else "INCOMING_COMPLETE",
                transport = IMessageManager.TRANSPORT, text = text, parts = listOf(com.lightdecoder.relay.PartDto("0", "text", text)), isOld = true,
            ),
        )
    }

    private fun firstEditText(view: View): EditText? = when (view) {
        is EditText -> view
        is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { firstEditText(view.getChildAt(it)) }
        else -> null
    }

    /** The bubble on screen whose text is [text]. */
    private fun bubbleOf(activity: Activity, text: String): View? {
        val list = activity.findViewById<RecyclerView>(R.id.messages)
        return (0 until list.childCount).map { list.getChildAt(it) }
            .firstOrNull { it.findViewById<android.widget.TextView>(R.id.body)?.text?.toString() == text }
    }

    @Test
    fun withTheLibraryMyIMessageIsEditedAndTappedBackHereNotInOpenBubbles() {
        two.env.pairedManager()
        installOpenBubbles()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        directMode(asked)
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        val now = System.currentTimeMillis()
        directMessage("H1", "Hers", now - 120_000, mine = false)
        directMessage("M1", "Mine, with a tpyo", now - 60_000, mine = true)
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("my iMessage") { shownTexts(it).contains("Mine, with a tpyo") }
            scenario.onActivity { activity -> activity.onMessageLongPress(rowOf(activity, "Mine, with a tpyo")) }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val sheet = ShadowDialog.getLatestDialog()
            val editItem = sheet.findViewById<android.widget.TextView>(R.id.actionEdit)
            assertEquals(View.VISIBLE, editItem.visibility)
            assertEquals("Edit", editItem.text.toString())
            val unsend = sheet.findViewById<android.widget.TextView>(R.id.actionUnsend)
            assertEquals(View.VISIBLE, unsend.visibility)
            assertEquals("Unsend", unsend.text.toString())
            editItem.performClick()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val dialog = latestDialog()
            assertEquals(two.app.getString(R.string.imessage_edit_note), dialog.findViewById<android.widget.TextView>(android.R.id.message)?.text?.toString())
            firstEditText(dialog.window!!.decorView)!!.setText("Mine, with a typo")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            scenario.waitFor("the edit, sent to Apple and shown") { activity ->
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                bubbleOf(activity, "Mine, with a typo")?.findViewById<View>(R.id.edited)?.visibility == View.VISIBLE
            }
            val (what, request) = asked.single()
            assertEquals(com.lightdecoder.imessage.direct.DirectWire.EDIT, what)
            assertEquals("M1", request.getString("guid"))
            assertEquals("Mine, with a typo", request.getString("text"))
            assertTrue(imRepo.message("imsg:M1")!!.editedAtMs > 0)
            // Her iMessage: Apple's six tapbacks, and a tap sends one.
            scenario.onActivity { activity -> activity.onMessageLongPress(rowOf(activity, "Hers")) }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val row = ShadowDialog.getLatestDialog().findViewById<android.view.ViewGroup>(R.id.reactionRow)
            assertEquals(View.VISIBLE, row.visibility)
            assertEquals(ThreadActivity.TAPBACKS, (0 until 6).map { (row.getChildAt(it) as android.widget.TextView).text.toString() })
            assertNotNull("then any emoji", row.getChildAt(6).findViewById<View>(R.id.reactionMore))
            // Not hers to edit or unsend.
            assertEquals(View.GONE, ShadowDialog.getLatestDialog().findViewById<View>(R.id.actionEdit).visibility)
            assertEquals(View.GONE, ShadowDialog.getLatestDialog().findViewById<View>(R.id.actionUnsend).visibility)
            row.getChildAt(0).performClick()
            scenario.waitFor("the tapback, sent to Apple") {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                asked.size == 2 && imRepo.message("imsg:H1")!!.reactions.any { it.fromMe && it.emoji == "❤️" }
            }
            assertEquals(com.lightdecoder.imessage.direct.DirectWire.REACT, asked[1].first)
            assertEquals("H1", asked[1].second.getString("guid"))
            assertTrue(asked[1].second.getBoolean("enable"))
            // Nothing opened OpenBubbles.
            scenario.onActivity { activity -> assertEquals(null, shadowOf(activity).nextStartedActivity) }
            assertFalse(com.lightdecoder.imessage.IMessageLog.read(two.app).any { it.contains("OpenBubbles opened") })
            // She edits hers: "Edited" by it as well, and a tap shows what it read before, from when (iOS 16).
            val editedAt = System.currentTimeMillis()
            com.lightdecoder.imessage.direct.DirectLink.get(two.app)
                .apply(com.lightdecoder.imessage.direct.DirectArchive.Update.Edit("imsg:H1", "Hers, changed", 0, editedAt, sender = "tel:+15551234567"))
            scenario.waitFor("her edit, marked") { activity ->
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                bubbleOf(activity, "Hers, changed")?.findViewById<View>(R.id.edited)?.visibility == View.VISIBLE
            }
            scenario.onActivity { activity -> bubbleOf(activity, "Hers, changed")!!.findViewById<View>(R.id.edited).performClick() }
            scenario.waitFor("what it read before") { activity ->
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                bubbleOf(activity, "Hers, changed")?.findViewById<View>(R.id.editHistory)?.visibility == View.VISIBLE
            }
            scenario.onActivity { activity ->
                val box = bubbleOf(activity, "Hers, changed")!!.findViewById<android.view.ViewGroup>(R.id.editHistory)
                assertEquals(1, box.childCount)
                assertEquals("Hers", (box.getChildAt(0) as android.widget.TextView).text.toString())
                assertEquals(
                    two.app.getString(R.string.imessage_edit_history_version, com.lightdecoder.ui.chat.TimeFormat.clock(two.app, now - 120_000), "Hers"),
                    box.getChildAt(0).contentDescription.toString(),
                )
            }
        }
    }

    @Test
    fun editingMyFormattedIMessageKeepsItsFormatting() {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        directMode(asked)
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        val now = System.currentTimeMillis()
        directMessage("H9", "Hers", now - 120_000, mine = false)
        val bold = com.lightdecoder.imessage.direct.TextRun(0, 3, bold = true)
        imRepo.upsertMessage(
            com.lightdecoder.relay.MessageDto(
                id = "imsg:M9", conversationId = ALICE_DIRECT, fromMe = true, timestampMs = now - 60_000, direction = "out",
                status = "OUTGOING_COMPLETE", transport = IMessageManager.TRANSPORT, text = "Fix this tpyo",
                parts = listOf(com.lightdecoder.relay.PartDto("0", "text", "Fix this tpyo")), isOld = true,
                extra = com.lightdecoder.imessage.direct.IMessageExtra(runs = mapOf(0 to listOf(bold))).toJson(),
            ),
        )
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("my iMessage") { shownTexts(it).contains("Fix this tpyo") }
            scenario.onActivity { activity -> activity.onMessageLongPress(rowOf(activity, "Fix this tpyo")) }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            ShadowDialog.getLatestDialog().findViewById<View>(R.id.actionEdit).performClick()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val dialog = latestDialog()
            val field = firstEditText(dialog.window!!.decorView)!!
            // Shown as sent, and the selection menu formats here too.
            assertEquals(1, field.text.getSpans(0, 3, com.lightdecoder.ui.chat.BoldMark::class.java).size)
            assertTrue(field.customSelectionActionModeCallback is com.lightdecoder.ui.chat.FormattingActionMode)
            field.text.replace(9, 13, "typo")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            scenario.waitFor("the edit, shown") { activity ->
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                bubbleOf(activity, "Fix this typo")?.findViewById<View>(R.id.edited)?.visibility == View.VISIBLE
            }
            val edit = com.lightdecoder.imessage.direct.DirectWire.EditRequest.fromJson(asked.single { it.first == com.lightdecoder.imessage.direct.DirectWire.EDIT }.second)
            assertEquals("Fix this typo", edit.text)
            assertEquals(listOf(bold), edit.runs)
            scenario.onActivity { activity ->
                val body = bubbleOf(activity, "Fix this typo")!!.findViewById<android.widget.TextView>(R.id.body)
                assertEquals(android.graphics.Typeface.BOLD, (body.text as android.text.Spanned).getSpans(0, 3, android.text.style.StyleSpan::class.java).single().style)
            }
        }
    }

    @Test
    fun withTheLibraryEveryWayToATapbackStaysHereAndALateUnsendIsTooLate() {
        two.env.pairedManager()
        installOpenBubbles()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        directMode(asked)
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        val now = System.currentTimeMillis()
        directMessage("H2", "Hers again", now - 120_000, mine = false)
        directMessage("M4", "Mine just now", now - 20_000, mine = true)
        imRepo.applyReaction("imsg:H2", "+15551234567", "👍", enable = true, fromMe = false, atMs = now - 60_000)
        fun mine() = imRepo.message("imsg:H2")!!.reactions.firstOrNull { it.fromMe }?.emoji
        fun lastReact() = asked.last().also { assertEquals(com.lightdecoder.imessage.direct.DirectWire.REACT, it.first) }.second
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("her iMessage") { shownTexts(it).contains("Hers again") }
            // A tap on her 👍 puts the user's on too.
            scenario.onActivity { activity ->
                val row = rowOf(activity, "Hers again")
                activity.onReactionTap(row, row.reactions.single())
            }
            two.waitUntil("👍 sent to Apple") { mine() == "👍" && asked.size == 1 }
            assertEquals("👍", lastReact().getString("emoji"))
            assertTrue(lastReact().getBoolean("enable"))
            // Remove reaction takes it back.
            scenario.waitFor("👍 shown") { rowOf(it, "Hers again").reactions.any { r -> r.fromMe } }
            scenario.onActivity { activity -> activity.onMessageLongPress(rowOf(activity, "Hers again")) }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val remove = ShadowDialog.getLatestDialog().findViewById<View>(R.id.actionRemoveReaction)
            assertEquals(View.VISIBLE, remove.visibility)
            remove.performClick()
            two.waitUntil("taken back at Apple") { mine() == null && asked.size == 2 }
            assertEquals("👍", lastReact().getString("emoji"))
            assertFalse(lastReact().getBoolean("enable"))
            // Any emoji from "+": sent as it is, and offered after the six tapbacks from then on.
            scenario.waitFor("taken back here") { rowOf(it, "Hers again").reactions.none { r -> r.fromMe } }
            scenario.onActivity { activity -> activity.reactionPicked(rowOf(activity, "Hers again"), "🎉", mine = null, isIMessage = true) }
            two.waitUntil("🎉 sent to Apple") { mine() == "🎉" && asked.size == 3 }
            assertEquals("🎉", lastReact().getString("emoji"))
            assertTrue(lastReact().getBoolean("enable"))
            scenario.waitFor("🎉 shown") { rowOf(it, "Hers again").reactions.any { r -> r.fromMe } }
            scenario.onActivity { activity -> activity.onMessageLongPress(rowOf(activity, "Hers again")) }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val quick = ShadowDialog.getLatestDialog().findViewById<android.view.ViewGroup>(R.id.reactionRow)
            val offered = (0 until 7).map { quick.getChildAt(it) as android.widget.TextView }
            assertEquals(ThreadActivity.TAPBACKS + "🎉", offered.map { it.text.toString() })
            assertTrue(offered.last().isSelected)
            assertNotNull("then any emoji", quick.getChildAt(7).findViewById<View>(R.id.reactionMore))
            ShadowDialog.getLatestDialog().dismiss()
            // Who reacted: by the names of the iMessage chat's people.
            scenario.onActivity { activity ->
                val row = rowOf(activity, "Hers again")
                activity.onReactionDetails(row, row.reactions.first { !it.fromMe })
            }
            two.waitUntil("the reactions' details") {
                (ShadowDialog.getLatestDialog() as? AlertDialog)?.let { two.hasText(it.window!!.decorView, "Alice") } == true
            }
            // Unsend tapped after Apple's 2 minutes (a sheet left open): too late, and nothing asked.
            scenario.onActivity { activity -> activity.unsendIMessage(rowOf(activity, "Mine just now").copy(timestampMs = now - 3 * 60_000L)) }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val tooLate = latestDialog()
            assertTrue("titled Unsend", two.hasText(tooLate.window!!.decorView, two.app.getString(R.string.imessage_action_unsend)))
            assertEquals(two.app.getString(R.string.imessage_unsend_too_late), tooLate.findViewById<android.widget.TextView>(android.R.id.message)?.text?.toString())
            assertEquals(3, asked.size)
            scenario.onActivity { activity -> assertEquals(null, shadowOf(activity).nextStartedActivity) }
            assertFalse(com.lightdecoder.imessage.IMessageLog.read(two.app).any { it.contains("OpenBubbles opened") })
        }
    }

    @Test
    fun withTheLibraryAnUnsendGoesOnWhenTheScreenClosesAndShowsNothingThere() {
        two.env.pairedManager()
        installOpenBubbles()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        // Apple's answer comes only once the screen is gone.
        val answer = java.util.concurrent.CountDownLatch(1)
        directMode(asked) { what ->
            if (what == com.lightdecoder.imessage.direct.DirectWire.UNSEND) answer.await(10, java.util.concurrent.TimeUnit.SECONDS)
            com.lightdecoder.imessage.direct.DirectWire.ok()
        }
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        directMessage("M5", "Wrong chat", System.currentTimeMillis() - 20_000, mine = true)
        val scenario = ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0))
        scenario.waitFor("my iMessage") { shownTexts(it).contains("Wrong chat") }
        scenario.onActivity { activity -> activity.onMessageLongPress(rowOf(activity, "Wrong chat")) }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        ShadowDialog.getLatestDialog().findViewById<View>(R.id.actionUnsend).performClick()
        two.waitUntil("Apple asked") { asked.isNotEmpty() }
        scenario.close()
        val shown = ShadowDialog.getLatestDialog()
        answer.countDown()
        two.waitUntil("unsent all the same") { imRepo.message("imsg:M5")?.direction == "system" }
        var rounds = 0
        two.waitUntil("the closed screen's turn") { ++rounds > 5 }
        assertTrue("no dialog for a closed screen", ShadowDialog.getLatestDialog() === shown)
        assertEquals(com.lightdecoder.imessage.direct.DirectWire.UNSEND, asked.single().first)
    }

    @Test
    fun withTheLibraryMyIMessageIsUnsentHereAndSaysSo() {
        two.env.pairedManager()
        installOpenBubbles()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        directMode(asked)
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        val now = System.currentTimeMillis()
        directMessage("M2", "Oops, wrong one", now - 30_000, mine = true)
        directMessage("M3", "An old one", now - 10 * 60_000, mine = true)
        // One OpenBubbles' notification showed before the library: no GUID for Apple to know it by.
        imRepo.upsertMessage(
            com.lightdecoder.relay.MessageDto(
                id = "ld:old", conversationId = ALICE_DIRECT, fromMe = true, timestampMs = now - 20_000, direction = "out",
                status = "OUTGOING_COMPLETE", transport = IMessageManager.TRANSPORT, text = "From OpenBubbles' days", isOld = true,
            ),
        )
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("my iMessages") { shownTexts(it).contains("Oops, wrong one") }
            // Within two minutes: unsent through the library, and "You unsent a message." takes its place.
            scenario.onActivity { activity -> activity.onMessageLongPress(rowOf(activity, "Oops, wrong one")) }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            ShadowDialog.getLatestDialog().findViewById<View>(R.id.actionUnsend).performClick()
            scenario.waitFor("the placeholder") { activity ->
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                shownTexts(activity).contains(activity.getString(R.string.imessage_you_unsent))
            }
            assertEquals(com.lightdecoder.imessage.direct.DirectWire.UNSEND, asked.single().first)
            assertEquals("M2", asked.single().second.getString("guid"))
            assertEquals("system", imRepo.message("imsg:M2")!!.direction)
            // Too late for Apple: no Unsend, but still Edit (15 minutes).
            scenario.onActivity { activity -> activity.onMessageLongPress(rowOf(activity, "An old one")) }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertEquals(View.GONE, ShadowDialog.getLatestDialog().findViewById<View>(R.id.actionUnsend).visibility)
            assertEquals(View.VISIBLE, ShadowDialog.getLatestDialog().findViewById<View>(R.id.actionEdit).visibility)
            // No GUID: no Edit, Unsend or tapbacks, and no OpenBubbles.
            scenario.onActivity { activity -> activity.onMessageLongPress(rowOf(activity, "From OpenBubbles' days")) }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val sheet = ShadowDialog.getLatestDialog()
            assertEquals(View.GONE, sheet.findViewById<View>(R.id.actionUnsend).visibility)
            assertEquals(View.GONE, sheet.findViewById<View>(R.id.actionEdit).visibility)
            assertEquals(View.GONE, sheet.findViewById<View>(R.id.reactionRow).visibility)
            scenario.onActivity { activity -> assertEquals(null, shadowOf(activity).nextStartedActivity) }
            assertEquals(1, asked.size)
        }
    }

    // --- with the iMessage library: typing, receipts, no hand-offs (phase 3) -----------------

    /** Apple's answers as [directMode] gives them, and a send's (its GUID [guid]). */
    private fun appleAnswers(guid: String = "S1"): (Int) -> org.json.JSONObject = { what ->
        when (what) {
            com.lightdecoder.imessage.direct.DirectWire.SEND -> com.lightdecoder.imessage.direct.DirectWire.sent(guid, System.currentTimeMillis())
            com.lightdecoder.imessage.direct.DirectWire.REACT -> com.lightdecoder.imessage.direct.DirectWire.guid("T-OWN")
            else -> com.lightdecoder.imessage.direct.DirectWire.ok()
        }
    }

    private fun asked(asked: List<Pair<Int, org.json.JSONObject>>, what: Int) = synchronized(asked) { asked.filter { it.first == what }.map { it.second } }

    /**
     * Apple's answers to sends as the library gives them: a new GUID each ("S1", "S2"…), its
     * attachments numbered as rustpush numbers them.
     */
    private fun appleSends(): (Int, org.json.JSONObject) -> org.json.JSONObject {
        val count = java.util.concurrent.atomic.AtomicInteger()
        return { what, request ->
            if (what != com.lightdecoder.imessage.direct.DirectWire.SEND) {
                com.lightdecoder.imessage.direct.DirectWire.ok()
            } else {
                val r = com.lightdecoder.imessage.direct.DirectWire.SendRequest.fromJson(request)
                val first = if (r.text.isEmpty()) 0 else 1
                val parts = r.attachments.mapIndexed { i, a ->
                    com.lightdecoder.imessage.direct.DirectWire.SentPart(first + i, "p${first + i}", a.mime, a.name, java.io.File(a.path).length())
                }
                com.lightdecoder.imessage.direct.DirectWire.sent("S${count.incrementAndGet()}", System.currentTimeMillis(), parts)
            }
        }
    }

    @Test
    fun withTheLibraryNothingIsHandedToOpenBubbles() {
        two.env.pairedManager()
        installOpenBubbles()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        val answer = appleSends()
        directMode(asked)
        // Apple's answers to the sends only, as the library gives them.
        com.lightdecoder.imessage.direct.DirectLink.installForTesting(two.app) { what, request, _ ->
            if (what == com.lightdecoder.imessage.direct.DirectWire.SEND) asked += what to request
            answer(what, request)
        }
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        directMessage("H1", "Hers", System.currentTimeMillis() - 60_000, mine = false)
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("her iMessage") { shownTexts(it).contains("Hers") }
            // A picture goes straight to Apple, with the text, as an iMessage.
            scenario.onActivity { activity ->
                val picture = java.io.File(activity.cacheDir, "IMG_1.jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, -32, 1, 2, 3)) }
                activity.setAttachment(android.net.Uri.fromFile(picture))
                activity.findViewById<EditText>(R.id.input).setText("Look")
                activity.findViewById<View>(R.id.send).performClick()
            }
            two.waitUntil("the picture sent through the library") { asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND).isNotEmpty() }
            val picture = com.lightdecoder.imessage.direct.DirectWire.SendRequest.fromJson(asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND).single())
            assertEquals("Look", picture.text)
            assertEquals("image/jpeg", picture.attachments.single().mime)
            assertEquals("IMG_1.jpg", picture.attachments.single().name)
            assertFalse(picture.voice)
            two.waitUntil("stored as Apple numbered it") { imRepo.message("imsg:S1")?.media?.singleOrNull()?.partId == "p1" }
            assertEquals("Look", imRepo.message("imsg:S1")!!.text)
            scenario.onActivity { activity ->
                assertEquals("", activity.findViewById<EditText>(R.id.input).text.toString())
                assertEquals(View.GONE, activity.findViewById<View>(R.id.attachmentBar).visibility)
                // A sticker: a picture of its own.
                val sticker = java.io.File(activity.cacheDir, "sticker.png").apply { writeBytes(byteArrayOf(-119, 80, 78, 71, 1, 2, 3)) }
                activity.sendStickers(listOf(sticker))
            }
            two.waitUntil("the sticker sent through the library") { asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND).size == 2 }
            val sticker = com.lightdecoder.imessage.direct.DirectWire.SendRequest.fromJson(asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND)[1])
            assertEquals("", sticker.text)
            assertEquals("image/png", sticker.attachments.single().mime)
            // A text alone too.
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.input).setText("Nice")
                activity.findViewById<View>(R.id.send).performClick()
            }
            two.waitUntil("the text sent through the library") { asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND).size == 3 }
            val text = com.lightdecoder.imessage.direct.DirectWire.SendRequest.fromJson(asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND)[2])
            assertEquals("Nice", text.text)
            assertTrue(text.attachments.isEmpty())
            scenario.onActivity { activity -> assertEquals(null, shadowOf(activity).nextStartedActivity) }
            val log = com.lightdecoder.imessage.IMessageLog.read(two.app)
            assertTrue(log.joinToString("\n"), log.any { it.contains("sent as iMessage, 1 file(s), through the iMessage library") })
            assertFalse(log.joinToString("\n"), log.any { it.contains("OpenBubbles") })
        }
    }

    @Test
    fun withTheLibraryAnIMessageChatsMenuLeadsToTheIMessageScreenNotOpenBubbles() {
        two.env.pairedManager()
        installOpenBubbles()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        directMode(asked, answer = appleAnswers())
        directMessage("H1", "Hers", System.currentTimeMillis() - 60_000, mine = false)
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, ALICE_DIRECT, service = ChatService.IMESSAGE).setFlags(0)).use { scenario ->
            scenario.waitFor("her iMessage") { shownTexts(it).contains("Hers") }
            scenario.onActivity { activity ->
                assertFalse(menu(activity).findItem(R.id.action_open_openbubbles).isVisible)
                activity.onOptionsItemSelected(org.robolectric.fakes.RoboMenuItem(R.id.action_open_openbubbles))
                assertEquals("nothing opened", null, shadowOf(activity).nextStartedActivity)
                activity.onOptionsItemSelected(org.robolectric.fakes.RoboMenuItem(R.id.action_link_status))
                assertEquals(
                    com.lightdecoder.ui.imessage.DirectSetupActivity::class.java.name,
                    shadowOf(activity).nextStartedActivity.component?.className,
                )
                // Its light: blue, through the library.
                assertEquals(com.lightdecoder.ui.chat.IMessageLight.State.ACTIVE, light(activity).second)
            }
        }
    }

    @Test
    fun withTheLibraryItReadsAsOnAnIphone() {
        com.lightdecoder.reactions.ReadTimes.resetForTesting()
        try {
            two.env.pairedManager()
            IMessagePrefs.setEnabled(two.app, true)
            val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
            directMode(asked, notices = true, answer = appleAnswers())
            two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
            val now = System.currentTimeMillis()
            directMessage("H1", "Hers", now - 300_000, mine = false)
            directMessage("M1", "Mine, read", now - 200_000, mine = true)
            directMessage("M2", "Mine, delivered", now - 100_000, mine = true)
            val rank = com.lightdecoder.imessage.direct.DirectArchive::statusRank
            imRepo.advanceMessageStatus(listOf("imsg:M1"), com.lightdecoder.imessage.direct.DirectArchive.STATUS_READ, 11, rank)
            imRepo.advanceMessageStatus(listOf("imsg:M2"), com.lightdecoder.imessage.direct.DirectArchive.STATUS_DELIVERED, 2, rank)
            val readAt = now - 150_000
            com.lightdecoder.reactions.ReadTimes.get(two.app).note(ChatService.IMESSAGE, "imsg:M1", readAt, live = true)
            ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
                // "Read 9:41" under the one read, "Delivered" under the later one; no ticks on either.
                val read = two.app.getString(R.string.imessage_receipt_read_at, com.lightdecoder.ui.chat.TimeFormat.receiptTime(two.app, readAt))
                fun footer(activity: Activity, text: String) =
                    bubbleOf(activity, text)?.findViewById<android.widget.TextView>(R.id.receipt)?.takeIf { it.visibility == View.VISIBLE }?.text?.toString()
                scenario.waitFor("the receipts") { activity ->
                    footer(activity, "Mine, read") == read && footer(activity, "Mine, delivered") == two.app.getString(R.string.imessage_receipt_delivered)
                }
                scenario.onActivity { activity ->
                    assertEquals(View.GONE, bubbleOf(activity, "Mine, read")!!.findViewById<View>(R.id.statusIcon).visibility)
                    assertEquals(View.GONE, bubbleOf(activity, "Mine, delivered")!!.findViewById<View>(R.id.statusIcon).visibility)
                    assertEquals(null, footer(activity, "Hers"))
                }
                // Opened: Apple is told her message was read (the setting decides whether she is).
                two.waitUntil("the read receipt") { asked(asked, com.lightdecoder.imessage.direct.DirectWire.READ).any { it.getString("guid") == "H1" } }
                // She types: shown above the composer (an iPhone-style chat: as a bubble with three dots,
                // the words for TalkBack), until she stops.
                iMessage.noteTyping(ALICE_DIRECT, "+15551234567", "Alice", typing = true)
                scenario.waitFor("her typing") { activity ->
                    val typing = activity.findViewById<View>(R.id.typingDots)
                    typing.visibility == View.VISIBLE && typing.contentDescription?.toString() == activity.getString(R.string.chat_typing_one, "Alice")
                }
                scenario.onActivity { activity -> assertEquals(View.GONE, activity.findViewById<View>(R.id.typingText).visibility) }
                iMessage.noteTyping(ALICE_DIRECT, "+15551234567", "Alice", typing = false)
                scenario.waitFor("her typing gone") { it.findViewById<View>(R.id.typingDots).visibility == View.GONE }
                // The user types: her iPhone is told, and told it stopped when the composer is emptied.
                scenario.onActivity { activity -> activity.findViewById<EditText>(R.id.input).setText("H") }
                two.waitUntil("typing told") { asked(asked, com.lightdecoder.imessage.direct.DirectWire.TYPING).map { it.getBoolean("typing") } == listOf(true) }
                scenario.onActivity { activity -> activity.findViewById<EditText>(R.id.input).setText("") }
                two.waitUntil("stopped told") { asked(asked, com.lightdecoder.imessage.direct.DirectWire.TYPING).map { it.getBoolean("typing") } == listOf(true, false) }
                // A new message from her while the conversation is open: read too.
                directMessage("H2", "Hers again", System.currentTimeMillis(), mine = false)
                two.waitUntil("its read receipt") { asked(asked, com.lightdecoder.imessage.direct.DirectWire.READ).any { it.getString("guid") == "H2" } }
                assertEquals("once per message", 1, asked(asked, com.lightdecoder.imessage.direct.DirectWire.READ).count { it.getString("guid") == "H1" })
            }
        } finally {
            com.lightdecoder.reactions.ReadTimes.resetForTesting()
        }
    }

    @Test
    fun withTheLibraryASendThatMayHaveGoneAsksBeforeItGoesAgain() {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        directMode(asked, answer = appleAnswers())
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        val now = System.currentTimeMillis()
        directMessage("H1", "Hers", now - 60_000, mine = false)
        // LightDecoder stopped while this was being sent.
        imRepo.insertLocalOutgoing("t-cut", ALICE_DIRECT, "Cut short", null, null, null, null, now - 30_000)
        imRepo.setLocalState("t-cut", com.lightdecoder.archive.LocalState.FAILED, com.lightdecoder.relay.Outbox.INTERRUPTED)
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("the message cut short") { shownTexts(it).contains("Cut short") }
            scenario.onActivity { activity -> activity.onFailedTap(rowOf(activity, "Cut short")) }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            // Not delivered, as an iPhone says it: what can be done, with a word that it may have gone.
            val sheet = ShadowDialog.getLatestDialog() as com.google.android.material.bottomsheet.BottomSheetDialog
            assertEquals(View.VISIBLE, sheet.findViewById<View>(R.id.notDeliveredNote)!!.visibility)
            assertTrue("nothing sent from the sheet alone", asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND).isEmpty())
            assertTrue(sheet.findViewById<View>(R.id.actionTryAgain)!!.performClick())
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val question = latestDialog()
            assertEquals(two.app.getString(R.string.imessage_resend_body_direct), question.findViewById<android.widget.TextView>(android.R.id.message)?.text?.toString())
            assertTrue("nothing sent before the answer", asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND).isEmpty())
            question.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            two.waitUntil("sent again, through the library") { asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND).isNotEmpty() }
            assertEquals("Cut short", asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND).single().getString("text"))
            scenario.onActivity { activity -> assertEquals(null, shadowOf(activity).nextStartedActivity) }
        }
    }

    @Test
    fun withTheLibraryThePersonsOlderIMessageChatIsIMessageToo() {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        directMode(asked, answer = appleAnswers())
        // Another iMessage chat with Alice, from before: shown in the same conversation.
        val older = "iMessage;-;+15551234567"
        imRepo.upsertConversation(
            com.lightdecoder.relay.ConversationDto(
                id = older, type = IMessageManager.TRANSPORT,
                participants = listOf(
                    com.lightdecoder.relay.ParticipantDto(id = "me", isMe = true),
                    com.lightdecoder.relay.ParticipantDto(id = "+15551234567", name = "Alice", number = "+15551234567", formattedNumber = "+15551234567"),
                ),
            ),
        )
        val now = System.currentTimeMillis()
        imRepo.upsertMessage(
            com.lightdecoder.relay.MessageDto(
                id = "imsg:OLD1", conversationId = older, senderId = "+15551234567", senderName = "Alice", timestampMs = now - 600_000,
                direction = "in", status = "INCOMING_COMPLETE", transport = IMessageManager.TRANSPORT, text = "From the older chat",
                parts = listOf(com.lightdecoder.relay.PartDto("0", "text", "From the older chat")), isOld = true,
            ),
        )
        directMessage("H1", "In the newer chat", now - 60_000, mine = false)
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("both chats' iMessages") { shownTexts(it).containsAll(listOf("From the older chat", "In the newer chat")) }
            // Each is an iMessage: Apple's tapbacks, sent through the library.
            for ((text, guid) in listOf("From the older chat" to "OLD1", "In the newer chat" to "H1")) {
                scenario.onActivity { activity -> activity.onMessageLongPress(rowOf(activity, text)) }
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                val row = ShadowDialog.getLatestDialog().findViewById<android.view.ViewGroup>(R.id.reactionRow)
                assertEquals(text, ThreadActivity.TAPBACKS, (0 until 6).map { (row.getChildAt(it) as android.widget.TextView).text.toString() })
                row.getChildAt(0).performClick()
                two.waitUntil("the tapback on \"$text\"") { asked(asked, com.lightdecoder.imessage.direct.DirectWire.REACT).any { it.getString("guid") == guid } }
            }
        }
    }

    @Test
    fun withTheLibrarySendIMessageAsksAppleAndSaysWhenTheyAreNotOnIMessage() {
        two.env.pairedManager()
        installOpenBubbles()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        directMode(asked) { what ->
            if (what == com.lightdecoder.imessage.direct.DirectWire.REACH) org.json.JSONObject() else com.lightdecoder.imessage.direct.DirectWire.ok()
        }
        two.seedConversation(ChatService.MESSAGES, "c2", "Bob", lastTs = 1_000, number = "+1 555-987-6543")
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c2").setFlags(0)).use { scenario ->
            scenario.waitFor("the conversation") { it.findViewById<View>(R.id.input).isEnabled }
            // Opened: Apple is asked (as an iPhone does), and says not.
            two.waitUntil("asked on opening") { asked(asked, com.lightdecoder.imessage.direct.DirectWire.REACH).size == 1 }
            assertEquals("+1 555-987-6543", asked(asked, com.lightdecoder.imessage.direct.DirectWire.REACH).single().getString("recipient"))
            val notOn = two.app.getString(R.string.imessage_not_on_imessage, "+1 555-987-6543")
            scenario.onActivity { activity ->
                assertTrue(menu(activity).findItem(R.id.action_send_imessage).isVisible)
                activity.onOptionsItemSelected(org.robolectric.fakes.RoboMenuItem(R.id.action_send_imessage))
            }
            two.waitUntil("Apple's answer") { org.robolectric.shadows.ShadowToast.getTextOfLatestToast() == notOn }
            assertEquals("asked again when the user asks", 2, asked(asked, com.lightdecoder.imessage.direct.DirectWire.REACH).size)
            // The check says what it does, and asks Apple too.
            scenario.onActivity { activity -> activity.onOptionsItemSelected(org.robolectric.fakes.RoboMenuItem(R.id.action_check_imessage)) }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val explain = latestDialog().findViewById<android.widget.TextView>(android.R.id.message)?.text?.toString().orEmpty()
            assertTrue(explain, explain.endsWith(two.app.getString(R.string.imessage_check_new_chat_direct)))
            latestDialog().dismiss()
            org.robolectric.shadows.ShadowToast.reset()
            scenario.onActivity { activity -> activity.startIMessageCheck(com.lightdecoder.imessage.IMessageCheck.TEXT) }
            two.waitUntil("Apple's answer to the check") { org.robolectric.shadows.ShadowToast.getTextOfLatestToast() == notOn }
            assertEquals(3, asked(asked, com.lightdecoder.imessage.direct.DirectWire.REACH).size)
            assertTrue("nothing sent", asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND).isEmpty())
            scenario.onActivity { activity -> assertEquals("OpenBubbles not opened", null, shadowOf(activity).nextStartedActivity) }
        }
    }

    @Test
    fun withTheLibraryTheCheckGoesFromHereAndAppleConfirmsIt() {
        two.env.pairedManager()
        installOpenBubbles()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        directMode(asked, answer = appleAnswers("CHK1"))
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        directMessage("H1", "Hers", System.currentTimeMillis() - 60_000, mine = false)
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("her iMessage") { shownTexts(it).contains("Hers") }
            scenario.onActivity { activity -> activity.startIMessageCheck(com.lightdecoder.imessage.IMessageCheck.TEXT) }
            scenario.waitFor("the check dialog") { it.checkDialog != null }
            val sent = "\u2713  " + two.app.getString(R.string.imessage_check_step_sent_direct)
            val confirmed = "\u2713  " + two.app.getString(R.string.imessage_check_step_confirmed_direct)
            scenario.waitFor("sent, and confirmed by Apple") { steps(it).lines().take(2) == listOf(sent, confirmed) }
            assertEquals(com.lightdecoder.imessage.IMessageCheck.TEXT, asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND).single().getString("text"))
            scenario.onActivity { activity -> assertEquals(null, shadowOf(activity).nextStartedActivity) }
            val log = com.lightdecoder.imessage.IMessageLog.read(two.app)
            assertTrue(log.joinToString("\n"), log.any { it.contains("step 1 sent from LightDecoder") })
            assertFalse(log.joinToString("\n"), log.any { it.contains("OpenBubbles") })
        }
    }

    @Test
    fun withTheLibraryTickingBlueBubblesNeedsNeitherOpenBubblesNorNotificationAccess() {
        two.env.pairedManager()
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        directMode(asked)
        // Turned on once when the library was registered, and off by the user since.
        com.lightdecoder.imessage.direct.DirectPrefs.setBlueBubblesTurnedOn(two.app)
        IMessagePrefs.setEnabled(two.app, false)
        two.seedConversation(ChatService.MESSAGES, "c1", "Bob")
        launchList().use { scenario ->
            scenario.waitFor("the Messages inbox") { titles(it).contains("Bob") }
            scenario.onActivity { activity -> activity.onOptionsItemSelected(menu(activity).findItem(R.id.action_blue_bubbles)) }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertTrue(IMessagePrefs.enabled(two.app))
            assertEquals(two.app.getString(R.string.blue_bubbles_on_body_direct), latestDialog().findViewById<android.widget.TextView>(android.R.id.message)?.text?.toString())
            scenario.onActivity { activity -> assertEquals("no Play Store, no settings", null, shadowOf(activity).nextStartedActivity) }
        }
    }

    @Test
    fun theIMessageScreenHasTheReadReceiptsSwitchOffAtFirst() {
        ActivityScenario.launch(com.lightdecoder.ui.imessage.DirectSetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val switch = activity.findViewById<android.widget.CompoundButton>(R.id.switchReadReceipts)
                assertFalse(switch.isChecked)
                switch.performClick()
                assertTrue(com.lightdecoder.imessage.direct.DirectPrefs.sendReadReceipts(activity))
                switch.performClick()
                assertFalse(com.lightdecoder.imessage.direct.DirectPrefs.sendReadReceipts(activity))
            }
        }
    }

    @Test
    fun theIMessageScreenTakesOnlyAnHttpsRelayServer() {
        val prefs = com.lightdecoder.imessage.direct.DirectPrefs.prefs(two.app)
        prefs.edit().clear().commit()
        ActivityScenario.launch(com.lightdecoder.ui.imessage.DirectSetupActivity::class.java).use { scenario ->
            for ((typed, why) in listOf(
                "http://registration-relay.beeper.com" to R.string.imessage_relay_host_not_https,
                "https://me:secret@relay.example.com" to R.string.imessage_relay_host_credentials,
                "https://relay..example.com" to R.string.imessage_relay_host_bad_host,
            )) {
                scenario.onActivity { activity ->
                    activity.findViewById<EditText>(R.id.relayCode).setText("ABCD-1234")
                    activity.findViewById<EditText>(R.id.relayHost).setText(typed)
                    // Whatever the library's state here (it is not imported in this test), the address is checked first.
                    activity.findViewById<View>(R.id.register).apply { isEnabled = true }.performClick()
                }
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                assertEquals(typed, two.app.getString(why), latestDialog().findViewById<android.widget.TextView>(android.R.id.message)?.text?.toString())
                latestDialog().dismiss()
                scenario.onActivity { activity -> assertEquals(two.app.getString(why), activity.findViewById<EditText>(R.id.relayHost).error?.toString()) }
                // Nothing kept, nothing started.
                assertNull(prefs.getString(com.lightdecoder.imessage.direct.DirectPrefs.KEY_HOST, null))
                assertNull(prefs.getString(com.lightdecoder.imessage.direct.DirectPrefs.KEY_CODE, null))
            }
        }
    }

    private val openPigeon = com.lightdecoder.imessage.madrid.MadridApps.OPENPIGEON

    private fun installOpenPigeon(verified: Boolean) {
        shadowOf(two.app.packageManager).installPackage(
            PackageInfo().apply {
                packageName = openPigeon.pkg
                lastUpdateTime = if (verified) 1 else 2
                applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = openPigeon.pkg }
            },
        )
        com.lightdecoder.imessage.madrid.MadridApps.verifier = { _, _ -> verified }
    }

    private fun gamesStatus(activity: Activity) = activity.findViewById<android.widget.TextView>(R.id.gamesStatus)

    @Test
    fun theIMessageScreenHasIMessageGamesOnAtFirstAndOffersOpenPigeon() {
        assertTrue(com.lightdecoder.imessage.direct.DirectPrefs.gamesOn(two.app))
        ActivityScenario.launch(com.lightdecoder.ui.imessage.DirectSetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val switch = activity.findViewById<android.widget.CompoundButton>(R.id.switchGames)
                assertTrue(switch.isChecked)
                assertEquals("iMessage games", switch.text.toString())
                // Not installed: said, with "Get games", which opens its store page.
                assertEquals(View.VISIBLE, gamesStatus(activity).visibility)
                assertEquals("OpenPigeon is not installed.", gamesStatus(activity).text.toString())
                val get = activity.findViewById<android.widget.Button>(R.id.getGames)
                assertEquals(View.VISIBLE, get.visibility)
                assertEquals("Get games", get.text.toString())
                get.performClick()
                val opened = shadowOf(activity).nextStartedActivity
                assertEquals(Intent.ACTION_VIEW, opened.action)
                assertEquals("market://details?id=com.openbubbles.openpigeon", opened.dataString)
                // Off: kept, and neither is shown.
                switch.performClick()
                assertFalse(com.lightdecoder.imessage.direct.DirectPrefs.gamesOn(activity))
                assertEquals(View.GONE, gamesStatus(activity).visibility)
                assertEquals(View.GONE, get.visibility)
            }
        }
        // Still off when the screen opens again; on again from there.
        ActivityScenario.launch(com.lightdecoder.ui.imessage.DirectSetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val switch = activity.findViewById<android.widget.CompoundButton>(R.id.switchGames)
                assertFalse(switch.isChecked)
                assertEquals(View.GONE, gamesStatus(activity).visibility)
                switch.performClick()
                assertTrue(com.lightdecoder.imessage.direct.DirectPrefs.gamesOn(activity))
                assertEquals(View.VISIBLE, gamesStatus(activity).visibility)
            }
        }
    }

    @Test
    fun withNoStoreAppGetGamesOpensThePlayStoreOnTheWeb() {
        shadowOf(two.app).checkActivities(true)
        val browser = ComponentName("com.example.browser", "com.example.browser.Main")
        shadowOf(two.app.packageManager).addActivityIfNotPresent(browser)
        shadowOf(two.app.packageManager).addIntentFilterForActivity(
            browser,
            android.content.IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("https")
            },
        )
        ActivityScenario.launch(com.lightdecoder.ui.imessage.DirectSetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.getGames).performClick()
                assertEquals("https://play.google.com/store/apps/details?id=com.openbubbles.openpigeon", shadowOf(activity).nextStartedActivity.dataString)
            }
        }
    }

    @Test
    fun theIMessageScreenSaysWhenGamesAreReadyOnceOpenPigeonIsInstalled() {
        ActivityScenario.launch(com.lightdecoder.ui.imessage.DirectSetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertEquals("OpenPigeon is not installed.", gamesStatus(activity).text.toString()) }
            // Installed from the store, and back to the screen.
            installOpenPigeon(verified = true)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertEquals("Ready. Tap a game in a chat, or choose Games when adding a picture.", gamesStatus(activity).text.toString())
                assertEquals(View.GONE, activity.findViewById<View>(R.id.getGames).visibility)
            }
            // A copy signed by someone else.
            installOpenPigeon(verified = false)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertEquals(two.app.getString(R.string.imessage_app_unverified, "OpenPigeon"), gamesStatus(activity).text.toString())
                assertEquals(View.GONE, activity.findViewById<View>(R.id.getGames).visibility)
            }
        }
    }

    /** Alice's Messages conversation with her iMessage chat through the library, and her game's card in it. */
    private fun gameChat() {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        directMode(java.util.Collections.synchronizedList(ArrayList()))
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        val game = com.lightdecoder.imessage.direct.IMessageExtra.App(
            name = "GamePigeon", appId = 1124197642L, url = "data:state", ldText = "8 Ball", imageTitle = "8 Ball", caption = "Your move", root = "imsg:G1",
            session = "0f8fad5b-d9cb-469f-a165-70867728950e",
        )
        directMessageWith("G1", "Your move", System.currentTimeMillis() - 60_000, com.lightdecoder.imessage.direct.IMessageExtra(app = game))
    }

    @Test
    fun withIMessageGamesOffAddingAPictureOpensThePickerAndAGameOpensOnlyOnAnIphone() {
        gameChat()
        com.lightdecoder.imessage.direct.DirectPrefs.setGamesOn(two.app, false)
        // OpenPigeon is here, and is not used.
        installOpenPigeon(verified = true)
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("her game") { shownTexts(it).contains("Your move") }
            scenario.onActivity { activity ->
                // An iPhone-style chat's "+" offers pictures, stickers and a poll: no Games among them.
                activity.findViewById<View>(R.id.attach).performClick()
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                val choice = latestDialog()
                assertEquals(
                    "no Games choice",
                    listOf("Photos and videos", "Stickers", "Poll"),
                    (0 until choice.listView.adapter.count).map { choice.listView.adapter.getItem(it).toString() },
                )
                choice.listView.performItemClick(null, 0, 0)
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                val before = ShadowDialog.getLatestDialog()
                assertNotNull("the photo picker", shadowOf(activity).nextStartedActivity)
                // Her game's card: not opened in OpenPigeon, nothing bound; it opens on an Apple device.
                appCardOf(activity, "Your move")!!.findViewById<View>(R.id.bubble).performClick()
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                assertEquals("GamePigeon can only be opened on an iPhone, iPad or Mac.", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
                assertTrue("no install offer", ShadowDialog.getLatestDialog() === before)
                assertEquals(null, shadowOf(activity).nextStartedActivity)
                assertTrue("nothing bound", shadowOf(two.app).boundServiceConnections.isEmpty())
            }
        }
    }

    @Test
    fun withIMessageGamesOnAndNoOpenPigeonGamesOffersToInstallIt() {
        gameChat()
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("her game") { shownTexts(it).contains("Your move") }
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.attach).performClick()
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                val choice = latestDialog()
                val list = choice.listView
                // (Stickers too: in an iPhone-style chat the "+" is the way to them; and a poll, in a chat the library reaches.)
                assertEquals(
                    listOf("Photos and videos", "Games", "Stickers", "Poll"),
                    (0 until list.adapter.count).map { list.adapter.getItem(it).toString() },
                )
                assertEquals(null, shadowOf(activity).nextStartedActivityForResult)
                list.performItemClick(null, 1, 1)
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                val install = latestDialog()
                assertTrue(install !== choice)
                assertEquals(
                    "Install OpenPigeon to play GamePigeon games with iPhone users.",
                    install.findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString(),
                )
                install.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                assertEquals("market://details?id=com.openbubbles.openpigeon", shadowOf(activity).nextStartedActivity.dataString)
                assertTrue("nothing bound", shadowOf(two.app).boundServiceConnections.isEmpty())
            }
        }
    }

    @Test
    fun withTheLibraryEveryKeystrokeCountsForWhenTheUserStoppedTyping() {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        // "Typing" told on every keystroke the library hears, and never "stopped" by a pause.
        directMode(asked, notices = true, typingRepeatMs = 0, typingIdleMs = 600_000, answer = appleAnswers())
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        directMessage("M1", "Mine", System.currentTimeMillis() - 60_000, mine = true)
        fun typings() = asked(asked, com.lightdecoder.imessage.direct.DirectWire.TYPING).map { it.getBoolean("typing") }
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("my iMessage") { shownTexts(it).contains("Mine") }
            // Three keystrokes within a second: the library hears each (its pause counts from the last).
            scenario.onActivity { activity ->
                val input = activity.findViewById<EditText>(R.id.input)
                input.setText("H")
                input.setText("He")
                input.setText("Hey")
            }
            two.waitUntil("every keystroke heard") { typings() == listOf(true, true, true) }
            scenario.onActivity { activity -> activity.findViewById<EditText>(R.id.input).setText("") }
            two.waitUntil("stopped told") { typings().lastOrNull() == false }
        }
    }

    /** A recorder that has a short m4a at once, and remembers the longest recording it was allowed. */
    private class FakeVoiceRecorder : com.lightdecoder.ui.chat.VoiceRecorder {
        var file: java.io.File? = null
        var maxDurationMs = 0
        override var onMaxDuration: (() -> Unit)? = null
        override fun start(file: java.io.File, maxDurationMs: Int): Boolean {
            file.parentFile?.mkdirs()
            file.writeBytes(byteArrayOf(0, 0, 0, 0x18) + "ftypM4A voice".toByteArray())
            this.file = file
            this.maxDurationMs = maxDurationMs
            return true
        }
        override fun stop(): Boolean = file?.isFile == true
        override fun cancel() {
            file?.delete()
        }
    }

    /** [block] with [FakeVoiceRecorder] as the app's recorder, on a phone with a microphone the app may use. */
    private fun withRecorder(block: (FakeVoiceRecorder) -> Unit) {
        val default = com.lightdecoder.ui.chat.VoiceRecorder.factory
        val recorder = FakeVoiceRecorder()
        com.lightdecoder.ui.chat.VoiceRecorder.factory = { recorder }
        shadowOf(two.app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_MICROPHONE, true)
        shadowOf(two.app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        try {
            block(recorder)
        } finally {
            com.lightdecoder.ui.chat.VoiceRecorder.factory = default
        }
    }

    @Test
    fun withTheLibraryAVoiceMessageToAnIMessageChatGoesAsAnIphonesVoiceMessage() = withRecorder { recorder ->
        val (_, fake) = two.env.pairedManager()
        installOpenBubbles()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        directMode(asked, answer = appleAnswers())
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        directMessage("H1", "Hers", System.currentTimeMillis() - 60_000, mine = false)
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("her iMessage and the record button") {
                shownTexts(it).contains("Hers") && it.findViewById<View>(R.id.voiceMessage).isShown && it.findViewById<View>(R.id.voiceMessage).isEnabled
            }
            scenario.onActivity { it.findViewById<View>(R.id.voiceMessage).performClick() }
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
            scenario.onActivity { it.findViewById<View>(R.id.recordingSend).performClick() }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            // As an iPhone's voice message, straight to Apple: not as a text, not through OpenBubbles.
            two.waitUntil("the voice message sent through the library") { asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND).isNotEmpty() }
            val voice = com.lightdecoder.imessage.direct.DirectWire.SendRequest.fromJson(asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND).single())
            assertTrue(voice.voice)
            assertEquals("", voice.text)
            assertEquals("audio/mp4", voice.attachments.single().mime)
            assertEquals("Audio Message.m4a", voice.attachments.single().name)
            scenario.onActivity { activity -> assertEquals(null, shadowOf(activity).nextStartedActivity) }
            assertTrue("no text sent instead", fake.sent.isEmpty())
            assertFalse(com.lightdecoder.imessage.IMessageLog.read(two.app).any { it.contains("OpenBubbles") })
            // As long as an iPhone's may be.
            assertEquals(10 * 60 * 1000, recorder.maxDurationMs)
        }
    }

    @Test
    fun withoutTheLibraryAVoiceMessageToAnIMessageChatKeepsTheConversationsLimit() = withRecorder { recorder ->
        two.env.pairedManager()
        installOpenBubbles()
        IMessagePrefs.setEnabled(two.app, true)
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        seedIMessage("An iMessage", ts = 5_000)
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("her iMessage and the record button") {
                shownTexts(it).contains("An iMessage") && it.findViewById<View>(R.id.voiceMessage).isShown && it.findViewById<View>(R.id.voiceMessage).isEnabled
            }
            scenario.onActivity { it.findViewById<View>(R.id.voiceMessage).performClick() }
            // OpenBubbles sends it: the Messages conversation's limit (an MMS), as before the library.
            assertEquals(3 * 60 * 1000, recorder.maxDurationMs)
        }
    }

    @Test
    fun withTheLibraryAnIMessageChatsOwnScreenSendsVoiceMessagesPicturesAndStickers() = withRecorder { recorder ->
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        directMode(asked)
        val answer = appleSends()
        com.lightdecoder.imessage.direct.DirectLink.installForTesting(two.app) { what, request, _ ->
            if (what == com.lightdecoder.imessage.direct.DirectWire.SEND) asked += what to request
            answer(what, request)
        }
        directMessage("H1", "Hers", System.currentTimeMillis() - 60_000, mine = false)
        fun sends() = asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND).map(com.lightdecoder.imessage.direct.DirectWire.SendRequest::fromJson)
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, ALICE_DIRECT, service = ChatService.IMESSAGE).setFlags(0)).use { scenario ->
            scenario.waitFor("her iMessage and the record button") {
                shownTexts(it).contains("Hers") && it.findViewById<View>(R.id.voiceMessage).isShown && it.findViewById<View>(R.id.voiceMessage).isEnabled
            }
            // A voice message, as long as an iPhone's may be, sent as one.
            scenario.onActivity { it.findViewById<View>(R.id.voiceMessage).performClick() }
            assertEquals(10 * 60 * 1000, recorder.maxDurationMs)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
            scenario.onActivity { it.findViewById<View>(R.id.recordingSend).performClick() }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            two.waitUntil("the voice message sent") { sends().size == 1 }
            val voice = sends()[0]
            assertTrue(voice.voice)
            assertEquals("audio/mp4", voice.attachments.single().mime)
            assertEquals("Audio Message.m4a", voice.attachments.single().name)
            // A picture with a caption.
            scenario.onActivity { activity ->
                val picture = java.io.File(activity.cacheDir, "IMG_2.jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, -32, 4, 5, 6)) }
                activity.setAttachment(android.net.Uri.fromFile(picture))
                activity.findViewById<EditText>(R.id.input).setText("Look")
                activity.findViewById<View>(R.id.send).performClick()
            }
            two.waitUntil("the picture sent") { sends().size == 2 }
            val picture = sends()[1]
            assertEquals("Look", picture.text)
            assertFalse(picture.voice)
            assertEquals("image/jpeg", picture.attachments.single().mime)
            assertEquals("IMG_2.jpg", picture.attachments.single().name)
            scenario.onActivity { activity ->
                assertEquals("", activity.findViewById<EditText>(R.id.input).text.toString())
                assertEquals(View.GONE, activity.findViewById<View>(R.id.attachmentBar).visibility)
                // A sticker: a picture of its own.
                val sticker = java.io.File(activity.cacheDir, "sticker.png").apply { writeBytes(byteArrayOf(-119, 80, 78, 71, 1, 2, 3)) }
                activity.sendStickers(listOf(sticker))
            }
            two.waitUntil("the sticker sent") { sends().size == 3 }
            val sticker = sends()[2]
            assertEquals("", sticker.text)
            assertEquals("image/png", sticker.attachments.single().mime)
            // All stored as Apple numbered them, nothing handed to another app.
            two.waitUntil("all three stored") { imRepo.message("imsg:S3") != null }
            assertEquals("p0", imRepo.message("imsg:S1")!!.media.single().partId)
            assertEquals("p1", imRepo.message("imsg:S2")!!.media.single().partId)
            assertEquals("p0", imRepo.message("imsg:S3")!!.media.single().partId)
            scenario.onActivity { activity -> assertEquals(null, shadowOf(activity).nextStartedActivity) }
        }
    }

    @Test
    fun withTheLibraryAVoiceMessageThisPhoneCannotPlayAndADocumentOpenInAnotherApp() {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        directMode(asked)
        // An iPhone's voice message in a codec Android cannot play (Apple Lossless), and a PDF.
        fun chunk(type: String, body: ByteArray) = java.nio.ByteBuffer.allocate(12 + body.size).put(type.toByteArray()).putLong(body.size.toLong()).put(body).array()
        val desc = java.nio.ByteBuffer.allocate(32).putDouble(44100.0).put("alac".toByteArray()).putInt(0).putInt(0).putInt(4096).putInt(1).putInt(16).array()
        val alac = "caff".toByteArray() + byteArrayOf(0, 1, 0, 0) + chunk("desc", desc) + chunk("data", ByteArray(4) + ByteArray(16) { 3 })
        val pdf = "%PDF-1.4 plan".toByteArray()
        com.lightdecoder.imessage.direct.DirectLink.installForTesting(two.app) { what, request, _ ->
            asked += what to request
            if (what == com.lightdecoder.imessage.direct.DirectWire.DOWNLOAD) {
                val bytes = if (request.getString("descriptor") == "DESC-V1") alac else pdf
                java.io.File(request.getString("path")).apply { parentFile?.mkdirs(); writeBytes(bytes) }
                com.lightdecoder.imessage.direct.DirectWire.downloaded(bytes.size.toLong())
            } else {
                com.lightdecoder.imessage.direct.DirectWire.ok()
            }
        }
        val now = System.currentTimeMillis()
        fun incoming(guid: String, ts: Long, mime: String, name: String, extra: String = "") = imRepo.upsertMessage(
            com.lightdecoder.relay.MessageDto(
                id = "imsg:$guid", conversationId = ALICE_DIRECT, senderId = "+15551234567", senderName = "Alice", timestampMs = ts,
                direction = "in", status = "INCOMING_COMPLETE", transport = IMessageManager.TRANSPORT, isOld = true, extra = extra,
                parts = listOf(com.lightdecoder.relay.PartDto("p0", "media", mediaId = "$guid/p0", key = "DESC-$guid".toByteArray(), mime = mime, fileName = name, size = 30)),
            ),
        )
        directMessage("H1", "Hers", now - 90_000, mine = false)
        incoming("V1", now - 60_000, "audio/x-caf", "Audio Message.caf", com.lightdecoder.imessage.direct.IMessageExtra(voice = true).toJson())
        incoming("D1", now - 30_000, "application/pdf", "Plan.pdf")
        // The other app offered to open it: what it is handed, and that it can read it.
        fun offered(activity: ThreadActivity, mime: String, bytes: ByteArray) {
            val chooser = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_CHOOSER, chooser?.action)
            @Suppress("DEPRECATION")
            val view = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_VIEW, view.action)
            assertEquals(mime, view.type)
            assertEquals("${two.app.packageName}.fileprovider", view.data!!.authority)
            assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertTrue(two.app.contentResolver.openInputStream(view.data!!)!!.use { it.readBytes() }.contentEquals(bytes))
        }
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, ALICE_DIRECT, service = ChatService.IMESSAGE).setFlags(0)).use { scenario ->
            scenario.waitFor("her messages") { shownTexts(it).contains("Hers") }
            org.robolectric.shadows.ShadowToast.reset()
            scenario.onActivity { a -> a.onAudioTap(imRepo.message("imsg:V1")!!, imRepo.mediaPart("imsg:V1", "p0")!!) }
            scenario.waitFor("the voice message downloaded") { imRepo.mediaPart("imsg:V1", "p0")!!.localPath.isNotEmpty() }
            scenario.waitFor("another app offered for it") { shadowOf(it).peekNextStartedActivity() != null }
            scenario.onActivity { a -> offered(a, "audio/x-caf", alac) }
            assertFalse(
                "not said to be missing",
                org.robolectric.shadows.ShadowToast.shownToastCount() > 0 &&
                    org.robolectric.shadows.ShadowToast.getTextOfLatestToast() == two.app.getString(R.string.chat_media_unavailable),
            )
            scenario.onActivity { a -> a.onMediaTap(imRepo.message("imsg:D1")!!, imRepo.mediaPart("imsg:D1", "p0")!!) }
            scenario.waitFor("another app offered for the document") { shadowOf(it).peekNextStartedActivity() != null }
            scenario.onActivity { a -> offered(a, "application/pdf", pdf) }
        }
    }

    @Test
    fun withTheLibraryFilesToAChatOnlyOpenBubblesCouldReplyToStayInTheComposer() {
        two.env.pairedManager()
        installOpenBubbles()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        directMode(asked, answer = appleAnswers())
        // A group the library has no group id for, whose OpenBubbles notification left a Reply button (text only).
        val group = "iMessage;+;chat123"
        imRepo.upsertConversation(
            com.lightdecoder.relay.ConversationDto(
                id = group, isGroup = true, name = "Trip", type = IMessageManager.TRANSPORT,
                participants = listOf(
                    com.lightdecoder.relay.ParticipantDto(id = "me", isMe = true),
                    com.lightdecoder.relay.ParticipantDto(id = "+15551234567", name = "Alice", number = "+15551234567"),
                    com.lightdecoder.relay.ParticipantDto(id = "+15559876543", name = "Bob", number = "+15559876543"),
                ),
            ),
        )
        imRepo.upsertMessage(
            com.lightdecoder.relay.MessageDto(
                id = "ld:g1", conversationId = group, senderId = "+15551234567", senderName = "Alice", timestampMs = System.currentTimeMillis() - 60_000,
                direction = "in", status = "INCOMING_COMPLETE", transport = IMessageManager.TRANSPORT, text = "Who's driving?", isOld = true,
            ),
        )
        val reply = com.lightdecoder.imessage.OpenBubblesTestNotifications.replyAction(two.app)
        com.lightdecoder.imessage.IMessageReplies.update(
            group, com.lightdecoder.imessage.IMessageAction(reply.actionIntent!!, arrayOf(android.app.RemoteInput.Builder("text_reply").build())),
            null, null, "m1",
        )
        try {
            ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, group, service = ChatService.IMESSAGE).setFlags(0)).use { scenario ->
                scenario.waitFor("the group") { shownTexts(it).contains("Who's driving?") }
                scenario.onActivity { activity ->
                    activity.setAttachment(android.net.Uri.parse("content://media/external/images/media/1"))
                    activity.findViewById<EditText>(R.id.input).setText("Look")
                    activity.findViewById<View>(R.id.send).performClick()
                }
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                // It says why, and the picture and the text are still there to send another way.
                assertEquals(two.app.getString(R.string.imessage_error_no_reply_direct), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
                scenario.onActivity { activity ->
                    assertEquals("Look", activity.findViewById<EditText>(R.id.input).text.toString())
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.attachmentBar).visibility)
                    assertEquals(null, shadowOf(activity).nextStartedActivity)
                }
                assertTrue("nothing asked of the library", asked.isEmpty())
                assertTrue("nothing stored", imRepo.messages(group).none { it.isLocal })
            }
        } finally {
            com.lightdecoder.imessage.IMessageReplies.clear()
        }
    }

    @Test
    fun withTheLibraryAPictureANotificationOnlyNamedSaysSoWithoutOpenBubbles() {
        two.env.pairedManager()
        installOpenBubbles()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        directMode(asked)
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        // From before the library: OpenBubbles' notification said only "Image".
        iMessage.record(
            CapturedIMessageChat(ALICE, "m1", "Alice", false, "+15551234567", listOf(CapturedIMessage("Alice", "tel:+15551234567", false, "Image", System.currentTimeMillis()))),
            live = false,
        )
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            fun status(a: Activity) = a.findViewById<RecyclerView>(R.id.messages).findViewById<android.widget.TextView>(R.id.mediaStatus)
            scenario.waitFor("a picture slot that names no other app") { a ->
                status(a)?.let { it.isShown && it.text == a.getString(R.string.imessage_media_waiting_direct) } == true
            }
            scenario.onActivity { a -> a.findViewById<RecyclerView>(R.id.messages).findViewById<View>(R.id.mediaFrame).performClick() }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val dialog = latestDialog()
            assertEquals(two.app.getString(R.string.imessage_media_not_here_direct), dialog.findViewById<android.widget.TextView>(android.R.id.message)?.text?.toString())
            assertFalse("no Open in OpenBubbles", dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isShown == true)
            scenario.onActivity { activity ->
                assertEquals(null, shadowOf(activity).nextStartedActivity)
                assertEquals("no photo access asked for", null, shadowOf(activity).lastRequestedPermission)
            }
        }
    }

    @Test
    fun withTheLibraryAnIMessagesPictureShownInMessagesComesThroughIMessage() {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        directMode(asked)
        val picture = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 7, 7, 7)
        // The ":imessage" process downloads it to the file asked for.
        com.lightdecoder.imessage.direct.DirectLink.installForTesting(two.app) { what, request, _ ->
            asked += what to request
            if (what == com.lightdecoder.imessage.direct.DirectWire.DOWNLOAD) {
                java.io.File(request.getString("path")).apply { parentFile?.mkdirs(); writeBytes(picture) }
                com.lightdecoder.imessage.direct.DirectWire.downloaded(picture.size.toLong())
            } else {
                com.lightdecoder.imessage.direct.DirectWire.ok()
            }
        }
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        val now = System.currentTimeMillis()
        directMessage("H1", "Hers", now - 120_000, mine = false)
        // Her picture as the library stored it: nothing here yet, Apple's description to download it by.
        imRepo.upsertMessage(
            com.lightdecoder.relay.MessageDto(
                id = "imsg:P1", conversationId = ALICE_DIRECT, senderId = "+15551234567", senderName = "Alice", timestampMs = now - 60_000,
                direction = "in", status = "INCOMING_COMPLETE", transport = IMessageManager.TRANSPORT, isOld = true,
                parts = listOf(
                    com.lightdecoder.relay.PartDto(
                        "p0", "media", mediaId = "P1/p0", key = "DESC-P1".toByteArray(), mime = "image/png", fileName = "IMG_1.png", size = picture.size.toLong(),
                    ),
                ),
            ),
        )
        // And her inline reply to her own message.
        imRepo.upsertMessage(
            com.lightdecoder.relay.MessageDto(
                id = "imsg:R1", conversationId = ALICE_DIRECT, senderId = "+15551234567", senderName = "Alice", timestampMs = now - 30_000,
                direction = "in", status = "INCOMING_COMPLETE", transport = IMessageManager.TRANSPORT, text = "About that",
                parts = listOf(com.lightdecoder.relay.PartDto("0", "text", "About that")), replyToId = "imsg:H1", isOld = true,
            ),
        )
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("her messages") { shownTexts(it).containsAll(listOf("Hers", "About that")) }
            // Shown at once, downloaded through iMessage (not Google Messages).
            two.waitUntil("her picture downloaded") { imRepo.mediaPart("imsg:P1", "p0")?.localPath?.isNotEmpty() == true }
            val download = asked(asked, com.lightdecoder.imessage.direct.DirectWire.DOWNLOAD).single()
            assertEquals("DESC-P1", download.getString("descriptor"))
            assertTrue(java.io.File(imRepo.mediaPart("imsg:P1", "p0")!!.localPath).readBytes().contentEquals(picture))
            // Her reply quotes her message, from the iMessage archive (iPhone-style: a faded copy of it above the reply).
            scenario.waitFor("the quote") { a ->
                val row = bubbleOf(a, "About that")
                row?.findViewById<android.widget.TextView>(R.id.quoteText)?.takeIf { it.isShown }?.text?.toString() == "Hers" ||
                    row?.findViewById<android.widget.TextView>(R.id.threadQuote)?.takeIf { it.isShown }?.text?.toString() == "Hers"
            }
            // Tapped: the gallery of her iMessage chat.
            scenario.onActivity { a -> a.findViewById<RecyclerView>(R.id.messages).findViewById<View>(R.id.mediaFrame).performClick() }
            scenario.onActivity { activity ->
                val started = shadowOf(activity).nextStartedActivity
                assertEquals(com.lightdecoder.ui.chat.PictureGalleryActivity::class.java.name, started.component?.className)
                assertEquals(ChatService.IMESSAGE, RelayIntents.serviceOf(started))
                assertEquals(ALICE_DIRECT, started.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID))
                assertEquals("imsg:P1", started.getStringExtra(RelayIntents.EXTRA_MESSAGE_ID))
            }
        }
    }

    @Test
    fun withTheLibraryAnIMessageChatItCannotReachSaysSoAndHandsNothingOver() {
        two.env.pairedManager()
        installOpenBubbles()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        directMode(asked, answer = appleAnswers())
        // A group OpenBubbles' notifications showed: the library has no group id to send to it by.
        val group = "iMessage;+;chat123"
        imRepo.upsertConversation(
            com.lightdecoder.relay.ConversationDto(
                id = group, isGroup = true, name = "Trip", type = IMessageManager.TRANSPORT,
                participants = listOf(
                    com.lightdecoder.relay.ParticipantDto(id = "me", isMe = true),
                    com.lightdecoder.relay.ParticipantDto(id = "+15551234567", name = "Alice", number = "+15551234567"),
                    com.lightdecoder.relay.ParticipantDto(id = "+15559876543", name = "Bob", number = "+15559876543"),
                ),
            ),
        )
        val now = System.currentTimeMillis()
        imRepo.upsertMessage(
            com.lightdecoder.relay.MessageDto(
                id = "ld:g1", conversationId = group, senderId = "+15551234567", senderName = "Alice", timestampMs = now - 60_000,
                direction = "in", status = "INCOMING_COMPLETE", transport = IMessageManager.TRANSPORT, text = "Who's driving?", isOld = true,
            ),
        )
        imRepo.insertLocalOutgoing("t-g", group, "Not me", null, null, null, null, now - 30_000)
        imRepo.setLocalState("t-g", com.lightdecoder.archive.LocalState.FAILED, com.lightdecoder.imessage.IMessageErrors.NO_REPLY_BUTTON)
        val unreachable = two.app.getString(R.string.imessage_error_no_reply_direct)
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, group, service = ChatService.IMESSAGE).setFlags(0)).use { scenario ->
            scenario.waitFor("the group") { shownTexts(it).containsAll(listOf("Who's driving?", "Not me")) }
            // Its light: a problem, not "one tap in OpenBubbles".
            scenario.onActivity { activity -> assertEquals(com.lightdecoder.ui.chat.IMessageLight.State.PROBLEM, light(activity).second) }
            // A text: it says why, and the composer keeps it.
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.input).setText("I can")
                activity.findViewById<View>(R.id.send).performClick()
            }
            assertEquals(unreachable, org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            // A picture: the same.
            org.robolectric.shadows.ShadowToast.reset()
            scenario.onActivity { activity ->
                assertEquals("I can", activity.findViewById<EditText>(R.id.input).text.toString())
                activity.setAttachment(android.net.Uri.parse("content://media/external/images/media/1"))
                activity.findViewById<View>(R.id.send).performClick()
            }
            assertEquals(unreachable, org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            scenario.onActivity { activity -> assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.attachmentBar).visibility) }
            // The failed one, tapped: why, not OpenBubbles.
            org.robolectric.shadows.ShadowToast.reset()
            scenario.onActivity { activity -> activity.onFailedTap(rowOf(activity, "Not me")) }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertEquals(unreachable, org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            scenario.onActivity { activity ->
                assertEquals(null, shadowOf(activity).nextStartedActivity)
                assertEquals("I can", activity.findViewById<EditText>(R.id.input).text.toString())
            }
            assertTrue("nothing asked of the library", asked.isEmpty())
            assertEquals(com.lightdecoder.archive.LocalState.FAILED, imRepo.message(com.lightdecoder.archive.MessageRow.tmpMessageId("t-g"))!!.localState)
            assertFalse(com.lightdecoder.imessage.IMessageLog.read(two.app).any { it.contains("OpenBubbles opened") })
        }
    }

    @Test
    fun withTheLibraryOpeningAConversationFindsThePersonsIMessage() {
        val (_, fake) = two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        val bob = "imsg:tel:+15559876543"
        directMode(asked) { what ->
            when (what) {
                // Apple: Bob has iMessage.
                com.lightdecoder.imessage.direct.DirectWire.REACH -> org.json.JSONObject().put(
                    "conversation",
                    com.lightdecoder.relay.ConversationDto(
                        id = bob, name = "+15559876543", type = IMessageManager.TRANSPORT, lastMessageTs = System.currentTimeMillis(),
                        participants = listOf(
                            com.lightdecoder.relay.ParticipantDto(id = "tel:+15559876543", number = "+15559876543"),
                            com.lightdecoder.relay.ParticipantDto(id = "tel:+15550000001", isMe = true),
                        ),
                    ).toJson(),
                )
                com.lightdecoder.imessage.direct.DirectWire.SEND -> com.lightdecoder.imessage.direct.DirectWire.sent("B1", System.currentTimeMillis())
                else -> com.lightdecoder.imessage.direct.DirectWire.ok()
            }
        }
        two.seedConversation(ChatService.MESSAGES, "c2", "Bob", lastTs = 1_000, number = "+1 555-987-6543")
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c2").setFlags(0)).use { scenario ->
            // Asked on opening; their iMessage chat is made, and is this conversation's from then on.
            scenario.waitFor("their iMessage chat, inside") { activity ->
                imRepo.conversation(bob) != null && !menu(activity).findItem(R.id.action_send_imessage).isVisible
            }
            assertEquals("+1 555-987-6543", asked(asked, com.lightdecoder.imessage.direct.DirectWire.REACH).single().getString("recipient"))
            assertEquals("not at the top of the list before anything is written", 0L, imRepo.conversation(bob)!!.lastMessageTs)
            scenario.onActivity { activity -> assertEquals(com.lightdecoder.ui.chat.IMessageLight.State.ACTIVE, light(activity).second) }
            // What is typed now goes blue, through the library.
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.input).setText("Hi Bob")
                activity.findViewById<View>(R.id.send).performClick()
            }
            two.waitUntil("sent as iMessage") { asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND).isNotEmpty() }
            assertEquals("Hi Bob", asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND).single().getString("text"))
            assertTrue("not as a text", fake.sent.isEmpty())
        }
        // Opened again: their chat is here already, nothing to ask.
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c2").setFlags(0)).use { scenario ->
            scenario.waitFor("Hi Bob") { shownTexts(it).contains("Hi Bob") }
            assertEquals(1, asked(asked, com.lightdecoder.imessage.direct.DirectWire.REACH).size)
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
        // OpenBubbles hides message text: the light turns red and says which setting to change.
        IMessagePrefs.setHiddenTextAt(two.app, 1L)
        launchList().use { scenario ->
            scenario.waitFor("the list") { titles(it).isNotEmpty() }
            scenario.onActivity { activity ->
                assertEquals(true to com.lightdecoder.ui.chat.IMessageLight.State.PROBLEM, light(activity))
                menu(activity).findItem(R.id.action_imessage_light).actionView!!.performClick()
            }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertEquals(two.app.getString(R.string.imessage_light_hidden_text), latestDialog().findViewById<android.widget.TextView>(android.R.id.message)?.text?.toString())
        }
        IMessagePrefs.setHiddenTextAt(two.app, 0L)
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

    @Test
    fun aCheckStartedBeforeAnyIMessageChatSeesAReplyThatCarriesOnlyTheirName() {
        two.env.pairedManager()
        installOpenBubbles()
        IMessagePrefs.setEnabled(two.app, true)
        two.seedConversation(ChatService.MESSAGES, "c1", "Nina", lastTs = 1_000, number = "+1 555-123-4567")
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("the conversation") { it.findViewById<View>(R.id.input).isEnabled }
            scenario.onActivity { activity ->
                assertTrue("offered before any iMessage chat", menu(activity).findItem(R.id.action_check_imessage).isVisible)
                activity.startIMessageCheck(com.lightdecoder.imessage.IMessageCheck.TEXT)
            }
            // OpenBubbles opened on a new chat with the check filled in; the user taps send there.
            scenario.waitFor("the check dialog") { it.checkDialog != null }
            scenario.onActivity { activity ->
                val lines = steps(activity).lines()
                assertEquals("\u2022  " + activity.getString(R.string.imessage_check_step_tap_send), lines[0])
                assertTrue("no steps that could never tick", steps(activity).contains(activity.getString(R.string.imessage_check_come_back)))
            }
            // Nina answers; OpenBubbles' notification names her but has no number.
            iMessage.record(
                CapturedIMessageChat(ALICE, "m1", "Nina", false, "", listOf(CapturedIMessage("Nina", "", false, "OK", System.currentTimeMillis() + 1_000))),
                live = true,
            )
            val blue = two.app.getString(R.string.imessage_check_blue, "Nina")
            scenario.waitFor("the verdict") { verdict(it) == blue }
            assertTrue(com.lightdecoder.imessage.IMessageLog.read(two.app).any { it.contains("found the person's iMessage chat") })
        }
    }

    private fun steps(activity: Activity): String =
        (activity as ThreadActivity).checkDialog?.findViewById<android.widget.TextView>(R.id.checkSteps)?.text?.toString().orEmpty()

    private fun verdict(activity: Activity): String =
        (activity as ThreadActivity).checkDialog?.findViewById<android.widget.TextView>(R.id.checkVerdict)?.text?.toString().orEmpty()

    // --- helpers ------------------------------------------------------------------------

    /** Alice's iMessage carrying [extra] (what it has beyond its text), as the library stores it. */
    private fun directMessageWith(guid: String, text: String, ts: Long, extra: com.lightdecoder.imessage.direct.IMessageExtra) {
        imRepo.upsertMessage(
            com.lightdecoder.relay.MessageDto(
                id = "imsg:$guid", conversationId = ALICE_DIRECT, senderId = "+15551234567", senderName = "Alice",
                timestampMs = ts, direction = "in", status = "INCOMING_COMPLETE", transport = IMessageManager.TRANSPORT,
                text = text, parts = listOf(com.lightdecoder.relay.PartDto("0", "text", text)), isOld = true, extra = extra.toJson(),
            ),
        )
    }

    /** The message on screen whose view [matches]. */
    private fun itemWhere(activity: Activity, matches: (View) -> Boolean): View? {
        val list = activity.findViewById<RecyclerView>(R.id.messages)
        return (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull(matches)
    }

    private fun appCardOf(activity: Activity, caption: String): View? =
        itemWhere(activity) { it.findViewById<android.widget.TextView>(R.id.appCaption)?.text?.toString() == caption }

    @Test
    fun withTheLibraryEffectsShowAndGoAsOnAnIphone() {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        val answer = appleSends()
        directMode(asked)
        com.lightdecoder.imessage.direct.DirectLink.installForTesting(two.app) { what, request, _ ->
            if (what == com.lightdecoder.imessage.direct.DirectWire.SEND) asked += what to request
            answer(what, request)
        }
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        val slam = "com.apple.MobileSMS.expressivesend.impact"
        val now = System.currentTimeMillis()
        directMessageWith("E1", "Boom", now - 120_000, com.lightdecoder.imessage.direct.IMessageExtra(effect = slam))
        directMessageWith(
            "E2", "the secret", now - 60_000,
            com.lightdecoder.imessage.direct.IMessageExtra(effect = com.lightdecoder.imessage.direct.IMessageExtra.INVISIBLE_INK),
        )
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("her iMessages") { shownTexts(it).containsAll(listOf("Boom", "the secret")) }
            scenario.onActivity { activity ->
                val effect = bubbleOf(activity, "Boom")!!.findViewById<android.widget.TextView>(R.id.effect)
                assertEquals(View.VISIBLE, effect.visibility)
                assertEquals("Sent with Slam", effect.text.toString())
                // Invisible ink: hidden until tapped.
                assertEquals(null, bubbleOf(activity, "the secret"))
                val inked = bubbleOf(activity, "▒▒▒ ▒▒▒▒▒▒")!!
                assertEquals("Sent with Invisible Ink", inked.findViewById<android.widget.TextView>(R.id.effect).text.toString())
                inked.findViewById<View>(R.id.bubble).performClick()
            }
            scenario.waitFor("the ink tapped away") { bubbleOf(it, "the secret") != null }
            // Send held down: "Send with effect", Bubble first; Slam picked and sent with the arrow.
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.input).setText("Kaboom")
                assertTrue(activity.findViewById<View>(R.id.send).performLongClick())
            }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val picker = effectSheet()
            assertEquals(two.app.getString(R.string.imessage_effect_pick_title), picker.findViewById<android.widget.TextView>(R.id.effectTitle)!!.text.toString())
            assertEquals("Kaboom", picker.findViewById<android.widget.TextView>(R.id.effectPreview)!!.text.toString())
            val bubbles = picker.findViewById<android.widget.RadioGroup>(R.id.effectBubbleList)!!
            val screens = picker.findViewById<android.widget.RadioGroup>(R.id.effectScreenList)!!
            assertEquals(View.VISIBLE, bubbles.visibility)
            assertEquals(View.GONE, screens.visibility)
            assertEquals(listOf("Slam", "Loud", "Gentle", "Invisible Ink"), effectNames(bubbles))
            assertEquals(
                listOf("Echo", "Spotlight", "Balloons", "Confetti", "Love", "Lasers", "Fireworks", "Celebration"),
                effectNames(screens),
            )
            val arrow = picker.findViewById<View>(R.id.effectSend)!!
            assertFalse("nothing picked yet", arrow.isEnabled)
            bubbles.getChildAt(0).performClick()
            assertTrue(arrow.isEnabled)
            arrow.performClick()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertFalse("closed once sent", picker.isShowing)
            two.waitUntil("sent with the effect") { asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND).isNotEmpty() }
            val sent = com.lightdecoder.imessage.direct.DirectWire.SendRequest.fromJson(asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND).single())
            assertEquals("Kaboom", sent.text)
            assertEquals(slam, sent.effect)
            two.waitUntil("stored with it") { imRepo.message("imsg:S1") != null }
            assertEquals(slam, com.lightdecoder.imessage.direct.IMessageExtra.parse(imRepo.message("imsg:S1")!!.extra).effect)
            scenario.waitFor("shown with it") { bubbleOf(it, "Kaboom")?.findViewById<android.widget.TextView>(R.id.effect)?.text?.toString() == "Sent with Slam" }
            scenario.onActivity { activity -> assertEquals(null, shadowOf(activity).nextStartedActivity) }
        }
    }

    @Test
    fun withTheLibraryAScreenEffectPickedGoesWithTheMessageAndCloseSendsNothing() {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        val answer = appleSends()
        directMode(asked)
        com.lightdecoder.imessage.direct.DirectLink.installForTesting(two.app) { what, request, _ ->
            if (what == com.lightdecoder.imessage.direct.DirectWire.SEND) asked += what to request
            answer(what, request)
        }
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        directMessageWith("E1", "Hello", System.currentTimeMillis() - 60_000, com.lightdecoder.imessage.direct.IMessageExtra())
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("her iMessage") { shownTexts(it).contains("Hello") }
            // Closed with the X: nothing goes, the text stays.
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.input).setText("Party")
                assertTrue(activity.findViewById<View>(R.id.send).performLongClick())
            }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val first = effectSheet()
            first.findViewById<View>(R.id.effectClose)!!.performClick()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertFalse(first.isShowing)
            scenario.onActivity { activity -> assertEquals("Party", activity.findViewById<EditText>(R.id.input).text.toString()) }

            // Screen: Confetti picked, sent with the arrow.
            scenario.onActivity { activity -> assertTrue(activity.findViewById<View>(R.id.send).performLongClick()) }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val picker = effectSheet()
            picker.findViewById<View>(R.id.effectTabScreen)!!.performClick()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val screens = picker.findViewById<android.widget.RadioGroup>(R.id.effectScreenList)!!
            assertEquals(View.VISIBLE, screens.visibility)
            assertEquals(View.GONE, picker.findViewById<View>(R.id.effectBubbleList)!!.visibility)
            screens.getChildAt(effectNames(screens).indexOf("Confetti")).performClick()
            picker.findViewById<View>(R.id.effectSend)!!.performClick()
            two.waitUntil("sent with the effect") { asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND).isNotEmpty() }
            val sent = com.lightdecoder.imessage.direct.DirectWire.SendRequest.fromJson(asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND).single())
            assertEquals("Party", sent.text)
            assertEquals("com.apple.messages.effect.CKConfettiEffect", sent.effect)
        }
    }

    /**
     * The iMessage library set up as Apple would answer sends, Alice's Messages conversation with her
     * iMessage chat. Returns the sends asked of it.
     */
    private fun effectsSetUp(): MutableList<Pair<Int, org.json.JSONObject>> {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        val answer = appleSends()
        directMode(asked)
        com.lightdecoder.imessage.direct.DirectLink.installForTesting(two.app) { what, request, _ ->
            if (what == com.lightdecoder.imessage.direct.DirectWire.SEND) asked += what to request
            answer(what, request)
        }
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        return asked
    }

    private fun plays(activity: Activity) = (activity as ThreadActivity).effectPlayer.plays

    private fun overlayShown(activity: Activity): Boolean {
        val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        return (0 until content.childCount).any { content.getChildAt(it) is com.lightdecoder.ui.chat.EffectOverlayView }
    }

    @Test
    fun sendingWithSlamPlaysItOnceOnTheNewBubble() {
        effectsSetUp()
        directMessageWith("E1", "Hello", System.currentTimeMillis() - 60_000, com.lightdecoder.imessage.direct.IMessageExtra())
        val slam = "com.apple.MobileSMS.expressivesend.impact"
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("her iMessage") { shownTexts(it).contains("Hello") }
            scenario.onActivity { activity ->
                assertTrue("nothing plays for a message without an effect", plays(activity).isEmpty())
                activity.findViewById<EditText>(R.id.input).setText("Kaboom")
                assertTrue(activity.findViewById<View>(R.id.send).performLongClick())
            }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val picker = effectSheet()
            picker.findViewById<android.widget.RadioGroup>(R.id.effectBubbleList)!!.getChildAt(0).performClick()
            picker.findViewById<View>(R.id.effectSend)!!.performClick()
            scenario.waitFor("Slam played on the sent bubble") { plays(it).isNotEmpty() }
            scenario.onActivity { activity ->
                val play = plays(activity).single()
                assertEquals(slam, play.effect)
                assertEquals(com.lightdecoder.ui.chat.EffectPlayer.Reason.SENT, play.reason)
                assertFalse("a bubble effect", play.screen)
                assertTrue(play.animated)
            }
            // Confirmed by Apple (another row, the same message): not again.
            two.waitUntil("stored with it") { imRepo.message("imsg:S1") != null }
            scenario.waitFor("the confirmed row") { a -> bubbleOf(a, "Kaboom") != null && shownTexts(a).count { it == "Kaboom" } == 1 }
            org.robolectric.shadows.ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            scenario.onActivity { activity -> assertEquals(1, plays(activity).size) }
        }
    }

    @Test
    fun anIncomingConfettiSeenTheFirstTimePlaysButNotWhenOpenedAgain() {
        effectsSetUp()
        val confetti = "com.apple.messages.effect.CKConfettiEffect"
        directMessageWith("C1", "Party!", System.currentTimeMillis() - 60_000, com.lightdecoder.imessage.direct.IMessageExtra(effect = confetti))
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("the confetti played") { plays(it).isNotEmpty() }
            scenario.onActivity { activity ->
                val play = plays(activity).single()
                assertEquals("imsg:C1", play.messageId)
                assertEquals(confetti, play.effect)
                assertEquals(com.lightdecoder.ui.chat.EffectPlayer.Reason.RECEIVED, play.reason)
                assertTrue("over the screen", play.screen)
                assertEquals(1, (activity as ThreadActivity).effectPlayer.overlaysShown)
            }
            // One that arrives while the thread is open plays too.
            val balloons = "com.apple.messages.effect.CKHappyBirthdayEffect"
            directMessageWith("C2", "Happy birthday", System.currentTimeMillis(), com.lightdecoder.imessage.direct.IMessageExtra(effect = balloons))
            scenario.waitFor("the balloons played") { plays(it).size == 2 }
            scenario.onActivity { activity -> assertEquals("imsg:C2", plays(activity).last().messageId) }
            scenario.waitFor("the effect over") { !overlayShown(it) }
        }
        // Opened again: seen already, nothing plays.
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("her iMessages") { shownTexts(it).containsAll(listOf("Party!", "Happy birthday")) }
            org.robolectric.shadows.ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            scenario.onActivity { activity ->
                assertTrue(plays(activity).isEmpty())
                assertFalse(overlayShown(activity))
            }
        }
    }

    @Test
    fun replayUnderTheBubblePlaysTheEffectAgain() {
        effectsSetUp()
        val slam = "com.apple.MobileSMS.expressivesend.impact"
        // Read long ago: it does not play by itself.
        directMessageWith("R1", "Boom", System.currentTimeMillis() - 3 * 60 * 60_000L, com.lightdecoder.imessage.direct.IMessageExtra(effect = slam))
        directMessageWith("R2", "Later", System.currentTimeMillis() - 2 * 60 * 60_000L, com.lightdecoder.imessage.direct.IMessageExtra())
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("her iMessages") { shownTexts(it).containsAll(listOf("Boom", "Later")) }
            org.robolectric.shadows.ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            scenario.onActivity { activity ->
                assertTrue(plays(activity).isEmpty())
                val boom = bubbleOf(activity, "Boom")!!
                assertEquals("Sent with Slam", boom.findViewById<android.widget.TextView>(R.id.effect).text.toString())
                val replay = boom.findViewById<android.widget.TextView>(R.id.effectReplay)
                assertEquals(View.VISIBLE, replay.visibility)
                assertEquals(activity.getString(R.string.imessage_effect_replay), replay.text.toString())
                assertEquals("Replay Slam", replay.contentDescription.toString())
                assertEquals(View.GONE, bubbleOf(activity, "Later")!!.findViewById<View>(R.id.effectReplay).visibility)
                replay.performClick()
                assertEquals(com.lightdecoder.ui.chat.EffectPlayer.Reason.REPLAY, plays(activity).single().reason)
                replay.performClick()
                assertEquals("as often as tapped", 2, plays(activity).size)
            }
        }
    }

    @Test
    fun withAnimationsRemovedAnEffectDoesNotMoveButItsCaptionStays() {
        effectsSetUp()
        Settings.Global.putFloat(two.app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        try {
            val confetti = "com.apple.messages.effect.CKConfettiEffect"
            directMessageWith("A1", "Party!", System.currentTimeMillis() - 60_000, com.lightdecoder.imessage.direct.IMessageExtra(effect = confetti))
            ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
                scenario.waitFor("its turn") { plays(it).isNotEmpty() }
                scenario.onActivity { activity ->
                    assertFalse("skipped", plays(activity).single().animated)
                    assertEquals(0, (activity as ThreadActivity).effectPlayer.overlaysShown)
                    assertEquals("Sent with Confetti", bubbleOf(activity, "Party!")!!.findViewById<android.widget.TextView>(R.id.effect).text.toString())
                }
            }
        } finally {
            Settings.Global.putFloat(two.app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
    }

    private fun formatted(vararg runs: com.lightdecoder.imessage.direct.TextRun) =
        com.lightdecoder.imessage.direct.IMessageExtra(runs = mapOf(0 to runs.toList()))

    private fun textPlays(activity: Activity) = plays(activity).filter { it.effect == com.lightdecoder.ui.chat.EffectPlayer.TEXT }

    @Test
    fun anIncomingFormattedTextShowsItsStylesAndItsTextEffectPlaysOnceAndOnTap() {
        effectsSetUp()
        val big = com.lightdecoder.imessage.direct.TextEffectKind.BIG
        directMessageWith(
            "T1", "Hello big world", System.currentTimeMillis() - 3 * 60 * 60_000L,
            formatted(com.lightdecoder.imessage.direct.TextRun(0, 5, bold = true), com.lightdecoder.imessage.direct.TextRun(6, 9, effect = big)),
        )
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("the text effect played") { textPlays(it).isNotEmpty() }
            scenario.onActivity { activity ->
                val play = textPlays(activity).single()
                assertEquals("imsg:T1", play.messageId)
                assertEquals(com.lightdecoder.ui.chat.EffectPlayer.Reason.RECEIVED, play.reason)
                assertTrue(play.animated)
                val bubble = bubbleOf(activity, "Hello big world")!!
                val body = bubble.findViewById<com.lightdecoder.ui.chat.TextEffectView>(R.id.body)
                val text = body.text as android.text.Spanned
                assertEquals(android.graphics.Typeface.BOLD, text.getSpans(0, 5, android.text.style.StyleSpan::class.java).single().style)
                assertEquals(big, text.getSpans(6, 9, com.lightdecoder.ui.chat.TextEffectSpan::class.java).single().effect)
                assertTrue(text.getSpans(10, 15, android.text.style.StyleSpan::class.java).isEmpty())
                assertTrue(bubble.findViewById<View>(R.id.bubble).contentDescription.toString().contains("With text effects: Big"))
                // No "Sent with …" caption: text effects are the words' own.
                assertEquals(View.GONE, bubble.findViewById<View>(R.id.effectRow).visibility)
                // A tap on the text plays it again, as often as tapped.
                body.performClick()
                body.performClick()
                assertEquals(listOf(com.lightdecoder.ui.chat.EffectPlayer.Reason.RECEIVED, com.lightdecoder.ui.chat.EffectPlayer.Reason.REPLAY, com.lightdecoder.ui.chat.EffectPlayer.Reason.REPLAY), textPlays(activity).map { it.reason })
                // The list and its quote say the words alone.
                assertEquals("Hello big world", imRepo.message("imsg:T1")!!.text)
            }
        }
        // Opened again: played already, it stays still until tapped.
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("her iMessage") { shownTexts(it).contains("Hello big world") }
            org.robolectric.shadows.ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            scenario.onActivity { activity -> assertTrue(textPlays(activity).isEmpty()) }
        }
    }

    @Test
    fun withAnimationsRemovedTextEffectsStayStillButFormattingShows() {
        effectsSetUp()
        Settings.Global.putFloat(two.app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        try {
            directMessageWith(
                "T2", "shake it off", System.currentTimeMillis() - 60_000,
                formatted(
                    com.lightdecoder.imessage.direct.TextRun(0, 5, effect = com.lightdecoder.imessage.direct.TextEffectKind.SHAKE),
                    com.lightdecoder.imessage.direct.TextRun(9, 12, italic = true),
                ),
            )
            ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
                scenario.waitFor("its turn") { textPlays(it).isNotEmpty() }
                scenario.onActivity { activity ->
                    assertFalse(textPlays(activity).single().animated)
                    val body = bubbleOf(activity, "shake it off")!!.findViewById<com.lightdecoder.ui.chat.TextEffectView>(R.id.body)
                    assertFalse(body.playing)
                    body.performClick()
                    assertFalse("a tap moves nothing either", body.playing)
                    val text = body.text as android.text.Spanned
                    assertEquals(android.graphics.Typeface.ITALIC, text.getSpans(9, 12, android.text.style.StyleSpan::class.java).single().style)
                    assertFalse(text.getSpans(0, 5, com.lightdecoder.ui.chat.TextEffectSpan::class.java).single().hidden)
                }
            }
        } finally {
            Settings.Global.putFloat(two.app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
    }

    @Test
    fun selectedWordsAreFormattedInTheComposerAndSentSo() {
        val asked = effectsSetUp()
        directMessageWith("E1", "Hello", System.currentTimeMillis() - 60_000, com.lightdecoder.imessage.direct.IMessageExtra())
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("her iMessage") { shownTexts(it).contains("Hello") }
            scenario.onActivity { activity ->
                val input = activity.findViewById<EditText>(R.id.input)
                input.setText("  make it bold and big")
                val mode = input.customSelectionActionModeCallback as com.lightdecoder.ui.chat.FormattingActionMode
                val menu = androidx.appcompat.view.menu.MenuBuilder(activity)
                mode.addItems(menu)
                assertEquals(
                    listOf("Text effects", "Bold", "Italic", "Underline", "Strikethrough"),
                    (0 until menu.size()).map { menu.getItem(it).title.toString() },
                )
                input.setSelection(10, 14)
                assertTrue(mode.handle(R.id.text_format_bold))
                // Bold shows in the composer as it is typed.
                assertEquals(android.graphics.Typeface.BOLD, input.text.getSpans(10, 14, android.text.style.StyleSpan::class.java).single().style)
                // "Text effects": the eight, by name; Big on "big".
                input.setSelection(19, 22)
                assertTrue(mode.handle(R.id.text_format_effects))
            }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val picker = latestDialog()
            val names = (0 until picker.listView.adapter.count).map { picker.listView.adapter.getItem(it).toString() }
            assertEquals(listOf("Big", "Small", "Shake", "Nod", "Explode", "Ripple", "Bloom", "Jitter"), names)
            picker.listView.performItemClick(null, 0, 0)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            scenario.onActivity { activity ->
                val input = activity.findViewById<EditText>(R.id.input)
                assertEquals(1, input.text.getSpans(19, 22, com.lightdecoder.ui.chat.EffectMark::class.java).size)
                activity.findViewById<View>(R.id.send).performClick()
            }
            two.waitUntil("sent") { asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND).isNotEmpty() }
            val sent = com.lightdecoder.imessage.direct.DirectWire.SendRequest.fromJson(asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND).single())
            // Trimmed, the runs moved with it.
            assertEquals("make it bold and big", sent.text)
            assertEquals(
                listOf(
                    com.lightdecoder.imessage.direct.TextRun(8, 12, bold = true),
                    com.lightdecoder.imessage.direct.TextRun(17, 20, effect = com.lightdecoder.imessage.direct.TextEffectKind.BIG),
                ),
                sent.runs,
            )
            // The sent copy shows formatted, and its effect plays once (the copy and the sent message are one).
            two.waitUntil("stored") { imRepo.message("imsg:S1") != null }
            scenario.waitFor("the sent message") { a -> bubbleOf(a, "make it bold and big") != null && shownTexts(a).count { it == "make it bold and big" } == 1 }
            org.robolectric.shadows.ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            scenario.onActivity { activity ->
                assertEquals(1, textPlays(activity).size)
                assertEquals(com.lightdecoder.ui.chat.EffectPlayer.Reason.SENT, textPlays(activity).single().reason)
                val body = bubbleOf(activity, "make it bold and big")!!.findViewById<android.widget.TextView>(R.id.body)
                assertEquals(1, (body.text as android.text.Spanned).getSpans(8, 12, android.text.style.StyleSpan::class.java).size)
            }
        }
    }

    @Test
    fun theComposersFormattingSurvivesARotation() {
        effectsSetUp()
        directMessageWith("E1", "Hello", System.currentTimeMillis() - 60_000, com.lightdecoder.imessage.direct.IMessageExtra())
        val runs = listOf(
            com.lightdecoder.imessage.direct.TextRun(0, 4, bold = true),
            com.lightdecoder.imessage.direct.TextRun(5, 9, effect = com.lightdecoder.imessage.direct.TextEffectKind.NOD),
        )
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("her iMessage") { shownTexts(it).contains("Hello") }
            scenario.onActivity { activity ->
                val input = activity.findViewById<EditText>(R.id.input)
                input.setText("bold nods")
                com.lightdecoder.ui.chat.ComposerFormatting.write(input.text, runs, 0)
            }
            scenario.recreate()
            scenario.waitFor("the composer again") { it.findViewById<EditText>(R.id.input).text.toString() == "bold nods" }
            scenario.onActivity { activity ->
                assertEquals(runs, com.lightdecoder.ui.chat.ComposerFormatting.runs(activity.findViewById<EditText>(R.id.input).text))
            }
        }
    }

    @Test
    fun theEffectSheetsTextTabPutsATextEffectOnTheWholeMessage() {
        val asked = effectsSetUp()
        directMessageWith("E1", "Hello", System.currentTimeMillis() - 60_000, com.lightdecoder.imessage.direct.IMessageExtra())
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("her iMessage") { shownTexts(it).contains("Hello") }
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.input).setText("Party time")
                assertTrue(activity.findViewById<View>(R.id.send).performLongClick())
            }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val sheet = effectSheet()
            assertEquals(View.VISIBLE, sheet.findViewById<View>(R.id.effectTabText)!!.visibility)
            sheet.findViewById<View>(R.id.effectTabText)!!.performClick()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val texts = sheet.findViewById<android.widget.RadioGroup>(R.id.effectTextList)!!
            assertEquals(View.VISIBLE, texts.visibility)
            assertEquals(View.GONE, sheet.findViewById<View>(R.id.effectBubbleList)!!.visibility)
            assertEquals(View.GONE, sheet.findViewById<View>(R.id.effectScreenList)!!.visibility)
            assertEquals(listOf("Big", "Small", "Shake", "Nod", "Explode", "Ripple", "Bloom", "Jitter"), effectNames(texts))
            texts.getChildAt(effectNames(texts).indexOf("Shake")).performClick()
            sheet.findViewById<View>(R.id.effectSend)!!.performClick()
            two.waitUntil("sent") { asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND).isNotEmpty() }
            val sent = com.lightdecoder.imessage.direct.DirectWire.SendRequest.fromJson(asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND).single())
            assertEquals("Party time", sent.text)
            assertNull("no bubble or screen effect with it", sent.effect)
            assertEquals(listOf(com.lightdecoder.imessage.direct.TextRun(0, 10, effect = com.lightdecoder.imessage.direct.TextEffectKind.SHAKE)), sent.runs)
        }
    }

    @Test
    fun outsideAnIMessageChatTheSelectionMenuHasNoFormatting() {
        two.env.pairedManager()
        two.seedConversation(ChatService.MESSAGES, "c1", "Bob", lastTs = 1_000)
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("the composer") { it.findViewById<View>(R.id.input).isEnabled }
            scenario.onActivity { activity ->
                val input = activity.findViewById<EditText>(R.id.input)
                input.setText("green text")
                val menu = androidx.appcompat.view.menu.MenuBuilder(activity)
                (input.customSelectionActionModeCallback as com.lightdecoder.ui.chat.FormattingActionMode).addItems(menu)
                assertEquals(0, menu.size())
            }
        }
    }

    @Test
    fun withoutAnIMessageChatSendHeldDownSaysEffectsAreForIMessageAndSendsNothing() {
        two.env.pairedManager()
        two.seedConversation(ChatService.MESSAGES, "c1", "Bob", lastTs = 1_000)
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("the composer") { it.findViewById<View>(R.id.input).isEnabled }
            scenario.onActivity { activity ->
                // Nothing typed: nothing offered.
                assertFalse(activity.findViewById<View>(R.id.send).performLongClick())
                activity.findViewById<EditText>(R.id.input).setText("Green one")
                assertTrue(activity.findViewById<View>(R.id.send).performLongClick())
            }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertEquals(two.app.getString(R.string.imessage_effect_not_imessage), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            assertFalse("no sheet", ShadowDialog.getLatestDialog() is com.google.android.material.bottomsheet.BottomSheetDialog)
            scenario.onActivity { activity -> assertEquals("Green one", activity.findViewById<EditText>(R.id.input).text.toString()) }
        }
    }

    /** The "Send with effect" sheet shown last. */
    private fun effectSheet(): com.google.android.material.bottomsheet.BottomSheetDialog {
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue("no effect sheet: $dialog", dialog is com.google.android.material.bottomsheet.BottomSheetDialog)
        assertTrue(dialog.isShowing)
        return dialog as com.google.android.material.bottomsheet.BottomSheetDialog
    }

    private fun effectNames(group: android.widget.RadioGroup): List<String> =
        (0 until group.childCount).map { (group.getChildAt(it) as android.widget.TextView).text.toString() }

    @Test
    fun withTheLibraryALinksPreviewAndAnAppsCardShowAndOpen() {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        directMode(java.util.Collections.synchronizedList(ArrayList()))
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        val now = System.currentTimeMillis()
        fun extra(link: com.lightdecoder.imessage.direct.IMessageExtra.Link? = null, app: com.lightdecoder.imessage.direct.IMessageExtra.App? = null) =
            com.lightdecoder.imessage.direct.IMessageExtra(link = link, app = app)
        directMessageWith(
            "L1", "https://www.example.com/a", now - 180_000,
            extra(link = com.lightdecoder.imessage.direct.IMessageExtra.Link("https://www.example.com/a", "A page", "About it", "example.com")),
        )
        val game = com.lightdecoder.imessage.direct.IMessageExtra.App(
            name = "GamePigeon", appId = 1124197642L, url = "data:state", ldText = "8 Ball", imageTitle = "8 Ball", caption = "Your move", root = "imsg:G1",
        )
        directMessageWith("G1", "Your move", now - 120_000, extra(app = game))
        directMessageWith("P1", "Lunch?", now - 60_000, extra(app = game.copy(name = "Polls", url = "https://example.com/poll", caption = "Lunch?", imageTitle = "")))
        try {
            ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
                scenario.waitFor("her iMessages") { shownTexts(it).containsAll(listOf("https://www.example.com/a", "Your move", "Lunch?")) }
                scenario.onActivity { activity ->
                    // The link's preview, under its text: a tap opens the page.
                    val card = bubbleOf(activity, "https://www.example.com/a")!!.findViewById<View>(R.id.linkCard)
                    assertEquals(View.VISIBLE, card.visibility)
                    assertEquals("A page", card.findViewById<android.widget.TextView>(R.id.linkTitle).text.toString())
                    assertEquals("About it", card.findViewById<android.widget.TextView>(R.id.linkSummary).text.toString())
                    assertEquals("example.com", card.findViewById<android.widget.TextView>(R.id.linkSite).text.toString())
                    card.performClick()
                    val opened = shadowOf(activity).nextStartedActivity
                    assertEquals(Intent.ACTION_VIEW, opened.action)
                    assertEquals("https://www.example.com/a", opened.dataString)
                    // A game's card: its lines instead of the text that stands for them, the app's name small.
                    val item = appCardOf(activity, "Your move")!!
                    assertEquals(View.GONE, item.findViewById<View>(R.id.body).visibility)
                    assertEquals("8 Ball", item.findViewById<android.widget.TextView>(R.id.appImageTitle).text.toString())
                    assertEquals("GamePigeon", item.findViewById<android.widget.TextView>(R.id.appName).text.toString())
                    assertEquals(View.GONE, item.findViewById<View>(R.id.appSubcaption).visibility)
                    // The app that plays it here is not installed: it is offered, from the store.
                    item.findViewById<View>(R.id.bubble).performClick()
                    val install = latestDialog()
                    assertEquals(
                        "Install OpenPigeon to play GamePigeon games with iPhone users.",
                        install.findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString(),
                    )
                    assertEquals(null, shadowOf(activity).nextStartedActivity)
                    install.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    shadowOf(android.os.Looper.getMainLooper()).idle()
                    assertEquals("market://details?id=com.openbubbles.openpigeon", shadowOf(activity).nextStartedActivity.dataString)
                    // With no app to play it here at all, it opens on an Apple device, and nothing is opened.
                    com.lightdecoder.imessage.direct.IMessageApps.host = null
                    item.findViewById<View>(R.id.bubble).performClick()
                    assertEquals("GamePigeon can only be opened on an iPhone, iPad or Mac.", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
                    assertEquals(null, shadowOf(activity).nextStartedActivity)
                    // An app that keeps its state in a web address opens in the browser.
                    appCardOf(activity, "Lunch?")!!.findViewById<View>(R.id.bubble).performClick()
                    assertEquals("https://example.com/poll", shadowOf(activity).nextStartedActivity.dataString)
                    // An installed extension takes the card first.
                    val engaged = ArrayList<String>()
                    com.lightdecoder.imessage.direct.IMessageApps.host =
                        com.lightdecoder.imessage.direct.IMessageApps.Host { _, row, app -> engaged += row.id + " " + app.name; true }
                    org.robolectric.shadows.ShadowToast.reset()
                    item.findViewById<View>(R.id.bubble).performClick()
                    assertEquals(listOf("imsg:G1 GamePigeon"), engaged)
                    assertEquals(0, org.robolectric.shadows.ShadowToast.shownToastCount())
                    assertEquals(null, shadowOf(activity).nextStartedActivity)
                }
            }
        } finally {
            com.lightdecoder.imessage.direct.IMessageApps.host = null
        }
    }

    @Test
    fun withTheLibraryAnIMessageGroupIsStartedFromTheMessagesMenu() {
        two.env.pairedManager()
        two.seedConversation(ChatService.MESSAGES, "c1", "Bob", lastTs = 1_000)
        launchList().use { scenario ->
            scenario.waitFor("the Messages inbox") { titles(it) == listOf("Bob") }
            // Not without the library: OpenBubbles' notifications start nothing.
            scenario.onActivity { activity -> assertFalse(menu(activity).findItem(R.id.action_new_imessage_group).isVisible) }
        }
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        directMode(asked)
        com.lightdecoder.imessage.direct.DirectLink.installForTesting(two.app) { what, request, _ ->
            asked += what to request
            if (what != com.lightdecoder.imessage.direct.DirectWire.REACH) return@installForTesting com.lightdecoder.imessage.direct.DirectWire.ok()
            val to = com.lightdecoder.imessage.direct.Handles.normalize(request.getString("recipient"))!!
            org.json.JSONObject().put(
                "conversation",
                com.lightdecoder.relay.ConversationDto(
                    id = "imsg:$to", type = IMessageManager.TRANSPORT, defaultOutgoingId = "tel:+15550000001",
                    participants = listOf(
                        com.lightdecoder.relay.ParticipantDto(id = to),
                        com.lightdecoder.relay.ParticipantDto(id = "tel:+15550000001", isMe = true),
                    ),
                ).toJson(),
            )
        }
        val picker = launchList().use { scenario ->
            scenario.waitFor("the Messages inbox") { titles(it).contains("Bob") }
            var started: Intent? = null
            scenario.onActivity { activity ->
                val item = menu(activity).findItem(R.id.action_new_imessage_group)
                assertTrue(item.isVisible)
                assertEquals("New iMessage group", item.title.toString())
                activity.onOptionsItemSelected(item)
                started = shadowOf(activity).nextStartedActivity
            }
            started!!
        }
        assertEquals(com.lightdecoder.ui.chat.NewConversationActivity::class.java.name, picker.component?.className)
        assertEquals(ChatService.IMESSAGE.key, picker.getStringExtra(RelayIntents.EXTRA_SERVICE))
        ActivityScenario.launch<com.lightdecoder.ui.chat.NewConversationActivity>(picker.setFlags(0)).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("New iMessage group", activity.supportActionBar?.title?.toString())
                val query = activity.findViewById<EditText>(R.id.query)
                // A number and an email address, both picked.
                query.setText("+1 555 222 3333")
                activity.findViewById<View>(R.id.useNumber).performClick()
                query.setText("carol@example.com")
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.useNumber).visibility)
                activity.findViewById<View>(R.id.useNumber).performClick()
                assertEquals(2, activity.findViewById<android.view.ViewGroup>(R.id.selectedChips).childCount)
                activity.findViewById<View>(R.id.start).performClick()
            }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val dialog = latestDialog()
            assertEquals(two.app.getString(R.string.imessage_group_body), dialog.findViewById<android.widget.TextView>(R.id.groupBody)?.text?.toString())
            dialog.findViewById<EditText>(R.id.groupName)!!.setText("Book club")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            var opened: Intent? = null
            two.waitUntil("the group's chat opened") {
                opened = opened ?: shadowOf(two.app).nextStartedActivity
                opened != null
            }
            val id = opened!!.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID)!!
            assertEquals(ChatService.IMESSAGE.key, opened!!.getStringExtra(RelayIntents.EXTRA_SERVICE))
            assertTrue(id, id.startsWith("imsg:group:"))
            val group = imRepo.conversation(id)!!
            assertTrue(group.isGroup)
            assertEquals("Book club", group.name)
            assertEquals(setOf("me", "+15552223333", "carol@example.com"), imRepo.participants(id).map { it.id }.toSet())
            assertEquals(2, asked(asked, com.lightdecoder.imessage.direct.DirectWire.REACH).size)
        }
    }

    // --- videos through the iMessage library ------------------------------------------------------

    /** A tiny QuickTime file's start: enough to be a .mov here (nothing decodes it in these tests). */
    private val movBytes = byteArrayOf(0, 0, 0, 0x14, 0x66, 0x74, 0x79, 0x70, 0x71, 0x74, 0x20, 0x20, 0, 0, 0, 0, 0x71, 0x74, 0x20, 0x20)

    /** The row of [id] as the thread shows it. */
    private fun shownRow(activity: Activity, id: String): com.lightdecoder.archive.MessageRow? =
        ((activity.findViewById<RecyclerView>(R.id.messages).adapter as MessagesAdapter).currentList)
            .filterIsInstance<com.lightdecoder.ui.chat.ThreadItem.Message>().firstOrNull { it.row.id == id }?.row

    /** Taps [id]'s video and returns what the thread started for it (null: nothing, a toast said why). */
    private fun tapVideo(scenario: ActivityScenario<ThreadActivity>, id: String): Intent? {
        org.robolectric.shadows.ShadowToast.reset()
        scenario.onActivity { a -> a.onMediaTap(shownRow(a, id)!!, shownRow(a, id)!!.media.single()) }
        var started: Intent? = null
        two.waitUntil("the video's tap answered") {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            started = shadowOf(two.app).nextStartedActivity
            val said = org.robolectric.shadows.ShadowToast.getTextOfLatestToast()
            started != null || (said != null && said != two.app.getString(R.string.chat_media_downloading))
        }
        return started
    }

    /**
     * Plays what the thread started ([started]) as a phone does: there the app's data directory is
     * reached through /data/user/0, a link to /data/data where its files really are, so a file's
     * real (canonical) path does not begin with the directory's name. Asserts it plays: the player
     * stays open, without "This attachment is not available".
     */
    private fun assertPlaysOnAPhone(started: Intent?) {
        assertEquals(com.lightdecoder.ui.chat.VideoPlayerActivity::class.java.name, started?.component?.className)
        val path = started!!.getStringExtra("path")!!
        assertTrue("the video's file is here: $path", java.io.File(path).length() > 0)
        val info = two.app.applicationInfo
        val dataDir = info.dataDir
        val real = java.io.File(dataDir).canonicalFile
        val link = java.io.File(two.app.cacheDir.parentFile!!.parentFile, "user0-${System.nanoTime()}")
        java.nio.file.Files.createSymbolicLink(link.toPath(), real.toPath())
        org.robolectric.shadows.ShadowToast.reset()
        try {
            info.dataDir = link.path
            val throughLink = java.io.File(link, java.io.File(path).canonicalFile.toRelativeString(real)).path
            val intent = android.content.Intent(started).setClassName(two.app, com.lightdecoder.ui.chat.VideoPlayerActivity::class.java.name)
                .putExtra("path", throughLink)
            ActivityScenario.launch<com.lightdecoder.ui.chat.VideoPlayerActivity>(intent).use { player ->
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                assertNotEquals(
                    two.app.getString(R.string.chat_media_unavailable),
                    org.robolectric.shadows.ShadowToast.getTextOfLatestToast(),
                )
                player.onActivity { assertFalse("the player stays open", it.isFinishing) }
            }
        } finally {
            info.dataDir = dataDir
            link.delete()
        }
    }

    @Test
    fun withTheLibraryAVideoSentInTheMessagesConversationPlaysHere() {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        directMode(asked)
        val answer = appleSends()
        com.lightdecoder.imessage.direct.DirectLink.installForTesting(two.app) { what, request, _ ->
            asked += what to request
            answer(what, request)
        }
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        directMessage("H1", "Hers", System.currentTimeMillis() - 60_000, mine = false)
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("her iMessage") { shownTexts(it).contains("Hers") }
            scenario.onActivity { activity ->
                val video = java.io.File(activity.cacheDir, "IMG_0042.MOV").apply { writeBytes(movBytes) }
                activity.setAttachment(android.net.Uri.fromFile(video))
                activity.findViewById<View>(R.id.send).performClick()
            }
            // Stored, and its file recorded (just after the row is written, under the link's lock).
            two.waitUntil("the video sent and stored") { imRepo.message("imsg:S1")?.media?.singleOrNull()?.localPath?.isNotEmpty() == true }
            val sent = asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND).map(com.lightdecoder.imessage.direct.DirectWire.SendRequest::fromJson).single()
            assertEquals("video/quicktime", sent.attachments.single().mime)
            // The sent file is the message's: the outbox copy moved to where a download would be kept.
            val part = imRepo.message("imsg:S1")!!.media.single()
            assertTrue(java.io.File(part.localPath).readBytes().contentEquals(movBytes))
            scenario.waitFor("the sent video") { shownRow(it, "imsg:S1")?.media?.singleOrNull()?.localPath == part.localPath }
            assertPlaysOnAPhone(tapVideo(scenario, "imsg:S1"))
            assertTrue("nothing downloaded", asked(asked, com.lightdecoder.imessage.direct.DirectWire.DOWNLOAD).isEmpty())
        }
    }

    @Test
    fun withTheLibraryHerVideoIsDownloadedThroughIMessageThenPlaysHere() {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        directMode(asked)
        com.lightdecoder.imessage.direct.DirectLink.installForTesting(two.app) { what, request, _ ->
            asked += what to request
            if (what == com.lightdecoder.imessage.direct.DirectWire.DOWNLOAD) {
                java.io.File(request.getString("path")).apply { parentFile?.mkdirs(); writeBytes(movBytes) }
                com.lightdecoder.imessage.direct.DirectWire.downloaded(movBytes.size.toLong())
            } else {
                com.lightdecoder.imessage.direct.DirectWire.ok()
            }
        }
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = 1_000, number = "+1 555-123-4567")
        val now = System.currentTimeMillis()
        directMessage("H1", "Hers", now - 120_000, mine = false)
        imRepo.upsertMessage(
            com.lightdecoder.relay.MessageDto(
                id = "imsg:V1", conversationId = ALICE_DIRECT, senderId = "+15551234567", senderName = "Alice", timestampMs = now - 60_000,
                direction = "in", status = "INCOMING_COMPLETE", transport = IMessageManager.TRANSPORT, isOld = true,
                parts = listOf(
                    com.lightdecoder.relay.PartDto(
                        "p0", "media", mediaId = "V1/p0", key = "DESC-V1".toByteArray(), mime = "video/quicktime", fileName = "IMG_7.MOV", size = movBytes.size.toLong(),
                    ),
                ),
            ),
        )
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("her video") { shownRow(it, "imsg:V1") != null }
            // Tapped: downloaded through iMessage (Apple's description), then played.
            val started = tapVideo(scenario, "imsg:V1")
            assertEquals("DESC-V1", asked(asked, com.lightdecoder.imessage.direct.DirectWire.DOWNLOAD).single().getString("descriptor"))
            assertEquals(imRepo.mediaPart("imsg:V1", "p0")!!.localPath, started?.getStringExtra("path"))
            assertPlaysOnAPhone(started)
        }
    }

    @Test
    fun withTheLibraryAVideoSentInTheIMessageChatsOwnScreenPlaysHere() {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        directMode(asked)
        val answer = appleSends()
        com.lightdecoder.imessage.direct.DirectLink.installForTesting(two.app) { what, request, _ ->
            asked += what to request
            answer(what, request)
        }
        directMessage("H1", "Hers", System.currentTimeMillis() - 60_000, mine = false)
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, ALICE_DIRECT, service = ChatService.IMESSAGE).setFlags(0)).use { scenario ->
            scenario.waitFor("her iMessage") { shownTexts(it).contains("Hers") }
            scenario.onActivity { activity ->
                val video = java.io.File(activity.cacheDir, "clip.mp4").apply { writeBytes(movBytes) }
                activity.setAttachment(android.net.Uri.fromFile(video))
                activity.findViewById<View>(R.id.send).performClick()
            }
            two.waitUntil("the video sent and stored") { imRepo.message("imsg:S1") != null }
            val part = imRepo.message("imsg:S1")!!.media.single()
            assertTrue(part.mime, part.mime.startsWith("video/"))
            scenario.waitFor("the sent video") { shownRow(it, "imsg:S1")?.media?.singleOrNull()?.localPath == part.localPath }
            assertPlaysOnAPhone(tapVideo(scenario, "imsg:S1"))
        }
    }

    // --- sharing from another app -----------------------------------------------------------------

    /** The share screen's rows: title and snippet, in order. */
    private fun shareRows(activity: Activity): List<Pair<String, String>> =
        (activity.findViewById<RecyclerView>(R.id.list).adapter as ConversationsAdapter).currentList.map { it.title to it.row.snippet }

    @Test
    fun theShareListIsTheMessagesInboxWithTheCompanionsNewestIMessageAndFollowsIt() {
        two.env.pairedManager()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        directMode(asked)
        val answer = appleSends()
        com.lightdecoder.imessage.direct.DirectLink.installForTesting(two.app) { what, request, _ ->
            asked += what to request
            answer(what, request)
        }
        val now = System.currentTimeMillis()
        // Alice's texts are old; her newest is an iMessage the user sent in her companion chat.
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = now - 600_000, number = "+1 555-123-4567")
        two.seedIncoming(ChatService.MESSAGES, "sms1", "c1", "Old text", ts = now - 599_000)
        two.seedConversation(ChatService.MESSAGES, "c2", "Taylor", lastTs = now - 300_000, number = "+1 770-343-3554")
        two.seedIncoming(ChatService.MESSAGES, "sms2", "c2", "Hi Ramon! This is Taylor", ts = now - 299_000)
        two.seedConversation(ChatService.MESSAGES, "c3", "TEMU", lastTs = now - 400_000, number = "+1 555-000-7777")
        two.seedIncoming(ChatService.MESSAGES, "sms3", "c3", "Deals", ts = now - 399_000)
        directMessage("M1", "You there?", now - 60_000, mine = true)
        launchList().use { list ->
            list.waitFor("the inbox") { titles(it).firstOrNull() == "Alice" }
            var inbox: List<Pair<String, String>> = emptyList()
            list.onActivity { inbox = shareRows(it) }
            assertEquals(listOf("Alice" to "You there?", "Taylor" to "Hi Ramon! This is Taylor", "TEMU" to "Deals"), inbox)
            val share = Intent(Intent.ACTION_SEND).setClassName(two.app, com.lightdecoder.ui.share.ShareActivity::class.java.name)
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Look at this")
            ActivityScenario.launch<com.lightdecoder.ui.share.ShareActivity>(share).use { scenario ->
                // The inbox's own rows, order and snippets: Alice once, with her iMessage.
                scenario.waitFor("the inbox's rows") { shareRows(it) == inbox }
                // While it is open: a text moves TEMU up, then a new iMessage moves Alice back.
                two.seedIncoming(ChatService.MESSAGES, "sms4", "c3", "Flash sale", ts = now - 30_000)
                scenario.waitFor("TEMU up") { shareRows(it).first() == ("TEMU" to "Flash sale") }
                directMessage("M2", "Are you coming?", now - 10_000, mine = false)
                scenario.waitFor("Alice up with her iMessage") { shareRows(it).first() == ("Alice" to "Are you coming?") }
                list.onActivity { inbox = shareRows(it) }
                scenario.onActivity { assertEquals(inbox, shareRows(it)) }
            }
        }
    }

    @Test
    fun aPictureSharedToAMessagesConversationWithAnIMessageChatGoesThroughTheLibrary() {
        two.env.pairedManager()
        installOpenBubbles()
        IMessagePrefs.setEnabled(two.app, true)
        val asked = java.util.Collections.synchronizedList(ArrayList<Pair<Int, org.json.JSONObject>>())
        directMode(asked)
        val answer = appleSends()
        com.lightdecoder.imessage.direct.DirectLink.installForTesting(two.app) { what, request, _ ->
            asked += what to request
            answer(what, request)
        }
        val now = System.currentTimeMillis()
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice", lastTs = now - 600_000, number = "+1 555-123-4567")
        directMessage("M1", "You there?", now - 60_000, mine = true)
        org.robolectric.Robolectric.setupContentProvider(com.lightdecoder.ui.share.SharingAppFiles::class.java, com.lightdecoder.ui.share.SharingAppFiles.AUTHORITY)
        val jpeg = byteArrayOf(-1, -40, -1, -32, 0, 16, 74, 70, 73, 70)
        val shared = com.lightdecoder.ui.share.SharingAppFiles.add(two.app, "IMG_5.jpg", jpeg)
        val share = Intent(Intent.ACTION_SEND).setClassName(two.app, com.lightdecoder.ui.share.ShareActivity::class.java.name)
            .setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, shared)
        var started: Intent? = null
        ActivityScenario.launch<com.lightdecoder.ui.share.ShareActivity>(share).use { scenario ->
            scenario.waitFor("Alice, as in the inbox") { shareRows(it) == listOf("Alice" to "You there?") }
            scenario.onActivity { a ->
                val list = a.findViewById<RecyclerView>(R.id.list)
                list.measure(0, 0)
                list.layout(0, 0, 1080, 2000)
                list.findViewHolderForAdapterPosition(0)!!.itemView.performClick()
            }
            two.waitUntil("the conversation opened") {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                started = shadowOf(two.app).nextStartedActivity
                started != null
            }
        }
        // Her Messages conversation, the picture in its composer: nothing sent yet.
        assertEquals(ThreadActivity::class.java.name, started!!.component?.className)
        assertEquals("c1", started!!.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID))
        assertEquals(ChatService.MESSAGES, RelayIntents.serviceOf(started!!))
        assertTrue(asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND).isEmpty())
        ActivityScenario.launch<ThreadActivity>(Intent(started).setFlags(0)).use { scenario ->
            scenario.waitFor("the picture ready, to go as iMessage") {
                it.findViewById<View>(R.id.attachmentBar).isShown && it.findViewById<View>(R.id.send).isEnabled &&
                    it.findViewById<EditText>(R.id.input).hint.toString() == it.getString(R.string.imessage_compose_hint)
            }
            scenario.onActivity { it.findViewById<View>(R.id.send).performClick() }
            two.waitUntil("sent through the library") { asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND).size == 1 }
            val sent = com.lightdecoder.imessage.direct.DirectWire.SendRequest.fromJson(asked(asked, com.lightdecoder.imessage.direct.DirectWire.SEND).single())
            assertEquals("image/jpeg", sent.attachments.single().mime)
            two.waitUntil("stored in her iMessage chat") { imRepo.message("imsg:S1")?.conversationId == ALICE_DIRECT }
            assertTrue(java.io.File(imRepo.message("imsg:S1")!!.media.single().localPath).readBytes().contentEquals(jpeg))
            // Nothing handed to OpenBubbles.
            scenario.onActivity { assertNull(shadowOf(it).nextStartedActivity) }
        }
    }

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

        /** Alice's iMessage chat as the library names it. */
        const val ALICE_DIRECT = "imsg:tel:+15551234567"
    }
}
