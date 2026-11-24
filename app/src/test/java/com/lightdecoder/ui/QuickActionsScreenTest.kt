package com.lightdecoder.ui

import android.app.Activity
import android.view.View
import android.widget.TextView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.IMessagePrefs
import com.lightdecoder.imessage.direct.DirectLibrary
import com.lightdecoder.imessage.direct.DirectLink
import com.lightdecoder.imessage.direct.DirectPrefs
import com.lightdecoder.imessage.direct.DirectWire
import com.lightdecoder.imessage.direct.IMessageExtra
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.PartDto
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.chat.MessagesAdapter
import com.lightdecoder.imessage.direct.TextEffectKind
import com.lightdecoder.ui.chat.ThreadActivity
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * B5's quick actions in an iMessage chat through the library: double tap a bubble to react (and
 * what never starts a double tap), "Jump to first unread", and the screen effect a phrase sends.
 */
@RunWith(AndroidJUnit4::class)
class QuickActionsScreenTest {

    private lateinit var two: TwoServicesEnv
    private lateinit var imDb: ArchiveDb
    private lateinit var imRepo: ArchiveRepository
    private val requests: MutableList<Pair<Int, JSONObject>> = java.util.Collections.synchronizedList(ArrayList())

    @Before
    fun setUp() {
        two = TwoServicesEnv()
        two.app.deleteDatabase(IM_DB)
        imDb = ArchiveDb(two.app, IM_DB)
        imRepo = ArchiveRepository.forTesting(imDb, ChatService.IMESSAGE)
        ArchiveRepository.installForTesting(imRepo)
        IMessageManager.installForTesting(two.app, imRepo)
        IMessagePrefs.setEnabled(two.app, true)
        DirectPrefs.setPhoneStyleOn(two.app, true)
        DirectPrefs.setRegistered(two.app, true)
        DirectLibrary.REQUIRED.forEach { File(DirectLibrary.dir(two.app), it).apply { parentFile?.mkdirs(); writeText("") } }
        var sends = 0
        DirectLink.installForTesting(two.app) { what, request, _ ->
            requests += what to request
            if (what == DirectWire.SEND) DirectWire.sent("S${++sends}", System.currentTimeMillis()) else DirectWire.ok()
        }
        imRepo.upsertConversation(
            ConversationDto(
                id = ALICE, name = "Alice Martin", type = IMessageManager.TRANSPORT, defaultOutgoingId = "tel:+15550000001",
                participants = listOf(
                    ParticipantDto(id = "me", isMe = true),
                    ParticipantDto(id = "+15551234567", name = "Alice Martin", number = "+15551234567", formattedNumber = "+15551234567"),
                ),
            ),
        )
    }

    @After
    fun tearDown() {
        DirectPrefs.setPhraseEffects(two.app, true)
        IMessagePrefs.setEnabled(two.app, false)
        DirectPrefs.setRegistered(two.app, false)
        DirectLink.resetForTesting()
        IMessageManager.resetForTesting()
        two.tearDown()
        imDb.close()
    }

    private fun message(guid: String, text: String, ts: Long, mine: Boolean = false, status: String = if (mine) "OUTGOING_COMPLETE" else "INCOMING_COMPLETE") {
        imRepo.upsertMessage(
            MessageDto(
                id = "imsg:$guid", conversationId = ALICE, fromMe = mine,
                senderId = if (mine) "" else "+15551234567", senderName = if (mine) "" else "Alice Martin",
                timestampMs = ts, direction = if (mine) "out" else "in", status = status,
                transport = IMessageManager.TRANSPORT, text = text, parts = listOf(PartDto("0", "text", text)), isOld = true,
            ),
        )
    }

    private fun launch(): ActivityScenario<ThreadActivity> = ActivityScenario.launch(RelayIntents.thread(two.app, ALICE, service = ChatService.IMESSAGE).setFlags(0))

    private fun <A : Activity> ActivityScenario<A>.waitFor(what: String, condition: (A) -> Boolean) =
        two.waitUntil(what) {
            var ok = false
            onActivity { ok = condition(it) }
            ok
        }

    private fun rowOf(activity: Activity, text: String): View? {
        val list = activity.findViewById<RecyclerView>(R.id.messages)
        return (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.findViewById<TextView>(R.id.body)?.text?.toString() == text }
    }

