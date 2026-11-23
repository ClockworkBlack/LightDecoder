package com.lightdecoder.ui

import android.app.Activity
import android.app.NotificationManager
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageLog
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.IMessagePrefs
import com.lightdecoder.imessage.direct.DirectArchive
import com.lightdecoder.imessage.direct.DirectLibrary
import com.lightdecoder.imessage.direct.DirectLink
import com.lightdecoder.imessage.direct.DirectPrefs
import com.lightdecoder.imessage.direct.DirectWire
import com.lightdecoder.imessage.direct.FaceTimeState
import com.lightdecoder.imessage.facetime.CallRows
import com.lightdecoder.imessage.facetime.Outcome
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.Folder
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.chat.CallList
import com.lightdecoder.ui.chat.CallsActivity
import com.lightdecoder.ui.chat.ConversationsActivity
import com.lightdecoder.ui.chat.NewConversationActivity
import com.lightdecoder.ui.chat.ReturnToCallBar
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.ui.imessage.FaceTimeCallActivity
import java.io.File
import java.util.Calendar
import java.util.Collections
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The FaceTime list ([CallsActivity]: every call's row but those of Deleted and Spam chats, newest
 * first by day; a tap calls again, a long press opens the chat or deletes the row), its entry in the
 * chats' menu with the missed calls, New FaceTime, and the "return to call" bar of the chats and a
 * chat, which follows where FaceTime stands as the ":imessage" process tells it (SPEC2 B2).
 */
@RunWith(AndroidJUnit4::class)
class CallsScreenTest {

    private lateinit var two: TwoServicesEnv
    private lateinit var imDb: ArchiveDb
    private lateinit var imRepo: ArchiveRepository
    private val nm: NotificationManager get() = two.app.getSystemService(NotificationManager::class.java)
    private val asked: MutableList<Pair<Int, JSONObject>> = Collections.synchronizedList(ArrayList())

    /** What the ":imessage" process answers an FT_STATE with. */
    @Volatile
    private var ftState: FaceTimeState = FaceTimeState.NONE

    @Before
    fun setUp() {
        two = TwoServicesEnv()
        two.app.deleteDatabase(IM_DB)
        imDb = ArchiveDb(two.app, IM_DB)
        imRepo = ArchiveRepository.forTesting(imDb, ChatService.IMESSAGE)
        ArchiveRepository.installForTesting(imRepo)
        IMessageManager.installForTesting(two.app, imRepo)
        IMessagePrefs.setEnabled(two.app, true)
        DirectPrefs.setFaceTimeOn(two.app, true)
        IMessageLog.clear(two.app)
        two.app.getSharedPreferences("facetime_calls", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        two.app.getSharedPreferences("imessage_facetime_link", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        nm.cancelAll()
        directMode()
    }

    @After
    fun tearDown() {
        nm.cancelAll()
        IMessagePrefs.setEnabled(two.app, false)
        DirectPrefs.setRegistered(two.app, false)
        DirectLink.resetForTesting()
        IMessageManager.resetForTesting()
        two.tearDown()
        imDb.close()
    }

    private fun directMode() {
        DirectPrefs.setRegistered(two.app, true)
        DirectLibrary.REQUIRED.forEach { File(DirectLibrary.dir(two.app), it).apply { parentFile?.mkdirs(); writeText("") } }
        DirectLink.installForTesting(two.app) { what, request, _ ->
            asked += what to request
            when (what) {
                DirectWire.FT_CAPABLE -> DirectWire.capable(DirectWire.CapableRequest.fromJson(request).targets)
                DirectWire.FT_STATE -> ftState.toJson()
                else -> DirectWire.ok()
            }
        }
    }

    private val link: DirectLink get() = DirectLink.get(two.app)

    private fun chat(id: String, name: String, number: String, folder: String = "inbox") = imRepo.upsertConversation(
        ConversationDto(
            id = id, name = name, type = IMessageManager.TRANSPORT, defaultOutgoingId = ME, folder = folder,
            participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = number, name = name, number = number, formattedNumber = number)),
        ),
    )

