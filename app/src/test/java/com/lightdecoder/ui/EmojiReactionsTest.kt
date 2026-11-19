package com.lightdecoder.ui

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.RelayTestEnv
import com.lightdecoder.ui.chat.MessagesAdapter
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.ui.chat.ThreadItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

/** Reactions: five quick ones and a white "+" that opens every emoji; any emoji goes to the phone. */
@RunWith(AndroidJUnit4::class)
class EmojiReactionsTest {

    private lateinit var env: RelayTestEnv
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        env = RelayTestEnv()
        ArchiveRepository.installForTesting(env.repo)
    }

    @After
    fun tearDown() {
        env.tearDown()
        ArchiveRepository.resetForTesting()
    }

    private fun seed() {
        env.repo.upsertConversation(
            ConversationDto(
                id = "c1", name = "Alice", folder = "inbox", lastMessageTs = 1_000,
                participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = "p1", name = "Alice")),
            )
        )
        env.repo.upsertMessage(MessageDto(id = "m1", conversationId = "c1", senderId = "p1", senderName = "Alice", timestampMs = 1_000, text = "We won!", isOld = true))
    }

    private fun row(activity: ThreadActivity) =
        (activity.findViewById<RecyclerView>(R.id.messages).adapter as MessagesAdapter).currentList
            .filterIsInstance<ThreadItem.Message>().single { it.row.text == "We won!" }.row

    @Test
    fun fiveQuickReactionsThenAWhitePlusThatOpensEveryEmoji() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        manager.awaitIdleForTesting()
        seed()
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("the message") { a -> runCatching { row(a) }.isSuccess }
            scenario.onActivity { a -> a.onMessageLongPress(row(a)) }
            shadowOf(Looper.getMainLooper()).idle()
            val sheet = ShadowDialog.getLatestDialog()
            val quick = sheet.findViewById<ViewGroup>(R.id.reactionRow)
            assertEquals("five emoji and the plus", 6, quick.childCount)
            assertEquals(ThreadActivity.QUICK_REACTIONS, (0 until 5).map { (quick.getChildAt(it) as TextView).text.toString() })
            val plus = quick.getChildAt(5).findViewById<View>(R.id.reactionMore)
            assertNotNull(plus)
            plus.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull("the full picker", ShadowDialog.getLatestDialog().findViewById<View>(R.id.emojiPicker))
            // Any emoji from it goes to the phone like a quick one.
            scenario.onActivity { a -> a.reactionPicked(row(a), "🔥", null, isIMessage = false) }
            env.eventually(manager, what = "the reaction sent") { fake.reactionsSent.any { it.first == "m1" && it.second == "🔥" } }
        }
    }

    @Test
    fun aReactionThatIsAnotherEmojiShowsInTheQuickRow() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        manager.awaitIdleForTesting()
        seed()
        env.repo.upsertMessage(
            MessageDto(
                id = "m1", conversationId = "c1", senderId = "p1", senderName = "Alice", timestampMs = 1_000, text = "We won!", isOld = true,
                reactions = listOf(com.lightdecoder.relay.ReactionDto("🔥", participantIds = listOf("me"), fromMe = true)),
            )
        )
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("the message") { a -> runCatching { row(a) }.isSuccess }
            scenario.onActivity { a -> a.onMessageLongPress(row(a)) }
            shadowOf(Looper.getMainLooper()).idle()
            val quick = ShadowDialog.getLatestDialog().findViewById<ViewGroup>(R.id.reactionRow)
            val last = quick.getChildAt(4) as TextView
            assertEquals("🔥", last.text.toString())
            assertTrue("shown as mine", last.isSelected)
        }
    }

    private fun <A : android.app.Activity> ActivityScenario<A>.waitFor(what: String, timeoutMs: Long = 10_000, condition: (A) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            var ok = false
            onActivity { ok = condition(it) }
            if (ok) return
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }
}
