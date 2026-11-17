package com.lightdecoder.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableString
import android.text.style.URLSpan
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.tabs.TabLayout
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.MediaRow
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.relay.ContactDto
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.Folder
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.ReactionDto
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.RelayTestEnv
import com.lightdecoder.ui.chat.BubbleLinks
import com.lightdecoder.ui.chat.ConversationsActivity
import com.lightdecoder.ui.chat.MediaLoader
import com.lightdecoder.ui.chat.MessagesAdapter
import com.lightdecoder.ui.chat.NewConversationActivity
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.ui.chat.ThreadItem
import com.lightdecoder.ui.chat.ThreadItems
import com.lightdecoder.ui.relay.RelayStatusActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

/**
 * The UI half of the 0.3.0 known-limitations pass: links in bubbles for
 * TalkBack, the reply target kept across a rotation, "Not spam", the local
 * Deleted folder, the contact picker's offline hint and the battery
 * exemption button.
 */
@RunWith(AndroidJUnit4::class)
class LinkLimitsTest {

    private lateinit var env: RelayTestEnv
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        env = RelayTestEnv()
        ArchiveRepository.installForTesting(env.repo)
    }

    @After
    fun tearDown() {
        MessageNotifier.visibleConversationId = null
        env.tearDown()
        ArchiveRepository.resetForTesting()
    }

    // --- links in bubbles -----------------------------------------------------------------

    @Test
    fun linksAreFoundInReadingOrderWithoutRepeats() {
        val text = SpannableString("b a b c")
        text.setSpan(URLSpan("https://b.example"), 0, 1, 0)
        text.setSpan(URLSpan("https://a.example"), 2, 3, 0)
        text.setSpan(URLSpan("https://b.example"), 4, 5, 0)
        assertEquals(listOf("b", "a"), BubbleLinks.linksIn(text).map { it.label })
        assertTrue(BubbleLinks.linksIn("plain").isEmpty())
        assertTrue(BubbleLinks.linksIn(null).isEmpty())

        val many = SpannableString((1..15).joinToString(" ") { "l$it" })
        var at = 0
        for (i in 1..15) {
            val word = "l$i"
            many.setSpan(URLSpan("https://$word.example"), at, at + word.length, 0)
            at += word.length + 1
        }
        assertEquals(BubbleLinks.MAX_LINKS, BubbleLinks.linksIn(many).size)
    }

    @Test
    fun eachLinkInABubbleIsATalkBackActionThatOpensIt() {
        val (manager, _) = env.pairedManager()
        seedConversation("c1", "Alice")
        env.repo.upsertMessage(textMessage("m1", "c1", 1_000, "See https://example.com/a and www.example.org today"))
        env.repo.upsertMessage(textMessage("m2", "c1", 2_000, "No links here"))
        val rows = env.repo.messages("c1", limit = 10)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        // As in the thread screen, the bubble's context is an activity's.
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val context = ContextThemeWrapper(host.get(), R.style.Theme_LightDecoder)
            val adapter = MessagesAdapter(MediaLoader(manager, scope), NoCallbacks)
            val items = ThreadItems.build(rows, false, emptyMap(), null, null, { 0L }, { "" })
                .filterIsInstance<ThreadItem.Message>()
            adapter.submitList(items)
            val parent = FrameLayout(context)
            val withLinks = items.indexOfFirst { it.row.id == "m1" }
            val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(withLinks)) as MessagesAdapter.MessageHolder
            adapter.onBindViewHolder(holder, withLinks)

            val bubble = holder.binding.bubble
            val first = app.getString(R.string.chat_open_link, "https://example.com/a")
            val second = app.getString(R.string.chat_open_link, "www.example.org")
            val labels = bubble.createAccessibilityNodeInfo().actionList.mapNotNull { it.label?.toString() }
            assertTrue(labels.toString(), labels.containsAll(listOf(first, second)))
            assertTrue("reply is still offered", labels.contains(app.getString(R.string.chat_action_reply)))

            // Activating one opens the link.
            val action = bubble.createAccessibilityNodeInfo().actionList.first { it.label?.toString() == first }
            assertTrue(bubble.performAccessibilityAction(action.id, null))
            val opened = shadowOf(host.get()).nextStartedActivity
            assertNotNull("nothing opened", opened)
            assertEquals(Intent.ACTION_VIEW, opened.action)
            assertEquals("https://example.com/a", opened.data.toString())

            // Rebinding the holder to a message without links drops them; rebinding again adds them once.
            val plain = items.indexOfFirst { it.row.id == "m2" }
            adapter.onBindViewHolder(holder, plain)
            val after = bubble.createAccessibilityNodeInfo().actionList.mapNotNull { it.label?.toString() }
            assertTrue(after.toString(), after.none { it.startsWith(app.getString(R.string.chat_open_link, "")) })
            adapter.onBindViewHolder(holder, withLinks)
            adapter.onBindViewHolder(holder, withLinks)
            val again = bubble.createAccessibilityNodeInfo().actionList.mapNotNull { it.label?.toString() }
            assertEquals(1, again.count { it == first })
        } finally {
            scope.cancel()
            host.pause().stop().destroy()
        }
    }

    // --- the reply target across a rotation ---------------------------------------------------

    @Test
    fun theReplyTargetSurvivesARotation() {
        env.pairedManager()
        seedConversation("c1", "Alice")
        env.repo.upsertMessage(textMessage("m1", "c1", 1_000, "Lunch?"))
        val controller = Robolectric.buildActivity(ThreadActivity::class.java, RelayIntents.thread(app, "c1").setFlags(0)).setup()
        try {
            settle()
            controller.get().onReply(env.repo.message("m1")!!)
            assertEquals(View.VISIBLE, controller.get().findViewById<View>(R.id.replyBar).visibility)

            controller.recreate()
            waitUntil("the reply bar is back") { controller.get().findViewById<View>(R.id.replyBar).visibility == View.VISIBLE }
            val activity = controller.get()
            assertEquals("Lunch?", activity.findViewById<TextView>(R.id.replyText).text.toString())
            assertEquals(
                app.getString(R.string.chat_replying_to, "Someone"),
                activity.findViewById<TextView>(R.id.replySender).text.toString(),
            )

            // Closed before the next rotation: it stays closed.
            activity.findViewById<View>(R.id.replyClose).performClick()
            controller.recreate()
            settle()
            assertEquals(View.GONE, controller.get().findViewById<View>(R.id.replyBar).visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    // --- "Not spam" -------------------------------------------------------------------------

    @Test
    fun notSpamMovesAConversationToTheInboxFromTheListAndFromTheThread() {
        val (manager, fake) = env.pairedManager()
        val alice = seedConversation("c1", "Alice", folder = "spam", lastTs = 2_000)
        val bob = seedConversation("c2", "Bob", folder = "spam", lastTs = 1_000)
        fake.conversations += listOf(alice, bob)
        fake.goOnline()
        env.eventually(manager, what = "online") { manager.state.value.isOnline }

        ActivityScenario.launch(ConversationsActivity::class.java).use { scenario ->
            scenario.onActivity { it.findViewById<TabLayout>(R.id.folders).getTabAt(2)!!.select() }
            scenario.waitFor("both spam rows") { it.findViewById<RecyclerView>(R.id.list).childCount == 2 }
            scenario.onActivity { activity ->
                activity.findViewById<RecyclerView>(R.id.list).getChildAt(0).performLongClick()
                val actions = listItems(latestDialog())
                assertTrue(actions.toString(), actions.contains(activity.getString(R.string.chat_action_not_spam)))
                assertFalse(actions.contains(activity.getString(R.string.chat_action_archive)))
                clickItem(latestDialog(), activity.getString(R.string.chat_action_not_spam))
            }
            scenario.waitFor("Alice in the inbox") { env.repo.conversation("c1")?.folderEnum == Folder.INBOX }
            assertTrue(fake.calls.toString(), fake.calls.contains("setConversationStatus:not_spam"))
            scenario.waitFor("only Bob left in Spam") { it.findViewById<RecyclerView>(R.id.list).childCount == 1 }
        }

        val controller = Robolectric.buildActivity(ThreadActivity::class.java, RelayIntents.thread(app, "c2").setFlags(0)).setup()
        try {
            waitUntil("Bob's thread") { controller.get().supportActionBar?.title?.toString() == "Bob" }
            val activity = controller.get()
            val menu = activity.findViewById<Toolbar>(R.id.toolbar).menu
            val notSpam = menu.findItem(R.id.action_not_spam)
            assertTrue("offered in spam", notSpam.isVisible)
            assertFalse(menu.findItem(R.id.action_restore).isVisible)
            activity.onOptionsItemSelected(notSpam)
            waitUntil("Bob in the inbox") { env.repo.conversation("c2")?.folderEnum == Folder.INBOX }
            waitUntil("no longer offered") { !activity.findViewById<Toolbar>(R.id.toolbar).menu.findItem(R.id.action_not_spam).isVisible }
            assertTrue(activity.findViewById<Toolbar>(R.id.toolbar).menu.findItem(R.id.action_archive).isVisible)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    // --- the local Deleted folder -----------------------------------------------------------

    @Test
    fun theDeletedFolderRestoresDeletesForeverAndEmpties() {
        val (_, fake) = env.pairedManager()
        seedConversation("c1", "Alice", lastTs = 3_000)
        seedConversation("c2", "Bob", lastTs = 2_000)
        seedConversation("c3", "Carol", lastTs = 1_000)
        seedConversation("c4", "Dave", lastTs = 4_000)
        listOf("c1", "c2", "c3").forEach { env.repo.setConversationFolder(it, Folder.DELETED) }

        ActivityScenario.launch(ConversationsActivity::class.java).use { scenario ->
            scenario.waitFor("Dave in the inbox") { it.findViewById<RecyclerView>(R.id.list).childCount == 1 }
            scenario.onActivity { it.findViewById<TabLayout>(R.id.folders).getTabAt(3)!!.select() }
            scenario.waitFor("three deleted rows") { it.findViewById<RecyclerView>(R.id.list).childCount == 3 }
            scenario.onActivity { activity ->
                val menu = activity.findViewById<Toolbar>(R.id.toolbar).menu
                assertTrue("Empty offered", menu.findItem(R.id.action_empty_deleted).isVisible)
                assertFalse("nothing to ask the phone for", menu.findItem(R.id.action_refresh).isVisible)
                assertFalse(activity.findViewById<View>(R.id.swipe).isEnabled)

                // Restore and Delete forever only: mute and a delete on the phone make no sense here.
                activity.findViewById<RecyclerView>(R.id.list).getChildAt(0).performLongClick()
                assertEquals(
                    listOf(activity.getString(R.string.chat_action_restore), activity.getString(R.string.chat_action_delete_forever)),
                    listItems(latestDialog()),
                )
                clickItem(latestDialog(), activity.getString(R.string.chat_action_restore))
            }
            scenario.waitFor("Alice restored") { env.repo.conversation("c1")?.folderEnum == Folder.INBOX }
            scenario.waitFor("two left") { it.findViewById<RecyclerView>(R.id.list).childCount == 2 }

            scenario.onActivity { activity ->
                activity.findViewById<RecyclerView>(R.id.list).getChildAt(0).performLongClick()
                clickItem(latestDialog(), activity.getString(R.string.chat_action_delete_forever))
                val confirm = latestDialog()
                assertTrue(hasText(confirm.window!!.decorView, activity.getString(R.string.chat_delete_forever_body)))
                confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            scenario.waitFor("Bob gone for good") { env.repo.conversation("c2") == null }
            scenario.waitFor("one left") { it.findViewById<RecyclerView>(R.id.list).childCount == 1 }

            scenario.onActivity { activity ->
                val menu = activity.findViewById<Toolbar>(R.id.toolbar).menu
                activity.onOptionsItemSelected(menu.findItem(R.id.action_empty_deleted))
                latestDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            scenario.waitFor("Deleted is empty") {
                it.findViewById<TextView>(R.id.emptyText).let { t ->
                    t.isShown && t.text.toString() == it.getString(R.string.chat_empty_deleted)
                }
            }
            assertNull(env.repo.conversation("c3"))
            assertNotNull("the inbox is untouched", env.repo.conversation("c4"))
            assertNotNull(env.repo.conversation("c1"))
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<Toolbar>(R.id.toolbar).menu.findItem(R.id.action_empty_deleted).isVisible)
            }
        }
        // Nothing of this went to the phone.
        assertTrue(fake.calls.toString(), fake.calls.none { it.startsWith("deleteConversation") || it.startsWith("setConversationStatus") })
    }

    @Test
    fun aDeletedConversationsThreadIsReadOnlyAndCanBeRestoredOrDeletedForever() {
        env.pairedManager()
        seedConversation("c1", "Alice")
        seedConversation("c2", "Bob")
        env.repo.upsertMessage(textMessage("m1", "c1", 1_000, "Hello"))
        env.repo.setConversationFolder("c1", Folder.DELETED)
        env.repo.setConversationFolder("c2", Folder.DELETED)

        val controller = Robolectric.buildActivity(ThreadActivity::class.java, RelayIntents.thread(app, "c1").setFlags(0)).setup()
        try {
            waitUntil("Alice's thread") { controller.get().supportActionBar?.title?.toString() == "Alice" }
            val activity = controller.get()
            val menu = activity.findViewById<Toolbar>(R.id.toolbar).menu
            assertTrue(menu.findItem(R.id.action_restore).isVisible)
            assertTrue(menu.findItem(R.id.action_delete_forever).isVisible)
            assertFalse(menu.findItem(R.id.action_mute).isVisible)
            assertFalse(menu.findItem(R.id.action_delete_conversation).isVisible)
            assertFalse(menu.findItem(R.id.action_not_spam).isVisible)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.composer).visibility)
            assertEquals(
                app.getString(R.string.chat_deleted_banner),
                activity.findViewById<TextView>(R.id.readOnlyText).text.toString(),
            )

            activity.onOptionsItemSelected(menu.findItem(R.id.action_restore))
            waitUntil("Alice restored") { env.repo.conversation("c1")?.folderEnum == Folder.INBOX }
            waitUntil("the composer back") { activity.findViewById<View>(R.id.composer).visibility == View.VISIBLE }
            assertFalse(activity.findViewById<Toolbar>(R.id.toolbar).menu.findItem(R.id.action_restore).isVisible)
        } finally {
            controller.pause().stop().destroy()
        }

        val bob = Robolectric.buildActivity(ThreadActivity::class.java, RelayIntents.thread(app, "c2").setFlags(0)).setup()
        try {
            waitUntil("Bob's thread") { bob.get().supportActionBar?.title?.toString() == "Bob" }
            val activity = bob.get()
            activity.onOptionsItemSelected(activity.findViewById<Toolbar>(R.id.toolbar).menu.findItem(R.id.action_delete_forever))
            latestDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitUntil("Bob gone for good") { env.repo.conversation("c2") == null }
            waitUntil("the thread closed") { activity.isFinishing }
        } finally {
            bob.pause().stop().destroy()
        }
    }

    // --- the contact picker's offline hint ----------------------------------------------------

    @Test
    fun thePickerSaysWhenItShowsTheSavedContactsBecauseThePhoneWasNotReached() {
        val (manager, fake) = env.pairedManager()
        env.repo.upsertContacts(listOf(ContactDto(id = "k1", name = "Alice", number = "+15550100")))

        // Offline: the saved list shows, and the hint says so.
        ActivityScenario.launch(NewConversationActivity::class.java).use { scenario ->
            scenario.waitFor("the saved contact") { it.findViewById<RecyclerView>(R.id.contacts).childCount == 1 }
            scenario.waitFor("the offline hint") { it.findViewById<View>(R.id.offlineHint).isShown }
            scenario.waitFor("done asking") { it.findViewById<View>(R.id.progress).visibility == View.GONE }
        }

        // Online: the phone's list, no hint.
        fake.contacts += listOf(ContactDto(id = "k1", name = "Alice", number = "+15550100"), ContactDto(id = "k2", name = "Bob", number = "+15550101"))
        fake.goOnline()
        env.eventually(manager, what = "online") { manager.state.value.isOnline }
        ActivityScenario.launch(NewConversationActivity::class.java).use { scenario ->
            scenario.waitFor("the phone's contacts") { it.findViewById<RecyclerView>(R.id.contacts).childCount == 2 }
            scenario.waitFor("done asking") { it.findViewById<View>(R.id.progress).visibility == View.GONE }
            scenario.onActivity { assertEquals(View.GONE, it.findViewById<View>(R.id.offlineHint).visibility) }
        }
    }

    // --- battery optimisation --------------------------------------------------------------

    @Test
    fun theStatusScreenAsksForTheBatteryExemptionOnlyWhileNotExempt() {
        val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue(requested.contains(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS))

        env.pairedManager()
        val power = shadowOf(app.getSystemService(PowerManager::class.java))
        power.setIgnoringBatteryOptimizations(app.packageName, false)
        // The system's question exists on this "device".
        val ask = RelayIntents.batteryExemption(app)
        shadowOf(app.packageManager).addResolveInfoForIntent(
            ask,
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = "com.android.settings"
                    name = "RequestIgnoreBatteryOptimizations"
                }
            },
        )
        ActivityScenario.launch(RelayStatusActivity::class.java).use { scenario ->
            scenario.waitFor("the button") { it.findViewById<View>(R.id.batteryExempt).isShown }
            scenario.onActivity { activity ->
                assertEquals(
                    activity.getString(R.string.link_battery_optimized),
                    activity.findViewById<TextView>(R.id.batteryText).text.toString(),
                )
                activity.findViewById<View>(R.id.batteryExempt).performClick()
                val started = shadowOf(activity).nextStartedActivity
                assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, started?.action)
                assertEquals("package:${app.packageName}", started?.data?.toString())
            }
            // The user allowed it; back on the screen the button goes.
            power.setIgnoringBatteryOptimizations(app.packageName, true)
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.findViewById<View>(R.id.batteryExempt).visibility)
                assertEquals(
                    activity.getString(R.string.link_battery_exempt),
                    activity.findViewById<TextView>(R.id.batteryText).text.toString(),
                )
            }
        }
    }

    // --- helpers ----------------------------------------------------------------------

    private object NoCallbacks : MessagesAdapter.Callbacks {
        override fun onMessageLongPress(row: MessageRow) = Unit
        override fun onFailedTap(row: MessageRow) = Unit
        override fun onMediaTap(row: MessageRow, part: MediaRow) = Unit
        override fun onLoadFullSize(row: MessageRow, part: MediaRow) = Unit
        override fun onQuoteTap(messageId: String) = Unit
        override fun onReactionTap(row: MessageRow, reaction: ReactionDto) = Unit
        override fun onReply(row: MessageRow) = Unit
    }

    private fun textMessage(id: String, conversationId: String, ts: Long, text: String) = MessageDto(
        id = id, conversationId = conversationId, senderId = "p1", senderName = "Someone", timestampMs = ts,
        isOld = true, text = text,
    )

    private fun seedConversation(id: String, name: String, folder: String = "inbox", lastTs: Long = 1_000): ConversationDto {
        val dto = ConversationDto(
            id = id,
            name = name,
            folder = folder,
            lastMessageTs = lastTs,
            participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = "p1", name = name)),
        )
        env.repo.upsertConversation(dto)
        return dto
    }

    private fun listItems(dialog: AlertDialog): List<String> =
        (0 until dialog.listView.adapter.count).map { dialog.listView.adapter.getItem(it).toString() }

    private fun clickItem(dialog: AlertDialog, label: String) {
        val index = listItems(dialog).indexOf(label)
        assertTrue("no \"$label\" in ${listItems(dialog)}", index >= 0)
        dialog.listView.performItemClick(null, index, 0)
    }

    private fun latestDialog(): AlertDialog {
        settle(1)
        val dialog: Dialog? = ShadowDialog.getLatestDialog()
        assertTrue("no question: $dialog", dialog is AlertDialog && dialog.isShowing)
        return dialog as AlertDialog
    }

    private fun hasText(root: View, text: String): Boolean {
        if (root is TextView && root.text.toString() == text) return true
        if (root is ViewGroup) for (i in 0 until root.childCount) if (hasText(root.getChildAt(i), text)) return true
        return false
    }

    private fun settle(rounds: Int = 5) {
        repeat(rounds) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            Thread.sleep(10)
        }
    }

    private fun waitUntil(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(20)
        }
    }

    private fun <A : Activity> ActivityScenario<A>.waitFor(what: String, timeoutMs: Long = 10_000, condition: (A) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            var ok = false
            onActivity { ok = condition(it) }
            if (ok) return
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(20)
        }
    }
}
