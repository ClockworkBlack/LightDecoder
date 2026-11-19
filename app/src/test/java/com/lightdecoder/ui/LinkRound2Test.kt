package com.lightdecoder.ui

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.MediaRow
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.ReactionDto
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.RelayState
import com.lightdecoder.relay.RelayTestEnv
import com.lightdecoder.ui.chat.MediaLoader
import com.lightdecoder.ui.chat.MessagesAdapter
import com.lightdecoder.ui.chat.ReactionRow
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.ui.chat.ThreadItem
import com.lightdecoder.ui.chat.ThreadItems
import com.lightdecoder.ui.relay.LocalArchive
import com.lightdecoder.ui.relay.RelayStatusActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboMenuItem
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The second review round of the screens: a thread switch closes what
 * belonged to the old conversation and drops its load's tail, the status
 * screen's renders apply in order, reaction chips keep their padding and
 * wrap, and the release viewer opens what the debug build shares.
 */
@RunWith(AndroidJUnit4::class)
class LinkRound2Test {

    private lateinit var env: RelayTestEnv
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        env = RelayTestEnv()
        ArchiveRepository.installForTesting(env.repo)
    }

    @After
    fun tearDown() {
        LocalArchive.beforeHasAny = null
        MessageNotifier.visibleConversationId = null
        env.tearDown()
        ArchiveRepository.resetForTesting()
    }

    // --- a thread switch closes the old conversation's sheet and questions -------------

    @Test
    fun switchingThreadClosesTheOldConversationsSheetAndQuestions() {
        val (manager, fake) = env.pairedManager()
        // Online, so a delete or send that did go out would reach the phone (and show here).
        fake.goOnline()
        env.eventually(manager, what = "online") { manager.state.value.isOnline }
        seedConversation("c1", "Alice")
        seedConversation("c2", "Bob")
        env.repo.upsertMessage(textMessage("a1", "c1", 1_000))
        env.repo.upsertMessage(textMessage("b1", "c2", 1_000))
        val alices = env.repo.messages("c1", limit = 10).single()
        val controller = Robolectric.buildActivity(ThreadActivity::class.java, RelayIntents.thread(app, "c1").setFlags(0)).setup()
        try {
            val activity = controller.get()
            settle()
            assertEquals("Alice", activity.supportActionBar?.title?.toString())
            val replyBar = activity.findViewById<View>(R.id.replyBar)

            // Alice's long-press sheet is open when Bob's notification is tapped.
            activity.onMessageLongPress(alices)
            val sheet = ShadowDialog.getLatestDialog() as BottomSheetDialog
            assertTrue(sheet.isShowing)
            val reply = sheet.findViewById<View>(R.id.actionReply)!!
            controller.newIntent(RelayIntents.thread(app, "c2").setFlags(0))
            assertFalse("Alice's sheet closed", sheet.isShowing)
            assertNotEquals("not Alice's title on Bob's thread", "Alice", activity.supportActionBar?.title?.toString())
            // A tap that still reaches it, or Alice's row any other way, quotes nothing in Bob's thread.
            reply.performClick()
            activity.onReply(alices)
            assertEquals(View.GONE, replyBar.visibility)
            settle()
            assertEquals("Bob", activity.supportActionBar?.title?.toString())

            // "Delete this conversation?" for Bob, then Alice's notification: Bob is never deleted.
            activity.onOptionsItemSelected(RoboMenuItem(R.id.action_delete_conversation))
            val confirm = latestAlert()
            val delete = confirm.getButton(AlertDialog.BUTTON_POSITIVE)
            controller.newIntent(RelayIntents.thread(app, "c1").setFlags(0))
            assertFalse("Bob's question closed", confirm.isShowing)
            delete.performClick()
            settle()
            assertNotNull(env.repo.conversation("c2"))
            assertFalse("deleteConversation" in fake.calls)
            assertFalse(activity.isFinishing)
            assertEquals("Alice", activity.supportActionBar?.title?.toString())

            // "Delete this message?" for Alice's message, then Bob's notification: it stays.
            activity.onMessageLongPress(env.repo.messages("c1", limit = 10).single())
            (ShadowDialog.getLatestDialog() as BottomSheetDialog).findViewById<View>(R.id.actionDelete)!!.performClick()
            val question = latestAlert()
            val deleteMessage = question.getButton(AlertDialog.BUTTON_POSITIVE)
            controller.newIntent(RelayIntents.thread(app, "c2").setFlags(0))
            assertFalse("Alice's message question closed", question.isShowing)
            deleteMessage.performClick()
            settle()
            assertFalse("deleteMessage" in fake.calls)
            assertNotNull(env.repo.message("a1"))

            // And what is typed for Bob goes to Bob, quoting nothing of Alice's.
            activity.findViewById<EditText>(R.id.input).setText("for Bob")
            activity.findViewById<View>(R.id.send).performClick()
            waitUntil("the send") { fake.sent.isNotEmpty() }
            val local = env.repo.messages("c2", limit = 10).filter { it.isOutgoing }
            assertTrue("$local", local.all { it.replyToId.isEmpty() })
            assertTrue(fake.sent.all { it.conversationId == "c2" })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    // --- a load that outlives a thread switch ------------------------------------------

    @Test
    fun aLoadThatOutlivesAThreadSwitchDoesNotPageTheNewThreadFromThePhone() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        env.eventually(manager, what = "online") { manager.state.value.isOnline }
        seedConversation("c0", "Carol")
        // Alice has a few messages here, so her own load asks the phone for more.
        seedConversation("c1", "Alice", unread = true)
        for (i in 1..3) env.repo.upsertMessage(textMessage("a$i", "c1", i * 1_000L))
        // Bob has more than a page here: his own load never needs the phone.
        seedConversation("c2", "Bob")
        for (i in 1..60) env.repo.upsertMessage(textMessage("b$i", "c2", i * 1_000L))

        val controller = Robolectric.buildActivity(ThreadActivity::class.java, RelayIntents.thread(app, "c0").setFlags(0)).setup()
        try {
            val activity = controller.get()
            settle()
            fake.fetches.clear()
            val looper = shadowOf(Looper.getMainLooper())
            // Alice's notification; her rows are read and her list is being built ...
            InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, RelayIntents.thread(app, "c1").setFlags(0))
            val deadline = System.currentTimeMillis() + 10_000
            while (activity.supportActionBar?.title?.toString() != "Alice") {
                if (System.currentTimeMillis() > deadline) fail("Alice's rows never arrived")
                // One main-thread task at a time, so the build's hand-over does not run yet.
                if (!looper.isIdle) looper.runOneTask() else Thread.sleep(5)
            }
            // ... when Bob's notification is tapped.
            InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, RelayIntents.thread(app, "c2").setFlags(0))
            waitUntil("Bob's rows") { activity.supportActionBar?.title?.toString() == "Bob" }
            settle(10)
            assertTrue("Bob was paged from the phone with Alice's numbers: ${fake.fetches}", fake.fetches.none { it.first == "c2" })
            assertTrue("Alice left as she was", env.repo.conversation("c1")!!.unread)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    // --- the status screen: the newest render wins ---------------------------------------

    @Test
    fun aSlowStatusRenderNeverOverwritesANewerOne() {
        val (manager, fake) = env.pairedManager()
        ActivityScenario.launch(RelayStatusActivity::class.java).use { scenario ->
            scenario.waitFor("the first render") { it.findViewById<View>(R.id.controls).isShown }
            settle()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val held = AtomicBoolean(false)
            // The next render's read of the archive is slow (a backfill keeps the database busy).
            LocalArchive.beforeHasAny = {
                if (held.compareAndSet(false, true)) {
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }
            try {
                fake.emitState("connecting")
                waitUntil("the connecting render under way") { entered.count == 0L }
                fake.emitState("connected")
                env.eventually(manager, what = "connected") { manager.state.value.phase == RelayState.Phase.CONNECTED }
                scenario.waitFor("connected on screen") {
                    it.findViewById<TextView>(R.id.stateTitle).text.toString() == it.getString(R.string.link_phase_connected)
                }
                // The slow render of "connecting" finishes last.
                release.countDown()
                settle(10)
                scenario.onActivity {
                    assertEquals(it.getString(R.string.link_phase_connected), it.findViewById<TextView>(R.id.stateTitle).text.toString())
                    assertEquals(it.getString(R.string.link_body_connected), it.findViewById<TextView>(R.id.stateBody).text.toString())
                }
            } finally {
                release.countDown()
            }
        }
    }

    // --- reaction chips -------------------------------------------------------------------

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun sixReactionsAllFitUnderABubbleAndKeepTheirPadding() {
        val (manager, _) = env.pairedManager()
        seedConversation("c1", "Alice")
        val reactions = (ThreadActivity.QUICK_REACTIONS + "🙏").mapIndexed { i, emoji ->
            ReactionDto(emoji, participantIds = listOf("p1", "p$i", "q$i"), fromMe = i == 0)
        }
        env.repo.upsertMessage(textMessage("m1", "c1", 1_000).copy(reactions = reactions))
        env.repo.upsertMessage(textMessage("m2", "c1", 2_000).copy(reactions = reactions, fromMe = true, direction = "out"))
        val rows = env.repo.messages("c1", limit = 10)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val context = ContextThemeWrapper(app, R.style.Theme_LightDecoder)
            val density = context.resources.displayMetrics.density
            val adapter = MessagesAdapter(MediaLoader(manager, scope), NoCallbacks)
            val items = ThreadItems.build(rows, false, emptyMap(), null, null, { 0L }, { "" }).filterIsInstance<ThreadItem.Message>()
            adapter.submitList(items)
            val parent = FrameLayout(context)
            for ((position, item) in items.withIndex()) {
                val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(position))
                adapter.onBindViewHolder(holder, position)
                val root = holder.itemView
                val width = (360 * density).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)

                val row: ReactionRow = (holder as MessagesAdapter.MessageHolder).binding.reactions
                assertEquals(6, row.childCount)
                val chips = (0 until row.childCount).map { row.getChildAt(it) as TextView }
                val side = (8 * density).toInt()
                for (chip in chips) {
                    assertTrue("${chip.text}: ${chip.width}px wide", chip.width >= (48 * density).toInt())
                    assertTrue("${chip.text} cut off at the row's end", chip.right <= row.width && chip.left >= 0)
                    assertEquals("${chip.text}: side padding", side, chip.paddingLeft)
                    assertEquals("${chip.text}: side padding", side, chip.paddingRight)
                    assertTrue("label wider than its pill", chip.paint.measureText(chip.text.toString()) <= chip.width - 2 * side)
                }
                // More than one line, and the lines do not overlap.
                val lines = chips.groupBy { it.top }.toSortedMap()
                assertTrue("one line cannot hold six 48dp chips at 360dp", lines.size > 1)
                for (line in lines.values) {
                    val sorted = line.sortedBy { it.left }
                    sorted.zipWithNext { a, b -> assertTrue("overlap", a.right <= b.left) }
                    if (item.row.isOutgoing) {
                        // Under an outgoing bubble each line ends at the row's end (less the chip's end margin).
                        assertEquals(row.width - (4 * density).toInt(), sorted.last().right)
                    } else {
                        assertEquals(0, sorted.first().left)
                    }
                }
            }
        } finally {
            scope.cancel()
        }
    }

    // --- the release viewer and the debug build's provider -------------------------------

    @Test
    fun theViewerOpensAPictureSharedFromAnotherBuildsProvider() {
        // Another build of the app (the ".debug" one, seen from the release build) shares a picture:
        // its authority starts with this app's package name but is not this app's.
        val uri = Uri.parse("content://${app.packageName}.debug.fileprovider/relay-media/shared.png")
        val bytes = png()
        shadowOf(app.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        ShadowToast.reset()
        val share = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
        val controller = Robolectric.buildActivity(ViewerActivity::class.java, share).setup()
        try {
            val activity = controller.get()
            val deadline = System.currentTimeMillis() + 10_000
            while (!activity.findViewById<View>(R.id.stickerView).isShown) {
                if (System.currentTimeMillis() > deadline) fail("refused: ${ShadowToast.getTextOfLatestToast()}")
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
                Thread.sleep(20)
            }
            assertNotEquals(activity.getString(R.string.cannot_open), ShadowToast.getTextOfLatestToast())
        } finally {
            controller.pause().stop().destroy()
        }
        // This app's own provider is still refused (LinkFixesTest covers the rest).
        ShadowToast.reset()
        val own = Uri.parse("content://${app.packageName}.fileprovider/relay-media/shared.png")
        shadowOf(app.contentResolver).registerInputStreamSupplier(own) { ByteArrayInputStream(bytes) }
        val refused = Robolectric.buildActivity(ViewerActivity::class.java, Intent(Intent.ACTION_VIEW, own)).setup()
        try {
            settle()
            assertEquals(app.getString(R.string.cannot_open), ShadowToast.getTextOfLatestToast())
        } finally {
            refused.pause().stop().destroy()
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

    private fun textMessage(id: String, conversationId: String, ts: Long) = MessageDto(
        id = id, conversationId = conversationId, senderId = "p1", senderName = "Someone", timestampMs = ts,
        isOld = true, text = "message $id",
    )

    private fun latestAlert(): AlertDialog {
        val dialog: Dialog? = ShadowDialog.getLatestDialog()
        assertNotNull("no dialog", dialog)
        assertTrue("dialog not showing", dialog!!.isShowing)
        return dialog as AlertDialog
    }

    private fun seedConversation(id: String, name: String, lastTs: Long = 1_000, unread: Boolean = false) {
        env.repo.upsertConversation(
            ConversationDto(
                id = id,
                name = name,
                folder = "inbox",
                lastMessageTs = lastTs,
                unread = unread,
                participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = "p1", name = name)),
            )
        )
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
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
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            var ok = false
            onActivity { ok = condition(it) }
            if (ok) return
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Suppress("unused")
    private fun hasText(root: View, text: String): Boolean {
        if (root is TextView && root.text.toString() == text) return true
        if (root is ViewGroup) for (i in 0 until root.childCount) if (hasText(root.getChildAt(i), text)) return true
        return false
    }
}
