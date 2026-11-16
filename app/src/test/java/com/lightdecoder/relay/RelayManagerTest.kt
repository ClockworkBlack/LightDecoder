package com.lightdecoder.relay

import android.app.Application
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.TestMedia
import com.lightdecoder.archive.LocalState
import com.lightdecoder.archive.MessageRow
import com.lightdecoder.heif.HeifCodec
import com.lightdecoder.inbox.InboxItem
import com.lightdecoder.inbox.InboxStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File

/** The relay runtime against a fake bridge: pairing, state, messages, stickers, losing the pairing, unpairing. */
@RunWith(AndroidJUnit4::class)
class RelayManagerTest {

    private lateinit var env: RelayTestEnv

    @Before
    fun setUp() {
        env = RelayTestEnv()
    }

    @After
    fun tearDown() {
        env.tearDown()
    }

    // --- pairing --------------------------------------------------------------------

    @Test
    fun pairingStoresTheSessionAndGoesLive() {
        val manager = env.manager()
        assertFalse(manager.isPaired())
        assertEquals(RelayState.Phase.UNPAIRED, manager.state.value.phase)

        val challenge = runBlocking { manager.startPairing(COOKIES) }
        assertEquals("🦊", challenge.emoji)
        assertEquals(RelayState.Phase.PAIRING, manager.state.value.phase)

        val phoneId = runBlocking { manager.finishPairing() }
        assertEquals("phone-1", phoneId)
        manager.awaitIdleForTesting()

        assertTrue(manager.isPaired())
        assertEquals(RelayState.Phase.DISCONNECTED, manager.state.value.phase)
        assertArrayEquals(FakeRelayBridge.SESSION, SessionVault.load(env.context))
        assertTrue("stay connected is on", RelayPrefs.liveEnabled(env.context))
        val started = shadowOf(env.context as Application).nextStartedService
        assertEquals(RelayService::class.java.name, started?.component?.className)
        assertEquals("phone-1", env.repo.health().phoneId)
        assertEquals(listOf("startPairing", "finishPairing"), env.fake!!.calls.filter { it.contains("Pairing") })
        assertLogIsContentFree()
    }

    @Test
    fun wrongEmojiOnThePhoneLeavesTheAppUnpaired() {
        val manager = env.manager()
        runBlocking { manager.startPairing(COOKIES) }
        env.fake!!.finishPairing = { throw RelayException(RelayException.INCORRECT_EMOJI, "a different emoji was chosen on the phone") }

        try {
            runBlocking { manager.finishPairing() }
            fail("expected incorrect_emoji")
        } catch (e: RelayException) {
            assertEquals(RelayException.INCORRECT_EMOJI, e.code)
        }
        manager.awaitIdleForTesting()

        assertFalse(manager.isPaired())
        assertEquals(RelayState.Phase.UNPAIRED, manager.state.value.phase)
        assertNull(SessionVault.load(env.context))
        assertFalse(RelayPrefs.liveEnabled(env.context))
        assertNull(shadowOf(env.context as Application).nextStartedService)
        assertTrue(RelayLog.read(env.context).any { it.endsWith("Pairing did not finish: incorrect_emoji") })

        // The user can try again with a new emoji.
        env.fake!!.finishPairing = { "phone-1" }
        runBlocking { manager.startPairing(COOKIES) }
        assertEquals("phone-1", runBlocking { manager.finishPairing() })
        assertTrue(manager.isPaired())
        assertLogIsContentFree()
    }

    @Test
    fun pairingThatCannotStartRestoresTheState() {
        val manager = env.manager()
        env.setUpFake = { it.startPairingError = RelayException(RelayException.COOKIES_INVALID, "cookies are not signed in") }
        try {
            runBlocking { manager.startPairing(COOKIES) }
            fail("expected cookies_invalid")
        } catch (e: RelayException) {
            assertEquals(RelayException.COOKIES_INVALID, e.code)
        }
        assertEquals(RelayState.Phase.UNPAIRED, manager.state.value.phase)
    }

    // --- state ----------------------------------------------------------------------

