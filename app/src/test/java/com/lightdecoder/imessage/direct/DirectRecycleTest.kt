package com.lightdecoder.imessage.direct

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.Capability
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.IMessagePrefs
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.Folder
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.ParticipantDto
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.IndexedMessagePart
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MessagePart
import uniffi.rust_bridge.MessageType
import uniffi.rust_bridge.NormalMessage
import uniffi.rust_bridge.PushChatMessage
import uniffi.rust_bridge.TextFlags
import uniffi.rust_bridge.TextFormat
import java.io.File
import java.util.Collections

/**
 * "Delete on All Devices" and Recently Deleted in the main process: deletes here go to Recently
 * Deleted (and, with the setting on, to the user's own devices only), the user's other devices'
 * deletes are taken within their limits, and Apple handing a message over again never brings a
 * deleted one back.
 */
@RunWith(AndroidJUnit4::class)
class DirectRecycleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ArchiveDb
    private lateinit var repo: ArchiveRepository
    private val me = "tel:+15550000001"
    private val bob = "tel:+15559876543"
    private val chat = "imsg:$bob"
    private val day = 24L * 60 * 60 * 1000

    private val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())

    @Before
    fun setUp() {
        context.deleteDatabase(DB)
        db = ArchiveDb(context, DB)
        repo = ArchiveRepository.forTesting(db, ChatService.IMESSAGE)
        ArchiveRepository.installForTesting(repo)
        IMessageManager.installForTesting(context, repo)
        DirectPrefs.setRegistered(context, true)
        DirectLibrary.REQUIRED.forEach { File(DirectLibrary.dir(context), it).apply { parentFile?.mkdirs(); writeText("") } }
        DirectLink.installForTesting(context) { what, request, _ ->
            asked += what to request
            DirectWire.ok()
        }
    }

    @After
    fun tearDown() {
        DirectPrefs.setDeleteOnAllDevices(context, false)
        MessageNotifier.cancelAll(context, ChatService.IMESSAGE)
        IMessagePrefs.setEnabled(context, false)
        DirectPrefs.setRegistered(context, false)
        DirectLink.resetForTesting()
        IMessageManager.resetForTesting()
        ArchiveRepository.resetForTesting()
        db.close()
    }

    private val link get() = DirectLink.get(context)
    private val manager get() = IMessageManager.get(context)

    private fun wire(u: DirectArchive.Update) = DirectWire.decode(JSONObject(DirectWire.encode(u).toString()))

    private fun incoming(guid: String, text: String, ts: Long) = DirectArchive.map(
        MessageInst(
            guid, bob, ConversationData(listOf(me, bob), null, null, null),
            PushChatMessage.Message(
                NormalMessage(
                    listOf(IndexedMessagePart(MessagePart.Text(text, TextFormat.Flags(TextFlags(false, false, false, false))), null, null)),
                    null, null, null, MessageType.IMessage, null, null, null, false, null, null,
                ),
            ),
            ts.toULong(), null, false, false, null,
        ),
        setOf(me), Handles::display, DirectTexts(context),
    ).let(::wire)

    private fun recycles(): List<DirectWire.RecycleRequest> =
        synchronized(asked) { asked.filter { it.first == DirectWire.RECYCLE }.map { DirectWire.RecycleRequest.fromJson(it.second) } }

    /** The RECYCLE requests, once [count] have gone (sent off the caller's thread), or what went within 3 s. */
    private fun awaitRecycles(count: Int): List<DirectWire.RecycleRequest> {
        val until = System.currentTimeMillis() + 3_000
        while (recycles().size < count && System.currentTimeMillis() < until) Thread.sleep(20)
        return recycles()
    }

    private fun folderOf(id: String) = repo.conversation(id)?.folderEnum

    @Test
    fun aDeleteMovesTheChatToRecentlyDeletedAndNothingGoesWithTheSettingOff() = runBlocking {
        link.apply(incoming("G1", "hi", 1_700_000_000_000L))
        assertTrue(Capability.DELETED_FOLDER in manager.capabilities)
        manager.deleteConversation(chat)
        assertEquals(Folder.DELETED, folderOf(chat))
        assertTrue(repo.conversation(chat)!!.deletedAt > 0)
        assertNotNull(repo.message("imsg:G1"))
        // Recovered here.
        assertTrue(manager.restoreDeletedLocally(chat))
        assertEquals(Folder.INBOX, folderOf(chat))
        manager.deleteMessage("imsg:G1")
        Thread.sleep(200)
        assertTrue(recycles().isEmpty())
    }

    @Test
    fun withTheSettingOnTheUsersOwnDevicesAreTold() = runBlocking {
        DirectPrefs.setDeleteOnAllDevices(context, true)
        link.apply(incoming("G1", "hi", 1_700_000_000_000L))
        link.apply(incoming("G2", "there", 1_700_000_001_000L))
        manager.deleteMessage("imsg:G2")
        manager.deleteConversation(chat)
        manager.restoreDeletedLocally(chat)
        manager.deleteConversation(chat)
        assertEquals(1, manager.purgeDeleted(chat))
        val sent = awaitRecycles(5)
        assertEquals(
            listOf(RecycleRules.OP_BIN to listOf("G2"), RecycleRules.OP_BIN to null, RecycleRules.OP_RECOVER to null, RecycleRules.OP_BIN to null, RecycleRules.OP_PURGE to null),
            sent.map { it.op to it.messages },
        )
        // The chat as the library names it: its person, from the user's own address.
        assertTrue(sent.all { bob in it.target.participants })
        assertNull(repo.conversation(chat))
    }

    @Test
    fun aMessageHandedOverAgainDoesNotBringADeletedChatBackButANewOneDoes() = runBlocking {
        link.apply(incoming("G1", "old", 1_700_000_000_000L))
        manager.deleteConversation(chat)
        // The same message again (Apple's journal after a restart), and one older than the delete.
        link.apply(incoming("G1", "old", 1_700_000_000_000L))
        link.apply(incoming("G0", "older", 1_700_000_000_500L))
        assertEquals(Folder.DELETED, folderOf(chat))
        // A new message sent after it was deleted: the chat is back.
        link.apply(incoming("G3", "new", System.currentTimeMillis() + 1_000))
        assertEquals(Folder.INBOX, folderOf(chat))
        assertEquals(0L, repo.conversation(chat)!!.deletedAt)
    }

    @Test
    fun aDeletedMessageIsNotStoredAgain() = runBlocking {
        link.apply(incoming("G1", "hi", 1_700_000_000_000L))
        manager.deleteMessage("imsg:G1")
        link.apply(incoming("G1", "hi", 1_700_000_000_000L))
        assertNull(repo.message("imsg:G1"))
        assertTrue(repo.isTombstoned("imsg:G1"))
    }

    @Test
    fun theUsersOtherDevicesDeletesAreTakenOnlyWithTheSettingOn() {
        link.apply(incoming("G1", "hi", 1_700_000_000_000L))
        val now = System.currentTimeMillis()
        link.apply(wire(DirectArchive.Update.Recycle(chat, null, emptyList(), now)))
        assertEquals(Folder.INBOX, folderOf(chat))
        link.apply(wire(DirectArchive.Update.Recycle(null, null, listOf("imsg:G1"), now)))
        assertNotNull(repo.message("imsg:G1"))

        DirectPrefs.setDeleteOnAllDevices(context, true)
        link.apply(wire(DirectArchive.Update.Recycle(chat, null, emptyList(), now - 5_000)))
        assertEquals(Folder.DELETED, folderOf(chat))
        assertEquals(now - 5_000, repo.conversation(chat)!!.deletedAt)
        link.apply(wire(DirectArchive.Update.Recover(chat, null)))
        assertEquals(Folder.INBOX, folderOf(chat))
        // Messages: deleted for good here, and kept as deleted.
        link.apply(wire(DirectArchive.Update.Recycle(null, null, listOf("imsg:G1", "imsg:NOT-HERE"), now)))
        assertNull(repo.message("imsg:G1"))
        assertTrue(repo.isTombstoned("imsg:NOT-HERE"))
    }

    @Test
    fun aRecoverOfAChatNotDeletedDoesNothingAndAChatNotStoredIsNotMade() {
        DirectPrefs.setDeleteOnAllDevices(context, true)
        link.apply(incoming("G1", "hi", 1_700_000_000_000L))
        link.apply(wire(DirectArchive.Update.Recover(chat, null)))
        assertEquals(Folder.INBOX, folderOf(chat))
        link.apply(wire(DirectArchive.Update.Recycle("imsg:tel:+15550009999", null, emptyList(), 1)))
        link.apply(wire(DirectArchive.Update.Recycle(DirectArchive.groupConversationId("NOPE"), "NOPE", emptyList(), 1)))
        assertNull(repo.conversation("imsg:tel:+15550009999"))
        assertNull(repo.conversation(DirectArchive.groupConversationId("NOPE")))
    }

    @Test
    fun aRemotePurgeIsLimited() {
        DirectPrefs.setDeleteOnAllDevices(context, true)
        link.apply(incoming("G1", "hi", 1_700_000_000_000L))
        // Not in Recently Deleted: nothing is purged.
        link.apply(wire(DirectArchive.Update.Purge(chat, null, emptyList())))
        assertNotNull(repo.message("imsg:G1"))
        assertEquals(Folder.INBOX, folderOf(chat))
        // Recycled and purged at once (one forged-but-verified pair): it stays a day.
        val now = System.currentTimeMillis()
        link.apply(wire(DirectArchive.Update.Recycle(chat, null, emptyList(), now)))
        link.apply(wire(DirectArchive.Update.Purge(chat, null, emptyList())))
        assertEquals(Folder.DELETED, folderOf(chat))
        assertNotNull(repo.message("imsg:G1"))
        val at = repo.conversation(chat)!!.deletedAt
        assertTrue(at in (now - RecycleRules.RETENTION_MS + day - 60_000)..(now - RecycleRules.RETENTION_MS + day + 60_000))
        // Retention then takes it once the day is over, and not before.
        assertEquals(0, manager.purgeExpired(now))
        assertEquals(1, manager.purgeExpired(now + day + 1_000))
        assertNull(repo.conversation(chat))
        // In Recently Deleted for more than a day: deleted for good at once.
        link.apply(incoming("G5", "again", System.currentTimeMillis() + 1_000))
        repo.moveToDeleted(chat, System.currentTimeMillis() - 2 * day)
        link.apply(wire(DirectArchive.Update.Purge(chat, null, emptyList())))
        assertNull(repo.conversation(chat))
    }

    @Test
    fun aRecycleDatedLongAgoStillStaysADayInRecentlyDeleted() {
        DirectPrefs.setDeleteOnAllDevices(context, true)
        link.apply(incoming("G1", "hi", 1_700_000_000_000L))
        val now = System.currentTimeMillis()
        // Named as recycled a month ago (or at 1 ms): held to a day before retention, also here in the main process.
        link.apply(wire(DirectArchive.Update.Recycle(chat, null, emptyList(), 1)))
        assertEquals(Folder.DELETED, folderOf(chat))
        val at = repo.conversation(chat)!!.deletedAt
        assertTrue(at >= now - RecycleRules.RETENTION_MS + day - 60_000)
        assertEquals(0, manager.purgeExpired())
        assertNotNull(repo.message("imsg:G1"))
        // A day later it goes.
        assertEquals(1, manager.purgeExpired(System.currentTimeMillis() + day + 60_000))
        assertNull(repo.conversation(chat))
    }

    @Test
    fun openingAConversationWithSomeoneInRecentlyDeletedLeavesTheirChatThere() = runBlocking {
        val reached = JSONObject().put(
            "conversation",
            ConversationDto(
                id = chat, type = DirectArchive.TRANSPORT, defaultOutgoingId = me,
                participants = listOf(ParticipantDto(id = bob, number = Handles.display(bob)), ParticipantDto(id = me, isMe = true)),
                otherParticipantIds = listOf(bob),
            ).toJson(),
        )
        DirectLink.installForTesting(context) { what, request, _ ->
            asked += what to request
            if (what == DirectWire.REACH) reached else DirectWire.ok()
        }
        link.apply(incoming("G1", "hi", 1_700_000_000_000L))
        manager.deleteConversation(chat)
        val at = repo.conversation(chat)!!.deletedAt
        // A Messages conversation with them opens: Apple is asked, and their chat stays in Recently Deleted.
        assertNull(link.reachOnce(Handles.display(bob)))
        assertTrue(synchronized(asked) { asked.any { it.first == DirectWire.REACH } })
        assertEquals(Folder.DELETED, folderOf(chat))
        assertEquals(at, repo.conversation(chat)!!.deletedAt)
        // The user writing to them: it comes back.
        assertEquals(chat, link.conversationFor(Handles.display(bob), revive = true))
        assertEquals(Folder.INBOX, folderOf(chat))
        assertEquals(0L, repo.conversation(chat)!!.deletedAt)
    }

    @Test
    fun aRecoverJustBeforeThePurgeKeepsWhatIsKeptOfTheChat() = runBlocking {
        link.apply(incoming("G1", "hi", 1_700_000_000_000L))
        com.lightdecoder.imessage.direct.GroupVersions(context).forget(chat)
        com.lightdecoder.imessage.direct.GroupVersions(context).note(chat, 3)
        // Not in Recently Deleted (recovered already): nothing is purged, and nothing outside the archive is forgotten.
        assertEquals(0, link.purgeForGood(chat))
        assertNotNull(repo.conversation(chat))
        assertEquals(3L, com.lightdecoder.imessage.direct.GroupVersions(context).of(chat))
        manager.deleteConversation(chat)
        assertEquals(1, link.purgeForGood(chat))
        assertNull(repo.conversation(chat))
        assertEquals(null, com.lightdecoder.imessage.direct.GroupVersions(context).of(chat))
    }

    @Test
    fun aRecycleOfAMergedChatMovesOnlyTheImessageChat() {
        DirectPrefs.setDeleteOnAllDevices(context, true)
        val messagesDb = ArchiveDb(context, MESSAGES_DB)
        try {
            val messages = ArchiveRepository.forTesting(messagesDb, ChatService.MESSAGES)
            ArchiveRepository.installForTesting(messages)
            messages.upsertConversation(
                ConversationDto(id = "g1", participants = listOf(ParticipantDto(id = "p1", name = "Bob", number = "+15559876543"))),
            )
            link.apply(incoming("G1", "hi", 1_700_000_000_000L))
            link.apply(wire(DirectArchive.Update.Recycle(chat, null, emptyList(), System.currentTimeMillis())))
            assertEquals(Folder.DELETED, folderOf(chat))
            assertEquals(Folder.INBOX, messages.conversation("g1")!!.folderEnum)
        } finally {
            messagesDb.close()
            context.deleteDatabase(MESSAGES_DB)
        }
    }

    @Test
    fun reportJunkStillDeletesForGood() = runBlocking {
        link.apply(incoming("G1", "buy now", 1_700_000_000_000L))
        manager.deleteConversationForGood(chat)
        assertNull(repo.conversation(chat))
        assertTrue(repo.isTombstoned("imsg:G1"))
    }

    private companion object {
        const val DB = "direct-recycle-test.db"
        const val MESSAGES_DB = "direct-recycle-messages-test.db"
    }
}
