package com.lightdecoder.imessage.madrid

import android.app.Application
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bluebubbles.messaging.IKeyboardHandle
import com.bluebubbles.messaging.IMadridExtension
import com.bluebubbles.messaging.IMessageViewHandle
import com.bluebubbles.messaging.ITaskCompleteCallback
import com.bluebubbles.messaging.IViewUpdateCallback
import com.bluebubbles.messaging.MadridMessage
import com.lightdecoder.R
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.archive.MediaCache
import com.lightdecoder.chat.ChatService
import com.lightdecoder.imessage.IMessageManager
import com.lightdecoder.imessage.direct.DirectArchive
import com.lightdecoder.imessage.direct.DirectLibrary
import com.lightdecoder.imessage.direct.DirectLink
import com.lightdecoder.imessage.direct.DirectPrefs
import com.lightdecoder.imessage.direct.DirectTexts
import com.lightdecoder.imessage.direct.DirectWire
import com.lightdecoder.imessage.direct.Handles
import com.lightdecoder.imessage.direct.IMessageApps
import com.lightdecoder.imessage.direct.IMessageExtra
import com.lightdecoder.archive.MessageRow
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
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

/**
 * The host with an extension app played by a fake: a tap opens the session's newest message, a
 * move is told complete only once Apple took it, moves come no faster than one every 2 s, and
 * moves from Apple reach the app only for its open sessions.
 */
