package com.lightdecoder.imessage.direct

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.MediaCache
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.IMessagePrefs
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.MessageNotifier
import java.io.File
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import uniffi.rust_bridge.Balloon
import uniffi.rust_bridge.BalloonLayout
import uniffi.rust_bridge.ConversationData
import uniffi.rust_bridge.ExtensionApp
import uniffi.rust_bridge.IndexedMessagePart
import uniffi.rust_bridge.MessageInst
import uniffi.rust_bridge.MessagePart
import uniffi.rust_bridge.MessageType
import uniffi.rust_bridge.NormalMessage
import uniffi.rust_bridge.NsDictionaryClass
import uniffi.rust_bridge.PushChatMessage
import uniffi.rust_bridge.ReactMessage
import uniffi.rust_bridge.ReactMessageType
import uniffi.rust_bridge.TextFlags
import uniffi.rust_bridge.TextFormat

/**
 * iMessage apps (games) through the iMessage library, main-process side: a new card and a move
 * sent for an extension app, moves from Apple handed to it, and a chat kept quiet while it asks.
 * The ":imessage" process is played by a fake that answers as it does.
 */
@RunWith(AndroidJUnit4::class)
class DirectAppsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ArchiveDb
    private lateinit var repo: ArchiveRepository
    private val me = "tel:+15550000001"
    private val bob = "tel:+15559876543"
    private val chat = "imsg:$bob"
    private val session = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private val gamePigeon = "com.apple.messages.MSMessageExtensionBalloonPlugin:EWFNLB79LQ:com.gamerdelights.gamepigeon.ext"

    @Before
    fun setUp() {
        context.deleteDatabase(DB)
        db = ArchiveDb(context, DB)
        repo = ArchiveRepository.forTesting(db, ChatService.IMESSAGE)
        ArchiveRepository.installForTesting(repo)
        IMessageManager.installForTesting(context, repo)
        DirectPrefs.setRegistered(context, true)
        DirectLibrary.REQUIRED.forEach { File(DirectLibrary.dir(context), it).apply { parentFile?.mkdirs(); writeText("") } }
        MediaCache.mediaDir(context, ChatService.IMESSAGE).deleteRecursively()
        MediaCache.outboxDir(context, ChatService.IMESSAGE).deleteRecursively()
    }

    @After
    fun tearDown() {
        DirectLink.appEvents = null
        MessageNotifier.cancelAll(context, ChatService.IMESSAGE)
        IMessagePrefs.setEnabled(context, false)
        DirectPrefs.setRegistered(context, false)
        DirectLink.resetForTesting()
        IMessageManager.resetForTesting()
        ArchiveRepository.resetForTesting()
        db.close()
    }

    private val asked = Collections.synchronizedList(ArrayList<Pair<Int, JSONObject>>())

    private fun link(answer: (Int, JSONObject) -> JSONObject): DirectLink =
        DirectLink.installForTesting(context) { what, request, _ ->
            asked += what to request
            answer(what, request)
        }

    private fun requests(what: Int) = synchronized(asked) { asked.filter { it.first == what }.map { it.second } }

    /** Apple's answers as the ":imessage" process gives them: the app's summary is part 0, its picture part 1. */
    private fun answers(guid: String, fail: Boolean = false): (Int, JSONObject) -> JSONObject = { what, request ->
        when {
            what != DirectWire.SEND && what != DirectWire.EXTENSION -> DirectWire.ok()
            fail -> DirectWire.error("Apple refused the message: busy")
            what == DirectWire.SEND -> {
                val r = DirectWire.SendRequest.fromJson(request)
                DirectWire.sent(guid, 1_700_000_050_000L, r.attachments.map { DirectWire.SentPart(1, "p1", it.mime, it.name, File(it.path).length()) })
            }
            else -> {
                val r = DirectWire.ExtensionRequest.fromJson(request)
                DirectWire.sent(guid, 1_700_000_060_000L, listOfNotNull(r.imagePath?.let { DirectWire.SentPart(1, "p1", "image/jpeg", "jpeg-image.jpeg", File(it).length()) }))
            }
        }
    }

    private fun text(t: String) = IndexedMessagePart(MessagePart.Text(t, TextFormat.Flags(TextFlags(false, false, false, false))), 0UL, null)

    private fun bobsChat(link: DirectLink) {
        link.apply(
            DirectArchive.map(
                MessageInst(
                    "B0", bob, ConversationData(listOf(me, bob), null, null, null),
                    PushChatMessage.Message(NormalMessage(listOf(text("hi")), null, null, null, MessageType.IMessage, null, null, null, false, null, null)),
                    1_699_999_000_000UL, null, false, false, null,
                ),
                setOf(me), Handles::display, DirectTexts(context),
            ),
        )
    }

    private val card = IMessageExtra.App(
        name = "GamePigeon", appId = 1124197642L, bundleId = gamePigeon, url = "data:?ver=52&data=a%2Bb", session = session,
        ldText = "8 Ball", isLive = true, caption = "Let's play!",
    )

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)

    @Test
    fun aNewGameGoesAsACardWithItsPictureAndStartsItsSession() {
        val link = link(answers("C1"))
        bobsChat(link)
        val tmpId = link.sendAppCard(chat, card, jpeg)
        val local = repo.message(MessageRow.tmpMessageId(tmpId))
        if (local != null) assertEquals("Let's play!", local.text)
        waitFor("the sent card") { repo.message("imsg:C1") != null }
        val request = DirectWire.SendRequest.fromJson(requests(DirectWire.SEND).single())
        assertEquals("the card has no words of its own", "", request.text)
        assertEquals(card.url, request.app!!.url)
        assertEquals(session, request.app!!.session)
        assertEquals(1124197642L, request.app!!.appId)
        assertEquals("image/jpeg", request.attachments.single().mime)
        assertEquals("jpeg-image.jpeg", request.attachments.single().name)
        val sent = repo.message("imsg:C1")!!
        assertEquals("Let's play!", sent.text)
        val app = IMessageExtra.parse(sent.extra).app!!
        assertEquals("its own session's first card", "imsg:C1", app.root)
        assertEquals(card.url, app.url)
        val part = sent.media.single()
        assertEquals("p1", part.partId)
        assertArrayEquals(jpeg, File(part.localPath).readBytes())
    }

    @Test
    fun aMoveGoesToItsSessionsFirstCardAndIsStoredAsACardOfItsOwn() {
        val link = link(answers("MV1"))
        bobsChat(link)
        val move = card.copy(caption = "Your move", url = "data:?ver=52&data=c")
        assertTrue(runBlocking { link.sendAppUpdate(chat, "imsg:ROOT", move, jpeg) }.sent)
        val request = DirectWire.ExtensionRequest.fromJson(requests(DirectWire.EXTENSION).single())
        assertEquals("ROOT", request.rootGuid)
        assertFalse(request.isMeta)
        assertEquals("data:?ver=52&data=c", request.app.url)
        assertEquals(listOf(bob), request.target.participants)
        assertNotNull(request.imagePath)
        val row = repo.message("imsg:MV1")!!
        assertTrue(row.isOutgoing)
        assertEquals("Your move", row.text)
        assertEquals("imsg:ROOT", IMessageExtra.parse(row.extra).app!!.root)
        assertArrayEquals(jpeg, File(row.media.single().localPath).readBytes())
        assertFalse("the picture's copy moved to the move's media", File(request.imagePath!!).exists())
    }

    @Test
    fun aMoveWithoutAPictureGoesWithoutAndShowsTheSessionsLastPicture() {
        val link = link(answers("MV2"))
        bobsChat(link)
        // Nothing of the session shown yet: no picture.
        assertTrue(runBlocking { link.sendAppUpdate(chat, "imsg:ROOT", card, null) }.sent)
        assertNull(DirectWire.ExtensionRequest.fromJson(requests(DirectWire.EXTENSION).single()).imagePath)
        assertTrue(repo.message("imsg:MV2")!!.media.isEmpty())
    }

    @Test
    fun aMoveWithoutAPictureShowsTheSessionsPreviousOneHere() {
        var guid = "C1"
        val link = link { what, request -> answers(guid)(what, request) }
        bobsChat(link)
        link.sendAppCard(chat, card, jpeg)
        waitFor("the sent card") { repo.message("imsg:C1") != null }
        assertEquals(link.latestOfAppSession(chat, session)?.id, "imsg:C1")
        guid = "MV4"
        assertTrue(runBlocking { link.sendAppUpdate(chat, "imsg:C1", card.copy(url = "data:2"), null) }.sent)
        assertNull("Apple is sent no picture", DirectWire.ExtensionRequest.fromJson(requests(DirectWire.EXTENSION).single()).imagePath)
        val move = repo.message("imsg:MV4")!!
        val shown = move.media.single()
        assertArrayEquals(jpeg, File(shown.localPath).readBytes())
        assertTrue("a copy of its own", shown.localPath != repo.message("imsg:C1")!!.media.single().localPath)
        assertEquals("the move is now the session's newest", "imsg:MV4", link.latestOfAppSession(chat, session)?.id)
        assertNull(link.latestOfAppSession(chat, "11111111-2222-3333-4444-555555555555"))
    }

    @Test
    fun aMoveAppleRefusedIsNotStored() {
        val link = link(answers("MV3", fail = true))
        bobsChat(link)
        val refused = runBlocking { link.sendAppUpdate(chat, "imsg:ROOT", card, jpeg) }
        assertFalse(refused.sent)
        assertFalse(refused.unconfirmed)
        assertNull(repo.message("imsg:MV3"))
        assertEquals("Apple refused the message: busy", refused.error)
        // Not a session of an iMessage: nothing goes, and it says why.
        val notOne = runBlocking { link.sendAppUpdate(chat, "tmp:x", card, null) }
        assertFalse(notOne.sent)
        assertFalse(notOne.error.isNullOrEmpty())
        assertEquals(1, requests(DirectWire.EXTENSION).size)
    }

    @Test
    fun aMoveAppleDidNotConfirmInTimeSaysSo() {
        val link = link { what, _ ->
            if (what == DirectWire.EXTENSION) DirectWire.failure(DirectEngine.Unconfirmed("not confirmed in 45 s")) else DirectWire.ok()
        }
        bobsChat(link)
        val r = runBlocking { link.sendAppUpdate(chat, "imsg:ROOT", card, null) }
        assertFalse(r.sent)
        assertTrue(r.unconfirmed)
    }

    @Test
    fun aFailedCardIsSentAgainAsACard() {
        var fail = true
        val link = link { what, request -> answers("C9", fail)(what, request) }
        bobsChat(link)
        val tmpId = link.sendAppCard(chat, card, jpeg)
        waitFor("the failed card") { repo.message(MessageRow.tmpMessageId(tmpId))?.localState == com.lightdecoder.archive.LocalState.FAILED }
        fail = false
        assertTrue(link.retry(tmpId))
        waitFor("the sent card") { repo.message("imsg:C9") != null }
        val again = DirectWire.SendRequest.fromJson(requests(DirectWire.SEND).last())
        assertEquals("", again.text)
        assertEquals(card.url, again.app!!.url)
        assertEquals(session, again.app!!.session)
        assertEquals("jpeg-image.jpeg", again.attachments.single().name)
        assertEquals("imsg:C9", IMessageExtra.parse(repo.message("imsg:C9")!!.extra).app!!.root)
    }

    private class Events(var quiet: Boolean = false) : DirectLink.AppEvents {
        val moves = Collections.synchronizedList(ArrayList<Pair<String, MessageDto>>())
        override fun appUpdateArrived(conversationId: String, message: MessageDto) {
            moves += conversationId to message
        }

        override fun silences(conversationId: String): Boolean = quiet
    }

    private fun game(caption: String) = ExtensionApp(
        "GamePigeon", 1124197642UL, gamePigeon,
        Balloon("data:state", session, BalloonLayout.TemplateLayout("", "", caption, "", "", "", NsDictionaryClass.NS_DICTIONARY), "8 Ball", true, null),
    )

    private fun fromBob(guid: String, m: PushChatMessage) = DirectArchive.map(
        MessageInst(guid, bob, ConversationData(listOf(me, bob), null, null, null), m, 1_700_000_100_000UL, null, false, false, null),
        setOf(me), Handles::display, DirectTexts(context),
    ).let { DirectWire.decode(JSONObject(DirectWire.encode(it).toString())) }

    @Test
    fun aMoveFromAppleReachesTheExtensionHostButANewCardDoesNot() {
        val events = Events()
        DirectLink.appEvents = events
        val link = link(answers("unused"))
        bobsChat(link)
        link.apply(
            fromBob(
                "NEW",
                PushChatMessage.Message(
                    NormalMessage(listOf(IndexedMessagePart(MessagePart.Object("8 Ball"), 0UL, null)), null, null, null, MessageType.IMessage, null, game("Let's play"), null, false, null, null),
                ),
            ),
        )
        assertTrue("a new card is opened by a tap, not handed over", events.moves.isEmpty())
        val move = PushChatMessage.React(
            ReactMessage("NEW", null, ReactMessageType.Extension(game("Bob moved"), listOf(IndexedMessagePart(MessagePart.Object("8 Ball"), 0UL, null)), false), "", null),
        )
        link.apply(fromBob("M1", move))
        val (conversation, message) = events.moves.single()
        assertEquals(chat, conversation)
        assertEquals("imsg:M1", message.id)
        assertEquals("imsg:NEW", IMessageExtra.parse(message.extra).app!!.root)
        // Handed over again by Apple's journal: once is enough.
        link.apply(fromBob("M1", move))
        assertEquals(1, events.moves.size)
    }

    @Test
    fun aChatAGameAsksToKeepQuietMakesNoSound() {
        IMessagePrefs.setEnabled(context, true)
        IMessagePrefs.setNotifyMessages(context, true)
        val events = Events(quiet = true)
        DirectLink.appEvents = events
        val link = link(answers("unused"))
        fun announced() = shadowOf(context.getSystemService(NotificationManager::class.java)).activeNotifications
            .any { it.tag?.startsWith("im:") == true }
        link.apply(fromBob("Q1", PushChatMessage.Message(NormalMessage(listOf(text("your turn")), null, null, null, MessageType.IMessage, null, null, null, false, null, null))))
        assertNotNull("stored all the same", repo.message("imsg:Q1"))
        assertFalse(announced())
        events.quiet = false
        link.apply(fromBob("Q2", PushChatMessage.Message(NormalMessage(listOf(text("and again")), null, null, null, MessageType.IMessage, null, null, null, false, null, null))))
        assertTrue(announced())
    }

    /** Polls, for work the link does on its own threads. */
    private fun waitFor(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    private companion object {
        const val DB = "direct-apps-test.db"
    }
}
