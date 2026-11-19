package com.lightdecoder.imessage.direct

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.ParticipantRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.rust_bridge.Balloon
import uniffi.rust_bridge.BalloonLayout
import uniffi.rust_bridge.ChangeParticipantMessage
import uniffi.rust_bridge.ChatRenameMessage
import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.EditMessage
import uniffi.rust_bridge.ErrorMessage
import uniffi.rust_bridge.ExtensionApp
import uniffi.rust_bridge.IconChangeMessage
import uniffi.rust_bridge.IndexedMessagePart
import uniffi.rust_bridge.LinkMeta
import uniffi.rust_bridge.LpLinkMetadata
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MessagePart
import uniffi.rust_bridge.MessageType
import uniffi.rust_bridge.MmcsFile
import uniffi.rust_bridge.NormalMessage
import uniffi.rust_bridge.NsDictionaryClass
import uniffi.rust_bridge.NsUrl
import uniffi.rust_bridge.PushChatMessage
import uniffi.rust_bridge.ReactMessage
import uniffi.rust_bridge.ReactMessageType
import uniffi.rust_bridge.Reaction
import uniffi.rust_bridge.TextFlags
import uniffi.rust_bridge.TextFormat
import uniffi.rust_bridge.UnsendMessage

/** iMessages in the Messages tab's archive: ids, and what each event from Apple changes. */
@RunWith(AndroidJUnit4::class)
class DirectArchiveTest {

    private val me = "tel:+15550000001"
    private val bob = "tel:+15550000002"
    private val carol = "mailto:carol@example.com"
    private val dave = "tel:+15550000004"
    private val names = mapOf(bob to "Bob", carol to "Carol", dave to "Dave")
    private fun nameOf(h: String) = names[h] ?: Handles.display(h)

    private fun text(s: String, idx: Int? = null) =
        IndexedMessagePart(MessagePart.Text(s, TextFormat.Flags(TextFlags(false, false, false, false))), idx?.toULong(), null)

    private fun attachment(name: String, idx: Int? = null) = IndexedMessagePart(
        MessagePart.Attachment(uniffi.rust_bridge.MessageAttachment(uniffi.rust_bridge.AttachmentType.Inline(byteArrayOf()), 0UL, "public.jpeg", "image/jpeg", name, false)),
        idx?.toULong(), null,
    )

    private fun normal(parts: List<IndexedMessagePart>, reply: String? = null, effect: String? = null, voice: Boolean = false, app: ExtensionApp? = null, link: LinkMeta? = null) =
        NormalMessage(parts, effect, reply, null, MessageType.IMessage, null, app, link, voice, null, null)

    private fun message(id: String, sender: String?, participants: List<String>, body: String, groupGuid: String? = null, name: String? = null, reply: String? = null) =
        MessageInst(
            id, sender, ConversationData(participants, name, groupGuid, null),
            PushChatMessage.Message(normal(listOf(text(body)), reply)),
            1_700_000_000_000UL, null, false, false, null,
        )

    private fun inst(id: String, sender: String?, m: PushChatMessage, participants: List<String> = listOf(me, bob), groupGuid: String? = null, name: String? = null, unverified: Boolean = false) =
        MessageInst(id, sender, ConversationData(participants, name, groupGuid, null), m, 1_700_000_005_000UL, null, false, unverified, null)

    private fun map(i: MessageInst) = DirectArchive.map(i, setOf(me), ::nameOf, TestTexts)

    @Test
    fun idsNeverLookLikeGoogleMessagesIds() {
        assertTrue(DirectArchive.isImessage("imsg:tel:+1555"))
        assertFalse(DirectArchive.isImessage("12345"))
        assertFalse(DirectArchive.isImessage("tmp:abc"))
        assertEquals("imsg:ABC-123", DirectArchive.messageId("ABC-123"))
        assertEquals("ABC-123", DirectArchive.guidOf("imsg:ABC-123"))
        assertNull(DirectArchive.guidOf("98765"))
        assertEquals("p2", DirectArchive.partId(2))
        assertEquals("ABC/p2", DirectArchive.mediaId("ABC", 2))
    }

    @Test
    fun aOneToOneConversationIsKeyedByTheOtherPerson() {
        assertEquals("imsg:$bob", DirectArchive.conversationId(listOf(me, bob), setOf(me), null))
        assertEquals("imsg:$bob", DirectArchive.conversationId(listOf(bob, me), setOf(me), "guid-ignored"))
        assertFalse(DirectArchive.isGroup("imsg:$bob"))
    }

