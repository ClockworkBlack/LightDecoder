package com.lightdecoder.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.speech.RecognizerIntent
import android.view.Menu
import android.view.View
import android.widget.EditText
import androidx.appcompat.widget.Toolbar
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.MediaRow
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.RelayTestEnv
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.ui.voice.Dictation
import com.lightdecoder.ui.voice.MessageReader
import com.lightdecoder.ui.voice.ReadAloudText
import com.lightdecoder.ui.voice.VoicePrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.fakes.RoboMenuItem
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.time.Duration

/**
 * Voice on the thread screen: dictation into the composer through the
 * system's recogniser, and reading messages aloud (on request from the
 * long-press sheet, and new incoming ones while the thread is open).
 */
@RunWith(AndroidJUnit4::class)
class VoiceTest {

    private lateinit var env: RelayTestEnv
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val reader = FakeReader()

    @Before
    fun setUp() {
        env = RelayTestEnv()
        ArchiveRepository.installForTesting(env.repo)
        MessageReader.factory = { _, listener -> reader.also { it.listener = listener } }
        ThreadActivity.readerUnavailableShown = false
        VoicePrefs.setReadNewAloud(app, false)
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        MessageReader.factory = null
        ThreadActivity.readerUnavailableShown = false
        VoicePrefs.setReadNewAloud(app, false)
        MessageNotifier.visibleConversationId = null
        env.tearDown()
        ArchiveRepository.resetForTesting()
    }

    // --- dictation --------------------------------------------------------------------

    @Test
    fun dictatedTextGoesInAtTheCursorAndIsNotSent() {
        installRecogniser()
        val (_, fake) = env.pairedManager()
        seedConversation("c1", "Alice")
        val controller = open("c1")
        try {
            val activity = controller.get()
            val mic = activity.findViewById<View>(R.id.voiceInput)
            assertEquals(View.VISIBLE, mic.visibility)
            assertTrue(mic.isEnabled)
            assertEquals(activity.getString(R.string.chat_voice_input), mic.contentDescription)
            val density = activity.resources.displayMetrics.density
            assertTrue("48dp target", mic.width >= (48 * density).toInt() - 1 && mic.height >= (48 * density).toInt() - 1)

            val input = activity.findViewById<EditText>(R.id.input)
            input.setText("Hello there")
            input.setSelection(5)
            mic.performClick()
            val started = shadowOf(activity).nextStartedActivityForResult
            assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, started.intent.action)
            assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM, started.intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
            assertTrue(started.intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))

