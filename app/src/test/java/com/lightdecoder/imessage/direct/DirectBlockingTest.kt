package com.lightdecoder.imessage.direct

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.MediaCache
import com.lightdecoder.chat.BlockList
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageAddress
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.IMessagePrefs
import com.lightdecoder.imessage.facetime.BlockedCallers
import com.lightdecoder.imessage.facetime.CallRows
import com.lightdecoder.imessage.facetime.Outcome
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.MessageNotifier
import java.io.File
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
import org.robolectric.Shadows.shadowOf
import uniffi.rust_bridge.AttachmentType
import uniffi.rust_bridge.ChatRenameMessage
import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.IndexedMessagePart
import uniffi.rust_bridge.MessageAttachment
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MessagePart
import uniffi.rust_bridge.MessageType
import uniffi.rust_bridge.NormalMessage
import uniffi.rust_bridge.PushChatMessage
import uniffi.rust_bridge.TextFlags
import uniffi.rust_bridge.TextFormat

/**
 * Blocking in the iMessage link (SPEC2 B3.1): a blocked sender's message is not stored, not
 * notified, answered "blocked" (so no delivery receipt goes from this phone), and its files written
 * aside go; a blocked member's group changes still apply; nothing is sent to a blocked person unasked;
 * their FaceTime calls make no row; the ":imessage" process gets a hashed copy of the list.
 */