    @Test
    fun aGroupIsKeyedByItsGuid() {
        val id = DirectArchive.conversationId(listOf(me, bob, carol), setOf(me), "G-1")
        assertEquals("imsg:group:G-1", id)
        assertTrue(DirectArchive.isGroup(id))
        assertEquals("G-1", DirectArchive.groupGuid(id))
        val noGuid = DirectArchive.conversationId(listOf(me, carol, bob), setOf(me), null)
        assertTrue(DirectArchive.isGroup(noGuid))
        assertNull(DirectArchive.groupGuid(noGuid))
    }

    @Test
    fun anIncomingMessageBecomesAConversationAndAMessage() {
        val u = map(message("M1", bob, listOf(me, bob), "hello", reply = "M0"))
        u as DirectArchive.Update.Upsert
        assertEquals("imsg:$bob", u.conversation.id)
        assertEquals("Bob", u.conversation.name)
        assertEquals(me, u.conversation.defaultOutgoingId)
        assertEquals(setOf(me, bob), u.conversation.participants.map { it.id }.toSet())
        assertTrue(u.conversation.participants.first { it.id == me }.isMe)
        with(u.message) {
            assertEquals("imsg:M1", id)
            assertEquals("imsg:$bob", conversationId)
            assertFalse(fromMe)
            assertEquals("in", direction)
            assertEquals("hello", text)
            assertEquals("Bob", senderName)
            assertEquals("imsg:M0", replyToId)
            assertEquals(1_700_000_000_000L, timestampMs)
            assertEquals(DirectArchive.TRANSPORT, transport)
            assertEquals(mapOf(0 to "hello"), IMessageExtra.parse(extra).parts)
        }
    }

    @Test
    fun aCopyOfWhatTheUserSentFromAnotherDeviceIsOutgoing() {
        val u = map(message("M2", me, listOf(me, bob), "sent from my Mac"))
        u as DirectArchive.Update.Upsert
        assertEquals("imsg:$bob", u.conversation.id)
        assertTrue(u.message.fromMe)
        assertEquals("out", u.message.direction)
        assertEquals(DirectArchive.STATUS_SENT, u.message.status)
    }

    @Test
    fun aGroupTakesTheNameItsMembersGaveIt() {
        val named = map(message("M3", bob, listOf(me, bob, carol), "hi all", "G-2", "Trip"))
        assertEquals("Trip", (named as DirectArchive.Update.Upsert).conversation.name)
        assertTrue(named.conversation.isGroup)
        // None: the main process names it after its members, with their contact names.
        val unnamed = map(message("M4", bob, listOf(me, bob, carol), "hi", "G-3"))
        assertEquals("", (unnamed as DirectArchive.Update.Upsert).conversation.name)
        // A name with a comma is still a name.
        val commas = map(message("M5", bob, listOf(me, bob, carol), "hi", "G-4", "Mom, Dad & me"))
        assertEquals("Mom, Dad & me", (commas as DirectArchive.Update.Upsert).conversation.name)
    }

    @Test
    fun partsKeepTheirIndexesAndRunsOfOnePartStayOnOneLine() {
        // "Hello " plain and "world" bold are one part; the picture is part 1; the caption after it part 2.
        val parts = listOf(
            text("Hello ", 0),
            IndexedMessagePart(MessagePart.Text("world", TextFormat.Flags(TextFlags(true, false, false, false))), 0UL, null),
            attachment("IMG_1.jpg", 1),
            text("see?", 2),
        )
        val u = map(MessageInst("M5", bob, ConversationData(listOf(me, bob), null, null, null), PushChatMessage.Message(normal(parts)), 1UL, null, false, false, null))
        u as DirectArchive.Update.Upsert
        assertEquals("Hello world\n" + DirectArchive.ATTACHMENT_LINE + "IMG_1.jpg\nsee?", u.message.text)
        assertEquals(mapOf(0 to "Hello world", 1 to DirectArchive.ATTACHMENT_LINE + "IMG_1.jpg", 2 to "see?"), IMessageExtra.parse(u.message.extra).parts)
    }

    @Test
    fun partsWithoutIndexesAreNumberedAsRustpushNumbersThem() {
        assertEquals(listOf(0, 0, 1, 2), DirectArchive.partIndexes(listOf(text("a"), text("b"), attachment("x"), text("c"))))
        assertEquals(listOf(0, 1), DirectArchive.partIndexes(listOf(attachment("x"), attachment("y"))))
        assertEquals(listOf(4, 7), DirectArchive.partIndexes(listOf(text("a", 4), attachment("x", 7))))
    }

