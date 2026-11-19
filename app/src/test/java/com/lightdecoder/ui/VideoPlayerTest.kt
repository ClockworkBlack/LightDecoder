package com.lightdecoder.ui

import android.app.Application
import android.os.Looper
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.PartDto
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.RelayTestEnv
import com.lightdecoder.ui.chat.MessagesAdapter
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.ui.chat.ThreadItem
import com.lightdecoder.ui.chat.VideoPlayerActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** A video in a conversation plays inside LightDecoder, from its own file. */
@RunWith(AndroidJUnit4::class)
class VideoPlayerTest {

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

    @Test
    fun tappingAVideoPlaysItHereNotInAnotherApp() {
        env.pairedManager()
        env.repo.upsertConversation(
            ConversationDto(
                id = "c1", name = "Alice", folder = "inbox", lastMessageTs = 1_000,
                participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = "p1", name = "Alice")),
            )
        )
        env.repo.upsertMessage(
            MessageDto(
                id = "v1", conversationId = "c1", senderId = "p1", senderName = "Alice", timestampMs = 1_000, isOld = true,
                parts = listOf(PartDto(partId = "0", kind = "media", mediaId = "media-v1", mime = "video/quicktime", size = 100)),
            )
        )
        val file = env.repo.mediaFileFor("media-v1", "mov").apply { parentFile?.mkdirs(); writeBytes(ByteArray(64)) }
        env.repo.setMediaLocalPath("v1", "0", file.path, thumbnail = false)
        ActivityScenario.launch<ThreadActivity>(RelayIntents.thread(app, "c1").setFlags(0)).use { scenario ->
            fun row(a: ThreadActivity) = (a.findViewById<RecyclerView>(R.id.messages).adapter as MessagesAdapter).currentList
                .filterIsInstance<ThreadItem.Message>().firstOrNull { it.row.id == "v1" }?.row
            val deadline = System.currentTimeMillis() + 10_000
            while (true) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                var found = false
                scenario.onActivity { found = row(it) != null }
                if (found) break
                if (System.currentTimeMillis() > deadline) fail("the video message")
            }
            scenario.onActivity { a -> a.onMediaTap(row(a)!!, row(a)!!.media.single()) }
            val until = System.currentTimeMillis() + 5_000
            var started: android.content.Intent? = null
            while (started == null && System.currentTimeMillis() < until) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                started = shadowOf(app).nextStartedActivity
            }
            assertEquals(VideoPlayerActivity::class.java.name, started?.component?.className)
            assertEquals(file.path, started?.getStringExtra("path"))
        }
    }
}
