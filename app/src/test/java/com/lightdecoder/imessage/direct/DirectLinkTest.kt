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
import java.io.File
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
    ) =
        DirectArchive.map(
            MessageInst(guid, from, ConversationData(participants, null, groupGuid, null), m, ts.toULong(), null, false, false, null),
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
    }
}