    @Test
    fun attachmentsAreNamedInTheText() {
        val parts = listOf(
            MessagePart.Text("look", TextFormat.Flags(TextFlags(false, false, false, false))),
            MessagePart.Attachment(uniffi.rust_bridge.MessageAttachment(uniffi.rust_bridge.AttachmentType.Inline(byteArrayOf()), 1UL, "public.jpeg", "image/jpeg", "IMG_1.jpg", false)),
        )
        assertEquals("look\n" + DirectArchive.ATTACHMENT_LINE + "IMG_1.jpg", DirectArchive.textOf(parts))
    }

    @Test
    fun effectVoiceLinkAndAppGoInTheExtra() {
        val link = LinkMeta(
            LpLinkMetadata(null, 1u, null, NsUrl("\$null", "https://www.example.com/a"), NsUrl("\$null", "https://www.example.com/a"), "A page", "About it", null, null, null, null, null, null, null, null, null),
            emptyList(),
        )
        val app = ExtensionApp(
            "GamePigeon", 1124197642UL, "com.apple.messages.MSMessageExtensionBalloonPlugin:EWFNLB79LQ:com.gamerdelights.gamepigeon.ext",
            Balloon("data:game", "S-1", BalloonLayout.TemplateLayout("sub", "title", "Let's play 8 Ball!", "2nd", "3rd", "subcap", NsDictionaryClass.NS_DICTIONARY), "8 Ball", true, null),
        )
        val m = MessageInst(
            "M6", bob, ConversationData(listOf(me, bob), null, null, null),
            PushChatMessage.Message(normal(listOf(text("https://www.example.com/a")), effect = "com.apple.MobileSMS.expressivesend.impact", voice = true, app = app, link = link)),
            1UL, null, false, false, null,
        )
        val extra = IMessageExtra.parse((map(m) as DirectArchive.Update.Upsert).message.extra)
        assertEquals("com.apple.MobileSMS.expressivesend.impact", extra.effect)
        assertTrue(extra.voice)
        assertEquals(IMessageExtra.Link("https://www.example.com/a", "A page", "About it", "example.com"), extra.link)
        val a = extra.app!!
        assertEquals("GamePigeon", a.name)
        assertEquals(1124197642L, a.appId)
        assertEquals("S-1", a.session)
        assertEquals("8 Ball", a.ldText)
        assertEquals("Let's play 8 Ball!", a.caption)
        assertEquals("title", a.imageTitle)
        assertEquals("subcap", a.subcaption)
        assertEquals("imsg:M6", a.root)
        assertTrue(a.isLive)
    }

    @Test
    fun tapbacksBecomeOnePersonsReaction() {
        fun react(r: Reaction, enable: Boolean = true, sender: String = bob) =
            map(inst("R1", sender, PushChatMessage.React(ReactMessage("M1", 0UL, ReactMessageType.React(r, enable), "", null))))
        val heart = react(Reaction.Heart) as DirectArchive.Update.Reaction
        assertEquals(DirectArchive.Update.Reaction("imsg:M1", "+15550000002", "❤️", true, false, 1_700_000_005_000L), heart)
        assertEquals("👍", (react(Reaction.Like) as DirectArchive.Update.Reaction).emoji)
        assertEquals("👎", (react(Reaction.Dislike) as DirectArchive.Update.Reaction).emoji)
        assertEquals("😂", (react(Reaction.Laugh) as DirectArchive.Update.Reaction).emoji)
        assertEquals("‼️", (react(Reaction.Emphasize) as DirectArchive.Update.Reaction).emoji)
        assertEquals("❓", (react(Reaction.Question) as DirectArchive.Update.Reaction).emoji)
        assertEquals("🦊", (react(Reaction.Emoji("🦊")) as DirectArchive.Update.Reaction).emoji)
        assertEquals("🖼️", (react(Reaction.Sticker(null, emptyList())) as DirectArchive.Update.Reaction).emoji)
        assertFalse((react(Reaction.Heart, enable = false) as DirectArchive.Update.Reaction).enable)
        // The user's own, from another device.
        val mine = react(Reaction.Laugh, sender = me) as DirectArchive.Update.Reaction
        assertTrue(mine.fromMe)
        assertEquals("+15550000001", mine.participantId)
    }