    private fun group() = imRepo.upsertConversation(
        ConversationDto(
            id = GROUP, name = "Hikers", isGroup = true, type = IMessageManager.TRANSPORT, defaultOutgoingId = ME,
            participants = listOf(
                ParticipantDto(id = "me", isMe = true),
                ParticipantDto(id = "+15551234567", name = "Alice Martin", number = "+15551234567"),
                ParticipantDto(id = "+15557654321", name = "Bob", number = "+15557654321"),
            ),
        ),
    )

    private fun call(guid: String, peer: String, outcome: String, startedMs: Long, outgoing: Boolean = false, conversationId: String = "", group: Boolean = false, members: List<String> = emptyList()) =
        link.apply(
            DirectWire.decode(
                JSONObject(
                    DirectWire.encode(DirectArchive.Update.Call(guid, peer, outgoing, true, startedMs, if (outcome == "answered") 65_000 else 0, outcome, false, conversationId, group, members)).toString(),
                ),
            ),
        )

    private fun <A : Activity> ActivityScenario<A>.waitFor(what: String, condition: (A) -> Boolean) =
        two.waitUntil(what) {
            var ok = false
            onActivity { ok = condition(it) }
            ok
        }

    private fun rows(activity: CallsActivity) = activity.items.filterIsInstance<CallList.Item.Row>().map { it.call }

    private val dayMs = 24 * 60 * 60 * 1000L

    // --- the list --------------------------------------------------------------------------------------

    @Test
    fun theFaceTimeListShowsEveryCallNewestFirstByDayButNoneOfDeletedOrSpamChats() {
        val now = System.currentTimeMillis()
        chat(ALICE_CHAT, "Alice Martin", "+15551234567")
        chat(BOB_CHAT, "Bob", "+15557654321")
        chat(CAROL_CHAT, "Carol", "+15553334444")
        group()
        call(guid(1), ALICE, "missed", now - 60_000)
        call(guid(2), ALICE, "answered", now - 2 * dayMs, outgoing = true, conversationId = ALICE_CHAT)
        call(guid(3), BOB, "missed", now - 30_000)
        call(guid(4), CAROL, "declined", now - 90_000)
        call(guid(5), ALICE, "answered", now - 3 * dayMs, outgoing = true, conversationId = GROUP, group = true, members = listOf(ALICE, BOB))
        imRepo.setConversationFolder(BOB_CHAT, Folder.SPAM)
        imRepo.setConversationFolder(CAROL_CHAT, Folder.DELETED)
        ActivityScenario.launch(CallsActivity::class.java).use { scenario ->
            scenario.waitFor("the calls") { rows(it).size == 3 }
            scenario.onActivity { activity ->
                val calls = rows(activity)
                assertEquals("newest first, none of Spam or Deleted", listOf(guid(1), guid(2), guid(5)).map(CallRows::messageId), calls.map { it.messageId })
                assertEquals(listOf("Alice Martin", "Alice Martin", "Hikers"), calls.map { it.title })
                assertTrue(calls[0].missed)
                assertFalse(calls[1].missed)
                assertTrue(calls[2].group)
                val days = activity.items.filterIsInstance<CallList.Item.Day>()
                assertEquals(3, days.size)
                assertEquals("Today", days.first().label)
                assertTrue(activity.items.first() is CallList.Item.Day)
                assertFalse(activity.findViewById<View>(R.id.empty).isShown)
                assertTrue(two.hasText(activity.window.decorView, "Missed FaceTime Video"))
                assertTrue(two.hasText(activity.window.decorView, "FaceTime Video · 1 min"))
            }
        }
    }

