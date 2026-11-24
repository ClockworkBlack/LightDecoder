package com.lightdecoder.imessage.direct

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.rust_bridge.AttachmentMeta
import uniffi.rust_bridge.AttributedBody
import uniffi.rust_bridge.AttributedRun
import uniffi.rust_bridge.CloudAttachment
import uniffi.rust_bridge.CloudChat
import uniffi.rust_bridge.CloudChatPage
import uniffi.rust_bridge.CloudMessage
import uniffi.rust_bridge.CloudMessageBody
import uniffi.rust_bridge.CloudMessagePage
import uniffi.rust_bridge.CloudParticipant
import uniffi.rust_bridge.CloudProp001
import uniffi.rust_bridge.MessageProto

/** Messages in iCloud as the archive keeps them ([CloudMapping]) and the import's runs ([CloudImport]), read-only. */
@RunWith(AndroidJUnit4::class)
class CloudMappingTest {

    private val now = 1_800_000_000_000L
    private val bob = "tel:+15550000002"
    private val guid = "0A1B2C3D-0000-1111-2222-333344445555"

    private fun chat(identifier: String, style: Long = 45, groupId: String = "", service: String = "iMessage", name: String? = null, people: List<String> = listOf(identifier)) =
        CloudChat(
            style, 0, 0, 0, identifier, groupId, service, "", null, people.map(::CloudParticipant), CloudProp001(0u), 0, "", "chat-guid", name, null, null, false,
        )

    private fun proto(text: String?, body: ByteArray? = null) =
        MessageProto(1u, null, text, body, null, null, null, null, null, null, null, null, null, null, null, null, null)

    private fun message(
        text: String?,
        chatId: String = "iMessage;-;+15550000002",
        sender: String = "+15550000002",
        time: Long = 700_000_000_000_000_000L,
        g: String = guid,
        reaction: Boolean = false,
        body: ByteArray? = null,
        service: String = "iMessage",
    ) = CloudMessage(null, 0, chatId, sender, time, "", 0, g, service, CloudMessageBody.Message(reaction, proto(text, body), null, null, null))

    private val chats = mapOf("+15550000002" to "imsg:$bob", "chat123" to "imsg:group:G-1")

    private fun map(m: CloudMessage, skip: (String) -> Boolean = { false }, since: Long = 0, attachments: Map<String, CloudMapping.Attachment> = emptyMap(), decode: (ByteArray) -> AttributedBody? = { null }) =
        CloudMapping.message(m, chats, attachments, decode, skip, since, now)

    @Test
    fun aChatWithOnePersonIsKeyedByThemAndAGroupByItsId() {
        val one = CloudMapping.chat("r1", chat("+15550000002"))!!
        assertEquals("imsg:$bob", one.conversation.id)
        assertFalse(one.conversation.isGroup)
        val mail = CloudMapping.chat("r2", chat("Carol@Example.com"))!!
        assertEquals("imsg:mailto:carol@example.com", mail.conversation.id)
        val group = CloudMapping.chat("r3", chat("chat123", style = 43, groupId = "G-1", name = "Trip‮", people = listOf("tel:+15550000002", "mailto:a@example.com")))!!
        assertEquals("imsg:group:G-1", group.conversation.id)
        assertTrue(group.conversation.isGroup)
        assertEquals("Trip", group.conversation.name)
        assertEquals(2, group.conversation.participants.size)
        // Not iMessage, or not an address, or a group with no id: left out.
        assertNull(CloudMapping.chat("r4", chat("+15550000002", service = "SMS")))
        assertNull(CloudMapping.chat("r5", chat("chat999", people = emptyList())))
        assertNull(CloudMapping.chat("r6", chat("chat123", style = 43, groupId = "bad id!")))
    }

    @Test
    fun theSenderIsTheUserWhenEmptyAndElseThePerson() {
        val mine = map(message("hi", sender = ""))!!
        assertTrue(mine.fromMe)
        assertEquals("out", mine.direction)
        val theirs = map(message("hello"))!!
        assertFalse(theirs.fromMe)
        assertEquals(bob, theirs.senderId)
        assertEquals("imsg:$bob", theirs.conversationId)
        assertEquals("imsg:$guid", theirs.id)
    }

