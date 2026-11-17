package com.lightdecoder.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.LocalState
import com.lightdecoder.relay.ContactDto
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.PartDto
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.RelayState
import com.lightdecoder.relay.RelayTestEnv
import com.lightdecoder.ui.chat.ConversationsActivity
import com.lightdecoder.ui.chat.MessagesAdapter
import com.lightdecoder.ui.chat.NewConversationActivity
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.ui.chat.ThreadItem
import com.lightdecoder.ui.relay.RelaySetupActivity
import com.lightdecoder.ui.relay.RelayStatusActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.time.Duration

/**
 * Every messaging screen (and the home screen) opens against the real relay
 * runtime with a fake bridge and a seeded archive, reaches RESUMED without
 * crashing, and shows what was seeded. The screens read the archive through
 * [ArchiveRepository.get], so the test archive is installed as the process one.
 */
@RunWith(AndroidJUnit4::class)
class ScreensSmokeTest {

    private lateinit var env: RelayTestEnv

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

    // --- link setup and status --------------------------------------------------------

    @Test
    fun setupStartsAtTheDisclosureWhenUnpaired() {
        env.manager()
        ActivityScenario.launch(RelaySetupActivity::class.java).use { scenario ->
            settle()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.onActivity { activity ->
                assertVisible(activity, R.id.stepIntro)
                assertGone(activity, R.id.stepSignIn)
                assertGone(activity, R.id.stepConfirm)
                // Continue waits for the user to tick that they understood.
                assertFalse(activity.findViewById<View>(R.id.introContinue).isEnabled)
            }
        }
    }

    @Test
    fun setupSaysAlreadyLinkedWhenPaired() {
        env.pairedManager()
        ActivityScenario.launch(RelaySetupActivity::class.java).use { scenario ->
            scenario.waitFor("the already-linked step") { it.findViewById<View>(R.id.stepDone).isShown }
            scenario.onActivity { activity ->
                assertEquals(
                    activity.getString(R.string.link_done_already_title),
                    activity.findViewById<TextView>(R.id.doneTitle).text.toString(),
                )
            }
        }
    }

    @Test
    fun setupSignsInAgainWhenGoogleRejectedTheCookiesBeforeARestart() {
        env.pairedManager()
        env.repo.updateHealth(status = "cookies_invalid")
        ActivityScenario.launch(RelaySetupActivity::class.java).use { scenario ->
            settle()
            scenario.onActivity { activity ->
                assertVisible(activity, R.id.stepIntro)
                assertGone(activity, R.id.stepDone)
            }
        }
    }

    @Test
    fun statusShowsTheControlsWhenPaired() {
        env.pairedManager()
        env.repo.updateHealth(status = "disconnected", account = "someone@example.com", phoneId = "phone-1")
        ActivityScenario.launch(RelayStatusActivity::class.java).use { scenario ->
            scenario.waitFor("the controls") { it.findViewById<View>(R.id.controls).isShown }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.waitFor("the account") {
                hasText(it.window.decorView, it.getString(R.string.link_status_account, "someone@example.com"))
            }
            scenario.onActivity { activity ->
                assertEquals(
                    activity.getString(R.string.link_phase_disconnected),
                    activity.findViewById<TextView>(R.id.stateTitle).text.toString(),
                )
                assertTrue("unpair offered", activity.findViewById<View>(R.id.unpair).isEnabled)
            }
        }
    }

    @Test
    fun statusStillOffersUnpairAfterThePairingWasLost() {
        val (manager, fake) = env.pairedManager()
        fake.emitState("logged_out")
        env.eventually(manager, what = "logged out") { manager.state.value.phase == RelayState.Phase.LOGGED_OUT }
        ActivityScenario.launch(RelayStatusActivity::class.java).use { scenario ->
            scenario.waitFor("unpair offered") {
                it.findViewById<View>(R.id.controls).isShown && it.findViewById<View>(R.id.unpair).isEnabled
            }
            scenario.onActivity { activity ->
                assertFalse("nothing to disconnect", activity.findViewById<View>(R.id.disconnect).isEnabled)
                assertVisible(activity, R.id.setUp)
            }
        }
    }

