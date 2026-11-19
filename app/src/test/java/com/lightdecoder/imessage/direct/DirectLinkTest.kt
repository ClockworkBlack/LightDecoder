package com.lightdecoder.imessage.direct

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.IMessagePrefs
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.ui.chat.IMessageLight
import java.io.File
import java.util.Collections
import kotlinx.coroutines.launch
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
import uniffi.rust_bridge.ChatRenameMessage
import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.EditMessage
import uniffi.rust_bridge.ErrorMessage
import uniffi.rust_bridge.IndexedMessagePart
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MessagePart
import uniffi.rust_bridge.MessageType
import uniffi.rust_bridge.NormalMessage
import uniffi.rust_bridge.PushChatMessage
import uniffi.rust_bridge.ReactMessage
import uniffi.rust_bridge.ReactMessageType
import uniffi.rust_bridge.Reaction
import uniffi.rust_bridge.TextFlags
import uniffi.rust_bridge.TextFormat
import uniffi.rust_bridge.UnsendMessage

/**
 * The library's messages land in Blue Bubbles' iMessage archive, in its conventions: a person's
 * existing chat is reused, the user is `me`, addresses are stored without their scheme.
 */
@RunWith(AndroidJUnit4::class)
class DirectLinkTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ArchiveDb
    private lateinit var repo: ArchiveRepository
    private val me = "tel:+15550000001"
    private val alice = "tel:+15551234567"
    private val bob = "tel:+15559876543"

    @Before
    fun setUp() {
        context.deleteDatabase(DB)
        db = ArchiveDb(context, DB)
        repo = ArchiveRepository.forTesting(db, ChatService.IMESSAGE)
        ArchiveRepository.installForTesting(repo)
        IMessageManager.installForTesting(context, repo)
    }

    @After
    fun tearDown() {
        MessageNotifier.cancelAll(context, ChatService.IMESSAGE)
        IMessagePrefs.setEnabled(context, false)
        DirectPrefs.setRegistered(context, false)
        DirectPrefs.setLastStatus(context, "")
        DirectLink.resetForTesting()
        IMessageManager.resetForTesting()
        ArchiveRepository.resetForTesting()
        db.close()
    }

    private fun incoming(guid: String, from: String, text: String, ts: Long = 1_700_000_000_000L, replyGuid: String? = null) =
        DirectArchive.map(
            MessageInst(
                guid, from, ConversationData(listOf(me, from), null, null, null),
                PushChatMessage.Message(
                    NormalMessage(
                        listOf(IndexedMessagePart(MessagePart.Text(text, TextFormat.Flags(TextFlags(false, false, false, false))), null, null)),
                        null, replyGuid, replyGuid?.let { "0:0:0" }, MessageType.IMessage, null, null, null, false, null, null,
                    ),
                ),
                ts.toULong(), null, false, false, null,
            ),
            setOf(me), Handles::display, DirectTexts(context),
        ).let(::wire)

    /** As it arrives from the other process. */
    private fun wire(u: DirectArchive.Update) = DirectWire.decode(org.json.JSONObject(DirectWire.encode(u).toString()))

    private fun event(
        guid: String,
        from: String,
        m: PushChatMessage,
        participants: List<String> = listOf(me, from),
        groupGuid: String? = null,
        ts: Long = 1_700_000_009_000L,
        groupName: String? = null,
    ) =
        DirectArchive.map(
            MessageInst(guid, from, ConversationData(participants, groupName, groupGuid, null), m, ts.toULong(), null, false, false, null),
            setOf(me), Handles::display, DirectTexts(context),
        ).let(::wire)

    private fun sentHere(guid: String, text: String, ts: Long = 1_700_000_000_000L) = repo.upsertMessage(
        com.lightdecoder.relay.MessageDto(
            id = "imsg:$guid", conversationId = "imsg:$bob", fromMe = true, timestampMs = ts, direction = "out",
            status = DirectArchive.STATUS_SENT, statusCode = 1, transport = IMessageManager.TRANSPORT, text = text,
            parts = listOf(com.lightdecoder.relay.PartDto("0", "text", text)),
        ),
    )

    @Test
    fun aPersonsBlueBubblesChatIsReused() {
        // Alice's chat as Blue Bubbles stored it from OpenBubbles' notifications.
        repo.upsertConversation(
            ConversationDto(
                id = "iMessage;-;+15551234567", type = IMessageManager.TRANSPORT,
                participants = listOf(
                    ParticipantDto(id = "me", isMe = true),
                    ParticipantDto(id = "+15551234567", name = "Alice", number = "+15551234567"),
                ),
            ),
        )
        DirectLink.get(context).apply(incoming("G1", alice, "hello from the library"))
        val m = repo.message("imsg:G1")!!
        assertEquals("iMessage;-;+15551234567", m.conversationId)
        assertEquals("hello from the library", m.text)
        assertEquals("+15551234567", m.senderId)
        assertEquals(IMessageManager.TRANSPORT, m.transport)
        assertNull(repo.conversation("imsg:$alice"))
        assertTrue(repo.conversation("iMessage;-;+15551234567")!!.unread)
    }

    @Test
    fun someoneNewGetsAChatInTheArchivesConventions() {
        DirectLink.get(context).apply(incoming("G2", bob, "hi"))
        val id = "imsg:$bob"
        assertEquals(id, repo.message("imsg:G2")!!.conversationId)
        val people = repo.participants(id)
        assertEquals("me", people.single { it.isMe }.id)
        assertEquals("+15559876543", people.single { !it.isMe }.number)
        // Sending back to them (after any restart): their address, from the archive.
        val target = DirectLink.get(context).targetOf(id)!!
        assertEquals(listOf(bob), target.participants)
        assertNull(target.groupGuid)
    }

    @Test
    fun aGroupBlueBubblesMadeIsNotSentToDirectly() {
        // A group chat from OpenBubbles' notifications has no iMessage group id: Blue Bubbles' way is used.
        repo.upsertConversation(
            ConversationDto(
                id = "iMessage;+;chat123", isGroup = true, name = "Trip", type = IMessageManager.TRANSPORT,
                participants = listOf(
                    ParticipantDto(id = "me", isMe = true),
                    ParticipantDto(id = "+15551234567", number = "+15551234567"),
                    ParticipantDto(id = "+15559876543", number = "+15559876543"),
                ),
            ),
        )
        assertNull(DirectLink.get(context).targetOf("iMessage;+;chat123"))
    }

    @Test
    fun anInlineReplyFromAnIphoneShowsUnderTheMessageItAnswers() {
        val link = DirectLink.get(context)
        link.apply(incoming("G4", bob, "dinner at 7?"))
        link.apply(incoming("G5", bob, "or 8", ts = 1_700_000_001_000L, replyGuid = "G4"))
        assertEquals("imsg:G4", repo.message("imsg:G5")!!.replyToId)
        // And a reply from here to either threads under it on their iPhone.
        assertEquals("G4", DirectArchive.guidOf(repo.message("imsg:G4")!!.id))
    }

    @Test
    fun aRemovedMessageGoes() {
        val link = DirectLink.get(context)
        link.apply(incoming("G3", bob, "to be removed"))
        link.apply(DirectArchive.Update.Remove("imsg:G3"))
        assertNull(repo.message("imsg:G3"))
    }

    @Test
    fun anUnsentMessageLeavesWhoUnsentIt() {
        val link = DirectLink.get(context)
        link.apply(incoming("U1", bob, "oops"))
        link.apply(event("X1", bob, PushChatMessage.Unsend(UnsendMessage("U1", 0UL))))
        val row = repo.message("imsg:U1")!!
        assertEquals("system", row.direction)
        assertEquals("+15559876543 unsent a message.", row.text)
        // One the user unsent on their iPhone.
        sentHere("U2", "mine", ts = 1_700_000_001_000L)
        link.apply(event("X2", me, PushChatMessage.Unsend(UnsendMessage("U2", 0UL)), participants = listOf(me, bob)))
        assertEquals("You unsent a message.", repo.message("imsg:U2")!!.text)
        // Apple sending the message again does not bring it back.
        link.apply(incoming("U1", bob, "oops"))
        assertEquals("system", repo.message("imsg:U1")!!.direction)
    }

    @Test
    fun unsendingOnePartOfSeveralKeepsTheRest() {
        val link = DirectLink.get(context)
        val parts = listOf(
            IndexedMessagePart(MessagePart.Text("look", TextFormat.Flags(TextFlags(false, false, false, false))), 0UL, null),
            IndexedMessagePart(MessagePart.Text("and this", TextFormat.Flags(TextFlags(false, false, false, false))), 1UL, null),
        )
        link.apply(event("U3", bob, PushChatMessage.Message(NormalMessage(parts, null, null, null, MessageType.IMessage, null, null, null, false, null, null))))
        assertEquals("look\nand this", repo.message("imsg:U3")!!.text)
        link.apply(event("X3", bob, PushChatMessage.Unsend(UnsendMessage("U3", 1UL))))
        val row = repo.message("imsg:U3")!!
        assertEquals("in", row.direction)
        assertEquals("look", row.text)
        assertEquals(0L, row.editedAtMs)
    }

    @Test
    fun theSameUnsendTwiceAndTheMessageAgainLeaveTheRestAlone() {
        val link = DirectLink.get(context)
        val parts = listOf(
            IndexedMessagePart(MessagePart.Text("look", TextFormat.Flags(TextFlags(false, false, false, false))), 0UL, null),
            IndexedMessagePart(MessagePart.Text("and this", TextFormat.Flags(TextFlags(false, false, false, false))), 1UL, null),
        )
        val original = event("U4", bob, PushChatMessage.Message(NormalMessage(parts, null, null, null, MessageType.IMessage, null, null, null, false, null, null)))
        link.apply(original)
        val unsend = event("X7", bob, PushChatMessage.Unsend(UnsendMessage("U4", 1UL)))
        link.apply(unsend)
        // Apple's journal hands the unsend over again (say the other process restarted before it was acknowledged).
        link.apply(unsend)
        assertEquals("in", repo.message("imsg:U4")!!.direction)
        assertEquals("look", repo.message("imsg:U4")!!.text)
        // And Apple sends the message itself again: the unsent part stays away.
        link.apply(original)
        assertEquals("look", repo.message("imsg:U4")!!.text)
        assertEquals(setOf(1), IMessageExtra.parse(repo.message("imsg:U4")!!.extra).unsent)
        // Then the last part too: nothing is left.
        link.apply(event("X8", bob, PushChatMessage.Unsend(UnsendMessage("U4", 0UL))))
        assertEquals("system", repo.message("imsg:U4")!!.direction)
    }

    @Test
    fun theSameEditTwiceCountsOnce() {
        val link = DirectLink.get(context)
        link.apply(incoming("E2", bob, "dinner at 7"))
        val edit = event("X9", bob, PushChatMessage.Edit(EditMessage("E2", 0UL, listOf(IndexedMessagePart(MessagePart.Text("dinner at 8", TextFormat.Flags(TextFlags(false, false, false, false))), null, null)))))
        link.apply(edit)
        link.apply(edit)
        assertEquals(1, IMessageExtra.parse(repo.message("imsg:E2")!!.extra).edits)
        // A later edit back to an earlier text is an edit of its own.
        link.apply(DirectArchive.Update.Edit("imsg:E2", "dinner at 7", 0, 1_700_000_010_000L))
        assertEquals("dinner at 7", repo.message("imsg:E2")!!.text)
        assertEquals(2, IMessageExtra.parse(repo.message("imsg:E2")!!.extra).edits)
    }

    @Test
    fun theListShowsTheLatestMessageAsItNowReads() {
        val link = DirectLink.get(context)
        val id = "imsg:$bob"
        link.apply(incoming("P1", bob, "dinner at 7"))
        repo.conversation(id)!!.let {
            assertEquals("imsg:P1", it.latestMessageId)
            assertEquals("dinner at 7", it.snippet)
        }
        link.apply(event("X10", bob, PushChatMessage.Edit(EditMessage("P1", 0UL, listOf(IndexedMessagePart(MessagePart.Text("dinner at 8", TextFormat.Flags(TextFlags(false, false, false, false))), null, null))))))
        assertEquals("dinner at 8", repo.conversation(id)!!.snippet)
        // An older message Apple sends again leaves it as it is.
        link.apply(incoming("P0", bob, "earlier", ts = 1_600_000_000_000L))
        assertEquals("imsg:P1", repo.conversation(id)!!.latestMessageId)
        assertEquals("dinner at 8", repo.conversation(id)!!.snippet)
        link.apply(event("X11", bob, PushChatMessage.Unsend(UnsendMessage("P1", 0UL))))
        repo.conversation(id)!!.let {
            assertEquals("+15559876543 unsent a message.", it.snippet)
            assertFalse(it.snippetFromMe)
            assertEquals("", it.snippetSender)
        }
    }

    @Test
    fun aMessageAlreadyReadOrUnsentElsewhereIsNotAnnounced() {
        IMessagePrefs.setEnabled(context, true)
        IMessagePrefs.setNotifyMessages(context, true)
        val link = DirectLink.get(context)
        fun announced() = shadowOf(context.getSystemService(NotificationManager::class.java)).activeNotifications
            .any { it.tag?.startsWith("im:") == true }
        // Read on the user's iPhone before it reached this phone.
        link.apply(event("N1", me, PushChatMessage.Read))
        link.apply(incoming("N1", bob, "seen it already"))
        assertFalse(announced())
        assertFalse(repo.conversation("imsg:$bob")!!.unread)
        // Unsent before it got here.
        link.apply(event("X12", bob, PushChatMessage.Unsend(UnsendMessage("N2", 0UL))))
        link.apply(incoming("N2", bob, "never mind", ts = 1_700_000_001_000L))
        assertEquals("system", repo.message("imsg:N2")!!.direction)
        assertFalse(announced())
        // Anything else is.
        link.apply(incoming("N3", bob, "hello", ts = 1_700_000_002_000L))
        assertTrue(announced())
    }

    @Test
    fun whatComesWhileTheSendWaitsForAppleIsAppliedOnceItIsStored() {
        val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())
        lateinit var link: DirectLink
        link = DirectLink.installForTesting(context) { what, request, _ ->
            asked += what to request
            // Apple's Delivered and Bob's tapback come before the send's own answer does.
            link.apply(DirectArchive.Update.Receipt(listOf("imsg:S9"), DirectArchive.STATUS_DELIVERED))
            link.apply(DirectArchive.Update.Reaction("imsg:S9", "+15559876543", "❤️", true, false, 5))
            DirectWire.sent("S9", 1_700_000_005_000L)
        }
        link.apply(incoming("B0", bob, "hi"))
        val tmpId = link.sendText("imsg:$bob", "on my way", null)
        waitFor("the sent message, delivered and loved") {
            repo.message("imsg:S9")?.let { it.status == DirectArchive.STATUS_DELIVERED && it.reactions.isNotEmpty() } == true
        }
        val row = repo.message("imsg:S9")!!
        assertEquals("on my way", row.text)
        assertEquals(listOf(com.lightdecoder.relay.ReactionDto("❤️", listOf("+15559876543"))), row.reactions)
        assertNull("the local copy gave way", repo.message(MessageRow.tmpMessageId(tmpId)))
        val (what, request) = asked.single()
        assertEquals(DirectWire.SEND, what)
        assertEquals("B0", DirectWire.SendRequest.fromJson(request).target.afterGuid)
    }

    @Test
    fun onlyAMessageTheUserSentCanBeUnsent() {
        // Direct mode on: registered, with the library's files.
        DirectPrefs.setRegistered(context, true)
        DirectLibrary.REQUIRED.forEach { File(DirectLibrary.dir(context), it).apply { parentFile?.mkdirs(); writeText("") } }
        val link = DirectLink.get(context)
        val now = System.currentTimeMillis()
        link.apply(incoming("B0", bob, "hi"))
        sentHere("V1", "oops", ts = now)
        assertTrue(link.canUnsend(repo.message("imsg:V1")!!, now))
        // Unsent on the user's iPhone: the placeholder is the user's, but there is nothing to unsend.
        link.apply(event("X13", me, PushChatMessage.Unsend(UnsendMessage("V1", 0UL)), participants = listOf(me, bob)))
        val placeholder = repo.message("imsg:V1")!!
        assertEquals("system", placeholder.direction)
        assertFalse(link.canUnsend(placeholder, now))
        try {
            runBlocking { link.unsend("imsg:V1") }
            fail("a placeholder was unsent")
        } catch (e: DirectEngine.ImessageError) {
            assertEquals("Only your own messages can be unsent.", e.message)
        }
    }

    @Test
    fun aGroupsNameWithACommaIsItsName() {
        val link = DirectLink.get(context)
        link.apply(event("GE2", bob, PushChatMessage.RenameMessage(ChatRenameMessage("Mom, Dad & me")), participants = listOf(me, bob, alice), groupGuid = "G-2"))
        assertEquals("Mom, Dad & me", repo.conversation("imsg:group:G-2")!!.name)
        // A group without a name is named after its members.
        link.apply(event("GE3", bob, PushChatMessage.RenameMessage(ChatRenameMessage("")), participants = listOf(me, bob, alice), groupGuid = "G-3"))
        assertEquals(setOf("+15559876543", "+15551234567"), repo.conversation("imsg:group:G-3")!!.name.split(", ").toSet())
    }

    @Test
    fun anEditRebuildsTheTextAndKeepsReactions() {
        val link = DirectLink.get(context)
        link.apply(incoming("E1", bob, "dinner at 7"))
        link.apply(DirectArchive.Update.Reaction("imsg:E1", "me", "👍", true, true, 1))
        link.apply(event("X4", bob, PushChatMessage.Edit(EditMessage("E1", 0UL, listOf(IndexedMessagePart(MessagePart.Text("dinner at 8", TextFormat.Flags(TextFlags(false, false, false, false))), null, null))))))
        val row = repo.message("imsg:E1")!!
        assertEquals("dinner at 8", row.text)
        assertEquals(1_700_000_009_000L, row.editedAtMs)
        assertEquals(1, IMessageExtra.parse(row.extra).edits)
        assertEquals(listOf(com.lightdecoder.relay.ReactionDto("👍", listOf("me"), fromMe = true)), row.reactions)
        // Apple sending the original again keeps the edit and the reaction.
        link.apply(incoming("E1", bob, "dinner at 7"))
        assertEquals("dinner at 8", repo.message("imsg:E1")!!.text)
        assertEquals(1, repo.message("imsg:E1")!!.reactions.size)
    }

    @Test
    fun tapbacksAreOnePerPersonAndTheUserIsMe() {
        val link = DirectLink.get(context)
        link.apply(incoming("T1", bob, "hello"))
        fun react(from: String, r: Reaction, enable: Boolean = true) =
            link.apply(event("R-${r.hashCode()}-$enable", from, PushChatMessage.React(ReactMessage("T1", 0UL, ReactMessageType.React(r, enable), "", null)), participants = listOf(me, bob)))
        react(bob, Reaction.Heart)
        react(me, Reaction.Laugh)
        assertEquals(
            listOf(com.lightdecoder.relay.ReactionDto("❤️", listOf("+15559876543")), com.lightdecoder.relay.ReactionDto("😂", listOf("me"), fromMe = true)),
            repo.message("imsg:T1")!!.reactions,
        )
        react(bob, Reaction.Heart, enable = false)
        assertEquals(listOf(com.lightdecoder.relay.ReactionDto("😂", listOf("me"), fromMe = true)), repo.message("imsg:T1")!!.reactions)
    }

    @Test
    fun aReceiptBeforeItsMessageIsAppliedOnceTheMessageIsStored() {
        val link = DirectLink.get(context)
        // Apple's Delivered arrives before the send returned.
        link.apply(event("S1", bob, PushChatMessage.Delivered))
        link.apply(DirectArchive.Update.Reaction("imsg:S1", "+15559876543", "❤️", true, false, 5))
        link.apply(incoming("S1", me, "sent from the Mac"))
        val row = repo.message("imsg:S1")!!
        assertEquals(DirectArchive.STATUS_DELIVERED, row.status)
        assertEquals("❤️", row.reactions.single().emoji)
        // A later copy of the message does not put the status back.
        link.apply(incoming("S1", me, "sent from the Mac"))
        assertEquals(DirectArchive.STATUS_DELIVERED, repo.message("imsg:S1")!!.status)
    }

    @Test
    fun aFailureMarksTheUsersMessageUnlessItWasDelivered() {
        val link = DirectLink.get(context)
        sentHere("F1", "one")
        sentHere("F2", "two")
        link.apply(DirectArchive.Update.Receipt(listOf("imsg:F2"), DirectArchive.STATUS_DELIVERED))
        link.apply(event("X5", me, PushChatMessage.Error(ErrorMessage("F1", 4UL, "Not delivered"))))
        link.apply(event("X6", me, PushChatMessage.Error(ErrorMessage("F2", 4UL, "Not delivered"))))
        assertEquals(DirectArchive.STATUS_FAILED, repo.message("imsg:F1")!!.status)
        assertEquals("Not delivered", repo.message("imsg:F1")!!.errorText)
        assertEquals(DirectArchive.STATUS_DELIVERED, repo.message("imsg:F2")!!.status)
    }

    @Test
    fun readOnAnotherDeviceMarksTheChatRead() {
        val link = DirectLink.get(context)
        link.apply(incoming("R1", bob, "first", ts = 1_700_000_000_000L))
        link.apply(incoming("R2", bob, "second", ts = 1_700_000_001_000L))
        assertTrue(repo.conversation("imsg:$bob")!!.unread)
        // Read up to the first only: the second is still unread.
        link.apply(event("R1", me, PushChatMessage.Read))
        assertTrue(repo.conversation("imsg:$bob")!!.unread)
        link.apply(event("R2", me, PushChatMessage.Read))
        assertFalse(repo.conversation("imsg:$bob")!!.unread)
    }

    @Test
    fun typingShowsUntilTheyStopOrWrite() {
        val link = DirectLink.get(context)
        val manager = IMessageManager.get(context)
        link.apply(incoming("Y0", bob, "hi"))
        link.apply(event("Y1", bob, PushChatMessage.Typing(true, null)))
        assertEquals(mapOf("imsg:$bob" to setOf("+15559876543")), manager.typing.value)
        link.apply(event("Y2", bob, PushChatMessage.Typing(false, null)))
        assertTrue(manager.typing.value.isEmpty())
        link.apply(event("Y3", bob, PushChatMessage.Typing(true, null)))
        link.apply(incoming("Y4", bob, "there", ts = 1_700_000_002_000L))
        assertTrue(manager.typing.value.isEmpty())
    }

    @Test
    fun aGroupsEventsAreRowsThatAreNotUnread() {
        val link = DirectLink.get(context)
        link.apply(event("GE1", bob, PushChatMessage.RenameMessage(ChatRenameMessage("Ski trip")), participants = listOf(me, bob, alice), groupGuid = "G-1"))
        val id = "imsg:group:G-1"
        assertEquals("Ski trip", repo.conversation(id)!!.name)
        val row = repo.message("imsg:GE1")!!
        assertEquals("system", row.direction)
        assertEquals("+15559876543 named the conversation “Ski trip”.", row.text)
        assertFalse(repo.conversation(id)!!.unread)
        assertEquals("me", repo.participants(id).single { it.isMe }.id)
        // The list shows it as it reads: no "Bob:" before it.
        assertEquals("+15559876543 named the conversation “Ski trip”.", repo.conversation(id)!!.snippet)
        assertEquals("", repo.conversation(id)!!.snippetSender)
        // One the user made: not "You: You named …".
        link.apply(
            event("GE4", me, PushChatMessage.RenameMessage(ChatRenameMessage("Ski trip 2")), listOf(me, bob, alice), "G-1", ts = 1_700_000_010_000L),
        )
        assertEquals("You named the conversation “Ski trip 2”.", repo.conversation(id)!!.snippet)
        assertFalse(repo.conversation(id)!!.snippetFromMe)
    }

    @Test
    fun sendingNamesTheChatsLatestMessage() {
        val link = DirectLink.get(context)
        link.apply(incoming("L1", bob, "hi"))
        assertEquals("L1", link.targetOf("imsg:$bob")!!.afterGuid)
    }

    @Test
    fun heldChangesWaitTwoMinutesAtMost() {
        val held = HeldUpdates(windowMs = 120_000, maxMessages = 2)
        val r = DirectArchive.Update.Receipt(listOf("imsg:A"), DirectArchive.STATUS_READ)
        held.hold("imsg:A", r, 0)
        assertEquals(listOf(r), held.take("imsg:A", 120_000))
        assertTrue("taken once", held.take("imsg:A", 120_000).isEmpty())
        held.hold("imsg:A", r, 0)
        assertTrue("too old", held.take("imsg:A", 120_001).isEmpty())
        held.hold("imsg:A", r, 0)
        held.hold("imsg:B", r, 0)
        held.hold("imsg:C", r, 0)
        assertTrue("the oldest goes first", held.take("imsg:A", 1).isEmpty())
        assertEquals(listOf(r), held.take("imsg:C", 1))
    }

    @Test
    fun theStringsForGroupEventsReadAsOnAnIphone() {
        val t = DirectTexts(context)
        val e = DirectArchiveTest.TestTexts
        assertEquals(e.named("Bob", "Trip"), t.named("Bob", "Trip"))
        assertEquals(e.added("Bob", "Dave"), t.added("Bob", "Dave"))
        assertEquals(e.removed("Bob", "you"), t.removed("Bob", "you"))
        assertEquals(e.left("Carol"), t.left("Carol"))
        assertEquals(e.changedPhoto("You"), t.changedPhoto("You"))
        assertEquals(e.removedPhoto("Bob"), t.removedPhoto("Bob"))
        assertEquals("You", t.you)
        assertEquals("you", t.youObject)
    }

    @Test
    fun directModesSettingsAreItsOwnAndKeepTheRelayCode() {
        // Before: the relay code was kept in the main process's "imessage" file.
        val old = context.getSharedPreferences("imessage", Context.MODE_PRIVATE)
        old.edit().putString(DirectPrefs.KEY_CODE, "ABCD").putString(DirectPrefs.KEY_HOST, "https://relay.example").putBoolean("enabled", true).commit()
        val prefs = DirectPrefs.prefs(context)
        assertEquals("ABCD", prefs.getString(DirectPrefs.KEY_CODE, null))
        assertEquals("https://relay.example", prefs.getString(DirectPrefs.KEY_HOST, null))
        // Read, never written: the main process's file is as it was.
        assertEquals(setOf(DirectPrefs.KEY_CODE, DirectPrefs.KEY_HOST, "enabled"), old.all.keys)
        // Once only: a later change of the old file is not taken again.
        prefs.edit().putString(DirectPrefs.KEY_CODE, "NEW").commit()
        assertEquals("NEW", DirectPrefs.prefs(context).getString(DirectPrefs.KEY_CODE, null))
        assertFalse(DirectPrefs.sendReadReceipts(context))
        DirectPrefs.setSendReadReceipts(context, true)
        assertTrue(DirectPrefs.sendReadReceipts(context))
    }

    // --- edits, tapbacks and unsends from here ------------------------------------------------

    /** Direct mode on: registered, with the library's files. */
    private fun directModeOn() {
        DirectPrefs.setRegistered(context, true)
        DirectLibrary.REQUIRED.forEach { File(DirectLibrary.dir(context), it).apply { parentFile?.mkdirs(); writeText("") } }
    }

    /** A link whose requests to the other process are [asked], answered by [answer]. */
    private fun fakeLink(asked: MutableList<Pair<Int, JSONObject>>, answer: (Int, JSONObject) -> JSONObject = { _, _ -> DirectWire.ok() }) =
        DirectLink.installForTesting(context) { what, request, _ ->
            asked += what to request
            answer(what, request)
        }

    private fun row(
        id: String = "imsg:E",
        text: String = "on my way",
        ageMs: Long = 60_000,
        now: Long = NOW,
        outgoing: Boolean = true,
        direction: String = if (outgoing) "out" else "in",
        status: String = DirectArchive.STATUS_SENT,
        localState: String = com.lightdecoder.archive.LocalState.SYNCED,
        media: List<com.lightdecoder.archive.MediaRow> = emptyList(),
        extra: IMessageExtra = IMessageExtra(),
    ) = MessageRow(
        id = id, conversationId = "imsg:$bob", tmpId = "", senderId = "", senderName = "", isOutgoing = outgoing,
        timestampMs = now - ageMs, direction = direction, status = status, statusCode = 1, errorText = "",
        transport = IMessageManager.TRANSPORT, text = text, subject = "", replyToId = "", reactions = emptyList(),
        localState = localState, media = media, updatedAt = 0, extra = extra.toJson(),
    )

    /** A picture of the message `imsg:E`, at [partId]. */
    private fun picture(partId: String) = com.lightdecoder.archive.MediaRow(
        "imsg:E", partId, "E/$partId", ByteArray(0), "", ByteArray(0), "image/jpeg", "a.jpg", 1, 0, 0, ByteArray(0), false, "", "", false, false,
    )

    @Test
    fun onlyAWholeMessageOfTheUsersCanBeUnsent() {
        assertTrue(DirectLink.unsendable(row()))
        assertTrue("a picture alone", DirectLink.unsendable(row(text = "", media = listOf(picture("p0")))))
        // Not someone else's, not a row of the conversation's own, not one Apple does not know by a GUID.
        assertFalse(DirectLink.unsendable(row(outgoing = false)))
        assertFalse(DirectLink.unsendable(row(direction = "system")))
        assertFalse(DirectLink.unsendable(row(id = "ld:t1")))
        assertFalse(DirectLink.unsendable(row(id = MessageRow.tmpMessageId("t1"))))
        // Not one that failed (here, or as Apple said).
        assertFalse(DirectLink.unsendable(row(localState = com.lightdecoder.archive.LocalState.FAILED)))
        assertFalse(DirectLink.unsendable(row(status = DirectArchive.STATUS_FAILED)))
        // An unsend takes back one part: a message with more (sent from an iPhone) is not offered.
        assertFalse(DirectLink.unsendable(row(extra = IMessageExtra(parts = mapOf(0 to "look", 1 to "a.jpg")))))
        assertFalse(DirectLink.unsendable(row(media = listOf(picture("p1")))))
        assertFalse(DirectLink.unsendable(row(extra = IMessageExtra(parts = mapOf(1 to "a.jpg"), unsent = setOf(0)))))
    }

    @Test
    fun onlyTheUsersOwnTextIMessagesCanBeEditedAsOnAnIphone() {
        assertTrue(DirectLink.editable(row()))
        assertTrue(DirectLink.inEditWindow(row(), NOW))
        // Not someone else's, not a row of the conversation's own, not one sent before the library (no GUID).
        assertFalse(DirectLink.editable(row(outgoing = false)))
        assertFalse(DirectLink.editable(row(direction = "system")))
        assertFalse(DirectLink.editable(row(id = "ld:t1")))
        assertFalse(DirectLink.editable(row(id = MessageRow.tmpMessageId("t1"))))
        // Not one that failed, nor one without text, with a picture, or an iMessage app's.
        assertFalse(DirectLink.editable(row(localState = com.lightdecoder.archive.LocalState.FAILED)))
        assertFalse(DirectLink.editable(row(status = DirectArchive.STATUS_FAILED)))
        assertFalse(DirectLink.editable(row(text = " ")))
        assertFalse(DirectLink.editable(row(media = listOf(picture("p1")))))
        assertFalse(DirectLink.editable(row(extra = IMessageExtra(app = IMessageExtra.App(name = "GamePigeon")))))
        // Text in another part than the first: an edit of part 0 would not say which.
        assertFalse(DirectLink.editable(row(extra = IMessageExtra(parts = mapOf(0 to "a", 1 to "b")))))
        assertTrue(DirectLink.editable(row(extra = IMessageExtra(parts = mapOf(0 to "on my way"), edits = 2))))
        // 15 minutes, 5 edits.
        assertTrue(DirectLink.inEditWindow(row(ageMs = 15 * 60_000L - 1), NOW))
        assertFalse(DirectLink.inEditWindow(row(ageMs = 15 * 60_000L), NOW))
        assertTrue(DirectLink.inEditWindow(row(extra = IMessageExtra(edits = 4)), NOW))
        assertFalse(DirectLink.inEditWindow(row(extra = IMessageExtra(edits = 5)), NOW))
    }

    @Test
    fun tapbacksGoOnMessagesAppleKnowsByTheirGuid() {
        assertTrue(DirectLink.reactable(row()))
        assertTrue(DirectLink.reactable(row(outgoing = false)))
        assertFalse(DirectLink.reactable(row(direction = "system")))
        assertFalse(DirectLink.reactable(row(id = "ld:t1")))
        assertFalse(DirectLink.reactable(row(id = MessageRow.tmpMessageId("t1"))))
        // The text a tapback carries: 100 characters at most, never half an emoji.
        assertEquals("short", DirectLink.tapbackText("short"))
        assertEquals("a".repeat(100), DirectLink.tapbackText("a".repeat(150)))
        assertEquals("a".repeat(99), DirectLink.tapbackText("a".repeat(99) + "😀" + "tail"))
    }

    @Test
    fun theGatesAskForDirectModeAndAChatItCanReach() {
        val link = DirectLink.get(context)
        link.apply(incoming("B0", bob, "hi"))
        val now = System.currentTimeMillis()
        sentHere("G1", "mine", ts = now - 60_000)
        val mine = repo.message("imsg:G1")!!
        assertFalse("not without the library", link.canEdit(mine, now))
        assertFalse(link.canReact(mine))
        assertFalse(link.canUnsend(mine, now))
        directModeOn()
        assertTrue(link.canEdit(mine, now))
        assertTrue(link.canReact(mine))
        assertTrue(link.canUnsend(mine, now))
        assertTrue(link.canReact(repo.message("imsg:B0")!!))
        assertFalse("someone else's", link.canEdit(repo.message("imsg:B0")!!, now))
        assertFalse("someone else's", link.canUnsend(repo.message("imsg:B0")!!, now))
        assertFalse("too late", link.canEdit(mine, now + 15 * 60_000L))
        assertFalse("too late", link.canUnsend(mine, now + 60_000L))
        assertFalse("Apple said it failed", link.canUnsend(mine.copy(status = DirectArchive.STATUS_FAILED), now))
        // A Blue Bubbles group has no iMessage group id: nothing can reach it.
        val group = mine.copy(id = "imsg:G2", conversationId = "imsg:group:members:a,b")
        assertFalse(link.canEdit(group, now))
        assertFalse(link.canReact(group))
        assertFalse(link.canUnsend(group, now))
        // What a screen already found of the chat is taken as it is.
        assertFalse(link.canEdit(mine, now, reachable = false))
        assertFalse(link.canReact(mine, reachable = false))
        assertFalse(link.canUnsend(mine, now, reachable = false))
        assertTrue(link.canReact(group, reachable = true))
    }

    @Test
    fun anEditGoesToAppleThenReadsEditedHere() {
        val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())
        val link = fakeLink(asked)
        link.apply(incoming("B0", bob, "hi"))
        sentHere("E5", "on my wya", ts = System.currentTimeMillis() - 60_000)
        link.apply(DirectArchive.Update.Reaction("imsg:E5", "+15559876543", "❤️", true, false, 5))
        runBlocking { link.edit("imsg:E5", "on my way") }
        val (what, request) = asked.single()
        assertEquals(DirectWire.EDIT, what)
        assertEquals("E5", request.getString("guid"))
        assertEquals(0, request.getInt("part"))
        assertEquals("on my way", request.getString("text"))
        assertEquals(listOf("tel:+15559876543"), DirectWire.Target.fromJson(request.getJSONObject("target")).participants)
        val row = repo.message("imsg:E5")!!
        assertEquals("on my way", row.text)
        assertTrue(row.editedAtMs > 0)
        assertEquals(IMessageExtra(parts = mapOf(0 to "on my way"), edits = 1), IMessageExtra.parse(row.extra))
        assertEquals("the tapback stays", "❤️", row.reactions.single().emoji)
        assertEquals(DirectArchive.STATUS_SENT, row.status)
    }

    @Test
    fun anEditAppleConfirmsAfterTheMessageWasUnsentLeavesItUnsent() {
        val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())
        lateinit var link: DirectLink
        link = fakeLink(asked) { what, _ ->
            // Unsent on the user's Mac while Apple was asked to edit it.
            if (what == DirectWire.EDIT) link.apply(DirectArchive.Update.Unsent("imsg:E8", 0, fromMe = true, senderName = ""))
            DirectWire.ok()
        }
        link.apply(incoming("B0", bob, "hi"))
        sentHere("E8", "frist", ts = System.currentTimeMillis() - 30_000)
        runBlocking { link.edit("imsg:E8", "first") }
        assertEquals(DirectWire.EDIT, asked.single().first)
        val row = repo.message("imsg:E8")!!
        assertEquals("system", row.direction)
        assertEquals("You unsent a message.", row.text)
        assertEquals(0L, row.editedAtMs)
        assertEquals("You unsent a message.", repo.conversation("imsg:$bob")!!.snippet)
    }

    @Test
    fun anEditAppleRefusesOrTooLateChangesNothing() {
        val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())
        val link = fakeLink(asked) { _, _ -> DirectWire.error("Apple refused the edit: no") }
        link.apply(incoming("B0", bob, "hi"))
        val now = System.currentTimeMillis()
        sentHere("E6", "first", ts = now - 60_000)
        try {
            runBlocking { link.edit("imsg:E6", "second") }
            fail("refused, yet edited")
        } catch (e: DirectEngine.ImessageError) {
            assertEquals("Apple refused the edit: no", e.message)
            // The screen shows it in those words.
            assertEquals("Apple refused the edit: no", com.lightdecoder.ui.chat.ChatErrors.message(context, ChatService.IMESSAGE, e))
        }
        assertEquals("first", repo.message("imsg:E6")!!.text)
        assertEquals(0L, repo.message("imsg:E6")!!.editedAtMs)
        // Too late (16 minutes): not even asked, and the screen says so in its own words.
        asked.clear()
        sentHere("E7", "old", ts = now - 16 * 60_000L)
        try {
            runBlocking { link.edit("imsg:E7", "new") }
            fail("edited too late")
        } catch (e: com.lightdecoder.relay.RelayException) {
            assertEquals(com.lightdecoder.relay.RelayException.INVALID_ARGUMENT, e.code)
        }
        assertTrue(asked.isEmpty())
        // Someone else's: not the user's to edit.
        try {
            runBlocking { link.edit("imsg:B0", "hijacked") }
            fail("edited someone else's message")
        } catch (e: DirectEngine.ImessageError) {
            assertEquals(context.getString(com.lightdecoder.R.string.imessage_direct_edit_not_editable), e.message)
        }
        assertTrue(asked.isEmpty())
    }

    @Test
    fun aTapbackShowsAtOnceReplacesTheUsersEarlierOneAndIsTakenBackIfAppleRefusesIt() {
        val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())
        var refuse = false
        // The user's tapback as the archive has it while Apple is being asked.
        val whileAsked = ArrayList<String?>()
        val link = fakeLink(asked) { _, _ ->
            whileAsked += repo.message("imsg:T5")?.reactions?.firstOrNull { it.fromMe }?.emoji
            if (refuse) DirectWire.error("Apple refused the reaction: no") else DirectWire.guid("T-OWN")
        }
        link.apply(incoming("T5", bob, "we won"))
        runBlocking { link.react("imsg:T5", "❤️", remove = false) }
        assertEquals("shown before Apple answered", listOf<String?>("❤️"), whileAsked)
        assertEquals(listOf(com.lightdecoder.relay.ReactionDto("❤️", listOf("me"), fromMe = true)), repo.message("imsg:T5")!!.reactions)
        val (what, request) = asked.single()
        assertEquals(DirectWire.REACT, what)
        assertEquals("T5", request.getString("guid"))
        assertEquals(0, request.getInt("part"))
        assertEquals("❤️", request.getString("emoji"))
        assertTrue(request.getBoolean("enable"))
        assertEquals("we won", request.getString("targetText"))
        // Another one replaces it (Apple does the same on the other side).
        runBlocking { link.react("imsg:T5", "😂", remove = false) }
        assertEquals(listOf(com.lightdecoder.relay.ReactionDto("😂", listOf("me"), fromMe = true)), repo.message("imsg:T5")!!.reactions)
        assertTrue(asked.last().second.getBoolean("enable"))
        // Refused: back to how it was.
        refuse = true
        try {
            runBlocking { link.react("imsg:T5", "👍", remove = false) }
            fail("refused, yet kept")
        } catch (e: DirectEngine.ImessageError) {
            assertEquals("Apple refused the reaction: no", e.message)
        }
        assertEquals(listOf(com.lightdecoder.relay.ReactionDto("😂", listOf("me"), fromMe = true)), repo.message("imsg:T5")!!.reactions)
        try {
            runBlocking { link.react("imsg:T5", "😂", remove = true) }
            fail("refused, yet taken back")
        } catch (e: DirectEngine.ImessageError) {
            // As expected.
        }
        assertEquals("😂", repo.message("imsg:T5")!!.reactions.single().emoji)
        // Taken back: enable false, for that emoji.
        refuse = false
        runBlocking { link.react("imsg:T5", "😂", remove = true) }
        assertTrue(repo.message("imsg:T5")!!.reactions.isEmpty())
        assertEquals("😂", asked.last().second.getString("emoji"))
        assertFalse(asked.last().second.getBoolean("enable"))
        // A first one refused leaves none.
        refuse = true
        runCatching { runBlocking { link.react("imsg:T5", "‼️", remove = false) } }
        assertTrue(repo.message("imsg:T5")!!.reactions.isEmpty())
    }

    @Test
    fun aRefusedTapbackLeavesOneChosenSinceOnAnotherDevice() {
        val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())
        // The tapback the user puts on it on their iPhone while Apple is asked.
        var meanwhile = ""
        lateinit var link: DirectLink
        link = fakeLink(asked) { _, _ ->
            link.apply(DirectArchive.Update.Reaction("imsg:T6", "me", meanwhile, true, true, 7))
            DirectWire.error("Apple refused the reaction: no")
        }
        link.apply(incoming("T6", bob, "dinner?"))
        link.apply(DirectArchive.Update.Reaction("imsg:T6", "me", "😂", true, true, 5))
        fun mine() = repo.message("imsg:T6")!!.reactions
        // ❤️ for 😂 refused, 👍 chosen meanwhile: 👍 stays (not 😂 again).
        meanwhile = "👍"
        assertTrue(runCatching { runBlocking { link.react("imsg:T6", "❤️", remove = false) } }.isFailure)
        assertEquals(listOf(com.lightdecoder.relay.ReactionDto("👍", listOf("me"), fromMe = true)), mine())
        // 👍 taken back, refused, ‼️ chosen meanwhile: ‼️ stays (not 👍 again).
        meanwhile = "‼️"
        assertTrue(runCatching { runBlocking { link.react("imsg:T6", "👍", remove = true) } }.isFailure)
        assertEquals(listOf(com.lightdecoder.relay.ReactionDto("‼️", listOf("me"), fromMe = true)), mine())
    }

    @Test
    fun anUnsendFromHereLeavesYouUnsentAMessage() {
        val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())
        val link = fakeLink(asked)
        link.apply(incoming("B0", bob, "hi"))
        sentHere("V2", "wrong chat", ts = System.currentTimeMillis() - 10_000)
        runBlocking { link.unsend("imsg:V2") }
        assertEquals(DirectWire.UNSEND, asked.single().first)
        val row = repo.message("imsg:V2")!!
        assertEquals("system", row.direction)
        assertEquals("You unsent a message.", row.text)
        assertEquals("You unsent a message.", repo.conversation("imsg:$bob")!!.snippet)
        // One part of a message with more (a caption and an attachment, sent from the user's iPhone):
        // only that part goes, as Apple does. Unsend is not offered on such a message.
        directModeOn()
        val now = System.currentTimeMillis()
        repo.upsertMessage(
            com.lightdecoder.relay.MessageDto(
                id = "imsg:V3", conversationId = "imsg:$bob", fromMe = true, timestampMs = now - 10_000, direction = "out",
                status = DirectArchive.STATUS_SENT, statusCode = 1, transport = IMessageManager.TRANSPORT, text = "look",
                parts = listOf(
                    com.lightdecoder.relay.PartDto("0", "text", "look"),
                    com.lightdecoder.relay.PartDto("p1", "media", mediaId = "V3/p1", mime = "image/jpeg", fileName = "a.jpg"),
                ),
                extra = IMessageExtra(parts = mapOf(0 to "look")).toJson(),
            ),
        )
        assertFalse(link.canUnsend(repo.message("imsg:V3")!!, now))
        runBlocking { link.unsend("imsg:V3", part = 0) }
        val partly = repo.message("imsg:V3")!!
        assertEquals("out", partly.direction)
        assertEquals("", partly.text)
        assertEquals(listOf("p1"), partly.media.map { it.partId })
        assertEquals(setOf(0), IMessageExtra.parse(partly.extra).unsent)
        // A chat the library cannot reach: it says so, in words.
        repo.upsertMessage(
            com.lightdecoder.relay.MessageDto(
                id = "imsg:V4", conversationId = "imsg:group:members:a,b", fromMe = true, timestampMs = now, direction = "out",
                status = DirectArchive.STATUS_SENT, statusCode = 1, transport = IMessageManager.TRANSPORT, text = "hi all",
            ),
        )
        try {
            runBlocking { link.unsend("imsg:V4") }
            fail("unsent where it cannot reach")
        } catch (e: DirectEngine.ImessageError) {
            assertEquals(context.getString(com.lightdecoder.R.string.imessage_direct_unreachable), e.message)
        }
        assertEquals(listOf(DirectWire.UNSEND, DirectWire.UNSEND), asked.map { it.first })
    }

    @Test
    fun aTapbackOnTheUsersMessageIsAnnouncedOnce() {
        IMessagePrefs.setEnabled(context, true)
        IMessagePrefs.setNotifyMessages(context, true)
        val link = DirectLink.get(context)
        link.apply(incoming("B0", bob, "hi"))
        sentHere("A1", "see you at 6")
        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
        fun lines() = notifications.activeNotifications.filter { it.tag?.startsWith("im:") == true }
            .flatMap { androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it.notification)?.messages.orEmpty() }
            .map { it.text.toString() }
        MessageNotifier.cancelAll(context, ChatService.IMESSAGE)
        val love = event("R1", bob, PushChatMessage.React(ReactMessage("A1", 0UL, ReactMessageType.React(Reaction.Heart, true), "see you at 6", null)), participants = listOf(me, bob))
        link.apply(love)
        assertEquals(listOf("Loved “see you at 6”"), lines())
        // The same tapback handed over again (a restart) is not announced again.
        MessageNotifier.cancelAll(context, ChatService.IMESSAGE)
        link.apply(love)
        assertTrue(lines().isEmpty())
        // Nor one taken back, one on someone else's message, or the user's own from another device.
        link.apply(event("R2", bob, PushChatMessage.React(ReactMessage("A1", 0UL, ReactMessageType.React(Reaction.Heart, false), "", null)), participants = listOf(me, bob)))
        link.apply(event("R3", bob, PushChatMessage.React(ReactMessage("B0", 0UL, ReactMessageType.React(Reaction.Like, true), "", null)), participants = listOf(me, bob)))
        link.apply(event("R4", me, PushChatMessage.React(ReactMessage("A1", 0UL, ReactMessageType.React(Reaction.Laugh, true), "", null)), participants = listOf(me, bob)))
        assertTrue(lines().isEmpty())
        // Another emoji, as an iPhone words it.
        link.apply(event("R5", bob, PushChatMessage.React(ReactMessage("A1", 0UL, ReactMessageType.React(Reaction.Emoji("🔥"), true), "", null)), participants = listOf(me, bob)))
        assertEquals(listOf("Reacted 🔥 to “see you at 6”"), lines())
    }

    @Test
    fun theIMessageBackendEditsAndReactsOnlyThroughTheLibrary() {
        val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())
        val link = fakeLink(asked)
        val manager = IMessageManager.get(context)
        link.apply(incoming("B1", bob, "hi"))
        sentHere("M1", "mine", ts = System.currentTimeMillis() - 30_000)
        // Through OpenBubbles' notifications: neither (and no groups are started here).
        assertFalse(com.lightdecoder.chat.Capability.EDIT in manager.capabilities)
        assertFalse(com.lightdecoder.chat.Capability.NEW_GROUP in manager.capabilities)
        fun unsupported(block: suspend () -> Unit) = try {
            runBlocking { block() }
            fail("went through without the library")
        } catch (e: com.lightdecoder.relay.RelayException) {
            assertEquals("unsupported", e.code)
        }
        unsupported { manager.react("imsg:B1", "👍", remove = false) }
        unsupported { manager.editMessage("imsg:M1", "changed") }
        assertTrue(asked.isEmpty())
        // Through the library: both, to Apple.
        directModeOn()
        assertTrue(com.lightdecoder.chat.Capability.EDIT in manager.capabilities)
        runBlocking { manager.react("imsg:B1", "👍", remove = false) }
        runBlocking { manager.editMessage("imsg:M1", "  changed  ") }
        assertEquals(listOf(DirectWire.REACT, DirectWire.EDIT), asked.map { it.first })
        assertEquals("changed", repo.message("imsg:M1")!!.text)
        // Nothing left of the text: not an edit Apple is asked for.
        try {
            runBlocking { manager.editMessage("imsg:M1", "   ") }
            fail("edited to nothing")
        } catch (e: com.lightdecoder.relay.RelayException) {
            assertEquals(com.lightdecoder.relay.RelayException.INVALID_ARGUMENT, e.code)
        }
        assertEquals(2, asked.size)
        // A message known only from OpenBubbles' notifications has no GUID to put a tapback on.
        unsupported { manager.react("ld:t1", "👍", remove = false) }
    }

    @Test
    fun aTapbacksNoticeReadsAsOnAnIphone() {
        val quoted = "“hi”"
        assertEquals("Loved “hi”", DirectLink.tapbackNotice(context, "❤️", quoted))
        assertEquals("Liked “hi”", DirectLink.tapbackNotice(context, "👍", quoted))
        assertEquals("Disliked “hi”", DirectLink.tapbackNotice(context, "👎", quoted))
        assertEquals("Laughed at “hi”", DirectLink.tapbackNotice(context, "😂", quoted))
        assertEquals("Emphasized “hi”", DirectLink.tapbackNotice(context, "‼️", quoted))
        assertEquals("Questioned “hi”", DirectLink.tapbackNotice(context, "❓", quoted))
        assertEquals("Reacted 🎉 to a message", DirectLink.tapbackNotice(context, "🎉", "a message"))
    }

    // --- typing and read receipts out, sends that may have gone (phase 3) ----------------------------

    @Test
    fun theChatIsToldTheUserIsTypingAndThatTheyStopped() {
        directModeOn()
        val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())
        val link = DirectLink.installForTesting(context, typingIdleMs = 400) { what, request, _ ->
            asked += what to request
            if (what == DirectWire.SEND) DirectWire.sent("TS1", 1_700_000_005_000L) else DirectWire.ok()
        }
        link.apply(incoming("T0", bob, "hi"))
        val chat = "imsg:$bob"
        fun typings() = synchronized(asked) { asked.filter { it.first == DirectWire.TYPING }.map { it.second.getBoolean("typing") } }
        // Two keystrokes: "typing" once, to the chat's person; "stopped" once it is quiet.
        link.typing(chat)
        link.typing(chat)
        waitFor("typing") { typings().isNotEmpty() }
        assertEquals(true, typings().first())
        val typing = synchronized(asked) { asked.first { it.first == DirectWire.TYPING }.second }
        assertEquals(listOf(bob), DirectWire.Target.fromJson(typing.getJSONObject("target")).participants)
        waitFor("stopped, once quiet") { typings() == listOf(true, false) }
        // The composer emptied: stopped at once, once.
        link.typing(chat)
        waitFor("typing again") { typings().size == 3 }
        link.stopTyping(chat)
        link.stopTyping(chat)
        waitFor("stopped") { typings() == listOf(true, false, true, false) }
        // A message ends it: no "stopped" after it.
        link.typing(chat)
        waitFor("typing again") { typings().size == 5 }
        link.sendText(chat, "here", null)
        waitFor("sent") { repo.message("imsg:TS1") != null }
        Thread.sleep(600)
        assertEquals(listOf(true, false, true, false, true), typings())
    }

    @Test
    fun readingAChatTellsAppleOncePerLatestMessageTheyWrote() {
        directModeOn()
        val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())
        var refuse = true
        val link = fakeLink(asked) { what, _ -> if (what == DirectWire.READ && refuse) DirectWire.error("not connected") else DirectWire.ok() }
        link.apply(incoming("R1", bob, "first", ts = 1_700_000_000_000L))
        link.apply(incoming("R2", bob, "second", ts = 1_700_000_001_000L))
        sentHere("R3", "mine, after", ts = 1_700_000_002_000L)
        val chat = "imsg:$bob"
        assertTrue(repo.conversation(chat)!!.unread)
        // Refused (not connected): read here all the same, and asked again next time.
        runBlocking { link.markRead(chat) }
        assertFalse(repo.conversation(chat)!!.unread)
        refuse = false
        runBlocking { link.markRead(chat) }
        assertEquals(listOf(DirectWire.READ, DirectWire.READ), asked.map { it.first })
        assertEquals("their latest message, not the user's", "R2", asked.last().second.getString("guid"))
        // Once per message.
        runBlocking { link.markRead(chat) }
        assertEquals(2, asked.size)
        link.apply(incoming("R4", bob, "third", ts = 1_700_000_003_000L))
        runBlocking { link.markRead(chat) }
        assertEquals("R4", asked.last().second.getString("guid"))
        assertEquals(3, asked.size)
        // Through the IMessage backend the same.
        link.apply(incoming("R5", bob, "fourth", ts = 1_700_000_004_000L))
        runBlocking { IMessageManager.get(context).markRead(chat) }
        assertEquals("R5", asked.last().second.getString("guid"))
    }

    @Test
    fun theirReadReceiptKeepsWhenTheMessageWasRead() {
        com.lightdecoder.reactions.ReadTimes.resetForTesting()
        try {
            val link = DirectLink.get(context)
            link.apply(incoming("B0", bob, "hi"))
            sentHere("RT1", "mine")
            val before = System.currentTimeMillis()
            link.apply(event("RT1", bob, PushChatMessage.Read))
            assertEquals(DirectArchive.STATUS_READ, repo.message("imsg:RT1")!!.status)
            val times = com.lightdecoder.reactions.ReadTimes.get(context)
            val time = times.timeFor(ChatService.IMESSAGE, "imsg:RT1")!!
            assertTrue(time.atMs >= before)
            // Read again later (Apple's journal after a restart): the first time stays.
            link.apply(event("RT1", bob, PushChatMessage.Read))
            assertEquals(time, times.timeFor(ChatService.IMESSAGE, "imsg:RT1"))
            // Delivered only: no read time.
            sentHere("RT2", "mine too", ts = 1_700_000_001_000L)
            link.apply(event("RT2", bob, PushChatMessage.Delivered))
            assertNull(times.timeFor(ChatService.IMESSAGE, "imsg:RT2"))
        } finally {
            com.lightdecoder.reactions.ReadTimes.resetForTesting()
        }
    }

    @Test
    fun aSendThatMayHaveGoneAsksBeforeItGoesAgain() {
        DirectLink.get(context).apply(incoming("B0", bob, "hi"))
        val chat = "imsg:$bob"
        // Being sent when the app stopped.
        repo.insertLocalOutgoing("t-cut", chat, "cut short", null, null, null, null, NOW)
        repo.setLocalState("t-cut", com.lightdecoder.archive.LocalState.SENDING)
        directModeOn()
        DirectLink.installForTesting(context) { _, _, _ -> DirectWire.ok() }
        fun local(tmpId: String) = repo.message(MessageRow.tmpMessageId(tmpId))!!
        waitFor("marked failed") { local("t-cut").localState == com.lightdecoder.archive.LocalState.FAILED }
        assertTrue("interrupted: it may have gone", com.lightdecoder.ui.chat.MessageStatus.outcomeUnknown(local("t-cut")))
        // Apple did not confirm it in time: the same.
        val late = DirectLink.installForTesting(context) { what, _, _ ->
            if (what == DirectWire.SEND) DirectWire.failure(DirectEngine.Unconfirmed("Apple did not confirm the message within 45 seconds.")) else DirectWire.ok()
        }.sendText(chat, "late", null)
        waitFor("not confirmed") { local(late).localState == com.lightdecoder.archive.LocalState.FAILED }
        assertTrue(com.lightdecoder.ui.chat.MessageStatus.outcomeUnknown(local(late)))
        // Refused by Apple: it did not go, and goes again without asking.
        val refused = DirectLink.installForTesting(context) { what, _, _ ->
            if (what == DirectWire.SEND) DirectWire.error("Apple refused the message: no") else DirectWire.ok()
        }.sendText(chat, "refused", null)
        waitFor("refused") { local(refused).localState == com.lightdecoder.archive.LocalState.FAILED }
        assertFalse(com.lightdecoder.ui.chat.MessageStatus.outcomeUnknown(local(refused)))
    }

    @Test
    fun askingAppleAboutSomeoneSaysWhenTheLibraryCouldNotBeAsked() {
        directModeOn()
        var answer = DirectWire.error(DirectIpc.Client.UNREACHABLE)
        val link = DirectLink.installForTesting(context) { _, _, _ -> answer }
        try {
            runBlocking { link.conversationFor("+15557777777") }
            fail("not asked, yet answered")
        } catch (e: DirectEngine.ImessageError) {
            assertEquals(DirectIpc.Client.UNREACHABLE, e.message)
        }
        // Asked: not on iMessage.
        answer = JSONObject()
        assertNull(runBlocking { link.conversationFor("+15557777777") })
    }

    @Test
    fun throughTheLibraryTheIMessageBackendNeverHandsOff() {
        com.lightdecoder.imessage.IMessageLog.clear(context)
        directModeOn()
        val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())
        val link = fakeLink(asked) { what, _ -> if (what == DirectWire.REACH) JSONObject() else DirectWire.ok() }
        val manager = IMessageManager.get(context)
        link.apply(incoming("B0", bob, "hi"))
        val chat = "imsg:$bob"
        // Typing goes to Apple.
        manager.typing(chat)
        waitFor("typing") { synchronized(asked) { asked.any { it.first == DirectWire.TYPING } } }
        // Nothing opens the OpenBubbles app.
        assertFalse(manager.handOff(context, chat, "hi"))
        assertEquals(com.lightdecoder.imessage.OpenBubbles.NewChat.FAILED, manager.startInOpenBubbles(context, "+15559876543"))
        // Attachments go to Apple too: one that cannot be read says so, in words, and nothing is stored.
        try {
            manager.sendMedia(chat, android.net.Uri.parse("content://x/1"), null, null)
            fail("sent a file it could not read")
        } catch (e: DirectEngine.ImessageError) {
            assertEquals(context.getString(com.lightdecoder.R.string.imessage_direct_attachment_unreadable), com.lightdecoder.ui.chat.ChatErrors.message(context, ChatService.IMESSAGE, e))
        }
        assertTrue(repo.messages(chat).none { it.isLocal })
        // A chat the library cannot reach: kept here as failed, not sent through a Reply button.
        repo.upsertConversation(
            ConversationDto(
                id = "iMessage;+;chat123", isGroup = true, name = "Trip", type = IMessageManager.TRANSPORT,
                participants = listOf(
                    ParticipantDto(id = "me", isMe = true),
                    ParticipantDto(id = "+15551234567", number = "+15551234567"),
                    ParticipantDto(id = "+15559876543", number = "+15559876543"),
                ),
            ),
        )
        try {
            manager.sendText("iMessage;+;chat123", "hi all", null)
            fail("sent to a chat the library cannot reach")
        } catch (e: com.lightdecoder.relay.RelayException) {
            assertEquals(com.lightdecoder.imessage.IMessageErrors.NO_REPLY_BUTTON, e.code)
        }
        val failed = repo.messages("iMessage;+;chat123").single()
        assertEquals(com.lightdecoder.archive.LocalState.FAILED, failed.localState)
        // Tried again: still nothing to send it through, and no Reply button tried.
        manager.retry(failed.tmpId)
        assertEquals(failed, repo.messages("iMessage;+;chat123").single())
        assertTrue("nothing asked of the library for it", synchronized(asked) { asked.none { it.first == DirectWire.SEND } })
        // New chats: with one person once Apple says they have iMessage; a group once Apple says each has.
        try {
            runBlocking { manager.startConversation(listOf("+15557777777"), null) }
            fail("a chat with someone not on iMessage")
        } catch (e: DirectEngine.ImessageError) {
            assertEquals("+15557777777 is not on iMessage.", e.message)
        }
        try {
            runBlocking { manager.startConversation(listOf("+15557777777", "+15558888888"), "Trip") }
            fail("a group with people not on iMessage")
        } catch (e: DirectEngine.ImessageError) {
            assertEquals("+15557777777, +15558888888 are not on iMessage, so the group can't be started.", e.message)
        }
        assertEquals(chat, runBlocking { manager.startConversation(listOf("+1 555 987 6543"), null) })
        // What the logs and the problem report say of it names the library, not OpenBubbles.
        assertTrue(manager.setupSummary(), manager.setupSummary().startsWith("iMessage library"))
        // What the library's own process last said, not a fixed "registered with Apple".
        assertTrue(manager.setupSummary(), manager.setupSummary().endsWith("last status: none yet"))
        DirectPrefs.setLastStatus(context, DirectEngine.describe(DirectEngine.Status.Retrying("busy", 30)))
        assertTrue(manager.setupSummary(), manager.setupSummary().contains("last status: not connected, Apple's library tries again in 30 s: busy ("))
        assertFalse(com.lightdecoder.imessage.IMessageLog.read(context).any { it.contains("OpenBubbles") })
    }

    /** Apple's answer to whether [handle] has iMessage: their chat, as the library describes it, or nothing. */
    private fun reached(handle: String): JSONObject = JSONObject().put(
        "conversation",
        ConversationDto(
            id = "imsg:$handle", type = DirectArchive.TRANSPORT, defaultOutgoingId = me,
            participants = listOf(
                ParticipantDto(id = handle, number = Handles.display(handle)),
                ParticipantDto(id = me, isMe = true),
            ),
            otherParticipantIds = listOf(handle),
        ).toJson(),
    )

    @Test
    fun aGroupStartsOnceAppleSaysEveryoneIsOnIMessageAndWhatGoesToItCarriesItsGuid() {
        directModeOn()
        val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())
        val onIMessage = setOf(alice, bob)
        val link = fakeLink(asked) { what, request ->
            when (what) {
                DirectWire.REACH -> Handles.normalize(request.getString("recipient"))!!.let { if (it in onIMessage) reached(it) else JSONObject() }
                DirectWire.SEND -> DirectWire.sent("GS-1", NOW)
                else -> DirectWire.ok()
            }
        }
        val manager = IMessageManager.get(context)
        assertTrue(com.lightdecoder.chat.Capability.NEW_GROUP in manager.capabilities)
        // Someone not on iMessage: nothing is made, and the error names them.
        try {
            runBlocking { manager.startConversation(listOf("+1 555 123 4567", "+15557777777"), null) }
            fail("a group with someone not on iMessage")
        } catch (e: DirectEngine.ImessageError) {
            assertEquals("+15557777777 is not on iMessage, so the group can't be started.", e.message)
        }
        try {
            runBlocking { manager.startConversation(listOf("+15551234567", "not an address"), null) }
            fail("a group with something that is no address")
        } catch (e: DirectEngine.ImessageError) {
            assertEquals("not an address is not on iMessage, so the group can't be started.", e.message)
        }
        assertTrue(repo.conversations(null).none { it.isGroup })
        val id = runBlocking { manager.startConversation(listOf("+1 555 123 4567", "+15559876543"), null) }
        assertTrue(id, Regex("imsg:group:[0-9A-F-]{36}").matches(id))
        val row = repo.conversation(id)!!
        assertTrue(row.isGroup)
        assertEquals("+15551234567, +15559876543", row.name)
        assertEquals(me, row.defaultOutgoingId)
        assertEquals(setOf("me", "+15551234567", "+15559876543"), repo.participants(id).map { it.id }.toSet())
        assertEquals("me", repo.participants(id).single { it.isMe }.id)
        // What goes to it carries its GUID, and no name: their iPhones show it by its members too.
        val target = link.targetOf(id)!!
        assertEquals(DirectArchive.groupGuid(id), target.groupGuid)
        assertNull(target.groupName)
        assertEquals(setOf(alice, bob), target.participants.toSet())
        assertTrue(link.canSend(id))
        link.sendText(id, "hi all", null)
        waitFor("sent to Apple") { repo.message("imsg:GS-1") != null }
        val sent = synchronized(asked) { asked.single { it.first == DirectWire.SEND } }.second.getJSONObject("target")
        assertEquals(DirectArchive.groupGuid(id), sent.getString("groupGuid"))
        assertTrue(sent.isNull("groupName"))
        // The same people again: the same group. Named: a group of its own, whose name goes with what is sent to it.
        assertEquals(id, runBlocking { manager.startConversation(listOf("+15559876543", "+15551234567"), null) })
        val named = runBlocking { manager.startConversation(listOf("+15559876543", "+15551234567"), " Trip ") }
        assertFalse(named == id)
        assertEquals("Trip", repo.conversation(named)!!.name)
        assertEquals("Trip", link.targetOf(named)!!.groupName)
        // One person twice: a chat with them.
        assertEquals("imsg:$alice", runBlocking { link.startGroup(listOf("+15551234567", "(555) 123-4567"), null) })
    }

    @Test
    fun aGroupFollowsItsRenamesAndMembersAndWhatIsSentCarriesOnlyItsOwnName() {
        val link = DirectLink.get(context)
        val carol = "tel:+15550001111"
        val members = listOf(me, bob, alice, carol)
        val id = "imsg:group:G-N"
        link.apply(
            event(
                "GN1", bob,
                PushChatMessage.Message(
                    NormalMessage(
                        listOf(IndexedMessagePart(MessagePart.Text("hi all", TextFormat.Flags(TextFlags(false, false, false, false))), null, null)),
                        null, null, null, MessageType.IMessage, null, null, null, false, null, null,
                    ),
                ),
                members, "G-N",
            ),
        )
        // Shown by its members' names, sent with none.
        assertEquals(setOf("+15559876543", "+15551234567", "+15550001111"), repo.conversation(id)!!.name.split(", ").toSet())
        assertNull(link.targetOf(id)!!.groupName)
        link.apply(event("GN2", bob, PushChatMessage.RenameMessage(ChatRenameMessage("Ski trip")), members, "G-N", ts = 1_700_000_010_000L))
        assertEquals("Ski trip", repo.conversation(id)!!.name)
        assertEquals("Ski trip", link.targetOf(id)!!.groupName)
        // Someone leaves: the group follows (its name with it), and the user is still in it.
        link.apply(
            event(
                "GN3", carol, PushChatMessage.ChangeParticipants(uniffi.rust_bridge.ChangeParticipantMessage(listOf(me, bob, alice), 2UL)), members, "G-N",
                ts = 1_700_000_011_000L,
            ),
        )
        assertEquals(setOf("me", "+15559876543", "+15551234567"), repo.participants(id).map { it.id }.toSet())
        assertEquals("Ski trip", repo.conversation(id)!!.name)
        // The user removed from it: still "me" in it here, with the others.
        link.apply(
            event(
                "GN4", bob, PushChatMessage.ChangeParticipants(uniffi.rust_bridge.ChangeParticipantMessage(listOf(bob, alice), 3UL)), listOf(me, bob, alice), "G-N",
                ts = 1_700_000_012_000L,
            ),
        )
        assertEquals("me", repo.participants(id).single { it.isMe }.id)
        assertEquals(setOf("me", "+15559876543", "+15551234567"), repo.participants(id).map { it.id }.toSet())
        assertEquals("+15559876543 removed you.", repo.message("imsg:GN4")!!.text)
        assertEquals(id, repo.message("imsg:GN4")!!.conversationId)
        // Events without a name (members changed) leave it as it was.
        assertEquals("Ski trip", repo.conversation(id)!!.name)
        assertEquals("Ski trip", link.targetOf(id)!!.groupName)
    }

    private fun says(text: String) = PushChatMessage.Message(
        NormalMessage(
            listOf(IndexedMessagePart(MessagePart.Text(text, TextFormat.Flags(TextFlags(false, false, false, false))), null, null)),
            null, null, null, MessageType.IMessage, null, null, null, false, null, null,
        ),
    )

    @Test
    fun aGroupsNameIsTakenOffOnlyByARenameAndNeverPutBackByAnOlderMessage() {
        val link = DirectLink.get(context)
        val members = listOf(me, bob, alice)
        val id = "imsg:group:G-K"
        link.apply(event("GK1", bob, says("hi"), members, "G-K", ts = 1_700_000_001_000L, groupName = "Old"))
        link.apply(event("GK2", bob, PushChatMessage.RenameMessage(ChatRenameMessage("New")), members, "G-K", ts = 1_700_000_002_000L))
        // A message with no name says nothing about it.
        link.apply(event("GK3", alice, says("yo"), members, "G-K", ts = 1_700_000_003_000L))
        assertEquals("New", repo.conversation(id)!!.name)
        assertEquals("New", link.targetOf(id)!!.groupName)
        // One from before the rename, come late: the name stays.
        link.apply(event("GK0", alice, says("late"), members, "G-K", ts = 1_700_000_000_500L, groupName = "Old"))
        assertEquals("New", repo.conversation(id)!!.name)
        assertEquals("New", link.targetOf(id)!!.groupName)
        assertEquals(id, repo.message("imsg:GK0")!!.conversationId)
        // A rename to nothing takes it off: shown by its members, sent with none.
        link.apply(event("GK4", bob, PushChatMessage.RenameMessage(ChatRenameMessage("")), members, "G-K", ts = 1_700_000_004_000L))
        assertEquals(setOf("+15559876543", "+15551234567"), repo.conversation(id)!!.name.split(", ").toSet())
        assertNull(link.targetOf(id)!!.groupName)
        link.apply(event("GK5", bob, says("again"), members, "G-K", ts = 1_700_000_005_000L))
        assertNull(link.targetOf(id)!!.groupName)
    }

    @Test
    fun aGroupStoredBeforeItsOwnNameWasKeptIsNotSentItsMembersNames() {
        val link = DirectLink.get(context)
        fun stored(id: String, name: String) = repo.upsertConversation(
            ConversationDto(
                id = id, name = name, isGroup = true, type = IMessageManager.TRANSPORT,
                participants = listOf(
                    ParticipantDto(id = "me", isMe = true),
                    ParticipantDto(id = "+15551234567", name = "Alice", number = "+15551234567"),
                    ParticipantDto(id = "+15559876543", name = "Bob", number = "+15559876543"),
                ),
            ),
        )
        stored("imsg:group:OLD-1", "Alice, Bob")
        stored("imsg:group:OLD-2", "Book club")
        assertNull(link.targetOf("imsg:group:OLD-1")!!.groupName)
        assertEquals("Book club", link.targetOf("imsg:group:OLD-2")!!.groupName)
    }

    @Test
    fun aGroupDownToOneOtherPersonStaysTheGroup() {
        val link = DirectLink.get(context)
        val id = "imsg:group:G-S"
        link.apply(event("GS1", bob, says("hi"), listOf(me, bob, alice), "G-S", groupName = "Pair"))
        link.apply(
            event("GS2", alice, PushChatMessage.ChangeParticipants(uniffi.rust_bridge.ChangeParticipantMessage(listOf(me, bob), 2UL)), listOf(me, bob, alice), "G-S"),
        )
        assertEquals(setOf("me", "+15559876543"), repo.participants(id).map { it.id }.toSet())
        // What follows in it is the group's, not a chat with Bob.
        link.apply(event("GS3", bob, says("just us"), listOf(me, bob), "G-S", ts = 1_700_000_010_000L))
        assertEquals(id, repo.message("imsg:GS3")!!.conversationId)
        assertNull(repo.conversation("imsg:$bob"))
        assertTrue(repo.conversation(id)!!.isGroup)
        assertEquals("Pair", repo.conversation(id)!!.name)
        // Someone added back to it: the group's event.
        link.apply(
            event("GS4", bob, PushChatMessage.ChangeParticipants(uniffi.rust_bridge.ChangeParticipantMessage(listOf(me, bob, alice), 3UL)), listOf(me, bob), "G-S", ts = 1_700_000_011_000L),
        )
        assertEquals(id, repo.message("imsg:GS4")!!.conversationId)
        assertEquals(setOf("me", "+15559876543", "+15551234567"), repo.participants(id).map { it.id }.toSet())
        val target = link.targetOf(id)!!
        assertEquals("G-S", target.groupGuid)
        assertEquals("Pair", target.groupName)
        // A chat with one person that has a guid of its own is a chat with them, and their events are nothing.
        link.apply(event("GS5", alice, says("solo"), listOf(me, alice), "ALICE-CHAT"))
        assertEquals("imsg:$alice", repo.message("imsg:GS5")!!.conversationId)
        link.apply(event("GS6", alice, PushChatMessage.RenameMessage(ChatRenameMessage("x")), listOf(me, alice), "ALICE-CHAT"))
        assertNull(repo.message("imsg:GS6"))
    }

    @Test
    fun aMessageGoesWithItsEffectAndKeepsItWhenSentAgain() {
        DirectLink.get(context).apply(incoming("B0", bob, "hi"))
        directModeOn()
        val slam = "com.apple.MobileSMS.expressivesend.impact"
        val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())
        val refuse = java.util.concurrent.atomic.AtomicBoolean(true)
        val link = fakeLink(asked) { what, _ ->
            when {
                what != DirectWire.SEND -> DirectWire.ok()
                refuse.get() -> DirectWire.error("Apple refused the message: no")
                else -> DirectWire.sent("FX-1", NOW)
            }
        }
        val chat = "imsg:$bob"
        val tmpId = IMessageManager.get(context).sendText(chat, "boom", null, slam)
        fun local() = repo.message(MessageRow.tmpMessageId(tmpId))!!
        waitFor("refused") { local().localState == com.lightdecoder.archive.LocalState.FAILED }
        assertEquals(slam, IMessageExtra.parse(local().extra).effect)
        assertEquals(slam, synchronized(asked) { asked.single { it.first == DirectWire.SEND } }.second.getString("effect"))
        refuse.set(false)
        assertTrue(link.retry(tmpId))
        waitFor("sent") { repo.message("imsg:FX-1") != null }
        assertEquals(slam, IMessageExtra.parse(repo.message("imsg:FX-1")!!.extra).effect)
        val sends = synchronized(asked) { asked.filter { it.first == DirectWire.SEND } }
        assertEquals(listOf(slam, slam), sends.map { it.second.getString("effect") })
        // Without an effect, none goes.
        link.sendText(chat, "plain", null)
        waitFor("sent plain") { synchronized(asked) { asked.count { it.first == DirectWire.SEND } } == 3 }
        assertTrue(synchronized(asked) { asked.last { it.first == DirectWire.SEND } }.second.isNull("effect"))
    }

    @Test
    fun invisibleInkStaysHiddenInTheNotification() {
        IMessagePrefs.setEnabled(context, true)
        IMessagePrefs.setNotifyMessages(context, true)
        val link = DirectLink.get(context)
        fun said() = shadowOf(context.getSystemService(NotificationManager::class.java)).activeNotifications
            .filter { it.tag?.startsWith("im:") == true }
            .flatMap { androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it.notification)?.messages.orEmpty() }
            .map { it.text.toString() }
        link.apply(
            event(
                "INK1", bob,
                PushChatMessage.Message(
                    NormalMessage(
                        listOf(IndexedMessagePart(MessagePart.Text("the secret", TextFormat.Flags(TextFlags(false, false, false, false))), null, null)),
                        IMessageExtra.INVISIBLE_INK, null, null, MessageType.IMessage, null, null, null, false, null, null,
                    ),
                ),
            ),
        )
        assertEquals(listOf("Sent with Invisible Ink"), said())
        assertEquals("the secret", repo.message("imsg:INK1")!!.text)
    }

    @Test
    fun aTypingNoticeOvertakenByAMessageIsDroppedOrFollowedByStopped() {
        directModeOn()
        val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())
        val slow = java.util.concurrent.CountDownLatch(1)
        val link = DirectLink.installForTesting(context, typingIdleMs = 60_000) { what, request, _ ->
            asked += what to request
            when (what) {
                // The first "typing" takes a while to go (the session coming up).
                DirectWire.TYPING -> {
                    if (synchronized(asked) { asked.count { it.first == DirectWire.TYPING } } == 1) slow.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    DirectWire.ok()
                }
                DirectWire.SEND -> DirectWire.sent("G-" + request.getString("text"), 1_700_000_005_000L)
                else -> DirectWire.ok()
            }
        }
        link.apply(incoming("A0", alice, "hi"))
        link.apply(incoming("B0", bob, "hi"))
        val toAlice = "imsg:$alice"
        val toBob = "imsg:$bob"
        fun typings() = synchronized(asked) {
            asked.filter { it.first == DirectWire.TYPING }
                .map { DirectWire.Target.fromJson(it.second.getJSONObject("target")).participants.single() to it.second.getBoolean("typing") }
        }
        // Typing to Alice is on its way; typing to Bob waits behind it.
        link.typing(toAlice)
        waitFor("typing to Alice asked") { typings().isNotEmpty() }
        link.typing(toBob)
        // A message to each goes before either typing is through.
        link.sendText(toBob, "b", null)
        link.sendText(toAlice, "a", null)
        waitFor("both sent") { repo.message("imsg:G-a") != null && repo.message("imsg:G-b") != null }
        slow.countDown()
        // Alice's "typing" may have reached her after the message: "stopped" follows it. Bob's,
        // still waiting, never goes. (Typing to Alice again shows Bob's turn has been passed.)
        waitFor("stopped after the message") { typings().size == 2 }
        link.typing(toAlice)
        waitFor("typing again") { typings().size == 3 }
        assertEquals(listOf(alice to true, alice to false, alice to true), typings())
    }

    @Test
    fun aReadReceiptThatFailedOrWasLeftGoesAgainAndOneOnItsWayGoesOnWhenTheScreenCloses() {
        directModeOn()
        val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())
        var fail = true
        val held = java.util.concurrent.CountDownLatch(1)
        val link = fakeLink(asked) { what, _ ->
            if (what == DirectWire.READ && fail) throw IllegalStateException("the other process went away")
            if (what == DirectWire.READ) held.await(5, java.util.concurrent.TimeUnit.SECONDS)
            DirectWire.ok()
        }
        link.apply(incoming("RR1", bob, "first"))
        val chat = "imsg:$bob"
        // It failed: not counted as gone.
        runBlocking { link.markRead(chat) }
        fail = false
        // The screen that asked closes while Apple is being told: it is told all the same, and counted.
        val reading = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { link.markRead(chat) }
        waitFor("asked again") { asked.size == 2 }
        reading.cancel()
        held.countDown()
        runBlocking { reading.join() }
        runBlocking { link.markRead(chat) }
        assertEquals("tried again after the failure, then once", 2, asked.count { it.first == DirectWire.READ })
    }

    @Test
    fun aReadTimeIsKeptOnlyWhenTheReceiptMakesTheMessageRead() {
        com.lightdecoder.reactions.ReadTimes.resetForTesting()
        try {
            val link = DirectLink.get(context)
            link.apply(incoming("B0", bob, "hi"))
            sentHere("OLD", "read before read times were kept")
            repo.advanceMessageStatus(listOf("imsg:OLD"), DirectArchive.STATUS_READ, 11, DirectArchive::statusRank)
            // Apple's journal hands the receipt over again after a restart: no time is made up for it.
            link.apply(event("OLD", bob, PushChatMessage.Read))
            assertNull(com.lightdecoder.reactions.ReadTimes.get(context).timeFor(ChatService.IMESSAGE, "imsg:OLD"))
            // One the receipt makes read: when.
            sentHere("NEW", "mine, sent just now", ts = 1_700_000_001_000L)
            link.apply(event("NEW", bob, PushChatMessage.Delivered))
            assertNull(com.lightdecoder.reactions.ReadTimes.get(context).timeFor(ChatService.IMESSAGE, "imsg:NEW"))
            link.apply(event("NEW", bob, PushChatMessage.Read))
            assertNotNull(com.lightdecoder.reactions.ReadTimes.get(context).timeFor(ChatService.IMESSAGE, "imsg:NEW"))
        } finally {
            com.lightdecoder.reactions.ReadTimes.resetForTesting()
        }
    }

    @Test
    fun appleIsAskedAboutAConversationsPersonOnceInAWhile() {
        directModeOn()
        val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())
        var answer: JSONObject = DirectWire.error(DirectIpc.Client.UNREACHABLE)
        val link = fakeLink(asked) { _, _ -> answer }
        val reaches = { asked.count { it.first == DirectWire.REACH } }
        // Could not be asked: asked again next time.
        assertNull(runBlocking { link.reachOnce("+1 555 777 7777", now = 1_000) })
        answer = JSONObject()
        assertNull(runBlocking { link.reachOnce("+1 555 777 7777", now = 2_000) })
        assertEquals(2, reaches())
        // Not on iMessage: not asked again for a while (also not under another spelling of the number).
        assertNull(runBlocking { link.reachOnce("+15557777777", now = 3_000) })
        assertEquals(2, reaches())
        assertNull("no address", runBlocking { link.reachOnce("Mom", now = 3_000) })
        assertEquals(2, reaches())
        // Later they may have got it: asked again, and their chat made, at the bottom of the list
        // until something is written in it.
        answer = JSONObject().put(
            "conversation",
            ConversationDto(
                id = "imsg:tel:+15557777777", name = "+15557777777", type = DirectArchive.TRANSPORT, lastMessageTs = 1_700_000_000_000L,
                participants = listOf(
                    ParticipantDto(id = "tel:+15557777777", number = "+15557777777"),
                    ParticipantDto(id = me, isMe = true),
                ),
            ).toJson(),
        )
        val chat = runBlocking { link.reachOnce("+15557777777", now = 2_000 + DirectLink.REACH_AGAIN_MS) }
        assertEquals("imsg:tel:+15557777777", chat)
        assertEquals(3, reaches())
        assertEquals(0L, repo.conversation(chat!!)!!.lastMessageTs)
        assertTrue(link.canSend(chat))
    }

    @Test
    fun theLightSaysHowAConversationSendsThroughTheLibrary() {
        directModeOn()
        IMessagePrefs.setEnabled(context, true)
        val link = fakeLink(Collections.synchronizedList(ArrayList()))
        link.apply(incoming("B0", bob, "hi"))
        repo.upsertConversation(
            ConversationDto(
                id = "iMessage;+;chat123", isGroup = true, name = "Trip", type = IMessageManager.TRANSPORT,
                participants = listOf(
                    ParticipantDto(id = "me", isMe = true),
                    ParticipantDto(id = "+15551234567", number = "+15551234567"),
                    ParticipantDto(id = "+15559876543", number = "+15559876543"),
                ),
            ),
        )
        assertEquals(
            IMessageLight.Reading(IMessageLight.State.ACTIVE, com.lightdecoder.R.string.imessage_light_direct),
            IMessageLight.forConversation(context, "imsg:$bob", isGroup = false),
        )
        // A chat the library cannot reach (a group from OpenBubbles' notifications): a problem, not "one tap in OpenBubbles".
        assertEquals(
            IMessageLight.Reading(IMessageLight.State.PROBLEM, com.lightdecoder.R.string.imessage_light_unreachable),
            IMessageLight.forConversation(context, "iMessage;+;chat123", isGroup = true),
        )
        // A Messages group: texts, and where iMessage groups are.
        assertEquals(
            IMessageLight.Reading(IMessageLight.State.OFF, com.lightdecoder.R.string.imessage_light_group_direct),
            IMessageLight.forConversation(context, null, isGroup = true),
        )
        assertEquals(
            IMessageLight.Reading(IMessageLight.State.OFF, com.lightdecoder.R.string.imessage_light_no_chat),
            IMessageLight.forConversation(context, null, isGroup = false),
        )
    }

    /** Polls, for work the link does on its own threads. */
    private fun waitFor(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    companion object {
        private const val DB = "direct-link-test.db"
        private const val NOW = 1_700_000_100_000L
    }
}
