package com.lightdecoder.wa

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.ArchiveDb
import com.lightdecoder.archive.ArchiveRepository
import com.lightdecoder.chat.Capability
import com.lightdecoder.chat.ChatService
import com.lightdecoder.chat.Presence
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.RelayException
import com.lightdecoder.relay.RelayIntents
import com.lightdecoder.relay.RelayLog
import com.lightdecoder.relay.RelayPrefs
import com.lightdecoder.relay.RelayService
import com.lightdecoder.relay.RelayState
import com.lightdecoder.ui.wa.WaSetupActivity
import com.lightdecoder.ui.wa.WaStatusActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What the WhatsApp runtime and screens are built on: the bridge's Kotlin
 * side (DTOs, error codes, the fake), its preferences, log and id stand-ins,
 * the manager's public API while it is only a placeholder, and the manifest
 * entries.
 */
@RunWith(AndroidJUnit4::class)
class WaFoundationTest {

    private lateinit var context: Context
    private lateinit var db: ArchiveDb
    private lateinit var repo: ArchiveRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(WaPrefs.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("relay", Context.MODE_PRIVATE).edit().clear().commit()
        WaLog.clear(context)
        RelayLog.clear(context)
        context.deleteDatabase(DB_NAME)
        db = ArchiveDb(context, DB_NAME)
        repo = ArchiveRepository.forTesting(db, ChatService.WHATSAPP)
    }

    @After
    fun tearDown() {
        WaManager.resetForTesting()
        WaBridges.factory = null
        db.close()
    }

    // --- the bridge's Kotlin side ---------------------------------------------------------

    @Test
    fun parsesTheBridgesOwnEvents() {
        val status = WaStatus.fromJson(JSONObject("""{"paired":true,"connected":true,"ownId":"15550100","pushName":"Sam"}"""))
        assertEquals(WaStatus(paired = true, connected = true, ownId = "15550100", pushName = "Sam"), status)
        assertEquals("a missing key is the default", WaStatus(), WaStatus.fromJson(JSONObject("{}")))

        val receipt = WaReceipt.fromJson(
            JSONObject("""{"conversationId":"1@s.whatsapp.net","messageIds":["A","B",null],"status":"read","timestampMs":42}""")
        )
        assertEquals(WaReceipt("1@s.whatsapp.net", listOf("A", "B"), WaReceipt.READ, 42), receipt)
        assertEquals(emptyList<String>(), WaReceipt.fromJson(JSONObject("""{"conversationId":"c","messageIds":null}""")).messageIds)

        val presence = WaPresenceEvent.fromJson(JSONObject("""{"participantId":"1@s.whatsapp.net","online":false,"lastSeenMs":7}"""))
        assertEquals(Presence(online = false, lastSeenMs = 7), presence.toPresence())
        assertEquals("1@s.whatsapp.net", presence.participantId)

        assertEquals(WaHistorySync(40, false), WaHistorySync.fromJson(JSONObject("""{"progress":40,"done":false}""")))
        assertEquals("out of range is clamped", 100, WaHistorySync.fromJson(JSONObject("""{"progress":250,"done":true}""")).progress)
        assertEquals("2@abc", WaQr.fromJson(JSONObject("""{"code":"2@abc"}""")).code)
        assertEquals("15550100", WaPairSuccess.fromJson(JSONObject("""{"ownId":"15550100"}""")).ownId)
        assertEquals(WaPairError("pairing_timeout", "the link was not confirmed in time"),
            WaPairError.fromJson(JSONObject("""{"code":"pairing_timeout","detail":"the link was not confirmed in time"}""")))
        assertEquals("a JSON null is empty, not \"null\"", "", WaPairError.fromJson(JSONObject("""{"code":null}""")).code)
    }

