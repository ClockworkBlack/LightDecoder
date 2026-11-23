package com.lightdecoder.ui

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageLog
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.IMessagePrefs
import com.lightdecoder.imessage.direct.DirectArchive
import com.lightdecoder.imessage.direct.DirectLibrary
import com.lightdecoder.imessage.direct.DirectLink
import com.lightdecoder.imessage.direct.DirectPrefs
import com.lightdecoder.imessage.direct.DirectSinkService
import com.lightdecoder.imessage.direct.DirectWire
import com.lightdecoder.imessage.direct.IMessageExtra
import com.lightdecoder.imessage.facetime.CallRows
import com.lightdecoder.imessage.facetime.MissedCallLimits
import com.lightdecoder.imessage.facetime.CallerInfo
import com.lightdecoder.imessage.facetime.FaceTimeNotices
import com.lightdecoder.relay.ContactDto
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.MessageNotifier
import com.lightdecoder.relay.PartDto
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.ui.chat.ChatDetailsActivity
import com.lightdecoder.ui.chat.MessagesAdapter
import com.lightdecoder.ui.chat.ThreadActivity
import com.lightdecoder.ui.chat.ThreadItem
import com.lightdecoder.ui.imessage.FaceTimeCallActivity
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FaceTime in the main process: who can be called (asked through the library, remembered a while),
 * who is calling, a call's row in the person's chat and a missed call's notice, and the chats'
 * FaceTime buttons (the camera in an iPhone-style chat's bar, the two calls in the menu elsewhere,
 * the two round buttons of a chat's details), which start the call screen and nothing else.
 */
@RunWith(AndroidJUnit4::class)
class FaceTimeChatTest {

    private lateinit var two: TwoServicesEnv
    private lateinit var imDb: ArchiveDb
    private lateinit var imRepo: ArchiveRepository
    private lateinit var iMessage: IMessageManager
    private val nm: NotificationManager get() = two.app.getSystemService(NotificationManager::class.java)

    /** What the main process asked of the ":imessage" process. */
    private val asked: MutableList<Pair<Int, JSONObject>> = Collections.synchronizedList(ArrayList())

    /** Apple's word on whether Alice can be called: true, false, or null (it cannot be asked). */
    @Volatile
    private var capable: Boolean? = true

    /** With [capable] true, only these can be called (null: everyone asked about). */
    @Volatile
    private var capableOnly: Set<String>? = null

    @Before
    fun setUp() {
        two = TwoServicesEnv()
        two.app.deleteDatabase(IM_DB)
        imDb = ArchiveDb(two.app, IM_DB)
        imRepo = ArchiveRepository.forTesting(imDb, ChatService.IMESSAGE)
        ArchiveRepository.installForTesting(imRepo)
        iMessage = IMessageManager.installForTesting(two.app, imRepo)
        IMessagePrefs.setEnabled(two.app, true)
        DirectPrefs.setPhoneStyleOn(two.app, true)
        DirectPrefs.setFaceTimeOn(two.app, true)
        DirectPrefs.setSilenceUnknownFaceTime(two.app, false)
        IMessageLog.clear(two.app)
        nm.cancelAll()
    }

    @After
    fun tearDown() {
        nm.cancelAll()
        DirectPrefs.setPhoneStyleOn(two.app, true)
        DirectPrefs.setFaceTimeOn(two.app, true)
        DirectPrefs.setSilenceUnknownFaceTime(two.app, false)
        IMessagePrefs.setEnabled(two.app, false)
        DirectPrefs.setRegistered(two.app, false)
        DirectLink.resetForTesting()
        IMessageManager.resetForTesting()
        two.tearDown()
        imDb.close()
    }

    // --- set-up ------------------------------------------------------------------------------

    /** The iMessage library set up, answering as Apple would; Alice's iMessage chat as it stores it. */
    private fun directMode(withAlice: Boolean = true) {
        DirectPrefs.setRegistered(two.app, true)
        DirectLibrary.REQUIRED.forEach { File(DirectLibrary.dir(two.app), it).apply { parentFile?.mkdirs(); writeText("") } }
        DirectLink.installForTesting(two.app) { what, request, _ ->
            asked += what to request
            when {
                what != DirectWire.FT_CAPABLE -> DirectWire.ok()
                capable == null -> DirectWire.error("iMessage is not connected.")
                capable == true -> DirectWire.capable(DirectWire.CapableRequest.fromJson(request).targets.filter { capableOnly?.contains(it) ?: true })
                else -> DirectWire.capable(emptyList())
            }
        }
        if (withAlice) chat(ALICE_CHAT, "Alice Martin", "+15551234567")
    }

    private fun chat(id: String, name: String, number: String) = imRepo.upsertConversation(
        ConversationDto(
            id = id, name = name, type = IMessageManager.TRANSPORT, defaultOutgoingId = ME,
            participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = number, name = name, number = number, formattedNumber = number)),
        ),
    )

    private fun group() = imRepo.upsertConversation(
        ConversationDto(
            id = GROUP, name = "Hikers", isGroup = true, type = IMessageManager.TRANSPORT, defaultOutgoingId = ME,
            participants = listOf(
                ParticipantDto(id = "me", isMe = true),
                ParticipantDto(id = "+15551234567", name = "Alice Martin", number = "+15551234567"),
                ParticipantDto(id = "+15557654321", name = "Bob", number = "+15557654321"),
            ),
        ),
    )

    private fun message(guid: String, text: String, mine: Boolean, chat: String = ALICE_CHAT, ts: Long = System.currentTimeMillis() - 60_000) = imRepo.upsertMessage(
        MessageDto(
            id = "imsg:$guid", conversationId = chat, fromMe = mine, senderId = if (mine) "" else "+15551234567",
            timestampMs = ts, direction = if (mine) "out" else "in", status = if (mine) "OUTGOING_COMPLETE" else "INCOMING_COMPLETE",
            transport = IMessageManager.TRANSPORT, text = text, parts = listOf(PartDto("0", "text", text)), isOld = true,
        ),
    )

    private fun callUpdate(
        guid: String = CALL,
        peer: String = ALICE,
        outgoing: Boolean = false,
        video: Boolean = true,
        outcome: String = "missed",
        durationMs: Long = 0,
        quiet: Boolean = false,
        conversationId: String = "",
        startedMs: Long = System.currentTimeMillis() - 5_000,
    ): DirectArchive.Update =
        // As it arrives from the other process.
        DirectWire.decode(JSONObject(DirectWire.encode(DirectArchive.Update.Call(guid, peer, outgoing, video, startedMs, durationMs, outcome, quiet, conversationId)).toString()))

    private val link: DirectLink get() = DirectLink.get(two.app)

    private fun launch(chat: String, service: ChatService = ChatService.IMESSAGE): ActivityScenario<ThreadActivity> =
        ActivityScenario.launch(RelayIntents.thread(two.app, chat, service = service).setFlags(0))

    private fun <A : Activity> ActivityScenario<A>.waitFor(what: String, condition: (A) -> Boolean) =
        two.waitUntil(what) {
            var ok = false
            onActivity { ok = condition(it) }
            ok
        }

    private fun menu(activity: Activity) = activity.findViewById<Toolbar>(R.id.toolbar).menu

    private fun shownTexts(activity: Activity): List<String> =
        (activity.findViewById<RecyclerView>(R.id.messages).adapter as MessagesAdapter).currentList.filterIsInstance<ThreadItem.Message>().map { it.row.text }

    private fun asks(what: Int): List<JSONObject> = asked.toList().filter { it.first == what }.map { it.second }

    // --- a call's row and notice -----------------------------------------------------------------

    @Test
    fun aMissedCallIsARowInThePersonsChatAndANoticeThatOpensIt() {
        directMode()
        message("M1", "See you", mine = true)
        val before = link.targetOf(ALICE_CHAT)!!.afterGuid
        link.apply(callUpdate(outcome = "missed", video = true))
        val row = imRepo.message(CallRows.messageId(CALL))!!
        assertEquals(ALICE_CHAT, row.conversationId)
        assertEquals("a line of the chat's own", "system", row.direction)
        assertEquals("Missed FaceTime Video", row.text)
        assertEquals(IMessageExtra.CallInfo(CALL, outgoing = false, video = true, outcome = "missed"), IMessageExtra.parse(row.extra).call)
        assertFalse(row.isOutgoing)
        assertTrue("a missed call makes the chat unread", imRepo.conversation(ALICE_CHAT)!!.unread)
        // It is no iMessage: what a send names as the chat's latest message is still the message.
        assertEquals(before, link.targetOf(ALICE_CHAT)!!.afterGuid)
        assertEquals("M1", link.targetOf(ALICE_CHAT)!!.afterGuid)
        // The notice: who, what, and a tap opens the chat.
        val n = shadowOf(nm).allNotifications.single()
        assertEquals(FaceTimeNotices.CHANNEL_MISSED, n.channelId)
        assertEquals("Alice Martin", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Missed FaceTime Video", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        val open = shadowOf(n.contentIntent).savedIntent
        assertEquals(ThreadActivity::class.java.name, open.component?.className)
        assertEquals(ALICE_CHAT, open.getStringExtra(RelayIntents.EXTRA_CONVERSATION_ID))
        // The log names the chat by its tag only.
        val log = IMessageLog.read(two.app).first()
        assertTrue(log, log.contains(IMessageLog.chatRef(ALICE_CHAT)) && log.contains("missed"))
        assertFalse(log, log.contains("5551234567") || log.contains(CALL))
        // Handed over again (Apple's journal after a restart): the same row, no second notice.
        nm.cancelAll()
        link.apply(callUpdate(outcome = "missed", video = true))
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
        assertEquals(1, imRepo.messages(ALICE_CHAT, limit = 50).count { CallRows.isCallRow(it.id) })
    }

    @Test
    fun aCallThatWasTalkedInSaysHowLongAndGoesWhereItWasPlacedFrom() {
        directMode()
        chat(OTHER_ALICE_CHAT, "Alice Martin", "+15551234567")
        link.apply(callUpdate(outgoing = true, video = false, outcome = "answered", durationMs = 12_300, conversationId = OTHER_ALICE_CHAT))
        val row = imRepo.message(CallRows.messageId(CALL))!!
        assertEquals("FaceTime Audio · 12 sec", row.text)
        assertEquals("the chat it was placed from", OTHER_ALICE_CHAT, row.conversationId)
        assertTrue(row.isOutgoing)
        assertFalse("the user's own call makes nothing unread", imRepo.conversation(OTHER_ALICE_CHAT)!!.unread)
        assertTrue("and no notice", shadowOf(nm).allNotifications.isEmpty())
        // A chat named that is not theirs (a group, someone else's, none at all) is not written into: theirs is found.
        group()
        chat(BOB_CHAT, "Bob", "+15557654321")
        for ((i, wrong) in listOf(GROUP, BOB_CHAT, "imsg:tel:+19999999999", "../../x").withIndex()) {
            val guid = "DDDDDDDD-0000-4000-8000-00000000000$i"
            link.apply(callUpdate(guid = guid, outgoing = true, outcome = "cancelled", conversationId = wrong))
            val stored = imRepo.message(CallRows.messageId(guid))!!
            assertTrue("in one of Alice's own chats, not $wrong", stored.conversationId == ALICE_CHAT || stored.conversationId == OTHER_ALICE_CHAT)
            assertEquals("FaceTime Video · Cancelled", stored.text)
        }
        assertTrue(imRepo.messages(GROUP, limit = 10).isEmpty())
        assertTrue(imRepo.messages(BOB_CHAT, limit = 10).isEmpty())
    }

    @Test
    fun aCallFromSomeoneNewMakesTheirChatAsAMessageWould() {
        directMode(withAlice = false)
        link.apply(callUpdate(peer = "tel:+15557654321", outcome = "missed", video = false))
        val row = imRepo.message(CallRows.messageId(CALL))!!
        assertEquals("imsg:tel:+15557654321", row.conversationId)
        val chat = imRepo.conversation(row.conversationId)!!
        assertFalse(chat.isGroup)
        assertEquals(listOf("+15557654321"), imRepo.participants(chat.id).filter { !it.isMe }.map { it.number })
        assertEquals("Missed FaceTime Audio", row.text)
        assertEquals("their address stands for the name nobody knows", "+15557654321", shadowOf(nm).allNotifications.single().extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        // A chat from OpenBubbles' notifications for the same person would have been reused instead.
        imRepo.upsertConversation(
            ConversationDto(
                id = "iMessage;-;+15550009999", type = IMessageManager.TRANSPORT,
                participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = "+15550009999", name = "Carol", number = "+15550009999")),
            ),
        )
        link.apply(callUpdate(guid = OTHER_CALL, peer = "tel:+15550009999", outcome = "declined"))
        assertEquals("iMessage;-;+15550009999", imRepo.message(CallRows.messageId(OTHER_CALL))!!.conversationId)
    }

    @Test
    fun theSameCallAgainNeverUndoesWhatIsKnownOfIt() {
        directMode()
        link.apply(callUpdate(outgoing = false, outcome = "answered", durationMs = 300_000))
        assertEquals("FaceTime Video · 5 min", imRepo.message(CallRows.messageId(CALL))!!.text)
        // An old ring for it, handed over after a restart, is noted as missed: the row stays, nothing is told.
        link.apply(callUpdate(outcome = "missed"))
        assertEquals("FaceTime Video · 5 min", imRepo.message(CallRows.messageId(CALL))!!.text)
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
        assertFalse(imRepo.conversation(ALICE_CHAT)!!.unread)
        // The other way round, the row says more from then on.
        link.apply(callUpdate(guid = OTHER_CALL, outcome = "missed"))
        link.apply(callUpdate(guid = OTHER_CALL, outcome = "answered", durationMs = 61_000))
        assertEquals("FaceTime Video · 1 min", imRepo.message(CallRows.messageId(OTHER_CALL))!!.text)
    }

    @Test
    fun aQuietMissedCallAndOneInAChatWithHiddenAlertsMakeNoSound() {
        directMode()
        link.apply(callUpdate(outcome = "missed", quiet = true))
        assertEquals(Notification.GROUP_ALERT_SUMMARY, shadowOf(nm).allNotifications.single().groupAlertBehavior)
        nm.cancelAll()
        imRepo.setConversationMuted(ALICE_CHAT, true)
        link.apply(callUpdate(guid = OTHER_CALL, outcome = "missed"))
        assertEquals(Notification.GROUP_ALERT_SUMMARY, shadowOf(nm).allNotifications.single().groupAlertBehavior)
        // With the chat on screen there is nothing to tell, and nothing to mark unread.
        nm.cancelAll()
        imRepo.markConversationRead(ALICE_CHAT)
        MessageNotifier.setVisibleConversationId(ChatService.IMESSAGE, ALICE_CHAT)
        link.apply(callUpdate(guid = THIRD_CALL, outcome = "missed"))
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
        assertFalse(imRepo.conversation(ALICE_CHAT)!!.unread)
        assertNotNull(imRepo.message(CallRows.messageId(THIRD_CALL)))
    }

    @Test
    fun withUnknownCallersSilencedAStrangersMissedCallMakesNoSoundHoweverItCameToBeMissed() {
        directMode()
        // The user wrote to Alice: she is known. Bob only ever wrote first: he is not.
        message("M1", "See you", mine = true)
        chat(BOB_CHAT, "+15557654321", "+15557654321")
        message("B1", "Hi, it's me", mine = false, chat = BOB_CHAT)
        fun sounds(n: Notification) = n.groupAlertBehavior != Notification.GROUP_ALERT_SUMMARY
        // Without the setting, a stranger's missed call the call engine did not mark as quiet (a group call, one during
        // another call, one the phone was busy for) sounds.
        link.apply(callUpdate(guid = OTHER_CALL, peer = "tel:+15557654321", outcome = "missed", quiet = false))
        assertTrue(sounds(shadowOf(nm).allNotifications.single()))
        nm.cancelAll()
        // With it, it does not: decided here, whatever the call engine said.
        DirectPrefs.setSilenceUnknownFaceTime(two.app, true)
        link.apply(callUpdate(guid = THIRD_CALL, peer = "tel:+15557654321", outcome = "missed", quiet = false))
        assertFalse(sounds(shadowOf(nm).allNotifications.single()))
        assertNotNull("its row is there all the same", imRepo.message(CallRows.messageId(THIRD_CALL)))
        nm.cancelAll()
        // Someone the user knows still sounds.
        link.apply(callUpdate(guid = CALL, peer = ALICE, outcome = "missed", quiet = false))
        assertTrue(sounds(shadowOf(nm).allNotifications.single()))
    }

    @Test
    fun strangersMissedCallsMakeOnlySoManyNewChatsAndNoticesAnHour() {
        directMode()
        message("M1", "See you", mine = true)
        fun call(i: Int) = "CCCCCCCC-0000-4000-8000-%012d".format(i)
        fun stranger(i: Int) = "tel:+1555800%04d".format(i)
        // Twenty strangers, each from an address of their own.
        for (i in 1..20) link.apply(callUpdate(guid = call(i), peer = stranger(i), outcome = "missed"))
        val made = (1..20).filter { imRepo.conversation("imsg:" + stranger(it)) != null }
        assertEquals("only the first few made a chat", (1..MissedCallLimits.MAX_NEW_CHATS_PER_HOUR).toList(), made)
        assertEquals(MissedCallLimits.MAX_NEW_CHATS_PER_HOUR, (1..20).count { imRepo.message(CallRows.messageId(call(it))) != null })
        assertEquals(MissedCallLimits.MAX_NEW_CHATS_PER_HOUR, shadowOf(nm).allNotifications.size)
        assertTrue(IMessageLog.read(two.app).any { it.contains("too many new chats") && !it.contains("5558") })
        // A stranger who already has a chat here makes no new one: their rows are stored, their notices are limited too.
        nm.cancelAll()
        for (i in 21..40) link.apply(callUpdate(guid = call(i), peer = stranger(1), outcome = "missed"))
        assertEquals(20, (21..40).count { imRepo.message(CallRows.messageId(call(it))) != null })
        // (One notice a chat: counted by what the limit let through, in the log.)
        val refused = IMessageLog.read(two.app).count { it.contains("notice was not shown") }
        assertEquals(20 - (MissedCallLimits.MAX_UNKNOWN_NOTICES_PER_HOUR - MissedCallLimits.MAX_NEW_CHATS_PER_HOUR), refused)
        // Someone the user knows is still told.
        nm.cancelAll()
        link.apply(callUpdate(guid = CALL, peer = ALICE, outcome = "missed"))
        assertEquals("Alice Martin", shadowOf(nm).allNotifications.single().extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNotNull(imRepo.message(CallRows.messageId(CALL)))
    }

    @Test
    fun whatTheCallEngineDidIsWrittenToTheIMessageLogWithoutNumbers() {
        directMode()
        val reply = DirectSinkService.take(DirectWire.encode(DirectArchive.Update.Note("call#3f9a1c: ringing (video) from tel:+15551234567"))) { link.apply(it) }
        assertNull(DirectWire.errorOf(reply))
        val line = IMessageLog.read(two.app).first()
        assertTrue(line, line.contains("FaceTime: call#3f9a1c: ringing (video)"))
        assertFalse("a number that got into a line is taken out", line.contains("5551234567"))
    }

    // --- who is calling --------------------------------------------------------------------------

    @Test
    fun aCallerIsKnownByTheirContactNameOrByAChatTheUserWroteIn() {
        two.env.pairedManager()
        directMode(withAlice = false)
        // Nobody: no name, no chat.
        assertEquals(CallerInfo("", known = false), link.callerInfo(ALICE))
        // A chat only they wrote in (anyone can write first) makes nobody known.
        chat(ALICE_CHAT, "+15551234567", "+15551234567")
        message("M1", "Hi, it's me", mine = false)
        val wroteFirst = link.callerInfo(ALICE)
        assertFalse(wroteFirst.known)
        assertEquals("", wroteFirst.name)
        assertEquals("their chat, for the call's row and Message", ALICE_CHAT, wroteFirst.conversationId)
        // Once the user wrote to them, they are.
        message("M2", "Hello!", mine = true)
        assertTrue(link.callerInfo(ALICE).known)
        // Or called them.
        chat(BOB_CHAT, "+15557654321", "+15557654321")
        assertFalse(link.callerInfo("tel:+15557654321").known)
        link.apply(callUpdate(guid = OTHER_CALL, peer = "tel:+15557654321", outgoing = true, outcome = "unanswered"))
        assertTrue(link.callerInfo("tel:+15557654321").known)
        // A contact is known by name, with or without a chat.
        two.env.repo.upsertContacts(listOf(ContactDto(id = "k1", name = "Carol Jones", number = "+15550009999")))
        assertEquals(CallerInfo("Carol Jones", known = true), link.callerInfo("tel:+15550009999"))
        // Asked from the other process, it is answered the same; what is no address is not looked up.
        val server = DirectWire.callerReply(link.callerInfo(ALICE))
        assertTrue(DirectWire.callerInfo(server).known)
        assertNull(DirectWire.callerOf(JSONObject().put("handle", "everyone")))
    }

    // --- who can be called -----------------------------------------------------------------------

    @Test
    fun whetherSomeoneCanBeCalledIsAskedOnceAndRemembered() = runBlocking {
        directMode()
        assertNull("not known before Apple was asked", link.faceTimeCapableKnown(ALICE_CHAT))
        assertEquals(true, link.faceTimeCapable(ALICE_CHAT))
        val request = asks(DirectWire.FT_CAPABLE).single()
        assertEquals(listOf(ALICE), DirectWire.CapableRequest.fromJson(request).targets)
        assertEquals("asked from the address the chat is carried on", ME, DirectWire.CapableRequest.fromJson(request).from)
        assertEquals(true, link.faceTimeCapable(ALICE_CHAT))
        assertEquals(true, link.faceTimeCapableKnown(ALICE_CHAT))
        assertEquals("remembered: not asked again", 1, asks(DirectWire.FT_CAPABLE).size)
        // A day later it is asked again.
        assertEquals(true, link.faceTimeCapable(ALICE_CHAT, System.currentTimeMillis() + 24 * 3_600_000L))
        assertEquals(2, asks(DirectWire.FT_CAPABLE).size)
        // A group, and a chat that is none, are never asked about.
        group()
        assertEquals(false, link.faceTimeCapable(GROUP))
        assertEquals(false, link.faceTimeCapable("imsg:tel:+19999999999"))
        assertNull(link.faceTimeTarget(GROUP))
        assertEquals(2, asks(DirectWire.FT_CAPABLE).size)
    }

    @Test
    fun whenAppleCannotBeAskedNothingIsRememberedAndWhoCannotBeCalledIs() = runBlocking {
        directMode()
        capable = null
        assertNull(link.faceTimeCapable(ALICE_CHAT))
        assertNull(link.faceTimeCapableKnown(ALICE_CHAT))
        capable = false
        assertEquals(false, link.faceTimeCapable(ALICE_CHAT))
        assertEquals(false, link.faceTimeCapableKnown(ALICE_CHAT))
        assertEquals(2, asks(DirectWire.FT_CAPABLE).size)
        // With FaceTime off nobody is asked about, and no call goes anywhere.
        DirectPrefs.setFaceTimeOn(two.app, false)
        assertEquals(false, link.faceTimeCapable(ALICE_CHAT, System.currentTimeMillis() + 24 * 3_600_000L))
        assertNull(link.faceTimeTarget(ALICE_CHAT))
        assertEquals(2, asks(DirectWire.FT_CAPABLE).size)
    }

    // --- the chats' buttons ----------------------------------------------------------------------

    @Test
    @Config(qualifiers = "w390dp-h800dp-xhdpi")
    fun anIPhoneStyleChatHasACameraInItsBarThatAsksVideoOrAudioAndStartsTheCallScreen() {
        directMode()
        message("M1", "See you", mine = true)
        launch(ALICE_CHAT).use { scenario ->
            scenario.waitFor("the chat and Apple's word") { shownTexts(it).contains("See you") && asks(DirectWire.FT_CAPABLE).isNotEmpty() && it.faceTimeOffered() }
            scenario.onActivity { activity ->
                assertTrue(activity.phoneStyle)
                assertEquals(ALICE_CHAT, activity.faceTimeChat)
                val camera = menu(activity).findItem(R.id.action_facetime)
                assertTrue("a camera in the bar, as on an iPhone", camera.isVisible)
                assertEquals("FaceTime", camera.title.toString())
                assertFalse(menu(activity).findItem(R.id.action_facetime_video).isVisible)
                assertFalse(menu(activity).findItem(R.id.action_facetime_audio).isVisible)
                // It asks: Video or Audio.
                val popup = activity.chooseFaceTime(activity.findViewById(R.id.toolbar))!!
                assertEquals(listOf("FaceTime Video", "FaceTime Audio"), (0 until popup.menu.size()).map { popup.menu.getItem(it).title.toString() })
                assertNull("nothing is started by asking", shadowOf(two.app).nextStartedActivity)
                popup.menu.performIdentifierAction(popup.menu.getItem(0).itemId, 0)
            }
            two.waitUntil("the call screen started") { shadowOf(two.app).peekNextStartedActivity() != null }
            val started = shadowOf(two.app).nextStartedActivity
            assertEquals(FaceTimeCallActivity::class.java.name, started.component?.className)
            assertEquals(two.app.packageName, started.component?.packageName)
            assertEquals(FaceTimeCallActivity.ACTION_PLACE, started.action)
            assertEquals(ALICE, started.getStringExtra(FaceTimeCallActivity.EXTRA_PEER))
            assertEquals("Alice Martin", started.getStringExtra(FaceTimeCallActivity.EXTRA_NAME))
            assertTrue(started.getBooleanExtra(FaceTimeCallActivity.EXTRA_VIDEO, false))
            assertEquals(ME, started.getStringExtra(FaceTimeCallActivity.EXTRA_FROM))
            assertEquals(ALICE_CHAT, started.getStringExtra(FaceTimeCallActivity.EXTRA_CONVERSATION))
            assertTrue("made just now", android.os.SystemClock.elapsedRealtime() - started.getLongExtra(FaceTimeCallActivity.EXTRA_AT, 0) < 20_000)
            // Audio, the same way.
            scenario.onActivity { it.startFaceTime(video = false) }
            two.waitUntil("the call screen started again") { shadowOf(two.app).peekNextStartedActivity() != null }
            assertFalse(shadowOf(two.app).nextStartedActivity.getBooleanExtra(FaceTimeCallActivity.EXTRA_VIDEO, true))
            assertTrue("the main process never asks the library to call: the call screen does", asks(DirectWire.SEND).isEmpty())
        }
    }

    @Test
    fun withIPhoneStyleOffTheTwoCallsAreInTheMenu() {
        DirectPrefs.setPhoneStyleOn(two.app, false)
        directMode()
        message("M1", "See you", mine = true)
        launch(ALICE_CHAT).use { scenario ->
            scenario.waitFor("the chat") { shownTexts(it).contains("See you") && it.faceTimeOffered() }
            scenario.onActivity { activity ->
                assertFalse(activity.phoneStyle)
                assertFalse(menu(activity).findItem(R.id.action_facetime).isVisible)
                val video = menu(activity).findItem(R.id.action_facetime_video)
                val audio = menu(activity).findItem(R.id.action_facetime_audio)
                assertTrue(video.isVisible && audio.isVisible)
                assertEquals("FaceTime Video", video.title.toString())
                assertEquals("FaceTime Audio", audio.title.toString())
                activity.onOptionsItemSelected(audio)
            }
            two.waitUntil("the call screen started") { shadowOf(two.app).peekNextStartedActivity() != null }
            val started = shadowOf(two.app).nextStartedActivity
            assertEquals(FaceTimeCallActivity.ACTION_PLACE, started.action)
            assertFalse(started.getBooleanExtra(FaceTimeCallActivity.EXTRA_VIDEO, true))
        }
    }

    @Test
    fun aMessagesConversationWithThePersonsIMessageChatHasFaceTimeToo() {
        two.env.pairedManager()
        directMode()
        two.seedConversation(ChatService.MESSAGES, "c1", "Alice Martin", lastTs = 1_000, number = "+1 555-123-4567")
        message("M1", "Blue one", mine = false)
        launch("c1", ChatService.MESSAGES).use { scenario ->
            scenario.waitFor("her iMessage in the conversation") { shownTexts(it).contains("Blue one") && it.faceTimeOffered() }
            scenario.onActivity { activity ->
                assertEquals("the call goes from her iMessage chat", ALICE_CHAT, activity.faceTimeChat)
                assertTrue(menu(activity).findItem(R.id.action_facetime).isVisible)
            }
        }
    }

    @Test
    fun thereAreNoFaceTimeButtonsWhereNoCallCouldGo() {
        directMode()
        // A group none of whose members has an address a call can go to (SPEC2 B2: a group with some has them, see below).
        imRepo.upsertConversation(
            ConversationDto(
                id = GROUP, name = "Hikers", isGroup = true, type = IMessageManager.TRANSPORT, defaultOutgoingId = ME,
                participants = listOf(ParticipantDto(id = "me", isMe = true), ParticipantDto(id = "Trail news", name = "Trail news"), ParticipantDto(id = "Hikers bot", name = "Bot")),
            ),
        )
        message("G1", "Hello all", mine = true, chat = GROUP)
        message("M1", "See you", mine = true)
        fun assertNone(activity: ThreadActivity, why: String) {
            assertFalse(why, activity.faceTimeOffered())
            for (id in listOf(R.id.action_facetime, R.id.action_facetime_video, R.id.action_facetime_audio)) assertFalse(why, menu(activity).findItem(id).isVisible)
        }
        launch(GROUP).use { scenario ->
            scenario.waitFor("the group") { shownTexts(it).contains("Hello all") }
            two.settle()
            scenario.onActivity { activity ->
                assertNone(activity, "a group nobody of which can be called")
                assertNull(activity.faceTimeChat)
                assertNull("and asking says so instead of calling", activity.chooseFaceTime(activity.findViewById(R.id.toolbar)))
            }
        }
        assertTrue("Apple is not asked about such a group", asks(DirectWire.FT_CAPABLE).isEmpty())
        // Someone Apple says cannot be called: the buttons go once the answer is in.
        capable = false
        launch(ALICE_CHAT).use { scenario ->
            scenario.waitFor("Apple's answer") { shownTexts(it).contains("See you") && asks(DirectWire.FT_CAPABLE).isNotEmpty() && !it.faceTimeOffered() }
            scenario.onActivity { assertNone(it, "not on FaceTime") }
        }
        // "FaceTime" off.
        capable = true
        DirectLink.resetForTesting()
        directMode()
        DirectPrefs.setFaceTimeOn(two.app, false)
        asked.clear()
        launch(ALICE_CHAT).use { scenario ->
            scenario.waitFor("the chat") { shownTexts(it).contains("See you") }
            two.settle()
            scenario.onActivity { activity ->
                assertNone(activity, "FaceTime is off")
                activity.startFaceTime(video = true)
            }
            two.settle()
            assertNull("nothing is started with FaceTime off", shadowOf(two.app).nextStartedActivity)
        }
        assertTrue("nor is Apple asked", asks(DirectWire.FT_CAPABLE).isEmpty())
    }

    @Test
    fun withoutTheLibraryThereIsNoFaceTime() {
        // Blue Bubbles through the OpenBubbles app alone: the chat is there, the library is not.
        chat(ALICE_CHAT, "Alice Martin", "+15551234567")
        message("M1", "See you", mine = true)
        launch(ALICE_CHAT).use { scenario ->
            scenario.waitFor("the chat") { shownTexts(it).contains("See you") }
            two.settle()
            scenario.onActivity { activity ->
                assertFalse(activity.faceTimeOffered())
                assertFalse(menu(activity).findItem(R.id.action_facetime).isVisible)
                assertFalse(menu(activity).findItem(R.id.action_facetime_video).isVisible)
            }
        }
    }

    @Test
    fun aCallsRowShowsInTheChatWithItsSignAndATapCallsBack() {
        directMode()
        message("M1", "See you", mine = true, ts = System.currentTimeMillis() - 120_000)
        link.apply(callUpdate(outcome = "missed", video = true, startedMs = System.currentTimeMillis() - 30_000))
        link.apply(callUpdate(guid = OTHER_CALL, outgoing = true, video = false, outcome = "answered", durationMs = 300_000, startedMs = System.currentTimeMillis() - 20_000))
        launch(ALICE_CHAT).use { scenario ->
            scenario.waitFor("the rows") { shownTexts(it).containsAll(listOf("Missed FaceTime Video", "FaceTime Audio · 5 min")) && it.faceTimeOffered() }
            two.settle()
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.messages)
                fun row(text: String): TextView = (0 until list.childCount).mapNotNull { list.getChildAt(it).findViewById<TextView>(R.id.systemText) }
                    .first { it.text.toString() == text }
                val missed = row("Missed FaceTime Video")
                assertNotNull("its sign before the words", missed.compoundDrawablesRelative[0])
                assertEquals("a missed call is red", activity.getColor(R.color.phone_red), missed.currentTextColor)
                assertEquals("Incoming call: Missed FaceTime Video. Tap to call back.", missed.contentDescription.toString())
                val answered = row("FaceTime Audio · 5 min")
                assertNotNull(answered.compoundDrawablesRelative[0])
                assertTrue(answered.currentTextColor != activity.getColor(R.color.phone_red))
                assertEquals("Outgoing call: FaceTime Audio · 5 min. Tap to call back.", answered.contentDescription.toString())
                assertTrue(answered.hasOnClickListeners())
                // A tap asks Video or Audio; a long press does nothing a message's would.
                missed.performClick()
                activity.onMessageLongPress(imRepo.message(CallRows.messageId(CALL))!!)
            }
            two.settle()
            val popup = org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu()
            assertNotNull("asked at the row", popup)
            popup.menu.performIdentifierAction(popup.menu.getItem(1).itemId, 0)
            two.waitUntil("the call screen started") { shadowOf(two.app).peekNextStartedActivity() != null }
            val started = shadowOf(two.app).nextStartedActivity
            assertEquals(FaceTimeCallActivity.ACTION_PLACE, started.action)
            assertFalse("the second choice is audio", started.getBooleanExtra(FaceTimeCallActivity.EXTRA_VIDEO, true))
            // Opening the chat read it: the missed call's notice is gone.
            assertTrue(shadowOf(nm).allNotifications.none { it.channelId == FaceTimeNotices.CHANNEL_MISSED })
        }
    }

    @Test
    fun aGroupChatHasFaceTimeForItsMembersAndCallsOnlyThoseWhoCanBeCalledAtOnce() {
        directMode()
        group()
        message("G1", "Hello all", mine = true, chat = GROUP)
        // Apple says only Alice can be called.
        capableOnly = setOf(ALICE)
        launch(GROUP).use { scenario ->
            scenario.waitFor("the group and Apple's word") { shownTexts(it).contains("Hello all") && asks(DirectWire.FT_CAPABLE).isNotEmpty() && it.faceTimeOffered() }
            scenario.onActivity { activity ->
                assertEquals(GROUP, activity.faceTimeChat)
                assertTrue(menu(activity).findItem(R.id.action_facetime).isVisible)
                activity.startFaceTime(video = true)
            }
            two.waitUntil("the call screen started") { shadowOf(two.app).peekNextStartedActivity() != null }
            val started = shadowOf(two.app).nextStartedActivity
            assertEquals(FaceTimeCallActivity.ACTION_PLACE, started.action)
            assertEquals("only who can be called", listOf(ALICE), started.getStringArrayListExtra(FaceTimeCallActivity.EXTRA_PEERS))
            assertEquals(GROUP, started.getStringExtra(FaceTimeCallActivity.EXTRA_CONVERSATION))
        }
        // Apple was asked about all its members at once, from the address the chat is carried on.
        val request = DirectWire.CapableRequest.fromJson(asks(DirectWire.FT_CAPABLE).first())
        assertEquals(listOf(ALICE, BOB), request.targets)
        assertEquals(ME, request.from)
        // Nobody of it can be called: no buttons.
        DirectLink.resetForTesting()
        directMode()
        capable = false
        launch(GROUP).use { scenario ->
            scenario.waitFor("Apple's answer") { shownTexts(it).contains("Hello all") && asks(DirectWire.FT_CAPABLE).size >= 2 && !it.faceTimeOffered() }
        }
    }

    @Test
    fun aGroupCallsRowGoesInTheGroupChatOfExactlyItsPeopleAndNowhereElse() {
        directMode()
        group()
        // An incoming group call from Alice, its list naming the group's people: in the group chat.
        link.apply(DirectWire.decode(JSONObject(DirectWire.encode(DirectArchive.Update.Call(CALL, ALICE, false, true, System.currentTimeMillis() - 5_000, 0, "missed", group = true, members = listOf(ALICE, BOB))).toString())))
        assertEquals(GROUP, imRepo.message(CallRows.messageId(CALL))!!.conversationId)
        // A stranger naming the group's people, or a list of other people: never there.
        link.apply(DirectWire.decode(JSONObject(DirectWire.encode(DirectArchive.Update.Call(OTHER_CALL, MALLORY, false, true, System.currentTimeMillis() - 4_000, 0, "missed", group = true, members = listOf(MALLORY, ALICE, BOB))).toString())))
        assertEquals("imsg:$MALLORY", imRepo.message(CallRows.messageId(OTHER_CALL))!!.conversationId)
        link.apply(DirectWire.decode(JSONObject(DirectWire.encode(DirectArchive.Update.Call(THIRD_CALL, ALICE, false, true, System.currentTimeMillis() - 3_000, 0, "missed", group = true, members = listOf(ALICE, MALLORY))).toString())))
        assertTrue("in a chat of Alice's own", imRepo.message(CallRows.messageId(THIRD_CALL))!!.conversationId in setOf(ALICE_CHAT, OTHER_ALICE_CHAT))
        assertEquals(1, imRepo.messages(GROUP, limit = 10).size)
        // A placed one goes where it was placed from.
        val placed = "EEEEEEEE-0000-4000-8000-000000000005"
        link.apply(DirectWire.decode(JSONObject(DirectWire.encode(DirectArchive.Update.Call(placed, ALICE, true, false, System.currentTimeMillis(), 60_000, "answered", conversationId = GROUP, group = true, members = listOf(ALICE, BOB))).toString())))
        assertEquals(GROUP, imRepo.message(CallRows.messageId(placed))!!.conversationId)
    }

    // --- a chat's details ------------------------------------------------------------------------

    @Test
    fun theDetailsOfAChatWithOnePersonHaveTheTwoRoundButtons() {
        directMode()
        ActivityScenario.launch<ChatDetailsActivity>(ChatDetailsActivity.intent(two.app, ALICE_CHAT, ChatService.IMESSAGE)).use { scenario ->
            scenario.waitFor("the details") { it.details?.faceTime != null && it.findViewById<View>(R.id.faceTimeRow).isShown }
            scenario.onActivity { activity ->
                val audio = activity.findViewById<View>(R.id.faceTimeAudio)
                val video = activity.findViewById<View>(R.id.faceTimeVideo)
                assertTrue("a tap through another app's window places no call", audio.filterTouchesWhenObscured && video.filterTouchesWhenObscured)
                assertTrue(audio.isShown && video.isShown)
                assertEquals("FaceTime Audio call", audio.contentDescription.toString())
                assertEquals("FaceTime Video call", video.contentDescription.toString())
                audio.performClick()
                val started = shadowOf(two.app).nextStartedActivity
                assertEquals(FaceTimeCallActivity::class.java.name, started.component?.className)
                assertEquals(FaceTimeCallActivity.ACTION_PLACE, started.action)
                assertEquals(ALICE, started.getStringExtra(FaceTimeCallActivity.EXTRA_PEER))
                assertFalse(started.getBooleanExtra(FaceTimeCallActivity.EXTRA_VIDEO, true))
                video.performClick()
                assertTrue(shadowOf(two.app).nextStartedActivity.getBooleanExtra(FaceTimeCallActivity.EXTRA_VIDEO, false))
            }
        }
    }

    @Test
    fun aGroupsDetailsCallItsMembersAndAChatsWithFaceTimeOffHaveNone() {
        directMode()
        group()
        // SPEC2 B2: a group's details have the two round buttons too, for one call to its members.
        ActivityScenario.launch<ChatDetailsActivity>(ChatDetailsActivity.intent(two.app, GROUP, ChatService.IMESSAGE)).use { scenario ->
            scenario.waitFor("the details") { it.details?.faceTimeGroup != null && it.findViewById<View>(R.id.faceTimeRow).isShown }
            scenario.onActivity { activity ->
                assertNull(activity.details!!.faceTime)
                activity.findViewById<View>(R.id.faceTimeVideo).performClick()
                val started = shadowOf(two.app).nextStartedActivity
                assertEquals(FaceTimeCallActivity.ACTION_PLACE, started.action)
                assertEquals(listOf(ALICE, BOB), started.getStringArrayListExtra(FaceTimeCallActivity.EXTRA_PEERS))
                assertEquals(GROUP, started.getStringExtra(FaceTimeCallActivity.EXTRA_CONVERSATION))
                assertEquals("Hikers", started.getStringExtra(FaceTimeCallActivity.EXTRA_NAME))
            }
        }
        // Apple's word on the group's members is forgotten for what follows.
        DirectLink.resetForTesting()
        directMode()
        DirectPrefs.setFaceTimeOn(two.app, false)
        ActivityScenario.launch<ChatDetailsActivity>(ChatDetailsActivity.intent(two.app, ALICE_CHAT, ChatService.IMESSAGE)).use { scenario ->
            scenario.waitFor("the details") { it.details != null }
            two.settle()
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<View>(R.id.faceTimeRow).isShown)
                activity.startFaceTime(video = true)
                assertNull(shadowOf(two.app).nextStartedActivity)
            }
        }
        // Someone who cannot be called: the buttons go once Apple said so.
        DirectPrefs.setFaceTimeOn(two.app, true)
        capable = false
        ActivityScenario.launch<ChatDetailsActivity>(ChatDetailsActivity.intent(two.app, ALICE_CHAT, ChatService.IMESSAGE)).use { scenario ->
            scenario.waitFor("Apple's answer") { asks(DirectWire.FT_CAPABLE).isNotEmpty() && it.details != null && it.details!!.faceTime == null }
            scenario.onActivity { assertFalse(it.findViewById<View>(R.id.faceTimeRow).isShown) }
        }
    }

    private companion object {
        const val IM_DB = "facetime-chat-test.db"
        const val ME = "tel:+15550000001"
        const val ALICE = "tel:+15551234567"
        const val BOB = "tel:+15557654321"
        const val MALLORY = "tel:+15559990000"
        const val ALICE_CHAT = "imsg:tel:+15551234567"
        const val OTHER_ALICE_CHAT = "iMessage;-;+15551234567"
        const val BOB_CHAT = "imsg:tel:+15557654321"
        const val GROUP = "imsg:group:11111111-2222-3333-4444-555555555555"
        const val CALL = "BBBBBBBB-0000-4000-8000-000000000002"
        const val OTHER_CALL = "CCCCCCCC-0000-4000-8000-000000000003"
        const val THIRD_CALL = "DDDDDDDD-0000-4000-8000-0000000000FF"
    }
}
