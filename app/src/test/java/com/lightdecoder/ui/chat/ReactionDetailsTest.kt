package com.lightdecoder.ui.chat

import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.R
import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MediaRow
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.archive.ParticipantRow
import com.lightdecoder.reactions.ReactionKey
import com.lightdecoder.reactions.ReactionTime
import com.lightdecoder.relay.ReactionDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.util.Calendar

/** Holding a reaction down shows who reacted with what, and when. */
@RunWith(AndroidJUnit4::class)
class ReactionDetailsTest {

    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun row(id: String, ts: Long, text: String, reactions: List<ReactionDto> = emptyList(), sender: String = "p2") = MessageRow(
        id = id, conversationId = "c1", tmpId = "", senderId = sender, senderName = "", isOutgoing = false, timestampMs = ts,
        direction = "in", status = "", statusCode = 0, errorText = "", transport = "RCS", text = text, subject = "",
        replyToId = "", reactions = reactions, localState = LocalState.SYNCED, media = emptyList<MediaRow>(), updatedAt = 0,
    )

    private fun person(id: String, name: String, me: Boolean = false) = ParticipantRow(
        conversationId = "c1", id = id, name = name, firstName = name, number = "", formattedNumber = "", isMe = me,
        color = "", contactId = "", visible = true,
    )

    private val people = listOf(person("1", "Me", me = true), person("p2", "Alice"), person("p3", "Bob"))

    @Test
    fun everyoneIsListedWithTheirTimeTheChosenEmojiFirstAndUnknownLast() {
        val message = row(
            "m1", 1_000, "hi",
            listOf(
                ReactionDto("👍", listOf("1"), fromMe = true),
                ReactionDto("❤️", listOf("p2", "p3")),
            ),
        )
        val stored = mapOf(
            ReactionKey("❤️", "p3") to ReactionTime(9_000, ReactionTime.Kind.SEEN),
            ReactionKey("👍", "1") to ReactionTime(4_000, ReactionTime.Kind.BY),
        )
        val entries = ReactionDetails.entries(message, "❤️", people, stored, you = "You", unknown = "Unknown")
        assertEquals(listOf("❤️ Bob", "❤️ Alice", "👍 You"), entries.map { "${it.emoji} ${it.name}" })
        assertEquals(9_000L, entries[0].time!!.atMs)
        assertNull("Alice's was there before it was seen", entries[1].time)
        assertTrue(entries[2].isMe)
        assertEquals(ReactionTime.Kind.BY, entries[2].time!!.kind)
    }

    @Test
    fun anIPhoneReactionKeepsTheTimeItsTextWasSent() {
        val target = row("m1", 1_000, "see you at 6")
        val tapback = row("m2", 61_000, "Loved “see you at 6”")
        val shown = Tapbacks.apply(listOf(target, tapback))
        val heart = shown.single().reactions.single()
        assertEquals(mapOf("p2" to 61_000L), heart.madeAtMs)
        val entry = ReactionDetails.entries(shown.single(), "❤️", people, emptyMap(), "You", "Unknown").single()
        assertEquals("Alice", entry.name)
        assertEquals(ReactionTime(61_000, ReactionTime.Kind.EXACT), entry.time)
    }

    @Test
    fun timesReadAsDayAndClockWithAtOrBeforeAndNotKnown() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 17, 16, 30) }.timeInMillis
        val earlier = now - 60 * 60 * 1000
        val seen = ReactionDetails.describe(app, ReactionTime(earlier, ReactionTime.Kind.SEEN), now)
        assertTrue(seen, seen.startsWith("Today, "))
        assertTrue(ReactionDetails.describe(app, ReactionTime(earlier, ReactionTime.Kind.BY), now).startsWith("At or before Today, "))
        assertEquals("Time not known", ReactionDetails.describe(app, null, now))
    }

    @Test
    fun holdingAChipAsksForTheDetailsAndTappingStillReacts() {
        val reaction = ReactionDto("❤️", listOf("p2"))
        val message = row("m1", 1_000, "hi", listOf(reaction))
        var details: ReactionDto? = null
        var tapped: ReactionDto? = null
        val callbacks = object : MessagesAdapter.Callbacks {
            override fun onMessageLongPress(row: MessageRow) = Unit
            override fun onFailedTap(row: MessageRow) = Unit
            override fun onMediaTap(row: MessageRow, part: MediaRow) = Unit
            override fun onLoadFullSize(row: MessageRow, part: MediaRow) = Unit
            override fun onQuoteTap(messageId: String) = Unit
            override fun onReactionTap(row: MessageRow, reaction: ReactionDto) { tapped = reaction }
            override fun onReply(row: MessageRow) = Unit
            override fun onReactionDetails(row: MessageRow, reaction: ReactionDto) { details = reaction }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val env = com.lightdecoder.relay.RelayTestEnv()
        try {
            val backend = FakeChatBackend(com.lightdecoder.chat.ChatService.MESSAGES, env.repo)
            val adapter = MessagesAdapter(MediaLoader(backend, scope), callbacks)
            adapter.submitList(
                ThreadItems.build(listOf(message), false, emptyMap(), null, null, { 0L }, { "" }).filterIsInstance<ThreadItem.Message>()
            )
            shadowOf(Looper.getMainLooper()).idle()
            val parent = FrameLayout(ContextThemeWrapper(app, R.style.Theme_LightDecoder))
            val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
            adapter.onBindViewHolder(holder, 0)
            val chip = (holder as MessagesAdapter.MessageHolder).binding.reactions.getChildAt(0)
            assertTrue(chip.performLongClick())
            assertEquals(reaction, details)
            assertNull("holding does not react", tapped)
            chip.performClick()
            assertEquals(reaction, tapped)
        } finally {
            scope.cancel()
            env.tearDown()
        }
    }

    @Test
    fun thePanelListsThePeopleTheirTimesAndTheNote() {
        // Any of the app's screens will do as the panel's window.
        ActivityScenario.launch(com.lightdecoder.ui.LicensesActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val message = row("m1", 1_000, "hi", listOf(ReactionDto("❤️", listOf("p2"))))
                ReactionDetails.show(activity, message, "❤️", people, emptyMap())
            }
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog()
            assertTrue(dialog.isShowing)
            val texts = ArrayList<String>()
            fun walk(v: View) {
                if (v is TextView) texts += v.text.toString()
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(dialog.window!!.decorView)
            assertTrue(texts.toString(), "Reactions" in texts)
            assertTrue(texts.toString(), "Alice" in texts)
            assertTrue(texts.toString(), "Time not known" in texts)
            assertTrue(texts.toString(), texts.any { it.startsWith("Google Messages doesn't say when a reaction was made") })
        }
    }
}
