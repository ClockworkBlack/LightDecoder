package com.lightdecoder.wa

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.RelayException
import com.lightdecoder.relay.RelayState
import kotlinx.coroutines.runBlocking
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

/**
 * The WhatsApp runtime over a fake bridge: linking by code and by QR (the
 * user's confirmation is the fake's [FakeWaBridge.completePairing], never
 * the manager's), what the bridge pushes landing in the archive and the
 * flows, the outbox, and the ways the link ends.
 */
@RunWith(AndroidJUnit4::class)
class WaRuntimeTest {

    private lateinit var env: WaTestEnv

    @Before
    fun setUp() {
        env = WaTestEnv()
    }

    @After
    fun tearDown() {
        env.tearDown()
    }

    // --- linking ----------------------------------------------------------------------------

    @Test
    fun aPairingCodeIsShownAndTheLinkWaitsForThePhone() {
        val manager = env.manager()
        val code = runBlocking { manager.requestPairingCode("+1 555 0100") }
        val fake = requireNotNull(env.fake)

        assertEquals("ABCD2345", code)
        assertEquals(WaPairing.WaitingForCode("ABCD2345"), manager.pairing.value)
        assertEquals(RelayState.Phase.PAIRING, manager.state.value.phase)
        assertEquals(listOf("+1 555 0100"), fake.pairPhoneNumbers.toList())
        // Nothing answered for the user: the device is linked only once the phone says so.
        assertFalse(manager.isPaired())
        assertFalse(WaPrefs.liveEnabled(env.context))
        assertNull("no service without a link", shadowOf(env.app).peekNextStartedService())

        fake.completePairing()
        env.eventually(manager, what = "the link") { manager.pairing.value == WaPairing.Paired }
        assertTrue(manager.isPaired())
        assertTrue("linked means stay connected", WaPrefs.liveEnabled(env.context))
        assertEquals(
            "its service, and so its notification, starts with the link",
            ComponentName(env.context, WaService::class.java),
            shadowOf(env.app).nextStartedService?.component,
        )
        assertEquals(FakeWaBridge.OWN_ID, env.repo.health().account)

        val log = WaLog.read(env.context).joinToString("\n")
        assertTrue(log.contains("Linked with WhatsApp"))
        assertFalse("the code is never logged", log.contains("ABCD2345"))
        assertFalse("nor the number", log.contains("5550100"))
    }

    @Test
    fun qrCodesFollowEachOtherUntilTheyRunOut() {
        val manager = env.manager()
        runBlocking { manager.startQr() }
        val fake = requireNotNull(env.fake)
        assertNull(manager.qr.value)

        fake.emitQr("2@first")
        env.eventually(manager, what = "the first code") { manager.qr.value == "2@first" }
        assertEquals(WaPairing.WaitingForQr("2@first"), manager.pairing.value)
        fake.emitQr("2@second")
        env.eventually(manager, what = "the second code") { manager.qr.value == "2@second" }

        fake.failPairing(RelayException.PAIRING_TIMEOUT)
        env.eventually(manager, what = "the end of linking") { manager.pairing.value is WaPairing.Failed }
        assertEquals(WaPairing.Failed(RelayException.PAIRING_TIMEOUT), manager.pairing.value)
        assertNull(manager.qr.value)
        assertFalse(manager.isPaired())
        assertEquals(RelayState.Phase.UNPAIRED, manager.state.value.phase)
        assertFalse(WaLog.read(env.context).joinToString("\n").contains("2@"))
    }

    @Test
    fun cancellingAbandonsTheAttempt() {
        val manager = env.manager()
        runBlocking { manager.requestPairingCode("15550100") }
        val fake = requireNotNull(env.fake)

        manager.cancelPairing()
        assertEquals(WaPairing.Idle, manager.pairing.value)
        env.eventually(manager, what = "the bridge to be told") { fake.calls.contains("cancelPairing") }
        env.eventually(manager, what = "the state to settle") { manager.state.value.phase == RelayState.Phase.UNPAIRED }
        assertFalse(fake.linking)

        // A code that arrives for the abandoned attempt links nothing.
        assertFalse(manager.isPaired())
    }