    @Test
    fun everyMessageIsMarkedImportedAndOld() {
        val m = map(message("hello"))!!
        assertTrue(IMessageExtra.parse(m.extra).imported)
        assertTrue(m.isOld)
    }

    @Test
    fun appleTimeIsClamped() {
        // Nanoseconds since 2001.
        assertEquals(978_307_200_000L + 700_000_000_000L, CloudMapping.timeOf(700_000_000_000_000_000L, now))
        // Seconds since 2001 (older records).
        assertEquals(978_307_200_000L + 700_000_000_000L, CloudMapping.timeOf(700_000_000L, now))
        // Never before 2001 nor after now.
        assertEquals(978_307_200_000L, CloudMapping.timeOf(-5, now))
        assertEquals(now, CloudMapping.timeOf(Long.MAX_VALUE, now))
    }

    @Test
    fun textsAndBodiesAreCapped() {
        val long = map(message("x".repeat(CloudMapping.MAX_TEXT_CHARS + 500)))!!
        assertEquals(CloudMapping.MAX_TEXT_CHARS, long.text.length)
        var decoded = 0
        val huge = ByteArray(CloudMapping.MAX_BODY_BYTES + 1)
        assertNull(map(message(null, body = huge), decode = { decoded++; AttributedBody("never", emptyList()) }))
        assertEquals("a body over the cap is never decoded", 0, decoded)
        val ok = map(message(null, body = "from body".toByteArray()), decode = { AttributedBody(String(it), emptyList()) })!!
        assertEquals("from body", ok.text)
        // A body the library cannot read is no message (nothing thrown).
        assertNull(map(message(null, body = "x".toByteArray()), decode = { throw IllegalStateException("panic") }))
    }

    @Test
    fun alreadyStoredOrDeletedGuidsAndOldOnesAreLeftOut() {
        assertNull(map(message("hi"), skip = { it == "imsg:$guid" }))
        assertNull(map(message("hi", time = 100_000_000L), since = now - 1000))
        assertNull(map(message("tapback", reaction = true)))
        assertNull(map(message("sms", service = "SMS")))
        assertNull(map(message("unknown chat", chatId = "iMessage;-;+19990000000")))
        assertNull(map(message("bad guid", g = "not a guid!")))
    }

    @Test
    fun attachmentsAreRecordIdsDownloadedOnDemand() {
        val meta = AttachmentMeta("image/jpeg", 0, 1234, 0, false, "ATT-1", false, null, "IMG.jpg", null, false, "IMG.jpg", 1, null, 0, null, null)
        val (g, a) = CloudMapping.attachment("rec-1", CloudAttachment(meta))!!
        assertEquals("ATT-1", g)
        val body = AttributedBody("￼", listOf(AttributedRun(1u, "ATT-1", 0, null, null, null, null, null, null, null, null)))
        val m = map(message(null, body = "b".toByteArray()), attachments = mapOf(g to a), decode = { body })!!
        val part = m.parts.single { it.isMedia }
        assertEquals("rec-1", CloudMapping.recordOf(part.key))
        assertEquals("$guid/p1", part.mediaId)
        // A record id that could name anything else is never taken.
        assertNull(CloudMapping.attachment("../../x", CloudAttachment(meta)))
        assertNull(CloudMapping.recordOf("descriptor".toByteArray()))
    }

    @Test
    fun batchesStayUnderTheUpdateCap() {
        val many = (1..300).map { i -> map(message("y".repeat(900), g = "0A1B2C3D-0000-1111-2222-%012d".format(i)))!! }
        val batches = CloudMapping.batches(emptyList(), many)
        assertTrue(batches.size > 1)
        assertEquals(300, batches.sumOf { it.messages.size })
        for (b in batches) assertTrue(DirectWire.encodeUpdate(b).toString().length <= DirectWire.MAX_UPDATE_CHARS)
        // And they cross as they were made.
        val back = DirectWire.decode(JSONObject(DirectWire.encodeUpdate(batches[0]).toString())) as DirectArchive.Update.CloudBatch
        assertEquals(batches[0].messages.map { it.id }, back.messages.map { it.id })
    }

