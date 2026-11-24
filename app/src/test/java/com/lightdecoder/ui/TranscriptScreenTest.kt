package com.lightdecoder.ui

import android.app.Activity
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.IMessagePrefs
import com.lightdecoder.imessage.direct.DirectLibrary
import com.lightdecoder.imessage.direct.DirectLink
import com.lightdecoder.imessage.direct.DirectPrefs
import com.lightdecoder.imessage.direct.DirectWire
import com.lightdecoder.imessage.direct.IMessageExtra
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.Folder
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.PartDto
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.chat.ConversationsActivity
import com.lightdecoder.ui.chat.MessagesAdapter
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.ui.chat.ThreadItem
import com.lightdecoder.ui.chat.TimeFormat
import java.io.File
import java.time.Duration
import java.util.Collections
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

/**
 * A chat's transcript and the conversation list, on screen, as on an iPhone: a drag to the left shows
 * every message's time (a swipe to the right still replies; elsewhere both ways reply); "Edited" shows
 * the earlier versions; an iMessage that was not delivered says so, with what can be done; "Report
 * Junk" for a stranger; who is typing in a group; Pin and Mark as Unread in the list; a Messages
 * conversation's iMessage chats going with it. [transcriptScreenshotsForReview] draws them to PNG
 * files, only when asked (-Dlightdecoder.uiShots=<folder>).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h800dp-xhdpi")
class TranscriptScreenTest {

    private lateinit var two: TwoServicesEnv
    private lateinit var imDb: ArchiveDb
    private lateinit var imRepo: ArchiveRepository
    private lateinit var iMessage: IMessageManager

    /** What went to the iMessage library, by kind. */
    private val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())

    @Before
    fun setUp() {
        two = TwoServicesEnv()
        two.app.deleteDatabase(IM_DB)
        imDb = ArchiveDb(two.app, IM_DB)
        imRepo = ArchiveRepository.forTesting(imDb, ChatService.IMESSAGE)
        ArchiveRepository.installForTesting(imRepo)
        iMessage = IMessageManager.installForTesting(two.app, imRepo)
        IMessagePrefs.setEnabled(two.app, true)
        DirectPrefs.setPhoneStyleOn(two.app, true)
        DirectPrefs.setRegistered(two.app, true)
        DirectLibrary.REQUIRED.forEach { File(DirectLibrary.dir(two.app), it).apply { parentFile?.mkdirs(); writeText("") } }
        var n = 0
        DirectLink.installForTesting(two.app) { what, request, _ ->
            asked += what to request
            if (what == DirectWire.SEND) DirectWire.sent("OUT${++n}", System.currentTimeMillis()) else DirectWire.ok()
        }
    }

    @After
    fun tearDown() {
        MessageNotifier.cancelAll(two.app, ChatService.IMESSAGE)
        IMessagePrefs.setEnabled(two.app, false)
        DirectPrefs.setPhoneStyleOn(two.app, true)
        DirectPrefs.setRegistered(two.app, false)
        DirectLink.resetForTesting()
        IMessageManager.resetForTesting()
        com.lightdecoder.archive.MediaCache.mediaDir(two.app, ChatService.IMESSAGE).deleteRecursively()
        two.tearDown()
        imDb.close()
    }

    // --- set-up ------------------------------------------------------------------------------

    /** Alice's iMessage chat; [name] her name as stored (her number: someone not in the user's contacts). */
    private fun aliceChat(name: String = "Alice Martin") {
        imRepo.upsertConversation(
            ConversationDto(
                id = ALICE, name = name, type = IMessageManager.TRANSPORT, defaultOutgoingId = "tel:+15550000001",
                participants = listOf(
                    ParticipantDto(id = "me", isMe = true),
                    ParticipantDto(id = "+15551234567", name = name, number = "+15551234567", formattedNumber = "+15551234567"),
                ),
            ),
        )
    }

    private fun groupChat() {
        imRepo.upsertConversation(
            ConversationDto(
                id = GROUP, name = "Saturday hike", isGroup = true, type = IMessageManager.TRANSPORT, defaultOutgoingId = "tel:+15550000001",
                participants = listOf(
                    ParticipantDto(id = "me", isMe = true),
                    ParticipantDto(id = "+15551234567", name = "Alice Martin", number = "+15551234567", formattedNumber = "+15551234567"),
                    ParticipantDto(id = "+15557654321", name = "Ben Ortiz", number = "+15557654321", formattedNumber = "+15557654321"),
                ),
            ),
        )
    }

    private fun message(
        guid: String,
        text: String,
        agoMs: Long,
        mine: Boolean = false,
        chat: String = ALICE,
        sender: Pair<String, String> = "+15551234567" to "Alice Martin",
        extra: IMessageExtra = IMessageExtra(),
        editedAgoMs: Long = 0,
        status: String = if (mine) "OUTGOING_COMPLETE" else "INCOMING_COMPLETE",
    ) {
        val now = System.currentTimeMillis()
        imRepo.upsertMessage(
            MessageDto(
                id = "imsg:$guid", conversationId = chat, fromMe = mine,
                senderId = if (mine) "" else sender.first, senderName = if (mine) "" else sender.second,
                timestampMs = now - agoMs, direction = if (mine) "out" else "in", status = status,
                transport = IMessageManager.TRANSPORT, text = text, parts = listOf(PartDto("0", "text", text)), isOld = true,
                editedAtMs = if (editedAgoMs > 0) now - editedAgoMs else 0, extra = extra.toJson(),
            ),
        )
    }

    /** One of the user's iMessages whose send failed: its local copy. */
    private fun failedCopy(tmpId: String, text: String, chat: String = ALICE, agoMs: Long = 30_000) {
        imRepo.insertLocalOutgoing(tmpId, chat, text, null, null, null, null, System.currentTimeMillis() - agoMs)
        imRepo.setLocalState(tmpId, LocalState.FAILED, "Apple refused the message")
    }

    private fun launch(chat: String = ALICE, service: ChatService = ChatService.IMESSAGE): ActivityScenario<ThreadActivity> =
        ActivityScenario.launch(RelayIntents.thread(two.app, chat, service = service).setFlags(0))

    private fun <A : Activity> ActivityScenario<A>.waitFor(what: String, condition: (A) -> Boolean) =
        two.waitUntil(what) {
            var ok = false
            onActivity { ok = condition(it) }
            ok
        }

    private fun list(activity: Activity) = activity.findViewById<RecyclerView>(R.id.messages)

    private fun rowOf(activity: Activity, text: String): View? {
        val list = list(activity)
        return (0 until list.childCount).map { list.getChildAt(it) }
            .firstOrNull { it.findViewById<TextView>(R.id.body)?.text?.toString() == text }
    }

    private fun holders(activity: Activity): List<RecyclerView.ViewHolder> {
        val list = list(activity)
        return (0 until list.childCount).map { list.getChildViewHolder(list.getChildAt(it)) }
    }

    private fun shownRow(activity: ThreadActivity, text: String): MessageRow =
        (list(activity).adapter as MessagesAdapter).currentList.filterIsInstance<ThreadItem.Message>().map { it.row }.single { it.text == text }

    private fun asked(what: Int) = synchronized(asked) { asked.filter { it.first == what }.map { it.second } }

    /** A drag over the list from ([x], [y]) by ([dx], [dy]) in [steps] moves, a frame drawn after each, [up] at its end. */
    private fun drag(activity: Activity, x: Float, y: Float, dx: Float, dy: Float, steps: Int = 12, up: Boolean = true) {
        val list = list(activity)
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, ax: Float, ay: Float, t: Long) {
            val e = MotionEvent.obtain(start, start + t, action, ax, ay, 0)
            list.dispatchTouchEvent(e)
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, x, y, 0)
        val frame = Canvas(Bitmap.createBitmap(list.width, list.height, Bitmap.Config.ARGB_8888))
        for (i in 1..steps) {
            send(MotionEvent.ACTION_MOVE, x + dx * i / steps, y + dy * i / steps, 16L * i)
            list.draw(frame)
        }
        if (up) send(MotionEvent.ACTION_UP, x + dx, y + dy, 16L * steps + 16)
    }

    private fun settle(ms: Long = 600) {
        repeat((ms / 50).toInt()) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)) }
    }

    private fun menu(activity: Activity): android.view.Menu {
        val menu = activity.findViewById<Toolbar>(R.id.toolbar).menu
        activity.onPrepareOptionsMenu(menu)
        return menu
    }

    private fun dp(activity: Activity, value: Float) = value * activity.resources.displayMetrics.density

    /** Whether the screen is gone, or going. */
    private fun ActivityScenario<out Activity>.left(): Boolean {
        if (state == androidx.lifecycle.Lifecycle.State.DESTROYED) return true
        var finishing = false
        onActivity { finishing = it.isFinishing }
        return finishing
    }

    /**
     * The Messages link as a stand-in over the test's Messages archive ([com.lightdecoder.ui.chat.FakeChatBackend]):
     * it remembers what it was asked (a mute, an archive, a deletion, a text sent) instead of asking a phone.
     */
    private fun fakeMessages(): com.lightdecoder.ui.chat.FakeChatBackend {
        val messages = com.lightdecoder.ui.chat.FakeChatBackend(ChatService.MESSAGES, two.env.repo)
        com.lightdecoder.ui.chat.ScreenBackends.forTesting = { service ->
            when (service) {
                ChatService.MESSAGES -> messages
                ChatService.WHATSAPP -> two.wa
                else -> null
            }
        }
        return messages
    }

    // --- the times, on a drag to the left ------------------------------------------------------

    private fun transcript() {
        aliceChat()
        message("H1", "Are we still on for tonight?", 40 * 60_000)
        message("H2", "Yes! 7 at the usual place", 39 * 60_000, mine = true)
        message("H3", "Perfect, see you there", 10 * 60_000)
    }

    @Test
    fun aDragToTheLeftShowsEveryMessagesTimeAndTheyComeBackWhenLetGo() {
        transcript()
        launch().use { scenario ->
            scenario.waitFor("the messages") { rowOf(it, "Perfect, see you there") != null && rowOf(it, "Are we still on for tonight?") != null }
            scenario.onActivity { activity ->
                val reveal = activity.timeReveal
                val row = rowOf(activity, "Perfect, see you there")!!
                val y = row.top + row.height / 2f
                // Further than the rows go: they stop at the most.
                drag(activity, list(activity).width * 0.75f, y, -dp(activity, 150f), dp(activity, 6f), up = false)
                assertEquals(reveal.maxShift, reveal.offset, 0.5f)
                val messages = holders(activity).filterIsInstance<MessagesAdapter.MessageHolder>()
                assertEquals(3, messages.size)
                val mine = messages.filter { it.outgoing }
                val hers = messages.filter { !it.outgoing }
                assertEquals(1, mine.size)
                assertEquals(2, hers.size)
                // As on an iPhone: the user's own bubble moves over; hers, and the times between the messages, stay where they are.
                for (h in mine) assertEquals(-reveal.maxShift, h.itemView.translationX, 0.5f)
                for (h in hers) assertEquals(0f, h.itemView.translationX, 0.01f)
                holders(activity).filter { it !is MessagesAdapter.MessageHolder }.forEach { assertEquals(0f, it.itemView.translationX, 0.01f) }
                // Every row's time, hers too, in the column at the right edge, as on an iPhone.
                val labels = reveal.labels()
                assertEquals(messages.map { it.shownTime }.toSet(), labels.map { it.time }.toSet())
                assertEquals(TimeFormat.clock(activity, shownRow(activity, "Perfect, see you there").timestampMs), messages.single { it.itemView === row }.shownTime)
                val width = list(activity).width
                assertTrue(labels.all { it.x > width - reveal.maxShift && it.right <= width && it.alpha == 1f })
                // Never over her bubbles.
                for (label in labels.filter { l -> hers.any { it.itemView === l.row } }) {
                    assertTrue("clear of her bubble", label.x >= bubbleEnd(activity, label.row))
                }
                // Let go: back in place, nothing replied to.
                val end = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 0f, y, 0)
                list(activity).dispatchTouchEvent(end)
                end.recycle()
            }
            settle()
            scenario.onActivity { activity ->
                assertEquals(0f, activity.timeReveal.offset, 0.01f)
                assertTrue(activity.timeReveal.labels().isEmpty())
                holders(activity).forEach { assertEquals(0f, it.itemView.translationX, 0.01f) }
                assertEquals(View.GONE, activity.findViewById<View>(R.id.replyBar).visibility)
            }
        }
    }

    @Test
    fun aRowThatComesOnScreenMidDragMovesWithTheOthersAndAllComeBack() {
        transcript()
        launch().use { scenario ->
            scenario.waitFor("the messages") { rowOf(it, "Perfect, see you there") != null }
            scenario.onActivity { activity ->
                val row = rowOf(activity, "Perfect, see you there")!!
                drag(activity, list(activity).width * 0.75f, row.top + row.height / 2f, -dp(activity, 150f), 0f, up = false)
                assertEquals(activity.timeReveal.maxShift, activity.timeReveal.offset, 0.5f)
            }
            // Messages arrive while the finger is still down (one of the user's, sent from another device,
            // and one of hers): their rows are laid out as the others are, the user's moved, hers in place.
            message("H4", "One more thing", 90_000, mine = true)
            message("H5", "Sure, what is it?", 60_000)
            scenario.waitFor("they show") { rowOf(it, "One more thing") != null && rowOf(it, "Sure, what is it?") != null }
            scenario.onActivity { activity ->
                val reveal = activity.timeReveal
                assertEquals(reveal.maxShift, reveal.offset, 0.5f)
                assertEquals(-reveal.maxShift, rowOf(activity, "One more thing")!!.translationX, 0.5f)
                assertEquals(0f, rowOf(activity, "Sure, what is it?")!!.translationX, 0.01f)
                val times = reveal.labels().map { it.time }
                assertTrue(times.contains(TimeFormat.clock(activity, shownRow(activity, "One more thing").timestampMs)))
                assertTrue(times.contains(TimeFormat.clock(activity, shownRow(activity, "Sure, what is it?").timestampMs)))
                val end = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 0f, 0f, 0)
                list(activity).dispatchTouchEvent(end)
                end.recycle()
            }
            settle()
            scenario.onActivity { activity ->
                assertEquals(0f, activity.timeReveal.offset, 0.01f)
                holders(activity).forEach { assertEquals(0f, it.itemView.translationX, 0.01f) }
            }
        }
    }

    /** Where [row]'s bubble ends, in the list. */
    private fun bubbleEnd(activity: Activity, row: View): Float {
        val bubble = row.findViewById<View>(R.id.bubble)
        val at = IntArray(2).also { bubble.getLocationInWindow(it) }
        val listAt = IntArray(2).also { list(activity).getLocationInWindow(it) }
        return (at[0] - listAt[0] + bubble.width).toFloat()
    }

    @Test
    fun aTimeNeverCoversSomeoneElsesWideBubble() {
        groupChat()
        val long = "Leaving in five minutes from the north car park, the one by the visitor centre, so text me if you are running late or need a lift up"
        message("W1", long, 20 * 60_000, chat = GROUP)
        message("W2", "Ok!", 19 * 60_000, chat = GROUP, sender = "+15557654321" to "Ben Ortiz")
        message("W3", "On my way", 18 * 60_000, chat = GROUP, mine = true)
        launch(GROUP).use { scenario ->
            scenario.waitFor("the group") { rowOf(it, long) != null && rowOf(it, "Ok!") != null && rowOf(it, "On my way") != null }
            scenario.onActivity { activity ->
                val reveal = activity.timeReveal
                val row = rowOf(activity, "Ok!")!!
                drag(activity, list(activity).width * 0.75f, row.top + row.height / 2f, -dp(activity, 150f), 0f, up = false)
                assertEquals(reveal.maxShift, reveal.offset, 0.5f)
                val wide = rowOf(activity, long)!!
                // Her wide bubble reaches into the column: it stays put, and nothing is drawn over it.
                assertEquals(0f, wide.translationX, 0.01f)
                assertTrue(bubbleEnd(activity, wide) > list(activity).width - reveal.maxShift)
                val labels = reveal.labels()
                labels.filter { it.row === wide }.forEach { assertTrue("clear of her bubble", it.x >= bubbleEnd(activity, wide)) }
                // The short ones have their times, and so does the user's (moved over).
                assertTrue(labels.any { it.row === row && it.x >= bubbleEnd(activity, row) })
                assertTrue(labels.any { it.row === rowOf(activity, "On my way") })
                assertEquals(-reveal.maxShift, rowOf(activity, "On my way")!!.translationX, 0.5f)
                assertTrue(labels.all { it.right <= list(activity).width })
                val end = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 0f, 0f, 0)
                list(activity).dispatchTouchEvent(end)
                end.recycle()
            }
        }
    }

    @Test
    fun aSwipeToTheRightStillRepliesAndAScrollShowsNoTimes() {
        transcript()
        launch().use { scenario ->
            scenario.waitFor("the messages") { rowOf(it, "Perfect, see you there") != null }
            scenario.onActivity { activity ->
                val row = rowOf(activity, "Perfect, see you there")!!
                val y = row.top + row.height / 2f
                // Up and down, a little sideways: the list's, no times.
                drag(activity, list(activity).width * 0.5f, y, -dp(activity, 20f), -dp(activity, 160f))
                assertEquals(0f, activity.timeReveal.offset, 0.01f)
                assertTrue(activity.timeReveal.labels().isEmpty())
            }
            settle()
            scenario.onActivity { activity ->
                val row = rowOf(activity, "Perfect, see you there")!!
                // To the right: a reply, as on an iPhone.
                drag(activity, list(activity).width / 3f, row.top + row.height / 2f, dp(activity, 300f), 0f, steps = 20)
                assertEquals(0f, activity.timeReveal.offset, 0.01f)
            }
            settle(1_000)
            scenario.waitFor("the reply bar") { it.findViewById<View>(R.id.replyBar).isShown }
            scenario.onActivity { activity -> assertEquals(0f, rowOf(activity, "Perfect, see you there")!!.translationX, 0.5f) }
        }
    }

    @Test
    fun elsewhereASwipeEitherWayRepliesAndNoTimesShow() {
        DirectPrefs.setPhoneStyleOn(two.app, false)
        transcript()
        launch().use { scenario ->
            scenario.waitFor("the messages") { rowOf(it, "Perfect, see you there") != null }
            scenario.onActivity { activity ->
                assertFalse(activity.phoneStyle)
                val row = rowOf(activity, "Perfect, see you there")!!
                drag(activity, list(activity).width * 0.7f, row.top + row.height / 2f, -dp(activity, 300f), 0f, steps = 20)
                assertEquals(0f, activity.timeReveal.offset, 0.01f)
            }
            settle(1_000)
            scenario.waitFor("the reply bar") { it.findViewById<View>(R.id.replyBar).isShown }
        }
    }

    @Test
    fun aFingerHeldStillIsStillALongPress() {
        transcript()
        launch().use { scenario ->
            scenario.waitFor("the messages") { rowOf(it, "Perfect, see you there") != null }
            scenario.onActivity { activity ->
                val row = rowOf(activity, "Perfect, see you there")!!
                val bubble = row.findViewById<View>(R.id.bubble)
                val at = IntArray(2).also { bubble.getLocationInWindow(it) }
                val listAt = IntArray(2).also { list(activity).getLocationInWindow(it) }
                val x = (at[0] - listAt[0] + bubble.width / 2).toFloat()
                val y = (at[1] - listAt[1] + bubble.height / 2).toFloat()
                val start = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, x, y, 0)
                list(activity).dispatchTouchEvent(down)
                down.recycle()
            }
            settle(1_000)
            scenario.onActivity { activity ->
                assertTrue("the message's actions", ShadowDialog.getLatestDialog() is BottomSheetDialog && ShadowDialog.getLatestDialog().isShowing)
                assertEquals(0f, activity.timeReveal.offset, 0.01f)
                val end = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
                list(activity).dispatchTouchEvent(end)
                end.recycle()
            }
        }
    }

    // --- edit history ----------------------------------------------------------------------------

    @Test
    fun editedShowsTheEarlierVersionsAboveTheMessageAndATapHidesThem() {
        aliceChat()
        val now = System.currentTimeMillis()
        val history = listOf(IMessageExtra.Version("See you at 6", now - 10 * 60_000), IMessageExtra.Version("See you at 7", now - 8 * 60_000))
        message("E1", "Make it 8", 10 * 60_000, editedAgoMs = 5 * 60_000, extra = IMessageExtra(edits = 2, history = history))
        launch().use { scenario ->
            scenario.waitFor("the message") { rowOf(it, "Make it 8") != null }
            scenario.onActivity { activity ->
                val row = rowOf(activity, "Make it 8")!!
                val edited = row.findViewById<TextView>(R.id.edited)
                assertEquals(View.VISIBLE, edited.visibility)
                assertEquals(View.GONE, row.findViewById<View>(R.id.editHistory).visibility)
                // TalkBack: what a double-tap on it does, and the same from the bubble.
                fun clickLabel(v: View) = v.createAccessibilityNodeInfo().actionList.first { it.id == AccessibilityNodeInfo.ACTION_CLICK }.label?.toString()
                assertEquals(activity.getString(R.string.imessage_edit_history_show), clickLabel(edited))
                assertTrue(row.findViewById<View>(R.id.bubble).createAccessibilityNodeInfo().actionList.any { it.label?.toString() == activity.getString(R.string.imessage_edit_history_show) })
                assertTrue(edited.performClick())
            }
            scenario.waitFor("the earlier versions") { rowOf(it, "Make it 8")?.findViewById<View>(R.id.editHistory)?.visibility == View.VISIBLE }
            scenario.onActivity { activity ->
                val row = rowOf(activity, "Make it 8")!!
                val box = row.findViewById<LinearLayout>(R.id.editHistory)
                assertEquals(listOf("See you at 6", "See you at 7"), (0 until box.childCount).map { (box.getChildAt(it) as TextView).text.toString() })
                // Above the bubble, as on an iPhone.
                assertTrue(box.bottom <= row.findViewById<View>(R.id.bubble).top + 1)
                assertEquals(
                    activity.getString(R.string.imessage_edit_history_version, TimeFormat.clock(activity, history[0].atMs), "See you at 6"),
                    box.getChildAt(0).contentDescription.toString(),
                )
                val edited = row.findViewById<TextView>(R.id.edited)
                assertEquals(activity.getString(R.string.imessage_edit_history_hide), edited.createAccessibilityNodeInfo().actionList.first { it.id == AccessibilityNodeInfo.ACTION_CLICK }.label?.toString())
                // A tap on an earlier version hides them again.
                assertTrue(box.getChildAt(1).performClick())
            }
            scenario.waitFor("hidden again") { rowOf(it, "Make it 8")?.findViewById<View>(R.id.editHistory)?.visibility == View.GONE }
        }
    }

    // --- Not Delivered ----------------------------------------------------------------------------

    @Test
    fun anIMessageThatWasNotDeliveredSaysSoAndOffersTryAgainOrDelete() {
        transcript()
        failedCopy("t-f", "Running late")
        failedCopy("t-g", "Also this one", agoMs = 20_000)
        launch().use { scenario ->
            scenario.waitFor("the failed one") { rowOf(it, "Running late") != null && rowOf(it, "Also this one") != null }
            scenario.onActivity { activity ->
                val row = rowOf(activity, "Running late")!!
                assertEquals(activity.getString(R.string.imessage_not_delivered), row.findViewById<TextView>(R.id.failedText).text.toString())
                val mark = row.findViewById<View>(R.id.failedMark)
                assertEquals(View.VISIBLE, mark.visibility)
                // Beside the bubble, at its middle.
                val bubble = row.findViewById<View>(R.id.bubble)
                val column = row.findViewById<View>(R.id.column)
                assertTrue(mark.left >= column.right)
                assertEquals((column.top + bubble.top + bubble.height / 2).toFloat(), mark.top + mark.height / 2f, 2f)
                assertTrue(bubble.contentDescription.toString().contains(activity.getString(R.string.imessage_not_delivered)))
                assertTrue(mark.performClick())
            }
            ShadowLooper.idleMainLooper()
            scenario.onActivity { activity ->
                val sheet = ShadowDialog.getLatestDialog() as BottomSheetDialog
                assertTrue(sheet.isShowing)
                // Not a Messages conversation: no text to send it as.
                assertEquals(View.GONE, sheet.findViewById<View>(R.id.actionSendAsText)!!.visibility)
                assertEquals(View.GONE, sheet.findViewById<View>(R.id.notDeliveredNote)!!.visibility)
                assertTrue(sheet.findViewById<View>(R.id.actionTryAgain)!!.performClick())
            }
            two.waitUntil("sent again") { asked(DirectWire.SEND).isNotEmpty() }
            assertEquals("Running late", DirectWire.SendRequest.fromJson(asked(DirectWire.SEND).single()).text)
            // The other one: deleted.
            scenario.onActivity { activity -> activity.onFailedTap(shownRow(activity, "Also this one")) }
            ShadowLooper.idleMainLooper()
            scenario.onActivity { (ShadowDialog.getLatestDialog() as BottomSheetDialog).findViewById<View>(R.id.actionDeleteFailed)!!.performClick() }
            two.waitUntil("its copy gone") { imRepo.message(MessageRow.tmpMessageId("t-g")) == null }
            assertEquals(1, asked(DirectWire.SEND).size)
        }
    }

    /** Alice's Messages conversation, with her iMessage chat inside it (Blue Bubbles). */
    private fun mergedAlice() {
        two.seedConversation(ChatService.MESSAGES, MESSAGES_ALICE, "Alice Martin", lastTs = 1_000, number = "+1 555-123-4567")
        aliceChat()
    }

    @Test
    fun inAMessagesConversationItCanGoAsATextMessageInstead() {
        val messages = fakeMessages()
        mergedAlice()
        message("H1", "Are we still on?", 40 * 60_000)
        failedCopy("t-f", "Running late")
        launch(MESSAGES_ALICE, ChatService.MESSAGES).use { scenario ->
            scenario.waitFor("the failed one") { rowOf(it, "Running late") != null }
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.imessage_not_delivered), rowOf(activity, "Running late")!!.findViewById<TextView>(R.id.failedText).text.toString())
                activity.onFailedTap(shownRow(activity, "Running late"))
            }
            ShadowLooper.idleMainLooper()
            scenario.onActivity {
                val sheet = ShadowDialog.getLatestDialog() as BottomSheetDialog
                val asText = sheet.findViewById<TextView>(R.id.actionSendAsText)!!
                assertEquals(View.VISIBLE, asText.visibility)
                assertEquals(two.app.getString(R.string.imessage_not_delivered_as_text), asText.text.toString())
                assertTrue(asText.performClick())
            }
            two.waitUntil("sent as a text") { messages.texts.any { it.second == "Running late" } }
            assertEquals(MESSAGES_ALICE, messages.texts.single { it.second == "Running late" }.first)
            two.waitUntil("the iMessage copy gone") { imRepo.message(MessageRow.tmpMessageId("t-f")) == null }
            assertTrue("never as an iMessage", asked(DirectWire.SEND).isEmpty())
        }
    }

    /** A failed batch's local copy: [files] picked together, the first in the row, the others in its extra (as DirectLink.sendMediaBatch keeps them). */
    private fun failedBatch(tmpId: String, caption: String, files: Int, missing: Int? = null) {
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        val paths = (0 until files).map { i -> imRepo.outboxFileFor(if (i == 0) tmpId else "$tmpId-$i").apply { parentFile?.mkdirs(); writeBytes(png + i.toByte()) } }
        val extra = IMessageExtra(pending = paths.drop(1).mapIndexed { i, f -> IMessageExtra.PendingFile(f.path, "image/png", "p${i + 1}.png") })
        imRepo.insertLocalOutgoing(tmpId, ALICE, caption, paths[0].path, "image/png", "p0.png", null, System.currentTimeMillis() - 30_000, extra.toJson())
        imRepo.setLocalState(tmpId, LocalState.FAILED, "Apple refused the message")
        missing?.let { paths[it].delete() }
    }

    @Test
    fun aFailedBatchGoesAsATextMessageWholeOrNotAtAll() {
        val messages = fakeMessages()
        mergedAlice()
        failedBatch("t-b", "Trip", files = 3)
        failedBatch("t-c", "Half here", files = 3, missing = 2)
        launch(MESSAGES_ALICE, ChatService.MESSAGES).use { scenario ->
            scenario.waitFor("the batches") { rowOf(it, "Trip") != null && rowOf(it, "Half here") != null }
            // One of its files gone: nothing goes, and it stays.
            scenario.onActivity { activity -> activity.sendAsText(shownRow(activity, "Half here")) }
            two.waitUntil("refused") { org.robolectric.shadows.ShadowToast.getTextOfLatestToast() == two.app.getString(R.string.imessage_not_delivered_files_missing) }
            assertTrue(messages.calls.none { it.startsWith("sendMedia") })
            assertNotNull(imRepo.message(MessageRow.tmpMessageId("t-c")))
            assertTrue(imRepo.outboxFileFor("t-c-1").isFile)
            // All here: every file goes, then the iMessage copy (and its files).
            scenario.onActivity { activity -> activity.sendAsText(shownRow(activity, "Trip")) }
            two.waitUntil("all three sent") { messages.calls.count { it == "sendMedia:$MESSAGES_ALICE" } == 3 }
            two.waitUntil("the iMessage copy gone") { imRepo.message(MessageRow.tmpMessageId("t-b")) == null }
            assertFalse(imRepo.outboxFileFor("t-b-1").exists())
            assertFalse(imRepo.outboxFileFor("t-b-2").exists())
            assertTrue("never as an iMessage", asked(DirectWire.SEND).isEmpty())
        }
    }

    // --- Report Junk ------------------------------------------------------------------------------

    @Test
    fun aStrangersChatOffersReportJunkWhichGoesToAppleThenDeletesIt() {
        aliceChat(name = "+15551234567")
        message("J1", "You have won a prize", 30 * 60_000)
        message("J2", "Click here to claim it", 29 * 60_000)
        launch().use { scenario ->
            scenario.waitFor("the banner") { it.findViewById<View>(R.id.junkBanner).visibility == View.VISIBLE }
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.imessage_junk_banner), activity.findViewById<TextView>(R.id.junkText).text.toString())
                assertTrue(menu(activity).findItem(R.id.action_report_junk).isVisible)
                assertTrue(activity.findViewById<View>(R.id.junkReport).performClick())
            }
            ShadowLooper.idleMainLooper()
            val question = ShadowDialog.getLatestDialog() as AlertDialog
            assertEquals(two.app.getString(R.string.imessage_junk_confirm_body), question.findViewById<TextView>(android.R.id.message)!!.text.toString())
            assertTrue("nothing reported before the answer", asked(DirectWire.REPORT_JUNK).isEmpty())
            question.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            two.waitUntil("reported") { asked(DirectWire.REPORT_JUNK).isNotEmpty() }
            val report = DirectWire.ReportJunkRequest.fromJson(asked(DirectWire.REPORT_JUNK).single())
            assertEquals(listOf("J1", "J2"), report.messages.map { it.guid })
            assertEquals("tel:+15551234567", report.messages.first().sender)
            two.waitUntil("deleted here") { imRepo.conversation(ALICE) == null }
            two.waitUntil("the chat left") { scenario.left() }
        }
    }

    @Test
    fun noReportJunkForAContactOrOnceTheUserWrote() {
        aliceChat()
        message("J1", "Hi it's Alice", 30 * 60_000)
        launch().use { scenario ->
            scenario.waitFor("her message") { rowOf(it, "Hi it's Alice") != null }
            two.settle()
            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.findViewById<View>(R.id.junkBanner).visibility)
                assertFalse(menu(activity).findItem(R.id.action_report_junk).isVisible)
            }
        }
        imRepo.deleteConversationLocal(ALICE)
        aliceChat(name = "+15551234567")
        message("J1", "Who is this?", 30 * 60_000)
        message("S1", "Wrong number, sorry", 20 * 60_000, mine = true)
        launch().use { scenario ->
            scenario.waitFor("the messages") { rowOf(it, "Wrong number, sorry") != null }
            two.settle()
            scenario.onActivity { assertEquals(View.GONE, it.findViewById<View>(R.id.junkBanner).visibility) }
        }
    }

    // --- who is typing ------------------------------------------------------------------------------

    @Test
    fun inAGroupWhoIsTypingShowsBesideTheDotsAndTalkBackSaysHowManyMore() {
        groupChat()
        message("G1", "Who's driving?", 10 * 60_000, chat = GROUP)
        launch(GROUP).use { scenario ->
            scenario.waitFor("the group") { rowOf(it, "Who's driving?") != null }
            iMessage.noteTyping(GROUP, "+15551234567", "Alice Martin", typing = true)
            scenario.waitFor("her typing") { it.findViewById<View>(R.id.typingDots).visibility == View.VISIBLE }
            scenario.onActivity { activity ->
                val avatar = activity.findViewById<TextView>(R.id.typingAvatar)
                assertEquals(View.VISIBLE, avatar.visibility)
                assertEquals("AM", avatar.text.toString())
                assertEquals(activity.getString(R.string.chat_typing_one, "Alice Martin"), activity.findViewById<View>(R.id.typingDots).contentDescription.toString())
            }
            iMessage.noteTyping(GROUP, "+15557654321", "Ben Ortiz", typing = true)
            scenario.waitFor("both typing") {
                it.findViewById<View>(R.id.typingDots).contentDescription.toString() ==
                    it.resources.getQuantityString(R.plurals.imessage_typing_first_and_others, 1, "Alice Martin", 1)
            }
            scenario.onActivity { assertEquals("the first one's", "AM", it.findViewById<TextView>(R.id.typingAvatar).text.toString()) }
            iMessage.noteTyping(GROUP, "+15551234567", "Alice Martin", typing = false)
            iMessage.noteTyping(GROUP, "+15557654321", "Ben Ortiz", typing = false)
            scenario.waitFor("nobody typing") { it.findViewById<View>(R.id.typingAvatar).visibility == View.GONE && it.findViewById<View>(R.id.typingDots).visibility == View.GONE }
        }
        // A chat with one person shows no picture beside the dots, as on an iPhone.
        aliceChat()
        message("H1", "Hello", 10 * 60_000)
        launch().use { scenario ->
            scenario.waitFor("her message") { rowOf(it, "Hello") != null }
            iMessage.noteTyping(ALICE, "+15551234567", "Alice Martin", typing = true)
            scenario.waitFor("her typing") { it.findViewById<View>(R.id.typingDots).visibility == View.VISIBLE }
            scenario.onActivity { assertEquals(View.GONE, it.findViewById<View>(R.id.typingAvatar).visibility) }
        }
    }

    // --- the list: Pin, Mark as Unread, and a Messages conversation's iMessage chats going with it -------

    private fun launchList(): ActivityScenario<ConversationsActivity> = ActivityScenario.launch(
        android.content.Intent(two.app, ConversationsActivity::class.java).putExtra(RelayIntents.EXTRA_SERVICE, ChatService.MESSAGES.key),
    )

    private fun titles(activity: Activity): List<String> =
        (activity.findViewById<RecyclerView>(R.id.list).adapter as com.lightdecoder.ui.chat.ConversationsAdapter).currentList.map { it.title }

    /** Holds row [title] down and picks [action] from what it offers; the offers, in order. */
    private fun longPressPick(scenario: ActivityScenario<ConversationsActivity>, title: String, action: Int?): List<String> {
        var offered = emptyList<String>()
        scenario.onActivity { activity ->
            val list = activity.findViewById<RecyclerView>(R.id.list)
            val adapter = list.adapter as com.lightdecoder.ui.chat.ConversationsAdapter
            val index = adapter.currentList.indexOfFirst { it.title == title }
            list.scrollToPosition(index)
        }
        two.settle()
        scenario.onActivity { activity ->
            val list = activity.findViewById<RecyclerView>(R.id.list)
            val adapter = list.adapter as com.lightdecoder.ui.chat.ConversationsAdapter
            val index = adapter.currentList.indexOfFirst { it.title == title }
            assertTrue(list.findViewHolderForAdapterPosition(index)!!.itemView.performLongClick())
            ShadowLooper.idleMainLooper()
            val dialog = activity.actionsDialog!!
            val items = dialog.listView
            offered = (0 until items.adapter.count).map { items.adapter.getItem(it).toString() }
            if (action == null) {
                dialog.dismiss()
            } else {
                val at = offered.indexOf(activity.getString(action))
                assertTrue("${activity.getString(action)} in $offered", at >= 0)
                items.performItemClick(null, at, at.toLong())
            }
        }
        ShadowLooper.idleMainLooper()
        return offered
    }

    @Test
    fun theListOffersPinAndMarkAsUnreadAndAMessagesConversationsIMessageChatGoesWithIt() {
        val messages = fakeMessages()
        mergedAlice()
        two.seedConversation(ChatService.MESSAGES, "c2", "Bob", lastTs = 9_000_000_000_000, number = "+1 555-987-6543")
        message("H1", "See you at 7", 60_000)
        imRepo.markConversationRead(ALICE)
        two.env.repo.markConversationRead(MESSAGES_ALICE)
        launchList().use { scenario ->
            scenario.waitFor("the inbox") { titles(it).containsAll(listOf("Alice Martin", "Bob")) && titles(it).size == 2 }
            scenario.onActivity { assertEquals("Bob is newer", listOf("Bob", "Alice Martin"), titles(it)) }
            val offered = longPressPick(scenario, "Alice Martin", R.string.chat_action_pin)
            assertEquals(
                listOf(R.string.chat_action_pin, R.string.chat_action_mark_unread, R.string.chat_action_archive, R.string.chat_action_mute, R.string.chat_action_delete)
                    .map { two.app.getString(it) },
                offered,
            )
            two.waitUntil("both pinned") { two.env.repo.conversation(MESSAGES_ALICE)!!.pinned && imRepo.conversation(ALICE)!!.pinned }
            scenario.waitFor("pinned first") { titles(it) == listOf("Alice Martin", "Bob") }
            // Mark as Unread: both, and through the library her other devices are told.
            longPressPick(scenario, "Alice Martin", R.string.chat_action_mark_unread)
            two.waitUntil("both unread") { two.env.repo.conversation(MESSAGES_ALICE)!!.unread && imRepo.conversation(ALICE)!!.unread }
            two.waitUntil("Apple told") { asked(DirectWire.MARK_UNREAD).isNotEmpty() }
            assertEquals("H1", DirectWire.MarkUnreadRequest.fromJson(asked(DirectWire.MARK_UNREAD).single()).guid)
            // Now it offers Unpin and Mark as Read.
            val again = longPressPick(scenario, "Alice Martin", R.string.chat_action_mark_read)
            assertTrue(again.containsAll(listOf(two.app.getString(R.string.chat_action_unpin), two.app.getString(R.string.chat_action_mark_read))))
            two.waitUntil("both read") { !two.env.repo.conversation(MESSAGES_ALICE)!!.unread && !imRepo.conversation(ALICE)!!.unread }
            // Hide Alerts: both.
            longPressPick(scenario, "Alice Martin", R.string.chat_action_mute)
            two.waitUntil("both quiet") { "setMuted:$MESSAGES_ALICE:true" in messages.calls && imRepo.conversation(ALICE)!!.muted }
            // Archive: both.
            longPressPick(scenario, "Alice Martin", R.string.chat_action_archive)
            two.waitUntil("both archived") {
                "setArchived:$MESSAGES_ALICE:true" in messages.calls && imRepo.conversation(ALICE)!!.folderEnum == Folder.ARCHIVE
            }
        }
    }

    @Test
    fun deletingAMessagesConversationFromTheListDeletesItsIMessageChat() {
        val messages = fakeMessages()
        mergedAlice()
        message("H1", "See you at 7", 60_000)
        launchList().use { scenario ->
            scenario.waitFor("the inbox") { titles(it) == listOf("Alice Martin") }
            longPressPick(scenario, "Alice Martin", R.string.chat_action_delete)
            val question = ShadowDialog.getLatestDialog() as AlertDialog
            question.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            // Through the iMessage library her chat goes to Recently Deleted with it.
            two.waitUntil("her iMessage chat gone with it") {
                "deleteConversation:$MESSAGES_ALICE" in messages.calls && imRepo.conversation(ALICE)?.folderEnum == Folder.DELETED
            }
        }
    }

    @Test
    fun recentlyDeletedListsTheImessageChatWithItsDaysLeftAndRecoversIt() {
        val messages = com.lightdecoder.ui.chat.FakeChatBackend(
            ChatService.MESSAGES, two.env.repo, setOf(com.lightdecoder.chat.Capability.DELETED_FOLDER, com.lightdecoder.chat.Capability.SPAM_FOLDER),
        )
        com.lightdecoder.ui.chat.ScreenBackends.forTesting = { service -> if (service == ChatService.MESSAGES) messages else null }
        aliceChat()
        message("H1", "See you at 7", 60_000)
        imRepo.moveToDeleted(ALICE, System.currentTimeMillis() - 24 * 3_600_000L - 60_000)
        launchList().use { scenario ->
            scenario.waitFor("the folders") { it.findViewById<com.google.android.material.tabs.TabLayout>(R.id.folders).tabCount > 1 }
            scenario.onActivity { activity ->
                val tabs = activity.findViewById<com.google.android.material.tabs.TabLayout>(R.id.folders)
                val tab = (0 until tabs.tabCount).map { tabs.getTabAt(it)!! }.first { it.tag == Folder.DELETED }
                assertEquals("Recently Deleted", tab.text.toString())
                tab.select()
            }
            scenario.waitFor("the deleted chat") { titles(it) == listOf("Alice Martin") }
            two.settle()
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.list)
                assertEquals("29 days", list.getChildAt(0).findViewById<TextView>(R.id.time).text.toString())
            }
            assertEquals(listOf("Recover", "Delete"), longPressPick(scenario, "Alice Martin", R.string.imessage_recover))
            two.waitUntil("recovered") { imRepo.conversation(ALICE)?.folderEnum == Folder.INBOX }
        }
    }

    @Test
    fun aMessagesConversationsMenuReachesItsIMessageChatAndMarkAsUnreadLeavesIt() {
        val messages = fakeMessages()
        mergedAlice()
        message("H1", "See you at 7", 60_000)
        launch(MESSAGES_ALICE, ChatService.MESSAGES).use { scenario ->
            scenario.waitFor("her iMessage") { rowOf(it, "See you at 7") != null }
            two.waitUntil("read") { !imRepo.conversation(ALICE)!!.unread }
            scenario.onActivity { activity ->
                assertTrue(menu(activity).findItem(R.id.action_mute).isVisible)
                activity.onOptionsItemSelected(menu(activity).findItem(R.id.action_mute))
            }
            two.waitUntil("both quiet") { "setMuted:$MESSAGES_ALICE:true" in messages.calls && imRepo.conversation(ALICE)!!.muted }
            scenario.onActivity { activity ->
                assertTrue(menu(activity).findItem(R.id.action_mark_unread).isVisible)
                activity.onOptionsItemSelected(menu(activity).findItem(R.id.action_mark_unread))
                assertTrue(activity.isFinishing)
            }
            two.waitUntil("both unread") { two.env.repo.conversation(MESSAGES_ALICE)!!.unread && imRepo.conversation(ALICE)!!.unread }
            two.settle()
            assertTrue("still unread", two.env.repo.conversation(MESSAGES_ALICE)!!.unread && imRepo.conversation(ALICE)!!.unread)
        }
    }

    @Test
    fun anIMessageInsideAMessagesConversationWithItsAlertsHiddenMakesNoSound() {
        two.env.pairedManager()
        IMessagePrefs.setNotifyMessages(two.app, true)
        mergedAlice()
        message("H0", "Earlier", 60 * 60_000)
        two.env.repo.setConversationMuted(MESSAGES_ALICE, true)
        val notifications = shadowOf(two.app.getSystemService(NotificationManager::class.java))
        fun announced() = notifications.activeNotifications.count { it.tag?.startsWith("im:") == true }
        val incoming = MessageDto(
            id = "imsg:N1", conversationId = ALICE, senderId = "+15551234567", senderName = "Alice Martin", timestampMs = System.currentTimeMillis(),
            direction = "in", status = "INCOMING_COMPLETE", transport = IMessageManager.TRANSPORT, text = "Ping",
        )
        assertTrue(MessageNotifier.mutedAsMerged(two.app, ChatService.IMESSAGE, ALICE))
        MessageNotifier.onIncoming(two.app, imRepo, incoming)
        assertEquals(0, announced())
        // Its alerts on again: it is heard.
        two.env.repo.setConversationMuted(MESSAGES_ALICE, false)
        com.lightdecoder.imessage.IMessageLinks.invalidate()
        assertFalse(MessageNotifier.mutedAsMerged(two.app, ChatService.IMESSAGE, ALICE))
        MessageNotifier.onIncoming(two.app, imRepo, incoming.copy(id = "imsg:N2"))
        assertEquals(1, announced())
    }

    // --- screenshots for review ------------------------------------------------------------------

    /**
     * Draws, in light and dark, a chat mid-drag with every time showing and an edited message with its
     * earlier versions (transcript-*.png); a stranger's chat with "Report Junk" (transcript-junk-*.png);
     * and an iMessage that was not delivered with its sheet (transcript-failed-*.png,
     * transcript-sheet-*.png). Only when asked.
     */
    @Test
    @Config(qualifiers = "w390dp-h900dp-xhdpi")
    fun transcriptScreenshotsForReview() {
        val folder = System.getProperty(SHOTS_PROPERTY).orEmpty()
        assumeTrue("screenshots only when asked: -D$SHOTS_PROPERTY=<folder>", folder.isNotEmpty())
        val out = File(folder).apply { mkdirs() }
        val now = System.currentTimeMillis()
        for (night in listOf(false, true)) {
            val mode = if (night) "dark" else "light"
            RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
            imRepo.deleteConversationLocal(ALICE)
            aliceChat()
            message("T1", "Hi! Is the bike still for sale?", 50 * 60_000)
            message("T2", "It is! Want to come and see it?", 48 * 60_000, mine = true)
            message(
                "T3", "Can you do $250?", 20 * 60_000, editedAgoMs = 15 * 60_000,
                extra = IMessageExtra(edits = 2, history = listOf(IMessageExtra.Version("Can you do $200?", now - 20 * 60_000), IMessageExtra.Version("Can you do $220?", now - 18 * 60_000))),
            )
            message("T4", "Deal. Saturday at 10?", 12 * 60_000, mine = true)
            message("T5", "Perfect, see you then", 11 * 60_000)
            launch().use { scenario ->
                scenario.waitFor("the chat") { rowOf(it, "Can you do $250?") != null && rowOf(it, "Perfect, see you then") != null }
                scenario.onActivity { rowOf(it, "Can you do $250?")!!.findViewById<View>(R.id.edited).performClick() }
                scenario.waitFor("its earlier versions") { rowOf(it, "Can you do $250?")?.findViewById<View>(R.id.editHistory)?.visibility == View.VISIBLE }
                scenario.onActivity { activity ->
                    val row = rowOf(activity, "Deal. Saturday at 10?")!!
                    drag(activity, list(activity).width * 0.75f, row.top + row.height / 2f, -dp(activity, 150f), 0f, up = false)
                    shoot(activity, File(out, "transcript-$mode.png"))
                    val end = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 0f, 0f, 0)
                    list(activity).dispatchTouchEvent(end)
                    end.recycle()
                }
            }
            imRepo.deleteConversationLocal(ALICE)
            aliceChat(name = "+15551234567")
            message("J1", "Hi! Is this the right number for the bike?", 50 * 60_000)
            message("J2", "It's still for sale I hope", 49 * 60_000)
            launch().use { scenario ->
                scenario.waitFor("the banner") { rowOf(it, "It's still for sale I hope") != null && it.findViewById<View>(R.id.junkBanner).visibility == View.VISIBLE }
                scenario.onActivity { shoot(it, File(out, "transcript-junk-$mode.png")) }
            }
            imRepo.deleteConversationLocal(ALICE)
            aliceChat()
            message("H1", "Are we still on for tonight?", 40 * 60_000)
            message("H2", "Yes! 7 at the usual place", 39 * 60_000, mine = true)
            failedCopy("t-f$mode", "Running 10 minutes late, sorry")
            launch().use { scenario ->
                scenario.waitFor("the failed one") { rowOf(it, "Running 10 minutes late, sorry") != null }
                scenario.onActivity { shoot(it, File(out, "transcript-failed-$mode.png")) }
                scenario.onActivity { it.onFailedTap(shownRow(it, "Running 10 minutes late, sorry")) }
                two.settle()
                val sheet = ShadowDialog.getLatestDialog() as BottomSheetDialog
                ShadowLooper.idleMainLooper()
                shootWindow(sheet.window!!.decorView, File(out, "transcript-sheet-$mode.png"))
                sheet.dismiss()
            }
        }
        RuntimeEnvironment.setQualifiers("+notnight")
    }

    private fun shoot(activity: AppCompatActivity, file: File) {
        ShadowLooper.idleMainLooper()
        shootWindow(activity.window.decorView, file)
    }

    private fun shootWindow(root: View, file: File) {
        val bitmap = Bitmap.createBitmap(maxOf(1, root.width), maxOf(1, root.height), Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        const val IM_DB = "transcript-screen-test.db"
        const val ALICE = "imsg:tel:+15551234567"
        const val GROUP = "imsg:group:11111111-2222-3333-4444-555555555555"
        const val MESSAGES_ALICE = "c-alice"
        const val SHOTS_PROPERTY = "lightdecoder.uiShots"
    }
}
