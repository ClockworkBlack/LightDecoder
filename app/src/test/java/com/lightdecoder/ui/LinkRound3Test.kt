package com.lightdecoder.ui

import android.app.Application
import android.app.Dialog
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MediaRow
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.archive.OutboxRow
import com.lightdecoder.archive.OutboxStatus
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.Outbox
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.ReactionDto
import com.lightdecoder.relay.RelayException
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.RelayManager
import com.lightdecoder.relay.RelayTestEnv
import com.lightdecoder.ui.chat.MediaLoader
import com.lightdecoder.ui.chat.MessagesAdapter
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.ui.chat.ThreadItem
import com.lightdecoder.ui.chat.ThreadItems
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The finishing pass on the thread screen: a failed send that may have gone
 * out all the same says so and is only sent again once the user confirms,
 * while one that did not go out keeps its one-tap retry; and going online
 * during a thread switch does not page the new thread from the phone before
 * its own rows are read.
 */
@RunWith(AndroidJUnit4::class)
class LinkRound3Test {

    private lateinit var env: RelayTestEnv
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        env = RelayTestEnv()
        ArchiveRepository.installForTesting(env.repo)
    }

    @After
    fun tearDown() {
        ThreadActivity.beforeLocalRead = null
        MessageNotifier.visibleConversationId = null
        env.tearDown()
        ArchiveRepository.resetForTesting()
    }

    // --- sends whose outcome is unknown ---------------------------------------------------

    @Test
    fun aSendThatMayHaveGoneOutHasItsOwnLabelThatTalkBackReads() {
        val (manager, _) = env.pairedManager()
        seedConversation("c1", "Alice")
        val unknown = listOf(Outbox.UNCONFIRMED, Outbox.INTERRUPTED, RelayException.PHONE_NOT_RESPONDING)
        unknown.forEachIndexed { i, reason -> queueFailed("u$i", "c1", reason, 1_000L + i) }
        queueFailed("f1", "c1", RelayException.SEND_FAILED, 2_000)
        val rows = env.repo.messages("c1", limit = 10)
        assertEquals(4, rows.size)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val context = ContextThemeWrapper(app, R.style.Theme_LightDecoder)
            val adapter = MessagesAdapter(MediaLoader(manager, scope), NoCallbacks)
            val items = ThreadItems.build(rows, false, emptyMap(), null, null, { 0L }, { "" }).filterIsInstance<ThreadItem.Message>()
            adapter.submitList(items)
            val parent = FrameLayout(context)
            val unconfirmed = app.getString(R.string.chat_failed_unconfirmed)
            val notSent = app.getString(R.string.chat_failed_retry)
            for ((position, item) in items.withIndex()) {
                val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(position)) as MessagesAdapter.MessageHolder
                adapter.onBindViewHolder(holder, position)
                val maybeSent = item.row.errorText in unknown
                val label = holder.binding.failedText
                assertEquals(View.VISIBLE, label.visibility)
                assertEquals(item.row.errorText, if (maybeSent) unconfirmed else notSent, label.text.toString())

                // TalkBack reads the label on its own, and says what a double-tap does.
                assertTrue(label.isImportantForAccessibility)
                val node = label.createAccessibilityNodeInfo()
                assertEquals(label.text.toString(), node.text?.toString())
                val click = node.actionList.firstOrNull { it.id == AccessibilityNodeInfo.ACTION_CLICK }
                assertNotNull("${item.row.errorText}: no click action", click)
                assertEquals(
                    app.getString(if (maybeSent) R.string.chat_failed_unconfirmed_action else R.string.chat_action_retry),
                    click!!.label?.toString(),
                )
                // The bubble's description carries it too (for whoever moves bubble by bubble).
                val description = holder.binding.bubble.contentDescription.toString()
                if (maybeSent) {
                    assertTrue(description, description.endsWith(unconfirmed))
                } else {
                    assertTrue(description, description.endsWith(app.getString(R.string.chat_status_failed)))
                }
            }

            // A holder rebound from "may have been sent" to a delivered message loses the click label.
            val first = items.indexOfFirst { it.row.errorText in unknown }
            val delivered = items[first].let {
                it.copy(row = it.row.copy(localState = LocalState.SYNCED, errorText = "", status = "OUTGOING_DELIVERED", id = "m1"))
            }
            val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(first)) as MessagesAdapter.MessageHolder
            adapter.onBindViewHolder(holder, first)
            assertNotNull(holder.binding.bubble.createAccessibilityNodeInfo().actionList.firstOrNull { it.id == AccessibilityNodeInfo.ACTION_CLICK })
            val later = MessagesAdapter(MediaLoader(manager, scope), NoCallbacks).apply { submitList(listOf(delivered)) }
            later.onBindViewHolder(holder, 0)
            assertEquals(View.GONE, holder.binding.failedText.visibility)
            assertNull(
                holder.binding.bubble.createAccessibilityNodeInfo().actionList.firstOrNull { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aSendThatMayHaveGoneOutIsSentAgainOnlyOnceTheUserSaysSo() {
        val (manager, fake) = env.pairedManager()
        seedConversation("c1", "Alice")
        queueFailed("t1", "c1", Outbox.UNCONFIRMED, 1_000)
        queueFailed("t2", "c1", Outbox.INTERRUPTED, 2_000)
        val controller = open("c1")
        try {
            val activity = controller.get()
            waitUntil("both sends on screen") { holderFor(activity, "t1") != null && holderFor(activity, "t2") != null }

            // A tap on "Not confirmed" asks, and sends nothing.
            holderFor(activity, "t1")!!.binding.failedText.performClick()
            var question = latestAlert()
            assertEquals(app.getString(R.string.chat_resend_body), question.findViewById<TextView>(android.R.id.message)!!.text.toString())
            assertEquals(app.getString(R.string.chat_resend_send), question.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            assertEquals(app.getString(R.string.chat_action_discard), question.getButton(AlertDialog.BUTTON_NEUTRAL).text.toString())
            settleWith(manager)
            assertStillFailed("t1")

            // Cancel: still nothing.
            question.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            settleWith(manager)
            assertFalse(question.isShowing)
            assertStillFailed("t1")

            // The bubble itself asks too; "Send again" queues it.
            holderFor(activity, "t1")!!.binding.bubble.performClick()
            question = latestAlert()
            question.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            whenIdle(manager, "t1 queued again") { env.repo.outbox("t1")?.status == OutboxStatus.PENDING }
            assertEquals(LocalState.PENDING, env.repo.message(MessageRow.tmpMessageId("t1"))!!.localState)

            // The sheet's "Try sending again" asks as well; "Discard" drops the copy here.
            activity.onMessageLongPress(env.repo.message(MessageRow.tmpMessageId("t2"))!!)
            (ShadowDialog.getLatestDialog() as BottomSheetDialog).findViewById<View>(R.id.actionRetry)!!.performClick()
            question = latestAlert()
            settleWith(manager)
            assertStillFailed("t2")
            question.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            whenIdle(manager, "t2 discarded") {
                env.repo.outbox("t2") == null && env.repo.message(MessageRow.tmpMessageId("t2")) == null
            }
            assertTrue("nothing went out while offline", fake.sent.isEmpty())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun aSendThatDidNotGoOutIsRetriedWithOneTap() {
        val (manager, _) = env.pairedManager()
        seedConversation("c1", "Alice")
        queueFailed("t1", "c1", RelayException.SEND_FAILED, 1_000)
        queueFailed("t2", "c1", RelayException.TOO_LARGE, 2_000)
        val controller = open("c1")
        try {
            val activity = controller.get()
            waitUntil("both sends on screen") { holderFor(activity, "t1") != null && holderFor(activity, "t2") != null }
            assertEquals(app.getString(R.string.chat_failed_retry), holderFor(activity, "t1")!!.binding.failedText.text.toString())

            holderFor(activity, "t1")!!.binding.failedText.performClick()
            whenIdle(manager, "t1 queued again") { env.repo.outbox("t1")?.status == OutboxStatus.PENDING }
            assertNoQuestion()

            activity.onMessageLongPress(env.repo.message(MessageRow.tmpMessageId("t2"))!!)
            (ShadowDialog.getLatestDialog() as BottomSheetDialog).findViewById<View>(R.id.actionRetry)!!.performClick()
            whenIdle(manager, "t2 queued again") { env.repo.outbox("t2")?.status == OutboxStatus.PENDING }
            assertNoQuestion()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    // --- going online during a thread switch ----------------------------------------------

    @Test
    fun goingOnlineDuringAThreadSwitchDoesNotPageTheNewThreadOnTheEmptyList() {
        val (manager, fake) = env.pairedManager()
        seedConversation("c0", "Carol")
        // Bob has more than a page here: nothing should ask the phone for his history.
        seedConversation("c2", "Bob")
        for (i in 1..60) env.repo.upsertMessage(textMessage("b$i", "c2", i * 1_000L))
        val controller = open("c0")
        try {
            val activity = controller.get()
            val release = holdTheFirstReadOf("c2") {
                InstrumentationRegistry.getInstrumentation()
                    .callActivityOnNewIntent(activity, RelayIntents.thread(app, "c2").setFlags(0))
            }
            try {
                // The link comes up while Bob's rows are still being read.
                fake.goOnline()
                env.eventually(manager, what = "online") { manager.state.value.isOnline }
                settle(10)
                assertTrue("paged from the empty list: ${fake.fetches}", fake.fetches.none { it.first == "c2" })
            } finally {
                release.countDown()
            }
            waitUntil("Bob's rows") { activity.supportActionBar?.title?.toString() == "Bob" }
            settle(10)
            assertTrue("Bob was paged from the phone: ${fake.fetches}", fake.fetches.none { it.first == "c2" })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun aThreadSwitchedToWhileGoingOnlineIsStillPagedOnceItsOwnRowsAreIn() {
        val (manager, fake) = env.pairedManager()
        seedConversation("c0", "Carol")
        // Dave has only a few messages here: once they are read, the phone is asked for more.
        seedConversation("c3", "Dave")
        for (i in 1..3) env.repo.upsertMessage(textMessage("d$i", "c3", i * 1_000L))
        val controller = open("c0")
        try {
            val activity = controller.get()
            val release = holdTheFirstReadOf("c3") {
                InstrumentationRegistry.getInstrumentation()
                    .callActivityOnNewIntent(activity, RelayIntents.thread(app, "c3").setFlags(0))
            }
            try {
                fake.goOnline()
                env.eventually(manager, what = "online") { manager.state.value.isOnline }
                settle(10)
                assertTrue("paged before its rows were read: ${fake.fetches}", fake.fetches.none { it.first == "c3" })
            } finally {
                release.countDown()
            }
            waitUntil("Dave paged from the phone") { fake.fetches.any { it.first == "c3" } }
            settle(5)
            assertEquals("asked once: ${fake.fetches}", 1, fake.fetches.count { it.first == "c3" })
            assertEquals("Dave", activity.supportActionBar?.title?.toString())
        } finally {
            controller.pause().stop().destroy()
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

    /** A send the outbox gave up on with [reason], as it leaves one (local copy and queue entry). */
    private fun queueFailed(tmpId: String, conversationId: String, reason: String, ts: Long) {
        val text = "send $tmpId"
        env.repo.insertLocalOutgoing(tmpId, conversationId, text, null, null, null, null, ts)
        env.repo.enqueueOutbox(
            OutboxRow(
                idempotencyKey = tmpId, conversationId = conversationId, textBody = text,
                status = OutboxStatus.FAILED, lastError = reason, createdAt = ts,
            )
        )
        env.repo.setLocalState(tmpId, LocalState.FAILED, reason)
    }

    private fun assertStillFailed(tmpId: String) {
        assertEquals(OutboxStatus.FAILED, env.repo.outbox(tmpId)?.status)
        assertEquals(LocalState.FAILED, env.repo.message(MessageRow.tmpMessageId(tmpId))!!.localState)
    }

    private fun assertNoQuestion() {
        assertFalse("asked", ShadowDialog.getShownDialogs().any { it is AlertDialog && it.isShowing })
    }

    private fun open(conversationId: String): ActivityController<ThreadActivity> {
        val controller = Robolectric.buildActivity(ThreadActivity::class.java, RelayIntents.thread(app, conversationId).setFlags(0)).setup()
        settle()
        return controller
    }

    /**
     * Runs [switch] with the first read of [conversationId]'s rows held (as a
     * busy database would hold it) and returns once that read has started;
     * counting the latch down lets it go on.
     */
    private fun holdTheFirstReadOf(conversationId: String, switch: () -> Unit): CountDownLatch {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        ThreadActivity.beforeLocalRead = { id ->
            if (id == conversationId && entered.count > 0) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        switch()
        if (!entered.await(10, TimeUnit.SECONDS)) {
            release.countDown()
            fail("the read of $conversationId never started")
        }
        return release
    }

    private fun holderFor(activity: ThreadActivity, tmpId: String): MessagesAdapter.MessageHolder? {
        val list = activity.findViewById<RecyclerView>(R.id.messages)
        val adapter = list.adapter as MessagesAdapter
        val position = adapter.currentList.indexOfFirst { it is ThreadItem.Message && it.row.tmpId == tmpId }
        if (position < 0) return null
        return list.findViewHolderForAdapterPosition(position) as? MessagesAdapter.MessageHolder
    }

    private fun textMessage(id: String, conversationId: String, ts: Long) = MessageDto(
        id = id, conversationId = conversationId, senderId = "p1", senderName = "Someone", timestampMs = ts,
        isOld = true, text = "message $id",
    )

    private fun latestAlert(): AlertDialog {
        settle(1)
        val dialog: Dialog? = ShadowDialog.getLatestDialog()
        assertNotNull("no dialog", dialog)
        assertTrue("not a question: $dialog", dialog is AlertDialog)
        assertTrue("dialog not showing", dialog!!.isShowing)
        return dialog as AlertDialog
    }

    private fun seedConversation(id: String, name: String) {
        env.repo.upsertConversation(
            ConversationDto(
                id = id,
                name = name,
                folder = "inbox",
                lastMessageTs = 1_000,
                participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = "p1", name = name)),
            )
        )
    }

    /** Like [RelayTestEnv.eventually], letting the main thread run too (dialog buttons post their clicks). */
    private fun whenIdle(manager: RelayManager, what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            settle(1)
            manager.awaitIdleForTesting()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
        }
    }

    private fun settleWith(manager: RelayManager) {
        settle()
        manager.awaitIdleForTesting()
        settle(1)
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
}