    @Test
    fun whatsAppErrorCodesAreKnownAndUnknownTextIsDropped() {
        val limited = WaErrors.fromThrowable(Exception("rate_limited: WhatsApp answered 429"))
        assertEquals(WaErrors.RATE_LIMITED, limited.code)
        assertEquals("WhatsApp answered 429", limited.message)
        assertEquals("a code without detail", WaErrors.NOT_ON_WHATSAPP, WaErrors.fromThrowable(Exception("not_on_whatsapp")).code)
        assertEquals("shared codes too", RelayException.NOT_CONNECTED, WaErrors.fromThrowable(Exception("not_connected: x")).code)

        // Text that is not the bridge's "<code>: <detail>" was never checked for content.
        val unknown = WaErrors.fromThrowable(IllegalStateException("15550100@s.whatsapp.net said hello"))
        assertEquals(RelayException.INTERNAL, unknown.code)
        assertFalse(unknown.message.orEmpty().contains("15550100"))
        assertNull(unknown.cause)

        // The Google Messages bridge does not know WhatsApp's codes, and stays as it was.
        assertEquals(RelayException.INTERNAL, RelayException.fromThrowable(Exception("rate_limited: x")).code)
        assertEquals(RelayException.NOT_CONNECTED, RelayException.fromThrowable(Exception("not_connected: x")).code)
        assertTrue(WaErrors.CODES.containsAll(listOf(RelayException.NOT_PAIRED, RelayException.UNPAIR_REMOTE_FAILED, WaErrors.TEMP_BANNED)))
    }

    @Test
    fun pairingStatesNeverPrintTheirCode() {
        val code = WaPairing.WaitingForCode("ABCD2345")
        val qr = WaPairing.WaitingForQr("2@secret,keys")
        assertFalse(code.toString().contains("ABCD2345"))
        assertFalse(qr.toString().contains("secret"))
        assertEquals(WaPairing.WaitingForCode("ABCD2345"), code)
        assertNotEquals(WaPairing.WaitingForCode("WXYZ6789"), code)
        assertNotEquals("a QR code is not a pairing code", WaPairing.WaitingForQr("ABCD2345"), code)
        assertEquals(WaPairing.WaitingForQr("2@secret,keys"), qr)
        assertEquals("pairing_timeout", WaPairing.Failed("pairing_timeout").code)
        assertSame(WaPairing.Idle, WaPairing.Idle)
    }

    // --- preferences, log, ids --------------------------------------------------------------

    @Test
    fun whatsAppHasItsOwnChoices() {
        assertFalse("off until the user turns it on", WaPrefs.liveEnabled(context))
        assertTrue(WaPrefs.autoStart(context))
        assertTrue("message notifications are on unless turned off", WaPrefs.notifyMessages(context))

        WaPrefs.setLiveEnabled(context, true)
        WaPrefs.setAutoStart(context, false)
        WaPrefs.setNotifyMessages(context, false)
        assertTrue(WaPrefs.liveEnabled(context))
        assertFalse(WaPrefs.autoStart(context))
        assertFalse(WaPrefs.notifyMessages(context))

        // Nothing of it reaches the Google Messages link's choices, or the other way round.
        assertFalse(RelayPrefs.liveEnabled(context))
        assertTrue(RelayPrefs.autoStart(context))
        assertTrue("untouched by WhatsApp's switch", RelayPrefs.notifyMessages(context))
        RelayPrefs.setLiveEnabled(context, true)
        WaPrefs.setLiveEnabled(context, false)
        assertTrue(RelayPrefs.liveEnabled(context))
    }

    @Test
    fun theWhatsAppLogIsItsOwnAndEveryLineIsPrefixed() {
        RelayLog.add(context, "Connected")
        WaLog.add(context, "Connected")
        WaLog.add(context, "Send failed: not_connected")

        val wa = WaLog.read(context)
        assertEquals(2, wa.size)
        assertTrue(wa.all { it.contains(WaLog.PREFIX) })
        assertTrue("newest first", wa[0].endsWith("WhatsApp: Send failed: not_connected"))
        assertEquals("the Messages log has only its own line", 1, RelayLog.read(context).size)
        assertFalse(RelayLog.read(context).single().contains(WaLog.PREFIX))

        WaLog.clear(context)
        assertTrue(WaLog.read(context).isEmpty())
        assertEquals(1, RelayLog.read(context).size)
    }