    // --- conversations ----------------------------------------------------------------

    @Test
    fun conversationsListsTheInboxFromTheArchive() {
        env.pairedManager()
        seedConversation("c1", "Alice", lastTs = 2_000)
        seedConversation("c2", "Bob", lastTs = 1_000)
        seedConversation("c3", "Archived Carol", lastTs = 3_000, folder = "archive")

        ActivityScenario.launch(ConversationsActivity::class.java).use { scenario ->
            scenario.waitFor("two inbox rows") { it.findViewById<RecyclerView>(R.id.list).adapter?.itemCount == 2 }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.waitFor("the names on screen") {
                val list = it.findViewById<RecyclerView>(R.id.list)
                hasText(list, "Alice") && hasText(list, "Bob") && !hasText(list, "Archived Carol")
            }
        }
    }

    @Test
    fun conversationsWithoutPairingOrArchiveGoesToSetup() {
        env.manager()
        ActivityScenario.launch(ConversationsActivity::class.java).use { scenario ->
            settle()
            scenario.onActivity { activity ->
                val next = shadowOf(activity).nextStartedActivity
                assertEquals(RelaySetupActivity::class.java.name, next?.component?.className)
            }
        }
    }

    // --- thread -----------------------------------------------------------------------

    @Test
    @Config(qualifiers = "w400dp-h1600dp") // tall enough for every bubble to be laid out
    fun threadShowsTextMediaAndUnsentMessages() {
        env.pairedManager()
        seedConversation("c1", "Alice", lastTs = 5_000)
        env.repo.upsertMessage(
            MessageDto(id = "m1", conversationId = "c1", senderId = "p1", senderName = "Alice", timestampMs = 1_000, text = "Hello there", isOld = true)
        )
        env.repo.upsertMessage(
            MessageDto(
                id = "m2", conversationId = "c1", fromMe = true, direction = "out", status = "OUTGOING_DELIVERED",
                timestampMs = 2_000, text = "Hi Alice", isOld = true,
            )
        )
        env.repo.upsertMessage(
            MessageDto(
                id = "m3", conversationId = "c1", senderId = "p1", senderName = "Alice", timestampMs = 3_000, isOld = true,
                parts = listOf(PartDto(partId = "0", kind = "media", mime = "image/png", inlineData = png(), size = 1)),
            )
        )
        env.repo.insertLocalOutgoing("tmp-pending", "c1", "On its way", null, null, null, null, 4_000)
        env.repo.insertLocalOutgoing("tmp-failed", "c1", "Did not go", null, null, null, null, 5_000)
        env.repo.setLocalState("tmp-failed", LocalState.FAILED, "send_failed")

        // The intent notifications use, so its extras are what the thread reads.
        val intent = RelayIntents.thread(env.context, "c1").setFlags(0)
        ActivityScenario.launch<ThreadActivity>(intent).use { scenario ->
            scenario.waitFor("every message in the list") { activity ->
                val adapter = activity.findViewById<RecyclerView>(R.id.messages).adapter as MessagesAdapter
                adapter.currentList.filterIsInstance<ThreadItem.Message>().map { it.row.id } ==
                    listOf("m1", "m2", "m3", "tmp:tmp-pending", "tmp:tmp-failed")
            }
            scenario.waitFor("every message on screen") { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.messages)
                listOf("Hello there", "Hi Alice", "On its way", "Did not go").all { hasText(list, it) }
            }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.waitFor("the picture decoded") { activity ->
                findAll(activity.findViewById(R.id.messages)) { it.id == R.id.mediaImage }
                    .any { (it as ImageView).drawable is BitmapDrawable }
            }
            scenario.onActivity { activity ->
                assertEquals("Alice", activity.supportActionBar?.title?.toString())
                val failedNotes = findAll(activity.findViewById(R.id.messages)) { it.id == R.id.failedText && it.isShown }
                assertEquals("one failed send", 1, failedNotes.size)
                assertTrue("composer usable while paired", activity.findViewById<View>(R.id.input).isEnabled)
                // While on screen, its messages are not notified.
                assertEquals("c1", MessageNotifier.visibleConversationId)
            }
            // Opening it marks it read.
            scenario.waitFor("marked read") { env.repo.conversation("c1")?.unread == false }
            scenario.moveToState(Lifecycle.State.STARTED)
            assertNull(MessageNotifier.visibleConversationId)
        }
    }

    @Test
    fun threadWithoutAConversationIdCloses() {
        env.manager()
        ActivityScenario.launch(ThreadActivity::class.java).use { scenario ->
            settle()
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
    }

    // --- new conversation ---------------------------------------------------------------

    @Test
    fun newConversationListsStoredContactsWhileOffline() {
        env.pairedManager()
        env.repo.upsertContacts(
            listOf(
                ContactDto(id = "k1", name = "Dora", number = "+15550101", formattedNumber = "(555) 010-1"),
                ContactDto(id = "k2", name = "Eve", number = "+15550102", formattedNumber = "(555) 010-2"),
            )
        )
        ActivityScenario.launch(NewConversationActivity::class.java).use { scenario ->
            scenario.waitFor("both contacts") { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.contacts)
                list.adapter?.itemCount == 2 && hasText(list, "Dora") && hasText(list, "Eve")
            }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            // The refresh needs the phone; offline, the runtime falls back to the stored list,
            // and the hint says that is what is shown.
            scenario.waitFor("the refresh to finish") { !it.findViewById<View>(R.id.progress).isShown }
            scenario.onActivity { assertTrue(it.findViewById<View>(R.id.offlineHint).isShown) }
        }
    }

    // --- home -------------------------------------------------------------------------

    @Test
    fun inboxStillOpensWithTheLinkCard() {
        env.manager()
        ActivityScenario.launch(InboxActivity::class.java).use { scenario ->
            scenario.waitFor("the link card") { activity ->
                activity.findViewById<TextView>(R.id.relayBody).text.toString() ==
                    activity.getString(R.string.link_card_not_set_up)
            }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.onActivity { activity ->
                assertEquals(
                    activity.getString(R.string.link_card_set_up),
                    activity.findViewById<TextView>(R.id.relayAction).text.toString(),
                )
            }
        }
    }

    // --- helpers ----------------------------------------------------------------------

    private fun seedConversation(id: String, name: String, lastTs: Long, folder: String = "inbox") {
        env.repo.upsertConversation(
            ConversationDto(
                id = id,
                name = name,
                folder = folder,
                unread = true,
                lastMessageTs = lastTs,
                snippet = "snippet of $name",
                participants = listOf(
                    ParticipantDto(id = "me", isMe = true),
                    ParticipantDto(id = "p1", name = name, number = "+1555000$lastTs"),
                ),
            )
        )
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** Runs the main looper (advancing its clock, for debounces) and gives the IO threads a moment. */
    private fun settle(rounds: Int = 5) {
        repeat(rounds) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            Thread.sleep(10)
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

    private fun assertVisible(activity: Activity, id: Int) =
        assertEquals(View.VISIBLE, activity.findViewById<View>(id).visibility)

    private fun assertGone(activity: Activity, id: Int) =
        assertEquals(View.GONE, activity.findViewById<View>(id).visibility)

    private fun hasText(root: View, text: String): Boolean =
        findAll(root) { it is TextView && it.isShown && it.text.toString() == text }.isNotEmpty()

    private fun findAll(root: View, match: (View) -> Boolean): List<View> {
        val found = ArrayList<View>()
        fun walk(view: View) {
            if (match(view)) found += view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
        return found
    }
}