    @Test
    fun stateEventsDriveTheStateAndTheHealthTable() {
        val (manager, fake) = env.pairedManager()
        assertTrue(manager.isPaired())
        assertEquals(RelayState.Phase.DISCONNECTED, manager.state.value.phase)

        fake.emitState("connecting")
        manager.awaitIdleForTesting()
        assertEquals(RelayState.Phase.CONNECTING, manager.state.value.phase)
        assertEquals("connecting", env.repo.health().status)

        fake.conversations += ConversationDto(id = "c1", name = "Sam", lastMessageTs = 5_000, snippet = "hi")
        fake.connected = true
        fake.emitState("connected")
        manager.awaitIdleForTesting()
        assertEquals(RelayState.Phase.CONNECTED, manager.state.value.phase)
        assertTrue(manager.state.value.isOnline)
        val health = env.repo.health()
        assertEquals("connected", health.status)
        assertTrue(health.lastEventTs > 0)
        // Once per connection, the inbox is listed.
        env.eventually(manager, what = "inbox refresh") { env.repo.conversation("c1")?.name == "Sam" }
        env.eventually(manager, what = "account in health") { env.repo.health().account == "someone@example.com" }

        fake.emitState("reconnecting", RelayException.INTERNAL, "network error")
        manager.awaitIdleForTesting()
        assertEquals(RelayState(RelayState.Phase.RECONNECTING, "internal", "network error"), manager.state.value)
        assertEquals("internal: network error", env.repo.health().lastError)

        fake.emitState("active")
        fake.emitState("active_elsewhere", "", "BROWSER_INACTIVE")
        manager.awaitIdleForTesting()
        assertEquals(RelayState.Phase.ACTIVE_ELSEWHERE, manager.state.value.phase)
        assertFalse(manager.state.value.isOnline)

        fake.emitState("phone_offline", RelayException.PHONE_NOT_RESPONDING)
        manager.awaitIdleForTesting()
        assertEquals(RelayState.Phase.PHONE_OFFLINE, manager.state.value.phase)
        assertEquals("phone_offline", env.repo.health().status)
        assertTrue(RelayLog.read(env.context).any { it.endsWith("State: phone_offline (phone_not_responding)") })
    }

    @Test
    fun everySessionTheBridgeHandsOverIsEncryptedAndStored() {
        val (manager, fake) = env.pairedManager()
        val rotated = """{"envelope":1,"auth":"rotated"}""".toByteArray()
        fake.emitState("connecting")
        // As after an auth token refresh.
        fake.pushSession(rotated)
        manager.awaitIdleForTesting()
        assertArrayEquals(rotated, SessionVault.load(env.context))
        assertTrue(env.repo.health().lastTokenRefresh > 0)
        val onDisk = File(File(env.context.filesDir, "relay"), "session.bin").readBytes()
        assertFalse(String(onDisk, Charsets.ISO_8859_1).contains("rotated"))
    }

    // --- messages -------------------------------------------------------------------

    @Test
    fun messageEventsAreStoredAndTheEchoReplacesTheLocalCopy() {
        val (manager, fake) = env.pairedManager()
        fake.emitMessage(incoming("m1", "hello there"))
        manager.awaitIdleForTesting()
        val stored = env.repo.message("m1")
        assertNotNull(stored)
        assertEquals("hello there", stored!!.text)
        assertEquals("m1", env.repo.conversation(CONV)!!.latestMessageId)

        // Offline, a send waits in the outbox as "tmp:<id>".
        val tmpId = manager.sendText(CONV, "on my way")
        manager.awaitIdleForTesting()
        val local = env.repo.message(MessageRow.tmpMessageId(tmpId))
        assertNotNull(local)
        assertEquals(LocalState.PENDING, local!!.localState)
        assertNotNull(env.repo.outbox(tmpId))
        assertTrue(fake.sent.isEmpty())

        // The phone's echo (sent from another client, say) replaces it.
        fake.emitMessage(outgoing("m2", "on my way", tmpId))
        manager.awaitIdleForTesting()
        assertNull(env.repo.message(MessageRow.tmpMessageId(tmpId)))
        assertEquals("on my way", env.repo.message("m2")!!.text)
        assertNull(env.repo.outbox(tmpId))

        // A deletion removes it.
        fake.emitMessage(MessageDto(id = "m1", conversationId = CONV, direction = "deleted"))
        manager.awaitIdleForTesting()
        assertNull(env.repo.message("m1"))
        assertLogIsContentFree()
    }

