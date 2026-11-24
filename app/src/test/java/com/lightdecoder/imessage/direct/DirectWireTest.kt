package com.lightdecoder.imessage.direct

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.relay.ConversationDto
import com.lightdecoder.relay.MessageDto
import com.lightdecoder.relay.ParticipantDto
import com.lightdecoder.relay.PartDto
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What crosses between the ":imessage" process and the main process comes out as it went in. */
@RunWith(AndroidJUnit4::class)
class DirectWireTest {

    /** Through JSON text, as the Bundle carries it. */
    private fun trip(u: DirectArchive.Update) = DirectWire.decode(JSONObject(DirectWire.encode(u).toString()))

    @Test
    fun everyKindOfChangeSurvivesTheTrip() {
        val conv = ConversationDto(
            id = "imsg:tel:+15550000002", name = "+15550000002", type = DirectArchive.TRANSPORT, defaultOutgoingId = "tel:+15550000001",
            participants = listOf(
                ParticipantDto(id = "tel:+15550000002", name = "+15550000002", number = "+15550000002"),
                ParticipantDto(id = "tel:+15550000001", isMe = true),
            ),
            otherParticipantIds = listOf("tel:+15550000002"),
        )
        val msg = MessageDto(
            id = "imsg:G1", conversationId = conv.id, senderId = "tel:+15550000002", timestampMs = 1234, direction = "in",
            status = DirectArchive.STATUS_RECEIVED, transport = DirectArchive.TRANSPORT, text = "hi \"there\"\nnew line ✓",
            parts = listOf(PartDto(partId = "0", kind = "text", text = "hi \"there\"\nnew line ✓")), replyToId = "imsg:G0",
            editedAtMs = 99, extra = IMessageExtra(parts = mapOf(0 to "hi"), effect = "com.apple.MobileSMS.expressivesend.loud").toJson(),
        )
        val up = trip(DirectArchive.Update.Upsert(conv, msg)) as DirectArchive.Update.Upsert
        assertEquals(conv, up.conversation)
        assertEquals(msg.copy(parts = msg.parts), up.message)
        assertEquals(DirectArchive.Update.Remove("imsg:G1"), trip(DirectArchive.Update.Remove("imsg:G1")))
        assertEquals(
            DirectArchive.Update.Receipt(listOf("imsg:A", "imsg:B"), DirectArchive.STATUS_READ),
            trip(DirectArchive.Update.Receipt(listOf("imsg:A", "imsg:B"), DirectArchive.STATUS_READ)),
        )
        assertEquals(DirectArchive.Update.Edit("imsg:G1", "fixed", 2, 5_000), trip(DirectArchive.Update.Edit("imsg:G1", "fixed", 2, 5_000)))
        val kinds = listOf(
            DirectArchive.Update.Reaction("imsg:G1", "+15550000002", "❤️", true, false, 7_000),
            DirectArchive.Update.Reaction("imsg:G1", "+15550000001", "🦊", false, true, 8_000),
            DirectArchive.Update.Unsent("imsg:G1", 1, false, "Bob"),
            DirectArchive.Update.Unsent("imsg:G1", 0, true, ""),
            DirectArchive.Update.Typing("imsg:tel:+15550000002", "+15550000002", true),
            DirectArchive.Update.Typing("imsg:group:G", "a@b.c", false),
            DirectArchive.Update.Typing("imsg:tel:+15550000002", "+15550000002", true, groupGuid = "G-S"),
            DirectArchive.Update.ReadElsewhere("imsg:G1"),
            DirectArchive.Update.Failed("imsg:G1", "Not delivered"),
            // Who did it and where, as the main process checks it.
            DirectArchive.Update.Receipt(listOf("imsg:A"), DirectArchive.STATUS_DELIVERED, "tel:+15550000002"),
            DirectArchive.Update.Edit("imsg:G1", "fixed", 0, 5_000, emptyList(), "tel:+15550000002", false, "imsg:group:G", "G"),
            DirectArchive.Update.Edit("imsg:G1", "mine", 0, 6_000, emptyList(), "tel:+15550000001", true, ""),
            DirectArchive.Update.Reaction("imsg:G1", "+15550000002", "❤️", true, false, 7_000, "imsg:tel:+15550000002"),
            DirectArchive.Update.Reaction("imsg:G1", "+15550000002", "👍", true, false, 7_000, "imsg:group:G", "G"),
            DirectArchive.Update.Unsent("imsg:G1", 0, false, "Bob", "tel:+15550000002", 9_000, "imsg:group:G", "G"),
            DirectArchive.Update.Remove("imsg:G2", scheduledOnly = true),
            DirectArchive.Update.Dropped("chat background", "its poster is larger than 20 MB"),
        )
        for (u in kinds) assertEquals(u, trip(u))
        val changed = trip(DirectArchive.Update.Upsert(conv, msg, membersChanged = true)) as DirectArchive.Update.Upsert
        assertTrue(changed.membersChanged)
        assertFalse((trip(DirectArchive.Update.Upsert(conv, msg)) as DirectArchive.Update.Upsert).membersChanged)
        assertEquals(DirectArchive.Update.Ignore, trip(DirectArchive.Update.Ignore))
        assertEquals(DirectArchive.Update.Ignore, DirectWire.decode(JSONObject().put("kind", "from-a-newer-version")))
    }

    @Test
    fun aMessageTooLargeForOneTransactionDropsItsLargestDescriptionsUntilItFits() {
        val conv = ConversationDto(id = "imsg:tel:+15550000002", type = DirectArchive.TRANSPORT)
        fun media(idx: Int, keyBytes: Int) = PartDto(
            partId = "p$idx", kind = "media", mediaId = "G9/p$idx", key = ByteArray(keyBytes) { 'k'.code.toByte() },
            mime = "image/png", fileName = "$idx.png", size = 10,
        )
        val msg = MessageDto(
            id = "imsg:G9", conversationId = conv.id, timestampMs = 1, direction = "in", transport = DirectArchive.TRANSPORT,
            // Two descriptions that together are more than one transaction takes, and a small one.
            parts = listOf(media(0, 2_000), media(1, 60_000), media(2, 50_000)),
        )
        val big = DirectArchive.Update.Upsert(conv, msg)
        assertTrue(DirectWire.encode(big).toString().length > DirectWire.MAX_UPDATE_CHARS)
        val json = DirectWire.encodeUpdate(big)
        assertTrue(json.toString().length <= DirectWire.MAX_UPDATE_CHARS)
        val fitted = DirectWire.decode(JSONObject(json.toString())) as DirectArchive.Update.Upsert
        // The largest went; the others, and everything else of the message, came across.
        assertEquals(listOf(2_000, 0, 50_000), fitted.message.parts.map { it.key.size })
        assertEquals(msg.parts.map { it.mediaId }, fitted.message.parts.map { it.mediaId })
        // One that fits, and any other change, as encode gives it.
        val small = DirectArchive.Update.Upsert(conv, msg.copy(parts = listOf(media(0, 2_000))))
        assertEquals(DirectWire.encode(small).toString(), DirectWire.encodeUpdate(small).toString())
        val receipt = DirectArchive.Update.Receipt(listOf("imsg:G9"), DirectArchive.STATUS_RECEIVED)
        assertEquals(DirectWire.encode(receipt).toString(), DirectWire.encodeUpdate(receipt).toString())
    }