    @Test
    fun idStandInsAreStableAndSayNothingAboutTheId() {
        val jid = "15550100@s.whatsapp.net"
        val first = WaIds.opaque(context, jid)
        assertEquals("the same every time, so a notification can be found again", first, WaIds.opaque(context, jid))
        assertEquals(32, first.length)
        assertTrue(first.all { it in "0123456789abcdef" })
        assertFalse(first.contains("15550100"))
        assertNotEquals(first, WaIds.opaque(context, "15550101@s.whatsapp.net"))

        // Keyed with a secret of this installation: another installation gives another stand-in,
        // so it cannot be turned back into a number by hashing every number.
        context.getSharedPreferences(WaPrefs.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        assertNotEquals(first, WaIds.opaque(context, jid))
    }

    // --- the manager's API without a link (linking itself: WaRuntimeTest) -----------------------------------------

    @Test
    fun theManagerIsTheWhatsAppBackendAndStartsUnlinked() {
        val manager = WaManager.installForTesting(context, repo)
        assertSame(manager, WaManager.get(context))
        assertEquals(ChatService.WHATSAPP, manager.service)
        assertSame(repo, manager.repo)
        assertEquals(setOf(Capability.PRESENCE, Capability.DELETE_FOR_EVERYONE, Capability.EDIT), manager.capabilities)

        assertFalse(manager.isPaired())
        assertEquals(RelayState.Phase.UNPAIRED, manager.state.value.phase)
        assertEquals(WaPairing.Idle, manager.pairing.value)
        assertNull(manager.qr.value)
        assertFalse(manager.unpairing.value)
        assertTrue(manager.typing.value.isEmpty())
        assertTrue(manager.presence.value.isEmpty())
        assertFalse(manager.backfill.value.running)

        assertEquals(File(context.filesDir, "whatsapp"), WaManager.storeDir(context))
        assertEquals("LightDecoder", WaManager.DEVICE_NAME)
    }

    @Test
    fun anArchiveOfAnotherServiceIsRefused() {
        val messagesDb = ArchiveDb(context, "wa-foundation-messages-test.db")
        try {
            WaManager.installForTesting(context, ArchiveRepository.forTesting(messagesDb))
            fail("a Google Messages archive must not become WhatsApp's")
        } catch (e: IllegalArgumentException) {
            // expected
        } finally {
            messagesDb.close()
        }
    }

    @Test
    fun whileUnlinkedNothingIsSentAndLocalThingsDoNotThrow() {
        val manager = WaManager.installForTesting(context, repo)
        val jid = FakeWaBridge.jid("15550111")

        fun assertNotLinked(what: String, block: suspend () -> Unit) {
            try {
                runBlocking { block() }
                fail("$what should need the link")
            } catch (e: RelayException) {
                assertEquals(what, RelayException.NOT_PAIRED, e.code)
                assertFalse("no id in the exception's text", e.message.orEmpty().contains("15550111"))
            }
        }
        assertNotLinked("send") { manager.sendText(jid, "hello") }
        assertNotLinked("react") { manager.react("M1", "👍", remove = false) }
        assertNotLinked("refresh") { manager.refreshConversations() }
        assertNotLinked("load older") { manager.loadOlder(jid) }
        assertNotLinked("delete for everyone") { manager.deleteMessage("M1", forEveryone = true) }
        assertNotLinked("start a chat") { manager.startConversation(listOf("+15550111"), null) }
        assertNotLinked("contacts from the phone") { manager.contacts(refresh = true) }
        assertTrue(repo.messages(jid).isEmpty())

        // What the screens call without asking first.
        manager.cancelPairing()
        manager.startLive()
        manager.stopLive()
        manager.typing(jid)
        manager.watchPresence(jid)
        manager.setAppVisible(true)
        manager.retry("t1")
        manager.discardFailed("t1")
        assertFalse("startLive without a link leaves stay connected off", WaPrefs.liveEnabled(context))
        runBlocking {
            manager.markRead(jid)
            manager.unpair(deleteArchive = false)
            assertNull(manager.mediaFile("M1", "0"))
            assertFalse(manager.restoreDeletedLocally(jid))
            assertEquals(0, manager.purgeDeleted(null))
            assertTrue(manager.contacts(refresh = false).contacts.isEmpty())
        }

        // The archive of an unlinked WhatsApp can be deleted, as the Messages one can.
        repo.upsertMessage(MessageDto(id = "M1", conversationId = jid, text = "kept from an earlier link", timestampMs = 1))
        assertTrue(runBlocking { manager.deleteLocalArchive() })
        assertNull(repo.message("M1"))
    }

    // --- manifest and intents -----------------------------------------------------------------

    @Test
    fun theWhatsAppComponentsAreDeclaredAndPrivate() {
        val pm = context.packageManager
        for (screen in listOf(WaSetupActivity::class.java, WaStatusActivity::class.java)) {
            val info = pm.getActivityInfo(ComponentName(context, screen), 0)
            assertFalse("${screen.simpleName} is not exported", info.exported)
        }
        val service = pm.getServiceInfo(ComponentName(context, WaService::class.java), 0)
        val messagesService = pm.getServiceInfo(ComponentName(context, RelayService::class.java), 0)
        assertFalse(service.exported)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            service.foregroundServiceType,
        )
        assertEquals("as the Google Messages link's service", messagesService.foregroundServiceType, service.foregroundServiceType)

        assertEquals(ComponentName(context, WaSetupActivity::class.java), RelayIntents.waSetup(context).component)
        assertEquals(ComponentName(context, WaStatusActivity::class.java), RelayIntents.waStatus(context).component)
        for (intent in listOf(RelayIntents.waSetup(context), RelayIntents.waStatus(context))) {
            assertTrue(pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty())
        }
    }