@RunWith(AndroidJUnit4::class)
class MadridHostTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: ArchiveDb
    private lateinit var repo: ArchiveRepository
    private val me = "tel:+15550000001"
    private val bob = "tel:+15559876543"
    private val chat = "imsg:$bob"
    private val session = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private val other = "1f8fad5b-d9cb-469f-a165-70867728950e"
    private val ext = MadridApps.OPENPIGEON

    /** The extension app: what it was given. */
    private class Fake : IMadridExtension.Stub() {
        @Volatile var tapped: MadridMessage? = null

        @Volatile var handle: IMessageViewHandle? = null

        @Volatile var users = 0
        val updated = Collections.synchronizedList(ArrayList<MadridMessage>())

        override fun keyboardOpened(callback: IViewUpdateCallback?, handle: IKeyboardHandle?, userCount: Int): RemoteViews? = null
        override fun keyboardClosed() {}
        override fun didTapTemplate(message: MadridMessage?, handle: IMessageViewHandle?, userCount: Int) {
            this.handle = handle
            users = userCount
            tapped = message
        }

        override fun getLiveView(callback: IViewUpdateCallback?, message: MadridMessage?, handle: IMessageViewHandle?, userCount: Int): RemoteViews? = null
        override fun messageUpdated(message: MadridMessage?) {
            if (message != null) updated += message
        }
    }

    private val fake = Fake()

    @Volatile private var refuse = false
    private val guids = AtomicInteger()
    private val extensions = Collections.synchronizedList(ArrayList<JSONObject>())
    private lateinit var link: DirectLink

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
        link = DirectLink.installForTesting(context) { what, request, _ ->
            when {
                what != DirectWire.EXTENSION -> DirectWire.ok()
                refuse -> DirectWire.error("Apple refused the message: busy").also { extensions += request }
                else -> DirectWire.sent("SENT${guids.incrementAndGet()}", 1_700_000_200_000L, emptyList()).also { extensions += request }
            }
        }
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                packageName = ext.pkg
                lastUpdateTime = 1
                applicationInfo = ApplicationInfo().apply { packageName = ext.pkg }
            },
        )
        MadridApps.verifier = { _, _ -> true }
        shadowOf(context).setComponentNameAndServiceForBindService(ComponentName(ext.pkg, ext.service), fake)
        MadridHost.install(context)
        // Bob's chat, his new game (C1) and his move in it (M1).
        link.apply(fromBob("B0", PushChatMessage.Message(NormalMessage(listOf(obj("hi")), null, null, null, MessageType.IMessage, null, null, null, false, null, null)), 0))
        link.apply(fromBob("C1", PushChatMessage.Message(NormalMessage(listOf(obj("8 Ball")), null, null, null, MessageType.IMessage, null, game(session, "Let's play"), null, false, null, null)), 1))
        link.apply(fromBob("M1", move("C1", session, "Bob moved"), 2))
    }

    @After
    fun tearDown() {
        MadridHost.resetForTesting()
        MadridApps.resetForTesting()
        IMessageApps.host = null
        DirectLink.appEvents = null
        shadowOf(context.packageManager).deletePackage(ext.pkg)
        DirectPrefs.setRegistered(context, false)
        DirectLink.resetForTesting()
        IMessageManager.resetForTesting()
        ArchiveRepository.resetForTesting()
        db.close()
    }

    private fun obj(t: String) = IndexedMessagePart(MessagePart.Object(t), 0UL, null)

    private fun game(s: String, caption: String, url: String = "data:state") = ExtensionApp(
        "GamePigeon", 1124197642UL, ext.bundleId,
        Balloon(url, s, BalloonLayout.TemplateLayout("", "", caption, "", "", "", NsDictionaryClass.NS_DICTIONARY), "8 Ball", true, null),
    )

    private fun move(root: String, s: String, caption: String) =
        PushChatMessage.React(ReactMessage(root, null, ReactMessageType.Extension(game(s, caption, "data:$caption"), listOf(obj("8 Ball")), false), "", null))

    private fun fromBob(guid: String, m: PushChatMessage, n: Int) = DirectArchive.map(
        MessageInst(guid, bob, ConversationData(listOf(me, bob), null, null, null), m, (1_700_000_100_000L + n * 1_000L).toULong(), null, false, false, null),
        setOf(me), Handles::display, DirectTexts(context),
    ).let { DirectWire.decode(JSONObject(DirectWire.encode(it).toString())) }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun tap(id: String): Boolean {
        val row = repo.message(id)!!
        return IMessageApps.host!!.engage(context, row, IMessageExtra.parse(row.extra).app!!)
    }

    private class Done : ITaskCompleteCallback.Stub() {
        val times = AtomicInteger()
        override fun complete() {
            times.incrementAndGet()
        }
    }

    private fun madrid(url: String) = MadridMessage().apply {
        this.url = url
        session = "ignored, the handle's is used"
        caption = "My move"
        ldText = "8 Ball"
        isLive = true
    }

    @Test
    fun aTapOpensTheSessionsNewestMessageAndAMoveIsCompleteOnlyOnceAppleTookIt() {
        assertTrue(tap("imsg:C1"))
        waitFor("the tap") { fake.tapped != null }
        assertEquals("the newest of the session, not the card tapped", "M1", fake.tapped!!.messageGuid)
        assertEquals(session, fake.tapped!!.session)
        assertNull(fake.tapped!!.imageBase64)
        assertEquals("Bob and the user", 2, fake.users)
        val handle = fake.handle!!

        // Refused by Apple: not complete; the user is told why.
        refuse = true
        val refused = Done()
        handle.updateMessage(madrid("data:1"), refused)
        waitFor("the refused move") { ShadowToast.getTextOfLatestToast()?.contains("busy") == true }
        assertEquals(0, refused.times.get())
        assertEquals(1, extensions.size)

        // Taken, after a while: complete, to the session's first card, stored with it.
        refuse = false
        Thread.sleep(MadridSessions.MOVE_INTERVAL_MS + 100)
        val taken = Done()
        handle.updateMessage(madrid("data:2"), taken)
        waitFor("the move") { taken.times.get() == 1 }
        val request = DirectWire.ExtensionRequest.fromJson(extensions.last())
        assertEquals("C1", request.rootGuid)
        assertEquals(session, request.app.session)
        assertEquals("data:2", request.app.url)
        val stored = repo.message("imsg:SENT1")!!
        assertEquals("imsg:C1", IMessageExtra.parse(stored.extra).app!!.root)

        // The same move again (the app's retry): not sent twice, complete all the same.
        val again = Done()
        handle.updateMessage(madrid("data:2"), again)
        waitFor("the repeat") { again.times.get() == 1 }
        assertEquals(2, extensions.size)

        // Another move at once: too soon, not sent.
        val tooSoon = Done()
        handle.updateMessage(madrid("data:3"), tooSoon)
        waitFor("the refusal") {
            ShadowToast.getTextOfLatestToast() == context.getString(R.string.imessage_app_move_failed, context.getString(R.string.imessage_app_move_too_soon))
        }
        assertEquals(0, tooSoon.times.get())
        assertEquals(2, extensions.size)

        // An address too long: refused, never cut.
        Thread.sleep(MadridSessions.MOVE_INTERVAL_MS + 100)
        handle.updateMessage(madrid("data:" + "a".repeat(MadridMessages.MAX_URL)), Done())
        waitFor("the refusal") {
            ShadowToast.getTextOfLatestToast() == context.getString(R.string.imessage_app_move_failed, context.getString(R.string.imessage_app_move_invalid))
        }
        assertEquals(2, extensions.size)
    }

    @Test
    fun movesFromAppleReachTheAppOnlyForItsOpenSessionsInOrder() {
        // Not open yet: not handed over.
        link.apply(fromBob("M2", move("C1", session, "Bob again"), 3))
        assertTrue(tap("imsg:M1"))
        waitFor("the tap") { fake.tapped != null }
        assertEquals("M2", fake.tapped!!.messageGuid)
        link.apply(fromBob("M3", move("C1", session, "third"), 4))
        link.apply(fromBob("M4", move("C1", session, "fourth"), 5))
        // Another game in the chat, not open.
        link.apply(fromBob("C2", PushChatMessage.Message(NormalMessage(listOf(obj("Darts")), null, null, null, MessageType.IMessage, null, game(other, "Darts?"), null, false, null, null)), 6))
        link.apply(fromBob("N1", move("C2", other, "not open"), 7))
        waitFor("the moves") { fake.updated.size >= 2 }
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("M3", "M4"), fake.updated.map { it.messageGuid })
        assertEquals(listOf("data:third", "data:fourth"), fake.updated.map { it.url })
        assertNull(fake.updated.single { it.messageGuid == "M3" }.imageBase64)
    }

    @Test
    fun aGameStillOnItsWayCannotBeOpenedAndSaysSo() {
        val card = IMessageExtra.App(
            name = "GamePigeon", appId = 1124197642L, bundleId = ext.bundleId, url = "data:new", session = other, ldText = "Darts",
        )
        repo.insertLocalOutgoing("t1", chat, "Darts", null, null, null, null, 1_700_000_300_000L, IMessageExtra(app = card).toJson())
        val row = repo.message(MessageRow.tmpMessageId("t1"))
        assertNotNull(row)
        assertTrue(IMessageApps.host!!.engage(context, row!!, card))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(context.getString(R.string.imessage_app_still_sending), ShadowToast.getTextOfLatestToast())
        Thread.sleep(200)
        assertNull("nothing opened", fake.tapped)
    }

    private companion object {
        const val DB = "madrid-host-test.db"
    }
}