    @Test
    fun aTapCallsAgainAndALongPressOpensTheChatOrDeletesTheRow() {
        val now = System.currentTimeMillis()
        chat(ALICE_CHAT, "Alice Martin", "+15551234567")
        group()
        call(guid(1), ALICE, "missed", now - 60_000)
        call(guid(2), ALICE, "answered", now - 120_000, outgoing = true, conversationId = GROUP, group = true, members = listOf(ALICE, BOB))
        ActivityScenario.launch(CallsActivity::class.java).use { scenario ->
            scenario.waitFor("the calls") { rows(it).size == 2 }
            // A person's call: to them again.
            scenario.onActivity { it.place(rows(it).first(), video = false) }
            two.waitUntil("the call screen") { shadowOf(two.app).peekNextStartedActivity() != null }
            val one = shadowOf(two.app).nextStartedActivity
            assertEquals(FaceTimeCallActivity::class.java.name, one.component?.className)
            assertEquals(FaceTimeCallActivity.ACTION_PLACE, one.action)
            assertEquals(ALICE, one.getStringExtra(FaceTimeCallActivity.EXTRA_PEER))
            assertFalse(one.getBooleanExtra(FaceTimeCallActivity.EXTRA_VIDEO, true))
            assertEquals(ALICE_CHAT, one.getStringExtra(FaceTimeCallActivity.EXTRA_CONVERSATION))
            // A group's: to its members again.
            scenario.onActivity { it.place(rows(it)[1], video = true) }
            two.waitUntil("the call screen again") { shadowOf(two.app).peekNextStartedActivity() != null }
            val group = shadowOf(two.app).nextStartedActivity
            assertEquals(listOf(ALICE, BOB), group.getStringArrayListExtra(FaceTimeCallActivity.EXTRA_PEERS))
            assertEquals(GROUP, group.getStringExtra(FaceTimeCallActivity.EXTRA_CONVERSATION))
            assertTrue(group.getBooleanExtra(FaceTimeCallActivity.EXTRA_VIDEO, false))
            // Open Chat; Delete takes the row away.
            scenario.onActivity { it.openChat(rows(it).first()) }
            val opened = shadowOf(two.app).nextStartedActivity
            assertEquals(ThreadActivity::class.java.name, opened.component?.className)
            assertEquals(ALICE_CHAT, opened.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID))
            scenario.onActivity { it.delete(rows(it).first()) }
            scenario.waitFor("one row left") { rows(it).size == 1 }
            assertNull(imRepo.message(CallRows.messageId(guid(1))))
            // A row of a tapped list item asks Video or Audio first.
            scenario.onActivity { activity ->
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.calls)
                val row = (0 until list.childCount).map { list.getChildAt(it) }.first { it.findViewById<TextView>(R.id.name) != null }
                row.performClick()
            }
            two.settle()
            val popup = org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu()
            assertNotNull(popup)
            assertEquals(listOf("FaceTime Video", "FaceTime Audio"), (0 until popup.menu.size()).map { popup.menu.getItem(it).title.toString() })
        }
    }

    @Test
    fun theListIsEmptyAndSaysSoAndNewFaceTimeSaysWhatEveryoneSees() {
        two.env.pairedManager()
        ActivityScenario.launch(CallsActivity::class.java).use { scenario ->
            scenario.waitFor("empty") { it.findViewById<View>(R.id.empty).isShown }
            scenario.onActivity { it.findViewById<View>(R.id.newCall).performClick() }
            val picker = shadowOf(two.app).nextStartedActivity
            assertEquals(NewConversationActivity::class.java.name, picker.component?.className)
            assertTrue(picker.getBooleanExtra(NewConversationActivity.EXTRA_FACETIME, false))
        }
        ActivityScenario.launch<NewConversationActivity>(NewConversationActivity.newFaceTime(two.app)).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.faceTimeHint).isShown)
                assertEquals("Everyone you add can see who else is in the call.", activity.findViewById<TextView>(R.id.faceTimeHint).text.toString())
                assertEquals("New FaceTime", activity.supportActionBar?.title.toString())
                activity.placeFaceTime(listOf(ALICE, BOB), "Alice & Bob", video = true)
            }
            val started = shadowOf(two.app).nextStartedActivity
            assertEquals(FaceTimeCallActivity.ACTION_PLACE, started.action)
            assertEquals(listOf(ALICE, BOB), started.getStringArrayListExtra(FaceTimeCallActivity.EXTRA_PEERS))
            assertNull("from no chat", started.getStringExtra(FaceTimeCallActivity.EXTRA_CONVERSATION))
        }
    }

    @Test
    fun aHiddenPersonsCallsAreLeftOutWhicheverWayTheyWent() {
        fun chat(group: Boolean) = com.lightdecoder.archive.ConversationRow(
            "chat", "T", group, false, "inbox", 0, false, false, false, false, 0, "", "", false, "", "", "", 0,
        )
        fun row(outgoing: Boolean) = com.lightdecoder.archive.MessageRow(
            CallRows.messageId("G"), "chat", "", if (outgoing) "" else "+1555", "", outgoing, 1_000, if (outgoing) "out" else "in",
            "", 0, "", "", "", "", "", emptyList(), "", emptyList(), 0,
            extra = com.lightdecoder.imessage.direct.IMessageExtra(call = com.lightdecoder.imessage.direct.IMessageExtra.CallInfo("G", outgoing, true, "answered")).toJson(),
        )
        val blocked: (String) -> Boolean = { it == "+1555" }
        // A call to the person of a chat with them, and one from them: both left out.
        assertNull(CallList.call(row(outgoing = true), chat(false), "T", null, peer = "+1555", hidden = blocked))
        assertNull(CallList.call(row(outgoing = false), chat(false), "T", null, peer = "+1555", hidden = blocked))
        // Someone else's: listed.
        assertNotNull(CallList.call(row(outgoing = true), chat(false), "T", null, peer = "+1666", hidden = blocked))
        // A group's placed call is not left out by the one person who might be among them.
        assertNotNull(CallList.call(row(outgoing = true), chat(true), "T", null, peer = "+1555", hidden = blocked))
    }

    @Test
    fun theDaysAndTheMissedCallsAreCountedAsThePersonSeesThem() {
        val texts = object : CallList.DayTexts {
            override fun today() = "Today"
            override fun yesterday() = "Yesterday"
            override fun date(ms: Long) = "D$ms"
        }
        val noon = Calendar.getInstance().apply { set(2026, 10, 23, 12, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        fun c(id: String, at: Long, outcome: Outcome = Outcome.MISSED, outgoing: Boolean = false) =
            CallList.Call(id, "chat", "T", false, null, true, outgoing, outcome, at, 0, if (outgoing) "" else "+1555")
        val items = CallList.items(listOf(c("a", noon - 60_000), c("b", noon - 13 * 60 * 60 * 1000L), c("c", noon - 3 * dayMs)), noon, texts)
        assertEquals(listOf("Today", "a", "Yesterday", "b", "D${noon - 3 * dayMs}", "c"), items.map { if (it is CallList.Item.Day) it.label else (it as CallList.Item.Row).call.messageId })
        assertTrue(c("x", 0).missed)
        assertFalse(c("x", 0, outgoing = true).missed)
        assertFalse(c("x", 0, Outcome.DECLINED).missed)
    }

    // --- the chats' menu and the bar ---------------------------------------------------------------------

    @Test
    fun theChatsMenuHasTheFaceTimeListWithTheMissedCallsSinceItWasOpened() {
        two.env.pairedManager()
        chat(ALICE_CHAT, "Alice Martin", "+15551234567")
        call(guid(1), ALICE, "missed", System.currentTimeMillis() - 60_000)
        ActivityScenario.launch<ConversationsActivity>(RelayIntents.conversations(two.app, ChatService.MESSAGES).setFlags(0)).use { scenario ->
            scenario.waitFor("the missed call counted") { it.faceTimeMissed == 1 }
            scenario.onActivity { activity ->
                val menu = activity.findViewById<Toolbar>(R.id.toolbar).menu
                activity.onPrepareOptionsMenu(menu)
                val item = menu.findItem(R.id.action_facetime_calls)
                assertTrue(item.isVisible)
                assertEquals("FaceTime (1 missed)", item.title.toString())
                activity.onOptionsItemSelected(item)
                assertEquals(CallsActivity::class.java.name, shadowOf(two.app).nextStartedActivity.component?.className)
            }
        }
        // Opened: counted again from then on.
        CallsActivity.markOpened(two.app)
        assertEquals(0, CallsActivity.missedSinceOpened(two.app))
        // FaceTime off: no entry.
        DirectPrefs.setFaceTimeOn(two.app, false)
        ActivityScenario.launch<ConversationsActivity>(RelayIntents.conversations(two.app, ChatService.MESSAGES).setFlags(0)).use { scenario ->
            two.settle()
            scenario.onActivity { activity ->
                val menu = activity.findViewById<Toolbar>(R.id.toolbar).menu
                activity.onPrepareOptionsMenu(menu)
                assertFalse(menu.findItem(R.id.action_facetime_calls).isVisible)
            }
        }
    }

    @Test
    fun theReturnToCallBarShowsWhileACallHoldsTheLineInTheChatsAndAChat() {
        two.env.pairedManager()
        chat(ALICE_CHAT, "Alice Martin", "+15551234567")
        // Nothing is asked of the ":imessage" process before it ever told of FaceTime.
        ActivityScenario.launch<ConversationsActivity>(RelayIntents.conversations(two.app, ChatService.MESSAGES).setFlags(0)).use { scenario ->
            two.settle()
            scenario.onActivity { activity -> assertFalse(activity.findViewById<View>(R.id.returnToCall).isShown) }
        }
        assertTrue(asked.none { it.first == DirectWire.FT_STATE })
        // A call holds the line: told, and the bar shows (with its time once it is on).
        val since = System.currentTimeMillis() - 65_000
        link.apply(DirectArchive.Update.FaceTime(FaceTimeState(live = true, guid = CALL, title = "Alice Martin", video = true, sinceMs = since)))
        ftState = FaceTimeState(live = true, guid = CALL, title = "Alice Martin", video = true, sinceMs = since)
        ActivityScenario.launch<ConversationsActivity>(RelayIntents.conversations(two.app, ChatService.MESSAGES).setFlags(0)).use { scenario ->
            scenario.waitFor("the bar") { it.findViewById<View>(R.id.returnToCall).isShown }
            scenario.onActivity { activity ->
                val bar = activity.findViewById<TextView>(R.id.returnToCall)
                assertTrue(bar.text.toString(), Regex("Touch to return to call · 01:0\\d").matches(bar.text.toString()))
                bar.performClick()
                val open = shadowOf(two.app).nextStartedActivity
                assertEquals(FaceTimeCallActivity::class.java.name, open.component?.className)
                assertEquals(two.app.packageName, open.component?.packageName)
            }
        }
        two.waitUntil("asked once FaceTime was told of") { asked.any { it.first == DirectWire.FT_STATE } }
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(two.app, ALICE_CHAT, service = ChatService.IMESSAGE).setFlags(0)).use { scenario ->
            scenario.waitFor("the bar in a chat") { it.findViewById<View>(R.id.returnToCall).isShown }
            // It ends: told, and the bar goes.
            link.apply(DirectArchive.Update.FaceTime(FaceTimeState.NONE))
            scenario.waitFor("no bar") { !it.findViewById<View>(R.id.returnToCall).isShown }
        }
        // Not on yet: no time.
        assertEquals("Touch to return to call", ReturnToCallBar.text(two.app, FaceTimeState(live = true, guid = CALL, title = "A"), System.currentTimeMillis()))
        assertEquals("Touch to return to call · 02:05", ReturnToCallBar.text(two.app, FaceTimeState(live = true, guid = CALL, title = "A", sinceMs = 1_000), 126_000))
    }

    private fun guid(n: Int): String = "0A1B2C3D-0000-4000-8000-%012d".format(n)

    private companion object {
        const val IM_DB = "calls-screen-test.db"
        const val ME = "tel:+15550000001"
        const val ALICE = "tel:+15551234567"
        const val BOB = "tel:+15557654321"
        const val CAROL = "tel:+15553334444"
        const val ALICE_CHAT = "imsg:tel:+15551234567"
        const val BOB_CHAT = "imsg:tel:+15557654321"
        const val CAROL_CHAT = "imsg:tel:+15553334444"
        const val GROUP = "imsg:group:11111111-2222-3333-4444-555555555555"
        const val CALL = "BBBBBBBB-0000-4000-8000-000000000002"
    }
}
