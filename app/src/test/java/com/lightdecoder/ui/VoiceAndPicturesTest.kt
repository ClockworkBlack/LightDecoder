package com.lightdecoder.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.PartDto
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.RelayTestEnv
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.ui.chat.ThreadItem
import com.lightdecoder.ui.chat.ThreadItems
import com.lightdecoder.ui.chat.VoicePlayer
import com.lightdecoder.ui.chat.VoiceRecorder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration

/**
 * Google Messages conversations: several pictures picked at once go out
 * together and show as one stack; voice messages are recorded, sent as
 * audio, and played inside their bubble.
 */
@RunWith(AndroidJUnit4::class)
class VoiceAndPicturesTest {

    private lateinit var env: RelayTestEnv
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val defaultRecorder = VoiceRecorder.factory
    private val recorder = FakeRecorder()

    @Before
    fun setUp() {
        env = RelayTestEnv()
        ArchiveRepository.installForTesting(env.repo)
        VoiceRecorder.factory = { recorder }
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_MICROPHONE, true)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    @After
    fun tearDown() {
        VoiceRecorder.factory = defaultRecorder
        ShadowMediaPlayer.resetStaticState()
        env.tearDown()
        ArchiveRepository.resetForTesting()
    }

    // --- several pictures ------------------------------------------------------------------