    @Test
    fun emojiAreSentAsTheClassicTapbacksWhereThereIsOne() {
        assertEquals(Reaction.Heart, DirectArchive.reactionOf("❤️"))
        assertEquals(Reaction.Heart, DirectArchive.reactionOf("❤"))
        assertEquals(Reaction.Like, DirectArchive.reactionOf("👍"))
        assertEquals(Reaction.Dislike, DirectArchive.reactionOf("👎"))
        assertEquals(Reaction.Laugh, DirectArchive.reactionOf("😂"))
        assertEquals(Reaction.Emphasize, DirectArchive.reactionOf("‼️"))
        assertEquals(Reaction.Question, DirectArchive.reactionOf("❓"))
        assertEquals(Reaction.Emoji("🦊"), DirectArchive.reactionOf("🦊"))
        assertEquals(Reaction.Emoji("👍🏽"), DirectArchive.reactionOf("👍🏽"))
    }

    @Test
    fun unsendEditAndTypingChangeTheStoredMessage() {
        assertEquals(
            DirectArchive.Update.Unsent("imsg:M1", 2, false, "Bob"),
            map(inst("X", bob, PushChatMessage.Unsend(UnsendMessage("M1", 2UL)))),
        )
        assertEquals(DirectArchive.Update.Unsent("imsg:M1", 0, true, ""), map(inst("X", me, PushChatMessage.Unsend(UnsendMessage("M1", 0UL)))))
        assertEquals(
            DirectArchive.Update.Edit("imsg:M1", "fixed it", 1, 1_700_000_005_000L),
            map(inst("X", bob, PushChatMessage.Edit(EditMessage("M1", 1UL, listOf(text("fixed "), text("it")))))),
        )
        assertEquals(DirectArchive.Update.Typing("imsg:$bob", "+15550000002", true), map(inst("X", bob, PushChatMessage.Typing(true, null))))
        assertEquals(DirectArchive.Update.Typing("imsg:$bob", "+15550000002", false), map(inst("X", bob, PushChatMessage.Typing(false, null))))
        // The user's own devices never show as typing.
        assertEquals(DirectArchive.Update.Ignore, map(inst("X", me, PushChatMessage.Typing(true, null))))
        assertEquals(DirectArchive.Update.Ignore, map(inst("X", bob, PushChatMessage.MarkUnread)))
    }

    @Test
    fun receiptsFromThemAndFromTheUsersOtherDevices() {
        assertEquals(DirectArchive.Update.Receipt(listOf("imsg:M5"), DirectArchive.STATUS_DELIVERED), map(inst("M5", bob, PushChatMessage.Delivered)))
        assertEquals(DirectArchive.Update.Receipt(listOf("imsg:M5"), DirectArchive.STATUS_READ), map(inst("M5", bob, PushChatMessage.Read)))
        // Read on the user's iPhone: the chat is read; their other devices' Delivered says nothing.
        assertEquals(DirectArchive.Update.ReadElsewhere("imsg:M5"), map(inst("M5", me, PushChatMessage.Read)))
        assertEquals(DirectArchive.Update.Ignore, map(inst("M5", me, PushChatMessage.Delivered)))
    }

    @Test
    fun whatAppleCouldNotVerifyIsDropped() {
        for (m in listOf(
            PushChatMessage.Delivered, PushChatMessage.Read, PushChatMessage.Typing(true, null),
            PushChatMessage.Edit(EditMessage("M1", 0UL, listOf(text("x")))), PushChatMessage.Unsend(UnsendMessage("M1", 0UL)),
            PushChatMessage.RenameMessage(ChatRenameMessage("Evil")),
        )) {
            assertEquals(m.toString(), DirectArchive.Update.Ignore, map(inst("X", bob, m, participants = listOf(me, bob, carol), groupGuid = "G", unverified = true)))
        }
    }

    @Test
    fun anErrorForTheUsersMessageFailsIt() {
        assertEquals(
            DirectArchive.Update.Failed("imsg:M7", "Not delivered"),
            map(inst("X", me, PushChatMessage.Error(ErrorMessage("M7", 4UL, "Not delivered")))),
        )
        assertEquals(DirectArchive.Update.Ignore, map(inst("X", bob, PushChatMessage.Error(ErrorMessage("M7", 4UL, "x")))))
    }