    @Test
    fun aMessageStillTooLargeWithoutDescriptionsHasItsTextCutUntilItFitsAndNeverGoesOverTheLimit() {
        val conv = ConversationDto(id = "imsg:tel:+15550000002", type = DirectArchive.TRANSPORT)
        val text = "long pasted text ".repeat(20_000)
        val msg = MessageDto(
            id = "imsg:GT", conversationId = conv.id, timestampMs = 1, direction = "in", transport = DirectArchive.TRANSPORT,
            text = text, parts = listOf(PartDto("0", "text", text)), extra = IMessageExtra(parts = mapOf(0 to text)).toJson(),
        )
        val big = DirectArchive.Update.Upsert(conv, msg)
        assertTrue(DirectWire.encode(big).toString().length > 3 * DirectWire.MAX_UPDATE_CHARS)
        val json = DirectWire.encodeUpdate(big)
        assertTrue(json.toString().length <= DirectWire.MAX_UPDATE_CHARS)
        val fitted = DirectWire.decode(JSONObject(json.toString())) as DirectArchive.Update.Upsert
        // Stored, with the start of what was said.
        assertEquals("imsg:GT", fitted.message.id)
        assertTrue(fitted.message.text.length > 1_000)
        assertTrue(text.startsWith(fitted.message.text.removeSuffix("…")))
        assertTrue(fitted.message.text.endsWith("…"))
        assertTrue(text.startsWith(fitted.message.parts.single().text.removeSuffix("…")))
        // An app's card whose own state is huge: it goes, as a card without it.
        val app = IMessageExtra(app = IMessageExtra.App(name = "Game", url = "https://example.com/?" + "x".repeat(300_000)))
        val card = DirectArchive.Update.Upsert(conv, msg.copy(text = "", parts = emptyList(), extra = app.toJson()))
        val cardJson = DirectWire.encodeUpdate(card)
        assertTrue(cardJson.toString().length <= DirectWire.MAX_UPDATE_CHARS)
        assertEquals("imsg:GT", (DirectWire.decode(cardJson) as DirectArchive.Update.Upsert).message.id)
        // Nothing left to cut (thousands of members): taken as nothing, rather than handed over for ever.
        val crowd = conv.copy(participants = (1..20_000).map { ParticipantDto(id = "tel:+1555$it", name = "Person $it") })
        val tooMany = DirectWire.encodeUpdate(DirectArchive.Update.Upsert(crowd, msg.copy(text = "hi", parts = emptyList(), extra = "")))
        assertTrue(tooMany.toString().length <= DirectWire.MAX_UPDATE_CHARS)
        assertEquals(DirectArchive.Update.Ignore, DirectWire.decode(tooMany))
    }