    @Test
    fun severalPicturesPickedAtOnceGoOutTogetherInOrderWithTheTextLast() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        manager.awaitIdleForTesting()
        seedConversation()
        val picked = listOf(Color.RED, Color.GREEN, Color.BLUE).mapIndexed { i, color ->
            Uri.fromFile(File(app.cacheDir, "picked-$i.png").apply { writeBytes(png(color)) })
        }
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(app, "c1").setFlags(0)).use { scenario ->
            settle()
            scenario.onActivity { activity ->
                activity.setAttachments(picked)
                activity.findViewById<TextView>(R.id.input).text = "our trip"
                assertEquals(
                    activity.resources.getQuantityString(R.plurals.chat_attachments, 3, 3),
                    activity.findViewById<TextView>(R.id.attachmentName).text.toString(),
                )
            }
            scenario.waitFor("send to be possible") { it.findViewById<View>(R.id.send).isEnabled }
            scenario.onActivity { it.findViewById<View>(R.id.send).performClick() }
            env.eventually(manager, what = "three pictures and the text to go out") { fake.sent.size == 4 }
            assertEquals("in the order picked", listOf("picked-0.png", "picked-1.png", "picked-2.png"), fake.sent.take(3).map { it.fileName })
            assertTrue("the pictures go alone", fake.sent.take(3).all { it.text.isEmpty() })
            assertEquals("the text after the last picture", "our trip", fake.sent[3].text)
            scenario.onActivity { activity ->
                assertFalse("the attachment bar is cleared", activity.findViewById<View>(R.id.attachmentBar).isShown)
            }
        }
    }

    @Test
    fun picturesSentTogetherAreDrawnAsOneStack() {
        fun picture(id: String, ts: Long, from: String = "p1") = row(id, ts, from, media = true)
        val rows = listOf(
            picture("a", 10_000), picture("b", 12_000), picture("c", 14_000), // sent together
            row("t", 15_000, "p1", text = "and this"), // text breaks the stack
            picture("d", 16_000), picture("e", 16_000 + ThreadItems.STACK_GAP_MS + 1), // too far apart
            picture("f", 400_000, from = "p2"), picture("g", 401_000), // someone else's
        )
        val items = ThreadItems.build(rows, isGroup = true, quotes = emptyMap(), header = null, highlightId = null,
            dayStart = { 0L }, dayLabel = { "Today" })
        val stacked = items.filterIsInstance<ThreadItem.Message>().associate { it.row.id to it.stackedWithNext }
        assertEquals(
            mapOf("a" to true, "b" to true, "c" to false, "t" to false, "d" to false, "e" to false, "f" to false, "g" to false),
            stacked,
        )
    }

    // --- recording and sending voice messages ---------------------------------------------

    @Test
    fun aRecordedVoiceMessageIsSentAsAudio() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        manager.awaitIdleForTesting()
        seedConversation()
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(app, "c1").setFlags(0)).use { scenario ->
            settle()
            scenario.waitFor("the record button") { it.findViewById<View>(R.id.voiceMessage).isShown && it.findViewById<View>(R.id.voiceMessage).isEnabled }
            scenario.onActivity { it.findViewById<View>(R.id.voiceMessage).performClick() }
            scenario.onActivity { activity ->
                assertTrue("recording", recorder.recording)
                assertTrue(activity.findViewById<View>(R.id.recordingBar).isShown)
                assertFalse("the composer makes way", activity.findViewById<View>(R.id.composer).isShown)
            }
            // Three seconds later the user taps send.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            scenario.onActivity { activity ->
                assertEquals(
                    activity.getString(R.string.chat_voice_recording, "0:03"),
                    activity.findViewById<TextView>(R.id.recordingTime).text.toString(),
                )
                activity.findViewById<View>(R.id.recordingSend).performClick()
            }
            env.eventually(manager, what = "the voice message to go out") { fake.sent.size == 1 }
            val sent = fake.sent.single()
            assertEquals("audio/mp4", sent.mime)
            assertTrue(sent.fileName, sent.fileName.endsWith(".m4a"))
            assertTrue("the recording itself", sent.media!!.contentEquals(FakeRecorder.AUDIO))
            assertEquals("", sent.text)
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<View>(R.id.recordingBar).isShown)
                assertTrue("the composer is back", activity.findViewById<View>(R.id.composer).isShown)
            }
        }
    }

    @Test
    fun aRecordingTooShortOrThrownAwayIsNotSent() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        manager.awaitIdleForTesting()
        seedConversation()
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(app, "c1").setFlags(0)).use { scenario ->
            settle()
            scenario.waitFor("the record button") { it.findViewById<View>(R.id.voiceMessage).isEnabled }
            // A slip of the finger: send at once.
            scenario.onActivity {
                it.findViewById<View>(R.id.voiceMessage).performClick()
                it.findViewById<View>(R.id.recordingSend).performClick()
            }
            assertEquals("thrown away", 1, recorder.cancelled)
            // Recorded, then deleted.
            scenario.onActivity { it.findViewById<View>(R.id.voiceMessage).performClick() }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            scenario.onActivity { it.findViewById<View>(R.id.recordingCancel).performClick() }
            assertEquals(2, recorder.cancelled)
            assertFalse("its file is gone", recorder.lastFile!!.exists())
            settle()
            manager.awaitIdleForTesting()
            assertTrue("nothing went out", fake.sent.isEmpty())
            scenario.onActivity { assertTrue(it.findViewById<View>(R.id.composer).isShown) }
        }
    }

    @Test
    fun withoutAMicrophoneThereIsNoRecordButton() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_MICROPHONE, false)
        env.pairedManager()
        seedConversation()
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(app, "c1").setFlags(0)).use { scenario ->
            settle()
            scenario.onActivity { assertFalse(it.findViewById<View>(R.id.voiceMessage).isShown) }
        }
    }

    // --- playing voice messages -----------------------------------------------------------

    @Test
    fun aVoiceMessagePlaysAndPausesInsideItsBubble() {
        env.pairedManager()
        seedConversation()
        val file = seedVoiceMessage("v1", 1_000)
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(file.path), ShadowMediaPlayer.MediaInfo(65_000, 0))
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(app, "c1").setFlags(0)).use { scenario ->
            scenario.waitFor("the voice message") { audioRow(it) != null }
            scenario.onActivity { activity ->
                val row = audioRow(activity)!!
                assertEquals(activity.getString(R.string.chat_voice_play), row.findViewById<View>(R.id.audioPlay).contentDescription)
                assertFalse("only a file row for other files", activity.findViewById<View>(R.id.mediaFile).isShown)
                row.findViewById<View>(R.id.audioPlay).performClick()
            }
            scenario.waitFor("it to play") { audioRow(it)!!.findViewById<View>(R.id.audioPlay).contentDescription == it.getString(R.string.chat_voice_pause) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            scenario.onActivity { activity ->
                val row = audioRow(activity)!!
                assertTrue("its bar moves", row.findViewById<android.widget.SeekBar>(R.id.audioProgress).progress > 0)
                assertTrue("its position", row.findViewById<TextView>(R.id.audioTime).text.toString().startsWith("0:0"))
                row.findViewById<View>(R.id.audioPlay).performClick()
            }
            scenario.onActivity { activity ->
                val row = audioRow(activity)!!
                assertEquals("paused", activity.getString(R.string.chat_voice_play), row.findViewById<View>(R.id.audioPlay).contentDescription)
            }
        }
    }

    @Test
    fun clockReadsMinutesAndSeconds() {
        assertEquals("0:00", VoicePlayer.clock(0))
        assertEquals("0:03", VoicePlayer.clock(2_600))
        assertEquals("1:05", VoicePlayer.clock(65_000))
        assertEquals("10:00", VoicePlayer.clock(600_000))
    }

    // --- helpers ---------------------------------------------------------------------------

    /** Records nothing: the "recording" is a few known bytes written at the start. */
    private class FakeRecorder : VoiceRecorder {
        var recording = false
        var cancelled = 0
        var lastFile: File? = null
        override var onMaxDuration: (() -> Unit)? = null

        override fun start(file: File, maxDurationMs: Int): Boolean {
            file.parentFile?.mkdirs()
            file.writeBytes(AUDIO)
            lastFile = file
            recording = true
            return true
        }

        override fun stop(): Boolean {
            recording = false
            return lastFile?.isFile == true
        }

        override fun cancel() {
            recording = false
            cancelled++
            lastFile?.delete()
        }

        companion object {
            val AUDIO = byteArrayOf(0, 0, 0, 0x18) + "ftypM4A voice".toByteArray()
        }
    }

    private fun row(id: String, ts: Long, from: String, media: Boolean = false, text: String = "") = MessageRow(
        id = id, conversationId = "c1", tmpId = "", senderId = from, senderName = "", isOutgoing = false, timestampMs = ts,
        direction = "in", status = "", statusCode = 0, errorText = "", transport = "", text = text, subject = "",
        replyToId = "", reactions = emptyList(), localState = "", updatedAt = 0,
        media = if (media) listOf(
            com.lightdecoder.archive.MediaRow(
                messageId = id, partId = "0", mediaId = "m-$id", key = ByteArray(0), thumbMediaId = "", thumbKey = ByteArray(0),
                mime = "image/jpeg", fileName = "", size = 1, width = 10, height = 10, inlineData = ByteArray(0),
                needsFullSize = false, localPath = "", thumbPath = "", fullSizeRequested = false, stickerChecked = false,
            )
        ) else emptyList(),
    )

    private fun seedConversation() {
        env.repo.upsertConversation(
            ConversationDto(
                id = "c1", name = "Alice", folder = "inbox", lastMessageTs = 1_000, type = "RCS",
                participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = "p1", name = "Alice")),
            )
        )
    }

    /** An incoming voice message whose audio is on this phone already. */
    private fun seedVoiceMessage(id: String, ts: Long): File {
        val mediaId = "media-$id"
        env.repo.upsertMessage(
            MessageDto(
                id = id, conversationId = "c1", senderId = "p1", senderName = "Alice", direction = "in",
                timestampMs = ts, isOld = true,
                parts = listOf(PartDto(partId = "0", kind = "media", mediaId = mediaId, mime = "audio/mp4", fileName = "voice.m4a", size = 100)),
            )
        )
        val file = env.repo.mediaFileFor(mediaId, "m4a").apply { parentFile?.mkdirs(); writeBytes(FakeRecorder.AUDIO) }
        env.repo.setMediaLocalPath(id, "0", file.path, thumbnail = false)
        return file
    }

    private fun audioRow(activity: Activity): View? {
        val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.messages)
        for (i in 0 until list.childCount) {
            list.getChildAt(i).findViewById<View>(R.id.mediaAudio)?.takeIf { it.visibility == View.VISIBLE }?.let { return it }
        }
        return null
    }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

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
}