    @Test
    fun aRefusedCodeRequestIsAFailureNotACrash() {
        val manager = env.manager()
        env.setUpFake = { it.errors["pairPhone"] = RelayException(WaErrors.RATE_LIMITED, "slow down") }
        try {
            runBlocking { manager.requestPairingCode("15550100") }
            fail("the bridge refused")
        } catch (e: RelayException) {
            assertEquals(WaErrors.RATE_LIMITED, e.code)
        }
        assertEquals(WaPairing.Failed(WaErrors.RATE_LIMITED), manager.pairing.value)
        assertEquals(RelayState.Phase.UNPAIRED, manager.state.value.phase)
    }

    @Test
    fun aLinkedDeviceDoesNotLinkAgain() {
        val (manager, _) = env.linkedManager()
        try {
            runBlocking { manager.requestPairingCode("15550100") }
            fail("already linked")
        } catch (e: RelayException) {
            assertEquals(WaErrors.ALREADY_PAIRED, e.code)
        }
        assertTrue(manager.isPaired())
    }

    // --- what the bridge pushes -------------------------------------------------------------

    @Test
    fun messagesReceiptsTypingAndPresenceReachTheArchiveAndTheFlows() {
        val (manager, fake) = env.connectedManager()
        val chat = env.conversation("15550111")
        fake.emitConversation(chat)
        fake.emitMessage(
            MessageDto(id = "IN1", conversationId = chat.id, senderId = chat.id, timestampMs = 2_000, text = "hello", transport = "WHATSAPP")
        )
        fake.emitMessage(
            MessageDto(
                id = "OUT1", conversationId = chat.id, fromMe = true, direction = "out", status = "OUTGOING_COMPLETE",
                timestampMs = 3_000, text = "hi", transport = "WHATSAPP",
            )
        )
        env.eventually(manager, what = "the messages") { env.repo.message("OUT1") != null }
        assertEquals("hello", env.repo.message("IN1")?.text)
        assertNotNull(env.repo.conversation(chat.id))

        // Receipts move an outgoing message forward, never back.
        fake.emitReceipt(chat.id, listOf("OUT1"), WaReceipt.READ)
        env.eventually(manager, what = "the read receipt") { env.repo.message("OUT1")?.status == "OUTGOING_DISPLAYED" }
        fake.emitReceipt(chat.id, listOf("OUT1"), WaReceipt.DELIVERED)
        manager.awaitIdleForTesting()
        assertEquals("OUTGOING_DISPLAYED", env.repo.message("OUT1")?.status)

        fake.emitTyping(chat.id, chat.id)
        env.eventually(manager, what = "typing") { manager.typing.value.containsKey(chat.id) }
        // Nobody stays "typing" for ever when the stop never arrives.
        env.eventually(manager, what = "typing to run out") { manager.typing.value.isEmpty() }

        // Presence is only followed while one of WhatsApp's screens is in front.
        manager.setAppVisible(true)
        fake.emitPresence(chat.id, online = true)
        env.eventually(manager, what = "presence") { manager.presence.value[chat.id]?.online == true }
        fake.emitPresence(chat.id, online = false, lastSeenMs = 42)
        env.eventually(manager, what = "last seen") { manager.presence.value[chat.id]?.lastSeenMs == 42L }

        // None of it names anyone in the log.
        val log = WaLog.read(env.context).joinToString("\n")
        assertFalse(log.contains("15550111"))
        assertFalse(log.contains("hello"))
    }

    // --- sending ----------------------------------------------------------------------------

    @Test
    fun aSendShowsAtOnceAndIsReplacedByWhatWhatsAppSent() {
        val (manager, fake) = env.connectedManager()
        val chat = env.conversation("15550111")
        fake.emitConversation(chat)
        env.eventually(manager, what = "the chat") { env.repo.conversation(chat.id) != null }

        val tmpId = manager.sendText(chat.id, "on my way")
        env.eventually(manager, what = "the send") { fake.sent.isNotEmpty() }
        assertEquals(FakeWaBridge.Sent(chat.id, tmpId, "on my way"), fake.sent.single())
        env.eventually(manager, what = "the sent message") { env.repo.message("wa-sent-1") != null }
        env.eventually(manager, what = "the local row to go") { env.repo.message(MessageRow.tmpMessageId(tmpId)) == null }
        assertEquals("exactly one row for the message", 1, env.repo.messages(chat.id).size)
    }

