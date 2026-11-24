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
import com.lightdecoder.relay.ContactDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.PartDto
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
 * Batch 3 through the iMessage library, in the main process ([DirectLink]): an edited message keeps
 * what it read before; "Mark as Unread" both ways (only the user's own devices are listened to);
 * the user's new device told to the people of the recent chats, at most once in ten minutes; "Report
 * Junk" (when it is offered, what goes to Apple, the chat deleted after); a message Apple could not
 * deliver sent again; a deleted chat's pictures going with it.
 */
@RunWith(AndroidJUnit4::class)
class TranscriptSyncTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ArchiveDb
    private lateinit var repo: ArchiveRepository
    private val me = "tel:+15550000001"
    private val alice = "tel:+15551234567"
    private val bob = "tel:+15559876543"
    private val carol = "tel:+15554443333"
    private val aliceChat = "imsg:tel:+15551234567"
    private val plain = TextFormat.Flags(TextFlags(false, false, false, false))

    /** What went to the ":imessage" process, by kind. */
    private val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())

    /** What the ":imessage" process answers: Apple's, as the library gives it. */
    @Volatile
    private var answer: (Int) -> JSONObject = { what -> if (what == DirectWire.SEND) DirectWire.sent("NEW-${asked.size}", System.currentTimeMillis()) else DirectWire.ok() }

    private lateinit var link: DirectLink

    @Before
    fun setUp() {
        // Names come from the Messages archive's contacts: a fresh one, with nobody in it.
        com.lightdecoder.relay.RelayManager.resetForTesting()
        ArchiveRepository.resetForTesting()
        context.deleteDatabase(DB)
        db = ArchiveDb(context, DB)
        repo = ArchiveRepository.forTesting(db, ChatService.IMESSAGE)
        ArchiveRepository.installForTesting(repo)
        IMessageManager.installForTesting(context, repo)
        DirectPrefs.setRegistered(context, true)
        DirectLibrary.REQUIRED.forEach { java.io.File(DirectLibrary.dir(context), it).apply { parentFile?.mkdirs(); writeText("") } }
        link = DirectLink.installForTesting(context) { what, request, _ ->
            asked += what to request
            answer(what)
        }
    }

    @After
    fun tearDown() {
        MessageNotifier.cancelAll(context, ChatService.IMESSAGE)
        IMessagePrefs.setEnabled(context, false)
        DirectPrefs.setRegistered(context, false)
        DirectLink.resetForTesting()
        IMessageManager.resetForTesting()
        com.lightdecoder.relay.RelayManager.resetForTesting()
        ArchiveRepository.resetForTesting()
        db.close()
        com.lightdecoder.archive.MediaCache.mediaDir(context, ChatService.IMESSAGE).deleteRecursively()
    }

    // --- set-up ----------------------------------------------------------------------------------

    private fun wire(u: DirectArchive.Update) = DirectWire.decode(JSONObject(DirectWire.encode(u).toString()))

    private fun inst(
        guid: String,
        from: String?,
        m: PushChatMessage,
        participants: List<String> = listOf(me, alice),
        groupGuid: String? = null,
        ts: Long = System.currentTimeMillis() - 60_000,
        unverified: Boolean = false,
    ) = MessageInst(guid, from, ConversationData(participants, null, groupGuid, null), m, ts.toULong(), null, false, unverified, null)

    private fun map(i: MessageInst) = wire(DirectArchive.map(i, setOf(me), Handles::display, DirectTexts(context)))

    private fun text(s: String) = PushChatMessage.Message(
        NormalMessage(listOf(IndexedMessagePart(MessagePart.Text(s, plain), null, null)), null, null, null, MessageType.IMessage, null, null, null, false, null, null),
    )

    /** Alice writes [body] to the user, at [ts]. */
    private fun fromAlice(guid: String, body: String, ts: Long = System.currentTimeMillis() - 60_000) =
        link.apply(map(inst(guid, alice, text(body), ts = ts)))

    private fun outgoing(guid: String, chat: String, body: String, ts: Long, status: String = DirectArchive.STATUS_SENT) {
        repo.upsertMessage(
            MessageDto(
                id = "imsg:$guid", conversationId = chat, fromMe = true, timestampMs = ts, direction = "out", status = status,
                transport = IMessageManager.TRANSPORT, text = body, parts = listOf(PartDto("0", "text", body)), isOld = true,
            ),
        )
    }

    private fun waitFor(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    private fun asked(what: Int) = synchronized(asked) { asked.filter { it.first == what }.map { it.second } }

    // --- edit history (iOS 16) ---------------------------------------------------------------------

    @Test
    fun incomingEditsKeepEveryEarlierTextWithWhenItWasThat() {
        val sent = System.currentTimeMillis() - 5 * 60_000
        fromAlice("E1", "See you at 6", ts = sent)
        val times = (1..5).map { sent + it * 30_000L }
        val texts = listOf("See you at 7", "See you at 8", "See you at 8:30", "Make it 9", "Make it 9:15")
        for ((at, body) in times.zip(texts)) {
            link.apply(wire(DirectArchive.Update.Edit("imsg:E1", body, 0, at, sender = alice)))
        }
        val row = repo.message("imsg:E1")!!
        assertEquals("Make it 9:15", row.text)
        val history = IMessageExtra.parse(row.extra).history
        assertEquals(IMessageExtra.MAX_HISTORY, history.size)
        assertEquals(listOf("See you at 6", "See you at 7", "See you at 8", "See you at 8:30", "Make it 9"), history.map { it.text })
        // Each from when it became the text: the first when it was sent, the others when they were edited to.
        assertEquals(listOf(sent) + times.dropLast(1), history.map { it.atMs })
        // The same edit handed over again (Apple's journal after a restart) adds nothing.
        link.apply(wire(DirectArchive.Update.Edit("imsg:E1", "Make it 9:15", 0, times.last(), sender = alice)))
        assertEquals(history, IMessageExtra.parse(repo.message("imsg:E1")!!.extra).history)
        // A sixth edit is past Apple's five: nothing changes.
        link.apply(wire(DirectArchive.Update.Edit("imsg:E1", "Never mind", 0, times.last() + 1_000, sender = alice)))
        assertEquals("Make it 9:15", repo.message("imsg:E1")!!.text)
    }

    @Test
    fun theUsersOwnEditKeepsWhatItReadBefore() {
        fromAlice("A1", "Hi")
        val sentAt = System.currentTimeMillis() - 60_000
        outgoing("S1", aliceChat, "Tomorow then", sentAt)
        runBlocking { link.edit("imsg:S1", "Tomorrow then") }
        val row = repo.message("imsg:S1")!!
        assertEquals("Tomorrow then", row.text)
        assertEquals(listOf(IMessageExtra.Version("Tomorow then", sentAt)), IMessageExtra.parse(row.extra).history)
        assertEquals("Tomorrow then", DirectWire.EditRequest.fromJson(asked(DirectWire.EDIT).single()).text)
    }

    @Test
    fun anEarlierVersionIsKeptAtMostSoLongAndTheLatestFiveOfThem() {
        var extra = IMessageExtra()
        for (i in 1..7) extra = extra.copy(history = extra.withEarlier("v$i", i * 1_000L))
        assertEquals(listOf("v3", "v4", "v5", "v6", "v7"), extra.history.map { it.text })
        // Read back as it was stored, five at most whatever was stored.
        val stored = JSONObject().put(
            "history", org.json.JSONArray().apply { (1..9).forEach { put(JSONObject().put("text", "w$it").put("t", it)) } },
        ).toString()
        assertEquals(listOf("w5", "w6", "w7", "w8", "w9"), IMessageExtra.parse(stored).history.map { it.text })
        assertEquals(extra, IMessageExtra.parse(extra.toJson()))
        // A long text is cut, never in half a character pair.
        val long = "🙂".repeat(IMessageExtra.MAX_HISTORY_TEXT_CHARS)
        val cut = IMessageExtra().withEarlier(long, 1).single().text
        assertTrue(cut.length <= IMessageExtra.MAX_HISTORY_TEXT_CHARS)
        assertTrue(cut.endsWith("…"))
        assertFalse(Character.isHighSurrogate(cut[cut.length - 2]))
        // Nothing before: nothing kept.
        assertTrue(IMessageExtra().withEarlier("", 5).isEmpty())
    }

    // --- Mark as Unread -------------------------------------------------------------------------------

    @Test
    fun markUnreadTellsTheUsersOtherDevicesNamingTheNewestMessageTheOtherPersonSent() {
        val now = System.currentTimeMillis()
        fromAlice("A1", "First", now - 3 * 60_000)
        fromAlice("A2", "Second", now - 2 * 60_000)
        outgoing("S1", aliceChat, "Mine, newest", now - 60_000)
        runBlocking { link.markRead(aliceChat) }
        assertFalse(repo.conversation(aliceChat)!!.unread)
        val readsBefore = asked(DirectWire.READ).size
        runBlocking { link.markUnread(aliceChat) }
        assertTrue(repo.conversation(aliceChat)!!.unread)
        val request = DirectWire.MarkUnreadRequest.fromJson(asked(DirectWire.MARK_UNREAD).single())
        assertEquals("A2", request.guid)
        assertEquals(listOf(alice), request.target.participants)
        assertEquals(me, request.target.from)
        // Read again here: the read receipt goes again (the user's other devices clear it too).
        runBlocking { link.markRead(aliceChat) }
        assertFalse(repo.conversation(aliceChat)!!.unread)
        assertEquals(readsBefore + 1, asked(DirectWire.READ).size)
    }

    @Test
    fun markUnreadFromTheUsersOwnDeviceMarksTheChatUnreadAndNotifiesNothing() {
        IMessagePrefs.setEnabled(context, true)
        IMessagePrefs.setNotifyMessages(context, true)
        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
        fun announced() = notifications.activeNotifications.count { it.tag?.startsWith("im:") == true }
        fromAlice("A1", "Hi there")
        // An ordinary message would be announced: the chat can make a sound.
        assertEquals(1, announced())
        MessageNotifier.cancelAll(context, ChatService.IMESSAGE)
        repo.markConversationRead(aliceChat)
        val mark = map(inst("A1", me, PushChatMessage.MarkUnread, participants = listOf(me)))
        assertEquals(DirectArchive.Update.MarkUnread("imsg:A1"), mark)
        link.apply(mark)
        assertTrue(repo.conversation(aliceChat)!!.unread)
        assertEquals("no notification", 0, announced())
    }

    @Test
    fun markUnreadIsTakenOnlyFromTheUsersOwnVerifiedDevices() {
        fromAlice("A1", "Hi there")
        repo.markConversationRead(aliceChat)
        // Alice cannot mark the user's chat unread.
        assertEquals(DirectArchive.Update.Ignore, map(inst("A1", alice, PushChatMessage.MarkUnread, participants = listOf(me, alice))))
        // Nor can anyone claiming to be the user whom Apple could not verify.
        assertEquals(DirectArchive.Update.Ignore, map(inst("A1", me, PushChatMessage.MarkUnread, participants = listOf(me), unverified = true)))
        // One that went over the wire as something else is no mark either.
        assertEquals(DirectArchive.Update.Ignore, DirectWire.decode(JSONObject().put("kind", "markUnread").put("messageId", "ld:A1")))
        assertFalse(repo.conversation(aliceChat)!!.unread)
        // For a message not stored yet: it waits for it (here one the user sent from another device,
        // which itself leaves the chat read).
        link.apply(map(inst("A9", me, PushChatMessage.MarkUnread, participants = listOf(me))))
        assertFalse(repo.conversation(aliceChat)!!.unread)
        link.apply(map(inst("A9", me, text("From my Mac"), participants = listOf(me, alice))))
        assertNotNull(repo.message("imsg:A9"))
        assertTrue(repo.conversation(aliceChat)!!.unread)
    }

    // --- the user's devices changed --------------------------------------------------------------------

    @Test
    fun onlyTheUsersOwnAccountSaysItsDevicesChanged() {
        // As the library makes it: from nobody, of the user's account.
        assertEquals(DirectArchive.Update.PeerCacheInvalidate, map(MessageInst("X", null, null, PushChatMessage.PeerCacheInvalidate, 0u, null, false, false, null)))
        assertEquals(DirectArchive.Update.PeerCacheInvalidate, map(inst("X", me, PushChatMessage.PeerCacheInvalidate)))
        assertEquals(DirectArchive.Update.Ignore, map(inst("X", alice, PushChatMessage.PeerCacheInvalidate)))
        assertEquals(DirectArchive.Update.Ignore, map(inst("X", me, PushChatMessage.PeerCacheInvalidate, unverified = true)))
    }

    @Test
    fun theUsersNewDeviceIsToldToThePeopleOfTheRecentChatsAtMostOnceInTenMinutes() {
        val now = System.currentTimeMillis()
        fromAlice("A1", "Recent", now - 60 * 60_000)
        outgoing("S1", aliceChat, "Hi Alice", now - 59 * 60_000)
        // A group with Bob, recent too, the user wrote in it.
        link.apply(map(inst("G1", bob, text("Group news"), participants = listOf(me, alice, bob), groupGuid = "GRP-1", ts = now - 2 * 60 * 60_000)))
        outgoing("S2", "imsg:group:GRP-1", "Nice", now - 2 * 60 * 60_000 + 1_000)
        // Carol wrote two days ago (and the user answered): not recent.
        link.apply(map(inst("C1", carol, text("Old news"), participants = listOf(me, carol), ts = now - 2 * 24 * 60 * 60_000)))
        outgoing("S3", "imsg:$carol", "Old answer", now - 2 * 24 * 60 * 60_000 + 1_000)
        link.apply(DirectArchive.Update.PeerCacheInvalidate)
        waitFor("the people told") { asked(DirectWire.PEERS).isNotEmpty() }
        val groups = DirectWire.PeersRequest.fromJson(asked(DirectWire.PEERS).single()).groups
        assertEquals(1, groups.size)
        assertEquals(me, groups.single().from)
        assertEquals(setOf(me, alice, bob), groups.single().participants.toSet())
        // Again at once: not again.
        link.apply(DirectArchive.Update.PeerCacheInvalidate)
        Thread.sleep(200)
        assertEquals(1, asked(DirectWire.PEERS).size)
        // Eleven minutes later: told again.
        context.getSharedPreferences("imessage_peer_notices", Context.MODE_PRIVATE).edit().putLong("told_at", now - 11 * 60_000).commit()
        link.apply(DirectArchive.Update.PeerCacheInvalidate)
        waitFor("told again") { asked(DirectWire.PEERS).size == 2 }
    }

    @Test
    fun whoIsToldOfANewDeviceAndHowItGoesAcross() {
        val t1 = DirectWire.Target(listOf(alice), null, null, me)
        val t2 = DirectWire.Target(listOf(alice, bob), "Trip", "G", me)
        val other = DirectWire.Target(listOf(carol), null, null, "mailto:me@example.com")
        val alone = DirectWire.Target(listOf("mailto:me2@example.com"), null, null, "mailto:me2@example.com")
        val groups = PeerInvalidation.groups(listOf(t1, t2, other, alone, DirectWire.Target(listOf(bob), null, null, "")))
        assertEquals(
            listOf(DirectWire.PeerGroup(me, listOf(me, alice, bob)), DirectWire.PeerGroup("mailto:me@example.com", listOf("mailto:me@example.com", carol))),
            groups,
        )
        // Across the processes, as it went, with its limits.
        assertEquals(groups, DirectWire.PeersRequest.fromJson(JSONObject(DirectWire.peers(groups).toString())).groups)
        val many = DirectWire.PeerGroup(me, (1..1_000).map { "tel:+1555000${it.toString().padStart(4, '0')}" })
        val read = DirectWire.PeersRequest.fromJson(JSONObject(DirectWire.peers(List(20) { many }).toString()))
        assertEquals(DirectWire.MAX_PEER_GROUPS, read.groups.size)
        assertTrue(read.groups.all { it.participants.size <= DirectWire.MAX_PEERS })
        // Recent: twelve hours.
        val now = 1_700_000_000_000L
        assertTrue(PeerInvalidation.recent(now - 11 * 60 * 60_000, now))
        assertFalse(PeerInvalidation.recent(now - 13 * 60 * 60_000, now))
        assertFalse(PeerInvalidation.recent(0, now))
        // Once in ten minutes, a clock gone back counting as since.
        val notices = PeerNotices(context)
        assertTrue(notices.due(now))
        notices.told(now)
        assertFalse(notices.due(now + 9 * 60_000))
        assertTrue(notices.due(now + 10 * 60_000))
        assertTrue(notices.due(now - 60_000))
    }

    @Test
    fun aNewDeviceIsNeverToldToSomeoneUnknownTheUserNeverWroteTo() {
        val now = System.currentTimeMillis()
        // Carol, whom the user does not know, wrote an hour ago; the user never answered. Nor Bob's group.
        link.apply(map(inst("C1", carol, text("Who is this?"), participants = listOf(me, carol), ts = now - 60 * 60_000)))
        link.apply(map(inst("G1", bob, text("Join us"), participants = listOf(me, bob, carol), groupGuid = "GRP-9", ts = now - 30 * 60_000)))
        link.apply(DirectArchive.Update.PeerCacheInvalidate)
        Thread.sleep(300)
        assertTrue("nobody told", asked(DirectWire.PEERS).isEmpty())
        // Once the user wrote to her, she is (and only she).
        outgoing("S1", "imsg:$carol", "Hi Carol", now - 20 * 60_000)
        link.apply(DirectArchive.Update.PeerCacheInvalidate)
        waitFor("Carol told") { asked(DirectWire.PEERS).isNotEmpty() }
        val groups = DirectWire.PeersRequest.fromJson(asked(DirectWire.PEERS).single()).groups
        assertEquals(listOf(DirectWire.PeerGroup(me, listOf(me, carol))), groups)
    }

    @Test
    fun aNewDeviceIsToldToAContactWhoWroteThoughTheUserDidNotAnswer() {
        context.deleteDatabase(MESSAGES_DB)
        val messagesDb = ArchiveDb(context, MESSAGES_DB)
        try {
            val messages = ArchiveRepository.forTesting(messagesDb, ChatService.MESSAGES)
            ArchiveRepository.installForTesting(messages)
            com.lightdecoder.relay.RelayManager.installForTesting(context, messages, com.lightdecoder.relay.RelayManager.Timing())
            messages.upsertContacts(listOf(ContactDto(id = "c1", name = "Alice Martin", number = "+15551234567", formattedNumber = "(555) 123-4567")))
            fromAlice("A1", "New phone, who dis")
            link.apply(map(inst("C1", carol, text("Hello?"), participants = listOf(me, carol))))
            link.apply(DirectArchive.Update.PeerCacheInvalidate)
            waitFor("Alice told") { asked(DirectWire.PEERS).isNotEmpty() }
            assertEquals(listOf(DirectWire.PeerGroup(me, listOf(me, alice))), DirectWire.PeersRequest.fromJson(asked(DirectWire.PEERS).single()).groups)
        } finally {
            com.lightdecoder.relay.RelayManager.resetForTesting()
            messagesDb.close()
        }
    }

    // --- an edited message of which a part was unsent ---------------------------------------------------

    @Test
    fun aPartUnsentTakesTheEarlierTextsWithIt() {
        val sent = System.currentTimeMillis() - 60_000
        fun part(s: String, idx: Int) = IndexedMessagePart(MessagePart.Text(s, plain), idx.toULong(), null)
        val two = PushChatMessage.Message(NormalMessage(listOf(part("Meet at 6", 0), part("the code is 4721", 1)), null, null, null, MessageType.IMessage, null, null, null, false, null, null))
        link.apply(map(inst("PU1", alice, two, ts = sent)))
        // The first part edited: what the whole message read before is kept, the second part in it.
        link.apply(wire(DirectArchive.Update.Edit("imsg:PU1", "Meet at 7", 0, sent + 10_000, sender = alice)))
        var row = repo.message("imsg:PU1")!!
        assertEquals(listOf("Meet at 6\nthe code is 4721"), IMessageExtra.parse(row.extra).history.map { it.text })
        assertTrue(row.editedAtMs > 0)
        // The second part unsent: nothing of it stays, in the earlier texts either.
        link.apply(wire(DirectArchive.Update.Unsent("imsg:PU1", 1, false, "Alice", sender = alice, atMs = sent + 20_000)))
        row = repo.message("imsg:PU1")!!
        assertEquals("Meet at 7", row.text)
        val extra = IMessageExtra.parse(row.extra)
        assertTrue(extra.history.isEmpty())
        assertFalse(row.extra.contains("4721"))
        assertEquals("the edited marker goes with what it showed", 0L, row.editedAtMs)
        assertEquals("Apple's five edits still count", 1, extra.edits)
        // The same edit handed over again (Apple's journal after a restart): nothing changes.
        link.apply(wire(DirectArchive.Update.Edit("imsg:PU1", "Meet at 7", 0, sent + 10_000, sender = alice)))
        row = repo.message("imsg:PU1")!!
        assertEquals(0L, row.editedAtMs)
        assertEquals(1, IMessageExtra.parse(row.extra).edits)
        assertTrue(IMessageExtra.parse(row.extra).history.isEmpty())
    }

    // --- Report Junk ------------------------------------------------------------------------------------

    @Test
    fun reportJunkIsOfferedForSomeoneUnknownWhomTheUserNeverWroteTo() {
        fromAlice("A1", "You won a prize")
        assertTrue(link.junkReportable(aliceChat))
        // A group: never.
        link.apply(map(inst("G1", bob, text("Group news"), participants = listOf(me, alice, bob), groupGuid = "GRP-1")))
        assertFalse(link.junkReportable("imsg:group:GRP-1"))
        // Once the user wrote in it (a copy still being sent counts): not any more.
        repo.insertLocalOutgoing("t1", aliceChat, "Who is this?", null, null, null, null, System.currentTimeMillis())
        assertFalse(link.junkReportable(aliceChat))
    }

    @Test
    fun reportJunkIsNotOfferedForAContact() {
        context.deleteDatabase(MESSAGES_DB)
        val messagesDb = ArchiveDb(context, MESSAGES_DB)
        try {
            val messages = ArchiveRepository.forTesting(messagesDb, ChatService.MESSAGES)
            ArchiveRepository.installForTesting(messages)
            com.lightdecoder.relay.RelayManager.installForTesting(context, messages, com.lightdecoder.relay.RelayManager.Timing())
            messages.upsertContacts(listOf(ContactDto(id = "c1", name = "Alice Martin", number = "+15551234567", formattedNumber = "(555) 123-4567")))
            fromAlice("A1", "Hi, it's Alice")
            assertFalse(link.junkReportable(aliceChat))
            // Their name as the chat stores it is a contact's.
            assertEquals("Alice Martin", repo.participants(aliceChat).single { !it.isMe }.name)
        } finally {
            com.lightdecoder.relay.RelayManager.resetForTesting()
            messagesDb.close()
        }
        assertTrue(JunkReports.offered(isGroup = false, readOnly = false, deleted = false, others = 1, knownContact = false, wroteIn = false, somethingToReport = true))
        assertFalse(JunkReports.offered(isGroup = false, readOnly = false, deleted = false, others = 1, knownContact = false, wroteIn = false, somethingToReport = false))
        assertFalse(JunkReports.offered(isGroup = false, readOnly = true, deleted = false, others = 1, knownContact = false, wroteIn = false, somethingToReport = true))
        assertFalse(JunkReports.offered(isGroup = false, readOnly = false, deleted = false, others = 2, knownContact = false, wroteIn = false, somethingToReport = true))
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun aPictureAloneMakesNobodyAKnownContactForReportJunk() {
        fromAlice("A1", "You won a prize")
        // Google Messages has a picture for her number (anyone's own, sent with an RCS message), but no name.
        val bitmap = android.graphics.Bitmap.createBitmap(20, 20, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        val jpeg = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val photos = com.lightdecoder.relay.ContactPhotos.get(context)
        try {
            photos.store(mapOf("p1" to "+15551234567"), listOf(com.lightdecoder.relay.ThumbnailDto("p1", jpeg)), System.currentTimeMillis())
            assertNotNull(photos.forAddress("+15551234567"))
            assertTrue(link.junkReportable(aliceChat))
        } finally {
            photos.clear()
        }
    }

    @Test
    fun theImessageProcessReportsJunkOfAChatWithOnePersonOnlyAndOnlyWhatTheySent() {
        assertEquals(alice, DirectEngine.junkPeer(DirectWire.Target(listOf(alice), null, null, me)))
        assertEquals(alice, DirectEngine.junkPeer(DirectWire.Target(listOf(alice, me), null, null, me)))
        // A group (by its guid, or of several people): none.
        assertNull(DirectEngine.junkPeer(DirectWire.Target(listOf(alice, bob), "Trip", "GRP-1", me)))
        assertNull(DirectEngine.junkPeer(DirectWire.Target(listOf(alice), null, "GRP-1", me)))
        assertNull(DirectEngine.junkPeer(DirectWire.Target(listOf(alice, bob), null, null, me)))
        assertNull(DirectEngine.junkPeer(DirectWire.Target(emptyList(), null, null, me)))
    }

    @Test
    fun reportJunkSendsTheirLastFiveMessagesThenDeletesTheChat() {
        val base = System.currentTimeMillis() - 30 * 60_000
        for (i in 1..7) fromAlice("J$i", "Offer $i", base + i * 60_000L)
        // The chat's own "Send Read Receipts" goes with it too.
        link.setSendsReadReceipts(aliceChat, false)
        // A picture kept with one of them (its link's) goes with the chat.
        val picture = DirectLink.pictureFile(context, IMessageExtra.linkImageKey("J7")).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        val row = repo.message("imsg:J7")!!
        repo.editText(row.id, row.text, 0, IMessageExtra(link = IMessageExtra.Link("https://example.com/win", "Win", image = IMessageExtra.linkImageKey("J7"))).toJson())
        runBlocking { link.reportJunk(aliceChat) }
        val request = DirectWire.ReportJunkRequest.fromJson(asked(DirectWire.REPORT_JUNK).single())
        assertEquals(alice, request.target.peer)
        assertEquals(listOf("J3", "J4", "J5", "J6", "J7"), request.messages.map { it.guid })
        val last = request.messages.last()
        assertEquals(alice, last.sender)
        assertEquals(1, last.conversationSize)
        assertEquals("Offer 7", last.text)
        // Seconds since 1970, as OpenBubbles reports it.
        assertEquals((base + 7 * 60_000L) / 1000.0, last.timeOfMessage, 0.0005)
        assertNull("the chat is deleted here", repo.conversation(aliceChat))
        assertFalse("with its pictures", picture.exists())
        assertNull("and its own read receipts", ReadReceiptChoices(context).of(aliceChat))
    }

    @Test
    fun aReportAppleDidNotTakeKeepsTheChat() {
        fromAlice("J1", "Offer")
        answer = { DirectWire.error("Apple refused the report") }
        try {
            runBlocking { link.reportJunk(aliceChat) }
            fail("reported")
        } catch (e: DirectEngine.ImessageError) {
            assertEquals("Apple refused the report", e.message)
        }
        assertNotNull(repo.conversation(aliceChat))
        // Nor is anything reported where it is not offered.
        outgoing("S1", aliceChat, "Hello?", System.currentTimeMillis())
        answer = { DirectWire.ok() }
        try {
            runBlocking { link.reportJunk(aliceChat) }
            fail("reported")
        } catch (e: DirectEngine.ImessageError) {
            assertEquals(context.getString(com.lightdecoder.R.string.imessage_junk_not_offered), e.message)
        }
        assertEquals(1, asked(DirectWire.REPORT_JUNK).size)
    }

    @Test
    fun aJunkReportCrossesWithItsLimits() {
        val target = DirectWire.Target(listOf(alice), null, null, me)
        val messages = (1..9).map { DirectWire.JunkMessage("G$it", alice, 1, "x".repeat(10_000), 1_700_000_000.5 + it, listOf("{\"a\":1}", "d".repeat(DirectWire.MAX_DESCRIPTOR_CHARS + 1))) }
        val read = DirectWire.ReportJunkRequest.fromJson(JSONObject(DirectWire.reportJunk(target, messages).toString()))
        assertEquals(DirectWire.MAX_JUNK_MESSAGES, read.messages.size)
        assertEquals(DirectWire.MAX_JUNK_TEXT_CHARS, read.messages.first().text.length)
        assertEquals(listOf("{\"a\":1}"), read.messages.first().attachments)
        assertEquals(1_700_000_001.5, read.messages.first().timeOfMessage, 0.0)
        // One with no GUID or sender is none.
        val bad = JSONObject().put("target", target.toJson()).put("messages", org.json.JSONArray().put(JSONObject().put("guid", "").put("sender", alice)))
        assertTrue(DirectWire.ReportJunkRequest.fromJson(bad).messages.isEmpty())
        // The rows a report picks: someone else's, Apple's own, the last five.
        val rows = (1..8).map { i ->
            MessageRow(
                id = if (i == 8) "ld:x" else "imsg:R$i", conversationId = aliceChat, tmpId = "", senderId = "+15551234567", senderName = "Alice",
                isOutgoing = i == 7, timestampMs = i * 1_000L, direction = if (i == 7) "out" else "in", status = "", statusCode = 0, errorText = "",
                transport = "IMESSAGE", text = "r$i", subject = "", replyToId = "", reactions = emptyList(), localState = "synced", media = emptyList(), updatedAt = 0,
            )
        }
        assertEquals(listOf("imsg:R2", "imsg:R3", "imsg:R4", "imsg:R5", "imsg:R6"), JunkReports.pick(rows).map { it.id })
        assertEquals(alice, JunkReports.message(rows[1], target)!!.sender)
        // Imported from Messages in iCloud: never reported, nor an iCloud record id as an attachment.
        val imported = rows[1].copy(id = "imsg:R9", extra = IMessageExtra(imported = true).toJson())
        assertEquals(listOf("imsg:R2", "imsg:R3", "imsg:R4", "imsg:R5", "imsg:R6"), JunkReports.pick(rows.take(6) + imported).map { it.id })
        val part = com.lightdecoder.archive.MediaRow(
            messageId = "imsg:R2", partId = "1", mediaId = "R2/p1", key = (CloudMapping.CLOUD_KEY + "rec1").toByteArray(), thumbMediaId = "",
            thumbKey = ByteArray(0), mime = "image/jpeg", fileName = "a.jpg", size = 1, width = 0, height = 0, inlineData = ByteArray(0),
            needsFullSize = false, localPath = "", thumbPath = "", fullSizeRequested = false, stickerChecked = false,
        )
        assertTrue(JunkReports.message(rows[1].copy(media = listOf(part)), target)!!.attachments.isEmpty())
    }

    // --- a message Apple could not deliver, and pictures that go with a chat -------------------------------

    @Test
    fun aMessageAppleCouldNotDeliverIsSentAgainAsANewOne() {
        fromAlice("A1", "Hi")
        outgoing("S1", aliceChat, "Did you get this?", System.currentTimeMillis() - 30_000, status = DirectArchive.STATUS_FAILED)
        val failed = repo.message("imsg:S1")!!
        assertTrue(link.undelivered(failed))
        val tmpId = link.sendAgain("imsg:S1")
        assertNotNull(tmpId)
        waitFor("sent again") { asked(DirectWire.SEND).isNotEmpty() }
        assertEquals("Did you get this?", DirectWire.SendRequest.fromJson(asked(DirectWire.SEND).single()).text)
        assertNull("the failed one is gone", repo.message("imsg:S1"))
        // One that was delivered is no such message.
        outgoing("S2", aliceChat, "Fine", System.currentTimeMillis())
        assertFalse(link.undelivered(repo.message("imsg:S2")!!))
        assertNull(link.sendAgain("imsg:S2"))
    }

    @Test
    fun deletingAChatTakesTheLinkAndStickerPicturesKeptWithItsMessages() {
        fromAlice("P1", "Look https://example.com")
        fromAlice("P2", "Nice")
        link.apply(map(inst("O1", carol, text("Carol's"), participants = listOf(me, carol))))
        fun keep(key: String) = DirectLink.pictureFile(context, key).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(7)) }
        val linkPicture = keep(IMessageExtra.linkImageKey("P1"))
        val placed = keep(IMessageExtra.stickerKey("P2", "ST1"))
        val tapback = keep(IMessageExtra.tapbackStickerKey("P2", "+15551234567"))
        val carols = keep(IMessageExtra.linkImageKey("O1"))
        val p1 = repo.message("imsg:P1")!!
        repo.editText(p1.id, p1.text, 0, IMessageExtra(link = IMessageExtra.Link("https://example.com", "Example", image = IMessageExtra.linkImageKey("P1"))).toJson())
        val p2 = repo.message("imsg:P2")!!
        repo.editText(p2.id, p2.text, 0, IMessageExtra(stickers = listOf(IMessageExtra.PlacedSticker("ST1", "+15551234567", 1))).toJson())
        repo.applyReaction(p2.id, "+15551234567", DirectArchive.STICKER_TAPBACK, enable = true, fromMe = false, atMs = 1)
        val o1 = repo.message("imsg:O1")!!
        repo.editText(o1.id, o1.text, 0, IMessageExtra(link = IMessageExtra.Link("https://example.com", "Example", image = IMessageExtra.linkImageKey("O1"))).toJson())
        assertEquals(listOf(DirectArchive.STICKER_TAPBACK), repo.message(p2.id)!!.reactions.map { it.emoji })
        // Deleted, it waits in Recently Deleted with its pictures; deleted there, they go with it.
        runBlocking { IMessageManager.get(context).deleteConversation(aliceChat) }
        assertEquals(com.lightdecoder.relay.Folder.DELETED, repo.conversation(aliceChat)!!.folderEnum)
        assertTrue(linkPicture.exists())
        runBlocking { IMessageManager.get(context).purgeDeleted(aliceChat) }
        assertNull(repo.conversation(aliceChat))
        assertFalse(linkPicture.exists())
        assertFalse(placed.exists())
        assertFalse(tapback.exists())
        assertTrue("another chat's stays", carols.exists())
        // Deleting a message alone takes its own too.
        runBlocking { IMessageManager.get(context).deleteMessage("imsg:O1") }
        assertFalse(carols.exists())
    }

    private companion object {
        const val DB = "transcript-sync-test.db"
        const val MESSAGES_DB = "transcript-sync-messages-test.db"
    }
}