    @Test
    fun conversationAndTypingEvents() {
        val (manager, fake) = env.pairedManager()
        fake.emit(
            "conversation",
            ConversationDto(
                id = CONV, name = "Sam",
                participants = listOf(ParticipantDto(id = "p2", name = "Sam Jones"), ParticipantDto(id = "me", isMe = true)),
            ).toJson(),
        )
        manager.awaitIdleForTesting()
        assertEquals("Sam", env.repo.conversation(CONV)!!.name)

        fake.emitTyping(CONV, "p2", typing = true)
        manager.awaitIdleForTesting()
        assertEquals(mapOf(CONV to setOf("Sam Jones")), manager.typing.value)

        // Typing expires by itself (6 s in the app, shorter here).
        env.eventually(manager, what = "typing to expire") { manager.typing.value.isEmpty() }

        fake.emitTyping(CONV, "p2", typing = true)
        fake.emitTyping(CONV, "p2", typing = false)
        manager.awaitIdleForTesting()
        assertTrue(manager.typing.value.isEmpty())
    }

    // --- stickers -------------------------------------------------------------------

    @Test
    fun aHeicStickerFromTheLinkLandsInTheInbox() {
        assumeTrue("native decoder not available on this machine", HeifCodec.isAvailable)
        val (manager, fake) = env.pairedManager()
        val bytes = TestMedia.bytes("still_alpha.heic")
        fake.media["media-1"] = bytes

        fake.emitMessage(
            incoming("m-sticker", "").copy(
                parts = listOf(
                    PartDto(
                        partId = "part-1", kind = "media", mediaId = "media-1", key = ByteArray(32) { 7 },
                        mime = "image/heic", size = bytes.size.toLong(),
                    )
                )
            )
        )

        val store = InboxStore.get(env.context)
        env.eventually(manager, what = "sticker in the inbox") { store.list().isNotEmpty() }
        val item = store.list().single()
        assertEquals(InboxItem.SOURCE_RELAY, item.source)
        assertEquals(InboxStore.idFor("relay", CONV, "m-sticker", 0, "part-1"), item.id)
        assertEquals(TestMedia.WIDTH, item.width)
        assertEquals(listOf("media-1"), fake.downloads)

        env.eventually(manager, what = "part marked checked") { env.repo.mediaPart("m-sticker", "part-1")!!.stickerChecked }
        val part = env.repo.mediaPart("m-sticker", "part-1")!!
        assertTrue("downloaded file kept for the conversation screen", File(part.localPath).readBytes().contentEquals(bytes))

        // The same message again (a status update) is not downloaded again.
        fake.emitMessage(
            incoming("m-sticker", "").copy(
                status = "INCOMING_DISPLAYED",
                parts = listOf(
                    PartDto(partId = "part-1", kind = "media", mediaId = "media-1", key = ByteArray(32) { 7 }, mime = "image/heic")
                ),
            )
        )
        manager.awaitIdleForTesting()
        Thread.sleep(100)
        assertEquals(1, fake.downloads.size)
        assertEquals(1, store.list().size)
    }