    @Test
    fun aRenamedGroupGetsItsNewNameAndARow() {
        val u = map(inst("E1", bob, PushChatMessage.RenameMessage(ChatRenameMessage("Ski trip")), listOf(me, bob, carol), "G-5", "Trip"))
        u as DirectArchive.Update.Upsert
        assertEquals("imsg:group:G-5", u.conversation.id)
        assertEquals("Ski trip", u.conversation.name)
        assertEquals("imsg:E1", u.message.id)
        assertEquals("system", u.message.direction)
        assertEquals("Bob named the conversation “Ski trip”.", u.message.text)
        val mine = map(inst("E2", me, PushChatMessage.RenameMessage(ChatRenameMessage("Ski")), listOf(me, bob, carol), "G-5"))
        assertEquals("You named the conversation “Ski”.", (mine as DirectArchive.Update.Upsert).message.text)
        // Not a group: nothing to rename.
        assertEquals(DirectArchive.Update.Ignore, map(inst("E3", bob, PushChatMessage.RenameMessage(ChatRenameMessage("x")))))
    }

    @Test
    fun membersComingAndGoingAreRowsAndTheGroupFollows() {
        fun change(sender: String, after: List<String>) =
            map(inst("E4", sender, PushChatMessage.ChangeParticipants(ChangeParticipantMessage(after, 2UL)), listOf(me, bob, carol), "G-6"))
                as DirectArchive.Update.Upsert
        val added = change(bob, listOf(me, bob, carol, dave))
        assertEquals("Bob added Dave.", added.message.text)
        assertEquals(setOf(me, bob, carol, dave), added.conversation.participants.map { it.id }.toSet())
        assertEquals("imsg:group:G-6", added.conversation.id)
        assertEquals("Bob removed Carol.", change(bob, listOf(me, bob)).message.text)
        // Down to two people, it is still the group.
        assertEquals("imsg:group:G-6", change(bob, listOf(me, bob)).conversation.id)
        assertEquals("Carol left the conversation.", change(carol, listOf(me, bob)).message.text)
        assertEquals("Bob removed you.", change(bob, listOf(bob, carol)).message.text)
        assertEquals("You added Dave.", change(me, listOf(me, bob, carol, dave)).message.text)
    }

    @Test
    fun aGroupPhotoChangeIsARow() {
        val file = MmcsFile(byteArrayOf(), "o", "u", byteArrayOf(), 1UL)
        val changed = map(inst("E5", bob, PushChatMessage.IconChange(IconChangeMessage(file, 3UL)), listOf(me, bob, carol), "G-7"))
        assertEquals("Bob changed the group photo.", (changed as DirectArchive.Update.Upsert).message.text)
        val removed = map(inst("E6", carol, PushChatMessage.IconChange(IconChangeMessage(null, 4UL)), listOf(me, bob, carol), "G-7"))
        assertEquals("Carol removed the group photo.", (removed as DirectArchive.Update.Upsert).message.text)
    }

    @Test
    fun receiptsNeverMoveAMessageBackwards() {
        assertTrue(DirectArchive.statusRank(DirectArchive.STATUS_READ) > DirectArchive.statusRank(DirectArchive.STATUS_DELIVERED))
        assertTrue(DirectArchive.statusRank(DirectArchive.STATUS_DELIVERED) > DirectArchive.statusRank(DirectArchive.STATUS_SENT))
        assertTrue(DirectArchive.statusRank(DirectArchive.STATUS_FAILED) < DirectArchive.statusRank(DirectArchive.STATUS_SENT))
    }

    @Test
    fun sendingToAStoredConversationUsesItsMembersAndGroupGuid() {
        fun p(id: String, isMe: Boolean = false) = ParticipantRow("c", id, "", "", "", "", isMe, "", "", true)
        val one = DirectArchive.conversationData("imsg:$bob", listOf(p(bob), p(me, true)), "Bob")
        assertEquals(listOf(bob, me), one.participants)
        assertNull(one.cvName)
        assertNull(one.senderGuid)
        val group = DirectArchive.conversationData("imsg:group:G-9", listOf(p(bob), p(carol), p(me, true)), "Trip")
        assertEquals("G-9", group.senderGuid)
        assertEquals("Trip", group.cvName)
    }

    /** The English strings, as strings_imessage.xml has them. */
    object TestTexts : DirectArchive.Texts {
        override val you = "You"
        override val youObject = "you"
        override fun named(who: String, name: String) = "$who named the conversation “$name”."
        override fun added(who: String, whom: String) = "$who added $whom."
        override fun removed(who: String, whom: String) = "$who removed $whom."
        override fun left(who: String) = "$who left the conversation."
        override fun changedPhoto(who: String) = "$who changed the group photo."
        override fun removedPhoto(who: String) = "$who removed the group photo."
    }
}
