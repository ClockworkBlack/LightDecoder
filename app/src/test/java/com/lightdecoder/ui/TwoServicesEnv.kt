package com.lightdecoder.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.ChatService
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.RelayTestEnv
import com.lightdecoder.ui.chat.ChatVisibility
import com.lightdecoder.ui.chat.FakeChatBackend
import com.lightdecoder.ui.chat.ScreenBackends
import com.lightdecoder.wa.FakeWaBridge
import com.lightdecoder.wa.WaBridges
import org.junit.Assert.fail
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Both services for a screen test: Google Messages as every other screen
 * test has it (the real runtime over a fake bridge and a test archive,
 * [env]), and WhatsApp as a test archive ([waRepo], installed as the process
 * one) behind a [FakeChatBackend] ([wa]) the screens are pointed at.
 * [useRealWhatsApp] takes the fake away again, for the app's own (unlinked)
 * WhatsApp manager.
 */
internal class TwoServicesEnv {
    val env = RelayTestEnv()
    val app: Application = ApplicationProvider.getApplicationContext()
    private val waDb: ArchiveDb
    val waRepo: ArchiveRepository
    val wa: FakeChatBackend

    init {
        ArchiveRepository.installForTesting(env.repo)
        app.deleteDatabase(WA_DB)
        waDb = ArchiveDb(app, WA_DB)
        waRepo = ArchiveRepository.forTesting(waDb, ChatService.WHATSAPP)
        ArchiveRepository.installForTesting(waRepo)
        wa = FakeChatBackend(ChatService.WHATSAPP, waRepo)
        ScreenBackends.forTesting = { service -> if (service == ChatService.WHATSAPP) wa else null }
        // The app's own WhatsApp manager, where a test uses it, never loads the Go library.
        WaBridges.factory = { dir, sink -> FakeWaBridge(dir, sink) }
        // Which tab the list showed last is remembered across launches: every test starts with none.
        app.getSharedPreferences("chat_ui", Context.MODE_PRIVATE).edit().clear().commit()
        ChatVisibility.resetForTesting()
    }

    fun useRealWhatsApp() {
        ScreenBackends.forTesting = null
    }

    fun tearDown() {
        ScreenBackends.forTesting = null
        WaBridges.factory = null
        ChatVisibility.resetForTesting()
        for (service in ChatService.entries) MessageNotifier.setVisibleConversationId(service, null)
        env.tearDown()
        ArchiveRepository.resetForTesting()
        waDb.close()
    }

    // --- seeding --------------------------------------------------------------------------

    fun repoOf(service: ChatService): ArchiveRepository = if (service == ChatService.WHATSAPP) waRepo else env.repo

    /** A one-to-one conversation with [name]; in WhatsApp the other person's id is the conversation's, as there. */
    fun seedConversation(
        service: ChatService,
        id: String,
        name: String,
        folder: String = "inbox",
        unread: Boolean = false,
        lastTs: Long = 1_000,
        number: String = "+15550100",
    ) {
        val otherId = if (service == ChatService.WHATSAPP) id else "p1"
        repoOf(service).upsertConversation(
            ConversationDto(
                id = id, name = name, folder = folder, unread = unread, lastMessageTs = lastTs,
                participants = listOf(
                    ParticipantDto(id = "me", isMe = true),
                    ParticipantDto(id = otherId, name = name, number = number, formattedNumber = number),
                ),
            )
        )
    }

    fun seedIncoming(service: ChatService, messageId: String, conversationId: String, text: String, ts: Long = 1_000, old: Boolean = true) {
        repoOf(service).upsertMessage(
            MessageDto(
                id = messageId, conversationId = conversationId, senderId = "p1", senderName = "Someone",
                timestampMs = ts, text = text, isOld = old,
            )
        )
    }

    fun seedOutgoing(service: ChatService, messageId: String, conversationId: String, text: String, ts: Long = 2_000) {
        repoOf(service).upsertMessage(
            MessageDto(
                id = messageId, conversationId = conversationId, fromMe = true, direction = "out",
                status = "OUTGOING_DELIVERED", timestampMs = ts, text = text, isOld = true,
            )
        )
    }

    // --- waiting and looking --------------------------------------------------------------

    /** Runs the main looper (advancing its clock, for debounces) and gives the IO threads a moment. */
    fun settle(rounds: Int = 5) {
        repeat(rounds) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            Thread.sleep(10)
        }
    }

    fun waitUntil(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            if (condition()) return
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    fun hasText(root: View, text: String): Boolean =
        findAll(root) { it is TextView && it.isShown && it.text.toString() == text }.isNotEmpty()

    fun findAll(root: View, match: (View) -> Boolean): List<View> {
        val found = ArrayList<View>()
        fun walk(view: View) {
            if (match(view)) found += view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
        return found
    }

    companion object {
        const val WA_DB = "whatsapp-screens-test.db"
    }
}