    @Test
    fun aThumbnailOnlyStickerAsksForTheFullSizeOnce() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        val thumbOnly = incoming("m-thumb", "").copy(
            parts = listOf(
                PartDto(
                    partId = "action-1", kind = "media", thumbMediaId = "thumb-1", thumbKey = ByteArray(32),
                    mime = "image/heic", needsFullSize = true,
                )
            )
        )
        fake.emitMessage(thumbOnly)
        env.eventually(manager, what = "full-size request") { fake.fullSizeRequests.isNotEmpty() }
        env.eventually(manager, what = "request recorded") { env.repo.mediaPart("m-thumb", "action-1")!!.fullSizeRequested }
        assertEquals(listOf("m-thumb" to "action-1"), fake.fullSizeRequests.toList())
        assertTrue(fake.downloads.isEmpty())
    }

    @Test
    fun picturesMessagesCanShowAreNotDownloadedForTheInbox() {
        val (manager, fake) = env.pairedManager()
        fake.media["jpeg-1"] = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)
        fake.emitMessage(
            incoming("m-photo", "").copy(
                parts = listOf(PartDto(partId = "p", kind = "media", mediaId = "jpeg-1", key = ByteArray(32), mime = "image/jpeg"))
            )
        )
        manager.awaitIdleForTesting()
        Thread.sleep(100)
        assertTrue(fake.downloads.isEmpty())
    }

    // --- losing the pairing -----------------------------------------------------------

    @Test
    fun loggedOutStopsTheServiceKeepsTheArchiveAndAsksToPairAgain() {
        val (manager, fake) = env.pairedManager()
        RelayPrefs.setLiveEnabled(env.context, true)
        fake.goOnline()
        fake.emitMessage(incoming("m1", "kept"))
        manager.awaitIdleForTesting()

        val withoutPairing = """{"envelope":1,"auth":"cookies-only"}""".toByteArray()
        fake.loseThePairing(withoutPairing)
        manager.awaitIdleForTesting()

        assertEquals(RelayState.Phase.LOGGED_OUT, manager.state.value.phase)
        assertEquals(RelayException.LOGGED_OUT, manager.state.value.code)
        assertFalse(manager.isPaired())
        assertFalse(RelayPrefs.liveEnabled(env.context))
        assertArrayEquals("session kept (cookies and keys), only the pairing is gone", withoutPairing, SessionVault.load(env.context))
        assertEquals("kept", env.repo.message("m1")!!.text)
        assertEquals("logged_out", env.repo.health().status)
        val stopped = shadowOf(env.context as Application).nextStoppedService
        assertEquals(RelayService::class.java.name, stopped?.component?.className)
        val notifications = env.context.getSystemService(NotificationManager::class.java)
        val repair = shadowOf(notifications).allNotifications.single()
        assertEquals(RelayNotifications.CHANNEL_ALERTS, repair.channelId)
    }

    @Test
    fun invalidCookiesKeepThePairingButStopTheService() {
        val (manager, fake) = env.pairedManager()
        RelayPrefs.setLiveEnabled(env.context, true)
        fake.goOnline()
        fake.emitState("cookies_invalid", RelayException.COOKIES_INVALID, "Google rejected the account cookies")
        manager.awaitIdleForTesting()
        assertEquals(RelayState.Phase.COOKIES_INVALID, manager.state.value.phase)
        assertTrue(manager.isPaired())
        assertFalse(RelayPrefs.liveEnabled(env.context))
        assertNotNull(shadowOf(env.context as Application).nextStoppedService)
    }

    // --- unpairing ------------------------------------------------------------------

    @Test
    fun unpairWithDeleteArchiveWipesArchiveAndSessionButNotTheStickerInbox() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        fake.emitMessage(incoming("m1", "secret"))
        manager.awaitIdleForTesting()
        val inboxFile = File(File(env.context.filesDir, "inbox"), "keep.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        assertNotNull(env.repo.message("m1"))

        runBlocking { manager.unpair(deleteArchive = true) }

        assertTrue(fake.calls.contains("unpair"))
        assertFalse(manager.isPaired())
        assertEquals(RelayState.Phase.UNPAIRED, manager.state.value.phase)
        assertFalse(SessionVault.exists(env.context))
        assertNull(SessionVault.load(env.context))
        assertNull(env.repo.message("m1"))
        assertTrue(env.repo.conversations(folder = null).isEmpty())
        assertTrue("sticker inbox untouched", inboxFile.exists())
        assertFalse(RelayPrefs.liveEnabled(env.context))
    }

    @Test
    fun unpairCanKeepTheArchive() {
        val (manager, fake) = env.pairedManager()
        fake.emitMessage(incoming("m1", "kept"))
        manager.awaitIdleForTesting()

        runBlocking { manager.unpair(deleteArchive = false) }

        assertNull(SessionVault.load(env.context))
        assertEquals("kept", env.repo.message("m1")!!.text)
        assertEquals("unpaired", env.repo.health().status)
    }

    @Test
    fun sendsQueuedBeforeAnUnpairDoNotGoOutUnderTheNextPairing() {
        val (manager, fake) = env.pairedManager()
        val tmpId = manager.sendText(CONV, "for the old phone") // never online: stays queued
        manager.awaitIdleForTesting()

        runBlocking { manager.unpair(deleteArchive = false) }
        val local = env.repo.message(MessageRow.tmpMessageId(tmpId))!!
        assertEquals(LocalState.FAILED, local.localState)
        assertEquals(RelayManager.UNPAIRED_ERROR, local.errorText)

        // Paired again (perhaps another phone, where this id is someone else) and online.
        runBlocking { manager.startPairing(COOKIES) }
        runBlocking { manager.finishPairing() }
        fake.goOnline()
        manager.awaitIdleForTesting()
        Thread.sleep(150)
        assertTrue("nothing sent by itself", fake.sent.isEmpty())

        // The user can still choose to send it.
        manager.retry(tmpId)
        env.eventually(manager, what = "the resend") { fake.sent.size == 1 }
    }

    // --- user actions -----------------------------------------------------------------

    @Test
    fun markReadClearsLocallyAndTellsThePhoneTheLatestIncoming() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        fake.emit("conversation", ConversationDto(id = CONV, name = "Sam", unread = true).toJson())
        fake.emitMessage(incoming("m1", "one", ts = 1_000))
        fake.emitMessage(incoming("m2", "two", ts = 2_000))
        fake.emitMessage(outgoing("m3", "mine", "", ts = 3_000))
        manager.awaitIdleForTesting()
        assertTrue(env.repo.conversation(CONV)!!.unread)

        runBlocking { manager.markRead(CONV) }
        assertFalse(env.repo.conversation(CONV)!!.unread)
        assertTrue(fake.calls.contains("markRead:m2"))
    }

    @Test
    fun reactionsChooseAddSwitchOrRemove() {
        val (manager, fake) = env.pairedManager()
        fake.goOnline()
        fake.emitMessage(incoming("m1", "one").copy(reactions = listOf(ReactionDto("👍", listOf("me"), fromMe = true))))
        manager.awaitIdleForTesting()
        runBlocking {
            manager.react("m1", "❤️", remove = false)
            manager.react("m1", "👍", remove = true)
        }
        assertEquals(listOf("sendReaction:switch", "sendReaction:remove"), fake.calls.filter { it.startsWith("sendReaction") })
    }

    @Test
    fun userActionsWhileOfflineThrowNotConnected() {
        val (manager, _) = env.pairedManager()
        try {
            runBlocking { manager.refreshConversations() }
            fail("expected not_connected")
        } catch (e: RelayException) {
            assertEquals(RelayException.NOT_CONNECTED, e.code)
        }
        // markRead never throws.
        runBlocking { manager.markRead(CONV) }
    }

    private fun assertLogIsContentFree() {
        val log = RelayLog.read(env.context).joinToString("\n")
        for (secret in listOf("🦊", "someone@example.com", "hello there", "on my way", "SID", "secret-sid")) {
            assertFalse("log must not contain '$secret'", log.contains(secret))
        }
    }

    companion object {
        const val CONV = "conv-1"
        val COOKIES = mapOf("SID" to "secret-sid", "HSID" to "h", "SSID" to "s", "APISID" to "a", "SAPISID" to "sa", "OSID" to "o")

        fun incoming(id: String, text: String, ts: Long = 1_000) = MessageDto(
            id = id, conversationId = CONV, senderId = "p2", senderName = "Sam", timestampMs = ts,
            direction = "in", status = "INCOMING_COMPLETE", text = text,
        )

        fun outgoing(id: String, text: String, tmpId: String, ts: Long = 2_000) = MessageDto(
            id = id, conversationId = CONV, tmpId = tmpId, senderId = "me", fromMe = true, timestampMs = ts,
            direction = "out", status = "OUTGOING_DELIVERED", text = text,
        )
    }
}