            val data = Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf("big world", "pig whirled"))
            shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, data)
            settle()
            assertEquals("Hello big world there", input.text.toString())
            assertEquals("Hello big world".length, input.selectionStart)

            // A cancelled recogniser changes nothing.
            mic.performClick()
            val again = shadowOf(activity).nextStartedActivityForResult
            shadowOf(activity).receiveResult(again.intent, Activity.RESULT_CANCELED, null)
            settle()
            assertEquals("Hello big world there", input.text.toString())

            // The user reviews it: nothing went out.
            assertTrue(fake.sent.isEmpty())
            assertTrue(env.repo.messages("c1", limit = 10).isEmpty())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun dictationIsHiddenWithoutARecogniser() {
        env.pairedManager()
        seedConversation("c1", "Alice")
        val controller = open("c1")
        try {
            assertEquals(View.GONE, controller.get().findViewById<View>(R.id.voiceInput).visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun dictationIsDisabledWithTheComposer() {
        installRecogniser()
        env.manager() // not paired: the composer is disabled
        seedConversation("c1", "Alice")
        val controller = open("c1")
        try {
            val activity = controller.get()
            assertFalse(activity.findViewById<View>(R.id.input).isEnabled)
            val mic = activity.findViewById<View>(R.id.voiceInput)
            assertEquals(View.VISIBLE, mic.visibility)
            assertFalse(mic.isEnabled)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun dictationPiecesAreSpacedFromTheWordsAround() {
        assertEquals("hi", Dictation.piece("", 0, 0, "hi"))
        assertEquals(" hi", Dictation.piece("well", 4, 4, "hi"))
        assertEquals("hi ", Dictation.piece("there", 0, 0, "hi"))
        assertEquals("hi", Dictation.piece("a  b", 2, 2, "hi"))
        assertEquals(" hi ", Dictation.piece("aXb", 1, 2, "hi"))
    }

    // --- read aloud from the sheet ----------------------------------------------------

    @Test
    fun theSheetsReadAloudSaysWhoSaidWhat() {
        env.pairedManager()
        seedConversation("c1", "Alice")
        env.repo.upsertMessage(incoming("a1", "c1", "See you at eight", 1_000))
        env.repo.upsertMessage(MessageDto(id = "m1", conversationId = "c1", fromMe = true, direction = "out", timestampMs = 2_000, text = "On my way"))
        val controller = open("c1")
        try {
            val activity = controller.get()
            val rows = env.repo.messages("c1", limit = 10)
            val alices = rows.single { it.id == "a1" }
            val mine = rows.single { it.id == "m1" }

            readAloudFromSheet(activity, alices)
            assertEquals(listOf("Alice said: See you at eight" to true), reader.spoken)

            reader.spoken.clear()
            readAloudFromSheet(activity, mine)
            assertEquals(listOf("On my way" to true), reader.spoken)

            reader.spoken.clear()
            readAloudFromSheet(activity, alices.copy(text = "", media = listOf(media("image/jpeg"))))
            assertEquals(listOf("Alice said: a picture" to true), reader.spoken)

            reader.spoken.clear()
            readAloudFromSheet(activity, mine.copy(text = "", media = listOf(media("application/pdf"))))
            assertEquals(listOf("an attachment" to true), reader.spoken)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun withoutTextToSpeechTheOptionsAreHiddenAndItSaysSoOnce() {
        reader.available = false
        VoicePrefs.setReadNewAloud(app, true)
        env.pairedManager()
        seedConversation("c1", "Alice")
        env.repo.upsertMessage(incoming("a1", "c1", "Hi", 1_000))
        val first = open("c1")
        try {
            val activity = first.get()
            assertEquals(1, ShadowToast.shownToastCount())
            assertEquals(activity.getString(R.string.chat_read_aloud_unavailable), ShadowToast.getTextOfLatestToast())
            val menu = preparedMenu(activity)
            assertFalse(menu.findItem(R.id.action_read_new_aloud).isVisible)
            assertFalse(menu.findItem(R.id.action_stop_reading).isVisible)
            activity.onMessageLongPress(env.repo.messages("c1", limit = 10).single())
            val sheet = ShadowDialog.getLatestDialog() as BottomSheetDialog
            assertEquals(View.GONE, sheet.findViewById<View>(R.id.actionReadAloud)!!.visibility)
            sheet.dismiss()
        } finally {
            first.pause().stop().destroy()
        }
        val second = open("c1")
        try {
            assertEquals("said once", 1, ShadowToast.shownToastCount())
        } finally {
            second.pause().stop().destroy()
        }
    }

    // --- reading new messages aloud ---------------------------------------------------

    @Test
    fun onlyNewIncomingMessagesAreReadOut() {
        env.pairedManager()
        seedConversation("c1", "Alice")
        env.repo.upsertMessage(incoming("a1", "c1", "Already here", 1_000))
        val controller = open("c1")
        try {
            val activity = controller.get()
            var menu = preparedMenu(activity)
            val toggle = menu.findItem(R.id.action_read_new_aloud)
            assertTrue(toggle.isVisible)
            assertTrue(toggle.isCheckable)
            assertFalse(toggle.isChecked)
            activity.onOptionsItemSelected(toggle)
            assertTrue(VoicePrefs.readNewAloud(app))
            assertTrue(preparedMenu(activity).findItem(R.id.action_read_new_aloud).isChecked)
            settle()
            assertTrue("nothing already there is read: ${reader.spoken}", reader.spoken.isEmpty())

            // A reply arrives, the user sends one, and older history is paged in.
            val now = System.currentTimeMillis()
            env.repo.upsertMessage(incoming("a2", "c1", "Are you there?", now))
            env.repo.upsertMessage(MessageDto(id = "m1", conversationId = "c1", fromMe = true, direction = "out", timestampMs = now + 1, text = "Yes"))
            env.repo.upsertMessage(incoming("a0", "c1", "From long ago", 500))
            waitUntil("the reply is read") { reader.spoken.isNotEmpty() }
            settle()
            assertEquals(listOf("Alice said: Are you there?" to false), reader.spoken)

            // While it speaks, Stop reading is offered and stops it.
            menu = preparedMenu(activity)
            assertTrue(menu.findItem(R.id.action_stop_reading).isVisible)
            activity.onOptionsItemSelected(RoboMenuItem(R.id.action_stop_reading))
            assertFalse(reader.isSpeaking)
            assertFalse(preparedMenu(activity).findItem(R.id.action_stop_reading).isVisible)

            // Re-reads of the same message say nothing again.
            env.repo.upsertMessage(incoming("a2", "c1", "Are you there?", now))
            settle()
            assertEquals(1, reader.spoken.size)

            // Turned off: new messages are not read.
            activity.onOptionsItemSelected(preparedMenu(activity).findItem(R.id.action_read_new_aloud))
            assertFalse(VoicePrefs.readNewAloud(app))
            env.repo.upsertMessage(incoming("a3", "c1", "Hello?", now + 2))
            settle()
            assertEquals(1, reader.spoken.size)

            // Leaving the thread stops reading.
            val stopsBefore = reader.stops
            controller.pause().stop()
            assertTrue(reader.stops > stopsBefore)
            controller.destroy()
            assertTrue(reader.shutDown)
        } finally {
            if (!controller.get().isDestroyed) controller.pause().stop().destroy()
        }
    }

    @Test
    fun readAloudTextNeverHasNothingToSayForEmptyMessages() {
        val row = MessageRow(
            id = "x", conversationId = "c1", tmpId = "", senderId = "p1", senderName = "", isOutgoing = false,
            timestampMs = 0, direction = "in", status = "", statusCode = 0, errorText = "", transport = "",
            text = "", subject = "", replyToId = "", reactions = emptyList(), localState = "", media = emptyList(), updatedAt = 0,
        )
        assertNull(ReadAloudText.sentence(app, row))
        assertEquals("Unknown said: Hi", ReadAloudText.sentence(app, row.copy(text = "  Hi ")))
    }

    // --- helpers ----------------------------------------------------------------------

    private class FakeReader : MessageReader {
        var listener: MessageReader.Listener? = null
        var available = true
        val spoken = mutableListOf<Pair<String, Boolean>>()
        var stops = 0
        var shutDown = false
        private var speaking = false

        override val isAvailable get() = available
        override val isSpeaking get() = speaking

        override fun speak(text: String, interrupt: Boolean) {
            spoken += text to interrupt
            setSpeaking(true)
        }

        override fun stop() {
            stops++
            setSpeaking(false)
        }

        override fun shutdown() {
            shutDown = true
            setSpeaking(false)
        }

        private fun setSpeaking(on: Boolean) {
            if (speaking == on) return
            speaking = on
            listener?.onSpeakingChanged(on)
        }
    }

    private fun readAloudFromSheet(activity: ThreadActivity, row: MessageRow) {
        activity.onMessageLongPress(row)
        val sheet = ShadowDialog.getLatestDialog() as BottomSheetDialog
        val item = sheet.findViewById<View>(R.id.actionReadAloud)!!
        assertEquals(View.VISIBLE, item.visibility)
        item.performClick()
        assertFalse(sheet.isShowing)
    }

    private fun preparedMenu(activity: ThreadActivity): Menu {
        // The toolbar is the action bar: its menu is the screen's options menu.
        val menu = activity.findViewById<Toolbar>(R.id.toolbar).menu
        activity.onPrepareOptionsMenu(menu)
        return menu
    }

    private fun installRecogniser() {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "org.example.speech"
                name = "org.example.speech.Recognise"
            }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), info)
    }

    private fun incoming(id: String, conversationId: String, text: String, ts: Long) = MessageDto(
        id = id, conversationId = conversationId, senderId = "p1", senderName = "Alice", timestampMs = ts, text = text,
    )

    private fun media(mime: String) = MediaRow(
        messageId = "x", partId = "p", mediaId = "", key = ByteArray(0), thumbMediaId = "", thumbKey = ByteArray(0),
        mime = mime, fileName = "", size = 0, width = 0, height = 0, inlineData = ByteArray(0), needsFullSize = false,
        localPath = "", thumbPath = "", fullSizeRequested = false, stickerChecked = false,
    )

    private fun seedConversation(id: String, name: String) {
        env.repo.upsertConversation(
            ConversationDto(
                id = id, name = name, folder = "inbox", lastMessageTs = 1_000,
                participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = "p1", name = name)),
            )
        )
    }

    private fun open(conversationId: String): ActivityController<ThreadActivity> {
        val controller = Robolectric.buildActivity(ThreadActivity::class.java, RelayIntents.thread(app, conversationId).setFlags(0)).setup()
        settle()
        return controller
    }

    private fun settle(rounds: Int = 5) {
        repeat(rounds) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            Thread.sleep(10)
        }
    }

    private fun waitUntil(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(20)
        }
    }
}