    // --- the import's runs ------------------------------------------------------------------------

    private val dir: File = Files.createTempDirectory("ld-cloud").toFile()
    private val bridge = FakeFeatureBridge()
    private val delivered = ArrayList<DirectArchive.Update.CloudBatch>()
    private var on = true
    private var adp = 0
    private val waits = ArrayList<Long>()

    private fun import(context: kotlin.coroutines.CoroutineContext) = CloudImport(
        bridge, dir, enabled = { on }, deliver = { delivered += it; true }, adp = { adp++ }, now = { now }, context = context,
        wait = { waits += it },
    )

    @Test
    fun aRunTakesChatsThenAttachmentsThenMessagesAndKeepsItsTokens() = runTest {
        bridge.chatPages += { CloudChatPage("c1".toByteArray(), mapOf("r1" to chat("+15550000002")), 1) }
        bridge.messagePages += { CloudMessagePage("m1".toByteArray(), mapOf("x" to message("hello"), "gone" to null), 1) }
        val run = import(StandardTestDispatcher(testScheduler))
        assertTrue(run.start(0))
        run.join()
        assertEquals(listOf("syncCloudChats", "syncCloudChats", "syncCloudAttachments", "syncCloudMessages", "syncCloudMessages"), bridge.calls.filter { it.startsWith("sync") })
        assertEquals(1, delivered.flatMap { it.chats }.size)
        assertEquals(1, delivered.flatMap { it.messages }.size)
        assertTrue(run.progress.value.done)
        // The next run goes on from where this one ended.
        bridge.calls.clear()
        bridge.tokens.clear()
        val again = import(StandardTestDispatcher(testScheduler))
        again.start(0)
        again.join()
        assertEquals("c-end", bridge.tokens.first { it.first == "chats" }.second)
        assertEquals("m-end", bridge.tokens.first { it.first == "messages" }.second)
    }

    @Test
    fun aWiderWindowPagesTheMessagesAgainAndANarrowerOneGoesOn() = runTest {
        val run = import(StandardTestDispatcher(testScheduler))
        run.start(now - 31L * 24 * 60 * 60 * 1000)
        run.join()
        assertEquals("m-end", run.let { CloudImport.State.load(dir).tokens["messages"]?.let { String(it) } })
        // A narrower one (or the same one, later): goes on from where it was.
        bridge.tokens.clear()
        val later = import(StandardTestDispatcher(testScheduler))
        later.start(now - 10L * 24 * 60 * 60 * 1000)
        later.join()
        assertEquals("m-end", bridge.tokens.first { it.first == "messages" }.second)
        // "All": the messages from the start again (else the older ones would never come); chats go on.
        bridge.tokens.clear()
        val wider = import(StandardTestDispatcher(testScheduler))
        wider.start(0)
        wider.join()
        assertNull(bridge.tokens.first { it.first == "messages" }.second)
        assertEquals("c-end", bridge.tokens.first { it.first == "chats" }.second)
    }

    @Test
    fun aMessageUnsentOnTheUsersDevicesIsNotBroughtBack() {
        fun summary(retracted: List<UInt>) = uniffi.rust_bridge.MessageSummaryInfo(
            null, null, null, null, null, emptyMap(), emptyList(), emptyMap(), null, retracted, null, null, null, emptyList(),
        )
        fun withSummary(bytes: ByteArray) = message("hello").let { m ->
            val b = m.message as CloudMessageBody.Message
            m.copy(message = b.copy(proto = b.proto.copy(messageSummaryInfo = bytes)))
        }
        val m = withSummary(byteArrayOf(1, 2, 3))
        assertNull(CloudMapping.message(m, chats, emptyMap(), { null }, { false }, 0, now, summary = { summary(listOf(0u)) }))
        assertNotNull(CloudMapping.message(m, chats, emptyMap(), { null }, { false }, 0, now, summary = { summary(emptyList()) }))
        // Not readable, or too large to read: left out too.
        assertNull(CloudMapping.message(m, chats, emptyMap(), { null }, { false }, 0, now, summary = { throw DirectEngine.ImessageError("bad") }))
        assertNull(CloudMapping.message(withSummary(ByteArray(CloudMapping.MAX_BODY_BYTES + 1)), chats, emptyMap(), { null }, { false }, 0, now, summary = { summary(emptyList()) }))
    }