    @Test
    fun anUpdateTheMainProcessCanNeverStoreIsGivenUpOnAndOneItCannotReachIsNot() {
        // The main process's answers.
        assertEquals(Delivery.STORED, Delivery.of(DirectWire.ok()))
        assertEquals(Delivery.REJECTED, Delivery.of(DirectWire.rejected(IllegalStateException("bad row"))))
        assertEquals(Delivery.REJECTED, Delivery.of(DirectWire.error(DirectIpc.Client.TOO_LARGE)))
        assertEquals(Delivery.UNREACHABLE, Delivery.of(DirectWire.error(DirectIpc.Client.UNREACHABLE)))
        assertEquals(Delivery.FAILED, Delivery.of(DirectWire.error(DirectIpc.Client.DIED)))
        assertEquals(Delivery.FAILED, Delivery.of(DirectWire.error(DirectIpc.Client.NO_ANSWER)))
        assertEquals(Delivery.FAILED, Delivery.of(DirectWire.failure(android.database.sqlite.SQLiteFullException("full"))))
        var clock = 0L
        val pauses = ArrayList<Long>()
        fun run(answers: Iterator<Delivery>): Pair<Boolean, Int> {
            var tries = 0
            val stored = kotlinx.coroutines.runBlocking {
                UpdateDelivery.untilSettled({ tries++; answers.next() }, { pauses.add(it); clock += it }, { clock })
            }
            return stored to tries
        }
        // Stored on a later try, after waits that grow and stop growing at about a minute.
        assertEquals(true to 9, run((List(8) { Delivery.UNREACHABLE } + Delivery.STORED).iterator()))
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 64_000L, 64_000L), pauses)
        // One that can never be stored: a few tries, then given up on.
        assertEquals(false to UpdateDelivery.MAX_REJECTED_TRIES, run(generateSequence { Delivery.REJECTED }.iterator()))
        // A main process that keeps failing at it: given up on after a while.
        clock = 0
        val (stored, tries) = run(generateSequence { Delivery.FAILED }.iterator())
        assertFalse(stored)
        assertTrue(clock >= UpdateDelivery.GIVE_UP_MS && clock < UpdateDelivery.GIVE_UP_MS + 65_000)
        assertTrue(tries > 3)
        // A main process that cannot be reached: never given up on (nothing after it could be stored either).
        clock = 0
        val unreachable = generateSequence { Delivery.UNREACHABLE }.take(1_000).iterator()
        val ended = kotlinx.coroutines.runBlocking {
            UpdateDelivery.untilSettled({ if (unreachable.hasNext()) unreachable.next() else Delivery.STORED }, { clock += it }, { clock })
        }
        assertTrue(ended)
        assertFalse(unreachable.hasNext())
        // A delivery that throws counts as failed, and is tried again.
        var thrown = false
        assertTrue(
            kotlinx.coroutines.runBlocking {
                UpdateDelivery.untilSettled({ if (!thrown) { thrown = true; throw IllegalStateException("x") } else Delivery.STORED }, { }, { 0 })
            },
        )
    }

    // --- blocking (SPEC2 B3) -------------------------------------------------------------------------

    @Test
    fun noDeliveryReceiptFollowsAMessageTheMainProcessDroppedAsBlocked() {
        val good = DirectWire.encode(DirectArchive.Update.Remove("imsg:G1"))
        // A fake main process ("sink") that drops it as from someone the user blocked.
        val sink = DirectEngine.Sink { update ->
            Delivery.of(JSONObject(DirectSinkService.take(DirectWire.encode(update)) { DirectLink.Applied.BLOCKED }.toString()))
        }
        val settled = kotlinx.coroutines.runBlocking { UpdateDelivery.settle({ sink.deliver(DirectWire.decode(good)) }, { }, { 0 }) }
        assertEquals(Delivery.BLOCKED, settled)
        assertFalse("no delivery receipt", DirectEngine.acknowledgesAfter(settled))
        // Settled, not tried again: the ones after it are not held up.
        assertTrue(kotlinx.coroutines.runBlocking { UpdateDelivery.untilSettled({ Delivery.BLOCKED }, { }, { 0 }) })
        // Stored, or nothing handed over, or given up on: the receipt goes as before.
        assertTrue(DirectEngine.acknowledgesAfter(Delivery.STORED))
        assertTrue(DirectEngine.acknowledgesAfter(null))
        val stored = DirectEngine.Sink { update -> Delivery.of(DirectSinkService.take(DirectWire.encode(update)) { DirectLink.Applied.STORED }) }
        assertTrue(DirectEngine.acknowledgesAfter(kotlinx.coroutines.runBlocking { UpdateDelivery.settle({ stored.deliver(DirectWire.decode(good)) }, { }, { 0 }) }))
        // The engine's own step (DirectEngine.handle): through the fake sink, the receipt is not sent after
        // "blocked", and is after "stored" and when nothing was handed over.
        val acks = ArrayList<String>()
        kotlinx.coroutines.runBlocking {
            assertEquals(Delivery.BLOCKED, DirectEngine.storeThenAcknowledge(DirectWire.decode(good), { DirectEngine.deliver(sink, it) }) { acks += "blocked" })
            assertEquals(Delivery.STORED, DirectEngine.storeThenAcknowledge(DirectWire.decode(good), { DirectEngine.deliver(stored, it) }) { acks += "stored" })
            assertNull(DirectEngine.storeThenAcknowledge(DirectArchive.Update.Ignore, { throw AssertionError("nothing to hand over") }) { acks += "ignored" })
        }
        assertEquals(listOf("stored", "ignored"), acks)
        // Only a real true says blocked; never an error.
        assertTrue(DirectWire.isBlocked(DirectWire.blocked()))
        assertFalse(DirectWire.isBlocked(DirectWire.ok().put("blocked", "true")))
        assertFalse(DirectWire.isBlocked(DirectWire.error("x").put("blocked", true)))
        assertEquals(Delivery.FAILED, Delivery.of(DirectWire.error("x").put("blocked", true)))
    }

    @Test
    fun theCallerAnswerSaysBlockedOnlyForARealTrue() {
        val info = com.lightdecoder.imessage.facetime.CallerInfo("Mallory", known = true, blocked = true)
        assertTrue(DirectWire.callerInfo(JSONObject(DirectWire.callerReply(info).toString())).blocked)
        assertFalse(DirectWire.callerInfo(JSONObject(DirectWire.callerReply(info.copy(blocked = false)).toString())).blocked)
        assertFalse(DirectWire.callerReply(info.copy(blocked = false)).has("blocked"))
        assertFalse(DirectWire.callerInfo(DirectWire.ok().put("blocked", "yes")).blocked)
        assertEquals(23, DirectWire.BLOCKLIST)
    }

    @Test
    fun theMainProcessRejectsAnUpdateItCanNeverStoreAndAsksAgainForOneItCouldNotStoreForNow() {
        val good = DirectWire.encode(DirectArchive.Update.Remove("imsg:G1"))
        val stored = ArrayList<DirectArchive.Update>()
        assertEquals(Delivery.STORED, Delivery.of(DirectSinkService.take(good) { stored.add(it) }))
        assertEquals(listOf<DirectArchive.Update>(DirectArchive.Update.Remove("imsg:G1")), stored)
        // Unreadable: never stored, whatever the tries.
        val unreadable = JSONObject().put("kind", "remove")
        assertEquals(Delivery.REJECTED, Delivery.of(DirectSinkService.take(unreadable) { stored.add(it) }))
        // Storing it fails as it would every time.
        assertEquals(Delivery.REJECTED, Delivery.of(DirectSinkService.take(good) { throw IllegalArgumentException("bad") }))
        // The storage full or busy: for now only.
        assertEquals(Delivery.FAILED, Delivery.of(DirectSinkService.take(good) { throw android.database.sqlite.SQLiteFullException("full") }))
        assertEquals(
            Delivery.FAILED,
            Delivery.of(DirectSinkService.take(good) { throw android.database.sqlite.SQLiteDatabaseLockedException("busy") }),
        )
        // An error while it is stored (a stack or the memory run out on what it holds): that update
        // rejected, never the main process taken down with it.
        assertEquals(Delivery.REJECTED, Delivery.of(DirectSinkService.take(good) { throw StackOverflowError("deep") }))
        assertEquals(Delivery.REJECTED, Delivery.of(DirectSinkService.take(good) { throw OutOfMemoryError("huge") }))
    }

    @Test
    fun aRequestThatWasNeverHandedOverDidNotGoAndIsNotAskedAboutAgain() {
        val dead = DirectIpc.Client.notHandedOver(android.os.DeadObjectException())
        assertEquals(DirectIpc.Client.UNREACHABLE, DirectWire.errorOf(dead))
        assertFalse(DirectWire.unconfirmed(dead))
        val tooLarge = DirectIpc.Client.notHandedOver(android.os.TransactionTooLargeException())
        assertEquals(DirectIpc.Client.TOO_LARGE, DirectWire.errorOf(tooLarge))
        assertFalse(DirectWire.unconfirmed(tooLarge))
        // Handed over, then the other process stopped: it may have gone.
        assertTrue(DirectWire.unconfirmed(DirectWire.error(DirectIpc.Client.DIED)))
    }

    @Test
    fun anOlderEditWithoutPartOrTimeIsPartZero() {
        val old = JSONObject().put("kind", "edit").put("messageId", "imsg:G1").put("text", "fixed")
        assertEquals(DirectArchive.Update.Edit("imsg:G1", "fixed", 0, 0), DirectWire.decode(old))
    }

    @Test
    fun targetsSurviveTheTripWithAndWithoutAGroup() {
        val one = DirectWire.Target(listOf("tel:+2"), null, null, "tel:+1")
        assertEquals(one, DirectWire.Target.fromJson(JSONObject(one.toJson().toString())))
        val group = DirectWire.Target(listOf("tel:+2", "mailto:a@b.c"), "Trip", "G-1", "tel:+1", afterGuid = "LAST")
        assertEquals(group, DirectWire.Target.fromJson(JSONObject(group.toJson().toString())))
        // From a side that does not know afterGuid yet.
        val older = JSONObject(one.toJson().toString()).apply { remove("afterGuid") }
        assertNull(DirectWire.Target.fromJson(older).afterGuid)
        assertTrue(one.isOneToOne)
        assertEquals("tel:+2", one.peer)
        assertFalse(group.isOneToOne)
        assertNull(group.peer)
        // The user's own address listed too is not another person.
        val listingMe = DirectWire.Target(listOf("tel:+1", "tel:+2"), null, null, "tel:+1")
        assertTrue(listingMe.isOneToOne)
        assertEquals("tel:+2", listingMe.peer)
        // Two people besides the user, without a group guid: not one-to-one either.
        assertNull(DirectWire.Target(listOf("tel:+2", "tel:+3"), null, null, "tel:+1").peer)
    }

    @Test
    fun aSendCarriesItsEffectAttachmentsAndVoiceFlag() {
        val t = DirectWire.Target(listOf("tel:+2"), null, null, "tel:+1", "G0")
        val files = listOf(DirectWire.Attachment("/data/x/a.jpg", "image/jpeg", "a.jpg"), DirectWire.Attachment("/data/x/b.m4a", "audio/mp4", "b.m4a"))
        val wire = JSONObject(DirectWire.send(t, "look", "G-R", "0:0:4", "com.apple.MobileSMS.expressivesend.impact", files, voice = true).toString())
        val r = DirectWire.SendRequest.fromJson(wire)
        assertEquals(DirectWire.SendRequest(t, "look", "G-R", "0:0:4", "com.apple.MobileSMS.expressivesend.impact", files, true), r)
        // A plain text, as before.
        val plain = DirectWire.SendRequest.fromJson(JSONObject(DirectWire.send(t, "hi", null).toString()))
        assertNull(plain.replyGuid)
        assertNull(plain.effect)
        assertTrue(plain.attachments.isEmpty())
        assertFalse(plain.voice)
        // The answer: the GUID, Apple's time and the attachment parts as Apple numbers them.
        val parts = listOf(DirectWire.SentPart(1, "p1", "image/jpeg", "a.jpg", 1234), DirectWire.SentPart(2, "p2", "audio/mp4", "b.m4a", 99))
        val reply = DirectWire.SendReply("GUID-9", 42, parts)
        assertEquals(reply, DirectWire.SendReply.fromJson(JSONObject(reply.toJson().toString())))
        assertEquals(emptyList<DirectWire.SentPart>(), DirectWire.SendReply.fromJson(JSONObject().put("guid", "G").put("ts", 1)).parts)
    }

    @Test
    fun theNewRequestsCarryWhatTheEngineReads() {
        val t = DirectWire.Target(listOf("tel:+2"), null, null, "tel:+1")
        val edit = JSONObject(DirectWire.edit(t, "G1", 0, "new text").toString())
        assertEquals(t, DirectWire.Target.fromJson(edit.getJSONObject("target")))
        assertEquals("G1", edit.getString("guid"))
        assertEquals(0, edit.getInt("part"))
        assertEquals("new text", edit.getString("text"))
        val react = JSONObject(DirectWire.react(t, "G1", 0, "❤️", false, "dinner?").toString())
        assertEquals("❤️", react.getString("emoji"))
        assertFalse(react.getBoolean("enable"))
        assertEquals("dinner?", react.getString("targetText"))
        assertTrue(JSONObject(DirectWire.typing(t, true).toString()).getBoolean("typing"))
        assertEquals("G2", JSONObject(DirectWire.read(t, "G2").toString()).getString("guid"))
        val download = JSONObject(DirectWire.download("<plist/>", "/data/x/f.jpg").toString())
        assertEquals("<plist/>", download.getString("descriptor"))
        assertEquals("/data/x/f.jpg", download.getString("path"))
        assertEquals(1234L, DirectWire.downloaded(1234).getLong("size"))
        assertNull(DirectWire.errorOf(DirectWire.downloaded(1)))
        assertEquals("R1", DirectWire.guid("R1").getString("guid"))
        // Every code is its own.
        val codes = listOf(
            DirectWire.SEND, DirectWire.UNSEND, DirectWire.REACH, DirectWire.EDIT, DirectWire.REACT, DirectWire.TYPING,
            DirectWire.READ, DirectWire.DOWNLOAD, DirectWire.EXTENSION, DirectWire.UPDATE, DirectWire.REPLY,
        )
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun requestsCarryWhatTheOtherSideReads() {
        val t = DirectWire.Target(listOf("tel:+2"), null, null, "tel:+1")
        val send = JSONObject(DirectWire.send(t, "hello", null).toString())
        assertEquals("hello", send.getString("text"))
        assertTrue(send.isNull("replyGuid"))
        val unsend = JSONObject(DirectWire.unsend(t, "GUID-1", 0).toString())
        assertEquals("GUID-1", unsend.getString("guid"))
        assertEquals(0, unsend.getInt("part"))
        assertEquals(t, DirectWire.Target.fromJson(unsend.getJSONObject("target")))
    }

    @Test
    fun anInlineReplyNamesTheAnsweredMessageAndItsText() {
        val t = DirectWire.Target(listOf("tel:+1"), null, null, "tel:+2")
        val send = JSONObject(DirectWire.send(t, "yes", "GUID-A", DirectWire.replyPart("are you coming?")).toString())
        assertEquals("GUID-A", send.getString("replyGuid"))
        // Part 0, from its start to the end of its 15 characters, as OpenBubbles writes it.
        assertEquals("0:0:15", send.getString("replyPart"))
        assertTrue(JSONObject(DirectWire.send(t, "hi", null).toString()).isNull("replyPart"))
    }

    @Test
    fun errorsAreTellableFromAnswers() {
        assertEquals("nope", DirectWire.errorOf(DirectWire.error("nope")))
        assertNull(DirectWire.errorOf(DirectWire.ok()))
        assertNull(DirectWire.errorOf(DirectWire.sent("G", 1)))
        assertFalse(DirectWire.sent("G", 1).has("error"))
    }

    @Test
    fun onlyTheImessageProcessIsRecognised() {
        assertTrue(DirectProcess.isImessageProcess("com.lightdecoder:imessage", "com.lightdecoder"))
        assertFalse(DirectProcess.isImessageProcess("com.lightdecoder", "com.lightdecoder"))
        assertFalse(DirectProcess.isImessageProcess("com.lightdecoder:discord", "com.lightdecoder"))
        assertFalse(DirectProcess.isImessageProcess(null, "com.lightdecoder"))
    }

    private val game = IMessageExtra.App(
        name = "GamePigeon", appId = 1124197642L, bundleId = "com.apple.messages.MSMessageExtensionBalloonPlugin:EWFNLB79LQ:com.gamerdelights.gamepigeon.ext",
        url = "data:?ver=52&data=a%2Bb/c\"d", session = "0f8fad5b-d9cb-469f-a165-70867728950e", ldText = "8 Ball", isLive = true,
        imageTitle = "t", imageSubtitle = "st", caption = "Your move", subcaption = "sc", secondarySubcaption = "2", tertiarySubcaption = "3",
        root = "imsg:ROOT",
    )

    @Test
    fun anIMessageAppsUpdateSurvivesTheTripWithItsAddressAsItWas() {
        val target = DirectWire.Target(listOf("tel:+15550000002"), null, null, "tel:+15550000001", "G9")
        val r = DirectWire.ExtensionRequest.fromJson(JSONObject(DirectWire.extension(target, "ROOT", false, game, "/data/x.jpeg").toString()))
        assertEquals(target, r.target)
        assertEquals("ROOT", r.rootGuid)
        assertFalse(r.isMeta)
        assertEquals(game, r.app)
        assertEquals("data:?ver=52&data=a%2Bb/c\"d", r.app.url)
        assertEquals("/data/x.jpeg", r.imagePath)
        val bare = DirectWire.ExtensionRequest.fromJson(JSONObject(DirectWire.extension(target, "ROOT", true, game.copy(appId = null), null).toString()))
        assertNull(bare.imagePath)
        assertTrue(bare.isMeta)
        assertNull(bare.app.appId)
        // Answered as a send is.
        val reply = DirectWire.SendReply.fromJson(JSONObject(DirectWire.sent("MV1", 5, listOf(DirectWire.SentPart(1, "p1", "image/jpeg", "jpeg-image.jpeg", 9))).toString()))
        assertEquals("p1", reply.parts.single().partId)
    }

    @Test
    fun aNewCardsSendCarriesItsAppAndAPlainSendNone() {
        val target = DirectWire.Target(listOf("tel:+15550000002"), null, null, "tel:+15550000001")
        val card = DirectWire.SendRequest.fromJson(
            JSONObject(DirectWire.send(target, "", null, attachments = listOf(DirectWire.Attachment("/p.jpeg", "image/jpeg", "jpeg-image.jpeg")), app = game).toString()),
        )
        assertEquals(game, card.app)
        assertEquals("/p.jpeg", card.attachments.single().path)
        assertNull(DirectWire.SendRequest.fromJson(JSONObject(DirectWire.send(target, "hi", null).toString())).app)
    }

    @Test
    fun formattingTravelsWithASendAnEditAndAnIncomingEdit() {
        val t = DirectWire.Target(listOf("tel:+2"), null, null, "tel:+1")
        val runs = listOf(TextRun(0, 3, bold = true, italic = true), TextRun(4, 9, effect = TextEffectKind.EXPLODE))
        val send = DirectWire.SendRequest.fromJson(JSONObject(DirectWire.send(t, "Hey there", null, runs = runs).toString()))
        assertEquals(runs, send.runs)
        assertEquals(emptyList<TextRun>(), DirectWire.SendRequest.fromJson(JSONObject(DirectWire.send(t, "hi", null).toString())).runs)
        val edit = DirectWire.EditRequest.fromJson(JSONObject(DirectWire.edit(t, "G1", 0, "Hey there", runs).toString()))
        assertEquals(DirectWire.EditRequest(t, "G1", 0, "Hey there", runs), edit)
        assertEquals(emptyList<TextRun>(), DirectWire.EditRequest.fromJson(JSONObject(DirectWire.edit(t, "G1", 0, "plain").toString())).runs)
        val incoming = DirectArchive.Update.Edit("imsg:G1", "Hey there", 0, 5_000, runs)
        assertEquals(incoming, trip(incoming))
    }

    // --- a group's name, members and photo; a chat's own read receipts ------------------------------

    @Test
    fun theGroupRequestsHaveCodesOfTheirOwnAfterTheOnesBefore() {
        assertEquals(13, DirectWire.RENAME)
        assertEquals(14, DirectWire.MEMBERS)
        assertEquals(15, DirectWire.GROUP_PHOTO)
        val codes = listOf(
            DirectWire.SEND, DirectWire.UNSEND, DirectWire.REACH, DirectWire.EDIT, DirectWire.REACT, DirectWire.TYPING,
            DirectWire.READ, DirectWire.DOWNLOAD, DirectWire.EXTENSION, DirectWire.UPDATE, DirectWire.BACKGROUND,
            DirectWire.UNSCHEDULE, DirectWire.RENAME, DirectWire.MEMBERS, DirectWire.GROUP_PHOTO, DirectWire.REPLY,
        )
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun aGroupsRenameMembersAndPhotoSurviveTheTrip() {
        val t = DirectWire.Target(listOf("tel:+15550000002", "mailto:carol@example.com"), "Trip", "G-1", "tel:+15550000001", "G-LAST")
        val rename = DirectWire.RenameRequest.fromJson(JSONObject(DirectWire.rename(t, "Ski “trip” ✓").toString()))
        assertEquals(DirectWire.RenameRequest(t, "Ski “trip” ✓"), rename)
        assertEquals("", DirectWire.RenameRequest.fromJson(JSONObject(DirectWire.rename(t, "").toString())).name)
        val members = listOf("tel:+15550000002", "mailto:carol@example.com", "tel:+15550000004", "tel:+15550000001")
        assertEquals(DirectWire.MembersRequest(t, members, 7), DirectWire.MembersRequest.fromJson(JSONObject(DirectWire.members(t, members, 7).toString())))
        // Leaving: the list without the user's own address.
        val leave = DirectWire.MembersRequest.fromJson(JSONObject(DirectWire.members(t, members.dropLast(1), 8).toString()))
        assertEquals(members.dropLast(1), leave.participants)
        assertEquals(8L, leave.groupVersion)
        val photo = DirectWire.GroupPhotoRequest.fromJson(JSONObject(DirectWire.groupPhoto(t, "/data/x/imessage/group-photos/outgoing-1.jpg", 3).toString()))
        assertEquals(DirectWire.GroupPhotoRequest(t, "/data/x/imessage/group-photos/outgoing-1.jpg", 3), photo)
        assertNull(DirectWire.GroupPhotoRequest.fromJson(JSONObject(DirectWire.groupPhoto(t, null, 4).toString())).path)
        // An older main process's request names no version: 0, as OpenBubbles starts.
        assertEquals(0L, DirectWire.MembersRequest.fromJson(JSONObject().put("target", t.toJson()).put("participants", org.json.JSONArray(members))).groupVersion)
    }

    @Test
    fun aReadCarriesTheChatsOwnReceiptsChoiceOnlyWhenItHasOne() {
        val t = DirectWire.Target(listOf("tel:+2"), null, null, "tel:+1")
        assertNull(DirectWire.ReadRequest.fromJson(JSONObject(DirectWire.read(t, "G2").toString())).receipts)
        assertFalse(JSONObject(DirectWire.read(t, "G2").toString()).has("receipts"))
        assertEquals(DirectWire.ReadRequest(t, "G2", true), DirectWire.ReadRequest.fromJson(JSONObject(DirectWire.read(t, "G2", true).toString())))
        assertEquals(false, DirectWire.ReadRequest.fromJson(JSONObject(DirectWire.read(t, "G2", false).toString())).receipts)
    }

    @Test
    fun aGroupsVersionAndPhotoChangeSurviveTheTrip() {
        val conv = ConversationDto(
            id = "imsg:group:G-1", name = "Trip", isGroup = true, type = DirectArchive.TRANSPORT,
            participants = listOf(ParticipantDto(id = "tel:+15550000002"), ParticipantDto(id = "tel:+15550000001", isMe = true)),
        )
        val row = MessageDto(
            id = "imsg:IC1", conversationId = conv.id, senderId = "tel:+15550000002", senderName = "Bob", timestampMs = 5,
            direction = "system", transport = DirectArchive.TRANSPORT, text = "Bob changed the group photo.",
            parts = listOf(PartDto("0", "text", "Bob changed the group photo.")),
        )
        val versioned = DirectArchive.Update.Upsert(conv, row, member = true, membersChanged = true, groupVersion = 12)
        assertEquals(12L, (trip(versioned) as DirectArchive.Update.Upsert).groupVersion)
        assertNull((trip(DirectArchive.Update.Upsert(conv, row)) as DirectArchive.Update.Upsert).groupVersion)
        val photo = DirectArchive.Update.GroupPhoto(conv.id, "G-1", 12, "/data/x/imessage/group-photos/incoming-1.jpg", versioned)
        val back = trip(photo) as DirectArchive.Update.GroupPhoto
        assertEquals(photo.copy(row = back.row), back)
        assertEquals(versioned.message, back.row.message)
        assertEquals(12L, back.row.groupVersion)
        val removed = trip(photo.copy(path = null)) as DirectArchive.Update.GroupPhoto
        assertNull(removed.path)
        // Without who did it, or the group's guid, it is nothing to take.
        assertEquals(DirectArchive.Update.Ignore, DirectWire.decode(JSONObject(DirectWire.encode(photo).toString()).apply { remove("row") }))
        assertEquals(DirectArchive.Update.Ignore, DirectWire.decode(JSONObject(DirectWire.encode(photo).toString()).put("groupGuid", "")))
        // It crosses as it is (no message of its own to cut).
        assertEquals(DirectWire.encode(photo).toString(), DirectWire.encodeUpdate(photo).toString())
        // One to fetch: where it is, as the library describes it; one far too long to be that, none.
        val toFetch = photo.copy(path = null, file = "<plist>photo</plist>")
        assertEquals(toFetch.copy(row = (trip(toFetch) as DirectArchive.Update.GroupPhoto).row), trip(toFetch))
        assertFalse(toFetch.removed)
        assertTrue(photo.copy(path = null).removed)
        val tooLong = JSONObject(DirectWire.encode(toFetch).toString()).put("file", "x".repeat(DirectArchive.MAX_FILE_CHARS + 1))
        assertEquals("", (DirectWire.decode(tooLong) as DirectArchive.Update.GroupPhoto).file)
    }

    @Test
    fun aDownloadNobodyAskedForCrossesWithItsLimits() {
        // One the user asked for: the attachment's own limit, no time of its own.
        val asked = DirectWire.DownloadRequest.fromJson(JSONObject(DirectWire.download("<plist/>", "/data/x/imessage-media/a.jpg").toString()))
        assertEquals(DirectWire.DownloadRequest("<plist/>", "/data/x/imessage-media/a.jpg", DirectMedia.MAX_DOWNLOAD_BYTES, null), asked)
        // A sticker's: its size and time.
        val sticker = DirectWire.DownloadRequest.fromJson(JSONObject(DirectWire.download("<plist/>", "/p", DirectEngine.STICKER_MAX_BYTES, 20_000).toString()))
        assertEquals(DirectEngine.STICKER_MAX_BYTES, sticker.maxBytes)
        assertEquals(20_000L, sticker.automaticMs)
        // Never past the attachment's limit, nor a time past the longest.
        val wild = DirectWire.DownloadRequest.fromJson(JSONObject(DirectWire.download("<plist/>", "/p", Long.MAX_VALUE, Long.MAX_VALUE).toString()))
        assertEquals(DirectMedia.MAX_DOWNLOAD_BYTES, wild.maxBytes)
        assertEquals(DirectWire.MAX_AUTOMATIC_MS, wild.automaticMs)
        assertEquals(DirectMedia.MAX_DOWNLOAD_BYTES, DirectWire.DownloadRequest.fromJson(JSONObject(DirectWire.download("d", "/p", -5).toString())).maxBytes)
        // A sticker's file crosses with its change.
        val tapback = DirectArchive.Update.Reaction("imsg:G1", "+15550000002", DirectArchive.STICKER_TAPBACK, true, false, 7, "imsg:group:G", "G", "G1/tapback/+15550000002", "<plist>s</plist>")
        assertEquals(tapback, trip(tapback))
        val placed = DirectArchive.Update.Sticker("imsg:G1", "PS1", "+15550000002", false, 7, "imsg:group:G", "G", "<plist>s</plist>")
        assertEquals(placed, trip(placed))
    }

    @Test
    fun mentionsALinksPreviewAndTheNewChangesSurviveTheTrip() {
        val target = DirectWire.Target(listOf("tel:+15550000002", "tel:+15550000003"), "Trip", "G-1", "tel:+15550000001")
        val mentions = listOf(Mention(3, 6, "+15550000002"), Mention(11, 16, "carol@example.com"))
        val link = DirectWire.LinkPreviewRequest(
            "https://www.example.com/story", "https://example.com/s", "A story", "About it",
            "https://example.com/cover.jpg", "/data/user/0/app/files/imessage-media/x.jpg", "image/jpeg",
        )
        val sent = DirectWire.SendRequest.fromJson(JSONObject(DirectWire.send(target, "Hi Bob and Carol", null, mentions = mentions, link = link).toString()))
        assertEquals(mentions, sent.mentions)
        assertEquals(link, sent.link)
        // Without either: none.
        val plain = DirectWire.SendRequest.fromJson(JSONObject(DirectWire.send(target, "hi", null).toString()))
        assertTrue(plain.mentions.isEmpty())
        assertNull(plain.link)
        // A preview whose address is no web page's is none; a picture's address that is none is left out.
        val odd = DirectWire.send(target, "x", null, link = link.copy(url = "javascript:alert(1)", originalUrl = "javascript:alert(1)")).toString()
        assertNull(DirectWire.SendRequest.fromJson(JSONObject(odd)).link)
        val noPicture = DirectWire.send(target, "x", null, link = link.copy(imageUrl = "file:///etc/passwd")).toString()
        assertNull(DirectWire.SendRequest.fromJson(JSONObject(noPicture)).link!!.imageUrl)
        // An edit's mentions.
        val edit = DirectWire.EditRequest.fromJson(JSONObject(DirectWire.edit(target, "G", 0, "Hi Bob", emptyList(), listOf(Mention(3, 6, "+15550000002"))).toString()))
        assertEquals(listOf(Mention(3, 6, "+15550000002")), edit.mentions)
        // The changes from Apple.
        val kinds = listOf(
            DirectArchive.Update.Reaction("imsg:G1", "+15550000002", DirectArchive.STICKER_TAPBACK, true, false, 7_000, "imsg:group:G", "G", sticker = "G1/tapback/+15550000002"),
            DirectArchive.Update.Edit("imsg:G1", "Now Me", 0, 5, emptyList(), "tel:+15550000002", false, "", null, listOf(Mention(4, 6, "+15550000001", mine = true))),
            DirectArchive.Update.Sticker("imsg:G1", "ST-1", "+15550000002", false, 9_000, "imsg:group:G", "G"),
            DirectArchive.Update.Sticker("imsg:G1", "ST-2", "me", true, 9_500),
            DirectArchive.Update.PollVote("imsg:CARD", "tel:+15550000002", false, listOf("O1", "O2"), 10_000, "imsg:group:G", "G"),
            DirectArchive.Update.PollVote("imsg:CARD", "tel:+15550000001", true, emptyList(), 11_000),
        )
        for (k in kinds) assertEquals(k, trip(k))
        // A poll's change, its card and all; without its card or its first card's id, nothing.
        val row = DirectArchive.Update.Upsert(
            ConversationDto(id = "imsg:group:G", name = "Trip", isGroup = true, type = DirectArchive.TRANSPORT),
            MessageDto(
                id = "imsg:PC", conversationId = "imsg:group:G", senderId = "tel:+15550000002", timestampMs = 1, direction = "in",
                transport = DirectArchive.TRANSPORT, text = "Dinner?", parts = listOf(PartDto(partId = "0", kind = "text", text = "Dinner?")),
                extra = IMessageExtra(app = Polls.card(Polls.create("Dinner?", listOf("A", "B"), "+1")!!, "S", "imsg:P")).toJson(),
            ),
        )
        assertEquals(DirectArchive.Update.PollChange("imsg:P", row), trip(DirectArchive.Update.PollChange("imsg:P", row)))
        assertEquals(DirectArchive.Update.Ignore, DirectWire.decode(JSONObject().put("kind", "pollChange").put("rootId", "imsg:P")))
        assertEquals(DirectArchive.Update.Ignore, DirectWire.decode(JSONObject().put("kind", "pollChange").put("row", DirectWire.encode(row))))
        // A vote naming more choices than a poll has: no more than it can have.
        val many = JSONObject(DirectWire.encode(DirectArchive.Update.PollVote("imsg:CARD", "tel:+1", false, (0 until 80).map { "O$it" }, 1)).toString())
        assertEquals(Polls.MAX_OPTIONS, (DirectWire.decode(many) as DirectArchive.Update.PollVote).options.size)
    }

    @Test
    fun aPollsChangeTooLargeToCrossIsMadeSmallerAsAMessageIs() {
        val row = DirectArchive.Update.Upsert(
            ConversationDto(id = "imsg:group:G", name = "Trip", isGroup = true, type = DirectArchive.TRANSPORT),
            MessageDto(
                id = "imsg:PC", conversationId = "imsg:group:G", senderId = "tel:+15550000002", timestampMs = 1, direction = "in",
                transport = DirectArchive.TRANSPORT, text = "x".repeat(DirectWire.MAX_UPDATE_CHARS),
                parts = listOf(PartDto(partId = "0", kind = "text", text = "y".repeat(DirectWire.MAX_UPDATE_CHARS))),
            ),
        )
        val json = DirectWire.encodeUpdate(DirectArchive.Update.PollChange("imsg:P", row))
        assertTrue(json.toString().length <= DirectWire.MAX_UPDATE_CHARS)
        assertEquals("pollChange", json.getString("kind"))
        assertTrue(DirectWire.decode(json) is DirectArchive.Update.PollChange)
    }

    // --- the optional Apple Account (SPEC2 §3) ---------------------------------------------------------

    @Test
    fun theCodeTableIsSpec2sAndEveryCodeIsItsOwn() {
        val spec = linkedMapOf(
            "SEND" to 1, "UNSEND" to 2, "REACH" to 3, "EDIT" to 4, "REACT" to 5, "TYPING" to 6, "READ" to 7, "DOWNLOAD" to 8,
            "EXTENSION" to 9, "UPDATE" to 10, "BACKGROUND" to 11, "UNSCHEDULE" to 12, "RENAME" to 13, "MEMBERS" to 14,
            "GROUP_PHOTO" to 15, "MARK_UNREAD" to 16, "REPORT_JUNK" to 17, "PEERS" to 18, "FT_CAPABLE" to 19, "CALLER" to 20,
            "ACCOUNT" to 21, "FT_STATE" to 22, "BLOCKLIST" to 23, "PROGRESS" to 24, "CANCEL" to 25,
        )
        assertEquals(spec, DirectWire.REQUEST_CODES)
        assertEquals(DirectWire.REQUEST_CODES.size, DirectWire.REQUEST_CODES.values.toSet().size)
        assertFalse(DirectWire.REPLY in DirectWire.REQUEST_CODES.values)
        assertEquals(21, DirectWire.ACCOUNT)
        assertEquals(100, DirectWire.REPLY)
    }

    @Test
    fun theAccountsStateCrossesAsBooleansOnly() {
        val state = AccountState(signedIn = true, attention = false, icloud = true)
        val reply = JSONObject(state.toJson().toString())
        assertEquals(setOf("signedIn", "attention", "icloud", "clique", "focus", "history"), reply.keys().asSequence().toSet())
        for (key in reply.keys()) assertTrue(key, reply.get(key) is Boolean)
        assertEquals(state, AccountState.fromJson(reply))
        assertEquals(0, DirectWire.account().length())
        // Only a real true is true; not signed in, nothing else is.
        assertEquals(AccountState.SIGNED_OUT, AccountState.fromJson(JSONObject("""{"signedIn":"true","icloud":1}""")))
        assertEquals(AccountState.SIGNED_OUT, AccountState.fromJson(JSONObject("""{"signedIn":false,"icloud":true,"attention":true}""")))
        assertEquals(AccountState.SIGNED_OUT, AccountState.fromJson(DirectWire.error("LightDecoder's iMessage part could not be reached.")))
    }

    @Test
    fun theAccountRequestSaysVisibleOnlyWhenAsked() {
        assertEquals("{}", DirectWire.account().toString())
        assertEquals("""{"visible":true}""", DirectWire.account(visible = true).toString())
        assertTrue(DirectWire.AccountRequest.fromJson(JSONObject(DirectWire.account(visible = true).toString())).visible)
        // Read strictly: only a real true.
        for (bad in listOf("{}", """{"visible":false}""", """{"visible":"true"}""", """{"visible":1}""", """{"visible":[true]}""")) {
            assertFalse(bad, DirectWire.AccountRequest.fromJson(JSONObject(bad)).visible)
        }
    }

    @Test
    fun theAccountUpdateSurvivesTheTrip() {
        val u = DirectArchive.Update.Account(AccountState(signedIn = true, attention = true))
        assertEquals(u, trip(u))
        assertEquals("account", DirectWire.encodeUpdate(u).getString("kind"))
        assertEquals(DirectArchive.Update.Account(AccountState.SIGNED_OUT), trip(DirectArchive.Update.Account(AccountState.SIGNED_OUT)))
        assertEquals(DirectArchive.Update.Account(AccountState.SIGNED_OUT), DirectWire.decode(JSONObject("""{"kind":"account","signedIn":"yes"}""")))
        // Told right before the process ends for its restart: said, and read strictly.
        val restarting = DirectArchive.Update.Account(AccountState.SIGNED_OUT, restarting = true)
        assertEquals(restarting, trip(restarting))
        assertFalse(DirectWire.encodeUpdate(u).has("restarting"))
        for (bad in listOf("\"true\"", "1", "false", "null")) {
            assertEquals(bad, DirectArchive.Update.Account(AccountState.SIGNED_OUT), DirectWire.decode(JSONObject("""{"kind":"account","restarting":$bad}""")))
        }
    }

    @Test
    fun theAccountAnswerSaysARestartIsPendingOnlyWhenItIs() {
        val state = AccountState(signedIn = true, icloud = true)
        val pending = JSONObject(DirectWire.accountReply(state, restartPending = true).toString())
        assertTrue(DirectWire.restartPendingOf(pending))
        assertEquals(state, AccountState.fromJson(pending))
        for (key in pending.keys()) assertTrue(key, pending.get(key) is Boolean)
        val none = JSONObject(DirectWire.accountReply(AccountState.SIGNED_OUT, restartPending = false).toString())
        assertFalse(DirectWire.restartPendingOf(none))
        assertEquals(AccountState.SIGNED_OUT.toJson().toString(), none.toString())
        for (bad in listOf("""{"restartPending":"true"}""", """{"restartPending":1}""", "{}")) assertFalse(bad, DirectWire.restartPendingOf(JSONObject(bad)))
        assertFalse(DirectWire.restartPendingOf(DirectWire.error("LightDecoder's iMessage part could not be reached.")))
    }

    // --- FaceTime II (SPEC2 B2) ---------------------------------------------------------------------

    @Test
    fun whereFaceTimeStandsIsAskedWithNothingAndAnsweredStrictly() {
        assertEquals(22, DirectWire.FT_STATE)
        assertEquals("{}", DirectWire.faceTimeState().toString())
        val state = FaceTimeState(live = true, guid = CALL, title = "Alice · Group FaceTime", video = true, group = true, sinceMs = 1_700_000_000_000)
        assertEquals(state, FaceTimeState.fromJson(JSONObject(state.toJson().toString())))
        assertEquals(FaceTimeState.NONE, FaceTimeState.fromJson(JSONObject(FaceTimeState.NONE.toJson().toString())))
        // Never an address or anything else in it.
        assertEquals(setOf("live", "guid", "title", "video", "group", "sinceMs"), state.toJson().keys().asSequence().toSet())
        assertEquals(setOf("live", "video", "group", "sinceMs"), FaceTimeState.NONE.toJson().keys().asSequence().toSet())
        // Read strictly: live only for a real true and a call's id; a title of one short line; no time before none.
        for (bad in listOf("""{"live":"true","guid":"$CALL"}""", """{"live":true}""", """{"live":true,"guid":"x"}""", """{"live":1,"guid":"$CALL"}""")) {
            assertEquals(bad, FaceTimeState.NONE, FaceTimeState.fromJson(JSONObject(bad)))
        }
        val odd = FaceTimeState.fromJson(JSONObject().put("live", true).put("guid", CALL).put("title", "A\nB" + "c".repeat(500)).put("sinceMs", -5).put("video", "yes"))
        assertEquals(com.lightdecoder.imessage.facetime.CallIds.MAX_NAME_CHARS, odd.title.length)
        assertFalse(odd.title.contains('\n'))
        assertEquals(0L, odd.sinceMs)
        assertFalse(odd.video)
        assertEquals(FaceTimeState.NONE, FaceTimeState.fromJson(DirectWire.error("not connected")))
    }

    @Test
    fun whereFaceTimeStandsCrossesAsACallstateUpdate() {
        val u = DirectArchive.Update.FaceTime(FaceTimeState(live = true, guid = CALL, title = "Alice", video = false, group = false, sinceMs = 5))
        assertEquals(u, trip(u))
        assertEquals("callstate", DirectWire.encodeUpdate(u).getString("kind"))
        assertEquals(DirectArchive.Update.FaceTime(FaceTimeState.NONE), trip(DirectArchive.Update.FaceTime(FaceTimeState.NONE)))
        assertEquals(DirectArchive.Update.FaceTime(FaceTimeState.NONE), DirectWire.decode(JSONObject("""{"kind":"callstate","live":true,"guid":"nope"}""")))
    }

    @Test
    fun aGroupCallsRowCrossesWithItsPeopleOnly() {
        val members = listOf("tel:+15551234567", "tel:+15557654321", "mailto:carol@example.com")
        val u = DirectArchive.Update.Call(CALL, members[0], outgoing = false, video = true, startedMs = 5, durationMs = 0, outcome = "missed", group = true, members = members)
        assertEquals(u, trip(u))
        // A one-to-one call says nothing of members.
        val one = u.copy(group = false, members = emptyList())
        assertEquals(one, trip(one))
        assertFalse(DirectWire.encode(one).has("members"))
        assertEquals("members only with a group", one, DirectWire.decode(DirectWire.encode(u).put("group", false)))
        // Only addresses, each once, a group call's worth at most.
        val odd = DirectWire.decode(
            DirectWire.encode(u).put("members", org.json.JSONArray(listOf("temp:x", "javascript:x", members[0], members[0]) + List(60) { "tel:+1555600%04d".format(it) })),
        ) as DirectArchive.Update.Call
        assertEquals(members[0], odd.members.first())
        assertEquals(odd.members.distinct(), odd.members)
        assertTrue(odd.members.size <= DirectWire.MAX_FT_TARGETS)
        assertTrue(odd.members.all { com.lightdecoder.imessage.facetime.CallIds.handle(it) != null })
    }

    @Test
    fun whoCanBeCalledIsAskedForAGroupsWorth() {
        assertEquals(32, DirectWire.MAX_FT_TARGETS)
        val many = List(40) { "tel:+1555600%04d".format(it) }
        assertEquals(many.take(32), DirectWire.CapableRequest.fromJson(JSONObject(DirectWire.faceTimeCapable(many, null).toString())).targets)
        assertEquals(many.take(32), DirectWire.capableOf(JSONObject(DirectWire.capable(many).toString())))
    }

    private companion object {
        const val CALL = "BBBBBBBB-0000-4000-8000-000000000002"
    }
    // --- B4: progress and cancel ---------------------------------------------------------------------

    @Test
    fun progressGoesAndComesBack() {
        val p = DirectWire.Progress.fromJson(JSONObject(DirectWire.progress(500, 1000, 1, 3).toString()))!!
        assertEquals(DirectWire.Progress(500, 1000, 1, 3), p)
        assertEquals(0.5f, p.fraction, 0.0001f)
        assertEquals(24, DirectWire.PROGRESS)
        assertEquals(25, DirectWire.CANCEL)
    }

    @Test
    fun badProgressNumbersAreClampedOrRefused() {
        fun read(json: String) = DirectWire.Progress.fromJson(JSONObject(json))
        // More done than the whole, negative, parts out of range: clamped.
        assertEquals(DirectWire.Progress(10, 10, 0, 1), read("{\"done\":99,\"total\":10}"))
        assertEquals(DirectWire.Progress(0, 0, 0, 1), read("{\"done\":-5,\"total\":-1,\"part\":-2,\"parts\":-3}"))
        assertEquals(DirectWire.Progress(1, 2, 31, 32), read("{\"done\":1,\"total\":2,\"part\":500,\"parts\":99999}"))
        assertEquals(DirectWire.MAX_PROGRESS_BYTES, read("{\"done\":1,\"total\":99999999999999}")!!.total)
        // Not whole numbers: nothing.
        assertNull(read("{\"done\":\"NaN\",\"total\":10}"))
        assertNull(read("{\"done\":1.5,\"total\":10}"))
        assertNull(read("{\"total\":10}"))
        assertNull(read("{\"done\":1,\"total\":1e3}"))
        // Never more than twice the part's own size.
        assertEquals(DirectWire.Progress(200, 200, 0, 1), DirectWire.Progress(900, 1000, 0, 1).clampedTo(100))
        assertEquals(DirectWire.Progress(9, 10, 0, 1), DirectWire.Progress(9, 10, 0, 1).clampedTo(0))
    }

    @Test
    fun aCancelNamesOnlyATransfersKey() {
        assertEquals("G-1/p2", DirectWire.cancelKeyOf(JSONObject(DirectWire.cancel(DirectWire.transferKey("G-1/p2")).toString())))
        val tmp = DirectWire.sendKey("0f8fad5b-d9cb-469f-a165-70867728950e")
        assertEquals(tmp, DirectWire.cancelKeyOf(DirectWire.cancel(tmp)))
        for (bad in listOf("", "../etc/passwd", "G/p", "G/p1/../x", "tmp:", "tmp:a/b", "x".repeat(100) + "/p1", "G1/q1")) {
            assertNull(bad, DirectWire.cancelKeyOf(DirectWire.cancel(bad)))
        }
        assertNull(DirectWire.cancelKeyOf(JSONObject().put("key", 5)))
        assertTrue(DirectWire.isCancelled(DirectWire.cancelled(true)))
        assertFalse(DirectWire.isCancelled(DirectWire.cancelled(false)))
        assertFalse(DirectWire.isCancelled(JSONObject().put("cancelled", "true")))
        assertFalse(DirectWire.isCancelled(DirectWire.error("x").put("cancelled", true)))
    }

    @Test
    fun onlyADownloadTheUserAskedForCanBeStoppedAndASendByItsLocalCopy() {
        val asked = DirectWire.DownloadRequest.fromJson(JSONObject(DirectWire.download("D", "/p", transfer = "G1/p0").toString()))
        assertEquals("G1/p0", asked.transfer)
        // An automatic one: never.
        val automatic = DirectWire.DownloadRequest.fromJson(DirectWire.download("D", "/p", 100, 1000, transfer = "G1/p0").put("transfer", "G1/p0"))
        assertNull(automatic.transfer)
        assertNull(DirectWire.DownloadRequest.fromJson(DirectWire.download("D", "/p").put("transfer", "tmp:abc")).transfer)
        val target = DirectWire.Target(listOf("tel:+15551234567"), null, null, "tel:+15550000001")
        val send = DirectWire.SendRequest.fromJson(JSONObject(DirectWire.send(target, "", null, transfer = "tmp:abc").toString()))
        assertEquals("tmp:abc", send.transfer)
        assertNull(DirectWire.SendRequest.fromJson(DirectWire.send(target, "", null, transfer = "G1/p0")).transfer)
        assertNull(DirectWire.SendRequest.fromJson(DirectWire.send(target, "", null)).transfer)
    }

    @Test
    fun aStoppedTransferAnswersAsStopped() {
        val reply = JSONObject(DirectWire.failure(Transfers.Stopped()).toString())
        assertTrue(DirectWire.isStopped(reply))
        assertEquals(Transfers.STOPPED, DirectWire.errorOf(reply))
        assertFalse(DirectWire.isStopped(DirectWire.failure(DirectEngine.ImessageError("no"))))
        assertFalse(DirectWire.isStopped(DirectWire.ok().put("stopped", true)))
    }

    @Test
    fun aTapbacksPhotoCrossesWithIt() {
        val r = DirectArchive.Update.Reaction("imsg:G", "+15551234567", "❤️", true, false, 1, "imsg:x", part = 3)
        assertEquals(3, (DirectWire.decode(JSONObject(DirectWire.encode(r).toString())) as DirectArchive.Update.Reaction).part)
        val whole = DirectWire.decode(JSONObject(DirectWire.encode(r.copy(part = 0)).toString())) as DirectArchive.Update.Reaction
        assertEquals(0, whole.part)
        val far = DirectWire.encode(r).put("part", 1_000_000)
        assertEquals(DirectArchive.MAX_PART_INDEX, (DirectWire.decode(far) as DirectArchive.Update.Reaction).part)
    }
}