    @Test
    fun nothingGoesOutWhileDisconnectedAndItIsSentOnceConnected() {
        // The archive is known to be this account's: queued sends may go out by themselves.
        WaPrefs.setArchiveOwner(env.context, FakeWaBridge.OWN_ID)
        val (manager, fake) = env.linkedManager()
        val chat = env.conversation("15550111")
        env.repo.upsertConversation(chat)

        val tmpId = manager.sendText(chat.id, "later")
        manager.awaitIdleForTesting()
        assertNotNull("shown as pending", env.repo.message(MessageRow.tmpMessageId(tmpId)))
        assertTrue(fake.sent.isEmpty())

        WaPrefs.setLiveEnabled(env.context, true)
        env.startService()
        env.eventually(manager, what = "the queued send") { fake.sent.singleOrNull()?.tmpId == tmpId }
    }

    // --- the ways it ends ---------------------------------------------------------------------

    @Test
    fun disconnectKeepsTheLinkAndTurnsStayConnectedOff() {
        val (manager, fake) = env.connectedManager()
        manager.stopLive()
        env.eventually(manager, what = "the disconnect") { fake.calls.contains("disconnect") }
        env.eventually(manager, what = "the state") { manager.state.value.phase == RelayState.Phase.DISCONNECTED }
        assertTrue(manager.isPaired())
        assertFalse(WaPrefs.liveEnabled(env.context))
    }

    @Test
    fun unlinkingTellsWhatsAppAndWipesWhatWasAsked() {
        val (manager, fake) = env.connectedManager()
        val chat = env.conversation("15550111")
        fake.emitConversation(chat)
        fake.emitMessage(MessageDto(id = "IN1", conversationId = chat.id, senderId = chat.id, timestampMs = 2_000, text = "hello"))
        env.eventually(manager, what = "the message") { env.repo.message("IN1") != null }

        runBlocking { manager.unpair(deleteArchive = true) }

        assertTrue(fake.calls.contains("logout"))
        assertFalse(manager.isPaired())
        assertEquals(RelayState(RelayState.Phase.UNPAIRED), manager.state.value)
        assertFalse(WaPrefs.liveEnabled(env.context))
        assertNull(env.repo.message("IN1"))
        assertFalse(manager.unpairing.value)
    }

    @Test
    fun anUnlinkWhatsAppDidNotHearOfSaysSoAndKeepsTheArchiveWhenAsked() {
        val (manager, fake) = env.connectedManager()
        val chat = env.conversation("15550111")
        fake.emitConversation(chat)
        fake.emitMessage(MessageDto(id = "IN1", conversationId = chat.id, senderId = chat.id, timestampMs = 2_000, text = "hello"))
        env.eventually(manager, what = "the message") { env.repo.message("IN1") != null }
        fake.logoutRemoteFails = true

        runBlocking { manager.unpair(deleteArchive = false) }

        assertFalse(manager.isPaired())
        assertEquals(RelayState.Phase.UNPAIRED, manager.state.value.phase)
        assertEquals("the user removes the device on the phone", RelayState.UNPAIR_REMOTE_FAILED, manager.state.value.code)
        assertNotNull("kept, as asked", env.repo.message("IN1"))
    }

    @Test
    fun beingRemovedOnThePhoneEndsTheLinkHere() {
        val (manager, fake) = env.connectedManager()
        fake.logOutFromPhone()
        env.eventually(manager, what = "logged out") { manager.state.value.phase == RelayState.Phase.LOGGED_OUT }
        assertFalse(manager.isPaired())
        assertFalse("nothing reconnects by itself", WaPrefs.liveEnabled(env.context))
    }

    @Test
    fun aBanIsNotRetried() {
        val (manager, fake) = env.connectedManager()
        fake.failConnection(WaErrors.TEMP_BANNED)
        env.eventually(manager, what = "the error") { manager.state.value.phase == RelayState.Phase.ERROR }
        assertEquals(WaErrors.TEMP_BANNED, manager.state.value.code)
        assertFalse("only the user starts it again", WaPrefs.liveEnabled(env.context))
        assertTrue(manager.isPaired())
    }
}