    @Test
    fun forgetWaitsForTheRunSoNothingOfItIsWrittenAgain() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        bridge.chatPages += { kotlinx.coroutines.runBlocking { gate.await() }; CloudChatPage("c".toByteArray(), emptyMap(), 1) }
        val run = import(kotlinx.coroutines.Dispatchers.IO)
        run.start(0)
        Thread.sleep(50)
        val forgetting = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { run.forget() }
        Thread.sleep(50)
        gate.complete(Unit)
        forgetting.join()
        assertFalse(File(dir, "state.json").exists())
    }

    @Test
    fun tooManyRequestsWaitsAndTriesAgainSixTimesAtMost() = runTest {
        var failures = 0
        bridge.chatPages += { failures++; throw DirectEngine.ImessageError("Too many requests") }
        bridge.chatPages += { failures++; throw DirectEngine.ImessageError("Too many requests") }
        val run = import(StandardTestDispatcher(testScheduler))
        run.start(0)
        run.join()
        assertEquals(2, failures)
        assertEquals(listOf(CloudImport.waitFor(1), CloudImport.waitFor(2)), waits)
        assertEquals(10_000L, CloudImport.waitFor(1))
        assertEquals(30_000L, CloudImport.waitFor(9))
        assertTrue(run.progress.value.done)
        // Always failing: given up after six.
        repeat(10) { bridge.chatPages += { throw DirectEngine.ImessageError("Too many requests") } }
        bridge.chatPages.add(0) { throw DirectEngine.ImessageError("Too many requests") }
        val busy = import(StandardTestDispatcher(testScheduler))
        busy.start(0)
        busy.join()
        assertNotNull(busy.progress.value.failed)
    }

    @Test
    fun advancedDataProtectionTurnsItOff() = runTest {
        bridge.chatPages += { throw DirectEngine.ImessageError("Disable Advanced Data Protection to continue") }
        val run = import(StandardTestDispatcher(testScheduler))
        run.start(0)
        run.join()
        assertEquals(1, adp)
        assertNotNull(run.progress.value.failed)
    }

    @Test
    fun offItDoesNothingAndForgetRemovesItsTokens() = runTest {
        on = false
        val run = import(StandardTestDispatcher(testScheduler))
        assertFalse(run.start(0))
        assertTrue(bridge.calls.isEmpty())
        on = true
        run.start(0)
        run.join()
        assertTrue(File(dir, "state.json").isFile)
        run.forget()
        assertFalse(dir.exists())
    }

    @Test
    fun stopEndsARun() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        bridge.chatPages += { kotlinx.coroutines.runBlocking { gate.await() }; CloudChatPage("c".toByteArray(), emptyMap(), CloudImport.DONE) }
        val run = import(kotlinx.coroutines.Dispatchers.IO)
        run.start(0)
        Thread.sleep(50)
        run.stop()
        gate.complete(Unit)
        run.join()
        assertFalse(run.progress.value.running)
        assertFalse(run.progress.value.done)
    }

    @Test
    fun theImportNeverCallsAnythingButReads() = runTest {
        bridge.chatPages += { CloudChatPage("c1".toByteArray(), mapOf("r1" to chat("+15550000002")), CloudImport.DONE) }
        val run = import(StandardTestDispatcher(testScheduler))
        run.start(0)
        run.join()
        assertTrue(bridge.calls.all { it.startsWith("sync") || it == "decodeAttributedBody" })
    }
}
