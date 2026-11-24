package com.lightdecoder.imessage.direct

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.BlockList
import com.lightdecoder.chat.ChatService
import com.lightdecoder.chat.FocusStates
import com.lightdecoder.chat.SharedProfiles
import com.lightdecoder.imessage.IMessageAddress
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.IMessagePrefs
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.ParticipantDto
import java.io.File
import java.util.Collections
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.IndexedMessagePart
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MessagePart
import uniffi.rust_bridge.MessageType
import uniffi.rust_bridge.NormalMessage
import uniffi.rust_bridge.PushChatMessage
import uniffi.rust_bridge.TextFlags
import uniffi.rust_bridge.TextFormat

/**
 * The Apple Account's features in the main process (B6): a shared name and photo taken only for a
 * chat that is here, Focus status only for someone asked about, "Delivered Quietly", the iCloud import
 * stored read-only through the archive (blocked people and deleted messages left out), and all of it
 * gone with a sign-out. Without the account nothing of it is taken.
 */
@RunWith(AndroidJUnit4::class)
class DirectLinkFeaturesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ArchiveDb
    private lateinit var repo: ArchiveRepository
    private lateinit var blocks: BlockList
    private val me = "tel:+15550000001"
    private val bob = "tel:+15559876543"
    private val plain = TextFormat.Flags(TextFlags(false, false, false, false))
    private val asked: MutableList<Pair<Int, JSONObject>> = Collections.synchronizedList(ArrayList())
    private val now = System.currentTimeMillis()

    private val on = AccountState(signedIn = true, icloud = true, clique = true, focus = true, history = true, profiles = true, profile = true)

    /** What the stand-in ":imessage" process answers an ACCOUNT request with. */
    @Volatile
    private var accountAnswer: JSONObject = on.toJson()

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
        SharedProfiles.dirOf(context).deleteRecursively()
        SharedProfiles.resetForTesting()
        File(context.filesDir, "focus.json").delete()
        FocusStates.resetForTesting()
        DirectPrefs.setRegistered(context, true)
        DirectLibrary.REQUIRED.forEach { File(DirectLibrary.dir(context), it).apply { parentFile?.mkdirs(); writeText("") } }
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
        IMessageAddress.resetForTesting()
        SharedProfiles.dirOf(context).deleteRecursively()
        SharedProfiles.resetForTesting()
        FocusStates.resetForTesting()
        db.close()
    }

    private fun link() = DirectLink.installForTesting(context) { what, request, _ ->
        asked += what to request
        when (what) {
            DirectWire.PROFILE_FETCH -> DirectWire.ProfileReply("Robert‮", "Robert", "", false).toJson()
            DirectWire.ACCOUNT -> accountAnswer
            else -> DirectWire.ok()
        }
    }

    private fun wire(u: DirectArchive.Update) = DirectWire.decode(JSONObject(DirectWire.encodeUpdate(u).toString()))

    private fun text(s: String) = IndexedMessagePart(MessagePart.Text(s, plain), null, null)

    private fun normal(s: String) = PushChatMessage.Message(NormalMessage(listOf(text(s)), null, null, null, MessageType.IMessage, null, null, null, false, null, null))

    private fun inst(guid: String, from: String, m: PushChatMessage, ts: Long = now) =
        MessageInst(guid, from, ConversationData(listOf(me, bob), null, null, null), m, ts.toULong(), null, false, false, null)

    private fun map(i: MessageInst) = wire(DirectArchive.map(i, setOf(me), Handles::display, DirectTexts(context)))

    private fun signIn(link: DirectLink, state: AccountState = on) = link.apply(wire(DirectArchive.Update.Account(state)))

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    private val chat = "imsg:$bob"
    private val descriptor = ProfileDescriptor(byteArrayOf(1, 2, 3), "record-1").encode()

    @Test
    fun withoutTheAccountNothingOfItIsTaken() {
        accountAnswer = AccountState.SIGNED_OUT.toJson()
        val link = link()
        link.apply(map(inst("B1", bob, normal("hi"))))
        link.apply(wire(DirectArchive.Update.ProfileOffer(chat, bob, descriptor)))
        // Not taken for now (handed over again, its page's token kept), and the account asked again.
        assertThrows(DirectLink.AccountNotKnown::class.java) {
            link.apply(wire(DirectArchive.Update.CloudBatch(listOf(ConversationDto(id = chat)), listOf(imported("C1")))))
        }
        link.focusOpened(chat)
        Thread.sleep(200)
        assertTrue(asked.none { it.first == DirectWire.PROFILE_FETCH || it.first == DirectWire.FOCUS_SUBSCRIBE })
        assertNull(repo.message("imsg:C1"))
        assertEquals(DirectLink.AccountBanners(), link.accountBannersFor(chat))
    }

    @Test
    fun anOfferIsFetchedOnlyForAChatThatIsHereAndShownOnlyOnceTaken() {
        val link = link()
        signIn(link)
        // No chat with Bob yet: nothing fetched.
        link.apply(wire(DirectArchive.Update.ProfileOffer(chat, bob, descriptor)))
        Thread.sleep(200)
        assertTrue(asked.none { it.first == DirectWire.PROFILE_FETCH })
        link.apply(map(inst("B1", bob, normal("hi"))))
        link.apply(wire(DirectArchive.Update.ProfileOffer(chat, bob, ProfileDescriptor(byteArrayOf(4), "record-2").encode())))
        waitFor("the offer") { link.accountBannersFor(chat).offerName != null }
        val fetch = asked.first { it.first == DirectWire.PROFILE_FETCH }.second
        val into = File(fetch.getString("imagePath"))
        assertEquals("into a new file of their folder", SharedProfiles.dirOf(context).canonicalFile, into.parentFile!!.canonicalFile)
        assertTrue(into.name.startsWith(SharedProfiles.INCOMING_PREFIX))
        assertEquals("Robert", link.accountBannersFor(chat).offerName)
        // Not the chat's name until the user says "Update".
        assertEquals("+15559876543", link.sharedTitle(chat, "+15559876543"))
        link.acceptProfile(chat)
        assertEquals("Robert", link.sharedTitle(chat, "+15559876543"))
        // Never over a name the chat has.
        assertEquals("Mom", link.sharedTitle(chat, "Mom"))
        assertNull(link.accountBannersFor(chat).offerName)
    }

    @Test
    fun aBlockedPersonsOfferIsDropped() {
        val link = link()
        signIn(link)
        link.apply(map(inst("B1", bob, normal("hi"))))
        blocks.block("+1 555 987 6543")
        assertEquals(DirectLink.Applied.BLOCKED, link.apply(wire(DirectArchive.Update.ProfileOffer(chat, bob, descriptor))))
        Thread.sleep(200)
        assertTrue(asked.none { it.first == DirectWire.PROFILE_FETCH })
    }

    @Test
    fun focusIsShownOnlyForSomeoneAskedAboutAndDeliveredQuietlyFollowsIt() {
        val link = link()
        signIn(link)
        link.apply(wire(DirectArchive.Update.Focus(bob, true, now)))
        link.apply(map(inst("B1", bob, normal("hi"))))
        assertFalse(link.accountBannersFor(chat).silenced)
        link.focusOpened(chat)
        waitFor("asked about") { asked.any { it.first == DirectWire.FOCUS_SUBSCRIBE } }
        val sub = asked.first { it.first == DirectWire.FOCUS_SUBSCRIBE }.second
        assertEquals(listOf(bob), DirectWire.focusHandlesOf(sub))
        link.apply(wire(DirectArchive.Update.Focus(bob, true, now + 1)))
        assertTrue(link.accountBannersFor(chat).silenced)
        // The user's message, delivered while Bob is silenced: "Delivered Quietly".
        link.apply(map(inst("M1", me, normal("are you there"))))
        link.apply(wire(DirectArchive.Update.Receipt(listOf("imsg:M1"), DirectArchive.STATUS_DELIVERED, bob)))
        assertTrue(IMessageExtra.parse(repo.message("imsg:M1")!!.extra).quietly)
        // Not silenced any more: the next one is delivered as usual.
        link.apply(wire(DirectArchive.Update.Focus(bob, false, now + 2)))
        link.apply(map(inst("M2", me, normal("again"))))
        link.apply(wire(DirectArchive.Update.Receipt(listOf("imsg:M2"), DirectArchive.STATUS_DELIVERED, bob)))
        assertFalse(IMessageExtra.parse(repo.message("imsg:M2")!!.extra).quietly)
    }

    private fun imported(guid: String, sender: String = bob, conversation: String = chat, ts: Long = 1_600_000_000_000L) = MessageDto(
        id = "imsg:$guid", conversationId = conversation, senderId = sender, fromMe = sender.isEmpty(), timestampMs = ts,
        direction = if (sender.isEmpty()) "out" else "in", text = "old $guid", isOld = true, extra = IMessageExtra(parts = mapOf(0 to "old $guid"), imported = true).toJson(),
    )

    private fun announced() = shadowOf(context.getSystemService(NotificationManager::class.java)).activeNotifications.any { it.tag?.startsWith("im:") == true }

    @Test
    fun anImportIsStoredReadOnlyWithoutNotifyingAndLeavesOutWhatWasDeletedOrBlocked() {
        val link = link()
        signIn(link)
        repo.tombstone(listOf("imsg:C2"))
        val carol = "tel:+15550000003"
        blocks.block("+1 555 000 0003")
        val chats = listOf(
            ConversationDto(id = chat, participants = listOf(ParticipantDto(id = bob, number = bob))),
            ConversationDto(id = "imsg:$carol", participants = listOf(ParticipantDto(id = carol, number = carol))),
        )
        link.apply(wire(DirectArchive.Update.CloudBatch(chats, listOf(imported("C1"), imported("C2"), imported("C3", sender = ""), imported("C4", carol, "imsg:$carol")))))
        assertNotNull(repo.message("imsg:C1"))
        assertNull("deleted here", repo.message("imsg:C2"))
        assertTrue(repo.message("imsg:C3")!!.isOutgoing)
        assertNull("blocked", repo.message("imsg:C4"))
        assertNull(repo.conversation("imsg:$carol"))
        assertFalse(repo.conversation(chat)!!.unread)
        assertFalse(announced())
        // Handed over again: nothing doubled.
        link.apply(wire(DirectArchive.Update.CloudBatch(chats.take(1), listOf(imported("C1")))))
        assertEquals(2, repo.messages(chat).size)
    }

    @Test
    fun withMessagesInIcloudOffNothingIsImported() {
        accountAnswer = on.copy(history = false).toJson()
        val link = link()
        signIn(link, on.copy(history = false))
        assertThrows(DirectLink.AccountNotKnown::class.java) {
            link.apply(wire(DirectArchive.Update.CloudBatch(listOf(ConversationDto(id = chat, participants = listOf(ParticipantDto(id = bob, number = bob)))), listOf(imported("C1")))))
        }
        assertNull(repo.message("imsg:C1"))
    }

    @Test
    fun aBatchThatCameBeforeTheAccountWasKnownHereIsTakenOnceItIs() {
        // A main process just made: the account not yet told here, though the ":imessage" process has it on.
        val link = link()
        val batch = wire(DirectArchive.Update.CloudBatch(listOf(ConversationDto(id = chat, participants = listOf(ParticipantDto(id = bob, number = bob)))), listOf(imported("C1"))))
        try {
            link.apply(batch)
            fail("taken before the account was known")
        } catch (e: DirectLink.AccountNotKnown) {
            // Not stored: the ":imessage" process hands it over again.
        }
        waitFor("the account asked") { asked.any { it.first == DirectWire.ACCOUNT } && link.account.value.history }
        link.apply(batch)
        assertNotNull(repo.message("imsg:C1"))
    }

    @Test
    fun aSharedNameNeverReplacesTheContactsName() {
        val link = link()
        signIn(link)
        link.apply(map(inst("B1", bob, normal("hi"))))
        // The chat's person is a contact ("Mom"); the chat itself has no name (as every one-to-one iMessage chat).
        repo.upsertConversation(
            ConversationDto(
                id = chat, name = "", type = IMessageManager.TRANSPORT,
                participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = "+15559876543", name = "Mom", number = "+15559876543", formattedNumber = "+15559876543")),
            ),
        )
        SharedProfiles.get(context).offered(chat, bob, "r", "Bank", null)
        SharedProfiles.get(context).accept(chat)
        assertEquals("Bank", SharedProfiles.get(context).nameFor(bob))
        assertEquals("the chat's own name stays", "", link.sharedTitle(chat, ""))
        assertEquals("Mom", com.lightdecoder.ui.chat.ConversationTitles.of(context, repo, repo.conversation(chat)!!))
        // With no contact (the person is just their number): the shared name is shown.
        repo.upsertConversation(
            ConversationDto(
                id = chat, name = "", type = IMessageManager.TRANSPORT,
                participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = "+15559876543", name = "+15559876543", number = "+15559876543", formattedNumber = "+15559876543")),
            ),
        )
        assertEquals("Bank", link.sharedTitle(chat, ""))
        assertEquals("Bank", com.lightdecoder.ui.chat.ConversationTitles.of(context, repo, repo.conversation(chat)!!))
    }

    @Test
    fun importedMessagesAreNeverReadReceiptedOrMarkedUnreadToAnyone() = kotlinx.coroutines.runBlocking {
        val link = link()
        signIn(link)
        link.apply(wire(DirectArchive.Update.CloudBatch(listOf(ConversationDto(id = chat, participants = listOf(ParticipantDto(id = bob, number = bob)))), listOf(imported("C1")))))
        assertNotNull(repo.message("imsg:C1"))
        link.markRead(chat)
        link.markUnread(chat)
        assertTrue(asked.none { it.first == DirectWire.READ || it.first == DirectWire.MARK_UNREAD })
        // A live message since: that one is.
        link.apply(map(inst("B1", bob, normal("hi"))))
        link.markRead(chat)
        val read = asked.filter { it.first == DirectWire.READ }
        assertEquals(1, read.size)
        assertTrue(read[0].second.toString().contains("B1"))
    }

    @Test
    fun aChatDeletedForGoodIsNotBroughtBackByAnImport() {
        val link = link()
        signIn(link)
        link.apply(map(inst("B1", bob, normal("hi"), ts = now - 10_000)))
        repo.setConversationFolder(chat, com.lightdecoder.relay.Folder.DELETED)
        assertEquals(1, repo.purgeDeleted(chat))
        assertNotNull(repo.conversationDeletedAt(chat))
        // Long after, the messages' own marks gone: still not brought back.
        repo.pruneTombstones(now + 365L * 24 * 60 * 60 * 1000)
        assertEquals(0, repo.tombstoneCount())
        val chats = listOf(ConversationDto(id = chat, participants = listOf(ParticipantDto(id = bob, number = bob))))
        link.apply(wire(DirectArchive.Update.CloudBatch(chats, listOf(imported("C1"), imported("B1", ts = now - 10_000)))))
        assertNull(repo.conversation(chat))
        assertNull(repo.message("imsg:C1"))
        // Written in again (live): the chat is back, but nothing from before its deletion is imported into it.
        link.apply(map(inst("B2", bob, normal("hello again"))))
        link.apply(wire(DirectArchive.Update.CloudBatch(chats, listOf(imported("C1"), imported("C5", ts = now + 1_000)))))
        assertNull(repo.message("imsg:C1"))
        assertNotNull(repo.message("imsg:C5"))
    }

    @Test
    fun removingImportedMessagesKeepsTheRealDeletionsMarks() {
        val link = link()
        signIn(link)
        repo.tombstone(listOf("imsg:D1"))
        link.apply(wire(DirectArchive.Update.CloudBatch(listOf(ConversationDto(id = chat, participants = listOf(ParticipantDto(id = bob, number = bob)))), listOf(imported("C1"), imported("C2")))))
        link.apply(wire(DirectArchive.Update.Account(AccountState.SIGNED_OUT, removeImported = true)))
        assertNull(repo.message("imsg:C1"))
        assertEquals("no mark for a copy removed", 1, repo.tombstoneCount())
        assertTrue(repo.isTombstoned("imsg:D1"))
    }

    @Test
    fun aSignOutSeenOnlyInAnAnswerStillRemovesWhatTheAccountBrought() {
        val link = link()
        signIn(link)
        link.apply(map(inst("B1", bob, normal("hi"))))
        SharedProfiles.get(context).offered(chat, bob, "r", "Robert", null)
        SharedProfiles.get(context).accept(chat)
        link.apply(wire(DirectArchive.Update.CloudBatch(listOf(ConversationDto(id = chat, participants = listOf(ParticipantDto(id = bob, number = bob)))), listOf(imported("C1")))))
        // The sign-out's own telling never came; the next answer says it, with the imported messages to go.
        accountAnswer = DirectWire.accountReply(AccountState.SIGNED_OUT, restartPending = false, removeImported = true)
        link.screenShown()
        waitFor("signed out") { !link.account.value.signedIn && repo.message("imsg:C1") == null }
        assertNull(SharedProfiles.get(context).nameFor(bob))
        assertNotNull(repo.message("imsg:B1"))
    }

    @Test
    fun aSignOutRemovesNamesPhotosAndFocusAndImportedMessagesWhenAsked() {
        val link = link()
        signIn(link)
        link.apply(map(inst("B1", bob, normal("hi"))))
        SharedProfiles.get(context).offered(chat, bob, "r", "Robert", null)
        SharedProfiles.get(context).accept(chat)
        link.apply(wire(DirectArchive.Update.CloudBatch(listOf(ConversationDto(id = chat, participants = listOf(ParticipantDto(id = bob, number = bob)))), listOf(imported("C1")))))
        assertNotNull(repo.message("imsg:C1"))
        link.apply(wire(DirectArchive.Update.Account(AccountState.SIGNED_OUT)))
        assertNull(SharedProfiles.get(context).nameFor(bob))
        assertNotNull("kept unless asked", repo.message("imsg:C1"))
        signIn(link)
        link.apply(wire(DirectArchive.Update.Account(AccountState.SIGNED_OUT, removeImported = true)))
        assertNull(repo.message("imsg:C1"))
        assertNotNull("a live message stays", repo.message("imsg:B1"))
    }

    companion object {
        private const val DB = "direct-features-test.db"
    }
}