    private fun bubble(activity: Activity, text: String): View = rowOf(activity, text)!!.findViewById(R.id.bubble)

    private fun waitOut() = ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)

    private fun actionLabels(view: View): List<String> =
        AccessibilityNodeInfoCompat.wrap(view.createAccessibilityNodeInfo()).actionList.mapNotNull { it.label?.toString() }

    // --- double tap ------------------------------------------------------------------------------

    @Test
    @Config(qualifiers = "w390dp-h800dp-xhdpi")
    fun aDoubleTapOpensTheTapbacksAndASingleTapDoesNot() {
        val now = System.currentTimeMillis()
        message("A1", "Dinner at 8?", now - 60_000)
        message("F1", "Never left", now - 30_000, mine = true, status = "OUTGOING_FAILED")
        launch().use { scenario ->
            scenario.waitFor("the messages") { rowOf(it, "Dinner at 8?") != null && rowOf(it, "Never left") != null }
            scenario.onActivity { activity ->
                // A single tap: nothing, once its wait is over.
                bubble(activity, "Dinner at 8?").performClick()
                waitOut()
                assertNull(activity.tapbackPopup)
                // Two: the six tapbacks and "+" above the bubble.
                bubble(activity, "Dinner at 8?").performClick()
                bubble(activity, "Dinner at 8?").performClick()
                val popup = activity.tapbackPopup!!
                assertTrue(popup.isShowing)
                val bar = popup.contentView as android.view.ViewGroup
                assertEquals(com.lightdecoder.relay.MessageNotifier.TAPBACK_CHOICES, (0 until 6).map { (bar.getChildAt(it) as TextView).text.toString() })
                assertEquals(7, bar.childCount)
                // One tap reacts, as the long-press sheet does.
                bar.getChildAt(0).performClick()
                assertFalse(popup.isShowing)
                two.waitUntil("the tapback") { synchronized(requests) { requests.any { it.first == DirectWire.REACT } } }
                // TalkBack: "React" next to Reply.
                assertTrue(actionLabels(bubble(activity, "Dinner at 8?")).containsAll(listOf("React", "Reply")))
                // A failed message: its tap is the failure's at once, it never reacts, and has no "React".
                val failed = bubble(activity, "Never left")
                failed.performClick()
                failed.performClick()
                assertNull(activity.tapbackPopup?.takeIf { it.isShowing })
                assertFalse(actionLabels(failed).contains("React"))
                waitOut()
            }
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h800dp-xhdpi")
    fun aLinksTapIsTheLinksAndNeverCounts() {
        message("L1", "Here: https://example.com/trailhead", System.currentTimeMillis() - 60_000)
        launch().use { scenario ->
            scenario.waitFor("the link") { rowOf(it, "Here: https://example.com/trailhead") != null }
            scenario.onActivity { activity ->
                val body = rowOf(activity, "Here: https://example.com/trailhead")!!.findViewById<TextView>(R.id.body)
                val layout = body.layout
                val text = body.text.toString()
                val x = layout.getPrimaryHorizontal(text.indexOf("example")) + body.totalPaddingLeft
                val y = body.totalPaddingTop + layout.getLineBottom(0) / 2f
                repeat(2) {
                    val at = android.os.SystemClock.uptimeMillis()
                    for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                        val event = android.view.MotionEvent.obtain(at, at + 20, action, x, y, 0)
                        body.dispatchTouchEvent(event)
                        event.recycle()
                    }
                    ShadowLooper.idleMainLooper()
                }
                assertNull("two taps on a link open it twice, never the tapbacks", activity.tapbackPopup)
                assertEquals("https://example.com/trailhead", org.robolectric.Shadows.shadowOf(activity).nextStartedActivity?.dataString)
                waitOut()
            }
        }
    }

    // --- jump to first unread ----------------------------------------------------------------------------

    @Test
    @Config(qualifiers = "w390dp-h800dp-xhdpi")
    fun theArrowShowsOnlyWhileTheFirstUnreadMessageIsAboveTheScreen() {
        val now = System.currentTimeMillis()
        for (i in 0 until 60) message("M$i", "Message number $i", now - (60 - i) * 60_000L)
        // Unread since message 20, as the archive kept it when the chat became unread.
        imRepo.markConversationRead(ALICE)
        imRepo.upsertConversation(imRepo.conversation(ALICE)!!.let {
            ConversationDto(id = ALICE, name = it.name, type = IMessageManager.TRANSPORT, unread = true, defaultOutgoingId = it.defaultOutgoingId,
                participants = imRepo.participants(ALICE).map { p -> ParticipantDto(id = p.id, name = p.name, number = p.number, isMe = p.isMe) })
        }, unreadSinceMs = now - 40 * 60_000L)
        assertEquals(now - 40 * 60_000L, imRepo.conversation(ALICE)!!.firstUnreadTs)
        launch().use { scenario ->
            scenario.waitFor("the newest") { rowOf(it, "Message number 59") != null }
            ShadowLooper.idleMainLooper()
            scenario.onActivity { activity ->
                activity.renderCatchUp()
                val arrow = activity.findViewById<View>(R.id.catchUp)
                assertEquals(View.VISIBLE, arrow.visibility)
                assertEquals("Jump to first unread", arrow.contentDescription.toString())
                arrow.performClick()
                ShadowLooper.idleMainLooper()
                assertEquals(View.GONE, arrow.visibility)
            }
            scenario.waitFor("the first unread on screen") { rowOf(it, "Message number 20") != null }
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h800dp-xhdpi")
    fun noArrowWhenTheFirstUnreadMessageIsOnScreen() {
        val now = System.currentTimeMillis()
        for (i in 0 until 60) message("M$i", "Message number $i", now - (60 - i) * 60_000L)
        imRepo.markConversationRead(ALICE)
        imRepo.upsertConversation(ConversationDto(id = ALICE, name = "Alice Martin", type = IMessageManager.TRANSPORT, unread = true, defaultOutgoingId = "tel:+15550000001",
            participants = imRepo.participants(ALICE).map { p -> ParticipantDto(id = p.id, name = p.name, number = p.number, isMe = p.isMe) }), unreadSinceMs = now - 60_000L)
        launch().use { scenario ->
            scenario.waitFor("the newest") { rowOf(it, "Message number 59") != null }
            ShadowLooper.idleMainLooper()
            scenario.onActivity { activity ->
                activity.renderCatchUp()
                assertEquals(View.GONE, activity.findViewById<View>(R.id.catchUp).visibility)
            }
        }
    }

    // --- phrase effects ----------------------------------------------------------------------------

    @Test
    @Config(qualifiers = "w390dp-h800dp-xhdpi")
    fun aPhraseGoesWithItsEffectAndAChosenEffectWins() {
        message("A1", "It's my birthday", System.currentTimeMillis() - 60_000)
        launch().use { scenario ->
            scenario.waitFor("the chat") { rowOf(it, "It's my birthday") != null }
            scenario.onActivity { activity ->
                activity.findViewById<android.widget.EditText>(R.id.input).setText("Happy Birthday!!!")
                activity.findViewById<View>(R.id.send).performClick()
                // The choices the phrase gives way to.
                val loud = "com.apple.MobileSMS.expressivesend.loud"
                assertEquals(loud, activity.phraseEffect("happy birthday", loud, null, textOnly = true))
                assertNull(activity.phraseEffect("happy birthday", null, TextEffectKind.values().first(), textOnly = true))
                assertNull(activity.phraseEffect("happy birthday", null, null, textOnly = false))
                assertNull(activity.phraseEffect("happy birthday to you", null, null, textOnly = true))
                DirectPrefs.setPhraseEffects(two.app, false)
                assertNull(activity.phraseEffect("happy birthday", null, null, textOnly = true))
            }
            two.waitUntil("sent to Apple") { synchronized(requests) { requests.any { it.first == DirectWire.SEND } } }
            val sent = synchronized(requests) { requests.first { it.first == DirectWire.SEND }.second }
            assertEquals("Happy Birthday!!!", sent.getString("text"))
            assertEquals("com.apple.messages.effect.CKHappyBirthdayEffect", sent.getString("effect"))
            two.waitUntil("its caption") { imRepo.messages(ALICE).any { IMessageExtra.parse(it.extra).effect == "com.apple.messages.effect.CKHappyBirthdayEffect" } }
        }
    }

    private companion object {
        const val IM_DB = "quick-actions-test.db"
        const val ALICE = "imsg:tel:+15551234567"
    }
}
