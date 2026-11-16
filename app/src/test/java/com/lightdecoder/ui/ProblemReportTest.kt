package com.lightdecoder.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.RelayLog
import com.lightdecoder.relay.RelayTestEnv
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The problem report: it shows what happened to a picture, and nothing of what was said or to whom. */
@RunWith(AndroidJUnit4::class)
class ProblemReportTest {

    private lateinit var env: RelayTestEnv

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
    fun aPictureThePhoneDroppedShowsUpInTheReportWithoutAnyContent() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        env.repo.upsertConversation(
            ConversationDto(
                id = "c1", name = "Alice Secretname", folder = "inbox", lastMessageTs = 1_000,
                participants = listOf(
                    ParticipantDto(id = "me", isMe = true),
                    ParticipantDto(id = "p1", name = "Alice Secretname", number = "+15557654321"),
                ),
            )
        )
        val picture = File(env.context.cacheDir, "pic.png").apply {
            outputStream().use { Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        manager.sendMedia("c1", Uri.fromFile(picture), "meet at the hidden cafe", null)
        env.eventually(manager, what = "both sends") { fake.sent.size == 2 }
        val pictureTmp = fake.sent[0].tmpId
        // The phone echoes the picture's send without its picture.
        fake.emitMessage(
            MessageDto(
                id = "real-1", conversationId = "c1", tmpId = pictureTmp, fromMe = true, direction = "out",
                status = "OUTGOING_SENDING", transport = "MMS", timestampMs = System.currentTimeMillis(),
            )
        )
        manager.awaitIdleForTesting()

        assertTrue(RelayLog.read(env.context).any { it.contains("THE PICTURE IS MISSING") })
        val report = ProblemReport.build(env.context)
        assertTrue(report.contains("LightDecoder problem report"))
        assertTrue("the queue and the log are in it", report.contains("Attachment queued: image/png"))
        assertTrue(report.contains("THE PICTURE IS MISSING"))
        assertTrue(report.contains("OUTGOING_SENDING"))
        for (secret in listOf("hidden cafe", "Secretname", "5557654321", "Alice")) {
            assertFalse("the report must not contain \"$secret\"", report.contains(secret))
        }
    }
}
