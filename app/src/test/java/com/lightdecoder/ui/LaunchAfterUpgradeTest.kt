package com.lightdecoder.ui

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.LightDecoderApp
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.BlockList
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.IMessagePrefs
import com.lightdecoder.imessage.direct.DirectLibrary
import com.lightdecoder.imessage.direct.DirectLink
import com.lightdecoder.imessage.direct.DirectPrefs
import com.lightdecoder.imessage.facetime.CallRows
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.Folder
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.PartDto
import com.lightdecoder.relay.RelayManager
import com.lightdecoder.ui.chat.ConversationsActivity
import java.io.File
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The app opened after an update from 0.5.73 (archives at schema version 3, with real rows: Messages
 * chats, iMessage direct chats, a chat in Deleted, FaceTime call rows), with the iMessage library
 * registered and its cache cleared: the process starts ([LightDecoderApp.onCreate]) and the launcher
 * screen and the chats open without anything, on the main thread or in the background, taking the
 * process down.
 */
@RunWith(AndroidJUnit4::class)
class LaunchAfterUpgradeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val crashes: MutableList<Throwable> = Collections.synchronizedList(ArrayList())
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> crashes += e }
        resetProcess()
        for (name in DBS) app.deleteDatabase(name)
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        DirectPrefs.setRegistered(app, false)
        IMessagePrefs.setEnabled(app, false)
        resetProcess()
        for (name in DBS) app.deleteDatabase(name)
    }

    /** The singletons of a process, gone (as a new process has none). */
    private fun resetProcess() {
        DirectLink.resetForTesting()
        IMessageManager.resetForTesting()
        RelayManager.resetForTesting()
        BlockList.installForTesting(null)
        com.lightdecoder.imessage.facetime.BlockedCallers.installForTesting(null)
        ArchiveRepository.resetForTesting()
    }

    // --- an archive as 0.5.73 left it ----------------------------------------------------------------

    private fun person(id: String, name: String, number: String, folder: String = "inbox", type: String = "UNKNOWN") = ConversationDto(
        id = id, name = name, type = type, defaultOutgoingId = "me", folder = folder,
        participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = number, name = name, number = number, formattedNumber = number)),
    )

    private fun message(id: String, conversation: String, ts: Long, text: String, fromMe: Boolean = false) = MessageDto(
        id = id, conversationId = conversation, senderId = if (fromMe) "" else "+15550000002", fromMe = fromMe, timestampMs = ts,
        direction = if (fromMe) "out" else "in", status = "INCOMING_COMPLETE", transport = "IMESSAGE", text = text,
        parts = listOf(PartDto("0", "text", text)),
    )

    /** Writes [service]'s archive with the current code, then takes it back to what version 3 had. */
    private fun writeV3(service: ChatService, seed: (ArchiveRepository) -> Unit) {
        val name = ArchiveDb.nameFor(service)
        val db = ArchiveDb(app, name)
        seed(ArchiveRepository.forTesting(db, service))
        db.close()
        val raw = SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE)
        raw.execSQL("DROP TABLE IF EXISTS deleted_guids")
        raw.execSQL("ALTER TABLE conversations DROP COLUMN deleted_at")
        raw.execSQL("ALTER TABLE conversations DROP COLUMN first_unread_ts")
        raw.version = 3
        raw.close()
    }

    private fun seedArchives() {
        val now = System.currentTimeMillis()
        writeV3(ChatService.MESSAGES) { repo ->
            repo.upsertConversation(person("c1", "Bob", "+15550000002"))
            repo.upsertMessage(message("m1", "c1", now - 60_000, "hello"))
            repo.upsertConversation(person("c2", "Gone", "+15550000003", folder = Folder.DELETED.wire))
        }
        writeV3(ChatService.IMESSAGE) { repo ->
            repo.upsertConversation(person("imsg:chat1", "Alice", "+15551234567", type = IMessageManager.TRANSPORT))
            repo.upsertMessage(message("imsg:G1", "imsg:chat1", now - 120_000, "hi there"))
            repo.upsertMessage(message("imsg:G2", "imsg:chat1", now - 100_000, "back", fromMe = true))
            repo.upsertMessage(message(CallRows.messageId("CALL-1"), "imsg:chat1", now - 90_000, "Missed FaceTime Video"))
            repo.upsertConversation(person("imsg:chat2", "Carol", "+15553334444", folder = Folder.DELETED.wire, type = IMessageManager.TRANSPORT))
            repo.upsertMessage(message("imsg:G3", "imsg:chat2", now - 3_000_000_000L, "old"))
        }
    }

    /** iMessage through the library, as a user of 0.5.73 who set it up and used FaceTime has it. */
    private fun directMode() {
        DirectPrefs.setRegistered(app, true)
        DirectPrefs.setBlueBubblesTurnedOn(app)
        DirectPrefs.setFaceTimeOn(app, true)
        IMessagePrefs.setEnabled(app, true)
        DirectLibrary.REQUIRED.forEach { File(DirectLibrary.dir(app), it).apply { parentFile?.mkdirs(); writeText("") } }
        app.getSharedPreferences("imessage_facetime_link", Context.MODE_PRIVATE).edit().putBoolean("seen", true).commit()
        app.getSharedPreferences("imessage_account_link", Context.MODE_PRIVATE).edit().putBoolean("seen", true).commit()
    }

    private fun clearCache() {
        app.cacheDir.deleteRecursively()
        app.codeCacheDir.deleteRecursively()
    }

    /** A new main process: its singletons made again from what is stored, as [LightDecoderApp] does. */
    private fun startProcess() {
        resetProcess()
        (app as LightDecoderApp).onCreate()
    }

    private fun settle(ms: Long = 1_500) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertNoCrash() {
        val all = synchronized(crashes) { crashes.toList() }
        if (all.isNotEmpty()) throw AssertionError("the process would have crashed", all.first())
    }

    private fun launchBoth() {
        ActivityScenario.launch(InboxActivity::class.java).use { scenario ->
            settle()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
        assertNoCrash()
        ActivityScenario.launch(ConversationsActivity::class.java).use { scenario ->
            settle()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.recreate()
            settle()
        }
        assertNoCrash()
    }

    @Test
    fun theAppOpensAfterTheUpdateWithTheLibraryRegisteredAndTheCacheCleared() {
        seedArchives()
        directMode()
        clearCache()
        startProcess()
        settle()
        assertNoCrash()
        launchBoth()
        val repo = ArchiveRepository.get(app, ChatService.IMESSAGE)
        assertTrue("the iMessage archive was upgraded", repo.conversation("imsg:chat1") != null)
    }

    @Test
    fun theAppOpensAgainAfterTheFirstStartUpgradedItsArchives() {
        seedArchives()
        directMode()
        startProcess()
        launchBoth()
        clearCache()
        startProcess()
        settle()
        assertNoCrash()
        launchBoth()
    }

    @Test
    fun theAppOpensWithACacheFolderThatCannotBeMade() {
        seedArchives()
        directMode()
        clearCache()
        // Not a folder at all: what is kept under the cache cannot be made there.
        app.cacheDir.parentFile?.mkdirs()
        app.cacheDir.writeText("")
        try {
            startProcess()
            settle()
            assertNoCrash()
            launchBoth()
        } finally {
            app.cacheDir.delete()
            app.cacheDir.mkdirs()
        }
    }

    @Test
    fun theAppOpensWithoutTheLibraryToo() {
        seedArchives()
        clearCache()
        startProcess()
        settle()
        assertNoCrash()
        launchBoth()
    }

    private companion object {
        val DBS = ChatService.entries.map(ArchiveDb::nameFor).distinct()
    }
}