    // --- the fake bridge the runtime and screen tests use ---------------------------------------

    @Test
    fun theFakeBridgeLinksConnectsAndRemembersTheLink() {
        val events = ArrayList<Pair<String, JSONObject>>()
        val dir = File(context.filesDir, "wa-fake-test").apply { deleteRecursively() }
        val fake = FakeWaBridge(dir) { kind, payload -> events += kind to JSONObject(payload) }
        assertFalse(fake.isPaired)

        // Linking: the connection first, then a code, then the phone confirms.
        try {
            fake.pairPhone("+15550100")
            fail("a pairing code needs connect() first")
        } catch (e: RelayException) {
            assertEquals(RelayException.NOT_CONNECTED, e.code)
        }
        fake.connect()
        assertEquals("ABCD2345", fake.pairPhone("+15550100"))
        fake.completePairing()
        assertTrue(fake.isPaired)
        assertEquals(
            listOf("state:connecting", "pair_success", "state:connecting", "state:connected"),
            events.map { (kind, o) -> if (kind == WaBridge.KIND_STATE) "state:" + o.getString("state") else kind },
        )
        assertEquals(WaStatus(paired = true, connected = true, ownId = FakeWaBridge.OWN_ID), fake.status())

        // A send comes back as the sent message, with the tmpId.
        val jid = FakeWaBridge.jid("+1 555 0111")
        assertEquals("15550111@s.whatsapp.net", jid)
        val sent = fake.sendText(jid, "t1", "hello", null)
        assertEquals("t1", sent.tmpId)
        assertTrue(sent.fromMe)
        assertEquals(FakeWaBridge.Sent(jid, "t1", "hello"), fake.sent.single())

        // The app restarts: a new bridge over the same store is still linked.
        fake.close()
        val again = FakeWaBridge(dir) { _, _ -> }
        assertTrue(again.isPaired)
        try {
            again.sendText(jid, "t2", "too early", null)
            fail("not connected yet")
        } catch (e: RelayException) {
            assertEquals(RelayException.NOT_CONNECTED, e.code)
        }

        // Logging out wipes the store, also when WhatsApp could not be told.
        again.logoutRemoteFails = true
        try {
            again.logout()
            fail("unpair_remote_failed is thrown after the wipe")
        } catch (e: RelayException) {
            assertEquals(RelayException.UNPAIR_REMOTE_FAILED, e.code)
        }
        assertFalse(again.isPaired)
        assertFalse(FakeWaBridge(dir) { _, _ -> }.isPaired)
        dir.deleteRecursively()
    }

    private companion object {
        const val DB_NAME = "wa-foundation-test.db"
    }
}