@RunWith(AndroidJUnit4::class)
class DirectBlockingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ArchiveDb
    private lateinit var repo: ArchiveRepository
    private lateinit var blocks: BlockList
    private val me = "tel:+15550000001"
    private val alice = "tel:+15551234567"
    private val bob = "tel:+15559876543"
    private val plain = TextFormat.Flags(TextFlags(false, false, false, false))
    private val asked: MutableList<Pair<Int, JSONObject>> = Collections.synchronizedList(ArrayList())

    @Before
    fun setUp() {
        IMessageAddress.setRegionForTesting("US")
        context.deleteDatabase(DB)
        db = ArchiveDb(context, DB)
        repo = ArchiveRepository.forTesting(db, ChatService.IMESSAGE)
        ArchiveRepository.installForTesting(repo)
        IMessageManager.installForTesting(context, repo)
        blocks = BlockList(File(context.filesDir, "blocked-test.json").apply { delete() })
        BlockList.installForTesting(blocks)
        IMessagePrefs.setEnabled(context, true)
        IMessagePrefs.setNotifyMessages(context, true)
        DirectPrefs.setNotifyWhenMentioned(context, true)
    }

    @After
    fun tearDown() {
        MessageNotifier.cancelAll(context, ChatService.IMESSAGE)
        IMessagePrefs.setEnabled(context, false)
        DirectPrefs.setRegistered(context, false)
        DirectLink.resetForTesting()
        IMessageManager.resetForTesting()
        ArchiveRepository.resetForTesting()
        BlockList.installForTesting(null)
        BlockedCallers.installForTesting(null)
        IMessageAddress.resetForTesting()
        db.close()
        MediaCache.mediaDir(context, ChatService.IMESSAGE).deleteRecursively()
    }

    private fun link() = DirectLink.installForTesting(context) { what, request, _ ->
        asked += what to request
        DirectWire.ok()
    }

    private fun wire(u: DirectArchive.Update) = DirectWire.decode(JSONObject(DirectWire.encode(u).toString()))

    private fun text(s: String) = IndexedMessagePart(MessagePart.Text(s, plain), null, null)

    private fun normal(parts: List<IndexedMessagePart>) =
        PushChatMessage.Message(NormalMessage(parts, null, null, null, MessageType.IMessage, null, null, null, false, null, null))

    private fun inst(guid: String, from: String, m: PushChatMessage, participants: List<String> = listOf(me, from), groupGuid: String? = null, ts: Long = 1_700_000_000_000L) =
        MessageInst(guid, from, ConversationData(participants, null, groupGuid, null), m, ts.toULong(), null, false, false, null)

    private fun map(i: MessageInst) = wire(DirectArchive.map(i, setOf(me), Handles::display, DirectTexts(context)))

    private fun announced() = shadowOf(context.getSystemService(NotificationManager::class.java)).activeNotifications.any { it.tag?.startsWith("im:") == true }

    /** The answer the ":imessage" process gets for [update], as the sink service gives it. */
    private fun answer(link: DirectLink, update: DirectArchive.Update): JSONObject =
        DirectSinkService.take(DirectWire.encodeUpdate(update)) { link.apply(it) }

    private fun activate() {
        DirectPrefs.setRegistered(context, true)
        DirectLibrary.REQUIRED.forEach { File(DirectLibrary.dir(context), it).apply { parentFile?.mkdirs(); writeText("") } }
    }

    private fun waitFor(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Test
    fun aBlockedSendersMessageIsNotStoredNotNotifiedAnsweredBlockedAndItsFilesGo() {
        val link = link()
        blocks.block("+1 555 987 6543")
        // Bob's small picture, written aside by the ":imessage" process before it hands the message over.
        val bytes = byteArrayOf(71, 73, 70, 56, 57, 97, 1)
        val gif = MessageAttachment(AttachmentType.Inline(bytes), 0UL, "com.compuserve.gif", "image/gif", "a.gif", false)
        val file = MediaCache.fileFor(context, "B1/p0", DirectMedia.extensionFor("image/gif", "a.gif"), ChatService.IMESSAGE)
        val pending = DirectMedia.pendingInlineFile(file).also { MediaCache.writeAtomically(it, bytes) }
        val update = wire(
            DirectArchive.map(
                inst("B1", bob, PushChatMessage.Message(NormalMessage(listOf(IndexedMessagePart(MessagePart.Attachment(gif), 0UL, null)), null, null, null, MessageType.IMessage, null, null, null, false, null, null))),
                setOf(me), Handles::display, DirectTexts(context), { "DESC-B1" },
            ),
        )
        assertTrue((update as DirectArchive.Update.Upsert).message.mediaParts.any { it.mediaId == "B1/p0" })
        val reply = answer(link, update)
        assertTrue(DirectWire.isBlocked(reply))
        assertEquals(Delivery.BLOCKED, Delivery.of(reply))
        assertFalse("no delivery receipt goes for it", Delivery.of(reply).acknowledges)
        assertNull(repo.message("imsg:B1"))
        assertNull(repo.conversation("imsg:$bob"))
        assertFalse(announced())
        assertFalse(pending.exists())
        assertFalse(file.exists())
        // Their tapbacks, typing and edits go too; someone else's message is stored and notified.
        assertEquals(DirectLink.Applied.BLOCKED, link.apply(DirectArchive.Update.Typing("imsg:$bob", bob, true)))
        assertEquals(DirectLink.Applied.BLOCKED, link.apply(DirectArchive.Update.Edit("imsg:B1", "edited", sender = bob)))
        assertEquals(DirectLink.Applied.BLOCKED, link.apply(DirectArchive.Update.Reaction("imsg:X", bob, "❤️", true, false, 1L)))
        val fine = answer(link, map(inst("A1", alice, normal(listOf(text("hello"))))))
        assertFalse(DirectWire.isBlocked(fine))
        assertEquals(Delivery.STORED, Delivery.of(fine))
        assertNotNull(repo.message("imsg:A1"))
        assertTrue(announced())
        // Unblocked: their next message arrives.
        blocks.unblock(bob)
        assertEquals(DirectLink.Applied.STORED, link.apply(map(inst("B2", bob, normal(listOf(text("again")))))))
        assertNotNull(repo.message("imsg:B2"))
    }

    @Test
    fun aBlockedMembersGroupChangesStillApplyButNotTheirMessages() {
        val link = link()
        val members = listOf(me, alice, bob)
        link.apply(map(inst("G0", alice, normal(listOf(text("hello all"))), members, "GRP-B")))
        val group = "imsg:group:GRP-B"
        assertNotNull(repo.conversation(group))
        blocks.block(bob)
        // Bob renames the group: the group's name follows (silently), as it must for the group to stay one.
        link.apply(map(inst("G1", bob, PushChatMessage.RenameMessage(ChatRenameMessage("Ski trip")), members, "GRP-B", ts = 1_700_000_001_000L)))
        assertEquals("Ski trip", repo.conversation(group)!!.name)
        // Bob's message to the group, and his mention of the user, are dropped: not stored, not heard.
        MessageNotifier.cancelAll(context, ChatService.IMESSAGE)
        repo.markConversationRead(group)
        repo.setConversationMuted(group, true)
        val mention = map(
            inst("G2", bob, normal(listOf(text("Hey "), IndexedMessagePart(MessagePart.Mention("+15550000001", "You"), null, null))), members, "GRP-B", ts = 1_700_000_002_000L),
        )
        assertEquals(DirectLink.Applied.BLOCKED, link.apply(mention))
        assertNull(repo.message("imsg:G2"))
        assertFalse(announced())
        // Alice's mention still lifts the group's hidden alerts.
        link.apply(map(inst("G3", alice, normal(listOf(text("Hey "), IndexedMessagePart(MessagePart.Mention("+15550000001", "You"), null, null))), members, "GRP-B", ts = 1_700_000_003_000L)))
        assertTrue(announced())
    }

    @Test
    fun aBlockedPersonsChatBackgroundIsDroppedWithItsPictureAndMakesNoChat() {
        val link = link()
        blocks.block(bob)
        val row = map(inst("BG1", bob, normal(listOf(text("Bob changed the chat background."))))) as DirectArchive.Update.Upsert
        val picture = ChatBackgrounds.incomingFile(context).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val update = wire(
            DirectArchive.Update.Background(
                row.conversation.id, null, null, System.currentTimeMillis(), picture.path,
                row.copy(message = row.message.copy(direction = "system")),
            ),
        )
        val reply = answer(link, update)
        assertTrue("answered blocked: no delivery receipt for it", DirectWire.isBlocked(reply))
        assertNull("no row, and no chat made by it", repo.conversation(row.conversation.id))
        assertNull(repo.message("imsg:BG1"))
        assertFalse("the picture the :imessage process saved goes", picture.exists())
        assertEquals(0L, ChatBackgrounds(context).of(row.conversation.id).version)
    }

    @Test
    fun aBlockedPersonsOwnSystemRowIsDroppedButTheirGroupsStateIsNot() {
        val link = link()
        blocks.block(bob)
        // An iMessage app's "updated" row: written by its sender (the app's name), not a group's state.
        val row = map(inst("AU1", bob, normal(listOf(text("Bob updated Pwned App"))))) as DirectArchive.Update.Upsert
        val meta = wire(row.copy(message = row.message.copy(direction = "system")))
        assertEquals(DirectLink.Applied.BLOCKED, link.apply(meta))
        assertNull(repo.message("imsg:AU1"))
        assertNull(repo.conversation(row.conversation.id))
        // A group's rename by them is still a group's state: it applies (the group stays whole).
        val members = listOf(me, alice, bob)
        link.apply(map(inst("G0", alice, normal(listOf(text("hello all"))), members, "GRP-S")))
        val renamed = map(inst("G1", bob, PushChatMessage.RenameMessage(ChatRenameMessage("Renamed")), members, "GRP-S", ts = 1_700_000_001_000L))
        assertTrue((renamed as DirectArchive.Update.Upsert).groupEvent)
        assertEquals(DirectLink.Applied.STORED, link.apply(renamed))
        assertEquals("Renamed", repo.conversation("imsg:group:GRP-S")!!.name)
    }

    @Test
    fun aBlockedSendersMentionDoesNotNotifyEvenWhereAMentionLiftsHiddenAlerts() {
        repo.upsertConversation(
            com.lightdecoder.relay.ConversationDto(
                id = "imsg:group:N", isGroup = true, type = DirectArchive.TRANSPORT, muted = true,
                participants = listOf(com.lightdecoder.relay.ParticipantDto(id = "me", isMe = true), com.lightdecoder.relay.ParticipantDto(id = "+15559876543", number = "+15559876543")),
            ),
        )
        blocks.block(bob)
        MessageNotifier.onIncoming(context, repo, MessageDto(id = "imsg:N1", conversationId = "imsg:group:N", senderId = "+15559876543", direction = "in", text = "@you"), mentioned = true)
        assertFalse(announced())
        blocks.unblock(bob)
        MessageNotifier.onIncoming(context, repo, MessageDto(id = "imsg:N2", conversationId = "imsg:group:N", senderId = "+15559876543", direction = "in", text = "@you"), mentioned = true)
        assertTrue(announced())
    }

    @Test
    fun nothingIsSentUnaskedToABlockedPersonAndTheirCallsMakeNoRow() {
        activate()
        val link = link()
        link.apply(map(inst("C0", bob, normal(listOf(text("hi"))))))
        val chat = repo.conversation("imsg:$bob")!!.id
        blocks.block(bob)
        assertTrue(link.blockedChat(chat))
        runBlocking { link.markRead(chat) }
        assertTrue(asked.none { it.first == DirectWire.READ })
        // Their missed FaceTime call: no row, no notice (their rings are dropped first; this is the last check).
        link.apply(
            DirectArchive.Update.Call("BBBBBBBB-0000-4000-8000-000000000002", bob, outgoing = false, video = false, startedMs = 1_700_000_000_000L, durationMs = 0, outcome = Outcome.MISSED.wire),
        )
        assertNull(repo.message(CallRows.messageId("BBBBBBBB-0000-4000-8000-000000000002")))
        // The CALLER answer says so too.
        assertTrue(DirectWire.callerInfo(DirectWire.callerReply(link.callerInfo(bob))).blocked)
        assertFalse(DirectWire.callerInfo(DirectWire.callerReply(link.callerInfo(alice))).blocked)
        // Unblocked: read receipts go again (the positive control: the same chat, the same message).
        blocks.unblock(bob)
        assertFalse(link.blockedChat(chat))
        runBlocking { link.markRead(chat) }
        assertTrue(asked.any { it.first == DirectWire.READ })
    }

    @Test
    fun theUsersOwnAddressIsKnownFromTheMainArchiveAndCannotBeBlocked() {
        // The main process's own list, as the app builds it: its own addresses from its own archive only.
        BlockList.installForTesting(null)
        File(context.filesDir, "blocked.json").delete()
        repo.upsertConversation(
            com.lightdecoder.relay.ConversationDto(
                id = "imsg:$alice", isGroup = false, type = DirectArchive.TRANSPORT, defaultOutgoingId = me,
                participants = listOf(com.lightdecoder.relay.ParticipantDto(id = "me", isMe = true), com.lightdecoder.relay.ParticipantDto(id = alice, number = alice)),
            ),
        )
        val list = BlockList.get(context)
        assertEquals(BlockList.Added.OWN, list.block("+1 (555) 000-0001"))
        assertEquals(BlockList.Added.ADDED, list.block(bob))
        assertFalse(File(context.filesDir, "imessage/own-handles").exists())
        list.unblock(bob)
        File(context.filesDir, "blocked.json").delete()
    }

    @Test
    fun noTypingGoesToABlockedPersonAndItGoesAgainOnceUnblocked() {
        activate()
        val link = DirectLink.installForTesting(context, typingIdleMs = 300) { what, request, _ ->
            asked += what to request
            DirectWire.ok()
        }
        link.apply(map(inst("T0", bob, normal(listOf(text("hi"))))))
        val chat = repo.conversation("imsg:$bob")!!.id
        fun typings() = synchronized(asked) { asked.filter { it.first == DirectWire.TYPING }.map { it.second.getBoolean("typing") } }
        blocks.block(bob)
        // Typing, then quiet: neither "typing" nor "stopped" goes.
        link.typing(chat)
        Thread.sleep(800)
        link.stopTyping(chat)
        Thread.sleep(200)
        assertTrue("nothing told to a blocked person: ${typings()}", typings().isEmpty())
        // Unblocked: the same typing goes.
        blocks.unblock(bob)
        link.typing(chat)
        waitFor("typing") { typings().isNotEmpty() }
        assertEquals(true, typings().first())
        waitFor("stopped") { typings() == listOf(true, false) }
    }

    @Test
    fun theIMessageProcessGetsAHashedCopyOnEveryChange() {
        activate()
        link()
        blocks.block(bob)
        waitFor("the block list's copy") { asked.any { it.first == DirectWire.BLOCKLIST && it.second.optJSONArray("hashes")?.length() == 1 } }
        val sent = DirectWire.BlockListRequest.fromJson(asked.last { it.first == DirectWire.BLOCKLIST }.second)!!
        assertFalse("no address crosses", asked.filter { it.first == DirectWire.BLOCKLIST }.any { it.second.toString().contains("5559876543") })
        val copy = BlockedCallers(null).apply { replace(sent) }
        assertTrue(copy.isBlocked(bob))
        assertFalse(copy.isBlocked(alice))
        // Unblocked: an empty copy follows.
        blocks.unblock(bob)
        waitFor("the empty copy") { asked.last { it.first == DirectWire.BLOCKLIST }.second.optJSONArray("hashes")?.length() == 0 }
    }
}

private const val DB = "direct-blocking-test.db"
